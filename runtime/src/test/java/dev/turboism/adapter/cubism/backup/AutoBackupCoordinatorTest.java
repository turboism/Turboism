package dev.turboism.adapter.cubism.backup;

import dev.turboism.mapping.verification.AutoBackupVerificationManifest;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.backup.BackupCompletedEvent;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupStatus;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoBackupCoordinatorTest {

    @TempDir
    Path temporary;

    @Test
    void settingsReadsThroughTheVerifiedHostOnTheEdt() {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        EditorAutoBackupSettings settings = service.settings();
        assertTrue(settings.enabled());
        assertEquals(5, settings.intervalMinutes());
        assertEquals(50, settings.maxMB());
        assertEquals(host.backupDir().getPath(), settings.backupDir());
        assertTrue(host.onEdt.get(), "host operations must run on the EDT");
    }

    @Test
    void updateSettingsAppliesAndReadsBackWithoutTouchingTheBackupDir() {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        EditorAutoBackupSettings updated = service.updateSettings(
            new EditorAutoBackupSettings(true, 3, 120, "ignored")
        );
        assertTrue(updated.enabled());
        assertEquals(3, updated.intervalMinutes());
        assertEquals(120, updated.maxMB());
        assertEquals(3, host.manager.interval);
        assertEquals(120, host.manager.maxMB);
        assertEquals(host.backupDir().getPath(), updated.backupDir(), "backupDir is host-read-only");
    }

    @Test
    void updateSettingsShortCircuitsIdenticalValuesWithoutSetterSideEffects() {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        service.updateSettings(new EditorAutoBackupSettings(true, 5, 50, null));
        assertEquals(0, host.manager.setCalls, "no setter side effects for an identical request");
    }

    @Test
    void updateSettingsRollsBackToTheObservedOriginalsWhenAMutationFails() {
        FakeHost host = new FakeHost();
        host.failOnSetInterval = true;
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        assertThrows(RuntimeException.class, () -> service.updateSettings(
            new EditorAutoBackupSettings(false, 9, 80, null)
        ));
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
        assertThrows(RuntimeException.class, () -> service.updateSettings(
            new EditorAutoBackupSettings(false, 9, 80, null)
        ));
        assertFalse(host.manager.restoredVerified, "an unverified rollback must not be claimed");
    }

    @Test
    void failsClosedWhenSelectorsAreAbsentFromTheVerifiedPlan() {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host.resolver(false), 60_000L);
        assertThrows(RuntimeException.class, service::settings);
        assertThrows(RuntimeException.class, () -> service.updateSettings(
            new EditorAutoBackupSettings(true, 3, 120, null)
        ));
        assertThrows(RuntimeException.class, service::statuses);
        CompletionStage<BackupCompletedEvent> stage = service.backupNow();
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> stage.toCompletableFuture().get(10, TimeUnit.SECONDS),
            "backupNow must fail closed without verified selectors"
        );

        CompletionStage<BackupCompletedEvent> afterSave = service.backupAfterSave(snapshot("model.cmo3"));
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> afterSave.toCompletableFuture().get(10, TimeUnit.SECONDS),
            "backupAfterSave must fail closed without verified selectors"
        );
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
        RecordingEventBus bus = new RecordingEventBus();
        AutoBackupCoordinator service = new AutoBackupCoordinator(
            AutoBackupAdapter.connected(host.operations()),
            bus,
            Clock.systemUTC(),
            60_000L
        );

        BackupCompletedEvent event = service.backupNow().toCompletableFuture()
            .get(30, TimeUnit.SECONDS);
        assertEquals(1, event.newBackupFiles().size());
        assertTrue(event.newBackupFiles().get(0).getName().startsWith("model_backup"),
            "artifact must match the <name>_backup<ts>.cmo3 pattern");
        assertTrue(event.newBackupFiles().get(0).length() > 0);
        assertTrue(event.statuses().stream().anyMatch(
            status -> status.lastAutoBackupTimeMillis() > 1_000L),
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
        List<File> received = new CopyOnWriteArrayList<>();
        Registration first = service.registerSyncTarget(files -> {
            received.addAll(files);
            throw new IllegalStateException("target exploded");
        });
        service.registerSyncTarget(files -> {
            received.addAll(files);
            throw new IllegalStateException("second target exploded");
        });

        BackupCompletedEvent event = service.backupNow().toCompletableFuture()
            .get(30, TimeUnit.SECONDS);
        assertEquals(1, event.newBackupFiles().size(), "target failures must not corrupt the result");
        assertEquals(2, received.size(), "every registered target must still be invoked");

        first.close();
        BackupCompletedEvent second = service.backupNow().toCompletableFuture()
            .get(30, TimeUnit.SECONDS);
        assertEquals(1, second.newBackupFiles().size(), "closed registrations are removed");
    }

    @Test
    void backupNowTimesOutAndFailsClosedWhenNoArtifactAppears() {
        FakeHost host = new FakeHost();
        host.produceArtifact = false;
        AutoBackupCoordinator service = coordinator(host, 1_500L);
        CompletionStage<BackupCompletedEvent> stage = service.backupNow();
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> stage.toCompletableFuture().get(30, TimeUnit.SECONDS)
        );
    }

    @Test
    void backupNowFailsClosedWhenTheHostHasNoCompletePack() {
        FakeHost host = new FakeHost();
        host.packPresent = false;
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        CompletionStage<BackupCompletedEvent> stage = service.backupNow();
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> stage.toCompletableFuture().get(30, TimeUnit.SECONDS)
        );
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
    void backupAfterSaveProducesTheArtifactPublishesTheEventAndSyncs() throws Exception {
        FakeHost host = new FakeHost();
        RecordingEventBus bus = new RecordingEventBus();
        AutoBackupCoordinator service = new AutoBackupCoordinator(
            AutoBackupAdapter.connected(host.operations()), bus, Clock.systemUTC(), 60_000L
        );
        List<File> received = new CopyOnWriteArrayList<>();
        service.registerSyncTarget(files -> {
            received.addAll(files);
            throw new IllegalStateException("sync target exploded");
        });
        BackupCompletedEvent event = service.backupAfterSave(snapshot("model.cmo3"))
            .toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(1, event.newBackupFiles().size());
        File artifact = event.newBackupFiles().get(0);
        assertTrue(artifact.getName().startsWith("model_backup"),
            "artifact must match the <name>_backup<ts>.cmo3 pattern");
        assertTrue(artifact.getName().endsWith(".cmo3"));
        assertTrue(artifact.length() > 0);
        assertEquals(host.backupDir, artifact.getParentFile().toPath());
        assertEquals(1, host.saveDocumentCalls);
        assertEquals(1, bus.events.size(), "the completed event must be published");
        assertEquals(List.of(artifact), received, "sync targets must receive the new artifact");
        assertTrue(host.onEdt.get(), "the host saveDocument call must run on the EDT");
    }

    @Test
    void backupAfterSaveCoalescesSavesWithinTheDebounceWindowAndRunsAfterItExpires() throws Exception {
        FakeHost host = new FakeHost();
        MutableClock clock = new MutableClock(1_000_000L);
        AutoBackupCoordinator service = coordinator(host, clock, 60_000L);
        ProjectContentSnapshot saved = snapshot("model.cmo3");
        CompletionStage<BackupCompletedEvent> first = service.backupAfterSave(saved);
        clock.advance(1_000L); // still inside the 2s debounce window
        CompletionStage<BackupCompletedEvent> second = service.backupAfterSave(saved);
        assertSame(first, second, "a save inside the debounce window must coalesce");
        first.toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(1, host.saveDocumentCalls, "one backup for saves inside the window");
        clock.advance(2_000L); // window expired
        CompletionStage<BackupCompletedEvent> third = service.backupAfterSave(saved);
        assertNotSame(first, third, "a save after the window must start a new backup");
        third.toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(2, host.saveDocumentCalls);
    }

    @Test
    void backupAfterSaveSerializesHostSaveDocumentCallsGlobally() throws Exception {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        ProjectContentSnapshot model = snapshot("model.cmo3");
        ProjectContentSnapshot animation = new ProjectContentSnapshot(
            "anim:test", "anim.motion3.json", ProjectContentKind.ANIMATION,
            java.util.Optional.empty(), List.of()
        );
        service.backupAfterSave(model).toCompletableFuture().get(30, TimeUnit.SECONDS);
        service.backupAfterSave(animation).toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(2, host.saveDocumentCalls);
        assertEquals(1, host.maxConcurrentSaveDocument.get(),
            "host saveDocument calls must never run concurrently");
        try (var files = Files.list(host.backupDir)) {
            assertEquals(2, files.filter(path -> path.getFileName().toString().contains("_backup"))
                .count(), "both documents must produce artifacts");
        }
    }

    @Test
    void backupAfterSaveSupportsGameDataDocumentsThroughTheVoidPrimitive() throws Exception {
        FakeHost host = new FakeHost();
        host.pack.fileContents = List.of(new FakeHost.FakeGameDataDocument(host, "game.cmo3", 0L, 0L, false));
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        BackupCompletedEvent event = service.backupAfterSave(snapshot("game.cmo3"))
            .toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(1, event.newBackupFiles().size());
        assertTrue(event.newBackupFiles().get(0).getName().startsWith("game_backup"));
        assertEquals(1, host.saveDocumentCalls);
    }

    @Test
    void backupAfterSaveFailsClosedWhenNoPackContentMatches() throws Exception {
        FakeHost host = new FakeHost();
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        CompletionStage<BackupCompletedEvent> stage = service.backupAfterSave(snapshot("missing.cmo3"));
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> stage.toCompletableFuture().get(30, TimeUnit.SECONDS)
        );
        assertEquals(0, host.saveDocumentCalls, "no host mutation without a match");
        assertEquals(0, host.updateCalls);
    }

    @Test
    void backupAfterSaveFailsClosedWhenTheHostRejectsTheSaveDocument() throws Exception {
        FakeHost host = new FakeHost();
        host.rejectSaveDocument = true;
        AutoBackupCoordinator service = coordinator(host, 60_000L);
        CompletionStage<BackupCompletedEvent> stage = service.backupAfterSave(snapshot("model.cmo3"));
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> stage.toCompletableFuture().get(30, TimeUnit.SECONDS)
        );
        assertEquals(1, host.saveDocumentCalls);
    }

    // ---- helpers ----

    private AutoBackupCoordinator coordinator(final FakeHost host, final long timeout) {
        return coordinator(host.resolver(true), timeout);
    }

    private AutoBackupCoordinator coordinator(final VerifiedMemberResolver resolver, final long timeout) {
        return new AutoBackupCoordinator(
            AutoBackupAdapter.connected(new VerifiedAutoBackupHostOperations(resolver)),
            new RecordingEventBus(),
            Clock.systemUTC(),
            timeout
        );
    }

    private AutoBackupCoordinator coordinator(final FakeHost host, final Clock clock, final long timeout) {
        return new AutoBackupCoordinator(
            AutoBackupAdapter.connected(new VerifiedAutoBackupHostOperations(host.resolver(true))),
            new RecordingEventBus(),
            clock,
            timeout
        );
    }

    private static ProjectContentSnapshot snapshot(final String name) {
        return new ProjectContentSnapshot(
            "model:test", name, ProjectContentKind.MODEL, java.util.Optional.empty(), List.of()
        );
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

    private static final class RecordingEventBus implements EventBus {
        final List<Object> events = new CopyOnWriteArrayList<>();

        @Override
        public <T extends TurboismEvent> Registration subscribe(Class<T> type, Consumer<T> listener) {
            return () -> {
            };
        }

        @Override
        public <T extends TurboismEvent> void publish(T event) {
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
        final java.util.concurrent.atomic.AtomicBoolean onEdt =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean failOnSetInterval;
        boolean failOnRestoreInterval;
        boolean produceArtifact = true;
        boolean packPresent = true;
        int attachCalls;
        int updateCalls;
        Path backupDir;
        boolean rejectSaveDocument;
        int saveDocumentCalls;
        final java.util.concurrent.atomic.AtomicInteger concurrentSaveDocument =
            new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxConcurrentSaveDocument =
            new java.util.concurrent.atomic.AtomicInteger();

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
            pack.fileContents = List.of(
                FakeFileContent.model(this),
                FakeFileContent.animation(this)
            );
        }

        File backupDir() {
            return backupDir.toFile();
        }

        AutoBackupAdapter.HostOperations operations() {
            return new VerifiedAutoBackupHostOperations(resolver(true));
        }

        /** Fake saveDocument primitive: records the call and writes the artifact synchronously. */
        boolean saveDocument(final File target, final FakeFileContent content) {
            onEdt();
            saveDocumentCalls++;
            final int active = concurrentSaveDocument.incrementAndGet();
            try {
                maxConcurrentSaveDocument.accumulateAndGet(active, Math::max);
                if (rejectSaveDocument) {
                    return false;
                }
                try {
                    Files.writeString(target.toPath(), "backup-content-" + target.getName());
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
                return true;
            } finally {
                concurrentSaveDocument.decrementAndGet();
            }
        }

        void onEdt() {
            onEdt.set(SwingUtilities.isEventDispatchThread());
        }

        VerifiedMemberResolver resolver(final boolean typed) {
            List<StaticSelector> selectors = new ArrayList<>();
            String manager = internal(FakeManager.class);
            String app = internal(FakeApp.class);
            String pack = internal(FakePack.class);
            String content = internal(FakeFileContent.class);
            selectors.add(StaticSelector.classSelector("cubism.auto-backup.manager.class", manager));
            selectors.add(StaticSelector.field(
                "cubism.auto-backup.manager.instance", manager, "a", "L" + manager + ";",
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
                "cubism.auto-backup.app-controller.get-complete-pack", app, "getCompletePack",
                "()L" + pack + ";"));
            selectors.add(StaticSelector.classSelector("cubism.auto-backup.complete-pack.class", pack));
            selectors.add(StaticSelector.method(
                "cubism.auto-backup.complete-pack.file-contents", pack, "getAllFileContents",
                "()Ljava/util/List;"));
            selectors.add(StaticSelector.classSelector("cubism.auto-backup.file-content.class", content));
            selectors.add(StaticSelector.method(
                "cubism.auto-backup.file-content.last-auto-backup-time", content,
                "getLastAutoBackupTime", "()J"));
            selectors.add(StaticSelector.method(
                "cubism.auto-backup.file-content.set-last-auto-backup-time", content,
                "setLastAutoBackupTime", "(J)V"));
            selectors.add(StaticSelector.method(
                "cubism.auto-backup.file-content.last-saved-time", content,
                "getLastSavedTime", "()J"));
            selectors.add(StaticSelector.method(
                "cubism.auto-backup.file-content.modified-after-saving", content,
                "isModifiedAfterSaving", "()Z"));
            selectors.add(StaticSelector.method(
                "cubism.auto-backup.file-content.file", content, "getFile", "()Ljava/io/File;"));
            selectors.add(StaticSelector.staticMethod(
                "cubism.auto-backup.app-controller.instance", app, "access$get_instance$cp",
                "()L" + app + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            String modeling = internal(FakeModelingDocument.class);
            String animationContent = internal(FakeAnimationFileContent.class);
            String gameData = internal(FakeGameDataDocument.class);
            selectors.add(StaticSelector.method(
                "cubism.auto-backup.save-document.modeling", modeling, "saveDocument",
                "(Ljava/io/File;Z)Z"));
            selectors.add(StaticSelector.method(
                "cubism.auto-backup.save-document.animation", animationContent, "saveDocument",
                "(Ljava/io/File;Z)Z"));
            selectors.add(StaticSelector.method(
                "cubism.auto-backup.save-document.game-data", gameData, "saveDocument",
                "(Ljava/io/File;)V"));
            if (!typed) {
                selectors.removeIf(selector -> selector.alias().equals("cubism.auto-backup.manager.instance"));
            }
            return TestVerifiedResolvers.create(
                AutoBackupVerificationManifest.ADAPTER_SLICE_ID,
                AutoBackupVerificationManifest.CAPABILITY_IDS,
                selectors,
                FakeHost.class.getClassLoader()
            );
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
                    Thread producer = new Thread(() -> {
                        try {
                            Thread.sleep(100L);
                            File artifact = new File(backupDir, "model_backup2026_08_08_1200.cmo3");
                            Files.writeString(artifact.toPath(), "backup-content");
                            for (Object content : host.pack.fileContents) {
                                ((FakeFileContent) content).lastAutoBackupTime =
                                    Math.max(((FakeFileContent) content).lastAutoBackupTime,
                                        System.currentTimeMillis());
                            }
                        } catch (Exception ignored) {
                            // fail closed in the fake producer
                        }
                    }, "fake-host-producer");
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
            FakeModelingDocument(FakeHost host, String name, long lastAutoBackupTime,
                                 long lastSavedTime, boolean modified) {
                super(host, name, lastAutoBackupTime, lastSavedTime, modified);
            }

            public boolean saveDocument(File target, boolean unused) {
                return host.saveDocument(target, this);
            }
        }

        public static final class FakeAnimationFileContent extends FakeFileContent {
            FakeAnimationFileContent(FakeHost host, String name, long lastAutoBackupTime,
                                     long lastSavedTime, boolean modified) {
                super(host, name, lastAutoBackupTime, lastSavedTime, modified);
            }

            public boolean saveDocument(File target, boolean unused) {
                return host.saveDocument(target, this);
            }
        }

        public static final class FakeGameDataDocument extends FakeFileContent {
            FakeGameDataDocument(FakeHost host, String name, long lastAutoBackupTime,
                                 long lastSavedTime, boolean modified) {
                super(host, name, lastAutoBackupTime, lastSavedTime, modified);
            }

            public void saveDocument(File target) {
                host.saveDocument(target, this);
            }
        }

        public static class FakeFileContent {
            final FakeHost host;
            final String name;
            long lastAutoBackupTime;
            final long lastSavedTime;
            final boolean modified;

            FakeFileContent(FakeHost host, String name, long lastAutoBackupTime, long lastSavedTime,
                            boolean modified) {
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
                return new File(host.backupDir.getParent().toFile(), name);
            }

            private void onEdt() {
                host.onEdt.set(SwingUtilities.isEventDispatchThread());
            }
        }
    }
}
