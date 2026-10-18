package dev.turboism.adapter.cubism.interpolation;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class VerifiedMeshInterpolationInstallerTest {
    @org.junit.jupiter.api.io.TempDir
    Path temporary;

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
    void bridgeRejectsForeignArtifactsEvenAfterTheirFingerprintIsCached() throws Exception {
        final Path jar = Files.writeString(temporary.resolve("host.jar"), "foreign content");
        assertEquals(HostArtifactDigest.from(jar), NativeMeshInterpolationBridge.hostArtifactDigest(jar));
        assertFalse(NativeMeshInterpolationBridge.isAdmittedHostArtifact(jar));
        assertThrows(
                IllegalArgumentException.class, () -> NativeMeshInterpolationBridge.requireAdmittedHostArtifact(jar));
    }

    @Test
    void fingerprintCacheStreamsOnlyOnceForUnchangedCanonicalFile() throws Exception {
        final Path jar = Files.writeString(temporary.resolve("host.jar"), "measured content");
        final AtomicInteger reads = new AtomicInteger();
        final var cache = new HostArtifactDigestCache(path -> {
            reads.incrementAndGet();
            return HostArtifactDigest.from(path);
        });
        final var expected = HostArtifactDigest.from(jar);
        assertEquals(expected, cache.digest(jar));
        assertEquals(expected, cache.digest(jar.getParent().resolve(".").resolve(jar.getFileName())));
        assertEquals(1, reads.get());
    }

    @Test
    void fingerprintCacheInvalidatesChangedReplacedAndDeletedFiles() throws Exception {
        final Path jar = Files.writeString(temporary.resolve("host.jar"), "initial content");
        final var cache = new HostArtifactDigestCache();
        final var first = cache.digest(jar);
        Files.writeString(jar, "longer modified content");
        assertNotEquals(first, cache.digest(jar));
        final var modified = cache.digest(jar);
        final FileTime timestamp = Files.getLastModifiedTime(jar);
        final Path replacement = Files.writeString(temporary.resolve("replacement.jar"), "changed content of size");
        Files.setLastModifiedTime(replacement, timestamp);
        Files.move(replacement, jar, StandardCopyOption.REPLACE_EXISTING);
        assertNotEquals(modified, cache.digest(jar));
        assertEquals(HostArtifactDigest.from(jar), cache.digest(jar));
        Files.delete(jar);
        assertThrows(IOException.class, () -> cache.digest(jar));
    }

    @Test
    void fingerprintCacheDoesNotPublishArtifactChangedDuringHashing() throws Exception {
        final Path jar = Files.writeString(temporary.resolve("host.jar"), "initial content");
        final AtomicInteger reads = new AtomicInteger();
        final var cache = new HostArtifactDigestCache(path -> {
            final var digest = HostArtifactDigest.from(path);
            if (reads.getAndIncrement() == 0) {
                Files.writeString(path, "content changed while hashing");
            }
            return digest;
        });
        assertThrows(IOException.class, () -> cache.digest(jar));
        assertEquals(HostArtifactDigest.from(jar), cache.digest(jar));
        assertEquals(2, reads.get());
    }
}
