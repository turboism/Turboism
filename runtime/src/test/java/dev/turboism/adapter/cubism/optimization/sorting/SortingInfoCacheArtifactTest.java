package dev.turboism.adapter.cubism.optimization.sorting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Exact-artifact admission for the skipped-frame sorting-info guard and the
 * allocation-free mesh-container lookup across every reviewed Cubism version.
 *
 * <p>Both transformers compare the observed method shape against the reference
 * bytes they receive. This test feeds each official artifact's own bytes, so it
 * proves the rewrites are accepted for the whole supported matrix rather than
 * only for the reviewed 5.3 host.</p>
 */
class SortingInfoCacheArtifactTest {

    @Test
    void everyConfiguredReviewedArtifactRewritesSortingAndMeshLookup() throws Exception {
        final Path root = referenceRoot();
        int rewritten = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = root.resolve(version).resolve("Live2D_Cubism.jar");
            if (!Files.isRegularFile(artifact)) continue;
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final SortingInfoCacheTarget target = SortingInfoCacheTarget.of(digest)
                    .orElseThrow(() -> new AssertionError("reviewed artifact refused: " + version));
            try (URLClassLoader loader = loader(artifact.getParent())) {
                final byte[] sorting = bytes(artifact, SortingInfoCacheTarget.SORTING_OWNER);
                final SortingInfoCacheTransformer sortingTransformer =
                        new SortingInfoCacheTransformer(loader, artifact, sorting, target);
                final ProtectionDomain sortingDomain = domain(artifact);
                final byte[] rewrittenSorting = sortingTransformer.transform(
                        null, loader, SortingInfoCacheTarget.SORTING_OWNER, null, sortingDomain, sorting);
                assertNotNull(rewrittenSorting, version + ": " + sortingTransformer.failure());
                assertEquals(1, sortingTransformer.matches(), version);
                assertNull(sortingTransformer.failure(), version);

                final byte[] renderer = bytes(artifact, SortingInfoCacheTarget.MESH_RENDERER_OWNER);
                final MeshContainerLookupTransformer containerTransformer =
                        new MeshContainerLookupTransformer(loader, artifact, renderer);
                final byte[] rewrittenRenderer = containerTransformer.transform(
                        null, loader, SortingInfoCacheTarget.MESH_RENDERER_OWNER, null, domain(artifact), renderer);
                assertNotNull(rewrittenRenderer, version + ": " + containerTransformer.failure());
                assertEquals(1, containerTransformer.matches(), version);
                assertNull(containerTransformer.failure(), version);
            }
            rewritten++;
            System.out.println("SORTING_INFO artifact transform PASS version=" + version);
        }
        assertTrue(rewritten > 0, "no reviewed exact artifact found under " + root);
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
