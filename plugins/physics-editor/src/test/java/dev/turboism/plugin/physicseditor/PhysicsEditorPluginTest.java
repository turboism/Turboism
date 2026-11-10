package dev.turboism.plugin.physicseditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.cubism.preview.PreviewFrame;
import dev.turboism.sdk.cubism.preview.PreviewFrameService;
import dev.turboism.sdk.cubism.preview.PreviewFrameSource;
import dev.turboism.sdk.cubism.preview.PreviewFrameSourceClosedException;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginServices;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.test.plugin.NoopPluginLogger;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * The plugin owns the GIF recording feature: the shortcuts drive its state machine, its control
 * strip is the only thing bound to a preview, and no recording command reaches the retired runtime
 * seam.
 */
class PhysicsEditorPluginTest {

    private final Map<String, dev.turboism.sdk.action.ActionRegistry.Action> actions = new HashMap<>();
    private final AtomicReference<PhysicsEditorContribution> contribution = new AtomicReference<>();
    private final AtomicBoolean revoked = new AtomicBoolean();
    private final List<PhysicsEditorService.GifCommand> runtimeCommands = new ArrayList<>();
    private final AtomicReference<PreviewFrameSource> focused = new AtomicReference<>();

    @Test
    void disablingThePluginCancelsTheRecorderAndReleasesEveryRegistration() throws Exception {
        final PhysicsEditorPlugin plugin = enabled(plugin());

        assertEquals(4, actions.size());
        assertEquals(
                "F7",
                actions.get("physics-editor.gif.record-pause").defaultShortcut().orElseThrow());
        assertEquals(
                "Shift+F7",
                actions.get("physics-editor.gif.pause").defaultShortcut().orElseThrow());
        assertEquals(
                "F8", actions.get("physics-editor.gif.stop").defaultShortcut().orElseThrow());
        assertEquals(
                "F9",
                actions.get("physics-editor.gif.discard").defaultShortcut().orElseThrow());

        plugin.disable();
        assertTrue(actions.isEmpty(), "disabling the plugin must unregister its shortcuts");
        assertNull(plugin.recording(), "disabling the plugin must release the recorder");
        assertTrue(revoked.get(), "disabling the plugin must withdraw the Physics Settings contribution");
        assertEquals(new PhysicsEditorContribution(true, true), contribution.get());
    }

    @Test
    void shortcutsDriveThePluginStateMachineInsteadOfTheRetiredRuntimeCommand() throws Exception {
        final PhysicsEditorPlugin plugin = enabled(plugin());

        for (String name : List.of("record-pause", "pause", "stop", "discard")) {
            actions.get("physics-editor.gif." + name)
                    .handler()
                    .accept(new dev.turboism.sdk.action.ActionRegistry.ActionContext() {});
        }

        assertTrue(runtimeCommands.isEmpty(), "the plugin must never fall back to PhysicsEditorService.gifCommand");
        assertEquals(
                GifRecordingController.State.IDLE,
                plugin.recording().state(),
                "a command with no focused preview leaves the plugin idle");
    }

    @Test
    void pollingBindsOneControlStripToTheFocusedPreviewAndReleasesItWhenThePreviewGoesAway() throws Exception {
        final PhysicsEditorPlugin plugin = enabled(plugin());
        final FakeSource source = new FakeSource("cubism.preview.1");
        focused.set(source);

        plugin.pollPreview();

        assertSame(source, plugin.boundSource());
        assertEquals(1, source.attachments.get(), "the strip is attached once, not once per poll");
        final List<String> actionIds = actionIds(source.content.get());
        assertTrue(actionIds.contains("record"), "the strip carries the record control: " + actionIds);
        assertTrue(actionIds.contains("stop"), "the strip carries the stop control: " + actionIds);
        assertTrue(actionIds.contains("discard"), "the strip carries the discard control: " + actionIds);
        assertTrue(actionIds.contains("fps"), "the strip carries the frame-rate control: " + actionIds);

        plugin.pollPreview();
        assertEquals(1, source.attachments.get(), "the same preview is not re-attached");

        focused.set(null);
        plugin.pollPreview();

        assertNull(plugin.boundSource());
        assertNull(source.content.get(), "the strip is withdrawn with the preview");
        assertTrue(source.closed.get(), "the released source is closed");
    }

    @Test
    void stripRecordControlStartsRecordingThroughTheCapability() throws Exception {
        final PhysicsEditorPlugin plugin = enabled(plugin());
        final FakeSource source = new FakeSource("cubism.preview.2");
        focused.set(source);
        plugin.pollPreview();

        source.action.accept("record", Optional.empty());

        await(() -> source.captures.get() > 0, "recording reads frames through PreviewFrameSource.capture()");
        // The fake surface refuses every frame, so the recording settles back to idle instead of
        // leaving the state machine stuck in RECORDING.
        await(
                () -> plugin.recording().state() == GifRecordingController.State.IDLE,
                "a failed capture returns the state machine to idle");
        assertTrue(
                statusValue(source.content.get()).isBlank(), "a finished run leaves no notice in the frame-rate line");
        plugin.disable();
    }

    /**
     * The native preview pane is about 280 logical pixels wide, so the strip must stack the
     * transport icon buttons above the frame-rate field and the status. Counter, pause and resume
     * updates only change masks; the node layout must stay identical, because a strip that grows by
     * a line shrinks the preview and the recorder aborts on the changed preview size.
     */
    @Test
    void stripStacksControlsAndKeepsItsLayoutWhileTheRecordingStatusChanges() throws Exception {
        final byte[] png = previewPng();
        final RecordingSource source = new RecordingSource(png);
        final GifRecordingController controller = new GifRecordingController(
                TestLocalization.ENGLISH, NoopPluginLogger.INSTANCE, FakeDirectUiScheduler.direct());
        try {
            controller.bind(source);
            final PanelView idle = source.content.get();
            final PanelView.Row row = assertInstanceOf(PanelView.Row.class, idle);
            assertEquals(
                    List.of("record", "stop", "discard", "fps"),
                    actionIds(row),
                    "transport buttons and the frame-rate field share one row");
            final List<PanelView.ColorIconButton> transport = iconButtons(row);
            assertTrue(
                    transport.stream().allMatch(button -> button.pngBytes().length > 0),
                    "every transport control ships its bundled Lucide glyph");
            assertTrue(transport.get(0).enabled(), "record is available while idle");
            assertFalse(transport.get(1).enabled(), "stop is unavailable until a recording exists");
            assertFalse(transport.get(2).enabled(), "discard is unavailable until a recording exists");
            final PanelView.Centered cell = statusCell(row);
            assertTrue(cell.preferredWidth() > 0, "the status line reserves a fixed cell width");
            assertTrue(cell.preferredHeight() > 0, "the status line reserves one fixed text line");

            controller.command(GifRecordingController.Command.RECORD_PAUSE);
            await(() -> statusValue(source.content.get()).startsWith("Recording"), "the counter reaches the strip");
            final PanelView recording = source.content.get();
            controller.command(GifRecordingController.Command.RECORD_PAUSE);
            await(() -> statusValue(source.content.get()).startsWith("Paused"), "pausing reaches the strip");
            final PanelView paused = source.content.get();
            controller.command(GifRecordingController.Command.RECORD_PAUSE);
            await(() -> statusValue(source.content.get()).startsWith("Recording"), "resuming reaches the strip");
            final PanelView resumed = source.content.get();

            assertEquals(layoutShape(idle), layoutShape(recording), "a counter update must not reflow the strip");
            assertEquals(layoutShape(idle), layoutShape(paused), "pausing must not reflow the strip");
            assertEquals(layoutShape(idle), layoutShape(resumed), "resuming must not reflow the strip");
            assertFalse(
                    java.util.Arrays.equals(recordMask(idle), recordMask(recording)),
                    "the record mask must show that the next press pauses");
            assertFalse(
                    java.util.Arrays.equals(recordMask(idle), recordMask(paused)),
                    "the record mask must show that the next press resumes");
            assertTrue(
                    java.util.Arrays.equals(recordMask(recording), recordMask(resumed)),
                    "resuming returns the record mask to the pause shape");
            for (int index : new int[] {1, 2}) {
                assertTrue(
                        iconButtons(row(recording)).get(index).enabled(),
                        "stop and discard accept input while a recording can be ended");
                assertTrue(
                        iconButtons(row(paused)).get(index).enabled(),
                        "stop and discard stay available while the recording is paused");
                assertTrue(
                        java.util.Arrays.equals(transportMask(recording, index), transportMask(paused, index)),
                        "stop and discard keep one glyph while a recording can be ended");
            }
            assertTrue(iconButtons(row(paused)).get(0).enabled(), "record stays available while paused");
        } finally {
            controller.command(GifRecordingController.Command.DISCARD);
            controller.close();
        }
    }

    private static PanelView.Centered statusCell(final PanelView row) {
        final PanelView.Row cells = assertInstanceOf(PanelView.Row.class, row);
        for (PanelView child : cells.children()) {
            if (child instanceof PanelView.Centered cell && cell.child() instanceof PanelView.Text) return cell;
        }
        throw new AssertionError("the status row has no reserved status cell: " + cells.children());
    }

    private static PanelView row(final PanelView view) {
        return assertInstanceOf(PanelView.Row.class, view);
    }

    private static List<PanelView.ColorIconButton> iconButtons(final PanelView row) {
        final PanelView.Row cells = assertInstanceOf(PanelView.Row.class, row);
        final List<PanelView.ColorIconButton> buttons = new ArrayList<>();
        for (PanelView child : cells.children()) {
            if (child instanceof PanelView.ColorIconButton button) buttons.add(button);
        }
        return buttons;
    }

    private static byte[] recordMask(final PanelView view) {
        return transportMask(view, 0);
    }

    private static byte[] transportMask(final PanelView view, final int index) {
        return iconButtons(row(view)).get(index).pngBytes();
    }

    private static String statusValue(final PanelView view) {
        final PanelView.Centered cell = statusCell(assertInstanceOf(PanelView.Row.class, view));
        return assertInstanceOf(PanelView.Text.class, cell.child()).value();
    }

    /** Layout identity of a strip: node kinds, fixed cell bounds and control ids, never text. */
    private static String layoutShape(final PanelView view) {
        final StringBuilder shape = new StringBuilder();
        appendShape(view, shape);
        return shape.toString();
    }

    private static void appendShape(final PanelView view, final StringBuilder shape) {
        if (view instanceof PanelView.Column column) {
            shape.append("column(");
            column.children().forEach(child -> appendShape(child, shape));
            shape.append(')');
        } else if (view instanceof PanelView.Row row) {
            shape.append("row(");
            row.children().forEach(child -> appendShape(child, shape));
            shape.append(')');
        } else if (view instanceof PanelView.Centered cell) {
            shape.append("cell(")
                    .append(cell.preferredWidth())
                    .append('x')
                    .append(cell.preferredHeight())
                    .append(',');
            appendShape(cell.child(), shape);
            shape.append(')');
        } else if (view instanceof PanelView.Button button) {
            shape.append("button:").append(button.actionId());
        } else if (view instanceof PanelView.TextInput input) {
            shape.append("input:").append(input.actionId()).append('=').append(input.value());
        } else if (view instanceof PanelView.Text) {
            shape.append("text");
        } else {
            shape.append(view.getClass().getSimpleName());
        }
    }

    private static byte[] previewPng() throws java.io.IOException {
        final java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        return png.toByteArray();
    }

    private static void await(final java.util.function.BooleanSupplier condition, final String message)
            throws InterruptedException {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError(message);
    }

    @Test
    void repeatedEnableIsIdempotentAndDoesNotContributeTwice() throws Exception {
        final AtomicInteger contributions = new AtomicInteger();
        final AtomicInteger revocations = new AtomicInteger();
        final PhysicsEditorService service = value -> {
            contributions.incrementAndGet();
            return revocations::incrementAndGet;
        };
        final PhysicsEditorPlugin plugin = new PhysicsEditorPlugin();
        plugin.init(context(service, PreviewFrameService.unavailable()));

        plugin.enable();
        plugin.enable();
        assertEquals(1, contributions.get(), "a second enable must be a no-op");
        assertEquals(0, revocations.get());

        plugin.disable();
        assertEquals(1, revocations.get(), "disable must revoke exactly once");

        plugin.enable();
        assertEquals(2, contributions.get(), "re-enable must contribute again");
        plugin.shutdown();
        assertEquals(2, revocations.get(), "shutdown must revoke the re-enabled contribution");
    }

    private PhysicsEditorPlugin enabled(final PhysicsEditorPlugin plugin) throws Exception {
        plugin.init(context(
                new PhysicsEditorService() {
                    @Override
                    public Registration contribute(final PhysicsEditorContribution value) {
                        contribution.set(value);
                        return () -> revoked.set(true);
                    }

                    @Override
                    public void gifCommand(final GifCommand command) {
                        runtimeCommands.add(command);
                    }
                },
                () -> Optional.ofNullable(focused.get())));
        plugin.enable();
        return plugin;
    }

    private PhysicsEditorPlugin plugin() {
        return new PhysicsEditorPlugin();
    }

    private PluginContext context(final PhysicsEditorService editor, final PreviewFrameService frames) {
        final UiScheduler scheduler = new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                return () -> {};
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                return () -> {};
            }
        };
        return (PluginContext) Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "logger" -> logger();
                    case "localization" -> TestLocalization.ENGLISH;
                    case "uiScheduler" -> scheduler;
                    case "actions" ->
                        (dev.turboism.sdk.action.ActionRegistry) (id, action) -> {
                            actions.put(id, action);
                            return () -> actions.remove(id);
                        };
                    case "services" ->
                        PluginServices.builder()
                                .install(PhysicsEditorService.class, editor)
                                .install(PreviewFrameService.class, frames)
                                .fallback(PluginServices.of((PluginContext) proxy))
                                .build();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static List<String> actionIds(final PanelView view) {
        final List<String> ids = new ArrayList<>();
        collect(view, ids);
        return ids;
    }

    private static void collect(final PanelView view, final List<String> ids) {
        if (view instanceof PanelView.Row row) row.children().forEach(child -> collect(child, ids));
        else if (view instanceof PanelView.Column column) column.children().forEach(child -> collect(child, ids));
        else if (view instanceof PanelView.Centered centered) collect(centered.child(), ids);
        else if (view instanceof PanelView.Button button) ids.add(button.actionId());
        else if (view instanceof PanelView.IconButton iconButton) ids.add(iconButton.actionId());
        else if (view instanceof PanelView.ColorIconButton colorIcon) ids.add(colorIcon.actionId());
        else if (view instanceof PanelView.TextInput input) ids.add(input.actionId());
        else if (view instanceof PanelView.Select select) ids.add(select.actionId());
        else if (view instanceof PanelView.Toggle toggle) ids.add(toggle.actionId());
    }

    private static PluginLogger logger() {
        return (PluginLogger) Proxy.newProxyInstance(
                PluginLogger.class.getClassLoader(),
                new Class<?>[] {PluginLogger.class},
                (proxy, method, arguments) -> null);
    }

    /** A preview source that serves readable frames, so the strip can be observed in every state. */
    private static final class RecordingSource implements PreviewFrameSource {

        private final String id = "cubism.preview.frames";
        private final byte[] png;
        private final AtomicReference<PanelView> content = new AtomicReference<>();

        private RecordingSource(final byte[] png) {
            this.png = png;
        }

        @Override
        public String surfaceId() {
            return id;
        }

        @Override
        public int width() {
            return 8;
        }

        @Override
        public int height() {
            return 8;
        }

        @Override
        public CompletionStage<PreviewFrame> capture() {
            return CompletableFuture.completedFuture(new PreviewFrame(8, 8, png));
        }

        @Override
        public Registration attachControls(
                final PanelView view, final BiConsumer<String, Optional<UiActionEvent>> actions) {
            content.set(view);
            return () -> content.set(null);
        }

        @Override
        public void updateControls(final PanelView view) {
            content.set(view);
        }

        @Override
        public void close() {}
    }

    /** A preview source the plugin can bind, edit and release without a host surface. */
    private static final class FakeSource implements PreviewFrameSource {

        private final String id;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger attachments = new AtomicInteger();
        private final AtomicInteger captures = new AtomicInteger();
        private final AtomicInteger updates = new AtomicInteger();
        private final AtomicReference<PanelView> content = new AtomicReference<>();
        private volatile BiConsumer<String, Optional<UiActionEvent>> action = (id, event) -> {};

        private FakeSource(final String id) {
            this.id = id;
        }

        @Override
        public String surfaceId() {
            return id;
        }

        @Override
        public int width() {
            return 640;
        }

        @Override
        public int height() {
            return 480;
        }

        @Override
        public CompletionStage<PreviewFrame> capture() {
            captures.incrementAndGet();
            final CompletableFuture<PreviewFrame> failed = new CompletableFuture<>();
            failed.completeExceptionally(new PreviewFrameSourceClosedException());
            return failed;
        }

        @Override
        public Registration attachControls(
                final PanelView view, final BiConsumer<String, Optional<UiActionEvent>> actions) {
            attachments.incrementAndGet();
            content.set(view);
            action = actions;
            return () -> content.set(null);
        }

        @Override
        public void updateControls(final PanelView view) {
            updates.incrementAndGet();
            if (content.get() != null) content.set(view);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
