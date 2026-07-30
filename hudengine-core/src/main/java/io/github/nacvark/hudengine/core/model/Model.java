package io.github.nacvark.hudengine.core.model;

import io.github.nacvark.hudengine.core.util.ConfigNode;
import io.github.nacvark.hudengine.core.util.EngineLogger;
import io.github.nacvark.hudengine.core.util.MiniYaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The configuration model: what the YAML files under the HUD folder mean.
 *
 * The layout ({@code huds/}, {@code layouts/}, {@code images/}, {@code texts/}, {@code heads/},
 * {@code compasses/}, {@code points/}) is compatible with BetterHud so that an existing HUD can be
 * moved over without editing configs. See NOTICE.md.
 */
public final class Model {

    private Model() {
    }

    /* ---------------- asset definitions ---------------- */

    public enum ImageType {
        /** A single sprite. */
        SINGLE,
        /** A sprite sliced into fill steps, driven by a value and a maximum. */
        LISTENER,
        /** A set of sprites, one of which is chosen by a placeholder's value. */
        FOLLOW
    }

    /**
     * @param split    LISTENER only: how many fill steps to slice the sprite into
     * @param children FOLLOW only: placeholder value to image key
     */
    public record ImageDef(
            String key,
            ImageType type,
            String file,
            double scale,
            int split,
            String splitType,
            String listenerValue,
            String listenerMax,
            String followPlaceholder,
            Map<String, String> children
    ) {
    }

    /**
     * @param scale        glyph height in pixels at layout scale 1
     * @param mergeDefault draw vanilla bitmap glyphs first and let the TTF fill in the rest
     * @param include      language sets this font is rasterised for, on top of the always-present
     *                     Latin base; see {@code Charsets}
     */
    public record TextFontDef(
            String key,
            String file,
            double scale,
            boolean useUnifont,
            boolean mergeDefault,
            List<String> include
    ) {
    }

    public record HeadDef(String key, int pixel) {
    }

    /* ---------------- layout elements ---------------- */

    public record LayoutImage(String name, double x, double y, int layer, String condition) {
    }

    public record LayoutText(String name, String pattern,
                             double x, double y, double scale,
                             String color, String align, int outline, int layer,
                             String colorByKey, Map<String, String> colorByMap) {
    }

    public record LayoutHead(String name, double x, double y, String align, int layer) {
    }

    public record LayoutCompass(String name, double x, double y, int layer, int outline) {
    }

    /* ---------------- compass ---------------- */

    /** A compass icon: its file, its offset inside the ribbon, and its scale and opacity. */
    public record CompassIcon(String file, int x, int y, double scale, double opacity) {
    }

    /**
     * A distance label shown when its point is near the centre of the compass window, that is when
     * the point's offset from centre is within {@code focus} (a fraction of half the window).
     */
    public record CompassDistDef(String font, double scale, String color,
                                 int x, int y, int outline,
                                 double focus, String suffix) {
    }

    /**
     * A compass definition.
     *
     * {@code scaleA} and {@code scaleB} are the linear form of {@code scale-equation}: the
     * multiplier for variant {@code i} is {@code scaleA + scaleB * i}, where {@code i} runs from 0
     * at the centre of the ribbon to {@code div - 1} at its edge.
     *
     * Icon slots are {@code n}, {@code e}, {@code s}, {@code w}, {@code nw}, {@code ne},
     * {@code sw}, {@code se}, {@code chain}, {@code point}, and {@code icon:<name>}.
     */
    public record CompassDef(
            String key, int length, int space, boolean applyOpacity,
            double baseScale, double scaleA, double scaleB,
            Map<String, CompassIcon> icons,
            CompassDistDef distanceText
    ) {
        /** How many size variants each icon needs: centre plus one per column to the edge. */
        public int div() {
            return (length + 1) / 2;
        }
    }

    public record LayoutDef(String key, double x, double y,
                            List<LayoutImage> images,
                            List<LayoutText> texts,
                            List<LayoutHead> heads,
                            List<LayoutCompass> compasses) {
    }

    /** A HUD places a layout at an anchor given in percent of the screen. */
    public record HudLayoutRef(String layoutName, double anchorX, double anchorY) {
    }

    public record HudDef(String key, List<HudLayoutRef> layouts) {
    }

    /* ---------------- root ---------------- */

    public record Root(
            Map<String, ImageDef> images,
            Map<String, TextFontDef> textFonts,
            Map<String, HeadDef> heads,
            Map<String, LayoutDef> layouts,
            Map<String, HudDef> huds,
            Map<String, CompassDef> compasses,
            Path assetsDir,
            Path fontsDir,
            Path vanillaDir
    ) {
    }

    /* ---------------- loading ---------------- */

    public static Root load(Path folder, EngineLogger log) throws IOException {
        Map<String, ImageDef> images = new LinkedHashMap<>();
        Map<String, TextFontDef> textFonts = new LinkedHashMap<>();
        Map<String, HeadDef> heads = new LinkedHashMap<>();
        Map<String, LayoutDef> layouts = new LinkedHashMap<>();
        Map<String, HudDef> huds = new LinkedHashMap<>();
        Map<String, CompassDef> compasses = new LinkedHashMap<>();

        for (ConfigNode root : readAll(folder.resolve("images"))) {
            root.asMap().forEach((key, node) -> put(images, key, parseImage(key, node), "images", log));
        }
        for (ConfigNode root : readAll(folder.resolve("texts"))) {
            root.asMap().forEach((key, node) -> put(textFonts, key, new TextFontDef(
                    key,
                    node.str("file", null),
                    node.dbl("scale", 8),
                    node.bool("use-unifont", false),
                    node.bool("merge-default-bitmap", true),
                    stringList(node.child("include"))), "texts", log));
        }
        for (ConfigNode root : readAll(folder.resolve("heads"))) {
            root.asMap().forEach((key, node) ->
                    put(heads, key, new HeadDef(key, node.integer("pixel", 8)), "heads", log));
        }
        for (ConfigNode root : readAll(folder.resolve("layouts"))) {
            root.asMap().forEach((key, node) -> put(layouts, key, parseLayout(key, node), "layouts", log));
        }
        for (ConfigNode root : readAll(folder.resolve("huds"))) {
            root.asMap().forEach((key, node) -> put(huds, key, parseHud(key, node), "huds", log));
        }
        for (ConfigNode root : readAll(folder.resolve("compasses"))) {
            root.asMap().forEach((key, node) ->
                    put(compasses, key, parseCompass(key, node, log), "compasses", log));
        }

        return new Root(images, textFonts, heads, layouts, huds, compasses,
                folder.resolve("assets"), folder.resolve("fonts"), folder.resolve("vanilla"));
    }

    private static List<ConfigNode> readAll(Path dir) throws IOException {
        List<ConfigNode> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                out.add(MiniYaml.parse(file));
            }
        }
        return out;
    }

    private static final int MIN_COMPASS_LENGTH = 10;
    private static final int MAX_COMPASS_LENGTH = 360;

    private static CompassDef parseCompass(String key, ConfigNode node, EngineLogger log) {
        int length = Math.clamp(node.integer("length", MIN_COMPASS_LENGTH),
                MIN_COMPASS_LENGTH, MAX_COMPASS_LENGTH);
        int space = Math.max(0, node.integer("space", 2));
        boolean applyOpacity = node.bool("apply-opacity", false);
        double baseScale = node.dbl("scale", 1.0);
        int div = (length + 1) / 2;

        double scaleA = 1.0;
        double scaleB = 0.0;
        String equation = node.str("scale-equation", null);
        if (equation != null) {
            double[] parsed = parseLinearEquation(equation);
            if (parsed == null) {
                log.warn("compass " + key + ": scale-equation \"" + equation
                        + "\" is not linear (expected A, A + t/B or A - t*B); using 1");
            } else {
                scaleA = parsed[0];
                scaleB = parsed[1];
            }
        }
        if (node.has("edge-scale")) {
            double edge = node.dbl("edge-scale", 1.0);
            scaleA = 1.0;
            scaleB = div > 1 ? (edge - 1.0) / (div - 1) : 0.0;
        }
        if (node.has("color-equation")) {
            log.warn("compass " + key + ": color-equation is not supported yet, ignoring");
        }

        Map<String, CompassIcon> icons = new LinkedHashMap<>();
        ConfigNode files = node.child("file");
        for (String slot : List.of("n", "e", "s", "w", "nw", "ne", "sw", "se", "chain", "point")) {
            if (files.has(slot)) {
                icons.put(slot, parseCompassIcon(files.child(slot)));
            }
        }
        if (files.has("custom-icon")) {
            files.child("custom-icon").asMap()
                    .forEach((name, iconNode) -> icons.put("icon:" + name, parseCompassIcon(iconNode)));
        }

        CompassDistDef distance = null;
        if (node.has("distance-text")) {
            ConfigNode dt = node.child("distance-text");
            distance = new CompassDistDef(
                    dt.str("font", null),
                    dt.dbl("scale", 0.3),
                    dt.str("color", "white"),
                    dt.integer("x", 0), dt.integer("y", 12),
                    dt.integer("outline", 0),
                    dt.dbl("focus", 0.2),
                    dt.str("suffix", ""));
        }
        return new CompassDef(key, length, space, applyOpacity, baseScale, scaleA, scaleB, icons, distance);
    }

    private static CompassIcon parseCompassIcon(ConfigNode node) {
        return new CompassIcon(node.str("name", null),
                node.integer("x", 0), node.integer("y", 0),
                node.dbl("scale", 1.0), node.dbl("opacity", 1.0));
    }

    private static final Pattern LINEAR_EQUATION = Pattern.compile("([0-9.]+)([+-])t([*/])([0-9.]+)");

    /** {@code "1 - t/20"} to {@code {1, -0.05}}, {@code "0.8"} to {@code {0.8, 0}}, else null. */
    private static double[] parseLinearEquation(String equation) {
        String normalized = equation.trim().replace(" ", "");
        if (normalized.matches("[0-9.]+")) {
            return new double[]{Double.parseDouble(normalized), 0};
        }
        Matcher m = LINEAR_EQUATION.matcher(normalized);
        if (!m.matches()) {
            return null;
        }
        double base = Double.parseDouble(m.group(1));
        double operand = Double.parseDouble(m.group(4));
        double coefficient = m.group(3).equals("/") ? 1.0 / operand : operand;
        if (m.group(2).equals("-")) {
            coefficient = -coefficient;
        }
        return new double[]{base, coefficient};
    }

    private static ImageDef parseImage(String key, ConfigNode node) {
        ConfigNode setting = node.child("setting");
        String declared = node.str("type", "single").toLowerCase(Locale.ROOT);
        ImageType type = switch (declared) {
            case "listener" -> ImageType.LISTENER;
            // A "single" image that declares a follow placeholder is really a chooser.
            default -> setting.has("follow") ? ImageType.FOLLOW : ImageType.SINGLE;
        };

        ConfigNode listener = setting.child("listener");
        Map<String, String> children = new LinkedHashMap<>();
        setting.child("children").asMap().forEach((k, v) -> children.put(k, v.asString(null)));

        return new ImageDef(
                key, type,
                node.str("file", null),
                setting.dbl("scale", 1.0),
                node.integer("split", 0),
                node.str("split-type", "left"),
                listener.str("value", null),
                listener.str("max", null),
                setting.str("follow", null),
                children);
    }

    private static LayoutDef parseLayout(String key, ConfigNode node) {
        double layoutX = node.dbl("x", 0);
        double layoutY = node.dbl("y", 0);

        List<LayoutImage> images = new ArrayList<>();
        sortedChildren(node.child("images")).forEach(el -> images.add(new LayoutImage(
                el.str("name", null), el.dbl("x", 0), el.dbl("y", 0),
                el.integer("layer", 0), el.str("condition", null))));

        List<LayoutText> texts = new ArrayList<>();
        sortedChildren(node.child("texts")).forEach(el -> texts.add(new LayoutText(
                el.str("name", null), el.str("pattern", ""),
                el.dbl("x", 0), el.dbl("y", 0), el.dbl("scale", 1.0),
                el.str("color", "white"), el.str("align", "left"),
                el.integer("outline", 0), el.integer("layer", 0),
                el.child("color-by").str("key", null),
                el.child("color-by").has("map")
                        ? el.child("color-by").child("map").asFlatStringMap()
                        : null)));

        List<LayoutHead> heads = new ArrayList<>();
        sortedChildren(node.child("heads")).forEach(el -> heads.add(new LayoutHead(
                el.str("name", null), el.dbl("x", 0), el.dbl("y", 0),
                el.str("align", "left"), el.integer("layer", 0))));

        List<LayoutCompass> compasses = new ArrayList<>();
        sortedChildren(node.child("compasses")).forEach(el -> compasses.add(new LayoutCompass(
                el.str("name", null), el.dbl("x", 0), el.dbl("y", 0),
                el.integer("layer", 0), el.integer("outline", 0))));

        return new LayoutDef(key, layoutX, layoutY, images, texts, heads, compasses);
    }

    private static HudDef parseHud(String key, ConfigNode node) {
        List<HudLayoutRef> refs = new ArrayList<>();
        sortedChildren(node.child("layouts")).forEach(el -> refs.add(new HudLayoutRef(
                el.str("name", null), el.dbl("x", 0), el.dbl("y", 0))));
        return new HudDef(key, refs);
    }

    /**
     * Stores a definition, warning if it replaces one already loaded.
     *
     * Two files defining the same key is almost always a copy-paste accident, and the loser
     * vanishing without a word is a genuinely hard thing to notice.
     */
    private static <T> void put(Map<String, T> into, String key, T value, String folder, EngineLogger log) {
        if (into.containsKey(key)) {
            log.warn(folder + "/: \"" + key + "\" is defined more than once; the later definition wins");
        }
        into.put(key, value);
    }

    /** A YAML list of scalars. Also accepts a bare scalar, which reads as a single-entry list. */
    private static List<String> stringList(ConfigNode node) {
        if (node.isList()) {
            return node.asList().stream()
                    .map(item -> item.asString(null))
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
        }
        String single = node.asString(null);
        return single == null || single.isBlank() ? List.of() : List.of(single);
    }

    /** Children of a numbered section ({@code 1:}, {@code 2:}, ...) in numeric order. */
    private static List<ConfigNode> sortedChildren(ConfigNode section) {
        return section.asMap().entrySet().stream()
                .sorted(Comparator.comparingInt(e -> {
                    try {
                        return Integer.parseInt(e.getKey());
                    } catch (NumberFormatException ex) {
                        return Integer.MAX_VALUE;
                    }
                }))
                .map(Map.Entry::getValue)
                .toList();
    }
}
