package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.core.CoreCapabilities;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.sdk.cubism.core.CoreVersion;
import dev.turboism.sdk.cubism.core.MocConsistency;
import dev.turboism.sdk.cubism.core.MocData;
import dev.turboism.sdk.cubism.core.MocInfo;
import dev.turboism.sdk.cubism.core.MocInspector;
import dev.turboism.sdk.cubism.core.MocVersion;

import java.util.Objects;

/** Runtime-owned normalization of admitted Core metadata and byte inspection. */
final class CoreRuntimeMetadata implements CoreRuntimeInfo {

    static final int DEFAULT_MOC_BYTE_QUOTA = 64 * 1024 * 1024;

    private final CorePublicApiProvider provider;
    private final Runnable freshness;
    private final int mocByteQuota;

    CoreRuntimeMetadata(final CorePublicApiProvider provider) {
        this(provider, () -> { }, DEFAULT_MOC_BYTE_QUOTA);
    }

    CoreRuntimeMetadata(final CorePublicApiProvider provider, final Runnable freshness) {
        this(provider, freshness, DEFAULT_MOC_BYTE_QUOTA);
    }

    CoreRuntimeMetadata(
        final CorePublicApiProvider provider,
        final Runnable freshness,
        final int mocByteQuota
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.freshness = Objects.requireNonNull(freshness, "freshness");
        if (mocByteQuota < 1) throw new IllegalArgumentException("mocByteQuota must be positive");
        this.mocByteQuota = mocByteQuota;
    }

    @Override
    public CoreVersion version() {
        freshness.run();
        final CoreRuntimeVersion version = requireValue(
            provider.runtimeVersion(),
            "Core runtime version"
        );
        return new CoreVersion(version.major(), version.minor(), version.patch());
    }

    @Override
    public CoreCapabilities capabilities() {
        freshness.run();
        return provider.capabilities();
    }

    @Override
    public MocInspector mocInspector() {
        if (!capabilities().mocInspection()) {
            throw unavailable("Core MOC inspection");
        }
        return new MocInspector() {
            @Override
            public MocVersion latestVersion() {
                freshness.run();
                return normalizeVersion(requireValue(
                    provider.latestMocVersion(),
                    "Core latest MOC version"
                ));
            }

            @Override
            public MocInfo inspect(final MocData data) {
                final MocData value = Objects.requireNonNull(data, "data");
                if (value.size() > mocByteQuota) {
                    throw new IllegalArgumentException(
                        "MOC data exceeds the configured byte quota of " + mocByteQuota + "."
                    );
                }
                final byte[] bytes = value.toByteArray();
                freshness.run();
                final int version = requireValue(
                    provider.mocVersion(bytes.clone()),
                    "Core MOC version"
                );
                final boolean consistent = requireValue(
                    provider.hasMocConsistency(bytes.clone()),
                    "Core MOC consistency"
                );
                return new MocInfo(
                    normalizeVersion(version),
                    consistent ? MocConsistency.CONSISTENT : MocConsistency.INCONSISTENT
                );
            }
        };
    }

    private static <T> T requireValue(
        final CoreProviderResult<T> result,
        final String feature
    ) {
        final CoreProviderFailure failure = Objects.requireNonNull(result, "result")
            .failure().orElse(null);
        if (failure == null) {
            return result.value().orElseThrow();
        }
        if (failure.code() == CoreProviderFailure.Code.ADAPTER_UNAVAILABLE
            || failure.code() == CoreProviderFailure.Code.EVIDENCE_REJECTED) {
            throw unavailable(feature);
        }
        throw new IllegalStateException(feature + " failed: " + failure.code());
    }

    private static MocVersion normalizeVersion(final int version) {
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

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
