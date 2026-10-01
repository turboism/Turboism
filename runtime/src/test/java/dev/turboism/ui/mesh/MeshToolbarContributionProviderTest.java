package dev.turboism.ui.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.mesh.MeshToolCoordinator;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;

class MeshToolbarContributionProviderTest {

    @Test
    void materializesToolsAndSlidersDeterministicallyAndCleansUpInReverse() {
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        RecordingHost host = new RecordingHost();
        MeshToolbarContributionProvider provider =
                new MeshToolbarContributionProvider(admission(3L), host, coordinator);
        List<EditorUiContribution<?>> contributions = List.of(
                new MeshToolbarSliderContributionDescriptor(
                                "plugin.b", 2, "radius", "Radius", 8, 128, 32, 10, ignored -> {})
                        .contribution(),
                new MeshToolbarContributionDescriptor("plugin.b", 2, "b", "B", "icons/b.png", 10).contribution(),
                new MeshToolbarContributionDescriptor("plugin.a", 1, "a", "A", "icons/a.png", 10).contribution());

        Registration registration = provider.apply(3L, contributions);
        assertEquals(List.of("button:plugin.a:1:a", "button:plugin.b:2:b", "slider:plugin.b:2:radius"), host.added);

        host.rebuild.run();
        assertEquals(3, host.closed.size());
        assertEquals(6, host.added.size());

        registration.close();
        registration.close();
        assertEquals(6, host.closed.size());
    }

    @Test
    void selectedStateTracksExactActiveIdentityAndSameButtonTogglesOff() {
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        coordinator.register("plugin.a", 1L, tool("a"), (permission, operation) -> {});
        RecordingHost host = new RecordingHost();
        MeshToolbarContributionProvider provider =
                new MeshToolbarContributionProvider(admission(4L), host, coordinator);
        provider.apply(
                4L,
                List.of(new MeshToolbarContributionDescriptor("plugin.a", 1L, "a", "A", "icons/a.png", 0)
                        .contribution()));

        coordinator.beginSession(new TestSession(4L));
        host.actions.get(0).run();
        assertTrue(host.buttons.get(0).selected);
        host.actions.get(0).run();
        assertFalse(host.buttons.get(0).selected);
    }

    @Test
    void changedRadiusSurvivesRebuildAndNewProviderWithoutCallingPluginUnderRebuild() {
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        RecordingHost host = new RecordingHost();
        AtomicInteger pluginRadius = new AtomicInteger(32);
        var slider = new MeshToolbarSliderContributionDescriptor(
                "plugin.a", 1L, "radius", "Radius", 8, 128, 32, 0, value -> {
                    pluginRadius.set(value);
                    host.rebuild.run();
                });
        List<EditorUiContribution<?>> contributions = List.of(slider.contribution());
        var provider = new MeshToolbarContributionProvider(admission(4L), host, coordinator);
        Registration first = provider.apply(4L, contributions);

        host.sliderChanges.get(0).accept(8);
        assertEquals(8, pluginRadius.get());
        assertEquals(8, host.sliders.get(1).value());
        host.rebuild.run();
        assertEquals(8, host.sliders.get(2).value());
        first.close();

        var replacement = new MeshToolbarContributionProvider(admission(5L), host, coordinator);
        Registration second = replacement.apply(5L, contributions);
        assertEquals(8, host.sliders.get(3).value());
        second.close();
        var nextPluginGeneration = new MeshToolbarSliderContributionDescriptor(
                "plugin.a", 2L, "radius", "Radius", 8, 128, 32, 0, ignored -> {});
        Registration third = replacement.apply(5L, List.of(nextPluginGeneration.contribution()));
        assertEquals(32, host.sliders.get(4).value());
        third.close();
    }

    private static MeshTool tool(String id) {
        return new MeshTool() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public String iconResourcePath() {
                return "icons/" + id + ".png";
            }

            @Override
            public void activate(MeshToolContext context) {}
        };
    }

    private static EditorUiProviderAdmission admission(long generation) {
        return EditorUiProviderAdmission.admitted(
                EditorUiFamily.MESH_TOOLBAR,
                generation,
                new EditorUiProviderAdmission.VerificationEvidence(
                        "5.3.03", 1, "a".repeat(64), "ui.mesh-toolbar.contribute", "b".repeat(64)));
    }

    private static final class TestSession implements MeshToolCoordinator.Session {
        private final long hostGeneration;

        TestSession(long hostGeneration) {
            this.hostGeneration = hostGeneration;
        }

        @Override
        public long hostGeneration() {
            return hostGeneration;
        }

        @Override
        public boolean revalidate() {
            return true;
        }

        @Override
        public dev.turboism.sdk.cubism.model.Drawable drawable() {
            return null;
        }

        @Override
        public dev.turboism.sdk.cubism.mesh.VertexSelection selection() {
            return dev.turboism.sdk.cubism.mesh.VertexSelection.empty();
        }

        @Override
        public void select(
                dev.turboism.sdk.cubism.mesh.VertexSelection selection,
                dev.turboism.sdk.cubism.mesh.SelectionMode mode) {}
    }

    private static final class RecordingHost implements MeshToolbarHostOperations {
        final List<String> added = new ArrayList<>();
        final List<String> closed = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final List<Button> buttons = new ArrayList<>();
        final List<Slider> sliders = new ArrayList<>();
        final List<IntConsumer> sliderChanges = new ArrayList<>();
        Runnable rebuild = () -> {};

        @Override
        public ButtonHandle addButton(MeshToolbarContributionDescriptor descriptor, Runnable action) {
            added.add("button:" + descriptor.pluginId() + ":" + descriptor.pluginGeneration() + ":"
                    + descriptor.toolId());
            actions.add(action);
            Button button = new Button(descriptor.toolId(), closed);
            buttons.add(button);
            return button;
        }

        @Override
        public SliderHandle addSlider(MeshToolbarSliderContributionDescriptor descriptor, IntConsumer onChanged) {
            added.add("slider:" + descriptor.pluginId() + ":" + descriptor.pluginGeneration() + ":"
                    + descriptor.controlId());
            Slider slider = new Slider(descriptor.controlId(), descriptor.currentValue(), closed);
            sliders.add(slider);
            sliderChanges.add(onChanged);
            return slider;
        }

        @Override
        public Registration onRebuild(Runnable reconcile) {
            rebuild = reconcile;
            return () -> {};
        }
    }

    private static final class Button implements MeshToolbarHostOperations.ButtonHandle {
        private final String id;
        private final List<String> closed;
        boolean selected;
        boolean done;

        Button(String id, List<String> closed) {
            this.id = id;
            this.closed = closed;
        }

        @Override
        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public void close() {
            if (!done) {
                done = true;
                closed.add("button:" + id);
            }
        }
    }

    private static final class Slider implements MeshToolbarHostOperations.SliderHandle {
        private final String id;
        private final List<String> closed;
        int value;
        boolean done;

        Slider(String id, int value, List<String> closed) {
            this.id = id;
            this.value = value;
            this.closed = closed;
        }

        @Override
        public int value() {
            return value;
        }

        @Override
        public void setValue(int value) {
            this.value = value;
        }

        @Override
        public void close() {
            if (!done) {
                done = true;
                closed.add("slider:" + id);
            }
        }
    }
}
