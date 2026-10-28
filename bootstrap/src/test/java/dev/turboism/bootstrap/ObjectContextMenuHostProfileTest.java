package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ObjectContextMenuHostProfileTest {

    @Test
    void pinsIndependent52And53OwnersAndAppendCardinality() {
        final ObjectContextMenuHostProfile v52 = ObjectContextMenuHostProfile.forArtifact(
                        ReviewedHostArtifacts.CUBISM_5_2_03)
                .orElseThrow();
        final ObjectContextMenuHostProfile v53 = ObjectContextMenuHostProfile.forArtifact(
                        ReviewedHostArtifacts.CUBISM_5_3_02)
                .orElseThrow();
        final ObjectContextMenuHostProfile v5303 = ObjectContextMenuHostProfile.forArtifact(
                        ReviewedHostArtifacts.CUBISM_5_3_03)
                .orElseThrow();

        assertProfile(
                v52,
                "com/live2d/cubism/view/palette/parameter/aL",
                7,
                "com/live2d/cubism/view/palette/parts/R",
                21,
                23,
                List.of(3, 3, 2, 1));
        assertProfile(
                v53,
                "com/live2d/cubism/view/palette/parameter/aL",
                7,
                "com/live2d/cubism/view/palette/parts/T",
                22,
                22,
                List.of(1, 3, 1, 1));
        assertProfile(
                v5303,
                "com/live2d/cubism/view/palette/parameter/aM",
                10,
                "com/live2d/cubism/view/palette/parts/T",
                22,
                22,
                List.of(3, 3, 1, 1));
    }

    @Test
    void rejectsUnknownArtifacts() {
        assertTrue(ObjectContextMenuHostProfile.forArtifact(new HostArtifactDigest(1L, "0".repeat(64)))
                .isEmpty());
    }

    @Test
    void coversAllTwentyReviewedArtifactsConsistently() {
        final List<HostArtifactDigest> artifacts = ReviewedHostArtifacts.all();
        assertEquals(20, artifacts.size());
        for (final HostArtifactDigest artifact : artifacts) {
            final String version =
                    ReviewedHostArtifacts.cubismVersionOf(artifact).orElseThrow();
            final ObjectContextMenuHostProfile fromArtifact =
                    ObjectContextMenuHostProfile.forArtifact(artifact).orElseThrow();
            final ObjectContextMenuHostProfile fromVersion =
                    ObjectContextMenuHostProfile.forReviewedVersion(version).orElseThrow();
            assertEquals(fromArtifact, fromVersion, "Mismatch for version " + version);
        }
    }

    @Test
    void transformsRealHostBytecodeForEveryReviewedArtifact() throws Exception {
        final Path refRoot = Path.of("/opt/dev/projects/turboism/cubism-ref");
        Assumptions.assumeTrue(Files.isDirectory(refRoot), "cubism-ref directory not present");

        for (final HostArtifactDigest artifact : ReviewedHostArtifacts.all()) {
            final String version =
                    ReviewedHostArtifacts.cubismVersionOf(artifact).orElseThrow();
            final Path jarPath = refRoot.resolve(version).resolve("Live2D_Cubism.jar");
            Assumptions.assumeTrue(Files.isRegularFile(jarPath), "Missing jar for " + version);

            try (final URLClassLoader loader = new URLClassLoader(
                            new URL[] {jarPath.toUri().toURL()},
                            ObjectContextMenuHostProfileTest.class.getClassLoader());
                    final JarFile jarFile = new JarFile(jarPath.toFile())) {
                final ObjectContextMenuHostProfile profile =
                        ObjectContextMenuHostProfile.forReviewedVersion(version).orElseThrow();
                for (final VerifiedObjectContextMenuHookInstaller.Binding binding : profile.bindings()) {
                    final String owner = binding.operation().ownerInternalName();
                    final JarEntry entry = jarFile.getJarEntry(owner + ".class");
                    assertNotNull(entry, "Missing class entry: " + owner + " in " + version);
                    final byte[] classBytes;
                    try (final InputStream in = jarFile.getInputStream(entry)) {
                        classBytes = in.readAllBytes();
                    }

                    final ClassFileTransformer transformer = binding.transformer(loader);
                    final byte[] transformedBytes = transformer.transform(null, loader, owner, null, null, classBytes);
                    assertNotNull(
                            transformedBytes,
                            "Transform failed for " + owner + " in " + version + " (binding " + binding.location()
                                    + ")");
                }
            }
        }
    }

    private static void assertProfile(
            final ObjectContextMenuHostProfile profile,
            final String parameterOwner,
            final int parameterAppends,
            final String partsOwner,
            final int partsAppends,
            final int workspaceAppends,
            final List<Integer> injectionPoints) {
        final List<VerifiedObjectContextMenuHookInstaller.Binding> bindings = profile.bindings();
        assertEquals(
                List.of(Location.DEFORMER_TAB, Location.PARAMETER_TAB, Location.PART_TAB, Location.WORKSPACE_OBJECT),
                bindings.stream()
                        .map(VerifiedObjectContextMenuHookInstaller.Binding::location)
                        .toList());
        assertEquals(11, bindings.get(0).expectedAppendPoints());
        assertEquals(parameterOwner, bindings.get(1).operation().ownerInternalName());
        assertEquals(parameterAppends, bindings.get(1).expectedAppendPoints());
        assertEquals("c", bindings.get(1).append().memberName());
        assertEquals(partsOwner, bindings.get(2).operation().ownerInternalName());
        assertEquals(partsAppends, bindings.get(2).expectedAppendPoints());
        assertEquals(workspaceAppends, bindings.get(3).expectedAppendPoints());
        assertEquals(
                injectionPoints,
                bindings.stream()
                        .map(VerifiedObjectContextMenuHookInstaller.Binding::injectionPoint)
                        .toList());
        assertEquals(
                List.of(List.of(2, 3), List.of(2), List.of(5), List.of(1)),
                bindings.stream()
                        .map(binding -> java.util.Arrays.stream(binding.sourceLocals())
                                .boxed()
                                .toList())
                        .toList());
    }
}
