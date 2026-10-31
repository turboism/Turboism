package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PendingPluginChangesRecoveryTest {
    @TempDir
    Path root;

    private RuntimePluginManagementService service(final Path home) {
        return new RuntimePluginManagementService(home, (java.util.function.Supplier<Optional<Path>>) Optional::empty, List::of);
    }

    private Path journal(final Path home) {
        return home.resolve("state/runtime/plugin-management/pending.json");
    }

    private void stage(final RuntimePluginManagementService service) throws Exception {
        final Path update = root.resolve("update.jar");
        Files.write(update, PluginManagementPackageFixture.pluginJarBytes("example.plugin", "2.0.0"));
        assertTrue(service.pendingOperations().stage(update).accepted());
    }

    @Test
    void explicitCancellationKeepsInstalledBytesSettingsAndUnrelatedStagingFiles() throws Exception {
        final Path home = root.resolve("home");
        final Path live = Files.createDirectories(home.resolve("plugins")).resolve("legacy-name.jar");
        final byte[] original = PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0");
        Files.write(live, original);
        final Path config = Files.writeString(home.resolve("config.json"), "{\"sentinel\":true}");
        try (var service = service(home)) {
            stage(service);
            final Path owned = Path.of(service.pendingOperations().operations().get(0).stagedJar());
            final Path unrelated = Files.writeString(owned.getParent().resolve("user-note.jar"), "keep me");
            final var review = service.pendingChanges();
            assertEquals("PLUGIN_PENDING_READY", review.code());
            assertEquals("example.plugin", review.changes().get(0).pluginId());
            assertTrue(service.discardPendingChanges(review.snapshot()).accepted());
            assertFalse(Files.exists(journal(home)));
            assertFalse(Files.exists(owned));
            assertEquals("keep me", Files.readString(unrelated));
            assertEquals("{\"sentinel\":true}", Files.readString(config));
            assertArrayEquals(original, Files.readAllBytes(live));
            assertEquals("PLUGIN_PENDING_EMPTY", service.pendingChanges().code());
            stage(service); // Retry may publish a fresh intent; the old one was not silently executed.
            assertEquals(1, service.pendingOperations().operations().size());
        }
    }

    @Test
    void changedIntentAfterReviewMustNotBeCancelled() throws Exception {
        final Path home = root.resolve("home");
        try (var service = service(home)) {
            stage(service);
            final var review = service.pendingChanges();
            assertTrue(service.pendingOperations().stageUninstall("example.other").accepted());
            final byte[] current = Files.readAllBytes(journal(home));
            assertEquals("PLUGIN_PLAN_CHANGED", service.discardPendingChanges(review.snapshot()).code());
            assertArrayEquals(current, Files.readAllBytes(journal(home)));
            assertEquals(2, service.pendingOperations().operations().size());
        }
    }

    @Test
    void identicallyRestagedIntentStillRequiresFreshConsent() throws Exception {
        final Path home = root.resolve("home");
        try (var service = service(home)) {
            assertTrue(service.pendingOperations().stageUninstall("example.same").accepted());
            final var oldReview = service.pendingChanges();
            assertTrue(service.pendingOperations().stageUninstall("example.same").accepted());
            assertEquals("PLUGIN_PLAN_CHANGED", service.discardPendingChanges(oldReview.snapshot()).code());
            assertEquals(1, service.pendingOperations().operations().size());
        }
    }

    @Test
    void uninstallOnlySnapshotsAreBoundToTheInstallationRoot() throws Exception {
        try (var a = service(root.resolve("a")); var b = service(root.resolve("b"))) {
            assertTrue(a.pendingOperations().stageUninstall("example.plugin").accepted());
            assertTrue(b.pendingOperations().stageUninstall("example.plugin").accepted());
            Files.write(journal(b.homeDirectory()), Files.readAllBytes(journal(a.homeDirectory())));
            assertNotEquals(a.pendingChanges().snapshot(), b.pendingChanges().snapshot());
            final byte[] before = Files.readAllBytes(journal(b.homeDirectory()));
            assertEquals("PLUGIN_PLAN_CHANGED", b.discardPendingChanges(a.pendingChanges().snapshot()).code());
            assertArrayEquals(before, Files.readAllBytes(journal(b.homeDirectory())));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending.json", "journal.json"})
    void frameworkIntentOrHealthTransactionBlocksCancellationEvenAfterConsent(final String name) throws Exception {
        final Path home = root.resolve("home");
        try (var service = service(home)) {
            stage(service);
            final var review = service.pendingChanges();
            final Path work = Files.createDirectories(home.resolve("state/runtime/update/helper"));
            final Path marker = Files.writeString(work.resolve(name), "framework sentinel");
            final byte[] intent = Files.readAllBytes(journal(home));
            assertEquals("PLUGIN_FRAMEWORK_RECOVERY_PENDING", service.pendingChanges().code());
            assertEquals("PLUGIN_FRAMEWORK_RECOVERY_PENDING", service.discardPendingChanges(review.snapshot()).code());
            assertArrayEquals(intent, Files.readAllBytes(journal(home)));
            assertEquals("framework sentinel", Files.readString(marker));
            Files.delete(marker);
            assertTrue(service.discardPendingChanges(review.snapshot()).accepted());
        }
    }

    @Test
    void foreignManualLeaseIsNotStolenOrReleased() throws Exception {
        final Path home = root.resolve("home");
        try (var service = service(home)) {
            stage(service);
            final var review = service.pendingChanges();
            try (var lease = PluginUpdateGate.acquireLease(home).orElseThrow()) {
                assertEquals("PLUGIN_PENDING_BUSY", service.discardPendingChanges(review.snapshot()).code());
                assertTrue(PluginUpdateGate.isHeld(home));
                assertEquals(1, service.pendingOperations().operations().size());
            }
            assertTrue(service.discardPendingChanges(review.snapshot()).accepted());
            assertFalse(PluginUpdateGate.isHeld(home));
        }
    }

    @Test
    void completeRollbackSuspendsOriginalIntentAndNeverRetriesBadBytes() throws Exception {
        final Path home = root.resolve("home");
        final Path live = Files.createDirectories(home.resolve("plugins")).resolve("legacy.jar");
        final byte[] original = PluginManagementPackageFixture.pluginJarBytes("example.plugin", "1.0.0");
        Files.write(live, original);
        try (var service = service(home)) {
            stage(service);
            final byte[] consent = Files.readAllBytes(journal(home));
            final Path bad = Path.of(service.pendingOperations().operations().get(0).stagedJar());
            Files.write(bad, new byte[] {0});
            assertEquals(PendingPluginOperations.Status.ROLLED_BACK, service.pendingOperations().apply().status());
            final Path failed = journal(home).resolveSibling("failed-pending.json");
            assertFalse(Files.exists(journal(home)), "Older runtimes must not see an active bad intent");
            assertArrayEquals(consent, Files.readAllBytes(failed));
            assertArrayEquals(original, Files.readAllBytes(live));
            assertEquals(PendingPluginOperations.Status.DEFERRED, service.pendingOperations().apply().status());
            assertArrayEquals(consent, Files.readAllBytes(failed));
            assertEquals("PLUGIN_PENDING_FAILED", service.pendingChanges().code());
            assertTrue(PluginUpdateGate.isAutoUpdateBlocked(home));
            final var coordinated = new CoordinatedPluginUpdateService(home, new PluginCatalogClient(),
                    service.pendingOperations(), dev.turboism.core.FrameworkBuildInfo.Channel.STABLE,
                    Optional::empty, dev.turboism.core.FrameworkBuildInfo.current().apiVersion());
            assertEquals("PLUGIN_PENDING_REVIEW_REQUIRED", coordinated.downloadAndPrepare(List.of(), Optional.empty(),
                    new PluginCatalogClient.DownloadControl()).code());
            assertFalse(service.pendingOperations().stageUninstall("example.other").accepted());
            assertTrue(service.discardPendingChanges(service.pendingChanges().snapshot()).accepted());
            assertFalse(Files.exists(failed));
            assertFalse(Files.exists(bad));
            assertFalse(PluginUpdateGate.isAutoUpdateBlocked(home));
            assertArrayEquals(original, Files.readAllBytes(live));
            stage(service);
            assertTrue(service.pendingOperations().apply().applied());
        }
    }

    @Test
    void cancellingSuspendedBatchNeverErasesSeparateActiveIntentOrItsReferencedPackage() throws Exception {
        final Path home = root.resolve("home");
        try (var service = service(home)) {
            stage(service);
            final byte[] consent = Files.readAllBytes(journal(home));
            final Path failed = journal(home).resolveSibling("failed-pending.json");
            Files.move(journal(home), failed);
            final var review = service.pendingChanges();
            // A legacy writer can create a separate active intent while unaware of failed-pending.json.
            Files.write(journal(home), consent);
            final Path shared = Path.of(service.pendingOperations().operations().get(0).stagedJar());
            assertTrue(service.discardPendingChanges(review.snapshot()).accepted());
            assertFalse(Files.exists(failed));
            assertArrayEquals(consent, Files.readAllBytes(journal(home)));
            assertTrue(Files.exists(shared));
            assertEquals("PLUGIN_PENDING_READY", service.pendingChanges().code());
        }
    }

    @Test
    void malformedIntentIsNeverBlessedOrDeleted() throws Exception {
        final Path home = root.resolve("home");
        Files.createDirectories(journal(home).getParent());
        Files.writeString(journal(home), "not json");
        try (var service = service(home)) {
            assertEquals("PLUGIN_PENDING_RECOVERY_REQUIRED", service.pendingChanges().code());
            assertEquals("PLUGIN_PENDING_RECOVERY_REQUIRED", service.discardPendingChanges("untrusted").code());
            assertEquals("not json", Files.readString(journal(home)));
        }
    }

    @Test
    void cleanupDoesNotFollowUntrustedStagedPaths() throws Exception {
        final Path home = root.resolve("home");
        try (var service = service(home)) {
            stage(service);
            final Path userFile = Files.writeString(root.resolve("user.jar"), "user sentinel");
            final var json = new com.fasterxml.jackson.databind.ObjectMapper();
            final var node = json.readTree(journal(home).toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("operations").get(0))
                    .put("stagedJar", userFile.toString());
            json.writeValue(journal(home).toFile(), node);
            assertTrue(service.discardPendingChanges(service.pendingChanges().snapshot()).accepted());
            assertEquals("user sentinel", Files.readString(userFile));
            assertFalse(Files.exists(journal(home)));
        }
    }

    @Test
    void workerOnlyApiDoesNoMutationOnEdt() throws Exception {
        final Path home = root.resolve("home");
        try (var service = service(home)) {
            stage(service);
            final var review = service.pendingChanges();
            final AtomicReference<String> code = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("PLUGIN_PENDING_WORKER_REQUIRED", service.pendingChanges().code());
                code.set(service.discardPendingChanges(review.snapshot()).code());
            });
            assertEquals("PLUGIN_PENDING_WORKER_REQUIRED", code.get());
            assertEquals(1, service.pendingOperations().operations().size());
        }
    }

    @Test
    void closedServiceAndEmptyOrForgedReviewCannotCancelAnything() throws Exception {
        final Path home = root.resolve("home");
        final var service = service(home);
        final var empty = service.pendingChanges();
        assertEquals("PLUGIN_PENDING_EMPTY", service.discardPendingChanges(empty.snapshot()).code());
        stage(service);
        assertFalse(service.discardPendingChanges("").accepted());
        service.close();
        assertEquals("PLUGIN_PENDING_UNAVAILABLE", service.pendingChanges().code());
        assertEquals("PLUGIN_PENDING_UNAVAILABLE", service.discardPendingChanges(empty.snapshot()).code());
        assertTrue(Files.exists(journal(home)));
    }

    @Test
    void oversizedJournalWritePreservesPreviousIntent() throws Exception {
        final Path home = root.resolve("home");
        try (var service = service(home)) {
            stage(service);
            final byte[] before = Files.readAllBytes(journal(home));
            assertFalse(service.pendingOperations().stageUninstall("x".repeat(70_000)).accepted());
            assertArrayEquals(before, Files.readAllBytes(journal(home)));
        }
    }
}
