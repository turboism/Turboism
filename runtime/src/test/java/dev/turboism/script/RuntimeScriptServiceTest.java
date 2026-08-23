package dev.turboism.script;

import dev.turboism.graal.GraalHostConfiguration;
import dev.turboism.graal.GraalHostManager;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.script.ScriptId;
import dev.turboism.sdk.script.ScriptRunRequest;
import dev.turboism.sdk.script.ScriptRunResult;
import dev.turboism.sdk.script.ScriptRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeScriptServiceTest {

    @TempDir
    Path temporary;

    @Test
    void maximumLengthMissingScriptIdReturnsRejectedHandle() throws Exception {
        final ScriptId missing = new ScriptId("s".repeat(128));
        try (GraalHostManager host = new GraalHostManager(
            GraalHostConfiguration.disabled(),
            ignored -> { }
        )) {
            final RuntimeScriptService service = new RuntimeScriptService(
                temporary,
                unusedContext(),
                new DisposableScope(),
                host,
                ignored -> { }
            );

            final ScriptRunResult result = service.run(
                new ScriptRunRequest(missing, Map.of())
            ).completion().toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertEquals(ScriptRunStatus.REJECTED, result.status());
            assertTrue(result.failure().isPresent());
            assertEquals("SCRIPT_NOT_FOUND", result.failure().orElseThrow().code());
            assertTrue(result.executionId().value().length() <= 128);
        }
    }

    @Test
    void sourceChangedAfterDiscoveryReturnsRejectedHandle() throws Exception {
        final Path root = temporary.resolve("scripts/source-change");
        Files.createDirectories(root);
        Files.writeString(root.resolve("main.js"), "print('before');");
        Files.writeString(root.resolve("script.json"), """
            {
              "schemaVersion": 2,
              "id": "source.change",
              "name": "Source change",
              "version": "1.0.0",
              "language": "js",
              "entry": "main.js",
              "sourceSha256": "9abe467768bc9d800bade17932132b7703b34c7d18635e43f0d2f83019e0587e",
              "permissions": []
            }
            """);
        try (GraalHostManager host = new GraalHostManager(
            GraalHostConfiguration.disabled(),
            ignored -> { }
        )) {
            final Path source = root.resolve("main.js");
            final ScriptRegistry registry = new ScriptRegistry(
                temporary,
                ignored -> { }
            );
            final ScriptRegistry.InstalledScript installed = registry.find(
                new ScriptId("source.change")
            ).orElseThrow();
            Files.writeString(source, "print('after and changed');");
            final ScriptRegistry stableRegistry = new ScriptRegistry(
                temporary,
                ignored -> { }
            ) {
                @Override
                java.util.Optional<InstalledScript> find(final ScriptId id) {
                    return id.equals(installed.descriptor().id())
                        ? java.util.Optional.of(installed)
                        : java.util.Optional.empty();
                }
            };
            final RuntimeScriptService service = new RuntimeScriptService(
                stableRegistry,
                unusedContext(),
                new DisposableScope(),
                host,
                ignored -> { }
            );
            final ScriptRunResult result = service.run(
                new ScriptRunRequest(new ScriptId("source.change"), Map.of())
            ).completion().toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertEquals(ScriptRunStatus.REJECTED, result.status());
            assertEquals(
                "SCRIPT_SOURCE_INVALID",
                result.failure().orElseThrow().code()
            );
        }
    }

    private static PluginContext unusedContext() {
        return (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[] {PluginContext.class},
            (proxy, method, arguments) -> {
                throw new AssertionError("Plugin context should not be used: " + method.getName());
            }
        );
    }
}
