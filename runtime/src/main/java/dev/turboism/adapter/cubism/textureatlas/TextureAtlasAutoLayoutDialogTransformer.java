package dev.turboism.adapter.cubism.textureatlas;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/**
 * Routes one exact native automatic-layout settings dialog construction through a
 * loader-neutral {@code Consumer<Object>} ingress invoked at the end of the constructor.
 *
 * <p>The ingress receives the constructed host {@code JDialog}. The ingress itself must
 * fail open (see {@link TextureAtlasAutoLayoutDialogContributor}), because the injected
 * call site carries no exception handler: a throwing ingress would abort construction.</p>
 */
public final class TextureAtlasAutoLayoutDialogTransformer implements ClassFileTransformer {

    private final String ownerInternalName;
    private final String constructorDescriptor;
    private final ClassLoader expectedClassLoader;
    private final String ingressKey;

    public TextureAtlasAutoLayoutDialogTransformer(
        final String ownerInternalName,
        final String constructorDescriptor,
        final ClassLoader expectedClassLoader,
        final String ingressKey
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.constructorDescriptor = requireText(constructorDescriptor, "constructorDescriptor");
        this.expectedClassLoader = expectedClassLoader;
        this.ingressKey = requireText(ingressKey, "ingressKey");
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
                    access, name, methodDescriptor, signature, exceptions
                );
                if (!"<init>".equals(name) || !constructorDescriptor.equals(methodDescriptor)) {
                    return delegate;
                }
                transformed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private boolean injected;

                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN && !injected) {
                            injected = true;
                            visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "java/lang/System",
                                "getProperties",
                                "()Ljava/util/Properties;",
                                false
                            );
                            visitLdcInsn(ingressKey);
                            visitMethodInsn(
                                Opcodes.INVOKEVIRTUAL,
                                "java/util/Properties",
                                "get",
                                "(Ljava/lang/Object;)Ljava/lang/Object;",
                                false
                            );
                            visitInsn(Opcodes.DUP);
                            visitTypeInsn(
                                Opcodes.INSTANCEOF,
                                "java/util/function/Consumer"
                            );
                            final Label skip = new Label();
                            final Label after = new Label();
                            visitJumpInsn(Opcodes.IFEQ, skip);
                            visitTypeInsn(
                                Opcodes.CHECKCAST,
                                "java/util/function/Consumer"
                            );
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitMethodInsn(
                                Opcodes.INVOKEINTERFACE,
                                "java/util/function/Consumer",
                                "accept",
                                "(Ljava/lang/Object;)V",
                                true
                            );
                            visitJumpInsn(Opcodes.GOTO, after);
                            visitLabel(skip);
                            visitInsn(Opcodes.POP);
                            visitLabel(after);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        if (transformed[0]) {
            diag("dialog transform applied owner=" + className + " bytes=" + writer.toByteArray().length);
        }
        return transformed[0] ? writer.toByteArray() : null;
    }

    /** Best-effort diagnostic file for exact-host dialog transformation evidence. */
    private static void diag(final String message) {
        try {
            final java.nio.file.Path home =
                java.nio.file.Path.of(System.getProperty("turboism.home", "."));
            final java.nio.file.Path file =
                home.resolve("logs").resolve("dialog-transform.log");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(
                file,
                java.time.Instant.now() + " " + message + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Throwable ignored) {
            // diagnostics are best-effort
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
