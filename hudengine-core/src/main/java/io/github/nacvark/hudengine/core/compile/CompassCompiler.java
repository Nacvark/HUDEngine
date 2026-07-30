package io.github.nacvark.hudengine.core.compile;

import io.github.nacvark.hudengine.core.model.Compiled;
import io.github.nacvark.hudengine.core.model.Model;
import io.github.nacvark.hudengine.core.util.EngineLogger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles a compass into font glyphs.
 *
 * A compass is a ribbon of columns that scrolls with the player's yaw. Because the pack is static,
 * every icon has to be baked in advance at every size it can appear at: {@code div = ceil(length/2)}
 * variants, where variant {@code i} runs from the centre of the window out to its edge and is scaled
 * by {@code (scaleA + scaleB * i) * icon.scale}. With {@code apply-opacity} the variant also fades
 * towards the edge.
 *
 * Variant textures are cached per compass, slot and index and shared between layouts; providers
 * and codepoints are per use, since each use sits at its own ascent.
 */
public final class CompassCompiler {

    /** A baked variant texture together with the source dimensions its advance is derived from. */
    private record Baked(String texture, int width, int height) {
    }

    private static final double MIN_SCALE = 0.05;

    private final Model.Root model;
    private final PackBuilder pack;
    private final Encoding.CharPool pool;
    private final String fontName;
    private final EngineLogger log;

    private final Map<String, Baked> variantCache = new LinkedHashMap<>();
    private final Map<String, BufferedImage> sourceCache = new LinkedHashMap<>();

    public CompassCompiler(Model.Root model, PackBuilder pack, Encoding.CharPool pool,
                           String fontName, EngineLogger log) {
        this.model = model;
        this.pack = pack;
        this.pool = pool;
        this.fontName = fontName;
        this.log = log;
    }

    /** Compiles one use of a compass definition at {@code (stateId, y)}. */
    public Compiled.CompassEl element(Model.CompassDef def, String elementKey, double x,
                                      boolean outline, int stateId, int y, Compiled.CompassDist dist) {
        int div = def.div();
        Map<String, Compiled.CompassSlot> slots = new LinkedHashMap<>();

        for (Map.Entry<String, Model.CompassIcon> entry : def.icons().entrySet()) {
            String slot = entry.getKey();
            Model.CompassIcon icon = entry.getValue();

            BufferedImage image = trimmed(icon.file());
            if (image == null) {
                log.warn("compass " + def.key() + ": icon " + slot + " (" + icon.file()
                        + ") is missing or fully transparent, skipping");
                continue;
            }

            double fullHeight = image.getHeight() * def.baseScale() * icon.scale();
            int maxHeight = Math.max(1, (int) Math.round(fullHeight));

            List<Compiled.Glyph> variants = new ArrayList<>(div);
            for (int i = 0; i < div; i++) {
                double multiplier = def.scaleA() + def.scaleB() * i;
                if (multiplier <= 0) {
                    log.warn("compass " + def.key() + ": scale-equation produced " + multiplier
                            + " at step " + i + ", clamping to " + MIN_SCALE);
                    multiplier = MIN_SCALE;
                }
                int height = Math.max(1, (int) Math.round(fullHeight * multiplier));

                double alpha = icon.opacity();
                if (def.applyOpacity()) {
                    alpha *= Math.sin((div - i) / (double) div * Math.PI / 2);
                }

                Baked baked = bake(def.key(), slot, i, image, alpha);
                // Variants are centred against the largest one so the ribbon does not bob.
                int providerY = Math.max(0, y + icon.y() + (maxHeight - height) / 2);
                int cp = pool.next();
                pack.addProvider(fontName, PackBuilder.bitmap(baked.texture(),
                        Encoding.ascent(stateId, providerY), height,
                        List.of(Encoding.CharPool.str(cp))));
                variants.add(new Compiled.Glyph(cp,
                        ImageCompiler.advance(baked.width(), baked.height(), height)));
            }
            slots.put(slot, new Compiled.CompassSlot(icon.x(), List.copyOf(variants)));
        }

        return new Compiled.CompassEl(elementKey, x, def.length(), def.space(), div, outline,
                Map.copyOf(slots), dist);
    }

    /** Bakes a variant's opacity into its texture, since a pack cannot vary alpha at runtime. */
    private Baked bake(String defKey, String slot, int index, BufferedImage image, double alpha) {
        String key = defKey + "|" + slot + "|" + index + "|" + Math.round(alpha * 1000);
        return variantCache.computeIfAbsent(key, k -> {
            BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    int a = Math.clamp(Math.round(((argb >>> 24) & 0xFF) * alpha), 0, 255);
                    out.setRGB(x, y, (a << 24) | (argb & 0xFFFFFF));
                }
            }
            String name = "compass_" + sanitize(defKey) + "_" + sanitize(slot) + "_" + (index + 1) + ".png";
            return new Baked(pack.addTexture(name, out), image.getWidth(), image.getHeight());
        });
    }

    /** Loads an icon and trims its transparent border so scaling stays centred on the artwork. */
    private BufferedImage trimmed(String file) {
        if (file == null) {
            return null;
        }
        return sourceCache.computeIfAbsent(file, f -> {
            Path path = model.assetsDir().resolve(f.replace('/', java.io.File.separatorChar));
            BufferedImage raw;
            try {
                raw = ImageIO.read(path.toFile());
            } catch (IOException e) {
                return null;
            }
            if (raw == null) {
                return null;
            }
            BufferedImage argb = new BufferedImage(raw.getWidth(), raw.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            argb.getGraphics().drawImage(raw, 0, 0, null);

            int left = argb.getWidth();
            int right = -1;
            int top = argb.getHeight();
            int bottom = -1;
            for (int y = 0; y < argb.getHeight(); y++) {
                for (int x = 0; x < argb.getWidth(); x++) {
                    if ((argb.getRGB(x, y) >>> 24) > 0) {
                        left = Math.min(left, x);
                        right = Math.max(right, x);
                        top = Math.min(top, y);
                        bottom = Math.max(bottom, y);
                    }
                }
            }
            if (right < 0) {
                return null;
            }
            BufferedImage crop = new BufferedImage(right - left + 1, bottom - top + 1,
                    BufferedImage.TYPE_INT_ARGB);
            crop.getGraphics().drawImage(argb, 0, 0, crop.getWidth(), crop.getHeight(),
                    left, top, right + 1, bottom + 1, null);
            return crop;
        });
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
