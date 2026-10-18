package dev.turboism.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.test.action.RecordingActionRegistry;
import dev.turboism.test.config.InMemoryConfigRegistry;
import dev.turboism.test.event.RecordingEventBus;
import dev.turboism.test.storage.InMemoryPluginStorage;
import dev.turboism.test.ui.RecordingContextMenuRegistry;
import dev.turboism.test.ui.RecordingUiHost;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class FakePluginContextTest {

    @Test
    void defaultContextProvidesStandardFakes() {
        final FakePluginContext context = FakePluginContext.create();

        assertNotNull(context.descriptor());
        assertNotNull(context.logger());
        assertNotNull(context.localization());
        assertNotNull(context.storage());
        assertNotNull(context.config());
        assertNotNull(context.actions());
        assertNotNull(context.menus());
        assertNotNull(context.eventBus());
        assertNotNull(context.uiScheduler());
        assertNotNull(context.disposableScope());

        assertTrue(context.storage() instanceof InMemoryPluginStorage);
        assertTrue(context.config() instanceof InMemoryConfigRegistry);
        assertTrue(context.actions() instanceof RecordingActionRegistry);
        assertTrue(context.eventBus() instanceof RecordingEventBus);
    }

    @Test
    void builderWiresCustomFakesAndServices() {
        final RecordingUiHost uiHost = new RecordingUiHost();
        final RecordingContextMenuRegistry contextMenu = new RecordingContextMenuRegistry();

        final FakePluginContext context = FakePluginContext.builder()
                .uiHost(uiHost)
                .contextMenu(contextMenu)
                .build();

        assertEquals(uiHost, context.uiHost());
        assertEquals(contextMenu, context.contextMenu());
        assertEquals(uiHost, context.requireService(UiHostCapabilityService.class));
        assertEquals(contextMenu, context.requireService(ContextMenuRegistry.class));
    }

    @Test
    void actionExecutionDispatch() {
        final FakePluginContext context = FakePluginContext.create();
        final AtomicBoolean executed = new AtomicBoolean();

        context.actions().register("test.action", ActionRegistry.Action.of("test.action", "Test", ctx -> {
            executed.set(true);
        }));

        context.actionRegistry().execute("test.action");
        assertTrue(executed.get());
    }

    @Test
    void eventBusDispatch() {
        final FakePluginContext context = FakePluginContext.create();
        final AtomicBoolean received = new AtomicBoolean();

        context.eventBus().subscribe(EventBus.TurboismEvent.class, event -> received.set(true));
        context.eventBus().publish(new EventBus.TurboismEvent() {});

        assertTrue(received.get());
    }
}
