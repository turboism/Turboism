package dev.turboism.sdk.cubism.mesh.automesh;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything one auto-mesh run may read: the target snapshot, its texture input, the authored
 * keyform count and the resolved parameter values.
 *
 * <p>The runtime builds the request on the Editor UI thread and hands it to an {@link
 * AutoMeshCompute} running off that thread, so the snapshot is immutable and the compute never
 * touches the live model.</p>
 *
 * <p>{@link #keyformCount()} is the number of keyforms the target ArtMesh carries. A value above
 * one means the target has per-keyform shapes: applying a single-keyform result would collapse
 * them, so the runtime refuses such a run unless a later milestone implements keyform remapping.</p>
 *
 * <p>{@link #parameterValues()} holds canonical text for every parameter the run resolved; a
 * parameter absent from the map keeps the default declared by its descriptor.</p>
 */
@CubismEditor
public record AutoMeshRequest(
        String targetId,
        ArtMeshGeometry geometry,
        Optional<TexturePixels> texture,
        int keyformCount,
        Map<String, String> parameterValues,
        int vertexTarget) {

    /** Value of {@link #vertexTarget()} when the caller states no vertex budget. */
    public static final int UNSPECIFIED_VERTEX_TARGET = 0;

    public AutoMeshRequest {
        targetId = AutoMeshContracts.requireHostId(targetId, "targetId");
        geometry = Objects.requireNonNull(geometry, "geometry");
        texture = Objects.requireNonNull(texture, "texture");
        if (keyformCount < 1) {
            throw new IllegalArgumentException("keyformCount must be at least 1");
        }
        Objects.requireNonNull(parameterValues, "parameterValues");
        final LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        parameterValues.forEach((parameterId, value) -> copied.put(
                AutoMeshContracts.requireId(parameterId, "parameterValues key"),
                AutoMeshContracts.requireValue(value, "parameterValues[" + parameterId + "]")));
        parameterValues = Collections.unmodifiableMap(copied);
        if (vertexTarget < UNSPECIFIED_VERTEX_TARGET) {
            throw new IllegalArgumentException("vertexTarget must not be negative");
        }
    }

    /** Reports whether the algorithm runs without a user-supplied vertex budget. */
    public boolean hasVertexTarget() {
        return vertexTarget != UNSPECIFIED_VERTEX_TARGET;
    }
}
