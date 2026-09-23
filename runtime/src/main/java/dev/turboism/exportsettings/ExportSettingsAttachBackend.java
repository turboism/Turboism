package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.plugin.Registration;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.HierarchyEvent;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Inert, injectable attachment backend for the embedded-model Export Settings UI.
 *
 * <p>This class only renders a supplied option snapshot into the caller-supplied
 * dialog content: the owned panel is appended inside the dialog's native options
 * container, after the last native option and before the button area. It does not
 * discover host windows, resolve localization, invoke plugin callbacks, mutate a
 * model, or execute an export.</p>
 */
public final class ExportSettingsAttachBackend {

    /** Stable rejection identities surfaced through {@link ExportSettingsAttachException}. */
    public static final String NULL_CONTAINER_KEY = "export-settings.attach.null-container";
    public static final String NULL_CONTRIBUTIONS_KEY = "export-settings.attach.null-contributions";
    public static final String NULL_CONTRIBUTION_KEY = "export-settings.attach.null-contribution";
    public static final String DUPLICATE_OPTION_KEY = "export-settings.attach.duplicate-option";
    public static final String ALREADY_ATTACHED_KEY = "export-settings.attach.already-attached";
    public static final String CLOSED_KEY = "export-settings.attach.closed";
    public static final String NOT_ATTACHED_KEY = "export-settings.attach.not-attached";
    public static final String INTERRUPTED_KEY = "export-settings.attach.interrupted";
    public static final String EDT_TIMEOUT_KEY = "export-settings.attach.edt-timeout";
    public static final String BOUNDARY_FAILURE_KEY = "export-settings.attach.boundary-failure";
    public static final String OPTIONS_CONTAINER_KEY = "export-settings.attach.options-container";

    private static final long EDT_TIMEOUT_MILLIS = 5_000L;
    private static final int MAX_TREE_NODES = 1024;

    private final Object lifecycleLock = new Object();
    private final Function<String, JCheckBox> checkboxFactory;
    private final Function<Container, Component> topLevelResolver;
    private boolean closed;
    private boolean attaching;
    private Container container;
    private JPanel ownedPanel;
    private Map<String, JCheckBox> checkboxes;
    private DialogGrowth growth;

    public ExportSettingsAttachBackend() {
        this(JCheckBox::new);
    }

    /** Test-only checkbox factory seam; the default factory yields default-off checkboxes. */
    ExportSettingsAttachBackend(final Function<String, JCheckBox> checkboxFactory) {
        this(checkboxFactory, SwingUtilities::getWindowAncestor);
    }

    /** Test-only seams: checkbox factory and top-level window lookup. */
    ExportSettingsAttachBackend(
        final Function<String, JCheckBox> checkboxFactory,
        final Function<Container, Component> topLevelResolver
    ) {
        this.checkboxFactory = Objects.requireNonNull(checkboxFactory, "checkboxFactory");
        this.topLevelResolver =
            Objects.requireNonNull(topLevelResolver, "topLevelResolver");
    }

    /** Materializes one owned panel from contribution descriptors. */
    public Registration attach(
        final Container container,
        final List<ExportSettingsContribution> contributions
    ) {
        return attachInternal(container, validatedSnapshot(contributions), false);
    }

    /** Materializes one owned panel from a localized, plugin-owned option snapshot. */
    public Registration attachResolved(
        final Container container,
        final List<ExportSettingsOptionSnapshot> options
    ) {
        return attachInternal(container, validatedOptionSnapshot(options), true);
    }

    /** Returns the current checkbox selection, keyed by the supplied option ids. */
    public Map<String, Boolean> selectedSnapshot() {
        final Map<String, JCheckBox> boxes;
        synchronized (lifecycleLock) {
            boxes = checkboxes;
            if (boxes == null || closed) {
                throw new ExportSettingsAttachException(NOT_ATTACHED_KEY);
            }
        }
        try {
            return onEdt(() -> readSelection(boxes), true);
        } catch (ExportSettingsAttachException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new ExportSettingsAttachException(BOUNDARY_FAILURE_KEY, failure);
        }
    }

    private Registration attachInternal(
        final Container requestedContainer,
        final List<?> requested,
        final boolean resolved
    ) {
        if (requestedContainer == null) {
            throw new ExportSettingsAttachException(NULL_CONTAINER_KEY);
        }
        synchronized (lifecycleLock) {
            if (closed) {
                throw new ExportSettingsAttachException(CLOSED_KEY);
            }
            if (container != null || attaching) {
                throw new ExportSettingsAttachException(ALREADY_ATTACHED_KEY);
            }
            attaching = true;
        }

        AttachmentParts parts = null;
        try {
            parts = onEdt(() -> resolved
                ? materializeResolved(requestedContainer, castResolved(requested))
                : materializeContributions(requestedContainer, castContributions(requested)), true);
            final boolean becameClosed;
            synchronized (lifecycleLock) {
                becameClosed = closed;
                attaching = false;
                if (!becameClosed) {
                    container = parts.mount();
                    ownedPanel = parts.panel();
                    checkboxes = Collections.unmodifiableMap(parts.checkboxes());
                    growth = parts.growth();
                }
            }
            if (becameClosed) {
                removeParts(parts.mount(), parts.panel(), parts.growth());
                throw new ExportSettingsAttachException(CLOSED_KEY);
            }
            return this::closeInternal;
        } catch (ExportSettingsAttachException failure) {
            synchronized (lifecycleLock) {
                attaching = false;
            }
            if (parts != null) {
                removeParts(parts.mount(), parts.panel(), parts.growth());
            }
            throw failure;
        } catch (Throwable failure) {
            synchronized (lifecycleLock) {
                attaching = false;
            }
            if (parts != null) {
                removeParts(parts.mount(), parts.panel(), parts.growth());
            }
            throw new ExportSettingsAttachException(BOUNDARY_FAILURE_KEY, failure);
        }
    }

    private AttachmentParts materializeContributions(
        final Container target,
        final List<ExportSettingsContribution> requested
    ) {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        final Map<String, JCheckBox> boxes = new LinkedHashMap<>();
        DialogGrowth dialogGrowth = null;
        try {
            for (ExportSettingsContribution contribution : requested) {
                final JCheckBox box = Objects.requireNonNull(
                    checkboxFactory.apply(contribution.labelKey()),
                    "checkboxFactory result"
                );
                panel.add(box);
                boxes.put(contribution.optionId(), box);
            }
            final Container mount = commitOrRollback(target, panel);
            dialogGrowth = new DialogGrowth(topLevelResolver, mount, panel);
            dialogGrowth.arm();
            return new AttachmentParts(panel, boxes, mount, dialogGrowth);
        } catch (Throwable failure) {
            if (dialogGrowth != null) {
                dialogGrowth.undo();
            }
            rollback(target, panel, failure);
            throw failure;
        }
    }

    private AttachmentParts materializeResolved(
        final Container target,
        final List<ExportSettingsOptionSnapshot> requested
    ) {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        final Map<String, JCheckBox> boxes = new LinkedHashMap<>();
        DialogGrowth dialogGrowth = null;
        try {
            for (ExportSettingsOptionSnapshot option : requested) {
                final JCheckBox box = Objects.requireNonNull(
                    checkboxFactory.apply(option.label()),
                    "checkboxFactory result"
                );
                panel.add(box);
                boxes.put(option.optionId(), box);
            }
            final Container mount = commitOrRollback(target, panel);
            dialogGrowth = new DialogGrowth(topLevelResolver, mount, panel);
            dialogGrowth.arm();
            return new AttachmentParts(panel, boxes, mount, dialogGrowth);
        } catch (Throwable failure) {
            if (dialogGrowth != null) {
                dialogGrowth.undo();
            }
            rollback(target, panel, failure);
            throw failure;
        }
    }

    /**
     * Appends the owned panel inside the dialog's native options container and
     * returns that container so teardown removes exactly what was added. The
     * supplied container is the dialog content pane; its south region belongs to
     * the native button row, so the contribution must never be mounted there.
     */
    private static Container commitOrRollback(final Container target, final JPanel panel) {
        final Container mount = optionsContainer(target);
        if (mount == null) {
            throw new ExportSettingsAttachException(OPTIONS_CONTAINER_KEY);
        }
        mount.add(panel);
        mount.revalidate();
        mount.repaint();
        return mount;
    }

    /**
     * The native export dialog builds its options in a component-order container
     * whose leaves are the host's own check-box subclasses (FlatLaf tri-state
     * buttons), so the options list is the container with the most direct native
     * check-box children. The runtime's owned rows are plain {@link JCheckBox}
     * instances and are never counted, which also keeps a leaked owned panel from
     * ever being picked as its own mount. Returns {@code null} when the supplied
     * tree carries no native option at all — mounting anywhere else would land
     * the contribution outside the options list or on top of the button row.
     */
    private static Container optionsContainer(final Container content) {
        Container best = null;
        int bestCount = 0;
        final Deque<Component> pending = new ArrayDeque<>();
        final Set<Component> visited =
            Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(content);
        while (!pending.isEmpty() && visited.size() < MAX_TREE_NODES) {
            final Component component = pending.poll();
            if (component == null
                || !visited.add(component)
                || !(component instanceof Container container)) {
                continue;
            }
            int direct = 0;
            for (Component child : container.getComponents()) {
                if (child instanceof JCheckBox && child.getClass() != JCheckBox.class) {
                    direct++;
                }
                if (child instanceof Container) {
                    pending.add(child);
                }
            }
            if (direct > bestCount) {
                best = container;
                bestCount = direct;
            }
        }
        return best;
    }

    private static void rollback(final Container target, final JPanel panel, final Throwable failure) {
        try {
            final Container parent = panel.getParent();
            if (parent != null) {
                parent.remove(panel);
            }
        } catch (Throwable rollback) {
            failure.addSuppressed(rollback);
        }
        try {
            target.revalidate();
        } catch (Throwable rollback) {
            failure.addSuppressed(rollback);
        }
        try {
            target.repaint();
        } catch (Throwable rollback) {
            failure.addSuppressed(rollback);
        }
    }

    private static void removeParts(
        final Container target,
        final JPanel panel,
        final DialogGrowth growth
    ) {
        try {
            onEdt(() -> {
                if (growth != null) {
                    growth.undo();
                }
                if (panel.getParent() == target) {
                    target.remove(panel);
                }
                target.revalidate();
                target.repaint();
                return null;
            }, false, true);
        } catch (Throwable ignored) {
            // The caller already has a typed attach/close failure. Do not mask it with cleanup.
        }
    }

    private void closeInternal() {
        final Container target;
        final JPanel panel;
        final DialogGrowth dialogGrowth;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            target = container;
            panel = ownedPanel;
            dialogGrowth = growth;
            container = null;
            ownedPanel = null;
            checkboxes = null;
            growth = null;
        }
        if (target == null || panel == null) {
            return;
        }
        try {
            onEdt(() -> {
                if (dialogGrowth != null) {
                    dialogGrowth.undo();
                }
                if (panel.getParent() == target) {
                    target.remove(panel);
                }
                target.revalidate();
                target.repaint();
                return null;
            }, false, true);
        } catch (ExportSettingsAttachException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new ExportSettingsAttachException(BOUNDARY_FAILURE_KEY, failure);
        }
    }

    private static Map<String, Boolean> readSelection(final Map<String, JCheckBox> boxes) {
        final Map<String, Boolean> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, JCheckBox> entry : boxes.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().isSelected());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private static List<ExportSettingsOptionSnapshot> validatedOptionSnapshot(
        final List<ExportSettingsOptionSnapshot> options
    ) {
        if (options == null) {
            throw new ExportSettingsAttachException(NULL_CONTRIBUTIONS_KEY);
        }
        final Set<String> seen = new HashSet<>();
        for (ExportSettingsOptionSnapshot option : options) {
            if (option == null) {
                throw new ExportSettingsAttachException(NULL_CONTRIBUTION_KEY);
            }
            if (!seen.add(option.optionId())) {
                throw new ExportSettingsAttachException(
                    DUPLICATE_OPTION_KEY + ": " + option.optionId()
                );
            }
        }
        return List.copyOf(options);
    }

    private static List<ExportSettingsContribution> validatedSnapshot(
        final List<ExportSettingsContribution> contributions
    ) {
        if (contributions == null) {
            throw new ExportSettingsAttachException(NULL_CONTRIBUTIONS_KEY);
        }
        final Set<String> seen = new HashSet<>();
        for (ExportSettingsContribution contribution : contributions) {
            if (contribution == null) {
                throw new ExportSettingsAttachException(NULL_CONTRIBUTION_KEY);
            }
            if (!seen.add(contribution.optionId())) {
                throw new ExportSettingsAttachException(
                    DUPLICATE_OPTION_KEY + ": " + contribution.optionId()
                );
            }
        }
        return List.copyOf(contributions);
    }

    @SuppressWarnings("unchecked")
    private static List<ExportSettingsOptionSnapshot> castResolved(final List<?> values) {
        return (List<ExportSettingsOptionSnapshot>) values;
    }

    @SuppressWarnings("unchecked")
    private static List<ExportSettingsContribution> castContributions(final List<?> values) {
        return (List<ExportSettingsContribution>) values;
    }

    private static <T> T onEdt(final Operation<T> operation, final boolean rejectInterrupted) {
        return onEdt(operation, rejectInterrupted, false);
    }

    private static <T> T onEdt(
        final Operation<T> operation,
        final boolean rejectInterrupted,
        final boolean keepQueuedAfterCallerStops
    ) {
        Objects.requireNonNull(operation, "operation");
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.run();
        }
        if (rejectInterrupted && Thread.currentThread().isInterrupted()) {
            throw new ExportSettingsAttachException(INTERRUPTED_KEY);
        }

        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean execute = new AtomicBoolean(true);
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> {
            if (!execute.get()) {
                completed.countDown();
                return;
            }
            try {
                result.set(operation.run());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(EDT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                if (!keepQueuedAfterCallerStops) {
                    execute.set(false);
                }
                throw new ExportSettingsAttachException(EDT_TIMEOUT_KEY);
            }
        } catch (InterruptedException interrupted) {
            if (!keepQueuedAfterCallerStops) {
                execute.set(false);
            }
            Thread.currentThread().interrupt();
            throw new ExportSettingsAttachException(INTERRUPTED_KEY, interrupted);
        }
        final Throwable operationFailure = failure.get();
        if (operationFailure != null) {
            if (operationFailure instanceof ExportSettingsAttachException typed) {
                throw typed;
            }
            throw new ExportSettingsAttachException(BOUNDARY_FAILURE_KEY, operationFailure);
        }
        return result.get();
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }

    /**
     * Grows the host dialog's top-level window so the contributed rows fit
     * without clipping native options, and restores the captured size on
     * teardown.
     *
     * <p>The native dialog packs and restores a remembered size during
     * {@code setVisible} — after the attach hook runs — so a resize at attach
     * time would be overwritten. Growth is therefore armed on the owned panel's
     * first {@code SHOWING_CHANGED} event and re-ensured over a bounded number
     * of deferred EDT passes. It only ever grows the height toward the window's
     * preferred size: never shrinks, never touches the width. All methods run
     * on the EDT.</p>
     */
    private static final class DialogGrowth {

        /** Client-property keys the host probe reads as grow/restore evidence. */
        static final String BASELINE_PROPERTY =
            "turboism.export-settings.dialogBaselineSize";
        static final String GROWN_PROPERTY =
            "turboism.export-settings.dialogGrownSize";
        private static final int MAX_PASSES = 3;

        private final Function<Container, Component> topLevelResolver;
        private final Container mount;
        private final JPanel panel;
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private Component topLevel;
        private Dimension baseline;
        private int passes;

        DialogGrowth(
            final Function<Container, Component> topLevelResolver,
            final Container mount,
            final JPanel panel
        ) {
            this.topLevelResolver = topLevelResolver;
            this.mount = mount;
            this.panel = panel;
        }

        void arm() {
            panel.addHierarchyListener(event -> {
                if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                    SwingUtilities.invokeLater(this::ensureGrowth);
                }
            });
            // A host that attaches into an already-showing window never fires
            // SHOWING_CHANGED again, so queue one pass unconditionally.
            SwingUtilities.invokeLater(this::ensureGrowth);
        }

        private void ensureGrowth() {
            if (!armed.get() || panel.getParent() != mount) {
                return;
            }
            final Component top = topLevelResolver.apply(mount);
            if (top == null || !top.isShowing()) {
                // Not shown yet — the hierarchy event re-triggers the pass.
                return;
            }
            topLevel = top;
            if (baseline == null) {
                baseline = top.getSize();
                panel.putClientProperty(BASELINE_PROPERTY, baseline);
            }
            top.validate();
            final Dimension needed = top.getPreferredSize();
            final Dimension current = top.getSize();
            if (needed != null && needed.height > current.height) {
                top.setSize(new Dimension(current.width, needed.height));
                top.validate();
            }
            panel.putClientProperty(GROWN_PROPERTY, top.getSize());
            if (++passes < MAX_PASSES) {
                SwingUtilities.invokeLater(this::ensureGrowth);
            }
        }

        void undo() {
            armed.set(false);
            final Component top = topLevel;
            final Dimension size = baseline;
            if (top != null && size != null) {
                try {
                    top.setSize(size);
                } catch (Throwable ignored) {
                    // The window is going away; restore is best-effort.
                }
            }
        }
    }

    private record AttachmentParts(
        JPanel panel,
        Map<String, JCheckBox> checkboxes,
        Container mount,
        DialogGrowth growth
    ) {
        private AttachmentParts {
            Objects.requireNonNull(panel, "panel");
            Objects.requireNonNull(checkboxes, "checkboxes");
            Objects.requireNonNull(mount, "mount");
            Objects.requireNonNull(growth, "growth");
        }
    }
}
