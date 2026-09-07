package dev.turboism.core.plugin.context;

import dev.turboism.sdk.cubism.CubismEditorApiUnavailableException;
import dev.turboism.sdk.CubismEditor;

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
import java.util.function.Supplier;

/** Applies exact Cubism Editor availability policy to one plugin's SDK object graph. */
final class CubismEditorApiAvailabilityInterceptor {

    private final Supplier<Optional<String>> activeVersion;
    // Both sides must be weak: a strongly cached proxy retains its delegate through Handler.
    private final ReferenceQueue<Object> collectedDelegates = new ReferenceQueue<>();
    private final Map<IdentityWeakReference, Map<Class<?>, WeakReference<Object>>> proxies =
        new LinkedHashMap<>();

    CubismEditorApiAvailabilityInterceptor(final Supplier<Optional<String>> activeVersion) {
        this.activeVersion = Objects.requireNonNull(activeVersion, "activeVersion");
    }

    CubismContextServices intercept(final CubismContextServices services) {
        Objects.requireNonNull(services, "services");
        return new CubismContextServices(
            wrap(services.cubismFacade(), dev.turboism.sdk.cubism.CubismFacade.class),
            wrap(services.parameterQueryService(), dev.turboism.sdk.cubism.service.query.ParameterQueryService.class),
            wrap(services.selectionQueryService(), dev.turboism.sdk.cubism.service.query.SelectionQueryService.class),
            wrap(services.modelHierarchyQueryService(), dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService.class),
            wrap(services.cubismReadCapabilityService(), dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService.class),
            wrap(services.modelObjectService(), dev.turboism.sdk.cubism.model.ModelObjectService.class),
            wrap(services.physicsEditorService(), dev.turboism.sdk.cubism.physics.PhysicsEditorService.class),
            wrap(services.cubismClipMaskService(), dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.class),
            wrap(services.editorCommandService(), dev.turboism.sdk.cubism.command.EditorCommandService.class),
            wrap(services.backupService(), dev.turboism.sdk.cubism.backup.EditorAutoBackupService.class)
        );
    }

    <T> T wrapForTesting(final T value, final Class<T> sdkInterface) {
        return wrap(value, sdkInterface);
    }

    private <T> T wrap(final T value, final Class<T> sdkInterface) {
        return sdkInterface.cast(wrapInterface(value, sdkInterface));
    }

    private Object wrapInterface(final Object value, final Class<?> sdkInterface) {
        if (value == null || !isInterceptableSdkInterface(sdkInterface)) {
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
            if (existing != null) return existing;
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

        @Override public int hashCode() {
            return identityHash;
        }

        @Override public boolean equals(final Object other) {
            if (this == other) return true;
            final Object value = get();
            return value != null && other instanceof IdentityWeakReference reference
                && value == reference.get();
        }
    }

    private final class Handler implements InvocationHandler {
        private final CubismEditorApiAvailabilityInterceptor owner = CubismEditorApiAvailabilityInterceptor.this;
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
                return objectMethod(proxy, method, arguments);
            }
            enforce(method);
            final Object[] unwrapped = unwrapArguments(arguments);
            try {
                final Object result = method.invoke(delegate, unwrapped);
                return wrapValue(result, method.getGenericReturnType());
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        private Object objectMethod(final Object proxy, final Method method, final Object[] arguments) {
            return switch (method.getName()) {
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "CubismEditorApiProxy[" + sdkInterface.getName() + "]";
                default -> throw new IllegalStateException("Unexpected Object method: " + method);
            };
        }
    }

    private void enforce(final Method method) {
        final CubismEditorAvailabilityPolicy.Resolution resolution =
            CubismEditorAvailabilityPolicy.resolve(method);
        if (!resolution.restricted()) {
            return;
        }
        final Optional<String> current = Objects.requireNonNull(
            activeVersion.get(), "activeVersion.get()"
        );
        if (current.isPresent()
            && resolution.supportedVersions().contains(current.orElseThrow())) {
            return;
        }
        throw new CubismEditorApiUnavailableException(
            apiId(method), current, resolution.supportedVersions()
        );
    }

    private Object wrapValue(final Object value, final Type declaredType) {
        if (value == null) {
            return null;
        }
        if (declaredType instanceof Class<?> declaredClass) {
            if (declaredClass.isInterface() && isInterceptableSdkInterface(declaredClass)) {
                return wrapInterface(value, declaredClass);
            }
            if (declaredClass.isArray()) {
                return mapArray(value, declaredClass.getComponentType(), declaredClass.getComponentType());
            }
            return value;
        }
        if (declaredType instanceof GenericArrayType arrayType) {
            final Type component = arrayType.getGenericComponentType();
            final Class<?> componentClass = rawClass(component);
            return mapArray(value, componentClass, component);
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
                    if (mapped == null) mapped = new ArrayList<>(source);
                    mapped.set(index, wrapped);
                }
                index++;
            }
            // Preserve immutable value/snapshot caches when there is nothing to proxy.
            // Lists containing restricted SDK objects still get a wrapped, read-only copy.
            return mapped == null ? value : Collections.unmodifiableList(mapped);
        }
        if (raw == Set.class) {
            final LinkedHashSet<Object> mapped = new LinkedHashSet<>();
            for (Object item : (Set<?>) value) mapped.add(wrapValue(item, types[0]));
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
        if (raw.isInterface() && isInterceptableSdkInterface(raw)) {
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
        if (value == null) return null;
        if (Proxy.isProxyClass(value.getClass())) {
            final InvocationHandler handler = Proxy.getInvocationHandler(value);
            if (handler instanceof Handler owned && owned.owner == this) {
                return owned.delegate;
            }
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(this::unwrapValue);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::unwrapValue).toList();
        }
        if (value instanceof Set<?> set) {
            final LinkedHashSet<Object> unwrapped = new LinkedHashSet<>();
            for (Object item : set) unwrapped.add(unwrapValue(item));
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

    private static boolean isInterceptableSdkInterface(final Class<?> type) {
        if (!type.isInterface()) return false;
        final Package typePackage = type.getPackage();
        // Every SDK interface is part of the plugin object graph.  Unannotated
        // carriers (CubismModelAccess, Parts, Parameters, Drawables, ...) would
        // otherwise let annotated descendants escape exact-version enforcement.
        return (typePackage != null && typePackage.getName().startsWith("dev.turboism.sdk."))
            || hasTypeAvailability(type, new LinkedHashSet<>())
            || declaresAnnotatedMethod(type);
    }

    private static boolean declaresAnnotatedMethod(final Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.isAnnotationPresent(CubismEditor.class)) return true;
        }
        return false;
    }

    private static boolean hasTypeAvailability(final Class<?> type, final Set<Class<?>> visited) {
        if (!visited.add(type)) return false;
        if (type.isAnnotationPresent(CubismEditor.class)) return true;
        for (Class<?> parent : type.getInterfaces()) {
            if (hasTypeAvailability(parent, visited)) return true;
        }
        return false;
    }

    private static Class<?> rawClass(final Type type) {
        if (type instanceof Class<?> value) return value;
        if (type instanceof ParameterizedType value) return rawClass(value.getRawType());
        throw new IllegalStateException("Unsupported SDK carrier type: " + type.getTypeName());
    }

    private static String apiId(final Method method) {
        final ArrayList<String> parameters = new ArrayList<>();
        for (Class<?> parameter : method.getParameterTypes()) parameters.add(parameter.getTypeName());
        return method.getDeclaringClass().getName() + "#" + method.getName()
            + "(" + String.join(",", parameters) + ")";
    }
}
