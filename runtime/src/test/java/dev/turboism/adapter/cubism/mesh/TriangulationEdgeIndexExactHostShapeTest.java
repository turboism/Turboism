package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void localBuilderWeavePreservesOtherMethodsAndRejectsChangedOrientation() throws Exception {
        for (final Path jar : reviewedArtifacts(legacyEvidence()).values()) {
            final byte[] original = new TriangulationEdgeIndexPatcher().patch(readEntry(jar));
            final byte[] patched = TriangleListEdgeBuilderPatcher.patch(original);
            assertEquals(withoutBuilderFingerprint(original), withoutBuilderFingerprint(patched),
                    "the local-builder weave must not change any other runtime member");
            final List<String> calls = new java.util.ArrayList<>();
            new org.objectweb.asm.ClassReader(patched).accept(new org.objectweb.asm.ClassVisitor(
                    org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(final int access,
                        final String name, final String desc, final String signature, final String[] exceptions) {
                    if (!name.equals("b") || !desc.endsWith("/k;")) return null;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override public void visitMethodInsn(final int opcode, final String owner,
                                final String method, final String descriptor, final boolean itf) {
                            calls.add(owner + "." + method + descriptor);
                        }
                    };
                }
            }, 0);
            final String prefix = "com/live2d/graphics3d/editableMesh/triangulation/";
            assertEquals(List.of(prefix + "l.d()L" + prefix + "j;",
                    prefix + "l.e()L" + prefix + "j;", prefix + "l.f()L" + prefix + "j;"),
                    calls.stream().filter(call -> call.startsWith(prefix + "l.")).toList(),
                    "all three original edge getters retain their ordering");
            assertEquals(3, calls.stream().filter(call -> call.equals(prefix + "k.a(L"
                    + prefix + "j;Z)Z")).count(), "native membership fallback remains at every site");
            assertEquals(3, calls.stream().filter(call -> call.startsWith(
                    "dev/turboism/adapter/cubism/mesh/TriangulationBuilderEdges.seen(")).count());
            assertEquals(3, calls.stream().filter(call -> call.equals(prefix + "k.a(L"
                    + prefix + "j;)Z")).count(), "native appends retain the original branches");
            assertEquals(2, calls.stream().filter(call -> call.startsWith(
                    "dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge.leave(")).count());

            final var writer = new org.objectweb.asm.ClassWriter(0);
            new org.objectweb.asm.ClassReader(original).accept(new org.objectweb.asm.ClassVisitor(
                    org.objectweb.asm.Opcodes.ASM9, writer) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(final int access,
                        final String name, final String desc, final String signature, final String[] exceptions) {
                    final var visitor = super.visitMethod(access, name, desc, signature, exceptions);
                    if (!name.equals("b") || !desc.endsWith("/k;")) return visitor;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, visitor) {
                        @Override public void visitInsn(final int opcode) {
                            super.visitInsn(opcode == org.objectweb.asm.Opcodes.ICONST_0
                                    ? org.objectweb.asm.Opcodes.ICONST_1 : opcode);
                        }
                    };
                }
            }, 0);
            assertThrows(IllegalArgumentException.class,
                    () -> TriangleListEdgeBuilderPatcher.patch(writer.toByteArray()));
            assertThrows(IllegalArgumentException.class, () -> TriangleListEdgeBuilderPatcher.patch(patched),
                    "an already woven builder must not be woven again");
        }
    }

    private static String withoutBuilderFingerprint(final byte[] bytes) {
        final var writer = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(
                org.objectweb.asm.Opcodes.ASM9, writer) {
            @Override public org.objectweb.asm.MethodVisitor visitMethod(final int access, final String name,
                    final String desc, final String signature, final String[] exceptions) {
                return name.equals("b") && desc.endsWith("/k;") ? null
                        : super.visitMethod(access, name, desc, signature, exceptions);
            }
        }, 0);
        return TriangulationDefinitionFingerprint.runtimeOf(writer.toByteArray());
    }

    @Test
    void builderDependencyContractRejectsFieldAndBuilderChangesButAllowsQueryRecording() throws Exception {
        final byte[] original = TriangleListEdgeBuilderPatcher.patch(new TriangulationEdgeIndexPatcher().patch(
                readEntry(legacyEvidence().resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar"))));
        final String expected = TriangleListEdgeBuilderPatcher.dependencyFingerprint(original);
        for (final String change : List.of("query", "builder", "field")) {
            final var writer = new org.objectweb.asm.ClassWriter(0);
            new org.objectweb.asm.ClassReader(original).accept(new org.objectweb.asm.ClassVisitor(
                    org.objectweb.asm.Opcodes.ASM9, writer) {
                @Override public org.objectweb.asm.FieldVisitor visitField(final int access, final String name,
                        final String descriptor, final String signature, final Object value) {
                    return super.visitField(change.equals("field") && name.equals("b")
                            ? access ^ org.objectweb.asm.Opcodes.ACC_FINAL : access,
                            name, descriptor, signature, value);
                }
                @Override public org.objectweb.asm.MethodVisitor visitMethod(final int access, final String name,
                        final String descriptor, final String signature, final String[] exceptions) {
                    final var visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    final boolean mutate = change.equals("builder") && name.equals("b") && descriptor.endsWith("/k;")
                            || change.equals("query") && name.equals("a") && descriptor.endsWith("Ljava/util/List;");
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, visitor) {
                        @Override public void visitCode() {
                            super.visitCode();
                            if (mutate) super.visitInsn(org.objectweb.asm.Opcodes.NOP);
                        }
                    };
                }
            }, 0);
            final byte[] changed = writer.toByteArray();
            assertNotEquals(TriangulationDefinitionFingerprint.runtimeOf(original),
                    TriangulationDefinitionFingerprint.runtimeOf(changed), "negative control really changed the class");
            if (change.equals("query")) {
                assertEquals(expected, LazyTriangulationEdgePreparation.dependencyFingerprint(changed),
                        "query recording is outside the local-builder dependency contract");
            } else {
                assertNotEquals(expected, LazyTriangulationEdgePreparation.dependencyFingerprint(changed),
                        "all relevant field/builder runtime instructions remain bound");
            }
        }
    }

    @Test
    void bothCallerOptimizationsMatchReviewedCombinedBytes() throws Exception {
        final Map<String, String> expected = Map.of(
                "Cubism-5.2 (5.2.03 family)", "d0fac0cd2c2092db163db7b78bffd011713f2bef17279b08ab088af4e7d27d92",
                "Cubism-5.3.02 (5.3.x family)", "40d0754026a7a2fb7c491e95144b9d8a1a605d7aee44579cb20bb2363962f8e6");
        for (final var artifact : reviewedArtifacts(legacyEvidence()).entrySet()) {
            final byte[] bytes = readEntry(artifact.getValue(),
                    TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME + ".class");
            final java.util.List<String> receipts = new java.util.ArrayList<>();
            final var transformer = new TriangulationEdgeIndexTransformer(receipts::add);
            final byte[] patched = transformer.transform(null,
                    TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME, null, null, bytes);
            assertNotNull(patched);
            assertEquals(TriangulationEdgeIndexTransformer.Outcome.PATCHED, transformer.membershipOutcome());
            assertEquals(TriangulationEdgeIndexTransformer.Outcome.PATCHED, transformer.freshEdgeOutcome());
            assertEquals(expected.get(artifact.getKey()), TriangulationEdgeIndexTransformer.sha256(patched));
            assertEquals(List.of("TRIANGULATION_FRESH_EDGE_PATCHED inputSha256="
                    + TriangulationEdgeIndexTransformer.sha256(bytes) + " outputSha256="
                    + expected.get(artifact.getKey()), "TRIANGULATION_MEMBERSHIP_PATCHED inputSha256="
                    + TriangulationEdgeIndexTransformer.sha256(bytes) + " outputSha256="
                    + expected.get(artifact.getKey())), receipts);
            final var brokenReceipt = new TriangulationEdgeIndexTransformer(code -> {
                throw new IllegalStateException("sink unavailable");
            });
            assertEquals(expected.get(artifact.getKey()), TriangulationEdgeIndexTransformer.sha256(
                    brokenReceipt.transform(null, TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME,
                            null, null, bytes)));
        }
    }

    private static byte[] readEntry(final Path jar, final String entry) throws IOException {
        try (JarFile file = new JarFile(jar.toFile());
                InputStream stream = file.getInputStream(file.getEntry(entry))) {
            return stream.readAllBytes();
        }
    }

    @Test
    void lazyAdmissionAcceptsOnlyTheReviewedHashComposition() throws Exception {
        byte[] original = readEntry(legacyEvidence().resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar"),
                MeshTriangulationHashTransformer.TARGET_INTERNAL_NAME + ".class");
        assertEquals(MeshTriangulationHashTransformer.REVIEWED_CLASS_SHA256,
                MeshTriangulationHashTransformer.sha256(original));
        byte[] patched = new MeshTriangulationHashTransformer().transform(null,
                MeshTriangulationHashTransformer.TARGET_INTERNAL_NAME, null, null, original);
        assertNotNull(patched);
        String pristine = TriangulationDefinitionFingerprint.runtimeOf(original);
        assertNotEquals(pristine, TriangulationDefinitionFingerprint.runtimeOf(patched));
        assertEquals(pristine, LazyTriangulationEdgePreparation.dependencyFingerprint(original));
        assertEquals(pristine, LazyTriangulationEdgePreparation.dependencyFingerprint(patched));
        assertEquals(withoutHashFingerprint(original), withoutHashFingerprint(patched),
                "the existing hash patch must preserve every other runtime member");
        for (String change : List.of("getter", "hash", "field")) {
            byte[] tampered = tamperHashComposition(patched, change);
            String observed = TriangulationDefinitionFingerprint.runtimeOf(tampered);
            assertNotEquals(TriangulationDefinitionFingerprint.runtimeOf(patched), observed, change);
            assertEquals(observed, LazyTriangulationEdgePreparation.dependencyFingerprint(tampered),
                    change + " must not be normalized to an admitted definition");
            assertNotEquals(pristine, LazyTriangulationEdgePreparation.dependencyFingerprint(tampered), change);
        }
    }

    private static String withoutHashFingerprint(byte[] bytes) {
        var writer = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(
                org.objectweb.asm.Opcodes.ASM9, writer) {
            @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                    String descriptor, String signature, String[] exceptions) {
                return name.equals("hashCode") && descriptor.equals("()I") ? null
                        : super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        return TriangulationDefinitionFingerprint.runtimeOf(writer.toByteArray());
    }

    private static byte[] tamperHashComposition(byte[] bytes, String change) {
        var writer = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(
                org.objectweb.asm.Opcodes.ASM9, writer) {
            @Override public org.objectweb.asm.FieldVisitor visitField(int access, String name,
                    String descriptor, String signature, Object value) {
                if (change.equals("field") && name.equals("a")) access ^= org.objectweb.asm.Opcodes.ACC_FINAL;
                return super.visitField(access, name, descriptor, signature, value);
            }
            @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                    String descriptor, String signature, String[] exceptions) {
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String type) {
                        super.visitFieldInsn(opcode, owner,
                                change.equals("getter") && name.equals("a") && field.equals("a") ? "b" : field, type);
                    }
                    @Override public void visitIntInsn(int opcode, int operand) {
                        super.visitIntInsn(opcode, change.equals("hash") && name.equals("hashCode")
                                && opcode == org.objectweb.asm.Opcodes.BIPUSH && operand == 31 ? 30 : operand);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
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
