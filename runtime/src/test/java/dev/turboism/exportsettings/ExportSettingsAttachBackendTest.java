package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.cubism.export.ExportSettingsDecision;
import dev.turboism.sdk.cubism.export.ExportSettingsDecisionCallback;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.awt.BorderLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExportSettingsAttachBackendTest {

    @Test
    void attachMaterializesOneOwnedPanelInOrderWithDefaultOffCheckboxes() {
        final JPanel host = new JPanel(new BorderLayout());
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        backend.attach(host, List.of(
            contribution("option-a", "label.a"),
            contribution("option-b", "label.b"),
            contribution("option-c", "label.c")
        ));

        // One owned panel at BorderLayout.SOUTH, nothing else.
        assertEquals(1, host.getComponentCount());
        final Component child = host.getComponent(0);
        assertTrue(child instanceof JPanel, "owned component must be a JPanel");
        final JPanel panel = (JPanel) child;
        assertSame(
            panel,
            ((BorderLayout) host.getLayout()).getLayoutComponent(host, BorderLayout.SOUTH)
        );

        // One checkbox per descriptor, deterministic order, labels from descriptors,
        // default-off per the SDK contract.
        assertEquals(3, panel.getComponentCount());
        for (int index = 0; index < 3; index++) {
            final Component component = panel.getComponent(index);
            assertTrue(component instanceof JCheckBox, "owned row must be a JCheckBox");
            final JCheckBox box = (JCheckBox) component;
            assertEquals(List.of("label.a", "label.b", "label.c").get(index), box.getText());
            assertFalse(box.isSelected(), "checkbox must default to unselected");
        }
    }

    @Test
    void selectionSnapshotIsImmutableLiveAndKeyedByOptionId() {
        final JPanel host = new JPanel(new BorderLayout());
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        backend.attach(host, List.of(
            contribution("option-a", "label.a"),
            contribution("option-b", "label.b")
        ));

        final Map<String, Boolean> before = backend.selectedSnapshot();
        assertEquals(Map.of("option-a", false, "option-b", false), before);
        assertThrows(UnsupportedOperationException.class, () -> before.put("option-a", true));

        // Toggle one owned checkbox through the materialized component tree.
        checkbox(host, "label.a").setSelected(true);

        final Map<String, Boolean> after = backend.selectedSnapshot();
        assertEquals(Map.of("option-a", true, "option-b", false), after);
        assertThrows(UnsupportedOperationException.class, () -> after.put("option-b", true));
        assertNotSame(before, after);
        assertEquals(List.of("option-a", "option-b"), List.copyOf(after.keySet()));
    }

    @Test
    void closeRemovesOnlyOwnedComponentsOnEdtRevalidatesRepaintsAndIsIdempotent() {
        final RecordingContainer host = new RecordingContainer();
        final JLabel unrelated = new JLabel("unrelated");
        host.add(unrelated, BorderLayout.NORTH);
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        final Registration registration = backend.attach(host, List.of(
            contribution("option-a", "label.a"),
            contribution("option-b", "label.b")
        ));
        assertEquals(2, host.getComponentCount());

        host.resetCounts();
        registration.close();
        registration.close();
        registration.close();

        // Only the owned panel was removed; the unrelated child survives.
        assertEquals(1, host.getComponentCount());
        assertSame(unrelated, host.getComponent(0));
        assertTrue(host.removals > 0, "owned panel must be removed on the EDT");
        assertTrue(host.revalidations > 0, "container must be revalidated after removal");
        assertTrue(host.repaints > 0, "container must be repainted after removal");
        assertThrows(ExportSettingsAttachException.class, backend::selectedSnapshot);
    }

    @Test
    void duplicateOptionIdsFailBeforeAnyMutationAndDoNotPoisonTheSession() {
        final JPanel host = new JPanel(new BorderLayout());
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            () -> backend.attach(host, List.of(
                contribution("option-a", "label.a"),
                contribution("option-a", "label.duplicate")
            ))
        );
        assertEquals(
            ExportSettingsAttachBackend.DUPLICATE_OPTION_KEY + ": option-a",
            failure.getMessage()
        );
        assertEquals(0, host.getComponentCount(), "no UI mutation may happen");

        // A failed validation does not consume the attach session.
        backend.attach(host, List.of(contribution("option-a", "label.a")));
        assertEquals(1, host.getComponentCount());
    }

    @Test
    void duplicateAttachSessionFailsBeforeAnyMutation() {
        final JPanel host = new JPanel(new BorderLayout());
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        backend.attach(host, List.of(contribution("option-a", "label.a")));

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            () -> backend.attach(host, List.of(contribution("option-b", "label.b")))
        );
        assertEquals(ExportSettingsAttachBackend.ALREADY_ATTACHED_KEY, failure.getMessage());
        assertEquals(1, host.getComponentCount(), "second attach must not mutate the host");
        assertEquals(1, panel(host).getComponentCount(), "second attach must not add checkboxes");
    }

    @Test
    void nullArgumentsRejectBeforeAnyMutation() {
        final JPanel host = new JPanel(new BorderLayout());
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();

        assertEquals(
            ExportSettingsAttachBackend.NULL_CONTAINER_KEY,
            assertThrows(
                ExportSettingsAttachException.class,
                () -> backend.attach(null, List.of(contribution("option-a", "label.a")))
            ).getMessage()
        );
        assertEquals(
            ExportSettingsAttachBackend.NULL_CONTRIBUTIONS_KEY,
            assertThrows(
                ExportSettingsAttachException.class,
                () -> backend.attach(host, null)
            ).getMessage()
        );
        assertEquals(
            ExportSettingsAttachBackend.NULL_CONTRIBUTION_KEY,
            assertThrows(
                ExportSettingsAttachException.class,
                () -> backend.attach(host, java.util.Arrays.asList(
                    contribution("option-a", "label.a"), null))
            ).getMessage()
        );
        assertEquals(0, host.getComponentCount(), "no UI mutation may happen");
    }

    @Test
    void offEdtAttachAndCloseRunOnTheEdt() {
        assertFalse(SwingUtilities.isEventDispatchThread(), "JUnit thread must be off the EDT");
        final RecordingContainer host = new RecordingContainer();
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();

        final Registration registration = backend.attach(host, List.of(
            contribution("option-a", "label.a")
        ));
        assertTrue(host.addedOnEdt, "component mutation must happen on the EDT");
        assertEquals(1, host.getComponentCount());

        registration.close();
        assertTrue(host.removedOnEdt, "component removal must happen on the EDT");
        assertEquals(0, host.getComponentCount());
    }

    @Test
    void onEdtAttachAndCloseRunDirectlyWithoutDeadlock() throws Exception {
        final AtomicBoolean completed = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            final RecordingContainer host = new RecordingContainer();
            final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
            final Registration registration = backend.attach(host, List.of(
                contribution("option-a", "label.a")
            ));
            assertEquals(1, host.getComponentCount());
            assertTrue(host.addedOnEdt);
            registration.close();
            assertEquals(0, host.getComponentCount());
            assertTrue(host.removedOnEdt);
            completed.set(true);
        });
        assertTrue(completed.get());
    }

    @Test
    void boundaryThrowableIsContainedAsOneStableTypeAndLeavesHostUnchanged() {
        final JPanel throwing = new JPanel(new BorderLayout()) {
            @Override
            public void add(final Component component, final Object constraints) {
                throw new IllegalStateException("boom");
            }
        };
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            () -> backend.attach(throwing, List.of(contribution("option-a", "label.a")))
        );
        assertEquals(ExportSettingsAttachBackend.BOUNDARY_FAILURE_KEY, failure.getMessage());
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals(0, throwing.getComponentCount(), "host must stay unchanged");

        // A contained boundary failure does not consume the session.
        final JPanel healthy = new JPanel(new BorderLayout());
        backend.attach(healthy, List.of(contribution("option-a", "label.a")));
        assertEquals(1, healthy.getComponentCount());
    }

    @Test
    void interruptionBeforeEdtDispatchFailsClosedAndRestoresInterruptedStatus() {
        final Thread thread = Thread.currentThread();
        final JPanel host = new JPanel(new BorderLayout());
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        thread.interrupt();
        try {
            final ExportSettingsAttachException failure = assertThrows(
                ExportSettingsAttachException.class,
                () -> backend.attach(host, List.of(contribution("option-a", "label.a")))
            );
            assertEquals(ExportSettingsAttachBackend.INTERRUPTED_KEY, failure.getMessage());
            assertTrue(thread.isInterrupted(), "interrupted status must be restored");
            assertEquals(0, host.getComponentCount(), "no UI mutation may happen");
        } finally {
            thread.interrupted();
        }
    }

    @Test
    void closedBackendRejectsFurtherAttachAndSnapshot() {
        final JPanel host = new JPanel(new BorderLayout());
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        final Registration registration = backend.attach(host, List.of(
            contribution("option-a", "label.a")
        ));
        registration.close();

        assertEquals(
            ExportSettingsAttachBackend.CLOSED_KEY,
            assertThrows(
                ExportSettingsAttachException.class,
                () -> backend.attach(host, List.of(contribution("option-b", "label.b")))
            ).getMessage()
        );
        assertEquals(
            ExportSettingsAttachBackend.NOT_ATTACHED_KEY,
            assertThrows(ExportSettingsAttachException.class, backend::selectedSnapshot).getMessage()
        );
    }

    @Test
    void revalidateThrowableAfterPanelAddedRollsBackToExactHostChildSet() {
        final ThrowingContainer host = new ThrowingContainer();
        final JLabel unrelated = new JLabel("unrelated");
        host.add(unrelated, BorderLayout.NORTH);
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        host.throwOnRevalidate = true;

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            () -> backend.attach(host, List.of(contribution("option-a", "label.a")))
        );
        assertEquals(ExportSettingsAttachBackend.BOUNDARY_FAILURE_KEY, failure.getMessage());
        final Throwable cause = failure.getCause();
        assertTrue(cause instanceof IllegalStateException);
        assertEquals("revalidate-boom", cause.getMessage());
        // Rollback refresh failure is suppressed onto the original, never replacing it.
        assertEquals(1, cause.getSuppressed().length);
        assertTrue(cause.getSuppressed()[0] instanceof IllegalStateException);
        // Exact host child set restored: only the unrelated child remains.
        assertEquals(1, host.getComponentCount());
        assertSame(unrelated, host.getComponent(0));
        // Ownership state was not published: a subsequent attach works.
        final JPanel healthy = new JPanel(new BorderLayout());
        backend.attach(healthy, List.of(contribution("option-a", "label.a")));
        assertEquals(1, healthy.getComponentCount());
    }

    @Test
    void repaintThrowableAfterPanelAddedRollsBackToExactHostChildSet() {
        final ThrowingContainer host = new ThrowingContainer();
        final JLabel unrelated = new JLabel("unrelated");
        host.add(unrelated, BorderLayout.NORTH);
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        host.throwOnRepaint = true;

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            () -> backend.attach(host, List.of(contribution("option-a", "label.a")))
        );
        assertEquals(ExportSettingsAttachBackend.BOUNDARY_FAILURE_KEY, failure.getMessage());
        final Throwable cause = failure.getCause();
        assertTrue(cause instanceof IllegalStateException);
        assertEquals("repaint-boom", cause.getMessage());
        assertEquals(1, cause.getSuppressed().length);
        assertTrue(cause.getSuppressed()[0] instanceof IllegalStateException);
        assertEquals(1, host.getComponentCount());
        assertSame(unrelated, host.getComponent(0));
        final JPanel healthy = new JPanel(new BorderLayout());
        backend.attach(healthy, List.of(contribution("option-a", "label.a")));
        assertEquals(1, healthy.getComponentCount());
    }

    @Test
    void addThrowableAfterSuperAddRollsBackToExactHostChildSet() {
        final ThrowingContainer host = new ThrowingContainer();
        final JLabel unrelated = new JLabel("unrelated");
        host.add(unrelated, BorderLayout.NORTH);
        host.throwOnAddAfterSuper = true;
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            () -> backend.attach(host, List.of(contribution("option-a", "label.a")))
        );
        assertEquals(ExportSettingsAttachBackend.BOUNDARY_FAILURE_KEY, failure.getMessage());
        final Throwable cause = failure.getCause();
        assertTrue(cause instanceof IllegalStateException);
        assertEquals("add-boom", cause.getMessage());
        // The exact preexisting host children are restored (the panel entered the host
        // via super.add and was rolled back); no suppressed rollback failure expected.
        assertEquals(0, cause.getSuppressed().length);
        assertEquals(1, host.getComponentCount());
        assertSame(unrelated, host.getComponent(0));
        // Session was never published: a subsequent attach works.
        final JPanel healthy = new JPanel(new BorderLayout());
        backend.attach(healthy, List.of(contribution("option-a", "label.a")));
        assertEquals(1, healthy.getComponentCount());
    }

    @Test
    void closeTimeRemoveThrowableIsContainedWithRemovalAttemptedOnce() {
        final ThrowingContainer host = new ThrowingContainer();
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        final Registration registration = backend.attach(host, List.of(
            contribution("option-a", "label.a")
        ));
        assertEquals(1, host.getComponentCount());
        host.throwOnRemove = true;

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            registration::close
        );
        assertEquals(ExportSettingsAttachBackend.BOUNDARY_FAILURE_KEY, failure.getMessage());
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals("remove-boom", failure.getCause().getMessage());
        // Truthful cleanup state: removal attempted exactly once before the failure,
        // then closed — repeated close performs no second mutation.
        assertEquals(1, host.removeAttempts);
        registration.close();
        assertEquals(1, host.removeAttempts);
        assertThrows(ExportSettingsAttachException.class, backend::selectedSnapshot);
        assertEquals(1, host.getComponentCount(), "host refused removal; panel remains, state is closed");
    }

    @Test
    void closeTimeRevalidateThrowableIsContainedAfterPanelRemoval() {
        final ThrowingContainer host = new ThrowingContainer();
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        final Registration registration = backend.attach(host, List.of(
            contribution("option-a", "label.a")
        ));
        host.throwOnRevalidate = true;

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            registration::close
        );
        assertEquals(ExportSettingsAttachBackend.BOUNDARY_FAILURE_KEY, failure.getMessage());
        assertEquals("revalidate-boom", failure.getCause().getMessage());
        assertEquals(0, host.getComponentCount(), "owned panel must be removed before the failure");
        registration.close();
        assertEquals(1, host.removeAttempts, "repeated close must not re-mutate");
        assertThrows(ExportSettingsAttachException.class, backend::selectedSnapshot);
    }

    @Test
    void closeTimeRepaintThrowableIsContainedAfterPanelRemoval() {
        final ThrowingContainer host = new ThrowingContainer();
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        final Registration registration = backend.attach(host, List.of(
            contribution("option-a", "label.a")
        ));
        host.throwOnRepaint = true;

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            registration::close
        );
        assertEquals(ExportSettingsAttachBackend.BOUNDARY_FAILURE_KEY, failure.getMessage());
        assertEquals("repaint-boom", failure.getCause().getMessage());
        assertEquals(0, host.getComponentCount(), "owned panel must be removed before the failure");
        registration.close();
        assertEquals(1, host.removeAttempts, "repeated close must not re-mutate");
        assertThrows(ExportSettingsAttachException.class, backend::selectedSnapshot);
    }

    @Test
    void interruptedCloseStillQueuesRemovalAndRestoresInterruptedStatus() throws Exception {
        final Thread thread = Thread.currentThread();
        final RecordingContainer host = new RecordingContainer();
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        final Registration registration = backend.attach(host, List.of(
            contribution("option-a", "label.a")
        ));
        assertEquals(1, host.getComponentCount());
        thread.interrupt();
        try {
            final ExportSettingsAttachException failure = assertThrows(
                ExportSettingsAttachException.class,
                registration::close
            );
            assertEquals(ExportSettingsAttachBackend.INTERRUPTED_KEY, failure.getMessage());
            assertTrue(thread.isInterrupted(), "interrupted status must be restored");
        } finally {
            thread.interrupted();
        }
        // The removal event was queued before the interruption surfaced and must complete.
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, host.getComponentCount());
        assertTrue(host.removedOnEdt);
    }

    @Test
    void selectedSnapshotReadsSwingStateOnTheEdt() {
        assertFalse(SwingUtilities.isEventDispatchThread());
        final JPanel host = new JPanel(new BorderLayout());
        final AtomicReference<RecordingCheckBox> box = new AtomicReference<>();
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend(label -> {
            final RecordingCheckBox created = new RecordingCheckBox(label);
            box.set(created);
            return created;
        });
        backend.attach(host, List.of(contribution("option-a", "label.a")));
        box.get().setSelected(true);

        final Map<String, Boolean> snapshot = backend.selectedSnapshot();
        assertEquals(Map.of("option-a", true), snapshot);
        assertTrue(box.get().readOnEdt.get(), "checkbox state must be read on the EDT");
    }

    private static ExportSettingsContribution contribution(
        final String optionId,
        final String labelKey
    ) {
        final ExportSettingsDecisionCallback callback =
            (selected, documentId, modelId) -> ExportSettingsDecision.proceedUnchanged();
        return new ExportSettingsContribution(optionId, labelKey, callback);
    }

    private static JCheckBox checkbox(final Container host, final String label) {
        for (Component component : panel(host).getComponents()) {
            if (component instanceof JCheckBox box && label.equals(box.getText())) {
                return box;
            }
        }
        throw new AssertionError("owned checkbox not found: " + label);
    }

    private static JPanel panel(final Container host) {
        for (Component component : host.getComponents()) {
            if (component instanceof JPanel panel) {
                return panel;
            }
        }
        throw new AssertionError("owned panel not found");
    }

    /** Records whether component mutation happens on the EDT and counts refresh calls. */
    private static class RecordingContainer extends JPanel {

        private volatile boolean addedOnEdt;
        private volatile boolean removedOnEdt;
        private int revalidations;
        private int repaints;
        private int removals;

        private RecordingContainer() {
            super(new BorderLayout());
        }

        @Override
        public void add(final Component component, final Object constraints) {
            addedOnEdt = SwingUtilities.isEventDispatchThread();
            super.add(component, constraints);
        }

        @Override
        public void remove(final Component component) {
            removedOnEdt = SwingUtilities.isEventDispatchThread();
            super.remove(component);
            removals++;
        }

        @Override
        public void revalidate() {
            super.revalidate();
            revalidations++;
        }

        @Override
        public void repaint() {
            super.repaint();
            repaints++;
        }

        private void resetCounts() {
            revalidations = 0;
            repaints = 0;
            removals = 0;
        }
    }

    /** Container whose add/remove/revalidate/repaint can be switched to throw. */
    private static final class ThrowingContainer extends RecordingContainer {

        private boolean throwOnAddAfterSuper;
        private boolean throwOnRevalidate;
        private boolean throwOnRepaint;
        private boolean throwOnRemove;
        private int removeAttempts;
        @Override
        public void add(final Component component, final Object constraints) {
            super.add(component, constraints);
            if (throwOnAddAfterSuper) {
                throw new IllegalStateException("add-boom");
            }
        }
        @Override
        public void remove(final Component component) {
            removeAttempts++;
            if (throwOnRemove) {
                throw new IllegalStateException("remove-boom");
            }
            super.remove(component);
        }

        @Override
        public void revalidate() {
            if (throwOnRevalidate) {
                throw new IllegalStateException("revalidate-boom");
            }
            super.revalidate();
        }

        @Override
        public void repaint() {
            if (throwOnRepaint) {
                throw new IllegalStateException("repaint-boom");
            }
            super.repaint();
        }
    }

    /** Checkbox recording whether its selected state was read on the EDT. */
    private static final class RecordingCheckBox extends JCheckBox {

        private final AtomicBoolean readOnEdt = new AtomicBoolean();

        private RecordingCheckBox(final String label) {
            super(label);
        }

        @Override
        public boolean isSelected() {
            readOnEdt.set(SwingUtilities.isEventDispatchThread());
            return super.isSelected();
        }
    }
}
