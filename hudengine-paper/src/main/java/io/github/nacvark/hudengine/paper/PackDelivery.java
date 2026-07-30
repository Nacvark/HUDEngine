package io.github.nacvark.hudengine.paper;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Gets the built pack onto players' clients.
 *
 * Three modes, because servers differ:
 *
 * - none — build the file and stop. The server already has a way to distribute it.
 * - url — the pack is published somewhere already; send that address.
 * - host — run a small HTTP server and hand the pack out directly, so a HUD works with
 *   no infrastructure at all.
 *
 * The hash is computed from the file that was actually built, never configured by hand. A wrong
 * hash makes every client re-download the pack on every join, and a hash that has to be updated by
 * hand after every change is a wrong hash waiting to happen.
 *
 * Additional packs can be listed and are sent in the same request. Since 1.20.3 the client stacks
 * packs rather than replacing, so a server with its own textures does not have to merge anything —
 * both arrive together and the player sees one prompt.
 *
 * Where the HUD pack sits in that stack is configurable, and it matters. The HUD works by replacing
 * files the client already has: the boss bar sprites it rides on are made transparent, and
 * {@code rendertype_text} is replaced outright. Another pack touching either one wins or loses
 * depending only on order, and the symptom is specific — a yellow bar across the top of the screen
 * means another pack restored the sprites this one blanked.
 */
final class PackDelivery implements Listener {

    /** Order matters: packs later in the request are applied on top. */
    private static final String NAMESPACE = "hudengine-pack:";

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private enum Mode {
        NONE, URL, HOST
    }

    /** An extra pack sent alongside ours. */
    private record ExtraPack(UUID id, URI uri, String hash) {
    }

    private final Plugin plugin;
    private final PluginLogger log;
    private final Messages messages;

    private final Mode mode;
    private final boolean sendOnJoin;
    private final boolean required;
    private final Component prompt;
    private final Component kickMessage;
    private final List<ExtraPack> extras;

    private final String externalUrl;
    private final String hostPublicUrl;
    private final boolean sendFirst;
    private final PackHost host;

    private volatile String hash = "";
    private volatile boolean ready;

    private PackDelivery(Plugin plugin, PluginLogger log, Messages messages, FileConfiguration config,
                         PackHost host) {
        this.plugin = plugin;
        this.log = log;
        this.messages = messages;
        this.host = host;

        ConfigurationSection section = config.getConfigurationSection("resource-pack");
        this.mode = parseMode(section == null ? "none" : section.getString("delivery", "none"), log, messages);
        this.sendOnJoin = section == null || section.getBoolean("send-on-join", true);
        this.required = section != null && section.getBoolean("required", false);
        this.prompt = text(section, "prompt");
        this.kickMessage = text(section, "kick-message");
        this.externalUrl = section == null ? "" : section.getString("url", "");
        this.hostPublicUrl = section == null ? "" : section.getString("host.public-url", "");
        this.sendFirst = section != null && "first".equalsIgnoreCase(section.getString("position", "last"));
        this.extras = parseExtras(section, log, messages);
    }

    /**
     * Builds delivery from the configuration, starting the built-in host if that mode is selected.
     *
     * @return null when delivery is switched off entirely
     */
    static PackDelivery create(Plugin plugin, PluginLogger log, Messages messages, FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("resource-pack");
        Mode mode = parseMode(section == null ? "none" : section.getString("delivery", "none"), log, messages);
        if (mode == Mode.NONE) {
            return null;
        }
        PackHost host = null;
        if (mode == Mode.HOST) {
            String bind = section.getString("host.bind", "0.0.0.0");
            int port = section.getInt("host.port", 8123);
            String path = section.getString("host.path", "/hudengine.zip");
            try {
                host = PackHost.start(bind, port, path);
                log.info(messages.plain("console.host-started",
                        "url", "http://" + bind + ":" + port + path));
            } catch (IOException e) {
                log.error(messages.plain("console.host-failed",
                        "bind", bind, "port", port, "error", e.getMessage()), null);
                return null;
            }
        }
        return new PackDelivery(plugin, log, messages, config, host);
    }

    private static Mode parseMode(String value, PluginLogger log, Messages messages) {
        try {
            return Mode.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn(messages.plain("console.delivery-unknown", "value", value));
            return Mode.NONE;
        }
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    void stop() {
        HandlerList.unregisterAll(this);
        if (host != null) {
            host.stop();
        }
    }

    /**
     * Points delivery at a freshly built pack.
     *
     * Hashing happens here rather than per join: the file changes only on compile, and hashing a
     * megabyte on every login for no reason is exactly the sort of thing that is never noticed until
     * a hundred people log in at once.
     */
    void publish(Path packFile) {
        if (!Files.isRegularFile(packFile)) {
            log.warn(messages.plain("console.pack-missing", "path", packFile));
            ready = false;
            return;
        }
        try {
            hash = sha1(packFile);
            if (host != null) {
                host.publish(packFile);
            }
            ready = true;
        } catch (IOException e) {
            log.error(messages.plain("console.pack-unreadable", "error", e.getMessage()), null);
            ready = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (sendOnJoin) {
            send(event.getPlayer());
        }
    }

    /** Sends the pack, and any extras, as a single request so the player sees one prompt. */
    void send(Player player) {
        if (!ready) {
            return;
        }
        String url = url();
        if (url == null || url.isBlank()) {
            return;
        }
        ResourcePackInfo ours = ResourcePackInfo.resourcePackInfo(packId(), URI.create(url), hash);
        List<ResourcePackInfo> packs = new ArrayList<>();
        if (sendFirst) {
            packs.add(ours);
        }
        for (ExtraPack extra : extras) {
            packs.add(ResourcePackInfo.resourcePackInfo(extra.id(), extra.uri(), extra.hash()));
        }
        if (!sendFirst) {
            packs.add(ours);
        }

        ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                .packs(packs)
                .required(required)
                .replace(false);
        if (prompt != null) {
            request.prompt(prompt);
        }
        player.sendResourcePacks(request.build());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!required) {
            return;
        }
        switch (event.getStatus()) {
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                Player player = event.getPlayer();
                if (player.isOnline()) {
                    player.kick(kickMessage != null
                            ? kickMessage
                            : messages.plain("pack.kick-default"));
                }
            }
            default -> { /* accepted, downloaded, loaded or discarded: nothing to do */ }
        }
    }

    private String url() {
        return switch (mode) {
            case URL -> externalUrl;
            case HOST -> hostPublicUrl;
            case NONE -> null;
        };
    }

    /**
     * A stable id derived from the URL, so an updated pack replaces the previous one in the client's
     * list instead of stacking beside it.
     */
    private UUID packId() {
        return UUID.nameUUIDFromBytes((NAMESPACE + url()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static List<ExtraPack> parseExtras(ConfigurationSection section, PluginLogger log,
                                               Messages messages) {
        List<ExtraPack> out = new ArrayList<>();
        if (section == null) {
            return out;
        }
        List<?> configured = section.getList("extra-packs", List.of());
        for (Object entry : configured) {
            if (!(entry instanceof java.util.Map<?, ?> map)) {
                log.warn(messages.plain("console.extra-pack-not-a-map"));
                continue;
            }
            Object url = map.get("url");
            if (url == null) {
                log.warn(messages.plain("console.extra-pack-no-url"));
                continue;
            }
            String hash = map.get("hash") == null ? "" : String.valueOf(map.get("hash"));
            try {
                out.add(new ExtraPack(
                        UUID.nameUUIDFromBytes((NAMESPACE + url).getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)),
                        URI.create(String.valueOf(url)),
                        hash));
            } catch (IllegalArgumentException e) {
                log.warn(messages.plain("console.extra-pack-bad-url", "url", url));
            }
        }
        return out;
    }

    private Component text(ConfigurationSection section, String key) {
        if (section == null) {
            return null;
        }
        String value = section.getString(key, "");
        return value == null || value.isBlank() ? null : LEGACY.deserialize(value);
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required of every JVM", e);
        }
    }
}
