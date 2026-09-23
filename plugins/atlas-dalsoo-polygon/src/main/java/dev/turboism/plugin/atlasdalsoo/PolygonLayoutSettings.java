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
    PolygonLayoutLockPreset lockPreset,
    double autoScaleTolerance,
    int autoScaleMaxTry,
    Map<String, TextureAtlasItemLayoutPolicy> itemPolicies
) {

    /** Default automatic-scale tolerance, matching the planner's built-in value. */
    public static final double DEFAULT_AUTO_SCALE_TOLERANCE = 0.005;
    /** Sentinel: derive the attempt bound from the quality preset. */
    public static final int AUTO_SCALE_MAX_TRY_QUALITY = 0;

    public PolygonLayoutSettings {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(lockPreset, "lockPreset");
        if (!(fixedScale > 0) || !Double.isFinite(fixedScale)) {
            throw new IllegalArgumentException("fixedScale must be positive");
        }
        if (!(autoScaleTolerance > 0) || !Double.isFinite(autoScaleTolerance)) {
            throw new IllegalArgumentException("autoScaleTolerance must be positive");
        }
        if (autoScaleMaxTry < 0) {
            throw new IllegalArgumentException(
                "autoScaleMaxTry must be >= 0 (0 = quality preset)");
        }
        itemPolicies = itemPolicies == null ? Map.of() : Map.copyOf(itemPolicies);
    }

    /** Confirmed defaults preserving the pre-dialog behavior. */
    public static PolygonLayoutSettings defaults() {
        return new PolygonLayoutSettings(
            TextureAtlasLayoutBackend.AUTO,
            TextureAtlasRotationMode.QUARTER,
            TextureAtlasLayoutQuality.BALANCED,
            true, 1.0, true, false,
            PolygonLayoutLockPreset.NONE,
            DEFAULT_AUTO_SCALE_TOLERANCE, AUTO_SCALE_MAX_TRY_QUALITY,
            Map.of());
    }

    /**
     * Effective per-item policy: an explicit {@code itemPolicies} entry wins;
     * otherwise the global lock preset supplies the default layer.
     */
    public TextureAtlasItemLayoutPolicy policyFor(final String modelId, final String textureId) {
        return itemPolicies.getOrDefault(modelId + "/" + textureId,
            lockPreset.toPolicy(textureId));
    }
}
