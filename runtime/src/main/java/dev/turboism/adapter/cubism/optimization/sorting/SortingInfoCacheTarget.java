package dev.turboism.adapter.cubism.optimization.sorting;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reviewed host metadata for the skipped-frame sorting-info reuse and the mesh-container
 * lookup rewrite.
 *
 * <p>The sorting cache lives in {@code com.live2d.graphics3d.component.a.b}; the method
 * {@code a(GCamera)} unconditionally recalculates the layer/order bits, the sorting Z
 * value and the relative draw order. During a skipped model-update frame the camera is
 * {@code null} and the model renderers cannot have changed, so the three cached values
 * are already current. The transformer injects a fail-closed guard that reuses them.</p>
 *
 * <p>{@code com.live2d.graphics3d.component.GMeshRenderer.getMeshContainer()} scans the
 * entity's {@code Components} with an {@link java.util.Iterator} on every call. Its
 * backing {@code com.live2d.graphics3d.entity.Components.a()} is a {@link java.util.List},
 * so the transformer replaces the scan with an indexed loop that allocates nothing.</p>
 *
 * <p>Targets are admitted only through exact reviewed artifact digests. The actual
 * method shapes are compared against the reference bytes by each transformer, so an
 * artifact with a different method shape fails closed even if it is otherwise reviewed.</p>
 */
public record SortingInfoCacheTarget(HostArtifactDigest digest, String version) {

    /** Reviewed sorting-info cache owner: {@code com.live2d.graphics3d.component.a.b}. */
    public static final String SORTING_OWNER = "com/live2d/graphics3d/component/a/b";
    /** Reviewed sorting-info refresh method name. */
    public static final String SORTING_METHOD = "a";
    /** {@code a(GCamera) void}. */
    public static final String SORTING_DESCRIPTOR = "(Lcom/live2d/graphics3d/GCamera;)V";

    /** Reviewed mesh renderer owner. */
    public static final String MESH_RENDERER_OWNER = "com/live2d/graphics3d/component/GMeshRenderer";
    /** Reviewed mesh-container lookup method name. */
    public static final String MESH_CONTAINER_METHOD = "getMeshContainer";
    /** {@code getMeshContainer() GMeshContainer}. */
    public static final String MESH_CONTAINER_DESCRIPTOR = "()Lcom/live2d/graphics3d/component/GMeshContainer;";

    /** Entity owner exposing the component collection. */
    public static final String ENTITY_OWNER = "com/live2d/graphics3d/entity/GEntity";
    /** Component collection owner. */
    public static final String COMPONENTS_OWNER = "com/live2d/graphics3d/entity/Components";
    /** {@code Components.a() List} — the indexed backing list used by the rewrite. */
    public static final String COMPONENTS_LIST_METHOD = "a";
    /** {@code a() List}. */
    public static final String COMPONENTS_LIST_DESCRIPTOR = "()Ljava/util/List;";
    /** Mesh container component owner. */
    public static final String MESH_CONTAINER_OWNER = "com/live2d/graphics3d/component/GMeshContainer";

    public SortingInfoCacheTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    /** The reviewed target for a host artifact digest, or empty when unsupported. */
    public static Optional<SortingInfoCacheTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .map(version -> new SortingInfoCacheTarget(digest, version));
    }

    /** Every reviewed target, oldest supported version first. */
    public static List<SortingInfoCacheTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }

    /**
     * Host members the mesh-container rewrite depends on. Installers can verify these
     * against the admitted artifact before arming the transformer.
     */
    public List<Dep> dependencies() {
        return List.of(
                new Dep(ENTITY_OWNER, "getComponents", "()Lcom/live2d/graphics3d/entity/Components;"),
                new Dep(COMPONENTS_OWNER, COMPONENTS_LIST_METHOD, COMPONENTS_LIST_DESCRIPTOR));
    }

    /** A reviewed dependency method the rewrite relies on. */
    public record Dep(String owner, String name, String descriptor) {
        public Dep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
