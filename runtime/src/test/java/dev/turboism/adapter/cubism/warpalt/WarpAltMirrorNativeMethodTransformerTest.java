package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class WarpAltMirrorNativeMethodTransformerTest {

    @Test
    void actualClassBytesMustMatchTheBoundContractEvenWithTheCorrectCodeSource() throws Exception {
        final var profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final var artifact = java.nio.file.Path.of("/tmp/warp-bound-contract.jar");
        final var loader = getClass().getClassLoader();
        final var domain = new java.security.ProtectionDomain(
                new java.security.CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
        final byte[] original = fixture(profile, profile.pointMoveOwner());
        final String pin = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        final var transformer = new WarpAltMirrorNativeMethodTransformer(
                profile, loader, artifact, java.util.Map.of(profile.pointMoveOwner(), pin), ignored -> {});
        final byte[] changed = original.clone();
        changed[changed.length - 1] ^= 1;

        assertNull(transformer.transform(null, loader, profile.pointMoveOwner(), null, domain, changed));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.CLASS_BYTES_MISMATCH, transformer.outcome());
        assertNull(transformer.admittedClassLoader());
        assertTrue(transformer.transformedOwners().isEmpty());
        assertNotNull(transformer.transform(null, loader, profile.pointMoveOwner(), null, domain, original));

        final var missingProof = new WarpAltMirrorNativeMethodTransformer(profile, loader, artifact, ignored -> {});
        assertNull(missingProof.transform(null, loader, profile.pointMoveOwner(), null, domain, original));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.CLASS_BYTES_MISMATCH, missingProof.outcome());
    }

    @Test
    void deactivationRetainsRestorationInventoryButPreventsNewPatches() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, getClass().getClassLoader());
        assertNotNull(transformer.transform(
                null,
                getClass().getClassLoader(),
                profile.pointMoveOwner(),
                null,
                null,
                fixture(profile, profile.pointMoveOwner())));

        transformer.deactivate();

        assertNull(transformer.transform(
                null,
                getClass().getClassLoader(),
                profile.dragTickOwner(),
                null,
                null,
                fixture(profile, profile.dragTickOwner())));
        assertEquals(java.util.Set.of(profile.pointMoveOwner()), transformer.transformedOwners());
        assertFalse(transformer.targetTransformed());
    }

    @Test
    void transformsOnlyTheExactReviewedDragMoveMethod() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null);

        assertNull(transformer.transform(null, null, "fixture/Other", null, null, fixture(profile, "fixture/Other")));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.NONE, transformer.outcome());

        final byte[] transformed = transformer.transform(
                null, null, profile.pointMoveOwner(), null, null, fixture(profile, profile.pointMoveOwner()));
        assertNotNull(transformed);
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.TARGET_TRANSFORMED, transformer.outcome());
        assertTrue(transformer.targetTransformed());
        assertTrue(containsBridgeCall(transformed, "mirrorPointMove", "(Ljava/lang/Object;Ljava/lang/Object;F)V"));

        // The drag-tick owner is instrumented with the event-carrying bridge call.
        final WarpAltMirrorNativeMethodTransformer tickTransformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null);
        final byte[] tickTransformed = tickTransformer.transform(
                null, null, profile.dragTickOwner(), null, null, fixture(profile, profile.dragTickOwner()));
        assertNotNull(tickTransformed);
        assertTrue(containsBridgeCall(
                tickTransformed, "mirrorWarpDragMove", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"));
    }

    @Test
    void weightWriteOwnerInstrumentsBothConvergedWrites() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null);

        final byte[] transformed = transformer.transform(
                null, null, profile.weightWriteOwner(), null, null, fixture(profile, profile.weightWriteOwner()));
        assertNotNull(transformed);
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.TARGET_TRANSFORMED, transformer.outcome());
        assertTrue(containsBridgeCall(transformed, "mirrorWeightAdd", "(Ljava/lang/Object;Ljava/lang/Object;FZ)V"));
        assertTrue(containsBridgeCall(transformed, "mirrorWeightSet", "(Ljava/lang/Object;Ljava/lang/Object;F)V"));
    }

    @Test
    void unrelatedMethodOnTheTargetOwnerIsLeftAlone() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null);

        assertNull(transformer.transform(
                null, null, profile.pointMoveOwner(), null, null, unrelatedOwnerFixture(profile)));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.TARGET_UNCHANGED, transformer.outcome());
    }

    @Test
    void rejectsRetransformationFailClosed() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null);
        final List<String> diagnostics = new ArrayList<>();
        final WarpAltMirrorNativeMethodTransformer retransform =
                new WarpAltMirrorNativeMethodTransformer(profile, null, null, diagnostics::add);

        assertNull(retransform.transform(
                null, null, profile.pointMoveOwner(), Object.class, null, fixture(profile, profile.pointMoveOwner())));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.RETRANSFORM_REJECTED, retransform.outcome());
        assertTrue(diagnostics.get(0).startsWith("WARP_ALT_MIRROR_RETRANSFORM_REJECTED"));
    }

    @Test
    void pinsTheFirstAdmittedLoaderAndRejectsEveryOtherLoader() throws Exception {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final ClassLoader first = new ClassLoader() {};
        final ClassLoader second = new ClassLoader() {};
        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, first, null, ignored -> {});

        assertNotNull(transformer.transform(
                null, first, profile.pointMoveOwner(), null, null, fixture(profile, profile.pointMoveOwner())));
        assertEquals(first, transformer.admittedClassLoader());

        assertNull(transformer.transform(
                null, second, profile.pointMoveOwner(), null, null, fixture(profile, profile.pointMoveOwner())));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.LOADER_MISMATCH, transformer.outcome());
    }

    @Test
    void rejectsBootstrapLoaderWhenAnExpectationIsDeclared() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final WarpAltMirrorNativeMethodTransformer transformer = new WarpAltMirrorNativeMethodTransformer(
                profile, null, java.nio.file.Path.of("/tmp/warp-alt-mirror-expected.jar"), ignored -> {});

        assertNull(transformer.transform(
                null, null, profile.pointMoveOwner(), null, null, fixture(profile, profile.pointMoveOwner())));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.BOOTSTRAP_LOADER_REJECTED, transformer.outcome());
    }

    @Test
    void rejectsArtifactsFromAnUnexpectedCodeSource() throws Exception {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final java.nio.file.Path expected = java.nio.file.Path.of("/tmp/warp-alt-mirror-expected.jar");
        final java.nio.file.Path other = java.nio.file.Path.of("/tmp/warp-alt-mirror-other.jar");
        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null, expected, ignored -> {});

        final java.security.ProtectionDomain domain = new java.security.ProtectionDomain(
                new java.security.CodeSource(other.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
        assertNull(transformer.transform(
                null,
                new ClassLoader() {},
                profile.pointMoveOwner(),
                null,
                domain,
                fixture(profile, profile.pointMoveOwner())));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.ARTIFACT_MISMATCH, transformer.outcome());
    }

    @Test
    void stripLayoutCallbackPrecedesEveryNormalReturn() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, profile.stripOwner(), null, "java/lang/Object", null);
        final MethodVisitor layout = writer.visitMethod(
                Opcodes.ACC_PUBLIC, profile.stripLayoutMethod(), profile.stripLayoutDescriptor(), null, null);
        layout.visitCode();
        final org.objectweb.asm.Label secondExit = new org.objectweb.asm.Label();
        layout.visitVarInsn(Opcodes.ALOAD, 1);
        layout.visitJumpInsn(Opcodes.IFNULL, secondExit);
        layout.visitInsn(Opcodes.RETURN);
        layout.visitLabel(secondExit);
        layout.visitInsn(Opcodes.RETURN);
        layout.visitMaxs(0, 0);
        layout.visitEnd();
        writer.visitEnd();

        final var transformer = new WarpAltMirrorNativeMethodTransformer(profile, null);
        final byte[] transformed =
                transformer.transform(null, null, profile.stripOwner(), null, null, writer.toByteArray());
        assertNotNull(transformed);
        final List<String> exits = new ArrayList<>();
        new ClassReader(transformed)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!name.equals(profile.stripLayoutMethod())
                                        || !descriptor.equals(profile.stripLayoutDescriptor())) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String calledName,
                                            final String calledDescriptor,
                                            final boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && owner.equals(
                                                        "dev/turboism/adapter/cubism/warpalt/NativeWarpAltMirrorBridge")
                                                && calledName.equals("positionStripButton")
                                                && calledDescriptor.equals("(Ljava/lang/Object;)V"))
                                            exits.add("position");
                                    }

                                    @Override
                                    public void visitInsn(final int opcode) {
                                        if (opcode == Opcodes.RETURN) exits.add("return");
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        assertEquals(
                List.of("position", "return", "position", "return"),
                exits,
                "a callback emitted after RETURN is unreachable and cannot support the feature");
    }

    @Test
    void stripMountSelectorFollowsTheProfileVersion() {
        final WarpAltMirrorHostProfile p5203 = WarpAltMirrorHostProfile.reviewed5203();
        final WarpAltMirrorHostProfile p53 = WarpAltMirrorHostProfile.reviewed5302And5303();

        // 5.2.03 mounts at L()V; the 5.3.x name R()V is an unrelated delegate there
        // and must not be instrumented.
        final WarpAltMirrorNativeMethodTransformer wrongName = new WarpAltMirrorNativeMethodTransformer(p5203, null);
        assertNull(wrongName.transform(null, null, p5203.stripOwner(), null, null, stripFixture(p5203, "R")));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.TARGET_UNCHANGED, wrongName.outcome());

        final WarpAltMirrorNativeMethodTransformer rightName = new WarpAltMirrorNativeMethodTransformer(p5203, null);
        final byte[] mounted =
                rightName.transform(null, null, p5203.stripOwner(), null, null, stripFixture(p5203, "L"));
        assertNotNull(mounted);
        assertTrue(containsBridgeCall(mounted, "mountViewContextMenu", "(Ljava/lang/Object;)V"));

        // 5.3.x mounts at R()V and must not touch a 5.2.03-style L()V member.
        final WarpAltMirrorNativeMethodTransformer otherName = new WarpAltMirrorNativeMethodTransformer(p53, null);
        assertNull(otherName.transform(null, null, p53.stripOwner(), null, null, stripFixture(p53, "L")));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.TARGET_UNCHANGED, otherName.outcome());
    }

    private static boolean containsBridgeCall(final byte[] classBytes, final String method, final String descriptor) {
        final boolean[] found = {false};
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String visitedName,
                                    final String visitedDescriptor,
                                    final String visitedSignature,
                                    final String[] visitedExceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String calledName,
                                            final String calledDescriptor,
                                            final boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && owner.endsWith("NativeWarpAltMirrorBridge")
                                                && method.equals(calledName)
                                                && descriptor.equals(calledDescriptor)) {
                                            found[0] = true;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return found[0];
    }

    /** Builds a minimal owner class containing the exact reviewed drag-move method. */
    private static byte[] fixture(final WarpAltMirrorHostProfile profile, final String owner) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        if (owner.equals(profile.pointMoveOwner())) {
            method(writer, profile.pointMoveMethod(), profile.pointMoveDescriptor(), Opcodes.RETURN);
        }
        if (owner.equals(profile.dragTickOwner())) {
            method(writer, profile.dragTickMethod(), profile.dragTickDescriptor(), Opcodes.RETURN);
        }
        if (owner.equals(profile.weightWriteOwner())) {
            final MethodVisitor add = writer.visitMethod(
                    Opcodes.ACC_PUBLIC, profile.weightAddMethod(), profile.weightAddDescriptor(), null, null);
            add.visitCode();
            add.visitInsn(Opcodes.ICONST_0);
            add.visitInsn(Opcodes.IRETURN);
            add.visitMaxs(0, 0);
            add.visitEnd();
            method(writer, profile.weightSetMethod(), profile.weightSetDescriptor(), Opcodes.RETURN);
        }
        method(writer, "unrelated", "()V", Opcodes.RETURN);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Strip-owner class carrying a single no-arg void method with the given name. */
    private static byte[] stripFixture(final WarpAltMirrorHostProfile profile, final String methodName) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, profile.stripOwner(), null, "java/lang/Object", null);
        method(writer, methodName, "()V", Opcodes.RETURN);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Owner class with only an unrelated method, so the exact method is missing. */
    private static byte[] unrelatedOwnerFixture(final WarpAltMirrorHostProfile profile) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, profile.pointMoveOwner(), null, "java/lang/Object", null);
        method(writer, "unrelated", "()V", Opcodes.RETURN);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(
            final ClassWriter writer, final String name, final String descriptor, final int returnOpcode) {
        final MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        visitor.visitCode();
        visitor.visitInsn(returnOpcode);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }
}
