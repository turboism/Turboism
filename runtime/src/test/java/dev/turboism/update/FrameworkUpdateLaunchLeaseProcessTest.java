package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.core.FrameworkBuildInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real cross-process launch-lease loop. Exercises the production {@link FrameworkUpdateHelper#main} entry point
 * (installedAuthority path) against a real second JVM that performs the premain handshake, so the live/dead USING
 * and LAUNCHING transitions are observed across process boundaries — not through the package-local test seam.
 */
final class FrameworkUpdateLaunchLeaseProcessTest {
    @TempDir
    Path temporary;

    private Path home;
    private Path work;
    private final List<Process> spawned = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        home = temporary.resolve("home");
        work = home.resolve("state/runtime/update/helper");
        Files.createDirectories(home);
        Files.writeString(home.resolve("turboism-agent.jar"), "agent");
    }

    @AfterEach
    void cleanupProcesses() throws Exception {
        for (final Process process : spawned) {
            if (process.isAlive()) process.destroyForcibly();
        }
        for (final Process process : spawned) {
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** A launcher JVM that publishes its own LAUNCHING entry via the production helper, then exits (pid goes dead). */
    public static final class Launcher {
        public static void main(final String[] args) {
            FrameworkUpdateHelper.main(new String[] {
                "--root",
                args[0],
                "--work",
                args[1],
                "--prepare-launch",
                Long.toString(ProcessHandle.current().pid()),
                "--launch-token",
                args[2]
            });
        }
    }

    /** A runtime JVM that performs the premain handshake, then stays alive or exits. */
    public static final class RuntimeHandshake {
        public static void main(final String[] args) throws Exception {
            final Path home = Path.of(args[0]);
            final String token = args[1].equals("null") ? null : args[1];
            final boolean stay = args.length > 2 && args[2].equals("stay");
            try {
                FrameworkUpdateHealth.confirmRuntimeUsing(home, token);
                System.out.println("HANDSHAKE_OK");
                System.out.flush();
            } catch (Throwable failure) {
                System.out.println("HANDSHAKE_FAIL: " + failure.getMessage());
                System.out.flush();
                System.exit(90);
            }
            if (stay) {
                Thread.sleep(60_000);
            }
        }
    }

    /** A runtime JVM that handshakes, then runs the production startup-health path ({@code startupSucceeded})
     * from a JVM whose FrameworkBuildInfo is loaded from the installed agent JAR, then stays alive. */
    public static final class RuntimeHandshakeHealth {
        public static void main(final String[] args) throws Exception {
            final Path home = Path.of(args[0]);
            final String token = args[1].equals("null") ? null : args[1];
            final boolean stay = args.length > 2 && args[2].equals("stay");
            try {
                FrameworkUpdateHealth.confirmRuntimeUsing(home, token);
                System.out.println("HANDSHAKE_OK");
                System.out.flush();
                final AtomicReference<String> diagnostic = new AtomicReference<>();
                FrameworkUpdateHealth.startupSucceeded(home, diagnostic::set);
                System.out.println("DIAGNOSTIC: " + diagnostic.get());
                System.out.flush();
            } catch (Throwable failure) {
                System.out.println("PROBE_FAIL: " + failure.getMessage());
                System.out.flush();
                System.exit(90);
            }
            if (stay) {
                Thread.sleep(60_000);
            }
        }
    }

    /** A launcher JVM that publishes its own LAUNCHING entry, then releases it while still alive, then exits. */
    public static final class PrepareReleaseLauncher {
        public static void main(final String[] args) {
            final Path home = Path.of(args[0]);
            final Path work = Path.of(args[1]);
            final String token = args[2];
            final long pid = ProcessHandle.current().pid();
            final Map<String, PublicKey> keys = Map.of();
            final int prepare = FrameworkUpdateHelper.runInstalled(
                    new String[] {
                        "--root",
                        home.toString(),
                        "--work",
                        work.toString(),
                        "--prepare-launch",
                        Long.toString(pid),
                        "--launch-token",
                        token
                    },
                    System.out,
                    System.err,
                    keys,
                    ProcessHandle::of,
                    Instant::now);
            System.out.println("PREPARE: " + prepare);
            System.out.flush();
            final int release = FrameworkUpdateHelper.runInstalled(
                    new String[] {
                        "--root",
                        home.toString(),
                        "--work",
                        work.toString(),
                        "--release-launch",
                        Long.toString(pid),
                        "--launch-token",
                        token
                    },
                    System.out,
                    System.err,
                    keys,
                    ProcessHandle::of,
                    Instant::now);
            System.out.println("RELEASE: " + release);
            System.out.flush();
            System.exit(release);
        }
    }

    private Process java(final Path output, final String... args) throws Exception {
        return launch(output, System.getProperty("java.class.path"), args);
    }

    /** Launches a real runtime JVM whose FrameworkBuildInfo is loaded from the installed agent JAR, so that
     * {@code FrameworkRuntimeArtifact.resolve()} binds to home/turboism-agent.jar (the production health path). */
    private Process javaWithAgent(final Path output, final String... args) throws Exception {
        final String classpath =
                home.resolve("turboism-agent.jar") + java.io.File.pathSeparator + System.getProperty("java.class.path");
        return launch(output, classpath, args);
    }

    private Process launch(final Path output, final String classpath, final String... args) throws Exception {
        final String executable =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final String[] command = new String[args.length + 3];
        command[0] = executable;
        command[1] = "-cp";
        command[2] = classpath;
        System.arraycopy(args, 0, command, 3, args.length);
        final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        spawned.add(process);
        return process;
    }

    private static String waitFor(final Path output, final String marker, final long seconds) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (Files.exists(output)) {
                final String text = Files.readString(output);
                if (text.contains(marker)) return text;
            }
            Thread.sleep(100);
        }
        return Files.exists(output) ? Files.readString(output) : "";
    }

    /** Spawns a real helper {@code --recover} and asserts it is refused with EXIT_BUSY while a lease blocks it. */
    private void assertRecoverBusy(final String name) throws Exception {
        final Path log = temporary.resolve(name);
        final Process recover = java(
                log,
                FrameworkUpdateHelper.class.getName(),
                "--recover",
                "--root",
                home.toString(),
                "--work",
                work.toString());
        assertTrue(recover.waitFor(60, TimeUnit.SECONDS), "recover timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_BUSY, recover.exitValue(), Files.readString(log));
    }

    /** Leaves a real {@code AWAITING_HEALTH} journal on disk via the applier's unauthenticated component path.
     * The installed agent is a real JAR whose {@code FrameworkBuildInfo} reports {@code version} and whose code
     * source is home/turboism-agent.jar, so a child JVM can exercise the production {@code startupSucceeded} path. */
    private void stageAwaitingHealth(final String version) throws Exception {
        final byte[] agent = buildAgentJar(version);
        final Path stage = temporary.resolve("stage");
        Files.createDirectories(stage);
        Files.write(stage.resolve("turboism-agent.jar"), agent);
        final String hash = FrameworkUpdateTaskStore.hash(agent);
        final var entry = new UpdateManifestEntry("turboism-agent.jar", agent.length, hash, "sha256:" + hash);
        final var manifest = new UpdateManifest(
                1, "fixture", version, "stable", 50, "a".repeat(40), "0.1.0", "windows-x64", List.of(entry));
        final var applier = new FrameworkUpdateApplier(home, work);
        final var result = applier.applyPendingHealth(
                manifest, stage, ("{\"version\":\"" + version + "\"}\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(FrameworkUpdateApplier.Outcome.AWAITING_HEALTH, result.outcome());
        assertTrue(Files.exists(work.resolve(FrameworkUpdateApplier.JOURNAL)), "AWAITING_HEALTH journal retained");
    }

    /** Builds a minimal agent JAR carrying the real FrameworkBuildInfo class bytes plus an explicitly
     * {@code local}/{@code dirty} identity resource (no CI or release identity, no build number). The no-receipt
     * component health path only needs version/path/hash, never a numbered framework identity. */
    private byte[] buildAgentJar(final String version) throws Exception {
        final byte[] info = classBytes("FrameworkBuildInfo.class");
        final byte[] channel = classBytes("FrameworkBuildInfo$Channel.class");
        final byte[] loaded = classBytes("FrameworkBuildInfo$Loaded.class");
        final byte[] props = ("version=" + version + "\n"
                        + "apiVersion=" + version + "\n"
                        + "Implementation-Version=" + version + "\n"
                        + "channel=stable\n"
                        + "buildNumber=\n"
                        + "sourceRevision=unknown\n"
                        + "buildKind=local\n"
                        + "dirty=true\n")
                .getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(bytes)) {
            jarEntry(jar, "dev/turboism/core/FrameworkBuildInfo.class", info);
            jarEntry(jar, "dev/turboism/core/FrameworkBuildInfo$Channel.class", channel);
            jarEntry(jar, "dev/turboism/core/FrameworkBuildInfo$Loaded.class", loaded);
            jarEntry(jar, "META-INF/turboism/framework-version.properties", props);
        }
        return bytes.toByteArray();
    }

    private static byte[] classBytes(final String name) throws Exception {
        try (var input = FrameworkBuildInfo.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing class resource: " + name);
            return input.readAllBytes();
        }
    }

    private static void jarEntry(final JarOutputStream jar, final String name, final byte[] bytes) throws Exception {
        jar.putNextEntry(new ZipEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    @Test
    void liveUsingBlocksRecoveryThenExitAllowsReap() throws Exception {
        final String token = FrameworkUpdateLaunchLease.newToken();
        // Launcher publishes LAUNCHING and exits.
        final Path prepareLog = temporary.resolve("prepare.log");
        final Process prepare = java(prepareLog, Launcher.class.getName(), home.toString(), work.toString(), token);
        assertTrue(prepare.waitFor(60, TimeUnit.SECONDS), "prepare timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, prepare.exitValue(), Files.readString(prepareLog));

        // Runtime handshake converts LAUNCHING -> USING and stays alive.
        final Path runtimeLog = temporary.resolve("runtime.log");
        final Process runtime = java(runtimeLog, RuntimeHandshake.class.getName(), home.toString(), token, "stay");
        try {
            assertTrue(waitFor(runtimeLog, "HANDSHAKE_OK", 30).contains("HANDSHAKE_OK"), Files.readString(runtimeLog));
            // A helper --recover is refused (EXIT_BUSY=75) while the runtime's USING entry is live, even though
            // CIM cannot see this JVM (its command line never references the agent path).
            final Path recoverLog = temporary.resolve("recover-busy.log");
            final Process recover = java(
                    recoverLog,
                    FrameworkUpdateHelper.class.getName(),
                    "--recover",
                    "--root",
                    home.toString(),
                    "--work",
                    work.toString());
            assertTrue(recover.waitFor(60, TimeUnit.SECONDS), "recover timed out");
            assertEquals(FrameworkUpdateHelper.EXIT_BUSY, recover.exitValue(), Files.readString(recoverLog));
        } finally {
            runtime.destroyForcibly();
            runtime.waitFor(10, TimeUnit.SECONDS);
        }
        // Once the runtime exits, --recover reaps the stale USING and proceeds (no journal → EXIT_NOTHING=10).
        final Path recoverLog = temporary.resolve("recover-after-exit.log");
        final Process recover = java(
                recoverLog,
                FrameworkUpdateHelper.class.getName(),
                "--recover",
                "--root",
                home.toString(),
                "--work",
                work.toString());
        assertTrue(recover.waitFor(60, TimeUnit.SECONDS), "recover timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, recover.exitValue(), Files.readString(recoverLog));
    }

    @Test
    void prepareLaunchPublishesLaunchingAndHandshakeConvertsIt() throws Exception {
        final String token = FrameworkUpdateLaunchLease.newToken();
        // A real launcher JVM publishes LAUNCHING under the transaction lock, then exits (its pid goes dead).
        final Path prepareLog = temporary.resolve("prepare.log");
        final Process prepare = java(prepareLog, Launcher.class.getName(), home.toString(), work.toString(), token);
        assertTrue(prepare.waitFor(60, TimeUnit.SECONDS), "prepare timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, prepare.exitValue(), Files.readString(prepareLog));
        assertTrue(FrameworkUpdateLaunchLease.readAll(work).stream()
                .anyMatch(e -> e.kind() == FrameworkUpdateLaunchLease.Kind.LAUNCHING
                        && e.token().equals(token)));

        // The runtime handshake converts that LAUNCHING entry to USING.
        final Path runtimeLog = temporary.resolve("runtime.log");
        final Process runtime = java(runtimeLog, RuntimeHandshake.class.getName(), home.toString(), token, "stay");
        try {
            assertTrue(waitFor(runtimeLog, "HANDSHAKE_OK", 30).contains("HANDSHAKE_OK"), Files.readString(runtimeLog));
            assertTrue(FrameworkUpdateLaunchLease.readAll(work).stream()
                    .anyMatch(e -> e.kind() == FrameworkUpdateLaunchLease.Kind.USING
                            && e.token().equals(token)));
        } finally {
            runtime.destroyForcibly();
            runtime.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void wrongTokenHandshakeFailsClosed() throws Exception {
        // No LAUNCHING entry exists for this token; the handshake must fail rather than silently register.
        final Path runtimeLog = temporary.resolve("runtime.log");
        final Process runtime = java(
                runtimeLog,
                RuntimeHandshake.class.getName(),
                home.toString(),
                FrameworkUpdateLaunchLease.newToken(),
                "stay");
        assertTrue(runtime.waitFor(60, TimeUnit.SECONDS), "runtime timed out");
        assertEquals(90, runtime.exitValue(), Files.readString(runtimeLog));
        assertTrue(Files.readString(runtimeLog).contains("HANDSHAKE_FAIL"));
        assertTrue(FrameworkUpdateLaunchLease.readAll(work).isEmpty());
    }

    @Test
    void deadUnhandshakenLaunchingBlocksRecovery() throws Exception {
        final String token = FrameworkUpdateLaunchLease.newToken();
        // The launcher JVM publishes LAUNCHING and exits; its pid is now dead and un-handshaken.
        final Path prepareLog = temporary.resolve("prepare.log");
        final Process prepare = java(prepareLog, Launcher.class.getName(), home.toString(), work.toString(), token);
        assertTrue(prepare.waitFor(60, TimeUnit.SECONDS), "prepare timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, prepare.exitValue(), Files.readString(prepareLog));

        // Recovery must be refused (EXIT_BUSY) even though the launcher is dead: termination is unproven.
        final Path recoverLog = temporary.resolve("recover.log");
        final Process recover = java(
                recoverLog,
                FrameworkUpdateHelper.class.getName(),
                "--recover",
                "--root",
                home.toString(),
                "--work",
                work.toString());
        assertTrue(recover.waitFor(60, TimeUnit.SECONDS), "recover timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_BUSY, recover.exitValue(), Files.readString(recoverLog));
        assertEquals(1, FrameworkUpdateLaunchLease.readAll(work).size(), "dead LAUNCHING must remain");
    }

    @Test
    void twoLiveUsingCoexistAndOnlyAllExitAllowsReap() throws Exception {
        final String tokenA = FrameworkUpdateLaunchLease.newToken();
        final String tokenB = FrameworkUpdateLaunchLease.newToken();
        final Path prepareALog = temporary.resolve("prepare-a.log");
        final Process prepareA = java(prepareALog, Launcher.class.getName(), home.toString(), work.toString(), tokenA);
        assertTrue(prepareA.waitFor(60, TimeUnit.SECONDS), "prepare A timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, prepareA.exitValue(), Files.readString(prepareALog));
        final Path prepareBLog = temporary.resolve("prepare-b.log");
        final Process prepareB = java(prepareBLog, Launcher.class.getName(), home.toString(), work.toString(), tokenB);
        assertTrue(prepareB.waitFor(60, TimeUnit.SECONDS), "prepare B timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, prepareB.exitValue(), Files.readString(prepareBLog));

        final Path runtimeALog = temporary.resolve("runtime-a.log");
        final Path runtimeBLog = temporary.resolve("runtime-b.log");
        final Process runtimeA = java(runtimeALog, RuntimeHandshake.class.getName(), home.toString(), tokenA, "stay");
        try {
            // Wait for A's handshake to complete (its transaction lock is released) before starting B, so the two
            // handshakes never race on tryLock and both reliably reach USING.
            assertTrue(
                    waitFor(runtimeALog, "HANDSHAKE_OK", 30).contains("HANDSHAKE_OK"), Files.readString(runtimeALog));
            final Process runtimeB =
                    java(runtimeBLog, RuntimeHandshake.class.getName(), home.toString(), tokenB, "stay");
            try {
                assertTrue(
                        waitFor(runtimeBLog, "HANDSHAKE_OK", 30).contains("HANDSHAKE_OK"),
                        Files.readString(runtimeBLog));
                final var using = FrameworkUpdateLaunchLease.readAll(work).stream()
                        .filter(e -> e.kind() == FrameworkUpdateLaunchLease.Kind.USING)
                        .toList();
                assertEquals(2, using.size(), "two USING entries must coexist without overwriting each other");
                assertEquals(
                        2,
                        using.stream()
                                .map(FrameworkUpdateLaunchLease.Entry::token)
                                .distinct()
                                .count());

                // Both live: recovery refused.
                assertRecoverBusy("recover-both.log");

                // One exits: still refused because the other remains live.
                runtimeA.destroyForcibly();
                assertTrue(runtimeA.waitFor(10, TimeUnit.SECONDS), "runtime A must exit");
                assertRecoverBusy("recover-one.log");

                // The second exits too.
                runtimeB.destroyForcibly();
                assertTrue(runtimeB.waitFor(10, TimeUnit.SECONDS), "runtime B must exit");
            } finally {
                if (runtimeB.isAlive()) runtimeB.destroyForcibly();
                runtimeB.waitFor(10, TimeUnit.SECONDS);
            }
        } finally {
            if (runtimeA.isAlive()) runtimeA.destroyForcibly();
            runtimeA.waitFor(10, TimeUnit.SECONDS);
        }

        // Both really exited: the next helper reaps both stale USING and proceeds (no journal → NOTHING).
        final Path recoverLog = temporary.resolve("recover-after-both.log");
        final Process recover = java(
                recoverLog,
                FrameworkUpdateHelper.class.getName(),
                "--recover",
                "--root",
                home.toString(),
                "--work",
                work.toString());
        assertTrue(recover.waitFor(60, TimeUnit.SECONDS), "recover timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, recover.exitValue(), Files.readString(recoverLog));
        assertTrue(FrameworkUpdateLaunchLease.readAll(work).isEmpty(), "both stale USING entries reaped");
    }

    @Test
    void awaitingHealthCommitKeepsSameLiveJvmUsingUntilExit() throws Exception {
        stageAwaitingHealth("1.1.0");
        final String token = FrameworkUpdateLaunchLease.newToken();
        final Path prepareLog = temporary.resolve("prepare.log");
        final Process prepare = java(prepareLog, Launcher.class.getName(), home.toString(), work.toString(), token);
        assertTrue(prepare.waitFor(60, TimeUnit.SECONDS), "prepare timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, prepare.exitValue(), Files.readString(prepareLog));

        final Path runtimeLog = temporary.resolve("runtime.log");
        final Process runtime =
                javaWithAgent(runtimeLog, RuntimeHandshakeHealth.class.getName(), home.toString(), token, "stay");
        try {
            assertTrue(
                    waitFor(runtimeLog, "DIAGNOSTIC: UPDATE_STARTUP_CONFIRMED", 30)
                            .contains("UPDATE_STARTUP_CONFIRMED"),
                    Files.readString(runtimeLog));
            assertFalse(
                    Files.exists(work.resolve(FrameworkUpdateApplier.JOURNAL)), "health commit must clear the journal");
            assertEquals(
                    1,
                    FrameworkUpdateLaunchLease.readAll(work).stream()
                            .filter(e -> e.kind() == FrameworkUpdateLaunchLease.Kind.USING)
                            .count(),
                    "health commit must not clear the USING lease");
            // The same JVM is still alive, so recovery is still refused.
            assertRecoverBusy("recover-after-health.log");
        } finally {
            runtime.destroyForcibly();
            runtime.waitFor(10, TimeUnit.SECONDS);
        }

        // Only after the JVM really exits does the next helper reap the USING and proceed.
        final Path recoverLog = temporary.resolve("recover-after-exit.log");
        final Process recover = java(
                recoverLog,
                FrameworkUpdateHelper.class.getName(),
                "--recover",
                "--root",
                home.toString(),
                "--work",
                work.toString());
        assertTrue(recover.waitFor(60, TimeUnit.SECONDS), "recover timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, recover.exitValue(), Files.readString(recoverLog));
    }

    @Test
    void consecutivePrepareCyclesReapStaleUsingWithoutAccumulation() throws Exception {
        for (int i = 0; i < 2; i++) {
            // A legacy runtime registers an independent USING entry and then exits, leaving it dead.
            final Path runtimeLog = temporary.resolve("runtime-" + i + ".log");
            final Process runtime = java(runtimeLog, RuntimeHandshake.class.getName(), home.toString(), "null");
            assertTrue(runtime.waitFor(60, TimeUnit.SECONDS), "runtime timed out");
            assertEquals(0, runtime.exitValue(), Files.readString(runtimeLog));
            assertTrue(Files.readString(runtimeLog).contains("HANDSHAKE_OK"));
            assertTrue(FrameworkUpdateLaunchLease.readAll(work).stream()
                    .anyMatch(e -> e.kind() == FrameworkUpdateLaunchLease.Kind.USING));

            // A normal no-pending prepare reaps the dead USING, publishes then releases its own LAUNCHING.
            final String token = FrameworkUpdateLaunchLease.newToken();
            final Path launcherLog = temporary.resolve("launcher-" + i + ".log");
            final Process launcher =
                    java(launcherLog, PrepareReleaseLauncher.class.getName(), home.toString(), work.toString(), token);
            assertTrue(launcher.waitFor(60, TimeUnit.SECONDS), "launcher timed out");
            assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, launcher.exitValue(), Files.readString(launcherLog));
            assertTrue(Files.readString(launcherLog).contains("PREPARE: " + FrameworkUpdateHelper.EXIT_NOTHING));
            assertTrue(Files.readString(launcherLog).contains("RELEASE: " + FrameworkUpdateHelper.EXIT_NOTHING));
            assertTrue(FrameworkUpdateLaunchLease.readAll(work).isEmpty(), "dead USING reaped and LAUNCHING released");
        }
    }

    @Test
    void transactionLockExcludesConcurrentHelperMutation() throws Exception {
        try (var held = FrameworkUpdateLock.acquire(work)) {
            final Path lockedLog = temporary.resolve("recover-locked.log");
            final Process locked = java(
                    lockedLog,
                    FrameworkUpdateHelper.class.getName(),
                    "--recover",
                    "--root",
                    home.toString(),
                    "--work",
                    work.toString());
            assertTrue(locked.waitFor(60, TimeUnit.SECONDS), "recover timed out");
            assertEquals(FrameworkUpdateHelper.EXIT_FAILED, locked.exitValue(), Files.readString(lockedLog));
            assertTrue(Files.readString(lockedLog).contains("Could not acquire update transaction lock"));
        }
        // Once the lock is released, the same helper proceeds (no journal → NOTHING).
        final Path recoverLog = temporary.resolve("recover-after-release.log");
        final Process recover = java(
                recoverLog,
                FrameworkUpdateHelper.class.getName(),
                "--recover",
                "--root",
                home.toString(),
                "--work",
                work.toString());
        assertTrue(recover.waitFor(60, TimeUnit.SECONDS), "recover timed out");
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, recover.exitValue(), Files.readString(recoverLog));
    }
}
