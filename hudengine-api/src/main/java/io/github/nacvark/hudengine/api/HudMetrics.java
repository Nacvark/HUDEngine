package io.github.nacvark.hudengine.api;

import java.util.List;

/**
 * Pixel measurement against a HUD's real fonts.
 *
 * <p>Element keys come from {@code build/manifest.json}, which the engine writes on every compile.
 * They read as {@code <layout>_text_<n>}, numbered in the order the layout declares its texts.
 *
 * <p>Useful because a HUD's text is rasterised at a specific size from specific glyphs, so its width
 * has nothing to do with character count. Wrapping a quest description by counting characters gives
 * ragged lines; wrapping it with these gives the lines the player will actually see.
 */
public interface HudMetrics {

    /**
     * Width of a string in HUD pixels.
     *
     * @throws IllegalArgumentException if the element does not exist or is not a text element
     */
    int width(String hudKey, String elementKey, String text);

    /**
     * Wraps text to a pixel width. Existing newlines are honoured, and a word too long for a whole
     * line is broken by character rather than left to overflow.
     *
     * @throws IllegalArgumentException if the element does not exist or is not a text element
     */
    List<String> wrap(String hudKey, String elementKey, String text, int maxWidthPx);
}
