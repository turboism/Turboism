package dev.turboism.sdk.cubism.textureatlas;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable polygon packing result.
 *
 * <p>{@code placements} covers every item the planner committed inside the page;
 * items the planner could not fit are listed in {@code overflowTextureIds} and keep
 * their issued transform. {@code scale} is the uniform scale the plan was produced
 * at (equal to {@code requestedScale} for fixed-scale runs). {@code diagnostics}
 * carries planner-emitted key/value facts such as the chosen backend, attempt
 * counts, or conservative outline handling.</p>
 */
public record TextureAtlasPolygonPlan(
    int pageWidth,
    int pageHeight,
    double scale,
    List<TextureAtlasPolygonPlacement> placements,
    List<String> overflowTextureIds,
    TextureAtlasLayoutBackend backend,
    Map<String, String> diagnostics
) {

    public TextureAtlasPolygonPlan {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException("pageWidth/pageHeight must be positive");
        }
        if (!(scale > 0) || !Double.isFinite(scale)) {
            throw new IllegalArgumentException("scale must be positive");
        }
        placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
        overflowTextureIds = List.copyOf(Objects.requireNonNull(overflowTextureIds, "overflowTextureIds"));
        Objects.requireNonNull(backend, "backend");
        diagnostics = Map.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public Optional<TextureAtlasPolygonPlacement> placementFor(final String textureId) {
        Objects.requireNonNull(textureId, "textureId");
        return placements.stream().filter(p -> p.textureId().equals(textureId)).findFirst();
    }
}
