package dev.turboism.gradle.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rules for the execution-count guard, exercised against fixture JUnit XML.
 *
 * <p>The negative case is the one the review asked for: a suite whose entire
 * selection is skipped must fail even when every skipped test is registered, unless
 * the task itself is registered as a sanctioned zero-execution suite with a reason.
 */
class TestExecutionGuardTest {

    private static final String SKIP_ALLOWLIST = "dev.turboism.example.SkippedTest.* # requires a licensed artifact\n";

    @TempDir
    Path root;

    private Path results;

    @BeforeEach
    void setUp() throws IOException {
        results = Files.createDirectories(root.resolve("results"));
    }

    @Test
    void allSkippedSuiteFailsWhenTheTaskIsNotRegistered() throws IOException {
        writeResults("dev.turboism.example.SkippedTest", 2, 2);
        Path skipAllowlist = writeFile("skip-allowlist.txt", SKIP_ALLOWLIST);
        Path zeroExecutedAllowlist = writeFile("zero-executed-allowlist.txt", "");

        GradleException failure = assertThrows(
                GradleException.class,
                () -> TestExecutionGuard.inspect(
                        ":runtime:test", results, skipAllowlist, zeroExecutedAllowlist));

        assertTrue(
                failure.getMessage().contains("executed 0 tests"),
                () -> "unexpected message: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("zero-executed-allowlist.txt"),
                () -> "the failure must name the allowlist to register: " + failure.getMessage());
    }

    @Test
    void allSkippedSuiteFailsWhenTheAllowlistIsMissing() throws IOException {
        writeResults("dev.turboism.example.SkippedTest", 2, 2);
        Path skipAllowlist = writeFile("skip-allowlist.txt", SKIP_ALLOWLIST);
        Path zeroExecutedAllowlist = root.resolve("absent-allowlist.txt");

        assertThrows(
                GradleException.class,
                () -> TestExecutionGuard.inspect(
                        ":runtime:test", results, skipAllowlist, zeroExecutedAllowlist));
    }

    @Test
    void allSkippedSuitePassesOnlyWhenTheTaskIsRegisteredWithAReason() throws IOException {
        writeResults("dev.turboism.example.SkippedTest", 2, 2);
        Path skipAllowlist = writeFile("skip-allowlist.txt", SKIP_ALLOWLIST);
        Path zeroExecutedAllowlist =
                writeFile("zero-executed-allowlist.txt", ":runtime:test # fixtures are gitignored locally\n");

        TestExecutionGuard.Summary summary = TestExecutionGuard.inspect(
                ":runtime:test", results, skipAllowlist, zeroExecutedAllowlist);

        assertEquals(0, summary.executed());
        assertEquals(2, summary.skipped());
        assertTrue(summary.sanctionedZeroExecution(), "a registered zero-execution suite must stay visible in the log");
    }

    @Test
    void zeroExecutedRegistrationWithoutAReasonIsRejected() throws IOException {
        writeResults("dev.turboism.example.SkippedTest", 2, 2);
        Path skipAllowlist = writeFile("skip-allowlist.txt", SKIP_ALLOWLIST);
        Path zeroExecutedAllowlist = writeFile("zero-executed-allowlist.txt", ":runtime:test\n");

        GradleException failure = assertThrows(
                GradleException.class,
                () -> TestExecutionGuard.inspect(
                        ":runtime:test", results, skipAllowlist, zeroExecutedAllowlist));

        assertTrue(
                failure.getMessage().contains("without a reason"),
                () -> "unexpected message: " + failure.getMessage());
    }

    @Test
    void partialSkipsPassWhenEverySkipIsRegistered() throws IOException {
        writeResults("dev.turboism.example.SkippedTest", 3, 1);
        Path skipAllowlist = writeFile("skip-allowlist.txt", SKIP_ALLOWLIST);
        Path zeroExecutedAllowlist = writeFile("zero-executed-allowlist.txt", "");

        TestExecutionGuard.Summary summary = TestExecutionGuard.inspect(
                ":runtime:test", results, skipAllowlist, zeroExecutedAllowlist);

        assertEquals(2, summary.executed());
        assertEquals(1, summary.skipped());
        assertFalse(summary.sanctionedZeroExecution());
    }

    @Test
    void unregisteredSkipStillFails() throws IOException {
        writeResults("dev.turboism.example.OtherTest", 2, 2);
        Path skipAllowlist = writeFile("skip-allowlist.txt", SKIP_ALLOWLIST);
        Path zeroExecutedAllowlist =
                writeFile("zero-executed-allowlist.txt", ":runtime:test # registered for the wrong reason\n");

        GradleException failure = assertThrows(
                GradleException.class,
                () -> TestExecutionGuard.inspect(
                        ":runtime:test", results, skipAllowlist, zeroExecutedAllowlist));

        assertTrue(
                failure.getMessage().contains("not registered"),
                () -> "unexpected message: " + failure.getMessage());
    }

    @Test
    void nestedClassSkipsMatchTheOuterClassPattern() throws IOException {
        writeResults("dev.turboism.example.SkippedTest$Inner", 2, 2);
        Path skipAllowlist = writeFile("skip-allowlist.txt", SKIP_ALLOWLIST);
        Path zeroExecutedAllowlist =
                writeFile("zero-executed-allowlist.txt", ":runtime:test # registered for this fixture\n");

        TestExecutionGuard.Summary summary = TestExecutionGuard.inspect(
                ":runtime:test", results, skipAllowlist, zeroExecutedAllowlist);

        assertEquals(2, summary.skipped());
        assertTrue(summary.sanctionedZeroExecution());
    }

    private Path writeFile(String name, String content) throws IOException {
        Path path = root.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    /** Writes one JUnit XML file with {@code total} cases, {@code skipped} of them skipped. */
    private void writeResults(String className, int total, int skipped) throws IOException {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"").append(className).append("\" tests=\"").append(total).append("\" skipped=\"")
                .append(skipped).append("\">\n");
        for (int index = 0; index < total; index++) {
            xml.append("  <testcase name=\"case").append(index).append("()\" classname=\"")
                    .append(className).append("\"");
            if (index < skipped) {
                xml.append("><skipped/></testcase>\n");
            } else {
                xml.append("/>\n");
            }
        }
        xml.append("</testsuite>\n");
        Files.writeString(results.resolve("TEST-" + className + ".xml"), xml.toString(), StandardCharsets.UTF_8);
    }
}
