package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Shared ASM bytecode adapters for edit-mode subtree updates and deformer overlay elision.
 *
 * <p>Uses the repository's fail-closed {@link SlotBridgeEmitter} pattern at method entry and
 * normal {@code RETURN} exits without altering surrounding host exception tables or logic.</p>
 */
final class EditSubtreeMethodWeavers {

    private static final String THROWABLE_OWNER = "java/lang/Throwable";
    private static final String METHOD_HANDLE_OWNER = "java/lang/invoke/MethodHandle";
    private static final String INVOKE_EXACT_METHOD = "invokeExact";

    private static final String MH_UPDATE_CORE_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String MH_END_CORE_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String MH_SKIP_MESH_APPEARANCE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String MH_OVERLAY_ELIDE_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;ZZLjava/lang/Object;Ljava/lang/Object;)Z";
    private static final String MH_OVERLAY_RECORD_DESC = "(Ljava/lang/Object;ZZLjava/lang/Object;Ljava/lang/Object;)V";
    private static final String MH_LEGACY_OVERLAY_ELIDE_DESC = "(Ljava/lang/Object;)Z";
    private static final String MH_LEGACY_OVERLAY_RECORD_DESC = "(Ljava/lang/Object;)V";

    private EditSubtreeMethodWeavers() {}

    /** Creates the {@code updater.updateCore} entry/tail adapter for a reviewed target build. */
    static MethodVisitor coreUpdateAdapter(final MethodVisitor out) {
        return new HeadGateAndReturnTailAdapter(
                out,
                WarpDeformEditBridge.EDIT_SUBTREE_UPDATE_MODEL_PROPERTY,
                MH_UPDATE_CORE_DESC,
                WarpDeformEditBridge.EDIT_SUBTREE_END_MODEL_PROPERTY,
                MH_END_CORE_DESC,
                EditSubtreeMethodWeavers::pushCoreUpdateArgs,
                EditSubtreeMethodWeavers::pushCoreUpdateArgs);
    }

    /** Creates the {@code updater.updateArtMeshAppearance} entry skip adapter for clean ArtMeshes. */
    static MethodVisitor artMeshAppearanceAdapter(final MethodVisitor out) {
        return new HeadGateOnlyAdapter(
                out,
                WarpDeformEditBridge.EDIT_SUBTREE_SKIP_MESH_APPEARANCE_PROPERTY,
                MH_SKIP_MESH_APPEARANCE_DESC,
                mv -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitVarInsn(Opcodes.ALOAD, 2);
                });
    }

    /** Creates the Cubism 5.1+ {@code K$b.a} deformer overlay entry/tail adapter. */
    static MethodVisitor overlayHelperAdapter(final MethodVisitor out) {
        return new HeadGateAndReturnTailAdapter(
                out,
                WarpDeformEditBridge.EDIT_OVERLAY_ELIDE_PROPERTY,
                MH_OVERLAY_ELIDE_DESC,
                WarpDeformEditBridge.EDIT_OVERLAY_RECORD_PROPERTY,
                MH_OVERLAY_RECORD_DESC,
                EditSubtreeMethodWeavers::pushOverlayElideArgs,
                EditSubtreeMethodWeavers::pushOverlayRecordArgs);
    }

    /** Creates the Cubism 5.0.x {@code J.E}/{@code J.F} deformer overlay entry/tail adapter. */
    static MethodVisitor legacyOverlayViewAdapter(final MethodVisitor out) {
        return new HeadGateAndReturnTailAdapter(
                out,
                WarpDeformEditBridge.EDIT_LEGACY_OVERLAY_ELIDE_PROPERTY,
                MH_LEGACY_OVERLAY_ELIDE_DESC,
                WarpDeformEditBridge.EDIT_LEGACY_OVERLAY_RECORD_PROPERTY,
                MH_LEGACY_OVERLAY_RECORD_DESC,
                mv -> mv.visitVarInsn(Opcodes.ALOAD, 0),
                mv -> mv.visitVarInsn(Opcodes.ALOAD, 0));
    }

    private static void pushCoreUpdateArgs(final MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
    }

    private static void pushOverlayElideArgs(final MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
    }

    private static void pushOverlayRecordArgs(final MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
    }

    @FunctionalInterface
    private interface ArgEmitter {
        void emit(MethodVisitor mv);
    }

    private static final class HeadGateAndReturnTailAdapter extends MethodVisitor {

        private final String headSlotKey;
        private final String headMhDesc;
        private final String tailSlotKey;
        private final String tailMhDesc;
        private final ArgEmitter headArgs;
        private final ArgEmitter tailArgs;

        private HeadGateAndReturnTailAdapter(
                final MethodVisitor out,
                final String headSlotKey,
                final String headMhDesc,
                final String tailSlotKey,
                final String tailMhDesc,
                final ArgEmitter headArgs,
                final ArgEmitter tailArgs) {
            super(Opcodes.ASM9, out);
            this.headSlotKey = headSlotKey;
            this.headMhDesc = headMhDesc;
            this.tailSlotKey = tailSlotKey;
            this.tailMhDesc = tailMhDesc;
            this.headArgs = headArgs;
            this.tailArgs = tailArgs;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label handler = new Label();
            final Label notHandle = new Label();
            final Label fallback = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
            mv.visitLabel(tryStart);
            SlotBridgeEmitter.emitMethodHandleConsult(mv, headSlotKey, notHandle);
            headArgs.emit(mv);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, headMhDesc, false);
            mv.visitJumpInsn(Opcodes.IFEQ, fallback);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(notHandle);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, fallback);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(fallback);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.RETURN) {
                final Label tryStart = new Label();
                final Label tryEnd = new Label();
                final Label handler = new Label();
                final Label notHandle = new Label();
                final Label fallback = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
                mv.visitLabel(tryStart);
                SlotBridgeEmitter.emitMethodHandleConsult(mv, tailSlotKey, notHandle);
                tailArgs.emit(mv);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, tailMhDesc, false);
                mv.visitJumpInsn(Opcodes.GOTO, fallback);
                mv.visitLabel(notHandle);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(tryEnd);
                mv.visitJumpInsn(Opcodes.GOTO, fallback);
                mv.visitLabel(handler);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(fallback);
            }
            super.visitInsn(opcode);
        }
    }

    private static final class HeadGateOnlyAdapter extends MethodVisitor {

        private final String headSlotKey;
        private final String headMhDesc;
        private final ArgEmitter headArgs;

        private HeadGateOnlyAdapter(
                final MethodVisitor out, final String headSlotKey, final String headMhDesc, final ArgEmitter headArgs) {
            super(Opcodes.ASM9, out);
            this.headSlotKey = headSlotKey;
            this.headMhDesc = headMhDesc;
            this.headArgs = headArgs;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label handler = new Label();
            final Label notHandle = new Label();
            final Label fallback = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
            mv.visitLabel(tryStart);
            SlotBridgeEmitter.emitMethodHandleConsult(mv, headSlotKey, notHandle);
            headArgs.emit(mv);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, headMhDesc, false);
            mv.visitJumpInsn(Opcodes.IFEQ, fallback);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(notHandle);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, fallback);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(fallback);
        }
    }
}
