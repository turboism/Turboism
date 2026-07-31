package dev.turboism.ui.table;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.table.SceneTableService;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Runtime-owned Scene table bridge; native hosts call the publish methods. */
public final class RuntimeSceneTableService implements SceneTableService {

    private final Host host;
    private final List<Consumer<HeaderClick>> headerListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<TableSnapshot>> snapshotListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ItemOrderChanged>> orderListeners = new CopyOnWriteArrayList<>();
    private volatile TableSnapshot latestSnapshot;

    public RuntimeSceneTableService(final Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public Registration onHeaderClick(final String tableId, final Consumer<HeaderClick> listener) {
        requireSceneTable(tableId);
        final Consumer<HeaderClick> registered = Objects.requireNonNull(listener, "listener");
        headerListeners.add(registered);
        return () -> headerListeners.remove(registered);
    }

    @Override
    public Registration onSnapshot(final String tableId, final Consumer<TableSnapshot> listener) {
        requireSceneTable(tableId);
        final Consumer<TableSnapshot> registered = Objects.requireNonNull(listener, "listener");
        snapshotListeners.add(registered);
        final TableSnapshot current = latestSnapshot;
        if (current != null) registered.accept(current);
        return () -> snapshotListeners.remove(registered);
    }

    @Override
    public Registration onItemOrderChanged(final String tableId, final Consumer<ItemOrderChanged> listener) {
        requireSceneTable(tableId);
        final Consumer<ItemOrderChanged> registered = Objects.requireNonNull(listener, "listener");
        orderListeners.add(registered);
        return () -> orderListeners.remove(registered);
    }

    @Override
    public void setHeader(final String tableId, final String columnId, final String label) {
        requireSceneTable(tableId);
        host.setHeader(requireText(columnId, "columnId"), Objects.requireNonNull(label, "label"));
    }

    @Override
    public void setItemPosition(final String tableId, final String itemId, final int position) {
        requireSceneTable(tableId);
        if (position < 0) {
            throw new IllegalArgumentException("position must not be negative");
        }
        host.setItemPosition(requireText(itemId, "itemId"), position);
    }

    @Override
    public void setItemOrder(final String tableId, final List<String> itemIds) {
        requireSceneTable(tableId);
        host.setItemOrder(List.copyOf(Objects.requireNonNull(itemIds, "itemIds")));
    }

    @Override
    public void setManualReordering(final String tableId, final boolean enabled) {
        requireSceneTable(tableId);
        host.setManualReordering(enabled);
    }

    public void publishHeaderClick(final String columnId) {
        final HeaderClick event = new HeaderClick(SCENE_TABLE_ID, requireText(columnId, "columnId"));
        headerListeners.forEach(listener -> listener.accept(event));
    }

    public void publishSnapshot(final TableSnapshot snapshot) {
        final TableSnapshot event = Objects.requireNonNull(snapshot, "snapshot");
        requireSceneTable(event.tableId());
        latestSnapshot = event;
        snapshotListeners.forEach(listener -> listener.accept(event));
    }

    public void publishItemOrderChanged(final ItemOrderChanged event) {
        final ItemOrderChanged published = Objects.requireNonNull(event, "event");
        requireSceneTable(published.tableId());
        orderListeners.forEach(listener -> listener.accept(published));
    }

    private static void requireSceneTable(final String tableId) {
        if (!SCENE_TABLE_ID.equals(tableId)) {
            throw new IllegalArgumentException("unsupported tableId: " + tableId);
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public interface Host {
        void setHeader(String columnId, String label);

        void setItemPosition(String itemId, int position);


        void setItemOrder(List<String> itemIds);


        void setManualReordering(boolean enabled);
    }
}
