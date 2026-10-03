package dev.turboism.adapter.cubism.backup;

import dev.turboism.ui.host.EdtDispatch;
import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * EDT-dispatching {@link AutoBackupAdapter} wrapper: every host operation runs
 * on the host UI thread (the native auto-backup manager is Swing-hosted), and
 * failures never escape the calling thread un-sanitized.
 */
final class VerifiedDispatchAutoBackupAdapter implements AutoBackupAdapter {

    private final AutoBackupAdapter.HostOperations host;

    VerifiedDispatchAutoBackupAdapter(final AutoBackupAdapter.HostOperations host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public AutoBackupAdapter.Snapshot settings() {
        return onEdt(() -> host.settings());
    }

    @Override
    public AutoBackupAdapter.Snapshot applySettings(final AutoBackupAdapter.Snapshot target) {
        Objects.requireNonNull(target, "target");
        return onEdt(() -> host.applySettings(target));
    }

    @Override
    public List<AutoBackupAdapter.Document> documents() {
        return onEdt(() -> host.documents());
    }

    @Override
    public void triggerBackupNow() {
        onEdt(() -> {
            host.triggerBackupNow();
            return null;
        });
    }

    @Override
    public File saveDocumentFor(final File matchFile, final List<String> documentUids, final long timestampMillis) {
        Objects.requireNonNull(matchFile, "matchFile");
        Objects.requireNonNull(documentUids, "documentUids");
        return onEdt(() -> host.saveDocumentFor(matchFile, documentUids, timestampMillis));
    }

    @Override
    public boolean available() {
        return true;
    }

    private static <T> T onEdt(final Operation<T> operation) {
        return EdtDispatch.call("auto-backup EDT operation", operation::run);
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
