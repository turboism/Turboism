package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AcpProcessTransportTest {

    /** Observation window that lets a surviving child write at least one more heartbeat byte. */
    private static final long HEARTBEAT_SETTLE_MILLIS = 1500L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void commandIsAnOpaqueArgvList() {
        assertEquals(
                java.util.List.of("C:\\Program Files\\agent.exe", "acp"),
                new AgentLaunchSpec(java.util.List.of("C:\\Program Files\\agent.exe", "acp"), temporaryDirectory)
                        .command());
        assertEquals(
                java.util.List.of("agent", "acp", "--model", "vendor/model with space"),
                new AgentLaunchSpec(
                                java.util.List.of("agent", "acp", "--model", "vendor/model with space"),
                                temporaryDirectory)
                        .command());
    }

    @Test
    void specValidationRejectsEmptyAndOversizedArgv() {
        assertThrows(
                IllegalArgumentException.class, () -> new AgentLaunchSpec(java.util.List.of(), temporaryDirectory));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentLaunchSpec(java.util.List.of("agent", ""), temporaryDirectory));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentLaunchSpec(java.util.List.of("agent", "x".repeat(5000)), temporaryDirectory));
    }

    @Test
    void descendantSamplingBacksOffAfterTheLaunchWindow() {
        // A short-lived helper forked at start-up must be seen, so the launch window samples fast.
        assertTrue(AcpProcessTransport.sampleIntervalMillis(0L) <= 10L);
        assertTrue(AcpProcessTransport.sampleIntervalMillis(199L) <= 10L);
        // A long session must not traverse the process tree at launch rate.
        final long steady = AcpProcessTransport.sampleIntervalMillis(200L);
        assertTrue(steady >= 250L && steady <= 500L, "steady sampling period was " + steady + "ms");
        assertEquals(steady, AcpProcessTransport.sampleIntervalMillis(3_600_000L));
    }

    @Test
    void windowsTeardownCapturesTheProcessTreeWithoutABackgroundSampler() {
        // A cmd.exe shim reparents its child on exit, so sampling cannot be trusted on Windows.
        assertFalse(AcpProcessTransport.samplesDescendantsInBackground("Windows 11"));
        assertFalse(AcpProcessTransport.samplesDescendantsInBackground("windows server 2025"));
        assertTrue(AcpProcessTransport.samplesDescendantsInBackground("Linux"));
        assertTrue(AcpProcessTransport.samplesDescendantsInBackground(null));
    }

    /**
     * Guards the Windows regression where only {@code cmd.exe} was terminated and the CLI child
     * survived as an orphan.
     *
     * <p>The child appends to a heartbeat file, which is the only portable liveness signal: a batch
     * file cannot report its own PID. A frozen heartbeat after teardown proves that no process in
     * the launch tree survived.</p>
     */
    @Test
    void teardownTerminatesDescendantsSpawnedBehindAShellShim() throws Exception {
        final Path heartbeat = temporaryDirectory.resolve("shim-heartbeat.txt");
        final Path fixture = shellShimTreeFixture("shim-agent", heartbeat);

        final AcpProcessTransport transport = AcpProcessTransport.start(
                new AgentLaunchSpec(java.util.List.of(fixture.toString()), temporaryDirectory));
        try {
            awaitHeartbeat(heartbeat);
        } finally {
            transport.terminate(Duration.ofMillis(750));
        }
        final long settled = heartbeatSize(heartbeat);
        Thread.sleep(HEARTBEAT_SETTLE_MILLIS);
        assertEquals(
                settled,
                heartbeatSize(heartbeat),
                "a descendant survived teardown, so the shell shim leaked a child process");
    }

    @Test
    void validationBridgeRequiresTheSystemPropertyAndAJavaExecutable() {
        final AgentLaunchSpec javaExe = new AgentLaunchSpec(
                java.util.List.of("C:\\Program Files\\Live2D Cubism 5.3\\app\\jre\\bin\\java.exe"), temporaryDirectory);
        final AgentLaunchSpec plainExecutable = new AgentLaunchSpec(
                java.util.List.of(temporaryDirectory.resolve("agent.cmd").toString()), temporaryDirectory);
        try {
            System.clearProperty("turboism.acp.validation.bridge");
            assertFalse(AcpProcessTransport.validationJavaBridge(javaExe));
            System.setProperty("turboism.acp.validation.bridge", "true");
            assertTrue(AcpProcessTransport.validationJavaBridge(javaExe));
            assertFalse(AcpProcessTransport.validationJavaBridge(plainExecutable));
        } finally {
            System.clearProperty("turboism.acp.validation.bridge");
        }
    }

    @Test
    void validationBridgeRewritesCommandToTheBridgeLaunch() {
        try {
            System.setProperty("turboism.acp.validation.bridge", "true");
            System.setProperty("turboism.acp.validation.bridgeClassPath", "Z:\\home\\acp-fake-agent.jar");
            System.setProperty("turboism.acp.validation.bridgeConfig", "Z:\\home\\agent.properties");
            assertEquals(
                    java.util.List.of(
                            "C:\\jre\\java.exe",
                            "-Dturboism.acp.validation.bridgeConfig=Z:\\home\\agent.properties",
                            "-cp",
                            "Z:\\home\\acp-fake-agent.jar",
                            "acp",
                            "serve"),
                    AcpProcessTransport.command(
                            new AgentLaunchSpec(java.util.List.of("C:\\jre\\java.exe", "serve"), temporaryDirectory)));
        } finally {
            System.clearProperty("turboism.acp.validation.bridge");
            System.clearProperty("turboism.acp.validation.bridgeClassPath");
            System.clearProperty("turboism.acp.validation.bridgeConfig");
        }
    }

    @Test
    void validationBridgeFailsClosedWhenPropertiesAreMissing() {
        try {
            System.setProperty("turboism.acp.validation.bridge", "true");
            System.clearProperty("turboism.acp.validation.bridgeClassPath");
            System.clearProperty("turboism.acp.validation.bridgeConfig");
            final AgentLaunchSpec launch =
                    new AgentLaunchSpec(java.util.List.of("C:\\jre\\java.exe"), temporaryDirectory);
            assertThrows(IllegalStateException.class, () -> AcpProcessTransport.command(launch));
            System.setProperty("turboism.acp.validation.bridgeClassPath", "Z:\\home\\acp-fake-agent.jar");
            assertThrows(IllegalStateException.class, () -> AcpProcessTransport.command(launch));
        } finally {
            System.clearProperty("turboism.acp.validation.bridge");
            System.clearProperty("turboism.acp.validation.bridgeClassPath");
            System.clearProperty("turboism.acp.validation.bridgeConfig");
        }
    }

    @Test
    void validationBridgeStripsInheritedJavaOptionVariables() {
        final java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put("JAVA_TOOL_OPTIONS", "-javaagent:x");
        environment.put("_JAVA_OPTIONS", "-Xmx1g");
        environment.put("jdk_java_options", "-Dy=1");
        environment.put("PATH", "/bin");
        AcpProcessTransport.stripJavaOptionVariables(environment);
        assertEquals(java.util.Map.of("PATH", "/bin"), environment);
    }

    /**
     * Verifies that cleanup reaches descendants that outlive the launch process.
     *
     * <p>On POSIX the launcher exits first and the background sampler must retain the child handle.
     * Windows cannot sample that way, because a batch shim reparents its child on exit, so the same
     * contract is asserted through teardown of a fast-exiting shim parent.</p>
     */
    @Test
    void bestEffortCleanupUsesRetainedChildHandleAfterParentExit() throws Exception {
        if (AgentLocator.isWindows()) {
            assertFastExitingShimParentLeavesNoDescendant();
            return;
        }
        final Path childPid = temporaryDirectory.resolve("late-child.pid");
        final Path grandchildPid = temporaryDirectory.resolve("late-grandchild.pid");
        final Path childScript = temporaryDirectory.resolve("late-child.sh");
        Files.writeString(childScript, """
            #!/bin/sh
            trap '' TERM
            sleep 0.2
            sh -c 'trap "" TERM; while :; do sleep 1; done' &
            grandchild=$!
            printf '%s' "$grandchild" > "$1"
            while :; do sleep 1; done
            """);
        childScript.toFile().setExecutable(true, true);
        final Path executable = temporaryDirectory.resolve("late fixture agent");
        Files.writeString(executable, """
            #!/bin/sh
            "%s" "%s" &
            printf '%%s' "$!" > "%s"
            sleep 1
            exit 0
            """.formatted(childScript, grandchildPid, childPid));
        executable.toFile().setExecutable(true, true);

        final AcpProcessTransport transport = AcpProcessTransport.start(
                new AgentLaunchSpec(java.util.List.of(executable.toString()), temporaryDirectory));
        long child = -1L;
        long grandchild = -1L;
        try {
            assertTrue(awaitFile(childPid), "fixture child pid was not published");
            child = Long.parseLong(Files.readString(childPid));
            final ProcessHandle childHandle = ProcessHandle.of(child).orElseThrow();
            for (int attempt = 0; attempt < 200 && !transport.retains(child); attempt++) {
                Thread.sleep(5L);
            }
            assertTrue(transport.retains(child), "fixture child was not sampled for retention");
            for (int attempt = 0; attempt < 400 && transport.isAlive(); attempt++) {
                Thread.sleep(5L);
            }
            assertFalse(transport.isAlive(), "fixture parent did not exit");
            transport.terminate(Duration.ofMillis(750));
            if (awaitFile(grandchildPid)) {
                grandchild = Long.parseLong(Files.readString(grandchildPid));
            }
            assertTrue(
                    awaitGone(childHandle, Duration.ofSeconds(5)), "retained child survived after direct process exit");
            if (grandchild > 0L) {
                assertTrue(
                        awaitGone(grandchild, Duration.ofSeconds(3)),
                        "observed late-spawned grandchild survived best-effort cleanup");
            }
        } finally {
            killFixtureProcess(child);
            killFixtureProcess(grandchild);
            transport.terminate(Duration.ZERO);
        }
    }

    /**
     * Asserts that teardown removes the child of a shim parent that already exited.
     *
     * <p>The frozen heartbeat is the only portable proof: a batch file cannot report its own PID,
     * and any surviving writer would append at least one more byte.</p>
     */
    private void assertFastExitingShimParentLeavesNoDescendant() throws Exception {
        final Path heartbeat = temporaryDirectory.resolve("late-heartbeat.txt");
        final Path fixture = shellShimTreeFixture("late-shim-agent", heartbeat);

        final AcpProcessTransport transport = AcpProcessTransport.start(
                new AgentLaunchSpec(java.util.List.of(fixture.toString()), temporaryDirectory));
        try {
            awaitHeartbeat(heartbeat);
        } finally {
            transport.terminate(Duration.ofMillis(750));
        }
        assertHeartbeatFrozen(heartbeat, "a descendant survived teardown of a fast-exiting shim parent");
    }

    /**
     * Verifies that teardown force-kills a descendant that ignores the graceful request.
     *
     * <p>The child traps the graceful signal on POSIX and keeps running on Windows, so the frozen
     * heartbeat proves that teardown escalated instead of returning after the grace period.</p>
     */
    @Test
    void teardownForceKillsAChildThatIgnoresGracefulTermination() throws Exception {
        final Path heartbeat = temporaryDirectory.resolve("force-heartbeat.txt");
        final Path fixture = shellShimTreeFixture("force-agent", heartbeat);

        final AcpProcessTransport transport = AcpProcessTransport.start(
                new AgentLaunchSpec(java.util.List.of(fixture.toString()), temporaryDirectory));
        try {
            awaitHeartbeat(heartbeat);
        } finally {
            transport.terminate(Duration.ofMillis(100));
        }
        assertHeartbeatFrozen(heartbeat, "descendant survived a forcible teardown");
    }

    /**
     * Asserts that no process in the launch tree still writes to the heartbeat file.
     *
     * <p>A child writes faster than the observation window, so unchanged size proves the tree is
     * gone; a single surviving writer would add at least one byte.</p>
     */
    private static void assertHeartbeatFrozen(final Path heartbeat, final String message) throws InterruptedException {
        final long settled = heartbeatSize(heartbeat);
        Thread.sleep(HEARTBEAT_SETTLE_MILLIS);
        assertEquals(settled, heartbeatSize(heartbeat), message);
    }

    /** Waits for a fixture to publish a file. */
    private static boolean awaitFile(final Path path) throws InterruptedException {
        for (int attempt = 0; attempt < 400 && !Files.exists(path); attempt++) {
            Thread.sleep(5L);
        }
        return Files.exists(path);
    }

    private static void killFixtureProcess(final long pid) {
        if (pid <= 0L) return;
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
    }

    /**
     * Builds an agent fixture whose child keeps rewriting a heartbeat file until terminated.
     *
     * <p>A heartbeat is the only portable liveness signal here: a batch file cannot report its own
     * PID, and a frozen heartbeat proves that no process in the launch tree still writes.</p>
     */
    private Path shellShimTreeFixture(final String name, final Path heartbeat) throws java.io.IOException {
        final boolean windows = AgentLocator.isWindows();
        final Path child = temporaryDirectory.resolve(windows ? name + "-child.cmd" : name + "-child.sh");
        final Path fixture = temporaryDirectory.resolve(windows ? name + ".cmd" : name + ".sh");
        if (windows) {
            // A batch child appends one heartbeat byte per tick; ping supplies the tick delay.
            Files.writeString(
                    child,
                    "@echo off\r\n"
                            + ":loop\r\n"
                            + ">>\"%~dp0"
                            + heartbeat.getFileName()
                            + "\" echo .\r\n"
                            + "ping -n 2 127.0.0.1 >nul\r\n"
                            + "goto loop\r\n");
            Files.writeString(
                    fixture,
                    "@echo off\r\n"
                            + "start \"\" /b \"%~dp0"
                            + child.getFileName()
                            + "\"\"\r\n"
                            + "ping -n 300 127.0.0.1 >nul\r\n");
            return fixture;
        }
        Files.writeString(
                child,
                "#!/bin/sh\n"
                        + "trap '' TERM\n"
                        + "while :; do\n"
                        + "  printf '.' >> \"$1\"\n"
                        + "  sleep 0.05\n"
                        + "done\n");
        child.toFile().setExecutable(true, true);
        Files.writeString(
                fixture, "#!/bin/sh\n" + "trap '' TERM\n" + "sh \"$1\" \"$2\" &\n" + "while :; do sleep 1; done\n");
        fixture.toFile().setExecutable(true, true);
        return fixture;
    }

    /** Waits until the heartbeat file exists and holds at least one byte. */
    private static long awaitHeartbeat(final Path heartbeat) throws InterruptedException {
        for (int attempt = 0; attempt < 4000; attempt++) {
            final long size = heartbeatSize(heartbeat);
            if (size > 0L) return size;
            Thread.sleep(5L);
        }
        throw new AssertionError("fixture child never wrote a heartbeat");
    }

    /** Reads the heartbeat size; a missing file reports 0 so growth comparisons stay simple. */
    private static long heartbeatSize(final Path heartbeat) {
        try {
            return Files.exists(heartbeat) ? Files.size(heartbeat) : 0L;
        } catch (java.io.IOException failure) {
            return 0L;
        }
    }

    private static boolean awaitGone(final long pid, final Duration timeout) throws InterruptedException {
        final ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        return handle == null || awaitGone(handle, timeout);
    }

    private static boolean awaitGone(final ProcessHandle handle, final Duration timeout) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!handle.isAlive() || zombie(handle)) return true;
            Thread.sleep(10L);
        }
        return !handle.isAlive() || zombie(handle);
    }

    private static boolean zombie(final ProcessHandle handle) {
        final Path status = Path.of("/proc", Long.toString(handle.pid()), "status");
        if (!Files.isRegularFile(status)) return false;
        try {
            return Files.readAllLines(status).stream()
                    .anyMatch(line -> line.startsWith("State:") && line.contains("Z"));
        } catch (java.io.IOException ignored) {
            return false;
        }
    }
}
