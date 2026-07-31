package dev.turboism.ui.table;

import dev.turboism.sdk.ui.table.SceneTableService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RuntimeSceneTableServiceTest {

    @Test
    void forwardsHeadersPositionsAndNativeEvents() {
        final RecordingHost host = new RecordingHost();
        final RuntimeSceneTableService service = new RuntimeSceneTableService(host);
        final List<String> clicks = new ArrayList<>();
        final List<Integer> snapshotSizes = new ArrayList<>();
        service.onHeaderClick(SceneTableService.SCENE_TABLE_ID, click -> clicks.add(click.columnId()));
        service.onSnapshot(SceneTableService.SCENE_TABLE_ID, snapshot -> snapshotSizes.add(snapshot.items().size()));

        service.setHeader(SceneTableService.SCENE_TABLE_ID, "name", "Name ↓");
        service.setItemPosition(SceneTableService.SCENE_TABLE_ID, "scene-1", 0);
        service.setManualReordering(SceneTableService.SCENE_TABLE_ID, true);
        service.setItemOrder(SceneTableService.SCENE_TABLE_ID, List.of("scene-2", "scene-1"));
        service.publishHeaderClick("name");
        service.publishSnapshot(new SceneTableService.TableSnapshot(
            SceneTableService.SCENE_TABLE_ID,
            List.of(new SceneTableService.Column("name", "Name")),
            List.of(new SceneTableService.Item("scene-1", Map.of("name", "Scene 1")))
        ));

        assertEquals(List.of("name=Name ↓"), host.headers);
        assertEquals(List.of("scene-1@0"), host.positions);
        assertEquals(List.of(List.of("scene-2", "scene-1")), host.orders);
        assertEquals(List.of("name"), clicks);
        assertEquals(List.of(true), host.manualReordering);
        assertEquals(List.of(1), snapshotSizes);
    }

    @Test
    void rejectsUnknownTablesAndNegativePositions() {
        final RuntimeSceneTableService service = new RuntimeSceneTableService(new RecordingHost());
        assertThrows(IllegalArgumentException.class, () -> service.setHeader("parts", "name", "Name"));
        assertThrows(IllegalArgumentException.class, () -> service.setItemPosition("scene", "id", -1));
    }

    private static final class RecordingHost implements RuntimeSceneTableService.Host {
        private final List<String> headers = new ArrayList<>();
        private final List<String> positions = new ArrayList<>();
        private final List<List<String>> orders = new ArrayList<>();
        private final List<Boolean> manualReordering = new ArrayList<>();

        @Override
        public void setHeader(final String columnId, final String label) {
            headers.add(columnId + "=" + label);
        }

        @Override
        public void setItemPosition(final String itemId, final int position) {
            positions.add(itemId + "@" + position);
        }

        @Override
        public void setItemOrder(final List<String> itemIds) {
            orders.add(itemIds);
        }

        @Override
        public void setManualReordering(final boolean enabled) {
            manualReordering.add(enabled);
        }
    }
}
