package dev.turboism.adapter.cubism.optimization.drag;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionTransformer;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
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
 * Verifies reviewed method shapes across all six drag-responsiveness host owners and injects
 * loader-neutral {@link System#getProperties()} consults for drag coalescing, drag-scoped UI
 * deferral with release flush, and same-cursor elision.
 */
public final class EditDragResponsivenessTransformer implements ClassFileTransformer {

    private static final String THROWABLE_OWNER = "java/lang/Throwable";
    private static final String INTEGER_OWNER = "java/lang/Integer";
    private static final String INTEGER_VALUE_OF_METHOD = "valueOf";
    private static final String INTEGER_VALUE_OF_DESCRIPTOR = "(I)Ljava/lang/Integer;";
    private static final String PREDICATE_OWNER = "java/util/function/Predicate";
    private static final String BIPREDICATE_OWNER = "java/util/function/BiPredicate";
    private static final String BIPREDICATE_TEST_METHOD = "test";
    private static final String BIPREDICATE_TEST_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

    private static final int EXPECTED_MOUSE_SITES = 3;
    private static final int EXPECTED_SINGLE_SITE = 1;
    private static final int DEFORMER_SYNC_FLAG = 2;
    private static final int DEFORMER_SKIP_EXPAND_FLAG = 1;
    private static final int ZERO_FLAG = 0;
    private static final int FINALLY_MIN_LOCAL_SLOT = 16;

    private final ClassLoader loader;
    private final Path artifact;
    private final EditDragResponsivenessTarget contract;

    private final List<String> mousePressedShape;
    private final List<String> mouseDraggedShape;
    private final List<String> mouseReleasedShape;
    private final List<String> cursorOfficialShape;
    private final List<String> cursorComposedShape;
    private final List<String> inspectorCtrlShape;
    private final List<String> inspectorPanelShape;
    private final List<String> deformerSelectorShape;
    private final List<String> deformerCtrlShape;

    private final Map<String, Integer> ownerMatches = new LinkedHashMap<>();
    private volatile String failure;
    private volatile int sites;

    /**
     * Captures exact reference method shapes for all six reviewed host classes.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param references official class bytes keyed by internal class name
     * @param contract resolved version contract
     */
    public EditDragResponsivenessTransformer(
            final ClassLoader loader,
            final Path artifact,
            final Map<String, byte[]> references,
            final EditDragResponsivenessTarget contract) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.contract = Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(references, "references");

        final byte[] mouseRef = requireReference(references, EditDragResponsivenessTarget.MOUSE_LISTENER_OWNER);
        final byte[] widgetRef = requireReference(references, EditDragResponsivenessTarget.WIDGET_OWNER);
        final byte[] inspCtrlRef = requireReference(references, EditDragResponsivenessTarget.INSPECTOR_VIEW_CTRL_OWNER);
        final byte[] inspPanelRef = requireReference(references, EditDragResponsivenessTarget.INSPECTOR_INTERNAL_OWNER);
        final byte[] selectorRef = requireReference(references, EditDragResponsivenessTarget.DEFORMER_SELECTOR_OWNER);
        final byte[] defCtrlRef = requireReference(references, EditDragResponsivenessTarget.DEFORMER_VIEW_CTRL_OWNER);

        this.mousePressedShape = requireShape(
                mouseRef,
                EditDragResponsivenessTarget.MOUSE_LISTENER_OWNER,
                EditDragResponsivenessTarget.MOUSE_PRESSED_METHOD,
                EditDragResponsivenessTarget.MOUSE_EVENT_DESCRIPTOR);
        this.mouseDraggedShape = requireShape(
                mouseRef,
                EditDragResponsivenessTarget.MOUSE_LISTENER_OWNER,
                EditDragResponsivenessTarget.MOUSE_DRAGGED_METHOD,
                EditDragResponsivenessTarget.MOUSE_EVENT_DESCRIPTOR);
        this.mouseReleasedShape = requireShape(
                mouseRef,
                EditDragResponsivenessTarget.MOUSE_LISTENER_OWNER,
                EditDragResponsivenessTarget.MOUSE_RELEASED_METHOD,
                EditDragResponsivenessTarget.MOUSE_EVENT_DESCRIPTOR);
        this.cursorOfficialShape = requireShape(
                widgetRef,
                EditDragResponsivenessTarget.WIDGET_OWNER,
                EditDragResponsivenessTarget.SET_CURSOR_METHOD,
                EditDragResponsivenessTarget.SET_CURSOR_DESCRIPTOR);
        this.cursorComposedShape = InputPathElisionTransformer.composedCursorShape(widgetRef);
        this.inspectorCtrlShape = requireShape(
                inspCtrlRef,
                EditDragResponsivenessTarget.INSPECTOR_VIEW_CTRL_OWNER,
                EditDragResponsivenessTarget.UPDATE_METHOD,
                EditDragResponsivenessTarget.UPDATE_DELAY_DESCRIPTOR);
        this.inspectorPanelShape = requireShape(
                inspPanelRef,
                EditDragResponsivenessTarget.INSPECTOR_INTERNAL_OWNER,
                EditDragResponsivenessTarget.INSPECTOR_INTERNAL_METHOD,
                EditDragResponsivenessTarget.INSPECTOR_APPLY_DESCRIPTOR);
        this.deformerSelectorShape = requireShape(
                selectorRef,
                EditDragResponsivenessTarget.DEFORMER_SELECTOR_OWNER,
                EditDragResponsivenessTarget.UPDATE_WIDGET_METHOD,
                EditDragResponsivenessTarget.VOID_DESCRIPTOR);
        this.deformerCtrlShape = requireShape(
                defCtrlRef,
                EditDragResponsivenessTarget.DEFORMER_VIEW_CTRL_OWNER,
                EditDragResponsivenessTarget.UPDATE_METHOD,
                EditDragResponsivenessTarget.UPDATE_DEFORMER_DESCRIPTOR);

        for (final String owner : contract.targetClasses()) {
            ownerMatches.put(owner, 0);
        }
    }

    /** Returns the first failure reason recorded by this transformer, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns total rewritten classes verified across all owners. */
    public int matches() {
        synchronized (ownerMatches) {
            int sum = 0;
            for (final int count : ownerMatches.values()) {
                sum += count;
            }
            return sum;
        }
    }

    /**
     * Returns verified rewrite matches for one target class internal name.
     *
     * @param owner internal class name
     * @return match count recorded for {@code owner}
     */
    public int matchesFor(final String owner) {
        synchronized (ownerMatches) {
            return ownerMatches.getOrDefault(owner, 0);
        }
    }

    /** Returns the total injected site count across all transformed classes. */
    public int sites() {
        return sites;
    }

    /** Returns total expected site injections when every reviewed owner transforms once. */
    public int expectedTotalSites() {
        return EXPECTED_MOUSE_SITES + 5 * EXPECTED_SINGLE_SITE;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (actualLoader != loader || bytes == null || !contract.targetClasses().contains(name)) {
            return null;
        }
        try {
            if (!HostCodeSource.matchesArtifact(domain, artifact)) {
                failure = "edit drag responsiveness code source mismatch: " + name;
                return null;
            }
            return switch (name) {
                case EditDragResponsivenessTarget.MOUSE_LISTENER_OWNER -> transformMouseListener(bytes);
                case EditDragResponsivenessTarget.WIDGET_OWNER -> transformWidget(bytes);
                case EditDragResponsivenessTarget.INSPECTOR_VIEW_CTRL_OWNER ->
                    transformSinglePredicateOwner(
                            bytes,
                            EditDragResponsivenessTarget.INSPECTOR_VIEW_CTRL_OWNER,
                            EditDragResponsivenessTarget.UPDATE_METHOD,
                            EditDragResponsivenessTarget.UPDATE_DELAY_DESCRIPTOR,
                            inspectorCtrlShape,
                            EditDragResponsivenessBridge.DEFER_INSPECTOR_PROPERTY);
                case EditDragResponsivenessTarget.INSPECTOR_INTERNAL_OWNER ->
                    transformSinglePredicateOwner(
                            bytes,
                            EditDragResponsivenessTarget.INSPECTOR_INTERNAL_OWNER,
                            EditDragResponsivenessTarget.INSPECTOR_INTERNAL_METHOD,
                            EditDragResponsivenessTarget.INSPECTOR_APPLY_DESCRIPTOR,
                            inspectorPanelShape,
                            EditDragResponsivenessBridge.DEFER_INSPECTOR_APPLY_PROPERTY);
                case EditDragResponsivenessTarget.DEFORMER_SELECTOR_OWNER ->
                    transformSinglePredicateOwner(
                            bytes,
                            EditDragResponsivenessTarget.DEFORMER_SELECTOR_OWNER,
                            EditDragResponsivenessTarget.UPDATE_WIDGET_METHOD,
                            EditDragResponsivenessTarget.VOID_DESCRIPTOR,
                            deformerSelectorShape,
                            EditDragResponsivenessBridge.DEFER_DEFORMER_SELECTOR_PROPERTY);
                case EditDragResponsivenessTarget.DEFORMER_VIEW_CTRL_OWNER -> transformDeformerCtrl(bytes);
                default -> null;
            };
        } catch (final Throwable rejected) {
            FatalErrors.rethrowIfFatal(rejected);
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure = rejected + " at " + (trace.length == 0 ? "<no frames>" : trace[0].toString());
            return null;
        }
    }

    private byte[] transformMouseListener(final byte[] bytes) {
        final String owner = EditDragResponsivenessTarget.MOUSE_LISTENER_OWNER;
        if (!matchesShape(
                        bytes,
                        owner,
                        EditDragResponsivenessTarget.MOUSE_PRESSED_METHOD,
                        EditDragResponsivenessTarget.MOUSE_EVENT_DESCRIPTOR,
                        mousePressedShape)
                || !matchesShape(
                        bytes,
                        owner,
                        EditDragResponsivenessTarget.MOUSE_DRAGGED_METHOD,
                        EditDragResponsivenessTarget.MOUSE_EVENT_DESCRIPTOR,
                        mouseDraggedShape)
                || !matchesShape(
                        bytes,
                        owner,
                        EditDragResponsivenessTarget.MOUSE_RELEASED_METHOD,
                        EditDragResponsivenessTarget.MOUSE_EVENT_DESCRIPTOR,
                        mouseReleasedShape)) {
            failure = "edit drag responsiveness shape changed: " + owner;
            return null;
        }
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] applied = {false, false, false};
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
                        if (!EditDragResponsivenessTarget.MOUSE_EVENT_DESCRIPTOR.equals(descriptor)) {
                            return delegate;
                        }
                        if (EditDragResponsivenessTarget.MOUSE_PRESSED_METHOD.equals(method)) {
                            return new VoidBiEntryVisitor(
                                    delegate, applied, 0, EditDragResponsivenessBridge.PRESS_PROPERTY);
                        }
                        if (EditDragResponsivenessTarget.MOUSE_DRAGGED_METHOD.equals(method)) {
                            return new EarlyReturnBiEntryVisitor(
                                    delegate, applied, 1, EditDragResponsivenessBridge.DRAG_PROPERTY);
                        }
                        if (EditDragResponsivenessTarget.MOUSE_RELEASED_METHOD.equals(method)) {
                            return new ReleaseLifecycleVisitor(delegate, applied, 2);
                        }
                        return delegate;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (!applied[0] || !applied[1] || !applied[2]) {
            failure = "edit drag responsiveness anchor drift on " + owner;
            return null;
        }
        recordOwnerMatch(owner, EXPECTED_MOUSE_SITES);
        return writer.toByteArray();
    }

    private byte[] transformWidget(final byte[] bytes) {
        final String owner = EditDragResponsivenessTarget.WIDGET_OWNER;
        final List<String> observed = ReviewedMethodShape.read(
                bytes,
                owner,
                EditDragResponsivenessTarget.SET_CURSOR_METHOD,
                EditDragResponsivenessTarget.SET_CURSOR_DESCRIPTOR);
        final boolean official = cursorOfficialShape.equals(observed);
        final boolean composed = cursorComposedShape != null && cursorComposedShape.equals(observed);
        if (!official && !composed) {
            failure = "edit drag responsiveness shape changed: " + owner;
            return null;
        }
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] applied = {false};
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
                        if (EditDragResponsivenessTarget.SET_CURSOR_METHOD.equals(method)
                                && EditDragResponsivenessTarget.SET_CURSOR_DESCRIPTOR.equals(descriptor)) {
                            return new EarlyReturnBiEntryVisitor(
                                    delegate, applied, 0, EditDragResponsivenessBridge.CURSOR_PROPERTY);
                        }
                        return delegate;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (!applied[0]) {
            failure = "edit drag responsiveness anchor drift on " + owner;
            return null;
        }
        recordOwnerMatch(owner, EXPECTED_SINGLE_SITE);
        return writer.toByteArray();
    }

    private byte[] transformSinglePredicateOwner(
            final byte[] bytes,
            final String owner,
            final String targetMethod,
            final String targetDescriptor,
            final List<String> expectedShape,
            final String propertyKey) {
        if (!matchesShape(bytes, owner, targetMethod, targetDescriptor, expectedShape)) {
            failure = "edit drag responsiveness shape changed: " + owner;
            return null;
        }
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] applied = {false};
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
                        if (targetMethod.equals(method) && targetDescriptor.equals(descriptor)) {
                            return new EarlyReturnPredicateEntryVisitor(delegate, applied, 0, propertyKey);
                        }
                        return delegate;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (!applied[0]) {
            failure = "edit drag responsiveness anchor drift on " + owner;
            return null;
        }
        recordOwnerMatch(owner, EXPECTED_SINGLE_SITE);
        return writer.toByteArray();
    }

    private byte[] transformDeformerCtrl(final byte[] bytes) {
        final String owner = EditDragResponsivenessTarget.DEFORMER_VIEW_CTRL_OWNER;
        if (!matchesShape(
                bytes,
                owner,
                EditDragResponsivenessTarget.UPDATE_METHOD,
                EditDragResponsivenessTarget.UPDATE_DEFORMER_DESCRIPTOR,
                deformerCtrlShape)) {
            failure = "edit drag responsiveness shape changed: " + owner;
            return null;
        }
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] applied = {false};
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
                        if (EditDragResponsivenessTarget.UPDATE_METHOD.equals(method)
                                && EditDragResponsivenessTarget.UPDATE_DEFORMER_DESCRIPTOR.equals(descriptor)) {
                            return new DeformerPaletteEntryVisitor(delegate, applied);
                        }
                        return delegate;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (!applied[0]) {
            failure = "edit drag responsiveness anchor drift on " + owner;
            return null;
        }
        recordOwnerMatch(owner, EXPECTED_SINGLE_SITE);
        return writer.toByteArray();
    }

    private void recordOwnerMatch(final String owner, final int injectedSites) {
        synchronized (ownerMatches) {
            ownerMatches.merge(owner, 1, Integer::sum);
        }
        sites += injectedSites;
    }

    private static byte[] requireReference(final Map<String, byte[]> references, final String owner) {
        final byte[] bytes = references.get(owner);
        if (bytes == null) {
            throw new IllegalArgumentException("missing reference bytes for " + owner);
        }
        return bytes;
    }

    private static List<String> requireShape(
            final byte[] reference, final String owner, final String method, final String descriptor) {
        final List<String> shape = ReviewedMethodShape.read(reference, owner, method, descriptor);
        if (shape == null) {
            throw new IllegalArgumentException("reviewed method absent: " + owner + '.' + method + descriptor);
        }
        return shape;
    }

    private static boolean matchesShape(
            final byte[] bytes,
            final String owner,
            final String method,
            final String descriptor,
            final List<String> expected) {
        return expected.equals(ReviewedMethodShape.read(bytes, owner, method, descriptor));
    }

    private static void emitPredicateConsult(
            final MethodVisitor mv, final String property, final boolean returnOnTrue) {
        final Label start = new Label();
        final Label end = new Label();
        final Label handler = new Label();
        final Label fallback = new Label();
        final Label resume = new Label();

        mv.visitTryCatchBlock(start, end, handler, THROWABLE_OWNER);
        mv.visitLabel(start);
        SlotBridgeEmitter.emitPredicateConsult(mv, property, fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        SlotBridgeEmitter.emitTest(mv);
        if (returnOnTrue) {
            mv.visitJumpInsn(Opcodes.IFEQ, resume);
            mv.visitInsn(Opcodes.RETURN);
        } else {
            mv.visitInsn(Opcodes.POP);
            mv.visitJumpInsn(Opcodes.GOTO, resume);
        }
        mv.visitLabel(end);
        mv.visitLabel(fallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, resume);
        mv.visitLabel(handler);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(resume);
    }

    private static void emitBiPredicateConsult(
            final MethodVisitor mv, final String property, final boolean returnOnTrue) {
        final Label start = new Label();
        final Label end = new Label();
        final Label handler = new Label();
        final Label discard = new Label();
        final Label resume = new Label();

        mv.visitTryCatchBlock(start, end, handler, THROWABLE_OWNER);
        mv.visitLabel(start);
        SlotBridgeEmitter.emitGetSlot(mv, property);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, BIPREDICATE_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, discard);
        mv.visitTypeInsn(Opcodes.CHECKCAST, BIPREDICATE_OWNER);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, BIPREDICATE_OWNER, BIPREDICATE_TEST_METHOD, BIPREDICATE_TEST_DESCRIPTOR, true);
        if (returnOnTrue) {
            mv.visitJumpInsn(Opcodes.IFEQ, resume);
            mv.visitInsn(Opcodes.RETURN);
        } else {
            mv.visitInsn(Opcodes.POP);
            mv.visitJumpInsn(Opcodes.GOTO, resume);
        }
        mv.visitLabel(end);
        mv.visitLabel(discard);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, resume);
        mv.visitLabel(handler);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(resume);
    }

    private static final class EarlyReturnPredicateEntryVisitor extends MethodVisitor {

        private final boolean[] applied;
        private final int index;
        private final String property;

        EarlyReturnPredicateEntryVisitor(
                final MethodVisitor delegate, final boolean[] applied, final int index, final String property) {
            super(Opcodes.ASM9, delegate);
            this.applied = applied;
            this.index = index;
            this.property = property;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitPredicateConsult(mv, property, true);
            applied[index] = true;
        }
    }

    private static final class EarlyReturnBiEntryVisitor extends MethodVisitor {

        private final boolean[] applied;
        private final int index;
        private final String property;

        EarlyReturnBiEntryVisitor(
                final MethodVisitor delegate, final boolean[] applied, final int index, final String property) {
            super(Opcodes.ASM9, delegate);
            this.applied = applied;
            this.index = index;
            this.property = property;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitBiPredicateConsult(mv, property, true);
            applied[index] = true;
        }
    }

    private static final class VoidBiEntryVisitor extends MethodVisitor {

        private final boolean[] applied;
        private final int index;
        private final String property;

        VoidBiEntryVisitor(
                final MethodVisitor delegate, final boolean[] applied, final int index, final String property) {
            super(Opcodes.ASM9, delegate);
            this.applied = applied;
            this.index = index;
            this.property = property;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitBiPredicateConsult(mv, property, false);
            applied[index] = true;
        }
    }

    private static final class DeformerPaletteEntryVisitor extends MethodVisitor {

        private final boolean[] applied;

        DeformerPaletteEntryVisitor(final MethodVisitor delegate, final boolean[] applied) {
            super(Opcodes.ASM9, delegate);
            this.applied = applied;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label start = new Label();
            final Label end = new Label();
            final Label handler = new Label();
            final Label discard = new Label();
            final Label syncZero = new Label();
            final Label syncDone = new Label();
            final Label expandZero = new Label();
            final Label expandDone = new Label();
            final Label resume = new Label();

            mv.visitTryCatchBlock(start, end, handler, THROWABLE_OWNER);
            mv.visitLabel(start);
            SlotBridgeEmitter.emitGetSlot(mv, EditDragResponsivenessBridge.DEFER_DEFORMER_PALETTE_PROPERTY);
            mv.visitInsn(Opcodes.DUP);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, BIPREDICATE_OWNER);
            mv.visitJumpInsn(Opcodes.IFEQ, discard);
            mv.visitTypeInsn(Opcodes.CHECKCAST, BIPREDICATE_OWNER);
            mv.visitVarInsn(Opcodes.ALOAD, 0);

            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitJumpInsn(Opcodes.IFEQ, syncZero);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitJumpInsn(Opcodes.GOTO, syncDone);
            mv.visitLabel(syncZero);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitLabel(syncDone);

            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitJumpInsn(Opcodes.IFEQ, expandZero);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitJumpInsn(Opcodes.GOTO, expandDone);
            mv.visitLabel(expandZero);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitLabel(expandDone);

            mv.visitInsn(Opcodes.IOR);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, INTEGER_OWNER, INTEGER_VALUE_OF_METHOD, INTEGER_VALUE_OF_DESCRIPTOR, false);
            mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    BIPREDICATE_OWNER,
                    BIPREDICATE_TEST_METHOD,
                    BIPREDICATE_TEST_DESCRIPTOR,
                    true);
            mv.visitJumpInsn(Opcodes.IFEQ, resume);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(end);
            mv.visitLabel(discard);
            mv.visitInsn(Opcodes.POP);
            mv.visitJumpInsn(Opcodes.GOTO, resume);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(resume);
            applied[0] = true;
        }
    }

    private static final class ReleaseLifecycleVisitor extends MethodVisitor {

        private final boolean[] applied;
        private final int index;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();
        private final Label catchAll = new Label();
        private boolean tryStarted;
        private boolean sawReturn;
        private int maxSeenLocal = FINALLY_MIN_LOCAL_SLOT;

        ReleaseLifecycleVisitor(final MethodVisitor delegate, final boolean[] applied, final int index) {
            super(Opcodes.ASM9, delegate);
            this.applied = applied;
            this.index = index;
        }

        private void ensureTryStarted() {
            if (!tryStarted) {
                tryStarted = true;
                // Register after original catch tables so host exception containment runs first.
                mv.visitTryCatchBlock(tryStart, tryEnd, catchAll, null);
                mv.visitLabel(tryStart);
            }
        }

        private void recordLocal(final int varIndex) {
            if (varIndex + 2 > maxSeenLocal) {
                maxSeenLocal = varIndex + 2;
            }
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitBiPredicateConsult(mv, EditDragResponsivenessBridge.RELEASE_BEGIN_PROPERTY, false);
        }

        @Override
        public void visitInsn(final int opcode) {
            ensureTryStarted();
            if (opcode == Opcodes.RETURN) {
                sawReturn = true;
                emitBiPredicateConsult(mv, EditDragResponsivenessBridge.RELEASE_END_PROPERTY, false);
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            ensureTryStarted();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            ensureTryStarted();
            recordLocal(varIndex);
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            ensureTryStarted();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            ensureTryStarted();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            ensureTryStarted();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name,
                final String descriptor,
                final org.objectweb.asm.Handle bootstrapMethodHandle,
                final Object... bootstrapMethodArguments) {
            ensureTryStarted();
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            ensureTryStarted();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            ensureTryStarted();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(final int varIndex, final int increment) {
            ensureTryStarted();
            recordLocal(varIndex);
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {
            ensureTryStarted();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
            ensureTryStarted();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            ensureTryStarted();
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            if (tryStarted) {
                mv.visitLabel(tryEnd);
                mv.visitLabel(catchAll);
                final int exceptionLocal = Math.max(maxSeenLocal, maxLocals);
                mv.visitVarInsn(Opcodes.ASTORE, exceptionLocal);
                emitBiPredicateConsult(mv, EditDragResponsivenessBridge.RELEASE_END_PROPERTY, false);
                mv.visitVarInsn(Opcodes.ALOAD, exceptionLocal);
                mv.visitInsn(Opcodes.ATHROW);
                super.visitMaxs(maxStack, Math.max(maxLocals, exceptionLocal + 1));
            } else {
                super.visitMaxs(maxStack, maxLocals);
            }
            if (sawReturn) {
                applied[index] = true;
            }
        }
    }
}
