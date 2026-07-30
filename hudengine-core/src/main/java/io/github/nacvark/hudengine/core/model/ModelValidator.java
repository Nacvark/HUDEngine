package io.github.nacvark.hudengine.core.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Checks a loaded configuration before anything is compiled.
 *
 * The point is to report every problem at once, naming what to fix and where. Failing on the
 * first bad reference makes fixing a HUD a sequence of restarts, which is the worst way to spend an
 * evening on a config file.
 *
 * Only what can be known statically is checked. Whether a placeholder will ever have a value is
 * a runtime question and deliberately left alone.
 */
public final class ModelValidator {

    private ModelValidator() {
    }

    public enum Severity {
        /** Compilation cannot produce a correct pack. */
        ERROR,
        /** Compilation will succeed, but the result is probably not what was meant. */
        WARNING
    }

    /**
     * @param where a config location such as {@code layouts/main -> images -> 2}
     */
    public record Problem(Severity severity, String where, String message) {

        @Override
        public String toString() {
            return severity + " " + where + ": " + message;
        }
    }

    public static List<Problem> validate(Model.Root model) {
        List<Problem> problems = new ArrayList<>();
        checkHuds(model, problems);
        checkLayouts(model, problems);
        checkImages(model, problems);
        checkFonts(model, problems);
        checkCompasses(model, problems);
        return problems;
    }

    public static boolean hasErrors(List<Problem> problems) {
        return problems.stream().anyMatch(p -> p.severity() == Severity.ERROR);
    }

    /* ---------------- huds ---------------- */

    private static void checkHuds(Model.Root model, List<Problem> problems) {
        for (Model.HudDef hud : model.huds().values()) {
            String where = "huds/" + hud.key();
            if (hud.layouts().isEmpty()) {
                problems.add(warning(where, "declares no layouts, so it will draw nothing"));
                continue;
            }
            int index = 0;
            for (Model.HudLayoutRef ref : hud.layouts()) {
                index++;
                String at = where + " -> layouts -> " + index;
                if (ref.layoutName() == null) {
                    problems.add(error(at, "has no name"));
                } else if (!model.layouts().containsKey(ref.layoutName())) {
                    problems.add(error(at, "refers to layout \"" + ref.layoutName() + "\", which no "
                            + "file in layouts/ defines" + suggest(ref.layoutName(), model.layouts().keySet())));
                }
                checkAnchor(at, "x", ref.anchorX(), problems);
                checkAnchor(at, "y", ref.anchorY(), problems);
            }
        }
    }

    /** Anchors are a percentage of the screen, so anything outside 0..100 is off it. */
    private static void checkAnchor(String where, String axis, double value, List<Problem> problems) {
        if (value < 0 || value > 100) {
            problems.add(warning(where, axis + " is " + value + ", but an anchor is a percentage of "
                    + "the screen; this places the layout off-screen"));
        }
    }

    /* ---------------- layouts ---------------- */

    private static void checkLayouts(Model.Root model, List<Problem> problems) {
        for (Model.LayoutDef layout : model.layouts().values()) {
            String where = "layouts/" + layout.key();

            int index = 0;
            for (Model.LayoutImage image : layout.images()) {
                index++;
                checkReference(where + " -> images -> " + index, image.name(),
                        model.images().keySet(), "images/", problems);
            }
            index = 0;
            for (Model.LayoutText text : layout.texts()) {
                index++;
                String at = where + " -> texts -> " + index;
                checkReference(at, text.name(), model.textFonts().keySet(), "texts/", problems);
                if (text.pattern() == null || text.pattern().isEmpty()) {
                    problems.add(warning(at, "has an empty pattern, so it will draw nothing"));
                }
                checkAlign(at, text.align(), problems);
                if (text.scale() <= 0) {
                    problems.add(error(at, "has scale " + text.scale() + "; it must be greater than zero"));
                }
                checkLegible(at, model.textFonts().get(text.name()), text.scale(), problems);
            }
            index = 0;
            for (Model.LayoutHead head : layout.heads()) {
                index++;
                checkReference(where + " -> heads -> " + index, head.name(),
                        model.heads().keySet(), "heads/", problems);
            }
            index = 0;
            for (Model.LayoutCompass compass : layout.compasses()) {
                index++;
                checkReference(where + " -> compasses -> " + index, compass.name(),
                        model.compasses().keySet(), "compasses/", problems);
            }
        }
    }

    /**
     * Height below which text stops being readable.
     *
     * The vanilla font is eight pixels tall. Around five, digits stop being distinguishable from
     * one another; below three they are a smudge. Nothing fails when text is compiled that small —
     * it renders, it is simply illegible — so without this the only symptom is "the text does not
     * work" and no way to tell why.
     */
    private static final int MIN_READABLE_HEIGHT = 5;

    private static void checkLegible(String where, Model.TextFontDef font, double scale,
                                     List<Problem> problems) {
        if (font == null) {
            return; // the missing-font error already covers this
        }
        int height = (int) Math.round(font.scale() * scale);
        if (height < MIN_READABLE_HEIGHT) {
            problems.add(warning(where, "compiles to " + height + " pixels tall (font scale "
                    + font.scale() + " times " + scale + "), which is too small to read. Raise the "
                    + "scale here or the font's."));
        }
    }

    private static void checkAlign(String where, String align, List<Problem> problems) {
        if (align == null) {
            return;
        }
        String normalized = align.toLowerCase(Locale.ROOT);
        if (!normalized.equals("left") && !normalized.equals("center") && !normalized.equals("right")) {
            problems.add(warning(where, "align is \"" + align
                    + "\", which is not left, center or right; it will be treated as left"));
        }
    }

    private static void checkReference(String where, String name, Set<String> known,
                                       String folder, List<Problem> problems) {
        if (name == null) {
            problems.add(error(where, "has no name"));
            return;
        }
        if (!known.contains(name)) {
            problems.add(error(where, "refers to \"" + name + "\", which no file in " + folder
                    + " defines" + suggest(name, known)));
        }
    }

    /* ---------------- images ---------------- */

    private static void checkImages(Model.Root model, List<Problem> problems) {
        for (Model.ImageDef image : model.images().values()) {
            String where = "images/" + image.key();

            if (image.file() == null) {
                problems.add(error(where, "has no file"));
            } else if (!Files.isRegularFile(model.assetsDir().resolve(image.file()))) {
                problems.add(error(where, "points at assets/" + image.file() + ", which does not exist"));
            }
            if (image.scale() <= 0) {
                problems.add(error(where, "has scale " + image.scale() + "; it must be greater than zero"));
            }

            switch (image.type()) {
                case LISTENER -> {
                    if (image.split() <= 0) {
                        problems.add(error(where, "is a listener but split is " + image.split()
                                + "; it needs the number of steps to slice the bar into"));
                    }
                    if (image.listenerValue() == null) {
                        problems.add(error(where, "is a listener but has no setting.listener.value"));
                    }
                    if (image.listenerMax() == null) {
                        problems.add(error(where, "is a listener but has no setting.listener.max"));
                    }
                }
                case FOLLOW -> {
                    if (image.children().isEmpty()) {
                        problems.add(error(where, "follows \"" + image.followPlaceholder()
                                + "\" but lists no children to choose between"));
                    }
                    image.children().forEach((value, child) -> {
                        if (child == null) {
                            problems.add(error(where + " -> children -> " + value, "has no image"));
                        } else if (!model.images().containsKey(child)) {
                            problems.add(error(where + " -> children -> " + value,
                                    "refers to image \"" + child + "\", which no file in images/ defines"
                                            + suggest(child, model.images().keySet())));
                        }
                    });
                }
                case SINGLE -> { /* nothing beyond the shared checks */ }
            }
        }
    }

    /* ---------------- fonts ---------------- */

    private static void checkFonts(Model.Root model, List<Problem> problems) {
        for (Model.TextFontDef font : model.textFonts().values()) {
            String where = "texts/" + font.key();

            if (font.scale() <= 0) {
                problems.add(error(where, "has scale " + font.scale() + "; it must be greater than zero"));
            }
            if (font.file() != null && !Files.isRegularFile(model.fontsDir().resolve(font.file()))) {
                problems.add(error(where, "points at fonts/" + font.file() + ", which does not exist"));
            }
            if (font.file() == null && !font.mergeDefault()) {
                problems.add(error(where, "has merge-default-bitmap: false and no file, so it has "
                        + "nowhere to take glyphs from"));
            }
        }
        checkUnusedFontFiles(model, problems);
    }

    /**
     * Font files sitting in {@code fonts/} that nothing points at.
     *
     * A font file takes effect only once an entry in {@code texts/} names it with {@code file:}.
     * Until then the text still renders, in the vanilla font, with nothing in the output to
     * distinguish it from a font that was applied.
     */
    private static void checkUnusedFontFiles(Model.Root model, List<Problem> problems) {
        if (!Files.isDirectory(model.fontsDir())) {
            return;
        }
        Set<String> used = new HashSet<>();
        for (Model.TextFontDef font : model.textFonts().values()) {
            if (font.file() != null) {
                used.add(font.file().toLowerCase(Locale.ROOT));
            }
        }
        try (Stream<Path> files = Files.list(model.fontsDir())) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> {
                        String lower = name.toLowerCase(Locale.ROOT);
                        return (lower.endsWith(".ttf") || lower.endsWith(".otf"))
                                && !used.contains(lower);
                    })
                    .forEach(name -> problems.add(warning("fonts/" + name,
                            "is not used by anything. A font file is only drawn from once an entry "
                                    + "in texts/ names it, for example \"file: " + name + "\"")));
        } catch (IOException e) {
            // Not being able to list the folder is not a configuration problem; the compiler will
            // report it properly if it actually needs a file from there.
        }
    }

    /* ---------------- compasses ---------------- */

    private static void checkCompasses(Model.Root model, List<Problem> problems) {
        for (Model.CompassDef compass : model.compasses().values()) {
            String where = "compasses/" + compass.key();

            if (compass.icons().isEmpty()) {
                problems.add(error(where, "defines no icons under file:, so it will draw nothing"));
            }
            compass.icons().forEach((slot, icon) -> {
                String at = where + " -> file -> " + slot;
                if (icon.file() == null) {
                    problems.add(error(at, "has no name"));
                } else if (!Files.isRegularFile(model.assetsDir().resolve(icon.file()))) {
                    problems.add(error(at, "points at assets/" + icon.file() + ", which does not exist"));
                }
                if (icon.scale() <= 0) {
                    problems.add(error(at, "has scale " + icon.scale() + "; it must be greater than zero"));
                }
            });

            if (compass.distanceText() != null) {
                String at = where + " -> distance-text";
                String font = compass.distanceText().font();
                if (font == null) {
                    problems.add(error(at, "has no font"));
                } else if (!model.textFonts().containsKey(font)) {
                    problems.add(error(at, "refers to font \"" + font + "\", which no file in texts/ "
                            + "defines" + suggest(font, model.textFonts().keySet())));
                } else {
                    checkLegible(at, model.textFonts().get(font),
                            compass.distanceText().scale(), problems);
                }
            }
        }
    }

    /* ---------------- helpers ---------------- */

    private static Problem error(String where, String message) {
        return new Problem(Severity.ERROR, where, message);
    }

    private static Problem warning(String where, String message) {
        return new Problem(Severity.WARNING, where, message);
    }

    /**
     * Offers the closest defined key, so a typo reads as a typo.
     *
     * Distance is capped relative to the name's length: past that the "suggestion" is noise that
     * sends people looking in the wrong place.
     */
    private static String suggest(String name, Set<String> known) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : known) {
            int distance = editDistance(name.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        int limit = Math.max(2, name.length() / 3);
        if (best == null || bestDistance > limit) {
            return known.isEmpty() ? "" : ". Defined: " + String.join(", ", new TreeSet<>(known));
        }
        return ". Did you mean \"" + best + "\"?";
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** Renders problems as lines for a log, errors first. */
    public static List<String> format(List<Problem> problems) {
        List<String> lines = new ArrayList<>(problems.size());
        for (Severity severity : List.of(Severity.ERROR, Severity.WARNING)) {
            for (Problem problem : problems) {
                if (problem.severity() == severity) {
                    lines.add(problem.where() + ": " + problem.message());
                }
            }
        }
        return lines;
    }

    /** Groups problems by severity, for callers that report them differently. */
    public static Map<Severity, List<Problem>> bySeverity(List<Problem> problems) {
        List<Problem> errors = new ArrayList<>();
        List<Problem> warnings = new ArrayList<>();
        for (Problem problem : problems) {
            (problem.severity() == Severity.ERROR ? errors : warnings).add(problem);
        }
        return Map.of(Severity.ERROR, errors, Severity.WARNING, warnings);
    }
}
