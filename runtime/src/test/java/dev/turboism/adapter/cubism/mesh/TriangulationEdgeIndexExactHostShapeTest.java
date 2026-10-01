package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Exact-byte contract for the triangulation edge-index weave against the licensed reviewed host
 * artifacts. Runs only in the {@code legacyCubismEvidenceTest} lane, which supplies the licensed
 * Cubism reference directory through {@code TURBOISM_LEGACY_CUBISM_REF} or the repository-sibling
 * {@code turboism-legacy/cubism-ref} checkout.
 *
 * <p>For each reviewed version family the test verifies the pinned class digest is what the
 * transformer admits and that the patcher accepts the real class bytes, producing a patched class
 * that still parses.</p>
 */
final class TriangulationEdgeIndexExactHostShapeTest {

    private static final String ENTRY =
            "com/live2d/graphics3d/editableMesh/triangulation/TriangleList.class";

    /** Reviewed artifact paths: label → jar under the legacy evidence root. */
    private static Map<String, Path> reviewedArtifacts(final Path evidence) {
        final Map<String, Path> artifacts = new LinkedHashMap<>();
        artifacts.put("Cubism-5.2 (5.2.03 family)",
                evidence.resolve("Cubism-5.2/jars/Live2D_Cubism.jar"));
        artifacts.put("Cubism-5.3.02 (5.3.x family)",
                evidence.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar"));
        return artifacts;
    }

    @Test
    void bothReviewedFamiliesMatchThePinnedDigestsAndPatchCleanly() throws Exception {
        final Path evidence = legacyEvidence();
        final Map<String, String> familyDigests = Map.of(
                "Cubism-5.2 (5.2.03 family)",
                        TriangulationEdgeIndexTransformer.REVIEWED_CLASS_SHA256_5203,
                "Cubism-5.3.02 (5.3.x family)",
                        TriangulationEdgeIndexTransformer.REVIEWED_CLASS_SHA256_53X);
        for (final Map.Entry<String, Path> artifact : reviewedArtifacts(evidence).entrySet()) {
            final byte[] bytes = readEntry(artifact.getValue());
            assertEquals(familyDigests.get(artifact.getKey()),
                    TriangulationEdgeIndexTransformer.sha256(bytes),
                    artifact.getKey() + " must match the pinned digest");

            final TriangulationEdgeIndexTransformer transformer =
                    new TriangulationEdgeIndexTransformer();
            final byte[] patched = transformer.transform(
                    null,
                    TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME,
                    null,
                    null,
                    bytes);
            assertNotNull(patched,
                    artifact.getKey() + " must pass the shape gate and be patched");
            assertEquals(TriangulationEdgeIndexTransformer.Outcome.PATCHED, transformer.outcome());

            // The patched class must still be a parseable class whose woven entry points exist.
            assertWovenCallSites(patched);
        }
    }

    /** Asserts the patched bytes carry all five bridge call sites. */
    private static void assertWovenCallSites(final byte[] patched) {
        final org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(patched);
        final int[] calls = new int[5]; // add, remove, clear, tryQuery, contains
        reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(
                    final int access, final String name, final String descriptor,
                    final String signature, final String[] exceptions) {
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                            final int op, final String owner, final String n,
                            final String d, final boolean itf) {
                        if (TriangulationEdgeIndexPatcher.BRIDGE.equals(owner)) {
                            switch (n) {
                                case "add" -> calls[0]++;
                                case "remove" -> calls[1]++;
                                case "clear" -> calls[2]++;
                                case "tryQuery" -> calls[3]++;
                                case "contains" -> calls[4]++;
                                default -> throw new AssertionError("unexpected bridge call: " + n);
                            }
                        }
                    }
                };
            }
        }, 0);
        assertEquals(List.of(1, 1, 1, 1, 1), List.of(calls[0], calls[1], calls[2], calls[3], calls[4]),
                "each bridge call site must appear exactly once");
    }

    private static byte[] readEntry(final Path jar) throws IOException {
        return readEntry(jar, ENTRY);
    }

    @Test
    void bothMembershipCallersMatchPrototypeBytes() throws Exception {
        final Map<String, String> expected = Map.of(
                "Cubism-5.2 (5.2.03 family)", "d38c2fbe690cba705e223b7d43f0aaabcee0679c02f9996e5e4ab5ff49492be7",
                "Cubism-5.3.02 (5.3.x family)", "32e143cfcd433be586c74d98ca24c47e2829b9807ac7d61e1ab4f35f59ecc544");
        for (final var artifact : reviewedArtifacts(legacyEvidence()).entrySet()) {
            final byte[] bytes = readEntry(artifact.getValue(),
                    TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME + ".class");
            final var transformer = new TriangulationEdgeIndexTransformer();
            final byte[] patched = transformer.transform(null,
                    TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME, null, null, bytes);
            assertNotNull(patched);
            assertEquals(TriangulationEdgeIndexTransformer.Outcome.PATCHED, transformer.membershipOutcome());
            assertEquals(expected.get(artifact.getKey()), TriangulationEdgeIndexTransformer.sha256(patched));
        }
    }

    private static byte[] readEntry(final Path jar, final String entry) throws IOException {
        try (JarFile file = new JarFile(jar.toFile());
                InputStream stream = file.getInputStream(file.getEntry(entry))) {
            return stream.readAllBytes();
        }
    }

    private static Path legacyEvidence() {
        final String configured = System.getenv("TURBOISM_LEGACY_CUBISM_REF");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        while (current != null) {
            final Path candidate = current.resolveSibling("turboism-legacy/cubism-ref");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("legacy Cubism evidence directory is unavailable");
    }
}
