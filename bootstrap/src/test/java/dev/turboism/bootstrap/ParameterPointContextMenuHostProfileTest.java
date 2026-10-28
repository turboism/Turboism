package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.ui.context.ParameterPointContextMenuNativeMethodTransformer;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ParameterPointContextMenuHostProfileTest {

    @Test
    void selectsIndependentExactOwnersForEveryReviewedArtifact() {
        final ParameterPointContextMenuHostProfile v52 = ParameterPointContextMenuHostProfile.forArtifact(
                        ReviewedHostArtifacts.CUBISM_5_2_03)
                .orElseThrow();
        final ParameterPointContextMenuHostProfile v5302 = ParameterPointContextMenuHostProfile.forArtifact(
                        ReviewedHostArtifacts.CUBISM_5_3_02)
                .orElseThrow();
        final ParameterPointContextMenuHostProfile v5303 = ParameterPointContextMenuHostProfile.forArtifact(
                        ReviewedHostArtifacts.CUBISM_5_3_03)
                .orElseThrow();

        assertProfile(v52, "Q");
        assertProfile(v5302, "ab");
        assertProfile(v5303, "ac");
    }

    @Test
    void rejectsUnknownArtifacts() {
        assertTrue(ParameterPointContextMenuHostProfile.forArtifact(new HostArtifactDigest(1L, "0".repeat(64)))
                .isEmpty());
    }

    @Test
    void coversAllTwentyReviewedArtifactsConsistently() {
        final List<HostArtifactDigest> artifacts = ReviewedHostArtifacts.all();
        assertEquals(20, artifacts.size());
        for (final HostArtifactDigest artifact : artifacts) {
            final String version =
                    ReviewedHostArtifacts.cubismVersionOf(artifact).orElseThrow();
            final ParameterPointContextMenuHostProfile fromArtifact =
                    ParameterPointContextMenuHostProfile.forArtifact(artifact).orElseThrow();
            final ParameterPointContextMenuHostProfile fromVersion =
                    ParameterPointContextMenuHostProfile.forReviewedVersion(version)
                            .orElseThrow();
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
                            ParameterPointContextMenuHostProfileTest.class.getClassLoader());
                    final JarFile jarFile = new JarFile(jarPath.toFile())) {
                final ParameterPointContextMenuHostProfile profile =
                        ParameterPointContextMenuHostProfile.forReviewedVersion(version)
                                .orElseThrow();
                final String owner = profile.owner();
                final JarEntry entry = jarFile.getJarEntry(owner + ".class");
                assertNotNull(entry, "Missing class entry: " + owner + " in " + version);
                final byte[] classBytes;
                try (final InputStream in = jarFile.getInputStream(entry)) {
                    classBytes = in.readAllBytes();
                }

                final ParameterPointContextMenuNativeMethodTransformer transformerA =
                        new ParameterPointContextMenuNativeMethodTransformer(
                                owner, "a", "(" + profile.contextDescriptor() + "II)V", "i", loader);
                final byte[] transformedA = transformerA.transform(null, loader, owner, null, null, classBytes);
                assertNotNull(transformedA, "Transform 'a' failed for " + owner + " in " + version);

                final ParameterPointContextMenuNativeMethodTransformer transformerB =
                        new ParameterPointContextMenuNativeMethodTransformer(
                                owner, "b", "(" + profile.contextDescriptor() + "II)V", "h", loader);
                final byte[] transformedB = transformerB.transform(null, loader, owner, null, null, classBytes);
                assertNotNull(transformedB, "Transform 'b' failed for " + owner + " in " + version);
            }
        }
    }

    private static void assertProfile(final ParameterPointContextMenuHostProfile profile, final String ownerSuffix) {
        final String owner = "com/live2d/cubism/view/palette/parameter/ui/" + ownerSuffix;
        assertEquals(owner, profile.owner());
        assertEquals("L" + owner + "$b;", profile.contextDescriptor());
    }
}
