package dev.turboism.update;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Entry point of the external update helper. It runs in its own JVM after Cubism has exited so that no loaded
 * framework bytes are ever replaced in place.
 *
 * <h2>Trust model</h2>
 *
 * The helper trusts nothing the caller says about the update. The command line carries only <em>paths</em>. The
 * helper itself:
 *
 * <ul>
 *   <li>waits for the exact process id to exit (and refuses to continue if it does not);
 *   <li>re-reads the staged manifest and signature and verifies them with the keys compiled into this program;
 *   <li>refuses downgrades and mismatched platform/channel unless explicitly allowed by a pinned policy;
 *   <li>re-hashes every staged file inside {@link FrameworkUpdateApplier} immediately before installing it.
 * </ul>
 *
 * A compromised application process can therefore at worst ask for an update the publisher really signed.
 *
 * <p>Exit codes: 0 applied, 10 nothing to do, 20 rolled back (installation unchanged), 30 recovered an earlier
 * interrupted update, 40 rollback INCOMPLETE (installation may be mixed; journal kept, must be re-run), 64 usage
 * error, 70 rejected (verification/policy), 74 failed before application, 75 Cubism still running (retry later).
 *
 * <p>Only {@code 0/10/20/30} let the launcher start Cubism. Every other code aborts the launch, so this program
 * must not leave its own {@code LAUNCHING} lease entry behind for them; see the package-local launch guard.
 */
public final class FrameworkUpdateHelper {
    static final int EXIT_APPLIED = 0;
    static final int EXIT_NOTHING = 10;
    static final int EXIT_ROLLED_BACK = 20;
    static final int EXIT_RECOVERED = 30;
    /** Rollback incomplete: installation may be mixed. Journal kept; run again (or restart) once locks clear. */
    static final int EXIT_NEEDS_RECOVERY = 40;

    static final int EXIT_USAGE = 64;
    static final int EXIT_REJECTED = 70;
    static final int EXIT_BUSY = 75;
    /** IO/admission failed before application; unlike exit 20, this does not claim a completed rollback. */
    static final int EXIT_FAILED = 74;

    /**
     * Package-local test seam: invoked under the transaction lock immediately before a failed launch's LAUNCHING
     * entry is released. Production leaves this as a no-op; a test may pause here to prove the release runs while
     * the caller still holds the lock.
     */
    static Runnable beforeFailedLaunchRelease = () -> {};

    /**
     * Package-local test seam: invoked in the outer failure path before any best-effort lease cleanup, i.e. when
     * the transaction lock scope itself failed. Production leaves this as a no-op; a test may pause here to prove
     * that cleanup never touches a lease entry this invocation did not publish.
     */
    static Runnable beforeLockScopeCleanup = () -> {};

    /** Package-local test seam: invoked under the lock immediately before startup preparation. A test may throw
     * {@link InterruptedException} here to exercise the interrupt-cleanup path deterministically. */
    @FunctionalInterface
    interface BeforePrepareLaunch {
        void run() throws InterruptedException;
    }

    static BeforePrepareLaunch beforePrepareLaunch = () -> {};

    /** Parsed, validated invocation. */
    record Invocation(
            Path root,
            Path stage,
            Path work,
            Optional<Long> waitForPid,
            long waitMillis,
            boolean recoverOnly,
            boolean requireHealth,
            Optional<Long> launcherPid,
            boolean releaseLaunch,
            String launchToken,
            boolean initializeReceipt) {}

    private FrameworkUpdateHelper() {}

    public static void main(final String[] args) {
        System.exit(runInstalled(args, System.out, System.err, trustedKeys(), ProcessHandle::of, Instant::now));
    }

    /** Framework-specific bundled authority; never reuse plugin-catalog keys or accept keys from the stage. */
    private static Map<String, PublicKey> trustedKeys() {
        try {
            return FrameworkUpdateTrust.bundled().activeKeys();
        } catch (IOException invalid) {
            System.err.println("Framework update trust configuration is invalid; installation disabled");
            return Map.of();
        }
    }

    @FunctionalInterface
    interface ProcessLookup {
        Optional<ProcessHandle> of(long pid);
    }

    /** Explicit package-local component-test seam; production main always requires old signed ownership. */
    static int run(
            final String[] args,
            final PrintStream out,
            final PrintStream err,
            final Map<String, PublicKey> keys,
            final ProcessLookup processes,
            final java.util.function.Supplier<Instant> clock) {
        return run(args, out, err, keys, processes, clock, false);
    }

    static int runInstalled(
            final String[] args,
            final PrintStream out,
            final PrintStream err,
            final Map<String, PublicKey> keys,
            final ProcessLookup processes,
            final java.util.function.Supplier<Instant> clock) {
        return run(args, out, err, keys, processes, clock, true);
    }

    private static int run(
            final String[] args,
            final PrintStream out,
            final PrintStream err,
            final Map<String, PublicKey> keys,
            final ProcessLookup processes,
            final java.util.function.Supplier<Instant> clock,
            final boolean installedAuthority) {
        final Invocation invocation;
        try {
            invocation = parse(args);
        } catch (IllegalArgumentException usage) {
            err.println("usage: " + usage.getMessage());
            err.println("  --root <install dir> --stage <staged dir> --work <state dir> [--wait-pid <pid>] "
                    + "[--wait-seconds <n>] | --recover --root <dir> --work <dir>");
            return EXIT_USAGE;
        }
        // Declared before the lock scope so the outer failure path can ask it whether this invocation published
        // anything; a lease that already existed must never be deleted here.
        final LaunchLeaseGuard lease = new LaunchLeaseGuard(invocation);
        try {
            final var applier = new FrameworkUpdateApplier(invocation.root(), invocation.work());
            if (invocation.waitForPid().isPresent()
                    && !waitForExit(invocation.waitForPid().get(), invocation.waitMillis(), processes)) {
                err.println(
                        "Cubism process " + invocation.waitForPid().get() + " is still running; not touching files");
                return EXIT_BUSY;
            }
            try (var transaction = FrameworkUpdateLock.acquire(invocation.work())) {
                // Single owner of the LAUNCHING entry this invocation published: every result the launcher refuses
                // to spawn for is cleaned up in one finally, still under the transaction lock (see LaunchLeaseGuard).
                try {
                    if (invocation.releaseLaunch()) {
                        final long launcherPid = invocation.launcherPid().get();
                        FrameworkUpdateLaunchLease.releaseLaunching(
                                invocation.work(),
                                invocation.root(),
                                launcherPid,
                                launcherStartInstant(launcherPid),
                                invocation.launchToken());
                        out.println("LAUNCH_RELEASED");
                        return lease.allowSpawn(EXIT_NOTHING);
                    }
                    final boolean launching = invocation.launcherPid().isPresent();
                    if (installedAuthority && !launching && runtimeInUse(invocation.root())) {
                        err.println("A live process still references this installed agent; no application or recovery"
                                + " performed");
                        return EXIT_BUSY;
                    }
                    if (installedAuthority && !launching) {
                        final String blocked = mutatorBlocked(invocation.root(), invocation.work());
                        if (blocked != null) {
                            err.println("Refusing to mutate installation: " + blocked);
                            return EXIT_BUSY;
                        }
                    }
                    if (launching) {
                        final long launcherPid = invocation.launcherPid().get();
                        // A launcher only mutates when a journal or pending task requires recovery/apply. Gate
                        // admission
                        // on that so ordinary multi-instance launches stay allowed (each registers its own lease
                        // entry).
                        final boolean mutationPending = Files.exists(
                                        invocation.work().resolve(FrameworkUpdateApplier.JOURNAL),
                                        LinkOption.NOFOLLOW_LINKS)
                                || FrameworkUpdatePendingTask.read(invocation.work())
                                        .isPresent();
                        if (installedAuthority && mutationPending && runtimeInUse(invocation.root())) {
                            err.println(
                                    "A live process still references this installed agent; no application or recovery"
                                            + " performed");
                            return EXIT_BUSY;
                        }
                        if (installedAuthority && mutationPending) {
                            final String blocked = mutatorBlocked(invocation.root(), invocation.work());
                            if (blocked != null) {
                                err.println("Refusing to mutate installation: " + blocked);
                                return EXIT_BUSY;
                            }
                        }
                        // Reap dead USING entries before publishing so ordinary launches never accumulate toward the
                        // cap.
                        FrameworkUpdateLaunchLease.reapStale(
                                invocation.work(), invocation.root(), FrameworkUpdateLaunchLease.SYSTEM);
                        if (invocation.launchToken() != null) {
                            try {
                                FrameworkUpdateLaunchLease.publishLaunching(
                                        invocation.work(),
                                        invocation.root(),
                                        launcherPid,
                                        launcherStartInstant(launcherPid),
                                        invocation.launchToken());
                                lease.published();
                            } catch (IOException leaseFailure) {
                                err.println("Launch lease could not be published: " + leaseFailure.getMessage());
                                return EXIT_FAILED;
                            }
                        }
                        beforePrepareLaunch.run();
                        final var prepared = applier.prepareLaunch(launcherPid);
                        if (prepared.outcome() == FrameworkUpdateApplier.Outcome.RECOVERED) {
                            FrameworkUpdatePendingTask.discard(invocation.work());
                            out.println(prepared.outcome() + ": " + prepared.detail());
                            return lease.allowSpawn(EXIT_RECOVERED);
                        }
                        if (prepared.outcome() == FrameworkUpdateApplier.Outcome.APPLIED) {
                            FrameworkUpdatePendingTask.discard(invocation.work());
                            out.println(prepared.outcome() + ": " + prepared.detail());
                            return lease.allowSpawn(EXIT_NOTHING);
                        }
                        if ("first startup awaiting health confirmation".equals(prepared.detail())
                                || "already starting for this launcher".equals(prepared.detail())) {
                            FrameworkUpdatePendingTask.discard(invocation.work());
                            out.println(prepared.outcome() + ": " + prepared.detail());
                            return lease.allowSpawn(EXIT_NOTHING);
                        }
                        final var pendingOpt = FrameworkUpdatePendingTask.read(invocation.work());
                        if (pendingOpt.isPresent()) {
                            final var pending = pendingOpt.get();
                            try {
                                if (!invocation
                                        .root()
                                        .equals(pending.root().toAbsolutePath().normalize())) {
                                    throw new UpdateManifestVerifier.UpdateManifestVerificationException(
                                            "ROOT_MISMATCH", "Pending update root does not match invocation root");
                                }
                                final Path stage = pending.stage();
                                if (!Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)
                                        || stage.startsWith(invocation.root())) {
                                    throw new IOException("Pending update stage directory is missing or invalid");
                                }
                                if (keys.isEmpty()) {
                                    throw new UpdateManifestVerifier.UpdateManifestVerificationException(
                                            "NO_KEYS", "No active framework-update signing keys are provisioned");
                                }
                                final byte[] manifestBytes =
                                        readBounded(stage.resolve("manifest.json"), UpdateManifest.MAX_MANIFEST_BYTES);
                                final byte[] envelopeBytes = readBounded(
                                        stage.resolve("manifest.json.sig"), UpdateManifestVerifier.MAX_ENVELOPE_BYTES);
                                final var verifier = new UpdateManifestVerifier(keys);
                                final UpdateManifest manifest = verifier.authenticate(manifestBytes, envelopeBytes);

                                final FrameworkUpdateApplier.Result result;
                                if (installedAuthority) {
                                    final var previous = FrameworkInstallationReceiptLoader.loadForHelper(
                                            invocation.root(), verifier);
                                    final var context = new UpdateVerificationContext(
                                            manifest.version(),
                                            manifest.channel(),
                                            manifest.buildNumber(),
                                            manifest.sourceRevision(),
                                            dev.turboism.core.FrameworkBuildInfo.current()
                                                    .version(),
                                            "windows-x64",
                                            InstalledBuild.from(previous.loaded()),
                                            previous.hashes(),
                                            previous.binding(),
                                            () -> {});
                                    verifier.verify(manifestBytes, envelopeBytes, context, clock.get());
                                    final var receipt = FrameworkUpdateReceipt.prepare(
                                            invocation.root(), manifestBytes, envelopeBytes, verifier);
                                    if (runtimeInUse(invocation.root())) {
                                        err.println("Cubism started during verification; application deferred without"
                                                + " changing payload");
                                        return EXIT_BUSY;
                                    }
                                    result = applier.applyAuthenticated(
                                            receipt,
                                            previous,
                                            stage.resolve("files"),
                                            identity(invocation.root(), manifest));
                                } else {
                                    final String problem = policyProblem(invocation, manifest);
                                    if (problem != null) {
                                        throw new UpdateManifestVerifier.UpdateManifestVerificationException(
                                                "POLICY_REJECTED", problem);
                                    }
                                    if (runtimeInUse(invocation.root())) {
                                        err.println("Cubism started during verification; application deferred without"
                                                + " changing payload");
                                        return EXIT_BUSY;
                                    }
                                    final byte[] identity = identity(invocation.root(), manifest);
                                    result = applier.applyPendingHealth(manifest, stage.resolve("files"), identity);
                                }
                                FrameworkUpdatePendingTask.discard(invocation.work());
                                out.println(result.outcome() + ": " + result.detail());
                                if (result.outcome() == FrameworkUpdateApplier.Outcome.AWAITING_HEALTH) {
                                    final var preparedHealth = applier.prepareLaunch(launcherPid);
                                    out.println(preparedHealth.outcome() + ": " + preparedHealth.detail());
                                    return lease.allowSpawn(EXIT_APPLIED);
                                }
                                return switch (result.outcome()) {
                                    case APPLIED, AWAITING_HEALTH -> lease.allowSpawn(EXIT_APPLIED);
                                    case ROLLED_BACK -> lease.allowSpawn(EXIT_ROLLED_BACK);
                                    case RECOVERED -> lease.allowSpawn(EXIT_RECOVERED);
                                    case NOTHING -> lease.allowSpawn(EXIT_NOTHING);
                                };
                            } catch (FrameworkUpdateApplier.IncompleteRollbackException incomplete) {
                                err.println("ROLLBACK INCOMPLETE - installation may be mixed; will be repaired on next"
                                        + " run: "
                                        + incomplete.getMessage());
                                return EXIT_NEEDS_RECOVERY;
                            } catch (Exception rejectedOrIo) {
                                if (Files.exists(
                                        invocation.work().resolve(FrameworkUpdateApplier.JOURNAL),
                                        LinkOption.NOFOLLOW_LINKS)) {
                                    throw rejectedOrIo;
                                }
                                FrameworkUpdatePendingTask.discard(invocation.work());
                                out.println("UPDATE_DISCARDED: " + rejectedOrIo.getMessage());
                                return lease.allowSpawn(EXIT_NOTHING);
                            }
                        }
                        out.println(prepared.outcome() + ": " + prepared.detail());
                        return lease.allowSpawn(EXIT_NOTHING);
                    }
                    // Always finish or undo any earlier interrupted transaction first.
                    final var recovered = applier.recover();
                    if (invocation.recoverOnly()) {
                        FrameworkUpdatePendingTask.discard(invocation.work());
                        out.println(recovered.outcome() + ": " + recovered.detail());
                        return lease.allowSpawn(
                                recovered.outcome() == FrameworkUpdateApplier.Outcome.RECOVERED
                                        ? EXIT_RECOVERED
                                        : EXIT_NOTHING);
                    }
                    if (keys.isEmpty()) {
                        err.println("Rejected update: no active framework-update signing keys are provisioned");
                        return EXIT_REJECTED;
                    }
                    final byte[] manifestBytes =
                            readBounded(invocation.stage().resolve("manifest.json"), UpdateManifest.MAX_MANIFEST_BYTES);
                    final byte[] envelopeBytes = readBounded(
                            invocation.stage().resolve("manifest.json.sig"), UpdateManifestVerifier.MAX_ENVELOPE_BYTES);
                    final var verifier = new UpdateManifestVerifier(keys);
                    final UpdateManifest manifest = verifier.authenticate(manifestBytes, envelopeBytes);
                    if (invocation.initializeReceipt()) {
                        FrameworkUpdateReceipt.initialize(invocation.root(), manifestBytes, envelopeBytes, verifier);
                        out.println("RECEIPT_INITIALIZED: " + manifest.version());
                        return lease.allowSpawn(EXIT_NOTHING);
                    }
                    final FrameworkUpdateApplier.Result result;
                    if (installedAuthority) {
                        final var previous =
                                FrameworkInstallationReceiptLoader.loadForHelper(invocation.root(), verifier);
                        final var context = new UpdateVerificationContext(
                                manifest.version(),
                                manifest.channel(),
                                manifest.buildNumber(),
                                manifest.sourceRevision(),
                                dev.turboism.core.FrameworkBuildInfo.current().version(),
                                "windows-x64",
                                InstalledBuild.from(previous.loaded()),
                                previous.hashes(),
                                previous.binding(),
                                () -> {});
                        verifier.verify(manifestBytes, envelopeBytes, context, clock.get());
                        final var receipt = FrameworkUpdateReceipt.prepare(
                                invocation.root(), manifestBytes, envelopeBytes, verifier);
                        if (runtimeInUse(invocation.root())) {
                            err.println("Cubism started during verification; application deferred without changing"
                                    + " payload");
                            return EXIT_BUSY;
                        }
                        result = applier.applyAuthenticated(
                                receipt,
                                previous,
                                invocation.stage().resolve("files"),
                                identity(invocation.root(), manifest));
                    } else {
                        final String problem = policyProblem(invocation, manifest);
                        if (problem != null) {
                            err.println("Rejected update: " + problem);
                            return EXIT_REJECTED;
                        }
                        final byte[] identity = identity(invocation.root(), manifest);
                        result = invocation.requireHealth()
                                ? applier.applyPendingHealth(
                                        manifest, invocation.stage().resolve("files"), identity)
                                : applier.apply(manifest, invocation.stage().resolve("files"), identity);
                    }
                    FrameworkUpdatePendingTask.discard(invocation.work());
                    out.println(result.outcome() + ": " + result.detail());
                    return switch (result.outcome()) {
                        case APPLIED, AWAITING_HEALTH -> lease.allowSpawn(EXIT_APPLIED);
                        case ROLLED_BACK -> lease.allowSpawn(EXIT_ROLLED_BACK);
                        case RECOVERED -> lease.allowSpawn(EXIT_RECOVERED);
                        case NOTHING -> lease.allowSpawn(EXIT_NOTHING);
                    };
                } catch (FrameworkUpdateApplier.IncompleteRollbackException incomplete) {
                    err.println("ROLLBACK INCOMPLETE - installation may be mixed; will be repaired on next run: "
                            + incomplete.getMessage());
                    for (final Throwable detail : incomplete.getSuppressed()) err.println("  " + detail);
                    return EXIT_NEEDS_RECOVERY;
                } catch (UpdateManifestVerifier.UpdateManifestVerificationException rejected) {
                    FrameworkUpdatePendingTask.discard(invocation.work());
                    err.println("Rejected update: " + rejected.errorCode() + ": " + rejected.getMessage());
                    return EXIT_REJECTED;
                } catch (InterruptedException interrupted) {
                    // Rollback completion is not proven here; return a code the launcher refuses to spawn for.
                    Thread.currentThread().interrupt();
                    err.println(
                            "Interrupted; the launch is abandoned and any partial change is repaired on next start");
                    return Files.exists(
                                    invocation.work().resolve(FrameworkUpdateApplier.JOURNAL),
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            ? EXIT_NEEDS_RECOVERY
                            : EXIT_FAILED;
                } catch (IOException | RuntimeException failure) {
                    err.println("Update failed: " + failure);
                    return Files.exists(
                                    invocation.work().resolve(FrameworkUpdateApplier.JOURNAL),
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            ? EXIT_NEEDS_RECOVERY
                            : EXIT_FAILED;
                } finally {
                    // Every exit path passes through here while the transaction lock is still held: a result the
                    // launcher will not spawn for must not leave this invocation's LAUNCHING entry behind, and a
                    // spawn-allowed result must keep it so the runtime premain can handshake.
                    lease.finish();
                }
            }
        } catch (IOException | RuntimeException outerFailure) {
            // The lock scope itself failed (acquire, applier construction or close() after the body completed).
            // Cleanup stays conditional on this invocation having published an entry: a lease that already
            // existed must never be deleted by an invocation that failed before publishing anything.
            beforeLockScopeCleanup.run();
            lease.finishOutsideLock();
            err.println("Update failed: " + outerFailure);
            return Files.exists(
                            invocation.work().resolve(FrameworkUpdateApplier.JOURNAL),
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    ? EXIT_NEEDS_RECOVERY
                    : EXIT_FAILED;
        }
    }

    /** Additional same-user process guard for concurrent Cubism instances and an orphaned launcher child. */
    static boolean runtimeInUse(final Path root) throws IOException, InterruptedException {
        return FrameworkUpdateProcessGuard.inUse(root);
    }

    /** Launch-lease gate for mutation. Reaps stale USING entries; never reaps a LAUNCHING entry. */
    static String mutatorBlocked(final Path root, final Path work) throws IOException, InterruptedException {
        FrameworkUpdateLaunchLease.reapStale(work, root, FrameworkUpdateLaunchLease.SYSTEM);
        final var decision = FrameworkUpdateLaunchLease.mutatorDecision(work, root, FrameworkUpdateLaunchLease.SYSTEM);
        return decision.allowed() ? null : decision.reason();
    }

    private static String launcherStartInstant(final long launcherPid) throws IOException {
        final var handle = ProcessHandle.of(launcherPid)
                .filter(ProcessHandle::isAlive)
                .orElseThrow(() -> new IOException("Launcher process is not running"));
        return handle.info()
                .startInstant()
                .orElseThrow(() -> new IOException("Launcher process identity unavailable"))
                .toString();
    }

    /**
     * Owns cleanup of the {@code LAUNCHING} entry this invocation published. The launcher treats only exit codes
     * {@code 0/10/20/30} as "Cubism may start"; every other code must leave no lease behind, because a
     * {@code LAUNCHING} entry whose launcher is gone is recovery-required and would block later updates. Instead
     * of repeating that cleanup in each failure path (where an early {@code return} is easy to miss), the body
     * marks spawn-allowed results with {@link #allowSpawn(int)} and the single {@code finally} of the locked
     * section releases everything else. Non-launching invocations never publish, so the flag stays false.
     */
    private static final class LaunchLeaseGuard {
        private final Invocation invocation;
        private boolean published;
        private boolean spawnAllowed;

        LaunchLeaseGuard(final Invocation invocation) {
            this.invocation = invocation;
        }

        /** Record that this invocation's own {@code LAUNCHING} entry now exists. */
        void published() {
            this.published = true;
        }

        /** Mark a result the launcher may start Cubism for; the runtime premain still has to handshake. */
        int allowSpawn(final int exitCode) {
            this.spawnAllowed = true;
            return exitCode;
        }

        /** Runs before the transaction lock is released, so cleanup and publication share one lock scope. */
        void finish() {
            if (!published || spawnAllowed) return;
            // Lease cleanup reads and deletes files. Those calls tolerate a set interrupt flag on this JDK/OS,
            // but FileChannel-based operations (the lease writer's force) throw ClosedByInterruptException, so
            // clear the flag for the cleanup and restore it afterwards.
            final boolean interrupted = Thread.interrupted();
            try {
                releaseLaunchingUnderLock(invocation);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        /**
         * Cleanup for the outer failure path, where the lock scope is gone. Reaching it always yields a code the
         * launcher refuses to spawn for, so an entry this invocation published must be released even if the body
         * had marked the result spawn-allowed. Nothing is attempted when this invocation published no entry.
         */
        void finishOutsideLock() {
            if (!published) return;
            final boolean interrupted = Thread.interrupted();
            try {
                releaseLaunchingBestEffort(invocation);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    /** Release own LAUNCHING while the caller already holds the transaction lock. */
    private static void releaseLaunchingUnderLock(final Invocation invocation) {
        if (invocation.launcherPid().isEmpty() || invocation.releaseLaunch() || invocation.launchToken() == null) {
            return;
        }
        beforeFailedLaunchRelease.run();
        try {
            FrameworkUpdateLaunchLease.releaseLaunching(
                    invocation.work(),
                    invocation.root(),
                    invocation.launcherPid().get(),
                    launcherStartInstant(invocation.launcherPid().get()),
                    invocation.launchToken());
        } catch (Exception ignored) {
            // A lingering LAUNCHING entry becomes recovery-required, which is fail-closed.
        }
    }

    /** Release own LAUNCHING by acquiring the transaction lock (for the lock-scope-exit failure path). */
    private static void releaseLaunchingBestEffort(final Invocation invocation) {
        if (invocation.launcherPid().isEmpty() || invocation.releaseLaunch() || invocation.launchToken() == null) {
            return;
        }
        try (var transaction = FrameworkUpdateLock.acquire(invocation.work())) {
            FrameworkUpdateLaunchLease.releaseLaunching(
                    invocation.work(),
                    invocation.root(),
                    invocation.launcherPid().get(),
                    launcherStartInstant(invocation.launcherPid().get()),
                    invocation.launchToken());
        } catch (Exception ignored) {
            // A lingering LAUNCHING entry becomes recovery-required, which is fail-closed.
        }
    }

    /** Policy checks that depend on the installation, not on signatures. Returns a reason, or null if acceptable. */
    static String policyProblem(final Invocation invocation, final UpdateManifest manifest) throws IOException {
        if (!manifest.targetPlatform().equals("windows-x64")
                && !manifest.targetPlatform().equals("all")) {
            return "wrong platform " + manifest.targetPlatform();
        }
        final Path metadata = invocation.root().resolve(InstalledBuild.METADATA_FILE_NAME);
        if (Files.isRegularFile(metadata)) {
            final var tree = UpdateFileSupport.JSON.readTree(Files.readAllBytes(metadata));
            final String installed = tree.path("version").asText("");
            if (!installed.isEmpty()) {
                final UpdateVersion current = UpdateVersion.parseInstalled(installed);
                final UpdateVersion target = UpdateVersion.parse(manifest.version());
                if (current != null && target.compareTo(current) <= 0) {
                    return "target " + manifest.version() + " is not newer than installed " + installed;
                }
            }
        }
        return null;
    }

    /** Exact bytes of the new installed-build.json. Preserves the installed variant. */
    static byte[] identity(final Path root, final UpdateManifest manifest) throws IOException {
        String variant = InstalledBuild.inferVariant(root);
        final var node = UpdateFileSupport.JSON.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("variant", variant);
        node.put("version", manifest.version());
        node.put("channel", manifest.channel());
        node.put("buildNumber", manifest.buildNumber());
        node.put("sourceRevision", manifest.sourceRevision());
        return (UpdateFileSupport.JSON.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static Invocation parse(final String[] args) {
        Path root = null;
        Path stage = null;
        Path work = null;
        Long pid = null;
        long waitSeconds = 120;
        boolean recover = false;
        boolean requireHealth = false;
        Long launcherPid = null;
        boolean prepareLaunch = false;
        boolean releaseLaunch = false;
        String launchToken = null;
        boolean initializeReceipt = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = path(args, ++i);
                case "--stage" -> stage = path(args, ++i);
                case "--work" -> work = path(args, ++i);
                case "--wait-pid" -> pid = number(args, ++i);
                case "--wait-seconds" -> waitSeconds = number(args, ++i);
                case "--recover" -> recover = true;
                case "--require-health" -> requireHealth = true;
                case "--prepare-launch" -> {
                    prepareLaunch = true;
                    launcherPid = number(args, ++i);
                }
                case "--release-launch" -> {
                    releaseLaunch = true;
                    launcherPid = number(args, ++i);
                }
                case "--launch-token" -> launchToken = token(args, ++i);
                case "--initialize-receipt" -> initializeReceipt = true;
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (root == null || work == null || (!recover && launcherPid == null && stage == null)) {
            throw new IllegalArgumentException("--root, --work and (unless --recover) --stage are required");
        }
        if (prepareLaunch && releaseLaunch) {
            throw new IllegalArgumentException("--prepare-launch and --release-launch are exclusive");
        }
        if ((recover ? 1 : 0) + (launcherPid == null ? 0 : 1) + (initializeReceipt ? 1 : 0) > 1) {
            throw new IllegalArgumentException(
                    "Recovery, startup preparation and receipt initialization are exclusive");
        }
        if (launchToken != null && launcherPid == null) {
            throw new IllegalArgumentException("--launch-token requires --prepare-launch or --release-launch");
        }
        if (releaseLaunch && launchToken == null) {
            throw new IllegalArgumentException("--release-launch requires --launch-token");
        }
        if (pid != null && pid <= 0 || launcherPid != null && launcherPid <= 0) {
            throw new IllegalArgumentException("Process IDs must be positive");
        }
        if (waitSeconds < 0 || waitSeconds > 3600) throw new IllegalArgumentException("--wait-seconds out of range");
        return new Invocation(
                root,
                stage == null ? null : stage,
                work,
                Optional.ofNullable(pid),
                waitSeconds * 1000,
                recover,
                requireHealth,
                Optional.ofNullable(launcherPid),
                releaseLaunch,
                launchToken,
                initializeReceipt);
    }

    private static Path path(final String[] args, final int index) {
        if (index >= args.length) throw new IllegalArgumentException("missing value");
        final Path path = Path.of(args[index]);
        if (!path.isAbsolute()) throw new IllegalArgumentException("paths must be absolute: " + args[index]);
        return path.normalize();
    }

    private static String token(final String[] args, final int index) {
        if (index >= args.length) throw new IllegalArgumentException("missing value");
        final String value = args[index];
        if (!value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("launch token must be 64 lowercase hex characters");
        }
        return value;
    }

    private static long number(final String[] args, final int index) {
        if (index >= args.length) throw new IllegalArgumentException("missing value");
        try {
            return Long.parseLong(args[index]);
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("not a number: " + args[index]);
        }
    }

    /** True once the process is gone. Never assumes exit from a closed window; polls the real process. */
    static boolean waitForExit(final long pid, final long maxMillis, final ProcessLookup processes) {
        final long deadline = System.nanoTime() + maxMillis * 1_000_000L;
        while (true) {
            final Optional<ProcessHandle> handle = processes.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) return true;
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static byte[] readBounded(final Path path, final int limit) throws IOException {
        final var attributes = FrameworkUpdateCache.attributes(path);
        if (attributes == null
                || !attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.size() > limit) {
            throw new IOException("Staged document missing or invalid: " + path.getFileName());
        }
        return Files.readAllBytes(path);
    }
}
