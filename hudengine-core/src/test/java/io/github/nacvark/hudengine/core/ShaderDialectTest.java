package io.github.nacvark.hudengine.core;

import io.github.nacvark.hudengine.core.compile.Encoding;
import io.github.nacvark.hudengine.core.compile.ShaderDialect;
import io.github.nacvark.hudengine.core.compile.ShaderGen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the generated shaders against the way this breaks in practice.
 *
 * A shader that fails to compile does not degrade gracefully — the client drops it and the HUD
 * disappears entirely, with the reason buried in a log file the server owner never sees. The checks
 * here encode what each client version requires: which imports exist, where the transforms come
 * from, and which fog helper is available.
 *
 * These are the exact things Mojang changed in 1.21.6, 1.21.9 and 26.1.
 */
class ShaderDialectTest {

    private static Encoding.StateTable states() {
        Encoding.StateTable table = new Encoding.StateTable();
        table.idOf(new Encoding.RenderState(2, 4, 0, false));
        table.idOf(new Encoding.RenderState(50, 90, 3, true));
        return table;
    }

    private static String vertex(ShaderDialect dialect) {
        return ShaderGen.vertex(dialect, states(), false);
    }

    @ParameterizedTest
    @EnumSource(ShaderDialect.class)
    void declaresTheGlslVersionTheClientExpects(ShaderDialect dialect) {
        String expected = "#version " + dialect.glslVersion();
        assertTrue(vertex(dialect).startsWith(expected), dialect + " vertex must start with " + expected);
    }

    @ParameterizedTest
    @EnumSource(ShaderDialect.class)
    void decodesTheEncodedPositionAndAppliesEveryState(ShaderDialect dialect) {
        String vsh = vertex(dialect);

        // The whole engine rests on this block running before projection.
        assertTrue(vsh.contains("ProjMat[3].x == -1.0"), "missing the GUI check");
        assertTrue(vsh.contains("gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0)"),
                "must project the moved position, not the original one");
        assertTrue(vsh.contains("case 1:"), "missing a case for the first render state");
        assertTrue(vsh.contains("case 2:"), "missing a case for the second render state");
        assertTrue(vsh.contains("layer = 3.0;"), "the second state's layer was not applied");
    }

    @ParameterizedTest
    @EnumSource(ShaderDialect.class)
    void keepsTheEncodingConstantsInStepWithTheCompiler(ShaderDialect dialect) {
        String vsh = vertex(dialect);

        // These are a contract between the shader and Encoding. Changing one side alone produces a
        // pack that renders in the wrong place rather than failing outright, which is worse.
        assertTrue(vsh.contains("#define HEIGHT_BIT " + Encoding.HEIGHT_BIT), "HEIGHT_BIT drifted");
        assertTrue(vsh.contains("#define MAX_BIT " + Encoding.MAX_BIT), "MAX_BIT drifted");
        assertTrue(vsh.contains("#define ADD_OFFSET " + Encoding.ADD_OFFSET), "ADD_OFFSET drifted");
        assertTrue(vsh.contains("#define DEFAULT_OFFSET " + Encoding.DEFAULT_OFFSET),
                "DEFAULT_OFFSET drifted");
    }

    @Test
    void legacyTakesTransformsFromUniformsAndUsesTheSingleFogDistance() {
        String vsh = vertex(ShaderDialect.LEGACY);

        assertTrue(vsh.contains("uniform mat4 ProjMat;"), "1.21.4 has ProjMat as a plain uniform");
        assertTrue(vsh.contains("uniform mat4 ModelViewMat;"));
        assertTrue(vsh.contains("out float vertexDistance;"));
        assertTrue(vsh.contains("fog_distance(pos, FogShape)"));
        assertFalse(vsh.contains("dynamictransforms.glsl"),
                "the uniform blocks do not exist before 1.21.6");
    }

    @ParameterizedTest
    @EnumSource(value = ShaderDialect.class,
            names = {"UNIFORM_BLOCKS_150", "UNIFORM_BLOCKS_330", "LIGHTMAP_HELPER"})
    void modernDialectsTakeTransformsFromUniformBlocks(ShaderDialect dialect) {
        String vsh = vertex(dialect);

        assertTrue(vsh.contains("#moj_import <minecraft:projection.glsl>"), "ProjMat comes from here");
        assertTrue(vsh.contains("#moj_import <minecraft:dynamictransforms.glsl>"),
                "ModelViewMat comes from here");
        assertFalse(vsh.contains("uniform mat4 ProjMat;"),
                "redeclaring a block member as a uniform fails to compile");
        assertTrue(vsh.contains("out float sphericalVertexDistance;"));
        assertTrue(vsh.contains("out float cylindricalVertexDistance;"));
    }

    @Test
    void onlyTheNewestDialectSamplesTheLightmapThroughTheHelper() {
        String modern = vertex(ShaderDialect.LIGHTMAP_HELPER);
        assertTrue(modern.contains("#moj_import <minecraft:sample_lightmap.glsl>"));
        assertTrue(modern.contains("sample_lightmap(Sampler2, UV2)"));

        for (ShaderDialect older : new ShaderDialect[]{
                ShaderDialect.LEGACY, ShaderDialect.UNIFORM_BLOCKS_150, ShaderDialect.UNIFORM_BLOCKS_330}) {
            String vsh = vertex(older);
            assertFalse(vsh.contains("sample_lightmap"),
                    older + " predates the helper and must fetch the texel directly");
            assertTrue(vsh.contains("texelFetch(Sampler2, UV2 / 16, 0)"), older + " lost its lightmap read");
        }
    }

    @Test
    void everyPackFormatInRangeMapsToExactlyOneDialect() {
        for (int format = ShaderDialect.MIN_PACK_FORMAT; format <= ShaderDialect.MAX_PACK_FORMAT; format++) {
            int matches = 0;
            for (ShaderDialect dialect : ShaderDialect.values()) {
                if (dialect.covers(format)) {
                    matches++;
                }
            }
            // A gap means a client gets no shader at all; an overlap means two overlays fight.
            assertEquals(1, matches, "pack format " + format + " is covered by " + matches + " dialects");
        }
    }

    @Test
    void knownVersionsResolveToTheDialectTheyNeed() {
        assertEquals(ShaderDialect.LEGACY, ShaderDialect.forPackFormat(46));            // 1.21.4
        assertEquals(ShaderDialect.LEGACY, ShaderDialect.forPackFormat(55));            // 1.21.5
        assertEquals(ShaderDialect.UNIFORM_BLOCKS_150, ShaderDialect.forPackFormat(63)); // 1.21.6
        assertEquals(ShaderDialect.UNIFORM_BLOCKS_150, ShaderDialect.forPackFormat(64)); // 1.21.7-1.21.8
        assertEquals(ShaderDialect.UNIFORM_BLOCKS_330, ShaderDialect.forPackFormat(69)); // 1.21.9-1.21.10
        assertEquals(ShaderDialect.UNIFORM_BLOCKS_330, ShaderDialect.forPackFormat(75)); // 1.21.11
        assertEquals(ShaderDialect.LIGHTMAP_HELPER, ShaderDialect.forPackFormat(84));    // 26.1
        assertEquals(ShaderDialect.TEXT_VARIANTS, ShaderDialect.forPackFormat(88));      // 26.2
    }

    @Test
    void anUnknownFutureFormatGetsTheNewestDialect() {
        // Guessing at the newest shape gives a new release a chance of working; falling back to the
        // oldest one guarantees it will not.
        assertEquals(ShaderDialect.TEXT_VARIANTS, ShaderDialect.forPackFormat(500));
    }

    @Test
    void theShaderIsWrittenUnderTheNameItsVersionUses() {
        // 26.2 renamed rendertype_text to text. Writing the old name there leaves the client using
        // its own shader, and the HUD is simply absent with nothing logged anywhere.
        for (ShaderDialect dialect : new ShaderDialect[]{
                ShaderDialect.LEGACY, ShaderDialect.UNIFORM_BLOCKS_150,
                ShaderDialect.UNIFORM_BLOCKS_330, ShaderDialect.LIGHTMAP_HELPER}) {
            assertEquals("assets/minecraft/shaders/core/rendertype_text.vsh", dialect.vertexShaderPath());
        }
        assertEquals("assets/minecraft/shaders/core/text.vsh",
                ShaderDialect.TEXT_VARIANTS.vertexShaderPath());
    }

    @Test
    void theNewestDialectKeepsTheVariantGuardsTheClientCompilesWith() {
        String vsh = vertex(ShaderDialect.TEXT_VARIANTS);

        // The client builds this source once per variant. A variant that fails to compile takes the
        // whole shader down, including the GUI one the HUD lives in.
        assertTrue(vsh.contains("#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)"), vsh);
        assertTrue(vsh.contains("#else"), "the GUI variant needs its own vertexColor");
        assertTrue(vsh.contains("vertexColor = Color;"), "GUI text has no lightmap to sample");
        assertTrue(vsh.contains("sample_lightmap(Sampler2, UV2)"), "world text still does");

        // Guards must balance, or the shader will not parse at all.
        assertEquals(countOf(vsh, "#if "), countOf(vsh, "#endif"), "unbalanced preprocessor guards");
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    @Test
    void hidingTheLevelTextIsOptOut() {
        assertFalse(ShaderGen.vertex(ShaderDialect.LEGACY, states(), false).contains("levelColor"));
        assertTrue(ShaderGen.vertex(ShaderDialect.LEGACY, states(), true).contains("levelColor"));
    }

    @ParameterizedTest
    @EnumSource(ShaderDialect.class)
    void aLowerBossBarLineMovesTheBaselineDown(ShaderDialect dialect) {
        for (int line = 1; line <= Encoding.MAX_BOSS_BAR_LINE; line++) {
            int offset = Encoding.defaultOffset(line);
            // The stride is the client's own: y += 10 + font.lineHeight, with lineHeight 9. Getting
            // it wrong puts the HUD a couple of pixels off on every line but the first, which is
            // easy to mistake for a layout mistake in the config.
            assertEquals(Encoding.DEFAULT_OFFSET + 19 * (line - 1), offset);
            assertTrue(ShaderGen.vertex(dialect, states(), false, offset)
                            .contains("#define DEFAULT_OFFSET " + offset),
                    dialect + " must build against line " + line);
        }
    }

    @Test
    void aBossBarLineTheClientNeverDrawsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Encoding.defaultOffset(0));
        assertThrows(IllegalArgumentException.class,
                () -> Encoding.defaultOffset(Encoding.MAX_BOSS_BAR_LINE + 1));
    }
}
