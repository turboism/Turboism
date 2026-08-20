package dev.turboism.sdk.script;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Runtime-managed script discovery and execution. */
@PreviewApi
public interface ScriptService {

    List<ScriptDescriptor> list();

    Optional<ScriptDescriptor> find(ScriptId id);

    ScriptRunHandle run(ScriptRunRequest request);

    default boolean available() {
        return true;
    }

    static ScriptService unavailable() {
        return Unavailable.INSTANCE;
    }

    @PreviewApi
    enum Unavailable implements ScriptService {
        INSTANCE;

        @Override
        public List<ScriptDescriptor> list() {
            return List.of();
        }

        @Override
        public Optional<ScriptDescriptor> find(final ScriptId id) {
            return Optional.empty();
        }

        @Override
        public ScriptRunHandle run(final ScriptRunRequest request) {
            final ScriptExecutionId id = new ScriptExecutionId("unavailable");
            final ScriptRunResult result = ScriptRunResult.failure(
                id,
                ScriptRunStatus.REJECTED,
                "SCRIPT_RUNTIME_UNAVAILABLE",
                "Script runtime is unavailable.",
                ""
            );
            return new ScriptRunHandle() {
                @Override
                public ScriptExecutionId id() {
                    return id;
                }

                @Override
                public java.util.concurrent.CompletionStage<ScriptRunResult> completion() {
                    return CompletableFuture.completedFuture(result);
                }

                @Override
                public boolean cancel() {
                    return false;
                }
            };
        }

        @Override
        public boolean available() {
            return false;
        }
    }
}
