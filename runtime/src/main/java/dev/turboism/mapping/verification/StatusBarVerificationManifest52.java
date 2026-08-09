package dev.turboism.mapping.verification;

/**
 * Runtime trust root constants for the exact Cubism 5.2.03 status-bar slice,
 * companion to {@link StatusBarVerificationManifest} (5.3.02). Admission is
 * exact-digest based; the 5.2.03 record pins selectors observed from the
 * exact 5.2.03 classfiles (see {@code cubism-ref/verification/cubism-5.2-ui-status-bar.json}).
 */
final class StatusBarVerificationManifest52 {

    static final String VERIFICATION_ID = "cubism-5.2.03.ui-status-bar.static";
    static final String RECORD_SHA256 =
        "94ef52c898cffe9b5837dd3e34e53ba150fc2d616f1269362e5151ec602fe4c0";
    static final String CUBISM_VERSION = "5.2.03";
    static final String PROFILE_ID = "cubism-5.2";
    static final long ARTIFACT_SIZE = 40_805_584L;
    static final String ARTIFACT_SHA256 =
        "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd";

    private StatusBarVerificationManifest52() {
    }
}
