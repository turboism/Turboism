package dev.turboism.adapter.cubism;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewContent;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewRenderer;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.ui.panel.SwingPanelViewRenderer;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ChangeListener;
import javax.swing.event.MenuListener;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Host-owned Recent Files hover popup bridge, ported from the legacy
 * {@code CubismRecentPreviewManager} popup machinery: menu listener install on the
 * Recent menu (refresh on open), per-item mouse/selection/action tracking, themed
 * popup next to the active item, hide on click/leave/switch, and self-suppression
 * during capture. Plugins only provide {@link RecentPreviewRenderer}s; the runtime
 * renders their {@link PanelView} into the popup. Safe mode never touches the host.
 */
public final class VerifiedRecentPreviewPopupHostOperations
    implements RecentPreviewContributionAdapter.HostOperations, PreviewCaptureHostOperations.PopupSuppression {

    private static final String INSTALLED_KEY = "turboism.recentPreviewInstalled";
    private static final String PATH_KEY = "turboism.recentPreviewPath";
    private static final String POPUP_KEY = "turboism.recentPreviewPopup";
    private static final String ACTIVE_ITEM_KEY = "turboism.recentPreviewActiveItem";
    private static final String ACTIVE_PROJECT_KEY = "turboism.recentPreviewActiveProject";
    private static final String MENU_LISTENER_KEY = "turboism.recentPreviewMenuListener";

    private final VerifiedMemberResolver panelResolver;
    private final Locale locale;
    private final CopyOnWriteArrayList<RecentPreviewRenderer> renderers = new CopyOnWriteArrayList<>();

    private volatile JPopupMenu activePopupMenu;
    private volatile Popup activePopup;

    public VerifiedRecentPreviewPopupHostOperations(final VerifiedMemberResolver panelResolver) {
        this(panelResolver, dev.turboism.i18n.CubismHostLocale.resolve());
    }

    public VerifiedRecentPreviewPopupHostOperations(
        final VerifiedMemberResolver panelResolver,
        final Locale locale
    ) {
        this.panelResolver = Objects.requireNonNull(panelResolver, "panelResolver");
        this.locale = Objects.requireNonNull(locale, "locale");
        RecentMenuChain.PANEL_ALIASES.forEach(panelResolver::verifiedSelector);
    }

    @Override
    public Registration contribute(final RecentPreviewRenderer renderer) {
        Objects.requireNonNull(renderer, "renderer");
        renderers.add(renderer);
        installOnEventDispatchThread();
        return () -> {
            renderers.remove(renderer);
            hideActivePopup();
        };
    }

    @Override
    public void refresh() {
        onEventDispatchThread(this::refreshActivePopup);
    }

    @Override
    public void hide() {
        hideActivePopup();
    }

    @Override
    public void restore() {
        // The popup stays hidden after a capture until the next hover event.
    }

    /** Menu chain + listener install; only the first contribution installs. */
    private void installOnEventDispatchThread() {
        if (renderers.size() != 1) return;
        onEventDispatchThread(() -> {
            try {
                final Object window = RecentMenuChain.resolveWindow(panelResolver);
                final JMenu recent = RecentMenuChain.recentMenu(panelResolver, window);
                if (recent == null) return;
                installMenuListener(recent);
                installItemHandlers(recent);
            } catch (RuntimeException ignored) {
                // fail closed: no popup without the verified chain
            }
        });
    }

    private void installMenuListener(final JMenu recent) {
        if (Boolean.TRUE.equals(recent.getClientProperty(MENU_LISTENER_KEY))) return;
        final MenuListener listener = new MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent event) {
                installItemHandlers(recent);
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent event) {
                hideActivePopup();
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent event) {
                hideActivePopup();
            }
        };
        recent.addMenuListener(listener);
        recent.putClientProperty(MENU_LISTENER_KEY, Boolean.TRUE);
    }

    /** Installs per-item handlers once per item; rebinds the item→path mapping. */
    private void installItemHandlers(final JMenu recent) {
        for (Component component : recent.getMenuComponents()) {
            if (!(component instanceof JMenuItem item)) continue;
            if (!Boolean.TRUE.equals(item.getClientProperty(INSTALLED_KEY))) {
                item.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(final MouseEvent event) {
                        showForItem(item);
                    }

                    @Override
                    public void mousePressed(final MouseEvent event) {
                        hideForItem(item);
                    }
                });
                item.addChangeListener(changeListener(item));
                item.addActionListener(actionListener());
                item.putClientProperty(INSTALLED_KEY, Boolean.TRUE);
            }
            final Path path = RecentMenuChain.firstExistingPath(
                item.getActionCommand(), item.getToolTipText(), item.getText()
            );
            if (path != null) {
                item.putClientProperty(PATH_KEY, path.toString());
            }
        }
    }

    private ChangeListener changeListener(final JMenuItem item) {
        return event -> {
            final var model = item.getModel();
            final boolean active = model != null
                && (model.isArmed() || model.isRollover() || model.isSelected());
            if (active) {
                showForItem(item);
            } else {
                hideForItem(item);
            }
        };
    }

    private ActionListener actionListener() {
        return event -> hideActivePopup();
    }

    private void showForItem(final JMenuItem item) {
        onEventDispatchThread(() -> {
            try {
                showPopup(item);
            } catch (RuntimeException ignored) {
                // fail closed: a broken popup must never escape the EDT
            }
        });
    }

    private void hideForItem(final JMenuItem item) {
        final JPopupMenu popupMenu = popupMenuOf(item);
        if (popupMenu != null) {
            hidePopup(popupMenu);
        } else {
            hideActivePopup();
        }
    }

    private void showPopup(final JMenuItem item) {
        final JPopupMenu popupMenu = popupMenuOf(item);
        if (popupMenu == null) return;
        final Path path = pathOf(item);
        if (path == null) {
            hidePopup(popupMenu);
            return;
        }
        final String pathKey = RecentMenuChain.pathKey(path);
        if (popupMenu.getClientProperty(ACTIVE_ITEM_KEY) == item
            && pathKey.equals(popupMenu.getClientProperty(ACTIVE_PROJECT_KEY))
            && popupMenu.getClientProperty(POPUP_KEY) instanceof Popup) {
            return; // already showing for this item
        }
        final RecentPreviewContent content = renderContent(summaryFor(path));
        if (content == null) {
            hidePopup(popupMenu);
            return;
        }
        if (activePopupMenu != null && activePopupMenu != popupMenu) {
            hideActivePopup();
        }
        hidePopup(popupMenu);
        final JPanel panel = themedPanel(content.view(), locale);
        final Point location = new Point(item.getWidth() + 8, 0);
        SwingUtilities.convertPointToScreen(location, item);
        final Popup popup = PopupFactory.getSharedInstance().getPopup(
            item, panel, location.x, location.y
        );
        popup.show();
        popupMenu.putClientProperty(POPUP_KEY, popup);
        popupMenu.putClientProperty(ACTIVE_ITEM_KEY, item);
        popupMenu.putClientProperty(ACTIVE_PROJECT_KEY, pathKey);
        activePopupMenu = popupMenu;
        activePopup = popup;
    }

    private void refreshActivePopup() {
        final JPopupMenu popupMenu = activePopupMenu;
        if (popupMenu == null) return;
        final Object item = popupMenu.getClientProperty(ACTIVE_ITEM_KEY);
        if (!(item instanceof JMenuItem menuItem)) return;
        final Path path = pathOf(menuItem);
        if (path == null) {
            hideActivePopup();
            return;
        }
        // Re-render in place: keep the popup position, swap the content panel.
        hidePopup(popupMenu);
        showPopup(menuItem);
    }

    private void hidePopup(final JPopupMenu popupMenu) {
        if (popupMenu == null) return;
        final Object raw = popupMenu.getClientProperty(POPUP_KEY);
        if (raw instanceof Popup popup) {
            try {
                popup.hide();
            } catch (RuntimeException ignored) {
                // diagnostics only
            }
        }
        popupMenu.putClientProperty(POPUP_KEY, null);
        popupMenu.putClientProperty(ACTIVE_ITEM_KEY, null);
        popupMenu.putClientProperty(ACTIVE_PROJECT_KEY, null);
        if (activePopupMenu == popupMenu) {
            activePopupMenu = null;
            activePopup = null;
        }
    }

    private void hideActivePopup() {
        final JPopupMenu popupMenu = activePopupMenu;
        if (popupMenu != null) {
            hidePopup(popupMenu);
            return;
        }
        final Popup popup = activePopup;
        if (popup != null) {
            try {
                popup.hide();
            } catch (RuntimeException ignored) {
                // diagnostics only
            }
            activePopup = null;
        }
    }

    private RecentPreviewContent renderContent(final RecentFileSummary summary) {
        for (RecentPreviewRenderer renderer : renderers) {
            try {
                final Optional<RecentPreviewContent> content = renderer.render(summary);
                if (content.isPresent()) {
                    final RecentPreviewContent value = content.orElseThrow();
                    if (summary.id().equals(value.id())) {
                        return value;
                    }
                }
            } catch (RuntimeException ignored) {
                // one broken renderer must not break the popup for every plugin
            }
        }
        return null;
    }

    private static RecentFileSummary summaryFor(final Path path) {
        Instant lastModified;
        try {
            lastModified = Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis());
        } catch (Exception unavailable) {
            lastModified = null;
        }
        return new RecentFileSummary(
            VerifiedRecentFileListHostOperations.idFor(path),
            path.getFileName().toString(),
            Optional.ofNullable(lastModified),
            Optional.of(path.toString())
        );
    }

    private static Path pathOf(final JMenuItem item) {
        final Object raw = item.getClientProperty(PATH_KEY);
        if (raw instanceof String value && !value.isBlank()) {
            try {
                final Path path = Path.of(value);
                return Files.isRegularFile(path) ? path : null;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return RecentMenuChain.firstExistingPath(item.getActionCommand(), item.getToolTipText(), item.getText());
    }

    private static JPopupMenu popupMenuOf(final JMenuItem item) {
        final Container ancestor = SwingUtilities.getAncestorOfClass(JPopupMenu.class, item);
        return ancestor instanceof JPopupMenu popupMenu ? popupMenu : null;
    }

    /** Themed popup panel wrapping the renderer's PanelView (legacy applyRecentPreviewTheme). */
    static JPanel themedPanel(final PanelView view) {
        return themedPanel(view, dev.turboism.i18n.CubismHostLocale.resolve());
    }

    /** Uses the caller's already-resolved effective locale; no second locale resolution. */
    static JPanel themedPanel(final PanelView view, final Locale locale) {
        final boolean dark = isDarkMode();
        final Color background = dark ? new Color(28, 30, 34) : new Color(246, 247, 249);
        final Color border = dark ? new Color(74, 78, 88) : new Color(196, 201, 208);
        final Color foreground = dark ? new Color(232, 235, 240) : new Color(31, 35, 40);
        final JComponent rendered = SwingPanelViewRenderer.render(view, (id, event) -> { }, locale);
        final JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(background);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(border, 1),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));
        panel.add(rendered, BorderLayout.CENTER);
        themeLabels(rendered, foreground, background);
        return panel;
    }

    private static void themeLabels(final Component component, final Color foreground, final Color background) {
        if (component instanceof JLabel label) {
            label.setForeground(foreground);
            label.setBackground(background);
            label.setOpaque(false);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                themeLabels(child, foreground, background);
            }
        }
    }

    /** Legacy isLikelyDarkLookAndFeel semantics: dark marker in the active LAF name/class. */
    static boolean isDarkMode() {
        final javax.swing.LookAndFeel lookAndFeel = UIManager.getLookAndFeel();
        if (lookAndFeel == null) return false;
        final String signature = (lookAndFeel.getName() + " " + lookAndFeel.getClass().getName())
            .toLowerCase(Locale.ROOT);
        return signature.contains("dark") || signature.contains("darcular") || signature.contains("moonlight")
            || signature.contains("night") || signature.contains("intellij") && signature.contains("dark");
    }

    private void onEventDispatchThread(final Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    /** Test visibility: the renderer set size. */
    int rendererCountForTest() {
        return renderers.size();
    }

    /** Test visibility: the currently active popup, or null. */
    Popup activePopupForTest() {
        return activePopup;
    }

    /** Test visibility: the currently active popup menu, or null. */
    JPopupMenu activePopupMenuForTest() {
        return activePopupMenu;
    }
}
