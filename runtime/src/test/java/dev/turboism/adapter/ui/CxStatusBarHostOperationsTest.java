package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CxStatusBarHostOperationsTest {

    @Test
    void insertsBeforeLastNativeCLabelWithoutMutatingChildrenAndRefreshes() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeMemoryViewer("memoryViewer"),
            new FakeLabel("cursorPosition"), new FakeLabel("coordinates"));
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        Registration registration = host.notifyStatus(new StatusNotification("build", "INFO", "Building"));

        assertEquals(1, tree.addCalls.size());
        AddCall add = tree.addCalls.get(0);
        assertSame(statusBar, add.parent());
        assertEquals(2, add.index());
        assertEquals(List.of("memoryViewer", "cursorPosition", "build", "coordinates"),
            tree.ids(statusBar));
        assertTrue(tree.refreshed.contains(statusBar));
        assertTrue(tree.edtFlags.stream().allMatch(Boolean::booleanValue), "host access must run on EDT");

        registration.close();
        flushEdt();
        assertEquals(1, tree.removeCalls.size());
        assertEquals(List.of("memoryViewer", "cursorPosition", "coordinates"), tree.ids(statusBar));
    }

    @Test
    void compactMetricInsertsImmediatelyLeftOfMemoryViewerWithoutSeverityAppearance() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeMemoryViewer("memoryViewer"),
            new FakeLabel("cursorPosition"), new FakeLabel("coordinates"));
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        Registration registration = host.notifyStatus(compactMetric("perf.cpu", "CPU 12.3%"));

        assertEquals(1, tree.addCalls.size());
        assertSame(statusBar, tree.addCalls.get(0).parent());
        assertEquals(0, tree.addCalls.get(0).index(),
            "compact metric must mount at the memory-viewer child index (left of the memory control)");
        assertEquals(List.of("perf.cpu", "memoryViewer", "cursorPosition", "coordinates"),
            tree.ids(statusBar));
        FakeLabel widget = tree.labels.get("perf.cpu");
        assertEquals("CPU 12.3%", widget.text, "compact metric shows the raw message");
        assertNull(widget.severity, "compact metric must not apply severity appearance");

        registration.close();
        flushEdt();
        assertEquals(List.of("memoryViewer", "cursorPosition", "coordinates"), tree.ids(statusBar));
    }

    @Test
    void compactMetricIgnoresNativeLabelsWhenChoosingMemoryViewerIndex() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeLabel("cursorPosition"),
            new FakeMemoryViewer("memoryViewer"), new FakeLabel("coordinates"));
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        host.notifyStatus(compactMetric("perf.cpu", "CPU --%"));

        assertEquals(1, tree.addCalls.size());
        assertEquals(1, tree.addCalls.get(0).index(),
            "compact metric must use the memory-viewer index even when native labels exist");
        assertEquals(List.of("cursorPosition", "perf.cpu", "memoryViewer", "coordinates"),
            tree.ids(statusBar));
    }

    @Test
    void compactMetricSameIdUpdatesReuseWidgetAndStaleCloseKeepsLatest() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeMemoryViewer("memoryViewer"),
            new FakeLabel("coordinates"));
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        Registration first = host.notifyStatus(compactMetric("perf.cpu", "CPU --%"));
        assertEquals(1, tree.addCalls.size());
        FakeLabel widget = tree.labels.get("perf.cpu");
        assertNull(widget.severity);

        Registration second = host.notifyStatus(compactMetric("perf.cpu", "CPU 12.3%"));
        assertEquals(1, tree.addCalls.size(), "same final ID must not re-add");
        assertSame(widget, tree.labels.get("perf.cpu"));
        assertEquals("CPU 12.3%", widget.text);
        assertNull(widget.severity, "repeated compact updates must never apply severity appearance");

        first.close();
        flushEdt();
        assertEquals(0, tree.removeCalls.size(), "stale registration must not remove the newer entry");
        assertTrue(tree.ids(statusBar).contains("perf.cpu"));

        second.close();
        flushEdt();
        assertEquals(1, tree.removeCalls.size());
        assertFalse(tree.ids(statusBar).contains("perf.cpu"));
    }

    @Test
    void compactMetricAndNotificationSameIdApplyLatestPresentationDeterministically() throws Exception {
        FakeTree tree = new FakeTree().ready();
        tree.statusBar(new FakeMemoryViewer("memoryViewer"), new FakeLabel("coordinates"));
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        host.notifyStatus(compactMetric("status.mixed", "CPU 12.3%"));
        assertNull(tree.labels.get("status.mixed").severity);

        host.notifyStatus(new StatusNotification("status.mixed", "WARNING", "Slow"));
        assertEquals("Slow", tree.labels.get("status.mixed").text);
        assertEquals("WARNING", tree.labels.get("status.mixed").severity,
            "a later ordinary notification must apply severity appearance to the shared widget");
    }

    @Test
    void differentNotificationIdsReplaceTheSingleLatestMessageWidget() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeMemoryViewer("memoryViewer"),
            new FakeLabel("coordinates"));
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        Registration first = host.notifyStatus(
            new StatusNotification("save.started", "INFO", "Saving")
        );
        FakeLabel widget = tree.labels.get("save.started");
        Registration second = host.notifyStatus(
            new StatusNotification("save.finished", "INFO", "Saved")
        );

        assertEquals(1, tree.addCalls.size(), "ordinary messages share one latest-message slot");
        assertSame(widget, tree.labels.get("save.started"));
        assertEquals("save.finished", widget.name);
        assertEquals("Saved", widget.text);
        assertEquals(1, statusBar.children.stream()
            .filter(child -> child == widget)
            .count());

        first.close();
        assertTrue(statusBar.children.contains(widget),
            "a stale registration must not dismiss the latest message");
        second.close();
        assertFalse(statusBar.children.contains(widget));
    }

    @Test
    void fallsBackToMemoryViewerPositionWhenNoCLabelExists() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeMemoryViewer("memoryViewer"), new FakeWidget("spacer"));
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        host.notifyStatus(new StatusNotification("build", "INFO", "Building"));

        assertEquals(0, tree.addCalls.get(0).index());
        assertEquals(List.of("build", "memoryViewer", "spacer"), tree.ids(statusBar));
    }

    @Test
    void reusesWidgetForSameIdAndIgnoresStaleRegistrationsWhileCurrentCloseRemoves() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeMemoryViewer("memoryViewer"),
            new FakeLabel("coordinates"));
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        Registration first = host.notifyStatus(new StatusNotification("build", "INFO", "Building"));
        assertEquals(1, tree.addCalls.size());
        FakeLabel widget = tree.labels.get("build");

        Registration second = host.notifyStatus(new StatusNotification("build", "WARNING", "Slow"));
        assertEquals(1, tree.addCalls.size(), "same final ID must not re-add");
        assertSame(widget, tree.labels.get("build"));
        assertEquals("Slow", widget.text);
        assertEquals("WARNING", widget.severity);
        assertEquals(1, tree.ids(statusBar).stream().filter("build"::equals).count());

        first.close();
        flushEdt();
        assertEquals(0, tree.removeCalls.size(), "stale registration must not remove the newer entry");
        assertTrue(tree.ids(statusBar).contains("build"));

        second.close();
        flushEdt();
        assertEquals(1, tree.removeCalls.size());
        assertFalse(tree.ids(statusBar).contains("build"));

        second.close();
        flushEdt();
        assertEquals(1, tree.removeCalls.size(), "repeated close must be a no-op");
    }

    @Test
    void staleRegistrationNeverRemovesAReplacementEntryWithTheSameId() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeMemoryViewer("memoryViewer"),
            new FakeLabel("coordinates"));
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        Registration first = host.notifyStatus(new StatusNotification("build", "INFO", "Building"));
        Registration second = host.notifyStatus(new StatusNotification("build", "WARNING", "Slow"));
        second.close();
        flushEdt();
        assertEquals(1, tree.removeCalls.size());

        Registration third = host.notifyStatus(new StatusNotification("build", "ERROR", "Failed"));
        assertEquals(2, tree.addCalls.size(), "replacement after close must add a fresh widget");
        assertEquals("Failed", tree.labels.get("build").text);

        first.close();
        flushEdt();
        assertEquals(1, tree.removeCalls.size(),
            "stale first registration must not remove the replacement entry");
        assertTrue(tree.ids(statusBar).contains("build"));
        assertEquals("Failed", tree.labels.get("build").text);

        third.close();
        flushEdt();
        assertEquals(2, tree.removeCalls.size());
        assertFalse(tree.ids(statusBar).contains("build"));
    }

    @Test
    void failedCloseCanBeRetriedAndCompletesOnSecondAttempt() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeMemoryViewer("memoryViewer"),
            new FakeLabel("coordinates"));
        tree.failNextRemove = true;
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        Registration registration = host.notifyStatus(new StatusNotification("build", "INFO", "Building"));
        assertThrows(IllegalStateException.class, registration::close, "first close failure must propagate");
        flushEdt();
        assertEquals(1, tree.removeCalls.size());
        assertTrue(tree.ids(statusBar).contains("build"), "failed close must keep the widget mounted");

        registration.close();
        flushEdt();
        assertEquals(2, tree.removeCalls.size(), "second close retry must remove the widget");
        assertFalse(tree.ids(statusBar).contains("build"));

        registration.close();
        flushEdt();
        assertEquals(2, tree.removeCalls.size(), "third close must be a no-op");
    }

    @Test
    void failedCloseRefreshRetriesRefreshWithoutRemovingTwice() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeMemoryViewer("memoryViewer"),
            new FakeLabel("coordinates"));
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);
        Registration registration = host.notifyStatus(new StatusNotification("build", "INFO", "Building"));
        tree.failNextRefresh = true;

        assertThrows(IllegalStateException.class, registration::close);
        assertFalse(tree.ids(statusBar).contains("build"), "successful remove must not leave the widget mounted");
        assertEquals(1, tree.removeCalls.size());

        registration.close();
        assertEquals(1, tree.removeCalls.size(), "retry must not remove an already detached widget");
        assertEquals(3, tree.refreshed.size(), "retry must complete the failed parent refresh");
    }

    @Test
    void failedFirstRefreshAfterAddIsCompensatedByNativeRemoveAndAllowsFreshReinstall() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeMemoryViewer("memoryViewer"),
            new FakeLabel("coordinates"));
        tree.failNextRefresh = true;
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        assertThrows(IllegalStateException.class,
            () -> host.notifyStatus(new StatusNotification("build", "INFO", "Building")));
        flushEdt();
        assertEquals(1, tree.addCalls.size());
        assertEquals(1, tree.removeCalls.size(), "compensation must native-remove the orphaned widget");
        assertEquals(2, tree.refreshed.size(), "failed refresh plus compensation refresh must both be attempted");
        assertFalse(tree.ids(statusBar).contains("build"), "no orphan widget may remain in the tree");

        Registration registration = host.notifyStatus(new StatusNotification("build", "INFO", "Building"));
        assertEquals(2, tree.addCalls.size(), "no ghost entry: same ID must fresh-add again");
        assertTrue(tree.ids(statusBar).contains("build"));

        registration.close();
        flushEdt();
        assertEquals(2, tree.removeCalls.size());
        assertFalse(tree.ids(statusBar).contains("build"));
    }

    @Test
    void failedNativeRemoveKeepsEntrySoNextNotifyReusesWidgetWithoutReAdding() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget statusBar = tree.statusBar(new FakeMemoryViewer("memoryViewer"),
            new FakeLabel("coordinates"));
        tree.failNextRemove = true;
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        Registration first = host.notifyStatus(new StatusNotification("build", "INFO", "Building"));
        assertThrows(IllegalStateException.class, first::close, "native remove failure must propagate");
        flushEdt();
        assertEquals(1, tree.addCalls.size());
        assertEquals(1, tree.removeCalls.size());
        assertTrue(tree.ids(statusBar).contains("build"), "failed close must not detach the widget");

        host.notifyStatus(new StatusNotification("build", "WARNING", "Slow"));
        assertEquals(1, tree.addCalls.size(), "failed close must not lead to a duplicate widget");
        assertEquals("Slow", tree.labels.get("build").text);
        assertEquals("WARNING", tree.labels.get("build").severity);
    }

    @Test
    void cycleInTreeDoesNotHangAndFailsClosedWhenNoUniqueAnchor() throws Exception {
        FakeTree tree = new FakeTree().ready();
        FakeWidget boxA = tree.newBox();
        FakeWidget boxB = tree.newBox();
        boxA.children.add(boxB);
        boxB.children.add(boxA);
        tree.root.children.add(boxA);

        assertThrows(IllegalStateException.class,
            () -> host(tree).notifyStatus(new StatusNotification("build", "INFO", "Building")));
        assertTrue(tree.addCalls.isEmpty());
    }

    @Test
    void traversalBudgetFailsClosedOnWideTree() throws Exception {
        FakeTree tree = new FakeTree().ready();
        for (int index = 0; index < 5000; index++) {
            tree.root.children.add(new FakeWidget("leaf-" + index));
        }

        assertThrows(IllegalStateException.class,
            () -> host(tree).notifyStatus(new StatusNotification("build", "INFO", "Building")));
        assertTrue(tree.addCalls.isEmpty());
    }

    @Test
    void failsClosedWhenContentRootIsMissing() {
        FakeTree tree = new FakeTree();
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);

        assertThrows(IllegalStateException.class,
            () -> host.notifyStatus(new StatusNotification("build", "INFO", "Building")));
        assertTrue(tree.addCalls.isEmpty());
    }

    @Test
    void failsClosedOnMissingOrAmbiguousAnchors() throws Exception {
        FakeTree noViewer = new FakeTree().ready();
        noViewer.root.children.add(new FakeWidget("box"));
        assertThrows(IllegalStateException.class,
            () -> host(noViewer).notifyStatus(new StatusNotification("build", "INFO", "Building")));

        FakeTree twoParents = new FakeTree().ready();
        twoParents.root.children.add(twoParents.newBox(new FakeMemoryViewer("viewer-a")));
        twoParents.root.children.add(twoParents.newBox(new FakeMemoryViewer("viewer-b")));
        assertThrows(IllegalStateException.class,
            () -> host(twoParents).notifyStatus(new StatusNotification("build", "INFO", "Building")));

        FakeTree twoViewers = new FakeTree().ready();
        twoViewers.root.children.add(twoViewers.newBox(
            new FakeMemoryViewer("viewer-a"), new FakeMemoryViewer("viewer-b")));
        assertThrows(IllegalStateException.class,
            () -> host(twoViewers).notifyStatus(new StatusNotification("build", "INFO", "Building")));
    }

    @Test
    void adapterTurnsHostFailuresIntoSafeModeDiagnostics() {
        FakeTree tree = new FakeTree();
        StatusToolbarAdapter adapter = StatusToolbarAdapterImpl.connected(
            new CxStatusBarHostOperations("5.3.02", tree)
        );

        StatusToolbarAdapter.AdapterResult<Registration> result =
            adapter.notifyStatus(new StatusNotification("build", "INFO", "Building"));

        assertFalse(result.isAvailable());
        SafeModeDiagnostic diagnostic = result.diagnostic().orElseThrow();
        assertEquals(SafeModeDiagnostic.Code.VALIDATION_FAILURE, diagnostic.code());
        assertFalse(diagnostic.message().contains("content root"),
            "host failure text must not leak into diagnostics");
    }

    @Test
    void allHostAccessRunsOnEdtIncludingClose() throws Exception {
        FakeTree tree = new FakeTree().ready();
        tree.statusBar(new FakeMemoryViewer("memoryViewer"), new FakeLabel("coordinates"));
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", tree);
        AtomicReference<Registration> registration = new AtomicReference<>();

        Thread notifier = new Thread(() -> registration.set(
            host.notifyStatus(new StatusNotification("build", "INFO", "Building"))
        ));
        notifier.start();
        notifier.join();
        Thread closer = new Thread(() -> registration.get().close());
        closer.start();
        closer.join();

        assertFalse(tree.edtFlags.isEmpty());
        assertTrue(tree.edtFlags.stream().allMatch(Boolean::booleanValue),
            "every seam method must run on the Swing EDT");
        assertTrue(tree.edtFlags.size() >= 11,
            "every seam method (including contentRoot/children/classification/createLabel) must record");
    }

    @Test
    void exposesOnlyTheExistingTypedStatusCapability() {
        CxStatusBarHostOperations host = new CxStatusBarHostOperations("5.3.02", new FakeTree());

        assertEquals("5.3.02", host.hostVersion());
        assertTrue(host.supports(StatusToolbarAdapter.Capability.STATUS_NOTIFY));
    }

    private static CxStatusBarHostOperations host(final FakeTree tree) {
        return new CxStatusBarHostOperations("5.3.02", tree);
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** Fake native CX tree: children are only mutable through the seam's add/remove. */
    private static final class FakeTree implements CxStatusBarHostAccess {
        private final FakeWidget root = new FakeWidget("contentPane");
        private final List<AddCall> addCalls = new ArrayList<>();
        private final List<AddCall> removeCalls = new ArrayList<>();
        private final List<FakeWidget> refreshed = new ArrayList<>();
        private final List<Boolean> edtFlags = new ArrayList<>();
        private final Map<String, FakeLabel> labels = new HashMap<>();
        private boolean rootReady;
        private boolean failNextRemove;
        private boolean failNextRefresh;

        private FakeTree ready() {
            rootReady = true;
            return this;
        }

        @Override
        public Object contentRoot() {
            recordEdt();
            return rootReady ? root : null;
        }

        @Override
        public List<?> children(final Object container) {
            recordEdt();
            return container instanceof FakeWidget widget
                ? Collections.unmodifiableList(widget.children)
                : null;
        }

        @Override
        public boolean isCLabel(final Object widget) {
            recordEdt();
            return widget instanceof FakeLabel;
        }

        @Override
        public boolean isCMemoryViewerPanel(final Object widget) {
            recordEdt();
            return widget instanceof FakeMemoryViewer;
        }

        @Override
        public Object createLabel(final String id, final String text) {
            recordEdt();
            FakeLabel label = new FakeLabel(id, text);
            labels.put(id, label);
            return label;
        }

        @Override
        public void setName(final Object widget, final String id) {
            ((FakeWidget) widget).name = id;
            recordEdt();
        }

        @Override
        public void setText(final Object widget, final String text) {
            ((FakeWidget) widget).text = text;
            recordEdt();
        }

        @Override
        public void setSeverityAppearance(final Object widget, final String severity) {
            ((FakeWidget) widget).severity = severity;
            recordEdt();
        }

        @Override
        public void add(final Object parent, final Object widget, final int index) {
            FakeWidget target = (FakeWidget) parent;
            if (index < 0 || index > target.children.size()) {
                throw new IllegalStateException("native add rejected index " + index);
            }
            target.children.add(index, (FakeWidget) widget);
            addCalls.add(new AddCall(parent, widget, index));
            recordEdt();
        }

        @Override
        public void remove(final Object parent, final Object widget) {
            removeCalls.add(new AddCall(parent, widget, -1));
            recordEdt();
            if (failNextRemove) {
                failNextRemove = false;
                throw new IllegalStateException("native remove failed");
            }
            FakeWidget target = (FakeWidget) parent;
            if (!target.children.remove(widget)) {
                throw new IllegalStateException("native remove found no child");
            }
        }

        @Override
        public void refresh(final Object widget) {
            refreshed.add((FakeWidget) widget);
            recordEdt();
            if (failNextRefresh) {
                failNextRefresh = false;
                throw new IllegalStateException("native refresh failed");
            }
        }

        private void recordEdt() {
            edtFlags.add(SwingUtilities.isEventDispatchThread());
        }

        private FakeWidget statusBar(final FakeWidget... children) {
            FakeWidget statusBar = new FakeWidget("statusBar");
            Collections.addAll(statusBar.children, children);
            root.children.add(statusBar);
            return statusBar;
        }

        private FakeWidget newBox(final FakeWidget... children) {
            FakeWidget box = new FakeWidget("box");
            Collections.addAll(box.children, children);
            return box;
        }

        private List<String> ids(final FakeWidget widget) {
            return widget.children.stream().map(child -> child.id).toList();
        }
    }

    private static class FakeWidget {
        final String id;
        final List<FakeWidget> children = new ArrayList<>();
        String name;
        String text;
        String severity;

        private FakeWidget(final String id) {
            this.id = Objects.requireNonNull(id, "id");
        }
    }

    private static final class FakeLabel extends FakeWidget {
        private FakeLabel(final String id, final String text) {
            super(id);
            this.text = text;
        }

        private FakeLabel(final String id) {
            super(id);
        }
    }

    private static final class FakeMemoryViewer extends FakeWidget {
        private FakeMemoryViewer(final String id) {
            super(id);
        }
    }

    private record AddCall(Object parent, Object widget, int index) {
    }

    private static StatusNotification compactMetric(final String id, final String message) {
        return new StatusNotification(
            id,
            "INFO",
            message,
            StatusNotification.Presentation.COMPACT_METRIC
        );
    }
}
