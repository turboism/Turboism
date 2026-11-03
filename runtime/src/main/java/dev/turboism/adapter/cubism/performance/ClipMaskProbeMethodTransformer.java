package dev.turboism.adapter.cubism.performance;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Weaves the measurement-only clipping-mask probe consults into the reviewed mask
 * and group render path.
 *
 * <p>Every woven consult is straight-line and stack-neutral: it looks a JDK
 * functional-interface slot up in {@link System#getProperties()}, checks the
 * {@code instanceof} before the cast, calls it, and discards its result. Nothing is
 * skipped, reordered, cached or short-circuited, so a probe failure can only stop
 * measurement. Each consult is wrapped in its own {@code Throwable} handler that pops
 * the throwable and continues, leaving any pending return value untouched.</p>
 *
 * <p>Transformation fails closed per class: the class must be loaded by the expected
 * loader, its code source must be the attested artifact, and the shape of every
 * method to weave must equal the shape read from the official artifact's reference
 * bytes ({@link ReviewedMethodShape}). Any drift leaves the whole class untouched.</p>
 */
public final class ClipMaskProbeMethodTransformer implements ClassFileTransformer {

    /** Opt-in system property; the probe is off by default and measures nothing when unset. */
    public static final String ENABLE_PROPERTY = "turboism.validation.clipMaskProbe";

    private static final String PROPERTIES = "java/util/Properties";
    private static final String GET_PROPERTIES = "java/lang/System";

    private final ClassLoader loader;
    private final Path artifact;
    private final Map<String, byte[]> references;
    private final List<ClipMaskProbeTargets.Slot> slots;
    private final Map<ClipMaskProbeTargets.Slot, AtomicInteger> matches = new ConcurrentHashMap<>();
    private final Map<String, String> beforeSha256 = new ConcurrentHashMap<>();
    private final Map<String, String> instrumentedSha256 = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private volatile int sites;

    /**
     * @param loader         the host class loader admitted for the artifact
     * @param artifact       official host JAR path, attested at every transform
     * @param references     official class bytes per owner internal name
     * @param slots          the reviewed selectors to weave
     */
    public ClipMaskProbeMethodTransformer(
            final ClassLoader loader,
            final Path artifact,
            final Map<String, byte[]> references,
            final List<ClipMaskProbeTargets.Slot> slots) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.references = Map.copyOf(references);
        this.slots = List.copyOf(slots);
        this.slots.forEach(slot -> matches.put(slot, new AtomicInteger()));
    }

    /**
     * @return how many times each declared selector matched. A count of zero means the
     *     method was never woven, which is how a version mismatch shows up.
     */
    public Map<ClipMaskProbeTargets.Slot, Integer> matchCounts() {
        final Map<ClipMaskProbeTargets.Slot, Integer> copy = new LinkedHashMap<>();
        matches.forEach((slot, count) -> copy.put(slot, count.get()));
        return Map.copyOf(copy);
    }

    /** @return the injected consult count */
    public int sites() {
        return sites;
    }

    /** @return the SHA-256 of the input bytes this transformer received, per owner */
    public Map<String, String> beforeSha256() {
        return Map.copyOf(beforeSha256);
    }

    /** @return the SHA-256 of the bytes this transformer returned, per owner */
    public Map<String, String> instrumentedSha256() {
        return Map.copyOf(instrumentedSha256);
    }

    /** @return the latest per-class refusal reason, or {@code null} */
    public String failure() {
        if (failures.isEmpty()) return null;
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<String, String> entry : new java.util.TreeMap<>(failures).entrySet()) {
            if (!text.isEmpty()) text.append("; ");
            text.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return text.toString();
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
        final List<ClipMaskProbeTargets.Slot> owned =
                slots.stream().filter(slot -> slot.owner().equals(name)).toList();
        if (owned.isEmpty()) return null;
        final byte[] reference = references.get(name);
        if (reference == null) {
            failures.put(name, "no reference bytes for " + name);
            return null;
        }
        try {
            if (domain == null
                    || domain.getCodeSource() == null
                    || !artifact.equals(
                            Path.of(domain.getCodeSource().getLocation().toURI())
                                    .toAbsolutePath()
                                    .normalize())) {
                failures.put(name, "clip mask probe source mismatch: " + name);
                return null;
            }
            for (final ClipMaskProbeTargets.Slot slot : owned) {
                final List<String> expected =
                        ReviewedMethodShape.read(reference, name, slot.method(), slot.descriptor());
                if (expected == null) {
                    failures.put(name, "reviewed selector absent: " + name + "." + slot.method());
                    return null;
                }
                if (!expected.equals(ReviewedMethodShape.read(bytes, name, slot.method(), slot.descriptor()))) {
                    failures.put(name, "selector shape changed: " + name + "." + slot.method());
                    return null;
                }
            }
            final ClassReader reader = new ClassReader(bytes);
            final Map<String, Integer> locals = maxLocals(reader, owned);
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
                            final ClipMaskProbeTargets.Slot slot = owned.stream()
                                    .filter(candidate -> candidate.method().equals(method)
                                            && candidate.descriptor().equals(descriptor))
                                    .findFirst()
                                    .orElse(null);
                            if (slot == null) return delegate;
                            woven[0]++;
                            matches.get(slot).incrementAndGet();
                            return new ProbeVisitor(delegate, slot, locals.getOrDefault(method + descriptor, 0));
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (woven[0] != owned.size()) {
                failures.put(name, "selector inventory mismatch on " + name + ": " + woven[0] + "/" + owned.size());
                return null;
            }
            final byte[] result = writer.toByteArray();
            beforeSha256.putIfAbsent(name, sha256(bytes));
            instrumentedSha256.putIfAbsent(name, sha256(result));
            sites += woven[0];
            return result;
        } catch (Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failures.put(name, rejected + " at " + (trace.length == 0 ? "<no frames>" : trace[0].toString()));
            return null;
        }
    }

    private static Map<String, Integer> maxLocals(
            final ClassReader reader, final List<ClipMaskProbeTargets.Slot> slots) {
        final Map<String, Integer> result = new HashMap<>();
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String method,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final boolean wanted = slots.stream()
                                .anyMatch(slot -> slot.method().equals(method)
                                        && slot.descriptor().equals(descriptor));
                        if (!wanted) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMaxs(final int stack, final int locals) {
                                result.put(method + descriptor, locals);
                            }
                        };
                    }
                },
                ClassReader.SKIP_FRAMES);
        return result;
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    /**
     * Emits the slot consult for one selector. Dispatch/group selectors also gain a
     * start stamp local and an exit consult on every return path plus a catch-all
     * handler, so time is accumulated even when the host method throws.
     */
    private static final class ProbeVisitor extends MethodVisitor {

        private final ClipMaskProbeTargets.Slot slot;
        private final int tokenLocal;
        private final Label bodyStart = new Label();
        private final Label bodyEnd = new Label();
        private final Label throwableHandler = new Label();
        private final boolean timed;

        ProbeVisitor(final MethodVisitor delegate, final ClipMaskProbeTargets.Slot slot, final int locals) {
            super(Opcodes.ASM9, delegate);
            this.slot = slot;
            this.tokenLocal = Math.max(locals, 1);
            this.timed = slot.kind() == ClipMaskProbeTargets.Kind.MASK_DISPATCH
                    || slot.kind() == ClipMaskProbeTargets.Kind.GROUP_RENDER;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            switch (slot.kind()) {
                case FRAME ->
                    emitConsult(ClipMaskProbeBridge.FRAME_PROPERTY, "java/lang/Runnable", "run", "()V", () -> {});
                case MASK_SET ->
                    emitConsult(
                            ClipMaskProbeBridge.MASK_SET_PROPERTY,
                            "java/util/function/Consumer",
                            "accept",
                            "(Ljava/lang/Object;)V",
                            () -> visitVarInsn(Opcodes.ALOAD, 0));
                case RENDER_TARGET_BIND ->
                    emitConsult(
                            ClipMaskProbeBridge.BIND_PROPERTY,
                            "java/util/function/Consumer",
                            "accept",
                            "(Ljava/lang/Object;)V",
                            () -> visitVarInsn(Opcodes.ALOAD, 0));
                case MASK_DISPATCH ->
                    emitConsult(
                            ClipMaskProbeBridge.MASK_DISPATCH_ENTER_PROPERTY,
                            "java/util/function/LongSupplier",
                            "getAsLong",
                            "()J",
                            () -> {});
                case GROUP_RENDER ->
                    emitConsult(
                            ClipMaskProbeBridge.GROUP_ENTER_PROPERTY,
                            "java/util/function/LongSupplier",
                            "getAsLong",
                            "()J",
                            () -> {});
                default -> throw new IllegalStateException("unhandled clip mask probe kind");
            }
            if (timed) visitVarInsn(Opcodes.LSTORE, tokenLocal);
            // The catch-all below starts after the guarded entry consult, so the guarded
            // entry and the catch-all never nest and ASM computes one consistent frame set.
            visitLabel(bodyStart);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (timed && opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) emitExit();
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(final int stack, final int locals) {
            if (!timed) {
                super.visitMaxs(stack, locals);
                return;
            }
            visitLabel(bodyEnd);
            super.visitTryCatchBlock(bodyStart, bodyEnd, throwableHandler, null);
            visitLabel(throwableHandler);
            final int caught = tokenLocal + 2;
            visitVarInsn(Opcodes.ASTORE, caught);
            emitExit();
            visitVarInsn(Opcodes.ALOAD, caught);
            visitInsn(Opcodes.ATHROW);
            super.visitMaxs(stack, caught + 1);
        }

        /**
         * Emits the exit consult. It is deliberately unguarded: it sits inside the
         * catch-all range, and the bridge already converts every callback failure into a
         * counter, so nothing here can escape into host code. Its unmatched paths leave the
         * operand stack exactly as they found it, so a pending return value survives.
         */
        private void emitExit() {
            emitConsult(
                    slot.kind() == ClipMaskProbeTargets.Kind.GROUP_RENDER
                            ? ClipMaskProbeBridge.GROUP_EXIT_PROPERTY
                            : ClipMaskProbeBridge.MASK_DISPATCH_EXIT_PROPERTY,
                    "java/util/function/LongConsumer",
                    "accept",
                    "(J)V",
                    () -> visitVarInsn(Opcodes.LLOAD, tokenLocal));
        }

        /**
         * Emits {@code if (System.getProperties().get(KEY) instanceof IFACE) { IFACE.method(args) }}
         * inside a {@code Throwable} handler that pops the throwable and continues. When the
         * interface method returns a value, every path pushes one so the fall-through frame
         * merges: a mistyped or absent slot yields {@code 0L}, which the bridge reads as
         * "not measuring".
         */
        private void emitConsult(
                final String key,
                final String iface,
                final String method,
                final String descriptor,
                final Runnable arguments) {
            final boolean valueReturning = descriptor.endsWith(")J");
            final Label start = new Label();
            final Label end = new Label();
            final Label handler = new Label();
            final Label discard = new Label();
            final Label done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            visitLabel(start);
            visitMethodInsn(Opcodes.INVOKESTATIC, GET_PROPERTIES, "getProperties", "()Ljava/util/Properties;", false);
            visitLdcInsn(key);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROPERTIES, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            visitInsn(Opcodes.DUP);
            visitTypeInsn(Opcodes.INSTANCEOF, iface);
            visitJumpInsn(Opcodes.IFEQ, discard);
            visitTypeInsn(Opcodes.CHECKCAST, iface);
            arguments.run();
            visitMethodInsn(Opcodes.INVOKEINTERFACE, iface, method, descriptor, true);
            visitLabel(end);
            visitJumpInsn(Opcodes.GOTO, done);
            visitLabel(discard);
            visitInsn(Opcodes.POP);
            if (valueReturning) visitInsn(Opcodes.LCONST_0);
            visitJumpInsn(Opcodes.GOTO, done);
            visitLabel(handler);
            visitInsn(Opcodes.POP);
            if (valueReturning) visitInsn(Opcodes.LCONST_0);
            visitLabel(done);
        }
    }

    /**
     * @param slots every selector of one reviewed version
     * @return the distinct owners those selectors live in, in declaration order
     */
    public static List<String> owners(final List<ClipMaskProbeTargets.Slot> slots) {
        final List<String> owners = new ArrayList<>();
        for (final ClipMaskProbeTargets.Slot slot : slots) {
            if (!owners.contains(slot.owner())) owners.add(slot.owner());
        }
        return List.copyOf(owners);
    }
}
