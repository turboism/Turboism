package dev.turboism.plugin.scenepalette;

import dev.turboism.sdk.ui.table.SceneTableService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SceneTableSorterTest {

    private static final String SCOPE_A = "a".repeat(64);
    private static final String SCOPE_B = "b".repeat(64);

    @Test
    void cyclesDescendingAscendingAndManualOrder() {
        final FakeService service = new FakeService();
        final SceneTableSorter sorter = new SceneTableSorter(service);
        sorter.onSnapshot(snapshot());

        sorter.onHeaderClick(click("name"));
        assertEquals(List.of("scene-10", "scene-2", "scene-1"), service.order);
        assertEquals("Name ↓", service.headers.get("name"));

        sorter.onHeaderClick(click("name"));
        assertEquals(List.of("scene-1", "scene-2", "scene-10"), service.order);
        assertEquals("Name ↑", service.headers.get("name"));

        service.order = List.of();
        sorter.onHeaderClick(click("name"));
        assertEquals(List.of("scene-2", "scene-10", "scene-1"), service.order);
        assertEquals("Name", service.headers.get("name"));
    }

    @Test
    void restoresCurrentOrderAndMigratesLegacyOrder() {
        final FakeService service = new FakeService();
        final FakeStore store = new FakeStore();
        store.loads.put(SCOPE_A, CompletableFuture.completedFuture(ManualOrderStore.LoadResult.current(
            List.of("scene-10", "deleted", "scene-2")
        )));
        final SceneTableSorter sorter = new SceneTableSorter(service, store, new TestLogger());

        sorter.onSnapshot(snapshot(SCOPE_A));
        assertEquals(List.of("scene-10", "scene-2", "scene-1"), service.order);
        assertTrue(store.saves.isEmpty());

        store.loads.put(SCOPE_B, CompletableFuture.completedFuture(ManualOrderStore.LoadResult.legacy(
            List.of("scene-1", "scene-10", "scene-2")
        )));
        sorter.onSnapshot(snapshot(SCOPE_B));
        assertEquals(List.of("scene-1", "scene-10", "scene-2"), service.order);
        assertEquals(List.of("scene-1", "scene-10", "scene-2"), store.saves.get(SCOPE_B));
    }

    @Test
    void sameScopeLoadCannotOverwriteUserReorder() {
        final FakeService service = new FakeService();
        final FakeStore store = new FakeStore();
        final CompletableFuture<ManualOrderStore.LoadResult> pending = new CompletableFuture<>();
        store.loads.put(SCOPE_A, pending);
        final SceneTableSorter sorter = new SceneTableSorter(service, store, new TestLogger());
        sorter.onSnapshot(snapshot(SCOPE_A));

        final List<String> userOrder = List.of("scene-1", "scene-10", "scene-2");
        service.order = userOrder;
        sorter.onItemOrderChanged(new SceneTableService.ItemOrderChanged(
            SceneTableService.SCENE_TABLE_ID, SCOPE_A, userOrder
        ));
        pending.complete(ManualOrderStore.LoadResult.current(List.of("scene-10", "scene-2", "scene-1")));

        assertEquals(userOrder, service.order);
        assertEquals(userOrder, store.saves.get(SCOPE_A));
    }

    @Test
    void unusableLoadBlocksLaterWritesForThatScope() {
        final FakeService service = new FakeService();
        final FakeStore store = new FakeStore();
        store.loads.put(SCOPE_A, CompletableFuture.completedFuture(
            ManualOrderStore.LoadResult.unusable()
        ));
        final SceneTableSorter sorter = new SceneTableSorter(service, store, new TestLogger());
        sorter.onSnapshot(snapshot(SCOPE_A));

        sorter.onItemOrderChanged(new SceneTableService.ItemOrderChanged(
            SceneTableService.SCENE_TABLE_ID,
            SCOPE_A,
            List.of("scene-1", "scene-10", "scene-2")
        ));

        assertTrue(store.saves.isEmpty());
    }

    @Test
    void reorderDuringPendingUnusableLoadDoesNotOverwriteStorage() {
        final FakeService service = new FakeService();
        final FakeStore store = new FakeStore();
        final CompletableFuture<ManualOrderStore.LoadResult> pending = new CompletableFuture<>();
        store.loads.put(SCOPE_A, pending);
        final SceneTableSorter sorter = new SceneTableSorter(service, store, new TestLogger());
        sorter.onSnapshot(snapshot(SCOPE_A));

        sorter.onItemOrderChanged(new SceneTableService.ItemOrderChanged(
            SceneTableService.SCENE_TABLE_ID,
            SCOPE_A,
            List.of("scene-1", "scene-10", "scene-2")
        ));
        pending.complete(ManualOrderStore.LoadResult.unusable());

        assertTrue(store.saves.isEmpty());
    }

    @Test
    void closeInvalidatesPendingLoadAndRestoresHeader() {
        final FakeService service = new FakeService();
        final FakeStore store = new FakeStore();
        final CompletableFuture<ManualOrderStore.LoadResult> pending = new CompletableFuture<>();
        store.loads.put(SCOPE_A, pending);
        final SceneTableSorter sorter = new SceneTableSorter(service, store, new TestLogger());
        sorter.onSnapshot(snapshot(SCOPE_A));
        sorter.onHeaderClick(click("name"));
        assertEquals("Name ↓", service.headers.get("name"));

        sorter.close();
        final int mutationsAfterClose = service.mutations;
        assertEquals("Name", service.headers.get("name"));
        assertFalse(service.manualReordering);

        pending.complete(ManualOrderStore.LoadResult.current(List.of("scene-1", "scene-2", "scene-10")));
        assertEquals(mutationsAfterClose, service.mutations);
    }

    @Test
    void keepsDistinctScopesIndependentEvenWhenIdsMatch() {
        final FakeService service = new FakeService();
        final FakeStore store = new FakeStore();
        store.loads.put(SCOPE_A, CompletableFuture.completedFuture(ManualOrderStore.LoadResult.missing()));
        store.loads.put(SCOPE_B, CompletableFuture.completedFuture(ManualOrderStore.LoadResult.missing()));
        final SceneTableSorter sorter = new SceneTableSorter(service, store, new TestLogger());
        sorter.onSnapshot(snapshot(SCOPE_A));
        sorter.onItemOrderChanged(new SceneTableService.ItemOrderChanged(
            SceneTableService.SCENE_TABLE_ID,
            SCOPE_A,
            List.of("scene-1", "scene-10", "scene-2")
        ));

        final List<String> scopeBOrder = List.of("scene-10", "scene-1", "scene-2");
        sorter.onSnapshot(new SceneTableService.TableSnapshot(
            SceneTableService.SCENE_TABLE_ID,
            SCOPE_B,
            snapshot().columns(),
            scopeBOrder.stream().map(id -> item(id, id)).toList()
        ));

        assertEquals(scopeBOrder, service.order);
        assertFalse(store.saves.containsKey(SCOPE_B));
    }

    @Test
    void missingAndUnusableLoadsKeepLiveOrderWithoutUnsafeRewrite() {
        final FakeService service = new FakeService();
        final FakeStore store = new FakeStore();
        store.loads.put(SCOPE_A, CompletableFuture.completedFuture(ManualOrderStore.LoadResult.missing()));
        store.loads.put(SCOPE_B, CompletableFuture.completedFuture(ManualOrderStore.LoadResult.unusable()));
        final SceneTableSorter sorter = new SceneTableSorter(service, store, new TestLogger());

        sorter.onSnapshot(snapshot(SCOPE_A));
        assertEquals(List.of("scene-2", "scene-10", "scene-1"), service.order);
        assertTrue(store.saves.isEmpty());

        sorter.onSnapshot(new SceneTableService.TableSnapshot(
            SceneTableService.SCENE_TABLE_ID,
            SCOPE_B,
            snapshot().columns(),
            List.of(item("other-2", "Other 2"), item("other-1", "Other 1"))
        ));
        assertEquals(List.of("other-2", "other-1"), service.order);
        assertTrue(store.saves.isEmpty());
    }

    @Test
    void comparesNumericRunsNaturallyWithoutOverflow() {
        assertTrue(SceneTableSorter.compareNatural("Scene 2", "Scene 10") < 0);
        assertTrue(SceneTableSorter.compareNatural("Scene 99999999999999999999", "Scene 10") > 0);
    }

    private static SceneTableService.HeaderClick click(final String columnId) {
        return new SceneTableService.HeaderClick(SceneTableService.SCENE_TABLE_ID, columnId);
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

    private static SceneTableService.TableSnapshot snapshot(final String scopeId) {
        return new SceneTableService.TableSnapshot(
            SceneTableService.SCENE_TABLE_ID,
            scopeId,
            snapshot().columns(),
            snapshot().items()
        );
    }

    private static SceneTableService.Item item(final String id, final String name) {
        return new SceneTableService.Item(id, Map.of("name", name));
    }

    private static final class FakeService implements SceneTableService {
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<String> positions = new ArrayList<>();
        private List<String> order = List.of();
        private boolean manualReordering;
        private int mutations;

        @Override
        public void setHeader(final String tableId, final String columnId, final String label) {
            headers.put(columnId, label);
            mutations++;
        }

        @Override
        public void setItemPosition(final String tableId, final String itemId, final int position) {
            while (positions.size() <= position) positions.add(null);
            positions.set(position, itemId);
            order = List.copyOf(positions);
            mutations++;
        }

        @Override
        public void setItemOrder(final String tableId, final List<String> itemIds) {
            positions.clear();
            positions.addAll(itemIds);
            order = List.copyOf(itemIds);
            mutations += itemIds.size();
        }

        @Override
        public void setManualReordering(final String tableId, final boolean enabled) {
            manualReordering = enabled;
            mutations++;
        }
    }

    private static final class FakeStore implements ManualOrderStore {
        private final Map<String, CompletableFuture<LoadResult>> loads = new LinkedHashMap<>();
        private final Map<String, List<String>> saves = new LinkedHashMap<>();

        @Override
        public CompletionStage<LoadResult> load(final String scopeId) {
            return loads.getOrDefault(
                scopeId,
                CompletableFuture.completedFuture(LoadResult.missing())
            );
        }

        @Override
        public CompletionStage<Void> save(final String scopeId, final List<String> itemIds) {
            saves.put(scopeId, List.copyOf(itemIds));
            return CompletableFuture.completedStage(null);
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
