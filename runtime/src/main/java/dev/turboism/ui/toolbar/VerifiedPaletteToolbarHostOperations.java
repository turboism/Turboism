package dev.turboism.ui.toolbar;

import dev.turboism.ui.host.EdtDispatch;
import dev.turboism.ui.host.LogPaletteHostStructure;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.event.HierarchyListener;
import java.beans.PropertyChangeListener;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/** Self-healing production host operations for toolbar buttons on the Cubism Log palette. */
public final class VerifiedPaletteToolbarHostOperations implements PaletteToolbarHostOperations {

    static final String BUTTON_MARKER_KEY = "turboism.paletteToolbar.button";
    static final String WRAPPER_MARKER_KEY = "turboism.paletteToolbar.wrapper";
    static final String ROW_MARKER_KEY = "turboism.paletteToolbar.row";
    static final String BUTTON_NAME = "turboismPaletteToolbarButton";

    private static final int FAST_POLL_DELAY_MS = 250;
    /**
     * Unbound-phase safety interval. The palette root can appear with no event Turboism can
     * observe, so the attach search keeps one slow poll; once the toolbar is attached the
     * timer stops entirely and a host hierarchy/look-and-feel event re-arms it.
     */
    private static final int SAFETY_POLL_DELAY_MS = 10_000;

    private static final int FAST_POLL_ATTEMPTS = 300;
    private static final Set<String> WRAPPER_MARKERS =
            Set.of(WRAPPER_MARKER_KEY, LogPaletteHostStructure.FILTER_WRAPPER_MARKER_KEY);

    private final EditorUiPluginResourceRegistry resources;
    private final LogPaletteRootResolver logPaletteRoot;
    private final Map<String, ButtonContribution> contributions = new LinkedHashMap<>();
    /** Coalesced host structure/appearance demand; the pane identity is re-resolved by it. */
    private final HierarchyListener structureWatch = ignored -> structureChanged();

    private final PropertyChangeListener appearanceWatch = event -> {
        if ("lookAndFeel".equals(event.getPropertyName())) requestReconcile();
    };
    private Binding binding;
    private Timer pollTimer;
    private int pollAttempt;
    private int contributionsVersion;
    private int syncedVersion = -1;
    private boolean watchingAppearance;
    private volatile boolean reconcileQueued;
    /** Set while this class mutates the host hierarchy, so its own events are not a demand. */
    private boolean applying;

    public VerifiedPaletteToolbarHostOperations(final EditorUiPluginResourceRegistry resources) {
        this(resources, LogPaletteHostStructure::findLogTextPane);
    }

    /** Test seam for replacing the current LOG palette root within one host generation. */
    VerifiedPaletteToolbarHostOperations(
            final EditorUiPluginResourceRegistry resources, final LogPaletteRootResolver logPaletteRoot) {
        this.resources = resources;
        this.logPaletteRoot = Objects.requireNonNull(logPaletteRoot, "logPaletteRoot");
    }

    @Override
    public void setContributions(final List<ButtonContribution> requested) {
        Objects.requireNonNull(requested, "contributions");
        onEdt(() -> {
            final Map<String, ButtonContribution> next = new LinkedHashMap<>();
            for (ButtonContribution contribution : requested) {
                final PaletteToolbarContributionDescriptor descriptor = contribution.descriptor();
                alignment(descriptor.anchor());
                final ButtonContribution previous = next.put(descriptor.nativeId(), contribution);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate palette toolbar contribution: " + descriptor.nativeId());
                }
            }
            contributions.clear();
            contributions.putAll(next);
            contributionsVersion++;
            if (contributions.isEmpty()) {
                stopPolling();
                unwatchAppearance();
                resetBinding();
                return null;
            }
            watchAppearance();
            try {
                reconcile();
            } finally {
                // A successful reconcile is the event the attach search was waiting for.
                if (binding != null) {
                    stopPolling();
                } else {
                    startPolling();
                }
            }
            return null;
        });
    }

    @Override
    public void reconcileNow() {
        onEdt(() -> {
            reconcile();
            return null;
        });
    }

    @Override
    public void clearContributions() {
        // Removal semantics: on acceptance timeout the clear stays queued instead of
        // being dropped — EDT queue order keeps it ahead of any later setContributions,
        // so a disabled plugin's buttons are never orphaned.
        onEdtEventually(() -> {
            contributions.clear();
            contributionsVersion++;
            stopPolling();
            unwatchAppearance();
            resetBinding();
        });
    }

    @Override
    public boolean hasLiveButtons() {
        return onEdt(() -> binding != null && !binding.buttons.isEmpty());
    }

    private void reconcile() {
        if (contributions.isEmpty()) {
            resetBinding();
            return;
        }
        // resolve() is the only source of truth for pane identity — the host can swap the
        // pane while the old structure still looks attached, so every reconcile trigger
        // (poll, host hierarchy event, look-and-feel event) must re-resolve it.
        final JTextPane pane = logPaletteRoot.resolve();
        if (binding != null && bindingIsCurrent(pane)) {
            if (syncedVersion != contributionsVersion || !buttonsIntact()) {
                syncButtons();
            }
            return;
        }
        applying = true;
        try {
            resetBinding();
            if (pane == null) {
                throw new IllegalStateException("log-palette-root-not-found");
            }
            final JViewport viewport = LogPaletteHostStructure.findAncestorViewport(pane);
            if (viewport == null || viewport.getParent() == null) {
                throw new IllegalStateException("log-palette-scroll-shell-not-found");
            }
            final Container scrollShell = viewport.getParent();
            Container target = LogPaletteHostStructure.outermostMarkedWrapper(scrollShell, WRAPPER_MARKERS);
            if (isToolbarWrapper(target)) {
                target = unwrapStaleToolbarWrapper(target);
            }
            final Container originalParent = target.getParent();
            if (originalParent == null) {
                throw new IllegalStateException("log-palette-toolbar-host-not-attached");
            }

            final JPanel left = buttonGroup(FlowLayout.LEFT);
            final JPanel right = buttonGroup(FlowLayout.RIGHT);
            final JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            row.putClientProperty(ROW_MARKER_KEY, Boolean.TRUE);
            row.add(left, BorderLayout.WEST);
            row.add(right, BorderLayout.EAST);

            final JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.putClientProperty(WRAPPER_MARKER_KEY, Boolean.TRUE);
            wrapper.add(row, BorderLayout.NORTH);
            wrapper.add(target, BorderLayout.CENTER);
            LogPaletteHostStructure.replaceComponent(originalParent, target, wrapper);
            binding = new Binding(pane, scrollShell, target, originalParent, wrapper, row, left, right);
            watchStructure(binding);
            syncButtons();
        } finally {
            applying = false;
        }
    }

    private boolean bindingIsCurrent(final JTextPane currentPane) {
        if (currentPane == null || currentPane != binding.pane) {
            return false;
        }
        if (binding.wrapper.getParent() != binding.originalParent || binding.row.getParent() != binding.wrapper) {
            return false;
        }
        final JViewport viewport = LogPaletteHostStructure.findAncestorViewport(currentPane);
        return viewport != null
                && viewport.getParent() == binding.scrollShell
                && SwingUtilities.isDescendingFrom(currentPane, binding.wrapper);
    }

    private void syncButtons() {
        final boolean outer = applying;
        applying = true;
        try {
            placeButtons();
        } finally {
            applying = outer;
        }
    }

    private void placeButtons() {
        final List<String> vanished = new ArrayList<>(binding.buttons.keySet());
        vanished.removeAll(contributions.keySet());
        for (String nativeId : vanished) {
            removeButton(nativeId);
        }
        for (ButtonContribution contribution : contributions.values()) {
            final String nativeId = contribution.descriptor().nativeId();
            final ButtonState current = binding.buttons.get(nativeId);
            if (current == null || !current.contribution.equals(contribution)) {
                removeButton(nativeId);
                binding.buttons.put(nativeId, new ButtonState(contribution, createButton(contribution)));
            }
        }
        binding.left.removeAll();
        binding.right.removeAll();
        for (ButtonContribution contribution : contributions.values()) {
            final JButton button = binding.buttons.get(contribution.descriptor().nativeId()).button;
            groupFor(contribution.descriptor().anchor()).add(button);
        }
        binding.row.revalidate();
        binding.row.repaint();
        syncedVersion = contributionsVersion;
    }

    /** Cheap per-reconcile check: every contributed button still sits in its anchor group. */
    private boolean buttonsIntact() {
        for (ButtonContribution contribution : contributions.values()) {
            final ButtonState state =
                    binding.buttons.get(contribution.descriptor().nativeId());
            if (state == null
                    || state.button.getParent()
                            != groupFor(contribution.descriptor().anchor())) {
                return false;
            }
        }
        return true;
    }

    private void removeButton(final String nativeId) {
        final ButtonState removed = binding.buttons.remove(nativeId);
        if (removed != null && removed.button.getParent() != null) {
            removed.button.getParent().remove(removed.button);
        }
    }

    private JPanel groupFor(final String anchor) {
        return alignment(anchor) == FlowLayout.LEFT ? binding.left : binding.right;
    }

    private JButton createButton(final ButtonContribution contribution) {
        final PaletteToolbarContributionDescriptor descriptor = contribution.descriptor();
        final JButton button = new JButton(descriptor.label(), icon(descriptor));
        button.setName(BUTTON_NAME);
        button.putClientProperty(BUTTON_MARKER_KEY, descriptor.nativeId());
        button.setToolTipText(descriptor.label());
        button.setFocusable(false);
        button.addHierarchyListener(structureWatch);
        button.addActionListener(ignored -> contribution.action().run());
        return button;
    }

    private ImageIcon icon(final PaletteToolbarContributionDescriptor contribution) {
        if (resources == null) {
            return null;
        }
        final URL url = resources
                .resource(contribution.pluginId(), contribution.iconResourcePath())
                .orElse(null);
        return url == null ? null : new ImageIcon(url);
    }

    private void resetBinding() {
        syncedVersion = -1;
        if (binding == null) {
            return;
        }
        unwatchStructure(binding);
        binding.buttons.clear();
        binding.row.removeAll();
        if (binding.wrapper.getParent() == binding.originalParent) {
            final Component restored = LogPaletteHostStructure.centerComponent(binding.wrapper, binding.target);
            binding.wrapper.remove(restored);
            LogPaletteHostStructure.replaceComponent(binding.originalParent, binding.wrapper, restored);
        }
        binding = null;
    }

    private Container unwrapStaleToolbarWrapper(final Container wrapper) {
        final Container parent = wrapper.getParent();
        if (parent == null) {
            throw new IllegalStateException("stale-palette-toolbar-wrapper-not-attached");
        }
        final Component restored = LogPaletteHostStructure.centerComponent(wrapper, null);
        if (!(restored instanceof Container container)) {
            throw new IllegalStateException("stale-palette-toolbar-wrapper-content-not-found");
        }
        wrapper.remove(restored);
        LogPaletteHostStructure.replaceComponent(parent, wrapper, restored);
        return container;
    }

    private void startPolling() {
        if (pollTimer != null || contributions.isEmpty()) {
            return;
        }
        pollAttempt = 0;
        pollTimer = new Timer(FAST_POLL_DELAY_MS, ignored -> poll());
        pollTimer.setRepeats(true);
        pollTimer.start();
    }

    private void poll() {
        if (contributions.isEmpty()) {
            stopPolling();
            return;
        }
        try {
            reconcile();
        } catch (RuntimeException failure) {
            reportReconcileFailure(failure);
        }
        if (binding != null) {
            // Reconciled: the tree walk is over until a host structure/appearance event
            // arrives or an explicit reconcile is requested.
            stopPolling();
            return;
        }
        pollAttempt++;
        if (pollAttempt == FAST_POLL_ATTEMPTS && pollTimer != null) {
            pollTimer.setDelay(SAFETY_POLL_DELAY_MS);
        }
    }

    /** Host structure event: a demand unless this class caused it. */
    private void structureChanged() {
        if (applying) return;
        requestReconcile();
    }

    /** Coalesces host hierarchy/appearance events into one reconcile on the EDT. */
    private void requestReconcile() {
        if (reconcileQueued) return;
        reconcileQueued = true;
        SwingUtilities.invokeLater(() -> {
            reconcileQueued = false;
            if (contributions.isEmpty()) return;
            try {
                reconcile();
            } catch (RuntimeException failure) {
                reportReconcileFailure(failure);
            }
            if (binding != null) {
                stopPolling();
            } else {
                startPolling();
            }
        });
    }

    private void reportReconcileFailure(final RuntimeException failure) {
        System.getLogger(getClass().getName())
                .log(
                        System.Logger.Level.WARNING,
                        "Palette toolbar lifecycle reconcile failed: " + failure.getMessage());
    }

    private void watchAppearance() {
        if (watchingAppearance) return;
        watchingAppearance = true;
        UIManager.addPropertyChangeListener(appearanceWatch);
    }

    private void unwatchAppearance() {
        if (!watchingAppearance) return;
        watchingAppearance = false;
        UIManager.removePropertyChangeListener(appearanceWatch);
    }

    /** Watches every component whose reparenting can invalidate the installed toolbar. */
    private void watchStructure(final Binding current) {
        current.pane.addHierarchyListener(structureWatch);
        current.target.addHierarchyListener(structureWatch);
        current.wrapper.addHierarchyListener(structureWatch);
        current.row.addHierarchyListener(structureWatch);
    }

    private void unwatchStructure(final Binding current) {
        current.pane.removeHierarchyListener(structureWatch);
        current.target.removeHierarchyListener(structureWatch);
        current.wrapper.removeHierarchyListener(structureWatch);
        current.row.removeHierarchyListener(structureWatch);
        current.buttons.values().forEach(state -> state.button.removeHierarchyListener(structureWatch));
    }

    /** Test visibility: whether the attach search is still polling for the palette root. */
    boolean polling() {
        return pollTimer != null;
    }

    private void stopPolling() {
        if (pollTimer != null) {
            pollTimer.stop();
            pollTimer = null;
        }
        pollAttempt = 0;
    }

    private static JPanel buttonGroup(final int alignment) {
        final JPanel group = new JPanel(new FlowLayout(alignment, 4, 0));
        group.setOpaque(false);
        return group;
    }

    private static boolean isToolbarWrapper(final Container component) {
        return component instanceof JComponent value
                && Boolean.TRUE.equals(value.getClientProperty(WRAPPER_MARKER_KEY));
    }

    private static int alignment(final String anchor) {
        return switch (anchor) {
            case "start", "first" -> FlowLayout.LEFT;
            case "end", "last" -> FlowLayout.RIGHT;
            default -> throw new IllegalStateException("palette toolbar anchor is unsupported: " + anchor);
        };
    }

    private static <T> T onEdt(final Operation<T> operation) {
        // setContributions/reconcile stay "call" semantics: an abandoned update is
        // reported to the caller and the polling reconcile self-heals the visible state.
        return EdtDispatch.call("palette-toolbar EDT operation", operation::run);
    }

    /**
     * Idempotent removal work: on acceptance timeout the task stays queued and still runs
     * exactly once when the EDT drains, so a cleared contribution set is never orphaned.
     */
    private static void onEdtEventually(final Runnable operation) {
        EdtDispatch.runEventually("palette-toolbar EDT removal", operation);
    }

    @FunctionalInterface
    interface LogPaletteRootResolver {
        JTextPane resolve();
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }

    private static final class Binding {
        private final JTextPane pane;
        private final Container scrollShell;
        private final Container target;
        private final Container originalParent;
        private final JPanel wrapper;
        private final JPanel row;
        private final JPanel left;
        private final JPanel right;
        private final Map<String, ButtonState> buttons = new LinkedHashMap<>();

        private Binding(
                final JTextPane pane,
                final Container scrollShell,
                final Container target,
                final Container originalParent,
                final JPanel wrapper,
                final JPanel row,
                final JPanel left,
                final JPanel right) {
            this.pane = pane;
            this.scrollShell = scrollShell;
            this.target = target;
            this.originalParent = originalParent;
            this.wrapper = wrapper;
            this.row = row;
            this.left = left;
            this.right = right;
        }
    }

    private record ButtonState(ButtonContribution contribution, JButton button) {}
}
