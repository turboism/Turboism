package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TurboismAcpPluginLifecycleTest {

    @Test
    void agentWindowAutoConnectsOnlyForTheAgentTarget() {
        assertEquals(true, TurboismAcpPlugin.shouldAutoConnect(true));
        assertEquals(false, TurboismAcpPlugin.shouldAutoConnect(false));
    }

    @Test
    void firstOpenStartsAutoConnectBeforeClaimingWindowFocus() {
        final java.util.ArrayList<String> order = new java.util.ArrayList<>();

        TurboismAcpPlugin.presentAgentWindow(() -> order.add("connect"), () -> order.add("focus"));

        assertEquals(List.of("connect", "focus"), order);
    }

    @Test
    void reopeningWithoutAutoConnectStillClaimsWindowFocus() {
        final AtomicInteger focused = new AtomicInteger();

        TurboismAcpPlugin.presentAgentWindow(null, focused::incrementAndGet);

        assertEquals(1, focused.get());
    }

    @Test
    void settingsOpenClaimsFocusWithoutStartingConnectionWork() {
        final AtomicInteger focused = new AtomicInteger();
        final AtomicInteger connections = new AtomicInteger();

        TurboismAcpPlugin.presentSettingsWindow(focused::incrementAndGet);

        assertEquals(1, focused.get());
        assertEquals(0, connections.get());
    }

    @Test
    void queuedWindowOpenReturnsSafelyAfterDisableClosesSettings() throws Exception {
        final ScopeAwareConfig config = new ScopeAwareConfig();
        final DisposableScope runtimeScope = new DisposableScope();
        final AtomicInteger actions = new AtomicInteger();
        final AtomicInteger menus = new AtomicInteger();
        final AtomicInteger toolbar = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final TurboismAcpPlugin plugin = new TurboismAcpPlugin(() -> {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("window-open test latch timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        plugin.init(context(config, runtimeScope, actions, menus, toolbar));
        plugin.enable();
        final java.lang.reflect.Method showWindow = plugin.getClass()
                .getDeclaredMethod(
                        "showWindow",
                        Class.forName("dev.turboism.plugin.acp.TurboismAcpPlugin$WindowTarget"),
                        long.class);
        showWindow.setAccessible(true);
        final Object agentTarget = java.util.Arrays.stream(showWindow.getParameterTypes()[0].getEnumConstants())
                .filter(value -> "AGENT".equals(value.toString()))
                .findFirst()
                .orElseThrow();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread opener = new Thread(
                () -> {
                    try {
                        showWindow.invoke(plugin, agentTarget, 0L);
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                },
                "acp-window-open-race");
        opener.start();
        assertEquals(true, entered.await(2, TimeUnit.SECONDS));

        plugin.disable();
        release.countDown();
        opener.join(2000L);

        assertEquals(false, opener.isAlive());
        assertEquals(null, failure.get());
        assertEquals(0, config.activeReadScopes());
        assertEquals(0, config.activeWriteScopes());
        plugin.shutdown();
    }

    @Test
    void recreatesConfigScopesAfterDisableAndRuntimeScopeClosure() throws Exception {
        final ScopeAwareConfig config = new ScopeAwareConfig();
        final DisposableScope runtimeScope = new DisposableScope();
        final AtomicInteger actions = new AtomicInteger();
        final AtomicInteger menus = new AtomicInteger();
        final AtomicInteger toolbar = new AtomicInteger();
        final TurboismAcpPlugin plugin = new TurboismAcpPlugin();
        plugin.init(context(config, runtimeScope, actions, menus, toolbar));

        plugin.enable();
        final AcpPluginSettings first = settings(plugin);
        assertEquals(true, first.writeUserSettings("custom", "first-agent", "Persist these user instructions."));
        first.writeSessionId("session-1");
        assertEquals("custom", first.agentId());
        assertEquals("first-agent", first.customCommand());
        assertEquals("session-1", first.sessionId());
        assertEquals("Persist these user instructions.", first.initialPrompt().orElse(""));
        assertEquals(1, config.activeReadScopes());
        assertEquals(1, config.activeWriteScopes());
        assertEquals(2, actions.get());
        assertEquals(1, menus.get());
        assertEquals(1, toolbar.get());

        plugin.disable();
        runtimeScope.close();
        assertEquals(0, config.activeReadScopes());
        assertEquals(0, config.activeWriteScopes());
        assertEquals(0, actions.get());
        assertEquals(0, menus.get());
        assertEquals(0, toolbar.get());
        assertEquals("first-agent", first.customCommand());

        plugin.enable();
        final AcpPluginSettings second = settings(plugin);
        assertNotSame(first, second);
        assertEquals("custom", second.agentId());
        assertEquals("first-agent", second.customCommand());
        assertEquals("session-1", second.sessionId());
        assertEquals("Persist these user instructions.", second.initialPrompt().orElse(""));
        assertEquals(true, second.writeUserSettings("custom", "second-agent", ""));
        assertEquals("second-agent", second.customCommand());
        assertEquals(1, config.activeReadScopes());
        assertEquals(1, config.activeWriteScopes());
        assertEquals(2, actions.get());
        assertEquals(1, menus.get());
        assertEquals(1, toolbar.get());

        plugin.shutdown();
        assertEquals(0, config.activeReadScopes());
        assertEquals(0, config.activeWriteScopes());
        assertEquals(0, actions.get());
        assertEquals(0, menus.get());
        assertEquals(0, toolbar.get());
    }

    @Test
    void toolbarFailureDuringEnableRollsBackActionsAndMenusAndSettings() throws Exception {
        final ScopeAwareConfig config = new ScopeAwareConfig();
        final DisposableScope runtimeScope = new DisposableScope();
        final AtomicInteger actions = new AtomicInteger();
        final AtomicInteger menus = new AtomicInteger();
        final TurboismAcpPlugin plugin = new TurboismAcpPlugin();
        final PluginContext context = contextWithFailingToolbar(config, runtimeScope, actions, menus);
        plugin.init(context);

        assertThrows(IllegalStateException.class, plugin::enable);
        assertEquals(0, actions.get(), "actions must be rolled back on enable failure");
        assertEquals(0, menus.get(), "menus must be rolled back on enable failure");
        assertEquals(0, config.activeReadScopes(), "config scopes must be closed on enable failure");
        assertEquals(0, config.activeWriteScopes());
    }

    @Test
    void disableClosesRegistrationsInReverseOrder() throws Exception {
        final ScopeAwareConfig config = new ScopeAwareConfig();
        final DisposableScope runtimeScope = new DisposableScope();
        final List<String> closeEvents = new ArrayList<>();
        final TurboismAcpPlugin plugin = new TurboismAcpPlugin();
        final PluginContext context = contextWithOrderRecording(config, runtimeScope, closeEvents);
        plugin.init(context);
        plugin.enable();

        plugin.disable();

        assertEquals(
                List.of("close-toolbar", "close-menu", "close-action-settings", "close-action-agent"), closeEvents);
        plugin.shutdown();
    }

    @Test
    void shutdownKeepsFatalFailurePrimaryWhenSettingsCleanupAlsoFails() {
        final ScopeAwareConfig config = new ScopeAwareConfig();
        config.readCloseFailure = new IllegalStateException("settings close failed");
        final AtomicInteger actions = new AtomicInteger();
        final AtomicInteger menus = new AtomicInteger();
        final AtomicInteger toolbar = new AtomicInteger();
        final TurboismAcpPlugin plugin = new TurboismAcpPlugin();
        plugin.init(context(config, new DisposableScope(), actions, menus, toolbar));
        plugin.enable();
        final VirtualMachineError fatal = new VirtualMachineError("fatal registration cleanup") {};
        plugin.disposalScope().register(() -> {
            throw fatal;
        });

        assertSame(fatal, assertThrows(VirtualMachineError.class, plugin::shutdown));
        assertTrue(java.util.Arrays.asList(fatal.getSuppressed()).contains(config.readCloseFailure));
        assertThrows(IllegalStateException.class, plugin::context);
        assertEquals(0, actions.get());
        assertEquals(0, menus.get());
        assertEquals(0, toolbar.get());
        assertEquals(0, config.activeReadScopes());
        assertEquals(0, config.activeWriteScopes());
    }

    @Test
    void queuedSettingsOpenAndDeferredCleanupStayInTheirOwnGeneration() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        final var scheduler = new TestUiScheduler();
        scheduler.queueOpens = true;
        final var scope = new DisposableScope();
        final var base =
                context(new ScopeAwareConfig(), scope, new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        final var plugin = new TurboismAcpPlugin();
        plugin.init(withScheduler(base, scheduler));
        plugin.enable();
        final var open = TurboismAcpPlugin.class.getDeclaredMethod("openSettingsWindow");
        open.setAccessible(true);
        open.invoke(plugin);
        assertEquals(1, scheduler.opens.size());
        plugin.disable();
        plugin.enable();
        onEdt(scheduler.opens.remove(0));
        assertEquals(null, field(plugin, "window"), "old open must not cross re-enable");
        open.invoke(plugin);
        onEdt(scheduler.opens.remove(0));
        final var old = (java.awt.Window) field(field(plugin, "window"), "settingsFrame");
        scheduler.fenced = true;
        scheduler.deferTeardown = true;
        plugin.disable();
        assertTrue(old.isDisplayable());
        assertEquals(1, scheduler.teardowns);
        scheduler.fenced = false;
        plugin.enable();
        open.invoke(plugin);
        onEdt(scheduler.opens.remove(0));
        final var fresh = (java.awt.Window) field(field(plugin, "window"), "settingsFrame");
        try {
            onEdt(scheduler.cleanups.get(0));
            onEdt(scheduler.cleanups.get(0));
            assertEquals(false, old.isDisplayable());
            assertTrue(fresh.isDisplayable());
        } finally {
            scheduler.deferTeardown = false;
            onEdt(plugin::shutdown);
            plugin.disable();
            scope.close();
        }
        assertEquals(false, fresh.isDisplayable());
        assertEquals(2, scheduler.teardowns);
    }

    private static final class TestUiScheduler implements dev.turboism.sdk.ui.UiScheduler {
        boolean fenced;
        boolean deferTeardown;
        boolean queueOpens;
        int teardowns;
        final java.util.List<Runnable> opens = new java.util.ArrayList<>();
        final java.util.List<Runnable> cleanups = new java.util.ArrayList<>();

        @Override
        public dev.turboism.sdk.plugin.Registration runOnUiThread(final Runnable work) {
            if (!fenced) {
                if (queueOpens) opens.add(work);
                else javax.swing.SwingUtilities.invokeLater(work);
            }
            return () -> {};
        }

        @Override
        public dev.turboism.sdk.plugin.Registration runOnUiThreadLater(
                final Runnable work, final java.time.Duration delay) {
            return runOnUiThread(work);
        }

        @Override
        public boolean runUiTeardown(final Runnable cleanup, final java.time.Duration timeout) {
            teardowns++;
            if (deferTeardown) {
                cleanups.add(cleanup);
                return false;
            }
            onEdt(cleanup);
            return true;
        }
    }

    private static void onEdt(final Runnable work) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) work.run();
        else {
            try {
                javax.swing.SwingUtilities.invokeAndWait(work);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static PluginContext withScheduler(final PluginContext base, final TestUiScheduler scheduler) {
        return (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(), new Class<?>[] {PluginContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("uiScheduler")) return scheduler;
                    try {
                        return method.invoke(base, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static Object field(final Object target, final String name) throws Exception {
        final var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static PluginContext context(
            final ScopeAwareConfig config,
            final DisposableScope scope,
            final AtomicInteger actions,
            final AtomicInteger menus,
            final AtomicInteger toolbar) {
        final PluginLogger logger = new PluginLogger() {
            @Override
            public void debug(final String message) {}

            @Override
            public void info(final String message) {}

            @Override
            public void warn(final String message) {}

            @Override
            public void error(final String message) {}

            @Override
            public void error(final String message, final Throwable throwable) {}
        };
        final PluginLocalization localization = new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                return key;
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return key;
            }

            @Override
            public boolean contains(final String key) {
                return true;
            }
        };
        final ActionRegistry actionRegistry = (id, action) -> {
            assertEquals(id, action.id());
            return registration(actions);
        };
        final MenuRegistry menuRegistry = contribution -> {
            assertEquals("Turboism/menu.acp-settings", contribution.menuPath());
            assertEquals(TurboismAcpPlugin.SETTINGS_ACTION_ID, contribution.actionId());
            assertEquals(42, contribution.order());
            return registration(menus);
        };
        final MainToolbarRegistry toolbarRegistry = new MainToolbarRegistry() {
            @Override
            public Registration contribute(final MainToolbarContribution contribution) {
                throw new AssertionError("production plugin must use typed toolbar contribution");
            }

            @Override
            public Registration contributeButton(final MainToolbarButtonContribution contribution) {
                assertEquals(TurboismAcpPlugin.TOOLBAR_CONTRIBUTION_ID, contribution.contributionId());
                assertEquals(TurboismAcpPlugin.OPEN_ACTION_ID, contribution.actionId());
                assertEquals("icons/main-toolbar-acp.png", contribution.icons().normal());
                assertEquals(
                        Optional.of("icons/main-toolbar-acp-hover.png"),
                        contribution.icons().hover());
                assertEquals(
                        MainToolbarRegistry.Position.AFTER,
                        contribution.placement().position());
                assertEquals(
                        Optional.of(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY),
                        contribution.placement().anchor());
                assertEquals(11, contribution.order());
                return registration(toolbar);
            }
        };
        return (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "uiScheduler" -> new dev.turboism.test.ui.FakeDirectUiScheduler();
                    case "logger" -> logger;
                    case "localization" -> localization;
                    case "config" -> config;
                    case "paths" -> paths();
                    case "actions" -> actionRegistry;
                    case "menus" -> menuRegistry;
                    case "disposableScope" -> scope;
                    case "toString" -> "TurboismAcpPluginLifecycleTestContext";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                    case "services" ->
                        dev.turboism.sdk.plugin.PluginServices.builder()
                                .install(dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.class, toolbarRegistry)
                                .fallback(dev.turboism.sdk.plugin.PluginServices.of((PluginContext) proxy))
                                .build();
                    default ->
                        throw new UnsupportedOperationException("unused PluginContext method: " + method.getName());
                });
    }

    private static PluginContext contextWithFailingToolbar(
            final ScopeAwareConfig config,
            final DisposableScope scope,
            final AtomicInteger actions,
            final AtomicInteger menus) {
        final PluginContext base = context(config, scope, actions, menus, new AtomicInteger());
        final MainToolbarRegistry failingToolbar = new MainToolbarRegistry() {
            @Override
            public Registration contribute(final MainToolbarContribution contribution) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Registration contributeButton(final MainToolbarButtonContribution contribution) {
                throw new IllegalStateException("fixture toolbar failure");
            }
        };
        return (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "services" ->
                        dev.turboism.sdk.plugin.PluginServices.builder()
                                .install(dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.class, failingToolbar)
                                .fallback(base.services())
                                .build();
                    default -> method.invoke(base, arguments);
                });
    }

    private static PluginContext contextWithOrderRecording(
            final ScopeAwareConfig config, final DisposableScope scope, final List<String> closeEvents) {
        final ActionRegistry actionRegistry = (id, action) -> {
            final String label =
                    TurboismAcpPlugin.SETTINGS_ACTION_ID.equals(id) ? "close-action-settings" : "close-action-agent";
            final AtomicBoolean closed = new AtomicBoolean();
            return () -> {
                if (closed.compareAndSet(false, true)) {
                    closeEvents.add(label);
                }
            };
        };
        final MenuRegistry menuRegistry = contribution -> {
            final AtomicBoolean closed = new AtomicBoolean();
            return () -> {
                if (closed.compareAndSet(false, true)) {
                    closeEvents.add("close-menu");
                }
            };
        };
        final MainToolbarRegistry toolbarRegistry = new MainToolbarRegistry() {
            @Override
            public Registration contribute(final MainToolbarContribution contribution) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Registration contributeButton(final MainToolbarButtonContribution contribution) {
                final AtomicBoolean closed = new AtomicBoolean();
                return () -> {
                    if (closed.compareAndSet(false, true)) {
                        closeEvents.add("close-toolbar");
                    }
                };
            }
        };
        final PluginContext base =
                context(config, scope, new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        return (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "actions" -> actionRegistry;
                    case "menus" -> menuRegistry;
                    case "services" ->
                        dev.turboism.sdk.plugin.PluginServices.builder()
                                .install(dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.class, toolbarRegistry)
                                .fallback(base.services())
                                .build();
                    default -> method.invoke(base, arguments);
                });
    }

    private static dev.turboism.sdk.plugin.PluginPaths paths() {
        final java.nio.file.Path root = java.nio.file.Path.of(
                        System.getProperty("java.io.tmpdir"), "turboism-acp-lifecycle-test")
                .toAbsolutePath()
                .normalize();
        return new dev.turboism.sdk.plugin.PluginPaths() {
            @Override
            public java.nio.file.Path configDir() {
                return root.resolve("config/dev.turboism.plugin.acp");
            }

            @Override
            public java.nio.file.Path dataDir() {
                return root.resolve("data/dev.turboism.plugin.acp");
            }

            @Override
            public java.nio.file.Path logsDir() {
                return root.resolve("logs/dev.turboism.plugin.acp");
            }

            @Override
            public java.nio.file.Path stateDir() {
                return root.resolve("state/dev.turboism.plugin.acp");
            }

            @Override
            public java.nio.file.Path cacheDir() {
                return root.resolve("cache/dev.turboism.plugin.acp");
            }
        };
    }

    private static Registration registration(final AtomicInteger count) {
        count.incrementAndGet();
        final AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) count.decrementAndGet();
        };
    }

    private static AcpPluginSettings settings(final TurboismAcpPlugin plugin) throws ReflectiveOperationException {
        final java.lang.reflect.Field field = plugin.getClass().getDeclaredField("settings");
        field.setAccessible(true);
        return (AcpPluginSettings) field.get(plugin);
    }

    private static final class ScopeAwareConfig implements PluginConfigRegistry {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final AtomicInteger readScopes = new AtomicInteger();
        private final AtomicInteger writeScopes = new AtomicInteger();
        private RuntimeException readCloseFailure;

        @Override
        public Registration readScope(final String relativePath) {
            final Registration scope = registration(readScopes);
            return () -> {
                scope.close();
                if (readCloseFailure != null) throw readCloseFailure;
            };
        }

        @Override
        public Registration writeScope(final String relativePath) {
            return registration(writeScopes);
        }

        @Override
        public Optional<String> readString(final String relativePath, final String key) {
            requireActive(readScopes, "read");
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void writeString(final String relativePath, final String key, final String value) {
            requireActive(writeScopes, "write");
            values.put(key, value);
        }

        @Override
        public CompletionStage<Void> registerSchema(final ConfigSchema schema, final List<ConfigMigration> migrations) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> CompletionStage<ConfigWriteResult> write(
                final ConfigKey<T> key, final T value, final long expectedRevision) {
            throw new UnsupportedOperationException("not used");
        }

        private int activeReadScopes() {
            return readScopes.get();
        }

        private int activeWriteScopes() {
            return writeScopes.get();
        }

        private static void requireActive(final AtomicInteger scopes, final String operation) {
            if (scopes.get() == 0) {
                throw new IllegalStateException("Config " + operation + " scope is not registered");
            }
        }
    }
}
