package io.github.nacvark.hudengine.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Warns when ItemsAdder is set up to replace the text shader HUDEngine relies on.
 *
 * Its text effects and scoreboard-number hiding both work by editing {@code rendertype_text}. With
 * either on, one of the two plugins' shaders replaces the other's in the final pack, and whichever
 * loses stops working with no error from either side.
 *
 * Only reads ItemsAdder's config file, so it works without ItemsAdder having enabled yet.
 */
final class ItemsAdderCheck {

    /** Older releases and the current one name the same switches differently; both are checked. */
    private static final List<String> SHADER_SETTINGS = List.of(
            "effects.text-effects.enabled",
            "effects.hide-scoreboard-numbers",
            "effects.hide-scoreboard-numbers-old-clients",
            "text_effects.enabled");

    private ItemsAdderCheck() {
    }

    static void warnIfConflicting(Plugin plugin, PluginLogger log, Messages messages) {
        Plugin itemsAdder = plugin.getServer().getPluginManager().getPlugin("ItemsAdder");
        if (itemsAdder == null) {
            return;
        }
        File config = new File(itemsAdder.getDataFolder(), "config.yml");
        if (!config.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(config);
        List<String> enabled = new ArrayList<>();
        for (String key : SHADER_SETTINGS) {
            if (yaml.getBoolean(key, false)) {
                enabled.add(key);
            }
        }
        if (!enabled.isEmpty()) {
            log.warn(messages.plain("console.itemsadder-conflict", "settings", String.join(", ", enabled)));
        }
    }
}
