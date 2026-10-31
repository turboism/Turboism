package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrameworkUpdateNextLaunchTest {
    @TempDir
    Path temporary;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private final class Fixture {
        final KeyPair keys;
        final Path root = temporary.resolve("install");
        final Path work = temporary.resolve("work");
        final Path stage = temporary.resolve("stage");

        Fixture(final String installedVersion, final String targetVersion) throws Exception {
            keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Files.createDirectories(root);
            Files.writeString(root.resolve("turboism-agent.jar"), "agent-old");
            Files.writeString(root.resolve("config.json"), "{\"user\":1}");
            Files.writeString(
                    root.resolve("installed-build.json"),
                    "{\"schemaVersion\":1,\"variant\":\"full\",\"version\":\"" + installedVersion + "\"}\n");
            final Map<String, byte[]> files = new TreeMap<>();
            files.put("turboism-agent.jar", "agent-new".getBytes(StandardCharsets.UTF_8));
            final List<UpdateManifestEntry> entries = new ArrayList<>();
            for (var e : files.entrySet()) {
                final Path p = stage.resolve("files").resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
                final String hash = FrameworkUpdateTaskStore.hash(e.getValue());
                entries.add(new UpdateManifestEntry(e.getKey(), e.getValue().length, hash, "sha256:" + hash));
            }
            final var manifest = new UpdateManifest(
                    1, "fixture", targetVersion, "stable", 60, "c".repeat(40), "1.0.0", "windows-x64", entries);
            final byte[] bytes = manifest.toCanonicalBytes();
            Files.write(stage.resolve("manifest.json"), bytes);
            Files.write(stage.resolve("manifest.json.sig"), sign(bytes, keys));
        }

        Map<String, PublicKey> trusted() {
            return Map.of("fixture", keys.getPublic());
        }

        void persistPending(final String version) throws Exception {
            FrameworkUpdatePendingTask.write(work, root, stage, version);
        }

        int runPrepare(final long launcherPid, final FrameworkUpdateHelper.ProcessLookup processes) {
            final String[] args = {
                "--root", root.toString(),
                "--work", work.toString(),
                "--prepare-launch", Long.toString(launcherPid)
            };
            return FrameworkUpdateHelper.run(
                    args,
                    new PrintStream(out),
                    new PrintStream(err),
                    trusted(),
                    processes,
                    Instant::now);
        }
    }

    private static byte[] sign(final byte[] manifest, final KeyPair keys) throws Exception {
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
    void recoverWithNoJournalDoesNotDeleteValidPendingTask() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        f.persistPending("1.1.0");
        assertTrue(FrameworkUpdatePendingTask.read(f.work).isPresent());

        // Calling recover() when no journal exists must not delete the valid pending task
        final var applier = new FrameworkUpdateApplier(f.root, f.work);
        final var result = applier.recover();
        assertEquals(FrameworkUpdateApplier.Outcome.NOTHING, result.outcome());
        assertTrue(FrameworkUpdatePendingTask.read(f.work).isPresent(),
                "recover with no journal must not delete valid pending task");
    }

    @Test
    void nextLaunchAppliesPersistedPendingTaskWithoutNeedingOldHelperAlive() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        f.persistPending("1.1.0");
        assertTrue(FrameworkUpdatePendingTask.read(f.work).isPresent());
        assertFalse(Files.exists(f.work.resolve("journal.json")));

        // Launcher invokes prepare-launch with current PID. No old helper process is alive.
        final long launcherPid = ProcessHandle.current().pid();
        final int code = f.runPrepare(launcherPid, ProcessHandle::of);
        assertEquals(FrameworkUpdateHelper.EXIT_APPLIED, code, err.toString());

        // Target files are updated
        assertEquals("agent-new", Files.readString(f.root.resolve("turboism-agent.jar")));
        // Pending task file is consumed/discarded
        assertFalse(FrameworkUpdatePendingTask.read(f.work).isPresent(), "pending task must be consumed on apply");
        // Journal is now tracking health for this launcher
        final Path journalPath = f.work.resolve("journal.json");
        assertTrue(Files.exists(journalPath));
        final ObjectNode journal = (ObjectNode) UpdateFileSupport.JSON.readTree(Files.readAllBytes(journalPath));
        assertEquals("STARTING_HEALTH", journal.path("state").asText());
        assertEquals(launcherPid, journal.path("launcherPid").asLong());

        // Runtime health confirmation completes and removes journal
        final var applier = new FrameworkUpdateApplier(f.root, f.work);
        assertTrue(applier.confirmHealthy("1.1.0", f.root.resolve("turboism-agent.jar")));
        assertFalse(Files.exists(journalPath), "journal must be deleted after health confirmation");
    }

    @Test
    void unconfirmedLaunchCrashRollsBackAndDiscardsPendingTaskWithoutInfiniteRetry() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        final var applier = new FrameworkUpdateApplier(f.root, f.work);
        final var manifest = new UpdateManifestVerifier(f.trusted())
                .authenticate(Files.readAllBytes(f.stage.resolve("manifest.json")),
                        Files.readAllBytes(f.stage.resolve("manifest.json.sig")));
        applier.applyPendingHealth(manifest, f.stage.resolve("files"), "{}".getBytes(StandardCharsets.UTF_8));
        applier.prepareLaunch(ProcessHandle.current().pid());

        // Simulate crash: the recorded launcher has a past start timestamp (dead process identity)
        final Path journalPath = f.work.resolve("journal.json");
        final ObjectNode journal = (ObjectNode) UpdateFileSupport.JSON.readTree(Files.readAllBytes(journalPath));
        journal.put("launcherStarted", "1970-01-01T00:00:00Z");
        Files.write(journalPath, UpdateFileSupport.JSON.writeValueAsBytes(journal));
        f.persistPending("1.1.0");

        // The next controlled launch invokes prepare-launch: must roll back to old version
        final int code = f.runPrepare(ProcessHandle.current().pid(), ProcessHandle::of);
        assertEquals(FrameworkUpdateHelper.EXIT_RECOVERED, code);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
        // Pending task must be discarded to prevent infinite retry loops
        assertFalse(FrameworkUpdatePendingTask.read(f.work).isPresent(),
                "pending task must be discarded on rollback");
        assertFalse(Files.exists(journalPath));

        // Subsequent launch does not retry the bad update: returns NOTHING and keeps old version
        err.reset();
        out.reset();
        final int subsequentCode = f.runPrepare(ProcessHandle.current().pid(), ProcessHandle::of);
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, subsequentCode);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
    }

    @Test
    void legitimateFirstStartupInAwaitingHealthIsNotRolledBack() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        // An update was applied (e.g. by background helper before Cubism closed)
        final var applier = new FrameworkUpdateApplier(f.root, f.work);
        final var manifest = new UpdateManifestVerifier(f.trusted())
                .authenticate(Files.readAllBytes(f.stage.resolve("manifest.json")),
                        Files.readAllBytes(f.stage.resolve("manifest.json.sig")));
        applier.applyPendingHealth(manifest, f.stage.resolve("files"), "{}".getBytes(StandardCharsets.UTF_8));

        final Path journalPath = f.work.resolve("journal.json");
        final ObjectNode journal = (ObjectNode) UpdateFileSupport.JSON.readTree(Files.readAllBytes(journalPath));
        assertEquals("AWAITING_HEALTH", journal.path("state").asText());

        // First launch prepares launch: must NOT roll back
        final int code = f.runPrepare(ProcessHandle.current().pid(), ProcessHandle::of);
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, code);
        assertEquals("agent-new", Files.readString(f.root.resolve("turboism-agent.jar")), "must not roll back legitimate startup");
        assertTrue(Files.exists(f.work.resolve("backup")), "backup must be retained for health check");

        final ObjectNode updated = (ObjectNode) UpdateFileSupport.JSON.readTree(Files.readAllBytes(journalPath));
        assertEquals("STARTING_HEALTH", updated.path("state").asText());
    }

    @Test
    void prepareLaunchDiscardsTaskWhenManifestMissingAndAllowsOldCubismLaunch() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        f.persistPending("1.1.0");
        Files.delete(f.stage.resolve("manifest.json"));

        final int code = f.runPrepare(ProcessHandle.current().pid(), ProcessHandle::of);
        // Does not throw or fail: discards bad task and allows old Cubism to launch
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, code);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
        assertFalse(FrameworkUpdatePendingTask.read(f.work).isPresent(), "bad pending task must be discarded");
        assertTrue(out.toString().contains("UPDATE_DISCARDED"));
    }

    @Test
    void prepareLaunchDiscardsTaskWhenSignatureMissingAndAllowsOldCubismLaunch() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        f.persistPending("1.1.0");
        Files.delete(f.stage.resolve("manifest.json.sig"));

        final int code = f.runPrepare(ProcessHandle.current().pid(), ProcessHandle::of);
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, code);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
        assertFalse(FrameworkUpdatePendingTask.read(f.work).isPresent());
        assertTrue(out.toString().contains("UPDATE_DISCARDED"));
    }

    @Test
    void prepareLaunchDiscardsTaskWhenSignatureCorruptedAndAllowsOldCubismLaunch() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        f.persistPending("1.1.0");
        final byte[] sig = Files.readAllBytes(f.stage.resolve("manifest.json.sig"));
        sig[sig.length - 10] ^= 1;
        Files.write(f.stage.resolve("manifest.json.sig"), sig);

        final int code = f.runPrepare(ProcessHandle.current().pid(), ProcessHandle::of);
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, code);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
        assertFalse(FrameworkUpdatePendingTask.read(f.work).isPresent());
        assertTrue(out.toString().contains("UPDATE_DISCARDED"));
    }

    @Test
    void prepareLaunchDiscardsTaskWhenStagedFileMissingAndAllowsOldCubismLaunch() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        f.persistPending("1.1.0");
        Files.delete(f.stage.resolve("files/turboism-agent.jar"));

        final int code = f.runPrepare(ProcessHandle.current().pid(), ProcessHandle::of);
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, code);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
        assertFalse(FrameworkUpdatePendingTask.read(f.work).isPresent());
        assertTrue(out.toString().contains("UPDATE_DISCARDED"));
    }

    @Test
    void prepareLaunchDiscardsTaskWhenPendingJsonMalformedAndAllowsOldCubismLaunch() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        Files.createDirectories(f.work);
        Files.writeString(f.work.resolve(FrameworkUpdatePendingTask.FILE_NAME), "not-valid-json{{{");

        final int code = f.runPrepare(ProcessHandle.current().pid(), ProcessHandle::of);
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, code);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
        assertFalse(Files.exists(f.work.resolve(FrameworkUpdatePendingTask.FILE_NAME)));
    }

    @Test
    void prepareLaunchDiscardsTaskWhenStagePathRelativeOrEscapingAndAllowsOldCubismLaunch() throws Exception {
        final Fixture f = new Fixture("1.0.0", "1.1.0");
        Files.createDirectories(f.work);
        final String raw = "{\"format\":\"turboism-framework-pending-v1\",\"root\":\""
                + f.root.toString().replace("\\", "\\\\") + "\",\"stage\":\"relative/stage\",\"version\":\"1.1.0\"}";
        Files.writeString(f.work.resolve(FrameworkUpdatePendingTask.FILE_NAME), raw);

        final int code = f.runPrepare(ProcessHandle.current().pid(), ProcessHandle::of);
        assertEquals(FrameworkUpdateHelper.EXIT_NOTHING, code);
        assertEquals("agent-old", Files.readString(f.root.resolve("turboism-agent.jar")));
        assertFalse(Files.exists(f.work.resolve(FrameworkUpdatePendingTask.FILE_NAME)));
    }
}
