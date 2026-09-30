package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.selector.TextureAtlasSelectorContract;

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

    public static final Set<String> AUTO_LAYOUT_HOOK_ALIASES =
        TextureAtlasSelectorContract.AUTO_LAYOUT_HOOK_ALIASES;

    public static final Set<String> NATIVE_INVOCATION_ALIASES =
        TextureAtlasSelectorContract.NATIVE_INVOCATION_ALIASES;

    public static final Set<String> DIALOG_INJECTION_ALIASES =
        TextureAtlasSelectorContract.DIALOG_INJECTION_ALIASES;

    // The statistics view contribution is verified on 5.2.00-5.2.02 and every 5.3 profile:
    // the 5.2.03 artifact (and every profile below 5.2) uses a differently structured editor
    // view, so those profiles carry an empty statistics set.
    public static final Set<String> STATISTICS_ALIASES =
        TextureAtlasSelectorContract.STATISTICS_ALIASES;

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
        Set<String> statisticsAliases
    ) {
    }

    private static Profile profile520AndBelow(final String cubismVersion) {
        return new Profile(
            cubismVersion,
            REQUIRED_ALIASES,
            HOOK_ALIASES,
            AUTO_LAYOUT_HOOK_ALIASES,
            Set.of(),
            DIALOG_INJECTION_ALIASES,
            Set.of()
        );
    }

    private static final Map<String, Profile> PROFILES = Map.ofEntries(
        Map.entry("5.0.00", profile520AndBelow("5.0.00")),
        Map.entry("5.0.01", profile520AndBelow("5.0.01")),
        Map.entry("5.0.02", profile520AndBelow("5.0.02")),
        Map.entry("5.0.03", profile520AndBelow("5.0.03")),
        Map.entry("5.0.04", profile520AndBelow("5.0.04")),
        Map.entry("5.0.05", profile520AndBelow("5.0.05")),
        Map.entry("5.0.06", profile520AndBelow("5.0.06")),
        Map.entry("5.1.00", profile520AndBelow("5.1.00")),
        Map.entry("5.1.01", profile520AndBelow("5.1.01")),
        Map.entry("5.1.02", profile520AndBelow("5.1.02")),
        Map.entry("5.1.03", profile520AndBelow("5.1.03")),
        Map.entry("5.2.00", new Profile(
            "5.2.00",
            REQUIRED_ALIASES,
            HOOK_ALIASES,
            AUTO_LAYOUT_HOOK_ALIASES,
            NATIVE_INVOCATION_ALIASES,
            DIALOG_INJECTION_ALIASES,
            STATISTICS_ALIASES
        )),
        Map.entry("5.2.01", new Profile(
            "5.2.01",
            REQUIRED_ALIASES,
            HOOK_ALIASES,
            AUTO_LAYOUT_HOOK_ALIASES,
            NATIVE_INVOCATION_ALIASES,
            DIALOG_INJECTION_ALIASES,
            STATISTICS_ALIASES
        )),
        Map.entry("5.2.02", new Profile(
            "5.2.02",
            REQUIRED_ALIASES,
            HOOK_ALIASES,
            AUTO_LAYOUT_HOOK_ALIASES,
            NATIVE_INVOCATION_ALIASES,
            DIALOG_INJECTION_ALIASES,
            STATISTICS_ALIASES
        )),
        Map.entry("5.2.03", new Profile(
            "5.2.03",
            REQUIRED_ALIASES,
            HOOK_ALIASES,
            AUTO_LAYOUT_HOOK_ALIASES,
            NATIVE_INVOCATION_ALIASES,
            DIALOG_INJECTION_ALIASES,
            Set.of()
        )),
        Map.entry("5.3.00", new Profile(
            "5.3.00",
            REQUIRED_ALIASES,
            HOOK_ALIASES,
            AUTO_LAYOUT_HOOK_ALIASES,
            NATIVE_INVOCATION_ALIASES,
            DIALOG_INJECTION_ALIASES,
            STATISTICS_ALIASES
        )),
        Map.entry("5.3.01", new Profile(
            "5.3.01",
            REQUIRED_ALIASES,
            HOOK_ALIASES,
            AUTO_LAYOUT_HOOK_ALIASES,
            NATIVE_INVOCATION_ALIASES,
            DIALOG_INJECTION_ALIASES,
            STATISTICS_ALIASES
        )),
        Map.entry("5.3.02", new Profile(
            "5.3.02",
            REQUIRED_ALIASES,
            HOOK_ALIASES,
            AUTO_LAYOUT_HOOK_ALIASES,
            NATIVE_INVOCATION_ALIASES,
            DIALOG_INJECTION_ALIASES,
            STATISTICS_ALIASES
        )),
        Map.entry("5.3.03", new Profile(
            "5.3.03",
            REQUIRED_ALIASES,
            HOOK_ALIASES,
            AUTO_LAYOUT_HOOK_ALIASES,
            NATIVE_INVOCATION_ALIASES,
            DIALOG_INJECTION_ALIASES,
            STATISTICS_ALIASES
        )),
        Map.entry("5.3.04", new Profile(
            "5.3.04",
            REQUIRED_ALIASES,
            HOOK_ALIASES,
            AUTO_LAYOUT_HOOK_ALIASES,
            NATIVE_INVOCATION_ALIASES,
            DIALOG_INJECTION_ALIASES,
            STATISTICS_ALIASES
        ))
    );

    /**
     * Returns the reviewed profile for one exact Cubism version.
     *
     * @param cubismVersion the version reported by the verified resolver
     * @return the bound profile, or empty when no reviewed record covers that version
     */
    public static Optional<Profile> profileFor(final String cubismVersion) {
        return Optional.ofNullable(PROFILES.get(cubismVersion));
    }

    private VerifiedTextureAtlasSelectorContract() {
    }
}
