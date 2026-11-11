package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression protection for the plugin install/enable journals and the v2 catalog state (#111).
 *
 * <p>The journals are execution authority: an unreadable or forward-written document must stop
 * new staging and application while the original bytes stay on disk for explicit recovery. The
 * tests drive only the production {@link PendingPluginOperations}, {@link PluginUpdateGate} and
 * {@link PluginCatalogStateStore} paths.</p>
 */
class PluginPersistenceJournalCompatibilityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES =
            Path.of(System.getProperty("turboism.goldenSnapshotRoot", "testing/fixtures/snapshots"));

    @TempDir
    Path home;

    private Path journal() {
        return home.resolve("state/runtime/plugin-management/pending.json");
    }

    private Path failedJournal() {
        return home.resolve("state/runtime/plugin-management/failed-pending.json");
    }

    private Path catalogState() {
        return home.resolve(PluginCatalogStateStore.RELATIVE_PATH);
    }

    private Path stagedSource() throws Exception {
        final Path source = home.resolve("source.jar");
        Files.write(source, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "2.0.0"));
        return source;
    }

    private byte[] write(final Path path, final String document) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, document);
        return Files.readAllBytes(path);
    }

    private byte[] validJournalWithUnknownField(final String field, final boolean operationField) throws Exception {
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        assertTrue(pending.stageUninstall("example.plugin").accepted());
        final ObjectNode root = (ObjectNode) JSON.readTree(journal().toFile());
        if (operationField) {
            ((ObjectNode) root.path("operations").get(0)).put(field, true);
        } else {
            root.put(field, true);
        }
        Files.write(journal(), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        return Files.readAllBytes(journal());
    }

    @Test
    void corruptPendingJournalIsRejectedAndNeverRewritten() throws Exception {
        final byte[] original = write(journal(), """
                {
                  "format": "turboism.plugin.pending",
                  "schemaVersion": 1,
                  "operations": [
                """);
        final PendingPluginOperations pending = new PendingPluginOperations(home);

        assertTrue(pending.recoveryRequired());
        final PendingPluginOperations.ApplyResult result = pending.apply();
        assertEquals(PendingPluginOperations.Status.RECOVERY_REQUIRED, result.status());
        assertEquals("PLUGIN_PENDING_INVALID", result.code());
        assertEquals(
                "PLUGIN_PENDING_RECOVERY_REQUIRED", pending.pendingChanges().code());
        final PendingPluginOperations.StagedInstall staged = pending.stage(stagedSource());
        assertFalse(staged.accepted());
        assertEquals("PLUGIN_PENDING_RECOVERY_REQUIRED", staged.code());
        assertArrayEquals(original, Files.readAllBytes(journal()), "corrupt journal bytes must stay for recovery");
    }

    @Test
    void wrongPendingJournalSchemaVersionIsRejectedAndNeverRewritten() throws Exception {
        final byte[] original = write(journal(), """
                {
                  "format": "turboism.plugin.pending",
                  "schemaVersion": 2,
                  "operations": []
                }
                """);
        final PendingPluginOperations pending = new PendingPluginOperations(home);

        assertTrue(pending.recoveryRequired());
        assertEquals(
                PendingPluginOperations.Status.RECOVERY_REQUIRED,
                pending.apply().status());
        assertFalse(pending.stageUninstall("example.other").accepted());
        assertArrayEquals(original, Files.readAllBytes(journal()));
    }

    @Test
    void unknownPendingJournalTopLevelFieldIsRejectedAndNeverRewritten() throws Exception {
        final byte[] original = validJournalWithUnknownField("futureJournalField", false);
        final PendingPluginOperations pending = new PendingPluginOperations(home);

        assertTrue(pending.recoveryRequired());
        assertEquals(
                PendingPluginOperations.Status.RECOVERY_REQUIRED,
                pending.apply().status());
        assertFalse(pending.stageUninstall("example.other").accepted());
        assertArrayEquals(original, Files.readAllBytes(journal()), "unknown journal data must not be rewritten");
    }

    @Test
    void unknownPendingOperationFieldIsRejectedAndNeverRewritten() throws Exception {
        final byte[] original = validJournalWithUnknownField("futureOperationField", true);
        final PendingPluginOperations pending = new PendingPluginOperations(home);

        assertTrue(pending.recoveryRequired());
        assertEquals(
                PendingPluginOperations.Status.RECOVERY_REQUIRED,
                pending.apply().status());
        assertArrayEquals(original, Files.readAllBytes(journal()));
    }

    /**
     * The retired {@code rawSha256} field always repeated {@code jarSha256}. Released journals
     * still carry it, so the reader must accept it without storing it again, while every other
     * unknown operation field keeps failing closed.
     */
    @Test
    void retiredRawSha256OperationFieldIsAcceptedAndDroppedOnRewrite() throws Exception {
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        assertTrue(pending.stageUninstall("example.plugin").accepted());
        final ObjectNode root = (ObjectNode) JSON.readTree(journal().toFile());
        final ObjectNode operation = (ObjectNode) root.path("operations").get(0);
        operation.put("rawSha256", "b".repeat(64));
        Files.write(journal(), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));

        final PendingPluginOperations released = new PendingPluginOperations(home);
        assertFalse(released.recoveryRequired(), "a released journal field must not look corrupt");
        assertEquals("PLUGIN_PENDING_READY", released.pendingChanges().code());
        assertEquals(
                List.of("example.plugin"),
                released.operations().stream()
                        .map(PendingPluginOperations.Operation::pluginId)
                        .toList());

        assertTrue(released.stageUninstall("example.other").accepted());
        final ObjectNode rewritten = (ObjectNode) JSON.readTree(journal().toFile());
        for (final JsonNode entry : rewritten.path("operations")) {
            assertFalse(entry.has("rawSha256"), "the current writer must never emit the retired field");
        }
    }

    @Test
    void duplicatePendingJournalKeysAreRejectedInsteadOfLastValueWins() throws Exception {
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        assertTrue(pending.stageUninstall("example.plugin").accepted());
        final String valid = Files.readString(journal());
        final byte[] original = valid.replaceFirst("\\{", "{\\n  \"schemaVersion\": 1,")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(journal(), original);

        assertTrue(pending.recoveryRequired());
        assertEquals(
                PendingPluginOperations.Status.RECOVERY_REQUIRED,
                pending.apply().status());
        assertArrayEquals(original, Files.readAllBytes(journal()));
    }

    @Test
    void trailingPendingJournalTokensAreRejectedInsteadOfSilentlyDiscarded() throws Exception {
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        assertTrue(pending.stageUninstall("example.plugin").accepted());
        final byte[] original = (Files.readString(journal()) + "\n{\"future\":true}\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(journal(), original);

        assertTrue(pending.recoveryRequired());
        assertFalse(pending.stageUninstall("example.other").accepted());
        assertArrayEquals(original, Files.readAllBytes(journal()));
    }

    @Test
    void suspendedIntentBlocksNewStagingAndIsKeptByteForByte() throws Exception {
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        assertTrue(pending.stageUninstall("example.plugin").accepted());
        final byte[] suspended = Files.readAllBytes(journal());
        Files.move(journal(), failedJournal());

        assertTrue(pending.recoveryRequired());
        assertTrue(PluginUpdateGate.isAutoUpdateBlocked(home));
        final PendingPluginOperations.StagedInstall staged = pending.stage(stagedSource());
        assertFalse(staged.accepted());
        assertEquals("PLUGIN_PENDING_RECOVERY_REQUIRED", staged.code());
        assertArrayEquals(suspended, Files.readAllBytes(failedJournal()), "suspended intent must not be modified");
        assertFalse(Files.exists(journal()), "no new active journal may be created while review is pending");
    }

    @Test
    void corruptCatalogStateIsRejectedWithoutRewritingItsBytes() throws Exception {
        final byte[] original = write(catalogState(), """
                {
                  "schemaVersion": 2,
                  "catalogVersion": 7,
                """);
        final List<String> diagnostics = new ArrayList<>();
        final PluginCatalogStateStore store = new PluginCatalogStateStore(home, diagnostics::add);

        final PluginCatalogStateStore.State state = store.read();
        assertTrue(state.catalogVersion().isEmpty());
        assertTrue(state.stagedUpdates().isEmpty());
        assertEquals(List.of("PLUGIN_CATALOG_STATE_CORRUPT"), diagnostics);
        assertArrayEquals(original, Files.readAllBytes(catalogState()));
    }

    @Test
    void catalogStateV1IsRefusedRatherThanMigrated() throws Exception {
        final byte[] original = write(catalogState(), """
                {
                  "schemaVersion": 1,
                  "catalogVersion": 7,
                  "publishedAt": "2026-01-01T00:00:00Z",
                  "catalogSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                }
                """);
        final List<String> diagnostics = new ArrayList<>();
        final PluginCatalogStateStore store = new PluginCatalogStateStore(home, diagnostics::add);

        assertTrue(store.read().catalogVersion().isEmpty());
        assertEquals(List.of("PLUGIN_CATALOG_STATE_CORRUPT"), diagnostics);
        assertArrayEquals(original, Files.readAllBytes(catalogState()));
    }

    @Test
    void unknownCatalogStateFieldIsRejectedWithoutRewritingTheDocument() throws Exception {
        final Path path = catalogState();
        final PluginCatalogStateStore store = new PluginCatalogStateStore(home);
        assertTrue(
                store.save(PluginCatalogStateStore.State.empty().withStagedUpdates(Map.of("example.plugin", "2.0.0"))));
        final ObjectNode root = (ObjectNode) JSON.readTree(path.toFile());
        root.putObject("futureCatalogField").put("catalogVersion", 99);
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        final byte[] original = Files.readAllBytes(path);

        final List<String> diagnostics = new ArrayList<>();
        final PluginCatalogStateStore hardened = new PluginCatalogStateStore(home, diagnostics::add);
        assertTrue(hardened.read().stagedUpdates().isEmpty());
        assertEquals(List.of("PLUGIN_CATALOG_STATE_CORRUPT"), diagnostics);
        assertArrayEquals(original, Files.readAllBytes(path), "an unknown v2 field must not be discarded in place");
    }

    @Test
    void validCatalogStateRoundTripsWithoutUnknownFieldRejection() throws Exception {
        final PluginCatalogStateStore store = new PluginCatalogStateStore(home);
        assertTrue(
                store.save(PluginCatalogStateStore.State.empty().withStagedUpdates(Map.of("example.plugin", "2.0.0"))));
        final byte[] original = Files.readAllBytes(catalogState());

        final PluginCatalogStateStore.State read = new PluginCatalogStateStore(home).read();
        assertEquals(Map.of("example.plugin", "2.0.0"), read.stagedUpdates());
        assertArrayEquals(original, Files.readAllBytes(catalogState()), "a plain read must not rewrite valid state");
    }

    @Test
    void savingAfterACorruptReadKeepsTheUnreadableCatalogState() throws Exception {
        final byte[] corrupt =
                "{\n  \"schemaVersion\" : 2,\n  \"catalogVersion\" :".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.createDirectories(catalogState().getParent());
        Files.write(catalogState(), corrupt);

        final PluginCatalogStateStore store = new PluginCatalogStateStore(home);
        assertTrue(store.read().catalogVersion().isEmpty());
        assertTrue(
                store.save(PluginCatalogStateStore.State.empty().withStagedUpdates(Map.of("example.plugin", "3.0.0"))));

        final Path image = catalogState().resolveSibling(catalogState().getFileName() + ".corrupt.bak");
        assertArrayEquals(
                corrupt,
                Files.readAllBytes(image),
                "the bytes the corrupt read discarded must survive the overwrite that replaces them");
        assertEquals(Map.of("example.plugin", "3.0.0"), store.read().stagedUpdates());
    }

    @Test
    void aRefusedFutureCatalogStateIsKeptBeforeTheNextSaveReplacesIt() throws Exception {
        final byte[] future = """
                {
                  "schemaVersion": 3,
                  "catalogVersion": 99
                }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.createDirectories(catalogState().getParent());
        Files.write(catalogState(), future);

        final PluginCatalogStateStore store = new PluginCatalogStateStore(home);
        assertTrue(store.read().catalogVersion().isEmpty(), "a future schema must be refused, never migrated");
        assertArrayEquals(future, Files.readAllBytes(catalogState()), "reading must not rewrite it");
        assertTrue(store.save(PluginCatalogStateStore.State.empty()));
        assertArrayEquals(
                future,
                Files.readAllBytes(catalogState().resolveSibling(catalogState().getFileName() + ".corrupt.bak")),
                "a document from a newer build must be recoverable after the downgrade overwrites it");
    }

    @Test
    void aNormalCatalogStateOverwriteKeepsNoRecoveryImage() throws Exception {
        final PluginCatalogStateStore store = new PluginCatalogStateStore(home);
        assertTrue(store.save(PluginCatalogStateStore.State.empty()));
        assertTrue(
                store.save(PluginCatalogStateStore.State.empty().withStagedUpdates(Map.of("example.plugin", "2.0.0"))));
        assertFalse(
                Files.exists(catalogState().resolveSibling(catalogState().getFileName() + ".corrupt.bak")),
                "a readable document must be overwritten in place without accumulating backups");
    }

    @Test
    void pendingJournalRevisionIsNotRequiredForAlreadyWrittenLegacyDocuments() throws Exception {
        // A journal without the optional random revision is still a frozen v1 document; unknown
        // fields are rejected, but a missing revision remains readable for older bytes.
        final PendingPluginOperations pending = new PendingPluginOperations(home);
        assertTrue(pending.stageUninstall("example.plugin").accepted());
        final ObjectNode root = (ObjectNode) JSON.readTree(journal().toFile());
        root.remove("revision");
        Files.write(journal(), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));

        assertEquals(1, pending.operations().size());
        assertEquals("example.plugin", pending.operations().get(0).pluginId());
    }

    private Path pendingLock() {
        return home.resolve("state/runtime/plugin-management/pending.lock");
    }

    /**
     * N4: the journal transaction holds an operating-system lock on {@code pending.lock}.
     *
     * <p>A second channel in this JVM sees an overlapping lock, which is exactly the exclusion a
     * second process would observe, so the probe proves the lock is held for the whole mutation
     * window instead of only around the final rename.</p>
     */
    @Test
    void pendingJournalLockIsHeldForTheWholeTransaction() throws Exception {
        final Path source = home.resolve("source.jar");
        Files.write(source, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0"));
        final Path packages = home.resolve("state/runtime/plugin-management/packages");
        final var preparation = new dev.turboism.distribution.LocalPluginJarPreparer().prepare(source, packages);
        final var prepared = ((dev.turboism.distribution.LocalPluginJarPreparer.Prepared) preparation).value();
        assertTrue(new PendingPluginOperations(home).stage(prepared).accepted());
        assertTrue(
                Files.isRegularFile(pendingLock(), LinkOption.NOFOLLOW_LINKS),
                "staging must publish the journal lock file");

        final List<String> observed = new ArrayList<>();
        final PendingPluginOperations.SnapshotCopier probe = (from, to, parent) -> {
            try (FileChannel channel = FileChannel.open(pendingLock(), StandardOpenOption.WRITE)) {
                try {
                    channel.tryLock().release();
                    observed.add("unlocked");
                } catch (OverlappingFileLockException held) {
                    observed.add("locked");
                }
            }
            Files.copy(from, to);
        };
        final PendingPluginOperations pending = new PendingPluginOperations(home, probe, probe);

        final PendingPluginOperations.ApplyResult result = pending.apply();

        assertTrue(result.applied(), result.code());
        assertEquals(
                List.of("locked"),
                observed,
                "apply must hold pending.lock while it mutates the journal and the plugin directory");
        assertTrue(Files.exists(home.resolve("plugins/example.plugin.jar")));
    }

    /**
     * N4: two independent instances (the shape two processes have) serialize on the same journal
     * instead of producing a last-writer-wins document that silently drops one staged operation.
     */
    @Test
    void concurrentStagingFromTwoInstancesKeepsEveryOperation() throws Exception {
        final PendingPluginOperations left = new PendingPluginOperations(home);
        final PendingPluginOperations right = new PendingPluginOperations(home);
        for (int round = 0; round < 16; round++) {
            final String leftId = "example.left" + round;
            final String rightId = "example.right" + round;
            final CyclicBarrier barrier = new CyclicBarrier(2);
            final List<Throwable> failures = new CopyOnWriteArrayList<>();
            final Thread first = new Thread(() -> stageUninstall(barrier, left, leftId, failures), "stage-left");
            final Thread second = new Thread(() -> stageUninstall(barrier, right, rightId, failures), "stage-right");
            first.start();
            second.start();
            first.join(30_000);
            second.join(30_000);
            assertFalse(first.isAlive() || second.isAlive(), "staging must not block forever");
            assertTrue(failures.isEmpty(), failures::toString);

            final List<String> staged = new PendingPluginOperations(home)
                    .operations().stream()
                            .map(PendingPluginOperations.Operation::pluginId)
                            .sorted()
                            .toList();
            assertEquals(List.of(leftId, rightId), staged, "round " + round + " lost a staged operation");
            assertTrue(new PendingPluginOperations(home).apply().applied(), "the journal must clear between rounds");
        }
    }

    private static void stageUninstall(
            final CyclicBarrier barrier,
            final PendingPluginOperations pending,
            final String pluginId,
            final List<Throwable> failures) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
            if (!pending.stageUninstall(pluginId).accepted()) {
                failures.add(new AssertionError("stageUninstall rejected " + pluginId));
            }
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    /**
     * N4: a symlinked lock file is refused instead of followed, exactly like every other
     * runtime-owned path, and the refused transaction writes no journal.
     */
    @Test
    void symlinkedPendingJournalLockIsRefusedWithoutTouchingItsTarget() throws Exception {
        final Path outside = Files.createTempFile("pending-lock-outside-", ".txt");
        final byte[] original = "not-a-journal".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(outside, original);
        Files.createDirectories(pendingLock().getParent());
        try {
            Files.createSymbolicLink(pendingLock(), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are not creatable on this platform");
        }
        final PendingPluginOperations pending = new PendingPluginOperations(home);

        assertFalse(pending.stageUninstall("example.plugin").accepted());
        assertEquals(
                PendingPluginOperations.Status.RECOVERY_REQUIRED,
                pending.apply().status());
        assertArrayEquals(original, Files.readAllBytes(outside), "the link target must never be locked or truncated");
        assertFalse(Files.exists(journal()), "no journal may be written without the journal lock");
    }

    /**
     * Replays every release-derived {@code plugin-journal} fixture through the production reader.
     *
     * <p>The v0.42.0 document is the journal a published release actually wrote: a staged install
     * of a shipped plugin plus a queued uninstall. It predates the {@code revision} generation
     * field, so this replay is also the release-derived proof that a revision-less v1 journal is
     * still read exactly the same way.</p>
     */
    @TestFactory
    Stream<DynamicTest> goldenPluginJournalSnapshotsReplayThroughProductionReader() throws Exception {
        final JsonNode cases = JSON.readTree(Files.readAllBytes(FIXTURES.resolve("manifest.json")))
                .path("snapshots");
        final List<JsonNode> fixtures = StreamSupport.stream(cases.spliterator(), false)
                .filter(fixture ->
                        "plugin-journal".equals(fixture.path("family").asText()))
                .toList();
        assertFalse(fixtures.isEmpty(), "the plugin-journal golden family must stay registered");
        return fixtures.stream().map(fixture -> dynamicTest(fixture.path("id").asText(), () -> replayJournal(fixture)));
    }

    private void replayJournal(final JsonNode fixture) throws Exception {
        final byte[] original =
                Files.readAllBytes(FIXTURES.resolve(fixture.path("path").asText()));
        Files.createDirectories(journal().getParent());
        Files.write(journal(), original);

        final PendingPluginOperations pending = new PendingPluginOperations(home);
        assertFalse(pending.recoveryRequired(), "a released journal must never be treated as corrupt");
        assertEquals("PLUGIN_PENDING_READY", pending.pendingChanges().code());

        final List<PendingPluginOperations.Operation> operations = pending.operations();
        assertEquals(2, operations.size(), "both released operations must be read");
        final PendingPluginOperations.Operation uninstall = operations.get(0);
        assertEquals("UNINSTALL", uninstall.type());
        assertEquals("dev.turboism.plugin.example", uninstall.pluginId());
        assertEquals("", uninstall.stagedJar());
        assertEquals(0L, uninstall.jarSize());

        final PendingPluginOperations.Operation install = operations.get(1);
        assertEquals("INSTALL", install.type());
        assertEquals("dev.turboism.plugin.uitheme", install.pluginId());
        assertEquals("0.1.0", install.version());
        assertTrue(install.jarSha256().matches("[0-9a-f]{64}"));
        assertEquals(
                "9c26efa63293cbdb327609df481b57c7df3bb6ea679f18a8e8211c42a055d22b",
                install.jarSha256(),
                "the retired rawSha256 field must not replace the staged JAR digest that verifies the package");
        assertEquals(130987L, install.jarSize());
        assertTrue(install.descriptorSha256().matches("[0-9a-f]{64}"));
        assertArrayEquals(original, Files.readAllBytes(journal()), "reading a released journal must not rewrite it");
    }
}
