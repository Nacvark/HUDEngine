package io.github.nacvark.hudengine.cli;

import io.github.nacvark.hudengine.core.compile.HudPackCompiler;
import io.github.nacvark.hudengine.core.model.ConfigurationException;
import io.github.nacvark.hudengine.core.util.EngineLogger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds a resource pack from a config folder without a running server.
 *
 *   java -jar hudengine-cli.jar &lt;config-folder&gt; [output-folder]
 *
 * Writes {@code <output>/pack/} as a browsable tree, {@code <output>/HUDEngine.zip} for
 * distribution, and {@code <output>/manifest.json} describing what was compiled.
 */
public final class Main {

    private Main() {
    }

    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAILED = 1;

    public static void main(String[] args) {
        if (args.length < 1 || args[0].equals("-h") || args[0].equals("--help")) {
            System.err.println("usage: hudengine-cli <config-folder> [output-folder]");
            System.exit(EXIT_USAGE);
            return;
        }

        Path config = Path.of(args[0]);
        Path output = Path.of(args.length > 1 ? args[1] : "out");

        if (!Files.isDirectory(config)) {
            System.err.println("not a directory: " + config.toAbsolutePath());
            System.exit(EXIT_USAGE);
            return;
        }

        EngineLogger log = EngineLogger.console();
        long start = System.nanoTime();
        try {
            HudPackCompiler.Result result = HudPackCompiler.compile(new HudPackCompiler.Request(
                    config,
                    output.resolve("pack"),
                    output.resolve("HUDEngine.zip"),
                    output.resolve("manifest.json"),
                    HudPackCompiler.Options.defaults(),
                    null,
                    log));

            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("compiled in " + ms + " ms into " + output.toAbsolutePath());
            result.report().forEach(line -> log.info("  " + line));
        } catch (ConfigurationException e) {
            // A typo in a config is not a crash, so it does not get a stack trace. The problems
            // were already listed as they were found; all that is left is the count.
            log.error(e.getMessage() + "; fix the errors listed above and run again", null);
            System.exit(EXIT_FAILED);
        } catch (Exception e) {
            log.error("compilation failed: " + e.getMessage(), e);
            System.exit(EXIT_FAILED);
        }
    }
}
