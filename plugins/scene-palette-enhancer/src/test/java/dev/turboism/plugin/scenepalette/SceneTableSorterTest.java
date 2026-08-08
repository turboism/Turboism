package dev.turboism.plugin.scenepalette;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.table.SceneTableService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SceneTableSorterTest {

    @Test
    void cyclesDescendingAscendingAndManualOrder() {
        final FakeService service = new FakeService();
        final SceneTableSorter sorter = new SceneTableSorter(service);
        sorter.enable();
        service.snapshot(snapshot());

        service.click("name");
        assertEquals(List.of("scene-10", "scene-2", "scene-1"), service.order);
        assertEquals("Name ↓", service.headers.get("name"));

        service.click("name");
        assertEquals(List.of("scene-1", "scene-2", "scene-10"), service.order);
        assertEquals("Name ↑", service.headers.get("name"));

        service.order = List.of();
        service.click("name");
        assertEquals(List.of("scene-2", "scene-10", "scene-1"), service.order);
        assertEquals("Name", service.headers.get("name"));
    }

    @Test
    void restoresAndPersistsManualOrderPerScope() {
        final FakeService service = new FakeService();
        final FakeStore store = new FakeStore(List.of("scene-10", "deleted", "scene-2"));
        final SceneTableSorter sorter = new SceneTableSorter(service, store, new TestLogger());
        sorter.enable();
        service.snapshot(new SceneTableService.TableSnapshot(
            SceneTableService.SCENE_TABLE_ID,
            "a".repeat(64),
            snapshot().columns(),
            snapshot().items()
        ));
        assertEquals(List.of("scene-10", "scene-2", "scene-1"), service.order);

        service.orderChanged.accept(new SceneTableService.ItemOrderChanged(
            SceneTableService.SCENE_TABLE_ID,
            "a".repeat(64),
            List.of("scene-1", "scene-10", "scene-2")
        ));
        assertEquals(List.of("scene-1", "scene-10", "scene-2"), store.saved);
    }

    @Test
    void comparesNumericRunsNaturallyWithoutOverflow() {
        assertTrue(SceneTableSorter.compareNatural("Scene 2", "Scene 10") < 0);
        assertTrue(SceneTableSorter.compareNatural("Scene 99999999999999999999", "Scene 10") > 0);
    }

    private static SceneTableService.TableSnapshot snapshot() {
        return new SceneTableService.TableSnapshot(
            SceneTableService.SCENE_TABLE_ID,
            List.of(new SceneTableService.Column("name", "Name")),
            List.of(
                item("scene-2", "Scene 2"),
                item("scene-10", "Scene 10"),
                item("scene-1", "Scene 1")
            )
        );
    }

    private static SceneTableService.Item item(final String id, final String name) {
        return new SceneTableService.Item(id, Map.of("name", name));
    }

    private static final class FakeService implements SceneTableService {
        private Consumer<HeaderClick> headerClick;
        private Consumer<TableSnapshot> snapshot;
        private Consumer<ItemOrderChanged> orderChanged;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<String> positions = new ArrayList<>();
        private List<String> order = List.of();

        @Override
        public Registration onHeaderClick(final String tableId, final Consumer<HeaderClick> listener) {
            headerClick = listener;
            return () -> headerClick = null;
        }

        @Override
        public Registration onSnapshot(final String tableId, final Consumer<TableSnapshot> listener) {
            snapshot = listener;
            return () -> snapshot = null;
        }

        @Override
        public Registration onItemOrderChanged(final String tableId, final Consumer<ItemOrderChanged> listener) {
            orderChanged = listener;
            return () -> orderChanged = null;
        }

        @Override
        public void setHeader(final String tableId, final String columnId, final String label) {
            headers.put(columnId, label);
        }

        @Override
        public void setItemPosition(final String tableId, final String itemId, final int position) {
            while (positions.size() <= position) positions.add(null);
            positions.set(position, itemId);
            order = List.copyOf(positions);
        }

        private void click(final String columnId) {
            positions.clear();
            headerClick.accept(new HeaderClick(SCENE_TABLE_ID, columnId));
        }

        private void snapshot(final TableSnapshot value) {
            snapshot.accept(value);
        }
    }

    private static final class FakeStore implements ManualOrderStore {
        private final List<String> loaded;
        private List<String> saved = List.of();

        private FakeStore(final List<String> loaded) {
            this.loaded = loaded;
        }

        @Override public java.util.concurrent.CompletionStage<List<String>> load(final String scopeId) {
            return java.util.concurrent.CompletableFuture.completedFuture(loaded);
        }

        @Override public java.util.concurrent.CompletionStage<Void> save(final String scopeId, final List<String> itemIds) {
            saved = List.copyOf(itemIds);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    private static final class TestLogger implements dev.turboism.sdk.plugin.PluginLogger {
        @Override public void debug(final String message) { }
        @Override public void info(final String message) { }
        @Override public void warn(final String message) { }
        @Override public void error(final String message) { }
        @Override public void error(final String message, final Throwable throwable) { }
    }
}
