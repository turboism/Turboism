package dev.turboism.plugin.physicseditor;

import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.cubism.preview.PreviewFrameService;
import dev.turboism.sdk.cubism.preview.PreviewFrameSource;
import dev.turboism.sdk.plugin.AbstractTurboismPlugin;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import java.time.Duration;
import java.util.Optional;

/**
 * First-party workflow policy for the native Physics Settings group list and preview GIF recording.
 *
 * <p>The group select-all / reopen retention contribution stays a declarative registration. Preview
 * GIF recording is this plugin's own feature: it owns the state machine, the encoder, the export
 * dialog, the file and Gifsicle policy, its control strip and its translations, and reaches the host
 * only through the framework's {@link PreviewFrameService} capability.</p>
 *
 * <p>The preview surface is resolved by polling the capability, because a plugin cannot attach to a
 * preview that does not exist yet. The poll costs one focus probe on the host UI thread and does no
 * model read; while a preview is bound it only re-checks the surface identity.</p>
 */
public final class PhysicsEditorPlugin extends AbstractTurboismPlugin {

    private static final Duration PREVIEW_POLL_INTERVAL = Duration.ofMillis(400);

    /** One status-region slot, so a later export replaces the previous notice instead of stacking. */
    private static final String STATUS_NOTIFICATION_ID = "physics-editor.gif.export";

    private boolean enabled;
    private UiScheduler uiScheduler;
    private PreviewFrameService previewFrames;
    private GifRecordingController recording;
    private PreviewFrameSource bound;
    private Registration poll;

    @Override
    public synchronized void enable() {
        super.enable();
        if (enabled) return;
        final PluginContext active = context();
        uiScheduler = active.uiScheduler();
        previewFrames = active.services().find(PreviewFrameService.class).orElseGet(PreviewFrameService::unavailable);
        // Reporting the finished export is optional: a context without a status region must still
        // record and export, so a missing capability only drops the notice.
        final UiHostCapabilityService hostUi =
                active.services().find(UiHostCapabilityService.class).orElse(null);
        recording =
                new GifRecordingController(active.localization(), active.logger(), uiScheduler, (severity, message) -> {
                    if (hostUi != null) {
                        hostUi.notifyStatus(new StatusNotification(STATUS_NOTIFICATION_ID, severity, message));
                    }
                });
        disposalScope()
                .register(active.services()
                        .require(PhysicsEditorService.class)
                        .contribute(new PhysicsEditorContribution(true, true)));
        disposalScope().register(recording);
        registerGifAction(
                "record-pause", "Record / pause / resume GIF", "F7", GifRecordingController.Command.RECORD_PAUSE);
        registerGifAction("pause", "Pause GIF recording", "Shift+F7", GifRecordingController.Command.PAUSE);
        registerGifAction("stop", "Stop and export GIF", "F8", GifRecordingController.Command.STOP);
        registerGifAction("discard", "Discard GIF recording", "F9", GifRecordingController.Command.DISCARD);
        enabled = true;
        schedulePreviewPoll();
        active.logger().info("Physics editor header select-all, reopen retention and preview GIF recording enabled");
    }

    @Override
    public synchronized void disable() throws Exception {
        if (!enabled) {
            enabled = false;
            super.disable();
            return;
        }
        enabled = false;
        final Registration active = poll;
        poll = null;
        if (active != null) active.close();
        final GifRecordingController controller = recording;
        recording = null;
        if (controller != null) controller.close();
        bound = null;
        previewFrames = null;
        super.disable();
    }

    @Override
    public synchronized void shutdown() throws Exception {
        enabled = false;
        poll = null;
        recording = null;
        bound = null;
        previewFrames = null;
        uiScheduler = null;
        super.shutdown();
    }

    private void registerGifAction(
            final String name,
            final String fallback,
            final String shortcut,
            final GifRecordingController.Command command) {
        final String id = "physics-editor.gif." + name;
        disposalScope()
                .register(context()
                        .actions()
                        .register(
                                id,
                                dev.turboism.sdk.action.ActionRegistry.Action.of(
                                        id,
                                        context().localization().text("gif." + name, fallback),
                                        shortcut,
                                        ignored -> dispatch(command))));
    }

    private void dispatch(final GifRecordingController.Command command) {
        final GifRecordingController controller = recording;
        if (controller == null || !enabled) return;
        if (bound == null) {
            // A shortcut with no bound preview still starts a recording when one is open; the
            // controller reports "no preview" through the strip otherwise.
            pollPreview();
        }
        controller.command(command);
    }

    /** Reschedules the preview poll on the host UI thread. */
    private void schedulePreviewPoll() {
        final UiScheduler scheduler = uiScheduler;
        if (scheduler == null) return;
        poll = scheduler.runOnUiThreadLater(
                () -> {
                    pollPreview();
                    if (enabled) schedulePreviewPoll();
                },
                PREVIEW_POLL_INTERVAL);
    }

    /**
     * One poll tick: resolve the focused preview, bind or release the control strip.
     *
     * <p>Package-private so the lifecycle tests can drive one tick deterministically instead of
     * waiting for the scheduler.</p>
     */
    void pollPreview() {
        final GifRecordingController controller = recording;
        final PreviewFrameService service = previewFrames;
        if (!enabled || controller == null) return;
        Optional<PreviewFrameSource> focused;
        try {
            focused = service == null ? Optional.empty() : service.focused();
        } catch (RuntimeException unavailable) {
            // A refused capability (missing permission, fenced generation) resolves nothing. The
            // poll runs every few hundred milliseconds, so it must not log on the failure path.
            focused = Optional.empty();
        }
        final PreviewFrameSource next = focused.orElse(null);
        if (next == null) {
            final PreviewFrameSource stale = bound;
            bound = null;
            if (stale != null) controller.unbind(stale);
            return;
        }
        if (next == bound) return;
        final PreviewFrameSource previous = bound;
        bound = next;
        if (previous != null) controller.unbind(previous);
        controller.bind(next);
    }

    /**
     * @return the source the strip is currently attached to, or {@code null}
     */
    PreviewFrameSource boundSource() {
        return bound;
    }

    /**
     * @return the recording controller, or {@code null} before {@code enable()}
     */
    GifRecordingController recording() {
        return recording;
    }
}
