package dev.turboism.adapter.cubism.mesh;

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

final class MeshMirrorNativeMethodTransformerTest {

    @Test
    void instrumentsOnlyTheExactVerifiedMethods() {
        final MeshMirrorHostProfile profile = MeshMirrorHostProfile.reviewed52And53();
        final MeshMirrorNativeMethodTransformer transformer =
            new MeshMirrorNativeMethodTransformer(profile, null);

        assertNull(transformer.transform(
            null, null, "fixture/Other", null, null, fixture("fixture/Other", profile)
        ));

        final byte[] meshTransformed = transformer.transform(
            null, null, profile.meshEditorOwner(), null, null,
            fixture(profile.meshEditorOwner(), profile)
        );
        final byte[] widgetTransformed = transformer.transform(
            null, null, profile.mirrorWidgetOwner(), null, null,
            fixture(profile.mirrorWidgetOwner(), profile)
        );
        final byte[] drawTransformed = transformer.transform(
            null, null, profile.mirrorAxisDrawOwner(), null, null,
            fixture(profile.mirrorAxisDrawOwner(), profile)
        );
        assertNotNull(meshTransformed);
        assertNotNull(widgetTransformed);
        assertNotNull(drawTransformed);
        assertEquals(List.of("adjustPoint", "adjustAxisPoint", "adjustHit"), bridgeCalls(meshTransformed));
        assertEquals(List.of("attachControl"), bridgeCalls(widgetTransformed));
        assertEquals(List.of("drawAxis"), bridgeCalls(drawTransformed));
    }

    @Test
    void transformedBytecodeRemainsJvmVerifiableWithFrames() throws Exception {
        final MeshMirrorHostProfile profile = MeshMirrorHostProfile.reviewed52And53();
        final MeshMirrorNativeMethodTransformer transformer =
            new MeshMirrorNativeMethodTransformer(profile, null);

        // A branching method forces stack-map frames; a frame-stale transform fails verification.
        final byte[] meshTransformed = transformer.transform(
            null, null, profile.meshEditorOwner(), null, null,
            branchingFixture(profile.meshEditorOwner(), profile)
        );

        assertNotNull(meshTransformed);
        final Class<?> loaded = new ClassLoader() {
            Class<?> define() {
                return defineClass(profile.meshEditorOwner().replace('/', '.'), meshTransformed, 0, meshTransformed.length);
            }
        }.define();
        assertEquals(profile.meshEditorOwner().replace('/', '.'), loaded.getName());
    }

    private static byte[] branchingFixture(final String owner, final MeshMirrorHostProfile profile) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        // mirrorPointMethod: (GVector2)GVector2 with a branch -> frames in the method body.
        final MethodVisitor point = writer.visitMethod(
            Opcodes.ACC_PUBLIC, profile.mirrorPointMethod(), profile.mirrorPointDescriptor(), null, null);
        point.visitCode();
        final org.objectweb.asm.Label branch = new org.objectweb.asm.Label();
        point.visitVarInsn(Opcodes.ALOAD, 1);
        point.visitJumpInsn(Opcodes.IFNULL, branch);
        point.visitInsn(Opcodes.ACONST_NULL);
        point.visitInsn(Opcodes.ARETURN);
        point.visitLabel(branch);
        point.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        point.visitInsn(Opcodes.ACONST_NULL);
        point.visitInsn(Opcodes.ARETURN);
        point.visitMaxs(1, 2);
        point.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fixture(final String owner, final MeshMirrorHostProfile profile) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        if (owner.equals(profile.meshEditorOwner())) {
            method(writer, profile.mirrorPointMethod(), profile.mirrorPointDescriptor(), Opcodes.ARETURN);
            method(writer, profile.mirrorAxisPointMethod(), profile.mirrorPointDescriptor(), Opcodes.ARETURN);
            method(writer, profile.mirrorHitMethod(), profile.mirrorHitDescriptor(), Opcodes.IRETURN);
        }
        if (owner.equals(profile.mirrorWidgetOwner())) {
            method(writer, profile.mirrorWidgetMethod(), profile.mirrorWidgetDescriptor(), Opcodes.ARETURN);
        }
        if (owner.equals(profile.mirrorAxisDrawOwner())) {
            method(writer, profile.mirrorAxisDrawMethod(), profile.mirrorAxisDrawDescriptor(), Opcodes.RETURN);
        }
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
        final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        switch (returnOpcode) {
            case Opcodes.ARETURN -> method.visitInsn(Opcodes.ACONST_NULL);
            case Opcodes.IRETURN -> method.visitInsn(Opcodes.ICONST_0);
            default -> { }
        }
        method.visitInsn(returnOpcode);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static List<String> bridgeCalls(final byte[] bytecode) {
        final List<String> calls = new ArrayList<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
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
                        if (owner.equals("dev/turboism/adapter/cubism/mesh/NativeMeshMirrorBridge")) {
                            calls.add(calledName);
                        }
                    }
                };
            }
        }, 0);
        return calls;
    }
}
