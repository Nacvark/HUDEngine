package io.github.nacvark.hudengine.core;

import io.github.nacvark.hudengine.core.compile.AssetExport;
import io.github.nacvark.hudengine.core.compile.HudPackCompiler;
import io.github.nacvark.hudengine.core.compile.ShaderDialect;
import io.github.nacvark.hudengine.core.util.EngineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The copy written for plugins that merge other plugins' assets into a pack of their own.
 *
 * The folder belongs to another plugin, so most of these tests check what the export leaves alone.
 */
class AssetExportTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void removesOnlyWhatAnEarlierExportWrote(@TempDir Path folder) throws IOException {
        Files.createDirectories(folder.resolve("assets/other"));
        Files.writeString(folder.resolve("assets/other/theirs.png"), "not ours");
        Files.writeString(folder.resolve("unrelated.txt"), "not ours either");

        AssetExport.write(folder, Map.of(
                "assets/hudengine/keep.png", bytes("a"),
                "assets/hudengine/old/gone.png", bytes("b")));
        AssetExport.Result second = AssetExport.write(folder, Map.of(
                "assets/hudengine/keep.png", bytes("a2")));

        assertEquals(1, second.removed());
        assertFalse(Files.exists(folder.resolve("assets/hudengine/old/gone.png")));
        assertFalse(Files.exists(folder.resolve("assets/hudengine/old")), "an emptied directory is pruned");
        assertEquals("a2", Files.readString(folder.resolve("assets/hudengine/keep.png")));
        assertTrue(Files.exists(folder.resolve("assets/other/theirs.png")), "someone else's file was removed");
        assertTrue(Files.exists(folder.resolve("unrelated.txt")), "someone else's file was removed");
    }

    @Test
    void ignoresAManifestLinePointingOutsideTheFolder(@TempDir Path root) throws IOException {
        Path folder = root.resolve("export");
        Path victim = root.resolve("server.properties");
        Files.writeString(victim, "precious");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(".hudengine-export"), "../server.properties\n");

        AssetExport.write(folder, Map.of("assets/hudengine/a.png", bytes("a")));

        assertTrue(Files.exists(victim), "a hand-edited manifest reached outside the export folder");
    }

    @Test
    void theCompilerExportCarriesOneShaderForTheTargetVersionAndNoOverlays(@TempDir Path work)
            throws IOException {
        Path folder = work.resolve("export");
        HudPackCompiler.compile(new HudPackCompiler.Request(
                        CompilerTest.fixture(), null, null, null, HudPackCompiler.Options.defaults(),
                        null, EngineLogger.silent()),
                new HudPackCompiler.Export(folder, ShaderDialect.forMinecraftVersion("26.3")));

        List<String> files;
        try (Stream<Path> walk = Files.walk(folder)) {
            files = walk.filter(Files::isRegularFile)
                    .map(path -> folder.relativize(path).toString().replace(java.io.File.separatorChar, '/'))
                    .toList();
        }
        List<String> shaders = files.stream().filter(path -> path.endsWith(".vsh")).toList();

        // The merging plugin keeps assets/ and drops the rest, so the one shader there has to be the
        // right one; the 1.21.4 shader the full pack keeps at its root would not compile on 26.3.
        assertEquals(List.of("assets/minecraft/shaders/core/text.vsh"), shaders);
        assertTrue(Files.readString(folder.resolve(shaders.get(0)))
                .contains("GL_ARB_separate_shader_objects"), "the export carries the wrong dialect");
        assertTrue(files.stream().noneMatch(path -> path.startsWith("overlay_")), "overlays were exported");
        assertFalse(files.contains("pack.mcmeta"), "the merging plugin writes its own pack.mcmeta");
        assertTrue(files.stream().anyMatch(path -> path.startsWith("assets/hudengine/font/")),
                "the HUD fonts are missing from the export");
    }
}
