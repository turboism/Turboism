package dev.turboism.validation.externalpsd;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.swing.JPopupMenu;
import javax.swing.JTable;

/** Offline unit coverage for the probe's PSD mutation, popup dispatch, and GUI evidence gates. */
public final class ExternalPsdEditHostProbeTest {
    public static void main(final String[] args) throws Exception {
        testPopupTriggerDispatch();
        testSyntheticTargetDiagnostics();
        testRendererPreparationFollowsDispatch();
        testRightClickDispatchFailure();
        testReplacedTargetAfterSelection();
        testUnrelocatableTargetIsRejected();
        testActiveRowResolver();
        testStableRowKeySafety();
        testBoundedRowDispatches();
        testPopupMarker();
        testPopupAttemptAssociation();
        testAutoImportEvidence();

        final byte[] psd = syntheticPsd("LayerA", "B2");
        final List<int[]> names = ExternalPsdEditHostProbe.layerNameRanges(psd);
        assertEquals(2, names.size(), "two layer names parsed");
        assertEquals("LayerA", slice(psd, names.get(0)), "first name");
        assertEquals("B2", slice(psd, names.get(1)), "second name");

        final Optional<byte[]> first = ExternalPsdEditHostProbe.mutateLayerName(psd, 1);
        assertTrue(first.isPresent(), "mutation present");
        final byte[] mutated = first.orElseThrow();
        assertEquals(psd.length, mutated.length, "mutation preserves length");
        int diffs = 0;
        for (int i = 0; i < psd.length; i++) if (psd[i] != mutated[i]) diffs++;
        assertEquals(1, diffs, "exactly one byte differs");
        assertTrue(!Arrays.equals(psd, mutated), "digest changes");

        final Optional<byte[]> second = ExternalPsdEditHostProbe.mutateLayerName(psd, 2);
        assertTrue(second.isPresent(), "cycle 2 mutates second layer");
        assertTrue(!Arrays.equals(mutated, second.orElseThrow()), "distinct content per cycle");

        assertTrue(ExternalPsdEditHostProbe.layerNameRanges(new byte[10]).isEmpty(),
            "truncated input yields no ranges");
        assertTrue(ExternalPsdEditHostProbe.layerNameRanges(
            "not a psd".getBytes(StandardCharsets.UTF_8)).isEmpty(),
            "non-PSD input yields no ranges");
        assertTrue(ExternalPsdEditHostProbe.mutateLayerName(
            "not a psd".getBytes(StandardCharsets.UTF_8), 1).isEmpty(),
            "non-PSD input cannot mutate");

        // A file whose first name byte already equals the replacement still differs.
        final byte[] startsWithA = syntheticPsd("apple", "B2");
        final byte[] changed = ExternalPsdEditHostProbe.mutateLayerName(startsWithA, 1)
            .orElseThrow();
        assertTrue(!Arrays.equals(startsWithA, changed), "same-letter cycle still differs");

        // mutationFor coordinates must describe exactly the byte applyMutation writes,
        // so a reopen stage can re-verify the persisted marker by coordinates alone.
        final var marker = ExternalPsdEditHostProbe.mutationFor(psd, 1).orElseThrow();
        final int[] markedRange = names.get(marker.layer());
        assertEquals(marker.letter(),
            (char) first.orElseThrow()[markedRange[0] + marker.nameOffset()],
            "marker coordinates identify the mutated byte");
        assertEquals(marker.letter(), (char) mutated[markedRange[0] + marker.nameOffset()],
            "persisted byte equals the recorded marker letter");
        System.out.println("PASS: ExternalPsdEditHostProbeTest");
    }

    private static void testPopupTriggerDispatch() {
        assertTrue(!ExternalPsdEditHostProbe.popupTriggerOnPress("Windows 11"),
            "Windows popup trigger is on release");
        assertTrue(ExternalPsdEditHostProbe.popupTriggerOnPress("Linux"),
            "non-Windows popup trigger is on press");

        assertSinglePopupTrigger(true, MouseEvent.MOUSE_PRESSED);
        assertSinglePopupTrigger(false, MouseEvent.MOUSE_RELEASED);
    }

    private static void assertSinglePopupTrigger(final boolean triggerOnPress,
        final int expectedTriggerEvent) {
        final RecordingComponent component = new RecordingComponent();
        final var dispatch = ExternalPsdEditHostProbe.dispatchRightClick(
            component, 10, 12, triggerOnPress);
        assertContains(dispatch.diagnostic(), "synthetic=",
            "synthetic event coordinates are recorded");
        assertContains(dispatch.diagnostic(), "pointer=",
            "real pointer observation is recorded");
        final List<MouseEvent> right = component.events().stream()
            .filter(event -> event.getButton() == MouseEvent.BUTTON3)
            .toList();
        assertEquals(2, right.size(), "right click dispatches press and release");
        assertEquals(expectedTriggerEvent,
            right.stream().filter(MouseEvent::isPopupTrigger).findFirst().orElseThrow().getID(),
            "exactly one right-click event is the popup trigger");
        assertEquals(1L, right.stream().filter(MouseEvent::isPopupTrigger).count(),
            "one popup trigger per right click");
    }

    private static void testRightClickDispatchFailure() {
        final ThrowingComponent component = new ThrowingComponent();
        try {
            ExternalPsdEditHostProbe.dispatchRightClick(component, 10, 12, true);
            throw new AssertionError("right-click handler failures must be exposed");
        } catch (ExternalPsdEditHostProbe.RightClickDispatchException failure) {
            assertEquals(2L, component.events().stream()
                .filter(event -> event.getButton() == MouseEvent.BUTTON3).count(),
                "release is attempted after press failure");
            final java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            final String text = trace.toString();
            assertContains(text, "MOUSE_PRESSED", "press phase is in failure trace");
            assertContains(text, "pressed-failure", "press cause is in failure trace");
            assertContains(text, "MOUSE_RELEASED", "release phase is in failure trace");
            assertContains(text, "released-failure", "release cause is in failure trace");
            assertTrue(failure.getCause() != null, "first dispatch failure remains the cause");
            assertEquals("pressed-failure", failure.getCause().getCause().getMessage(),
                "first original cause is retained");
            assertEquals(1, failure.getSuppressed().length,
                "second dispatch failure remains suppressed");
            assertEquals("released-failure", failure.getSuppressed()[0].getCause().getMessage(),
                "second original cause is retained");
        }
    }

    private static void testSyntheticTargetDiagnostics() {
        final javax.swing.JTable table = new javax.swing.JTable(
            new Object[][]{{"value"}}, new Object[]{"column"}) {
            @Override protected void processMouseEvent(final MouseEvent event) {
                // Keep this renderer-diagnostic test independent of BasicTableUI's headful
                // menu-shortcut lookup; the production probe still dispatches normally.
            }
        };
        final var dispatch = ExternalPsdEditHostProbe.dispatchRightClick(table, 2, 2, true);
        assertContains(dispatch.diagnostic(), "target=",
            "dispatch target identity is recorded");
        assertContains(dispatch.diagnostic(), "renderer=",
            "table renderer candidate is recorded");
        assertContains(dispatch.diagnostic(), "prepared=",
            "prepared renderer state is recorded");
        assertContains(dispatch.diagnostic(), "skipped renderer preparation",
            "detached diagnostic does not prepare a renderer");
        assertContains(dispatch.diagnostic(), "screen=(",
            "synthetic screen coordinates are recorded");
    }

    private static void testRendererPreparationFollowsDispatch() {
        final OrderedTable table = new OrderedTable();
        ExternalPsdEditHostProbe.dispatchRightClick(table, 2, 2, true);
        assertTrue(table.rightClickSeen(), "right-click events run before renderer preparation");
        assertTrue(table.rendererPrepared(), "live renderer diagnostic is still recorded");
    }

    private static void testReplacedTargetAfterSelection() {
        final ReplacingComponent captured = new ReplacingComponent();
        final RecordingComponent replacement = new RecordingComponent();
        final var dispatch = ExternalPsdEditHostProbe.dispatchRightClickAfterSelection(
            captured, 10, 12, true,
            () -> new ExternalPsdEditHostProbe.DispatchTarget(replacement, 20, 22));

        assertTrue(captured.detached(), "selection phase records the captured component as detached");
        assertEquals(0L, captured.events().stream()
            .filter(event -> event.getButton() == MouseEvent.BUTTON3).count(),
            "detached captured component receives no right-click events");
        final List<MouseEvent> right = replacement.events().stream()
            .filter(event -> event.getButton() == MouseEvent.BUTTON3)
            .toList();
        assertEquals(2, right.size(), "replacement receives the right-click press and release");
        assertEquals(1L, right.stream().filter(MouseEvent::isPopupTrigger).count(),
            "replacement receives one popup trigger");
        assertContains(dispatch.diagnostic(), "source=",
            "replacement dispatch records its event source");
    }

    private static void testUnrelocatableTargetIsRejected() {
        final ReplacingComponent captured = new ReplacingComponent();
        try {
            ExternalPsdEditHostProbe.dispatchRightClickAfterSelection(
                captured, 10, 12, true, () -> null);
            throw new AssertionError("missing replacement target must be rejected");
        } catch (RuntimeException failure) {
            assertContains(failure.getMessage(), "could not be revalidated",
                "missing replacement target explains the rejection");
        }
        assertEquals(0L, captured.events().stream()
            .filter(event -> event.getButton() == MouseEvent.BUTTON3).count(),
            "unrelocatable target receives no right-click events");
        assertEquals(3, ExternalPsdEditHostProbe.rowDispatchAttemptLimit(),
            "row relocation retries are bounded");
    }

    private static void testActiveRowResolver() {
        final Component original = new RecordingComponent();
        final Component replacement = new RecordingComponent();
        final var captured = capturedRow("widget-a", "window-a", 2, "mesh-a", true);

        final var sameIdentity = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(currentRow("widget-a", "window-a", 2, "mesh-a", true, original)));
        assertTrue(sameIdentity.available(), "same widget and row identity resolves");
        assertSame(original, sameIdentity.row().component(), "same identity keeps target");

        final var sameIdentityWithDuplicate = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(
                currentRow("widget-a", "window-a", 2, "mesh-a", true, original),
                currentRow("widget-b", "window-a", 8, "mesh-a", true, replacement)));
        assertTrue(sameIdentityWithDuplicate.available(),
            "same identity wins over another same-key row");
        assertSame(original, sameIdentityWithDuplicate.row().component(),
            "same identity branch preserves the captured target constraint");

        final var moved = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(currentRow("widget-a", "window-a", 7, "mesh-a", true, original)));
        assertTrue(moved.available(), "a uniquely keyed row can move within its widget");
        assertEquals(7, moved.row().row(), "row movement resolves the new row index");

        final var replaced = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(currentRow("widget-b", "window-a", 8, "mesh-a", true, replacement)));
        assertTrue(replaced.available(), "a replacement component can be relocated");
        assertSame(replacement, replaced.row().component(),
            "replacement component is the resolved target");
        assertEquals(8, replaced.row().row(), "replacement row is used instead of captured index");

        final var otherWindow = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(currentRow("widget-b", "window-b", 8, "mesh-a", true, replacement)));
        assertTrue(!otherWindow.available(), "matching row in another window is rejected");
        assertContains(otherWindow.reason(), "captured window",
            "cross-window rejection identifies the captured window constraint");

        final var ambiguous = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            captured, List.of(
                currentRow("widget-b", "window-a", 8, "mesh-a", true, replacement),
                currentRow("widget-c", "window-a", 9, "mesh-a", true, new RecordingComponent())));
        assertTrue(!ambiguous.available(), "duplicate same-window keys are rejected");
        assertContains(ambiguous.reason(), "ambiguous", "ambiguous row rejection is explicit");

        final var unavailable = ExternalPsdEditHostProbe.resolveActiveRowForTest(
            capturedRow("widget-a", "window-a", 2, "", false),
            List.of(currentRow("widget-a", "window-a", 2, "mesh-a", true, original)));
        assertTrue(!unavailable.available(), "an unavailable capture key is rejected");
        assertContains(unavailable.reason(), "no stable key",
            "unavailable key rejection explains why index reuse is unsafe");
    }

    private static void testStableRowKeySafety() {
        final JTable collision = new JTable(new Object[][]{
            {"a", "b|String:c"},
            {"a|String:b", "c"}
        }, new Object[]{"one", "two"});
        final var first = ExternalPsdEditHostProbe.rowKeyForTest(
            collision, ExternalPsdEditHostProbe.RowKind.TABLE, 0);
        final var second = ExternalPsdEditHostProbe.rowKeyForTest(
            collision, ExternalPsdEditHostProbe.RowKind.TABLE, 1);
        assertTrue(first.available() && second.available(),
            "ordinary table values have stable keys");
        assertTrue(!first.value().equals(second.value()),
            "delimiters in values cannot collide in a row key");

        final String prefix = "x".repeat(256);
        final JTable longValues = new JTable(new Object[][]{
            {prefix + "a"}, {prefix + "b"}
        }, new Object[]{"one"});
        final var longFirst = ExternalPsdEditHostProbe.rowKeyForTest(
            longValues, ExternalPsdEditHostProbe.RowKind.TABLE, 0);
        final var longSecond = ExternalPsdEditHostProbe.rowKeyForTest(
            longValues, ExternalPsdEditHostProbe.RowKind.TABLE, 1);
        assertTrue(!longFirst.value().equals(longSecond.value()),
            "full values, not a 256-character preview, identify rows");

        final JTable throwing = new JTable(new Object[][]{{new ThrowingValue()}},
            new Object[]{"one"});
        assertTrue(!ExternalPsdEditHostProbe.rowKeyForTest(
            throwing, ExternalPsdEditHostProbe.RowKind.TABLE, 0).available(),
            "toString failure makes the row key unavailable");

        final Object identityOnly = new Object();
        final JTable defaultString = new JTable(new Object[][]{{identityOnly}},
            new Object[]{"one"});
        assertTrue(!ExternalPsdEditHostProbe.rowKeyForTest(
            defaultString, ExternalPsdEditHostProbe.RowKind.TABLE, 0).available(),
            "Object class-at-identity text is not a stable cross-component key");
    }

    private static void testBoundedRowDispatches() throws Exception {
        final int[] calls = {0};
        final int exhausted = ExternalPsdEditHostProbe.runBoundedRowDispatches(attempt -> {
            calls[0]++;
            assertEquals(calls[0], attempt, "retry attempt numbers are sequential");
            return true;
        });
        assertEquals(ExternalPsdEditHostProbe.rowDispatchAttemptLimit(), exhausted,
            "retry loop stops at its configured upper bound");
        assertEquals(ExternalPsdEditHostProbe.rowDispatchAttemptLimit(), calls[0],
            "retry operation is invoked only through the upper bound");

        calls[0] = 0;
        final int stopped = ExternalPsdEditHostProbe.runBoundedRowDispatches(attempt -> {
            calls[0]++;
            return attempt < 2;
        });
        assertEquals(2, stopped, "retry loop stops when the operation succeeds");
        assertEquals(2, calls[0], "successful attempt prevents another retry");
    }

    private static ExternalPsdEditHostProbe.CapturedRow capturedRow(
        final String widget, final String window, final int row, final String key,
        final boolean keyAvailable) {
        return new ExternalPsdEditHostProbe.CapturedRow(
            ExternalPsdEditHostProbe.RowKind.TABLE, "HostTable", widget, window, row,
            key, keyAvailable, "(0,0,10,10)");
    }

    private static ExternalPsdEditHostProbe.CurrentRow currentRow(
        final String widget, final String window, final int row, final String key,
        final boolean keyAvailable, final Component component) {
        return new ExternalPsdEditHostProbe.CurrentRow(
            capturedRow(widget, window, row, key, keyAvailable), component,
            new Rectangle(0, row * 10, 100, 10));
    }

    private static void testPopupMarker() {
        final JPopupMenu popup = new JPopupMenu();
        final javax.swing.JMenuItem item = new javax.swing.JMenuItem("Edit PSD Externally");
        popup.add(item);
        final String marker = ExternalPsdEditHostProbe.popupMarker(popup);
        assertContains(marker, "javax.swing.JPopupMenu@", "popup class and identity are recorded");
        assertContains(marker, "javax.swing.JMenuItem@", "menu child class and identity are recorded");
        assertContains(marker, "menuItem=true", "menu item subtype is identified");
        assertContains(marker, "text='Edit PSD Externally'", "menu text is recorded");
        assertContains(marker, "visible=false", "popup visibility is recorded");
        assertContains(marker, "showing=false", "popup showing state is recorded");
    }

    private static void testPopupAttemptAssociation() {
        final JPopupMenu old = new JPopupMenu();
        final JPopupMenu fresh = new JPopupMenu();
        assertSame(old, ExternalPsdEditHostProbe.popupForAttempt(
            List.of(old), List.of(old), List.of(old)),
            "a successfully dismissed popup may be reused for this attempt");
        assertSame(fresh, ExternalPsdEditHostProbe.popupForAttempt(
            List.of(old), List.of(), List.of(old, fresh)),
            "a new visible popup is associated with this attempt");
        assertNull(ExternalPsdEditHostProbe.popupForAttempt(
            List.of(old), List.of(), List.of(old)),
            "an old popup that was not dismissed is rejected as stale");
    }

    private static void testAutoImportEvidence() {
        final var before = new ExternalPsdEditHostProbe.GuiTargetState(
            true, "binding-a", 7L, "raw-a", false, "");
        final var applied = new ExternalPsdEditHostProbe.GuiTargetState(
            true, "binding-a", 7L, "raw-a", true, "");
        assertTrue(ExternalPsdEditHostProbe.acceptsAutoImport(before, applied, "raw-a"),
            "same target false-to-true replacement is accepted");
        assertTrue(!ExternalPsdEditHostProbe.acceptsAutoImport(before,
            new ExternalPsdEditHostProbe.GuiTargetState(true, "binding-b", 7L, "raw-a", true, ""),
            "raw-a"), "binding change is not replacement evidence");
        assertTrue(!ExternalPsdEditHostProbe.acceptsAutoImport(before,
            new ExternalPsdEditHostProbe.GuiTargetState(true, "binding-a", 8L, "raw-a", true, ""),
            "raw-a"), "generation change is not replacement evidence");
        assertTrue(!ExternalPsdEditHostProbe.acceptsAutoImport(before,
            new ExternalPsdEditHostProbe.GuiTargetState(true, "binding-a", 7L, "raw-b", true, ""),
            "raw-a"), "raw target change is not replacement evidence");
        assertTrue(!ExternalPsdEditHostProbe.acceptsAutoImport(
            new ExternalPsdEditHostProbe.GuiTargetState(true, "binding-a", 7L, "raw-a", true, ""),
            applied, "raw-a"), "initial isReplaced=true is not new replacement evidence");
    }

    private static String slice(final byte[] psd, final int[] range) {
        return new String(psd, range[0], range[1], StandardCharsets.US_ASCII);
    }

    /** Minimal valid-structured PSD: header, empty color mode, empty resources, layer info. */
    private static byte[] syntheticPsd(final String... layerNames)
        throws java.io.IOException {
        final ByteArrayOutputStream layerRecords = new ByteArrayOutputStream();
        final ByteArrayOutputStream channelData = new ByteArrayOutputStream();
        for (final String name : layerNames) {
            final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            final int padded = (nameBytes.length + 1 + 3) & ~3;
            final ByteArrayOutputStream extra = new ByteArrayOutputStream();
            extra.write(int32(0));            // mask length
            extra.write(int32(0));            // blending ranges length
            extra.write(nameBytes.length);    // pascal length
            extra.write(nameBytes, 0, nameBytes.length);
            for (int i = nameBytes.length + 1; i < padded; i++) extra.write(0);
            final byte[] extraBytes = extra.toByteArray();

            layerRecords.write(int32(0)); layerRecords.write(int32(0));
            layerRecords.write(int32(4)); layerRecords.write(int32(4)); // rect
            layerRecords.write(int16(1));                                // channel count
            layerRecords.write(int16(0)); layerRecords.write(int32(4));  // ch id + len
            layerRecords.write("8BIM".getBytes(StandardCharsets.US_ASCII), 0, 4);
            layerRecords.write("norm".getBytes(StandardCharsets.US_ASCII), 0, 4);
            layerRecords.write(255); layerRecords.write(0);
            layerRecords.write(8); layerRecords.write(0);                // opacity..filler
            layerRecords.write(int32(extraBytes.length));
            layerRecords.write(extraBytes, 0, extraBytes.length);
            channelData.write(int32(0xDEADBEEF));                        // 4 bytes channel data
        }
        final ByteArrayOutputStream layerInfo = new ByteArrayOutputStream();
        layerInfo.write(int16(layerNames.length));
        layerInfo.write(layerRecords.toByteArray(), 0, layerRecords.size());
        layerInfo.write(channelData.toByteArray(), 0, channelData.size());
        final ByteArrayOutputStream layerMask = new ByteArrayOutputStream();
        layerMask.write(int32(layerInfo.size()));
        layerMask.write(layerInfo.toByteArray(), 0, layerInfo.size());

        final ByteArrayOutputStream psd = new ByteArrayOutputStream();
        psd.write("8BPS".getBytes(StandardCharsets.US_ASCII), 0, 4);
        psd.write(int16(1));                 // version
        psd.write(new byte[6], 0, 6);        // reserved
        psd.write(int16(3));                 // channels
        psd.write(int32(4)); psd.write(int32(4));  // height, width
        psd.write(int16(8)); psd.write(int16(3));  // depth, color mode
        psd.write(int32(0));                 // color mode data
        psd.write(int32(0));                 // image resources
        psd.write(int32(layerMask.size()));
        psd.write(layerMask.toByteArray(), 0, layerMask.size());
        psd.write(int16(0));                 // compression
        psd.write(new byte[12], 0, 12);      // image data
        return psd.toByteArray();
    }

    private static byte[] int32(final int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    private static byte[] int16(final int value) {
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) value).array();
    }

    private static void assertTrue(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertContains(final String text, final String expected,
        final String message) {
        assertTrue(text.contains(expected), message + " expected=" + expected + " text=" + text);
    }

    private static void assertSame(final Object expected, final Object actual,
        final String message) {
        if (expected != actual) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertNull(final Object actual, final String message) {
        if (actual != null) throw new AssertionError(message + " actual=" + actual);
    }

    private static void assertEquals(final Object expected, final Object actual,
        final String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static final class RecordingComponent extends Component {
        private final List<MouseEvent> events = new ArrayList<>();

        private RecordingComponent() {
            enableEvents(AWTEvent.MOUSE_EVENT_MASK);
        }

        @Override protected void processMouseEvent(final MouseEvent event) {
            events.add(event);
        }

        private List<MouseEvent> events() { return events; }
    }

    private static final class ThrowingComponent extends Component {
        private final List<MouseEvent> events = new ArrayList<>();

        private ThrowingComponent() {
            enableEvents(AWTEvent.MOUSE_EVENT_MASK);
        }

        @Override protected void processMouseEvent(final MouseEvent event) {
            events.add(event);
            if (event.getButton() == MouseEvent.BUTTON3) {
                throw new IllegalStateException(event.getID() == MouseEvent.MOUSE_PRESSED
                    ? "pressed-failure" : "released-failure");
            }
        }

        private List<MouseEvent> events() { return events; }
    }

    private static final class ThrowingValue {
        @Override public String toString() {
            throw new IllegalStateException("row-value-failure");
        }
    }

    private static final class ReplacingComponent extends Component {
        private final List<MouseEvent> events = new ArrayList<>();
        private boolean detached;

        private ReplacingComponent() {
            enableEvents(AWTEvent.MOUSE_EVENT_MASK);
        }

        @Override protected void processMouseEvent(final MouseEvent event) {
            events.add(event);
            if (event.getButton() == MouseEvent.BUTTON1
                && event.getID() == MouseEvent.MOUSE_RELEASED) {
                detached = true;
            }
        }

        private boolean detached() { return detached; }
        private List<MouseEvent> events() { return events; }
    }

    private static final class OrderedTable extends javax.swing.JTable {
        private final Container parent = new Container();
        private boolean rightClickSeen;
        private boolean rendererPrepared;

        private OrderedTable() {
            super(new Object[][]{{"value"}}, new Object[]{"column"});
        }

        @Override public boolean isShowing() { return true; }
        @Override public boolean isDisplayable() { return true; }
        @Override public Container getParent() { return parent; }

        @Override protected void processMouseEvent(final MouseEvent event) {
            if (event.getButton() == MouseEvent.BUTTON3) rightClickSeen = true;
        }

        @Override public Component prepareRenderer(final javax.swing.table.TableCellRenderer renderer,
            final int row, final int column) {
            rendererPrepared = true;
            return super.prepareRenderer(renderer, row, column);
        }

        private boolean rightClickSeen() { return rightClickSeen; }
        private boolean rendererPrepared() { return rendererPrepared; }
    }
}
