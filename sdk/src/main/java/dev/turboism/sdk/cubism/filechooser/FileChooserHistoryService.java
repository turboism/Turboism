package dev.turboism.sdk.cubism.filechooser;

import dev.turboism.sdk.PreviewApi;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Read/write access to the recent save directories remembered per file-chooser
 * context (project save vs. export output save).
 *
 * <p>The export context gets its own recent directory (applied to the save
 * dialog when the dialog is opened from an export flow) so that project saves
 * and export saves no longer share one history. The project context keeps
 * Cubism's native {@code jp.noids.io.UtFileChooser.dir_history} behavior.
 * Directories are persisted in the global {@code <turboism.home>/config.json}.
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

    /** Safe-mode instance: reads are empty and writes fail closed. */
    static FileChooserHistoryService unavailable() {
        return Unavailable.INSTANCE;
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
    }
}
