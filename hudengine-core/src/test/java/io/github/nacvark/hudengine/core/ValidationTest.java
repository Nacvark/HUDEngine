package io.github.nacvark.hudengine.core;

import io.github.nacvark.hudengine.core.model.Model;
import io.github.nacvark.hudengine.core.model.ModelValidator;
import io.github.nacvark.hudengine.core.util.EngineLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationTest {

    @Test
    void acceptsTheFixture(@TempDir Path work) throws IOException {
        List<ModelValidator.Problem> problems = validate(copyFixture(work));

        assertFalse(ModelValidator.hasErrors(problems),
                "the shipped fixture must validate cleanly: " + ModelValidator.format(problems));
    }

    @Test
    void reportsAMissingImageAndSuggestsTheRealName(@TempDir Path work) throws IOException {
        Path config = copyFixture(work);
        replace(config.resolve("layouts/main.yml"), "name: bar", "name: barr");

        String reported = report(validate(config));

        assertTrue(reported.contains("layouts/main -> images -> 1"), reported);
        assertTrue(reported.contains("\"barr\""), reported);
        assertTrue(reported.contains("Did you mean \"bar\"?"), reported);
    }

    @Test
    void reportsAMissingAssetFile(@TempDir Path work) throws IOException {
        Path config = copyFixture(work);
        Files.delete(config.resolve("assets/bar.png"));

        String reported = report(validate(config));

        assertTrue(reported.contains("images/bar"), reported);
        assertTrue(reported.contains("assets/bar.png"), reported);
    }

    @Test
    void reportsEveryProblemAtOnce(@TempDir Path work) throws IOException {
        Path config = copyFixture(work);
        replace(config.resolve("layouts/main.yml"), "name: bar", "name: nope");
        replace(config.resolve("layouts/side.yml"), "name: face", "name: alsonope");
        replace(config.resolve("huds/side.yml"), "name: side", "name: missing");

        List<ModelValidator.Problem> problems = validate(config);
        long errors = problems.stream()
                .filter(p -> p.severity() == ModelValidator.Severity.ERROR)
                .count();

        // Stopping at the first bad reference would turn fixing a config into one restart per typo.
        assertTrue(errors >= 3, "expected all three broken references, got " + report(problems));
    }

    @Test
    void warnsAboutAnAnchorOffTheScreen(@TempDir Path work) throws IOException {
        Path config = copyFixture(work);
        replace(config.resolve("huds/main.yml"), "x: 2", "x: 240");

        List<ModelValidator.Problem> problems = validate(config);

        assertFalse(ModelValidator.hasErrors(problems), "an odd anchor is suspicious, not fatal");
        assertTrue(report(problems).contains("percentage of the screen"), report(problems));
    }

    @Test
    void rejectsAFontWithNowhereToTakeGlyphsFrom(@TempDir Path work) throws IOException {
        Path config = copyFixture(work);
        replace(config.resolve("texts/default.yml"),
                "default:\n  scale: 8\n  merge-default-bitmap: true",
                "default:\n  scale: 8\n  merge-default-bitmap: false");

        String reported = report(validate(config));

        assertTrue(reported.contains("texts/default"), reported);
        assertTrue(reported.contains("nowhere to take glyphs from"), reported);
    }

    /* ---------------- helpers ---------------- */

    private static List<ModelValidator.Problem> validate(Path config) throws IOException {
        return ModelValidator.validate(Model.load(config, EngineLogger.silent()));
    }

    private static String report(List<ModelValidator.Problem> problems) {
        return String.join("\n", ModelValidator.format(problems));
    }

    /** The fixture is read-only on the classpath, so tests that break it work on a copy. */
    private static Path copyFixture(Path target) throws IOException {
        Path source = CompilerTest.fixture();
        try (Stream<Path> files = Files.walk(source)) {
            for (Path path : files.sorted(Comparator.naturalOrder()).toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }

    private static void replace(Path file, String from, String to) throws IOException {
        String text = Files.readString(file);
        if (!text.contains(from)) {
            throw new IllegalStateException("fixture no longer contains \"" + from + "\": " + file);
        }
        Files.writeString(file, text.replace(from, to));
    }

    @Test
    void reportsAFontFileNothingPointsAt(@TempDir Path work) throws IOException {
        Path config = copyFixture(work);
        Files.createDirectories(config.resolve("fonts"));
        Files.write(config.resolve("fonts/unused.ttf"), new byte[]{0});

        List<ModelValidator.Problem> problems = validate(config);
        String reported = report(problems);

        assertTrue(reported.contains("fonts/unused.ttf"), reported);
        assertTrue(reported.contains("file: unused.ttf"), reported);
        // A font nobody uses is untidy, not broken, so it must not stop the compilation.
        assertFalse(ModelValidator.hasErrors(problems), reported);
    }

    @Test
    void saysNothingAboutAFontFileThatIsUsed(@TempDir Path work) throws IOException {
        Path config = copyFixture(work);
        Files.createDirectories(config.resolve("fonts"));
        Files.write(config.resolve("fonts/used.ttf"), new byte[]{0});
        replace(config.resolve("texts/default.yml"),
                "default:\n  scale: 8",
                "default:\n  file: used.ttf\n  scale: 8");

        assertFalse(report(validate(config)).contains("used.ttf"), report(validate(config)));
    }
}
