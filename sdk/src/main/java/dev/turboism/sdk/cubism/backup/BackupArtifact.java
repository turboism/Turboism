package dev.turboism.sdk.cubism.backup;


import java.util.Objects;

/**
 * Detached metadata for one backup artifact.
 *
 * <p>The event surface intentionally exposes only the base file name, byte size,
 * and whether the artifact is temporary. It never exposes a host path or a mutable
 * {@code File} handle.</p>
 *
 * @param fileName artifact base name, never a path
 * @param sizeBytes observed artifact size in bytes
 * @param temporary whether the runtime created a temporary save-triggered artifact
 */
public record BackupArtifact(String fileName, long sizeBytes, boolean temporary) {
    public BackupArtifact {
        Objects.requireNonNull(fileName, "fileName");
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("fileName must be a non-blank base name");
        }
        if (sizeBytes < 0L) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
