package dev.turboism.hook.ingress;

import dev.turboism.sdk.event.EventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookIngressRegistryTest {

    @Test
    void defaultSpecsRemainProductionDisabledAndComplete() {
        HookIngressRegistry registry = HookIngressRegistry.defaults();

        assertEquals(8, registry.specs().size());
        for (HookIngressSpec spec : registry.specs()) {
            assertFalse(spec.productionEnabled());
            assertEquals("enqueue-only", spec.safeMode());
        }
        assertTrue(registry.find("hook-ingress.project.lifecycle").isPresent());
        assertTrue(registry.find("hook-ingress.selection.changed").isPresent());
        assertTrue(registry.find("hook-ingress.context-menu.opening").isPresent());
        assertTrue(registry.find("hook-ingress.texture-atlas.reinit").isPresent());
        assertTrue(registry.find("hook-ingress.viewport.overlay.lifecycle").isPresent());
        assertTrue(registry.find("hook-ingress.render.status").isPresent());
        assertTrue(registry.find("hook-ingress.model.tree.changed").isPresent());
        assertTrue(registry.find("hook-ingress.parameter.changed").isPresent());
    }

    @Test
    void dispatcherPublishesOnlyAfterSemanticValidation() {
        List<EventBus.TurboismEvent> events = new ArrayList<>();
        HookIngressDispatcher dispatcher = new HookIngressDispatcher(
            HookIngressRegistry.defaults(),
            events::add
        );
        TestIngressEvent project = new TestIngressEvent("project-1");

        HookIngressSpec result = dispatcher.dispatch(
            "hook-ingress.project.lifecycle",
            "event.project.lifecycle",
            project
        );

        assertEquals("hook-ingress.project.lifecycle", result.hookId());
        assertEquals(List.of(project), events);
    }

    @Test
    void dispatcherRejectsUnknownOrMismatchedIngresses() {
        List<EventBus.TurboismEvent> events = new ArrayList<>();
        HookIngressDispatcher dispatcher = new HookIngressDispatcher(
            HookIngressRegistry.defaults(),
            events::add
        );
        TestIngressEvent event = new TestIngressEvent("project-1");

        assertThrows(
            IllegalArgumentException.class,
            () -> dispatcher.dispatch("hook-ingress.unknown", "event.project.lifecycle", event)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> dispatcher.dispatch(
                "hook-ingress.project.lifecycle",
                "event.selection.changed",
                event
            )
        );
        assertTrue(events.isEmpty());
    }

    @Test
    void specsValidateAndRegistryRejectsDuplicates() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new HookIngressSpec(
                "hook-ingress.render.status",
                "event.render.status.changed",
                true,
                "enqueue-only"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new HookIngressRegistry(List.of(
                new HookIngressSpec(
                    "hook-ingress.dup",
                    "event.project.lifecycle",
                    false,
                    "enqueue-only"
                ),
                new HookIngressSpec(
                    "hook-ingress.dup",
                    "event.selection.changed",
                    false,
                    "enqueue-only"
                )
            ))
        );
    }

    private record TestIngressEvent(String value) implements EventBus.TurboismEvent {
    }
}
