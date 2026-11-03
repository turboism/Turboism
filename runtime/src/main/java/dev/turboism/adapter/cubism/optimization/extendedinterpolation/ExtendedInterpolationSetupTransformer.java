package dev.turboism.adapter.cubism.optimization.extendedinterpolation;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites the two forced extended-interpolation rebuilds in {@code CModelSource.setup()}.
 *
 * <p>Each reviewed call site pushes the four default arguments
 * ({@code ICONST_0 ICONST_0 ICONST_3 ACONST_NULL}) and calls
 * {@code CExtendedInterpolationExtension.setupFormStructure$default}. The transform drops those
 * four arguments and, in place, consults {@code Function<Object, Object>} in
 * {@link System#getProperties()}: {@link Boolean#TRUE} means the bridge already applied the host's
 * own incremental update and the forced rebuild is skipped; anything else — no slot, wrong type,
 * a thrown error or a non-{@code TRUE} answer — executes the original call.</p>
 *
 * <p>Nothing is added to the class: retransformation cannot change its schema, so the consult is
 * inlined at both call sites and the extension reference is parked in a fresh local. Nothing is
 * rewritten unless the artifact's own bytes prove the reviewed premise: the exact {@code setup()V}
 * method shape, exactly two class-wide forced call sites, both inside {@code setup()}, each
 * directly preceded by the four default arguments.</p>
 */
public final class ExtendedInterpolationSetupTransformer implements ClassFileTransformer {

    private final ClassLoader loader;
    private final Path artifact;
    private final byte[] reference;
    private final List<String> referenceShape;
    private final List<String> anchorProblems;
    private final int extensionSlot;
    private volatile int sites;
    private volatile String failure;
    private volatile String beforeSha256;
    private volatile String instrumentedSha256;

    /**
     * @param loader host loader this transformer may rewrite
     * @param artifact exact admitted artifact, compared to the candidate's code source
     * @param reference {@code CModelSource.class} bytes read from the artifact
     * @throws IllegalArgumentException when the reviewed anchors are absent from the reference
     */
    public ExtendedInterpolationSetupTransformer(
            final ClassLoader loader, final Path artifact, final byte[] reference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.reference = Objects.requireNonNull(reference, "reference").clone();
        this.referenceShape = ReviewedMethodShape.read(
                this.reference,
                ExtendedInterpolationSetupTarget.MODEL_SOURCE,
                ExtendedInterpolationSetupTarget.SETUP_METHOD,
                ExtendedInterpolationSetupTarget.SETUP_DESCRIPTOR);
        if (referenceShape == null) {
            throw new IllegalArgumentException("reviewed setup entry absent");
        }
        this.anchorProblems = ExtendedInterpolationSetupTarget.verify(this.reference);
        if (!anchorProblems.isEmpty()) {
            throw new IllegalArgumentException("reviewed setup anchors absent: " + anchorProblems);
        }
        this.extensionSlot = freeLocalSlot(this.reference);
    }

    /** {@return the number of rewritten call sites} */
    public int sites() {
        return sites;
    }

    /** {@return the most recent rejection, or {@code null}} */
    public String failure() {
        return failure;
    }

    /** {@return the SHA-256 of the first accepted input class} */
    public String beforeSha256() {
        return beforeSha256;
    }

    /** {@return the SHA-256 of the first woven output class} */
    public String instrumentedSha256() {
        return instrumentedSha256;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (bytes == null || actualLoader != loader || name == null) return null;
        if (!ExtendedInterpolationSetupTarget.MODEL_SOURCE.equals(name)) return null;
        try {
            if (domain == null
                    || domain.getCodeSource() == null
                    || !artifact.equals(
                            Path.of(domain.getCodeSource().getLocation().toURI())
                                    .toAbsolutePath()
                                    .normalize())) {
                failure = "setup owner source mismatch";
                return null;
            }
            final List<String> shape = ReviewedMethodShape.read(
                    bytes,
                    ExtendedInterpolationSetupTarget.MODEL_SOURCE,
                    ExtendedInterpolationSetupTarget.SETUP_METHOD,
                    ExtendedInterpolationSetupTarget.SETUP_DESCRIPTOR);
            if (!referenceShape.equals(shape)) {
                failure = "setup method shape mismatch";
                return null;
            }
            final List<String> problems = ExtendedInterpolationSetupTarget.verify(bytes);
            if (!problems.isEmpty()) {
                failure = "setup anchors changed: " + problems;
                return null;
            }

            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader;
                }
            };
            final int[] woven = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String method,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor delegate =
                                    super.visitMethod(access, method, descriptor, signature, exceptions);
                            if (!ExtendedInterpolationSetupTarget.SETUP_METHOD.equals(method)
                                    || !ExtendedInterpolationSetupTarget.SETUP_DESCRIPTOR.equals(descriptor)) {
                                return delegate;
                            }
                            return new MethodVisitor(Opcodes.ASM9, delegate) {
                                @Override
                                public void visitMethodInsn(
                                        final int opcode,
                                        final String owner,
                                        final String methodName,
                                        final String desc,
                                        final boolean isInterface) {
                                    if (opcode == Opcodes.INVOKESTATIC
                                            && ExtendedInterpolationSetupTarget.EXTENSION.equals(owner)
                                            && ExtendedInterpolationSetupTarget.FORCED_METHOD.equals(methodName)
                                            && ExtendedInterpolationSetupTarget.FORCED_DESCRIPTOR.equals(desc)) {
                                        for (int i = 0; i < 4; i++) {
                                            super.visitInsn(Opcodes.POP);
                                        }
                                        emitConsult(delegate, extensionSlot);
                                        woven[0]++;
                                        return;
                                    }
                                    super.visitMethodInsn(opcode, owner, methodName, desc, isInterface);
                                }
                            };
                        }
                    },
                    ClassReader.EXPAND_FRAMES);

            if (woven[0] != ExtendedInterpolationSetupTarget.EXPECTED_CALL_SITES) {
                failure = "woven call sites " + woven[0] + "/" + ExtendedInterpolationSetupTarget.EXPECTED_CALL_SITES;
                return null;
            }
            final byte[] result = writer.toByteArray();
            if (beforeSha256 == null) beforeSha256 = sha256(bytes);
            if (instrumentedSha256 == null) instrumentedSha256 = sha256(result);
            sites += woven[0];
            return result;
        } catch (Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure = rejected + " at " + (trace.length == 0 ? "<no frames>" : trace[0].toString());
            return null;
        }
    }

    /**
     * Emits the inlined consult: the extension reference on the stack is parked in
     * {@code slot}, the property slot is checked, and either the forced call is skipped or the
     * exact reviewed arguments are pushed again.
     */
    private static void emitConsult(final MethodVisitor out, final int slot) {
        final Label tryStart = new Label(), tryEnd = new Label(), handler = new Label();
        final Label nativeCall = new Label(), notFunction = new Label(), done = new Label();
        out.visitVarInsn(Opcodes.ASTORE, slot);
        out.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
        out.visitLabel(tryStart);
        out.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
        out.visitLdcInsn(ExtendedInterpolationSetupBridge.CONSULT_PROPERTY);
        out.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        out.visitInsn(Opcodes.DUP);
        out.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Function");
        out.visitJumpInsn(Opcodes.IFEQ, notFunction);
        out.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function");
        out.visitVarInsn(Opcodes.ALOAD, slot);
        out.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/function/Function",
                "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                true);
        out.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
        out.visitJumpInsn(Opcodes.IF_ACMPNE, nativeCall);
        out.visitJumpInsn(Opcodes.GOTO, done);
        out.visitLabel(notFunction);
        out.visitInsn(Opcodes.POP);
        out.visitLabel(tryEnd);
        out.visitLabel(nativeCall);
        out.visitVarInsn(Opcodes.ALOAD, slot);
        out.visitInsn(Opcodes.ICONST_0);
        out.visitInsn(Opcodes.ICONST_0);
        out.visitInsn(Opcodes.ICONST_3);
        out.visitInsn(Opcodes.ACONST_NULL);
        out.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                ExtendedInterpolationSetupTarget.EXTENSION,
                ExtendedInterpolationSetupTarget.FORCED_METHOD,
                ExtendedInterpolationSetupTarget.FORCED_DESCRIPTOR,
                false);
        out.visitJumpInsn(Opcodes.GOTO, done);
        out.visitLabel(handler);
        out.visitInsn(Opcodes.POP);
        out.visitJumpInsn(Opcodes.GOTO, nativeCall);
        out.visitLabel(done);
    }

    /**
     * @param classFile {@code CModelSource.class} bytes
     * @return a local slot the reviewed {@code setup()} method never touches
     */
    private static int freeLocalSlot(final byte[] classFile) {
        final int[] max = {0};
        new ClassReader(classFile)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!ExtendedInterpolationSetupTarget.SETUP_METHOD.equals(name)
                                        || !ExtendedInterpolationSetupTarget.SETUP_DESCRIPTOR.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitVarInsn(final int opcode, final int var) {
                                        if (var > max[0]) max[0] = var;
                                    }

                                    @Override
                                    public void visitIincInsn(final int var, final int increment) {
                                        if (var > max[0]) max[0] = var;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return max[0] + 2;
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
