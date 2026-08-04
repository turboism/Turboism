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
        if (resolver == null) return Optional.empty();
        final Object viewValue = view.get();
        if (viewValue == null) return Optional.empty();
        final Object dataModel = resolver.invoke(
            VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_VIEW_DATA_MODEL, viewValue
        );
        if (dataModel == null) return Optional.empty();
        final Object pageState = resolver.invoke(
            VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_DATA_MODEL_CURRENT_PAGE, dataModel
        );
        if (pageState == null) return Optional.empty();
        final Object atlas = resolver.invoke(
            VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_PAGE_STATE_ATLAS, pageState
        );
        if (atlas == null) return Optional.empty();
        final List<?> entries = listOrEmpty(resolver.invoke(
            "cubism.texture-atlas.atlas.entries", atlas
        ));
        return Optional.of(new TextureAtlasSummary(
            entries.size(),
            1,
            entrySizeDistribution(entries)
        ));
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
