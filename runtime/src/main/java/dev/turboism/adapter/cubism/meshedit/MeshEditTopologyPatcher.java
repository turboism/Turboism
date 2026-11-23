package dev.turboism.adapter.cubism.meshedit;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Patches editable-mesh triangulation, edge-loop enumeration, duplicate-point clustering, and
 * {@code GEditableMesh2} lookup/cache-invalidation bytecode.
 */
final class MeshEditTopologyPatcher {

    static final String TRIANGULATION_G_INTERNAL = "com/live2d/graphics3d/editableMesh/triangulation/g";
    static final String EDITABLE_MESH_HELPER_INTERNAL = "com/live2d/graphics3d/editableMesh/b";
    static final String MESH_HANDLER_INTERNAL = "com/live2d/graphics3d/editableMesh/GEditableMeshHandler";
    static final String G_EDITABLE_MESH_INTERNAL = "com/live2d/graphics3d/editableMesh/GEditableMesh2";

    private static final String TOPOLOGY_OPT_INTERNAL =
            "dev/turboism/adapter/cubism/meshedit/EditableMeshTopologyOptimizer";
    private static final String NO_MATCH_SENTINEL_FIELD = "NO_MATCH_SENTINEL";
    private static final String INTEGER_INTERNAL = "java/lang/Integer";
    private static final String MEDGE_INTERNAL = "com/live2d/graphics3d/editableMesh/MEdge";
    private static final String INT_ARRAY_CLASS_INTERNAL = "[I";
    private static final String ITERABLE_INTERNAL = "java/lang/Iterable";
    private static final String ARRAYS_KT_INTERNAL = "kotlin/collections/ArraysKt";
    private static final String SYSTEM_INTERNAL = "java/lang/System";
    private static final String ARRAYCOPY_NAME = "arraycopy";

    private static final String TRIANGULATE_METHOD_NAME = "a";
    private static final String TRIANGULATE_DESC_PREFIX =
            "(Lcom/live2d/graphics3d/editableMesh/GEditableMesh2;Lcom/live2d/util/";
    private static final String TRIANGULATE_DESC_SUFFIX = "/a;)V";
    private static final String NON_DELAUNAY_METHOD_NAME = "b";
    private static final String NON_DELAUNAY_DESC =
            "(Lcom/live2d/graphics3d/editableMesh/GEditableMesh2;)Ljava/util/List;";
    private static final String DUP_POINTS_METHOD_NAME = "d";
    private static final String FIND_TRIANGLES_METHOD_NAME = "a";

    private static final String BUILD_INCIDENT_MATRIX_NAME = "buildIncidentEdgeMatrix";
    private static final String BUILD_INCIDENT_MATRIX_DESC = "(Ljava/util/List;Ljava/util/List;)[[I";
    private static final String BUILD_EDGE_TABLE_NAME = "buildUndirectedEdgeTable";
    private static final String BUILD_EDGE_TABLE_DESC = "(Ljava/util/List;)[J";
    private static final String CONTAINS_EDGE_NAME = "containsUndirectedEdge";
    private static final String CONTAINS_EDGE_DESC = "([JII)Z";
    private static final String FIND_DUPLICATES_NAME = "findDuplicatePointGroups";
    private static final String FIND_TRIANGLES_BRIDGE_NAME = "findMeshTriangles";
    private static final String SINGLE_LIST_BRIDGE_DESC = "(Ljava/lang/Object;)Ljava/util/List;";

    private static final String FIELD_MIN_REQUIRED_DEG = "MIN_REQUIRED_DEG";
    private static final String FLOAT_DESC = "F";
    private static final float DEFAULT_MIN_REQUIRED_DEG = 4.0f;

    private static final String CHECK_EXISTING_NAME = "chechExistingEdge_exe";
    private static final String CHECK_EXISTING_DESC = "(IIZ)Ljava/lang/Integer;";
    private static final String GET_EDGE_INDEX_NAME = "getEdgeIndex";
    private static final String GET_EDGE_INDEX_DESC = "(II)I";
    private static final String GET_EDGE_NAME = "getEdge";
    private static final String GET_EDGE_DESC = "(II)Lcom/live2d/graphics3d/editableMesh/MEdge;";
    private static final String GET_POINT_INDEX_UID_NAME = "getPointIndexByUid";
    private static final String GET_POINT_REF_UID_NAME = "getPointRefByUid";
    private static final String LONG_TO_INT_DESC = "(J)I";
    private static final String GET_POINT_REF_UID_DESC = "(J)Lcom/live2d/graphics3d/editableMesh/a;";
    private static final String CHECK_SIMILAR_NAME = "checkSimilarEdge_exe";
    private static final String CHECK_SIMILAR_DESC = "(II)Ljava/lang/Integer;";
    private static final String CHECK_CROSS_NAME = "checkCross__";
    private static final String CHECK_CROSS_DESC =
            "(Lcom/live2d/graphics3d/type/GVector2;IIZZLcom/live2d/graphics3d/editableMesh/MEdge;)Z";
    private static final String ADD_EDGE_NAME = "addEdge";
    private static final String ADD_EDGE_DESC =
            "(IILcom/live2d/graphics3d/editableMesh/MEdge$EdgeType;ZZ)Ljava/lang/Integer;";

    private static final String CLEAR_AUTO_TRI_NAME = "clearAutoTriangulation";
    private static final String CHECK_TYPED_EDGE_NAME = "checkExitingTypedEdge";
    private static final String CHECK_TYPED_EDGE_DESC =
            "(IILcom/live2d/graphics3d/editableMesh/MEdge$EdgeType;)Ljava/lang/Integer;";
    private static final String REMOVE_EDGE_NAME = "removeEdge";
    private static final String REMOVE_ALL_EDGES_NAME = "removeAllEdges";
    private static final String ADD_POINT_NAME = "addPoint";
    private static final String REMOVE_ALL_POINTS_NAME = "removeAllPoints";
    private static final String INIT_BY_MESH_NAME = "initByEditableMesh";
    private static final String REINIT_EXE_NAME = "reinit_exe";

    private static final String BRIDGE_FIND_EXISTING_DESC = "(Ljava/lang/Object;IIZ)Ljava/lang/Object;";
    private static final String BRIDGE_EDGE_INDEX_DESC = "(Ljava/lang/Object;II)I";
    private static final String BRIDGE_GET_EDGE_DESC = "(Ljava/lang/Object;II)Ljava/lang/Object;";
    private static final String BRIDGE_UID_INDEX_DESC = "(Ljava/lang/Object;J)I";
    private static final String BRIDGE_FIND_SIMILAR_DESC = "(Ljava/lang/Object;IIF)Ljava/lang/Object;";
    private static final String BRIDGE_CHECK_CROSS_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;IIZZLjava/lang/Object;ZF)I";
    private static final String ON_EDGE_ADDED_NAME = "onEdgeAdded";
    private static final String ON_TYPED_EDGE_CHECKED_NAME = "onTypedEdgeChecked";
    private static final String ON_EDGE_OBSERVER_DESC = "(Ljava/lang/Object;Ljava/lang/Integer;)V";
    private static final String INVALIDATE_MESH_NAME = "invalidateMesh";
    private static final String INVALIDATE_MESH_DESC = "(Ljava/lang/Object;)V";

    private static final String GET_POINTS_NAME = "getPoints";
    private static final String LIST_SIZE_NAME = "size";
    private static final String ARRAYS_FIRST_NAME = "first";
    private static final String IS_SAME_EDGE_NAME = "isSameEdge";
    private static final String IS_SAME_EDGE_DESC = "(IIZ)Z";

    private static final int UNSET = -1;

    private MeshEditTopologyPatcher() {}

    static boolean supports(final String internalName) {
        return TRIANGULATION_G_INTERNAL.equals(internalName)
                || EDITABLE_MESH_HELPER_INTERNAL.equals(internalName)
                || MESH_HANDLER_INTERNAL.equals(internalName)
                || G_EDITABLE_MESH_INTERNAL.equals(internalName);
    }

    static byte[] patch(final String internalName, final byte[] bytes, final ClassLoader loader) {
        if (TRIANGULATION_G_INTERNAL.equals(internalName)) {
            return patchTriangulationG(bytes, loader);
        }
        if (EDITABLE_MESH_HELPER_INTERNAL.equals(internalName)) {
            return patchEditableMeshHelper(bytes, loader);
        }
        if (MESH_HANDLER_INTERNAL.equals(internalName)) {
            return patchEditableMeshHandler(bytes, loader);
        }
        if (G_EDITABLE_MESH_INTERNAL.equals(internalName)) {
            return patchGEditableMesh2(bytes, loader);
        }
        return null;
    }

    private static boolean isTriangulationTarget(final String name, final String desc) {
        return TRIANGULATE_METHOD_NAME.equals(name)
                && desc.startsWith(TRIANGULATE_DESC_PREFIX)
                && desc.endsWith(TRIANGULATE_DESC_SUFFIX);
    }

    private static byte[] patchTriangulationG(final byte[] bytes, final ClassLoader loader) {
        final ClassReader reader = MeshEditAsmSupport.newClassReader(bytes);
        final MatrixLoopSurvey survey = new MatrixLoopSurvey(false);
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (!isTriangulationTarget(name, desc)) {
                            return null;
                        }
                        survey.foundMethod = true;
                        return survey.createVisitor();
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MeshEditAsmSupport.requireShape(survey.isValid(), "Incompatible triangulation.g bytecode");

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
                        if (!isTriangulationTarget(name, desc) || mv == null) {
                            return mv;
                        }
                        return new MatrixLoopPatcher(mv, survey, UNSET);
                    }
                },
                ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchEditableMeshHelper(final byte[] bytes, final ClassLoader loader) {
        final ClassReader reader = MeshEditAsmSupport.newClassReader(bytes);
        final MatrixLoopSurvey loopSurvey = new MatrixLoopSurvey(true);
        final boolean[] hasDupPoints = new boolean[1];
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (NON_DELAUNAY_METHOD_NAME.equals(name) && NON_DELAUNAY_DESC.equals(desc)) {
                            loopSurvey.foundMethod = true;
                            return loopSurvey.createVisitor();
                        }
                        if (DUP_POINTS_METHOD_NAME.equals(name) && NON_DELAUNAY_DESC.equals(desc)) {
                            hasDupPoints[0] = true;
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MeshEditAsmSupport.requireShape(loopSurvey.isValid(), "Incompatible editableMesh.b bytecode");

        final int edgeTableSlot = loopSurvey.maxLocals;
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
                        if (NON_DELAUNAY_METHOD_NAME.equals(name) && NON_DELAUNAY_DESC.equals(desc)) {
                            return new MatrixLoopPatcher(mv, loopSurvey, edgeTableSlot);
                        }
                        if (hasDupPoints[0] && DUP_POINTS_METHOD_NAME.equals(name) && NON_DELAUNAY_DESC.equals(desc)) {
                            return new SingleMeshListPrefixPatcher(mv, FIND_DUPLICATES_NAME);
                        }
                        return mv;
                    }
                },
                ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchEditableMeshHandler(final byte[] bytes, final ClassLoader loader) {
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
                        if (FIND_TRIANGLES_METHOD_NAME.equals(name) && NON_DELAUNAY_DESC.equals(desc)) {
                            found[0] = true;
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MeshEditAsmSupport.requireShape(found[0], "Missing GEditableMeshHandler.a");

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
                        if (!FIND_TRIANGLES_METHOD_NAME.equals(name) || !NON_DELAUNAY_DESC.equals(desc) || mv == null) {
                            return mv;
                        }
                        return new SingleMeshListPrefixPatcher(mv, FIND_TRIANGLES_BRIDGE_NAME);
                    }
                },
                ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchGEditableMesh2(final byte[] bytes, final ClassLoader loader) {
        final ClassReader reader = MeshEditAsmSupport.newClassReader(bytes);
        final EditableMesh2Survey survey = surveyGEditableMesh2(reader);
        MeshEditAsmSupport.requireShape(survey.isValid(), "Incompatible GEditableMesh2 bytecode");

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
                        if (CHECK_EXISTING_NAME.equals(name) && CHECK_EXISTING_DESC.equals(desc)) {
                            return new CheckExistingEdgePatcher(mv);
                        }
                        if (GET_EDGE_INDEX_NAME.equals(name) && GET_EDGE_INDEX_DESC.equals(desc)) {
                            return new EdgeIndexPatcher(mv);
                        }
                        if (GET_EDGE_NAME.equals(name) && GET_EDGE_DESC.equals(desc)) {
                            return new GetEdgePatcher(mv);
                        }
                        if (GET_POINT_INDEX_UID_NAME.equals(name) && LONG_TO_INT_DESC.equals(desc)) {
                            return new PointIndexUidPatcher(mv);
                        }
                        if (GET_POINT_REF_UID_NAME.equals(name) && GET_POINT_REF_UID_DESC.equals(desc)) {
                            return new PointRefUidPatcher(mv, survey);
                        }
                        if (CHECK_SIMILAR_NAME.equals(name)
                                && CHECK_SIMILAR_DESC.equals(desc)
                                && !survey.checkSimilarHasDummyFloatArray) {
                            return new CheckSimilarEdgePatcher(mv, survey.hasMinRequiredDegField);
                        }
                        if (CHECK_CROSS_NAME.equals(name) && CHECK_CROSS_DESC.equals(desc)) {
                            return new CheckCrossPatcher(mv, survey.checkCrossUsesMinDeg);
                        }
                        if (ADD_EDGE_NAME.equals(name) && ADD_EDGE_DESC.equals(desc)) {
                            return new EdgeResultObserverPatcher(mv, ON_EDGE_ADDED_NAME);
                        }
                        if (CHECK_TYPED_EDGE_NAME.equals(name) && CHECK_TYPED_EDGE_DESC.equals(desc)) {
                            return new EdgeResultObserverPatcher(mv, ON_TYPED_EDGE_CHECKED_NAME);
                        }
                        if (isCacheInvalidatingMutator(name)) {
                            return new InvalidateOnReturnPatcher(mv);
                        }
                        return mv;
                    }
                },
                ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static EditableMesh2Survey surveyGEditableMesh2(final ClassReader reader) {
        final EditableMesh2Survey survey = new EditableMesh2Survey();
        reader.accept(
                new ClassVisitor(MeshEditAsmSupport.ASM_API) {
                    @Override
                    public FieldVisitor visitField(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final Object value) {
                        if (FIELD_MIN_REQUIRED_DEG.equals(name) && FLOAT_DESC.equals(desc)) {
                            survey.hasMinRequiredDegField = true;
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
                        if (CHECK_EXISTING_NAME.equals(name) && CHECK_EXISTING_DESC.equals(desc)) {
                            survey.hasCheckExisting = true;
                        } else if (GET_EDGE_INDEX_NAME.equals(name) && GET_EDGE_INDEX_DESC.equals(desc)) {
                            survey.hasGetEdgeIndex = true;
                        } else if (GET_EDGE_NAME.equals(name) && GET_EDGE_DESC.equals(desc)) {
                            survey.hasGetEdge = true;
                        } else if (GET_POINT_INDEX_UID_NAME.equals(name) && LONG_TO_INT_DESC.equals(desc)) {
                            survey.hasGetPointIndexUid = true;
                        } else if (CHECK_SIMILAR_NAME.equals(name) && CHECK_SIMILAR_DESC.equals(desc)) {
                            survey.hasCheckSimilar = true;
                            return new MethodVisitor(MeshEditAsmSupport.ASM_API) {
                                @Override
                                public void visitIntInsn(final int opcode, final int operand) {
                                    if (opcode == Opcodes.NEWARRAY && operand == Opcodes.T_FLOAT) {
                                        survey.checkSimilarHasDummyFloatArray = true;
                                    }
                                }
                            };
                        } else if (ADD_EDGE_NAME.equals(name) && ADD_EDGE_DESC.equals(desc)) {
                            survey.hasAddEdge = true;
                        } else if (GET_POINT_REF_UID_NAME.equals(name) && GET_POINT_REF_UID_DESC.equals(desc)) {
                            survey.hasGetPointRefUid = true;
                            return new MethodVisitor(MeshEditAsmSupport.ASM_API) {
                                private int istoreCount;
                                private boolean prevMinusOne;
                                private int candidateVar = UNSET;
                                private int candidateOrdinal = UNSET;

                                @Override
                                public void visitInsn(final int opcode) {
                                    prevMinusOne = opcode == Opcodes.ICONST_M1;
                                }

                                @Override
                                public void visitVarInsn(final int opcode, final int varIndex) {
                                    if (opcode == Opcodes.ISTORE) {
                                        final int ordinal = istoreCount++;
                                        if (prevMinusOne) {
                                            candidateVar = varIndex;
                                            candidateOrdinal = ordinal;
                                        }
                                    }
                                    prevMinusOne = false;
                                }

                                @Override
                                public void visitJumpInsn(final int opcode, final Label label) {
                                    if (opcode == Opcodes.IFGE
                                            && candidateVar >= 0
                                            && survey.pointRefIndexVar == UNSET) {
                                        survey.pointRefIndexVar = candidateVar;
                                        survey.pointRefStoreOrdinal = candidateOrdinal;
                                    }
                                    prevMinusOne = false;
                                }
                            };
                        } else if (CHECK_CROSS_NAME.equals(name) && CHECK_CROSS_DESC.equals(desc)) {
                            survey.hasCheckCross = true;
                            return new MethodVisitor(MeshEditAsmSupport.ASM_API) {
                                @Override
                                public void visitFieldInsn(
                                        final int opcode,
                                        final String owner,
                                        final String fieldName,
                                        final String fieldDesc) {
                                    if (opcode == Opcodes.GETSTATIC
                                            && G_EDITABLE_MESH_INTERNAL.equals(owner)
                                            && FIELD_MIN_REQUIRED_DEG.equals(fieldName)
                                            && FLOAT_DESC.equals(fieldDesc)) {
                                        survey.checkCrossUsesMinDeg = true;
                                    }
                                }
                            };
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return survey;
    }

    private static boolean isCacheInvalidatingMutator(final String name) {
        return CLEAR_AUTO_TRI_NAME.equals(name)
                || REMOVE_EDGE_NAME.equals(name)
                || REMOVE_ALL_EDGES_NAME.equals(name)
                || ADD_POINT_NAME.equals(name)
                || REMOVE_ALL_POINTS_NAME.equals(name)
                || INIT_BY_MESH_NAME.equals(name)
                || REINIT_EXE_NAME.equals(name);
    }

    private static void emitMinRequiredDeg(final MethodVisitor mv, final boolean hasMinRequiredDegField) {
        if (hasMinRequiredDegField) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, G_EDITABLE_MESH_INTERNAL, FIELD_MIN_REQUIRED_DEG, FLOAT_DESC);
        } else {
            mv.visitLdcInsn(DEFAULT_MIN_REQUIRED_DEG);
        }
    }

    private static void emitIntLookupPrefixOrFallback(final MethodVisitor mv) {
        final Label fallbackLabel = new Label();
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(Integer.MIN_VALUE);
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, fallbackLabel);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(fallbackLabel);
        mv.visitInsn(Opcodes.POP);
    }

    private static final class SingleMeshListPrefixPatcher extends MethodVisitor {

        private final String bridgeMethodName;

        private SingleMeshListPrefixPatcher(final MethodVisitor mv, final String bridgeMethodName) {
            super(MeshEditAsmSupport.ASM_API, mv);
            this.bridgeMethodName = bridgeMethodName;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label fallbackLabel = new Label();
            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, TOPOLOGY_OPT_INTERNAL, bridgeMethodName, SINGLE_LIST_BRIDGE_DESC, false);
            super.visitInsn(Opcodes.DUP);
            super.visitJumpInsn(Opcodes.IFNULL, fallbackLabel);
            super.visitInsn(Opcodes.ARETURN);
            super.visitLabel(fallbackLabel);
            super.visitInsn(Opcodes.POP);
        }
    }

    private static final class MatrixLoopSurvey {

        private final boolean trackTailIsSameEdge;
        boolean foundMethod;
        int pointsVar = UNSET;
        int nonAutoEdgesVar = UNSET;
        int incVar = UNSET;
        int tmpBufStoreOrdinal = UNSET;
        int arraycopyGotoOrdinal = UNSET;
        int polyLoopEdgesAloadOrdinal = UNSET;
        int polyVar = UNSET;
        int foundVar = UNSET;
        int foundStoreOrdinal = UNSET;
        int maxLocals = UNSET;

        private MatrixLoopSurvey(final boolean trackTailIsSameEdge) {
            this.trackTailIsSameEdge = trackTailIsSameEdge;
        }

        MethodVisitor createVisitor() {
            return new MethodVisitor(MeshEditAsmSupport.ASM_API) {
                private int astoreCount;
                private int istoreCount;
                private int aloadCount;
                private int gotoCount;
                private boolean justCalledGetPoints;
                private boolean justNewIncMatrix;
                private boolean sawIncStore;
                private int candidateEdgesVar = UNSET;
                private boolean expectTmpBufSize;
                private boolean justNewTmpBuf;
                private boolean afterArrayCopy;
                private int lastAloadVar = UNSET;
                private int lastEdgesAloadOrdinal = UNSET;
                private int lastIterableEdgesAloadOrdinal = UNSET;
                private boolean sawIsSameEdge;

                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String name,
                        final String desc,
                        final boolean isInterface) {
                    justCalledGetPoints = false;
                    justNewIncMatrix = false;
                    justNewTmpBuf = false;
                    if (G_EDITABLE_MESH_INTERNAL.equals(owner) && GET_POINTS_NAME.equals(name)) {
                        justCalledGetPoints = true;
                    } else if (sawIncStore
                            && nonAutoEdgesVar == UNSET
                            && expectTmpBufSize
                            && LIST_SIZE_NAME.equals(name)) {
                        nonAutoEdgesVar = candidateEdgesVar;
                        expectTmpBufSize = false;
                    } else if (SYSTEM_INTERNAL.equals(owner) && ARRAYCOPY_NAME.equals(name)) {
                        afterArrayCopy = true;
                    } else if (trackTailIsSameEdge
                            && ARRAYS_KT_INTERNAL.equals(owner)
                            && ARRAYS_FIRST_NAME.equals(name)) {
                        polyVar = lastAloadVar;
                    } else if (trackTailIsSameEdge
                            && MEDGE_INTERNAL.equals(owner)
                            && IS_SAME_EDGE_NAME.equals(name)
                            && IS_SAME_EDGE_DESC.equals(desc)) {
                        polyLoopEdgesAloadOrdinal = lastIterableEdgesAloadOrdinal;
                        sawIsSameEdge = true;
                    }
                }

                @Override
                public void visitTypeInsn(final int opcode, final String type) {
                    if (opcode == Opcodes.ANEWARRAY && INT_ARRAY_CLASS_INTERNAL.equals(type) && incVar == UNSET) {
                        justNewIncMatrix = true;
                    } else if (trackTailIsSameEdge
                            && opcode == Opcodes.CHECKCAST
                            && ITERABLE_INTERNAL.equals(type)
                            && lastEdgesAloadOrdinal >= 0
                            && !sawIsSameEdge) {
                        lastIterableEdgesAloadOrdinal = lastEdgesAloadOrdinal;
                    }
                    justCalledGetPoints = false;
                }

                @Override
                public void visitIntInsn(final int opcode, final int operand) {
                    if (opcode == Opcodes.NEWARRAY
                            && operand == Opcodes.T_INT
                            && sawIncStore
                            && tmpBufStoreOrdinal == UNSET) {
                        justNewTmpBuf = true;
                    }
                }

                @Override
                public void visitVarInsn(final int opcode, final int varIndex) {
                    if (opcode == Opcodes.ASTORE) {
                        final int ordinal = astoreCount++;
                        if (justCalledGetPoints && pointsVar == UNSET) {
                            pointsVar = varIndex;
                        } else if (justNewIncMatrix && incVar == UNSET) {
                            incVar = varIndex;
                            sawIncStore = true;
                        } else if (justNewTmpBuf && tmpBufStoreOrdinal == UNSET) {
                            tmpBufStoreOrdinal = ordinal;
                        }
                    } else if (opcode == Opcodes.ALOAD) {
                        final int ordinal = aloadCount++;
                        lastAloadVar = varIndex;
                        if (sawIncStore && nonAutoEdgesVar == UNSET) {
                            candidateEdgesVar = varIndex;
                            expectTmpBufSize = true;
                        }
                        if (trackTailIsSameEdge && nonAutoEdgesVar >= 0 && varIndex == nonAutoEdgesVar) {
                            lastEdgesAloadOrdinal = ordinal;
                        }
                    } else if (opcode == Opcodes.ISTORE) {
                        final int ordinal = istoreCount++;
                        if (trackTailIsSameEdge && sawIsSameEdge && foundStoreOrdinal == UNSET) {
                            foundVar = varIndex;
                            foundStoreOrdinal = ordinal;
                        }
                    }
                    justCalledGetPoints = false;
                    justNewIncMatrix = false;
                    justNewTmpBuf = false;
                }

                @Override
                public void visitInsn(final int opcode) {
                    justCalledGetPoints = false;
                }

                @Override
                public void visitJumpInsn(final int opcode, final Label label) {
                    final int ordinal = gotoCount++;
                    if (afterArrayCopy && opcode == Opcodes.GOTO && arraycopyGotoOrdinal == UNSET) {
                        arraycopyGotoOrdinal = ordinal;
                        afterArrayCopy = false;
                    }
                }

                @Override
                public void visitMaxs(final int maxStack, final int maxLocals) {
                    MatrixLoopSurvey.this.maxLocals = maxLocals;
                }
            };
        }

        boolean isValid() {
            final boolean baseValid = foundMethod
                    && pointsVar >= 0
                    && nonAutoEdgesVar >= 0
                    && incVar >= 0
                    && tmpBufStoreOrdinal >= 0
                    && arraycopyGotoOrdinal >= 0;
            if (!baseValid) {
                return false;
            }
            if (!trackTailIsSameEdge) {
                return true;
            }
            return maxLocals >= 0
                    && polyLoopEdgesAloadOrdinal >= 0
                    && polyVar >= 0
                    && foundVar >= 0
                    && foundStoreOrdinal >= 0;
        }
    }

    private static final class MatrixLoopPatcher extends MethodVisitor {

        private final MatrixLoopSurvey survey;
        private final int edgeTableSlot;
        private final Label afterMatrixLoopLabel = new Label();
        private final Label afterIsSameEdgeLabel = new Label();
        private int astoreCount;
        private int aloadCount;
        private int istoreCount;
        private int gotoCount;

        private MatrixLoopPatcher(final MethodVisitor mv, final MatrixLoopSurvey survey, final int edgeTableSlot) {
            super(MeshEditAsmSupport.ASM_API, mv);
            this.survey = survey;
            this.edgeTableSlot = edgeTableSlot;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (edgeTableSlot >= 0) {
                super.visitInsn(Opcodes.ACONST_NULL);
                super.visitVarInsn(Opcodes.ASTORE, edgeTableSlot);
            }
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (opcode == Opcodes.ALOAD) {
                final int ordinal = aloadCount++;
                if (edgeTableSlot >= 0 && ordinal == survey.polyLoopEdgesAloadOrdinal) {
                    emitContainsEdgeShortcut();
                }
                super.visitVarInsn(opcode, varIndex);
                return;
            }
            super.visitVarInsn(opcode, varIndex);
            if (opcode == Opcodes.ASTORE) {
                final int ordinal = astoreCount++;
                if (ordinal == survey.tmpBufStoreOrdinal) {
                    emitMatrixShortcut();
                }
            } else if (opcode == Opcodes.ISTORE) {
                final int ordinal = istoreCount++;
                if (edgeTableSlot >= 0 && ordinal == survey.foundStoreOrdinal) {
                    super.visitLabel(afterIsSameEdgeLabel);
                }
            }
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            super.visitJumpInsn(opcode, label);
            final int ordinal = gotoCount++;
            if (ordinal == survey.arraycopyGotoOrdinal) {
                super.visitLabel(afterMatrixLoopLabel);
            }
        }

        private void emitMatrixShortcut() {
            if (edgeTableSlot >= 0) {
                super.visitVarInsn(Opcodes.ALOAD, survey.nonAutoEdgesVar);
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        TOPOLOGY_OPT_INTERNAL,
                        BUILD_EDGE_TABLE_NAME,
                        BUILD_EDGE_TABLE_DESC,
                        false);
                super.visitVarInsn(Opcodes.ASTORE, edgeTableSlot);
            }
            final Label fallbackLabel = new Label();
            super.visitVarInsn(Opcodes.ALOAD, survey.pointsVar);
            super.visitVarInsn(Opcodes.ALOAD, survey.nonAutoEdgesVar);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    TOPOLOGY_OPT_INTERNAL,
                    BUILD_INCIDENT_MATRIX_NAME,
                    BUILD_INCIDENT_MATRIX_DESC,
                    false);
            super.visitInsn(Opcodes.DUP);
            super.visitJumpInsn(Opcodes.IFNULL, fallbackLabel);
            super.visitVarInsn(Opcodes.ASTORE, survey.incVar);
            super.visitJumpInsn(Opcodes.GOTO, afterMatrixLoopLabel);
            super.visitLabel(fallbackLabel);
            super.visitInsn(Opcodes.POP);
        }

        private void emitContainsEdgeShortcut() {
            final Label fallbackLabel = new Label();
            super.visitVarInsn(Opcodes.ALOAD, edgeTableSlot);
            super.visitJumpInsn(Opcodes.IFNULL, fallbackLabel);
            super.visitVarInsn(Opcodes.ALOAD, survey.polyVar);
            super.visitInsn(Opcodes.ARRAYLENGTH);
            super.visitJumpInsn(Opcodes.IFLE, fallbackLabel);
            super.visitVarInsn(Opcodes.ALOAD, edgeTableSlot);
            super.visitVarInsn(Opcodes.ALOAD, survey.polyVar);
            super.visitInsn(Opcodes.ICONST_0);
            super.visitInsn(Opcodes.IALOAD);
            super.visitVarInsn(Opcodes.ALOAD, survey.polyVar);
            super.visitInsn(Opcodes.DUP);
            super.visitInsn(Opcodes.ARRAYLENGTH);
            super.visitInsn(Opcodes.ICONST_1);
            super.visitInsn(Opcodes.ISUB);
            super.visitInsn(Opcodes.IALOAD);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, TOPOLOGY_OPT_INTERNAL, CONTAINS_EDGE_NAME, CONTAINS_EDGE_DESC, false);
            super.visitVarInsn(Opcodes.ISTORE, survey.foundVar);
            super.visitJumpInsn(Opcodes.GOTO, afterIsSameEdgeLabel);
            super.visitLabel(fallbackLabel);
        }
    }

    private static final class CheckExistingEdgePatcher extends MethodVisitor {

        private CheckExistingEdgePatcher(final MethodVisitor mv) {
            super(MeshEditAsmSupport.ASM_API, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ILOAD, 1);
            super.visitVarInsn(Opcodes.ILOAD, 2);
            super.visitVarInsn(Opcodes.ILOAD, 3);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    TOPOLOGY_OPT_INTERNAL,
                    "findExistingEdgeIndex",
                    BRIDGE_FIND_EXISTING_DESC,
                    false);
            MeshEditAsmSupport.emitSentinelObjectReturnOrFallback(
                    this, TOPOLOGY_OPT_INTERNAL, NO_MATCH_SENTINEL_FIELD, INTEGER_INTERNAL);
        }
    }

    private static final class EdgeIndexPatcher extends MethodVisitor {

        private EdgeIndexPatcher(final MethodVisitor mv) {
            super(MeshEditAsmSupport.ASM_API, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ILOAD, 1);
            super.visitVarInsn(Opcodes.ILOAD, 2);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, TOPOLOGY_OPT_INTERNAL, "getEdgeIndexFast", BRIDGE_EDGE_INDEX_DESC, false);
            emitIntLookupPrefixOrFallback(this);
        }
    }

    private static final class GetEdgePatcher extends MethodVisitor {

        private GetEdgePatcher(final MethodVisitor mv) {
            super(MeshEditAsmSupport.ASM_API, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ILOAD, 1);
            super.visitVarInsn(Opcodes.ILOAD, 2);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, TOPOLOGY_OPT_INTERNAL, "getEdgeFast", BRIDGE_GET_EDGE_DESC, false);
            MeshEditAsmSupport.emitSentinelObjectReturnOrFallback(
                    this, TOPOLOGY_OPT_INTERNAL, NO_MATCH_SENTINEL_FIELD, MEDGE_INTERNAL);
        }
    }

    private static final class PointIndexUidPatcher extends MethodVisitor {

        private PointIndexUidPatcher(final MethodVisitor mv) {
            super(MeshEditAsmSupport.ASM_API, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.LLOAD, 1);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    TOPOLOGY_OPT_INTERNAL,
                    "getPointIndexByUidFast",
                    BRIDGE_UID_INDEX_DESC,
                    false);
            emitIntLookupPrefixOrFallback(this);
        }
    }

    private static final class PointRefUidPatcher extends MethodVisitor {

        private final EditableMesh2Survey survey;
        private final Label afterSearchLabel = new Label();
        private int istoreCount;

        private PointRefUidPatcher(final MethodVisitor mv, final EditableMesh2Survey survey) {
            super(MeshEditAsmSupport.ASM_API, mv);
            this.survey = survey;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label fallbackLabel = new Label();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.LLOAD, 1);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    TOPOLOGY_OPT_INTERNAL,
                    "getPointIndexByUidFast",
                    BRIDGE_UID_INDEX_DESC,
                    false);
            super.visitInsn(Opcodes.DUP);
            super.visitLdcInsn(Integer.MIN_VALUE);
            super.visitJumpInsn(Opcodes.IF_ICMPEQ, fallbackLabel);
            super.visitVarInsn(Opcodes.ISTORE, survey.pointRefIndexVar);
            super.visitJumpInsn(Opcodes.GOTO, afterSearchLabel);
            super.visitLabel(fallbackLabel);
            super.visitInsn(Opcodes.POP);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            super.visitVarInsn(opcode, varIndex);
            if (opcode == Opcodes.ISTORE) {
                final int ordinal = istoreCount++;
                if (ordinal == survey.pointRefStoreOrdinal) {
                    super.visitLabel(afterSearchLabel);
                }
            }
        }
    }

    private static final class CheckSimilarEdgePatcher extends MethodVisitor {

        private final boolean hasMinRequiredDegField;

        private CheckSimilarEdgePatcher(final MethodVisitor mv, final boolean hasMinRequiredDegField) {
            super(MeshEditAsmSupport.ASM_API, mv);
            this.hasMinRequiredDegField = hasMinRequiredDegField;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ILOAD, 1);
            super.visitVarInsn(Opcodes.ILOAD, 2);
            emitMinRequiredDeg(this, hasMinRequiredDegField);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    TOPOLOGY_OPT_INTERNAL,
                    "findSimilarEdgeFast",
                    BRIDGE_FIND_SIMILAR_DESC,
                    false);
            MeshEditAsmSupport.emitSentinelObjectReturnOrFallback(
                    this, TOPOLOGY_OPT_INTERNAL, NO_MATCH_SENTINEL_FIELD, INTEGER_INTERNAL);
        }
    }

    private static final class CheckCrossPatcher extends MethodVisitor {

        private final boolean checkCollinear;

        private CheckCrossPatcher(final MethodVisitor mv, final boolean checkCollinear) {
            super(MeshEditAsmSupport.ASM_API, mv);
            this.checkCollinear = checkCollinear;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitVarInsn(Opcodes.ILOAD, 2);
            super.visitVarInsn(Opcodes.ILOAD, 3);
            super.visitVarInsn(Opcodes.ILOAD, 4);
            super.visitVarInsn(Opcodes.ILOAD, 5);
            super.visitVarInsn(Opcodes.ALOAD, 6);
            super.visitInsn(checkCollinear ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            emitMinRequiredDeg(this, checkCollinear);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, TOPOLOGY_OPT_INTERNAL, "checkCrossFast", BRIDGE_CHECK_CROSS_DESC, false);
            MeshEditAsmSupport.emitTriStateBooleanReturnOrFallback(this);
        }
    }

    private static final class EdgeResultObserverPatcher extends MethodVisitor {

        private final String bridgeMethodName;

        private EdgeResultObserverPatcher(final MethodVisitor mv, final String bridgeMethodName) {
            super(MeshEditAsmSupport.ASM_API, mv);
            this.bridgeMethodName = bridgeMethodName;
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.ARETURN) {
                super.visitInsn(Opcodes.DUP);
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitInsn(Opcodes.SWAP);
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC, TOPOLOGY_OPT_INTERNAL, bridgeMethodName, ON_EDGE_OBSERVER_DESC, false);
            }
            super.visitInsn(opcode);
        }
    }

    private static final class InvalidateOnReturnPatcher extends MethodVisitor {

        private InvalidateOnReturnPatcher(final MethodVisitor mv) {
            super(MeshEditAsmSupport.ASM_API, mv);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.RETURN || opcode == Opcodes.IRETURN || opcode == Opcodes.ARETURN) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC, TOPOLOGY_OPT_INTERNAL, INVALIDATE_MESH_NAME, INVALIDATE_MESH_DESC, false);
            }
            super.visitInsn(opcode);
        }
    }

    private static final class EditableMesh2Survey {
        boolean hasMinRequiredDegField;
        boolean hasCheckExisting;
        boolean hasGetEdgeIndex;
        boolean hasGetEdge;
        boolean hasGetPointIndexUid;
        boolean hasGetPointRefUid;
        boolean hasCheckSimilar;
        boolean hasCheckCross;
        boolean hasAddEdge;
        boolean checkSimilarHasDummyFloatArray;
        boolean checkCrossUsesMinDeg;
        int pointRefIndexVar = UNSET;
        int pointRefStoreOrdinal = UNSET;

        boolean isValid() {
            return hasCheckExisting
                    && hasGetEdgeIndex
                    && hasGetEdge
                    && hasGetPointIndexUid
                    && hasGetPointRefUid
                    && pointRefIndexVar >= 0
                    && pointRefStoreOrdinal >= 0
                    && hasCheckSimilar
                    && hasCheckCross
                    && hasAddEdge;
        }
    }
}
