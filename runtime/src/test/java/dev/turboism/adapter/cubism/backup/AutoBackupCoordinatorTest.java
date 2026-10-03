package dev.turboism.adapter.cubism.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.AutoBackupVerificationManifest;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.backup.BackupArtifactHandle;
import dev.turboism.sdk.cubism.backup.BackupRunResult;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupStatus;
import dev.turboism.sdk.plugin.Registration;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoBackupCoordinatorTest {

    @TempDir
    Path temporary;

    @Test
    void availabilityFollowsTheAdapterProbe() throws Exception {
        final AutoBackupCoordinator safe =
                new AutoBackupCoordinator(AutoBackupAdapter.safeMode(), ignored -> {}, Clock.systemUTC(), 60_000L);
        assertFalse(safe.isAvailable(), "a coordinator over the safe-mode adapter must report unavailable");
        safe.close();

        final FakeHost host = new FakeHost();
        final AutoBackupCoordinator connected = new AutoBackupCoordinator(
                AutoBackupAdapter.connected(host.operations()), ignored -> {}, Clock.systemUTC(), 60_000L);
        assertTrue(connected.isAvailable());
        connected.close();
        assertFalse(connected.isAvailable(), "a closed coordinator must report unavailable");
    }

    @Test
    void unknownDeclarationWithMatchedSelectorsCanMutateSettingsAndProduceABackup() throws Exception {
        final FakeHost host = new FakeHost();
        final VerifiedMemberResolver resolver = host.resolver(true, "5.3.99");
        final AutoBackupCoordinator service = coordinator(resolver, 60_000L);

        final EditorAutoBackupSettings updated =
                service.updateSettings(new EditorAutoBackupSettings(true, 3, 120, java.util.Optional.empty()));
        final BackupRunResult backup = service.backupNow().toCompletableFuture().get(30, TimeUnit.SECONDS);

        assertEquals("5.3.99", resolver.cubismVersion());
        assertEquals("5.3.02", resolver.admittedCubismVersion());
        assertEquals(3, updated.intervalMinutes());
        assertEquals(120, host.manager.maxMB);
        assertEquals(1, host.attachCalls);
        assertEquals(1, host.updateCalls);
        assertEquals(1, backup.artifacts().size());
        assertTrue(backup.artifacts().get(0).sizeBytes() > 0);
        assertTrue(host.onEdt.get());
    }

    @Test
    void unknownDeclarationStillRollsBackAFailedSettingsMutation() {
        final FakeHost host = new FakeHost();
        host.failOnSetInterval = true;
        final AutoBackupCoordinator service = coordinator(host.resolver(true, "5.3.99"), 60_000L);

        assertThrows(
                RuntimeException.class,
                () -> service.updateSettings(new EditorAutoBackupSettings(false, 9, 80, java.util.Optional.empty())));

        assertTrue(host.manager.enabled);
        assertEquals(5, host.manager.interval);
        assertEquals(50, host.manager.maxMB);
    }

    @Test
    void settingsReadsThroughTheVerifiedHostOnTheEdt() {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        EditorAutoBackupSettings settings = service.settings();
        assertTrue(settings.enabled());
        assertEquals(5, settings.intervalMinutes());
        assertEquals(50, settings.maxMB());
        assertEquals(java.util.Optional.of(host.backupDir().getPath()), settings.backupDirDisplay());
        assertTrue(host.onEdt.get(), "host operations must run on the EDT");
    }

    @Test
    void updateSettingsAppliesAndReadsBackWithoutTouchingTheBackupDir() {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        EditorAutoBackupSettings updated =
                service.updateSettings(new EditorAutoBackupSettings(true, 3, 120, java.util.Optional.of("ignored")));
        assertTrue(updated.enabled());
        assertEquals(3, updated.intervalMinutes());
        assertEquals(120, updated.maxMB());
        assertEquals(3, host.manager.interval);
        assertEquals(120, host.manager.maxMB);
        assertEquals(
                java.util.Optional.of(host.backupDir().getPath()),
                updated.backupDirDisplay(),
                "backupDir is host-read-only");
    }

    @Test
    void updateSettingsShortCircuitsIdenticalValuesWithoutSetterSideEffects() {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        service.updateSettings(new EditorAutoBackupSettings(true, 5, 50, java.util.Optional.empty()));
        assertEquals(0, host.manager.setCalls, "no setter side effects for an identical request");
    }

    @Test
    void updateSettingsRollsBackToTheObservedOriginalsWhenAMutationFails() {
        FakeHost host = new FakeHost();
        host.failOnSetInterval = true;
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        assertThrows(
                RuntimeException.class,
                () -> service.updateSettings(new EditorAutoBackupSettings(false, 9, 80, java.util.Optional.empty())));
        assertTrue(host.manager.enabled, "enabled must be restored");
        assertEquals(5, host.manager.interval, "interval must be restored");
        assertEquals(50, host.manager.maxMB, "maxMB must be restored");
    }

    @Test
    void updateSettingsFailsClosedWhenTheRollbackCannotBeVerified() {
        FakeHost host = new FakeHost();
        host.failOnSetInterval = true;
        host.failOnRestoreInterval = true;
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        assertThrows(
                RuntimeException.class,
                () -> service.updateSettings(new EditorAutoBackupSettings(false, 9, 80, java.util.Optional.empty())));
        assertFalse(host.manager.restoredVerified, "an unverified rollback must not be claimed");
    }

    @Test
    void failsClosedWhenSelectorsAreAbsentFromTheVerifiedPlan() {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host.resolver(false), 60_000L);
        assertThrows(RuntimeException.class, service::settings);
        assertThrows(
                RuntimeException.class,
                () -> service.updateSettings(new EditorAutoBackupSettings(true, 3, 120, java.util.Optional.empty())));
        assertThrows(RuntimeException.class, service::statuses);
        CompletionStage<BackupRunResult> stage = service.backupNow();
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> stage.toCompletableFuture().get(10, TimeUnit.SECONDS),
                "backupNow must fail closed without verified selectors");

        CompletionStage<BackupRunResult> afterSave = service.backupAfterSave(snapshot("model.cmo3"));
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> afterSave.toCompletableFuture().get(10, TimeUnit.SECONDS),
                "backupAfterSave must fail closed without verified selectors");
        assertEquals(5, host.manager.interval, "no host mutation without verified selectors");
    }

    @Test
    void statusesSnapshotEveryDocumentInTheCurrentPack() {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        List<EditorAutoBackupStatus> statuses = service.statuses();
        assertEquals(2, statuses.size());
        EditorAutoBackupStatus first = statuses.get(0);
        assertEquals("model.cmo3", first.documentName());
        assertEquals(1_000L, first.lastAutoBackupTimeMillis());
        assertEquals(900L, first.lastSavedTimeMillis());
        assertTrue(first.modifiedAfterSaving());
    }

    @Test
    void backupNowProducesTheEventWithFreshArtifactsAndPublishesIt() throws Exception {
        FakeHost host = new FakeHost();
        RecordingEventSink bus = new RecordingEventSink();
        AutoBackupCoordinator service = new AutoBackupCoordinator(
                AutoBackupAdapter.connected(host.operations()), bus, Clock.systemUTC(), 60_000L);

        BackupRunResult event = service.backupNow().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(1, event.artifacts().size());
        assertTrue(
                event.artifacts().get(0).fileName().startsWith("model_backup"),
                "artifact must match the <name>_backup<ts>.cmo3 pattern");
        assertTrue(event.artifacts().get(0).sizeBytes() > 0);
        assertTrue(
                event.statuses().stream().anyMatch(status -> status.lastAutoBackupTimeMillis() > 1_000L),
                "lastAutoBackupTime must advance after a completed backup");
        assertEquals(1, bus.events.size(), "the completed event must be published");
        assertEquals(1, host.attachCalls);
        assertEquals(1, host.updateCalls);
        assertTrue(host.onEdt.get(), "the host trigger must run on the EDT");
    }

    @Test
    void backupNowInvokesSyncTargetsWithTheNewFilesAndIsolatesTargetFailures() throws Exception {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        List<BackupArtifactHandle> received = new CopyOnWriteArrayList<>();
        Registration first = service.registerSyncTarget(files -> {
            received.addAll(files);
            throw new IllegalStateException("target exploded");
        });
        service.registerSyncTarget(files -> {
            received.addAll(files);
            throw new IllegalStateException("second target exploded");
        });

        BackupRunResult event = service.backupNow().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(1, event.artifacts().size(), "target failures must not corrupt the result");
        assertEquals(2, received.size(), "every registered target must still be invoked");

        first.close();
        BackupRunResult second = service.backupNow().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(1, second.artifacts().size(), "closed registrations are removed");
    }

    @Test
    void backupNowTimesOutAndFailsClosedWhenNoArtifactAppears() {
        FakeHost host = new FakeHost();
        host.produceArtifact = false;
        AutoBackupCoordinator service = coordinator(host, 1_500L);
        CompletionStage<BackupRunResult> stage = service.backupNow();
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> stage.toCompletableFuture().get(30, TimeUnit.SECONDS));
    }

    @Test
    void backupNowFailsClosedWhenTheHostHasNoCompletePack() {
        FakeHost host = new FakeHost();
        host.packPresent = false;
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        CompletionStage<BackupRunResult> stage = service.backupNow();
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> stage.toCompletableFuture().get(30, TimeUnit.SECONDS));
    }

    @Test
    void closeShutsDownTheHostThreadAndRejectsFurtherUse() {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        service.close();
        assertThrows(IllegalStateException.class, service::settings);
        assertThrows(IllegalStateException.class, service::backupNow);
    }

    @Test
    void closeSettlesQueuedBackupStagesInsteadOfLeavingThemIncomplete() throws Exception {
        final ThreadPoolExecutor hostThread =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
                    final Thread thread = new Thread(runnable, "test-autobackup-host");
                    thread.setDaemon(true);
                    return thread;
                });
        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        hostThread.execute(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
        final FakeHost host = new FakeHost();
        final AutoBackupCoordinator service = new AutoBackupCoordinator(
                AutoBackupAdapter.connected(host.operations()),
                new RecordingEventSink(),
                Clock.systemUTC(),
                60_000L,
                ignored -> {},
                null,
                hostThread);
        final CompletionStage<BackupRunResult> stage = service.backupNow();

        service.close();
        releaseBlocker.countDown();

        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> stage.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertTrue(stage.toCompletableFuture().isDone());
    }

    @Test
    void throwingDiagnosticsCannotLeaveAStageIncomplete() throws Exception {
        final FakeHost host = new FakeHost();
        final AutoBackupCoordinator service = new AutoBackupCoordinator(
                AutoBackupAdapter.connected(new VerifiedAutoBackupHostOperations(host.resolver(false))),
                new RecordingEventSink(),
                Clock.systemUTC(),
                60_000L,
                ignored -> {
                    throw new IllegalStateException("diagnostics failed");
                },
                null);

        final CompletionStage<BackupRunResult> stage = service.backupNow();

        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertTrue(stage.toCompletableFuture().isDone());
        service.close();
    }

    @Test
    void closedCoordinatorRejectsLateContinuationRegistration() throws Exception {
        final dev.turboism.sdk.plugin.DisposableScope scope = new dev.turboism.sdk.plugin.DisposableScope();
        final dev.turboism.core.runtime.RuntimeScheduler runtimeScheduler =
                new dev.turboism.core.runtime.RuntimeScheduler(
                        new dev.turboism.core.runtime.DefaultWorkBudgetPolicy(),
                        new dev.turboism.core.runtime.work.PluginWorkExecutorRegistry(
                                1, 8, ignored -> {}, Clock.systemUTC()),
                        dev.turboism.core.runtime.sidecar.SidecarDispatcher.noop(),
                        ignored -> {});
        final dev.turboism.task.RuntimePluginTaskScheduler pluginTasks =
                new dev.turboism.task.RuntimePluginTaskScheduler("dev.example.backup", runtimeScheduler, scope);
        final FakeHost host = new FakeHost();
        final AutoBackupCoordinator service = new AutoBackupCoordinator(
                AutoBackupAdapter.connected(host.operations()),
                new RecordingEventSink(),
                Clock.systemUTC(),
                60_000L,
                ignored -> {},
                pluginTasks);
        final CompletionStage<BackupRunResult> stage = service.backupAfterSave(snapshot("model.cmo3"));
        stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

        service.close();

        assertThrows(IllegalStateException.class, () -> stage.whenComplete((ignored, failure) -> {}));
        scope.close();
        runtimeScheduler.shutdown();
    }

    @Test
    void rejectedHostExecutionDoesNotLeaveDebounceStateOwnedByCompletedStage() {
        final ThreadPoolExecutor hostThread =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        hostThread.shutdown();
        final FakeHost host = new FakeHost();
        final AutoBackupCoordinator service = new AutoBackupCoordinator(
                AutoBackupAdapter.connected(host.operations()),
                new RecordingEventSink(),
                Clock.systemUTC(),
                60_000L,
                ignored -> {},
                null,
                hostThread);
        final ProjectContentSnapshot saved = snapshot("model.cmo3");

        final CompletionStage<BackupRunResult> first = service.backupAfterSave(saved);
        final CompletionStage<BackupRunResult> second = service.backupAfterSave(saved);

        assertNotSame(first, second);
        assertTrue(first.toCompletableFuture().isCompletedExceptionally());
        assertTrue(second.toCompletableFuture().isCompletedExceptionally());
        service.close();
    }

    @Test
    void closeWaitsForAnAlreadyRunningContinuationBeforeReturning() throws Exception {
        final dev.turboism.sdk.plugin.DisposableScope scope = new dev.turboism.sdk.plugin.DisposableScope();
        final dev.turboism.core.runtime.RuntimeScheduler runtimeScheduler =
                new dev.turboism.core.runtime.RuntimeScheduler(
                        new dev.turboism.core.runtime.DefaultWorkBudgetPolicy(),
                        new dev.turboism.core.runtime.work.PluginWorkExecutorRegistry(
                                1, 8, ignored -> {}, Clock.systemUTC()),
                        dev.turboism.core.runtime.sidecar.SidecarDispatcher.noop(),
                        ignored -> {});
        final dev.turboism.task.RuntimePluginTaskScheduler pluginTasks =
                new dev.turboism.task.RuntimePluginTaskScheduler("dev.example.backup", runtimeScheduler, scope);
        final FakeHost host = new FakeHost();
        final AutoBackupCoordinator service = new AutoBackupCoordinator(
                AutoBackupAdapter.connected(host.operations()),
                new RecordingEventSink(),
                Clock.systemUTC(),
                60_000L,
                ignored -> {},
                pluginTasks);
        final CountDownLatch callbackStarted = new CountDownLatch(1);
        final CountDownLatch releaseCallback = new CountDownLatch(1);
        service.backupAfterSave(snapshot("model.cmo3")).whenComplete((ignored, failure) -> {
            callbackStarted.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(callbackStarted.await(5, TimeUnit.SECONDS));
        final CountDownLatch closeReturned = new CountDownLatch(1);
        final Thread closeThread = new Thread(
                () -> {
                    service.close();
                    closeReturned.countDown();
                },
                "test-autobackup-close");
        closeThread.start();

        assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS));
        releaseCallback.countDown();
        assertTrue(closeReturned.await(5, TimeUnit.SECONDS));
        scope.close();
        runtimeScheduler.shutdown();
    }

    @Test
    void backupAfterSaveProducesTheArtifactPublishesTheEventAndSyncs() throws Exception {
        FakeHost host = new FakeHost();
        RecordingEventSink bus = new RecordingEventSink();
        AutoBackupCoordinator service = new AutoBackupCoordinator(
                AutoBackupAdapter.connected(host.operations()), bus, Clock.systemUTC(), 60_000L);
        List<BackupArtifactHandle> received = new CopyOnWriteArrayList<>();
        service.registerSyncTarget(files -> {
            received.addAll(files);
            throw new IllegalStateException("sync target exploded");
        });
        BackupRunResult event = service.backupAfterSave(snapshot("model.cmo3"))
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
        assertEquals(1, event.artifacts().size());
        BackupArtifactHandle artifact = event.artifacts().get(0);
        assertTrue(
                artifact.fileName().startsWith("model_backup"),
                "artifact must match the <name>_backup<ts>.cmo3 pattern");
        assertTrue(artifact.fileName().endsWith(".cmo3"));
        assertTrue(artifact.sizeBytes() > 0);
        assertTrue(artifact.temporary(), "the save-triggered artifact must be a temporary artifact");
        assertEquals(
                FakeHost.FakeFileContent.sourceContent("model.cmo3"),
                readHandle(artifact),
                "the artifact must be a copy of the document file");
        assertEquals(1, bus.events.size(), "the completed event must be published");
        assertEquals(List.of(artifact), received, "sync targets must receive the new artifact");
        assertTrue(host.onEdt.get(), "the file copy must run on the host thread");
    }

    @Test
    void backupAfterSaveCoalescesSavesWithinTheDebounceWindowAndRunsAfterItExpires() throws Exception {
        FakeHost host = new FakeHost();
        MutableClock clock = new MutableClock(1_000_000L);
        AutoBackupCoordinator service = coordinator(host, clock, 60_000L);
        ProjectContentSnapshot saved = snapshot("model.cmo3");
        CompletionStage<BackupRunResult> first = service.backupAfterSave(saved);
        clock.advance(1_000L); // still inside the 2s debounce window
        CompletionStage<BackupRunResult> second = service.backupAfterSave(saved);
        assertSame(first, second, "a save inside the debounce window must coalesce");
        assertEquals(
                1,
                first.toCompletableFuture()
                        .get(30, TimeUnit.SECONDS)
                        .artifacts()
                        .size(),
                "one backup for saves inside the window");
        clock.advance(2_000L); // window expired
        CompletionStage<BackupRunResult> third = service.backupAfterSave(saved);
        assertNotSame(first, third, "a save after the window must start a new backup");
        assertEquals(
                1,
                third.toCompletableFuture()
                        .get(30, TimeUnit.SECONDS)
                        .artifacts()
                        .size());
    }

    @Test
    void backupAfterSaveSupportsGameDataDocumentsThroughTheVoidPrimitive() throws Exception {
        FakeHost host = new FakeHost();
        host.pack.fileContents = List.of(new FakeHost.FakeGameDataDocument(host, "game.cmo3", 0L, 0L, false));
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        BackupRunResult event = service.backupAfterSave(snapshot("game.cmo3"))
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
        assertEquals(1, event.artifacts().size());
        assertTrue(event.artifacts().get(0).fileName().startsWith("game_backup"));
        assertEquals(
                FakeHost.FakeFileContent.sourceContent("game.cmo3"),
                readHandle(event.artifacts().get(0)));
    }

    @Test
    void backupAfterSaveMatchesByModelingDocumentUidWhenTheNameDiffers() throws Exception {
        FakeHost host = new FakeHost();
        FakeHost.FakeModelingDocument model = new FakeHost.FakeModelingDocument(host, "fixture.cmo3", 0L, 0L, false);
        model.uid = "uid-model-1";
        host.pack.fileContents = List.of(model);
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        // The saved snapshot name differs from the pack file name (runner rename),
        // but the stable document UID matches.
        BackupRunResult event = service.backupAfterSave(snapshotWithUids("测试 混合模式.cmo3", List.of("uid-model-1")))
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
        assertEquals(1, event.artifacts().size());
        assertTrue(event.artifacts().get(0).fileName().startsWith("fixture_backup"));
        assertEquals(
                FakeHost.FakeFileContent.sourceContent("fixture.cmo3"),
                readHandle(event.artifacts().get(0)),
                "the UID match must copy the selected pack content");
    }

    @Test
    void backupAfterSaveMatchesByAnimationSceneUid() throws Exception {
        FakeHost host = new FakeHost();
        FakeHost.FakeAnimationFileContent animation =
                new FakeHost.FakeAnimationFileContent(host, "anim.motion3.json", 0L, 0L, false);
        animation.scenes = List.of(
                new FakeHost.FakeSceneDocument(host, "uid-scene-1"),
                new FakeHost.FakeSceneDocument(host, "uid-scene-2"));
        host.pack.fileContents = List.of(animation);
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        BackupRunResult event = service.backupAfterSave(
                        snapshotWithUids("renamed-anim.motion3.json", List.of("uid-scene-2")))
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
        assertEquals(1, event.artifacts().size());
        assertTrue(event.artifacts().get(0).fileName().startsWith("anim.motion3_backup"));
        assertEquals(
                FakeHost.FakeFileContent.sourceContent("anim.motion3.json"),
                readHandle(event.artifacts().get(0)),
                "a scene UID hit must copy the animation");
    }

    @Test
    void backupAfterSaveFallsBackToNameMatchingWhenNoUidMatches() throws Exception {
        FakeHost host = new FakeHost();
        FakeHost.FakeModelingDocument model = new FakeHost.FakeModelingDocument(host, "model.cmo3", 0L, 0L, false);
        model.uid = "uid-model-1";
        host.pack.fileContents = List.of(model);
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        BackupRunResult event = service.backupAfterSave(snapshotWithUids("model.cmo3", List.of("uid-other")))
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
        assertEquals(1, event.artifacts().size());
        assertEquals(
                FakeHost.FakeFileContent.sourceContent("model.cmo3"),
                readHandle(event.artifacts().get(0)),
                "no UID hit must fall back to the name match");
    }

    @Test
    void backupAfterSaveFallsBackToNameMatchingForAnEmptyUidList() throws Exception {
        FakeHost host = new FakeHost();
        host.pack.fileContents = List.of(new FakeHost.FakeModelingDocument(host, "model.cmo3", 0L, 0L, false));
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        BackupRunResult event = service.backupAfterSave(snapshot("model.cmo3"))
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
        assertEquals(1, event.artifacts().size());
        assertEquals(
                FakeHost.FakeFileContent.sourceContent("model.cmo3"),
                readHandle(event.artifacts().get(0)),
                "an empty UID list must use name matching");
    }

    @Test
    void backupAfterSaveFailsClosedWhenUidSelectorsAreMissing() throws Exception {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host.resolverWithoutUidSelectors(), 60_000L);
        CompletionStage<BackupRunResult> stage = service.backupAfterSave(snapshot("model.cmo3"));
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> stage.toCompletableFuture().get(30, TimeUnit.SECONDS));
        assertEquals(0, host.updateCalls, "missing UID selectors must fail before any host mutation");
    }

    @Test
    void backupAfterSaveFailsClosedWhenNoPackContentMatches() throws Exception {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        CompletionStage<BackupRunResult> stage = service.backupAfterSave(snapshot("missing.cmo3"));
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> stage.toCompletableFuture().get(30, TimeUnit.SECONDS));
        assertEquals(0, host.updateCalls, "no host mutation without a match");
    }

    @Test
    void artifactsListsRegularFilesInTheHostBackupDirectoryAsHandles() throws Exception {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        Files.writeString(host.backupDir.resolve("model_backup2026_08_08_1200.cmo3"), "artifact");
        Files.writeString(host.backupDir.resolve("notes.txt"), "not-an-artifact");
        Files.createDirectories(host.backupDir.resolve("nested"));

        List<BackupArtifactHandle> artifacts = service.artifacts();

        assertEquals(
                List.of("model_backup2026_08_08_1200.cmo3", "notes.txt"),
                artifacts.stream().map(BackupArtifactHandle::fileName).toList(),
                "the listing must cover regular files only, sorted by name, never directories");
        assertTrue(artifacts.stream().noneMatch(BackupArtifactHandle::temporary));
        assertEquals("artifact", readHandle(artifacts.get(0)));
        assertThrows(
                IllegalStateException.class,
                () -> artifacts.get(0).discard(),
                "host-owned artifacts are never discardable through the handle");
    }

    @Test
    void artifactsIsEmptyWhenTheHostExposesNoBackupDirectory() {
        FakeHost host = new FakeHost();
        host.manager.backupDir = null;
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        assertTrue(service.artifacts().isEmpty());
    }

    @Test
    void artifactsRequiresTheBackupObservePermission() {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = new AutoBackupCoordinator(
                AutoBackupAdapter.connected(host.operations()),
                new RecordingEventSink(),
                Clock.systemUTC(),
                60_000L,
                ignored -> {},
                null,
                dev.turboism.permissions.PermissionChecker.from(
                        List.<dev.turboism.sdk.permission.PluginPermission>of()));
        assertThrows(dev.turboism.sdk.permission.CubismPermissionException.class, service::artifacts);
    }

    @Test
    void issuedHandlesEnforceThePluginGrantOnReadsAndDiscards() throws Exception {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = new AutoBackupCoordinator(
                AutoBackupAdapter.connected(host.operations()),
                new RecordingEventSink(),
                Clock.systemUTC(),
                60_000L,
                ignored -> {},
                null,
                dev.turboism.permissions.PermissionChecker.from(
                        List.<dev.turboism.sdk.permission.PluginPermission>of()));
        BackupRunResult result = service.backupNow().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(1, result.artifacts().size(), "the command result is produced regardless");
        assertThrows(
                dev.turboism.sdk.permission.CubismPermissionException.class,
                () -> result.artifacts().get(0).openStream());
        assertThrows(
                dev.turboism.sdk.permission.CubismPermissionException.class,
                () -> result.artifacts().get(0).discard());
        service.close();
    }

    // ---- helpers ----

    private AutoBackupCoordinator coordinator(final FakeHost host, final long timeout) {
        return coordinator(host.resolver(true), timeout);
    }

    private AutoBackupCoordinator coordinator(final VerifiedMemberResolver resolver, final long timeout) {
        return new AutoBackupCoordinator(
                AutoBackupAdapter.connected(new VerifiedAutoBackupHostOperations(resolver)),
                new RecordingEventSink(),
                Clock.systemUTC(),
                timeout);
    }

    private AutoBackupCoordinator coordinator(final FakeHost host, final Clock clock, final long timeout) {
        return new AutoBackupCoordinator(
                AutoBackupAdapter.connected(new VerifiedAutoBackupHostOperations(host.resolver(true))),
                new RecordingEventSink(),
                clock,
                timeout);
    }

    private static ProjectContentSnapshot snapshot(final String name) {
        return new ProjectContentSnapshot(
                "model:test", name, ProjectContentKind.MODEL, java.util.Optional.empty(), List.of());
    }

    private static ProjectContentSnapshot snapshotWithUids(final String name, final List<String> uids) {
        return new ProjectContentSnapshot(
                "model:test", name, ProjectContentKind.MODEL, java.util.Optional.empty(), uids);
    }

    private static String readHandle(final BackupArtifactHandle handle) throws IOException {
        try (var in = handle.openStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static final class MutableClock extends Clock {
        private final java.util.concurrent.atomic.AtomicLong millis;

        MutableClock(final long initialMillis) {
            millis = new java.util.concurrent.atomic.AtomicLong(initialMillis);
        }

        void advance(final long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    private static final class RecordingEventSink
            implements java.util.function.Consumer<dev.turboism.sdk.cubism.backup.BackupCompletedEvent> {
        final List<dev.turboism.sdk.cubism.backup.BackupCompletedEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void accept(final dev.turboism.sdk.cubism.backup.BackupCompletedEvent event) {
            events.add(event);
        }
    }

    /**
     * Fake host mirroring the reviewed auto-backup manager surface. All fake members
     * record whether they were called on the EDT and support injected failures.
     */
    final class FakeHost {

        final FakeManager manager = new FakeManager();
        final FakeApp app = new FakeApp();
        final FakePack pack = new FakePack();
        final java.util.concurrent.atomic.AtomicBoolean onEdt = new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean failOnSetInterval;
        boolean failOnRestoreInterval;
        boolean produceArtifact = true;
        boolean packPresent = true;
        int attachCalls;
        int updateCalls;
        Path backupDir;

        FakeHost() {
            try {
                backupDir = Files.createDirectories(temporary.resolve("backup"));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            FakeManager.a = manager;
            manager.host = this;
            app.host = this;
            pack.host = this;
            manager.enabled = true;
            manager.interval = 5;
            manager.maxMB = 50;
            manager.backupDir = backupDir.toFile();
            FakeApp.INSTANCE = app;
            app.pack = pack;
            pack.fileContents = List.of(FakeFileContent.model(this), FakeFileContent.animation(this));
        }

        File backupDir() {
            return backupDir.toFile();
        }

        AutoBackupAdapter.HostOperations operations() {
            return new VerifiedAutoBackupHostOperations(resolver(true));
        }

        void onEdt() {
            onEdt.set(SwingUtilities.isEventDispatchThread());
        }

        VerifiedMemberResolver resolver(final boolean typed) {
            return resolver(typed, null);
        }

        VerifiedMemberResolver resolver(final boolean typed, final String declaredVersion) {
            List<StaticSelector> selectors = new ArrayList<>();
            String manager = internal(FakeManager.class);
            String app = internal(FakeApp.class);
            String pack = internal(FakePack.class);
            String content = internal(FakeFileContent.class);
            selectors.add(StaticSelector.classSelector("cubism.auto-backup.manager.class", manager));
            selectors.add(StaticSelector.field(
                    "cubism.auto-backup.manager.instance",
                    manager,
                    "a",
                    "L" + manager + ";",
                    StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.method("cubism.auto-backup.is-enabled", manager, "a", "()Z"));
            selectors.add(StaticSelector.method("cubism.auto-backup.set-enabled", manager, "a", "(Z)V"));
            selectors.add(StaticSelector.method("cubism.auto-backup.set-interval-minute", manager, "a", "(I)V"));
            selectors.add(StaticSelector.method("cubism.auto-backup.attach-pack", manager, "a", "(L" + pack + ";)V"));
            selectors.add(StaticSelector.method("cubism.auto-backup.get-interval-minute", manager, "b", "()I"));
            selectors.add(StaticSelector.method("cubism.auto-backup.set-max-mb", manager, "b", "(I)V"));
            selectors.add(StaticSelector.method("cubism.auto-backup.get-max-mb", manager, "c", "()I"));
            selectors.add(StaticSelector.method("cubism.auto-backup.update", manager, "h", "()V"));
            selectors.add(StaticSelector.method("cubism.auto-backup.backup-dir", manager, "i", "()Ljava/io/File;"));
            selectors.add(StaticSelector.classSelector("cubism.auto-backup.app-controller.class", app));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.app-controller.get-complete-pack", app, "getCompletePack", "()L" + pack + ";"));
            selectors.add(StaticSelector.classSelector("cubism.auto-backup.complete-pack.class", pack));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.complete-pack.file-contents",
                    pack,
                    "getAllFileContents",
                    "()Ljava/util/List;"));
            selectors.add(StaticSelector.classSelector("cubism.auto-backup.file-content.class", content));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.file-content.last-auto-backup-time", content, "getLastAutoBackupTime", "()J"));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.file-content.set-last-auto-backup-time",
                    content,
                    "setLastAutoBackupTime",
                    "(J)V"));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.file-content.last-saved-time", content, "getLastSavedTime", "()J"));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.file-content.modified-after-saving", content, "isModifiedAfterSaving", "()Z"));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.file-content.file", content, "getFile", "()Ljava/io/File;"));
            selectors.add(StaticSelector.staticMethod(
                    "cubism.auto-backup.app-controller.instance",
                    app,
                    "access$get_instance$cp",
                    "()L" + app + ";",
                    StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            String modeling = internal(FakeModelingDocument.class);
            String animationContent = internal(FakeAnimationFileContent.class);
            String gameData = internal(FakeGameDataDocument.class);
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.document-uid.modeling", modeling, "getDocumentUID", "()Ljava/lang/String;"));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.scene-docs",
                    animationContent,
                    "getSceneDocs",
                    "()Lcom/live2d/type/CArrayList;"));
            String scene = internal(FakeSceneDocument.class);
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.document-uid.scene", scene, "getDocumentUID", "()Ljava/lang/String;"));
            if (!typed) {
                selectors.removeIf(selector -> selector.alias().equals("cubism.auto-backup.manager.instance"));
            }
            if (declaredVersion != null) {
                return TestVerifiedResolvers.createCompatible(
                        "5.3.02",
                        declaredVersion,
                        AutoBackupVerificationManifest.ADAPTER_SLICE_ID,
                        AutoBackupVerificationManifest.CAPABILITY_IDS,
                        selectors,
                        FakeHost.class.getClassLoader());
            }
            return TestVerifiedResolvers.create(
                    AutoBackupVerificationManifest.ADAPTER_SLICE_ID,
                    AutoBackupVerificationManifest.CAPABILITY_IDS,
                    selectors,
                    FakeHost.class.getClassLoader());
        }

        /** Resolver whose UID selectors are absent (older record): saveDocumentFor fails closed. */
        VerifiedMemberResolver resolverWithoutUidSelectors() {
            List<StaticSelector> selectors = new ArrayList<>();
            String manager = internal(FakeManager.class);
            String app = internal(FakeApp.class);
            String pack = internal(FakePack.class);
            String content = internal(FakeFileContent.class);
            selectors.add(StaticSelector.classSelector("cubism.auto-backup.manager.class", manager));
            selectors.add(StaticSelector.field(
                    "cubism.auto-backup.manager.instance",
                    manager,
                    "a",
                    "L" + manager + ";",
                    StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.add(StaticSelector.method("cubism.auto-backup.is-enabled", manager, "a", "()Z"));
            selectors.add(StaticSelector.method("cubism.auto-backup.set-enabled", manager, "a", "(Z)V"));
            selectors.add(StaticSelector.method("cubism.auto-backup.set-interval-minute", manager, "a", "(I)V"));
            selectors.add(StaticSelector.method("cubism.auto-backup.attach-pack", manager, "a", "(L" + pack + ";)V"));
            selectors.add(StaticSelector.method("cubism.auto-backup.get-interval-minute", manager, "b", "()I"));
            selectors.add(StaticSelector.method("cubism.auto-backup.set-max-mb", manager, "b", "(I)V"));
            selectors.add(StaticSelector.method("cubism.auto-backup.get-max-mb", manager, "c", "()I"));
            selectors.add(StaticSelector.method("cubism.auto-backup.update", manager, "h", "()V"));
            selectors.add(StaticSelector.method("cubism.auto-backup.backup-dir", manager, "i", "()Ljava/io/File;"));
            selectors.add(StaticSelector.classSelector("cubism.auto-backup.app-controller.class", app));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.app-controller.get-complete-pack", app, "getCompletePack", "()L" + pack + ";"));
            selectors.add(StaticSelector.classSelector("cubism.auto-backup.complete-pack.class", pack));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.complete-pack.file-contents",
                    pack,
                    "getAllFileContents",
                    "()Ljava/util/List;"));
            selectors.add(StaticSelector.classSelector("cubism.auto-backup.file-content.class", content));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.file-content.last-auto-backup-time", content, "getLastAutoBackupTime", "()J"));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.file-content.set-last-auto-backup-time",
                    content,
                    "setLastAutoBackupTime",
                    "(J)V"));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.file-content.last-saved-time", content, "getLastSavedTime", "()J"));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.file-content.modified-after-saving", content, "isModifiedAfterSaving", "()Z"));
            selectors.add(StaticSelector.method(
                    "cubism.auto-backup.file-content.file", content, "getFile", "()Ljava/io/File;"));
            String modeling = internal(FakeModelingDocument.class);
            String animationContent = internal(FakeAnimationFileContent.class);
            String gameData = internal(FakeGameDataDocument.class);
            return TestVerifiedResolvers.create(
                    AutoBackupVerificationManifest.ADAPTER_SLICE_ID,
                    AutoBackupVerificationManifest.CAPABILITY_IDS,
                    selectors,
                    FakeHost.class.getClassLoader());
        }

        private static String internal(final Class<?> type) {
            return type.getName().replace('.', '/');
        }

        // ---- fake host members (mirroring the obfuscated manager) ----

        public static final class FakeManager {
            public static FakeManager a = new FakeManager();
            FakeHost host;
            boolean enabled;
            int interval;
            int maxMB;
            File backupDir;
            int setCalls;
            boolean restoredVerified;

            public boolean a() {
                onEdt();
                return enabled;
            }

            public void a(boolean value) {
                onEdt();
                setCalls++;
                enabled = value;
            }

            public void a(int value) {
                onEdt();
                setCalls++;
                if (host.failOnSetInterval) {
                    host.failOnSetInterval = false;
                    throw new IllegalStateException("injected host failure at setIntervalMinute");
                }
                if (host.failOnRestoreInterval && value == 5) {
                    host.failOnRestoreInterval = false;
                    restoredVerified = false;
                    throw new IllegalStateException("injected host failure restoring the interval");
                }
                if (value == 5) {
                    restoredVerified = true;
                }
                interval = value;
            }

            public void a(FakePack pack) {
                onEdt();
                host.attachCalls++;
            }

            public int b() {
                onEdt();
                return interval;
            }

            public void b(int value) {
                onEdt();
                setCalls++;
                maxMB = value;
            }

            public int c() {
                onEdt();
                return maxMB;
            }

            public void h() {
                onEdt();
                host.updateCalls++;
                if (host.produceArtifact) {
                    Thread producer = new Thread(
                            () -> {
                                try {
                                    Thread.sleep(100L);
                                    File artifact = new File(backupDir, "model_backup2026_08_08_1200.cmo3");
                                    Files.writeString(artifact.toPath(), "backup-content");
                                    for (Object content : host.pack.fileContents) {
                                        ((FakeFileContent) content).lastAutoBackupTime = Math.max(
                                                ((FakeFileContent) content).lastAutoBackupTime,
                                                System.currentTimeMillis());
                                    }
                                } catch (Exception ignored) {
                                    // fail closed in the fake producer
                                }
                            },
                            "fake-host-producer");
                    producer.setDaemon(true);
                    producer.start();
                }
            }

            public File i() {
                onEdt();
                return backupDir;
            }

            private void onEdt() {
                host.onEdt.set(SwingUtilities.isEventDispatchThread());
            }
        }

        public static final class FakeApp {
            static FakeApp INSTANCE = new FakeApp();
            FakeHost host;
            FakePack pack;

            public static FakeApp access$get_instance$cp() {
                return INSTANCE;
            }

            public FakePack getCompletePack() {
                onEdt();
                return host.packPresent ? pack : null;
            }

            private void onEdt() {
                host.onEdt.set(SwingUtilities.isEventDispatchThread());
            }
        }

        public static final class FakePack {
            FakeHost host;
            List<Object> fileContents = List.of();

            public List<Object> getAllFileContents() {
                onEdt();
                return fileContents;
            }

            private void onEdt() {
                host.onEdt.set(SwingUtilities.isEventDispatchThread());
            }
        }

        public static final class FakeModelingDocument extends FakeFileContent {
            FakeModelingDocument(
                    FakeHost host, String name, long lastAutoBackupTime, long lastSavedTime, boolean modified) {
                super(host, name, lastAutoBackupTime, lastSavedTime, modified);
            }

            public String getDocumentUID() {
                host.onEdt.set(SwingUtilities.isEventDispatchThread());
                return uid;
            }
        }

        public static final class FakeAnimationFileContent extends FakeFileContent {
            FakeAnimationFileContent(
                    FakeHost host, String name, long lastAutoBackupTime, long lastSavedTime, boolean modified) {
                super(host, name, lastAutoBackupTime, lastSavedTime, modified);
            }

            public com.live2d.type.CArrayList getSceneDocs() {
                host.onEdt.set(SwingUtilities.isEventDispatchThread());
                return new com.live2d.type.CArrayList(scenes);
            }
        }

        public static final class FakeGameDataDocument extends FakeFileContent {
            FakeGameDataDocument(
                    FakeHost host, String name, long lastAutoBackupTime, long lastSavedTime, boolean modified) {
                super(host, name, lastAutoBackupTime, lastSavedTime, modified);
            }
        }

        public static final class FakeSceneDocument {
            final FakeHost host;
            private final String uid;

            FakeSceneDocument(FakeHost host, String uid) {
                this.host = host;
                this.uid = uid;
            }

            public String getDocumentUID() {
                host.onEdt.set(SwingUtilities.isEventDispatchThread());
                return uid;
            }
        }

        public static class FakeFileContent {
            final FakeHost host;
            final String name;
            long lastAutoBackupTime;
            final long lastSavedTime;
            final boolean modified;
            String uid;
            List<FakeSceneDocument> scenes = List.of();

            FakeFileContent(FakeHost host, String name, long lastAutoBackupTime, long lastSavedTime, boolean modified) {
                this.host = host;
                this.name = name;
                this.lastAutoBackupTime = lastAutoBackupTime;
                this.lastSavedTime = lastSavedTime;
                this.modified = modified;
            }

            static FakeFileContent model(FakeHost host) {
                return new FakeModelingDocument(host, "model.cmo3", 1_000L, 900L, true);
            }

            static FakeFileContent animation(FakeHost host) {
                return new FakeAnimationFileContent(host, "anim.motion3.json", 500L, 400L, false);
            }

            public long getLastAutoBackupTime() {
                onEdt();
                return lastAutoBackupTime;
            }

            public void setLastAutoBackupTime(long value) {
                onEdt();
                lastAutoBackupTime = value;
            }

            public long getLastSavedTime() {
                onEdt();
                return lastSavedTime;
            }

            public boolean isModifiedAfterSaving() {
                onEdt();
                return modified;
            }

            public File getFile() {
                onEdt();
                // The host already wrote the document to its original file by
                // the time the save hook fires; materialize it for the copy.
                final File file = new File(host.backupDir.getParent().toFile(), name);
                try {
                    if (!file.isFile()) {
                        Files.writeString(file.toPath(), sourceContent(name));
                    }
                } catch (IOException failure) {
                    throw new IllegalStateException("fake source file unavailable: " + file, failure);
                }
                return file;
            }

            static String sourceContent(final String name) {
                return "source-content-" + name;
            }

            private void onEdt() {
                host.onEdt.set(SwingUtilities.isEventDispatchThread());
            }
        }
    }
}
