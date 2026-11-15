package dev.turboism.adapter.cubism.optimization.warpdraw;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Defines the reviewed host classes, fields, and descriptors for eliding disabled
 * outside-of-parent warp control-point buffer construction.
 *
 * <p>{@code WarpDeformer_Draw.drawNormalPoint} disables {@code eWarpGridNormalPointOutSideOfParent}
 * when developer outside-of-parent highlighting is off, yet continues allocating an
 * {@link java.util.ArrayList}, boxing coordinates via {@link Float#valueOf(float)}, converting the
 * list to a float array, and uploading geometry onto the disabled point entity. Admitted hosts
 * from {@code 5.0.06} onward short-circuit after clearing {@code selectOutSideOfParentPoint}
 * whenever {@code eWarpGridNormalPointOutSideOfParent} remains disabled.</p>
 */
public record WarpNormalPointElisionTarget(HostArtifactDigest digest, String version) {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.warpNormalPointElision";

    /** Reviewed Cubism versions carrying {@code selectOutSideOfParentPoint}. */
    public static final Set<String> SUPPORTED_VERSIONS = ReviewedVersions.since("5.0.06");

    /** Warp deformer overlay entity owner. */
    public static final String OWNER = "com/live2d/cubism/doc/model/deformer/warp/WarpDeformer_Draw";

    /** Normal control-point update method on {@code WarpDeformer_Draw}. */
    public static final String DRAW_NORMAL_POINT_METHOD = "drawNormalPoint";

    /** Descriptor of {@code WarpDeformer_Draw.drawNormalPoint(boolean, boolean, float[], CColor)}. */
    public static final String DRAW_NORMAL_POINT_DESCRIPTOR = "(ZZ[FLcom/live2d/type/CColor;)V";

    /** Developer setting owner queried before enabling outside-of-parent points. */
    public static final String DEVELOPER_SETTING_OWNER = "com/live2d/cubism/setting/AppSetting$DeveloperSetting";

    /** Developer flag accessor gating outside-of-parent point visibility. */
    public static final String HIGHLIGHT_OUTSIDE_METHOD = "getHighLightOutsideOfParentDeformer";

    /** Boolean getter descriptor. */
    public static final String BOOLEAN_GETTER_DESCRIPTOR = "()Z";

    /** Point entity owner used for warp grid point overlays. */
    public static final String SIMPLE_POINTS_OWNER = "com/live2d/graphics3d/geometry/GSimplePointsEntity";

    /** Descriptor of {@code GSimplePointsEntity}. */
    public static final String SIMPLE_POINTS_DESCRIPTOR = "Lcom/live2d/graphics3d/geometry/GSimplePointsEntity;";

    /** Visibility getter on {@code GSimplePointsEntity}. */
    public static final String GET_ENABLED_METHOD = "getEnabled";

    /** Visibility setter on {@code GSimplePointsEntity}. */
    public static final String SET_ENABLED_METHOD = "setEnabled";

    /** Descriptor of {@code setEnabled(boolean)}. */
    public static final String SET_ENABLED_DESCRIPTOR = "(Z)V";

    /** Field storing the outside-of-parent normal point entity on {@code WarpDeformer_Draw}. */
    public static final String OUTSIDE_NORMAL_POINT_FIELD = "eWarpGridNormalPointOutSideOfParent";

    /** Field storing the outside-of-parent selection point entity on {@code WarpDeformer_Draw}. */
    public static final String OUTSIDE_SELECT_POINT_FIELD = "selectOutSideOfParentPoint";

    public WarpNormalPointElisionTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    /** Resolves the reviewed target for an admitted host artifact digest from 5.0.06 onward. */
    public static Optional<WarpNormalPointElisionTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .filter(SUPPORTED_VERSIONS::contains)
                .map(version -> new WarpNormalPointElisionTarget(digest, version));
    }

    /** Returns every supported reviewed target in version order. */
    public static List<WarpNormalPointElisionTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }

    /** Returns the public methods verified on {@code GSimplePointsEntity} before arming the weave. */
    public List<MethodDep> methodDependencies() {
        return List.of(
                new MethodDep(SIMPLE_POINTS_OWNER, GET_ENABLED_METHOD, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(SIMPLE_POINTS_OWNER, SET_ENABLED_METHOD, SET_ENABLED_DESCRIPTOR));
    }

    /** Returns the declared fields verified on {@code WarpDeformer_Draw} before arming the weave. */
    public List<FieldDep> fieldDependencies() {
        return List.of(
                new FieldDep(OWNER, OUTSIDE_NORMAL_POINT_FIELD, SIMPLE_POINTS_DESCRIPTOR),
                new FieldDep(OWNER, OUTSIDE_SELECT_POINT_FIELD, SIMPLE_POINTS_DESCRIPTOR));
    }

    /** Identifies one reviewed dependency method verified before arming the weave. */
    public record MethodDep(String owner, String name, String descriptor) {
        public MethodDep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Identifies one reviewed declared field verified before arming the weave. */
    public record FieldDep(String owner, String name, String descriptor) {
        public FieldDep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
