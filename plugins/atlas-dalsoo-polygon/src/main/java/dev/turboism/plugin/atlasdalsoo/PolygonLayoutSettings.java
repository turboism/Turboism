package dev.turboism.plugin.atlasdalsoo;

import java.util.Map;
import java.util.Objects;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;

/** Confirmed polygon-layout settings persisted by this plugin. */
public record PolygonLayoutSettings(
    TextureAtlasLayoutBackend backend,
    TextureAtlasRotationMode rotation,
    TextureAtlasLayoutQuality quality,
    boolean automaticScale,
    double fixedScale,
    boolean useAbey,
    boolean parallel,
    Map<String, TextureAtlasItemLayoutPolicy> itemPolicies
) {

    public PolygonLayoutSettings {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(quality, "quality");
        if (!(fixedScale > 0) || !Double.isFinite(fixedScale)) {
            throw new IllegalArgumentException("fixedScale must be positive");
        }
        itemPolicies = itemPolicies == null ? Map.of() : Map.copyOf(itemPolicies);
    }

    public static PolygonLayoutSettings defaults() {
        return new PolygonLayoutSettings(
            TextureAtlasLayoutBackend.AUTO,
            TextureAtlasRotationMode.QUARTER,
            TextureAtlasLayoutQuality.BALANCED,
            true, 1.0, true, false, Map.of());
    }

    public TextureAtlasItemLayoutPolicy policyFor(final String modelId, final String textureId) {
        return itemPolicies.getOrDefault(modelId + "/" + textureId,
            TextureAtlasItemLayoutPolicy.participating(textureId));
    }
}
