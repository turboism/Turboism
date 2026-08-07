package dev.turboism.adapter.cubism.filechooser;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Objects;

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

    public FileChooserHistoryNativeMethodTransformer(
        final String ownerInternalName,
        final List<FileChooserHistoryHostProfile.SaveDialogMethod> methods,
        final ClassLoader expectedClassLoader
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
        if (this.methods.isEmpty()) {
            throw new IllegalArgumentException("methods must not be empty");
        }
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
        if (classfileBuffer == null
            || (expectedClassLoader != null && loader != expectedClassLoader)
            || !ownerInternalName.equals(className)) {
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
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(
                    access, name, descriptor, signature, exceptions
                );
                if (!isSaveDialogMethod(name, descriptor)) {
                    return delegate;
                }
                transformed[0] = true;
                System.out.println("FILE-CHOOSER-TRANSFORM:class=" + className
                    + " method=" + name + " desc=" + descriptor);
                return instrument(delegate);
            }
        }, ClassReader.EXPAND_FRAMES);
        return transformed[0] ? writer.toByteArray() : null;
    }

    private boolean isSaveDialogMethod(final String name, final String descriptor) {
        return methods.stream()
            .anyMatch(method -> method.name().equals(name) && method.descriptor().equals(descriptor));
    }

    private static MethodVisitor instrument(final MethodVisitor delegate) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                this.visitVarInsn(Opcodes.ALOAD, 0);
                this.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    BRIDGE,
                    "onSaveDialogPreparing",
                    "(Ljava/lang/Object;)V",
                    false
                );
            }

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
