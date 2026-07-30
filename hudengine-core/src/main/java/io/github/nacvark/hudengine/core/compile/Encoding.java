package io.github.nacvark.hudengine.core.compile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a HUD element's screen position survives the trip through the vanilla client.
 *
 * The client offers exactly one place to put arbitrary content — the boss bar title — and no way to
 * say where on screen it should land. The position therefore travels inside the glyph itself and is
 * unpacked in a shader:
 *
 * - A glyph is given an {@code ascent} of
 *   {@code -(((stateId + 2^MAX_BIT) << HEIGHT_BIT) + ADD_OFFSET + y)}, which is large enough
 *   that the client draws it millions of pixels below the screen.
 * - The generated {@code rendertype_text} vertex shader sees a vertex below the GUI, reads the
 *   state id out of the high bits and the screen Y out of the low ones, subtracts the offset to
 *   bring the glyph back, and applies that state's anchor, layer and outline flag.
 * - Horizontal position comes from space glyphs with negative advances. The total advance of the
 *   whole bar is always zero, so the client's centring puts the string's origin exactly at
 *   screen centre and the shader shifts it to the left edge.
 *
 * These constants are part of the contract with the generated shader: changing one here without
 * changing {@link ShaderGen} produces a pack that renders in the wrong place or not at all.
 */
public final class Encoding {

    private Encoding() {
    }

    /** Bits reserved for the screen Y inside the encoded ascent. */
    public static final int HEIGHT_BIT = 13;

    /** Bit that marks an ascent as ours, so the shader leaves ordinary text alone. */
    public static final int MAX_BIT = 10;

    /** Constant added so that the encoded value never collides with a plausible real ascent. */
    public static final int ADD_OFFSET = 4095;

    /** Baseline of the first boss bar line in GUI coordinates. */
    public static final int DEFAULT_OFFSET = 10;

    /**
     * Distance between two boss bar lines, title included.
     *
     * The client stacks bars with {@code y += 10 + font.lineHeight}, and {@code lineHeight} is 9.
     * Checked against the shipped client classes for 1.21.11, 26.1.2 and 26.2, which agree.
     */
    public static final int BOSS_BAR_LINE_HEIGHT = 19;

    /** Highest line the client will draw a boss bar on. */
    public static final int MAX_BOSS_BAR_LINE = 7;

    /**
     * Baseline for a HUD riding on the {@code line}-th boss bar, counting from one.
     *
     * The shader measures down from the top of the screen, so a HUD that ends up one line lower than
     * it was built for is drawn one line too high. Servers where another plugin reliably claims the
     * first line can compensate here.
     */
    public static int defaultOffset(int line) {
        if (line < 1 || line > MAX_BOSS_BAR_LINE) {
            throw new IllegalArgumentException(
                    "boss bar line " + line + " is outside 1.." + MAX_BOSS_BAR_LINE);
        }
        return DEFAULT_OFFSET + BOSS_BAR_LINE_HEIGHT * (line - 1);
    }

    /** Highest screen Y that still fits in {@link #HEIGHT_BIT} bits, with headroom for tall glyphs. */
    public static final int MAX_Y = (1 << HEIGHT_BIT) - ADD_OFFSET - 1 - 64;

    /** Ascent for a glyph belonging to {@code stateId} whose top sits at {@code y} on screen. */
    public static int ascent(int stateId, int y) {
        if (y < 0 || y > MAX_Y) {
            throw new IllegalArgumentException("y=" + y + " is outside the encodable range 0.." + MAX_Y);
        }
        return -(((stateId + (1 << MAX_BIT)) << HEIGHT_BIT) + ADD_OFFSET + y);
    }

    /** Inverse of {@link #ascent}: {@code {stateId, y}}. Used to verify generated packs. */
    public static int[] decode(int ascent) {
        long value = -(long) ascent;
        int bits = (int) (value >> HEIGHT_BIT);
        int stateId = bits - (1 << MAX_BIT);
        int y = (int) (value - ((long) bits << HEIGHT_BIT) - ADD_OFFSET);
        return new int[]{stateId, y};
    }

    /* ---------------- render states ---------------- */

    /**
     * A combination the shader has to tell apart: anchor in percent of the GUI, z layer, and whether
     * the glyph draws with an outline. Each distinct combination becomes one case in the shader.
     */
    public record RenderState(double anchorX, double anchorY, int layer, boolean outline) {
    }

    /** Assigns a stable id to each distinct render state, in first-seen order. */
    public static final class StateTable {

        private final Map<RenderState, Integer> ids = new LinkedHashMap<>();

        public int idOf(RenderState state) {
            return ids.computeIfAbsent(state, s -> ids.size() + 1);
        }

        public List<Map.Entry<RenderState, Integer>> entries() {
            return new ArrayList<>(ids.entrySet());
        }

        public int size() {
            return ids.size();
        }
    }

    /* ---------------- codepoint allocation ---------------- */

    /** Base of the space font: {@code cp = SPACE_BASE + SPACE_RANGE + advance}. */
    public static final int SPACE_BASE = 0xF0000;

    /** Largest single horizontal step, in pixels, that one space glyph can express. */
    public static final int SPACE_RANGE = 8192;

    /** Where content glyphs start, in Supplementary Private Use Area-A. */
    public static final int GLYPH_BASE = 0xF5000;

    /** Hands out codepoints in sequence. One pool covers a whole compilation. */
    public static final class CharPool {

        private int next = GLYPH_BASE;

        public int next() {
            return next++;
        }

        public static String str(int codePoint) {
            return new String(Character.toChars(codePoint));
        }
    }
}
