package dev.turboism.gradle.internal;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.gradle.api.GradleException;
import org.gradle.api.tasks.testing.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Post-execution guard for every {@code Test} task.
 *
 * <p>Gradle reports a passing task when every selected test is skipped, so a filter
 * that matches nothing, or an environment that silently disables a whole suite, looks
 * identical to real coverage. This guard reads the task's own JUnit XML results and
 * fails the build when:
 *
 * <ul>
 *   <li>no test was discovered at all, or
 *   <li>nothing executed while some tests were skipped, unless every skipped test is
 *       registered in the explicit skip allowlist.
 * </ul>
 *
 * <p>Unregistered skips always fail: the allowlist is the only sanctioned way to skip
 * a test, and it must carry a reason. A registered skip set that leaves nothing
 * executed is reported as a warning rather than silently counted as coverage.
 */
public final class TestExecutionGuard {

    private TestExecutionGuard() {
    }

    /** Attaches the guard to {@code task}, reading allowed skips from {@code allowlist}. */
    public static void apply(Test task, File allowlist) {
        task.getInputs().file(allowlist).withPropertyName("testSkipAllowlist").optional(true);
        task.doLast(ignored -> verify(task, allowlist.toPath()));
    }

    private static void verify(Test task, Path allowlistPath) {
        Path resultsDir = task.getReports().getJunitXml().getOutputLocation().getAsFile().get().toPath();
        if (!Files.isDirectory(resultsDir)) {
            throw new GradleException("Test task " + task.getPath() + " produced no result directory at "
                    + resultsDir + "; the execution-count guard cannot verify what ran.");
        }
        List<Result> results = readResults(resultsDir);
        if (results.isEmpty()) {
            throw new GradleException("Test task " + task.getPath()
                    + " discovered no tests. A task that selects nothing must fail the build, not report success.");
        }
        long executed = results.stream().filter(result -> !result.skipped()).count();
        List<String> skippedIds = results.stream().filter(Result::skipped).map(Result::id)
                .toList();
        SkipAllowlist allowlist = SkipAllowlist.load(allowlistPath);
        List<String> unregistered = skippedIds.stream()
                .filter(id -> !allowlist.matches(id))
                .toList();
        if (!unregistered.isEmpty()) {
            throw new GradleException("Test task " + task.getPath() + " skipped " + unregistered.size()
                    + " test(s) that are not registered in " + allowlistPath + ":\n  "
                    + String.join("\n  ", unregistered)
                    + "\nRegister each skip with a reason, or make the test fail instead of skipping.");
        }
        if (executed == 0) {
            if (skippedIds.isEmpty()) {
                throw new GradleException("Test task " + task.getPath() + " executed no tests.");
            }
            task.getLogger()
                    .warn(
                            "Test task {} executed 0 tests; all {} skipped test(s) are registered as allowed.",
                            task.getPath(), skippedIds.size());
        }
        task.getLogger()
                .info(
                        "Test execution guard: {} executed, {} skipped, {} total for {}",
                        executed, skippedIds.size(), results.size(), task.getPath());
    }

    private static List<Result> readResults(Path resultsDir) {
        List<Result> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(resultsDir)) {
            List<Path> xmlFiles = files.filter(file -> file.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .collect(Collectors.toList());
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            for (Path xmlFile : xmlFiles) {
                Document document;
                try {
                    document = builder.parse(xmlFile.toFile());
                } catch (SAXException failure) {
                    throw new GradleException("Cannot parse JUnit XML " + xmlFile + ": " + failure.getMessage(), failure);
                }
                NodeList cases = document.getElementsByTagName("testcase");
                for (int index = 0; index < cases.getLength(); index++) {
                    Element testCase = (Element) cases.item(index);
                    boolean skipped = testCase.getElementsByTagName("skipped").getLength() > 0;
                    results.add(new Result(testCase.getAttribute("classname"), testCase.getAttribute("name"), skipped));
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (ParserConfigurationException failure) {
            throw new GradleException("Cannot configure JUnit XML parsing: " + failure.getMessage(), failure);
        }
        return results;
    }

    private record Result(String className, String name, boolean skipped) {
        String id() {
            return className + "." + name;
        }
    }

    /** Explicit, reviewed set of tests that may skip, each with a required reason. */
    private static final class SkipAllowlist {

        private final List<Pattern> patterns;

        private SkipAllowlist(List<Pattern> patterns) {
            this.patterns = patterns;
        }

        static SkipAllowlist load(Path path) {
            if (!Files.isRegularFile(path)) {
                return new SkipAllowlist(List.of());
            }
            List<Pattern> patterns = new ArrayList<>();
            try {
                for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int comment = line.indexOf(" #");
                    String pattern = (comment >= 0 ? line.substring(0, comment) : line).trim();
                    if (pattern.isEmpty()) {
                        continue;
                    }
                    if (comment < 0) {
                        throw new GradleException("Skip allowlist entry without a reason in " + path + ": " + line);
                    }
                    patterns.add(glob(pattern));
                }
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            return new SkipAllowlist(patterns);
        }

        boolean matches(String testId) {
            return patterns.stream().anyMatch(pattern -> pattern.matcher(testId).matches());
        }

        private static Pattern glob(String glob) {
            StringBuilder regex = new StringBuilder();
            for (char character : glob.toCharArray()) {
                switch (character) {
                    case '*' -> regex.append(".*");
                    case '?' -> regex.append('.');
                    default -> regex.append(Pattern.quote(String.valueOf(character)));
                }
            }
            return Pattern.compile(regex.toString());
        }
    }
}
