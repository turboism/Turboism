package dev.turboism.plugin.physicseditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "logger" -> logger();
                    case "services" ->
                        dev.turboism.sdk.plugin.PluginServices.builder()
                                .install(PhysicsEditorService.class, service)
                                .fallback(dev.turboism.sdk.plugin.PluginServices.of((PluginContext) proxy))
                                .build();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final PhysicsEditorPlugin plugin = new PhysicsEditorPlugin();

        plugin.init(context);
        plugin.enable();
        plugin.disable();

        assertEquals(new PhysicsEditorContribution(true, true), contribution.get());
        assertTrue(closed.get());
    }

    @Test
    void repeatedEnableIsIdempotentAndDoesNotContributeTwice() throws Exception {
        final AtomicInteger contributions = new AtomicInteger();
        final AtomicInteger revocations = new AtomicInteger();
        final PhysicsEditorService service = value -> {
            contributions.incrementAndGet();
            return revocations::incrementAndGet;
        };
        final PluginContext context = (PluginContext) Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "logger" -> logger();
                    case "services" ->
                        dev.turboism.sdk.plugin.PluginServices.builder()
                                .install(PhysicsEditorService.class, service)
                                .fallback(dev.turboism.sdk.plugin.PluginServices.of((PluginContext) proxy))
                                .build();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final PhysicsEditorPlugin plugin = new PhysicsEditorPlugin();

        plugin.init(context);
        plugin.enable();
        plugin.enable();
        assertEquals(1, contributions.get(), "second enable must be a no-op");
        assertEquals(0, revocations.get());

        plugin.disable();
        assertEquals(1, revocations.get(), "disable must revoke exactly once");

        plugin.enable();
        assertEquals(2, contributions.get(), "re-enable must contribute again");
        plugin.shutdown();
        assertEquals(2, revocations.get(), "shutdown must revoke the re-enabled contribution");
    }

    private static PluginLogger logger() {
        return (PluginLogger) Proxy.newProxyInstance(
                PluginLogger.class.getClassLoader(),
                new Class<?>[] {PluginLogger.class},
                (proxy, method, arguments) -> null);
    }
}
