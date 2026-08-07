package dev.turboism.sdk.cubism.backup;

import dev.turboism.sdk.PreviewApi;

/**
 * Immutable auto-backup settings projection of the host's auto-backup manager
 * ({@code com.live2d.cubism.util.a}, CEAutobackupManager).
 *
 * <p>{@code backupDir} is host-read-only: it is the directory the host writes
 * {@code <name>_backup<yyyy_MMdd_HHmm>.cmo3} artifacts into and is never
 * mutated through this API. It is {@code null} when the host did not expose
 * a backup directory (fail closed).</p>
 */
@PreviewApi
public record EditorAutoBackupSettings(
    boolean enabled,
    int intervalMinutes,
    int maxMB,
    String backupDir
) {

    public static final int MIN_INTERVAL_MINUTES = 1;
    public static final int MAX_INTERVAL_MINUTES = 1440;
    public static final int MIN_MAX_MB = 1;
    public static final int MAX_MAX_MB = 1_048_576;

    public EditorAutoBackupSettings {
        if (intervalMinutes < MIN_INTERVAL_MINUTES || intervalMinutes > MAX_INTERVAL_MINUTES) {
            throw new IllegalArgumentException(
                "intervalMinutes must be within [" + MIN_INTERVAL_MINUTES + ", "
                    + MAX_INTERVAL_MINUTES + "], got " + intervalMinutes
            );
        }
        if (maxMB < MIN_MAX_MB || maxMB > MAX_MAX_MB) {
            throw new IllegalArgumentException(
                "maxMB must be within [" + MIN_MAX_MB + ", " + MAX_MAX_MB + "], got " + maxMB
            );
        }
    }

    /** Defaults mirrored from the host manager (interval 5 min, 50 MB cap, disabled). */
    public static EditorAutoBackupSettings defaults() {
        return new EditorAutoBackupSettings(false, 5, 50, null);
    }
}
