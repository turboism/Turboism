package dev.turboism.plugin.physicseditor;

import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsEditorPluginTest {

    @Test
    void enablesTheBoundedWorkflowAndClosesItOnDisable() throws Exception {
        final AtomicReference<PhysicsEditorContribution> contribution = new AtomicReference<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final PhysicsEditorService service = value -> {
            contribution.set(value);
            return () -> closed.set(true);
        };
        final PluginContext context = (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[]{PluginContext.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "physicsEditor" -> service;
                case "logger" -> logger();
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
        final PhysicsEditorPlugin plugin = new PhysicsEditorPlugin();

        plugin.init(context);
        plugin.enable();
        plugin.disable();

        assertEquals(new PhysicsEditorContribution(true, true), contribution.get());
        assertTrue(closed.get());
    }

    private static PluginLogger logger() {
        return (PluginLogger) Proxy.newProxyInstance(
            PluginLogger.class.getClassLoader(),
            new Class<?>[]{PluginLogger.class},
            (proxy, method, arguments) -> null
        );
    }
}
