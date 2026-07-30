package io.github.nacvark.hudengine.paper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the two locales and the code that reads them in step.
 *
 * Three ways this drifts, none of which shows up at compile time: a key added to one language and not
 * the other, a key read from code that no file defines, and a template whose {@code {placeholder}}
 * names do not match the arguments passed at the call site. All three degrade quietly — a raw key or
 * an unfilled brace in the console — so they are checked here instead.
 */
class MessagesTest {

    private static final List<String> LANGUAGES = List.of("en", "ru");

    private static final Pattern KEY = Pattern.compile("^ {2}([a-z0-9-]+):");
    private static final Pattern SECTION = Pattern.compile("^([a-z0-9-]+):");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)}");

    /** Message file text, straight off the classpath of the plugin module. */
    private static String bundled(String language) throws IOException {
        Path local = Path.of("..", "hudengine-plugin", "src", "main", "resources",
                "messages", language + ".yml");
        if (Files.isRegularFile(local)) {
            return Files.readString(local, StandardCharsets.UTF_8);
        }
        try (InputStream in = MessagesTest.class.getResourceAsStream("/messages/" + language + ".yml")) {
            if (in == null) {
                throw new IOException("no bundled messages for " + language);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Fully qualified key to template, for one language. */
    private static Map<String, String> entries(String language) throws IOException {
        Map<String, String> out = new TreeMap<>();
        String section = "";
        for (String line : bundled(language).split("\n")) {
            Matcher top = SECTION.matcher(line);
            if (top.find()) {
                section = top.group(1);
                continue;
            }
            Matcher key = KEY.matcher(line);
            if (key.find()) {
                int colon = line.indexOf(':');
                out.put(section + "." + key.group(1), line.substring(colon + 1).strip());
            }
        }
        return out;
    }

    @Test
    void everyLanguageDefinesTheSameKeys() throws IOException {
        Map<String, String> english = entries("en");
        for (String language : LANGUAGES) {
            if (language.equals("en")) {
                continue;
            }
            assertEquals(english.keySet(), entries(language).keySet(),
                    language + ".yml does not define the same keys as en.yml");
        }
    }

    @Test
    void everyTemplateUsesTheSamePlaceholdersInEveryLanguage() throws IOException {
        Map<String, String> english = entries("en");
        for (String language : LANGUAGES) {
            if (language.equals("en")) {
                continue;
            }
            Map<String, String> other = entries(language);
            for (Map.Entry<String, String> entry : english.entrySet()) {
                assertEquals(placeholders(entry.getValue()), placeholders(other.get(entry.getKey())),
                        entry.getKey() + " uses different placeholders in " + language + ".yml");
            }
        }
    }

    @Test
    void everyKeyReadFromCodeIsDefined() throws IOException {
        Set<String> defined = entries("en").keySet();
        Set<String> missing = new TreeSet<>();

        Pattern call = Pattern.compile("(?:plain|prefixed)\\(\"([a-z0-9.-]+)\"");
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = call.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    if (!defined.contains(m.group(1))) {
                        missing.add(m.group(1) + " (" + file.getFileName() + ")");
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "keys read from code but not defined in en.yml: " + missing);
    }

    private static Set<String> placeholders(String template) {
        Set<String> out = new LinkedHashSet<>();
        if (template == null) {
            return out;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
