package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.plugin.Registration;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Container;
import java.util.Collections;
import java.util.HashSet;
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
 * <p>This class only renders a supplied option snapshot into a caller-supplied
 * container. It does not discover host widgets, resolve localization, invoke plugin
 * callbacks, mutate a model, or execute an export.</p>
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

    private static final long EDT_TIMEOUT_MILLIS = 5_000L;

    private final Object lifecycleLock = new Object();
    private final Function<String, JCheckBox> checkboxFactory;
    private boolean closed;
    private boolean attaching;
    private Container container;
    private JPanel ownedPanel;
    private Map<String, JCheckBox> checkboxes;

    public ExportSettingsAttachBackend() {
        this(JCheckBox::new);
    }

    /** Test-only checkbox factory seam; the default factory yields default-off checkboxes. */
    ExportSettingsAttachBackend(final Function<String, JCheckBox> checkboxFactory) {
        this.checkboxFactory = Objects.requireNonNull(checkboxFactory, "checkboxFactory");
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
                    container = requestedContainer;
                    ownedPanel = parts.panel();
                    checkboxes = Collections.unmodifiableMap(parts.checkboxes());
                }
            }
            if (becameClosed) {
                removeParts(requestedContainer, parts.panel());
                throw new ExportSettingsAttachException(CLOSED_KEY);
            }
            return this::closeInternal;
        } catch (ExportSettingsAttachException failure) {
            synchronized (lifecycleLock) {
                attaching = false;
            }
            if (parts != null) {
                removeParts(requestedContainer, parts.panel());
            }
            throw failure;
        } catch (Throwable failure) {
            synchronized (lifecycleLock) {
                attaching = false;
            }
            if (parts != null) {
                removeParts(requestedContainer, parts.panel());
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
        try {
            for (ExportSettingsContribution contribution : requested) {
                final JCheckBox box = Objects.requireNonNull(
                    checkboxFactory.apply(contribution.labelKey()),
                    "checkboxFactory result"
                );
                panel.add(box);
                boxes.put(contribution.optionId(), box);
            }
            commitOrRollback(target, panel);
            return new AttachmentParts(panel, boxes);
        } catch (Throwable failure) {
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
        try {
            for (ExportSettingsOptionSnapshot option : requested) {
                final JCheckBox box = Objects.requireNonNull(
                    checkboxFactory.apply(option.label()),
                    "checkboxFactory result"
                );
                panel.add(box);
                boxes.put(option.optionId(), box);
            }
            commitOrRollback(target, panel);
            return new AttachmentParts(panel, boxes);
        } catch (Throwable failure) {
            rollback(target, panel, failure);
            throw failure;
        }
    }

    private static void commitOrRollback(final Container target, final JPanel panel) {
        target.add(panel, BorderLayout.SOUTH);
        target.revalidate();
        target.repaint();
    }

    private static void rollback(final Container target, final JPanel panel, final Throwable failure) {
        try {
            if (panel.getParent() == target) {
                target.remove(panel);
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

    private static void removeParts(final Container target, final JPanel panel) {
        try {
            onEdt(() -> {
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
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            target = container;
            panel = ownedPanel;
            container = null;
            ownedPanel = null;
            checkboxes = null;
        }
        if (target == null || panel == null) {
            return;
        }
        try {
            onEdt(() -> {
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

    private record AttachmentParts(JPanel panel, Map<String, JCheckBox> checkboxes) {
        private AttachmentParts {
            Objects.requireNonNull(panel, "panel");
            Objects.requireNonNull(checkboxes, "checkboxes");
        }
    }
}
