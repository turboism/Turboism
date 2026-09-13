package dev.turboism.validation.atlasimage;
import java.awt.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

/** Offline configuration/admission checks; never adds official Cubism bytes to a test class path. */
public final class AtlasImageProbeConfigurationSelfCheck {
    private static int checks;

    private AtlasImageProbeConfigurationSelfCheck() {}

    public static void main(final String[] args) throws Exception {
        final Path root = Files.createTempDirectory("atlas-image-config-selfcheck-");
        try {
            checkPathAdmission(root);
            checkPropertiesCompatibility(root);
            checkNamedProperties(root);
            checkDriverStateMachine();
            checkActionFailureBarrier();
            checkEdtInvocationGate();
            checkEditorMenuDispatch(root);
            checkMainLookupStartup(root);
            checkStageEvidence(root);
            System.out.println("ATLAS_IMAGE_CONFIGURATION_SELFCHECK PASS checks=" + checks
                + " hostExecuted=false");
        } finally {
            deleteTree(root);
        }
    }

    private static void checkPathAdmission(final Path root) throws Exception {
        expectFailure(() -> AtlasImageLoadProbeAgent.findUniqueOfficialJar(""), "missing class path");
        final Path missing = root.resolve("missing").resolve("Live2D_Cubism.jar");
        expectFailure(() -> AtlasImageLoadProbeAgent.findUniqueOfficialJar(missing.toString()),
            "missing official jar");

        final Path first = root.resolve("one").resolve("Live2D_Cubism.jar");
        final Path second = root.resolve("two").resolve("Live2D_Cubism.jar");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, "one");
        Files.writeString(second, "two");
        final String duplicateClassPath = first + File.pathSeparator + second;
        expectFailure(() -> AtlasImageLoadProbeAgent.findUniqueOfficialJar(duplicateClassPath),
            "duplicate official jars");

        final Path wrongPath = root.resolve("not-the-official-name.jar");
        Files.writeString(wrongPath, "wrong");
        final Properties wrongPathProperties = properties(wrongPath, root.resolve("wrong-path"), "wrong-path-001");
        expectFailure(() -> AtlasImageLoadProbeAgent.ProbeConfig.fromProperties(wrongPathProperties)
            .verifyArtifact(), "wrong official jar path");

        final Path wrongHash = root.resolve("wrong-hash").resolve("Live2D_Cubism.jar");
        Files.createDirectories(wrongHash.getParent());
        Files.writeString(wrongHash, "not the reviewed 5303 jar");
        final Properties wrongHashProperties = properties(wrongHash, root.resolve("wrong-hash-output"),
            "wrong-hash-001");
        expectFailure(() -> AtlasImageLoadProbeAgent.ProbeConfig.fromProperties(wrongHashProperties)
            .verifyArtifact(), "wrong official jar hash");

        expectFailure(() -> AtlasImageLoadProbeAgent.validTaskId("../escape"), "path traversal task id");
    }

    private static void checkPropertiesCompatibility(final Path root) throws Exception {
        final Path artifact = root.resolve("properties").resolve("Live2D_Cubism.jar");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "properties compatibility fake");
        final Path output = root.resolve("properties-output");
        final AtlasImageLoadProbeAgent.ProbeConfig config =
            AtlasImageLoadProbeAgent.ProbeConfig.fromProperties(
                properties(artifact, output, "properties-run-001"));
        final Path claimed = config.claimRun();
        check(claimed.equals(output.resolve("properties-run-001")), "properties path entry remains supported");
        expectFailure(config::claimRun, "properties duplicate run id");
    }

    private static void checkNamedProperties(final Path root) throws Exception {
        final Path home = root.resolve("named-home");
        Files.createDirectory(home);
        final Path artifact = root.resolve("named").resolve("Live2D_Cubism.jar");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "named fake; identity verification is a separate check");

        final String[] keys = {
            "turboism.home", "turboism.validation.runId", "turboism.validation.hostVersion",
            AtlasImageLoadProbeAgent.NAMED_PREFIX + "home",
            AtlasImageLoadProbeAgent.NAMED_PREFIX + "taskId",
            AtlasImageLoadProbeAgent.NAMED_PREFIX + "version",
            AtlasImageLoadProbeAgent.NAMED_PREFIX + "fixture",
            AtlasImageLoadProbeAgent.NAMED_PREFIX + "fixtureName",
            AtlasImageLoadProbeAgent.NAMED_PREFIX + "outputRelative",
            "java.class.path"
        };
        final Properties saved = new Properties();
        for (String key : keys) {
            final String old = System.getProperty(key);
            if (old != null) saved.setProperty(key, old);
        }
        final String runId = "named-run-001";
        try {
            System.setProperty("turboism.home", home.toString());
            System.setProperty("turboism.validation.runId", runId);
            System.setProperty("turboism.validation.hostVersion", "5303");
            System.setProperty(AtlasImageLoadProbeAgent.NAMED_PREFIX + "home", home.toString());
            System.setProperty(AtlasImageLoadProbeAgent.NAMED_PREFIX + "taskId", runId);
            System.setProperty(AtlasImageLoadProbeAgent.NAMED_PREFIX + "version", "5303");
            System.setProperty(AtlasImageLoadProbeAgent.NAMED_PREFIX + "fixture",
                "/offline/input/atlas_mapping_100.cmo3");
            System.setProperty(AtlasImageLoadProbeAgent.NAMED_PREFIX + "fixtureName",
                runId + "-atlas_mapping_100.cmo3");
            expectFailure(AtlasImageLoadProbeAgent.ProbeConfig::fromSystemProperties,
                "named source fixture basename mismatch");
            System.setProperty(AtlasImageLoadProbeAgent.NAMED_PREFIX + "fixture",
                "/offline/input/" + runId + "-atlas_mapping_100.cmo3");
            System.setProperty(AtlasImageLoadProbeAgent.NAMED_PREFIX + "outputRelative",
                AtlasImageLoadProbeAgent.OUTPUT_RELATIVE);
            System.setProperty("java.class.path", artifact.toString());

            final AtlasImageLoadProbeAgent.ProbeConfig config =
                AtlasImageLoadProbeAgent.ProbeConfig.fromSystemProperties();
            final Path claimed = config.claimRun();
            check(claimed.equals(home.resolve(AtlasImageLoadProbeAgent.OUTPUT_RELATIVE)
                .resolve(runId)), "named no-arg entry uses explicit home and run id");
            expectFailure(config::claimRun, "named duplicate run id");

            System.clearProperty(AtlasImageLoadProbeAgent.NAMED_PREFIX + "fixtureName");
            expectFailure(AtlasImageLoadProbeAgent.ProbeConfig::fromSystemProperties,
                "named entry missing required property");
        } finally {
            for (String key : keys) {
                System.clearProperty(key);
                final String old = saved.getProperty(key);
                if (old != null) System.setProperty(key, old);
            }
        }
    }

    private static void checkDriverStateMachine() {
        final SceneDriverState state = new SceneDriverState();
        expectFailure(() -> state.transition(SceneDriverState.Stage.INITIAL,
            SceneDriverState.Stage.FINISH_REQUESTED), "finish before Cancel");
        expectFailure(() -> state.transition(SceneDriverState.Stage.INITIAL,
            SceneDriverState.Stage.EXIT_REQUESTED), "exit before result");
        state.transition(SceneDriverState.Stage.INITIAL, SceneDriverState.Stage.MAIN_IDENTIFIED);
        state.transition(SceneDriverState.Stage.MAIN_IDENTIFIED, SceneDriverState.Stage.EDIT_REQUESTED);
        state.transition(SceneDriverState.Stage.EDIT_REQUESTED, SceneDriverState.Stage.EDITOR_CONFIRMED);
        state.transition(SceneDriverState.Stage.EDITOR_CONFIRMED, SceneDriverState.Stage.CANCELLED);
        state.transition(SceneDriverState.Stage.CANCELLED, SceneDriverState.Stage.FINISH_REQUESTED);
        state.transition(SceneDriverState.Stage.FINISH_REQUESTED, SceneDriverState.Stage.OBSERVER_PERSISTED);
        expectFailure(() -> state.transition(SceneDriverState.Stage.OBSERVER_PERSISTED,
            SceneDriverState.Stage.RESULT_WRITTEN), "result before native exit");
        state.transition(SceneDriverState.Stage.OBSERVER_PERSISTED, SceneDriverState.Stage.EXIT_REQUESTED);
        state.transition(SceneDriverState.Stage.EXIT_REQUESTED, SceneDriverState.Stage.RESULT_WRITTEN);
        check(state.stage() == SceneDriverState.Stage.RESULT_WRITTEN, "native exit before result publication");

        final Properties missing = new Properties();
        check(!AtlasImageSceneDriverAgent.observerResultIsPass(missing), "missing observer result is not pass");
        final Properties blocked = new Properties();
        blocked.setProperty("status", "BLOCKED");
        blocked.setProperty("actual.requiredTargetObserved", "true");
        blocked.setProperty("actual.conflictOrOverflow", "false");
        blocked.setProperty("guardInstalled", "false");
        blocked.setProperty("optimizationReadiness", "NOT_EVALUATED");
        check(!AtlasImageSceneDriverAgent.observerResultIsPass(blocked), "non-PASS observer is not pass");
        final Properties pass = new Properties();
        pass.setProperty("status", "PASS");
        pass.setProperty("actual.requiredTargetObserved", "true");
        pass.setProperty("actual.conflictOrOverflow", "false");
        pass.setProperty("guardInstalled", "false");
        pass.setProperty("optimizationReadiness", "NOT_EVALUATED");
        check(AtlasImageSceneDriverAgent.observerResultIsPass(pass), "only complete observer PASS qualifies");

        check(AtlasImageSceneDriverAgent.isReviewedEditorClassName(
            "com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b"), "exact editor window class accepted");
        check(!AtlasImageSceneDriverAgent.isReviewedEditorClassName(
            "com.live2d.cubism.doc.modeling.ui.atlasEditor.a.f"), "settings dialog class rejected");
        check(!AtlasImageSceneDriverAgent.isReviewedEditorClassName(
            "com.live2d.cubism.doc.modeling.ui.atlasEditor.f"), "editor controller class rejected");

    }
    private static void checkActionFailureBarrier() throws Exception {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<String> published = new AtomicReference<>();
        final Thread action = new Thread(() -> {
            failure.set(new IllegalStateException("synthetic Cancel action failure"));
            completed.countDown();
        }, "atlas-image-cancel-failure-selfcheck");
        action.start();
        action.join();
        boolean rejected = false;
        try {
            AtlasImageSceneDriverAgent.awaitActionCompletion(completed, failure, "editor Cancel action");
            published.set("PASS");
        } catch (Exception expected) {
            rejected = true;
            checks++;
        }
        check(rejected, "Cancel action exception rejected");
        check(published.get() == null, "Cancel action exception prevented PASS publication");
        check(completed.getCount() == 0, "Cancel action completion latch observed");
    }

    private static void checkEdtInvocationGate() throws Exception {
        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        check(blockerStarted.await(1, TimeUnit.SECONDS), "EDT blocker started");
        try {
            final AtomicReference<Boolean> lateActionRan = new AtomicReference<>(false);
            final AtlasImageSceneDriverAgent.FixedEdtInvocation<Void> queued =
                new AtlasImageSceneDriverAgent.FixedEdtInvocation<>(() -> {
                    lateActionRan.set(true);
                    return null;
                });
            enqueue(queued);
            check(queued.timeoutIfQueued()
                == AtlasImageSceneDriverAgent.EdtInvocationState.TIMED_OUT,
                "queued EDT timeout recorded");
            releaseBlocker.countDown();
            check(queued.await(1), "queued timeout callback drained");
            check(!lateActionRan.get(), "timed-out queued action stayed inert");
            check(queued.state() == AtlasImageSceneDriverAgent.EdtInvocationState.TIMED_OUT,
                "queued callback remained timed out");

            final CountDownLatch actionStarted = new CountDownLatch(1);
            final CountDownLatch releaseAction = new CountDownLatch(1);
            final AtlasImageSceneDriverAgent.FixedEdtInvocation<String> started =
                new AtlasImageSceneDriverAgent.FixedEdtInvocation<>(() -> {
                    actionStarted.countDown();
                    releaseAction.await(2, TimeUnit.SECONDS);
                    return "late";
                });
            enqueue(started);
            check(actionStarted.await(1, TimeUnit.SECONDS), "started EDT action entered");
            check(started.timeoutIfQueued() == AtlasImageSceneDriverAgent.EdtInvocationState.STARTED,
                "started EDT timeout is not cancellable");
            final AtomicReference<Throwable> finalFailure = new AtomicReference<>();
            final AtomicReference<String> canonical = new AtomicReference<>();
            finalFailure.set(new IllegalStateException("synthetic started-action timeout"));
            releaseAction.countDown();
            check(started.await(1), "started late callback drained");
            check(started.state() == AtlasImageSceneDriverAgent.EdtInvocationState.COMPLETED,
                "started action eventually completed");
            check("late".equals(started.value()), "started action return retained for diagnosis");
            check(finalFailure.get() != null && canonical.get() == null,
                "late completion did not overwrite recorded FAIL");

            final AtlasImageSceneDriverAgent.FixedEdtInvocation<String> normal =
                new AtlasImageSceneDriverAgent.FixedEdtInvocation<>(() -> "normal");
            enqueue(normal);
            check(normal.await(1), "normal EDT action completed");
            check(normal.state() == AtlasImageSceneDriverAgent.EdtInvocationState.COMPLETED
                && "normal".equals(normal.value()), "normal EDT action retained result");

            final AtlasImageSceneDriverAgent.FixedEdtInvocation<Void> exceptional =
                new AtlasImageSceneDriverAgent.FixedEdtInvocation<>(() -> {
                    throw new IllegalStateException("synthetic EDT action failure");
                });
            enqueue(exceptional);
            check(exceptional.await(1), "exceptional EDT action completed");
            check(exceptional.state() == AtlasImageSceneDriverAgent.EdtInvocationState.COMPLETED
                && exceptional.failure() != null, "EDT action exception propagated");
            System.out.println("ATLAS_IMAGE_EDT_GATE_SELFCHECK PASS");
        } finally {
            releaseBlocker.countDown();
        }
    }

    private static void checkEditorMenuDispatch(final Path root) throws Exception {
        final AtomicInteger blockedClicks = new AtomicInteger();
        final JFrame blockedFrame = menuFrame("editor-dispatch-blocked", blockedClicks);
        showFrame(blockedFrame);
        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        blockEdt(blockerStarted, releaseBlocker);
        check(blockerStarted.await(1, TimeUnit.SECONDS), "editor dispatch EDT blocker started");
        final AtomicReference<Throwable> blockedDispatchFailure = new AtomicReference<>();
        final AtomicReference<Throwable> blockedActionFailure = new AtomicReference<>();
        final AtomicBoolean blockedClickStarted = new AtomicBoolean();
        final CountDownLatch blockedActionFinished = new CountDownLatch(1);
        final AtlasImageSceneDriverAgent.EditorMenuDispatch blockedDispatch =
            new AtlasImageSceneDriverAgent.EditorMenuDispatch(blockedFrame, blockedDispatchFailure,
                blockedActionFailure, blockedClickStarted, blockedActionFinished, null);
        final long blockedStartedNanos = System.nanoTime();
        boolean timedOut = false;
        try {
            try {
                blockedDispatch.dispatch();
                throw new AssertionError("blocked editor dispatch unexpectedly returned");
            } catch (AtlasImageSceneDriverAgent.EdtTimeoutException expected) {
                timedOut = true;
                check(expected.operation == AtlasImageSceneDriverAgent.EdtOperation.EDITOR_MENU_DISPATCH,
                    "blocked editor dispatch operation recorded");
                check(expected.state == AtlasImageSceneDriverAgent.EdtInvocationState.TIMED_OUT,
                    "blocked editor dispatch queued timeout recorded");
            }
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - blockedStartedNanos) >= 4500L,
                "blocked editor dispatch honored the five-second budget");
        } finally {
            releaseBlocker.countDown();
        }
        check(timedOut, "blocked editor dispatch failed");
        check(blockedActionFinished.await(1, TimeUnit.SECONDS),
            "timed-out editor callback drained after EDT release");
        check(!blockedClickStarted.get() && blockedClicks.get() == 0,
            "timed-out editor callback did not click");
        check(MenuSelectionManager.defaultManager().getSelectedPath().length == 0,
            "timed-out editor callback did not select a menu path");
        check(blockedDispatchFailure.get() == null && blockedActionFailure.get() == null,
            "queued editor timeout did not fabricate callback failure");
        disposeFrame(blockedFrame);

        final AtomicInteger interruptedClicks = new AtomicInteger();
        final JFrame interruptedFrame = menuFrame("editor-dispatch-interrupted", interruptedClicks);
        showFrame(interruptedFrame);
        final CountDownLatch interruptedBlockerStarted = new CountDownLatch(1);
        final CountDownLatch releaseInterruptedBlocker = new CountDownLatch(1);
        blockEdt(interruptedBlockerStarted, releaseInterruptedBlocker);
        check(interruptedBlockerStarted.await(1, TimeUnit.SECONDS),
            "interrupted editor dispatch EDT blocker started");
        final Path interruptedStagePath = root.resolve("editor-interrupted")
            .resolve("driver-stage.properties");
        final AtlasImageSceneDriverAgent.StageEvidence interruptedEvidence =
            new AtlasImageSceneDriverAgent.StageEvidence(interruptedStagePath);
        final AtomicBoolean interruptedClickStarted = new AtomicBoolean();
        final CountDownLatch interruptedActionFinished = new CountDownLatch(1);
        final AtomicReference<Throwable> interruptedFailure = new AtomicReference<>();
        final Thread interruptedDispatch = new Thread(() -> {
            try {
                new AtlasImageSceneDriverAgent.EditorMenuDispatch(interruptedFrame,
                    new AtomicReference<>(), new AtomicReference<>(), interruptedClickStarted,
                    interruptedActionFinished, interruptedEvidence).dispatch();
            } catch (Throwable failure) {
                interruptedFailure.set(failure);
            }
        }, "atlas-image-editor-dispatch-interrupted-selfcheck");
        try {
            interruptedDispatch.start();
            Thread.sleep(100L);
            check(interruptedDispatch.isAlive(), "interrupted editor dispatch was waiting");
            interruptedDispatch.interrupt();
            interruptedDispatch.join(1000L);
            check(!interruptedDispatch.isAlive(), "interrupted editor dispatch returned promptly");
            check(interruptedFailure.get() instanceof IllegalStateException,
                "interrupted editor dispatch failed");
        } finally {
            releaseInterruptedBlocker.countDown();
            interruptedDispatch.join(1000L);
        }
        check(interruptedActionFinished.await(1, TimeUnit.SECONDS),
            "interrupted editor callback drained after EDT release");
        check(!interruptedClickStarted.get() && interruptedClicks.get() == 0,
            "interrupted editor callback did not click");
        check(MenuSelectionManager.defaultManager().getSelectedPath().length == 0,
            "interrupted editor callback did not select a menu path");
        final Properties interruptedStage = loadProperties(interruptedStagePath);
        check("TIMED_OUT".equals(interruptedStage.getProperty("onEdt.lastState")),
            "interrupted queued editor dispatch is timed out");
        check("LATE_CALLBACK_SKIPPED".equals(interruptedStage.getProperty("onEdt.lastOutcome")),
            "interrupted queued editor callback was skipped");
        check("1".equals(interruptedStage.getProperty("onEdt.lateSkipped")),
            "interrupted queued editor callback skip counted");
        disposeFrame(interruptedFrame);
        System.out.println("ATLAS_IMAGE_EDITOR_DISPATCH_SELFCHECK PASS blocked-and-interrupted=true");
    }

    private static void checkMainLookupStartup(final Path root) throws Exception {
        final String fixtureName = "startup-atlas_mapping_100.cmo3";
        final AtomicInteger retryClicks = new AtomicInteger();
        final JFrame retryFrame = menuFrame(fixtureName, retryClicks);
        final CountDownLatch retryBlockerStarted = new CountDownLatch(1);
        final CountDownLatch releaseRetryBlocker = new CountDownLatch(1);
        final Path retryStagePath = root.resolve("main-lookup-retry").resolve("driver-stage.properties");
        final AtlasImageSceneDriverAgent.StageEvidence retryEvidence =
            new AtlasImageSceneDriverAgent.StageEvidence(retryStagePath);
        try {
            showFrame(retryFrame);
            blockEdt(retryBlockerStarted, releaseRetryBlocker);
            check(retryBlockerStarted.await(1, TimeUnit.SECONDS),
                "main lookup retry EDT blocker started");
            final Thread releaseAfterTimeout = new Thread(() -> {
                try {
                    Thread.sleep(5600L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    releaseRetryBlocker.countDown();
                }
            }, "atlas-image-main-lookup-release-selfcheck");
            releaseAfterTimeout.start();
            final Window found;
            try {
                found = AtlasImageSceneDriverAgent.waitForMain(fixtureName,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(15L), retryEvidence);
            } finally {
                releaseRetryBlocker.countDown();
                releaseAfterTimeout.join(1000L);
            }
            check(found == retryFrame, "main lookup retry identified the exact fixture window");
            final Properties retryStage = loadProperties(retryStagePath);
            check("120".equals(retryStage.getProperty("startupBudgetSeconds")),
                "main lookup startup budget is explicit");
            check("2".equals(retryStage.getProperty("onEdt.queued"))
                    && "1".equals(retryStage.getProperty("onEdt.started"))
                    && "1".equals(retryStage.getProperty("onEdt.completed")),
                "main lookup retry ran one replacement read only");
            check("1".equals(retryStage.getProperty("onEdt.timedOut"))
                    && "1".equals(retryStage.getProperty("onEdt.lateSkipped"))
                    && "1".equals(retryStage.getProperty("mainLookupRetryCount"))
                    && "false".equals(retryStage.getProperty("startedAtTimeout")),
                "main lookup queued timeout was invalidated before retry");
            check(retryClicks.get() == 0
                    && MenuSelectionManager.defaultManager().getSelectedPath().length == 0,
                "main lookup retry did not trigger UI actions");
        } finally {
            releaseRetryBlocker.countDown();
            disposeFrame(retryFrame);
        }

        final AtomicInteger expiredClicks = new AtomicInteger();
        final JFrame expiredFrame = menuFrame("expired-atlas_mapping_100.cmo3", expiredClicks);
        final CountDownLatch expiredBlockerStarted = new CountDownLatch(1);
        final CountDownLatch releaseExpiredBlocker = new CountDownLatch(1);
        final Path expiredStagePath = root.resolve("main-lookup-expired")
            .resolve("driver-stage.properties");
        final AtlasImageSceneDriverAgent.StageEvidence expiredEvidence =
            new AtlasImageSceneDriverAgent.StageEvidence(expiredStagePath);
        try {
            showFrame(expiredFrame);
            blockEdt(expiredBlockerStarted, releaseExpiredBlocker);
            check(expiredBlockerStarted.await(1, TimeUnit.SECONDS),
                "main lookup deadline EDT blocker started");
            boolean failed = false;
            try {
                AtlasImageSceneDriverAgent.waitForMain("expired-atlas_mapping_100.cmo3",
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(4900L), expiredEvidence);
            } catch (IllegalStateException expected) {
                failed = true;
                check(expected.getCause() instanceof AtlasImageSceneDriverAgent.EdtTimeoutException,
                    "main lookup deadline retains timeout cause");
            } finally {
                releaseExpiredBlocker.countDown();
            }
            check(failed, "main lookup startup deadline still fails closed");
            final Properties expiredStage = waitForProperty(expiredStagePath,
                "onEdt.lateSkipped", "1");
            check("1".equals(expiredStage.getProperty("onEdt.queued"))
                    && "0".equals(expiredStage.getProperty("onEdt.started"))
                    && "0".equals(expiredStage.getProperty("onEdt.completed"))
                    && "1".equals(expiredStage.getProperty("onEdt.lateSkipped"))
                    && "0".equals(expiredStage.getProperty("mainLookupRetryCount")),
                "expired startup deadline did not retry a queued lookup");
            check(expiredClicks.get() == 0
                    && MenuSelectionManager.defaultManager().getSelectedPath().length == 0,
                "expired main lookup had no UI side effect");
        } finally {
            releaseExpiredBlocker.countDown();
            disposeFrame(expiredFrame);
        }
        System.out.println("ATLAS_IMAGE_MAIN_LOOKUP_SELFCHECK PASS queued-retry-only=true startup-budget=120");
    }

    private static void checkStageEvidence(final Path root) throws Exception {
        final Path target = root.resolve("stage-evidence").resolve("driver-stage.properties");
        final AtlasImageSceneDriverAgent.StageEvidence evidence =
            new AtlasImageSceneDriverAgent.StageEvidence(target);
        evidence.cancelDispatchStarted();
        evidence.onEdtQueued(AtlasImageSceneDriverAgent.EdtOperation.MAIN_LOOKUP);
        evidence.onEdtStarted(AtlasImageSceneDriverAgent.EdtOperation.MAIN_LOOKUP);
        evidence.onEdtEnded(AtlasImageSceneDriverAgent.EdtOperation.MAIN_LOOKUP,
            AtlasImageSceneDriverAgent.EdtInvocationState.COMPLETED, "COMPLETED", 3L);
        evidence.cancelDispatchStarted();
        evidence.onEdtQueued(AtlasImageSceneDriverAgent.EdtOperation.CANCEL_BUTTON);
        evidence.onEdtStarted(AtlasImageSceneDriverAgent.EdtOperation.CANCEL_BUTTON);
        evidence.onEdtEnded(AtlasImageSceneDriverAgent.EdtOperation.CANCEL_BUTTON,
            AtlasImageSceneDriverAgent.EdtInvocationState.COMPLETED, "COMPLETED", 4L);
        evidence.closePollStarted();
        evidence.onEdtQueued(AtlasImageSceneDriverAgent.EdtOperation.CANCEL_CLOSE_POLL);
        evidence.onEdtStarted(AtlasImageSceneDriverAgent.EdtOperation.CANCEL_CLOSE_POLL);
        evidence.onEdtEnded(AtlasImageSceneDriverAgent.EdtOperation.CANCEL_CLOSE_POLL,
            AtlasImageSceneDriverAgent.EdtInvocationState.COMPLETED, "COMPLETED", 5L);
        evidence.closePollClosed();
        evidence.actionWaitStarted();
        evidence.actionWaitEnded(6L, true);
        evidence.finishStarted();
        evidence.finishWritten(7L);

        final Properties values = loadProperties(target);
        final String[] required = {
            "schemaVersion", "sequence", "runStartedEpochMillis", "eventEpochMillis",
            "runElapsedMillis", "eventElapsedMillis", "stage", "event", "failure",
            "failureCode", "timedOut", "startedAtTimeout", "startupBudgetSeconds",
            "onEdt.queued", "onEdt.started", "onEdt.completed", "onEdt.timedOut",
            "onEdt.lateSkipped", "mainLookupRetryCount", "onEdt.lastOperation",
            "onEdt.lastState", "onEdt.lastOutcome", "cancel.dispatchStarted", "cancel.edtReturned",
            "cancel.dispatchElapsedMillis", "cancel.closePollStarted", "cancel.closePollCount",
            "cancel.closed", "editor.actionWaitStarted", "editor.actionWaitCompleted",
            "editor.actionWaitElapsedMillis", "finish.requestStarted", "finish.requestWritten",
            "finish.requestElapsedMillis"
        };
        for (String key : required) check(values.containsKey(key), "stage evidence field: " + key);
        check("1".equals(values.getProperty("schemaVersion")), "stage evidence schema");
        check("FINISH_REQUEST".equals(values.getProperty("stage")), "stage evidence final stage");
        check("WRITTEN".equals(values.getProperty("event")), "stage evidence final event");
        check("false".equals(values.getProperty("failure")), "stage evidence failure default");
        check("NONE".equals(values.getProperty("failureCode")), "stage evidence failure code default");
        check("false".equals(values.getProperty("timedOut")), "stage evidence timeout default");
        check("false".equals(values.getProperty("startedAtTimeout")),
            "stage evidence started timeout default");
        check("120".equals(values.getProperty("startupBudgetSeconds")),
            "stage evidence startup budget");
        check("3".equals(values.getProperty("onEdt.queued"))
                && "3".equals(values.getProperty("onEdt.started"))
                && "3".equals(values.getProperty("onEdt.completed")),
            "stage evidence EDT counters");
        check("0".equals(values.getProperty("onEdt.timedOut"))
                && "0".equals(values.getProperty("onEdt.lateSkipped"))
                && "0".equals(values.getProperty("mainLookupRetryCount")),
            "stage evidence EDT failure counters");
        check("CANCEL_CLOSE_POLL".equals(values.getProperty("onEdt.lastOperation"))
                && "COMPLETED".equals(values.getProperty("onEdt.lastState"))
                && "COMPLETED".equals(values.getProperty("onEdt.lastOutcome")),
            "stage evidence last EDT fields");
        check("true".equals(values.getProperty("cancel.dispatchStarted"))
                && "true".equals(values.getProperty("cancel.edtReturned"))
                && "4".equals(values.getProperty("cancel.dispatchElapsedMillis")),
            "stage evidence Cancel fields");
        check("true".equals(values.getProperty("cancel.closePollStarted"))
                && "1".equals(values.getProperty("cancel.closePollCount"))
                && "true".equals(values.getProperty("cancel.closed")),
            "stage evidence close-poll fields");
        check("true".equals(values.getProperty("editor.actionWaitStarted"))
                && "true".equals(values.getProperty("editor.actionWaitCompleted"))
                && "6".equals(values.getProperty("editor.actionWaitElapsedMillis")),
            "stage evidence action wait fields");
        check("true".equals(values.getProperty("finish.requestStarted"))
                && "true".equals(values.getProperty("finish.requestWritten"))
                && "7".equals(values.getProperty("finish.requestElapsedMillis")),
            "stage evidence finish fields");
        check(Long.parseLong(values.getProperty("sequence")) > 0L,
            "stage evidence sequence is numeric");
        check(Long.parseLong(values.getProperty("runStartedEpochMillis")) > 0L
                && Long.parseLong(values.getProperty("eventEpochMillis")) > 0L,
            "stage evidence timestamps are numeric");
        check(Long.parseLong(values.getProperty("runElapsedMillis")) >= 0L
                && Long.parseLong(values.getProperty("eventElapsedMillis")) >= 0L,
            "stage evidence elapsed values are numeric");
        final String content = Files.readString(target);
        check(content.indexOf('\n') >= 0, "stage evidence contains real newlines");
        check(!content.contains("\\n"), "stage evidence has no literal backslash-n");
        System.out.println("ATLAS_IMAGE_STAGE_EVIDENCE_SELFCHECK PASS properties-load=true");
    }

    private static JFrame menuFrame(final String title, final AtomicInteger clicks) {
        final JFrame frame = new JFrame(title);
        final JMenuBar bar = new JMenuBar();
        final JMenu modeling = new JMenu("建模");
        final JMenu texture = new JMenu("纹理");
        final JMenuItem editor = new JMenuItem("编辑纹理集...");
        editor.addActionListener(ignored -> clicks.incrementAndGet());
        texture.add(editor);
        modeling.add(texture);
        bar.add(modeling);
        frame.setJMenuBar(bar);
        frame.setSize(320, 120);
        return frame;
    }

    private static void showFrame(final JFrame frame) throws Exception {
        final CountDownLatch shown = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(true);
            shown.countDown();
        });
        check(shown.await(1, TimeUnit.SECONDS), "selfcheck frame shown");
    }

    private static void disposeFrame(final JFrame frame) throws Exception {
        final CountDownLatch disposed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            frame.dispose();
            disposed.countDown();
        });
        check(disposed.await(1, TimeUnit.SECONDS), "selfcheck frame disposed");
    }

    private static void blockEdt(final CountDownLatch started, final CountDownLatch release) {
        SwingUtilities.invokeLater(() -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static Properties loadProperties(final Path path) throws Exception {
        final Properties values = new Properties();
        try (var input = Files.newInputStream(path)) {
            values.load(input);
        }
        return values;
    }

    private static Properties waitForProperty(final Path path, final String key,
                                              final String expected) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (System.nanoTime() < deadline) {
            final Properties values = loadProperties(path);
            if (expected.equals(values.getProperty(key))) return values;
            Thread.sleep(10L);
        }
        throw new IllegalStateException("stage evidence property timeout: " + key);
    }

    private static <T> void enqueue(
            final AtlasImageSceneDriverAgent.FixedEdtInvocation<T> invocation) {
        SwingUtilities.invokeLater(() -> {
            if (!invocation.tryStart()) {
                invocation.skipAfterTimeout();
                return;
            }
            try {
                invocation.executeStarted();
            } finally {
                invocation.completeStarted();
            }
        });
    }

    private static Properties properties(final Path artifact, final Path output, final String runId) {
        final Properties values = new Properties();
        values.setProperty("version", "5303");
        values.setProperty("editorJar", artifact.toString());
        values.setProperty("outputRoot", output.toString());
        values.setProperty("runId", runId);
        return values;
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static void expectFailure(final CheckedAction action, final String label) {
        try {
            action.run();
            throw new AssertionError(label + " accepted");
        } catch (IllegalArgumentException | java.nio.file.FileAlreadyExistsException expected) {
            checks++;
        } catch (Exception expected) {
            checks++;
        }
    }

    private static void check(final boolean condition, final String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void deleteTree(final Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
    }
}
