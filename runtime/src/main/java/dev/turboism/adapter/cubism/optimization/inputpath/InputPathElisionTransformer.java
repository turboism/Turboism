package dev.turboism.adapter.cubism.optimization.inputpath;

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
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Test-only input-path elision for the widget base class
 * {@code com/live2d/ui/CWidget}. At the entry of the two reviewed forwarder
 * methods the transformer injects a loader-neutral consult:
 *
 * <ul>
 *   <li>{@code requestFocus()V}: a {@code Predicate<Object>} slot receives the
 *   widget; {@code true} returns immediately, skipping the
 *   {@code getJComponent().requestFocus()} forward that ends in the Wine-side
 *   {@code shouldNativelyFocusHeavyweight} query;</li>
 *   <li>{@code setCursor(Lcom/live2d/type/CCursor;)V}: a
 *   {@code BiPredicate<Object,Object>} slot receives {@code (widget, cursor)};
 *   {@code true} returns immediately, skipping the
 *   {@code Component.setCursor} → {@code updateCursorImmediately} native
 *   re-query.</li>
 * </ul>
 *
 * <p>Every consult goes through {@link System#getProperties()} slots holding
 * JDK functional interfaces, exactly like the model-update-skip and
 * upload-elision transformers; absent or mistyped slots and any callback
 * failure fall through to the unmodified host path. The reviewed method shapes
 * of <em>both</em> methods must equal the official artifact's reference and the
 * injection inventory is asserted — any drift leaves the class untouched
 * (fail-closed).</p>
 */
public final class InputPathElisionTransformer implements ClassFileTransformer {

    /** Opt-in system property; the experiment is off by default. */
    public static final String ENABLE_PROPERTY = "turboism.validation.inputPathElision";

    private static final String PREDICATE = "java/util/function/Predicate";
    private static final String BIPREDICATE = "java/util/function/BiPredicate";

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> focusShape;
    private final List<String> cursorShape;
    private volatile String beforeSha256, failure;
    private volatile int matches, sites;

    /**
     * @param loader the host class loader admitted for the artifact
     * @param artifact official host JAR path, attested at every transform
     * @param reference official {@link ClassReader#EXPAND_FRAMES} bytes of
     *                  {@link InputPathElisionTarget#OWNER}
     */
    public InputPathElisionTransformer(final ClassLoader loader, final Path artifact,
                                       final byte[] reference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        focusShape = ReviewedMethodShape.read(reference, InputPathElisionTarget.OWNER,
            InputPathElisionTarget.FOCUS_METHOD, InputPathElisionTarget.FOCUS_DESCRIPTOR);
        cursorShape = ReviewedMethodShape.read(reference, InputPathElisionTarget.OWNER,
            InputPathElisionTarget.CURSOR_METHOD, InputPathElisionTarget.CURSOR_DESCRIPTOR);
        if (focusShape == null || cursorShape == null) {
            throw new IllegalArgumentException("reviewed input-path methods absent: "
                + InputPathElisionTarget.OWNER);
        }
    }

    /** Returns the latest rejection, or null. */
    public String failure() { return failure; }
    /** Returns the successful class transform count. */
    public int matches() { return matches; }
    /** Returns the injected entry-consult count so far. */
    public int sites() { return sites; }
    /** Returns the original full class digest for restoration verification. */
    public String beforeSha256() { return beforeSha256; }

    @Override public byte[] transform(final Module module, final ClassLoader actualLoader,
                                      final String name, final Class<?> type,
                                      final ProtectionDomain domain, final byte[] bytes) {
        if (actualLoader != loader || !InputPathElisionTarget.OWNER.equals(name)
            || bytes == null) {
            return null;
        }
        try {
            if (domain == null || domain.getCodeSource() == null || !artifact.equals(
                    Path.of(domain.getCodeSource().getLocation().toURI())
                        .toAbsolutePath().normalize())) {
                failure = "input path elision source mismatch: " + InputPathElisionTarget.OWNER;
                return null;
            }
            if (!focusShape.equals(ReviewedMethodShape.read(bytes, InputPathElisionTarget.OWNER,
                    InputPathElisionTarget.FOCUS_METHOD,
                    InputPathElisionTarget.FOCUS_DESCRIPTOR))
                || !cursorShape.equals(ReviewedMethodShape.read(bytes, InputPathElisionTarget.OWNER,
                    InputPathElisionTarget.CURSOR_METHOD,
                    InputPathElisionTarget.CURSOR_DESCRIPTOR))) {
                failure = "input path method shape changed: " + InputPathElisionTarget.OWNER;
                return null;
            }
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            final boolean[] applied = {false, false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(final int access, final String method,
                                                           final String descriptor,
                                                           final String signature,
                                                           final String[] exceptions) {
                    final MethodVisitor visitor =
                        super.visitMethod(access, method, descriptor, signature, exceptions);
                    if (InputPathElisionTarget.FOCUS_METHOD.equals(method)
                        && InputPathElisionTarget.FOCUS_DESCRIPTOR.equals(descriptor)) {
                        return new EntryVisitor(visitor, applied, 0,
                            InputPathElisionBridge.FOCUS_PROPERTY, PREDICATE, false);
                    }
                    if (InputPathElisionTarget.CURSOR_METHOD.equals(method)
                        && InputPathElisionTarget.CURSOR_DESCRIPTOR.equals(descriptor)) {
                        return new EntryVisitor(visitor, applied, 1,
                            InputPathElisionBridge.CURSOR_PROPERTY, BIPREDICATE, true);
                    }
                    return visitor;
                }
            }, ClassReader.EXPAND_FRAMES);
            if (!applied[0] || !applied[1]) {
                failure = "input path elision anchor drift on "
                    + InputPathElisionTarget.OWNER + ": focus=" + applied[0]
                    + " cursor=" + applied[1];
                return null;
            }
            final byte[] result = writer.toByteArray();
            if (beforeSha256 == null) {
                beforeSha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            }
            matches++;
            sites += 2;
            return result;
        } catch (Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure = rejected + " at "
                + (trace.length == 0 ? "<no frames>" : trace[0].toString());
            return null;
        }
    }

    /**
     * Injects {@code if (slot.test(this[, arg])) return;} at method entry,
     * wrapped in a {@code Throwable} guard that always falls through to the
     * unmodified host path. The consult sits at a basic-block boundary with an
     * empty operand stack, so every exit is frame-consistent.
     */
    private static final class EntryVisitor extends MethodVisitor {
        private final boolean[] applied;
        private final int index;
        private final String property;
        private final String slotType;
        private final boolean binary;

        EntryVisitor(final MethodVisitor visitor, final boolean[] applied, final int index,
                     final String property, final String slotType, final boolean binary) {
            super(Opcodes.ASM9, visitor);
            this.applied = applied;
            this.index = index;
            this.property = property;
            this.slotType = slotType;
            this.binary = binary;
        }

        @Override public void visitCode() {
            super.visitCode();
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label discard = new Label(), resume = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                "()Ljava/util/Properties;", false);
            super.visitLdcInsn(property);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            super.visitInsn(Opcodes.DUP);
            super.visitTypeInsn(Opcodes.INSTANCEOF, slotType);
            super.visitJumpInsn(Opcodes.IFEQ, discard);
            super.visitTypeInsn(Opcodes.CHECKCAST, slotType);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            if (binary) {
                super.visitVarInsn(Opcodes.ALOAD, 1);
                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, slotType, "test",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", true);
            } else {
                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, slotType, "test",
                    "(Ljava/lang/Object;)Z", true);
            }
            super.visitJumpInsn(Opcodes.IFEQ, resume);
            super.visitInsn(Opcodes.RETURN);
            super.visitLabel(end);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, resume);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(resume);
            applied[index] = true;
        }
    }
}
