package dev.turboism.sdk.cubism.backup;

import java.io.IOException;
import java.io.InputStream;

/**
 * An opaque, permission-gated handle over one backup artifact: the only
 * artifact reference a plugin ever sees.
 *
 * <p>The handle deliberately never exposes the underlying host path. Detached
 * metadata (base name, observed size, temporary flag) is available through
 * {@link #artifact()}, and {@link #openStream()} is the only way to read the
 * bytes. Content reads and {@link #discard()} require the
 * {@code turboism.cubism.backup.observe} permission and fail with a
 * {@link dev.turboism.sdk.permission.CubismPermissionException} without it.
 * The runtime issues handles confined to the directory they were produced in
 * (the host backup directory, or a runtime-created temporary directory for
 * save-triggered artifacts): reads refuse symbolic links and reject artifacts
 * that would resolve outside that issuing directory.</p>
 */
public interface BackupArtifactHandle {

    /**
     * Returns the detached metadata projection safe to store, log, or
     * republish: base name, observed size, and the temporary flag. Never a path.
     *
     * @return this artifact's detached metadata
     */
    BackupArtifact artifact();

    /**
     * Returns the artifact's last-modified time in epoch milliseconds as
     * observed when this handle was issued, or {@code -1} when the runtime
     * could not observe it.
     *
     * @return the observed modification time, or {@code -1} when unknown
     */
    long lastModifiedMillis();

    /**
     * Opens a new read stream over the artifact bytes.
     *
     * <p>Every call re-validates the artifact: it must still resolve to a
     * regular file inside the directory this handle was issued for, and
     * symbolic links are never followed. The caller owns the returned stream
     * and must close it.</p>
     *
     * @return a fresh read stream over the artifact content
     * @throws IOException when the artifact is missing, is not a regular file,
     *         or no longer resolves inside its issuing directory
     * @throws dev.turboism.sdk.permission.CubismPermissionException when the
     *         caller lacks {@code turboism.cubism.backup.observe}
     */
    InputStream openStream() throws IOException;

    /**
     * Discards this artifact when the runtime created it as a temporary
     * save-triggered copy ({@link BackupArtifact#temporary()}), pruning its
     * runtime temp directory when it becomes empty. Discarding an already
     * removed temporary artifact succeeds silently.
     *
     * <p>Host-owned artifacts inside the configured backup directory are never
     * deletable through this surface: their retention belongs to the host's
     * {@code maxMB} cap, so discarding them fails closed with
     * {@link IllegalStateException}.</p>
     *
     * @throws IOException when the delete itself fails
     * @throws IllegalStateException when the artifact is not temporary
     * @throws dev.turboism.sdk.permission.CubismPermissionException when the
     *         caller lacks {@code turboism.cubism.backup.observe}
     */
    void discard() throws IOException;

    /**
     * Returns the artifact base name; never a path.
     *
     * @return {@code artifact().fileName()}
     */
    default String fileName() {
        return artifact().fileName();
    }

    /**
     * Returns the artifact size in bytes as observed when the handle was issued.
     *
     * @return {@code artifact().sizeBytes()}
     */
    default long sizeBytes() {
        return artifact().sizeBytes();
    }

    /**
     * Returns whether the runtime created this artifact as a temporary
     * save-triggered copy.
     *
     * @return {@code artifact().temporary()}
     */
    default boolean temporary() {
        return artifact().temporary();
    }
}
