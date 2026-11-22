package dev.turboism.adapter.cubism.textureatlas.interactive;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import dev.turboism.adapter.cubism.textureatlas.interactive.AtlasInteractiveRenderTarget.Generation;
import dev.turboism.core.runtime.work.FatalErrors;
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
 * Verifies exact reviewed method shapes and rewrites the six Texture Atlas Editor classes cleanly
 * for interactive overlay caching, viewport clipping, drag UI coalescing, zero-delta cursor warp
 * elision, and hidden linked-tab camera deferral.
 */
public final class AtlasInteractiveRenderTransformer implements ClassFileTransformer {

    private static final String OBJECT_OWNER = "java/lang/Object";
    private static final String BOOLEAN_OWNER = "java/lang/Boolean";
    private static final String INTEGER_OWNER = "java/lang/Integer";
    private static final String DOUBLE_OWNER = "java/lang/Double";
    private static final String BIPREDICATE_OWNER = "java/util/function/BiPredicate";
    private static final String UT_THREAD_OWNER = "jp/noids/thread/UtThread";
    private static final String GRAPHICS_OWNER = "java/awt/Graphics";
    private static final String LAYER_CORE_A_OWNER = "jp/noids/design/layer/core/a";
    private static final String NOIDS_GRAPHICS_A_OWNER = "jp/noids/graphics/a";
    private static final String POINT_OWNER = "java/awt/Point";
    private static final String KOTLIN_INTRINSICS_OWNER = "kotlin/jvm/internal/Intrinsics";
    private static final String AWT_EXCEPTION_OWNER = "java/awt/AWTException";
    private static final String BASIC_STROKE_OWNER = "java/awt/BasicStroke";
    private static final String ITERATOR_OWNER = "java/util/Iterator";
    private static final String ARRAY_LIST_OWNER = "java/util/ArrayList";
    private static final String VIEWPORT_CANVAS_OWNER = AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER;
    private static final String JTEXTFIELD_OWNER = "javax/swing/JTextField";
    private static final String LAYER_REF_OWNER = AtlasInteractiveRenderTarget.LAYER_REF_OWNER;
    private static final String ROBOT_OWNER = AtlasInteractiveRenderTarget.ROBOT_OWNER;

    private static final String BIPREDICATE_TEST_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final int FINALLY_THROWABLE_LOCAL_SLOT = 3;

    private static final int EXPECTED_EDIT_LAYER_SITES = 1;
    private static final int EXPECTED_CANVAS_SITES = 2;
    private static final int EXPECTED_UNIT_PANEL_SITES = 1;
    private static final int EXPECTED_CAMERA_SITES = 2;
    private static final int EXPECTED_SCROLL_DRAG_SITES = 2;
    private static final int EXPECTED_INSPECTOR_SITES = 6;

    private final ClassLoader loader;
    private final Path artifact;
    private final AtlasInteractiveRenderTarget target;
    private final Generation generation;

    private final List<String> drawExeShape;
    private final List<String> canvasPaintShape;
    private final List<String> canvasRenderShape;
    private final List<String> unitLayerUpdatedShape;
    private final List<String> wheelZoomShape;
    private final List<String> mouseDraggedShape;
    private final List<String> inspectorSyncShape;

    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures reference shapes across all six reviewed atlas-editor host classes.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param target reviewed generation target descriptors
     * @param editLayerReference bytes of {@code TAE__EditLayer_ModelImage}
     * @param canvasReference bytes of the atlas canvas class
     * @param unitPanelReference bytes of the unit panel class
     * @param cameraReference bytes of the camera panel class
     * @param scrollDragReference bytes of the scroll-pan mouse listener class
     * @param inspectorReference bytes of the inspector panel class
     */
    public AtlasInteractiveRenderTransformer(
            final ClassLoader loader,
            final Path artifact,
            final AtlasInteractiveRenderTarget target,
            final byte[] editLayerReference,
            final byte[] canvasReference,
            final byte[] unitPanelReference,
            final byte[] cameraReference,
            final byte[] scrollDragReference,
            final byte[] inspectorReference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.target = Objects.requireNonNull(target, "target");
        this.generation = target.generation();

        this.drawExeShape = requireShape(
                editLayerReference,
                AtlasInteractiveRenderTarget.EDIT_LAYER_OWNER,
                AtlasInteractiveRenderTarget.DRAW_EXE_METHOD,
                AtlasInteractiveRenderTarget.DRAW_EXE_DESCRIPTOR);
        this.canvasPaintShape = requireShape(
                canvasReference,
                generation.canvasOwner(),
                AtlasInteractiveRenderTarget.CANVAS_PAINT_METHOD,
                AtlasInteractiveRenderTarget.CANVAS_PAINT_DESCRIPTOR);
        this.canvasRenderShape = requireShape(
                canvasReference,
                generation.canvasOwner(),
                AtlasInteractiveRenderTarget.CANVAS_RENDER_METHOD,
                AtlasInteractiveRenderTarget.CANVAS_RENDER_DESCRIPTOR);
        this.unitLayerUpdatedShape = requireShape(
                unitPanelReference,
                generation.unitPanelOwner(),
                AtlasInteractiveRenderTarget.UNIT_LAYER_UPDATED_METHOD,
                AtlasInteractiveRenderTarget.UNIT_LAYER_UPDATED_DESCRIPTOR);
        this.wheelZoomShape = requireShape(
                cameraReference,
                generation.cameraOwner(),
                generation.wheelZoomMethod(),
                generation.wheelZoomDescriptor());
        this.mouseDraggedShape = requireShape(
                scrollDragReference,
                generation.scrollDragOwner(),
                AtlasInteractiveRenderTarget.MOUSE_DRAGGED_METHOD,
                AtlasInteractiveRenderTarget.MOUSE_DRAGGED_DESCRIPTOR);
        this.inspectorSyncShape = requireShape(
                inspectorReference,
                AtlasInteractiveRenderTarget.INSPECTOR_OWNER,
                generation.inspectorSyncMethod(),
                AtlasInteractiveRenderTarget.VOID_METHOD_DESCRIPTOR);
    }

    /** Returns the latest fail-closed rejection reason, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns the number of rewritten host classes. */
    public int matches() {
        return matches;
    }

    /** Returns the total number of instrumented bytecode sites across all rewritten classes. */
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
        if (bytes == null || name == null || !isTargetOwner(name)) {
            return null;
        }
        if (actualLoader != loader || !HostCodeSource.matchesArtifact(domain, artifact)) {
            failure = "unexpected loader or code source for " + name;
            return null;
        }
        if (!matchesExpectedShapes(name, bytes)) {
            failure = "method shape mismatch for " + name;
            return null;
        }

        try {
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
            final int[] localSites = {0};
            reader.accept(createClassVisitor(name, writer, localSites), ClassReader.EXPAND_FRAMES);

            final int expected = expectedSitesFor(name);
            if (localSites[0] != expected) {
                failure = name + " site count mismatch: expected " + expected + ", got " + localSites[0];
                return null;
            }
            final byte[] output = writer.toByteArray();
            matches++;
            sites += localSites[0];
            return output;
        } catch (final Throwable rejected) {
            FatalErrors.rethrowIfFatal(rejected);
            failure = rejected.toString();
            return null;
        }
    }

    private boolean isTargetOwner(final String name) {
        return AtlasInteractiveRenderTarget.EDIT_LAYER_OWNER.equals(name)
                || generation.canvasOwner().equals(name)
                || generation.unitPanelOwner().equals(name)
                || generation.cameraOwner().equals(name)
                || generation.scrollDragOwner().equals(name)
                || AtlasInteractiveRenderTarget.INSPECTOR_OWNER.equals(name);
    }

    private boolean matchesExpectedShapes(final String name, final byte[] bytes) {
        if (AtlasInteractiveRenderTarget.EDIT_LAYER_OWNER.equals(name)) {
            return drawExeShape.equals(ReviewedMethodShape.read(
                    bytes,
                    name,
                    AtlasInteractiveRenderTarget.DRAW_EXE_METHOD,
                    AtlasInteractiveRenderTarget.DRAW_EXE_DESCRIPTOR));
        }
        if (generation.canvasOwner().equals(name)) {
            return canvasPaintShape.equals(ReviewedMethodShape.read(
                            bytes,
                            name,
                            AtlasInteractiveRenderTarget.CANVAS_PAINT_METHOD,
                            AtlasInteractiveRenderTarget.CANVAS_PAINT_DESCRIPTOR))
                    && canvasRenderShape.equals(ReviewedMethodShape.read(
                            bytes,
                            name,
                            AtlasInteractiveRenderTarget.CANVAS_RENDER_METHOD,
                            AtlasInteractiveRenderTarget.CANVAS_RENDER_DESCRIPTOR));
        }
        if (generation.unitPanelOwner().equals(name)) {
            return unitLayerUpdatedShape.equals(ReviewedMethodShape.read(
                    bytes,
                    name,
                    AtlasInteractiveRenderTarget.UNIT_LAYER_UPDATED_METHOD,
                    AtlasInteractiveRenderTarget.UNIT_LAYER_UPDATED_DESCRIPTOR));
        }
        if (generation.cameraOwner().equals(name)) {
            return wheelZoomShape.equals(ReviewedMethodShape.read(
                    bytes, name, generation.wheelZoomMethod(), generation.wheelZoomDescriptor()));
        }
        if (generation.scrollDragOwner().equals(name)) {
            return mouseDraggedShape.equals(ReviewedMethodShape.read(
                    bytes,
                    name,
                    AtlasInteractiveRenderTarget.MOUSE_DRAGGED_METHOD,
                    AtlasInteractiveRenderTarget.MOUSE_DRAGGED_DESCRIPTOR));
        }
        if (AtlasInteractiveRenderTarget.INSPECTOR_OWNER.equals(name)) {
            return inspectorSyncShape.equals(ReviewedMethodShape.read(
                    bytes,
                    name,
                    generation.inspectorSyncMethod(),
                    AtlasInteractiveRenderTarget.VOID_METHOD_DESCRIPTOR));
        }
        return false;
    }

    private int expectedSitesFor(final String name) {
        if (AtlasInteractiveRenderTarget.EDIT_LAYER_OWNER.equals(name)) {
            return EXPECTED_EDIT_LAYER_SITES;
        }
        if (generation.canvasOwner().equals(name)) {
            return EXPECTED_CANVAS_SITES;
        }
        if (generation.unitPanelOwner().equals(name)) {
            return EXPECTED_UNIT_PANEL_SITES;
        }
        if (generation.cameraOwner().equals(name)) {
            return EXPECTED_CAMERA_SITES;
        }
        if (generation.scrollDragOwner().equals(name)) {
            return EXPECTED_SCROLL_DRAG_SITES;
        }
        return EXPECTED_INSPECTOR_SITES;
    }

    private ClassVisitor createClassVisitor(
            final String ownerName, final ClassVisitor delegate, final int[] localSites) {
        return new ClassVisitor(Opcodes.ASM9, delegate) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String methodName,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, methodName, descriptor, signature, exceptions);
                if (AtlasInteractiveRenderTarget.EDIT_LAYER_OWNER.equals(ownerName)
                        && AtlasInteractiveRenderTarget.DRAW_EXE_METHOD.equals(methodName)
                        && AtlasInteractiveRenderTarget.DRAW_EXE_DESCRIPTOR.equals(descriptor)) {
                    return wrapDrawExe(mv, localSites);
                }
                if (generation.canvasOwner().equals(ownerName)
                        && AtlasInteractiveRenderTarget.CANVAS_PAINT_METHOD.equals(methodName)
                        && AtlasInteractiveRenderTarget.CANVAS_PAINT_DESCRIPTOR.equals(descriptor)) {
                    return wrapCanvasPaint(mv, localSites);
                }
                if (generation.canvasOwner().equals(ownerName)
                        && AtlasInteractiveRenderTarget.CANVAS_RENDER_METHOD.equals(methodName)
                        && AtlasInteractiveRenderTarget.CANVAS_RENDER_DESCRIPTOR.equals(descriptor)) {
                    return wrapCanvasRender(mv, localSites);
                }
                if (generation.unitPanelOwner().equals(ownerName)
                        && AtlasInteractiveRenderTarget.UNIT_LAYER_UPDATED_METHOD.equals(methodName)
                        && AtlasInteractiveRenderTarget.UNIT_LAYER_UPDATED_DESCRIPTOR.equals(descriptor)) {
                    return wrapUnitLayerUpdated(mv, localSites);
                }
                if (generation.cameraOwner().equals(ownerName)
                        && generation.wheelZoomMethod().equals(methodName)
                        && generation.wheelZoomDescriptor().equals(descriptor)) {
                    return wrapWheelZoom(mv, localSites);
                }
                if (generation.scrollDragOwner().equals(ownerName)
                        && AtlasInteractiveRenderTarget.MOUSE_DRAGGED_METHOD.equals(methodName)
                        && AtlasInteractiveRenderTarget.MOUSE_DRAGGED_DESCRIPTOR.equals(descriptor)) {
                    return wrapScrollDrag(mv, localSites);
                }
                if (AtlasInteractiveRenderTarget.INSPECTOR_OWNER.equals(ownerName)
                        && generation.inspectorSyncMethod().equals(methodName)
                        && AtlasInteractiveRenderTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return wrapInspectorSync(mv, localSites);
                }
                return mv;
            }
        };
    }

    private MethodVisitor wrapDrawExe(final MethodVisitor delegate, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private int gSlot = -1;
            private int txSlot = -1;
            private int iconSlot = -1;

            private boolean awaitGraphicsStore;
            private boolean sawBasicStrokeInit;
            private boolean awaitTxStore;
            private boolean awaitIconBoolValue;
            private boolean awaitIconStore;
            private boolean awaitShapeSizeIfne;
            private Label drawShapesLabel;
            private boolean inDrawShapesSection;
            private boolean afterHasNext;
            private int loopEndBranchesSeen;
            private Label pendingHostDoneLabel;
            private Label skipShapesTargetLabel;

            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean itf) {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                if (LAYER_CORE_A_OWNER.equals(owner) && "k".equals(name) && gSlot < 0) {
                    awaitGraphicsStore = true;
                } else if (BASIC_STROKE_OWNER.equals(owner) && "<init>".equals(name)) {
                    sawBasicStrokeInit = true;
                } else if (sawBasicStrokeInit
                        && NOIDS_GRAPHICS_A_OWNER.equals(owner)
                        && "getTransform".equals(name)
                        && txSlot < 0) {
                    awaitTxStore = true;
                } else if (LAYER_CORE_A_OWNER.equals(owner)
                        && "b".equals(name)
                        && "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".equals(descriptor)) {
                    awaitIconBoolValue = true;
                } else if (awaitIconBoolValue
                        && BOOLEAN_OWNER.equals(owner)
                        && "booleanValue".equals(name)) {
                    awaitIconBoolValue = false;
                    awaitIconStore = true;
                } else if (ARRAY_LIST_OWNER.equals(owner) && "size".equals(name) && drawShapesLabel == null) {
                    awaitShapeSizeIfne = true;
                } else if (inDrawShapesSection && ITERATOR_OWNER.equals(owner) && "hasNext".equals(name)) {
                    afterHasNext = true;
                }
            }

            @Override
            public void visitVarInsn(final int opcode, final int varIndex) {
                super.visitVarInsn(opcode, varIndex);
                if (awaitGraphicsStore && opcode == Opcodes.ASTORE) {
                    awaitGraphicsStore = false;
                    gSlot = varIndex;
                } else if (awaitTxStore && opcode == Opcodes.ASTORE) {
                    awaitTxStore = false;
                    txSlot = varIndex;
                } else if (awaitIconStore && opcode == Opcodes.ISTORE) {
                    awaitIconStore = false;
                    iconSlot = varIndex;
                }
            }

            @Override
            public void visitJumpInsn(final int opcode, final Label label) {
                super.visitJumpInsn(opcode, label);
                if (awaitShapeSizeIfne && opcode == Opcodes.IFNE) {
                    awaitShapeSizeIfne = false;
                    drawShapesLabel = label;
                } else if (afterHasNext && opcode == Opcodes.IFEQ) {
                    afterHasNext = false;
                    loopEndBranchesSeen++;
                    if (loopEndBranchesSeen == 2) {
                        pendingHostDoneLabel = label;
                    }
                }
            }

            @Override
            public void visitLabel(final Label label) {
                super.visitLabel(label);
                if (label == drawShapesLabel
                        && !inDrawShapesSection
                        && gSlot >= 0
                        && txSlot >= 0
                        && iconSlot >= 0) {
                    inDrawShapesSection = true;
                    final Label fallbackRaw = new Label();
                    final Label runOriginal = new Label();
                    skipShapesTargetLabel = new Label();

                    SlotBridgeEmitter.emitPredicateConsult(
                            mv, AtlasInteractiveRenderBridge.OVERLAY_RENDER_PROPERTY, fallbackRaw);
                    mv.visitInsn(Opcodes.ICONST_4);
                    mv.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT_OWNER);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitInsn(Opcodes.AASTORE);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitVarInsn(Opcodes.ALOAD, gSlot);
                    mv.visitInsn(Opcodes.AASTORE);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitInsn(Opcodes.ICONST_2);
                    mv.visitVarInsn(Opcodes.ALOAD, txSlot);
                    mv.visitInsn(Opcodes.AASTORE);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitInsn(Opcodes.ICONST_3);
                    mv.visitVarInsn(Opcodes.ILOAD, iconSlot);
                    mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC, BOOLEAN_OWNER, "valueOf", "(Z)Ljava/lang/Boolean;", false);
                    mv.visitInsn(Opcodes.AASTORE);
                    SlotBridgeEmitter.emitTest(mv);
                    mv.visitJumpInsn(Opcodes.IFNE, skipShapesTargetLabel);
                    mv.visitJumpInsn(Opcodes.GOTO, runOriginal);
                    mv.visitLabel(fallbackRaw);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitLabel(runOriginal);
                } else if (skipShapesTargetLabel != null && label == pendingHostDoneLabel) {
                    mv.visitLabel(skipShapesTargetLabel);
                    skipShapesTargetLabel = null;
                    localSites[0]++;
                }
            }
        };
    }

    private MethodVisitor wrapCanvasPaint(final MethodVisitor delegate, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private boolean sawTrailingTimerKey;

            @Override
            public void visitLdcInsn(final Object value) {
                if (AtlasInteractiveRenderTarget.TRAILING_REPAINT_TIMER_KEY.equals(value)) {
                    sawTrailingTimerKey = true;
                }
                super.visitLdcInsn(value);
            }

            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean itf) {
                if (sawTrailingTimerKey
                        && opcode == Opcodes.INVOKESTATIC
                        && UT_THREAD_OWNER.equals(owner)
                        && "invokeLaterOnce".equals(name)
                        && "(JLjava/lang/Object;Ljava/lang/Runnable;)V".equals(descriptor)) {
                    sawTrailingTimerKey = false;
                    final Label fallbackRaw = new Label();
                    final Label runOriginal = new Label();
                    final Label after = new Label();

                    SlotBridgeEmitter.emitPredicateConsult(
                            mv, AtlasInteractiveRenderBridge.ELIDE_TRAILING_REPAINT_PROPERTY, fallbackRaw);
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    SlotBridgeEmitter.emitTest(mv);
                    mv.visitJumpInsn(Opcodes.IFEQ, runOriginal);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.POP2);
                    mv.visitJumpInsn(Opcodes.GOTO, after);

                    mv.visitLabel(fallbackRaw);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitLabel(runOriginal);
                    super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    mv.visitLabel(after);
                    localSites[0]++;
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }
        };
    }

    private MethodVisitor wrapCanvasRender(final MethodVisitor delegate, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean itf) {
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && GRAPHICS_OWNER.equals(owner)
                        && "drawImage".equals(name)
                        && "(Ljava/awt/Image;IILjava/awt/image/ImageObserver;)Z".equals(descriptor)) {
                    final Label fallbackRaw = new Label();
                    final Label runOriginal = new Label();
                    final Label after = new Label();

                    // Reviewed shape pins args as (Graphics, Image, 0, 0, null).
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.DUP2);
                    emitTypedConsult(mv, AtlasInteractiveRenderBridge.CLIP_BG_PROPERTY, BIPREDICATE_OWNER, fallbackRaw);
                    mv.visitInsn(Opcodes.DUP_X2);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitMethodInsn(
                            Opcodes.INVOKEINTERFACE, BIPREDICATE_OWNER, "test", BIPREDICATE_TEST_DESC, true);
                    mv.visitJumpInsn(Opcodes.IFEQ, runOriginal);
                    mv.visitInsn(Opcodes.POP2);
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitJumpInsn(Opcodes.GOTO, after);

                    mv.visitLabel(fallbackRaw);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.POP2);
                    mv.visitLabel(runOriginal);
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    mv.visitLabel(after);
                    localSites[0]++;
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }
        };
    }

    private MethodVisitor wrapUnitLayerUpdated(final MethodVisitor delegate, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private final Label ourTryStart = new Label();
            private final Label ourTryEnd = new Label();
            private final Label ourFinallyHandler = new Label();
            private boolean enterEmitted;
            private boolean skippedOrHandledTable;
            private boolean exitEmitted;

            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean itf) {
                if (opcode == Opcodes.INVOKESTATIC
                        && KOTLIN_INTRINSICS_OWNER.equals(owner)
                        && "checkNotNullParameter".equals(name)) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    emitSlotTestIgnoreResult(
                            mv, AtlasInteractiveRenderBridge.ENTER_DRAG_SYNC_PROPERTY, 1);
                    // Register after host Exception handler so Exception catch still runs first.
                    mv.visitTryCatchBlock(ourTryStart, ourTryEnd, ourFinallyHandler, null);
                    mv.visitLabel(ourTryStart);
                    enterEmitted = true;
                    return;
                }

                final String tableMethod = generation.inspectorTableSyncMethod();
                if (tableMethod != null
                        && opcode == Opcodes.INVOKEVIRTUAL
                        && AtlasInteractiveRenderTarget.INSPECTOR_OWNER.equals(owner)
                        && tableMethod.equals(name)
                        && AtlasInteractiveRenderTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    final Label fallbackRaw = new Label();
                    final Label runOriginal = new Label();
                    final Label after = new Label();

                    mv.visitInsn(Opcodes.DUP);
                    SlotBridgeEmitter.emitPredicateConsult(
                            mv, AtlasInteractiveRenderBridge.SKIP_DRAG_TABLE_PROPERTY, fallbackRaw);
                    mv.visitInsn(Opcodes.SWAP);
                    SlotBridgeEmitter.emitTest(mv);
                    mv.visitJumpInsn(Opcodes.IFEQ, runOriginal);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitJumpInsn(Opcodes.GOTO, after);

                    mv.visitLabel(fallbackRaw);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitLabel(runOriginal);
                    super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    mv.visitLabel(after);
                    skippedOrHandledTable = true;
                    return;
                }

                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }

            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.RETURN && enterEmitted && !exitEmitted) {
                    exitEmitted = true;
                    mv.visitLabel(ourTryEnd);
                    emitSlotTestIgnoreResult(mv, AtlasInteractiveRenderBridge.EXIT_DRAG_SYNC_PROPERTY, 0);
                    super.visitInsn(opcode);

                    mv.visitLabel(ourFinallyHandler);
                    mv.visitVarInsn(Opcodes.ASTORE, FINALLY_THROWABLE_LOCAL_SLOT);
                    emitSlotTestIgnoreResult(mv, AtlasInteractiveRenderBridge.EXIT_DRAG_SYNC_PROPERTY, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, FINALLY_THROWABLE_LOCAL_SLOT);
                    mv.visitInsn(Opcodes.ATHROW);

                    final boolean tableReady = generation.inspectorTableSyncMethod() == null || skippedOrHandledTable;
                    if (tableReady) {
                        localSites[0]++;
                    }
                    return;
                }
                super.visitInsn(opcode);
            }
        };
    }

    private MethodVisitor wrapInspectorSync(final MethodVisitor delegate, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean itf) {
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && LAYER_REF_OWNER.equals(owner)
                        && AtlasInteractiveRenderTarget.SET_TRANSFORM_TO_PARENT_METHOD.equals(name)
                        && AtlasInteractiveRenderTarget.SET_TRANSFORM_TO_PARENT_DESCRIPTOR.equals(descriptor)) {
                    emitTwoArgSkip(
                            mv,
                            AtlasInteractiveRenderBridge.SKIP_DRAG_TRANSFORM_PROPERTY,
                            () -> super.visitMethodInsn(opcode, owner, name, descriptor, itf));
                    localSites[0]++;
                    return;
                }
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && JTEXTFIELD_OWNER.equals(owner)
                        && "setText".equals(name)
                        && "(Ljava/lang/String;)V".equals(descriptor)) {
                    final Label fallbackRaw = new Label();
                    final Label runOriginal = new Label();
                    final Label after = new Label();

                    mv.visitInsn(Opcodes.DUP2);
                    emitTypedConsult(mv, AtlasInteractiveRenderBridge.APPLY_TEXT_PROPERTY, BIPREDICATE_OWNER, fallbackRaw);
                    mv.visitInsn(Opcodes.DUP_X2);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitMethodInsn(
                            Opcodes.INVOKEINTERFACE, BIPREDICATE_OWNER, "test", BIPREDICATE_TEST_DESC, true);
                    mv.visitJumpInsn(Opcodes.IFEQ, runOriginal);
                    mv.visitInsn(Opcodes.POP2);
                    mv.visitJumpInsn(Opcodes.GOTO, after);

                    mv.visitLabel(fallbackRaw);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.POP2);
                    mv.visitLabel(runOriginal);
                    super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    mv.visitLabel(after);
                    localSites[0]++;
                    return;
                }
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && AtlasInteractiveRenderTarget.INSPECTOR_OWNER.equals(owner)
                        && generation.inspectorIconTimerMethod().equals(name)
                        && AtlasInteractiveRenderTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    emitOneArgReceiverSkip(
                            mv,
                            AtlasInteractiveRenderBridge.SKIP_DRAG_ICON_PROPERTY,
                            0,
                            () -> super.visitMethodInsn(opcode, owner, name, descriptor, itf));
                    localSites[0]++;
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }
        };
    }

    private MethodVisitor wrapWheelZoom(final MethodVisitor delegate, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private Label awtTryStart;
            private Label skipRobotLabel;
            private boolean inAwtTry;
            private Label linkedLoopContinue;

            private int prevOpcode = -1;
            private boolean awaitPointStore;
            private int pointSlot = -1;
            private int newZoomSlot = -1;
            private int lastDstoreSlot = -1;
            private int ratioSlot = -1;
            private int zeroStoreCount;
            private int dxSlot = -1;
            private int dySlot = -1;
            private boolean awaitLinkedStore;

            @Override
            public void visitTryCatchBlock(
                    final Label start, final Label end, final Label handler, final String type) {
                super.visitTryCatchBlock(start, end, handler, type);
                if (AWT_EXCEPTION_OWNER.equals(type) && awtTryStart == null) {
                    awtTryStart = start;
                }
            }

            @Override
            public void visitLabel(final Label label) {
                super.visitLabel(label);
                if (label == awtTryStart && !inAwtTry && dxSlot >= 0 && dySlot >= 0 && ratioSlot >= 0) {
                    inAwtTry = true;
                    skipRobotLabel = new Label();
                    final Label fallbackRaw = new Label();
                    final Label runOriginal = new Label();

                    SlotBridgeEmitter.emitPredicateConsult(
                            mv, AtlasInteractiveRenderBridge.WHEEL_ZERO_ROBOT_PROPERTY, fallbackRaw);
                    mv.visitInsn(Opcodes.ICONST_4);
                    mv.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT_OWNER);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitInsn(Opcodes.AASTORE);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitVarInsn(Opcodes.ILOAD, dxSlot);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, INTEGER_OWNER, "valueOf", "(I)Ljava/lang/Integer;", false);
                    mv.visitInsn(Opcodes.AASTORE);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitInsn(Opcodes.ICONST_2);
                    mv.visitVarInsn(Opcodes.ILOAD, dySlot);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, INTEGER_OWNER, "valueOf", "(I)Ljava/lang/Integer;", false);
                    mv.visitInsn(Opcodes.AASTORE);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitInsn(Opcodes.ICONST_3);
                    mv.visitVarInsn(Opcodes.DLOAD, ratioSlot);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, DOUBLE_OWNER, "valueOf", "(D)Ljava/lang/Double;", false);
                    mv.visitInsn(Opcodes.AASTORE);
                    SlotBridgeEmitter.emitTest(mv);
                    mv.visitJumpInsn(Opcodes.IFNE, skipRobotLabel);
                    mv.visitJumpInsn(Opcodes.GOTO, runOriginal);
                    mv.visitLabel(fallbackRaw);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitLabel(runOriginal);
                }
            }

            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean itf) {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                prevOpcode = -1;
                if (POINT_OWNER.equals(owner) && "<init>".equals(name) && "()V".equals(descriptor) && pointSlot < 0) {
                    awaitPointStore = true;
                } else if (VIEWPORT_CANVAS_OWNER.equals(owner)
                        && "getVisibleRect".equals(name)
                        && ratioSlot < 0
                        && lastDstoreSlot >= 0) {
                    ratioSlot = lastDstoreSlot;
                } else if (inAwtTry && ROBOT_OWNER.equals(owner) && generation.robotMoveMethod().equals(name)) {
                    mv.visitLabel(skipRobotLabel);
                    inAwtTry = false;
                    localSites[0]++;
                }
            }

            @Override
            public void visitTypeInsn(final int opcode, final String type) {
                super.visitTypeInsn(opcode, type);
                prevOpcode = -1;
                if (opcode == Opcodes.CHECKCAST && generation.cameraOwner().equals(type)) {
                    awaitLinkedStore = true;
                }
            }

            @Override
            public void visitInsn(final int opcode) {
                super.visitInsn(opcode);
                if (opcode == Opcodes.DDIV && ratioSlot < 0 && lastDstoreSlot >= 0) {
                    newZoomSlot = lastDstoreSlot;
                }
                prevOpcode = opcode;
            }

            @Override
            public void visitVarInsn(final int opcode, final int varIndex) {
                super.visitVarInsn(opcode, varIndex);
                if (opcode == Opcodes.ASTORE && awaitPointStore) {
                    awaitPointStore = false;
                    pointSlot = varIndex;
                } else if (opcode == Opcodes.DSTORE) {
                    lastDstoreSlot = varIndex;
                } else if (opcode == Opcodes.ISTORE && prevOpcode == Opcodes.ICONST_0 && awtTryStart != null && !inAwtTry) {
                    zeroStoreCount++;
                    if (zeroStoreCount == 1 || (zeroStoreCount % 2 == 1)) {
                        dxSlot = varIndex;
                    } else {
                        dySlot = varIndex;
                    }
                } else if (opcode == Opcodes.ASTORE && awaitLinkedStore) {
                    awaitLinkedStore = false;
                    if (pointSlot >= 0 && newZoomSlot >= 0) {
                        linkedLoopContinue = new Label();
                        final Label fallbackRaw = new Label();
                        final Label runOriginal = new Label();

                        SlotBridgeEmitter.emitPredicateConsult(
                                mv, AtlasInteractiveRenderBridge.DEFER_LINKED_ZOOM_PROPERTY, fallbackRaw);
                        mv.visitInsn(Opcodes.ICONST_3);
                        mv.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT_OWNER);
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitInsn(Opcodes.ICONST_0);
                        mv.visitVarInsn(Opcodes.ALOAD, varIndex);
                        mv.visitInsn(Opcodes.AASTORE);
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitInsn(Opcodes.ICONST_1);
                        mv.visitVarInsn(Opcodes.ALOAD, pointSlot);
                        mv.visitInsn(Opcodes.AASTORE);
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitInsn(Opcodes.ICONST_2);
                        mv.visitVarInsn(Opcodes.DLOAD, newZoomSlot);
                        mv.visitMethodInsn(
                                Opcodes.INVOKESTATIC, DOUBLE_OWNER, "valueOf", "(D)Ljava/lang/Double;", false);
                        mv.visitInsn(Opcodes.AASTORE);
                        SlotBridgeEmitter.emitTest(mv);
                        mv.visitJumpInsn(Opcodes.IFNE, linkedLoopContinue);
                        mv.visitJumpInsn(Opcodes.GOTO, runOriginal);
                        mv.visitLabel(fallbackRaw);
                        mv.visitInsn(Opcodes.POP);
                        mv.visitLabel(runOriginal);
                    }
                }
                prevOpcode = -1;
            }

            @Override
            public void visitIincInsn(final int varIndex, final int increment) {
                if (linkedLoopContinue != null && increment == 1) {
                    mv.visitLabel(linkedLoopContinue);
                    linkedLoopContinue = null;
                    localSites[0]++;
                }
                super.visitIincInsn(varIndex, increment);
                prevOpcode = -1;
            }

            @Override
            public void visitJumpInsn(final int opcode, final Label label) {
                super.visitJumpInsn(opcode, label);
                prevOpcode = -1;
            }
        };
    }

    private MethodVisitor wrapScrollDrag(final MethodVisitor delegate, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private boolean awaitPointStore;
            private int targetPtSlot = -1;
            private boolean awaitLinkedStore;
            private Label linkedLoopContinue;

            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean itf) {
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && generation.cameraOwner().equals(owner)
                        && "repaint".equals(name)
                        && AtlasInteractiveRenderTarget.VOID_METHOD_DESCRIPTOR.equals(descriptor)) {
                    final Label fallbackRaw = new Label();
                    final Label runOriginal = new Label();
                    final Label after = new Label();

                    mv.visitInsn(Opcodes.DUP);
                    SlotBridgeEmitter.emitPredicateConsult(
                            mv, AtlasInteractiveRenderBridge.REPAINT_VIEWPORT_ONLY_PROPERTY, fallbackRaw);
                    mv.visitInsn(Opcodes.SWAP);
                    SlotBridgeEmitter.emitTest(mv);
                    mv.visitJumpInsn(Opcodes.IFEQ, runOriginal);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitJumpInsn(Opcodes.GOTO, after);

                    mv.visitLabel(fallbackRaw);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitLabel(runOriginal);
                    super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    mv.visitLabel(after);
                    localSites[0]++;
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                if (POINT_OWNER.equals(owner) && "<init>".equals(name) && "(II)V".equals(descriptor)) {
                    awaitPointStore = true;
                }
            }

            @Override
            public void visitTypeInsn(final int opcode, final String type) {
                super.visitTypeInsn(opcode, type);
                if (opcode == Opcodes.CHECKCAST && generation.cameraOwner().equals(type)) {
                    awaitLinkedStore = true;
                }
            }

            @Override
            public void visitVarInsn(final int opcode, final int varIndex) {
                super.visitVarInsn(opcode, varIndex);
                if (opcode == Opcodes.ASTORE && awaitPointStore) {
                    awaitPointStore = false;
                    targetPtSlot = varIndex;
                } else if (opcode == Opcodes.ASTORE && awaitLinkedStore) {
                    awaitLinkedStore = false;
                    if (targetPtSlot >= 0) {
                        linkedLoopContinue = new Label();
                        final Label fallbackRaw = new Label();
                        final Label runOriginal = new Label();

                        SlotBridgeEmitter.emitPredicateConsult(
                                mv, AtlasInteractiveRenderBridge.DEFER_LINKED_PAN_PROPERTY, fallbackRaw);
                        mv.visitInsn(Opcodes.ICONST_2);
                        mv.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT_OWNER);
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitInsn(Opcodes.ICONST_0);
                        mv.visitVarInsn(Opcodes.ALOAD, varIndex);
                        mv.visitInsn(Opcodes.AASTORE);
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitInsn(Opcodes.ICONST_1);
                        mv.visitVarInsn(Opcodes.ALOAD, targetPtSlot);
                        mv.visitInsn(Opcodes.AASTORE);
                        SlotBridgeEmitter.emitTest(mv);
                        mv.visitJumpInsn(Opcodes.IFNE, linkedLoopContinue);
                        mv.visitJumpInsn(Opcodes.GOTO, runOriginal);
                        mv.visitLabel(fallbackRaw);
                        mv.visitInsn(Opcodes.POP);
                        mv.visitLabel(runOriginal);
                    }
                }
            }

            @Override
            public void visitIincInsn(final int varIndex, final int increment) {
                if (linkedLoopContinue != null && increment == 1) {
                    mv.visitLabel(linkedLoopContinue);
                    linkedLoopContinue = null;
                    localSites[0]++;
                }
                super.visitIincInsn(varIndex, increment);
            }
        };
    }

    private static void emitTypedConsult(
            final MethodVisitor mv, final String propertyKey, final String expectedType, final Label fallback) {
        SlotBridgeEmitter.emitGetSlot(mv, propertyKey);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, expectedType);
        mv.visitJumpInsn(Opcodes.IFEQ, fallback);
        mv.visitTypeInsn(Opcodes.CHECKCAST, expectedType);
    }

    private static void emitSlotTestIgnoreResult(
            final MethodVisitor mv, final String propertyKey, final int argLocalSlot) {
        final Label fallback = new Label();
        final Label done = new Label();
        SlotBridgeEmitter.emitPredicateConsult(mv, propertyKey, fallback);
        mv.visitVarInsn(Opcodes.ALOAD, argLocalSlot);
        SlotBridgeEmitter.emitTest(mv);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(fallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(done);
    }

    private static void emitTwoArgSkip(
            final MethodVisitor mv, final String propertyKey, final Runnable originalInsn) {
        final Label fallbackRaw = new Label();
        final Label runOriginal = new Label();
        final Label after = new Label();

        SlotBridgeEmitter.emitPredicateConsult(mv, propertyKey, fallbackRaw);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        SlotBridgeEmitter.emitTest(mv);
        mv.visitJumpInsn(Opcodes.IFEQ, runOriginal);
        mv.visitInsn(Opcodes.POP2);
        mv.visitJumpInsn(Opcodes.GOTO, after);

        mv.visitLabel(fallbackRaw);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(runOriginal);
        originalInsn.run();
        mv.visitLabel(after);
    }

    private static void emitOneArgReceiverSkip(
            final MethodVisitor mv,
            final String propertyKey,
            final int receiverSlot,
            final Runnable originalInsn) {
        final Label fallbackRaw = new Label();
        final Label runOriginal = new Label();
        final Label after = new Label();

        SlotBridgeEmitter.emitPredicateConsult(mv, propertyKey, fallbackRaw);
        mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
        SlotBridgeEmitter.emitTest(mv);
        mv.visitJumpInsn(Opcodes.IFEQ, runOriginal);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, after);

        mv.visitLabel(fallbackRaw);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(runOriginal);
        originalInsn.run();
        mv.visitLabel(after);
    }

    private static List<String> requireShape(
            final byte[] bytes, final String owner, final String method, final String descriptor) {
        Objects.requireNonNull(bytes, owner);
        final List<String> shape = ReviewedMethodShape.read(bytes, owner, method, descriptor);
        if (shape == null) {
            throw new IllegalArgumentException(owner + "." + method + descriptor + " absent from reference");
        }
        return shape;
    }
}
