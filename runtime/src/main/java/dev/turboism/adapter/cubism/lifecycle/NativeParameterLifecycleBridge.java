package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Static bridge called by the verified native parameter method instrumentation. */
public final class NativeParameterLifecycleBridge {

    private static final AtomicReference<NativeParameterLifecycleBridge> INSTALLED =
        new AtomicReference<>();
    private static final ThreadLocal<ArrayDeque<NativeFrame>> NATIVE_FRAMES =
        ThreadLocal.withInitial(ArrayDeque::new);

    private final ParameterLifecycleCoordinator coordinator;
    private final CubismModelAccess modelAccess;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<Long, ParameterLifecycleCoordinator.NativeInvocation> invocations =
        new ConcurrentHashMap<>();

    public NativeParameterLifecycleBridge(final ParameterLifecycleCoordinator coordinator) {
        this(coordinator, () -> {
            throw new IllegalStateException("Native parameter model access is unavailable.");
        });
    }

    public NativeParameterLifecycleBridge(
        final ParameterLifecycleCoordinator coordinator,
        final CubismModelAccess modelAccess
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.modelAccess = Objects.requireNonNull(modelAccess, "modelAccess");
    }

    /**
     * Publishes the bridge instance the injected native instrumentation calls into, replacing any
     * previously installed one.
     *
     * @param bridge the bridge to make current
     * @throws NullPointerException when {@code bridge} is null
     */
    public static void install(final NativeParameterLifecycleBridge bridge) {
        INSTALLED.set(Objects.requireNonNull(bridge, "bridge"));
    }

    /**
     * Clears the installed bridge only if {@code bridge} is still the current one, so a later
     * installation is never removed by a stale uninstall. Instrumented call sites then fail open.
     *
     * @param bridge the bridge expected to be current
     */
    public static void uninstall(final NativeParameterLifecycleBridge bridge) {
        INSTALLED.compareAndSet(bridge, null);
    }

    /** Entry injected into the verified native parameter-palette operation. */
    public static float beforeNative(final String parameterId, final float requestedValue) {
        final NativeParameterLifecycleBridge bridge = INSTALLED.get();
        ParameterLifecycleCoordinator.NativeInvocation invocation = null;
        float effectiveValue = requestedValue;
        if (bridge != null) {
            try {
                final Parameter parameter = bridge.modelAccess.active().parameters().find(
                    new ParameterId(parameterId)
                );
                invocation = bridge.coordinator.beginNative(parameter, requestedValue);
                effectiveValue = invocation.effectiveValue();
            } catch (Throwable ignored) {
                // Native ingress must fail open when model identity or lifecycle state is unavailable.
            }
        }
        NATIVE_FRAMES.get().push(new NativeFrame(bridge, invocation));
        return effectiveValue;
    }

    /** Normal return injected into the verified native parameter-palette operation. */
    public static void afterNative() {
        completeNativeFrame(true);
    }

    /** Exceptional return injected into the verified native parameter-palette operation. */
    public static void failedNative() {
        completeNativeFrame(false);
    }

    private static void completeNativeFrame(final boolean succeeded) {
        final ArrayDeque<NativeFrame> frames = NATIVE_FRAMES.get();
        final NativeFrame frame = frames.poll();
        if (frames.isEmpty()) {
            NATIVE_FRAMES.remove();
        }
        if (frame == null || frame.bridge() == null || frame.invocation() == null) {
            return;
        }
        try {
            frame.bridge().coordinator.completeNative(frame.invocation(), succeeded);
        } catch (Throwable ignored) {
            // Native completion must never destabilize Cubism.
        }
    }

    /**
     * Token-based entry for instrumented parameter writes: opens a lifecycle invocation for the write
     * and returns the token that identifies it.
     *
     * @param parameter the parameter being written
     * @param requestedValue value requested before interception
     * @return an invocation token to pass to {@code effectiveValue} and {@code after}/{@code failed},
     *     or {@code 0} when no bridge is installed, meaning the write proceeds unobserved
     */
    public static long before(final Parameter parameter, final float requestedValue) {
        final NativeParameterLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) {
            return 0L;
        }
        return bridge.begin(parameter, requestedValue);
    }

    /**
     * Reads back the value the interceptors settled on for an open invocation.
     *
     * @param token token returned by {@code before}
     * @return the effective value the host should actually write
     * @throws IllegalStateException when the token is {@code 0}, no bridge is installed, or the
     *     invocation has already been completed
     */
    public static float effectiveValue(final long token) {
        if (token == 0L) {
            throw new IllegalStateException("Native parameter lifecycle bridge is unavailable.");
        }
        final NativeParameterLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) {
            throw new IllegalStateException("Native parameter lifecycle bridge is unavailable.");
        }
        return bridge.invocation(token).effectiveValue();
    }

    /**
     * Normal-return completion for a token-based invocation: publishes success and releases the token.
     * A {@code 0} token, an unknown token, or an absent bridge is a no-op.
     *
     * @param token token returned by {@code before}
     */
    public static void after(final long token) {
        complete(token, true);
    }

    /**
     * Exceptional-return completion for a token-based invocation: publishes failure and releases the
     * token. A {@code 0} token, an unknown token, or an absent bridge is a no-op.
     *
     * @param token token returned by {@code before}
     */
    public static void failed(final long token) {
        complete(token, false);
    }

    private static void complete(final long token, final boolean succeeded) {
        if (token == 0L) {
            return;
        }
        final NativeParameterLifecycleBridge bridge = INSTALLED.get();
        if (bridge != null) {
            bridge.completeInvocation(token, succeeded);
        }
    }

    private long begin(final Parameter parameter, final float requestedValue) {
        final long token = sequence.incrementAndGet();
        invocations.put(token, coordinator.beginNative(parameter, requestedValue));
        return token;
    }

    private ParameterLifecycleCoordinator.NativeInvocation invocation(final long token) {
        final ParameterLifecycleCoordinator.NativeInvocation invocation = invocations.get(token);
        if (invocation == null) {
            throw new IllegalStateException("Native parameter lifecycle token is unavailable.");
        }
        return invocation;
    }

    private void completeInvocation(final long token, final boolean succeeded) {
        final ParameterLifecycleCoordinator.NativeInvocation invocation = invocations.remove(token);
        if (invocation == null) {
            return;
        }
        coordinator.completeNative(invocation, succeeded);
    }

    private record NativeFrame(
        NativeParameterLifecycleBridge bridge,
        ParameterLifecycleCoordinator.NativeInvocation invocation
    ) {
    }
}
