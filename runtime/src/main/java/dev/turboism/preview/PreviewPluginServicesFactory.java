package dev.turboism.preview;

import dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.config.RuntimeTypedPluginConfigRegistry;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.hostread.ProjectWorkspaceHostReadSource;
import dev.turboism.hostread.RuntimeAsyncHostReadService;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.i18n.CubismHostLocale;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.home.PluginHomePaths;
import dev.turboism.home.TurboismHomeLayout;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.storage.RuntimePluginStorage;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.ui.RuntimeUiScheduler;
import dev.turboism.ui.UiHostStateSource;
import dev.turboism.userfile.RuntimeUserFileAccessService;
import dev.turboism.userfile.UserFileGrantSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Creates the shared service graph for one preview plugin scope. */
final class PreviewPluginServicesFactory {

    private final Path home;
    private final RuntimeScheduler scheduler;
    private final RuntimeHostAdapterAccess hostAccess;
    private final SharedAsyncHostReadLane hostReadLane;
    private final PreviewLog log;
    private final RuntimeFailureCollector failureCollector;

    PreviewPluginServicesFactory(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final SharedAsyncHostReadLane hostReadLane,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector
    ) {
        this.home = home;
        this.scheduler = scheduler;
        this.hostAccess = hostAccess;
        this.hostReadLane = hostReadLane;
        this.log = log;
        this.failureCollector = failureCollector;
    }

    PreviewPluginServices create(
        final PluginDescriptor descriptor,
        final ClassLoader classLoader,
        final DisposableScope scope
    ) throws IOException {
        final RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(scheduler, descriptor.id());
        scope.register(uiScheduler);
        final PluginHomePaths paths = TurboismHomeLayout.create(home).plugin(descriptor.id());
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final RuntimePluginTaskScheduler tasks = tasks(descriptor, scope, evidence);
        final Set<String> permissions = permissionIds(descriptor);
        final CorePluginContext.Dependencies dependencies = dependencies(descriptor, paths, uiScheduler, scope);
        return new PreviewPluginServices(
            dependencies, localization(descriptor, classLoader), tasks,
            storage(descriptor, paths, permissions, tasks, scope, evidence),
            typedConfig(dependencies, descriptor, paths, permissions, tasks, scope, evidence),
            userFiles(descriptor, permissions, tasks, scope, evidence),
            hostReads(descriptor, permissions, tasks, scope), evidence
        );
    }

    private CorePluginContext.Dependencies dependencies(
        final PluginDescriptor descriptor,
        final PluginHomePaths paths,
        final RuntimeUiScheduler uiScheduler,
        final DisposableScope scope
    ) {
        return new CorePluginContext.Dependencies(
            descriptor, new PreviewPluginLogger(log, descriptor.id()), paths, uiScheduler, scheduler,
            new PreviewDiagnosticReport(), scope, EmptyHostSnapshotSource.INSTANCE,
            M12ReadSnapshotSource.EMPTY, new PreviewUiHostStateSource(paths),
            event -> log.debug(descriptor.id(), event.toString()), Clock.systemUTC(), failureCollector
        );
    }

    private RuntimePluginLocalization localization(
        final PluginDescriptor descriptor,
        final ClassLoader classLoader
    ) {
        // Cubism 语言版本说明见 CubismHostLocale（CubismEditor5.bat 设置 -Duser.language=zh）。
        final Locale cubismLocale = CubismHostLocale.resolve();
        return RuntimePluginLocalization.create(
            descriptor.id(), classLoader, descriptor.i18n(), System.getProperty("turboism.locale"),
            cubismLocale, Locale.getDefault(Locale.Category.DISPLAY),
            diagnostic -> log.warn(descriptor.id(), diagnostic.code() + ": " + diagnostic.message())
        );
    }

    private RuntimePluginTaskScheduler tasks(
        final PluginDescriptor descriptor,
        final DisposableScope scope,
        final CleanupEvidenceCollector evidence
    ) {
        return new RuntimePluginTaskScheduler(
            descriptor.id(), scheduler, scope, evidence, failureCollector
        );
    }

    private static Set<String> permissionIds(final PluginDescriptor descriptor) {
        return descriptor.permissions().stream()
            .map(permission -> permission.id())
            .collect(Collectors.toUnmodifiableSet());
    }

    private RuntimePluginStorage storage(
        final PluginDescriptor descriptor,
        final PluginHomePaths paths,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope,
        final CleanupEvidenceCollector evidence
    ) throws IOException {
        return new RuntimePluginStorage(
            descriptor.id(), storageRoots(paths), permissions, tasks, scope, evidence, failureCollector
        );
    }

    private static Map<StorageRoot, Path> storageRoots(final PluginHomePaths paths) {
        return Map.of(
            StorageRoot.DATA, paths.dataDir(), StorageRoot.STATE, paths.stateDir(),
            StorageRoot.CACHE, paths.cacheDir()
        );
    }

    private RuntimeTypedPluginConfigRegistry typedConfig(
        final CorePluginContext.Dependencies dependencies,
        final PluginDescriptor descriptor,
        final PluginHomePaths paths,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope,
        final CleanupEvidenceCollector evidence
    ) {
        return new RuntimeTypedPluginConfigRegistry(
            dependencies.config(), descriptor.id(), paths.configDir(), permissions,
            tasks, scope, evidence, failureCollector
        );
    }

    private RuntimeUserFileAccessService userFiles(
        final PluginDescriptor descriptor,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope,
        final CleanupEvidenceCollector evidence
    ) {
        return new RuntimeUserFileAccessService(
            descriptor.id(), permissions, UserFileGrantSource.unavailable(), tasks, scope,
            evidence, failureCollector
        );
    }

    private RuntimeAsyncHostReadService hostReads(
        final PluginDescriptor descriptor,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope
    ) {
        return new RuntimeAsyncHostReadService(
            descriptor.id(), permissions,
            ProjectWorkspaceHostReadSource.from(hostAccess.adapters().projectWorkspace()),
            hostReadLane, tasks, scope
        );
    }
}

record PreviewPluginServices(
    CorePluginContext.Dependencies dependencies,
    RuntimePluginLocalization localization,
    RuntimePluginTaskScheduler taskScheduler,
    RuntimePluginStorage pluginStorage,
    RuntimeTypedPluginConfigRegistry typedConfig,
    RuntimeUserFileAccessService userFiles,
    RuntimeAsyncHostReadService hostReads,
    CleanupEvidenceCollector cleanupEvidence
) {
}

/**
 * Preview UiHostStateSource that can open the plugin storage directory in the
 * host file manager and detects the active color mode from the UIManager.
 */
final class PreviewUiHostStateSource implements UiHostStateSource {

    private final PluginHomePaths paths;

    PreviewUiHostStateSource(final PluginHomePaths paths) {
        this.paths = paths;
    }

    @Override
    public void openDirectory(final dev.turboism.sdk.storage.StoragePath directory) {
        final java.nio.file.Path base = paths.dataDir();
        final java.nio.file.Path resolved = base.resolve(directory.relativePath())
            .normalize();
        if (!resolved.startsWith(base)) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(resolved);
        } catch (java.io.IOException ignored) {
            return;
        }
        if (java.awt.Desktop.isDesktopSupported()) {
            try {
                java.awt.Desktop.getDesktop().open(resolved.toFile());
            } catch (java.io.IOException ignored) {
                // Opening a directory is best-effort on the validation host.
            }
        }
    }
}
