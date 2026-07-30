package io.github.nacvark.hudengine.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Where a plugin publishes points for compasses to point at.
 *
 * <p>Fixed points belong in {@code points/*.yml}. This is for points that move or come and go:
 *
 * <pre>{@code
 * hud.compass().register("quests", player -> quests.activeFor(player).stream()
 *         .map(quest -> CompassPoint.at(quest.location(), "quest"))
 *         .toList());
 * }</pre>
 *
 * <p>The engine draws what a provider returns and does not ask why. Deciding whether a player has an
 * available quest is the quest plugin's job, not the HUD's.
 *
 * <p>A provider runs once per HUD tick per player, on that player's own thread. Returning null means
 * no points.
 */
public interface HudCompass {

    /** Registers a source of moving points. Registering the same id again replaces it. */
    void register(String id, Provider provider);

    void unregister(String id);

    boolean isRegistered(String id);

    /** Supplies points for one player. */
    @FunctionalInterface
    interface Provider {
        Collection<CompassPoint> points(Player player);
    }

    /**
     * Somewhere a compass can point.
     *
     * @param icon   a {@code custom-icon} key from the compass definition, or null for the default
     *               marker
     * @param radius only show within this many blocks; zero means always
     */
    record CompassPoint(String world, double x, double y, double z, String icon, double radius) {

        /** A point with no height, which is always measured on the flat. */
        public static CompassPoint flat(String world, double x, double z, String icon) {
            return new CompassPoint(world, x, Double.NaN, z, icon, 0);
        }

        /** A point at a location, with height. */
        public static CompassPoint at(Location location, String icon) {
            return new CompassPoint(location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ(), icon, 0);
        }

        /** A point at a location that only appears within {@code radius} blocks. */
        public static CompassPoint within(Location location, String icon, double radius) {
            return new CompassPoint(location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ(), icon, radius);
        }

        /** Whether this point carries a height at all. */
        public boolean hasHeight() {
            return !Double.isNaN(y);
        }
    }
}
