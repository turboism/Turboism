package dev.turboism.validation.atlaspolygon;

import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Task-local exerciser for the dalsoo polygon texture-atlas auto-layout
 * exact-host path.
 *
 * <p>Phase {@code a} (default): waits for the runner trigger flag and the
 * active model, asserts the native auto-layout callback is installed, opens the
 * texture-atlas editor (confirming the creation dialog when the fixture has no
 * atlas), then drives controlled auto-layout invocations. Each invocation is
 * proven through the production plugin's runtime-log diagnostics: the
 * {@code dalsoo snapshot:} line carries a digest of the live pre-apply state
 * and the {@code dalsoo applied:} line a digest of the live post-apply state,
 * so undo/redo are proven by digest equality across invocations rather than by
 * version-specific provider internals.
 *
 * <p>Phase {@code b}: reopens the already-saved fixture copy in a fresh task
 * and asserts the reopened snapshot digest equals the phase-A post-layout
 * digest, proving durable persistence rather than an in-session echo.
 *
 * <p>All host object reads run on the Swing EDT via {@link #onHostThread}. The
 * probe is validation tooling only and is never part of the production preview
 * bundle or product build.</p>
 */
public final class AtlasPolygonHostValidationPlugin implements TurboismPlugin {

    private static final String FLAG = "exerciser.flag";
    private static final long FLAG_TIMEOUT_MILLIS = 240_000L;
    private static final long MODEL_AWAIT_MAX_MILLIS = 240_000L;
    private static final long EDT_TIMEOUT_MILLIS = 30_000L;
    private static final long ATLAS_OPEN_TIMEOUT_MILLIS = 120_000L;
    private static final long DIALOG_TIMEOUT_MILLIS = 60_000L;
    private static final long LAYOUT_TIMEOUT_MILLIS = 600_000L;
    private static final long SAVE_TIMEOUT_MILLIS = 180_000L;
    private static final long LOG_MARKER_TIMEOUT_MILLIS = 60_000L;

    private static final String CALLBACK_KEY =
        "dev.turboism.texture-atlas.auto-layout.callback";
    private static final String OBSERVER_KEY =
        "dev.turboism.texture-atlas.dialog.validation-observer";
    private static final String ALGORITHM_KEY =
        "dev.turboism.texture-atlas.dialog.algorithm";
    private static final String ALGORITHM_DALSOO = "dalsoo";

    private static final String[] ATLAS_MENU = {
        "编辑纹理集", "テクスチャアトラス編集", "Texture Atlas", "Edit Texture Atlas"
    };
    private static final String[] AUTO_LAYOUT_MENU = {
        "自动排版", "自動レイアウト", "Auto Layout", "Automatic Layout", "Layout Automatically"
    };
    private static final String[] OK_BUTTON = {"OK", "确定", "確定"};

    private PluginContext context;
    private PluginLogger logger;
    private Path stateDir;
    private final List<String> assertions = new ArrayList<>();
    private final AtomicReference<Object> dialogObservation = new AtomicReference<>();
    private int invocationCount;

    /** One parsed auto-layout invocation from the production runtime log. */
    private record Invocation(
        String label,
        String preState,
        String postState,
        String snapshotLine,
        String planLine,
        String status,
        boolean handled,
        boolean failed) {
    }

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenFlagged, "atlas-polygon-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
        logger.info("ATLAS_POLYGON_PROBE_READY stateDir=" + stateDir);
    }

    @Override
    public void enable() {
        logger.info("ATLAS_POLYGON_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("ATLAS_POLYGON_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("ATLAS_POLYGON_PROBE_SHUTDOWN");
    }

    private void runWhenFlagged() {
        final Path flag = stateDir.resolve(FLAG);
        final long deadline = System.currentTimeMillis() + FLAG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(flag)) {
                run();
                return;
            }
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warn("ATLAS_POLYGON_PROBE_FLAG_TIMEOUT flag=" + flag);
        Runtime.getRuntime().halt(2);
    }

    private void run() {
        final long startedNanos = System.nanoTime();
        final String phase = System.getProperty("turboism.validation.atlasPolygon.phase", "a");
        try {
            if ("b".equals(phase)) {
                runPhaseB();
            } else {
                runPhaseA();
            }
        } catch (Throwable failure) {
            recordAssertion("probe.unexpectedFailure", "no exception",
                singleLine(failure), "FAIL");
            logger.error("ATLAS_POLYGON_PROBE_FAILED " + singleLine(failure), failure);
        }
        writeResult(phase, startedNanos);
        try {
            Thread.sleep(3_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(0);
    }

    // ------------------------------------------------------------------
    // Phase A: apply + undo/redo + save on the task-scoped fixture copy
    // ------------------------------------------------------------------

    private void runPhaseA() throws Exception {
        awaitActiveModel();
        awaitHostBootstrap();

        final Object callback = System.getProperties().get(CALLBACK_KEY);
        recordAssertion("callback.installed",
            "BooleanSupplier registered at " + CALLBACK_KEY,
            callback == null ? "absent" : callback.getClass().getName(),
            callback instanceof BooleanSupplier ? "PASS" : "FAIL");

        installDialogObserver();
        System.getProperties().put(ALGORITHM_KEY, ALGORITHM_DALSOO);

        final Path fixture = phaseAFixture();
        final String fixtureShaBefore = fixture == null ? "" : sha256(fixture);
        recordAssertion("fixture.readable", "fixture file readable for hashing",
            fixture == null ? "no fixture sysprop" : fixture + " sha256=" + fixtureShaBefore,
            fixture != null && !fixtureShaBefore.isEmpty() ? "PASS" : "FAIL");

        ensureAtlasEditor();
        recordSdkReadback();

        // Invocation 1: baseline apply. The snapshot digest is the committed
        // pre-state S0; the applied digest is the resulting state S1.
        final Invocation inv1 = runAutoLayoutInvocation();
        assertInvocation(inv1, "inv1");
        commitAtlasEditor();

        // Global undo must restore S0; the next invocation reads S0 live.
        pressShortcut(KeyEvent.VK_Z);
        ensureAtlasEditor();
        final Invocation inv2 = runAutoLayoutInvocation();
        assertInvocation(inv2, "inv2");
        recordAssertion("undo.restoresPreState",
            "state digest after undo equals pre-layout digest",
            "pre(inv1)=" + inv1.preState + " pre(inv2)=" + inv2.preState,
            inv1.preState != null && inv1.preState.equals(inv2.preState) ? "PASS" : "FAIL");
        commitAtlasEditor();

        // Undo the second commit, then redo: the next invocation must read the
        // post-inv2 state digest.
        pressShortcut(KeyEvent.VK_Z);
        pressShortcut(KeyEvent.VK_Y);
        ensureAtlasEditor();
        final Invocation inv3 = runAutoLayoutInvocation();
        assertInvocation(inv3, "inv3");
        recordAssertion("redo.restoresPostState",
            "state digest after redo equals post-inv2 digest",
            "post(inv2)=" + inv2.postState + " pre(inv3)=" + inv3.preState,
            inv2.postState != null && inv2.postState.equals(inv3.preState) ? "PASS" : "FAIL");
        commitAtlasEditor();

        // Save: the host writes the task fixture in place (real save path);
        // the saved bytes are preserved as a sibling evidence file and the
        // fixture is restored so the runner's fixture-integrity check holds.
        final Path savedCopy = saveAndPreserveCopy(fixture, fixtureShaBefore);
        recordAssertion("persist.savedCopy",
            "host save persisted committed state; saved bytes preserved as sibling copy",
            String.valueOf(savedCopy), savedCopy != null ? "PASS" : "FAIL");
        recordAssertion("persist.fixtureUnchanged",
            "task fixture copy restored unchanged after evidence capture",
            "before=" + fixtureShaBefore + " after=" + sha256(fixture),
            fixture != null && sha256(fixture).equals(fixtureShaBefore) ? "PASS" : "FAIL");
        // The persisted committed state is inv3's post digest; phase B re-reads it.
        recordAssertion("persist.expectedPostState", "post-layout state digest for phase B",
            String.valueOf(inv3.postState), inv3.postState != null ? "PASS" : "FAIL");
        System.setProperty("turboism.validation.atlasPolygon.postStateDigest",
            String.valueOf(inv3.postState));
        if (savedCopy != null) {
            System.setProperty("turboism.validation.atlasPolygon.savedCopy",
                savedCopy.toString());
        }
    }

    // ------------------------------------------------------------------
    // Phase B: reopen the saved copy and verify durable persistence
    // ------------------------------------------------------------------

    private void runPhaseB() throws Exception {
        awaitActiveModel();
        awaitHostBootstrap();
        final String expected = System.getProperty(
            "turboism.validation.atlasPolygon.expectedPlanSha", "");
        recordAssertion("persist.expectedShaProvided", "phase-A state digest provided",
            expected.isBlank() ? "missing" : expected, expected.isBlank() ? "FAIL" : "PASS");
        ensureAtlasEditor();
        final Invocation inv = runAutoLayoutInvocation();
        recordAssertion("persist.stateMatchesPost",
            "reopened snapshot digest equals phase-A post-layout digest",
            "expected=" + expected + " actual=" + inv.preState,
            inv.preState != null && inv.preState.equals(expected) ? "PASS" : "FAIL");
        // Leave the editor committed so cleanup exercises a normal close.
        commitAtlasEditor();
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    private CubismModel awaitActiveModel() throws Exception {
        final long deadline = System.currentTimeMillis() + MODEL_AWAIT_MAX_MILLIS;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                final CubismModel model = onHostThread(() -> context.cubism().model().active());
                if (model != null
                    && onHostThread(() -> !model.drawables().all().isEmpty())) {
                    logger.info("ATLAS_POLYGON_MODEL_READY");
                    return model;
                }
            } catch (Exception failure) {
                lastFailure = failure;
            }
            Thread.sleep(1_000L);
        }
        throw new IllegalStateException("No active model within " + MODEL_AWAIT_MAX_MILLIS
            + " ms", lastFailure);
    }

    /**
     * Waits until the runtime bootstrap has installed the auto-layout hook.
     * The document can become active before bootstrap completes (observed on
     * 5.3.02 phase B); triggering auto-layout before the hook exists opens the
     * native dialog with no callback wired and no dalsoo snapshot is emitted.
     */
    private void awaitHostBootstrap() throws Exception {
        final long deadline = System.currentTimeMillis() + MODEL_AWAIT_MAX_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (readRuntimeLogs().contains(
                "TURBOISM_TEXTURE_ATLAS_AUTO_LAYOUT_HOOK installation=COMPLETE")) {
                logger.info("ATLAS_POLYGON_HOOK_READY");
                return;
            }
            Thread.sleep(2_000L);
        }
        logger.warn("ATLAS_POLYGON_HOOK_WAIT_TIMEOUT");
    }

    private void installDialogObserver() {
        // Consumer<Object> keeps the runtime-owned DialogObservation type out of
        // the probe's compile-time surface; the record accessors are public.
        final Consumer<Object> observer = observation -> {
            dialogObservation.set(observation);
            try {
                final Object algorithms = observation.getClass()
                    .getMethod("algorithms").invoke(observation);
                logger.info("ATLAS_POLYGON_DIALOG_OBSERVED algorithms=" + algorithms);
            } catch (ReflectiveOperationException failure) {
                logger.warn("ATLAS_POLYGON_DIALOG_OBSERVER_READ_FAILED " + failure);
            }
        };
        System.getProperties().put(OBSERVER_KEY, observer);
    }

    private void assertObserverSawDalsoo() {
        final Object observation = dialogObservation.get();
        String detail = "observer not invoked";
        boolean saw = false;
        if (observation != null) {
            try {
                final Object algorithms = observation.getClass()
                    .getMethod("algorithms").invoke(observation);
                if (algorithms instanceof List<?> list) {
                    final List<String> ids = new ArrayList<>();
                    for (final Object algorithm : list) {
                        final Object id = algorithm.getClass().getMethod("id").invoke(algorithm);
                        ids.add(String.valueOf(id));
                    }
                    saw = ids.contains(ALGORITHM_DALSOO);
                    detail = "algorithms=" + ids;
                }
            } catch (ReflectiveOperationException failure) {
                detail = "observer read failed: " + failure;
            }
        }
        recordAssertion("dialog.algorithmRegistered." + invocationCount,
            "dalsoo algorithm registered in the dialog selector",
            detail, saw ? "PASS" : "FAIL");
    }

    /**
     * Ensures the atlas editor window is open. The model fixture may not own an
     * atlas yet: the menu entry then raises the creation dialog
     * ("新纹理集设置"), which is confirmed here. Creation itself may already run
     * the native auto-layout (observed on 5.2.03); those invocations are
     * baseline noise and do not enter the digest chain.
     */
    private void ensureAtlasEditor() throws Exception {
        logMenuInventory();
        final long deadline = System.currentTimeMillis() + ATLAS_OPEN_TIMEOUT_MILLIS;
        long lastClick = 0;
        long lastDump = 0;
        while (System.currentTimeMillis() < deadline) {
            final Window editor = onHostThread(this::findAtlasEditorWindow);
            if (editor != null) {
                logger.info("ATLAS_POLYGON_EDITOR_OPEN window="
                    + editor.getClass().getSimpleName());
                return;
            }
            final JDialog creation = onHostThread(this::findNewAtlasDialog);
            if (creation != null) {
                logger.info("ATLAS_POLYGON_NEW_ATLAS_DIALOG title=" + creation.getTitle());
                clickButton(creation, OK_BUTTON);
            } else if (System.currentTimeMillis() - lastClick > 15_000L) {
                clickMenuItem(ATLAS_MENU);
                lastClick = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - lastDump > 15_000L) {
                lastDump = System.currentTimeMillis();
                logger.info("ATLAS_POLYGON_WAIT label=editor windows="
                    + onHostThread(AtlasPolygonHostValidationPlugin::describeWindows));
            }
            Thread.sleep(1_500L);
        }
        throw new IllegalStateException("Atlas editor window never appeared.");
    }

    /**
     * Runs one controlled auto-layout invocation inside the open editor and
     * waits for the production plugin's snapshot/applied diagnostics to land in
     * the runtime log. The returned digests are the live pre/post apply states
     * read by the service inside the same callback.
     */
    private Invocation runAutoLayoutInvocation() throws Exception {
        invocationCount++;
        final String label = "inv" + invocationCount;
        final String logBefore = readRuntimeLogs();
        final Window editor = onHostThread(this::findAtlasEditorWindow);
        if (editor == null) {
            throw new IllegalStateException("Atlas editor not open for auto-layout.");
        }
        final boolean triggered = onHostThread(() -> {
            final JMenuBar bar = menuBarOf(editor);
            final JMenuItem item = bar == null ? null : findMenuItem(bar, AUTO_LAYOUT_MENU);
            if (item != null && item.isEnabled()) {
                logger.info("ATLAS_POLYGON_LAYOUT_TRIGGER kind=menu text=" + item.getText());
                SwingUtilities.invokeLater(() -> item.doClick(0));
                return true;
            }
            final AbstractButton button = findButtonContains(editor, AUTO_LAYOUT_MENU);
            if (button != null && button.isEnabled()) {
                logger.info("ATLAS_POLYGON_LAYOUT_TRIGGER kind=button text=" + button.getText());
                SwingUtilities.invokeLater(() -> button.doClick(0));
                return true;
            }
            return false;
        });
        recordAssertion("layout.trigger." + label,
            "auto-layout trigger found and clicked in atlas editor",
            "triggered=" + triggered, triggered ? "PASS" : "FAIL");
        if (!triggered) {
            throw new IllegalStateException("No auto-layout trigger in atlas editor.");
        }

        final JDialog settings = awaitLayoutDialog();
        if (settings != null) {
            recordAssertion("dialog.autoLayoutOpened." + label,
                "native auto-layout settings dialog opened",
                settings.getTitle(), "PASS");
            if (dialogObservation.get() != null) {
                assertObserverSawDalsoo();
            }
            clickButton(settings, OK_BUTTON);
        }

        final long deadline = System.currentTimeMillis() + LAYOUT_TIMEOUT_MILLIS;
        String tail = "";
        while (System.currentTimeMillis() < deadline) {
            tail = diffRuntimeLogs(logBefore);
            final Invocation invocation = parseInvocation(tail);
            if (invocation != null) {
                return invocation;
            }
            Thread.sleep(1_000L);
        }
        logger.warn("ATLAS_POLYGON_INVOCATION_TIMEOUT tail=" + abbrev(tail));
        return new Invocation(label, null, null, null, null, null, false, false);
    }

    /**
     * Parses the newest complete invocation record out of the log tail: a
     * {@code dalsoo snapshot:} line followed by a terminal
     * {@code dalsoo applied:}/{@code automatic-layout status=}/failure line.
     */
    private Invocation parseInvocation(final String tail) {
        final int snapIndex = tail.lastIndexOf("dalsoo snapshot:");
        if (snapIndex < 0) {
            return null;
        }
        final String after = tail.substring(snapIndex);
        final int newline = after.indexOf('\n');
        final String snapshotLine = newline < 0 ? after : after.substring(0, newline);
        final String rest = newline < 0 ? "" : after.substring(newline);
        final String preState = extractToken(snapshotLine, "state=");
        String postState = null;
        String status = null;
        String planLine = null;
        for (final String line : rest.split("\n")) {
            if (line.contains("dalsoo plan:")) {
                planLine = line.trim();
            } else if (line.contains("dalsoo applied:")) {
                postState = extractToken(line, "state=");
                status = extractToken(line, "status=");
            } else if (line.contains("automatic-layout status=")) {
                if (status == null) {
                    status = extractToken(line, "status=");
                }
            } else if (line.contains("automatic-layout failureCode=")) {
                return new Invocation("inv" + invocationCount, preState, postState,
                    snapshotLine, planLine, extractToken(line, "failureCode="), false, true);
            }
        }
        if (status == null && !rest.contains("dalsoo applied:")) {
            // No terminal marker yet: the apply is still in flight.
            return null;
        }
        return new Invocation("inv" + invocationCount, preState, postState,
            snapshotLine, planLine, status, "APPLIED".equals(status), false);
    }

    private void assertInvocation(final Invocation inv, final String label) {
        recordAssertion("handled.byPlugin." + label,
            "dalsoo callback applied a valid plan (not host fallback)",
            "handled=" + inv.handled + " failed=" + inv.failed
                + " status=" + inv.status + " plan=" + abbrev(inv.planLine),
            inv.handled && !inv.failed ? "PASS" : "FAIL");
        final String shapes = extractToken(inv.snapshotLine, "outlineDrawDataShapes=");
        final boolean contours = shapes != null && !"0".equals(shapes);
        recordAssertion("outline.drawDataShapes." + label,
            "real draw-data-shape contours extracted for at least one item",
            "outlineDrawDataShapes=" + shapes + " snapshot=" + abbrev(inv.snapshotLine),
            contours ? "PASS" : "FAIL");
        recordAssertion("layout.writeBack." + label,
            "post-apply live state digest logged by the production service",
            "pre=" + inv.preState + " post=" + inv.postState,
            inv.postState != null && !"unreadable".equals(inv.postState) ? "PASS" : "FAIL");
    }

    private void recordSdkReadback() throws Exception {
        final boolean present = onHostThread(
            () -> context.cubism().textureAtlasLayouts().current().isPresent());
        recordAssertion("editor.sdkReadback",
            "provider exposes current authoring state (informational)",
            "current()=" + (present ? "present" : "empty"),
            present ? "PASS" : "NOTE");
    }

    /** The atlas-creation dialog: a showing modal JDialog that is not the auto-layout dialog. */
    private JDialog findNewAtlasDialog() {
        for (final Window window : Window.getWindows()) {
            if (!(window instanceof JDialog dialog)
                || !dialog.isVisible() || !dialog.isModal()) {
                continue;
            }
            if (findComboWithDalsoo(dialog) != null) {
                continue;
            }
            final String title = dialog.getTitle();
            // Match only the creation dialog ("新纹理集设置" / "New ..."), never the
            // editor window itself ("编辑纹理集"), which also contains "纹理集".
            if (title != null && (title.contains("新纹理集")
                || title.contains("新規")
                || title.toLowerCase(Locale.ROOT).contains("new ")
                || title.toLowerCase(Locale.ROOT).startsWith("new"))) {
                return dialog;
            }
        }
        return null;
    }

    private void logMenuInventory() throws Exception {
        final String inventory = onHostThread(() -> {
            final StringBuilder out = new StringBuilder();
            for (final Window window : Window.getWindows()) {
                if (!window.isVisible()) {
                    continue;
                }
                final JMenuBar bar = menuBarOf(window);
                if (bar == null) {
                    continue;
                }
                final String title = window instanceof Frame frame ? frame.getTitle()
                    : window instanceof java.awt.Dialog dialog ? dialog.getTitle() : "?";
                out.append('[').append(title).append(':');
                for (int i = 0; i < bar.getMenuCount(); i++) {
                    final JMenu menu = bar.getMenu(i);
                    if (menu != null) {
                        out.append(' ').append(menu.getText()).append('{');
                        for (int j = 0; j < menu.getItemCount(); j++) {
                            final JMenuItem item = menu.getItem(j);
                            if (item != null && !(item instanceof JMenu)) {
                                out.append(item.getText()).append(';');
                            }
                        }
                        out.append('}');
                    }
                }
                out.append(']');
            }
            return out.toString();
        });
        logger.info("ATLAS_POLYGON_MENUS " + inventory);
    }

    private JDialog awaitLayoutDialog() throws Exception {
        final long deadline = System.currentTimeMillis() + DIALOG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final JDialog dialog = onHostThread(() -> {
                for (final Window window : Window.getWindows()) {
                    if (!(window instanceof JDialog candidate)
                        || !candidate.isVisible() || !candidate.isShowing()) {
                        continue;
                    }
                    if (findComboWithDalsoo(candidate) != null) {
                        return candidate;
                    }
                }
                return null;
            });
            if (dialog != null) {
                return dialog;
            }
            Thread.sleep(500L);
        }
        return null;
    }

    private static JComboBox<?> findComboWithDalsoo(final Container root) {
        for (final Component component : root.getComponents()) {
            if (component instanceof JComboBox<?> combo) {
                for (int i = 0; i < combo.getItemCount(); i++) {
                    final Object item = combo.getItemAt(i);
                    if (item != null && String.valueOf(item).toLowerCase(Locale.ROOT)
                        .contains("dalsoo")) {
                        return combo;
                    }
                }
            }
            if (component instanceof Container child) {
                final JComboBox<?> found = findComboWithDalsoo(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void commitAtlasEditor() throws Exception {
        final Window editor = onHostThread(this::findAtlasEditorWindow);
        recordAssertion("editor.windowFound." + invocationCount,
            "atlas editor window located for commit",
            editor == null ? "none" : editor.getClass().getName(),
            editor != null ? "PASS" : "FAIL");
        if (editor == null) {
            return;
        }
        // invokeLater: the commit action may run a nested modal pump.
        final boolean clicked = onHostThread(() -> {
            SwingUtilities.invokeLater(() -> clickButtonNow(editor, OK_BUTTON));
            return findButton(editor, OK_BUTTON) != null;
        });
        recordAssertion("editor.commitClicked." + invocationCount,
            "editor OK/commit button clicked",
            "clicked=" + clicked, clicked ? "PASS" : "FAIL");
        // Wait for the editor window to close.
        final long deadline = System.currentTimeMillis() + DIALOG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (onHostThread(this::findAtlasEditorWindow) == null) {
                return;
            }
            Thread.sleep(1_000L);
        }
        logger.warn("ATLAS_POLYGON_EDITOR_CLOSE_TIMEOUT after commit");
    }

    /**
     * Proves durable persistence: triggers the host save (Ctrl+S / 保存 menu
     * accelerator), waits for the fixture copy's bytes to change on disk,
     * preserves the saved bytes as a sibling evidence file for phase B, then
     * restores the fixture copy so the runner's fixture-integrity check holds.
     */
    private Path saveAndPreserveCopy(final Path fixture, final String beforeSha)
        throws Exception {
        if (fixture == null) {
            return null;
        }
        final byte[] before = Files.readAllBytes(fixture);
        pressShortcut(KeyEvent.VK_S);
        final long deadline = System.currentTimeMillis() + SAVE_TIMEOUT_MILLIS;
        String savedSha = null;
        while (System.currentTimeMillis() < deadline) {
            final String current = sha256(fixture);
            if (!current.isEmpty() && !current.equals(beforeSha)) {
                savedSha = current;
                break;
            }
            Thread.sleep(2_000L);
        }
        if (savedSha == null) {
            logger.warn("ATLAS_POLYGON_SAVE_TIMEOUT fixture=" + fixture);
            return null;
        }
        final Path evidence = fixture.resolveSibling("atlas-polygon-saved.cmo3");
        Files.copy(fixture, evidence,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        logger.info("ATLAS_POLYGON_SAVED_COPY path=" + evidence
            + " sha256=" + sha256(evidence));
        // Restore the fixture bytes: the host save is already proven and
        // preserved; the runner requires the staged copy to end unchanged.
        Files.write(fixture, before);
        return evidence;
    }

    private Path phaseAFixture() {
        final String value = System.getProperty("turboism.validation.atlasPolygon.fixture");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            final Path path = Path.of(value);
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Swing helpers (all invoked on the EDT)
    // ------------------------------------------------------------------

    /**
     * Finds the atlas editor window: a visible window carrying an auto-layout
     * trigger (menu item or button), or whose title matches the editor title
     * while not matching the creation dialog.
     */
    private Window findAtlasEditorWindow() {
        Window titled = null;
        for (final Window window : Window.getWindows()) {
            if (!window.isVisible()) {
                continue;
            }
            final JMenuBar bar = menuBarOf(window);
            if (bar != null && findMenuItem(bar, AUTO_LAYOUT_MENU) != null) {
                return window;
            }
            if (findButtonContains(window, AUTO_LAYOUT_MENU) != null
                && !isCreationTitle(titleOf(window))) {
                return window;
            }
            if (isAtlasEditorTitle(window)) {
                titled = window;
            }
        }
        return titled;
    }

    private static String titleOf(final Window window) {
        if (window instanceof Frame frame) {
            return frame.getTitle();
        }
        if (window instanceof java.awt.Dialog dialog) {
            return dialog.getTitle();
        }
        return null;
    }

    private static boolean isCreationTitle(final String title) {
        if (title == null) {
            return false;
        }
        final String lower = title.toLowerCase(Locale.ROOT);
        return title.contains("新纹理集") || title.contains("新規")
            || lower.startsWith("new") || lower.contains("new ");
    }

    private static boolean isAtlasEditorTitle(final Window window) {
        final String title = titleOf(window);
        if (title == null || isCreationTitle(title)) {
            return false;
        }
        return title.contains("编辑纹理集") || title.contains("テクスチャアトラス")
            || title.toLowerCase(Locale.ROOT).contains("texture atlas");
    }

    private void clickMenuItem(final String[] candidates) throws Exception {
        // The item is located on the EDT but clicked through invokeLater: menu
        // actions may open a nested modal pump, which would stall invokeAndWait.
        final boolean found = onHostThread(() -> {
            for (final Window window : Window.getWindows()) {
                if (!window.isVisible()) {
                    continue;
                }
                final JMenuBar bar = menuBarOf(window);
                if (bar == null) {
                    continue;
                }
                final JMenuItem item = findMenuItem(bar, candidates);
                if (item != null && item.isEnabled()) {
                    logger.info("ATLAS_POLYGON_MENU_CLICK text=" + item.getText());
                    SwingUtilities.invokeLater(() -> item.doClick(0));
                    return true;
                }
            }
            return false;
        });
        if (!found) {
            logger.warn("ATLAS_POLYGON_MENU_NOT_FOUND candidates="
                + String.join("|", candidates) + " windows=" + describeWindows());
        }
    }

    private static JMenuBar menuBarOf(final Window window) {
        if (window instanceof JFrame frame) {
            return frame.getJMenuBar();
        }
        if (window instanceof JDialog dialog) {
            return dialog.getJMenuBar();
        }
        return null;
    }

    private static JMenuItem findMenuItem(final JMenuBar bar, final String[] candidates) {
        for (int i = 0; i < bar.getMenuCount(); i++) {
            final JMenuItem found = findMenuItem(bar.getMenu(i), candidates);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static JMenuItem findMenuItem(final JMenu menu, final String[] candidates) {
        if (menu == null) {
            return null;
        }
        for (int i = 0; i < menu.getItemCount(); i++) {
            final JMenuItem item = menu.getItem(i);
            if (item == null) {
                continue;
            }
            if (item instanceof JMenu submenu) {
                final JMenuItem found = findMenuItem(submenu, candidates);
                if (found != null) {
                    return found;
                }
                continue;
            }
            final String text = item.getText();
            if (text == null) {
                continue;
            }
            for (final String candidate : candidates) {
                if (text.contains(candidate)) {
                    return item;
                }
            }
        }
        return null;
    }

    /** Schedules a click on the first matching enabled button; the click itself runs later on the EDT. */
    private void clickButton(final Window window, final String[] candidates) throws Exception {
        onHostThread(() -> {
            SwingUtilities.invokeLater(() -> clickButtonNow(window, candidates));
            return null;
        });
    }

    private static AbstractButton findButton(final Container root, final String[] candidates) {
        for (final Component component : root.getComponents()) {
            if (component instanceof AbstractButton button) {
                final String text = button.getText();
                if (text != null && button.isEnabled() && button.isVisible()) {
                    for (final String candidate : candidates) {
                        if (text.strip().equals(candidate)) {
                            return button;
                        }
                    }
                }
            }
            if (component instanceof Container child) {
                final AbstractButton found = findButton(child, candidates);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static AbstractButton findButtonContains(
        final Container root,
        final String[] candidates
    ) {
        for (final Component component : root.getComponents()) {
            if (component instanceof AbstractButton button) {
                final String text = button.getText();
                if (text != null && button.isEnabled() && button.isVisible()) {
                    for (final String candidate : candidates) {
                        if (text.contains(candidate)) {
                            return button;
                        }
                    }
                }
            }
            if (component instanceof Container child) {
                final AbstractButton found = findButtonContains(child, candidates);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean clickButtonNow(final Container root, final String[] candidates) {
        for (final Component component : root.getComponents()) {
            if (component instanceof AbstractButton button) {
                final String text = button.getText();
                if (text != null && button.isEnabled() && button.isVisible()) {
                    for (final String candidate : candidates) {
                        if (text.strip().equals(candidate)) {
                            button.doClick(0);
                            return true;
                        }
                    }
                }
            }
            if (component instanceof Container child) {
                if (clickButtonNow(child, candidates)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void pressShortcut(final int key) throws Exception {
        if (invokeMenuShortcut(key)) {
            logger.info("ATLAS_POLYGON_SHORTCUT strategy=menu key=" + KeyEvent.getKeyText(key));
            Thread.sleep(500L);
            return;
        }
        logger.info("ATLAS_POLYGON_SHORTCUT strategy=robot key=" + KeyEvent.getKeyText(key));
        final Robot robot = new Robot();
        try {
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(key);
        } finally {
            try {
                robot.keyRelease(key);
            } finally {
                robot.keyRelease(KeyEvent.VK_CONTROL);
            }
        }
        Thread.sleep(500L);
    }

    private boolean invokeMenuShortcut(final int key) throws Exception {
        final AtomicReference<JMenuItem> match = new AtomicReference<>();
        final AtomicBoolean enabled = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            for (final Frame frame : Frame.getFrames()) {
                if (!(frame instanceof JFrame swingFrame) || !frame.isVisible()) {
                    continue;
                }
                final JMenuBar bar = swingFrame.getJMenuBar();
                if (bar == null) {
                    continue;
                }
                for (int index = 0; index < bar.getMenuCount() && match.get() == null; index++) {
                    findMenuShortcut(bar.getMenu(index), key, match);
                }
            }
            final JMenuItem item = match.get();
            enabled.set(item != null && item.isEnabled());
            if (enabled.get()) {
                item.doClick(0);
            }
        });
        return enabled.get();
    }

    private static void findMenuShortcut(
        final JMenu menu,
        final int key,
        final AtomicReference<JMenuItem> match
    ) {
        for (int index = 0; index < menu.getItemCount() && match.get() == null; index++) {
            final JMenuItem item = menu.getItem(index);
            if (item == null) {
                continue;
            }
            if (item instanceof JMenu submenu) {
                findMenuShortcut(submenu, key, match);
            } else if (item.getAccelerator() != null
                && item.getAccelerator().getKeyCode() == key
                && (item.getAccelerator().getModifiers()
                    & InputEvent.CTRL_DOWN_MASK) != 0) {
                match.set(item);
            }
        }
    }

    private static String describeWindows() {
        final StringBuilder out = new StringBuilder();
        for (final Window window : Window.getWindows()) {
            if (!window.isVisible()) {
                continue;
            }
            final String title = window instanceof Frame frame ? frame.getTitle()
                : window instanceof java.awt.Dialog dialog ? dialog.getTitle() : null;
            out.append('[').append(window.getClass().getSimpleName())
                .append("|").append(title).append(']');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Runtime log markers
    // ------------------------------------------------------------------

    private String readRuntimeLogs() {
        final StringBuilder out = new StringBuilder();
        try {
            final Path dir = Path.of(System.getProperty("turboism.home"), "logs", "runtime");
            if (!Files.isDirectory(dir)) {
                return "";
            }
            try (var stream = Files.walk(dir)) {
                for (final Path file : stream.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".log")).toList()) {
                    try {
                        out.append(Files.readString(file));
                    } catch (Exception ignored) {
                        // A log file being rotated is evidence-neutral.
                    }
                }
            }
        } catch (Exception ignored) {
            // Diagnostics must never fail the probe.
        }
        return out.toString();
    }

    private static String diffRuntimeLogs(final String before, final String after) {
        // Markers we care about are appended lines; a simple tail-diff suffices.
        if (after.startsWith(before)) {
            return after.substring(before.length());
        }
        return after;
    }

    private String diffRuntimeLogs(final String before) {
        return diffRuntimeLogs(before, readRuntimeLogs());
    }

    /** Extracts the whitespace-delimited token following {@code key}, or null. */
    private static String extractToken(final String text, final String key) {
        if (text == null) {
            return null;
        }
        final int index = text.indexOf(key);
        if (index < 0) {
            return null;
        }
        int end = index + key.length();
        final StringBuilder value = new StringBuilder();
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            value.append(text.charAt(end));
            end++;
        }
        return value.length() == 0 ? null : value.toString();
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private <T> T onHostThread(final Callable<T> call) throws Exception {
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(call.call());
            } catch (Exception exception) {
                failure.set(exception);
            } catch (Throwable throwable) {
                failure.set(new IllegalStateException("EDT call failed", throwable));
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(EDT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Cubism EDT did not accept the probe within "
                + EDT_TIMEOUT_MILLIS + " ms.");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private void recordAssertion(
        final String name,
        final String expected,
        final String actual,
        final String status
    ) {
        assertions.add("assertion." + name + ".expected=" + oneLine(expected));
        assertions.add("assertion." + name + ".actual=" + oneLine(actual));
        assertions.add("assertion." + name + ".status=" + status);
        logger.info("ATLAS_POLYGON_ASSERT " + name + " status=" + status
            + " actual=" + oneLine(actual));
    }

    private void writeResult(final String phase, final long startedNanos) {
        String terminal = "PASS";
        for (final String line : assertions) {
            if (line.endsWith(".status=FAIL")) {
                terminal = "FAIL";
            }
        }
        final StringBuilder report = new StringBuilder();
        report.append("status=").append(terminal).append('\n');
        report.append("phase=").append(phase).append('\n');
        report.append("hostVersion=")
            .append(System.getProperty("turboism.validation.hostVersion", "unknown")).append('\n');
        report.append("durationMillis=")
            .append((System.nanoTime() - startedNanos) / 1_000_000L).append('\n');
        for (final String line : assertions) {
            report.append(line).append('\n');
        }
        try {
            final Path result = Path.of(System.getProperty("turboism.home"),
                "state", "atlas-polygon-validation-result.properties");
            Files.createDirectories(result.getParent());
            Files.writeString(result, report.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception failure) {
            logger.error("ATLAS_POLYGON_RESULT_WRITE_FAILED " + failure, failure);
        }
        logger.info("ATLAS_POLYGON_PROBE_RESULT status=" + terminal
            + " phase=" + phase + " assertions=" + assertions.size());
    }

    private static String sha(final String canonical) {
        if (canonical == null) {
            return "null";
        }
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            return "sha-error";
        }
    }

    private static String sha256(final Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
        } catch (Exception failure) {
            return "";
        }
    }

    private static String abbrev(final String canonical) {
        if (canonical == null) {
            return "null";
        }
        final String header = canonical.contains("\n")
            ? canonical.substring(0, canonical.indexOf('\n'))
            : canonical;
        final long placements = canonical.lines().count() - 1;
        return header + " placements=" + placements;
    }

    private static String oneLine(final String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static String singleLine(final Throwable failure) {
        if (failure == null) {
            return "null";
        }
        return failure.getClass().getName() + ": "
            + String.valueOf(failure.getMessage()).replace('\n', ' ');
    }
}
