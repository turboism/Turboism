package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.cubism.export.ExportSettingsDecision;
import dev.turboism.sdk.cubism.export.ExportSettingsDecisionCallback;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.HierarchyEvent;
import javax.swing.BoxLayout;
import javax.swing.JButton;
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
        final JPanel host = hostWithOptions();
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        backend.attach(host, List.of(
            contribution("option-a", "label.a"),
            contribution("option-b", "label.b"),
            contribution("option-c", "label.c")
        ));

        // One owned panel appended after the native options, nothing else added.
        assertEquals(3, host.getComponentCount());
        assertEquals("native-a", ((JCheckBox) host.getComponent(0)).getText());
        assertEquals("native-b", ((JCheckBox) host.getComponent(1)).getText());
        final Component child = host.getComponent(2);
        assertTrue(child instanceof JPanel, "owned component must be a JPanel");
        final JPanel panel = (JPanel) child;

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
    void attachAppendsInsideTheNativeOptionsContainerNotTheDialogWrapper() {
        // Stand-in for the reviewed dialog composition: the content pane carries the
        // native options list (the component-order container holding the native
        // check-box leaves, including a nested single-option row) and a separate
        // button row in the south region.
        final JPanel content = new JPanel(new BorderLayout());
        final JPanel options = new JPanel();
        final JCheckBox nativeA = new NativeCheckBox("native-a");
        final JCheckBox nativeB = new NativeCheckBox("native-b");
        final JPanel nestedRow = new JPanel();
        nestedRow.add(new NativeCheckBox("native-row"));
        options.add(nativeA);
        options.add(nativeB);
        options.add(nestedRow);
        content.add(options, BorderLayout.CENTER);
        final JPanel buttons = new JPanel();
        buttons.add(new JButton("OK"));
        buttons.add(new JButton("Cancel"));
        content.add(buttons, BorderLayout.SOUTH);

        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        backend.attach(content, List.of(contribution("option-a", "label.a")));

        // The dialog wrapper is untouched: the contribution must never occupy the
        // content pane's south region (the button row's region).
        assertEquals(2, content.getComponentCount());
        assertSame(options, content.getComponent(0));
        assertSame(buttons, content.getComponent(1));
        assertEquals(2, buttons.getComponentCount(), "button row must stay untouched");

        // The densest native check-box container wins over a nested single-option
        // row; the owned panel lands after the last existing child.
        assertEquals(4, options.getComponentCount());
        assertSame(nativeA, options.getComponent(0));
        assertSame(nativeB, options.getComponent(1));
        assertSame(nestedRow, options.getComponent(2));
        final Component owned = options.getComponent(3);
        assertTrue(owned instanceof JPanel, "owned panel must be the last options child");
        assertEquals("label.a", ((JCheckBox) ((JPanel) owned).getComponent(0)).getText());
    }

    @Test
    void attachFailsClosedWhenTheDialogHasNoNativeOptionsContainer() {
        final JPanel content = new JPanel(new BorderLayout());
        content.add(new JLabel("not an options list"), BorderLayout.CENTER);
        final JPanel buttons = new JPanel();
        buttons.add(new JButton("OK"));
        content.add(buttons, BorderLayout.SOUTH);
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            () -> backend.attach(content, List.of(contribution("option-a", "label.a")))
        );
        assertEquals(ExportSettingsAttachBackend.OPTIONS_CONTAINER_KEY, failure.getMessage());
        assertEquals(2, content.getComponentCount(), "an unrecognized dialog must stay unchanged");

        // Plain JCheckBox leaves are the injected type, never a native option.
        final JPanel onlyInjected = new JPanel();
        onlyInjected.add(new JCheckBox("stale injected"));
        assertEquals(
            ExportSettingsAttachBackend.OPTIONS_CONTAINER_KEY,
            assertThrows(
                ExportSettingsAttachException.class,
                () -> backend.attach(onlyInjected, List.of(contribution("option-b", "label.b")))
            ).getMessage()
        );

        // A failed mount resolution does not consume the attach session.
        final JPanel healthy = hostWithOptions();
        backend.attach(healthy, List.of(contribution("option-a", "label.a")));
        assertEquals(3, healthy.getComponentCount());
    }

    @Test
    void selectionSnapshotIsImmutableLiveAndKeyedByOptionId() {
        final JPanel host = hostWithOptions();
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
        final JCheckBox nativeOption = new NativeCheckBox("native-a");
        final JLabel unrelated = new JLabel("unrelated");
        host.add(nativeOption);
        host.add(unrelated);
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        final Registration registration = backend.attach(host, List.of(
            contribution("option-a", "label.a"),
            contribution("option-b", "label.b")
        ));
        assertEquals(3, host.getComponentCount());

        host.resetCounts();
        registration.close();
        registration.close();
        registration.close();

        // Only the owned panel was removed; the native option and the unrelated
        // child survive untouched.
        assertEquals(2, host.getComponentCount());
        assertSame(nativeOption, host.getComponent(0));
        assertSame(unrelated, host.getComponent(1));
        assertTrue(host.removals > 0, "owned panel must be removed on the EDT");
        assertTrue(host.revalidations > 0, "container must be revalidated after removal");
        assertTrue(host.repaints > 0, "container must be repainted after removal");
        assertThrows(ExportSettingsAttachException.class, backend::selectedSnapshot);
    }

    @Test
    void duplicateOptionIdsFailBeforeAnyMutationAndDoNotPoisonTheSession() {
        final JPanel host = hostWithOptions();
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
        assertEquals(2, host.getComponentCount(), "no UI mutation may happen");

        // A failed validation does not consume the attach session.
        backend.attach(host, List.of(contribution("option-a", "label.a")));
        assertEquals(3, host.getComponentCount());
    }

    @Test
    void duplicateAttachSessionFailsBeforeAnyMutation() {
        final JPanel host = hostWithOptions();
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        backend.attach(host, List.of(contribution("option-a", "label.a")));

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            () -> backend.attach(host, List.of(contribution("option-b", "label.b")))
        );
        assertEquals(ExportSettingsAttachBackend.ALREADY_ATTACHED_KEY, failure.getMessage());
        assertEquals(3, host.getComponentCount(), "second attach must not mutate the host");
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
        host.add(new NativeCheckBox("native-a"));
        host.resetFlags();
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();

        final Registration registration = backend.attach(host, List.of(
            contribution("option-a", "label.a")
        ));
        assertTrue(host.addedOnEdt, "component mutation must happen on the EDT");
        assertEquals(2, host.getComponentCount());

        registration.close();
        assertTrue(host.removedOnEdt, "component removal must happen on the EDT");
        assertEquals(1, host.getComponentCount());
    }

    @Test
    void onEdtAttachAndCloseRunDirectlyWithoutDeadlock() throws Exception {
        final AtomicBoolean completed = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            final RecordingContainer host = new RecordingContainer();
            host.add(new NativeCheckBox("native-a"));
            final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
            final Registration registration = backend.attach(host, List.of(
                contribution("option-a", "label.a")
            ));
            assertEquals(2, host.getComponentCount());
            assertTrue(host.addedOnEdt);
            registration.close();
            assertEquals(1, host.getComponentCount());
            assertTrue(host.removedOnEdt);
            completed.set(true);
        });
        assertTrue(completed.get());
    }

    @Test
    void boundaryThrowableIsContainedAsOneStableTypeAndLeavesHostUnchanged() {
        final AtomicBoolean hostile = new AtomicBoolean();
        final JPanel throwing = new JPanel(new BorderLayout()) {
            @Override
            public Component add(final Component component) {
                if (hostile.get()) {
                    throw new IllegalStateException("boom");
                }
                return super.add(component);
            }
        };
        throwing.add(new NativeCheckBox("native-a"));
        hostile.set(true);
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();

        final ExportSettingsAttachException failure = assertThrows(
            ExportSettingsAttachException.class,
            () -> backend.attach(throwing, List.of(contribution("option-a", "label.a")))
        );
        assertEquals(ExportSettingsAttachBackend.BOUNDARY_FAILURE_KEY, failure.getMessage());
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals(1, throwing.getComponentCount(), "host must stay unchanged");

        // A contained boundary failure does not consume the session.
        final JPanel healthy = hostWithOptions();
        backend.attach(healthy, List.of(contribution("option-a", "label.a")));
        assertEquals(3, healthy.getComponentCount());
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
        final JPanel host = hostWithOptions();
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
        final JCheckBox nativeOption = new NativeCheckBox("native-a");
        final JLabel unrelated = new JLabel("unrelated");
        host.add(nativeOption);
        host.add(unrelated);
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
        // Exact host child set restored: only the preexisting children remain.
        assertEquals(2, host.getComponentCount());
        assertSame(nativeOption, host.getComponent(0));
        assertSame(unrelated, host.getComponent(1));
        // Ownership state was not published: a subsequent attach works.
        host.throwOnRevalidate = false;
        backend.attach(host, List.of(contribution("option-a", "label.a")));
        assertEquals(3, host.getComponentCount());
    }

    @Test
    void repaintThrowableAfterPanelAddedRollsBackToExactHostChildSet() {
        final ThrowingContainer host = new ThrowingContainer();
        final JCheckBox nativeOption = new NativeCheckBox("native-a");
        final JLabel unrelated = new JLabel("unrelated");
        host.add(nativeOption);
        host.add(unrelated);
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
        assertEquals(2, host.getComponentCount());
        assertSame(nativeOption, host.getComponent(0));
        assertSame(unrelated, host.getComponent(1));
        host.throwOnRepaint = false;
        backend.attach(host, List.of(contribution("option-a", "label.a")));
        assertEquals(3, host.getComponentCount());
    }

    @Test
    void addThrowableAfterSuperAddRollsBackToExactHostChildSet() {
        final ThrowingContainer host = new ThrowingContainer();
        final JCheckBox nativeOption = new NativeCheckBox("native-a");
        final JLabel unrelated = new JLabel("unrelated");
        host.add(nativeOption);
        host.add(unrelated);
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
        assertEquals(2, host.getComponentCount());
        assertSame(nativeOption, host.getComponent(0));
        assertSame(unrelated, host.getComponent(1));
        // Session was never published: a subsequent attach works.
        host.throwOnAddAfterSuper = false;
        backend.attach(host, List.of(contribution("option-a", "label.a")));
        assertEquals(3, host.getComponentCount());
    }

    @Test
    void closeTimeRemoveThrowableIsContainedWithRemovalAttemptedOnce() {
        final ThrowingContainer host = new ThrowingContainer();
        host.add(new NativeCheckBox("native-a"));
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        final Registration registration = backend.attach(host, List.of(
            contribution("option-a", "label.a")
        ));
        assertEquals(2, host.getComponentCount());
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
        assertEquals(2, host.getComponentCount(), "host refused removal; panel remains, state is closed");
    }

    @Test
    void closeTimeRevalidateThrowableIsContainedAfterPanelRemoval() {
        final ThrowingContainer host = new ThrowingContainer();
        host.add(new NativeCheckBox("native-a"));
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
        assertEquals(1, host.getComponentCount(), "owned panel must be removed before the failure");
        registration.close();
        assertEquals(1, host.removeAttempts, "repeated close must not re-mutate");
        assertThrows(ExportSettingsAttachException.class, backend::selectedSnapshot);
    }

    @Test
    void closeTimeRepaintThrowableIsContainedAfterPanelRemoval() {
        final ThrowingContainer host = new ThrowingContainer();
        host.add(new NativeCheckBox("native-a"));
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
        assertEquals(1, host.getComponentCount(), "owned panel must be removed before the failure");
        registration.close();
        assertEquals(1, host.removeAttempts, "repeated close must not re-mutate");
        assertThrows(ExportSettingsAttachException.class, backend::selectedSnapshot);
    }

    @Test
    void interruptedCloseStillQueuesRemovalAndRestoresInterruptedStatus() throws Exception {
        final Thread thread = Thread.currentThread();
        final RecordingContainer host = new RecordingContainer();
        host.add(new NativeCheckBox("native-a"));
        final ExportSettingsAttachBackend backend = new ExportSettingsAttachBackend();
        final Registration registration = backend.attach(host, List.of(
            contribution("option-a", "label.a")
        ));
        assertEquals(2, host.getComponentCount());
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
        assertEquals(1, host.getComponentCount());
        assertTrue(host.removedOnEdt);
    }

    @Test
    void selectedSnapshotReadsSwingStateOnTheEdt() {
        assertFalse(SwingUtilities.isEventDispatchThread());
        final JPanel host = hostWithOptions();
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

    @Test
    void topLevelGrowsToFitContributionOnShowAndRestoresOnClose() throws Exception {
        // Options container plus a fake top-level window, sized too small for
        // the contributed rows — the stand-in for the host's BoundsKeyManager
        // restored size.
        final JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.add(new NativeCheckBox("native-a"));
        options.add(new NativeCheckBox("native-b"));
        final FakeTopLevel window = new FakeTopLevel();
        window.add(options);
        // Native-fitted size: what the host's pack/restore would produce
        // without the contribution.
        window.setSize(400, window.getPreferredSize().height);
        final int baselineHeight = window.getHeight();
        final ExportSettingsAttachBackend backend =
            new ExportSettingsAttachBackend(JCheckBox::new, mount -> window);

        final Registration registration = backend.attach(options, List.of(
            contribution("option-a", "label.a"),
            contribution("option-b", "label.b")
        ));
        final JPanel owned = panel(options);
        flushEdt();
        assertEquals(baselineHeight, window.getHeight(),
            "no growth may run before the window is showing");

        window.showing = true;
        fireShowingChanged(owned);
        flushEdt();
        flushEdt();
        flushEdt();

        final int preferredHeight = window.getPreferredSize().height;
        assertTrue(preferredHeight > baselineHeight,
            "the contributed panel must raise the required height");
        assertEquals(preferredHeight, window.getHeight(),
            "window must grow to cover its full content height");
        assertEquals(400, window.getWidth(), "width must stay untouched");
        assertEquals(new Dimension(400, baselineHeight), owned.getClientProperty(
            "turboism.export-settings.dialogBaselineSize"));

        registration.close();
        assertEquals(new Dimension(400, baselineHeight), window.getSize(),
            "close must restore the captured pre-growth size");
    }

    @Test
    void topLevelNeverShrinksWhenAlreadyRoomy() throws Exception {
        final JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.add(new NativeCheckBox("native-a"));
        final FakeTopLevel window = new FakeTopLevel();
        window.add(options);
        window.setSize(400, 800);
        final ExportSettingsAttachBackend backend =
            new ExportSettingsAttachBackend(JCheckBox::new, mount -> window);
        final Registration registration = backend.attach(options, List.of(
            contribution("option-a", "label.a")
        ));

        window.showing = true;
        fireShowingChanged(panel(options));
        flushEdt();
        flushEdt();
        flushEdt();

        assertEquals(800, window.getHeight(),
            "a window already covering its content must not shrink");
        registration.close();
        assertEquals(800, window.getHeight());
    }

    @Test
    void failedAttachLeavesTopLevelSizeUntouched() throws Exception {
        final ThrowingContainer options = new ThrowingContainer();
        options.add(new NativeCheckBox("native-a"));
        final FakeTopLevel window = new FakeTopLevel();
        window.add(options);
        window.setSize(400, 120);
        final ExportSettingsAttachBackend backend =
            new ExportSettingsAttachBackend(JCheckBox::new, mount -> window);
        options.throwOnRevalidate = true;

        assertThrows(ExportSettingsAttachException.class,
            () -> backend.attach(options, List.of(contribution("option-a", "label.a"))));
        window.showing = true;
        flushEdt();
        flushEdt();
        assertEquals(120, window.getHeight(),
            "a rolled-back attach must not resize the window");
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void fireShowingChanged(final Component component) {
        final HierarchyEvent event = new HierarchyEvent(
            component, HierarchyEvent.HIERARCHY_CHANGED, component,
            component.getParent(), HierarchyEvent.SHOWING_CHANGED);
        for (java.awt.event.HierarchyListener listener : component.getHierarchyListeners()) {
            listener.hierarchyChanged(event);
        }
    }

    /** Fake top-level window whose showing flag the test controls directly. */
    private static final class FakeTopLevel extends JPanel {
        private boolean showing;

        private FakeTopLevel() {
            super(new BorderLayout());
        }

        @Override
        public boolean isShowing() {
            return showing;
        }
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

    /**
     * A supplied container that is itself a native options-list stand-in: two
     * native check-box leaves resolve it as the mount container.
     */
    private static JPanel hostWithOptions() {
        final JPanel host = new JPanel();
        host.add(new NativeCheckBox("native-a"));
        host.add(new NativeCheckBox("native-b"));
        return host;
    }

    /**
     * Stands in for the host's own check-box leaves ({@code CCheckBox$a} —
     * FlatLaf tri-state buttons): a {@link JCheckBox} subclass, never the exact
     * class the backend materializes.
     */
    private static final class NativeCheckBox extends JCheckBox {
        private NativeCheckBox(final String text) {
            super(text);
        }
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
        public Component add(final Component component) {
            addedOnEdt = SwingUtilities.isEventDispatchThread();
            return super.add(component);
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

        private void resetFlags() {
            addedOnEdt = false;
            removedOnEdt = false;
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
        public Component add(final Component component) {
            final Component added = super.add(component);
            if (throwOnAddAfterSuper) {
                throw new IllegalStateException("add-boom");
            }
            return added;
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
