package dev.turboism.validation.externalpsd;

import static dev.turboism.validation.externalpsd.SessionFileReadiness.tempRoot;

import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Ownership record for the native PSD handles the probe opens, plus their quarantine move.
 *
 * <p>A probe run must stop every handle it opened and must never leave a task directory behind.
 * The tracker refuses unregistered handles, refuses a changed task temp root, and moves each
 * stopped directory atomically into the task-owned quarantine root.</p>
 */
final class TempExportTracker {
    static final String TEMP_DIRECTORY_PREFIX = "turboism-psd-";
    static final String TEMP_FILE_NAME = "external-edit.psd";

    /**
     * Stops every tracked export while preserving an already-failing phase as the primary cause.
     * Cleanup failures are suppressed on that cause; with no primary failure they fail the phase.
     */
    static void stopAllPreservingPrimary(final TempTracker tracker, final Properties result,
        final Throwable primary) throws Exception {
        try {
            tracker.stopAll(result);
        } catch (Throwable cleanup) {
            if (primary != null) {
                addSuppressed(primary, cleanup);
                return;
            }
            rethrowCleanup(cleanup);
        }
    }

    static void stopPreservingPrimary(final TempTracker tracker,
        final TrackedExport export, final Properties result, final Throwable primary)
        throws Exception {
        try {
            tracker.stop(export, result);
        } catch (Throwable cleanup) {
            if (primary != null) {
                addSuppressed(primary, cleanup);
                return;
            }
            rethrowCleanup(cleanup);
        }
    }

    static void addSuppressed(final Throwable primary, final Throwable cleanup) {
        if (primary != cleanup) primary.addSuppressed(cleanup);
    }

    static void rethrowCleanup(final Throwable failure) throws Exception {
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
        throw new IllegalStateException("cleanup failed", failure);
    }

    static final class TrackedExport {
        final String label;
        final PsdEditFile file;
        Path path;
        PsdFileOperationResult stopResult;
        Throwable stopFailure;
        boolean stopAttempted;
        long exportStartedAtEpochMillis;
        long exportCompletedAtEpochMillis;

        TrackedExport(final String label, final PsdEditFile file) {
            this.label = label;
            this.file = file;
        }

        PsdEditFile file() { return file; }
        Path path() { return path; }
        void setPath(final Path path) { this.path = path; }
        void setExportTimes(final long startedAtEpochMillis,
            final long completedAtEpochMillis) {
            exportStartedAtEpochMillis = startedAtEpochMillis;
            exportCompletedAtEpochMillis = completedAtEpochMillis;
        }
        long exportStartedAtEpochMillis() { return exportStartedAtEpochMillis; }
        long exportCompletedAtEpochMillis() { return exportCompletedAtEpochMillis; }
        Path directory() { return path == null ? null : path.getParent(); }
    }

    static final class TempTracker {
        final List<TrackedExport> exports = new ArrayList<>();
        Path tempRoot;

        TrackedExport register(final String label, final PsdEditFile file,
            final Path root) {
            if (label == null || label.isBlank() || file == null || root == null) {
                throw new IllegalArgumentException("tracked export identity is incomplete");
            }
            if (exports.stream().anyMatch(export -> export.label.equals(label))) {
                throw new IllegalArgumentException("duplicate tracked export label " + label);
            }
            final Path normalizedRoot = root.toAbsolutePath().normalize();
            if (tempRoot == null) tempRoot = normalizedRoot;
            if (!tempRoot.equals(normalizedRoot)) {
                throw new IllegalStateException("native exports changed task temp root");
            }
            final TrackedExport tracked = new TrackedExport(label, file);
            exports.add(tracked);
            return tracked;
        }

        PsdFileOperationResult stop(final TrackedExport tracked,
            final Properties result) throws Exception {
            if (!exports.contains(tracked)) {
                throw new IllegalArgumentException("unregistered export handle");
            }
            if (tracked.stopAttempted) {
                if (tracked.stopFailure != null) {
                    throw asException(tracked.stopFailure);
                }
                if (tracked.stopResult == null) {
                    throw new IllegalStateException("tracked stop has no result");
                }
                return tracked.stopResult;
            }
            tracked.stopAttempted = true;
            try {
                final PsdFileOperationResult stopped = tracked.file.stop()
                    .toCompletableFuture().get(60, TimeUnit.SECONDS);
                tracked.stopResult = stopped;
                result.setProperty("export." + tracked.label + ".stopStatus",
                    stopped.status().name());
                result.setProperty("export." + tracked.label + ".stopDiagnostic",
                    stopped.diagnostic());
                if (stopped.status() != PsdFileOperationResult.Status.STOPPED) {
                    throw new IllegalStateException("export handle did not stop: "
                        + tracked.label + " status=" + stopped.status());
                }
                return stopped;
            } catch (Throwable failure) {
                tracked.stopFailure = failure;
                result.setProperty("export." + tracked.label + ".stopStatus", "FAILED");
                result.setProperty("export." + tracked.label + ".stopDiagnostic",
                    failure.toString());
                rethrowCleanup(failure);
                return null;
            }
        }

        void stopAll(final Properties result) throws Exception {
            final List<Throwable> failures = new ArrayList<>();
            for (final TrackedExport tracked : exports) {
                try {
                    stop(tracked, result);
                } catch (Throwable failure) {
                    result.setProperty("export." + tracked.label + ".stopStatus", "FAILED");
                    result.setProperty("export." + tracked.label + ".stopDiagnostic",
                        failure.toString());
                    failures.add(failure);
                }
            }
            if (!failures.isEmpty()) {
                Throwable selected = failures.stream()
                    .filter(failure -> failure instanceof Error)
                    .findFirst()
                    .orElse(failures.get(0));
                for (final Throwable failure : failures) {
                    if (failure != selected) addSuppressed(selected, failure);
                }
                rethrowCleanup(selected);
            }
        }

        boolean allStopped() {
            return !exports.isEmpty() && exports.stream().allMatch(export ->
                export.stopAttempted && export.stopFailure == null && export.stopResult != null
                    && export.stopResult.status() == PsdFileOperationResult.Status.STOPPED);
        }

        void quarantine(final Properties result, final Path taskRoot) throws Exception {
            if (!allStopped()) {
                result.setProperty("persist.tempQuarantine.status", "FAILED");
                result.setProperty("persist.tempQuarantine.diagnostic",
                    "not all native export handles stopped");
                throw new IllegalStateException("cannot quarantine while an export handle is active");
            }
            final String runId = System.getProperty("turboism.validation.externalpsd.runId", "");
            if (!runId.matches("[A-Za-z0-9._-]+")) {
                result.setProperty("persist.tempQuarantine.status", "FAILED");
                result.setProperty("persist.tempQuarantine.diagnostic", "invalid task runId");
                throw new IllegalStateException("task runId is not safe for quarantine naming");
            }
            final Path owner = taskRoot.toAbsolutePath().normalize();
            final Path destination = owner.resolve("external-psd-quarantine-" + runId);
            result.setProperty("persist.tempQuarantine.root", destination.toString());
            for (int index = 0; index < exports.size(); index++) {
                final TrackedExport tracked = exports.get(index);
                final String prefix = "persist.tempQuarantine.item." + (index + 1);
                result.setProperty(prefix + ".source",
                    tracked.directory() == null ? "" : tracked.directory().toString());
                result.setProperty(prefix + ".target",
                    tracked.directory() == null ? ""
                        : destination.resolve(tracked.directory().getFileName()).toString());
                result.setProperty(prefix + ".stopStatus", tracked.stopResult.status().name());
                result.setProperty(prefix + ".moved", "false");
            }
            try {
                final QuarantineReport report = moveTrackedTempDirectories(
                    exports.stream().map(TrackedExport::directory).toList(),
                    tempRoot, owner, destination);
                for (int index = 0; index < report.entries().size(); index++) {
                    final QuarantineEntry entry = report.entries().get(index);
                    final String prefix = "persist.tempQuarantine.item." + (index + 1);
                    result.setProperty(prefix + ".target", entry.target().toString());
                    result.setProperty(prefix + ".moved", Boolean.toString(entry.moved()));
                }
                result.setProperty("persist.tempQuarantine.status", report.status());
                result.setProperty("persist.tempQuarantine.taskOwned",
                    Boolean.toString(report.taskOwned()));
                result.setProperty("persist.tempQuarantine.sourceMissing",
                    Boolean.toString(report.sourceMissing()));
                if (!report.diagnostic().isBlank()) {
                    result.setProperty("persist.tempQuarantine.diagnostic", report.diagnostic());
                }
                if (!"MOVED".equals(report.status())) {
                    throw new IllegalStateException(
                        "per-directory quarantine move failed: " + report.diagnostic());
                }
            } catch (Exception failure) {
                result.setProperty("persist.tempQuarantine.status", "FAILED");
                result.setProperty("persist.tempQuarantine.taskOwned", "false");
                result.setProperty("persist.tempQuarantine.sourceMissing", "false");
                result.setProperty("persist.tempQuarantine.diagnostic", failure.toString());
                throw failure;
            }
        }

        static Exception asException(final Throwable failure) {
            if (failure instanceof Exception exception) return exception;
            if (failure instanceof Error error) throw error;
            return new IllegalStateException(failure);
        }
    }

    /** Atomic, no-overwrite move used by the persist tail and its offline path-boundary tests. */
    static QuarantineReport moveTrackedTempDirectories(final List<Path> sources,
        final Path expectedTempRoot, final Path taskRoot, final Path destinationRoot)
        throws Exception {
        return moveTrackedTempDirectories(sources, expectedTempRoot, taskRoot, destinationRoot,
            (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
    }

    /**
     * Testable implementation of the per-directory atomic quarantine protocol. The injected
     * mover must perform one no-overwrite atomic move; production passes only Files.move with
     * ATOMIC_MOVE and never falls back to copy/delete.
     */
    static QuarantineReport moveTrackedTempDirectories(final List<Path> sources,
        final Path expectedTempRoot, final Path taskRoot, final Path destinationRoot,
        final AtomicDirectoryMove mover) throws Exception {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("tracked temporary directories are required");
        }
        if (mover == null) throw new IllegalArgumentException("atomic directory mover is required");
        final Path tempRoot = requireOwnedDirectory(expectedTempRoot, "temporary root");
        final Path owner = requireOwnedDirectory(taskRoot, "task quarantine root");
        final Path destination = destinationRoot.toAbsolutePath().normalize();
        if (!destination.getParent().equals(owner)) {
            throw new IllegalStateException("quarantine destination escaped task root");
        }
        SessionFileReadiness.requireNoSymlinkPath(destination.getParent(), "quarantine destination");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(destination)) {
            throw new IllegalStateException("quarantine destination already exists: " + destination);
        }

        final Set<Path> uniqueSources = new LinkedHashSet<>();
        final List<QuarantineEntry> entries = new ArrayList<>(sources.size());
        for (final Path sourceValue : sources) {
            if (sourceValue == null) throw new IllegalArgumentException("null tracked source");
            final Path source = sourceValue.toAbsolutePath().normalize();
            if (!source.getParent().equals(tempRoot)
                || !source.getFileName().toString().startsWith(TEMP_DIRECTORY_PREFIX)) {
                throw new IllegalStateException("tracked source escaped task temp root: " + source);
            }
            if (!uniqueSources.add(source)) {
                throw new IllegalStateException("duplicate tracked source: " + source);
            }
            if (Files.isSymbolicLink(source)
                || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("tracked source is not a real directory: " + source);
            }
            SessionFileReadiness.requireNoSymlinkPath(source, "tracked source");
            try (Stream<Path> descendants = Files.walk(source)) {
                descendants.forEach(path -> {
                    if (Files.isSymbolicLink(path)) {
                        throw new IllegalStateException("tracked source contains a symlink: " + path);
                    }
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalStateException("tracked source contains an unsupported item: "
                            + path);
                    }
                });
            }
            final Path target = destination.resolve(source.getFileName());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)) {
                throw new IllegalStateException("quarantine target already exists: " + target);
            }
            entries.add(new QuarantineEntry(source, target, false));
        }

        Files.createDirectory(destination);
        for (int index = 0; index < entries.size(); index++) {
            final QuarantineEntry entry = entries.get(index);
            try {
                mover.move(entry.source(), entry.target());
                entries.set(index, new QuarantineEntry(entry.source(), entry.target(), true));
            } catch (Exception failure) {
                for (int check = index; check < entries.size(); check++) {
                    final QuarantineEntry candidate = entries.get(check);
                    entries.set(check, new QuarantineEntry(candidate.source(), candidate.target(),
                        candidate.moved() || isCompletedDirectoryMove(candidate)));
                }
                return new QuarantineReport("FAILED", false, false, entries,
                    failure.toString());
            }
        }
        boolean sourceMissing = true;
        for (final QuarantineEntry entry : entries) {
            final boolean missing = !Files.exists(entry.source(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(entry.source());
            sourceMissing &= missing;
            if (!Files.isDirectory(entry.target(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(entry.target())) {
                return new QuarantineReport("FAILED", false, false, entries,
                    "quarantine target is not a real directory: " + entry.target());
            }
        }
        if (!sourceMissing) {
            return new QuarantineReport("FAILED", false, false, entries,
                "not all tracked sources are missing");
        }
        return new QuarantineReport("MOVED", true, true, entries, "");
    }

    static boolean isCompletedDirectoryMove(final QuarantineEntry entry) {
        return !Files.exists(entry.source(), LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(entry.source())
            && Files.isDirectory(entry.target(), LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(entry.target());
    }

    static Path requireOwnedDirectory(final Path value, final String label) {
        if (value == null) throw new IllegalArgumentException(label + " is null");
        final Path path = value.toAbsolutePath().normalize();
        if (path.equals(Path.of("/")) || Files.isSymbolicLink(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(label + " is not a real directory: " + path);
        }
        SessionFileReadiness.requireNoSymlinkPath(path, label);
        return path;
    }

    record QuarantineEntry(Path source, Path target, boolean moved) {
    }

    @FunctionalInterface
    interface AtomicDirectoryMove {
        void move(Path source, Path target) throws Exception;
    }

    record QuarantineReport(String status, boolean taskOwned, boolean sourceMissing,
        List<QuarantineEntry> entries, String diagnostic) {
        QuarantineReport {
            status = status == null ? "FAILED" : status;
            entries = List.copyOf(entries);
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }
}
