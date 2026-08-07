package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Covers the {@code fileChooserHistory()} exposure on {@link CorePluginContext}. */
class CorePluginContextFileChooserHistoryTest {

    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void defaultsToUnavailableWithoutInjection() {
        final CorePluginContext context =
            new CorePluginContext(dependencies(TEMP), RuntimeHostAdapters.safeMode());
        assertSame(FileChooserHistoryService.unavailable(), context.fileChooserHistory());
        assertThrows(
            UnsupportedOperationException.class,
            () -> context.fileChooserHistory().setExportRecentDirectory(Path.of("x"))
        );
    }

    @Test
    void returnsInjectedServiceInstance() {
        final FileChooserHistoryService injected = FileChooserHistoryService.unavailable();
        final CorePluginContext context = new CorePluginContext(
            dependencies(TEMP),
            RuntimeHostAdapters.safeMode(),
            null,
            null,
            null,
            null,
            null,
            injected
        );
        assertSame(injected, context.fileChooserHistory());
    }

    private static CorePluginContext.Dependencies dependencies(final Path dataDir) {
        return new CorePluginContext.Dependencies(
            descriptor(),
            logger(),
            paths(dataDir),
            uiScheduler(),
            scheduler(),
            diagnostics(),
            new DisposableScope(),
            noopHostSnapshotSource(),
            ignored -> { },
            CLOCK
        );
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return "dev.turboism.test.FileChooserHistoryTest"; }
            @Override public String name() { return "File Chooser History Test"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Test"; }
            @Override public List<String> entrypoints() { return List.of("dev.turboism.test.FileChooserHistoryPlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() { return Optional.of("https://turboism.dev"); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() {
                return new I18n() {
                    @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
                    @Override public List<String> locales() { return List.of(); }
                };
            }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() {
                return new Environment() {
                    @Override public boolean requiresCubism() { return false; }
                    @Override public String ui() { return "none"; }
                };
            }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void error(String message) { }
            @Override public void error(String message, Throwable throwable) { }
        };
    }

    private static PluginPaths paths(Path dataDir) {
        return new PluginPaths() {
            @Override public Path dataDir() { return dataDir; }
            @Override public Path logsDir() { return dataDir; }
            @Override public Path stateDir() { return dataDir; }
            @Override public Path cacheDir() { return dataDir; }
        };
    }

    private static UiScheduler uiScheduler() {
        return new UiScheduler() {
            @Override public dev.turboism.sdk.plugin.Registration runOnUiThread(Runnable work) { work.run(); return () -> { }; }
            @Override public dev.turboism.sdk.plugin.Registration runOnUiThreadLater(Runnable work, Duration delay) { return () -> { }; }
        };
    }

    private static RuntimeScheduler scheduler() {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
            (task, callback) -> {
                callback.run();
                return java.util.concurrent.CompletableFuture.completedFuture(
                    dev.turboism.core.runtime.sidecar.SidecarResult.success("")
                );
            },
            events::add
        );
    }

    private static DiagnosticReport diagnostics() {
        return new DiagnosticReport() {
            @Override public Instant createdAt() { return CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }

    private static HostSnapshotSource noopHostSnapshotSource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSelection selection() {
                return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
            }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0; }
        };
    }

    @TempDir
    static Path TEMP;
}
