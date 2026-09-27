package dev.turboism.preview;

import dev.turboism.sdk.plugin.PluginContext;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-generation admission gate wrapped around the {@link PluginContext} handed to entrypoints.
 *
 * <p>While the generation is live, every delegated SDK call is counted so retention can wait for
 * admitted work to settle. {@link #fence()} — invoked when a lifecycle deadline fences the
 * generation or teardown begins — makes every later call fail fast with {@link IllegalStateException},
 * including calls on service handles the plugin acquired earlier, because returned
 * {@code dev.turboism.sdk.*} interfaces are wrapped recursively.</p>
 *
 * <p>Two method classes bypass the fence:</p>
 * <ul>
 *   <li>Diagnostic accessors on the context ({@code descriptor}, {@code logger},
 *       {@code localization}, {@code permissions}, {@code diagnostics}) so fenced cleanup and
 *       operator-visible logging keep working.</li>
 *   <li>Terminal release operations ({@code close}, {@code cancel}, {@code dispose},
 *       {@code release}, {@code unregister}) on any wrapped interface. Plugins commonly hand
 *       guarded registrations
 *       back to their own scope via {@code scope.register(registration)}; teardown must still be
 *       able to detach and close them — including blocking closers such as atlas
 *       {@code Registration.close()} — or the generation could never release its resources.
 *       Terminal calls are still counted in-flight so retention waits for a slow close.</li>
 * </ul>
 *
 * <p>{@code disposableScope()} is gated here and additionally fenced by
 * {@code DisposableScope.seal()} for scopes obtained before the fence.</p>
 */
final class PluginGenerationGuard {

    private static final Set<String> DIAGNOSTIC_ACCESSORS = Set.of(
        "descriptor",
        "logger",
        "localization",
        "permissions",
        "diagnostics"
    );

    // Idempotent detach/release verbs used across the SDK surface (Registration.close,
    // FileChooserHistoryService.Registration.unregister, task cancel, handle release/dispose).
    private static final Set<String> TERMINAL_OPERATIONS = Set.of(
        "close",
        "cancel",
        "dispose",
        "release",
        "unregister"
    );

    private final String pluginId;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicInteger inFlight = new AtomicInteger();
    // Both sides must be weak: a strongly cached proxy retains its delegate through Handler,
    // and a strong map would retain every SDK handle ever returned for the plugin's lifetime.
    private final ReferenceQueue<Object> collectedDelegates = new ReferenceQueue<>();
    private final Map<IdentityWeakReference, Map<Class<?>, WeakReference<Object>>> proxies =
        new LinkedHashMap<>();

    PluginGenerationGuard(final String pluginId) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
    }

    /** Returns the gated plugin-facing view of one plugin context. */
    PluginContext wrap(final PluginContext delegate) {
        return (PluginContext) wrapInterface(
            Objects.requireNonNull(delegate, "delegate"),
            PluginContext.class
        );
    }

    /** Test seam mirroring CubismEditorApiAvailabilityInterceptor.wrapForTesting. */
    <T> T wrapForTesting(final T value, final Class<T> sdkInterface) {
        return sdkInterface.cast(wrapInterface(value, sdkInterface));
    }

    /**
     * Denies new SDK admission for this generation. In-flight calls and terminal release
     * operations finish normally; retention waits for them through {@link #drained()}.
     */
    void fence() {
        open.set(false);
    }

    boolean isFenced() {
        return !open.get();
    }

    /** @return {@code true} when no admitted SDK call is currently executing */
    boolean drained() {
        return inFlight.get() == 0;
    }

    int inFlightCalls() {
        return inFlight.get();
    }

    private void enter() {
        if (!open.get()) {
            throw fenced();
        }
        inFlight.incrementAndGet();
        if (!open.get()) {
            inFlight.decrementAndGet();
            throw fenced();
        }
    }

    private void enterTerminal() {
        inFlight.incrementAndGet();
    }

    private void exit() {
        inFlight.decrementAndGet();
    }

    private IllegalStateException fenced() {
        return new IllegalStateException(
            "Plugin generation is closed for new work: " + pluginId
        );
    }

    private Object wrapInterface(final Object value, final Class<?> sdkInterface) {
        if (value == null || !isGatableSdkInterface(sdkInterface)) {
            return value;
        }
        if (Proxy.isProxyClass(value.getClass())
            && Proxy.getInvocationHandler(value) instanceof Handler handler
            && handler.owner == this) {
            return value;
        }
        synchronized (proxies) {
            for (Reference<?> collected; (collected = collectedDelegates.poll()) != null;) {
                proxies.remove(collected);
            }
            final IdentityWeakReference lookup = new IdentityWeakReference(value, null);
            Map<Class<?>, WeakReference<Object>> byInterface = proxies.get(lookup);
            if (byInterface == null) {
                byInterface = new LinkedHashMap<>();
                proxies.put(new IdentityWeakReference(value, collectedDelegates), byInterface);
            }
            final WeakReference<Object> cached = byInterface.get(sdkInterface);
            final Object existing = cached == null ? null : cached.get();
            if (existing != null) {
                return existing;
            }
            final Object created = Proxy.newProxyInstance(
                sdkInterface.getClassLoader(),
                new Class<?>[] {sdkInterface},
                new Handler(value, sdkInterface)
            );
            byInterface.put(sdkInterface, new WeakReference<>(created));
            return created;
        }
    }

    private static final class IdentityWeakReference extends WeakReference<Object> {
        private final int identityHash;

        private IdentityWeakReference(final Object value, final ReferenceQueue<Object> queue) {
            super(value, queue);
            identityHash = System.identityHashCode(value);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            final Object value = get();
            return value != null && other instanceof IdentityWeakReference reference
                && value == reference.get();
        }
    }

    private Object wrapValue(final Object value, final Type declaredType) {
        if (value == null) {
            return null;
        }
        if (declaredType instanceof Class<?> declaredClass) {
            if (declaredClass.isInterface() && isGatableSdkInterface(declaredClass)) {
                return wrapInterface(value, declaredClass);
            }
            if (declaredClass.isArray()) {
                return mapArray(value, declaredClass.getComponentType(), declaredClass.getComponentType());
            }
            return value;
        }
        if (declaredType instanceof GenericArrayType arrayType) {
            final Type component = arrayType.getGenericComponentType();
            return mapArray(value, rawClass(component), component);
        }
        if (!(declaredType instanceof ParameterizedType parameterized)) {
            return value;
        }
        final Class<?> raw = rawClass(parameterized.getRawType());
        final Type[] types = parameterized.getActualTypeArguments();
        if (raw == Optional.class) {
            return ((Optional<?>) value).map(item -> wrapValue(item, types[0]));
        }
        if (raw == List.class || raw == Collection.class) {
            final Collection<?> source = (Collection<?>) value;
            ArrayList<Object> mapped = null;
            int index = 0;
            for (Object item : source) {
                final Object wrapped = wrapValue(item, types[0]);
                if (wrapped != item) {
                    if (mapped == null) {
                        mapped = new ArrayList<>(source);
                    }
                    mapped.set(index, wrapped);
                }
                index++;
            }
            return mapped == null ? value : Collections.unmodifiableList(mapped);
        }
        if (raw == Set.class) {
            final LinkedHashSet<Object> mapped = new LinkedHashSet<>();
            for (Object item : (Set<?>) value) {
                mapped.add(wrapValue(item, types[0]));
            }
            return Collections.unmodifiableSet(mapped);
        }
        if (raw == Map.class) {
            final LinkedHashMap<Object, Object> mapped = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                mapped.put(wrapValue(entry.getKey(), types[0]), wrapValue(entry.getValue(), types[1]));
            }
            return Collections.unmodifiableMap(mapped);
        }
        if (CompletionStage.class.isAssignableFrom(raw)) {
            return ((CompletionStage<?>) value).thenApply(item -> wrapValue(item, types[0]));
        }
        if (raw.isInterface() && isGatableSdkInterface(raw)) {
            return wrapInterface(value, raw);
        }
        return value;
    }

    private Object[] unwrapArguments(final Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return arguments;
        }
        final Object[] unwrapped = arguments.clone();
        for (int index = 0; index < unwrapped.length; index++) {
            unwrapped[index] = unwrapValue(unwrapped[index]);
        }
        return unwrapped;
    }

    private Object unwrapValue(final Object value) {
        if (value == null) {
            return null;
        }
        if (Proxy.isProxyClass(value.getClass())
            && Proxy.getInvocationHandler(value) instanceof Handler handler
            && handler.owner == this) {
            return handler.delegate;
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(this::unwrapValue);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::unwrapValue).toList();
        }
        if (value instanceof Set<?> set) {
            final LinkedHashSet<Object> unwrapped = new LinkedHashSet<>();
            for (Object item : set) {
                unwrapped.add(unwrapValue(item));
            }
            return Collections.unmodifiableSet(unwrapped);
        }
        if (value instanceof Map<?, ?> map) {
            final LinkedHashMap<Object, Object> unwrapped = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                unwrapped.put(unwrapValue(entry.getKey()), unwrapValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(unwrapped);
        }
        if (value.getClass().isArray()) {
            final int length = Array.getLength(value);
            final Object copy = Array.newInstance(value.getClass().getComponentType(), length);
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, unwrapValue(Array.get(value, index)));
            }
            return copy;
        }
        return value;
    }

    private Object mapArray(final Object value, final Class<?> componentClass, final Type componentType) {
        final int length = Array.getLength(value);
        final Object mapped = Array.newInstance(componentClass, length);
        for (int index = 0; index < length; index++) {
            Array.set(mapped, index, wrapValue(Array.get(value, index), componentType));
        }
        return mapped;
    }

    private static boolean isGatableSdkInterface(final Class<?> type) {
        if (!type.isInterface()) {
            return false;
        }
        final Package typePackage = type.getPackage();
        return typePackage != null && typePackage.getName().startsWith("dev.turboism.sdk.");
    }

    private static Class<?> rawClass(final Type type) {
        if (type instanceof Class<?> value) {
            return value;
        }
        if (type instanceof ParameterizedType value) {
            return rawClass(value.getRawType());
        }
        throw new IllegalStateException("Unsupported SDK carrier type: " + type.getTypeName());
    }

    private final class Handler implements InvocationHandler {
        private final PluginGenerationGuard owner = PluginGenerationGuard.this;
        private final Object delegate;
        private final Class<?> sdkInterface;

        private Handler(final Object delegate, final Class<?> sdkInterface) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.sdkInterface = Objects.requireNonNull(sdkInterface, "sdkInterface");
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] arguments)
            throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "PluginGenerationGuard[" + sdkInterface.getName() + "]";
                    default -> throw new IllegalStateException(
                        "Unexpected Object method: " + method
                    );
                };
            }
            // Diagnostic accessors stay available while fenced so cleanup and operator-visible
            // logging keep working.
            if (method.getDeclaringClass() == PluginContext.class
                && DIAGNOSTIC_ACCESSORS.contains(method.getName())
                && method.getParameterCount() == 0) {
                try {
                    return method.invoke(delegate, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
            // Terminal release operations bypass the fence: teardown must be able to detach and
            // close handles acquired before fencing, but they still count toward in-flight work
            // so retention waits for a blocking close to actually return.
            final boolean terminal = TERMINAL_OPERATIONS.contains(method.getName())
                && method.getParameterCount() == 0;
            if (terminal) {
                enterTerminal();
            } else {
                enter();
            }
            try {
                final Object result = method.invoke(delegate, unwrapArguments(arguments));
                return wrapValue(result, method.getGenericReturnType());
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            } finally {
                exit();
            }
        }
    }
}
