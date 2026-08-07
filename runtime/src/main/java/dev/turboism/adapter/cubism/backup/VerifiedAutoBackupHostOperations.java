package dev.turboism.adapter.cubism.backup;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact-version verified host operations over the native auto-backup manager
 * ({@code com.live2d.cubism.util.a} = CEAutobackupManager) and its pack /
 * file-content surface.
 *
 * <p>Safety guarantees:</p>
 * <ul>
 *   <li>Every selector used by an operation resolves BEFORE the first mutation,
 *       so a missing or drifted selector tuple can never leave partial host state.</li>
 *   <li>{@code applySettings} applies the target through the manager setters,
 *       reads the values back, and verifies the readback equals the target; the
 *       caller (coordinator) restores the observed originals through the same
 *       verified path on failure, so an unverified rollback fails closed.</li>
 *   <li>{@code triggerBackupNow} attaches the current complete pack idempotently
 *       (the host ignores a re-attached pack) and then invokes {@code h()}
 *       (updateAutoBackup); without a pack it fails closed.</li>
 *   <li>The forbidden members {@code e()}, {@code d()}, {@code f()}, {@code g()},
 *       {@code j()} and {@code a(ZI)V} are never mapped or invoked.</li>
 * </ul>
 */
public final class VerifiedAutoBackupHostOperations implements AutoBackupAdapter.HostOperations {

    /** Exact selector aliases pinned in the reviewed auto-backup verification record. */
    static final class Aliases {
        static final String MANAGER_CLASS = "cubism.auto-backup.manager.class";
        static final String MANAGER_INSTANCE = "cubism.auto-backup.manager.instance";
        static final String IS_ENABLED = "cubism.auto-backup.is-enabled";
        static final String SET_ENABLED = "cubism.auto-backup.set-enabled";
        static final String SET_INTERVAL_MINUTE = "cubism.auto-backup.set-interval-minute";
        static final String ATTACH_PACK = "cubism.auto-backup.attach-pack";
        static final String GET_INTERVAL_MINUTE = "cubism.auto-backup.get-interval-minute";
        static final String SET_MAX_MB = "cubism.auto-backup.set-max-mb";
        static final String GET_MAX_MB = "cubism.auto-backup.get-max-mb";
        static final String UPDATE = "cubism.auto-backup.update";
        static final String BACKUP_DIR = "cubism.auto-backup.backup-dir";
        static final String APP_CONTROLLER_INSTANCE = "cubism.auto-backup.app-controller.instance";
        static final String APP_CONTROLLER_CLASS = "cubism.auto-backup.app-controller.class";
        static final String APP_CONTROLLER_GET_COMPLETE_PACK = "cubism.auto-backup.app-controller.get-complete-pack";
        static final String COMPLETE_PACK_CLASS = "cubism.auto-backup.complete-pack.class";
        static final String COMPLETE_PACK_FILE_CONTENTS = "cubism.auto-backup.complete-pack.file-contents";
        static final String FILE_CONTENT_CLASS = "cubism.auto-backup.file-content.class";
        static final String FILE_CONTENT_LAST_AUTO_BACKUP_TIME = "cubism.auto-backup.file-content.last-auto-backup-time";
        static final String FILE_CONTENT_SET_LAST_AUTO_BACKUP_TIME = "cubism.auto-backup.file-content.set-last-auto-backup-time";
        static final String FILE_CONTENT_LAST_SAVED_TIME = "cubism.auto-backup.file-content.last-saved-time";
        static final String FILE_CONTENT_MODIFIED_AFTER_SAVING = "cubism.auto-backup.file-content.modified-after-saving";
        static final String FILE_CONTENT_FILE = "cubism.auto-backup.file-content.file";

        private Aliases() {
        }
    }

    static final String APP_INSTANCE = "cubism.auto-backup.app-controller.instance";

    private final VerifiedMemberResolver resolver;

    public VerifiedAutoBackupHostOperations(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public AutoBackupAdapter.Snapshot settings() {
        requireResolvable(
            Aliases.MANAGER_CLASS, Aliases.MANAGER_INSTANCE, Aliases.IS_ENABLED,
            Aliases.GET_INTERVAL_MINUTE, Aliases.GET_MAX_MB, Aliases.BACKUP_DIR
        );
        final Object manager = manager();
        final boolean enabled = (Boolean) resolver.invoke(Aliases.IS_ENABLED, manager);
        final int interval = (Integer) resolver.invoke(Aliases.GET_INTERVAL_MINUTE, manager);
        final int maxMB = (Integer) resolver.invoke(Aliases.GET_MAX_MB, manager);
        final File backupDir = (File) resolver.invoke(Aliases.BACKUP_DIR, manager);
        return new AutoBackupAdapter.Snapshot(enabled, interval, maxMB, backupDir);
    }

    @Override
    public AutoBackupAdapter.Snapshot applySettings(final AutoBackupAdapter.Snapshot target) {
        Objects.requireNonNull(target, "target");
        requireResolvable(
            Aliases.MANAGER_CLASS, Aliases.MANAGER_INSTANCE, Aliases.IS_ENABLED,
            Aliases.SET_ENABLED, Aliases.SET_INTERVAL_MINUTE, Aliases.GET_INTERVAL_MINUTE,
            Aliases.SET_MAX_MB, Aliases.GET_MAX_MB, Aliases.BACKUP_DIR
        );
        final Object manager = manager();
        resolver.invoke(Aliases.SET_ENABLED, manager, target.enabled());
        resolver.invoke(Aliases.SET_INTERVAL_MINUTE, manager, target.intervalMinutes());
        resolver.invoke(Aliases.SET_MAX_MB, manager, target.maxMB());
        final AutoBackupAdapter.Snapshot readback = settings();
        if (!matches(target, readback)) {
            throw new IllegalStateException(
                "auto-backup settings readback does not match the applied target"
            );
        }
        return readback;
    }

    @Override
    public List<AutoBackupAdapter.Document> documents() {
        requireResolvable(
            Aliases.MANAGER_CLASS, Aliases.MANAGER_INSTANCE, Aliases.APP_CONTROLLER_CLASS,
            Aliases.APP_CONTROLLER_GET_COMPLETE_PACK, Aliases.COMPLETE_PACK_CLASS,
            Aliases.COMPLETE_PACK_FILE_CONTENTS, Aliases.FILE_CONTENT_CLASS,
            Aliases.FILE_CONTENT_LAST_AUTO_BACKUP_TIME, Aliases.FILE_CONTENT_LAST_SAVED_TIME,
            Aliases.FILE_CONTENT_MODIFIED_AFTER_SAVING, Aliases.FILE_CONTENT_FILE,
            APP_INSTANCE
        );
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        if (app == null) {
            return List.of();
        }
        final Object pack = resolver.invoke(Aliases.APP_CONTROLLER_GET_COMPLETE_PACK, app);
        if (pack == null) {
            return List.of();
        }
        final List<?> contents = (List<?>) resolver.invoke(Aliases.COMPLETE_PACK_FILE_CONTENTS, pack);
        if (contents == null) {
            return List.of();
        }
        final List<AutoBackupAdapter.Document> documents = new ArrayList<>();
        for (Object content : contents) {
            if (content == null) {
                continue;
            }
            final File file = (File) resolver.invoke(Aliases.FILE_CONTENT_FILE, content);
            final String name = file == null ? "unnamed" : file.getName();
            final long lastAutoBackup = (Long) resolver.invoke(
                Aliases.FILE_CONTENT_LAST_AUTO_BACKUP_TIME, content
            );
            final long lastSaved = (Long) resolver.invoke(
                Aliases.FILE_CONTENT_LAST_SAVED_TIME, content
            );
            final boolean modified = (Boolean) resolver.invoke(
                Aliases.FILE_CONTENT_MODIFIED_AFTER_SAVING, content
            );
            documents.add(new AutoBackupAdapter.Document(
                name, file, lastAutoBackup, lastSaved, modified
            ));
        }
        return List.copyOf(documents);
    }

    @Override
    public void triggerBackupNow() {
        requireResolvable(
            Aliases.MANAGER_CLASS, Aliases.MANAGER_INSTANCE, Aliases.ATTACH_PACK,
            Aliases.UPDATE, Aliases.APP_CONTROLLER_CLASS,
            Aliases.APP_CONTROLLER_GET_COMPLETE_PACK, Aliases.COMPLETE_PACK_CLASS,
            APP_INSTANCE
        );
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object pack = app == null
            ? null
            : resolver.invoke(Aliases.APP_CONTROLLER_GET_COMPLETE_PACK, app);
        if (pack == null) {
            throw new IllegalStateException(
                "auto-backup cannot trigger without an attached complete pack"
            );
        }
        // Idempotent attach: the host manager ignores a pack that is already mounted.
        final Object manager = manager();
        resolver.invoke(Aliases.ATTACH_PACK, manager, pack);
        resolver.invoke(Aliases.UPDATE, manager);
    }

    private Object manager() {
        return resolver.readStaticField(Aliases.MANAGER_INSTANCE);
    }

    private static boolean matches(
        final AutoBackupAdapter.Snapshot expected,
        final AutoBackupAdapter.Snapshot actual
    ) {
        return expected.enabled() == actual.enabled()
            && expected.intervalMinutes() == actual.intervalMinutes()
            && expected.maxMB() == actual.maxMB();
    }

    /**
     * Resolves every selector the operation may need BEFORE the first mutation.
     * A missing or drifted alias throws before any host state can change.
     */
    private void requireResolvable(final String... aliases) {
        for (String alias : aliases) {
            final StaticSelector selector = resolver.verifiedSelector(alias);
            switch (selector.kind()) {
                case METHOD -> {
                    if ((selector.requiredAccessFlags() & StaticSelector.ACCESS_STATIC) != 0) {
                        resolver.bindStatic(alias);
                    } else {
                        resolver.bind(alias);
                    }
                }
                case FIELD -> {
                    if ((selector.requiredAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
                        throw new IllegalStateException(
                            "instance-field alias is not supported by auto-backup operations: " + alias
                        );
                    }
                    resolver.readStaticField(alias);
                }
                case CONSTRUCTOR, CLASS -> {
                    try {
                        Class.forName(
                            selector.ownerInternalName().replace('/', '.'),
                            false,
                            resolver.hostClassLoader()
                        );
                    } catch (ClassNotFoundException | LinkageError failure) {
                        throw new IllegalStateException(
                            "verified host type cannot be loaded for alias " + alias, failure
                        );
                    }
                }
            }
        }
    }
}
