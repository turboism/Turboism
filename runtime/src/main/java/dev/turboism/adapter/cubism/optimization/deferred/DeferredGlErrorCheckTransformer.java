package dev.turboism.adapter.cubism.optimization.deferred;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HexFormat;
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

    /** Production opt-in property set by the launcher when the combined option is on. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.mesaGlThread";

    private static final String GL_OWNER = "com/jogamp/opengl/GL";
    private static final String GL_METHOD = "glGetError";
    private static final String GL_DESCRIPTOR = "()I";

    /** Method-local slots fixed by the non-static {@code (GL,String,Z)} signature. */
    private static final int ERROR_GL_LOCAL = 1;
    private static final int ERROR_CONTEXT_LOCAL = 2;
    private static final int ERROR_THROWING_LOCAL = 3;

    private enum Kind { ERROR_SITE, FRAME_REPORT }
    private record MethodSpec(String owner, String method, String descriptor, Kind kind) { }

    private final ClassLoader loader;
    private final Path artifact;
    private final List<MethodSpec> methods;
    private final Map<String, List<String>> officialShapes = new HashMap<>();
    private final Map<String, List<String>> composedShapes = new HashMap<>();
    private final Map<String, String> beforeSha256 = new HashMap<>();
    private int matches;
    private String failure;
    private Runnable onRejection = () -> { };

    /**
     * Creates the deferred transform from official reference bytes for both
     * reviewed owners. The {@code errorReference} must be the bytes of
     * {@code target.errorOwner()}, the {@code frameReference} the bytes of
     * {@code target.frameOwner()}.
     *
     * @throws IllegalArgumentException when a reviewed method body is absent
     */
    public DeferredGlErrorCheckTransformer(final ClassLoader loader, final Path artifact,
                                           final byte[] errorReference, final byte[] frameReference,
                                           final DeferredGlErrorCheckTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(errorReference, "errorReference");
        Objects.requireNonNull(frameReference, "frameReference");
        methods = List.of(
            new MethodSpec(target.errorOwner(), DeferredGlErrorCheckTarget.ERROR_METHOD,
                DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR, Kind.ERROR_SITE),
            new MethodSpec(target.frameOwner(), DeferredGlErrorCheckTarget.FRAME_METHOD,
                DeferredGlErrorCheckTarget.FRAME_DESCRIPTOR, Kind.FRAME_REPORT));
        for (final MethodSpec spec : methods) {
            final byte[] reference =
                spec.kind() == Kind.ERROR_SITE ? errorReference : frameReference;
            final List<String> shape =
                ReviewedMethodShape.read(reference, spec.owner(), spec.method(), spec.descriptor());
            if (shape == null) {
                throw new IllegalArgumentException("deferred-check method absent: " + spec.owner());
            }
            officialShapes.put(spec.owner(), shape);
            final int sites = errorSites(reference, spec);
            if (spec.kind() == Kind.ERROR_SITE && sites != 1) {
                throw new IllegalArgumentException(
                    "reviewed glGetError call sites != 1: " + spec.owner());
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
    public void onRejection(final Runnable action) { onRejection = Objects.requireNonNull(action); }

    /** Returns the latest rejection, or null. */
    public String failure() { return failure; }
    /** Returns successful class rewrites across both owners. */
    public int matches() { return matches; }
    /** Returns the pre-rewrite class digest per owner for restoration verification. */
    public String beforeSha256(final String owner) { return beforeSha256.get(owner); }

    @Override public byte[] transform(final Module module, final ClassLoader actualLoader,
                                      final String name, final Class<?> type,
                                      final ProtectionDomain domain, final byte[] bytes) {
        if (actualLoader != loader || bytes == null) return null;
        final MethodSpec spec = spec(name);
        if (spec == null) return null;
        try {
            if (domain == null || domain.getCodeSource() == null || !artifact.equals(
                    Path.of(domain.getCodeSource().getLocation().toURI())
                        .toAbsolutePath().normalize())) {
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
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(final int access, final String method,
                                                           final String descriptor,
                                                           final String signature,
                                                           final String[] exceptions) {
                    if (!method.equals(spec.method()) || !descriptor.equals(spec.descriptor())) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(final int opcode, final String owner,
                                                              final String invoked,
                                                              final String desc,
                                                              final boolean itf) {
                            if (spec.kind() == Kind.ERROR_SITE
                                && errorQuery(opcode, owner, invoked, desc, itf)) scan[0]++;
                        }
                        @Override public void visitInsn(final int opcode) {
                            if (spec.kind() == Kind.FRAME_REPORT && opcode == Opcodes.RETURN) {
                                scan[0]++;
                            }
                        }
                        @Override public void visitMaxs(final int stack, final int maxLocals) {
                            scan[1] = maxLocals;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
            if (scan[0] < 1) {
                throw new IllegalArgumentException(
                    "deferred-check emission point absent: " + spec.owner());
            }
            if (spec.kind() == Kind.ERROR_SITE && scan[0] != 1) {
                throw new IllegalArgumentException("deferred-check error sites != 1: " + spec.owner());
            }
            final int deferredLocal = scan[1];
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            final int[] applied = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(final int access, final String method,
                                                           final String descriptor,
                                                           final String signature,
                                                           final String[] exceptions) {
                    final MethodVisitor original =
                        super.visitMethod(access, method, descriptor, signature, exceptions);
                    return method.equals(spec.method()) && descriptor.equals(spec.descriptor())
                        ? new DeferredVisitor(original, spec, deferredLocal, applied)
                        : original;
                }
            }, ClassReader.EXPAND_FRAMES);
            if (applied[0] != (spec.kind() == Kind.ERROR_SITE ? 1 : scan[0])) {
                throw new IllegalArgumentException("deferred-check emission incomplete: " + spec.owner());
            }
            final byte[] result = writer.toByteArray();
            beforeSha256.putIfAbsent(spec.owner(), HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)));
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

    private static boolean errorQuery(final int opcode, final String owner, final String method,
                                      final String desc, final boolean itf) {
        return opcode == Opcodes.INVOKEINTERFACE && itf && GL_OWNER.equals(owner)
            && GL_METHOD.equals(method) && GL_DESCRIPTOR.equals(desc);
    }

    private static int errorSites(final byte[] bytes, final MethodSpec spec) {
        final int[] count = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(final int access, final String name,
                                                       final String descriptor,
                                                       final String signature,
                                                       final String[] exceptions) {
                if (!name.equals(spec.method()) || !descriptor.equals(spec.descriptor())) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(final int opcode, final String owner,
                                                          final String method, final String desc,
                                                          final boolean itf) {
                        if (errorQuery(opcode, owner, method, desc, itf)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
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
    private static final class DeferredVisitor extends MethodVisitor {
        private final MethodSpec spec;
        private final int deferredLocal;
        private final int[] applied;
        DeferredVisitor(final MethodVisitor original, final MethodSpec spec,
                        final int deferredLocal, final int[] applied) {
            super(Opcodes.ASM9, original);
            this.spec = spec;
            this.deferredLocal = deferredLocal;
            this.applied = applied;
        }
        @Override public void visitMethodInsn(final int opcode, final String owner,
                                              final String method, final String descriptor,
                                              final boolean itf) {
            if (spec.kind() != Kind.ERROR_SITE || !errorQuery(opcode, owner, method, descriptor, itf)) {
                super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                return;
            }
            // Stack: [gl]. Park it so the callback arguments order (mh, gl, ctx, z).
            super.visitVarInsn(Opcodes.ASTORE, deferredLocal);
            final Label start = new Label(), end = new Label(), miss = new Label(),
                failure = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                "()Ljava/util/Properties;", false);
            super.visitLdcInsn(UniformLocationHookBridge.DEFER_QUERY_PROPERTY);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/invoke/MethodHandle");
            super.visitJumpInsn(Opcodes.IFEQ, miss);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/invoke/MethodHandle");
            super.visitVarInsn(Opcodes.ALOAD, deferredLocal);
            super.visitVarInsn(Opcodes.ALOAD, ERROR_CONTEXT_LOCAL);
            super.visitVarInsn(Opcodes.ILOAD, ERROR_THROWING_LOCAL);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
                "invokeExact", "(Ljava/lang/Object;Ljava/lang/String;Z)I", false);
            super.visitLabel(end);
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
        @Override public void visitInsn(final int opcode) {
            if (spec.kind() == Kind.FRAME_REPORT && opcode == Opcodes.RETURN) {
                emitReport();
                applied[0]++;
            }
            super.visitInsn(opcode);
        }
        /**
         * Emits a guarded {@code consumeReport()} consult: when the bridge armed
         * a throwable (the frame-end query observed a nonzero error at a
         * throwing checkpoint), it is rethrown through the host's own frame
         * return path; a missing or failed callback consults nothing.
         */
        private void emitReport() {
            final Label start = new Label(), end = new Label(), miss = new Label(),
                failure = new Label(), have = new Label(), empty = new Label();
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                "()Ljava/util/Properties;", false);
            super.visitLdcInsn(UniformLocationHookBridge.DEFER_REPORT_PROPERTY);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/invoke/MethodHandle");
            super.visitJumpInsn(Opcodes.IFEQ, miss);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/invoke/MethodHandle");
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
                "invokeExact", "()Ljava/lang/Object;", false);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, have);
            super.visitLabel(miss);
            super.visitInsn(Opcodes.POP);
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitJumpInsn(Opcodes.GOTO, have);
            super.visitLabel(failure);
            super.visitInsn(Opcodes.POP);
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitLabel(have);
            super.visitInsn(Opcodes.DUP);
            super.visitJumpInsn(Opcodes.IFNULL, empty);
            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Throwable");
            super.visitInsn(Opcodes.ATHROW);
            super.visitLabel(empty);
            super.visitInsn(Opcodes.POP);
        }
    }
}
