package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.plugin.Registration;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Framework registry of texture-atlas layout algorithms. Plugins register their
 * algorithms at enable time; the runtime dialog contribution lists them and the
 * runtime routes the native automatic-layout invocation to the selected
 * algorithm.
 *
 * <p>Ownership: the runtime owns the registry, the selection state, and dispatch.
 * A registration lives until its {@link Registration} is closed or the owning
 * plugin is disabled, whichever comes first; a plugin that forgets to close is
 * still detached when its scope ends. Dispatch resolves the selected algorithm at
 * invocation time, so a missing, unregistered, or unloaded selection — or one
 * whose planner is {@code null} — falls back to the host's native packing rather
 * than failing. Planners that return {@code null} or throw are also converted to
 * native fallback; plugin failures never escape into the host.</p>
 */
public interface TextureAtlasLayoutAlgorithmRegistry {

    /**
     * Registers an algorithm. Replacing an existing id is allowed; closing the
     * returned registration removes only that exact registration generation.
     * Registering a {@code null} algorithm is rejected. Registering never selects
     * the algorithm; selection is owned by the runtime and driven by the native
     * dialog or {@link #select(TextureAtlasLayoutSelection)}.
     */
    Registration register(TextureAtlasLayoutAlgorithm algorithm);

    /** Finds a registered algorithm by id, if present. */
    Optional<TextureAtlasLayoutAlgorithm> find(String id);

    /** All registered algorithms, in registration order. */
    List<TextureAtlasLayoutAlgorithm> algorithms();

    /**
     * Returns the current automatic-layout selection.
     *
     * <p>The selection is runtime-owned user state, persisted across restarts.
     * A {@code null} algorithm id means the native default; an id that is not
     * currently registered remains stored and resolves to the native algorithm
     * until a matching registration exists.</p>
     *
     * <p>The default implementation reports the native default; it is used by
     * environments without a runtime selection authority.</p>
     */
    default TextureAtlasLayoutSelection selection() {
        return TextureAtlasLayoutSelection.nativeDefault();
    }

    /**
     * Requests an automatic-layout selection.
     *
     * <p>This is a request against runtime-owned state, not a registration-side
     * effect: the runtime persists it, the native dialog may overwrite it, and an
     * unknown id is stored as-is and falls back natively at dispatch. Pass
     * {@link TextureAtlasLayoutSelection#nativeDefault()} (or a selection with a
     * {@code null} algorithm id) to restore the native algorithm. The runtime
     * applies the change immediately for the next invocation.</p>
     *
     * <p>The default implementation accepts and ignores the request.</p>
     *
     * @throws NullPointerException if {@code selection} is null
     */
    default void select(final TextureAtlasLayoutSelection selection) {
        Objects.requireNonNull(selection, "selection");
    }
}
