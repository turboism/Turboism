package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterLifecycleVerificationManifestTest {

    @Test
    void candidateRequiresTheExactCompoundIdentity() {
        assertTrue(ParameterLifecycleVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_PARAMETER_LIFECYCLE_HOOK_CANDIDATE",
            "5303",
            "parameter-lifecycle-hook-5303",
            "parameter-hook-r1"
        ));
        assertFalse(ParameterLifecycleVerificationManifest.admits5303ValidationCandidate(
            "wrong", "5303", "parameter-lifecycle-hook-5303", "parameter-hook-r1"
        ));
        assertFalse(ParameterLifecycleVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_PARAMETER_LIFECYCLE_HOOK_CANDIDATE",
            "5302",
            "parameter-lifecycle-hook-5303",
            "parameter-hook-r1"
        ));
        assertFalse(ParameterLifecycleVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_PARAMETER_LIFECYCLE_HOOK_CANDIDATE",
            "5303",
            "parameter-value-write-5303",
            "parameter-hook-r1"
        ));
        assertFalse(ParameterLifecycleVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_PARAMETER_LIFECYCLE_HOOK_CANDIDATE",
            "5303",
            "parameter-lifecycle-hook-5303",
            " "
        ));
    }
}
