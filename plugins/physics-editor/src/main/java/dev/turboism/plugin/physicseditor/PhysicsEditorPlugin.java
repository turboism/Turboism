package dev.turboism.plugin.physicseditor;

import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.plugin.AbstractTurboismPlugin;

/** First-party workflow policy for the native Physics Settings group list. */
public final class PhysicsEditorPlugin extends AbstractTurboismPlugin {

    @Override
    public void enable() throws Exception {
        super.enable();
        disposalScope()
                .register(context()
                        .services()
                        .require(PhysicsEditorService.class)
                        .contribute(new PhysicsEditorContribution(true, true)));
        context().logger().info("Physics editor header select-all and reopen retention enabled");
    }
}
