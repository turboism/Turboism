package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.NativeOptimizationConfig.Choice;
import dev.turboism.config.NativeOptimizationConfig.Option;
import dev.turboism.config.NativeOptimizationDiagnostics.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Aggregation of protocol outcomes per option: one preference served by two hooks shows what
 * each of them did, instead of whichever reported last.
 */
final class NativeOptimizationDiagnosticsTest {

    private static final String UPLOAD_ELISION = "TURBOISM_UPLOAD_ELISION";
    private static final String LINE_SPLIT = "TURBOISM_LINE_RENDERER_UPLOAD_SPLIT";

    @BeforeEach
    @AfterEach
    void reset() {
        NativeOptimizationDiagnostics.resetForTesting();
    }

    @Test
    void oneRefusedProtocolMakesTheOptionPartialAndNamesItsReason() {
        requested(true);
        NativeOptimizationDiagnostics.installed(UPLOAD_ELISION, "not-installed", "uniformLocationCache-enabled");
        NativeOptimizationDiagnostics.installed(LINE_SPLIT, "installed", "verified");
        final Status status = status(Option.UPLOAD_ELISION);
        assertEquals(NativeOptimizationDiagnostics.STATE_PARTIAL, status.state());
        assertEquals(UPLOAD_ELISION + "=uniformLocationCache-enabled", status.reason());
    }

    @Test
    void partialStateDoesNotDependOnReportOrder() {
        requested(true);
        NativeOptimizationDiagnostics.installed(LINE_SPLIT, "installed", "verified");
        NativeOptimizationDiagnostics.installed(UPLOAD_ELISION, "not-installed", "uniformLocationCache-enabled");
        final Status status = status(Option.UPLOAD_ELISION);
        assertEquals(NativeOptimizationDiagnostics.STATE_PARTIAL, status.state());
        assertEquals(UPLOAD_ELISION + "=uniformLocationCache-enabled", status.reason());
    }

    @Test
    void everyServingProtocolInstalledIsInstalled() {
        requested(true);
        NativeOptimizationDiagnostics.installed(UPLOAD_ELISION, "installed", "verified");
        NativeOptimizationDiagnostics.installed(LINE_SPLIT, "installed", "verified");
        final Status status = status(Option.UPLOAD_ELISION);
        assertEquals("installed", status.state());
        assertEquals("verified", status.reason());
    }

    @Test
    void noProtocolInstalledKeepsThePreferenceDisabledPrecedence() {
        requested(false);
        NativeOptimizationDiagnostics.installed(UPLOAD_ELISION, "not-installed", "modelUpdateSkip-disabled");
        NativeOptimizationDiagnostics.installed(LINE_SPLIT, "not-installed", "preference-disabled");
        final Status status = status(Option.UPLOAD_ELISION);
        assertEquals("not-installed", status.state());
        assertEquals("preference-disabled", status.reason());
    }

    @Test
    void theMostSignificantRefusalWinsInsteadOfTheLastReport() {
        requested(true);
        NativeOptimizationDiagnostics.installed(UPLOAD_ELISION, "failed", "LinkageError");
        NativeOptimizationDiagnostics.installed(LINE_SPLIT, "not-installed", "host-not-supported-or-hook-disabled");
        final Status reported = status(Option.UPLOAD_ELISION);
        assertEquals("failed", reported.state());
        assertEquals("LinkageError", reported.reason());

        NativeOptimizationDiagnostics.resetForTesting();
        requested(true);
        NativeOptimizationDiagnostics.installed(LINE_SPLIT, "not-installed", "host-not-supported-or-hook-disabled");
        NativeOptimizationDiagnostics.installed(UPLOAD_ELISION, "failed", "LinkageError");
        assertEquals(reported, status(Option.UPLOAD_ELISION));
    }

    @Test
    void aPendingReportDoesNotDowngradeAnInstalledProtocol() {
        requested(Option.SHADER_UNIFORM, true);
        NativeOptimizationDiagnostics.installed("TURBOISM_SHADER_UNIFORM", "installed", "PATCHED_5304");
        NativeOptimizationDiagnostics.installed("TURBOISM_SHADER_UNIFORM", "pending", "waiting-class-load");
        final Status status = status(Option.SHADER_UNIFORM);
        assertEquals("installed", status.state());
        assertEquals("PATCHED_5304", status.reason());
    }

    @Test
    void aClosedSingleProtocolOptionReportsClosed() {
        requested(Option.MODEL_UPDATE_SKIP, true);
        NativeOptimizationDiagnostics.installed("TURBOISM_MODEL_UPDATE_SKIP", "installed", "verified");
        NativeOptimizationDiagnostics.installed("TURBOISM_MODEL_UPDATE_SKIP", "not-installed", "closed");
        final Status status = status(Option.MODEL_UPDATE_SKIP);
        assertEquals("not-installed", status.state());
        assertEquals("closed", status.reason());
    }

    @Test
    void anOptionWithoutAnyReportStaysNotAttempted() {
        requested(Option.ASYNC_READBACK, true);
        final Status status = status(Option.ASYNC_READBACK);
        assertEquals("not-installed", status.state());
        assertEquals("not-attempted", status.reason());
    }

    @Test
    void aValidationOnlyProbeReportProvesNoOption() {
        NativeOptimizationDiagnostics.installed("TURBOISM_CLIP_MASK_PROBE", "installed", "verified");
        assertTrue(NativeOptimizationDiagnostics.snapshot().isEmpty());
        assertFalse(NativeOptimizationDiagnostics.protocols().containsKey("TURBOISM_CLIP_MASK_PROBE"));
    }

    @Test
    void aDisabledPreferenceWithoutAnyReportSaysSo() {
        requested(Option.ASYNC_READBACK, false);
        final Status status = status(Option.ASYNC_READBACK);
        assertEquals("not-installed", status.state());
        assertEquals("preference-disabled", status.reason());
    }

    private static void requested(final boolean enabled) {
        requested(Option.UPLOAD_ELISION, enabled);
    }

    private static void requested(final Option option, final boolean enabled) {
        NativeOptimizationDiagnostics.requested(option, new Choice(enabled, "global-config"));
    }

    private static Status status(final Option option) {
        final Status status = NativeOptimizationDiagnostics.snapshot().get(option);
        assertNotNull(status, option.key() + " has no status");
        return status;
    }
}
