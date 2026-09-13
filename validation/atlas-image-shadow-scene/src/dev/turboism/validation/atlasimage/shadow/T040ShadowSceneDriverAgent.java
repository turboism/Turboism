package dev.turboism.validation.atlasimage.shadow;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.concurrent.Callable;
import javax.swing.JLabel;
import javax.swing.JTextField;

/** Fixed T040 UI driver; T039 is reached only through its fixed public freeze API. */
public final class T040ShadowSceneDriverAgent {
    private T040ShadowSceneDriverAgent() {}

    public static void premain(final String ignored, final Instrumentation ignoredInstrumentation) {
        try {
            final DriverConfig config = DriverConfig.fromSystemProperties();
            final Thread worker = new Thread(
                () -> new FixedDriver(config,
                    runId -> T039FreezeBridge.invokeReal(runId, config.profile)).run(),
                "atlas-image-shadow-fixed-driver");
            worker.setDaemon(true);
            worker.start();
            System.out.println("ATLAS_IMAGE_SHADOW_DRIVER_STARTED");
        } catch (Exception failure) {
            System.err.println("ATLAS_IMAGE_SHADOW_DRIVER_BLOCKED "
                + failure.getClass().getSimpleName());
        }
    }

    static final class DriverConfig {
        final Path home;
        final String taskId;
        final Path fixture;
        final String fixtureName;
        final String fixtureSha256;
        final long timeoutSeconds;
        final String version;
        final String scene;
        /** The 5303 profile carries the T039 shadow capture; 5203 has no T039 agent. */
        final boolean shadow;
        final String profile;
        final String layoutScalePercent;
        final String layoutMode;
        final long startupSeconds;
        final long closePollSeconds;
        final long layoutDialogSeconds;
        final long editorSettleSeconds;
        final boolean menuDump;
        final boolean exportProbe;
        final boolean exportAfterEditor;
        final boolean editorReopen;

        private DriverConfig(final Path home, final String taskId, final Path fixture,
                             final String fixtureName, final String fixtureSha256,
                             final long timeoutSeconds, final String version,
                             final String profile,
                             final String layoutScalePercent, final String layoutMode,
                             final long startupSeconds,
                             final long closePollSeconds, final long layoutDialogSeconds,
                             final long editorSettleSeconds, final boolean menuDump,
                             final boolean exportProbe, final boolean exportAfterEditor,
                             final boolean editorReopen) {
            this.home = home;
            this.taskId = taskId;
            this.fixture = fixture;
            this.fixtureName = fixtureName;
            this.fixtureSha256 = fixtureSha256;
            this.timeoutSeconds = timeoutSeconds;
            this.version = version;
            this.scene = ShadowSceneContract.sceneFor(version);
            this.shadow = ShadowSceneContract.VERSION_5303.equals(version);
            this.profile = profile;
            this.layoutScalePercent = layoutScalePercent;
            this.layoutMode = layoutMode;
            this.startupSeconds = startupSeconds;
            this.closePollSeconds = closePollSeconds;
            this.layoutDialogSeconds = layoutDialogSeconds;
            this.editorSettleSeconds = editorSettleSeconds;
            this.menuDump = menuDump;
            this.exportProbe = exportProbe;
            this.exportAfterEditor = exportAfterEditor;
            this.editorReopen = editorReopen;
        }

        static DriverConfig fromSystemProperties() throws Exception {
            final Path home = Path.of(ShadowSceneContract.requiredProperty("turboism.home"))
                .toAbsolutePath().normalize();
            final Path namedHome = Path.of(ShadowSceneContract.requiredProperty(
                ShadowSceneContract.NAMED_PREFIX + "home")).toAbsolutePath().normalize();
            if (!home.equals(namedHome)) throw new IllegalArgumentException("named home differs from turboism.home");
            if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("turboism.home is not a directory");
            }

            final String runId = ShadowSceneContract.requireTaskId(
                ShadowSceneContract.requiredProperty("turboism.validation.runId"), "runId");
            final String taskId = ShadowSceneContract.requireTaskId(
                ShadowSceneContract.requiredProperty(ShadowSceneContract.NAMED_PREFIX + "taskId"), "taskId");
            if (!runId.equals(taskId)) throw new IllegalArgumentException("taskId differs from runId");
            final String version = ShadowSceneContract.requireVersion(
                ShadowSceneContract.requiredProperty(ShadowSceneContract.NAMED_PREFIX + "version"));
            if (!version.equals(System.getProperty(
                    "turboism.validation.hostVersion", version))) {
                throw new IllegalArgumentException("scene version differs from hostVersion");
            }

            final String fixtureText = ShadowSceneContract.requiredProperty(
                ShadowSceneContract.NAMED_PREFIX + "fixture");

            final String fixtureName = ShadowSceneContract.requiredProperty(
                ShadowSceneContract.NAMED_PREFIX + "fixtureName");
            final Path fixture = Path.of(fixtureText).toAbsolutePath().normalize();
            if (!Files.isRegularFile(fixture, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("fixture is not a regular file");
            }
            final String fixtureSha256 = ShadowSceneContract.requireHash(
                ShadowSceneContract.requiredProperty(ShadowSceneContract.NAMED_PREFIX + "fixtureSha256"),
                "fixtureSha256");
            final String allowedFixtureName = ShadowSceneContract.requireAllowlistedFixture(
                version, taskId, fixtureName, fixtureSha256);
            ShadowSceneContract.requireNamedFixture(fixtureText, fixtureName, taskId,
                allowedFixtureName);
            if (!fixtureSha256.equals(sha256(fixture))) {
                throw new IllegalArgumentException("allowlisted fixture hash mismatch");
            }
            final String outputRelative = System.getProperty(
                ShadowSceneContract.NAMED_PREFIX + "outputRelative", ShadowSceneContract.OUTPUT_RELATIVE);
            if (!ShadowSceneContract.OUTPUT_RELATIVE.equals(outputRelative)) {
                throw new IllegalArgumentException("outputRelative is fixed to state/atlas-image-shadow");
            }
            final long timeoutSeconds = parseTimeout(System.getProperty(
                ShadowSceneContract.NAMED_PREFIX + "timeoutSeconds",
                Long.toString(ShadowSceneContract.DEFAULT_TIMEOUT_SECONDS)));

            final String profile;
            if (ShadowSceneContract.VERSION_5303.equals(version)) {
                final String t039Profile = ShadowSceneContract.requiredProperty(
                    ShadowSceneContract.T039_PREFIX + "profile");
                final String t039RunId = ShadowSceneContract.requiredProperty(
                    ShadowSceneContract.T039_PREFIX + "runId");
                ShadowSceneContract.requireT039FixedValues(
                    t039Profile, t039RunId, taskId,
                    ShadowSceneContract.requiredProperty(
                        ShadowSceneContract.T039_PREFIX + "sourceBinding"),
                    ShadowSceneContract.requiredProperty(
                        ShadowSceneContract.T039_PREFIX + "trustedSourcePaths"),
                    ShadowSceneContract.requiredProperty(ShadowSceneContract.T039_PREFIX + "jarSha256"),
                    ShadowSceneContract.requiredProperty(ShadowSceneContract.T039_PREFIX + "classSha256"),
                    ShadowSceneContract.requiredProperty(ShadowSceneContract.T039_PREFIX + "shapeSha256"),
                    ShadowSceneContract.requiredProperty(ShadowSceneContract.T039_PREFIX + "loaderClass"),
                    ShadowSceneContract.requiredProperty(ShadowSceneContract.T039_PREFIX + "helperSha256"),
                    ShadowSceneContract.requiredProperty(
                        ShadowSceneContract.T039_PREFIX + "t038HelperSha256"),
                    ShadowSceneContract.requiredProperty(ShadowSceneContract.T039_PREFIX + "shadowMode"),
                    ShadowSceneContract.requiredProperty(ShadowSceneContract.T039_PREFIX + "shadowOptIn"));
                profile = t039Profile;
            } else {
                ShadowSceneContract.requireT039Absent();
                profile = version;
            }
            return new DriverConfig(home, taskId, fixture, fixtureName, fixtureSha256,
                timeoutSeconds, version, profile, ShadowSceneContract.layoutScalePercent(),
                ShadowSceneContract.layoutMode(),
                ShadowSceneContract.secondsProperty(ShadowSceneContract.STARTUP_SECONDS_PROPERTY,
                    ShadowSceneContract.STARTUP_TIMEOUT_SECONDS),
                ShadowSceneContract.secondsProperty(ShadowSceneContract.CLOSE_POLL_SECONDS_PROPERTY,
                    ShadowSceneContract.CLOSE_POLL_TIMEOUT_SECONDS),
                ShadowSceneContract.secondsProperty(
                    ShadowSceneContract.LAYOUT_DIALOG_SECONDS_PROPERTY,
                    ShadowSceneContract.LAYOUT_DIALOG_TIMEOUT_SECONDS),
                ShadowSceneContract.settleSecondsProperty(
                    ShadowSceneContract.EDITOR_SETTLE_SECONDS_PROPERTY, 0L),
                ShadowSceneContract.booleanProperty(
                    ShadowSceneContract.MENU_DUMP_PROPERTY, false),
                ShadowSceneContract.booleanProperty(
                    ShadowSceneContract.EXPORT_PROBE_PROPERTY, false),
                ShadowSceneContract.booleanProperty(
                    ShadowSceneContract.EXPORT_AFTER_EDITOR_PROPERTY, false),
                ShadowSceneContract.booleanProperty(
                    ShadowSceneContract.EDITOR_REOPEN_PROPERTY, false));
        }

        static DriverConfig forSelfCheck(final Path home, final String taskId,
                                         final Path fixture, final String fixtureName,
                                         final String fixtureSha256, final long timeoutSeconds) {
            return forSelfCheck(home, taskId, fixture, fixtureName, fixtureSha256, timeoutSeconds,
                ShadowSceneContract.LAYOUT_SCALE_KERNEL_PERCENT);
        }

        static DriverConfig forSelfCheck(final Path home, final String taskId,
                                         final Path fixture, final String fixtureName,
                                         final String fixtureSha256, final long timeoutSeconds,
                                         final String layoutScalePercent,
                                         final String layoutMode) {
            return forSelfCheck(home, taskId, fixture, fixtureName, fixtureSha256,
                timeoutSeconds, ShadowSceneContract.VERSION_5303, layoutScalePercent,
                layoutMode);
        }

        static DriverConfig forSelfCheck(final Path home, final String taskId,
                                         final Path fixture, final String fixtureName,
                                         final String fixtureSha256, final long timeoutSeconds,
                                         final String version, final String layoutScalePercent,
                                         final String layoutMode) {
            return new DriverConfig(home, taskId, fixture, fixtureName, fixtureSha256,
                timeoutSeconds, ShadowSceneContract.requireVersion(version), version,
                ShadowSceneContract.requireLayoutScalePercent(layoutScalePercent),
                ShadowSceneContract.requireLayoutMode(layoutMode),
                ShadowSceneContract.STARTUP_TIMEOUT_SECONDS,
                ShadowSceneContract.CLOSE_POLL_TIMEOUT_SECONDS,
                ShadowSceneContract.LAYOUT_DIALOG_TIMEOUT_SECONDS, 0L, false, false, false,
                false);
        }

        static DriverConfig forSelfCheck(final Path home, final String taskId,
                                         final Path fixture, final String fixtureName,
                                         final String fixtureSha256, final long timeoutSeconds,
                                         final String layoutScalePercent) {
            return forSelfCheck(home, taskId, fixture, fixtureName, fixtureSha256,
                timeoutSeconds, ShadowSceneContract.VERSION_5303, layoutScalePercent,
                ShadowSceneContract.LAYOUT_MODE_AUTO_SCALE);
        }

        Path claimOutputRoot() throws Exception {
            final Path homeReal = secureHome();
            final Path relative = Path.of(ShadowSceneContract.OUTPUT_RELATIVE);
            if (relative.isAbsolute() || relative.getNameCount() == 0) {
                throw new IllegalArgumentException("shadow output path is not a fixed relative path");
            }
            Path current = homeReal;
            for (int index = 0; index < relative.getNameCount(); index++) {
                final Path next = current.resolve(relative.getName(index).toString()).normalize();
                if (!next.startsWith(homeReal)) throw new IllegalArgumentException("output escaped task home");
                if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(next) || !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalStateException("shadow output component is not a real directory");
                    }
                } else {
                    try {
                        Files.createDirectory(next);
                    } catch (FileAlreadyExistsException race) {
                        if (Files.isSymbolicLink(next) || !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IllegalStateException("shadow output component was claimed unsafely", race);
                        }
                    }
                }
                current = next;
            }
            final Path claimed = current.toRealPath();
            if (!claimed.startsWith(homeReal) || !claimed.equals(current)) {
                throw new IllegalStateException("shadow output resolved outside task home");
            }
            return claimed;
        }

        Path claimRunDirectory(final Path outputRoot) throws Exception {
            final Path homeReal = secureHome();
            final Path expectedRoot = homeReal.resolve(Path.of(ShadowSceneContract.OUTPUT_RELATIVE)).normalize();
            final Path suppliedRoot = outputRoot.toAbsolutePath().normalize();
            if (!suppliedRoot.equals(expectedRoot)) {
                throw new IllegalArgumentException("run claim root is not the fixed shadow output");
            }
            if (Files.isSymbolicLink(suppliedRoot)
                    || !Files.isDirectory(suppliedRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("run claim root is not a real directory");
            }
            final Path realRoot = suppliedRoot.toRealPath();
            if (!realRoot.equals(expectedRoot) || !realRoot.startsWith(homeReal)) {
                throw new IllegalStateException("run claim root escaped task home");
            }
            final Path candidate = realRoot.resolve(taskId).normalize();
            if (!candidate.startsWith(realRoot)) throw new IllegalArgumentException("run escaped output root");
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("shadow run already claimed");
            }
            try {
                Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException race) {
                throw new IllegalStateException("shadow run already claimed", race);
            }
            if (Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("claimed run is not a real directory");
            }
            final Path claimed = candidate.toRealPath();
            if (!claimed.equals(candidate) || !claimed.startsWith(realRoot)) {
                throw new IllegalStateException("claimed run escaped output root");
            }
            return claimed;
        }

        private Path secureHome() throws Exception {
            if (Files.isSymbolicLink(home) || !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("turboism.home is not a real directory");
            }
            return home.toRealPath();
        }

        private static long parseTimeout(final String value) {
            try {
                final long seconds = Long.parseLong(value);
                if (seconds < 1L || seconds > 3600L) throw new NumberFormatException("range");
                return seconds;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("timeoutSeconds must be an integer from 1 to 3600");
            }
        }
    }

    static final class FixedDriver {
        private final DriverConfig config;
        private final T039FreezeBridge.FreezeProvider freezeProvider;
        private Path outputRoot;
        private Path run;
        private Path result;
        private Window main;
        private StageEvidence evidence;
        private long runDeadlineNanos;
        private boolean canonicalComplete;
        private boolean canonicalAttempted;
        private final Set<Window> baselineWindows =
            Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Window> dumpedCloseDialogs =
            Collections.newSetFromMap(new IdentityHashMap<>());

        FixedDriver(final DriverConfig config, final T039FreezeBridge.FreezeProvider freezeProvider) {
            this.config = config;
            this.freezeProvider = freezeProvider;
        }

        void run() {
            runDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.timeoutSeconds);
            try {
                outputRoot = config.claimOutputRoot();
                run = config.claimRunDirectory(outputRoot);
                evidence = new StageEvidence(run.resolve("driver-stage.properties"),
                    config.scene, config.startupSeconds, config.closePollSeconds,
                    config.layoutDialogSeconds);
                result = outputRoot.resolve(ShadowPayloadStore.RESULT_NAME).normalize();
                if (Files.exists(result, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("canonical result already exists; refusing reuse");
                }
                if (Files.exists(run.resolve(ShadowPayloadStore.PAYLOAD_NAME), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("payload already exists; refusing duplicate run");
                }

                main = awaitHostReady();
                evidence.stage("MAIN", "READY");
                captureBaseline();
                if (config.menuDump) dumpMenuTree();
                if (config.exportProbe && !config.exportAfterEditor) probeExportDialog();
                final EditorMenuDispatch menuDispatch = new EditorMenuDispatch(main, evidence);
                menuDispatch.dispatch();
                evidence.stage("EDITOR", "REQUESTED");
                final Editor editor = waitForEditor(main);
                evidence.stage("EDITOR", "CONFIRMED");
                if (ShadowSceneContract.LAYOUT_MODE_PRESERVE.equals(config.layoutMode)) {
                    // The user path this represents: the texture set is loaded and kept as it is.
                    // No layout button, no dialog, no scale; the host's saved layout is preserved.
                    evidence.layoutPreserved();
                    evidence.stage("LAYOUT", "PRESERVED");
                } else {
                    final LayoutDispatch layoutDispatch =
                        new LayoutDispatch(editor, main, evidence, baselineWindows);
                    layoutDispatch.dispatch();
                    evidence.stage("LAYOUT", "REQUESTED");
                    final Window layoutDialog = waitForLayoutDialog(main, baselineWindows);
                    final LayoutApplyDispatch layoutApply =
                        new LayoutApplyDispatch(layoutDialog, evidence, config.layoutScalePercent);
                    layoutApply.dispatch();
                    waitForLayoutDialogClosed(layoutDialog);
                    layoutApply.awaitCompletion(remainingMillis(runDeadlineNanos));
                    layoutDispatch.awaitCompletion(remainingMillis(runDeadlineNanos));
                    evidence.stage("LAYOUT", "APPLIED");
                }
                if (config.editorSettleSeconds > 0) {
                    // Lazily-triggered atlas work (deferred per-page texture
                    // generation) needs wall-clock dwell before OK closes the
                    // editor; the driver thread sleeps while the EDT stays free.
                    Thread.sleep(TimeUnit.SECONDS.toMillis(config.editorSettleSeconds));
                    evidence.stage("EDITOR", "SETTLED");
                }
                final OkDispatch okDispatch = new OkDispatch(editor, main, evidence, baselineWindows);
                okDispatch.dispatch();
                evidence.stage("EDITOR", "OK_STARTED");
                waitForEditorClosed(editor, main);
                awaitOkCompletion(okDispatch);
                evidence.stage("EDITOR", "OK_COMPLETED");
                awaitMenuCompletion(menuDispatch);
                evidence.stage("EDITOR", "ACTION_COMPLETE");
                if (config.exportProbe && config.exportAfterEditor) probeExportDialog();
                if (config.editorReopen) {
                    // Second open→OK round-trip: the "user reopens the atlas" path. The
                    // host hands the editor fresh atlas instances (deep copies); whether
                    // the second open still rebuilds every page is exactly what the
                    // cache-reuse verdicts answer.
                    final EditorMenuDispatch reopenDispatch =
                        new EditorMenuDispatch(main, evidence);
                    reopenDispatch.dispatch();
                    evidence.stage("EDITOR2", "REQUESTED");
                    final Editor editor2 = waitForEditor(main);
                    evidence.stage("EDITOR2", "CONFIRMED");
                    final OkDispatch ok2 =
                        new OkDispatch(editor2, main, evidence, baselineWindows);
                    ok2.dispatch();
                    waitForEditorClosed(editor2, main);
                    awaitOkCompletion(ok2);
                    awaitMenuCompletion(reopenDispatch);
                    evidence.stage("EDITOR2", "ACTION_COMPLETE");
                }

                final Map<String, String> freeze;
                if (config.shadow) {
                    evidence.freezeStarted();
                    freeze = T039FreezeBridge.validateAndCopy(
                        freezeProvider.freeze(config.taskId), config.taskId, config.profile);
                    evidence.freezeCompleted();
                } else {
                    // The 5203 profile has no T039 agent; the payload records the run
                    // identity without any freeze.* keys.
                    freeze = Map.of();
                }
                evidence.payloadWriteStarted();
                final ShadowPayloadStore.Persisted payload = ShadowPayloadStore.persist(
                    run, config.taskId, config.scene, config.profile, config.fixtureName,
                    config.fixtureSha256, freeze);
                evidence.payloadWriteCompleted(payload.sha256());
                evidence.payloadReopenVerified();
                publishComplete(payload.sha256());
                requestNativeExit(main);
                evidence.stage("DONE", "COLLECTION_COMPLETE");
            } catch (Throwable failure) {
                if (evidence != null) evidence.failed(failureCode(failure));
                recordFailure(failure);
                publishFailureIfSafe(failure);
                System.err.println("ATLAS_IMAGE_SHADOW_DRIVER_BLOCKED "
                    + failure.getClass().getSimpleName());
            }
        }

        /**
         * The atlas auto-layout dialog is the host's own {@code APPLICATION_MODAL} window: opening it
         * blocks the EDT until its OK hides it again, so the scene posts the click and drives the
         * dialog from a second action. Its OK handler reads the scale control's own text field, so
         * the scene only has to place the fixed percentage below the downsampling kernel threshold.
         */
        private Window waitForLayoutDialog(final Window mainWindow, final Set<Window> baseline)
                throws Exception {
            final long deadline = Math.min(runDeadlineNanos, System.nanoTime()
                + TimeUnit.SECONDS.toNanos(config.layoutDialogSeconds));
            final long startedNanos = System.nanoTime();
            while (System.nanoTime() < deadline) {
                Window found;
                try {
                    found = FixedEdt.call(() -> findLayoutDialog(mainWindow, baseline),
                        FixedEdt.Operation.LAYOUT_LOOKUP, evidence);
                } catch (FixedEdt.Timeout timeout) {
                    if (timeout.operation != FixedEdt.Operation.LAYOUT_LOOKUP
                            || timeout.state != FixedEdt.State.TIMED_OUT) throw timeout;
                    evidence.stage("LAYOUT_LOOKUP", "QUEUED_TIMEOUT_CONTINUE");
                    sleep(Math.min(250L, remainingMillis(deadline)));
                    continue;
                }
                if (found != null) {
                    evidence.layoutDialog(true, elapsedMillis(startedNanos));
                    return found;
                }
                sleep(Math.min(250L, remainingMillis(deadline)));
            }
            evidence.layoutDialog(false, elapsedMillis(startedNanos));
            throw new IllegalStateException("atlas auto-layout dialog did not appear");
        }

        /** The posted open click returns once the modal dialog is hidden; the poll bounds that wait. */
        private void waitForLayoutDialogClosed(final Window dialog) throws Exception {
            final long deadline = Math.min(runDeadlineNanos, System.nanoTime()
                + TimeUnit.SECONDS.toNanos(config.layoutDialogSeconds));
            while (System.nanoTime() < deadline) {
                final boolean showing = FixedEdt.call(dialog::isShowing,
                    FixedEdt.Operation.LAYOUT_LOOKUP, evidence);
                if (!showing) {
                    evidence.layoutClosed(true);
                    return;
                }
                sleep(Math.min(250L, remainingMillis(deadline)));
            }
            evidence.layoutClosed(false);
            throw new IllegalStateException("atlas auto-layout dialog did not close");
        }


        /**
         * Fixture window plus host-readiness gate.
         *
         * <p>The driver starts at premain, while the real host still owns the EDT for its own
         * startup. Driving the scene then both loses the race and contaminates the measured
         * atlas work, so the fixture window must answer consecutive EDT round trips inside
         * {@code EDT_READY_ROUND_MILLIS} before any UI action is dispatched. A queued or slow
         * round proves the EDT is still saturated and resets the counter; only an unstarted
         * lookup timeout may be retried, never a started one.</p>
         */
        private Window awaitHostReady() throws Exception {
            final long startupDeadline = Math.min(runDeadlineNanos,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(config.startupSeconds));
            int readyRounds = 0;
            int slowRounds = 0;
            while (System.nanoTime() < startupDeadline) {
                Window found;
                final long roundStartedNanos = System.nanoTime();
                try {
                    found = FixedEdt.call(
                        () -> findMainWindow(config.fixtureName), FixedEdt.Operation.MAIN_LOOKUP, evidence);
                } catch (FixedEdt.Timeout timeout) {
                    if (timeout.operation != FixedEdt.Operation.MAIN_LOOKUP
                            || timeout.state != FixedEdt.State.TIMED_OUT) throw timeout;
                    evidence.stage("MAIN_LOOKUP", "QUEUED_TIMEOUT_CONTINUE");
                    readyRounds = 0;
                    slowRounds++;
                    continue;
                }
                final long roundMillis = elapsedMillis(roundStartedNanos);
                if (found == null) {
                    readyRounds = 0;
                    sleep(Math.min(250L, remainingMillis(startupDeadline)));
                    continue;
                }
                if (roundMillis > ShadowSceneContract.EDT_READY_ROUND_MILLIS) {
                    readyRounds = 0;
                    slowRounds++;
                    sleep(Math.min(250L, remainingMillis(startupDeadline)));
                    continue;
                }
                if (++readyRounds >= ShadowSceneContract.EDT_READY_ROUNDS) {
                    evidence.readiness(readyRounds, slowRounds, roundMillis);
                    return found;
                }
            }
            throw new IllegalStateException("fixture main window readiness timeout");
        }

        /**
         * Host windows that already show before the scene touches the UI. Cubism hosts its own
         * floating windows in plain {@code javax.swing.JDialog}s, so "any window that is not
         * main/editor" would flag the host's own furniture; only windows appearing after this
         * baseline can be a save prompt or another new blocking state.
         */
        private void captureBaseline() throws Exception {
            final WindowObservation sample = FixedEdt.call(
                () -> observeWindows(main, null, null), FixedEdt.Operation.MAIN_LOOKUP, evidence);
            FixedEdt.call(() -> {
                for (Window window : Window.getWindows()) {
                    if (window == main || !window.isShowing()) continue;
                    baselineWindows.add(window);
                }
                return null;
            }, FixedEdt.Operation.MAIN_LOOKUP, evidence);
            evidence.baseline(sample.blockingCount() + sample.toleratedCount(),
                mergeWindowClasses(sample.blockingClasses(), sample.toleratedClasses()));
        }

        /**
         * Read-only label discovery for the fixed contract. The host's localized menu
         * strings are not recoverable offline (obfuscated constant pools), so finding a
         * new scene action's real label — the export path — needs one instrumented run.
         * Enumeration is bounded by depth and line count and records structure only:
         * path index, class, label text, enabled/visible flags.
         */
        private void dumpMenuTree() throws Exception {
            final List<String> lines = FixedEdt.call(() -> {
                final List<String> out = new ArrayList<>();
                if (main instanceof JFrame frame && frame.getJMenuBar() != null) {
                    final MenuElement[] tops = frame.getJMenuBar().getSubElements();
                    for (int index = 0; index < tops.length; index++) {
                        walkMenu(tops[index], 0, Integer.toString(index), out);
                    }
                }
                return out;
            }, FixedEdt.Operation.MENU_DUMP, evidence);
            Files.write(run.resolve(ShadowSceneContract.MENU_DUMP_FILE), lines,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            evidence.stage("MENU", "DUMPED");
        }

        private static void walkMenu(final MenuElement element, final int depth,
                                     final String path, final List<String> out) {
            if (depth > ShadowSceneContract.MENU_DUMP_MAX_DEPTH
                    || out.size() >= ShadowSceneContract.MENU_DUMP_MAX_LINES) return;
            final Component component = element.getComponent();
            String text = "";
            if (component instanceof AbstractButton button && button.getText() != null) {
                text = button.getText();
            }
            out.add(depth + "|" + path + "|" + component.getClass().getName()
                + "|" + text.replace('|', ' ').replace('\n', ' ').replace('\r', ' ')
                + "|" + (component.isEnabled() ? "e" : "-") + (component.isVisible() ? "v" : "-"));
            final MenuElement[] children = element.getSubElements();
            for (int index = 0; index < children.length; index++) {
                walkMenu(children[index], depth + 1, path + "." + index, out);
            }
        }

        /**
         * Export-path observation. The host's moc3 export item starts the whole export
         * pipeline immediately: a modal progress window ({@code jp.noids.framework.e.a.f})
         * saturates the EDT for the entire processing phase — that phase is exactly the
         * per-image generation this validation measures. The probe clicks the item, then
         * watches the window set with EDT-timeout tolerance until the progress window
         * disappears, dumps whatever dialog follows (save-path or confirmation), and
         * dismisses it through a plain window-close event. No export is ever confirmed.
         */
        private void probeExportDialog() throws Exception {
            final PostedAction click = new PostedAction(this::clickExportMenu);
            click.dispatch(FixedEdt.Operation.EXPORT_PROBE, evidence);
            final long observeDeadline = Math.min(runDeadlineNanos, System.nanoTime()
                + TimeUnit.SECONDS.toNanos(ShadowSceneContract.EXPORT_OBSERVE_TIMEOUT_SECONDS));
            final Set<Window> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            int dumped = 0;
            boolean progressSeen = false;
            int quietPolls = 0;
            while (System.nanoTime() < observeDeadline) {
                Window dialog;
                try {
                    dialog = FixedEdt.call(() -> {
                        for (Window window : Window.getWindows()) {
                            if (window == main || baselineWindows.contains(window)
                                    || !window.isShowing() || seen.contains(window)) continue;
                            if (window instanceof Dialog) return window;
                        }
                        return null;
                    }, FixedEdt.Operation.EXPORT_LOOKUP, evidence);
                } catch (FixedEdt.Timeout saturated) {
                    // Export processing starves the EDT; a timed-out observation is
                    // expected mid-flight, not a scene failure.
                    evidence.stage("EXPORT", "EDT_SATURATED_CONTINUE");
                    sleep(Math.min(500L, remainingMillis(observeDeadline)));
                    continue;
                }
                if (dialog != null) {
                    quietPolls = 0;
                    seen.add(dialog);
                    final boolean progress = ShadowSceneContract.EXPORT_PROGRESS_CLASS
                        .equals(dialog.getClass().getName());
                    progressSeen |= progress;
                    evidence.stage("EXPORT", progress ? "PROGRESS_SEEN" : "DIALOG_SEEN");
                    dumpWindowTree(dialog, "export-dialog-" + (++dumped) + ".txt");
                    if (!progress) {
                        // A non-progress dialog after processing is the save/confirm
                        // step; dismiss it like a user closing the window. More dialogs
                        // can follow (warnings, then the export settings form), so keep
                        // observing instead of leaving them to block later stages.
                        final long dismissDeadline = Math.min(observeDeadline,
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(
                                ShadowSceneContract.EXPORT_DIALOG_TIMEOUT_SECONDS));
                        dismissWindow(dialog, dismissDeadline);
                    }
                } else if (progressSeen) {
                    // The export's trailing dialogs (warnings, settings form) can appear a
                    // beat after the progress window closes; only a sustained quiet set
                    // means the pipeline is done.
                    if (++quietPolls >= ShadowSceneContract.EXPORT_QUIET_POLLS) break;
                }
                sleep(Math.min(500L, remainingMillis(observeDeadline)));
            }
            if (!progressSeen && seen.isEmpty()) {
                throw new IllegalStateException("export produced no observable window");
            }
            try {
                click.awaitFinished(FixedEdt.Operation.EXPORT_PROBE, evidence,
                    Math.min(60_000L, remainingMillis(runDeadlineNanos)));
            } catch (FixedEdt.Timeout stillModal) {
                evidence.stage("EXPORT", "ACTION_STILL_MODAL");
            }
            evidence.stage("EXPORT", "OBSERVED");
        }

        /**
         * Bounded diagnostics while the close poll is blocked: each blocking modal dialog's
         * component tree is dumped once, so a wedge leaves the blocking shape on disk instead
         * of only a class name. Capped like the export dumps; a dialog that survives dismissal
         * attempts is never re-dumped.
         */
        private void dumpBlockingDialogsOnce(final Window mainWindow, final Window editorWindow) {
            dumpUnexpectedDialogsOnce(mainWindow, editorWindow, "close-dialog-");
        }

        /**
         * Same bounded dump as the close-poll variant but usable from any stage: every
         * showing non-baseline dialog not already dumped is captured once under the given
         * filename prefix. The dumped-window set is shared across stages so a dialog is
         * never dumped twice, and the total count stays under CLOSE_DIALOG_DUMP_MAX.
         */
        private void dumpUnexpectedDialogsOnce(final Window mainWindow, final Window ignored,
                final String prefix) {
            if (dumpedCloseDialogs.size() >= ShadowSceneContract.CLOSE_DIALOG_DUMP_MAX) return;
            try {
                FixedEdt.call(() -> {
                    for (Window window : Window.getWindows()) {
                        if (window == mainWindow || window == ignored || !window.isShowing()
                                || baselineWindows.contains(window)
                                || dumpedCloseDialogs.contains(window)) continue;
                        if (window instanceof Dialog dialog) {
                            dumpedCloseDialogs.add(window);
                            dumpWindowTree(dialog, prefix + dumpedCloseDialogs.size() + ".txt");
                        }
                    }
                    return null;
                }, FixedEdt.Operation.EDITOR_CLOSE_POLL, evidence);
            } catch (FixedEdt.Timeout queued) {
                // Observation-only path; a busy EDT just defers the dump to the next poll.
            } catch (Throwable dumpFailure) {
                // Diagnostics must never fail the poll itself.
            }
        }

        private void dumpWindowTree(final Window window, final String fileName) {
            try {
                final List<String> lines = FixedEdt.call(() -> {
                    final List<String> out = new ArrayList<>();
                    walkComponents(window, 0, out);
                    return out;
                }, FixedEdt.Operation.EXPORT_LOOKUP, evidence);
                Files.write(run.resolve(fileName), lines,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (Exception dumpFailure) {
                evidence.stage("EXPORT", "DUMP_FAILED_" + dumpFailure.getClass().getSimpleName());
            }
        }

        private void dismissWindow(final Window window, final long deadlineNanos)
                throws Exception {
            try {
                FixedEdt.call(() -> {
                    window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
                    return null;
                }, FixedEdt.Operation.EXPORT_LOOKUP, evidence);
            } catch (FixedEdt.Timeout saturated) {
                evidence.stage("EXPORT", "DISPATCH_TIMEOUT");
            }
            while (System.nanoTime() < deadlineNanos) {
                final boolean showing;
                try {
                    showing = FixedEdt.call(window::isShowing,
                        FixedEdt.Operation.EXPORT_LOOKUP, evidence);
                } catch (FixedEdt.Timeout saturated) {
                    sleep(Math.min(500L, remainingMillis(deadlineNanos)));
                    continue;
                }
                if (!showing) return;
                sleep(Math.min(250L, remainingMillis(deadlineNanos)));
            }
            evidence.stage("EXPORT", "DISMISS_TIMEOUT_CONTINUE");
        }

        private Void clickExportMenu() {
            if (!(main instanceof JFrame frame)) throw new IllegalStateException("main window is not JFrame");
            final JMenuBar bar = frame.getJMenuBar();
            if (bar == null) throw new IllegalStateException("main window has no menu bar");
            final JMenu file = uniqueMenu(bar.getComponents(), ShadowSceneContract.FILE_MENU,
                "file menu");
            final JMenu runtime = uniqueMenu(file.getMenuComponents(),
                ShadowSceneContract.RUNTIME_EXPORT_MENU, "runtime export submenu");
            final JMenuItem export = uniqueItem(runtime.getMenuComponents(),
                ShadowSceneContract.EXPORT_MOC3_ITEM, "moc3 export menu item");
            if (!export.isEnabled() || !export.isVisible()) {
                throw new IllegalStateException("moc3 export menu item is not enabled");
            }
            final MenuElement[] path = {bar, file, file.getPopupMenu(), runtime,
                runtime.getPopupMenu(), export};
            MenuSelectionManager.defaultManager().setSelectedPath(path);
            try {
                export.doClick(50);
            } finally {
                MenuSelectionManager.defaultManager().clearSelectedPath();
            }
            return null;
        }



        private static void walkComponents(final Component component, final int depth,
                                           final List<String> out) {
            if (depth > ShadowSceneContract.EXPORT_DUMP_MAX_DEPTH
                    || out.size() >= ShadowSceneContract.EXPORT_DUMP_MAX_LINES) return;
            final StringBuilder line = new StringBuilder()
                .append(depth).append('|').append(component.getClass().getName()).append('|')
                .append(component.getX()).append(',').append(component.getY()).append(',')
                .append(component.getWidth()).append('x').append(component.getHeight()).append('|');
            if (component instanceof AbstractButton button) {
                line.append("text=").append(sanitize(button.getText()))
                    .append(button.isSelected() ? ",sel" : "");
            } else if (component instanceof JLabel label) {
                line.append("text=").append(sanitize(label.getText()));
            } else if (component instanceof JTextField field) {
                line.append("text=").append(sanitize(field.getText()))
                    .append(field.isEditable() ? ",editable" : ",readonly");
            }
            line.append('|')
                .append(component.isEnabled() ? 'e' : '-')
                .append(component.isVisible() ? 'v' : '-');
            out.add(line.toString());
            if (component instanceof Container container) {
                for (Component child : container.getComponents()) {
                    walkComponents(child, depth + 1, out);
                }
            }
        }

        private static String sanitize(final String text) {
            return text == null ? "" : text.replace('|', ' ').replace('\n', ' ').replace('\r', ' ');
        }

        /**
         * Best-effort native exit, observed instead of awaited.
         *
         * <p>The run is already published COMPLETE at this point, so the exit is host lifecycle:
         * the JVM may terminate inside the host's own exit action (no completion signal can ever
         * arrive), and the queue independently proves a graceful launcher exit and owns containment.
         * A click that does not return therefore must not turn a finished collection into a scene
         * failure. What the driver still owes is evidence: which window the host raises while it
         * shuts down, and which non-daemon threads are still alive, so a stuck exit is diagnosable
         * by structure instead of by guesswork.</p>
         */
        private void requestNativeExit(final Window mainWindow) {
            evidence.nativeExitStarted();
            final long postedNanos = System.nanoTime();
            final FixedEdt.Invocation<Void> exit = new FixedEdt.Invocation<>(() -> {
                if (!(mainWindow instanceof JFrame frame)) {
                    throw new IllegalStateException("main window is not JFrame");
                }
                final JMenuBar bar = frame.getJMenuBar();
                if (bar == null) throw new IllegalStateException("main window has no menu bar");
                final List<JMenuItem> exits = new ArrayList<>();
                collectItems(bar, ShadowSceneContract.EXIT_MENU, exits,
                    new IdentityHashMap<>());
                if (exits.size() != 1) {
                    throw new IllegalStateException("expected exactly one native exit menu item");
                }
                final JMenuItem item = exits.get(0);
                if (!item.isEnabled() || !item.isVisible()) {
                    throw new IllegalStateException("native exit menu item is not enabled");
                }
                item.doClick(50);
                return null;
            });
            FixedEdt.post(exit, FixedEdt.Operation.NATIVE_EXIT, evidence, postedNanos);
            try {
                FixedEdt.awaitStarted(exit, FixedEdt.Operation.NATIVE_EXIT, evidence);
            } catch (Throwable notStarted) {
                // The host may already own the EDT with its own exit sequence; the posted click and
                // its queue state stay visible in the onEdt.* evidence.
            }

            int samples = 0;
            int windowCount = 0;
            boolean promptAnswered = false;
            final Set<String> exitWindowClasses = new LinkedHashSet<>();
            final long probeDeadline = Math.min(runDeadlineNanos,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(ShadowSceneContract.EXIT_PROBE_SECONDS));
            while (System.nanoTime() < probeDeadline) {
                if (!promptAnswered) {
                    try {
                        final ExitPrompt prompt =
                            answerExitPrompt(mainWindow, baselineWindows, evidence);
                        if (prompt != null) {
                            evidence.exitPrompt(true, prompt.answer, prompt.buttonCount,
                                prompt.classes, elapsedMillis(postedNanos));
                            promptAnswered = true;
                        }
                    } catch (Throwable ignored) {
                        // Answering the host's own prompt is best effort: an unreadable prompt must
                        // not fail an already published run, and the probe keeps observing it.
                    }
                }
                WindowObservation sample = null;
                try {
                    sample = FixedEdt.call(() -> observeWindows(mainWindow, null, baselineWindows),
                        FixedEdt.Operation.EDITOR_CLOSE_POLL, evidence);
                } catch (FixedEdt.Timeout queued) {
                    // The host owns the EDT inside its own exit sequence; that is exactly what the
                    // exit thread snapshot below is for.
                } catch (Throwable ignored) {
                    // Observation only: nothing here may fail an already published run.
                }
                if (sample != null) {
                    samples++;
                    windowCount = Math.max(windowCount, sample.blockingCount() + sample.toleratedCount());
                    exitWindowClasses.addAll(splitClasses(sample.blockingClasses()));
                    exitWindowClasses.addAll(splitClasses(sample.toleratedClasses()));
                }
                if (exit.state() != FixedEdt.State.STARTED) break;
                try {
                    sleep(ShadowSceneContract.CLOSE_POLL_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            final ThreadSnapshot threads = threadSnapshot();
            if (!promptAnswered) {
                evidence.exitPrompt(false, "NONE", 0, "NONE", elapsedMillis(postedNanos));
            }
            evidence.nativeExitProbe(exit.state() == FixedEdt.State.COMPLETED,
                elapsedMillis(postedNanos), samples, windowCount, joinClasses(exitWindowClasses),
                threads.count, threads.names);
        }

        private void awaitOkCompletion(final OkDispatch dispatch) throws Exception {
            evidence.okActionWaitStarted();
            final long started = System.nanoTime();
            try {
                dispatch.awaitCompletion(remainingMillis(runDeadlineNanos));
                evidence.okActionWaitEnded(elapsedMillis(started), true);
            } catch (Throwable failure) {
                evidence.okActionWaitEnded(elapsedMillis(started), false);
                throw failure;
            }
        }

        /**
         * Wait for a quiet window set after OK. The host applies the texture set asynchronously and
         * shows its own modal progress window ({@code jp.noids.framework.e.a.f}) while it works, so
         * failing on first sight would block every real run. A modal dialog or a still-showing
         * editor keeps the poll going; only a quiet set proceeds, and the budget bounds a run that
         * never finishes. Nothing is ever published while a modal window is up.
         */
        private void waitForEditorClosed(final Editor editor, final Window mainWindow) throws Exception {
            evidence.editorClosePollStarted();
            final long pollDeadline = Math.min(runDeadlineNanos, System.nanoTime()
                + TimeUnit.SECONDS.toNanos(config.closePollSeconds));
            final long pollStartedNanos = System.nanoTime();
            long polls = 0L;
            while (System.nanoTime() < pollDeadline) {
                WindowObservation observation;
                try {
                    observation = FixedEdt.call(
                        () -> observeWindows(mainWindow, editor.window, baselineWindows),
                        FixedEdt.Operation.EDITOR_CLOSE_POLL, evidence);
                } catch (FixedEdt.Timeout timeout) {
                    // The host owns the EDT while it applies the texture set; a poll that never
                    // started is retried, a started one is not cancellable.
                    if (timeout.operation != FixedEdt.Operation.EDITOR_CLOSE_POLL
                            || timeout.state != FixedEdt.State.TIMED_OUT) throw timeout;
                    evidence.editorClosePoll(polls, false, false, false, 0, "NONE", 0, "NONE",
                        elapsedMillis(pollStartedNanos));
                    sleep(ShadowSceneContract.CLOSE_POLL_INTERVAL_MILLIS);
                    continue;
                }
                polls++;
                evidence.editorClosePoll(polls, observation.closed(), observation.editorVisible(),
                    observation.blocked(), observation.blockingCount(), observation.blockingClasses(),
                    observation.toleratedCount(), observation.toleratedClasses(),
                    elapsedMillis(pollStartedNanos));
                if (observation.closed()) return;
                if (observation.blocked()) {
                    // The host can raise acknowledge-only warnings (single option pane, single
                    // button - for example the mask-permutation notice) while applying the texture
                    // set. A user would click the one button; anything carrying a real choice is
                    // never answered here and keeps blocking until the poll budget expires.
                    try {
                        final int dismissed = FixedEdt.call(
                            () -> dismissAcknowledgeOnlyDialogs(mainWindow, editor.window,
                                baselineWindows),
                            FixedEdt.Operation.EDITOR_CLOSE_POLL, evidence);
                        if (dismissed > 0) {
                            evidence.stage("EDITOR_CLOSE", "ACKNOWLEDGED_" + dismissed);
                        }
                    } catch (FixedEdt.Timeout queued) {
                        // Same posture as a timed-out observation: the EDT is busy, retry next poll.
                    }
                    dumpBlockingDialogsOnce(mainWindow, editor.window);
                }
                sleep(ShadowSceneContract.CLOSE_POLL_INTERVAL_MILLIS);
            }
            evidence.editorClosePollExpired(elapsedMillis(pollStartedNanos));
            throw new IllegalStateException("OK did not close the editor inside the close-poll budget");
        }

        private void awaitMenuCompletion(final EditorMenuDispatch dispatch) throws Exception {
            evidence.editorActionWaitStarted();
            final long started = System.nanoTime();
            try {
                dispatch.awaitCompletion();
                evidence.editorActionWaitEnded(elapsedMillis(started), true);
            } catch (Throwable failure) {
                evidence.editorActionWaitEnded(elapsedMillis(started), false);
                throw failure;
            }
        }

        private Editor waitForEditor(final Window mainWindow) throws Exception {
            final boolean[] newAtlasAnswered = new boolean[1];
            while (!expired()) {
                Editor found;
                final boolean wasAnswered = newAtlasAnswered[0];
                try {
                    found = FixedEdt.call(() -> {
                        // On profiles whose host asks to create the texture set first, answer
                        // that form once and keep polling; while it is still closing the
                        // editor simply is not up yet, so this poll reports "not found".
                        if (ShadowSceneContract.allowsNewAtlasDialog(config.profile)
                                && answerNewAtlasDialog(mainWindow, baselineWindows,
                                    newAtlasAnswered)) {
                            return null;
                        }
                        return findEditorWindow(mainWindow, baselineWindows);
                    }, FixedEdt.Operation.EDITOR_LOOKUP, evidence);
                } catch (FixedEdt.Timeout timeout) {
                    // Only a lookup that never started is retried inside the run deadline.
                    if (timeout.operation != FixedEdt.Operation.EDITOR_LOOKUP
                            || timeout.state != FixedEdt.State.TIMED_OUT) throw timeout;
                    evidence.stage("EDITOR_LOOKUP", "QUEUED_TIMEOUT_CONTINUE");
                    continue;
                } catch (IllegalStateException lookupFailure) {
                    // Leave the offending dialog's component tree on disk before failing:
                    // version differences surface here as dialogs the scene contract does
                    // not know (e.g. a create-atlas settings form on hosts that ask first).
                    dumpUnexpectedDialogsOnce(mainWindow, null, "lookup-dialog-");
                    throw lookupFailure;
                }
                if (!wasAnswered && newAtlasAnswered[0]) {
                    evidence.stage("EDITOR_LOOKUP", "NEW_ATLAS_ANSWERED");
                }
                if (found != null) return found;
                sleep(200L);
            }
            throw new IllegalStateException("unique atlas editor window timeout");
        }

        private void publishComplete(final String payloadSha256) throws Exception {
            if (canonicalAttempted) throw new IllegalStateException("canonical publication already attempted");
            canonicalAttempted = true;
            evidence.canonicalWriteStarted();
            ShadowPayloadStore.publishComplete(result, config.taskId, config.scene, payloadSha256);
            canonicalComplete = true;
            evidence.canonicalComplete();
        }

        private void publishFailureIfSafe(final Throwable failure) {
            if (canonicalComplete || outputRoot == null || result == null) return;
            if (Files.exists(result, LinkOption.NOFOLLOW_LINKS)) return;
            try {
                canonicalAttempted = true;
                if (evidence != null) evidence.canonicalWriteStarted();
                ShadowPayloadStore.publishFailed(result, config.taskId, config.scene,
                    failureCode(failure));
                if (evidence != null) evidence.canonicalFailed();
            } catch (Exception ignored) {
                // A failed publication means no COMPLETE is claimed; the manager owns missing-result handling.
            }
        }

        private void recordFailure(final Throwable failure) {
            if (run == null) return;
            final Path target = run.resolve("driver-error.properties");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
            // The bounded message names which contract gate refused; it carries no model
            // data (the fixed messages never interpolate user content), so recording it
            // changes no evidence scope.
            String message = failure.getMessage();
            if (message == null) message = "NONE";
            if (message.length() > 160) message = message.substring(0, 160);
            message = message.replace('\n', ' ').replace('\r', ' ');
            final String text = "scene=" + config.scene + "\n"
                + "state=" + failureCode(failure) + "\n"
                + "error=" + failure.getClass().getName() + "\n"
                + "errorMessage=" + message + "\n";
            try {
                Files.writeString(target, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (Exception ignored) {
                // Result/timeout and cleanup remain manager-owned when evidence cannot be written.
            }
        }

        private boolean expired() {
            return System.nanoTime() >= runDeadlineNanos;
        }

        private long remainingMillis(final long deadline) {
            final long nanos = Math.max(1L, deadline - System.nanoTime());
            return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nanos));
        }

        private void sleep(final long millis) throws InterruptedException {
            Thread.sleep(Math.max(1L, millis));
        }
    }

    /**
     * Bounded close-poll observation. {@code blockingClasses} and {@code toleratedClasses} each carry
     * at most {@link ShadowSceneContract#WINDOW_SAMPLE_MAX} sanitised, truncated window class names:
     * the block reason must be diagnosable without ever recording titles or model content.
     */
    private record WindowObservation(boolean editorVisible, int blockingCount, String blockingClasses,
                                     int toleratedCount, String toleratedClasses) {
        boolean blocked() {
            return blockingCount > 0;
        }

        boolean closed() {
            return !editorVisible && !blocked();
        }
    }

    private static final class Editor {
        final Window window;
        final AbstractButton ok;

        Editor(final Window window, final AbstractButton ok) {
            this.window = window;
            this.ok = ok;
        }
    }

    /** Menu dispatch releases the driver before the modal doClick and keeps completion separate. */
    static final class EditorMenuDispatch {
        private final Window mainWindow;
        private final StageEvidence evidence;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean clickStarted = new AtomicBoolean();
        private final CountDownLatch actionFinished = new CountDownLatch(1);
        private final FixedEdt.Invocation<Void> invocation = new FixedEdt.Invocation<>(this::click);
        private final long createdNanos = System.nanoTime();

        EditorMenuDispatch(final Window mainWindow, final StageEvidence evidence) {
            this.mainWindow = mainWindow;
            this.evidence = evidence;
        }

        void dispatch() throws Exception {
            if (evidence != null) evidence.editorDispatchStarted();
            try {
                SwingUtilities.invokeLater(() -> {
                    if (!invocation.tryStart()) {
                        if (evidence != null) evidence.onEdtLateSkipped(FixedEdt.Operation.EDITOR_MENU_DISPATCH);
                        invocation.skipAfterTimeout();
                        actionFinished.countDown();
                        return;
                    }
                    if (evidence != null) evidence.onEdtStarted(FixedEdt.Operation.EDITOR_MENU_DISPATCH);
                    try {
                        invocation.executeStarted();
                        final Throwable caught = invocation.failure();
                        if (caught != null) failure.compareAndSet(null, caught);
                        if (evidence != null) {
                            evidence.onEdtEnded(FixedEdt.Operation.EDITOR_MENU_DISPATCH,
                                FixedEdt.State.COMPLETED,
                                caught == null ? "COMPLETED" : "EXCEPTION",
                                elapsedMillis(createdNanos));
                        }
                    } finally {
                        invocation.completeStarted();
                        actionFinished.countDown();
                    }
                });
            } catch (Throwable caught) {
                failure.compareAndSet(null, caught);
                invocation.failBeforeDispatch(caught);
                actionFinished.countDown();
                FixedEdt.throwFailure(caught, "editor menu request");
                return;
            }
            FixedEdt.awaitStarted(invocation, FixedEdt.Operation.EDITOR_MENU_DISPATCH, evidence);
            if (evidence != null) evidence.editorDispatchEnded(elapsedMillis(createdNanos), true);
            FixedEdt.throwFailure(failure.get(), "editor menu request");
        }

        void awaitCompletion() throws Exception {
            try {
                if (!actionFinished.await(ShadowSceneContract.MODAL_ACTION_LATCH_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS)) {
                    final FixedEdt.State state = invocation.timeoutIfQueued();
                    throw new FixedEdt.Timeout(FixedEdt.Operation.EDITOR_MENU_DISPATCH, state);
                }
            } catch (InterruptedException interrupted) {
                final FixedEdt.State state = invocation.timeoutIfQueued();
                if (evidence != null) evidence.onEdtWaitInterrupted(
                    FixedEdt.Operation.EDITOR_MENU_DISPATCH, state);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("editor menu action interrupted state=" + state, interrupted);
            }
            FixedEdt.throwFailure(failure.get(), "editor menu action");
        }

        private Void click() {
            if (!(mainWindow instanceof JFrame frame)) throw new IllegalStateException("main window is not JFrame");
            final JMenuBar bar = frame.getJMenuBar();
            if (bar == null) throw new IllegalStateException("main window has no menu bar");
            final JMenu modeling = uniqueMenu(bar.getComponents(), ShadowSceneContract.MODELING_MENU,
                "top menu");
            final JMenu texture = uniqueMenu(modeling.getMenuComponents(), ShadowSceneContract.TEXTURE_MENU,
                "texture submenu");
            final JMenuItem editor = uniqueItem(texture.getMenuComponents(),
                ShadowSceneContract.EDIT_TEXTURE_SET, "texture editor menu item");
            if (!editor.isEnabled() || !editor.isVisible()) {
                throw new IllegalStateException("texture editor menu item is not enabled");
            }
            final MenuElement[] path = {bar, modeling, modeling.getPopupMenu(), texture,
                texture.getPopupMenu(), editor};
            MenuSelectionManager.defaultManager().setSelectedPath(path);
            clickStarted.set(true);
            // This latch is intentionally signalled before synchronous modal doClick.
            invocation.signalStarted();
            try {
                editor.doClick(50);
            } finally {
                // The selected path shows two heavy-weight popups of our own making; clearing it
                // keeps the host's window set free of driver artifacts during the close poll.
                MenuSelectionManager.defaultManager().clearSelectedPath();
            }
            return null;
        }

        boolean clickStarted() {
            return clickStarted.get();
        }
    }

    /**
     * OK dispatch mirrors the menu protocol. The real host runs the atlas edit apply inside this
     * click, so the driver must not charge that host work to the fixed query budget: the click is
     * only posted, the modal editor close is observed by polling, and the action completion is
     * awaited afterwards inside the caller's remaining run budget.
     */
    static final class OkDispatch {
        private final Editor expected;
        private final Window mainWindow;
        private final Set<Window> baseline;
        private final StageEvidence evidence;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch actionFinished = new CountDownLatch(1);
        private final FixedEdt.Invocation<Void> invocation = new FixedEdt.Invocation<>(this::click);
        private final long createdNanos = System.nanoTime();

        OkDispatch(final Editor expected, final Window mainWindow, final StageEvidence evidence,
                   final Set<Window> baseline) {
            this.expected = expected;
            this.mainWindow = mainWindow;
            this.evidence = evidence;
            this.baseline = baseline;
        }

        void dispatch() throws Exception {
            if (evidence != null) evidence.okDispatchStarted();
            try {
                SwingUtilities.invokeLater(() -> {
                    if (!invocation.tryStart()) {
                        if (evidence != null) evidence.onEdtLateSkipped(FixedEdt.Operation.OK_BUTTON);
                        invocation.skipAfterTimeout();
                        actionFinished.countDown();
                        return;
                    }
                    if (evidence != null) evidence.onEdtStarted(FixedEdt.Operation.OK_BUTTON);
                    try {
                        invocation.executeStarted();
                        final Throwable caught = invocation.failure();
                        if (caught != null) failure.compareAndSet(null, caught);
                        if (evidence != null) {
                            evidence.onEdtEnded(FixedEdt.Operation.OK_BUTTON,
                                FixedEdt.State.COMPLETED,
                                caught == null ? "COMPLETED" : "EXCEPTION",
                                elapsedMillis(createdNanos));
                        }
                    } finally {
                        invocation.completeStarted();
                        actionFinished.countDown();
                    }
                });
            } catch (Throwable caught) {
                failure.compareAndSet(null, caught);
                invocation.failBeforeDispatch(caught);
                actionFinished.countDown();
                FixedEdt.throwFailure(caught, "editor OK request");
                return;
            }
            FixedEdt.awaitStarted(invocation, FixedEdt.Operation.OK_BUTTON, evidence);
            if (evidence != null) evidence.okDispatchEnded(elapsedMillis(createdNanos), true);
            FixedEdt.throwFailure(failure.get(), "editor OK request");
        }

        void awaitCompletion(final long budgetMillis) throws Exception {
            try {
                if (!actionFinished.await(Math.max(1L, budgetMillis), TimeUnit.MILLISECONDS)) {
                    final FixedEdt.State state = invocation.timeoutIfQueued();
                    if (evidence != null) evidence.onEdtWaitTimedOut(FixedEdt.Operation.OK_BUTTON, state);
                    throw new FixedEdt.Timeout(FixedEdt.Operation.OK_BUTTON, state);
                }
            } catch (InterruptedException interrupted) {
                final FixedEdt.State state = invocation.timeoutIfQueued();
                if (evidence != null) evidence.onEdtWaitInterrupted(FixedEdt.Operation.OK_BUTTON, state);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("editor OK action interrupted state=" + state, interrupted);
            }
            FixedEdt.throwFailure(failure.get(), "editor OK action");
        }

        private Void click() {
            final Editor current = findEditorWindow(mainWindow, baseline);
            if (current == null || current.window != expected.window) {
                throw new IllegalStateException("editor changed before OK");
            }
            // This latch is intentionally signalled before the synchronous host apply.
            invocation.signalStarted();
            current.ok.doClick(50);
            return null;
        }
    }

    /** One posted EDT action; the fixed scene keeps queue wait and action budget apart. */
    private static final class PostedAction {
        private final FixedEdt.Invocation<Void> invocation;
        private final long createdNanos = System.nanoTime();

        PostedAction(final Callable<Void> action) {
            this.invocation = new FixedEdt.Invocation<>(action);
        }

        void dispatch(final FixedEdt.Operation operation, final StageEvidence evidence)
                throws Exception {
            FixedEdt.post(invocation, operation, evidence, createdNanos);
            FixedEdt.awaitStarted(invocation, operation, evidence);
        }

        void awaitFinished(final FixedEdt.Operation operation, final StageEvidence evidence,
                           final long millis) throws Exception {
            FixedEdt.awaitCompleted(invocation, operation, evidence, millis);
        }
    }

    /** Opens the host's modal auto-layout dialog on the EDT and waits for it to be closed. */
    static final class LayoutDispatch {
        private final Editor expected;
        private final Window mainWindow;
        private final Set<Window> baseline;
        private final StageEvidence evidence;
        private final PostedAction open;

        LayoutDispatch(final Editor expected, final Window mainWindow,
                       final StageEvidence evidence, final Set<Window> baseline) {
            this.expected = expected;
            this.mainWindow = mainWindow;
            this.evidence = evidence;
            this.baseline = baseline;
            this.open = new PostedAction(this::openDialog);
        }

        void dispatch() throws Exception {
            open.dispatch(FixedEdt.Operation.LAYOUT_DIALOG_OPEN, evidence);
        }

        void awaitCompletion(final long budgetMillis) throws Exception {
            open.awaitFinished(FixedEdt.Operation.LAYOUT_DIALOG_OPEN, evidence, budgetMillis);
        }

        private Void openDialog() {
            final Editor current = findEditorWindow(mainWindow, baseline);
            if (current == null || current.window != expected.window) {
                throw new IllegalStateException("editor changed before auto-layout");
            }
            final List<AbstractButton> buttons = new ArrayList<>();
            collectButtons(current.window, ShadowSceneContract.LAYOUT_BUTTON, buttons,
                new IdentityHashMap<>());
            if (buttons.size() != 1) {
                throw new IllegalStateException("expected exactly one visible enabled auto-layout");
            }
            buttons.get(0).doClick(50);
            return null;
        }
    }

    /** Selects the user-specified layout scale and confirms the host dialog with its own OK. */
    static final class LayoutApplyDispatch {
        private final Window dialog;
        private final StageEvidence evidence;
        private final String scalePercent;
        private final PostedAction apply;

        LayoutApplyDispatch(final Window dialog, final StageEvidence evidence,
                            final String scalePercent) {
            this.dialog = dialog;
            this.evidence = evidence;
            this.scalePercent = scalePercent;
            this.apply = new PostedAction(this::configureAndApply);
        }

        void dispatch() throws Exception {
            apply.dispatch(FixedEdt.Operation.LAYOUT_DIALOG_APPLY, evidence);
        }

        void awaitCompletion(final long budgetMillis) throws Exception {
            apply.awaitFinished(FixedEdt.Operation.LAYOUT_DIALOG_APPLY, evidence, budgetMillis);
        }

        private Void configureAndApply() {
            final List<AbstractButton> fixed = new ArrayList<>();
            collectButtons(dialog, ShadowSceneContract.LAYOUT_FIXED_SCALE, fixed,
                new IdentityHashMap<>());
            if (fixed.size() != 1) {
                throw new IllegalStateException("expected exactly one user-specified scale radio");
            }
            fixed.get(0).doClick(50);
            final List<Component> controls = new ArrayList<>();
            collectByClassName(dialog, ShadowSceneContract.LAYOUT_SCALE_CONTROL_CLASS, controls,
                new IdentityHashMap<>());
            if (controls.size() != 1) {
                throw new IllegalStateException("expected exactly one layout scale control");
            }
            final JTextField field = layoutScaleField(controls.get(0));
            if (field == null) throw new IllegalStateException("layout scale field is unreachable");
            field.setText(scalePercent);
            final String applied = field.getText();
            evidence.layoutScale(applied);
            if (!scalePercent.equals(applied)) {
                throw new IllegalStateException("layout scale field rejected the selected percentage");
            }
            final List<AbstractButton> oks = new ArrayList<>();
            collectButtons(dialog, ShadowSceneContract.OK_BUTTON, oks, new IdentityHashMap<>());
            if (oks.size() != 1) throw new IllegalStateException("expected exactly one layout OK");
            oks.get(0).doClick(50);
            return null;
        }
    }

    private static Window findMainWindow(final String fixtureName) {
        final List<Window> matches = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (!window.isShowing() || !(window instanceof Frame) || window instanceof Dialog) continue;
            if (window instanceof JFrame frame && titleMatches(frame.getTitle(), fixtureName)) {
                matches.add(window);
            }
        }
        if (matches.size() > 1) throw new IllegalStateException("ambiguous fixture main window");
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static Editor findEditorWindow(final Window mainWindow, final Set<Window> baseline) {
        final List<Window> matches = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (window == mainWindow || !window.isShowing()) continue;
            if (ShadowSceneContract.EDITOR_CLASS.equals(window.getClass().getName())) matches.add(window);
        }
        if (matches.size() > 1) throw new IllegalStateException("ambiguous atlas editor window");
        if (matches.isEmpty()) {
            for (Window window : Window.getWindows()) {
                if (window == mainWindow || !window.isShowing()) continue;
                if (baseline != null && baseline.contains(window)) continue;
                if (window instanceof Dialog dialog) {
                    // Name the blocking dialog so a new host popup is diagnosable from the
                    // error evidence alone; the title is truncated like every bounded field.
                    throw new IllegalStateException("unexpected visible dialog; refusing save/default action"
                        + " class=" + dialog.getClass().getName()
                        + " title=" + boundedTitle(dialog.getTitle()));
                }
            }
            return null;
        }
        final List<AbstractButton> oks = new ArrayList<>();
        collectButtons(matches.get(0), ShadowSceneContract.OK_BUTTON, oks,
            new IdentityHashMap<>());
        if (oks.size() != 1) throw new IllegalStateException("expected exactly one visible enabled OK");
        return new Editor(matches.get(0), oks.get(0));
    }

    /**
     * Answers the 5.2 host's create-atlas form exactly once. Returns true while the titled
     * dialog is showing — either just answered or still closing — so the caller keeps
     * polling instead of letting {@link #findEditorWindow} fail on it. The shape gate is the
     * observed contract (exact title, exactly one visible enabled OK, exactly one Cancel); a
     * form that drifts from it refuses like any other unexpected dialog, and a second
     * appearance after the answer also refuses rather than clicking again.
     */
    private static boolean answerNewAtlasDialog(final Window mainWindow, final Set<Window> baseline,
            final boolean[] answered) {
        for (Window window : Window.getWindows()) {
            if (window == mainWindow || !window.isShowing()
                    || (baseline != null && baseline.contains(window))) continue;
            if (!(window instanceof Dialog dialog)
                    || !ShadowSceneContract.NEW_ATLAS_DIALOG_TITLE.equals(dialog.getTitle())) {
                continue;
            }
            if (answered[0]) return true;
            final List<AbstractButton> oks = new ArrayList<>();
            final List<AbstractButton> cancels = new ArrayList<>();
            collectButtons(dialog, ShadowSceneContract.OK_BUTTON, oks, new IdentityHashMap<>());
            collectButtons(dialog, ShadowSceneContract.CANCEL_BUTTON, cancels,
                new IdentityHashMap<>());
            if (oks.size() != 1 || cancels.size() != 1) {
                throw new IllegalStateException(
                    "create-atlas dialog is not the reviewed OK/Cancel form; refusing default action");
            }
            answered[0] = true;
            // Queue the click instead of running the handler inside this lookup: if the
            // host opens the editor synchronously from OK (or any nested modal pump), a
            // blocking doClick would never let this call return and the run would die on
            // an EDT timeout while the editor was actually coming up.
            final AbstractButton ok = oks.get(0);
            SwingUtilities.invokeLater(ok::doClick);
            return true;
        }
        return false;
    }

    private static Window findLayoutDialog(final Window mainWindow, final Set<Window> baseline) {
        final List<Window> matches = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (window == mainWindow || !window.isShowing()) continue;
            if (baseline != null && baseline.contains(window)) continue;
            if (ShadowSceneContract.LAYOUT_DIALOG_CLASS.equals(window.getClass().getName())) {
                matches.add(window);
            }
        }
        if (matches.size() > 1) throw new IllegalStateException("ambiguous atlas layout dialog");
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * The host scale control keeps its editing buffer in a {@code JTextField} that only joins the
     * component tree while the value label is being edited, and the host enters that edit mode from
     * its label's {@code mouseClicked} handler; the scene therefore clicks the label with the same
     * event before writing the fixed percentage into the revealed field.
     */
    private static JTextField layoutScaleField(final Component control) {
        final JTextField direct = firstTextField(control, new IdentityHashMap<>());
        if (direct != null) return direct;
        final JLabel label = firstValueLabel(control, new IdentityHashMap<>());
        if (label == null) return null;
        final long when = System.currentTimeMillis();
        for (final int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED,
                MouseEvent.MOUSE_CLICKED}) {
            label.dispatchEvent(new MouseEvent(label, id, when, 0, 5, 5, 1, false,
                MouseEvent.BUTTON1));
        }
        return firstTextField(control, new IdentityHashMap<>());
    }

    private static JTextField firstTextField(final Component component,
                                             final Map<Component, Boolean> seen) {
        if (seen.put(component, Boolean.TRUE) != null) return null;
        if (component instanceof JTextField field) return field;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTextField found = firstTextField(child, seen);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JLabel firstValueLabel(final Component component,
                                          final Map<Component, Boolean> seen) {
        if (seen.put(component, Boolean.TRUE) != null) return null;
        if (component instanceof JLabel label && numericText(label.getText())) return label;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JLabel found = firstValueLabel(child, seen);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean numericText(final String text) {
        if (text == null || text.isBlank()) return false;
        try {
            Double.parseDouble(text.strip());
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    private static void collectByClassName(final Component component, final String className,
                                           final List<Component> result,
                                           final Map<Component, Boolean> seen) {
        if (seen.put(component, Boolean.TRUE) != null) return;
        if (className.equals(component.getClass().getName())) result.add(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectByClassName(child, className, result, seen);
            }
        }
    }

    /**
     * The host answers "unsaved changes" with {@code com.live2d.util.UUOption}, which builds three
     * buttons from the Look-and-Feel texts {@code OptionPane.yesButtonText} / {@code noButtonText} /
     * {@code cancelButtonText} and shows them modally. The fixture must stay byte-identical, so the
     * only answer this scene may give is the no/discard button, and only when that answer is unique
     * inside exactly one option pane of a new modal dialog. The expected label is read from the same
     * Look-and-Feel resource the host reads, so no localized text is ever hard-coded or recorded.
     */
    private static ExitPrompt answerExitPrompt(final Window mainWindow, final Set<Window> baseline,
                                              final StageEvidence evidence)
            throws Exception {
        return FixedEdt.callWithin(() -> {
            final List<Window> dialogs = new ArrayList<>();
            for (Window window : Window.getWindows()) {
                if (window == mainWindow || !window.isShowing()) continue;
                if (baseline != null && baseline.contains(window)) continue;
                if (window instanceof Dialog dialog && dialog.isModal()) dialogs.add(window);
            }
            if (dialogs.isEmpty()) return null;
            final List<JOptionPane> panes = new ArrayList<>();
            for (Window dialog : dialogs) collectOptionPanes(dialog, panes, new IdentityHashMap<>());
            final int buttonCount = panes.isEmpty() ? 0 : collectOptionButtons(panes.get(0)).size();
            final String classes = promptClasses(dialogs, panes);
            if (panes.size() != 1) {
                return new ExitPrompt(panes.size() > 1 ? "AMBIGUOUS_PANES" : "NOT_A_PROMPT",
                    buttonCount, classes);
            }
            final String expected = normalizeLabel(
                UIManager.getString(ShadowSceneContract.OPTION_PANE_NO_TEXT_KEY));
            AbstractButton match = null;
            int matches = 0;
            for (AbstractButton button : collectOptionButtons(panes.get(0))) {
                if (button.isShowing() && button.isVisible() && button.isEnabled()
                        && expected.equals(normalizeLabel(button.getText()))) {
                    match = button;
                    matches++;
                }
            }
            if (matches != 1) {
                return new ExitPrompt("NO_UNIQUE_" + (matches == 0 ? "MATCH" : "MATCH_SET"),
                    buttonCount, classes);
            }
            match.doClick(50);
            return new ExitPrompt(ShadowSceneContract.EXIT_PROMPT_ANSWER, buttonCount, classes);
        }, FixedEdt.Operation.EDITOR_CLOSE_POLL, evidence,
            TimeUnit.SECONDS.toMillis(ShadowSceneContract.EDT_QUERY_TIMEOUT_SECONDS));
    }

    /**
     * Best-effort dismissal of modal dialogs that offer no choice: exactly one option pane
     * containing exactly one usable button. The only possible answer is acknowledgement, so
     * clicking it changes nothing the scene could otherwise decide; dialogs carrying a real
     * choice (zero or several usable buttons, several panes, or no pane at all) are left alone
     * and keep the stage blocked.
     */
    private static int dismissAcknowledgeOnlyDialogs(final Window mainWindow,
                                                     final Window expectedEditor,
                                                     final Set<Window> baseline) {
        int dismissed = 0;
        for (Window window : Window.getWindows()) {
            if (window == mainWindow || window == expectedEditor || !window.isShowing()) continue;
            if (baseline != null && baseline.contains(window)) continue;
            if (window instanceof Dialog dialog && dialog.isModal()
                    && clickAcknowledgeOnlyButton(window)) dismissed++;
        }
        return dismissed;
    }

    private static boolean clickAcknowledgeOnlyButton(final Window window) {
        final List<JOptionPane> panes = new ArrayList<>();
        collectOptionPanes(window, panes, new IdentityHashMap<>());
        if (panes.size() != 1) return false;
        AbstractButton only = null;
        for (AbstractButton button : collectOptionButtons(panes.get(0))) {
            if (button.isShowing() && button.isVisible() && button.isEnabled()) {
                if (only != null) return false;
                only = button;
            }
        }
        if (only == null) return false;
        only.doClick(50);
        return true;
    }

    /** The one answer this scene may give, plus the structural shape of the dialog it came from. */
    private static final class ExitPrompt {
        final String answer;
        final int buttonCount;
        final String classes;

        ExitPrompt(final String answer, final int buttonCount, final String classes) {
            this.answer = answer;
            this.buttonCount = buttonCount;
            this.classes = classes;
        }
    }

    private static void collectOptionPanes(final Component component, final List<JOptionPane> result,
                                           final Map<Component, Boolean> seen) {
        if (seen.put(component, Boolean.TRUE) != null) return;
        if (component instanceof JOptionPane pane) result.add(pane);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectOptionPanes(child, result, seen);
            }
        }
    }

    private static List<AbstractButton> collectOptionButtons(final JOptionPane pane) {
        final List<AbstractButton> buttons = new ArrayList<>();
        gatherButtons(pane, buttons, new IdentityHashMap<>());
        return buttons;
    }

    private static void gatherButtons(final Component component, final List<AbstractButton> result,
                                      final Map<Component, Boolean> seen) {
        if (seen.put(component, Boolean.TRUE) != null) return;
        if (component instanceof AbstractButton button) result.add(button);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) gatherButtons(child, result, seen);
        }
    }

    /** Look-and-Feel labels carry a localized accelerator suffix, e.g. {@code No (N)}. */
    private static String normalizeLabel(final String label) {
        if (label == null) return "";
        final String trimmed = label.trim();
        final int ascii = trimmed.lastIndexOf("(");
        final int wide = trimmed.lastIndexOf('\uFF08');
        int open = Math.max(ascii, wide);
        if (open > 0 && (trimmed.endsWith(")") || trimmed.endsWith("\uFF09"))) {
            return trimmed.substring(0, open).trim();
        }
        return trimmed;
    }

    private static String promptClasses(final List<Window> dialogs, final List<JOptionPane> panes) {
        final StringBuilder classes = new StringBuilder();
        for (JOptionPane pane : panes) appendClassName(classes, pane.getClass().getName());
        for (Window dialog : dialogs) appendClassName(classes, dialog.getClass().getName());
        return classes.length() == 0 ? "NONE" : classes.toString();
    }

    private static void appendClassName(final StringBuilder target, final String name) {
        final String safe = safeName(name);
        if (target.indexOf(safe) >= 0) return;
        if (target.length() > 0) target.append(',');
        target.append(safe);
    }

    private static Set<String> splitClasses(final String classes) {
        final Set<String> values = new LinkedHashSet<>();
        if (classes == null || classes.isEmpty() || "NONE".equals(classes)) return values;
        for (String value : classes.split(",")) {
            if (values.size() >= ShadowSceneContract.WINDOW_SAMPLE_MAX) break;
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

    private static String joinClasses(final Set<String> classes) {
        return classes.isEmpty() ? "NONE" : String.join(",", classes);
    }
    private static String mergeWindowClasses(final String first, final String second) {
        if ("NONE".equals(first)) return second;
        if ("NONE".equals(second)) return first;
        return first + ',' + second;
    }

    /**
     * Close-poll window view. The boolean answer stays fail-closed; the bounded class sample makes
     * a block diagnosable without ever recording a window title or model content.
     */
    private static WindowObservation observeWindows(final Window mainWindow,
                                                    final Window expectedEditor,
                                                    final Set<Window> baseline) {
        final boolean editorVisible = expectedEditor != null && expectedEditor.isShowing();
        int blocking = 0;
        int tolerated = 0;
        final StringBuilder blockingClasses = new StringBuilder();
        final StringBuilder toleratedClasses = new StringBuilder();
        for (Window window : Window.getWindows()) {
            if (window == mainWindow || window == expectedEditor || !window.isShowing()) continue;
            if (baseline != null && baseline.contains(window)) continue;
            if (blockingDialog(window)) {
                blocking++;
                appendWindowClass(blockingClasses, window, blocking);
            } else {
                tolerated++;
                appendWindowClass(toleratedClasses, window, tolerated);
            }
        }
        return new WindowObservation(editorVisible, blocking,
            classesOrNone(blockingClasses), tolerated, classesOrNone(toleratedClasses));
    }

    /**
     * Only a modal dialog can swallow input or stand in for a save prompt. Non-modal windows - Swing
     * heavy-weight popups from our own menu dispatch, the host's palette windows - cannot.
     */
    private static boolean blockingDialog(final Window window) {
        return window instanceof Dialog dialog && dialog.isModal();
    }

    private static void appendWindowClass(final StringBuilder target, final Window window,
                                          final int seen) {
        if (seen > ShadowSceneContract.WINDOW_SAMPLE_MAX) return;
        if (target.length() > 0) target.append(',');
        target.append(safeWindowClass(window));
    }

    private static String classesOrNone(final StringBuilder classes) {
        return classes.length() == 0 ? "NONE" : classes.toString();
    }

    /** Sanitised, truncated class name: structural only, never a title or user content. */
    private static String safeWindowClass(final Window window) {
        final String name = window.getClass().getName();
        final StringBuilder safe = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            final char value = name.charAt(index);
            if (Character.isLetterOrDigit(value) || value == '.' || value == '$' || value == '_') {
                safe.append(value);
            }
            if (safe.length() >= ShadowSceneContract.WINDOW_CLASS_MAX_CHARS) break;
        }
        return safe.length() == 0 ? "UNNAMED" : safe.toString();
    }

    /** Bounded structural snapshot: thread names are runtime identifiers, never user content. */
    private static final class ThreadSnapshot {
        private final int count;
        private final String names;

        ThreadSnapshot(final int count, final String names) {
            this.count = count;
            this.names = names;
        }
    }

    private static ThreadSnapshot threadSnapshot() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root != null && root.getParent() != null) root = root.getParent();
        if (root == null) return new ThreadSnapshot(0, "NONE");
        final Thread[] threads = new Thread[Math.max(8, root.activeCount() * 2 + 8)];
        final int found = root.enumerate(threads, true);
        int count = 0;
        final StringBuilder names = new StringBuilder();
        for (int index = 0; index < found; index++) {
            final Thread thread = threads[index];
            if (thread == null || thread.isDaemon()) continue;
            count++;
            if (count > ShadowSceneContract.WINDOW_SAMPLE_MAX) continue;
            if (names.length() > 0) names.append(',');
            names.append(safeName(thread.getName()));
        }
        return new ThreadSnapshot(count, names.length() == 0 ? "NONE" : names.toString());
    }

    /** Sanitised, truncated runtime identifier: structural only, never a title or user content. */
    private static String safeName(final String name) {
        if (name == null) return "UNNAMED";
        final StringBuilder safe = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            final char value = name.charAt(index);
            if (Character.isLetterOrDigit(value) || value == '.' || value == '$' || value == '_'
                    || value == '-') {
                safe.append(value);
            }
            if (safe.length() >= ShadowSceneContract.THREAD_NAME_MAX_CHARS) break;
        }
        return safe.length() == 0 ? "UNNAMED" : safe.toString();
    }

    static boolean titleMatches(final String title, final String fixtureName) {
        if (title == null) return false;
        final int first = title.indexOf(fixtureName);
        return first >= 0 && title.indexOf(fixtureName, first + fixtureName.length()) < 0
            && (first == 0 || !isTaskNameCharacter(title.charAt(first - 1)))
            && (first + fixtureName.length() == title.length()
                || !isTaskNameCharacter(title.charAt(first + fixtureName.length())));
    }

    private static boolean isTaskNameCharacter(final char value) {
        return Character.isLetterOrDigit(value) || value == '.' || value == '_' || value == '-';
    }

    private static String boundedTitle(final String title) {
        if (title == null) return "NONE";
        String text = title.replace('\n', ' ').replace('\r', ' ');
        return text.length() > 80 ? text.substring(0, 80) : text;
    }

    private static JMenu uniqueMenu(final Component[] components, final String text,
                                    final String description) {
        final List<JMenu> matches = new ArrayList<>();
        for (Component component : components) {
            if (component instanceof JMenu menu && text.equals(menu.getText())) matches.add(menu);
        }
        if (matches.size() != 1) throw new IllegalStateException(
            "expected exactly one " + description);
        return matches.get(0);
    }

    private static JMenuItem uniqueItem(final Component[] components, final String text,
                                        final String description) {
        final List<JMenuItem> matches = new ArrayList<>();
        for (Component component : components) {
            if (component instanceof JMenuItem item && !(component instanceof JMenu)
                    && text.equals(item.getText())) matches.add(item);
        }
        if (matches.size() != 1) throw new IllegalStateException(
            "expected exactly one " + description);
        return matches.get(0);
    }

    private static void collectButtons(final Component component, final String text,
                                       final List<AbstractButton> result,
                                       final Map<Component, Boolean> seen) {
        if (seen.put(component, Boolean.TRUE) != null) return;
        if (component instanceof AbstractButton button && text.equals(button.getText())
                && button.isShowing() && button.isVisible() && button.isEnabled()) {
            result.add(button);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectButtons(child, text, result, seen);
        }
    }

    private static void collectItems(final Component component, final String text,
                                     final List<JMenuItem> result,
                                     final Map<Component, Boolean> seen) {
        if (seen.put(component, Boolean.TRUE) != null) return;
        if (component instanceof JMenuItem item && !(component instanceof JMenu)
                && text.equals(item.getText())) result.add(item);
        if (component instanceof JMenu menu) {
            for (Component child : menu.getMenuComponents()) collectItems(child, text, result, seen);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectItems(child, text, result, seen);
        }
    }

    private static long elapsedMillis(final long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    private static String failureCode(final Throwable failure) {
        if (failure instanceof FixedEdt.Timeout) return "EDT_TIMEOUT";
        if (failure instanceof IllegalStateException) return "DRIVER_BLOCKED";
        return "DRIVER_FAILURE";
    }

    private static String sha256(final Path path) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path, StandardOpenOption.READ)) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        final StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
