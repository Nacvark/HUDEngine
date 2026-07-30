package io.github.nacvark.hudengine.paper;

import io.github.nacvark.hudengine.core.model.Compiled;
import io.github.nacvark.hudengine.core.runtime.HudRenderer;
import io.github.nacvark.hudengine.core.runtime.TextMetrics;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live HUD: one invisible boss bar per player, retitled as its contents change.
 *
 * The bar is invisible because the compiled pack replaces the sprites of whichever boss bar
 * colour the HUD claims with transparent ones. What remains is the title, which is where the whole
 * HUD lives.
 *
 * Each player is ticked by their own scheduler rather than from one global loop. On a regular
 * server that is equivalent; on Folia it is the difference between working and touching a player
 * from the wrong thread.
 *
 * The bar is added at the lowest join priority so it precedes boss bars from other plugins. The
 * client stacks bars in the order it received them, and the shader measures from the first line.
 */
public final class HudService implements Listener {

    private final Plugin plugin;
    private final PluginLogger log;
    private final Messages messages;
    private final SkinFaces skins;
    private final ValueRegistry values;
    private final CompassPoints compass;
    private final RunStyler styler;
    private final int tickPeriod;
    private final boolean skipBedrock;

    /**
     * The compiled model, its renderer and the default HUD set, swapped as one.
     *
     * Held together rather than in three fields because a reload replaces all three. On Folia a tick
     * runs on the player's region thread while {@code /hudengine reload} runs on another, so three
     * separate reads could land either side of the swap and render keys from the new model against
     * the old renderer.
     */
    private record Loaded(Compiled.Pack pack, HudRenderer renderer, List<String> defaultHuds) {
    }

    private volatile Loaded loaded;

    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, HudRenderer.RenderCache> caches = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> tickers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> selections = new ConcurrentHashMap<>();
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private final Set<Integer> reportedMissing = ConcurrentHashMap.newKeySet();
    private final Map<String, ScheduledTask> timedHuds = new ConcurrentHashMap<>();

    HudService(Plugin plugin, PluginLogger log, Messages messages, SkinFaces skins, ValueRegistry values,
               CompassPoints compass, RunStyler styler,
               Compiled.Pack pack, List<String> defaultHuds,
               int tickPeriod, boolean skipBedrock) {
        this.plugin = plugin;
        this.log = log;
        this.messages = messages;
        this.skins = skins;
        this.values = values;
        this.compass = compass;
        this.styler = styler;
        this.tickPeriod = Math.max(1, tickPeriod);
        this.skipBedrock = skipBedrock;
        swap(pack, defaultHuds);
    }

    /* ---------------- lifecycle ---------------- */

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getOnlinePlayers().forEach(this::attach);
    }

    void stop() {
        tickers.values().forEach(ScheduledTask::cancel);
        tickers.clear();
        timedHuds.values().forEach(ScheduledTask::cancel);
        timedHuds.clear();
        HandlerList.unregisterAll(this);
        plugin.getServer().getOnlinePlayers().forEach(this::detach);
        bars.clear();
        caches.clear();
    }

    /** Swaps in a freshly compiled model without a restart. */
    void swap(Compiled.Pack newPack, List<String> newDefaults) {
        this.loaded = new Loaded(newPack, new HudRenderer(newPack), List.copyOf(newDefaults));
        caches.clear();
        styler.clearCache();
    }

    /* ---------------- players ---------------- */

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    /**
     * Re-attaches after a respawn.
     *
     * A player's scheduler tasks are cancelled when their entity is removed, and dying removes
     * it. Without this the HUD stops updating the moment a player dies and stays frozen on their
     * last values — health reading zero long after they respawned with full hearts.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // The entity is replaced during respawn, so the ticker has to be bound to the new one.
        ScheduledTask stale = tickers.remove(player.getUniqueId());
        if (stale != null) {
            stale.cancel();
        }
        attach(player);
        refresh(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        detach(event.getPlayer());
        bars.remove(id);
        caches.remove(id);
        skins.forget(id);
        // selections and hidden survive a relog on purpose: a player who turned the HUD off should
        // not have it come back the moment they reconnect.
    }

    private void attach(Player player) {
        if (isBedrock(player) || hidden.contains(player.getUniqueId())) {
            return;
        }
        UUID id = player.getUniqueId();
        BossBar bar = bars.computeIfAbsent(id, k ->
                BossBar.bossBar(Component.empty(), 0.0f, barColor(), BossBar.Overlay.PROGRESS));
        player.showBossBar(bar);
        caches.remove(id);
        skins.facePixels(player); // start the download now rather than on the first tick

        tickers.computeIfAbsent(id, k -> player.getScheduler().runAtFixedRate(
                plugin,
                task -> tick(player),
                () -> tickers.remove(id),
                tickPeriod, tickPeriod));
    }

    private void detach(Player player) {
        UUID id = player.getUniqueId();
        ScheduledTask ticker = tickers.remove(id);
        if (ticker != null) {
            ticker.cancel();
        }
        BossBar bar = bars.get(id);
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /** Floodgate hands Bedrock players a UUID whose high bits are zero. */
    private boolean isBedrock(Player player) {
        return skipBedrock && player.getUniqueId().getMostSignificantBits() == 0;
    }

    private BossBar.Color barColor() {
        try {
            return BossBar.Color.valueOf(loaded.pack().bossBarColor().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.YELLOW;
        }
    }

    /* ---------------- public API ---------------- */

    /** Every HUD the current configuration compiled, in compile order. */
    public List<String> availableHuds() {
        return List.copyOf(loaded.pack().huds().keySet());
    }

    /** What this player can see right now; empty when their HUD is switched off entirely. */
    public List<String> visibleHuds(Player player) {
        return isVisible(player) ? List.copyOf(hudsFor(player.getUniqueId(), loaded)) : List.of();
    }

    public boolean isVisible(Player player) {
        return !hidden.contains(player.getUniqueId()) && !isBedrock(player);
    }

    /** Turns the whole HUD on or off for one player. */
    public void setVisible(Player player, boolean visible) {
        if (visible) {
            hidden.remove(player.getUniqueId());
            attach(player);
            refresh(player);
        } else {
            hidden.add(player.getUniqueId());
            detach(player);
        }
    }

    /** @return true if the HUD is now shown */
    public boolean toggle(Player player) {
        boolean nowVisible = hidden.contains(player.getUniqueId());
        setVisible(player, nowVisible);
        return nowVisible;
    }

    /** @return false if no such HUD was compiled */
    public boolean showHud(Player player, String hudKey) {
        if (!loaded.pack().huds().containsKey(hudKey)) {
            return false;
        }
        selectionOf(player.getUniqueId()).add(hudKey);
        refresh(player);
        return true;
    }

    /** @return false if no such HUD was compiled */
    public boolean hideHud(Player player, String hudKey) {
        if (!loaded.pack().huds().containsKey(hudKey)) {
            return false;
        }
        selectionOf(player.getUniqueId()).remove(hudKey);
        refresh(player);
        return true;
    }

    /**
     * Shows a HUD for a fixed time, then hides it again. Calling it again before the timer runs out
     * extends it. For something whose duration is not known up front, use showHud and hideHud.
     *
     * @return false if no such HUD was compiled
     */
    public boolean showHudFor(Player player, String hudKey, int ticks) {
        if (!showHud(player, hudKey)) {
            return false;
        }
        String token = player.getUniqueId() + "|" + hudKey;
        ScheduledTask previous = timedHuds.remove(token);
        if (previous != null) {
            previous.cancel();
        }
        timedHuds.put(token, player.getScheduler().runDelayed(plugin, task -> {
            timedHuds.remove(token);
            hideHud(player, hudKey);
        }, null, Math.max(1, ticks)));
        return true;
    }

    /** Puts the player back on the configured default set. */
    public void resetHuds(Player player) {
        selections.remove(player.getUniqueId());
        refresh(player);
    }

    /** Recomputes and sends immediately instead of waiting for the next tick. */
    public void refresh(Player player) {
        caches.remove(player.getUniqueId());
        if (bars.containsKey(player.getUniqueId()) && player.isOnline() && isVisible(player)) {
            tick(player);
        }
    }

    /**
     * Wraps text to a pixel width using the metrics of a specific HUD text element, so the result
     * matches what the player will actually see rather than a character count.
     *
     * @throws IllegalArgumentException if the element does not exist or is not a text element
     */
    public List<String> wrapText(String hudKey, String elementKey, String text, int maxWidthPx) {
        return TextMetrics.wrap(textElement(hudKey, elementKey), text, maxWidthPx);
    }

    /** Width of a string in HUD pixels, for a specific text element. */
    public int textWidth(String hudKey, String elementKey, String text) {
        return TextMetrics.width(textElement(hudKey, elementKey), text);
    }

    private Compiled.Text textElement(String hudKey, String elementKey) {
        Compiled.Hud hud = loaded.pack().huds().get(hudKey);
        if (hud == null) {
            throw new IllegalArgumentException("no such hud: " + hudKey);
        }
        for (Compiled.Element element : hud.elements()) {
            if (element instanceof Compiled.Text text && text.key().equals(elementKey)) {
                return text;
            }
        }
        throw new IllegalArgumentException("no text element " + elementKey + " in hud " + hudKey);
    }

    /* ---------------- selection ---------------- */

    /** The player's own set, seeded from the defaults the first time they change anything. */
    private Set<String> selectionOf(UUID id) {
        return selections.computeIfAbsent(id, k -> {
            Set<String> selection = ConcurrentHashMap.newKeySet();
            selection.addAll(loaded.defaultHuds());
            return selection;
        });
    }

    /** The player's HUDs in compile order, so draw order never depends on selection order. */
    private List<String> hudsFor(UUID id, Loaded current) {
        Set<String> selection = selections.get(id);
        if (selection == null) {
            return current.defaultHuds();
        }
        List<String> out = new ArrayList<>(selection.size());
        for (String key : current.pack().huds().keySet()) {
            if (selection.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /* ---------------- ticking ---------------- */

    private void tick(Player player) {
        UUID id = player.getUniqueId();
        BossBar bar = bars.get(id);
        if (bar == null || !player.isOnline() || hidden.contains(id)) {
            return;
        }
        // Read once, so everything below belongs to the same compilation even if a reload lands
        // halfway through.
        Loaded current = loaded;
        try {
            HudRenderer.RenderCache cache =
                    caches.computeIfAbsent(id, k -> new HudRenderer.RenderCache());
            HudRenderer.Output output = current.renderer().render(
                    hudsFor(id, current),
                    values.forPlayer(player),
                    headKey -> skins.facePixels(player),
                    () -> new HudRenderer.CompassCtx(
                            player.getLocation().getYaw(), compass.viewsFor(player)),
                    cache);

            reportMissing(output);
            if (output.changed()) {
                bar.name(styler.compose(output.runs()));
            }
        } catch (RuntimeException e) {
            log.error(messages.plain("console.tick-failed", "player", player.getName()), e);
        }
    }

    /** Each unknown character is reported once, not once per tick per player. */
    private void reportMissing(HudRenderer.Output output) {
        for (int cp : output.missingChars()) {
            if (reportedMissing.add(cp)) {
                log.warn(messages.plain("console.glyph-missing",
                        "codepoint", String.format("U+%04X", cp),
                        "character", new String(Character.toChars(cp))));
            }
        }
    }
}
