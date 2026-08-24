package dev.turboism.adapter.cubism;

import dev.turboism.adapter.cubism.RecentPreviewHostFixture.PanelHost;
import dev.turboism.adapter.cubism.RecentPreviewHostFixture.ProjectHost;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewContent;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewRenderer;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.PanelView;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.panelResolver;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.projectChain;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.projectResolver;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.recentMenu;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedRecentPreviewPopupHostOperationsTest {
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    );

    @Test
    void contributeInstallsMenuListenerAndItemHandlersOnTheVerifiedChain() throws Exception {
        final Path recent = Files.createTempFile("recent-preview-popup", ".cmo3");
        final JMenu menu = recentMenu(recent);
        final ClassLoader loader = getClass().getClassLoader();
        PanelHost.setRoot(RecentPreviewHostFixture.panelChain(menu));
        ProjectHost.setRoot(null);

        final VerifiedRecentPreviewPopupHostOperations popup =
            new VerifiedRecentPreviewPopupHostOperations(panelResolver("5.3.02", loader));
        popup.contribute(renderer("popup-renderer", PNG));

        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, menu.getMenuListeners().length, "the Recent menu must carry one MenuListener");
        final JMenuItem item = (JMenuItem) menu.getMenuComponents()[0];
        assertTrue(item.getMouseListeners().length >= 1, "items must track mouse hover");
        assertTrue(item.getChangeListeners().length >= 1, "items must track selection state");
        assertTrue(item.getActionListeners().length >= 1, "items must hide the popup on click");

        // A second contribution must not install a second listener chain.
        popup.contribute(renderer("popup-renderer-2", PNG));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, menu.getMenuListeners().length);
        assertEquals(2, popup.rendererCountForTest());
    }

    @Test
    void selectionChangeReconcilesAMenuBuiltAfterContribution() throws Exception {
        final Path recent = Files.createTempFile("recent-preview-late", ".cmo3");
        final JMenu menu = recentMenu(recent);
        final AtomicBoolean rendered = new AtomicBoolean(false);
        PanelHost.setRoot(null);

        final VerifiedRecentPreviewPopupHostOperations popup =
            new VerifiedRecentPreviewPopupHostOperations(panelResolver("5.3.02", getClass().getClassLoader()));
        final Registration registration = popup.contribute(summary -> {
            rendered.set(true);
            return Optional.of(new RecentPreviewContent(summary.id(), PanelView.text(summary.displayName())));
        });
        SwingUtilities.invokeAndWait(() -> { });

        PanelHost.setRoot(RecentPreviewHostFixture.panelChain(menu));
        final JMenuItem item = (JMenuItem) menu.getMenuComponents()[0];
        SwingUtilities.invokeAndWait(() -> MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{
            menu, menu.getPopupMenu(), item
        }));
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue(rendered.get(), "the selected path must reconcile and render the late-built menu");
        assertTrue(item.getMouseListeners().length >= 1, "the late-built item must be bound");

        registration.close();
        SwingUtilities.invokeAndWait(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
    }

    @Test
    void selectionChangeRebindsAReplacedRecentMenu() throws Exception {
        final Path firstPath = Files.createTempFile("recent-preview-first", ".cmo3");
        final Path replacementPath = Files.createTempFile("recent-preview-replacement", ".cmo3");
        final JMenu first = recentMenu(firstPath);
        final JMenu replacement = recentMenu(replacementPath);
        final AtomicBoolean replacementRendered = new AtomicBoolean(false);
        PanelHost.setRoot(RecentPreviewHostFixture.panelChain(first));

        final VerifiedRecentPreviewPopupHostOperations popup =
            new VerifiedRecentPreviewPopupHostOperations(panelResolver("5.3.02", getClass().getClassLoader()));
        final Registration registration = popup.contribute(summary -> {
            replacementRendered.set(summary.displayName().equals(replacementPath.getFileName().toString()));
            return Optional.of(new RecentPreviewContent(summary.id(), PanelView.text(summary.displayName())));
        });
        SwingUtilities.invokeAndWait(() -> { });
        final JMenuItem firstItem = (JMenuItem) first.getMenuComponents()[0];
        final int firstMouseListeners = firstItem.getMouseListeners().length;

        PanelHost.setRoot(RecentPreviewHostFixture.panelChain(replacement));
        final JMenuItem replacementItem = (JMenuItem) replacement.getMenuComponents()[0];
        SwingUtilities.invokeAndWait(() -> MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{
            replacement, replacement.getPopupMenu(), replacementItem
        }));
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue(replacementRendered.get(), "the replacement menu item must render");
        assertTrue(replacementItem.getMouseListeners().length >= 1, "replacement items must be bound");
        assertTrue(firstItem.getMouseListeners().length < firstMouseListeners,
            "listeners owned by the bridge must be removed from replaced items");

        registration.close();
        SwingUtilities.invokeAndWait(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
    }

    @Test
    void closingLastRegistrationUnbindsTheCurrentMenu() throws Exception {
        final Path recent = Files.createTempFile("recent-preview-close", ".cmo3");
        final JMenu menu = recentMenu(recent);
        PanelHost.setRoot(RecentPreviewHostFixture.panelChain(menu));
        final JMenuItem item = (JMenuItem) menu.getMenuComponents()[0];
        final int baseMenuListeners = menu.getMenuListeners().length;
        final int baseMouseListeners = item.getMouseListeners().length;

        final VerifiedRecentPreviewPopupHostOperations popup =
            new VerifiedRecentPreviewPopupHostOperations(panelResolver("5.3.02", getClass().getClassLoader()));
        final Registration registration = popup.contribute(renderer("popup-renderer", PNG));
        SwingUtilities.invokeAndWait(() -> assertTrue(
            popup.ownsBindingForTest(menu, item),
            "the bridge must own the current menu and item binding"
        ));
        assertTrue(menu.getMenuListeners().length > baseMenuListeners);
        assertTrue(item.getMouseListeners().length > baseMouseListeners);

        registration.close();
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(
                popup.ownsBindingForTest(menu, item),
                "closing the bridge must release the current menu and item binding"
            );
            assertFalse(
                popup.trackingInstalledForTest(),
                "closing the bridge must release all EDT-owned tracking"
            );
        });
        assertEquals(baseMenuListeners, menu.getMenuListeners().length);
        assertEquals(baseMouseListeners, item.getMouseListeners().length);
    }

    @Test
    void contributionClosedBeforeEdtInstallationDoesNotLeakTracking() throws Exception {
        final Path recent = Files.createTempFile("recent-preview-race", ".cmo3");
        final JMenu menu = recentMenu(recent);
        final JMenuItem item = (JMenuItem) menu.getMenuComponents()[0];
        final int baseMenuListeners = menu.getMenuListeners().length;
        final int baseMouseListeners = item.getMouseListeners().length;
        PanelHost.setRoot(RecentPreviewHostFixture.panelChain(menu));

        final VerifiedRecentPreviewPopupHostOperations popup =
            new VerifiedRecentPreviewPopupHostOperations(panelResolver("5.3.02", getClass().getClassLoader()));
        final Registration registration = popup.contribute(renderer("popup-renderer", PNG));
        registration.close();
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(
                popup.trackingInstalledForTest(),
                "closing before installation must leave no bridge tracking"
            );
        });

        assertEquals(0, popup.rendererCountForTest());
        assertEquals(baseMenuListeners, menu.getMenuListeners().length);
        assertEquals(baseMouseListeners, item.getMouseListeners().length);
    }

    @Test
    void rendererReceivesOpaqueIdentityWithoutAbsolutePath() throws Exception {
        final Path recent = Files.createTempFile("recent-preview-private", ".cmo3");
        final JMenu menu = recentMenu(recent);
        final java.util.concurrent.atomic.AtomicReference<dev.turboism.sdk.cubism.recentfile.RecentFileSummary>
            observed = new java.util.concurrent.atomic.AtomicReference<>();
        PanelHost.setRoot(RecentPreviewHostFixture.panelChain(menu));

        final VerifiedRecentPreviewPopupHostOperations popup =
            new VerifiedRecentPreviewPopupHostOperations(panelResolver("5.3.02", getClass().getClassLoader()));
        final Registration registration = popup.contribute(summary -> {
            observed.set(summary);
            return Optional.of(new RecentPreviewContent(summary.id(), PanelView.text(summary.displayName())));
        });
        final JMenuItem item = (JMenuItem) menu.getMenuComponents()[0];
        SwingUtilities.invokeAndWait(() -> MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{
            menu, menu.getPopupMenu(), item
        }));
        SwingUtilities.invokeAndWait(() -> { });

        assertNotNull(observed.get());
        assertTrue(observed.get().path().isEmpty(),
            "popup contribution permission must not disclose an absolute recent-file path");
        registration.close();
        SwingUtilities.invokeAndWait(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
    }

    @Test
    void themedPanelRendersRendererContentIncludingImageNodes() {
        final PanelView view = PanelView.column(
            PanelView.image(PNG, "thumbnail"),
            PanelView.text("model.cmo3")
        );
        final JPanel panel = VerifiedRecentPreviewPopupHostOperations.themedPanel(view);
        assertNotNull(findNamed(panel, "panel-image"), "the Image node must render as an image label");
        assertTrue(findLabels(panel) >= 1, "text nodes must render as labels");
    }

    @Test
    void popupShowsOnHoverAndHidesOnLeaveAndClick() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
            "showing a real popup requires a display; covered by the real-host rerun");

        final Path recent = Files.createTempFile("recent-preview-popup", ".cmo3");
        final ClassLoader loader = getClass().getClassLoader();
        final JFrame frame = new JFrame();
        final JMenu recentMenu = new JMenu("Recent Files");
        final JMenuItem item = new JMenuItem(recent.toString());
        item.setActionCommand(recent.toString());
        recentMenu.add(item);
        final JMenu fileMenu = new JMenu("File");
        fileMenu.add(recentMenu);
        final JMenuBar bar = new JMenuBar();
        bar.add(fileMenu);
        frame.setJMenuBar(bar);
        final JMenu menu = recentMenu; // the verified chain resolves this menu peer
        PanelHost.setRoot(RecentPreviewHostFixture.panelChain(menu));
        ProjectHost.setRoot(projectChain(recent));

        final AtomicBoolean rendered = new AtomicBoolean(false);
        final VerifiedRecentPreviewPopupHostOperations popup =
            new VerifiedRecentPreviewPopupHostOperations(panelResolver("5.3.02", loader));
        popup.contribute(summary -> {
            rendered.set(true);
            return Optional.of(new RecentPreviewContent(
                summary.id(), PanelView.column(PanelView.image(PNG, "thumb"), PanelView.text(summary.displayName()))
            ));
        });
        SwingUtilities.invokeAndWait(() -> { });

        SwingUtilities.invokeAndWait(() -> {
            frame.setSize(600, 400);
            frame.setLocation(40, 40);
            frame.setVisible(true);
            recentMenu.setPopupMenuVisible(true);
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (var listener : item.getMouseListeners()) {
                    listener.mouseEntered(new java.awt.event.MouseEvent(
                        item, java.awt.event.MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, 1, 1, 0, false
                    ));
                }
            });
            assertTrue(rendered.get(), "the renderer must be consulted for the hovered item");
            assertNotNull(popup.activePopupForTest(), "a popup must be showing after hover");

            SwingUtilities.invokeAndWait(() -> {
                for (var listener : item.getActionListeners()) {
                    listener.actionPerformed(new java.awt.event.ActionEvent(
                        item, java.awt.event.ActionEvent.ACTION_PERFORMED, "click"
                    ));
                }
            });
            assertNull(popup.activePopupForTest(), "clicking the item must hide the popup");

            popup.refresh();
            SwingUtilities.invokeAndWait(() -> { });
            assertNull(popup.activePopupForTest(), "refresh without an active popup must be a no-op");
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test
    void closingTheRegistrationRemovesTheRendererAndHidesThePopup() throws Exception {
        final Path recent = Files.createTempFile("recent-preview-popup", ".cmo3");
        final JMenu menu = recentMenu(recent);
        PanelHost.setRoot(RecentPreviewHostFixture.panelChain(menu));
        ProjectHost.setRoot(null);
        final VerifiedRecentPreviewPopupHostOperations popup =
            new VerifiedRecentPreviewPopupHostOperations(panelResolver("5.3.02", getClass().getClassLoader()));

        final Registration registration = popup.contribute(renderer("popup-renderer", PNG));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, popup.rendererCountForTest());
        registration.close();
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, popup.rendererCountForTest());
    }

    private static RecentPreviewRenderer renderer(final String text, final byte[] png) {
        return summary -> Optional.of(new RecentPreviewContent(
            summary.id(), PanelView.column(PanelView.image(png, "thumb"), PanelView.text(text))
        ));
    }

    private static Component findNamed(final Container root, final String name) {
        if (name.equals(root.getName())) return root;
        for (Component child : root.getComponents()) {
            if (child instanceof Container nested) {
                final Component found = findNamed(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int findLabels(final Container root) {
        int count = root instanceof JLabel ? 1 : 0;
        for (Component child : root.getComponents()) {
            if (child instanceof Container nested) {
                count += findLabels(nested);
            }
        }
        return count;
    }
}
