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

    private static final String ARRAYS_OWNER = "java/util/Arrays";
    private static final String ARRAYS_COPY_OF_METHOD = "copyOf";
    private static final String ARRAYS_COPY_OF_DESCRIPTOR = "([FI)[F";
    private static final String MH_WARP_REINIT_POS_DESC = "(Ljava/lang/Object;Ljava/lang/Object;I)[F";
    private static final String MH_ROT_SCRATCH_DESC = "(I)[F";
    private static final String MH_SELECTION_DRAW_ELIDE_DESC = "(Ljava/lang/Object;)Z";
    private static final String MH_SELECTION_DRAW_RECORD_DESC = "(Ljava/lang/Object;)V";
    private static final String MH_SELECTION_MESH_ELIDE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;ZZ)Z";

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

    /** Creates the {@code CWarpDeformerSource.reinitInstance} array-reuse adapter. */
    static MethodVisitor warpSourceReinitAdapter(final MethodVisitor out, final int nextFreeLocal) {
        return new WarpSourceReinitAdapter(out, nextFreeLocal);
    }

    /** Creates a {@code float[2]} scratch-array substitution adapter for rotation deformer transforms. */
    static MethodVisitor rotationScratchAdapter(final MethodVisitor out, final int[] slotIndices, final int tmpLocal) {
        return new RotationScratchAdapter(out, slotIndices, tmpLocal);
    }

    /** Creates the {@code updateSelectionDraw} entry/tail gate plus lazy create and volatile/mesh elision adapter. */
    static MethodVisitor selectionDrawAdapter(
            final MethodVisitor out,
            final int srcMeshSlot,
            final int srcPackSlot,
            final int srcSelectedSlot,
            final int srcGluedSlot,
            final int nextFreeLocal) {
        final MethodVisitor headTail = new HeadGateAndReturnTailAdapter(
                out,
                WarpDeformEditBridge.EDIT_SELECTION_DRAW_ELIDE_PROPERTY,
                MH_SELECTION_DRAW_ELIDE_DESC,
                WarpDeformEditBridge.EDIT_SELECTION_DRAW_RECORD_PROPERTY,
                MH_SELECTION_DRAW_RECORD_DESC,
                mv -> mv.visitVarInsn(Opcodes.ALOAD, 0),
                mv -> mv.visitVarInsn(Opcodes.ALOAD, 0));
        return new ArtMeshSelectionCallSiteAdapter(
                headTail, srcMeshSlot, srcPackSlot, srcSelectedSlot, srcGluedSlot, nextFreeLocal);
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

    private static final class WarpSourceReinitAdapter extends MethodVisitor {
        private final int srcLocal;
        private final int lenLocal;
        private final int formLocal;
        private final int reusedLocal;

        private WarpSourceReinitAdapter(final MethodVisitor out, final int nextFreeLocal) {
            super(Opcodes.ASM9, out);
            this.srcLocal = nextFreeLocal;
            this.lenLocal = nextFreeLocal + 1;
            this.formLocal = nextFreeLocal + 2;
            this.reusedLocal = nextFreeLocal + 3;
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            if (opcode == Opcodes.INVOKESTATIC
                    && ARRAYS_OWNER.equals(owner)
                    && ARRAYS_COPY_OF_METHOD.equals(name)
                    && ARRAYS_COPY_OF_DESCRIPTOR.equals(desc)) {
                final Label tryStart = new Label();
                final Label tryEnd = new Label();
                final Label handler = new Label();
                final Label notHandle = new Label();
                final Label fallback = new Label();
                final Label done = new Label();

                mv.visitVarInsn(Opcodes.ISTORE, lenLocal);
                mv.visitVarInsn(Opcodes.ASTORE, srcLocal);
                mv.visitVarInsn(Opcodes.ASTORE, formLocal);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitVarInsn(Opcodes.ASTORE, reusedLocal);

                mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
                mv.visitLabel(tryStart);
                SlotBridgeEmitter.emitMethodHandleConsult(mv, WarpDeformEditBridge.WARP_REINIT_POS_PROPERTY, notHandle);
                mv.visitVarInsn(Opcodes.ALOAD, srcLocal);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ILOAD, lenLocal);
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        METHOD_HANDLE_OWNER,
                        INVOKE_EXACT_METHOD,
                        MH_WARP_REINIT_POS_DESC,
                        false);
                mv.visitVarInsn(Opcodes.ASTORE, reusedLocal);
                mv.visitJumpInsn(Opcodes.GOTO, fallback);
                mv.visitLabel(notHandle);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(tryEnd);
                mv.visitJumpInsn(Opcodes.GOTO, fallback);
                mv.visitLabel(handler);
                mv.visitInsn(Opcodes.POP);

                mv.visitLabel(fallback);
                mv.visitVarInsn(Opcodes.ALOAD, reusedLocal);
                mv.visitJumpInsn(Opcodes.IFNULL, done);
                mv.visitVarInsn(Opcodes.ALOAD, formLocal);
                mv.visitVarInsn(Opcodes.ALOAD, reusedLocal);
                final Label endLabel = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, endLabel);

                mv.visitLabel(done);
                mv.visitVarInsn(Opcodes.ALOAD, formLocal);
                mv.visitVarInsn(Opcodes.ALOAD, srcLocal);
                mv.visitVarInsn(Opcodes.ILOAD, lenLocal);
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                mv.visitLabel(endLabel);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            super.visitMaxs(maxStack + 6, Math.max(maxLocals, reusedLocal + 1));
        }
    }

    private static final class RotationScratchAdapter extends MethodVisitor {
        private final int[] slots;
        private final int tmpLocal;
        private int nextSite;
        private boolean pendingIconst2;

        private RotationScratchAdapter(final MethodVisitor out, final int[] slots, final int tmpLocal) {
            super(Opcodes.ASM9, out);
            this.slots = slots;
            this.tmpLocal = tmpLocal;
        }

        private void flushPending() {
            if (pendingIconst2) {
                pendingIconst2 = false;
                super.visitInsn(Opcodes.ICONST_2);
            }
        }

        @Override
        public void visitInsn(final int opcode) {
            flushPending();
            if (opcode == Opcodes.ICONST_2 && nextSite < slots.length) {
                pendingIconst2 = true;
                return;
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            if (pendingIconst2 && opcode == Opcodes.NEWARRAY && operand == Opcodes.T_FLOAT && nextSite < slots.length) {
                pendingIconst2 = false;
                final int scratchSlot = slots[nextSite++];
                final Label tryStart = new Label();
                final Label tryEnd = new Label();
                final Label handler = new Label();
                final Label notHandle = new Label();
                final Label afterTry = new Label();
                final Label done = new Label();

                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitVarInsn(Opcodes.ASTORE, tmpLocal);
                mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
                mv.visitLabel(tryStart);
                SlotBridgeEmitter.emitMethodHandleConsult(mv, WarpDeformEditBridge.ROT_SCRATCH_PROPERTY, notHandle);
                mv.visitIntInsn(Opcodes.BIPUSH, scratchSlot);
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, MH_ROT_SCRATCH_DESC, false);
                mv.visitVarInsn(Opcodes.ASTORE, tmpLocal);
                mv.visitJumpInsn(Opcodes.GOTO, afterTry);
                mv.visitLabel(notHandle);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(tryEnd);
                mv.visitJumpInsn(Opcodes.GOTO, afterTry);
                mv.visitLabel(handler);
                mv.visitInsn(Opcodes.POP);

                mv.visitLabel(afterTry);
                mv.visitVarInsn(Opcodes.ALOAD, tmpLocal);
                mv.visitInsn(Opcodes.DUP);
                mv.visitJumpInsn(Opcodes.IFNONNULL, done);
                mv.visitInsn(Opcodes.POP);
                super.visitInsn(Opcodes.ICONST_2);
                super.visitIntInsn(opcode, operand);
                mv.visitLabel(done);
                return;
            }
            flushPending();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            flushPending();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            flushPending();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String desc) {
            flushPending();
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            flushPending();
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name, final String desc, final org.objectweb.asm.Handle bsm, final Object... bsmArgs) {
            flushPending();
            super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            flushPending();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(final Label label) {
            flushPending();
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            flushPending();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(final int varIndex, final int increment) {
            flushPending();
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {
            flushPending();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
            flushPending();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            flushPending();
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            flushPending();
            super.visitMaxs(maxStack + 4, Math.max(maxLocals, tmpLocal + 1));
        }
    }

    private static final class ArtMeshSelectionCallSiteAdapter extends MethodVisitor {
        private final int srcMeshSlot;
        private final int srcPackSlot;
        private final int srcSelectedSlot;
        private final int srcGluedSlot;
        private final int entityLocal;
        private final int packLocal;
        private final int flag1Local;
        private final int flag2Local;
        private final int skipLocal;
        private final int meshElideMhLocal;
        private final int createElideMhLocal;
        private final int setVolElideMhLocal;
        private final int setVolSceneTmpLocal;
        private final int setVolEntityTmpLocal;
        private final Label postArtMeshSetVolatileLabel = new Label();
        private boolean sawArtMeshCreate;
        private boolean awaitingArtMeshSetVolatile;

        private ArtMeshSelectionCallSiteAdapter(
                final MethodVisitor out,
                final int srcMeshSlot,
                final int srcPackSlot,
                final int srcSelectedSlot,
                final int srcGluedSlot,
                final int nextFreeLocal) {
            super(Opcodes.ASM9, out);
            this.srcMeshSlot = srcMeshSlot;
            this.srcPackSlot = srcPackSlot;
            this.srcSelectedSlot = srcSelectedSlot;
            this.srcGluedSlot = srcGluedSlot;
            this.entityLocal = nextFreeLocal;
            this.packLocal = nextFreeLocal + 1;
            this.flag1Local = nextFreeLocal + 2;
            this.flag2Local = nextFreeLocal + 3;
            this.skipLocal = nextFreeLocal + 4;
            this.meshElideMhLocal = nextFreeLocal + 5;
            this.createElideMhLocal = nextFreeLocal + 6;
            this.setVolElideMhLocal = nextFreeLocal + 7;
            this.setVolSceneTmpLocal = nextFreeLocal + 8;
            this.setVolEntityTmpLocal = nextFreeLocal + 9;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitConsultOptionalHandle(mv, WarpDeformEditBridge.EDIT_SELECTION_MESH_ELIDE_PROPERTY, meshElideMhLocal);
            emitConsultOptionalHandle(
                    mv, WarpDeformEditBridge.EDIT_SELECTION_MESH_CREATE_ELIDE_PROPERTY, createElideMhLocal);
            emitConsultOptionalHandle(
                    mv, WarpDeformEditBridge.EDIT_SELECTION_SET_VOLATILE_ELIDE_PROPERTY, setVolElideMhLocal);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (!sawArtMeshCreate
                    && opcode == Opcodes.NEW
                    && WarpDeformEditTarget.DRAW_ENTITY_ARTMESH_SELECTION_OWNER.equals(type)) {
                sawArtMeshCreate = true;
                final Label proceedCreate = new Label();
                // Skip lazy creation when selected, glued, hovered, or flagged overlays require the entity.
                mv.visitVarInsn(Opcodes.ILOAD, srcSelectedSlot);
                mv.visitJumpInsn(Opcodes.IFNE, proceedCreate);
                mv.visitVarInsn(Opcodes.ILOAD, srcGluedSlot);
                mv.visitJumpInsn(Opcodes.IFNE, proceedCreate);
                mv.visitVarInsn(Opcodes.ALOAD, createElideMhLocal);
                mv.visitJumpInsn(Opcodes.IFNULL, proceedCreate);
                emitElidePredicateCheck(
                        mv, createElideMhLocal, srcMeshSlot, srcPackSlot, srcSelectedSlot, srcGluedSlot, skipLocal);
                mv.visitVarInsn(Opcodes.ILOAD, skipLocal);
                mv.visitJumpInsn(Opcodes.IFNE, postArtMeshSetVolatileLabel);
                mv.visitLabel(proceedCreate);
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && WarpDeformEditTarget.DRAW_ENTITY_ARTMESH_SELECTION_OWNER.equals(owner)
                    && WarpDeformEditTarget.DRAW_ENTITY_ARTMESH_UPDATE_METHOD.equals(name)
                    && WarpDeformEditTarget.DRAW_ENTITY_ARTMESH_UPDATE_DESCRIPTOR.equals(desc)) {
                final Label skipCall = new Label();
                mv.visitVarInsn(Opcodes.ISTORE, flag2Local);
                mv.visitVarInsn(Opcodes.ISTORE, flag1Local);
                mv.visitVarInsn(Opcodes.ASTORE, packLocal);
                mv.visitVarInsn(Opcodes.ASTORE, entityLocal);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitVarInsn(Opcodes.ISTORE, skipLocal);

                final Label afterCheck = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, meshElideMhLocal);
                mv.visitJumpInsn(Opcodes.IFNULL, afterCheck);
                emitElidePredicateCheck(
                        mv, meshElideMhLocal, entityLocal, packLocal, flag1Local, flag2Local, skipLocal);
                mv.visitLabel(afterCheck);
                mv.visitVarInsn(Opcodes.ILOAD, skipLocal);
                mv.visitJumpInsn(Opcodes.IFNE, skipCall);
                mv.visitVarInsn(Opcodes.ALOAD, entityLocal);
                mv.visitVarInsn(Opcodes.ALOAD, packLocal);
                mv.visitVarInsn(Opcodes.ILOAD, flag1Local);
                mv.visitVarInsn(Opcodes.ILOAD, flag2Local);
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                mv.visitLabel(skipCall);
                awaitingArtMeshSetVolatile = true;
                return;
            }
            if (awaitingArtMeshSetVolatile
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && WarpDeformEditTarget.SCENE_GRAPH_SET_VOLATILE_METHOD.equals(name)
                    && WarpDeformEditTarget.SCENE_GRAPH_SET_VOLATILE_DESCRIPTOR.equals(desc)) {
                awaitingArtMeshSetVolatile = false;
                final Label callSetVolatile = new Label();
                // Spill [sceneGraph, entity] before the try/catch predicate so operand stack stays empty across try.
                mv.visitVarInsn(Opcodes.ASTORE, setVolEntityTmpLocal);
                mv.visitVarInsn(Opcodes.ASTORE, setVolSceneTmpLocal);
                mv.visitVarInsn(Opcodes.ILOAD, flag1Local);
                mv.visitJumpInsn(Opcodes.IFNE, callSetVolatile);
                mv.visitVarInsn(Opcodes.ILOAD, flag2Local);
                mv.visitJumpInsn(Opcodes.IFNE, callSetVolatile);
                mv.visitVarInsn(Opcodes.ALOAD, setVolElideMhLocal);
                mv.visitJumpInsn(Opcodes.IFNULL, callSetVolatile);
                emitElidePredicateCheck(
                        mv, setVolElideMhLocal, entityLocal, packLocal, flag1Local, flag2Local, skipLocal);
                mv.visitVarInsn(Opcodes.ILOAD, skipLocal);
                mv.visitJumpInsn(Opcodes.IFNE, postArtMeshSetVolatileLabel);
                mv.visitLabel(callSetVolatile);
                mv.visitVarInsn(Opcodes.ALOAD, setVolSceneTmpLocal);
                mv.visitVarInsn(Opcodes.ALOAD, setVolEntityTmpLocal);
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                mv.visitLabel(postArtMeshSetVolatileLabel);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        private static void emitConsultOptionalHandle(
                final MethodVisitor mv, final String propertyKey, final int dstLocal) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, dstLocal);
            final Label initStart = new Label();
            final Label initEnd = new Label();
            final Label initNotHandle = new Label();
            final Label initHandler = new Label();
            final Label initDone = new Label();
            mv.visitTryCatchBlock(initStart, initEnd, initHandler, THROWABLE_OWNER);
            mv.visitLabel(initStart);
            SlotBridgeEmitter.emitMethodHandleConsult(mv, propertyKey, initNotHandle);
            mv.visitVarInsn(Opcodes.ASTORE, dstLocal);
            mv.visitJumpInsn(Opcodes.GOTO, initDone);
            mv.visitLabel(initNotHandle);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(initEnd);
            mv.visitJumpInsn(Opcodes.GOTO, initDone);
            mv.visitLabel(initHandler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(initDone);
        }

        private static void emitElidePredicateCheck(
                final MethodVisitor mv,
                final int mhLocal,
                final int targetLocal,
                final int actionPackLocal,
                final int selLocal,
                final int glueLocal,
                final int outBoolLocal) {
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label handler = new Label();
            final Label afterTry = new Label();
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, outBoolLocal);
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
            mv.visitLabel(tryStart);
            mv.visitVarInsn(Opcodes.ALOAD, mhLocal);
            mv.visitVarInsn(Opcodes.ALOAD, targetLocal);
            mv.visitVarInsn(Opcodes.ALOAD, actionPackLocal);
            mv.visitVarInsn(Opcodes.ILOAD, selLocal);
            mv.visitVarInsn(Opcodes.ILOAD, glueLocal);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    METHOD_HANDLE_OWNER,
                    INVOKE_EXACT_METHOD,
                    MH_SELECTION_MESH_ELIDE_DESC,
                    false);
            mv.visitVarInsn(Opcodes.ISTORE, outBoolLocal);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, afterTry);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(afterTry);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            super.visitMaxs(maxStack + 8, Math.max(maxLocals, setVolEntityTmpLocal + 1));
        }
    }

    private static void emitActiveGuard(final MethodVisitor mv, final Label skipLabel) {
        final String atomicBooleanOwner = "java/util/concurrent/atomic/AtomicBoolean";
        final Label castOk = new Label();
        SlotBridgeEmitter.emitGetSlot(mv, WarpDeformEditBridge.ACTIVE_PROPERTY);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, atomicBooleanOwner);
        mv.visitJumpInsn(Opcodes.IFNE, castOk);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, skipLabel);
        mv.visitLabel(castOk);
        mv.visitTypeInsn(Opcodes.CHECKCAST, atomicBooleanOwner);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, atomicBooleanOwner, "get", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, skipLabel);
    }

    /**
     * Increments an {@code AtomicLong} epoch slot on method entry, optionally filtered to ArtMesh keyform instances.
     */
    static final class EpochIncrementEntryAdapter extends MethodVisitor {

        private static final String ATOMIC_LONG_OWNER = "java/util/concurrent/atomic/AtomicLong";
        private static final String INCREMENT_METHOD = "incrementAndGet";
        private static final String INCREMENT_DESC = "()J";

        private final String epochSlotProperty;
        private final boolean artMeshKeyformOnly;

        EpochIncrementEntryAdapter(
                final MethodVisitor delegate, final String epochSlotProperty, final boolean artMeshKeyformOnly) {
            super(Opcodes.ASM9, delegate);
            this.epochSlotProperty = epochSlotProperty;
            this.artMeshKeyformOnly = artMeshKeyformOnly;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label done = new Label();
            final Label popSkip = new Label();
            if (artMeshKeyformOnly) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, WarpDeformEditTarget.CART_MESH_FORM_OWNER);
                mv.visitJumpInsn(Opcodes.IFEQ, done);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        WarpDeformEditTarget.ACFORM_OWNER,
                        WarpDeformEditTarget.ACFORM_INSTANCE_FIELD,
                        WarpDeformEditTarget.ACFORM_INSTANCE_DESCRIPTOR);
                mv.visitJumpInsn(Opcodes.IFNONNULL, done);
            }
            emitActiveGuard(mv, done);
            emitAtomicSlotIncrement(mv, epochSlotProperty);
            mv.visitLabel(done);
        }
    }

    private static final String ATOMIC_LONG_OWNER = "java/util/concurrent/atomic/AtomicLong";
    private static final String ATOMIC_INCREMENT_METHOD = "incrementAndGet";
    private static final String ATOMIC_INCREMENT_DESC = "()J";

    /**
     * Emits an optional {@code AtomicLong.incrementAndGet()} call on a property-backed slot.
     */
    private static void emitAtomicSlotIncrement(final MethodVisitor mv, final String slotProperty) {
        final Label popSkip = new Label();
        final Label next = new Label();
        SlotBridgeEmitter.emitGetSlot(mv, slotProperty);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, ATOMIC_LONG_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, popSkip);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ATOMIC_LONG_OWNER);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, ATOMIC_LONG_OWNER, ATOMIC_INCREMENT_METHOD, ATOMIC_INCREMENT_DESC, false);
        mv.visitInsn(Opcodes.POP2);
        mv.visitJumpInsn(Opcodes.GOTO, next);
        mv.visitLabel(popSkip);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(next);
    }

    /**
     * Routes {@code ACForm} mutations to ArtMesh keyform epoch, deformer source registry, or local mesh epoch.
     */
    static final class AcFormMutationAdapter extends MethodVisitor {

        private static final String THREAD_LOCAL_OWNER = "java/lang/ThreadLocal";
        private static final String THREAD_LOCAL_GET_METHOD = "get";
        private static final String THREAD_LOCAL_GET_DESC = "()Ljava/lang/Object;";
        private static final String DEFORMER_MUTATION_MH_DESC = "(Ljava/lang/Object;)V";

        private final boolean onReturn;

        AcFormMutationAdapter(final MethodVisitor delegate, final boolean onReturn) {
            super(Opcodes.ASM9, delegate);
            this.onReturn = onReturn;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (!onReturn) {
                emitHook();
            }
        }

        @Override
        public void visitInsn(final int opcode) {
            if (onReturn && opcode == Opcodes.RETURN) {
                emitHook();
            }
            super.visitInsn(opcode);
        }

        private void emitHook() {
            final Label done = new Label();
            final Label runtimeInstance = new Label();
            final Label deformerKeyform = new Label();
            final Label popSkip = new Label();

            // Fast-skip runtime deformer instances (_instance != null && !(this instanceof CArtMeshForm)).
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(
                    Opcodes.GETFIELD,
                    WarpDeformEditTarget.ACFORM_OWNER,
                    WarpDeformEditTarget.ACFORM_INSTANCE_FIELD,
                    WarpDeformEditTarget.ACFORM_INSTANCE_DESCRIPTOR);
            mv.visitJumpInsn(Opcodes.IFNONNULL, runtimeInstance);

            emitActiveGuard(mv, done);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, WarpDeformEditTarget.CART_MESH_FORM_OWNER);
            mv.visitJumpInsn(Opcodes.IFEQ, deformerKeyform);
            emitAtomicSlotIncrement(mv, WarpDeformEditBridge.MESH_KEYFORM_EPOCH_PROPERTY);
            mv.visitJumpInsn(Opcodes.GOTO, done);

            mv.visitLabel(deformerKeyform);
            SlotBridgeEmitter.emitMethodHandleConsult(mv, WarpDeformEditBridge.DEFORMER_MUTATION_PROPERTY, popSkip);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    WarpDeformEditTarget.ACFORM_OWNER,
                    WarpDeformEditTarget.ACFORM_GET_SOURCE_INTERNAL_METHOD,
                    WarpDeformEditTarget.ACFORM_GET_SOURCE_INTERNAL_DESCRIPTOR,
                    false);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, DEFORMER_MUTATION_MH_DESC, false);
            mv.visitJumpInsn(Opcodes.GOTO, done);

            mv.visitLabel(runtimeInstance);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, WarpDeformEditTarget.CART_MESH_FORM_OWNER);
            mv.visitJumpInsn(Opcodes.IFEQ, done);
            emitActiveGuard(mv, done);
            emitNotFastMeshTransformGuard(mv, done);
            emitAtomicSlotIncrement(mv, WarpDeformEditBridge.LOCAL_MESH_EPOCH_PROPERTY);
            mv.visitJumpInsn(Opcodes.GOTO, done);

            mv.visitLabel(popSkip);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(done);
        }

        static void emitNotFastMeshTransformGuard(final MethodVisitor mv, final Label skipLabel) {
            final Label popProceed = new Label();
            final Label proceed = new Label();
            SlotBridgeEmitter.emitGetSlot(mv, WarpDeformEditBridge.FAST_MESH_TRANSFORM_THREAD_PROPERTY);
            mv.visitInsn(Opcodes.DUP);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, THREAD_LOCAL_OWNER);
            mv.visitJumpInsn(Opcodes.IFEQ, popProceed);
            mv.visitTypeInsn(Opcodes.CHECKCAST, THREAD_LOCAL_OWNER);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, THREAD_LOCAL_OWNER, THREAD_LOCAL_GET_METHOD, THREAD_LOCAL_GET_DESC, false);
            mv.visitJumpInsn(Opcodes.IFNONNULL, skipLabel);
            mv.visitJumpInsn(Opcodes.GOTO, proceed);
            mv.visitLabel(popProceed);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(proceed);
        }
    }

    /**
     * Increments {@code localMeshEpoch} (and optionally {@code meshKeyformEpoch}) on method entry,
     * optionally skipping fast parallel mesh transforms.
     */
    static final class LocalMeshEpochEntryAdapter extends MethodVisitor {

        private final boolean guardFastMeshTransform;
        private final boolean alsoMeshKeyformEpoch;
        private final boolean alsoDirectMeshEpoch;

        LocalMeshEpochEntryAdapter(
                final MethodVisitor delegate,
                final boolean guardFastMeshTransform,
                final boolean alsoMeshKeyformEpoch,
                final boolean alsoDirectMeshEpoch) {
            super(Opcodes.ASM9, delegate);
            this.guardFastMeshTransform = guardFastMeshTransform;
            this.alsoMeshKeyformEpoch = alsoMeshKeyformEpoch;
            this.alsoDirectMeshEpoch = alsoDirectMeshEpoch;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label done = new Label();
            emitActiveGuard(mv, done);
            if (guardFastMeshTransform) {
                AcFormMutationAdapter.emitNotFastMeshTransformGuard(mv, done);
            }
            emitAtomicSlotIncrement(mv, WarpDeformEditBridge.LOCAL_MESH_EPOCH_PROPERTY);
            if (alsoMeshKeyformEpoch) {
                emitAtomicSlotIncrement(mv, WarpDeformEditBridge.MESH_KEYFORM_EPOCH_PROPERTY);
            }
            if (alsoDirectMeshEpoch) {
                emitAtomicSlotIncrement(mv, WarpDeformEditBridge.DIRECT_LOCAL_MESH_EPOCH_PROPERTY);
            }
            mv.visitLabel(done);
        }
    }

    /**
     * Records a mutated deformer source instance on entry into {@code ACDeformerSource}/{@code CWarpDeformerSource}.
     */
    static final class DeformerSourceMutationAdapter extends MethodVisitor {

        private static final String DEFORMER_MUTATION_MH_DESC = "(Ljava/lang/Object;)V";

        DeformerSourceMutationAdapter(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label done = new Label();
            final Label popSkip = new Label();
            emitActiveGuard(mv, done);
            SlotBridgeEmitter.emitMethodHandleConsult(mv, WarpDeformEditBridge.DEFORMER_MUTATION_PROPERTY, popSkip);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, DEFORMER_MUTATION_MH_DESC, false);
            mv.visitJumpInsn(Opcodes.GOTO, done);
            mv.visitLabel(popSkip);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(done);
        }
    }

    /**
     * Entry fast path for {@code KeyformBindingSource.getKeyIndicesInRange()}.
     */
    static final class KeyIndicesInRangeAdapter extends MethodVisitor {

        private static final String MH_DESC = "(Ljava/lang/Object;)Ljava/util/List;";

        KeyIndicesInRangeAdapter(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label original = new Label();
            final Label popOriginal = new Label();
            emitActiveGuard(mv, original);
            SlotBridgeEmitter.emitMethodHandleConsult(
                    mv, WarpDeformEditBridge.KEYFORM_INDICES_IN_RANGE_PROPERTY, popOriginal);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, MH_DESC, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFNULL, popOriginal);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(popOriginal);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(original);
        }
    }

    /**
     * Entry fast path for {@code KeyformGridSource.findKeyform(...)}.
     */
    static final class FindKeyformAdapter extends MethodVisitor {

        private static final String MH_DESC = "(Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;";
        private static final String ORDINAL_METHOD = "ordinal";
        private static final String ORDINAL_DESC = "()I";

        private final boolean hasSearchType;

        FindKeyformAdapter(final MethodVisitor delegate, final boolean hasSearchType) {
            super(Opcodes.ASM9, delegate);
            this.hasSearchType = hasSearchType;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label original = new Label();
            final Label popOriginal = new Label();
            emitActiveGuard(mv, original);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitJumpInsn(Opcodes.IFNULL, original);
            if (hasSearchType) {
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitJumpInsn(Opcodes.IFNULL, original);
            }
            SlotBridgeEmitter.emitMethodHandleConsult(mv, WarpDeformEditBridge.KEYFORM_FIND_PROPERTY, popOriginal);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            if (hasSearchType) {
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        WarpDeformEditTarget.PARAMETER_KEY_SEARCH_TYPE_OWNER,
                        ORDINAL_METHOD,
                        ORDINAL_DESC,
                        false);
            } else {
                mv.visitInsn(Opcodes.ICONST_M1);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, MH_DESC, false);
            mv.visitInsn(Opcodes.DUP);
            SlotBridgeEmitter.emitGetSlot(mv, WarpDeformEditBridge.KEYFORM_UNHANDLED_PROPERTY);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, popOriginal);
            mv.visitTypeInsn(Opcodes.CHECKCAST, WarpDeformEditTarget.KEYFORM_ON_GRID_OWNER);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(popOriginal);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(original);
        }
    }
}
