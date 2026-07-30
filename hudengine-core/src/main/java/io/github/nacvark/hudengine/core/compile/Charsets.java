package io.github.nacvark.hudengine.core.compile;

import io.github.nacvark.hudengine.core.util.EngineLogger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which characters a font is rasterised for.
 *
 * Every character in a font's set becomes a glyph in the resource pack, so the set is a direct
 * trade between coverage and download size. A font therefore declares the languages it needs rather
 * than getting everything:
 *
 *   my_font:
 *     file: my_font.ttf
 *     scale: 16
 *     include:
 *       - russia
 *
 * Language names match BetterHud's so existing font configs keep working, and as there, the Latin
 * alphabet is always included whether or not it is listed.
 */
public final class Charsets {

    private Charsets() {
    }

    /**
     * How many glyphs a language has to reach before the compiler warns about it. The CJK and Hangul
     * blocks run to thousands each, which costs minutes of rasterising and adds megabytes to the
     * pack.
     */
    private static final int LARGE_LANGUAGE_THRESHOLD = 1000;

    private static final Map<String, String> LANGUAGES = new LinkedHashMap<>();

    static {
        // Living Cyrillic: Russian, Ukrainian, Belarusian, Bulgarian, Serbian, Macedonian, plus the
        // Ukrainian ge. Stops short of 0x0460, where the block turns into Old Church Slavonic. The
        // vanilla sheets do not carry those, so including them would require a font file for glyphs
        // a HUD is very unlikely to use.
        LANGUAGES.put("russia", range(0x0400, 0x045F) + range(0x0490, 0x0491));
        LANGUAGES.put("cyrillic-full", range(0x0400, 0x04FF));
        LANGUAGES.put("greece", range(0x0370, 0x03FF));
        LANGUAGES.put("hebrew", range(0x0590, 0x05FF));
        LANGUAGES.put("arab", range(0x0600, 0x06FF));
        LANGUAGES.put("hindi", range(0x0900, 0x097F));       // Devanagari
        LANGUAGES.put("bengal", range(0x0980, 0x09FF));
        LANGUAGES.put("thailand", range(0x0E00, 0x0E7F));
        LANGUAGES.put("japan", range(0x3040, 0x30FF) + range(0x4E00, 0x9FFF));
        LANGUAGES.put("china", range(0x4E00, 0x9FFF));
        LANGUAGES.put("korean", range(0xAC00, 0xD7A3));
    }

    /** Aliases for the same sets, for configs that prefer script names over BetterHud's. */
    private static final Map<String, String> ALIASES = Map.of(
            "cyrillic", "russia",
            "ukraine", "russia",
            "greek", "greece",
            "arabic", "arab",
            "devanagari", "hindi",
            "bengali", "bengal",
            "thai", "thailand",
            "hangul", "korean",
            "chinese", "china",
            "japanese", "japan");

    /** Language names the config accepts, in documentation order. */
    public static List<String> languageNames() {
        return List.copyOf(LANGUAGES.keySet());
    }

    /**
     * The set every font gets: printable ASCII, Latin-1 letters and common punctuation.
     *
     * This is not optional. A HUD is built out of numbers and separators even when its labels are
     * in another script, and leaving them out breaks every layout.
     */
    public static String base() {
        return range(0x21, 0x7E)
                + range(0x00C0, 0x00FF)
                + "«»–—‘’“”…·№°";
    }

    /**
     * Builds the character set for one font.
     *
     * @param include language names from the font's config; unknown names are reported and skipped,
     *                so one typo does not take the whole HUD down
     * @param extra   additional characters to add to every font, from the engine config
     */
    public static String forFont(List<String> include, String extra, String fontKey, EngineLogger log) {
        Set<Integer> codePoints = new LinkedHashSet<>();
        base().codePoints().forEach(codePoints::add);

        if (include != null) {
            for (String name : include) {
                addLanguage(name, fontKey, codePoints, log);
            }
        }
        if (extra != null) {
            extra.codePoints().forEach(codePoints::add);
        }

        StringBuilder out = new StringBuilder(codePoints.size());
        codePoints.forEach(out::appendCodePoint);
        return out.toString();
    }

    private static void addLanguage(String name, String fontKey, Set<Integer> into, EngineLogger log) {
        String key = name.strip().toLowerCase(Locale.ROOT);
        key = ALIASES.getOrDefault(key, key);

        String characters = LANGUAGES.get(key);
        if (characters == null) {
            log.warn("font " + fontKey + ": unknown language \"" + name + "\" in include, skipping. "
                    + "Known languages: " + String.join(", ", languageNames()));
            return;
        }
        if (characters.codePointCount(0, characters.length()) >= LARGE_LANGUAGE_THRESHOLD) {
            log.warn("font " + fontKey + ": language \"" + key + "\" adds "
                    + characters.codePointCount(0, characters.length())
                    + " glyphs; expect a slow compile and a much larger resource pack");
        }
        characters.codePoints().forEach(into::add);
    }

    private static String range(int from, int to) {
        StringBuilder out = new StringBuilder(to - from + 1);
        for (int cp = from; cp <= to; cp++) {
            out.appendCodePoint(cp);
        }
        return out.toString();
    }
}
