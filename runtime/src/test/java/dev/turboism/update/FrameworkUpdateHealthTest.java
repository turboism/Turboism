package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrameworkUpdateHealthTest {
    @TempDir Path base;

    private FrameworkUpdateApplier pending() throws Exception {
        final Path root = base.resolve("home");
        final Path stage = base.resolve("stage");
        Files.createDirectories(root);
        Files.createDirectories(stage);
        Files.writeString(root.resolve("turboism-agent.jar"), "old");
        Files.writeString(root.resolve("config.json"), "user config");
        Files.writeString(stage.resolve("turboism-agent.jar"), "new");
        final String hash = FrameworkUpdateTaskStore.hash("new".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final var manifest = new UpdateManifest(1, "test", "1.1.0", "stable", 2,
                "a".repeat(40), "1.0.0", "windows-x64",
                List.of(new UpdateManifestEntry("turboism-agent.jar", 3, hash, "sha256:" + hash)));
        final var applier = new FrameworkUpdateApplier(root, base.resolve("work"));
        applier.applyPendingHealth(manifest, stage, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(Files.exists(base.resolve("work/backup/turboism-agent.jar")));
        return applier;
    }

    @Test void noConfirmationRestoresOldInstallationOnRecovery() throws Exception {
        final var applier = pending();
        assertEquals(FrameworkUpdateApplier.Outcome.RECOVERED, applier.recover().outcome());
        assertEquals("old", Files.readString(base.resolve("home/turboism-agent.jar")));
        assertEquals("user config", Files.readString(base.resolve("home/config.json")));
        assertFalse(Files.exists(base.resolve("home/installed-build.json")));
        assertEquals(FrameworkUpdateApplier.Outcome.NOTHING, applier.recover().outcome());
    }

    @Test void healthyStartupCommitsAndDiscardsBackup() throws Exception {
        final var applier = pending();
        assertTrue(applier.confirmHealthy("1.1.0", base.resolve("home/turboism-agent.jar")));
        assertFalse(Files.exists(base.resolve("work/journal.json")));
        assertFalse(Files.exists(base.resolve("work/backup")));
        assertEquals("new", Files.readString(base.resolve("home/turboism-agent.jar")));
        assertFalse(applier.confirmHealthy("1.1.0", base.resolve("home/turboism-agent.jar")));
    }

    @Test void wrongVersionCannotConfirm() throws Exception {
        final var applier = pending();
        assertThrows(IOException.class, () -> applier.confirmHealthy("1.0.0", base.resolve("home/turboism-agent.jar")));
        assertTrue(Files.exists(base.resolve("work/backup")));
        applier.recover();
        assertEquals("old", Files.readString(base.resolve("home/turboism-agent.jar")));
    }

    @Test void changedAgentCannotConfirm() throws Exception {
        final var applier = pending();
        Files.writeString(base.resolve("home/turboism-agent.jar"), "bad");
        assertThrows(IOException.class, () -> applier.confirmHealthy("1.1.0", base.resolve("home/turboism-agent.jar")));
        applier.recover();
        assertEquals("old", Files.readString(base.resolve("home/turboism-agent.jar")));
    }

    @Test void firstLaunchRetainsBackupAndRejectsConcurrentLaunch() throws Exception {
        final var applier = pending();
        assertEquals(FrameworkUpdateApplier.Outcome.NOTHING,
                applier.prepareLaunch(ProcessHandle.current().pid()).outcome());
        assertTrue(Files.exists(base.resolve("work/backup")));
        assertThrows(IOException.class, () -> applier.prepareLaunch(ProcessHandle.current().pid()));
        assertTrue(applier.confirmHealthy("1.1.0", base.resolve("home/turboism-agent.jar")));
    }

    @Test void unconfirmedPreviousLaunchRollsBackBeforeNextLaunch() throws Exception {
        final var applier = pending();
        applier.prepareLaunch(ProcessHandle.current().pid());
        final Path journalPath = base.resolve("work/journal.json");
        final var journal = (com.fasterxml.jackson.databind.node.ObjectNode)
                UpdateFileSupport.JSON.readTree(Files.readAllBytes(journalPath));
        // A different start instant models PID reuse: this process is not the previous launcher.
        journal.put("launcherStarted", "1970-01-01T00:00:00Z");
        Files.write(journalPath, UpdateFileSupport.JSON.writeValueAsBytes(journal));
        assertEquals(FrameworkUpdateApplier.Outcome.RECOVERED,
                applier.prepareLaunch(ProcessHandle.current().pid()).outcome());
        assertEquals("old", Files.readString(base.resolve("home/turboism-agent.jar")));
    }

    @Test void interruptedBackupNeverReplacesUntouchedOriginal() throws Exception {
        final var applier = pending();
        final Path journalPath = base.resolve("work/journal.json");
        final var journal = (com.fasterxml.jackson.databind.node.ObjectNode)
                UpdateFileSupport.JSON.readTree(Files.readAllBytes(journalPath));
        journal.put("state", "PREPARED");
        Files.write(journalPath, UpdateFileSupport.JSON.writeValueAsBytes(journal));
        Files.writeString(base.resolve("home/turboism-agent.jar"), "old");
        Files.writeString(base.resolve("work/backup/turboism-agent.jar"), "partial backup");
        applier.recover();
        assertEquals("old", Files.readString(base.resolve("home/turboism-agent.jar")));
    }

    /** A real launcher process starts an attempt and exits without acknowledging health. */
    public static final class Launcher {
        public static void main(final String[] args) {
            FrameworkUpdateHelper.main(new String[] {
                    "--root", args[0], "--work", args[1],
                    "--prepare-launch", Long.toString(ProcessHandle.current().pid())
            });
        }
    }

    @Test void realLauncherExitWithoutHealthTriggersRecoveryInNextProcess() throws Exception {
        pending();
        final String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            final Path output = base.resolve("launch-" + attempt + ".log");
            final Process process = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                    Launcher.class.getName(), base.resolve("home").toString(), base.resolve("work").toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("Startup preparation timed out");
            }
            assertEquals(attempt == 0 ? FrameworkUpdateHelper.EXIT_NOTHING : FrameworkUpdateHelper.EXIT_RECOVERED,
                    process.exitValue(), Files.readString(output));
            assertEquals(attempt == 0 ? "new" : "old",
                    Files.readString(base.resolve("home/turboism-agent.jar")));
        }
        assertFalse(Files.exists(base.resolve("work/journal.json")));
    }

    @Test void transactionLockExcludesRecoveryInAnotherJvm() throws Exception {
        final var applier = pending();
        final Path output = base.resolve("locked-recovery.log");
        try (var transaction = FrameworkUpdateLock.acquire(base.resolve("work"))) {
            assertThrows(IOException.class, () -> FrameworkUpdateLock.acquire(base.resolve("work")));
            final Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), FrameworkUpdateHelper.class.getName(),
                    "--recover", "--root", base.resolve("home").toString(), "--work", base.resolve("work").toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("Locked recovery timed out");
            }
            assertNotEquals(0, process.exitValue());
            assertTrue(Files.readString(output).contains("lock"), Files.readString(output));
            assertEquals("new", Files.readString(base.resolve("home/turboism-agent.jar")));
            assertTrue(Files.exists(base.resolve("work/journal.json")));
        }
        applier.recover();
        assertEquals("old", Files.readString(base.resolve("home/turboism-agent.jar")));
    }

    @Test void journalCannotRollBackUserConfig() throws Exception {
        final var applier = pending();
        final Path path = base.resolve("work/journal.json");
        Files.writeString(path, Files.readString(path).replace("\"path\":\"turboism-agent.jar\"", "\"path\":\"config.json\""));
        assertThrows(IOException.class, applier::recover);
        assertEquals("user config", Files.readString(base.resolve("home/config.json")));
    }
}
