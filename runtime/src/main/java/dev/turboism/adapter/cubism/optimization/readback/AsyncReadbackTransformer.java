package dev.turboism.adapter.cubism.optimization.readback;

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
 * Rewrites the single GLSL-path readback of JOGL's {@code GLJPanel$OffscreenBackend.postGL} into
 * {@link AsyncReadbackBridge#read}.
 *
 * <p>The class is admitted only when it is defined by the digest-attested JOGL artifact and its
 * whole {@code postGL} instruction stream equals the official reference shape. The rewrite itself
 * replaces exactly one {@code INVOKEINTERFACE GL.glReadPixels(IIIIIILjava/nio/Buffer;)V} with the
 * static bridge call that takes the same eight operands, so no local, frame or stack map changes:
 * the first client readback (GLSL flip path) is pipelined, the second one (CPU flip path) stays
 * native. Any drift fails closed and leaves the class untouched.</p>
 */
public final class AsyncReadbackTransformer implements ClassFileTransformer {

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> referenceShape;
    private final int referenceReadSites;
    private volatile String beforeSha256;
    private volatile String failure;
    private volatile int matches;

    /**
     * Creates an inert transform from the official JOGL reference bytes.
     *
     * @param loader the exact defining loader of the offscreen backend
     * @param artifact the digest-attested {@code jogl-all.jar}
     * @param reference official class bytes of {@link AsyncReadbackTarget#OWNER}
     */
    public AsyncReadbackTransformer(final ClassLoader loader, final Path artifact, final byte[] reference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        final byte[] official = Objects.requireNonNull(reference, "reference").clone();
        referenceShape = ReviewedMethodShape.read(
                official, AsyncReadbackTarget.OWNER, AsyncReadbackTarget.METHOD, AsyncReadbackTarget.DESCRIPTOR);
        if (referenceShape == null) {
            throw new IllegalArgumentException("reviewed readback method absent: " + AsyncReadbackTarget.OWNER);
        }
        referenceReadSites = countClientReadSites(official);
        if (referenceReadSites != 2) {
            throw new IllegalArgumentException(
                    "reviewed readback shape has " + referenceReadSites + " client readbacks");
        }
    }

    @Override
    public byte[] transform(
            final ClassLoader definingLoader,
            final String name,
            final Class<?> beingRedefined,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (!AsyncReadbackTarget.OWNER.equals(name)) {
            return null;
        }
        try {
            if (definingLoader != loader || domain == null || domain.getCodeSource() == null) {
                throw new IllegalArgumentException("readback artifact mismatch");
            }
            if (!artifact.equals(Path.of(domain.getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize())) {
                throw new IllegalArgumentException("readback artifact mismatch");
            }
            final List<String> shape = ReviewedMethodShape.read(
                    bytes, AsyncReadbackTarget.OWNER, AsyncReadbackTarget.METHOD, AsyncReadbackTarget.DESCRIPTOR);
            if (!referenceShape.equals(shape)) {
                throw new IllegalArgumentException("readback method shape drift");
            }
            final byte[] changed = rewrite(bytes);
            if (beforeSha256 == null) {
                beforeSha256 = sha256(bytes);
            }
            matches++;
            return changed;
        } catch (final Exception | LinkageError problem) {
            failure = problem.toString();
            return null;
        }
    }

    private static byte[] rewrite(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = new ClassWriter(reader, 0);
        final int[] sites = {0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String method,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor next = super.visitMethod(access, method, descriptor, signature, exceptions);
                        if (!AsyncReadbackTarget.METHOD.equals(method)
                                || !AsyncReadbackTarget.DESCRIPTOR.equals(descriptor)) {
                            return next;
                        }
                        return new MethodVisitor(Opcodes.ASM9, next) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String name,
                                    final String descriptor,
                                    final boolean isInterface) {
                                if (sites[0] == 0
                                        && opcode == Opcodes.INVOKEINTERFACE
                                        && AsyncReadbackTarget.READ_OWNER.equals(owner)
                                        && AsyncReadbackTarget.READ_METHOD.equals(name)
                                        && AsyncReadbackTarget.READ_DESCRIPTOR.equals(descriptor)) {
                                    sites[0]++;
                                    super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            AsyncReadbackTarget.BRIDGE_OWNER,
                                            AsyncReadbackTarget.BRIDGE_METHOD,
                                            AsyncReadbackTarget.BRIDGE_DESCRIPTOR,
                                            false);
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            }
                        };
                    }
                },
                0);
        if (sites[0] != 1) {
            throw new IllegalStateException("expected exactly one GLSL readback site, rewrote " + sites[0]);
        }
        return writer.toByteArray();
    }

    private static int countClientReadSites(final byte[] bytes) {
        final int[] count = {0};
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
                                if (!AsyncReadbackTarget.METHOD.equals(method)
                                        || !AsyncReadbackTarget.DESCRIPTOR.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String name,
                                            final String descriptor,
                                            final boolean isInterface) {
                                        if (AsyncReadbackTarget.READ_OWNER.equals(owner)
                                                && AsyncReadbackTarget.READ_METHOD.equals(name)
                                                && AsyncReadbackTarget.READ_DESCRIPTOR.equals(descriptor)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return count[0];
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final Exception unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Returns why the last transform refused, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns successful class rewrites, not method counts. */
    public int matches() {
        return matches;
    }

    /** Returns the pre-rewrite class digest for restoration verification. */
    public String beforeSha256() {
        return beforeSha256;
    }
}
