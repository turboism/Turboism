package dev.turboism.adapter.cubism.backup;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        static final String DOCUMENT_UID_MODELING = "cubism.auto-backup.document-uid.modeling";
        static final String SCENE_DOCS = "cubism.auto-backup.scene-docs";
        static final String DOCUMENT_UID_SCENE = "cubism.auto-backup.document-uid.scene";

        private Aliases() {
        }
    }

    static final String APP_INSTANCE = "cubism.auto-backup.app-controller.instance";

    /** Backup artifact timestamp pattern: {@code yyyy_MMdd_HHmmss} (host h() naming). */
    static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy_MMdd_HHmmss");

    /** Bounded retries for the backup file copy (the host may still hold the file). */
    static final int COPY_ATTEMPTS = 3;
    static final long COPY_RETRY_DELAY_MILLIS = 200L;

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

    @Override
    public File saveDocumentFor(
        final File matchFile, final List<String> documentUids, final long timestampMillis
    ) {
        Objects.requireNonNull(matchFile, "matchFile");
        Objects.requireNonNull(documentUids, "documentUids");
        requireResolvable(
            Aliases.DOCUMENT_UID_MODELING, Aliases.SCENE_DOCS, Aliases.DOCUMENT_UID_SCENE,
            Aliases.MANAGER_CLASS, Aliases.MANAGER_INSTANCE,
            Aliases.APP_CONTROLLER_CLASS, Aliases.APP_CONTROLLER_GET_COMPLETE_PACK,
            Aliases.COMPLETE_PACK_CLASS, Aliases.COMPLETE_PACK_FILE_CONTENTS,
            Aliases.FILE_CONTENT_CLASS, Aliases.FILE_CONTENT_FILE,
            APP_INSTANCE
        );
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object pack = app == null
            ? null
            : resolver.invoke(Aliases.APP_CONTROLLER_GET_COMPLETE_PACK, app);
        if (pack == null) {
            throw new IllegalStateException(
                "auto-backup save-triggered backup requires an attached complete pack"
            );
        }
        final List<?> contents = (List<?>) resolver.invoke(Aliases.COMPLETE_PACK_FILE_CONTENTS, pack);
        if (contents == null) {
            return null;
        }
        if (!documentUids.isEmpty()) {
            // UID matching first: the saved snapshot carries stable document UIDs
            // (host getDocumentUID), while the pack file base name can differ
            // (e.g. the runner renames the fixture). First UID hit wins.
            for (Object content : contents) {
                if (content == null) {
                    continue;
                }
                if (uidMatches(content, documentUids)) {
                    return copyBackupFile(content, timestampMillis);
                }
            }
        }
        // Fallback: name/path matching (also used when the saved UID list is empty).
        for (Object content : contents) {
            if (content == null) {
                continue;
            }
            final File file = (File) resolver.invoke(Aliases.FILE_CONTENT_FILE, content);
            if (!matches(file, matchFile)) {
                continue;
            }
            return copyBackupFile(content, timestampMillis);
        }
        return null; // no match: the coordinator fails closed
    }

    /**
     * UID matching for one pack file content: modeling compares its own
     * {@code getDocumentUID}, animation compares each scene's UID from
     * {@code getSceneDocs}; game-data has no UID mapping and never matches here.
     */
    private boolean uidMatches(final Object content, final List<String> documentUids) {
        if (isOwnerInstance(Aliases.DOCUMENT_UID_MODELING, content)) {
            final String uid = (String) resolver.invoke(Aliases.DOCUMENT_UID_MODELING, content);
            return uid != null && documentUids.contains(uid);
        }
        if (isOwnerInstance(Aliases.SCENE_DOCS, content)) {
            final Object sceneDocs = resolver.invoke(Aliases.SCENE_DOCS, content);
            if (sceneDocs instanceof Iterable<?> iterable) {
                for (Object scene : iterable) {
                    if (scene == null) {
                        continue;
                    }
                    final String uid = (String) resolver.invoke(Aliases.DOCUMENT_UID_SCENE, scene);
                    if (uid != null && documentUids.contains(uid)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Silent-by-construction backup: by the time the save hook fires the host
     * has already written the document to its original file, so the backup is
     * a pure file copy — no saveDocument, no host UI, no reference switch. The
     * artifact is a TEMPORARY file (created under a {@code turboism-backup-}
     * temp directory with the regular {@code <name>_backup<ts>.<ext>} name):
     * the plugin uploads it and deletes it, so no backup file ever remains in
     * the host backup directory. The copy retries briefly because the host may
     * still hold the file; any failure fails closed.
     */
    private File copyBackupFile(final Object content, final long timestampMillis) {
        final File source = (File) resolver.invoke(Aliases.FILE_CONTENT_FILE, content);
        if (source == null || !source.isFile()) {
            throw new IllegalStateException(
                "auto-backup source file is unavailable: " + (source == null ? "<null>" : source)
            );
        }
        final String name = source.getName();
        final String base = baseName(name);
        final String ext = extension(name);
        final String stamp = BACKUP_TIMESTAMP.format(
            Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
        );
        try {
            final Path tempDir = Files.createTempDirectory("turboism-backup-");
            final Path target = tempDir.resolve(
                base + "_backup" + stamp + (ext.isEmpty() ? "" : "." + ext)
            );
            IOException last = null;
            for (int attempt = 1; attempt <= COPY_ATTEMPTS; attempt++) {
                try {
                    Files.copy(
                        source.toPath(), target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES
                    );
                    return target.toFile();
                } catch (IOException failure) {
                    last = failure;
                    if (attempt < COPY_ATTEMPTS) {
                        try {
                            Thread.sleep(COPY_RETRY_DELAY_MILLIS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("auto-backup copy interrupted", interrupted);
                        }
                    }
                }
            }
            throw new IllegalStateException(
                "auto-backup copy failed: " + source + " -> " + target, last
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                "auto-backup temp backup directory unavailable", failure
            );
        }
    }

    private boolean isOwnerInstance(final String alias, final Object content) {
        final StaticSelector selector = resolver.verifiedSelector(alias);
        try {
            final Class<?> owner = Class.forName(
                selector.ownerInternalName().replace('/', '.'),
                false,
                resolver.hostClassLoader()
            );
            return owner.isInstance(content);
        } catch (ClassNotFoundException | LinkageError failure) {
            throw new IllegalStateException(
                "verified saveDocument owner cannot be loaded for alias " + alias, failure
            );
        }
    }

    private static boolean matches(final File contentFile, final File matchFile) {
        if (contentFile == null) {
            return false;
        }
        final String contentName = contentFile.getName();
        final String matchName = matchFile.getName();
        if (contentName.equalsIgnoreCase(matchName)) {
            return true;
        }
        if (baseName(contentName).equalsIgnoreCase(baseName(matchName))) {
            return true;
        }
        final String contentPath = normalize(contentFile.getPath());
        final String matchPath = normalize(matchFile.getPath());
        return contentPath.endsWith(matchPath) || matchPath.endsWith(contentPath);
    }

    private static String baseName(final String name) {
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(final String name) {
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    private static String normalize(final String value) {
        return value.replace('\\', '/').toLowerCase(Locale.ROOT);
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
