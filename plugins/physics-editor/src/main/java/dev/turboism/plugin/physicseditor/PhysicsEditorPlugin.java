package dev.turboism.plugin.physicseditor;

import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.plugin.AbstractTurboismPlugin;

/** First-party workflow policy for the native Physics Settings group list. */
public final class PhysicsEditorPlugin extends AbstractTurboismPlugin {

    private boolean enabled;

    @Override
    public synchronized void enable() {
        super.enable();
        if (enabled) {
            return;
        }
        disposalScope()
                .register(context()
                        .services()
                        .require(PhysicsEditorService.class)
                        .contribute(new PhysicsEditorContribution(true, true)));
        registerGifAction(
                "record-pause", "Record / pause / resume GIF", "F7", PhysicsEditorService.GifCommand.RECORD_PAUSE);
        registerGifAction("pause", "Pause GIF recording", "Shift+F7", PhysicsEditorService.GifCommand.PAUSE);
        registerGifAction("stop", "Stop and export GIF", "F8", PhysicsEditorService.GifCommand.STOP);
        registerGifAction("discard", "Discard GIF recording", "F9", PhysicsEditorService.GifCommand.DISCARD);
        enabled = true;
        context().logger().info("Physics editor header select-all and reopen retention enabled");
    }

    private void registerGifAction(
            final String name,
            final String fallback,
            final String shortcut,
            final PhysicsEditorService.GifCommand command) {
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
                                        ignored -> context()
                                                .services()
                                                .require(PhysicsEditorService.class)
                                                .gifCommand(command))));
    }

    @Override
    public synchronized void disable() throws Exception {
        if (!enabled) {
            return;
        }
        enabled = false;
        super.disable();
    }

    @Override
    public synchronized void shutdown() throws Exception {
        enabled = false;
        super.shutdown();
    }
}
