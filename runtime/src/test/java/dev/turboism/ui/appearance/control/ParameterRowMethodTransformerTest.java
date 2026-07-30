package dev.turboism.ui.appearance.control;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParameterRowMethodTransformerTest {
    @Test
    void instrumentsExactConstructorAndSelectionNormalReturns() throws Exception {
        final String owner = Target.class.getName().replace('.', '/');
        final ParameterRowMethodTransformer transformer = new ParameterRowMethodTransformer(
            owner,
            Set.of(
                new ParameterRowMethodTransformer.MethodSelector("<init>", "()V"),
                new ParameterRowMethodTransformer.MethodSelector("updateSelection", "(Z)V")
            ), false, Target.class.getClassLoader()
        );
        final byte[] transformed = transformer.transform(
            null, Target.class.getClassLoader(), owner, null, null, bytes()
        );
        final AtomicInteger callbacks = new AtomicInteger();
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions
            ) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String invokedOwner, String invokedName,
                        String invokedDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                            && invokedOwner.equals("dev/turboism/ui/appearance/control/NativeParameterAppearanceBridge")
                            && invokedName.equals("afterParameterRow")
                            && invokedDescriptor.equals("(Ljava/lang/Object;)V")) callbacks.incrementAndGet();
                    }
                };
            }
        }, 0);
        assertEquals(2, callbacks.get());
    }

    private static byte[] bytes() throws Exception {
        try (InputStream input = Target.class.getResourceAsStream("/" + Target.class.getName().replace('.', '/') + ".class")) {
            return input.readAllBytes();
        }
    }

    public static class Target {
        public Target() { }
        public void updateSelection(boolean selected) { }
    }
}
