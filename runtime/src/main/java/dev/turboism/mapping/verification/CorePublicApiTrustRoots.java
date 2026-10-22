package dev.turboism.mapping.verification;

import java.util.Objects;

/** Public non-secret identities of the reviewed Core verification trust roots. */
public final class CorePublicApiTrustRoots {

    private CorePublicApiTrustRoots() {}

    /**
     * @param profile Cubism Core profile matching one of the reviewed
     *     {@code cubism-<version>-core-model-read} records
     * @return the verification id of that profile's reviewed record
     * @throws IllegalArgumentException for any other profile; the set is closed
     *     on purpose so an unreviewed host cannot name a trust root
     * @throws NullPointerException if {@code profile} is {@code null}
     */
    public static String verificationId(final String profile) {
        Objects.requireNonNull(profile, "profile");
        if (ReviewedReleaseTable.find(profile).isEmpty()) {
            throw new IllegalArgumentException("unsupported Cubism Core profile: " + profile);
        }
        return "cubism-" + profile + ".core-model-read.static";
    }
}
