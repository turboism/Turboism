package dev.turboism.tests.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.RuntimeTypedPluginConfigRegistry;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.plugin.uitheme.service.ThemeSelectionConfig;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.ui.appearance.EarlyThemeAppearanceBootstrap;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cross-module contract tests pinning {@link ThemeSelectionConfig} (in {@code :plugins:ui-theme})
 * against {@link EarlyThemeAppearanceBootstrap} (in {@code :runtime}) over the real persisted
 * typed-config document store (`EXC-AD-001`).
 */
class EarlyThemeBootstrapInteractionTest {

    private static final String THEME_PLUGIN_ID = "dev.turboism.plugin.uitheme";
    private static final String APPEARANCE_COMPONENT = "appearance";
    private static final String CUSTOM_THEME_ID = "user.aurora";
    private static final String VIEW_BACKGROUND_KEY = "CubismCommon.gl.viewArea.background";
    private static final String VIEW_BACKGROUND_HEX = "#1E222A";
    private static final String PANEL_BACKGROUND_KEY = "Panel.background";
    private static final String PANEL_BACKGROUND_HEX = "#282C34";

    @TempDir
    Path home;

    @AfterEach
    void clearDiagnostics() {
        RuntimeDiagnostics.clear();
    }

    @Test
    void persistedUiThemeSelectionRoundTripsIntoPreStartupBootstrapAndHonorsNativeReset() throws Exception {
        writeThemeArchive(
                home,
                CUSTOM_THEME_ID,
                Map.of(
                        VIEW_BACKGROUND_KEY, VIEW_BACKGROUND_HEX,
                        PANEL_BACKGROUND_KEY, PANEL_BACKGROUND_HEX));

        withProductionSelectionConfig(home, selections -> {
            selections.saveSelectedThemeId(CUSTOM_THEME_ID);
            assertEquals(Optional.of(CUSTOM_THEME_ID), selections.selectedThemeId());
        });

        final List<String> diagnostics = captureAppearanceDiagnostics();
        final Optional<Map<String, String>> injectedColors = loadBootstrapColors(home);
        assertTrue(injectedColors.isPresent(), "bootstrap must resolve persisted custom theme colors");
        assertEquals(VIEW_BACKGROUND_HEX, injectedColors.orElseThrow().get(VIEW_BACKGROUND_KEY));
        assertEquals(PANEL_BACKGROUND_HEX, injectedColors.orElseThrow().get(PANEL_BACKGROUND_KEY));
        assertEquals(List.of(), diagnostics);

        withProductionSelectionConfig(home, selections -> {
            assertEquals(Optional.of(CUSTOM_THEME_ID), selections.selectedThemeId());
            selections.saveSelectedThemeId(ThemeSelectionConfig.NATIVE_ID);
            assertEquals(Optional.of(ThemeSelectionConfig.NATIVE_ID), selections.selectedThemeId());
        });
        assertTrue(loadBootstrapColors(home).isEmpty(), "native sentinel must skip early injection");

        withProductionSelectionConfig(home, selections -> {
            assertTrue(selections.selectedThemeId().isEmpty(), "reopened store reads native sentinel as empty");
            selections.clearSelectedThemeId();
        });
        assertTrue(loadBootstrapColors(home).isEmpty(), "cleared selection must skip early injection");
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void corruptArchiveOrUnsupportedSchemaFailsOpenWithoutBlockingPluginRecovery() throws Exception {
        final Path archive = themeArchivePath(home, CUSTOM_THEME_ID);
        Files.createDirectories(archive.getParent());
        Files.write(archive, new byte[] {0x50, 0x4B, 0x03, 0x00, (byte) 0xFF});

        withProductionSelectionConfig(home, selections -> selections.saveSelectedThemeId(CUSTOM_THEME_ID));

        final List<String> archiveDiagnostics = captureAppearanceDiagnostics();
        assertTrue(loadBootstrapColors(home).isEmpty(), "corrupt zip archive must fail open safely");
        assertEquals(1, archiveDiagnostics.size(), archiveDiagnostics.toString());

        final Path selectionFile =
                home.resolve("config").resolve(THEME_PLUGIN_ID).resolve(ThemeSelectionConfig.CONFIG_PATH);
        Files.writeString(
                selectionFile,
                "turboism-typed-config-v1\nschemaVersion=2\nrevision=1\ncount=0\n",
                StandardCharsets.UTF_8);

        RuntimeDiagnostics.clear();
        final List<String> schemaDiagnostics = captureAppearanceDiagnostics();
        assertTrue(loadBootstrapColors(home).isEmpty(), "future schema version must fail closed with one diagnostic");
        assertEquals(1, schemaDiagnostics.size(), schemaDiagnostics.toString());
        assertTrue(schemaDiagnostics.get(0).contains("EARLY_THEME_SELECTION_SCHEMA_UNSUPPORTED"));

        Files.deleteIfExists(selectionFile);
        writeThemeArchive(home, CUSTOM_THEME_ID, Map.of(VIEW_BACKGROUND_KEY, VIEW_BACKGROUND_HEX));
        withProductionSelectionConfig(home, selections -> selections.saveSelectedThemeId(CUSTOM_THEME_ID));
        assertEquals(
                VIEW_BACKGROUND_HEX, loadBootstrapColors(home).orElseThrow().get(VIEW_BACKGROUND_KEY));
    }

    private static void withProductionSelectionConfig(final Path homeDir, final Consumer<ThemeSelectionConfig> action)
            throws Exception {
        final Path configRoot = homeDir.resolve("config").resolve(THEME_PLUGIN_ID);
        Files.createDirectories(configRoot.getParent());
        final RuntimeScheduler scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 4, ignored -> {}, Clock.systemUTC()),
                ignored -> {});
        final DisposableScope scope = new DisposableScope();
        try {
            final RuntimePluginTaskScheduler tasks = new RuntimePluginTaskScheduler(THEME_PLUGIN_ID, scheduler, scope);
            try (RuntimeTypedPluginConfigRegistry registry = new RuntimeTypedPluginConfigRegistry(
                    new NoopLegacyConfigRegistry(),
                    THEME_PLUGIN_ID,
                    configRoot,
                    Set.of(PermissionIds.TURBOISM_CONFIG_PLUGIN_READ, PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE),
                    tasks,
                    scope)) {
                final ThemeSelectionConfig selections = new ThemeSelectionConfig(registry);
                selections.initialize().toCompletableFuture().join();
                action.accept(selections);
            }
        } finally {
            scope.close();
            scheduler.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, String>> loadBootstrapColors(final Path homeDir) throws Exception {
        final EarlyThemeAppearanceBootstrap bootstrap = new EarlyThemeAppearanceBootstrap(
                homeDir, EarlyThemeBootstrapInteractionTest.class.getClassLoader(), () -> {});
        final Method load = EarlyThemeAppearanceBootstrap.class.getDeclaredMethod("loadPersistedThemeColors");
        load.setAccessible(true);
        return (Optional<Map<String, String>>) load.invoke(bootstrap);
    }

    private static Path themeArchivePath(final Path homeDir, final String themeId) {
        return homeDir.resolve("data")
                .resolve(THEME_PLUGIN_ID)
                .resolve("themes")
                .resolve(themeId + ".zip");
    }

    private static void writeThemeArchive(final Path homeDir, final String themeId, final Map<String, String> colors)
            throws Exception {
        final Path archive = themeArchivePath(homeDir, themeId);
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("colors.properties"));
            final StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> entry : colors.entrySet()) {
                body.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            zip.write(body.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static List<String> captureAppearanceDiagnostics() {
        final List<String> captured = new CopyOnWriteArrayList<>();
        RuntimeDiagnostics.install((level, component, message, failure) -> {
            if (APPEARANCE_COMPONENT.equals(component)) {
                captured.add(level + " " + component + " " + message);
            }
        });
        return captured;
    }

    private static final class NoopLegacyConfigRegistry implements PluginConfigRegistry {
        @Override
        public Registration readScope(final String relativePath) {
            return () -> {};
        }

        @Override
        public Registration writeScope(final String relativePath) {
            return () -> {};
        }

        @Override
        public Optional<String> readString(final String relativePath, final String key) {
            return Optional.empty();
        }

        @Override
        public void writeString(final String relativePath, final String key, final String value) {}
    }
}
