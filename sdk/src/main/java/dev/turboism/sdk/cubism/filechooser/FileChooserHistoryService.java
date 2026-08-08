package dev.turboism.sdk.cubism.filechooser;

import dev.turboism.sdk.PreviewApi;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Read/write access to the recent save directories remembered per file-chooser
 * context (project save vs. export output save).
 *
 * <p>The export context gets its own recent directory (applied to the save
 * dialog when the dialog is opened from an export flow) and the project
 * context keeps its own, so project saves and export saves no longer share
 * one history. Both are applied/captured by the framework host hook when
 * {@link #exportSeparationEnabled()} is on.
 *
 * <p>Persistence is owned by the plugin side: a single {@link Provider}
 * registered by the core plugin stores both directories under the plugin
 * config directory ({@code <home>/config/dev.turboism.plugin.core/}). The
 * runtime service delegates reads/writes to the registered provider and is
 * fail-closed without one (reads empty, writes no-op).
 */
@PreviewApi
public interface FileChooserHistoryService {

    /** Returns the recent directory remembered for project saves, if any. */
    Optional<Path> projectRecentDirectory();

    /** Returns the recent directory remembered for export-output saves, if any. */
    Optional<Path> exportRecentDirectory();

    /** Persists the recent directory for project saves. */
    void setProjectRecentDirectory(Path dir);

    /** Persists the recent directory for export-output saves. */
    void setExportRecentDirectory(Path dir);

    /**
     * Whether export save directories are separated from project save
     * directories. Backed by {@code RuntimeSettings.separateExportSaveDirectory}
     * ({@code hooks.startup.separateExportSaveDirectory} in config.json).
     */
    boolean exportSeparationEnabled();

    /**
     * Registers the single persistence provider. At most one provider may be
     * active; registering while one is still registered throws
     * {@link IllegalStateException}.
     */
    Registration registerProvider(Provider provider);

    /** Safe-mode instance: reads are empty and writes fail closed. */
    static FileChooserHistoryService unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Persistence backend for the two recent-directory slots. Implementations
     * must tolerate a missing or partially corrupt store (missing data loads
     * as {@link Optional#empty()}).
     */
    interface Provider {

        /** Loads the recent directory remembered for project saves, if any. */
        Optional<Path> loadProjectDirectory();

        /** Loads the recent directory remembered for export-output saves, if any. */
        Optional<Path> loadExportDirectory();

        /** Persists the recent directory for project saves. */
        void saveProjectDirectory(Path dir);

        /** Persists the recent directory for export-output saves. */
        void saveExportDirectory(Path dir);
    }

    /** Handle for a provider registration; {@link #unregister()} is idempotent. */
    interface Registration extends AutoCloseable {

        void unregister();

        @Override
        default void close() {
            unregister();
        }
    }

    enum Unavailable implements FileChooserHistoryService {
        INSTANCE;

        @Override
        public Optional<Path> projectRecentDirectory() {
            return Optional.empty();
        }

        @Override
        public Optional<Path> exportRecentDirectory() {
            return Optional.empty();
        }

        @Override
        public void setProjectRecentDirectory(final Path dir) {
            throw new UnsupportedOperationException("file chooser history is unavailable in safe mode");
        }

        @Override
        public void setExportRecentDirectory(final Path dir) {
            throw new UnsupportedOperationException("file chooser history is unavailable in safe mode");
        }

        @Override
        public boolean exportSeparationEnabled() {
            return false;
        }

        @Override
        public Registration registerProvider(final Provider provider) {
            throw new UnsupportedOperationException("file chooser history is unavailable in safe mode");
        }
    }
}
