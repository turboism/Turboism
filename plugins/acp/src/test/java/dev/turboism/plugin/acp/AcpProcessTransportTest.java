package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AcpProcessTransportTest {

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
    void windowsTeardownSkipsProcessHandleDescendantEnumeration() {
        assertFalse(AcpProcessTransport.tracksDescendants("Windows 11"));
        assertFalse(AcpProcessTransport.tracksDescendants("windows server 2025"));
        assertTrue(AcpProcessTransport.tracksDescendants("Linux"));
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

    @Test
    void bestEffortCleanupUsesRetainedChildHandleAfterParentExit() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
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
            for (int attempt = 0; attempt < 200 && !Files.exists(childPid); attempt++) {
                Thread.sleep(5L);
            }
            assertTrue(Files.exists(childPid), "fixture child pid was not published");
            child = Long.parseLong(Files.readString(childPid));
            final ProcessHandle childHandle = ProcessHandle.of(child).orElseThrow();
            for (int attempt = 0; attempt < 200 && !transport.retains(child); attempt++) {
                Thread.sleep(5L);
            }
            assertTrue(transport.retains(child), "fixture child was not sampled for retention");
            for (int attempt = 0; attempt < 400 && transport.isAlive(); attempt++) {
                Thread.sleep(5L);
            }
            assertTrue(!transport.isAlive(), "fixture parent did not exit");
            transport.terminate(Duration.ofMillis(750));
            for (int attempt = 0; attempt < 200 && !Files.exists(grandchildPid); attempt++) {
                Thread.sleep(5L);
            }
            if (Files.exists(grandchildPid)) {
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

    @Test
    void teardownForceKillsAChildThatIgnoresGracefulTermination() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        final Path childPid = temporaryDirectory.resolve("child.pid");
        final Path script = temporaryDirectory.resolve("agent-fixture.sh");
        Files.writeString(script, """
            #!/bin/sh
            sh -c 'trap "" TERM; while :; do sleep 1; done' &
            child=$!
            printf '%s' "$child" > "$1"
            trap '' TERM
            while :; do sleep 1; done
            """);
        script.toFile().setExecutable(true, true);
        final Path executable = temporaryDirectory.resolve("fixture agent");
        Files.writeString(executable, """
            #!/bin/sh
            exec "%s" "%s"
            """.formatted(script, childPid));
        executable.toFile().setExecutable(true, true);

        final AcpProcessTransport transport = AcpProcessTransport.start(
                new AgentLaunchSpec(java.util.List.of(executable.toString()), temporaryDirectory));
        long pid = -1L;
        try {
            for (int attempt = 0; attempt < 200 && !Files.exists(childPid); attempt++) {
                Thread.sleep(5L);
            }
            assertTrue(Files.exists(childPid), "fixture child pid was not published");
            pid = Long.parseLong(Files.readString(childPid));
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            transport.terminate(Duration.ofMillis(100));
        }
        final long retainedPid = pid;
        assertTrue(
                awaitGone(retainedPid, Duration.ofSeconds(3)), "retained fixture child survived best-effort cleanup");
    }

    private static void killFixtureProcess(final long pid) {
        if (pid <= 0L) return;
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
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
