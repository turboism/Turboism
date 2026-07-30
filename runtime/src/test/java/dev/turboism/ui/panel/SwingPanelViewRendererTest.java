package dev.turboism.ui.panel;

import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.ui.PanelView;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SwingPanelViewRendererTest {

    @Test
    void rendersControlsAndEmitsTypedEventsWithoutCallingPluginCodeDirectly() throws Exception {
        List<String> actions = new ArrayList<>();
        List<Optional<UiActionEvent>> events = new ArrayList<>();
        PanelView view = PanelView.column(
            PanelView.button("run", "Run", "profile.run"),
            PanelView.textInput("name", "Name", "Alice", "profile.name.changed"),
            PanelView.select(
                "mode",
                "Mode",
                List.of(PanelView.option("fast", "Fast"), PanelView.option("safe", "Safe")),
                "fast",
                "profile.mode.changed"
            ),
            PanelView.toggle("enabled", "Enabled", false, "profile.enabled.changed")
        );

        SwingUtilities.invokeAndWait(() -> {
            JComponent rendered = SwingPanelViewRenderer.render(view, (action, event) -> {
                actions.add(action);
                events.add(event);
            });
            assertInstanceOf(AbstractButton.class, named(rendered, "run")).doClick();
            JTextField text = assertInstanceOf(JTextField.class, named(rendered, "name"));
            text.setText("Bob");
            text.postActionEvent();
            @SuppressWarnings("unchecked") JComboBox<PanelView.Option> select =
                (JComboBox<PanelView.Option>) assertInstanceOf(JComboBox.class, named(rendered, "mode"));
            select.setSelectedIndex(1);
            assertInstanceOf(AbstractButton.class, named(rendered, "enabled")).doClick();
        });

        assertEquals(
            List.of(
                "profile.run",
                "profile.name.changed",
                "profile.mode.changed",
                "profile.enabled.changed"
            ),
            actions
        );
        assertEquals(Optional.empty(), events.get(0));
        assertEquals(
            "Bob",
            assertInstanceOf(UiActionEvent.TextValue.class, events.get(1).orElseThrow().value()).value()
        );
        assertEquals(
            "safe",
            assertInstanceOf(UiActionEvent.SelectionValue.class, events.get(2).orElseThrow().value()).value()
        );
        assertEquals(
            true,
            assertInstanceOf(UiActionEvent.ToggleValue.class, events.get(3).orElseThrow().value()).value()
        );
    }

    private static Component named(final Component component, final String name) {
        if (name.equals(component.getName())) {
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component match = namedOrNull(child, name);
                if (match != null) {
                    return match;
                }
            }
        }
        throw new AssertionError("component not found: " + name);
    }

    private static Component namedOrNull(final Component component, final String name) {
        if (name.equals(component.getName())) {
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component match = namedOrNull(child, name);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }
}
