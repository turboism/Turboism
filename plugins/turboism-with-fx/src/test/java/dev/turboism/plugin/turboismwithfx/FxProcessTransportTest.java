package dev.turboism.plugin.turboismwithfx;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FxProcessTransportTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void managedRuntimeIsReverifiedImmediatelyBeforeLaunch() throws Exception {
        final Path executable = temporaryDirectory.resolve("fx-managed");
        Files.writeString(executable, "original");
        executable.toFile().setExecutable(true, true);
        final String digest = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(executable))
        );
        final FxLaunchConfiguration configuration = new FxLaunchConfiguration(
            executable.toString(),
            temporaryDirectory,
            FxSecurityMode.FX_NATIVE_TOOLS,
            new FxLaunchConfiguration.ManagedRuntimeIdentity(
                Files.size(executable),
                digest
            )
        );
        Files.writeString(executable, "changed!");

        assertThrows(java.io.IOException.class, () -> FxProcessTransport.start(configuration));
    }

    @Test
    void teardownForceKillsAChildThatIgnoresGracefulTermination() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        final Path childPid = temporaryDirectory.resolve("child.pid");
        final Path script = temporaryDirectory.resolve("fx-fixture.sh");
        Files.writeString(script, """
            #!/bin/sh
            sh -c 'trap "" TERM; while :; do sleep 1; done' &
            child=$!
            printf '%s' "$child" > "$1"
            trap '' TERM
            while :; do sleep 1; done
            """);
        script.toFile().setExecutable(true, true);
        final Path executable = temporaryDirectory.resolve("fixture fx");
        Files.writeString(executable, """
            #!/bin/sh
            exec "%s" "%s"
            """.formatted(script, childPid));
        executable.toFile().setExecutable(true, true);

        final FxProcessTransport transport = FxProcessTransport.start(new FxLaunchConfiguration(
            executable.toString(),
            temporaryDirectory,
            FxSecurityMode.FX_NATIVE_TOOLS
        ));
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
        assertTrue(awaitGone(retainedPid, Duration.ofSeconds(3)),
            "retained fixture child survived process-tree teardown");
    }

    private static boolean awaitGone(final long pid, final Duration timeout)
        throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            final ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle == null || !handle.isAlive() || zombie(handle)) return true;
            Thread.sleep(10L);
        }
        final ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        return handle == null || !handle.isAlive() || zombie(handle);
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
