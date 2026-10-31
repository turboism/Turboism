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
        /** Files replaced, but backups retained until a successful runtime startup confirms the new agent. */
        AWAITING_HEALTH,
        /** A previous interrupted run was rolled back; nothing new was applied. */
        RECOVERED,
        /** The update failed and the previous installation was restored. */
        ROLLED_BACK,
        /** No journal and nothing to do. */
        NOTHING
    }

    record Result(Outcome outcome, String detail) {}

    /**
     * Restoring the previous installation did not fully succeed (typically a locked file). The journal and backups
     * are kept; the installation may be mixed until {@link #recover} succeeds, e.g. on the next start.
     */
    static final class IncompleteRollbackException extends IOException {
        IncompleteRollbackException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

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
        FrameworkUpdateCache.installationAncestors(work);
        final Path journalPath = work.resolve(JOURNAL);
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) return new Result(Outcome.NOTHING, "no journal");
        final ObjectNode journal = readJournal(journalPath);
        if (journal.has("root") && !root.toString().equals(journal.path("root").asText())) {
            throw new IOException("Update journal belongs to a different installation");
        }
        final String state = journal.path("state").asText();
        if (state.equals("PREPARED")) {
            // No installation mutation occurs until APPLYING. Discard possibly partial backup copies.
            cleanup();
            return new Result(Outcome.RECOVERED, "discarded preparation; installation unchanged");
        }
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
        return apply(manifest, staged, installedBuildJson, false, null, null);
    }

    /** Retain backups until a subsequent startup confirms the exact installed agent. */
    Result applyPendingHealth(final UpdateManifest manifest, final Path staged, final byte[] installedBuildJson)
            throws IOException, InterruptedException {
        return apply(manifest, staged, installedBuildJson, true, null, null);
    }

    /** Production application requires independently reloaded old ownership and exact signed new receipts. */
    Result applyAuthenticated(final FrameworkUpdateReceipt.Prepared receipt,
            final FrameworkInstallationReceiptLoader.Evidence previous, final Path staged,
            final byte[] installedBuildJson) throws IOException, InterruptedException {
        if (!root.equals(receipt.root()) || !root.equals(previous.root())) {
            throw new IOException("Receipt installation binding differs from apply root");
        }
        return apply(receipt.manifest(), staged, installedBuildJson, true, receipt, previous.hashes());
    }

    private Result apply(final UpdateManifest manifest, final Path staged, final byte[] installedBuildJson,
            final boolean requireHealth, final FrameworkUpdateReceipt.Prepared receipt,
            final Map<String, String> previousHashes) throws IOException, InterruptedException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(staged, "staged");
        Objects.requireNonNull(installedBuildJson, "installedBuildJson");
        final Result previous = recover();
        if (previous.outcome() == Outcome.RECOVERED) {
            // An earlier crash was restored; start clean rather than layering a new update on a repaired tree.
            faults.hit("after-recover");
        }
        Files.createDirectories(work);
        final List<Step> steps = prepare(manifest, staged.toAbsolutePath().normalize(), previousHashes);
        if (receipt != null) receipt.publishDocuments();
        final Path backupDir = work.resolve("backup");
        final ObjectNode journal = UpdateFileSupport.JSON.createObjectNode();
        journal.put("format", JOURNAL_FORMAT);
        journal.put("state", "PREPARED");
        journal.put("version", manifest.version());
        journal.put("root", root.toString());
        if (requireHealth) {
            final UpdateManifestEntry agent = manifest.entries().stream()
                    .filter(e -> e.relativePath().equals("turboism-agent.jar"))
                    .findFirst().orElseThrow(() -> new IOException("Health check requires an agent entry"));
            journal.put("agentSha256", agent.sha256());
            journal.put("agentBytes", agent.bytes());
        }
        final Path identity = root.resolve(InstalledBuild.METADATA_FILE_NAME);
        final BasicFileAttributes identityAttributes = FrameworkUpdateCache.attributes(identity);
        if (identityAttributes != null && (!identityAttributes.isRegularFile() || identityAttributes.isSymbolicLink()
                || identityAttributes.isOther() || identityAttributes.size() > 16384)) {
            throw new IOException("installed-build.json is not a plain file");
        }
        journal.put("identityExisted", identityAttributes != null);
        if (receipt != null) {
            final var oldIndex = FrameworkUpdateCache.attributes(receipt.indexPath());
            if (oldIndex == null || !oldIndex.isRegularFile() || oldIndex.isSymbolicLink()
                    || oldIndex.isOther() || oldIndex.size() > 4096) {
                throw new IOException("Previously authenticated receipt index is missing or unsafe");
            }
            journal.put("receiptTracked", true);
        }
        final ArrayNode files = journal.putArray("files");
        for (final Step step : steps) {
            final ObjectNode node = files.addObject();
            node.put("path", step.relative);
            node.put("sha256", step.entry.sha256());
            node.put("existed", step.existed);
        }
        long required = 16L * 1024 * 1024 + (identityAttributes == null ? 0 : identityAttributes.size());
        for (final Step step : steps) {
            if (step.existed) required = Math.addExact(required, Files.size(step.target));
            if (!step.retired) required = Math.addExact(required, step.entry.bytes());
        }
        final var store = Files.getFileStore(root);
        if (!store.equals(Files.getFileStore(work)) || store.getUsableSpace() < required) {
            throw new IOException("Atomic apply needs a same-filesystem work directory and space for payload plus backups");
        }
        writeJournal(journal);
        try {
            faults.hit("after-prepared");
            FrameworkUpdateCache.installationAncestors(backupDir);
            Files.createDirectories(backupDir);
            if (receipt != null) {
                final Path receiptBackup = backupDir.resolve("receipt-index.json");
                Files.copy(receipt.indexPath(), receiptBackup, StandardCopyOption.REPLACE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS);
                force(receiptBackup);
            }
            if (identityAttributes != null) {
                final Path identityBackup = backupDir.resolve(InstalledBuild.METADATA_FILE_NAME);
                Files.copy(identity, identityBackup, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                force(identityBackup);
            }
            for (final Step step : steps) {
                if (!step.existed) continue;
                faults.hit("before-backup:" + step.relative);
                final Path backup = backupDir.resolve(step.relative);
                FrameworkUpdateCache.installationAncestors(backup.getParent());
                Files.createDirectories(backup.getParent());
                verifyPrevious(step);
                Files.copy(step.target, backup, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                force(backup);
                if (step.previousHash != null && !FrameworkUpdateCache.hash(backup, Files.size(backup))
                        .equals(step.previousHash)) throw new IOException("Old payload changed during backup");
            }
            setState(journal, "APPLYING");
            for (final Step step : steps) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Update cancelled");
                faults.hit("before-replace:" + step.relative);
                replace(step);
                faults.hit("after-replace:" + step.relative);
            }
            if (receipt != null) {
                faults.hit("before-receipt-index");
                writeAtomic(receipt.indexPath(), receipt.index());
                faults.hit("after-receipt-index");
            }
            faults.hit("before-installed-build");
            writeAtomic(root.resolve(InstalledBuild.METADATA_FILE_NAME), installedBuildJson);
            setState(journal, requireHealth ? "AWAITING_HEALTH" : "COMMITTED");
            faults.hit("after-commit");
        } catch (IOException | RuntimeException | InterruptedException failure) {
            try {
                if (!"PREPARED".equals(journal.path("state").asText())) restore(journal);
                cleanup();
            } catch (IOException rollbackFailure) {
                // Leave the journal in place: the next start retries the restore from durable state.
                final var incomplete = new IncompleteRollbackException(
                        "Update failed (" + failure + ") and the previous installation could not be fully restored",
                        failure);
                incomplete.addSuppressed(rollbackFailure);
                throw incomplete;
            }
            if (failure instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            return new Result(Outcome.ROLLED_BACK, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        if (!requireHealth) cleanup();
        return new Result(requireHealth ? Outcome.AWAITING_HEALTH : Outcome.APPLIED, manifest.version());
    }

    boolean hasTrackedReceipt() throws IOException {
        final Path journal = work.resolve(JOURNAL);
        return Files.exists(journal, LinkOption.NOFOLLOW_LINKS)
                && readJournal(journal).path("receiptTracked").asBoolean(false);
    }

    /** Called only after runtime startup succeeds, outside the EDT. Does not mutate loaded payload files. */
    boolean confirmHealthy(final String loadedVersion, final Path loadedAgent)
            throws IOException, InterruptedException {
        final Path journalPath = work.resolve(JOURNAL);
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) return false;
        final ObjectNode journal = readJournal(journalPath);
        if (!java.util.Set.of("AWAITING_HEALTH", "STARTING_HEALTH")
                .contains(journal.path("state").asText())) return false;
        if (!root.toString().equals(journal.path("root").asText())
                || !loadedVersion.equals(journal.path("version").asText())
                || !root.resolve("turboism-agent.jar").equals(loadedAgent.toAbsolutePath().normalize())) {
            throw new IOException("Health confirmation identity mismatch");
        }
        final long bytes = journal.path("agentBytes").asLong(-1);
        if (bytes <= 0 || bytes > UpdateManifest.MAX_FILE_BYTES
                || !FrameworkUpdateCache.hash(loadedAgent, bytes).equals(journal.path("agentSha256").asText())) {
            throw new IOException("Health confirmation agent hash mismatch");
        }
        setState(journal, "COMMITTED");
        cleanup();
        return true;
    }

    /** External launcher calls this before starting Cubism. One startup attempt must confirm health. */
    Result prepareLaunch(final long launcherPid) throws IOException {
        final Path journalPath = work.resolve(JOURNAL);
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) return new Result(Outcome.NOTHING, "no journal");
        final ObjectNode journal = readJournal(journalPath);
        if (!root.toString().equals(journal.path("root").asText())) {
            throw new IOException("Startup journal belongs to another installation");
        }
        if ("AWAITING_HEALTH".equals(journal.path("state").asText())) {
            final var launcher = ProcessHandle.of(launcherPid).filter(ProcessHandle::isAlive)
                    .orElseThrow(() -> new IOException("Launcher process is not running"));
            final var started = launcher.info().startInstant()
                    .orElseThrow(() -> new IOException("Launcher process identity unavailable"));
            journal.put("launcherPid", launcherPid);
            journal.put("launcherStarted", started.toString());
            setState(journal, "STARTING_HEALTH");
            return new Result(Outcome.NOTHING, "first startup awaiting health confirmation");
        }
        if ("STARTING_HEALTH".equals(journal.path("state").asText())) {
            final var previous = ProcessHandle.of(journal.path("launcherPid").asLong(-1));
            if (previous.isPresent() && previous.get().isAlive()) {
                final var started = previous.get().info().startInstant();
                if (started.isEmpty() || started.get().toString().equals(journal.path("launcherStarted").asText())) {
                    throw new IOException("Previous launch still running; recovery must wait for exit");
                }
            }
        }
        return recover();
    }

    // ---------------------------------------------------------------- preparation

    private record Step(String relative, UpdateManifestEntry entry, Path target, Path source, boolean existed,
            String previousHash, boolean retired) {}

    private List<Step> prepare(final UpdateManifest manifest, final Path staged, final Map<String, String> previousHashes)
            throws IOException, InterruptedException {
        final Map<String, Step> byLowerPath = new LinkedHashMap<>();
        final java.util.Set<String> targetPaths = new java.util.HashSet<>();
        if (previousHashes != null) {
            for (final var old : previousHashes.entrySet()) verifyPrevious(root.resolve(old.getKey()), old.getValue());
        }
        for (final UpdateManifestEntry entry : manifest.entries()) {
            final String relative = entry.relativePath();
            if (!targetPaths.add(relative.toLowerCase(java.util.Locale.ROOT))) {
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
            final String previousHash = previousHashes == null ? null : previousHashes.get(relative);
            if (existing != null && previousHashes != null && previousHash == null) {
                throw new IOException("Existing file is not owned by the authenticated old receipt: " + relative);
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
            byLowerPath.put(relative.toLowerCase(java.util.Locale.ROOT),
                    new Step(relative, entry, target, source, existing != null, previousHash, false));
        }
        if (previousHashes != null) {
            for (final var old : previousHashes.entrySet()) {
                if (targetPaths.contains(old.getKey().toLowerCase(java.util.Locale.ROOT))) continue;
                final Path target = root.resolve(old.getKey()).normalize();
                requirePlainDirectories(target.getParent());
                final var oldEntry = new UpdateManifestEntry(old.getKey(), Files.size(target), old.getValue(),
                        "sha256:" + old.getValue());
                byLowerPath.put(old.getKey().toLowerCase(java.util.Locale.ROOT),
                        new Step(old.getKey(), oldEntry, target, null, true, old.getValue(), true));
            }
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

    private void verifyPrevious(final Step step) throws IOException, InterruptedException {
        if (step.previousHash != null) verifyPrevious(step.target, step.previousHash);
        else if (!step.existed && FrameworkUpdateCache.attributes(step.target) != null) {
            throw new IOException("A new target appeared during application: " + step.relative);
        }
    }

    private void verifyPrevious(final Path path, final String expected) throws IOException, InterruptedException {
        requirePlainDirectories(path.getParent());
        final var attributes = FrameworkUpdateCache.attributes(path);
        if (attributes == null || !attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || attributes.size() > UpdateManifest.MAX_FILE_BYTES
                || !FrameworkUpdateCache.hash(path, attributes.size()).equals(expected)) {
            throw new IOException("Installed file differs from authenticated old receipt: " + path.getFileName());
        }
    }

    private void replace(final Step step) throws IOException, InterruptedException {
        verifyPrevious(step);
        if (step.retired) {
            Files.delete(step.target);
            return;
        }
        Files.createDirectories(step.target.getParent());
        requirePlainDirectories(step.target.getParent());
        final Path prepared = work.resolve("prepared");
        FrameworkUpdateCache.installationAncestors(prepared);
        Files.createDirectories(prepared);
        final Path temporary = Files.createTempFile(prepared, "payload-", ".new");
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
        final List<IOException> failures = new ArrayList<>();
        // Restore in reverse so a partially applied tail is undone first. One locked file must not stop the others
        // from being restored, so every file is attempted and failures are reported together.
        for (int i = files.size() - 1; i >= 0; i--) {
            final String relative = files.get(i).path("path").asText();
            final boolean existed = files.get(i).path("existed").asBoolean();
            try {
                final Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root) || target.equals(root)) {
                    throw new IOException("Journal path escapes installation");
                }
                if (existed) {
                    restoreFile(journal, backupDir.resolve(relative), target, relative);
                } else {
                    Files.deleteIfExists(target);
                }
            } catch (IOException failure) {
                failures.add(new IOException("Could not restore " + relative + ": " + failure, failure));
            }
        }
        // installed-build.json is only ever written after every file, so restoring files without it is consistent,
        // but a rollback after it was written must restore the previous identity too.
        try {
            final Path previousIdentity = backupDir.resolve(InstalledBuild.METADATA_FILE_NAME);
            final Path identity = root.resolve(InstalledBuild.METADATA_FILE_NAME);
            if (Files.isRegularFile(previousIdentity, LinkOption.NOFOLLOW_LINKS)) {
                restoreFile(journal, previousIdentity, identity, InstalledBuild.METADATA_FILE_NAME);
            } else if (!journal.path("identityExisted").asBoolean(true)) {
                Files.deleteIfExists(identity);
            }
        } catch (IOException failure) {
            failures.add(new IOException("Could not restore installed-build.json: " + failure, failure));
        }
        if (journal.path("receiptTracked").asBoolean(false)) {
            try {
                final Path index = root.resolve(FrameworkInstallationReceiptLoader.STATE).resolve("installed-receipt.json");
                restoreFile(journal, backupDir.resolve("receipt-index.json"), index, "installed-receipt.json");
            } catch (IOException failure) {
                failures.add(new IOException("Could not restore installed receipt index", failure));
            }
        }
        if (!failures.isEmpty()) {
            final var all = new IOException(failures.size() + " file(s) could not be restored");
            failures.forEach(all::addSuppressed);
            throw all;
        }
    }

    private void restoreFile(final ObjectNode journal, final Path backup, final Path target, final String relative)
            throws IOException {
        FrameworkUpdateCache.installationAncestors(backup.getParent());
        requirePlainDirectories(target.getParent());
        if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
            // Backup not yet taken means the original was never replaced (backups precede replacement).
            if (!journal.path("state").asText().equals("PREPARED")) {
                throw new IOException("Backup missing during rollback: " + relative);
            }
            return;
        }
        // A file whose bytes already equal the backup was never changed (or is already restored): leave it alone.
        // This matters on Windows, where touching a still-locked, unchanged file would fail needlessly.
        final BasicFileAttributes current = FrameworkUpdateCache.attributes(target);
        if (current != null && current.isRegularFile() && current.size() == Files.size(backup)) {
            try {
                if (FrameworkUpdateCache.hash(target, current.size()).equals(FrameworkUpdateCache.hash(backup, current.size()))) {
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while verifying " + relative, interrupted);
            }
        }
        Files.createDirectories(target.getParent());
        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        force(target);
    }

    private void cleanup() throws IOException {
        deleteTree(work.resolve("backup"));
        deleteTree(work.resolve("prepared"));
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
        FrameworkUpdateCache.installationAncestors(work);
        final BasicFileAttributes a = FrameworkUpdateCache.attributes(path);
        if (a == null || !a.isRegularFile() || a.size() > 1024 * 1024) throw new IOException("Invalid journal");
        final var parsed = UpdateFileSupport.JSON.readTree(Files.readAllBytes(path));
        if (!(parsed instanceof ObjectNode object) || !JOURNAL_FORMAT.equals(object.path("format").asText())) {
            throw new IOException("Unrecognized update journal");
        }
        if (!java.util.Set.of("PREPARED", "APPLYING", "AWAITING_HEALTH", "STARTING_HEALTH", "COMMITTED")
                .contains(object.path("state").asText())
                || !object.path("files").isArray()
                || object.path("files").size() > 2 * UpdateManifest.MAX_ENTRIES
                || object.has("receiptTracked") && !object.path("receiptTracked").isBoolean()) {
            throw new IOException("Invalid journal state or file inventory");
        }
        final var paths = new java.util.HashSet<String>();
        for (final var entry : object.path("files")) {
            final String relative = entry.path("path").asText();
            final String hash = entry.path("sha256").asText();
            try {
                new UpdateManifestEntry(relative, 1, hash, "sha256:" + hash);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Journal names a non-framework path", invalid);
            }
            if (!entry.path("existed").isBoolean()
                    || !paths.add(relative.toLowerCase(java.util.Locale.ROOT))) {
                throw new IOException("Invalid or duplicate journal entry");
            }
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
        final Path temporary = Files.createTempFile(target.getParent(), ".update-", ".writing");
        try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            force(temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Atomic replacement unavailable", unsupported);
        } finally {
            Files.deleteIfExists(temporary);
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
