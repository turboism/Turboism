package dev.turboism.mapping.verification;

/** Runtime trust root for the exact Cubism 5.2 Editor model read/write binding. */
final class EditorModelVerificationManifest52 {

    static final String VERIFICATION_ID = "cubism-5.2.editor-model.static";
    static final String RECORD_SHA256 =
        "d3b55e0ea62a1dbdcbd09c6a985c8503e07a6e345958cf9702fe52ae58e28ac8";
    static final String CUBISM_VERSION = "5.2.0";
    static final String PROFILE_ID = "cubism-5.2";
    static final long ARTIFACT_SIZE = ReviewedHostArtifacts.CUBISM_5_2_03.size();
    static final String ARTIFACT_SHA256 = ReviewedHostArtifacts.CUBISM_5_2_03.sha256();

    private EditorModelVerificationManifest52() {
    }
}
