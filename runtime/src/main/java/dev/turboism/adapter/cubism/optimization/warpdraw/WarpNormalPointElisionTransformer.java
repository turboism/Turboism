package dev.turboism.adapter.cubism.optimization.warpdraw;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites {@code WarpDeformer_Draw.drawNormalPoint} to skip outside-of-parent coordinate boxing
 * and buffer uploads when the outside-of-parent point entity is disabled.
 */
public final class WarpNormalPointElisionTransformer implements ClassFileTransformer {

    private static final int EXPECTED_SITES = 1;

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> methodShape;

    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures the reference method shape of {@code WarpDeformer_Draw.drawNormalPoint}.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param reference reference bytes of {@code WarpDeformer_Draw}
     * @param target reviewed target metadata
     */
    public WarpNormalPointElisionTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] reference,
            final WarpNormalPointElisionTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(target, "target");
        methodShape = ReviewedMethodShape.read(
                reference,
                WarpNormalPointElisionTarget.OWNER,
                WarpNormalPointElisionTarget.DRAW_NORMAL_POINT_METHOD,
                WarpNormalPointElisionTarget.DRAW_NORMAL_POINT_DESCRIPTOR);
        if (methodShape == null) {
            throw new IllegalArgumentException("WarpDeformer_Draw.drawNormalPoint absent from reference");
        }
    }

    /** Returns the latest fail-closed rejection reason, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns the number of rewritten classes. */
    public int matches() {
        return matches;
    }

    /** Returns the number of rewritten short-circuit sites. */
    public int sites() {
        return sites;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (bytes == null || !WarpNormalPointElisionTarget.OWNER.equals(name)) {
            return null;
        }
        if (actualLoader != loader || !matchesArtifactSource(domain)) {
            failure = "unexpected loader or code source for " + name;
            return null;
        }
        if (!methodShape.equals(ReviewedMethodShape.read(
                bytes,
                WarpNormalPointElisionTarget.OWNER,
                WarpNormalPointElisionTarget.DRAW_NORMAL_POINT_METHOD,
                WarpNormalPointElisionTarget.DRAW_NORMAL_POINT_DESCRIPTOR))) {
            failure = "WarpDeformer_Draw.drawNormalPoint method shape mismatch";
            return null;
        }
        try {
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = HostClassWriters.computeFrames(reader, loader);
            final int[] localSites = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String methodName,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor mv =
                                    super.visitMethod(access, methodName, descriptor, signature, exceptions);
                            if (!WarpNormalPointElisionTarget.DRAW_NORMAL_POINT_METHOD.equals(methodName)
                                    || !WarpNormalPointElisionTarget.DRAW_NORMAL_POINT_DESCRIPTOR.equals(descriptor)) {
                                return mv;
                            }
                            return wrapDrawNormalPoint(mv, localSites);
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (localSites[0] != EXPECTED_SITES) {
                failure = "WarpDeformer_Draw site count mismatch: " + localSites[0];
                return null;
            }
            final byte[] output = writer.toByteArray();
            matches++;
            sites += localSites[0];
            return output;
        } catch (final Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private boolean matchesArtifactSource(final ProtectionDomain domain) {
        if (domain == null || domain.getCodeSource() == null || domain.getCodeSource().getLocation() == null) {
            return false;
        }
        try {
            return Path.of(domain.getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize()
                    .equals(artifact);
        } catch (final Exception ignored) {
            return false;
        }
    }

    private static MethodVisitor wrapDrawNormalPoint(
            final MethodVisitor downstream, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, downstream) {
            private boolean afterHighlightCheck;
            private boolean injected;

            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean isInterface) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                if (!injected
                        && opcode == Opcodes.INVOKEVIRTUAL
                        && WarpNormalPointElisionTarget.DEVELOPER_SETTING_OWNER.equals(owner)
                        && WarpNormalPointElisionTarget.HIGHLIGHT_OUTSIDE_METHOD.equals(name)
                        && WarpNormalPointElisionTarget.BOOLEAN_GETTER_DESCRIPTOR.equals(descriptor)) {
                    afterHighlightCheck = true;
                    return;
                }
                if (afterHighlightCheck
                        && !injected
                        && opcode == Opcodes.INVOKEVIRTUAL
                        && WarpNormalPointElisionTarget.SIMPLE_POINTS_OWNER.equals(owner)
                        && WarpNormalPointElisionTarget.SET_ENABLED_METHOD.equals(name)
                        && WarpNormalPointElisionTarget.SET_ENABLED_DESCRIPTOR.equals(descriptor)) {
                    injected = true;
                    localSites[0]++;
                    final Label continueWhenEnabled = new Label();
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitFieldInsn(
                            Opcodes.GETFIELD,
                            WarpNormalPointElisionTarget.OWNER,
                            WarpNormalPointElisionTarget.OUTSIDE_NORMAL_POINT_FIELD,
                            WarpNormalPointElisionTarget.SIMPLE_POINTS_DESCRIPTOR);
                    super.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            WarpNormalPointElisionTarget.SIMPLE_POINTS_OWNER,
                            WarpNormalPointElisionTarget.GET_ENABLED_METHOD,
                            WarpNormalPointElisionTarget.BOOLEAN_GETTER_DESCRIPTOR,
                            false);
                    super.visitJumpInsn(Opcodes.IFNE, continueWhenEnabled);
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitFieldInsn(
                            Opcodes.GETFIELD,
                            WarpNormalPointElisionTarget.OWNER,
                            WarpNormalPointElisionTarget.OUTSIDE_SELECT_POINT_FIELD,
                            WarpNormalPointElisionTarget.SIMPLE_POINTS_DESCRIPTOR);
                    super.visitInsn(Opcodes.ICONST_0);
                    super.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            WarpNormalPointElisionTarget.SIMPLE_POINTS_OWNER,
                            WarpNormalPointElisionTarget.SET_ENABLED_METHOD,
                            WarpNormalPointElisionTarget.SET_ENABLED_DESCRIPTOR,
                            false);
                    super.visitInsn(Opcodes.RETURN);
                    super.visitLabel(continueWhenEnabled);
                }
            }
        };
    }
}
