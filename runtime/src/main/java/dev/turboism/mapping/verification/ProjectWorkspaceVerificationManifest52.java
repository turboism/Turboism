package dev.turboism.mapping.verification;

/** Runtime trust root for the exact Cubism 5.2 project/workspace binding. */
final class ProjectWorkspaceVerificationManifest52 {

    static final String VERIFICATION_ID = "m15.cubism-5.2.project-workspace.static";
    static final String RECORD_SHA256 =
        "661be268ad68726eecfdad7ca385f0c52c0105f1180c72813547504b23f81003";
    static final String CUBISM_VERSION = "5.2.0";
    static final String PROFILE_ID = "cubism-5.2";
    static final long ARTIFACT_SIZE = ReviewedHostArtifacts.CUBISM_5_2_03.size();
    static final String ARTIFACT_SHA256 = ReviewedHostArtifacts.CUBISM_5_2_03.sha256();

    private ProjectWorkspaceVerificationManifest52() {
    }
}
