package dev.turboism.ui.appearance.control;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.Set;

/** Hooks exact parameter-row constructor and selection normal returns. */
public final class ParameterRowMethodTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "dev/turboism/ui/appearance/control/NativeParameterAppearanceBridge";
    private final String owner;
    private final Set<MethodSelector> methods;
    private final String callback;
    private final ClassLoader loader;

    public ParameterRowMethodTransformer(
        final String owner,
        final Set<MethodSelector> methods,
        final boolean folder,
        final ClassLoader loader
    ) {
        this.owner = requireText(owner, "owner");
        this.methods = Set.copyOf(Objects.requireNonNull(methods, "methods"));
        if (this.methods.isEmpty()) throw new IllegalArgumentException("methods must not be empty");
        this.callback = folder ? "afterParameterFolder" : "afterParameterRow";
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    public byte[] transform(
        final Module module, final ClassLoader candidateLoader, final String className,
        final Class<?> classBeingRedefined, final ProtectionDomain protectionDomain, final byte[] bytes
    ) {
        if (!owner.equals(className) || candidateLoader != loader || bytes == null) return null;
        final boolean[] changed = {false};
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access, final String name, final String descriptor,
                final String signature, final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!methods.contains(new MethodSelector(name, descriptor))) return delegate;
                changed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, callback, "(Ljava/lang/Object;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return changed[0] ? writer.toByteArray() : null;
    }

    public record MethodSelector(String name, String descriptor) {
        public MethodSelector {
            requireText(name, "name");
            requireText(descriptor, "descriptor");
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
