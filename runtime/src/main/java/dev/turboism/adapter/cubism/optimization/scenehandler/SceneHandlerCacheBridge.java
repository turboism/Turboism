package dev.turboism.adapter.cubism.optimization.scenehandler;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Caches depth-first {@code GMouseHandlerComponent} lists per scene-graph root and invalidates
 * selectively when mouse-handler topology mutates.
 */
public final class SceneHandlerCacheBridge implements AutoCloseable {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.sceneHandlerCache";

    /** Property key for the {@code Function<Object, Object>} cache lookup callback. */
    public static final String GET_PROPERTY = "turboism.scene-handler.get";

    /** Property key for the {@code BiFunction<Object, Object, Object>} cache population callback. */
    public static final String PUT_PROPERTY = "turboism.scene-handler.put";

    /** Property key for the {@code Consumer<Object>} selective topology invalidation callback. */
    public static final String INVALIDATE_PROPERTY = "turboism.scene-handler.invalidate";

    /** Property key exposing diagnostic counters. */
    public static final String STATS_PROPERTY = "turboism.scene-handler.stats";

    private static final long INITIAL_EPOCH = 1L;

    private final Class<?> mouseHandlerType;
    private final Class<?> entityType;
    private final Class<?> entitiesType;
    private final MethodHandle entityComponentsHandle;
    private final MethodHandle componentsListHandle;
    private final MethodHandle entityChildrenHandle;
    private final MethodHandle entitiesListHandle;

    private final AtomicBoolean active = new AtomicBoolean();
    private final Map<Object, CachedEntry> cache = Collections.synchronizedMap(new WeakHashMap<>());
    private final Function<Object, Object> getCallback = this::lookup;
    private final BiFunction<Object, Object, Object> putCallback = this::store;
    private final Consumer<Object> invalidateCallback = this::invalidateIfRelevant;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    private volatile long epoch = INITIAL_EPOCH;
    private Properties installedProperties;

    private long lookups;
    private long hits;
    private long misses;
    private long invalidations;
    private long skippedInvalidations;
    private long observerFailures;

    private record CachedEntry(long epoch, List<?> handlers) {}

    /**
     * Resolves all required host types and member handles on the admitted host class loader.
     *
     * @param loader the admitted host class loader
     * @throws ReflectiveOperationException when any reviewed class or member is unavailable
     */
    public SceneHandlerCacheBridge(final ClassLoader loader) throws ReflectiveOperationException {
        mouseHandlerType = HostHandles.load(loader, SceneHandlerCacheTarget.MOUSE_HANDLER_OWNER);
        entityType = HostHandles.load(loader, SceneHandlerCacheTarget.ENTITY_OWNER);
        entitiesType = HostHandles.load(loader, SceneHandlerCacheTarget.ENTITIES_OWNER);
        final Class<?> componentsType = HostHandles.load(loader, SceneHandlerCacheTarget.COMPONENTS_OWNER);
        entityComponentsHandle = HostHandles.virtual(
                entityType,
                SceneHandlerCacheTarget.GET_COMPONENTS_METHOD,
                SceneHandlerCacheTarget.GET_COMPONENTS_DESCRIPTOR,
                MethodType.methodType(Object.class, Object.class));
        componentsListHandle = HostHandles.virtual(
                componentsType,
                SceneHandlerCacheTarget.COMPONENTS_METHOD,
                SceneHandlerCacheTarget.COMPONENTS_LIST_DESCRIPTOR,
                MethodType.methodType(List.class, Object.class));
        entityChildrenHandle = HostHandles.virtual(
                entityType,
                SceneHandlerCacheTarget.GET_CHILDREN_METHOD,
                SceneHandlerCacheTarget.GET_CHILDREN_DESCRIPTOR,
                MethodType.methodType(Object.class, Object.class));
        entitiesListHandle = HostHandles.virtual(
                entitiesType,
                SceneHandlerCacheTarget.ENTITIES_LIST_METHOD,
                SceneHandlerCacheTarget.ENTITIES_LIST_DESCRIPTOR,
                MethodType.methodType(List.class, Object.class));
    }

    /** Publishes the cache and invalidation callback slots into {@link System#getProperties()}. */
    public synchronized void install() {
        if (active.get()) {
            throw new IllegalStateException("scene handler cache already installed");
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(GET_PROPERTY)
                    || properties.containsKey(PUT_PROPERTY)
                    || properties.containsKey(INVALIDATE_PROPERTY)
                    || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("scene handler cache slots occupied");
            }
            properties.put(GET_PROPERTY, getCallback);
            properties.put(PUT_PROPERTY, putCallback);
            properties.put(INVALIDATE_PROPERTY, invalidateCallback);
            properties.put(STATS_PROPERTY, statistics);
            installedProperties = properties;
            cache.clear();
            epoch++;
            active.set(true);
        }
    }

    @Override
    public synchronized void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        cache.clear();
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(GET_PROPERTY, getCallback);
                properties.remove(PUT_PROPERTY, putCallback);
                properties.remove(INVALIDATE_PROPERTY, invalidateCallback);
                properties.remove(STATS_PROPERTY, statistics);
            }
        }
    }

    /** Returns an immutable snapshot of cache and invalidation counters. */
    public Map<String, Long> snapshot() {
        return Map.of(
                "lookups", lookups,
                "hits", hits,
                "misses", misses,
                "invalidations", invalidations,
                "skippedInvalidations", skippedInvalidations,
                "observerFailures", observerFailures);
    }

    private Object lookup(final Object sceneGraph) {
        lookups++;
        if (!active.get() || sceneGraph == null) {
            misses++;
            return null;
        }
        try {
            final CachedEntry entry = cache.get(sceneGraph);
            if (entry != null && entry.epoch() == epoch) {
                hits++;
                return entry.handlers();
            }
            misses++;
            return null;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            misses++;
            return null;
        }
    }

    private Object store(final Object sceneGraph, final Object handlers) {
        if (!active.get() || sceneGraph == null || !(handlers instanceof List<?> list)) {
            return handlers;
        }
        try {
            cache.put(sceneGraph, new CachedEntry(epoch, List.copyOf(list)));
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
        }
        return handlers;
    }

    private void invalidateIfRelevant(final Object mutatedTarget) {
        if (!active.get()) {
            return;
        }
        try {
            if (containsMouseHandler(mutatedTarget)) {
                epoch++;
                invalidations++;
            } else {
                skippedInvalidations++;
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            epoch++;
            invalidations++;
        }
    }

    private boolean containsMouseHandler(final Object candidate) throws Throwable {
        if (candidate == null) {
            return true;
        }
        if (mouseHandlerType.isInstance(candidate)) {
            return true;
        }
        if (entityType.isInstance(candidate)) {
            final Object components = (Object) entityComponentsHandle.invokeExact(candidate);
            final List<?> componentList = (List<?>) componentsListHandle.invokeExact(components);
            for (int i = 0, size = componentList.size(); i < size; i++) {
                if (mouseHandlerType.isInstance(componentList.get(i))) {
                    return true;
                }
            }
            final Object children = (Object) entityChildrenHandle.invokeExact(candidate);
            final List<?> childList = (List<?>) entitiesListHandle.invokeExact(children);
            for (int i = 0, size = childList.size(); i < size; i++) {
                if (containsMouseHandler(childList.get(i))) {
                    return true;
                }
            }
            return false;
        }
        if (entitiesType.isInstance(candidate)) {
            final List<?> childList = (List<?>) entitiesListHandle.invokeExact(candidate);
            for (int i = 0, size = childList.size(); i < size; i++) {
                if (containsMouseHandler(childList.get(i))) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}
