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

    public CompletionStage<ConfigBindingResult> enable() {
        if (!initialized || registry == null) {
            return completed(ConfigBindingResult.RUNTIME_UNAVAILABLE);
        }
        enabled = true;
        final long activeEpoch = ++epoch;
        return readAll(activeEpoch, false);
    }

    public void disable() {
        enabled = false;
        epoch++;
    }

    public void shutdown() {
        disable();
        initialized = false;
        registry = null;
    }

    public ProjectPanelStateModel confirmed() {
        return confirmed;
    }

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
                    confirmed = ProjectPanelStateModel.hydrate(
                        phase(reads.first().value().value()),
                        reads.second().value().value(), reads.third().value().value(),
                        reads.fourth().value().value(), reads.fifth().value().value()
                    );
                    revision = valueRevision;
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
            final ProjectPanelStateModel lastConfirmed = confirmed;
            final long lastRevision = revision;
            return readAll(activeEpoch, false).thenApply(ignored -> {
                confirmed = lastConfirmed;
                revision = lastRevision;
                return ConfigBindingResult.PARTIAL_PERSISTENCE;
            });
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
