package dev.turboism.adapter.cubism.optimization.scenehandler;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
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
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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
    private static final String OBJECT_INTERNAL = "java/lang/Object";
    private static final String LIST_INTERNAL = "java/util/List";
    private static final String PREDICATE_INTERNAL = "java/util/function/Predicate";
    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final String VOID_DESCRIPTOR = "()V";
    private static final String PREDICATE_INTERNAL_NAME =
            "dev/turboism/adapter/cubism/optimization/scenehandler/SceneHandlerPredicate";
    private static final String SCAN_ENTITY_METHOD = "scanEntity";
    private static final String SCAN_ENTITY_DESCRIPTOR = "(L" + SceneHandlerCacheTarget.ENTITY_OWNER + ";)Z";
    private static final String SCAN_ENTITIES_METHOD = "scanEntities";
    private static final String SCAN_ENTITIES_DESCRIPTOR = "(L" + SceneHandlerCacheTarget.ENTITIES_OWNER + ";)Z";

    private final Predicate<Object> mouseHandlerDetector;

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
     * Resolves all required host types and compiles a direct topology scanner on the host loader.
     *
     * @param loader the admitted host class loader
     * @throws ReflectiveOperationException when any reviewed class or member is unavailable
     */
    public SceneHandlerCacheBridge(final ClassLoader loader) throws ReflectiveOperationException {
        final Class<?> mouseHandlerType = HostHandles.load(loader, SceneHandlerCacheTarget.MOUSE_HANDLER_OWNER);
        final Class<?> entityType = HostHandles.load(loader, SceneHandlerCacheTarget.ENTITY_OWNER);
        final Class<?> entitiesType = HostHandles.load(loader, SceneHandlerCacheTarget.ENTITIES_OWNER);
        final Class<?> componentsType = HostHandles.load(loader, SceneHandlerCacheTarget.COMPONENTS_OWNER);
        final MethodHandle entityComponentsHandle = HostHandles.virtual(
                entityType,
                SceneHandlerCacheTarget.GET_COMPONENTS_METHOD,
                SceneHandlerCacheTarget.GET_COMPONENTS_DESCRIPTOR,
                MethodType.methodType(Object.class, Object.class));
        final MethodHandle componentsListHandle = HostHandles.virtual(
                componentsType,
                SceneHandlerCacheTarget.COMPONENTS_METHOD,
                SceneHandlerCacheTarget.COMPONENTS_LIST_DESCRIPTOR,
                MethodType.methodType(List.class, Object.class));
        final MethodHandle entityChildrenHandle = HostHandles.virtual(
                entityType,
                SceneHandlerCacheTarget.GET_CHILDREN_METHOD,
                SceneHandlerCacheTarget.GET_CHILDREN_DESCRIPTOR,
                MethodType.methodType(Object.class, Object.class));
        final MethodHandle entitiesListHandle = HostHandles.virtual(
                entitiesType,
                SceneHandlerCacheTarget.ENTITIES_LIST_METHOD,
                SceneHandlerCacheTarget.ENTITIES_LIST_DESCRIPTOR,
                MethodType.methodType(List.class, Object.class));

        final Predicate<Object> compiled = compilePredicate(loader);
        this.mouseHandlerDetector = compiled != null
                ? compiled
                : new FallbackDetector(
                        mouseHandlerType,
                        entityType,
                        entitiesType,
                        entityComponentsHandle,
                        componentsListHandle,
                        entityChildrenHandle,
                        entitiesListHandle);
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
            lookups = 0L;
            hits = 0L;
            misses = 0L;
            invalidations = 0L;
            skippedInvalidations = 0L;
            observerFailures = 0L;
            active.set(true);
        }
    }

    /** Removes bridge callbacks from {@link System#getProperties()} and drops cached entries. */
    @Override
    public synchronized void close() {
        active.set(false);
        cache.clear();
        final Properties properties = installedProperties;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(GET_PROPERTY, getCallback);
                properties.remove(PUT_PROPERTY, putCallback);
                properties.remove(INVALIDATE_PROPERTY, invalidateCallback);
                properties.remove(STATS_PROPERTY, statistics);
            }
            installedProperties = null;
        }
    }

    /** Returns an immutable snapshot of cache hit/miss and selective invalidation telemetry. */
    public synchronized Map<String, Long> snapshot() {
        return Map.of(
                "active", active.get() ? 1L : 0L,
                "epoch", epoch,
                "lookups", lookups,
                "hits", hits,
                "misses", misses,
                "invalidations", invalidations,
                "skippedInvalidations", skippedInvalidations,
                "observerFailures", observerFailures,
                "size", (long) cache.size());
    }

    private Object lookup(final Object sceneGraph) {
        if (!active.get() || sceneGraph == null) {
            return null;
        }
        try {
            final long currentEpoch = epoch;
            final CachedEntry entry = cache.get(sceneGraph);
            lookups++;
            if (entry != null && entry.epoch() == currentEpoch) {
                hits++;
                return entry.handlers();
            }
            misses++;
            return null;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
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
            if (mouseHandlerDetector.test(mutatedTarget)) {
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

    @SuppressWarnings("unchecked")
    private static Predicate<Object> compilePredicate(final ClassLoader loader) {
        try {
            final PredicateClassLoader predicateLoader = new PredicateClassLoader(loader);
            final Class<?> predicateClass =
                    predicateLoader.define(PREDICATE_INTERNAL_NAME.replace('/', '.'), buildPredicateClass(loader));
            return (Predicate<Object>) predicateClass.getConstructor().newInstance();
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static byte[] buildPredicateClass(final ClassLoader loader) {
        final ClassWriter writer = HostClassWriters.newFrameWriter(loader);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                PREDICATE_INTERNAL_NAME,
                null,
                OBJECT_INTERNAL,
                new String[] {PREDICATE_INTERNAL});

        final MethodVisitor init =
                writer.visitMethod(Opcodes.ACC_PUBLIC, CONSTRUCTOR_NAME, VOID_DESCRIPTOR, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, OBJECT_INTERNAL, CONSTRUCTOR_NAME, VOID_DESCRIPTOR, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        emitTestMethod(writer);
        emitScanEntityMethod(writer);
        emitScanEntitiesMethod(writer);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitTestMethod(final ClassWriter writer) {
        final MethodVisitor mv =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "test", "(L" + OBJECT_INTERNAL + ";)Z", null, null);
        mv.visitCode();
        final Label checkHandler = new Label();
        final Label checkEntity = new Label();
        final Label checkEntities = new Label();
        final Label returnTrue = new Label();
        final Label returnFalse = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNULL, returnTrue);

        mv.visitLabel(checkHandler);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, SceneHandlerCacheTarget.MOUSE_HANDLER_OWNER);
        mv.visitJumpInsn(Opcodes.IFNE, returnTrue);

        mv.visitLabel(checkEntity);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, SceneHandlerCacheTarget.ENTITY_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, checkEntities);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SceneHandlerCacheTarget.ENTITY_OWNER);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, PREDICATE_INTERNAL_NAME, SCAN_ENTITY_METHOD, SCAN_ENTITY_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(checkEntities);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, SceneHandlerCacheTarget.ENTITIES_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, returnFalse);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SceneHandlerCacheTarget.ENTITIES_OWNER);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, PREDICATE_INTERNAL_NAME, SCAN_ENTITIES_METHOD, SCAN_ENTITIES_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(returnTrue);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(returnFalse);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitScanEntityMethod(final ClassWriter writer) {
        final MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, SCAN_ENTITY_METHOD, SCAN_ENTITY_DESCRIPTOR, null, null);
        mv.visitCode();
        final Label loopTop = new Label();
        final Label loopNext = new Label();
        final Label scanChildren = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                SceneHandlerCacheTarget.ENTITY_OWNER,
                SceneHandlerCacheTarget.GET_COMPONENTS_METHOD,
                SceneHandlerCacheTarget.GET_COMPONENTS_DESCRIPTOR,
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                SceneHandlerCacheTarget.COMPONENTS_OWNER,
                SceneHandlerCacheTarget.COMPONENTS_METHOD,
                SceneHandlerCacheTarget.COMPONENTS_LIST_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_INTERNAL, "size", "()I", true);
        mv.visitVarInsn(Opcodes.ISTORE, 3);

        mv.visitLabel(loopTop);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, scanChildren);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_INTERNAL, "get", "(I)L" + OBJECT_INTERNAL + ";", true);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, SceneHandlerCacheTarget.MOUSE_HANDLER_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, loopNext);

        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(loopNext);
        mv.visitIincInsn(2, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopTop);

        mv.visitLabel(scanChildren);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                SceneHandlerCacheTarget.ENTITY_OWNER,
                SceneHandlerCacheTarget.GET_CHILDREN_METHOD,
                SceneHandlerCacheTarget.GET_CHILDREN_DESCRIPTOR,
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, PREDICATE_INTERNAL_NAME, SCAN_ENTITIES_METHOD, SCAN_ENTITIES_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitScanEntitiesMethod(final ClassWriter writer) {
        final MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, SCAN_ENTITIES_METHOD, SCAN_ENTITIES_DESCRIPTOR, null, null);
        mv.visitCode();
        final Label loopTop = new Label();
        final Label loopNext = new Label();
        final Label returnTrue = new Label();
        final Label returnFalse = new Label();
        final Label checkOtherTypes = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                SceneHandlerCacheTarget.ENTITIES_OWNER,
                SceneHandlerCacheTarget.ENTITIES_LIST_METHOD,
                SceneHandlerCacheTarget.ENTITIES_LIST_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_INTERNAL, "size", "()I", true);
        mv.visitVarInsn(Opcodes.ISTORE, 3);

        mv.visitLabel(loopTop);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, returnFalse);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_INTERNAL, "get", "(I)L" + OBJECT_INTERNAL + ";", true);
        mv.visitVarInsn(Opcodes.ASTORE, 4);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitJumpInsn(Opcodes.IFNULL, returnTrue);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, SceneHandlerCacheTarget.ENTITY_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, checkOtherTypes);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SceneHandlerCacheTarget.ENTITY_OWNER);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, PREDICATE_INTERNAL_NAME, SCAN_ENTITY_METHOD, SCAN_ENTITY_DESCRIPTOR, false);
        mv.visitJumpInsn(Opcodes.IFNE, returnTrue);
        mv.visitJumpInsn(Opcodes.GOTO, loopNext);

        mv.visitLabel(checkOtherTypes);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, SceneHandlerCacheTarget.MOUSE_HANDLER_OWNER);
        mv.visitJumpInsn(Opcodes.IFNE, returnTrue);

        mv.visitLabel(loopNext);
        mv.visitIincInsn(2, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopTop);

        mv.visitLabel(returnTrue);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(returnFalse);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static final class PredicateClassLoader extends ClassLoader {
        PredicateClassLoader(final ClassLoader parent) {
            super(parent);
        }

        Class<?> define(final String binaryName, final byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    private record FallbackDetector(
            Class<?> mouseHandlerType,
            Class<?> entityType,
            Class<?> entitiesType,
            MethodHandle entityComponentsHandle,
            MethodHandle componentsListHandle,
            MethodHandle entityChildrenHandle,
            MethodHandle entitiesListHandle)
            implements Predicate<Object> {

        @Override
        public boolean test(final Object candidate) {
            try {
                return containsMouseHandler(candidate);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(failure);
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
}
