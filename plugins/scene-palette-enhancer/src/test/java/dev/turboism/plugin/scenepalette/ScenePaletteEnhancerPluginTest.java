package dev.turboism.plugin.scenepalette;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.table.SceneTableHeaderClickEvent;
import dev.turboism.sdk.ui.table.SceneTableService;
import dev.turboism.sdk.ui.table.SceneTableSnapshotEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScenePaletteEnhancerPluginTest {

    @Test
    void enableFallsBackWhenStorageServiceIsUnavailable() {
        final FakeService service = new FakeService();
        final TestLogger logger = new TestLogger();
        final PluginContext context = (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[] {PluginContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "storage" -> throw new UnsupportedOperationException("storage unavailable");
                case "sceneTable" -> service;
                case "logger" -> logger;
                case "toString" -> "TestPluginContext";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new AssertionError("Unexpected context call: " + method.getName());
            }
        );
        final ScenePaletteEnhancerPlugin plugin = new ScenePaletteEnhancerPlugin();
        plugin.init(context);

        plugin.enable();
        plugin.onSnapshot(new SceneTableSnapshotEvent(new SceneTableService.TableSnapshot(
            SceneTableService.SCENE_TABLE_ID,
            List.of(new SceneTableService.Column("name", "Name")),
            List.of(
                new SceneTableService.Item("scene-2", Map.of("name", "Scene 2")),
                new SceneTableService.Item("scene-10", Map.of("name", "Scene 10"))
            )
        )));
        plugin.onHeaderClick(new SceneTableHeaderClickEvent(new SceneTableService.HeaderClick(
            SceneTableService.SCENE_TABLE_ID, "name"
        )));

        assertEquals(List.of("scene-10", "scene-2"), service.order);
        assertTrue(logger.infos.stream().anyMatch(message -> message.contains("storage is unavailable")));
        plugin.disable();
    }

    private static final class FakeService implements SceneTableService {
        private List<String> order = List.of();

        @Override public void setHeader(final String tableId, final String columnId, final String label) { }

        @Override
        public void setItemPosition(final String tableId, final String itemId, final int position) {
            final List<String> next = new ArrayList<>(order);
            while (next.size() <= position) next.add(null);
            next.set(position, itemId);
            order = List.copyOf(next);
        }

        @Override public void setItemOrder(final String tableId, final List<String> itemIds) {
            order = List.copyOf(itemIds);
        }
    }

    private static final class TestLogger implements PluginLogger {
        private final List<String> infos = new ArrayList<>();

        @Override public void debug(final String message) { }
        @Override public void info(final String message) { infos.add(message); }
        @Override public void warn(final String message) { }
        @Override public void error(final String message) { }
        @Override public void error(final String message, final Throwable throwable) { }
    }
}
