package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.storage.StorageWriteResult;

import javax.imageio.ImageIO;

import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

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
    private static final long ROW_DOWN_SETTLE_MILLIS = 400L;
    private static final long ROW_SETTLE_MILLIS = 1_500L;
    private static final int SAVE_ROWS = 8;

    private static final Pattern WINDOWS_DRIVE_PREFIX = Pattern.compile("(?m)^[A-Za-z]:[/\\\\]");
    private static final Pattern WINDOWS_UNC_PREFIX = Pattern.compile("(?m)^\\\\{2}");
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final String PROGRESS_DIALOG_CLASS_PREFIX = "noids.framework.e.a";
    private static final Set<String> FILE_LABELS = Set.of("file", "ファイル", "文件", "파일");
    private static final Set<String> RECENT_LABELS = Set.of(
        "recent", "recent files", "open recent",
        "最近使用したファイル", "最近的文件", "最近使用的文件", "최근 파일"
    );

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
    private volatile String runId = "unknown";
    private volatile int recentCount;
    private volatile boolean idOpaque;
    private volatile boolean pathAbsolute;
    private volatile boolean pathEndsWithFixture;
    private volatile int directCaptureWidth;
    private volatile int directCaptureHeight;
    private volatile String directPngSha256 = "";
    private volatile int directPngColors;
    private volatile boolean savedEventMatched;
    private volatile boolean productionCachePng;
    private volatile boolean productionIndex;
    private volatile boolean popupThumbnail;
    private final AtomicReference<RecentFileId> recentId = new AtomicReference<>();
    private final AtomicReference<String> savedContentName = new AtomicReference<>();
    private volatile String fixturePath;

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
        if (model != null && matchesFixtureName(model.name())) {
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
            runId = System.getProperty("turboism.validation.runId", "unknown");
            hostCloseRoute(hostVersion);
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

    // --- phase (a): recent list ------------------------------------------------

    private void validateRecentList() throws Exception {
        phase = "recent-list";
        final RecentFileSummary fixture = awaitRecentListFixture();
        recentCount = context.recentFiles().list().size();
        final String idValue = fixture.id().value();
        idOpaque = isOpaqueHexId(idValue);
        if (!idOpaque) {
            throw new IllegalStateException("recent file id is not opaque 64-lowercase-hex");
        }
        final String path = fixture.path().orElseThrow(
            () -> new IllegalStateException("recent file summary carries no path")
        );
        pathAbsolute = isAbsolutePath(path);
        pathEndsWithFixture = endsWithSeparator(path, fixtureName);
        if (!pathAbsolute || !pathEndsWithFixture) {
            throw new IllegalStateException(
                "recent file path is not absolute or does not end with the fixture name"
            );
        }
        recentId.set(fixture.id());
        fixturePath = path;
        context.logger().info("Recent preview recent-list displayName=" + fixture.displayName()
            + " idOpaque=" + idOpaque + " pathAbsolute=" + pathAbsolute
            + " pathEndsWithFixture=" + pathEndsWithFixture + " recentCount=" + recentCount);
    }

    private RecentFileSummary awaitRecentListFixture() throws Exception {
        final long deadline = System.nanoTime() + RECENT_LIST_DEADLINE_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            final List<RecentFileSummary> files = context.recentFiles().list();
            for (RecentFileSummary file : files) {
                if (fixtureName.equals(file.displayName())) {
                    return file;
                }
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new IllegalStateException("recent-file list did not expose the fixture entry");
    }

    // --- phase (b): direct capture ---------------------------------------------

    private void validateDirectCapture(final RecentFileId id) throws Exception {
        phase = "direct-capture";
        final ScreenshotCaptureResult capture;
        try {
            capture = await(
                context.screenshots().capture(new ScreenshotCaptureRequest(id, TARGET_SIZE, TARGET_SIZE)),
                CAPTURE_DEADLINE_MILLIS, TimeUnit.MILLISECONDS
            );
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
        if (directCaptureWidth < 1 || directCaptureWidth > TARGET_SIZE
            || directCaptureHeight < 1 || directCaptureHeight > TARGET_SIZE) {
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
        context.logger().info("Recent preview direct capture width=" + directCaptureWidth
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
            context.logger().error("Recent preview direct capture FAILED exceptionClass="
                + failure.getClass().getName() + " windows=" + windows);
        } catch (Exception diagnosticFailure) {
            context.logger().error("Recent preview direct capture FAILED exceptionClass="
                + failure.getClass().getName()
                + " windowEnumerationFailed=" + diagnosticFailure.getClass().getName());
        }
    }

    // --- phase (c): typed save event via the host File menu --------------------

    private void validateSavedEvent(final RecentFileId id) throws Exception {
        phase = "save";
        if (fixturePath == null) {
            throw new IllegalStateException("recent file path was not captured before the save phase");
        }
        final Path fixture = Path.of(fixturePath);
        final long baselineModifiedMillis = Files.getLastModifiedTime(fixture).toMillis();
        final long baselineSize = Files.size(fixture);
        final Window target = onEdt(() -> selectHostWindow(Window.getWindows()));
        onEdt(() -> {
            target.toFront();
            target.requestFocus();
            return null;
        });
        recoverSaveFocus(target);
        final String savePath = triggerMenuSave(fixture, baselineModifiedMillis, baselineSize);
        final long deadline = System.nanoTime() + SAVED_EVENT_DEADLINE_MILLIS * 1_000_000L;
        boolean fileModified = false;
        while (System.nanoTime() < deadline) {
            if (savedContentName.get() != null) {
                savedEventMatched = true;
                break;
            }
            if (!fileModified && fileModifiedSince(fixture, baselineModifiedMillis, baselineSize)) {
                fileModified = true;
            }
            Thread.sleep(POLL_MILLIS);
        }
        context.logger().info(saveDiagnostic(savedEventMatched, fileModified, savePath));
        final String failurePhase = saveFailurePhase(savedEventMatched, fileModified);
        if (failurePhase != null) {
            phase = failurePhase;
            throw new IllegalStateException("save-event".equals(failurePhase)
                ? "fixture file was saved but ModelFileHooks.onModelSaved did not fire"
                : "typed ModelFileHooks.onModelSaved was not observed and the fixture file was not modified");
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
        context.logger().info("Recent preview save focus active=" + outcome[0]
            + " progressDialogVisible=" + outcome[1]);
    }

    private boolean[] saveFocusSnapshot(final Window target) {
        boolean progressVisible = false;
        for (final Window window : Window.getWindows()) {
            if (window.isShowing()
                && window.getClass().getName().contains(PROGRESS_DIALOG_CLASS_PREFIX)) {
                progressVisible = true;
                break;
            }
        }
        return new boolean[] {target.isActive(), progressVisible};
    }

    /**
     * Triggers a save through the host File menu (the real command_save path),
     * not the Ctrl+S accelerator, using pure keyboard navigation (no
     * coordinates): Alt activates the menu bar, Enter opens the first menu,
     * then rows 1..SAVE_ROWS are walked with Down+Enter until the saved event
     * or a fixture modification is observed. A newly appeared dialog (e.g. the
     * "Save As..." chooser) is dismissed with Escape before continuing. When no
     * popup opens after MENU_OPEN_ATTEMPTS tries, falls back to the legacy
     * Ctrl+S path once. Returns the path used via saveMenuPath ("menu",
     * "ctrls", or "none"). Logs menuOpened/attempts and per-row outcomes;
     * never logs paths.
     */
    private String triggerMenuSave(final Path fixture,
        final long baselineModifiedMillis, final long baselineSize) throws Exception {
        final Robot robot = new Robot();
        boolean menuOpened = false;
        int attempts = 0;
        for (int attempt = 1; attempt <= MENU_OPEN_ATTEMPTS; attempt++) {
            attempts++;
            pressKey(robot, KeyEvent.VK_ALT);
            robot.delay((int) MENU_OPEN_SETTLE_MILLIS);
            pressKey(robot, KeyEvent.VK_ENTER);
            final long menuDeadline = System.nanoTime() + MENU_OPEN_POLL_MILLIS * 1_000_000L;
            while (System.nanoTime() < menuDeadline) {
                menuOpened = onEdt(this::findPopupWindow) != null;
                if (menuOpened) break;
                robot.waitForIdle();
                Thread.sleep(100L);
            }
            if (menuOpened || attempt == MENU_OPEN_ATTEMPTS) break;
            robot.delay((int) MENU_OPEN_RETRY_GAP_MILLIS);
        }
        context.logger().info("Recent preview save menuOpened=" + menuOpened + " attempts=" + attempts);
        if (!menuOpened) {
            pressCtrlSBackground();
            return saveMenuPath(false, true);
        }
        final AtomicReference<Set<String>> dialogsBefore = new AtomicReference<>(
            onEdt(() -> visibleDialogKeys(Window.getWindows())));
        for (int row = 1; row <= SAVE_ROWS; row++) {
            pressKey(robot, KeyEvent.VK_DOWN);
            robot.delay((int) ROW_DOWN_SETTLE_MILLIS);
            pressKey(robot, KeyEvent.VK_ENTER);
            final long rowDeadline = System.nanoTime() + ROW_SETTLE_MILLIS * 1_000_000L;
            boolean saved = false;
            boolean modified = false;
            while (System.nanoTime() < rowDeadline) {
                saved = savedContentName.get() != null;
                modified = fileModifiedSince(fixture, baselineModifiedMillis, baselineSize);
                if (saved || modified) break;
                Thread.sleep(100L);
            }
            final boolean newDialog = onEdt(() -> hasNewVisibleDialog(Window.getWindows(), dialogsBefore.get()));
            context.logger().info("Recent preview menu save row=" + row
                + " savedEvent=" + saved + " fileModified=" + modified);
            if (saved || modified) break;
            if (shouldCloseDialog(saved, modified, newDialog)) {
                pressEscape(robot);
                robot.waitForIdle();
                dialogsBefore.set(onEdt(() -> visibleDialogKeys(Window.getWindows())));
            }
        }
        return saveMenuPath(true, false);
    }

    /** First showing popup window (Swing menu popups are "Popup" windows). */
    private Window findPopupWindow() {
        for (final Window window : Window.getWindows()) {
            if (isPopupWindow(window.isShowing(), window.getClass().getName())) return window;
        }
        return null;
    }


    /** Popup windows are Swing popup containers whose class name contains "Popup". */
    static boolean isPopupWindow(final boolean showing, final String className) {
        return showing && className != null && className.contains("Popup");
    }

    // --- phase (d): production plugin cache -------------------------------------

    private void validateProductionCache(final RecentFileId id) throws Exception {
        phase = "production-cache";
        final String key = sha256Hex(id.value().getBytes(StandardCharsets.UTF_8));
        final Path cacheRoot = Path.of(System.getProperty("turboism.home"))
            .resolve("cache").resolve(PRODUCTION_PLUGIN_ID).resolve("recent-preview");
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
        productionCachePng = true;
        productionIndex = true;
        context.logger().info("Recent preview production cache png=" + productionCachePng
            + " index=" + productionIndex + " key=" + key);
    }

    // --- phase (e): Robot-driven File->Recent hover popup ------------------------

    private void validatePopup(final RecentFileId id) throws Exception {
        phase = "popup";
        final Robot robot = new Robot();
        final Window host = onEdt(() -> selectHostWindow(Window.getWindows()));
        onEdt(() -> {
            host.toFront();
            host.requestFocus();
            return null;
        });
        robot.waitForIdle();
        Thread.sleep(600L);

        openFileMenu(robot, host);
        final Point recent = awaitMenuPoint(robot, this::recentMenuPoint, SUBMENU_DEADLINE_MILLIS);
        moveTo(robot, recent);
        final Point item = awaitMenuPoint(robot, this::fixtureItemPoint, ITEM_DEADLINE_MILLIS);
        moveTo(robot, item);

        final long deadline = System.nanoTime() + POPUP_DEADLINE_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(onEdt(WindowsRecentPreviewValidationProbe::popupThumbnailVisible))) {
                popupThumbnail = true;
                break;
            }
            Thread.sleep(POLL_MILLIS);
        }
        if (!popupThumbnail) {
            throw new IllegalStateException("themed hover popup with image content was not observed");
        }
        pressEscape(robot);
        context.logger().info("Recent preview popup thumbnail=" + popupThumbnail);
    }

    private static final long SUBMENU_DEADLINE_MILLIS = 30_000L;
    private static final long ITEM_DEADLINE_MILLIS = 30_000L;

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

    private Point fixtureItemPoint() {
        for (Window window : Window.getWindows()) {
            final JMenuItem item = findMenuItemFor(window, fixtureName);
            if (item != null && item.isShowing()) {
                return centerOf(item);
            }
        }
        return null;
    }

    private static JMenuItem findMenuItemFor(final Component root, final String fixtureName) {
        if (root instanceof JMenuItem item && item.isShowing()) {
            final String text = Objects.toString(item.getText(), "");
            final String command = Objects.toString(item.getActionCommand(), "");
            if (matchesFixtureText(text, fixtureName) || matchesFixtureText(command, fixtureName)) {
                return item;
            }
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JMenuItem found = findMenuItemFor(child, fixtureName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean matchesFixtureText(final String value, final String fixtureName) {
        if (value.isEmpty()) return false;
        final String normalized = value.trim();
        if (normalized.equals(fixtureName)) return true;
        return normalized.endsWith("/" + fixtureName) || normalized.endsWith("\\" + fixtureName);
    }

    private static JMenu findMenuByLabel(final Component root, final Set<String> labels) {
        if (root instanceof JMenu menu && labels.contains(normalizeLabel(menu.getText()))) {
            return menu;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JMenu found = findMenuByLabel(child, labels);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean popupThumbnailVisible() {
        for (Window window : Window.getWindows()) {
            if (findPopupImage(window)) return true;
        }
        return false;
    }

    private static boolean findPopupImage(final Component root) {
        if (root instanceof JLabel label
            && POPUP_IMAGE_LABEL.equals(label.getName())
            && label.getIcon() != null
            && validThumbnailIcon(label.getIcon())
            && distinctSampledColors(iconImage(label.getIcon())) >= 2) {
            return true;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (findPopupImage(child)) return true;
            }
        }
        return false;
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
                SwingUtilities.invokeLater(() -> target.dispatchEvent(
                    new WindowEvent(target, WindowEvent.WINDOW_CLOSING)));
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
            context.logger().error("Recent preview automated host close failed: "
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
            fields.put("recentCount", String.valueOf(recentCount));
            fields.put("idOpaque", String.valueOf(idOpaque));
            fields.put("pathAbsolute", String.valueOf(pathAbsolute));
            fields.put("pathEndsWithFixture", String.valueOf(pathEndsWithFixture));
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
            context.logger().error("RECENT_PREVIEW_HOST_RESULT status=FAIL"
                + " failureClass=" + writeFailure.getClass().getName()
                + " phase=" + phase);
        }
    }

    private void publishFailure(final Throwable failure) {
        try {
            writeUtf8(new StoragePath(StorageRoot.STATE, "result.properties"),
                failureResult(failure.getClass().getName(), phase));
            resultPublished = true;
        } catch (Exception writeFailure) {
            context.logger().error("RECENT_PREVIEW_HOST_RESULT status=FAIL"
                + " failureClass=" + writeFailure.getClass().getName()
                + " phase=" + phase);
        }
        context.logger().error("RECENT_PREVIEW_HOST_RESULT status=FAIL"
            + " failureClass=" + failure.getClass().getName()
            + " failurePhase=" + phase);
    }

    // --- pure helpers (unit-tested) ---------------------------------------------------

    static HostCloseRoute hostCloseRoute(final String hostVersion) {
        if (hostVersion == null) {
            throw new IllegalArgumentException("turboism.validation.hostVersion must be 5203 or 5302");
        }
        return switch (hostVersion) {
            case "5203" -> HostCloseRoute.SYNTHETIC_WINDOW_CLOSING;
            case "5302" -> HostCloseRoute.ROBOT_ALT_F4;
            default -> throw new IllegalArgumentException(
                "turboism.validation.hostVersion must be 5203 or 5302");
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
        return WINDOWS_DRIVE_PREFIX.matcher(value).find() || WINDOWS_UNC_PREFIX.matcher(value).find();
    }

    static boolean endsWithSeparator(final String value, final String suffix) {
        if (value == null || suffix == null || suffix.isBlank()) return false;
        return value.endsWith(suffix)
            || value.endsWith("/" + suffix)
            || value.endsWith("\\" + suffix);
    }

    static boolean matchesFixtureName(final String modelName) {
        final String fixture = "fixture.cmo3";
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
                && decoded.getWidth() >= 1 && decoded.getWidth() <= maxDimension
                && decoded.getHeight() >= 1 && decoded.getHeight() <= maxDimension;
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
            && icon.getIconWidth() >= 1 && icon.getIconWidth() <= TARGET_SIZE
            && icon.getIconHeight() >= 1 && icon.getIconHeight() <= TARGET_SIZE;
    }

    static String resultContent(final Map<String, String> fields) {
        final StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            final String key = entry.getKey();
            final String value = entry.getValue();
            if (key == null || key.isBlank() || key.indexOf('=') >= 0
                || value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
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
    static boolean shouldCloseDialog(final boolean savedEvent, final boolean fileModified,
        final boolean newDialogVisible) {
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
            if (window instanceof java.awt.Dialog && window.isShowing()
                && !baseline.contains(dialogIdentity(window.getClass().getName(), System.identityHashCode(window)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the file was modified after the recorded baseline (lastModified
     * newer or size changed); false when unchanged or unreadable (deleted).
     */
    static boolean fileModifiedSince(final Path path, final long baselineModifiedMillis,
        final long baselineSize) {
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
        final Thread robotThread = new Thread(() -> {
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
        }, "turboism-recent-preview-robot");
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
        return new Point(
            location.x + component.getWidth() / 2,
            location.y + component.getHeight() / 2
        );
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
        return java.util.Objects.toString(value, "").trim().toLowerCase(Locale.ROOT)
            .replace("…", "").replace("...", "");
    }

    static Window selectHostWindow(final Window[] windows) {
        Window target = null;
        long largestArea = -1L;
        for (final Window window : windows) {
            if (window instanceof java.awt.Dialog
                || !window.isDisplayable()
                || !window.isVisible()) {
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
        final StorageWriteResult written = await(
            context.storage().writeBytesAtomic(path, content), 30, TimeUnit.SECONDS);
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
        if (!completed.await(5L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Cubism EDT did not accept the probe within 5 seconds.");
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static BufferedImage iconImage(final javax.swing.Icon icon) {
        final BufferedImage image = new BufferedImage(
            icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        try {
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
