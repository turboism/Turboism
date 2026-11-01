package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrameworkUpdateLaunchLeaseTest {
    @TempDir
    Path temporary;

    private Path root;
    private Path work;

    @BeforeEach
    void setup() throws Exception {
        root = temporary.resolve("home");
        work = temporary.resolve("work");
        Files.createDirectories(root);
        Files.createDirectories(work);
    }

    /** Deterministic per-test tokens that satisfy the 64-hex format. */
    private static String hex(final int n) {
        final String token = String.format("%064x", n + 0x10000L);
        assertEquals(64, token.length(), "test token must be exactly 64 hex characters");
        return token;
    }

    private static final String STARTED = "2026-10-31T16:00:00Z";

    /** A second JVM that attempts to acquire the transaction lock and reports the outcome. */
    public static final class LockProbe {
        public static void main(final String[] args) {
            try (var lock = FrameworkUpdateLock.acquire(Path.of(args[0]))) {
                System.out.println("LOCK_ACQUIRED");
            } catch (Throwable failure) {
                System.out.println("LOCK_REFUSED");
            }
        }
    }

    @Test
    void liveLaunchingBlocksMutator() throws Exception {
        FrameworkUpdateLaunchLease.publishLaunching(work, root, 100, STARTED, hex(1));
        final var decision = FrameworkUpdateLaunchLease.mutatorDecision(work, root, (pid, started) -> true);
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("in flight"));
    }

    @Test
    void deadUnhandshakenLaunchingRequiresRecoveryAndIsNeverReaped() throws Exception {
        FrameworkUpdateLaunchLease.publishLaunching(work, root, 100, STARTED, hex(2));
        final var decision = FrameworkUpdateLaunchLease.mutatorDecision(work, root, (pid, started) -> false);
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("recovery required"), decision.reason());
        final var reaped = FrameworkUpdateLaunchLease.reapStale(work, root, (pid, started) -> false);
        assertEquals(0, reaped.reapedUsing());
        assertTrue(reaped.recoveryRequired());
        assertEquals(1, FrameworkUpdateLaunchLease.readAll(work).size(), "dead LAUNCHING must never be auto-reaped");
    }

    @Test
    void liveUsingBlocksMutator() throws Exception {
        FrameworkUpdateLaunchLease.publishUsing(work, root, 200, STARTED, hex(3));
        final var decision = FrameworkUpdateLaunchLease.mutatorDecision(work, root, (pid, started) -> true);
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("using"));
    }

    @Test
    void staleUsingAllowsMutationAndIsReaped() throws Exception {
        FrameworkUpdateLaunchLease.publishUsing(work, root, 200, STARTED, hex(4));
        final var decision = FrameworkUpdateLaunchLease.mutatorDecision(work, root, (pid, started) -> false);
        assertTrue(decision.allowed(), decision.reason());
        final var reaped = FrameworkUpdateLaunchLease.reapStale(work, root, (pid, started) -> false);
        assertEquals(1, reaped.reapedUsing());
        assertFalse(reaped.recoveryRequired());
        assertTrue(FrameworkUpdateLaunchLease.readAll(work).isEmpty());
    }

    @Test
    void handshakeConvertsOwnLaunchingToUsing() throws Exception {
        FrameworkUpdateLaunchLease.publishLaunching(work, root, 100, STARTED, hex(6));
        final var entry = FrameworkUpdateLaunchLease.completeToUsing(work, root, 200, STARTED, hex(6));
        assertEquals(FrameworkUpdateLaunchLease.Kind.USING, entry.kind());
        final var decision = FrameworkUpdateLaunchLease.mutatorDecision(work, root, (pid, started) -> true);
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("using"));
    }

    @Test
    void handshakeWithMissingLaunchingTokenRejects() throws Exception {
        assertThrows(
                java.io.IOException.class,
                () -> FrameworkUpdateLaunchLease.completeToUsing(work, root, 200, STARTED, hex(7)));
        assertTrue(FrameworkUpdateLaunchLease.readAll(work).isEmpty());
    }

    @Test
    void handshakeIsIdempotentForSameHolder() throws Exception {
        FrameworkUpdateLaunchLease.publishLaunching(work, root, 100, STARTED, hex(8));
        final var first = FrameworkUpdateLaunchLease.completeToUsing(work, root, 200, STARTED, hex(8));
        final var second = FrameworkUpdateLaunchLease.completeToUsing(work, root, 200, STARTED, hex(8));
        assertEquals(first.kind(), second.kind());
        assertEquals(1, FrameworkUpdateLaunchLease.readAll(work).size());
    }

    @Test
    void legacyHandshakeWithoutTokenRegistersIndependentUsing() throws Exception {
        final var entry = FrameworkUpdateLaunchLease.completeToUsing(work, root, 200, STARTED, null);
        assertEquals(FrameworkUpdateLaunchLease.Kind.USING, entry.kind());
        assertNotNull(entry.token());
        assertEquals(1, FrameworkUpdateLaunchLease.readAll(work).size());
    }

    @Test
    void handshakeFailsClosedOnForeignRootEntryCollision() throws Exception {
        final Path otherRoot = temporary.resolve("other");
        Files.createDirectories(otherRoot);
        FrameworkUpdateLaunchLease.publishUsing(work, otherRoot, 200, STARTED, hex(9));
        assertThrows(
                java.io.IOException.class,
                () -> FrameworkUpdateLaunchLease.completeToUsing(work, root, 300, STARTED, hex(9)));
        assertEquals(1, FrameworkUpdateLaunchLease.readAll(work).size());
    }

    @Test
    void foreignRootEntryBlocksMutator() throws Exception {
        final Path otherRoot = temporary.resolve("other");
        Files.createDirectories(otherRoot);
        FrameworkUpdateLaunchLease.publishUsing(work, otherRoot, 200, STARTED, hex(10));
        final var decision = FrameworkUpdateLaunchLease.mutatorDecision(work, root, (pid, started) -> true);
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("another installation"));
    }

    @Test
    void tokenCollisionInPublishIsRejected() throws Exception {
        FrameworkUpdateLaunchLease.publishLaunching(work, root, 100, STARTED, hex(11));
        assertThrows(
                java.io.IOException.class,
                () -> FrameworkUpdateLaunchLease.publishLaunching(work, root, 101, STARTED, hex(11)));
    }

    @Test
    void releaseLaunchingRemovesOnlyOwnEntry() throws Exception {
        FrameworkUpdateLaunchLease.publishLaunching(work, root, 100, STARTED, hex(12));
        FrameworkUpdateLaunchLease.releaseLaunching(work, root, 999, STARTED, hex(12));
        assertEquals(1, FrameworkUpdateLaunchLease.readAll(work).size());
        FrameworkUpdateLaunchLease.releaseLaunching(work, root, 100, STARTED, hex(12));
        assertTrue(FrameworkUpdateLaunchLease.readAll(work).isEmpty());
    }

    @Test
    void releaseLaunchingRefusesMismatchedRoot() throws Exception {
        final Path otherRoot = temporary.resolve("other");
        Files.createDirectories(otherRoot);
        FrameworkUpdateLaunchLease.publishLaunching(work, root, 100, STARTED, hex(24));
        // Same work dir but a different root must not release the entry.
        FrameworkUpdateLaunchLease.releaseLaunching(work, otherRoot, 100, STARTED, hex(24));
        assertEquals(1, FrameworkUpdateLaunchLease.readAll(work).size());
    }

    @Test
    void multipleUsingInstancesAllProtect() throws Exception {
        FrameworkUpdateLaunchLease.publishUsing(work, root, 200, STARTED, hex(13));
        FrameworkUpdateLaunchLease.publishUsing(work, root, 201, STARTED, hex(14));
        final var decision = FrameworkUpdateLaunchLease.mutatorDecision(work, root, (pid, started) -> pid == 201);
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("using"));
        assertTrue(FrameworkUpdateLaunchLease.mutatorDecision(work, root, (pid, started) -> false)
                .allowed());
    }

    @Test
    void fullCapacityStillAllowsInPlaceHandshakeAndRejectsNewEntries() throws Exception {
        FrameworkUpdateLaunchLease.publishLaunching(work, root, 100, STARTED, hex(0));
        for (int i = 1; i < FrameworkUpdateLaunchLease.MAX_ENTRIES; i++) {
            FrameworkUpdateLaunchLease.publishUsing(work, root, 200 + i, STARTED, hex(i + 100));
        }
        assertEquals(
                FrameworkUpdateLaunchLease.MAX_ENTRIES,
                FrameworkUpdateLaunchLease.readAll(work).size());
        // In-place conversion and idempotent re-handshake must not consume capacity.
        assertEquals(
                FrameworkUpdateLaunchLease.Kind.USING,
                FrameworkUpdateLaunchLease.completeToUsing(work, root, 200, STARTED, hex(0))
                        .kind());
        FrameworkUpdateLaunchLease.completeToUsing(work, root, 200, STARTED, hex(0));
        assertEquals(
                FrameworkUpdateLaunchLease.MAX_ENTRIES,
                FrameworkUpdateLaunchLease.readAll(work).size());
        // A brand-new entry is refused, leaving the directory untouched.
        assertThrows(
                java.io.IOException.class,
                () -> FrameworkUpdateLaunchLease.publishUsing(work, root, 999, STARTED, hex(9_999)));
        assertEquals(
                FrameworkUpdateLaunchLease.MAX_ENTRIES,
                FrameworkUpdateLaunchLease.readAll(work).size());
    }

    @Test
    void invalidStartInstantIsRejected() throws Exception {
        writeRawEntry(hex(18), "USING", 200, "not-an-instant");
        assertThrows(java.io.IOException.class, () -> FrameworkUpdateLaunchLease.readAll(work));
    }

    @Test
    void filenameTokenMismatchIsRejected() throws Exception {
        writeRawEntryFile(hex(19), hex(20), "USING", 200, STARTED);
        assertThrows(java.io.IOException.class, () -> FrameworkUpdateLaunchLease.readAll(work));
    }

    @Test
    void malformedEntryIsRejected() throws Exception {
        final Path leaseDir = work.resolve("lease");
        Files.createDirectories(leaseDir);
        Files.writeString(leaseDir.resolve("lease-" + hex(21) + ".json"), "{not json");
        assertThrows(java.io.IOException.class, () -> FrameworkUpdateLaunchLease.readAll(work));
    }

    @Test
    void leaseDirectoryThatIsAFileIsRejectedNotTreatedAsEmpty() throws Exception {
        Files.writeString(work.resolve("lease"), "not a directory");
        assertThrows(java.io.IOException.class, () -> FrameworkUpdateLaunchLease.readAll(work));
    }

    @Test
    void invalidHolderPidOrStartIsRejectedBeforeWrite() throws Exception {
        assertThrows(
                java.io.IOException.class,
                () -> FrameworkUpdateLaunchLease.publishUsing(work, root, 0, STARTED, hex(22)));
        assertThrows(
                java.io.IOException.class,
                () -> FrameworkUpdateLaunchLease.publishUsing(work, root, 200, "bogus", hex(23)));
        assertTrue(FrameworkUpdateLaunchLease.readAll(work).isEmpty());
    }

    @Test
    void confirmRuntimeUsingRegistersLegacyUsingEntry() throws Exception {
        final Path healthWork = FrameworkUpdateHealth.workDirectory(root);
        FrameworkUpdateHealth.confirmRuntimeUsing(root, null);
        final List<FrameworkUpdateLaunchLease.Entry> entries = FrameworkUpdateLaunchLease.readAll(healthWork);
        assertEquals(1, entries.size());
        assertEquals(FrameworkUpdateLaunchLease.Kind.USING, entries.get(0).kind());
        assertFalse(FrameworkUpdateLaunchLease.mutatorDecision(healthWork, root, (pid, started) -> true)
                .allowed());
    }

    @Test
    void confirmRuntimeUsingWithManagedTokenWithoutLaunchingFailsClosed() throws Exception {
        assertThrows(java.io.IOException.class, () -> FrameworkUpdateHealth.confirmRuntimeUsing(root, hex(30)));
        assertTrue(FrameworkUpdateLaunchLease.readAll(FrameworkUpdateHealth.workDirectory(root))
                .isEmpty());
    }

    @Test
    void failedLaunchReleasesLaunchingEntry() throws Exception {
        // A journal bound to another installation makes prepareLaunch fail after LAUNCHING was published; the
        // helper must release its own LAUNCHING so a failed launch never leaves a permanent recovery-required block.
        final var journal = UpdateFileSupport.JSON.createObjectNode();
        journal.put("format", "turboism-update-journal-v1");
        journal.put("state", "AWAITING_HEALTH");
        journal.put("version", "1.0.0");
        journal.put("root", root.resolveSibling("elsewhere").toString());
        journal.putArray("files");
        Files.write(work.resolve("journal.json"), UpdateFileSupport.JSON.writeValueAsBytes(journal));

        final var out = new PrintStream(new ByteArrayOutputStream());
        final var err = new PrintStream(new ByteArrayOutputStream());
        final int exit = FrameworkUpdateHelper.run(
                new String[] {
                    "--root",
                    root.toString(),
                    "--work",
                    work.toString(),
                    "--prepare-launch",
                    Long.toString(ProcessHandle.current().pid()),
                    "--launch-token",
                    hex(31)
                },
                out,
                err,
                Map.of(),
                ProcessHandle::of,
                Instant::now);
        assertEquals(FrameworkUpdateHelper.EXIT_NEEDS_RECOVERY, exit, err.toString());
        assertTrue(
                FrameworkUpdateLaunchLease.readAll(work).isEmpty(), "a failed launch must not leave a LAUNCHING entry");
    }

    @Test
    void failedLaunchReleaseRunsUnderTransactionLock() throws Exception {
        final var journal = UpdateFileSupport.JSON.createObjectNode();
        journal.put("format", "turboism-update-journal-v1");
        journal.put("state", "AWAITING_HEALTH");
        journal.put("version", "1.0.0");
        journal.put("root", root.resolveSibling("elsewhere").toString());
        journal.putArray("files");
        Files.write(work.resolve("journal.json"), UpdateFileSupport.JSON.writeValueAsBytes(journal));

        final CountDownLatch inRelease = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final Runnable original = FrameworkUpdateHelper.beforeFailedLaunchRelease;
        FrameworkUpdateHelper.beforeFailedLaunchRelease = () -> {
            inRelease.countDown();
            try {
                proceed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        final AtomicInteger exit = new AtomicInteger(-1);
        final Thread helper = new Thread(() -> {
            final var out = new PrintStream(new ByteArrayOutputStream());
            final var err = new PrintStream(new ByteArrayOutputStream());
            exit.set(FrameworkUpdateHelper.run(
                    new String[] {
                        "--root",
                        root.toString(),
                        "--work",
                        work.toString(),
                        "--prepare-launch",
                        Long.toString(ProcessHandle.current().pid()),
                        "--launch-token",
                        hex(40)
                    },
                    out,
                    err,
                    Map.of(),
                    ProcessHandle::of,
                    Instant::now));
        });
        helper.start();
        try {
            assertTrue(inRelease.await(10, TimeUnit.SECONDS), "helper should reach the release path");
            // Same-JVM: while the helper thread holds the lock in its failure-release path, a concurrent acquire
            // must be refused. The pre-fix code released the lease after the lock had already closed.
            FrameworkUpdateLock unexpected = null;
            try {
                unexpected = FrameworkUpdateLock.acquire(work);
            } catch (IOException expected) {
                // refused - correct
            } finally {
                // A red light must not leak a FileLock into the temporary directory cleanup.
                if (unexpected != null) unexpected.close();
            }
            assertNull(unexpected, "concurrent acquire must be refused while the helper holds the transaction lock");
            // Cross-JVM: a real second process must also observe the lock as held.
            final Path probeLog = temporary.resolve("probe.log");
            final String executable =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            final Process probe = new ProcessBuilder(
                            executable,
                            "-cp",
                            System.getProperty("java.class.path"),
                            LockProbe.class.getName(),
                            work.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(probeLog.toFile())
                    .start();
            try {
                assertTrue(probe.waitFor(30, TimeUnit.SECONDS), "second JVM probe timed out");
                assertEquals("LOCK_REFUSED", Files.readString(probeLog).trim());
                assertEquals(0, probe.exitValue(), "probe must report through its stdout, not a crash");
            } finally {
                if (probe.isAlive()) {
                    probe.destroyForcibly();
                    probe.waitFor(10, TimeUnit.SECONDS);
                }
            }
            proceed.countDown();
        } finally {
            proceed.countDown();
            helper.join(30_000);
            if (helper.isAlive()) helper.interrupt();
            helper.join(10_000);
            FrameworkUpdateHelper.beforeFailedLaunchRelease = original;
            assertFalse(helper.isAlive(), "helper thread must not survive the test");
        }
        assertEquals(FrameworkUpdateHelper.EXIT_NEEDS_RECOVERY, exit.get());
        assertTrue(FrameworkUpdateLaunchLease.readAll(work).isEmpty());
    }

    @Test
    void interruptedLaunchReturnsNonSpawnCodeAndReleasesLaunching() throws Exception {
        final var original = FrameworkUpdateHelper.beforePrepareLaunch;
        FrameworkUpdateHelper.beforePrepareLaunch = () -> {
            throw new InterruptedException("injected");
        };
        final AtomicInteger exit = new AtomicInteger(-1);
        final Thread helper = new Thread(() -> {
            final var out = new PrintStream(new ByteArrayOutputStream());
            final var err = new PrintStream(new ByteArrayOutputStream());
            exit.set(FrameworkUpdateHelper.run(
                    new String[] {
                        "--root",
                        root.toString(),
                        "--work",
                        work.toString(),
                        "--prepare-launch",
                        Long.toString(ProcessHandle.current().pid()),
                        "--launch-token",
                        hex(41)
                    },
                    out,
                    err,
                    Map.of(),
                    ProcessHandle::of,
                    Instant::now));
        });
        try {
            helper.start();
            helper.join(30_000);
        } finally {
            FrameworkUpdateHelper.beforePrepareLaunch = original;
        }
        assertFalse(SPAWN_ALLOWED.contains(exit.get()), "an interrupted launch must not return a spawn-allowed code");
        assertTrue(FrameworkUpdateLaunchLease.readAll(work).isEmpty(), "LAUNCHING released on interrupt");
    }

    @Test
    void realInterruptDuringStartupPreparationReleasesLaunchingAndRefusesSpawn() throws Exception {
        // A genuine Thread.interrupt (not an injected exception) while the helper waits in startup preparation.
        final CountDownLatch preparing = new CountDownLatch(1);
        final CountDownLatch hold = new CountDownLatch(1);
        final var original = FrameworkUpdateHelper.beforePrepareLaunch;
        FrameworkUpdateHelper.beforePrepareLaunch = () -> {
            preparing.countDown();
            hold.await(30, TimeUnit.SECONDS);
        };
        final AtomicInteger exit = new AtomicInteger(Integer.MIN_VALUE);
        final Thread helper = new Thread(() -> {
            final var out = new PrintStream(new ByteArrayOutputStream());
            final var err = new PrintStream(new ByteArrayOutputStream());
            exit.set(FrameworkUpdateHelper.run(
                    new String[] {
                        "--root",
                        root.toString(),
                        "--work",
                        work.toString(),
                        "--prepare-launch",
                        Long.toString(ProcessHandle.current().pid()),
                        "--launch-token",
                        hex(42)
                    },
                    out,
                    err,
                    Map.of(),
                    ProcessHandle::of,
                    Instant::now));
        });
        try {
            helper.start();
            assertTrue(preparing.await(30, TimeUnit.SECONDS), "helper should reach startup preparation");
            assertEquals(1, FrameworkUpdateLaunchLease.readAll(work).size(), "LAUNCHING published before prepare");
            helper.interrupt();
            helper.join(30_000);
            assertFalse(helper.isAlive(), "helper must finish after a real interrupt");
        } finally {
            hold.countDown();
            helper.join(10_000);
            if (helper.isAlive()) helper.interrupt();
            FrameworkUpdateHelper.beforePrepareLaunch = original;
        }
        assertFalse(SPAWN_ALLOWED.contains(exit.get()), "an interrupted launch must not allow Cubism to start");
        assertEquals(FrameworkUpdateHelper.EXIT_FAILED, exit.get());
        assertTrue(
                FrameworkUpdateLaunchLease.readAll(work).isEmpty(),
                "the interrupt cleanup must delete this invocation's LAUNCHING entry");
    }

    @Test
    void publishCollisionKeepsForeignUsingEntryAndPublishesNothing() throws Exception {
        // A second live instance already holds a USING entry. Publishing our own LAUNCHING under the same token
        // fails, which is a non-spawn result: the foreign USING entry must survive untouched (never released by
        // an invocation that did not create it) and no LAUNCHING entry may appear.
        final String shared = hex(43);
        final String started =
                ProcessHandle.current().info().startInstant().orElseThrow().toString();
        FrameworkUpdateLaunchLease.publishUsing(
                work, root, ProcessHandle.current().pid(), started, shared);
        final var err = new PrintStream(new ByteArrayOutputStream());
        final int exit = FrameworkUpdateHelper.run(
                new String[] {
                    "--root",
                    root.toString(),
                    "--work",
                    work.toString(),
                    "--prepare-launch",
                    Long.toString(ProcessHandle.current().pid()),
                    "--launch-token",
                    shared
                },
                new PrintStream(new ByteArrayOutputStream()),
                err,
                Map.of(),
                ProcessHandle::of,
                Instant::now);
        assertEquals(FrameworkUpdateHelper.EXIT_FAILED, exit, err.toString());
        final List<FrameworkUpdateLaunchLease.Entry> entries = FrameworkUpdateLaunchLease.readAll(work);
        assertEquals(1, entries.size(), "the foreign USING entry must be the only lease entry");
        assertEquals(FrameworkUpdateLaunchLease.Kind.USING, entries.get(0).kind());
        assertEquals(shared, entries.get(0).token());
    }

    @Test
    void discardedPendingUpdateKeepsLaunchingBecauseCubismMayStart() throws Exception {
        // A pending task whose stage vanished is discarded and reported as "nothing to do" (exit 10). The
        // launcher then starts Cubism with the token, so the LAUNCHING entry must be kept for the premain
        // handshake: cleanup may never over-release a spawn-allowed result.
        final Path missingStage = temporary.resolve("stage-gone");
        FrameworkUpdatePendingTask.write(work, root, missingStage, "9.9.9");
        final String token = hex(44);
        final var err = new PrintStream(new ByteArrayOutputStream());
        final int exit = FrameworkUpdateHelper.run(
                new String[] {
                    "--root",
                    root.toString(),
                    "--work",
                    work.toString(),
                    "--prepare-launch",
                    Long.toString(ProcessHandle.current().pid()),
                    "--launch-token",
                    token
                },
                new PrintStream(new ByteArrayOutputStream()),
                err,
                Map.of(),
                ProcessHandle::of,
                Instant::now);
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, exit, err.toString());
        final List<FrameworkUpdateLaunchLease.Entry> entries = FrameworkUpdateLaunchLease.readAll(work);
        assertEquals(1, entries.size(), "a spawn-allowed result must keep the LAUNCHING entry");
        assertEquals(FrameworkUpdateLaunchLease.Kind.LAUNCHING, entries.get(0).kind());
        assertEquals(token, entries.get(0).token());
    }

    @Test
    void lockScopeFailureNeverReleasesALeaseThisInvocationDidNotPublish() throws Exception {
        // The same launcher process already published a LAUNCHING entry in an earlier attempt. This invocation
        // then fails to acquire the transaction lock and therefore never published anything; when it retries the
        // lock for cleanup it must not delete the entry it did not create.
        final long pid = ProcessHandle.current().pid();
        final String started =
                ProcessHandle.current().info().startInstant().orElseThrow().toString();
        final String token = hex(45);
        FrameworkUpdateLaunchLease.publishLaunching(work, root, pid, started, token);

        final CountDownLatch reachedCleanup = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final Runnable originalHook = FrameworkUpdateHelper.beforeLockScopeCleanup;
        FrameworkUpdateHelper.beforeLockScopeCleanup = () -> {
            reachedCleanup.countDown();
            try {
                proceed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        final AtomicInteger exit = new AtomicInteger(Integer.MIN_VALUE);
        final Thread helper = new Thread(() -> {
            final var out = new PrintStream(new ByteArrayOutputStream());
            final var err = new PrintStream(new ByteArrayOutputStream());
            exit.set(FrameworkUpdateHelper.run(
                    new String[] {
                        "--root",
                        root.toString(),
                        "--work",
                        work.toString(),
                        "--prepare-launch",
                        Long.toString(pid),
                        "--launch-token",
                        token
                    },
                    out,
                    err,
                    Map.of(),
                    ProcessHandle::of,
                    Instant::now));
        });
        FrameworkUpdateLock blocker = FrameworkUpdateLock.acquire(work);
        try {
            helper.start();
            assertTrue(reachedCleanup.await(10, TimeUnit.SECONDS), "helper should reach the lock-scope failure path");
            // Release the competing holder: the old implementation then re-acquired the lock and deleted a lease
            // entry it had not published.
            blocker.close();
            blocker = null;
            proceed.countDown();
            helper.join(30_000);
        } finally {
            proceed.countDown();
            helper.join(10_000);
            if (helper.isAlive()) helper.interrupt();
            FrameworkUpdateHelper.beforeLockScopeCleanup = originalHook;
            if (blocker != null) blocker.close();
        }
        assertFalse(SPAWN_ALLOWED.contains(exit.get()), "a lock-scope failure must not allow Cubism to start");
        final List<FrameworkUpdateLaunchLease.Entry> entries = FrameworkUpdateLaunchLease.readAll(work);
        assertEquals(1, entries.size(), "a lease this invocation did not publish must survive");
        assertEquals(token, entries.get(0).token());
    }

    @Test
    void legacyHandshakeReapsDeadUsingEntriesInsteadOfHittingTheCap() throws Exception {
        // A legacy launch (no launcher token) registers its own USING entry. Dead entries from earlier runs must be
        // reaped under the same transaction lock, otherwise a fixed home permanently refuses every handshake once
        // the entry cap is reached. Dead LAUNCHING entries and live USING entries stay untouched.
        final Path healthWork = FrameworkUpdateHealth.workDirectory(root);
        final Path leaseDir = healthWork.resolve("lease");
        Files.createDirectories(leaseDir);
        for (int i = 0; i < FrameworkUpdateLaunchLease.MAX_ENTRIES - 2; i++) {
            writeRawEntryInto(leaseDir, hex(1000 + i), "USING", 999_999, STARTED);
        }
        final String liveStarted =
                ProcessHandle.current().info().startInstant().orElseThrow().toString();
        writeRawEntryInto(leaseDir, hex(2000), "USING", ProcessHandle.current().pid(), liveStarted);
        writeRawEntryInto(leaseDir, hex(2001), "LAUNCHING", 999_999, STARTED);
        assertEquals(
                FrameworkUpdateLaunchLease.MAX_ENTRIES,
                FrameworkUpdateLaunchLease.readAll(healthWork).size());

        FrameworkUpdateHealth.confirmRuntimeUsing(root, null);

        final List<FrameworkUpdateLaunchLease.Entry> entries = FrameworkUpdateLaunchLease.readAll(healthWork);
        assertEquals(3, entries.size(), "dead USING entries are reaped; live USING and LAUNCHING are kept");
        assertEquals(
                1,
                entries.stream()
                        .filter(e -> e.kind() == FrameworkUpdateLaunchLease.Kind.LAUNCHING)
                        .count());
        assertEquals(
                2,
                entries.stream()
                        .filter(e -> e.kind() == FrameworkUpdateLaunchLease.Kind.USING)
                        .count());
        assertTrue(
                entries.stream()
                        .anyMatch(e -> e.pid() == ProcessHandle.current().pid()),
                "the live USING entry of the running instance must survive");
    }

    /** Nested helper JVM: stays alive so the real OS process guard can observe a live "agent user". */
    public static final class SleepProbe {
        public static void main(final String[] args) throws Exception {
            Thread.sleep(Long.parseLong(args.length > 1 ? args[1] : "60000"));
        }
    }

    /** Ed25519 signature envelope for a staged manifest, matching the helper's verification format. */
    private static byte[] signedEnvelope(final byte[] manifest, final KeyPair keys) throws Exception {
        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(manifest);
        return UpdateFileSupport.JSON.writeValueAsBytes(UpdateFileSupport.JSON
                .createObjectNode()
                .put("format", UpdateManifestVerifier.SIGNATURE_FORMAT)
                .put("schemaVersion", 1)
                .put("algorithm", "Ed25519")
                .put("keyId", "fixture")
                .put("manifestSha256", FrameworkUpdateTaskStore.hash(manifest))
                .put("signature", Base64.getEncoder().encodeToString(signer.sign())));
    }

    @Test
    void busyFromRealProcessGuardDuringPendingVerificationReleasesOwnLaunching() throws Exception {
        // Component (non-installed) pending path: policyProblem passes and the real OS process guard runs right
        // before applyPendingHealth. A launcher that already published its LAUNCHING entry and then observes a
        // live agent user must return EXIT_BUSY(75), must not install payload and must not leave its lease behind.
        final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Path agent = root.resolve("turboism-agent.jar");
        Files.writeString(agent, "agent-old");
        Files.writeString(
                root.resolve("installed-build.json"),
                "{\"schemaVersion\":1,\"variant\":\"full\",\"version\":\"1.0.0\"}\n");
        final Path stage = temporary.resolve("stage");
        final byte[] payload = "agent-new".getBytes(StandardCharsets.UTF_8);
        final Path staged = stage.resolve("files").resolve("turboism-agent.jar");
        Files.createDirectories(staged.getParent());
        Files.write(staged, payload);
        final String hash = FrameworkUpdateTaskStore.hash(payload);
        final var manifest = new UpdateManifest(
                1,
                "fixture",
                "1.1.0",
                "stable",
                60,
                "c".repeat(40),
                "1.0.0",
                "windows-x64",
                List.of(new UpdateManifestEntry("turboism-agent.jar", payload.length, hash, "sha256:" + hash)));
        final byte[] manifestBytes = manifest.toCanonicalBytes();
        Files.write(stage.resolve("manifest.json"), manifestBytes);
        Files.write(stage.resolve("manifest.json.sig"), signedEnvelope(manifestBytes, keys));
        FrameworkUpdatePendingTask.write(work, root, stage, "1.1.0");

        final AtomicReference<Process> liveAgentUser = new AtomicReference<>();
        final var originalHook = FrameworkUpdateHelper.beforePrepareLaunch;
        FrameworkUpdateHelper.beforePrepareLaunch = () -> {
            // Runs after this invocation published its LAUNCHING entry: start a real, independent JVM whose
            // command line references <root>/turboism-agent.jar, then wait until the product's own guard sees it.
            try {
                final Process process = new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java")
                                        .toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                SleepProbe.class.getName(),
                                agent.toString(),
                                "60000")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                liveAgentUser.set(process);
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!FrameworkUpdateHelper.runtimeInUse(root)) {
                    if (System.nanoTime() >= deadline || !process.isAlive()) {
                        throw new IllegalStateException("the fixture JVM never became visible to the process guard");
                    }
                    Thread.sleep(100);
                }
            } catch (IOException ioFailure) {
                throw new IllegalStateException("the fixture JVM could not be started", ioFailure);
            }
        };
        final var err = new PrintStream(new ByteArrayOutputStream());
        final int exit;
        try {
            exit = FrameworkUpdateHelper.run(
                    new String[] {
                        "--root",
                        root.toString(),
                        "--work",
                        work.toString(),
                        "--prepare-launch",
                        Long.toString(ProcessHandle.current().pid()),
                        "--launch-token",
                        hex(46)
                    },
                    new PrintStream(new ByteArrayOutputStream()),
                    err,
                    Map.of("fixture", keys.getPublic()),
                    ProcessHandle::of,
                    Instant::now);
        } finally {
            FrameworkUpdateHelper.beforePrepareLaunch = originalHook;
            final Process process = liveAgentUser.get();
            if (process != null) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                assertFalse(process.isAlive(), "the fixture JVM must be gone before the test ends");
            }
        }
        assertEquals(FrameworkUpdateHelper.EXIT_BUSY, exit, err.toString());
        assertTrue(
                FrameworkUpdateLaunchLease.readAll(work).isEmpty(),
                "a late busy result must release this invocation's LAUNCHING entry");
        assertEquals("agent-old", Files.readString(agent), "no payload may be installed on the busy path");
        assertTrue(Files.exists(work.resolve("pending.json")), "the pending task is kept for the next launch");
    }

    /** Exit codes the launcher accepts as "start Cubism". */
    private static final java.util.Set<Integer> SPAWN_ALLOWED = java.util.Set.of(
            FrameworkUpdateHelper.EXIT_APPLIED, FrameworkUpdateHelper.EXIT_NOTHING,
            FrameworkUpdateHelper.EXIT_ROLLED_BACK, FrameworkUpdateHelper.EXIT_RECOVERED);

    /** Write a valid-JSON raw entry using the production serializer (same token in filename and content). */
    private void writeRawEntry(final String token, final String kind, final long pid, final String started)
            throws Exception {
        writeRawEntryFile(token, token, kind, pid, started);
    }

    /** Write a raw entry whose filename token may differ from the content token. */
    private void writeRawEntryFile(
            final String fileToken, final String contentToken, final String kind, final long pid, final String started)
            throws Exception {
        writeRawEntryInto(work.resolve("lease"), fileToken, contentToken, kind, pid, started);
    }

    /** Write a raw entry into an explicit lease directory (the health handshake uses a different work directory). */
    private void writeRawEntryInto(
            final Path dir, final String fileToken, final String kind, final long pid, final String started)
            throws Exception {
        writeRawEntryInto(dir, fileToken, fileToken, kind, pid, started);
    }

    private void writeRawEntryInto(
            final Path dir,
            final String fileToken,
            final String contentToken,
            final String kind,
            final long pid,
            final String started)
            throws Exception {
        Files.createDirectories(dir);
        final var node = UpdateFileSupport.JSON.createObjectNode();
        node.put("format", "turboism-launch-lease-v1");
        node.put("schemaVersion", 1);
        node.put("kind", kind);
        node.put("pid", pid);
        node.put("started", started);
        node.put("root", root.toAbsolutePath().normalize().toString());
        node.put("token", contentToken);
        Files.write(dir.resolve("lease-" + fileToken + ".json"), UpdateFileSupport.JSON.writeValueAsBytes(node));
    }
}
