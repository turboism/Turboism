package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verdict-seam tests for {@link WindowsMeshMirrorAxisValidationProbe}'s roundtrip report. */
final class WindowsMeshMirrorAxisValidationProbeTest {

    @Test
    void roundtripPassedOnlyAfterSetAndRestore() {
        assertTrue(WindowsMeshMirrorAxisValidationProbe.roundtripPassed(45.0f, 0.0f));
        assertFalse(WindowsMeshMirrorAxisValidationProbe.roundtripPassed(45.0f, 1.0f));
        assertFalse(WindowsMeshMirrorAxisValidationProbe.roundtripPassed(44.9f, 0.0f));
    }

    @Test
    void roundtripReportCarriesTheVerdictLine() {
        final String report = WindowsMeshMirrorAxisValidationProbe.roundtripReport(0.0f, 45.0f, 0.0f);
        assertTrue(report.contains("initialAngleDegrees=0.0"));
        assertTrue(report.contains("afterSet45Degrees=45.0"));
        assertTrue(report.contains("afterRestore0Degrees=0.0"));
        assertTrue(report.contains("roundtripPassed=true"));

        final String failed = WindowsMeshMirrorAxisValidationProbe.roundtripReport(0.0f, 45.0f, 12.0f);
        assertTrue(failed.contains("roundtripPassed=false"));
    }
}
