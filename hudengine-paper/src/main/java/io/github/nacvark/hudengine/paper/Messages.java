package io.github.nacvark.hudengine.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Localised text.
 *
 * Resolution runs in three steps, each falling through to the next: a {@code messages.yml} the
 * server wrote, the bundled file for the configured language, and the bundled English file. That
 * means an override file only has to contain the lines it actually changes, and a missing
 * translation degrades to English rather than to a raw key.
 */
final class Messages {

    private static final String FALLBACK_LANGUAGE = "en";
    private static final String OVERRIDE_FILE = "messages.yml";

    /** Legacy {@code &} codes, since that is what server owners already know. */
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private final YamlConfiguration override;
    private final YamlConfiguration language;
    private final YamlConfiguration fallback;
    private final String prefix;

    private Messages(YamlConfiguration override, YamlConfiguration language, YamlConfiguration fallback) {
        this.override = override;
        this.language = language;
        this.fallback = fallback;
        this.prefix = lookup("prefix");
    }

    static Messages load(Plugin plugin, String configuredLanguage) {
        String requested = configuredLanguage == null
                ? FALLBACK_LANGUAGE
                : configuredLanguage.strip().toLowerCase(Locale.ROOT);

        YamlConfiguration fallback = bundled(plugin, FALLBACK_LANGUAGE);
        YamlConfiguration language = requested.equals(FALLBACK_LANGUAGE)
                ? fallback
                : bundled(plugin, requested);
        if (language == null) {
            plugin.getLogger().warning("no bundled messages for language \"" + requested
                    + "\", falling back to English. Available: " + available(plugin));
            language = fallback;
        }
        return new Messages(onDisk(plugin), language, fallback);
    }

    private static List<String> available(Plugin plugin) {
        return List.of("en", "ru");
    }

    private static YamlConfiguration bundled(Plugin plugin, String code) {
        try (InputStream in = plugin.getResource("messages/" + code + ".yml")) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static YamlConfiguration onDisk(Plugin plugin) {
        Path file = plugin.getDataFolder().toPath().resolve(OVERRIDE_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            plugin.getLogger().warning("could not read " + OVERRIDE_FILE + ": " + e.getMessage());
            return null;
        }
    }

    /** A message with no prefix, for console output where the platform adds its own tag. */
    Component plain(String key, Object... replacements) {
        return LEGACY.deserialize(fill(lookup(key), replacements));
    }

    /** A message carrying the engine's prefix, for anything sent to a player. */
    Component prefixed(String key, Object... replacements) {
        return LEGACY.deserialize(prefix + fill(lookup(key), replacements));
    }

    private String lookup(String key) {
        if (override != null && override.isString(key)) {
            return override.getString(key);
        }
        if (language.isString(key)) {
            return language.getString(key);
        }
        String english = fallback.getString(key);
        return english != null ? english : key;
    }

    /** Replaces {@code {name}} placeholders, taking arguments as alternating name and value. */
    private static String fill(String template, Object... replacements) {
        if (replacements.length == 0) {
            return template;
        }
        if (replacements.length % 2 != 0) {
            throw new IllegalArgumentException("replacements must be name, value pairs");
        }
        String out = template;
        for (int i = 0; i < replacements.length; i += 2) {
            out = out.replace("{" + replacements[i] + "}", String.valueOf(replacements[i + 1]));
        }
        return out;
    }

    /** Joins a list for display, or a dash when it is empty. */
    static String list(List<String> values) {
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    /** Exposed so callers can build one-off strings with the same placeholder syntax. */
    static String template(String text, Map<String, Object> replacements) {
        String out = text;
        for (Map.Entry<String, Object> entry : replacements.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return out;
    }
}
