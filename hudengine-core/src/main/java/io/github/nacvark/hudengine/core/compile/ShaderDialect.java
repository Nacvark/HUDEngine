package io.github.nacvark.hudengine.core.compile;

import java.util.List;

/**
 * The forms the vanilla {@code rendertype_text} shader has taken.
 *
 * HUDEngine replaces that shader, so its output has to match whatever the target client expects
 * to compile. The client has changed it five times in the supported range, and a pack that gets it
 * wrong does not degrade — the shader fails to compile and the HUD does not draw at all.
 *
 * What actually changed, in order:
 *
 * - 1.21.6 moved {@code ProjMat} and {@code ModelViewMat} out of plain uniforms and into
 *   uniform blocks, and split fog into a spherical and a cylindrical distance.
 * - 1.21.9 raised the GLSL version from 150 to 330.
 * - 26.1 replaced the direct lightmap texel fetch with a {@code sample_lightmap} helper.
 * - 26.2 renamed the shader to {@code text} and split it into preprocessor variants.
 * - 26.3 turned on separate shader objects: every input and output carries an explicit location,
 *   and {@code #moj_import} was replaced by {@code #include}.
 *
 * None of that touches the trick the engine relies on: the vertex position still arrives before
 * projection, and {@code ProjMat} is still readable, so a glyph can still be moved back on screen.
 * Only the surrounding boilerplate differs.
 *
 * Ranges are contiguous and cover the gaps between released pack formats, so a version released
 * between two known ones still lands on the nearest older dialect rather than on nothing.
 */
public enum ShaderDialect {

    /** 1.21.4 and 1.21.5: plain uniforms, single fog distance. */
    LEGACY("legacy", 46, 62, 150, false, false, false, "rendertype_text"),

    /** 1.21.6 to 1.21.8: uniform blocks and split fog, still GLSL 150. */
    UNIFORM_BLOCKS_150("ubo150", 63, 68, 150, true, false, false, "rendertype_text"),

    /** 1.21.9 to 1.21.11: the same, raised to GLSL 330. */
    UNIFORM_BLOCKS_330("ubo330", 69, 83, 330, true, false, false, "rendertype_text"),

    /** 26.1: adds the lightmap sampling helper. */
    LIGHTMAP_HELPER("lightmap", 84, 87, 330, true, true, false, "rendertype_text"),

    /**
     * 26.2: the shader was renamed and split into preprocessor variants.
     *
     * One source now serves world text, GUI text and see-through text, selected by
     * {@code IS_GUI} and {@code IS_SEE_THROUGH}. The GUI variant carries no fog and no lightmap, so
     * a replacement has to reproduce those guards or it will not compile in every variant the
     * client builds. The HUD itself is GUI text.
     */
    TEXT_VARIANTS("text", 88, 96, 330, true, true, false, "text"),

    /**
     * 26.3 and newer: the 26.2 shader with separate shader objects enabled.
     *
     * The client's fragment shader reads its inputs by location, so a replacement vertex shader has
     * to write the same outputs at the same locations or the two stages no longer link.
     */
    SEPARATE_OBJECTS("sso", 97, 99, 330, true, true, true, "text");

    /** Lowest pack format the engine supports at all. */
    public static final int MIN_PACK_FORMAT = 46;

    /** Highest pack format the ranges below cover. */
    public static final int MAX_PACK_FORMAT = 99;

    private final String id;
    private final int minFormat;
    private final int maxFormat;
    private final int glslVersion;
    private final boolean uniformBlocks;
    private final boolean lightmapHelper;
    private final boolean separateObjects;
    private final String shaderName;

    ShaderDialect(String id, int minFormat, int maxFormat, int glslVersion,
                  boolean uniformBlocks, boolean lightmapHelper, boolean separateObjects,
                  String shaderName) {
        this.id = id;
        this.minFormat = minFormat;
        this.maxFormat = maxFormat;
        this.glslVersion = glslVersion;
        this.uniformBlocks = uniformBlocks;
        this.lightmapHelper = lightmapHelper;
        this.separateObjects = separateObjects;
        this.shaderName = shaderName;
    }

    /**
     * Path of the vertex shader inside the pack.
     *
     * Only the vertex shader is replaced. Ours keeps exactly the outputs vanilla's does, so the
     * client's own fragment shader still fits, and not shipping one removes a file that would have
     * to be kept correct against every future change for no gain.
     */
    public String vertexShaderPath() {
        return "assets/minecraft/shaders/core/" + shaderName + ".vsh";
    }

    /** True when this dialect's shader is compiled once per variant with preprocessor guards. */
    public boolean preprocessorVariants() {
        return "text".equals(shaderName);
    }

    /** Short name used for this dialect's overlay directory inside the pack. */
    public String id() {
        return id;
    }

    public int minFormat() {
        return minFormat;
    }

    public int maxFormat() {
        return maxFormat;
    }

    public int glslVersion() {
        return glslVersion;
    }

    /** True when transforms come from uniform blocks and fog is split in two. */
    public boolean uniformBlocks() {
        return uniformBlocks;
    }

    /** True when the lightmap is read through {@code sample_lightmap} rather than a texel fetch. */
    public boolean lightmapHelper() {
        return lightmapHelper;
    }

    /**
     * True when inputs and outputs carry explicit locations, imports use {@code #include}, and the
     * shader declares the separate shader objects extension.
     */
    public boolean separateObjects() {
        return separateObjects;
    }

    public boolean covers(int packFormat) {
        return packFormat >= minFormat && packFormat <= maxFormat;
    }

    /** The dialect a client on this pack format needs. */
    public static ShaderDialect forPackFormat(int packFormat) {
        for (ShaderDialect dialect : values()) {
            if (dialect.covers(packFormat)) {
                return dialect;
            }
        }
        // Anything newer than the table knows about gets the newest dialect. It may be wrong, but a
        // guess at the newest shape stands a far better chance than the oldest one.
        return packFormat < MIN_PACK_FORMAT ? LEGACY : values()[values().length - 1];
    }

    /** Dialects other than the one written at the pack root, which need an overlay. */
    public static List<ShaderDialect> overlaysFor(ShaderDialect base) {
        return List.of(values()).stream().filter(dialect -> dialect != base).toList();
    }
}
