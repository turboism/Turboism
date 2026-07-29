package dev.turboism.adapter.cubism.textureatlas;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/** Instruments one exact model-image-list initialization method to capture its data model. */
public final class TextureAtlasDataModelTransformer implements ClassFileTransformer {

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final ClassLoader expectedClassLoader;
    private final String dataModelGetterName;
    private final String dataModelGetterDescriptor;
    private final String captureKey;

    public TextureAtlasDataModelTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final ClassLoader expectedClassLoader,
        final String dataModelGetterName,
        final String dataModelGetterDescriptor,
        final String captureKey
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methodName = requireText(methodName, "methodName");
        this.descriptor = requireText(descriptor, "descriptor");
        this.expectedClassLoader = expectedClassLoader;
        this.dataModelGetterName = requireText(dataModelGetterName, "dataModelGetterName");
        this.dataModelGetterDescriptor = requireText(
            dataModelGetterDescriptor,
            "dataModelGetterDescriptor"
        );
        this.captureKey = requireText(captureKey, "captureKey");
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
            || (expectedClassLoader != null && loader != expectedClassLoader)) {
            return null;
        }
        final boolean[] transformed = {false};
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(
            reader,
            ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
        );
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String methodDescriptor,
                final String signature,
                final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(
                    access,
                    name,
                    methodDescriptor,
                    signature,
                    exceptions
                );
                if (!methodName.equals(name) || !descriptor.equals(methodDescriptor)) {
                    return delegate;
                }
                transformed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "java/lang/System",
                                "getProperties",
                                "()Ljava/util/Properties;",
                                false
                            );
                            visitLdcInsn(captureKey);
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitMethodInsn(
                                Opcodes.INVOKEVIRTUAL,
                                ownerInternalName,
                                dataModelGetterName,
                                dataModelGetterDescriptor,
                                false
                            );
                            visitMethodInsn(
                                Opcodes.INVOKEVIRTUAL,
                                "java/util/Properties",
                                "put",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                false
                            );
                            visitInsn(Opcodes.POP);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return transformed[0] ? writer.toByteArray() : null;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
