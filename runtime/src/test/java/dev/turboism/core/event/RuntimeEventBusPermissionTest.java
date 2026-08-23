package dev.turboism.core.event;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeEventBusPermissionTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.test";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    private RuntimeScheduler scheduler;

    @AfterEach
    void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void subscribeWithoutPermissionThrowsCubismPermissionException() {
        // Given
        RuntimeEventBus eventBus = eventBus((permissionId, operation) -> {
            throw new CubismPermissionException(operation + " denied");
        });

        // When / Then
        CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> eventBus.subscribe(TestEvent.class, ignored -> { })
        );
        assertEquals("event.subscribe denied", exception.getMessage());
    }

    @Test
    void rootSubscriptionChecksOnlyBaselinePermission() {
        final List<String> checks = new CopyOnWriteArrayList<>();
        final RuntimeEventBus eventBus = eventBus((permissionId, operation) ->
            checks.add(permissionId + ":" + operation)
        );

        final Registration registration = eventBus.subscribe(
            EventBus.TurboismEvent.class,
            ignored -> { }
        );

        assertEquals(
            List.of(PermissionIds.TURBOISM_EVENT_SUBSCRIBE + ":event.subscribe"),
            checks
        );
        registration.close();
    }

    @Test
    void concreteRuntimeEventRequiresItsDomainPermission() {
        final List<String> checks = new CopyOnWriteArrayList<>();
        final RuntimeEventBus eventBus = eventBus((permissionId, operation) -> {
            checks.add(permissionId + ":" + operation);
            if (PermissionIds.TURBOISM_CUBISM_SELECTION_OBSERVE.equals(permissionId)) {
                throw new CubismPermissionException(operation + " denied");
            }
        });

        final CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> eventBus.subscribe(SelectionChangedEvent.class, ignored -> { })
        );

        assertEquals(
            "event.subscribe." + SelectionChangedEvent.class.getName() + " denied",
            exception.getMessage()
        );
        assertEquals(List.of(
            PermissionIds.TURBOISM_EVENT_SUBSCRIBE + ":event.subscribe",
            PermissionIds.TURBOISM_CUBISM_SELECTION_OBSERVE
                + ":event.subscribe."
                + SelectionChangedEvent.class.getName()
        ), checks);
    }

    @Test
    void publishWithoutPermissionThrowsCubismPermissionException() {
        // Given
        RuntimeEventBus eventBus = eventBus((permissionId, operation) -> {
            if ("event.publish".equals(operation)) {
                throw new CubismPermissionException(operation + " denied");
            }
        });
        Registration registration = eventBus.subscribe(TestEvent.class, ignored -> { });

        // When / Then
        CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> eventBus.publish(new TestEvent("probe"))
        );
        assertEquals("event.publish denied", exception.getMessage());
        registration.close();
    }

    private RuntimeEventBus eventBus(dev.turboism.permissions.PermissionChecker permissionChecker) {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
        return new RuntimeEventBus(scheduler, PLUGIN_ID, permissionChecker);
    }

    private record TestEvent(String name) implements EventBus.TurboismEvent {
    }
}
