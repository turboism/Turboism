package dev.turboism.tests.plugin;

import dev.turboism.sdk.ui.dialog.HostDialogOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused unit coverage for the dialog-automation probe's result contract. */
class HostDialogAutomationValidationProbeTest {

    @TempDir
    Path temporary;

    @Test
    void actedOutcomeWritesPassWithExpectedActualAndStatus() throws Exception {
        final Path result = temporary.resolve("state/dialog-automation-result.properties");

        HostDialogAutomationValidationProbe.writeResult(
            result, "run-1", "5302", HostDialogOutcome.ACTED, true, 1234L
        );

        final String content = Files.readString(result);
        assertTrue(content.contains("expected=ACTED\n"));
        assertTrue(content.contains("actual=ACTED\n"));
        assertTrue(content.contains("status=PASS\n"));
        assertTrue(content.contains("hostVersion=5302\n"));
        assertTrue(content.contains("runId=run-1\n"));
    }

    @Test
    void nonActedOutcomeWritesFail() throws Exception {
        final Path result = temporary.resolve("state/dialog-automation-result.properties");

        HostDialogAutomationValidationProbe.writeResult(
            result, "run-2", "5203", HostDialogOutcome.TIMEOUT, false, 30_000L
        );

        final String content = Files.readString(result);
        assertTrue(content.contains("expected=ACTED\n"));
        assertTrue(content.contains("actual=TIMEOUT\n"));
        assertTrue(content.contains("status=FAIL\n"));
    }

    @Test
    void outcomeVocabularyMatchesTheFrameworkContract() {
        assertEquals(
            "[ACTED, NOT_FOUND, TIMEOUT, AMBIGUOUS, UNSUPPORTED]",
            java.util.Arrays.toString(HostDialogOutcome.values())
        );
    }
}
