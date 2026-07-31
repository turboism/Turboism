package dev.turboism.plugin.physicseditor;

import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Objects;

/** First-party workflow policy for the native Physics Settings group list. */
public final class PhysicsEditorPlugin implements TurboismPlugin {
    private PluginContext context;
    private Registration registration;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void enable() {
        if (registration != null) return;
        registration = context.physicsEditor().contribute(new PhysicsEditorContribution(true, true));
        context.logger().info("Physics editor header select-all and reopen retention enabled");
    }

    @Override
    public void disable() {
        if (registration == null) return;
        registration.close();
        registration = null;
    }

    @Override
    public void shutdown() {
        disable();
        context = null;
    }
}
