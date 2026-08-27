package dev.turboism.adapter.cubism.textureatlas;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.time.Instant;
import java.util.Objects;

/** Routes one exact native automatic-layout entry through a loader-neutral Predicate receiver ingress. */
public final class TextureAtlasAutoLayoutTransformer implements ClassFileTransformer {

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final ClassLoader expectedClassLoader;
    private final String callbackKey;
    private final String handledReturnKey;
    private final String nativeBodyEntryKey;
    private final String nativeCompletionKey;

    public TextureAtlasAutoLayoutTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final ClassLoader expectedClassLoader,
        final String callbackKey
    ) {
        this(
            ownerInternalName,
            methodName,
            descriptor,
            expectedClassLoader,
            callbackKey,
            "",
            "",
            ""
        );
    }

    public TextureAtlasAutoLayoutTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final ClassLoader expectedClassLoader,
        final String callbackKey,
        final String nativeCompletionKey
    ) {
        this(
            ownerInternalName,
            methodName,
            descriptor,
            expectedClassLoader,
            callbackKey,
            "",
            "",
            nativeCompletionKey
        );
    }

    public TextureAtlasAutoLayoutTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final ClassLoader expectedClassLoader,
        final String callbackKey,
        final String handledReturnKey,
        final String nativeBodyEntryKey,
        final String nativeCompletionKey
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methodName = requireText(methodName, "methodName");
        this.descriptor = requireText(descriptor, "descriptor");
        this.expectedClassLoader = expectedClassLoader;
        this.callbackKey = requireText(callbackKey, "callbackKey");
        this.handledReturnKey = Objects.requireNonNull(
            handledReturnKey,
            "handledReturnKey"
        );
        this.nativeBodyEntryKey = Objects.requireNonNull(
            nativeBodyEntryKey,
            "nativeBodyEntryKey"
        );
        this.nativeCompletionKey = Objects.requireNonNull(
            nativeCompletionKey,
            "nativeCompletionKey"
        );
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
                if (!methodName.equals(name) || !descriptor.equals(methodDescriptor)) {
                    return delegate;
                }
                transformed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        final Label callback = new Label();
                        final Label nativePath = new Label();
                        final Label callbackStart = new Label();
                        final Label callbackEnd = new Label();
                        final Label callbackFailure = new Label();
                        visitTryCatchBlock(
                            callbackStart,
                            callbackEnd,
                            callbackFailure,
                            "java/lang/Throwable"
                        );
                        visitLabel(callbackStart);
                        visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "java/lang/System",
                            "getProperties",
                            "()Ljava/util/Properties;",
                            false
                        );
                        visitLdcInsn(callbackKey);
                        visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "java/util/Properties",
                            "get",
                            "(Ljava/lang/Object;)Ljava/lang/Object;",
                            false
                        );
                        visitInsn(Opcodes.DUP);
                        visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Predicate");
                        visitJumpInsn(Opcodes.IFNE, callback);
                        visitInsn(Opcodes.POP);
                        visitJumpInsn(Opcodes.GOTO, callbackEnd);
                        visitLabel(callback);
                        visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate");
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitMethodInsn(
                            Opcodes.INVOKEINTERFACE,
                            "java/util/function/Predicate",
                            "test",
                            "(Ljava/lang/Object;)Z",
                            true
                        );
                        visitJumpInsn(Opcodes.IFEQ, callbackEnd);
                        if (!handledReturnKey.isEmpty()) {
                            emitObserver(handledReturnKey);
                        }
                        visitInsn(Opcodes.ICONST_1);
                        super.visitInsn(Opcodes.IRETURN);
                        visitLabel(callbackEnd);
                        visitJumpInsn(Opcodes.GOTO, nativePath);
                        visitLabel(callbackFailure);
                        visitInsn(Opcodes.POP);
                        visitLabel(nativePath);
                        if (!nativeBodyEntryKey.isEmpty()) {
                            emitObserver(nativeBodyEntryKey);
                        }
                    }

                    @Override
                    public void visitInsn(final int opcode) {
                        if (!nativeCompletionKey.isEmpty()
                            && opcode >= Opcodes.IRETURN
                            && opcode <= Opcodes.RETURN) {
                            emitObserver(nativeCompletionKey);
                        }
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitMaxs(final int maxStack, final int maxLocals) {
                        super.visitMaxs(
                            handledReturnKey.isEmpty()
                                && nativeBodyEntryKey.isEmpty()
                                && nativeCompletionKey.isEmpty()
                                ? maxStack
                                : maxStack + 3,
                            maxLocals
                        );
                    }

                    private void emitObserver(final String propertyKey) {
                        visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "java/lang/System",
                            "getProperties",
                            "()Ljava/util/Properties;",
                            false
                        );
                        visitLdcInsn(propertyKey);
                        visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "java/util/Properties",
                            "get",
                            "(Ljava/lang/Object;)Ljava/lang/Object;",
                            false
                        );
                        final Label completionEnd = new Label();
                        visitInsn(Opcodes.DUP);
                        visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Consumer");
                        visitJumpInsn(Opcodes.IFEQ, completionEnd);
                        visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitMethodInsn(
                            Opcodes.INVOKEINTERFACE,
                            "java/util/function/Consumer",
                            "accept",
                            "(Ljava/lang/Object;)V",
                            true
                        );
                        final Label completionDone = new Label();
                        visitJumpInsn(Opcodes.GOTO, completionDone);
                        visitLabel(completionEnd);
                        visitInsn(Opcodes.POP);
                        visitLabel(completionDone);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        if (transformed[0] && !handledReturnKey.isEmpty()) {
            diag(
                "ingress transform applied owner=" + className
                    + " method=" + methodName
                    + " handledReturnKey=" + handledReturnKey
                    + " nativeBodyEntryKey=" + nativeBodyEntryKey
                    + " nativeCompletionKey=" + nativeCompletionKey
                    + " bytes=" + writer.toByteArray().length
            );
        }
        return transformed[0] ? writer.toByteArray() : null;
    }

    private static void diag(final String message) {
        try {
            final Path home = Path.of(System.getProperty("turboism.home", "."));
            final Path file = home.resolve("logs").resolve("auto-layout-transform.log");
            Files.createDirectories(file.getParent());
            Files.writeString(
                file,
                Instant.now() + " " + message + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
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
