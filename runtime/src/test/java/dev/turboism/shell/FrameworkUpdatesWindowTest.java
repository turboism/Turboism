package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.distribution.LocalPluginJarPreparer;
import dev.turboism.distribution.PreparedPluginJar;
import dev.turboism.internal.core.CoreFrameworkUpdates;
import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.internal.core.CoreUpdateService;
import dev.turboism.pluginmanagement.CoordinatedPluginUpdateService;
import dev.turboism.pluginmanagement.CoordinatedPluginUpdateService.PluginUpdateRow;
import dev.turboism.pluginmanagement.PendingPluginOperations;
import dev.turboism.pluginmanagement.PluginCatalogClient;
import dev.turboism.pluginmanagement.PluginUpdateGate;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.Registration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.jar.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** UI orchestration with injected services; signed catalog/JAR integration is tested separately. */
class FrameworkUpdatesWindowTest {
    @TempDir Path home;
    private static final String ID = "dev.turboism.plugin.demo";
    private static final PluginLocalization I18N = new PluginLocalization() {
        public Locale locale() { return Locale.ENGLISH; }
        public String text(String key) { return key; }
        public String format(String key, Object... args) { return key + ": " + java.util.Arrays.toString(args); }
        public boolean contains(String key) { return true; }
    };
    private static final PluginUpdateRow ROW = new PluginUpdateRow(ID, "Demo", "1.0.0", "1.1.0", true, true,
            CoordinatedPluginUpdateService.CompatibilityStatus.DUAL_COMPATIBLE, "", List.of(), Optional.empty());

    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(action.call()); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
    private static void await(Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            if (edt(condition)) return;
            Thread.sleep(10);
        }
        fail("Timed out waiting for UI state");
    }

    private final class Fixture implements AutoCloseable {
        final FakeFramework framework = new FakeFramework();
        final FakeDiscovery discovery = new FakeDiscovery(framework);
        final StubPlugins service = new StubPlugins();
        final FrameworkUpdatesWindow window;
        Fixture(Function<String, List<PluginUpdateRow>> planner) throws Exception {
            window = edt(() -> new FrameworkUpdatesWindow(I18N, discovery, new EmptyPlugins(), service, planner));
            edt(() -> { window.show(); return null; });
        }
        void planned() throws Exception { await(() -> window.currentPlanState() == FrameworkUpdatesWindow.PlanState.PLANNED); }
        void download() throws Exception { edt(window::triggerDownload).get(10, TimeUnit.SECONDS); }
        public void close() throws Exception {
            edt(() -> { window.close(); return null; });
            await(() -> !PluginUpdateGate.isHeld(home));
        }
    }

    @Test void failedPlanDoesNotAutomaticallyRetryAndCannotInstall() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (Fixture f = new Fixture(target -> { attempts.incrementAndGet(); throw new IllegalStateException("Bad catalog"); })) {
            await(() -> f.window.currentPlanState() == FrameworkUpdatesWindow.PlanState.PLAN_FAILED);
            for (int i = 0; i < 10; i++) edt(() -> { f.window.render(); return null; });
            assertEquals(1, attempts.get());
            assertFalse(edt(() -> f.window.downloadButton().isEnabled()));
            assertFalse(edt(() -> f.window.installButton().isEnabled()));
            assertTrue(edt(f.window::currentDetailText).contains("Bad catalog"));
            edt(() -> { f.window.checkButton().doClick(); return null; });
            await(() -> attempts.get() == 2 && f.window.currentPlanState() == FrameworkUpdatesWindow.PlanState.PLAN_FAILED);
        }
    }

    @Test void catalogFailureAllowsExplicitFrameworkOnlyChoiceWithoutRetryStorm() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (Fixture f = new Fixture(target -> { attempts.incrementAndGet(); throw new IllegalStateException("Offline catalog"); })) {
            await(() -> f.window.currentPlanState() == FrameworkUpdatesWindow.PlanState.PLAN_FAILED);
            edt(() -> { f.window.includePluginsCheckbox().doClick(); return null; });
            f.planned(); f.download();
            edt(f.window::triggerInstall).get(10, TimeUnit.SECONDS);
            assertEquals(1, attempts.get());
            assertEquals(0, f.service.lastCommitted.size());
            assertEquals(CoreFrameworkUpdates.Phase.APPLY_PENDING, f.framework.snapshot().phase());
        }
    }

    @Test void planningCannotBeBypassedAndFrameworkOnlyModeStillWorks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (Fixture f = new Fixture(target -> {
            entered.countDown();
            try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return List.of();
        })) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            f.framework.state = new CoreFrameworkUpdates.Snapshot(CoreFrameworkUpdates.Phase.STAGED, "0.44.0", 100, 100, "");
            edt(() -> { f.window.render(); return null; });
            assertFalse(edt(() -> f.window.installButton().isEnabled()));
            assertFalse(edt(() -> f.window.downloadButton().isEnabled()));
            finish.countDown(); f.planned(); f.download();
            assertTrue(edt(() -> f.window.installButton().isEnabled()));
            edt(f.window::triggerInstall).get(10, TimeUnit.SECONDS);
            assertEquals(CoreFrameworkUpdates.Phase.APPLY_PENDING, f.framework.snapshot().phase());
        } finally { finish.countDown(); }
    }

    @Test void selectedDownloadDoesNotCommitAndChangingSelectionInvalidatesIt() throws Exception {
        try (Fixture f = new Fixture(target -> List.of(ROW))) {
            f.planned(); f.download();
            assertEquals(0, f.service.commits.get());
            assertTrue(edt(() -> f.window.installButton().isEnabled()), edt(f.window::currentDetailText));
            edt(() -> { f.window.tableModel().setValueAt(false, 0, 0); return null; });
            assertEquals(FrameworkUpdatesWindow.BatchState.IDLE, edt(f.window::currentBatchState));
            assertTrue(edt(f.window::currentDetailText).contains("selection_changed"));
            // Selecting it again requires a new verified batch, not the discarded old file.
            edt(() -> { f.window.tableModel().setValueAt(true, 0, 0); return null; });
            assertFalse(edt(() -> f.window.installButton().isEnabled()));
            f.download(); edt(f.window::triggerInstall).get(10, TimeUnit.SECONDS);
            assertEquals(1, f.service.commits.get());
            assertEquals(1, f.service.lastCommitted.size());
        }
    }

    @Test void cancellingOldDownloadDoesNotReviveItOrCancelNewGeneration() throws Exception {
        try (Fixture f = new Fixture(target -> List.of(ROW))) {
            f.planned();
            CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
            AtomicReference<PluginCatalogClient.DownloadControl> oldControl = new AtomicReference<>();
            f.service.downloadHook = control -> {
                oldControl.set(control); started.countDown();
                try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
            };
            var old = edt(f.window::triggerDownload);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(edt(() -> f.window.tableModel().isCellEditable(0, 0)));
            edt(() -> { f.window.triggerCancel(); return null; });
            assertTrue(oldControl.get().isCancelled());
            f.service.downloadHook = control -> {};
            var next = edt(f.window::triggerDownload);
            finish.countDown();
            assertEquals(FrameworkUpdatesWindow.BatchState.CANCELLED, old.get(10, TimeUnit.SECONDS));
            assertEquals(FrameworkUpdatesWindow.BatchState.PREPARED, next.get(10, TimeUnit.SECONDS));
            assertTrue(edt(() -> f.window.installButton().isEnabled()));
        }
    }

    @Test void anotherOwnerLeasePreventsBothDownloadsAndCommit() throws Exception {
        try (Fixture f = new Fixture(target -> List.of(ROW))) {
            f.planned();
            try (var lease = PluginUpdateGate.acquireLease(home).orElseThrow()) {
                f.download();
                assertEquals(0, f.framework.downloads.get());
                assertEquals(0, f.service.downloads.get());
                assertFalse(edt(() -> f.window.installButton().isEnabled()));
                assertTrue(PluginUpdateGate.isHeld(home));
            }
        }
    }

    @Test void downloadFailureIsVisibleAndRetryable() throws Exception {
        try (Fixture f = new Fixture(target -> List.of(ROW))) {
            f.planned(); f.service.failDownload = true; f.download();
            assertTrue(edt(f.window::currentDetailText).contains("Hash mismatch"));
            assertTrue(edt(() -> f.window.downloadButton().isEnabled()));
            assertFalse(edt(() -> f.window.installButton().isEnabled()));
            f.service.failDownload = false; f.download();
            assertTrue(edt(() -> f.window.installButton().isEnabled()));
        }
    }

    @Test void frameworkQueueFailureKeepsAccuratePartialStatus() throws Exception {
        try (Fixture f = new Fixture(target -> List.of(ROW))) {
            f.planned(); f.download(); f.framework.failQueue = true;
            edt(f.window::triggerInstall).get(10, TimeUnit.SECONDS);
            assertEquals(1, f.service.commits.get());
            assertTrue(edt(f.window::currentDetailText).contains("partial_failure"));
            assertTrue(edt(f.window::currentDetailText).contains("Queue IO failure"));
            edt(() -> { f.window.render(); return null; });
            assertTrue(edt(f.window::currentDetailText).contains("partial_failure"));
        }
    }

    @Test void frameworkOnlyFailureDoesNotClaimAnyPluginsWereQueued() throws Exception {
        try (Fixture f = new Fixture(target -> List.of())) {
            f.planned(); f.download(); f.framework.failQueue = true;
            edt(f.window::triggerInstall).get(10, TimeUnit.SECONDS);
            String message = edt(f.window::currentDetailText);
            assertTrue(message.contains("Queue IO failure"));
            assertFalse(message.contains("partial_failure"));
            assertEquals(0, f.service.lastCommitted.size());
        }
    }

    @Test void closingWhileConsentedCommitRunsDoesNotCancelIt() throws Exception {
        try (Fixture f = new Fixture(target -> List.of(ROW))) {
            f.planned(); f.download();
            CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
            f.service.commitHook = () -> {
                started.countDown();
                try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
            };
            var commit = edt(f.window::triggerInstall);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(edt(() -> f.window.tableModel().isCellEditable(0, 0)));
            assertFalse(edt(() -> f.window.checkButton().isEnabled()));
            assertFalse(edt(() -> f.window.includePluginsCheckbox().isEnabled()));
            edt(() -> { f.window.close(); return null; });
            finish.countDown(); commit.get(10, TimeUnit.SECONDS);
            assertEquals(CoreFrameworkUpdates.Phase.APPLY_PENDING, f.framework.snapshot().phase());
        }
    }

    @Test void aDifferentTargetDiscardsStalePlanningResults() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (Fixture f = new Fixture(target -> {
            if (target.equals("0.44.0")) {
                entered.countDown();
                try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            return List.of(new PluginUpdateRow(ID, target, "1.0.0", "1.1.0", true, true,
                    CoordinatedPluginUpdateService.CompatibilityStatus.DUAL_COMPATIBLE, "", List.of(), Optional.empty()));
        })) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            f.discovery.target = "0.45.0";
            edt(() -> { f.window.render(); return null; });
            finish.countDown(); f.planned();
            assertEquals("0.45.0", edt(() -> f.window.tableModel().rows.get(0).pluginName()));
        } finally { finish.countDown(); }
    }

    @Test void unavailableFrameworkCannotBeActivatedByPluginPlanning() throws Exception {
        try (Fixture f = new Fixture(target -> List.of())) {
            f.planned();
            f.framework.state = new CoreFrameworkUpdates.Snapshot(CoreFrameworkUpdates.Phase.UNAVAILABLE, "", 0, 0, "No signing keys");
            edt(() -> { f.window.render(); return null; });
            assertFalse(edt(() -> f.window.downloadButton().isEnabled()));
            assertFalse(edt(() -> f.window.installButton().isEnabled()));
        }
    }

    private final class StubPlugins extends CoordinatedPluginUpdateService {
        final AtomicInteger downloads = new AtomicInteger(), commits = new AtomicInteger();
        volatile boolean failDownload;
        volatile Consumer<PluginCatalogClient.DownloadControl> downloadHook = control -> {};
        volatile Runnable commitHook = () -> {};
        volatile List<PreparedPluginJar> lastCommitted = List.of();
        StubPlugins() { super(home, new PluginCatalogClient(), new PendingPluginOperations(home),
                FrameworkBuildInfo.Channel.STABLE, Optional::empty, PluginVersion.parse("0.43.10")); }
        @Override public DownloadResult downloadAndPrepare(List<PluginUpdateRow> rows,
                Optional<PluginVersion> target, PluginCatalogClient.DownloadControl control) {
            assertFalse(SwingUtilities.isEventDispatchThread());
            downloads.incrementAndGet(); downloadHook.accept(control);
            if (control.isCancelled()) return DownloadResult.cancelled();
            if (failDownload) return DownloadResult.failure("HASH", "Hash mismatch");
            try { return DownloadResult.success(rows.stream().anyMatch(PluginUpdateRow::selected)
                    ? List.of(prepared()) : List.of()); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public CommitResult commitStagedBatch(List<PreparedPluginJar> batch, PluginVersion target,
                List<CorePluginManagement.PluginInfo> installed) {
            assertFalse(SwingUtilities.isEventDispatchThread());
            commitHook.run(); commits.incrementAndGet(); lastCommitted = List.copyOf(batch);
            return CommitResult.success(batch);
        }
        @Override public void discardUncommitted(List<PreparedPluginJar> files) {
            assertFalse(SwingUtilities.isEventDispatchThread());
            // Stub commit has no journal; real reference-protected cleanup is covered by service integration tests.
        }
    }

    private PreparedPluginJar prepared() throws Exception {
        Path jar = Files.createTempFile(home, "demo-", ".jar");
        String descriptor = "{\"format\":\"turboism.plugin.meta\",\"schemaVersion\":3,\"id\":\"" + ID
                + "\",\"name\":\"Demo\",\"version\":\"1.1.0\",\"description\":\"Test\","
                + "\"entrypoints\":[\"example.Plugin\"],\"turboismApi\":\"[0.43.0,1.0.0)\","
                + "\"authors\":[{\"name\":\"Test\"}],\"license\":\"Project License\",\"website\":\"https://turboism.dev\",\"resources\":[],"
                + "\"i18n\":{\"baseName\":\"META-INF/turboism/i18n/messages\",\"locales\":[]},"
                + "\"dependencies\":[],\"permissions\":[],\"capabilities\":[],"
                + "\"environment\":{\"requiresCubism\":false,\"ui\":\"none\"},\"category\":\"integration\",\"tags\":[]}";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/turboism/plugin.json"));
            out.write(descriptor.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            out.putNextEntry(new JarEntry("example/Plugin.class")); out.write(new byte[]{0}); out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/turboism/i18n/messages.properties"));
            out.write("plugin.name=Demo\n".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        var result = new LocalPluginJarPreparer().prepare(jar, home.resolve("packages"));
        if (result instanceof LocalPluginJarPreparer.PreparationRejected rejected) {
            throw new IllegalStateException("UI fixture rejected: " + rejected.code());
        }
        return ((LocalPluginJarPreparer.Prepared) result).value();
    }

    private static final class FakeDiscovery implements CoreUpdateService {
        final CoreFrameworkUpdates framework;
        volatile String target = "0.44.0";
        FakeDiscovery(CoreFrameworkUpdates f) { framework = f; }
        public Snapshot snapshot() { return new Snapshot(Status.UPDATE_AVAILABLE, "0.43.10", Optional.of(target), OptionalLong.of(1), false, false); }
        public boolean available() { return true; }
        public void start() {}
        public void close() {}
        public Preferences preferences() { return new Preferences(false); }
        public CompletionStage<Snapshot> checkManual() { return CompletableFuture.completedFuture(snapshot()); }
        public PreferenceSaveResult savePreferences(Preferences p) { return PreferenceSaveResult.success(); }
        public CoreFrameworkUpdates frameworkUpdates() { return framework; }
        public Registration subscribe(Consumer<Snapshot> listener) { listener.accept(snapshot()); return () -> {}; }
    }
    private static final class FakeFramework implements CoreFrameworkUpdates {
        volatile Snapshot state = new Snapshot(Phase.IDLE, "", 0, 0, "");
        final AtomicInteger downloads = new AtomicInteger();
        volatile boolean failQueue;
        public Snapshot snapshot() { return state; }
        public CompletionStage<Snapshot> download() {
            downloads.incrementAndGet(); state = new Snapshot(Phase.STAGED, "0.44.0", 100, 100, "");
            return CompletableFuture.completedFuture(state);
        }
        public void cancel() { if (state.phase() == Phase.DOWNLOADING) state = new Snapshot(Phase.CANCELLED, "0.44.0", 0, 0, ""); }
        public CompletionStage<Snapshot> installOnExit() {
            state = new Snapshot(failQueue ? Phase.FAILED : Phase.APPLY_PENDING, "0.44.0", 100, 100,
                    failQueue ? "Queue IO failure" : ""); return CompletableFuture.completedFuture(state);
        }
        public Registration subscribe(Consumer<Snapshot> listener) { listener.accept(state); return () -> {}; }
    }
    private static final class EmptyPlugins implements CorePluginManagement {
        public List<PluginInfo> plugins() { assertFalse(SwingUtilities.isEventDispatchThread()); return List.of(); }
        public OperationResult install() { return OperationResult.accepted("OK", ""); }
        public OperationResult uninstall(String id) { return OperationResult.accepted("OK", ""); }
        public OperationResult setEnabled(String id, boolean enabled) { return OperationResult.accepted("OK", ""); }
    }
}
