package dev.turboism.ui.resource;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.sdk.ui.resource.CubismIcon;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Static evidence only: logical-16 PNG resource paths in the nine exact reviewed 5.2/5.3
 * artifacts, not bundled assets.
 *
 * <p>The whole-artifact identity in {@link ReviewedHostArtifacts} proves the archive, and
 * {@link CubismNativeIconResolver#decodePng} proves each extracted PNG's signature, size and
 * square bounds. A per-PNG digest would restate that same trust boundary 120 times, so the
 * catalog names paths only.</p>
 */
final class CubismNativeIconCatalog {
    private CubismNativeIconCatalog() {}

    static Optional<HostArtifactDigest> artifact(final String version) {
        java.util.Objects.requireNonNull(version, "version");
        if (!ReviewedVersions.since("5.2.00").contains(version)) {
            return Optional.empty();
        }
        return ReviewedHostArtifacts.all().stream()
                .filter(artifact -> ReviewedHostArtifacts.cubismVersionOf(artifact)
                        .orElseThrow()
                        .equals(version))
                .findFirst();
    }

    // Rows: object, theme, disabled. Scales 100/125/150/175/200 share one resource name.
    private static final String VARIANTS = """
        ART_MESH LIGHT false
        ART_MESH LIGHT true
        ART_MESH DARK false
        ART_MESH DARK true
        WARP_DEFORMER LIGHT false
        WARP_DEFORMER LIGHT true
        WARP_DEFORMER DARK false
        WARP_DEFORMER DARK true
        ROTATION_DEFORMER LIGHT false
        ROTATION_DEFORMER LIGHT true
        ROTATION_DEFORMER DARK false
        ROTATION_DEFORMER DARK true
        PART LIGHT false
        PART LIGHT true
        PART DARK false
        PART DARK true
        LOCK LIGHT false
        LOCK LIGHT true
        LOCK DARK false
        LOCK DARK true
        LOCK_PLACEHOLDER LIGHT false
        LOCK_PLACEHOLDER LIGHT true
        LOCK_PLACEHOLDER DARK false
        LOCK_PLACEHOLDER DARK true
        """;

    /** The five scales every reviewed sheet ships. */
    private static final int[] SCALES = {100, 125, 150, 175, 200};

    private static final Map<NativeIconVariant, String> RESOURCES = parseVariants();

    static Map<NativeIconVariant, String> resources() {
        return RESOURCES;
    }

    private static Map<NativeIconVariant, String> parseVariants() {
        final Map<NativeIconVariant, String> result = new LinkedHashMap<>();
        for (String row : VARIANTS.strip().split("\\R")) {
            final String[] cells = row.strip().split(" +");
            if (cells.length != 3 || !(cells[2].equals("true") || cells[2].equals("false"))) {
                throw new IllegalStateException("invalid native icon catalog row");
            }
            for (int scale : SCALES) {
                final NativeIconVariant key = new NativeIconVariant(
                        CubismIcon.valueOf(cells[0]),
                        NativeIconVariant.Theme.valueOf(cells[1]),
                        scale,
                        Boolean.parseBoolean(cells[2]));
                if (result.putIfAbsent(key, key.resourcePath()) != null) {
                    throw new IllegalStateException("duplicate native icon variant");
                }
            }
        }
        if (result.size() != 120) throw new IllegalStateException("incomplete native icon catalog");
        return Map.copyOf(result);
    }
}
