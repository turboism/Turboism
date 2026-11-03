package dev.turboism.adapter.cubism.optimization.pair;

import dev.turboism.adapter.cubism.optimization.ClassPinTable;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Removes the transient {@code kotlin.Pair} objects that Kotlin's inline {@code associateBy}
 * expansion allocates once per model object inside {@code CModel.reinitModelInstance_exe()}.
 *
 * <p>Each reviewed site has the shape {@code new Pair; dup; <key>; <value>;
 * Pair.<init>(Object,Object); astore p} followed by {@code map.put(p.getFirst(),
 * p.getSecond())}. The rewrite drops the allocation and stores the two already-evaluated
 * stack values in two invocation-local slots, so the later {@code put} reads those slots
 * directly. No new runtime class, bridge, callback or shared state is introduced, and the
 * allocation count for each reinitialization drops from one Pair per object to zero.</p>
 *
 * <p>Admission is fail closed. The reviewed method shape must equal the attested artifact
 * reference, every {@code kotlin.Pair} use in that method must be one of the recognized
 * creation/getter/copy patterns, the rewrite must consume exactly the planned creations,
 * and the rewritten method must contain no remaining {@code kotlin.Pair} reference.
 * Frame recomputation uses the host loader. Any mismatch leaves the class byte-identical.</p>
 */
public final class ReinitPairElisionTransformer implements ClassFileTransformer {
    /** Exact host class carrying the model reinitialization method. */
    public static final String OWNER = "com/live2d/cubism/doc/model/CModel";
    /** Exact reviewed method whose transient Pair allocations are removed. */
    public static final String METHOD = "reinitModelInstance_exe";
    /** Exact descriptor of {@link #METHOD}. */
    public static final String DESCRIPTOR = "()V";

    private static final String PAIR = "kotlin/Pair";
    private static final String PAIR_INIT = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String PAIR_GETTER = "()Ljava/lang/Object;";
    private static final String GET_FIRST = "getFirst";
    private static final String GET_SECOND = "getSecond";

    private static final int IDLE = 0;
    private static final int AWAIT_DUP = 1;
    private static final int IN_CREATION = 2;
    private static final int AWAIT_STORE = 3;

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> shape;
    private final List<Integer> creationSlots;
    private final int localBase;
    private volatile String failure;
    private volatile String beforeSha256;
    private volatile int matches;

    private record Plan(List<Integer> creationSlots, int localBase) {}

    /**
     * Builds one inert transformer for an exact loader, artifact and reviewed reference.
     *
     * @param loader the admitted host class loader
     * @param artifact the expected defining archive
     * @param reference the original class bytes from that archive
     * @throws IllegalArgumentException when the reviewed method or its Pair sites are absent
     */
    public ReinitPairElisionTransformer(final ClassLoader loader, final Path artifact, final byte[] reference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.shape = ReviewedMethodShape.read(reference, OWNER, METHOD, DESCRIPTOR);
        if (shape == null) {
            throw new IllegalArgumentException("reviewed reinit method absent");
        }
        final Plan plan = plan(reference);
        this.creationSlots = plan.creationSlots();
        this.localBase = plan.localBase();
    }

    /** Reviewed CModel class SHA-256 pins, keyed by Cubism Editor version. */
    public static Map<String, Map<String, String>> reviewedClassSha256() {
        return REVIEWED_CLASS_SHA256;
    }

    private static final Map<String, Map<String, String>> REVIEWED_CLASS_SHA256 =
            ClassPinTable.load("reinit-pair-elision");

    /** Most recent rejection, or null when none has occurred. */
    public String failure() {
        return failure;
    }

    /** Successful class transform count. */
    public int matches() {
        return matches;
    }

    /** Original full class SHA-256, available after the first successful rewrite. */
    public String beforeSha256() {
        return beforeSha256;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (actualLoader != loader || !OWNER.equals(name) || bytes == null) {
            return null;
        }
        try {
            if (domain == null
                    || domain.getCodeSource() == null
                    || !artifact.equals(
                            Path.of(domain.getCodeSource().getLocation().toURI())
                                    .toAbsolutePath()
                                    .normalize())) {
                failure = "reinit source mismatch";
                return null;
            }
            if (!shape.equals(ReviewedMethodShape.read(bytes, OWNER, METHOD, DESCRIPTOR))) {
                failure = "reinit method changed";
                return null;
            }
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader;
                }
            };
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
                            return METHOD.equals(method) && DESCRIPTOR.equals(descriptor)
                                    ? new Rewrite(original, creationSlots, localBase)
                                    : original;
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            final byte[] result = writer.toByteArray();
            if (referencesPair(result)) {
                failure = "kotlin.Pair references remain after rewrite";
                return null;
            }
            if (beforeSha256 == null) {
                beforeSha256 = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            }
            matches++;
            return result;
        } catch (Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private static Plan plan(final byte[] reference) {
        final List<Integer> slots = new ArrayList<>();
        final int[] locals = {-1};
        final int[] state = {IDLE};
        final int[] getters = {0};
        new ClassReader(reference)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!METHOD.equals(name) || !DESCRIPTOR.equals(descriptor)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitTypeInsn(final int opcode, final String type) {
                                        if (PAIR.equals(type)) {
                                            if (opcode != Opcodes.NEW || state[0] != IDLE) {
                                                throw new IllegalArgumentException("unsupported kotlin.Pair type use");
                                            }
                                            state[0] = AWAIT_DUP;
                                            return;
                                        }
                                        rejectPending("type", state[0]);
                                    }

                                    @Override
                                    public void visitInsn(final int opcode) {
                                        if (state[0] == AWAIT_DUP) {
                                            if (opcode != Opcodes.DUP) {
                                                throw new IllegalArgumentException("kotlin.Pair NEW without DUP");
                                            }
                                            state[0] = IN_CREATION;
                                            return;
                                        }
                                        rejectPending("insn", state[0]);
                                    }

                                    @Override
                                    public void visitVarInsn(final int opcode, final int variable) {
                                        if (state[0] == AWAIT_STORE) {
                                            if (opcode != Opcodes.ASTORE) {
                                                throw new IllegalArgumentException("kotlin.Pair init without store");
                                            }
                                            slots.add(variable);
                                            state[0] = IDLE;
                                            return;
                                        }
                                        rejectPending("var", state[0]);
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String name,
                                            final String descriptor,
                                            final boolean isInterface) {
                                        if (state[0] == IN_CREATION) {
                                            if (PAIR.equals(owner)) {
                                                if (opcode == Opcodes.INVOKESPECIAL
                                                        && "<init>".equals(name)
                                                        && PAIR_INIT.equals(descriptor)) {
                                                    state[0] = AWAIT_STORE;
                                                    return;
                                                }
                                                throw new IllegalArgumentException(
                                                        "unsupported kotlin.Pair constructor");
                                            }
                                            return;
                                        }
                                        if (PAIR.equals(owner)) {
                                            if (opcode == Opcodes.INVOKEVIRTUAL
                                                    && PAIR_GETTER.equals(descriptor)
                                                    && (GET_FIRST.equals(name) || GET_SECOND.equals(name))) {
                                                getters[0]++;
                                                return;
                                            }
                                            throw new IllegalArgumentException("unsupported kotlin.Pair method");
                                        }
                                        rejectPending("method", state[0]);
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            final int opcode,
                                            final String owner,
                                            final String name,
                                            final String descriptor) {
                                        if (PAIR.equals(owner)) {
                                            throw new IllegalArgumentException("unsupported kotlin.Pair field");
                                        }
                                        rejectPending("field", state[0]);
                                    }

                                    @Override
                                    public void visitLdcInsn(final Object value) {
                                        if (referencesPair(value)) {
                                            throw new IllegalArgumentException("unsupported kotlin.Pair constant");
                                        }
                                        rejectPending("ldc", state[0]);
                                    }

                                    @Override
                                    public void visitIntInsn(final int opcode, final int operand) {
                                        rejectPending("int", state[0]);
                                    }

                                    @Override
                                    public void visitIincInsn(final int variable, final int increment) {
                                        rejectPending("iinc", state[0]);
                                    }

                                    @Override
                                    public void visitMultiANewArrayInsn(final String descriptor, final int dimensions) {
                                        if (descriptor != null && descriptor.contains(PAIR)) {
                                            throw new IllegalArgumentException("unsupported kotlin.Pair array");
                                        }
                                        rejectPending("multiarray", state[0]);
                                    }

                                    @Override
                                    public void visitJumpInsn(final int opcode, final Label label) {
                                        if (state[0] != IDLE) {
                                            throw new IllegalArgumentException("branch inside kotlin.Pair sequence");
                                        }
                                    }

                                    @Override
                                    public void visitLabel(final Label label) {
                                        if (state[0] != IDLE) {
                                            throw new IllegalArgumentException("label inside kotlin.Pair sequence");
                                        }
                                    }

                                    @Override
                                    public void visitMaxs(final int stack, final int maxLocals) {
                                        locals[0] = maxLocals;
                                    }

                                    @Override
                                    public void visitEnd() {
                                        if (state[0] != IDLE) {
                                            throw new IllegalArgumentException("unterminated kotlin.Pair sequence");
                                        }
                                        if (slots.isEmpty()) {
                                            throw new IllegalArgumentException("no kotlin.Pair creation site");
                                        }
                                        if (getters[0] == 0) {
                                            throw new IllegalArgumentException("no kotlin.Pair getter site");
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (locals[0] < 0) {
            throw new IllegalArgumentException("reviewed reinit method has no code");
        }
        return new Plan(List.copyOf(slots), locals[0]);
    }

    private static void rejectPending(final String where, final int state) {
        if (state == AWAIT_DUP) {
            throw new IllegalArgumentException("kotlin.Pair NEW without DUP before " + where);
        }
        if (state == AWAIT_STORE) {
            throw new IllegalArgumentException("kotlin.Pair init without store before " + where);
        }
    }

    private static boolean referencesPair(final byte[] bytes) {
        final boolean[] found = {false};
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
                                if (!METHOD.equals(name) || !DESCRIPTOR.equals(descriptor)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitTypeInsn(final int opcode, final String type) {
                                        if (PAIR.equals(type)) found[0] = true;
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            final int opcode,
                                            final String fieldOwner,
                                            final String fieldName,
                                            final String fieldDescriptor) {
                                        if (PAIR.equals(fieldOwner)) found[0] = true;
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String methodOwner,
                                            final String methodName,
                                            final String methodDescriptor,
                                            final boolean isInterface) {
                                        if (PAIR.equals(methodOwner)) found[0] = true;
                                    }

                                    @Override
                                    public void visitLdcInsn(final Object value) {
                                        if (referencesPair(value)) found[0] = true;
                                    }

                                    @Override
                                    public void visitMultiANewArrayInsn(
                                            final String elementDescriptor, final int dimensions) {
                                        if (elementDescriptor != null && elementDescriptor.contains(PAIR)) {
                                            found[0] = true;
                                        }
                                    }

                                    @Override
                                    public void visitInvokeDynamicInsn(
                                            final String dynamicName,
                                            final String dynamicDescriptor,
                                            final Handle bootstrap,
                                            final Object... arguments) {
                                        if (dynamicDescriptor.contains(PAIR)
                                                || (bootstrap != null && PAIR.equals(bootstrap.getOwner()))) {
                                            found[0] = true;
                                        }
                                        for (final Object argument : arguments) {
                                            if (referencesPair(argument)) found[0] = true;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean referencesPair(final Object value) {
        if (value instanceof Type type) {
            return type.getDescriptor().contains(PAIR);
        }
        if (value instanceof Handle handle) {
            return PAIR.equals(handle.getOwner());
        }
        return false;
    }

    private static final class Rewrite extends MethodVisitor {
        private final List<Integer> creationSlots;
        private final int localBase;
        private final Map<Integer, int[]> valueSlots = new HashMap<>();
        private final Set<Integer> pairLocals = new HashSet<>();
        private int creationIndex;
        private int state = IDLE;
        private int pendingLoad = -1;

        Rewrite(final MethodVisitor delegate, final List<Integer> creationSlots, final int localBase) {
            super(Opcodes.ASM9, delegate);
            this.creationSlots = creationSlots;
            this.localBase = localBase;
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (PAIR.equals(type)) {
                if (opcode != Opcodes.NEW || state != IDLE || pendingLoad >= 0) {
                    throw new IllegalStateException("unexpected kotlin.Pair allocation");
                }
                state = AWAIT_DUP;
                return;
            }
            rejectPending("type");
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (state == AWAIT_DUP) {
                if (opcode != Opcodes.DUP) {
                    throw new IllegalStateException("kotlin.Pair NEW without DUP");
                }
                state = IN_CREATION;
                return;
            }
            rejectPending("insn");
            if (pendingLoad >= 0) {
                // Kotlin's inline-function markers leave NOPs between a local load
                // and its consumer; replaying a NOP keeps the original offsets while
                // the load itself stays suppressed until its consumer is known.
                if (opcode != Opcodes.NOP) {
                    throw new IllegalStateException("kotlin.Pair escaped through an instruction consumer");
                }
                super.visitInsn(opcode);
                return;
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(final int opcode, final int variable) {
            if (state == AWAIT_STORE) {
                if (opcode != Opcodes.ASTORE || variable != creationSlots.get(creationIndex)) {
                    throw new IllegalStateException("kotlin.Pair store does not match the reviewed site");
                }
                pairLocals.add(variable);
                creationIndex++;
                state = IDLE;
                return;
            }
            rejectPending("var");
            if (pendingLoad >= 0) {
                if (opcode != Opcodes.ASTORE) {
                    throw new IllegalStateException("kotlin.Pair escaped through a local consumer");
                }
                final int[] slots = valueSlots.get(pendingLoad);
                if (slots == null) {
                    throw new IllegalStateException("kotlin.Pair local has no scratch values");
                }
                valueSlots.put(variable, slots);
                pairLocals.add(variable);
                pendingLoad = -1;
                return;
            }
            if (opcode == Opcodes.ALOAD && pairLocals.contains(variable)) {
                pendingLoad = variable;
                return;
            }
            if (opcode == Opcodes.ASTORE) {
                pairLocals.remove(variable);
                valueSlots.remove(variable);
            }
            super.visitVarInsn(opcode, variable);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            if (state == IN_CREATION) {
                if (PAIR.equals(owner)) {
                    if (opcode != Opcodes.INVOKESPECIAL || !"<init>".equals(name) || !PAIR_INIT.equals(descriptor)) {
                        throw new IllegalStateException("unexpected kotlin.Pair constructor");
                    }
                    final int[] slots = valueSlots(creationSlots.get(creationIndex));
                    super.visitInsn(Opcodes.SWAP);
                    super.visitVarInsn(Opcodes.ASTORE, slots[0]);
                    super.visitVarInsn(Opcodes.ASTORE, slots[1]);
                    state = AWAIT_STORE;
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }
            rejectPending("method");
            if (pendingLoad >= 0) {
                if (!PAIR.equals(owner)
                        || opcode != Opcodes.INVOKEVIRTUAL
                        || !PAIR_GETTER.equals(descriptor)
                        || !(GET_FIRST.equals(name) || GET_SECOND.equals(name))) {
                    throw new IllegalStateException("kotlin.Pair escaped through a method consumer");
                }
                final int[] slots = valueSlots.get(pendingLoad);
                if (slots == null) {
                    throw new IllegalStateException("kotlin.Pair local has no scratch values");
                }
                super.visitVarInsn(Opcodes.ALOAD, GET_FIRST.equals(name) ? slots[0] : slots[1]);
                pendingLoad = -1;
                return;
            }
            if (PAIR.equals(owner)) {
                throw new IllegalStateException("unsupported kotlin.Pair method");
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            if (PAIR.equals(owner)) {
                throw new IllegalStateException("unsupported kotlin.Pair field");
            }
            rejectPending("field");
            rejectPendingLoad("field");
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            if (referencesPair(value)) {
                throw new IllegalStateException("unsupported kotlin.Pair constant");
            }
            rejectPending("ldc");
            rejectPendingLoad("ldc");
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            rejectPending("int");
            rejectPendingLoad("int");
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitIincInsn(final int variable, final int increment) {
            rejectPending("iinc");
            rejectPendingLoad("iinc");
            super.visitIincInsn(variable, increment);
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int dimensions) {
            if (descriptor != null && descriptor.contains(PAIR)) {
                throw new IllegalStateException("unsupported kotlin.Pair array");
            }
            rejectPending("multiarray");
            rejectPendingLoad("multiarray");
            super.visitMultiANewArrayInsn(descriptor, dimensions);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            if (state != IDLE || pendingLoad >= 0) {
                throw new IllegalStateException("branch inside kotlin.Pair sequence");
            }
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label fallback, final Label... labels) {
            if (state != IDLE || pendingLoad >= 0) {
                throw new IllegalStateException("switch inside kotlin.Pair sequence");
            }
            super.visitTableSwitchInsn(min, max, fallback, labels);
        }

        @Override
        public void visitLookupSwitchInsn(final Label fallback, final int[] keys, final Label[] labels) {
            if (state != IDLE || pendingLoad >= 0) {
                throw new IllegalStateException("switch inside kotlin.Pair sequence");
            }
            super.visitLookupSwitchInsn(fallback, keys, labels);
        }

        @Override
        public void visitLabel(final Label label) {
            if (state != IDLE || pendingLoad >= 0) {
                throw new IllegalStateException("label inside kotlin.Pair sequence");
            }
            super.visitLabel(label);
        }

        @Override
        public void visitEnd() {
            if (state != IDLE || pendingLoad >= 0 || creationIndex != creationSlots.size()) {
                throw new IllegalStateException("kotlin.Pair rewrite did not consume every reviewed site");
            }
            super.visitEnd();
        }

        private int[] valueSlots(final int pairLocal) {
            final int[] existing = valueSlots.get(pairLocal);
            if (existing != null) return existing;
            final int base = localBase + 2 * valueSlots.size();
            final int[] created = {base, base + 1};
            valueSlots.put(pairLocal, created);
            return created;
        }

        private void rejectPending(final String where) {
            if (state == AWAIT_DUP) {
                throw new IllegalStateException("kotlin.Pair NEW without DUP before " + where);
            }
            if (state == AWAIT_STORE) {
                throw new IllegalStateException("kotlin.Pair init without store before " + where);
            }
        }

        private void rejectPendingLoad(final String where) {
            if (pendingLoad >= 0) {
                throw new IllegalStateException("kotlin.Pair escaped through a " + where + " consumer");
            }
        }
    }
}
