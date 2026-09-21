package dev.turboism.adapter.cubism.warpalt;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WarpAltMirrorNativeMethodTransformerTest {

    @Test
    void transformsOnlyTheExactReviewedDragMoveMethod() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5303();
        final WarpAltMirrorNativeMethodTransformer transformer =
            new WarpAltMirrorNativeMethodTransformer(profile, null);

        assertNull(transformer.transform(
            null, null, "fixture/Other", null, null, fixture(profile, "fixture/Other")
        ));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.NONE, transformer.outcome());

        final byte[] transformed = transformer.transform(
            null, null, profile.pointMoveOwner(), null, null, fixture(profile, profile.pointMoveOwner())
        );
        assertNotNull(transformed);
        assertEquals(
            WarpAltMirrorNativeMethodTransformer.Outcome.TARGET_TRANSFORMED, transformer.outcome());
        assertTrue(transformer.targetTransformed());
        assertTrue(containsBridgeCall(transformed, "mirrorPointMove",
            "(Ljava/lang/Object;Ljava/lang/Object;F)V"));

        // The drag-tick owner is instrumented with the event-carrying bridge call.
        final WarpAltMirrorNativeMethodTransformer tickTransformer =
            new WarpAltMirrorNativeMethodTransformer(profile, null);
        final byte[] tickTransformed = tickTransformer.transform(
            null, null, profile.dragTickOwner(), null, null, fixture(profile, profile.dragTickOwner())
        );
        assertNotNull(tickTransformed);
        assertTrue(containsBridgeCall(tickTransformed, "mirrorWarpDragMove",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"));
    }

    @Test
    void unrelatedMethodOnTheTargetOwnerIsLeftAlone() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5303();
        final WarpAltMirrorNativeMethodTransformer transformer =
            new WarpAltMirrorNativeMethodTransformer(profile, null);

        assertNull(transformer.transform(
            null, null, profile.pointMoveOwner(), null, null, unrelatedOwnerFixture(profile)
        ));
        assertEquals(
            WarpAltMirrorNativeMethodTransformer.Outcome.TARGET_UNCHANGED, transformer.outcome());
    }

    @Test
    void rejectsRetransformationFailClosed() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5303();
        final WarpAltMirrorNativeMethodTransformer transformer =
            new WarpAltMirrorNativeMethodTransformer(profile, null);
        final List<String> diagnostics = new ArrayList<>();
        final WarpAltMirrorNativeMethodTransformer retransform =
            new WarpAltMirrorNativeMethodTransformer(profile, null, null, diagnostics::add);

        assertNull(retransform.transform(
            null, null, profile.pointMoveOwner(),
            Object.class, null, fixture(profile, profile.pointMoveOwner())
        ));
        assertEquals(
            WarpAltMirrorNativeMethodTransformer.Outcome.RETRANSFORM_REJECTED,
            retransform.outcome());
        assertTrue(diagnostics.get(0).startsWith("WARP_ALT_MIRROR_RETRANSFORM_REJECTED"));
    }

    @Test
    void pinsTheFirstAdmittedLoaderAndRejectsEveryOtherLoader() throws Exception {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5303();
        final ClassLoader first = new ClassLoader() { };
        final ClassLoader second = new ClassLoader() { };
        final WarpAltMirrorNativeMethodTransformer transformer =
            new WarpAltMirrorNativeMethodTransformer(profile, first, null, ignored -> { });

        assertNotNull(transformer.transform(
            null, first, profile.pointMoveOwner(), null, null, fixture(profile, profile.pointMoveOwner())
        ));
        assertEquals(first, transformer.admittedClassLoader());

        assertNull(transformer.transform(
            null, second, profile.pointMoveOwner(), null, null, fixture(profile, profile.pointMoveOwner())
        ));
        assertEquals(
            WarpAltMirrorNativeMethodTransformer.Outcome.LOADER_MISMATCH,
            transformer.outcome());
    }

    @Test
    void rejectsBootstrapLoaderWhenAnExpectationIsDeclared() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5303();
        final WarpAltMirrorNativeMethodTransformer transformer =
            new WarpAltMirrorNativeMethodTransformer(
                profile, null, java.nio.file.Path.of("/tmp/warp-alt-mirror-expected.jar"), ignored -> { });

        assertNull(transformer.transform(
            null, null, profile.pointMoveOwner(), null, null, fixture(profile, profile.pointMoveOwner())
        ));
        assertEquals(
            WarpAltMirrorNativeMethodTransformer.Outcome.BOOTSTRAP_LOADER_REJECTED,
            transformer.outcome());
    }

    @Test
    void rejectsArtifactsFromAnUnexpectedCodeSource() throws Exception {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5303();
        final java.nio.file.Path expected = java.nio.file.Path.of("/tmp/warp-alt-mirror-expected.jar");
        final java.nio.file.Path other = java.nio.file.Path.of("/tmp/warp-alt-mirror-other.jar");
        final WarpAltMirrorNativeMethodTransformer transformer =
            new WarpAltMirrorNativeMethodTransformer(profile, null, expected, ignored -> { });

        final java.security.ProtectionDomain domain = new java.security.ProtectionDomain(
            new java.security.CodeSource(other.toUri().toURL(), (java.security.cert.Certificate[]) null),
            null
        );
        assertNull(transformer.transform(
            null, new ClassLoader() { }, profile.pointMoveOwner(), null, domain,
            fixture(profile, profile.pointMoveOwner())
        ));
        assertEquals(
            WarpAltMirrorNativeMethodTransformer.Outcome.ARTIFACT_MISMATCH, transformer.outcome());
    }

    private static boolean containsBridgeCall(
        final byte[] classBytes, final String method, final String descriptor
    ) {
        final boolean[] found = {false};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String visitedName,
                final String visitedDescriptor,
                final String visitedSignature,
                final String[] visitedExceptions
            ) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String calledName,
                        final String calledDescriptor,
                        final boolean isInterface
                    ) {
                        if (opcode == Opcodes.INVOKESTATIC
                            && owner.endsWith("NativeWarpAltMirrorBridge")
                            && method.equals(calledName)
                            && descriptor.equals(calledDescriptor)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
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
        method(writer, "unrelated", "()V", Opcodes.RETURN);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Owner class with only an unrelated method, so the exact method is missing. */
    private static byte[] unrelatedOwnerFixture(final WarpAltMirrorHostProfile profile) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC, profile.pointMoveOwner(), null, "java/lang/Object", null);
        method(writer, "unrelated", "()V", Opcodes.RETURN);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(
        final ClassWriter writer,
        final String name,
        final String descriptor,
        final int returnOpcode
    ) {
        final MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        visitor.visitCode();
        visitor.visitInsn(returnOpcode);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }
}
