package io.github.nacvark.hudengine.core.compile;

import io.github.nacvark.hudengine.core.model.Model;
import io.github.nacvark.hudengine.core.util.EngineLogger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns image and head elements into providers of the shared HUD font.
 *
 * Scaling is left to the client via the provider's {@code height}, so nothing is resampled on the
 * server and sprites stay crisp.
 */
public final class ImageCompiler {

    /**
     * The client packs font glyphs into 256x256 atlases. A glyph larger than that does not fit and
     * breaks the whole font, so oversized images are sliced into a grid.
     */
    public static final int MAX_GLYPH_SIZE = 256;

    /** A glyph reference for the renderer: codepoint plus advance. */
    public record Glyph(int cp, int width) {
    }

    /** A sliced image: glyphs in row-major order, and how many columns each row has. */
    public record Sliced(List<Glyph> parts, int cols) {
    }

    /** Head glyphs: one codepoint per face row, plus the pixel size and its advance. */
    public record HeadGlyphs(List<Integer> rowCps, int pixelAdvance, int pixel) {
    }

    private final Model.Root model;
    private final PackBuilder pack;
    private final Encoding.CharPool pool;
    private final String fontName;
    private final EngineLogger log;

    private final Map<String, BufferedImage> sourceCache = new LinkedHashMap<>();
    private final Map<String, String> textureCache = new LinkedHashMap<>();

    public ImageCompiler(Model.Root model, PackBuilder pack, Encoding.CharPool pool,
                         String fontName, EngineLogger log) {
        this.model = model;
        this.pack = pack;
        this.pool = pool;
        this.fontName = fontName;
        this.log = log;
    }

    public String fontId() {
        return pack.fontId(fontName);
    }

    /**
     * The client's own advance formula for a bitmap glyph:
     * {@code (int) (0.5 + actualWidth * (height / spriteRowHeight)) + 1}.
     *
     * Reproducing it exactly is what makes the server-side layout match the screen to the pixel.
     */
    public static int advance(int sourceWidth, int sourceHeight, int displayHeight) {
        return (int) (0.5 + sourceWidth * (displayHeight / (double) sourceHeight)) + 1;
    }

    /**
     * Compiles a single sprite into a grid of glyphs at {@code (stateId, y)}.
     *
     * Images wider or taller than {@link #MAX_GLYPH_SIZE} are cut up automatically: columns are
     * drawn flush against each other and each row becomes its own provider, offset by the rounded
     * display heights of the rows above it. At {@code scale: 1} the cuts are pixel-exact; at any
     * other scale rounding can shift a seam by a pixel.
     */
    public Sliced single(Model.ImageDef def, int stateId, int y) {
        BufferedImage source = source(def.file());
        double scale = def.scale();

        List<int[]> columns = cuts(source.getWidth());
        List<int[]> rows = cuts(source.getHeight());

        if ((columns.size() > 1 || rows.size() > 1) && scale != 1.0) {
            log.warn("image \"" + def.key() + "\" is larger than " + MAX_GLYPH_SIZE + " px and has scale="
                    + scale + "; seams may be off by a pixel, prefer scale: 1");
        }

        List<Glyph> parts = new ArrayList<>();
        int yOffset = 0;
        for (int r = 0; r < rows.size(); r++) {
            int sourceY = rows.get(r)[0];
            int sourceH = rows.get(r)[1];
            int displayH = Math.max(1, (int) Math.round(sourceH * scale));
            int ascent = Encoding.ascent(stateId, y + yOffset);

            for (int c = 0; c < columns.size(); c++) {
                int sourceX = columns.get(c)[0];
                int sourceW = columns.get(c)[1];
                String cacheKey = def.file() + "#r" + r + "c" + c + "|" + columns.size() + "x" + rows.size();
                String textureName = columns.size() == 1 && rows.size() == 1
                        ? "image_" + def.key() + ".png"
                        : "image_" + def.key() + "_r" + (r + 1) + "c" + (c + 1) + ".png";

                String texture = textureCache.computeIfAbsent(cacheKey,
                        k -> pack.addTexture(textureName, crop(source, sourceX, sourceY, sourceW, sourceH)));
                int cp = pool.next();
                pack.addProvider(fontName, PackBuilder.bitmap(texture, ascent, displayH,
                        List.of(Encoding.CharPool.str(cp))));
                parts.add(new Glyph(cp, advance(sourceW, sourceH, displayH)));
            }
            yOffset += displayH;
        }
        return new Sliced(List.copyOf(parts), columns.size());
    }

    /** Splits a dimension into runs of at most {@link #MAX_GLYPH_SIZE}: pairs of start and length. */
    private static List<int[]> cuts(int size) {
        List<int[]> out = new ArrayList<>();
        for (int start = 0; start < size; start += MAX_GLYPH_SIZE) {
            out.add(new int[]{start, Math.min(MAX_GLYPH_SIZE, size - start)});
        }
        return out;
    }

    /** Slices a bar into {@code split} fill steps. Step zero is not generated: it is the empty bar. */
    public List<Glyph> listenerFrames(Model.ImageDef def, int stateId, int y) {
        BufferedImage source = source(def.file());
        int height = Math.max(1, (int) Math.round(source.getHeight() * def.scale()));
        boolean fromRight = "right".equalsIgnoreCase(def.splitType());

        List<Glyph> frames = new ArrayList<>(def.split());
        for (int i = 1; i <= def.split(); i++) {
            int width = Math.max(1, (int) Math.floor(source.getWidth() * (long) i / (double) def.split()));
            int x = fromRight ? source.getWidth() - width : 0;
            BufferedImage frame = crop(source, x, 0, width, source.getHeight());

            String texture = pack.addTexture("image_" + def.key() + "_" + i + ".png", frame);
            int cp = pool.next();
            pack.addProvider(fontName, PackBuilder.bitmap(texture, Encoding.ascent(stateId, y), height,
                    List.of(Encoding.CharPool.str(cp))));
            frames.add(new Glyph(cp, advance(width, source.getHeight(), height)));
        }
        return frames;
    }

    /** Compiles every child of a follow image; they all sit at the parent's position. */
    public Map<String, Glyph> followChildren(Model.ImageDef def, int stateId, int y) {
        Map<String, Glyph> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> child : def.children().entrySet()) {
            Model.ImageDef childDef = model.images().get(child.getValue());
            if (childDef == null) {
                throw new IllegalStateException("follow child not found: " + child.getValue()
                        + " (referenced by " + def.key() + ")");
            }
            out.put(child.getKey(), singleGlyph(childDef, stateId, y));
        }
        return out;
    }

    /** Follow children must fit in one glyph; auto-slicing them would break the chooser. */
    private Glyph singleGlyph(Model.ImageDef def, int stateId, int y) {
        List<Glyph> parts = single(def, stateId, y).parts();
        if (parts.size() != 1) {
            throw new IllegalStateException("follow image \"" + def.key() + "\" is wider than "
                    + MAX_GLYPH_SIZE + " px; follow images must fit in a single glyph");
        }
        return parts.getFirst();
    }

    /**
     * Compiles a head into eight glyphs, one per face row, each a white square of {@code pixel} size.
     * The renderer tints each pixel by setting the component's colour.
     */
    public HeadGlyphs head(Model.HeadDef def, int stateId, int y) {
        int pixel = def.pixel();
        String texture = textureCache.computeIfAbsent("#pixel_" + pixel,
                k -> pack.addTexture("pixel_" + pixel + ".png", whiteSquare(pixel)));

        List<Integer> rows = new ArrayList<>(8);
        for (int row = 0; row < 8; row++) {
            int cp = pool.next();
            pack.addProvider(fontName, PackBuilder.bitmap(texture,
                    Encoding.ascent(stateId, y + row * pixel), pixel,
                    List.of(Encoding.CharPool.str(cp))));
            rows.add(cp);
        }
        return new HeadGlyphs(rows, pixel + 1, pixel);
    }

    private BufferedImage source(String file) {
        return sourceCache.computeIfAbsent(file, f -> {
            Path path = model.assetsDir().resolve(f);
            try {
                BufferedImage raw = ImageIO.read(path.toFile());
                if (raw == null) {
                    throw new IOException("not a readable image: " + path);
                }
                return toArgb(raw);
            } catch (IOException e) {
                throw new IllegalStateException("asset not found or unreadable: " + f, e);
            }
        });
    }

    private static BufferedImage toArgb(BufferedImage raw) {
        BufferedImage argb = new BufferedImage(raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_INT_ARGB);
        argb.getGraphics().drawImage(raw, 0, 0, null);
        return argb;
    }

    private static BufferedImage crop(BufferedImage source, int x, int y, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(source, 0, 0, width, height, x, y, x + width, y + height, null);
        return out;
    }

    private static BufferedImage whiteSquare(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        return image;
    }
}
