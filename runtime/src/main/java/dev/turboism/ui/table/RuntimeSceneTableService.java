package dev.turboism.ui.table;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.sdk.ui.table.SceneTableHeaderClickEvent;
import dev.turboism.sdk.ui.table.SceneTableItemOrderEvent;
import dev.turboism.sdk.ui.table.SceneTableService;
import dev.turboism.sdk.ui.table.SceneTableSnapshotEvent;

import java.util.List;
import java.util.Objects;

/** Runtime-owned Scene table bridge; native hosts call the publish methods. */
public final class RuntimeSceneTableService implements SceneTableService {

    private final Host host;
    private volatile RuntimeEventBroker eventBroker;
    private volatile TableSnapshot latestSnapshot;

    public RuntimeSceneTableService(final Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /** Attaches the session event broker used to publish scene-table observations. */
    public void attachEventBroker(final RuntimeEventBroker broker) {
        final RuntimeEventBroker value = Objects.requireNonNull(broker, "broker");
        synchronized (this) {
            if (eventBroker != null && eventBroker != value) {
                throw new IllegalStateException("Scene-table event Broker is already attached");
            }
            eventBroker = value;
        }
        final TableSnapshot current = latestSnapshot;
        if (current != null) {
            value.publishRuntimeRetained(new SceneTableSnapshotEvent(current));
        }
    }

    /** Returns the latest detached scene-table snapshot, when one has been observed. */
    public java.util.Optional<TableSnapshot> latestSnapshot() {
        return java.util.Optional.ofNullable(latestSnapshot);
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

    /** Publishes a detached header-click observation without invoking plugin code on Swing EDT. */
    public void publishHeaderClick(final String columnId) {
        publish(new SceneTableHeaderClickEvent(
            new HeaderClick(SCENE_TABLE_ID, requireText(columnId, "columnId"))
        ));
    }

    /** Records and asynchronously publishes the latest detached Scene-table state. */
    public void publishSnapshot(final TableSnapshot snapshot) {
        final TableSnapshot event = Objects.requireNonNull(snapshot, "snapshot");
        requireSceneTable(event.tableId());
        latestSnapshot = event;
        publishRetained(new SceneTableSnapshotEvent(event));
    }

    /** Publishes a detached native reorder observation without retaining it for replay. */
    public void publishItemOrderChanged(final ItemOrderChanged event) {
        final ItemOrderChanged published = Objects.requireNonNull(event, "event");
        requireSceneTable(published.tableId());
        publish(new SceneTableItemOrderEvent(published));
    }

    private void publish(final dev.turboism.sdk.event.TurboismEvent event) {
        final RuntimeEventBroker broker = eventBroker;
        if (broker != null) {
            broker.publishRuntime(event);
        }
    }

    private void publishRetained(final dev.turboism.sdk.event.TurboismEvent event) {
        final RuntimeEventBroker broker = eventBroker;
        if (broker != null) {
            broker.publishRuntimeRetained(event);
        }
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
