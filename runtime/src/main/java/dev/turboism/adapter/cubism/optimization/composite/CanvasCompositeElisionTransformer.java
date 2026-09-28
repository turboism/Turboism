package dev.turboism.adapter.cubism.optimization.composite;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
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
 * Test-only canvas-composite elision. Two entry consults are injected:
 *
 * <ul>
 *   <li>{@code PaintManager.paint} (java.desktop, bootstrap loader, null code
 *   source — attested by the jrt reference bytes of the running VM): a
 *   {@code Predicate<Object>} slot receives the painting component;
 *   {@code true} returns {@code false}, selecting the JDK's own direct-paint
 *   fallback in {@code RepaintManager.paint} and bypassing the shared
 *   offscreen back buffer + its screen blit;</li>
 *   <li>{@code FlatPanelUI.update} (flatlaf jar beside the host artifact, host
 *   class loader, exact code-source pin): a
 *   {@code BiPredicate<Object,Object>} slot receives {@code (graphics,
 *   component)}; {@code true} returns immediately, skipping a background fill
 *   the bridge proved invisible.</li>
 * </ul>
 *
 * <p>Fail-closed: each class passes loader + protection-domain attestation and
 * the reviewed method-shape gate; both sites must rewrite or the installer
 * refuses. Consults use {@link System#getProperties()} slots holding JDK
 * functional interfaces; absent slots and callback failures fall through to
 * the unmodified path.</p>
 */
public final class CanvasCompositeElisionTransformer implements ClassFileTransformer {

    /** Opt-in system property; the experiment is off by default. */
    public static final String ENABLE_PROPERTY = "turboism.validation.canvasCompositeElision";

    private static final String PREDICATE = "java/util/function/Predicate";
    private static final String BIPREDICATE = "java/util/function/BiPredicate";

    private final ClassLoader loader;
    private final Path flatlaf;
    private final List<String> paintShape;
    private final List<String> fillShape;
    private final Map<String, String> beforeSha256 = new HashMap<>();
    private volatile String failure;
    private volatile int matches, sites;

    /**
     * @param loader the host class loader admitted for the artifacts
     * @param flatlaf the FlatLaf JAR beside the host artifact
     * @param paintReference bytes of {@code PaintManager} from the running
     *                       VM's jrt image
     * @param fillReference bytes of {@code FlatPanelUI} from the FlatLaf JAR
     */
    public CanvasCompositeElisionTransformer(
            final ClassLoader loader, final Path flatlaf, final byte[] paintReference, final byte[] fillReference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.flatlaf =
                Objects.requireNonNull(flatlaf, "flatlaf").toAbsolutePath().normalize();
        paintShape = ReviewedMethodShape.read(
                paintReference,
                CanvasCompositeElisionTarget.PAINT_OWNER,
                CanvasCompositeElisionTarget.PAINT_METHOD,
                CanvasCompositeElisionTarget.PAINT_DESCRIPTOR);
        fillShape = ReviewedMethodShape.read(
                fillReference,
                CanvasCompositeElisionTarget.FILL_OWNER,
                CanvasCompositeElisionTarget.FILL_METHOD,
                CanvasCompositeElisionTarget.FILL_DESCRIPTOR);
        if (paintShape == null || fillShape == null) {
            throw new IllegalArgumentException("reviewed canvas-composite methods absent");
        }
    }

    /** Returns the latest rejection, or null. */
    public String failure() {
        return failure;
    }
    /** Returns the successful class transform count. */
    public int matches() {
        return matches;
    }
    /** Returns the injected entry-consult count so far. */
    public int sites() {
        return sites;
    }
    /** Returns the original class digest for a restored target, or null. */
    public String beforeSha256(final String owner) {
        return beforeSha256.get(owner);
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        final boolean paint = CanvasCompositeElisionTarget.PAINT_OWNER.equals(name);
        final boolean fill = CanvasCompositeElisionTarget.FILL_OWNER.equals(name);
        if ((!paint && !fill) || bytes == null) {
            return null;
        }
        try {
            if (paint) {
                // java.desktop is loaded by a JDK-owned loader (bootstrap or
                // platform); identity is proven by the shape gate against the
                // running VM's own jrt bytes instead of an artifact path.
                if (actualLoader != null && actualLoader != ClassLoader.getPlatformClassLoader()) {
                    failure = "canvas composite loader mismatch: " + name;
                    return null;
                }
            } else {
                if (actualLoader != loader
                        || domain == null
                        || domain.getCodeSource() == null
                        || !flatlaf.equals(
                                Path.of(domain.getCodeSource().getLocation().toURI())
                                        .toAbsolutePath()
                                        .normalize())) {
                    failure = "canvas composite source mismatch: " + name;
                    return null;
                }
            }
            final List<String> expected = paint ? paintShape : fillShape;
            final String method =
                    paint ? CanvasCompositeElisionTarget.PAINT_METHOD : CanvasCompositeElisionTarget.FILL_METHOD;
            final String descriptor = paint
                    ? CanvasCompositeElisionTarget.PAINT_DESCRIPTOR
                    : CanvasCompositeElisionTarget.FILL_DESCRIPTOR;
            if (!expected.equals(ReviewedMethodShape.read(bytes, name, method, descriptor))) {
                failure = "canvas composite method shape changed: " + name;
                return null;
            }
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader != null ? loader : super.getClassLoader();
                }
            };
            final boolean[] applied = {false};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String name2,
                                final String descriptor2,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor visitor =
                                    super.visitMethod(access, name2, descriptor2, signature, exceptions);
                            if (method.equals(name2) && descriptor.equals(descriptor2)) {
                                return paint
                                        ? new EntryVisitor(
                                                visitor,
                                                applied,
                                                CanvasCompositeElisionBridge.PAINT_PROPERTY,
                                                PREDICATE,
                                                false,
                                                true)
                                        : new EntryVisitor(
                                                visitor,
                                                applied,
                                                CanvasCompositeElisionBridge.FILL_PROPERTY,
                                                BIPREDICATE,
                                                true,
                                                false);
                            }
                            return visitor;
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (!applied[0]) {
                failure = "canvas composite elision anchor drift on " + name;
                return null;
            }
            beforeSha256.putIfAbsent(
                    name,
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            matches++;
            sites++;
            return writer.toByteArray();
        } catch (Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure = rejected + " at " + (trace.length == 0 ? "<no frames>" : trace[0].toString());
            return null;
        }
    }

    /**
     * Injects {@code if (slot.test(args)) <ret>;} at method entry, wrapped in a
     * {@code Throwable} guard. {@code returnsBoolean} selects
     * {@code return false} (paint dispatcher) vs a bare {@code return} (fill).
     */
    private static final class EntryVisitor extends MethodVisitor {
        private final boolean[] applied;
        private final String property;
        private final String slotType;
        private final boolean binary;
        private final boolean returnsBoolean;

        EntryVisitor(
                final MethodVisitor visitor,
                final boolean[] applied,
                final String property,
                final String slotType,
                final boolean binary,
                final boolean returnsBoolean) {
            super(Opcodes.ASM9, visitor);
            this.applied = applied;
            this.property = property;
            this.slotType = slotType;
            this.binary = binary;
            this.returnsBoolean = returnsBoolean;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label discard = new Label(), resume = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
            super.visitLdcInsn(property);
            super.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/util/Properties",
                    "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, slotType);
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, slotType);
            super.visitVarInsn(Opcodes.ALOAD, 1);
            if (binary) {
                super.visitVarInsn(Opcodes.ALOAD, 2);
                super.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE, slotType, "test", "(Ljava/lang/Object;Ljava/lang/Object;)Z", true);
            } else {
                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, slotType, "test", "(Ljava/lang/Object;)Z", true);
            }
            super.visitJumpInsn(Opcodes.IFEQ, resume);
            if (returnsBoolean) {
                super.visitInsn(Opcodes.ICONST_0);
                super.visitInsn(Opcodes.IRETURN);
            } else {
                super.visitInsn(Opcodes.RETURN);
            }
            super.visitLabel(end);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, resume);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(resume);
            applied[0] = true;
        }
    }
}
