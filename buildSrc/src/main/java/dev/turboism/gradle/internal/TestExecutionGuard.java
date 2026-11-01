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
 *   <li>a test skipped without an entry in the skip allowlist, or
 *   <li>nothing executed while tests were skipped, unless the task path itself is
 *       registered in the zero-execution allowlist with a reason.
 * </ul>
 *
 * <p>Unregistered skips always fail: the allowlist is the only sanctioned way to skip
 * a test, and it must carry a reason. A registered skip set that leaves nothing
 * executed also fails unless the task is registered, so a suite can never turn
 * "everything skipped" into a green build by accident.
 */
public final class TestExecutionGuard {

    private TestExecutionGuard() {
    }

    /** Attaches the guard to {@code task}, reading both allowlists from the root project. */
    public static void apply(Test task, File skipAllowlist, File zeroExecutedAllowlist) {
        task.getInputs().file(skipAllowlist).withPropertyName("testSkipAllowlist").optional(true);
        task.getInputs()
                .file(zeroExecutedAllowlist)
                .withPropertyName("testZeroExecutedAllowlist")
                .optional(true);
        task.doLast(ignored -> {
            Summary summary = inspect(
                    task.getPath(),
                    task.getReports().getJunitXml().getOutputLocation().getAsFile().get().toPath(),
                    skipAllowlist.toPath(),
                    zeroExecutedAllowlist.toPath());
            if (summary.sanctionedZeroExecution()) {
                task.getLogger()
                        .warn(
                                "Test task {} executed 0 tests; the task is registered as a sanctioned zero-execution suite.",
                                task.getPath());
            }
            task.getLogger()
                    .info(
                            "Test execution guard: {} executed, {} skipped for {}",
                            summary.executed(),
                            summary.skipped(),
                            task.getPath());
        });
    }

    /** What the guard observed for one task; also the seam used by the unit tests. */
    public record Summary(int executed, int skipped, boolean sanctionedZeroExecution) {
    }

    /**
     * Verifies one task's JUnit XML results.
     *
     * @throws GradleException when the task discovered no test, skipped an unregistered
     *     test, or executed nothing without a registered zero-execution exemption
     */
    public static Summary inspect(
            String taskPath, Path resultsDir, Path skipAllowlistPath, Path zeroExecutedAllowlistPath) {
        if (!Files.isDirectory(resultsDir)) {
            throw new GradleException("Test task " + taskPath + " produced no result directory at " + resultsDir
                    + "; the execution-count guard cannot verify what ran.");
        }
        List<Result> results = readResults(resultsDir);
        if (results.isEmpty()) {
            throw new GradleException("Test task " + taskPath
                    + " discovered no tests. A task that selects nothing must fail the build, not report success.");
        }
        long executed = results.stream().filter(result -> !result.skipped()).count();
        List<String> skippedIds = results.stream()
                .filter(Result::skipped)
                .map(Result::id)
                .collect(Collectors.toList());
        TestAllowlist skipAllowlist = TestAllowlist.load(skipAllowlistPath);
        List<String> unregistered = skippedIds.stream()
                .filter(id -> !skipAllowlist.matches(id))
                .collect(Collectors.toList());
        if (!unregistered.isEmpty()) {
            throw new GradleException("Test task " + taskPath + " skipped " + unregistered.size()
                    + " test(s) that are not registered in " + skipAllowlistPath + ":\n  "
                    + String.join("\n  ", unregistered)
                    + "\nRegister each skip with a reason, or make the test fail instead of skipping.");
        }
        boolean sanctionedZeroExecution = false;
        if (executed == 0) {
            if (skippedIds.isEmpty()) {
                throw new GradleException("Test task " + taskPath + " executed no tests.");
            }
            TestAllowlist zeroExecutedAllowlist = TestAllowlist.load(zeroExecutedAllowlistPath);
            if (!zeroExecutedAllowlist.matches(taskPath)) {
                throw new GradleException("Test task " + taskPath + " executed 0 tests; all "
                        + skippedIds.size() + " skipped test(s) are registered as allowed. Register the task in "
                        + zeroExecutedAllowlistPath + " with a reason when a suite is legitimately empty,"
                        + " or make at least one test run.");
            }
            sanctionedZeroExecution = true;
        }
        return new Summary((int) executed, skippedIds.size(), sanctionedZeroExecution);
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
                    throw new GradleException(
                            "Cannot parse JUnit XML " + xmlFile + ": " + failure.getMessage(), failure);
                }
                NodeList cases = document.getElementsByTagName("testcase");
                for (int index = 0; index < cases.getLength(); index++) {
                    Element testCase = (Element) cases.item(index);
                    boolean skipped = testCase.getElementsByTagName("skipped").getLength() > 0;
                    results.add(new Result(
                            testCase.getAttribute("classname"), testCase.getAttribute("name"), skipped));
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

    /** Explicit, reviewed globs; every entry must carry a trailing reason. */
    private static final class TestAllowlist {

        private final List<Pattern> patterns;

        private TestAllowlist(List<Pattern> patterns) {
            this.patterns = patterns;
        }

        static TestAllowlist load(Path path) {
            if (!Files.isRegularFile(path)) {
                return new TestAllowlist(List.of());
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
                        throw new GradleException("Allowlist entry without a reason in " + path + ": " + line);
                    }
                    patterns.add(glob(pattern));
                }
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            return new TestAllowlist(patterns);
        }

        boolean matches(String id) {
            return patterns.stream().anyMatch(pattern -> pattern.matcher(id).matches());
        }

        private static Pattern glob(String glob) {
            StringBuilder regex = new StringBuilder();
            for (char character : glob.toCharArray()) {
                switch (character) {
                    case '*' -> regex.append(".*");
                    case '?' -> regex.append('.');
                    // A dot separates package, class and the `$`-joined nested class name:
                    // `Outer.*` must match `Outer$Inner.method` as well as `Outer.method`.
                    case '.' -> regex.append("[.$]");
                    default -> regex.append(Pattern.quote(String.valueOf(character)));
                }
            }
            return Pattern.compile(regex.toString());
        }
    }
}
