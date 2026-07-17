package dev.turboism.config;

import dev.turboism.failure.RuntimeFailure;
import dev.turboism.failure.RuntimeFailureDomain;
import dev.turboism.failure.RuntimeFailureSink;
import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigRegistrationError;
import dev.turboism.sdk.config.ConfigSchemaValidationException;
import dev.turboism.sdk.config.ConfigWriteResult;

/** Reports typed-config failures without exposing values or storage paths. */
final class TypedConfigFailureReporter {

    private static final String REGISTRATION_OPERATION = "config.registerSchema";

    private final String pluginId;
    private final RuntimeFailureSink failureSink;

    TypedConfigFailureReporter(
        final String pluginId,
        final RuntimeFailureSink failureSink
    ) {
        this.pluginId = pluginId;
        this.failureSink = RuntimeFailureSink.require(failureSink);
    }

    void schemaValidationFailed(final ConfigSchemaValidationException failure) {
        record(
            failure.error().name(),
            "validation",
            REGISTRATION_OPERATION,
            null,
            "Typed config schema validation failed safely."
        );
    }

    void schemaRegistrationFailed(
        final ConfigRegistrationError error,
        final String permissionId
    ) {
        record(
            error.name(),
            "registration",
            REGISTRATION_OPERATION,
            permissionId,
            "Typed config schema registration failed safely."
        );
    }

    <T> ConfigReadResult<T> observe(
        final ConfigReadResult<T> result,
        final String operationId,
        final String permissionId
    ) {
        result.error().ifPresent(error -> record(
            error.code().name(),
            "typed-config",
            operationId,
            permissionId,
            error.message()
        ));
        return result;
    }

    ConfigWriteResult observe(
        final ConfigWriteResult result,
        final String operationId,
        final String permissionId
    ) {
        result.error().ifPresent(error -> record(
            error.code().name(),
            "typed-config",
            operationId,
            permissionId,
            error.message()
        ));
        return result;
    }

    private void record(
        final String code,
        final String phase,
        final String operationId,
        final String permissionId,
        final String message
    ) {
        failureSink.record(RuntimeFailureDomain.CONFIG, new RuntimeFailure(
            code,
            "ERROR",
            phase,
            pluginId,
            operationId,
            permissionId,
            message,
            null,
            1
        ));
    }
}
