package io.github.nacvark.hudengine.core.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates the {@code rendertype_text} core shaders that put HUD glyphs back on screen.
 *
 * See {@link Encoding} for what the shader decodes and why, and {@link ShaderDialect} for how the
 * vanilla shader has changed across versions.
 *
 * Both files are emitted together for a given dialect, so they always agree with each other. What
 * they must also agree with is the client: the imports, the uniform sources and the fog helpers all
 * have to be the ones that version provides.
 *
 * Fragments are assembled as indented lines rather than by substituting into nested text blocks.
 * Text blocks strip their own common indentation, so a block pasted into another arrives flattened,
 * and the generated shader is something people will open and read.
 */
public final class ShaderGen {

    private ShaderGen() {
    }

    /** Indentation of a statement inside {@code main}, matching vanilla's own formatting. */
    private static final String INDENT = "    ";

    /** Guard around everything the GUI and see-through variants of the 26.2 shader leave out. */
    private static final String WORLD_ONLY = "#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)";

    public static String vertex(ShaderDialect dialect, Encoding.StateTable states,
                                boolean hideVanillaLevelText) {
        return vertex(dialect, states, hideVanillaLevelText, Encoding.DEFAULT_OFFSET);
    }

    /**
     * @param defaultOffset baseline the HUD is drawn from, from
     *                      {@link Encoding#defaultOffset(int)}
     */
    public static String vertex(ShaderDialect dialect, Encoding.StateTable states,
                                boolean hideVanillaLevelText, int defaultOffset) {
        if (dialect.preprocessorVariants()) {
            return variantVertex(dialect, states, hideVanillaLevelText, defaultOffset);
        }
        List<String> out = new ArrayList<>();
        out.add("#version " + dialect.glslVersion());
        out.add("");

        out.add("#moj_import <minecraft:fog.glsl>");
        if (dialect.uniformBlocks()) {
            out.add("#moj_import <minecraft:dynamictransforms.glsl>");
            out.add("#moj_import <minecraft:projection.glsl>");
        }
        if (dialect.lightmapHelper()) {
            out.add("#moj_import <minecraft:sample_lightmap.glsl>");
        }
        out.add("");

        out.add("#define HEIGHT_BIT " + Encoding.HEIGHT_BIT);
        out.add("#define MAX_BIT " + Encoding.MAX_BIT);
        out.add("#define ADD_OFFSET " + Encoding.ADD_OFFSET);
        out.add("#define DEFAULT_OFFSET " + defaultOffset);
        out.add("");

        out.add("in vec3 Position;");
        out.add("in vec4 Color;");
        out.add("in vec2 UV0;");
        out.add("in ivec2 UV2;");
        out.add("");

        out.add("uniform sampler2D Sampler2;");
        if (!dialect.uniformBlocks()) {
            // Before 1.21.6 these are plain uniforms. Declaring them once the client moved them into
            // a uniform block is a redefinition and the shader fails to compile.
            out.add("uniform mat4 ModelViewMat;");
            out.add("uniform mat4 ProjMat;");
            out.add("uniform int FogShape;");
        }
        out.add("");

        if (dialect.uniformBlocks()) {
            out.add("out float sphericalVertexDistance;");
            out.add("out float cylindricalVertexDistance;");
        } else {
            out.add("out float vertexDistance;");
        }
        out.add("out vec4 vertexColor;");
        out.add("out vec2 texCoord0;");
        out.add("");

        if (hideVanillaLevelText) {
            out.addAll(hideLevelHelper());
            out.add("");
        }

        out.add("void main() {");
        out.add(INDENT + "vec3 pos = Position;");
        out.add(INDENT + "vec2 ui = ceil(2.0 / vec2(ProjMat[0][0], -ProjMat[1][1]));");
        out.add(INDENT + "vertexColor = Color * " + (dialect.lightmapHelper()
                ? "sample_lightmap(Sampler2, UV2);"
                : "texelFetch(Sampler2, UV2 / 16, 0);"));
        out.add("");

        out.addAll(decode(states, hideVanillaLevelText));
        out.add("");

        if (dialect.uniformBlocks()) {
            out.add(INDENT + "sphericalVertexDistance = fog_spherical_distance(pos);");
            out.add(INDENT + "cylindricalVertexDistance = fog_cylindrical_distance(pos);");
        } else {
            out.add(INDENT + "vertexDistance = fog_distance(pos, FogShape);");
        }
        out.add(INDENT + "texCoord0 = UV0;");
        out.add(INDENT + "gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);");
        out.add("}");

        return String.join("\n", out) + "\n";
    }

    /**
     * 26.2 and newer, where one source is compiled once per variant.
     *
     * The guards are reproduced exactly as vanilla writes them. The client builds this for world
     * text, GUI text and see-through text, and a variant that fails to compile takes the whole
     * shader down — including the GUI one the HUD lives in.
     */
    private static String variantVertex(ShaderDialect dialect, Encoding.StateTable states,
                                        boolean hideVanillaLevelText, int defaultOffset) {
        List<String> out = new ArrayList<>();
        out.add("#version " + dialect.glslVersion());
        out.add("");
        out.add(WORLD_ONLY);
        out.add("#moj_import <minecraft:fog.glsl>");
        out.add("#moj_import <minecraft:sample_lightmap.glsl>");
        out.add("#endif");
        out.add("");
        out.add("#moj_import <minecraft:dynamictransforms.glsl>");
        out.add("#moj_import <minecraft:projection.glsl>");
        out.add("");

        out.add("#define HEIGHT_BIT " + Encoding.HEIGHT_BIT);
        out.add("#define MAX_BIT " + Encoding.MAX_BIT);
        out.add("#define ADD_OFFSET " + Encoding.ADD_OFFSET);
        out.add("#define DEFAULT_OFFSET " + defaultOffset);
        out.add("");

        out.add("in vec3 Position;");
        out.add("in vec4 Color;");
        out.add("in vec2 UV0;");
        out.add(WORLD_ONLY);
        out.add("in ivec2 UV2;");
        out.add("#endif");
        out.add("");

        out.add(WORLD_ONLY);
        out.add("uniform sampler2D Sampler2;");
        out.add("out float sphericalVertexDistance;");
        out.add("out float cylindricalVertexDistance;");
        out.add("#endif");
        out.add("");

        out.add("out vec4 vertexColor;");
        out.add("out vec2 texCoord0;");
        out.add("");

        if (hideVanillaLevelText) {
            out.addAll(hideLevelHelper());
            out.add("");
        }

        out.add("void main() {");
        out.add(INDENT + "vec3 pos = Position;");
        out.add(INDENT + "vec2 ui = ceil(2.0 / vec2(ProjMat[0][0], -ProjMat[1][1]));");
        out.add("");
        out.add(WORLD_ONLY);
        out.add(INDENT + "vertexColor = Color * sample_lightmap(Sampler2, UV2);");
        out.add("#else");
        out.add(INDENT + "vertexColor = Color;");
        out.add("#endif");
        out.add("");

        out.addAll(decode(states, hideVanillaLevelText));
        out.add("");

        out.add(WORLD_ONLY);
        out.add(INDENT + "sphericalVertexDistance = fog_spherical_distance(pos);");
        out.add(INDENT + "cylindricalVertexDistance = fog_cylindrical_distance(pos);");
        out.add("#endif");
        out.add(INDENT + "texCoord0 = UV0;");
        out.add(INDENT + "gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);");
        out.add("}");

        return String.join("\n", out) + "\n";
    }

    /**
     * The block that undoes the encoding. Identical in every dialect, because it works on the vertex
     * position before projection and nothing that changed between versions reaches it.
     */
    private static List<String> decode(Encoding.StateTable states, boolean hideVanillaLevelText) {
        List<String> out = new ArrayList<>();
        String i1 = INDENT;
        String i2 = INDENT.repeat(2);
        String i3 = INDENT.repeat(3);
        String i4 = INDENT.repeat(4);

        out.add(i1 + "// HUD glyphs carry their position in a huge ascent, which parks them far");
        out.add(i1 + "// below the screen. Bring them back and apply the state they were tagged with.");
        out.add(i1 + "if (ProjMat[3].x == -1.0 && pos.y >= ui.y) {");
        out.add(i2 + "int bits = int(pos.y) >> HEIGHT_BIT;");
        out.add(i2 + "if (((bits >> MAX_BIT) & 1) == 1) {");
        out.add(i3 + "int id = bits - (1 << MAX_BIT);");
        out.add(i3 + "// the boss bar centres its title, so move the origin to the left edge");
        out.add(i3 + "pos.x -= 0.5 * ui.x;");
        out.add(i3 + "pos.y -= float((bits << HEIGHT_BIT) + ADD_OFFSET + DEFAULT_OFFSET);");
        out.add(i3 + "float xGui = 0.0;");
        out.add(i3 + "float yGui = 0.0;");
        out.add(i3 + "float layer = 0.0;");
        out.add(i3 + "switch (id) {");

        for (Map.Entry<Encoding.RenderState, Integer> entry : states.entries()) {
            Encoding.RenderState state = entry.getKey();
            out.add(i4 + "case " + entry.getValue() + ":");
            if (state.anchorX() != 0) {
                out.add(i4 + INDENT + String.format(Locale.ROOT,
                        "xGui = ui.x * %.1f / 100.0;", state.anchorX()));
            }
            if (state.anchorY() != 0) {
                out.add(i4 + INDENT + String.format(Locale.ROOT,
                        "yGui = ui.y * %.1f / 100.0;", state.anchorY()));
            }
            if (state.layer() != 0) {
                out.add(i4 + INDENT + "layer = " + state.layer() + ".0;");
            }
            out.add(i4 + INDENT + "break;");
        }

        out.add(i3 + "}");
        out.add(i3 + "pos.x += xGui;");
        out.add(i3 + "pos.y += yGui;");
        out.add(i3 + "pos.z += layer;");
        out.add(i2 + "}");

        if (hideVanillaLevelText) {
            out.add(i1 + "} else if (isVanillaLevelText(pos, ui, Color)) {");
            out.add(i2 + "vertexColor = vec4(0.0);");
            out.add(i1 + "}");
        } else {
            out.add(i1 + "}");
        }
        return out;
    }

    /**
     * Recognises the vanilla experience level number above the hotbar.
     *
     * There is no flag that turns it off, so this matches what it looks like: the level colour
     * (128, 255, 32) or its black outline, drawn inside a band across the middle of the screen.
     * Anything matching is made fully transparent by the caller.
     */
    private static List<String> hideLevelHelper() {
        List<String> out = new ArrayList<>();
        out.add("bool isVanillaLevelText(vec3 pos, vec2 ui, vec4 color) {");
        out.add(INDENT + "if (ProjMat[3].x != -1.0) {");
        out.add(INDENT.repeat(2) + "return false;");
        out.add(INDENT + "}");
        out.add(INDENT + "bool inBand = pos.y >= ui.y - 60.0 && pos.y <= ui.y - 20.0");
        out.add(INDENT.repeat(3) + "&& pos.x >= ui.x * 0.5 - 60.0 && pos.x <= ui.x * 0.5 + 60.0;");
        out.add(INDENT + "if (!inBand) {");
        out.add(INDENT.repeat(2) + "return false;");
        out.add(INDENT + "}");
        out.add(INDENT + "vec3 levelColor = vec3(128.0, 255.0, 32.0);");
        out.add(INDENT + "return (all(greaterThanEqual(color.rgb, levelColor / 256.0))");
        out.add(INDENT.repeat(3) + "&& all(lessThanEqual(color.rgb, levelColor / 254.0)))");
        out.add(INDENT.repeat(2) + "|| color.rgb == vec3(0.0);");
        out.add("}");
        return out;
    }

    public static String fragment(ShaderDialect dialect) {
        List<String> out = new ArrayList<>();
        out.add("#version " + dialect.glslVersion());
        out.add("");

        out.add("#moj_import <minecraft:fog.glsl>");
        if (dialect.uniformBlocks()) {
            out.add("#moj_import <minecraft:dynamictransforms.glsl>");
        }
        out.add("");

        out.add("uniform sampler2D Sampler0;");
        if (!dialect.uniformBlocks()) {
            out.add("uniform vec4 ColorModulator;");
            out.add("uniform float FogStart;");
            out.add("uniform float FogEnd;");
            out.add("uniform vec4 FogColor;");
        }
        out.add("");

        if (dialect.uniformBlocks()) {
            out.add("in float sphericalVertexDistance;");
            out.add("in float cylindricalVertexDistance;");
        } else {
            out.add("in float vertexDistance;");
        }
        out.add("in vec4 vertexColor;");
        out.add("in vec2 texCoord0;");
        out.add("");

        out.add("out vec4 fragColor;");
        out.add("");

        out.add("void main() {");
        out.add(INDENT + "vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;");
        out.add(INDENT + "if (color.a < 0.1) {");
        out.add(INDENT.repeat(2) + "discard;");
        out.add(INDENT + "}");
        if (dialect.uniformBlocks()) {
            out.add(INDENT + "fragColor = apply_fog(color, sphericalVertexDistance,");
            out.add(INDENT.repeat(3) + "cylindricalVertexDistance,");
            out.add(INDENT.repeat(3) + "FogEnvironmentalStart, FogEnvironmentalEnd,");
            out.add(INDENT.repeat(3) + "FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);");
        } else {
            out.add(INDENT + "fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);");
        }
        out.add("}");

        return String.join("\n", out) + "\n";
    }
}
