package dev.turboism.filechooser;

import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Runtime {@link FileChooserHistoryService} that delegates persistence to a
 * single {@link FileChooserHistoryService.Provider} registered by the core
 * plugin. Without a provider it is fail-closed: reads return empty and writes
 * are no-ops. The separation flag still comes from config.json
 * ({@code hooks.startup.separateExportSaveDirectory}).
 */
public final class RuntimeFileChooserHistoryService implements FileChooserHistoryService {

    private final AtomicReference<FileChooserHistoryService.Provider> provider = new AtomicReference<>();
    private final BooleanSupplier exportSeparationEnabled;

    public RuntimeFileChooserHistoryService(final BooleanSupplier exportSeparationEnabled) {
        this.exportSeparationEnabled = Objects.requireNonNull(exportSeparationEnabled, "exportSeparationEnabled");
    }

    @Override
    public Optional<Path> projectRecentDirectory() {
        final FileChooserHistoryService.Provider active = provider.get();
        return active == null ? Optional.empty() : active.loadProjectDirectory();
    }

    @Override
    public Optional<Path> exportRecentDirectory() {
        final FileChooserHistoryService.Provider active = provider.get();
        return active == null ? Optional.empty() : active.loadExportDirectory();
    }

    @Override
    public void setProjectRecentDirectory(final Path dir) {
        Objects.requireNonNull(dir, "dir");
        final FileChooserHistoryService.Provider active = provider.get();
        if (active != null) {
            active.saveProjectDirectory(dir);
        }
    }

    @Override
    public void setExportRecentDirectory(final Path dir) {
        Objects.requireNonNull(dir, "dir");
        final FileChooserHistoryService.Provider active = provider.get();
        if (active != null) {
            active.saveExportDirectory(dir);
        }
    }

    @Override
    public boolean exportSeparationEnabled() {
        return exportSeparationEnabled.getAsBoolean();
    }

    @Override
    public Registration registerProvider(final FileChooserHistoryService.Provider candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("provider");
        }
        if (!provider.compareAndSet(null, candidate)) {
            throw new IllegalStateException("A file-chooser history provider is already registered.");
        }
        return () -> provider.compareAndSet(candidate, null);
    }
}
