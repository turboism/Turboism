package dev.turboism.adapter.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostUiVersionCheckTest {

    @Test
    void reviewed5303IdentityEntersTheHostUiAllowlist() {
        assertTrue(HostUiVersionCheck.REVIEWED_HOST_VERSIONS.contains("5.3.03"));
        assertTrue(HostUiVersionCheck.diagnosticFor("test.capability", "5.3.03").isEmpty());
    }

    @Test
    void admitsOnlyReviewedExactHostVersions() {
        for (String version : HostUiVersionCheck.REVIEWED_HOST_VERSIONS) {
            assertTrue(HostUiVersionCheck.diagnosticFor("test.capability", version).isEmpty());
        }
    }

    @Test
    void rejectsUnreviewedPatchesInsidePreviouslyAcceptedRanges() {
        for (String version : new String[]{"5.2.00", "5.2.04", "5.3.00", "5.3.01", "5.3.04"}) {
            SafeModeDiagnostic diagnostic = HostUiVersionCheck
                .diagnosticFor("test.capability", version)
                .orElseThrow();
            assertEquals(SafeModeDiagnostic.Code.HOST_VERSION_UNSUPPORTED, diagnostic.code());
        }
    }

    @Test
    void rejectsMalformedAndBlankVersionsWithoutThrowing() {
        for (String version : new String[]{"", "5.3", "not-a-version"}) {
            assertEquals(
                SafeModeDiagnostic.Code.HOST_VERSION_UNSUPPORTED,
                HostUiVersionCheck.diagnosticFor("test.capability", version).orElseThrow().code()
            );
        }
    }
}
