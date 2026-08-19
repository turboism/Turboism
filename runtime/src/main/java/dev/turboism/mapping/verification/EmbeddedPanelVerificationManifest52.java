package dev.turboism.mapping.verification;

/** Runtime trust root for the exact Cubism 5.2.03 embedded-panel binding. */
final class EmbeddedPanelVerificationManifest52 {

    static final String VERIFICATION_ID = "cubism-5.2.03.ui-embedded-panel.static";
    static final String RECORD_SHA256 =
        "cd3238ac79ece01dbac0ca9b83c428d49c05f11d48ba1b213253362d37807ec4";
    static final String CUBISM_VERSION = "5.2.03";
    static final String PROFILE_ID = "cubism-5.2";
    static final long ARTIFACT_SIZE = ReviewedHostArtifacts.CUBISM_5_2_03.size();
    static final String ARTIFACT_SHA256 = ReviewedHostArtifacts.CUBISM_5_2_03.sha256();

    private EmbeddedPanelVerificationManifest52() {
    }
}
