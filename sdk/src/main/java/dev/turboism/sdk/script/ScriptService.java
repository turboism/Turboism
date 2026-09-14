package dev.turboism.sdk.script;


import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Runtime-managed, out-of-process JavaScript discovery and execution.
 *
 * <p>Scripts are bounded automation programs, not Java plugin entrypoints. They receive
 * JSON-shaped values through an explicit permission-checked bridge and pay cross-process
 * protocol costs for host calls. Prefer immutable snapshots and bulk operations; use a
 * Java plugin when code needs lifecycle hooks, UI registrations, the complete SDK, reviewed
 * host/native adaptation, or latency-sensitive/per-frame work.</p>
 */
public interface ScriptService {

    /** Returns every discovered script. */
    List<ScriptDescriptor> list();

    /** Returns the descriptor for {@code id}, or empty when no such script exists. */
    Optional<ScriptDescriptor> find(ScriptId id);

    /**
     * Starts {@code request} as an out-of-process execution and returns its handle.
     *
     * @param request the script and arguments to run
     */
    ScriptRunHandle run(ScriptRunRequest request);

    /** Returns whether script execution is available in this runtime. */
    default boolean available() {
        return true;
    }

    /** Returns a fail-closed service: no scripts are listed and runs are rejected. */
    static ScriptService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed implementation returned by {@link #unavailable()}. */
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
