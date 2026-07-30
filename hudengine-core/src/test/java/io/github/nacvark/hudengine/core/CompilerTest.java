package io.github.nacvark.hudengine.core;

import io.github.nacvark.hudengine.core.compile.HudPackCompiler;
import io.github.nacvark.hudengine.core.model.Compiled;
import io.github.nacvark.hudengine.core.util.EngineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerTest {

    @Test
    void compilesFixtureIntoAPack(@TempDir Path out) throws IOException {
        HudPackCompiler.Result result = compile(out);

        assertEquals(2, result.pack().huds().size(), "the fixture declares two huds");
        assertNotNull(result.pack().huds().get("main"));
        assertNotNull(result.pack().huds().get("side"));
        assertTrue(Files.isRegularFile(out.resolve("pack/pack.mcmeta")));
        assertTrue(Files.isRegularFile(out.resolve("pack/assets/minecraft/shaders/core/rendertype_text.vsh")));
        assertTrue(Files.isRegularFile(out.resolve("HUDEngine.zip")));
    }

    @Test
    void usesTheBundledVanillaGlyphsWhenNoFolderIsPresent(@TempDir Path out) throws IOException {
        HudPackCompiler.Result result = compile(out);

        // Every character of the fixture's patterns is plain ASCII, which the bundled sheets cover.
        // If the bundled data failed to load, the compiler would fall through to a TTF that the
        // fixture does not ship and the compilation would have failed outright.
        Compiled.Text text = firstText(result.pack());
        assertFalse(text.glyphs().isEmpty(), "text element compiled no glyphs");
        assertTrue(text.glyphs().containsKey((int) 'H'), "expected a glyph for 'H'");
    }

    @Test
    void producesAByteIdenticalPackForIdenticalInput(@TempDir Path first, @TempDir Path second)
            throws IOException {
        compile(first);
        compile(second);

        // A stable archive means a stable SHA-1, which is what stops clients re-downloading the
        // pack after every server restart.
        assertEquals(sha1(first.resolve("HUDEngine.zip")), sha1(second.resolve("HUDEngine.zip")));
    }

    @Test
    void sortsElementsByLayer(@TempDir Path out) throws IOException {
        Compiled.Hud hud = compile(out).pack().huds().get("main");

        // The fixture puts the bar before the texts in config order but on the same layer, so a
        // stable sort must preserve that order rather than shuffling it.
        assertEquals(3, hud.elements().size());
        assertTrue(hud.elements().getFirst() instanceof Compiled.Bar);
    }

    @Test
    void includingALanguageAddsItsGlyphs(@TempDir Path out) throws IOException {
        Compiled.Hud side = compile(out).pack().huds().get("side");

        Compiled.Text plain = textContaining(side, "Level");
        Compiled.Text cyrillic = textContaining(side, "Уровень");

        // Only the font that declared include: russia pays for Cyrillic. Rasterising every script
        // into every font is what makes a resource pack balloon.
        assertFalse(plain.glyphs().containsKey(0x0410), "a font without include: russia must stay Latin");
        assertTrue(cyrillic.glyphs().containsKey(0x0410), "include: russia must add Cyrillic glyphs");
    }

    @Test
    void aPatternWrittenInAnotherScriptNeedsNoInclude(@TempDir Path work) throws IOException {
        // The characters are right there in the config, so having to also name the language is a
        // step nobody discovers: the Latin half of the line renders and the rest silently does not.
        Path config = work.resolve("config");
        copyDirectory(fixture(), config);
        Files.writeString(config.resolve("layouts/main.yml"),
                Files.readString(config.resolve("layouts/main.yml"))
                        .replace("pattern: \"HUDEngine\"", "pattern: \"Привет\""));

        Compiled.Pack pack = HudPackCompiler.compile(new HudPackCompiler.Request(
                config, null, null, null, HudPackCompiler.Options.defaults(),
                null, EngineLogger.silent())).pack();

        Compiled.Text text = textContaining(pack.huds().get("main"), "Привет");
        assertTrue(text.glyphs().containsKey((int) 'П'),
                "a literal Cyrillic pattern must compile without include: russia");
        assertTrue(text.glyphs().containsKey((int) 'т'), "every character of it, not just the first");
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var files = Files.walk(source)) {
            for (Path path : files.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private static Compiled.Text textContaining(Compiled.Hud hud, String literal) {
        for (Compiled.Element element : hud.elements()) {
            if (element instanceof Compiled.Text text
                    && text.segments().stream().anyMatch(s -> !s.placeholder() && s.value().contains(literal))) {
                return text;
            }
        }
        throw new AssertionError("no text element with literal \"" + literal + "\"");
    }

    static HudPackCompiler.Result compile(Path out) throws IOException {
        return HudPackCompiler.compile(new HudPackCompiler.Request(
                fixture(),
                out.resolve("pack"),
                out.resolve("HUDEngine.zip"),
                out.resolve("manifest.json"),
                HudPackCompiler.Options.defaults(),
                null,
                EngineLogger.silent()));
    }

    static Path fixture() {
        try {
            return Path.of(CompilerTest.class.getResource("/fixture-hud").toURI());
        } catch (Exception e) {
            throw new IllegalStateException("test fixture is missing from the classpath", e);
        }
    }

    static Compiled.Text firstText(Compiled.Pack pack) {
        for (Compiled.Element element : pack.huds().get("main").elements()) {
            if (element instanceof Compiled.Text text) {
                return text;
            }
        }
        throw new AssertionError("the fixture should contain a text element");
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by every JVM", e);
        }
    }
}
