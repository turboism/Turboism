package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class FrameworkUpdateApplierTest {
    @TempDir
    Path temporary;

    private static final String HASH = "a".repeat(40);

    /** Installation with user state that no update may ever touch. */
    private final class Fixture {
        final Path root = temporary.resolve("install");
        final Path work = temporary.resolve("work");
        final Path staged = temporary.resolve("staged");
        final Map<String, byte[]> oldFiles = new TreeMap<>();
        final Map<String, byte[]> newFiles = new TreeMap<>();
        final byte[] oldIdentity = "{\"version\":\"1.0.0\"}\n".getBytes(StandardCharsets.UTF_8);
        final byte[] newIdentity = "{\"version\":\"1.1.0\"}\n".getBytes(StandardCharsets.UTF_8);

        Fixture() throws IOException {
            Files.createDirectories(root.resolve("graal/lib"));
            Files.createDirectories(root.resolve("plugins"));
            Files.createDirectories(root.resolve("state/runtime"));
            put(oldFiles, "turboism-agent.jar", "agent-1.0");
            put(oldFiles, "README.txt", "readme-1.0");
            put(oldFiles, "graal/lib/unchanged.jar", "same");
            put(newFiles, "turboism-agent.jar", "agent-1.1");
            put(newFiles, "README.txt", "readme-1.1");
            put(newFiles, "graal/lib/added.jar", "brand-new");
            for (var e : oldFiles.entrySet()) Files.write(root.resolve(e.getKey()), e.getValue());
            Files.write(root.resolve("installed-build.json"), oldIdentity);
            Files.writeString(root.resolve("config.json"), "{\"user\":true}");
            Files.writeString(root.resolve("plugins/third-party.jar"), "user-plugin");
            Files.writeString(root.resolve("state/runtime/settings"), "user-state");
            for (var e : newFiles.entrySet()) {
                final Path p = staged.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
            }
        }

        private void put(final Map<String, byte[]> map, final String path, final String text) {
            map.put(path, text.getBytes(StandardCharsets.UTF_8));
        }

        UpdateManifest manifest() {
            final List<UpdateManifestEntry> entries = new ArrayList<>();
            for (var e : newFiles.entrySet()) {
                final String hash = FrameworkUpdateTaskStore.hash(e.getValue());
                entries.add(new UpdateManifestEntry(e.getKey(), e.getValue().length, hash, "sha256:" + hash));
            }
            return new UpdateManifest(1, "fixture", "1.1.0", "stable", 50, HASH, "1.0.0", "windows-x64", entries);
        }

        FrameworkUpdateApplier applier(final FrameworkUpdateApplier.Faults faults) {
            return new FrameworkUpdateApplier(root, work, faults);
        }

        void assertOld() throws IOException {
            for (var e : oldFiles.entrySet()) assertArrayEquals(e.getValue(), Files.readAllBytes(root.resolve(e.getKey())), e.getKey());
            assertFalse(Files.exists(root.resolve("graal/lib/added.jar")), "added file removed");
            assertArrayEquals(oldIdentity, Files.readAllBytes(root.resolve("installed-build.json")));
            assertUserStateUntouched();
            assertClean();
        }

        void assertNew() throws IOException {
            for (var e : newFiles.entrySet()) assertArrayEquals(e.getValue(), Files.readAllBytes(root.resolve(e.getKey())), e.getKey());
            assertArrayEquals("same".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(root.resolve("graal/lib/unchanged.jar")));
            assertArrayEquals(newIdentity, Files.readAllBytes(root.resolve("installed-build.json")));
            assertUserStateUntouched();
            assertClean();
        }

        void assertUserStateUntouched() throws IOException {
            assertEquals("{\"user\":true}", Files.readString(root.resolve("config.json")));
            assertEquals("user-plugin", Files.readString(root.resolve("plugins/third-party.jar")));
            assertEquals("user-state", Files.readString(root.resolve("state/runtime/settings")));
        }

        void assertClean() throws IOException {
            assertFalse(Files.exists(work.resolve("journal.json")), "journal removed");
            assertFalse(Files.exists(work.resolve("backup")), "backup removed");
            try (var stream = Files.walk(root)) {
                assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".turboism-new")), "no temp leftovers");
            }
        }
    }

    @Test
    void appliesChangedAndNewFilesPreservesUnchangedAndUserState() throws Exception {
        final Fixture f = new Fixture();
        final var result = f.applier(p -> {}).apply(f.manifest(), f.staged, f.newIdentity);
        assertEquals(FrameworkUpdateApplier.Outcome.APPLIED, result.outcome());
        f.assertNew();
    }

    /** Every named step, failing as a lock/IO error: the old installation must come back exactly. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "after-prepared",
                "before-backup:README.txt",
                "before-journal:APPLYING",
                "before-replace:README.txt",
                "after-replace:README.txt",
                "before-replace:graal/lib/added.jar",
                "after-replace:graal/lib/added.jar",
                "before-replace:turboism-agent.jar",
                "after-replace:turboism-agent.jar",
                "before-installed-build",
                "before-journal:COMMITTED"
            })
    void failureAtAnyStepRollsBackToExactOldInstallation(final String point) throws Exception {
        final Fixture f = new Fixture();
        final var result = f.applier(p -> {
                    if (p.equals(point)) throw new IOException("injected lock at " + p);
                })
                .apply(f.manifest(), f.staged, f.newIdentity);
        assertEquals(FrameworkUpdateApplier.Outcome.ROLLED_BACK, result.outcome());
        f.assertOld();
    }

    /** Simulate the process dying (not an exception the applier can catch) at each step, then recover. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "after-prepared",
                "before-replace:README.txt",
                "after-replace:README.txt",
                "after-replace:graal/lib/added.jar",
                "after-replace:turboism-agent.jar",
                "before-installed-build"
            })
    void crashLeavesJournalAndRecoveryRestoresOldInstallation(final String point) throws Exception {
        final Fixture f = new Fixture();
        final class Crash extends Error {}
        try {
            f.applier(p -> {
                        if (p.equals(point)) throw new Crash();
                    })
                    .apply(f.manifest(), f.staged, f.newIdentity);
            fail("expected crash");
        } catch (Crash expected) {
            // Process "died": journal and partial state stay on disk.
        }
        assertTrue(Files.exists(f.work.resolve("journal.json")), "journal survives a crash");
        final var recovered = f.applier(p -> {}).recover();
        assertEquals(FrameworkUpdateApplier.Outcome.RECOVERED, recovered.outcome());
        f.assertOld();
        assertEquals(FrameworkUpdateApplier.Outcome.NOTHING, f.applier(p -> {}).recover().outcome(), "idempotent");
    }

    @Test
    void crashAfterCommitOnlyNeedsCleanupAndKeepsNewInstallation() throws Exception {
        final Fixture f = new Fixture();
        final class Crash extends Error {}
        try {
            f.applier(p -> {
                        if (p.equals("after-commit")) throw new Crash();
                    })
                    .apply(f.manifest(), f.staged, f.newIdentity);
            fail("expected crash");
        } catch (Crash expected) {
            // Committed, but cleanup not run.
        }
        final var recovered = f.applier(p -> {}).recover();
        assertEquals(FrameworkUpdateApplier.Outcome.APPLIED, recovered.outcome());
        f.assertNew();
    }

    @Test
    void recoveryItselfInterruptedCanBeRetried() throws Exception {
        final Fixture f = new Fixture();
        final class Crash extends Error {}
        try {
            f.applier(p -> {
                        if (p.equals("after-replace:turboism-agent.jar")) throw new Crash();
                    })
                    .apply(f.manifest(), f.staged, f.newIdentity);
        } catch (Crash expected) {
        }
        // First recovery attempt fails midway (restore throws before cleanup completes)...
        final var journal = Files.readAllBytes(f.work.resolve("journal.json"));
        Files.delete(f.work.resolve("backup/README.txt"));
        assertThrows(IOException.class, () -> f.applier(p -> {}).recover());
        assertArrayEquals(journal, Files.readAllBytes(f.work.resolve("journal.json")), "journal kept for retry");
    }

    @Test
    void tamperedStagedBytesAreRejectedBeforeAnythingChanges() throws Exception {
        final Fixture f = new Fixture();
        Files.writeString(f.staged.resolve("README.txt"), "evil-1.1");
        final byte[] sameSize = "readme-1.1".getBytes(StandardCharsets.UTF_8);
        Files.write(f.staged.resolve("README.txt"), "evilevil11".getBytes(StandardCharsets.UTF_8));
        assertEquals(sameSize.length, Files.size(f.staged.resolve("README.txt")));
        assertThrows(IOException.class, () -> f.applier(p -> {}).apply(f.manifest(), f.staged, f.newIdentity));
        f.assertOld();
    }

    @Test
    void missingStagedFileIsRejectedBeforeAnythingChanges() throws Exception {
        final Fixture f = new Fixture();
        Files.delete(f.staged.resolve("graal/lib/added.jar"));
        assertThrows(IOException.class, () -> f.applier(p -> {}).apply(f.manifest(), f.staged, f.newIdentity));
        f.assertOld();
    }

    @Test
    void symlinkedTargetOrDirectoryIsRejected() throws Exception {
        final Fixture f = new Fixture();
        final Path outside = temporary.resolve("outside");
        Files.createDirectory(outside);
        Files.writeString(outside.resolve("secret"), "keep");
        try {
            Files.delete(f.root.resolve("graal/lib/unchanged.jar"));
            Files.delete(f.root.resolve("graal/lib"));
            Files.createSymbolicLink(f.root.resolve("graal/lib"), outside);
        } catch (IOException | UnsupportedOperationException noSymlinks) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + noSymlinks);
        }
        assertThrows(IOException.class, () -> f.applier(p -> {}).apply(f.manifest(), f.staged, f.newIdentity));
        assertEquals("keep", Files.readString(outside.resolve("secret")));
        assertEquals(1, Files.list(outside).count(), "nothing written through the link");
    }

    @Test
    void unplainExistingTargetIsRejected() throws Exception {
        final Fixture f = new Fixture();
        Files.delete(f.root.resolve("README.txt"));
        Files.createDirectory(f.root.resolve("README.txt"));
        assertThrows(IOException.class, () -> f.applier(p -> {}).apply(f.manifest(), f.staged, f.newIdentity));
        assertTrue(Files.isDirectory(f.root.resolve("README.txt")));
    }

    @Test
    void caseCollidingManifestPathsAreRejected() throws Exception {
        final Fixture f = new Fixture();
        f.newFiles.put("readme.txt", "x".getBytes(StandardCharsets.UTF_8));
        // The manifest type itself may reject this; either layer must fail closed.
        assertThrows(Exception.class, () -> f.applier(p -> {}).apply(f.manifest(), f.staged, f.newIdentity));
        assertArrayEquals(f.oldFiles.get("README.txt"), Files.readAllBytes(f.root.resolve("README.txt")));
    }

    @Test
    void workDirectoryInsideFrameworkFilesIsRejected() throws Exception {
        final Fixture f = new Fixture();
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrameworkUpdateApplier(f.root, f.root.resolve("graal")));
    }

    /**
     * A file held open without FILE_SHARE_DELETE (antivirus, or the still-running JVM) blocks replacement, and for
     * the same reason also blocks restoring that very file. The applier must not report success, must keep its
     * journal, and must finish the rollback on the next start once the lock is gone.
     */
    @Test
    void lockedFileOnWindowsKeepsJournalAndRecoversAfterRelease() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name").startsWith("Windows"), "Windows sharing-violation semantics");
        final Fixture f = new Fixture();
        try (var held = new java.io.RandomAccessFile(f.root.resolve("turboism-agent.jar").toFile(), "r")) {
            // README.txt is replaced first; the locked agent then fails the move. Rolling back README works,
            // so the final state may already be consistent; either way no success may be reported.
            try {
                final var result = f.applier(p -> {}).apply(f.manifest(), f.staged, f.newIdentity);
                assertEquals(FrameworkUpdateApplier.Outcome.ROLLED_BACK, result.outcome());
            } catch (IOException rollbackBlockedByLock) {
                assertTrue(Files.exists(f.work.resolve("journal.json")), "journal retained for retry");
            }
            assertNotNull(held);
        }
        final var recovered = f.applier(p -> {}).recover();
        assertTrue(
                recovered.outcome() == FrameworkUpdateApplier.Outcome.RECOVERED
                        || recovered.outcome() == FrameworkUpdateApplier.Outcome.NOTHING,
                recovered.toString());
        f.assertOld();
    }
}
