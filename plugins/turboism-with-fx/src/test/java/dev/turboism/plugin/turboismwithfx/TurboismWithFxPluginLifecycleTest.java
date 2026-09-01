package dev.turboism.plugin.turboismwithfx;

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
import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class TurboismWithFxPluginLifecycleTest {

    @Test
    void agentWindowAutoConnectDoesNotDependOnProviderOrCompatibilitySetup() {
        assertEquals(true, TurboismWithFxPlugin.shouldAutoConnect(true));
        assertEquals(true, TurboismWithFxPlugin.shouldAutoConnect(false));
        assertEquals(true, TurboismWithFxPlugin.shouldAutoConnect(true, true));
        assertEquals(true, TurboismWithFxPlugin.shouldAutoConnect(true, false));
        assertEquals(false, TurboismWithFxPlugin.shouldAutoConnect(false, true));
        assertEquals(false, TurboismWithFxPlugin.shouldAutoConnect(false, false));
    }

    @Test
    void firstOpenStartsAutoConnectBeforeClaimingWindowFocus() {
        final java.util.ArrayList<String> order = new java.util.ArrayList<>();

        TurboismWithFxPlugin.presentAgentWindow(
            () -> order.add("connect"),
            () -> order.add("focus")
        );

        assertEquals(List.of("connect", "focus"), order);
    }

    @Test
    void reopeningWithoutAutoConnectStillClaimsWindowFocus() {
        final AtomicInteger focused = new AtomicInteger();

        TurboismWithFxPlugin.presentAgentWindow(null, focused::incrementAndGet);

        assertEquals(1, focused.get());
    }

    @Test
    void settingsOpenClaimsFocusWithoutStartingConnectionWork() {
        final AtomicInteger focused = new AtomicInteger();
        final AtomicInteger connections = new AtomicInteger();

        TurboismWithFxPlugin.presentSettingsWindow(focused::incrementAndGet);

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
        final TurboismWithFxPlugin plugin = new TurboismWithFxPlugin(() -> {
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
        final java.lang.reflect.Method showWindow = plugin.getClass().getDeclaredMethod(
            "showWindow",
            Class.forName(
                "dev.turboism.plugin.turboismwithfx.TurboismWithFxPlugin$WindowTarget"
            )
        );
        showWindow.setAccessible(true);
        final Object agentTarget = java.util.Arrays.stream(
            showWindow.getParameterTypes()[0].getEnumConstants()
        ).filter(value -> "AGENT".equals(value.toString())).findFirst().orElseThrow();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread opener = new Thread(() -> {
            try {
                showWindow.invoke(plugin, agentTarget);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "fx-window-open-race");
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
        final TurboismWithFxPlugin plugin = new TurboismWithFxPlugin();
        plugin.init(context(config, runtimeScope, actions, menus, toolbar));

        plugin.enable();
        final FxPluginSettings first = settings(plugin);
        first.writeExecutable("first-fx");
        first.writeSessionId("session-1");
        first.writeCompatibilityMode(true);
        first.writeInitialPrompt("Persist these user instructions.");
        assertEquals("first-fx", first.executable());
        assertEquals("session-1", first.sessionId());
        assertEquals(true, first.compatibilityMode());
        assertEquals("Persist these user instructions.", first.initialPrompt());
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
        assertEquals("first-fx", first.executable());

        plugin.enable();
        final FxPluginSettings second = settings(plugin);
        assertNotSame(first, second);
        assertEquals("first-fx", second.executable());
        assertEquals("session-1", second.sessionId());
        assertEquals(true, second.compatibilityMode());
        assertEquals("Persist these user instructions.", second.initialPrompt());
        second.writeExecutable("second-fx");
        assertEquals("second-fx", second.executable());
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

    private static PluginContext context(
        final ScopeAwareConfig config,
        final DisposableScope scope,
        final AtomicInteger actions,
        final AtomicInteger menus,
        final AtomicInteger toolbar
    ) {
        final PluginLogger logger = new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) { }
            @Override public void warn(final String message) { }
            @Override public void error(final String message) { }
            @Override public void error(final String message, final Throwable throwable) { }
        };
        final PluginLocalization localization = new PluginLocalization() {
            @Override public Locale locale() { return Locale.ENGLISH; }
            @Override public String text(final String key) { return key; }
            @Override public String format(final String key, final Object... arguments) { return key; }
            @Override public boolean contains(final String key) { return true; }
        };
        final ActionRegistry actionRegistry = (id, action) -> {
            assertEquals(id, action.id());
            return registration(actions);
        };
        final MenuRegistry menuRegistry = contribution -> {
            assertEquals("Turboism/menu.fx-settings", contribution.menuPath());
            assertEquals(TurboismWithFxPlugin.SETTINGS_ACTION_ID, contribution.actionId());
            assertEquals(42, contribution.order());
            return registration(menus);
        };
        final MainToolbarRegistry toolbarRegistry = new MainToolbarRegistry() {
            @Override
            public Registration contribute(
                final MainToolbarContribution contribution
            ) {
                throw new AssertionError("production plugin must use typed toolbar contribution");
            }

            @Override
            public Registration contributeButton(
                final MainToolbarButtonContribution contribution
            ) {
                assertEquals(TurboismWithFxPlugin.TOOLBAR_CONTRIBUTION_ID,
                    contribution.contributionId());
                assertEquals(TurboismWithFxPlugin.OPEN_ACTION_ID, contribution.actionId());
                assertEquals("icons/main-toolbar-fx.png", contribution.icons().normal());
                assertEquals(
                    Optional.of("icons/main-toolbar-fx-hover.png"),
                    contribution.icons().hover()
                );
                assertEquals(MainToolbarRegistry.Position.AFTER,
                    contribution.placement().position());
                assertEquals(
                    Optional.of(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY),
                    contribution.placement().anchor()
                );
                assertEquals(11, contribution.order());
                return registration(toolbar);
            }
        };
        return (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[] {PluginContext.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "logger" -> logger;
                case "localization" -> localization;
                case "config" -> config;
                case "paths" -> paths();
                case "actions" -> actionRegistry;
                case "mainToolbar" -> toolbarRegistry;
                case "menus" -> menuRegistry;
                case "disposableScope" -> scope;
                case "toString" -> "TurboismWithFxPluginLifecycleTestContext";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                default -> throw new UnsupportedOperationException(
                    "unused PluginContext method: " + method.getName()
                );
            }
        );
    }

    private static dev.turboism.sdk.plugin.PluginPaths paths() {
        final java.nio.file.Path root = java.nio.file.Path.of(
            System.getProperty("java.io.tmpdir"),
            "turboism-with-fx-lifecycle-test"
        ).toAbsolutePath().normalize();
        return new dev.turboism.sdk.plugin.PluginPaths() {
            @Override public java.nio.file.Path configDir() {
                return root.resolve("config/dev.turboism.plugin.turboism-with-fx");
            }
            @Override public java.nio.file.Path dataDir() {
                return root.resolve("data/dev.turboism.plugin.turboism-with-fx");
            }
            @Override public java.nio.file.Path logsDir() {
                return root.resolve("logs/dev.turboism.plugin.turboism-with-fx");
            }
            @Override public java.nio.file.Path stateDir() {
                return root.resolve("state/dev.turboism.plugin.turboism-with-fx");
            }
            @Override public java.nio.file.Path cacheDir() {
                return root.resolve("cache/dev.turboism.plugin.turboism-with-fx");
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

    private static FxPluginSettings settings(final TurboismWithFxPlugin plugin)
        throws ReflectiveOperationException {
        final java.lang.reflect.Field field = plugin.getClass().getDeclaredField("settings");
        field.setAccessible(true);
        return (FxPluginSettings) field.get(plugin);
    }

    private static final class ScopeAwareConfig implements PluginConfigRegistry {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final AtomicInteger readScopes = new AtomicInteger();
        private final AtomicInteger writeScopes = new AtomicInteger();

        @Override
        public Registration readScope(final String relativePath) {
            return registration(readScopes);
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
        public void writeString(
            final String relativePath,
            final String key,
            final String value
        ) {
            requireActive(writeScopes, "write");
            values.put(key, value);
        }

        @Override
        public CompletionStage<Void> registerSchema(
            final ConfigSchema schema,
            final List<ConfigMigration> migrations
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> CompletionStage<ConfigWriteResult> write(
            final ConfigKey<T> key,
            final T value,
            final long expectedRevision
        ) {
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
