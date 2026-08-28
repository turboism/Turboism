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
 * Every read uses one generation-bound resolver/view snapshot supplied by
 * {@link RuntimeTextureAtlasEditorUi}; it never combines independently-read host state.
 */
public final class RuntimeTextureAtlasEditorSession implements TextureAtlasEditorSession {

    private final Supplier<GenerationBinding> binding;

    /**
     * Creates a session over an atomically captured host generation. The supplier must return
     * {@code null} while no matching resolver and editor view are live.
     */
    public RuntimeTextureAtlasEditorSession(final Supplier<GenerationBinding> binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    /**
     * Returns a permanently detached session for hosts where the texture-atlas editor could not
     * be verified. It holds no resolver and no view, so every read reports empty rather than
     * failing; it never becomes available later.
     *
     * @return an unattached session whose reads always yield {@link java.util.Optional#empty()}
     */
    public static RuntimeTextureAtlasEditorSession unavailable() {
        return new RuntimeTextureAtlasEditorSession(() -> null);
    }

    @Override
    public Optional<TextureAtlasSummary> summary() {
        final GenerationBinding current = binding.get();
        if (current == null) return Optional.empty();
        return wholeAtlasSummary(current.resolver(), current.view());
    }

    @Override
    public Optional<TextureAtlasSummary> selectedTexture() {
        final GenerationBinding current = binding.get();
        if (current == null) return Optional.empty();
        return selectedTextureSummary(current.resolver(), current.view());
    }

    private Optional<Object> textureManager(
        final VerifiedMemberResolver selected,
        final Object viewValue
    ) {
        final Object dataModel = selected.invoke(
            VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_VIEW_DATA_MODEL, viewValue
        );
        if (dataModel == null) return Optional.empty();
        final Object modelSource = selected.invoke(
            "cubism.texture-atlas.data-model.model-source", dataModel
        );
        if (modelSource == null) return Optional.empty();
        final Object textureManager = selected.invoke(
            "cubism.texture-atlas.model-source.texture-manager", modelSource
        );
        return Optional.ofNullable(textureManager);
    }

    private Optional<TextureAtlasSummary> wholeAtlasSummary(
        final VerifiedMemberResolver selected,
        final Object viewValue
    ) {
        return textureManager(selected, viewValue).map(manager -> {
            final List<?> images = listOrEmpty(selected.invoke(
                "cubism.texture-atlas.texture-manager.images", manager
            ));
            final List<?> atlases = listOrEmpty(selected.invoke(
                "cubism.texture-atlas.texture-manager.atlases", manager
            ));
            return new TextureAtlasSummary(
                images.size(),
                atlases.size(),
                sizeDistribution(selected, images)
            );
        });
    }

    private Optional<TextureAtlasSummary> selectedTextureSummary(
        final VerifiedMemberResolver selected,
        final Object viewValue
    ) {
        final Object dataModel = selected.invoke(
            VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_VIEW_DATA_MODEL, viewValue
        );
        if (dataModel == null) return Optional.empty();
        final Object pageState = selected.invoke(
            VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_DATA_MODEL_CURRENT_PAGE, dataModel
        );
        if (pageState == null) return Optional.empty();
        final Object atlas = selected.invoke(
            VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_PAGE_STATE_ATLAS, pageState
        );
        if (atlas == null) return Optional.empty();
        final List<?> entries = listOrEmpty(selected.invoke(
            "cubism.texture-atlas.atlas.entries", atlas
        ));
        return Optional.of(new TextureAtlasSummary(
            entries.size(),
            1,
            entrySizeDistribution(selected, entries)
        ));
    }

    private List<TextureAtlasSizeBucket> entrySizeDistribution(
        final VerifiedMemberResolver selected,
        final List<?> entries
    ) {
        final List<Object> images = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            final Object image = selected.invoke("cubism.texture-atlas.entry.image", entry);
            if (image != null) images.add(image);
        }
        return sizeDistribution(selected, images);
    }

    private List<TextureAtlasSizeBucket> sizeDistribution(
        final VerifiedMemberResolver selected,
        final List<?> images
    ) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object image : images) {
            final int width = intValue(selected.invoke("cubism.texture-atlas.image.width", image));
            final int height = intValue(selected.invoke("cubism.texture-atlas.image.height", image));
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

    /** Immutable resolver/view pair for one verified host generation. */
    public record GenerationBinding(long generation, VerifiedMemberResolver resolver, Object view) {
        public GenerationBinding {
            if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
            resolver = Objects.requireNonNull(resolver, "resolver");
            view = Objects.requireNonNull(view, "view");
        }
    }
}
