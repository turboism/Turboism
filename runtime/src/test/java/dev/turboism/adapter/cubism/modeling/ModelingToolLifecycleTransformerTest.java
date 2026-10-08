package dev.turboism.adapter.cubism.modeling;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

class ModelingToolLifecycleTransformerTest {
    @TempDir
    Path temporary;

    private static final ModelingToolHostProfile PROFILE = new ModelingToolHostProfile(
            "5.3.03",
            "com/live2d/cubism/CEAppCtrl",
            "setupCurrentTool",
            "(Lcom/live2d/cubism/view/palette/tool/toolMode/AToolGroup;Lcom/live2d/cubism/view/palette/tool/toolMode/AToolMode;Z)V");

    @Test
    void observesAllNormalReturnsIncludingSameToolButNeverExceptionalExit() throws Exception {
        final Path artifact = temporary.resolve("Cubism.jar");
        final ClassLoader loader = getClass().getClassLoader();
        final var domain = new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
        final var transformer = new ModelingToolLifecycleTransformer(PROFILE, loader, artifact, ignored -> {});
        byte[] transformed = transformer.transform(null, loader, PROFILE.ownerInternalName(), null, domain, fixture());
        assertNotNull(transformed);
        final List<String> instructions = new ArrayList<>();
        new ClassReader(transformed)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String signature, String[] exceptions) {
                                if (!name.equals(PROFILE.method())) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String owner, String name, String desc, boolean iface) {
                                        if (owner.endsWith("/NativeModelingToolBridge")) instructions.add(name + desc);
                                    }

                                    @Override
                                    public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.RETURN) instructions.add("RETURN");
                                        if (opcode == Opcodes.ATHROW) instructions.add("ATHROW");
                                    }
                                };
                            }
                        },
                        0);
        assertEquals(
                List.of(
                        "afterToolChanged(Ljava/lang/Object;)V",
                        "RETURN",
                        "afterToolChanged(Ljava/lang/Object;)V",
                        "RETURN",
                        "ATHROW"),
                instructions);
        assertEquals(1, transformer.transformedCount());
    }

    private static byte[] fixture() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PROFILE.ownerInternalName(), null, "java/lang/Object", null);
        final MethodVisitor method =
                writer.visitMethod(Opcodes.ACC_PUBLIC, PROFILE.method(), PROFILE.descriptor(), null, null);
        method.visitCode();
        final Label second = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 3);
        method.visitJumpInsn(Opcodes.IFEQ, second);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(second);
        final Label thrown = new Label();
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitJumpInsn(Opcodes.IFNONNULL, thrown);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(thrown);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
