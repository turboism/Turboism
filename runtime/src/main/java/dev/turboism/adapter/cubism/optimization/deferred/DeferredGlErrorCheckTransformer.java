package dev.turboism.adapter.cubism.optimization.deferred;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Deferred GL error checking — the production replacement for the test-only
 * {@code glGetError} elision experiment. The obfuscated shader error-check
 * {@code shader/A.a(GL,String,Z)} (or {@code shader/y.a} on 5.2.03) runs a
 * synchronous {@code glGetError} per call; under a threaded GL submission
 * driver each call forces a flush. This rewrite turns every in-frame call
 * into a checkpoint that returns {@code GL_NO_ERROR} without querying, and
 * emits a single real {@code glGetError} at the frame boundary reported by
 * the bridge's render scope ({@code SGFramework/g.render3d}). The frame-end
 * result is then reported through the host-equivalent path and fed to the
 * uniform-location cache's conservative confirm/invalidate logic.
 *
 * <p>Out-of-frame invocations — or any frame the lifecycle seam did not
 * observe — pass through to the real {@code glGetError}, so non-scoped error
 * checking keeps its exact upstream semantics. The exact error location is
 * intentionally lost (the frame, not the call site, owns the report); errors
 * themselves are never swallowed.</p>
 *
 * <p>Admission is shape-exact on both owners: the official reference shape,
 * or — when the upstream uniform-location lifecycle transform is installed —
 * its exact reviewed output shape, computed by the verified installer from
 * the attested reference bytes. Any other shape fails closed.</p>
 */
public final class DeferredGlErrorCheckTransformer implements ClassFileTransformer {

    /** Production preference property controlling deferred GL error checks. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.mesaGlThread";

    private static volatile boolean configuredDefault = true;

    /**
     * Updates the resolved startup default used when {@link #ENABLE_PROPERTY} is unset.
     *
     * @param enabled resolved configuration preference
     */
    public static void setConfiguredDefault(final boolean enabled) {
        configuredDefault = enabled;
    }

    /**
     * Returns whether deferred GL error checking is enabled by system property or startup default.
     *
     * @return {@code true} when enabled
     */
    public static boolean enabledByPreference() {
        final String explicit = System.getProperty(ENABLE_PROPERTY);
        return explicit == null ? configuredDefault : Boolean.parseBoolean(explicit);
    }

    private static final String GL_OWNER = "com/jogamp/opengl/GL";
    private static final String GL_METHOD = "glGetError";
    private static final String GL_DESCRIPTOR = "()I";
    private static final String ATOMIC_BOOLEAN_OWNER = "java/util/concurrent/atomic/AtomicBoolean";
    private static final String ATOMIC_BOOLEAN_GET_METHOD = "get";
    private static final String BOOLEAN_DESCRIPTOR = "()Z";
    private static final String METHOD_HANDLE_OWNER = "java/lang/invoke/MethodHandle";
    private static final String BEGIN_DESCRIPTOR = "(Ljava/lang/Object;)J";
    private static final String END_DESCRIPTOR = "(J)V";
    private static final int FRAME_SCOPE_ARG_LOCAL = 1;
    private static final int FRAME_MIN_STACK = 4;
    private static final int FRAME_SCOPE_EXTRA_SLOTS = 3;

    /** Method-local slots fixed by the non-static {@code (GL,String,Z)} signature. */
    private static final int ERROR_GL_LOCAL = 1;

    private static final int ERROR_CONTEXT_LOCAL = 2;
    private static final int ERROR_THROWING_LOCAL = 3;

    private enum Kind {
        ERROR_SITE,
        FRAME_REPORT
    }

    private record MethodSpec(String owner, String method, String descriptor, Kind kind) {}

    private final ClassLoader loader;
    private final Path artifact;
    private final boolean wrapFrameScope;
    private final List<MethodSpec> methods;
    private final Map<String, List<String>> officialShapes = new HashMap<>();
    private final Map<String, List<String>> composedShapes = new HashMap<>();

    private int matches;
    private String failure;
    private Runnable onRejection = () -> {};

    /**
     * Creates the deferred transform in composed mode, assuming an upstream lifecycle hook owns
     * the render-scope bracket on {@code target.frameOwner()}.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param errorReference reference bytes of {@code target.errorOwner()}
     * @param frameReference reference bytes of {@code target.frameOwner()}
     * @param target reviewed deferred-check targets
     * @throws IllegalArgumentException when a reviewed method body is absent
     */
    public DeferredGlErrorCheckTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] errorReference,
            final byte[] frameReference,
            final DeferredGlErrorCheckTarget target) {
        this(loader, artifact, errorReference, frameReference, target, false);
    }

    /**
     * Creates the deferred transform from official reference bytes for both reviewed owners.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param errorReference reference bytes of {@code target.errorOwner()}
     * @param frameReference reference bytes of {@code target.frameOwner()}
     * @param target reviewed deferred-check targets
     * @param wrapFrameScope whether to emit standalone render-scope begin/end brackets on {@code render3d}
     * @throws IllegalArgumentException when a reviewed method body is absent
     */
    public DeferredGlErrorCheckTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] errorReference,
            final byte[] frameReference,
            final DeferredGlErrorCheckTarget target,
            final boolean wrapFrameScope) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.wrapFrameScope = wrapFrameScope;
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(errorReference, "errorReference");
        Objects.requireNonNull(frameReference, "frameReference");
        methods = List.of(
                new MethodSpec(
                        target.errorOwner(),
                        DeferredGlErrorCheckTarget.ERROR_METHOD,
                        DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR,
                        Kind.ERROR_SITE),
                new MethodSpec(
                        target.frameOwner(),
                        DeferredGlErrorCheckTarget.FRAME_METHOD,
                        DeferredGlErrorCheckTarget.FRAME_DESCRIPTOR,
                        Kind.FRAME_REPORT));
        for (final MethodSpec spec : methods) {
            final byte[] reference = spec.kind() == Kind.ERROR_SITE ? errorReference : frameReference;
            final List<String> shape =
                    ReviewedMethodShape.read(reference, spec.owner(), spec.method(), spec.descriptor());
            if (shape == null) {
                throw new IllegalArgumentException("deferred-check method absent: " + spec.owner());
            }
            officialShapes.put(spec.owner(), shape);
            final int sites = errorSites(reference, spec);
            if (spec.kind() == Kind.ERROR_SITE && sites != 1) {
                throw new IllegalArgumentException("reviewed glGetError call sites != 1: " + spec.owner());
            }
        }
    }

    /**
     * Additionally admits the exact reviewed shape produced by the upstream
     * uniform-location lifecycle transform for the given owner — computed by
     * the installer from the attested reference bytes. Anything else still
     * fails the official-shape check.
     */
    public void acceptComposedShape(final String owner, final List<String> shape) {
        composedShapes.put(Objects.requireNonNull(owner, "owner"), List.copyOf(shape));
    }

    /** Registers a fail-closed action before installing this transformer. */
    public void onRejection(final Runnable action) {
        onRejection = Objects.requireNonNull(action);
    }

    /** Returns the latest rejection, or null. */
    public String failure() {
        return failure;
    }
    /** Returns successful class rewrites across both owners. */
    public int matches() {
        return matches;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (actualLoader != loader || bytes == null) return null;
        final MethodSpec spec = spec(name);
        if (spec == null) return null;
        try {
            if (!HostCodeSource.matchesArtifact(domain, artifact)) {
                throw new IllegalArgumentException("deferred-check artifact mismatch");
            }
            final List<String> observed =
                    ReviewedMethodShape.read(bytes, spec.owner(), spec.method(), spec.descriptor());
            if (!officialShapes.get(spec.owner()).equals(observed)
                    && !composedShapes.getOrDefault(spec.owner(), List.of()).equals(observed)) {
                throw new IllegalArgumentException("deferred-check shape mismatch: " + spec.owner());
            }
            // Pass 1: count the kind-specific emission points (the glGetError
            // site for ERROR_SITE, RETURN opcodes for FRAME_REPORT) and read
            // the input body's local ceiling for the receiver park slot.
            final int[] scan = {0, 0};
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String method,
                                        final String descriptor,
                                        final String signature,
                                        final String[] exceptions) {
                                    if (!method.equals(spec.method()) || !descriptor.equals(spec.descriptor())) {
                                        return null;
                                    }
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int opcode,
                                                final String owner,
                                                final String invoked,
                                                final String desc,
                                                final boolean itf) {
                                            if (spec.kind() == Kind.ERROR_SITE
                                                    && errorQuery(opcode, owner, invoked, desc, itf)) scan[0]++;
                                        }

                                        @Override
                                        public void visitInsn(final int opcode) {
                                            if (spec.kind() == Kind.FRAME_REPORT && opcode == Opcodes.RETURN) {
                                                scan[0]++;
                                            }
                                        }

                                        @Override
                                        public void visitMaxs(final int stack, final int maxLocals) {
                                            scan[1] = maxLocals;
                                        }
                                    };
                                }
                            },
                            ClassReader.SKIP_DEBUG);
            if (scan[0] < 1) {
                throw new IllegalArgumentException("deferred-check emission point absent: " + spec.owner());
            }
            if (spec.kind() == Kind.ERROR_SITE && scan[0] != 1) {
                throw new IllegalArgumentException("deferred-check error sites != 1: " + spec.owner());
            }
            final int deferredLocal = scan[1];
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
            final int[] applied = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String method,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor original =
                                    super.visitMethod(access, method, descriptor, signature, exceptions);
                            return method.equals(spec.method()) && descriptor.equals(spec.descriptor())
                                    ? new DeferredVisitor(
                                            original,
                                            spec,
                                            deferredLocal,
                                            wrapFrameScope && spec.kind() == Kind.FRAME_REPORT,
                                            applied)
                                    : original;
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (applied[0] != (spec.kind() == Kind.ERROR_SITE ? 1 : scan[0])) {
                throw new IllegalArgumentException("deferred-check emission incomplete: " + spec.owner());
            }
            final byte[] result = writer.toByteArray();

            matches++;
            return result;
        } catch (Exception | LinkageError problem) {
            failure = problem.toString();
            onRejection.run();
            return null;
        }
    }

    private MethodSpec spec(final String owner) {
        for (final MethodSpec spec : methods) {
            if (spec.owner().equals(owner)) return spec;
        }
        return null;
    }

    private static boolean errorQuery(
            final int opcode, final String owner, final String method, final String desc, final boolean itf) {
        return opcode == Opcodes.INVOKEINTERFACE
                && itf
                && GL_OWNER.equals(owner)
                && GL_METHOD.equals(method)
                && GL_DESCRIPTOR.equals(desc);
    }

    private static int errorSites(final byte[] bytes, final MethodSpec spec) {
        final int[] count = {0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!name.equals(spec.method()) || !descriptor.equals(spec.descriptor())) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String method,
                                            final String desc,
                                            final boolean itf) {
                                        if (errorQuery(opcode, owner, method, desc, itf)) count[0]++;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    /**
     * ERROR_SITE: replaces {@code invokeinterface GL.glGetError()I} with a
     * guarded {@code deferQuery(gl, context, throwing)} dispatch whose result
     * keeps the exact operand-stack shape of the native call; when the bridge
     * callback is absent or fails, the real {@code glGetError} runs inline —
     * identical to the unmodified site. FRAME_REPORT: before every
     * {@code RETURN}, emits a guarded {@code consumeReport()} consult whose
     * non-null result is thrown — after the upstream lifecycle transform's
     * frame-end emission, which is earlier in the instruction stream.
     */
    private record Handler(Label start, Label end, Label target, String type) {}

    private static final class DeferredVisitor extends MethodVisitor {
        private final MethodSpec spec;
        private final int deferredLocal;
        private final boolean frameScope;
        private final int[] applied;
        private final List<Handler> originalHandlers = new ArrayList<>();
        private final Label bodyStart = new Label();
        private final Label bodyEnd = new Label();
        private final Label exceptionalExit = new Label();

        DeferredVisitor(
                final MethodVisitor original,
                final MethodSpec spec,
                final int deferredLocal,
                final boolean frameScope,
                final int[] applied) {
            super(Opcodes.ASM9, original);
            this.spec = spec;
            this.deferredLocal = deferredLocal;
            this.frameScope = frameScope;
            this.applied = applied;
        }

        @Override
        public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
            if (frameScope) {
                originalHandlers.add(new Handler(start, end, handler, type));
            } else {
                super.visitTryCatchBlock(start, end, handler, type);
            }
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (frameScope) {
                super.visitInsn(Opcodes.LCONST_0);
                super.visitVarInsn(Opcodes.LSTORE, deferredLocal);
                guardedCallback(
                        UniformLocationHookBridge.BEGIN_PROPERTY,
                        BEGIN_DESCRIPTOR,
                        () -> super.visitVarInsn(Opcodes.ALOAD, FRAME_SCOPE_ARG_LOCAL),
                        () -> super.visitVarInsn(Opcodes.LSTORE, deferredLocal),
                        () -> {});
                super.visitLabel(bodyStart);
            }
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String method, final String descriptor, final boolean itf) {
            if (spec.kind() != Kind.ERROR_SITE || !errorQuery(opcode, owner, method, descriptor, itf)) {
                super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                return;
            }
            // Stack: [gl]. Park it so the callback arguments order (mh, gl, ctx, z).
            super.visitVarInsn(Opcodes.ASTORE, deferredLocal);
            final Label inactive = new Label(), slowPath = new Label();
            SlotBridgeEmitter.emitGetSlot(this, UniformLocationHookBridge.DEFER_ACTIVE_PROPERTY);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, ATOMIC_BOOLEAN_OWNER);
            super.visitJumpInsn(Opcodes.IFEQ, inactive);
            super.visitTypeInsn(Opcodes.CHECKCAST, ATOMIC_BOOLEAN_OWNER);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, ATOMIC_BOOLEAN_OWNER, ATOMIC_BOOLEAN_GET_METHOD, BOOLEAN_DESCRIPTOR, false);
            super.visitJumpInsn(Opcodes.IFEQ, slowPath);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitInsn(Opcodes.IRETURN);
            super.visitLabel(inactive);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(slowPath);
            final Label start = new Label(),
                    end = new Label(),
                    miss = new Label(),
                    failure = new Label(),
                    deferred = new Label(),
                    done = new Label();
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            super.visitLabel(start);
            SlotBridgeEmitter.emitGetSlot(this, UniformLocationHookBridge.DEFER_QUERY_PROPERTY);
            this.visitInsn(Opcodes.DUP);
            this.visitTypeInsn(Opcodes.INSTANCEOF, METHOD_HANDLE_OWNER);
            super.visitJumpInsn(Opcodes.IFEQ, miss);
            super.visitTypeInsn(Opcodes.CHECKCAST, METHOD_HANDLE_OWNER);
            super.visitVarInsn(Opcodes.ALOAD, deferredLocal);
            super.visitVarInsn(Opcodes.ALOAD, ERROR_CONTEXT_LOCAL);
            super.visitVarInsn(Opcodes.ILOAD, ERROR_THROWING_LOCAL);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    METHOD_HANDLE_OWNER,
                    "invokeExact",
                    "(Ljava/lang/Object;Ljava/lang/String;Z)I",
                    false);
            super.visitLabel(end);
            // The callback never runs the real query itself: DEFERRED_FALLBACK
            // means the checkpoint is out of scope and the emitted code runs
            // glGetError inline — the native call executes exactly once. This
            // inline call sits outside the guarded region, so its own
            // exception propagates exactly like the original site's.
            super.visitInsn(Opcodes.DUP);
            super.visitLdcInsn(UniformLocationHookBridge.DEFERRED_FALLBACK);
            super.visitJumpInsn(Opcodes.IF_ICMPNE, deferred);
            super.visitInsn(Opcodes.POP);
            realQuery();
            super.visitLabel(deferred);
            super.visitJumpInsn(Opcodes.GOTO, done);
            // Callback absent or failed: run the real error query inline.
            super.visitLabel(miss);
            super.visitInsn(Opcodes.POP);
            realQuery();
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(failure);
            super.visitInsn(Opcodes.POP);
            realQuery();
            super.visitLabel(done);
            applied[0]++;
        }

        private void realQuery() {
            super.visitVarInsn(Opcodes.ALOAD, deferredLocal);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, GL_OWNER, GL_METHOD, GL_DESCRIPTOR, true);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (spec.kind() == Kind.FRAME_REPORT && opcode == Opcodes.RETURN) {
                if (frameScope) {
                    endScope();
                }
                emitReport();
                applied[0]++;
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(final int stack, final int localCount) {
            if (frameScope) {
                super.visitLabel(bodyEnd);
                super.visitLabel(exceptionalExit);
                final int exceptionLocal = deferredLocal + 2;
                super.visitVarInsn(Opcodes.ASTORE, exceptionLocal);
                endScope();
                super.visitVarInsn(Opcodes.ALOAD, exceptionLocal);
                super.visitInsn(Opcodes.ATHROW);
                for (final Handler handler : originalHandlers) {
                    super.visitTryCatchBlock(handler.start(), handler.end(), handler.target(), handler.type());
                }
                super.visitTryCatchBlock(bodyStart, bodyEnd, exceptionalExit, null);
                super.visitMaxs(
                        Math.max(stack, FRAME_MIN_STACK),
                        Math.max(localCount, deferredLocal + FRAME_SCOPE_EXTRA_SLOTS));
                return;
            }
            super.visitMaxs(stack, localCount);
        }

        private void endScope() {
            guardedCallback(
                    UniformLocationHookBridge.END_PROPERTY,
                    END_DESCRIPTOR,
                    () -> super.visitVarInsn(Opcodes.LLOAD, deferredLocal),
                    () -> {},
                    () -> {});
        }

        /**
         * Emits a guarded {@code consumeReport()} consult: when the bridge armed
         * a throwable (the frame-end query observed a nonzero error at a
         * throwing checkpoint), it is rethrown through the host's own frame
         * return path; a missing or failed callback consults nothing.
         */
        private void emitReport() {
            final Label have = new Label(), empty = new Label();
            guardedCallback(
                    UniformLocationHookBridge.DEFER_REPORT_PROPERTY,
                    "()Ljava/lang/Object;",
                    () -> {},
                    () -> {},
                    () -> super.visitInsn(Opcodes.ACONST_NULL));
            super.visitLabel(have);
            super.visitInsn(Opcodes.DUP);
            super.visitJumpInsn(Opcodes.IFNULL, empty);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Throwable");
            super.visitInsn(Opcodes.ATHROW);
            super.visitLabel(empty);
            super.visitInsn(Opcodes.POP);
        }

        private void guardedCallback(
                final String property,
                final String signature,
                final Runnable arguments,
                final Runnable consumeResult,
                final Runnable fallbackResult) {
            final Label start = new Label(),
                    end = new Label(),
                    miss = new Label(),
                    failure = new Label(),
                    done = new Label();
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            super.visitLabel(start);
            SlotBridgeEmitter.emitGetSlot(this, property);
            this.visitInsn(Opcodes.DUP);
            this.visitTypeInsn(Opcodes.INSTANCEOF, METHOD_HANDLE_OWNER);
            super.visitJumpInsn(Opcodes.IFEQ, miss);
            super.visitTypeInsn(Opcodes.CHECKCAST, METHOD_HANDLE_OWNER);
            arguments.run();
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, "invokeExact", signature, false);
            consumeResult.run();
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(miss);
            super.visitInsn(Opcodes.POP);
            fallbackResult.run();
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(failure);
            super.visitInsn(Opcodes.POP);
            fallbackResult.run();
            super.visitLabel(done);
        }
    }
}
