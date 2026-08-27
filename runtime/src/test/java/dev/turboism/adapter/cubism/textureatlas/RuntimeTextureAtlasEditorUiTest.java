package dev.turboism.adapter.cubism.textureatlas;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeTextureAtlasEditorUiTest {

    @Test
    void rebindMovesPanelsToTheLatestViewAndCloseDetachesThem() {
        final RuntimeTextureAtlasEditorUi ui = new RuntimeTextureAtlasEditorUi();
        final JPanel first = new JPanel(new BorderLayout());
        final JPanel second = new JPanel(new BorderLayout());
        ui.ingress().accept(first);
        final var panel = ui.attach();
        panel.setText("Atlas statistics");

        final JLabel label = (JLabel) first.getComponent(0);
        assertEquals("Atlas statistics", label.getText());
        assertSame(first, label.getParent());
        assertSame(first, ui.view());

        ui.ingress().accept(second);

        assertEquals(0, first.getComponentCount());
        assertEquals(1, second.getComponentCount());
        assertSame(label, second.getComponent(0));
        assertSame(second, label.getParent());
        assertSame(second, ui.view());

        ui.close();

        assertEquals(0, second.getComponentCount());
        assertNull(label.getParent());
        assertNull(ui.view());
        assertThrows(IllegalStateException.class, ui::attach);
    }

    @Test
    void panelCloseDetachesOnlyItsRegistrationAndIsIdempotent() {
        final RuntimeTextureAtlasEditorUi ui = new RuntimeTextureAtlasEditorUi();
        final JPanel view = new JPanel(new BorderLayout());
        final var first = ui.attach();
        final var second = ui.attach();
        ui.ingress().accept(view);
        assertEquals(2, view.getComponentCount());

        first.close();
        first.close();

        assertEquals(1, view.getComponentCount());
        second.setText("remaining");
        assertEquals("remaining", ((JLabel) view.getComponent(0)).getText());
        ui.close();
        assertEquals(0, view.getComponentCount());
    }

    @Test
    void deactivateDetachesButPreservesPanelsForTheNextHostGeneration() {
        final RuntimeTextureAtlasEditorUi ui = new RuntimeTextureAtlasEditorUi();
        final JPanel first = new JPanel(new BorderLayout());
        final JPanel second = new JPanel(new BorderLayout());
        final var panel = ui.attach();
        panel.setText("Atlas statistics");
        ui.ingress().accept(first);
        final JLabel label = (JLabel) first.getComponent(0);

        ui.deactivate();

        assertEquals(0, first.getComponentCount());
        assertNull(ui.view());
        ui.ingress().accept(second);
        assertSame(second, ui.view());
        assertSame(label, second.getComponent(0));
        assertEquals("Atlas statistics", label.getText());

        ui.close();
    }

    @Test
    void bindingWithoutPluginPanelsDoesNotDisturbNativeLayout() {
        final RuntimeTextureAtlasEditorUi ui = new RuntimeTextureAtlasEditorUi();
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
        final RuntimeTextureAtlasEditorUi ui = new RuntimeTextureAtlasEditorUi();
        final JPanel view = new JPanel(new BorderLayout());

        ui.close();
        ui.ingress().accept(view);

        assertEquals(0, view.getComponentCount());
        assertNull(ui.view());
        assertThrows(IllegalStateException.class, ui::attach);
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
}
