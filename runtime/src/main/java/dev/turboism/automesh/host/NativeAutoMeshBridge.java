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
 *
 * <p>The attach callback is the second half of the bridge. A constructor anchor reports the
 * container it just built, and the runtime decorates it; the callback answers nothing, because a
 * container that cannot be decorated must still construct exactly like the native one.</p>
 */
public final class NativeAutoMeshBridge {

    /** Property prefix of every guard callback key; transformed bytecode embeds these literals. */
    public static final String PROPERTY_PREFIX = "turboism.automesh.guard.";

    /** Property of the container attach callback; transformed bytecode embeds this literal. */
    public static final String ATTACH_KEY = "turboism.automesh.attach";

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
        final Function<Object, Object> attachCallback = NativeAutoMeshBridge::dispatchAttach;
        final String[] keys = {callbackKey(AutoMeshFunnel.MODELING_MODE), callbackKey(AutoMeshFunnel.MESH_EDITOR_MODE)};
        try {
            synchronized (properties) {
                for (final String key : keys) {
                    if (properties.containsKey(key)) {
                        throw new IllegalStateException("auto mesh guard callback property is already installed");
                    }
                }
                if (properties.containsKey(ATTACH_KEY)) {
                    throw new IllegalStateException("auto mesh attach callback property is already installed");
                }
                for (final String key : keys) {
                    properties.put(key, callback);
                }
                properties.put(ATTACH_KEY, attachCallback);
            }
        } catch (RuntimeException | Error failure) {
            FatalErrors.rethrowIfFatal(failure);
            HANDLER.compareAndSet(requested, null);
            synchronized (properties) {
                for (final String key : keys) {
                    properties.remove(key, callback);
                }
                properties.remove(ATTACH_KEY, attachCallback);
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
                    properties.remove(ATTACH_KEY, attachCallback);
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

    /**
     * Answers one container construction.
     *
     * <p>The property value is the container token followed by the live container instance. A
     * missing handler, an unexpected argument shape or a throwing handler all answer {@code null},
     * so the container finishes its own constructor exactly like the native one.</p>
     */
    private static Object dispatchAttach(final Object arguments) {
        final Handler handler = HANDLER.get();
        if (handler == null) {
            return null;
        }
        if (!(arguments instanceof Object[] hostArguments) || hostArguments.length == 0) {
            diagnostic("auto mesh attach: container handed an unexpected argument, the row is not added");
            return null;
        }
        if (!(hostArguments[0] instanceof String token)) {
            diagnostic("auto mesh attach: container identity is missing, the row is not added");
            return null;
        }
        final AutoMeshContainer container = containerOf(token);
        if (container == null) {
            diagnostic("auto mesh attach: container identity is unknown, the row is not added");
            return null;
        }
        try {
            handler.attach(container, hostArguments.length > 1 ? hostArguments[1] : null);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("auto mesh attach: policy failed and the row is not added: "
                    + failure.getClass().getName() + ": " + failure.getMessage());
        }
        return null;
    }

    /**
     * Returns the container the transformer encoded in the argument array, or {@code null}.
     *
     * <p>Transformed bytecode passes a private token as the first element, because one callback
     * cannot know which container it serves from the arguments alone.</p>
     */
    private static AutoMeshContainer containerOf(final String token) {
        for (final AutoMeshContainer container : AutoMeshContainer.values()) {
            if (container.token().equals(token)) {
                return container;
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

        /**
         * Decorates one freshly constructed native Auto Mesh Generate container.
         *
         * <p>The constructor has finished its own body, so the container is fully built but not
         * yet shown. A failure here must leave the container exactly as the host built it.</p>
         *
         * @param container the container that was just constructed
         * @param instance the live container widget; the values are opaque to this layer
         */
        default void attach(final AutoMeshContainer container, final Object instance) {}
    }
}
