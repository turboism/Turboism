package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.core.MocData;
import dev.turboism.sdk.cubism.core.MocVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreRuntimeMetadataTest {

    @Test
    void unadmittedMocOperationsFailClosed() {
        final CoreRuntimeMetadata metadata = new CoreRuntimeMetadata(
            CorePublicApiProvider.safeMode()
        );

        assertThrows(UnsupportedOperationException.class, metadata::mocInspector);
        assertThrows(UnsupportedOperationException.class, metadata::version);
    }

    @Test
    void unknownMocVersionsRemainNormalized() {
        final CorePublicApiProvider provider = new CorePublicApiProvider() {
            @Override public String providerId() { return "test"; }
            @Override public String artifactProfile() { return "test"; }
            @Override public boolean available() { return true; }
            @Override public CoreProviderResult<CoreRuntimeVersion> runtimeVersion() {
                return CoreProviderResult.success(new CoreRuntimeVersion(1, 2, 3));
            }
            @Override public dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() {
                return new dev.turboism.sdk.cubism.core.CoreCapabilities(false, false, true);
            }
            @Override public CoreProviderResult<Integer> latestMocVersion() {
                return CoreProviderResult.success(99);
            }
            @Override public CoreProviderResult<Integer> mocVersion(final byte[] bytes) {
                return CoreProviderResult.success(99);
            }
            @Override public CoreProviderResult<Boolean> hasMocConsistency(final byte[] bytes) {
                return CoreProviderResult.success(false);
            }
        };

        final var inspector = new CoreRuntimeMetadata(provider).mocInspector();
        org.junit.jupiter.api.Assertions.assertEquals(MocVersion.UNKNOWN, inspector.latestVersion());
        org.junit.jupiter.api.Assertions.assertEquals(
            MocVersion.UNKNOWN,
            inspector.inspect(MocData.copyOf(new byte[]{1})).version()
        );
    }
}
