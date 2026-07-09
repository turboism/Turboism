package dev.turboism.tests.migration;

import dev.turboism.hook.ingress.DefaultHookIngressSpecs;
import dev.turboism.hook.ingress.HookIngressDispatcher;
import dev.turboism.hook.ingress.HookIngressRegistry;
import dev.turboism.hook.ingress.HookIngressSpec;
import dev.turboism.sdk.cubism.event.ProjectLifecycleEvent;
import dev.turboism.sdk.cubism.event.RenderStatusChangedEvent;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;
import dev.turboism.sdk.cubism.event.TextureAtlasReinitEvent;
import dev.turboism.sdk.event.EventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M12HookIngressCapabilityTest {

    @Test
    void defaultM12HookIngressSpecsAreProductionDisabledAndCoverCatalogedIngresses() {
        HookIngressRegistry registry = HookIngressRegistry.m12Default();

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
    void dispatcherPublishesSdkSafeEventsOnlyThroughSemanticIngress() {
        List<EventBus.TurboismEvent> events = new ArrayList<>();
        HookIngressDispatcher dispatcher = new HookIngressDispatcher(HookIngressRegistry.m12Default(), events::add);

        dispatcher.dispatch(
            "hook-ingress.project.lifecycle",
            "event.project.lifecycle",
            new ProjectLifecycleEvent("event.project.lifecycle", "project-1", "opened")
        );
        dispatcher.dispatch(
            "hook-ingress.selection.changed",
            "event.selection.changed",
            new SelectionChangedEvent("event.selection.changed", List.of("object-1"))
        );
        dispatcher.dispatch(
            "hook-ingress.texture-atlas.reinit",
            "event.texture-atlas.reinit",
            new TextureAtlasReinitEvent("event.texture-atlas.reinit", "atlas-1", "after")
        );
        dispatcher.dispatch(
            "hook-ingress.render.status",
            "event.render.status.changed",
            new RenderStatusChangedEvent("event.render.status.changed", true, 60.0)
        );

        assertEquals(4, events.size());
        assertEquals("project-1", ((ProjectLifecycleEvent) events.get(0)).projectId());
        assertEquals(List.of("object-1"), ((SelectionChangedEvent) events.get(1)).selectedObjectIds());
        assertEquals("atlas-1", ((TextureAtlasReinitEvent) events.get(2)).atlasId());
        assertEquals(60.0, ((RenderStatusChangedEvent) events.get(3)).framesPerSecond());
    }

    @Test
    void dispatcherRejectsUnknownOrMismatchedIngressesBeforePublishing() {
        List<EventBus.TurboismEvent> events = new ArrayList<>();
        HookIngressDispatcher dispatcher = new HookIngressDispatcher(HookIngressRegistry.m12Default(), events::add);
        ProjectLifecycleEvent event = new ProjectLifecycleEvent("event.project.lifecycle", "project-1", "opened");

        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch("hook-ingress.unknown", "event.project.lifecycle", event));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch("hook-ingress.project.lifecycle", "event.selection.changed", event));
        assertTrue(events.isEmpty());
    }

    @Test
    void eventDtosValidateAndRemainImmutable() {
        SelectionChangedEvent selection = new SelectionChangedEvent("event.selection.changed", List.of("object-1"));

        assertThrows(UnsupportedOperationException.class, () -> selection.selectedObjectIds().add("object-2"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectLifecycleEvent("", "project-1", "opened"));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasReinitEvent("event.texture-atlas.reinit", "", "after"));
        assertThrows(IllegalArgumentException.class, () -> new RenderStatusChangedEvent("event.render.status.changed", true, -1.0));
        assertThrows(IllegalArgumentException.class, () -> new HookIngressSpec("hook-ingress.render.status", "event.render.status.changed", true, "enqueue-only"));
        assertThrows(IllegalArgumentException.class, () -> new HookIngressRegistry(List.of(
            new HookIngressSpec("hook-ingress.dup", "event.project.lifecycle", false, "enqueue-only"),
            new HookIngressSpec("hook-ingress.dup", "event.selection.changed", false, "enqueue-only")
        )));
    }
}
