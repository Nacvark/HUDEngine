package io.github.nacvark.hudengine.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The compiled HUD: the typed contract between the compiler and the renderer.
 *
 * The compiler builds this in memory at startup and the renderer walks it every tick. The JSON
 * manifest the compiler can also emit is derived from this model and exists only for inspection.
 */
public final class Compiled {

    private Compiled() {
    }

    /**
     * @param spaceBase a space glyph's codepoint is {@code spaceBase + spaceRange + advance}
     */
    public record Pack(
            String namespace,
            String hudFont,
            String spaceFont,
            int spaceBase,
            int spaceRange,
            String bossBarColor,
            List<State> states,
            Map<String, Hud> huds
    ) {
    }

    /** One shader branch: an anchor in percent of the GUI, a z layer, and whether to outline. */
    public record State(int id, double anchorX, double anchorY, int layer, boolean outline) {
    }

    public record Hud(String key, List<Element> elements) {
    }

    /** A glyph reference: its codepoint plus its advance in GUI pixels. */
    public record Glyph(int cp, int width) {
    }

    public sealed interface Element permits Img, Bar, Follow, Head, Text, CompassEl {
        String key();

        /** Absolute X from the anchor, i.e. {@code layout.x + element.x}. */
        double x();
    }

    /** A static sprite. {@code condition} names a value; the sprite shows only when it is truthy. */
    public record Img(String key, double x, List<Glyph> parts, int cols, String condition)
            implements Element {
    }

    /** A progress bar: {@code frames} is the full set of fill steps, picked by value over maximum. */
    public record Bar(String key, double x, String valueKey, String maxKey, List<Glyph> frames,
                      String condition) implements Element {
    }

    /** Picks one child sprite by the current value of a placeholder. */
    public record Follow(String key, double x, String placeholderKey,
                         Map<String, Glyph> children, String condition) implements Element {
    }

    /** The player's face: one glyph per row, coloured per pixel by the renderer. */
    public record Head(String key, double x, int pixel, int pixelAdvance, List<Integer> rowCps)
            implements Element {
    }

    /**
     * @param glyphs source codepoint to the pack glyph that draws it
     */
    public record Text(String key, double x, String align, int color, boolean outline,
                       int spaceAdvance, int height,
                       List<Seg> segments,
                       Map<Integer, Glyph> glyphs,
                       String colorByKey,
                       Map<String, Integer> colorBy) implements Element {
    }

    /** One piece of a text pattern: either a literal or a placeholder key. */
    public record Seg(boolean placeholder, String value) {
    }

    /** Distance label under a compass point: glyphs plus styling and the focus threshold. */
    public record CompassDist(int x, double focus, int color, boolean outline,
                              String suffix, int spaceAdvance, Map<String, Glyph> glyphs) {
    }

    /** One compass slot: the icon's X nudge and its variants by distance from centre (0 = centre). */
    public record CompassSlot(int xOff, List<Glyph> variants) {
    }

    public record CompassEl(String key, double x, int length, int space, int div, boolean outline,
                            Map<String, CompassSlot> slots, CompassDist dist) implements Element {
    }

    /** Everything except {@code null}, {@code ""}, {@code "0"} and {@code "false"} counts as true. */
    public static boolean truthy(String value) {
        return value != null
                && !value.isEmpty()
                && !value.equals("0")
                && !value.equalsIgnoreCase("false");
    }

    /** Splits {@code "HP: [hp]/[hp_max]"} into literal and placeholder segments. */
    public static List<Seg> parsePattern(String pattern) {
        List<Seg> out = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '[') {
                int close = pattern.indexOf(']', i + 1);
                if (close > i) {
                    if (!literal.isEmpty()) {
                        out.add(new Seg(false, literal.toString()));
                        literal.setLength(0);
                    }
                    out.add(new Seg(true, normalizeKey(pattern.substring(i + 1, close))));
                    i = close + 1;
                    continue;
                }
            }
            literal.append(c);
            i++;
        }
        if (!literal.isEmpty()) {
            out.add(new Seg(false, literal.toString()));
        }
        return out;
    }

    /**
     * Normalises a placeholder key.
     *
     * Only the {@code (number)} cast hint is stripped — it tells the config reader how to treat
     * the value, not where to find it. Any namespace prefix such as {@code papi:} is deliberately
     * kept: routing a key to PlaceholderAPI or to the engine's own registry is the resolver's job,
     * and it needs the prefix to decide.
     */
    public static String normalizeKey(String raw) {
        String key = raw.strip();
        if (key.startsWith("(number)")) {
            key = key.substring("(number)".length()).strip();
        }
        return key;
    }

    /** A named Minecraft colour or {@code #RRGGBB}. Unknown input falls back to white. */
    public static int parseColor(String value) {
        if (value == null) {
            return 0xFFFFFF;
        }
        String c = value.strip().toLowerCase(Locale.ROOT);
        if (c.startsWith("#") && c.length() == 7) {
            try {
                return Integer.parseInt(c.substring(1), 16);
            } catch (NumberFormatException e) {
                return 0xFFFFFF;
            }
        }
        return switch (c) {
            case "black" -> 0x000000;
            case "dark_blue" -> 0x0000AA;
            case "dark_green" -> 0x00AA00;
            case "dark_aqua" -> 0x00AAAA;
            case "dark_red" -> 0xAA0000;
            case "dark_purple" -> 0xAA00AA;
            case "gold" -> 0xFFAA00;
            case "gray", "grey" -> 0xAAAAAA;
            case "dark_gray", "dark_grey" -> 0x555555;
            case "blue" -> 0x5555FF;
            case "green" -> 0x55FF55;
            case "aqua" -> 0x55FFFF;
            case "red" -> 0xFF5555;
            case "light_purple" -> 0xFF55FF;
            case "yellow" -> 0xFFFF55;
            default -> 0xFFFFFF;
        };
    }
}
