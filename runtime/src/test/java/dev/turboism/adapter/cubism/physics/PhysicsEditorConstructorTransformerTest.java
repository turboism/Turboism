package dev.turboism.adapter.cubism.physics;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PhysicsEditorConstructorTransformerTest {

    @Test
    void instrumentsOnlyTheExactPanelOwner() {
        final PhysicsEditorConstructorTransformer transformer =
            new PhysicsEditorConstructorTransformer("fixture/Panel", null);

        assertNull(transformer.transform(null, null, "fixture/Other", null, null, fixture("fixture/Other")));
        assertNotNull(transformer.transform(null, null, "fixture/Panel", null, null, fixture("fixture/Panel")));
    }

    private static byte[] fixture(final String owner) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
