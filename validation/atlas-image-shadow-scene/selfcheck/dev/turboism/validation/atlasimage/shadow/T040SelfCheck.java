package dev.turboism.validation.atlasimage.shadow;

import com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b;
import com.live2d.cubism.doc.modeling.ui.atlasEditor.a.f;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.awt.Dialog;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JWindow;

/** Offline behavior checks; uses only a synthetic Swing fixture and a strict T039 map stub. */
public final class T040SelfCheck {
    private static final String TASK_ID = "offline-t040-5303";
    private static final String FIXTURE_NAME = TASK_ID + "-" + ShadowSceneContract.FIXTURE_CIRCLE100_NAME;

    private T040SelfCheck() {}

    public static void main(final String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("T040 UI selfcheck requires a display");
        }
        final AtomicInteger checks = new AtomicInteger();
        checkContract(checks);
        checkFreezeBridge(checks);
        checkRunClaimState(checks);
        checkPayloadStore(checks);
        checkEdtStateMachine(checks);
        checkUiStateMachine(checks);
        System.out.println("T040_SHADOW_SCENE_SELFCHECK PASS checks=" + checks.get()
            + " hostExecuted=false");
    }

    private static void checkContract(final AtomicInteger checks) throws Exception {
        final String allowed = ShadowSceneContract.requireAllowlistedFixture(
            ShadowSceneContract.VERSION_5303, TASK_ID, FIXTURE_NAME,
            ShadowSceneContract.FIXTURE_CIRCLE100_SHA256);
        ShadowSceneContract.requireNamedFixture("/tmp/" + FIXTURE_NAME, FIXTURE_NAME, TASK_ID, allowed);
        check(checks, T040ShadowSceneDriverAgent.titleMatches(
            "Model - " + FIXTURE_NAME + " - Cubism", FIXTURE_NAME), "exact fixture title match");
        check(checks, !T040ShadowSceneDriverAgent.titleMatches(
            "Model - other-" + FIXTURE_NAME, FIXTURE_NAME), "near fixture title rejected");
        rejects(checks, () -> ShadowSceneContract.requireNamedFixture(
            "/tmp/" + ShadowSceneContract.FIXTURE_CIRCLE100_NAME, FIXTURE_NAME, TASK_ID, allowed));
        rejects(checks, () -> ShadowSceneContract.requireNamedFixture(
            "/tmp/" + FIXTURE_NAME, "wrong-name", TASK_ID, allowed));

        // Fixture identity is a paired allowlist: a name and a hash may never be mixed across
        // entries, and an unknown fixture fails closed instead of being measured as reviewed.
        final String heavy = ShadowSceneContract.requireAllowlistedFixture(
            ShadowSceneContract.VERSION_5303, TASK_ID,
            TASK_ID + "-" + ShadowSceneContract.FIXTURE_HEAVY_NAME,
            ShadowSceneContract.FIXTURE_HEAVY_SHA256);
        check(checks, ShadowSceneContract.FIXTURE_HEAVY_NAME.equals(heavy),
            "the production-scale fixture is allowlisted");
        rejects(checks, () -> ShadowSceneContract.requireAllowlistedFixture(
            ShadowSceneContract.VERSION_5303, TASK_ID, FIXTURE_NAME,
            ShadowSceneContract.FIXTURE_HEAVY_SHA256));
        rejects(checks, () -> ShadowSceneContract.requireAllowlistedFixture(
            ShadowSceneContract.VERSION_5303, TASK_ID,
            TASK_ID + "-" + ShadowSceneContract.FIXTURE_HEAVY_NAME,
            ShadowSceneContract.FIXTURE_CIRCLE100_SHA256));
        rejects(checks, () -> ShadowSceneContract.requireAllowlistedFixture(
            ShadowSceneContract.VERSION_5303, TASK_ID, TASK_ID + "-unknown.cmo3",
            ShadowSceneContract.FIXTURE_HEAVY_SHA256));
        rejects(checks, () -> ShadowSceneContract.requireAllowlistedFixture(
            ShadowSceneContract.VERSION_5303, TASK_ID,
            TASK_ID + "-" + ShadowSceneContract.FIXTURE_HEAVY_NAME, "a".repeat(64)));
        rejects(checks, () -> ShadowSceneContract.requireAllowlistedFixture(
            ShadowSceneContract.VERSION_5303, TASK_ID,
            ShadowSceneContract.FIXTURE_HEAVY_NAME,
            ShadowSceneContract.FIXTURE_HEAVY_SHA256));

        // The 5203 profile admits only its own reviewed pair and never the 5303 fixtures.
        final String opacity52 = ShadowSceneContract.requireAllowlistedFixture(
            ShadowSceneContract.VERSION_5203, TASK_ID,
            TASK_ID + "-" + ShadowSceneContract.FIXTURE_OPACITY52_NAME,
            ShadowSceneContract.FIXTURE_OPACITY52_SHA256);
        check(checks, ShadowSceneContract.FIXTURE_OPACITY52_NAME.equals(opacity52),
            "the 5203 fixture is allowlisted under its own profile");
        rejects(checks, () -> ShadowSceneContract.requireAllowlistedFixture(
            ShadowSceneContract.VERSION_5203, TASK_ID,
            TASK_ID + "-" + ShadowSceneContract.FIXTURE_HEAVY_NAME,
            ShadowSceneContract.FIXTURE_HEAVY_SHA256));
        rejects(checks, () -> ShadowSceneContract.requireAllowlistedFixture(
            ShadowSceneContract.VERSION_5303, TASK_ID,
            TASK_ID + "-" + ShadowSceneContract.FIXTURE_OPACITY52_NAME,
            ShadowSceneContract.FIXTURE_OPACITY52_SHA256));

        // Budget overrides stay fail-closed and default to the values the scene always used.
        check(checks, ShadowSceneContract.STARTUP_TIMEOUT_SECONDS
                == ShadowSceneContract.secondsProperty("turboism.validation.test.absent",
                    ShadowSceneContract.STARTUP_TIMEOUT_SECONDS),
            "an unset budget property keeps the built-in default");
        System.setProperty(ShadowSceneContract.STARTUP_SECONDS_PROPERTY, "1200");
        check(checks, 1200L == ShadowSceneContract.secondsProperty(
                ShadowSceneContract.STARTUP_SECONDS_PROPERTY, ShadowSceneContract.STARTUP_TIMEOUT_SECONDS),
            "an in-range budget override is honoured");
        System.setProperty(ShadowSceneContract.STARTUP_SECONDS_PROPERTY, "0");
        rejects(checks, () -> ShadowSceneContract.secondsProperty(
            ShadowSceneContract.STARTUP_SECONDS_PROPERTY, ShadowSceneContract.STARTUP_TIMEOUT_SECONDS));
        System.setProperty(ShadowSceneContract.STARTUP_SECONDS_PROPERTY, "3601");
        rejects(checks, () -> ShadowSceneContract.secondsProperty(
            ShadowSceneContract.STARTUP_SECONDS_PROPERTY, ShadowSceneContract.STARTUP_TIMEOUT_SECONDS));
        System.setProperty(ShadowSceneContract.STARTUP_SECONDS_PROPERTY, "lots");
        rejects(checks, () -> ShadowSceneContract.secondsProperty(
            ShadowSceneContract.STARTUP_SECONDS_PROPERTY, ShadowSceneContract.STARTUP_TIMEOUT_SECONDS));
        System.clearProperty(ShadowSceneContract.STARTUP_SECONDS_PROPERTY);
        rejects(checks, () -> ShadowSceneContract.requireT039FixedValues(
            "5303", TASK_ID, TASK_ID, ShadowSceneContract.T039_SOURCE_BINDING,
            "/explicit/Live2D_Cubism.jar",
            "0".repeat(64), ShadowSceneContract.T039_CLASS_SHA256,
            ShadowSceneContract.T039_SHAPE_SHA256, "synthetic.Loader",
            "1".repeat(64), "2".repeat(64), ShadowSceneContract.T039_SHADOW_MODE,
            ShadowSceneContract.T039_SHADOW_OPT_IN));
        checkT039SourceBinding(checks);
        checks.incrementAndGet();
    }

    /** The launch wrapper emits no codeSource; the driver gates the target-pd candidate list. */
    private static void checkT039SourceBinding(final AtomicInteger checks) throws Exception {
        ShadowSceneContract.requireT039SourceBinding(ShadowSceneContract.T039_SOURCE_BINDING,
            "C:\\Program Files\\Live2D Cubism 5.3.03\\app\\lib\\Live2D_Cubism.jar");
        checks.incrementAndGet();
        ShadowSceneContract.requireT039SourceBinding(ShadowSceneContract.T039_SOURCE_BINDING,
            "/opt/runner/prefix/app/lib/Live2D_Cubism.jar");
        checks.incrementAndGet();
        rejects(checks, () -> ShadowSceneContract.requireT039SourceBinding(
            "fixed-pd", "/opt/runner/prefix/app/lib/Live2D_Cubism.jar"));
        rejects(checks, () -> ShadowSceneContract.requireT039SourceBinding(
            ShadowSceneContract.T039_SOURCE_BINDING,
            "C:\\one\\Live2D_Cubism.jar;C:\\two\\Live2D_Cubism.jar"));
        rejects(checks, () -> ShadowSceneContract.requireT039SourceBinding(
            ShadowSceneContract.T039_SOURCE_BINDING, "C:\\lib\\Live2D_Cubism_other.jar"));
        rejects(checks, () -> ShadowSceneContract.requireT039SourceBinding(
            ShadowSceneContract.T039_SOURCE_BINDING, "C:\\lib\\"));
        rejects(checks, () -> ShadowSceneContract.requireT039SourceBinding(
            ShadowSceneContract.T039_SOURCE_BINDING, "Live2D_Cubism.jar"));
        rejects(checks, () -> ShadowSceneContract.requireT039SourceBinding(
            ShadowSceneContract.T039_SOURCE_BINDING, " "));
        rejects(checks, () -> ShadowSceneContract.requireT039SourceBinding(
            ShadowSceneContract.T039_SOURCE_BINDING, "C:\\lib\\Live2D_Cubism.jar\n"));
        rejects(checks, () -> ShadowSceneContract.requireT039SourceBinding(
            ShadowSceneContract.T039_SOURCE_BINDING, "a".repeat(4097)));
        check(checks, ShadowSceneContract.LAYOUT_SCALE_KERNEL_PERCENT.equals(
            ShadowSceneContract.requireLayoutScalePercent("40")), "the kernel-path scale is allowed");
        check(checks, ShadowSceneContract.LAYOUT_SCALE_FAST_PATH_PERCENT.equals(
            ShadowSceneContract.requireLayoutScalePercent("60")), "the fast-path scale is allowed");
        rejects(checks, () -> ShadowSceneContract.requireLayoutScalePercent("45"));
        rejects(checks, () -> ShadowSceneContract.requireLayoutScalePercent("55"));
        rejects(checks, () -> ShadowSceneContract.requireLayoutScalePercent("40.0"));
        rejects(checks, () -> ShadowSceneContract.requireLayoutScalePercent(null));
        check(checks, ShadowSceneContract.LAYOUT_SCALE_KERNEL_PERCENT.equals(
            ShadowSceneContract.layoutScalePercent()), "an unset property defaults to the kernel path");
        System.setProperty(ShadowSceneContract.LAYOUT_SCALE_PROPERTY,
            ShadowSceneContract.LAYOUT_SCALE_FAST_PATH_PERCENT);
        check(checks, ShadowSceneContract.LAYOUT_SCALE_FAST_PATH_PERCENT.equals(
            ShadowSceneContract.layoutScalePercent()), "the property selects the fast-path control");
        System.setProperty(ShadowSceneContract.LAYOUT_SCALE_PROPERTY, "55");
        rejects(checks, ShadowSceneContract::layoutScalePercent);
        System.clearProperty(ShadowSceneContract.LAYOUT_SCALE_PROPERTY);
    }
    private static void checkRunClaimState(final AtomicInteger checks) throws Exception {
        final Path cleanHome = Files.createTempDirectory("t040-claim-clean-");
        final T040ShadowSceneDriverAgent.DriverConfig cleanConfig =
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(
                cleanHome, TASK_ID, cleanHome.resolve("fixture"), FIXTURE_NAME, "a".repeat(64), 20L);
        final Path expectedOutput = cleanHome.resolve(ShadowSceneContract.OUTPUT_RELATIVE);
        check(checks, !Files.exists(expectedOutput), "clean home has no pre-created shadow output");
        final Path output = cleanConfig.claimOutputRoot();
        check(checks, Files.isDirectory(output) && !Files.isSymbolicLink(output),
            "driver creates a real shadow output directory");
        final Path claimed = cleanConfig.claimRunDirectory(output);
        check(checks, Files.isDirectory(claimed) && !Files.isSymbolicLink(claimed),
            "driver atomically claims a real run directory");
        rejects(checks, () -> cleanConfig.claimRunDirectory(output));

        final Path outside = Files.createTempDirectory("t040-claim-outside-");
        rejects(checks, () -> cleanConfig.claimRunDirectory(outside));

        final Path runLinkHome = Files.createTempDirectory("t040-claim-run-link-");
        final T040ShadowSceneDriverAgent.DriverConfig runLinkConfig =
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(
                runLinkHome, TASK_ID, runLinkHome.resolve("fixture"), FIXTURE_NAME, "a".repeat(64), 20L);
        final Path runLinkOutput = runLinkConfig.claimOutputRoot();
        final Path runLinkTarget = Files.createTempDirectory("t040-claim-run-target-");
        Files.createSymbolicLink(runLinkOutput.resolve(TASK_ID), runLinkTarget);
        rejects(checks, () -> runLinkConfig.claimRunDirectory(runLinkOutput));

        final Path outputLinkHome = Files.createTempDirectory("t040-claim-output-link-");
        final Path outputLinkTarget = Files.createTempDirectory("t040-claim-output-target-");
        Files.createSymbolicLink(outputLinkHome.resolve("state"), outputLinkTarget);
        final T040ShadowSceneDriverAgent.DriverConfig outputLinkConfig =
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(
                outputLinkHome, TASK_ID, outputLinkHome.resolve("fixture"), FIXTURE_NAME,
                "a".repeat(64), 20L);
        rejects(checks, outputLinkConfig::claimOutputRoot);

        final Path concurrentHome = Files.createTempDirectory("t040-claim-concurrent-");
        final T040ShadowSceneDriverAgent.DriverConfig concurrentConfig =
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(
                concurrentHome, TASK_ID, concurrentHome.resolve("fixture"), FIXTURE_NAME,
                "a".repeat(64), 20L);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        final Runnable contender = () -> {
            try {
                ready.countDown();
                if (!go.await(2L, TimeUnit.SECONDS)) throw new IllegalStateException("claim gate timeout");
                final Path concurrentOutput = concurrentConfig.claimOutputRoot();
                concurrentConfig.claimRunDirectory(concurrentOutput);
                successes.incrementAndGet();
            } catch (Throwable failure) {
                firstFailure.compareAndSet(null, failure);
                failures.incrementAndGet();
            }
        };
        final Thread first = new Thread(contender, "t040-claim-first");
        final Thread second = new Thread(contender, "t040-claim-second");
        first.start();
        second.start();
        check(checks, ready.await(2L, TimeUnit.SECONDS), "concurrent claimants reached gate");
        go.countDown();
        first.join(3000L);
        second.join(3000L);
        check(checks, successes.get() == 1 && failures.get() == 1,
            "concurrent run claim has one winner and one rejection");
        check(checks, firstFailure.get() != null, "duplicate concurrent claim exposes rejection");
        check(checks, Files.isDirectory(concurrentHome.resolve(ShadowSceneContract.OUTPUT_RELATIVE)
            .resolve(TASK_ID)), "concurrent claim leaves one run directory");
    }

    private static void checkFreezeBridge(final AtomicInteger checks) throws Exception {
        final Map<String, String> good = goodFreeze(TASK_ID, "NO_CALLS");
        check(checks, good.size() == 43, "exact T039 43-key schema");
        final Map<String, String> frozen = T039FreezeBridge.validateAndCopy(good, TASK_ID, "5303");
        check(checks, "true".equals(frozen.get("frozen")), "frozen map accepted");
        check(checks, frozen.getClass().getName().contains("Unmodifiable"), "freeze map immutable");
        for (String outcome : new String[] {"NO_CALLS", "NO_ELIGIBLE_CALLS", "POTENTIAL_TRIM", "TRUNCATED"}) {
            T039FreezeBridge.validateAndCopy(goodFreeze(TASK_ID, outcome), TASK_ID, "5303");
            checks.incrementAndGet();
        }
        rejects(checks, () -> T039FreezeBridge.validateAndCopy(goodFreeze("wrong", "NO_CALLS"),
            TASK_ID, "5303"));
        final Map<String, String> incomplete = new HashMap<>(good);
        incomplete.put("state", "PATCHED");
        rejects(checks, () -> T039FreezeBridge.validateAndCopy(incomplete, TASK_ID, "5303"));
        final Map<String, String> missing = new HashMap<>(good);
        missing.remove("fullBoundsPreserved");
        rejects(checks, () -> T039FreezeBridge.validateAndCopy(missing, TASK_ID, "5303"));
        final Map<String, Object> nested = new HashMap<>();
        nested.putAll(good);
        nested.put("stats.bad", Integer.valueOf(1));
        rejects(checks, () -> T039FreezeBridge.validateAndCopy(nested, TASK_ID, "5303"));
        final Map<String, String> unknown = new HashMap<>(good);
        unknown.put("modelTitle", "must-not-be-read");
        rejects(checks, () -> T039FreezeBridge.validateAndCopy(unknown, TASK_ID, "5303"));
    }

    private static void checkPayloadStore(final AtomicInteger checks) throws Exception {
        final Path root = Files.createTempDirectory("t040-payload-");
        final Path run = root.resolve("run");
        Files.createDirectories(run);
        final Map<String, String> freeze = goodFreeze(TASK_ID, "TRUNCATED");
        final String fixtureSha = "a".repeat(64);
        final ShadowPayloadStore.Persisted payload = ShadowPayloadStore.persist(
            run, TASK_ID, ShadowSceneContract.SCENE_5303, "5303", FIXTURE_NAME, fixtureSha,
            freeze);
        final Properties values = ShadowPayloadStore.loadProperties(payload.path());
        check(checks, "1".equals(values.getProperty("payloadSchemaVersion")), "payload schema reread");
        check(checks, "TRUNCATED".equals(values.getProperty("freeze.sampleOutcome")),
            "payload freeze classification reread");
        rejects(checks, () -> ShadowPayloadStore.persist(
            run, TASK_ID, ShadowSceneContract.SCENE_5303, "5303", FIXTURE_NAME, fixtureSha,
            freeze));

        final Path result = root.resolve("result.txt");
        ShadowPayloadStore.publishComplete(result, TASK_ID, ShadowSceneContract.SCENE_5303, payload.sha256());
        final Properties complete = ShadowPayloadStore.loadProperties(result);
        check(checks, complete.size() == 4
                && "COMPLETE".equals(complete.getProperty("collectionStatus"))
                && ShadowSceneContract.SCENE_5303.equals(complete.getProperty("scene"))
                && TASK_ID.equals(complete.getProperty("runId"))
                && payload.sha256().equals(complete.getProperty("payloadSha256")),
            "complete canonical result reread");
        rejects(checks, () -> ShadowPayloadStore.publishComplete(result, TASK_ID,
            ShadowSceneContract.SCENE_5303, payload.sha256()));

        final Path shortResult = root.resolve("short-result.txt");
        ShadowPayloadStore.publishCompleteForSelfCheck(shortResult, TASK_ID,
            ShadowSceneContract.SCENE_5303, payload.sha256(),
            (channel, buffer) -> {
                final int oldLimit = buffer.limit();
                final int chunk = Math.min(3, buffer.remaining());
                buffer.limit(buffer.position() + chunk);
                try {
                    return channel.write(buffer);
                } finally {
                    buffer.limit(oldLimit);
                }
            });
        final Properties shortValues = ShadowPayloadStore.loadProperties(shortResult);
        check(checks, "COMPLETE".equals(shortValues.getProperty("collectionStatus"))
                && payload.sha256().equals(shortValues.getProperty("payloadSha256")),
            "short writes are completed before publication");

        final Path writeFailure = root.resolve("write-failure.txt");
        rejects(checks, () -> ShadowPayloadStore.publishCompleteForSelfCheck(
            writeFailure, TASK_ID, ShadowSceneContract.SCENE_5303, payload.sha256(),
            (channel, buffer) -> {
                throw new IOException("synthetic short-write failure");
            }));
        check(checks, !Files.exists(writeFailure), "write failure publishes no canonical result");

        final Path zeroProgress = root.resolve("zero-progress.txt");
        rejects(checks, () -> ShadowPayloadStore.publishCompleteForSelfCheck(
            zeroProgress, TASK_ID, ShadowSceneContract.SCENE_5303, payload.sha256(),
            (channel, buffer) -> 0));
        check(checks, !Files.exists(zeroProgress), "zero progress publishes no canonical result");

        final byte[] expectedComplete = ShadowPayloadStore.completeBytesForSelfCheck(
            TASK_ID, ShadowSceneContract.SCENE_5303, payload.sha256());
        final Path truncated = root.resolve("truncated-complete.tmp");
        Files.writeString(truncated, "collectionStatus=COMPLETE\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        rejects(checks, () -> ShadowPayloadStore.verifyCompleteForSelfCheck(
            truncated, expectedComplete));
        check(checks, Files.isRegularFile(truncated), "truncated prepublish probe remains only a temp file");

        final Path failedResult = root.resolve("failed-result.txt");
        ShadowPayloadStore.publishFailed(failedResult, TASK_ID,
            ShadowSceneContract.SCENE_5303, "freeze-invalid");
        check(checks, new String(Files.readAllBytes(failedResult), StandardCharsets.UTF_8)
            .contains("collectionStatus=FAILED\n"), "failed canonical result");
        final Path blockedResult = root.resolve("blocked-result");
        Files.createDirectory(blockedResult);
        rejects(checks, () -> ShadowPayloadStore.publishComplete(blockedResult, TASK_ID,
            ShadowSceneContract.SCENE_5303, payload.sha256()));
        check(checks, Files.isDirectory(blockedResult), "failed write did not replace target");
        check(checks, !Files.isRegularFile(blockedResult), "failed write has no COMPLETE file");
    }

    private static void checkEdtStateMachine(final AtomicInteger checks) throws Exception {
        final Path evidencePath = Files.createTempDirectory("t040-edt-").resolve("driver-stage.properties");
        final StageEvidence evidence = new StageEvidence(evidencePath,
            ShadowSceneContract.SCENE_5303, ShadowSceneContract.STARTUP_TIMEOUT_SECONDS,
            ShadowSceneContract.CLOSE_POLL_TIMEOUT_SECONDS,
            ShadowSceneContract.LAYOUT_DIALOG_TIMEOUT_SECONDS);
        final String value = FixedEdt.call(() -> "normal", FixedEdt.Operation.MAIN_LOOKUP, evidence);
        check(checks, "normal".equals(value), "normal EDT query");
        rejects(checks, () -> FixedEdt.call(() -> {
            throw new IOException("synthetic query failure");
        }, FixedEdt.Operation.MAIN_LOOKUP, evidence));

        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            blockerStarted.countDown();
            await(release, 7L);
        });
        check(checks, blockerStarted.await(2L, TimeUnit.SECONDS), "EDT blocker started");
        final AtomicInteger queuedSideEffect = new AtomicInteger();
        try {
            FixedEdt.call(() -> {
                queuedSideEffect.incrementAndGet();
                return null;
            }, FixedEdt.Operation.MAIN_LOOKUP, evidence);
            throw new IllegalStateException("queued EDT action unexpectedly returned");
        } catch (FixedEdt.Timeout timeout) {
            check(checks, timeout.state == FixedEdt.State.TIMED_OUT, "queued timeout is invalidated");
        }
        release.countDown();
        waitForEdt();
        final int afterRelease = FixedEdt.call(queuedSideEffect::get,
            FixedEdt.Operation.MAIN_LOOKUP, evidence);
        check(checks, afterRelease == 0, "late queued callback performed no action");

        final CountDownLatch startedAction = new CountDownLatch(1);
        final CountDownLatch releaseAction = new CountDownLatch(1);
        final AtomicBoolean actionRan = new AtomicBoolean();
        try {
            FixedEdt.call(() -> {
                actionRan.set(true);
                startedAction.countDown();
                await(releaseAction, 7L);
                return null;
            }, FixedEdt.Operation.MAIN_LOOKUP, evidence);
            throw new IllegalStateException("started EDT action unexpectedly returned");
        } catch (FixedEdt.Timeout timeout) {
            check(checks, timeout.state == FixedEdt.State.STARTED, "started timeout is not cancellable");
        }
        check(checks, startedAction.await(1L, TimeUnit.SECONDS) && actionRan.get(),
            "started action actually ran");
        releaseAction.countDown();
        waitForEdt();
        check(checks, "after-started".equals(FixedEdt.call(() -> "after-started",
            FixedEdt.Operation.MAIN_LOOKUP, evidence)), "late started completion does not block progress");

        final CountDownLatch interruptedBlockerStarted = new CountDownLatch(1);
        final CountDownLatch interruptedRelease = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            interruptedBlockerStarted.countDown();
            await(interruptedRelease, 7L);
        });
        check(checks, interruptedBlockerStarted.await(2L, TimeUnit.SECONDS), "interrupt blocker started");
        final AtomicInteger interruptedSideEffect = new AtomicInteger();
        final AtomicReference<Throwable> interruptedFailure = new AtomicReference<>();
        final AtomicBoolean interruptRestored = new AtomicBoolean();
        final Thread waiter = new Thread(() -> {
            try {
                FixedEdt.call(() -> {
                    interruptedSideEffect.incrementAndGet();
                    return null;
                }, FixedEdt.Operation.MAIN_LOOKUP, evidence);
            } catch (Throwable failure) {
                interruptedFailure.set(failure);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "t040-interrupted-waiter");
        waiter.start();
        Thread.sleep(100L);
        waiter.interrupt();
        waiter.join(2000L);
        interruptedRelease.countDown();
        waitForEdt();
        check(checks, interruptedFailure.get() instanceof IllegalStateException,
            "interrupted wait fails");
        check(checks, interruptRestored.get(), "interrupted wait restores status");
        check(checks, interruptedSideEffect.get() == 0, "interrupted queued callback skipped");

        final Properties stage = ShadowPayloadStore.loadProperties(evidencePath);
        check(checks, stage.getProperty("edtStartBudgetSeconds").equals("5"),
            "stage records the five-second queue budget");
        check(checks, stage.getProperty("edtQueryBudgetSeconds").equals("5"),
            "stage records the five-second query budget");
        check(checks, Long.parseLong(stage.getProperty("onEdt.queued")) >= 4L,
            "stage records queued count");
        check(checks, Long.parseLong(stage.getProperty("onEdt.started")) >= 2L,
            "stage records started count");
        check(checks, Long.parseLong(stage.getProperty("onEdt.completed")) >= 2L,
            "stage records completed count");
        check(checks, Long.parseLong(stage.getProperty("onEdt.lateSkipped")) >= 2L,
            "stage records late skipped callbacks");
        final String stageText = Files.readString(evidencePath, StandardCharsets.UTF_8);
        check(checks, stageText.contains("schemaVersion=1\n"), "stage uses real newlines");
        check(checks, !stageText.contains("\\nschemaVersion"), "stage has no literal newline escape");

        // A started host action is not cancellable: the fixed query budget reports it, while the
        // caller's own run budget absorbs it. The real OK click runs the atlas apply this way, so
        // it signals its own start barrier before blocking the EDT.
        final CountDownLatch hostActionStarted = new CountDownLatch(1);
        final AtomicReference<FixedEdt.Invocation<String>> hostActionHolder = new AtomicReference<>();
        final FixedEdt.Invocation<String> hostAction = new FixedEdt.Invocation<>(() -> {
            hostActionHolder.get().signalStarted();
            hostActionStarted.countDown();
            Thread.sleep(6_000L);
            return "slow-host-action";
        });
        hostActionHolder.set(hostAction);
        FixedEdt.post(hostAction, FixedEdt.Operation.OK_BUTTON, evidence, System.nanoTime());
        FixedEdt.awaitStarted(hostAction, FixedEdt.Operation.OK_BUTTON, evidence);
        check(checks, hostActionStarted.await(1L, TimeUnit.SECONDS), "host action started on the EDT");
        try {
            FixedEdt.awaitCompleted(hostAction, FixedEdt.Operation.OK_BUTTON, evidence, 1_000L);
            throw new IllegalStateException("short post-start budget unexpectedly completed");
        } catch (FixedEdt.Timeout timeout) {
            check(checks, timeout.operation == FixedEdt.Operation.OK_BUTTON
                    && timeout.state == FixedEdt.State.STARTED,
                "short action budget reports a started host action, not a queued one");
        }
        check(checks, "slow-host-action".equals(FixedEdt.awaitCompleted(
                hostAction, FixedEdt.Operation.OK_BUTTON, evidence, 15_000L)),
            "run budget absorbs a host action longer than the query budget");
    }

    private static void checkUiStateMachine(final AtomicInteger checks) throws Exception {
        f$b.reset();
        f$b.EXIT_PROMPT = "NO_BUTTON";
        final Path normalHome = Files.createTempDirectory("t040-ui-normal-");
        final UiFixture normal = createUiFixture(normalHome, false, false);
        check(checks, !Files.exists(normalHome.resolve(ShadowSceneContract.OUTPUT_RELATIVE)),
            "UI fixture starts with no pre-created output");
        final T040ShadowSceneDriverAgent.DriverConfig normalConfig = T040ShadowSceneDriverAgent.DriverConfig
            .forSelfCheck(normal.home, normal.taskId, normal.fixture, normal.fixtureName,
                normal.fixtureSha256, 20L);
        new T040ShadowSceneDriverAgent.FixedDriver(normalConfig,
            expected -> goodFreeze(expected, "NO_ELIGIBLE_CALLS")).run();
        check(checks, Files.isDirectory(normal.stage.getParent()),
            "driver created and claimed UI run directory");
        final Properties normalResult = ShadowPayloadStore.loadProperties(normal.result);
        check(checks, "COMPLETE".equals(normalResult.getProperty("collectionStatus")),
            "normal UI reaches COMPLETE");
        check(checks, f$b.OK_CLICKS.get() == 1, "exactly one OK action");
        check(checks, normal.exitClicks.get() == 1, "exactly one native exit action");
        final Properties normalStage = ShadowPayloadStore.loadProperties(normal.stage);
        check(checks, "true".equals(normalStage.getProperty("editor.closed")), "editor closed");
        check(checks, "true".equals(normalStage.getProperty("editor.actionWaitCompleted")),
            "menu action completion awaited");
        check(checks, "true".equals(normalStage.getProperty("payload.reopenHashVerified")),
            "payload reopened and hashed");
        check(checks, "true".equals(normalStage.getProperty("canonical.complete")),
            "canonical complete recorded");
        check(checks, "false".equals(normalStage.getProperty("editor.closePollUnexpected"))
                && "NONE".equals(normalStage.getProperty("editor.unexpectedWindowClasses")),
            "clean close poll reports no unexpected window");
        check(checks, "false".equals(normalStage.getProperty("editor.closePollTimedOut")),
            "a clean close poll never expires");
        check(checks, "true".equals(normalStage.getProperty("nativeExit.started"))
                && "true".equals(normalStage.getProperty("nativeExit.returned")),
            "a native exit that returns is recorded as such");
        check(checks, Long.parseLong(normalStage.getProperty("exit.windowSamples")) >= 1L
                && Long.parseLong(normalStage.getProperty("exit.nonDaemonThreadCount")) >= 1L
                && !"NONE".equals(normalStage.getProperty("exit.nonDaemonThreadNames")),
            "the bounded exit probe samples windows and non-daemon threads");
        check(checks, "true".equals(normalStage.getProperty("exit.promptSeen"))
                && ShadowSceneContract.EXIT_PROMPT_ANSWER.equals(
                    normalStage.getProperty("exit.promptAnswer"))
                && Long.parseLong(normalStage.getProperty("exit.promptButtonCount")) == 3L
                && normalStage.getProperty("exit.promptClasses").contains("JOptionPane")
                && f$b.EXIT_PROMPT_CLICKS.get() == 1,
            "the host-shaped exit prompt is answered with the only allowed answer");
        check(checks, "true".equals(normalStage.getProperty("layout.dialogSeen"))
                && "true".equals(normalStage.getProperty("layout.closed"))
                && ShadowSceneContract.LAYOUT_SCALE_KERNEL_PERCENT.equals(
                    normalStage.getProperty("layout.scaleText"))
                && f$b.LAYOUT_OPENS.get() == 1,
            "the scene drives the host automatic-layout dialog exactly once");
        check(checks, f.OK_CLICKS.get() == 1 && f.FIXED_SCALE_SELECTED
                && ShadowSceneContract.LAYOUT_SCALE_KERNEL_PERCENT.equals(f.APPLIED_PERCENT),
            "the layout dialog applies the user-specified scale below the kernel threshold");
        disposeUiFixture(normal);

        // The control run: 60% sits above the host's 0.45 threshold, so the same unchanged driver
        // must select exactly that percentage and the real host would take the Graphics2D fast path.
        f$b.reset();
        final Path fastPathHome = Files.createTempDirectory("t040-ui-fast-path-");
        final UiFixture fastPath = createUiFixture(fastPathHome, false, false);
        final T040ShadowSceneDriverAgent.DriverConfig fastPathConfig = T040ShadowSceneDriverAgent.DriverConfig
            .forSelfCheck(fastPath.home, fastPath.taskId, fastPath.fixture, fastPath.fixtureName,
                fastPath.fixtureSha256, 20L, ShadowSceneContract.LAYOUT_SCALE_FAST_PATH_PERCENT);
        new T040ShadowSceneDriverAgent.FixedDriver(fastPathConfig,
            expected -> goodFreeze(expected, "NO_CALLS")).run();
        final Properties fastPathStage = ShadowPayloadStore.loadProperties(fastPath.stage);
        check(checks, "COMPLETE".equals(
                ShadowPayloadStore.loadProperties(fastPath.result).getProperty("collectionStatus"))
                && f$b.LAYOUT_OPENS.get() == 1
                && ShadowSceneContract.LAYOUT_SCALE_FAST_PATH_PERCENT.equals(
                    fastPathStage.getProperty("layout.scaleText"))
                && ShadowSceneContract.LAYOUT_SCALE_FAST_PATH_PERCENT.equals(f.APPLIED_PERCENT),
            "the control run selects the above-threshold scale without a code change");
        disposeUiFixture(fastPath);

        // The "load / keep the texture set" path: the editor is opened and confirmed, and the
        // host's own auto-layout dialog is never opened, clicked or measured.
        f$b.reset();
        final Path preserveHome = Files.createTempDirectory("t040-ui-preserve-");
        final UiFixture preserve = createUiFixture(preserveHome, false, false);
        final T040ShadowSceneDriverAgent.DriverConfig preserveConfig = T040ShadowSceneDriverAgent.DriverConfig
            .forSelfCheck(preserve.home, preserve.taskId, preserve.fixture, preserve.fixtureName,
                preserve.fixtureSha256, 20L, ShadowSceneContract.LAYOUT_SCALE_KERNEL_PERCENT,
                ShadowSceneContract.LAYOUT_MODE_PRESERVE);
        new T040ShadowSceneDriverAgent.FixedDriver(preserveConfig,
            expected -> goodFreeze(expected, "NO_CALLS")).run();
        final Properties preserveStage = ShadowPayloadStore.loadProperties(preserve.stage);
        check(checks, "COMPLETE".equals(
                ShadowPayloadStore.loadProperties(preserve.result).getProperty("collectionStatus"))
                && f$b.LAYOUT_OPENS.get() == 0
                && "NONE".equals(f.APPLIED_PERCENT)
                && "true".equals(preserveStage.getProperty("layout.preserved"))
                && "false".equals(preserveStage.getProperty("layout.attempted"))
                && "true".equals(preserveStage.getProperty("editor.closed")),
            "the preserved-layout run confirms the editor without opening any layout dialog");
        disposeUiFixture(preserve);

        f$b.reset();
        final Path promptHome = Files.createTempDirectory("t040-ui-prompt-");
        final UiFixture prompt = createUiFixture(promptHome, true, false);
        final T040ShadowSceneDriverAgent.DriverConfig promptConfig = T040ShadowSceneDriverAgent.DriverConfig
            .forSelfCheck(prompt.home, prompt.taskId, prompt.fixture, prompt.fixtureName,
                prompt.fixtureSha256, 20L);
        new T040ShadowSceneDriverAgent.FixedDriver(promptConfig,
            expected -> goodFreeze(expected, "NO_CALLS")).run();
        final Properties promptResult = ShadowPayloadStore.loadProperties(prompt.result);
        check(checks, "FAILED".equals(promptResult.getProperty("collectionStatus")),
            "save prompt blocks collection");
        check(checks, f$b.SAVE_CLICKS.get() == 0, "save prompt was never accepted");
        check(checks, !new String(Files.readAllBytes(prompt.result), StandardCharsets.UTF_8)
            .contains("collectionStatus=COMPLETE\n"), "blocked prompt has no COMPLETE");
        check(checks, "false".equals(ShadowPayloadStore.loadProperties(prompt.stage)
                .getProperty("nativeExit.started")),
            "a blocked run never claims a native exit");
        disposeUiFixture(prompt);

        f$b.reset();
        final Path duplicateHome = Files.createTempDirectory("t040-ui-duplicate-ok-");
        final UiFixture duplicate = createUiFixture(duplicateHome, false, true);
        final T040ShadowSceneDriverAgent.DriverConfig duplicateConfig = T040ShadowSceneDriverAgent.DriverConfig
            .forSelfCheck(duplicate.home, duplicate.taskId, duplicate.fixture, duplicate.fixtureName,
                duplicate.fixtureSha256, 20L);
        new T040ShadowSceneDriverAgent.FixedDriver(duplicateConfig,
            expected -> goodFreeze(expected, "TRUNCATED")).run();
        final Properties duplicateResult = ShadowPayloadStore.loadProperties(duplicate.result);
        check(checks, "FAILED".equals(duplicateResult.getProperty("collectionStatus")),
            "duplicate OK is rejected");
        check(checks, f$b.OK_CLICKS.get() == 0, "duplicate OK was not clicked");
        disposeUiFixture(duplicate);

        // The real host runs the atlas apply inside the OK callback: the editor stays open and the
        // EDT stays saturated, so the close poll must be queued behind it rather than fail.
        f$b.reset();
        final Path slowOkHome = Files.createTempDirectory("t040-ui-slow-ok-");
        final UiFixture slowOk = createUiFixture(slowOkHome, false, false);
        f$b.SLOW_OK_MILLIS = 6_000L;
        final T040ShadowSceneDriverAgent.DriverConfig slowOkConfig =
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(slowOk.home, slowOk.taskId,
                slowOk.fixture, slowOk.fixtureName, slowOk.fixtureSha256, 60L);
        new T040ShadowSceneDriverAgent.FixedDriver(slowOkConfig,
            expected -> goodFreeze(expected, "NO_ELIGIBLE_CALLS")).run();
        final Properties slowOkResult = ShadowPayloadStore.loadProperties(slowOk.result);
        check(checks, "COMPLETE".equals(slowOkResult.getProperty("collectionStatus")),
            "host apply longer than the query budget still reaches COMPLETE");
        final Properties slowOkStage = ShadowPayloadStore.loadProperties(slowOk.stage);
        check(checks, "true".equals(slowOkStage.getProperty("editor.closed")),
            "slow OK closed the editor");
        check(checks, "true".equals(slowOkStage.getProperty("ok.actionWaitCompleted")),
            "OK action completion was awaited after the close poll");
        check(checks, "false".equals(slowOkStage.getProperty("editor.closePollTimedOut"))
                && Long.parseLong(slowOkStage.getProperty("onEdt.timedOut")) >= 1L,
            "the close poll survives being queued behind a long apply instead of failing");
        disposeUiFixture(slowOk);

        // The real 5303 OK keeps the editor open while the host shows its own modal progress window
        // (jp.noids.framework.e.a.f) and finishes from another thread. That window is fail-closed
        // only if it never clears, so the driver must wait it out and still publish.
        f$b.reset();
        final Path progressHome = Files.createTempDirectory("t040-ui-modal-progress-");
        final UiFixture progress = createUiFixture(progressHome, false, false);
        f$b.PROGRESS_MILLIS = 1_500L;
        final T040ShadowSceneDriverAgent.DriverConfig progressConfig =
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(progress.home, progress.taskId,
                progress.fixture, progress.fixtureName, progress.fixtureSha256, 30L);
        new T040ShadowSceneDriverAgent.FixedDriver(progressConfig,
            expected -> goodFreeze(expected, "POTENTIAL_TRIM")).run();
        final Properties progressResult = ShadowPayloadStore.loadProperties(progress.result);
        check(checks, "COMPLETE".equals(progressResult.getProperty("collectionStatus")),
            "a host-shaped modal progress window does not block a finished apply");
        final Properties progressStage = ShadowPayloadStore.loadProperties(progress.stage);
        check(checks, "true".equals(progressStage.getProperty("editor.closePollUnexpected")),
            "the modal progress window was observed as a blocking window");
        check(checks, Long.parseLong(progressStage.getProperty("editor.unexpectedWindowCount")) >= 1L
                && progressStage.getProperty("editor.unexpectedWindowClasses").contains("JDialog"),
            "the blocking progress window is named in the bounded evidence");
        check(checks, "true".equals(progressStage.getProperty("editor.closed"))
                && "false".equals(progressStage.getProperty("editor.closePollTimedOut"))
                && Long.parseLong(progressStage.getProperty("editor.closePollMillis")) >= 1_000L,
            "the close poll waited for the modal progress window instead of failing on first sight");
        disposeUiFixture(progress);

        // A prompt the scene may not answer must never be clicked: without a unique Look-and-Feel
        // no-button the driver keeps its hands off and records why the host never exited.
        f$b.reset();
        f$b.EXIT_PROMPT = "UNMATCHABLE";
        final Path unmatchableHome = Files.createTempDirectory("t040-ui-exit-prompt-");
        final UiFixture unmatchable = createUiFixture(unmatchableHome, false, false);
        final T040ShadowSceneDriverAgent.DriverConfig unmatchableConfig =
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(unmatchable.home, unmatchable.taskId,
                unmatchable.fixture, unmatchable.fixtureName, unmatchable.fixtureSha256, 30L);
        new T040ShadowSceneDriverAgent.FixedDriver(unmatchableConfig,
            expected -> goodFreeze(expected, "POTENTIAL_TRIM")).run();
        check(checks, "COMPLETE".equals(ShadowPayloadStore.loadProperties(unmatchable.result)
                .getProperty("collectionStatus")),
            "an unanswerable host prompt does not fail a finished collection");
        final Properties unmatchableStage = ShadowPayloadStore.loadProperties(unmatchable.stage);
        check(checks, "true".equals(unmatchableStage.getProperty("exit.promptSeen"))
                && unmatchableStage.getProperty("exit.promptAnswer").startsWith("NO_UNIQUE_")
                && f$b.EXIT_PROMPT_CLICKS.get() == 0,
            "a prompt without a unique no-button is recorded and left alone");
        disposeUiFixture(unmatchable);

        // The driver starts at premain, so the host can still be saturating the EDT. A slow round
        // trip must reset the readiness gate instead of letting the scene drive the UI anyway.
        f$b.reset();
        final Path readyHome = Files.createTempDirectory("t040-ui-readiness-");
        final UiFixture ready = createUiFixture(readyHome, false, false);
        final CountDownLatch saturated = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            saturated.countDown();
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        check(checks, saturated.await(2L, TimeUnit.SECONDS), "host saturation injected on the EDT");
        final T040ShadowSceneDriverAgent.DriverConfig readyConfig =
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(ready.home, ready.taskId,
                ready.fixture, ready.fixtureName, ready.fixtureSha256, 30L);
        new T040ShadowSceneDriverAgent.FixedDriver(readyConfig,
            expected -> goodFreeze(expected, "NO_ELIGIBLE_CALLS")).run();
        final Properties readyResult = ShadowPayloadStore.loadProperties(ready.result);
        check(checks, "COMPLETE".equals(readyResult.getProperty("collectionStatus")),
            "readiness gate still reaches COMPLETE");
        final Properties readyStage = ShadowPayloadStore.loadProperties(ready.stage);
        check(checks, Integer.parseInt(readyStage.getProperty("readiness.slowRounds")) >= 1,
            "a saturated EDT round trip resets the readiness gate");
        check(checks, Integer.parseInt(readyStage.getProperty("readiness.rounds"))
                == ShadowSceneContract.EDT_READY_ROUNDS,
            "readiness needs consecutive prompt round trips");
        disposeUiFixture(ready);

        // Host furniture that already shows before the scene acts is a baseline. Cubism hosts its
        // own floating windows in plain JDialogs, so treating every non-editor window as a blocker
        // would block every real run; only windows appearing after the baseline stay fail-closed.
        f$b.reset();
        final Path furnitureHome = Files.createTempDirectory("t040-ui-furniture-");
        final UiFixture furniture = createUiFixture(furnitureHome, false, false);
        final JWindow[] furnitureHolder = new JWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            final JWindow window = new JWindow();
            window.setSize(40, 40);
            window.setLocation(10, 300);
            window.setVisible(true);
            furnitureHolder[0] = window;
        });
        final T040ShadowSceneDriverAgent.DriverConfig furnitureConfig =
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(furniture.home, furniture.taskId,
                furniture.fixture, furniture.fixtureName, furniture.fixtureSha256, 30L);
        new T040ShadowSceneDriverAgent.FixedDriver(furnitureConfig,
            expected -> goodFreeze(expected, "NO_ELIGIBLE_CALLS")).run();
        final Properties furnitureResult = ShadowPayloadStore.loadProperties(furniture.result);
        check(checks, "COMPLETE".equals(furnitureResult.getProperty("collectionStatus")),
            "host furniture present before the scene does not block collection");
        final Properties furnitureStage = ShadowPayloadStore.loadProperties(furniture.stage);
        check(checks, Long.parseLong(furnitureStage.getProperty("baseline.windowCount")) >= 1L
                && furnitureStage.getProperty("baseline.windowClasses").contains("JWindow"),
            "baseline records the host's own already-showing window");
        check(checks, "false".equals(furnitureStage.getProperty("editor.closePollUnexpected")),
            "a baseline window is never reported as unexpected");
        SwingUtilities.invokeAndWait(() -> furnitureHolder[0].dispose());
        disposeUiFixture(furniture);

        // The fail-closed half: a window that exists only after the scene acted stays a block, and
        // the bounded class sample must name it so a Cubism progress or save window is diagnosable.
        f$b.reset();
        f$b.SHOW_STRAY_DIALOG = true;
        final Path strayHome = Files.createTempDirectory("t040-ui-post-baseline-window-");
        final UiFixture stray = createUiFixture(strayHome, false, false);
        final T040ShadowSceneDriverAgent.DriverConfig strayConfig =
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(stray.home, stray.taskId,
                stray.fixture, stray.fixtureName, stray.fixtureSha256, 8L);
        new T040ShadowSceneDriverAgent.FixedDriver(strayConfig,
            expected -> goodFreeze(expected, "NO_ELIGIBLE_CALLS")).run();
        final Properties strayResult = ShadowPayloadStore.loadProperties(stray.result);
        check(checks, "FAILED".equals(strayResult.getProperty("collectionStatus")),
            "a window appearing after the scene acted blocks collection");
        final Properties strayStage = ShadowPayloadStore.loadProperties(stray.stage);
        check(checks, "0".equals(strayStage.getProperty("baseline.windowCount")),
            "the post-baseline fixture starts with an empty baseline");
        check(checks, "true".equals(strayStage.getProperty("editor.closePollUnexpected")),
            "blocked close poll records the new window");
        check(checks, "false".equals(strayStage.getProperty("editor.closePollVisible")),
            "the post-baseline blocker is a new window, not the editor");
        check(checks, Long.parseLong(strayStage.getProperty("editor.unexpectedWindowCount")) >= 1L
                && strayStage.getProperty("editor.unexpectedWindowClasses").contains("JDialog"),
            "blocked close poll names the bounded window class");
        check(checks, "true".equals(strayStage.getProperty("editor.closePollTimedOut")),
            "the bounded close-poll budget ends the block");
        check(checks, Long.parseLong(strayStage.getProperty("editor.closePollCount")) >= 2L,
            "a blocked close poll sampled repeatedly");
        disposeUiFixture(stray);

        // The 5203 host profile runs the same scene without any T039 shadow capture: the
        // freeze provider must never be invoked, the payload carries no freeze.* keys, and
        // the evidence names the versioned scene identity.
        f$b.reset();
        final Path p5203Home = Files.createTempDirectory("t040-ui-5203-");
        final UiFixture p5203 = createUiFixture(p5203Home, false, false);
        final T040ShadowSceneDriverAgent.DriverConfig p5203Config =
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(p5203.home, p5203.taskId,
                p5203.fixture, p5203.fixtureName, p5203.fixtureSha256, 20L,
                ShadowSceneContract.VERSION_5203,
                ShadowSceneContract.LAYOUT_SCALE_KERNEL_PERCENT,
                ShadowSceneContract.LAYOUT_MODE_PRESERVE);
        check(checks, !p5203Config.shadow && ShadowSceneContract.SCENE_5203.equals(p5203Config.scene)
                && ShadowSceneContract.VERSION_5203.equals(p5203Config.profile),
            "the 5203 profile selects the versioned scene and disables the shadow capture");
        new T040ShadowSceneDriverAgent.FixedDriver(p5203Config,
            expected -> { throw new IllegalStateException("5203 must never freeze"); }).run();
        final Properties p5203Result = ShadowPayloadStore.loadProperties(p5203.result);
        check(checks, "COMPLETE".equals(p5203Result.getProperty("collectionStatus"))
                && ShadowSceneContract.SCENE_5203.equals(p5203Result.getProperty("scene")),
            "the 5203 profile reaches COMPLETE without a shadow capture");
        final Properties p5203Payload = ShadowPayloadStore.loadProperties(
            p5203.home.resolve(ShadowSceneContract.OUTPUT_RELATIVE)
                .resolve(p5203.taskId).resolve(ShadowPayloadStore.PAYLOAD_NAME));
        check(checks, p5203Payload.stringPropertyNames().stream()
                .noneMatch(key -> key.startsWith("freeze."))
                && ShadowSceneContract.SCENE_5203.equals(p5203Payload.getProperty("scene"))
                && ShadowSceneContract.VERSION_5203.equals(p5203Payload.getProperty("profile")),
            "the 5203 payload records identity with no freeze keys");
        final Properties p5203Stage = ShadowPayloadStore.loadProperties(p5203.stage);
        check(checks, ShadowSceneContract.SCENE_5203.equals(p5203Stage.getProperty("scene"))
                && !"true".equals(p5203Stage.getProperty("freeze.started"))
                && "true".equals(p5203Stage.getProperty("canonical.complete")),
            "5203 evidence records the versioned scene and no freeze stage");
        disposeUiFixture(p5203);

        // The reviewed 5203 host opens a create-atlas settings form (新纹理集设置, one OK and
        // one Cancel) between the menu item and the editor when the model has no texture set;
        // the 5203 profile answers its OK once and continues to the same editor scene.
        f$b.reset();
        final Path createHome = Files.createTempDirectory("t040-ui-5203-create-");
        final UiFixture create = createUiFixture(createHome, false, false, true);
        new T040ShadowSceneDriverAgent.FixedDriver(
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(create.home, create.taskId,
                create.fixture, create.fixtureName, create.fixtureSha256, 20L,
                ShadowSceneContract.VERSION_5203,
                ShadowSceneContract.LAYOUT_SCALE_KERNEL_PERCENT,
                ShadowSceneContract.LAYOUT_MODE_PRESERVE),
            expected -> { throw new IllegalStateException("5203 must never freeze"); }).run();
        final Properties createResult = ShadowPayloadStore.loadProperties(create.result);
        check(checks, "COMPLETE".equals(createResult.getProperty("collectionStatus"))
                && f$b.OK_CLICKS.get() == 1,
            "the 5203 create-atlas form is answered once and the scene completes");
        disposeUiFixture(create);

        // The 5303 contract never produces that dialog, so the same form stays an
        // unexpected post-baseline window there and the run must fail closed.
        f$b.reset();
        final Path refusedHome = Files.createTempDirectory("t040-ui-5303-create-");
        final UiFixture refused = createUiFixture(refusedHome, false, false, true);
        new T040ShadowSceneDriverAgent.FixedDriver(
            T040ShadowSceneDriverAgent.DriverConfig.forSelfCheck(refused.home, refused.taskId,
                refused.fixture, refused.fixtureName, refused.fixtureSha256, 20L,
                ShadowSceneContract.VERSION_5303,
                ShadowSceneContract.LAYOUT_SCALE_KERNEL_PERCENT,
                ShadowSceneContract.LAYOUT_MODE_PRESERVE),
            expected -> goodFreeze(expected, "TRUNCATED")).run();
        final Properties refusedResult = ShadowPayloadStore.loadProperties(refused.result);
        check(checks, "FAILED".equals(refusedResult.getProperty("collectionStatus"))
                && f$b.OK_CLICKS.get() == 0,
            "the 5303 profile refuses the create-atlas form without answering it");
        disposeUiFixture(refused);
    }

    private static UiFixture createUiFixture(final Path home, final boolean prompt,
                                             final boolean duplicateOk) throws Exception {
        return createUiFixture(home, prompt, duplicateOk, false);
    }

    private static UiFixture createUiFixture(final Path home, final boolean prompt,
                                             final boolean duplicateOk,
                                             final boolean createFirst) throws Exception {
        final String taskId = "ui-t040-" + Math.abs(home.getFileName().toString().hashCode());
        final String fixtureName = taskId + "-" + ShadowSceneContract.FIXTURE_CIRCLE100_NAME;
        final Path fixture = home.resolve(fixtureName);
        Files.writeString(fixture, "synthetic-circle100", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        final String fixtureSha = sha256(fixture);
        final Path output = home.resolve(ShadowSceneContract.OUTPUT_RELATIVE);
        final Path run = output.resolve(taskId);
        // The driver, not the selfcheck fixture, must create and claim output/run directories.
        final AtomicInteger exitClicks = new AtomicInteger();
        final JFrame[] frameHolder = new JFrame[1];
        f$b.SHOW_SAVE_PROMPT = prompt;
        f$b.DUPLICATE_OK = duplicateOk;
        SwingUtilities.invokeAndWait(() -> {
            final JFrame frame = new JFrame("Circle100 " + fixtureName);
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            final JMenuBar bar = new JMenuBar();
            final JMenu modeling = new JMenu(ShadowSceneContract.MODELING_MENU);
            final JMenu texture = new JMenu(ShadowSceneContract.TEXTURE_MENU);
            final JMenuItem editor = new JMenuItem(ShadowSceneContract.EDIT_TEXTURE_SET);
            editor.addActionListener(event -> {
                if (!createFirst) {
                    new f$b(frame).setVisible(true);
                    return;
                }
                // Mirrors the 5.2 host: with no texture set the menu item first opens the
                // create-atlas settings form; its OK creates the set and opens the editor.
                final JDialog create = new JDialog(frame,
                    ShadowSceneContract.NEW_ATLAS_DIALOG_TITLE, Dialog.ModalityType.APPLICATION_MODAL);
                final JButton ok = new JButton(ShadowSceneContract.OK_BUTTON);
                final JButton cancel = new JButton(ShadowSceneContract.CANCEL_BUTTON);
                ok.addActionListener(e2 -> {
                    create.dispose();
                    // The host's OK handler returns before the editor window appears; the
                    // modal f$b stub would otherwise wedge the answering doClick on the EDT.
                    SwingUtilities.invokeLater(() -> new f$b(frame).setVisible(true));
                });
                cancel.addActionListener(e2 -> create.dispose());
                final JPanel row = new JPanel();
                row.add(ok);
                row.add(cancel);
                create.add(row);
                create.pack();
                // The host returns from the menu action before the form appears; showing it
                // synchronously here would wedge the dispatch await behind the modal pump.
                SwingUtilities.invokeLater(() -> create.setVisible(true));
            });
            texture.add(editor);
            modeling.add(texture);
            bar.add(modeling);
            final JMenu file = new JMenu("文件");
            final JMenuItem exit = new JMenuItem(ShadowSceneContract.EXIT_MENU);
            exit.addActionListener(event -> {
                exitClicks.incrementAndGet();
                f$b.showExitPromptIfConfigured(frame);
            });
            file.add(exit);
            bar.add(file);
            frame.setJMenuBar(bar);
            frame.setSize(420, 240);
            frame.setLocation(10, 10);
            frame.setVisible(true);
            frameHolder[0] = frame;
        });
        final Path stage = run.resolve("driver-stage.properties");
        return new UiFixture(home, taskId, fixture, fixtureName, fixtureSha,
            output.resolve(ShadowPayloadStore.RESULT_NAME), stage, frameHolder[0], exitClicks);
    }

    private static void disposeUiFixture(final UiFixture fixture) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            f$b.disposePrompt();
            f$b.disposeEditors();
            f$b.disposeStray();
            f$b.disposeProgress();
            f$b.disposeLayout();
            if (fixture.frame != null) fixture.frame.dispose();
        });
        Thread.sleep(100L);
    }

    private record UiFixture(Path home, String taskId, Path fixture, String fixtureName,
                             String fixtureSha256, Path result, Path stage, JFrame frame,
                             AtomicInteger exitClicks) {}

    private static Map<String, String> goodFreeze(final String runId, final String outcome) {
        final Map<String, String> values = new HashMap<>();
        values.put("schemaVersion", "1");
        values.put("runId", runId);
        values.put("profile", "5303");
        values.put("shadowMode", "shadow-ready");
        values.put("state", "SHADOW_READY");
        values.put("removalStatus", "REMOVED");
        values.put("transformerRegistered", "false");
        values.put("candidateReturnedCount", "1");
        values.put("frozen", "true");
        values.put("fullBoundsPreserved", "true");
        values.put("sampleOutcome", outcome);
        values.put("reason", "eligible");
        values.put("helperPrewarmed", "true");
        values.put("helperHash", "1".repeat(64));
        values.put("t038HelperPrewarmed", "true");
        values.put("t038HelperHash", "2".repeat(64));
        values.put("stats.recorded", "0");
        values.put("stats.dropped", "0");
        values.put("stats.admitted", "0");
        values.put("stats.rejected", "0");
        values.put("stats.aliased", "0");
        values.put("stats.potentialTrim", "0");
        values.put("stats.eventLimit", "64");
        values.put("stats.lastKx", "0");
        values.put("stats.lastKy", "0");
        values.put("stats.lastSourceWidth", "0");
        values.put("stats.lastSourceHeight", "0");
        values.put("stats.lastStride", "0");
        values.put("stats.lastFullWidth", "0");
        values.put("stats.lastFullHeight", "0");
        values.put("stats.lastPadding", "0");
        values.put("stats.lastSourceLength", "0");
        values.put("stats.lastDestinationLength", "0");
        values.put("stats.lastAliased", "false");
        values.put("stats.lastAdmitted", "false");
        values.put("stats.lastPotentialTrim", "false");
        values.put("stats.lastOptimizationRequested", "false");
        values.put("stats.targetEvents", "1");
        values.put("stats.lateCallbacks", "0");
        values.put("stats.candidateCount", "1");
        values.put("stats.candidateReturnedCount", "1");
        values.put("stats.rejectionCount", "0");
        values.put("stats.methodExecuted", "false");
        return values;
    }

    private static String sha256(final Path path) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        final StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void waitForEdt() throws InterruptedException {
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(completed::countDown);
        if (!completed.await(2L, TimeUnit.SECONDS)) throw new IllegalStateException("EDT did not drain");
    }

    private static void await(final CountDownLatch latch, final long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static void rejects(final AtomicInteger checks, final CheckedAction action) throws Exception {
        try {
            action.run();
        } catch (Exception expected) {
            checks.incrementAndGet();
            return;
        }
        throw new IllegalStateException("invalid input was accepted");
    }

    private static void check(final AtomicInteger checks, final boolean condition,
                              final String description) {
        if (!condition) throw new IllegalStateException("selfcheck failed: " + description);
        checks.incrementAndGet();
    }
}
