package io.github.nacvark.hudengine.core.compile;

import io.github.nacvark.hudengine.core.model.Model;
import io.github.nacvark.hudengine.core.util.EngineLogger;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rasterises text into bitmap font providers.
 *
 * The pipeline, and why it is shaped this way:
 *
 * - Vanilla glyphs first. Characters that exist in the client's own sheets are taken from
 *   them at a multiplier of {@code scale / 8}. That is what makes HUD digits and letters
 *   indistinguishable from the rest of the interface. A font can opt out with
 *   {@code merge-default-bitmap: false} when a decorative TTF look is the point.
 * - TTF fills the gaps. Anything vanilla lacks is drawn onto an
 *   {@code em x round(em * 1.4)} canvas with the baseline at {@code em}, filled from the glyph
 *   outline with antialiasing off so the alpha stays binary, then trimmed of empty columns.
 * - Grouping. Glyphs are grouped by width, height and multiplier; each group shares
 *   texture pages of 16 glyphs per row and every glyph in a group gets the same advance.
 * - Placement. The 8 px vanilla sheets are the reference group, and every other group is
 *   seated on that group's baseline. Deriving each group's position independently would let
 *   rounding drift the 12 px sheet a pixel or two away from the 8 px one.
 *
 * Pages are cached per font and reused across elements; codepoints are handed out per element,
 * because each element needs its own ascent.
 */
public final class TextCompiler {

    /** Result for the renderer: which pack glyph draws each character, and how wide a space is. */
    public record TextGlyphs(Map<String, int[]> glyphs, int spaceAdvance, int height) {
    }

    private record Source(int cp, BufferedImage image, double multiplier) {
    }

    private record GroupKey(int width, int height, long multiplierBits) {
    }

    private record Page(String texture, List<Integer> cps, int cellWidth, int cellHeight, double multiplier) {
    }

    private record GlyphSet(List<Page> pages) {
    }

    /** One character's cell inside a vanilla glyph sheet. */
    private record VanillaCell(String file, int x, int y, int width, int height) {
    }

    private static final String METRICS_FILE = "vanilla.tsv";
    private static final String OVERRIDES_DIR = "overrides";

    /** Height of the vanilla 8 px sheets, and the reference for their multiplier. */
    private static final int VANILLA_CELL = 8;

    /** Ascent of the 12 px vanilla sheet, needed to seat it on the 8 px baseline. */
    private static final double TALL_SHEET_ASCENT = 10;

    private final Model.Root model;
    private final PackBuilder pack;
    private final Encoding.CharPool pool;
    private final String fontName;
    private final String extraChars;
    private final VanillaGlyphSource vanillaSource;
    private final EngineLogger log;

    private final Map<String, Font> baseFonts = new LinkedHashMap<>();
    private final Map<String, GlyphSet> glyphSets = new LinkedHashMap<>();
    private final Set<String> missing = new LinkedHashSet<>();

    private Map<Integer, VanillaCell> vanillaCells;
    private Map<Integer, BufferedImage> overrides;
    private final Map<String, BufferedImage> sheets = new LinkedHashMap<>();
    private boolean metricsWarningShown;

    /**
     * @param extraChars characters added to every font on top of its declared languages, for symbols
     *                   a HUD uses that belong to no particular script
     */
    public TextCompiler(Model.Root model, PackBuilder pack, Encoding.CharPool pool, String fontName,
                        String extraChars, VanillaGlyphSource vanillaSource, EngineLogger log) {
        this.model = model;
        this.pack = pack;
        this.pool = pool;
        this.fontName = fontName;
        this.extraChars = extraChars;
        this.vanillaSource = vanillaSource;
        this.log = log;
    }

    /** Characters the configured fonts could not draw at all. */
    public Set<String> missingChars() {
        return missing;
    }

    /**
     * Where each font's glyphs came from.
     *
     * Worth surfacing because it answers a question servers otherwise guess at: whether the TTF
     * they ship is pulling any weight. A font whose {@code fromFont} count is zero is drawing
     * entirely from vanilla and the font file can be deleted.
     */
    public record GlyphSources(String font, int fromVanilla, int fromOverrides,
                               String fontOnly, int missing) {
        public int fromFont() {
            return fontOnly.codePointCount(0, fontOnly.length());
        }
    }

    private final List<GlyphSources> glyphSourceStats = new ArrayList<>();

    public List<GlyphSources> glyphSources() {
        return List.copyOf(glyphSourceStats);
    }

    /** Compiles one text element of a layout at {@code (stateId, y)}. */
    public TextGlyphs element(Model.TextFontDef fontDef, Model.LayoutText text, int stateId, int y) {
        double fontScale = fontDef.scale();
        double elementScale = text.scale();
        GlyphSet set = glyphSets.computeIfAbsent(fontDef.key(), k -> rasterize(fontDef));

        int referenceHeight = Math.max(1, (int) Math.round(elementScale * fontScale));
        int referenceAdjust = referenceHeight
                - (referenceHeight - VANILLA_CELL) / 4
                - (int) Math.round(fontScale * elementScale);
        double baseline = (y - referenceAdjust) + 7.0 * referenceHeight / VANILLA_CELL;
        double vanillaMultiplier = fontScale / VANILLA_CELL;

        Map<String, int[]> glyphMap = new LinkedHashMap<>();
        for (Page page : set.pages()) {
            int height = Math.max(1, (int) Math.round(elementScale * page.multiplier() * page.cellHeight()));
            boolean fromVanilla = sameMultiplier(page.multiplier(), vanillaMultiplier)
                    || page.multiplier() * page.cellHeight() == fontScale; // an override sheet
            int top;
            if (fromVanilla && page.cellHeight() <= VANILLA_CELL) {
                top = y - referenceAdjust;
            } else {
                double sheetAscent = fromVanilla ? TALL_SHEET_ASCENT : fontScale;
                top = (int) Math.round(baseline - sheetAscent * height / (double) page.cellHeight());
            }
            int ascent = Encoding.ascent(stateId, Math.max(0, top));
            int advance = ImageCompiler.advance(page.cellWidth(), page.cellHeight(), height);

            List<String> rows = new ArrayList<>();
            StringBuilder row = new StringBuilder();
            for (int sourceCp : page.cps()) {
                int cp = pool.next();
                row.appendCodePoint(cp);
                glyphMap.put(new String(Character.toChars(sourceCp)), new int[]{cp, advance});
                if (row.codePointCount(0, row.length()) == 16) {
                    rows.add(row.toString());
                    row.setLength(0);
                }
            }
            if (!row.isEmpty()) {
                // The client derives cell width from texture width over row length, so a short
                // row must be padded out to 16. The padding must be NUL, the codepoint the
                // client reads as "no glyph here": any real character would bind that
                // blank cell to it, and a space would override the space glyph advance.
                while (row.codePointCount(0, row.length()) < 16) {
                    row.append('\0');
                }
                rows.add(row.toString());
            }
            pack.addProvider(fontName, PackBuilder.bitmap(page.texture(), ascent, height, rows));
        }

        int spaceAdvance = Math.max(1, (int) Math.round(fontScale * elementScale / 2.0) + 1);
        return new TextGlyphs(glyphMap, spaceAdvance, (int) Math.round(fontScale * elementScale));
    }

    private static boolean sameMultiplier(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    /**
     * Characters this font is asked to draw literally, collected from the configuration.
     *
     * A pattern written in Cyrillic without {@code include: russia} would otherwise compile to a HUD
     * with the Latin parts drawn and the rest absent, and no error to say why.
     *
     * {@code include} covers what only appears at runtime — what a placeholder returns, what another
     * plugin publishes — which cannot be known here.
     */
    private String literals(String fontKey) {
        StringBuilder out = new StringBuilder();
        for (Model.LayoutDef layout : model.layouts().values()) {
            for (Model.LayoutText text : layout.texts()) {
                if (fontKey.equals(text.name())) {
                    appendOutsidePlaceholders(text.pattern(), out);
                }
            }
        }
        for (Model.CompassDef compass : model.compasses().values()) {
            Model.CompassDistDef distance = compass.distanceText();
            if (distance != null && fontKey.equals(distance.font()) && distance.suffix() != null) {
                out.append(distance.suffix());
            }
        }
        return out.toString();
    }

    /** Appends everything outside {@code [...]}, so placeholder names are not mistaken for text. */
    private static void appendOutsidePlaceholders(String pattern, StringBuilder out) {
        if (pattern == null) {
            return;
        }
        int depth = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                out.append(c);
            }
        }
    }

    /* ---------------- rasterisation ---------------- */

    private GlyphSet rasterize(Model.TextFontDef def) {
        String charset = Charsets.forFont(def.include(), extraChars + literals(def.key()),
                def.key(), log);
        List<Source> sources = new ArrayList<>();
        Set<Integer> covered = new LinkedHashSet<>();
        int[] counts = new int[2]; // vanilla sheets, hand-drawn overrides

        Map<Integer, VanillaCell> cells = def.mergeDefault() ? vanillaCells() : Map.of();
        Map<Integer, BufferedImage> overrideGlyphs = overrides();

        charset.codePoints().distinct().forEach(cp -> {
            if (cp == ' ') {
                return;
            }
            BufferedImage override = overrideGlyphs.get(cp);
            if (override != null) {
                BufferedImage trimmed = trimColumns(override, 0, 0, override.getWidth(), override.getHeight());
                if (trimmed != null) {
                    sources.add(new Source(cp, trimmed, def.scale() / override.getHeight()));
                    covered.add(cp);
                    counts[1]++;
                    return;
                }
            }
            VanillaCell cell = cells.get(cp);
            if (cell == null) {
                return;
            }
            BufferedImage trimmed = trimColumns(sheet(cell.file()), cell.x(), cell.y(), cell.width(), cell.height());
            if (trimmed != null) {
                sources.add(new Source(cp, trimmed, def.scale() / VANILLA_CELL));
                covered.add(cp);
                counts[0]++;
            }
        });

        int beforeFont = sources.size();
        int missingBefore = missing.size();
        rasterizeFromFont(def, charset, sources, covered);

        StringBuilder fontOnly = new StringBuilder();
        sources.subList(beforeFont, sources.size()).forEach(s -> fontOnly.appendCodePoint(s.cp()));
        glyphSourceStats.add(new GlyphSources(def.key(), counts[0], counts[1],
                fontOnly.toString(), missing.size() - missingBefore));

        return new GlyphSet(buildPages(def, sources));
    }

    private void rasterizeFromFont(Model.TextFontDef def, String charset,
                                   List<Source> sources, Set<Integer> covered) {
        // Work out what is left before touching the TTF: a HUD whose charset is fully covered by
        // vanilla glyphs should not have to ship a font file at all.
        List<Integer> remaining = charset.codePoints().distinct().boxed()
                .filter(cp -> cp != ' ' && !covered.contains(cp))
                .toList();
        if (remaining.isEmpty()) {
            return;
        }
        if (def.file() == null) {
            // No TTF configured. A language block is a whole Unicode range and vanilla covers only
            // the part of it people actually type, so a few leftovers are normal and must not take
            // the compilation down: record them and carry on with what vanilla did provide.
            remaining.forEach(cp -> missing.add(new String(Character.toChars(cp))));
            log.warn("font " + def.key() + " declares no file, so " + remaining.size()
                    + " characters not present in the vanilla glyph sheets will not render");
            return;
        }

        Font base = baseFont(def);
        int em = Math.max(2, (int) Math.round(def.scale()));
        Font font = base.deriveFont((float) em);
        int canvasHeight = (int) Math.round(em * 1.4);

        remaining.forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            if (!font.canDisplay(cp)) {
                missing.add(ch);
                return;
            }
            BufferedImage canvas = new BufferedImage(em * 2, canvasHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.setColor(Color.WHITE);
            g.setFont(font);
            // Aliased text rendering, which is what grid-fits the outline to the pixel raster. At the
            // sizes a HUD font is used at, a stem is well under a pixel wide, and filling the raw
            // outline instead drops every glyph whose strokes miss the pixel centres — which is most
            // of "-+=|IL_[" in a typical font.
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.drawString(ch, 0, em);
            g.dispose();

            BufferedImage trimmed = trimColumns(canvas, 0, 0, canvas.getWidth(), canvas.getHeight());
            if (trimmed == null) {
                missing.add(ch);
            } else {
                sources.add(new Source(cp, trimmed, def.scale() / canvasHeight));
            }
        });
    }

    /** Groups glyphs of identical metrics and lays each group out on pages of 16 glyphs per row. */
    private List<Page> buildPages(Model.TextFontDef def, List<Source> sources) {
        Map<GroupKey, List<Source>> groups = new LinkedHashMap<>();
        for (Source source : sources) {
            GroupKey key = new GroupKey(source.image().getWidth(), source.image().getHeight(),
                    Double.doubleToLongBits(source.multiplier()));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(source);
        }

        List<Page> pages = new ArrayList<>();
        int pageIndex = 0;
        for (Map.Entry<GroupKey, List<Source>> group : groups.entrySet()) {
            List<Source> all = group.getValue();
            int cellWidth = group.getKey().width();
            int cellHeight = group.getKey().height();

            for (int start = 0; start < all.size(); start += 256) {
                List<Source> slice = all.subList(start, Math.min(all.size(), start + 256));
                int rows = (slice.size() + 15) / 16;
                BufferedImage sheet = new BufferedImage(16 * cellWidth, rows * cellHeight,
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = sheet.createGraphics();
                List<Integer> cps = new ArrayList<>(slice.size());
                for (int i = 0; i < slice.size(); i++) {
                    g.drawImage(slice.get(i).image(), (i % 16) * cellWidth, (i / 16) * cellHeight, null);
                    cps.add(slice.get(i).cp());
                }
                g.dispose();
                binarize(sheet);

                String texture = pack.addTexture(
                        "glyphs_" + sanitize(def.key()) + "_" + (++pageIndex) + ".png", sheet);
                pages.add(new Page(texture, cps, cellWidth, cellHeight, slice.getFirst().multiplier()));
            }
        }
        return pages;
    }

    /* ---------------- vanilla glyph data ---------------- */

    private Map<Integer, VanillaCell> vanillaCells() {
        if (vanillaCells != null) {
            return vanillaCells;
        }
        vanillaCells = new LinkedHashMap<>();

        byte[] metrics = vanillaSource.read(METRICS_FILE);
        if (metrics == null) {
            if (!metricsWarningShown) {
                metricsWarningShown = true;
                log.warn(METRICS_FILE + " not found; all text will be rasterised from the TTF and will "
                        + "not match the vanilla font");
            }
            return vanillaCells;
        }

        String text = new String(metrics, StandardCharsets.UTF_8);
        int lineNumber = 0;
        for (String line : text.split("\n")) {
            lineNumber++;
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\t");
            if (parts.length < 6) {
                throw new IllegalStateException(METRICS_FILE + ":" + lineNumber
                        + ": expected 6 tab-separated columns, got " + parts.length);
            }
            try {
                vanillaCells.put(Integer.parseInt(parts[3]), new VanillaCell(
                        parts[0],
                        Integer.parseInt(parts[4]), Integer.parseInt(parts[5]),
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(METRICS_FILE + ":" + lineNumber + ": malformed number", e);
            }
        }
        return vanillaCells;
    }

    /**
     * Hand-drawn replacements for individual glyphs, named {@code overrides/u<HEX>.png}.
     *
     * They take priority over the vanilla sheets and join the same metric group as the 8 px
     * sheets, which makes them sit correctly at any scale. This exists because a few characters live
     * on the 12 px sheet in vanilla with a different ascent, and redrawing them on the 8 px grid is
     * simpler than special-casing them.
     */
    private Map<Integer, BufferedImage> overrides() {
        if (overrides != null) {
            return overrides;
        }
        overrides = new LinkedHashMap<>();
        for (String name : vanillaSource.list(OVERRIDES_DIR)) {
            if (!name.startsWith("u") || !name.endsWith(".png")) {
                continue;
            }
            try {
                int cp = Integer.parseInt(name.substring(1, name.length() - 4), 16);
                BufferedImage image = readImage(vanillaSource.read(OVERRIDES_DIR + "/" + name));
                if (image != null) {
                    overrides.put(cp, image);
                }
            } catch (NumberFormatException e) {
                log.warn("override " + name + " skipped: the name must be u<HEX>.png");
            } catch (IOException e) {
                log.warn("override " + name + " skipped: " + e.getMessage());
            }
        }
        return overrides;
    }

    private BufferedImage sheet(String file) {
        return sheets.computeIfAbsent(file, f -> {
            try {
                BufferedImage image = readImage(vanillaSource.read(f));
                if (image == null) {
                    throw new IOException("missing or not a readable image");
                }
                return image;
            } catch (IOException e) {
                throw new IllegalStateException("cannot read vanilla glyph sheet " + f + ": " + e.getMessage(), e);
            }
        });
    }

    private static BufferedImage readImage(byte[] bytes) throws IOException {
        if (bytes == null) {
            return null;
        }
        BufferedImage raw = ImageIO.read(new ByteArrayInputStream(bytes));
        if (raw == null) {
            return null;
        }
        BufferedImage argb = new BufferedImage(raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_INT_ARGB);
        argb.getGraphics().drawImage(raw, 0, 0, null);
        return argb;
    }

    /* ---------------- helpers ---------------- */

    private Font baseFont(Model.TextFontDef def) {
        return baseFonts.computeIfAbsent(def.key(), k -> {
            Path path = model.fontsDir().resolve(def.file());
            try {
                return Font.createFont(Font.TRUETYPE_FONT, path.toFile());
            } catch (Exception e) {
                throw new IllegalStateException("cannot load font " + def.file() + ": " + e.getMessage(), e);
            }
        });
    }

    /** Trims empty columns while keeping full height. Returns null when the glyph is blank. */
    private static BufferedImage trimColumns(BufferedImage source, int x, int y, int width, int height) {
        int left = -1;
        int right = -1;
        for (int ix = 0; ix < width; ix++) {
            for (int iy = 0; iy < height; iy++) {
                if ((source.getRGB(x + ix, y + iy) >>> 24) > 0) {
                    if (left < 0) {
                        left = ix;
                    }
                    right = ix;
                    break;
                }
            }
        }
        if (left < 0) {
            return null;
        }
        BufferedImage out = new BufferedImage(right - left + 1, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(source, 0, 0, out.getWidth(), height, x + left, y, x + right + 1, y + height, null);
        g.dispose();
        return out;
    }

    /** Forces alpha to fully on or fully off; the client's font atlas does not blend partial alpha. */
    private static void binarize(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                image.setRGB(x, y, (argb >>> 24) >= 128 ? (0xFF000000 | (argb & 0xFFFFFF)) : 0);
            }
        }
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
