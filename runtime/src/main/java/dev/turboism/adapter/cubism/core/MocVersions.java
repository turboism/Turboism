package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.core.MocVersion;

/** Maps the numeric Cubism Core MOC version code onto the SDK enum. */
final class MocVersions {

    /**
     * Returns the SDK MOC version for a numeric Core version code.
     *
     * @param version the numeric code reported by Cubism Core
     * @return the matching version, or {@link MocVersion#UNKNOWN} for an unreviewed code
     */
    static MocVersion normalize(final int version) {
        return switch (version) {
            case 1 -> MocVersion.V3_0;
            case 2 -> MocVersion.V3_3;
            case 3 -> MocVersion.V4_0;
            case 4 -> MocVersion.V4_2;
            case 5 -> MocVersion.V5_0;
            case 6 -> MocVersion.V5_3;
            default -> MocVersion.UNKNOWN;
        };
    }

    private MocVersions() {}
}
