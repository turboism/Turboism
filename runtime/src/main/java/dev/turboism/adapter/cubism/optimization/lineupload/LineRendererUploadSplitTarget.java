package dev.turboism.adapter.cubism.optimization.lineupload;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reviewed host metadata for the line-renderer dirty-buffer split.
 *
 * <p>{@code GLineRenderer.updateMeshData()} unconditionally marks positions, colors and
 * indices dirty. Deformation updates only change positions, so the colors and indices
 * uploads are redundant. The transformer rewrites the method entry with a guarded
 * consult that can replace those three marks with content-aware marks.</p>
 */
public record LineRendererUploadSplitTarget(HostArtifactDigest digest, String version) {

    /** Reviewed renderer owner. */
    public static final String OWNER = "com/live2d/graphics3d/component/GLineRenderer";
    /** Reviewed method name. */
    public static final String METHOD = "updateMeshData";
    /** {@code updateMeshData() void}. */
    public static final String DESCRIPTOR = "()V";
    /** Float buffer wrapper returned by positions/colors getters. */
    public static final String FLOAT_BUFFER_OWNER = "com/live2d/graphics3d/mesh/a/b";
    /** Integer buffer wrapper returned by the indices getter. */
    public static final String INT_BUFFER_OWNER = "com/live2d/graphics3d/mesh/a/c";
    /** Base buffer wrapper owning the dirty marker {@code l()}. */
    public static final String BUFFER_BASE_OWNER = "com/live2d/graphics3d/mesh/a/a";
    /** Host dirty-marker method. */
    public static final String DIRTY_METHOD = "l";
    /** {@code l() void}. */
    public static final String DIRTY_DESCRIPTOR = "()V";

    /** Public renderer getters the bridge resolves once. */
    public static final List<Dep> DEPENDENCIES = List.of(
            new Dep(OWNER, "getPositionsBuffer", "()L" + FLOAT_BUFFER_OWNER + ";"),
            new Dep(OWNER, "getColorsBuffer", "()L" + FLOAT_BUFFER_OWNER + ";"),
            new Dep(OWNER, "getIndicesBuffer", "()L" + INT_BUFFER_OWNER + ";"),
            new Dep(OWNER, "getColors", "()[F"),
            new Dep(OWNER, "getIndices", "()[I"),
            new Dep(BUFFER_BASE_OWNER, DIRTY_METHOD, DIRTY_DESCRIPTOR));

    public LineRendererUploadSplitTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    /** The reviewed target for a host artifact digest, or empty when unsupported. */
    public static Optional<LineRendererUploadSplitTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .map(version -> new LineRendererUploadSplitTarget(digest, version));
    }

    /** Every reviewed target, oldest supported version first. */
    public static List<LineRendererUploadSplitTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
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
