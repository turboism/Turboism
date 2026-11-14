package dev.turboism.gradle.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The value source is the replacement for configuration-time {@code providers.exec}, so the
 * contract these tests pin is the one the build scripts rely on: a captured result reports its
 * exit status and both streams, and a command that never starts is distinguishable from one that
 * ran and failed.
 */
class CommandLineValueSourceTest {

    @Test
    void resultReportsSuccessAndBothStreams() {
        CommandLineValueSource.Result result = new CommandLineValueSource.Result(0, "  id-42 \n", "warning\n", "");

        assertTrue(result.succeeded());
        assertEquals(0, result.getExitValue());
        assertEquals("id-42", result.trimmedStandardOutput());
        assertEquals("  id-42 \n", result.getStandardOutput());
        assertEquals("warning\n", result.getStandardError());
        assertEquals("", result.getFailure());
    }

    @Test
    void nonZeroExitStatusIsNotSuccessButKeepsItsStreams() {
        CommandLineValueSource.Result result = new CommandLineValueSource.Result(3, "", "Forbidden worktree ID: test\n", "");

        assertFalse(result.succeeded());
        assertEquals(3, result.getExitValue());
        assertEquals("Forbidden worktree ID: test", result.getStandardError().trim());
    }

    @Test
    void launchFailureIsDistinguishableFromAFailedCommand() {
        CommandLineValueSource.Result neverStarted =
            new CommandLineValueSource.Result(-1, "", "", "Cannot run program \"bash\": No such file");

        assertFalse(neverStarted.succeeded());
        assertEquals(-1, neverStarted.getExitValue());
        assertTrue(neverStarted.getFailure().contains("Cannot run program"));
        assertEquals("", neverStarted.trimmedStandardOutput());
    }

    @Test
    void blankOutputIsAnEmptyProbeResultNotAFailure() {
        CommandLineValueSource.Result result = new CommandLineValueSource.Result(0, "\n\n", "", "");

        assertTrue(result.succeeded());
        assertEquals("", result.trimmedStandardOutput());
    }
}
