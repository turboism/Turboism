package dev.turboism.mapping.verification;

import java.util.Objects;

/** Public non-secret identities of the reviewed Core verification trust roots. */
public final class CorePublicApiTrustRoots {

    private CorePublicApiTrustRoots() {
    }

    /**
     * @param profile Cubism Core profile, {@code "5.2"} or {@code "5.3.02"}
     * @return the verification id of that profile's reviewed record
     * @throws IllegalArgumentException for any other profile; the set is closed
     *     on purpose so an unreviewed host cannot name a trust root
     * @throws NullPointerException if {@code profile} is {@code null}
     */
    public static String verificationId(final String profile) {
        Objects.requireNonNull(profile, "profile");
        return switch (profile) {
            case "5.2" -> "cubism-5.2.core-model-read.static";
            case "5.3.02" -> "cubism-5.3.02.core-model-read.static";
            default -> throw new IllegalArgumentException(
                "unsupported Cubism Core profile: " + profile
            );
        };
    }
}
