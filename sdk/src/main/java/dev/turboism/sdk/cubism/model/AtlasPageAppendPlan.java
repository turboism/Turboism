package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ModelImageId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Plan for appending one or more new pages with model-image placements to an existing
 * texture atlas library without mutating existing pages.
 *
 * @param pageWidth the pixel width of each appended page (1 to 8192)
 * @param pageHeight the pixel height of each appended page (1 to 8192)
 * @param pageCount the number of new pages to append (1 to 32)
 * @param pageNames optional display names for the appended pages; must be empty or have exactly
 *                  {@code pageCount} unique non-blank names
 * @param placements the non-empty list of non-overlapping model-image placements across the appended pages
 */
@CubismEditor(from = "5.0.00")
public record AtlasPageAppendPlan(
        int pageWidth,
        int pageHeight,
        int pageCount,
        List<String> pageNames,
        List<ModelImagePlacement> placements) {

    public static final int MAX_PAGE_COUNT = 32;
    public static final int MAX_PAGE_DIMENSION = 8192;
    public static final long MAX_TOTAL_PIXELS = 536_870_912L; // 32 * 4096 * 4096

    public AtlasPageAppendPlan {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException(
                    "page dimensions must be positive: width=" + pageWidth + ", height=" + pageHeight);
        }
        if (pageWidth > MAX_PAGE_DIMENSION || pageHeight > MAX_PAGE_DIMENSION) {
            throw new IllegalArgumentException(
                    "page dimensions exceed maximum allowed (" + MAX_PAGE_DIMENSION + "): width="
                            + pageWidth + ", height=" + pageHeight);
        }
        if (pageCount <= 0 || pageCount > MAX_PAGE_COUNT) {
            throw new IllegalArgumentException(
                    "pageCount must be between 1 and " + MAX_PAGE_COUNT + ", got: " + pageCount);
        }
        final long totalPixels = (long) pageWidth * (long) pageHeight * (long) pageCount;
        if (totalPixels > MAX_TOTAL_PIXELS) {
            throw new IllegalArgumentException(
                    "total atlas pixel budget exceeded: " + totalPixels + " > " + MAX_TOTAL_PIXELS);
        }

        pageNames = pageNames == null ? List.of() : List.copyOf(pageNames);
        if (!pageNames.isEmpty()) {
            if (pageNames.size() != pageCount) {
                throw new IllegalArgumentException(
                        "pageNames size (" + pageNames.size() + ") must match pageCount (" + pageCount + ")");
            }
            final var seenNames = new HashSet<String>();
            for (int i = 0; i < pageNames.size(); i++) {
                final String name = pageNames.get(i);
                if (name == null || name.strip().isEmpty()) {
                    throw new IllegalArgumentException("pageNames entry at index " + i + " must not be blank");
                }
                if (!seenNames.add(name.strip())) {
                    throw new IllegalArgumentException("duplicate page name in pageNames: " + name.strip());
                }
            }
        }

        placements = placements == null ? List.of() : List.copyOf(placements);
        if (placements.isEmpty()) {
            throw new IllegalArgumentException("placements must not be empty");
        }

        final var seenImages = new HashSet<ModelImageId>();
        final List<List<ModelImagePlacement>> pagePlacements = new ArrayList<>(pageCount);
        for (int i = 0; i < pageCount; i++) {
            pagePlacements.add(new ArrayList<>());
        }

        for (final ModelImagePlacement placement : placements) {
            Objects.requireNonNull(placement, "placement");
            if (placement.pageIndex() >= pageCount) {
                throw new IllegalArgumentException(
                        "placement pageIndex " + placement.pageIndex() + " exceeds pageCount " + pageCount);
            }
            final long right = (long) placement.x() + (long) placement.width();
            final long bottom = (long) placement.y() + (long) placement.height();
            if (right > (long) pageWidth || bottom > (long) pageHeight) {
                throw new IllegalArgumentException(
                        "placement for image " + placement.modelImageId().value() + " exceeds page dimensions ("
                                + pageWidth + "x" + pageHeight + "): at ("
                                + placement.x() + "," + placement.y() + ") size "
                                + placement.width() + "x" + placement.height());
            }
            if (!seenImages.add(placement.modelImageId())) {
                throw new IllegalArgumentException(
                        "duplicate placement for model image: " + placement.modelImageId().value());
            }

            // Check overlap with existing placements on the same page
            final List<ModelImagePlacement> onPage = pagePlacements.get(placement.pageIndex());
            for (final ModelImagePlacement existing : onPage) {
                final boolean overlapX = (long) placement.x() < (long) existing.x() + (long) existing.width()
                        && (long) placement.x() + (long) placement.width() > (long) existing.x();
                final boolean overlapY = (long) placement.y() < (long) existing.y() + (long) existing.height()
                        && (long) placement.y() + (long) placement.height() > (long) existing.y();
                if (overlapX && overlapY) {
                    throw new IllegalArgumentException(
                            "placements overlap on page " + placement.pageIndex() + ": "
                                    + placement.modelImageId().value() + " and " + existing.modelImageId().value());
                }
            }
            onPage.add(placement);
        }
    }

    public AtlasPageAppendPlan(
            final int pageWidth,
            final int pageHeight,
            final int pageCount,
            final List<ModelImagePlacement> placements) {
        this(pageWidth, pageHeight, pageCount, List.of(), placements);
    }
}
