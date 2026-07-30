package io.github.nacvark.hudengine.paper;

import io.github.nacvark.hudengine.api.HudEngine;
import io.github.nacvark.hudengine.api.event.HudsReloadedEvent;
import io.github.nacvark.hudengine.core.compile.Encoding;
import io.github.nacvark.hudengine.core.compile.HudPackCompiler;
import io.github.nacvark.hudengine.core.compile.VanillaHud;
import io.github.nacvark.hudengine.core.model.Compiled;
import io.github.nacvark.hudengine.core.model.ConfigurationException;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Plugin entry point.
 *
 * Everything the engine needs lives in the plugin's own data folder: {@code huds/},
 * {@code layouts/}, {@code images/}, {@code texts/}, {@code heads/}, {@code compasses/},
 * {@code points/}, plus {@code assets/} and {@code fonts/}. Compiler output goes to {@code build/},
 * which is safe to delete and safe to leave out of version control.
 */
public final class HudEnginePlugin extends JavaPlugin {

    private static final String PACK_NAME = "HUDEngine.zip";

    private PluginLogger log;
    private Messages messages;
    private ValueRegistry values;
    private CompassPoints compass;
    private SkinFaces skins;
    private HudService service;
    private PackDelivery delivery;

    /** Where the last successful compile wrote the pack, so delivery can pick it up after a reload. */
    private Path lastPack;

    @Override
    public void onEnable() {
        log = new PluginLogger(getComponentLogger());
        saveDefaultConfig();
        messages = Messages.load(this, getConfig().getString("language", "en"));

        if (!getConfig().getBoolean("enabled", true)) {
            log.info(messages.plain("console.disabled"));
            return;
        }
        // The TTF rasteriser goes through AWT, which must not try to reach a display on a server.
        System.setProperty("java.awt.headless", "true");

        if (getConfig().getBoolean("starter-hud", true)) {
            StarterHud.installIfMissing(this, dataFolder(), log, messages);
        }

        // Registered before anything can fail, so a server whose config does not compile can still
        // run /hudengine reload after fixing it instead of having to restart.
        registerCommand();

        Compiled.Pack pack;
        try {
            pack = compile();
        } catch (ConfigurationException e) {
            // The individual problems were logged as they were found, and a stack trace would only
            // bury them: a typo in a YAML file is not a fault in the engine.
            log.error(messages.plain("console.compile-failed"), null);
            return;
        } catch (Exception e) {
            log.error(messages.plain("console.compile-failed"), e);
            return;
        }
        if (pack.huds().isEmpty()) {
            log.warn(messages.plain("console.no-huds"));
        }

        startEngine(pack);
    }

    /**
     * Brings the runtime up around a freshly compiled model.
     *
     * Also reached from {@link #reload()} when the first compile failed, so that fixing a broken
     * config and reloading actually starts the engine rather than needing a restart.
     */
    private void startEngine(Compiled.Pack pack) {
        compass = new CompassPoints(log, messages);
        applyCompassSettings();

        PlaceholderApiBridge placeholders = PlaceholderApiBridge.detect(log, messages);
        values = new ValueRegistry(placeholders);
        skins = new SkinFaces(this, log, messages,
                getConfig().getBoolean("skins.enabled", true),
                Duration.ofSeconds(getConfig().getLong("skins.timeout-seconds", 5)));

        service = new HudService(this, log, messages, skins, values, compass,
                RunStyler.create(log, messages),
                pack, defaultHuds(pack),
                getConfig().getInt("huds.tick-period", 2),
                getConfig().getBoolean("huds.disable-for-bedrock", true));
        service.start();

        delivery = PackDelivery.create(this, log, messages, getConfig());
        if (delivery != null) {
            delivery.publish(lastPack);
            delivery.start();
        }

        // Registered only once compilation succeeded, so HudEngineProvider.find() being empty is a
        // reliable signal that the engine is not usable rather than merely late.
        getServer().getServicesManager()
                .register(HudEngine.class, new HudEngineImpl(this), this, ServicePriority.Normal);

        Metrics.start(this, () -> pack, placeholders.available());

        log.info(messages.plain("console.running", "huds", Messages.list(service.availableHuds())));
    }

    private void registerCommand() {
        HudCommand command = new HudCommand(this, messages);
        var registered = getCommand("hudengine");
        if (registered != null) {
            registered.setExecutor(command);
            registered.setTabCompleter(command);
        }
    }

    @Override
    public void onDisable() {
        if (delivery != null) {
            delivery.stop();
        }
        if (service != null) {
            service.stop();
        }
        if (skins != null) {
            skins.shutdown();
        }
    }

    /* ---------------- accessors ---------------- */

    /** The live HUD runtime, or null when the engine failed to start. */
    public HudService service() {
        return service;
    }

    /** Where other plugins publish values for HUD patterns. */
    public ValueRegistry values() {
        return values;
    }

    /** Fixed and moving compass points. */
    public CompassPoints compass() {
        return compass;
    }

    /* ---------------- compilation ---------------- */

    /**
     * Recompiles the configuration in place.
     *
     * The model is swapped live, so text and positions update at once. Clients only see new
     * textures, fonts or glyph positions after they receive the pack again, which means a reconnect.
     *
     * @return lines to show whoever asked for the reload
     */
    public List<Component> reload() {
        reloadConfig();
        messages = Messages.load(this, getConfig().getString("language", "en"));
        if (compass != null) {
            applyCompassSettings();
        }
        try {
            Compiled.Pack pack = compile();
            if (service == null) {
                // The engine never started, so this reload is what brings it up.
                startEngine(pack);
                return List.of(messages.prefixed("command.reloaded"));
            }
            service.swap(pack, defaultHuds(pack));
            if (delivery != null) {
                // Re-hash before anyone can be sent the new file; a stale hash makes every client
                // re-download on every join.
                delivery.publish(lastPack);
            }
            getServer().getPluginManager().callEvent(new HudsReloadedEvent(service.availableHuds()));
            return List.of(messages.prefixed("command.reloaded"));
        } catch (ConfigurationException e) {
            log.error(messages.plain("console.reload-failed"), null);
            return List.of(messages.prefixed("command.reload-error",
                    "error", e.getMessage() + ". See the console for what to fix."));
        } catch (Exception e) {
            log.error(messages.plain("console.reload-failed"), e);
            return List.of(messages.prefixed("command.reload-error", "error", String.valueOf(e.getMessage())));
        }
    }

    private void applyCompassSettings() {
        compass.loadFixed(dataFolder().resolve("points"));
        compass.configure(getConfig().getBoolean("compass.height-aware", false),
                getConfig().getBoolean("compass.flat-radius", true));
    }

    private Compiled.Pack compile() throws Exception {
        FileConfiguration config = getConfig();
        Path data = dataFolder();
        Path build = data.resolve("build");
        Files.createDirectories(build);

        Path target = packTarget(config, build);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path staging = build.resolve(PACK_NAME + ".tmp");

        HudPackCompiler.Options defaults = HudPackCompiler.Options.defaults();
        HudPackCompiler.Options options = new HudPackCompiler.Options(
                config.getString("pack.namespace", defaults.namespace()),
                config.getString("pack.boss-bar-color", defaults.bossBarColor()),
                config.getString("pack.description", defaults.packDescription()),
                config.getInt("pack.min-format", defaults.minPackFormat()),
                config.getInt("pack.max-format", defaults.maxPackFormat()),
                config.getString("pack.extra-chars", defaults.extraChars()),
                hiddenVanillaElements(config),
                bossBarLine(config, defaults));

        long start = System.nanoTime();
        HudPackCompiler.Result result = HudPackCompiler.compile(new HudPackCompiler.Request(
                data,
                config.getBoolean("pack.write-tree", false) ? build.resolve("pack") : null,
                staging,
                config.getBoolean("pack.write-manifest", true) ? build.resolve("manifest.json") : null,
                options,
                null,
                log));

        // Move into place only once the file is complete, so a web server can never hand a client
        // a half-written pack.
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        lastPack = target;

        log.info(messages.plain("console.compiled",
                "ms", (System.nanoTime() - start) / 1_000_000,
                "path", target));
        // Compiler diagnostics stay in English: they carry embedded technical data and come from the
        // platform-independent core, which has no business knowing about server languages.
        result.report().forEach(line -> log.info("  " + line));
        return result.pack();
    }

    /**
     * Which boss bar line the HUD is drawn for.
     *
     * Out-of-range values are clamped rather than refused: a wrong number here moves the HUD, and
     * moving it is easier to notice and undo than a server that will not start.
     */
    private int bossBarLine(FileConfiguration config, HudPackCompiler.Options defaults) {
        int line = config.getInt("pack.boss-bar-line", defaults.bossBarLine());
        if (line < 1 || line > Encoding.MAX_BOSS_BAR_LINE) {
            log.warn(messages.plain("console.boss-bar-line-range",
                    "max", Encoding.MAX_BOSS_BAR_LINE,
                    "value", line,
                    "fallback", defaults.bossBarLine()));
            return defaults.bossBarLine();
        }
        return line;
    }

    /**
     * Which vanilla HUD elements the pack should blank out.
     *
     * An unknown key is reported rather than ignored: someone who wrote {@code hearts} instead of
     * {@code health} would otherwise see nothing happen and no reason why.
     */
    private Set<VanillaHud> hiddenVanillaElements(FileConfiguration config) {
        if (config.contains("pack.hide-vanilla-level-text")) {
            log.warn(messages.plain("console.level-text-moved"));
        }
        ConfigurationSection section = config.getConfigurationSection("hide-vanilla-hud");
        if (section == null) {
            return Set.of();
        }
        Set<VanillaHud> hidden = EnumSet.noneOf(VanillaHud.class);
        for (String key : section.getKeys(false)) {
            if (!section.getBoolean(key, false)) {
                continue;
            }
            VanillaHud element = VanillaHud.byConfigName(key);
            if (element == null) {
                log.warn(messages.plain("console.unknown-hidden-element",
                        "key", key,
                        "known", String.join(", ", VanillaHud.configNames())));
            } else {
                hidden.add(element);
            }
        }
        return hidden;
    }

    /** The plugin own jar, so the starter HUD can be read out of it. */
    Path jarFile() {
        return getFile().toPath();
    }

    private Path dataFolder() {
        return getDataFolder().toPath();
    }

    private Path packTarget(FileConfiguration config, Path build) {
        String configured = config.getString("pack.output", "");
        return configured == null || configured.isBlank()
                ? build.resolve(PACK_NAME)
                : Path.of(configured);
    }

    private List<String> defaultHuds(Compiled.Pack pack) {
        List<String> configured = getConfig().getStringList("huds.default");
        if (configured.isEmpty()) {
            return new ArrayList<>(pack.huds().keySet());
        }
        List<String> known = new ArrayList<>();
        for (String key : configured) {
            if (pack.huds().containsKey(key)) {
                known.add(key);
            } else {
                log.warn(messages.plain("console.unknown-default-hud", "hud", key));
            }
        }
        return known;
    }
}
