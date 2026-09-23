package dev.turboism.sdk.runtime;

import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/**
 * Runtime-owned observation of one plugin lifecycle verdict.
 *
 * <p>Published by the runtime when a plugin load or unload attempt reaches an outcome:
 * a {@link Phase#LOAD} event reports the verdict of the construct/init/enable/commit
 * sequence, and a {@link Phase#UNLOAD} event reports the verdict of the fenced
 * disable/shutdown/cleanup sequence. The payload is deliberately minimal — stable
 * plugin identity, the admitted event-owner generation, the phase and its outcome —
 * and carries no {@link Throwable}, classloader, scope or other runtime handle.</p>
 *
 * <p>Semantics an observer can rely on:</p>
 * <ul>
 *   <li>{@link Outcome#SUCCEEDED} on {@link Phase#LOAD} is only published when the
 *       generation actually committed and activated — a timed-out load reports
 *       {@link Outcome#TIMED_OUT}, never success, even if its fenced worker later
 *       finishes.</li>
 *   <li>{@link Outcome#SUCCEEDED} on {@link Phase#UNLOAD} is only published once the
 *       generation's cleanup has genuinely completed; a close that overruns its
 *       deadline reports {@link Outcome#TIMED_OUT} and, if the retained cleanup
 *       later finishes, a separate {@link Outcome#SUCCEEDED} observation follows.
 *       No {@link Phase#UNLOAD} success is ever published while retained cleanup
 *       is incomplete.</li>
 *   <li>{@link #generation()} distinguishes reloads of the same plugin id: each
 *       admitted generation gets its own events. A {@link Phase#LOAD} failure that
 *       occurred before an event-owner generation was admitted reports
 *       {@link #NO_ADMITTED_GENERATION}.</li>
 *   <li>Events are live observations only — they are not retained for replay, so a
 *       plugin that subscribes later sees only verdicts reached after it
 *       subscribed. Subscription requires the
 *       {@code turboism.plugin.lifecycle.observe} permission.</li>
 * </ul>
 */
public record PluginLifecycleEvent(
    String pluginId,
    long generation,
    Phase phase,
    Outcome outcome
) implements TurboismEvent {

    /**
     * {@link #generation()} value reported when a load failed before the runtime
     * admitted an event-owner generation for the attempt.
     */
    public static final long NO_ADMITTED_GENERATION = -1L;

    public PluginLifecycleEvent {
        pluginId = Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        if (generation < NO_ADMITTED_GENERATION) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        phase = Objects.requireNonNull(phase, "phase");
        outcome = Objects.requireNonNull(outcome, "outcome");
    }

    /** The lifecycle transition whose verdict this event reports. */
    public enum Phase {
        /** Plugin construct/init/enable/commit. */
        LOAD,
        /** Plugin fenced disable/shutdown/scope/classloader cleanup. */
        UNLOAD
    }

    /** The verdict the phase reached. */
    public enum Outcome {
        /** The phase committed; for UNLOAD this includes completed cleanup. */
        SUCCEEDED,
        /** The phase failed with a terminal verdict. */
        FAILED,
        /** The phase exceeded its bounded deadline; the fenced generation may be
         *  retained until its in-flight work actually settles. */
        TIMED_OUT
    }
}
