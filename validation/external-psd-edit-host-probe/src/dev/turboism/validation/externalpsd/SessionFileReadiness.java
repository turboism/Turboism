package dev.turboism.validation.externalpsd;

import static dev.turboism.validation.externalpsd.PsdStructureInspection.sha256;
import static dev.turboism.validation.externalpsd.TargetResolution.remainingMillis;

import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.id.RawImageId;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Readiness and identity protocol for the PSD file the host writes in its task temp directory.
 *
 * <p>An external editor save is observable only after the file stops changing. Each observation
 * therefore pairs a content digest with a portable file identity and requires several identical
 * stable reads before the probe treats the bytes as the saved document.</p>
 */
final class SessionFileReadiness {
    static final int SESSION_DIAGNOSTIC_LIMIT = 512;

    /**
     * Narrow test seam for the platform file-object token.  The production implementation is
     * {@link BasicFileAttributes#fileKey()}; tests may return {@code null} or a controlled token
     * to exercise the portable path/content protocol without introducing a native file-ID API.
     */
    @FunctionalInterface
    interface FileKeySupplier {
        Object fileKey(BasicFileAttributes attributes);
    }

    static final FileKeySupplier PLATFORM_FILE_KEY_SUPPLIER =
        BasicFileAttributes::fileKey;

    static Path tempRoot() throws Exception {
        final String configured = System.getProperty("java.io.tmpdir", "");
        if (configured.isBlank()) throw new IllegalStateException("java.io.tmpdir is blank");
        final Path configuredPath = Path.of(configured);
        if (!configuredPath.isAbsolute()) {
            throw new IllegalStateException("java.io.tmpdir must be absolute");
        }
        requireNoSymlinkPath(configuredPath, "java.io.tmpdir");
        if (Files.isSymbolicLink(configuredPath)
            || !Files.isDirectory(configuredPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("java.io.tmpdir is not a real directory");
        }
        final Path root = configuredPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (root.equals(Path.of("/"))) throw new IllegalStateException("java.io.tmpdir is root");
        return root;
    }

    static Path tempMarker() throws Exception {
        final Path marker = tempRoot().resolve(
            ".external-psd-probe-" + ProcessHandle.current().pid());
        return Files.writeString(marker, Long.toString(System.nanoTime()));
    }

    /** Captures direct candidate directories without following any path component. */
    static TempCandidateSnapshot snapshotTempCandidates(final Path temporaryRoot)
        throws Exception {
        final Path tmp = TempExportTracker.requireOwnedDirectory(temporaryRoot, "temporary root");
        final Set<Path> directories = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.list(tmp)) {
            for (final Path candidate : stream.toList()) {
                final String name = candidate.getFileName().toString();
                if (!name.startsWith(TempExportTracker.TEMP_DIRECTORY_PREFIX)) continue;
                if (Files.isSymbolicLink(candidate)) {
                    throw new IllegalStateException(
                        "temporary PSD candidate is a symlink: " + candidate);
                }
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException(
                        "temporary PSD candidate is not a real directory: " + candidate);
                }
                requireNoSymlinkPath(candidate, "temporary PSD candidate");
                directories.add(candidate.toAbsolutePath().normalize());
            }
        }
        return new TempCandidateSnapshot(tmp, directories);
    }

    static Path locateNewTempFile(final TempCandidateSnapshot before, final Path marker,
        final Properties result, final String label) throws Exception {
        final TempCandidateSnapshot after = snapshotTempCandidates(tempRoot());
        result.setProperty("export." + label + ".candidateBefore",
            Integer.toString(before.directories().size()));
        result.setProperty("export." + label + ".candidateAfter",
            Integer.toString(after.directories().size()));
        final Path directory = uniqueNewTempCandidate(before.directories(), after.directories());
        if (!directory.getParent().equals(after.root())) {
            throw new IllegalStateException("temporary PSD candidate escaped its temp root");
        }
        final boolean markerAuxiliary = modified(directory) >= modified(marker);
        result.setProperty("export." + label + ".markerAfterCandidate",
            Boolean.toString(markerAuxiliary));
        result.setProperty("export." + label + ".directory", directory.toString());
        return requireTrackedTempFile(directory, after.root());
    }

    /** Resolves an export only when exactly one allocation directory was added. */
    static Path uniqueNewTempCandidate(final Set<Path> before, final Set<Path> after) {
        if (before == null || after == null) {
            throw new IllegalArgumentException("temporary candidate sets are required");
        }
        final Set<Path> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        if (added.size() != 1) {
            throw new IllegalStateException(
                "expected exactly one new temporary PSD candidate, found " + added.size());
        }
        return added.iterator().next();
    }

    /** Validates the one runtime PSD file inside a candidate directory without following links. */
    static Path requireTrackedTempFile(final Path directory, final Path expectedTempRoot)
        throws Exception {
        if (directory == null || expectedTempRoot == null) {
            throw new IllegalArgumentException("temporary candidate paths are required");
        }
        final Path root = TempExportTracker.requireOwnedDirectory(expectedTempRoot, "temporary root");
        final Path candidate = directory.toAbsolutePath().normalize();
        if (!candidate.getParent().equals(root)
            || !candidate.getFileName().toString().startsWith(TempExportTracker.TEMP_DIRECTORY_PREFIX)) {
            throw new IllegalStateException("temporary PSD candidate escaped its temp root: "
                + candidate);
        }
        if (Files.isSymbolicLink(candidate)) {
            throw new IllegalStateException("temporary PSD candidate is a symlink: " + candidate);
        }
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("temporary PSD candidate is not a real directory: "
                + candidate);
        }
        requireNoSymlinkPath(candidate, "temporary PSD candidate");
        final Path file = candidate.resolve(TempExportTracker.TEMP_FILE_NAME);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("temporary PSD candidate has no regular PSD file: " + file);
        }
        requireNoSymlinkPath(file, "temporary PSD file");
        return file.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    static void requireNoSymlinkPath(final Path path, final String label) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException(label + " contains a symlink: " + current);
            }
            current = current.getParent();
        }
    }

    static long modified(final Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (Exception error) { return 0; }
    }

    static PsdExportResult awaitExportResult(final Properties result, final RawImageId raw,
        final CompletionStage<PsdExportResult> stage) throws Exception {
        final CompletionStage<PsdExportResult> exportStage = Objects.requireNonNull(stage,
            "native export returned no completion stage");
        final PsdExportResult exported = exportStage.toCompletableFuture()
            .get(120, TimeUnit.SECONDS);
        result.setProperty("export.status", exported.status().name());
        result.setProperty("export.diagnostic", exported.diagnostic());
        if (exported.status() != PsdExportResult.Status.EXPORTED
            || exported.file().isEmpty() || exported.initialRevision().isEmpty()) {
            throw new IllegalStateException("Native export did not issue an edit handle: "
                + exported.status() + " " + exported.diagnostic());
        }
        return exported;
    }

    /**
     * Prepares the exact bytes written by one save cycle. The production loop uses this seam for
     * both its atomic sibling move and its overlap second write, while offline tests can verify
     * decoded fixture content rather than only the selected cycle number.
     */
    static CycleWritePlan prepareSaveCycleBytes(final byte[] current, final int cycle,
        final int cycles, final boolean validateTargetContent) {
        return prepareSaveCycleBytes(current, cycle, cycles, validateTargetContent,
            PsdValidationContent.Profile.SEVEN_LAYER_CONTROL);
    }

    static CycleWritePlan prepareSaveCycleBytes(final byte[] current, final int cycle,
        final int cycles, final boolean validateTargetContent,
        final PsdValidationContent.Profile profile) {
        if (current == null) throw new IllegalArgumentException("current PSD bytes are required");
        Objects.requireNonNull(profile, "profile");
        PerformanceEvidence.isFinalRgbMutationCycle(cycle, cycles);
        final PsdStructureInspection.Mutation mutation = PsdStructureInspection.mutationFor(current, cycle)
            .orElseThrow(() -> new IllegalStateException("PSD layer-name mutation failed"));
        byte[] mutated = PsdStructureInspection.applyMutation(current, mutation);
        final boolean finalCycle = validateTargetContent && PerformanceEvidence.isFinalRgbMutationCycle(cycle, cycles);
        if (finalCycle) {
            // Apply the decoded RGB mutation once. The overlap write below changes only a layer
            // name on this same byte array, so it retains exactly one content inversion.
            mutated = PsdValidationContent.invertTargetLayerRgb(mutated, profile);
        }
        byte[] overlapFinal = mutated;
        if (cycle == 3) {
            final PsdStructureInspection.Mutation secondMutation = PsdStructureInspection.mutationFor(mutated, cycle + 100)
                .orElseThrow(() -> new IllegalStateException("Second mutation failed"));
            overlapFinal = PsdStructureInspection.applyMutation(mutated, secondMutation);
        }
        return new CycleWritePlan(mutation, mutated, overlapFinal, finalCycle);
    }

    record CycleWritePlan(PsdStructureInspection.Mutation mutation, byte[] firstWrite, byte[] overlapFinalWrite,
        boolean targetRgbMutated) {
        CycleWritePlan {
            if (mutation == null || firstWrite == null || overlapFinalWrite == null) {
                throw new IllegalArgumentException("cycle write plan is incomplete");
            }
            firstWrite = firstWrite.clone();
            overlapFinalWrite = overlapFinalWrite.clone();
        }

        @Override public byte[] firstWrite() { return firstWrite.clone(); }
        @Override public byte[] overlapFinalWrite() { return overlapFinalWrite.clone(); }
    }

    record SaveCyclesResult(PsdStructureInspection.Mutation lastMutation, TargetResolution.Target currentTarget,
        TargetResolution.Target lastBeforeTarget) {
        SaveCyclesResult {
            Objects.requireNonNull(currentTarget, "currentTarget");
            Objects.requireNonNull(lastBeforeTarget, "lastBeforeTarget");
        }
    }

    record RevisionEvent(PsdFileRevision revision, long callbackNanos) {
        RevisionEvent {
            Objects.requireNonNull(revision, "revision");
            if (callbackNanos < 0L) throw new IllegalArgumentException(
                "revision callback time must not be negative");
        }
    }

    static void assertNoReplayAfterIdle(final Properties result,
        final Deque<RevisionEvent> revisions) throws Exception {
        final int before;
        synchronized (revisions) { before = revisions.size(); }
        Thread.sleep(3000);
        final int after;
        synchronized (revisions) { after = revisions.size(); }
        result.setProperty("idle.revisionsBefore", Integer.toString(before));
        result.setProperty("idle.revisionsAfter", Integer.toString(after));
        if (after != before) {
            throw new IllegalStateException("New revisions appeared without an external save");
        }
    }

    static void assertNoRevision(final Deque<RevisionEvent> revisions, final long millis,
        final String message) throws Exception {
        synchronized (revisions) {
            final long deadline = System.currentTimeMillis() + millis;
            while (revisions.isEmpty() && System.currentTimeMillis() < deadline) {
                revisions.wait(200);
            }
            if (!revisions.isEmpty()) throw new IllegalStateException(message);
        }
    }

    record TempCandidateSnapshot(Path root, Set<Path> directories) {
        TempCandidateSnapshot {
            root = root.toAbsolutePath().normalize();
            directories = Set.copyOf(directories);
        }
    }

    @FunctionalInterface
    interface CandidateSnapshotSupplier {
        TempCandidateSnapshot snapshot() throws Exception;
    }

    static final class SessionFileReadinessException extends IllegalStateException {
        static final long serialVersionUID = 1L;

        SessionFileReadinessException(final String message) { super(message); }
        SessionFileReadinessException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    record StablePsdSnapshot(Path path, byte[] bytes, FileObservation observation) {
        StablePsdSnapshot {
            Objects.requireNonNull(path, "stable PSD path");
            Objects.requireNonNull(bytes, "stable PSD bytes");
            Objects.requireNonNull(observation, "stable PSD observation");
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    record FileObservation(FileIdentity identity, String sha256,
        PsdStructureInspection.PsdStructureValidation structure) {
        FileObservation {
            Objects.requireNonNull(identity, "file identity");
            sha256 = sha256 == null ? "" : sha256;
            Objects.requireNonNull(structure, "PSD structure observation");
        }

        long size() { return identity.size(); }

        long modifiedMillis() { return identity.modifiedMillis(); }

        String realPath() { return identity.realPath().toString(); }

        String fileKeyStatus() { return identity.fileKeyStatus(); }

        boolean identityVerified() { return identity.identityVerified(); }
    }

    record FileIdentity(Path realPath, long size, long modifiedMillis, Object fileKey) {
        FileIdentity {
            Objects.requireNonNull(realPath, "real path");
        }

        String fileKeyStatus() { return fileKey == null ? "UNAVAILABLE" : "VERIFIED"; }

        boolean identityVerified() { return fileKey != null; }

        String diagnostic() {
            return "path=" + realPath + " size=" + size + " mtime=" + modifiedMillis
                + " fileKeyStatus=" + fileKeyStatus()
                + " identityVerified=" + identityVerified()
                + " fileKey=" + safeDiagnostic(fileKey == null ? "<unavailable>"
                    : String.valueOf(fileKey));
        }
    }

    record SessionFileRead(String state, byte[] bytes, FileObservation observation,
        String diagnostic) {
        SessionFileRead {
            state = state == null ? "UNKNOWN" : state;
            bytes = bytes == null ? null : bytes.clone();
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }

        boolean complete() { return "COMPLETE".equals(state); }

        static SessionFileRead failed(final String state, final String diagnostic) {
            return new SessionFileRead(state, null, null, diagnostic);
        }

        static SessionFileRead replaced(final FileIdentity identity, final String diagnostic) {
            final PsdStructureInspection.PsdStructureValidation structure = PsdStructureInspection.PsdStructureValidation.invalid(
                "file", -1, "file identity changed");
            return new SessionFileRead("REPLACED", null,
                identity == null ? null : new FileObservation(identity, "", structure),
                diagnostic);
        }

        static SessionFileRead replaced(final FileObservation observation,
            final String diagnostic) {
            return new SessionFileRead("REPLACED", null, observation, diagnostic);
        }
    }

    /** Offline seam exercising the exact production candidate/readiness helper on real files. */
    static StablePsdSnapshot awaitSessionFileForTest(final Path temporaryRoot,
        final Set<Path> beforeDirectories, final long timeoutMillis,
        final BooleanSupplier stopped, final TriggerSleeper sleeper) throws Exception {
        return awaitSessionFileForTest(temporaryRoot, beforeDirectories, timeoutMillis, stopped,
            sleeper, PLATFORM_FILE_KEY_SUPPLIER);
    }

    /** Offline seam with a controlled platform file-object token for readiness regression tests. */
    static StablePsdSnapshot awaitSessionFileForTest(final Path temporaryRoot,
        final Set<Path> beforeDirectories, final long timeoutMillis,
        final BooleanSupplier stopped, final TriggerSleeper sleeper,
        final FileKeySupplier fileKeySupplier) throws Exception {
        return awaitSessionFileForTest(temporaryRoot, beforeDirectories, timeoutMillis, null,
            stopped, sleeper, fileKeySupplier);
    }

    /** Offline seam retaining the production result diagnostics for readiness regression tests. */
    static StablePsdSnapshot awaitSessionFileForTest(final Path temporaryRoot,
        final Set<Path> beforeDirectories, final long timeoutMillis, final Properties result,
        final BooleanSupplier stopped, final TriggerSleeper sleeper,
        final FileKeySupplier fileKeySupplier) throws Exception {
        final Path root = TempExportTracker.requireOwnedDirectory(temporaryRoot, "temporary root");
        if (beforeDirectories == null) {
            throw new IllegalArgumentException("before candidate directories are required");
        }
        Objects.requireNonNull(fileKeySupplier, "file key supplier");
        final Set<Path> before = new LinkedHashSet<>();
        for (final Path directory : beforeDirectories) {
            if (directory == null) throw new IllegalArgumentException("null before candidate");
            before.add(directory.toAbsolutePath().normalize());
        }
        return awaitStableSessionFile(new TempCandidateSnapshot(root, before), timeoutMillis,
            result, stopped, () -> snapshotTempCandidates(root), sleeper, fileKeySupplier);
    }

    static StablePsdSnapshot confirmSessionFileForWriteForTest(
        final StablePsdSnapshot snapshot) throws Exception {
        return confirmSessionFileForWrite(snapshot, null, PLATFORM_FILE_KEY_SUPPLIER);
    }

    /** Offline seam with a controlled platform file-object token for write-check tests. */
    static StablePsdSnapshot confirmSessionFileForWriteForTest(
        final StablePsdSnapshot snapshot, final FileKeySupplier fileKeySupplier) throws Exception {
        return confirmSessionFileForWriteForTest(snapshot, null, fileKeySupplier);
    }

    /** Offline seam retaining the production result diagnostics for write-check tests. */
    static StablePsdSnapshot confirmSessionFileForWriteForTest(
        final StablePsdSnapshot snapshot, final Properties result,
        final FileKeySupplier fileKeySupplier) throws Exception {
        return confirmSessionFileForWrite(snapshot, result, fileKeySupplier);
    }

    static StablePsdSnapshot confirmSessionFileForWrite(final StablePsdSnapshot snapshot,
        final Properties result) throws Exception {
        return confirmSessionFileForWrite(snapshot, result, PLATFORM_FILE_KEY_SUPPLIER);
    }

    static StablePsdSnapshot confirmSessionFileForWrite(final StablePsdSnapshot snapshot,
        final Properties result, final FileKeySupplier fileKeySupplier) throws Exception {
        if (snapshot == null || snapshot.path() == null || snapshot.observation() == null) {
            throw new SessionFileReadinessException("write precondition has no validated session PSD");
        }
        Objects.requireNonNull(fileKeySupplier, "file key supplier");
        try {
            requireNoSymlinkPath(snapshot.path(), "validated session PSD path");
        } catch (RuntimeException failure) {
            final SessionFileRead rejected = SessionFileRead.failed("REJECTED_PATH",
                "validated session PSD path contains a symlink: " + failure.getMessage());
            recordSessionWriteCheck(result, rejected);
            throw new SessionFileReadinessException(rejected.diagnostic(), failure);
        }
        final SessionFileRead read = readSessionFile(snapshot.path(),
            snapshot.observation().identity(), fileKeySupplier);
        recordSessionWriteCheck(result, read);
        if (!read.complete() || read.bytes() == null
            || !Arrays.equals(snapshot.bytes(), read.bytes())
            || !sameStableObservation(snapshot.observation(), read.observation())) {
            throw new SessionFileReadinessException(
                "validated session PSD changed before mutation: " + read.diagnostic());
        }
        return new StablePsdSnapshot(snapshot.path(), read.bytes(), read.observation());
    }

    /** Reconfirms the complete snapshot and path immediately before the probe's external write. */
    static void writeSessionFile(final StablePsdSnapshot snapshot,
        final byte[] replacement) throws Exception {
        final StablePsdSnapshot verified = confirmSessionFileForWrite(snapshot, null);
        final Path path = verified.path().toAbsolutePath().normalize();
        requireNoSymlinkPath(path, "session PSD path before write");
        if (Files.isSymbolicLink(path)
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new SessionFileReadinessException(
                "session PSD path is not a regular non-symlink file before write: " + path);
        }
        final Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireNoSymlinkPath(real, "session PSD real path before write");
        if (!real.equals(path)) {
            throw new SessionFileReadinessException(
                "session PSD real path changed before write from " + path + " to " + real);
        }
        Files.write(real, replacement);
    }

    static StablePsdSnapshot awaitStableSessionFile(
        final TempCandidateSnapshot before, final long timeoutMillis, final Properties result,
        final BooleanSupplier stopped, final CandidateSnapshotSupplier snapshots,
        final TriggerSleeper sleeper, final FileKeySupplier fileKeySupplier) throws Exception {
        Objects.requireNonNull(before, "before candidate snapshot");
        Objects.requireNonNull(stopped, "stopped");
        Objects.requireNonNull(snapshots, "candidate snapshot supplier");
        Objects.requireNonNull(sleeper, "session PSD sleeper");
        Objects.requireNonNull(fileKeySupplier, "file key supplier");
        if (SwingUtilities.isEventDispatchThread()) {
            throw new SessionFileReadinessException(
                "session PSD readiness must run off the EDT");
        }
        if (timeoutMillis <= 0) {
            throw new SessionFileReadinessException(
                "session PSD readiness timeout must be positive");
        }

        final long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Path boundDirectory = null;
        Path boundFile = null;
        FileIdentity boundIdentity = null;
        FileObservation stableObservation = null;
        byte[] stableBytes = null;
        int stableReads = 0;
        int attempt = 0;
        SessionFileRead lastRead = null;
        String lastState = "NOT_STARTED";
        String lastDiagnostic = "session PSD candidate wait has not started";

        while (!stopped.getAsBoolean() && System.nanoTime() < deadline) {
            attempt++;
            final TempCandidateSnapshot after;
            try {
                after = snapshots.snapshot();
            } catch (Exception failure) {
                lastState = "REJECTED";
                lastDiagnostic = "candidate snapshot failed: " + failure;
                recordSessionAttempt(result, attempt, null, Set.of(), null,
                    lastState, null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic, failure);
            }
            if (!before.root().equals(after.root())) {
                lastState = "REJECTED";
                lastDiagnostic = "temporary root changed from " + before.root()
                    + " to " + after.root();
                recordSessionAttempt(result, attempt, after, Set.of(), boundDirectory,
                    lastState, null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic);
            }

            final Set<Path> added = new LinkedHashSet<>(after.directories());
            added.removeAll(before.directories());
            if (boundDirectory == null) {
                if (added.isEmpty()) {
                    lastState = "WAITING_NO_NEW_CANDIDATE";
                    lastDiagnostic = "no new PSD candidate directory yet";
                    recordSessionAttempt(result, attempt, after, added, null, lastState,
                        null, stableReads, lastDiagnostic);
                    if (!sleepForSessionPoll(deadline, stopped, sleeper)) break;
                    continue;
                }
                if (added.size() > 1) {
                    lastState = "AMBIGUOUS_CANDIDATES";
                    lastDiagnostic = "multiple new PSD candidate directories: " + added;
                    recordSessionAttempt(result, attempt, after, added, null, lastState,
                        null, stableReads, lastDiagnostic);
                    throw new SessionFileReadinessException(lastDiagnostic);
                }
                boundDirectory = added.iterator().next();
                stableObservation = null;
                stableBytes = null;
                stableReads = 0;
            } else if (added.size() != 1 || !added.contains(boundDirectory)) {
                lastState = added.size() > 1 ? "AMBIGUOUS_CANDIDATES"
                    : "BOUND_CANDIDATE_CHANGED";
                lastDiagnostic = "bound PSD candidate changed or disappeared: bound="
                    + boundDirectory + " added=" + added;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic);
            }
            if (result != null) {
                result.setProperty("gui.session.candidateDirectory", boundDirectory.toString());
            }

            final Path expectedFile = boundDirectory.resolve(TempExportTracker.TEMP_FILE_NAME);
            if (Files.isSymbolicLink(expectedFile)) {
                lastState = "REJECTED_SYMLINK";
                lastDiagnostic = "bound PSD file is a symlink: " + expectedFile;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic);
            }
            if (!Files.exists(expectedFile, LinkOption.NOFOLLOW_LINKS)) {
                if (boundFile != null) {
                    lastState = "BOUND_FILE_CHANGED";
                    lastDiagnostic = "bound PSD file disappeared: " + expectedFile;
                    recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                        null, stableReads, lastDiagnostic);
                    throw new SessionFileReadinessException(lastDiagnostic);
                }
                lastState = "WAITING_FILE";
                lastDiagnostic = "bound candidate has not created " + TempExportTracker.TEMP_FILE_NAME;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                if (!sleepForSessionPoll(deadline, stopped, sleeper)) break;
                continue;
            }
            if (!Files.isRegularFile(expectedFile, LinkOption.NOFOLLOW_LINKS)) {
                lastState = "REJECTED_NON_REGULAR";
                lastDiagnostic = "bound PSD path is not a regular file: " + expectedFile;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic);
            }

            final Path file;
            try {
                file = requireTrackedTempFile(boundDirectory, after.root());
            } catch (Exception failure) {
                lastState = "REJECTED_PATH";
                lastDiagnostic = "bound PSD path validation failed: " + failure;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic, failure);
            }
            if (boundFile == null) {
                boundFile = file;
            } else if (!boundFile.equals(file)) {
                lastState = "REPLACED_FILE";
                lastDiagnostic = "bound PSD real path changed from " + boundFile + " to " + file;
                recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                    null, stableReads, lastDiagnostic);
                throw new SessionFileReadinessException(lastDiagnostic);
            }

            final SessionFileRead read = readSessionFile(file, boundIdentity, fileKeySupplier);
            lastRead = read;
            if (read.observation() != null && boundIdentity == null) {
                boundIdentity = read.observation().identity();
            }
            lastState = read.state();
            lastDiagnostic = read.diagnostic();
            recordSessionAttempt(result, attempt, after, added, boundDirectory, lastState,
                read, stableReads, lastDiagnostic);
            if ("REPLACED".equals(read.state()) || "IDENTITY_UNAVAILABLE".equals(read.state())) {
                throw new SessionFileReadinessException(lastDiagnostic);
            }
            if (!read.complete()) {
                stableObservation = null;
                stableBytes = null;
                stableReads = 0;
            } else if (stableObservation != null
                && sameStableObservation(stableObservation, read.observation())
                && Arrays.equals(stableBytes, read.bytes())) {
                stableReads++;
            } else {
                stableObservation = read.observation();
                stableBytes = read.bytes();
                stableReads = 1;
            }
            if (stableReads >= GuiReadinessDiagnostics.GUI_SESSION_FILE_STABLE_READS) {
                if (result != null) {
                    result.setProperty("gui.session.read.status", "VERIFIED");
                    result.setProperty("gui.session.read.stableReads",
                        Integer.toString(stableReads));
                    result.setProperty("gui.session.read.bytes",
                        Integer.toString(stableBytes.length));
                    result.setProperty("gui.session.read.realPath",
                        stableObservation.realPath());
                    result.setProperty("gui.session.read.sha256",
                        stableObservation.sha256());
                    result.setProperty("gui.session.read.section",
                        stableObservation.structure().section());
                    result.setProperty("gui.session.read.fileKeyStatus",
                        stableObservation.fileKeyStatus());
                    result.setProperty("gui.session.read.identityVerified",
                        Boolean.toString(stableObservation.identityVerified()));
                }
                return new StablePsdSnapshot(boundFile, stableBytes, stableObservation);
            }
            if (!sleepForSessionPoll(deadline, stopped, sleeper)) break;
        }

        final String terminalState = stopped.getAsBoolean() ? "STOPPED" : "TIMEOUT";
        final String terminalDiagnostic = stopped.getAsBoolean()
            ? "session PSD readiness stopped after attempt " + attempt
            : "session PSD readiness timed out after " + timeoutMillis
                + "ms; last=" + lastState + " " + lastDiagnostic;
        recordSessionAttempt(result, attempt + 1, null, Set.of(), boundDirectory, terminalState,
            lastRead, stableReads, terminalDiagnostic);
        throw new SessionFileReadinessException(terminalDiagnostic);
    }

    static boolean sleepForSessionPoll(final long deadline,
        final BooleanSupplier stopped, final TriggerSleeper sleeper)
        throws SessionFileReadinessException {
        if (stopped.getAsBoolean()) return false;
        final long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) return false;
        final long remainingMillis = Math.max(1L,
            TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        try {
            sleeper.sleep(Math.min(GuiReadinessDiagnostics.GUI_SESSION_FILE_POLL_MILLIS, remainingMillis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SessionFileReadinessException(
                "session PSD readiness interrupted", interrupted);
        }
        return !stopped.getAsBoolean();
    }

    static SessionFileRead readSessionFile(final Path file,
        final FileIdentity expectedIdentity, final FileKeySupplier fileKeySupplier) {
        final FileIdentity before;
        try {
            before = readFileIdentity(file, fileKeySupplier);
        } catch (Exception failure) {
            return SessionFileRead.failed("READ_FAILED",
                "PSD metadata before read failed: " + failure);
        }
        if (expectedIdentity != null && !sameFileIdentity(expectedIdentity, before)) {
            return SessionFileRead.replaced(before,
                "PSD file identity changed before read from " + expectedIdentity.diagnostic()
                    + " to " + before.diagnostic() + "; "
                    + identityMismatch(expectedIdentity, before));
        }

        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(before.realPath());
        } catch (Exception failure) {
            return SessionFileRead.failed("READ_FAILED",
                "PSD bytes could not be read: " + failure);
        }
        final String digest;
        try {
            digest = sha256(bytes);
        } catch (Exception failure) {
            return SessionFileRead.failed("READ_FAILED",
                "PSD SHA-256 could not be computed: " + failure);
        }

        final FileIdentity after;
        try {
            after = readFileIdentity(file, fileKeySupplier);
        } catch (Exception failure) {
            return new SessionFileRead("READ_FAILED", bytes,
                new FileObservation(before, digest,
                    PsdStructureInspection.PsdStructureValidation.invalid("file", -1,
                        "PSD metadata after read failed: " + failure)),
                "PSD metadata after read failed: " + failure);
        }
        final PsdStructureInspection.PsdStructureValidation structure = PsdStructureInspection.inspectPsdStructure(bytes);
        final FileObservation observation = new FileObservation(after, digest, structure);
        if (!sameFileIdentity(before, after)) {
            return SessionFileRead.replaced(observation,
                "PSD file identity changed during read from " + before.diagnostic()
                    + " to " + after.diagnostic() + "; "
                    + identityMismatch(before, after));
        }
        if (!sameFileMetadata(before, after)) {
            return new SessionFileRead("CHANGED_DURING_READ", bytes, observation,
                "PSD file metadata changed during read from " + before.diagnostic()
                    + " to " + after.diagnostic() + " section=" + structure.section()
                    + " reason=" + structure.reason());
        }
        if (bytes.length != before.size() || bytes.length != after.size()) {
            return new SessionFileRead("CHANGED_DURING_READ", bytes, observation,
                "PSD byte length " + bytes.length + " disagrees with file size before/after "
                    + before.size() + "/" + after.size() + " section=" + structure.section()
                    + " reason=" + structure.reason());
        }
        if (expectedIdentity != null && !sameFileIdentity(expectedIdentity, after)) {
            return SessionFileRead.replaced(observation,
                "PSD file identity changed from " + expectedIdentity.diagnostic()
                    + " to " + after.diagnostic() + "; "
                    + identityMismatch(expectedIdentity, after));
        }
        if (!structure.complete()) {
            return new SessionFileRead("INCOMPLETE", bytes, observation,
                "PSD structure incomplete section=" + structure.section()
                    + " offset=" + structure.offset() + " reason=" + structure.reason());
        }
        return new SessionFileRead("COMPLETE", bytes, observation,
            "PSD structure complete bytes=" + bytes.length + " sha256=" + digest);
    }

    static FileIdentity readFileIdentity(final Path file,
        final FileKeySupplier fileKeySupplier) throws Exception {
        Objects.requireNonNull(fileKeySupplier, "file key supplier");
        if (file == null) throw new IllegalArgumentException("PSD file is null");
        final Path normalized = file.toAbsolutePath().normalize();
        requireNoSymlinkPath(normalized, "PSD file");
        if (Files.isSymbolicLink(normalized)
            || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("path is not a regular non-symlink file: " + normalized);
        }
        final Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireNoSymlinkPath(real, "PSD file");
        final BasicFileAttributes attributes = Files.readAttributes(real,
            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IllegalStateException("path is not a regular file: " + real);
        }
        return new FileIdentity(real, attributes.size(),
            attributes.lastModifiedTime().toMillis(), fileKeySupplier.fileKey(attributes));
    }

    static boolean sameFileIdentity(final FileIdentity first,
        final FileIdentity second) {
        return first != null && second != null
            && first.realPath().equals(second.realPath())
            && sameFileKey(first.fileKey(), second.fileKey());
    }

    static boolean sameFileKey(final Object first, final Object second) {
        return first == null && second == null
            || first != null && second != null && Objects.equals(first, second);
    }

    static String identityMismatch(final FileIdentity first,
        final FileIdentity second) {
        if (first == null || second == null) return "file identity observation is unavailable";
        if (!first.realPath().equals(second.realPath())) {
            return "real path changed from " + first.realPath() + " to " + second.realPath();
        }
        if (first.fileKey() == null && second.fileKey() != null) {
            return "fileKey availability changed UNAVAILABLE→VERIFIED";
        }
        if (first.fileKey() != null && second.fileKey() == null) {
            return "fileKey availability changed VERIFIED→UNAVAILABLE";
        }
        if (first.fileKey() != null && !Objects.equals(first.fileKey(), second.fileKey())) {
            return "fileKey changed while available";
        }
        return "file identity changed";
    }

    static boolean sameFileMetadata(final FileIdentity first,
        final FileIdentity second) {
        return sameFileIdentity(first, second)
            && first.size() == second.size()
            && first.modifiedMillis() == second.modifiedMillis();
    }

    static boolean sameStableObservation(final FileObservation first,
        final FileObservation second) {
        return first != null && second != null
            && sameFileIdentity(first.identity(), second.identity())
            && first.identity().size() == second.identity().size()
            && first.identity().modifiedMillis() == second.identity().modifiedMillis()
            && first.sha256().equals(second.sha256())
            && first.structure().equals(second.structure());
    }

    static void recordSessionAttempt(final Properties result, final int attempt,
        final TempCandidateSnapshot after, final Set<Path> added, final Path boundDirectory,
        final String state, final SessionFileRead read, final int stableReads,
        final String diagnostic) {
        if (result == null) return;
        final String prefix = "gui.session.read.attempt." + attempt + ".";
        result.setProperty(prefix + "state", safeDiagnostic(state));
        result.setProperty(prefix + "stableReads", Integer.toString(stableReads));
        result.setProperty(prefix + "candidateAfter", after == null ? "-1"
            : Integer.toString(after.directories().size()));
        result.setProperty(prefix + "newCandidates", Integer.toString(added.size()));
        result.setProperty(prefix + "boundDirectory", boundDirectory == null
            ? "" : boundDirectory.toString());
        result.setProperty(prefix + "diagnostic", safeDiagnostic(diagnostic));
        if (read != null) {
            final FileObservation observation = read.observation();
            result.setProperty(prefix + "size", observation == null ? "-1"
                : Long.toString(observation.identity().size()));
            result.setProperty(prefix + "byteLength", read.bytes() == null ? "-1"
                : Integer.toString(read.bytes().length));
            result.setProperty(prefix + "mtime", observation == null ? "-1"
                : Long.toString(observation.identity().modifiedMillis()));
            result.setProperty(prefix + "realPath", observation == null
                ? "" : observation.realPath());
            result.setProperty(prefix + "sha256", observation == null
                ? "" : observation.sha256());
            result.setProperty(prefix + "section", observation == null
                ? "unavailable" : observation.structure().section());
            result.setProperty(prefix + "sectionOffset", observation == null
                ? "-1" : Integer.toString(observation.structure().offset()));
            result.setProperty(prefix + "sectionReason", observation == null
                ? "" : safeDiagnostic(observation.structure().reason()));
            result.setProperty(prefix + "fileKeyStatus", observation == null
                ? "UNAVAILABLE" : observation.fileKeyStatus());
            result.setProperty(prefix + "identityVerified", observation == null
                ? "false" : Boolean.toString(observation.identityVerified()));
        } else {
            result.setProperty(prefix + "size", "-1");
            result.setProperty(prefix + "byteLength", "-1");
            result.setProperty(prefix + "mtime", "-1");
            result.setProperty(prefix + "sha256", "");
            result.setProperty(prefix + "section", "unavailable");
            result.setProperty(prefix + "sectionOffset", "-1");
            result.setProperty(prefix + "sectionReason", "");
            result.setProperty(prefix + "realPath", "");
            result.setProperty(prefix + "fileKeyStatus", "UNAVAILABLE");
            result.setProperty(prefix + "identityVerified", "false");
        }
        result.setProperty("gui.session.read.attempts", Integer.toString(attempt));
        result.setProperty("gui.session.read.lastState", safeDiagnostic(state));
        result.setProperty("gui.session.read.lastDiagnostic", safeDiagnostic(diagnostic));
        if (read != null && read.observation() != null) {
            result.setProperty("gui.session.read.lastSize",
                Long.toString(read.observation().identity().size()));
            result.setProperty("gui.session.read.lastByteLength", read.bytes() == null ? "-1"
                : Integer.toString(read.bytes().length));
            result.setProperty("gui.session.read.lastMtime",
                Long.toString(read.observation().identity().modifiedMillis()));
            result.setProperty("gui.session.read.lastRealPath",
                read.observation().realPath());
            result.setProperty("gui.session.read.lastSha256", read.observation().sha256());
            result.setProperty("gui.session.read.lastSection",
                read.observation().structure().section());
            result.setProperty("gui.session.read.lastSectionReason",
                safeDiagnostic(read.observation().structure().reason()));
            result.setProperty("gui.session.read.lastFileKeyStatus",
                read.observation().fileKeyStatus());
            result.setProperty("gui.session.read.lastIdentityVerified",
                Boolean.toString(read.observation().identityVerified()));
        } else {
            result.setProperty("gui.session.read.lastSize", "-1");
            result.setProperty("gui.session.read.lastByteLength", "-1");
            result.setProperty("gui.session.read.lastMtime", "-1");
            result.setProperty("gui.session.read.lastSha256", "");
            result.setProperty("gui.session.read.lastSection", "unavailable");
            result.setProperty("gui.session.read.lastSectionReason", "");
            result.setProperty("gui.session.read.lastRealPath", "");
            result.setProperty("gui.session.read.lastFileKeyStatus", "UNAVAILABLE");
            result.setProperty("gui.session.read.lastIdentityVerified", "false");
        }
    }

    static void recordSessionWriteCheck(final Properties result,
        final SessionFileRead read) {
        if (result == null) return;
        result.setProperty("gui.session.writeCheck.state", read.state());
        result.setProperty("gui.session.writeCheck.diagnostic", safeDiagnostic(read.diagnostic()));
        if (read.observation() != null) {
            result.setProperty("gui.session.writeCheck.size",
                Long.toString(read.observation().identity().size()));
            result.setProperty("gui.session.writeCheck.byteLength", read.bytes() == null ? "-1"
                : Integer.toString(read.bytes().length));
            result.setProperty("gui.session.writeCheck.mtime",
                Long.toString(read.observation().identity().modifiedMillis()));
            result.setProperty("gui.session.writeCheck.realPath",
                read.observation().realPath());
            result.setProperty("gui.session.writeCheck.sha256", read.observation().sha256());
            result.setProperty("gui.session.writeCheck.section",
                read.observation().structure().section());
            result.setProperty("gui.session.writeCheck.sectionOffset",
                Integer.toString(read.observation().structure().offset()));
            result.setProperty("gui.session.writeCheck.sectionReason",
                safeDiagnostic(read.observation().structure().reason()));
            result.setProperty("gui.session.writeCheck.fileKeyStatus",
                read.observation().fileKeyStatus());
            result.setProperty("gui.session.writeCheck.identityVerified",
                Boolean.toString(read.observation().identityVerified()));
        } else {
            result.setProperty("gui.session.writeCheck.size", "-1");
            result.setProperty("gui.session.writeCheck.byteLength", "-1");
            result.setProperty("gui.session.writeCheck.mtime", "-1");
            result.setProperty("gui.session.writeCheck.sha256", "");
            result.setProperty("gui.session.writeCheck.section", "unavailable");
            result.setProperty("gui.session.writeCheck.sectionOffset", "-1");
            result.setProperty("gui.session.writeCheck.sectionReason", "");
            result.setProperty("gui.session.writeCheck.realPath", "");
            result.setProperty("gui.session.writeCheck.fileKeyStatus", "UNAVAILABLE");
            result.setProperty("gui.session.writeCheck.identityVerified", "false");
        }
    }

    static String safeDiagnostic(final String value) {
        if (value == null) return "";
        final String singleLine = value.replace('\r', ' ').replace('\n', ' ');
        return singleLine.length() <= SESSION_DIAGNOSTIC_LIMIT
            ? singleLine : singleLine.substring(0, SESSION_DIAGNOSTIC_LIMIT) + "…";
    }

    @FunctionalInterface
    interface TriggerSleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
