package dev.turboism.adapter.cubism.optimization.modelinstances;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Adds callback guards to the reviewed native seams used by the model-instance coalescer.
 *
 * <p>Three exact owners are rewritten, each only after its method shape matches the
 * official reference bytes supplied by the installer:</p>
 * <ul>
 *   <li>{@code CModelSource.updateModelInstances()V} — entry predicate may skip an
 *       exact duplicate; a normal return publishes the token;</li>
 *   <li>{@code ACEditMode.updateLastModifiedTime()V} — entry captures the committing
 *       edit group; a normal return publishes it;</li>
 *   <li>{@code CUndoManager.undo()V}/{@code redo()V} — entry invalidates cached tokens.</li>
 * </ul>
 *
 * <p>Every injected call reads a JDK functional-interface value from
 * {@link System#getProperties()} and is wrapped in a {@code Throwable} guard that falls
 * through to the unmodified body. Missing, wrongly-typed or throwing slots therefore
 * leave native behaviour unchanged.</p>
 */
public final class ModelInstanceCoalesceTransformer implements ClassFileTransformer {

    private final ClassLoader loader;
    private final Path artifact;
    private final Map<String, ClassSpec> specs;
    private final Map<String, String> beforeSha256 = new ConcurrentHashMap<>();
    private final AtomicInteger matches = new AtomicInteger();
    private volatile String failure;

    /**
     * @param loader admitted host loader
     * @param artifact exact official artifact used for code-source attestation
     * @param sourceReference official {@code CModelSource} bytes
     * @param editModeReference official {@code ACEditMode} bytes
     * @param undoManagerReference official {@code CUndoManager} bytes
     */
    public ModelInstanceCoalesceTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] sourceReference,
            final byte[] editModeReference,
            final byte[] undoManagerReference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        final Map<String, ClassSpec> built = new LinkedHashMap<>();
        built.put(
                ModelInstanceCoalesceTarget.SOURCE_OWNER,
                spec(
                        sourceReference,
                        new Injection(
                                ModelInstanceCoalesceTarget.UPDATE_METHOD,
                                "()V",
                                ModelInstanceCoalesceBridge.UPDATE_CALLBACK_PROPERTY,
                                true,
                                ModelInstanceCoalesceBridge.AFTER_UPDATE_PROPERTY)));
        built.put(
                ModelInstanceCoalesceTarget.EDIT_MODE_OWNER,
                spec(
                        editModeReference,
                        new Injection(
                                ModelInstanceCoalesceTarget.COMMIT_METHOD,
                                "()V",
                                ModelInstanceCoalesceBridge.COMMIT_CALLBACK_PROPERTY,
                                false,
                                ModelInstanceCoalesceBridge.AFTER_COMMIT_PROPERTY)));
        built.put(
                ModelInstanceCoalesceTarget.UNDO_MANAGER_OWNER,
                spec(
                        undoManagerReference,
                        new Injection(
                                ModelInstanceCoalesceTarget.UNDO_METHOD,
                                "()V",
                                ModelInstanceCoalesceBridge.INVALIDATE_CALLBACK_PROPERTY,
                                false,
                                null),
                        new Injection(
                                ModelInstanceCoalesceTarget.REDO_METHOD,
                                "()V",
                                ModelInstanceCoalesceBridge.INVALIDATE_CALLBACK_PROPERTY,
                                false,
                                null)));
        specs = Map.copyOf(built);
    }

    private static ClassSpec spec(final byte[] reference, final Injection... injections) {
        Objects.requireNonNull(reference, "reference");
        final ClassReader reader = new ClassReader(reference);
        final String owner = reader.getClassName();
        final Map<String, List<String>> shapes = new LinkedHashMap<>();
        final Map<String, Injection> byMethod = new LinkedHashMap<>();
        for (final Injection injection : injections) {
            final String key = injection.method() + injection.descriptor();
            if (byMethod.put(key, injection) != null) {
                throw new IllegalArgumentException("duplicate injection " + key);
            }
            final List<String> shape =
                    ReviewedMethodShape.read(reference, owner, injection.method(), injection.descriptor());
            if (shape == null) {
                throw new IllegalArgumentException(
                        "reviewed coalescer seam absent: " + owner + "." + injection.method());
            }
            shapes.put(key, shape);
        }
        return new ClassSpec(owner, Map.copyOf(shapes), Map.copyOf(byMethod));
    }

    /** SHA-256 of each rewritten class before this transformer touched it. */
    public Map<String, String> beforeSha256() {
        return Map.copyOf(beforeSha256);
    }

    /** Rewritten methods observed so far. */
    public int matches() {
        return matches.get();
    }

    /** Most recent rejection, or null when none has occurred. */
    public String failure() {
        return failure;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (actualLoader != loader || name == null || bytes == null) return null;
        final ClassSpec spec = specs.get(name);
        if (spec == null) return null;
        try {
            if (domain == null
                    || !artifact.equals(
                            Path.of(domain.getCodeSource().getLocation().toURI())
                                    .toAbsolutePath()
                                    .normalize())) {
                failure = "coalescer seam source is not the reviewed artifact: " + name;
                return null;
            }
            for (final Map.Entry<String, List<String>> expected : spec.shapes().entrySet()) {
                final Injection injection = spec.byMethod().get(expected.getKey());
                if (!expected.getValue()
                        .equals(ReviewedMethodShape.read(
                                bytes, spec.owner(), injection.method(), injection.descriptor()))) {
                    failure = "coalescer seam shape mismatch: " + name + "." + injection.method();
                    return null;
                }
            }
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader;
                }
            };
            final int[] transformed = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String method,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor visitor =
                                    super.visitMethod(access, method, descriptor, signature, exceptions);
                            final Injection injection = spec.byMethod().get(method + descriptor);
                            if (injection == null) return visitor;
                            transformed[0]++;
                            return new InjectionVisitor(visitor, injection);
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (transformed[0] != spec.byMethod().size()) {
                failure = "coalescer seam cardinality mismatch: " + name;
                return null;
            }
            beforeSha256.putIfAbsent(
                    name,
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            matches.addAndGet(transformed[0]);
            return writer.toByteArray();
        } catch (Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private record ClassSpec(String owner, Map<String, List<String>> shapes, Map<String, Injection> byMethod) {}

    private record Injection(
            String method, String descriptor, String entryKey, boolean entryPredicate, String afterKey) {}

    private static final class InjectionVisitor extends MethodVisitor {
        private final Injection injection;
        private final List<Handler> handlers = new ArrayList<>();

        InjectionVisitor(final MethodVisitor visitor, final Injection injection) {
            super(Opcodes.ASM9, visitor);
            this.injection = injection;
        }

        @Override
        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
            handlers.add(new Handler(start, end, handler, type));
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (injection.entryPredicate()) {
                emitEntryPredicate();
            } else {
                emitEntryConsumer();
            }
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.RETURN && injection.afterKey() != null) emitAfterConsumer();
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(final int stack, final int locals) {
            for (final Handler handler : handlers) {
                super.visitTryCatchBlock(handler.start(), handler.end(), handler.target(), handler.type());
            }
            super.visitMaxs(stack + 4, locals);
        }

        /** Emits {@code if (predicate.test(this)) return;} inside a Throwable guard. */
        private void emitEntryPredicate() {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label discard = new Label(), nativePath = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            emitSlotLookup(injection.entryKey());
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Predicate");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate");
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test", "(Ljava/lang/Object;)Z", true);
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

        /** Emits {@code consumer.accept(this);} inside a Throwable guard. */
        private void emitEntryConsumer() {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label discard = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            emitSlotLookup(injection.entryKey());
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Consumer");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }

        /** Emits {@code consumer.accept(this);} immediately before a native return. */
        private void emitAfterConsumer() {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label discard = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            emitSlotLookup(injection.afterKey());
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Consumer");
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }

        private void emitSlotLookup(final String key) {
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
            super.visitLdcInsn(key);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/util/Properties",
                    "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
        }
    }

    private record Handler(Label start, Label end, Label target, String type) {}
}
