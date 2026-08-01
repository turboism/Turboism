package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasSizeBucket;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Read-only framework capability over the active native texture-atlas editor view.
 * The view reference is supplied by {@link RuntimeTextureAtlasEditorUi} (updated by
 * the exact-host ingress); all reads go through the verified resolver.
 */
public final class RuntimeTextureAtlasEditorSession implements TextureAtlasEditorSession {

    private final VerifiedMemberResolver resolver;
    private final Supplier<Object> view;

    public RuntimeTextureAtlasEditorSession(
        final VerifiedMemberResolver resolver,
        final Supplier<Object> view
    ) {
        // resolver may be null for the unavailable (unattached) session; every read
        // guards on it and reports empty
        this.resolver = resolver;
        this.view = Objects.requireNonNull(view, "view");
    }

    public static RuntimeTextureAtlasEditorSession unavailable() {
        return new RuntimeTextureAtlasEditorSession(null, () -> null);
    }

    @Override
    public Optional<TextureAtlasSummary> summary() {
        if (resolver == null) return Optional.empty();
        return wholeAtlasSummary();
    }

    @Override
    public Optional<TextureAtlasSummary> selectedTexture() {
        return selectedTextureSummary();
    }

    private Optional<Object> textureManager() {
        final Object dataModel = resolver.invoke(
            VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_VIEW_DATA_MODEL, view.get()
        );
        if (dataModel == null) return Optional.empty();
        final Object modelSource = resolver.invoke(
            "cubism.texture-atlas.data-model.model-source", dataModel
        );
        if (modelSource == null) return Optional.empty();
        final Object textureManager = resolver.invoke(
            "cubism.texture-atlas.model-source.texture-manager", modelSource
        );
        return Optional.ofNullable(textureManager);
    }

    private Optional<TextureAtlasSummary> wholeAtlasSummary() {
        return textureManager().map(manager -> {
            final List<?> images = listOrEmpty(resolver.invoke(
                "cubism.texture-atlas.texture-manager.images", manager
            ));
            final List<?> atlases = listOrEmpty(resolver.invoke(
                "cubism.texture-atlas.texture-manager.atlases", manager
            ));
            return new TextureAtlasSummary(
                images.size(),
                atlases.size(),
                sizeDistribution(images)
            );
        });
    }

    private Optional<TextureAtlasSummary> selectedTextureSummary() {
        final Object selectedImage = selectedImage();
        if (selectedImage == null) return Optional.empty();
        return textureManager().map(manager -> {
            final List<?> atlases = listOrEmpty(resolver.invoke(
                "cubism.texture-atlas.texture-manager.atlases", manager
            ));
            for (Object atlas : atlases) {
                final List<?> entries = listOrEmpty(resolver.invoke(
                    "cubism.texture-atlas.atlas.entries", atlas
                ));
                if (atlasContains(entries, selectedImage)) {
                    return new TextureAtlasSummary(
                        entries.size(),
                        1,
                        entrySizeDistribution(entries)
                    );
                }
            }
            return null;
        });
    }

    private Object selectedImage() {
        if (resolver == null) return null;
        try {
            final Object viewValue = view.get();
            final Object imageList = viewValue == null ? null : resolver.invoke(
                VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_VIEW_IMAGE_LIST, viewValue
            );
            if (imageList == null) {
                System.err.println("TURBOISM_STATS_PROBE view-image-list null (view=" + viewValue + ")");
                return null;
            }
            final Object list = resolver.invoke(
                VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_IMAGE_LIST_ITEMS, imageList
            );
            if (list == null) {
                System.err.println("TURBOISM_STATS_PROBE image-list-items null");
                return null;
            }
            final Object item = resolver.invoke(
                VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_LIST_SELECTED, list
            );
            if (item == null) {
                System.err.println("TURBOISM_STATS_PROBE list-selected null");
                return null;
            }
            final Object image = resolver.invoke(
                VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_IMAGE_ENTRY_IMAGE, item
            );
            if (image == null) {
                System.err.println("TURBOISM_STATS_PROBE entry-image null");
            }
            return image;
        } catch (RuntimeException failure) {
            System.err.println("TURBOISM_STATS_PROBE failure: " + failure);
            return null;
        }
    }

    private boolean atlasContains(final List<?> entries, final Object image) {
        for (Object entry : entries) {
            if (resolver.invoke("cubism.texture-atlas.entry.image", entry) == image) {
                return true;
            }
        }
        return false;
    }

    private List<TextureAtlasSizeBucket> entrySizeDistribution(final List<?> entries) {
        final List<Object> images = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            final Object image = resolver.invoke("cubism.texture-atlas.entry.image", entry);
            if (image != null) images.add(image);
        }
        return sizeDistribution(images);
    }

    private List<TextureAtlasSizeBucket> sizeDistribution(final List<?> images) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object image : images) {
            final int width = intValue(resolver.invoke("cubism.texture-atlas.image.width", image));
            final int height = intValue(resolver.invoke("cubism.texture-atlas.image.height", image));
            if (width < 1 || height < 1) continue;
            counts.merge(width + "x" + height, 1, Integer::sum);
        }
        final List<TextureAtlasSizeBucket> buckets = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            final String[] parts = entry.getKey().split("x");
            buckets.add(new TextureAtlasSizeBucket(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                entry.getValue()
            ));
        }
        return List.copyOf(buckets);
    }

    private static List<?> listOrEmpty(final Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static int intValue(final Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
