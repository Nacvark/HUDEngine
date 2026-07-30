package io.github.nacvark.hudengine.core;

import io.github.nacvark.hudengine.core.model.Compiled;
import io.github.nacvark.hudengine.core.runtime.HudRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererTest {

    private static Compiled.Pack pack;

    /** Codepoint to advance, so a rendered string can be re-measured independently. */
    private static Map<Integer, Integer> widths;

    private static final List<String> HUDS = List.of("main", "side");

    /** One drawn glyph: what it is, where it lands, and how it is styled. */
    private record Mark(int cp, int x, String font, int color, boolean shadow) {
    }

    @BeforeAll
    static void compileOnce(@TempDir Path out) throws IOException {
        pack = CompilerTest.compile(out).pack();
        widths = indexWidths(pack);
    }

    @Test
    void totalAdvanceIsZero() {
        HudRenderer.Output output = render(values(), skin(), null);

        // The client centres the boss bar title. A zero-width string therefore starts exactly at
        // screen centre, which is the origin the generated shader assumes. Any drift here moves the
        // entire HUD sideways.
        assertEquals(0, netAdvance(output.runs()));
    }

    @Test
    void totalAdvanceStaysZeroAcrossValueWidths() {
        for (String hp : List.of("0", "1", "37", "100")) {
            Map<String, String> values = values();
            values.put("hp", hp);
            assertEquals(0, netAdvance(render(values, skin(), null).runs()),
                    "advance drifted at hp=" + hp);
        }
    }

    @Test
    void barIsAbsentAtZeroAndPresentAboveIt() {
        Compiled.Bar bar = element(Compiled.Bar.class);

        Map<String, String> empty = values();
        empty.put("hp", "0");
        assertFalse(containsAny(render(empty, skin(), null).runs(), bar),
                "an empty bar should draw no frame at all");

        Map<String, String> partial = values();
        partial.put("hp", "1");
        assertTrue(containsCp(render(partial, skin(), null).runs(), bar.frames().getFirst().cp()),
                "the smallest non-zero value should pick the first frame");

        Map<String, String> full = values();
        full.put("hp", "100");
        assertTrue(containsCp(render(full, skin(), null).runs(), bar.frames().getLast().cp()),
                "a full bar should pick the last frame");
    }

    @Test
    void blockCacheReportsNoChangeWhenNothingChanged() {
        HudRenderer renderer = new HudRenderer(pack);
        HudRenderer.RenderCache cache = new HudRenderer.RenderCache();
        Map<String, String> values = values();
        // One provider for both ticks: skins are compared by array identity, because the platform
        // layer replaces a player's face array rather than mutating it.
        HudRenderer.SkinProvider skins = skin();

        HudRenderer.Output first = renderer.render(HUDS, values::get, skins, null, cache);
        assertTrue(first.changed(), "the first render always counts as changed");

        HudRenderer.Output second = renderer.render(HUDS, values::get, skins, null, cache);
        assertFalse(second.changed(), "identical inputs must not report a change");
        assertEquals(first.runs(), second.runs(), "a cache hit must reproduce the string exactly");
    }

    @Test
    void blockCacheReportsChangeWhenASkinArrives() {
        HudRenderer renderer = new HudRenderer(pack);
        HudRenderer.RenderCache cache = new HudRenderer.RenderCache();
        Map<String, String> values = values();

        renderer.render(HUDS, values::get, key -> null, null, cache);
        HudRenderer.Output loaded = renderer.render(HUDS, values::get, skin(), null, cache);

        // A head is skipped while its skin is still downloading, so the tick it arrives on has to
        // count as a change or the player would keep seeing an empty slot.
        assertTrue(loaded.changed());
    }

    @Test
    void blockCacheReportsChangeWhenAValueChanges() {
        HudRenderer renderer = new HudRenderer(pack);
        HudRenderer.RenderCache cache = new HudRenderer.RenderCache();
        Map<String, String> values = values();
        HudRenderer.SkinProvider skins = skin();

        HudRenderer.Output first = renderer.render(HUDS, values::get, skins, null, cache);
        values.put("player_x", "999");
        HudRenderer.Output second = renderer.render(HUDS, values::get, skins, null, cache);

        assertTrue(second.changed());
        assertNotEquals(first.runs(), second.runs());
    }

    @Test
    void cachedAndUncachedRenderingDrawTheSamePicture() {
        HudRenderer renderer = new HudRenderer(pack);
        Map<String, String> values = values();
        HudRenderer.SkinProvider skins = skin();

        HudRenderer.Output uncached = renderer.render(HUDS, values::get, skins, null, null);
        HudRenderer.Output cached =
                renderer.render(HUDS, values::get, skins, null, new HudRenderer.RenderCache());

        // The two paths are compared by what the player sees, not byte for byte. Assembling each HUD
        // from cursor zero means the cached path bridges the cursor back at every HUD boundary, so
        // it spends a few more space glyphs to reach the same positions. How a move is split into
        // steps is invisible; which glyph lands where, in what style, is not.
        assertEquals(marks(uncached.runs()), marks(cached.runs()));
    }

    @Test
    void aConditionHidesABarJustAsItHidesAnImage() {
        Compiled.Bar bar = element(Compiled.Bar.class);
        assertNotNull(bar.condition(), "the fixture's bar must carry a condition for this to mean anything");

        Map<String, String> off = values();
        off.put(bar.condition(), "0");
        assertFalse(containsAny(render(off, skin(), null).runs(), bar),
                "a false condition must hide the bar");

        Map<String, String> on = values();
        on.put(bar.condition(), "1");
        assertTrue(containsAny(render(on, skin(), null).runs(), bar),
                "a true condition must show it");
    }

    @Test
    void unknownCharactersAreReportedRatherThanDrawn() {
        Map<String, String> values = values();
        values.put("player_x", "中");

        HudRenderer.Output output = render(values, skin(), null);

        assertTrue(output.missingChars().contains(0x4E2D), "an uncompiled character must be reported");
        assertEquals(0, netAdvance(output.runs()), "skipping a glyph must not unbalance the string");
    }

    /* ---------------- helpers ---------------- */

    private static HudRenderer.Output render(Map<String, String> values, HudRenderer.SkinProvider skins,
                                             HudRenderer.CompassSource compass) {
        return new HudRenderer(pack).render(HUDS, values::get, skins, compass, null);
    }

    private static Map<String, String> values() {
        Map<String, String> values = new HashMap<>();
        values.put("bar_visible", "1");
        values.put("hp", "60");
        values.put("hp_max", "100");
        values.put("player_x", "-1287");
        values.put("player_y", "64");
        values.put("player_z", "3059");
        return values;
    }

    private static HudRenderer.SkinProvider skin() {
        int[] face = new int[64];
        for (int i = 0; i < face.length; i++) {
            face[i] = 0xFF000000 | (i * 3 << 16) | 0x8040;
        }
        face[3] = 0; // one transparent pixel, so the skip branch is exercised too
        return key -> face;
    }

    /** Walks a rendered string tracking the cursor, recording every glyph that actually draws. */
    private static List<Mark> marks(List<HudRenderer.Run> runs) {
        List<Mark> out = new ArrayList<>();
        int cursor = 0;
        for (HudRenderer.Run run : runs) {
            for (int i = 0; i < run.text().length(); ) {
                int cp = run.text().codePointAt(i);
                i += Character.charCount(cp);
                if (cp >= pack.spaceBase() && cp <= pack.spaceBase() + 2 * pack.spaceRange()) {
                    cursor += cp - pack.spaceBase() - pack.spaceRange();
                    continue;
                }
                Integer width = widths.get(cp);
                if (width == null) {
                    throw new AssertionError("rendered an unknown codepoint: " + cp);
                }
                out.add(new Mark(cp, cursor, run.font(), run.color(), run.shadow()));
                cursor += width;
            }
        }
        return out;
    }

    /** Re-measures a rendered string from its codepoints alone. */
    private static int netAdvance(List<HudRenderer.Run> runs) {
        int total = 0;
        for (HudRenderer.Run run : runs) {
            for (int i = 0; i < run.text().length(); ) {
                int cp = run.text().codePointAt(i);
                i += Character.charCount(cp);
                if (cp >= pack.spaceBase() && cp <= pack.spaceBase() + 2 * pack.spaceRange()) {
                    total += cp - pack.spaceBase() - pack.spaceRange();
                } else {
                    Integer width = widths.get(cp);
                    if (width == null) {
                        throw new AssertionError("rendered an unknown codepoint: " + cp);
                    }
                    total += width;
                }
            }
        }
        return total;
    }

    private static Map<Integer, Integer> indexWidths(Compiled.Pack pack) {
        Map<Integer, Integer> index = new HashMap<>();
        for (Compiled.Hud hud : pack.huds().values()) {
            for (Compiled.Element element : hud.elements()) {
                switch (element) {
                    case Compiled.Img e -> e.parts().forEach(g -> index.put(g.cp(), g.width()));
                    case Compiled.Bar e -> e.frames().forEach(g -> index.put(g.cp(), g.width()));
                    case Compiled.Follow e -> e.children().values()
                            .forEach(g -> index.put(g.cp(), g.width()));
                    case Compiled.Head e -> e.rowCps().forEach(cp -> index.put(cp, e.pixelAdvance()));
                    case Compiled.Text e -> e.glyphs().values().forEach(g -> index.put(g.cp(), g.width()));
                    case Compiled.CompassEl e -> {
                        e.slots().values().forEach(slot ->
                                slot.variants().forEach(g -> index.put(g.cp(), g.width())));
                        if (e.dist() != null) {
                            e.dist().glyphs().values().forEach(g -> index.put(g.cp(), g.width()));
                        }
                    }
                }
            }
        }
        return index;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Compiled.Element> T element(Class<T> type) {
        for (Compiled.Element element : pack.huds().get("main").elements()) {
            if (type.isInstance(element)) {
                return (T) element;
            }
        }
        throw new AssertionError("the fixture should contain a " + type.getSimpleName());
    }

    private static boolean containsCp(List<HudRenderer.Run> runs, int cp) {
        for (HudRenderer.Run run : runs) {
            if (!run.font().equals(pack.hudFont())) {
                continue;
            }
            for (int i = 0; i < run.text().length(); ) {
                int c = run.text().codePointAt(i);
                i += Character.charCount(c);
                if (c == cp) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsAny(List<HudRenderer.Run> runs, Compiled.Bar bar) {
        for (Compiled.Glyph frame : bar.frames()) {
            if (containsCp(runs, frame.cp())) {
                return true;
            }
        }
        return false;
    }
}
