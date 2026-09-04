package dev.turboism.ui.toolbar;

import dev.turboism.ui.palette.LogPaletteHostStructure;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Self-healing production host operations for toolbar buttons on the Cubism Log palette. */
public final class VerifiedPaletteToolbarHostOperations implements PaletteToolbarHostOperations {

    static final String BUTTON_MARKER_KEY = "turboism.paletteToolbar.button";
    static final String WRAPPER_MARKER_KEY = "turboism.paletteToolbar.wrapper";
    static final String ROW_MARKER_KEY = "turboism.paletteToolbar.row";
    static final String BUTTON_NAME = "turboismPaletteToolbarButton";

    private static final int FAST_POLL_DELAY_MS = 250;
    private static final int IDLE_POLL_DELAY_MS = 2_000;
    private static final int FAST_POLL_ATTEMPTS = 300;
    private static final Set<String> WRAPPER_MARKERS = Set.of(
        WRAPPER_MARKER_KEY,
        LogPaletteHostStructure.FILTER_WRAPPER_MARKER_KEY
    );

    private final EditorUiPluginResourceRegistry resources;
    private final LogPaletteRootResolver logPaletteRoot;
    private final Map<String, ButtonContribution> contributions = new LinkedHashMap<>();
    private Binding binding;
    private Timer pollTimer;
    private int pollAttempt;

    public VerifiedPaletteToolbarHostOperations(
        final EditorUiPluginResourceRegistry resources
    ) {
        this(resources, LogPaletteHostStructure::findLogTextPane);
    }

    /** Test seam for replacing the current LOG palette root within one host generation. */
    VerifiedPaletteToolbarHostOperations(
        final EditorUiPluginResourceRegistry resources,
        final LogPaletteRootResolver logPaletteRoot
    ) {
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
                        "duplicate palette toolbar contribution: " + descriptor.nativeId()
                    );
                }
            }
            contributions.clear();
            contributions.putAll(next);
            if (contributions.isEmpty()) {
                stopPolling();
                resetBinding();
                return null;
            }
            try {
                reconcile();
            } finally {
                startPolling();
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
        onEdt(() -> {
            contributions.clear();
            stopPolling();
            resetBinding();
            return null;
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
        final JTextPane pane = logPaletteRoot.resolve();
        if (binding != null && bindingIsCurrent(pane)) {
            syncButtons();
            return;
        }
        resetBinding();
        if (pane == null) {
            throw new IllegalStateException("log-palette-root-not-found");
        }
        final JViewport viewport = LogPaletteHostStructure.findAncestorViewport(pane);
        if (viewport == null || viewport.getParent() == null) {
            throw new IllegalStateException("log-palette-scroll-shell-not-found");
        }
        final Container scrollShell = viewport.getParent();
        Container target = LogPaletteHostStructure.outermostMarkedWrapper(
            scrollShell,
            WRAPPER_MARKERS
        );
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
        syncButtons();
    }

    private boolean bindingIsCurrent(final JTextPane currentPane) {
        if (currentPane == null || currentPane != binding.pane) {
            return false;
        }
        if (binding.wrapper.getParent() != binding.originalParent
            || binding.row.getParent() != binding.wrapper) {
            return false;
        }
        final JViewport viewport = LogPaletteHostStructure.findAncestorViewport(currentPane);
        return viewport != null
            && viewport.getParent() == binding.scrollShell
            && SwingUtilities.isDescendingFrom(currentPane, binding.wrapper);
    }

    private void syncButtons() {
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
                binding.buttons.put(nativeId, new ButtonState(
                    contribution,
                    createButton(contribution)
                ));
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
        button.addActionListener(ignored -> contribution.action().run());
        return button;
    }

    private ImageIcon icon(final PaletteToolbarContributionDescriptor contribution) {
        if (resources == null) {
            return null;
        }
        final URL url = resources.resource(
            contribution.pluginId(),
            contribution.iconResourcePath()
        ).orElse(null);
        return url == null ? null : new ImageIcon(url);
    }

    private void resetBinding() {
        if (binding == null) {
            return;
        }
        binding.buttons.clear();
        binding.row.removeAll();
        if (binding.wrapper.getParent() == binding.originalParent) {
            final Component restored = LogPaletteHostStructure.centerComponent(
                binding.wrapper,
                binding.target
            );
            binding.wrapper.remove(restored);
            LogPaletteHostStructure.replaceComponent(
                binding.originalParent,
                binding.wrapper,
                restored
            );
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
            System.getLogger(getClass().getName()).log(
                System.Logger.Level.WARNING,
                "Palette toolbar lifecycle reconcile failed: " + failure.getMessage()
            );
        }
        pollAttempt++;
        if (pollAttempt == FAST_POLL_ATTEMPTS && pollTimer != null) {
            pollTimer.setDelay(IDLE_POLL_DELAY_MS);
        }
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
            default -> throw new IllegalStateException(
                "palette toolbar anchor is unsupported: " + anchor
            );
        };
    }

    private static <T> T onEdt(final Operation<T> operation) {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.run();
        }
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = operation.run();
                } catch (Throwable throwable) {
                    failure[0] = throwable;
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("palette-toolbar EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("palette-toolbar EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException("palette-toolbar EDT operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
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
            final JPanel right
        ) {
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

    private record ButtonState(ButtonContribution contribution, JButton button) {
    }
}
