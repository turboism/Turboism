package dev.turboism.adapter.cubism.textureatlas;

import static org.objectweb.asm.Opcodes.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Rewrites non-mesh atlas loading, UI filtering, shape construction, and OK confirmation hotspots:
 * {@code TAE__EditLayer_ModelImage}, {@code TAE_ModelImageList}, {@code graphics3d.type.d},
 * {@code atlasEditor.impl.g}, and {@code doc.model.texture.r}.
 */
public final class TextureAtlasUiListTransformer implements ClassFileTransformer {

    public static final String EDIT_LAYER_MODEL_IMAGE =
            "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE__EditLayer_ModelImage";
    public static final String MODEL_IMAGE_LIST =
            "com/live2d/cubism/doc/modeling/ui/atlasEditor/modelImageList/TAE_ModelImageList";
    public static final String PATH_WRAPPER = "com/live2d/graphics3d/type/d";
    public static final String CONFIRM_TASK = "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/g";
    public static final String CONFIRM_BRIDGE = "com/live2d/cubism/doc/model/texture/r";

    private static final String MODEL_SOURCE_INTERNAL = "com/live2d/cubism/doc/model/CModelSource";
    private static final String IMAGE_RESOURCE_INTERNAL = "com/live2d/graphics/CImageResource";
    private static final String WRITABLE_IMAGE_INTERNAL = "com/live2d/graphics/CWritableImage";
    private static final String PATH_2D_FLOAT = "java/awt/geom/Path2D$Float";
    private static final String AFFINE_TRANSFORM = "java/awt/geom/AffineTransform";
    private static final String HASH_MAP = "java/util/HashMap";
    private static final String SYSTEM_INTERNAL = "java/lang/System";
    private static final String MATH_INTERNAL = "java/lang/Math";
    private static final String PREVIEW_BRIDGE = Type.getInternalName(TextureAtlasPreviewImages.class);
    private static final String UI_DELEGATE = Type.getInternalName(TextureAtlasUiListDelegate.class);

    private static final String CONSTRUCTOR = "<init>";
    private static final String UPDATE_LIST = "updateList";
    private static final String VOID_DESC = "()V";
    private static final String METHOD_A = "a";
    private static final String GET_IMAGE = "getImage";
    private static final String CREATE_SCALED = "createScaled";
    private static final String SCHEDULE_PREVIEW = "acquireOrSchedule";
    private static final String SCHEDULE_PREVIEW_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;IILjava/lang/Object;)Ljava/lang/Object;";
    private static final String TRY_UPDATE_LIST = "tryUpdateList";
    private static final String TRY_UPDATE_LIST_DESC = "(Ljava/lang/Object;)Z";
    private static final String TRY_POPULATE_MAP = "tryPopulateAtlasEntries";
    private static final String TRY_POPULATE_MAP_DESC = "(Ljava/lang/Object;Ljava/util/Map;)Z";
    private static final String PATH_WRAPPER_CTOR_DESC = "([F[IL" + AFFINE_TRANSFORM + ";)V";
    private static final String PATH_CTOR_CAPACITY_DESC = "(II)V";
    private static final String MATH_MAX_DESC = "(II)I";
    private static final String CONFIRM_MAP_PARAM = "(L" + MODEL_SOURCE_INTERNAL + ";)V";
    private static final String SYSTEM_GC = "gc";
    private static final String GET_ALL_DRAWABLES = "getAllDrawables";
    private static final int PATH_MIN_CAPACITY = 20;

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        return transform(null, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (classfileBuffer == null || classBeingRedefined != null) {
            return null;
        }
        return patch(className, classfileBuffer);
    }

    /** Patches a single non-mesh atlas class if its bytecode matches the reviewed shape. */
    public static byte[] patch(final String className, final byte[] bytes) {
        if (bytes == null) return null;
        if (EDIT_LAYER_MODEL_IMAGE.equals(className)) {
            return patchEditLayerModelImage(bytes);
        }
        if (MODEL_IMAGE_LIST.equals(className)) {
            return patchModelImageList(bytes);
        }
        if (PATH_WRAPPER.equals(className)) {
            return patchPathWrapper(bytes);
        }
        if (CONFIRM_TASK.equals(className)) {
            return patchConfirmTaskGc(bytes);
        }
        if (CONFIRM_BRIDGE.equals(className)) {
            return patchConfirmBridgeMap(bytes);
        }
        return null;
    }

    static byte[] patchEditLayerModelImage(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        if (!EDIT_LAYER_MODEL_IMAGE.equals(reader.getClassName())) return null;
        final PreviewScan scan = new PreviewScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) return null;
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new PreviewPatchVisitor(writer, scan.ctorDesc), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    static byte[] patchModelImageList(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        if (!MODEL_IMAGE_LIST.equals(reader.getClassName())) return null;
        final UiListScan scan = new UiListScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || scan.updateMethods != 1) return null;
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new UiListPatchVisitor(writer), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    static byte[] patchPathWrapper(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        if (!PATH_WRAPPER.equals(reader.getClassName())) return null;
        final PathScan scan = new PathScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadySized || scan.pathDefaultInits != 1) return null;
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new PathPatchVisitor(writer), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    static byte[] patchConfirmTaskGc(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        if (!CONFIRM_TASK.equals(reader.getClassName())) return null;
        final GcScan scan = new GcScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.gcCalls != 1) return null;
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new GcPatchVisitor(writer), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    static byte[] patchConfirmBridgeMap(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        if (!CONFIRM_BRIDGE.equals(reader.getClassName())) return null;
        final ConfirmMapScan scan = new ConfirmMapScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) return null;
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ConfirmMapPatchVisitor(writer), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static final class PreviewScan extends ClassVisitor {
        private String ctorDesc;
        private boolean alreadyPatched;
        private int createScaledCalls;
        private int getImageCalls;

        PreviewScan() {
            super(ASM9);
        }

        boolean matches() {
            return ctorDesc != null && createScaledCalls == 1 && getImageCalls == 1;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!CONSTRUCTOR.equals(name) || VOID_DESC.equals(desc)) return null;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    if (op == INVOKEVIRTUAL && IMAGE_RESOURCE_INTERNAL.equals(owner) && GET_IMAGE.equals(m)) {
                        getImageCalls++;
                    } else if (op == INVOKEVIRTUAL
                            && WRITABLE_IMAGE_INTERNAL.equals(owner)
                            && CREATE_SCALED.equals(m)) {
                        createScaledCalls++;
                        ctorDesc = desc;
                    } else if (op == INVOKESTATIC && PREVIEW_BRIDGE.equals(owner) && SCHEDULE_PREVIEW.equals(m)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }

    private static final class PreviewPatchVisitor extends ClassVisitor {
        private final String targetCtorDesc;

        PreviewPatchVisitor(final ClassWriter writer, final String targetCtorDesc) {
            super(ASM9, writer);
            this.targetCtorDesc = targetCtorDesc;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final MethodVisitor output = super.visitMethod(access, name, desc, sig, ex);
            if (!CONSTRUCTOR.equals(name) || !targetCtorDesc.equals(desc)) {
                return output;
            }
            return new MethodVisitor(ASM9, output) {
                private boolean previewZone;

                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    if (op == INVOKEVIRTUAL && IMAGE_RESOURCE_INTERNAL.equals(owner) && GET_IMAGE.equals(m)) {
                        // Replace synchronous PNG decode with [layer, srcImage] for parallel worker offload.
                        previewZone = true;
                        super.visitVarInsn(ALOAD, 0);
                        super.visitInsn(SWAP);
                        return;
                    }
                    if (previewZone
                            && op == INVOKEVIRTUAL
                            && WRITABLE_IMAGE_INTERNAL.equals(owner)
                            && CREATE_SCALED.equals(m)) {
                        previewZone = false;
                        super.visitMethodInsn(
                                INVOKESTATIC, PREVIEW_BRIDGE, SCHEDULE_PREVIEW, SCHEDULE_PREVIEW_DESC, false);
                        super.visitTypeInsn(CHECKCAST, WRITABLE_IMAGE_INTERNAL);
                        return;
                    }
                    super.visitMethodInsn(op, owner, m, d, itf);
                }
            };
        }
    }

    private static final class UiListScan extends ClassVisitor {
        private int updateMethods;
        private boolean alreadyPatched;

        UiListScan() {
            super(ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!UPDATE_LIST.equals(name) || !VOID_DESC.equals(desc)) return null;
            updateMethods++;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    if (op == INVOKESTATIC && UI_DELEGATE.equals(owner) && TRY_UPDATE_LIST.equals(m)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }

    private static final class UiListPatchVisitor extends ClassVisitor {
        UiListPatchVisitor(final ClassWriter writer) {
            super(ASM9, writer);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final MethodVisitor output = super.visitMethod(access, name, desc, sig, ex);
            if (!UPDATE_LIST.equals(name) || !VOID_DESC.equals(desc)) {
                return output;
            }
            return new MethodVisitor(ASM9, output) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    final Label fallback = new Label();
                    super.visitVarInsn(ALOAD, 0);
                    super.visitMethodInsn(INVOKESTATIC, UI_DELEGATE, TRY_UPDATE_LIST, TRY_UPDATE_LIST_DESC, false);
                    super.visitJumpInsn(IFEQ, fallback);
                    super.visitInsn(RETURN);
                    super.visitLabel(fallback);
                    super.visitFrame(F_NEW, 1, new Object[] {MODEL_IMAGE_LIST}, 0, new Object[0]);
                }
            };
        }
    }

    private static final class PathScan extends ClassVisitor {
        private int pathDefaultInits;
        private boolean alreadySized;

        PathScan() {
            super(ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!CONSTRUCTOR.equals(name) || !PATH_WRAPPER_CTOR_DESC.equals(desc)) return null;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    if (op == INVOKESPECIAL && PATH_2D_FLOAT.equals(owner) && CONSTRUCTOR.equals(m)) {
                        if (VOID_DESC.equals(d)) {
                            pathDefaultInits++;
                        } else if (PATH_CTOR_CAPACITY_DESC.equals(d)) {
                            alreadySized = true;
                        }
                    }
                }
            };
        }
    }

    private static final class PathPatchVisitor extends ClassVisitor {
        PathPatchVisitor(final ClassWriter writer) {
            super(ASM9, writer);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final MethodVisitor output = super.visitMethod(access, name, desc, sig, ex);
            if (!CONSTRUCTOR.equals(name) || !PATH_WRAPPER_CTOR_DESC.equals(desc)) {
                return output;
            }
            return new MethodVisitor(ASM9, output) {
                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    if (op == INVOKESPECIAL
                            && PATH_2D_FLOAT.equals(owner)
                            && CONSTRUCTOR.equals(m)
                            && VOID_DESC.equals(d)) {
                        super.visitInsn(ICONST_1);
                        super.visitVarInsn(ALOAD, 2);
                        super.visitInsn(ARRAYLENGTH);
                        super.visitInsn(ICONST_3);
                        super.visitInsn(IDIV);
                        super.visitInsn(ICONST_4);
                        super.visitInsn(IMUL);
                        super.visitIntInsn(BIPUSH, PATH_MIN_CAPACITY);
                        super.visitMethodInsn(INVOKESTATIC, MATH_INTERNAL, "max", MATH_MAX_DESC, false);
                        super.visitMethodInsn(
                                INVOKESPECIAL, PATH_2D_FLOAT, CONSTRUCTOR, PATH_CTOR_CAPACITY_DESC, false);
                        return;
                    }
                    super.visitMethodInsn(op, owner, m, d, itf);
                }
            };
        }
    }

    private static final class GcScan extends ClassVisitor {
        private int gcCalls;

        GcScan() {
            super(ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!METHOD_A.equals(name) || !desc.endsWith(";)V")) return null;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    if (op == INVOKESTATIC
                            && SYSTEM_INTERNAL.equals(owner)
                            && SYSTEM_GC.equals(m)
                            && VOID_DESC.equals(d)) {
                        gcCalls++;
                    }
                }
            };
        }
    }

    private static final class GcPatchVisitor extends ClassVisitor {
        GcPatchVisitor(final ClassWriter writer) {
            super(ASM9, writer);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final MethodVisitor output = super.visitMethod(access, name, desc, sig, ex);
            if (!METHOD_A.equals(name) || !desc.endsWith(";)V")) {
                return output;
            }
            return new MethodVisitor(ASM9, output) {
                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    if (op == INVOKESTATIC
                            && SYSTEM_INTERNAL.equals(owner)
                            && SYSTEM_GC.equals(m)
                            && VOID_DESC.equals(d)) {
                        return;
                    }
                    super.visitMethodInsn(op, owner, m, d, itf);
                }
            };
        }
    }

    private static final class ConfirmMapScan extends ClassVisitor {
        private boolean alreadyPatched;
        private int targetMethods;
        private int hashMapStores;
        private int getAllDrawablesCalls;

        ConfirmMapScan() {
            super(ASM9);
        }

        boolean matches() {
            return targetMethods == 1 && hashMapStores >= 1 && getAllDrawablesCalls == 1;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!METHOD_A.equals(name) || !CONFIRM_MAP_PARAM.equals(desc)) return null;
            targetMethods++;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitVarInsn(final int op, final int varIndex) {
                    if (op == ASTORE && varIndex == 2) {
                        hashMapStores++;
                    }
                }

                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    if (op == INVOKEVIRTUAL && MODEL_SOURCE_INTERNAL.equals(owner) && GET_ALL_DRAWABLES.equals(m)) {
                        getAllDrawablesCalls++;
                    } else if (op == INVOKESTATIC && UI_DELEGATE.equals(owner) && TRY_POPULATE_MAP.equals(m)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }

    private static final class ConfirmMapPatchVisitor extends ClassVisitor {
        ConfirmMapPatchVisitor(final ClassWriter writer) {
            super(ASM9, writer);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final MethodVisitor output = super.visitMethod(access, name, desc, sig, ex);
            if (!METHOD_A.equals(name) || !CONFIRM_MAP_PARAM.equals(desc)) {
                return output;
            }
            return new MethodVisitor(ASM9, output) {
                private final Label skipToDrawables = new Label();
                private boolean injectedFastMap;
                private boolean placedTargetLabel;
                private boolean pendingAload1;
                private boolean lastWasFrame;

                private void flushPendingAload1() {
                    if (pendingAload1) {
                        pendingAload1 = false;
                        lastWasFrame = false;
                        super.visitVarInsn(ALOAD, 1);
                    }
                }

                @Override
                public void visitFrame(
                        final int type,
                        final int numLocal,
                        final Object[] local,
                        final int numStack,
                        final Object[] stack) {
                    flushPendingAload1();
                    super.visitFrame(type, numLocal, local, numStack, stack);
                    lastWasFrame = true;
                }

                @Override
                public void visitVarInsn(final int op, final int varIndex) {
                    if (injectedFastMap && !placedTargetLabel && op == ALOAD && varIndex == 1) {
                        flushPendingAload1();
                        pendingAload1 = true;
                        return;
                    }
                    flushPendingAload1();
                    lastWasFrame = false;
                    super.visitVarInsn(op, varIndex);
                    if (!injectedFastMap && op == ASTORE && varIndex == 2) {
                        injectedFastMap = true;
                        super.visitVarInsn(ALOAD, 1);
                        super.visitVarInsn(ALOAD, 2);
                        super.visitMethodInsn(
                                INVOKESTATIC, UI_DELEGATE, TRY_POPULATE_MAP, TRY_POPULATE_MAP_DESC, false);
                        super.visitJumpInsn(IFNE, skipToDrawables);
                    }
                }

                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    if (pendingAload1
                            && !placedTargetLabel
                            && op == INVOKEVIRTUAL
                            && MODEL_SOURCE_INTERNAL.equals(owner)
                            && GET_ALL_DRAWABLES.equals(m)) {
                        pendingAload1 = false;
                        placedTargetLabel = true;
                        if (lastWasFrame) {
                            super.visitInsn(NOP);
                        }
                        super.visitLabel(skipToDrawables);
                        super.visitFrame(
                                F_NEW, 3, new Object[] {TOP, MODEL_SOURCE_INTERNAL, HASH_MAP}, 0, new Object[0]);
                        super.visitVarInsn(ALOAD, 1);
                        super.visitMethodInsn(op, owner, m, d, itf);
                        lastWasFrame = false;
                        return;
                    }
                    flushPendingAload1();
                    lastWasFrame = false;
                    super.visitMethodInsn(op, owner, m, d, itf);
                }

                @Override
                public void visitInsn(final int op) {
                    flushPendingAload1();
                    lastWasFrame = false;
                    super.visitInsn(op);
                }

                @Override
                public void visitIntInsn(final int op, final int operand) {
                    flushPendingAload1();
                    lastWasFrame = false;
                    super.visitIntInsn(op, operand);
                }

                @Override
                public void visitTypeInsn(final int op, final String type) {
                    flushPendingAload1();
                    lastWasFrame = false;
                    super.visitTypeInsn(op, type);
                }

                @Override
                public void visitFieldInsn(final int op, final String owner, final String f, final String fd) {
                    flushPendingAload1();
                    lastWasFrame = false;
                    super.visitFieldInsn(op, owner, f, fd);
                }

                @Override
                public void visitJumpInsn(final int op, final Label label) {
                    flushPendingAload1();
                    lastWasFrame = false;
                    super.visitJumpInsn(op, label);
                }

                @Override
                public void visitLdcInsn(final Object value) {
                    flushPendingAload1();
                    lastWasFrame = false;
                    super.visitLdcInsn(value);
                }

                @Override
                public void visitIincInsn(final int varIndex, final int increment) {
                    flushPendingAload1();
                    lastWasFrame = false;
                    super.visitIincInsn(varIndex, increment);
                }

                @Override
                public void visitLabel(final Label label) {
                    flushPendingAload1();
                    lastWasFrame = false;
                    super.visitLabel(label);
                }
            };
        }
    }
}
