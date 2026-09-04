package dev.turboism.ui.toolbar;

import dev.turboism.ui.palette.LogPaletteHostStructure;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedPaletteToolbarHostOperationsTest {

    @Test
    void attachesRoutesActionsReconcilesDeltasAndRestoresHost() {
        final LogPaletteFixture fixture = new LogPaletteFixture();
        final VerifiedPaletteToolbarHostOperations host = host(fixture);
        final AtomicInteger firstClicks = new AtomicInteger();
        final AtomicInteger secondClicks = new AtomicInteger();

        host.setContributions(List.of(
            button("plugin-a", "first", "start", firstClicks::incrementAndGet),
            button("plugin-b", "second", "end", secondClicks::incrementAndGet)
        ));

        assertEquals(2, countButtons(fixture.root));
        findButton(fixture.root, "plugin-b:second").doClick();
        assertEquals(1, secondClicks.get());
        assertTrue(host.hasLiveButtons());

        host.reconcileNow();
        assertEquals(2, countButtons(fixture.root), "reconcile must be idempotent");
        host.setContributions(List.of(
            button("plugin-a", "first", "start", firstClicks::incrementAndGet)
        ));
        assertEquals(1, countButtons(fixture.root));
        assertNotNull(findButton(fixture.root, "plugin-a:first"));

        host.clearContributions();
        assertSame(fixture.parent, fixture.scroll.getParent());
        assertEquals(0, countButtons(fixture.root));
        assertFalse(host.hasLiveButtons());
    }

    @Test
    void closeAndReopenWithinOneGenerationReattachesWithoutDuplicates() {
        final LogPaletteFixture first = new LogPaletteFixture();
        final LogPaletteFixture reopened = new LogPaletteFixture();
        final AtomicReference<JTextPane> currentPane = new AtomicReference<>(first.pane);
        final VerifiedPaletteToolbarHostOperations host =
            new VerifiedPaletteToolbarHostOperations(null, currentPane::get);

        host.setContributions(List.of(button("plugin-a", "toggle", "end", () -> { })));
        final Container oldWrapper = first.scroll.getParent();
        assertTrue(isToolbarWrapper(oldWrapper));

        currentPane.set(reopened.pane);
        host.reconcileNow();

        assertSame(first.parent, first.scroll.getParent(), "old host structure must be restored");
        assertNull(oldWrapper.getParent(), "old wrapper must not remain nested");
        assertEquals(0, countButtons(first.root));
        assertEquals(1, countButtons(reopened.root));
        host.reconcileNow();
        assertEquals(1, countButtons(reopened.root), "repeated lifecycle ticks must not duplicate");
        host.clearContributions();
    }

    @Test
    void filterWrapperAttachedBeforeToolbarSurvivesToolbarCleanup() {
        final LogPaletteFixture fixture = new LogPaletteFixture();
        final JPanel filterWrapper = wrap(
            LogPaletteHostStructure.FILTER_WRAPPER_MARKER_KEY,
            fixture.scroll
        );
        LogPaletteHostStructure.replaceComponent(fixture.parent, fixture.scroll, filterWrapper);
        final VerifiedPaletteToolbarHostOperations host = host(fixture);

        host.setContributions(List.of(button("plugin-a", "toggle", "end", () -> { })));
        assertSame(filterWrapper, fixture.scroll.getParent());
        assertTrue(isToolbarWrapper(filterWrapper.getParent()));

        host.clearContributions();
        assertSame(fixture.parent, filterWrapper.getParent());
        assertSame(filterWrapper, fixture.scroll.getParent());
    }

    @Test
    void filterWrapperAttachedAfterToolbarSurvivesToolbarCleanup() {
        final LogPaletteFixture fixture = new LogPaletteFixture();
        final VerifiedPaletteToolbarHostOperations host = host(fixture);
        host.setContributions(List.of(button("plugin-a", "toggle", "end", () -> { })));
        final Container toolbarWrapper = fixture.scroll.getParent();
        final JPanel filterWrapper = wrap(
            LogPaletteHostStructure.FILTER_WRAPPER_MARKER_KEY,
            fixture.scroll
        );
        LogPaletteHostStructure.replaceComponent(toolbarWrapper, fixture.scroll, filterWrapper);

        host.clearContributions();

        assertSame(fixture.parent, filterWrapper.getParent());
        assertSame(filterWrapper, fixture.scroll.getParent());
        assertNull(toolbarWrapper.getParent());
    }

    @Test
    void missingRootAndUnsupportedAnchorFailClosed() {
        final VerifiedPaletteToolbarHostOperations missing =
            new VerifiedPaletteToolbarHostOperations(null, () -> null);
        assertEquals("log-palette-root-not-found", assertThrows(
            IllegalStateException.class,
            () -> missing.setContributions(List.of(
                button("plugin-a", "toggle", "end", () -> { })
            ))
        ).getMessage());
        missing.clearContributions();

        final LogPaletteFixture fixture = new LogPaletteFixture();
        final VerifiedPaletteToolbarHostOperations host = host(fixture);
        assertThrows(IllegalStateException.class, () -> host.setContributions(List.of(
            button("plugin-a", "toggle", "middle", () -> { })
        )));
        assertEquals(0, countButtons(fixture.root));
        host.clearContributions();
    }

    private static VerifiedPaletteToolbarHostOperations host(final LogPaletteFixture fixture) {
        return new VerifiedPaletteToolbarHostOperations(null, () -> fixture.pane);
    }

    private static PaletteToolbarHostOperations.ButtonContribution button(
        final String pluginId,
        final String id,
        final String anchor,
        final Runnable action
    ) {
        return new PaletteToolbarHostOperations.ButtonContribution(
            new PaletteToolbarContributionDescriptor(
                pluginId,
                id,
                "action." + id,
                "label." + id,
                "icons/" + id + ".svg",
                "LOG",
                anchor,
                100
            ),
            action
        );
    }

    private static JPanel wrap(final String marker, final Container child) {
        final JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.putClientProperty(marker, Boolean.TRUE);
        wrapper.add(new JPanel(), BorderLayout.NORTH);
        wrapper.add(child, BorderLayout.CENTER);
        return wrapper;
    }

    private static boolean isToolbarWrapper(final Component component) {
        return component instanceof JComponent value
            && Boolean.TRUE.equals(value.getClientProperty(
                VerifiedPaletteToolbarHostOperations.WRAPPER_MARKER_KEY
            ));
    }

    private static JButton findButton(final Component root, final String nativeId) {
        if (root instanceof JButton button
            && nativeId.equals(button.getClientProperty(
                VerifiedPaletteToolbarHostOperations.BUTTON_MARKER_KEY
            ))) {
            return button;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JButton found = findButton(child, nativeId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int countButtons(final Component root) {
        if (root instanceof JButton button
            && VerifiedPaletteToolbarHostOperations.BUTTON_NAME.equals(button.getName())) {
            return 1;
        }
        int count = 0;
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                count += countButtons(child);
            }
        }
        return count;
    }

    private static final class LogPaletteFixture {
        private final JPanel root = new JPanel(new BorderLayout());
        private final JPanel parent = new JPanel(new BorderLayout());
        private final JPanel nativeToolbar = new JPanel();
        private final JButton nativeClear = new JButton("clear");
        private final JTextPane pane = new JTextPane();
        private final JScrollPane scroll = new JScrollPane(pane);

        private LogPaletteFixture() {
            nativeToolbar.add(nativeClear);
            parent.add(nativeToolbar, BorderLayout.NORTH);
            pane.setEditable(false);
            parent.add(scroll, BorderLayout.CENTER);
            root.add(parent, BorderLayout.CENTER);
        }
    }
}
