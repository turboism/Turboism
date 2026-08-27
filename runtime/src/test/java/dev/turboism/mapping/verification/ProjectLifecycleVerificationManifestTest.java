package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectLifecycleVerificationManifestTest {

    @Test
    void exact5303CandidateRequiresTheCompleteTaskIdentity() {
        assertTrue(ProjectLifecycleVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_PROJECT_LIFECYCLE_HOOK_CANDIDATE",
            "5303",
            "project-lifecycle-hook-5303",
            "project-lifecycle-r1"
        ));
        assertFalse(ProjectLifecycleVerificationManifest.admits5303ValidationCandidate(
            null,
            "5303",
            "project-lifecycle-hook-5303",
            "project-lifecycle-r1"
        ));
        assertFalse(ProjectLifecycleVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_PROJECT_LIFECYCLE_HOOK_CANDIDATE",
            "5302",
            "project-lifecycle-hook-5303",
            "project-lifecycle-r1"
        ));
        assertFalse(ProjectLifecycleVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_PROJECT_LIFECYCLE_HOOK_CANDIDATE",
            "5303",
            "project-lifecycle-hook-5302",
            "project-lifecycle-r1"
        ));
        assertFalse(ProjectLifecycleVerificationManifest.admits5303ValidationCandidate(
            "EXACT_5303_PROJECT_LIFECYCLE_HOOK_CANDIDATE",
            "5303",
            "project-lifecycle-hook-5303",
            " "
        ));
    }
}
