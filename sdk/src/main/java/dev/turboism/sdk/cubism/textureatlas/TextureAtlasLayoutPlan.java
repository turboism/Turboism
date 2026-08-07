package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.PreviewApi;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Complete, immutable host-independent atlas layout plan. */
@PreviewApi
public record TextureAtlasLayoutPlan(
    int pageWidth,
    int pageHeight,
    int pageCount,
    List<TextureAtlasPlacement> placements
) {

    public TextureAtlasLayoutPlan {
        if (pageWidth < 1 || pageHeight < 1) {
            throw new IllegalArgumentException("Atlas page dimensions must be positive.");
        }
        if (pageCount < 1) {
            throw new IllegalArgumentException("Atlas page count must be positive.");
        }
        placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
        final Set<String> textureIds = new HashSet<>();
        for (int index = 0; index < placements.size(); index++) {
            final TextureAtlasPlacement placement = Objects.requireNonNull(
                placements.get(index),
                "placement"
            );
            if (!textureIds.add(placement.textureId())) {
                throw new IllegalArgumentException(
                    "Atlas layout plan contains duplicate texture ID: " + placement.textureId()
                );
            }
            if (placement.pageIndex() >= pageCount) {
                throw new IllegalArgumentException(
                    "Placement page index is outside the atlas plan: " + placement.textureId()
                );
            }
            if ((long) placement.x() + placement.width() > pageWidth
                || (long) placement.y() + placement.height() > pageHeight) {
                throw new IllegalArgumentException(
                    "Placement is outside the atlas page: " + placement.textureId()
                );
            }
            for (int previous = 0; previous < index; previous++) {
                final TextureAtlasPlacement other = placements.get(previous);
                if (overlaps(placement, other)) {
                    throw new IllegalArgumentException(
                        "Atlas placements overlap: " + other.textureId() + " and " + placement.textureId()
                    );
                }
            }
        }
    }

    private static boolean overlaps(
        final TextureAtlasPlacement left,
        final TextureAtlasPlacement right
    ) {
        return left.pageIndex() == right.pageIndex()
            && left.x() < right.x() + right.width()
            && left.x() + left.width() > right.x()
            && left.y() < right.y() + right.height()
            && left.y() + left.height() > right.y();
    }
}
