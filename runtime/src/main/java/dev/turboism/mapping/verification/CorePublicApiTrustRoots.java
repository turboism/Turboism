package dev.turboism.mapping.verification;

import java.util.Objects;

/** Public non-secret identities of the reviewed Core verification trust roots. */
public final class CorePublicApiTrustRoots {

    private CorePublicApiTrustRoots() {}

    /**
     * @param profile Cubism Core profile matching one of the twenty reviewed
     *     {@code cubism-<version>-core-model-read} records
     * @return the verification id of that profile's reviewed record
     * @throws IllegalArgumentException for any other profile; the set is closed
     *     on purpose so an unreviewed host cannot name a trust root
     * @throws NullPointerException if {@code profile} is {@code null}
     */
    public static String verificationId(final String profile) {
        Objects.requireNonNull(profile, "profile");
        return switch (profile) {
            case "5.0.00" -> "cubism-5.0.00.core-model-read.static";
            case "5.0.01" -> "cubism-5.0.01.core-model-read.static";
            case "5.0.02" -> "cubism-5.0.02.core-model-read.static";
            case "5.0.03" -> "cubism-5.0.03.core-model-read.static";
            case "5.0.04" -> "cubism-5.0.04.core-model-read.static";
            case "5.0.05" -> "cubism-5.0.05.core-model-read.static";
            case "5.0.06" -> "cubism-5.0.06.core-model-read.static";
            case "5.1.00" -> "cubism-5.1.00.core-model-read.static";
            case "5.1.01" -> "cubism-5.1.01.core-model-read.static";
            case "5.1.02" -> "cubism-5.1.02.core-model-read.static";
            case "5.1.03" -> "cubism-5.1.03.core-model-read.static";
            case "5.2.00" -> "cubism-5.2.00.core-model-read.static";
            case "5.2.01" -> "cubism-5.2.01.core-model-read.static";
            case "5.2.02" -> "cubism-5.2.02.core-model-read.static";
            case "5.2.03" -> "cubism-5.2.03.core-model-read.static";
            case "5.3.00" -> "cubism-5.3.00.core-model-read.static";
            case "5.3.01" -> "cubism-5.3.01.core-model-read.static";
            case "5.3.02" -> "cubism-5.3.02.core-model-read.static";
            case "5.3.03" -> "cubism-5.3.03.core-model-read.static";
            case "5.3.04" -> "cubism-5.3.04.core-model-read.static";
            default -> throw new IllegalArgumentException("unsupported Cubism Core profile: " + profile);
        };
    }
}
