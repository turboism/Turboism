package dev.turboism.plugin.backup.b1.application;

import dev.turboism.plugin.backup.webdav.WebDavConfig;
import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigRegistrationError;
import dev.turboism.sdk.config.ConfigRegistrationException;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigRegistry;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Reads the {@code backup/webdav.cfg} plugin configuration into a validated
 * {@link WebDavConfig}. The binding only reads; the plugin never writes its own
 * config. The password value is held in memory and never logged.
 */
public final class WebDavSettingsBinding {

    public static final String CONFIG_ID = "backup.webdav";
    public static final String CONFIG_PATH = "backup/webdav.cfg";
    public static final String DEFAULT_URL = "http://localhost:8080";
    public static final String DEFAULT_REMOTE_PATH = "/turboism-backup";
    public static final int DEFAULT_RETRY_MAX = 3;
    public static final long DEFAULT_RETRY_BASE_DELAY_MS = 500L;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private static final ConfigKey<Boolean> ENABLED =
        new ConfigKey<>(CONFIG_ID, "enabled", false, ConfigCodecs.booleanValue());
    private static final ConfigKey<String> URL =
        new ConfigKey<>(CONFIG_ID, "url", DEFAULT_URL, ConfigCodecs.stringValue(512));
    private static final ConfigKey<String> USERNAME =
        new ConfigKey<>(CONFIG_ID, "username", "", ConfigCodecs.stringValue(256));
    private static final ConfigKey<String> PASSWORD =
        new ConfigKey<>(CONFIG_ID, "password", "", ConfigCodecs.stringValue(512));
    private static final ConfigKey<String> REMOTE_PATH =
        new ConfigKey<>(CONFIG_ID, "remotePath", DEFAULT_REMOTE_PATH, ConfigCodecs.stringValue(512));
    private static final ConfigKey<Boolean> VERIFY_TLS =
        new ConfigKey<>(CONFIG_ID, "verifyTls", true, ConfigCodecs.booleanValue());
    private static final ConfigKey<Integer> RETRY_MAX =
        new ConfigKey<>(CONFIG_ID, "retryMax", DEFAULT_RETRY_MAX, ConfigCodecs.boundedInt(0, 10));
    private static final ConfigKey<Integer> RETRY_BASE_DELAY_MS =
        new ConfigKey<>(CONFIG_ID, "retryBaseDelayMs", (int) DEFAULT_RETRY_BASE_DELAY_MS,
            ConfigCodecs.boundedInt(0, 60_000));
    private static final ConfigKey<Integer> TIMEOUT_SECONDS =
        new ConfigKey<>(CONFIG_ID, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS, ConfigCodecs.boundedInt(1, 300));

    private static final ConfigSchema SCHEMA = new ConfigSchema(CONFIG_ID, CONFIG_PATH, 1, List.of(
        ENABLED, URL, USERNAME, PASSWORD, REMOTE_PATH, VERIFY_TLS, RETRY_MAX,
        RETRY_BASE_DELAY_MS, TIMEOUT_SECONDS
    ));

    private PluginConfigRegistry registry;
    private boolean initialized;
    private boolean enabled;
    private volatile WebDavConfig confirmed;

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
            return completedResult(registration(failure));
        }
    }

    /**
     * Reads the bound configuration; fails closed (returns {@code null}, target
     * disabled) on any read/parse/validation error. Never logs the password.
     */
    public CompletionStage<WebDavConfig> read() {
        if (!initialized || registry == null || !enabled) {
            return completedConfig(null);
        }
        final CompletableFuture<ConfigReadResult<Boolean>> enabledRead = registry.read(ENABLED).toCompletableFuture();
        final CompletableFuture<ConfigReadResult<String>> urlRead = registry.read(URL).toCompletableFuture();
        final CompletableFuture<ConfigReadResult<String>> usernameRead = registry.read(USERNAME).toCompletableFuture();
        final CompletableFuture<ConfigReadResult<String>> passwordRead = registry.read(PASSWORD).toCompletableFuture();
        final CompletableFuture<ConfigReadResult<String>> remotePathRead = registry.read(REMOTE_PATH).toCompletableFuture();
        final CompletableFuture<ConfigReadResult<Boolean>> verifyTlsRead = registry.read(VERIFY_TLS).toCompletableFuture();
        final CompletableFuture<ConfigReadResult<Integer>> retryMaxRead = registry.read(RETRY_MAX).toCompletableFuture();
        final CompletableFuture<ConfigReadResult<Integer>> retryBaseRead =
            registry.read(RETRY_BASE_DELAY_MS).toCompletableFuture();
        final CompletableFuture<ConfigReadResult<Integer>> timeoutRead =
            registry.read(TIMEOUT_SECONDS).toCompletableFuture();

        return CompletableFuture
            .allOf(enabledRead, urlRead, usernameRead, passwordRead, remotePathRead,
                verifyTlsRead, retryMaxRead, retryBaseRead, timeoutRead)
            .thenApply(ignored -> toConfig(
                enabledRead.join(), urlRead.join(), usernameRead.join(), passwordRead.join(),
                remotePathRead.join(), verifyTlsRead.join(), retryMaxRead.join(),
                retryBaseRead.join(), timeoutRead.join()
            ))
            .thenApply(config -> {
                if (config != null) {
                    confirmed = config;
                }
                return config;
            })
            .exceptionally(failure -> null);
    }

    /**
     * Persists the target configuration through the registry write path
     * (revision-chained, in frozen key order) and confirms it by reading the
     * values back. On success the readback must equal the target; any
     * unconfirmed write reports {@link ConfigBindingResult#PARTIAL_PERSISTENCE}.
     * The password is only ever written through the config store and never
     * logged.
     */
    public CompletionStage<ConfigBindingResult> update(final WebDavConfig value) {
        Objects.requireNonNull(value, "value");
        if (!initialized || registry == null || !enabled) {
            return completedResult(ConfigBindingResult.DISABLED);
        }
        if (value.equals(confirmed)) {
            return completedResult(ConfigBindingResult.UNCHANGED);
        }
        return registry.read(ENABLED)
            .thenCompose(seed -> {
                if (seed.error().isPresent()) {
                    return completedResult(map(seed.error().orElseThrow().code()));
                }
                return writeChain(new Step(null, seed.value().revision(), false), value);
            })
            .exceptionally(failure -> ConfigBindingResult.RUNTIME_UNAVAILABLE);
    }

    private CompletionStage<ConfigBindingResult> writeChain(final Step seed, final WebDavConfig value) {
        return write(ENABLED, value.enabled(), seed.revision())
            .thenCompose(step -> next(step, URL, value.url().toString()))
            .thenCompose(step -> next(step, USERNAME, value.username()))
            .thenCompose(step -> next(step, PASSWORD, value.password()))
            .thenCompose(step -> next(step, REMOTE_PATH, value.remotePath()))
            .thenCompose(step -> next(step, VERIFY_TLS, value.verifyTls()))
            .thenCompose(step -> next(step, RETRY_MAX, value.retryMax()))
            .thenCompose(step -> next(step, RETRY_BASE_DELAY_MS, (int) value.retryBaseDelayMs()))
            .thenCompose(step -> next(step, TIMEOUT_SECONDS, value.timeoutSeconds()))
            .thenCompose(step -> finish(step, value));
    }

    private <T> CompletionStage<Step> write(
        final ConfigKey<T> key, final T value, final long expected
    ) {
        return registry.write(key, value, expected).handle((result, failure) -> failure == null
            ? Step.from(result, false)
            : new Step(ConfigBindingResult.RUNTIME_UNAVAILABLE, expected, false));
    }

    private <T> CompletionStage<Step> next(
        final Step prior, final ConfigKey<T> key, final T value
    ) {
        if (prior.result() != null) {
            return completedStep(prior);
        }
        return write(key, value, prior.revision()).thenApply(current ->
            new Step(current.result(), current.revision(), prior.wroteAny() || current.wroteAny())
        );
    }

    private CompletionStage<ConfigBindingResult> finish(final Step step, final WebDavConfig value) {
        if (step.result() != null) {
            return completedResult(step.wroteAny()
                ? ConfigBindingResult.PARTIAL_PERSISTENCE
                : step.result());
        }
        // Readback confirmation: the persisted values must equal the target.
        return read().thenApply(readback -> {
            if (value.equals(readback)) {
                confirmed = value;
                return ConfigBindingResult.APPLIED;
            }
            return ConfigBindingResult.PARTIAL_PERSISTENCE;
        });
    }

    private static ConfigBindingResult map(final ConfigErrorCode code) {
        return switch (code) {
            case REVISION_CONFLICT -> ConfigBindingResult.REVISION_CONFLICT;
            case PERMISSION_DENIED -> ConfigBindingResult.PERMISSION_DENIED;
            case INVALID_VALUE -> ConfigBindingResult.INVALID_VALUE;
            default -> ConfigBindingResult.RUNTIME_UNAVAILABLE;
        };
    }

    private record Step(ConfigBindingResult result, long revision, boolean wroteAny) {
        static Step from(final ConfigWriteResult value, final boolean previous) {
            return value.written()
                ? new Step(null, value.revision(), true)
                : new Step(map(value.error().orElseThrow().code()), value.revision(), previous);
        }
    }

    public void enable() {
        enabled = true;
    }

    public void disable() {
        enabled = false;
    }

    public void shutdown() {
        disable();
        initialized = false;
        registry = null;
    }

    /** Last value confirmed by a read or a successful update (may be null before the first read). */
    public WebDavConfig confirmed() {
        return confirmed;
    }

    private static WebDavConfig toConfig(
        final ConfigReadResult<Boolean> enabledRead,
        final ConfigReadResult<String> urlRead,
        final ConfigReadResult<String> usernameRead,
        final ConfigReadResult<String> passwordRead,
        final ConfigReadResult<String> remotePathRead,
        final ConfigReadResult<Boolean> verifyTlsRead,
        final ConfigReadResult<Integer> retryMaxRead,
        final ConfigReadResult<Integer> retryBaseRead,
        final ConfigReadResult<Integer> timeoutRead
    ) {
        for (ConfigReadResult<?> read : List.of(
            enabledRead, urlRead, usernameRead, passwordRead, remotePathRead,
            verifyTlsRead, retryMaxRead, retryBaseRead, timeoutRead
        )) {
            if (read.error().isPresent()) {
                return null; // fail closed: an unreadable key disables the target
            }
        }
        final URI url;
        try {
            url = URI.create(urlRead.value().value());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        try {
            return new WebDavConfig(
                enabledRead.value().value(),
                url,
                usernameRead.value().value(),
                passwordRead.value().value(),
                remotePathRead.value().value(),
                verifyTlsRead.value().value(),
                retryMaxRead.value().value(),
                retryBaseRead.value().value(),
                timeoutRead.value().value()
            );
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static ConfigBindingResult registration(final Throwable failure) {
        return failure instanceof ConfigRegistrationException registration
            && registration.error() == ConfigRegistrationError.PERMISSION_DENIED
            ? ConfigBindingResult.PERMISSION_DENIED
            : ConfigBindingResult.RUNTIME_UNAVAILABLE;
    }

    private static Throwable unwrap(final Throwable value) {
        return value.getCause() == null ? value : value.getCause();
    }

    private static CompletionStage<WebDavConfig> completedConfig(final WebDavConfig value) {
        return CompletableFuture.completedStage(value);
    }

    private static CompletionStage<ConfigBindingResult> completedResult(final ConfigBindingResult value) {
        return CompletableFuture.completedStage(value);
    }

    private static CompletionStage<Step> completedStep(final Step value) {
        return CompletableFuture.completedStage(value);
    }
}
