package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpSchemas.entry;
import static dev.turboism.plugin.mcp.McpSchemas.linked;

import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.RawTexture;
import java.util.Map;

/**
 * Wire projection of the texture-library metadata that both the diagnostics resources and the
 * texture domain publish.
 *
 * <p>The two surfaces describe the same native entries, so one projection keeps their field sets
 * and their field order identical. Field order is the wire order.</p>
 */
final class McpTextureProjection {

    private McpTextureProjection() {}

    /** Wire form of one raw texture: identity plus pixel dimensions. */
    static Map<String, Object> rawTexture(final RawTexture value) {
        return linked(
                entry("id", value.id().value()),
                entry("name", value.name()),
                entry("width", value.width()),
                entry("height", value.height()));
    }

    /** Wire form of one model image: identity plus pixel dimensions. */
    static Map<String, Object> modelImage(final ModelImageEntry value) {
        return linked(
                entry("id", value.id().value()),
                entry("name", value.name()),
                entry("width", value.width()),
                entry("height", value.height()));
    }

    /** Wire form of one texture atlas: identity, dimensions, and atlas membership counts. */
    static Map<String, Object> atlasTexture(final AtlasTexture value) {
        return linked(
                entry("id", value.id().value()),
                entry("name", value.name()),
                entry("width", value.width()),
                entry("height", value.height()),
                entry("atlasVersion", value.atlasVersion()),
                entry("modelImageCount", value.modelImageCount()));
    }
}
