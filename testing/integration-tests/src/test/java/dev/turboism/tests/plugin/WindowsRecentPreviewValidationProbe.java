package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileService;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

/**
 * Manual-test-only SDK probe for the recent-files preview slice. Runs its own
 * phases against the public SDK (plus Robot-driven host UI interaction) and
 * writes an atomic result properties file plus log markers; failures record
 * only {@code failureClass} and {@code failurePhase}, never paths.
 */
public final class WindowsRecentPreviewValidationProbe implements CubismPlugin {

    static final String PRODUCTION_PLUGIN_ID = "dev.turboism.plugin.recent-preview";
    static final String POPUP_IMAGE_LABEL = "panel-image";
    static final int TARGET_SIZE = 150;
    static final int MAX_PNG_BYTES = 1024 * 1024;
    static final long RECENT_LIST_DEADLINE_MILLIS = 120_000L;
    static final long CAPTURE_DEADLINE_MILLIS = 60_000L;
    static final long SAVED_EVENT_DEADLINE_MILLIS = 90_000L;
    static final long CACHE_DEADLINE_MILLIS = 90_000L;
    static final long POPUP_DEADLINE_MILLIS = 90_000L;
    static final long CLOSE_DEADLINE_MILLIS = 20_000L;
    static final long POLL_MILLIS = 500L;
    static final long SAVE_FOCUS_POLL_MILLIS = 90_000L;
    static final long SAVE_FOCUS_POLL_INTERVAL_MILLIS = 2_000L;
    private static final long MENU_OPEN_POLL_MILLIS = 2_000L;
    private static final long MENU_OPEN_SETTLE_MILLIS = 300L;
    private static final long MENU_OPEN_RETRY_GAP_MILLIS = 500L;
    private static final int MENU_OPEN_ATTEMPTS = 4;
    private static final long DOCUMENT_OPEN_DEADLINE_MILLIS = 120_000L;
    private static final long DOCUMENT_CLOSE_DEADLINE_MILLIS = 30_000L;
    private static final long WINDOW_ACTIVATE_POLL_MILLIS = 10_000L;
    private static final int TITLE_BAR_CLICK_OFFSET = 15;
    private static final String SCRATCH_DIRECTORY = "work";
    private static final String SCRATCH_NAME_SUFFIX = "-scratch";
    private static final String MODELING_DOCUMENT_CLASS = "com.live2d.cubism.doc.modeling.CModelingDocument";
    private static final Set<String> SAVE_LABELS = Set.of("save", "保存", "上書き保存", "저장");
    private static final long ROW_SETTLE_MILLIS = 1_500L;
    private static final long SDK_SAVE_ESCALATION_MILLIS = 5_000L;
    private static final long DOCUMENT_ACTIVATE_MILLIS = 15_000L;
    private static final long POPUP_HOVER_GRACE_MILLIS = 20_000L;
    private static final long POPUP_ESCALATE_RETRY_MILLIS = 5_000L;

    private static final Pattern WINDOWS_DRIVE_PREFIX = Pattern.compile("(?m)^[A-Za-z]:[/\\\\]");
    private static final Pattern WINDOWS_UNC_PREFIX = Pattern.compile("(?m)^\\\\{2}");
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final String PROGRESS_DIALOG_CLASS_PREFIX = "noids.framework.e.a";
    private static final Set<String> FILE_LABELS = Set.of("file", "ファイル", "文件", "파일");
    private static final Set<String> RECENT_LABELS =
            Set.of("recent", "recent files", "open recent", "最近使用したファイル", "最近的文件", "最近使用的文件", "최근 파일");

    enum HostCloseRoute {
        SYNTHETIC_WINDOW_CLOSING,
        ROBOT_ALT_F4
    }

    private PluginContext context;
    private volatile Thread validationThread;
    private volatile String phase = "start";
    private volatile boolean resultPublished;
    private volatile String hostVersion;
    private volatile String fixtureName = "fixture.cmo3";
    private volatile String workingName = "fixture-scratch.cmo3";
    private volatile String runId = "unknown";
    private volatile int recentCount;
    private volatile boolean idOpaque;
    private volatile boolean pathAbsolute;
    private volatile boolean pathEndsWithWorking;
    private volatile int directCaptureWidth;
    private volatile int directCaptureHeight;
    private volatile String directPngSha256 = "";
    private volatile int directPngColors;
    private volatile boolean scratchOpened;
    private volatile boolean savedEventMatched;
    private volatile boolean productionCachePng;
    private volatile boolean productionIndex;
    private volatile boolean popupThumbnail;
    private volatile BufferedImage productionThumbnail;
    private final AtomicReference<RecentFileId> recentId = new AtomicReference<>();
    private final AtomicReference<String> savedContentName = new AtomicReference<>();
    private volatile String expectedSavedContentId;
    private volatile Path workingPath;
    private volatile Object hostApp;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.logger().info("Windows recent preview validation probe initialized");
    }

    @Override
    public void enable() {
        validationThread = new Thread(this::runValidation, "turboism-recent-preview-validation");
        validationThread.setDaemon(true);
        validationThread.start();
    }

    @Override
    public void disable() {
        interruptValidation();
    }

    @Override
    public void shutdown() {
        interruptValidation();
    }

    @Override
    public void onModelSaved(final ProjectContentSnapshot model) {
        if (model == null) return;
        final PluginContext current = context;
        if (current != null) {
            current.logger()
                    .info("Recent preview save event kind=" + model.kind() + " name=" + model.name() + " contentId="
                            + model.contentId());
        }
        final String expected = expectedSavedContentId;
        final boolean matched =
                expected != null ? expected.equals(model.contentId()) : matchesFixtureName(model.name(), workingName);
        if (matched) {
            savedContentName.compareAndSet(null, model.name());
        }
    }

    private void interruptValidation() {
        final Thread thread = validationThread;
        if (thread != null) thread.interrupt();
    }

    private void runValidation() {
        try {
            phase = "config";
            hostVersion = System.getProperty("turboism.validation.hostVersion");
            fixtureName = System.getProperty("turboism.validation.fixtureName", "fixture.cmo3");
            workingName = scratchName(fixtureName);
            runId = System.getProperty("turboism.validation.runId", "unknown");
            hostCloseRoute(hostVersion);
            openScratchDocument();
            validateRecentList();
            final RecentFileId id = recentId.get();
            validateDirectCapture(id);
            validateSavedEvent(id);
            validateProductionCache(id);
            validatePopup(id);
            publishResult("PASS");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            context.logger().info("Recent preview validation interrupted; no result published");
        } catch (CompletionException wrapped) {
            final Throwable cause = wrapped.getCause();
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                context.logger().info("Recent preview validation interrupted; no result published");
            } else {
                publishFailure(cause != null ? cause : wrapped);
            }
        } catch (Exception failure) {
            publishFailure(failure);
        } finally {
            if (resultPublished && Boolean.getBoolean("turboism.validation.exitOnComplete")) {
                requestHostClose();
            }
        }
    }

    // --- phase (a0): task-private scratch document --------------------------------

    /**
     * Redirects every later phase at a task-private working copy: the runner
     * copies the fixture under the task directory and the host opens it at
     * launch, so a real save would rewrite that copied fixture and trip the
     * runner's unconditional copied-fixture-unchanged invariant. The probe
     * copies the delivered fixture into the plugin state directory, closes the
     * launch document and reopens the copy through the same
     * {@code CEAppCtrl.command_open(File, boolean)} entry the launch argument
     * and the reviewed mcp save/reopen flow map to (async=false, the synchronous
     * open the mcp persistence probe drives on these exact hosts). The working
     * copy carries a distinct file name so the recent-list entry, the
     * File->Recent menu item and the active-document leaf all match
     * unambiguously; paths are never logged.
     */
    private void openScratchDocument() throws Exception {
        phase = "scratch-open";
        final String launchPath = awaitRecentEntry(fixtureName)
                .path()
                .orElseThrow(() -> new IllegalStateException("recent file summary carries no path"));
        final Path fixture = Path.of(launchPath);
        final Path scratchDirectory = context.paths().stateDir().resolve(SCRATCH_DIRECTORY);
        Files.createDirectories(scratchDirectory);
        final Path scratch = scratchDirectory.resolve(workingName);
        if (scratch.toAbsolutePath().normalize().equals(fixture.toAbsolutePath().normalize())) {
            throw new IllegalStateException("scratch path must differ from the delivered fixture path");
        }
        Files.copy(fixture, scratch, StandardCopyOption.REPLACE_EXISTING);
        awaitOwnedDocument(fixture, "launch fixture");
        closeOwnedDocument(fixture);
        commandOpen(scratch);
        workingPath = scratch;
        scratchOpened = true;
        context.logger().info("Recent preview scratch document opened workingName=" + workingName);
    }

    /** The sibling working name derived from the delivered fixture name. */
    static String scratchName(final String fixtureName) {
        final String base = fixtureName == null || fixtureName.isBlank() ? "fixture.cmo3" : fixtureName;
        final int dot = base.lastIndexOf('.');
        return dot < 0
                ? base + SCRATCH_NAME_SUFFIX
                : base.substring(0, dot) + SCRATCH_NAME_SUFFIX + base.substring(dot);
    }

    /**
     * Whether the owned file is the active document with a usable model. Polls
     * tolerate a busy EDT (the host load keeps it occupied) until the caller's
     * deadline cuts the wait.
     */
    private boolean documentReady(final Path expected) {
        try {
            return Boolean.TRUE.equals(onEdt(() -> ownedFile(currentDocument(), expected)
                    && context.cubism().activeModel().isPresent()));
        } catch (Exception busy) {
            return false;
        }
    }

    private void awaitOwnedDocument(final Path expected, final String stage) throws Exception {
        final long deadline = System.nanoTime() + DOCUMENT_OPEN_DEADLINE_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (documentReady(expected)) return;
            Thread.sleep(POLL_MILLIS);
        }
        throw new IllegalStateException("the " + stage + " document did not become the ready active document");
    }

    /**
     * Closes the launch document through the reviewed native
     * {@code closeFile(boolean, boolean)} call, then requires the host to reach
     * the no-document state before the scratch open (the same ordering the mcp
     * persistence flow relies on).
     */
    private void closeOwnedDocument(final Path expected) throws Exception {
        onEdt(
                () -> {
                    final Object document = currentDocument();
                    if (!ownedFile(document, expected)) {
                        throw new IllegalStateException("refusing to close a different active document");
                    }
                    final Object closed = invoke(
                            document.getClass().getMethod("closeFile", boolean.class, boolean.class),
                            document,
                            false,
                            false);
                    if (!Boolean.TRUE.equals(closed)) {
                        throw new IllegalStateException("native task document close failed");
                    }
                    return null;
                },
                DOCUMENT_CLOSE_DEADLINE_MILLIS);
        final long deadline = System.nanoTime() + DOCUMENT_CLOSE_DEADLINE_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (noActiveDocument()) return;
            Thread.sleep(POLL_MILLIS);
        }
        throw new IllegalStateException("the launch document did not close");
    }

    private boolean noActiveDocument() {
        try {
            return Boolean.TRUE.equals(onEdt(() -> currentDocument() == null));
        } catch (Exception busy) {
            return false;
        }
    }

    private void commandOpen(final Path scratch) throws Exception {
        onEdt(
                () -> {
                    if (currentDocument() != null) {
                        throw new IllegalStateException("cannot open while another document is active");
                    }
                    invoke(
                            hostApp().getClass().getMethod("command_open", File.class, boolean.class),
                            hostApp(),
                            scratch.toFile(),
                            false);
                    return null;
                },
                DOCUMENT_OPEN_DEADLINE_MILLIS);
        awaitOwnedDocument(scratch, "scratch");
    }

    private Object currentDocument() throws Exception {
        return invoke(hostApp().getClass().getMethod("getCurrentDoc"), hostApp());
    }

    /**
     * The exact host application instance through {@code CEAppCtrl}: the same
     * loader-walking lookup the mcp persistence probe uses, with the clipmask
     * probe's {@code _instance} field fallback for hosts lacking the synthetic
     * companion accessor.
     */
    private Object hostApp() throws Exception {
        Object app = hostApp;
        if (app == null) {
            final Set<ClassLoader> loaders = new LinkedHashSet<>();
            for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
                loaders.add(loader);
            }
            loaders.add(ClassLoader.getSystemClassLoader());
            for (Frame frame : Frame.getFrames()) {
                loaders.add(frame.getClass().getClassLoader());
            }
            Class<?> appClass = null;
            for (ClassLoader loader : loaders) {
                if (loader == null) continue;
                try {
                    appClass = Class.forName("com.live2d.cubism.CEAppCtrl", false, loader);
                    break;
                } catch (ClassNotFoundException absent) {
                    // Try another already-present host loader.
                }
            }
            if (appClass == null) {
                throw new IllegalStateException("exact host application class is unavailable");
            }
            try {
                app = invoke(appClass.getMethod("access$get_instance$cp"), null);
            } catch (NoSuchMethodException missingAccessor) {
                final Field instance = appClass.getDeclaredField("_instance");
                instance.setAccessible(true);
                app = instance.get(null);
            }
            if (app == null) {
                throw new IllegalStateException("exact host application instance is unavailable");
            }
            hostApp = app;
        }
        return app;
    }

    /** The same owned-file identity check as McpTexturePersistenceProbe.requireFile. */
    private static boolean ownedFile(final Object document, final Path expected) throws Exception {
        if (document == null
                || !MODELING_DOCUMENT_CLASS.equals(document.getClass().getName())) {
            return false;
        }
        final Object file = invoke(document.getClass().getMethod("getFile"), document);
        try {
            return file instanceof File && Files.isSameFile(((File) file).toPath(), expected);
        } catch (java.io.IOException unreadable) {
            return false;
        }
    }

    /** Reflection invoke that unwraps the real cause, matching the mcp probe. */
    private static Object invoke(final Method method, final Object target, final Object... arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    // --- phase (a): recent list ------------------------------------------------

    private void validateRecentList() throws Exception {
        phase = "recent-list";
        final RecentFileSummary working = awaitRecentEntry(workingName);
        recentCount = context.services()
                .find(RecentFileService.class)
                .orElse(RecentFileService.unavailable())
                .list()
                .size();
        final String idValue = working.id().value();
        idOpaque = isOpaqueHexId(idValue);
        if (!idOpaque) {
            throw new IllegalStateException("recent file id is not opaque 64-lowercase-hex");
        }
        final String path =
                working.path().orElseThrow(() -> new IllegalStateException("recent file summary carries no path"));
        pathAbsolute = isAbsolutePath(path);
        pathEndsWithWorking = endsWithSeparator(path, workingName);
        if (!pathAbsolute || !pathEndsWithWorking) {
            throw new IllegalStateException("recent file path is not absolute or does not end with the working name");
        }
        recentId.set(working.id());
        context.logger()
                .info("Recent preview recent-list displayName=" + working.displayName()
                        + " idOpaque=" + idOpaque + " pathAbsolute=" + pathAbsolute
                        + " pathEndsWithWorking=" + pathEndsWithWorking + " recentCount=" + recentCount);
    }

    private RecentFileSummary awaitRecentEntry(final String displayName) throws Exception {
        final long deadline = System.nanoTime() + RECENT_LIST_DEADLINE_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            final List<RecentFileSummary> files = context.services()
                    .find(RecentFileService.class)
                    .orElse(RecentFileService.unavailable())
                    .list();
            for (RecentFileSummary file : files) {
                if (displayName.equals(file.displayName())) {
                    return file;
                }
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new IllegalStateException("recent-file list did not expose the " + displayName + " entry");
    }

    // --- phase (b): direct capture ---------------------------------------------

    private void validateDirectCapture(final RecentFileId id) throws Exception {
        phase = "direct-capture";
        final ScreenshotCaptureResult capture;
        try {
            capture = await(
                    context.services()
                            .find(ScreenshotCaptureService.class)
                            .orElse(ScreenshotCaptureService.unavailable())
                            .capture(new ScreenshotCaptureRequest(id, TARGET_SIZE, TARGET_SIZE)),
                    CAPTURE_DEADLINE_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            logDirectCaptureDiagnostics(failure);
            throw failure;
        }
        if (!id.equals(capture.id())) {
            throw new IllegalStateException("screenshot result id does not match the request");
        }
        final ScreenshotImage image = capture.image();
        directCaptureWidth = image.width();
        directCaptureHeight = image.height();
        if (directCaptureWidth < 1
                || directCaptureWidth > TARGET_SIZE
                || directCaptureHeight < 1
                || directCaptureHeight > TARGET_SIZE) {
            throw new IllegalStateException("screenshot dimensions are not bounded to 1..150");
        }
        final byte[] png = image.png();
        if (!isBoundedPng(png, TARGET_SIZE)) {
            throw new IllegalStateException("screenshot PNG is not readable or bounded");
        }
        writeBytes(new StoragePath(StorageRoot.STATE, "preview.png"), png);
        final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        directPngColors = distinctSampledColors(decoded);
        directPngSha256 = sha256Hex(png);
        context.logger()
                .info("Recent preview direct capture width=" + directCaptureWidth
                        + " height=" + directCaptureHeight + " colors=" + directPngColors
                        + " pngSha256=" + directPngSha256);
    }

    /**
     * Failure diagnostics for the direct-capture phase: exception class plus the
     * visible window set (class, bounds, showing/active). Never logs paths.
     */
    private void logDirectCaptureDiagnostics(final Throwable failure) {
        try {
            final List<String> windows = onEdt(() -> {
                final List<String> entries = new java.util.ArrayList<>();
                for (Window window : Window.getWindows()) {
                    entries.add(window.getClass().getName()
                            + " bounds=" + window.getX() + "," + window.getY()
                            + " " + window.getWidth() + "x" + window.getHeight()
                            + " showing=" + window.isShowing()
                            + " active=" + window.isActive()
                            + " focused=" + window.isFocused());
                }
                return entries;
            });
            context.logger()
                    .error("Recent preview direct capture FAILED exceptionClass="
                            + failure.getClass().getName() + " windows=" + windows);
        } catch (Exception diagnosticFailure) {
            context.logger()
                    .error("Recent preview direct capture FAILED exceptionClass="
                            + failure.getClass().getName()
                            + " windowEnumerationFailed="
                            + diagnosticFailure.getClass().getName());
        }
    }

    // --- phase (c): typed save event via the host File menu --------------------

    private void validateSavedEvent(final RecentFileId id) throws Exception {
        phase = "save";
        final Path working = workingPath;
        if (working == null) {
            throw new IllegalStateException("the scratch working path was not captured before the save phase");
        }
        final long baselineModifiedMillis = Files.getLastModifiedTime(working).toMillis();
        final long baselineSize = Files.size(working);
        final Window target = onEdt(() -> selectHostWindow(Window.getWindows()));
        onEdt(() -> {
            target.toFront();
            target.requestFocus();
            return null;
        });
        recoverSaveFocus(target);
        ensureWorkingDocumentActive(target);
        expectedSavedContentId = context.cubism()
                .activeDocument()
                .flatMap(dev.turboism.sdk.cubism.DocumentSnapshot::contentId)
                .orElse(null);
        dirtyDocumentForSave();
        final String savePath = triggerMenuSave(working, baselineModifiedMillis, baselineSize);
        final long deadline = System.nanoTime() + SAVED_EVENT_DEADLINE_MILLIS * 1_000_000L;
        boolean fileModified = false;
        while (System.nanoTime() < deadline) {
            if (savedContentName.get() != null) {
                savedEventMatched = true;
                break;
            }
            if (!fileModified && fileModifiedSince(working, baselineModifiedMillis, baselineSize)) {
                fileModified = true;
            }
            Thread.sleep(POLL_MILLIS);
        }
        context.logger().info(saveDiagnostic(savedEventMatched, fileModified, savePath));
        final String failurePhase = saveFailurePhase(savedEventMatched, fileModified);
        if (failurePhase != null) {
            phase = failurePhase;
            throw new IllegalStateException(
                    "save-event".equals(failurePhase)
                            ? "working file was saved but ModelFileHooks.onModelSaved did not fire"
                            : "typed ModelFileHooks.onModelSaved was not observed and the working file was not modified");
        }
        context.logger().info("Recent preview saved event matched=" + savedEventMatched);
    }

    /**
     * Wine/Proton focus recovery before the Robot save trigger: a real pointer click
     * on the main window's content center, then a bounded poll (2s interval,
     * up to SAVE_FOCUS_POLL_MILLIS) for the main window to become active or
     * the persistent progress dialog (class name containing
     * "noids.framework.e.a") to disappear. If the window is still not active
     * after the poll, the click is retried once and the phase proceeds anyway
     * (Ctrl+S may still work if the dialog is non-modal). Never touches the
     * progress dialog itself.
     */
    private void recoverSaveFocus(final Window target) throws Exception {
        final Robot robot = new Robot();
        clickAt(robot, onEdt(() -> centerOf(target)));
        robot.delay(300);
        boolean active = false;
        final long deadline = System.nanoTime() + SAVE_FOCUS_POLL_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            final boolean[] snapshot = onEdt(() -> saveFocusSnapshot(target));
            active = snapshot[0];
            if (active || !snapshot[1]) break;
            Thread.sleep(SAVE_FOCUS_POLL_INTERVAL_MILLIS);
        }
        if (!active) {
            clickAt(robot, onEdt(() -> centerOf(target)));
        }
        final boolean[] outcome = onEdt(() -> saveFocusSnapshot(target));
        context.logger()
                .info("Recent preview save focus active=" + outcome[0] + " progressDialogVisible=" + outcome[1]);
    }

    private boolean[] saveFocusSnapshot(final Window target) {
        boolean progressVisible = false;
        for (final Window window : Window.getWindows()) {
            if (window.isShowing() && window.getClass().getName().contains(PROGRESS_DIALOG_CLASS_PREFIX)) {
                progressVisible = true;
                break;
            }
        }
        return new boolean[] {target.isActive(), progressVisible};
    }

    /**
     * Marks the task-owned document dirty through a single validated SDK
     * parameter write so the host's save command has real work: on an
     * unmodified document the native save can be a no-op (or a disabled row),
     * which produced savedEvent=false on every earlier run. A write failure is
     * diagnostic-only — the menu path is still exercised so the failure stays
     * attributable.
     */
    private void dirtyDocumentForSave() {
        try {
            final dev.turboism.sdk.cubism.model.Parameter parameter =
                    context.cubism().model().active().parameters().all().stream()
                            .findFirst()
                            .orElse(null);
            if (parameter == null) {
                context.logger().info("Recent preview save dirtyWrite=skipped reason=no-parameter");
                return;
            }
            parameter.setValue(parameter.getValue() + 0.001f);
            context.logger().info("Recent preview save dirtyWrite=ok");
        } catch (Exception failure) {
            context.logger()
                    .info("Recent preview save dirtyWrite=failed reason="
                            + failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage()));
        }
    }

    /**
     * Whether the active document is the task working file. The snapshot's
     * relativePath leaf is the sanitized backing-file name
     * ({@code documents/<id>/<fileName>}), so comparing it against the working
     * name detects a session-restored document that took the active slot.
     */
    private boolean activeDocumentIsWorking() {
        try {
            return context.cubism()
                    .activeDocument()
                    .map(document -> {
                        final String relativePath = document.relativePath();
                        final int slash = relativePath.lastIndexOf('/');
                        final String leaf = slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
                        return workingName.equals(leaf);
                    })
                    .orElse(false);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Session restore can reopen an earlier document and leave it active — the
     * save below would then dirty and save that document instead of the working
     * file (observed on 5.2.03: a stale recent entry's file captured the whole
     * save). Re-selecting the working entry in File -> Recent through the real
     * menu path focuses its already-open document.
     */
    private void ensureWorkingDocumentActive(final Window host) throws Exception {
        if (activeDocumentIsWorking()) return;
        context.logger().info("Recent preview save active-document=mismatch; activating working via recent menu");
        final Robot robot = new Robot();
        if (!ensureFileMenuOpen(robot, host)) {
            openFileMenu(robot, host);
        }
        final Point recent = awaitMenuPoint(robot, this::recentMenuPoint, SUBMENU_DEADLINE_MILLIS);
        moveTo(robot, recent);
        final JMenu recentMenu = onEdt(this::showingRecentMenu);
        if (recentMenu != null && !awaitMenuPopup(robot, recentMenu)) {
            clickAt(robot, recent);
            if (!awaitMenuPopup(robot, recentMenu)) {
                onEdt(() -> {
                    recentMenu.setPopupMenuVisible(true);
                    return null;
                });
            }
        }
        final Point item = awaitMenuPoint(robot, this::workingItemPoint, ITEM_DEADLINE_MILLIS);
        clickAt(robot, item);
        final long deadline = System.nanoTime() + DOCUMENT_ACTIVATE_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (activeDocumentIsWorking()) {
                context.logger().info("Recent preview save active-document=working");
                return;
            }
            robot.waitForIdle();
            Thread.sleep(POLL_MILLIS);
        }
        throw new IllegalStateException("active document is not the working document after recent-menu activation");
    }

    /**
     * Triggers a save through the host File menu (the real command_save path):
     * the File menu is opened with a real click (keyboard Alt+Enter fallback),
     * then the save item is located structurally — Ctrl+S accelerator first,
     * localized label second — and clicked through the real popup. The earlier
     * blind Down+Enter row walk could only ever activate the first enabled row
     * (Enter fires the highlighted item and closes the popup), so the save row
     * was never reached on any run. Menu-open is detected by the popup itself,
     * not by a successful item match, and the visible item rows are logged so a
     * drifted save row is evidence rather than a silent retry loop. A newly
     * appeared dialog (e.g. the "Save As..." chooser) is dismissed with Escape
     * once. When the menu or the save item stays unreachable, falls back to the
     * legacy Ctrl+S path once. Returns the path used via saveMenuPath ("menu",
     * "ctrls", or "none"); never logs paths.
     */
    private String triggerMenuSave(final Path fixture, final long baselineModifiedMillis, final long baselineSize)
            throws Exception {
        final Robot robot = new Robot();
        final Window host = onEdt(() -> selectHostWindow(Window.getWindows()));
        boolean menuOpened = false;
        int attempts = 0;
        boolean clickedMenu = false;
        for (int attempt = 1; attempt <= MENU_OPEN_ATTEMPTS && !menuOpened; attempt++) {
            attempts++;
            final Point fileMenu = onEdt(() -> fileMenuPoint(host));
            if (fileMenu != null) {
                clickAt(robot, fileMenu);
                clickedMenu = true;
            } else {
                pressKey(robot, KeyEvent.VK_ALT);
                robot.delay((int) MENU_OPEN_SETTLE_MILLIS);
                pressKey(robot, KeyEvent.VK_ENTER);
            }
            final long menuDeadline = System.nanoTime() + MENU_OPEN_POLL_MILLIS * 1_000_000L;
            while (System.nanoTime() < menuDeadline) {
                menuOpened = onEdt(() -> fileMenuPopupShowing(host));
                if (menuOpened) break;
                robot.waitForIdle();
                Thread.sleep(100L);
            }
            if (!menuOpened && fileMenu != null && attempt >= 2) {
                // The host LAF toggles the popup closed on release; open it
                // through the menu model so the save item can still receive a
                // real click. Only after robot clicks demonstrably fail.
                onEdt(() -> {
                    openFileMenuPopup(host);
                    return null;
                });
                final long programmaticDeadline = System.nanoTime() + MENU_OPEN_POLL_MILLIS * 1_000_000L;
                while (System.nanoTime() < programmaticDeadline) {
                    menuOpened = onEdt(() -> fileMenuPopupShowing(host));
                    if (menuOpened) break;
                    robot.waitForIdle();
                    Thread.sleep(100L);
                }
            }
            if (!menuOpened) {
                pressEscape(robot);
                robot.waitForIdle();
                robot.delay((int) MENU_OPEN_RETRY_GAP_MILLIS);
            }
        }
        context.logger()
                .info("Recent preview save menuOpened=" + menuOpened + " attempts=" + attempts + " menuPoint="
                        + (clickedMenu ? "found" : "missing"));
        if (!menuOpened) {
            pressCtrlSBackground();
            return saveMenuPath(false, true);
        }
        final Point savePoint;
        try {
            savePoint = awaitMenuPoint(robot, this::saveMenuItemPoint, SUBMENU_DEADLINE_MILLIS);
        } catch (IllegalStateException unreachable) {
            context.logger().info("Recent preview save menu items: " + onEdt(this::menuItemDescriptors));
            pressEscape(robot);
            pressCtrlSBackground();
            return saveMenuPath(false, true);
        }
        final Set<String> dialogsBefore = onEdt(() -> visibleDialogKeys(Window.getWindows()));
        final Boolean saveItemEnabled = onEdt(this::saveMenuItemEnabled);
        clickAt(robot, savePoint);
        final long dialogDeadline = System.nanoTime() + ROW_SETTLE_MILLIS * 1_000_000L;
        while (System.nanoTime() < dialogDeadline) {
            final boolean saved = savedContentName.get() != null;
            final boolean modified = fileModifiedSince(fixture, baselineModifiedMillis, baselineSize);
            final boolean newDialog = onEdt(() -> hasNewVisibleDialog(Window.getWindows(), dialogsBefore));
            if (saved || modified || !newDialog) break;
            if (shouldCloseDialog(saved, modified, newDialog)) {
                pressEscape(robot);
                break;
            }
            Thread.sleep(100L);
        }
        final long escalationDeadline = System.nanoTime() + SDK_SAVE_ESCALATION_MILLIS * 1_000_000L;
        while (System.nanoTime() < escalationDeadline) {
            if (savedContentName.get() != null || fileModifiedSince(fixture, baselineModifiedMillis, baselineSize)) {
                return saveMenuPath(true, false);
            }
            Thread.sleep(100L);
        }
        final String sdkSave = executeSdkSave();
        context.logger().info("Recent preview save itemEnabled=" + saveItemEnabled + " sdkSave=" + sdkSave);
        return "menu+sdk";
    }

    /**
     * Whether the visible File-menu save item is enabled — a disabled item
     * swallows the Robot click with no observable effect.
     */
    private Boolean saveMenuItemEnabled() {
        for (Window window : Window.getWindows()) {
            final JMenuItem item = findSaveMenuItem(window);
            if (item != null && item.isShowing()) {
                return item.isEnabled();
            }
        }
        return null;
    }

    /**
     * Escalation for the case where the menu item visibly exists but the
     * physical click produces no save: run the same native save command the
     * menu dispatches through the verified Editor-command bridge.
     */
    private String executeSdkSave() {
        try {
            final dev.turboism.sdk.cubism.command.EditorCommandService service = context.services()
                    .find(dev.turboism.sdk.cubism.command.EditorCommandService.class)
                    .orElse(null);
            if (service == null || !service.isAvailable()) {
                return "unavailable";
            }
            return String.valueOf(service.execute(dev.turboism.sdk.cubism.command.EditorCommand.SAVE)
                    .status());
        } catch (RuntimeException failure) {
            return failure.getClass().getSimpleName();
        }
    }

    /** Screen center of the showing File-menu save item, or null when absent. */
    private Point saveMenuItemPoint() {
        for (Window window : Window.getWindows()) {
            final JMenuItem item = findSaveMenuItem(window);
            if (item != null && item.isShowing()) {
                return centerOf(item);
            }
        }
        return null;
    }

    /**
     * Opens the File menu's popup through the menu model. Used when real
     * clicks fail to leave the popup posted — under the host LAF a release on
     * the menu title can toggle the popup back closed. The save item itself
     * is still activated by a real mouse click afterwards.
     */
    private static boolean openFileMenuPopup(final Window host) {
        final JMenuBar bar = findComponent(host, JMenuBar.class);
        if (bar == null) return false;
        for (int index = 0; index < bar.getMenuCount(); index++) {
            final JMenu menu = bar.getMenu(index);
            if (menu != null && FILE_LABELS.contains(normalizeLabel(menu.getText()))) {
                menu.setPopupMenuVisible(true);
                return true;
            }
        }
        return false;
    }

    /**
     * Opens the File menu the same way {@code triggerMenuSave} does: real
     * clicks first, the menu-model popup as the fallback once clicks
     * demonstrably fail to leave it posted under the host LAF.
     */
    private boolean ensureFileMenuOpen(final Robot robot, final Window host) throws Exception {
        for (int attempt = 1; attempt <= MENU_OPEN_ATTEMPTS; attempt++) {
            final Point fileMenu = onEdt(() -> fileMenuPoint(host));
            if (fileMenu != null) {
                clickAt(robot, fileMenu);
            } else {
                pressKey(robot, KeyEvent.VK_ALT);
                robot.delay((int) MENU_OPEN_SETTLE_MILLIS);
                pressKey(robot, KeyEvent.VK_ENTER);
            }
            if (awaitFileMenuPopup(robot, host)) return true;
            if (fileMenu != null && attempt >= 2) {
                onEdt(() -> {
                    openFileMenuPopup(host);
                    return null;
                });
                if (awaitFileMenuPopup(robot, host)) return true;
            }
        }
        return false;
    }

    private boolean awaitFileMenuPopup(final Robot robot, final Window host) throws Exception {
        final long deadline = System.nanoTime() + MENU_OPEN_POLL_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (onEdt(() -> fileMenuPopupShowing(host))) return true;
            robot.waitForIdle();
            Thread.sleep(100L);
        }
        return false;
    }

    /** The File menu's popup is showing — the menu is genuinely open. */
    private static boolean fileMenuPopupShowing(final Window host) {
        final JMenuBar bar = findComponent(host, JMenuBar.class);
        if (bar == null) return false;
        for (int index = 0; index < bar.getMenuCount(); index++) {
            final JMenu menu = bar.getMenu(index);
            if (menu != null && FILE_LABELS.contains(normalizeLabel(menu.getText()))) {
                return menu.isPopupMenuVisible();
            }
        }
        return false;
    }

    private static JMenuItem findSaveMenuItem(final Component root) {
        if (root instanceof JMenuItem item && item.isShowing() && isSaveMenuItem(item)) {
            return item;
        }
        for (Component child : menuChildren(root)) {
            final JMenuItem found = findSaveMenuItem(child);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Children worth walking for menu traversal: a JMenu's popup contents are
     * not reachable through Container.getComponents(), so both surfaces are
     * enumerated.
     */
    private static List<Component> menuChildren(final Component root) {
        final List<Component> children = new java.util.ArrayList<>();
        if (root instanceof JMenu menu) {
            children.addAll(java.util.Arrays.asList(menu.getPopupMenu().getComponents()));
        }
        if (root instanceof Container container) {
            children.addAll(java.util.Arrays.asList(container.getComponents()));
        }
        return children;
    }

    /**
     * The File-menu "Save" command: identified by its Ctrl+S accelerator
     * (locale-independent), falling back to an exact normalized label so the
     * check does not collide with "Save As..." entries.
     */
    static boolean isSaveMenuItem(final JMenuItem item) {
        if (item instanceof JMenu) return false;
        final KeyStroke accelerator = item.getAccelerator();
        if (accelerator != null
                && accelerator.getKeyCode() == KeyEvent.VK_S
                && (accelerator.getModifiers() & InputEvent.CTRL_DOWN_MASK) != 0
                && (accelerator.getModifiers() & (InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) == 0) {
            return true;
        }
        return SAVE_LABELS.contains(normalizeLabel(item.getText()));
    }

    /** Popup windows are Swing popup containers whose class name contains "Popup". */
    static boolean isPopupWindow(final boolean showing, final String className) {
        return showing && className != null && className.contains("Popup");
    }

    /** Any Swing popup window currently showing — the menu is open. */
    static boolean anyPopupShowing() {
        for (Window window : Window.getWindows()) {
            if (isPopupWindow(window.isShowing(), window.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bounded "text|accelerator|enabled|class" descriptors for every showing
     * menu item, logged when the save row cannot be located so the actual menu
     * shape is preserved as evidence.
     */
    private String menuItemDescriptors() {
        final List<String> descriptors = new java.util.ArrayList<>();
        for (Window window : Window.getWindows()) {
            collectMenuItemDescriptors(window, descriptors);
        }
        if (descriptors.size() > 60) {
            return descriptors.subList(0, 60).toString() + " ...(" + (descriptors.size() - 60) + " more)";
        }
        return descriptors.toString();
    }

    private static void collectMenuItemDescriptors(final Component root, final List<String> out) {
        if (root instanceof JMenuItem item && item.isShowing()) {
            final KeyStroke accelerator = item.getAccelerator();
            out.add("[text=" + Objects.toString(item.getText(), "")
                    + " accel=" + (accelerator == null ? "none" : accelerator.toString())
                    + " enabled=" + item.isEnabled()
                    + " class=" + item.getClass().getSimpleName() + "]");
        }
        for (Component child : menuChildren(root)) {
            collectMenuItemDescriptors(child, out);
        }
    }

    // --- phase (d): production plugin cache -------------------------------------

    private void validateProductionCache(final RecentFileId id) throws Exception {
        phase = "production-cache";
        final String key = sha256Hex(id.value().getBytes(StandardCharsets.UTF_8));
        final Path cacheRoot = Path.of(System.getProperty("turboism.home"))
                .resolve("cache")
                .resolve(PRODUCTION_PLUGIN_ID)
                .resolve("recent-preview");
        final Path pngPath = cacheRoot.resolve("images").resolve(key + ".png");
        final Path indexPath = cacheRoot.resolve("index").resolve(key + ".entry");
        final long deadline = System.nanoTime() + CACHE_DEADLINE_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(pngPath) && Files.isRegularFile(indexPath)) break;
            Thread.sleep(POLL_MILLIS);
        }
        if (!Files.isRegularFile(pngPath)) {
            throw new IllegalStateException("production cache PNG was not written");
        }
        if (!Files.isRegularFile(indexPath)) {
            throw new IllegalStateException("production cache index was not written");
        }
        final byte[] png = Files.readAllBytes(pngPath);
        if (!isBoundedPng(png, TARGET_SIZE)) {
            throw new IllegalStateException("production cache PNG is not readable or bounded");
        }
        final String index = Files.readString(indexPath, StandardCharsets.UTF_8);
        if (containsAbsolutePath(index)) {
            throw new IllegalStateException("production cache index contains an absolute path");
        }
        if (!index.contains(id.value())) {
            throw new IllegalStateException("production cache index is not keyed by the opaque id");
        }
        productionThumbnail = ImageIO.read(new ByteArrayInputStream(png));
        productionCachePng = true;
        productionIndex = true;
        context.logger()
                .info("Recent preview production cache png=" + productionCachePng + " index=" + productionIndex
                        + " key=" + key);
    }

    // --- phase (e): Robot-driven File->Recent hover popup ------------------------

    private void validatePopup(final RecentFileId id) throws Exception {
        phase = "popup";
        final Robot robot = new Robot();
        final Window host = onEdt(() -> selectMenuHostWindow(Window.getWindows()));
        onEdt(() -> {
            host.toFront();
            host.requestFocus();
            return null;
        });
        robot.waitForIdle();
        Thread.sleep(600L);
        activateWindowForMenus(robot, host);

        if (!ensureFileMenuOpen(robot, host)) {
            openFileMenu(robot, host);
        }
        final Point recent;
        try {
            recent = awaitMenuPoint(robot, this::recentMenuPoint, SUBMENU_DEADLINE_MILLIS);
        } catch (IllegalStateException missing) {
            context.logger()
                    .info("Recent preview popup menu items: " + onEdt(this::menuItemDescriptors) + " windows="
                            + onEdt(this::windowDiagnostics));
            throw missing;
        }
        moveTo(robot, recent);
        final JMenu recentMenu = onEdt(this::showingRecentMenu);
        if (recentMenu != null && !awaitMenuPopup(robot, recentMenu)) {
            // The host LAF may not expand a submenu on a bare pointer move;
            // escalate to a real click, then to the menu model as a last
            // resort so the item still receives a real hover afterwards.
            clickAt(robot, recent);
            if (!awaitMenuPopup(robot, recentMenu)) {
                onEdt(() -> {
                    recentMenu.setPopupMenuVisible(true);
                    return null;
                });
            }
        }
        final Point item;
        try {
            item = awaitMenuPoint(robot, this::workingItemPoint, ITEM_DEADLINE_MILLIS);
        } catch (IllegalStateException missing) {
            context.logger()
                    .info("Recent preview popup menu items: " + onEdt(this::menuItemDescriptors) + " windows="
                            + onEdt(this::windowDiagnostics));
            throw missing;
        }
        moveTo(robot, item);
        context.logger()
                .info("Recent preview popup selectedPath="
                        + onEdt(WindowsRecentPreviewValidationProbe::selectedPathDepth));

        final long deadline = System.nanoTime() + POPUP_DEADLINE_MILLIS * 1_000_000L;
        long nextEscalation = System.nanoTime() + POPUP_HOVER_GRACE_MILLIS * 1_000_000L;
        int escalations = 0;
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(onEdt(this::popupThumbnailVisible))) {
                popupThumbnail = true;
                break;
            }
            if (System.nanoTime() >= nextEscalation) {
                escalations++;
                selectWorkingItemForPreview();
                nextEscalation = System.nanoTime() + POPUP_ESCALATE_RETRY_MILLIS * 1_000_000L;
            }
            Thread.sleep(POLL_MILLIS);
        }
        if (escalations > 0) {
            context.logger().info("Recent preview popup escalation attempts=" + escalations);
        }
        if (!popupThumbnail) {
            throw new IllegalStateException("themed hover popup with image content was not observed");
        }
        pressEscape(robot);
        context.logger().info("Recent preview popup thumbnail=" + popupThumbnail);
    }

    private static final long SUBMENU_DEADLINE_MILLIS = 30_000L;
    private static final long ITEM_DEADLINE_MILLIS = 30_000L;

    /**
     * Activates the host window for menu work: a real click on the title bar —
     * the gesture a window manager always treats as raise+focus — then a bounded
     * poll for {@code isActive}. The scratch-opened window never takes OS
     * activation from {@code toFront}/{@code requestFocus} hints under Wine,
     * which lets the host LAF collapse posted popups before the Recent submenu
     * can be located. Falls back to the save phase's content-center click when
     * the title click leaves the window inactive; callers proceed either way
     * because real menu clicks have succeeded on never-active windows.
     */
    private void activateWindowForMenus(final Robot robot, final Window host) throws Exception {
        final Point titlePoint = onEdt(() -> {
            final Point topLeft = host.getLocationOnScreen();
            return new Point(topLeft.x + host.getWidth() / 2, topLeft.y + TITLE_BAR_CLICK_OFFSET);
        });
        clickAt(robot, titlePoint);
        robot.waitForIdle();
        boolean active = false;
        final long deadline = System.nanoTime() + WINDOW_ACTIVATE_POLL_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            active = Boolean.TRUE.equals(onEdt(host::isActive));
            if (active) break;
            Thread.sleep(200L);
        }
        if (!active) {
            recoverSaveFocus(host);
            active = onEdt(() -> saveFocusSnapshot(host)[0]);
        }
        context.logger().info("Recent preview popup window active=" + active);
    }

    /**
     * The largest window that carries a File-labelled menu — a leftover
     * document-less frame (e.g. the start page shown between the launch
     * document close and the scratch open) is never selected even when it is
     * bigger, since its File menu cannot expose the Recent submenu. Falls back
     * to {@link #selectHostWindow} when no menu-carrying window exists.
     */
    static Window selectMenuHostWindow(final Window[] windows) {
        Window target = null;
        long largestArea = -1L;
        for (final Window window : windows) {
            if (window instanceof java.awt.Dialog
                    || !window.isDisplayable()
                    || !window.isVisible()
                    || !hasFileMenu(window)) {
                continue;
            }
            final long area = (long) window.getWidth() * window.getHeight();
            if (area > largestArea) {
                target = window;
                largestArea = area;
            }
        }
        return target != null ? target : selectHostWindow(windows);
    }

    /** Whether the window's menu bar carries a File-labelled top-level menu. */
    private static boolean hasFileMenu(final Window host) {
        final JMenuBar bar = findComponent(host, JMenuBar.class);
        if (bar == null) return false;
        for (int index = 0; index < bar.getMenuCount(); index++) {
            final JMenu menu = bar.getMenu(index);
            if (menu != null && FILE_LABELS.contains(normalizeLabel(menu.getText()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bounded per-window state dump for menu failures — class, bounds,
     * showing/active/focused, File-menu presence and whether the frame title
     * carries the working file stem. Never logs paths or raw titles.
     */
    private String windowDiagnostics() {
        final String stem;
        final int dot = workingName.lastIndexOf('.');
        stem = dot < 0 ? workingName : workingName.substring(0, dot);
        final List<String> entries = new java.util.ArrayList<>();
        for (final Window window : Window.getWindows()) {
            String titleMatch = "n/a";
            if (window instanceof Frame frame && frame.getTitle() != null) {
                titleMatch = String.valueOf(frame.getTitle().contains(stem));
            }
            entries.add(window.getClass().getSimpleName()
                    + " " + window.getWidth() + "x" + window.getHeight()
                    + " showing=" + window.isShowing()
                    + " active=" + window.isActive()
                    + " focused=" + window.isFocused()
                    + " fileMenu=" + hasFileMenu(window)
                    + " titleMatch=" + titleMatch);
        }
        return entries.toString();
    }

    private void openFileMenu(final Robot robot, final Window host) throws Exception {
        final Point fileMenu = onEdt(() -> fileMenuPoint(host));
        if (fileMenu != null) {
            clickAt(robot, fileMenu);
            return;
        }
        // Keyboard fallback: Alt focuses the menu bar; open each menu until the
        // Recent submenu becomes reachable.
        robot.keyPress(KeyEvent.VK_ALT);
        robot.keyRelease(KeyEvent.VK_ALT);
        for (int attempt = 0; attempt < 12; attempt++) {
            robot.keyPress(KeyEvent.VK_DOWN);
            robot.keyRelease(KeyEvent.VK_DOWN);
            robot.waitForIdle();
            Thread.sleep(300L);
            if (onEdt(this::recentMenuPoint) != null) return;
            robot.keyPress(KeyEvent.VK_ESCAPE);
            robot.keyRelease(KeyEvent.VK_ESCAPE);
            robot.keyPress(KeyEvent.VK_RIGHT);
            robot.keyRelease(KeyEvent.VK_RIGHT);
        }
        throw new IllegalStateException("File -> Recent menu was not reachable");
    }

    private Point awaitMenuPoint(final Robot robot, final Callable<Point> lookup, final long timeoutMillis)
            throws Exception {
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            final Point point = onEdt(lookup);
            if (point != null) return point;
            robot.waitForIdle();
            Thread.sleep(POLL_MILLIS);
        }
        throw new IllegalStateException("expected menu component did not appear");
    }

    private static Point fileMenuPoint(final Window host) {
        final JMenuBar bar = findComponent(host, JMenuBar.class);
        if (bar == null) return null;
        for (int index = 0; index < bar.getMenuCount(); index++) {
            final JMenu menu = bar.getMenu(index);
            if (menu != null && FILE_LABELS.contains(normalizeLabel(menu.getText()))) {
                return centerOf(menu);
            }
        }
        return null;
    }

    private Point recentMenuPoint() {
        for (Window window : Window.getWindows()) {
            final JMenu menu = findMenuByLabel(window, RECENT_LABELS);
            if (menu != null && menu.isShowing()) {
                return centerOf(menu);
            }
        }
        return null;
    }

    private Point workingItemPoint() {
        for (Window window : Window.getWindows()) {
            final JMenuItem item = findMenuItemFor(window, workingName);
            if (item != null && item.isShowing()) {
                return centerOf(item);
            }
        }
        return null;
    }

    private static JMenuItem findMenuItemFor(final Component root, final String workingName) {
        if (root instanceof JMenuItem item && item.isShowing()) {
            final String text = Objects.toString(item.getText(), "");
            final String command = Objects.toString(item.getActionCommand(), "");
            if (matchesFixtureText(text, workingName) || matchesFixtureText(command, workingName)) {
                return item;
            }
        }
        for (Component child : menuChildren(root)) {
            final JMenuItem found = findMenuItemFor(child, workingName);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The working item anywhere in the menu model, even when its popup is not
     * posted — unlike {@link #findMenuItemFor} no showing check applies, because
     * selecting the item through {@code MenuSelectionManager} posts the chain.
     */
    private static JMenuItem findMenuItemInModel(final Component root, final String workingName) {
        if (root instanceof JMenuItem item && !(root instanceof JMenu)) {
            final String text = Objects.toString(item.getText(), "");
            final String command = Objects.toString(item.getActionCommand(), "");
            if (matchesFixtureText(text, workingName) || matchesFixtureText(command, workingName)) {
                return item;
            }
        }
        for (Component child : menuChildren(root)) {
            final JMenuItem found = findMenuItemInModel(child, workingName);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean matchesFixtureText(final String value, final String workingName) {
        if (value.isEmpty()) return false;
        final String normalized = value.trim();
        if (normalized.equals(workingName)) return true;
        return normalized.endsWith("/" + workingName) || normalized.endsWith("\\" + workingName);
    }

    /** The showing File->Recent submenu component, or null. */
    private JMenu showingRecentMenu() {
        for (Window window : Window.getWindows()) {
            final JMenu menu = findMenuByLabel(window, RECENT_LABELS);
            if (menu != null && menu.isShowing()) {
                return menu;
            }
        }
        return null;
    }

    private boolean awaitMenuPopup(final Robot robot, final JMenu menu) throws Exception {
        final long deadline = System.nanoTime() + MENU_OPEN_POLL_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (onEdt(menu::isPopupMenuVisible)) return true;
            robot.waitForIdle();
            Thread.sleep(100L);
        }
        return false;
    }

    private static JMenu findMenuByLabel(final Component root, final Set<String> labels) {
        if (root instanceof JMenu menu && labels.contains(normalizeLabel(menu.getText()))) {
            return menu;
        }
        for (Component child : menuChildren(root)) {
            final JMenu found = findMenuByLabel(child, labels);
            if (found != null) return found;
        }
        return null;
    }

    private static int selectedPathDepth() {
        final MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
        return path == null ? 0 : path.length;
    }

    /**
     * Escalation for the case where the physical pointer hover produces no
     * preview: drives the real menu-selection machinery — the selected path
     * built from the item's invoker chain posts every popup in the chain and
     * selects the item (the state the bridge's rollover/selected ChangeListener
     * and selection-path reconcile observe), then dispatches a real
     * MOUSE_ENTERED to the item so the bridge's hover listener also runs. The
     * working item is located in the menu model even when its popup is not
     * posted yet, so a previously closed menu chain is re-established.
     * Returns whether a working item was found to select.
     */
    private boolean selectWorkingItemForPreview() {
        try {
            return Boolean.TRUE.equals(onEdt(() -> {
                for (Window window : Window.getWindows()) {
                    final JMenuItem item = findMenuItemInModel(window, workingName);
                    if (item == null) continue;
                    final List<MenuElement> path = new java.util.ArrayList<>();
                    path.add(0, item);
                    Component parent = item.getParent();
                    while (parent != null) {
                        if (parent instanceof MenuElement element) {
                            path.add(0, element);
                        }
                        parent = parent instanceof javax.swing.JPopupMenu popup
                                ? popup.getInvoker()
                                : parent.getParent();
                    }
                    MenuSelectionManager.defaultManager().setSelectedPath(path.toArray(new MenuElement[0]));
                    item.getModel().setArmed(true);
                    item.dispatchEvent(new java.awt.event.MouseEvent(
                            item,
                            java.awt.event.MouseEvent.MOUSE_ENTERED,
                            System.currentTimeMillis(),
                            0,
                            0,
                            0,
                            0,
                            false));
                    return true;
                }
                return false;
            }));
        } catch (Exception failure) {
            context.logger()
                    .info("Recent preview popup selection escalation failed="
                            + failure.getClass().getSimpleName());
            return true;
        }
    }

    private boolean popupThumbnailVisible() {
        for (Window window : Window.getWindows()) {
            if (findPopupImage(window)) return true;
        }
        return false;
    }

    private boolean findPopupImage(final Component root) {
        if (root instanceof JLabel label
                && POPUP_IMAGE_LABEL.equals(label.getName())
                && label.getIcon() != null
                && validThumbnailIcon(label.getIcon())
                && thumbnailContentMatches(iconImage(label.getIcon()))) {
            return true;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (findPopupImage(child)) return true;
            }
        }
        return false;
    }

    /**
     * Whether the painted icon carries real thumbnail content: a multi-color
     * capture, or — under hosts whose viewport readback yields solid content —
     * exactly the PNG the production plugin stored in its cache this run.
     */
    private boolean thumbnailContentMatches(final BufferedImage image) {
        if (distinctSampledColors(image) >= 2) return true;
        final BufferedImage expected = productionThumbnail;
        return expected != null && imagesEqual(image, expected);
    }

    private static boolean imagesEqual(final BufferedImage a, final BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }

    // --- host close ---------------------------------------------------------------

    private void requestHostClose() {
        try {
            final Window target = onEdt(() -> selectHostWindow(Window.getWindows()));
            final HostCloseRoute route = hostCloseRoute(hostVersion);
            if (route == HostCloseRoute.ROBOT_ALT_F4) {
                final Robot robot = new Robot();
                robot.keyPress(KeyEvent.VK_ALT);
                try {
                    robot.keyPress(KeyEvent.VK_F4);
                } finally {
                    robot.keyRelease(KeyEvent.VK_F4);
                    robot.keyRelease(KeyEvent.VK_ALT);
                }
                context.logger().info("Automated host close requested via Alt+F4");
            } else {
                SwingUtilities.invokeLater(
                        () -> target.dispatchEvent(new WindowEvent(target, WindowEvent.WINDOW_CLOSING)));
                context.logger().info("Automated host close requested via WINDOW_CLOSING");
            }
            final long deadline = System.nanoTime() + CLOSE_DEADLINE_MILLIS * 1_000_000L;
            boolean closed = false;
            while (System.nanoTime() < deadline) {
                if (Boolean.TRUE.equals(onEdt(() -> !target.isDisplayable() || !target.isVisible()))) {
                    closed = true;
                    break;
                }
                Thread.sleep(200L);
            }
            context.logger().info("Recent preview host close confirmed=" + closed);
        } catch (Exception failure) {
            context.logger()
                    .error("Recent preview automated host close failed: "
                            + failure.getClass().getName());
        }
    }

    // --- result publication ---------------------------------------------------------

    private void publishResult(final String status) {
        try {
            final LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            fields.put("schemaVersion", "1");
            fields.put("runId", runId);
            fields.put("hostVersion", hostVersion);
            fields.put("fixtureName", fixtureName);
            fields.put("workingName", workingName);
            fields.put("scratchOpened", String.valueOf(scratchOpened));
            fields.put("recentCount", String.valueOf(recentCount));
            fields.put("idOpaque", String.valueOf(idOpaque));
            fields.put("pathAbsolute", String.valueOf(pathAbsolute));
            fields.put("pathEndsWithWorking", String.valueOf(pathEndsWithWorking));
            fields.put("directCaptureWidth", String.valueOf(directCaptureWidth));
            fields.put("directCaptureHeight", String.valueOf(directCaptureHeight));
            fields.put("directPngSha256", directPngSha256);
            fields.put("directPngColors", String.valueOf(directPngColors));
            fields.put("savedEventMatched", String.valueOf(savedEventMatched));
            fields.put("productionCachePng", String.valueOf(productionCachePng));
            fields.put("productionIndex", String.valueOf(productionIndex));
            fields.put("popupThumbnail", String.valueOf(popupThumbnail));
            fields.put("status", status);
            writeUtf8(new StoragePath(StorageRoot.STATE, "result.properties"), resultContent(fields));
            resultPublished = true;
            context.logger().info("RECENT_PREVIEW_HOST_RESULT status=" + status);
        } catch (Exception writeFailure) {
            context.logger()
                    .error("RECENT_PREVIEW_HOST_RESULT status=FAIL"
                            + " failureClass=" + writeFailure.getClass().getName()
                            + " phase=" + phase);
        }
    }

    private void publishFailure(final Throwable failure) {
        try {
            writeUtf8(
                    new StoragePath(StorageRoot.STATE, "result.properties"),
                    failureResult(failure.getClass().getName(), phase));
            resultPublished = true;
        } catch (Exception writeFailure) {
            context.logger()
                    .error("RECENT_PREVIEW_HOST_RESULT status=FAIL"
                            + " failureClass=" + writeFailure.getClass().getName()
                            + " phase=" + phase);
        }
        context.logger()
                .error("RECENT_PREVIEW_HOST_RESULT status=FAIL"
                        + " failureClass=" + failure.getClass().getName()
                        + " failurePhase=" + phase);
    }

    // --- pure helpers (unit-tested) ---------------------------------------------------

    /**
     * The same reviewed host-close mapping as {@link WindowsHistoryNativeUiHostClose}:
     * focused Robot Alt+F4 is the verified 5302 route, while every other admitted exact
     * host accepts the synthetic task-window close event.
     */
    static HostCloseRoute hostCloseRoute(final String hostVersion) {
        if (hostVersion == null) {
            throw new IllegalArgumentException(
                    "turboism.validation.hostVersion must be an exact installed version code");
        }
        return switch (hostVersion) {
            case "5000",
                    "5001",
                    "5002",
                    "5003",
                    "5004",
                    "5005",
                    "5006",
                    "5100",
                    "5101",
                    "5102",
                    "5103",
                    "5200",
                    "5201",
                    "5202",
                    "5203",
                    "5300",
                    "5301",
                    "5303",
                    "5304" -> HostCloseRoute.SYNTHETIC_WINDOW_CLOSING;
            case "5302" -> HostCloseRoute.ROBOT_ALT_F4;
            default ->
                throw new IllegalArgumentException(
                        "turboism.validation.hostVersion must be an exact installed version code: " + hostVersion);
        };
    }

    static boolean isOpaqueHexId(final String value) {
        if (value == null || value.length() != 64) return false;
        for (int index = 0; index < value.length(); index++) {
            final char current = value.charAt(index);
            if (!((current >= '0' && current <= '9') || (current >= 'a' && current <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    static boolean isAbsolutePath(final String value) {
        if (value == null || value.isBlank()) return false;
        if (value.startsWith("/") || value.startsWith("~")) return true;
        return WINDOWS_DRIVE_PREFIX.matcher(value).find()
                || WINDOWS_UNC_PREFIX.matcher(value).find();
    }

    static boolean endsWithSeparator(final String value, final String suffix) {
        if (value == null || suffix == null || suffix.isBlank()) return false;
        return value.endsWith(suffix) || value.endsWith("/" + suffix) || value.endsWith("\\" + suffix);
    }

    static boolean matchesFixtureName(final String modelName) {
        return matchesFixtureName(modelName, "fixture.cmo3");
    }

    static boolean matchesFixtureName(final String modelName, final String fixtureName) {
        final String fixture = fixtureName == null || fixtureName.isBlank() ? "fixture.cmo3" : fixtureName;
        if (modelName == null || modelName.isBlank()) return false;
        if (modelName.equals(fixture)) return true;
        final int dot = fixture.lastIndexOf('.');
        final String stem = dot < 0 ? fixture : fixture.substring(0, dot);
        return modelName.equals(stem) || modelName.startsWith(stem + ".");
    }

    static boolean isPng(final byte[] value) {
        if (value == null || value.length < PNG_SIGNATURE.length) return false;
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (value[index] != PNG_SIGNATURE[index]) return false;
        }
        return true;
    }

    static boolean isBoundedPng(final byte[] value, final int maxDimension) {
        if (!isPng(value) || value.length > MAX_PNG_BYTES) return false;
        try {
            final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(value));
            return decoded != null
                    && decoded.getWidth() >= 1
                    && decoded.getWidth() <= maxDimension
                    && decoded.getHeight() >= 1
                    && decoded.getHeight() <= maxDimension;
        } catch (Exception ignored) {
            return false;
        }
    }

    static int distinctSampledColors(final BufferedImage image) {
        final int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 32);
        final Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                colors.add(image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        colors.add(image.getRGB(0, 0) & 0xFFFFFF);
        colors.add(image.getRGB(image.getWidth() - 1, 0) & 0xFFFFFF);
        colors.add(image.getRGB(0, image.getHeight() - 1) & 0xFFFFFF);
        colors.add(image.getRGB(image.getWidth() - 1, image.getHeight() - 1) & 0xFFFFFF);
        return colors.size();
    }

    static boolean validThumbnailIcon(final javax.swing.Icon icon) {
        return icon != null
                && icon.getIconWidth() >= 1
                && icon.getIconWidth() <= TARGET_SIZE
                && icon.getIconHeight() >= 1
                && icon.getIconHeight() <= TARGET_SIZE;
    }

    static String resultContent(final Map<String, String> fields) {
        final StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            final String key = entry.getKey();
            final String value = entry.getValue();
            if (key == null
                    || key.isBlank()
                    || key.indexOf('=') >= 0
                    || value == null
                    || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("result field must be a safe single-line key=value pair");
            }
            content.append(key).append('=').append(value).append('\n');
        }
        return content.toString();
    }

    static String failureResult(final String failureClass, final String failurePhase) {
        final LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("status", "FAIL");
        fields.put("failureClass", failureClass);
        fields.put("failurePhase", failurePhase);
        return resultContent(fields);
    }

    /**
     * Pure save-phase outcome decision: null means the phase passes, otherwise the
     * failurePhase string distinguishing keyboard/dirty problems ("save") from
     * hook problems ("save-event").
     */
    static String saveFailurePhase(final boolean savedEvent, final boolean fileModified) {
        if (savedEvent) return null;
        return fileModified ? "save-event" : "save";
    }

    /** Boolean/menuPath-only diagnostic line; never carries paths. */
    static String saveDiagnostic(final boolean savedEvent, final boolean fileModified, final String menuPath) {
        return "Recent preview save diagnostic savedEvent=" + savedEvent
                + " fileModified=" + fileModified
                + " menuPath=" + menuPath;
    }

    /**
     * Menu-path diagnostic value: "menu" when the popup opened, "ctrls" when
     * the Ctrl+S fallback was used, "none" when no save trigger was attempted.
     */
    static String saveMenuPath(final boolean menuOpened, final boolean ctrlFallbackUsed) {
        if (menuOpened) return "menu";
        return ctrlFallbackUsed ? "ctrls" : "none";
    }

    /**
     * Escape only when a new dialog appeared and the row produced no save: keeps
     * the probe out of a "Save As..." chooser without cancelling a real save.
     */
    static boolean shouldCloseDialog(
            final boolean savedEvent, final boolean fileModified, final boolean newDialogVisible) {
        return newDialogVisible && !savedEvent && !fileModified;
    }

    /** Stable identity key for a dialog window: class name + identity hash. */
    static String dialogIdentity(final String className, final int identityHashCode) {
        return className + "@" + identityHashCode;
    }

    /** Identity keys of all currently showing dialogs. */
    static Set<String> visibleDialogKeys(final Window[] windows) {
        final Set<String> keys = new HashSet<>();
        for (final Window window : windows) {
            if (window instanceof java.awt.Dialog && window.isShowing()) {
                keys.add(dialogIdentity(window.getClass().getName(), System.identityHashCode(window)));
            }
        }
        return keys;
    }

    /** True when a showing dialog is not part of the baseline set (newly appeared). */
    static boolean hasNewVisibleDialog(final Window[] windows, final Set<String> baseline) {
        for (final Window window : windows) {
            if (window instanceof java.awt.Dialog
                    && window.isShowing()
                    && !baseline.contains(
                            dialogIdentity(window.getClass().getName(), System.identityHashCode(window)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the file was modified after the recorded baseline (lastModified
     * newer or size changed); false when unchanged or unreadable (deleted).
     */
    static boolean fileModifiedSince(final Path path, final long baselineModifiedMillis, final long baselineSize) {
        try {
            final BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.lastModifiedTime().toMillis() > baselineModifiedMillis
                    || attributes.size() != baselineSize;
        } catch (Exception unreadable) {
            return false;
        }
    }

    static boolean containsAbsolutePath(final String value) {
        if (value == null) return false;
        if (value.indexOf('\\') >= 0) return true;
        if (value.indexOf("://") >= 0) return true;
        if (value.startsWith("/") || value.startsWith("~")) return true;
        return WINDOWS_DRIVE_PREFIX.matcher(value).find();
    }

    static String sha256Hex(final byte[] value) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            final StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format(Locale.ROOT, "%02x", item));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // --- robot / EDT plumbing ----------------------------------------------------------

    private void pressCtrlSBackground() {
        final Thread robotThread = new Thread(
                () -> {
                    try {
                        Thread.sleep(400L);
                        final Robot robot = new Robot();
                        robot.keyPress(KeyEvent.VK_CONTROL);
                        try {
                            robot.keyPress(KeyEvent.VK_S);
                        } finally {
                            robot.keyRelease(KeyEvent.VK_S);
                            robot.keyRelease(KeyEvent.VK_CONTROL);
                        }
                    } catch (Exception ignored) {
                        // Save failure surfaces as the Saved-event wait timeout.
                    }
                },
                "turboism-recent-preview-robot");
        robotThread.setDaemon(true);
        robotThread.start();
    }

    private static void clickAt(final Robot robot, final Point point) {
        robot.mouseMove(point.x, point.y);
        robot.waitForIdle();
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    private static void moveTo(final Robot robot, final Point point) {
        robot.mouseMove(point.x, point.y);
        robot.waitForIdle();
    }

    private static void pressEscape(final Robot robot) {
        robot.keyPress(KeyEvent.VK_ESCAPE);
        robot.keyRelease(KeyEvent.VK_ESCAPE);
        robot.waitForIdle();
    }

    private static void pressKey(final Robot robot, final int keyCode) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    private static Point centerOf(final Component component) {
        final Point location = component.getLocationOnScreen();
        return new Point(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }

    private static <T extends Component> T findComponent(final Component root, final Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final T found = findComponent(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String normalizeLabel(final String value) {
        return java.util.Objects.toString(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("…", "")
                .replace("...", "");
    }

    static Window selectHostWindow(final Window[] windows) {
        Window target = null;
        long largestArea = -1L;
        for (final Window window : windows) {
            if (window instanceof java.awt.Dialog || !window.isDisplayable() || !window.isVisible()) {
                continue;
            }
            final long area = (long) window.getWidth() * window.getHeight();
            if (area > largestArea) {
                target = window;
                largestArea = area;
            }
        }
        if (target == null) {
            throw new IllegalStateException("No visible, displayable non-dialog host window found.");
        }
        return target;
    }

    private void writeUtf8(final StoragePath path, final String content) throws Exception {
        writeBytes(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(final StoragePath path, final byte[] content) throws Exception {
        final StorageWriteResult written =
                await(context.storage().writeBytesAtomic(path, content), 30, TimeUnit.SECONDS);
        if (!written.written()) {
            throw new IllegalStateException("storage write was not acknowledged: "
                    + written.error().map(Object::toString).orElse("no error"));
        }
    }

    private static <T> T await(final CompletionStage<T> stage, final long timeout, final TimeUnit unit)
            throws Exception {
        try {
            return stage.toCompletableFuture().get(timeout, unit);
        } catch (ExecutionException execution) {
            final Throwable cause = execution.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("completion failed without a typed cause", cause);
        }
    }

    private static <T> T onEdt(final Callable<T> call) throws Exception {
        return onEdt(call, 5_000L);
    }

    private static <T> T onEdt(final Callable<T> call, final long timeoutMillis) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(call.call());
            } catch (Exception exception) {
                failure.set(exception);
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Cubism EDT did not accept the probe within " + timeoutMillis + " ms.");
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static BufferedImage iconImage(final javax.swing.Icon icon) {
        final BufferedImage image =
                new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        try {
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
