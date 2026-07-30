package io.github.nacvark.hudengine.core.compile;

import io.github.nacvark.hudengine.core.model.Compiled;
import io.github.nacvark.hudengine.core.model.ConfigurationException;
import io.github.nacvark.hudengine.core.model.Model;
import io.github.nacvark.hudengine.core.model.ModelValidator;
import io.github.nacvark.hudengine.core.util.EngineLogger;
import io.github.nacvark.hudengine.core.util.Json;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Entry point of the compiler: a folder of HUD configs in, a resource pack and a
 * {@link Compiled.Pack} out.
 *
 * The compiled model is the contract the renderer runs against. The pack is what the client
 * downloads. The optional JSON manifest is derived from the model and exists only for inspection.
 */
public final class HudPackCompiler {

    private HudPackCompiler() {
    }

    /** The vanilla boss bar sprite is 182x5; ours is a fully transparent copy of the same size. */
    private static final int BOSS_BAR_SPRITE_WIDTH = 182;
    private static final int BOSS_BAR_SPRITE_HEIGHT = 5;

    /** Size of the blank texture used to hide a vanilla sprite; transparent, so it is arbitrary. */
    private static final int BLANK_SPRITE_SIZE = 16;

    private static final String HUD_FONT = "hud";
    private static final String SPACE_FONT = "space";

    /**
     * @param bossBarColor which boss bar colour the HUD claims; the pack replaces that colour's
     *                     sprites with transparent ones, so the bar itself is invisible while its
     *                     title still renders
     * @param extraChars    characters added to every font on top of the languages it declares, for
     *                      symbols a HUD uses that belong to no particular script
     * @param minPackFormat oldest resource pack format the built pack declares support for; also
     *                      the format written at the pack root, with newer ones added as overlays
     * @param maxPackFormat newest format the pack declares support for
     * @param hiddenVanilla vanilla HUD elements to blank out, so a custom HUD does not sit on top
     *                      of the one the client draws by itself
     * @param bossBarLine   which boss bar line the HUD is built for, counting from one; only worth
     *                      raising on a server where another plugin reliably occupies the lines
     *                      above it
     */
    public record Options(
            String namespace,
            String bossBarColor,
            String packDescription,
            int minPackFormat,
            int maxPackFormat,
            String extraChars,
            Set<VanillaHud> hiddenVanilla,
            int bossBarLine
    ) {
        public Options {
            if (minPackFormat > maxPackFormat) {
                throw new IllegalArgumentException(
                        "min pack format " + minPackFormat + " is above max " + maxPackFormat);
            }
            if (bossBarLine < 1 || bossBarLine > Encoding.MAX_BOSS_BAR_LINE) {
                throw new IllegalArgumentException("boss bar line " + bossBarLine
                        + " is outside 1.." + Encoding.MAX_BOSS_BAR_LINE);
            }
            hiddenVanilla = hiddenVanilla == null ? Set.of() : Set.copyOf(hiddenVanilla);
        }

        public static Options defaults() {
            return new Options("hudengine", "yellow", "HUDEngine resource pack",
                    ShaderDialect.MIN_PACK_FORMAT, ShaderDialect.MAX_PACK_FORMAT, "",
                    Set.of(), 1);
        }

        /** Baseline the generated shader draws the HUD from. */
        int shaderOffset() {
            return Encoding.defaultOffset(bossBarLine);
        }

        /**
         * Whether the shader should blank the experience level number.
         *
         * Derived rather than stored: the level number is listed alongside the sprite-based elements
         * in the config, and having one field decide it keeps the two from disagreeing.
         */
        boolean hideVanillaLevelText() {
            return hiddenVanilla.contains(VanillaHud.LEVEL_TEXT);
        }
    }

    /**
     * Everything one compilation needs. Output paths may be null to skip writing that artefact.
     *
     * @param vanilla where vanilla glyph data comes from; defaults to the {@code vanilla}
     *                subdirectory of the config folder layered over the bundled copy
     */
    public record Request(
            Path configFolder,
            Path outDir,
            Path outZip,
            Path outManifest,
            Options options,
            VanillaGlyphSource vanilla,
            EngineLogger log
    ) {
        public Request {
            if (configFolder == null) {
                throw new IllegalArgumentException("configFolder is required");
            }
            if (options == null) {
                options = Options.defaults();
            }
            if (log == null) {
                log = EngineLogger.console();
            }
            if (vanilla == null) {
                // A vanilla folder next to the configs wins file by file; anything it does not
                // provide falls back to the copy bundled in the jar.
                vanilla = VanillaGlyphSource.layered(
                        VanillaGlyphSource.ofDirectory(configFolder.resolve("vanilla")),
                        VanillaGlyphSource.bundled());
            }
        }
    }

    public record Result(Compiled.Pack pack, Map<String, Object> manifest, List<String> report) {
    }

    public static Result compile(Request request) throws IOException {
        Options options = request.options();
        EngineLogger log = request.log();
        Model.Root model = Model.load(request.configFolder(), log);
        validate(model, log);
        List<String> report = new ArrayList<>();

        PackBuilder pack = new PackBuilder(options.namespace());
        Encoding.StateTable states = new Encoding.StateTable();
        Encoding.CharPool pool = new Encoding.CharPool();

        ImageCompiler images = new ImageCompiler(model, pack, pool, HUD_FONT, log);
        CompassCompiler compasses = new CompassCompiler(model, pack, pool, HUD_FONT, log);
        TextCompiler texts = new TextCompiler(model, pack, pool, HUD_FONT,
                options.extraChars(), request.vanilla(), log);

        Map<String, Compiled.Hud> huds = new LinkedHashMap<>();
        for (Model.HudDef hud : model.huds().values()) {
            List<Compiled.Element> elements = new ArrayList<>();
            for (Model.HudLayoutRef ref : hud.layouts()) {
                Model.LayoutDef layout = model.layouts().get(ref.layoutName());
                if (layout == null) {
                    throw new IllegalStateException("layout not found: " + ref.layoutName()
                            + " (referenced by hud " + hud.key() + ")");
                }
                compileLayout(ref, layout, model, states, images, compasses, texts, elements);
            }
            huds.put(hud.key(), new Compiled.Hud(hud.key(), elements));
        }

        writeSpaceFonts(pack);
        writePackFiles(pack, request, options, states);
        pack.write(request.outDir(), request.outZip());

        Compiled.Pack compiled = new Compiled.Pack(
                options.namespace(), pack.fontId(HUD_FONT), pack.fontId(SPACE_FONT),
                Encoding.SPACE_BASE, Encoding.SPACE_RANGE, options.bossBarColor(),
                buildStates(states), huds);

        Map<String, Object> manifest = toManifest(compiled);
        if (request.outManifest() != null) {
            Files.createDirectories(request.outManifest().toAbsolutePath().getParent());
            Files.writeString(request.outManifest(), Json.write(manifest));
        }

        report.add("render states: " + states.size());
        report.add("huds: " + huds.size());
        if (!options.hiddenVanilla().isEmpty()) {
            report.add("hidden vanilla elements: " + options.hiddenVanilla().stream()
                    .map(VanillaHud::configName).sorted().collect(Collectors.joining(", ")));
        }
        report.add("pack formats " + options.minPackFormat() + ".." + options.maxPackFormat()
                + ", shader dialects: " + dialectSummary(options));
        for (TextCompiler.GlyphSources font : texts.glyphSources()) {
            report.add(String.format("font %-24s vanilla=%d overrides=%d file=%d unavailable=%d",
                    font.font(), font.fromVanilla(), font.fromOverrides(), font.fromFont(), font.missing()));
            if (font.fromFont() == 0 && font.fromVanilla() > 0) {
                report.add("  the vanilla sheets covered everything; this font needs no file");
            } else if (font.fromFont() > 0) {
                report.add("  only the font file can draw: " + font.fontOnly());
            }
        }
        if (!texts.missingChars().isEmpty()) {
            report.add("characters no font could draw: " + summarise(texts.missingChars()));
        }
        return new Result(compiled, manifest, report);
    }

    /**
     * Reports everything wrong with the configuration before compiling any of it.
     *
     * Warnings are logged and compilation continues. Errors stop it, but only after all of them
     * have been collected: fixing a HUD one restart per typo is miserable.
     */
    private static void validate(Model.Root model, EngineLogger log) {
        List<ModelValidator.Problem> problems = ModelValidator.validate(model);
        if (problems.isEmpty()) {
            return;
        }
        for (ModelValidator.Problem problem : problems) {
            if (problem.severity() == ModelValidator.Severity.WARNING) {
                log.warn(problem.where() + ": " + problem.message());
            }
        }
        if (ModelValidator.hasErrors(problems)) {
            for (ModelValidator.Problem problem : problems) {
                if (problem.severity() == ModelValidator.Severity.ERROR) {
                    log.error(problem.where() + ": " + problem.message(), null);
                }
            }
            throw new ConfigurationException(problems);
        }
    }

    /**
     * Builds the shared advance table.
     *
     * It is registered under both the space font and the HUD font on purpose: when a horizontal
     * move does not switch fonts, adjacent glyphs of the same style stay in one component instead of
     * being split into a new one for every shift.
     */
    private static void writeSpaceFonts(PackBuilder pack) {
        Map<String, Integer> advances = new LinkedHashMap<>();
        for (int advance = -Encoding.SPACE_RANGE; advance <= Encoding.SPACE_RANGE; advance++) {
            advances.put(Encoding.CharPool.str(Encoding.SPACE_BASE + Encoding.SPACE_RANGE + advance), advance);
        }
        pack.addProvider(SPACE_FONT, PackBuilder.space(advances));
        pack.addProvider(HUD_FONT, PackBuilder.space(advances));
    }

    private static void writePackFiles(PackBuilder pack, Request request, Options options,
                                       Encoding.StateTable states) throws IOException {
        writeShaders(pack, options, states);

        Path icon = request.configFolder().resolve("pack.png");
        if (Files.isRegularFile(icon)) {
            pack.addFile("pack.png", Files.readAllBytes(icon));
        }

        // Blank the boss bar the HUD rides on, so only its title is visible.
        byte[] barSprite = pngBytes(new BufferedImage(
                BOSS_BAR_SPRITE_WIDTH, BOSS_BAR_SPRITE_HEIGHT, BufferedImage.TYPE_INT_ARGB));
        for (String sprite : List.of("background", "progress")) {
            pack.addFile("assets/minecraft/textures/gui/sprites/boss_bar/"
                    + options.bossBarColor() + "_" + sprite + ".png", barSprite);
        }
        writeHiddenVanillaSprites(pack, options);
    }

    /**
     * Replaces the sprites of the vanilla HUD elements the server asked to hide.
     *
     * One texture is reused for every path. It is fully transparent, so its size is irrelevant,
     * which also means there is no per-version size table to keep correct.
     */
    private static void writeHiddenVanillaSprites(PackBuilder pack, Options options) {
        if (options.hiddenVanilla().isEmpty()) {
            return;
        }
        byte[] blank = pngBytes(new BufferedImage(
                BLANK_SPRITE_SIZE, BLANK_SPRITE_SIZE, BufferedImage.TYPE_INT_ARGB));
        for (VanillaHud element : options.hiddenVanilla()) {
            for (String sprite : element.sprites()) {
                pack.addFile("assets/minecraft/textures/gui/sprites/" + sprite, blank);
            }
        }
    }

    /**
     * Writes one shader per dialect the pack covers, and the metadata that routes clients to it.
     *
     * The oldest supported dialect goes at the pack root and every newer one becomes an overlay.
     * A client applies only the overlay whose format range it falls in, so a single archive serves
     * every version instead of one download per release.
     */
    private static void writeShaders(PackBuilder pack, Options options, Encoding.StateTable states) {
        ShaderDialect base = ShaderDialect.forPackFormat(options.minPackFormat());
        pack.addFile(base.vertexShaderPath(),
                ShaderGen.vertex(base, states, options.hideVanillaLevelText(), options.shaderOffset()));

        List<Map<String, Object>> overlays = new ArrayList<>();
        for (ShaderDialect dialect : ShaderDialect.values()) {
            if (dialect == base) {
                continue;
            }
            int from = Math.max(dialect.minFormat(), options.minPackFormat());
            int to = Math.min(dialect.maxFormat(), options.maxPackFormat());
            if (from > to) {
                continue; // this dialect falls outside the declared range
            }
            String directory = "overlay_" + dialect.id();
            pack.addFile(directory + "/" + dialect.vertexShaderPath(),
                    ShaderGen.vertex(dialect, states, options.hideVanillaLevelText(),
                            options.shaderOffset()));

            overlays.add(formatRange(from, to, options, directory));
        }

        Map<String, Object> packMeta = new LinkedHashMap<>();
        packMeta.put("pack_format", options.minPackFormat());
        packMeta.putAll(formatRange(options.minPackFormat(), options.maxPackFormat(), options, null));
        packMeta.put("description", options.packDescription());

        Map<String, Object> mcmeta = new LinkedHashMap<>();
        mcmeta.put("pack", packMeta);
        if (!overlays.isEmpty()) {
            mcmeta.put("overlays", Map.of("entries", overlays));
        }
        pack.addFile("pack.mcmeta", Json.write(mcmeta));
    }

    /**
     * Format below which the old {@code supported_formats} spelling is required, and at or above
     * which it is forbidden.
     */
    private static final int NEW_FORMAT_FIELDS_FROM = 65;

    /**
     * Declares a version range the way every supported client reads it.
     *
     * The spelling changed in 1.21.9: {@code supported_formats} was replaced by
     * {@code min_format} and {@code max_format}. Older clients ignore the new pair and newer ones
     * ignore the old field, so a pack reaching across that line has to carry both — which is exactly
     * what a HUD pack spanning 1.21.4 to 26.x does.
     *
     * The old field is not merely optional above the line, it is rejected: a pack whose minimum
     * is 65 or higher and which still declares {@code supported_formats} is refused outright. So it
     * is emitted only when the range actually reaches below that.
     */
    private static Map<String, Object> formatRange(int from, int to, Options options, String directory) {
        Map<String, Object> range = new LinkedHashMap<>();
        if (options.minPackFormat() < NEW_FORMAT_FIELDS_FROM) {
            range.put(directory == null ? "supported_formats" : "formats", List.of(from, to));
        }
        range.put("min_format", from);
        range.put("max_format", to);
        if (directory != null) {
            range.put("directory", directory);
        }
        return range;
    }

    private static List<Compiled.State> buildStates(Encoding.StateTable states) {
        List<Compiled.State> out = new ArrayList<>();
        for (Map.Entry<Encoding.RenderState, Integer> entry : states.entries()) {
            Encoding.RenderState state = entry.getKey();
            out.add(new Compiled.State(entry.getValue(),
                    state.anchorX(), state.anchorY(), state.layer(), state.outline()));
        }
        return out;
    }

    /* ---------------- layout compilation ---------------- */

    private record Layered(int layer, Compiled.Element element) {
    }

    private static void compileLayout(Model.HudLayoutRef ref, Model.LayoutDef layout, Model.Root model,
                                      Encoding.StateTable states, ImageCompiler images,
                                      CompassCompiler compasses, TextCompiler texts,
                                      List<Compiled.Element> out) {
        double anchorX = ref.anchorX();
        double anchorY = ref.anchorY();

        // Depth testing is off, so whatever is drawn last ends up on top. Sorting by layer before
        // emitting is therefore what actually implements the layer setting.
        List<Layered> pending = new ArrayList<>();

        for (Model.LayoutImage element : layout.images()) {
            pending.add(compileImage(element, layout, model, states, images, anchorX, anchorY));
        }
        for (Model.LayoutHead element : layout.heads()) {
            pending.add(compileHead(element, layout, model, states, images, anchorX, anchorY));
        }
        for (Model.LayoutCompass element : layout.compasses()) {
            pending.add(compileCompass(element, layout, model, states, compasses, texts, anchorX, anchorY));
        }
        int index = 0;
        for (Model.LayoutText element : layout.texts()) {
            pending.add(compileText(element, ++index, layout, model, states, texts, anchorX, anchorY));
        }

        pending.sort(Comparator.comparingInt(Layered::layer)); // stable: ties keep config order
        for (Layered layered : pending) {
            out.add(layered.element());
        }
    }

    private static Layered compileImage(Model.LayoutImage element, Model.LayoutDef layout,
                                        Model.Root model, Encoding.StateTable states,
                                        ImageCompiler images, double anchorX, double anchorY) {
        Model.ImageDef def = model.images().get(element.name());
        if (def == null) {
            throw new IllegalStateException("image not found: " + element.name()
                    + " (referenced by layout " + layout.key() + ")");
        }
        int stateId = states.idOf(new Encoding.RenderState(anchorX, anchorY, element.layer(), false));
        int y = (int) Math.round(layout.y() + element.y());
        double x = layout.x() + element.x();
        String condition = element.condition() == null ? null : Compiled.normalizeKey(element.condition());

        return switch (def.type()) {
            case SINGLE -> {
                ImageCompiler.Sliced sliced = images.single(def, stateId, y);
                yield new Layered(element.layer(), new Compiled.Img(def.key(), x,
                        toGlyphs(sliced.parts()), sliced.cols(), condition));
            }
            case LISTENER -> new Layered(element.layer(), new Compiled.Bar(def.key(), x,
                    Compiled.normalizeKey(def.listenerValue()),
                    Compiled.normalizeKey(def.listenerMax()),
                    toGlyphs(images.listenerFrames(def, stateId, y)), condition));
            case FOLLOW -> {
                Map<String, Compiled.Glyph> children = new LinkedHashMap<>();
                images.followChildren(def, stateId, y)
                        .forEach((key, glyph) -> children.put(key, toGlyph(glyph)));
                yield new Layered(element.layer(), new Compiled.Follow(def.key(), x,
                        Compiled.normalizeKey(def.followPlaceholder()), children, condition));
            }
        };
    }

    private static Layered compileHead(Model.LayoutHead element, Model.LayoutDef layout,
                                       Model.Root model, Encoding.StateTable states,
                                       ImageCompiler images, double anchorX, double anchorY) {
        Model.HeadDef def = model.heads().get(element.name());
        if (def == null) {
            throw new IllegalStateException("head not found: " + element.name()
                    + " (referenced by layout " + layout.key() + ")");
        }
        int stateId = states.idOf(new Encoding.RenderState(anchorX, anchorY, element.layer(), false));
        int y = (int) Math.round(layout.y() + element.y());
        ImageCompiler.HeadGlyphs glyphs = images.head(def, stateId, y);
        return new Layered(element.layer(), new Compiled.Head(def.key(), layout.x() + element.x(),
                glyphs.pixel(), glyphs.pixelAdvance(), glyphs.rowCps()));
    }

    private static Layered compileCompass(Model.LayoutCompass element, Model.LayoutDef layout,
                                          Model.Root model, Encoding.StateTable states,
                                          CompassCompiler compasses, TextCompiler texts,
                                          double anchorX, double anchorY) {
        Model.CompassDef def = model.compasses().get(element.name());
        if (def == null) {
            throw new IllegalStateException("compass not found: " + element.name()
                    + " (referenced by layout " + layout.key() + ")");
        }
        int stateId = states.idOf(new Encoding.RenderState(
                anchorX, anchorY, element.layer(), element.outline() > 0));
        int y = (int) Math.round(layout.y() + element.y());

        Compiled.CompassDist dist = null;
        if (def.distanceText() != null) {
            dist = compileDistanceText(def, element, layout, model, states, texts, anchorX, anchorY);
        }
        Compiled.CompassEl compiled = compasses.element(def,
                layout.key() + "_compass_" + element.name(),
                layout.x() + element.x(), element.outline() > 0, stateId, y, dist);
        return new Layered(element.layer(), compiled);
    }

    /**
     * The distance label borrows the text pipeline: a synthetic text element gives it the same glyph
     * set and metrics as any other text using that font, so it lines up with the rest of the HUD.
     */
    private static Compiled.CompassDist compileDistanceText(Model.CompassDef def,
                                                            Model.LayoutCompass element,
                                                            Model.LayoutDef layout, Model.Root model,
                                                            Encoding.StateTable states,
                                                            TextCompiler texts,
                                                            double anchorX, double anchorY) {
        Model.CompassDistDef distDef = def.distanceText();
        Model.TextFontDef font = model.textFonts().get(distDef.font());
        if (font == null) {
            throw new IllegalStateException("compass " + def.key()
                    + ": distance-text font not found: " + distDef.font());
        }
        int stateId = states.idOf(new Encoding.RenderState(
                anchorX, anchorY, element.layer(), distDef.outline() > 0));
        Model.LayoutText synthetic = new Model.LayoutText(
                "compass_distance", "", 0, 0, distDef.scale(),
                distDef.color(), "left", distDef.outline(), element.layer(), null, null);
        int y = (int) Math.round(layout.y() + element.y() + distDef.y());

        TextCompiler.TextGlyphs glyphs = texts.element(font, synthetic, stateId, y);
        Map<String, Compiled.Glyph> mapped = new LinkedHashMap<>();
        glyphs.glyphs().forEach((ch, value) -> mapped.put(ch, new Compiled.Glyph(value[0], value[1])));

        return new Compiled.CompassDist(distDef.x(), distDef.focus(),
                Compiled.parseColor(distDef.color()), distDef.outline() > 0,
                distDef.suffix(), glyphs.spaceAdvance(), mapped);
    }

    private static Layered compileText(Model.LayoutText element, int index, Model.LayoutDef layout,
                                       Model.Root model, Encoding.StateTable states,
                                       TextCompiler texts, double anchorX, double anchorY) {
        Model.TextFontDef def = model.textFonts().get(element.name());
        if (def == null) {
            throw new IllegalStateException("font not found: " + element.name()
                    + " (referenced by layout " + layout.key() + ")");
        }
        int stateId = states.idOf(new Encoding.RenderState(
                anchorX, anchorY, element.layer(), element.outline() > 0));
        int y = (int) Math.round(layout.y() + element.y());

        TextCompiler.TextGlyphs compiled = texts.element(def, element, stateId, y);
        Map<Integer, Compiled.Glyph> glyphs = new LinkedHashMap<>();
        compiled.glyphs().forEach((ch, value) ->
                glyphs.put(ch.codePointAt(0), new Compiled.Glyph(value[0], value[1])));

        String colorByKey = element.colorByKey() == null
                ? null : Compiled.normalizeKey(element.colorByKey());
        Map<String, Integer> colorBy = null;
        if (colorByKey != null && element.colorByMap() != null) {
            colorBy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : element.colorByMap().entrySet()) {
                colorBy.put(entry.getKey(), Compiled.parseColor(entry.getValue()));
            }
            colorBy = Map.copyOf(colorBy);
        }

        return new Layered(element.layer(), new Compiled.Text(
                layout.key() + "_text_" + index, layout.x() + element.x(),
                element.align(), Compiled.parseColor(element.color()), element.outline() > 0,
                compiled.spaceAdvance(), compiled.height(),
                Compiled.parsePattern(element.pattern()), glyphs, colorByKey, colorBy));
    }

    private static List<Compiled.Glyph> toGlyphs(List<ImageCompiler.Glyph> source) {
        List<Compiled.Glyph> out = new ArrayList<>(source.size());
        for (ImageCompiler.Glyph glyph : source) {
            out.add(toGlyph(glyph));
        }
        return List.copyOf(out);
    }

    private static Compiled.Glyph toGlyph(ImageCompiler.Glyph glyph) {
        return new Compiled.Glyph(glyph.cp(), glyph.width());
    }

    /* ---------------- manifest ---------------- */

    private static Map<String, Object> toManifest(Compiled.Pack pack) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", 2);
        manifest.put("namespace", pack.namespace());
        manifest.put("hudFont", pack.hudFont());
        manifest.put("space", Map.of("font", pack.spaceFont(),
                "base", pack.spaceBase(), "range", pack.spaceRange()));
        manifest.put("bossBarColor", pack.bossBarColor());

        List<Map<String, Object>> states = new ArrayList<>();
        for (Compiled.State state : pack.states()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", state.id());
            entry.put("anchorX", state.anchorX());
            entry.put("anchorY", state.anchorY());
            entry.put("layer", state.layer());
            entry.put("outline", state.outline());
            states.add(entry);
        }
        manifest.put("states", states);

        Map<String, Object> huds = new LinkedHashMap<>();
        pack.huds().forEach((key, hud) -> {
            List<Map<String, Object>> elements = new ArrayList<>();
            for (Compiled.Element element : hud.elements()) {
                elements.add(elementJson(element));
            }
            huds.put(key, Map.of("elements", elements));
        });
        manifest.put("huds", huds);
        return manifest;
    }

    private static Map<String, Object> elementJson(Compiled.Element element) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("key", element.key());
        json.put("x", element.x());
        switch (element) {
            case Compiled.Img e -> {
                json.put("type", "image");
                if (e.condition() != null) {
                    json.put("condition", e.condition());
                }
                json.put("parts", glyphPairs(e.parts()));
                json.put("cols", e.cols());
            }
            case Compiled.Bar e -> {
                json.put("type", "bar");
                if (e.condition() != null) {
                    json.put("condition", e.condition());
                }
                json.put("value", e.valueKey());
                json.put("max", e.maxKey());
                json.put("frames", glyphPairs(e.frames()));
            }
            case Compiled.Follow e -> {
                json.put("type", "follow");
                json.put("placeholder", e.placeholderKey());
                if (e.condition() != null) {
                    json.put("condition", e.condition());
                }
                Map<String, List<Integer>> children = new LinkedHashMap<>();
                e.children().forEach((key, glyph) -> children.put(key, List.of(glyph.cp(), glyph.width())));
                json.put("children", children);
            }
            case Compiled.Head e -> {
                json.put("type", "head");
                json.put("pixel", e.pixel());
                json.put("pixelAdvance", e.pixelAdvance());
                json.put("rows", e.rowCps());
            }
            case Compiled.CompassEl e -> {
                json.put("type", "compass");
                json.put("length", e.length());
                json.put("space", e.space());
                json.put("div", e.div());
                json.put("slots", new ArrayList<>(e.slots().keySet()));
            }
            case Compiled.Text e -> {
                json.put("type", "text");
                if (e.colorByKey() != null) {
                    json.put("colorBy", e.colorByKey());
                }
                json.put("align", e.align());
                json.put("color", String.format("#%06X", e.color()));
                json.put("outline", e.outline());
                json.put("space", e.spaceAdvance());
                json.put("height", e.height());

                List<Map<String, Object>> segments = new ArrayList<>();
                for (Compiled.Seg segment : e.segments()) {
                    segments.add(Map.of(segment.placeholder() ? "placeholder" : "literal", segment.value()));
                }
                json.put("pattern", segments);

                Map<String, List<Integer>> glyphs = new LinkedHashMap<>();
                e.glyphs().forEach((cp, glyph) -> glyphs.put(
                        new String(Character.toChars(cp)), List.of(glyph.cp(), glyph.width())));
                json.put("glyphs", glyphs);
            }
        }
        return json;
    }

    private static List<List<Integer>> glyphPairs(List<Compiled.Glyph> glyphs) {
        List<List<Integer>> out = new ArrayList<>(glyphs.size());
        for (Compiled.Glyph glyph : glyphs) {
            out.add(List.of(glyph.cp(), glyph.width()));
        }
        return out;
    }

    private static String dialectSummary(Options options) {
        List<String> covered = new ArrayList<>();
        for (ShaderDialect dialect : ShaderDialect.values()) {
            int from = Math.max(dialect.minFormat(), options.minPackFormat());
            int to = Math.min(dialect.maxFormat(), options.maxPackFormat());
            if (from <= to) {
                covered.add(dialect.id() + "(" + from + ".." + to + ")");
            }
        }
        return String.join(" ", covered);
    }

    /** A whole unused Unicode block can end up here, so the report shows a sample, not the lot. */
    private static String summarise(Collection<String> characters) {
        int limit = 30;
        String sample = characters.stream().limit(limit).collect(Collectors.joining());
        return characters.size() <= limit
                ? sample
                : sample + " ... and " + (characters.size() - limit) + " more";
    }

    private static byte[] pngBytes(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to encode a PNG", e);
        }
    }
}
