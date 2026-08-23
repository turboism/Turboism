package dev.turboism.sdk.ui.table;

import java.util.List;
import java.util.Map;

/** Preview API for the native Scene palette table. */
public interface SceneTableService {

    String SCENE_TABLE_ID = "scene";

    void setHeader(String tableId, String columnId, String label);

    void setItemPosition(String tableId, String itemId, int position);

    default void setItemOrder(final String tableId, final List<String> itemIds) {
        for (int index = 0; index < itemIds.size(); index++) {
            setItemPosition(tableId, itemIds.get(index), index);
        }
    }

    /** Enables native manual row dragging while the plugin is in manual-order mode. */
    default void setManualReordering(final String tableId, final boolean enabled) {
    }

    static SceneTableService unavailable() {
        return Unavailable.INSTANCE;
    }

    record HeaderClick(String tableId, String columnId) {
    }

    record ItemOrderChanged(String tableId, String scopeId, List<String> itemIds) {
        public ItemOrderChanged {
            itemIds = List.copyOf(itemIds);
        }
    }

    record Column(String id, String label) {
    }

    record Item(String id, Map<String, String> cells) {
        public Item {
            cells = Map.copyOf(cells);
        }
    }

    record TableSnapshot(String tableId, String scopeId, List<Column> columns, List<Item> items) {
        public TableSnapshot {
            scopeId = scopeId == null ? "" : scopeId;
            columns = List.copyOf(columns);
            items = List.copyOf(items);
        }

        public TableSnapshot(final String tableId, final List<Column> columns, final List<Item> items) {
            this(tableId, "", columns, items);
        }
    }

    enum Unavailable implements SceneTableService {
        INSTANCE;

        @Override
        public void setHeader(final String tableId, final String columnId, final String label) {
        }

        @Override
        public void setItemPosition(final String tableId, final String itemId, final int position) {
        }

        @Override
        public void setManualReordering(final String tableId, final boolean enabled) {
        }
    }
}
