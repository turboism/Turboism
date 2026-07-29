package dev.turboism.adapter.cubism.physics;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/** Exact-owner constructor transformer for the Physics Settings group-list panel. */
public final class PhysicsEditorConstructorTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "dev/turboism/adapter/cubism/physics/NativePhysicsEditorBridge";

    private final String ownerInternalName;
    private final ClassLoader expectedClassLoader;

    public PhysicsEditorConstructorTransformer(
        final String ownerInternalName,
        final ClassLoader expectedClassLoader
    ) {
        this.ownerInternalName = Objects.requireNonNull(ownerInternalName, "ownerInternalName");
        this.expectedClassLoader = expectedClassLoader;
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
        if (!ownerInternalName.equals(className)
            || classfileBuffer == null
            || (expectedClassLoader != null && loader != expectedClassLoader)) return null;
        final boolean[] transformed = {false};
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<init>".equals(name)) return delegate;
                transformed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                BRIDGE,
                                "afterConstructed",
                                "(Ljava/lang/Object;)V",
                                false
                            );
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return transformed[0] ? writer.toByteArray() : null;
    }
}
