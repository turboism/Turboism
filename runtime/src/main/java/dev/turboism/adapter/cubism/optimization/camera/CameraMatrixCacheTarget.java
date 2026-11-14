package dev.turboism.adapter.cubism.optimization.camera;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines the reviewed host classes and descriptors for root-camera world-to-camera matrix caching.
 *
 * <p>{@code GCamera.getWorldToCameraMatrix()} recomputes the transform's world matrix and allocates
 * an inverted {@code GMatrix44} on every shader draw call even when camera position, Euler angles,
 * and scale remain unchanged across the frame.</p>
 */
public record CameraMatrixCacheTarget(HostArtifactDigest digest, String version) {

    /** Camera owner containing the world-to-camera matrix computation. */
    public static final String CAMERA_OWNER = "com/live2d/graphics3d/GCamera";

    /** World-to-camera matrix accessor on {@code GCamera}. */
    public static final String WORLD_TO_CAMERA_METHOD = "getWorldToCameraMatrix";

    /** Descriptor of {@code GCamera.getWorldToCameraMatrix()}. */
    public static final String WORLD_TO_CAMERA_DESCRIPTOR =
            "()Lcom/live2d/graphics3d/type/GMatrix44;";

    /** Transform accessor on {@code GCamera}. */
    public static final String GET_TRANSFORM_METHOD = "getTransform";

    /** Descriptor of {@code GCamera.getTransform()}. */
    public static final String GET_TRANSFORM_DESCRIPTOR =
            "()Lcom/live2d/graphics3d/component/GTransform;";

    /** Transform component owner. */
    public static final String TRANSFORM_OWNER = "com/live2d/graphics3d/component/GTransform";

    /** Entity accessor on {@code GTransform}. */
    public static final String GET_ENTITY_METHOD = "getEntity";

    /** Descriptor of {@code GTransform.getEntity()}. */
    public static final String GET_ENTITY_DESCRIPTOR =
            "()Lcom/live2d/graphics3d/entity/GEntity;";

    /** Position vector accessor on {@code GTransform}. */
    public static final String GET_POSITION_METHOD = "getPosition";

    /** Euler-angle vector accessor on {@code GTransform}. */
    public static final String GET_EULER_ANGLES_METHOD = "getEulerAngles";

    /** Scale vector accessor on {@code GTransform}. */
    public static final String GET_SCALE_METHOD = "getScale";

    /** Descriptor returning {@code GVector3}. */
    public static final String VECTOR3_GETTER_DESCRIPTOR =
            "()Lcom/live2d/graphics3d/type/GVector3;";

    /** Entity owner. */
    public static final String ENTITY_OWNER = "com/live2d/graphics3d/entity/GEntity";

    /** Parent-entity accessor on {@code GEntity}. */
    public static final String GET_PARENT_ENTITY_METHOD = "getParentEntity";

    /** Descriptor of {@code GEntity.getParentEntity()}. */
    public static final String GET_PARENT_ENTITY_DESCRIPTOR =
            "()Lcom/live2d/graphics3d/entity/GEntity;";

    /** 3D vector owner. */
    public static final String VECTOR3_OWNER = "com/live2d/graphics3d/type/GVector3";

    /** X component accessor on {@code GVector3}. */
    public static final String GET_X_METHOD = "getX";

    /** Y component accessor on {@code GVector3}. */
    public static final String GET_Y_METHOD = "getY";

    /** Z component accessor on {@code GVector3}. */
    public static final String GET_Z_METHOD = "getZ";

    /** Float getter descriptor. */
    public static final String FLOAT_GETTER_DESCRIPTOR = "()F";

    /** 4x4 matrix owner. */
    public static final String MATRIX_OWNER = "com/live2d/graphics3d/type/GMatrix44";

    /** Optional static local-to-parent override field on {@code GTransform}. */
    public static final String STATIC_MATRIX_FIELD = "internalStaticMatrixLocalToParent";

    /** Field descriptor of {@link #STATIC_MATRIX_FIELD}. */
    public static final String MATRIX_FIELD_DESCRIPTOR =
            "Lcom/live2d/graphics3d/type/GMatrix44;";

    public CameraMatrixCacheTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    /** Resolves the reviewed target for an admitted host artifact digest. */
    public static Optional<CameraMatrixCacheTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .map(version -> new CameraMatrixCacheTarget(digest, version));
    }

    /** Returns every reviewed target in version order. */
    public static List<CameraMatrixCacheTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }

    /** Returns all host methods read by {@link CameraMatrixCacheBridge}. */
    public List<Dep> dependencies() {
        return List.of(
                new Dep(CAMERA_OWNER, GET_TRANSFORM_METHOD, GET_TRANSFORM_DESCRIPTOR),
                new Dep(TRANSFORM_OWNER, GET_ENTITY_METHOD, GET_ENTITY_DESCRIPTOR),
                new Dep(TRANSFORM_OWNER, GET_POSITION_METHOD, VECTOR3_GETTER_DESCRIPTOR),
                new Dep(TRANSFORM_OWNER, GET_EULER_ANGLES_METHOD, VECTOR3_GETTER_DESCRIPTOR),
                new Dep(TRANSFORM_OWNER, GET_SCALE_METHOD, VECTOR3_GETTER_DESCRIPTOR),
                new Dep(ENTITY_OWNER, GET_PARENT_ENTITY_METHOD, GET_PARENT_ENTITY_DESCRIPTOR),
                new Dep(ENTITY_OWNER, GET_TRANSFORM_METHOD, GET_TRANSFORM_DESCRIPTOR),
                new Dep(VECTOR3_OWNER, GET_X_METHOD, FLOAT_GETTER_DESCRIPTOR),
                new Dep(VECTOR3_OWNER, GET_Y_METHOD, FLOAT_GETTER_DESCRIPTOR),
                new Dep(VECTOR3_OWNER, GET_Z_METHOD, FLOAT_GETTER_DESCRIPTOR));
    }

    /** Identifies one reviewed dependency method verified before arming the weave. */
    public record Dep(String owner, String name, String descriptor) {
        public Dep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
