package dev.turboism.ui.table;

import dev.turboism.core.event.PluginEventOwnerKey;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.ui.table.SceneTableHeaderClickEvent;
import dev.turboism.sdk.ui.table.SceneTableService;
import dev.turboism.sdk.ui.table.SceneTableSnapshotEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeSceneTableServiceTest {

    @Test
    void forwardsCommandsAndPublishesNativeEventsThroughBroker() throws Exception {
        final RecordingHost host = new RecordingHost();
        final RuntimeSceneTableService service = new RuntimeSceneTableService(host);
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner observerOwner = broker.admit("plugin.observer");
        final PluginEventOwnerKey observer = observerOwner.key();
        final AtomicReference<SceneTableHeaderClickEvent> click = new AtomicReference<>();
        final AtomicReference<SceneTableSnapshotEvent> snapshot = new AtomicReference<>();
        final CountDownLatch delivered = new CountDownLatch(2);
        broker.subscribe(observer, SceneTableHeaderClickEvent.class, event -> {
            click.set(event);
            delivered.countDown();
        });
        broker.subscribe(observer, SceneTableSnapshotEvent.class, event -> {
            snapshot.set(event);
            delivered.countDown();
        });
        observerOwner.activate();
        service.attachEventBroker(broker);

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

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("name=Name ↓"), host.headers);
        assertEquals(List.of("scene-1@0"), host.positions);
        assertEquals(List.of(List.of("scene-2", "scene-1")), host.orders);
        assertEquals("name", click.get().click().columnId());
        assertEquals(List.of(true), host.manualReordering);
        assertEquals(1, snapshot.get().snapshot().items().size());
        scheduler.shutdown();
    }

    @Test
    void replaysLatestSnapshotToLateSubscriber() throws Exception {
        final RuntimeSceneTableService service = new RuntimeSceneTableService(new RecordingHost());
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        service.attachEventBroker(broker);
        service.publishSnapshot(new SceneTableService.TableSnapshot(
            SceneTableService.SCENE_TABLE_ID,
            List.of(new SceneTableService.Column("name", "Name")),
            List.of(new SceneTableService.Item("scene-1", Map.of("name", "Scene 1")))
        ));
        final RuntimeEventBroker.Owner observerOwner = broker.admit("plugin.late-observer");
        final AtomicReference<SceneTableSnapshotEvent> replayed = new AtomicReference<>();
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribe(observerOwner.key(), SceneTableSnapshotEvent.class, event -> {
            replayed.set(event);
            delivered.countDown();
        });
        observerOwner.activate();

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals("scene-1", replayed.get().snapshot().items().get(0).id());
        scheduler.shutdown();
    }

    @Test
    void rejectsUnknownTablesAndNegativePositions() {
        final RuntimeSceneTableService service = new RuntimeSceneTableService(new RecordingHost());
        assertThrows(IllegalArgumentException.class, () -> service.setHeader("parts", "name", "Name"));
        assertThrows(IllegalArgumentException.class, () -> service.setItemPosition("scene", "id", -1));
    }

    private static RuntimeScheduler scheduler() {
        final Clock clock = Clock.fixed(
            Instant.parse("2026-08-23T00:00:00Z"),
            ZoneOffset.UTC
        );
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, ignored -> { }, clock),
            new SidecarDispatcher() {
                @Override
                public java.util.concurrent.CompletionStage<SidecarResult> dispatch(
                    final PluginTask task,
                    final Runnable callback
                ) {
                    return CompletableFuture.completedFuture(SidecarResult.success(""));
                }
            },
            ignored -> { }
        );
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
