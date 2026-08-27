package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Complete, immutable host-independent atlas layout plan. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record TextureAtlasLayoutPlan(
    int pageWidth,
    int pageHeight,
    int pageCount,
    List<String> pageNames,
    List<TextureAtlasPlacement> placements
) {

    public TextureAtlasLayoutPlan(
        final int pageWidth,
        final int pageHeight,
        final int pageCount,
        final List<TextureAtlasPlacement> placements
    ) {
        this(pageWidth, pageHeight, pageCount, defaultPageNames(pageCount), placements);
    }

    public TextureAtlasLayoutPlan {
        if (pageWidth < 1 || pageHeight < 1) {
            throw new IllegalArgumentException("Atlas page dimensions must be positive.");
        }
        if (pageCount < 1) {
            throw new IllegalArgumentException("Atlas page count must be positive.");
        }
        pageNames = List.copyOf(Objects.requireNonNull(pageNames, "pageNames"));
        if (!pageNames.isEmpty() && pageNames.size() != pageCount) {
            throw new IllegalArgumentException(
                "Atlas page names must be empty or match the page count."
            );
        }
        final Set<String> names = new HashSet<>();
        final java.util.ArrayList<String> normalizedNames = new java.util.ArrayList<>(
            pageNames.size()
        );
        for (String name : pageNames) {
            final String normalized = Objects.requireNonNull(name, "page name").strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Atlas page names must not be blank.");
            }
            if (!names.add(normalized)) {
                throw new IllegalArgumentException("Atlas page names must be unique: " + normalized);
            }
            normalizedNames.add(normalized);
        }
        pageNames = List.copyOf(normalizedNames);
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

    private static List<String> defaultPageNames(final int pageCount) {
        return java.util.stream.IntStream.range(0, pageCount)
            .mapToObj(index -> "Turboism Atlas " + (index + 1))
            .toList();
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
