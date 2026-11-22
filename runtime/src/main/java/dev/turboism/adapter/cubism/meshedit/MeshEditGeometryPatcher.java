package dev.turboism.adapter.cubism.meshedit;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Patches ArtMesh point-in-triangle lookup, mesh-generator batch transformation, editable-mesh
 * overlay dirty gating, and soft-selection rendering bytecode.
 */
final class MeshEditGeometryPatcher {

    static final String PITD_COMPANION_INTERNAL =
            "com/live2d/cubism/doc/model/drawable/artMesh/PointInTriangleD$a";
    static final String PIT_COMPANION_INTERNAL =
            "com/live2d/cubism/doc/model/drawable/artMesh/PointInTriangle$a";
    static final String MESH_UTILS_INTERNAL = "com/live2d/graphics3d/mesh/MeshUtils";
    static final String MESH_GEN_TRANSFORMER_INTERNAL =
            "com/live2d/cubism/doc/model/extension/meshGenerator/a/a";
    static final String DRAW_EDITABLE_MESH_INTERNAL =
            "com/live2d/cubism/doc/model/drawable/artMesh/DrawEntity_EditableMesh";
    static final String DRAW_ARTMESH_SELECTION_INTERNAL =
            "com/live2d/cubism/doc/model/drawable/artMesh/DrawEntity_ArtMesh_Selection";

    private static final String PIT_ACCEL_INTERNAL =
            "dev/turboism/adapter/cubism/meshedit/PointInTriangleAccelerator";
    private static final String RENDER_OPT_INTERNAL =
            "dev/turboism/adapter/cubism/meshedit/EditableMeshRenderOptimizer";
    private static final String SELECTION_OPT_INTERNAL =
            "dev/turboism/adapter/cubism/meshedit/EditableMeshSelectionOptimizer";

    private static final String NULL_SENTINEL_FIELD = "NULL_SENTINEL";
    private static final String PITD_INTERNAL =
            "com/live2d/cubism/doc/model/drawable/artMesh/PointInTriangleD";
    private static final String PIT_INTERNAL =
            "com/live2d/cubism/doc/model/drawable/artMesh/PointInTriangle";

    private static final String FIND_PIT_BRIDGE_DESC = "(Ljava/lang/Object;[F[IIZ)Ljava/lang/Object;";
    private static final String FIND_STRICT_BRIDGE_DESC = "([F[IILjava/lang/Object;[I)I";
    private static final String TRANSFORM_BATCH_BRIDGE_DESC = "([F[F[II[F[F)[F";

    private static final String PITD_FIND_METHOD_NAME = "a";
    private static final String PITD_FIND_METHOD_DESC =
            "(Lcom/live2d/graphics3d/type/GVector2;[F[IIZ)"
                    + "Lcom/live2d/cubism/doc/model/drawable/artMesh/PointInTriangleD;";
    private static final String PIT_FIND_METHOD_NAME = "a";
    private static final String PIT_FIND_METHOD_DESC =
            "(Lcom/live2d/graphics3d/type/GVector2;[F[IIZ)"
                    + "Lcom/live2d/cubism/doc/model/drawable/artMesh/PointInTriangle;";
    private static final String MESH_UTILS_FIND_NAME = "a";
    private static final String MESH_UTILS_FIND_DESC =
            "([F[IILcom/live2d/graphics3d/type/GVector2;[I)Z";
    private static final String MESH_GEN_TRANSFORM_NAME = "transform";
    private static final String MESH_GEN_TRANSFORM_DESC = "([F[F)[F";

    private static final String FIELD_STRIDE = "c";
    private static final String FIELD_SRC_POS = "d";
    private static final String FIELD_DST_POS = "e";
    private static final String FIELD_INDICES = "f";
    private static final String INT_DESC = "I";
    private static final String FLOAT_ARRAY_DESC = "[F";
    private static final String INT_ARRAY_DESC = "[I";

    private static final String EDITABLE_MESH_BASE_SETTING_DESC =
            "Lcom/live2d/cubism/doc/model/drawable/artMesh/DrawEntity_EditableMesh$a;";
    private static final String EDITABLE_MESH_DRAW_SETTING_DESC =
            "Lcom/live2d/cubism/doc/model/drawable/artMesh/DrawEntity_EditableMesh$c;";
    private static final String POINTS_ENTITY_DESC =
            "Lcom/live2d/graphics3d/geometry/GSimplePointsEntity;";
    private static final String FIELD_BASE_SETTING = "baseSetting";
    private static final String FIELD_POINT_HIGHLIGHT = "eArtMeshPointHighlight";
    private static final String FIELD_SELECTED_POINT = "eArtMeshSelectedPoint";

    private static final String UPDATE_EXE_NAME = "update_exe";
    private static final String UPDATE_EXE_DESC =
            "(Lcom/live2d/cubism/view/context/actionManager/N;"
                    + "Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMeshSource;"
                    + "Lcom/live2d/graphics3d/editableMesh/GEditableMesh2;"
                    + "Lcom/live2d/graphics3d/editableMesh/GEditableMeshSelection;"
                    + "Lcom/live2d/cubism/doc/model/drawable/artMesh/DrawEntity_EditableMesh$c;I)V";
    private static final String UPDATE_DISABLED_NAME = "updateAsDisabled";
    private static final String UPDATE_DISABLED_DESC = "()V";
    private static final String DRAW_MESH_LINES_NAME = "drawMeshLines";
    private static final String DRAW_MESH_POINTS_NAME = "drawMeshPoints";
    private static final String DRAW_ISOLATED_POINTS_NAME = "drawMeshIsolatedHighlightPoints";
    private static final String DRAW_POINTS_METHOD_DESC =
            "(Lcom/live2d/graphics3d/editableMesh/GEditableMesh2;"
                    + "Lcom/live2d/cubism/doc/model/drawable/artMesh/DrawEntity_EditableMesh$c;)V";
    private static final String DRAW_SELECTED_POINT_NAME = "drawSelectedPoint";
    private static final String DRAW_SELECTED_POINT_DESC =
            "(Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMeshSource;"
                    + "Lcom/live2d/graphics3d/editableMesh/GEditableMesh2;"
                    + "Lcom/live2d/graphics3d/editableMesh/GEditableMeshSelection;"
                    + "Lcom/live2d/cubism/doc/model/drawable/artMesh/DrawEntity_EditableMesh$c;)V";

    private static final String POINTS_ENTITY_INTERNAL =
            "com/live2d/graphics3d/geometry/GSimplePointsEntity";
    private static final String SET_ENABLED_NAME = "setEnabled";
    private static final String SET_ENABLED_DESC = "(Z)V";
    private static final String COLLECTION_INTERNAL = "java/util/Collection";
    private static final String COLLECTION_IS_EMPTY_NAME = "isEmpty";
    private static final String COLLECTIONS_KT_INTERNAL = "kotlin/collections/CollectionsKt";
    private static final String TO_FLOAT_ARRAY_NAME = "toFloatArray";
    private static final String TO_FLOAT_ARRAY_DESC = "(Ljava/util/Collection;)[F";
    private static final String LIST_INTERNAL = "java/util/List";
    private static final String LIST_CONTAINS_NAME = "contains";

    private static final String EXTRACT_POSITIONS_NAME = "extractPointPositions";
    private static final String EXTRACT_POSITIONS_DESC = "(Ljava/lang/Object;Z)[F";
    private static final String OVERLAY_VALID_NAME = "isOverlayCacheValid";
    private static final String OVERLAY_VALID_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String OVERLAY_RECORD_NAME = "recordOverlayUpdate";
    private static final String OVERLAY_RECORD_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String SELECTION_VALID_NAME = "isSelectionCacheValid";
    private static final String SELECTION_VALID_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                    + "Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String SELECTION_RECORD_NAME = "recordSelectionUpdate";
    private static final String SELECTION_RECORD_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String INVALIDATE_RENDER_NAME = "invalidateRenderState";
    private static final String INVALIDATE_RENDER_DESC = "(Ljava/lang/Object;)V";

    private static final String DRAW_SOFT_SELECTION_NAME = "drawSoftSelection";
    private static final String DRAW_SOFT_SELECTION_DESC =
            "(Lcom/live2d/cubism/view/context/actionManager/b;)V";
    private static final String ART_MESH_SOURCE_INTERNAL =
            "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshSource";
    private static final String GET_SELECTION_NAME = "getSelection";
    private static final String CCOLOR_INTERNAL = "com/live2d/type/CColor";
    private static final String GET_ALPHA_NAME = "getAlpha";
    private static final String GET_RED_NAME = "getRed";
    private static final String GET_GREEN_NAME = "getGreen";
    private static final String GET_BLUE_NAME = "getBlue";
    private static final String GRAPHICS_MESH_INTERNAL = "com/live2d/graphics3d/mesh/a";
    private static final String GET_VERTEX_COUNT_NAME = "getVertexCount";
    private static final String SET_COLORS_NAME = "setColors";
    private static final String GET_COLORS_NAME = "getColors";
    private static final String GET_POSITIONS_BUFFER_NAME = "getPositionsBuffer";
    private static final String FILL_SOFT_COLORS_NAME = "fillSoftSelectionColors";
    private static final String FILL_SOFT_COLORS_DESC = "(Ljava/lang/Object;IFFFF[F)Z";

    private static final int UNSET = -1;
    private static final int PARAM_QUERY_POINT = 1;
    private static final int PARAM_POSITIONS = 2;
    private static final int PARAM_INDICES = 3;
    private static final int PARAM_STEP = 4;
    private static final int PARAM_SKIP_FALLBACK = 5;
    private static final int PARAM_UTILS_POSITIONS = 1;
    private static final int PARAM_UTILS_INDICES = 2;
    private static final int PARAM_UTILS_STEP = 3;
    private static final int PARAM_UTILS_QUERY = 4;
    private static final int PARAM_UTILS_OUT_TRI = 5;
    private static final int PARAM_UPDATE_PACK = 1;
    private static final int PARAM_UPDATE_MESH = 3;
    private static final int PARAM_UPDATE_SELECTION = 4;
    private static final int PARAM_UPDATE_SETTING = 5;
    private static final int PARAM_DRAW_POINTS_MESH = 1;

    private MeshEditGeometryPatcher() {}

    static boolean supports(final String internalName) {
        return PITD_COMPANION_INTERNAL.equals(internalName)
                || PIT_COMPANION_INTERNAL.equals(internalName)
                || MESH_UTILS_INTERNAL.equals(internalName)
                || MESH_GEN_TRANSFORMER_INTERNAL.equals(internalName)
                || DRAW_EDITABLE_MESH_INTERNAL.equals(internalName)
                || DRAW_ARTMESH_SELECTION_INTERNAL.equals(internalName);
    }

    static byte[] patch(final String internalName, final byte[] bytes, final ClassLoader loader) {
        if (PITD_COMPANION_INTERNAL.equals(internalName)) {
            return patchPointInTriangleCompanion(
                    bytes,
                    loader,
                    PITD_FIND_METHOD_NAME,
                    PITD_FIND_METHOD_DESC,
                    "findDouble",
                    PITD_INTERNAL);
        }
        if (PIT_COMPANION_INTERNAL.equals(internalName)) {
            return patchPointInTriangleCompanion(
                    bytes,
                    loader,
                    PIT_FIND_METHOD_NAME,
                    PIT_FIND_METHOD_DESC,
                    "findFloat",
                    PIT_INTERNAL);
        }
        if (MESH_UTILS_INTERNAL.equals(internalName)) {
            return patchMeshUtils(bytes, loader);
        }
        if (MESH_GEN_TRANSFORMER_INTERNAL.equals(internalName)) {
            return patchMeshGeneratorTransformer(bytes, loader);
        }
        if (DRAW_EDITABLE_MESH_INTERNAL.equals(internalName)) {
            return patchDrawEntityEditableMesh(bytes, loader);
        }
        if (DRAW_ARTMESH_SELECTION_INTERNAL.equals(internalName)) {
            return patchDrawEntityArtMeshSelection(bytes, loader);
        }
        return null;
    }

    private static byte[] patchPointInTriangleCompanion(
            final byte[] bytes,
            final ClassLoader loader,
            final String methodName,
            final String methodDesc,
            final String bridgeMethodName,
            final String resultInternal) {
        final ClassReader reader = MeshEditAsmSupport.newClassReader(bytes);
        final boolean[] found = new boolean[1];
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (methodName.equals(name) && methodDesc.equals(desc)) {
                            found[0] = true;
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MeshEditAsmSupport.requireShape(found[0], "Missing " + methodName + methodDesc);

        final ClassWriter writer = MeshEditAsmSupport.newClassWriter(reader, loader);
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!methodName.equals(name) || !methodDesc.equals(desc) || mv == null) {
                            return mv;
                        }
                        return new MethodVisitor(MeshEditAsmSupport.ASM_API, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, PARAM_QUERY_POINT);
                                super.visitVarInsn(Opcodes.ALOAD, PARAM_POSITIONS);
                                super.visitVarInsn(Opcodes.ALOAD, PARAM_INDICES);
                                super.visitVarInsn(Opcodes.ILOAD, PARAM_STEP);
                                super.visitVarInsn(Opcodes.ILOAD, PARAM_SKIP_FALLBACK);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        PIT_ACCEL_INTERNAL,
                                        bridgeMethodName,
                                        FIND_PIT_BRIDGE_DESC,
                                        false);
                                MeshEditAsmSupport.emitSentinelObjectReturnOrFallback(
                                        this, PIT_ACCEL_INTERNAL, NULL_SENTINEL_FIELD, resultInternal);
                            }
                        };
                    }
                },
                ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchMeshUtils(final byte[] bytes, final ClassLoader loader) {
        final ClassReader reader = MeshEditAsmSupport.newClassReader(bytes);
        final boolean[] found = new boolean[1];
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (MESH_UTILS_FIND_NAME.equals(name) && MESH_UTILS_FIND_DESC.equals(desc)) {
                            found[0] = true;
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MeshEditAsmSupport.requireShape(found[0], "Missing MeshUtils.a");

        final ClassWriter writer = MeshEditAsmSupport.newClassWriter(reader, loader);
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!MESH_UTILS_FIND_NAME.equals(name)
                                || !MESH_UTILS_FIND_DESC.equals(desc)
                                || mv == null) {
                            return mv;
                        }
                        return new MethodVisitor(MeshEditAsmSupport.ASM_API, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, PARAM_UTILS_POSITIONS);
                                super.visitVarInsn(Opcodes.ALOAD, PARAM_UTILS_INDICES);
                                super.visitVarInsn(Opcodes.ILOAD, PARAM_UTILS_STEP);
                                super.visitVarInsn(Opcodes.ALOAD, PARAM_UTILS_QUERY);
                                super.visitVarInsn(Opcodes.ALOAD, PARAM_UTILS_OUT_TRI);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        PIT_ACCEL_INTERNAL,
                                        "findStrictTriangle",
                                        FIND_STRICT_BRIDGE_DESC,
                                        false);
                                MeshEditAsmSupport.emitTriStateBooleanReturnOrFallback(this);
                            }
                        };
                    }
                },
                ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchMeshGeneratorTransformer(final byte[] bytes, final ClassLoader loader) {
        final ClassReader reader = MeshEditAsmSupport.newClassReader(bytes);
        final boolean[] state = new boolean[5];
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API) {
                    @Override
                    public FieldVisitor visitField(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final Object value) {
                        if (FIELD_STRIDE.equals(name) && INT_DESC.equals(desc)) {
                            state[0] = true;
                        } else if (FIELD_SRC_POS.equals(name) && FLOAT_ARRAY_DESC.equals(desc)) {
                            state[1] = true;
                        } else if (FIELD_DST_POS.equals(name) && FLOAT_ARRAY_DESC.equals(desc)) {
                            state[2] = true;
                        } else if (FIELD_INDICES.equals(name) && INT_ARRAY_DESC.equals(desc)) {
                            state[3] = true;
                        }
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (MESH_GEN_TRANSFORM_NAME.equals(name) && MESH_GEN_TRANSFORM_DESC.equals(desc)) {
                            state[4] = true;
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MeshEditAsmSupport.requireShape(
                state[0] && state[1] && state[2] && state[3] && state[4],
                "Incompatible meshGenerator.a.a structure");

        final ClassWriter writer = MeshEditAsmSupport.newClassWriter(reader, loader);
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!MESH_GEN_TRANSFORM_NAME.equals(name)
                                || !MESH_GEN_TRANSFORM_DESC.equals(desc)
                                || mv == null) {
                            return mv;
                        }
                        return new MethodVisitor(MeshEditAsmSupport.ASM_API, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                final Label fallbackLabel = new Label();
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(
                                        Opcodes.GETFIELD, MESH_GEN_TRANSFORMER_INTERNAL, FIELD_SRC_POS, FLOAT_ARRAY_DESC);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(
                                        Opcodes.GETFIELD, MESH_GEN_TRANSFORMER_INTERNAL, FIELD_DST_POS, FLOAT_ARRAY_DESC);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(
                                        Opcodes.GETFIELD, MESH_GEN_TRANSFORMER_INTERNAL, FIELD_INDICES, INT_ARRAY_DESC);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(
                                        Opcodes.GETFIELD, MESH_GEN_TRANSFORMER_INTERNAL, FIELD_STRIDE, INT_DESC);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitVarInsn(Opcodes.ALOAD, 2);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        PIT_ACCEL_INTERNAL,
                                        "tryTransformBatch",
                                        TRANSFORM_BATCH_BRIDGE_DESC,
                                        false);
                                super.visitInsn(Opcodes.DUP);
                                super.visitJumpInsn(Opcodes.IFNULL, fallbackLabel);
                                super.visitInsn(Opcodes.ARETURN);
                                super.visitLabel(fallbackLabel);
                                super.visitInsn(Opcodes.POP);
                            }
                        };
                    }
                },
                ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchDrawEntityEditableMesh(final byte[] bytes, final ClassLoader loader) {
        final ClassReader reader = MeshEditAsmSupport.newClassReader(bytes);
        final EditableMeshSurvey survey = surveyDrawEntityEditableMesh(reader);
        MeshEditAsmSupport.requireShape(survey.isValid(), "Incompatible DrawEntity_EditableMesh bytecode");

        final ClassWriter writer = MeshEditAsmSupport.newClassWriter(reader, loader);
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (mv == null) {
                            return null;
                        }
                        if (UPDATE_DISABLED_NAME.equals(name) && UPDATE_DISABLED_DESC.equals(desc)) {
                            return new MethodVisitor(MeshEditAsmSupport.ASM_API, mv) {
                                @Override
                                public void visitCode() {
                                    super.visitCode();
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            RENDER_OPT_INTERNAL,
                                            INVALIDATE_RENDER_NAME,
                                            INVALIDATE_RENDER_DESC,
                                            false);
                                }
                            };
                        }
                        if (UPDATE_EXE_NAME.equals(name) && UPDATE_EXE_DESC.equals(desc)) {
                            return new UpdateExePatcher(mv, survey);
                        }
                        if (DRAW_MESH_POINTS_NAME.equals(name)
                                && DRAW_POINTS_METHOD_DESC.equals(desc)
                                && survey.drawMeshPoints.hasContainsAndFloatArray()) {
                            return new PointExtractPatcher(mv, survey.drawMeshPoints, true);
                        }
                        if (DRAW_ISOLATED_POINTS_NAME.equals(name)
                                && DRAW_POINTS_METHOD_DESC.equals(desc)
                                && survey.drawIsolatedPoints.hasContainsAndFloatArray()) {
                            return new PointExtractPatcher(mv, survey.drawIsolatedPoints, false);
                        }
                        return mv;
                    }
                },
                ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static EditableMeshSurvey surveyDrawEntityEditableMesh(final ClassReader reader) {
        final EditableMeshSurvey survey = new EditableMeshSurvey();
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API) {
                    @Override
                    public FieldVisitor visitField(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final Object value) {
                        if (FIELD_BASE_SETTING.equals(name) && EDITABLE_MESH_BASE_SETTING_DESC.equals(desc)) {
                            survey.hasBaseSetting = true;
                        } else if (FIELD_POINT_HIGHLIGHT.equals(name) && POINTS_ENTITY_DESC.equals(desc)) {
                            survey.hasPointHighlight = true;
                        } else if (FIELD_SELECTED_POINT.equals(name) && POINTS_ENTITY_DESC.equals(desc)) {
                            survey.hasSelectedPoint = true;
                        }
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (UPDATE_DISABLED_NAME.equals(name) && UPDATE_DISABLED_DESC.equals(desc)) {
                            survey.hasUpdateDisabled = true;
                            return null;
                        }
                        if (UPDATE_EXE_NAME.equals(name) && UPDATE_EXE_DESC.equals(desc)) {
                            survey.hasUpdateExe = true;
                            return new MethodVisitor(MeshEditAsmSupport.ASM_API) {
                                private int aloadZeroCount;
                                private int lastAloadZero = UNSET;

                                @Override
                                public void visitVarInsn(final int opcode, final int varIndex) {
                                    if (opcode == Opcodes.ALOAD && varIndex == 0) {
                                        lastAloadZero = aloadZeroCount++;
                                    }
                                }

                                @Override
                                public void visitMethodInsn(
                                        final int opcode,
                                        final String owner,
                                        final String methodName,
                                        final String methodDesc,
                                        final boolean isInterface) {
                                    if (opcode != Opcodes.INVOKESPECIAL || !DRAW_EDITABLE_MESH_INTERNAL.equals(owner)) {
                                        return;
                                    }
                                    if (DRAW_MESH_LINES_NAME.equals(methodName)) {
                                        survey.overlayStartAloadZero = lastAloadZero;
                                    } else if (DRAW_MESH_POINTS_NAME.equals(methodName)) {
                                        survey.overlayEndMethod = DRAW_MESH_POINTS_NAME;
                                    } else if (DRAW_ISOLATED_POINTS_NAME.equals(methodName)) {
                                        survey.overlayEndMethod = DRAW_ISOLATED_POINTS_NAME;
                                    } else if (DRAW_SELECTED_POINT_NAME.equals(methodName)
                                            && DRAW_SELECTED_POINT_DESC.equals(methodDesc)) {
                                        survey.selectionStartAloadZero = lastAloadZero;
                                    }
                                }
                            };
                        }
                        if (DRAW_MESH_POINTS_NAME.equals(name) && DRAW_POINTS_METHOD_DESC.equals(desc)) {
                            return surveyPointExtractMethod(survey.drawMeshPoints);
                        }
                        if (DRAW_ISOLATED_POINTS_NAME.equals(name) && DRAW_POINTS_METHOD_DESC.equals(desc)) {
                            return surveyPointExtractMethod(survey.drawIsolatedPoints);
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return survey;
    }

    private static MethodVisitor surveyPointExtractMethod(final PointExtractSurvey target) {
        target.present = true;
        return new MethodVisitor(MeshEditAsmSupport.ASM_API) {
            private int astoreCount;
            private int ifeqCount;
            private boolean justCalledToFloatArray;
            private boolean justCalledIsEmpty;

            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String desc,
                    final boolean isInterface) {
                justCalledToFloatArray = false;
                if (LIST_INTERNAL.equals(owner) && LIST_CONTAINS_NAME.equals(name)) {
                    target.hasListContains = true;
                } else if (COLLECTIONS_KT_INTERNAL.equals(owner)
                        && TO_FLOAT_ARRAY_NAME.equals(name)
                        && TO_FLOAT_ARRAY_DESC.equals(desc)) {
                    target.toFloatArrayCount++;
                    justCalledToFloatArray = true;
                } else if (COLLECTION_INTERNAL.equals(owner) && COLLECTION_IS_EMPTY_NAME.equals(name)) {
                    justCalledIsEmpty = true;
                }
            }

            @Override
            public void visitVarInsn(final int opcode, final int varIndex) {
                if (opcode == Opcodes.ASTORE) {
                    final int ordinal = astoreCount++;
                    if (justCalledToFloatArray) {
                        target.posVar = varIndex;
                        target.toFloatArrayStoreOrdinal = ordinal;
                        justCalledToFloatArray = false;
                    }
                } else {
                    justCalledToFloatArray = false;
                }
            }

            @Override
            public void visitJumpInsn(final int opcode, final Label label) {
                if (opcode == Opcodes.IFEQ) {
                    final int ordinal = ifeqCount++;
                    if (justCalledIsEmpty && target.afterEmptyIfeqOrdinal == UNSET) {
                        target.afterEmptyIfeqOrdinal = ordinal;
                        justCalledIsEmpty = false;
                    }
                }
                justCalledToFloatArray = false;
            }

            @Override
            public void visitInsn(final int opcode) {
                justCalledToFloatArray = false;
            }
        };
    }

    private static byte[] patchDrawEntityArtMeshSelection(final byte[] bytes, final ClassLoader loader) {
        final ClassReader reader = MeshEditAsmSupport.newClassReader(bytes);
        final SoftSelectionSurvey survey = surveySoftSelection(reader);
        MeshEditAsmSupport.requireShape(survey.isValid(), "Incompatible drawSoftSelection bytecode");

        final ClassWriter writer = MeshEditAsmSupport.newClassWriter(reader, loader);
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!DRAW_SOFT_SELECTION_NAME.equals(name)
                                || !DRAW_SOFT_SELECTION_DESC.equals(desc)
                                || mv == null) {
                            return mv;
                        }
                        return new SoftSelectionPatcher(mv, survey);
                    }
                },
                ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static SoftSelectionSurvey surveySoftSelection(final ClassReader reader) {
        final SoftSelectionSurvey survey = new SoftSelectionSurvey();
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (!DRAW_SOFT_SELECTION_NAME.equals(name) || !DRAW_SOFT_SELECTION_DESC.equals(desc)) {
                            return null;
                        }
                        survey.foundMethod = true;
                        return new MethodVisitor(MeshEditAsmSupport.ASM_API) {
                            private String pendingStoreKind;
                            private int lastAloadVar = UNSET;
                            private int currentAloadOrdinal;
                            private int lastAloadOrdinal = UNSET;
                            private boolean nextGetVertexCountCapturesSrcMesh;

                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String methodName,
                                    final String methodDesc,
                                    final boolean isInterface) {
                                pendingStoreKind = null;
                                if (ART_MESH_SOURCE_INTERNAL.equals(owner) && GET_SELECTION_NAME.equals(methodName)) {
                                    pendingStoreKind = "SEL";
                                } else if (CCOLOR_INTERNAL.equals(owner) && GET_ALPHA_NAME.equals(methodName)) {
                                    pendingStoreKind = "ALPHA";
                                } else if (CCOLOR_INTERNAL.equals(owner) && GET_RED_NAME.equals(methodName)) {
                                    pendingStoreKind = "RED";
                                } else if (CCOLOR_INTERNAL.equals(owner) && GET_GREEN_NAME.equals(methodName)) {
                                    pendingStoreKind = "GREEN";
                                } else if (CCOLOR_INTERNAL.equals(owner) && GET_BLUE_NAME.equals(methodName)) {
                                    pendingStoreKind = "BLUE";
                                } else if (GRAPHICS_MESH_INTERNAL.equals(owner)
                                        && GET_VERTEX_COUNT_NAME.equals(methodName)
                                        && survey.srcMeshVar == UNSET) {
                                    survey.srcMeshVar = lastAloadVar;
                                } else if (GRAPHICS_MESH_INTERNAL.equals(owner) && SET_COLORS_NAME.equals(methodName)) {
                                    survey.setColorsCount++;
                                    nextGetVertexCountCapturesSrcMesh = true;
                                } else if (GRAPHICS_MESH_INTERNAL.equals(owner)
                                        && GET_COLORS_NAME.equals(methodName)
                                        && survey.dstMeshVar == UNSET) {
                                    survey.dstMeshVar = lastAloadVar;
                                } else if (GRAPHICS_MESH_INTERNAL.equals(owner)
                                        && GET_POSITIONS_BUFFER_NAME.equals(methodName)
                                        && survey.getPositionsAloadOrdinal == UNSET) {
                                    if (survey.dstMeshVar == UNSET) {
                                        survey.dstMeshVar = lastAloadVar;
                                    }
                                    survey.getPositionsAloadOrdinal = lastAloadOrdinal;
                                }
                                if (nextGetVertexCountCapturesSrcMesh
                                        && GRAPHICS_MESH_INTERNAL.equals(owner)
                                        && GET_VERTEX_COUNT_NAME.equals(methodName)) {
                                    survey.srcMeshVar = lastAloadVar;
                                    nextGetVertexCountCapturesSrcMesh = false;
                                }
                            }

                            @Override
                            public void visitVarInsn(final int opcode, final int varIndex) {
                                if (opcode == Opcodes.ALOAD) {
                                    lastAloadVar = varIndex;
                                    lastAloadOrdinal = currentAloadOrdinal++;
                                    pendingStoreKind = null;
                                    return;
                                }
                                if (opcode == Opcodes.ASTORE && "SEL".equals(pendingStoreKind)) {
                                    survey.selVar = varIndex;
                                } else if (opcode == Opcodes.FSTORE && pendingStoreKind != null) {
                                    switch (pendingStoreKind) {
                                        case "ALPHA":
                                            survey.alphaVar = varIndex;
                                            break;
                                        case "RED":
                                            survey.redVar = varIndex;
                                            break;
                                        case "GREEN":
                                            survey.greenVar = varIndex;
                                            break;
                                        case "BLUE":
                                            survey.blueVar = varIndex;
                                            break;
                                        default:
                                            break;
                                    }
                                }
                                pendingStoreKind = null;
                            }

                            @Override
                            public void visitInsn(final int opcode) {
                                pendingStoreKind = null;
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return survey;
    }

    private static final class UpdateExePatcher extends MethodVisitor {

        private final EditableMeshSurvey survey;
        private final Label skipOverlayLabel = new Label();
        private final Label skipSelectionLabel = new Label();
        private int aloadZeroCount;

        private UpdateExePatcher(final MethodVisitor mv, final EditableMeshSurvey survey) {
            super(MeshEditAsmSupport.ASM_API, mv);
            this.survey = survey;
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (opcode == Opcodes.ALOAD && varIndex == 0) {
                final int ordinal = aloadZeroCount++;
                if (ordinal == survey.overlayStartAloadZero) {
                    emitOverlayCheck();
                } else if (ordinal == survey.selectionStartAloadZero) {
                    emitSelectionCheck();
                }
            }
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String desc,
                final boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, desc, isInterface);
            if (opcode != Opcodes.INVOKESPECIAL || !DRAW_EDITABLE_MESH_INTERNAL.equals(owner)) {
                return;
            }
            if (survey.overlayEndMethod.equals(name)) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_MESH);
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitFieldInsn(
                        Opcodes.GETFIELD, DRAW_EDITABLE_MESH_INTERNAL, FIELD_BASE_SETTING, EDITABLE_MESH_BASE_SETTING_DESC);
                super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_SETTING);
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC, RENDER_OPT_INTERNAL, OVERLAY_RECORD_NAME, OVERLAY_RECORD_DESC, false);
                super.visitLabel(skipOverlayLabel);
            } else if (DRAW_SELECTED_POINT_NAME.equals(name) && DRAW_SELECTED_POINT_DESC.equals(desc)) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_PACK);
                super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_MESH);
                super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_SELECTION);
                super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_SETTING);
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC, RENDER_OPT_INTERNAL, SELECTION_RECORD_NAME, SELECTION_RECORD_DESC, false);
                super.visitLabel(skipSelectionLabel);
            }
        }

        private void emitOverlayCheck() {
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_MESH);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(
                    Opcodes.GETFIELD, DRAW_EDITABLE_MESH_INTERNAL, FIELD_BASE_SETTING, EDITABLE_MESH_BASE_SETTING_DESC);
            super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_SETTING);
            if (survey.hasPointHighlight) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitFieldInsn(
                        Opcodes.GETFIELD, DRAW_EDITABLE_MESH_INTERNAL, FIELD_POINT_HIGHLIGHT, POINTS_ENTITY_DESC);
            } else {
                super.visitInsn(Opcodes.ACONST_NULL);
            }
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, RENDER_OPT_INTERNAL, OVERLAY_VALID_NAME, OVERLAY_VALID_DESC, false);
            super.visitJumpInsn(Opcodes.IFNE, skipOverlayLabel);
        }

        private void emitSelectionCheck() {
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_PACK);
            super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_MESH);
            super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_SELECTION);
            super.visitVarInsn(Opcodes.ALOAD, PARAM_UPDATE_SETTING);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(
                    Opcodes.GETFIELD, DRAW_EDITABLE_MESH_INTERNAL, FIELD_SELECTED_POINT, POINTS_ENTITY_DESC);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, RENDER_OPT_INTERNAL, SELECTION_VALID_NAME, SELECTION_VALID_DESC, false);
            super.visitJumpInsn(Opcodes.IFNE, skipSelectionLabel);
        }
    }

    private static final class PointExtractPatcher extends MethodVisitor {

        private final PointExtractSurvey survey;
        private final boolean connected;
        private final Label afterExtractLabel = new Label();
        private int astoreCount;
        private int ifeqCount;

        private PointExtractPatcher(
                final MethodVisitor mv, final PointExtractSurvey survey, final boolean connected) {
            super(MeshEditAsmSupport.ASM_API, mv);
            this.survey = survey;
            this.connected = connected;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label fallbackLabel = new Label();
            super.visitVarInsn(Opcodes.ALOAD, PARAM_DRAW_POINTS_MESH);
            super.visitInsn(connected ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, RENDER_OPT_INTERNAL, EXTRACT_POSITIONS_NAME, EXTRACT_POSITIONS_DESC, false);
            super.visitInsn(Opcodes.DUP);
            super.visitJumpInsn(Opcodes.IFNULL, fallbackLabel);
            if (!connected) {
                final Label nonEmptyLabel = new Label();
                super.visitInsn(Opcodes.DUP);
                super.visitInsn(Opcodes.ARRAYLENGTH);
                super.visitJumpInsn(Opcodes.IFNE, nonEmptyLabel);
                super.visitInsn(Opcodes.POP);
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitFieldInsn(
                        Opcodes.GETFIELD, DRAW_EDITABLE_MESH_INTERNAL, FIELD_POINT_HIGHLIGHT, POINTS_ENTITY_DESC);
                super.visitInsn(Opcodes.ICONST_0);
                super.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, POINTS_ENTITY_INTERNAL, SET_ENABLED_NAME, SET_ENABLED_DESC, false);
                super.visitInsn(Opcodes.RETURN);
                super.visitLabel(nonEmptyLabel);
            }
            super.visitVarInsn(Opcodes.ASTORE, survey.posVar);
            super.visitJumpInsn(Opcodes.GOTO, afterExtractLabel);
            super.visitLabel(fallbackLabel);
            super.visitInsn(Opcodes.POP);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            super.visitVarInsn(opcode, varIndex);
            if (opcode == Opcodes.ASTORE) {
                final int ordinal = astoreCount++;
                if (connected && ordinal == survey.toFloatArrayStoreOrdinal) {
                    super.visitLabel(afterExtractLabel);
                }
            }
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            super.visitJumpInsn(opcode, label);
            if (opcode == Opcodes.IFEQ) {
                final int ordinal = ifeqCount++;
                if (!connected && ordinal == survey.afterEmptyIfeqOrdinal) {
                    super.visitLabel(afterExtractLabel);
                }
            }
        }
    }

    private static final class SoftSelectionPatcher extends MethodVisitor {

        private final SoftSelectionSurvey survey;
        private final Label skipLoopLabel = new Label();
        private int aloadCount;

        private SoftSelectionPatcher(final MethodVisitor mv, final SoftSelectionSurvey survey) {
            super(MeshEditAsmSupport.ASM_API, mv);
            this.survey = survey;
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (opcode == Opcodes.ALOAD) {
                final int ordinal = aloadCount++;
                if (ordinal == survey.getPositionsAloadOrdinal) {
                    super.visitLabel(skipLoopLabel);
                }
            }
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String desc,
                final boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, desc, isInterface);
            if (GRAPHICS_MESH_INTERNAL.equals(owner) && SET_COLORS_NAME.equals(name)) {
                super.visitVarInsn(Opcodes.ALOAD, survey.selVar);
                super.visitVarInsn(Opcodes.ALOAD, survey.srcMeshVar);
                super.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, GRAPHICS_MESH_INTERNAL, GET_VERTEX_COUNT_NAME, "()I", false);
                super.visitVarInsn(Opcodes.FLOAD, survey.alphaVar);
                super.visitVarInsn(Opcodes.FLOAD, survey.redVar);
                super.visitVarInsn(Opcodes.FLOAD, survey.greenVar);
                super.visitVarInsn(Opcodes.FLOAD, survey.blueVar);
                super.visitVarInsn(Opcodes.ALOAD, survey.dstMeshVar);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRAPHICS_MESH_INTERNAL, GET_COLORS_NAME, "()[F", false);
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        SELECTION_OPT_INTERNAL,
                        FILL_SOFT_COLORS_NAME,
                        FILL_SOFT_COLORS_DESC,
                        false);
                super.visitJumpInsn(Opcodes.IFNE, skipLoopLabel);
            }
        }
    }

    private static final class EditableMeshSurvey {
        boolean hasBaseSetting;
        boolean hasPointHighlight;
        boolean hasSelectedPoint;
        boolean hasUpdateDisabled;
        boolean hasUpdateExe;
        int overlayStartAloadZero = UNSET;
        String overlayEndMethod;
        int selectionStartAloadZero = UNSET;
        final PointExtractSurvey drawMeshPoints = new PointExtractSurvey();
        final PointExtractSurvey drawIsolatedPoints = new PointExtractSurvey();

        boolean isValid() {
            if (!hasBaseSetting
                    || !hasSelectedPoint
                    || !hasUpdateDisabled
                    || !hasUpdateExe
                    || overlayStartAloadZero < 0
                    || overlayEndMethod == null
                    || selectionStartAloadZero <= overlayStartAloadZero) {
                return false;
            }
            if (hasPointHighlight) {
                return drawMeshPoints.hasContainsAndFloatArray()
                        && drawIsolatedPoints.hasContainsAndFloatArray()
                        && drawIsolatedPoints.afterEmptyIfeqOrdinal >= 0;
            }
            return drawMeshPoints.present;
        }
    }

    private static final class PointExtractSurvey {
        boolean present;
        boolean hasListContains;
        int toFloatArrayCount;
        int posVar = UNSET;
        int toFloatArrayStoreOrdinal = UNSET;
        int afterEmptyIfeqOrdinal = UNSET;

        boolean hasContainsAndFloatArray() {
            return present && hasListContains && toFloatArrayCount == 1 && posVar >= 0 && toFloatArrayStoreOrdinal >= 0;
        }
    }

    private static final class SoftSelectionSurvey {
        boolean foundMethod;
        int selVar = UNSET;
        int alphaVar = UNSET;
        int redVar = UNSET;
        int greenVar = UNSET;
        int blueVar = UNSET;
        int srcMeshVar = UNSET;
        int dstMeshVar = UNSET;
        int setColorsCount;
        int getPositionsAloadOrdinal = UNSET;

        boolean isValid() {
            return foundMethod
                    && selVar >= 0
                    && alphaVar >= 0
                    && redVar >= 0
                    && greenVar >= 0
                    && blueVar >= 0
                    && srcMeshVar >= 0
                    && dstMeshVar >= 0
                    && setColorsCount == 1
                    && getPositionsAloadOrdinal >= 0;
        }
    }
}
