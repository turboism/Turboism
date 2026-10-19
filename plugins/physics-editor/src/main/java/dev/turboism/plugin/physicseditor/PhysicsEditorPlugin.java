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
        enabled = true;
        context().logger().info("Physics editor header select-all and reopen retention enabled");
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
