package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;

import java.util.Optional;

/** Exact-version provider boundary for validated complete atlas plans. */
public interface TextureAtlasLayoutProvider {

    /**
     * @return the current authoring state the provider observes; empty when no atlas
     *         authoring surface is active
     */
    Optional<TextureAtlasAuthoringState> current();

    /**
     * Applies a validated layout plan when the observed state still matches.
     *
     * @param expected the authoring state the plan was validated against; a stale value
     *        rejects the apply rather than writing over a changed host
     * @param plan the complete atlas plan to apply
     * @return how the apply ended
     */
    ApplyOutcome apply(TextureAtlasAuthoringState expected, TextureAtlasLayoutPlan plan);

    /** How a {@link #apply} attempt ended. */
    enum ApplyOutcome {
        /** The plan was applied to the host atlas. */
        APPLIED,
        /** The host already matched the plan; nothing was written. */
        NO_CHANGE,
        /** The apply was refused, for example a stale {@code expected} state. */
        REJECTED
    }
}
