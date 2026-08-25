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
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ChangeListener;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.ActionListener;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Host-owned Recent Files hover popup bridge, ported from the legacy
 * {@code CubismRecentPreviewManager} popup machinery. The host rebuilds its Recent menu lazily,
 * so this bridge reconciles the current verified menu on selection and popup lifecycle events
 * instead of assuming the menu present at plugin startup remains current.
 */
public final class VerifiedRecentPreviewPopupHostOperations
    implements RecentPreviewContributionAdapter.HostOperations, PreviewCaptureHostOperations.PopupSuppression {

    private static final String PATH_KEY = "turboism.recentPreviewPath";
    private static final String POPUP_KEY = "turboism.recentPreviewPopup";
    private static final String ACTIVE_ITEM_KEY = "turboism.recentPreviewActiveItem";
    private static final String ACTIVE_PROJECT_KEY = "turboism.recentPreviewActiveProject";

    private final VerifiedMemberResolver panelResolver;
    private final Locale locale;
    private final Consumer<String> diagnostics;
    private final Set<String> emittedDiagnostics = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<RecentPreviewRenderer> renderers = new CopyOnWriteArrayList<>();

    /** EDT-owned listener and binding state. */
    private ChangeListener selectionListener;
    private MenuBinding menuBinding;
    private boolean reconcileQueued;
    private boolean selectionHandlingRequested;

    private volatile JPopupMenu activePopupMenu;
    private volatile Popup activePopup;

    public VerifiedRecentPreviewPopupHostOperations(final VerifiedMemberResolver panelResolver) {
        this(panelResolver, dev.turboism.i18n.CubismHostLocale.resolve(), ignored -> { });
    }

    public VerifiedRecentPreviewPopupHostOperations(
        final VerifiedMemberResolver panelResolver,
        final Locale locale
    ) {
        this(panelResolver, locale, ignored -> { });
    }

    public VerifiedRecentPreviewPopupHostOperations(
        final VerifiedMemberResolver panelResolver,
        final Locale locale,
        final Consumer<String> diagnostics
    ) {
        this.panelResolver = Objects.requireNonNull(panelResolver, "panelResolver");
        this.locale = Objects.requireNonNull(locale, "locale");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        RecentMenuChain.PANEL_ALIASES.forEach(panelResolver::verifiedSelector);
    }

    @Override
    public Registration contribute(final RecentPreviewRenderer renderer) {
        Objects.requireNonNull(renderer, "renderer");
        renderers.add(renderer);
        onEventDispatchThread(this::startTracking);
        final AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            renderers.remove(renderer);
            onEventDispatchThread(() -> {
                if (renderers.isEmpty()) stopTracking();
                else hideActivePopupNow();
            });
        };
    }

    @Override
    public void refresh() {
        onEventDispatchThread(this::refreshActivePopup);
    }

    @Override
    public void hide() {
        onEventDispatchThread(this::hideActivePopupNow);
    }

    @Override
    public void restore() {
        // Capture suppression intentionally waits for selection or an explicit refresh.
    }

    private void startTracking() {
        if (renderers.isEmpty()) return;
        if (selectionListener == null) {
            selectionListener = ignored -> queueReconcile(true);
            MenuSelectionManager.defaultManager().addChangeListener(selectionListener);
        }
        reconcileCurrentRecentMenu(false);
    }

    private void stopTracking() {
        hideActivePopupNow();
        if (selectionListener != null) {
            MenuSelectionManager.defaultManager().removeChangeListener(selectionListener);
            selectionListener = null;
        }
        unbindCurrentMenu();
        reconcileQueued = false;
        selectionHandlingRequested = false;
    }

    /** Coalesces the burst of Swing selection/model/container events into one fresh host lookup. */
    private void queueReconcile(final boolean handleSelection) {
        onEventDispatchThread(() -> {
            selectionHandlingRequested |= handleSelection;
            if (reconcileQueued || renderers.isEmpty()) return;
            reconcileQueued = true;
            SwingUtilities.invokeLater(() -> {
                final boolean selected = selectionHandlingRequested;
                reconcileQueued = false;
                selectionHandlingRequested = false;
                if (!renderers.isEmpty()) reconcileCurrentRecentMenu(selected);
            });
        });
    }

    private void reconcileCurrentRecentMenu(final boolean handleSelection) {
        requireEventDispatchThread();
        final JMenu recent;
        try {
            final Object window = RecentMenuChain.resolveWindow(panelResolver);
            recent = RecentMenuChain.recentMenu(panelResolver, window);
        } catch (RuntimeException failure) {
            diagnoseOnce("reconcile-verified-chain", failure);
            if (handleSelection) hideActivePopupNow();
            return;
        }
        if (recent == null) {
            if (handleSelection) diagnoseOnce("reconcile-menu-not-resolved", null);
            return;
        }

        final JPopupMenu popup = recent.getPopupMenu();
        if (menuBinding == null || menuBinding.menu != recent || menuBinding.popup != popup) {
            unbindCurrentMenu();
            menuBinding = bindMenu(recent, popup);
        } else {
            reconcileItems(menuBinding);
        }
        if (handleSelection) handleSelectedPath(menuBinding);
    }

    private MenuBinding bindMenu(final JMenu menu, final JPopupMenu popup) {
        final MenuListener menuListener = new MenuListener() {
            @Override
            public void menuSelected(final MenuEvent event) {
                queueReconcile(true);
            }

            @Override
            public void menuDeselected(final MenuEvent event) {
                hideActivePopupNow();
            }

            @Override
            public void menuCanceled(final MenuEvent event) {
                hideActivePopupNow();
            }
        };
        final PopupMenuListener popupListener = new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(final PopupMenuEvent event) {
                queueReconcile(true);
            }

            @Override
            public void popupMenuWillBecomeInvisible(final PopupMenuEvent event) {
                hideActivePopupNow();
            }

            @Override
            public void popupMenuCanceled(final PopupMenuEvent event) {
                hideActivePopupNow();
            }
        };
        final ContainerListener containerListener = new ContainerAdapter() {
            @Override
            public void componentAdded(final ContainerEvent event) {
                queueReconcile(true);
            }

            @Override
            public void componentRemoved(final ContainerEvent event) {
                queueReconcile(true);
            }
        };
        final MenuBinding binding = new MenuBinding(
            menu, popup, menuListener, popupListener, containerListener
        );
        menu.addMenuListener(menuListener);
        popup.addPopupMenuListener(popupListener);
        popup.addContainerListener(containerListener);
        reconcileItems(binding);
        return binding;
    }

    private void unbindCurrentMenu() {
        final MenuBinding binding = menuBinding;
        menuBinding = null;
        if (binding == null) return;
        hideActivePopupNow();
        binding.menu.removeMenuListener(binding.menuListener);
        binding.popup.removePopupMenuListener(binding.popupListener);
        binding.popup.removeContainerListener(binding.containerListener);
        for (ItemBinding item : new ArrayList<>(binding.items.values())) {
            unbindItem(item);
        }
        binding.items.clear();
    }

    private void reconcileItems(final MenuBinding binding) {
        final IdentityHashMap<JMenuItem, Boolean> present = new IdentityHashMap<>();
        for (Component component : binding.popup.getComponents()) {
            if (!(component instanceof JMenuItem item)) continue;
            present.put(item, Boolean.TRUE);
            if (!binding.items.containsKey(item)) {
                binding.items.put(item, bindItem(item));
            }
            final Path path = RecentMenuChain.firstExistingPath(
                item.getActionCommand(), item.getToolTipText(), item.getText()
            );
            item.putClientProperty(PATH_KEY, path == null ? null : path.toString());
        }
        for (ItemBinding item : new ArrayList<>(binding.items.values())) {
            if (!present.containsKey(item.item)) {
                binding.items.remove(item.item);
                unbindItem(item);
            }
        }
    }

    private ItemBinding bindItem(final JMenuItem item) {
        final MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseEntered(final MouseEvent event) {
                if (isCurrentItem(item)) showForItem(item);
            }

            @Override
            public void mouseExited(final MouseEvent event) {
                hideForItemIfActive(item);
            }

            @Override
            public void mousePressed(final MouseEvent event) {
                hideForItemIfActive(item);
            }
        };
        final ChangeListener change = ignored -> {
            if (!isCurrentItem(item)) return;
            final var model = item.getModel();
            final boolean active = model != null
                && (model.isArmed() || model.isRollover() || model.isSelected());
            if (active) showForItem(item);
            else hideForItemIfActive(item);
        };
        final ActionListener action = ignored -> hideActivePopupNow();
        item.addMouseListener(mouse);
        item.addChangeListener(change);
        item.addActionListener(action);
        return new ItemBinding(item, mouse, change, action);
    }

    private void unbindItem(final ItemBinding binding) {
        binding.item.removeMouseListener(binding.mouse);
        binding.item.removeChangeListener(binding.change);
        binding.item.removeActionListener(binding.action);
        binding.item.putClientProperty(PATH_KEY, null);
    }

    private void handleSelectedPath(final MenuBinding binding) {
        final MenuElement[] selected = MenuSelectionManager.defaultManager().getSelectedPath();
        boolean containsPopup = false;
        JMenuItem selectedItem = null;
        for (int index = selected.length - 1; index >= 0; index--) {
            final MenuElement element = selected[index];
            if (element == null) continue;
            if (element.getComponent() == binding.popup) containsPopup = true;
            if (selectedItem == null && element.getComponent() instanceof JMenuItem item
                && popupMenuOf(item) == binding.popup) {
                selectedItem = item;
            }
        }
        if (!containsPopup || selectedItem == null || !binding.items.containsKey(selectedItem)) {
            hideActivePopupNow();
            return;
        }
        showForItem(selectedItem);
    }

    private boolean isCurrentItem(final JMenuItem item) {
        final MenuBinding binding = menuBinding;
        return binding != null && binding.items.containsKey(item) && popupMenuOf(item) == binding.popup;
    }

    private void showForItem(final JMenuItem item) {
        onEventDispatchThread(() -> {
            if (!isCurrentItem(item)) return;
            try {
                showPopup(item);
            } catch (RuntimeException failure) {
                diagnoseOnce("popup-show", failure);
                hideActivePopupNow();
            }
        });
    }

    private void hideForItemIfActive(final JMenuItem item) {
        final JPopupMenu popupMenu = popupMenuOf(item);
        if (popupMenu != null && popupMenu.getClientProperty(ACTIVE_ITEM_KEY) == item) {
            hidePopup(popupMenu);
        }
    }

    private void showPopup(final JMenuItem item) {
        final JPopupMenu popupMenu = popupMenuOf(item);
        if (popupMenu == null) return;
        final Path path = pathOf(item);
        if (path == null) {
            hideForItemIfActive(item);
            return;
        }
        final String pathKey = RecentMenuChain.pathKey(path);
        if (popupMenu.getClientProperty(ACTIVE_ITEM_KEY) == item
            && pathKey.equals(popupMenu.getClientProperty(ACTIVE_PROJECT_KEY))
            && popupMenu.getClientProperty(POPUP_KEY) instanceof Popup) {
            return;
        }
        final RecentPreviewContent content = renderContent(summaryFor(path));
        if (content == null) {
            hideForItemIfActive(item);
            return;
        }
        if (activePopupMenu != null && activePopupMenu != popupMenu) {
            hideActivePopupNow();
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
        if (!(item instanceof JMenuItem menuItem) || !isCurrentItem(menuItem)) {
            hideActivePopupNow();
            return;
        }
        final Path path = pathOf(menuItem);
        if (path == null) {
            hideActivePopupNow();
            return;
        }
        hidePopup(popupMenu);
        showForItem(menuItem);
    }

    private void hidePopup(final JPopupMenu popupMenu) {
        if (popupMenu == null) return;
        final Object raw = popupMenu.getClientProperty(POPUP_KEY);
        if (raw instanceof Popup popup) {
            try {
                popup.hide();
            } catch (RuntimeException failure) {
                diagnoseOnce("popup-hide", failure);
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

    private void hideActivePopupNow() {
        final JPopupMenu popupMenu = activePopupMenu;
        if (popupMenu != null) {
            hidePopup(popupMenu);
            return;
        }
        final Popup popup = activePopup;
        if (popup != null) {
            try {
                popup.hide();
            } catch (RuntimeException failure) {
                diagnoseOnce("popup-hide", failure);
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
                    if (summary.id().equals(value.id())) return value;
                }
            } catch (RuntimeException failure) {
                diagnoseOnce("renderer", failure);
            }
        }
        return null;
    }

    private void diagnoseOnce(final String stage, final RuntimeException failure) {
        final String code = failure == null ? stage : stage + ":" + failure.getClass().getSimpleName();
        if (!emittedDiagnostics.add(code)) return;
        try {
            diagnostics.accept("popup-diag:" + code);
        } catch (RuntimeException ignored) {
            // Diagnostics must not escape Swing callbacks or alter popup cleanup.
        }
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
            Optional.empty()
        );
    }

    private static Path pathOf(final JMenuItem item) {
        final Object raw = item.getClientProperty(PATH_KEY);
        if (raw instanceof String value && !value.isBlank()) {
            final Path path = RecentMenuChain.existingProjectPath(value);
            if (path != null) return path;
        }
        return RecentMenuChain.firstExistingPath(
            item.getActionCommand(), item.getToolTipText(), item.getText()
        );
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
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }

    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("recent preview menu reconciliation requires the EDT");
        }
    }

    /** Test visibility: the renderer set size. */
    int rendererCountForTest() {
        return renderers.size();
    }

    /** Test visibility: whether EDT-owned menu tracking remains installed. */
    boolean trackingInstalledForTest() {
        requireEventDispatchThread();
        return selectionListener != null || menuBinding != null;
    }

    /** Test visibility: whether this bridge owns the current menu and item binding. */
    boolean ownsBindingForTest(final JMenu menu, final JMenuItem item) {
        requireEventDispatchThread();
        return menuBinding != null
            && menuBinding.menu == menu
            && menuBinding.items.containsKey(item);
    }

    /** Test visibility: the currently active popup, or null. */
    Popup activePopupForTest() {
        return activePopup;
    }

    /** Test visibility: the currently active popup menu, or null. */
    JPopupMenu activePopupMenuForTest() {
        return activePopupMenu;
    }

    private static final class MenuBinding {
        private final JMenu menu;
        private final JPopupMenu popup;
        private final MenuListener menuListener;
        private final PopupMenuListener popupListener;
        private final ContainerListener containerListener;
        private final Map<JMenuItem, ItemBinding> items = new IdentityHashMap<>();

        private MenuBinding(
            final JMenu menu,
            final JPopupMenu popup,
            final MenuListener menuListener,
            final PopupMenuListener popupListener,
            final ContainerListener containerListener
        ) {
            this.menu = menu;
            this.popup = popup;
            this.menuListener = menuListener;
            this.popupListener = popupListener;
            this.containerListener = containerListener;
        }
    }

    private record ItemBinding(
        JMenuItem item,
        MouseAdapter mouse,
        ChangeListener change,
        ActionListener action
    ) {
    }
}
