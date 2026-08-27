package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Complete immutable planning input for the active texture atlas. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record TextureAtlasLayoutSnapshot(
    TextureAtlasLayoutTarget target,
    String documentId,
    String modelId,
    String atlasId,
    TextureAtlasLayoutConstraints constraints,
    List<TextureAtlasLayoutItem> items,
    TextureAtlasLayoutPlan currentPlan
) {
    public TextureAtlasLayoutSnapshot {
        target = Objects.requireNonNull(target, "target");
        documentId = requireText(documentId, "documentId");
        modelId = requireText(modelId, "modelId");
        atlasId = requireText(atlasId, "atlasId");
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
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
