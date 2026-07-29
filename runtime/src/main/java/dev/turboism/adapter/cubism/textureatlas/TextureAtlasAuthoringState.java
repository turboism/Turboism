package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Runtime/provider boundary state; no raw host object crosses it. */
public record TextureAtlasAuthoringState(
    String documentId,
    String modelId,
    String atlasId,
    long revision,
    TextureAtlasLayoutConstraints constraints,
    List<TextureAtlasLayoutItem> items,
    TextureAtlasLayoutPlan currentPlan
) {
    public TextureAtlasAuthoringState {
        documentId = requireText(documentId, "documentId");
        modelId = requireText(modelId, "modelId");
        atlasId = requireText(atlasId, "atlasId");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        constraints = Objects.requireNonNull(constraints, "constraints");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        currentPlan = Objects.requireNonNull(currentPlan, "currentPlan");
        final Set<String> ids = new HashSet<>();
        for (TextureAtlasLayoutItem item : items) {
            final TextureAtlasLayoutItem value = Objects.requireNonNull(item, "item");
            if (!ids.add(value.textureId())) {
                throw new IllegalArgumentException("Duplicate texture atlas item ID: " + value.textureId());
            }
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        final String normalized = value.strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
