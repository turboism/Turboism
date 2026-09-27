package dev.turboism.preview;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.sdk.runtime.PluginLifecycleEvent;

import java.util.Objects;

/**
 * Narrow publisher of runtime-owned {@link PluginLifecycleEvent} observations.
 *
 * <p>Only verdict points call this: an event reports the outcome the lifecycle
 * actually reached, never an aspiration. Publication is best-effort — a failing
 * broker or a torn-down session must never change a lifecycle verdict, so every
 * emission failure is diagnosed to the log and swallowed.</p>
 */
final class PluginLifecycleEvents {

    private final RuntimeEventBroker eventBroker;
    private final PreviewLog log;

    PluginLifecycleEvents(final RuntimeEventBroker eventBroker, final PreviewLog log) {
        this.eventBroker = Objects.requireNonNull(eventBroker, "eventBroker");
        this.log = Objects.requireNonNull(log, "log");
    }

    void loaded(final String pluginId, final long generation) {
        publish(pluginId, generation,
            PluginLifecycleEvent.Phase.LOAD, PluginLifecycleEvent.Outcome.SUCCEEDED);
    }

    /** Load reached a terminal failure verdict (including a lane rejection). */
    void loadFailed(final String pluginId, final long generation) {
        publish(pluginId, generation,
            PluginLifecycleEvent.Phase.LOAD, PluginLifecycleEvent.Outcome.FAILED);
    }

    /** Load exceeded its deadline; the fenced generation may still be retained. */
    void loadTimedOut(final String pluginId, final long generation) {
        publish(pluginId, generation,
            PluginLifecycleEvent.Phase.LOAD, PluginLifecycleEvent.Outcome.TIMED_OUT);
    }

    /** Published only once the generation's cleanup genuinely completed. */
    void unloaded(final String pluginId, final long generation) {
        publish(pluginId, generation,
            PluginLifecycleEvent.Phase.UNLOAD, PluginLifecycleEvent.Outcome.SUCCEEDED);
    }

    /** Close reached a terminal failure verdict. */
    void unloadFailed(final String pluginId, final long generation) {
        publish(pluginId, generation,
            PluginLifecycleEvent.Phase.UNLOAD, PluginLifecycleEvent.Outcome.FAILED);
    }

    /** Close exceeded its deadline; cleanup continues under retention. */
    void unloadTimedOut(final String pluginId, final long generation) {
        publish(pluginId, generation,
            PluginLifecycleEvent.Phase.UNLOAD, PluginLifecycleEvent.Outcome.TIMED_OUT);
    }

    private void publish(
        final String pluginId,
        final long generation,
        final PluginLifecycleEvent.Phase phase,
        final PluginLifecycleEvent.Outcome outcome
    ) {
        try {
            eventBroker.publishRuntime(new PluginLifecycleEvent(
                pluginId, generation, phase, outcome
            ));
        } catch (Throwable failure) {
            log.error(
                "plugin-lifecycle",
                "Plugin lifecycle event publication failed safely: "
                    + pluginId + " " + phase + " " + outcome,
                failure
            );
        }
    }
}
