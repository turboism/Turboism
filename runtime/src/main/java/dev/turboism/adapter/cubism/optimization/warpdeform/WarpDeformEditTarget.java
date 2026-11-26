package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.adapter.cubism.optimization.TargetClassTable;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ModelUpdateTargetTable;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines reviewed host classes, members, and descriptors for edit-mode warp and brush acceleration.
 *
 * <p>Covers {@code CWarpDeformerForm.reinit} array reuse, zero-allocation {@code CWarpDeformer},
 * {@code CSimpleGrid}, and {@code CGridTransform} inverse transforms, Bezier grid evaluation,
 * {@code WarpDeformer_Draw} overlay caching, and deform-brush primitive float loops.</p>
 */
public record WarpDeformEditTarget(HostArtifactDigest digest, String version, List<String> targetClasses) {

    /** Target-class table resource name under {@code target-classes/}. */
    public static final String TARGET_TABLE = "warp-deform-edit-acceleration";

    /** Startup toggle property gating edit-mode warp and deform-brush acceleration. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.warpDeformEditAcceleration";

    /** Internal name of {@code CWarpDeformerForm}. */
    public static final String WARP_FORM_OWNER = "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformerForm";

    /** Internal name of {@code CWarpDeformer}. */
    public static final String WARP_DEFORMER_OWNER = "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformer";

    /** Internal name of {@code CWarpDeformerSource}. */
    public static final String WARP_SOURCE_OWNER = "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformerSource";

    /** Internal name of {@code CRotationDeformerForm}. */
    public static final String ROT_FORM_OWNER = "com/live2d/cubism/doc/model/deformer/rotation/CRotationDeformerForm";

    /** Internal name of {@code CRotationDeformer$a} vector transform helper. */
    public static final String ROT_HELPER_OWNER = "com/live2d/cubism/doc/model/deformer/rotation/CRotationDeformer$a";

    /** Internal name of {@code CSimpleGrid}. */
    public static final String SIMPLE_GRID_OWNER = "com/live2d/cubism/doc/model/deformer/warp/bezier/CSimpleGrid";

    /** Internal name of {@code CGridTransform}. */
    public static final String GRID_TRANSFORM_OWNER = "com/live2d/cubism/doc/model/deformer/warp/grid/CGridTransform";

    /** Internal name of {@code CRectangleGrid}. */
    public static final String RECTANGLE_GRID_OWNER =
            "com/live2d/cubism/doc/model/deformer/warp/grid/boxGrid/CRectangleGrid";

    /** Internal name of {@code CBezierGrid}. */
    public static final String BEZIER_GRID_OWNER = "com/live2d/cubism/doc/model/deformer/warp/bezier/CBezierGrid";

    /** Internal name of {@code CBezierPt}. */
    public static final String BEZIER_PT_OWNER = "com/live2d/cubism/doc/model/deformer/warp/bezier/CBezierPt";

    /** Internal name of {@code WarpDeformer_Draw}. */
    public static final String WARP_DRAW_OWNER = "com/live2d/cubism/doc/model/deformer/warp/WarpDeformer_Draw";

    /** Internal name of {@code WarpDeformerSelection}. */
    public static final String WARP_SELECTION_OWNER = "com/live2d/cubism/doc/model/deformer/warp/WarpDeformerSelection";

    /** Internal name of {@code WarpPointRef}. */
    public static final String WARP_POINT_REF_OWNER = "com/live2d/cubism/doc/model/deformer/warp/WarpPointRef";

    /** Internal name of {@code PointSelector}. */
    public static final String POINT_SELECTOR_OWNER = "com/live2d/doc/selection/PointSelector";

    /** Internal name of {@code GRectF}. */
    public static final String GRECTF_OWNER = "com/live2d/graphics3d/type/GRectF";

    /** Internal name of {@code GVector2}. */
    public static final String GVECTOR2_OWNER = "com/live2d/graphics3d/type/GVector2";

    /** Internal name of {@code GVector3}. */
    public static final String GVECTOR3_OWNER = "com/live2d/graphics3d/type/GVector3";

    /** Internal name of {@code GEntity}. */
    public static final String GENTITY_OWNER = "com/live2d/graphics3d/entity/GEntity";

    /** Internal name of {@code GSimpleMeshEntity}. */
    public static final String SIMPLE_MESH_ENTITY_OWNER = "com/live2d/graphics3d/geometry/GSimpleMeshEntity";

    /** Internal name of {@code GSimpleLinesEntity}. */
    public static final String SIMPLE_LINES_ENTITY_OWNER = "com/live2d/graphics3d/geometry/GSimpleLinesEntity";

    /** Internal name of {@code GSimplePointsEntity}. */
    public static final String SIMPLE_POINTS_ENTITY_OWNER = "com/live2d/graphics3d/geometry/GSimplePointsEntity";

    /** Internal name of {@code GTransform}. */
    public static final String GTRANSFORM_OWNER = "com/live2d/graphics3d/component/GTransform";

    /** Internal name of {@code GMeshContainer}. */
    public static final String GMESH_CONTAINER_OWNER = "com/live2d/graphics3d/component/GMeshContainer";

    /** Internal name of {@code GMeshRenderer}. */
    public static final String GMESH_RENDERER_OWNER = "com/live2d/graphics3d/component/GMeshRenderer";

    /** Internal name of {@code GLineRenderer}. */
    public static final String GLINE_RENDERER_OWNER = "com/live2d/graphics3d/component/GLineRenderer";

    /** Internal name of {@code GPointRenderer}. */
    public static final String GPOINT_RENDERER_OWNER = "com/live2d/graphics3d/component/GPointRenderer";

    /** Internal name of sorting-layer enum subclass {@code c$c}. */
    public static final String SORTING_LAYER_VALUE_OWNER = "com/live2d/graphics3d/component/a/c$c";

    /** Internal name of base mesh class {@code com.live2d.graphics3d.mesh.a}. */
    public static final String MESH_BASE_OWNER = "com/live2d/graphics3d/mesh/a";

    /** Internal name of {@code GMesh2}. */
    public static final String GMESH2_OWNER = "com/live2d/graphics3d/mesh/GMesh2";

    /** Internal name of float GPU buffer wrapper. */
    public static final String FLOAT_BUFFER_OWNER = "com/live2d/graphics3d/mesh/a/b";

    /** Internal name of int GPU buffer wrapper. */
    public static final String INT_BUFFER_OWNER = "com/live2d/graphics3d/mesh/a/c";

    /** Internal name of {@code GMaterial}. */
    public static final String GMATERIAL_OWNER = "com/live2d/graphics3d/material/GMaterial";

    /** Internal name of {@code CColor}. */
    public static final String CCOLOR_OWNER = "com/live2d/type/CColor";

    /** Internal name of {@code CColor$a}. */
    public static final String CCOLOR_COMPANION_OWNER = "com/live2d/type/CColor$a";

    /** Internal name of {@code SimpleGrid}. */
    public static final String LEGACY_SIMPLE_GRID_OWNER = "jp/noids/grid/bezierGrid/SimpleGrid";

    /** Internal name of host debug gate class {@code jp.live2d.cubism.aE}. */
    public static final String DEBUG_FLAG_OWNER = "jp/live2d/cubism/aE";

    /** Internal name prefix of context action classes. */
    public static final String ACTION_PACKAGE_PREFIX = "com/live2d/cubism/view/context/action/";

    public static final String REINIT_METHOD = "reinit";
    public static final String REINIT_DESCRIPTOR = "(Lcom/live2d/core/ICopyable;Lcom/live2d/core/a;)V";
    public static final String FORM_IS_INSIDE_METHOD = "isInside";
    public static final String FORM_IS_INSIDE_DESCRIPTOR = "(Lcom/live2d/graphics3d/type/GVector2;Z)Z";

    public static final String TRANSFORM_CANVAS_TO_LOCAL_METHOD = "transformCanvasToLocal";
    public static final String TRANSFORM_CANVAS_TO_LOCAL_DESCRIPTOR = "([F[FIII)V";

    public static final String GET01_EX_QUAD_METHOD = "get01_ex_quad";
    public static final String GET01_EX_QUAD_DESCRIPTOR = "(FFLcom/live2d/graphics3d/type/GVector2;[F)[F";

    public static final String INVERSE_TRANSFORM_3_3_QUAD_METHOD = "inverseTransform_3_3_Quad";
    public static final String INVERSE_TRANSFORM_3_3_QUAD_DESCRIPTOR =
            "(Lcom/live2d/graphics3d/type/GVector2;Lcom/live2d/graphics3d/type/GVector2;)Lcom/live2d/graphics3d/type/GVector2;";
    public static final String GRID_TRANSFORM_IS_INSIDE_METHOD = "isInside";
    public static final String GRID_TRANSFORM_IS_INSIDE_DESCRIPTOR = "(Lcom/live2d/graphics3d/type/GVector2;Z)Z";

    public static final String CREATE_POINTS_ON_BEZIER_METHOD = "createPointsOnBezier";
    public static final String CREATE_POINTS_ON_BEZIER_DESCRIPTOR = "(II)[F";

    public static final String DRAW_CORNER_TRIANGLE_METHOD = "drawCornerTriangle";
    public static final String DRAW_CORNER_TRIANGLE_DESCRIPTOR = "([FIIFFLcom/live2d/type/CColor;)V";
    public static final String DRAW_MAJOR_LINES_METHOD = "drawMajorLines";
    public static final String DRAW_MAJOR_LINES_DESCRIPTOR = "([FIILcom/live2d/type/CColor;Z)V";
    public static final String DRAW_DETAIL_LINES_METHOD = "drawDetailLines";
    public static final String DRAW_DETAIL_LINES_DESCRIPTOR = "([FIILcom/live2d/type/CColor;Z)V";
    public static final String DRAW_SELECTED_POINT_METHOD = "drawSelectedPoint";
    public static final String DRAW_SELECTED_POINT_DESCRIPTOR = "(Z)V";
    public static final String DRAW_SOFT_SELECTION_METHOD = "drawSoftSelection";
    public static final String DRAW_SOFT_SELECTION_DESCRIPTOR_PREFIX =
            "([FIILcom/live2d/cubism/view/context/actionManager/";

    public static final String ACTION_DEFORM_LIST_TO_FLOATS_METHOD = "a";
    public static final String ACTION_DEFORM_LIST_TO_FLOATS_DESCRIPTOR = "(Ljava/util/List;)[F";
    public static final String ACTION_LEGACY_TRANSFORM_FLOATS_METHOD = "e";
    public static final String ACTION_LEGACY_TRANSFORM_FLOATS_DESCRIPTOR =
            "(Lcom/live2d/cubism/view/context/actionManager/aG;)[F";
    public static final String ACTION_OFFSET_FLOATS_METHOD = "b";
    public static final String ACTION_OFFSET_FLOATS_DESCRIPTOR = "(Lcom/live2d/graphics3d/type/GVector2;)[F";

    /** Method name of {@code CWarpDeformerSource.reinitInstance}. */
    public static final String WARP_SOURCE_REINIT_METHOD = "reinitInstance";

    /** JVM descriptor of {@code CWarpDeformerSource.reinitInstance}. */
    public static final String WARP_SOURCE_REINIT_DESCRIPTOR =
            "(Lcom/live2d/cubism/doc/model/ACParameterControllable;)V";

    /** Method name of {@code CRotationDeformerForm.transform_exe}. */
    public static final String ROT_TRANSFORM_EXE_METHOD = "transform_exe";

    /** JVM descriptor of {@code CRotationDeformerForm.transform_exe}. */
    public static final String ROT_TRANSFORM_EXE_DESCRIPTOR =
            "(Lcom/live2d/doc/selection/d;Lcom/live2d/doc/selection/d;Lcom/live2d/cubism/doc/model/ACForm;)"
                    + "Lcom/live2d/cubism/doc/model/deformer/rotation/CRotationDeformerForm;";

    /** Method name of {@code CRotationDeformer$a.a} directional vector transform helper. */
    public static final String ROT_HELPER_METHOD = "a";

    /** JVM descriptor of {@code CRotationDeformer$a.a}. */
    public static final String ROT_HELPER_DESCRIPTOR = "(Lcom/live2d/doc/selection/d;[F[F[F)V";

    /** Internal name of overlay loop host in Cubism 5.0.x builds. */
    public static final String LEGACY_OVERLAY_VIEW_OWNER = "com/live2d/cubism/view/context/J";

    /** Internal name of selection-draw holder in Cubism 5.1+ builds. */
    public static final String OVERLAY_VIEW_OWNER = "com/live2d/cubism/view/context/K";

    /** Internal name of overlay loop helper in Cubism 5.1+ builds. */
    public static final String OVERLAY_HELPER_OWNER = "com/live2d/cubism/view/context/K$b";

    /** Constant-pool key identifying {@code updateSelectionDraw} across Cubism 5.0–5.3. */
    public static final String SELECTION_DRAW_KEY = "selectionDraw@updateSelectionDraw";

    /** Method descriptor of {@code updateSelectionDraw} ({@code D}/{@code E}). */
    public static final String SELECTION_DRAW_DESCRIPTOR = "()V";

    /** Internal name of {@code DrawEntity_ArtMesh_Selection}. */
    public static final String DRAW_ENTITY_ARTMESH_SELECTION_OWNER =
            "com/live2d/cubism/doc/model/drawable/artMesh/DrawEntity_ArtMesh_Selection";

    /** Internal name of {@code CArtMesh}. */
    public static final String ART_MESH_OWNER = "com/live2d/cubism/doc/model/drawable/artMesh/CArtMesh";

    /** Method name of object constructors. */
    public static final String INIT_METHOD = "<init>";

    /** JVM descriptor of {@code DrawEntity_ArtMesh_Selection.<init>(CArtMesh)}. */
    public static final String DRAW_ENTITY_ARTMESH_CTOR_DESCRIPTOR = "(L" + ART_MESH_OWNER + ";)V";

    /** Method name of {@code DrawEntity_ArtMesh_Selection.update}. */
    public static final String DRAW_ENTITY_ARTMESH_UPDATE_METHOD = "update";

    /** JVM descriptor of {@code DrawEntity_ArtMesh_Selection.update}. */
    public static final String DRAW_ENTITY_ARTMESH_UPDATE_DESCRIPTOR =
            "(Lcom/live2d/cubism/view/context/actionManager/b;ZZ)V";

    /** Method name of {@code GSceneGraph.setVolatile}. */
    public static final String SCENE_GRAPH_SET_VOLATILE_METHOD = "setVolatile";

    /** JVM descriptor of {@code GSceneGraph.setVolatile(GEntity)}. */
    public static final String SCENE_GRAPH_SET_VOLATILE_DESCRIPTOR = "(L" + GENTITY_OWNER + ";)V";

    /** Host internal name for {@code ACForm}. */
    public static final String ACFORM_OWNER = "com/live2d/cubism/doc/model/ACForm";

    /** Host internal name for {@code CArtMeshForm}. */
    public static final String CART_MESH_FORM_OWNER = "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm";

    /** Backing entity field name on {@code ACForm}. */
    public static final String ACFORM_INSTANCE_FIELD = "_instance";

    /** Backing entity field descriptor on {@code ACForm}. */
    public static final String ACFORM_INSTANCE_DESCRIPTOR = "Lcom/live2d/graphics3d/entity/GEntity;";

    /** Internal source-getter method name on {@code ACForm}. */
    public static final String ACFORM_GET_SOURCE_INTERNAL_METHOD = "get_source$cubism";

    /** Host internal name for {@code ACParameterControllableSource}. */
    public static final String AC_CONTROLLABLE_SOURCE_OWNER =
            "com/live2d/cubism/doc/model/ACParameterControllableSource";

    /** Internal source-getter descriptor on {@code ACForm}. */
    public static final String ACFORM_GET_SOURCE_INTERNAL_DESCRIPTOR = "()L" + AC_CONTROLLABLE_SOURCE_OWNER + ";";

    /** Host internal name for {@code ACDeformerSource}. */
    public static final String AC_DEFORMER_SOURCE_OWNER = "com/live2d/cubism/doc/model/deformer/ACDeformerSource";

    /** Host internal name for {@code CArtMeshSource}. */
    public static final String ART_MESH_SOURCE_OWNER = "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshSource";

    /** Host internal name for {@code ACDrawableForm}. */
    public static final String AC_DRAWABLE_FORM_OWNER = "com/live2d/cubism/doc/model/drawable/ACDrawableForm";

    /** Host internal name for {@code CWarpDeformerSource.setKeyforms}. */
    public static final String SET_KEYFORMS_METHOD = "setKeyforms";

    /** JVM descriptor for {@code setKeyforms(CArrayList)}. */
    public static final String SET_KEYFORMS_DESCRIPTOR = "(Lcom/live2d/type/CArrayList;)V";

    /** Method name of {@code ACDeformerSource.setUpdatedFlag}. */
    public static final String SET_UPDATED_FLAG_METHOD = "setUpdatedFlag";

    /** Void no-arg JVM descriptor. */
    public static final String VOID_NO_ARG_DESCRIPTOR = "()V";

    /** Method name of {@code CArtMesh.setAnimatedForm}. */
    public static final String SET_ANIMATED_FORM_METHOD = "setAnimatedForm";

    /** Method name of {@code CArtMesh.setLocalAnimatedForm}. */
    public static final String SET_LOCAL_ANIMATED_FORM_METHOD = "setLocalAnimatedForm";

    /** Method name of {@code CArtMesh.setInterpolatedForm}. */
    public static final String SET_INTERPOLATED_FORM_METHOD = "setInterpolatedForm";

    /** Method name of {@code CArtMesh.setDeformedForm}. */
    public static final String SET_DEFORMED_FORM_METHOD = "setDeformedForm";

    /** JVM descriptor accepting {@code ACForm}. */
    public static final String ACFORM_PARAM_DESCRIPTOR = "(L" + ACFORM_OWNER + ";)V";

    /** JVM descriptor accepting {@code CArtMeshForm}. */
    public static final String ART_MESH_FORM_PARAM_DESCRIPTOR = "(L" + CART_MESH_FORM_OWNER + ";)V";

    /** Method name of {@code CArtMesh.setCopiedMeshVersion}. */
    public static final String SET_COPIED_MESH_VERSION_METHOD = "setCopiedMeshVersion";

    /** Single-int setter JVM descriptor. */
    public static final String SET_INT_DESCRIPTOR = "(I)V";

    /** Method name of {@code CArtMesh.recover}. */
    public static final String RECOVER_METHOD = "recover";

    /** Method name of {@code CArtMeshSource.setMeshUpdatedFlag__testImpl}. */
    public static final String SET_MESH_UPDATED_TEST_METHOD = "setMeshUpdatedFlag__testImpl";

    /** Method name of {@code CArtMeshSource/CArtMeshForm.setPositions}. */
    public static final String SET_POSITIONS_METHOD = "setPositions";

    /** Single float-array setter JVM descriptor. */
    public static final String SET_POSITIONS_DESCRIPTOR = "([F)V";

    /** Method name of {@code ACDrawableForm.setDrawOrder}. */
    public static final String SET_DRAW_ORDER_METHOD = "setDrawOrder";

    /** Method name of {@code ACParameterControllableSource.setVisible}. */
    public static final String SET_VISIBLE_METHOD = "setVisible";

    /** Single-boolean setter JVM descriptor. */
    public static final String SET_BOOLEAN_DESCRIPTOR = "(Z)V";

    /** Method name of {@code ACParameterControllableSource.internal_setVisibilityAndLockInHierarchy$cubism}. */
    public static final String INTERNAL_SET_VIS_LOCK_METHOD = "internal_setVisibilityAndLockInHierarchy$cubism";

    /** Two-boolean visibility/lock JVM descriptor. */
    public static final String INTERNAL_SET_VIS_LOCK_DESCRIPTOR = "(ZZ)V";

    /** Method name of {@code ACParameterControllableSource.setParentGuid}. */
    public static final String SET_PARENT_GUID_METHOD = "setParentGuid";

    /** Single {@code CPartGuid} setter JVM descriptor. */
    public static final String SET_PARENT_GUID_DESCRIPTOR = "(Lcom/live2d/type/CPartGuid;)V";

    /** Method name of {@code ACParameterControllableSource.internal_setParent}. */
    public static final String INTERNAL_SET_PARENT_METHOD = "internal_setParent";

    /** Single {@code CPartSource} setter JVM descriptor. */
    public static final String INTERNAL_SET_PARENT_DESCRIPTOR = "(Lcom/live2d/cubism/doc/model/parts/CPartSource;)V";

    /** Method name of {@code GMeshContainer.setMesh}. */
    public static final String SET_MESH_METHOD = "setMesh";

    /** Single base-mesh setter JVM descriptor. */
    public static final String SET_MESH_DESCRIPTOR = "(L" + MESH_BASE_OWNER + ";)V";

    /** Host internal name for Cubism 5.3+ Bezier drag helper {@code extension/a/d}. */
    public static final String EXTENSION_BEZIER_EVAL_OWNER = "com/live2d/cubism/doc/model/extension/a/d";

    /** Host internal name for Cubism 5.3+ Bezier surface math {@code extension/a/f}. */
    public static final String EXTENSION_BEZIER_MATH_OWNER = "com/live2d/cubism/doc/model/extension/a/f";

    /** Method name of {@code extension.a.d} control-point drag batch evaluator and {@code extension.a.f} solvers. */
    public static final String EXTENSION_METHOD_A = "a";

    /** Method descriptor of {@code extension.a.d.a(d, i, i, int, int, float[])}. */
    public static final String EXTENSION_BEZIER_DRAG_EVAL_DESCRIPTOR = "(Lcom/live2d/cubism/doc/model/extension/a/d;"
            + "Lcom/live2d/cubism/doc/model/extension/a/i;"
            + "Lcom/live2d/cubism/doc/model/extension/a/i;"
            + "II[F)Ljava/util/List;";

    /** Method descriptor of {@code extension.a.f.a(c, c, c, c, float, float)}. */
    public static final String EXTENSION_COONS_EVAL_DESCRIPTOR = "(Lcom/live2d/cubism/doc/model/extension/a/c;"
            + "Lcom/live2d/cubism/doc/model/extension/a/c;"
            + "Lcom/live2d/cubism/doc/model/extension/a/c;"
            + "Lcom/live2d/cubism/doc/model/extension/a/c;"
            + "FF)Lcom/live2d/graphics3d/type/GVector2;";

    /** Method descriptor of {@code extension.a.f.a(c, c, c, c, GVector2, float, float)}. */
    public static final String EXTENSION_COONS_INVERT_DESCRIPTOR = "(Lcom/live2d/cubism/doc/model/extension/a/c;"
            + "Lcom/live2d/cubism/doc/model/extension/a/c;"
            + "Lcom/live2d/cubism/doc/model/extension/a/c;"
            + "Lcom/live2d/cubism/doc/model/extension/a/c;"
            + "Lcom/live2d/graphics3d/type/GVector2;"
            + "FF)Lcom/live2d/cubism/doc/model/extension/a/j;";

    /** Method descriptor of {@code extension.a.f.a(float[], int, int, float, float)}. */
    public static final String EXTENSION_BICUBIC_EVAL_DESCRIPTOR = "([FIIFF)Lcom/live2d/graphics3d/type/GVector2;";

    /** Method descriptor of {@code extension.a.f.a(float[], int, int)}. */
    public static final String EXTENSION_SPLINE_LINES_DESCRIPTOR = "([FII)Ljava/util/List;";

    /** Method name of {@code ACForm.setUpdated}. */
    public static final String SET_UPDATED_METHOD = "setUpdated";

    /** JVM descriptor of {@code ACForm.setUpdated}. */
    public static final String SET_UPDATED_DESCRIPTOR = "()V";

    /** JVM descriptor of {@code ACForm/Keyform reinit(ICopyable, a)}. */
    public static final String KEYFORM_REINIT_DESCRIPTOR = "(Lcom/live2d/core/ICopyable;Lcom/live2d/core/a;)V";

    /** Host internal name for {@code KeyformBindingSource}. */
    public static final String KEYFORM_BINDING_SOURCE_OWNER =
            "com/live2d/cubism/doc/model/interpolator/KeyformBindingSource";

    /** Host internal name for {@code KeyformGridSource}. */
    public static final String KEYFORM_GRID_SOURCE_OWNER = "com/live2d/cubism/doc/model/interpolator/KeyformGridSource";

    /** Host internal name for {@code ParameterKeySearchType}. */
    public static final String PARAMETER_KEY_SEARCH_TYPE_OWNER =
            "com/live2d/cubism/doc/model/interpolator/ParameterKeySearchType";

    /** Host internal name for {@code KeyformOnGrid}. */
    public static final String KEYFORM_ON_GRID_OWNER = "com/live2d/cubism/doc/model/interpolator/KeyformOnGrid";

    /** Method name of {@code KeyformBindingSource.getKeyIndicesInRange}. */
    public static final String GET_KEY_INDICES_IN_RANGE_METHOD = "getKeyIndicesInRange";

    /** JVM descriptor of {@code KeyformBindingSource.getKeyIndicesInRange}. */
    public static final String GET_KEY_INDICES_IN_RANGE_DESCRIPTOR = "()Ljava/util/List;";

    /** Method name of {@code KeyformBindingSource.setKeys}. */
    public static final String SET_KEYS_METHOD = "setKeys";

    /** JVM descriptor of {@code KeyformBindingSource.setKeys}. */
    public static final String SET_KEYS_DESCRIPTOR = "(Ljava/util/List;)V";

    /** Method name of {@code KeyformGridSource.findKeyform}. */
    public static final String FIND_KEYFORM_METHOD = "findKeyform";

    /** JVM descriptor of {@code KeyformGridSource.findKeyform(CParameterSet)}. */
    public static final String FIND_KEYFORM_DEFAULT_DESCRIPTOR =
            "(Lcom/live2d/cubism/doc/model/param/CParameterSet;)L" + KEYFORM_ON_GRID_OWNER + ";";

    /** JVM descriptor of {@code KeyformGridSource.findKeyform(CParameterSet, ParameterKeySearchType)}. */
    public static final String FIND_KEYFORM_SEARCH_DESCRIPTOR = "(Lcom/live2d/cubism/doc/model/param/CParameterSet;L"
            + PARAMETER_KEY_SEARCH_TYPE_OWNER
            + ";)L"
            + KEYFORM_ON_GRID_OWNER
            + ";";

    /** Exact mutator method names and descriptors on {@code KeyformGridSource}. */
    public static final Map<String, String> KEYFORM_GRID_MUTATOR_DESCRIPTORS = Map.ofEntries(
            Map.entry(REINIT_METHOD, KEYFORM_REINIT_DESCRIPTOR),
            Map.entry("reverseKeyformsOnParameter", "(Lcom/live2d/type/CParameterGuid;)V"),
            Map.entry(
                    "rearrangeKeyformsOnParameter",
                    "(Lcom/live2d/type/CParameterGuid;Ljava/util/List;Ljava/util/List;)V"),
            Map.entry(
                    "changeKeyformsOnParameter", "(Lcom/live2d/type/CParameterGuid;Lcom/live2d/type/CParameterGuid;)V"),
            Map.entry(
                    "addKey_internal",
                    "(FLcom/live2d/type/CParameterGuid;Lcom/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension;)V"),
            Map.entry("removeKey", "(FLcom/live2d/type/CParameterGuid;)V"),
            Map.entry(
                    "importCubism21",
                    "(Lcom/live2d/cubism/doc/model/CModelSource;Ljava/util/List;Ljava/util/List;Lcom/live2d/cubism/doc/model/io/b/c;)V"),
            Map.entry("import", "(Lcom/live2d/cubism/doc/model/CModelSource;Ljava/util/List;Ljava/util/List;)V"),
            Map.entry("addKeyformBinding", "(L" + KEYFORM_BINDING_SOURCE_OWNER + ";)V"),
            Map.entry("deserialize", "(Lorg/jdom/Element;Lcom/live2d/serialize/XmlReader;)V"));

    /** Reviewed overlay method name on {@code J} in 5.0.00–5.0.05 builds. */
    public static final String LEGACY_OVERLAY_METHOD_V500 = "E";

    /** Reviewed overlay method name on {@code J} in 5.0.06 builds. */
    public static final String LEGACY_OVERLAY_METHOD_V506 = "F";

    /** Reviewed overlay descriptor on {@code J} in 5.0.x builds. */
    public static final String LEGACY_OVERLAY_DESCRIPTOR = "()V";

    /** Reviewed overlay method name on {@code K$b} in 5.1+ builds. */
    public static final String OVERLAY_HELPER_METHOD = "a";

    /** Reviewed overlay descriptor on {@code K$b} in 5.1+ builds. */
    public static final String OVERLAY_HELPER_DESCRIPTOR = "(Lcom/live2d/cubism/doc/model/deformer/ACDeformer;"
            + "Lcom/live2d/graphics3d/sceneGraph/GSceneGraph;"
            + "ZZ"
            + "Lcom/live2d/cubism/setting/AppSetting$RotationDeformer$Mode;"
            + "Lcom/live2d/cubism/view/context/actionManager/aG;"
            + "Lkotlin/jvm/functions/Function1;)V";

    /** Reviewed {@code updateModel} method name on {@code aj..ay} updater classes. */
    public static final String UPDATE_MODEL_METHOD = "a";

    private static final String VERSION_5_0_PREFIX = "5.0.";

    private static final Map<String, List<String>> REVIEWED_TABLES = TargetClassTable.load(TARGET_TABLE);

    public WarpDeformEditTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
        targetClasses = List.copyOf(Objects.requireNonNull(targetClasses, "targetClasses"));
    }

    /** Returns the reviewed {@code updater} internal class name for this artifact version. */
    public String updaterOwner() {
        return modelUpdateRow().updater();
    }

    /** Returns the reviewed {@code updateCore} JVM descriptor on {@code updater} (disjoint from {@code ModelUpdateSkip}). */
    public String coreUpdateDescriptor() {
        final ModelUpdateTargetTable.Row row = modelUpdateRow();
        return "(Lcom/live2d/cubism/doc/model/CModel;Ljava/util/List;L"
                + row.updateContext()
                + ";Lcom/live2d/cubism/view/context/CEViewContext;L"
                + row.contextParam()
                + ";)V";
    }

    /** Returns the reviewed {@code updateArtMeshAppearance} JVM descriptor on {@code updater}. */
    public String artMeshAppearanceDescriptor() {
        final ModelUpdateTargetTable.Row row = modelUpdateRow();
        return "(Lcom/live2d/cubism/doc/model/drawable/artMesh/CArtMesh;Lcom/live2d/cubism/doc/model/CModel;L"
                + row.updateContext()
                + ";Lcom/live2d/cubism/doc/modeling/CModelingDocument;)V";
    }

    /** Returns the reviewed overlay loop owner internal name for this artifact version. */
    public String overlayOwner() {
        return version.startsWith(VERSION_5_0_PREFIX) ? LEGACY_OVERLAY_VIEW_OWNER : OVERLAY_HELPER_OWNER;
    }

    /** Returns the reviewed overlay loop method descriptor for this artifact version. */
    public String overlayMethodDescriptor() {
        return version.startsWith(VERSION_5_0_PREFIX) ? LEGACY_OVERLAY_DESCRIPTOR : OVERLAY_HELPER_DESCRIPTOR;
    }

    private ModelUpdateTargetTable.Row modelUpdateRow() {
        ModelUpdateTargetTable.Row versionMatch = null;
        for (final ModelUpdateTargetTable.Row row : ModelUpdateTargetTable.all()) {
            if (row.digest().equals(digest)) {
                return row;
            }
            if (row.version().equals(version)) {
                versionMatch = row;
            }
        }
        if (versionMatch != null) {
            return versionMatch;
        }
        throw new IllegalStateException("no model-update row for version " + version);
    }

    /**
     * Resolves a reviewed target descriptor from an official Cubism artifact digest.
     *
     * @param digest host JAR artifact digest
     * @return resolved target metadata, or empty when the artifact is unreviewed
     */
    public static Optional<WarpDeformEditTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .filter(REVIEWED_TABLES::containsKey)
                .map(version -> new WarpDeformEditTarget(digest, version, REVIEWED_TABLES.get(version)));
    }

    /**
     * Returns every reviewed artifact target descriptor in canonical order.
     *
     * @return immutable list of reviewed targets
     */
    public static List<WarpDeformEditTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }
}
