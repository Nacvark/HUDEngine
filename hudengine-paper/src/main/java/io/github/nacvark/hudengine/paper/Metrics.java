package io.github.nacvark.hudengine.paper;

import io.github.nacvark.hudengine.core.model.Compiled;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

import java.util.function.Supplier;

/**
 * Anonymous usage statistics through bStats.
 *
 * Reports what would change how the plugin is developed: which Minecraft versions are actually in
 * use, whether servers rely on PlaceholderAPI, and how large real HUDs get. Nothing identifies a
 * server or a player.
 *
 * Off with a single config line, and bStats has its own global opt-out in
 * plugins/bStats/config.yml that applies to every plugin at once.
 */
final class Metrics {

    /** Project id from bstats.org, under the nacvark account. */
    private static final int BSTATS_ID = 32968;

    private Metrics() {
    }

    static void start(HudEnginePlugin plugin, Supplier<Compiled.Pack> pack, boolean placeholderApi) {
        if (!plugin.getConfig().getBoolean("metrics", true)) {
            return;
        }
        org.bstats.bukkit.Metrics metrics = new org.bstats.bukkit.Metrics(plugin, BSTATS_ID);

        metrics.addCustomChart(new SimplePie("placeholderapi",
                () -> placeholderApi ? "yes" : "no"));

        metrics.addCustomChart(new SimplePie("pack_delivery",
                () -> plugin.getConfig().getString("resource-pack.delivery", "none")));

        metrics.addCustomChart(new SimplePie("language",
                () -> plugin.getConfig().getString("language", "en")));

        // How many HUDs a real configuration ends up with, which is the best available signal for
        // whether the starter example is being replaced by something substantial.
        metrics.addCustomChart(new SingleLineChart("huds",
                () -> pack.get() == null ? 0 : pack.get().huds().size()));
    }
}
