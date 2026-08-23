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

/**
 * Binds the bounding-box feature settings to the plugin config store, keeping a confirmed
 * in-memory copy that only ever reflects values the store actually accepted.
 *
 * <p>Operations follow the plugin lifecycle: {@link #init} registers the schema once,
 * {@link #enable} loads the persisted values, {@link #update} writes, {@link #disable} stops
 * accepting work and {@link #shutdown} releases the registry. Each enable bumps an epoch, and any
 * asynchronous stage whose epoch has been superseded resolves to
 * {@link ConfigBindingResult#DISABLED} instead of writing back stale state — so a disable during
 * an in-flight update cannot resurrect old settings.</p>
 *
 * <p>Nothing here throws for a config problem: every path completes with a
 * {@link ConfigBindingResult}. Writes are optimistic against a revision, and a multi-key write
 * that fails partway reports {@link ConfigBindingResult#PARTIAL_PERSISTENCE} after re-reading, so
 * the confirmed settings are never guessed. This class is not thread-safe; drive it from the
 * plugin's own lifecycle thread.</p>
 */
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

    /**
     * Registers the settings schema with the runtime. Must complete successfully before
     * {@link #enable} does anything; a failed registration leaves the binding uninitialized, and
     * a registry exception thrown synchronously is captured rather than propagated.
     *
     * @param value the plugin's config registry
     * @return {@link ConfigBindingResult#APPLIED} on success,
     *     {@link ConfigBindingResult#PERMISSION_DENIED} when registration was refused for lack of
     *     permission, otherwise {@link ConfigBindingResult#RUNTIME_UNAVAILABLE}
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
                return registration(unwrap(failure));
            });
        } catch (RuntimeException failure) {
            return completed(registration(failure));
        }
    }

    /**
     * Opens a new epoch and loads all three settings from the store into the confirmed settings.
     *
     * <p>The three keys are read together and must share one revision; a torn read is retried once
     * before being reported as a conflict. On any failure the previously confirmed settings are
     * left untouched.</p>
     *
     * @return {@link ConfigBindingResult#APPLIED} when the settings were loaded,
     *     {@link ConfigBindingResult#RUNTIME_UNAVAILABLE} when {@link #init} has not succeeded, and
     *     the mapped error otherwise
     */
    public CompletionStage<ConfigBindingResult> enable() {
        if (!initialized || registry == null) return completed(ConfigBindingResult.RUNTIME_UNAVAILABLE);
        enabled = true;
        return readAll(++epoch, false, true);
    }

    /**
     * Persists new settings, writing the three keys in sequence against the confirmed revision and
     * stopping at the first key the store refuses.
     *
     * <p>The confirmed settings are advanced only when every key was written. If some keys landed
     * before the refusal, the store is re-read and the call reports
     * {@link ConfigBindingResult#PARTIAL_PERSISTENCE}; if none did, the mapped error is reported
     * and nothing changed.</p>
     *
     * @param value the settings to persist
     * @return {@link ConfigBindingResult#APPLIED} on a complete write,
     *     {@link ConfigBindingResult#UNCHANGED} when {@code value} already equals the confirmed
     *     settings, {@link ConfigBindingResult#DISABLED} when the binding is disabled or was
     *     superseded mid-flight, otherwise the mapped failure
     * @throws NullPointerException if {@code value} is null
     */
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

    /**
     * Stops accepting reads and writes and retires the current epoch, so any in-flight stage
     * resolves to {@link ConfigBindingResult#DISABLED}. The schema registration and the confirmed
     * settings survive, so a later {@link #enable} can resume without re-registering.
     */
    public void disable() { enabled = false; epoch++; }

    /**
     * Disables the binding and additionally drops the registry reference and the initialized flag.
     * After this the binding is inert until {@link #init} is called again; the confirmed settings
     * remain readable.
     */
    public void shutdown() { disable(); initialized = false; registry = null; }

    /**
     * @return the settings the store is last known to hold — never a value that was merely
     *     requested. Starts at {@link BoundingBoxFeatureSettings#defaults()} and advances only on
     *     a successful load or a fully successful write.
     */
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
