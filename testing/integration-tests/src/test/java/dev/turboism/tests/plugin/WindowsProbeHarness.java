package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.UiColor;
import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * Shared infrastructure harness for Windows host probes and their verdict test suites.
 *
 * <p>Consolidates repeated EDT dispatching, artifact and JSON serialization, save confirmation
 * detection, host-close route mapping, failure description formatting, and common test fixture
 * doubles across {@code Windows*.java}.</p>
 */
public final class WindowsProbeHarness {

    public static final long DEFAULT_SAVE_DEADLINE_MILLIS = 30_000L;
    public static final long DEFAULT_SAVE_POLL_MILLIS = 100L;
    public static final int SAVE_STABLE_SAMPLES = 3;

    private WindowsProbeHarness() {}

    // --- EDT / Host-Thread Dispatching ---------------------------------------

    public static <T> T onEdt(final Callable<T> call) throws Exception { return onEdt(call, 5_000L); }

    public static <T> T onEdt(final Callable<T> call, final long timeoutMillis) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(call.call());
            } catch (Throwable error) {
                failure.set(error);
            } finally { completed.countDown(); }
        });
        if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("EDT execution timed out after " + timeoutMillis + "ms");
        }
        final Throwable error = failure.get();
        if (error != null) {
            if (error instanceof Exception exception) throw exception;
            throw new RuntimeException(error);
        }
        return result.get();
    }

    public static void onEdt(final Runnable operation) {
        if (SwingUtilities.isEventDispatchThread()) {
            operation.run();
        } else { SwingUtilities.invokeLater(operation); }
    }

    // --- JSON Serialization & Escaping ---------------------------------------

    public static String json(final String value) { return json(value, 512); }

    public static String json(final String value, final int maxCodePoints) {
        if (value == null) return "";
        final StringBuilder escaped = new StringBuilder(Math.min(value.length() + 16, maxCodePoints * 2));
        value.codePoints().limit(maxCodePoints).forEach(codePoint -> {
            switch (codePoint) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (codePoint < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04x", codePoint));
                    else escaped.appendCodePoint(codePoint);
                }
            }
        });
        return escaped.toString();
    }

    public static String quoted(final String value) { return "\"" + json(value) + "\""; }

    public static int identityHash(final Object value) { return value == null ? 0 : System.identityHashCode(value); }

    public static String pointJson(final Point point) {
        return point == null ? "null" : "{\"x\":" + point.x + ",\"y\":" + point.y + "}";
    }

    public static String rectangleJson(final Rectangle r) {
        return r == null ? "null" : "{\"x\":" + r.x + ",\"y\":" + r.y + ",\"width\":" + r.width + ",\"height\":" + r.height + "}";
    }

    // --- File / Artifact I/O -------------------------------------------------

    public static void append(final Path artifact, final String value) throws Exception {
        append(artifact, value, false);
    }

    public static void append(final Path artifact, final String value, final boolean terminal) throws Exception {
        if (artifact.getParent() != null) { Files.createDirectories(artifact.getParent()); }
        Files.writeString( artifact, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    public static void writeAtomic(final Path target, final String text) throws IOException {
        if (target.getParent() != null) { Files.createDirectories(target.getParent()); }
        final Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --- Save Confirmation ---------------------------------------------------

    public record SaveConfirmation(
            boolean confirmed, long beforeMtimeMillis, long beforeSize, long afterMtimeMillis, long afterSize) {
        public String report(final String prefix) {
            return prefix + "confirmed=" + confirmed + ";" + prefix + "beforeMtimeMillis=" + beforeMtimeMillis + ";"
                    + prefix + "beforeSize=" + beforeSize + ";" + prefix + "afterMtimeMillis=" + afterMtimeMillis + ";"
                    + prefix + "afterSize=" + afterSize;
        }
    }

    public static SaveConfirmation awaitSaveConfirmation(
            final Path fixture, final FileTime beforeMtime, final long beforeSize) throws Exception {
        return awaitSaveConfirmation(
                fixture, beforeMtime, beforeSize, DEFAULT_SAVE_DEADLINE_MILLIS, DEFAULT_SAVE_POLL_MILLIS);
    }

    public static SaveConfirmation awaitSaveConfirmation( final Path fixture, final FileTime beforeMtime,
            final long beforeSize, final long deadlineMillis, final long pollMillis)
            throws Exception {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(beforeMtime, "beforeMtime");
        if (!Files.isRegularFile(fixture)) {
            throw new IllegalArgumentException("validation fixture is missing or unreadable: " + fixture);
        }
        final long deadlineNanos = System.nanoTime() + deadlineMillis * 1_000_000L;
        FileTime changedMtime = null;
        long changedSize = -1L;
        int stableSamples = 0;
        while (System.nanoTime() < deadlineNanos) {
            final FileTime mtime = Files.getLastModifiedTime(fixture);
            final long size = Files.size(fixture);
            if (!mtime.equals(beforeMtime) || size != beforeSize) {
                if (changedMtime == null || !changedMtime.equals(mtime) || changedSize != size) {
                    changedMtime = mtime;
                    changedSize = size;
                    stableSamples = 0;
                }
                stableSamples++;
                if (stableSamples >= SAVE_STABLE_SAMPLES) {
                    return new SaveConfirmation(true, beforeMtime.toMillis(), beforeSize, mtime.toMillis(), size);
                }
            }
            Thread.sleep(pollMillis);
        }
        final FileTime lastMtime = Files.getLastModifiedTime(fixture);
        return new SaveConfirmation(
                false, beforeMtime.toMillis(), beforeSize, lastMtime.toMillis(), Files.size(fixture));
    }

    // --- Failure Description & Diagnostic Formatting ------------------------

    public static String failureDescription(final Throwable failure) {
        if (failure == null) return "null";
        final StringBuilder description = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            if (depth > 0) description.append(" <- ");
            final String message = current.getMessage();
            description.append(current.getClass().getSimpleName()).append(": ")
                    .append(message == null || message.isBlank() ? current.getClass().getSimpleName() : message);
        }
        return description.toString();
    }

    // --- Memory & GC Diagnostics --------------------------------------------

    public static void forceGcQuietly() {
        try {
            for (int round = 0; round < 3; round++) {
                System.gc();
                Thread.sleep(250L);
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    public static long heapUsedBytes() {
        return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    public static long nonHeapUsedBytes() {
        return java.lang.management.ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed();
    }

    public static long gcCollectionCount() {
        long total = 0L;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            final long count = gc.getCollectionCount();
            if (count > 0L) total += count;
        }
        return total;
    }

    public static long gcCollectionTimeMillis() {
        long total = 0L;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            final long time = gc.getCollectionTime();
            if (time > 0L) total += time;
        }
        return total;
    }

    // --- Host Window Selection & Host Close ----------------------------------

    public static WindowsHistoryNativeUiHostClose.HostCloseRoute hostCloseRoute(final String hostVersion) {
        return WindowsHistoryNativeUiHostClose.hostCloseRoute(hostVersion);
    }

    public static Window selectHostWindow(final Window[] windows) {
        Window target = null;
        long largestArea = -1L;
        for (final Window window : windows) {
            if (window instanceof Dialog || !window.isDisplayable() || !window.isVisible()) continue;
            final long area = (long) window.getWidth() * window.getHeight();
            if (area > largestArea) {
                target = window;
                largestArea = area;
            }
        }
        if (target == null) throw new IllegalStateException("No visible, displayable non-dialog host window found.");
        return target;
    }

    public static void pressAltF4(final Robot robot) {
        robot.keyPress(KeyEvent.VK_ALT);
        try {
            robot.keyPress(KeyEvent.VK_F4);
        } finally {
            robot.keyRelease(KeyEvent.VK_F4);
            robot.keyRelease(KeyEvent.VK_ALT);
        }
    }

    public static boolean invokeMenuShortcut(final int key) throws Exception {
        final AtomicReference<Boolean> invoked = new AtomicReference<>(false);
        final CountDownLatch dispatched = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                for (final Frame frame : Frame.getFrames()) {
                    if (!frame.isVisible()) continue;
                    final JMenuBar bar = frame instanceof javax.swing.JFrame jf ? jf.getJMenuBar() : null;
                    if (bar == null) continue;
                    for (int i = 0; i < bar.getMenuCount(); i++) {
                        final JMenu menu = bar.getMenu(i);
                        if (menu == null) continue;
                        final JMenuItem item = findMenuShortcut(menu, key);
                        if (item != null && item.isEnabled()) {
                            item.doClick();
                            invoked.set(true);
                            return;
                        }
                    }
                }
            } finally { dispatched.countDown(); }
        });
        if (!dispatched.await(500L, TimeUnit.MILLISECONDS)) return false;
        return invoked.get();
    }

    public static JMenuItem findMenuShortcut(final JMenuItem item, final int key) {
        final KeyStroke stroke = item.getAccelerator();
        if (stroke != null && stroke.getKeyCode() == key) return item;
        if (item instanceof JMenu menu) {
            for (int i = 0; i < menu.getItemCount(); i++) {
                final JMenuItem child = menu.getItem(i);
                if (child != null) {
                    final JMenuItem match = findMenuShortcut(child, key);
                    if (match != null) return match;
                }
            }
        }
        return null;
    }

    // --- AWT Mouse Dispatch Helpers for Tests -------------------------------

    public static void dispatchMouseEvent(final Component target, final int type, final Point point) throws Exception {
        dispatchMouseEvent(target, mouse(target, type, point));
    }

    public static void dispatchMouseEvent(final Component target, final int type, final Point point, final long when)
            throws Exception { dispatchMouseEvent(target, mouse(target, type, point, when)); }

    public static void dispatchMouseEvent(final Component target, final MouseEvent event) throws Exception {
        SwingUtilities.invokeAndWait(() -> target.dispatchEvent(event));
    }

    public static MouseEvent mouse(final Component source, final int type, final Point point) {
        return mouse(source, type, point, System.currentTimeMillis() + 1L);
    }

    public static MouseEvent mouse(final Component source, final int type, final Point point, final long when) {
        return new MouseEvent( source, type, when, InputEvent.BUTTON1_DOWN_MASK, point.x, point.y, point.x, point.y, 1,
                false, MouseEvent.BUTTON1);
    }

    // --- Shared History & Test Doubles --------------------------------------

    public static WindowsHistoryManagerValidationProbe.NativeDetail simpleUnavailable() {
        return new WindowsHistoryManagerValidationProbe.NativeDetail( "SIMPLE", "com.live2d.undo.SimpleUndo", "target",
                "", "", "", "", -1, 0, List.of(), List.of(), true, false, "history.detail.post-state-unavailable");
    }

    public static WindowsHistoryManagerValidationProbe.NativeDetail group( final int observedChildCount,
            final List<WindowsHistoryManagerValidationProbe.NativeDetail> children, final String degradationCode) {
        return new WindowsHistoryManagerValidationProbe.NativeDetail( "GROUP", "com.live2d.undo.GroupUndo", "", "", "",
                "", "", -1, observedChildCount, children.stream()
                        .map(WindowsHistoryManagerValidationProbe.NativeDetail::entryClass).toList(), children, false,
                false, degradationCode);
    }

    public static WindowsHistoryManagerValidationProbe.ManagerSnapshot absentManager(final String name) {
        return new WindowsHistoryManagerValidationProbe.ManagerSnapshot(name, "null", -1, false, false, 0, List.of());
    }

    public static WindowsHistoryManagerValidationProbe.ManagerSnapshot manager(
            final String name, final List<WindowsHistoryManagerValidationProbe.Entry> entries, final int position) {
        return new WindowsHistoryManagerValidationProbe.ManagerSnapshot(
                name, "native-" + name, position, true, false, entries.size(), entries);
    }

    public static WindowsHistoryManagerValidationProbe.Snapshot snapshot(final String... ids) {
        final List<WindowsHistoryManagerValidationProbe.Entry> nativeEntries = new ArrayList<>();
        final List<WindowsHistoryManagerValidationProbe.SdkEntry> sdkEntries = new ArrayList<>();
        final WindowsHistoryManagerValidationProbe.NativeDetail detail =
                new WindowsHistoryManagerValidationProbe.NativeDetail( "PROPERTY", "com.live2d.undo.PropertyUndo",
                        "target", "value", "0", "1", "", -1, 0, List.of(), List.of(), true, true, "");
        for (int index = 0; index < ids.length; index++) {
            nativeEntries.add(new WindowsHistoryManagerValidationProbe.Entry(index, "entry-" + index, true, detail));
            sdkEntries.add(
                    new WindowsHistoryManagerValidationProbe.SdkEntry(index, ids[index], "entry-" + index, "{}"));
        }
        final WindowsHistoryManagerValidationProbe.ManagerSnapshot document =
                manager("DOCUMENT", nativeEntries, ids.length);
        return new WindowsHistoryManagerValidationProbe.Snapshot( "2024-01-01T00:00:00Z", "AWT-EventQueue-0", true,
                "loader@1", "document@1", "ModelingMode", "mode@1", document,
                manager("CURRENT", nativeEntries, ids.length), manager("MAIN", nativeEntries, ids.length),
                manager("LINKED", nativeEntries, ids.length),
                new WindowsHistoryManagerValidationProbe.SdkHistorySnapshot( "AVAILABLE", 1L, 1L, ids.length, true,
                        false, "history-document-1", "history-manager-1", ids.length, sdkEntries));
    }

    public static WindowsHistoryManagerValidationProbe.Snapshot snapshotWithNativeDetail(
            final WindowsHistoryManagerValidationProbe.NativeDetail detail, final String... ids) {
        final List<WindowsHistoryManagerValidationProbe.Entry> nativeEntries = new ArrayList<>();
        final List<WindowsHistoryManagerValidationProbe.SdkEntry> sdkEntries = new ArrayList<>();
        for (int index = 0; index < ids.length; index++) {
            nativeEntries.add(new WindowsHistoryManagerValidationProbe.Entry(index, "entry-" + index, true, detail));
            sdkEntries.add(
                    new WindowsHistoryManagerValidationProbe.SdkEntry(index, ids[index], "entry-" + index, "{}"));
        }
        final WindowsHistoryManagerValidationProbe.ManagerSnapshot document =
                manager("DOCUMENT", nativeEntries, ids.length);
        return new WindowsHistoryManagerValidationProbe.Snapshot( "2024-01-01T00:00:00Z", "AWT-EventQueue-0", true,
                "loader@1", "document@1", "ModelingMode", "mode@1", document,
                manager("CURRENT", nativeEntries, ids.length), manager("MAIN", nativeEntries, ids.length),
                manager("LINKED", nativeEntries, ids.length),
                new WindowsHistoryManagerValidationProbe.SdkHistorySnapshot( "AVAILABLE", 1L, 1L, ids.length, true,
                        false, "history-document-1", "history-manager-1", ids.length, sdkEntries));
    }

    public static HistoryRelationChange.Endpoint targetEndpoint(final HistoryTarget target) {
        return new HistoryRelationChange.Endpoint(HistoryRelationChange.State.TARGET, Optional.of(target));
    }

    public static HistoryChange relationChange(final HistoryRelationChange relation) {
        return new HistoryChange( HistoryChange.Operation.SET, Optional.of(0), Optional.empty(), Optional.empty(),
                Optional.empty(), new HistoryEditContext(HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of()),
                Optional.of(relation));
    }

    public static HistoryEntryDetail membershipDetail( final HistoryRelationChange.Endpoint before,
            final HistoryRelationChange.Endpoint after, final HistoryAction.DetailLevel level) {
        return relationDetail(
                new HistoryRelationChange(HistoryRelationChange.Kind.PART_MEMBERSHIP, before, after), level);
    }

    public static HistoryEntryDetail relationDetail(
            final HistoryRelationChange relation, final HistoryAction.DetailLevel level) {
        final HistoryChange change = relationChange(relation);
        return new HistoryEntryDetail( "membership", level, HistoryOrigin.hostUnattributed(), List.of(childTarget()),
                List.of(change), Optional.empty(),
                level == HistoryAction.DetailLevel.FULL ? Optional.empty() : Optional.of("fixture.partial"));
    }

    public static HistoryTarget childTarget() {
        return new HistoryTarget("ART_MESH", Optional.of("child-1"), Optional.of("Child"));
    }

    public static WindowsHistoryManagerValidationProbe.SdkHistorySnapshot sdkHistory(
            final List<WindowsHistoryManagerValidationProbe.SdkEntry> entries, final int position) {
        return new WindowsHistoryManagerValidationProbe.SdkHistorySnapshot( "AVAILABLE", 7L, 1L, position, position > 0,
                false, "document-1", "manager-1", entries.size(), List.copyOf(entries));
    }

    public static WindowsHistoryManagerValidationProbe.SdkEntry sdkEntry(
            final int index, final String entryId, final HistoryEntryDetail detail) {
        return new WindowsHistoryManagerValidationProbe.SdkEntry( index, entryId, "entry-" + index,
                WindowsHistoryManagerValidationProbe.sdkDetailJson(detail, 0), detail);
    }

    public static WindowsHistoryManagerValidationProbe.SdkEntry sdkEntry(
            final int index, final String entryId, final String label, final String detailJson) {
        return new WindowsHistoryManagerValidationProbe.SdkEntry(index, entryId, label, detailJson);
    }

    public static Parameters mockParameters(final Parameter... parameters) {
        final List<Parameter> values = List.of(parameters);
        return new Parameters() {
            @Override
            public List<Parameter> all() { return values; }

            @Override
            public Parameter find(final ParameterId id) {
                return values.stream().filter(parameter -> parameter.id().equals(id)).findFirst()
                        .orElseThrow(() -> new java.util.NoSuchElementException(id.value()));
            }
        };
    }

    public static Parameter mockParameter( final String id, final String name, final ParameterType type,
            final Boolean repeat, final Boolean combined, final float value) {
        return new Parameter() {
            @Override
            public ParameterId id() { return new ParameterId(id); }

            @Override
            public Optional<String> name() { return Optional.ofNullable(name); }

            @Override
            public ParameterType type() { return type; }

            @Override
            public Optional<Boolean> repeat() { return Optional.ofNullable(repeat); }

            @Override
            public Optional<Boolean> combined() { return Optional.ofNullable(combined); }

            @Override
            public float getValue() { return value; }

            @Override
            public float getMinimumValue() { return -30.0F; }

            @Override
            public float getMaximumValue() { return 30.0F; }

            @Override
            public float getDefaultValue() { return 0.0F; }

            @Override
            public void setValue(final float ignored) {}
        };
    }

    public static dev.turboism.sdk.cubism.CubismFacade fakeCubism( final boolean hostPresent,
            final Optional<dev.turboism.sdk.cubism.DocumentSnapshot> document,
            final Optional<dev.turboism.sdk.cubism.ModelSnapshot> model) {
        return new dev.turboism.sdk.cubism.CubismFacade() {
            @Override
            public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() { return Optional.empty(); }

            @Override
            public Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() { return document; }

            @Override
            public Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() { return model; }

            @Override
            public boolean isHostPresent() { return hostPresent; }
        };
    }

    public static ParameterGroup mockParameterGroup(final String id, final UiColor[] color, final boolean available) {
        final ParameterGroupId groupId = new ParameterGroupId(id);
        return new ParameterGroup() {
            @Override
            public ParameterGroupId id() { return groupId; }

            @Override
            public Optional<String> name() { return Optional.of(id); }

            @Override
            public Optional<ParameterGroupId> parentId() { return Optional.empty(); }

            @Override
            public List<ParameterGroupId> childGroupIds() { return List.of(); }

            @Override
            public List<ParameterId> parameterIds() { return List.of(); }

            @Override
            public ParameterGroupAppearance ui() {
                if (!available) return ParameterGroupAppearance.unavailable();
                return new ParameterGroupAppearance() {
                    @Override
                    public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> parameterPaletteEntry() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<NativeLabelColorState> nativeLabelColor() {
                        return Optional.of(new NativeLabelColorState(
                                new NativeLabelColor.Custom(color[0]), Optional.of(color[0])));
                    }

                    @Override
                    public void setNativeLabelColor(final NativeLabelColor value) {
                        if (!(value instanceof NativeLabelColor.Custom custom)) {
                            throw new IllegalArgumentException("test fixture expects Custom");
                        }
                        color[0] = custom.color();
                    }
                };
            }
        };
    }
}
