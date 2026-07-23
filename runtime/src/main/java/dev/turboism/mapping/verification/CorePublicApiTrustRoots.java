package dev.turboism.mapping.verification;

import java.util.Objects;

/** Public non-secret identities of the reviewed Core verification trust roots. */
public final class CorePublicApiTrustRoots {

    private CorePublicApiTrustRoots() {
    }

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
