package dev.turboism.adapter.cubism.optimization.stateelision;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Entry-level redundant-GL-state elision transformer for the bundled JOGL
 * {@code GL4bcImpl}. For each tracked setter the injected consult
 * ({@code MethodHandle.invokeExact (Ljava/lang/Object;IIIII)Z}) runs inside an
 * always-fall-through {@code Throwable} guard; a true result returns before
 * the native dispatch. The original body is additionally wrapped in a
 * catch-all whose handler clears the context and rethrows, so a thrown call
 * never leaves a signature recorded that the driver did not apply.
 * Invalidator methods emit a fire-and-forget notify at entry; a slot failure
 * there falls back to a context clear through the exception slot.
 */
public final class RedundantStateElisionTransformer implements ClassFileTransformer {

    /** Opt-in validation switch; the experiment is inert without it. */
    public static final String ENABLE_PROPERTY = "turboism.validation.redundantStateElision";

    /** Human-readable target description for the ACTIVE marker. */
    public static final String OWNER_DESCRIPTION = "jogamp/opengl/gl4/GL4bcImpl";

    private final ClassLoader loader;
    private final Path artifact;
    private final Map<String, List<String>> shapes = new HashMap<>();
    private final Map<String, Integer> locals = new HashMap<>();
    private final Map<Integer, String> invalidatorNames = new LinkedHashMap<>();
    private volatile String failure, beforeSha256;
    private volatile int matches, lastSites;
    private Runnable onRejection = () -> { };

    /**
     * Creates an inert transform from official JOGL reference bytes.
     * @param loader the exact defining loader of {@code GL4bcImpl}
     * @param artifact the digest-attested {@code jogl-all.jar}
     * @param reference the official {@code GL4bcImpl} class bytes
     * @throws IllegalArgumentException if a tracked method is absent, duplicated or non-concrete
     */
    public RedundantStateElisionTransformer(final ClassLoader loader, final Path artifact,
                                            final byte[] reference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        final int[] count = {0};
        new ClassReader(Objects.requireNonNull(reference, "reference")).accept(
            new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(final int access, final String name,
                        final String descriptor, final String signature, final String[] exceptions) {
                    final Integer site = RedundantStateElisionTarget.SITES.get(name + descriptor);
                    if (site == null) return null;
                    count[0]++;
                    if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0) {
                        throw new IllegalArgumentException("tracked method not concrete: " + name + descriptor);
                    }
                    final List<String> shape = ReviewedMethodShape.read(reference,
                        RedundantStateElisionTarget.OWNER, name, descriptor);
                    if (shape == null) {
                        throw new IllegalArgumentException("tracked method unreadable: " + name + descriptor);
                    }
                    shapes.put(name + descriptor, shape);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMaxs(final int stack, final int maxLocals) {
                            locals.put(name + descriptor, maxLocals);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (count[0] != RedundantStateElisionTarget.SITES.size()
                || shapes.size() != RedundantStateElisionTarget.SITES.size()
                || locals.size() != RedundantStateElisionTarget.SITES.size()) {
            throw new IllegalArgumentException("incomplete tracked method inventory: " + count[0]);
        }
    }

    /** Registers a fail-closed action before installing this transformer. */
    public void onRejection(final Runnable action) {
        onRejection = Objects.requireNonNull(action, "action");
    }

    /** Returns the latest rejection, or null. */
    public String failure() { return failure; }

    /** Returns successful class rewrites, not method counts. */
    public int matches() { return matches; }

    /** Returns the pre-rewrite class digest for restoration verification. */
    public String beforeSha256() { return beforeSha256; }

    /** Returns the invalidator site-id → method-name map discovered during the last transform. */
    public Map<Integer, String> invalidatorNames() { return invalidatorNames; }

    /** Returns the number of methods instrumented by the last transform. */
    public int sites() { return lastSites; }

    @Override public byte[] transform(final Module module, final ClassLoader actualLoader,
            final String name, final Class<?> type, final ProtectionDomain domain, final byte[] bytes) {
        if (actualLoader != loader || !RedundantStateElisionTarget.OWNER.equals(name) || bytes == null) {
            return null;
        }
        try {
            if (domain == null || domain.getCodeSource() == null || !artifact.equals(
                    Path.of(domain.getCodeSource().getLocation().toURI()).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("state-elision artifact mismatch");
            }
            for (final var entry : shapes.entrySet()) {
                final String method = entry.getKey().substring(0, entry.getKey().indexOf('('));
                final String descriptor = entry.getKey().substring(entry.getKey().indexOf('('));
                if (!entry.getValue().equals(ReviewedMethodShape.read(bytes,
                        RedundantStateElisionTarget.OWNER, method, descriptor))) {
                    throw new IllegalArgumentException("tracked method shape mismatch: " + entry.getKey());
                }
            }
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            invalidatorNames.clear();
            final int[] sites = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(final int access, final String method,
                        final String descriptor, final String signature, final String[] exceptions) {
                    final MethodVisitor original =
                        super.visitMethod(access, method, descriptor, signature, exceptions);
                    if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return original;
                    final Integer site = RedundantStateElisionTarget.SITES.get(method + descriptor);
                    if (site != null) {
                        sites[0]++;
                        return new Consult(original, site,
                            locals.get(method + descriptor));
                    }
                    if ((access & Opcodes.ACC_STATIC) == 0
                            && RedundantStateElisionTarget.invalidates(method)) {
                        final int invalidatorSite =
                            RedundantStateElisionTarget.INVALIDATOR_BASE + invalidatorNames.size();
                        invalidatorNames.put(invalidatorSite, method);
                        sites[0]++;
                        return new Notify(original, invalidatorSite);
                    }
                    return original;
                }
            }, ClassReader.EXPAND_FRAMES);
            final byte[] changed = writer.toByteArray();
            if (beforeSha256 == null) {
                beforeSha256 = HexFormatHolder.format(bytes);
            }
            matches++;
            lastSites = sites[0];
            return changed;
        } catch (final Exception | LinkageError problem) {
            failure = problem.toString();
            onRejection.run();
            return null;
        }
    }

    private static final class HexFormatHolder {
        static String format(final byte[] bytes) {
            try {
                return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (final java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private record Handler(Label start, Label end, Label target, String type) { }

    /**
     * Emits the {@code getProperties → instanceof MethodHandle → checkcast}
     * prefix shared by every guarded slot lookup; leaves the handle on the
     * stack. Control lands on {@code discard} (with the raw object still on
     * the stack) when the slot is absent or of the wrong type.
     */
    private static void slotLookup(final MethodVisitor mv, final String property,
                                   final Label start, final Label discard) {
        mv.visitLabel(start);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
            "()Ljava/util/Properties;", false);
        mv.visitLdcInsn(property);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/invoke/MethodHandle");
        mv.visitJumpInsn(Opcodes.IFEQ, discard);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/invoke/MethodHandle");
    }

    /** Tracked-setter observer: entry consult with early return, body wrapped catch-all. */
    private static final class Consult extends MethodVisitor {
        private final int site;
        private final int base;
        private final int arity;
        private final List<Handler> originalHandlers = new ArrayList<>();
        private final Label bodyStart = new Label(), bodyEnd = new Label(),
            exceptionalExit = new Label();

        Consult(final MethodVisitor visitor, final int site, final int base) {
            super(Opcodes.ASM9, visitor);
            this.site = site;
            this.base = base;
            this.arity = RedundantStateElisionTarget.SITE_ARITY[site];
        }

        @Override public void visitTryCatchBlock(final Label start, final Label end,
                                                 final Label handler, final String type) {
            originalHandlers.add(new Handler(start, end, handler, type));
        }

        @Override public void visitCode() {
            super.visitCode();
            final Label start = new Label(), end = new Label(), failure = new Label();
            final Label discard = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            slotLookup(this, RedundantStateElisionBridge.CONSULT_PROPERTY, start, discard);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            pushInt(site);
            for (int i = 0; i < 4; i++) {
                if (i < arity) {
                    super.visitVarInsn(Opcodes.ILOAD, i + 1);
                } else {
                    super.visitInsn(Opcodes.ICONST_0);
                }
            }
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
                "invokeExact", "(Ljava/lang/Object;IIIII)Z", false);
            super.visitJumpInsn(Opcodes.IFEQ, done);
            super.visitInsn(Opcodes.RETURN);
            super.visitLabel(end);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(failure);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
            super.visitLabel(bodyStart);
        }

        @Override public void visitMaxs(final int stack, final int localCount) {
            super.visitLabel(bodyEnd);
            super.visitLabel(exceptionalExit);
            super.visitVarInsn(Opcodes.ASTORE, base);
            emitExceptionNotify();
            super.visitVarInsn(Opcodes.ALOAD, base);
            super.visitInsn(Opcodes.ATHROW);
            for (final Handler handler : originalHandlers) {
                super.visitTryCatchBlock(handler.start, handler.end, handler.target, handler.type);
            }
            super.visitTryCatchBlock(bodyStart, bodyEnd, exceptionalExit, null);
            super.visitMaxs(stack, Math.max(localCount, base + 1));
        }

        /** {@code slot.exception(this)} inside an always-fall-through guard. */
        private void emitExceptionNotify() {
            final Label start = new Label(), end = new Label(), failure = new Label();
            final Label discard = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            slotLookup(this, RedundantStateElisionBridge.EXCEPTION_PROPERTY, start, discard);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
                "invokeExact", "(Ljava/lang/Object;)V", false);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(failure);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }

        private void pushInt(final int value) {
            if (value >= -1 && value <= 5) {
                super.visitInsn(Opcodes.ICONST_0 + value);
            } else {
                super.visitIntInsn(Opcodes.BIPUSH, value);
            }
        }
    }

    /**
     * Invalidator observer: notifies at entry; a slot failure falls back to a
     * context clear through the exception slot so a broken bridge still fails
     * safe rather than tracking stale state.
     */
    private static final class Notify extends MethodVisitor {
        private final int site;

        Notify(final MethodVisitor visitor, final int site) {
            super(Opcodes.ASM9, visitor);
            this.site = site;
        }

        @Override public void visitCode() {
            super.visitCode();
            final Label start = new Label(), end = new Label(), failure = new Label();
            final Label discard = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            slotLookup(this, RedundantStateElisionBridge.INVALIDATE_PROPERTY, start, discard);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            pushInt(site);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
                "invokeExact", "(Ljava/lang/Object;I)V", false);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(failure);
            super.visitInsn(Opcodes.POP);
            emitClearFallback();
            super.visitLabel(done);
        }

        /** {@code slot.exception(this)} inside its own guard; failure just continues. */
        private void emitClearFallback() {
            final Label start = new Label(), end = new Label(), failure = new Label();
            final Label discard = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            slotLookup(this, RedundantStateElisionBridge.EXCEPTION_PROPERTY, start, discard);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
                "invokeExact", "(Ljava/lang/Object;)V", false);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(discard);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(failure);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }

        private void pushInt(final int value) {
            if (value <= 5) {
                super.visitInsn(Opcodes.ICONST_0 + value);
            } else if (value <= Byte.MAX_VALUE) {
                super.visitIntInsn(Opcodes.BIPUSH, value);
            } else {
                super.visitIntInsn(Opcodes.SIPUSH, value);
            }
        }
    }
}
