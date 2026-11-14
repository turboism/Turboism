package dev.turboism.kernel.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconAvailability;
import dev.turboism.sdk.ui.resource.UiIconRef;
import dev.turboism.sdk.ui.resource.UiResourceService;
import dev.turboism.test.Stubs;
import dev.turboism.test.plugin.FakePluginPaths;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import dev.turboism.testkit.RuntimeTestKit;
import dev.turboism.ui.resource.RuntimeUiResourceService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers Runtime UI-resource and file-chooser exposure on {@link CorePluginContext}. */
class CorePluginContextFileChooserHistoryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void defaultsToUnavailableWithoutInjection() {
        final CorePluginContext context = new CorePluginContext(dependencies(TEMP), RuntimeHostAdapters.safeMode());
        assertSame(
                FileChooserHistoryService.unavailable(),
                context.services()
                        .find(FileChooserHistoryService.class)
                        .orElse(FileChooserHistoryService.unavailable()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> context.services()
                        .find(FileChooserHistoryService.class)
                        .orElse(FileChooserHistoryService.unavailable())
                        .setExportRecentDirectory(Path.of("x")));
    }

    @Test
    void composesOneUiResourceServiceForAllContextsWithoutContextOwnership() {
        final UiIconRef reference = new UiIconRef(CubismIcon.ART_MESH);
        final RuntimeUiResourceService owner = RuntimeUiResourceService.unavailable();
        final UiResourceService injected = owner.sdkView();
        final RuntimeHostAdapters composed =
                RuntimeHostAdapters.withUiResources(RuntimeHostAdapters.safeMode(), injected);

        final CorePluginContext first = new CorePluginContext(dependencies(TEMP), composed);
        final CorePluginContext second = new CorePluginContext(dependencies(TEMP), composed);
        final CorePluginContext safe = new CorePluginContext(dependencies(TEMP), RuntimeHostAdapters.safeMode());

        assertFalse(injected instanceof AutoCloseable);
        assertSame(injected, owner.sdkView());
        assertSame(
                UiResourceService.unavailable(),
                safe.services().find(UiResourceService.class).orElse(UiResourceService.unavailable()));
        assertSame(injected, composed.uiResources());
        assertSame(injected, first.services().find(UiResourceService.class).orElse(UiResourceService.unavailable()));
        assertSame(injected, second.services().find(UiResourceService.class).orElse(UiResourceService.unavailable()));
        assertEquals(
                UiIconAvailability.SERVICE_UNAVAILABLE,
                first.services()
                        .find(UiResourceService.class)
                        .orElse(UiResourceService.unavailable())
                        .availability(reference));
    }

    @Test
    void rejectsInjectedServiceWithoutAReviewedEditorVersionBeforeDelegateInvocation() {
        final AtomicInteger calls = new AtomicInteger();
        final FileChooserHistoryService injected = recordingService(calls);
        final CorePluginContext context = new CorePluginContext(
                dependencies(TEMP), RuntimeHostAdapters.safeMode(), null, null, null, null, null, injected);

        assertThrows(
                dev.turboism.sdk.cubism.CubismEditorApiUnavailableException.class,
                context.services().find(FileChooserHistoryService.class).orElse(FileChooserHistoryService.unavailable())
                        ::projectRecentDirectory);
        assertEquals(0, calls.get());
    }

    private static FileChooserHistoryService recordingService(final AtomicInteger calls) {
        return new FileChooserHistoryService() {
            @Override
            public Optional<Path> projectRecentDirectory() {
                calls.incrementAndGet();
                return Optional.empty();
            }

            @Override
            public Optional<Path> exportRecentDirectory() {
                return Optional.empty();
            }

            @Override
            public void setProjectRecentDirectory(final Path dir) {}

            @Override
            public void setExportRecentDirectory(final Path dir) {}

            @Override
            public boolean exportSeparationEnabled() {
                return false;
            }

            @Override
            public Registration registerProvider(final Provider provider) {
                return () -> {};
            }
        };
    }

    private static CorePluginContext.Dependencies dependencies(final Path dataDir) {
        return new CorePluginContext.Dependencies(
                descriptor(),
                logger(),
                paths(dataDir),
                uiScheduler(),
                RuntimeTestKit.scheduler(CLOCK, 1, 4),
                diagnostics(),
                new DisposableScope(),
                noopHostSnapshotSource(),
                ignored -> {},
                CLOCK);
    }

    private static PluginDescriptor descriptor() {
        return Stubs.descriptorBuilder("dev.turboism.test.FileChooserHistoryTest")
                .name("File Chooser History Test")
                .version("0.1.0")
                .entrypoints("dev.turboism.test.FileChooserHistoryPlugin")
                .license("Project License")
                .website("https://turboism.dev")
                .i18n("META-INF/turboism/i18n/messages")
                .build();
    }

    private static PluginLogger logger() {
        return Stubs.noopLogger();
    }

    private static PluginPaths paths(final Path dataDir) {
        return FakePluginPaths.of(dataDir);
    }

    private static UiScheduler uiScheduler() {
        return FakeDirectUiScheduler.immediateOnly();
    }

    private static DiagnosticReport diagnostics() {
        return Stubs.diagnostics(CLOCK.instant());
    }

    private static HostSnapshotSource noopHostSnapshotSource() {
        return RuntimeTestKit.emptyHostSnapshotSource();
    }

    @TempDir
    static Path TEMP;
}
