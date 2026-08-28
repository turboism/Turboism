package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeTextureAtlasEditorUiTest {

    @Test
    void rebindMovesPanelsToTheLatestViewAndCloseDetachesThem() throws Exception {
        final RuntimeTextureAtlasEditorUi ui = boundUi();
        final JPanel first = new JPanel(new BorderLayout());
        final JPanel second = new JPanel(new BorderLayout());
        ui.ingress().accept(first);
        final var panel = ui.attach();
        panel.setText("Atlas statistics");

        final JPanel panelContainer = (JPanel) first.getComponent(0);
        final JLabel label = (JLabel) panelContainer.getComponent(0);
        assertEquals("Atlas statistics", label.getText());
        assertSame(panelContainer, label.getParent());
        assertSame(first, ui.view());

        ui.ingress().accept(second);

        assertEquals(0, first.getComponentCount());
        assertEquals(1, second.getComponentCount());
        assertSame(label, ((JPanel) second.getComponent(0)).getComponent(0));
        assertSame(second, ((JPanel) label.getParent()).getParent());
        assertSame(second, ui.view());

        ui.close();

        assertEquals(0, second.getComponentCount());
        assertNull(label.getParent());
        assertNull(ui.view());
        assertThrows(IllegalStateException.class, ui::attach);
    }

    @Test
    void pluginPanelsShareOneSouthContainerRatherThanReplacingEachOther() {
        final RuntimeTextureAtlasEditorUi ui = boundUi();
        final JPanel view = new JPanel(new BorderLayout());
        final var first = ui.attach();
        final var second = ui.attach();
        ui.ingress().accept(view);
        assertEquals(1, view.getComponentCount());
        assertEquals(2, ((JPanel) view.getComponent(0)).getComponentCount());

        first.close();
        first.close();

        assertEquals(1, view.getComponentCount());
        assertEquals(1, ((JPanel) view.getComponent(0)).getComponentCount());
        second.setText("remaining");
        assertEquals("remaining", ((JLabel) ((JPanel) view.getComponent(0)).getComponent(0)).getText());
        ui.close();
        assertEquals(0, view.getComponentCount());
    }

    @Test
    void deactivateDetachesButPreservesPanelsForTheNextHostGeneration() {
        final RuntimeTextureAtlasEditorUi ui = boundUi();
        final JPanel first = new JPanel(new BorderLayout());
        final JPanel second = new JPanel(new BorderLayout());
        final var panel = ui.attach();
        panel.setText("Atlas statistics");
        ui.ingress().accept(first);
        final JLabel label = (JLabel) ((JPanel) first.getComponent(0)).getComponent(0);

        ui.deactivate();

        assertEquals(0, first.getComponentCount());
        assertNull(ui.view());
        ui.bind(2, resolver());
        ui.ingress().accept(second);
        assertSame(second, ui.view());
        assertSame(label, ((JPanel) second.getComponent(0)).getComponent(0));
        assertEquals("Atlas statistics", label.getText());

        ui.close();
    }

    @Test
    void bindingWithoutPluginPanelsDoesNotDisturbNativeLayout() {
        final RuntimeTextureAtlasEditorUi ui = boundUi();
        final RecordingPanel view = new RecordingPanel();
        view.add(new JLabel("native"), BorderLayout.CENTER);
        view.reset();

        ui.ingress().accept(view);

        assertSame(view, ui.view());
        assertEquals(1, view.getComponentCount());
        assertEquals(0, view.revalidations);
        assertEquals(0, view.repaints);
        ui.close();
        assertEquals(0, view.revalidations);
        assertEquals(0, view.repaints);
    }

    @Test
    void closeBeforeBindingRejectsLateIngressAndAttachment() {
        final RuntimeTextureAtlasEditorUi ui = boundUi();
        final JPanel view = new JPanel(new BorderLayout());

        ui.close();
        ui.ingress().accept(view);

        assertEquals(0, view.getComponentCount());
        assertNull(ui.view());
        assertThrows(IllegalStateException.class, ui::attach);
    }

    @Test
    void swingMutationsRunOnTheEventDispatchThread() {
        final RuntimeTextureAtlasEditorUi ui = boundUi();
        final ThreadCheckingPanel view = new ThreadCheckingPanel();

        ui.ingress().accept(view);
        final var panel = ui.attach();
        panel.setText("Atlas statistics");
        panel.close();

        assertFalse(view.offEdtMutation.get());
        ui.close();
    }

    private static RuntimeTextureAtlasEditorUi boundUi() {
        final RuntimeTextureAtlasEditorUi ui = new RuntimeTextureAtlasEditorUi();
        ui.bind(1, resolver());
        return ui;
    }

    private static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            Set.of(VerifiedCubism5302TextureAtlasSelectorContract.CAPABILITY_ID),
            List.of(StaticSelector.classSelector("test.ui", Object.class.getName().replace('.', '/'))),
            RuntimeTextureAtlasEditorUiTest.class.getClassLoader()
        );
    }

    private static final class RecordingPanel extends JPanel {
        private int revalidations;
        private int repaints;

        private RecordingPanel() {
            super(new BorderLayout());
        }

        @Override public void revalidate() { revalidations++; }
        @Override public void repaint() { repaints++; }

        private void reset() {
            revalidations = 0;
            repaints = 0;
        }
    }

    private static final class ThreadCheckingPanel extends JPanel {
        private final AtomicBoolean offEdtMutation;

        private ThreadCheckingPanel() {
            super(new BorderLayout());
            offEdtMutation = new AtomicBoolean();
        }

        @Override public void add(final java.awt.Component component, final Object constraints) {
            checkEdt();
            super.add(component, constraints);
        }

        @Override public void remove(final java.awt.Component component) {
            checkEdt();
            super.remove(component);
        }

        @Override public void revalidate() {
            checkEdt();
            super.revalidate();
        }

        @Override public void repaint() {
            checkEdt();
            super.repaint();
        }

        private void checkEdt() {
            if (offEdtMutation != null && !SwingUtilities.isEventDispatchThread()) {
                offEdtMutation.set(true);
            }
        }
    }
}
