package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;

/**
 * A registered layout planner that consumes real outlines in native current-page
 * invocations. The runtime supplies the issued snapshot and owns validation and
 * writeback under the algorithm registration's lifetime. The inherited rectangle
 * entry remains available for explicit rectangle-only SDK callers.
 * This native polygon dispatch extension is incubating, matching the polygon
 * plugin's preview status; consumers must pin a compatible framework version.
 */
@CubismEditor
@Incubating
public interface TextureAtlasPolygonLayoutPlanner extends TextureAtlasLayoutPlanner {
    /** Computes a plan without writing host state; the runtime owns its application. */
    TextureAtlasPolygonPlan planPolygon(TextureAtlasPolygonLayoutSnapshot snapshot, boolean parallel);
}
