package dev.turboism.adapter.cubism.optimization.scenehandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.camera.CameraMatrixCacheTarget;
import dev.turboism.adapter.cubism.optimization.camera.CameraMatrixCacheTransformer;
import dev.turboism.adapter.cubism.optimization.warpdraw.WarpNormalPointElisionTarget;
import dev.turboism.adapter.cubism.optimization.warpdraw.WarpNormalPointElisionTransformer;
import dev.turboism.adapter.cubism.warpalt.WarpAltMirrorHostProfile;
import dev.turboism.adapter.cubism.warpalt.WarpAltMirrorNativeMethodTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Verifies exact-artifact admission and bytecode rewriting across all reviewed Cubism releases for
 * scene-handler caching (including WarpAltMirror composition), camera matrix caching, and warp
 * normal-point elision.
 */
final class InputAndRenderWeavesArtifactTest {

    @Test
    void everyConfiguredReviewedArtifactAdmitsExpectedWeavesAndComposedShapes() throws Exception {
        final Path root = referenceRoot();
        int checked = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = root.resolve(version).resolve("Live2D_Cubism.jar");
            if (!Files.isRegularFile(artifact)) continue;
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final ProtectionDomain domain = domain(artifact);

            try (URLClassLoader loader = loader(artifact.getParent())) {
                verifySceneHandlerWeave(version, artifact, digest, loader, domain);
                verifyCameraMatrixWeave(version, artifact, digest, loader, domain);
                verifyWarpNormalPointWeave(version, artifact, digest, loader, domain);
            }
            checked++;
        }
        assertTrue(checked > 0, "no reviewed exact artifact found under " + root);
    }

    private static void verifySceneHandlerWeave(
            final String version,
            final Path artifact,
            final HostArtifactDigest digest,
            final URLClassLoader loader,
            final ProtectionDomain domain)
            throws Exception {
        final SceneHandlerCacheTarget target = SceneHandlerCacheTarget.of(digest)
                .orElseThrow(() -> new AssertionError("scene-handler target refused: " + version));
        final byte[] viewBytes = bytes(artifact, SceneHandlerCacheTarget.VIEW_CONTEXT_OWNER);
        final byte[] entitiesBytes = bytes(artifact, SceneHandlerCacheTarget.ENTITIES_OWNER);
        final byte[] componentsBytes = bytes(artifact, SceneHandlerCacheTarget.COMPONENTS_OWNER);

        final SceneHandlerCacheTransformer rawTransformer = new SceneHandlerCacheTransformer(
                loader, artifact, target, viewBytes, entitiesBytes, componentsBytes);
        assertNotNull(
                rawTransformer.transform(
                        null, loader, SceneHandlerCacheTarget.VIEW_CONTEXT_OWNER, null, domain, viewBytes),
                version + " raw view: " + rawTransformer.failure());
        assertNotNull(
                rawTransformer.transform(
                        null, loader, SceneHandlerCacheTarget.ENTITIES_OWNER, null, domain, entitiesBytes),
                version + " entities: " + rawTransformer.failure());
        assertNotNull(
                rawTransformer.transform(
                        null, loader, SceneHandlerCacheTarget.COMPONENTS_OWNER, null, domain, componentsBytes),
                version + " components: " + rawTransformer.failure());
        assertEquals(3, rawTransformer.matches(), version);
        assertEquals(8, rawTransformer.sites(), version);
        assertNull(rawTransformer.failure(), version);

        final WarpAltMirrorHostProfile mirrorProfile =
                WarpAltMirrorHostProfile.forReviewedVersion(version).orElseThrow();
        final byte[] mirrorTransformedView = new WarpAltMirrorNativeMethodTransformer(mirrorProfile, loader)
                .transform(null, loader, SceneHandlerCacheTarget.VIEW_CONTEXT_OWNER, null, domain, viewBytes);
        assertNotNull(mirrorTransformedView, version + " warp-alt-mirror pre-weave");

        final SceneHandlerCacheTransformer composedTransformer = new SceneHandlerCacheTransformer(
                loader, artifact, target, viewBytes, entitiesBytes, componentsBytes);
        assertNotNull(
                composedTransformer.transform(
                        null,
                        loader,
                        SceneHandlerCacheTarget.VIEW_CONTEXT_OWNER,
                        null,
                        domain,
                        mirrorTransformedView),
                version + " composed view: " + composedTransformer.failure());
        assertNull(composedTransformer.failure(), version);
    }

    private static void verifyCameraMatrixWeave(
            final String version,
            final Path artifact,
            final HostArtifactDigest digest,
            final URLClassLoader loader,
            final ProtectionDomain domain)
            throws Exception {
        CameraMatrixCacheTarget.of(digest)
                .orElseThrow(() -> new AssertionError("camera-matrix target refused: " + version));
        final byte[] cameraBytes = bytes(artifact, CameraMatrixCacheTarget.CAMERA_OWNER);
        final CameraMatrixCacheTransformer transformer =
                new CameraMatrixCacheTransformer(loader, artifact, cameraBytes);
        assertNotNull(
                transformer.transform(null, loader, CameraMatrixCacheTarget.CAMERA_OWNER, null, domain, cameraBytes),
                version + " camera: " + transformer.failure());
        assertEquals(1, transformer.matches(), version);
        assertEquals(1, transformer.sites(), version);
        assertNull(transformer.failure(), version);
    }

    private static void verifyWarpNormalPointWeave(
            final String version,
            final Path artifact,
            final HostArtifactDigest digest,
            final URLClassLoader loader,
            final ProtectionDomain domain)
            throws Exception {
        final Optional<WarpNormalPointElisionTarget> target = WarpNormalPointElisionTarget.of(digest);
        if (!WarpNormalPointElisionTarget.SUPPORTED_VERSIONS.contains(version)) {
            assertTrue(target.isEmpty(), version + " must be refused before 5.0.06");
            return;
        }
        final WarpNormalPointElisionTarget admitted =
                target.orElseThrow(() -> new AssertionError("warp-normal-point target refused: " + version));
        final byte[] drawBytes = bytes(artifact, WarpNormalPointElisionTarget.OWNER);
        final WarpNormalPointElisionTransformer transformer =
                new WarpNormalPointElisionTransformer(loader, artifact, drawBytes, admitted);
        assertNotNull(
                transformer.transform(null, loader, WarpNormalPointElisionTarget.OWNER, null, domain, drawBytes),
                version + " warp-draw: " + transformer.failure());
        assertEquals(1, transformer.matches(), version);
        assertEquals(1, transformer.sites(), version);
        assertNull(transformer.failure(), version);
    }

    private static URLClassLoader loader(final Path directory) throws Exception {
        final List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(directory, 2)) {
            for (final Path path :
                    files.filter(path -> path.toString().endsWith(".jar")).toList()) {
                urls.add(path.toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static byte[] bytes(final Path artifact, final String owner) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final var entry = jar.getJarEntry(owner + ".class");
            assertNotNull(entry, owner + " absent from " + artifact);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static ProtectionDomain domain(final Path artifact) throws Exception {
        return new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
    }

    private static Path referenceRoot() {
        final String configured = System.getProperty(
                "turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }
}
