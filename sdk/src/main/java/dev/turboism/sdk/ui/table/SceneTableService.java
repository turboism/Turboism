package dev.turboism.sdk.ui.table;

import java.util.List;
import java.util.Map;

/** Preview API for the native Scene palette table. */
public interface SceneTableService {

    String SCENE_TABLE_ID = "scene";

    /** Sets the header label of one column of the named table. */
    void setHeader(String tableId, String columnId, String label);

    /** Moves one item to {@code position} in the named table. */
    void setItemPosition(String tableId, String itemId, int position);

    /** Applies {@code itemIds} as the complete row order of the named table. */
    default void setItemOrder(final String tableId, final List<String> itemIds) {
        for (int index = 0; index < itemIds.size(); index++) {
            setItemPosition(tableId, itemIds.get(index), index);
        }
    }

    /** Enables native manual row dragging while the plugin is in manual-order mode. */
    default void setManualReordering(final String tableId, final boolean enabled) {
    }

    /** Returns a fail-closed service: every call is a no-op. */
    static SceneTableService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Notification that a table column header was clicked. */
    record HeaderClick(String tableId, String columnId) {
    }

    /** Notification that manual dragging reordered items within one scope. */
    record ItemOrderChanged(String tableId, String scopeId, List<String> itemIds) {
        public ItemOrderChanged {
            itemIds = List.copyOf(itemIds);
        }
    }

    /** One table column descriptor. */
    record Column(String id, String label) {
    }

    /** One table row: an item id plus cell text keyed by column id. */
    record Item(String id, Map<String, String> cells) {
        public Item {
            cells = Map.copyOf(cells);
        }
    }

    /** Immutable snapshot of one table's columns and items within a scope. */
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

    /** Fail-closed implementation returned by {@link #unavailable()}. */
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
