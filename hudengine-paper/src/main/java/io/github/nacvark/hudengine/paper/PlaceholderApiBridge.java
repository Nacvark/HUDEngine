package io.github.nacvark.hudengine.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Optional bridge to PlaceholderAPI.
 *
 * PlaceholderAPI is reached reflectively rather than as a compile-time dependency, so the engine
 * neither ships its classes nor breaks when it is absent — which matters because a HUD engine is
 * useful on servers that have no use for placeholders at all. When it is missing, {@code papi:} keys
 * resolve to an empty string and say so once.
 */
final class PlaceholderApiBridge {

    private static final String PLUGIN_NAME = "PlaceholderAPI";
    private static final String API_CLASS = "me.clip.placeholderapi.PlaceholderAPI";

    private final PluginLogger log;
    private final Messages messages;
    private final Method setPlaceholders;
    private volatile boolean absenceReported;

    private PlaceholderApiBridge(PluginLogger log, Messages messages, Method setPlaceholders) {
        this.log = log;
        this.messages = messages;
        this.setPlaceholders = setPlaceholders;
    }

    static PlaceholderApiBridge detect(PluginLogger log, Messages messages) {
        if (Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) {
            return new PlaceholderApiBridge(log, messages, null);
        }
        try {
            Method method = Class.forName(API_CLASS)
                    .getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            log.info(messages.plain("console.papi-found"));
            return new PlaceholderApiBridge(log, messages, method);
        } catch (ReflectiveOperationException e) {
            log.warn(messages.plain("console.papi-mismatch"));
            return new PlaceholderApiBridge(log, messages, null);
        }
    }

    boolean available() {
        return setPlaceholders != null;
    }

    /** Resolves {@code name} as {@code %name%}. Never throws: a bad placeholder must not kill a tick. */
    String resolve(Player player, String name) {
        if (setPlaceholders == null) {
            if (!absenceReported) {
                absenceReported = true;
                log.warn(messages.plain("console.papi-missing"));
            }
            return "";
        }
        try {
            Object result = setPlaceholders.invoke(null, player, "%" + name + "%");
            return result == null ? "" : result.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }
}
