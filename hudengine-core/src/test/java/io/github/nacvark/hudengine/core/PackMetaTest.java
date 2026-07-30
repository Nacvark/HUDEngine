package io.github.nacvark.hudengine.core;

import io.github.nacvark.hudengine.core.compile.HudPackCompiler;
import io.github.nacvark.hudengine.core.compile.ShaderDialect;
import io.github.nacvark.hudengine.core.util.EngineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the pack declares which versions it supports.
 *
 * Getting this wrong does not produce an error anywhere the server can see. The client simply
 * refuses the pack, or ignores its overlays, and the HUD is missing on some versions and fine on
 * others.
 *
 * The rule, from Mojang: 1.21.9 replaced {@code supported_formats} with {@code min_format} and
 * {@code max_format}. A pack whose minimum is below format 65 must still carry the old field for the
 * clients that only understand it; a pack whose minimum is 65 or above must not, and is rejected
 * outright if it does.
 */
class PackMetaTest {

    private static final int OLD_FIELD_CUTOFF = 65;

    private String mcmeta(Path out, int minFormat, int maxFormat) throws IOException {
        HudPackCompiler.Options defaults = HudPackCompiler.Options.defaults();
        HudPackCompiler.compile(new HudPackCompiler.Request(
                CompilerTest.fixture(), out.resolve("pack"), null, null,
                new HudPackCompiler.Options(defaults.namespace(), defaults.bossBarColor(),
                        defaults.packDescription(), minFormat, maxFormat,
                        defaults.extraChars(), Set.of(),
                        defaults.bossBarLine()),
                null, EngineLogger.silent()));
        return Files.readString(out.resolve("pack/pack.mcmeta"));
    }

    @Test
    void aPackReachingBelowTheCutoffCarriesBothSpellings(@TempDir Path out) throws IOException {
        String meta = mcmeta(out, ShaderDialect.MIN_PACK_FORMAT, ShaderDialect.MAX_PACK_FORMAT);

        // Old clients read only the first, new ones only the second, and this pack serves both.
        assertTrue(meta.contains("\"supported_formats\""), meta);
        assertTrue(meta.contains("\"min_format\""), meta);
        assertTrue(meta.contains("\"max_format\""), meta);
        assertTrue(meta.contains("\"pack_format\""), meta);
    }

    @Test
    void aPackStartingAboveTheCutoffDropsTheOldSpelling(@TempDir Path out) throws IOException {
        String meta = mcmeta(out, OLD_FIELD_CUTOFF, ShaderDialect.MAX_PACK_FORMAT);

        // Not merely unnecessary: a pack declaring it above the cutoff is refused.
        assertFalse(meta.contains("\"supported_formats\""), meta);
        assertFalse(meta.contains("\"formats\""), "overlays must drop it too: " + meta);
        assertTrue(meta.contains("\"min_format\""), meta);
    }

    @Test
    void everyOverlayDeclaresItsRangeBothWays(@TempDir Path out) throws IOException {
        String meta = mcmeta(out, ShaderDialect.MIN_PACK_FORMAT, ShaderDialect.MAX_PACK_FORMAT);

        for (ShaderDialect dialect : ShaderDialect.values()) {
            if (dialect == ShaderDialect.forPackFormat(ShaderDialect.MIN_PACK_FORMAT)) {
                continue; // this one lives at the pack root, not in an overlay
            }
            String directory = "overlay_" + dialect.id();
            assertTrue(meta.contains(directory), "missing overlay for " + dialect + ": " + meta);
            assertTrue(meta.contains("\"min_format\":" + dialect.minFormat()),
                    dialect + " overlay has no min_format: " + meta);
        }
        // An overlay that only declares the deprecated field is silently skipped by new clients,
        // which is exactly the failure that made the HUD vanish on 1.21.11 and 26.x.
        assertTrue(meta.contains("\"formats\""), meta);
    }
}
