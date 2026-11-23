package dev.turboism.adapter.cubism.optimization.scenehandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies compiled scene-handler detection and selective invalidation parity on public host types.
 */
final class SceneHandlerCacheBridgeTest {

    @TempDir
    Path root;

    private final Object prevGet = System.getProperties().get(SceneHandlerCacheBridge.GET_PROPERTY);
    private final Object prevPut = System.getProperties().get(SceneHandlerCacheBridge.PUT_PROPERTY);
    private final Object prevInv = System.getProperties().get(SceneHandlerCacheBridge.INVALIDATE_PROPERTY);
    private final Object prevStats = System.getProperties().get(SceneHandlerCacheBridge.STATS_PROPERTY);

    @AfterEach
    void restoreProperties() {
        restore(SceneHandlerCacheBridge.GET_PROPERTY, prevGet);
        restore(SceneHandlerCacheBridge.PUT_PROPERTY, prevPut);
        restore(SceneHandlerCacheBridge.INVALIDATE_PROPERTY, prevInv);
        restore(SceneHandlerCacheBridge.STATS_PROPERTY, prevStats);
    }

    @Test
    @SuppressWarnings("unchecked")
    void selectiveInvalidationDetectsDirectAndNestedMouseHandlersWithoutMethodHandleDrift() throws Exception {
        try (HostFixture f = compileFixture()) {
            try (SceneHandlerCacheBridge bridge = new SceneHandlerCacheBridge(f.loader)) {
                bridge.install();
                final Function<Object, Object> getFn =
                        (Function<Object, Object>) System.getProperties().get(SceneHandlerCacheBridge.GET_PROPERTY);
                final BiFunction<Object, Object, Object> putFn = (BiFunction<Object, Object, Object>)
                        System.getProperties().get(SceneHandlerCacheBridge.PUT_PROPERTY);
                final Consumer<Object> invalidateFn =
                        (Consumer<Object>) System.getProperties().get(SceneHandlerCacheBridge.INVALIDATE_PROPERTY);

                final Object sceneRoot = f.newEntity(false, null);
                final Object overlay = f.newEntity(false, null);
                final Object mouseNode = f.newEntity(true, null);
                final Object nestedParent = f.newEntity(false, mouseNode);
                final Object mouseHandler = f.newMouseHandler();
                final Object emptyEntities = f.newEntities(null);
                final Object handlerEntities = f.newEntities(mouseNode);

                final List<Object> expected = List.of(mouseHandler);
                putFn.apply(sceneRoot, expected);
                assertEquals(expected, getFn.apply(sceneRoot));

                // Plain overlay entity without mouse handlers must skip epoch invalidation.
                invalidateFn.accept(overlay);
                invalidateFn.accept(emptyEntities);
                invalidateFn.accept("unrelated");
                assertEquals(expected, getFn.apply(sceneRoot));

                Map<String, Long> stats = bridge.snapshot();
                assertEquals(0L, stats.get("invalidations"));
                assertEquals(3L, stats.get("skippedInvalidations"));
                assertEquals(2L, stats.get("hits"));

                // Direct handler, nested handler entity, handler Entities collection, and null must invalidate live
                // caches.
                final List<Object> triggers = List.of(mouseHandler, nestedParent, handlerEntities);
                for (final Object trigger : triggers) {
                    putFn.apply(sceneRoot, expected);
                    assertEquals(expected, getFn.apply(sceneRoot));
                    invalidateFn.accept(trigger);
                    assertNull(getFn.apply(sceneRoot));
                }
                putFn.apply(sceneRoot, expected);
                invalidateFn.accept(null);
                assertNull(getFn.apply(sceneRoot));

                stats = bridge.snapshot();
                assertEquals(4L, stats.get("invalidations"));
                assertEquals(0L, stats.get("observerFailures"));
                assertSame(stats.get("invalidations") + 2L, stats.get("epoch"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void lazyInvalidationSkipsRedundantScansWhenStaleAndCoalescesBursts() throws Exception {
        try (HostFixture f = compileFixture()) {
            try (SceneHandlerCacheBridge bridge = new SceneHandlerCacheBridge(f.loader)) {
                bridge.install();
                final Function<Object, Object> getFn =
                        (Function<Object, Object>) System.getProperties().get(SceneHandlerCacheBridge.GET_PROPERTY);
                final BiFunction<Object, Object, Object> putFn = (BiFunction<Object, Object, Object>)
                        System.getProperties().get(SceneHandlerCacheBridge.PUT_PROPERTY);
                final Consumer<Object> invalidateFn =
                        (Consumer<Object>) System.getProperties().get(SceneHandlerCacheBridge.INVALIDATE_PROPERTY);

                final Object sceneRoot = f.newEntity(false, null);
                final Object overlay = f.newEntity(false, null);
                final Object mouseNode = f.newEntity(true, null);
                final Object nestedParent = f.newEntity(false, mouseNode);
                final Object handlerEntities = f.newEntities(mouseNode);
                final List<Object> expected = List.of(f.newMouseHandler());
                final int initialMouseReads = f.scanCount(mouseNode);

                // Mutations while no live cache exists skip eager subtree scans.
                invalidateFn.accept(nestedParent);
                invalidateFn.accept(handlerEntities);
                assertEquals(0, f.scanCount(nestedParent));
                assertEquals(initialMouseReads, f.scanCount(mouseNode));

                // Repeated identical overlay mutations defer and deduplicate until lookup.
                putFn.apply(sceneRoot, expected);
                for (int i = 0; i < 10; i++) {
                    invalidateFn.accept(overlay);
                }
                assertEquals(0, f.scanCount(overlay));
                assertEquals(expected, getFn.apply(sceneRoot));
                assertEquals(1, f.scanCount(overlay));

                // Large entity bursts (>16 distinct targets) coalesce via O(1) dirty invalidation.
                final List<Object> burst = new ArrayList<>();
                for (int i = 0; i < 17; i++) {
                    final Object target = f.newEntity(false, null);
                    burst.add(target);
                    invalidateFn.accept(target);
                }
                assertEquals(0, f.scanCount(burst.get(0)));
                assertEquals(0, f.scanCount(burst.get(16)));
                assertNull(getFn.apply(sceneRoot));
                assertEquals(0, f.scanCount(burst.get(0)));

                // Redundant invalidations after cache is already stale skip entity scans too.
                invalidateFn.accept(nestedParent);
                invalidateFn.accept(handlerEntities);
                assertEquals(0, f.scanCount(nestedParent));
                assertEquals(initialMouseReads, f.scanCount(mouseNode));
                assertEquals(1L, bridge.snapshot().get("invalidations"));
            }
        }
    }

    private static void restore(final String key, final Object previous) {
        if (previous == null) {
            System.getProperties().remove(key);
        } else {
            System.getProperties().put(key, previous);
        }
    }

    private HostFixture compileFixture() throws Exception {
        final Path src = root.resolve("src");
        final Path classes = root.resolve("classes");
        Files.createDirectories(src);
        Files.createDirectories(classes);
        final Map<String, String> sources = Map.of(
                "IGComponent.java",
                """
                package com.live2d.graphics3d.component;
                public interface IGComponent {}
                """,
                "GMouseHandlerComponent.java",
                """
                package com.live2d.cubism.view.context.guiEntity;
                import com.live2d.graphics3d.component.IGComponent;
                public final class GMouseHandlerComponent implements IGComponent {}
                """,
                "Components.java",
                """
                package com.live2d.graphics3d.entity;
                import com.live2d.graphics3d.component.IGComponent;
                import java.util.ArrayList;
                import java.util.List;
                public final class Components {
                    public final List<IGComponent> items = new ArrayList<>();
                    public List<IGComponent> a() { return items; }
                }
                """,
                "Entities.java",
                """
                package com.live2d.graphics3d.entity;
                import java.util.ArrayList;
                import java.util.List;
                public final class Entities {
                    public final List<GEntity> items = new ArrayList<>();
                    public List<GEntity> getList() { return items; }
                }
                """,
                "GEntity.java",
                """
                package com.live2d.graphics3d.entity;
                public class GEntity {
                    public final Components components = new Components();
                    public final Entities children = new Entities();
                    public int componentReads;
                    public Components getComponents() {
                        componentReads++;
                        return components;
                    }
                    public Entities getChildren() { return children; }
                }
                """);
        final List<String> args = new ArrayList<>(List.of("--release", "17", "-d", classes.toString()));
        for (final var entry : sources.entrySet()) {
            final Path file = src.resolve(entry.getKey());
            Files.writeString(file, entry.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        final URLClassLoader loader =
                new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        return new HostFixture(loader);
    }

    private static final class HostFixture implements AutoCloseable {
        final URLClassLoader loader;
        final Class<?> entityType;
        final Class<?> entitiesType;
        final Class<?> handlerType;

        HostFixture(final URLClassLoader loader) throws Exception {
            this.loader = loader;
            this.entityType = Class.forName("com.live2d.graphics3d.entity.GEntity", false, loader);
            this.entitiesType = Class.forName("com.live2d.graphics3d.entity.Entities", false, loader);
            this.handlerType =
                    Class.forName("com.live2d.cubism.view.context.guiEntity.GMouseHandlerComponent", false, loader);
        }

        Object newMouseHandler() throws Exception {
            return handlerType.getConstructor().newInstance();
        }

        @SuppressWarnings("unchecked")
        Object newEntity(final boolean withMouseHandler, final Object child) throws Exception {
            final Object entity = entityType.getConstructor().newInstance();
            if (withMouseHandler) {
                final Object components = entityType.getMethod("getComponents").invoke(entity);
                ((List<Object>) components.getClass().getMethod("a").invoke(components)).add(newMouseHandler());
            }
            if (child != null) {
                final Object children = entityType.getMethod("getChildren").invoke(entity);
                ((List<Object>) children.getClass().getMethod("getList").invoke(children)).add(child);
            }
            return entity;
        }

        @SuppressWarnings("unchecked")
        Object newEntities(final Object child) throws Exception {
            final Object entities = entitiesType.getConstructor().newInstance();
            if (child != null) {
                ((List<Object>) entitiesType.getMethod("getList").invoke(entities)).add(child);
            }
            return entities;
        }

        int scanCount(final Object entity) throws Exception {
            return entityType.getField("componentReads").getInt(entity);
        }

        @Override
        public void close() throws Exception {
            loader.close();
        }
    }
}
