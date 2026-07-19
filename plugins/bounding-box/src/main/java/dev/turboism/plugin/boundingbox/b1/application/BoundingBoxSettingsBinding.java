package dev.turboism.plugin.boundingbox.b1.application;

import dev.turboism.plugin.boundingbox.b1.domain.BoundingBoxFeatureSettings;
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

public final class BoundingBoxSettingsBinding {
    public static final String CONFIG_ID = "bounding-box.features";
    public static final String CONFIG_PATH = "bounding-box/features.cfg";
    private static final ConfigKey<Boolean> OVERLAY = new ConfigKey<>(CONFIG_ID, "overlayButtonsEnabled", true, ConfigCodecs.booleanValue());
    private static final ConfigKey<Boolean> WORKSPACE = new ConfigKey<>(CONFIG_ID, "workspaceButtonsEnabled", true, ConfigCodecs.booleanValue());
    private static final ConfigKey<Boolean> SUPPRESSED = new ConfigKey<>(CONFIG_ID, "mirrorAndShrinkSuppressed", false, ConfigCodecs.booleanValue());
    private static final ConfigSchema SCHEMA = new ConfigSchema(CONFIG_ID, CONFIG_PATH, 1, List.of(OVERLAY, WORKSPACE, SUPPRESSED));

    private PluginConfigRegistry registry;
    private BoundingBoxFeatureSettings confirmed = BoundingBoxFeatureSettings.defaults();
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
                return registration(unwrap(failure));
            });
        } catch (RuntimeException failure) {
            return completed(registration(failure));
        }
    }

    public CompletionStage<ConfigBindingResult> enable() {
        if (!initialized || registry == null) return completed(ConfigBindingResult.RUNTIME_UNAVAILABLE);
        enabled = true;
        return readAll(++epoch, false, true);
    }

    public CompletionStage<ConfigBindingResult> update(final BoundingBoxFeatureSettings value) {
        Objects.requireNonNull(value, "value");
        if (!enabled || registry == null) return completed(ConfigBindingResult.DISABLED);
        if (value.equals(confirmed)) return completed(ConfigBindingResult.UNCHANGED);
        final long active = epoch;
        return write(OVERLAY, value.overlayButtonsEnabled(), revision)
            .thenCompose(first -> next(first, WORKSPACE, value.workspaceButtonsEnabled(), active))
            .thenCompose(second -> next(second, SUPPRESSED, value.mirrorAndShrinkSuppressed(), active))
            .thenCompose(last -> finish(last, value, active));
    }

    public void disable() { enabled = false; epoch++; }
    public void shutdown() { disable(); initialized = false; registry = null; }
    public BoundingBoxFeatureSettings confirmed() { return confirmed; }

    private CompletionStage<ConfigBindingResult> readAll(
        final long active,
        final boolean retried,
        final boolean applyRead
    ) {
        return registry.read(OVERLAY).thenCombine(registry.read(WORKSPACE), Pair::new)
            .thenCombine(registry.read(SUPPRESSED), Triple::new)
            .thenCompose(reads -> {
                if (!enabled || epoch != active) return completed(ConfigBindingResult.DISABLED);
                for (ConfigReadResult<?> read : List.of(reads.first(), reads.second(), reads.third())) {
                    if (read.error().isPresent()) return completed(map(read.error().orElseThrow().code()));
                }
                final long readRevision = reads.first().value().revision();
                if (reads.second().value().revision() != readRevision || reads.third().value().revision() != readRevision) {
                    return retried ? completed(ConfigBindingResult.REVISION_CONFLICT) : readAll(active, true, applyRead);
                }
                if (applyRead) {
                    confirmed = new BoundingBoxFeatureSettings(
                        reads.first().value().value(), reads.second().value().value(), reads.third().value().value()
                    );
                    revision = readRevision;
                }
                return completed(ConfigBindingResult.APPLIED);
            }).exceptionally(failure -> ConfigBindingResult.RUNTIME_UNAVAILABLE);
    }

    private <T> CompletionStage<Step> write(final ConfigKey<T> key, final T value, final long expected) {
        return registry.write(key, value, expected).handle((result, failure) -> failure == null
            ? Step.from(result, false)
            : new Step(ConfigBindingResult.RUNTIME_UNAVAILABLE, expected, false));
    }

    private <T> CompletionStage<Step> next(
        final Step prior,
        final ConfigKey<T> key,
        final T value,
        final long active
    ) {
        if (!enabled || epoch != active) return completedStep(new Step(ConfigBindingResult.DISABLED, prior.revision(), prior.wroteAny()));
        if (prior.result() != null) return completedStep(prior);
        return write(key, value, prior.revision()).thenApply(current ->
            new Step(current.result(), current.revision(), prior.wroteAny() || current.wroteAny()));
    }

    private CompletionStage<ConfigBindingResult> finish(
        final Step step,
        final BoundingBoxFeatureSettings value,
        final long active
    ) {
        if (!enabled || epoch != active) return completed(ConfigBindingResult.DISABLED);
        if (step.result() == null) {
            revision = step.revision();
            confirmed = value;
            return completed(ConfigBindingResult.APPLIED);
        }
        if (step.wroteAny()) {
            return readAll(active, false, false).thenApply(ignored -> ConfigBindingResult.PARTIAL_PERSISTENCE);
        }
        return completed(step.result());
    }

    private static ConfigBindingResult registration(final Throwable failure) {
        return failure instanceof ConfigRegistrationException registration
            && registration.error() == ConfigRegistrationError.PERMISSION_DENIED
            ? ConfigBindingResult.PERMISSION_DENIED : ConfigBindingResult.RUNTIME_UNAVAILABLE;
    }

    private static ConfigBindingResult map(final ConfigErrorCode code) {
        return switch (code) {
            case REVISION_CONFLICT -> ConfigBindingResult.REVISION_CONFLICT;
            case PERMISSION_DENIED -> ConfigBindingResult.PERMISSION_DENIED;
            case INVALID_VALUE -> ConfigBindingResult.INVALID_VALUE;
            default -> ConfigBindingResult.RUNTIME_UNAVAILABLE;
        };
    }

    private static Throwable unwrap(final Throwable value) { return value.getCause() == null ? value : value.getCause(); }
    private static CompletionStage<ConfigBindingResult> completed(final ConfigBindingResult value) { return java.util.concurrent.CompletableFuture.completedStage(value); }
    private static CompletionStage<Step> completedStep(final Step value) { return java.util.concurrent.CompletableFuture.completedStage(value); }
    private record Pair(ConfigReadResult<Boolean> first, ConfigReadResult<Boolean> second) { }
    private record Triple(Pair pair, ConfigReadResult<Boolean> third) {
        ConfigReadResult<Boolean> first() { return pair.first(); }
        ConfigReadResult<Boolean> second() { return pair.second(); }
    }
    private record Step(ConfigBindingResult result, long revision, boolean wroteAny) {
        static Step from(final ConfigWriteResult value, final boolean previous) {
            return value.written() ? new Step(null, value.revision(), true)
                : new Step(map(value.error().orElseThrow().code()), value.revision(), previous);
        }
    }
}
