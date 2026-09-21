package dev.turboism.validation.edithost;

import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.edit.CancelSource;
import dev.turboism.sdk.cubism.edit.EditCancelledException;
import dev.turboism.sdk.cubism.edit.EditObjectKind;
import dev.turboism.sdk.cubism.edit.EditObjectNode;
import dev.turboism.sdk.cubism.edit.EditObjectSnapshot;
import dev.turboism.sdk.cubism.edit.EditParameterGroupNode;
import dev.turboism.sdk.cubism.edit.EditParameterKeyCondition;
import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.edit.EditSessionCloseOutcome;
import dev.turboism.sdk.cubism.edit.EditSessionCloseResult;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.edit.EditSessionState;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.edit.ParameterKeyOps;
import dev.turboism.sdk.cubism.edit.ParameterStructureOps;
import dev.turboism.sdk.cubism.edit.PartObjectOps;
import dev.turboism.sdk.cubism.edit.SelectionOps;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingTargetType;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Task-local exerciser for the external-application edit session port (spec 046, T7).
 *
 * <p>Runs inside the exact Cubism host through the public SDK surface only —
 * {@code PluginContext.cubism().edit()} sessions, the typed operation families, and the
 * {@code history()} read/move surface — and writes step-level evidence to
 * {@code state/edit-host-validation-result.properties}. Steps are numbered S1..S6; a single
 * step failure never aborts the matrix, and every opened session is cancelled (or committed
 * then undone) so the fixture returns to its pre-run history position.
 *
 * <p>Never part of the production preview bundle or product build.</p>
 */
public final class EditHostValidationPlugin implements TurboismPlugin {

    private static final String FLAG = "exerciser.flag";
    private static final String PLUGIN_ID = "dev.turboism.validation.edit-host";
    private static final String RESULT_FILE = "edit-host-validation-result.properties";
    private static final String MODE_PROPERTY = "turboism.edit.validation.mode";
    private static final long FLAG_TIMEOUT_MILLIS = 240_000L;
    private static final long DOCUMENT_AWAIT_MAX_MILLIS = 240_000L;
    private static final long EDT_TIMEOUT_MILLIS = 30_000L;
    private static final long STATE_AWAIT_MILLIS = 20_000L;
    private static final long SILENT_REVEAL_AWAIT_MILLIS = 20_000L;
    private static final String STATUS_DIALOG_TITLE = "Cubism Edit Session";
    private static final String INVISIBLE_MODAL_TITLE = "Invisible Modal Dialog";
    private static final String PROBE_NAME_SUFFIX = " (edit-probe)";
    private static final String PROBE_PART_NAME = "edit-probe part";

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;
    private final List<String> details = new ArrayList<>();
    private final List<String> observations = new ArrayList<>();
    private volatile boolean anyFail = false;

    private DocumentId documentId;
    private String documentName = "unknown";
    private String hostVersion = "unknown";
    private String mode = "matrix";

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenFlagged, "edit-host-validation-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
        logger.info("EDIT_PROBE_READY stateDir=" + stateDir);
    }

    @Override
    public void enable() {
        logger.info("EDIT_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("EDIT_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("EDIT_PROBE_SHUTDOWN");
    }

    private void runWhenFlagged() {
        final Path flag = stateDir.resolve(FLAG);
        final long deadline = System.currentTimeMillis() + FLAG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(flag)) {
                runMatrix();
                return;
            }
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warn("EDIT_PROBE_FLAG_TIMEOUT flag=" + flag);
        record("meta.flag", "fail",
            "exerciser flag not seen within " + FLAG_TIMEOUT_MILLIS + " ms: " + flag);
        writeResultFile(0L);
        Runtime.getRuntime().halt(2);
    }

    private void runMatrix() {
        final long startedNanos = System.nanoTime();
        mode = System.getProperty(MODE_PROPERTY, "matrix").strip();
        hostVersion = System.getProperty("turboism.validation.hostVersion", "unknown");
        try {
            final DocumentSnapshot document = awaitActiveModelDocument();
            documentId = new DocumentId(document.documentId());
            documentName = document.name();
            record("meta.document", "ok",
                document.documentId() + " name=" + document.name());
        } catch (Exception failure) {
            record("meta.document", "fail", singleLine(failure));
        }
        try {
            switch (mode) {
                case "matrix" -> {
                    stepLock();
                    stepCancelRestore();
                    stepCommit();
                    stepSemantics();
                    stepGetObject();
                    stepFailClosed();
                }
                case "lock" -> stepLock();
                case "cancel" -> {
                    stepCancelRestore();
                    stepCommit();
                }
                case "semantics" -> {
                    stepSemantics();
                    stepFailClosed();
                }
                case "getobject" -> stepGetObject();
                default -> record("meta.mode", "fail", "unsupported mode " + mode);
            }
        } catch (Exception failure) {
            record("matrix.unexpectedFailure", "fail", singleLine(failure));
            logger.error("EDIT_MATRIX_FAILED " + singleLine(failure), failure);
        }
        verifyFixtureRestored();
        final String terminal = anyFail ? "FAIL" : "PASS";
        writeResultFile(startedNanos);
        logger.info("EDIT_HOST_PROBE_RESULT status=" + terminal
            + " mode=" + mode
            + " hostVersion=" + hostVersion
            + " details=" + details.size()
            + " durationMillis=" + ((System.nanoTime() - startedNanos) / 1_000_000L));
        try {
            Thread.sleep(3_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(0);
    }

    // ------------------------------------------------------------------
    // S1 — session lock (US1)
    // ------------------------------------------------------------------

    private void stepLock() {
        // Non-silent session: status dialog visible immediately, main window disabled.
        EditSession session = null;
        try {
            final boolean approved = context.cubism().edit().isEditApproved(context);
            record("S1.approved", approved ? "ok" : "fail", "isEditApproved=" + approved);
            if (!approved) {
                record("S1.nonSilent.open", "blocked",
                    "edit session not approved on this host");
                return;
            }
            session = context.cubism().edit().open(
                context, documentId, EditSessionOptions.defaults());
            record("S1.nonSilent.open", session.isOpen() ? "ok" : "fail",
                "state=" + session.state());
            final EditSession open = session;
            runStepOp("S1.nonSilent.logChannel", () -> {
                open.log("edit-probe S1");
                open.progress(0.5d);
                return true;
            });
            assertWindowLock("S1.nonSilent", true);
            final EditSessionCloseResult result = open.cancel();
            record("S1.nonSilent.cancel",
                result.outcome() == EditSessionCloseOutcome.CANCELLED ? "ok" : "fail",
                "outcome=" + result.outcome()
                    + " source=" + result.cancelSource().map(Enum::name).orElse("-")
                    + " diagnostic=" + result.diagnosticId().orElse("-"));
            session = null;
            assertWindowReleased("S1.nonSilent");
        } catch (Exception failure) {
            record("S1.nonSilent.exception", "fail", singleLine(failure));
        } finally {
            cancelQuietly(session, "S1.nonSilent");
        }

        // Silent session: the invisible modal engages at once and the status dialog stays
        // hidden until the official 10s pulse timeout reveals it while the session is open.
        EditSession silentSession = null;
        try {
            silentSession = context.cubism().edit().open(
                context, documentId, EditSessionOptions.silentDialog());
            final EditSession open = silentSession;
            record("S1.silent.open", open.isOpen() ? "ok" : "fail", "state=" + open.state());
            final boolean statusInitiallyVisible =
                onEdt(() -> statusDialog() != null && statusDialog().isShowing());
            record("S1.silent.statusHiddenInitially",
                statusInitiallyVisible ? "fail" : "ok",
                "statusDialogShowing=" + statusInitiallyVisible);
            final String modal = onEdt(() -> {
                final JDialog dialog = invisibleModalDialog();
                return dialog == null
                    ? "absent"
                    : "visible=" + dialog.isVisible() + " showing=" + dialog.isShowing()
                        + " opacity=" + dialog.getOpacity();
            });
            observe("S1.silent.invisibleModal", modal);
            final boolean windowDisabled = onEdt(() -> {
                final Frame frame = mainWindow();
                return frame != null && !frame.isEnabled();
            });
            record("S1.silent.mainWindowDisabled", windowDisabled ? "ok" : "fail",
                "mainWindowDisabled=" + windowDisabled);
            // Keep the session open past the 10s pulse timeout; poll for the reveal.
            final long pollStart = System.currentTimeMillis();
            boolean revealed = false;
            long revealAfterMs = -1L;
            while (System.currentTimeMillis() - pollStart < SILENT_REVEAL_AWAIT_MILLIS) {
                final boolean visible =
                    onEdt(() -> statusDialog() != null && statusDialog().isShowing());
                if (visible) {
                    revealed = true;
                    revealAfterMs = System.currentTimeMillis() - pollStart;
                    break;
                }
                Thread.sleep(250L);
            }
            record("S1.silent.timeoutReveal", revealed ? "ok" : "fail",
                "statusDialogShowing=" + revealed + " afterMs=" + revealAfterMs
                    + " (pulse timeout 10000ms)");
            final EditSessionCloseResult result = open.cancel();
            record("S1.silent.cancel",
                result.outcome() == EditSessionCloseOutcome.CANCELLED ? "ok" : "fail",
                "outcome=" + result.outcome());
            silentSession = null;
            assertWindowReleased("S1.silent");
        } catch (Exception failure) {
            record("S1.silent.exception", "fail", singleLine(failure));
        } finally {
            cancelQuietly(silentSession, "S1.silent");
        }
    }

    private void assertWindowLock(final String prefix, final boolean expectDialog)
        throws Exception {
        final Map<String, String> state = onEdt(() -> {
            final Map<String, String> snapshot = new LinkedHashMap<>();
            final Frame frame = mainWindow();
            snapshot.put("mainWindow", frame == null ? "absent" : frame.getTitle());
            snapshot.put("mainEnabled",
                frame == null ? "?" : String.valueOf(frame.isEnabled()));
            final JDialog dialog = statusDialog();
            snapshot.put("statusDialog",
                dialog == null ? "absent" : "showing=" + dialog.isShowing());
            return snapshot;
        });
        final boolean disabled = "false".equals(state.get("mainEnabled"));
        record(prefix + ".mainWindowDisabled", disabled ? "ok" : "fail",
            "mainWindow=" + state.get("mainWindow")
                + " enabled=" + state.get("mainEnabled"));
        final boolean visible = state.get("statusDialog") != null
            && state.get("statusDialog").contains("showing=true");
        record(prefix + ".dialogVisible", visible == expectDialog ? "ok" : "fail",
            "statusDialog=" + state.get("statusDialog"));
    }

    private void assertWindowReleased(final String prefix) throws Exception {
        // Lock release happens on the EDT after the close work returns; poll briefly
        // so a still-disposing dialog does not flake the assertion.
        awaitTrue(() -> {
            try {
                return onEdt(() -> {
                    final Frame frame = mainWindow();
                    final JDialog dialog = statusDialog();
                    return (frame == null || frame.isEnabled())
                        && (dialog == null || !dialog.isShowing());
                });
            } catch (Exception failure) {
                return false;
            }
        }, STATE_AWAIT_MILLIS);
        final Map<String, String> state = onEdt(() -> {
            final Map<String, String> snapshot = new LinkedHashMap<>();
            final Frame frame = mainWindow();
            snapshot.put("mainEnabled",
                frame == null ? "?" : String.valueOf(frame.isEnabled()));
            final JDialog dialog = statusDialog();
            snapshot.put("statusDialog",
                dialog == null ? "gone" : "present showing=" + dialog.isShowing());
            return snapshot;
        });
        final boolean enabled = "true".equals(state.get("mainEnabled"));
        final boolean gone = state.get("statusDialog") != null
            && (state.get("statusDialog").equals("gone")
                || state.get("statusDialog").contains("showing=false"));
        record(prefix + ".lockReleased", enabled && gone ? "ok" : "fail",
            "mainEnabled=" + state.get("mainEnabled")
                + " statusDialog=" + state.get("statusDialog"));
    }

    // ------------------------------------------------------------------
    // S2 — cancellation recovery (US2)
    // ------------------------------------------------------------------

    private void stepCancelRestore() {
        EditSession session = null;
        final AtomicBoolean listenerFired = new AtomicBoolean();
        final AtomicReference<CancelSource> listenerSource = new AtomicReference<>();
        try {
            final HistorySnapshot before = historySnapshot();
            observe("S2.historyBefore", describeHistory(before));
            if (before.availability() != HistorySnapshot.Availability.AVAILABLE) {
                record("S2.open", "blocked", "history unavailable: " + before.availability());
                return;
            }
            final ModelBaseline baseline = captureBaseline();
            if (baseline == null) {
                record("S2.open", "blocked", "no editable parameter or keyed object found");
                return;
            }
            observe("S2.baseline", baseline);
            session = context.cubism().edit().open(
                context,
                documentId,
                EditSessionOptions.defaults().withUndoCancelListener(
                    (cancelled, source) -> {
                        listenerFired.set(true);
                        listenerSource.set(source);
                    }));
            record("S2.open", "ok", "state=" + session.state());
            final EditSession open = session;

            // One structure read plus three mutating operations across distinct families;
            // each is recorded independently so an unverified capability degrades to a
            // typed result instead of failing the whole step.
            runStepOp("S2.op.structureRead", () -> {
                final EditParameterGroupNode root =
                    open.parameterStructure().parameterStructure();
                observe("S2.op.structureRead.root",
                    "name=" + root.name() + " children=" + root.children().size());
                return true;
            });
            final String renamed = baseline.parameterName() + PROBE_NAME_SUFFIX;
            runStepOp("S2.op.rename", () -> open.parameterStructure().editParameter(
                new ParameterStructureOps.EditParameter(
                    baseline.parameterId(),
                    Optional.empty(),
                    Optional.of(renamed),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())));
            final double keyValue = unusedKeyValue(open, baseline);
            runStepOp("S2.op.addKey", () -> open.parameterKeys().addParameterKey(
                new ParameterKeyOps.AddParameterKey(
                    baseline.objectReference(), baseline.parameterId(), keyValue)));
            runStepOp("S2.op.addPart", () -> open.partObjects().addPart(
                new PartObjectOps.AddPart(Optional.of(PROBE_PART_NAME), Optional.empty())));
            observe("S2.historyDuringSession", describeHistory(historySnapshot()));

            // Preferred cancellation path: the visible status dialog's Cancel button —
            // a USER-source cancel that must deliver the NotifyUndoCancel-equivalent
            // listener event. Falls back to the plugin cancel when the dialog is absent.
            final boolean clicked = onEdt(() -> {
                final JDialog dialog = statusDialog();
                if (dialog == null) {
                    return false;
                }
                final JButton cancel = findButton(dialog.getContentPane(), "Cancel");
                if (cancel == null) {
                    return false;
                }
                cancel.doClick();
                return true;
            });
            observe("S2.cancelPath", clicked ? "status-dialog-cancel" : "sdk-cancel");
            if (!clicked) {
                open.cancel();
            }
            final boolean cancelled = awaitTrue(
                () -> stateOf(open) == EditSessionState.CANCELLED, STATE_AWAIT_MILLIS);
            record("S2.stateCancelled", cancelled ? "ok" : "fail", "state=" + stateOf(open));
            if (clicked) {
                record("S2.listenerNotified", listenerFired.get() ? "ok" : "fail",
                    "source=" + listenerSource.get());
                record("S2.listenerSource",
                    listenerSource.get() == CancelSource.USER ? "ok" : "fail",
                    "source=" + listenerSource.get());
            } else {
                // A plugin-originated cancel must not raise the undo-cancel listener;
                // observing one would contradict the documented semantics.
                record("S2.listenerNotified", listenerFired.get() ? "fail" : "skipped",
                    "plugin-source cancel; listener notification not expected;"
                        + " fired=" + listenerFired.get());
            }

            final HistorySnapshot after = historySnapshot();
            final boolean restored =
                after.availability() == HistorySnapshot.Availability.AVAILABLE
                    && after.position() == before.position()
                    && after.entries().size() == before.entries().size();
            observe("S2.historyAfter", describeHistory(after));
            observe("S2.historyEquals", String.valueOf(after.equals(before)));
            record("S2.historyRestored", restored ? "ok" : "fail",
                "position " + before.position() + "->" + after.position()
                    + " entries " + before.entries().size() + "->" + after.entries().size()
                    + " equals=" + after.equals(before));

            verifyBaseline(baseline, "S2");

            try {
                open.parameterStructure().parameterStructure();
                record("S2.opAfterCancelled", "fail", "operation admitted after cancel");
            } catch (EditCancelledException cancelledException) {
                record("S2.opAfterCancelled", "ok",
                    "EditCancelledException code=" + cancelledException.code()
                        + " source=" + cancelledException.source());
            } catch (EditSessionException other) {
                record("S2.opAfterCancelled", "fail",
                    "unexpected typed failure " + other.getClass().getSimpleName()
                        + " code=" + other.code());
            }
            try {
                open.cancel();
                record("S2.doubleCancel", "fail", "second cancel admitted");
            } catch (EditCancelledException cancelledException) {
                record("S2.doubleCancel", "ok", "code=" + cancelledException.code());
            } catch (EditSessionException other) {
                record("S2.doubleCancel", "fail",
                    other.getClass().getSimpleName() + " code=" + other.code());
            }
            session = null;
        } catch (Exception failure) {
            record("S2.exception", "fail", singleLine(failure));
        } finally {
            cancelQuietly(session, "S2");
        }
    }

    // ------------------------------------------------------------------
    // S3 — commit semantics
    // ------------------------------------------------------------------

    private void stepCommit() {
        EditSession session = null;
        try {
            final HistorySnapshot before = historySnapshot();
            if (before.availability() != HistorySnapshot.Availability.AVAILABLE) {
                record("S3.open", "blocked", "history unavailable: " + before.availability());
                return;
            }
            final ModelBaseline baseline = captureBaseline();
            if (baseline == null) {
                record("S3.open", "blocked", "no editable parameter discovered");
                return;
            }
            session = context.cubism().edit().open(
                context, documentId, EditSessionOptions.defaults());
            record("S3.open", "ok", "state=" + session.state());
            final EditSession open = session;
            final String renamed = baseline.parameterName() + PROBE_NAME_SUFFIX;
            runStepOp("S3.op.rename", () -> open.parameterStructure().editParameter(
                new ParameterStructureOps.EditParameter(
                    baseline.parameterId(),
                    Optional.empty(),
                    Optional.of(renamed),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())));
            final EditSessionCloseResult result = open.close();
            record("S3.closeCommitted",
                result.outcome() == EditSessionCloseOutcome.COMMITTED ? "ok" : "fail",
                "outcome=" + result.outcome()
                    + " diagnostic=" + result.diagnosticId().orElse("-"));
            session = null;

            final HistorySnapshot committed = historySnapshot();
            final int positionDelta = committed.position() - before.position();
            final int entriesDelta = committed.entries().size() - before.entries().size();
            observe("S3.historyCommitted", describeHistory(committed));
            observe("S3.committedEntry",
                positionDelta > 0 && committed.position() - 1 < committed.entries().size()
                    ? committed.entries().get(committed.position() - 1).label()
                    : "-");
            record("S3.historyPlusOne",
                positionDelta == 1 && entriesDelta == 1 ? "ok" : "fail",
                "position " + before.position() + "->" + committed.position()
                    + " entries " + before.entries().size() + "->" + committed.entries().size());

            final String committedName = parameterNameOf(baseline.parameterId());
            record("S3.readbackCommitted", renamed.equals(committedName) ? "ok" : "fail",
                "name=" + committedName);

            final HistoryMoveResult undo = context.cubism().history().undo(1);
            record("S3.undoMoved",
                undo.outcome() == HistoryMoveResult.Outcome.MOVED ? "ok" : "fail",
                "outcome=" + undo.outcome()
                    + " diagnostic=" + undo.diagnosticId().orElse("-"));
            final String restoredName = parameterNameOf(baseline.parameterId());
            record("S3.undoRestored",
                baseline.parameterName().equals(restoredName) ? "ok" : "fail",
                "name=" + restoredName);
            final HistorySnapshot restored = historySnapshot();
            record("S3.positionRestored",
                restored.position() == before.position() ? "ok" : "fail",
                "position " + before.position() + "->" + restored.position());
        } catch (Exception failure) {
            record("S3.exception", "fail", singleLine(failure));
        } finally {
            cancelQuietly(session, "S3");
        }
    }

    // ------------------------------------------------------------------
    // S4 — semantic direct evidence (observed, not pre-judged)
    // ------------------------------------------------------------------

    private void stepSemantics() {
        // A second concurrent session must be rejected while one is active.
        EditSession first = null;
        try {
            first = context.cubism().edit().open(
                context, documentId, EditSessionOptions.defaults());
            EditSession second = null;
            try {
                second = context.cubism().edit().open(
                    context, documentId, EditSessionOptions.defaults());
                record("S4.secondSessionRejected", "fail",
                    "second session admitted isOpen=" + second.isOpen());
            } catch (EditUnavailableException unavailable) {
                record("S4.secondSessionRejected", "ok",
                    "EditUnavailableException code=" + unavailable.code());
            } catch (EditSessionException other) {
                record("S4.secondSessionRejected", "fail",
                    other.getClass().getSimpleName() + " code=" + other.code());
            } finally {
                cancelQuietly(second, "S4.secondSession.inner");
            }
        } catch (Exception failure) {
            record("S4.secondSessionRejected", "blocked", singleLine(failure));
        } finally {
            cancelQuietly(first, "S4.secondSession");
        }

        stepSelectionAdditive();
        stepMoveKeyCollision(false);
        stepMoveKeyCollision(true);
        stepDeleteKeyLoose(true);
        stepDeleteKeyLoose(false);
    }

    private void stepSelectionAdditive() {
        EditSession session = null;
        try {
            session = context.cubism().edit().open(
                context, documentId, EditSessionOptions.defaults());
            final EditSession open = session;
            final List<ModelObjectReference> candidates = objectRefsOfKinds(
                open, EnumSet.of(EditObjectKind.ART_MESH, EditObjectKind.WARP_DEFORMER));
            if (candidates.size() < 2) {
                record("S4.selectionAdditive", "skipped",
                    "fixture exposes fewer than two selectable objects: " + candidates.size());
                return;
            }
            final ModelObjectId first = new ModelObjectId(candidates.get(0).id());
            final ModelObjectId second = new ModelObjectId(candidates.get(1).id());
            observe("S4.selection.initial", describeSelection(open));
            try {
                open.selection().clearSelectedObjects();
            } catch (Exception failure) {
                observe("S4.selection.clear", singleLine(failure));
            }
            try {
                final boolean addA = open.selection().addSelectedObjects(
                    new SelectionOps.AddSelectedObjects(List.of(first)));
                observe("S4.selection.afterA",
                    "add=" + addA + " " + describeSelection(open));
                final boolean addB = open.selection().addSelectedObjects(
                    new SelectionOps.AddSelectedObjects(List.of(second)));
                final List<ModelObjectId> readback = open.selection().selectedObjects();
                final boolean additive =
                    readback.contains(first) && readback.contains(second);
                record("S4.selectionAdditive", "ok",
                    "observed addB=" + addB + " readback=" + describeIds(readback)
                        + " additive=" + additive);
            } catch (EditSessionException failure) {
                record("S4.selectionAdditive", "ok",
                    "typed rejection " + typedFailure(failure));
            } catch (RuntimeException failure) {
                record("S4.selectionAdditive", "ok",
                    "runtime rejection " + singleLine(failure));
            }
        } catch (Exception failure) {
            record("S4.selectionAdditive", "fail", singleLine(failure));
        } finally {
            cancelQuietly(session, "S4.selection");
        }
    }

    private void stepMoveKeyCollision(final boolean forceOverwrite) {
        final String stepId =
            "S4.moveKey." + (forceOverwrite ? "forceOverwrite" : "noOverwrite");
        EditSession session = null;
        try {
            session = context.cubism().edit().open(
                context, documentId, EditSessionOptions.defaults());
            final EditSession open = session;
            final CollisionTarget target = findKeyCollisionTarget(open);
            if (target == null) {
                record(stepId, "skipped", "no object/parameter pair with >=2 keys found");
                return;
            }
            final List<Double> beforeKeys = keyValuesFor(open, target);
            try {
                final boolean moved = open.parameterKeys().moveParameterKey(
                    new ParameterKeyOps.MoveParameterKey(
                        Optional.of(target.object()),
                        Optional.of(target.parameter()),
                        target.fromValue(),
                        target.toValue(),
                        true,
                        forceOverwrite));
                final List<Double> after = keyValuesFor(open, target);
                record(stepId, "ok",
                    "observed moved=" + moved + " forceOverwrite=" + forceOverwrite
                        + " from=" + target.fromValue() + " to=" + target.toValue()
                        + " keys " + beforeKeys + " -> " + after);
            } catch (EditSessionException failure) {
                record(stepId, "ok", "typed rejection " + typedFailure(failure));
            } catch (RuntimeException failure) {
                record(stepId, "ok", "runtime rejection " + singleLine(failure));
            }
        } catch (Exception failure) {
            record(stepId, "fail", singleLine(failure));
        } finally {
            cancelQuietly(session, stepId);
        }
    }

    private void stepDeleteKeyLoose(final boolean byObject) {
        final String stepId =
            byObject ? "S4.deleteKey.looseObject" : "S4.deleteKey.looseParameter";
        EditSession session = null;
        try {
            session = context.cubism().edit().open(
                context, documentId, EditSessionOptions.defaults());
            final EditSession open = session;
            final CollisionTarget target = findKeyCollisionTarget(open);
            if (target == null) {
                record(stepId, "skipped", "no keyed object/parameter pair found");
                return;
            }
            final List<Double> beforeKeys = keyValuesFor(open, target);
            try {
                final boolean deleted = open.parameterKeys().deleteParameterKey(
                    byObject
                        ? new ParameterKeyOps.DeleteParameterKey(
                            Optional.of(target.object()), Optional.empty(),
                            Optional.empty(), false)
                        : new ParameterKeyOps.DeleteParameterKey(
                            Optional.empty(), Optional.of(target.parameter()),
                            Optional.empty(), false));
                final List<Double> afterKeys = keyValuesFor(open, target);
                record(stepId, "ok",
                    "observed deleted=" + deleted
                        + " keys " + beforeKeys.size() + "->" + afterKeys.size()
                        + " " + beforeKeys + " -> " + afterKeys);
            } catch (EditSessionException failure) {
                record(stepId, "ok", "typed rejection " + typedFailure(failure));
            } catch (RuntimeException failure) {
                record(stepId, "ok", "runtime rejection " + singleLine(failure));
            }
        } catch (Exception failure) {
            record(stepId, "fail", singleLine(failure));
        } finally {
            cancelQuietly(session, stepId);
        }
    }

    // ------------------------------------------------------------------
    // S5 — GetObject read matrix
    // ------------------------------------------------------------------

    private void stepGetObject() {
        EditSession session = null;
        try {
            session = context.cubism().edit().open(
                context, documentId, EditSessionOptions.defaults());
            final EditSession open = session;
            final Map<EditObjectKind, List<String>> byKind =
                new EnumMap<>(EditObjectKind.class);
            for (final EditObjectKind kind : EditObjectKind.values()) {
                byKind.put(kind, new ArrayList<>());
            }
            try {
                collectObjectKinds(open.partObjects().partStructure(), byKind);
                record("S5.partStructure", "ok", describeKindCounts(byKind));
            } catch (Exception failure) {
                record("S5.partStructure", "fail", singleLine(failure));
            }
            try {
                collectObjectKinds(open.deformers().deformerStructure(), byKind);
                record("S5.deformerStructure", "ok", describeKindCounts(byKind));
            } catch (Exception failure) {
                record("S5.deformerStructure", "fail", singleLine(failure));
            }

            // On 5.2.03 the extended part/art-mesh readers are expected to fail closed;
            // on 5.3.02+ they must return typed payloads.
            final boolean extendedReaders = !"5203".equals(hostVersion);
            readObjectKind(open, byKind, EditObjectKind.PART, ModelObjectKind.PART,
                extendedReaders, "S5.getObject.PART");
            readObjectKind(open, byKind, EditObjectKind.ART_MESH, ModelObjectKind.ART_MESH,
                extendedReaders, "S5.getObject.ART_MESH");
            readObjectKind(open, byKind, EditObjectKind.WARP_DEFORMER,
                ModelObjectKind.WARP_DEFORMER, true, "S5.getObject.WARP_DEFORMER");
            readObjectKind(open, byKind, EditObjectKind.ROTATION_DEFORMER,
                ModelObjectKind.ROTATION_DEFORMER, true, "S5.getObject.ROTATION_DEFORMER");
            // Glue ids resolve through the glue enumeration regardless of the declared
            // ModelObjectKind (the enum cannot name glue); PART is a placeholder here.
            readObjectKind(open, byKind, EditObjectKind.GLUE, ModelObjectKind.PART,
                true, "S5.getObject.GLUE");
            readArtPath(open, byKind);

            // The official Parameters[] keyform-condition gate is fail-closed.
            final ModelObjectReference anyRef = firstReference(byKind);
            if (anyRef == null) {
                record("S5.getObject.conditionsRejected", "skipped", "no readable object");
            } else {
                try {
                    open.partObjects().object(new PartObjectOps.GetObject(
                        anyRef,
                        List.of(new EditParameterKeyCondition(
                            Optional.empty(), Optional.of(0.0d)))));
                    record("S5.getObject.conditionsRejected", "fail",
                        "conditioned GetObject admitted");
                } catch (EditSessionException failure) {
                    record("S5.getObject.conditionsRejected", "ok", typedFailure(failure));
                } catch (RuntimeException failure) {
                    record("S5.getObject.conditionsRejected", "ok",
                        "rejected " + failure.getClass().getSimpleName()
                            + ": " + singleLine(failure.getMessage()));
                }
            }
        } catch (Exception failure) {
            record("S5.exception", "fail", singleLine(failure));
        } finally {
            cancelQuietly(session, "S5");
        }
    }

    private void readObjectKind(
        final EditSession session,
        final Map<EditObjectKind, List<String>> byKind,
        final EditObjectKind kind,
        final ModelObjectKind declared,
        final boolean expectData,
        final String stepId
    ) {
        final List<String> ids = byKind.getOrDefault(kind, List.of());
        if (ids.isEmpty()) {
            record(stepId, "skipped", "fixture has no " + kind + " object");
            return;
        }
        final String id = ids.get(0);
        try {
            final EditObjectSnapshot snapshot = session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(declared, id), List.of()));
            observe(stepId + ".data",
                snapshot.data().getClass().getSimpleName()
                    + " kind=" + snapshot.data().kind()
                    + " object=" + snapshot.object().value());
            record(stepId, expectData ? "ok" : "fail",
                "returned " + snapshot.data().getClass().getSimpleName()
                    + " expectData=" + expectData);
        } catch (EditUnavailableException unavailable) {
            record(stepId, expectData ? "fail" : "ok",
                "typed unavailable code=" + unavailable.code());
        } catch (EditSessionException failure) {
            record(stepId, expectData ? "fail" : "ok",
                "typed rejection " + typedFailure(failure));
        } catch (RuntimeException failure) {
            record(stepId, expectData ? "fail" : "ok",
                "runtime rejection " + failure.getClass().getSimpleName()
                    + ": " + singleLine(failure.getMessage()));
        }
    }

    private void readArtPath(
        final EditSession session,
        final Map<EditObjectKind, List<String>> byKind
    ) {
        final List<String> ids = byKind.getOrDefault(EditObjectKind.ART_PATH, List.of());
        if (ids.isEmpty()) {
            record("S5.getObject.ART_PATH", "skipped", "fixture has no ART_PATH node");
            return;
        }
        try {
            final EditObjectSnapshot snapshot = session.partObjects().object(
                new PartObjectOps.GetObject(
                    new ModelObjectReference(ModelObjectKind.ART_MESH, ids.get(0)),
                    List.of()));
            record("S5.getObject.ART_PATH", "fail",
                "art path read returned " + snapshot.data().getClass().getSimpleName()
                    + " — the official API defines no ArtPath payload");
        } catch (EditSessionException failure) {
            record("S5.getObject.ART_PATH", "ok", "fail-closed " + typedFailure(failure));
        } catch (RuntimeException failure) {
            record("S5.getObject.ART_PATH", "ok",
                "fail-closed " + failure.getClass().getSimpleName()
                    + ": " + singleLine(failure.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // S6 — fail-closed behavior (US4)
    // ------------------------------------------------------------------

    private void stepFailClosed() {
        // Operations on the unavailable session must throw typed rejections.
        try {
            EditSession.unavailable().parameterStructure().parameterStructure();
            record("S6.unavailableSessionOp", "fail",
                "op admitted on unavailable session");
        } catch (EditUnavailableException unavailable) {
            record("S6.unavailableSessionOp", "ok", "code=" + unavailable.code());
        } catch (EditSessionException failure) {
            record("S6.unavailableSessionOp", "ok",
                "typed rejection " + typedFailure(failure));
        } catch (RuntimeException failure) {
            record("S6.unavailableSessionOp", "fail",
                "unexpected " + failure.getClass().getSimpleName());
        }
        try {
            EditSession.unavailable().cancel();
            record("S6.unavailableCancel", "fail",
                "cancel admitted on unavailable session");
        } catch (EditUnavailableException unavailable) {
            record("S6.unavailableCancel", "ok", "code=" + unavailable.code());
        } catch (EditSessionException failure) {
            record("S6.unavailableCancel", "ok",
                "typed rejection " + typedFailure(failure));
        } catch (RuntimeException failure) {
            record("S6.unavailableCancel", "fail",
                "unexpected " + failure.getClass().getSimpleName());
        }
        EditSession stray = null;
        try {
            stray = context.cubism().edit().open(
                context, new DocumentId("edit-probe-no-such-document"),
                EditSessionOptions.defaults());
            record("S6.openUnknownDocument", "fail",
                "open admitted for absent document isOpen=" + stray.isOpen());
        } catch (EditUnavailableException unavailable) {
            record("S6.openUnknownDocument", "ok", "code=" + unavailable.code());
        } catch (EditSessionException failure) {
            record("S6.openUnknownDocument", "ok",
                "typed rejection " + typedFailure(failure));
        } finally {
            cancelQuietly(stray, "S6.openUnknownDocument");
        }

        // A committed session's operations must throw typed unavailability; an empty
        // commit may still register a history entry, so undo if the position advanced.
        EditSession session = null;
        HistorySnapshot before = null;
        try {
            before = historySnapshot();
            session = context.cubism().edit().open(
                context, documentId, EditSessionOptions.defaults());
            final EditSession open = session;
            final EditSessionCloseResult closed = open.close();
            observe("S6.closeOutcome", closed.outcome().name());
            try {
                open.partObjects().partStructure();
                record("S6.closedSessionOp", "fail", "op admitted after close");
            } catch (EditUnavailableException unavailable) {
                record("S6.closedSessionOp", "ok", "code=" + unavailable.code());
            } catch (EditSessionException failure) {
                record("S6.closedSessionOp", "fail",
                    "unexpected " + typedFailure(failure));
            }
        } catch (Exception failure) {
            record("S6.closedSessionOp", "fail", singleLine(failure));
        } finally {
            cancelQuietly(session, "S6.closedSession");
            restoreIfPositionAdvanced(before, "S6");
        }

        // Capability-gate evidence: reparenting an object under a part is deliberately
        // unverified; the request must be rejected before any mutation lands.
        session = null;
        before = null;
        try {
            before = historySnapshot();
            session = context.cubism().edit().open(
                context, documentId, EditSessionOptions.defaults());
            final EditSession open = session;
            final List<ModelObjectReference> parts =
                objectRefsOfKinds(open, EnumSet.of(EditObjectKind.PART));
            final List<ModelObjectReference> meshes =
                objectRefsOfKinds(open, EnumSet.of(EditObjectKind.ART_MESH));
            if (parts.isEmpty() || meshes.isEmpty()) {
                record("S6.unverifiedReparent", "skipped",
                    "fixture lacks part or art-mesh object");
            } else {
                try {
                    open.partObjects().moveObjectOnPartsPalette(
                        new PartObjectOps.MoveObjectOnPartsPalette(
                            meshes.get(0),
                            Optional.of(new PartId(parts.get(0).id())),
                            Optional.empty(),
                            Optional.empty()));
                    record("S6.unverifiedReparent", "fail", "unverified reparent admitted");
                } catch (EditSessionException failure) {
                    record("S6.unverifiedReparent", "ok",
                        "rejected before mutation " + typedFailure(failure));
                } catch (RuntimeException failure) {
                    record("S6.unverifiedReparent", "ok",
                        "rejected " + failure.getClass().getSimpleName());
                }
            }
        } catch (Exception failure) {
            record("S6.unverifiedReparent", "fail", singleLine(failure));
        } finally {
            cancelQuietly(session, "S6.unverifiedReparent");
            restoreIfPositionAdvanced(before, "S6.reparent");
        }
    }

    // ------------------------------------------------------------------
    // Baseline capture / fixture restoration
    // ------------------------------------------------------------------

    private record ModelBaseline(
        ParameterId parameterId,
        String parameterName,
        double parameterMin,
        double parameterMax,
        ModelObjectReference objectReference,
        int keyCount,
        int partCount
    ) {
    }

    private ModelBaseline captureBaseline() throws Exception {
        return onEdt(() -> {
            final CubismModel model = context.cubism().model().active();
            final ParameterBinding binding = findBinding(model);
            if (binding == null) {
                return null;
            }
            // Pair the binding's own parameter with its target so the rename, the key
            // add, and the key-count restore check all measure the same (param, object)
            // binding row.
            final Parameter chosen = parameterById(model, binding.parameterId());
            if (chosen == null) {
                return null;
            }
            final ParameterId parameterId = chosen.id();
            return new ModelBaseline(
                parameterId,
                chosen.name().orElse(parameterId.value()),
                parameterRange(parameterId, true),
                parameterRange(parameterId, false),
                new ModelObjectReference(
                    switch (binding.target().type()) {
                        case ART_MESH -> ModelObjectKind.ART_MESH;
                        case WARP_DEFORMER -> ModelObjectKind.WARP_DEFORMER;
                        case ROTATION_DEFORMER -> ModelObjectKind.ROTATION_DEFORMER;
                    },
                    binding.target().id()),
                binding.points().size(),
                model.parts().all().size());
        });
    }

    private Parameter parameterById(final CubismModel model, final ParameterId id) {
        for (final Parameter parameter : model.parameters().all()) {
            if (parameter.id().equals(id)) {
                return parameter;
            }
        }
        return null;
    }

    private ParameterBinding findBinding(final CubismModel model) {
        for (final Parameter parameter : model.parameters().all()) {
            for (final ParameterBinding binding : parameter.getParameterBindings()) {
                if (binding.target().type() == ParameterBindingTargetType.ART_MESH
                    && !binding.points().isEmpty()) {
                    return binding;
                }
            }
        }
        for (final Parameter parameter : model.parameters().all()) {
            for (final ParameterBinding binding : parameter.getParameterBindings()) {
                if (!binding.points().isEmpty()) {
                    return binding;
                }
            }
        }
        return null;
    }

    private double parameterRange(final ParameterId id, final boolean min) {
        final Optional<ModelSnapshot> snapshot = context.cubism().activeModel();
        if (snapshot.isPresent()) {
            for (final ParameterSnapshot parameter : snapshot.orElseThrow().parameters()) {
                if (parameter.id().equals(id.value())) {
                    return min ? parameter.minValue() : parameter.maxValue();
                }
            }
        }
        return min ? 0.0d : 1.0d;
    }

    private void verifyBaseline(final ModelBaseline baseline, final String prefix) {
        try {
            final String name = parameterNameOf(baseline.parameterId());
            record(prefix + ".paramNameRestored",
                baseline.parameterName().equals(name) ? "ok" : "fail",
                "expected=" + baseline.parameterName() + " actual=" + name);
        } catch (Exception failure) {
            record(prefix + ".paramNameRestored", "fail", singleLine(failure));
        }
        try {
            final int keys = keyCountFor(baseline);
            record(prefix + ".keyCountRestored",
                keys == baseline.keyCount() ? "ok" : "fail",
                "expected=" + baseline.keyCount() + " actual=" + keys);
        } catch (Exception failure) {
            record(prefix + ".keyCountRestored", "fail", singleLine(failure));
        }
        try {
            final int parts =
                onEdt(() -> context.cubism().model().active().parts().all().size());
            record(prefix + ".partCountRestored",
                parts == baseline.partCount() ? "ok" : "fail",
                "expected=" + baseline.partCount() + " actual=" + parts);
        } catch (Exception failure) {
            record(prefix + ".partCountRestored", "fail", singleLine(failure));
        }
    }

    private int keyCountFor(final ModelBaseline baseline) throws Exception {
        return onEdt(() -> {
            final CubismModel model = context.cubism().model().active();
            for (final Parameter parameter : model.parameters().all()) {
                for (final ParameterBinding binding : parameter.getParameterBindings()) {
                    if (binding.parameterId().equals(baseline.parameterId())
                        && binding.target().id().equals(baseline.objectReference().id())) {
                        return binding.points().size();
                    }
                }
            }
            return 0;
        });
    }

    private String parameterNameOf(final ParameterId id) throws Exception {
        return onEdt(() -> {
            final Optional<ModelSnapshot> snapshot = context.cubism().activeModel();
            if (snapshot.isPresent()) {
                for (final ParameterSnapshot parameter
                    : snapshot.orElseThrow().parameters()) {
                    if (parameter.id().equals(id.value())) {
                        return parameter.name();
                    }
                }
            }
            return null;
        });
    }

    private void verifyFixtureRestored() {
        try {
            observe("cleanup.historyFinal", describeHistory(historySnapshot()));
        } catch (Exception failure) {
            record("cleanup.historyReadable", "fail", singleLine(failure));
        }
    }

    private void restoreIfPositionAdvanced(final HistorySnapshot before, final String prefix) {
        if (before == null) {
            return;
        }
        try {
            final HistorySnapshot now = historySnapshot();
            if (now.availability() == HistorySnapshot.Availability.AVAILABLE
                && now.position() > before.position()) {
                final HistoryMoveResult undo = context.cubism().history()
                    .undo(now.position() - before.position());
                record(prefix + ".cleanupUndo",
                    undo.outcome() == HistoryMoveResult.Outcome.MOVED ? "ok" : "fail",
                    "outcome=" + undo.outcome());
            }
        } catch (Exception failure) {
            record(prefix + ".cleanupUndo", "fail", singleLine(failure));
        }
    }

    private static EditSessionState stateOf(final EditSession session) {
        try {
            return session.state();
        } catch (EditSessionException failure) {
            return null;
        }
    }

    private void cancelQuietly(final EditSession session, final String prefix) {
        if (session == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                final EditSessionCloseResult result = session.cancel();
                observe(prefix + ".cleanupCancel", "outcome=" + result.outcome());
            }
        } catch (Exception failure) {
            record(prefix + ".cleanupCancel", "fail", singleLine(failure));
        }
    }

    private HistorySnapshot historySnapshot() throws Exception {
        return onEdt(() -> context.cubism().history().snapshot());
    }

    // ------------------------------------------------------------------
    // Operation helpers
    // ------------------------------------------------------------------

    private interface StepOp {
        boolean run() throws EditSessionException;
    }

    private void runStepOp(final String stepId, final StepOp op) {
        try {
            final boolean applied = op.run();
            record(stepId, applied ? "ok" : "fail", "returned=" + applied);
        } catch (EditSessionException failure) {
            record(stepId, "blocked", "typed rejection " + typedFailure(failure));
        } catch (RuntimeException failure) {
            record(stepId, "blocked",
                failure.getClass().getSimpleName()
                    + ": " + singleLine(failure.getMessage()));
        }
    }

    private double unusedKeyValue(final EditSession session, final ModelBaseline baseline)
        throws EditSessionException {
        final List<Double> keys = keyValuesFor(session, baseline);
        for (int step = 1; step <= 97; step++) {
            final double candidate = baseline.parameterMin()
                + (baseline.parameterMax() - baseline.parameterMin()) * step / 97.0d;
            final double probe = candidate;
            if (keys.stream().noneMatch(existing -> Math.abs(existing - probe) < 1.0e-6d)) {
                return candidate;
            }
        }
        return baseline.parameterMin();
    }

    private List<Double> keyValuesFor(
        final EditSession session,
        final ModelBaseline baseline
    ) throws EditSessionException {
        for (final ParameterKeyOps.ParameterKeyValues row
            : session.parameterKeys().parameterKeys(
                new ParameterKeyOps.GetParameterKeys(baseline.objectReference()))) {
            if (row.parameter().equals(baseline.parameterId())) {
                return row.keyValues();
            }
        }
        return List.of();
    }

    private record CollisionTarget(
        ModelObjectReference object,
        ParameterId parameter,
        double fromValue,
        double toValue
    ) {
    }

    private CollisionTarget findKeyCollisionTarget(final EditSession session)
        throws EditSessionException {
        for (final ModelObjectReference reference : objectRefsOfKinds(
            session,
            EnumSet.of(
                EditObjectKind.ART_MESH,
                EditObjectKind.WARP_DEFORMER,
                EditObjectKind.ROTATION_DEFORMER))) {
            final List<ParameterKeyOps.ParameterKeyValues> rows;
            try {
                rows = session.parameterKeys().parameterKeys(
                    new ParameterKeyOps.GetParameterKeys(reference));
            } catch (EditSessionException | RuntimeException failure) {
                continue;
            }
            for (final ParameterKeyOps.ParameterKeyValues row : rows) {
                if (row.keyValues().size() >= 2) {
                    return new CollisionTarget(
                        reference, row.parameter(),
                        row.keyValues().get(0), row.keyValues().get(1));
                }
            }
        }
        return null;
    }

    private List<Double> keyValuesFor(
        final EditSession session,
        final CollisionTarget target
    ) {
        try {
            for (final ParameterKeyOps.ParameterKeyValues row
                : session.parameterKeys().parameterKeys(
                    new ParameterKeyOps.GetParameterKeys(target.object()))) {
                if (row.parameter().equals(target.parameter())) {
                    return row.keyValues();
                }
            }
        } catch (EditSessionException | RuntimeException failure) {
            observe("keyValuesFor.failure", singleLine(failure));
        }
        return List.of();
    }

    private List<ModelObjectReference> objectRefsOfKinds(
        final EditSession session,
        final Set<EditObjectKind> kinds
    ) {
        final List<ModelObjectReference> references = new ArrayList<>();
        try {
            collectRefs(session.partObjects().partStructure(), kinds, references);
        } catch (EditSessionException | RuntimeException failure) {
            observe("objectRefs.parts.failure", singleLine(failure));
        }
        try {
            collectRefs(session.deformers().deformerStructure(), kinds, references);
        } catch (EditSessionException | RuntimeException failure) {
            observe("objectRefs.deformers.failure", singleLine(failure));
        }
        return references;
    }

    private void collectRefs(
        final EditObjectNode node,
        final Set<EditObjectKind> kinds,
        final List<ModelObjectReference> sink
    ) {
        final ModelObjectKind declared = switch (node.kind()) {
            case PART -> ModelObjectKind.PART;
            case ART_MESH -> ModelObjectKind.ART_MESH;
            case WARP_DEFORMER -> ModelObjectKind.WARP_DEFORMER;
            case ROTATION_DEFORMER -> ModelObjectKind.ROTATION_DEFORMER;
            default -> null;
        };
        if (declared != null && kinds.contains(node.kind())) {
            sink.add(new ModelObjectReference(declared, node.id().value()));
        }
        for (final EditObjectNode child : node.children()) {
            collectRefs(child, kinds, sink);
        }
    }

    private void collectObjectKinds(
        final EditObjectNode node,
        final Map<EditObjectKind, List<String>> byKind
    ) {
        byKind.computeIfAbsent(node.kind(), ignored -> new ArrayList<>())
            .add(node.id().value());
        for (final EditObjectNode child : node.children()) {
            collectObjectKinds(child, byKind);
        }
    }

    private String describeKindCounts(final Map<EditObjectKind, List<String>> byKind) {
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<EditObjectKind, List<String>> entry : byKind.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(entry.getKey()).append('=').append(entry.getValue().size());
            }
        }
        return text.length() == 0 ? "empty" : text.toString();
    }

    private ModelObjectReference firstReference(
        final Map<EditObjectKind, List<String>> byKind) {
        final List<ModelObjectKind> order = List.of(
            ModelObjectKind.PART, ModelObjectKind.ART_MESH,
            ModelObjectKind.WARP_DEFORMER, ModelObjectKind.ROTATION_DEFORMER);
        for (final ModelObjectKind kind : order) {
            final EditObjectKind objectKind = switch (kind) {
                case PART -> EditObjectKind.PART;
                case ART_MESH -> EditObjectKind.ART_MESH;
                case WARP_DEFORMER -> EditObjectKind.WARP_DEFORMER;
                case ROTATION_DEFORMER -> EditObjectKind.ROTATION_DEFORMER;
            };
            final List<String> ids = byKind.getOrDefault(objectKind, List.of());
            if (!ids.isEmpty()) {
                return new ModelObjectReference(kind, ids.get(0));
            }
        }
        final List<String> glue = byKind.getOrDefault(EditObjectKind.GLUE, List.of());
        if (!glue.isEmpty()) {
            return new ModelObjectReference(ModelObjectKind.PART, glue.get(0));
        }
        return null;
    }

    private String describeSelection(final EditSession session) {
        try {
            return describeIds(session.selection().selectedObjects());
        } catch (Exception failure) {
            return "unavailable:" + singleLine(failure);
        }
    }

    private static String describeIds(final List<ModelObjectId> ids) {
        final StringBuilder text = new StringBuilder("[");
        for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
                text.append(',');
            }
            text.append(ids.get(index).value());
        }
        return text.append(']').toString();
    }

    // ------------------------------------------------------------------
    // Host windows
    // ------------------------------------------------------------------

    private Frame mainWindow() {
        Frame fallback = null;
        final String fixtureName =
            System.getProperty("turboism.validation.fixtureName", "");
        for (final Frame frame : Frame.getFrames()) {
            if (!frame.isVisible()) {
                continue;
            }
            final String title = frame.getTitle();
            if (title == null) {
                continue;
            }
            if (!fixtureName.isEmpty() && title.contains(fixtureName)) {
                return frame;
            }
            if (title.contains(".cmo3")) {
                return frame;
            }
            if (title.contains("Cubism") && fallback == null) {
                fallback = frame;
            }
        }
        return fallback;
    }

    private JDialog statusDialog() {
        for (final Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog
                && STATUS_DIALOG_TITLE.equals(dialog.getTitle())) {
                return dialog;
            }
        }
        return null;
    }

    private JDialog invisibleModalDialog() {
        for (final Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog
                && INVISIBLE_MODAL_TITLE.equals(dialog.getTitle())) {
                return dialog;
            }
        }
        return null;
    }

    private JButton findButton(final Container root, final String text) {
        for (final Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container container) {
                final JButton found = findButton(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Await helpers / EDT bridge
    // ------------------------------------------------------------------

    private DocumentSnapshot awaitActiveModelDocument() throws Exception {
        final long deadline = System.currentTimeMillis() + DOCUMENT_AWAIT_MAX_MILLIS;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                final Optional<DocumentSnapshot> document =
                    onEdt(() -> context.cubism().activeModelDocument());
                if (document.isPresent()) {
                    return document.orElseThrow();
                }
            } catch (Exception failure) {
                lastFailure = failure;
            }
            Thread.sleep(1_000L);
        }
        throw new IllegalStateException(
            "No active model document within " + DOCUMENT_AWAIT_MAX_MILLIS + " ms",
            lastFailure);
    }

    private boolean awaitTrue(final BooleanSupplier condition, final long timeoutMs)
        throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100L);
        }
        return condition.getAsBoolean();
    }

    private static void invokeOnEdt(final Runnable operation) throws Exception {
        Objects.requireNonNull(operation, "operation");
        if (SwingUtilities.isEventDispatchThread()) {
            operation.run();
            return;
        }
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> {
            try {
                operation.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(EDT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                    "EDT operation timed out after " + EDT_TIMEOUT_MILLIS + " ms");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        final Throwable throwable = failure.get();
        if (throwable != null) {
            throw new InvocationTargetException(throwable);
        }
    }

    private <T> T onEdt(final Callable<T> operation) throws Exception {
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.call();
        }
        invokeOnEdt(() -> {
            try {
                result.set(operation.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        final Throwable throwable = failure.get();
        if (throwable != null) {
            if (throwable instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (throwable instanceof Exception checked) {
                throw checked;
            }
            throw new IllegalStateException(throwable);
        }
        return result.get();
    }

    // ------------------------------------------------------------------
    // Evidence
    // ------------------------------------------------------------------

    private void record(final String step, final String status, final String detail) {
        final String normalized = switch (status) {
            case "ok" -> "ok";
            case "skipped" -> "skipped";
            default -> "fail".equals(status) ? "fail" : "blocked";
        };
        if ("fail".equals(normalized) || "blocked".equals(normalized)) {
            anyFail = true;
        }
        final String line = "detail." + step + "=" + normalized
            + (detail == null || detail.isEmpty() ? "" : " " + singleLine(detail));
        synchronized (details) {
            details.add(line);
        }
        logger.info("EDIT_PROBE_DETAIL " + line);
    }

    private void observe(final String key, final Object value) {
        final String line = "obs." + key + "=" + singleLine(value);
        synchronized (observations) {
            observations.add(line);
        }
    }

    private static String typedFailure(final EditSessionException failure) {
        return failure.getClass().getSimpleName() + " code=" + failure.code();
    }

    private static String describeHistory(final HistorySnapshot snapshot) {
        return "availability=" + snapshot.availability()
            + " position=" + snapshot.position()
            + " entries=" + snapshot.entries().size()
            + " revision=" + snapshot.revision()
            + " generation=" + snapshot.generation()
            + " canUndo=" + snapshot.canUndo()
            + " canRedo=" + snapshot.canRedo();
    }

    private static String singleLine(final Object value) {
        if (value == null) {
            return "null";
        }
        final String text = value.toString().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private void writeResultFile(final long startedNanos) {
        final Path result = stateDir.getParent().resolve(RESULT_FILE);
        try {
            final StringBuilder report = new StringBuilder()
                .append("schemaVersion=1\n")
                .append("runId=")
                .append(System.getProperty("turboism.validation.runId", "unknown"))
                .append('\n')
                .append("pluginId=").append(PLUGIN_ID).append('\n')
                .append("hostVersion=").append(hostVersion).append('\n')
                .append("fixtureName=")
                .append(System.getProperty("turboism.validation.fixtureName", "unknown"))
                .append('\n')
                .append("documentId=")
                .append(documentId == null ? "unknown" : documentId.value()).append('\n')
                .append("documentName=").append(singleLine(documentName)).append('\n')
                .append("mode=").append(mode).append('\n')
                .append("durationMillis=")
                .append(startedNanos == 0L
                    ? -1L
                    : (System.nanoTime() - startedNanos) / 1_000_000L)
                .append('\n');
            synchronized (details) {
                for (final String line : details) {
                    report.append(line).append('\n');
                }
            }
            synchronized (observations) {
                for (final String line : observations) {
                    report.append(line).append('\n');
                }
            }
            report.append("status=").append(anyFail ? "FAIL" : "PASS").append('\n');
            Files.createDirectories(result.getParent());
            Files.writeString(result, report.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("EDIT_RESULT_WRITTEN result=" + result
                + " status=" + (anyFail ? "FAIL" : "PASS"));
        } catch (Exception failure) {
            logger.error(
                "EDIT_RESULT_WRITE_FAILED result=" + result + " " + singleLine(failure));
        }
    }
}
