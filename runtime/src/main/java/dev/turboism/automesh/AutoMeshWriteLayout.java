package dev.turboism.automesh;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.util.Objects;
import java.util.Optional;

/**
 * One plugin layout together with the texture it was planned on.
 *
 * <p>The geometry carries the layout in the ArtMesh's own uv and position spaces. The texture
 * states which raster the plugin planned on and publishes the uv-to-pixel affine, so the write can
 * recover the layout pixels the host generator works with. An empty texture means the plugin
 * planned from geometry alone, and the write then scales uv by the host image extent.</p>
 *
 * @param geometry the layout the algorithm returned
 * @param texture the texture the algorithm received, or empty for a geometry-only run
 */
public record AutoMeshWriteLayout(ArtMeshGeometry geometry, Optional<TexturePixels> texture) {

    public AutoMeshWriteLayout {
        geometry = Objects.requireNonNull(geometry, "geometry");
        texture = Objects.requireNonNull(texture, "texture");
    }

    /** @return the layout of a run that received no texture pixels */
    public static AutoMeshWriteLayout of(final ArtMeshGeometry geometry) {
        return new AutoMeshWriteLayout(geometry, Optional.empty());
    }
}
