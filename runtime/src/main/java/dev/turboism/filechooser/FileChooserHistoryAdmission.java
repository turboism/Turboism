package dev.turboism.filechooser;

import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides which file-chooser history view one plugin context receives.
 *
 * <p>The remembered save directories are applied to and captured from the host save dialogs, and
 * the framework shell owns the persisted store. A plugin context there is write-incapable: it keeps
 * the reads, and every write or provider registration fails closed with an explicit message instead
 * of silently rewriting the operator's save history. Only the shell's reserved identity keeps the
 * writable surface. No dedicated permission id exists for this boundary, because there is no
 * plugin-facing write to grant.
 */
public final class FileChooserHistoryAdmission {

    /** The only identity allowed to write the persisted save-directory history. */
    public static final String WRITER_IDENTITY = CorePluginManagement.CORE_PLUGIN_ID;

    /** Stable message for a write refused because the caller is not the framework shell. */
    public static final String WRITE_REFUSED_MESSAGE =
            "file-chooser history writes are reserved for the framework shell (" + WRITER_IDENTITY + ")";

    private FileChooserHistoryAdmission() {}

    /**
     * Returns the shell's writable service for the reserved shell identity, and a read-only view for
     * every other plugin identity.
     *
     * @param pluginId the identity of the context receiving the service, non-null
     * @param service the shared runtime service, non-null
     * @return the writable service for the shell, otherwise a read-only view over it
     */
    public static FileChooserHistoryService forCaller(final String pluginId, final FileChooserHistoryService service) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(service, "service");
        return WRITER_IDENTITY.equals(pluginId) ? service : new ReadOnlyView(service);
    }

    /** Read-capable, write-refusing view handed to every non-shell plugin context. */
    private static final class ReadOnlyView implements FileChooserHistoryService {

        private final FileChooserHistoryService delegate;

        private ReadOnlyView(final FileChooserHistoryService delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Path> projectRecentDirectory() {
            return delegate.projectRecentDirectory();
        }

        @Override
        public Optional<Path> exportRecentDirectory() {
            return delegate.exportRecentDirectory();
        }

        @Override
        public void setProjectRecentDirectory(final Path dir) {
            throw new UnsupportedOperationException(WRITE_REFUSED_MESSAGE);
        }

        @Override
        public void setExportRecentDirectory(final Path dir) {
            throw new UnsupportedOperationException(WRITE_REFUSED_MESSAGE);
        }

        @Override
        public boolean exportSeparationEnabled() {
            return delegate.exportSeparationEnabled();
        }

        @Override
        public boolean isAvailable() {
            return delegate.isAvailable();
        }

        @Override
        public Registration registerProvider(final Provider provider) {
            throw new UnsupportedOperationException(
                    "file-chooser history provider registration is reserved for the framework shell ("
                            + WRITER_IDENTITY + ")");
        }
    }
}
