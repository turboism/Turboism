package dev.turboism.plugin.logfilter.b1.application;

import dev.turboism.plugin.logfilter.b1.domain.KeywordMode;
import dev.turboism.plugin.logfilter.b1.domain.LogFilterSettings;
import dev.turboism.plugin.logfilter.b1.domain.LogLevel;
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

public final class LogFilterSettingsBinding {

    public static final String CONFIG_ID = "log-filter.settings";
    public static final String CONFIG_PATH = "log-filter/settings.cfg";
    private static final ConfigKey<LogLevel> MINIMUM_LEVEL = new ConfigKey<>(
        CONFIG_ID, "minimumLevel", LogLevel.INFO, ConfigCodecs.enumValue(LogLevel.class)
    );
    private static final ConfigKey<KeywordMode> KEYWORD_MODE = new ConfigKey<>(
        CONFIG_ID, "keywordMode", KeywordMode.ANY, ConfigCodecs.enumValue(KeywordMode.class)
    );
    private static final ConfigKey<Boolean> CASE_SENSITIVE = new ConfigKey<>(
        CONFIG_ID, "caseSensitive", false, ConfigCodecs.booleanValue()
    );
    private static final ConfigKey<List<String>> KEYWORDS = new ConfigKey<>(
        CONFIG_ID, "keywords", List.of(), ConfigCodecs.boundedStringList(16, 128)
    );
    private static final ConfigSchema SCHEMA = new ConfigSchema(
        CONFIG_ID,
        CONFIG_PATH,
        1,
        List.of(MINIMUM_LEVEL, KEYWORD_MODE, CASE_SENSITIVE, KEYWORDS)
    );

    private PluginConfigRegistry registry;
    private LogFilterSettings confirmed = LogFilterSettings.defaults();
    private long documentRevision;
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
        registry = null;
        initialized = false;
    }

    public LogFilterSettings confirmed() {
        return confirmed;
    }

    public CompletionStage<ConfigBindingResult> update(final LogFilterSettings value) {
        Objects.requireNonNull(value, "value");
        if (!enabled || registry == null) {
            return completed(ConfigBindingResult.DISABLED);
        }
        if (value.equals(confirmed)) {
            return completed(ConfigBindingResult.UNCHANGED);
        }
        final long activeEpoch = epoch;
        final long startingRevision = documentRevision;
        return write(MINIMUM_LEVEL, value.minimumLevel(), startingRevision)
            .thenCompose(first -> continueWrite(first, KEYWORD_MODE, value.keywordMode(), activeEpoch))
            .thenCompose(second -> continueWrite(second, CASE_SENSITIVE, value.caseSensitive(), activeEpoch))
            .thenCompose(third -> continueWrite(third, KEYWORDS, value.keywords(), activeEpoch))
            .thenCompose(last -> finishUpdate(last, value, activeEpoch));
    }

    private CompletionStage<ConfigBindingResult> readAll(final long activeEpoch, final boolean retried) {
        return registry.read(MINIMUM_LEVEL).thenCombine(registry.read(KEYWORD_MODE), Pair::new)
            .thenCombine(registry.read(CASE_SENSITIVE), Triple::new)
            .thenCombine(registry.read(KEYWORDS), Quad::new)
            .thenCompose(reads -> {
                if (!enabled || epoch != activeEpoch) {
                    return completed(ConfigBindingResult.DISABLED);
                }
                final ConfigBindingResult readFailure = readFailure(reads);
                if (readFailure != null) {
                    return completed(readFailure);
                }
                final long revision = reads.first().value().revision();
                if (reads.second().value().revision() != revision
                    || reads.third().value().revision() != revision
                    || reads.fourth().value().revision() != revision) {
                    return retried
                        ? completed(ConfigBindingResult.REVISION_CONFLICT)
                        : readAll(activeEpoch, true);
                }
                try {
                    confirmed = new LogFilterSettings(
                        reads.first().value().value(),
                        reads.second().value().value(),
                        reads.third().value().value(),
                        reads.fourth().value().value()
                    );
                    documentRevision = revision;
                    return completed(ConfigBindingResult.APPLIED);
                } catch (IllegalArgumentException invalid) {
                    return completed(ConfigBindingResult.INVALID_VALUE);
                }
            }).exceptionally(failure -> runtimeFailure(unwrap(failure)));
    }

    private <T> CompletionStage<WriteStep> write(
        final ConfigKey<T> key,
        final T value,
        final long revision
    ) {
        return registry.write(key, value, revision)
            .handle((result, failure) -> failure == null
                ? WriteStep.from(result, false)
                : WriteStep.failure(ConfigBindingResult.RUNTIME_UNAVAILABLE, revision, false));
    }

    private <T> CompletionStage<WriteStep> continueWrite(
        final WriteStep previous,
        final ConfigKey<T> key,
        final T value,
        final long activeEpoch
    ) {
        if (!enabled || epoch != activeEpoch) {
            return completedStep(WriteStep.failure(ConfigBindingResult.DISABLED, previous.revision(), previous.wroteAny()));
        }
        if (previous.result() != null) {
            return completedStep(previous);
        }
        return write(key, value, previous.revision()).thenApply(next ->
            new WriteStep(next.result(), next.revision(), previous.wroteAny() || next.wroteAny()));
    }

    private CompletionStage<ConfigBindingResult> finishUpdate(
        final WriteStep step,
        final LogFilterSettings value,
        final long activeEpoch
    ) {
        if (!enabled || epoch != activeEpoch) {
            return completed(ConfigBindingResult.DISABLED);
        }
        if (step.result() == null) {
            documentRevision = step.revision();
            confirmed = value;
            return completed(ConfigBindingResult.APPLIED);
        }
        if (step.wroteAny()) {
            final LogFilterSettings lastConfirmed = confirmed;
            final long lastRevision = documentRevision;
            return readAll(activeEpoch, false).thenApply(ignored -> {
                confirmed = lastConfirmed;
                documentRevision = lastRevision;
                return ConfigBindingResult.PARTIAL_PERSISTENCE;
            });
        }
        return completed(step.result());
    }

    private static ConfigBindingResult readFailure(final Quad reads) {
        for (ConfigReadResult<?> read : List.of(reads.first(), reads.second(), reads.third(), reads.fourth())) {
            if (read.error().isPresent()) {
                return map(read.error().orElseThrow().code());
            }
        }
        return null;
    }

    private static ConfigBindingResult registrationFailure(final Throwable failure) {
        if (failure instanceof ConfigRegistrationException registration) {
            return registration.error() == ConfigRegistrationError.PERMISSION_DENIED
                ? ConfigBindingResult.PERMISSION_DENIED
                : ConfigBindingResult.RUNTIME_UNAVAILABLE;
        }
        return ConfigBindingResult.RUNTIME_UNAVAILABLE;
    }

    private static ConfigBindingResult runtimeFailure(final Throwable failure) {
        return failure instanceof IllegalArgumentException
            ? ConfigBindingResult.INVALID_VALUE
            : ConfigBindingResult.RUNTIME_UNAVAILABLE;
    }

    private static ConfigBindingResult map(final ConfigErrorCode code) {
        return switch (code) {
            case REVISION_CONFLICT -> ConfigBindingResult.REVISION_CONFLICT;
            case PERMISSION_DENIED -> ConfigBindingResult.PERMISSION_DENIED;
            case INVALID_VALUE -> ConfigBindingResult.INVALID_VALUE;
            default -> ConfigBindingResult.RUNTIME_UNAVAILABLE;
        };
    }

    private static Throwable unwrap(final Throwable value) {
        return value.getCause() == null ? value : value.getCause();
    }

    private static CompletionStage<ConfigBindingResult> completed(final ConfigBindingResult value) {
        return java.util.concurrent.CompletableFuture.completedStage(value);
    }

    private static CompletionStage<WriteStep> completedStep(final WriteStep value) {
        return java.util.concurrent.CompletableFuture.completedStage(value);
    }

    private record Pair(ConfigReadResult<LogLevel> first, ConfigReadResult<KeywordMode> second) {
    }

    private record Triple(Pair pair, ConfigReadResult<Boolean> third) {
        ConfigReadResult<LogLevel> first() { return pair.first(); }
        ConfigReadResult<KeywordMode> second() { return pair.second(); }
    }

    private record Quad(Triple triple, ConfigReadResult<List<String>> fourth) {
        ConfigReadResult<LogLevel> first() { return triple.first(); }
        ConfigReadResult<KeywordMode> second() { return triple.second(); }
        ConfigReadResult<Boolean> third() { return triple.third(); }
    }

    private record WriteStep(ConfigBindingResult result, long revision, boolean wroteAny) {
        static WriteStep from(final ConfigWriteResult value, final boolean previousWrite) {
            return value.written()
                ? new WriteStep(null, value.revision(), true)
                : failure(map(value.error().orElseThrow().code()), value.revision(), previousWrite);
        }

        static WriteStep failure(ConfigBindingResult result, long revision, boolean wroteAny) {
            return new WriteStep(result, revision, wroteAny);
        }
    }
}
