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

    /** Internal name of overlay loop host in Cubism 5.0.x builds. */
    public static final String LEGACY_OVERLAY_VIEW_OWNER = "com/live2d/cubism/view/context/J";

    /** Internal name of overlay loop helper in Cubism 5.1+ builds. */
    public static final String OVERLAY_HELPER_OWNER = "com/live2d/cubism/view/context/K$b";

    /** Reviewed overlay method name on {@code J} in 5.0.00–5.0.05 builds. */
    public static final String LEGACY_OVERLAY_METHOD_V500 = "E";

    /** Reviewed overlay method name on {@code J} in 5.0.06 builds. */
    public static final String LEGACY_OVERLAY_METHOD_V506 = "F";

    /** Reviewed overlay descriptor on {@code J} in 5.0.x builds. */
    public static final String LEGACY_OVERLAY_DESCRIPTOR = "()V";

    /** Reviewed overlay method name on {@code K$b} in 5.1+ builds. */
    public static final String OVERLAY_HELPER_METHOD = "a";

    /** Reviewed overlay descriptor on {@code K$b} in 5.1+ builds. */
    public static final String OVERLAY_HELPER_DESCRIPTOR =
            "(Lcom/live2d/cubism/doc/model/deformer/ACDeformer;"
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
