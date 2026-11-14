package dev.turboism.adapter.cubism.optimization.warpgrid;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Defines reviewed host classes and descriptors for warp grid point interpolation.
 *
 * <p>{@code com.live2d.cubism.doc.model.deformer.warp.o.a} transforms deformer points across a
 * 2D control grid. Isolating cold out-of-bounds extrapolation from the in-bounds loop reduces
 * register pressure and hoists cell-stride invariants while keeping IEEE-754 bit parity.</p>
 */
public record WarpGridInterpolationTarget(HostArtifactDigest digest, String version) {

    /** Internal name of the warp grid helper owner. */
    public static final String OWNER = "com/live2d/cubism/doc/model/deformer/warp/o";

    /** Hot point-interpolation method name on {@code o}. */
    public static final String METHOD = "a";

    /** JVM descriptor of {@code o.a(float[], float[], int, int, int, float[], int, int, boolean)}. */
    public static final String DESCRIPTOR = "([F[FIII[FIIZ)V";

    /** Static singleton field name on {@code o}. */
    public static final String SINGLETON_FIELD = "a";

    /** Internal name of {@code CWarpDeformer}. */
    public static final String WARP_DEFORMER_OWNER = "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformer";

    /** Internal name of {@code CWarpDeformerForm}. */
    public static final String WARP_FORM_OWNER = "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformerForm";

    /** Internal name of {@code CWarpDeformerSource}. */
    public static final String WARP_SOURCE_OWNER = "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformerSource";

    /** Deformer form accessor name. */
    public static final String GET_CALCULATED_FORM_METHOD = "getCalculatedForm";

    /** Deformer form accessor descriptor. */
    public static final String GET_CALCULATED_FORM_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/model/deformer/warp/CWarpDeformerForm;";

    /** Deformer source accessor name. */
    public static final String GET_SOURCE_METHOD = "getSource";

    /** Deformer source accessor descriptor. */
    public static final String GET_SOURCE_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/model/deformer/warp/CWarpDeformerSource;";

    /** Grid position array accessor name on {@code CWarpDeformerForm}. */
    public static final String GET_POSITIONS_METHOD = "getPositions";

    /** Grid position array accessor descriptor on {@code CWarpDeformerForm}. */
    public static final String GET_POSITIONS_DESCRIPTOR = "()[F";

    /** Grid column count accessor name on {@code CWarpDeformerSource}. */
    public static final String GET_COL_METHOD = "getCol";

    /** Grid column count accessor descriptor on {@code CWarpDeformerSource}. */
    public static final String GET_COL_DESCRIPTOR = "()I";

    /** Grid row count accessor name on {@code CWarpDeformerSource}. */
    public static final String GET_ROW_METHOD = "getRow";

    /** Grid row count accessor descriptor on {@code CWarpDeformerSource}. */
    public static final String GET_ROW_DESCRIPTOR = "()I";

    /** Quad-interpolation mode predicate name on {@code CWarpDeformerSource}. */
    public static final String IS_QUAD_TRANSFORM_METHOD = "isQuadTransform";

    /** Quad-interpolation mode predicate descriptor on {@code CWarpDeformerSource}. */
    public static final String IS_QUAD_TRANSFORM_DESCRIPTOR = "()Z";

    /** Reviewed Cubism versions sharing the warp grid interpolation bytecode shape. */
    public static final Set<String> SUPPORTED_VERSIONS = ReviewedVersions.since("5.0.00");

    public WarpGridInterpolationTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    /** Resolves a reviewed target from an admitted host artifact digest. */
    public static Optional<WarpGridInterpolationTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .filter(SUPPORTED_VERSIONS::contains)
                .map(version -> new WarpGridInterpolationTarget(digest, version));
    }

    /** Returns every reviewed target in artifact order. */
    public static List<WarpGridInterpolationTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }

    /** Returns host caller and model dependencies verified before arming the weave. */
    public List<Dep> dependencies() {
        return List.of(
                new Dep(WARP_DEFORMER_OWNER, GET_CALCULATED_FORM_METHOD, GET_CALCULATED_FORM_DESCRIPTOR),
                new Dep(WARP_DEFORMER_OWNER, GET_SOURCE_METHOD, GET_SOURCE_DESCRIPTOR),
                new Dep(WARP_FORM_OWNER, GET_POSITIONS_METHOD, GET_POSITIONS_DESCRIPTOR),
                new Dep(WARP_SOURCE_OWNER, GET_COL_METHOD, GET_COL_DESCRIPTOR),
                new Dep(WARP_SOURCE_OWNER, GET_ROW_METHOD, GET_ROW_DESCRIPTOR),
                new Dep(WARP_SOURCE_OWNER, IS_QUAD_TRANSFORM_METHOD, IS_QUAD_TRANSFORM_DESCRIPTOR));
    }

    /** Identifies one reviewed host dependency verified prior to retransformation. */
    public record Dep(String owner, String name, String descriptor) {
        public Dep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
