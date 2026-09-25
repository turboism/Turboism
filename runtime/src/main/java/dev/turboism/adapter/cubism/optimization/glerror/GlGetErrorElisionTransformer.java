package dev.turboism.adapter.cubism.optimization.glerror;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Test-only timing-leg experiment: replaces each reviewed {@code glGetError}
 * interface call inside the shader error-check marker {@code shader/A.a(GL,String,Z)}
 * with the constant {@code GL_NO_ERROR} so attribution legs can measure the cost of
 * the unconditional error checks. The rewrite admits exactly one owner/method/
 * descriptor whose instruction shape must equal the official artifact's reference
 * shape; any drift leaves the class untouched (fail-closed). This transformer is
 * independent of the {@code GlSubmissionProbe} decorator so it can run in
 * uninstrumented timing legs. Enable with
 * {@code -Dturboism.validation.glGetErrorElision=true}.
 */
public final class GlGetErrorElisionTransformer implements ClassFileTransformer {

    /** Opt-in system property; the experiment is off by default. */
    public static final String ENABLE_PROPERTY = "turboism.validation.glGetErrorElision";

    private static final String GL_OWNER = "com/jogamp/opengl/GL";
    private static final String GL_METHOD = "glGetError";
    private static final String GL_DESCRIPTOR = "()I";

    /**
     * Tracks the (loader, owner) pairs this elision rewrote and left installed.
     * The uniform-location lifecycle verifier consults it so a class body that
     * carries <em>this</em> reviewed elision can be told apart from foreign
     * bytecode drift; an unrecorded elided body still fails closed.
     */
    private record Installation(ClassLoader loader, String owner) { }

    private static final java.util.Set<Installation> INSTALLED =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ClassLoader loader;
    private final Path artifact;
    private final GlGetErrorElisionTarget target;
    private final List<String> shape;
    private final int expectedSites;
    private int matches;
    private int elided;
    private String failure;
    private String beforeSha256;

    /**
     * @param loader the host class loader admitted for the artifact
     * @param artifact official host JAR path, attested at every transform
     * @param reference official {@link ClassReader#EXPAND_FRAMES} reference bytes
     * @param target the reviewed target for the artifact digest
     */
    public GlGetErrorElisionTransformer(final ClassLoader loader, final Path artifact,
                                        final byte[] reference,
                                        final GlGetErrorElisionTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.target = Objects.requireNonNull(target, "target");
        shape = ReviewedMethodShape.read(
            reference, target.owner(), target.method(), target.descriptor());
        if (shape == null) {
            throw new IllegalArgumentException("reviewed error-check method absent");
        }
        expectedSites = sites(reference);
        if (expectedSites < 1) {
            throw new IllegalArgumentException("reviewed glGetError call site absent");
        }
    }

    /**
     * Records that this elision is installed for the given defining loader and
     * reviewed owner. Called by the verified installer only after admission.
     */
    public static void markInstalled(final ClassLoader loader, final String owner) {
        INSTALLED.add(new Installation(loader, owner));
    }

    /** Drops the installation record when the verified installer restores. */
    public static void clearInstalled(final ClassLoader loader, final String owner) {
        INSTALLED.remove(new Installation(loader, owner));
    }

    /** Returns whether this elision is installed for the loader/owner pair. */
    public static boolean isInstalled(final ClassLoader loader, final String owner) {
        return INSTALLED.contains(new Installation(loader, owner));
    }

    /**
     * Returns the exact reviewed method shape this transform produces for the
     * official body — the bytes a later transformer in the retransform chain
     * will observe. Computed by running a private probe instance over the
     * attested reference bytes; {@code null} when the rewrite cannot run.
     */
    public static List<String> composedShape(final ClassLoader loader, final Path artifact,
                                             final byte[] reference,
                                             final GlGetErrorElisionTarget target,
                                             final Class<?> type) {
        try {
            final GlGetErrorElisionTransformer probe =
                new GlGetErrorElisionTransformer(loader, artifact, reference, target);
            final byte[] rewritten = probe.transform(type.getModule(), loader,
                target.owner(), null, type.getProtectionDomain(), reference);
            if (rewritten == null) {
                return null;
            }
            return ReviewedMethodShape.read(
                rewritten, target.owner(), target.method(), target.descriptor());
        } catch (Exception | LinkageError failure) {
            return null;
        }
    }

    /** Returns the latest rejection, or null. */
    public String failure() { return failure; }
    /** Returns the successful class transform count. */
    public int matches() { return matches; }
    /** Returns the number of {@code glGetError} sites replaced so far. */
    public int elided() { return elided; }
    /** Returns the original full class digest for restoration verification. */
    public String beforeSha256() { return beforeSha256; }

    @Override public byte[] transform(final Module module, final ClassLoader actualLoader,
                                      final String name, final Class<?> type,
                                      final ProtectionDomain domain, final byte[] bytes) {
        if (actualLoader != loader || !target.owner().equals(name) || bytes == null) return null;
        try {
            if (domain == null || domain.getCodeSource() == null || !artifact.equals(
                    Path.of(domain.getCodeSource().getLocation().toURI())
                        .toAbsolutePath().normalize())) {
                failure = "glGetError elision source mismatch";
                return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(
                    bytes, target.owner(), target.method(), target.descriptor()))) {
                failure = "glGetError check method changed";
                return null;
            }
            final ClassReader reader = new ClassReader(bytes);
            final int[] replaced = {0};
            final ClassWriter writer = new ClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(final int access, final String method,
                                                         final String descriptor,
                                                         final String signature,
                                                         final String[] exceptions) {
                    final MethodVisitor original =
                        super.visitMethod(access, method, descriptor, signature, exceptions);
                    return target.method().equals(method) && target.descriptor().equals(descriptor)
                        ? new ElisionVisitor(original, replaced)
                        : original;
                }
            }, ClassReader.EXPAND_FRAMES);
            if (replaced[0] != expectedSites) {
                failure = "glGetError site count drift: expected=" + expectedSites
                    + " replaced=" + replaced[0];
                return null;
            }
            final byte[] result = writer.toByteArray();
            if (beforeSha256 == null) {
                beforeSha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            }
            matches++;
            elided += replaced[0];
            return result;
        } catch (Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    /** Counts the exact {@code GL.glGetError()I} interface call sites in the target method. */
    private int sites(final byte[] bytes) {
        final int[] count = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(final int access, final String name,
                                                     final String descriptor,
                                                     final String signature,
                                                     final String[] exceptions) {
                if (!target.method().equals(name) || !target.descriptor().equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(final int opcode, final String owner,
                                                          final String method, final String desc,
                                                          final boolean itf) {
                        if (opcode == Opcodes.INVOKEINTERFACE && itf && GL_OWNER.equals(owner)
                            && GL_METHOD.equals(method) && GL_DESCRIPTOR.equals(desc)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    /**
     * Replaces {@code invokeinterface GL.glGetError()I} with {@code pop; iconst_0}:
     * the receiver is dropped and a constant {@code GL_NO_ERROR} result is pushed,
     * preserving the exact operand-stack shape of the native call.
     */
    private static final class ElisionVisitor extends MethodVisitor {
        private final int[] replaced;
        ElisionVisitor(final MethodVisitor original, final int[] replaced) {
            super(Opcodes.ASM9, original);
            this.replaced = replaced;
        }
        @Override public void visitMethodInsn(final int opcode, final String owner,
                                              final String name, final String descriptor,
                                              final boolean itf) {
            if (opcode == Opcodes.INVOKEINTERFACE && itf && GL_OWNER.equals(owner)
                && GL_METHOD.equals(name) && GL_DESCRIPTOR.equals(descriptor)) {
                super.visitInsn(Opcodes.POP);
                super.visitInsn(Opcodes.ICONST_0);
                replaced[0]++;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }
    }
}
