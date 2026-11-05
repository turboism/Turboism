package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
}
