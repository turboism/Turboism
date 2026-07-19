package dev.turboism.plugin.renderopt.b1.application;

import dev.turboism.plugin.renderopt.b1.domain.RenderOptInState;
import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigRegistrationError;
import dev.turboism.sdk.config.ConfigRegistrationException;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.PluginConfigRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class RenderPreferenceBinding {

    public static final String CONFIG_ID = "render-opt.preference";
    public static final String CONFIG_PATH = "render-opt/preference.cfg";
    private static final ConfigKey<Boolean> REQUESTED = new ConfigKey<>(
        CONFIG_ID, "requested", false, ConfigCodecs.booleanValue()
    );
    private static final ConfigSchema SCHEMA = new ConfigSchema(CONFIG_ID, CONFIG_PATH, 1, List.of(REQUESTED));

    private PluginConfigRegistry registry;
    private RenderOptInState confirmed = RenderOptInState.defaults();
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
        return registry.read(REQUESTED).handle((read, failure) -> {
            if (!enabled || epoch != activeEpoch) {
                return ConfigBindingResult.DISABLED;
            }
            if (failure != null) {
                return ConfigBindingResult.RUNTIME_UNAVAILABLE;
            }
            if (read.error().isPresent()) {
                return map(read.error().orElseThrow().code());
            }
            confirmed = confirmed.setRequested(read.value().value());
            revision = read.value().revision();
            return ConfigBindingResult.APPLIED;
        });
    }

    public CompletionStage<ConfigBindingResult> setRequested(final boolean value) {
        if (!enabled || registry == null) {
            return completed(ConfigBindingResult.DISABLED);
        }
        final RenderOptInState next = confirmed.setRequested(value);
        if (next.equals(confirmed)) {
            return completed(ConfigBindingResult.UNCHANGED);
        }
        final long activeEpoch = epoch;
        return registry.write(REQUESTED, value, revision).handle((write, failure) -> {
            if (!enabled || epoch != activeEpoch) {
                return ConfigBindingResult.DISABLED;
            }
            if (failure != null) {
                return ConfigBindingResult.RUNTIME_UNAVAILABLE;
            }
            if (!write.written()) {
                return map(write.error().orElseThrow().code());
            }
            revision = write.revision();
            confirmed = next;
            return ConfigBindingResult.APPLIED;
        });
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

    public RenderOptInState confirmed() {
        return confirmed;
    }

    private static ConfigBindingResult registrationFailure(final Throwable failure) {
        return failure instanceof ConfigRegistrationException registration
            && registration.error() == ConfigRegistrationError.PERMISSION_DENIED
            ? ConfigBindingResult.PERMISSION_DENIED
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
}
