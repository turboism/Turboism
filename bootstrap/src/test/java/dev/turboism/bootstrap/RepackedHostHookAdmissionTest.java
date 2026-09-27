package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.geometry.MatrixScratchTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.CubismEditorReleaseDetector;
import dev.turboism.mapping.verification.CubismHostIdentity;
import dev.turboism.mapping.verification.ReviewedCubismReleases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Offline admission evidence from real class bytes; never launches an Editor. */
class RepackedHostHookAdmissionTest {
    private static final RuntimeStartupConfig POLICY = new RuntimeStartupConfig(false, false, false, false);
    @TempDir Path temporary;

    @Test
    void repackingAndUnknownDeclarationRetainMatchingHooksWhileChangedTargetsAreIsolated() throws Exception {
        final String supplied = System.getProperty("turboism.test.cubismEditorJar");
        assumeTrue(supplied != null, "no turboism.test.cubismEditorJar evidence supplied");
        final Path original = Path.of(supplied);
        assertTrue(Files.isRegularFile(original), "supplied real-host sample must exist");
        final CubismHostIdentity identity = CubismEditorReleaseDetector.probe(original)
            .identity().orElseThrow();
        assertTrue(ReviewedCubismReleases.isReviewed(identity.version(), identity.build()));
        final Map<String, Boolean> expected = admissions(original);
        assertTrue(expected.get("matrix"));
        assertTrue(expected.get("warp"));
        assertTrue(expected.get("mesh"));

        final Path repacked = copy(original, "repacked.jar", Map.of());
        final CubismHostIdentity repackedIdentity = CubismEditorReleaseDetector.probe(repacked)
            .identity().orElseThrow();
        assertEquals(identity.version(), repackedIdentity.version());
        assertEquals(identity.build(), repackedIdentity.build());
        assertNotEquals(identity.artifact(), repackedIdentity.artifact());
        assertEquals(expected, admissions(repacked), "resource-only repacking preserves supported hooks");

        final Path unknown = copy(original, "synthetic-unknown.jar", Map.of(
            identity.declarationClass() + ".class", bytes -> unknownDeclaration(bytes, identity)));
        final CubismHostIdentity unknownIdentity = CubismEditorReleaseDetector.probe(unknown)
            .identity().orElseThrow();
        assertEquals("5.9.99", unknownIdentity.version());
        assertEquals(509990001, unknownIdentity.build());
        final Map<String, Boolean> unknownAdmission = admissions(unknown);
        expected.forEach((hook, enabled) -> {
            if (enabled) assertTrue(unknownAdmission.get(hook),
                "unchanged target contract should bind for synthetic unknown declaration: " + hook);
        });

        final Path altered = copy(original, "changed-matrix.jar", Map.of(
            MatrixScratchTransformer.OWNER + ".class", RepackedHostHookAdmissionTest::alterClass));
        assertFalse(VerifiedMatrixScratchInstaller.admitted(altered, POLICY, true, 17));
        assertTrue(WarpAltMirrorHookContributor.resolveProfile(altered) instanceof ReviewedHostContract.Bound<?>,
            "changing matrix targets must not deny an independent mirror contract");
    }

    private static Map<String, Boolean> admissions(final Path artifact) {
        final Map<String, Boolean> admitted = new LinkedHashMap<>();
        admitted.put("matrix", VerifiedMatrixScratchInstaller.admitted(artifact, POLICY, true, 17));
        admitted.put("uniform", VerifiedUniformLocationInstaller.admitted(artifact, POLICY, true, 17));
        admitted.put("model-update", VerifiedModelUpdateSkipInstaller.admitted(artifact, POLICY, true, 17));
        admitted.put("incremental", VerifiedIncrementalUpdateInstaller.admitted(artifact, POLICY, true, 17));
        admitted.put("texture-upload", VerifiedTextureUploadPreparationInstaller.admitted(artifact, POLICY, true, 17));
        admitted.put("warp-position", VerifiedWarpPositionProjectionInstaller.admitted(artifact, POLICY, true, 17));
        admitted.put("float-array", VerifiedFloatArrayParseCacheInstaller.admitted(artifact, POLICY, true));
        admitted.put("image-archive", VerifiedImageArchiveReuseInstaller.admitted(artifact, POLICY, true));
        admitted.put("warp", WarpAltMirrorHookContributor.resolveProfile(artifact) instanceof ReviewedHostContract.Bound<?>);
        admitted.put("mesh", MeshMirrorHookContributor.resolveProfile(artifact) instanceof ReviewedHostContract.Bound<?>);
        return Map.copyOf(admitted);
    }

    private Path copy(
        final Path original, final String filename, final Map<String, UnaryOperator<byte[]>> replacements
    ) throws Exception {
        final Path target = temporary.resolve(filename);
        try (JarFile source = new JarFile(original.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            final var entries = source.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                final String name = entry.getName();
                final String upper = name.toUpperCase(Locale.ROOT);
                // Declaration edits invalidate signatures; the synthetic copy is not an official artifact.
                if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                    || upper.endsWith(".DSA") || upper.endsWith(".EC"))) continue;
                out.putNextEntry(new JarEntry(name));
                if (!entry.isDirectory()) {
                    try (var input = source.getInputStream(entry)) {
                        if (replacements.containsKey(name)) {
                            out.write(replacements.get(name).apply(input.readAllBytes()));
                        } else {
                            input.transferTo(out);
                        }
                    }
                }
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("META-INF/turboism-repack-test.txt"));
            out.write(new byte[] {1});
            out.closeEntry();
        }
        return target;
    }

    private static byte[] unknownDeclaration(final byte[] bytes, final CubismHostIdentity identity) {
        // Rewrite only the two equal-width constant-pool entries. Bootstrap tests
        // keep ASM private to :runtime, as required by the repository dependency gate.
        final byte[] patched = bytes.clone();
        final ByteBuffer pool = ByteBuffer.wrap(patched);
        assertEquals(0xCAFEBABE, pool.getInt());
        pool.position(8);
        final int count = Short.toUnsignedInt(pool.getShort());
        int versions = 0;
        int builds = 0;
        for (int index = 1; index < count; index++) {
            switch (Byte.toUnsignedInt(pool.get())) {
                case 1 -> {
                    final int length = Short.toUnsignedInt(pool.getShort());
                    final int offset = pool.position();
                    if (identity.version().equals(new String(patched, offset, length, StandardCharsets.UTF_8))) {
                        final byte[] replacement = "5.9.99".getBytes(StandardCharsets.UTF_8);
                        assertEquals(length, replacement.length);
                        System.arraycopy(replacement, 0, patched, offset, length);
                        versions++;
                    }
                    pool.position(offset + length);
                }
                case 3 -> {
                    final int offset = pool.position();
                    if (pool.getInt() == identity.build()) {
                        pool.putInt(offset, 509990001);
                        builds++;
                    }
                }
                case 4, 9, 10, 11, 12, 17, 18 -> pool.position(pool.position() + 4);
                case 5, 6 -> {
                    pool.position(pool.position() + 8);
                    index++;
                }
                case 7, 8, 16, 19, 20 -> pool.position(pool.position() + 2);
                case 15 -> pool.position(pool.position() + 3);
                default -> throw new AssertionError("unrecognized constant-pool tag");
            }
        }
        assertEquals(1, versions, "the fixture must rewrite the actual version constant");
        assertEquals(1, builds, "the fixture must rewrite the actual build constant");
        return patched;
    }

    private static byte[] alterClass(final byte[] bytes) {
        final byte[] corrupted = bytes.clone();
        corrupted[corrupted.length - 1] ^= 1;
        return corrupted;
    }
}
