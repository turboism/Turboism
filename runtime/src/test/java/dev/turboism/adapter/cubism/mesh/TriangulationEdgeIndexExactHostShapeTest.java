package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Exact-shape contract for the triangulation edge-index weave against the licensed reviewed host
 * artifacts. Runs only in the {@code legacyCubismEvidenceTest} lane, which supplies the licensed
 * Cubism reference directory through {@code TURBOISM_LEGACY_CUBISM_REF} or the repository-sibling
 * {@code turboism-legacy/cubism-ref} checkout.
 *
 * <p>For each reviewed version family the test verifies the structural contract the weave
 * requires, that the patcher accepts the real class bytes, and that the patched class keeps every
 * other runtime member. Admission is decided by live shape, so no test pins a byte preimage.</p>
 */
final class TriangulationEdgeIndexExactHostShapeTest {

    private static final String ENTRY = "com/live2d/graphics3d/editableMesh/triangulation/TriangleList.class";
    private static final String MATH_52 = "com.live2d.util.L";
    private static final String MATH_5051 = "com.live2d.util.K";
    /** Sibling runtime that supplies the Kotlin assertions and intrinsics links. */
    private static final String KOTLIN_JAR = "kotlin-stdlib-1.7.21.jar";

    private static final String MATH_FIELD = "a";
    private static final String THRESHOLD_FIELD = "i";
    private static final String THRESHOLD_METHOD = "f";
    /** Debug-gate probe the membership fusion keeps in front of every fused site. */
    private static final String DEBUG_PROBE_CALL = "com/live2d/graphics3d/editableMesh/triangulation/c$a.b()Z";

    /** Reviewed artifact paths: label → jar under the legacy evidence root. */
    private static Map<String, Path> reviewedArtifacts(final Path evidence) {
        final Map<String, Path> artifacts = new LinkedHashMap<>();
        artifacts.put("Cubism-5.2 (5.2.03 family)", evidence.resolve("Cubism-5.2/jars/Live2D_Cubism.jar"));
        artifacts.put("Cubism-5.3.02 (5.3.x family)", evidence.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar"));
        return artifacts;
    }

    @Test
    void bothReviewedFamiliesPassTheShapeGateAndPatchCleanly() throws Exception {
        assumeTrue(
                legacyEvidence() != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");

        final Path evidence = legacyEvidence();
        for (final Map.Entry<String, Path> artifact :
                reviewedArtifacts(evidence).entrySet()) {
            final byte[] bytes = readEntry(artifact.getValue());

            final TriangulationEdgeIndexTransformer transformer = new TriangulationEdgeIndexTransformer();
            final byte[] patched = transformer.transform(
                    null, TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME, null, null, bytes);
            assertNotNull(patched, artifact.getKey() + " must pass the shape gate and be patched");
            assertEquals(TriangulationEdgeIndexTransformer.Outcome.PATCHED, transformer.outcome());

            // The patched class must still be a parseable class whose woven entry points exist.
            assertWovenCallSites(patched);
        }
    }

    /** Asserts the patched bytes carry all five bridge call sites. */
    private static void assertWovenCallSites(final byte[] patched) {
        final ClassReader reader = new ClassReader(patched);
        final int[] calls = new int[5]; // add, remove, clear, tryQuery, contains
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    final int op,
                                    final String owner,
                                    final String n,
                                    final String d,
                                    final boolean itf) {
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
                },
                0);
        assertEquals(
                List.of(1, 1, 1, 1, 1),
                List.of(calls[0], calls[1], calls[2], calls[3], calls[4]),
                "each bridge call site must appear exactly once");
    }

    private static byte[] readEntry(final Path jar) throws IOException {
        return readEntry(jar, ENTRY);
    }

    @Test
    void localBuilderWeavePreservesOtherMethodsAndRejectsChangedOrientation() throws Exception {
        assumeTrue(
                legacyEvidence() != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");

        for (final Path jar : reviewedArtifacts(legacyEvidence()).values()) {
            final byte[] original = new TriangulationEdgeIndexPatcher().patch(readEntry(jar));
            final byte[] patched = TriangleListEdgeBuilderPatcher.patch(original);
            assertEquals(
                    structure(original, "b"),
                    structure(patched, "b"),
                    "the local-builder weave must not change any other runtime member");
            final List<String> calls = new ArrayList<>();
            new ClassReader(patched)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String desc,
                                        final String signature,
                                        final String[] exceptions) {
                                    if (!name.equals("b") || !desc.endsWith("/k;")) return null;
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int opcode,
                                                final String owner,
                                                final String method,
                                                final String descriptor,
                                                final boolean itf) {
                                            calls.add(owner + "." + method + descriptor);
                                        }
                                    };
                                }
                            },
                            0);
            final String prefix = "com/live2d/graphics3d/editableMesh/triangulation/";
            assertEquals(
                    List.of(
                            prefix + "l.d()L" + prefix + "j;",
                            prefix + "l.e()L" + prefix + "j;",
                            prefix + "l.f()L" + prefix + "j;"),
                    calls.stream()
                            .filter(call -> call.startsWith(prefix + "l."))
                            .toList(),
                    "all three original edge getters retain their ordering");
            assertEquals(
                    3,
                    calls.stream()
                            .filter(call -> call.equals(prefix + "k.a(L" + prefix + "j;Z)Z"))
                            .count(),
                    "native membership fallback remains at every site");
            assertEquals(
                    3,
                    calls.stream()
                            .filter(call ->
                                    call.startsWith("dev/turboism/adapter/cubism/mesh/TriangulationBuilderEdges.seen("))
                            .count());
            assertEquals(
                    3,
                    calls.stream()
                            .filter(call -> call.equals(prefix + "k.a(L" + prefix + "j;)Z"))
                            .count(),
                    "native appends retain the original branches");
            assertEquals(
                    2,
                    calls.stream()
                            .filter(call -> call.startsWith(
                                    "dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge.leave("))
                            .count());

            final var writer = new org.objectweb.asm.ClassWriter(0);
            new ClassReader(original)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9, writer) {
                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String desc,
                                        final String signature,
                                        final String[] exceptions) {
                                    final var visitor = super.visitMethod(access, name, desc, signature, exceptions);
                                    if (!name.equals("b") || !desc.endsWith("/k;")) return visitor;
                                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                                        @Override
                                        public void visitInsn(final int opcode) {
                                            super.visitInsn(opcode == Opcodes.ICONST_0 ? Opcodes.ICONST_1 : opcode);
                                        }
                                    };
                                }
                            },
                            0);
            assertThrows(
                    IllegalArgumentException.class, () -> TriangleListEdgeBuilderPatcher.patch(writer.toByteArray()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TriangleListEdgeBuilderPatcher.patch(patched),
                    "an already woven builder must not be woven again");
        }
    }

    @Test
    void angleGuardPreservesOtherMembersAndRejectsChangedBoundaryOrRepeatedWeave() throws Exception {
        assumeTrue(
                legacyEvidence() != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");

        for (final Path jar : reviewedArtifacts(legacyEvidence()).values()) {
            final byte[] original =
                    readEntry(jar, TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME + ".class");
            final byte[] patched = TriangulationAngleGuardPatcher.patch(original);
            assertEquals(structure(original, "d"), structure(patched, "d"));
            final List<String> calls = new ArrayList<>();
            new ClassReader(patched)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String descriptor,
                                        final String signature,
                                        final String[] exceptions) {
                                    if (!name.equals("d") || !descriptor.equals("()V")) return null;
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int opcode,
                                                final String owner,
                                                final String method,
                                                final String desc,
                                                final boolean itf) {
                                            calls.add(owner + "." + method + desc);
                                        }
                                    };
                                }
                            },
                            0);
            final String bridge = "dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge.";
            assertEquals(
                    1,
                    calls.stream()
                            .filter(call -> call.startsWith(bridge + "enter("))
                            .count());
            assertEquals(
                    2,
                    calls.stream()
                            .filter(call -> call.startsWith(bridge + "leave("))
                            .count());
            assertEquals(
                    1,
                    calls.stream()
                            .filter(call ->
                                    call.equals("dev/turboism/adapter/cubism/mesh/TriangulationAngleGuard.reject(FF)Z"))
                            .count());
            final String prefix = "com/live2d/graphics3d/editableMesh/triangulation/";
            assertEquals(
                    1,
                    calls.stream()
                            .filter(
                                    call -> call.equals(
                                            prefix
                                                    + "r.a(Lcom/live2d/graphics3d/type/GVector2;Lcom/live2d/graphics3d/type/GVector2;)F"))
                            .count(),
                    "the original angle call remains for cold and declined inputs");
            assertEquals(
                    List.of("getX", "getY", "getY", "getX", "getX", "getX", "getY", "getY"),
                    calls.stream()
                            .filter(call -> call.startsWith("com/live2d/graphics3d/type/GVector2.get"))
                            .map(call -> call.contains("getX") ? "getX" : "getY")
                            .toList());
            final var writer = new org.objectweb.asm.ClassWriter(0);
            new ClassReader(original)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9, writer) {
                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String descriptor,
                                        final String signature,
                                        final String[] exceptions) {
                                    final var output =
                                            super.visitMethod(access, name, descriptor, signature, exceptions);
                                    if (!name.equals("d") || !descriptor.equals("()V")) return output;
                                    return new MethodVisitor(Opcodes.ASM9, output) {
                                        @Override
                                        public void visitJumpInsn(final int opcode, final Label target) {
                                            super.visitJumpInsn(opcode == Opcodes.IFGT ? Opcodes.IFGE : opcode, target);
                                        }
                                    };
                                }
                            },
                            0);
            assertThrows(
                    IllegalArgumentException.class, () -> TriangulationAngleGuardPatcher.patch(writer.toByteArray()));
            assertThrows(IllegalArgumentException.class, () -> TriangulationAngleGuardPatcher.patch(patched));
        }
    }

    @Test
    void reviewedMathSingletonNameFollowsTheAdmittedVersion() {
        assertEquals(MATH_5051, LazyTriangulationEdgePreparation.mathSingleton("5.0.00"));
        assertEquals(MATH_5051, LazyTriangulationEdgePreparation.mathSingleton("5.1.03"));
        assertEquals(MATH_52, LazyTriangulationEdgePreparation.mathSingleton("5.2.00"));
        assertEquals(MATH_52, LazyTriangulationEdgePreparation.mathSingleton("5.3.04"));
        // A later major keeps the moved singleton; only the 5.0/5.1 family names K.
        assertEquals(MATH_52, LazyTriangulationEdgePreparation.mathSingleton("6.0.00"));
        assertEquals(MATH_52, LazyTriangulationEdgePreparation.mathSingleton("10.0.00"));
        assertEquals(MATH_5051, LazyTriangulationEdgePreparation.mathSingleton("4.9.99"));
        for (final String malformed : List.of("unreviewed", "5.2", "5.2.00.1", "", "5.x.00", "-5.2.00")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LazyTriangulationEdgePreparation.mathSingleton(malformed),
                    malformed + " must be rejected as an unreviewed version");
        }
        assertThrows(IllegalArgumentException.class, () -> LazyTriangulationEdgePreparation.mathSingleton(null));
    }

    @Test
    void capturedDependencySetContainsTheAngleSingletonForEveryFamily() {
        for (final String version : List.of("5.0.00", "5.1.03", "5.2.00", "5.3.04", "6.0.00")) {
            final String mathName = LazyTriangulationEdgePreparation.mathSingleton(version);
            final List<String> names = LazyTriangulationEdgePreparation.dependencyNames(mathName);
            assertTrue(names.contains(mathName), version + " must capture its angle singleton " + mathName);
            assertEquals(
                    names.size(),
                    new java.util.HashSet<>(names).size(),
                    version + " dependency names must stay unique");
        }
    }

    @Test
    void liveLinkContractAdmitsTheReviewedMathSingletonAndRejectsItsAbsence() throws Exception {
        assumeTrue(
                legacyEvidence() != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");

        for (final Path jar : reviewedArtifacts(legacyEvidence()).values()) {
            final JarLoader loader = new JarLoader(jar);
            final Class<?> host = loader.loadClass(TriangulationEdgeIndexTransformer.MEMBERSHIP_CLASS_NAME);
            final URI hostOrigin = origin(host);
            final URI kotlinOrigin = jar.getParent().resolve(KOTLIN_JAR).toUri().normalize();
            // Resolution must include the angle singleton; without it admission fails silently.
            final Class<?>[] types = LazyTriangulationEdgePreparation.resolve(host, MATH_52, hostOrigin, kotlinOrigin);
            assertTrue(
                    Arrays.asList(types).contains(loader.loadClass(MATH_52)),
                    "the captured set must contain the angle singleton");
            assertNull(
                    LazyTriangulationEdgePreparation.verifyLinks(types, MATH_52),
                    "the captured set must satisfy the live link contract including the angle singleton");
            // Dropping the singleton from the captured set must reject, never admit silently.
            assertNotNull(
                    LazyTriangulationEdgePreparation.verifyLinks(Arrays.copyOf(types, types.length - 1), MATH_52),
                    "a captured set without its angle singleton must be rejected");
        }
    }

    /** Origin of one loaded class, or null when the code source is unavailable. */
    private static URI origin(final Class<?> type) throws Exception {
        return type.getProtectionDomain().getCodeSource().getLocation().toURI().normalize();
    }

    @Test
    void reviewedMathSingletonCarriesTheAngleThresholdContract() throws Exception {
        assumeTrue(
                legacyEvidence() != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");

        for (final Path jar : reviewedArtifacts(legacyEvidence()).values()) {
            final Class<?> math = new JarLoader(jar).loadClass(MATH_52);
            assertTrue(Modifier.isFinal(math.getModifiers()), "angle threshold class must be final");
            assertEquals(Object.class, math.getSuperclass(), "angle threshold class must extend Object");
            final Method threshold = math.getDeclaredMethod(THRESHOLD_METHOD);
            assertTrue(
                    threshold.getReturnType() == float.class && Modifier.isFinal(threshold.getModifiers()),
                    "angle threshold method must be a final float getter");
            final Field epsilon = math.getDeclaredField(THRESHOLD_FIELD);
            assertTrue(
                    epsilon.getType() == float.class
                            && Modifier.isPrivate(epsilon.getModifiers())
                            && Modifier.isStatic(epsilon.getModifiers())
                            && Modifier.isFinal(epsilon.getModifiers()),
                    "angle threshold field must be an immutable private static float");
            final Field singleton = math.getDeclaredField(MATH_FIELD);
            assertTrue(
                    singleton.getType() == math
                            && Modifier.isStatic(singleton.getModifiers())
                            && Modifier.isFinal(singleton.getModifiers()),
                    "angle threshold singleton must be a static final self reference");
        }
    }

    @Test
    void localBuilderContractRejectsChangedQuerySitesAndMissingAppend() throws Exception {
        assumeTrue(
                legacyEvidence() != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");

        final byte[] original = new TriangulationEdgeIndexPatcher()
                .patch(readEntry(legacyEvidence().resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar")));
        assertNotNull(TriangleListEdgeBuilderPatcher.patch(original), "the reviewed builder must weave");
        for (final String change : List.of("receiver", "argument", "append")) {
            final byte[] changed = tamperBuilder(original, change);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TriangleListEdgeBuilderPatcher.patch(changed),
                    change + " must invalidate the local-builder contract");
        }
    }

    /** Rewrites one reviewed builder detail; every variant leaves the weave contract. */
    private static byte[] tamperBuilder(final byte[] bytes, final String change) {
        final var writer = new org.objectweb.asm.ClassWriter(0);
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                final var output = super.visitMethod(access, name, descriptor, signature, exceptions);
                                if (!name.equals("b") || !descriptor.endsWith("/k;")) return output;
                                return new MethodVisitor(Opcodes.ASM9, output) {
                                    @Override
                                    public void visitVarInsn(final int opcode, final int local) {
                                        super.visitVarInsn(
                                                opcode,
                                                change.equals("receiver") && opcode == Opcodes.ALOAD && local == 1
                                                        ? 2
                                                        : local);
                                    }

                                    @Override
                                    public void visitInsn(final int opcode) {
                                        super.visitInsn(
                                                change.equals("argument") && opcode == Opcodes.ICONST_0
                                                        ? Opcodes.ICONST_1
                                                        : opcode);
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String method,
                                            final String desc,
                                            final boolean itf) {
                                        super.visitMethodInsn(
                                                opcode,
                                                owner,
                                                change.equals("append")
                                                                && owner.endsWith("/k")
                                                                && method.equals("a")
                                                                && desc.endsWith(";)Z")
                                                        ? "c"
                                                        : method,
                                                desc,
                                                itf);
                                    }
                                };
                            }
                        },
                        0);
        return writer.toByteArray();
    }

    @Test
    void bothCallerOptimizationsReportCleanStageReceipts() throws Exception {
        assumeTrue(
                legacyEvidence() != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");

        for (final Path jar : reviewedArtifacts(legacyEvidence()).values()) {
            final byte[] bytes = readEntry(jar, TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME + ".class");
            final List<String> receipts = new ArrayList<>();
            final var transformer = new TriangulationEdgeIndexTransformer(receipts::add);
            final byte[] patched = transformer.transform(
                    null, TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME, null, null, bytes);
            assertNotNull(patched);
            assertEquals(TriangulationEdgeIndexTransformer.Outcome.PATCHED, transformer.membershipOutcome());
            assertEquals(TriangulationEdgeIndexTransformer.Outcome.PATCHED, transformer.freshEdgeOutcome());
            assertEquals(
                    List.of("TRIANGULATION_FRESH_EDGE_PATCHED", "TRIANGULATION_MEMBERSHIP_PATCHED"),
                    receipts,
                    "each independent stage reports one clean receipt");
            assertWovenMembershipCallSites(patched, bytes);

            final var brokenReceipt = new TriangulationEdgeIndexTransformer(code -> {
                throw new IllegalStateException("sink unavailable");
            });
            assertEquals(
                    structure(patched, ""),
                    structure(
                            brokenReceipt.transform(
                                    null,
                                    TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME,
                                    null,
                                    null,
                                    bytes),
                            ""),
                    "a failing receipt sink must not change the transformed definition");
        }
    }

    /** Asserts the membership weave carries its fresh-edge call site and the fused debug probe. */
    private static void assertWovenMembershipCallSites(final byte[] patched, final byte[] original) {
        final List<String> calls = new ArrayList<>();
        new ClassReader(patched)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String method,
                                            final String desc,
                                            final boolean itf) {
                                        calls.add(owner + "." + method + desc);
                                    }
                                };
                            }
                        },
                        0);
        assertTrue(
                calls.stream()
                        .anyMatch(call -> call.startsWith(
                                "dev/turboism/adapter/cubism/mesh/FreshTriangulationEdgeSearch.containsFresh")),
                "fresh-edge search elimination must be woven");
        assertTrue(
                calls.stream().anyMatch(call -> call.equals(DEBUG_PROBE_CALL)),
                "membership fusion must keep its debug probe call");
        assertFalse(Arrays.equals(original, patched), "the membership weave must change the definition");
    }

    @Test
    void hashWeavePreservesEveryOtherRuntimeMember() throws Exception {
        assumeTrue(
                legacyEvidence() != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");

        final byte[] original = readEntry(
                legacyEvidence().resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar"),
                MeshTriangulationHashTransformer.TARGET_INTERNAL_NAME + ".class");
        final byte[] patched = new MeshTriangulationHashTransformer()
                .transform(null, MeshTriangulationHashTransformer.TARGET_INTERNAL_NAME, null, null, original);
        assertNotNull(patched);
        assertEquals(
                structure(original, "hashCode"),
                structure(patched, "hashCode"),
                "the hash weave must preserve every other runtime member");
        assertTrue(
                structure(original, "").get("method hashCode()I") != null,
                "the reviewed host must declare the woven hashCode");
    }

    /**
     * Structural projection of one class: header, fields and per-method instruction tokens.
     *
     * <p>Two projections differ when any retained member changes shape, and the skipped method
     * stays outside the comparison so a weave can be checked against its own input.</p>
     */
    private static Map<String, List<String>> structure(final byte[] bytes, final String skippedMethod) {
        final Map<String, List<String>> structure = new TreeMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(
                                    final int version,
                                    final int access,
                                    final String name,
                                    final String signature,
                                    final String superName,
                                    final String[] interfaces) {
                                structure.put(
                                        "class",
                                        List.of(
                                                Integer.toString(version),
                                                Integer.toString(access),
                                                name,
                                                String.valueOf(superName),
                                                Arrays.toString(interfaces)));
                            }

                            @Override
                            public FieldVisitor visitField(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final Object value) {
                                structure.put(
                                        "field " + name + ':' + descriptor,
                                        List.of(
                                                Integer.toString(access),
                                                String.valueOf(signature),
                                                String.valueOf(value)));
                                return null;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (name.equals(skippedMethod)) return null;
                                final List<String> tokens = new ArrayList<>(List.of(
                                        Integer.toString(access),
                                        String.valueOf(signature),
                                        Arrays.toString(exceptions)));
                                structure.put("method " + name + descriptor, tokens);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitInsn(final int opcode) {
                                        tokens.add("insn " + opcode);
                                    }

                                    @Override
                                    public void visitIntInsn(final int opcode, final int operand) {
                                        tokens.add("int " + opcode + ' ' + operand);
                                    }

                                    @Override
                                    public void visitVarInsn(final int opcode, final int local) {
                                        tokens.add("var " + opcode + ' ' + local);
                                    }

                                    @Override
                                    public void visitTypeInsn(final int opcode, final String type) {
                                        tokens.add("type " + opcode + ' ' + type);
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            final int opcode,
                                            final String owner,
                                            final String fieldName,
                                            final String fieldDescriptor) {
                                        tokens.add("field " + opcode + ' ' + owner + '.' + fieldName + fieldDescriptor);
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String methodName,
                                            final String methodDescriptor,
                                            final boolean itf) {
                                        tokens.add(
                                                "call " + opcode + ' ' + owner + '.' + methodName + methodDescriptor);
                                    }

                                    @Override
                                    public void visitJumpInsn(final int opcode, final Label target) {
                                        tokens.add("jump " + opcode);
                                    }

                                    @Override
                                    public void visitLdcInsn(final Object value) {
                                        tokens.add("constant " + value);
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return structure;
    }

    /** Child loader over one reviewed jar plus its sibling Kotlin runtime; bootstrap parent. */
    private static final class JarLoader extends ClassLoader {
        private final List<Path> jars;

        JarLoader(final Path jar) {
            super(null);
            this.jars = List.of(jar, jar.getParent().resolve(KOTLIN_JAR));
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            for (final Path jar : jars) {
                try (JarFile file = new JarFile(jar.toFile())) {
                    final var entry = file.getEntry(name.replace('.', '/') + ".class");
                    if (entry == null) continue;
                    try (InputStream stream = file.getInputStream(entry)) {
                        final byte[] bytes = stream.readAllBytes();
                        return defineClass(name, bytes, 0, bytes.length, domain(jar));
                    }
                } catch (IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            throw new ClassNotFoundException(name);
        }

        /** Real file code source, so origin checks see the same shape a live host presents. */
        private static ProtectionDomain domain(final Path jar) {
            try {
                return new ProtectionDomain(
                        new CodeSource(jar.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
            } catch (java.net.MalformedURLException invalid) {
                throw new IllegalStateException("unreadable evidence jar " + jar, invalid);
            }
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
        return null;
    }
}
