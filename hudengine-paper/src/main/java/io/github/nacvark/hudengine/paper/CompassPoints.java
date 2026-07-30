package io.github.nacvark.hudengine.paper;

import io.github.nacvark.hudengine.api.HudCompass;
import io.github.nacvark.hudengine.core.runtime.HudRenderer;
import io.github.nacvark.hudengine.core.util.ConfigNode;
import io.github.nacvark.hudengine.core.util.EngineLogger;
import io.github.nacvark.hudengine.core.util.MiniYaml;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * What a compass points at. Two sources feed it.
 *
 * Fixed points come from {@code points/*.yml} — a bank, a town, a spawn:
 *
 *   bank:
 *     world: world
 *     x: 120.5
 *     y: 64        # optional, only used when height-aware is on
 *     z: -344
 *     icon: bank   # a custom-icon key from the compass; omit for the default marker
 *     radius: 300  # only show within this many blocks; 0 or absent means always
 *
 * Moving points come from providers other plugins register through the API.
 */
public final class CompassPoints implements HudCompass {

    /**
     * Below this squared horizontal distance the bearing is undefined — the player is directly above
     * or below the point — and the height correction would divide by nearly zero.
     */
    private static final double FLAT_EPSILON_SQUARED = 1.0E-6;

    private final PluginLogger log;
    private final Messages messages;
    private final List<CompassPoint> fixed = new CopyOnWriteArrayList<>();
    private final Map<String, Provider> providers = new ConcurrentHashMap<>();

    private volatile boolean heightAware;
    private volatile boolean flatRadius = true;

    CompassPoints(PluginLogger log, Messages messages) {
        this.log = log;
        this.messages = messages;
    }

    /**
     * @param heightAware fold height into the distance shown, instead of measuring on the flat
     * @param flatRadius  measure a point's radius horizontally rather than in three dimensions
     */
    void configure(boolean heightAware, boolean flatRadius) {
        this.heightAware = heightAware;
        this.flatRadius = flatRadius;
    }

    /** Loads or reloads the fixed points. */
    void loadFixed(Path folder) {
        List<CompassPoint> loaded = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                    ConfigNode root = MiniYaml.parse(file);
                    root.asMap().forEach((key, node) -> loaded.add(new CompassPoint(
                            node.str("world", "world"),
                            node.dbl("x", 0),
                            node.has("y") ? node.dbl("y", 0) : Double.NaN,
                            node.dbl("z", 0),
                            node.str("icon", null),
                            node.dbl("radius", 0))));
                }
            } catch (Exception e) {
                log.warn(messages.plain("console.points-unreadable", "path", folder, "error", e));
            }
        }
        fixed.clear();
        fixed.addAll(loaded);
    }

    @Override
    public void register(String id, Provider provider) {
        providers.put(id, provider);
    }

    @Override
    public void unregister(String id) {
        providers.remove(id);
    }

    @Override
    public boolean isRegistered(String id) {
        return providers.containsKey(id);
    }

    /** Points visible to a player right now, as offsets from them. */
    public List<HudRenderer.CompassPointView> viewsFor(Player player) {
        Location location = player.getLocation();
        String world = player.getWorld().getName();
        double px = location.getX();
        double py = location.getY();
        double pz = location.getZ();

        boolean useHeight = heightAware;   // snapshot, so a reload mid-pass cannot split behaviour
        boolean radiusIsFlat = flatRadius;

        List<HudRenderer.CompassPointView> out = new ArrayList<>();
        Consumer<CompassPoint> add =
                point -> addView(point, out, world, px, py, pz, useHeight, radiusIsFlat);

        fixed.forEach(add);
        for (Map.Entry<String, Provider> entry : providers.entrySet()) {
            try {
                Collection<CompassPoint> points = entry.getValue().points(player);
                if (points != null) {
                    points.forEach(add);
                }
            } catch (RuntimeException e) {
                log.warn(messages.plain("console.compass-provider-failed", "id", entry.getKey(), "error", e));
            }
        }
        return out;
    }

    /**
     * Turns a point into a view, applying the radius filter and the height correction.
     *
     * The renderer only ever knows a point's horizontal offset: the bearing is
     * {@code atan2(dz, dx)} and the label is the length of that offset. A point a hundred blocks
     * overhead would therefore read as "0" and leave the player standing on the marker wondering
     * where it went. Rather than teach the renderer about height, the point is pushed out along the
     * same horizontal direction until its flat distance equals its true distance:
     *
     *   k = d3 / flat = sqrt(1 + dy² / flat²)   then   dx *= k, dz *= k
     *
     * The bearing survives untouched, since {@code atan2(dz·k, dx·k) == atan2(dz, dx)} for k &gt; 0.
     */
    private static void addView(CompassPoint point, List<HudRenderer.CompassPointView> out, String world,
                                double px, double py, double pz,
                                boolean heightAware, boolean flatRadius) {
        if (point == null || point.world() == null || !world.equals(point.world())) {
            return;
        }
        double dx = point.x() - px;
        double dz = point.z() - pz;
        double flatSquared = dx * dx + dz * dz;
        double radiusSquared = point.radius() * point.radius();

        if (!heightAware || !point.hasHeight()) {
            if (point.radius() > 0 && flatSquared > radiusSquared) {
                return;
            }
            out.add(new HudRenderer.CompassPointView(dx, dz, point.icon()));
            return;
        }

        double dy = point.y() - py;
        // Filter before taking a square root: a flat radius is a cylinder, a full one is a sphere.
        double measured = flatRadius ? flatSquared : flatSquared + dy * dy;
        if (point.radius() > 0 && measured > radiusSquared) {
            return;
        }
        if (flatSquared < FLAT_EPSILON_SQUARED) {
            // Directly above or below: there is no direction to pick, so put it north at its
            // vertical distance rather than letting the correction blow up.
            out.add(new HudRenderer.CompassPointView(0, -Math.abs(dy), point.icon()));
            return;
        }
        double k = Math.sqrt(1.0 + (dy * dy) / flatSquared);
        out.add(new HudRenderer.CompassPointView(dx * k, dz * k, point.icon()));
    }
}
