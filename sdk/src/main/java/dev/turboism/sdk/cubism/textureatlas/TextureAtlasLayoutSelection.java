package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

/**
 * The runtime-owned texture-atlas automatic-layout selection: which registered
 * algorithm the native automatic-layout entry routes to, and whether parallel
 * search is requested.
 *
 * <p>A {@code null} or blank {@code algorithmId} is the unset/native default:
 * no choice was ever made and the host's own packing runs. {@link
 * #NATIVE_ALGORITHM_ID} is the <em>explicit</em> native choice recorded when a
 * user picks the native entry or a caller selects {@link #nativeDefault()} —
 * it resolves to native packing exactly like the unset state but stays
 * distinguishable from it, so a stored explicit choice is never mistaken for
 * "no selection yet". The {@code "native"} id is reserved: it can never be
 * registered by a plugin, so the explicit native choice can never collide with
 * a real algorithm. Any other non-null id is resolved against {@link
 * TextureAtlasLayoutAlgorithmRegistry} at invocation time; an id that is not
 * currently registered falls back to the native algorithm rather than failing.
 * Selecting an algorithm never registers it, and registering an algorithm
 * never selects it.</p>
 *
 * <p>{@code parallel} is a request forwarded to
 * {@link TextureAtlasLayoutPlanner#plan(java.util.List, TextureAtlasLayoutConstraints, boolean)};
 * algorithms that do not declare {@code supportsParallel} ignore it.</p>
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record TextureAtlasLayoutSelection(String algorithmId, boolean parallel) {

    /**
     * The reserved algorithm id for the explicit native choice. Registration of
     * this id is rejected, so it always resolves to the host's native packing.
     */
    public static final String NATIVE_ALGORITHM_ID = "native";

    public TextureAtlasLayoutSelection {
        algorithmId = algorithmId == null || algorithmId.isBlank() ? null : algorithmId.strip();
    }

    /** @return the native default selection: no algorithm id, serial packing. */
    public static TextureAtlasLayoutSelection nativeDefault() {
        return new TextureAtlasLayoutSelection(null, false);
    }

    /**
     * @return whether this selection resolves to the host's native packing —
     *     true for both the unset state ({@code null} id) and the explicit
     *     native choice ({@link #NATIVE_ALGORITHM_ID})
     */
    public boolean isNative() {
        return algorithmId == null || NATIVE_ALGORITHM_ID.equals(algorithmId);
    }
}
