package io.github.nacvark.hudengine.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a TTF survives being rasterised at the sizes a HUD font actually uses.
 *
 * A HUD font is drawn around 8 pixels tall, where a stem is well under one pixel wide. Filling the
 * glyph outline directly loses any glyph whose strokes miss the pixel centres, and in a monospace
 * font that is most of the punctuation plus I, L and T — they vanish from the HUD with only a line
 * in the compile report to say so. Aliased text rendering grid-fits the outline instead and keeps
 * them, which is what {@code TextCompiler} relies on.
 *
 * The fonts come from the machine running the tests, so the test skips rather than fails where
 * none of them exist.
 */
class FontRasterTest {

    private static final String ASCII =
            "!\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`"
                    + "abcdefghijklmnopqrstuvwxyz{|}~";

    private static final List<String> CANDIDATES = List.of(
            "C:/Windows/Fonts/consola.ttf",
            "C:/Windows/Fonts/arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf",
            "/System/Library/Fonts/Supplemental/Courier New.ttf");

    private static Font anyFont() {
        for (String path : CANDIDATES) {
            File file = new File(path);
            if (file.isFile()) {
                try {
                    return Font.createFont(Font.TRUETYPE_FONT, file);
                } catch (Exception ignored) {
                    // try the next one
                }
            }
        }
        return null;
    }

    /** The rasterisation TextCompiler performs, reduced to one glyph. */
    private static boolean draws(Font base, int em, char ch) {
        return notBlank(render(base, em, ch, true));
    }

    /** What TextCompiler used to do: fill the outline with no grid fitting. */
    private static boolean fills(Font base, int em, char ch) {
        return notBlank(render(base, em, ch, false));
    }

    private static BufferedImage render(Font base, int em, char ch, boolean gridFit) {
        Font font = base.deriveFont((float) em);
        BufferedImage canvas = new BufferedImage(em * 2, (int) Math.round(em * 1.4),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        if (gridFit) {
            g.setFont(font);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.drawString(String.valueOf(ch), 0, em);
        } else {
            g.fill(font.createGlyphVector(new FontRenderContext(null, false, false),
                    String.valueOf(ch)).getOutline(0f, em));
        }
        g.dispose();
        return canvas;
    }

    private static boolean notBlank(BufferedImage canvas) {
        for (int y = 0; y < canvas.getHeight(); y++) {
            for (int x = 0; x < canvas.getWidth(); x++) {
                if ((canvas.getRGB(x, y) >>> 24) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 9, 10, 12, 16})
    void everyPrintableAsciiGlyphSurvivesAtUsableSizes(int em) {
        Font base = anyFont();
        Assumptions.assumeTrue(base != null, "no TrueType font available on this machine");

        List<Character> lost = new ArrayList<>();
        for (char ch : ASCII.toCharArray()) {
            if (!draws(base, em, ch)) {
                lost.add(ch);
            }
        }
        assertTrue(lost.isEmpty(), "at " + em + " pixels these came out blank: " + lost);
    }

    /**
     * Below the vanilla size some punctuation has no whole pixel to land on at any setting, so the
     * guarantee there is only that grid fitting never loses more than filling the outline did.
     */
    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6, 7})
    void gridFittingIsNeverWorseBelowTheVanillaSize(int em) {
        Font base = anyFont();
        Assumptions.assumeTrue(base != null, "no TrueType font available on this machine");

        List<Character> lostDrawing = new ArrayList<>();
        List<Character> lostFilling = new ArrayList<>();
        for (char ch : ASCII.toCharArray()) {
            if (!draws(base, em, ch)) {
                lostDrawing.add(ch);
            }
            if (!fills(base, em, ch)) {
                lostFilling.add(ch);
            }
        }
        assertTrue(lostDrawing.size() <= lostFilling.size(),
                "at " + em + " pixels grid fitting lost " + lostDrawing
                        + " where filling the outline lost " + lostFilling);
    }
}
