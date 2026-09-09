package dev.turboism.userfile;

import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;

import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.failure.RuntimeFailureCollector;
import org.junit.jupiter.api.Test;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class SwingUserFileGrantSourceTest {

    @Test
    void readAndWriteUseTheEdtAndDoNotChangeGlobalUiState() throws Exception {
        final Object originalUiManagerValue = UIManager.get("ClassLoader");
        final RecordingChooser chooser = new RecordingChooser(
            JFileChooser.APPROVE_OPTION,
            Path.of("input.csv").toFile()
        );
        final SwingUserFileGrantSource source = source(chooser);

        final UserFileGrantSource.Selected read = assertInstanceOf(
            UserFileGrantSource.Selected.class,
            await(source.request(request(UserFileMode.READ)))
        );
        assertEquals(Path.of("input.csv"), read.path());
        assertEquals(1, chooser.openCalls.get());
        assertEquals(0, chooser.saveCalls.get());
        assertTrue(chooser.showOnEdt);

        final UserFileGrantSource.Selected write = assertInstanceOf(
            UserFileGrantSource.Selected.class,
            await(source.request(request(UserFileMode.WRITE)))
        );
        assertEquals(Path.of("input.csv"), write.path());
        assertEquals(1, chooser.saveCalls.get());
        assertSame(originalUiManagerValue, UIManager.get("ClassLoader"));
        source.close();
    }

    @Test
    void allowedExtensionsInstallARestrictiveFilterAndCancelIsExplicit() throws Exception {
        final RecordingChooser chooser = new RecordingChooser(JFileChooser.CANCEL_OPTION, null);
        final SwingUserFileGrantSource source = source(chooser);

        assertSame(
            UserFileGrantSource.Canceled.INSTANCE,
            await(source.request(request(UserFileMode.READ)))
        );

        final FileNameExtensionFilter filter = assertInstanceOf(
            FileNameExtensionFilter.class,
            chooser.getFileFilter()
        );
        assertEquals("*.csv, *.json", filter.getDescription());
        assertTrue(filter.accept(new File("input.csv")));
        assertTrue(filter.accept(new File("input.json")));
        assertFalse(filter.accept(new File("input.txt")));
        assertFalse(chooser.isAcceptAllFileFilterUsed());
        source.close();
    }

    @Test
    void errorAndUnknownChooserResultsAreUnavailable() throws Exception {
        final RecordingChooser errorChooser = new RecordingChooser(JFileChooser.ERROR_OPTION, null);
        final SwingUserFileGrantSource errorSource = source(errorChooser);
        assertSame(
            UserFileGrantSource.Unavailable.INSTANCE,
            await(errorSource.request(request(UserFileMode.READ)))
        );
        errorSource.close();

        final RecordingChooser unknownChooser = new RecordingChooser(42, null);
        final SwingUserFileGrantSource unknownSource = source(unknownChooser);
        assertSame(
            UserFileGrantSource.Unavailable.INSTANCE,
            await(unknownSource.request(request(UserFileMode.READ)))
        );
        unknownSource.close();
    }

    @Test
    void oneSourceRejectsAnOverlappingRequestAsUnavailable() throws Exception {
        final QueuedDispatcher dispatcher = new QueuedDispatcher();
        final RecordingChooser chooser = new RecordingChooser(
            JFileChooser.APPROVE_OPTION,
            Path.of("first.csv").toFile()
        );
        final SwingUserFileGrantSource source = new SwingUserFileGrantSource(
            () -> chooser,
            dispatcher
        );

        final CompletionStage<UserFileGrantSource.Decision> first =
            source.request(request(UserFileMode.READ));
        assertSame(
            UserFileGrantSource.Unavailable.INSTANCE,
            await(source.request(request(UserFileMode.READ)))
        );
        dispatcher.runNext();

        assertInstanceOf(UserFileGrantSource.Selected.class, await(first));
        assertEquals(1, chooser.openCalls.get());
        source.close();
    }

    @Test
    void requestFromTheEdtQueuesTheModalShowUntilTheRequestReturns() throws Exception {
        final RecordingChooser chooser = new RecordingChooser(
            JFileChooser.APPROVE_OPTION,
            Path.of("queued.csv").toFile()
        );
        final SwingUserFileGrantSource source = source(chooser);
        final AtomicReference<CompletionStage<UserFileGrantSource.Decision>> stage =
            new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            stage.set(source.request(request(UserFileMode.READ)));
            assertEquals(0, chooser.openCalls.get());
        });

        assertInstanceOf(UserFileGrantSource.Selected.class, await(stage.get()));
        assertEquals(1, chooser.openCalls.get());
        assertTrue(chooser.showOnEdt);
        source.close();
    }

    @Test
    void closeBeforeShowFencesTheQueuedRequestWithoutCreatingAChooser() throws Exception {
        final QueuedDispatcher dispatcher = new QueuedDispatcher();
        final RecordingChooser chooser = new RecordingChooser(
            JFileChooser.APPROVE_OPTION,
            Path.of("never-shown.csv").toFile()
        );
        final SwingUserFileGrantSource source = new SwingUserFileGrantSource(
            () -> chooser,
            dispatcher
        );

        final CompletionStage<UserFileGrantSource.Decision> pending =
            source.request(request(UserFileMode.READ));
        source.close();

        assertSame(UserFileGrantSource.Unavailable.INSTANCE, await(pending));
        dispatcher.runNext();
        assertEquals(0, chooser.openCalls.get());
        assertEquals(0, chooser.saveCalls.get());
    }

    @Test
    void closeDuringConfigureFencesTheRequestBeforeModalShow() throws Exception {
        final AtomicReference<SwingUserFileGrantSource> sourceReference = new AtomicReference<>();
        final RecordingChooser chooser = new RecordingChooser(
            JFileChooser.APPROVE_OPTION,
            Path.of("configured-after-close.csv").toFile()
        );
        chooser.onConfigure = () -> sourceReference.get().close();
        final SwingUserFileGrantSource source = source(chooser);
        sourceReference.set(source);

        assertSame(
            UserFileGrantSource.Unavailable.INSTANCE,
            await(source.request(request(UserFileMode.READ)))
        );
        assertEquals(0, chooser.openCalls.get());
    }

    @Test
    void closeDuringDialogCancelsTheChooserAndFencesLateApproval() throws Exception {
        final AtomicReference<SwingUserFileGrantSource> sourceReference = new AtomicReference<>();
        final RecordingChooser chooser = new RecordingChooser(
            JFileChooser.APPROVE_OPTION,
            Path.of("late.csv").toFile()
        );
        chooser.onShow = () -> sourceReference.get().close();
        final SwingUserFileGrantSource source = source(chooser);
        sourceReference.set(source);

        assertSame(
            UserFileGrantSource.Unavailable.INSTANCE,
            await(source.request(request(UserFileMode.READ)))
        );
        assertTrue(chooser.canceled.await(2, TimeUnit.SECONDS));
        assertEquals(1, chooser.cancelCalls.get());
    }

    @Test
    void chooserAndEdtFailuresFailClosedAsUnavailable() throws Exception {
        final SwingUserFileGrantSource chooserFailure = new SwingUserFileGrantSource(
            () -> {
                throw new IllegalStateException("chooser unavailable");
            },
            SwingUtilities::invokeLater
        );
        assertSame(
            UserFileGrantSource.Unavailable.INSTANCE,
            await(chooserFailure.request(request(UserFileMode.READ)))
        );

        final SwingUserFileGrantSource dispatchFailure = new SwingUserFileGrantSource(
            JFileChooser::new,
            ignored -> {
                throw new IllegalStateException("EDT unavailable");
            }
        );
        assertSame(
            UserFileGrantSource.Unavailable.INSTANCE,
            await(dispatchFailure.request(request(UserFileMode.READ)))
        );
    }

    @Test
    void settleReleasesTheLifecycleLockBeforeCompletionCallbacksRun() throws Exception {
        final QueuedDispatcher dispatcher = new QueuedDispatcher();
        final RecordingChooser chooser = new RecordingChooser(
            JFileChooser.APPROVE_OPTION,
            Path.of("linearized.csv").toFile()
        );
        final SwingUserFileGrantSource source = new SwingUserFileGrantSource(
            () -> chooser,
            dispatcher
        );
        final CompletionStage<UserFileGrantSource.Decision> first =
            source.request(request(UserFileMode.READ));
        final AtomicReference<CompletionStage<UserFileGrantSource.Decision>> next =
            new AtomicReference<>();
        final AtomicReference<Boolean> acquiredBeforeCallbackReturned =
            new AtomicReference<>(false);
        final CountDownLatch competitorFinished = new CountDownLatch(1);
        first.whenComplete((ignored, failure) -> {
            final Thread competitor = new Thread(() -> {
                try {
                    next.set(source.request(request(UserFileMode.READ)));
                } finally {
                    competitorFinished.countDown();
                }
            }, "swing-grant-source-linearization-test");
            competitor.start();
            try {
                acquiredBeforeCallbackReturned.set(
                    competitorFinished.await(1, TimeUnit.SECONDS)
                );
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        dispatcher.runNext();

        assertTrue(acquiredBeforeCallbackReturned.get());
        assertTrue(competitorFinished.await(2, TimeUnit.SECONDS));
        assertNotSame(UserFileGrantSource.Unavailable.INSTANCE, next.get());
        source.close();
    }

    @Test
    void cancelDispatchFailureIsReportedAsCleanupFailure() throws Exception {
        final BlockingChooser chooser = new BlockingChooser(Path.of("dispatch-failure.csv").toFile());
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final AtomicInteger dispatches = new AtomicInteger();
        final AtomicReference<Thread> showThread = new AtomicReference<>();
        final SwingUserFileGrantSource source = new SwingUserFileGrantSource(
            "test.plugin",
            failures,
            evidence,
            () -> chooser,
            action -> {
                if (dispatches.getAndIncrement() == 0) {
                    final Thread thread = new Thread(action, "swing-grant-source-dispatch-test");
                    showThread.set(thread);
                    thread.start();
                } else {
                    throw new IllegalStateException("cancel dispatch failed");
                }
            }
        );
        final CompletionStage<UserFileGrantSource.Decision> pending =
            source.request(request(UserFileMode.READ));
        assertTrue(chooser.entered.await(2, TimeUnit.SECONDS));

        source.close();
        try {
            assertSame(UserFileGrantSource.Unavailable.INSTANCE, await(pending));
            assertEquals(0, chooser.cancelCalls.get());
            assertFailure(failures, evidence, "USER_FILE_CHOOSER_CANCEL_DISPATCH_FAILED");
        } finally {
            chooser.release.countDown();
            showThread.get().join(2_000);
        }
        assertTrue(showThread.get() != null && !showThread.get().isAlive());
    }

    @Test
    void cancelSelectionFailureIsReportedAsCleanupFailure() throws Exception {
        final BlockingChooser chooser = new BlockingChooser(Path.of("cancel-failure.csv").toFile());
        chooser.cancelFailure = new IllegalStateException("cancel failed");
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final AtomicInteger dispatches = new AtomicInteger();
        final AtomicReference<Thread> showThread = new AtomicReference<>();
        final SwingUserFileGrantSource source = new SwingUserFileGrantSource(
            "test.plugin",
            failures,
            evidence,
            () -> chooser,
            action -> {
                if (dispatches.getAndIncrement() == 0) {
                    final Thread thread = new Thread(action, "swing-grant-source-cancel-test");
                    showThread.set(thread);
                    thread.start();
                } else {
                    action.run();
                }
            }
        );
        final CompletionStage<UserFileGrantSource.Decision> pending =
            source.request(request(UserFileMode.READ));
        assertTrue(chooser.entered.await(2, TimeUnit.SECONDS));

        source.close();
        try {
            assertSame(UserFileGrantSource.Unavailable.INSTANCE, await(pending));
            assertEquals(1, chooser.cancelCalls.get());
            assertFailure(failures, evidence, "USER_FILE_CHOOSER_CANCEL_FAILED");
        } finally {
            chooser.release.countDown();
            showThread.get().join(2_000);
        }
        assertTrue(showThread.get() != null && !showThread.get().isAlive());
    }

    private static SwingUserFileGrantSource source(final RecordingChooser chooser) {
        return new SwingUserFileGrantSource(() -> chooser, SwingUtilities::invokeLater);
    }

    private static UserFileRequest request(final UserFileMode mode) {
        return new UserFileRequest(
            "chooser-" + mode.name().toLowerCase(),
            "Choose data file",
            List.of("csv", "json"),
            mode,
            UserFileLifetime.ONE_OPERATION
        );
    }

    private static UserFileGrantSource.Decision await(
        final CompletionStage<UserFileGrantSource.Decision> stage
    ) throws Exception {
        return stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static void assertFailure(
        final RuntimeFailureCollector failures,
        final CleanupEvidenceCollector evidence,
        final String code
    ) {
        final var collected = failures.snapshot().storageFailures();
        assertEquals(1, collected.size());
        assertEquals(code, collected.get(0).code());
        assertEquals(null, collected.get(0).relativePath());
        assertEquals(1, evidence.snapshot().failures());
    }

    private static final class BlockingChooser extends JFileChooser {
        private final File selected;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private volatile RuntimeException cancelFailure;

        private BlockingChooser(final File selected) {
            this.selected = selected;
        }

        @Override
        public int showOpenDialog(final Component parent) {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return JFileChooser.APPROVE_OPTION;
        }

        @Override
        public File getSelectedFile() {
            return selected;
        }

        @Override
        public void cancelSelection() {
            cancelCalls.incrementAndGet();
            if (cancelFailure != null) {
                throw cancelFailure;
            }
        }
    }

    private static final class RecordingChooser extends JFileChooser {
        private final int result;
        private final File selected;
        private final AtomicInteger openCalls = new AtomicInteger();
        private final AtomicInteger saveCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final CountDownLatch canceled = new CountDownLatch(1);
        private volatile boolean showOnEdt;
        private Runnable onShow = () -> { };
        private Runnable onConfigure = () -> {};

        private RecordingChooser(final int result, final File selected) {
            this.result = result;
            this.selected = selected;
        }

        @Override
        public void setDialogTitle(final String title) {
            if (onConfigure != null) {
                onConfigure.run();
            }
            super.setDialogTitle(title);
        }

        @Override
        public int showOpenDialog(final Component parent) {
            showOnEdt = SwingUtilities.isEventDispatchThread();
            openCalls.incrementAndGet();
            onShow.run();
            return result;
        }

        @Override
        public int showSaveDialog(final Component parent) {
            showOnEdt = SwingUtilities.isEventDispatchThread();
            saveCalls.incrementAndGet();
            onShow.run();
            return result;
        }

        @Override
        public File getSelectedFile() {
            return selected;
        }

        @Override
        public void cancelSelection() {
            cancelCalls.incrementAndGet();
            canceled.countDown();
        }
    }

    private static final class QueuedDispatcher implements SwingUserFileGrantSource.EdtDispatcher {
        private final Deque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void dispatch(final Runnable action) {
            queued.addLast(action);
        }

        private void runNext() {
            final Runnable action = queued.pollFirst();
            assertTrue(action != null, "expected one queued EDT action");
            action.run();
        }
    }
}
