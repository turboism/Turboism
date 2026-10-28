package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.CorePluginManagement.OperationResult;
import dev.turboism.pluginmanagement.PluginCatalog;
import dev.turboism.pluginmanagement.PluginMarketService;
import dev.turboism.pluginmanagement.RuntimePluginManagementService;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginMarketWindowAsyncTest {
    @TempDir
    Path home;

    @Test
    void tableAndStatisticsUseSnapshotWithoutReadingPluginsOnEdt() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        try (var management = new RuntimePluginManagementService(home, Optional::empty, () -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            reads.incrementAndGet();
            return List.of();
        })) {
            final PluginMarketService market = management.marketService();
            final var catalog =
                    new PluginCatalog(PluginCatalog.FORMAT, 2, 1, Instant.now(), "0".repeat(64), "test-key", List.of());
            final var snapshot = market.snapshot(catalog).toCompletableFuture().get(5, TimeUnit.SECONDS);
            final var window = new PluginMarketWindow(localization(), market, management, Locale.ENGLISH);
            SwingUtilities.invokeAndWait(() -> {
                final var model = window.new MarketTableModel();
                set(window, "tableModel", model);
                set(window, "pluginStatsLabel", new JLabel());
                set(window, "table", new JTable(model));
                model.setSnapshot(snapshot);
                for (int i = 0; i < 29; i++) invoke(window, "updateStatsLabel");
                assertEquals(0, model.getRowCount());
            });
            assertEquals(1, reads.get(), "rendering and filter statistics must not rescan local plugins");
        }
    }

    @Test
    void rejectedOperationRetainsErrorCodeInsteadOfClaimingRestartWillApplyIt() throws Exception {
        try (var management = new RuntimePluginManagementService(home, Optional::empty, List::of)) {
            final var window =
                    new PluginMarketWindow(localization(), management.marketService(), management, Locale.ENGLISH);
            final JLabel feedback = new JLabel();
            SwingUtilities.invokeAndWait(() -> {
                set(window, "statusLabel", feedback);
                invoke(
                        window,
                        "handleOperationResult",
                        OperationResult.class,
                        OperationResult.rejected("PLUGIN_DOWNLOAD_FAILED", "Read timed out"));
                assertTrue(feedback.getText().contains("PLUGIN_DOWNLOAD_FAILED"));
                assertTrue(feedback.getText().contains("Read timed out"));
                assertFalse(feedback.getText().contains("restart-required"));
            });
        }
    }

    private static PluginLocalization localization() {
        return new PluginLocalization() {
            public Locale locale() {
                return Locale.ENGLISH;
            }

            public String text(String key) {
                return key;
            }

            public String format(String key, Object... arguments) {
                return key + " " + java.util.Arrays.toString(arguments);
            }

            public boolean contains(String key) {
                return true;
            }
        };
    }

    private static void set(Object target, String name, Object value) {
        try {
            final Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void invoke(Object target, String name) {
        invoke(target, name, null, null);
    }

    private static void invoke(Object target, String name, Class<?> type, Object value) {
        try {
            final Method method = type == null
                    ? target.getClass().getDeclaredMethod(name)
                    : target.getClass().getDeclaredMethod(name, type);
            method.setAccessible(true);
            if (type == null) method.invoke(target);
            else method.invoke(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
