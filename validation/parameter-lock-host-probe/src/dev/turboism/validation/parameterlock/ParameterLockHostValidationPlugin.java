package dev.turboism.validation.parameterlock;

import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.appearance.PaletteRowAction;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task-local host probe for dev.turboism.plugin.parameter-lock validation.
 *
 * <p>Validates the production parameter-lock plugin running on an exact Cubism host:</p>
 * <ul>
 *   <li>Model readiness gating on non-empty drawables and parameters (awaits real fixture);</li>
 *   <li>Parameter palette visibility assurance (activates SHOW_PARAMETER_PALETTE only when needed);</li>
 *   <li>Row-level leading action verification: checks row client property MARKER
 *       ({@code turboism.paletteRow.leadingAction}) and native 16x16 icon presence;</li>
 *   <li>Real MouseEvent dispatch targeting the leading 16px icon slot on the native JLabel
 *       (exercises exact host hit-testing without directly invoking plugin callbacks);</li>
 *   <li>Dynamic live row and JLabel re-resolution immediately before every interaction and marker read
 *       (accommodates host row widget destruction/recreation across native writes and selections);</li>
 *   <li>Strict source identity verification against the active model (raw CParameterSource exact match);</li>
 *   <li>Dual-layer write verification: SDK parameter.setValue and native controller submission
 *       across unlocked accepted (positive control), locked rejected, and unlocked restored;</li>
 *   <li>Parameter group recursion and MIXED state verification with cycle prevention,
 *       asserting write rejection and write restoration across ALL descendant IDs;</li>
 *   <li>Cross-session persistence (persist-write and persist-read modes) driven entirely via row UI;</li>
 *   <li>Explicit boundary reporting (NOT_TESTED when conditions cannot be exercised on fixture).</li>
 * </ul>
 */
public final class ParameterLockHostValidationPlugin implements TurboismPlugin {

    private static final String FLAG = "exerciser.flag";
    private static final String RESULT_FILE = "parameter-lock-host-result.properties";
    private static final String SNAPSHOT_FILE = "parameter-lock-snapshot.json";
    private static final String UI_SNAPSHOT_PNG = "parameter-lock-ui-snapshot.png";
    private static final String MODE_PROPERTY = "turboism.parameterLockValidation.mode";
    private static final String DEFAULT_MODE = "matrix";

    private static final String PRODUCTION_PLUGIN_ID = "dev.turboism.plugin.parameter-lock";
    private static final String ROW_ACTION_MARKER = "turboism.paletteRow.leadingAction";

    private static final long FLAG_TIMEOUT_MILLIS = 300_000L;
    private static final long MODEL_AWAIT_MAX_MILLIS = 300_000L;
    private static final long SETTLE_MILLIS = 2_000L;
    private static final long RETRY_INTERVAL_MILLIS = 1_000L;
    private static final float EPSILON = 0.0001f;

    private PluginContext context;
    private PluginLogger logger;
    private Path stateDir;

    private record ResolvedTargetRow(Parameter parameter, Object rowWidget, JLabel label) {}
    private record LiveTarget(Parameter parameter, Object rowWidget, JLabel label) {}
    private record CoordinatorBinding(boolean folder, String id, Component component) {}

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();

        final Thread exerciser = new Thread(this::runWhenFlagged, "parameter-lock-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();

        logger.info("PARAMETER_LOCK_PROBE_READY stateDir=" + stateDir);
    }

    @Override
    public void enable() {
        logger.info("PARAMETER_LOCK_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("PARAMETER_LOCK_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("PARAMETER_LOCK_PROBE_SHUTDOWN");
    }

    private void runWhenFlagged() {
        final Path flag = stateDir.resolve(FLAG);
        final Path parentFlag = stateDir.getParent() != null
                ? stateDir.getParent().resolve("dev.turboism.validation.parameter-lock/" + FLAG)
                : null;

        final long deadline = System.currentTimeMillis() + FLAG_TIMEOUT_MILLIS;
        logger.info("PARAMETER_LOCK_PROBE_AWAIT_FLAG flag=" + flag);

        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(flag) || (parentFlag != null && Files.isRegularFile(parentFlag))) {
                logger.info("PARAMETER_LOCK_PROBE_TRIGGERED flag=" + flag);
                executeValidation();
                return;
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        logger.error("PARAMETER_LOCK_PROBE_FLAG_TIMEOUT flag=" + flag);
        final Map<String, String> timeoutResults = new LinkedHashMap<>();
        timeoutResults.put("status", "FAIL");
        timeoutResults.put("mode", System.getProperty(MODE_PROPERTY, DEFAULT_MODE));
        timeoutResults.put("checks", "none");
        timeoutResults.put("failures", "flag_timeout_exceeded");
        writeResultFiles(timeoutResults);
        Runtime.getRuntime().halt(2);
    }

    private void executeValidation() {
        final long startNanos = System.nanoTime();
        final String mode = System.getProperty(MODE_PROPERTY, DEFAULT_MODE).strip();
        logger.info("PARAMETER_LOCK_VALIDATION_BEGIN mode=" + mode);

        final Map<String, String> results = new LinkedHashMap<>();
        final List<String> passedChecks = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        try {
            // 1. Await active model with non-empty drawables (real fixture loaded)
            final CubismModel model = awaitActiveModel();
            if (model == null) {
                throw new IllegalStateException("Active model with drawables not available within timeout");
            }
            final String modelId = onEdt(() -> model.id() != null ? model.id().value() : "unknown");
            results.put("modelId", modelId);
            logger.info("PARAMETER_LOCK_MODEL_ACTIVE modelId=" + modelId);

            switch (mode) {
                case "matrix" -> runMatrix(model, passedChecks, failures, results);
                case "persist-write" -> runPersistWrite(model, passedChecks, failures, results);
                case "persist-read" -> runPersistRead(model, passedChecks, failures, results);
                default -> throw new IllegalArgumentException("Unsupported validation mode: " + mode);
            }
        } catch (Exception failure) {
            logger.error("PARAMETER_LOCK_UNEXPECTED_FAILURE: " + failure.getMessage(), failure);
            failures.add("unexpected_failure: " + failure.getClass().getSimpleName() + " - " + failure.getMessage());
            captureUiSnapshot();
        }

        final boolean pass = failures.isEmpty();
        results.put("status", pass ? "PASS" : "FAIL");
        results.put("mode", mode);
        results.put("checks", String.join(",", passedChecks));
        results.put("durationMillis", String.valueOf((System.nanoTime() - startNanos) / 1_000_000L));
        if (!failures.isEmpty()) {
            results.put("failures", String.join(" | ", failures));
        }

        writeResultFiles(results);

        logger.info("PARAMETER_LOCK_VALIDATION_COMPLETE status=" + (pass ? "PASS" : "FAIL")
                + " mode=" + mode
                + " checks=" + passedChecks
                + " failures=" + failures.size()
                + " durationMillis=" + results.get("durationMillis"));

        try {
            Thread.sleep(SETTLE_MILLIS);
        } catch (InterruptedException ignored) {}

        Runtime.getRuntime().exit(pass ? 0 : 2);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Mode: matrix
    // ──────────────────────────────────────────────────────────────────────────

    private void runMatrix(
            final CubismModel model,
            final List<String> passedChecks,
            final List<String> failures,
            final Map<String, String> results) throws Exception {

        // 1. Discover first visible parameter row matching model source identity (no hidden fallback)
        final ResolvedTargetRow targetRow = awaitVisibleTargetParameterRow(model, results);
        if (targetRow == null) {
            failures.add("parameters: no visible parameter row matching model source identity found in Parameter palette");
            throw new IllegalStateException("No visible parameter row matching model source identity found in Parameter palette within timeout");
        }

        final Parameter target = targetRow.parameter();
        final String paramId = onEdt(() -> target.id().value());
        final String paramName = onEdt(() -> target.name().orElse(paramId));
        results.put("parameterId", paramId);
        results.put("parameterName", paramName);

        final float origVal = onEdt(target::getValue);
        final float minVal = onEdt(target::getMinimumValue);
        final float maxVal = onEdt(target::getMaximumValue);
        final float delta = (maxVal - minVal) > 0.001f ? (maxVal - minVal) * 0.1f : 1.0f;
        final float testVal = (origVal + delta <= maxVal) ? (origVal + delta) : (origVal - delta);

        logger.info("PARAMETER_LOCK_TARGET_PARAM id=" + paramId
                + " name=" + paramName
                + " origVal=" + origVal
                + " testVal=" + testVal);

        // Await production plugin's asynchronous decoration to install row leading action MARKER (at least 15s)
        awaitParamRowMarkerState(target, paramId, PaletteRowAction.State.UNLOCKED, 20_000L, results);

        // 2. Verify row leading action MARKER and native icon dimensions on fresh EDT label
        final JLabel targetLabel = resolveLiveParamLabel(target, paramId, 5_000L);
        verifyRowLeadingAction(targetLabel, PaletteRowAction.State.UNLOCKED, passedChecks, failures, results);
        if (!failures.isEmpty()) {
            return;
        }

        // 3. Step A: Unlocked writable verification (SDK write)
        onEdt(() -> {
            target.setValue(testVal);
            return null;
        });
        float currentVal = onEdt(target::getValue);
        if (Math.abs(currentVal - testVal) > EPSILON) {
            failures.add("unlockedWritable: expected " + testVal + " but got " + currentVal);
            return;
        }
        passedChecks.add("unlockedWritable");
        onEdt(() -> {
            target.setValue(origVal);
            return null;
        });

        // 4. Step B: Positive control - Native controller write when UNLOCKED
        driveNativeParameterWrite(target, paramId, testVal);
        final float nativeUnlockedVal = onEdt(target::getValue);
        if (Math.abs(nativeUnlockedVal - testVal) > EPSILON) {
            failures.add("nativeControllerWriteUnlocked: driving native controller a(source, float) did NOT change host value; got " + nativeUnlockedVal);
        } else {
            passedChecks.add("nativeControllerWriteUnlockedAccepted");
            logger.info("PARAMETER_LOCK_NATIVE_CONTROLLER_WRITE_ACCEPTED val=" + nativeUnlockedVal);
        }
        driveNativeParameterWrite(target, paramId, origVal);

        // 5. Select parameter row and collect rich selection diagnostics
        selectParameterRow(target, paramId, paramName, results);

        // 6. Step C: Lock parameter via real MouseEvent click on JLabel leading 16px icon slot
        logger.info("PARAMETER_LOCK_CLICK_ROW_LOCK paramId=" + paramId);
        clickParamRowLeadingAction(target, paramId);
        awaitParamRowMarkerState(target, paramId, PaletteRowAction.State.LOCKED, 10_000L, results);

        final JLabel lockedLabel = resolveLiveParamLabel(target, paramId, 5_000L);
        final Object lockedMarker = onEdt(() -> lockedLabel.getClientProperty(ROW_ACTION_MARKER));
        if (!isMarkerState(lockedMarker, PaletteRowAction.State.LOCKED)) {
            failures.add("rowLockClick: expected marker LOCKED after click on leading action slot, but got " + lockedMarker);
            return;
        }
        passedChecks.add("rowLockClicked");
        verifyEditingState(target, paramId, true, results);
        passedChecks.add("lockedEditorsDisabledAndShielded");
        logger.info("PARAMETER_LOCK_ROW_LOCK_CLICKED_OK marker=" + lockedMarker);

        // 7. Step D: Verify locked rejects SDK write (beforeSetParameterValue blocks mutation)
        onEdt(() -> {
            target.setValue(testVal);
            return null;
        });
        final float lockedVal = onEdt(target::getValue);
        if (Math.abs(lockedVal - origVal) > EPSILON) {
            failures.add("lockedRejectsWrite: parameter mutation was NOT rejected when locked; value changed to " + lockedVal);
        } else {
            passedChecks.add("lockedRejectsWrite");
            logger.info("PARAMETER_LOCK_WRITE_REJECTED_SUCCESSFULLY val=" + lockedVal);
        }

        // 8. Step E: Verify locked rejects NATIVE controller write (resolves fresh live row internally)
        driveNativeParameterWrite(target, paramId, testVal);
        final float nativeLockedVal = onEdt(target::getValue);
        if (Math.abs(nativeLockedVal - origVal) > EPSILON) {
            failures.add("nativeControllerWriteLocked: driving native controller write was NOT rejected when locked; value changed to " + nativeLockedVal);
        } else {
            passedChecks.add("nativeControllerWriteLockedRejected");
            logger.info("PARAMETER_LOCK_NATIVE_CONTROLLER_WRITE_LOCKED_REJECTED val=" + nativeLockedVal);
        }

        // 9. Step F: Inspect native parameter component (CSlidableFloat)
        final LiveTarget liveInspect = resolveLiveShowingTarget(target, paramId, 10_000L);
        if (liveInspect != null) {
            onEdt(() -> {
                inspectNativeControl(liveInspect.rowWidget(), passedChecks, results);
                return null;
            });
        }

        // 10. Step G: Unlock parameter via real MouseEvent click on JLabel leading 16px icon slot
        logger.info("PARAMETER_LOCK_CLICK_ROW_UNLOCK paramId=" + paramId);
        clickParamRowLeadingAction(target, paramId);
        awaitParamRowMarkerState(target, paramId, PaletteRowAction.State.UNLOCKED, 10_000L, results);

        final JLabel unlockedLabel = resolveLiveParamLabel(target, paramId, 5_000L);
        final Object unlockedMarker = onEdt(() -> unlockedLabel.getClientProperty(ROW_ACTION_MARKER));
        if (!isMarkerState(unlockedMarker, PaletteRowAction.State.UNLOCKED)) {
            failures.add("rowUnlockClick: expected marker UNLOCKED after click on leading action slot, but got " + unlockedMarker);
            return;
        }
        passedChecks.add("rowUnlockClicked");
        verifyEditingState(target, paramId, false, results);
        passedChecks.add("unlockedEditorsEnabledAndUnshielded");
        logger.info("PARAMETER_LOCK_ROW_UNLOCK_CLICKED_OK marker=" + unlockedMarker);

        // 11. Step H: Verify unlocked restored writable (SDK)
        onEdt(() -> {
            target.setValue(testVal);
            return null;
        });
        final float restoredVal = onEdt(target::getValue);
        if (Math.abs(restoredVal - testVal) > EPSILON) {
            failures.add("unlockedRestoredWritable: parameter was NOT writable after unlock; got " + restoredVal);
        } else {
            passedChecks.add("unlockedRestoredWritable");
            logger.info("PARAMETER_LOCK_WRITE_RESTORED_SUCCESSFULLY val=" + restoredVal);
        }
        onEdt(() -> {
            target.setValue(origVal);
            return null;
        });

        // 12. Step I: Verify unlocked restored writable (Native controller write on live resolved row)
        driveNativeParameterWrite(target, paramId, testVal);
        final float nativeRestoredVal = onEdt(target::getValue);
        if (Math.abs(nativeRestoredVal - testVal) > EPSILON) {
            failures.add("nativeControllerWriteUnlockedRestored: driving native controller write after unlock did NOT change value; got " + nativeRestoredVal);
        } else {
            passedChecks.add("nativeControllerWriteUnlockedRestored");
            logger.info("PARAMETER_LOCK_NATIVE_CONTROLLER_WRITE_RESTORED val=" + nativeRestoredVal);
        }
        driveNativeParameterWrite(target, paramId, origVal);

        // 13. Step J: Parameter Group Recursion and MIXED state verification
        verifyGroupRecursionAndMixedState(model, passedChecks, failures, results);

        // 14. Explicit boundaries
        results.put("sliderDrag.status", "NOT_TESTED");
        results.put("sliderDrag.boundary", "Interactive mouse slider drag requires physical display Robot; native lifecycle rejection verified via SDK setValue and native controller submission");
        results.put("textInput.status", "NOT_TESTED");
        results.put("textInput.boundary", "Direct textfield keystroke editing requires focused caret Robot; native rejection verified via host lifecycle interception");
        results.put("multiModelIsolation.status", "NOT_TESTED");
        results.put("multiModelIsolation.boundary",
                "Single-model fixture loaded; multi-model runtime switching and cross-model isolation is NOT_TESTED on single-document host run");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Mode: persist-write
    // ──────────────────────────────────────────────────────────────────────────

    private void runPersistWrite(
            final CubismModel model,
            final List<String> passedChecks,
            final List<String> failures,
            final Map<String, String> results) throws Exception {

        final ResolvedTargetRow targetRow = awaitVisibleTargetParameterRow(model, results);
        if (targetRow == null) {
            failures.add("persistWrite: no visible parameter row matching model source identity found in Parameter palette");
            return;
        }

        final Parameter target = targetRow.parameter();
        final String paramId = onEdt(() -> target.id().value());
        final String paramName = onEdt(() -> target.name().orElse(paramId));
        final String modelGuid = onEdt(() -> model.id().value());
        results.put("parameterId", paramId);

        // Await production plugin's asynchronous decoration to install row leading action MARKER (at least 15s)
        awaitParamRowMarkerState(target, paramId, PaletteRowAction.State.UNLOCKED, 20_000L, results);

        // Verify row leading action marker
        final JLabel targetLabel = resolveLiveParamLabel(target, paramId, 5_000L);
        verifyRowLeadingAction(targetLabel, PaletteRowAction.State.UNLOCKED, passedChecks, failures, results);

        // Lock parameter via row UI leading action MouseEvent click (freshly resolved right before click)
        logger.info("PARAMETER_LOCK_PERSIST_WRITE_CLICK_LOCK paramId=" + paramId);
        clickParamRowLeadingAction(target, paramId);
        awaitParamRowMarkerState(target, paramId, PaletteRowAction.State.LOCKED, 10_000L, results);

        final JLabel lockedLabel = resolveLiveParamLabel(target, paramId, 5_000L);
        final Object marker = onEdt(() -> lockedLabel.getClientProperty(ROW_ACTION_MARKER));
        if (!isMarkerState(marker, PaletteRowAction.State.LOCKED)) {
            failures.add("persistWrite: parameter row marker did not become LOCKED after click; got " + marker);
            return;
        }

        // Verify write is rejected on EDT
        final float origVal = onEdt(target::getValue);
        final float minVal = onEdt(target::getMinimumValue);
        final float maxVal = onEdt(target::getMaximumValue);
        final float delta = (maxVal - minVal) > 0.001f ? (maxVal - minVal) * 0.1f : 1.0f;
        final float testVal = (origVal + delta <= maxVal) ? (origVal + delta) : (origVal - delta);

        onEdt(() -> {
            target.setValue(testVal);
            return null;
        });
        final float lockedVal = onEdt(target::getValue);
        if (Math.abs(lockedVal - origVal) > EPSILON) {
            failures.add("persistWrite: lock did not take effect prior to save; value=" + lockedVal);
            return;
        }
        passedChecks.add("persistWriteLockActive");

        // Await production plugin's persistence file under TurboismHome/state/dev.turboism.plugin.parameter-lock/locks/<sha256>.json
        final Path turboismState = stateDir.getParent();
        final String guidHash = sha256Hex(modelGuid);
        final Path pluginLockFile = turboismState.resolve(PRODUCTION_PLUGIN_ID)
                .resolve("locks")
                .resolve(guidHash + ".json");

        logger.info("PARAMETER_LOCK_AWAIT_PERSISTENCE_FILE path=" + pluginLockFile);
        final long deadline = System.currentTimeMillis() + 10_000L;
        boolean fileFound = false;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(pluginLockFile) && Files.size(pluginLockFile) > 0) {
                fileFound = true;
                break;
            }
            Thread.sleep(500L);
        }

        if (!fileFound) {
            failures.add("persistWrite: production lock file was not written to " + pluginLockFile);
            return;
        }

        // Copy snapshot to standard location for user/runner multi-session transfer
        final Path publicSnapshot = turboismState.resolve(SNAPSHOT_FILE);
        final Path probeSnapshot = stateDir.resolve(SNAPSHOT_FILE);
        Files.copy(pluginLockFile, publicSnapshot, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pluginLockFile, probeSnapshot, StandardCopyOption.REPLACE_EXISTING);

        passedChecks.add("persistWriteSaved");
        results.put("persistWrite.lockFile", pluginLockFile.toString());
        results.put("persistWrite.snapshotPath", "state/" + SNAPSHOT_FILE);
        results.put("persistWrite.fileBytes", String.valueOf(Files.size(pluginLockFile)));
        logger.info("PARAMETER_LOCK_PERSIST_WRITE_SUCCESS snapshot=" + publicSnapshot);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Mode: persist-read
    // ──────────────────────────────────────────────────────────────────────────

    private void runPersistRead(
            final CubismModel model,
            final List<String> passedChecks,
            final List<String> failures,
            final Map<String, String> results) throws Exception {

        final Path turboismState = stateDir.getParent();
        final String modelGuid = onEdt(() -> model.id().value());
        final String guidHash = sha256Hex(modelGuid);
        final Path pluginLockFile = turboismState.resolve(PRODUCTION_PLUGIN_ID)
                .resolve("locks")
                .resolve(guidHash + ".json");

        if (!Files.isRegularFile(pluginLockFile) || Files.size(pluginLockFile) == 0) {
            throw new IllegalStateException("persistRead requires pre-seeded lock file at " + pluginLockFile
                    + ", but file does not exist or is empty. Runner must seed this file prior to host launch.");
        }
        logger.info("PARAMETER_LOCK_PERSIST_READ_FOUND_PRESEEDED_FILE path=" + pluginLockFile
                + " bytes=" + Files.size(pluginLockFile));

        // Allow async preload to complete
        Thread.sleep(3_000L);

        // Find the visible parameter that was persisted as locked
        final ResolvedTargetRow targetRow = awaitVisibleTargetParameterRow(model, results);
        if (targetRow == null) {
            failures.add("persistRead: no visible parameter row matching model source identity found in Parameter palette");
            return;
        }

        final Parameter target = targetRow.parameter();
        final String paramId = onEdt(() -> target.id().value());
        final String paramName = onEdt(() -> target.name().orElse(paramId));
        results.put("parameterId", paramId);

        // Await production plugin's asynchronous preloaded lock state to restore MARKER as LOCKED on startup (at least 15s)
        awaitParamRowMarkerState(target, paramId, PaletteRowAction.State.LOCKED, 20_000L, results);

        final JLabel liveTargetLabel = resolveLiveParamLabel(target, paramId, 5_000L);
        final Object restoredMarker = onEdt(() -> liveTargetLabel.getClientProperty(ROW_ACTION_MARKER));
        if (!isMarkerState(restoredMarker, PaletteRowAction.State.LOCKED)) {
            failures.add("persistRead: parameter row marker was NOT restored as LOCKED on startup; got " + restoredMarker);
            return;
        }

        // Verify parameter is ALREADY locked (mutation rejected) without any probe click
        final float origVal = onEdt(target::getValue);
        final float minVal = onEdt(target::getMinimumValue);
        final float maxVal = onEdt(target::getMaximumValue);
        final float delta = (maxVal - minVal) > 0.001f ? (maxVal - minVal) * 0.1f : 1.0f;
        final float testVal = (origVal + delta <= maxVal) ? (origVal + delta) : (origVal - delta);

        onEdt(() -> {
            target.setValue(testVal);
            return null;
        });
        float currentVal = onEdt(target::getValue);
        if (Math.abs(currentVal - origVal) > EPSILON) {
            failures.add("persistRead: parameter was NOT restored as locked from previous session; value changed to " + currentVal);
            return;
        }
        verifyEditingState(target, paramId, true, results);
        passedChecks.add("persistReadEditorsDisabled");
        passedChecks.add("persistReadRestoredLocked");
        logger.info("PARAMETER_LOCK_PERSIST_READ_LOCKED_RESTORED_OK marker=" + restoredMarker);

        // Unlock via row leading action MouseEvent click and verify it becomes writable
        clickParamRowLeadingAction(target, paramId);
        awaitParamRowMarkerState(target, paramId, PaletteRowAction.State.UNLOCKED, 10_000L, results);

        final JLabel unlockedLabel = resolveLiveParamLabel(target, paramId, 5_000L);
        final Object unlockedMarker = onEdt(() -> unlockedLabel.getClientProperty(ROW_ACTION_MARKER));
        if (!isMarkerState(unlockedMarker, PaletteRowAction.State.UNLOCKED)) {
            failures.add("persistRead: parameter row marker was NOT restored to UNLOCKED after unlock click; got " + unlockedMarker);
            return;
        }

        onEdt(() -> {
            target.setValue(testVal);
            return null;
        });
        float unlockedVal = onEdt(target::getValue);
        if (Math.abs(unlockedVal - testVal) > EPSILON) {
            failures.add("persistRead: parameter did not become writable after unlocking persisted state; got " + unlockedVal);
        } else {
            passedChecks.add("persistReadUnlocked");
            logger.info("PARAMETER_LOCK_PERSIST_READ_UNLOCKED_OK");
        }
        onEdt(() -> {
            target.setValue(origVal);
            return null;
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Row Action & Leading Icon Verification / Click Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void verifyRowLeadingAction(
            final JLabel label,
            final PaletteRowAction.State expectedState,
            final List<String> passedChecks,
            final List<String> failures,
            final Map<String, String> results) throws Exception {

        onEdt(() -> {
            final Object marker = label.getClientProperty(ROW_ACTION_MARKER);
            if (marker == null) {
                failures.add("rowLeadingAction: label missing client property marker '" + ROW_ACTION_MARKER + "'");
                return null;
            }
            final String markerStr = String.valueOf(marker);
            results.put("rowLeadingAction.marker", markerStr);
            if (expectedState != null && !isMarkerState(marker, expectedState)) {
                failures.add("rowLeadingAction: expected marker state " + expectedState.name() + " but got " + markerStr);
                return null;
            }

            results.put("rowLeadingAction.alignment", "INDEPENDENT_LEFT_SLOT");
            results.put("rowLeadingAction.nativeTextAlignment", String.valueOf(label.getHorizontalAlignment()));
            final Icon icon = label.getIcon();
            if (icon == null) {
                failures.add("rowLeadingAction: label icon is null");
                return null;
            }
            final int width = icon.getIconWidth();
            final int height = icon.getIconHeight();
            results.put("rowLeadingIcon.wrapperWidth", String.valueOf(width));
            results.put("rowLeadingIcon.wrapperHeight", String.valueOf(height));
            results.put("rowLeadingIcon.wrapperClass", icon.getClass().getName());

            // Reflectively inspect wrapper's normal and disabled Icon fields to verify logical 16x16 and native origin
            try {
                final Field normalField = findField(icon.getClass(), "normal");
                final Field disabledField = findField(icon.getClass(), "disabled");
                if (normalField != null) {
                    normalField.setAccessible(true);
                    final Object normalObj = normalField.get(icon);
                    if (normalObj instanceof Icon normalIcon) {
                        final int nw = normalIcon.getIconWidth();
                        final int nh = normalIcon.getIconHeight();
                        final String normalClass = normalIcon.getClass().getName();
                        results.put("rowLeadingIcon.normal.width", String.valueOf(nw));
                        results.put("rowLeadingIcon.normal.height", String.valueOf(nh));
                        results.put("rowLeadingIcon.normal.class", normalClass);

                        if (nw != 16 || nh != 16) {
                            failures.add("rowLeadingAction: expected normal icon to be exactly 16x16 logical, but got " + nw + "x" + nh);
                            return null;
                        }

                        if (normalClass.contains("CubismNativeIconResolver") || normalClass.contains("CachedIcon")) {
                            results.put("rowLeadingIcon.normal.provenance", "VERIFIED_CUBISM_NATIVE");
                        } else {
                            results.put("rowLeadingIcon.normal.provenance", "UNKNOWN_ORIGIN");
                            results.put("rowLeadingIcon.normal.boundary",
                                    "Normal icon origin class " + normalClass + " does not match verified CubismNativeIconResolver; native identity not claimed");
                        }
                    } else {
                        failures.add("rowLeadingAction: normal icon field is not an Icon instance; got " + normalObj);
                        return null;
                    }
                } else {
                    failures.add("rowLeadingAction: binding wrapper missing 'normal' field");
                    return null;
                }

                if (disabledField != null) {
                    disabledField.setAccessible(true);
                    final Object disabledObj = disabledField.get(icon);
                    if (disabledObj instanceof Icon disabledIcon) {
                        final int dw = disabledIcon.getIconWidth();
                        final int dh = disabledIcon.getIconHeight();
                        final String disabledClass = disabledIcon.getClass().getName();
                        results.put("rowLeadingIcon.disabled.width", String.valueOf(dw));
                        results.put("rowLeadingIcon.disabled.height", String.valueOf(dh));
                        results.put("rowLeadingIcon.disabled.class", disabledClass);

                        if (dw != 16 || dh != 16) {
                            failures.add("rowLeadingAction: expected disabled icon to be exactly 16x16 logical, but got " + dw + "x" + dh);
                            return null;
                        }
                    }
                }
            } catch (Throwable ex) {
                failures.add("rowLeadingAction: failed inspecting binding wrapper icon fields: " + ex.getMessage());
                return null;
            }

            passedChecks.add("rowLeadingActionVerified");
            logger.info("PARAMETER_LOCK_ROW_LEADING_ACTION_OK state=" + markerStr
                    + " wrapper=" + width + "x" + height
                    + " normal=" + results.get("rowLeadingIcon.normal.width") + "x" + results.get("rowLeadingIcon.normal.height")
                    + " class=" + results.get("rowLeadingIcon.normal.class"));
            return null;
        });
    }

    private void clickRowLeadingAction(final JLabel label) throws Exception {
        runOnEdt(() -> {
            if (label == null || !label.isShowing()) {
                throw new IllegalStateException("Cannot click row leading action: label is null or not showing");
            }
            // Lock painting/hit testing is independent of the native text layout.
            final int clickX;
            try {
                final Field borderField = findField(label.getIcon().getClass(), "originalBorder");
                if (borderField == null) throw new IllegalStateException("Missing independent lock slot border");
                borderField.setAccessible(true);
                final javax.swing.border.Border originalBorder = (javax.swing.border.Border) borderField.get(label.getIcon());
                final java.awt.Insets slotInsets = originalBorder == null ? new java.awt.Insets(0, 0, 0, 0)
                        : originalBorder.getBorderInsets(label);
                clickX = slotInsets.left + 8;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot locate independent lock slot", failure);
            }
            final int clickY = Math.max(1, label.getHeight() / 2);

            final long now = System.currentTimeMillis();
            final MouseEvent pressed = new MouseEvent(
                    label, MouseEvent.MOUSE_PRESSED, now,
                    InputEvent.BUTTON1_DOWN_MASK, clickX, clickY, 1, false, MouseEvent.BUTTON1);
            final MouseEvent released = new MouseEvent(
                    label, MouseEvent.MOUSE_RELEASED, now,
                    InputEvent.BUTTON1_DOWN_MASK, clickX, clickY, 1, false, MouseEvent.BUTTON1);
            final MouseEvent clicked = new MouseEvent(
                    label, MouseEvent.MOUSE_CLICKED, now,
                    InputEvent.BUTTON1_DOWN_MASK, clickX, clickY, 1, false, MouseEvent.BUTTON1);

            label.dispatchEvent(pressed);
            label.dispatchEvent(released);
            label.dispatchEvent(clicked);

            logger.info("PARAMETER_LOCK_ROW_CLICK_DISPATCHED text=" + label.getText()
                    + " at=(" + clickX + "," + clickY + ")"
                    + " consumed=" + clicked.isConsumed());
        });
    }

    private static boolean isMarkerState(final Object marker, final PaletteRowAction.State state) {
        if (marker == null || state == null) return false;
        if (marker == state) return true;
        return state.name().equals(String.valueOf(marker));
    }

    private JLabel resolveLiveParamLabel(final Parameter targetParam, final String paramId, final long timeoutMillis) throws Exception {
        final LiveTarget target = resolveLiveShowingTarget(targetParam, paramId, timeoutMillis);
        if (target == null || target.label() == null) {
            throw new IllegalStateException("Live visible JLabel matching model source identity not found for " + paramId);
        }
        return target.label();
    }

    private void clickParamRowLeadingAction(final Parameter targetParam, final String paramId) throws Exception {
        final JLabel label = resolveLiveParamLabel(targetParam, paramId, 10_000L);
        clickRowLeadingAction(label);
    }

    private void awaitParamRowMarkerState(
            final Parameter targetParam,
            final String paramId,
            final PaletteRowAction.State expected,
            final long timeoutMillis,
            final Map<String, String> results) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                final Boolean matches = onEdt(() -> {
                    final LiveTarget lt = resolveLiveShowingTarget(targetParam, paramId, 200L);
                    if (lt == null || lt.label() == null || !lt.label().isShowing()) return false;
                    final Object m = lt.label().getClientProperty(ROW_ACTION_MARKER);
                    return isMarkerState(m, expected);
                });
                if (Boolean.TRUE.equals(matches)) {
                    return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(50L);
        }

        // Timeout: collect diagnostics, capture UI screenshot, and fail closed
        final Map<String, String> diag = onEdt(() -> collectMarkerTimeoutDiagnostics(targetParam, paramId, expected));
        for (final Map.Entry<String, String> entry : diag.entrySet()) {
            if (results != null) {
                results.put(entry.getKey(), entry.getValue());
            }
            logger.error("PARAMETER_LOCK_MARKER_TIMEOUT_DIAG " + entry.getKey() + "=" + entry.getValue());
        }
        captureUiSnapshot();
        throw new IllegalStateException("Timeout awaiting parameter row marker state " + expected.name()
                + " for paramId=" + paramId + " after " + timeoutMillis + "ms. Diag: " + diag);
    }

    private JLabel resolveLiveGroupLabel(final CubismModel model, final String groupId, final long timeoutMillis) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final JLabel label = findGroupLabel(groupId, model);
            if (label != null && onEdt(label::isShowing)) {
                return label;
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("Live visible group JLabel not found in coordinator bindings for groupId=" + groupId);
    }

    private void clickGroupRowLeadingAction(final CubismModel model, final String groupId) throws Exception {
        final JLabel label = resolveLiveGroupLabel(model, groupId, 10_000L);
        clickRowLeadingAction(label);
    }

    private void awaitGroupRowMarkerState(
            final CubismModel model,
            final String groupId,
            final PaletteRowAction.State expected,
            final long timeoutMillis,
            final Map<String, String> results) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                final Boolean matches = onEdt(() -> {
                    final JLabel l = findGroupLabel(groupId, model);
                    if (l == null || !l.isShowing()) return false;
                    final Object m = l.getClientProperty(ROW_ACTION_MARKER);
                    return isMarkerState(m, expected);
                });
                if (Boolean.TRUE.equals(matches)) {
                    return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(50L);
        }

        // Timeout: collect diagnostics, capture UI screenshot, and fail closed
        final Map<String, String> diag = onEdt(() -> collectGroupMarkerTimeoutDiagnostics(model, groupId, expected));
        for (final Map.Entry<String, String> entry : diag.entrySet()) {
            if (results != null) {
                results.put(entry.getKey(), entry.getValue());
            }
            logger.error("PARAMETER_LOCK_GROUP_MARKER_TIMEOUT_DIAG " + entry.getKey() + "=" + entry.getValue());
        }
        captureUiSnapshot();
        throw new IllegalStateException("Timeout awaiting group row marker state " + expected.name()
                + " for groupId=" + groupId + " after " + timeoutMillis + "ms. Diag: " + diag);
    }

    private Map<String, String> collectMarkerTimeoutDiagnostics(
            final Parameter targetParam,
            final String paramId,
            final PaletteRowAction.State expected) {
        final Map<String, String> diag = new LinkedHashMap<>();
        diag.put("timeout.expectedMarker", expected != null ? expected.name() : "null");
        diag.put("timeout.paramId", paramId);

        // 1. Current live label status & row widget
        Object rowWidget = null;
        try {
            final LiveTarget live = resolveLiveShowingTarget(targetParam, paramId, 500L);
            if (live != null) {
                rowWidget = live.rowWidget();
                if (live.label() != null) {
                    diag.put("timeout.labelShowing", String.valueOf(live.label().isShowing()));
                    final Object m = live.label().getClientProperty(ROW_ACTION_MARKER);
                    diag.put("timeout.currentMarker", String.valueOf(m));
                    final Icon ic = live.label().getIcon();
                    diag.put("timeout.labelIconClass", ic != null ? ic.getClass().getName() : "null");
                    if (ic != null) {
                        diag.put("timeout.labelIconWidth", String.valueOf(ic.getIconWidth()));
                        diag.put("timeout.labelIconHeight", String.valueOf(ic.getIconHeight()));
                    }
                }
            } else {
                rowWidget = findSingleParameterRowWidget(paramId);
                diag.put("timeout.labelShowing", "false (live target null, fallback found: " + (rowWidget != null) + ")");
            }
        } catch (Throwable ex) {
            diag.put("timeout.labelErr", ex.getMessage());
        }

        // 2. Bridge & Coordinator status
        diag.putAll(collectCoordinatorStatusDiagnostics());

        // 3. Selectors & Step-by-Step invocation diagnostics
        diag.putAll(collectSelectorsStepDiagnostics(rowWidget));

        return diag;
    }

    private static Object invokeAccessor(final Object target, final String name) {
        if (target == null) return null;
        try {
            final Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ex) {
            return "EX:" + ex.getMessage();
        }
    }

    private Map<String, String> collectSelectorsStepDiagnostics(final Object rowWidget) {
        final Map<String, String> diag = new LinkedHashMap<>();
        try {
            ClassLoader bridgeLoader = context.getClass().getClassLoader();
            if (bridgeLoader == null) {
                bridgeLoader = Thread.currentThread().getContextClassLoader();
            }
            final Class<?> bridgeClass = Class.forName(
                    "dev.turboism.ui.appearance.control.NativeParameterAppearanceBridge",
                    true,
                    bridgeLoader);
            final Field installedField = bridgeClass.getDeclaredField("INSTALLED");
            installedField.setAccessible(true);
            final AtomicReference<?> ref = (AtomicReference<?>) installedField.get(null);
            final Object installed = ref != null ? ref.get() : null;
            if (installed == null) {
                diag.put("diag.selectors.error", "INSTALLED is null");
                return diag;
            }

            final Method selectorsMethod = installed.getClass().getDeclaredMethod("selectors");
            selectorsMethod.setAccessible(true);
            final Object selectors = selectorsMethod.invoke(installed);
            if (selectors == null) {
                diag.put("diag.selectors.error", "selectors is null");
                return diag;
            }

            // A. Output all Selectors values
            diag.put("diag.selector.singleRowOwner", String.valueOf(invokeAccessor(selectors, "singleRowOwner")));
            diag.put("diag.selector.doubleRowOwner", String.valueOf(invokeAccessor(selectors, "doubleRowOwner")));
            diag.put("diag.selector.folderRowOwner", String.valueOf(invokeAccessor(selectors, "folderRowOwner")));
            diag.put("diag.selector.parameterSourceMethod", String.valueOf(invokeAccessor(selectors, "parameterSourceMethod")));
            diag.put("diag.selector.secondaryParameterSourceMethod", String.valueOf(invokeAccessor(selectors, "secondaryParameterSourceMethod")));
            diag.put("diag.selector.folderSourceMethod", String.valueOf(invokeAccessor(selectors, "folderSourceMethod")));
            diag.put("diag.selector.parameterLabelField", String.valueOf(invokeAccessor(selectors, "parameterLabelField")));
            diag.put("diag.selector.secondaryParameterLabelField", String.valueOf(invokeAccessor(selectors, "secondaryParameterLabelField")));
            diag.put("diag.selector.folderLabelMethod", String.valueOf(invokeAccessor(selectors, "folderLabelMethod")));
            diag.put("diag.selector.parameterSourceOwner", String.valueOf(invokeAccessor(selectors, "parameterSourceOwner")));
            diag.put("diag.selector.folderSourceOwner", String.valueOf(invokeAccessor(selectors, "folderSourceOwner")));
            diag.put("diag.selector.parameterIdMethod", String.valueOf(invokeAccessor(selectors, "parameterIdMethod")));
            diag.put("diag.selector.folderIdMethod", String.valueOf(invokeAccessor(selectors, "folderIdMethod")));
            diag.put("diag.selector.idStringMethod", String.valueOf(invokeAccessor(selectors, "idStringMethod")));
            diag.put("diag.selector.cLabelOwner", String.valueOf(invokeAccessor(selectors, "cLabelOwner")));
            diag.put("diag.selector.cLabelSwingMethod", String.valueOf(invokeAccessor(selectors, "cLabelSwingMethod")));

            final ClassLoader hcl = (ClassLoader) invokeAccessor(selectors, "hostClassLoader");
            diag.put("diag.selector.hostClassLoader", hcl != null ? (hcl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(hcl))) : "null");

            // B. Target row class & classloader
            if (rowWidget != null) {
                final Class<?> rowClass = rowWidget.getClass();
                final ClassLoader rowCl = rowClass.getClassLoader();
                diag.put("diag.row.class", rowClass.getName());
                diag.put("diag.row.classLoader", rowCl != null ? (rowCl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(rowCl))) : "null");
                diag.put("diag.row.classLoaderMatchHost", String.valueOf(rowCl == hcl));

                // C. Step 1: parameterRow(row)
                try {
                    final Method parameterRowM = selectors.getClass().getDeclaredMethod("parameterRow", Object.class);
                    parameterRowM.setAccessible(true);
                    final Object res = parameterRowM.invoke(selectors, rowWidget);
                    diag.put("diag.step.parameterRow.result", String.valueOf(res));
                } catch (Throwable t) {
                    diag.put("diag.step.parameterRow.result", "EX:" + t.getClass().getSimpleName() + ":" + t.getMessage());
                }

                final String singleOwner = (String) invokeAccessor(selectors, "singleRowOwner");
                final String expectedSingleClass = singleOwner != null ? singleOwner.replace('/', '.') : "";
                final String doubleOwner = (String) invokeAccessor(selectors, "doubleRowOwner");
                final String expectedDoubleClass = doubleOwner != null ? doubleOwner.replace('/', '.') : "";
                diag.put("diag.step.parameterRow.matchesSingleOwner", String.valueOf(rowClass.getName().equals(expectedSingleClass)));
                diag.put("diag.step.parameterRow.matchesDoubleOwner", String.valueOf(rowClass.getName().equals(expectedDoubleClass)));

                // Step 2: Extract source
                Object sourceObj = null;
                final String sourceMethodName = (String) invokeAccessor(selectors, "parameterSourceMethod");
                try {
                    if (sourceMethodName != null) {
                        final Method srcM = rowClass.getMethod(sourceMethodName);
                        srcM.setAccessible(true);
                        sourceObj = srcM.invoke(rowWidget);
                        diag.put("diag.step.source.result", sourceObj != null ? sourceObj.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(sourceObj)) : "null");
                        if (sourceObj != null) {
                            final ClassLoader srcCl = sourceObj.getClass().getClassLoader();
                            diag.put("diag.step.source.classLoader", srcCl != null ? (srcCl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(srcCl))) : "null");
                            diag.put("diag.step.source.classLoaderMatchHost", String.valueOf(srcCl == hcl));
                        }
                    } else {
                        diag.put("diag.step.source.result", "SOURCE_METHOD_NAME_NULL");
                    }
                } catch (Throwable t) {
                    diag.put("diag.step.source.result", "EX:" + t.getClass().getSimpleName() + ":" + t.getMessage());
                }

                // Step 3: Extract id
                final String paramIdMethodName = (String) invokeAccessor(selectors, "parameterIdMethod");
                try {
                    if (paramIdMethodName != null) {
                        final Method idM = selectors.getClass().getDeclaredMethod("id", Object.class, String.class);
                        idM.setAccessible(true);
                        final Object idRes = idM.invoke(selectors, sourceObj, paramIdMethodName);
                        diag.put("diag.step.id.result", String.valueOf(idRes));
                    } else {
                        diag.put("diag.step.id.result", "PARAM_ID_METHOD_NAME_NULL");
                    }
                } catch (Throwable t) {
                    diag.put("diag.step.id.result", "EX:" + t.getClass().getSimpleName() + ":" + t.getMessage());
                }

                if (sourceObj != null && paramIdMethodName != null) {
                    try {
                        final String idStringMethodName = (String) invokeAccessor(selectors, "idStringMethod");
                        final Method rawIdM = sourceObj.getClass().getMethod(paramIdMethodName);
                        rawIdM.setAccessible(true);
                        final Object rawIdObj = rawIdM.invoke(sourceObj);
                        diag.put("diag.step.id.rawIdObj", rawIdObj != null ? rawIdObj.getClass().getName() : "null");
                        if (rawIdObj != null && idStringMethodName != null) {
                            final Method rawIdStrM = rawIdObj.getClass().getMethod(idStringMethodName);
                            rawIdStrM.setAccessible(true);
                            final Object rawIdStr = rawIdStrM.invoke(rawIdObj);
                            diag.put("diag.step.id.rawIdString", String.valueOf(rawIdStr));
                        }
                    } catch (Throwable t) {
                        diag.put("diag.step.id.rawError", t.getClass().getSimpleName() + ":" + t.getMessage());
                    }
                }

                // Step 4: Extract swingLabel
                Object cLabelObj = null;
                final String labelFieldName = (String) invokeAccessor(selectors, "parameterLabelField");
                try {
                    if (labelFieldName != null) {
                        final Field lf = findField(rowClass, labelFieldName);
                        if (lf != null) {
                            lf.setAccessible(true);
                            cLabelObj = lf.get(rowWidget);
                            diag.put("diag.step.cLabel.result", cLabelObj != null ? cLabelObj.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(cLabelObj)) : "null");
                            if (cLabelObj != null) {
                                final ClassLoader clCl = cLabelObj.getClass().getClassLoader();
                                diag.put("diag.step.cLabel.classLoader", clCl != null ? (clCl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(clCl))) : "null");
                                diag.put("diag.step.cLabel.classLoaderMatchHost", String.valueOf(clCl == hcl));
                            }
                        } else {
                            diag.put("diag.step.cLabel.result", "FIELD_NOT_FOUND:" + labelFieldName);
                        }
                    } else {
                        diag.put("diag.step.cLabel.result", "LABEL_FIELD_NAME_NULL");
                    }
                } catch (Throwable t) {
                    diag.put("diag.step.cLabel.result", "EX:" + t.getClass().getSimpleName() + ":" + t.getMessage());
                }

                try {
                    final Method swingLabelM = selectors.getClass().getDeclaredMethod("swingLabel", Object.class);
                    swingLabelM.setAccessible(true);
                    final Object swingComp = swingLabelM.invoke(selectors, cLabelObj);
                    diag.put("diag.step.swingLabel.result", swingComp != null ? swingComp.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(swingComp)) : "null");
                    if (swingComp instanceof Component comp) {
                        diag.put("diag.step.swingLabel.isShowing", String.valueOf(comp.isShowing()));
                        if (comp instanceof JLabel jl) {
                            diag.put("diag.step.swingLabel.text", jl.getText());
                            diag.put("diag.step.swingLabel.icon", String.valueOf(jl.getIcon()));
                            diag.put("diag.step.swingLabel.marker", String.valueOf(jl.getClientProperty(ROW_ACTION_MARKER)));
                        }
                    }
                } catch (Throwable t) {
                    diag.put("diag.step.swingLabel.result", "EX:" + t.getClass().getSimpleName() + ":" + t.getMessage());
                }
            } else {
                diag.put("diag.row", "rowWidget_is_null");
            }
        } catch (Throwable ex) {
            diag.put("diag.selectors.error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        return diag;
    }

    private Map<String, String> collectGroupMarkerTimeoutDiagnostics(
            final CubismModel model,
            final String groupId,
            final PaletteRowAction.State expected) {
        final Map<String, String> diag = new LinkedHashMap<>();
        diag.put("timeout.group.expectedMarker", expected != null ? expected.name() : "null");
        diag.put("timeout.group.groupId", groupId);

        try {
            final JLabel l = findGroupLabel(groupId, model);
            if (l != null) {
                diag.put("timeout.group.labelShowing", String.valueOf(l.isShowing()));
                final Object m = l.getClientProperty(ROW_ACTION_MARKER);
                diag.put("timeout.group.currentMarker", String.valueOf(m));
                final Icon ic = l.getIcon();
                diag.put("timeout.group.iconClass", ic != null ? ic.getClass().getName() : "null");
            } else {
                diag.put("timeout.group.labelShowing", "false (group label null)");
            }
        } catch (Throwable ex) {
            diag.put("timeout.group.labelErr", ex.getMessage());
        }

        diag.putAll(collectCoordinatorStatusDiagnostics());
        return diag;
    }

    private Map<String, String> collectCoordinatorStatusDiagnostics() {
        final Map<String, String> diag = new LinkedHashMap<>();
        try {
            ClassLoader bridgeLoader = context.getClass().getClassLoader();
            if (bridgeLoader == null) {
                bridgeLoader = Thread.currentThread().getContextClassLoader();
            }
            final Class<?> bridgeClass = Class.forName(
                    "dev.turboism.ui.appearance.control.NativeParameterAppearanceBridge",
                    true,
                    bridgeLoader);
            final Field installedField = bridgeClass.getDeclaredField("INSTALLED");
            installedField.setAccessible(true);
            final AtomicReference<?> ref = (AtomicReference<?>) installedField.get(null);
            final Object installed = ref != null ? ref.get() : null;
            diag.put("timeout.bridgeInstalled", String.valueOf(installed != null));

            if (installed != null) {
                final Method providerMethod = installed.getClass().getDeclaredMethod("provider");
                providerMethod.setAccessible(true);
                final Object provider = providerMethod.invoke(installed);
                diag.put("timeout.providerPresent", String.valueOf(provider != null));

                if (provider != null) {
                    final Field coordinatorField = provider.getClass().getDeclaredField("coordinator");
                    coordinatorField.setAccessible(true);
                    final Object coordinator = coordinatorField.get(provider);
                    diag.put("timeout.coordinatorPresent", String.valueOf(coordinator != null));

                    if (coordinator != null) {
                        final Method scopeMethod = coordinator.getClass().getDeclaredMethod("currentScope");
                        scopeMethod.setAccessible(true);
                        final Object scope = scopeMethod.invoke(coordinator);
                        diag.put("timeout.coordinatorScope", String.valueOf(scope));

                        final Method genMethod = coordinator.getClass().getMethod("hostGeneration");
                        final Object gen = genMethod.invoke(coordinator);
                        diag.put("timeout.hostGeneration", String.valueOf(gen));

                        final Field overridesField = coordinator.getClass().getDeclaredField("overrides");
                        overridesField.setAccessible(true);
                        final Map<?, ?> overrides = (Map<?, ?>) overridesField.get(coordinator);
                        diag.put("timeout.overridesCount", String.valueOf(overrides != null ? overrides.size() : -1));

                        final Field nativeIconsField = coordinator.getClass().getDeclaredField("nativeIcons");
                        nativeIconsField.setAccessible(true);
                        final Object nativeIcons = nativeIconsField.get(coordinator);
                        diag.put("timeout.nativeIconResolverPresent", String.valueOf(nativeIcons != null));

                        final Method bindingsMethod = coordinator.getClass().getMethod("parameterControlBindings");
                        final List<?> bindings = (List<?>) bindingsMethod.invoke(coordinator);
                        diag.put("timeout.parameterControlBindingsCount", String.valueOf(bindings != null ? bindings.size() : -1));
                    }
                }
            }
        } catch (Throwable ex) {
            diag.put("timeout.coordinatorDiagErr", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        return diag;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Parameter Group Recursion and MIXED State Verification
    // ──────────────────────────────────────────────────────────────────────────

    private record GroupTestCandidate(
            ParameterGroup group,
            String groupId,
            Set<String> descendants,
            Parameter param1,
            Parameter param2,
            boolean isNested) {}

    private void verifyGroupRecursionAndMixedState(
            final CubismModel model,
            final List<String> passedChecks,
            final List<String> failures,
            final Map<String, String> results) throws Exception {

        final List<ParameterGroup> allGroups = onEdt(() -> model.parameterGroups().all());
        final List<CoordinatorBinding> folderBindings = onEdt(() -> {
            final List<CoordinatorBinding> list = new ArrayList<>();
            for (final CoordinatorBinding b : getCoordinatorBindings()) {
                if (b.folder()) list.add(b);
            }
            return list;
        });

        logger.info("PARAMETER_LOCK_GROUPS_TOTAL count=" + allGroups.size() + " folderBindings=" + folderBindings.size());
        results.put("groupDiag.sdkGroupsCount", String.valueOf(allGroups.size()));
        results.put("groupDiag.coordinatorFoldersCount", String.valueOf(folderBindings.size()));

        // Log detailed diagnostics for all SDK groups and coordinator folder bindings
        final Map<String, String> groupDiag = onEdt(() -> collectAllGroupDiagnostics(model, allGroups, folderBindings));
        for (final Map.Entry<String, String> entry : groupDiag.entrySet()) {
            results.put(entry.getKey(), entry.getValue());
            logger.info("GROUP_DIAG " + entry.getKey() + "=" + entry.getValue());
        }

        GroupTestCandidate candidate = findExistingGroupCandidate(model, allGroups, folderBindings);
        final GroupFixtureMove move = candidate == null ? augmentSingleChildGroup(model, allGroups, results) : null;
        try {
            if (move != null) {
                candidate = findExistingGroupCandidate(model, onEdt(() -> model.parameterGroups().all()),
                        onEdt(this::getCoordinatorBindings));
            }
            if (candidate == null) {
                throw new IllegalStateException("No testable native group with at least two descendants; see group diagnostics");
            }
            runGroupVerification(model, candidate, passedChecks, failures, results);
        } finally {
            if (move != null) {
                onEdt(() -> {
                    model.parameterGroups().moveParameter(move.parameterId(), move.originalGroupId());
                    final ParameterGroup original = model.parameterGroups().find(move.originalGroupId());
                    if (!original.parameterIds().contains(move.parameterId())) {
                        throw new IllegalStateException("Failed restoring fixture parameter group membership");
                    }
                    return null;
                });
                results.put("groupFixture.restored", "true");
            }
        }
    }

    private record GroupFixtureMove(ParameterId parameterId, ParameterGroupId originalGroupId) {}

    private GroupFixtureMove augmentSingleChildGroup(
            final CubismModel model, final List<ParameterGroup> groups, final Map<String, String> results) throws Exception {
        return onEdt(() -> {
            for (ParameterGroup target : groups) {
                if (collectDescendantParameterIds(model, target.id()).size() != 1
                        || findGroupComponent(target.id().value(), model) == null) continue;
                for (ParameterGroup origin : groups) {
                    if (origin.id().equals(target.id()) || origin.parameterIds().isEmpty()) continue;
                    final ParameterId parameter = origin.parameterIds().get(0);
                    model.parameterGroups().moveParameter(parameter, target.id());
                    if (!model.parameterGroups().find(target.id()).parameterIds().contains(parameter)) {
                        throw new IllegalStateException("Fixture group move was not applied");
                    }
                    results.put("groupFixture.augmentation", "temporary in-memory move to existing native group; never saved");
                    results.put("groupFixture.movedParameter", parameter.value());
                    results.put("groupFixture.originalGroup", origin.id().value());
                    results.put("groupFixture.targetGroup", target.id().value());
                    return new GroupFixtureMove(parameter, origin.id());
                }
            }
            return null;
        });
    }

    private GroupTestCandidate findExistingGroupCandidate(
            final CubismModel model,
            final List<ParameterGroup> allGroups,
            final List<CoordinatorBinding> folderBindings) throws Exception {

        // Pass 1: Try nested candidate groups first
        for (final ParameterGroup group : allGroups) {
            final List<ParameterGroupId> children = onEdt(group::childGroupIds);
            if (children == null || children.isEmpty()) continue;
            final GroupTestCandidate cand = evaluateGroupCandidate(model, group, folderBindings, true);
            if (cand != null) {
                return cand;
            }
        }

        // Pass 2: Try flat candidate groups with >= 2 descendants
        for (final ParameterGroup group : allGroups) {
            final GroupTestCandidate cand = evaluateGroupCandidate(model, group, folderBindings, false);
            if (cand != null) {
                return cand;
            }
        }

        return null;
    }

    private GroupTestCandidate evaluateGroupCandidate(
            final CubismModel model,
            final ParameterGroup group,
            final List<CoordinatorBinding> folderBindings,
            final boolean requireNested) throws Exception {

        final String gId = onEdt(() -> group.id().value());
        final Set<String> descendants = onEdt(() -> collectDescendantParameterIds(model, group.id()));
        if (descendants.size() < 2) {
            return null;
        }

        final List<ParameterGroupId> children = onEdt(group::childGroupIds);
        final boolean isNested = children != null && !children.isEmpty();
        if (requireNested && !isNested) {
            return null;
        }

        // Verify native row widget exists and matches exact group ID
        final Object nativeRow = onEdt(() -> findNativeGroupRowWidget(gId));
        if (nativeRow == null) {
            return null;
        }

        // Ensure folder is expanded via exact verified native API (checks isExpand, never blind toggles)
        ensureFolderExpanded(nativeRow, gId);

        // Wait a short moment for descendant parameter rows to become showing in UI
        Thread.sleep(300L);

        // Dynamically re-resolve fresh group label on EDT to avoid stale references
        final JLabel groupLabel = findGroupLabel(gId, model);
        if (groupLabel == null || !onEdt(groupLabel::isShowing)) {
            return null;
        }

        // Await group row leading action marker up to 2s on fresh label
        final long markerDeadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < markerDeadline) {
            final JLabel freshLabel = findGroupLabel(gId, model);
            if (freshLabel != null && onEdt(() -> freshLabel.getClientProperty(ROW_ACTION_MARKER) != null)) break;
            Thread.sleep(50L);
        }

        // Find at least 1 visible descendant parameter in this group
        Parameter p1 = null;
        Parameter p2 = null;
        for (final String dId : descendants) {
            final Parameter dp = onEdt(() -> model.parameters().find(new ParameterId(dId)));
            if (dp != null) {
                if (p1 == null) {
                    try {
                        final LiveTarget lt = resolveLiveShowingTarget(dp, dId, 1_000L);
                        if (lt != null && onEdt(lt.label()::isShowing)) {
                            p1 = dp;
                            continue;
                        }
                    } catch (Exception ignored) {}
                }
                if (p2 == null && dp != p1) {
                    p2 = dp;
                }
            }
        }

        if (p1 != null && p2 == null) {
            for (final String dId : descendants) {
                final Parameter dp = onEdt(() -> model.parameters().find(new ParameterId(dId)));
                if (dp != null && !dId.equals(p1.id().value())) {
                    p2 = dp;
                    break;
                }
            }
        }

        if (p1 != null && p2 != null) {
            return new GroupTestCandidate(group, gId, descendants, p1, p2, isNested);
        }

        return null;
    }

    private void ensureFolderExpanded(final Object row, final String groupId) throws Exception {
        runOnEdt(() -> {
            try {
                if (row == null || !row.getClass().getName().equals(
                        "com.live2d.cubism.view.palette.parameter.ui.ParameterGroupRowWidget")
                        || !matchesGroupId(row, groupId)) {
                    throw new IllegalStateException("Native folder identity mismatch for " + groupId);
                }
                final Object pane = row.getClass().getMethod("getFoldingPane").invoke(row);
                if (pane == null || !pane.getClass().getName().equals("com.live2d.ui.container.CFoldingPane")) {
                    throw new IllegalStateException("Native folding pane missing for " + groupId);
                }
                final Method expanded = pane.getClass().getMethod("isExpand");
                if ((boolean) expanded.invoke(pane)) return;
                row.getClass().getMethod("ExpandOrCollapseFolder", pane.getClass()).invoke(row, pane);
                if (!(boolean) expanded.invoke(pane)) {
                    throw new IllegalStateException("Native folder did not expand: " + groupId);
                }
                logger.info("ENSURE_FOLDER_EXPANDED groupId=" + groupId);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native folder expansion failed for " + groupId, failure);
            }
        });
    }

    private Object findNativeGroupRowWidget(final String targetGroupId) {
        for (final Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            final Object found = searchGroupRowWidget(window, targetGroupId);
            if (found != null) return found;
        }
        return null;
    }

    private Object searchGroupRowWidget(final Component comp, final String targetGroupId) {
        if (comp == null || !comp.isShowing()) return null;

        try {
            final ClassLoader cl = comp.getClass().getClassLoader();
            if (cl != null) {
                final Class<?> kClass = cl.loadClass("com.live2d.ui.k");
                final Method aMethod = kClass.getMethod("a", Component.class);
                final Object widget = aMethod.invoke(null, comp);
                if (widget != null) {
                    final Object groupRow = resolveGroupRowWidget(widget);
                    if (groupRow != null && matchesGroupId(groupRow, targetGroupId)) {
                        return groupRow;
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (comp instanceof Container container) {
            for (final Component child : container.getComponents()) {
                final Object found = searchGroupRowWidget(child, targetGroupId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Object resolveGroupRowWidget(final Object widget) {
        Object curr = widget;
        int depth = 0;
        while (curr != null && depth < 10) {
            if (curr.getClass().getName().equals("com.live2d.cubism.view.palette.parameter.ui.ParameterGroupRowWidget")) {
                return curr;
            }
            try {
                final Method getParent = curr.getClass().getMethod("getParent");
                curr = getParent.invoke(curr);
            } catch (Throwable ex) {
                break;
            }
            depth++;
        }
        return null;
    }

    private boolean matchesGroupId(final Object groupRowWidget, final String expectedGroupId) {
        if (groupRowWidget == null || expectedGroupId == null) return false;
        try {
            final Method getParamGroup = groupRowWidget.getClass().getMethod("getParameterGroup");
            final Object groupSource = getParamGroup.invoke(groupRowWidget);
            if (groupSource != null) {
                final Method getId = groupSource.getClass().getMethod("getId");
                final Object idObj = getId.invoke(groupSource);
                if (idObj != null) {
                    final Method getIdStr = idObj.getClass().getMethod("getIdString");
                    final String actualId = (String) getIdStr.invoke(idObj);
                    return expectedGroupId.equals(actualId);
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private Component findGroupComponent(final String groupId, final CubismModel model) throws Exception {
        return onEdt(() -> {
            // 1. Ensure SDK group find succeeds with exact ID
            try {
                final ParameterGroup group = model.parameterGroups().find(new ParameterGroupId(groupId));
                if (group == null) return null;
            } catch (Exception notFound) {
                return null;
            }

            // 2. Strict exact match on coordinator binding ID: ONLY groupId.equals(b.id())
            final List<CoordinatorBinding> bindings = getCoordinatorBindings();
            for (final CoordinatorBinding b : bindings) {
                if (b.folder() && groupId.equals(b.id()) && b.component().isShowing()) {
                    return b.component();
                }
            }
            return null;
        });
    }

    private JLabel extractJLabel(final Component comp) {
        if (comp instanceof JLabel jl) {
            return jl;
        }
        if (comp instanceof Container c) {
            for (final Component child : c.getComponents()) {
                final JLabel jl = extractJLabel(child);
                if (jl != null) return jl;
            }
        }
        return null;
    }

    private void runGroupVerification(
            final CubismModel model,
            final GroupTestCandidate candidate,
            final List<String> passedChecks,
            final List<String> failures,
            final Map<String, String> results) throws Exception {

        final String groupId = candidate.groupId();
        final Set<String> candidateDescendants = candidate.descendants();
        final Parameter param1 = candidate.param1();
        final Parameter param2 = candidate.param2();
        final String param1Id = onEdt(() -> param1.id().value());
        final String param2Id = onEdt(() -> param2.id().value());
        final boolean isNested = candidate.isNested();

        results.put("testedGroupId", groupId);
        results.put("testedGroupDescendantsCount", String.valueOf(candidateDescendants.size()));
        results.put("testedGroupIsNested", String.valueOf(isNested));
        logger.info("PARAMETER_LOCK_TESTING_GROUP id=" + groupId + " isNested=" + isNested + " descendants=" + candidateDescendants);

        // Step A: Ensure all descendants in group start UNLOCKED
        for (final String dId : candidateDescendants) {
            final Parameter dp = onEdt(() -> model.parameters().find(new ParameterId(dId)));
            if (dp != null) {
                final float cur = onEdt(dp::getValue);
                final float min = onEdt(dp::getMinimumValue);
                final float max = onEdt(dp::getMaximumValue);
                final float delta = (max - min) > 0.001f ? (max - min) * 0.1f : 1.0f;
                final float test = (cur + delta <= max) ? (cur + delta) : (cur - delta);
                onEdt(() -> {
                    dp.setValue(test);
                    return null;
                });
                if (Math.abs(onEdt(dp::getValue) - test) > EPSILON) {
                    try {
                        clickParamRowLeadingAction(dp, dId);
                        awaitParamRowMarkerState(dp, dId, PaletteRowAction.State.UNLOCKED, 5_000L, results);
                    } catch (Exception ignored) {}
                } else {
                    onEdt(() -> {
                        dp.setValue(cur);
                        return null;
                    });
                }
            }
        }

        // Verify group marker is initially UNLOCKED
        awaitGroupRowMarkerState(model, groupId, PaletteRowAction.State.UNLOCKED, 10_000L, results);
        final JLabel initialGroupLabel = resolveLiveGroupLabel(model, groupId, 3_000L);
        final Object initialGroupMarker = onEdt(() -> initialGroupLabel.getClientProperty(ROW_ACTION_MARKER));
        if (!isMarkerState(initialGroupMarker, PaletteRowAction.State.UNLOCKED)) {
            failures.add("groupRecursion: expected group initially UNLOCKED but got " + initialGroupMarker);
            return;
        }

        // Step B: Lock ONLY param1 via its row leading action slot
        clickParamRowLeadingAction(param1, param1Id);
        awaitParamRowMarkerState(param1, param1Id, PaletteRowAction.State.LOCKED, 5_000L, results);

        // Verify param1 is locked (write rejected)
        final float p1Orig = onEdt(param1::getValue);
        final float p1Min = onEdt(param1::getMinimumValue);
        final float p1Max = onEdt(param1::getMaximumValue);
        final float p1Delta = (p1Max - p1Min) > 0.001f ? (p1Max - p1Min) * 0.1f : 1.0f;
        final float p1Test = (p1Orig + p1Delta <= p1Max) ? (p1Orig + p1Delta) : (p1Orig - p1Delta);
        onEdt(() -> {
            param1.setValue(p1Test);
            return null;
        });
        if (Math.abs(onEdt(param1::getValue) - p1Orig) > EPSILON) {
            failures.add("groupMixedState: param1 mutation was NOT rejected after locking; value=" + onEdt(param1::getValue));
            return;
        }

        // Verify param2 is still unlocked (write accepted)
        final float p2Orig = onEdt(param2::getValue);
        final float p2Min = onEdt(param2::getMinimumValue);
        final float p2Max = onEdt(param2::getMaximumValue);
        final float p2Delta = (p2Max - p2Min) > 0.001f ? (p2Max - p2Min) * 0.1f : 1.0f;
        final float p2Test = (p2Orig + p2Delta <= p2Max) ? (p2Orig + p2Delta) : (p2Orig - p2Delta);
        onEdt(() -> {
            param2.setValue(p2Test);
            return null;
        });
        if (Math.abs(onEdt(param2::getValue) - p2Test) > EPSILON) {
            failures.add("groupMixedState: param2 mutation was rejected when it should be unlocked");
            return;
        }
        onEdt(() -> {
            param2.setValue(p2Orig);
            return null;
        });

        // Step C: Verify group marker transitions to MIXED
        awaitGroupRowMarkerState(model, groupId, PaletteRowAction.State.MIXED, 10_000L, results);
        final JLabel mixedGroupLabel = resolveLiveGroupLabel(model, groupId, 3_000L);
        final Object mixedMarker = onEdt(() -> mixedGroupLabel.getClientProperty(ROW_ACTION_MARKER));
        if (!isMarkerState(mixedMarker, PaletteRowAction.State.MIXED)) {
            failures.add("groupMixedState: expected group marker MIXED when partial descendants locked, got " + mixedMarker);
            return;
        }
        passedChecks.add("groupMixedStateVerified");
        logger.info("PARAMETER_LOCK_GROUP_MIXED_STATE_VERIFIED groupId=" + groupId);

        // Step D: Click group leading action while in MIXED state -> locks ALL descendants
        logger.info("PARAMETER_LOCK_CLICK_GROUP_MIXED groupId=" + groupId);
        clickGroupRowLeadingAction(model, groupId);
        awaitGroupRowMarkerState(model, groupId, PaletteRowAction.State.LOCKED, 10_000L, results);
        final JLabel lockedGroupLabel = resolveLiveGroupLabel(model, groupId, 3_000L);
        final Object lockedGroupMarker = onEdt(() -> lockedGroupLabel.getClientProperty(ROW_ACTION_MARKER));
        if (!isMarkerState(lockedGroupMarker, PaletteRowAction.State.LOCKED)) {
            failures.add("groupMixedClick: expected group marker LOCKED after clicking mixed group, got " + lockedGroupMarker);
            return;
        }

        // Verify ALL descendants in group are now locked (writes rejected across all IDs)
        int lockedVerifiedCount = 0;
        for (final String dId : candidateDescendants) {
            final Parameter dp = onEdt(() -> model.parameters().find(new ParameterId(dId)));
            if (dp != null) {
                final float cur = onEdt(dp::getValue);
                final float min = onEdt(dp::getMinimumValue);
                final float max = onEdt(dp::getMaximumValue);
                final float delta = (max - min) > 0.001f ? (max - min) * 0.1f : 1.0f;
                final float test = (cur + delta <= max) ? (cur + delta) : (cur - delta);
                onEdt(() -> {
                    dp.setValue(test);
                    return null;
                });
                if (Math.abs(onEdt(dp::getValue) - cur) > EPSILON) {
                    failures.add("groupMixedClickLocksAll: descendant " + dId + " was not locked by group click; value changed to " + onEdt(dp::getValue));
                    return;
                }
                lockedVerifiedCount++;
            }
        }
        if (lockedVerifiedCount != candidateDescendants.size()) {
            failures.add("groupMixedClickLocksAll: could not verify all descendants locked; verified " + lockedVerifiedCount + "/" + candidateDescendants.size());
            return;
        }
        passedChecks.add("groupMixedClickLocksAll");
        results.put("groupLockAll.verifiedCount", String.valueOf(lockedVerifiedCount));
        logger.info("PARAMETER_LOCK_GROUP_MIXED_CLICK_LOCKS_ALL_VERIFIED groupId=" + groupId + " count=" + lockedVerifiedCount);

        // Step E: Click group leading action while in LOCKED state -> unlocks ALL descendants
        logger.info("PARAMETER_LOCK_CLICK_GROUP_UNLOCK groupId=" + groupId);
        clickGroupRowLeadingAction(model, groupId);
        awaitGroupRowMarkerState(model, groupId, PaletteRowAction.State.UNLOCKED, 10_000L, results);
        final JLabel unlockedGroupLabel = resolveLiveGroupLabel(model, groupId, 3_000L);
        final Object unlockedGroupMarker = onEdt(() -> unlockedGroupLabel.getClientProperty(ROW_ACTION_MARKER));
        if (!isMarkerState(unlockedGroupMarker, PaletteRowAction.State.UNLOCKED)) {
            failures.add("groupUnlockClick: expected group marker UNLOCKED after clicking locked group, got " + unlockedGroupMarker);
            return;
        }

        // Verify ALL descendants in group are now unlocked (writes accepted across all IDs)
        int unlockedVerifiedCount = 0;
        for (final String dId : candidateDescendants) {
            final Parameter dp = onEdt(() -> model.parameters().find(new ParameterId(dId)));
            if (dp != null) {
                final float cur = onEdt(dp::getValue);
                final float min = onEdt(dp::getMinimumValue);
                final float max = onEdt(dp::getMaximumValue);
                final float delta = (max - min) > 0.001f ? (max - min) * 0.1f : 1.0f;
                final float test = (cur + delta <= max) ? (cur + delta) : (cur - delta);
                onEdt(() -> {
                    dp.setValue(test);
                    return null;
                });
                if (Math.abs(onEdt(dp::getValue) - test) > EPSILON) {
                    failures.add("groupUnlockClickUnlocksAll: descendant " + dId + " was not restored writable after group unlock; got " + onEdt(dp::getValue));
                    return;
                }
                onEdt(() -> {
                    dp.setValue(cur);
                    return null;
                });
                unlockedVerifiedCount++;
            }
        }
        if (unlockedVerifiedCount != candidateDescendants.size()) {
            failures.add("groupUnlockClickUnlocksAll: could not verify all descendants unlocked; verified " + unlockedVerifiedCount + "/" + candidateDescendants.size());
            return;
        }
        passedChecks.add("groupUnlockClickUnlocksAll");
        passedChecks.add("groupRecursionVerified");
        results.put("groupUnlockAll.verifiedCount", String.valueOf(unlockedVerifiedCount));
        results.put("groupRecursion.status", "PASS");
        results.put("groupMixedState.status", "PASS");
        logger.info("PARAMETER_LOCK_GROUP_RECURSION_VERIFIED groupId=" + groupId + " count=" + unlockedVerifiedCount);

        // Update nestedGroupRecursion status strictly based on whether candidate is nested and passed
        if (isNested) {
            results.put("nestedGroupRecursion.status", "PASS");
            results.put("nestedGroupRecursion.parentId", groupId);
            results.put("nestedGroupRecursion.childGroupCount", String.valueOf(onEdt(() -> candidate.group().childGroupIds().size())));
            passedChecks.add("nestedGroupRecursionVerified");
            logger.info("PARAMETER_LOCK_NESTED_GROUP_RECURSION_VERIFIED parentId=" + groupId);
        } else {
            results.put("nestedGroupRecursion.status", "NOT_TESTED");
            results.put("nestedGroupRecursion.boundary",
                    "Tested candidate group " + groupId + " is single-tier (flat); active fixture did not exercise multi-tier nested group hierarchy");
            logger.info("PARAMETER_LOCK_NESTED_GROUP_RECURSION_NOT_TESTED: candidate group is single-tier");
        }
    }

    private Map<String, String> collectAllGroupDiagnostics(
            final CubismModel model,
            final List<ParameterGroup> allGroups,
            final List<CoordinatorBinding> folderBindings) {
        final Map<String, String> diag = new LinkedHashMap<>();

        // 1. Output all SDK groups
        int gIdx = 0;
        for (final ParameterGroup g : allGroups) {
            final String gid = g.id() != null ? g.id().value() : "<null>";
            final String name = g.name().orElse("<empty>");
            final String parent = g.parentId().map(ParameterGroupId::value).orElse("<root>");
            final List<String> pids = g.parameterIds().stream().map(ParameterId::value).toList();
            final List<String> cids = g.childGroupIds().stream().map(ParameterGroupId::value).toList();
            final Set<String> desc = collectDescendantParameterIds(model, g.id());

            diag.put("group[" + gIdx + "].id", gid);
            diag.put("group[" + gIdx + "].name", name);
            diag.put("group[" + gIdx + "].parentId", parent);
            diag.put("group[" + gIdx + "].parameterIds", String.join(";", pids));
            diag.put("group[" + gIdx + "].childGroupIds", String.join(";", cids));
            diag.put("group[" + gIdx + "].descendantsCount", String.valueOf(desc.size()));
            gIdx++;
        }

        // 2. Output all Coordinator folder bindings
        int fIdx = 0;
        for (final CoordinatorBinding b : folderBindings) {
            final Component comp = b.component();
            final boolean showing = comp != null && comp.isShowing();
            final String compClass = comp != null ? comp.getClass().getName() : "null";
            String text = "";
            String marker = "null";
            final JLabel jl = extractJLabel(comp);
            if (jl != null) {
                text = jl.getText() != null ? jl.getText() : "";
                marker = String.valueOf(jl.getClientProperty(ROW_ACTION_MARKER));
            }
            diag.put("folderBinding[" + fIdx + "].id", b.id());
            diag.put("folderBinding[" + fIdx + "].showing", String.valueOf(showing));
            diag.put("folderBinding[" + fIdx + "].class", compClass);
            diag.put("folderBinding[" + fIdx + "].text", text);
            diag.put("folderBinding[" + fIdx + "].marker", marker);
            fIdx++;
        }

        // 3. Strict exact match comparison / reason (ONLY exact gid.equals(b.id()))
        for (final ParameterGroup g : allGroups) {
            final String gid = g.id().value();
            final Set<String> desc = collectDescendantParameterIds(model, g.id());
            if (desc.size() < 2) {
                diag.put("groupMatch[" + gid + "].reason", "descendants_too_few (" + desc.size() + " < 2)");
                continue;
            }
            CoordinatorBinding matchedBinding = null;
            for (final CoordinatorBinding b : folderBindings) {
                if (gid.equals(b.id())) {
                    matchedBinding = b;
                    break;
                }
            }
            if (matchedBinding == null) {
                diag.put("groupMatch[" + gid + "].reason", "no_matching_folder_binding_in_coordinator");
            } else {
                final Component c = matchedBinding.component();
                final boolean showing = c != null && c.isShowing();
                final Object nativeRow = findNativeGroupRowWidget(gid);
                diag.put("groupMatch[" + gid + "].reason", "matched_binding_id=" + matchedBinding.id()
                        + ", showing=" + showing + ", nativeRowFound=" + (nativeRow != null));
            }
        }

        return diag;
    }

    private static Set<String> collectDescendantParameterIds(final CubismModel model, final ParameterGroupId rootGroupId) {
        final Set<String> collected = new LinkedHashSet<>();
        final Set<ParameterGroupId> visited = new HashSet<>();
        collectDescendantsRecursive(model, rootGroupId, collected, visited);
        return collected;
    }

    private static void collectDescendantsRecursive(
            final CubismModel model,
            final ParameterGroupId groupId,
            final Set<String> collected,
            final Set<ParameterGroupId> visited) {
        if (groupId == null || !visited.add(groupId)) {
            return;
        }
        try {
            final ParameterGroup group = model.parameterGroups().find(groupId);
            if (group == null) return;
            for (final ParameterId pid : group.parameterIds()) {
                collected.add(pid.value());
            }
            for (final ParameterGroupId childId : group.childGroupIds()) {
                collectDescendantsRecursive(model, childId, collected, visited);
            }
        } catch (Exception ignored) {}
    }

    private JLabel findGroupLabel(final String groupId, final CubismModel model) throws Exception {
        final Component comp = findGroupComponent(groupId, model);
        if (comp != null) {
            final JLabel jl = extractJLabel(comp);
            if (jl != null && onEdt(jl::isShowing)) {
                return jl;
            }
        }
        return null;
    }

    private List<CoordinatorBinding> getCoordinatorBindings() {
        try {
            ClassLoader bridgeLoader = context.getClass().getClassLoader();
            if (bridgeLoader == null) {
                bridgeLoader = Thread.currentThread().getContextClassLoader();
            }
            final Class<?> bridgeClass = Class.forName(
                    "dev.turboism.ui.appearance.control.NativeParameterAppearanceBridge",
                    true,
                    bridgeLoader);
            final Field installedField = bridgeClass.getDeclaredField("INSTALLED");
            installedField.setAccessible(true);
            final AtomicReference<?> ref = (AtomicReference<?>) installedField.get(null);
            final Object installed = ref != null ? ref.get() : null;
            if (installed == null) return List.of();

            final Method providerMethod = installed.getClass().getDeclaredMethod("provider");
            providerMethod.setAccessible(true);
            final Object provider = providerMethod.invoke(installed);
            if (provider == null) return List.of();

            final Field coordinatorField = provider.getClass().getDeclaredField("coordinator");
            coordinatorField.setAccessible(true);
            final Object coordinator = coordinatorField.get(provider);
            if (coordinator == null) return List.of();

            final Method bindingsMethod = coordinator.getClass().getMethod("parameterControlBindings");
            final List<?> rawBindings = (List<?>) bindingsMethod.invoke(coordinator);
            final List<CoordinatorBinding> result = new ArrayList<>();
            for (final Object b : rawBindings) {
                if (b == null) continue;
                final Method folderM = b.getClass().getMethod("folder");
                final Method idM = b.getClass().getMethod("id");
                final Method labelM = b.getClass().getMethod("label");
                final boolean folder = (boolean) folderM.invoke(b);
                final String id = (String) idM.invoke(b);
                final Component comp = (Component) labelM.invoke(b);
                result.add(new CoordinatorBinding(folder, id, comp));
            }
            return result;
        } catch (Throwable ex) {
            logger.warn("Failed retrieving coordinator bindings: " + ex.getMessage());
            return List.of();
        }
    }

    private JLabel findParameterLabelInCoordinator(final String paramId) {
        final List<CoordinatorBinding> bindings = getCoordinatorBindings();
        for (final CoordinatorBinding b : bindings) {
            if (!b.folder() && paramId.equals(b.id())) {
                if (b.component() instanceof JLabel jl) {
                    return jl;
                }
            }
        }
        return null;
    }

    private JLabel getRowJLabel(final Object rowWidget) {
        if (rowWidget == null) return null;
        try {
            final Field labelField = findField(rowWidget.getClass(), "paramNameLabel");
            if (labelField != null) {
                labelField.setAccessible(true);
                final Object cLabel = labelField.get(rowWidget);
                if (cLabel != null) {
                    final Method getJLabel = cLabel.getClass().getMethod("getJLabel");
                    final Object obj = getJLabel.invoke(cLabel);
                    if (obj instanceof JLabel jl) {
                        return jl;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Row Resolution & Inspection Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void activateParameterPalette() {
        try {
            final EditorCommandService service = context.services()
                    .find(EditorCommandService.class)
                    .orElse(EditorCommandService.unavailable());
            if (service.isAvailable()) {
                final EditorCommandResult res = service.execute(EditorCommand.SHOW_PARAMETER_PALETTE);
                logger.info("PARAMETER_LOCK_SHOW_PALETTE_EXECUTED status=" + res.status());
            } else {
                logger.warn("PARAMETER_LOCK_EDITOR_COMMAND_SERVICE_UNAVAILABLE");
            }
        } catch (Throwable failure) {
            logger.warn("PARAMETER_LOCK_SHOW_PALETTE_FAILED: " + failure.getMessage());
        }
    }

    private ResolvedTargetRow awaitVisibleTargetParameterRow(final CubismModel model, final Map<String, String> results) throws Exception {
        final long deadline = System.currentTimeMillis() + 30_000L;
        boolean triedActivatePalette = false;

        while (System.currentTimeMillis() < deadline) {
            final ResolvedTargetRow candidate = onEdt(() -> {
                final List<Parameter> allParams = model.parameters().all();
                for (Parameter p : allParams) {
                    final String paramId = p.id().value();
                    final Object rawSdkSource = findRawParameterSource(p, new ArrayList<>());
                    if (rawSdkSource == null) {
                        continue;
                    }
                    final Object rowWidget = findSingleParameterRowWidget(paramId);
                    if (rowWidget != null && isRowWidgetShowing(rowWidget)) {
                        try {
                            final Method getParam = rowWidget.getClass().getMethod("getParameter");
                            final Object rowSource = getParam.invoke(rowWidget);
                            if (rowSource != null && isCParameterSource(rowSource)) {
                                if (rowSource == rawSdkSource) {
                                    JLabel label = getRowJLabel(rowWidget);
                                    if (label == null) {
                                        label = findParameterLabelInCoordinator(paramId);
                                    }
                                    if (label != null && label.isShowing()) {
                                        return new ResolvedTargetRow(p, rowWidget, label);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                return null;
            });

            if (candidate != null) {
                return candidate;
            }

            if (!triedActivatePalette && (deadline - System.currentTimeMillis()) < 27_000L) {
                logger.info("PARAMETER_LOCK_AWAIT_ROW: attempting SHOW_PARAMETER_PALETTE to ensure palette visibility");
                activateParameterPalette();
                triedActivatePalette = true;
            }

            Thread.sleep(500L);
        }

        // Output detailed timeout diagnostics when no visible parameter row matches
        try {
            final Parameter firstParam = onEdt(() -> {
                final List<Parameter> all = model.parameters().all();
                return all.isEmpty() ? null : all.get(0);
            });
            if (firstParam != null) {
                final List<String> timeoutTrace = new ArrayList<>();
                final Object sdkSource = onEdt(() -> findRawParameterSource(firstParam, timeoutTrace));
                final String traceStr = String.join(" -> ", timeoutTrace);
                results.put("timeout.firstParam.id", onEdt(() -> firstParam.id().value()));
                results.put("timeout.firstParam.unwrapTrace", traceStr);
                results.put("timeout.firstParam.sdkSourceClass", sdkSource != null ? sdkSource.getClass().getName() : "null");
                results.put("timeout.firstParam.sdkSourceIdentity", sdkSource != null ? "@" + Integer.toHexString(System.identityHashCode(sdkSource)) : "null");
                logger.error("PARAMETER_LOCK_TIMEOUT_UNWRAP_DIAG param=" + onEdt(() -> firstParam.id().value())
                        + " source=" + (sdkSource != null ? sdkSource.getClass().getName() : "null")
                        + " trace=" + traceStr);
            }

            final Map<String, String> mismatchDiag = onEdt(() -> {
                final Map<String, String> map = new LinkedHashMap<>();
                final List<Parameter> all = model.parameters().all();
                int inspected = 0;
                for (final Parameter p : all) {
                    if (inspected++ >= 5) break;
                    final String pid = p.id().value();
                    final List<String> t = new ArrayList<>();
                    final Object sdkSrc = findRawParameterSource(p, t);
                    final Object row = findSingleParameterRowWidget(pid);
                    final boolean showing = row != null && isRowWidgetShowing(row);
                    Object rowSrc = null;
                    if (row != null) {
                        try {
                            rowSrc = row.getClass().getMethod("getParameter").invoke(row);
                        } catch (Throwable ignored) {}
                    }
                    map.put("timeout.param[" + pid + "].showing", String.valueOf(showing));
                    map.put("timeout.param[" + pid + "].sdkSource", sdkSrc != null ? sdkSrc.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(sdkSrc)) : "null");
                    map.put("timeout.param[" + pid + "].rowSource", rowSrc != null ? rowSrc.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(rowSrc)) : "null");
                    map.put("timeout.param[" + pid + "].match", String.valueOf(sdkSrc != null && rowSrc == sdkSrc));
                }
                return map;
            });
            for (final Map.Entry<String, String> entry : mismatchDiag.entrySet()) {
                results.put(entry.getKey(), entry.getValue());
                logger.error("PARAMETER_LOCK_TIMEOUT_MISMATCH_DIAG " + entry.getKey() + "=" + entry.getValue());
            }
        } catch (Throwable ex) {
            results.put("timeout.diagError", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            logger.error("PARAMETER_LOCK_TIMEOUT_DIAG_ERROR: " + ex.getMessage(), ex);
        }

        final Map<String, String> uiDiag = onEdt(this::collectUiHierarchyDiagnostics);
        for (final Map.Entry<String, String> entry : uiDiag.entrySet()) {
            results.put(entry.getKey(), entry.getValue());
            logger.error("PARAMETER_LOCK_ROW_UI_DIAG " + entry.getKey() + "=" + entry.getValue());
        }
        captureUiSnapshot();
        return null;
    }

    private LiveTarget resolveLiveShowingTarget(final Parameter targetParam, final String paramId, final long timeoutMillis) throws Exception {
        final List<String> trace = new ArrayList<>();
        final Object rawSdkSource = onEdt(() -> findRawParameterSource(targetParam, trace));
        if (rawSdkSource == null) {
            throw new IllegalStateException("Cannot resolve live row: SDK parameter raw CParameterSource could not be unwrapped for "
                    + paramId + ". Unwrap trace: " + String.join(" -> ", trace));
        }
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final LiveTarget candidate = onEdt(() -> {
                final Object row = findSingleParameterRowWidget(paramId);
                if (row != null && isRowWidgetShowing(row)) {
                    try {
                        final Method getParam = row.getClass().getMethod("getParameter");
                        final Object rowSource = getParam.invoke(row);
                        if (rowSource == rawSdkSource) {
                            JLabel label = getRowJLabel(row);
                            if (label == null) {
                                label = findParameterLabelInCoordinator(paramId);
                            }
                            if (label != null && label.isShowing()) {
                                return new LiveTarget(targetParam, row, label);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                return null;
            });
            if (candidate != null) {
                return candidate;
            }
            Thread.sleep(50L);
        }
        return null;
    }

    private void driveNativeParameterWrite(final Parameter targetParam, final String paramId, final float value) throws Exception {
        final LiveTarget live = resolveLiveShowingTarget(targetParam, paramId, 10_000L);
        if (live == null) {
            throw new IllegalStateException("driveNativeParameterWrite: live target row not found for paramId=" + paramId);
        }
        final Object rowWidget = live.rowWidget();
        runOnEdt(() -> {
            try {
                // 1. Update row widget current value
                final Method setParamCurrVal = rowWidget.getClass().getMethod("setParam_currentValue", float.class);
                setParamCurrVal.invoke(rowWidget, value);

                // 2. Submit to parameter controller via exact verified method s.a(CParameterSource, float)
                final Method getCtrlParam = rowWidget.getClass().getMethod("getCtrl_Param");
                final Object ctrlParam = getCtrlParam.invoke(rowWidget);

                final Method getParam = rowWidget.getClass().getMethod("getParameter");
                final Object paramSource = getParam.invoke(rowWidget);

                final Class<?> sourceClass = paramSource.getClass().getClassLoader()
                        .loadClass("com.live2d.cubism.doc.model.param.CParameterSource");
                final Method aMethod = ctrlParam.getClass().getMethod("a", sourceClass, float.class);
                aMethod.invoke(ctrlParam, paramSource, value);
            } catch (Exception ex) {
                throw new RuntimeException("driveNativeParameterWrite failed for paramId=" + paramId, ex);
            }
        });
    }

    private void verifyEditingState(final Parameter target, final String paramId, final boolean disabled, final Map<String, String> results) throws Exception {
        final long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            final LiveTarget live = resolveLiveShowingTarget(target, paramId, 2_000L);
            final boolean ready = live != null && onEdt(() -> {
                final Object row = live.rowWidget();
                final Object slider = row.getClass().getMethod("getSliderFloat").invoke(row);
                final javax.swing.JComponent value = (javax.swing.JComponent) slider.getClass().getMethod("getJComponent").invoke(slider);
                final Field paintField = row.getClass().getDeclaredField("paintComp"); paintField.setAccessible(true);
                final Object nativePaint = paintField.get(row);
                final javax.swing.JComponent drag = (javax.swing.JComponent) nativePaint.getClass().getMethod("getJComponent").invoke(nativePaint);
                final javax.swing.JComponent root = (javax.swing.JComponent) row.getClass().getMethod("getJComponent").invoke(row);
                final java.awt.Point point = SwingUtilities.convertPoint(drag, drag.getWidth()/2, drag.getHeight()/2, root);
                final Component hit = SwingUtilities.getDeepestComponentAt(root, point.x, point.y);
                final boolean shielded = hit != null && hit.getClass().getName().contains("ParameterEditingTracker$Overlay");
                final boolean marked = Boolean.TRUE.equals(value.getClientProperty("turboism.parameterEditing.disabled")) && Boolean.TRUE.equals(drag.getClientProperty("turboism.parameterEditing.disabled"));
                results.put("editing." + disabled + ".valueEnabled", String.valueOf(value.isEnabled()));
                results.put("editing." + disabled + ".dragEnabled", String.valueOf(drag.isEnabled()));
                results.put("editing." + disabled + ".pointerShielded", String.valueOf(shielded));
                final JLabel nameLabel = live.label();
                final java.awt.Color disabledName = javax.swing.UIManager.getColor("Label.disabledForeground");
                final Field foregroundField = findField(nameLabel.getIcon().getClass(), "originalForeground");
                if (foregroundField == null) throw new IllegalStateException("Missing restorable name color");
                foregroundField.setAccessible(true);
                final java.awt.Color expectedName = disabled
                        ? (disabledName == null ? java.awt.Color.GRAY : disabledName)
                        : (java.awt.Color) foregroundField.get(nameLabel.getIcon());
                final boolean nameColorMatches = expectedName.equals(nameLabel.getForeground());
                results.put("editing." + disabled + ".nameColorMatches", String.valueOf(nameColorMatches));
                if (!nameColorMatches) return false;
                return disabled ? !value.isEnabled() && !drag.isEnabled() && marked && shielded : value.isEnabled() && drag.isEnabled() && !marked && !shielded;
            });
            if (ready) return;
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Native editing controls did not reach disabled=" + disabled + ": " + results);
    }

    private void inspectNativeControl(
            final Object rowWidget,
            final List<String> passedChecks,
            final Map<String, String> results) {

        if (rowWidget != null) {
            results.put("nativeControl.found", "true");
            results.put("nativeControl.class", rowWidget.getClass().getName());

            // Inspect CSlidableFloat
            try {
                final Method getSlider = rowWidget.getClass().getMethod("getSliderFloat");
                final Object slider = getSlider.invoke(rowWidget);
                if (slider != null) {
                    results.put("nativeControl.sliderClass", slider.getClass().getName());
                    final Method getVal = slider.getClass().getMethod("getValue");
                    final Object val = getVal.invoke(slider);
                    results.put("nativeControl.sliderValue", String.valueOf(val));
                }
            } catch (Throwable ex) {
                results.put("nativeControl.sliderInspection", "unavailable: " + ex.getMessage());
            }

            passedChecks.add("nativeControlInspect");
            logger.info("PARAMETER_LOCK_NATIVE_ROW_INSPECTED class=" + rowWidget.getClass().getName());
        } else {
            results.put("nativeControl.found", "false");
            results.put("nativeControl.boundary",
                    "Native row widget not reachable via tree walk; write rejection verified via host lifecycle interception");
            logger.warn("PARAMETER_LOCK_NATIVE_ROW_NOT_FOUND");
        }
    }

    private Object selectParameterRow(
            final Parameter targetParam,
            final String paramId,
            final String paramName,
            final Map<String, String> results) throws Exception {

        logger.info("PARAMETER_LOCK_SELECTING_ROW paramId=" + paramId);

        // Always resolve fresh live showing row right before selection
        final LiveTarget live = resolveLiveShowingTarget(targetParam, paramId, 10_000L);
        if (live == null) {
            throw new IllegalStateException("selectParameterRow: live target not found for " + paramId);
        }
        final Object rowWidget = live.rowWidget();
        logger.info("PARAMETER_LOCK_NATIVE_ROW_WIDGET_FOUND class=" + rowWidget.getClass().getName()
                + " isShowing=" + onEdt(() -> isRowWidgetShowing(rowWidget)));

        // 1. Invoke native setSelectParameter() on EDT
        runOnEdt(() -> {
            try {
                final Method setSelect = rowWidget.getClass().getMethod("setSelectParameter");
                setSelect.setAccessible(true);
                setSelect.invoke(rowWidget);
                logger.info("PARAMETER_LOCK_SET_SELECT_PARAMETER_INVOKED");
            } catch (Exception ex) {
                throw new RuntimeException("Failed invoking setSelectParameter on " + rowWidget.getClass().getName(), ex);
            }
        });

        // 2. Collect bridge and selection diagnostics on EDT
        final Map<String, String> diag = onEdt(() -> collectSelectionDiagnostics(rowWidget, targetParam));
        for (Map.Entry<String, String> entry : diag.entrySet()) {
            results.put(entry.getKey(), entry.getValue());
            logger.info("PARAMETER_LOCK_DIAG " + entry.getKey() + "=" + entry.getValue());
        }

        // 3. Dispatch synthetic mouse clicks to Swing component
        runOnEdt(() -> {
            try {
                final JComponent comp = getWidgetJComponent(rowWidget);
                if (comp != null) {
                    dispatchClick(comp);
                }
            } catch (Throwable ignored) {}
        });

        // 4. Await and assert that target parameter is reflected in runtime selection (selectedObjectIds or activeParameterId)
        final long deadline = System.currentTimeMillis() + 5_000L;
        boolean selected = false;
        SelectionSnapshot lastSelection = null;
        while (System.currentTimeMillis() < deadline) {
            lastSelection = onEdt(() -> context.cubism().runtime().selection());
            if (lastSelection.selectedObjectIds().contains(paramId)
                    || paramId.equals(lastSelection.activeParameterId().orElse(null))) {
                selected = true;
                break;
            }
            Thread.sleep(100L);
        }

        if (!selected) {
            captureUiSnapshot();
            throw new IllegalStateException("Failed to assert target parameter selected in runtime selection within timeout: expected "
                    + paramId + ", actual selectedObjectIds="
                    + (lastSelection != null ? lastSelection.selectedObjectIds() : "<null>")
                    + ", activeParameterId="
                    + (lastSelection != null ? lastSelection.activeParameterId().orElse("<empty>") : "<null>"));
        }
        logger.info("PARAMETER_LOCK_ROW_SELECTED_OK paramId=" + paramId
                + " selectedIds=" + lastSelection.selectedObjectIds()
                + " activeParam=" + lastSelection.activeParameterId().orElse("<empty>"));

        return rowWidget;
    }

    private Map<String, String> collectSelectionDiagnostics(final Object rowWidget, final Parameter targetParam) {
        final Map<String, String> diag = new LinkedHashMap<>();

        try {
            ClassLoader bridgeLoader = context.getClass().getClassLoader();
            if (bridgeLoader == null) {
                bridgeLoader = targetParam.getClass().getClassLoader();
            }
            final Class<?> bridgeClass = Class.forName(
                    "dev.turboism.ui.appearance.control.NativeParameterAppearanceBridge",
                    true,
                    bridgeLoader);
            final Field installedField = bridgeClass.getDeclaredField("INSTALLED");
            installedField.setAccessible(true);
            final AtomicReference<?> ref = (AtomicReference<?>) installedField.get(null);
            final Object installed = ref != null ? ref.get() : null;

            diag.put("diag.bridgeInstalled", String.valueOf(installed != null));
            if (installed != null) {
                final Method selectedRowsMethod = installed.getClass().getDeclaredMethod("selectedRows");
                selectedRowsMethod.setAccessible(true);
                final Map<?, ?> selectedRows = (Map<?, ?>) selectedRowsMethod.invoke(installed);
                diag.put("diag.selectedRowsSize", String.valueOf(selectedRows.size()));
                final List<String> rowKeys = new ArrayList<>();
                for (Object k : selectedRows.keySet()) {
                    rowKeys.add(k.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(k)));
                }
                diag.put("diag.selectedRowsKeys", String.join(";", rowKeys));
            }

            final Method sourcesMethod = bridgeClass.getDeclaredMethod("selectedParameterSources");
            sourcesMethod.setAccessible(true);
            final List<?> sources = (List<?>) sourcesMethod.invoke(null);
            diag.put("diag.selectedParameterSourcesSize", String.valueOf(sources.size()));
            final List<String> sourceDetails = new ArrayList<>();
            for (Object s : sources) {
                sourceDetails.add(s.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(s)));
            }
            diag.put("diag.selectedParameterSources", String.join(";", sourceDetails));
        } catch (Throwable ex) {
            diag.put("diag.bridgeError", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        try {
            diag.put("diag.rowShowing", String.valueOf(isRowWidgetShowing(rowWidget)));
            final JComponent comp = getWidgetJComponent(rowWidget);
            if (comp != null) {
                diag.put("diag.rowSwingClass", comp.getClass().getName());
                diag.put("diag.rowSwingShowing", String.valueOf(comp.isShowing()));
            } else {
                diag.put("diag.rowSwingClass", "null");
                diag.put("diag.rowSwingShowing", "false");
            }
        } catch (Throwable ex) {
            diag.put("diag.rowShowingError", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        try {
            final Field labelField = findField(rowWidget.getClass(), "paramNameLabel");
            if (labelField != null) {
                labelField.setAccessible(true);
                final Object cLabel = labelField.get(rowWidget);
                if (cLabel != null) {
                    final Method getJLabel = cLabel.getClass().getMethod("getJLabel");
                    final Component jLabel = (Component) getJLabel.invoke(cLabel);
                    diag.put("diag.labelShowing", String.valueOf(jLabel != null && jLabel.isShowing()));
                    if (jLabel != null) {
                        diag.put("diag.labelClass", jLabel.getClass().getName());
                    }
                } else {
                    diag.put("diag.labelShowing", "cLabel_null");
                }
            } else {
                diag.put("diag.labelShowing", "field_null");
            }
        } catch (Throwable ex) {
            diag.put("diag.labelShowingError", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        try {
            final Method getParam = rowWidget.getClass().getMethod("getParameter");
            final Object rowSource = getParam.invoke(rowWidget);
            if (rowSource != null) {
                diag.put("diag.rowSourceClass", rowSource.getClass().getName());
                diag.put("diag.rowSourceIdentity", "@" + Integer.toHexString(System.identityHashCode(rowSource)));
                final Method getId = rowSource.getClass().getMethod("getId");
                final Object idObj = getId.invoke(rowSource);
                if (idObj != null) {
                    final Method getIdStr = idObj.getClass().getMethod("getIdString");
                    diag.put("diag.rowSourceId", String.valueOf(getIdStr.invoke(idObj)));
                }

                final List<String> sdkUnwrapTrace = new ArrayList<>();
                final Object sdkSource = findRawParameterSource(targetParam, sdkUnwrapTrace);
                diag.put("diag.sdkUnwrapTrace", String.join(" -> ", sdkUnwrapTrace));

                if (sdkSource != null) {
                    diag.put("diag.sdkSourceClass", sdkSource.getClass().getName());
                    diag.put("diag.sdkSourceIdentity", "@" + Integer.toHexString(System.identityHashCode(sdkSource)));
                    diag.put("diag.sourceIdentityMatch", String.valueOf(rowSource == sdkSource));
                } else {
                    diag.put("diag.sdkSourceClass", "null");
                    diag.put("diag.sdkSourceIdentity", "null");
                    diag.put("diag.sourceIdentityMatch", "false");
                }
            }
        } catch (Throwable ex) {
            diag.put("diag.sourceDiagError", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        return diag;
    }

    private Map<String, String> collectUiHierarchyDiagnostics() {
        final Map<String, String> diag = new LinkedHashMap<>();
        final Window[] windows = Window.getWindows();
        diag.put("ui.windows.total", String.valueOf(windows.length));
        int showingCount = 0;
        final List<String> windowSummaries = new ArrayList<>();
        final List<String> allRowIds = new ArrayList<>();
        final List<String> showingRowIds = new ArrayList<>();

        for (final Window w : windows) {
            if (w.isShowing()) {
                showingCount++;
                String title = w instanceof java.awt.Frame f ? f.getTitle() : (w instanceof java.awt.Dialog d ? d.getTitle() : "");
                windowSummaries.add(w.getClass().getSimpleName() + "[\"" + title + "\"," + w.getWidth() + "x" + w.getHeight() + "]");
                collectRowsInContainer(w, allRowIds, showingRowIds);
            }
        }
        diag.put("ui.windows.showing", String.valueOf(showingCount));
        diag.put("ui.windows.list", String.join("; ", windowSummaries));
        diag.put("ui.rows.totalFound", String.valueOf(allRowIds.size()));
        diag.put("ui.rows.showingCount", String.valueOf(showingRowIds.size()));
        diag.put("ui.rows.showingIds", String.join(";", showingRowIds));
        return diag;
    }

    private void collectRowsInContainer(final Component comp, final List<String> allIds, final List<String> showingIds) {
        if (comp != null) {
            try {
                final ClassLoader cl = comp.getClass().getClassLoader();
                if (cl != null) {
                    final Class<?> kClass = cl.loadClass("com.live2d.ui.k");
                    final Method aMethod = kClass.getMethod("a", Component.class);
                    final Object widget = aMethod.invoke(null, comp);
                    if (widget != null) {
                        final Object rowWidget = resolveRowWidget(widget);
                        if (rowWidget != null) {
                            final String id = extractParameterId(rowWidget);
                            if (id != null && !allIds.contains(id)) {
                                allIds.add(id);
                                if (isRowWidgetShowing(rowWidget)) {
                                    showingIds.add(id);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (comp instanceof Container container) {
            for (final Component child : container.getComponents()) {
                collectRowsInContainer(child, allIds, showingIds);
            }
        }
    }

    private String extractParameterId(final Object rowWidget) {
        try {
            final Method m = rowWidget.getClass().getMethod("getParameter1_id");
            final Object idObj = m.invoke(rowWidget);
            if (idObj != null) {
                final Method getIdStr = idObj.getClass().getMethod("getIdString");
                return (String) getIdStr.invoke(idObj);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void captureUiSnapshot() {
        try {
            final Window target = onEdt(() -> {
                for (final Window window : Window.getWindows()) {
                    if (window.isShowing() && window.getWidth() > 200 && window.getHeight() > 200) {
                        return window;
                    }
                }
                return null;
            });
            if (target != null) {
                final Window win = target;
                final BufferedImage img = onEdt(() -> {
                    final BufferedImage bImg = new BufferedImage(
                            Math.max(1, win.getWidth()),
                            Math.max(1, win.getHeight()),
                            BufferedImage.TYPE_INT_ARGB);
                    final Graphics2D g2 = bImg.createGraphics();
                    win.paint(g2);
                    g2.dispose();
                    return bImg;
                });
                if (stateDir != null) {
                    final Path statePath = stateDir.resolve(UI_SNAPSHOT_PNG);
                    ImageIO.write(img, "PNG", statePath.toFile());
                    logger.info("PARAMETER_LOCK_UI_SNAPSHOT_SAVED path=" + statePath);
                    if (stateDir.getParent() != null) {
                        final Path publicPath = stateDir.getParent().resolve(UI_SNAPSHOT_PNG);
                        Files.copy(statePath, publicPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Throwable failure) {
            logger.warn("Failed capturing UI snapshot screenshot: " + failure.getMessage());
        }
    }

    private static boolean isCParameterSource(final Object obj) {
        return obj != null && obj.getClass().getName().contains("CParameterSource");
    }

    private static Object findRawParameterSource(final Object target, final List<String> trace) {
        if (target == null) {
            trace.add("target=null");
            return null;
        }
        Object curr = target;
        final Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int depth = 0; depth < 16; depth++) {
            if (curr == null || !visited.add(curr)) {
                trace.add("stop@" + depth + (curr == null ? ":null" : ":visited"));
                break;
            }
            final String className = curr.getClass().getName();
            trace.add(className);
            if (isCParameterSource(curr)) {
                return curr;
            }

            // 0. Unwrap dynamic proxies via Proxy.getInvocationHandler -> delegate/target
            if (Proxy.isProxyClass(curr.getClass())) {
                try {
                    final InvocationHandler handler = Proxy.getInvocationHandler(curr);
                    trace.add("proxyHandler(" + handler.getClass().getName() + ")");
                    Field delegateField = findField(handler.getClass(), "delegate");
                    if (delegateField == null) {
                        delegateField = findField(handler.getClass(), "target");
                    }
                    if (delegateField != null) {
                        delegateField.setAccessible(true);
                        final Object delegateObj = delegateField.get(handler);
                        if (delegateObj != null) {
                            curr = delegateObj;
                            continue;
                        }
                    } else {
                        trace.add("proxy_delegate_field_null:" + handler.getClass().getName());
                    }
                } catch (Throwable t) {
                    trace.add("proxy_unwrap_error:" + t.getClass().getSimpleName() + ":" + t.getMessage());
                }
            }

            // A. Check for private/public source() method
            try {
                final Method sourceMethod = findMethod(curr.getClass(), "source");
                if (sourceMethod != null) {
                    sourceMethod.setAccessible(true);
                    final Object source = sourceMethod.invoke(curr);
                    if (isCParameterSource(source)) {
                        trace.add("source()->" + source.getClass().getName());
                        return source;
                    }
                }
            } catch (Throwable t) {
                trace.add("sourceMethod_err:" + t.getClass().getSimpleName() + ":" + t.getMessage());
            }

            // B. Check authoringSource field
            try {
                final Field authoringField = findField(curr.getClass(), "authoringSource");
                if (authoringField != null) {
                    authoringField.setAccessible(true);
                    Object source = authoringField.get(curr);
                    if (source == null) {
                        try {
                            final Method currentMethod = findMethod(curr.getClass(), "current");
                            if (currentMethod != null) {
                                currentMethod.setAccessible(true);
                                currentMethod.invoke(curr);
                                source = authoringField.get(curr);
                            }
                        } catch (Throwable t) {
                            trace.add("current_err:" + t.getClass().getSimpleName());
                        }
                    }
                    if (isCParameterSource(source)) {
                        trace.add("authoringSource->" + source.getClass().getName());
                        return source;
                    }
                }
            } catch (Throwable t) {
                trace.add("authoringField_err:" + t.getClass().getSimpleName() + ":" + t.getMessage());
            }

            // C. Check delegate field
            Object next = null;
            try {
                final Field delegateField = findField(curr.getClass(), "delegate");
                if (delegateField != null) {
                    delegateField.setAccessible(true);
                    next = delegateField.get(curr);
                }
            } catch (Throwable t) {
                trace.add("delegate_err:" + t.getClass().getSimpleName() + ":" + t.getMessage());
            }

            // D. Scan all fields for any delegate / Parameter
            if (next == null) {
                for (final Field f : getAllFields(curr.getClass())) {
                    try {
                        f.setAccessible(true);
                        final Object val = f.get(curr);
                        if (val != null) {
                            if (isCParameterSource(val)) {
                                trace.add("field(" + f.getName() + ")->" + val.getClass().getName());
                                return val;
                            }
                            if (val instanceof Parameter || f.getName().contains("delegate") || f.getName().contains("target")) {
                                next = val;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            if (next == null || next == curr) {
                trace.add("cannot_unwrap_further");
                break;
            }
            curr = next;
        }
        return null;
    }

    private static List<Field> getAllFields(final Class<?> clazz) {
        final List<Field> fields = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            fields.addAll(List.of(c.getDeclaredFields()));
            c = c.getSuperclass();
        }
        return fields;
    }

    private static Field findField(final Class<?> clazz, final String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(final Class<?> clazz, final String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private void dispatchClick(final Component target) {
        final int x = Math.max(1, target.getWidth() / 2);
        final int y = Math.max(1, target.getHeight() / 2);
        final long now = System.currentTimeMillis();
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now,
                InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now,
                InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, now,
                InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
    }

    private static JComponent getWidgetJComponent(final Object widget) {
        if (widget == null) return null;
        try {
            final Method m = widget.getClass().getMethod("getJComponent");
            final Object obj = m.invoke(widget);
            if (obj instanceof JComponent jc) return jc;
        } catch (Throwable ignored) {}
        try {
            final Method m = widget.getClass().getMethod("getSwingComponent");
            final Object obj = m.invoke(widget);
            if (obj instanceof JComponent jc) return jc;
        } catch (Throwable ignored) {}
        return null;
    }

    private Object findSingleParameterRowWidget(final String paramId) {
        for (final Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            final Object found = searchRowWidget(window, paramId);
            if (found != null) return found;
        }
        return null;
    }

    private Object searchRowWidget(final Component comp, final String paramId) {
        if (comp == null || !comp.isShowing()) return null;

        // Try extracting CWidget from component via com.live2d.ui.k.a(comp)
        try {
            final ClassLoader cl = comp.getClass().getClassLoader();
            if (cl != null) {
                final Class<?> kClass = cl.loadClass("com.live2d.ui.k");
                final Method aMethod = kClass.getMethod("a", Component.class);
                final Object widget = aMethod.invoke(null, comp);
                if (widget != null) {
                    final Object rowWidget = resolveRowWidget(widget);
                    if (rowWidget != null && matchesParamId(rowWidget, paramId) && isRowWidgetShowing(rowWidget)) {
                        return rowWidget;
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (comp instanceof Container container) {
            for (final Component child : container.getComponents()) {
                final Object found = searchRowWidget(child, paramId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean isRowWidgetShowing(final Object rowWidget) {
        if (rowWidget == null) return false;
        final JComponent comp = getWidgetJComponent(rowWidget);
        if (comp != null && comp.isShowing()) {
            return true;
        }
        try {
            final Field labelField = findField(rowWidget.getClass(), "paramNameLabel");
            if (labelField != null) {
                labelField.setAccessible(true);
                final Object cLabel = labelField.get(rowWidget);
                if (cLabel != null) {
                    final JComponent jc = getWidgetJComponent(cLabel);
                    if (jc != null && jc.isShowing()) {
                        return true;
                    }
                    final Method getJLabel = cLabel.getClass().getMethod("getJLabel");
                    final Component jLabel = (Component) getJLabel.invoke(cLabel);
                    if (jLabel != null && jLabel.isShowing()) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private Object resolveRowWidget(Object widget) {
        Object curr = widget;
        int depth = 0;
        while (curr != null && depth < 10) {
            if (curr.getClass().getName().contains("SingleParameterRowWidget")) {
                return curr;
            }
            try {
                final Method getParent = curr.getClass().getMethod("getParent");
                curr = getParent.invoke(curr);
            } catch (Throwable ex) {
                break;
            }
            depth++;
        }
        return null;
    }

    private boolean matchesParamId(final Object rowWidget, final String targetParamId) {
        try {
            final Method m = rowWidget.getClass().getMethod("getParameter1_id");
            final Object idObj = m.invoke(rowWidget);
            if (idObj != null) {
                final Method getIdStr = idObj.getClass().getMethod("getIdString");
                final String idStr = (String) getIdStr.invoke(idObj);
                if (targetParamId.equals(idStr)) return true;
            }
        } catch (Throwable ignored) {}
        try {
            final Method getParam = rowWidget.getClass().getMethod("getParameter");
            final Object param = getParam.invoke(rowWidget);
            if (param != null) {
                final Method getId = param.getClass().getMethod("getId");
                final Object idObj = getId.invoke(param);
                if (idObj != null) {
                    final Method getIdStr = idObj.getClass().getMethod("getIdString");
                    final String idStr = (String) getIdStr.invoke(idObj);
                    if (targetParamId.equals(idStr)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private CubismModel awaitActiveModel() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + MODEL_AWAIT_MAX_MILLIS;
        final long started = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                final Boolean ready = onEdt(() -> {
                    if (context.cubism().model().isAvailable()) {
                        final CubismModel m = context.cubism().model().active();
                        if (m != null) {
                            final boolean hasDrawables = !m.drawables().all().isEmpty();
                            final boolean hasParams = !m.parameters().all().isEmpty();
                            return hasDrawables && hasParams;
                        }
                    }
                    return false;
                });
                if (Boolean.TRUE.equals(ready)) {
                    final CubismModel model = onEdt(() -> context.cubism().model().active());
                    logger.info("PARAMETER_LOCK_MODEL_READY elapsedMs="
                            + (System.currentTimeMillis() - started)
                            + " modelId=" + onEdt(() -> model.id().value())
                            + " drawables=" + onEdt(() -> model.drawables().all().size())
                            + " params=" + onEdt(() -> model.parameters().all().size()));
                    return model;
                }
            } catch (Exception ignored) {}
            Thread.sleep(RETRY_INTERVAL_MILLIS);
        }
        return null;
    }

    private void writeResultFiles(final Map<String, String> properties) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, String> entry : properties.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue().replace('\n', ' ')).append('\n');
        }
        final String content = sb.toString();

        try {
            Files.createDirectories(stateDir);
            Files.writeString(stateDir.resolve(RESULT_FILE), content);
            logger.info("PARAMETER_LOCK_RESULT_WRITTEN path=" + stateDir.resolve(RESULT_FILE));
        } catch (IOException error) {
            logger.warn("Failed writing probe result to stateDir: " + error.getMessage());
        }

        final Path parentState = stateDir.getParent();
        if (parentState != null) {
            try {
                Files.createDirectories(parentState);
                Files.writeString(parentState.resolve(RESULT_FILE), content);
                logger.info("PARAMETER_LOCK_RESULT_WRITTEN path=" + parentState.resolve(RESULT_FILE));
            } catch (IOException error) {
                logger.warn("Failed writing probe result to parent state: " + error.getMessage());
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // EDT Dispatch Helpers
    // ──────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface EdtSupplier<T> {
        T get() throws Exception;
    }

    private static <T> T onEdt(final EdtSupplier<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.get());
            } catch (Exception ex) {
                error.set(ex);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    private static void runOnEdt(final Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        final AtomicReference<Exception> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Exception ex) {
                error.set(ex);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
    }

    private static String sha256Hex(final String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(text.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
