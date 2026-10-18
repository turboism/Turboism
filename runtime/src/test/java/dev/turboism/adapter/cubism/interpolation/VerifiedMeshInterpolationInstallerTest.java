package dev.turboism.adapter.cubism.interpolation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class VerifiedMeshInterpolationInstallerTest {
    @Test
    void productInstallationEnablesVerifiedCoefficientsOnlyForItsLifetime() {
        String previous = System.getProperty("turboism.meshInterpolation.coefficients");
        System.clearProperty("turboism.meshInterpolation.coefficients");
        try {
            assertFalse(NativeMeshInterpolationBridge.coefficientsEnabled());
            try (var installed = VerifiedMeshInterpolationInstaller.install(
                    instrumentation(new AtomicInteger(), new AtomicInteger(), false, false),
                    ignored -> {},
                    true,
                    false)) {
                assertNotNull(installed);
                assertTrue(NativeMeshInterpolationBridge.coefficientsEnabled());
                System.setProperty("turboism.meshInterpolation.coefficients", "false");
                assertFalse(NativeMeshInterpolationBridge.coefficientsEnabled());
                System.clearProperty("turboism.meshInterpolation.coefficients");
                assertTrue(NativeMeshInterpolationBridge.coefficientsEnabled());
            }
            assertFalse(NativeMeshInterpolationBridge.coefficientsEnabled());
        } finally {
            if (previous == null) System.clearProperty("turboism.meshInterpolation.coefficients");
            else System.setProperty("turboism.meshInterpolation.coefficients", previous);
        }
    }

    @Test
    void serializationOnlyInstallationDoesNotEnableCoefficients() {
        try (var installed = VerifiedMeshInterpolationInstaller.install(
                instrumentation(new AtomicInteger(), new AtomicInteger(), false, false), ignored -> {}, false, true)) {
            assertNotNull(installed);
            assertFalse(NativeMeshInterpolationBridge.coefficientsEnabled());
        }
    }

    static Instrumentation instrumentation(
            AtomicInteger adds, AtomicInteger removes, boolean failAdd, boolean failRemove) {
        return (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getAllLoadedClasses" -> new Class<?>[0];
                        case "addTransformer" -> {
                            adds.incrementAndGet();
                            if (failAdd) throw new IllegalStateException("add");
                            yield null;
                        }
                        case "removeTransformer" -> {
                            removes.incrementAndGet();
                            if (failRemove) throw new IllegalStateException("remove");
                            yield true;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    @Test
    void duplicateAndRepeatedCloseDoNotRemoveAnotherInstallation() {
        var adds = new AtomicInteger();
        var removes = new AtomicInteger();
        var instrumentation = instrumentation(adds, removes, false, false);
        var first = VerifiedMeshInterpolationInstaller.install(instrumentation, ignored -> {});
        assertNotNull(first);
        try {
            assertNull(VerifiedMeshInterpolationInstaller.install(instrumentation, ignored -> {}));
        } finally {
            first.close();
        }
        first.close();
        assertEquals(1, adds.get());
        assertEquals(1, removes.get());
    }

    @Test
    void failedRegistrationDoesNotPoisonNextInstall() {
        var adds = new AtomicInteger();
        var removes = new AtomicInteger();
        assertNull(
                VerifiedMeshInterpolationInstaller.install(instrumentation(adds, removes, true, false), ignored -> {}));
        try (var next = VerifiedMeshInterpolationInstaller.install(
                instrumentation(adds, removes, false, false), ignored -> {})) {
            assertNotNull(next);
        }
        assertEquals(2, adds.get());
        assertEquals(1, removes.get());
    }

    @Test
    void failedRemovalDoesNotInterruptShutdownOrLeaveOwnership() {
        var adds = new AtomicInteger();
        var removes = new AtomicInteger();
        var first =
                VerifiedMeshInterpolationInstaller.install(instrumentation(adds, removes, false, true), ignored -> {});
        assertNotNull(first);
        assertDoesNotThrow(first::close);
        try (var next = VerifiedMeshInterpolationInstaller.install(
                instrumentation(adds, removes, false, false), ignored -> {})) {
            assertNotNull(next);
        }
        assertEquals(2, adds.get());
        assertEquals(2, removes.get());
    }

    @Test
    void bridgeConsolidatesToReviewedArtifactSource() {
        assertEquals(
                dev.turboism.mapping.verification.ReviewedHostArtifacts.reviewedSha256s(),
                NativeMeshInterpolationBridge.HOST_SHA256S);
    }

    @Test
    void bridgeUsesCachedVerifiedFingerprintWithoutReReadingArtifact() throws Exception {
        final java.nio.file.Path tempJar = java.nio.file.Files.createTempFile("test-cubism-artifact", ".jar");
        java.nio.file.Files.writeString(tempJar, "foreign content that would fail raw hash check");

        // Without cache, foreign artifact fails
        assertFalse(NativeMeshInterpolationBridge.isAdmittedHostArtifact(tempJar));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeMeshInterpolationBridge.requireAdmittedHostArtifact(tempJar));

        // When cached as a verified artifact, verification succeeds without re-reading
        final dev.turboism.mapping.verification.HostArtifactDigest reviewedDigest =
                dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_3_03;
        NativeMeshInterpolationBridge.cacheVerifiedArtifact(tempJar, reviewedDigest);

        assertEquals(reviewedDigest, NativeMeshInterpolationBridge.getCachedVerifiedFingerprint(tempJar));
        assertTrue(NativeMeshInterpolationBridge.isAdmittedHostArtifact(tempJar));
        assertDoesNotThrow(() -> NativeMeshInterpolationBridge.requireAdmittedHostArtifact(tempJar));
    }
}
