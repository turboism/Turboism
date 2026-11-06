package dev.turboism.ui.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.ui.HostViewAnchors;
import dev.turboism.sdk.ui.HostViewPanel;
import dev.turboism.sdk.ui.HostViewPanelHandle;
import dev.turboism.sdk.ui.PanelView;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class RuntimeHostViewPanelRegistryTest {

    private static final String ANCHOR = HostViewAnchors.TEXTURE_ATLAS_EDITOR;

    @Test
    void attachBeforeTheHostViewExistsRendersWhenTheViewArrives() {
        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        final JPanel view = new JPanel(new BorderLayout());
        final HostViewPanelHandle handle = registry.attach(ANCHOR, PanelView.text("before"), 0);

        assertEquals(0, view.getComponentCount(), "an unattached host view is left untouched");

        registry.ingress(ANCHOR).accept(view);

        final JPanel container = (JPanel) view.getComponent(0);
        assertEquals("<html>before</html>", ((JLabel) container.getComponent(0)).getText());
        handle.close();
        registry.close();
    }

    @Test
    void panelsRenderInAttachOrderInsideOneSouthContainer() {
        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        final JPanel view = new JPanel(new BorderLayout());
        registry.attach(ANCHOR, PanelView.text("last"), 5);
        final HostViewPanelHandle middle = registry.attach(ANCHOR, PanelView.text("middle"), 3);
        registry.attach(ANCHOR, PanelView.text("first"), 0);

        registry.ingress(ANCHOR).accept(view);

        assertEquals(1, view.getComponentCount(), "every panel of one anchor shares a single container");
        final JPanel container = (JPanel) view.getComponent(0);
        assertEquals(3, container.getComponentCount());
        assertEquals("<html>first</html>", ((JLabel) container.getComponent(0)).getText());
        assertEquals("<html>middle</html>", ((JLabel) container.getComponent(1)).getText());
        assertEquals("<html>last</html>", ((JLabel) container.getComponent(2)).getText());

        middle.close();
        middle.close();

        assertEquals(2, ((JPanel) view.getComponent(0)).getComponentCount(), "closing a panel is idempotent");
        registry.close();
        assertEquals(0, view.getComponentCount());
    }

    @Test
    void rebindMovesContentToTheLatestViewAndKeepsItVisible() {
        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        final JPanel first = new JPanel(new BorderLayout());
        final JPanel second = new JPanel(new BorderLayout());
        final HostViewPanelHandle handle = registry.attach(ANCHOR, PanelView.text("preserved"), 0);
        registry.ingress(ANCHOR).accept(first);
        final JLabel label = (JLabel) ((JPanel) first.getComponent(0)).getComponent(0);

        registry.ingress(ANCHOR).accept(second);

        assertEquals(0, first.getComponentCount(), "the retired host view is cleaned up");
        assertSame(label, ((JPanel) second.getComponent(0)).getComponent(0), "content survives a host rebuild");
        assertSame(second, registry.view(ANCHOR));

        registry.close();

        assertEquals(0, second.getComponentCount());
        assertNull(label.getParent());
        assertNull(registry.view(ANCHOR));
        assertFalse(registry.isAvailable());
    }

    @Test
    void deactivateDetachesButPreservesPanelsForTheNextHostGeneration() {
        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        final JPanel first = new JPanel(new BorderLayout());
        final JPanel second = new JPanel(new BorderLayout());
        final HostViewPanelHandle handle = registry.attach(ANCHOR, PanelView.text("preserved"), 0);
        registry.ingress(ANCHOR).accept(first);

        registry.deactivate(ANCHOR);

        assertEquals(0, first.getComponentCount());
        assertNull(registry.view(ANCHOR));
        assertTrue(registry.isAvailable(), "deactivation is not a close");

        registry.ingress(ANCHOR).accept(second);

        assertEquals("<html>preserved</html>", ((JLabel) ((JPanel) second.getComponent(0)).getComponent(0)).getText());
        handle.close();
        registry.close();
    }

    @Test
    void contentUpdatesRunOnTheEventDispatchThread() {
        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        final ThreadCheckingPanel view = new ThreadCheckingPanel();
        final HostViewPanelHandle handle = registry.attach(ANCHOR, PanelView.text("initial"), 0);
        registry.ingress(ANCHOR).accept(view);

        handle.setContent(PanelView.text("updated"));

        assertEquals("<html>updated</html>", ((JLabel) ((JPanel) view.getComponent(0)).getComponent(0)).getText());
        handle.close();
        registry.close();
        assertFalse(view.offEdtMutation.get(), "every host mutation runs on the event dispatch thread");
    }

    @Test
    void updatesAfterTheGenerationFenceAreDroppedSilently() {
        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        final JPanel view = new JPanel(new BorderLayout());
        registry.ingress(ANCHOR).accept(view);
        final HostViewPanelHandle handle = registry.attach(ANCHOR, PanelView.text("first"), 0);
        handle.close();

        handle.setContent(PanelView.text("late"));

        assertEquals(0, view.getComponentCount(), "a fenced handle never reattaches its content");
        assertFalse(handle.isVisible());
        registry.close();
    }

    @Test
    void unsupportedAnchorsAndTheUnavailableSentinelFailClosed() {
        assertFalse(HostViewPanel.unavailable().isAvailable());
        assertThrows(
                UnsupportedOperationException.class,
                () -> HostViewPanel.unavailable().attach(ANCHOR, PanelView.text("line"), 0));

        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.attach("cubism.view.unknown", PanelView.text("line"), 0));
        assertTrue(registry.isAvailable());

        registry.close();

        assertThrows(IllegalStateException.class, () -> registry.attach(ANCHOR, PanelView.text("line"), 0));
        assertThrows(IllegalStateException.class, () -> registry.ingress(ANCHOR));
    }

    @Test
    void aPanelInAHiddenHostViewReportsHidden() {
        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        final JPanel view = new JPanel(new BorderLayout());
        final HostViewPanelHandle handle = registry.attach(ANCHOR, PanelView.text("line"), 0);
        registry.ingress(ANCHOR).accept(view);

        assertFalse(handle.isVisible(), "a view that was never shown reports hidden");

        handle.close();

        assertFalse(handle.isVisible(), "a detached panel never reports visible");
        registry.close();
    }

    @Test
    void visibilityFollowsTheShowingHostView() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a displayable host window");
        final RuntimeHostViewPanelRegistry registry = new RuntimeHostViewPanelRegistry();
        final JPanel view = new JPanel(new BorderLayout());
        final HostViewPanelHandle handle = registry.attach(ANCHOR, PanelView.text("line"), 0);
        registry.ingress(ANCHOR).accept(view);
        final JFrame frame = new JFrame("host view fixture");
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.setContentPane(view);
                frame.pack();
                frame.setVisible(true);
            });
            assertTrue(awaitVisible(handle, true), "content in a showing host view reports visible");

            handle.setContent(PanelView.text("updated"));

            assertTrue(awaitVisible(handle, true), "a re-rendered content tree keeps reporting visible");

            SwingUtilities.invokeAndWait(() -> frame.setVisible(false));

            assertTrue(awaitVisible(handle, false), "hiding the host view reports hidden again so polling stops");
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
            handle.close();
            registry.close();
        }
    }

    /** Drains queued EDT hierarchy events until the panel reports the expected visibility. */
    private static boolean awaitVisible(final HostViewPanelHandle handle, final boolean expected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (handle.isVisible() == expected) return true;
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(10L);
        }
        return handle.isVisible() == expected;
    }

    private static final class ThreadCheckingPanel extends JPanel {
        private final AtomicBoolean offEdtMutation = new AtomicBoolean();

        private ThreadCheckingPanel() {
            super(new BorderLayout());
        }

        @Override
        public void add(final Component component, final Object constraints) {
            checkEdt();
            super.add(component, constraints);
        }

        @Override
        public void remove(final Component component) {
            checkEdt();
            super.remove(component);
        }

        @Override
        public void revalidate() {
            checkEdt();
            super.revalidate();
        }

        @Override
        public void repaint() {
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
