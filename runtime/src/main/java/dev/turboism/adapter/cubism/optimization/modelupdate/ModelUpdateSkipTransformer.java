package dev.turboism.adapter.cubism.optimization.modelupdate;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Rewrites the reviewed model-update entry so unchanged frames return before any update
 * work and completed updates snapshot their inputs afterwards.
 *
 * <p>Two guarded injection sites, both delegated through JDK functional interfaces stored
 * in {@link System#getProperties()}:</p>
 * <ul>
 *   <li>at method entry — {@code Predicate<Object[]>} receiving {@code this} plus every
 *   argument (booleans boxed); {@code true} makes the method return immediately;</li>
 *   <li>before every {@code RETURN} — {@code Consumer<Object>} receiving the
 *   {@code CModel} argument.</li>
 * </ul>
 *
 * <p>Missing or wrongly-typed slots and any callback failure fall through to the
 * unmodified host path. The owner/method pair is admitted only when its shape equals the
 * reviewed {@link ReviewedMethodShape} capture and its protection domain is the exact
 * reviewed artifact.</p>
 */
public final class ModelUpdateSkipTransformer implements ClassFileTransformer {

    private final ModelUpdateSkipTarget target;
    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> shape;
    private volatile String beforeSha256, failure;
    private volatile int matches;

    /**
     * @param loader the host loader this instance is allowed to rewrite
     * @param artifact official artifact, compared to each candidate's code source
     * @param reference official {@link ClassReader#EXPAND_FRAMES} reference bytes
     * @param target the reviewed target for this artifact
     * @throws IllegalArgumentException when the reviewed method is absent from the reference
     */
    public ModelUpdateSkipTransformer(final ClassLoader loader, final Path artifact,
                                      final byte[] reference, final ModelUpdateSkipTarget target) {
        this.target = Objects.requireNonNull(target, "target");
        this.loader = loader;
        this.artifact = artifact == null ? null : artifact.toAbsolutePath().normalize();
        Objects.requireNonNull(reference, "reference");
        shape = ReviewedMethodShape.read(reference, target.owner(),
            ModelUpdateSkipTarget.METHOD, target.methodDescriptor());
        if (shape == null) {
            throw new IllegalArgumentException("reviewed model-update entry absent");
        }
    }

    /** SHA-256 of the first rewritten class bytes for restoration evidence. */
    public String beforeSha256() {
        return beforeSha256;
    }

    /** Rewritten methods observed so far. */
    public int matches() {
        return matches;
    }

    /** Most recent rejection, or null when none has occurred. */
    public String failure() {
        return failure;
    }

    @Override public byte[] transform(final Module module, final ClassLoader actualLoader,
                                      final String name, final Class<?> type,
                                      final ProtectionDomain domain, final byte[] bytes) {
        if (actualLoader != loader || name == null || !target.owner().equals(name) || bytes == null) {
            return null;
        }
        try {
            if (artifact == null || domain == null
                || !artifact.equals(Path.of(domain.getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize())) {
                failure = "model-update entry source is not the reviewed artifact";
                return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(bytes, target.owner(),
                    ModelUpdateSkipTarget.METHOD, target.methodDescriptor()))) {
                failure = "model-update entry method shape mismatch";
                return null;
            }
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() {
                    return loader;
                }
            };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(final int access, final String method,
                                                           final String descriptor,
                                                           final String signature,
                                                           final String[] exceptions) {
                    final MethodVisitor visitor = super.visitMethod(
                        access, method, descriptor, signature, exceptions);
                    if (!ModelUpdateSkipTarget.METHOD.equals(method)
                        || !target.methodDescriptor().equals(descriptor)) {
                        return visitor;
                    }
                    return new EntryVisitor(visitor, descriptor);
                }
            }, ClassReader.EXPAND_FRAMES);
            if (beforeSha256 == null) {
                beforeSha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            }
            matches++;
            return writer.toByteArray();
        } catch (Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private static final class EntryVisitor extends MethodVisitor {
        private final List<Handler> handlers = new ArrayList<>();
        private final Type[] arguments;
        private final int modelSlot;

        EntryVisitor(final MethodVisitor visitor, final String descriptor) {
            super(Opcodes.ASM9, visitor);
            arguments = Type.getArgumentTypes(descriptor);
            int slot = 1;
            int model = -1;
            for (final Type argument : arguments) {
                if ("Lcom/live2d/cubism/doc/model/CModel;".equals(argument.getDescriptor())) {
                    model = slot;
                }
                slot += argument.getSize();
            }
            modelSlot = model;
        }

        @Override public void visitTryCatchBlock(final Label start, final Label end,
                                                 final Label handler, final String type) {
            handlers.add(new Handler(start, end, handler, type));
        }

        @Override public void visitCode() {
            super.visitCode();
            emitEntryGuard();
        }

        @Override public void visitInsn(final int opcode) {
            if (opcode == Opcodes.RETURN) emitAfterUpdate();
            super.visitInsn(opcode);
        }

        @Override public void visitMaxs(final int stack, final int locals) {
            for (final Handler handler : handlers) {
                super.visitTryCatchBlock(handler.start(), handler.end(), handler.target(),
                    handler.type());
            }
            super.visitMaxs(stack, locals);
        }

        /**
         * Emits {@code if (predicate.test(args)) return;} inside a {@code Throwable} guard
         * that always falls through to the unmodified body.
         */
        private void emitEntryGuard() {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label discard = new Label(), nativePath = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                "()Ljava/util/Properties;", false);
            super.visitLdcInsn(ModelUpdateSkipBridge.CALLBACK_PROPERTY);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Predicate");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate");
            emitArgumentsArray();
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test",
                "(Ljava/lang/Object;)Z", true);
            super.visitJumpInsn(Opcodes.IFEQ, nativePath);
            super.visitInsn(Opcodes.RETURN);
            super.visitLabel(end);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, nativePath);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(nativePath);
        }

        /** Emits {@code consumer.accept(model)} inside the same always-fall-through guard. */
        private void emitAfterUpdate() {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label discard = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                "()Ljava/util/Properties;", false);
            super.visitLdcInsn(ModelUpdateSkipBridge.AFTER_PROPERTY);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Consumer");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
            super.visitVarInsn(Opcodes.ALOAD, modelSlot);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept",
                "(Ljava/lang/Object;)V", true);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }

        /**
         * Pushes {@code Object[]{this, arg1..argN}} with boolean arguments boxed through
         * {@link Boolean#valueOf}; the layout is fixed by the reviewed descriptor.
         */
        private void emitArgumentsArray() {
            pushInt(arguments.length + 1);
            super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            super.visitInsn(Opcodes.DUP);
            pushInt(0);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitInsn(Opcodes.AASTORE);
            int local = 1;
            for (int index = 0; index < arguments.length; index++) {
                super.visitInsn(Opcodes.DUP);
                pushInt(index + 1);
                if (arguments[index].equals(Type.BOOLEAN_TYPE)) {
                    super.visitVarInsn(Opcodes.ILOAD, local);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
                } else {
                    super.visitVarInsn(Opcodes.ALOAD, local);
                }
                super.visitInsn(Opcodes.AASTORE);
                local += arguments[index].getSize();
            }
        }

        private void pushInt(final int value) {
            if (value <= 5) {
                super.visitInsn(Opcodes.ICONST_0 + value);
            } else {
                super.visitIntInsn(Opcodes.BIPUSH, value);
            }
        }
    }

    private record Handler(Label start, Label end, Label target, String type) { }
}
