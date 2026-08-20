package dev.turboism.script;

import dev.turboism.graal.GraalHostManager;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.script.ScriptDescriptor;
import dev.turboism.sdk.script.ScriptExecutionId;
import dev.turboism.sdk.script.ScriptFailure;
import dev.turboism.sdk.script.ScriptId;
import dev.turboism.sdk.script.ScriptRunHandle;
import dev.turboism.sdk.script.ScriptRunRequest;
import dev.turboism.sdk.script.ScriptRunResult;
import dev.turboism.sdk.script.ScriptRunStatus;
import dev.turboism.sdk.script.ScriptService;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Plugin-scoped view of the global user script registry and shared Graal host. */
public final class RuntimeScriptService implements ScriptService {

    private final ScriptRegistry registry;
    private final PluginContext context;
    private final DisposableScope scope;
    private final GraalHostManager host;
    private final Consumer<String> diagnostics;

    public RuntimeScriptService(
        final Path turboismHome,
        final PluginContext context,
        final DisposableScope scope,
        final GraalHostManager host,
        final Consumer<String> diagnostics
    ) {
        this.registry = new ScriptRegistry(turboismHome, diagnostics);
        this.context = Objects.requireNonNull(context, "context");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.host = Objects.requireNonNull(host, "host");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public List<ScriptDescriptor> list() {
        return registry.discover().stream().map(ScriptRegistry.InstalledScript::descriptor).toList();
    }

    @Override
    public Optional<ScriptDescriptor> find(final ScriptId id) {
        return registry.find(Objects.requireNonNull(id, "id"))
            .map(ScriptRegistry.InstalledScript::descriptor);
    }

    @Override
    public ScriptRunHandle run(final ScriptRunRequest request) {
        Objects.requireNonNull(request, "request");
        final Optional<ScriptRegistry.InstalledScript> installed = registry.find(request.scriptId());
        if (installed.isEmpty()) {
            return completedFailure(
                new ScriptExecutionId("missing-" + request.scriptId().value()),
                ScriptRunStatus.REJECTED,
                "SCRIPT_NOT_FOUND",
                "Installed script was not found: " + request.scriptId()
            );
        }
        final ScriptRegistry.InstalledScript script = installed.orElseThrow();
        final GraalHostManager.Execution execution = host.submit(
            script.descriptor().id().value(),
            script.source(),
            request.arguments(),
            new RuntimeScriptHostBridge(context, script.descriptor())
        );
        final RuntimeHandle handle = new RuntimeHandle(execution);
        final Registration registration;
        try {
            registration = scope.register(handle);
        } catch (IllegalStateException closed) {
            execution.cancel();
            return completedFailure(
                execution.id(),
                ScriptRunStatus.CANCELLED,
                "SCRIPT_PLUGIN_SCOPE_CLOSED",
                "Calling plugin scope is already closed."
            );
        }
        handle.completion().whenComplete((ignored, failure) -> registration.close());
        return handle;
    }

    @Override
    public boolean available() {
        return host.configured();
    }

    private ScriptRunHandle completedFailure(
        final ScriptExecutionId id,
        final ScriptRunStatus status,
        final String code,
        final String message
    ) {
        diagnostics.accept(code + ": " + message);
        final ScriptRunResult result = ScriptRunResult.failure(id, status, code, message, "");
        return new ScriptRunHandle() {
            @Override
            public ScriptExecutionId id() {
                return id;
            }

            @Override
            public CompletionStage<ScriptRunResult> completion() {
                return CompletableFuture.completedFuture(result);
            }

            @Override
            public boolean cancel() {
                return false;
            }
        };
    }

    private static ScriptRunResult map(
        final ScriptExecutionId id,
        final GraalHostManager.TransportResult result
    ) {
        return switch (result.status()) {
            case SUCCEEDED -> ScriptRunResult.success(id, result.output());
            case CANCELLED -> ScriptRunResult.failure(
                id, ScriptRunStatus.CANCELLED,
                fallback(result.code(), "SCRIPT_CANCELLED"), result.message(), result.output()
            );
            case REJECTED -> ScriptRunResult.failure(
                id, ScriptRunStatus.REJECTED,
                fallback(result.code(), "SCRIPT_REJECTED"), result.message(), result.output()
            );
            case TIMED_OUT -> ScriptRunResult.failure(
                id, ScriptRunStatus.TIMED_OUT,
                fallback(result.code(), "SCRIPT_TIMED_OUT"), result.message(), result.output()
            );
            case FAILED -> ScriptRunResult.failure(
                id, ScriptRunStatus.FAILED,
                fallback(result.code(), "SCRIPT_FAILED"), result.message(), result.output()
            );
        };
    }

    private static String fallback(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class RuntimeHandle implements ScriptRunHandle {
        private final GraalHostManager.Execution execution;
        private final CompletionStage<ScriptRunResult> completion;

        private RuntimeHandle(final GraalHostManager.Execution execution) {
            this.execution = Objects.requireNonNull(execution, "execution");
            this.completion = execution.completion().thenApply(result -> map(execution.id(), result));
        }

        @Override
        public ScriptExecutionId id() {
            return execution.id();
        }

        @Override
        public CompletionStage<ScriptRunResult> completion() {
            return completion;
        }

        @Override
        public boolean cancel() {
            return execution.cancel();
        }
    }
}
