package dev.turboism.automesh.host;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Loader-neutral bridge from the transformed Auto Mesh funnels to runtime dispatch policy.
 *
 * <p>Transformed host bytecode sees only {@link System} properties, {@link Function}, and the
 * argument array it hands over. Runtime callback ownership stays on the parent loader, because the
 * host loader cannot see runtime types. The installer drops the callbacks only after it has
 * restored the host bytes, so transformed code can never call a removed callback.</p>
 *
 * <p>Every failure answers "not handled", which is what keeps the native algorithm reachable: a
 * broken policy, a buggy plugin algorithm, or a re-entrant call must never deny the user the
 * Editor's own Auto Mesh Generate. Each failure also reaches the diagnostic sink, so a silent
 * fallback never hides the reason.</p>
 */
public final class NativeAutoMeshBridge {

    /** Property prefix of every guard callback key; transformed bytecode embeds these literals. */
    public static final String PROPERTY_PREFIX = "turboism.automesh.guard.";

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();
    private static final AtomicReference<Consumer<String>> DIAGNOSTICS = new AtomicReference<>();

    /**
     * Per-thread nesting flag. The funnels run on the Editor UI thread, so a custom run that
     * triggers one of them again lands on the same thread and is answered "not handled" instead of
     * recursing.
     */
    private static final ThreadLocal<Boolean> DISPATCHING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final String NO_DISPATCH_MESSAGE =
            "auto mesh guard: nested funnel invocation ignored; the native algorithm runs";

    private NativeAutoMeshBridge() {}

    /**
     * Returns the exact property key the transformed bytecode of {@code funnel} reads.
     *
     * @param funnel the guarded funnel
     * @return the loader-neutral callback key of that funnel
     */
    public static String callbackKey(final AutoMeshFunnel funnel) {
        return PROPERTY_PREFIX + Objects.requireNonNull(funnel, "funnel").token();
    }

    /**
     * Installs one handler and publishes the loader-neutral callback of every funnel.
     *
     * @param handler runtime dispatch policy every funnel reports to
     * @param diagnostics sink for swallowed failures; it must not throw
     * @return a revocation that removes the callbacks and the handler
     * @throws IllegalStateException when a bridge is already installed or a key is taken
     */
    public static Registration install(final Handler handler, final Consumer<String> diagnostics) {
        final Handler requested = Objects.requireNonNull(handler, "handler");
        final Consumer<String> requestedDiagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (!HANDLER.compareAndSet(null, requested)) {
            throw new IllegalStateException("auto mesh guard bridge is already installed");
        }
        final Properties properties = System.getProperties();
        final Function<Object, Object> callback = NativeAutoMeshBridge::dispatch;
        final String[] keys = {callbackKey(AutoMeshFunnel.MODELING_MODE), callbackKey(AutoMeshFunnel.MESH_EDITOR_MODE)};
        try {
            synchronized (properties) {
                for (final String key : keys) {
                    if (properties.containsKey(key)) {
                        throw new IllegalStateException("auto mesh guard callback property is already installed");
                    }
                }
                for (final String key : keys) {
                    properties.put(key, callback);
                }
            }
        } catch (RuntimeException | Error failure) {
            FatalErrors.rethrowIfFatal(failure);
            HANDLER.compareAndSet(requested, null);
            synchronized (properties) {
                for (final String key : keys) {
                    properties.remove(key, callback);
                }
            }
            throw failure;
        }
        DIAGNOSTICS.set(requestedDiagnostics);
        return new Registration() {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                synchronized (properties) {
                    for (final String key : keys) {
                        properties.remove(key, callback);
                    }
                }
                DIAGNOSTICS.compareAndSet(requestedDiagnostics, null);
                HANDLER.compareAndSet(requested, null);
            }
        };
    }

    /**
     * Answers one funnel confirmation.
     *
     * <p>The property value is the funnel's argument array; the funnel infers nothing from it. A
     * missing or non-array value answers "not handled" so the native body keeps its behaviour.</p>
     */
    private static Object dispatch(final Object arguments) {
        final Handler handler = HANDLER.get();
        if (handler == null) {
            return Boolean.FALSE;
        }
        if (!(arguments instanceof Object[] hostArguments)) {
            diagnostic("auto mesh guard: funnel handed an unexpected argument, the native algorithm runs");
            return Boolean.FALSE;
        }
        final AutoMeshFunnel funnel = funnelOf(hostArguments);
        if (funnel == null) {
            diagnostic("auto mesh guard: funnel identity is unknown, the native algorithm runs");
            return Boolean.FALSE;
        }
        if (DISPATCHING.get()) {
            diagnostic(NO_DISPATCH_MESSAGE);
            return Boolean.FALSE;
        }
        DISPATCHING.set(Boolean.TRUE);
        try {
            return Boolean.TRUE.equals(handler.handle(funnel, hostArguments));
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("auto mesh guard: policy failed and the native algorithm runs: "
                    + failure.getClass().getName() + ": " + failure.getMessage());
            return Boolean.FALSE;
        } finally {
            DISPATCHING.remove();
        }
    }

    /**
     * Returns the funnel the transformer encoded in the argument array, or {@code null}.
     *
     * <p>Transformed bytecode passes a private token as the first element, because one callback
     * cannot know which funnel it serves from the arguments alone.</p>
     */
    private static AutoMeshFunnel funnelOf(final Object[] hostArguments) {
        if (hostArguments.length == 0) {
            return null;
        }
        if (!(hostArguments[0] instanceof String token)) {
            return null;
        }
        for (final AutoMeshFunnel funnel : AutoMeshFunnel.values()) {
            if (funnel.token().equals(token)) {
                return funnel;
            }
        }
        return null;
    }

    private static void diagnostic(final String message) {
        final Consumer<String> sink = DIAGNOSTICS.get();
        if (sink == null) {
            return;
        }
        try {
            sink.accept(message);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // A diagnostic sink must never influence whether the native algorithm runs.
        }
    }

    /** Runtime dispatch policy the transformed funnels report to. */
    @FunctionalInterface
    public interface Handler {

        /**
         * Decides whether the custom path owns one confirmation.
         *
         * @param funnel the funnel that was entered
         * @param hostArguments the funnel token followed by the live receiver and declared
         *     arguments; the values are opaque to this layer
         * @return {@code true} when a custom run owns the confirmation, so the native body must not
         *     run; {@code null} and {@code false} both mean the native body runs
         */
        Boolean handle(AutoMeshFunnel funnel, Object[] hostArguments);
    }
}
