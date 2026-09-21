package dev.turboism.validation.atlasimage;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

/** Fixed, in-process Circle100 UI driver; no process launcher, hook, or host reflection. */
public final class AtlasImageSceneDriverAgent {
    private static final String NAMED_PREFIX = AtlasImageObserveContract.NAMED_PREFIX;
    private static final String SCENE = AtlasImageObserveContract.SCENE;
    private static final String FIXTURE_SUFFIX = AtlasImageObserveContract.FIXTURE_SUFFIX;
    private static final String EDITOR_CLASS = "com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b";
    private static final String MODELING = "建模";
    private static final String TEXTURE = "纹理";
    private static final String EDIT_TEXTURE_SET = "编辑纹理集...";
    private static final String CANCEL = "Cancel";
    private static final String EXIT = "退出";
    private static final long DEFAULT_TIMEOUT_SECONDS = 900L;
    private static final long EDT_DISPATCH_TIMEOUT_SECONDS = 5L;
    private static final long STARTUP_TIMEOUT_SECONDS = 120L;

    private AtlasImageSceneDriverAgent() {}

    public static void premain(final String ignored, final Instrumentation ignoredInstrumentation) {
        try {
            final DriverConfig config = DriverConfig.fromSystemProperties();
            final Thread worker = new Thread(() -> new FixedDriver(config).run(),
                "atlas-image-scene-fixed-driver");
            worker.setDaemon(true);
            worker.start();
            System.out.println("ATLAS_IMAGE_SCENE_DRIVER_READY");
        } catch (Exception failure) {
            System.err.println("ATLAS_IMAGE_SCENE_DRIVER_BLOCKED " + failure.getClass().getSimpleName()
                + " reason=" + safeMessage(failure));
        }
    }

    private static String safeMessage(final Throwable failure) {
        final String message = failure.getMessage();
        return message == null ? "unspecified" : message.replace('\n', ' ').replace('\r', ' ');
    }

    enum EdtOperation {
        MAIN_LOOKUP,
        EDITOR_LOOKUP,
        CANCEL_BUTTON,
        CANCEL_CLOSE_POLL,
        EDITOR_MENU_DISPATCH,
        NATIVE_EXIT
    }

    enum EdtInvocationState {
        QUEUED,
        STARTED,
        COMPLETED,
        TIMED_OUT
    }

    /** One fixed-driver EDT callback gate; timed-out queued callbacks are permanently inert. */
    static final class FixedEdtInvocation<T> {
        private final Callable<T> action;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<EdtInvocationState> state =
            new AtomicReference<>(EdtInvocationState.QUEUED);
        private final AtomicReference<T> value = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        FixedEdtInvocation(final Callable<T> action) {
            if (action == null) throw new IllegalArgumentException("EDT action is required");
            this.action = action;
        }

        boolean tryStart() {
            return state.compareAndSet(EdtInvocationState.QUEUED, EdtInvocationState.STARTED);
        }

        void executeStarted() {
            try {
                value.set(action.call());
            } catch (Throwable caught) {
                failure.compareAndSet(null, caught);
            }
        }

        void signalStarted() {
            started.countDown();
        }

        void completeStarted() {
            state.compareAndSet(EdtInvocationState.STARTED, EdtInvocationState.COMPLETED);
            started.countDown();
            completed.countDown();
        }

        void skipAfterTimeout() {
            started.countDown();
            completed.countDown();
        }

        void failBeforeDispatch(final Throwable caught) {
            failure.compareAndSet(null, caught);
            state.compareAndSet(EdtInvocationState.QUEUED, EdtInvocationState.COMPLETED);
            started.countDown();
            completed.countDown();
        }

        boolean awaitStarted(final long timeoutSeconds) throws InterruptedException {
            return started.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        boolean await(final long timeoutSeconds) throws InterruptedException {
            return completed.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        EdtInvocationState timeoutIfQueued() {
            if (state.compareAndSet(EdtInvocationState.QUEUED, EdtInvocationState.TIMED_OUT)) {
                return EdtInvocationState.TIMED_OUT;
            }
            return state.get();
        }

        EdtInvocationState state() {
            return state.get();
        }

        T value() {
            return value.get();
        }

        Throwable failure() {
            return failure.get();
        }
    }

    static final class EdtTimeoutException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        final EdtOperation operation;
        final EdtInvocationState state;

        EdtTimeoutException(final EdtOperation operation, final EdtInvocationState state) {
            super("EDT operation timeout: " + operation + " state=" + state);
            this.operation = operation;
            this.state = state;
        }
    }

    private static String requiredSystem(final String key) {
        final String value = System.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static String validTaskId(final String value) {
        if (!value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid taskId");
        }
        return value;
    }

    private static final class DriverConfig {
        final Path home;
        final String taskId;
        final String fixture;
        final String fixtureName;
        final long timeoutSeconds;

        private DriverConfig(final Path home, final String taskId, final String fixture,
                             final String fixtureName, final long timeoutSeconds) {
            this.home = home;
            this.taskId = taskId;
            this.fixture = fixture;
            this.fixtureName = fixtureName;
            this.timeoutSeconds = timeoutSeconds;
        }

        static DriverConfig fromSystemProperties() throws Exception {
            final Path home = Path.of(requiredSystem("turboism.home")).toAbsolutePath().normalize();
            final Path namedHome = Path.of(requiredSystem(NAMED_PREFIX + "home"))
                .toAbsolutePath().normalize();
            if (!home.equals(namedHome)) throw new IllegalArgumentException("named home differs from turboism.home");
            if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("turboism.home is not a directory");
            }

            final String runId = validTaskId(requiredSystem("turboism.validation.runId"));
            final String taskId = validTaskId(requiredSystem(NAMED_PREFIX + "taskId"));
            if (!runId.equals(taskId)) throw new IllegalArgumentException("taskId differs from runId");
            if (!"5303".equals(requiredSystem(NAMED_PREFIX + "version"))) {
                throw new IllegalArgumentException("fixed driver requires version 5303");
            }
            if (!"5303".equals(System.getProperty("turboism.validation.hostVersion", "5303"))) {
                throw new IllegalArgumentException("scene version differs from hostVersion");
            }

            final String fixture = requiredSystem(NAMED_PREFIX + "fixture");
            final String fixtureName = requiredSystem(NAMED_PREFIX + "fixtureName");
            AtlasImageObserveContract.requireNamedFixture(fixture, fixtureName, taskId);
            final String outputRelative = System.getProperty(NAMED_PREFIX + "outputRelative",
                AtlasImageObserveContract.OUTPUT_RELATIVE);
            if (!AtlasImageObserveContract.OUTPUT_RELATIVE.equals(outputRelative)) {
                throw new IllegalArgumentException("outputRelative is fixed to "
                    + AtlasImageObserveContract.OUTPUT_RELATIVE);
            }
            final long timeoutSeconds = parseTimeout(System.getProperty(
                NAMED_PREFIX + "timeoutSeconds", Long.toString(DEFAULT_TIMEOUT_SECONDS)));
            return new DriverConfig(home, taskId, fixture, fixtureName, timeoutSeconds);
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

        Path outputRoot() throws Exception {
            final Path homeReal = home.toRealPath();
            final Path output = homeReal.resolve(AtlasImageObserveContract.OUTPUT_RELATIVE).normalize();
            if (!output.startsWith(homeReal)) throw new IllegalArgumentException("output escaped task home");
            return output.toRealPath();
        }

        Path runDirectory(final Path outputRoot) {
            final Path run = outputRoot.resolve(taskId).normalize();
            if (!run.startsWith(outputRoot)) throw new IllegalArgumentException("run escaped output root");
            return run;
        }
    }

    /** Fixed editor-menu dispatch gate; the modal click completion remains a separate latch. */
    static final class EditorMenuDispatch {
        private final Window window;
        private final AtomicReference<Throwable> dispatchFailure;
        private final AtomicReference<Throwable> actionFailure;
        private final AtomicBoolean clickStarted;
        private final CountDownLatch actionFinished;
        private final StageEvidence stageEvidence;
        private final long startedNanos = System.nanoTime();
        private final FixedEdtInvocation<Void> invocation = new FixedEdtInvocation<>(this::click);

        EditorMenuDispatch(final Window window, final AtomicReference<Throwable> dispatchFailure,
                           final AtomicReference<Throwable> actionFailure,
                           final AtomicBoolean clickStarted, final CountDownLatch actionFinished,
                           final StageEvidence stageEvidence) {
            this.window = window;
            this.dispatchFailure = dispatchFailure;
            this.actionFailure = actionFailure;
            this.clickStarted = clickStarted;
            this.actionFinished = actionFinished;
            this.stageEvidence = stageEvidence;
        }

        void dispatch() throws Exception {
            if (stageEvidence != null) stageEvidence.onEdtQueued(EdtOperation.EDITOR_MENU_DISPATCH);
            try {
                SwingUtilities.invokeLater(() -> {
                    if (!invocation.tryStart()) {
                        if (stageEvidence != null) {
                            stageEvidence.onEdtLateSkipped(EdtOperation.EDITOR_MENU_DISPATCH);
                        }
                        actionFinished.countDown();
                        invocation.skipAfterTimeout();
                        return;
                    }
                    if (stageEvidence != null) {
                        stageEvidence.onEdtStarted(EdtOperation.EDITOR_MENU_DISPATCH);
                    }
                    try {
                        invocation.executeStarted();
                        final Throwable failure = invocation.failure();
                        if (failure != null) {
                            if (clickStarted.get()) {
                                actionFailure.compareAndSet(null, failure);
                            } else {
                                dispatchFailure.compareAndSet(null, failure);
                            }
                        }
                        if (stageEvidence != null) {
                            stageEvidence.onEdtEnded(EdtOperation.EDITOR_MENU_DISPATCH,
                                EdtInvocationState.COMPLETED,
                                failure == null ? "COMPLETED" : "EXCEPTION",
                                elapsedMillis(startedNanos));
                        }
                    } finally {
                        invocation.signalStarted();
                        invocation.completeStarted();
                        actionFinished.countDown();
                    }
                });
            } catch (Throwable failure) {
                dispatchFailure.compareAndSet(null, failure);
                invocation.failBeforeDispatch(failure);
                actionFinished.countDown();
                if (stageEvidence != null) {
                    stageEvidence.onEdtEnded(EdtOperation.EDITOR_MENU_DISPATCH,
                        EdtInvocationState.COMPLETED, "EXCEPTION", elapsedMillis(startedNanos));
                }
                throwFailure(failure, "editor menu request");
                return;
            }
            try {
                if (!invocation.awaitStarted(EDT_DISPATCH_TIMEOUT_SECONDS)) {
                    final EdtInvocationState state = invocation.timeoutIfQueued();
                    if (stageEvidence != null) {
                        stageEvidence.onEdtWaitTimedOut(EdtOperation.EDITOR_MENU_DISPATCH, state);
                    }
                    throw new EdtTimeoutException(EdtOperation.EDITOR_MENU_DISPATCH, state);
                }
            } catch (InterruptedException interrupted) {
                final EdtInvocationState state = invocation.timeoutIfQueued();
                if (stageEvidence != null) {
                    stageEvidence.onEdtWaitInterrupted(EdtOperation.EDITOR_MENU_DISPATCH, state);
                }
                Thread.currentThread().interrupt();
                throw new IllegalStateException("editor menu dispatch interrupted state=" + state,
                    interrupted);
            }
            throwFailure(dispatchFailure.get(), "editor menu request");
        }

        private Void click() {
            if (!(window instanceof JFrame frame)) {
                throw new IllegalStateException("fixture window is not JFrame");
            }
            final JMenuBar bar = frame.getJMenuBar();
            if (bar == null) throw new IllegalStateException("fixture window has no menu bar");
            final JMenu modeling = uniqueMenu(bar.getComponents(), MODELING, "top menu");
            final JMenu texture = uniqueMenu(modeling.getMenuComponents(), TEXTURE,
                "texture submenu");
            final JMenuItem editor = uniqueItem(texture.getMenuComponents(), EDIT_TEXTURE_SET,
                "texture editor menu item");
            if (!editor.isEnabled()) {
                throw new IllegalStateException("texture editor menu item disabled");
            }
            final MenuElement[] path = {bar, modeling, modeling.getPopupMenu(), texture,
                texture.getPopupMenu(), editor};
            MenuSelectionManager.defaultManager().setSelectedPath(path);
            // Release the driver before doClick: this click opens a synchronous modal editor.
            clickStarted.set(true);
            invocation.signalStarted();
            editor.doClick(50);
            return null;
        }
    }

    private static final class FixedDriver {
        private final DriverConfig config;
        private final SceneDriverState state = new SceneDriverState();
        private volatile Path outputRoot;
        private Path run;
        private Window main;
        private StageEvidence stageEvidence;
        private volatile boolean taskResultWritten;
        private final AtomicReference<Throwable> editorDispatchFailure = new AtomicReference<>();
        private final CountDownLatch editorActionFinished = new CountDownLatch(1);
        private final AtomicReference<Throwable> editorActionFailure = new AtomicReference<>();

        FixedDriver(final DriverConfig config) {
            this.config = config;
        }

        void run() {
            try {
                outputRoot = waitForOutputRoot();
                run = waitForRun(outputRoot);
                stageEvidence = new StageEvidence(run.resolve("driver-stage.properties"));
                final Path result = outputRoot.resolve("result.txt");
                if (Files.exists(result, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("task result already exists; refusing reuse");
                }
                if (Files.exists(run.resolve("result.properties"), LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(run.resolve("finish.request"), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("observer run already contains completion state");
                }

                main = waitForMain();
                state.transition(SceneDriverState.Stage.INITIAL, SceneDriverState.Stage.MAIN_IDENTIFIED);
                requestEditor(main);
                state.transition(SceneDriverState.Stage.MAIN_IDENTIFIED, SceneDriverState.Stage.EDIT_REQUESTED);
                final Editor editor = waitForEditor(main);
                state.transition(SceneDriverState.Stage.EDIT_REQUESTED, SceneDriverState.Stage.EDITOR_CONFIRMED);
                cancelEditor(editor, main);
                state.transition(SceneDriverState.Stage.EDITOR_CONFIRMED, SceneDriverState.Stage.CANCELLED);

                requestFinish();
                state.transition(SceneDriverState.Stage.CANCELLED, SceneDriverState.Stage.FINISH_REQUESTED);
                final Properties observer = waitForObserverResult();
                state.transition(SceneDriverState.Stage.FINISH_REQUESTED, SceneDriverState.Stage.OBSERVER_PERSISTED);
                final boolean observerPass = isObserverPass(observer);
                state.transition(SceneDriverState.Stage.OBSERVER_PERSISTED,
                    SceneDriverState.Stage.EXIT_REQUESTED);
                requestNativeExit(main);
                state.transition(SceneDriverState.Stage.EXIT_REQUESTED,
                    SceneDriverState.Stage.RESULT_WRITTEN);
                publishTaskResult(observerPass ? "PASS" : "FAIL", observerPass
                    ? "" : "OBSERVER_RESULT_NOT_PASS", observer);
            } catch (Throwable failure) {
                final SceneDriverState.Stage failureStage = state.stage();
                state.fail();
                if (stageEvidence != null) {
                    stageEvidence.failed(failure instanceof EdtTimeoutException
                        ? "EDT_TIMEOUT" : "DRIVER_FAILURE");
                }
                recordFailure(failure);
                tryFinishAfterCancel(failureStage);
                if (!taskResultWritten && outputRoot != null) {
                    try {
                        publishTaskResult("FAIL", failure.getClass().getSimpleName(), null);
                    } catch (Exception publishFailure) {
                        System.err.println("ATLAS_IMAGE_SCENE_DRIVER_RESULT_FAILED "
                            + safeMessage(publishFailure));
                    }
                }
                System.err.println("ATLAS_IMAGE_SCENE_DRIVER_BLOCKED "
                    + failure.getClass().getSimpleName() + " reason=" + safeMessage(failure));
            }
        }

        private Path waitForOutputRoot() throws Exception {
            final long deadline = deadline();
            Exception last = null;
            while (System.nanoTime() < deadline) {
                try {
                    final Path output = config.outputRoot();
                    if (Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) return output;
                } catch (Exception failure) {
                    last = failure;
                }
                sleep(200L);
            }
            throw new IllegalStateException("observer output timeout"
                + (last == null ? "" : ": " + safeMessage(last)));
        }

        private Path waitForRun(final Path output) throws Exception {
            final Path expected = config.runDirectory(output);
            final long deadline = deadline();
            while (System.nanoTime() < deadline) {
                if (Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS)) return expected.toRealPath();
                sleep(200L);
            }
            throw new IllegalStateException("observer run timeout: " + expected);
        }

        private Window waitForMain() throws Exception {
            return AtlasImageSceneDriverAgent.waitForMain(config.fixtureName, deadline(), stageEvidence);
        }

        private void requestEditor(final Window window) throws Exception {
            new EditorMenuDispatch(window, editorDispatchFailure, editorActionFailure,
                new AtomicBoolean(), editorActionFinished, stageEvidence).dispatch();
        }

        private Editor waitForEditor(final Window mainWindow) throws Exception {
            final long deadline = deadline();
            while (System.nanoTime() < deadline) {
                throwIfEditorRequestFailure();
                final Editor found = onEdt(() -> findEditorWindow(mainWindow),
                    EdtOperation.EDITOR_LOOKUP, stageEvidence);
                throwIfEditorRequestFailure();
                if (found != null) return found;
                sleep(200L);
            }
            throw new IllegalStateException("unique atlas editor window timeout");
        }

        private void throwIfEditorRequestFailure() throws Exception {
            throwIfDispatchFailure(editorDispatchFailure, "editor menu request");
            throwIfDispatchFailure(editorActionFailure, "editor menu action");
        }

        private void cancelEditor(final Editor expected, final Window mainWindow) throws Exception {
            if (stageEvidence != null) stageEvidence.cancelDispatchStarted();
            onEdt(() -> {
                final Editor current = findEditorWindow(mainWindow);
                if (current == null || current.window != expected.window) {
                    throw new IllegalStateException("atlas editor changed before Cancel");
                }
                current.cancel.doClick(50);
                return null;
            }, EdtOperation.CANCEL_BUTTON, stageEvidence);
            if (stageEvidence != null) stageEvidence.closePollStarted();
            final long deadline = deadline();
            boolean closed = false;
            while (System.nanoTime() < deadline) {
                final Boolean safe = onEdt(() -> {
                    if (expected.window.isShowing()) return false;
                    return !hasUnexpectedSecondaryWindow(mainWindow);
                }, EdtOperation.CANCEL_CLOSE_POLL, stageEvidence);
                if (safe) {
                    closed = true;
                    if (stageEvidence != null) stageEvidence.closePollClosed();
                    break;
                }
                sleep(200L);
            }
            if (!closed) throw new IllegalStateException("Cancel did not close atlas editor safely");
            // Cancel closes the nested modal loop; only now can doClick return and report its action error.
            if (stageEvidence != null) stageEvidence.actionWaitStarted();
            final long waitStartedNanos = System.nanoTime();
            try {
                awaitActionCompletion(editorActionFinished, editorActionFailure, "editor menu action");
                if (stageEvidence != null) {
                    stageEvidence.actionWaitEnded(elapsedMillis(waitStartedNanos), true);
                }
            } catch (Throwable failure) {
                if (stageEvidence != null) {
                    stageEvidence.actionWaitEnded(elapsedMillis(waitStartedNanos), false);
                }
                throwFailure(failure, "editor menu action");
            }
        }

        private void requestFinish() throws Exception {
            if (stageEvidence != null) stageEvidence.finishStarted();
            final long startedNanos = System.nanoTime();
            final Path request = run.resolve("finish.request");
            if (Files.exists(request, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("finish.request already exists");
            }
            Files.writeString(request, "finish\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (stageEvidence != null) stageEvidence.finishWritten(elapsedMillis(startedNanos));
        }

        private Properties waitForObserverResult() throws Exception {
            final Path result = run.resolve("result.properties");
            final long deadline = deadline();
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS)) {
                    final Properties values = new Properties();
                    try (var input = Files.newInputStream(result)) {
                        values.load(input);
                    }
                    if (!config.taskId.equals(values.getProperty("runId"))) {
                        throw new IllegalStateException("observer result runId mismatch");
                    }
                    if (!"EXPLICIT_FINISH".equals(values.getProperty("completionReason"))) {
                        throw new IllegalStateException("observer did not finish from finish.request");
                    }
                    return values;
                }
                sleep(200L);
            }
            throw new IllegalStateException("observer result timeout");
        }

        private boolean isObserverPass(final Properties values) {
            return observerResultIsPass(values);
        }

        private void publishTaskResult(final String status, final String reason,
                                       final Properties observer) throws Exception {
            final Path target = outputRoot.resolve("result.txt");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("task result already exists; refusing overwrite");
            }
            final StringBuilder content = new StringBuilder();
            content.append("status=").append(status).append('\n');
            content.append("scene=").append(SCENE).append('\n');
            content.append("runId=").append(config.taskId).append('\n');
            content.append("fixture=").append(config.fixtureName).append('\n');
            content.append("driverState=").append(state.stage()).append('\n');
            if (reason != null && !reason.isBlank()) content.append("reason=").append(reason).append('\n');
            if (observer != null) {
                content.append("observerStatus=").append(observer.getProperty("status", "MISSING")).append('\n');
                content.append("completionReason=").append(observer.getProperty("completionReason", "MISSING"))
                    .append('\n');
            }
            atomicCreate(target, content.toString());
            taskResultWritten = true;
        }


        private void requestNativeExit(final Window mainWindow) throws Exception {
            onEdt(() -> {
                if (!(mainWindow instanceof JFrame frame)) {
                    throw new IllegalStateException("main window is not JFrame");
                }
                final JMenuBar bar = frame.getJMenuBar();
                if (bar == null) throw new IllegalStateException("main window has no menu bar");
                final List<JMenuItem> exits = new ArrayList<>();
                collectItems(bar, EXIT, exits, new IdentityHashMap<>());
                if (exits.size() != 1) {
                    throw new IllegalStateException("expected exactly one native exit menu item, found "
                        + exits.size());
                }
                final JMenuItem exit = exits.get(0);
                if (!exit.isEnabled()) throw new IllegalStateException("native exit menu item disabled");
                // Publish PASS only after this action returns without throwing.
                exit.doClick(50);
                return null;
            }, EdtOperation.NATIVE_EXIT, stageEvidence);
        }


        private void recordFailure(final Throwable failure) {
            if (run == null) return;
            try {
                final Path evidence = run.resolve("driver-error.properties");
                if (Files.exists(evidence, LinkOption.NOFOLLOW_LINKS)) return;
                final String text = "scene=" + SCENE + "\nstate=" + state.stage()
                    + "\nerror=" + failure.getClass().getName() + "\nmessage=" + safeMessage(failure) + "\n";
                Files.writeString(evidence, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (Exception ignored) {
                // The manager owns timeout and cleanup if even failure evidence cannot be written.
            }
        }

        private void tryFinishAfterCancel(final SceneDriverState.Stage failureStage) {
            if (run == null || failureStage.ordinal() < SceneDriverState.Stage.CANCELLED.ordinal()) return;
            // A failed driver never fabricates observer success. If Cancel already happened, let the
            // passive observer persist its own bounded result, but do not click any further controls.
            try {
                if (Files.exists(run.resolve("finish.request"), LinkOption.NOFOLLOW_LINKS)) return;
                Files.writeString(run.resolve("finish.request"), "finish\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (Exception ignored) {
                // No finish request is safer than a request before a verified Cancel.
            }
        }

        private long deadline() {
            return System.nanoTime() + Duration.ofSeconds(config.timeoutSeconds).toNanos();
        }

        private static void sleep(final long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    }

    /** Fixed-scene startup lookup: only an unstarted MAIN_LOOKUP timeout may continue. */
    static Window waitForMain(final String fixtureName, final long overallDeadlineNanos,
                              final StageEvidence evidence) throws Exception {
        final long startupDeadline = Math.min(overallDeadlineNanos,
            System.nanoTime() + Duration.ofSeconds(STARTUP_TIMEOUT_SECONDS).toNanos());
        while (System.nanoTime() < startupDeadline) {
            try {
                final Window found = onEdt(() -> findMainWindow(fixtureName),
                    EdtOperation.MAIN_LOOKUP, evidence);
                if (found != null) return found;
            } catch (EdtTimeoutException timeout) {
                if (timeout.operation != EdtOperation.MAIN_LOOKUP
                        || timeout.state != EdtInvocationState.TIMED_OUT) throw timeout;
                if (System.nanoTime() >= startupDeadline) {
                    throw new IllegalStateException("main window startup budget exhausted", timeout);
                }
                if (evidence != null) evidence.mainLookupRetry();
                continue;
            }
            final long remainingNanos = startupDeadline - System.nanoTime();
            if (remainingNanos <= 0L) break;
            final long sleepMillis = Math.max(1L, Math.min(250L,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            Thread.sleep(sleepMillis);
        }
        throw new IllegalStateException("unique fixture main window startup timeout");
    }

    /** Fixed-size diagnostic state; every write replaces one bounded properties file. */
    static final class StageEvidence {
        private final Path target;
        private final long runStartedEpochMillis = System.currentTimeMillis();
        private final long runStartedNanos = System.nanoTime();
        private final long startupBudgetSeconds = STARTUP_TIMEOUT_SECONDS;
        private long sequence;
        private String stage = "RUN";
        private String event = "START";
        private long eventEpochMillis = runStartedEpochMillis;
        private long eventElapsedMillis;
        private boolean failure;
        private String failureCode = "NONE";
        private boolean timedOut;
        private boolean startedAtTimeout;
        private long onEdtQueued;
        private long onEdtStarted;
        private long onEdtCompleted;
        private long onEdtTimedOut;
        private long onEdtLateSkipped;
        private long mainLookupRetryCount;
        private String onEdtLastOperation = "NONE";
        private String onEdtLastState = "NONE";
        private String onEdtLastOutcome = "NONE";
        private boolean cancelDispatchStarted;
        private boolean cancelEdtReturned;
        private boolean cancelClosePollStarted;
        private long cancelClosePollCount;
        private boolean cancelClosed;
        private long cancelDispatchElapsedMillis;
        private boolean editorActionWaitStarted;
        private boolean editorActionWaitCompleted;
        private long editorActionWaitElapsedMillis;
        private boolean finishRequestStarted;
        private boolean finishRequestWritten;
        private long finishRequestElapsedMillis;

        StageEvidence(final Path target) {
            this.target = target;
            write();
        }

        synchronized void cancelDispatchStarted() {
            cancelDispatchStarted = true;
            event("CANCEL_DISPATCH", "START", 0L);
        }

        synchronized void cancelDispatchEnded(final long elapsedMillis, final boolean returned) {
            cancelDispatchElapsedMillis = elapsedMillis;
            cancelEdtReturned = returned;
            event("CANCEL_DISPATCH", returned ? "EDT_RETURN" : "EDT_FAILURE", elapsedMillis);
        }

        synchronized void closePollStarted() {
            cancelClosePollStarted = true;
            event("CANCEL_CLOSE_POLL", "START", 0L);
        }

        synchronized void closePollClosed() {
            cancelClosed = true;
            event("CANCEL_CLOSE_POLL", "CLOSED", 0L);
        }

        synchronized void actionWaitStarted() {
            editorActionWaitStarted = true;
            event("EDITOR_ACTION_WAIT", "START", 0L);
        }

        synchronized void actionWaitEnded(final long elapsedMillis, final boolean completed) {
            editorActionWaitElapsedMillis = elapsedMillis;
            editorActionWaitCompleted = completed;
            event("EDITOR_ACTION_WAIT", completed ? "COMPLETED" : "FAILED", elapsedMillis);
        }

        synchronized void finishStarted() {
            finishRequestStarted = true;
            event("FINISH_REQUEST", "START", 0L);
        }

        synchronized void finishWritten(final long elapsedMillis) {
            finishRequestElapsedMillis = elapsedMillis;
            finishRequestWritten = true;
            event("FINISH_REQUEST", "WRITTEN", elapsedMillis);
        }

        synchronized void onEdtQueued(final EdtOperation operation) {
            onEdtQueued++;
            if (operation == EdtOperation.CANCEL_CLOSE_POLL) cancelClosePollCount++;
            onEdtLastOperation = operation.name();
            onEdtLastState = EdtInvocationState.QUEUED.name();
            onEdtLastOutcome = "NONE";
            event("ON_EDT", "QUEUED", 0L);
        }

        synchronized void onEdtStarted(final EdtOperation operation) {
            onEdtStarted++;
            onEdtLastOperation = operation.name();
            onEdtLastState = EdtInvocationState.STARTED.name();
            onEdtLastOutcome = "NONE";
            event("ON_EDT", "STARTED", 0L);
        }

        synchronized void onEdtEnded(final EdtOperation operation, final EdtInvocationState state,
                                      final String outcome, final long elapsedMillis) {
            onEdtCompleted++;
            onEdtLastOperation = operation.name();
            onEdtLastState = state.name();
            onEdtLastOutcome = outcome;
            if (operation == EdtOperation.CANCEL_BUTTON) {
                cancelDispatchEnded(elapsedMillis, "COMPLETED".equals(outcome));
            } else {
                event("ON_EDT", "END", elapsedMillis);
            }
        }

        synchronized void onEdtWaitTimedOut(final EdtOperation operation,
                                             final EdtInvocationState state) {
            onEdtTimedOut++;
            timedOut = true;
            startedAtTimeout = state == EdtInvocationState.STARTED;
            onEdtLastOperation = operation.name();
            onEdtLastState = state.name();
            onEdtLastOutcome = "TIMEOUT";
            event("ON_EDT", "WAIT_TIMEOUT", 0L);
        }

        synchronized void onEdtWaitInterrupted(final EdtOperation operation,
                                                final EdtInvocationState state) {
            onEdtLastOperation = operation.name();
            onEdtLastState = state.name();
            onEdtLastOutcome = "INTERRUPTED";
            event("ON_EDT", "WAIT_INTERRUPTED", 0L);
        }

        synchronized void onEdtLateSkipped(final EdtOperation operation) {
            onEdtLateSkipped++;
            onEdtLastOperation = operation.name();
            onEdtLastState = EdtInvocationState.TIMED_OUT.name();
            onEdtLastOutcome = "LATE_CALLBACK_SKIPPED";
            event("ON_EDT", "LATE_CALLBACK_SKIPPED", 0L);
        }

        synchronized void mainLookupRetry() {
            mainLookupRetryCount++;
            event("MAIN_LOOKUP", "RETRY", 0L);
        }

        synchronized void failed(final String code) {
            failure = true;
            if ("NONE".equals(failureCode)) failureCode = code;
            event("FAILURE", "RECORDED", 0L);
        }

        private void event(final String nextStage, final String nextEvent, final long elapsedMillis) {
            stage = nextStage;
            event = nextEvent;
            eventEpochMillis = System.currentTimeMillis();
            eventElapsedMillis = Math.max(0L, elapsedMillis);
            sequence++;
            write();
        }

        private void write() {
            final String content = "schemaVersion=1\n"
                + "sequence=" + sequence + "\n"
                + "runStartedEpochMillis=" + runStartedEpochMillis + "\n"
                + "eventEpochMillis=" + eventEpochMillis + "\n"
                + "runElapsedMillis=" + elapsedMillis(runStartedNanos) + "\n"
                + "eventElapsedMillis=" + eventElapsedMillis + "\n"
                + "stage=" + stage + "\n"
                + "event=" + event + "\n"
                + "failure=" + failure + "\n"
                + "failureCode=" + failureCode + "\n"
                + "timedOut=" + timedOut + "\n"
                + "startedAtTimeout=" + startedAtTimeout + "\n"
                + "startupBudgetSeconds=" + startupBudgetSeconds + "\n"
                + "onEdt.queued=" + onEdtQueued + "\n"
                + "onEdt.started=" + onEdtStarted + "\n"
                + "onEdt.completed=" + onEdtCompleted + "\n"
                + "onEdt.timedOut=" + onEdtTimedOut + "\n"
                + "onEdt.lateSkipped=" + onEdtLateSkipped + "\n"
                + "mainLookupRetryCount=" + mainLookupRetryCount + "\n"
                + "onEdt.lastOperation=" + onEdtLastOperation + "\n"
                + "onEdt.lastState=" + onEdtLastState + "\n"
                + "onEdt.lastOutcome=" + onEdtLastOutcome + "\n"
                + "cancel.dispatchStarted=" + cancelDispatchStarted + "\n"
                + "cancel.edtReturned=" + cancelEdtReturned + "\n"
                + "cancel.dispatchElapsedMillis=" + cancelDispatchElapsedMillis + "\n"
                + "cancel.closePollStarted=" + cancelClosePollStarted + "\n"
                + "cancel.closePollCount=" + cancelClosePollCount + "\n"
                + "cancel.closed=" + cancelClosed + "\n"
                + "editor.actionWaitStarted=" + editorActionWaitStarted + "\n"
                + "editor.actionWaitCompleted=" + editorActionWaitCompleted + "\n"
                + "editor.actionWaitElapsedMillis=" + editorActionWaitElapsedMillis + "\n"
                + "finish.requestStarted=" + finishRequestStarted + "\n"
                + "finish.requestWritten=" + finishRequestWritten + "\n"
                + "finish.requestElapsedMillis=" + finishRequestElapsedMillis + "\n";
            final Path parent = target.getParent();
            if (parent == null) return;
            Path temporary = null;
            boolean moved = false;
            try {
                Files.createDirectories(parent);
                temporary = Files.createTempFile(parent, ".atlas-image-stage-", ".tmp");
                Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (Exception ignored) {
                // Diagnostic evidence must not change the fixed driver action path.
            } finally {
                if (!moved && temporary != null) {
                    try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
                }
            }
        }

        private long elapsedMillis(final long startedNanos) {
            return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
        }
    }
    private static final class Editor {
        final Window window;
        final AbstractButton cancel;

        Editor(final Window window, final AbstractButton cancel) {
            this.window = window;
            this.cancel = cancel;
        }
    }

    private static Window findMainWindow(final DriverConfig config) {
        return findMainWindow(config.fixtureName);
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

    private static Editor findEditorWindow(final Window mainWindow) {
        final List<Window> matches = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (window == mainWindow || !window.isShowing()) continue;
            if (isReviewedEditorClassName(window.getClass().getName())) matches.add(window);
        }
        if (matches.size() > 1) throw new IllegalStateException("ambiguous atlas editor window");
        if (matches.isEmpty()) {
            for (Window window : Window.getWindows()) {
                if (window == mainWindow || !window.isShowing()) continue;
                if (window instanceof Dialog) {
                    throw new IllegalStateException("unexpected visible dialog; refusing save/default action");
                }
            }
            return null;
        }
        final List<AbstractButton> cancels = new ArrayList<>();
        collectButtons(matches.get(0), CANCEL, cancels, new IdentityHashMap<>());
        if (cancels.size() != 1) {
            throw new IllegalStateException("expected exactly one enabled visible Cancel, found " + cancels.size());
        }
        if (!cancels.get(0).isVisible() || !cancels.get(0).isEnabled()) {
            throw new IllegalStateException("Cancel is not visible and enabled");
        }
        return new Editor(matches.get(0), cancels.get(0));
    }

    private static boolean hasUnexpectedSecondaryWindow(final Window mainWindow) {
        for (Window window : Window.getWindows()) {
            if (window == mainWindow || !window.isShowing()) continue;
            return true;
        }
        return false;
    }

    static boolean observerResultIsPass(final Properties values) {
        return "PASS".equals(values.getProperty("status"))
            && "true".equals(values.getProperty("actual.requiredTargetObserved"))
            && "false".equals(values.getProperty("actual.conflictOrOverflow"))
            && "false".equals(values.getProperty("guardInstalled"))
            && "NOT_EVALUATED".equals(values.getProperty("optimizationReadiness"));
    }

    static boolean isReviewedEditorClassName(final String className) {
        return EDITOR_CLASS.equals(className);
    }

    private static boolean titleMatches(final String title, final String fixtureName) {
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

    private static JMenu uniqueMenu(final Component[] components, final String text, final String description) {
        final List<JMenu> matches = new ArrayList<>();
        for (Component component : components) {
            if (component instanceof JMenu menu && text.equals(menu.getText())) matches.add(menu);
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("expected exactly one " + description + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static JMenuItem uniqueItem(final Component[] components, final String text, final String description) {
        final List<JMenuItem> matches = new ArrayList<>();
        for (Component component : components) {
            if (component instanceof JMenuItem item && !(component instanceof JMenu)
                    && text.equals(item.getText())) matches.add(item);
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("expected exactly one " + description + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static void collectButtons(final Component component, final String text,
                                       final List<AbstractButton> result,
                                       final Map<Component, Boolean> seen) {
        if (seen.put(component, Boolean.TRUE) != null) return;
        if (component instanceof AbstractButton button && text.equals(button.getText())
                && button.isShowing() && button.isVisible() && button.isEnabled()) result.add(button);
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

    private static <T> T onEdt(final Callable<T> action, final EdtOperation operation,
                               final StageEvidence evidence) throws Exception {
        final long startedNanos = System.nanoTime();
        if (evidence != null) evidence.onEdtQueued(operation);
        if (SwingUtilities.isEventDispatchThread()) {
            if (evidence != null) evidence.onEdtStarted(operation);
            try {
                final T value = action.call();
                if (evidence != null) evidence.onEdtEnded(operation,
                    EdtInvocationState.COMPLETED, "COMPLETED", elapsedMillis(startedNanos));
                return value;
            } catch (Throwable caught) {
                if (evidence != null) evidence.onEdtEnded(operation,
                    EdtInvocationState.COMPLETED, "EXCEPTION", elapsedMillis(startedNanos));
                throwFailure(caught, operation.name());
                return null;
            }
        }
        final FixedEdtInvocation<T> invocation = new FixedEdtInvocation<>(action);
        try {
            SwingUtilities.invokeLater(() -> {
                if (!invocation.tryStart()) {
                    if (evidence != null) evidence.onEdtLateSkipped(operation);
                    invocation.skipAfterTimeout();
                    return;
                }
                if (evidence != null) evidence.onEdtStarted(operation);
                try {
                    invocation.executeStarted();
                    final Throwable caught = invocation.failure();
                    if (evidence != null) evidence.onEdtEnded(operation,
                        EdtInvocationState.COMPLETED,
                        caught == null ? "COMPLETED" : "EXCEPTION",
                        elapsedMillis(startedNanos));
                } finally {
                    invocation.completeStarted();
                }
            });
        } catch (Throwable caught) {
            invocation.failBeforeDispatch(caught);
            if (evidence != null) evidence.onEdtEnded(operation,
                EdtInvocationState.COMPLETED, "EXCEPTION", elapsedMillis(startedNanos));
            throwFailure(caught, operation.name());
            return null;
        }
        try {
            if (!invocation.await(EDT_DISPATCH_TIMEOUT_SECONDS)) {
                final EdtInvocationState state = invocation.timeoutIfQueued();
                if (evidence != null) evidence.onEdtWaitTimedOut(operation, state);
                throw new EdtTimeoutException(operation, state);
            }
        } catch (InterruptedException interrupted) {
            final EdtInvocationState state = invocation.timeoutIfQueued();
            if (evidence != null) evidence.onEdtWaitInterrupted(operation, state);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("EDT operation interrupted: " + operation
                + " state=" + state, interrupted);
        }
        throwFailure(invocation.failure(), operation.name());
        return invocation.value();
    }

    private static long elapsedMillis(final long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    private static void throwFailure(final Throwable caught, final String description) throws Exception {
        if (caught == null) return;
        if (caught instanceof Exception exception) throw exception;
        if (caught instanceof Error error) throw error;
        throw new IllegalStateException(description + " failed", caught);
    }

    static void awaitActionCompletion(final CountDownLatch completed,
                                      final AtomicReference<Throwable> failure,
                                      final String description) throws Exception {
        if (!completed.await(EDT_DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException(description + " completion timeout");
        }
        throwIfDispatchFailure(failure, description);
    }


    private static void throwIfDispatchFailure(final AtomicReference<Throwable> failure,
                                               final String description) throws Exception {
        throwFailure(failure.get(), description);
    }


    private static void atomicCreate(final Path target, final String content) throws Exception {
        final Path parent = target.getParent();
        if (parent == null) throw new IllegalArgumentException("result path has no parent");
        Files.createDirectories(parent);
        final Path temporary = Files.createTempFile(parent, ".atlas-image-result-", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                moved = true;
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IllegalStateException("atomic result publication is unavailable", unsupported);
            }
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

}
