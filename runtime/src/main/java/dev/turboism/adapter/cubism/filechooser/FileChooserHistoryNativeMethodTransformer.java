package dev.turboism.adapter.cubism.filechooser;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Exact-selector transformer for the Cubism FileChooser save-dialog methods.
 *
 * <p>Only before/after bridge calls are injected: the dialog method body is
 * left untouched (binary write paths are never replaced). {@code before} runs
 * on method entry, {@code after} immediately before every {@code ARETURN}
 * while the returned {@code java/io/File} is still on the stack (DUP + call).
 */
public final class FileChooserHistoryNativeMethodTransformer implements ClassFileTransformer {

    private static final String BRIDGE =
        "dev/turboism/adapter/cubism/filechooser/NativeFileChooserHistoryBridge";

    private final String ownerInternalName;
    private final List<FileChooserHistoryHostProfile.SaveDialogMethod> methods;
    private final ClassLoader expectedClassLoader;
    private final Consumer<FileChooserHistoryHostProfile.SaveDialogMethod> transformedMethod;
    private final boolean validationDialogShim;

    public FileChooserHistoryNativeMethodTransformer(
        final String ownerInternalName,
        final List<FileChooserHistoryHostProfile.SaveDialogMethod> methods,
        final ClassLoader expectedClassLoader
    ) {
        this(ownerInternalName, methods, expectedClassLoader, ignored -> { }, false);
    }

    public FileChooserHistoryNativeMethodTransformer(
        final String ownerInternalName,
        final List<FileChooserHistoryHostProfile.SaveDialogMethod> methods,
        final ClassLoader expectedClassLoader,
        final Consumer<FileChooserHistoryHostProfile.SaveDialogMethod> transformedMethod
    ) {
        this(ownerInternalName, methods, expectedClassLoader, transformedMethod, false);
    }

    public FileChooserHistoryNativeMethodTransformer(
        final String ownerInternalName,
        final List<FileChooserHistoryHostProfile.SaveDialogMethod> methods,
        final ClassLoader expectedClassLoader,
        final Consumer<FileChooserHistoryHostProfile.SaveDialogMethod> transformedMethod,
        final boolean validationDialogShim
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
        if (this.methods.isEmpty()) {
            throw new IllegalArgumentException("methods must not be empty");
        }
        this.expectedClassLoader = expectedClassLoader;
        this.transformedMethod = Objects.requireNonNull(transformedMethod, "transformedMethod");
        this.validationDialogShim = validationDialogShim;
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
        if (classfileBuffer == null
            || (expectedClassLoader != null && loader != expectedClassLoader)
            || !ownerInternalName.equals(className)) {
            return null;
        }
        final boolean[] transformed = {false};
        final List<FileChooserHistoryHostProfile.SaveDialogMethod> matched = new ArrayList<>();
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(
            reader,
            ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
        ) {
            @Override
            protected String getCommonSuperClass(
                final String left,
                final String right
            ) {
                try {
                    final ClassLoader classLoader = loader == null
                        ? FileChooserHistoryNativeMethodTransformer.class.getClassLoader()
                        : loader;
                    final Class<?> leftType = Class.forName(
                        left.replace('/', '.'),
                        false,
                        classLoader
                    );
                    final Class<?> rightType = Class.forName(
                        right.replace('/', '.'),
                        false,
                        classLoader
                    );
                    if (leftType.isAssignableFrom(rightType)) return left;
                    if (rightType.isAssignableFrom(leftType)) return right;
                    if (leftType.isInterface() || rightType.isInterface()) {
                        return "java/lang/Object";
                    }
                    Class<?> current = leftType;
                    do {
                        current = current.getSuperclass();
                    } while (current != null && !current.isAssignableFrom(rightType));
                    return current == null
                        ? "java/lang/Object"
                        : current.getName().replace('.', '/');
                } catch (Throwable ignored) {
                    return "java/lang/Object";
                }
            }
        };
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
                    access, name, descriptor, signature, exceptions
                );
                final FileChooserHistoryHostProfile.SaveDialogMethod method =
                    saveDialogMethod(name, descriptor);
                if (method == null) {
                    return delegate;
                }
                transformed[0] = true;
                matched.add(method);
                return instrument(delegate, method, validationDialogShim);
            }
        }, ClassReader.EXPAND_FRAMES);
        if (!transformed[0]) {
            return null;
        }
        final byte[] transformedBytes = writer.toByteArray();
        for (FileChooserHistoryHostProfile.SaveDialogMethod method : matched) {
            transformedMethod.accept(method);
        }
        dev.turboism.runtime.log.RuntimeDiagnostics.debug(
            "file-chooser",
            "Installed " + matched.size() + " verified file-chooser transforms"
        );
        return transformedBytes;
    }

    private FileChooserHistoryHostProfile.SaveDialogMethod saveDialogMethod(
        final String name,
        final String descriptor
    ) {
        return methods.stream()
            .filter(method -> method.name().equals(name) && method.descriptor().equals(descriptor))
            .findFirst()
            .orElse(null);
    }

    private static MethodVisitor instrument(
        final MethodVisitor delegate,
        final FileChooserHistoryHostProfile.SaveDialogMethod method,
        final boolean validationDialogShim
    ) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                if (validationDialogShim) {
                    this.visitLdcInsn(method.name() + method.descriptor());
                    this.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        BRIDGE,
                        "enterSelector",
                        "(Ljava/lang/String;)V",
                        false
                    );
                }
                this.visitVarInsn(Opcodes.ALOAD, 0);
                this.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    BRIDGE,
                    "onSaveDialogPreparing",
                    "(Ljava/lang/Object;)V",
                    false
                );
            }

            @Override
            public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface
            ) {
                if (validationDialogShim
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && owner.equals("com/live2d/ui/swingImpl/m")
                    && name.equals("showSaveDialog")
                    && descriptor.equals("(Ljava/awt/Component;)I")) {
                    super.visitLdcInsn(method.name() + method.descriptor());
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        BRIDGE,
                        "showSaveDialogForValidation",
                        "(Ljava/lang/Object;Ljava/awt/Component;Ljava/lang/String;)I",
                        false
                    );
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            /**
             * Emits the capture callback immediately before each {@code ARETURN}, so
             * {@code onSaveDialogFinished} observes the chooser once the dialog has produced its
             * result. Every other opcode is passed straight through unchanged.
             *
             * @param opcode the ASM opcode being visited
             */
            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.ARETURN) {
                    this.visitVarInsn(Opcodes.ALOAD, 0);
                    this.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        BRIDGE,
                        "onSaveDialogFinished",
                        "(Ljava/lang/Object;)V",
                        false
                    );
                    if (validationDialogShim) {
                        this.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            BRIDGE,
                            "exitSelector",
                            "()V",
                            false
                        );
                    }
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
