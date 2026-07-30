package io.github.nacvark.hudengine.core.runtime;

import io.github.nacvark.hudengine.core.model.Compiled;

import java.util.ArrayList;
import java.util.List;

/**
 * Pixel measurement and word wrapping for HUD text.
 *
 * Widths come from the glyphs compiled for one specific element, at that element's font and
 * scale. Measuring by character count instead would make a line of {@code W} and a line of {@code i}
 * look equally long, which is exactly where naive wrapping falls apart.
 */
public final class TextMetrics {

    private TextMetrics() {
    }

    /** Width of a string in HUD pixels, using the client's advance semantics. */
    public static int width(Compiled.Text element, String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ') {
                width += element.spaceAdvance();
                continue;
            }
            Compiled.Glyph glyph = element.glyphs().get(cp);
            if (glyph != null) {
                width += glyph.width();
            }
        }
        return width;
    }

    /**
     * Wraps text to a pixel width. Existing newlines are honoured, and a word too long for a whole
     * line is broken by character rather than left to overflow.
     */
    public static List<String> wrap(Compiled.Text element, String text, int maxWidthPx) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        for (String paragraph : text.split("\n", -1)) {
            wrapParagraph(element, paragraph, Math.max(1, maxWidthPx), lines);
        }
        return lines;
    }

    private static void wrapParagraph(Compiled.Text element, String paragraph, int max, List<String> out) {
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;

        for (String word : paragraph.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            int wordWidth = width(element, word);
            int needed = (lineWidth == 0 ? 0 : element.spaceAdvance()) + wordWidth;

            if (lineWidth + needed <= max) {
                if (lineWidth > 0) {
                    line.append(' ');
                }
                line.append(word);
                lineWidth += needed;
                continue;
            }
            if (lineWidth > 0) {
                out.add(line.toString());
                line.setLength(0);
                lineWidth = 0;
            }
            if (wordWidth <= max) {
                line.append(word);
                lineWidth = wordWidth;
                continue;
            }
            lineWidth = breakWord(element, word, max, line, out);
        }
        out.add(line.toString());
    }

    /** Splits a word that cannot fit on any line, returning the width left on the current line. */
    private static int breakWord(Compiled.Text element, String word, int max,
                                 StringBuilder line, List<String> out) {
        int lineWidth = 0;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            i += Character.charCount(cp);
            String ch = new String(Character.toChars(cp));
            int charWidth = width(element, ch);

            if (lineWidth + charWidth > max && lineWidth > 0) {
                out.add(line.toString());
                line.setLength(0);
                lineWidth = 0;
            }
            line.append(ch);
            lineWidth += charWidth;
        }
        return lineWidth;
    }
}
