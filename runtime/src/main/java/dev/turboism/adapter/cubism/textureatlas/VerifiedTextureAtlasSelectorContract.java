package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.selector.TextureAtlasSelectorContract;
import dev.turboism.internal.ReviewedVersions;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Exact-version selector contract for the texture-atlas authoring provider.
 *
 * <p>The alias catalog is shared by every admitted host and delegated to
 * {@link TextureAtlasSelectorContract} so capability admission and runtime
 * authorization resolve the same sets; what differs per exact Cubism version
 * is declared as data in {@link Profile} rows looked up through
 * {@link #profileFor(String)}, so no type name encodes a version.</p>
 */
public final class VerifiedTextureAtlasSelectorContract {

    public static final String ADAPTER_SLICE_ID = TextureAtlasSelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = TextureAtlasSelectorContract.LAYOUT_WRITE_CAPABILITY_ID;

    public static final Set<String> REQUIRED_ALIASES = TextureAtlasSelectorContract.REQUIRED_ALIASES;

    public static final Set<String> HOOK_ALIASES = TextureAtlasSelectorContract.HOOK_ALIASES;

    public static final Set<String> AUTO_LAYOUT_HOOK_ALIASES = TextureAtlasSelectorContract.AUTO_LAYOUT_HOOK_ALIASES;

    public static final Set<String> NATIVE_INVOCATION_ALIASES = TextureAtlasSelectorContract.NATIVE_INVOCATION_ALIASES;

    public static final Set<String> DIALOG_INJECTION_ALIASES = TextureAtlasSelectorContract.DIALOG_INJECTION_ALIASES;

    // The statistics view contribution is verified on every 5.2 and 5.3 profile: the four
    // selectors bind the same members on all nine artifacts. Every profile below 5.2 uses a
    // differently structured editor view, so those profiles carry an empty statistics set.
    public static final Set<String> STATISTICS_ALIASES = TextureAtlasSelectorContract.STATISTICS_ALIASES;

    /**
     * The complete reviewed selector scope bound to one exact Cubism version. All members are
     * immutable sets; {@code statisticsAliases} is empty where the artifact has no statistics
     * view contribution.
     */
    public record Profile(
            String cubismVersion,
            Set<String> requiredAliases,
            Set<String> hookAliases,
            Set<String> autoLayoutHookAliases,
            Set<String> nativeInvocationAliases,
            Set<String> dialogInjectionAliases,
            Set<String> statisticsAliases) {}

    private static Profile profile520AndBelow(final String cubismVersion) {
        return new Profile(
                cubismVersion,
                REQUIRED_ALIASES,
                HOOK_ALIASES,
                AUTO_LAYOUT_HOOK_ALIASES,
                Set.of(),
                DIALOG_INJECTION_ALIASES,
                Set.of());
    }

    private static final Map<String, Profile> PROFILES = ReviewedVersions.since("5.0.00").stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                    java.util.function.Function.identity(),
                    version -> ReviewedVersions.since("5.2.00").contains(version)
                            ? new Profile(
                                    version,
                                    REQUIRED_ALIASES,
                                    HOOK_ALIASES,
                                    AUTO_LAYOUT_HOOK_ALIASES,
                                    NATIVE_INVOCATION_ALIASES,
                                    DIALOG_INJECTION_ALIASES,
                                    STATISTICS_ALIASES)
                            : profile520AndBelow(version)));

    /**
     * Returns the reviewed profile for one exact Cubism version.
     *
     * @param cubismVersion the version reported by the verified resolver
     * @return the bound profile, or empty when no reviewed record covers that version
     */
    public static Optional<Profile> profileFor(final String cubismVersion) {
        return Optional.ofNullable(PROFILES.get(cubismVersion));
    }

    private VerifiedTextureAtlasSelectorContract() {}
}
