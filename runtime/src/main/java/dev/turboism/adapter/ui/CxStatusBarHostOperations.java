package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Exact-version host operations for the platform-owned CX bottom status region.
 *
 * <p>Implements the existing {@link StatusToolbarAdapter.HostOperations}
 * contract over the narrow {@link CxStatusBarHostAccess} seam. Package-private
 * keeps raw CX operations internal; production composes it only with the
 * reviewed exact-version (5.2.03, 5.3.02 or 5.3.03) resolver-backed access and
 * otherwise remains in safe mode.</p>
 */
final class CxStatusBarHostOperations implements StatusToolbarAdapter.HostOperations {

    /** Bounds the CX tree walk; identity-deduped nodes beyond this fail closed. */
    private static final int MAX_TRAVERSAL_BUDGET = 4096;
    private static final String LATEST_NOTIFICATION_SLOT = "notification:latest";

    private final String hostVersion;
    private final CxStatusBarHostAccess access;
    private final Map<String, Entry> entries = new HashMap<>();

    CxStatusBarHostOperations(
        final String hostVersion,
        final CxStatusBarHostAccess access
    ) {
        this.hostVersion = requireText(hostVersion, "hostVersion");
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override
    public String hostVersion() {
        return hostVersion;
    }

    @Override
    public boolean supports(final StatusToolbarAdapter.Capability capability) {
        return capability == StatusToolbarAdapter.Capability.STATUS_NOTIFY;
    }

    @Override
    public Registration notifyStatus(final StatusNotification notification) {
        Objects.requireNonNull(notification, "notification");
        return onEdt(() -> install(notification));
    }

    private Registration install(final StatusNotification notification) {
        final String slot = slot(notification);
        final Entry current = entries.get(slot);
        final Entry sameIdentity = entries.values().stream()
            .filter(entry -> entry.notificationId().equals(notification.id()))
            .findFirst()
            .orElse(null);
        if (current == null) {
            if (sameIdentity != null) {
                access.remove(sameIdentity.parent(), sameIdentity.widget());
                entries.remove(sameIdentity.slot(), sameIdentity);
            }
            return installNew(slot, notification);
        }
        if (sameIdentity != null && sameIdentity != current) {
            access.remove(sameIdentity.parent(), sameIdentity.widget());
            entries.remove(sameIdentity.slot(), sameIdentity);
        }
        return update(slot, current, notification);
    }

    private Registration update(
        final String slot,
        final Entry current,
        final StatusNotification notification
    ) {
        // Every install/update creates a fresh Entry instance; registrations
        // capture that instance so a stale close can never match a later one.
        final Entry entry = new Entry(
            slot,
            notification.id(),
            current.parent(),
            current.widget()
        );
        access.setName(current.widget(), notification.id());
        access.setText(current.widget(), notification.message());
        applySeverityAppearance(notification, current.widget());
        access.refresh(current.parent());
        entries.put(slot, entry);
        return closeRegistration(entry);
    }

    private Registration installNew(
        final String slot,
        final StatusNotification notification
    ) {
        final Object root = access.contentRoot();
        if (root == null) {
            throw new IllegalStateException("CX status-region content root is not ready");
        }
        final Anchor anchor = resolveAnchor(root);
        final List<?> children = childrenOf(anchor.parent());
        final Object widget = access.createLabel(notification.id(), notification.message());
        if (widget == null) {
            throw new IllegalStateException("CX status-region CLabel creation failed");
        }
        access.setName(widget, notification.id());
        access.setText(widget, notification.message());
        applySeverityAppearance(notification, widget);
        final int index = insertionIndex(children, anchor, notification.presentation());
        access.add(anchor.parent(), widget, index);
        try {
            access.refresh(anchor.parent());
        } catch (RuntimeException | Error failure) {
            // Minimal compensation for the first refresh after a successful add:
            // a failed refresh must not leave an orphan widget with no entry
            // and no registration. Compensation failures are suppressed onto
            // the original failure, which is rethrown.
            try {
                access.remove(anchor.parent(), widget);
                access.refresh(anchor.parent());
            } catch (RuntimeException | Error compensation) {
                failure.addSuppressed(compensation);
            }
            throw failure;
        }
        final Entry entry = new Entry(slot, notification.id(), anchor.parent(), widget);
        entries.put(slot, entry);
        return closeRegistration(entry);
    }

    private static String slot(final StatusNotification notification) {
        return notification.presentation() == StatusNotification.Presentation.COMPACT_METRIC
            ? notification.id()
            : LATEST_NOTIFICATION_SLOT;
    }

    /**
     * Close is retryable: the closed flag is only committed inside the EDT
     * operation after native remove, identity-conditioned map cleanup and
     * refresh all succeed, matching the IdempotentRegistration /
     * TrackedRegistration contract that a failed delegate close stays OPEN.
     */
    private Registration closeRegistration(final Entry entry) {
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean removed = new AtomicBoolean();
        return () -> onEdt(() -> {
            if (closed.get()) {
                return null;
            }
            if (!removed.get()) {
                if (entries.get(entry.slot()) != entry) {
                    closed.set(true);
                    return null;
                }
                // Native remove first: a failure must leave the entry in the map
                // so a later notify of the same ID reuses the existing widget
                // instead of silently leaking it and adding a duplicate.
                access.remove(entry.parent(), entry.widget());
                entries.remove(entry.slot(), entry);
                removed.set(true);
            }
            // A failed refresh is retried without repeating native removal.
            access.refresh(entry.parent());
            closed.set(true);
            return null;
        });
    }

    /**
     * Iterative depth-first search with identity deduplication and a traversal
     * budget for the parent container that directly contains a
     * CMemoryViewerPanel. Zero or more than one candidate fails closed.
     */
    private Anchor resolveAnchor(final Object root) {
        final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Anchor> candidates = new ArrayList<>();
        final ArrayDeque<Object> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            final Object node = stack.pop();
            if (!visited.add(node)) {
                continue;
            }
            if (visited.size() > MAX_TRAVERSAL_BUDGET) {
                throw new IllegalStateException(
                    "CX status-region tree traversal exceeded the budget of "
                        + MAX_TRAVERSAL_BUDGET + " nodes"
                );
            }
            final List<?> children = childrenOf(node);
            if (!children.isEmpty()) {
                Object memoryViewer = null;
                for (Object child : children) {
                    if (access.isCMemoryViewerPanel(child)) {
                        if (memoryViewer != null) {
                            throw new IllegalStateException(
                                "CX status-region anchor parent contains multiple CMemoryViewerPanel children"
                            );
                        }
                        memoryViewer = child;
                    }
                }
                if (memoryViewer != null) {
                    candidates.add(new Anchor(node, memoryViewer));
                }
            }
            for (int index = children.size() - 1; index >= 0; index--) {
                stack.push(children.get(index));
            }
        }
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                "CX status-region anchor is missing or ambiguous: " + candidates.size()
                    + " candidate parents"
            );
        }
        return candidates.get(0);
    }

    /**
     * Insertion position depends on the presentation: ordinary notifications
     * keep the legacy-verified position (before the last native CLabel, or
     * before the memory viewer when the status bar has no CLabel; owned labels
     * are excluded so new widgets always precede the native cursor-coordinate
     * display). Compact metrics always mount immediately left of the
     * runtime-found {@code CMemoryViewerPanel} instance (its child index),
     * never a fixed index. An inconsistent tree fails closed.
     */
    private int insertionIndex(
        final List<?> children,
        final Anchor anchor,
        final StatusNotification.Presentation presentation
    ) {
        final int memoryViewerIndex = children.indexOf(anchor.memoryViewer());
        if (memoryViewerIndex < 0) {
            throw new IllegalStateException(
                "CX status-region tree is inconsistent: memory viewer is not a child of its parent"
            );
        }
        if (presentation == StatusNotification.Presentation.COMPACT_METRIC) {
            return memoryViewerIndex;
        }
        final Set<Object> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entry entry : entries.values()) {
            owned.add(entry.widget());
        }
        int lastCLabel = -1;
        for (int index = 0; index < children.size(); index++) {
            final Object child = children.get(index);
            if (access.isCLabel(child) && !owned.contains(child)) {
                lastCLabel = index;
            }
        }
        if (lastCLabel >= 0) {
            return lastCLabel;
        }
        return memoryViewerIndex;
    }

    private List<?> childrenOf(final Object container) {
        final List<?> children = access.children(container);
        if (children == null) {
            return List.of();
        }
        return List.copyOf(children);
    }

    /**
     * Severity prefix and tooltip apply to ordinary notifications only.
     * Compact metrics show the raw message without severity appearance.
     */
    private void applySeverityAppearance(
        final StatusNotification notification,
        final Object widget
    ) {
        if (notification.presentation() != StatusNotification.Presentation.COMPACT_METRIC) {
            access.setSeverityAppearance(widget, notification.severity());
        }
    }

    private static <T> T onEdt(final Supplier<T> operation) {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.get();
        }
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = operation.get();
                } catch (Throwable throwable) {
                    failure[0] = throwable;
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CX status-region EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("CX status-region EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException("CX status-region EDT operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    private static String requireText(final String value, final String name) {
        final String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private record Anchor(Object parent, Object memoryViewer) {
        private Anchor {
            Objects.requireNonNull(parent, "parent");
            Objects.requireNonNull(memoryViewer, "memoryViewer");
        }
    }

    private record Entry(String slot, String notificationId, Object parent, Object widget) {
    }
}
