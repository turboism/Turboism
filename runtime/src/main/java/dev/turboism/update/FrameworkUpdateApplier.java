package dev.turboism.update;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Journaled, rollback-capable application of an already authenticated framework update to an installation root.
 *
 * <p>The applier is deliberately independent of the running application: it uses only the JDK and this package's
 * file helpers, so the same code runs inside the external helper process after Cubism has exited. It never decides
 * <em>whether</em> to update. Callers must pass a {@link UpdateManifest} produced by {@link UpdateManifestVerifier}
 * and staged bytes whose hashes are re-checked here immediately before use.
 *
 * <h2>Transaction</h2>
 *
 * <ol>
 *   <li>Validate every target path and re-hash every staged file against the signed manifest. Nothing is changed
 *       yet; a failure here leaves the installation untouched.
 *   <li>Write {@code journal.json} (state {@code PREPARED}) listing, per file, the target, the new hash and whether
 *       an old file exists, and fsync it.
 *   <li>Back up each existing target into {@code backup/} by copying (the original stays in place until replaced).
 *   <li>Mark {@code APPLYING}, then replace files one by one with an atomic move of a same-directory temp copy.
 *   <li>Write {@code installed-build.json} last, then mark {@code COMMITTED}, then delete backups and the journal.
 * </ol>
 *
 * <p>If any step fails, or a previous run left a journal that is not {@code COMMITTED}, {@link #recover} restores
 * every file from the backup (and deletes files that did not exist before). A journal in {@code COMMITTED} only
 * needs cleanup. Every journal write is atomic, so a crash leaves either the previous or the next state.
 *
 * <p>Out of scope by design: user configuration, projects and plugins are never in the manifest and are never
 * touched. The applier refuses paths outside the framework-owned allowlist enforced by {@link UpdateManifestEntry}.
 */
final class FrameworkUpdateApplier {
    static final String JOURNAL = "journal.json";
    static final String JOURNAL_FORMAT = "turboism-update-journal-v1";

    /** Test seam: invoked at named points so tests can simulate a crash or lock at any step. */
    @FunctionalInterface
    interface Faults {
        void hit(String point) throws IOException;
    }

    enum Outcome {
        /** Update applied, journal removed. */
        APPLIED,
        /** A previous interrupted run was rolled back; nothing new was applied. */
        RECOVERED,
        /** The update failed and the previous installation was restored. */
        ROLLED_BACK,
        /** No journal and nothing to do. */
        NOTHING
    }

    record Result(Outcome outcome, String detail) {}

    private final Path root;
    private final Path work;
    private final Faults faults;

    /**
     * @param root installation root
     * @param work private directory for journal and backups; must not be inside any path the manifest can name
     */
    FrameworkUpdateApplier(final Path root, final Path work) {
        this(root, work, point -> {});
    }

    FrameworkUpdateApplier(final Path root, final Path work, final Faults faults) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.work = Objects.requireNonNull(work, "work").toAbsolutePath().normalize();
        this.faults = Objects.requireNonNull(faults, "faults");
        if (this.work.startsWith(this.root) && !this.work.startsWith(this.root.resolve("state"))) {
            throw new IllegalArgumentException("Work directory must be outside framework-owned paths");
        }
    }

    /** Restore any interrupted transaction. Safe to call at every start and idempotent. */
    Result recover() throws IOException {
        final Path journalPath = work.resolve(JOURNAL);
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) return new Result(Outcome.NOTHING, "no journal");
        final ObjectNode journal = readJournal(journalPath);
        final String state = journal.path("state").asText();
        if (state.equals("COMMITTED")) {
            cleanup();
            return new Result(Outcome.APPLIED, "finished cleanup of committed update");
        }
        restore(journal);
        cleanup();
        return new Result(Outcome.RECOVERED, "rolled back interrupted update from state " + state);
    }

    /**
     * Apply {@code manifest} using bytes staged under {@code staged} (keyed by manifest relative path).
     *
     * @param installedBuildJson exact bytes for installed-build.json, written last
     */
    Result apply(final UpdateManifest manifest, final Path staged, final byte[] installedBuildJson)
            throws IOException, InterruptedException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(staged, "staged");
        Objects.requireNonNull(installedBuildJson, "installedBuildJson");
        final Result previous = recover();
        if (previous.outcome() == Outcome.RECOVERED) {
            // An earlier crash was restored; start clean rather than layering a new update on a repaired tree.
            faults.hit("after-recover");
        }
        Files.createDirectories(work);
        final List<Step> steps = prepare(manifest, staged.toAbsolutePath().normalize());
        final Path backupDir = work.resolve("backup");
        final ObjectNode journal = UpdateFileSupport.JSON.createObjectNode();
        journal.put("format", JOURNAL_FORMAT);
        journal.put("state", "PREPARED");
        journal.put("version", manifest.version());
        final Path identity = root.resolve(InstalledBuild.METADATA_FILE_NAME);
        final BasicFileAttributes identityAttributes = FrameworkUpdateCache.attributes(identity);
        if (identityAttributes != null && (!identityAttributes.isRegularFile() || identityAttributes.isSymbolicLink())) {
            throw new IOException("installed-build.json is not a plain file");
        }
        journal.put("identityExisted", identityAttributes != null);
        final ArrayNode files = journal.putArray("files");
        for (final Step step : steps) {
            final ObjectNode node = files.addObject();
            node.put("path", step.relative);
            node.put("sha256", step.entry.sha256());
            node.put("existed", step.existed);
        }
        writeJournal(journal);
        try {
            faults.hit("after-prepared");
            Files.createDirectories(backupDir);
            if (identityAttributes != null) {
                final Path identityBackup = backupDir.resolve(InstalledBuild.METADATA_FILE_NAME);
                Files.copy(identity, identityBackup, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                force(identityBackup);
            }
            for (final Step step : steps) {
                if (!step.existed) continue;
                faults.hit("before-backup:" + step.relative);
                final Path backup = backupDir.resolve(step.relative);
                Files.createDirectories(backup.getParent());
                Files.copy(step.target, backup, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                force(backup);
            }
            setState(journal, "APPLYING");
            for (final Step step : steps) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Update cancelled");
                faults.hit("before-replace:" + step.relative);
                replace(step);
                faults.hit("after-replace:" + step.relative);
            }
            faults.hit("before-installed-build");
            writeAtomic(root.resolve(InstalledBuild.METADATA_FILE_NAME), installedBuildJson);
            setState(journal, "COMMITTED");
            faults.hit("after-commit");
        } catch (IOException | RuntimeException | InterruptedException failure) {
            try {
                restore(journal);
                cleanup();
            } catch (IOException rollbackFailure) {
                // Leave the journal in place: the next start retries the restore from durable state.
                failure.addSuppressed(rollbackFailure);
                throw failure;
            }
            if (failure instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            return new Result(Outcome.ROLLED_BACK, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        cleanup();
        return new Result(Outcome.APPLIED, manifest.version());
    }

    // ---------------------------------------------------------------- preparation

    private record Step(String relative, UpdateManifestEntry entry, Path target, Path source, boolean existed) {}

    private List<Step> prepare(final UpdateManifest manifest, final Path staged)
            throws IOException, InterruptedException {
        final Map<String, Step> byLowerPath = new LinkedHashMap<>();
        for (final UpdateManifestEntry entry : manifest.entries()) {
            final String relative = entry.relativePath();
            if (byLowerPath.put(relative.toLowerCase(java.util.Locale.ROOT), null) != null) {
                throw new IOException("Duplicate or case-colliding manifest path: " + relative);
            }
            final Path target = root.resolve(relative).normalize();
            final Path source = staged.resolve(relative).normalize();
            if (!target.startsWith(root) || target.equals(root)) throw new IOException("Path escapes installation");
            if (!source.startsWith(staged)) throw new IOException("Staged path escapes staging directory");
            requirePlainDirectories(target.getParent());
            final BasicFileAttributes existing = FrameworkUpdateCache.attributes(target);
            if (existing != null && (!existing.isRegularFile() || existing.isSymbolicLink() || existing.isOther())) {
                throw new IOException("Existing target is not a plain file: " + relative);
            }
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                // Nothing staged: legitimate only for a file that is already exactly the signed bytes. The stage
                // omits unchanged files, but "omitted" must be proven by hash here, never assumed.
                if (existing == null
                        || existing.size() != entry.bytes()
                        || !FrameworkUpdateCache.hash(target, entry.bytes()).equals(entry.sha256())) {
                    throw new IOException("Staged file missing and installed copy differs: " + relative);
                }
                byLowerPath.remove(relative.toLowerCase(java.util.Locale.ROOT));
                continue;
            }
            requirePlainFile(source, entry.bytes());
            if (!FrameworkUpdateCache.hash(source, entry.bytes()).equals(entry.sha256())) {
                throw new IOException("Staged bytes do not match signed manifest: " + relative);
            }
            byLowerPath.put(relative.toLowerCase(java.util.Locale.ROOT), new Step(relative, entry, target, source, existing != null));
        }
        return new ArrayList<>(byLowerPath.values());
    }

    private void requirePlainDirectories(final Path directory) throws IOException {
        // Create missing framework directories (e.g. graal/lib) but refuse links anywhere on the way down.
        Path current = root;
        final Path relative = root.relativize(directory);
        final BasicFileAttributes rootAttributes = FrameworkUpdateCache.attributes(root);
        if (rootAttributes == null || !rootAttributes.isDirectory() || rootAttributes.isSymbolicLink()) {
            throw new IOException("Installation root is not a plain directory");
        }
        for (final Path part : relative) {
            current = current.resolve(part);
            final BasicFileAttributes a = FrameworkUpdateCache.attributes(current);
            if (a == null) continue;
            if (!a.isDirectory() || a.isSymbolicLink() || a.isOther()) {
                throw new IOException("Unsafe installation directory: " + current);
            }
        }
    }

    private static void requirePlainFile(final Path path, final long expectedBytes) throws IOException {
        final BasicFileAttributes a = FrameworkUpdateCache.attributes(path);
        if (a == null || !a.isRegularFile() || a.isSymbolicLink() || a.isOther() || a.size() != expectedBytes) {
            throw new IOException("Staged file missing, linked or wrong size: " + path.getFileName());
        }
    }

    // ---------------------------------------------------------------- mutation

    private void replace(final Step step) throws IOException, InterruptedException {
        Files.createDirectories(step.target.getParent());
        requirePlainDirectories(step.target.getParent());
        final Path temporary = step.target.resolveSibling(step.target.getFileName() + ".turboism-new");
        Files.deleteIfExists(temporary);
        try (InputStream in = Files.newInputStream(step.source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        force(temporary);
        // Re-hash the copy that will actually be installed: guards against corruption between staging and now.
        if (!FrameworkUpdateCache.hash(temporary, step.entry.bytes()).equals(step.entry.sha256())) {
            Files.deleteIfExists(temporary);
            throw new IOException("Copied bytes differ from signed manifest: " + step.relative);
        }
        try {
            Files.move(temporary, step.target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic replacement unavailable for " + step.relative, unsupported);
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure; // typically a locked file on Windows: fail, then roll back
        }
    }

    private void restore(final ObjectNode journal) throws IOException {
        final Path backupDir = work.resolve("backup");
        final var files = journal.path("files");
        // Restore in reverse so a partially applied tail is undone first.
        for (int i = files.size() - 1; i >= 0; i--) {
            final String relative = files.get(i).path("path").asText();
            final boolean existed = files.get(i).path("existed").asBoolean();
            final Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || target.equals(root)) throw new IOException("Journal path escapes installation");
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".turboism-new"));
            if (existed) {
                final Path backup = backupDir.resolve(relative);
                if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                    // Backup not yet taken means the original was never replaced (backups precede replacement).
                    if (journal.path("state").asText().equals("APPLYING")) {
                        throw new IOException("Backup missing during rollback: " + relative);
                    }
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                force(target);
            } else {
                Files.deleteIfExists(target);
            }
        }
        // installed-build.json is only ever written after every file, so restoring files without it is consistent,
        // but a rollback after it was written must restore the previous identity too.
        final Path previousIdentity = backupDir.resolve(InstalledBuild.METADATA_FILE_NAME);
        final Path identity = root.resolve(InstalledBuild.METADATA_FILE_NAME);
        if (Files.isRegularFile(previousIdentity, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(previousIdentity, identity, StandardCopyOption.REPLACE_EXISTING);
        } else if (!journal.path("identityExisted").asBoolean(true)) {
            Files.deleteIfExists(identity);
        }
    }

    private void cleanup() throws IOException {
        deleteTree(work.resolve("backup"));
        Files.deleteIfExists(work.resolve(JOURNAL));
    }

    private static void deleteTree(final Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = Files.walk(directory)) {
            final List<Path> paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
            for (final Path path : paths) Files.deleteIfExists(path);
        }
    }

    // ---------------------------------------------------------------- journal

    private ObjectNode readJournal(final Path path) throws IOException {
        final BasicFileAttributes a = FrameworkUpdateCache.attributes(path);
        if (a == null || !a.isRegularFile() || a.size() > 1024 * 1024) throw new IOException("Invalid journal");
        final var parsed = UpdateFileSupport.JSON.readTree(Files.readAllBytes(path));
        if (!(parsed instanceof ObjectNode object) || !JOURNAL_FORMAT.equals(object.path("format").asText())) {
            throw new IOException("Unrecognized update journal");
        }
        return object;
    }

    private void writeJournal(final ObjectNode journal) throws IOException {
        writeAtomic(work.resolve(JOURNAL), UpdateFileSupport.JSON.writeValueAsBytes(journal));
    }

    private void setState(final ObjectNode journal, final String state) throws IOException {
        journal.put("state", state);
        faults.hit("before-journal:" + state);
        writeJournal(journal);
    }

    private static void writeAtomic(final Path target, final byte[] bytes) throws IOException {
        final Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        force(temporary);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Atomic replacement unavailable", unsupported);
        }
    }

    private static void force(final Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (NoSuchFileException missing) {
            throw missing;
        }
    }

    /** Describe the staged tree for logs; never used for decisions. */
    static Optional<String> describe(final Path work) {
        try {
            final Path journal = work.resolve(JOURNAL);
            if (!Files.isRegularFile(journal)) return Optional.empty();
            return Optional.of(new String(Files.readAllBytes(journal), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }
}
