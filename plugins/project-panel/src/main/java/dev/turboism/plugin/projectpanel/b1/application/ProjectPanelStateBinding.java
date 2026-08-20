package dev.turboism.plugin.projectpanel.b1.application;

import dev.turboism.plugin.projectpanel.b1.domain.ProjectPanelStateModel;
import dev.turboism.plugin.projectpanel.b1.domain.ProjectPhase;
import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigRegistrationError;
import dev.turboism.sdk.config.ConfigRegistrationException;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Binds the project panel's state model to plugin configuration storage.
 *
 * <p>Holds the last confirmed state together with the revision it was read or written at, and
 * uses that revision for optimistic concurrency on every write. Because the five keys are
 * written one at a time, a mid-sequence failure can leave storage partially updated; the
 * binding then re-reads without adopting the result and reports
 * {@code PARTIAL_PERSISTENCE} rather than claiming success. An epoch counter, bumped on each
 * enable and disable, causes any operation still in flight across a lifecycle change to
 * resolve to {@code DISABLED} instead of overwriting newer state. Nothing here throws for
 * storage problems - every failure becomes a {@link ConfigBindingResult}.
 */
public final class ProjectPanelStateBinding {

    public static final String CONFIG_ID = "project-panel.state";
    public static final String CONFIG_PATH = "project-panel/state.cfg";
    private static final ConfigKey<StoredProjectPhase> LAST_PHASE = new ConfigKey<>(
        CONFIG_ID, "lastPhase", StoredProjectPhase.NONE, ConfigCodecs.enumValue(StoredProjectPhase.class)
    );
    private static final ConfigKey<Integer> OPENING_COUNT = counter("openingCount");
    private static final ConfigKey<Integer> OPENED_COUNT = counter("openedCount");
    private static final ConfigKey<Integer> CLOSING_COUNT = counter("closingCount");
    private static final ConfigKey<Integer> CLOSED_COUNT = counter("closedCount");
    private static final ConfigSchema SCHEMA = new ConfigSchema(
        CONFIG_ID, CONFIG_PATH, 1,
        List.of(LAST_PHASE, OPENING_COUNT, OPENED_COUNT, CLOSING_COUNT, CLOSED_COUNT)
    );

    private PluginConfigRegistry registry;
    private ProjectPanelStateModel confirmed = ProjectPanelStateModel.defaults();
    private long revision;
    private long epoch;
    private boolean initialized;
    private boolean enabled;

    /**
     * Registers the panel's configuration schema, the prerequisite for every other operation.
     *
     * <p>Does not enable the binding or read anything; {@link #enable()} does that. A failure
     * leaves the binding uninitialized, so a later {@code enable()} reports
     * {@code RUNTIME_UNAVAILABLE}. Both synchronous and asynchronous registration failures are
     * mapped to a result rather than propagated.
     *
     * @param value the plugin's configuration registry; must not be null
     * @return {@code APPLIED} on successful registration, {@code PERMISSION_DENIED} when the
     *         plugin may not register this schema, otherwise {@code RUNTIME_UNAVAILABLE}
     * @throws NullPointerException if {@code value} is null
     */
    public CompletionStage<ConfigBindingResult> init(final PluginConfigRegistry value) {
        registry = Objects.requireNonNull(value, "value");
        try {
            return registry.registerSchema(SCHEMA, List.of()).handle((ignored, failure) -> {
                if (failure == null) {
                    initialized = true;
                    return ConfigBindingResult.APPLIED;
                }
                return registrationFailure(unwrap(failure));
            });
        } catch (RuntimeException failure) {
            return completed(registrationFailure(failure));
        }
    }

    /**
     * Enables the binding and hydrates the confirmed state from storage.
     *
     * <p>Reads all five keys and requires them to share one revision; if they do not, the read is
     * retried once before reporting a conflict, since a concurrent write can be observed
     * mid-flight. Confirmed state and revision are adopted only on a clean, consistent read.
     *
     * @return {@code APPLIED} when the state was hydrated, {@code RUNTIME_UNAVAILABLE} when the
     *         schema was never registered, {@code DISABLED} if the binding was disabled or
     *         re-enabled while reading, {@code REVISION_CONFLICT} if the keys still disagreed
     *         after the retry, {@code INVALID_VALUE} if the stored values do not form a valid
     *         state model, or the mapped read error
     */
    public CompletionStage<ConfigBindingResult> enable() {
        if (!initialized || registry == null) {
            return completed(ConfigBindingResult.RUNTIME_UNAVAILABLE);
        }
        enabled = true;
        final long activeEpoch = ++epoch;
        return readAll(activeEpoch, false);
    }

    /**
     * Disables the binding and bumps the epoch, so any read or write already in flight resolves
     * to {@code DISABLED} rather than mutating confirmed state. The last confirmed state is
     * retained and the schema stays registered, so {@link #enable()} can resume.
     */
    public void disable() {
        enabled = false;
        epoch++;
    }

    /**
     * Disables the binding and drops the registry reference, returning to the uninitialized
     * state. {@link #init} must be called again before the binding can be enabled; the last
     * confirmed state is left in place.
     */
    public void shutdown() {
        disable();
        initialized = false;
        registry = null;
    }

    /**
     * @return the state model last known to match storage - the defaults until a successful
     *         hydrate or write, and unchanged by any operation that did not report
     *         {@code APPLIED}
     */
    public ProjectPanelStateModel confirmed() {
        return confirmed;
    }

    /**
     * Persists a new state model, writing each key against the tracked revision.
     *
     * <p>Short-circuits to {@code UNCHANGED} when the persisted fields already match, so
     * non-persisted differences never cause a write. Writes stop at the first failing key.
     * Confirmed state and revision are adopted only when every key was written.
     *
     * @param value the state to persist; must not be null
     * @return {@code APPLIED} when all keys were written, {@code UNCHANGED} when nothing needed
     *         writing, {@code DISABLED} when the binding is not enabled or its lifecycle changed
     *         mid-write, {@code PARTIAL_PERSISTENCE} when some keys were written before a later
     *         one failed, or the mapped write error when the first key itself failed
     * @throws NullPointerException if {@code value} is null
     */
    public CompletionStage<ConfigBindingResult> update(final ProjectPanelStateModel value) {
        Objects.requireNonNull(value, "value");
        if (!enabled || registry == null) {
            return completed(ConfigBindingResult.DISABLED);
        }
        if (persistedEquals(confirmed, value)) {
            return completed(ConfigBindingResult.UNCHANGED);
        }
        final long activeEpoch = epoch;
        return write(LAST_PHASE, stored(value.lastPhase()), revision)
            .thenCompose(first -> next(first, OPENING_COUNT, value.openingCount(), activeEpoch))
            .thenCompose(second -> next(second, OPENED_COUNT, value.openedCount(), activeEpoch))
            .thenCompose(third -> next(third, CLOSING_COUNT, value.closingCount(), activeEpoch))
            .thenCompose(fourth -> next(fourth, CLOSED_COUNT, value.closedCount(), activeEpoch))
            .thenCompose(last -> finish(last, value, activeEpoch));
    }

    private CompletionStage<ConfigBindingResult> readAll(final long activeEpoch, final boolean retried) {
        return readAll(activeEpoch, retried, true);
    }

    private CompletionStage<ConfigBindingResult> readAll(
        final long activeEpoch,
        final boolean retried,
        final boolean applyRead
    ) {
        return registry.read(LAST_PHASE).thenCombine(registry.read(OPENING_COUNT), Pair::new)
            .thenCombine(registry.read(OPENED_COUNT), Triple::new)
            .thenCombine(registry.read(CLOSING_COUNT), Quad::new)
            .thenCombine(registry.read(CLOSED_COUNT), Five::new)
            .thenCompose(reads -> {
                if (!enabled || epoch != activeEpoch) return completed(ConfigBindingResult.DISABLED);
                final ConfigBindingResult failure = readFailure(reads);
                if (failure != null) return completed(failure);
                final long valueRevision = reads.first().value().revision();
                if (!reads.sameRevision(valueRevision)) {
                    return retried ? completed(ConfigBindingResult.REVISION_CONFLICT) : readAll(activeEpoch, true);
                }
                try {
                    final ProjectPanelStateModel hydrated = ProjectPanelStateModel.hydrate(
                        phase(reads.first().value().value()),
                        reads.second().value().value(), reads.third().value().value(),
                        reads.fourth().value().value(), reads.fifth().value().value()
                    );
                    if (applyRead) {
                        confirmed = hydrated;
                        revision = valueRevision;
                    }
                    return completed(ConfigBindingResult.APPLIED);
                } catch (IllegalArgumentException invalid) {
                    return completed(ConfigBindingResult.INVALID_VALUE);
                }
            }).exceptionally(failure -> ConfigBindingResult.RUNTIME_UNAVAILABLE);
    }

    private <T> CompletionStage<WriteStep> write(ConfigKey<T> key, T value, long expected) {
        return registry.write(key, value, expected).handle((result, failure) -> failure == null
            ? WriteStep.from(result, false)
            : new WriteStep(ConfigBindingResult.RUNTIME_UNAVAILABLE, expected, false));
    }

    private <T> CompletionStage<WriteStep> next(
        WriteStep previous, ConfigKey<T> key, T value, long activeEpoch
    ) {
        if (!enabled || epoch != activeEpoch) return completedStep(new WriteStep(ConfigBindingResult.DISABLED, previous.revision(), previous.wroteAny()));
        if (previous.result() != null) return completedStep(previous);
        return write(key, value, previous.revision()).thenApply(current ->
            new WriteStep(current.result(), current.revision(), previous.wroteAny() || current.wroteAny()));
    }

    private CompletionStage<ConfigBindingResult> finish(
        WriteStep step, ProjectPanelStateModel value, long activeEpoch
    ) {
        if (!enabled || epoch != activeEpoch) return completed(ConfigBindingResult.DISABLED);
        if (step.result() == null) {
            revision = step.revision();
            confirmed = value;
            return completed(ConfigBindingResult.APPLIED);
        }
        if (step.wroteAny()) {
            return readAll(activeEpoch, false, false)
                .thenApply(ignored -> ConfigBindingResult.PARTIAL_PERSISTENCE);
        }
        return completed(step.result());
    }

    private static ConfigBindingResult readFailure(Five reads) {
        for (ConfigReadResult<?> read : List.of(reads.first(), reads.second(), reads.third(), reads.fourth(), reads.fifth())) {
            if (read.error().isPresent()) return map(read.error().orElseThrow().code());
        }
        return null;
    }

    private static boolean persistedEquals(ProjectPanelStateModel left, ProjectPanelStateModel right) {
        return left.lastPhase() == right.lastPhase()
            && left.openingCount() == right.openingCount()
            && left.openedCount() == right.openedCount()
            && left.closingCount() == right.closingCount()
            && left.closedCount() == right.closedCount();
    }

    private static ConfigKey<Integer> counter(String name) {
        return new ConfigKey<>(CONFIG_ID, name, 0, ConfigCodecs.boundedInt(0, 1_000_000));
    }

    private static StoredProjectPhase stored(ProjectPhase value) {
        return value == null ? StoredProjectPhase.NONE : StoredProjectPhase.valueOf(value.name());
    }

    private static ProjectPhase phase(StoredProjectPhase value) {
        return value == StoredProjectPhase.NONE ? null : ProjectPhase.valueOf(value.name());
    }

    private static ConfigBindingResult registrationFailure(Throwable failure) {
        return failure instanceof ConfigRegistrationException registration
            && registration.error() == ConfigRegistrationError.PERMISSION_DENIED
            ? ConfigBindingResult.PERMISSION_DENIED : ConfigBindingResult.RUNTIME_UNAVAILABLE;
    }

    private static ConfigBindingResult map(ConfigErrorCode code) {
        return switch (code) {
            case REVISION_CONFLICT -> ConfigBindingResult.REVISION_CONFLICT;
            case PERMISSION_DENIED -> ConfigBindingResult.PERMISSION_DENIED;
            case INVALID_VALUE -> ConfigBindingResult.INVALID_VALUE;
            default -> ConfigBindingResult.RUNTIME_UNAVAILABLE;
        };
    }

    private static Throwable unwrap(Throwable value) { return value.getCause() == null ? value : value.getCause(); }
    private static CompletionStage<ConfigBindingResult> completed(ConfigBindingResult value) { return java.util.concurrent.CompletableFuture.completedStage(value); }
    private static CompletionStage<WriteStep> completedStep(WriteStep value) { return java.util.concurrent.CompletableFuture.completedStage(value); }

    private record Pair(ConfigReadResult<StoredProjectPhase> first, ConfigReadResult<Integer> second) { }
    private record Triple(Pair pair, ConfigReadResult<Integer> third) { ConfigReadResult<StoredProjectPhase> first(){return pair.first();} ConfigReadResult<Integer> second(){return pair.second();} }
    private record Quad(Triple triple, ConfigReadResult<Integer> fourth) { ConfigReadResult<StoredProjectPhase> first(){return triple.first();} ConfigReadResult<Integer> second(){return triple.second();} ConfigReadResult<Integer> third(){return triple.third();} }
    private record Five(Quad quad, ConfigReadResult<Integer> fifth) {
        ConfigReadResult<StoredProjectPhase> first(){return quad.first();} ConfigReadResult<Integer> second(){return quad.second();} ConfigReadResult<Integer> third(){return quad.third();} ConfigReadResult<Integer> fourth(){return quad.fourth();}
        boolean sameRevision(long expected){return second().value().revision()==expected&&third().value().revision()==expected&&fourth().value().revision()==expected&&fifth().value().revision()==expected;}
    }
    private record WriteStep(ConfigBindingResult result, long revision, boolean wroteAny) {
        static WriteStep from(ConfigWriteResult value, boolean previous){return value.written()?new WriteStep(null,value.revision(),true):new WriteStep(map(value.error().orElseThrow().code()),value.revision(),previous);}
    }
}
