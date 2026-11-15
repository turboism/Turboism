package dev.turboism.adapter.cubism.optimization.hittest;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Defines reviewed host classes, methods, and descriptors for allocation-free mesh hit testing.
 *
 * <p>{@code com.live2d.graphics3d.mesh.a.contains(GVector2, int[])} calls {@code getBounds()},
 * which allocates one {@code GBounds} and two {@code GVector3} instances per call even when the
 * point lies outside the mesh bounding box. Admitted hosts evaluate the 2D bounds check directly
 * over the vertex array without heap allocations.</p>
 */
public record MeshHitTestTarget(HostArtifactDigest digest, String version) {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.meshHitTest";

    /** Reviewed Cubism versions sharing the mesh hit-test bytecode shape. */
    public static final Set<String> SUPPORTED_VERSIONS = ReviewedVersions.since("5.0.00");

    /** Internal name of the abstract mesh base class. */
    public static final String OWNER = "com/live2d/graphics3d/mesh/a";

    /** Internal name of the 2D mesh subclass. */
    public static final String GMESH2_OWNER = "com/live2d/graphics3d/mesh/GMesh2";

    /** Internal name of the 3D mesh subclass. */
    public static final String GMESH3_OWNER = "com/live2d/graphics3d/mesh/GMesh3";

    /** Internal name of {@code GBounds}. */
    public static final String BOUNDS_OWNER = "com/live2d/graphics3d/type/GBounds";

    /** Internal name of {@code GVector2}. */
    public static final String VECTOR2_OWNER = "com/live2d/graphics3d/type/GVector2";

    /** Internal name of Kotlin runtime parameter-check helper. */
    public static final String KOTLIN_INTRINSICS_OWNER = "kotlin/jvm/internal/Intrinsics";

    /** Kotlin parameter non-null assertion method name. */
    public static final String CHECK_NOT_NULL_PARAMETER_METHOD = "checkNotNullParameter";

    /** Descriptor of {@code Intrinsics.checkNotNullParameter(Object, String)}. */
    public static final String CHECK_NOT_NULL_PARAMETER_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/String;)V";

    /** Hit-test entry method name on {@code com.live2d.graphics3d.mesh.a}. */
    public static final String CONTAINS_METHOD = "contains";

    /** Exact polygon hit-test method name on {@code com.live2d.graphics3d.mesh.a}. */
    public static final String CONTAINS_IN_MESH_METHOD = "containsInMesh";

    /** Descriptor of {@code contains(GVector2, int[])} and {@code containsInMesh(GVector2, int[])}. */
    public static final String CONTAINS_DESCRIPTOR = "(Lcom/live2d/graphics3d/type/GVector2;[I)Z";

    /** Vertex positions accessor method name on {@code com.live2d.graphics3d.mesh.a}. */
    public static final String GET_POSITIONS_METHOD = "getPositions";

    /** Descriptor of {@code getPositions()}. */
    public static final String GET_POSITIONS_DESCRIPTOR = "()[F";

    /** Bounding box accessor method name on {@code com.live2d.graphics3d.mesh.a}. */
    public static final String GET_BOUNDS_METHOD = "getBounds";

    /** Descriptor of {@code getBounds()}. */
    public static final String GET_BOUNDS_DESCRIPTOR = "()Lcom/live2d/graphics3d/type/GBounds;";

    /** Default-argument bounds containment helper method name on {@code GBounds}. */
    public static final String BOUNDS_CONTAINS_DEFAULT_METHOD = "a";

    /** Descriptor of {@code GBounds.a(GBounds, GVector2, float, int, Object)}. */
    public static final String BOUNDS_CONTAINS_DEFAULT_DESCRIPTOR =
            "(Lcom/live2d/graphics3d/type/GBounds;Lcom/live2d/graphics3d/type/GVector2;FILjava/lang/Object;)Z";

    /** Bounds containment method name on {@code GBounds}. */
    public static final String BOUNDS_CONTAINS_METHOD = "a";

    /** Descriptor of {@code GBounds.a(GVector2, float)}. */
    public static final String BOUNDS_CONTAINS_DESCRIPTOR = "(Lcom/live2d/graphics3d/type/GVector2;F)Z";

    /** Constructor name for {@code GBounds}. */
    public static final String INIT_METHOD = "<init>";

    /** Descriptor of {@code GBounds(float[], int)}. */
    public static final String BOUNDS_ARRAY_INIT_DESCRIPTOR = "([FI)V";

    /** X-coordinate accessor name on {@code GVector2}. */
    public static final String GET_X_METHOD = "getX";

    /** Y-coordinate accessor name on {@code GVector2}. */
    public static final String GET_Y_METHOD = "getY";

    /** Zero-arg float getter descriptor. */
    public static final String FLOAT_GETTER_DESCRIPTOR = "()F";

    /** Bounds tolerance helper package prefix across reviewed Cubism builds. */
    public static final String UTIL_PACKAGE_PREFIX = "com/live2d/util/";

    /** Bounds tolerance accessor method name on the utility singleton. */
    public static final String TOLERANCE_GETTER_METHOD = "f";

    /** Class initializer method name. */
    public static final String CLINIT_METHOD = "<clinit>";

    /** Class initializer descriptor. */
    public static final String VOID_DESCRIPTOR = "()V";

    /** Reviewed default bounds tolerance returned by {@code K.f()} / {@code L.f()}. */
    public static final float REVIEWED_BOUNDS_MARGIN = 1.0E-6f;

    public MeshHitTestTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    /** Resolves the reviewed target for an admitted host artifact digest. */
    public static Optional<MeshHitTestTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .filter(SUPPORTED_VERSIONS::contains)
                .map(version -> new MeshHitTestTarget(digest, version));
    }

    /** Returns every reviewed target in version order. */
    public static List<MeshHitTestTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }

    /** Returns host methods verified before arming the weave. */
    public List<MethodDep> methodDependencies() {
        return List.of(
                new MethodDep(OWNER, CONTAINS_METHOD, CONTAINS_DESCRIPTOR),
                new MethodDep(OWNER, CONTAINS_IN_MESH_METHOD, CONTAINS_DESCRIPTOR),
                new MethodDep(OWNER, GET_POSITIONS_METHOD, GET_POSITIONS_DESCRIPTOR),
                new MethodDep(OWNER, GET_BOUNDS_METHOD, GET_BOUNDS_DESCRIPTOR),
                new MethodDep(GMESH2_OWNER, GET_BOUNDS_METHOD, GET_BOUNDS_DESCRIPTOR),
                new MethodDep(GMESH3_OWNER, GET_BOUNDS_METHOD, GET_BOUNDS_DESCRIPTOR),
                new MethodDep(VECTOR2_OWNER, GET_X_METHOD, FLOAT_GETTER_DESCRIPTOR),
                new MethodDep(VECTOR2_OWNER, GET_Y_METHOD, FLOAT_GETTER_DESCRIPTOR),
                new MethodDep(BOUNDS_OWNER, BOUNDS_CONTAINS_METHOD, BOUNDS_CONTAINS_DESCRIPTOR));
    }

    /** Identifies one reviewed method dependency verified before retransformation. */
    public record MethodDep(String owner, String name, String descriptor) {
        public MethodDep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
