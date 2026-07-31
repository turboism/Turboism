package dev.turboism.ui.overlay;

import dev.turboism.mapping.verification.StaticSelector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/** Exact-selector transformer adding one bounded post-update ingress call. */
public final class BoundingBoxOverlayButtonUpdateTransformer implements ClassFileTransformer {

    private static final String BRIDGE_OWNER =
        "dev/turboism/ui/overlay/NativeBoundingBoxOverlayButtonBridge";
    private static final String BRIDGE_DESCRIPTOR =
        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";

    private final ClassLoader expectedLoader;
    private final StaticSelector updateSelector;

    public BoundingBoxOverlayButtonUpdateTransformer(
        final ClassLoader expectedLoader,
        final StaticSelector updateSelector
    ) {
        this.expectedLoader = Objects.requireNonNull(expectedLoader, "expectedLoader");
        this.updateSelector = Objects.requireNonNull(updateSelector, "updateSelector");
    }

    @Override
    public byte[] transform(
        final Module module,
        final ClassLoader loader,
        final String className,
        final Class<?> classBeingRedefined,
        final ProtectionDomain protectionDomain,
        final byte[] classfileBuffer
    ) {
        if (loader != expectedLoader
            || className == null
            || !className.equals(updateSelector.ownerInternalName())) {
            return null;
        }
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(
                    access,
                    name,
                    descriptor,
                    signature,
                    exceptions
                );
                if (!name.equals(updateSelector.memberName())
                    || !descriptor.equals(updateSelector.descriptor())) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitVarInsn(Opcodes.ALOAD, 2);
                            visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                BRIDGE_OWNER,
                                "afterUpdate",
                                BRIDGE_DESCRIPTOR,
                                false
                            );
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
