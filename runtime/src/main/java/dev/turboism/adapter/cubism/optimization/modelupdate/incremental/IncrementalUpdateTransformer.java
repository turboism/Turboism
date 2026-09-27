package dev.turboism.adapter.cubism.optimization.modelupdate.incremental;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import org.objectweb.asm.Type;

/**
 * Rewrites the reviewed updater and form methods so unchanged objects keep prior output.
 *
 * <p>Five guarded injection sites, all delegated through JDK functional interfaces stored
 * in {@link System#getProperties()}:</p>
 * <ul>
 *   <li>update-core entry and every {@code RETURN} — {@code Consumer<Object>} receiving
 *   {@code {CModel, CEViewContext}}; </li>
 *   <li>the loop-1 {@code ACDeformer.setDirtyDeformedForm(true)} call site —
 *   {@code BiConsumer<Object,Object>} receiving (deformer, model);</li>
 *   <li>the {@code CArtMeshForm.transform} deform call site —
 *   {@code Function<Object[],Object>} receiving
 *   {@code [preDeformForm, targetDeformer, deformedFormOut, artMesh]};</li>
 *   <li>{@code interpolate__testImpl} entry on both form classes —
 *   {@code Consumer<Object>} receiving the form instance.</li>
 * </ul>
 *
 * <p>Missing or wrongly-typed slots and any callback failure fall through to the
 * unmodified host sequence. Every admitted method shape must equal the reviewed capture
 * and both call-site patterns must be found exactly once, otherwise the class is left
 * untransformed and fails closed.</p>
 */
public final class IncrementalUpdateTransformer implements ClassFileTransformer {

    private final IncrementalUpdateTarget target;
    private final ClassLoader loader;
    private final Path artifact;
    private final List<List<String>> shapes = new ArrayList<>();
    private volatile String failure;
    private volatile int matches;
    private final java.util.Map<String, String> beforeHashes = new java.util.HashMap<>();
    private final java.util.Set<String> touched = new java.util.HashSet<>();

    private record Site(String owner, String method, String descriptor) { }

    /** Binary names of every class this transformer touches, in reference order. */
    public List<String> classNames() {
        return List.of(
            target.updater(),
            IncrementalUpdateTarget.ROTATION_FORM.replace('.', '/'),
            IncrementalUpdateTarget.WARP_FORM.replace('.', '/'),
            IncrementalUpdateTarget.MESH_FORM.replace('.', '/'));
    }

    private List<Site> sites() {
        final String up = target.updater();
        return List.of(
            new Site(up, "a", target.coreDescriptor()),
            new Site(up, "a", target.deformerUpdateDescriptor()),
            new Site(up, "a", target.artMeshUpdateDescriptor()),
            new Site(IncrementalUpdateTarget.ROTATION_FORM.replace('.', '/'),
                "interpolate__testImpl", target.deformerInterpolateDescriptor()),
            new Site(IncrementalUpdateTarget.WARP_FORM.replace('.', '/'),
                "interpolate__testImpl", target.deformerInterpolateDescriptor()),
            new Site(IncrementalUpdateTarget.MESH_FORM.replace('.', '/'),
                "interpolate__testImpl", target.meshInterpolateDescriptor()));
    }

    /**
     * @param loader the host loader this instance is allowed to rewrite
     * @param artifact official artifact, compared to each candidate's code source
     * @param references official {@link ClassReader#EXPAND_FRAMES} bytes per touched class
     * @param target the reviewed target for this artifact
     * @throws IllegalArgumentException when any reviewed method is absent from references
     */
    public IncrementalUpdateTransformer(final ClassLoader loader, final Path artifact,
                                        final List<byte[]> references,
                                        final IncrementalUpdateTarget target) {
        this.target = Objects.requireNonNull(target, "target");
        this.loader = loader;
        this.artifact = artifact == null ? null : artifact.toAbsolutePath().normalize();
        Objects.requireNonNull(references, "references");
        final List<String> classes = classNames();
        if (references.size() != classes.size()) {
            throw new IllegalArgumentException("reference count mismatch");
        }
        final Map<String, byte[]> perClass = new HashMap<>();
        for (int i = 0; i < classes.size(); i++) {
            perClass.put(classes.get(i), references.get(i));
        }
        for (final Site site : sites()) {
            final List<String> shape = ReviewedMethodShape.read(
                perClass.get(site.owner()), site.owner(), site.method(), site.descriptor());
            if (shape == null) {
                throw new IllegalArgumentException(
                    "reviewed method absent: " + site.owner() + "." + site.method());
            }
            shapes.add(shape);
        }
    }

    /** Rewritten methods observed so far. */
    public int matches() {
        return matches;
    }

    /** Most recent rejection, or null when none has occurred. */
    public String failure() {
        return failure;
    }

    /** Per-class pre-rewrite SHA-256 for restoration evidence. */
    public String beforeSha256(final String owner) {
        return beforeHashes.get(owner);
    }

    /** Every class this transformer rewrote. */
    public java.util.Set<String> touchedClasses() {
        return java.util.Set.copyOf(touched);
    }

    @Override public byte[] transform(final Module module, final ClassLoader actualLoader,
                                      final String name, final Class<?> type,
                                      final ProtectionDomain domain, final byte[] bytes) {
        if (actualLoader != loader || name == null || bytes == null) return null;
        final boolean isUpdater = target.updater().equals(name);
        final boolean isForm = !isUpdater && classNames().contains(name);
        if (!isUpdater && !isForm) return null;
        try {
            if (artifact == null || domain == null
                || !artifact.equals(Path.of(domain.getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize())) {
                failure = "incremental-update class source is not the reviewed artifact";
                return null;
            }
            final List<Site> sites = sites();
            int rewritten = 0;
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() {
                    return loader;
                }
            };
            final int[] counter = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(final int access, final String method,
                                                           final String descriptor,
                                                           final String signature,
                                                           final String[] exceptions) {
                    final MethodVisitor visitor = super.visitMethod(
                        access, method, descriptor, signature, exceptions);
                    for (int i = 0; i < sites.size(); i++) {
                        final Site site = sites.get(i);
                        if (!site.owner().equals(name) || !site.method().equals(method)
                            || !site.descriptor().equals(descriptor)) continue;
                        if (!shapes.get(i).equals(ReviewedMethodShape.read(
                                bytes, site.owner(), site.method(), site.descriptor()))) {
                            failure = "incremental-update method shape mismatch: "
                                + site.method() + site.descriptor();
                            throw new IllegalStateException(failure);
                        }
                        counter[0]++;
                        return inject(visitor, i, name, descriptor);
                    }
                    return visitor;
                }
            }, ClassReader.EXPAND_FRAMES);
            rewritten = counter[0];
            final int expected = isUpdater ? 3 : 1;
            if (rewritten != expected || failure != null) {
                failure = failure != null ? failure
                    : "incremental-update sites absent: " + rewritten + "/" + expected;
                return null;
            }
            beforeHashes.putIfAbsent(name, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)));
            touched.add(name);
            matches += rewritten;
            return writer.toByteArray();
        } catch (Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private MethodVisitor inject(final MethodVisitor visitor, final int site,
                                 final String owner, final String descriptor) {
        return switch (site) {
            case 0 -> new CoreVisitor(visitor, descriptor);
            case 1 -> new MarkDirtyVisitor(visitor, descriptor);
            case 2 -> new DeformVisitor(visitor, descriptor);
            default -> new FormVisitor(visitor);
        };
    }

    /* ------------------------------------------------------------------ helpers */

    private record Pending(int kind, int opcode, int operand, String owner, String name,
                           String descriptor, boolean itf) {
        static Pending insn(final int opcode) {
            return new Pending(0, opcode, 0, null, null, null, false);
        }
        static Pending var(final int opcode, final int slot) {
            return new Pending(1, opcode, slot, null, null, null, false);
        }
        static Pending method(final int opcode, final String owner, final String name,
                              final String descriptor, final boolean itf) {
            return new Pending(2, opcode, 0, owner, name, descriptor, itf);
        }
        static Pending typeInsn(final int opcode, final String type) {
            return new Pending(3, opcode, 0, type, null, null, false);
        }
        void replay(final MethodVisitor mv) {
            switch (kind) {
                case 0 -> mv.visitInsn(opcode);
                case 1 -> mv.visitVarInsn(opcode, operand);
                case 2 -> mv.visitMethodInsn(opcode, owner, name, descriptor, itf);
                default -> mv.visitTypeInsn(opcode, owner);
            }
        }
    }

    private static void pushInt(final MethodVisitor mv, final int value) {
        if (value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        }
    }

    /** {@code System.getProperties().get(key)} duplicated + instanceof-checked. */
    private static void emitGuardHead(final MethodVisitor mv, final String property,
                                      final String iface, final Label miss) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
            "()Ljava/util/Properties;", false);
        mv.visitLdcInsn(property);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, iface);
        mv.visitJumpInsn(Opcodes.IFEQ, miss);
        mv.visitTypeInsn(Opcodes.CHECKCAST, iface);
    }

    private static void emitObjectArray(final MethodVisitor mv, final int... slots) {
        pushInt(mv, slots.length);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        for (int i = 0; i < slots.length; i++) {
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, i);
            mv.visitVarInsn(Opcodes.ALOAD, slots[i]);
            mv.visitInsn(Opcodes.AASTORE);
        }
    }

    /** Argument local slots for a method descriptor (instance method). */
    private static int[] argSlots(final String descriptor) {
        final Type[] args = Type.getArgumentTypes(descriptor);
        final int[] slots = new int[args.length];
        int local = 1;
        for (int i = 0; i < args.length; i++) {
            slots[i] = local;
            local += args[i].getSize();
        }
        return slots;
    }

    /* ------------------------------------------------------------ core method */

    /**
     * Injects {@code begin} at entry and {@code end} before every RETURN; both receive
     * {@code Object[]{model, ctx}} resolved from the descriptor's argument slots.
     */
    private final class CoreVisitor extends MethodVisitor {
        private final List<Handler> handlers = new ArrayList<>();
        private final int modelSlot, ctxSlot;

        CoreVisitor(final MethodVisitor visitor, final String descriptor) {
            super(Opcodes.ASM9, visitor);
            final int[] slots = argSlots(descriptor);
            int model = -1, ctx = -1;
            final Type[] args = Type.getArgumentTypes(descriptor);
            for (int i = 0; i < args.length; i++) {
                if ("Lcom/live2d/cubism/doc/model/CModel;".equals(args[i].getDescriptor())) {
                    model = slots[i];
                }
                if ("Lcom/live2d/cubism/view/context/CEViewContext;"
                    .equals(args[i].getDescriptor())) {
                    ctx = slots[i];
                }
            }
            if (model < 0 || ctx < 0) {
                throw new IllegalStateException("core descriptor lacks CModel/CEViewContext");
            }
            modelSlot = model;
            ctxSlot = ctx;
        }

        @Override public void visitTryCatchBlock(final Label start, final Label end,
                                                 final Label handler, final String type) {
            handlers.add(new Handler(start, end, handler, type));
        }

        @Override public void visitCode() {
            super.visitCode();
            emitCall(IncrementalUpdateBridge.BEGIN_PROPERTY);
        }

        @Override public void visitInsn(final int opcode) {
            if (opcode == Opcodes.RETURN) emitCall(IncrementalUpdateBridge.END_PROPERTY);
            super.visitInsn(opcode);
        }

        @Override public void visitMaxs(final int stack, final int locals) {
            for (final Handler handler : handlers) {
                super.visitTryCatchBlock(handler.start(), handler.end(), handler.target(),
                    handler.type());
            }
            super.visitMaxs(stack, locals);
        }

        /** Guarded {@code consumer.accept(new Object[]{model, ctx})}, always falling through. */
        private void emitCall(final String property) {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label miss = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            emitGuardHead(mv, property, "java/util/function/Consumer", miss);
            emitObjectArray(mv, modelSlot, ctxSlot);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer",
                "accept", "(Ljava/lang/Object;)V", true);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(miss);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }
    }

    /* ------------------------------------------------------- markDirty call site */

    /**
     * Replaces {@code aload d; iconst_1; invokevirtual ACDeformer.setDirtyDeformedForm(Z)V}
     * with a guarded {@code BiConsumer.accept(deformer, model)}; the miss/failure path
     * replays the original mark. The site must be found exactly once.
     */
    private final class MarkDirtyVisitor extends MethodVisitor {
        private final Deque<Pending> pending = new ArrayDeque<>(8);
        private final int modelSlot;
        private int rewritten;

        MarkDirtyVisitor(final MethodVisitor visitor, final String descriptor) {
            super(Opcodes.ASM9, visitor);
            modelSlot = argSlots(descriptor)[0];
        }

        private boolean matches() {
            if (pending.size() != 3) return false;
            final Pending[] p = pending.toArray(new Pending[0]);
            return p[0].kind == 1 && p[0].opcode == Opcodes.ALOAD
                && p[1].kind == 0 && p[1].opcode == Opcodes.ICONST_1
                && p[2].kind == 2 && p[2].opcode == Opcodes.INVOKEVIRTUAL
                && p[2].owner.equals(target.deformerBinary())
                && p[2].name.equals("setDirtyDeformedForm")
                && p[2].descriptor.equals("(Z)V");
        }

        private boolean prefix() {
            if (pending.size() >= 3) return false;
            final Pending[] p = pending.toArray(new Pending[0]);
            for (int i = 0; i < p.length; i++) {
                final Pending x = p[i];
                final boolean ok = switch (i) {
                    case 0 -> x.kind == 1 && x.opcode == Opcodes.ALOAD;
                    case 1 -> x.kind == 0 && x.opcode == Opcodes.ICONST_1;
                    default -> true;
                };
                if (!ok) return false;
            }
            return true;
        }

        private void offer(final Pending insn) {
            pending.addLast(insn);
            while (!pending.isEmpty()) {
                if (pending.size() == 3 && matches()) {
                    emitReplacement(pending.peekFirst().operand);
                    pending.clear();
                    rewritten++;
                    return;
                }
                if (prefix()) return;
                pending.removeFirst().replay(mv);
            }
        }

        private void flush() {
            while (!pending.isEmpty()) pending.removeFirst().replay(mv);
        }

        @Override public void visitVarInsn(final int opcode, final int slot) {
            offer(Pending.var(opcode, slot));
        }

        @Override public void visitInsn(final int opcode) {
            offer(Pending.insn(opcode));
        }

        @Override public void visitMethodInsn(final int opcode, final String owner,
                                              final String name, final String descriptor,
                                              final boolean itf) {
            offer(Pending.method(opcode, owner, name, descriptor, itf));
        }

        @Override public void visitTypeInsn(final int opcode, final String type) {
            flush();
            super.visitTypeInsn(opcode, type);
        }

        @Override public void visitJumpInsn(final int opcode, final Label label) {
            flush();
            super.visitJumpInsn(opcode, label);
        }

        @Override public void visitLabel(final Label label) {
            flush();
            super.visitLabel(label);
        }

        @Override public void visitLdcInsn(final Object value) {
            flush();
            super.visitLdcInsn(value);
        }

        @Override public void visitIincInsn(final int slot, final int amount) {
            flush();
            super.visitIincInsn(slot, amount);
        }

        @Override public void visitIntInsn(final int opcode, final int operand) {
            flush();
            super.visitIntInsn(opcode, operand);
        }

        @Override public void visitFieldInsn(final int opcode, final String owner,
                                             final String name, final String descriptor) {
            flush();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override public void visitFrame(final int type, final int count,
                                         final Object[] locals, final int stackCount,
                                         final Object[] stack) {
            flush();
            super.visitFrame(type, count, locals, stackCount, stack);
        }

        @Override public void visitTableSwitchInsn(final int min, final int max,
                                                   final Label dflt, final Label... labels) {
            flush();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override public void visitLookupSwitchInsn(final Label dflt, final int[] keys,
                                                    final Label[] labels) {
            flush();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override public void visitMultiANewArrayInsn(final String descriptor, final int dims) {
            flush();
            super.visitMultiANewArrayInsn(descriptor, dims);
        }

        @Override public void visitInvokeDynamicInsn(final String name, final String descriptor,
                                                     final org.objectweb.asm.Handle handle,
                                                     final Object... args) {
            flush();
            super.visitInvokeDynamicInsn(name, descriptor, handle, args);
        }

        @Override public void visitTryCatchBlock(final Label start, final Label end,
                                                 final Label handler, final String type) {
            flush();
            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override public void visitEnd() {
            flush();
            if (rewritten != 1) {
                failure = "dirty-mark call site found " + rewritten + " times";
            }
            super.visitEnd();
        }

        private void emitReplacement(final int deformerSlot) {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label miss = new Label(), nativ = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            emitGuardHead(mv, IncrementalUpdateBridge.MARK_PROPERTY,
                "java/util/function/BiConsumer", miss);
            super.visitVarInsn(Opcodes.ALOAD, deformerSlot);
            super.visitVarInsn(Opcodes.ALOAD, modelSlot);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiConsumer",
                "accept", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(miss);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, nativ);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(nativ);
            super.visitVarInsn(Opcodes.ALOAD, deformerSlot);
            super.visitInsn(Opcodes.ICONST_1);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, target.deformerBinary(),
                "setDirtyDeformedForm", "(Z)V", false);
            super.visitLabel(done);
        }
    }

    /* -------------------------------------------------------- deform call site */

    /**
     * Replaces the six-instruction deform sequence
     * {@code aload p; aload t; getCreateLocalToCanvasTransform; aload o; checkcast ACForm;
     * invokevirtual CArtMeshForm.transform} with a guarded
     * {@code Function.apply(new Object[]{p, t, o, mesh})}; the miss/failure path replays
     * the original sequence. The site must be found exactly once.
     */
    private final class DeformVisitor extends MethodVisitor {
        private final Deque<Pending> pending = new ArrayDeque<>(8);
        private final int meshSlot;
        private int rewritten;

        DeformVisitor(final MethodVisitor visitor, final String descriptor) {
            super(Opcodes.ASM9, visitor);
            final int[] slots = argSlots(descriptor);
            int mesh = -1;
            final Type[] args = Type.getArgumentTypes(descriptor);
            for (int i = 0; i < args.length; i++) {
                if ("Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMesh;"
                    .equals(args[i].getDescriptor())) {
                    mesh = slots[i];
                }
            }
            if (mesh < 0) {
                throw new IllegalStateException("artMesh descriptor lacks CArtMesh");
            }
            meshSlot = mesh;
        }

        private boolean matchAt(final Pending[] p, final int i) {
            final Pending x = p[i];
            return switch (i) {
                case 0, 1, 3 -> x.kind == 1 && x.opcode == Opcodes.ALOAD;
                case 2 -> x.kind == 2 && x.opcode == Opcodes.INVOKEVIRTUAL
                    && x.owner.equals(target.deformerBinary())
                    && x.name.equals("getCreateLocalToCanvasTransform")
                    && x.descriptor.equals("()Lcom/live2d/doc/selection/d;");
                case 4 -> x.kind == 3 && x.opcode == Opcodes.CHECKCAST
                    && x.owner.equals("com/live2d/cubism/doc/model/ACForm");
                default -> x.kind == 2 && x.opcode == Opcodes.INVOKEVIRTUAL
                    && x.owner.equals(target.meshFormBinary())
                    && x.name.equals("transform")
                    && x.descriptor.equals(
                        "(Lcom/live2d/doc/selection/d;Lcom/live2d/cubism/doc/model/ACForm;)"
                            + "Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm;");
            };
        }

        private void offer(final Pending insn) {
            pending.addLast(insn);
            while (!pending.isEmpty()) {
                final Pending[] p = pending.toArray(new Pending[0]);
                boolean prefix = p.length <= 6;
                for (int i = 0; prefix && i < p.length; i++) {
                    prefix = matchAt(p, i);
                }
                if (prefix && p.length == 6) {
                    emitReplacement(p[0].operand, p[1].operand, p[3].operand);
                    pending.clear();
                    rewritten++;
                    return;
                }
                if (prefix) return;
                pending.removeFirst().replay(mv);
            }
        }

        private void flush() {
            while (!pending.isEmpty()) pending.removeFirst().replay(mv);
        }

        @Override public void visitVarInsn(final int opcode, final int slot) {
            offer(Pending.var(opcode, slot));
        }

        @Override public void visitInsn(final int opcode) {
            offer(Pending.insn(opcode));
        }

        @Override public void visitMethodInsn(final int opcode, final String owner,
                                              final String name, final String descriptor,
                                              final boolean itf) {
            offer(Pending.method(opcode, owner, name, descriptor, itf));
        }

        @Override public void visitTypeInsn(final int opcode, final String type) {
            offer(Pending.typeInsn(opcode, type));
        }

        @Override public void visitJumpInsn(final int opcode, final Label label) {
            flush();
            super.visitJumpInsn(opcode, label);
        }

        @Override public void visitLabel(final Label label) {
            flush();
            super.visitLabel(label);
        }

        @Override public void visitLdcInsn(final Object value) {
            flush();
            super.visitLdcInsn(value);
        }

        @Override public void visitIincInsn(final int slot, final int amount) {
            flush();
            super.visitIincInsn(slot, amount);
        }

        @Override public void visitIntInsn(final int opcode, final int operand) {
            flush();
            super.visitIntInsn(opcode, operand);
        }

        @Override public void visitFieldInsn(final int opcode, final String owner,
                                             final String name, final String descriptor) {
            flush();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override public void visitFrame(final int type, final int count,
                                         final Object[] locals, final int stackCount,
                                         final Object[] stack) {
            flush();
            super.visitFrame(type, count, locals, stackCount, stack);
        }

        @Override public void visitTableSwitchInsn(final int min, final int max,
                                                   final Label dflt, final Label... labels) {
            flush();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override public void visitLookupSwitchInsn(final Label dflt, final int[] keys,
                                                    final Label[] labels) {
            flush();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override public void visitMultiANewArrayInsn(final String descriptor, final int dims) {
            flush();
            super.visitMultiANewArrayInsn(descriptor, dims);
        }

        @Override public void visitInvokeDynamicInsn(final String name, final String descriptor,
                                                     final org.objectweb.asm.Handle handle,
                                                     final Object... args) {
            flush();
            super.visitInvokeDynamicInsn(name, descriptor, handle, args);
        }

        @Override public void visitTryCatchBlock(final Label start, final Label end,
                                                 final Label handler, final String type) {
            flush();
            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override public void visitEnd() {
            flush();
            if (rewritten != 1) {
                failure = "deform call site found " + rewritten + " times";
            }
            super.visitEnd();
        }

        private void emitReplacement(final int preDeformSlot, final int targetSlot,
                                     final int outSlot) {
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label miss = new Label(), nativ = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            emitGuardHead(mv, IncrementalUpdateBridge.DEFORM_PROPERTY,
                "java/util/function/Function", miss);
            emitObjectArray(mv, preDeformSlot, targetSlot, outSlot, meshSlot);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function",
                "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(miss);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, nativ);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(nativ);
            super.visitVarInsn(Opcodes.ALOAD, preDeformSlot);
            super.visitVarInsn(Opcodes.ALOAD, targetSlot);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, target.deformerBinary(),
                "getCreateLocalToCanvasTransform", "()Lcom/live2d/doc/selection/d;", false);
            super.visitVarInsn(Opcodes.ALOAD, outSlot);
            super.visitTypeInsn(Opcodes.CHECKCAST, "com/live2d/cubism/doc/model/ACForm");
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, target.meshFormBinary(),
                "transform",
                "(Lcom/live2d/doc/selection/d;Lcom/live2d/cubism/doc/model/ACForm;)"
                    + "Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm;", false);
            super.visitLabel(done);
        }
    }

    /* ------------------------------------------------------ interpolate record */

    /** Injects a guarded {@code consumer.accept(this)} at interpolate__testImpl entry. */
    private final class FormVisitor extends MethodVisitor {
        FormVisitor(final MethodVisitor visitor) {
            super(Opcodes.ASM9, visitor);
        }

        @Override public void visitCode() {
            super.visitCode();
            final Label start = new Label(), end = new Label(), handler = new Label();
            final Label miss = new Label(), done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            emitGuardHead(mv, IncrementalUpdateBridge.FORM_PROPERTY,
                "java/util/function/Consumer", miss);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer",
                "accept", "(Ljava/lang/Object;)V", true);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(miss);
            super.visitInsn(Opcodes.POP);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }
    }

    private record Handler(Label start, Label end, Label target, String type) { }
}
