package dev.turboism.plugin.backup;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-safe coverage of the target rebuild path: the init/enable race
 * (refreshTarget before the async registerSchema completes) must retry with
 * backoff and eventually build the target, and disable() must cancel the
 * pending retries. The retry-exhaustion sequence (five backoffs, ~15.5s) is
 * covered by the interactive session, not here.
 */
final class BackupPluginTest {

    private final List<BackupPlugin> plugins = new ArrayList<>();

    @AfterEach
    void shutDownPlugins() {
        for (BackupPlugin plugin : plugins) {
            plugin.shutdown();
        }
        plugins.clear();
    }

    @Test
    void refreshTargetRetriesUntilTheBindingInitializes() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin = new BackupPlugin();
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable(); // registerSchema is still pending: read() yields null
        assertTrue(context.awaitLog("WEBDAV_TARGET_RETRY attempt=1", Duration.ofSeconds(2)),
            "a not-yet-initialized binding must schedule a bounded retry");
        context.registry.completeSchema();
        assertTrue(context.awaitLog("WEBDAV_TARGET_READY", Duration.ofSeconds(4)),
            "the retry (or the init completion) must build the target once the schema is registered");
        assertTrue(context.awaitLog("WebDAV backup sync binding initialized", Duration.ofSeconds(1)));
    }

    @Test
    void disableCancelsPendingTargetRetries() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin = new BackupPlugin();
        plugins.add(plugin);
        plugin.init(context);
        plugin.enable();
        assertTrue(context.awaitLog("WEBDAV_TARGET_RETRY attempt=1", Duration.ofSeconds(2)));
        plugin.disable(); // clears the retry state; binding is disabled
        context.registry.completeSchema();
        Thread.sleep(1_200L); // long enough for the first backoff (500ms) to elapse
        assertFalse(context.hasLog("WEBDAV_TARGET_READY"),
            "a cancelled retry must never build the target");
        assertFalse(context.hasLog("WEBDAV_TARGET_RETRY attempt=2"),
            "disable must stop the retry chain");
    }

    @Test
    void enableAfterInitBuildsTheTargetWithoutRetries() throws Exception {
        FakeContext context = new FakeContext();
        BackupPlugin plugin = new BackupPlugin();
        plugins.add(plugin);
        plugin.init(context);
        context.registry.completeSchema();
        assertTrue(context.awaitLog("WebDAV backup sync binding initialized", Duration.ofSeconds(2)));
        plugin.enable();
        assertTrue(context.awaitLog("WEBDAV_TARGET_READY", Duration.ofSeconds(2)),
            "an initialized binding must build the target on enable");
        assertFalse(context.hasLog("WEBDAV_TARGET_RETRY"));
    }

    /** Recording plugin context with a gated config registry. */
    private static final class FakeContext implements PluginContext {
        final RecordingLogger logger = new RecordingLogger();
        final GatedRegistry registry = new GatedRegistry();

        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            return null;
        }

        @Override
        public CubismFacade cubism() {
            return null;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            return new EventBus() {
                @Override
                public <T extends EventBus.TurboismEvent> Registration subscribe(
                    final Class<T> type, final java.util.function.Consumer<T> listener
                ) {
                    return () -> { };
                }

                @Override
                public <T extends EventBus.TurboismEvent> void publish(final T event) {
                }
            };
        }

        @Override
        public ActionRegistry actions() {
            return new ActionRegistry() {
                @Override
                public Registration register(final String id, final Action action) {
                    return () -> { };
                }
            };
        }

        @Override
        public MenuRegistry menus() {
            return new MenuRegistry() {
                @Override
                public Registration contribute(final MenuContribution contribution) {
                    return () -> { };
                }
            };
        }

        @Override
        public PluginConfigRegistry config() {
            return registry;
        }

        @Override
        public dev.turboism.sdk.plugin.DisposableScope disposableScope() {
            return new dev.turboism.sdk.plugin.DisposableScope();
        }

        @Override
        public dev.turboism.sdk.ui.UiScheduler uiScheduler() {
            return new dev.turboism.sdk.ui.UiScheduler() {
                @Override
                public Registration runOnUiThread(final Runnable work) {
                    return () -> { };
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final java.time.Duration delay) {
                    return () -> { };
                }
            };
        }

        @Override
        public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
            return new dev.turboism.sdk.diagnostics.DiagnosticReport() {
                @Override
                public java.time.Instant createdAt() {
                    return java.time.Instant.EPOCH;
                }

                @Override
                public List<dev.turboism.sdk.diagnostics.DiagnosticReport.Problem> problems() {
                    return List.of();
                }
            };
        }

        boolean awaitLog(final String fragment, final Duration timeout) throws InterruptedException {
            final long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (hasLog(fragment)) {
                    return true;
                }
                Thread.sleep(50L);
            }
            return hasLog(fragment);
        }

        boolean hasLog(final String fragment) {
            return logger.lines.stream().anyMatch(line -> line.contains(fragment));
        }
    }

    /** Registry whose registerSchema completes only when the test says so. */
    private static final class GatedRegistry implements PluginConfigRegistry {
        private final CompletableFuture<Void> schema = new CompletableFuture<>();
        private final Map<String, Object> values = new HashMap<>();

        void completeSchema() {
            schema.complete(null);
        }

        @Override
        public CompletionStage<Void> registerSchema(
            final dev.turboism.sdk.config.ConfigSchema schema,
            final List<dev.turboism.sdk.config.ConfigMigration> migrations
        ) {
            return this.schema;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
            final T value = (T) values.getOrDefault(key.name(), key.defaultValue());
            return CompletableFuture.completedFuture(new ConfigReadResult<>(
                new ConfigValue<>(value, ConfigValueSource.STORED, 1L), Optional.empty()));
        }

        @Override
        public <T> CompletionStage<ConfigWriteResult> write(
            final ConfigKey<T> key, final T value, final long expected
        ) {
            values.put(key.name(), value);
            return CompletableFuture.completedFuture(new ConfigWriteResult(true, expected + 1, Optional.empty()));
        }

        @Override
        public Registration readScope(final String relativePath) {
            return () -> { };
        }

        @Override
        public Registration writeScope(final String relativePath) {
            return () -> { };
        }

        @Override
        public Optional<String> readString(final String relativePath, final String key) {
            return Optional.empty();
        }

        @Override
        public void writeString(final String relativePath, final String key, final String value)
            throws PluginConfigException {
            throw new PluginConfigException("not supported");
        }
    }

    private static final class RecordingLogger implements PluginLogger {
        final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public void debug(final String message) {
            lines.add(message);
        }

        @Override
        public void info(final String message) {
            lines.add(message);
        }

        @Override
        public void warn(final String message) {
            lines.add(message);
        }

        @Override
        public void error(final String message) {
            lines.add(message);
        }

        @Override
        public void error(final String message, final Throwable throwable) {
            lines.add(message);
        }
    }
}
