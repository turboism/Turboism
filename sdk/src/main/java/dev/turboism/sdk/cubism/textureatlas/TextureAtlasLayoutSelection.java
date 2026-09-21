package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

/**
 * The runtime-owned texture-atlas automatic-layout selection: which registered
 * algorithm the native automatic-layout entry routes to, and whether parallel
 * search is requested.
 *
 * <p>A {@code null} or blank {@code algorithmId} designates the runtime native
 * default: the host's own packing runs and no plugin planner is invoked. A
 * non-null id is resolved against {@link TextureAtlasLayoutAlgorithmRegistry}
 * at invocation time; an id that is not currently registered falls back to the
 * native algorithm rather than failing. Selecting an algorithm never registers
 * it, and registering an algorithm never selects it.</p>
 *
 * <p>{@code parallel} is a request forwarded to
 * {@link TextureAtlasLayoutPlanner#plan(java.util.List, TextureAtlasLayoutConstraints, boolean)};
 * algorithms that do not declare {@code supportsParallel} ignore it.</p>
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record TextureAtlasLayoutSelection(String algorithmId, boolean parallel) {

    public TextureAtlasLayoutSelection {
        algorithmId = algorithmId == null || algorithmId.isBlank() ? null : algorithmId.strip();
    }

    /** @return the native default selection: no algorithm id, serial packing. */
    public static TextureAtlasLayoutSelection nativeDefault() {
        return new TextureAtlasLayoutSelection(null, false);
    }

    /** @return whether this selection designates the host's native packing. */
    public boolean isNative() {
        return algorithmId == null;
    }
}
