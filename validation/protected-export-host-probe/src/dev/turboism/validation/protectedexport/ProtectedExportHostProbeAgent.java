package dev.turboism.validation.protectedexport;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileSystemView;

/**
 * Validation-only exact-host probe for the production export-settings wiring.
 *
 * <p>It answers the question that no offline test can: on the reviewed Cubism 5.3.02 build, does
 * the transformed Embedded-Model Export Settings dialog actually carry the contributed option, and
 * does the decision gate behave like the native contract — unchecked confirmation continues the
 * native export path, a checked candidate rejects, and a cancel does neither?</p>
 *
 * <p>The probe is reflection-only over {@code com.live2d.*} plus plain Swing traversal. It never
 * imports a host type, never reads or writes the model, and never lets an export complete: every
 * continuation window the native flow raises is recorded and dismissed, so no file is written.
 * The only thing it drives is the same menu command a user would run, on the Swing thread.</p>
 *
 * <p>For observability the three loader-neutral bridge callbacks that transformed host bytecode
 * resolves through {@code System.getProperties()} are wrapped in counting delegates before the
 * first trigger. Semantics are preserved exactly — each delegate forwards to the installed
 * callback — so the run can tell apart "the transformed dialog never called the bridge" from
 * "the bridge was called but produced no option", which the previous blind run could not.</p>
 *
 * <p>Evidence is written under the task-scoped Turboism home as a flat properties result plus the
 * raw dialog trees, so the labels can be reviewed by a human rather than only asserted.</p>
 */
public final class ProtectedExportHostProbeAgent {

    private static final String APP_CTRL = "com.live2d.cubism.CEAppCtrl";
    private static final String MODELING_DOCUMENT =
        "com.live2d.cubism.doc.modeling.CModelingDocument";
    private static final String DIALOG_OWNER = "com.live2d.cubism.doc.model.exporter.e";
    private static final String STATE_PATH = "state/dev.turboism.validation.protectedexport";
    private static final String RESULT_NAME = "host-validation-result.properties";
    // Action classes on the native window buttons, verified against the dialog
    // tree dumps and button-action bytecode of every admitted build. On 5.3.x the
    // "OK" button carries com.live2d.ui.window.A (actionPerformed calls y.i(),
    // which sets the accept flag returned by y.f()) and "Cancel" carries
    // com.live2d.ui.window.z (y.h(), the dismiss path). On 5.2.03 the window A/z
    // classes are Kotlin Function1 lifecycle lambdas, not actions; the button
    // actions are com.live2d.ui.window.C (y.k(), which sets the accept flag
    // returned by y.f()) and com.live2d.ui.window.B (y.j(), the dismiss path).
    // Matching by action class rather than label is locale-stable.
    private static final String CONFIRM_ACTION_5_3 = "com.live2d.ui.window.A";
    private static final String CANCEL_ACTION_5_3 = "com.live2d.ui.window.z";
    private static final String CONFIRM_ACTION_5_2 = "com.live2d.ui.window.C";
    private static final String CANCEL_ACTION_5_2 = "com.live2d.ui.window.B";
    /**
     * Swing peer of the native {@code CVBox} options list, verified identical on
     * every reviewed build (5.2.03, 5.3.02, 5.3.03). Keyed by exact host version
     * like the button actions so a renamed peer on a future build fails loudly
     * instead of being smoothed over.
     */
    private static final String OPTIONS_PEER_5_3 = "com.live2d.ui.swingImpl.u";
    private static final String OPTIONS_PEER_5_2 = "com.live2d.ui.swingImpl.u";
    /** Affirmative button labels across the host's localized warning dialogs. */
    private static final java.util.regex.Pattern AFFIRMATIVE_LABEL =
        java.util.regex.Pattern.compile(
            "(?i)(yes|ok|确定|是|继续|continue|proceed|save|保存)");
    private static final String BRIDGE_PREFIX = "turboism.export-settings.dialog.";
    private static final String ATTACH_KEY = BRIDGE_PREFIX + "attach";
    private static final String CANCEL_KEY = BRIDGE_PREFIX + "cancel";
    private static final String DECIDE_KEY = BRIDGE_PREFIX + "decide";
    /**
     * Window name of the runtime's user-visible veto surface
     * ({@code ExportSettingsVetoDialog.DIALOG_NAME}). A checked confirmation
     * must always produce either a native continuation or this diagnostic —
     * a silent veto is the exact regression this probe exists to catch.
     */
    private static final String VETO_DIALOG_NAME = "turboism.export-settings.veto";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private static final long READY_TIMEOUT_MILLIS = 300_000L;
    private static final long DOCUMENT_TIMEOUT_MILLIS = 120_000L;
    private static final long DIALOG_TIMEOUT_MILLIS = 45_000L;
    private static final long SEQUENCE_TIMEOUT_MILLIS = 90_000L;
    private static final long QUIESCENCE_MILLIS = 15_000L;
    /** An armed session's terminal veto arrives after the worker unwinds — bound the wait. */
    private static final long VETO_TIMEOUT_MILLIS = 120_000L;
    private static final long IDLE_MILLIS = 6_000L;
    private static final long POLL_MILLIS = 200L;
    private static final long EXIT_GRACE_MILLIS = 60_000L;
    private static final long BIND_TIMEOUT_MILLIS = 60_000L;
    private static final long APPLY_STEP_MILLIS = 60_000L;
    private static final int TRIGGER_ATTEMPTS = 3;
    private static final long ATTEMPT_SETTLE_MILLIS = 15_000L;

    private ProtectedExportHostProbeAgent() {
    }

    public static void premain(final String ignored, final Instrumentation instrumentation) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        System.out.println("PROTECTED_EXPORT_PROBE_INITIALIZED");
        final Thread probe = new Thread(
            () -> run(instrumentation), "turboism-protected-export-probe"
        );
        probe.setDaemon(true);
        probe.start();
    }

    private static void run(final Instrumentation instrumentation) {
        final Path stateDir = stateDirectory();
        final Evidence evidence = new Evidence();
        evidence.progress = stateDir.resolve("probe-progress.properties");
        startWatchdog(stateDir, evidence);
        try {
            prepare(stateDir, evidence);
            if (!awaitHostReady(instrumentation, stateDir, evidence)) {
                finish(stateDir, evidence);
                return;
            }
            final Class<?> appCtrl = findLoadedClass(instrumentation, APP_CTRL);
            if (appCtrl == null) {
                evidence.fail("APP_CTRL_CLASS_MISSING");
                finish(stateDir, evidence);
                return;
            }
            final Object controller = staticInstance(appCtrl, evidence);
            if (controller == null) {
                evidence.fail("APP_CTRL_INSTANCE_UNAVAILABLE");
                finish(stateDir, evidence);
                return;
            }
            final BridgeObservation bridge = wrapBridgeCallbacks(evidence);
            awaitModelDocument(controller, stateDir, evidence);
            final List<String> phases = requestedPhases();
            evidence.put("phases", String.join(",", phases));

            if (phases.contains("dialog")) {
                ensureTextureAtlas(controller, evidence);
                final boolean unchecked = phaseUncheckedConfirm(
                    controller, appCtrl, instrumentation, stateDir, evidence
                );
                final boolean rejected =
                    unchecked && phaseCheckedReject(controller, appCtrl, stateDir, evidence);
                if (rejected) {
                    phaseCancel(controller, appCtrl, stateDir, evidence);
                }
            }
            if (phases.contains("copy-binding")) {
                phaseCopyBinding(controller, stateDir, evidence);
            }
            if (phases.contains("flatten")) {
                phaseFlatten(controller, stateDir, evidence);
            }
            if (phases.contains("census")) {
                phaseCensus(controller, stateDir, evidence);
            }
            if (phases.contains("atlas-fixture")) {
                phaseAtlasFixture(controller, stateDir, evidence);
            }
            if (phases.contains("export")) {
                phaseExport(controller, appCtrl, stateDir, evidence);
            }
            if (phases.contains("dirty-export")) {
                phaseExportDirty(controller, appCtrl, stateDir, evidence);
            }
            if (phases.contains("glue-export")) {
                phaseExportGlue(controller, appCtrl, stateDir, evidence);
            }
            if (phases.contains("export-native")) {
                phaseExportNative(controller, appCtrl, stateDir, evidence);
            }
            if (phases.contains("expect-reject")) {
                phaseExpectReject(controller, appCtrl, stateDir, evidence, false);
            }
            if (phases.contains("expect-reject-structure")) {
                phaseExpectReject(controller, appCtrl, stateDir, evidence, true);
            }
            bridge.report(evidence);
        } catch (Throwable failure) {
            evidence.fail("PROBE_FAILURE:" + failure.getClass().getName() + ":" + text(failure));
        }
        finish(stateDir, evidence);
    }

    /** Comma-separated phase list; default is the dialog-only evidence run. */
    private static List<String> requestedPhases() {
        final String raw = System.getProperty(
            "turboism.validation.protectedExport.phase", "dialog"
        );
        final List<String> phases = new ArrayList<>();
        for (String token : raw.split(",")) {
            final String phase = token.trim();
            if (!phase.isEmpty() && !phases.contains(phase)) {
                phases.add(phase);
            }
        }
        return phases.isEmpty() ? List.of("dialog") : phases;
    }

    // ------------------------------------------------------------------
    // Phase 1: unchecked confirmation must continue the native export path
    // ------------------------------------------------------------------

    private static boolean phaseUncheckedConfirm(
        final Object controller,
        final Class<?> appCtrl,
        final Instrumentation instrumentation,
        final Path stateDir,
        final Evidence evidence
    ) {
        for (int attempt = 1; attempt <= TRIGGER_ATTEMPTS; attempt++) {
            final Set<Window> alreadyVisible = visibleWindows();
            evidence.put("triggerAttempt", Integer.toString(attempt));
            if (!triggerExport(controller, appCtrl, evidence)) {
                return false;
            }
            final JDialog settings = awaitExportSettingsDialog(
                alreadyVisible, stateDir, evidence, "p1a" + attempt
            );
            if (settings == null) {
                settle(ATTEMPT_SETTLE_MILLIS);
                continue;
            }
            inspectSettingsDialog(settings, stateDir, evidence, "unchecked");
            confirmUnchecked(settings, evidence);
            observeContinuation(alreadyVisible, settings, stateDir, evidence, "unchecked");
            return true;
        }
        evidence.fail("EXPORT_SETTINGS_DIALOG_NOT_OBSERVED");
        return false;
    }

    // ------------------------------------------------------------------
    // Phase 2: a checked candidate must be rejected by the decision gate
    // ------------------------------------------------------------------

    private static boolean phaseCheckedReject(
        final Object controller,
        final Class<?> appCtrl,
        final Path stateDir,
        final Evidence evidence
    ) {
        final Set<Window> alreadyVisible = visibleWindows();
        if (!triggerExport(controller, appCtrl, evidence)) {
            return false;
        }
        final JDialog settings = awaitExportSettingsDialog(
            alreadyVisible, stateDir, evidence, "p2"
        );
        if (settings == null) {
            evidence.fail("CHECKED_SETTINGS_DIALOG_NOT_OBSERVED");
            return false;
        }
        inspectSettingsDialog(settings, stateDir, evidence, "checked");
        final List<JCheckBox> injected = injectedCheckBoxes(settings);
        if (injected.isEmpty()) {
            evidence.fail("CHECKED_INJECTED_OPTION_MISSING");
            dismiss(settings);
            return false;
        }
        try {
            onEdt(() -> {
                for (JCheckBox box : injected) {
                    box.doClick(0);
                }
                return null;
            });
            evidence.put("checkedSelectionClicked", "true");
        } catch (Throwable failure) {
            evidence.put("checkedSelectionFailure", text(failure));
        }
        final AbstractButton confirm = findButton(settings, confirmAction());
        if (confirm == null) {
            evidence.fail("CHECKED_CONFIRM_BUTTON_MISSING");
            dismiss(settings);
            return false;
        }
        try {
            onEdt(() -> {
                confirm.doClick(0);
                return null;
            });
            evidence.put("checkedConfirmClicked", "true");
        } catch (Throwable failure) {
            evidence.put("checkedConfirmFailure", text(failure));
        }
        // A checked veto must never die silently: the authority names an unwired
        // rejection at once, or the armed session's terminal failure posts the
        // veto dialog. Wait for it; every other new window stays unexpected.
        final List<String> unexpected = new ArrayList<>();
        awaitVetoDialog(alreadyVisible, settings, unexpected, stateDir, evidence,
            "checkedVeto", "checkedReject");
        evidence.put("checkedPostDecisionDialogs", String.join(" -> ", unexpected));
        evidence.put(
            "checkedNoContinuation", Boolean.toString(unexpected.isEmpty())
        );
        if (settings.isVisible()) {
            dismiss(settings);
            evidence.put("checkedSettingsStillOpen", "dismissed");
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Phase 3: cancel must clean up without touching the export path
    // ------------------------------------------------------------------

    private static void phaseCancel(
        final Object controller,
        final Class<?> appCtrl,
        final Path stateDir,
        final Evidence evidence
    ) {
        final Set<Window> alreadyVisible = visibleWindows();
        if (!triggerExport(controller, appCtrl, evidence)) {
            return;
        }
        final JDialog settings = awaitExportSettingsDialog(
            alreadyVisible, stateDir, evidence, "p3"
        );
        if (settings == null) {
            evidence.fail("CANCEL_SETTINGS_DIALOG_NOT_OBSERVED");
            return;
        }
        final AbstractButton cancel = findButton(settings, cancelAction());
        if (cancel == null) {
            evidence.put("cancelButtonDriven", "false");
            dismiss(settings);
        } else {
            evidence.put("cancelButtonDriven", "true");
            try {
                onEdt(() -> {
                    cancel.doClick(0);
                    return null;
                });
            } catch (Throwable failure) {
                evidence.put("cancelFailure", text(failure));
            }
        }
        final List<String> unexpected = awaitQuiescence(alreadyVisible, settings);
        evidence.put("cancelPostDecisionDialogs", String.join(" -> ", unexpected));
        evidence.put("cancelNoContinuation", Boolean.toString(unexpected.isEmpty()));
        if (settings.isVisible()) {
            dismiss(settings);
            evidence.put("cancelSettingsStillOpen", "dismissed");
        }
    }

    // ------------------------------------------------------------------
    // Phase: disposable-copy binding and restoration (M2)
    //
    // Proves that a task-owned copy of the active document can be opened
    // natively, observed as the active backing file, and that the original
    // document survives with file bytes, dirty flag, undo position, and
    // selection unchanged. No mutation is performed here — mutation is
    // admitted only after this evidence exists.
    // ------------------------------------------------------------------

    private static void phaseCopyBinding(
        final Object controller,
        final Path stateDir,
        final Evidence evidence
    ) {
        copySession(controller, evidence, "copy.", null);
    }

    // ------------------------------------------------------------------
    // Phase: supported-deformer flatten on the bound copy (M3)
    //
    // Binds a task-owned copy, then drives the exact native apply-to-children
    // path for every supported Warp/Rotation deformer leaf-to-root. Each step
    // re-resolves the target by stable GUID, re-verifies document/model-source/
    // model identity after the selection call (the sendEvent=true re-entrancy
    // window), requires the target GUID to be absent afterwards, and requires
    // zero supported deformers at completion. The original session is restored
    // and checked exactly as in copy-binding. Any unsupported deformer family
    // or guard failure rejects the run before or during mutation.
    // ------------------------------------------------------------------

    private static void phaseFlatten(
        final Object controller,
        final Path stateDir,
        final Evidence evidence
    ) {
        copySession(controller, evidence, "flat.", scope ->
            flattenSupportedDeformers(controller, scope, evidence));
    }

    // ------------------------------------------------------------------
    // Phase: production orchestrated export, end to end (M4)
    //
    // Drives the feature exactly like a user: check the contributed option and
    // confirm. The decision gate vetoes the outer export and the orchestrator
    // arms — preflight, disposable-copy bind, flatten, and the re-driven native
    // export all happen without probe involvement. The fixture stores no
    // texture atlas on disk and a file copy cannot carry an in-memory one, so
    // while the orchestrator works the probe watches for the bound copy and
    // injects the same atlas scaffolding there. The re-driven inner dialog must
    // appear with contributed options suppressed; confirming it reaches the
    // native save chooser, which is driven to a task-owned destination. The run
    // passes only when validated output is published at that destination and the
    // original session is verifiably restored.
    // ------------------------------------------------------------------

    private static void phaseExport(
        final Object controller,
        final Class<?> appCtrl,
        final Path stateDir,
        final Evidence evidence
    ) {
        phaseExportRun(controller, appCtrl, stateDir, evidence, "exp.", false,
            false);
    }

    /**
     * Dirty-document variant: one unsaved in-memory edit is applied to the
     * fixture before the export is armed, so the run proves protected export
     * publishes from the live document state — same content basis as the
     * native exporter — while the on-disk original stays byte-identical and
     * the unsaved edit survives the copy round-trip.
     */
    private static void phaseExportDirty(
        final Object controller,
        final Class<?> appCtrl,
        final Path stateDir,
        final Evidence evidence
    ) {
        phaseExportRun(controller, appCtrl, stateDir, evidence, "dexp.", true,
            false);
    }

    /**
     * Glue variant: injects one real Glue relation onto two fixture ArtMeshes
     * through the editor's own construction sequence, then drives the same
     * protected export. Evidence asserts the session admits the model, the
     * staged moc3 carries the Glue under its unchanged ID with resolvable
     * drawable references, and the live document's Glue is untouched.
     */
    private static void phaseExportGlue(
        final Object controller,
        final Class<?> appCtrl,
        final Path stateDir,
        final Evidence evidence
    ) {
        phaseExportRun(controller, appCtrl, stateDir, evidence, "gexp.", false,
            true);
    }

    private static void phaseExportRun(
        final Object controller,
        final Class<?> appCtrl,
        final Path stateDir,
        final Evidence evidence,
        final String prefix,
        final boolean dirty,
        final boolean glue
    ) {
        try {
            final Object original = readNoArg(controller, "getCurrentDoc");
            if (original == null || !isA(original.getClass(), MODELING_DOCUMENT)) {
                evidence.fail("EXP_NO_MODELING_DOCUMENT");
                return;
            }
            final Object content = readNoArg(original, "getFileContent");
            final Object fileObj =
                content == null ? null : readNoArg(content, "getFile");
            if (!(fileObj instanceof File originalFile) || !originalFile.isFile()) {
                evidence.fail("EXP_ORIGINAL_FILE_MISSING");
                return;
            }
            // Inject first so the snapshot baseline already carries whatever the
            // in-memory atlas scaffolding changes (undo position included).
            ensureTextureAtlas(controller, evidence);
            if (glue) {
                // Glue variant: build one real Glue relation on the live model
                // through the editor's own construction sequence, so the export
                // proves the admitted pass-through channel end to end.
                injectGlueFixture(original, evidence, prefix);
                if (evidence.error != null) {
                    return;
                }
            }
            if (dirty) {
                applyUnsavedEdit(original, evidence, prefix);
                if (evidence.error != null) {
                    return;
                }
            } else if (!glue) {
                final Object modifiedCheck =
                    readNoArg(content, "isModifiedAfterSaving");
                if (Boolean.TRUE.equals(modifiedCheck)) {
                    // The clean-fixture variant must start clean; a dirty
                    // baseline here means fixture setup, not the export path,
                    // drifted — the dirty-export variant covers that case.
                    evidence.fail("EXP_ORIGINAL_DIRTY");
                    return;
                }
            }
            final DocumentState before =
                snapshotDocument(original, evidence, prefix + "orig");
            evidence.put(prefix + "origFile", originalFile.getAbsolutePath());
            evidence.put(prefix + "origFileSha256", sha256(originalFile));

            final Path exportOut = stateDir.resolve("export-out");
            Files.createDirectories(exportOut);
            // The native destination picker runs in either open/directory mode
            // (only approves an existing selection) or save mode (wants a file
            // name inside a writable directory). The chooser-redirect
            // transformer replaces whatever is picked with the staged output
            // anyway, so both picks stay inside the task-owned export-out.
            final File pick = exportOut.toFile();
            final File pickFile =
                exportOut.resolve("protected-export.moc3").toFile();
            evidence.put(prefix + "pick", pick.getAbsolutePath());

            final Set<Window> alreadyVisible = visibleWindows();
            if (!triggerExport(controller, appCtrl, evidence)) {
                return;
            }
            final String phaseTag = prefix.replace(".", "");
            final JDialog outer = awaitExportSettingsDialog(
                alreadyVisible, stateDir, evidence, phaseTag + "Outer"
            );
            if (outer == null) {
                evidence.fail("EXP_OUTER_DIALOG_NOT_OBSERVED");
                return;
            }
            inspectSettingsDialog(outer, stateDir, evidence, phaseTag + "Outer");
            final List<JCheckBox> injected = injectedCheckBoxes(outer);
            evidence.put(
                prefix + "outerInjectedCheckBoxCount", Integer.toString(injected.size()));
            if (injected.isEmpty()) {
                evidence.fail("EXP_OUTER_OPTION_MISSING");
                dismiss(outer);
                return;
            }
            try {
                onEdt(() -> {
                    for (JCheckBox box : injected) {
                        box.doClick(0);
                    }
                    return null;
                });
                evidence.put(prefix + "optionChecked", "true");
            } catch (Throwable failure) {
                evidence.put(prefix + "optionCheckFailure", text(failure));
            }
            final AbstractButton outerConfirm = findButton(outer, confirmAction());
            if (outerConfirm == null) {
                evidence.fail("EXP_OUTER_CONFIRM_MISSING");
                dismiss(outer);
                return;
            }
            onEdt(() -> {
                outerConfirm.doClick(0);
                return null;
            });
            evidence.put(prefix + "outerConfirmed", "true");
            waitForHidden(outer);

            final JDialog inner = awaitInnerDialog(
                controller, alreadyVisible, outer, stateDir, evidence, prefix);
            if (inner == null) {
                return; // inner wait recorded its own failure evidence
            }
            inspectSettingsDialog(inner, stateDir, evidence, phaseTag + "Inner");
            final int innerInjected = injectedCheckBoxes(inner).size();
            evidence.put(prefix + "innerInjectedCheckBoxCount",
                Integer.toString(innerInjected));
            evidence.put(prefix + "innerInjectedSuppressed",
                Boolean.toString(innerInjected == 0));
            if (innerInjected != 0) {
                // Suppression failed: the inner dialog must be byte-identical to
                // a native unchecked dialog. Cancel it so the session unwinds.
                evidence.fail("EXP_INNER_OPTIONS_NOT_SUPPRESSED");
                dismiss(inner);
                return;
            }
            final AbstractButton innerConfirm = findButton(inner, confirmAction());
            if (innerConfirm == null) {
                evidence.fail("EXP_INNER_CONFIRM_MISSING");
                dismiss(inner);
                return;
            }
            onEdt(() -> {
                innerConfirm.doClick(0);
                return null;
            });
            evidence.put(prefix + "innerConfirmed", "true");
            waitForHidden(inner);
            evidence.put(prefix + "innerHiddenAfterConfirm",
                Boolean.toString(!inner.isVisible()));

            final File approved = driveToDestination(inner, pickFile,
                alreadyVisible, stateDir, evidence, prefix);
            if (approved == null) {
                return; // chooser drive recorded its own failure evidence
            }
            awaitPublished(approved, stateDir, evidence, prefix);
            // Dump the published moc3 through the SDK Core so it can be
            // diffed against the unprotected native baseline byte-semantics.
            final Path published = approved.getName().endsWith(".moc3")
                ? approved.toPath().toAbsolutePath()
                : approved.toPath().toAbsolutePath().getParent()
                    .resolve(approved.getName() + ".moc3");
            dumpMoc3(published, stateDir.resolve("published-moc3.txt"),
                evidence, prefix);

            final Object restored = readNoArg(controller, "getCurrentDoc");
            evidence.put(prefix + "restoredActive",
                Boolean.toString(restored != null
                    && System.identityHashCode(restored) == before.docId));
            if (restored != null) {
                final DocumentState after =
                    snapshotDocument(restored, evidence, prefix + "restored");
                evidence.put(prefix + "sameLiveDocument",
                    Boolean.toString(after.docId == before.docId));
                evidence.put(prefix + "modifiedPreserved",
                    Boolean.toString(after.modified == before.modified));
                evidence.put(prefix + "undoPreserved",
                    Boolean.toString(
                        after.undoSignature.equals(before.undoSignature)));
                evidence.put(prefix + "selectionPreserved",
                    Boolean.toString(after.selectionSignature
                        .equals(before.selectionSignature)));
                if (dirty) {
                    // The unsaved edit must still be present on the live
                    // original — proving the export neither saved nor reloaded
                    // the document it started from.
                    evidence.put(prefix + "unsavedEditPreserved",
                        Boolean.toString(unsavedEditValue(restored)
                            .equals(evidence.values.get(
                                prefix + "editedParamMax"))));
                }
            }
            evidence.put(prefix + "fileSha256Preserved",
                Boolean.toString(sha256(originalFile)
                    .equals(evidence.values.get(prefix + "origFileSha256"))));
            if (glue) {
                recordGlueOutcome(restored, evidence, prefix);
            }
            reportStagingResidue(stateDir, evidence, prefix);
        } catch (Throwable failure) {
            evidence.fail("EXP_PHASE_FAILURE:" + failure.getClass().getName()
                + ":" + text(failure));
        }
    }

    /**
     * Post-export Glue evidence for the {@code gexp.} variant: the staged moc3
     * dump must carry the injected Glue under its unchanged ID, and the live
     * document's Glue must still hold the exact identity and target references
     * recorded at injection time.
     */
    private static void recordGlueOutcome(
        final Object restored,
        final Evidence evidence,
        final String prefix
    ) {
        final String glueId = evidence.values.getOrDefault(prefix + "glueId", "");
        boolean inMoc = false;
        for (String id : evidence.values
            .getOrDefault(prefix + "moc3GlueIds", "").split(",")) {
            if (!glueId.isEmpty() && glueId.equals(id)) {
                inMoc = true;
            }
        }
        evidence.put(prefix + "glueInPublishedMoc3", Boolean.toString(inMoc));
        final String[] preserved = {"false"};
        try {
            onEdtBounded(APPLY_STEP_MILLIS, () -> {
                final Object found = restored == null
                    ? null : findGlue(restored, glueId);
                if (found == null) {
                    return null;
                }
                preserved[0] = Boolean.toString(
                    glueId.equals(idString(found))
                        && evidence.values.getOrDefault(prefix + "glueGuid", "")
                            .equals(guidString(found))
                        && evidence.values.getOrDefault(prefix + "glueName", "")
                            .equals(String.valueOf(readNoArg(found, "getLocalName")))
                        && evidence.values.getOrDefault(prefix + "glueTargets", "")
                            .equals(glueTargetSignature(found)));
                return null;
            });
        } catch (Throwable failure) {
            preserved[0] = "failed:" + text(failure);
        }
        evidence.put(prefix + "glueIdentityPreserved", preserved[0]);
    }

    /**
     * Applies one unsaved in-memory edit to the live document: bump the first
     * parameter's max value, then mark the document modified without saving.
     * The disk file keeps its pre-edit bytes, so the value surviving the export
     * proves the staged copy was serialized from memory, not copied from disk.
     * Must run on the EDT; failures land as evidence, not exceptions.
     */
    private static void applyUnsavedEdit(
        final Object document,
        final Evidence evidence,
        final String prefix
    ) {
        try {
            onEdtBounded(APPLY_STEP_MILLIS, () -> {
                final Object source = readNoArg(document, "getModelSource");
                final Object parameters = source == null
                    ? null : readNoArg(source, "getAllParameters");
                if (!(parameters instanceof List<?> list) || list.isEmpty()) {
                    evidence.fail("DIRTY_NO_PARAMETER_SOURCE");
                    return null;
                }
                final Object parameter = list.get(0);
                final Object max = readNoArg(parameter, "getMaxValue");
                if (!(max instanceof Number)) {
                    evidence.fail("DIRTY_PARAM_MAX_UNREADABLE");
                    return null;
                }
                final float bumped =
                    ((Number) max).floatValue() + 1.0f;
                invoke(parameter, "setMaxValue",
                    new Class<?>[] {float.class}, bumped);
                invoke(document, "updateLastModifiedTime",
                    new Class<?>[0]);
                evidence.put(prefix + "editedParamMax", Float.toString(bumped));
                return null;
            });
            if (evidence.error != null) {
                return;
            }
            final Object content = readNoArg(document, "getFileContent");
            final Object modified = content == null
                ? null : readNoArg(content, "isModifiedAfterSaving");
            evidence.put(prefix + "dirtyArmed", String.valueOf(modified));
            if (!Boolean.TRUE.equals(modified)) {
                evidence.fail("DIRTY_MARKING_FAILED");
            }
        } catch (Throwable failure) {
            evidence.fail("DIRTY_EDIT_FAILURE:" + text(failure));
        }
    }

    /** Reads the bumped parameter max back off a live document. */
    private static String unsavedEditValue(final Object document) {
        try {
            final String[] value = {""};
            onEdtBounded(APPLY_STEP_MILLIS, () -> {
                final Object source = readNoArg(document, "getModelSource");
                final Object parameters = source == null
                    ? null : readNoArg(source, "getAllParameters");
                if (!(parameters instanceof List<?> list) || list.isEmpty()) {
                    return null;
                }
                final Object max = readNoArg(list.get(0), "getMaxValue");
                value[0] = max == null ? "" : String.valueOf(max);
                return null;
            });
            return value[0];
        } catch (Throwable failure) {
            return "unavailable:" + text(failure);
        }
    }

    /**
     * Waits for the re-driven inner settings dialog on the disposable copy.
     * While waiting, the bound copy is detected through the active document's
     * backing file path (inside the task-owned staging tree) and the atlas
     * scaffolding is injected into it — the file copy cannot carry the atlas
     * the probe added to the original's live model. Intervening native veto
     * dialogs (for example the zero-atlas pre-check) are recorded and dismissed
     * so the re-drive can return and the session can fail cleanly.
     */
    private static JDialog awaitInnerDialog(
        final Object controller,
        final Set<Window> alreadyVisible,
        final JDialog outer,
        final Path stateDir,
        final Evidence evidence,
        final String prefix
    ) {
        final Set<Window> seen = new LinkedHashSet<>(alreadyVisible);
        seen.add(outer);
        // The settings window is a plain JDialog titled by the host (the owner
        // object e is a controller, not the window). The inner dialog is the
        // same native window re-driven on the copy: match by the outer title.
        final String settingsTitle = outer.getTitle();
        final List<String> observed = new ArrayList<>();
        final boolean[] copyAtlas = {false};
        final long deadline = System.currentTimeMillis() + 240_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!copyAtlas[0]) {
                copyAtlas[0] =
                    injectAtlasOnBoundCopy(controller, stateDir, evidence, prefix);
            }
            for (Window window : visibleWindows()) {
                if (seen.contains(window)) {
                    continue;
                }
                seen.add(window);
                final String signature = describe(window);
                observed.add(signature);
                dumpTree(stateDir.resolve(
                        "dialog-tree-" + prefix.replace(".", "")
                            + "Inner-observed-" + observed.size() + ".txt"),
                    window);
                // Title match alone identifies the settings window; requiring
                // native checkboxes here could dismiss the inner dialog if its
                // tree is still populating when it first becomes visible.
                if (window instanceof JDialog dialog
                    && settingsTitle != null
                    && settingsTitle.equals(dialog.getTitle())) {
                    evidence.put(prefix + "innerObserved", "true");
                    evidence.put(prefix + "innerObservedBefore",
                        String.join(" | ", observed));
                    return dialog;
                }
                // A veto/error dialog blocks the EDT; record then dismiss so the
                // re-drive unwinds and the session reports its own failure.
                if (isVetoDialog(window)) {
                    recordVetoDialog((java.awt.Dialog) window, stateDir,
                        evidence, prefix + "veto", "innerWait");
                    dismiss((java.awt.Dialog) window);
                    continue;
                }
                if (window instanceof java.awt.Dialog dialog
                    && firstButton(dialog) != null) {
                    dismiss(dialog);
                }
            }
            sleep(POLL_MILLIS);
        }
        evidence.put(prefix + "innerObserved", "false");
        evidence.put(prefix + "innerObservedBeforeTimeout",
            String.join(" | ", observed));
        evidence.fail("EXP_INNER_DIALOG_NOT_OBSERVED");
        return null;
    }

    /**
     * Injects the atlas scaffolding into the disposable copy once it is the
     * active document, recognised by its backing file living under the
     * task-owned {@code protected-export} staging tree. Non-dirtying, in-memory
     * only — the same precedent as {@link #ensureTextureAtlas}.
     */
    private static boolean injectAtlasOnBoundCopy(
        final Object controller,
        final Path stateDir,
        final Evidence evidence,
        final String prefix
    ) {
        try {
            // The copy must live under the runtime's own staging root
            // (<home>/state/runtime/protected-export/<session>/file.cmo3). The
            // task directory itself is named protected-export too, so a bare
            // substring match would false-positive on the original document.
            final Path stagingRoot = stateDir.getParent() == null ? null
                : stateDir.getParent().resolve("runtime").resolve("protected-export");
            final Object document = readNoArg(controller, "getCurrentDoc");
            if (document == null || stagingRoot == null) {
                return false;
            }
            final Object content = readNoArg(document, "getFileContent");
            final Object bound =
                content == null ? null : readNoArg(content, "getFile");
            if (!(bound instanceof File boundFile)
                || !boundFile.toPath().toAbsolutePath().normalize()
                    .startsWith(stagingRoot)) {
                return false;
            }
            evidence.put(prefix + "copyBoundPath", boundFile.getAbsolutePath());
            onEdtBounded(APPLY_STEP_MILLIS, () -> {
                final Object source = readNoArg(document, "getModelSource");
                final Object manager =
                    source == null ? null : readNoArg(source, "getTextureManager");
                final Object atlases =
                    manager == null ? null : readNoArg(manager, "getTextureAtlases");
                if (!(atlases instanceof List<?> list) || !list.isEmpty()) {
                    evidence.put(prefix + "copyAtlasInjected", "not-needed");
                    return null;
                }
                final Class<?> atlasType = Class.forName(
                    "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas");
                final java.lang.reflect.Constructor<?> ctor =
                    atlasType.getDeclaredConstructor(
                        Class.forName("com.live2d.cubism.doc.model.CModelSource"),
                        String.class, int.class, int.class);
                ctor.setAccessible(true);
                final Object atlas =
                    ctor.newInstance(source, "pe-probe-atlas", 1024, 1024);
                invoke(manager, "addTextureAtlas",
                    new Class<?>[] {atlasType, int.class}, atlas, 0);
                evidence.put(prefix + "copyAtlasInjected", "true");
                return null;
            });
            return true;
        } catch (Throwable failure) {
            evidence.put(prefix + "copyAtlasFailure", text(failure));
            return true; // do not retry every poll on a hard failure
        }
    }

    /**
     * After the inner confirm, the native flow may raise confirmation prompts
     * (export-warning list, alias list, physics report) before the destination
     * chooser. Each is a modal the user would confirm through; the chooser is
     * the window carrying a {@code JFileChooser} and is driven to the pick.
     */
    private static File driveToDestination(
        final JDialog inner,
        final File pickFile,
        final Set<Window> alreadyVisible,
        final Path stateDir,
        final Evidence evidence,
        final String prefix
    ) {
        final Map<Window, Integer> seen = new LinkedHashMap<>();
        for (Window window : alreadyVisible) {
            seen.put(window, Integer.MAX_VALUE);
        }
        seen.put(inner, Integer.MAX_VALUE);
        final List<String> sequence = new ArrayList<>();
        final File[] approved = new File[1];
        // JFileChooser construction under Wine can block the EDT for a long
        // time enumerating shell folders (Z: maps the whole Linux root), so the
        // chooser window may appear minutes after the export was confirmed.
        final long deadline = System.currentTimeMillis() + 300_000L;
        while (System.currentTimeMillis() < deadline) {
            // Re-scan every visible window each poll: a dialog observed before
            // its content was populated can gain the JFileChooser late, and the
            // chooser carrier must never be dismissed as an intermediate prompt.
            javax.swing.JFileChooser chooser = null;
            for (Window window : visibleWindows()) {
                final javax.swing.JFileChooser found = findFileChooser(window);
                if (found != null) {
                    chooser = found;
                    break;
                }
            }
            final javax.swing.JFileChooser target = chooser;
            if (target != null) {
                try {
                    onEdt(() -> {
                        // The task-home pick is not writable from the Wine
                        // side — the chooser's stock validation loops a
                        // "no access to the selected folder" Message. The
                        // shortcuts panel carries a guaranteed-writable
                        // Desktop entry; click it like a user would, then
                        // approve a selection inside Desktop. The redirect
                        // transformer replaces the picked File regardless.
                        final AbstractButton desktopShortcut =
                            desktopShortcut(target);
                        if (desktopShortcut != null) {
                            desktopShortcut.doClick(0);
                        }
                        final File desktop = desktopDir();
                        if (desktop != null
                                && target.getDialogType()
                                    == javax.swing.JFileChooser
                                        .SAVE_DIALOG) {
                            target.setCurrentDirectory(desktop);
                            final File current = target.getSelectedFile();
                            final String name =
                                current != null && !current.isDirectory()
                                    ? current.getName()
                                    : pickFile.getName();
                            approved[0] = new File(desktop, name);
                            target.setSelectedFile(approved[0]);
                        } else if (desktop != null) {
                            final File parent = desktop.getParentFile();
                            if (parent != null) {
                                target.setCurrentDirectory(parent);
                            }
                            approved[0] = desktop;
                            target.setSelectedFile(desktop);
                        }
                        target.approveSelection();
                        final File selected = target.getSelectedFile();
                        if (selected != null) {
                            approved[0] = selected;
                        }
                        return null;
                    });
                    // approveSelection only closes the dialog when the pick is
                    // approvable; keep polling until the chooser actually
                    // disappears so a refused selection is retried or timed out
                    // rather than mistaken for a successful drive.
                    evidence.put(prefix + "chooserDriven", "true");
                    final long hideDeadline =
                        System.currentTimeMillis() + 10_000L;
                    while (System.currentTimeMillis() < hideDeadline
                            && target.isShowing()) {
                        sleep(POLL_MILLIS);
                    }
                    if (!target.isShowing()) {
                        evidence.put(prefix + "chooserDialogSequence",
                            String.join(" -> ", sequence));
                        return approved[0];
                    }
                    final java.awt.Window carrier = currentCarrier(target);
                    evidence.put(prefix + "chooserApproveRefused",
                        carrier == null ? "<detached>" : describe(carrier));
                } catch (Throwable failure) {
                    evidence.put(prefix + "chooserDriveFailure", text(failure));
                    evidence.fail("EXP_CHOOSER_DRIVE_FAILED");
                    return null;
                }
            }
            clickSettledDialogs(seen, sequence, stateDir, evidence, prefix,
                "expChooser");
            sleep(POLL_MILLIS);
        }
        evidence.put(prefix + "chooserDriven", "false");
        evidence.put(prefix + "chooserDialogSequence",
            String.join(" -> ", sequence));
        final List<String> open = new ArrayList<>();
        for (Window window : visibleWindows()) {
            open.add(describe(window));
            dumpTree(stateDir.resolve(
                    "dialog-tree-expChooser-timeout-" + open.size() + ".txt"),
                window);
        }
        evidence.put(prefix + "chooserTimeoutWindows", String.join(" | ", open));
        evidence.fail("EXP_CHOOSER_NOT_OBSERVED");
        return null;
    }

    /**
     * One scan+click pass over all visible windows. Windows already visible when
     * the owning phase started (MAX_VALUE) are background: never clicked — a
     * stray button on the home window can open a modal chooser and wedge the
     * flow. Fresh dialogs get a few observation polls before clicking: the
     * chooser carrier can appear with only its title-bar close button attached,
     * and clicking that cancels the export. Clicks are posted non-blocking —
     * the button can open a modal dialog, and a blocking invokeAndWait would
     * deadlock the loop that is supposed to drive that very dialog.
     */
    private static void clickSettledDialogs(
        final Map<Window, Integer> seen,
        final List<String> sequence,
        final Path stateDir,
        final Evidence evidence,
        final String prefix,
        final String dumpTag
    ) {
        for (Window window : visibleWindows()) {
            if (isVetoDialog(window)) {
                // The veto diagnostic is the session's own visible verdict —
                // never a continuation window. Record its reason once, then
                // dismiss so the host can unwind.
                if (seen.get(window) == null) {
                    recordVetoDialog((java.awt.Dialog) window, stateDir,
                        evidence, prefix + "veto", dumpTag);
                }
                seen.put(window, Integer.MAX_VALUE);
                dismiss((java.awt.Dialog) window);
                continue;
            }
            final Integer polls = seen.computeIfAbsent(window, k -> 0);
            if (polls == 0) {
                sequence.add(describe(window));
                dumpTree(stateDir.resolve(
                        "dialog-tree-" + dumpTag + "-" + sequence.size() + ".txt"),
                    window);
            }
            if (polls == Integer.MAX_VALUE || polls < 5
                    || !(window instanceof java.awt.Dialog dialog)) {
                if (polls != Integer.MAX_VALUE) {
                    seen.put(window, polls + 1);
                }
                continue;
            }
            final AbstractButton confirm = findButton(dialog, confirmAction());
            final AbstractButton click =
                confirm != null ? confirm : firstButton(dialog);
            if (click != null) {
                java.awt.EventQueue.invokeLater(() -> click.doClick(0));
                evidence.put(prefix + "intermediateClicked", describe(window));
                // One click per dialog: if it stays open, record it rather
                // than spamming the button every poll.
                seen.put(window, Integer.MAX_VALUE);
            }
        }
    }

    private static javax.swing.JFileChooser findFileChooser(final Component root) {
        if (root instanceof javax.swing.JFileChooser chooser) {
            return chooser;
        }
        if (!(root instanceof Container container)) {
            return null;
        }
        for (Component component : allComponents(container)) {
            if (component instanceof javax.swing.JFileChooser chooser) {
                return chooser;
            }
        }
        return null;
    }

    /**
     * Finds the shortcuts-panel "Desktop" toggle inside the chooser's carrier —
     * the Wine-guaranteed-writable location a user would pick when the default
     * destination folder rejects the save.
     */
    private static AbstractButton desktopShortcut(
        final javax.swing.JFileChooser chooser
    ) {
        final java.awt.Window carrier = currentCarrier(chooser);
        final Container root = carrier != null ? carrier : chooser;
        for (Component component : allComponents(root)) {
            if (component instanceof JToggleButton toggle
                    && toggle.getText() != null
                    && toggle.getText().trim().matches(
                        "(?i)desktop|桌面|デスクトップ")) {
                return toggle;
            }
        }
        return null;
    }

    /**
     * {@code FileSystemView.getHomeDirectory()} resolves to the user Desktop
     * on the Windows shell (and under Wine); fall back to ~/Desktop then home.
     */
    private static File desktopDir() {
        final File home = FileSystemView.getFileSystemView().getHomeDirectory();
        if (home != null && home.isDirectory()) {
            return home;
        }
        final File desktop =
            new File(System.getProperty("user.home", "."), "Desktop");
        return desktop.isDirectory()
            ? desktop
            : new File(System.getProperty("user.home", "."));
    }

    /**
     * Publication is all-or-nothing: the picked destination name only appears
     * once the staged output validated and the original was restored. Polls
     * the approved pick's real destination for the moc3 and its companions.
     */
    private static void awaitPublished(
        final File approved,
        final Path stateDir,
        final Evidence evidence,
        final String prefix
    ) {
        // The chooser-redirect transformer mirrors the approved pick into
        // staging, so publication lands at the approved destination itself:
        // a file pick is the output path; a directory pick gains <name>.moc3
        // beside it in the parent directory.
        final Path approvedPath = approved.toPath().toAbsolutePath();
        final Path moc3 = approved.getName().endsWith(".moc3")
            ? approvedPath
            : approvedPath.getParent().resolve(approved.getName() + ".moc3");
        evidence.put(prefix + "approvedPick", approvedPath.toString());
        // The export can raise trailing prompts after the chooser (completion
        // notices, overwrite or error dialogs); each parks al.a on the EDT
        // until dismissed, so keep driving them while waiting for the moc3.
        final Map<Window, Integer> seen = new LinkedHashMap<>();
        for (Window window : visibleWindows()) {
            seen.put(window, Integer.MAX_VALUE);
        }
        final List<String> postChooser = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + 300_000L;
        while (System.currentTimeMillis() < deadline) {
            clickSettledDialogs(seen, postChooser, stateDir, evidence, prefix,
                "expPostChooser");
            try {
                if (Files.isRegularFile(moc3) && Files.size(moc3) > 0L) {
                    // Companions may still be streaming in; settle then list.
                    // Trailing modals (progress/completion prompts) must also
                    // be driven closed — a leftover dialog parks the EDT and
                    // blocks the host's exit path after the verdict lands.
                    final long settleDeadline =
                        System.currentTimeMillis() + 10_000L;
                    while (System.currentTimeMillis() < settleDeadline) {
                        clickSettledDialogs(seen, postChooser, stateDir,
                            evidence, prefix, "expPostChooser");
                        sleep(POLL_MILLIS);
                    }
                    final Path parent = moc3.getParent();
                    final List<String> files = new ArrayList<>();
                    try (var walk = Files.walk(parent)) {
                        walk.filter(Files::isRegularFile)
                            .forEach(p -> files.add(
                                parent.relativize(p).toString()));
                    }
                    evidence.put(prefix + "publishedMoc3", "true");
                    evidence.put(prefix + "publishedMoc3Bytes",
                        Long.toString(Files.size(moc3)));
                    evidence.put(prefix + "publishedFiles", String.join(",", files));
                    evidence.put(prefix + "publishedModelJson",
                        Boolean.toString(files.stream().anyMatch(
                            name -> name.endsWith(".model3.json")
                                || name.endsWith(".cdi3.json"))));
                    evidence.put(prefix + "postChooserDialogs",
                        String.join(" -> ", postChooser));
                    return;
                }
            } catch (IOException ignored) {
                // Keep polling until the deadline.
            }
            sleep(POLL_MILLIS);
        }
        evidence.put(prefix + "publishedMoc3", "false");
        evidence.put(prefix + "postChooserDialogs",
            String.join(" -> ", postChooser));
        evidence.fail("EXP_OUTPUT_NOT_PUBLISHED");
    }

    /**
     * Counts task-owned staging residue: {@code protected-export-*} session
     * directories or copied cmo3 files anywhere under the Turboism state tree
     * after the session ended.
     */
    private static void reportStagingResidue(
        final Path stateDir,
        final Evidence evidence,
        final String prefix
    ) {
        final Path stateRoot = stateDir.getParent() == null
            ? stateDir : stateDir.getParent().getParent();
        int residue = 0;
        final List<String> leftovers = new ArrayList<>();
        if (stateRoot != null && Files.isDirectory(stateRoot)) {
            try (var walk = Files.walk(stateRoot, 6)) {
                for (Path path : walk.toList()) {
                    final String name = path.getFileName() == null
                        ? "" : path.getFileName().toString();
                    if (name.contains("protected-export-")
                        || (name.endsWith(".cmo3") && name.contains("pe-"))) {
                        residue++;
                        leftovers.add(stateRoot.relativize(path).toString());
                    }
                }
            } catch (IOException failure) {
                evidence.put(prefix + "stagingResidueScan", text(failure));
            }
        }
        evidence.put(prefix + "stagingResidue", Integer.toString(residue));
        if (!leftovers.isEmpty()) {
            evidence.put(prefix + "stagingLeftovers",
                String.join(",", leftovers.subList(0, Math.min(10, leftovers.size()))));
        }
    }

    /**
     * Expected-rejection phase: drive the checked option on a fixture the
     * session must refuse, then prove the refusal. Unlike the positive path —
     * where a missing inner dialog is a transport failure — here the session's
     * own terminal report is the assertion surface: {@code reached=FAILED},
     * {@code published=false}, the configured failure key, the original file
     * hash and document invariants unchanged, zero staging residue, and no
     * inner export dialog ever raised. Normal host exit is still enforced by
     * the runner's exit-marker gate.
     */
    private static void phaseExpectReject(
        final Object controller,
        final Class<?> appCtrl,
        final Path stateDir,
        final Evidence evidence,
        final boolean injectUnsupported
    ) {
        final String prefix = "rej.";
        final String expectedFailure = System.getProperty(
            "turboism.validation.protectedExport.expectFailure",
            "protected-export.preflight-failed"
        );
        evidence.put(prefix + "expectedFailure", expectedFailure);
        UnsupportedInjection injection = null;
        try {
            final Object original = readNoArg(controller, "getCurrentDoc");
            if (original == null || !isA(original.getClass(), MODELING_DOCUMENT)) {
                evidence.fail("REJ_NO_MODELING_DOCUMENT");
                return;
            }
            final Object content = readNoArg(original, "getFileContent");
            final Object fileObj =
                content == null ? null : readNoArg(content, "getFile");
            if (!(fileObj instanceof File originalFile) || !originalFile.isFile()) {
                evidence.fail("REJ_ORIGINAL_FILE_MISSING");
                return;
            }
            final Object modifiedCheck = readNoArg(content, "isModifiedAfterSaving");
            if (Boolean.TRUE.equals(modifiedCheck)) {
                evidence.fail("REJ_ORIGINAL_DIRTY");
                return;
            }
            if (injectUnsupported) {
                // Census-negative variant: one art path drawable lands in
                // getAllObjects on every admitted build, so the session must
                // reject with a readable family-count detail. Glue is admitted
                // since the pass-through ruling, so it no longer counts here.
                injection = onEdt(() ->
                    injectUnsupportedSources(original, evidence, prefix));
                if (injection == null || injection.injected.isEmpty()) {
                    evidence.fail("REJ_INJECTION_FAILED");
                    return;
                }
                evidence.put(prefix + "injectedFamilies",
                    "art-path=" + injection.artPaths);
                evidence.put(prefix + "expectedDetail",
                    "protected-export.unsupported-structure:art-path="
                        + injection.artPaths);
            }
            ensureTextureAtlas(controller, evidence);
            final DocumentState before =
                snapshotDocument(original, evidence, prefix + "orig");
            evidence.put(prefix + "origFile", originalFile.getAbsolutePath());
            evidence.put(prefix + "origFileSha256", sha256(originalFile));

            // The session terminal lands as a "protected-export" line in the
            // runtime log; only lines appended after the trigger count.
            final Path turboismLog = turboismLogPath(stateDir);
            evidence.put(prefix + "runtimeLog", turboismLog.toString());
            final long logMark = Files.isRegularFile(turboismLog)
                ? Files.size(turboismLog) : 0L;

            final Set<Window> alreadyVisible = visibleWindows();
            if (!triggerExport(controller, appCtrl, evidence)) {
                return;
            }
            final JDialog outer = awaitExportSettingsDialog(
                alreadyVisible, stateDir, evidence, "rejOuter"
            );
            if (outer == null) {
                evidence.fail("REJ_OUTER_DIALOG_NOT_OBSERVED");
                return;
            }
            final List<JCheckBox> injected = injectedCheckBoxes(outer);
            evidence.put(prefix + "outerInjectedCheckBoxCount",
                Integer.toString(injected.size()));
            if (injected.isEmpty()) {
                evidence.fail("REJ_OUTER_OPTION_MISSING");
                dismiss(outer);
                return;
            }
            onEdt(() -> {
                for (JCheckBox box : injected) {
                    box.doClick(0);
                }
                return null;
            });
            evidence.put(prefix + "optionChecked", "true");
            final AbstractButton outerConfirm = findButton(outer, confirmAction());
            if (outerConfirm == null) {
                evidence.fail("REJ_OUTER_CONFIRM_MISSING");
                dismiss(outer);
                return;
            }
            onEdt(() -> {
                outerConfirm.doClick(0);
                return null;
            });
            evidence.put(prefix + "outerConfirmed", "true");
            waitForHidden(outer);

            // Await the session's own terminal line. Any inner export dialog or
            // unexpected window observed meanwhile is evidence AGAINST the
            // rejection expectation (recorded, then dismissed so the EDT and
            // the session can unwind).
            final Map<Window, Integer> seenDialogs = new LinkedHashMap<>();
            for (Window window : visibleWindows()) {
                seenDialogs.put(window, Integer.MAX_VALUE);
            }
            final List<String> unexpected = new ArrayList<>();
            final long deadline = System.currentTimeMillis() + 150_000L;
            String terminal = null;
            while (System.currentTimeMillis() < deadline && terminal == null) {
                clickSettledDialogs(seenDialogs, unexpected, stateDir, evidence,
                    prefix, "rejWait");
                terminal = sessionTerminalLine(turboismLog, logMark);
                sleep(POLL_MILLIS);
            }
            if (terminal != null) {
                // The veto surface is posted off the same terminal report but
                // can land a poll later; bound the wait so the verdict can
                // assert it actually appeared.
                final long vetoDeadline =
                    System.currentTimeMillis() + QUIESCENCE_MILLIS;
                while (!"true".equals(evidence.values.get(prefix + "vetoDialog"))
                    && System.currentTimeMillis() < vetoDeadline) {
                    clickSettledDialogs(seenDialogs, unexpected, stateDir,
                        evidence, prefix, "rejWait");
                    sleep(POLL_MILLIS);
                }
            }
            evidence.put(prefix + "unexpectedDialogs",
                String.join(" -> ", unexpected));
            if (terminal == null) {
                evidence.fail("REJ_SESSION_TERMINAL_MISSING");
                return;
            }
            evidence.put(prefix + "sessionTerminal", terminal);
            evidence.put(prefix + "sessionFailed",
                Boolean.toString(terminal.contains("reached=FAILED")));
            evidence.put(prefix + "sessionPublished",
                Boolean.toString(terminal.contains("published=true")));
            final String failure = terminalField(terminal, "failure=");
            evidence.put(prefix + "sessionFailureKey",
                failure == null ? "" : failure);
            evidence.put(prefix + "failureMatched",
                Boolean.toString(expectedFailure.equals(failure)));
            final String detail = terminalField(terminal, "detail=");
            evidence.put(prefix + "sessionDetail", detail == null ? "" : detail);
            if (injectUnsupported) {
                final String expectedDetail =
                    evidence.values.getOrDefault(prefix + "expectedDetail", "");
                final String vetoMessage =
                    evidence.values.getOrDefault(prefix + "vetoMessage", "");
                evidence.put(prefix + "detailMatched",
                    Boolean.toString(
                        !expectedDetail.isEmpty()
                            && expectedDetail.equals(detail)
                            && vetoMessage.contains(expectedDetail)));
            }

            final Object restored = readNoArg(controller, "getCurrentDoc");
            if (restored != null) {
                final DocumentState after =
                    snapshotDocument(restored, evidence, prefix + "restored");
                evidence.put(prefix + "sameLiveDocument",
                    Boolean.toString(after.docId == before.docId));
                evidence.put(prefix + "modifiedPreserved",
                    Boolean.toString(after.modified == before.modified));
                evidence.put(prefix + "undoPreserved",
                    Boolean.toString(
                        after.undoSignature.equals(before.undoSignature)));
                evidence.put(prefix + "selectionPreserved",
                    Boolean.toString(after.selectionSignature
                        .equals(before.selectionSignature)));
            }
            evidence.put(prefix + "fileSha256Preserved",
                Boolean.toString(sha256(originalFile)
                    .equals(evidence.values.get(prefix + "origFileSha256"))));
            reportStagingResidue(stateDir, evidence, prefix);
        } catch (Throwable failure) {
            evidence.fail("REJ_PHASE_FAILURE:" + failure.getClass().getName()
                + ":" + text(failure));
        } finally {
            if (injection != null) {
                try {
                    final UnsupportedInjection toRestore = injection;
                    onEdt(() -> {
                        toRestore.restore();
                        return null;
                    });
                    evidence.put(prefix + "injectionRestored", "true");
                } catch (Throwable failure) {
                    evidence.put(prefix + "injectionRestored",
                        "failed:" + text(failure));
                }
            }
        }
    }

    /**
     * One in-memory injection of an unsupported-family source into the live
     * model's drawable census list. The object is never serialized — the session
     * rejects at preflight — and {@link #restore} removes it so the authoring
     * document returns untouched.
     */
    private static UnsupportedInjection injectUnsupportedSources(
        final Object document,
        final Evidence evidence,
        final String prefix
    ) throws Exception {
        final Object source = readNoArg(document, "getModelSource");
        if (source == null) {
            return null;
        }
        final UnsupportedInjection injection = new UnsupportedInjection();
        final Object drawableSet = readNoArg(source, "getDrawableSourceSet");
        @SuppressWarnings("unchecked")
        final List<Object> drawables = drawableSet == null ? null
            : (List<Object>) readNoArg(drawableSet, "getSources");
        if (drawables == null) {
            return null;
        }
        final Class<?> artPathType = Class.forName(
            "com.live2d.cubism.doc.model.drawable.artPath.CArtPathSource");
        final java.lang.reflect.Constructor<?> artPathCtor =
            artPathType.getDeclaredConstructor();
        artPathCtor.setAccessible(true);
        final Object artPath = artPathCtor.newInstance();
        drawables.add(artPath);
        injection.injected.add(new InjectSlot(drawables, artPath));
        injection.artPaths++;
        evidence.put(prefix + "injectedCount",
            Integer.toString(injection.injected.size()));
        return injection;
    }

    private record InjectSlot(List<Object> list, Object object) {
    }

    private static final class UnsupportedInjection {
        final List<InjectSlot> injected = new ArrayList<>();
        int artPaths;

        void restore() {
            for (InjectSlot slot : injected) {
                slot.list().remove(slot.object());
            }
        }
    }

    /**
     * Builds one real Glue relation on the live document through the editor's
     * own construction sequence (same call order as the native glue-generation
     * routine): source construction binds the two target ArtMesh GUIDs, the part
     * handler registers the source into the model, a default keyform carries
     * intensity 1.0, the keyform grid is attached, and the first mesh vertex
     * pairs are bound 50/50. Must run on the EDT; failures land as evidence.
     */
    private static void injectGlueFixture(
        final Object document,
        final Evidence evidence,
        final String prefix
    ) {
        try {
            onEdtBounded(APPLY_STEP_MILLIS, () -> {
                final Object source = readNoArg(document, "getModelSource");
                final List<?> meshes =
                    source == null ? List.of() : asList(readNoArg(source, "getAllArtMeshes"));
                final List<?> parts =
                    source == null ? List.of() : asList(readNoArg(source, "getAllParts"));
                if (source == null || meshes.size() < 2 || parts.isEmpty()) {
                    evidence.fail("GEXP_FIXTURE_TOO_SMALL");
                    return null;
                }
                final Object meshA = meshes.get(0);
                final Object meshB = meshes.get(1);
                final Class<?> modelSourceType =
                    Class.forName("com.live2d.cubism.doc.model.CModelSource");
                final Class<?> artMeshType = Class.forName(
                    "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource");
                final Class<?> glueType = Class.forName(
                    "com.live2d.cubism.doc.model.affecter.glue.CGlueSource");
                final Object glue = glueType
                    .getDeclaredConstructor(modelSourceType, artMeshType, artMeshType)
                    .newInstance(source, meshA, meshB);
                invoke(glue, "setLocalName", new Class<?>[] {String.class},
                    "protected-export-probe-glue");
                final Class<?> guidType =
                    Class.forName("com.live2d.type.CAffecterGuid");
                invoke(glue, "setGuid", new Class<?>[] {guidType},
                    guidType.getDeclaredConstructor().newInstance());
                final Class<?> idType =
                    Class.forName("com.live2d.cubism.doc.model.id.CAffecterId");
                invoke(glue, "setId", new Class<?>[] {idType},
                    idType.getDeclaredConstructor(String.class)
                        .newInstance("ProtectedExportProbeGlue"));
                final Class<?> vecType =
                    Class.forName("com.live2d.graphics3d.type.GVector2");
                invoke(glue, "setTabPosOnCanvas", new Class<?>[] {vecType},
                    vecType.getDeclaredConstructor(float.class, float.class)
                        .newInstance(0f, 0f));
                // Registration: addPartChild -> setup(modelSource) lands the
                // source in the affecter set and the part's child list.
                final Class<?> controllableType = Class.forName(
                    "com.live2d.cubism.doc.model.ACParameterControllableSource");
                Object partHandler = null;
                Object part = null;
                for (Object candidate : parts) {
                    partHandler = readNoArg(candidate, "getHandler");
                    if (partHandler != null) {
                        part = candidate;
                        break;
                    }
                }
                if (partHandler == null) {
                    evidence.fail("GEXP_NO_PART_HANDLER");
                    return null;
                }
                final int childIndex =
                    asList(readNoArg(part, "getChildGuids")).size();
                invoke(partHandler, "addPartChild",
                    new Class<?>[] {controllableType, int.class}, glue, childIndex);
                final Class<?> formType = Class.forName(
                    "com.live2d.cubism.doc.model.affecter.glue.CGlueForm");
                final Class<?> glueInstanceType = Class.forName(
                    "com.live2d.cubism.doc.model.affecter.glue.CGlue");
                final Object form = formType
                    .getDeclaredConstructor(glueType, glueInstanceType)
                    .newInstance(glue, null);
                final Class<?> formGuidType =
                    Class.forName("com.live2d.type.CFormGuid");
                invoke(form, "setGuid", new Class<?>[] {formGuidType},
                    formGuidType.getDeclaredConstructor().newInstance());
                invoke(form, "setIntensity", new Class<?>[] {float.class}, 1.0f);
                invoke(readNoArg(glue, "getKeyforms"), "add",
                    new Class<?>[] {Object.class}, form);
                final Class<?> gridType = Class.forName(
                    "com.live2d.cubism.doc.model.interpolator.KeyformGridSource");
                final Object grid = gridType
                    .getDeclaredConstructor(controllableType)
                    .newInstance(glue);
                final Class<?> markerType =
                    Class.forName("com.live2d.cubism.doc.model.io.b.c");
                final Method importDefault = gridType.getDeclaredMethod(
                    "importCubism21$default", gridType, modelSourceType,
                    List.class, List.class, markerType, int.class, Object.class);
                importDefault.setAccessible(true);
                importDefault.invoke(null, grid, source, List.of(),
                    List.of(readNoArg(form, "getGuid")), null, 8, null);
                invoke(glue, "setKeyformGridSource",
                    new Class<?>[] {gridType}, grid);
                final List<?> pointsA = asList(readNoArg(meshA, "getAllPointRef"));
                final List<?> pointsB = asList(readNoArg(meshB, "getAllPointRef"));
                final int pairs =
                    Math.min(2, Math.min(pointsA.size(), pointsB.size()));
                if (pairs < 1) {
                    evidence.fail("GEXP_NO_MESH_POINTS");
                    return null;
                }
                final long[] uids = new long[pairs * 2];
                final float[] weights = new float[pairs * 2];
                for (int i = 0; i < pairs; i++) {
                    uids[i * 2] = pointUid(pointsA.get(i));
                    uids[i * 2 + 1] = pointUid(pointsB.get(i));
                    weights[i * 2] = 0.5f;
                    weights[i * 2 + 1] = 0.5f;
                }
                invoke(glue, "setBindVertexUids", new Class<?>[] {long[].class}, uids);
                invoke(glue, "setWeights", new Class<?>[] {float[].class}, weights);
                invoke(glue, "setUpdated", new Class<?>[0]);
                evidence.put(prefix + "glueInjected", "true");
                evidence.put(prefix + "glueId", "ProtectedExportProbeGlue");
                evidence.put(prefix + "glueGuid", String.valueOf(guidString(glue)));
                evidence.put(prefix + "glueName", "protected-export-probe-glue");
                evidence.put(prefix + "glueTargets", glueTargetSignature(glue));
                return null;
            });
        } catch (Throwable failure) {
            evidence.fail("GEXP_INJECTION_FAILURE:" + text(failure));
        }
    }

    /** Mesh point ref UID — the {@code MeshPointRef.b} member is package-private. */
    private static long pointUid(final Object pointRef) throws Exception {
        return (Long) invoke(pointRef, "b", new Class<?>[0]);
    }

    /** Ordered {@code guidA|guidB} signature of a Glue source's mesh references. */
    private static String glueTargetSignature(final Object glue) {
        final Object meshA = readNoArg(glue, "getTargetArtMeshA");
        final Object meshB = readNoArg(glue, "getTargetArtMeshB");
        return guidString(meshA) + "|" + guidString(meshB);
    }

    /** Finds a Glue source on the document's model by its unchanged ID. */
    private static Object findGlue(final Object document, final String id) {
        final Object source = readNoArg(document, "getModelSource");
        final Object affecterSet =
            source == null ? null : readNoArg(source, "getAffecterSourceSet");
        for (Object object : asList(readNoArg(affecterSet, "getSources"))) {
            if (isA(object.getClass(),
                    "com.live2d.cubism.doc.model.affecter.glue.CGlueSource")
                && id.equals(idString(object))) {
                return object;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Phase: native census (diagnostic, read-only)
    //
    // Enumerates the live model source through the same host members the
    // orchestrator verifies — actual object census (not XML tag counts),
    // per-mesh texture state and atlas membership, deformer parent chains,
    // and which sources bind each parameter at which key positions. The
    // r24 "Texture Atlas does not contain" warning made mesh exportability
    // dependent on textureState == TEXTURE_ATLAS, so both are recorded.
    // ------------------------------------------------------------------

    private static void phaseCensus(
        final Object controller,
        final Path stateDir,
        final Evidence evidence
    ) {
        try {
            final List<String> lines = new ArrayList<>();
            onEdtBounded(APPLY_STEP_MILLIS, () -> {
                censusCollect(controller, lines);
                return null;
            });
            Files.write(stateDir.resolve("census.txt"), lines,
                StandardCharsets.UTF_8);
            evidence.put("census.lines", Integer.toString(lines.size()));
        } catch (Throwable failure) {
            evidence.fail("CENSUS_FAILURE:" + text(failure));
        }
    }

    /** Enumerates the live model source; must run on the EDT. */
    private static void censusCollect(
        final Object controller,
        final List<String> out
    ) {
        final Object document = readNoArg(controller, "getCurrentDoc");
        final Object source =
            document == null ? null : readNoArg(document, "getModelSource");
        if (source == null) {
            out.add("error=no-model-source");
            return;
        }
        final Object allObjects = readNoArg(source, "getAllObjects");
        final Map<String, Integer> byClass = new TreeMap<>();
        final Set<String> uniqueGuids = new LinkedHashSet<>();
        if (allObjects instanceof List<?> objects) {
            for (Object object : objects) {
                byClass.merge(object.getClass().getName(), 1, Integer::sum);
                final String guid = guidString(object);
                if (guid != null) {
                    uniqueGuids.add(guid);
                }
            }
            out.add("objects.total=" + objects.size());
            out.add("objects.uniqueGuids=" + uniqueGuids.size());
            byClass.forEach((name, count) ->
                out.add("objectClass." + name + "=" + count));
        }
        // Parameter sources: evaluable contract + which sources bind them.
        final Map<String, List<String>> bindings = new TreeMap<>();
        final Map<String, String> bindingKeys = new TreeMap<>();
        final Object allParameters = readNoArg(source, "getAllParameters");
        if (allParameters instanceof List<?> parameters) {
            out.add("parameters.count=" + parameters.size());
            int index = 0;
            for (Object parameter : parameters) {
                final String id = idString(parameter);
                out.add("param." + index + ".id=" + id);
                out.add("param." + index + ".name=" + readNoArg(parameter,
                    "getName"));
                out.add("param." + index + ".min=" + readNoArg(parameter,
                    "getMinValue"));
                out.add("param." + index + ".max=" + readNoArg(parameter,
                    "getMaxValue"));
                out.add("param." + index + ".default=" + readNoArg(parameter,
                    "getDefaultValue"));
                out.add("param." + index + ".repeat=" + readNoArg(parameter,
                    "getRepeat"));
                index++;
            }
        }
        // Every controllable's keyform bindings — the ParamAngleX question is
        // which sources still reference it after flatten.
        if (allObjects instanceof List<?> objects) {
            for (Object object : objects) {
                final Object grid = readNoArg(object, "getKeyformGridSource");
                final Object bound = grid == null
                    ? null : readNoArg(grid, "getKeyformBindings");
                if (!(bound instanceof List<?> list) || list.isEmpty()) {
                    continue;
                }
                final String objectId = idString(object);
                for (Object binding : list) {
                    final String paramId =
                        idValueOf(readNoArg(binding, "getParameterId"));
                    final Object keys = readNoArg(binding, "getKeys");
                    final String keyList = keys instanceof List<?> keyList2
                        ? join(keyList2) : "?";
                    bindings.computeIfAbsent(paramId, k -> new ArrayList<>())
                        .add(objectId);
                    bindingKeys.merge(paramId, keyList,
                        (a, b) -> a.contains(b) ? a : a + "|" + b);
                }
            }
            bindings.forEach((paramId, sources) -> {
                out.add("bind." + paramId + ".sourceCount=" + sources.size());
                out.add("bind." + paramId + ".sources=" + join(sources));
                out.add("bind." + paramId + ".keys=" + bindingKeys.get(paramId));
            });
        }
        // Art meshes: texture state decides exportability.
        final Object allArtMeshes = readNoArg(source, "getAllArtMeshes");
        if (allArtMeshes instanceof List<?> meshes) {
            out.add("artMeshes.count=" + meshes.size());
            int index = 0;
            int atlasState = 0;
            for (Object mesh : meshes) {
                final String meshPrefix = "artMesh." + index + ".";
                out.add(meshPrefix + "guid="
                    + guidString(mesh));
                out.add(meshPrefix + "id="
                    + idString(mesh));
                out.add(meshPrefix + "name=" + readNoArg(mesh, "getLocalName"));
                final Object state = readNoArg(mesh, "getTextureState");
                out.add(meshPrefix + "textureState=" + state);
                if ("TEXTURE_ATLAS".equals(String.valueOf(state))) {
                    atlasState++;
                }
                final Object extension =
                    readNoArg(mesh, "getTextureInputExtension");
                final Object input = extension == null
                    ? null : readNoArg(extension, "getCurrentTextureInputData");
                out.add(meshPrefix + "textureInput=" + (input == null
                    ? "<none>" : input.getClass().getSimpleName()));
                final Object modelImageGuid = input == null
                    ? null : readNoArg(input, "getModelImageGuid");
                if (modelImageGuid != null) {
                    out.add(meshPrefix + "modelImage="
                        + uuidOf(modelImageGuid));
                }
                final Object parents =
                    readNoArg(mesh, "getAllParentDeformers");
                if (parents instanceof Iterable<?> chain) {
                    final List<String> links = new ArrayList<>();
                    for (Object deformer : chain) {
                        links.add(idString(deformer)
                            + ":" + deformer.getClass().getSimpleName());
                    }
                    out.add(meshPrefix + "deformerChain=" + join(links));
                }
                index++;
            }
            out.add("artMeshes.textureAtlasState=" + atlasState);
        }
        // Deformers: the flatten target census.
        final Object allDeformers = readNoArg(source, "getAllDeformers");
        if (allDeformers instanceof List<?> deformers) {
            out.add("deformers.count=" + deformers.size());
            int index = 0;
            for (Object deformer : deformers) {
                out.add("deformer." + index + ".guid="
                    + guidString(deformer));
                out.add("deformer." + index + ".id="
                    + idString(deformer));
                out.add("deformer." + index + ".class="
                    + deformer.getClass().getSimpleName());
                out.add("deformer." + index + ".target="
                    + targetDeformerGuid(deformer));
                index++;
            }
        }
        final Object allParts = readNoArg(source, "getAllParts");
        if (allParts instanceof List<?> parts) {
            out.add("parts.count=" + parts.size());
            int index = 0;
            for (Object part : parts) {
                out.add("part." + index + "="
                    + idString(part));
                index++;
            }
        }
        // Texture atlas census: placements are the export gate.
        final Object manager = readNoArg(source, "getTextureManager");
        final Object images = manager == null
            ? null : readNoArg(manager, "getAllModelImages");
        if (images instanceof List<?> list) {
            out.add("modelImages.count=" + list.size());
            int index = 0;
            for (Object image : list) {
                out.add("modelImage." + index + ".guid="
                    + uuidOf(readNoArg(image, "getGuid")));
                out.add("modelImage." + index + ".name="
                    + readNoArg(image, "getName"));
                out.add("modelImage." + index + ".size="
                    + readNoArg(image, "getWidth") + "x"
                    + readNoArg(image, "getHeight"));
                index++;
            }
        }
        final Object atlases = manager == null
            ? null : readNoArg(manager, "getTextureAtlases");
        if (atlases instanceof List<?> list) {
            out.add("atlases.count=" + list.size());
            int index = 0;
            for (Object atlas : list) {
                out.add("atlas." + index + ".name=" + readNoArg(atlas,
                    "getName"));
                out.add("atlas." + index + ".size="
                    + readNoArg(atlas, "getWidth") + "x"
                    + readNoArg(atlas, "getHeight"));
                final Object entries = readNoArg(atlas, "getModelImages");
                if (entries instanceof List<?> placements) {
                    out.add("atlas." + index + ".placements="
                        + placements.size());
                    int entryIndex = 0;
                    for (Object entry : placements) {
                        out.add("atlas." + index + ".placement." + entryIndex
                            + "=" + uuidOf(
                                readNoArg(entry, "getModelImageGuid")));
                        entryIndex++;
                    }
                }
                index++;
            }
        }
    }

    /** UUID text of a host Guid object (e.g. CModelImageGuid). */
    private static String uuidOf(final Object guid) {
        final Object value =
            guid == null ? null : readNoArg(guid, "getUuidString");
        return value == null ? null : value.toString();
    }

    /** Id-string text of a host Id object (e.g. CParameterId). */
    private static String idValueOf(final Object id) {
        final Object value =
            id == null ? null : readNoArg(id, "getIdString");
        return value == null ? "<null>" : value.toString();
    }

    private static String join(final List<?> items) {
        final StringBuilder joined = new StringBuilder();
        for (Object item : items) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(item);
        }
        return joined.toString();
    }

    // ------------------------------------------------------------------
    // Phase: build a task-owned fixture with a real texture atlas
    //
    // The converted fixture carries model-image inputs but no atlas
    // placements, so the exporter removes every drawable as unplaced
    // (w$a collects textureState != TEXTURE_ATLAS then deletes via
    // ModelHandler). This phase populates a real CTextureAtlas on the live
    // document — ModelImageEntry placements shelf-packed over every model
    // image — then re-links each mesh's texture input through the host's
    // own atlas-region repair and switches it to the atlas, exactly the
    // objects the serializer writes back out as a fixture.
    // ------------------------------------------------------------------

    private static void phaseAtlasFixture(
        final Object controller,
        final Path stateDir,
        final Evidence evidence
    ) {
        final String prefix = "atlas.";
        try {
            final List<String> before = new ArrayList<>();
            onEdtBounded(APPLY_STEP_MILLIS, () -> {
                censusCollect(controller, before);
                return null;
            });
            Files.write(stateDir.resolve("census-pre.txt"), before,
                StandardCharsets.UTF_8);

            final String outcome = onEdtBounded(APPLY_STEP_MILLIS,
                () -> placeAllMeshesIntoAtlas(controller, evidence, prefix));
            evidence.put(prefix + "placeOutcome", outcome);
            if (outcome.startsWith("failure:")) {
                evidence.fail("ATLAS_PLACE_FAILED");
                return;
            }

            final List<String> after = new ArrayList<>();
            onEdtBounded(APPLY_STEP_MILLIS, () -> {
                censusCollect(controller, after);
                return null;
            });
            Files.write(stateDir.resolve("census-post.txt"), after,
                StandardCharsets.UTF_8);

            final File out =
                stateDir.resolve("atlas-fixture.cmo3").toFile();
            final Boolean saved = onEdtBounded(APPLY_STEP_MILLIS, () -> {
                final Object document =
                    readNoArg(controller, "getCurrentDoc");
                final Object result = invoke(document, "saveDocument",
                    new Class<?>[] {File.class, boolean.class}, out, false);
                return Boolean.TRUE.equals(result);
            });
            evidence.put(prefix + "saved", String.valueOf(saved));
            if (Boolean.TRUE.equals(saved) && out.isFile()) {
                evidence.put(prefix + "fixture", out.getAbsolutePath());
                evidence.put(prefix + "fixtureSha256", sha256(out));
                evidence.put(prefix + "fixtureBytes",
                    Long.toString(out.length()));
            } else {
                evidence.fail("ATLAS_FIXTURE_NOT_SAVED");
            }
        } catch (Throwable failure) {
            evidence.fail("ATLAS_PHASE_FAILURE:" + text(failure));
        }
    }

    /** Populates one atlas and re-links every mesh to it; EDT only. */
    private static String placeAllMeshesIntoAtlas(
        final Object controller,
        final Evidence evidence,
        final String prefix
    ) {
        try {
            final Object document = readNoArg(controller, "getCurrentDoc");
            final Object source =
                document == null ? null : readNoArg(document, "getModelSource");
            final Object manager = source == null
                ? null : readNoArg(source, "getTextureManager");
            final Object images = manager == null
                ? null : readNoArg(manager, "getAllModelImages");
            if (!(images instanceof List<?> imageList) || imageList.isEmpty()) {
                return "failure:no-model-images";
            }
            final Object atlases = readNoArg(manager, "getTextureAtlases");
            final Class<?> atlasType = Class.forName(
                "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas");
            Object atlas = null;
            if (atlases instanceof List<?> list && !list.isEmpty()) {
                atlas = list.get(0);
            }
            if (atlas == null) {
                int widest = 0;
                long area = 0;
                for (Object image : imageList) {
                    final int width = intMember(image, "getWidth");
                    final int height = intMember(image, "getHeight");
                    widest = Math.max(widest, width);
                    area += (long) width * height;
                }
                int side = 512;
                while (side < widest || (long) side * side < area * 13 / 10) {
                    side <<= 1;
                }
                final java.lang.reflect.Constructor<?> ctor =
                    atlasType.getDeclaredConstructor(
                        Class.forName("com.live2d.cubism.doc.model.CModelSource"),
                        String.class, int.class, int.class);
                ctor.setAccessible(true);
                atlas = ctor.newInstance(source, "pe-atlas",
                    Math.min(side, 8192), Math.min(side, 8192));
                invoke(manager, "addTextureAtlas",
                    new Class<?>[] {atlasType, int.class}, atlas, 0);
            }
            // Shelf-pack every model image into real atlas placements.
            final Class<?> entryType = Class.forName(
                "com.live2d.cubism.doc.model.texture.textureAtlas."
                    + "CTextureAtlas$ModelImageEntry");
            final Class<?> affineType =
                Class.forName("com.live2d.type.CAffine");
            final Class<?> guidType =
                Class.forName("com.live2d.type.CModelImageGuid");
            final java.lang.reflect.Constructor<?> entryCtor =
                entryType.getDeclaredConstructor(
                    atlasType, guidType, affineType);
            entryCtor.setAccessible(true);
            @SuppressWarnings("unchecked")
            final List<Object> placements =
                (List<Object>) readNoArg(atlas, "getModelImages");
            final int side = intMember(atlas, "getWidth");
            int x = 0;
            int y = 0;
            int rowHeight = 0;
            int placed = 0;
            int overflow = 0;
            for (Object image : imageList) {
                final int width = intMember(image, "getWidth");
                final int height = intMember(image, "getHeight");
                if (x + width > side) {
                    x = 0;
                    y += rowHeight;
                    rowHeight = 0;
                }
                if (y + height > side) {
                    overflow++;
                    continue;
                }
                // atlasLocalToCanvas A must satisfy
                // modelImageLocalToCanvas ∘ A⁻¹ = translate(x, y), i.e.
                // A = translate(x, y)⁻¹ ∘ modelImageLocalToCanvas.
                final java.awt.geom.AffineTransform translation =
                    new java.awt.geom.AffineTransform();
                translation.setToTranslation(x, y);
                final java.awt.geom.AffineTransform transform =
                    (java.awt.geom.AffineTransform) translation.clone();
                transform.invert();
                final Object modelToCanvas = readNoArg(image,
                    "getModelImageLocalToCanvasTransform");
                if (modelToCanvas instanceof java.awt.geom.AffineTransform m2c) {
                    transform.concatenate(m2c);
                }
                final Object affine =
                    affineType.getDeclaredConstructor().newInstance();
                ((java.awt.geom.AffineTransform) affine).setTransform(transform);
                final Object entry = entryCtor.newInstance(
                    atlas, readNoArg(image, "getGuid"), affine);
                try {
                    invoke(entry, "setup", new Class<?>[0]);
                } catch (Throwable ignored) {
                    // The constructor may already have set the entry up.
                }
                placements.add(entry);
                x += width;
                rowHeight = Math.max(rowHeight, height);
                placed++;
            }
            evidence.put(prefix + "placementsAdded",
                Integer.toString(placed));
            evidence.put(prefix + "placementsOverflow",
                Integer.toString(overflow));
            // Re-link each mesh's texture input to an atlas region, then flip
            // it to TEXTURE_ATLAS — the same members the host's own relink
            // and the ArtMeshHandler atlas toggle use.
            final Class<?> relinkerType =
                Class.forName("com.live2d.cubism.doc.model.texture.r");
            final Field relinkerField =
                relinkerType.getDeclaredField("a");
            relinkerField.setAccessible(true);
            final Object relinker = relinkerField.get(null);
            final Class<?> meshType = Class.forName(
                "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource");
            final Class<?> sourceType = Class.forName(
                "com.live2d.cubism.doc.model.CModelSource");
            final Class<?> handlerType = Class.forName(
                "com.live2d.cubism.doc.model.drawable.artMesh.ArtMeshHandler");
            final java.lang.reflect.Constructor<?> handlerCtor =
                handlerType.getDeclaredConstructor(meshType);
            handlerCtor.setAccessible(true);
            final Class<?> collectorType = Class.forName(
                "com.live2d.cubism.doc.model.drawable.artMesh.ArtMeshHandler$c");
            final java.lang.reflect.Constructor<?> collectorCtor =
                collectorType.getDeclaredConstructor();
            collectorCtor.setAccessible(true);
            final Object meshes = readNoArg(source, "getAllArtMeshes");
            int atlasBound = 0;
            int meshCount = 0;
            if (meshes instanceof List<?> list) {
                meshCount = list.size();
                for (Object mesh : list) {
                    try {
                        invoke(relinker, "a",
                            new Class<?>[] {meshType, sourceType},
                            mesh, source);
                    } catch (Throwable ignored) {
                        // Region may already exist; the toggle still applies.
                    }
                    final Object handler = handlerCtor.newInstance(mesh);
                    final Object collector = collectorCtor.newInstance();
                    invoke(handler, "a",
                        new Class<?>[] {Boolean.class, collectorType},
                        Boolean.TRUE, collector);
                    if ("TEXTURE_ATLAS".equals(String.valueOf(
                            readNoArg(mesh, "getTextureState")))) {
                        atlasBound++;
                    }
                }
            }
            evidence.put(prefix + "meshCount", Integer.toString(meshCount));
            evidence.put(prefix + "atlasBoundMeshes",
                Integer.toString(atlasBound));
            // Rasterize the atlas image the same way the editor does after a
            // layout change; the export reads the cached atlas image.
            try {
                final Method update = atlasType.getDeclaredMethod(
                    "updateTexture$default", atlasType, boolean.class,
                    boolean.class,
                    Class.forName("com.live2d.util.a.a"),
                    int.class, Object.class);
                update.setAccessible(true);
                update.invoke(null, atlas, false, false, null, 7, null);
            } catch (Throwable failure) {
                evidence.put(prefix + "updateTexture", text(failure));
            }
            return "placed=" + placed + " bound=" + atlasBound
                + " overflow=" + overflow;
        } catch (Throwable failure) {
            return "failure:" + text(failure);
        }
    }

    private static int intMember(final Object target, final String name) {
        final Object value = readNoArg(target, name);
        return value instanceof Number number ? number.intValue() : 0;
    }

    // ------------------------------------------------------------------
    // Phase: unprotected native export comparison (diagnostic)
    //
    // Same dialog and chooser path as the protected run, but the contributed
    // option stays unchecked — the host writes its .moc3 directly. The output
    // is dumped through the SDK Core so protected-vs-native comparisons are
    // evidence, not inference: drawable set, parameter ids, key values,
    // min/max/default, parts.
    // ------------------------------------------------------------------

    private static void phaseExportNative(
        final Object controller,
        final Class<?> appCtrl,
        final Path stateDir,
        final Evidence evidence
    ) {
        final String prefix = "nat.";
        try {
            final Object original = readNoArg(controller, "getCurrentDoc");
            if (original == null || !isA(original.getClass(), MODELING_DOCUMENT)) {
                evidence.fail("NAT_NO_MODELING_DOCUMENT");
                return;
            }
            final Object content = readNoArg(original, "getFileContent");
            final Object fileObj =
                content == null ? null : readNoArg(content, "getFile");
            if (!(fileObj instanceof File originalFile) || !originalFile.isFile()) {
                evidence.fail("NAT_ORIGINAL_FILE_MISSING");
                return;
            }
            final Object modifiedCheck =
                readNoArg(content, "isModifiedAfterSaving");
            if (Boolean.TRUE.equals(modifiedCheck)) {
                evidence.fail("NAT_ORIGINAL_DIRTY");
                return;
            }
            ensureTextureAtlas(controller, evidence);
            final DocumentState before =
                snapshotDocument(original, evidence, prefix + "orig");
            evidence.put(prefix + "origFileSha256", sha256(originalFile));

            final Path exportOut = stateDir.resolve("export-native-out");
            Files.createDirectories(exportOut);
            final File pickFile =
                exportOut.resolve("native-export.moc3").toFile();

            final Set<Window> alreadyVisible = visibleWindows();
            if (!triggerExport(controller, appCtrl, evidence)) {
                return;
            }
            final JDialog outer = awaitExportSettingsDialog(
                alreadyVisible, stateDir, evidence, "natOuter");
            if (outer == null) {
                evidence.fail("NAT_OUTER_DIALOG_NOT_OBSERVED");
                return;
            }
            inspectSettingsDialog(outer, stateDir, evidence, "natOuter");
            evidence.put(prefix + "injectedCheckBoxCount",
                Integer.toString(injectedCheckBoxes(outer).size()));
            final AbstractButton confirm = findButton(outer, confirmAction());
            if (confirm == null) {
                evidence.fail("NAT_CONFIRM_MISSING");
                dismiss(outer);
                return;
            }
            onEdt(() -> {
                confirm.doClick(0);
                return null;
            });
            evidence.put(prefix + "outerConfirmed", "true");
            waitForHidden(outer);

            final File approved = driveToDestination(outer, pickFile,
                alreadyVisible, stateDir, evidence, prefix);
            if (approved == null) {
                return;
            }
            awaitPublished(approved, stateDir, evidence, prefix);
            final Path moc3 = approved.getName().endsWith(".moc3")
                ? approved.toPath().toAbsolutePath()
                : approved.toPath().toAbsolutePath().getParent()
                    .resolve(approved.getName() + ".moc3");
            dumpMoc3(moc3, stateDir.resolve("native-moc3.txt"),
                evidence, prefix);

            final Object restored = readNoArg(controller, "getCurrentDoc");
            evidence.put(prefix + "sameLiveDocument", Boolean.toString(
                restored != null && System.identityHashCode(restored)
                    == before.docId));
            evidence.put(prefix + "fileSha256Preserved",
                Boolean.toString(sha256(originalFile)
                    .equals(evidence.values.get(prefix + "origFileSha256"))));
        } catch (Throwable failure) {
            evidence.fail("NAT_PHASE_FAILURE:" + failure.getClass().getName()
                + ":" + text(failure));
        }
    }

    /**
     * Dumps a {@code .moc3} through the host-bundled SDK Core — parameter ids,
     * key values, ranges, defaults, drawable ids, vertex counts and position
     * hashes — so protected-vs-native diffs compare actual output bytes'
     * semantics, never assumed structure.
     */
    private static void dumpMoc3(
        final Path moc3,
        final Path out,
        final Evidence evidence,
        final String prefix
    ) {
        if (!Files.isRegularFile(moc3)) {
            evidence.put(prefix + "moc3Dump", "absent:" + moc3);
            return;
        }
        try {
            final byte[] bytes = Files.readAllBytes(moc3);
            final Class<?> mocType =
                Class.forName("com.live2d.sdk.cubism.core.CubismMoc");
            final Object moc = mocType
                .getMethod("instantiate", byte[].class)
                .invoke(null, bytes);
            final List<String> lines = new ArrayList<>();
            Object model = null;
            try {
                model = mocType.getMethod("instantiateModel").invoke(moc);
                final Object parameters = model.getClass()
                    .getMethod("getParameters").invoke(model);
                final String[] parameterIds = (String[]) parameters.getClass()
                    .getMethod("getIds").invoke(parameters);
                final float[][] keyValues = (float[][]) parameters.getClass()
                    .getMethod("getKeyValues").invoke(parameters);
                final float[] minimums = (float[]) parameters.getClass()
                    .getMethod("getMinimumValues").invoke(parameters);
                final float[] maximums = (float[]) parameters.getClass()
                    .getMethod("getMaximumValues").invoke(parameters);
                final float[] defaults = (float[]) parameters.getClass()
                    .getMethod("getDefaultValues").invoke(parameters);
                boolean[] repeats = null;
                try {
                    repeats = (boolean[]) parameters.getClass()
                        .getMethod("getParameterRepeats").invoke(parameters);
                } catch (NoSuchMethodException missing) {
                    repeats = null;
                }
                lines.add("parameters.count=" + parameterIds.length);
                for (int i = 0; i < parameterIds.length; i++) {
                    lines.add("param." + parameterIds[i] + ".keys="
                        + java.util.Arrays.toString(keyValues[i]));
                    lines.add("param." + parameterIds[i] + ".range="
                        + minimums[i] + "," + maximums[i]
                        + "," + defaults[i] + ",repeat="
                        + (repeats == null ? "unsupported" : repeats[i]));
                }
                final Object drawables = model.getClass()
                    .getMethod("getDrawables").invoke(model);
                final String[] drawableIds = (String[]) drawables.getClass()
                    .getMethod("getIds").invoke(drawables);
                final int[] vertexCounts = (int[]) drawables.getClass()
                    .getMethod("getVertexCounts").invoke(drawables);
                final float[][] positions = (float[][]) drawables.getClass()
                    .getMethod("getVertexPositions").invoke(drawables);
                final int[] textureIndices = (int[]) drawables.getClass()
                    .getMethod("getTextureIndices").invoke(drawables);
                lines.add("drawables.count=" + drawableIds.length);
                lines.add("drawables.ids=" + String.join(",", drawableIds));
                for (int i = 0; i < drawableIds.length; i++) {
                    lines.add("drawable." + drawableIds[i] + ".vertexCount="
                        + vertexCounts[i]);
                    lines.add("drawable." + drawableIds[i] + ".textureIndex="
                        + textureIndices[i]);
                    lines.add("drawable." + drawableIds[i] + ".positionsSha="
                        + sha256Floats(positions[i]));
                }
                final Object parts = model.getClass()
                    .getMethod("getParts").invoke(model);
                final String[] partIds = (String[]) parts.getClass()
                    .getMethod("getIds").invoke(parts);
                lines.add("parts.count=" + partIds.length);
                lines.add("parts.ids=" + String.join(",", partIds));
                final Object deformers = model.getClass()
                    .getMethod("getDeformers").invoke(model);
                final int deformerCount = (Integer) deformers.getClass()
                    .getMethod("getCount").invoke(deformers);
                lines.add("deformers.count=" + deformerCount);
                try {
                    final Object glues = model.getClass()
                        .getMethod("getGlues").invoke(model);
                    final String[] glueIds = (String[]) glues.getClass()
                        .getMethod("getIds").invoke(glues);
                    final int[] drawablesA = (int[]) glues.getClass()
                        .getMethod("getDrawablesA").invoke(glues);
                    final int[] drawablesB = (int[]) glues.getClass()
                        .getMethod("getDrawablesB").invoke(glues);
                    final int[] glueParameterCounts = (int[]) glues.getClass()
                        .getMethod("getParameterCounts").invoke(glues);
                    boolean glueRefsValid = true;
                    lines.add("glues.count=" + glueIds.length);
                    lines.add("glues.ids=" + String.join(",", glueIds));
                    for (int i = 0; i < glueIds.length; i++) {
                        glueRefsValid &= drawablesA[i] >= 0
                            && drawablesA[i] < drawableIds.length
                            && drawablesB[i] >= 0
                            && drawablesB[i] < drawableIds.length;
                        lines.add("glue." + glueIds[i] + ".drawableA="
                            + drawablesA[i]);
                        lines.add("glue." + glueIds[i] + ".drawableB="
                            + drawablesB[i]);
                        lines.add("glue." + glueIds[i] + ".parameterCount="
                            + glueParameterCounts[i]);
                    }
                    evidence.put(prefix + "moc3GlueIds",
                        String.join(",", glueIds));
                    evidence.put(prefix + "moc3GlueRefsValid",
                        Boolean.toString(glueRefsValid));
                } catch (NoSuchMethodException unsupported) {
                    lines.add("glues.unsupported=true");
                }
            } finally {
                if (model != null) {
                    model.getClass().getMethod("close").invoke(model);
                }
                mocType.getMethod("close").invoke(moc);
            }
            Files.write(out, lines, StandardCharsets.UTF_8);
            evidence.put(prefix + "moc3Dump", out.getFileName().toString());
            evidence.put(prefix + "moc3Sha256", sha256(moc3.toFile()));
        } catch (Throwable failure) {
            evidence.put(prefix + "moc3DumpFailure", text(failure));
        }
    }

    private static String sha256Floats(final float[] values) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (float value : values) {
                final int bits = Float.floatToIntBits(value);
                digest.update((byte) bits);
                digest.update((byte) (bits >>> 8));
                digest.update((byte) (bits >>> 16));
                digest.update((byte) (bits >>> 24));
            }
            final byte[] hash = digest.digest();
            final StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            return "sha256-unavailable";
        }
    }

    /**
     * Newest {@code <home>/logs/runtime/<date>/turboism-*.log} — the runtime
     * rotates per-session timestamped files, so the session terminal can only
     * be observed in the file this run actually opened. Falls back to the
     * legacy flat {@code logs/turboism.log} when no runtime file exists yet.
     */
    private static Path turboismLogPath(final Path stateDir) {
        final Path state = stateDir.getParent();
        final Path home = state == null ? null : state.getParent();
        if (home == null) {
            return stateDir.resolve("turboism.log");
        }
        final Path runtimeDir = home.resolve("logs").resolve("runtime");
        Path newest = null;
        try (var walk = Files.walk(runtimeDir, 2)) {
            for (Path candidate : walk.toList()) {
                final String name = candidate.getFileName() == null
                    ? "" : candidate.getFileName().toString();
                if (!Files.isRegularFile(candidate)
                    || !name.startsWith("turboism-") || !name.endsWith(".log")) {
                    continue;
                }
                if (newest == null
                    || Files.getLastModifiedTime(candidate)
                        .compareTo(Files.getLastModifiedTime(newest)) > 0) {
                    newest = candidate;
                }
            }
        } catch (IOException unavailable) {
            // Fall through to the legacy flat path.
        }
        return newest != null
            ? newest
            : home.resolve("logs").resolve("turboism.log");
    }

    /**
     * First {@code protected-export} session-terminal line appended to the
     * runtime log after {@code mark}; null while the session is still running.
     */
    private static String sessionTerminalLine(final Path log, final long mark) {
        try {
            if (!Files.isRegularFile(log) || Files.size(log) <= mark) {
                return null;
            }
            final String appended;
            try (var channel = Files.newByteChannel(log)) {
                channel.position(mark);
                final var buffer = java.nio.ByteBuffer.allocate(
                    (int) Math.min(Files.size(log) - mark, 1 << 20));
                channel.read(buffer);
                appended = new String(
                    buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            }
            for (String line : appended.split("\\R")) {
                if (line.contains("protected-export")
                    && line.contains("session=") && line.contains("reached=")) {
                    return line.strip();
                }
            }
        } catch (IOException unavailable) {
            // Keep polling until the deadline.
        }
        return null;
    }

    /** {@code key=value} field of a whitespace-delimited log line, or null. */
    private static String terminalField(final String line, final String key) {
        final int start = line.indexOf(key);
        if (start < 0) {
            return null;
        }
        final int end = line.indexOf(' ', start + key.length());
        return end < 0
            ? line.substring(start + key.length())
            : line.substring(start + key.length(), end);
    }

    /**
     * Shared disposable-copy session: snapshot the original, copy its file to a
     * task-owned name, bind it natively, run {@code mutation} while bound (null =
     * pure binding evidence), restore the original live document, and verify the
     * original's bytes, dirty flag, undo position and selection. Cleanup of the
     * copy document and file always runs.
     */
    private static void copySession(
        final Object controller,
        final Evidence evidence,
        final String prefix,
        final java.util.function.Consumer<CopyScope> mutation
    ) {
        File copyFile = null;
        Object copyDoc = null;
        try {
            final Object original = readNoArg(controller, "getCurrentDoc");
            if (original == null) {
                evidence.fail(prefix.toUpperCase() + "NO_ACTIVE_DOCUMENT");
                return;
            }
            final Object originalContent = readNoArg(original, "getFileContent");
            final Object originalFileObj =
                originalContent == null ? null : readNoArg(originalContent, "getFile");
            if (!(originalFileObj instanceof File originalFile) || !originalFile.isFile()) {
                evidence.fail(prefix.toUpperCase() + "ORIGINAL_FILE_MISSING");
                return;
            }

            final DocumentState before = snapshotDocument(original, evidence, prefix + "orig");
            evidence.put(prefix + "origFile", originalFile.getAbsolutePath());
            evidence.put(prefix + "origFileSha256", sha256(originalFile));

            copyFile = new File(
                originalFile.getParentFile(),
                "pe-copy-" + runToken() + originalFile.getName()
            );
            evidence.put(prefix + "file", copyFile.getAbsolutePath());
            Files.copy(originalFile.toPath(), copyFile.toPath());
            evidence.put(prefix + "fileCreated", "true");

            copyDoc = openAndAwaitBoundDocument(
                controller, copyFile, evidence, prefix
            );
            if (copyDoc == null) {
                evidence.fail(prefix.toUpperCase() + "OPEN_NOT_BOUND");
                return;
            }
            evidence.put(prefix + "bound", "true");
            evidence.put(prefix + "docId",
                Integer.toHexString(System.identityHashCode(copyDoc)));

            if (mutation != null) {
                final Object copyContent = readNoArg(copyDoc, "getFileContent");
                final Object copySource = readNoArg(copyDoc, "getModelSource");
                mutation.accept(new CopyScope(copyDoc, copyContent, copySource, copyFile));
            }

            // Restore: reopening the original file must reactivate the SAME live
            // document (identity), not a fresh instance.
            final Object restored = openAndAwaitBoundDocument(
                controller, originalFile, evidence, prefix
            );
            if (restored == null) {
                evidence.fail(prefix.toUpperCase() + "RESTORE_NOT_BOUND");
                return;
            }
            evidence.put(prefix + "restored", "true");
            evidence.put(
                prefix + "sameLiveDocument",
                Boolean.toString(System.identityHashCode(restored) == before.docId)
            );

            final DocumentState after =
                snapshotDocument(restored, evidence, prefix + "restored");
            evidence.put(prefix + "fileSha256Preserved",
                Boolean.toString(sha256(originalFile)
                    .equals(evidence.values.get(prefix + "origFileSha256"))));
            evidence.put(prefix + "modifiedPreserved",
                Boolean.toString(after.modified == before.modified));
            evidence.put(prefix + "undoPreserved",
                Boolean.toString(after.undoSignature.equals(before.undoSignature)));
            evidence.put(prefix + "selectionPreserved",
                Boolean.toString(after.selectionSignature.equals(before.selectionSignature)));
        } catch (Throwable failure) {
            evidence.fail(prefix.toUpperCase() + "PHASE_FAILURE:"
                + failure.getClass().getName() + ":" + text(failure));
        } finally {
            if (copyDoc != null) {
                try {
                    closeDocument(controller, copyDoc, evidence, prefix);
                } catch (Throwable failure) {
                    evidence.put(prefix + "closeFailure", text(failure));
                }
            }
            if (copyFile != null && copyFile.isFile()) {
                // The native closeFile releases the file asynchronously through the
                // doc.a.e file-handle cache (loader null + guarded release). Dump and
                // force-release any lingering handle for the copy path first, then
                // retry the delete while the channel drains.
                dumpFileCache(copyFile, evidence, prefix + "cacheBefore.");
                releaseFileCache(copyFile, evidence, prefix);
                int attempts = 0;
                Throwable last = null;
                while (attempts < 30 && copyFile.isFile()) {
                    attempts++;
                    try {
                        copyFile.setWritable(true, false);
                        Files.deleteIfExists(copyFile.toPath());
                    } catch (Throwable failure) {
                        last = failure;
                    }
                    if (!copyFile.isFile()) {
                        break;
                    }
                    System.gc();
                    System.runFinalization();
                    sleep(500L);
                }
                if (copyFile.isFile()) {
                    dumpFileCache(copyFile, evidence, prefix + "cacheAfter.");
                    evidence.put(prefix + "fileRemoveFailure",
                        last == null ? "still present"
                            : last.getClass().getName() + ":" + text(last));
                } else {
                    evidence.put(prefix + "fileRemoved", "true");
                    evidence.put(prefix + "fileRemoveAttempts", Integer.toString(attempts));
                }
            } else if (copyFile != null) {
                evidence.put(prefix + "fileRemoved", "true");
                evidence.put(prefix + "fileRemoveAttempts", "0");
            }
        }
    }

    /**
     * Drives the exact native apply-to-children path on the bound copy for every
     * supported deformer, leaf-to-root. Mirrors the production guard: after the
     * sendEvent=true selection call the document, model source, model instance
     * and the re-resolved target must all be unchanged, and the target GUID must
     * be absent after the native command.
     */
    private static void flattenSupportedDeformers(
        final Object controller,
        final CopyScope scope,
        final Evidence evidence
    ) {
        if (scope.modelSource == null) {
            evidence.fail("FLAT_NO_MODEL_SOURCE");
            return;
        }
        final Object modelInstance = readNoArg(scope.modelSource, "getCurrentInstance");

        // Unsupported families reject before any mutation: Glue, ArtPath and
        // Affecter sources are not flattenable by this feature. Aliases are ID
        // aliases only — recorded as census evidence, not a reject condition.
        for (String probe : List.of("getAllGlues", "getAllArtPaths",
            "getAllAffecters")) {
            final int count = countOf(scope.modelSource, probe);
            evidence.put("flat.census." + probe, Integer.toString(count));
            if (count != 0) {
                evidence.fail("FLAT_UNSUPPORTED_FAMILY:" + probe + "=" + count);
                return;
            }
        }
        evidence.put("flat.census.getAllAliases",
            Integer.toString(countOf(scope.modelSource, "getAllAliases")));

        evidence.put("flat.partsBefore", structureSignature(scope.modelSource, "getAllParts"));
        evidence.put("flat.paramsBefore",
            structureSignature(scope.modelSource, "getAllParameters"));
        evidence.put("flat.artMeshesBefore",
            structureSignature(scope.modelSource, "getAllArtMeshes"));

        final List<DeformerRef> plan = planDeformers(scope.modelSource, evidence);
        if (plan == null) {
            return;
        }
        evidence.put("flat.planSize", Integer.toString(plan.size()));
        final StringBuilder order = new StringBuilder();
        for (DeformerRef ref : plan) {
            order.append(order.length() == 0 ? "" : ",").append(ref.guid);
        }
        evidence.put("flat.plan", order.toString());

        for (int i = 0; i < plan.size(); i++) {
            applyOneDeformer(controller, scope, modelInstance, plan.get(i), evidence, i);
            if (evidence.error != null) {
                return;
            }
        }

        final int remaining = countOf(scope.modelSource, "getAllDeformers");
        evidence.put("flat.deformersAfter", Integer.toString(remaining));
        evidence.put("flat.zeroDeformers", Boolean.toString(remaining == 0));
        evidence.put("flat.partsPreserved", Boolean.toString(
            structureSignature(scope.modelSource, "getAllParts")
                .equals(evidence.values.get("flat.partsBefore"))));
        evidence.put("flat.paramsPreserved", Boolean.toString(
            structureSignature(scope.modelSource, "getAllParameters")
                .equals(evidence.values.get("flat.paramsBefore"))));
        evidence.put("flat.artMeshesPreserved", Boolean.toString(
            structureSignature(scope.modelSource, "getAllArtMeshes")
                .equals(evidence.values.get("flat.artMeshesBefore"))));
    }

    /** One deformer in the deterministic leaf-to-root plan. */
    private static final class DeformerRef {
        final String guid;
        final String kind;
        final String name;
        final int depth;

        DeformerRef(final String guid, final String kind, final String name, final int depth) {
            this.guid = guid;
            this.kind = kind;
            this.name = name;
            this.depth = depth;
        }
    }

    private static final Set<String> SUPPORTED_DEFORMER_SOURCES = Set.of(
        "com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerSource",
        "com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerSource"
    );

    /**
     * Builds the deterministic leaf-to-root order over stable GUIDs. Duplicate
     * GUIDs, a target-deformer GUID absent from the source set, a parent cycle,
     * or an unsupported deformer family all reject before any mutation (AC03).
     */
    private static List<DeformerRef> planDeformers(
        final Object modelSource,
        final Evidence evidence
    ) {
        final List<?> all = asList(readNoArg(modelSource, "getAllDeformers"));
        final Map<String, Object> byGuid = new LinkedHashMap<>();
        final Map<String, String> parentOf = new LinkedHashMap<>();
        final Map<String, String> kindOf = new LinkedHashMap<>();
        final Map<String, String> nameOf = new LinkedHashMap<>();
        for (Object source : all) {
            final String guid = guidString(source);
            final String kind = source == null ? "<null>" : source.getClass().getName();
            if (!SUPPORTED_DEFORMER_SOURCES.contains(kind)) {
                evidence.fail("FLAT_UNSUPPORTED_FAMILY:" + kind);
                return null;
            }
            if (guid == null || guid.isBlank()) {
                evidence.fail("FLAT_DEFORMER_GUID_MISSING:" + kind);
                return null;
            }
            if (byGuid.putIfAbsent(guid, source) != null) {
                evidence.fail("FLAT_DUPLICATE_GUID:" + guid);
                return null;
            }
            kindOf.put(guid, kind);
            nameOf.put(guid, String.valueOf(readNoArg(source, "getLocalName")));
            parentOf.put(guid, targetDeformerGuid(source));
        }

        // A deformer's targetDeformerGuid is its parent edge; roots anchor to a
        // non-deformer (model root / part) or to nothing. A parent GUID that
        // resolves to a deformer missing from the census is a genuine gap and
        // rejects; a GUID resolving to a non-deformer object or the model root is
        // a legitimate root anchor. A GUID resolving nowhere is a stale edge left
        // by a deleted parent — the native apply walks downward from each
        // deformer via getDeformerChildren, so an unresolvable parent edge has no
        // ancestor to order against and the deformer is a root.
        final Map<String, Object> objectByGuid = objectGuidMap(modelSource);
        final String modelGuid = guidString(modelSource);
        final Map<String, Integer> depthOf = new LinkedHashMap<>();
        for (String guid : byGuid.keySet()) {
            final Set<String> visiting = new LinkedHashSet<>();
            String cursor = guid;
            int depth = 0;
            while (cursor != null) {
                if (!visiting.add(cursor)) {
                    evidence.fail("FLAT_DEFORMER_CYCLE:" + cursor);
                    return null;
                }
                final String parent = parentOf.get(cursor);
                if (parent == null || parent.isBlank()) {
                    break;
                }
                if (!byGuid.containsKey(parent)) {
                    final Object resolved = objectByGuid.get(parent);
                    final String kind = parent.equals(modelGuid)
                        ? "model-root"
                        : resolved == null
                            ? "dangling-root"
                            : resolved.getClass().getName();
                    evidence.put("flat.parentAnchor." + cursor, parent + "=" + kind);
                    if (resolved instanceof Object && isDeformerSource(resolved)) {
                        evidence.fail("FLAT_MISSING_PARENT:" + cursor + "->" + parent);
                        return null;
                    }
                    break;
                }
                depth++;
                cursor = parent;
            }
            depthOf.put(guid, depth);
        }

        final List<DeformerRef> plan = new ArrayList<>();
        for (String guid : byGuid.keySet()) {
            plan.add(new DeformerRef(guid, kindOf.get(guid), nameOf.get(guid), depthOf.get(guid)));
        }
        plan.sort((a, b) -> a.depth != b.depth
            ? Integer.compare(b.depth, a.depth) : a.guid.compareTo(b.guid));
        return plan;
    }

    private static void applyOneDeformer(
        final Object controller,
        final CopyScope scope,
        final Object modelInstance,
        final DeformerRef target,
        final Evidence evidence,
        final int step
    ) {
        final String key = "flat.step" + step;
        evidence.put(key + ".guid", target.guid);
        evidence.put(key + ".kind", target.kind);
        evidence.put(key + ".name", target.name);
        try {
            onEdtBounded(APPLY_STEP_MILLIS, () -> {
                final Object source = resolveDeformer(scope.modelSource, target.guid);
                if (source == null) {
                    throw new IllegalStateException("target deformer GUID absent before apply");
                }
                // The command reads getCurrentEditMode().getSelector()'s _selected list
                // (CModelingSelector_Main.getSelectedDeformers). Select directly on that
                // selector instead of the async SelectedObjectSyncManager broadcast, which
                // silently drops when the current view context is not a modeling view.
                final Object editMode = readNoArg(scope.document, "getCurrentEditMode");
                final Object selector = readNoArg(scope.document, "getSelector");
                if (editMode == null || selector == null) {
                    throw new IllegalStateException("edit mode or selector unavailable");
                }
                invoke(selector, "clearSelection", new Class<?>[0]);
                invokeSelectorAdd(selector, source);

                if (readNoArg(controller, "getCurrentDoc") != scope.document) {
                    throw new IllegalStateException("active document changed during selection");
                }
                if (readNoArg(scope.document, "getModelSource") != scope.modelSource) {
                    throw new IllegalStateException("model source changed during selection");
                }
                if (readNoArg(scope.modelSource, "getCurrentInstance") != modelInstance) {
                    throw new IllegalStateException("model instance changed during selection");
                }
                if (resolveDeformer(scope.modelSource, target.guid) == null) {
                    throw new IllegalStateException("target deformer GUID lost during selection");
                }
                final List<?> selectedBefore = asList(readNoArg(selector, "getSelectedDeformers"));
                boolean landed = false;
                for (Object item : selectedBefore) {
                    if (target.guid.equals(guidString(item))) {
                        landed = true;
                    }
                }
                if (!landed) {
                    throw new IllegalStateException(
                        "deformer selection did not land in getSelectedDeformers: "
                            + selectedBefore.size());
                }

                // Invoke on the edit-mode instance: the command body only needs the
                // selector and model; the ar.V dispatch's view-context guards are UI-level
                // routing, not model semantics.
                invoke(editMode, "command_deleteDeformerAndSetParam", new Class<?>[0]);

                if (resolveDeformer(scope.modelSource, target.guid) != null) {
                    final List<?> selectedAfter =
                        asList(readNoArg(selector, "getSelectedDeformers"));
                    throw new IllegalStateException("target deformer GUID still present after apply"
                        + " (editMode=" + editMode.getClass().getName()
                        + ",selectedDeformers=" + selectedBefore.size() + "->" + selectedAfter.size()
                        + ")");
                }
                return null;
            });
            evidence.put(key + ".applied", "true");
        } catch (Throwable failure) {
            evidence.put(key + ".failure", text(failure));
            evidence.fail("FLAT_STEP_FAILED:" + target.guid + ":" + text(failure));
        }
    }

    /** Re-resolves a deformer source by GUID; exactly one match or null. */
    private static Object resolveDeformer(final Object modelSource, final String guid) {
        Object found = null;
        for (Object candidate : asList(readNoArg(modelSource, "getAllDeformers"))) {
            if (guid.equals(guidString(candidate))) {
                if (found != null) {
                    throw new IllegalStateException("duplicate deformer GUID at apply time");
                }
                found = candidate;
            }
        }
        return found;
    }

    /** GUID→object over {@code getAllObjects} — used to classify deformer parents. */
    private static Map<String, Object> objectGuidMap(final Object modelSource) {
        final Map<String, Object> map = new LinkedHashMap<>();
        for (Object object : asList(readNoArg(modelSource, "getAllObjects"))) {
            final String guid = guidString(object);
            if (guid != null) {
                map.putIfAbsent(guid, object);
            }
        }
        return map;
    }

    private static boolean isDeformerSource(final Object object) {
        return object != null && isA(
            object.getClass(), "com.live2d.cubism.doc.model.deformer.ACDeformerSource");
    }

    private static String guidString(final Object parameterControllableSource) {
        if (parameterControllableSource == null) {
            return null;
        }
        final Object guid = readNoArg(parameterControllableSource, "getGuid");
        final Object value = guid == null ? null : readNoArg(guid, "getUuidString");
        return value == null ? null : value.toString();
    }

    private static String targetDeformerGuid(final Object source) {
        final Object target = readNoArg(source, "getTargetDeformerGuid");
        final Object value = target == null ? null : readNoArg(target, "getUuidString");
        return value == null ? null : value.toString();
    }

    private static int countOf(final Object owner, final String getter) {
        return asList(readNoArg(owner, getter)).size();
    }

    private static List<?> asList(final Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof Iterable<?> iterable) {
            final List<Object> collected = new ArrayList<>();
            for (Object item : iterable) {
                collected.add(item);
            }
            return collected;
        }
        return List.of();
    }

    /**
     * Stable structural signature over a model-source collection: one
     * {@code guid|id|name} record per member, sorted so reordering alone is
     * observable but not fatal to comparison.
     */
    private static String structureSignature(final Object modelSource, final String getter) {
        final List<String> entries = new ArrayList<>();
        for (Object member : asList(readNoArg(modelSource, getter))) {
            final String id = idString(member);
            final String name = String.valueOf(readNoArg(member, "getLocalName"));
            entries.add(guidString(member) + "|" + id + "|" + name);
        }
        java.util.Collections.sort(entries);
        return entries.size() + ":" + String.join(",", entries);
    }

    private static String idString(final Object parameterControllableSource) {
        final Object id = readNoArg(parameterControllableSource, "getId");
        final Object value = id == null ? null : readNoArg(id, "getIdString");
        return value == null ? "?" : value.toString();
    }

    /** What a bound disposable copy exposes to a mutation step. */
    private static final class CopyScope {
        final Object document;
        final Object content;
        final Object modelSource;
        final File file;

        CopyScope(final Object document, final Object content,
            final Object modelSource, final File file) {
            this.document = document;
            this.content = content;
            this.modelSource = modelSource;
            this.file = file;
        }
    }

    /** Identity + state snapshot used to prove the original document is untouched. */
    private static final class DocumentState {
        final int docId;
        final boolean modified;
        final String undoSignature;
        final String selectionSignature;

        DocumentState(final int docId, final boolean modified,
            final String undoSignature, final String selectionSignature) {
            this.docId = docId;
            this.modified = modified;
            this.undoSignature = undoSignature;
            this.selectionSignature = selectionSignature;
        }
    }

    private static DocumentState snapshotDocument(
        final Object document,
        final Evidence evidence,
        final String prefix
    ) {
        final int docId = System.identityHashCode(document);
        final Object content = readNoArg(document, "getFileContent");
        final Object modifiedObj =
            content == null ? null : readNoArg(content, "isModifiedAfterSaving");
        final boolean modified = Boolean.TRUE.equals(modifiedObj);
        final String undo = undoSignature(document);
        final String selection = selectionSignature(document);
        evidence.put(prefix + ".docId", Integer.toHexString(docId));
        evidence.put(prefix + ".modified", Boolean.toString(modified));
        evidence.put(prefix + ".undo", undo);
        evidence.put(prefix + ".selection", selection);
        // Settings census: physics and motion-sync sources live outside
        // getAllObjects, so the plan gate counts them separately. Recording
        // them on every snapshot distinguishes genuine document content from
        // anything a copy/open round-trip might synthesize.
        final Object modelSource = readNoArg(document, "getModelSource");
        evidence.put(prefix + ".physicsSettings",
            modelSource == null ? "none"
                : Integer.toString(countOf(modelSource, "getAllPhysicsSettings")));
        evidence.put(prefix + ".motionSyncSettings",
            modelSource == null ? "none"
                : Integer.toString(countOf(modelSource, "getAllMotionSyncSettings")));
        return new DocumentState(docId, modified, undo, selection);
    }

    private static String undoSignature(final Object document) {
        try {
            final Object undo = readNoArg(document, "getUndoManager");
            if (undo == null) {
                return "none";
            }
            final Object pos = readNoArg(undo, "getCurrentPos");
            final Object count = readNoArg(undo, "getEditCount");
            final Object canUndo = readNoArg(undo, "canUndo");
            return "pos=" + pos + ",edits=" + count + ",canUndo=" + canUndo;
        } catch (Throwable failure) {
            return "unavailable:" + text(failure);
        }
    }

    private static String selectionSignature(final Object document) {
        try {
            final Object selector = readNoArg(document, "getSelector");
            if (selector == null) {
                return "none";
            }
            final Object count = readNoArg(selector, "getSelectedCount");
            final Object selected = readNoArg(selector, "getSelected");
            final int identities =
                selected instanceof List<?> list ? listIdentityHash(list) : -1;
            return "count=" + count + ",ids=" + Integer.toHexString(identities);
        } catch (Throwable failure) {
            return "unavailable:" + text(failure);
        }
    }

    private static int listIdentityHash(final List<?> items) {
        int hash = 1;
        for (Object item : items) {
            hash = 31 * hash + System.identityHashCode(item);
        }
        return hash;
    }

    /**
     * Invokes {@code command_open(File, boolean)} on the EDT and waits until the
     * active document's backing file is the requested file. Fire-and-forget like
     * {@code triggerExport}: the command can block on a modal, so the probe must
     * not wait for the call itself. Returns the bound document, or {@code null}
     * if the host never bound it.
     */
    private static Object openAndAwaitBoundDocument(
        final Object controller,
        final File file,
        final Evidence evidence,
        final String prefix
    ) {
        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    invoke(controller, "command_open",
                        new Class<?>[] {File.class, boolean.class}, file, Boolean.FALSE);
                } catch (Throwable failure) {
                    System.out.println(
                        "PROTECTED_EXPORT_OPEN_FAILURE " + text(failure));
                }
            });
        } catch (Throwable failure) {
            evidence.put(prefix + ".openFailure", text(failure));
            return null;
        }
        final String wanted = canonical(file);
        final long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MILLIS;
        Object lastDoc = null;
        while (System.currentTimeMillis() < deadline) {
            lastDoc = readNoArg(controller, "getCurrentDoc");
            if (lastDoc != null) {
                final Object content = readNoArg(lastDoc, "getFileContent");
                final Object bound =
                    content == null ? null : readNoArg(content, "getFile");
                if (bound instanceof File boundFile && wanted.equals(canonical(boundFile))) {
                    evidence.put(prefix + ".boundPath", boundFile.getAbsolutePath());
                    return lastDoc;
                }
            }
            sleep(POLL_MILLIS);
        }
        if (lastDoc != null) {
            final Object content = readNoArg(lastDoc, "getFileContent");
            final Object bound = content == null ? null : readNoArg(content, "getFile");
            evidence.put(prefix + ".lastBoundPath", String.valueOf(bound));
        }
        return null;
    }

    private static void closeDocument(
        final Object controller,
        final Object document,
        final Evidence evidence,
        final String prefix
    ) throws Exception {
        final Object content = readNoArg(document, "getFileContent");
        if (content == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                // A flattened copy is dirty: IFileContent.saveIfModified would
                // show a modal UUOption prompt inside command_closeFileContent,
                // blocking this runnable so the document never detaches. The
                // copy is disposable — marking it saved takes the discard path.
                invoke(document, "setLastSavedTime",
                    new Class<?>[] {long.class}, Long.MAX_VALUE);
                invokeByName(controller, "command_closeFileContent", content);
            } catch (Throwable failure) {
                System.out.println(
                    "PROTECTED_EXPORT_CLOSE_FAILURE " + text(failure));
            }
        });
        // Close completion is observed through the project: IFileContent.closeFile
        // removes the document from CEProject.getChildren(), so the copy is closed
        // once it is no longer a project entry. getFileContentDocs() is not usable
        // here — for a modeling document it always returns itself. Focus moving
        // away is recorded as corroborating evidence, not the gate.
        final long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final boolean detached = !projectContains(controller, document);
            final Object current = readNoArg(controller, "getCurrentDoc");
            final boolean unfocused = current == null
                || System.identityHashCode(current) != System.identityHashCode(document);
            if (detached) {
                evidence.put(prefix + "closed", "true");
                evidence.put(prefix + "closeState",
                    "projectDetached=" + detached + ",unfocused=" + unfocused);
                return;
            }
            sleep(POLL_MILLIS);
        }
        evidence.put(prefix + "closed", "timeout");
        evidence.put(prefix + "closeState", "projectDetached=false");
    }

    /**
     * Records every doc.a.e file-cache entry whose file matches {@code file},
     * including its class, loader-null state, and listener count — the guarded
     * {@code b.b.a(true)} release refuses to run while listeners remain.
     */
    private static void dumpFileCache(
        final File file,
        final Evidence evidence,
        final String prefix
    ) {
        try {
            final Object cache = fileCache();
            final Object handles = cache == null ? null : readNoArg(cache, "a");
            if (!(handles instanceof List<?> list)) {
                evidence.put(prefix + "count", "unavailable");
                return;
            }
            int index = 0;
            for (Object handle : list) {
                final Object path = readNoArg(handle, "a");
                if (!(path instanceof File handleFile) || !handleFile.equals(file)) {
                    continue;
                }
                final Object loader = readNoArg(handle, "b");
                final Object listeners = readNoArg(handle, "d");
                evidence.put(prefix + index,
                    handle.getClass().getName()
                        + "|loader=" + (loader == null ? "null" : loader.getClass().getName())
                        + "|listeners=" + (listeners instanceof List<?> l ? l.size() : "?"));
                index++;
            }
            evidence.put(prefix + "count", Integer.toString(index));
        } catch (Throwable failure) {
            evidence.put(prefix + "count", "failed:" + text(failure));
        }
    }

    /**
     * Force-releases the doc.a.e file-handle entry for {@code file}: nulls its
     * loader, runs the guarded {@code a(true)} release, and removes it from the
     * cache when the guard still refuses (lingering listeners).
     */
    private static void releaseFileCache(
        final File file,
        final Evidence evidence,
        final String prefix
    ) {
        try {
            final Object cache = fileCache();
            if (cache == null) {
                return;
            }
            final Object handles = readNoArg(cache, "a");
            if (!(handles instanceof List<?> list)) {
                return;
            }
            int released = 0;
            for (Object handle : new ArrayList<>(list)) {
                final Object path = readNoArg(handle, "a");
                if (!(path instanceof File handleFile) || !handleFile.equals(file)) {
                    continue;
                }
                try {
                    invoke(handle, "e", new Class<?>[0]);
                } catch (Throwable ignored) {
                }
                try {
                    invoke(handle, "a", new Class<?>[] {boolean.class}, true);
                } catch (Throwable ignored) {
                }
                try {
                    invoke(cache, "b", new Class<?>[] {
                        Class.forName("com.live2d.cubism.doc.a.b.a")
                    }, handle);
                    released++;
                } catch (Throwable ignored) {
                }
            }
            evidence.put(prefix + "cacheForceReleased", Integer.toString(released));
        } catch (Throwable failure) {
            evidence.put(prefix + "cacheForceReleased", "failed:" + text(failure));
        }
    }

    /** The doc.a.e file-handle cache singleton (static field {@code a}). */
    private static Object fileCache() {
        try {
            final Class<?> type = Class.forName("com.live2d.cubism.doc.a.e");
            final java.lang.reflect.Field field = type.getDeclaredField("a");
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable failure) {
            return null;
        }
    }

    /** True while {@code document} is still a child of the current project. */
    private static boolean projectContains(final Object controller, final Object document) {
        final Object project = readNoArg(controller, "getCurrentProject");
        final Object children =
            project == null ? null : readNoArg(project, "getChildren");
        if (!(children instanceof List<?> list)) {
            return true;
        }
        for (Object child : list) {
            if (System.identityHashCode(child) == System.identityHashCode(document)) {
                return true;
            }
        }
        return false;
    }

    /**
     * After a decision the native flow is finished: any new window inside the quiescence window
     * is unexpected and recorded. Nothing here is dismissed — a leftover window is evidence.
     */
    private static List<String> awaitQuiescence(
        final Set<Window> before,
        final JDialog settings
    ) {
        final Set<Window> seen = new LinkedHashSet<>(before);
        seen.add(settings);
        final List<String> unexpected = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + QUIESCENCE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            for (Window window : visibleWindows()) {
                if (seen.contains(window)) {
                    continue;
                }
                seen.add(window);
                unexpected.add(describe(window));
            }
            sleep(POLL_MILLIS);
        }
        return unexpected;
    }

    /**
     * Waits for the veto diagnostic after a checked confirmation. An unwired
     * veto is posted inside the decide gate; an armed session's terminal failure
     * posts it off the worker, so the bound is generous. Once the veto is
     * recorded and dismissed, only a short quiescence tail keeps collecting
     * unexpected windows.
     */
    private static void awaitVetoDialog(
        final Set<Window> before,
        final JDialog settings,
        final List<String> unexpected,
        final Path stateDir,
        final Evidence evidence,
        final String keyPrefix,
        final String dumpTag
    ) {
        final Set<Window> seen = new LinkedHashSet<>(before);
        seen.add(settings);
        long deadline = System.currentTimeMillis() + VETO_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            for (Window window : visibleWindows()) {
                if (seen.contains(window)) {
                    continue;
                }
                seen.add(window);
                if (isVetoDialog(window)) {
                    recordVetoDialog((java.awt.Dialog) window, stateDir, evidence,
                        keyPrefix, dumpTag);
                    dismiss((java.awt.Dialog) window);
                    deadline = Math.min(
                        deadline, System.currentTimeMillis() + QUIESCENCE_MILLIS);
                    continue;
                }
                unexpected.add(describe(window));
                dumpTree(stateDir.resolve(
                    "dialog-tree-" + dumpTag + "-unexpected-" + unexpected.size() + ".txt"),
                    window);
            }
            sleep(POLL_MILLIS);
        }
    }

    /**
     * Whether the window is the runtime's user-visible veto diagnostic
     * ({@code ExportSettingsVetoDialog}) — the surface that makes a checked
     * rejection visible instead of silent.
     */
    static boolean isVetoDialog(final Window window) {
        return window instanceof JDialog dialog
            && VETO_DIALOG_NAME.equals(dialog.getName());
    }

    /**
     * Records the veto dialog's diagnostic as evidence. The pane's message is
     * the bounded failure identity; its first config-key-shaped line is the
     * veto key itself.
     */
    private static void recordVetoDialog(
        final java.awt.Dialog dialog,
        final Path stateDir,
        final Evidence evidence,
        final String keyPrefix,
        final String dumpTag
    ) {
        String message = "";
        for (Component component : allComponents(dialog)) {
            if (component instanceof javax.swing.JOptionPane pane
                && pane.getMessage() != null) {
                message = String.valueOf(pane.getMessage());
                break;
            }
        }
        evidence.put(keyPrefix + "Dialog", "true");
        evidence.put(keyPrefix + "Key", vetoKeyFromMessage(message));
        evidence.put(keyPrefix + "Message",
            message.replace('\r', ' ').replace('\n', '|'));
        dumpTree(stateDir.resolve("dialog-tree-veto-" + dumpTag + ".txt"), dialog);
    }

    /**
     * Extracts the veto key from the diagnostic message: the first line that is
     * exactly a lowercase config key (the intro sentence and detail payloads
     * never match).
     */
    static String vetoKeyFromMessage(final String message) {
        for (String line : message.split("\\R")) {
            final String trimmed = line.trim();
            if (trimmed.matches("[a-z0-9][a-z0-9._-]*")) {
                return trimmed;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // Bridge observation
    // ------------------------------------------------------------------

    /**
     * Replaces the three bridge callbacks in {@code System.getProperties()} with delegates that
     * count invocations and then forward to the installed callback. The transformed bytecode
     * resolves the property fresh on every call, so a wrap installed before the first trigger
     * observes the whole run.
     */
    private static BridgeObservation wrapBridgeCallbacks(final Evidence evidence) {
        final BridgeObservation observation = new BridgeObservation();
        // The bridge installs during agent premain, which can race this probe thread;
        // poll briefly so a late install is still observed rather than read as absent.
        final long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            final Properties probe = System.getProperties();
            synchronized (probe) {
                if (probe.get(ATTACH_KEY) != null && probe.get(DECIDE_KEY) != null) {
                    break;
                }
            }
            sleep(POLL_MILLIS);
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            final Object attach = properties.get(ATTACH_KEY);
            final Object cancel = properties.get(CANCEL_KEY);
            final Object decide = properties.get(DECIDE_KEY);
            evidence.put("bridgeAttachInstalled", Boolean.toString(attach != null));
            evidence.put("bridgeCancelInstalled", Boolean.toString(cancel != null));
            evidence.put("bridgeDecideInstalled", Boolean.toString(decide != null));
            if (attach instanceof java.util.function.BiFunction<?, ?, ?> original) {
                @SuppressWarnings("unchecked")
                final java.util.function.BiFunction<Object, Object, Object> delegate =
                    (java.util.function.BiFunction<Object, Object, Object>) original;
                properties.put(ATTACH_KEY, (java.util.function.BiFunction<Object, Object, Object>)
                    (owner, container) -> {
                        observation.attachCalls++;
                        observation.attachOwnerClass =
                            owner == null ? "<null>" : owner.getClass().getName();
                        observation.attachContainerClass =
                            container == null ? "<null>" : container.getClass().getName();
                        final Object result = delegate.apply(owner, container);
                        if (container instanceof Container target) {
                            observation.attachProducedBoxes = injectedCheckBoxes(target).size();
                        }
                        return result;
                    });
                observation.attachWrapped = true;
            }
            if (cancel instanceof java.util.function.Consumer<?> original) {
                @SuppressWarnings("unchecked")
                final java.util.function.Consumer<Object> delegate =
                    (java.util.function.Consumer<Object>) original;
                properties.put(CANCEL_KEY, (java.util.function.Consumer<Object>) owner -> {
                    observation.cancelCalls++;
                    delegate.accept(owner);
                });
                observation.cancelWrapped = true;
            }
            if (decide instanceof java.util.function.Function<?, ?> original) {
                @SuppressWarnings("unchecked")
                final java.util.function.Function<Object, Object> delegate =
                    (java.util.function.Function<Object, Object>) original;
                properties.put(DECIDE_KEY, (java.util.function.Function<Object, Object>) owner -> {
                    observation.decideCalls++;
                    final Object result = delegate.apply(owner);
                    observation.decideResults.add(String.valueOf(result));
                    return result;
                });
                observation.decideWrapped = true;
            }
        }
        evidence.put("bridgeWrapped", observation.summary());
        return observation;
    }

    /** Invocation counters for the wrapped bridge callbacks. */
    private static final class BridgeObservation {
        private int attachCalls;
        private int attachProducedBoxes = -1;
        private int cancelCalls;
        private int decideCalls;
        private String attachOwnerClass = "";
        private String attachContainerClass = "";
        private final List<String> decideResults = new ArrayList<>();
        private boolean attachWrapped;
        private boolean cancelWrapped;
        private boolean decideWrapped;

        private String summary() {
            return "attach=" + attachWrapped + ",cancel=" + cancelWrapped
                + ",decide=" + decideWrapped;
        }

        private void report(final Evidence evidence) {
            evidence.put("bridgeAttachCalls", Integer.toString(attachCalls));
            evidence.put("bridgeAttachOwnerClass", attachOwnerClass);
            evidence.put("bridgeAttachContainerClass", attachContainerClass);
            evidence.put("bridgeAttachProducedBoxes", Integer.toString(attachProducedBoxes));
            evidence.put("bridgeCancelCalls", Integer.toString(cancelCalls));
            evidence.put("bridgeDecideCalls", Integer.toString(decideCalls));
            evidence.put("bridgeDecideResults", String.join(",", decideResults));
        }
    }

    // ------------------------------------------------------------------
    // Preconditions and triggering
    // ------------------------------------------------------------------

    private static void prepare(final Path stateDir, final Evidence evidence) throws IOException {
        Files.createDirectories(stateDir);
        evidence.put("probeStateDir", stateDir.toString());
        evidence.put("hostVersion", System.getProperty("turboism.validation.hostVersion", ""));
    }

    private static boolean awaitHostReady(
        final Instrumentation instrumentation,
        final Path stateDir,
        final Evidence evidence
    ) {
        // stateDir is <home>/state/<pluginId>; the runtime report lives beside it under state/runtime.
        final Path report = stateDir.getParent().resolve("runtime/preview-runtime-report.json");
        final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final String reportText = readText(report).replaceAll("\\s+", "");
            final boolean reportReady = reportText.contains("\"runtimeState\":\"RUNNING\"")
                && reportText.contains("\"adapterState\":\"READY\"");
            final boolean hostLoaded = findLoadedClass(instrumentation, APP_CTRL) != null;
            if (reportReady && hostLoaded) {
                evidence.put("hostReady", "true");
                evidence.put("runtimeReport", report.toString());
                return true;
            }
            sleep(POLL_MILLIS);
        }
        evidence.fail("HOST_NOT_READY");
        return false;
    }

    /**
     * The native export pre-check ({@code al.a}) rejects with {@code CUB3-0933} when the
     * active model has zero texture atlases, so the settings dialog is unreachable on an
     * atlas-less fixture. When the bound document has none, the probe registers one real
     * (empty) {@code CTextureAtlas} on the task-owned session copy — the fixture file itself
     * is never saved. This is probe scaffolding only; production never synthesises atlases.
     */
    private static void ensureTextureAtlas(
        final Object controller,
        final Evidence evidence
    ) {
        try {
            onEdtBounded(APPLY_STEP_MILLIS, () -> {
                final Object document = readNoArg(controller, "getCurrentDoc");
                final Object source =
                    document == null ? null : readNoArg(document, "getModelSource");
                final Object manager =
                    source == null ? null : readNoArg(source, "getTextureManager");
                final Object atlases =
                    manager == null ? null : readNoArg(manager, "getTextureAtlases");
                if (!(atlases instanceof List<?> list)) {
                    evidence.put("dialog.atlasInjected", "unavailable");
                    return null;
                }
                if (!list.isEmpty()) {
                    evidence.put("dialog.atlasInjected", "not-needed");
                    return null;
                }
                final Class<?> atlasType = Class.forName(
                    "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas"
                );
                final java.lang.reflect.Constructor<?> ctor = atlasType.getDeclaredConstructor(
                    Class.forName("com.live2d.cubism.doc.model.CModelSource"),
                    String.class, int.class, int.class
                );
                ctor.setAccessible(true);
                final Object atlas = ctor.newInstance(source, "pe-probe-atlas", 1024, 1024);
                invoke(manager, "addTextureAtlas",
                    new Class<?>[] {atlasType, int.class}, atlas, 0);
                final Object after = readNoArg(manager, "getTextureAtlases");
                evidence.put("dialog.atlasInjected", "true");
                evidence.put(
                    "dialog.atlasCountAfter",
                    after instanceof List<?> l ? Integer.toString(l.size()) : "?"
                );
                return null;
            });
        } catch (Throwable failure) {
            evidence.put("dialog.atlasInjected", "failed:" + text(failure));
        }
    }

    /**
     * Waits until the active document is a modeling document with a live model source, because
     * {@code command_exportModelData} silently returns without one. Every poll state is recorded
     * so a missing precondition is evidence rather than a blind failure.
     */
    private static void awaitModelDocument(
        final Object controller,
        final Path stateDir,
        final Evidence evidence
    ) {
        final long deadline = System.currentTimeMillis() + DOCUMENT_TIMEOUT_MILLIS;
        String lastClass = "";
        boolean ready = false;
        while (System.currentTimeMillis() < deadline && !ready) {
            final Object document = readNoArg(controller, "getCurrentDoc");
            lastClass = document == null ? "<null>" : document.getClass().getName();
            if (document != null && isA(document.getClass(), MODELING_DOCUMENT)) {
                final Object source = readNoArg(document, "getModelSource");
                if (source != null) {
                    ready = true;
                    evidence.put("modelSourceClass", source.getClass().getName());
                    final Object textureManager = readNoArg(source, "getTextureManager");
                    final Object atlases = textureManager == null
                        ? null : readNoArg(textureManager, "getTextureAtlases");
                    evidence.put(
                        "textureAtlasCount",
                        atlases instanceof List<?> list ? Integer.toString(list.size()) : "?"
                    );
                }
            }
            if (!ready) {
                sleep(POLL_MILLIS);
            }
        }
        evidence.put("currentDocClass", lastClass);
        evidence.put("modelDocumentReady", Boolean.toString(ready));
        if (ready) {
            final Object document = readNoArg(controller, "getCurrentDoc");
            final Object content = document == null ? null : readNoArg(document, "getFileContent");
            final Object file = content == null ? null : readNoArg(content, "getFile");
            final Object modified = content == null
                ? null : readNoArg(content, "isModifiedAfterSaving");
            evidence.put("currentDocFile", file == null ? "" : file.toString());
            evidence.put("currentDocModified", String.valueOf(modified));
        }
        if (!ready) {
            evidence.fail("MODEL_DOCUMENT_NOT_READY");
        }
        // Small settle so freshly opened documents finish their first layout before a menu command.
        settle(3_000L);
    }

    private static boolean isA(final Class<?> type, final String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean triggerExport(
        final Object controller,
        final Class<?> appCtrl,
        final Evidence evidence
    ) {
        for (String candidate : List.of("command_exportModelData", "command_exportModelData21")) {
            final Method command = noArgVoidMethod(appCtrl, candidate);
            if (command == null) {
                continue;
            }
            evidence.put("exportCommand", candidate);
            try {
                // Fire-and-forget: the command opens a modal dialog and therefore does not return
                // until the dialog is dismissed, so the probe must not wait for it.
                command.setAccessible(true);
                SwingUtilities.invokeLater(() -> {
                    try {
                        command.invoke(controller);
                    } catch (Throwable failure) {
                        System.out.println("PROTECTED_EXPORT_COMMAND_FAILURE " + text(failure));
                    }
                });
                return true;
            } catch (Throwable failure) {
                evidence.put("exportCommandFailure", text(failure));
            }
        }
        evidence.fail("EXPORT_COMMAND_UNAVAILABLE");
        return false;
    }

    // ------------------------------------------------------------------
    // Dialog observation
    // ------------------------------------------------------------------

    /**
     * Waits for the settings dialog, which is recognised by the runtime-injected plain
     * {@code JCheckBox}. Every other window that appears while waiting is recorded with its
     * signature and tree: message boxes are dismissed so the flow can unwind, while buttonless
     * windows (progress dialogs) are left alone to finish on their own.
     */
    private static JDialog awaitExportSettingsDialog(
        final Set<Window> alreadyVisible,
        final Path stateDir,
        final Evidence evidence,
        final String tag
    ) {
        final Set<Window> seen = new LinkedHashSet<>(alreadyVisible);
        final List<String> observed = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + DIALOG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            for (Window window : visibleWindows()) {
                if (seen.contains(window)) {
                    continue;
                }
                seen.add(window);
                final String signature = describe(window);
                observed.add(signature);
                dumpTree(
                    stateDir.resolve(
                        "dialog-tree-" + tag + "-observed-" + observed.size() + ".txt"
                    ),
                    window
                );
                if (window instanceof JDialog dialog
                    && !injectedCheckBoxes(dialog).isEmpty()) {
                    evidence.put(tag + "ObservedBeforeSettings", String.join(" | ", observed));
                    return dialog;
                }
                if (window instanceof java.awt.Dialog dialog && firstButton(dialog) != null) {
                    dismiss(dialog);
                }
            }
            sleep(POLL_MILLIS);
        }
        evidence.put(tag + "ObservedBeforeTimeout", String.join(" | ", observed));
        return null;
    }

    /** Short signature: class, title, native and injected checkbox counts, button presence. */
    private static String describe(final Window window) {
        if (!(window instanceof JDialog dialog)) {
            return window.getClass().getName();
        }
        int nativeBoxes = 0;
        boolean hasButton = false;
        for (Component component : allComponents(dialog)) {
            if (isNativeCheckBoxLeaf(component)) {
                nativeBoxes++;
            }
            if (component instanceof AbstractButton) {
                hasButton = true;
            }
        }
        return dialog.getClass().getSimpleName()
            + "(title=" + quote(dialog.getTitle())
            + ",nativeCBox=" + nativeBoxes
            + ",plainJBox=" + injectedCheckBoxes(dialog).size()
            + ",buttons=" + hasButton + ")";
    }

    /**
     * Records the dialog composition: native Cubism check boxes are {@code CCheckBox} wrappers over
     * FlatLaf tri-state buttons, while the contributed panel is built from plain
     * {@code javax.swing.JCheckBox} instances, so an exact-class match isolates the injected option.
     */
    private static void inspectSettingsDialog(
        final JDialog dialog,
        final Path stateDir,
        final Evidence evidence,
        final String phase
    ) {
        final List<JCheckBox> injected = injectedCheckBoxes(dialog);
        final List<String> nativeBoxes = new ArrayList<>();
        for (Component component : allComponents(dialog)) {
            if (isNativeCheckBoxLeaf(component)) {
                nativeBoxes.add(hostText(component) + ":" + hostSelected(component));
            }
        }
        final List<String> injectedLabels = new ArrayList<>();
        boolean allUnselected = true;
        for (JCheckBox box : injected) {
            injectedLabels.add(box.getText() == null ? "" : box.getText());
            allUnselected = allUnselected && !box.isSelected();
        }
        evidence.put(phase + "SettingsDialogClass", dialog.getClass().getName());
        evidence.put(
            phase + "SettingsDialogTitle",
            dialog.getTitle() == null ? "" : dialog.getTitle()
        );
        evidence.put(phase + "InjectedCheckBoxCount", Integer.toString(injected.size()));
        evidence.put(phase + "NativeCheckBoxCount", Integer.toString(nativeBoxes.size()));
        evidence.put(phase + "NativeCheckBoxes", String.join(" | ", nativeBoxes));
        evidence.put(phase + "InjectedCheckBoxLabels", String.join(" | ", injectedLabels));
        evidence.put(
            phase + "InjectedCheckBoxesUnselected", Boolean.toString(allUnselected)
        );
        evidence.put(
            phase + "ConfirmButtonPresent",
            Boolean.toString(findButton(dialog, confirmAction()) != null)
        );
        evidence.put(
            phase + "CancelButtonPresent",
            Boolean.toString(findButton(dialog, cancelAction()) != null)
        );
        mountEvidence(dialog, injected, evidence, phase);
        dumpTree(stateDir.resolve("dialog-tree-settings-" + phase + ".txt"), dialog);
    }

    /**
     * Pins where the contributed rows landed: the owned panel must be appended
     * inside the native options container — the component-order peer that directly
     * owns the host's check-box leaves — as its last child. The dialog content
     * pane's south region belongs to the native button row, so a mount parent
     * equal to the content pane (or any container without native options) is the
     * regression signature this evidence exists to catch.
     */
    private static void mountEvidence(
        final JDialog dialog,
        final List<JCheckBox> injected,
        final Evidence evidence,
        final String phase
    ) {
        Container ownedPanel = null;
        for (JCheckBox box : injected) {
            final Container parent = box.getParent();
            if (parent == null || (ownedPanel != null && parent != ownedPanel)) {
                ownedPanel = null;
                break;
            }
            ownedPanel = parent;
        }
        final Container mount = ownedPanel == null ? null : ownedPanel.getParent();
        final int index = mount == null ? -1 : indexOfComponent(mount, ownedPanel);
        final int childCount = mount == null ? -1 : mount.getComponentCount();
        int nativeSiblings = 0;
        if (mount != null) {
            for (Component child : mount.getComponents()) {
                if (isNativeCheckBoxLeaf(child)) {
                    nativeSiblings++;
                }
            }
        }
        final String mountClass = mount == null ? "" : mount.getClass().getName();
        final boolean insideOptions = mount != null
            && mount != dialog.getContentPane()
            && mountClass.equals(optionsContainerPeer(hostVersion()))
            && index == childCount - 1
            && nativeSiblings >= 1;
        evidence.put(phase + "MountParentClass", mountClass);
        evidence.put(phase + "MountExpectedParent", optionsContainerPeer(hostVersion()));
        evidence.put(phase + "MountIndex", Integer.toString(index));
        evidence.put(phase + "MountChildCount", Integer.toString(childCount));
        evidence.put(phase + "MountNativeSiblings", Integer.toString(nativeSiblings));
        evidence.put(phase + "MountedInsideOptions", Boolean.toString(insideOptions));
        geometryEvidence(dialog, ownedPanel, mount, evidence, phase);
    }

    /**
     * No-clipping evidence for the enlarged dialog: the window height must
     * cover its preferred content height, the contributed panel and the lowest
     * native option must both be fully laid out inside the options container,
     * and the recorded baseline→grown pair must show the window grew by about
     * the panel's required height (or was already roomy enough).
     */
    private static void geometryEvidence(
        final JDialog dialog,
        final Container ownedPanel,
        final Container mount,
        final Evidence evidence,
        final String phase
    ) {
        try {
            final javax.swing.JComponent ownedComponent =
                ownedPanel instanceof javax.swing.JComponent component
                    ? component : null;
            // The backend's grow passes are deferred invokeLaters queued off the
            // panel's SHOWING_CHANGED; wait on the probe thread — sleeping on the
            // EDT would starve the very passes being awaited.
            for (int wait = 0; wait < 40 && ownedComponent != null
                    && ownedComponent.getClientProperty(
                        "turboism.export-settings.dialogBaselineSize") == null;
                    wait++) {
                sleep(POLL_MILLIS);
            }
            onEdt(() -> {
                final int dialogHeight = dialog.getHeight();
                final int dialogPrefHeight = dialog.getPreferredSize().height;
                evidence.put(phase + "DialogHeight", Integer.toString(dialogHeight));
                evidence.put(phase + "DialogPrefHeight",
                    Integer.toString(dialogPrefHeight));
                evidence.put(phase + "HeightCoversContent",
                    Boolean.toString(dialogHeight >= dialogPrefHeight));
                if (ownedPanel == null || mount == null) {
                    return null;
                }
                final int panelPrefHeight = ownedPanel.getPreferredSize().height;
                final java.awt.Rectangle panelBounds = ownedPanel.getBounds();
                evidence.put(phase + "PanelPrefHeight",
                    Integer.toString(panelPrefHeight));
                evidence.put(phase + "PanelAllocHeight",
                    Integer.toString(ownedPanel.getHeight()));
                evidence.put(phase + "PanelFullyVisible", Boolean.toString(
                    panelBounds.y >= 0
                        && panelBounds.y + panelBounds.height <= mount.getHeight()
                        && ownedPanel.getHeight() >= panelPrefHeight));
                // The lowest native option in the options container must be
                // fully inside it — clipped natives are the regression this
                // evidence catches.
                int lastNativeBottom = Integer.MIN_VALUE;
                for (Component child : allComponents(mount)) {
                    if (!isNativeCheckBoxLeaf(child) || child.getParent() == null) {
                        continue;
                    }
                    final java.awt.Point origin = SwingUtilities.convertPoint(
                        child.getParent(), child.getLocation(), mount);
                    lastNativeBottom =
                        Math.max(lastNativeBottom, origin.y + child.getHeight());
                }
                evidence.put(phase + "LastNativeBottom",
                    Integer.toString(lastNativeBottom));
                evidence.put(phase + "MountHeight",
                    Integer.toString(mount.getHeight()));
                evidence.put(phase + "LastNativeFullyVisible", Boolean.toString(
                    lastNativeBottom >= 0 && lastNativeBottom <= mount.getHeight()));
                // Grow/restore pair recorded by the attach backend on the owned
                // panel (DialogGrowth.BASELINE_PROPERTY / GROWN_PROPERTY).
                final Object baseline = ownedComponent == null ? null
                    : ownedComponent.getClientProperty(
                        "turboism.export-settings.dialogBaselineSize");
                final Object grown = ownedComponent == null ? null
                    : ownedComponent.getClientProperty(
                        "turboism.export-settings.dialogGrownSize");
                final int baselineHeight = baseline instanceof java.awt.Dimension size
                    ? size.height : -1;
                final int grownHeight = grown instanceof java.awt.Dimension size
                    ? size.height : -1;
                evidence.put(phase + "GrowthBaselineHeight",
                    Integer.toString(baselineHeight));
                evidence.put(phase + "GrownHeight", Integer.toString(grownHeight));
                evidence.put(phase + "GrowthSufficient", Boolean.toString(
                    baselineHeight >= 0 && grownHeight >= baselineHeight
                        && (grownHeight - baselineHeight >= panelPrefHeight - 8
                            || baselineHeight >= dialogPrefHeight)));
                return null;
            });
        } catch (Throwable failure) {
            evidence.put(phase + "GeometryFailure", text(failure));
        }
    }

    private static int indexOfComponent(final Container parent, final Component child) {
        for (int index = 0; index < parent.getComponentCount(); index++) {
            if (parent.getComponent(index) == child) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The host's option leaves are {@code CCheckBox$a} — FlatLaf tri-state
     * subclasses of {@link JCheckBox} — while the runtime materializes plain
     * {@code JCheckBox} rows, so "a check box that is not exactly JCheckBox"
     * isolates the native options.
     */
    static boolean isNativeCheckBoxLeaf(final Component component) {
        return component instanceof JCheckBox && component.getClass() != JCheckBox.class;
    }

    private static void confirmUnchecked(final JDialog dialog, final Evidence evidence) {
        final AbstractButton confirm = findButton(dialog, confirmAction());
        if (confirm == null) {
            evidence.put("confirmClicked", "false");
            dismiss(dialog);
            return;
        }
        evidence.put("confirmClicked", "true");
        try {
            onEdt(() -> {
                confirm.doClick(0);
                return null;
            });
        } catch (Throwable failure) {
            evidence.put("confirmFailure", text(failure));
        }
    }

    /**
     * After a confirmed, unchecked settings dialog the native export continues. The next modal
     * window is the export destination chooser; observing it proves the decision gate let the
     * native path through unchanged. Every window in that sequence is recorded and then dismissed,
     * so no file is written.
     */
    private static void observeContinuation(
        final Set<Window> before,
        final JDialog settings,
        final Path stateDir,
        final Evidence evidence,
        final String phase
    ) {
        final Set<Window> seen = new LinkedHashSet<>(before);
        seen.add(settings);
        final List<String> sequence = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + SEQUENCE_TIMEOUT_MILLIS;
        long lastChange = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            boolean progressed = false;
            for (Window window : visibleWindows()) {
                if (seen.contains(window) || !(window instanceof java.awt.Dialog)) {
                    continue;
                }
                seen.add(window);
                sequence.add(describe(window));
                dumpTree(
                    stateDir.resolve(
                        "dialog-tree-continuation-" + phase + "-" + sequence.size() + ".txt"
                    ),
                    window
                );
                dismiss((java.awt.Dialog) window);
                progressed = true;
                lastChange = System.currentTimeMillis();
            }
            if (!progressed && System.currentTimeMillis() - lastChange > IDLE_MILLIS) {
                break;
            }
            sleep(POLL_MILLIS);
        }
        evidence.put(phase + "ContinuationDialogCount", Integer.toString(sequence.size()));
        evidence.put(phase + "ContinuationDialogSequence", String.join(" -> ", sequence));
        evidence.put(
            phase + "NativeContinuationObserved", Boolean.toString(!sequence.isEmpty())
        );
    }

    private static void dismiss(final java.awt.Dialog dialog) {
        try {
            final AbstractButton cancel = findButton(dialog, cancelAction());
            if (cancel != null) {
                onEdt(() -> {
                    cancel.doClick(0);
                    return null;
                });
                waitForHidden(dialog);
                return;
            }
        } catch (Throwable ignored) {
            // Fall through to the window-level close.
        }
        try {
            onEdt(() -> {
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                return null;
            });
        } catch (Throwable ignored) {
            // Last resort below.
        }
        waitForHidden(dialog);
        if (dialog.isVisible()) {
            try {
                onEdt(() -> {
                    dialog.dispose();
                    return null;
                });
            } catch (Throwable ignored) {
                // The runner's exit timeout owns the final cleanup.
            }
        }
    }

    private static void settle(final long millis) {
        final long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            sleep(POLL_MILLIS);
        }
    }

    private static void waitForHidden(final java.awt.Dialog dialog) {
        final long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline && dialog.isVisible()) {
            sleep(POLL_MILLIS);
        }
    }

    /** Walks a component's parent chain to the top-level window carrying it. */
    private static java.awt.Window currentCarrier(final Component component) {
        java.awt.Container parent = component.getParent();
        while (parent != null && !(parent instanceof java.awt.Window)) {
            parent = parent.getParent();
        }
        return parent instanceof java.awt.Window window ? window : null;
    }

    /**
     * Kill-safe stall diagnostics: a daemon thread that beats every 30s into the
     * progress file and writes a full thread dump every beat. When the probe or
     * the EDT wedges (for example inside native JFileChooser construction under
     * Wine), the dumps preserve exactly which stack blocked, instead of leaving
     * a silent timeout.
     */
    private static void startWatchdog(final Path stateDir, final Evidence evidence) {
        final Thread watchdog = new Thread(() -> {
            for (int beat = 1; beat <= 60; beat++) {
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException stopped) {
                    return;
                }
                evidence.put("watchdog.beat." + beat, Instant.now().toString());
                dumpAllThreads(stateDir.resolve("thread-dump-" + beat + ".txt"));
            }
        }, "protected-export-probe-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void dumpAllThreads(final Path target) {
        final StringBuilder out = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> entry
                : Thread.getAllStackTraces().entrySet()) {
            out.append('"').append(entry.getKey().getName())
                .append("\" state=").append(entry.getKey().getState())
                .append(" daemon=").append(entry.getKey().isDaemon())
                .append(System.lineSeparator());
            for (StackTraceElement frame : entry.getValue()) {
                out.append("    at ").append(frame).append(System.lineSeparator());
            }
        }
        try {
            Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Diagnostics only.
        }
    }

    /** Cubism's own controls are not Swing buttons, so their text/state is read reflectively. */
    private static String hostText(final Component component) {
        final Object value = readNoArg(component, "getText");
        return value == null ? "<null>" : value.toString().replace('|', '/');
    }

    private static String hostSelected(final Component component) {
        final Object value = readNoArg(component, "isSelected");
        return value == null ? "?" : value.toString();
    }

    /**
     * Invokes a method found by name and exact signature anywhere on the target's class
     * hierarchy. Used for host commands whose parameter types the probe can name
     * ({@code command_open(File, boolean)}).
     */
    private static Object invoke(
        final Object target,
        final String name,
        final Class<?>[] signature,
        final Object... args
    ) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Method method = type.getDeclaredMethod(name, signature);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (NoSuchMethodException missing) {
                // Keep walking up the hierarchy.
            }
        }
        throw new NoSuchMethodException(name);
    }

    /**
     * Invokes the single-argument method {@code name} whose declared parameter type accepts
     * {@code argument}. Used for host commands whose parameter is an interface the probe must
     * not import ({@code command_closeFileContent(IFileContent)}).
     */
    private static Object invokeByName(
        final Object target,
        final String name,
        final Object argument
    ) throws Exception {
        Method candidate = null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 1
                    || !method.getParameterTypes()[0].isInstance(argument)) {
                    continue;
                }
                if (candidate != null) {
                    throw new IllegalStateException("ambiguous host command " + name);
                }
                candidate = method;
            }
        }
        if (candidate == null) {
            throw new NoSuchMethodException(name);
        }
        candidate.setAccessible(true);
        return candidate.invoke(target, argument);
    }

    /**
     * Invokes the selector's two-argument {@code addSelected(source, index)} overload —
     * the one whose first parameter accepts a model source object — without importing
     * host types. Exactly one overload must match or this throws.
     */
    private static Object invokeSelectorAdd(final Object selector, final Object source)
        throws Exception {
        Method candidate = null;
        for (Class<?> type = selector.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("addSelected") || method.getParameterCount() != 2
                    || method.getParameterTypes()[1] != int.class
                    || !method.getParameterTypes()[0].isInstance(source)) {
                    continue;
                }
                if (candidate != null) {
                    throw new IllegalStateException("ambiguous selector addSelected overload");
                }
                candidate = method;
            }
        }
        if (candidate == null) {
            throw new NoSuchMethodException("addSelected(source,int)");
        }
        candidate.setAccessible(true);
        return candidate.invoke(selector, source, -1);
    }

    private static String canonical(final File file) {
        try {
            return file.getCanonicalFile().getAbsolutePath();
        } catch (IOException failure) {
            return file.getAbsoluteFile().toPath().normalize().toString();
        }
    }

    private static String sha256(final File file) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file.toPath())) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            final StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable failure) {
            return "unavailable:" + text(failure);
        }
    }

    /** Task-scoped token for the disposable copy name; sanitized for use as a filename. */
    private static String runToken() {
        String token = System.getProperty("turboism.validation.runId");
        if (token == null || token.isBlank()) {
            token = System.getProperty("Turboism.validation.runId");
        }
        if (token == null || token.isBlank()) {
            token = Long.toString(System.currentTimeMillis(), 36);
        }
        return token.replaceAll("[^A-Za-z0-9_-]", "_") + "-";
    }

    private static Object readNoArg(final Object target, final String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Method method = type.getDeclaredMethod(name);
                if (method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method.invoke(target);
                }
            } catch (Throwable ignored) {
                // Keep walking up the hierarchy.
            }
        }
        return null;
    }

    private static AbstractButton findButton(final Container root, final String actionClass) {
        return findButtonByAction(root, actionClass::equals);
    }

    private static String confirmAction() {
        return confirmAction(hostVersion());
    }

    private static String cancelAction() {
        return cancelAction(hostVersion());
    }

    /** The runner publishes the probed build as {@code turboism.validation.hostVersion}. */
    private static String hostVersion() {
        return System.getProperty("turboism.validation.hostVersion", "");
    }

    /** Exact-version dispatch: only the reviewed 5.2.03 build uses the C/B actions. */
    static String confirmAction(final String hostVersion) {
        return "5203".equals(hostVersion) ? CONFIRM_ACTION_5_2 : CONFIRM_ACTION_5_3;
    }

    static String cancelAction(final String hostVersion) {
        return "5203".equals(hostVersion) ? CANCEL_ACTION_5_2 : CANCEL_ACTION_5_3;
    }

    /**
     * Exact-version dispatch for the native options-list peer class. Every
     * reviewed build resolves to {@code com.live2d.ui.swingImpl.u} today; the
     * dispatch exists so a divergence on a newly admitted build is an explicit
     * reviewed choice, not a silent assumption.
     */
    static String optionsContainerPeer(final String hostVersion) {
        return "5203".equals(hostVersion) ? OPTIONS_PEER_5_2 : OPTIONS_PEER_5_3;
    }

    /**
     * Fallback action button for native warning dialogs. The first enabled
     * {@code AbstractButton} in component order can be a scrollbar arrow or the
     * FlatLaf title-bar close button — clicking those never dismisses the
     * dialog. Prefer an affirmative label, then any labeled button; unlabeled
     * chrome buttons are never a safe dismiss target.
     */
    private static AbstractButton firstButton(final Container root) {
        AbstractButton labeled = null;
        for (Component component : allComponents(root)) {
            if (!(component instanceof AbstractButton button) || !button.isEnabled()) {
                continue;
            }
            final String text = button.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (AFFIRMATIVE_LABEL.matcher(text).find()) {
                return button;
            }
            if (labeled == null) {
                labeled = button;
            }
        }
        return labeled;
    }

    static AbstractButton findButtonByAction(
        final Container root,
        final java.util.function.Predicate<String> actionClassName
    ) {
        for (Component component : allComponents(root)) {
            if (component instanceof AbstractButton button && button.getAction() != null
                && actionClassName.test(button.getAction().getClass().getName())) {
                return button;
            }
        }
        return null;
    }

    static List<Component> allComponents(final Component root) {
        final List<Component> collected = new ArrayList<>();
        collect(root, collected, new IdentityHashMap<>(), 0);
        return collected;
    }

    /**
     * Components whose exact class is the plain Swing check box. Native Cubism options are
     * {@code CCheckBox} wrappers over FlatLaf tri-state buttons, so an exact-class match isolates
     * the options contributed by the runtime.
     */
    static List<JCheckBox> injectedCheckBoxes(final Container root) {
        final List<JCheckBox> boxes = new ArrayList<>();
        for (Component component : allComponents(root)) {
            if (component.getClass() == JCheckBox.class) {
                boxes.add((JCheckBox) component);
            }
        }
        return boxes;
    }

    static void collect(
        final Component component,
        final List<Component> collected,
        final Map<Component, Boolean> visited,
        final int depth
    ) {
        if (component == null || depth > 40 || visited.put(component, Boolean.TRUE) != null) {
            return;
        }
        collected.add(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, collected, visited, depth + 1);
            }
        }
    }

    private static void dumpTree(final Path target, final Container root) {
        final StringBuilder builder = new StringBuilder();
        appendTree(builder, root, 0);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The properties result remains the authoritative evidence.
        }
    }

    private static void appendTree(final StringBuilder builder, final Component component, final int depth) {
        builder.append("  ".repeat(Math.max(0, depth)))
            .append(component.getClass().getName());
        if (component instanceof AbstractButton button) {
            builder.append(" text=").append(quote(button.getText()))
                .append(" selected=").append(button.isSelected())
                .append(" enabled=").append(button.isEnabled());
            if (button.getAction() != null) {
                builder.append(" action=").append(button.getAction().getClass().getName());
            }
        } else if (component instanceof javax.swing.JLabel label) {
            builder.append(" text=").append(quote(label.getText()));
        } else if (component instanceof java.awt.Window window) {
            builder.append(" visible=").append(window.isVisible());
            if (window instanceof java.awt.Dialog dialog && dialog.getTitle() != null) {
                builder.append(" title=").append(quote(dialog.getTitle()));
            }
        } else if (component instanceof javax.swing.JPanel panel) {
            builder.append(" layout=").append(
                panel.getLayout() == null ? "none" : panel.getLayout().getClass().getSimpleName()
            );
        }
        builder.append(System.lineSeparator());
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                appendTree(builder, child, depth + 1);
            }
        }
    }

    private static Set<Window> visibleWindows() {
        final Set<Window> windows = new LinkedHashSet<>();
        try {
            for (Window window : Window.getWindows()) {
                if (window.isVisible()) {
                    windows.add(window);
                }
            }
        } catch (Throwable ignored) {
            // A transient AWT state must not fail the probe.
        }
        return windows;
    }

    private static Object staticInstance(final Class<?> type, final Evidence evidence) {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && type.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    final Object value = field.get(null);
                    if (value != null) {
                        evidence.put("instanceSource", "static-field:" + field.getName());
                        return value;
                    }
                } catch (Throwable ignored) {
                    // Try the next candidate.
                }
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                || !type.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            try {
                method.setAccessible(true);
                final Object value = method.invoke(null);
                if (value != null) {
                    evidence.put("instanceSource", "static-method:" + method.getName());
                    return value;
                }
            } catch (Throwable ignored) {
                // Try the next candidate.
            }
        }
        return null;
    }

    private static Method noArgVoidMethod(final Class<?> type, final String name) {
        try {
            final Method method = type.getMethod(name);
            return method.getParameterCount() == 0 && method.getReturnType() == void.class
                ? method : null;
        } catch (Throwable missing) {
            return null;
        }
    }

    private static Class<?> findLoadedClass(final Instrumentation instrumentation, final String name) {
        try {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getName().equals(name)) {
                    return loaded;
                }
            }
        } catch (Throwable ignored) {
            // Reported by the caller as a missing class.
        }
        return null;
    }

    private static <T> T onEdt(final Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.call();
        }
        final FutureTask<T> task = new FutureTask<>(work);
        SwingUtilities.invokeLater(task);
        try {
            return task.get();
        } catch (java.util.concurrent.ExecutionException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof InvocationTargetException inner && inner.getCause() != null) {
                throw new IllegalStateException(text(inner.getCause()), inner.getCause());
            }
            throw new IllegalStateException(text(cause == null ? failure : cause), cause);
        }
    }

    /**
     * Bounded variant of {@link #onEdt}: a native modal raised inside the work would block
     * the EDT's secondary loop forever, so the probe waits at most {@code timeoutMillis} and
     * reports a step timeout instead of losing the whole run's evidence.
     */
    private static <T> T onEdtBounded(
        final long timeoutMillis,
        final Callable<T> work
    ) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.call();
        }
        final FutureTask<T> task = new FutureTask<>(work);
        SwingUtilities.invokeLater(task);
        try {
            return task.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IllegalStateException("EDT work exceeded " + timeoutMillis + "ms", timeout);
        } catch (java.util.concurrent.ExecutionException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof InvocationTargetException inner && inner.getCause() != null) {
                throw new IllegalStateException(text(inner.getCause()), inner.getCause());
            }
            throw new IllegalStateException(text(cause == null ? failure : cause), cause);
        }
    }

    private static Path stateDirectory() {
        final String home = System.getProperty("turboism.home");
        if (home == null || home.isBlank()) {
            return Path.of(".").toAbsolutePath().normalize().resolve(STATE_PATH);
        }
        return Path.of(home).resolve(STATE_PATH);
    }

    private static String readText(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unavailable) {
            return "";
        }
    }

    private static void finish(final Path stateDir, final Evidence evidence) {
        final boolean passed = verdict(evidence);
        final Path result = stateDir.resolve(RESULT_NAME);
        final Map<String, String> sorted = new TreeMap<>(evidence.values);
        final StringBuilder builder = new StringBuilder();
        if (evidence.error != null) {
            builder.append("error=").append(evidence.error).append(System.lineSeparator());
        }
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            builder.append(entry.getKey()).append('=').append(entry.getValue())
                .append(System.lineSeparator());
        }
        builder.append("status=").append(passed ? "PASS" : "FAIL")
            .append(System.lineSeparator());
        try {
            Files.createDirectories(stateDir);
            Files.writeString(result, builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The runner reports a timeout when the result file never appears.
        }
        System.out.println("PROTECTED_EXPORT_RESULT status=" + (passed ? "PASS" : "FAIL"));
        System.out.flush();
        if (Boolean.parseBoolean(
            System.getProperty("turboism.validation.protectedExport.exitOnComplete", "true")
        )) {
            requestNativeHostClose(stateDir, evidence, passed);
        }
    }

    /**
     * Drives the host through its own quit path: a daemon watchdog armed with a
     * bounded grace window halts the JVM only if the native exit never completes,
     * and a {@code WINDOW_CLOSING} event on the main editor frame asks the host to
     * quit normally — which is the only path whose shutdown hooks print the
     * {@code -- successfully exited pid:N --} line the runner's normalExit gate
     * keys on. A wedged quit therefore still terminates the run while leaving
     * honest evidence (no marker) rather than faking a clean exit.
     */
    private static void requestNativeHostClose(
        final Path stateDir,
        final Evidence evidence,
        final boolean passed
    ) {
        final Thread watchdog = new Thread(() -> {
            sleep(EXIT_GRACE_MILLIS);
            Runtime.getRuntime().halt(passed ? 0 : 2);
        }, "protected-export-exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        // Dialogs raised during the quit sequence (for example an unsaved-changes
        // prompt) are recorded; an unsaved-changes prompt is additionally answered
        // with the negative — "don't save" — because the dirty-export variant
        // deliberately leaves the document modified and only a negative answer
        // lets the host quit without writing the fixture copy. Any affirmative
        // answer is never produced: saving would mutate state the probe must not
        // touch.
        final Thread observer = new Thread(() -> observeExitDialogs(stateDir, evidence),
            "protected-export-exit-observer");
        observer.setDaemon(true);
        observer.start();
        try {
            SwingUtilities.invokeLater(ProtectedExportHostProbeAgent::closeMainFrame);
        } catch (Throwable failure) {
            evidence.put("exit.requestError", text(failure));
        }
    }

    /**
     * Polls for dialogs that appear while the host is quitting and records their
     * component signature into evidence. Runs until the JVM exits or the grace
     * window ends; any observed dialog explains a missing normal-exit marker.
     */
    private static void observeExitDialogs(final Path stateDir, final Evidence evidence) {
        final Set<Window> seen = new LinkedHashSet<>();
        final List<String> sequence = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + EXIT_GRACE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            for (Window window : Window.getWindows()) {
                if (!(window instanceof java.awt.Dialog) || !window.isVisible()
                    || seen.contains(window)) {
                    continue;
                }
                seen.add(window);
                sequence.add(describe(window));
                dumpTree(stateDir.resolve(
                    "exit-dialog-" + sequence.size() + ".txt"), window);
                final AbstractButton discard = discardButton((java.awt.Dialog) window);
                if (discard != null) {
                    evidence.put("exit.savePromptAnswered",
                        "no:" + describe(window));
                    try {
                        onEdt(() -> {
                            discard.doClick(0);
                            return null;
                        });
                    } catch (Throwable failure) {
                        evidence.put("exit.savePromptAnswerError", text(failure));
                    }
                }
            }
            sleep(POLL_MILLIS);
        }
        evidence.put("exit.dialogCount", Integer.toString(sequence.size()));
        evidence.put("exit.dialogSequence", String.join(" -> ", sequence));
    }

    /**
     * The "don't save" button of an unsaved-changes prompt, or {@code null} when
     * the dialog is anything else. The prompt is recognised by a label asking
     * whether to save ({@code 保存}/{@code save}) together with the three-way
     * Yes/No/Cancel button set; the returned button is the negative answer —
     * never the affirmative, which would write the fixture copy.
     */
    private static AbstractButton discardButton(final java.awt.Dialog dialog) {
        boolean asksSave = false;
        boolean hasYes = false;
        boolean hasCancel = false;
        AbstractButton no = null;
        for (java.awt.Component component : allComponents(dialog)) {
            if (component instanceof javax.swing.JLabel label) {
                final String text = label.getText();
                if (text != null && (text.contains("保存")
                    || text.toLowerCase(java.util.Locale.ROOT).contains("save"))) {
                    asksSave = true;
                }
            } else if (component instanceof AbstractButton button) {
                final String text = button.getText();
                if (text == null) {
                    continue;
                }
                final String lower = text.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("yes") || text.startsWith("是")) {
                    hasYes = true;
                } else if (lower.startsWith("no") || text.startsWith("否")) {
                    no = button;
                } else if (lower.startsWith("cancel") || text.startsWith("取消")) {
                    hasCancel = true;
                }
            }
        }
        return asksSave && hasYes && hasCancel ? no : null;
    }

    /**
     * Sends {@code WINDOW_CLOSING} to the editor's main frame — the same native
     * quit gesture a user makes. Prefers the frame carrying the model title, then
     * any Cubism-titled frame, then the largest visible frame.
     */
    private static void closeMainFrame() {
        Frame modelFrame = null;
        Frame cubismFrame = null;
        Frame fallback = null;
        long largestArea = -1L;
        for (Frame frame : Frame.getFrames()) {
            if (!frame.isVisible()) {
                continue;
            }
            if (fallback == null) {
                fallback = frame;
            }
            final long area = (long) frame.getWidth() * frame.getHeight();
            if (area > largestArea) {
                largestArea = area;
                fallback = frame;
            }
            final String title = frame.getTitle();
            if (title != null && title.contains(".cmo3")) {
                modelFrame = frame;
                break;
            }
            if (cubismFrame == null && title != null && title.contains("Cubism")) {
                cubismFrame = frame;
            }
        }
        final Frame target = modelFrame != null ? modelFrame
            : cubismFrame != null ? cubismFrame : fallback;
        if (target == null) {
            return;
        }
        target.dispatchEvent(new WindowEvent(target, WindowEvent.WINDOW_CLOSING));
    }

    /**
     * A run passes only when every claim the probe exists to make was actually observed. Anything
     * short of that is a failure, because a partially-observed run is exactly the evidence that
     * would be mistaken for a working feature.
     */
    private static boolean verdict(final Evidence evidence) {
        if (evidence.error != null) {
            return false;
        }
        final List<String> unmet = new ArrayList<>();
        final String phasesRaw = evidence.values.getOrDefault("phases", "dialog");
        final java.util.Set<String> phaseSet = new java.util.HashSet<>();
        for (String token : phasesRaw.split(",")) {
            final String phase = token.trim();
            if (!phase.isEmpty()) {
                phaseSet.add(phase);
            }
        }
        // Exact-token matching: "export-native" must not trip the protected
        // "export" checklist, and vice versa.
        final java.util.function.Predicate<String> phases = phaseSet::contains;
        if (phases.test("dialog")) {
            if (intOf(evidence, "uncheckedInjectedCheckBoxCount") < 1) {
                unmet.add("injected option missing from the native dialog");
            }
            if (!"true".equals(evidence.values.get("uncheckedInjectedCheckBoxesUnselected"))) {
                unmet.add("injected option was not default-off");
            }
            if (!"true".equals(evidence.values.get("uncheckedMountedInsideOptions"))) {
                unmet.add("contributed option is not appended inside the native options container");
            }
            if (!"true".equals(evidence.values.get("checkedMountedInsideOptions"))) {
                unmet.add("checked-phase contribution is not inside the native options container");
            }
            if (!"true".equals(evidence.values.get("confirmClicked"))) {
                unmet.add("native confirm button not driven");
            }
            if (!"true".equals(evidence.values.get("uncheckedNativeContinuationObserved"))) {
                unmet.add("unchecked confirmation did not continue the native export");
            }
            if (intOf(evidence, "bridgeDecideCalls") < 1) {
                unmet.add("transformed dialog never reached the decision gate");
            }
            if ("true".equals(evidence.values.get("checkedConfirmClicked"))
                && !evidence.values.getOrDefault("bridgeDecideResults", "").contains("false")) {
                unmet.add("checked confirmation was not rejected by the decision gate");
            }
            if (!"true".equals(evidence.values.get("checkedNoContinuation"))) {
                unmet.add("a checked rejection still raised a continuation window");
            }
            if ("true".equals(evidence.values.get("checkedConfirmClicked"))
                && !"true".equals(evidence.values.get("checkedVetoDialog"))) {
                unmet.add("checked rejection never surfaced the veto diagnostic dialog");
            }
            if ("true".equals(evidence.values.get("checkedVetoDialog"))
                && evidence.values.getOrDefault("checkedVetoKey", "").isEmpty()) {
                unmet.add("veto diagnostic dialog carried no failure key");
            }
            if (intOf(evidence, "bridgeCancelCalls") < 1) {
                unmet.add("cancel path never reached the bridge cleanup");
            }
            if (!"true".equals(evidence.values.get("cancelNoContinuation"))) {
                unmet.add("cancel still raised a continuation window");
            }
            // No-clipping evidence: the enlarged dialog must cover its content
            // and keep both the last native option and the contributed option
            // fully laid out in both observed dialog instances.
            for (String phasePrefix : new String[] {"unchecked", "checked"}) {
                if (!"true".equals(evidence.values.get(
                        phasePrefix + "HeightCoversContent"))) {
                    unmet.add(phasePrefix
                        + " dialog height does not cover its content");
                }
                if (!"true".equals(evidence.values.get(
                        phasePrefix + "PanelFullyVisible"))) {
                    unmet.add(phasePrefix
                        + " contributed option is clipped inside the dialog");
                }
                if (!"true".equals(evidence.values.get(
                        phasePrefix + "LastNativeFullyVisible"))) {
                    unmet.add(phasePrefix
                        + " native option is clipped inside the dialog");
                }
                if (!"true".equals(evidence.values.get(
                        phasePrefix + "GrowthSufficient"))) {
                    unmet.add(phasePrefix
                        + " dialog did not grow enough for the contributed option");
                }
            }
        }
        if (phases.test("copy-binding")) {
            requireCopySession(evidence, unmet, "copy.");
        }
        if (phases.test("flatten")) {
            requireCopySession(evidence, unmet, "flat.");
            if (intOf(evidence, "flat.planSize") < 1) {
                unmet.add("fixture bound no supported deformers; nothing was flattened");
            }
            if (!"true".equals(evidence.values.get("flat.zeroDeformers"))) {
                unmet.add("supported deformers were not flattened to zero on the copy");
            }
            if (!"true".equals(evidence.values.get("flat.partsPreserved"))) {
                unmet.add("Part identities/hierarchy changed during flatten");
            }
            if (!"true".equals(evidence.values.get("flat.paramsPreserved"))) {
                unmet.add("Parameter identities changed during flatten");
            }
            if (!"true".equals(evidence.values.get("flat.artMeshesPreserved"))) {
                unmet.add("ArtMesh identities changed during flatten");
            }
        }
        if (phases.test("export")) {
            requireExportSession(evidence, unmet, "exp.");
            if (!"true".equals(evidence.values.get("exp.undoPreserved"))) {
                unmet.add("original undo state changed across the export");
            }
            if (!"true".equals(evidence.values.get("exp.selectionPreserved"))) {
                unmet.add("original selection changed across the export");
            }
        }
        if (phases.test("dirty-export")) {
            requireExportSession(evidence, unmet, "dexp.");
            if (!"true".equals(evidence.values.get("dexp.dirtyArmed"))) {
                unmet.add("fixture was not dirty when the export was armed");
            }
            if (!"true".equals(evidence.values.get("dexp.unsavedEditPreserved"))) {
                unmet.add("the unsaved in-memory edit did not survive the export");
            }
        }
        if (phases.test("glue-export")) {
            requireExportSession(evidence, unmet, "gexp.");
            if (!"true".equals(evidence.values.get("gexp.glueInjected"))) {
                unmet.add("the glue fixture was never injected into the live document");
            }
            if (!"true".equals(evidence.values.get("gexp.glueInPublishedMoc3"))) {
                unmet.add("published moc3 does not carry the injected Glue ID");
            }
            if (!"true".equals(evidence.values.get("gexp.moc3GlueRefsValid"))) {
                unmet.add("published moc3 glue drawable references are out of range");
            }
            if (!"true".equals(evidence.values.get("gexp.glueIdentityPreserved"))) {
                unmet.add("the live document's Glue identity changed across the export");
            }
            if (!"true".equals(evidence.values.get("gexp.undoPreserved"))) {
                unmet.add("original undo state changed across the export");
            }
            if (!"true".equals(evidence.values.get("gexp.selectionPreserved"))) {
                unmet.add("original selection changed across the export");
            }
        }
        if (phases.test("export-native")) {
            if (intOf(evidence, "natOuterInjectedCheckBoxCount") < 1) {
                unmet.add("contributed option missing from the outer dialog");
            }
            if (!"true".equals(evidence.values.get("natOuterMountedInsideOptions"))) {
                unmet.add("outer contribution is not inside the native options container");
            }
            if (!"true".equals(evidence.values.get("natOuterInjectedCheckBoxesUnselected"))) {
                unmet.add("native comparison run must leave the contributed option unchecked");
            }
            if (!"true".equals(evidence.values.get("nat.outerConfirmed"))) {
                unmet.add("outer confirmation was not driven");
            }
            if (!"true".equals(evidence.values.get("nat.chooserDriven"))) {
                unmet.add("native destination chooser was not driven");
            }
            if (!"true".equals(evidence.values.get("nat.publishedMoc3"))) {
                unmet.add("no published moc3 at the picked destination");
            }
            if (!"true".equals(evidence.values.get("nat.publishedModelJson"))) {
                unmet.add("no published model/display-info json at the picked destination");
            }
            if (evidence.values.getOrDefault("nat.moc3Sha256", "").isEmpty()) {
                unmet.add("published moc3 was not dumped for comparison");
            }
            if (!"true".equals(evidence.values.get("nat.sameLiveDocument"))) {
                unmet.add("original was not the same live document after the native export");
            }
            if (!"true".equals(evidence.values.get("nat.fileSha256Preserved"))) {
                unmet.add("original file bytes changed across the native export");
            }
        }
        if (phases.test("expect-reject") || phases.test("expect-reject-structure")) {
            requireRejectSession(evidence, unmet);
        }
        if (phases.test("expect-reject-structure")) {
            if (!"true".equals(evidence.values.get("rej.detailMatched"))) {
                unmet.add("unsupported-structure rejection did not surface the family-count detail");
            }
            if (!"true".equals(evidence.values.get("rej.injectionRestored"))) {
                unmet.add("injected unsupported sources were not removed from the authoring document");
            }
        }
        if (!unmet.isEmpty()) {
            evidence.fail("UNMET:" + String.join("; ", unmet));
            return false;
        }
        return true;
    }

    /** Shared expected-rejection verdict gates ({@code rej.} evidence prefix). */
    private static void requireRejectSession(
        final Evidence evidence,
        final List<String> unmet
    ) {
        if (!"true".equals(evidence.values.get("rej.outerConfirmed"))) {
            unmet.add("expected-rejection drive never confirmed the outer dialog");
        }
        if (!"true".equals(evidence.values.get("rej.sessionFailed"))) {
            unmet.add("session did not terminate FAILED");
        }
        if ("true".equals(evidence.values.get("rej.sessionPublished"))) {
            unmet.add("a rejected fixture still published output");
        }
        if (!"true".equals(evidence.values.get("rej.failureMatched"))) {
            unmet.add("session failure key differs from the expected rejection");
        }
        if (intOf(evidence, "rej.outerInjectedCheckBoxCount") < 1) {
            unmet.add("contributed option missing from the outer dialog");
        }
        if (!evidence.values.getOrDefault("rej.unexpectedDialogs", "").isEmpty()) {
            unmet.add("rejected session still raised windows");
        }
        if (!"true".equals(evidence.values.get("rej.vetoDialog"))) {
            unmet.add("rejected session never surfaced the veto diagnostic dialog");
        }
        if (!evidence.values.getOrDefault("rej.expectedFailure", "")
                .equals(evidence.values.getOrDefault("rej.vetoKey", ""))) {
            unmet.add("veto diagnostic did not name the session's failure key");
        }
        if (!"true".equals(evidence.values.get("rej.sameLiveDocument"))) {
            unmet.add("original was not the live document after rejection");
        }
        if (!"true".equals(evidence.values.get("rej.fileSha256Preserved"))) {
            unmet.add("original file bytes changed across the rejection");
        }
        if (!"true".equals(evidence.values.get("rej.modifiedPreserved"))) {
            unmet.add("original dirty flag changed across the rejection");
        }
        if (!"true".equals(evidence.values.get("rej.undoPreserved"))) {
            unmet.add("original undo state changed across the rejection");
        }
        if (intOf(evidence, "rej.stagingResidue") != 0) {
            unmet.add("task-owned staging residue remains after rejection");
        }
    }

    /**
     * Shared export-session verdict gates, keyed by the phase's evidence prefix —
     * the option drive, inner-dialog suppression, chooser drive, published
     * outputs, live-document restoration and zero staging residue all hold for
     * the clean, dirty and glue export variants alike.
     */
    private static void requireExportSession(
        final Evidence evidence,
        final List<String> unmet,
        final String prefix
    ) {
        if (intOf(evidence, prefix + "outerInjectedCheckBoxCount") < 1) {
            unmet.add("contributed option missing from the outer dialog");
        }
        if (!"true".equals(evidence.values.get(
                prefix.replace(".", "") + "OuterMountedInsideOptions"))) {
            unmet.add("outer contribution is not inside the native options container");
        }
        if (!"true".equals(evidence.values.get(prefix + "outerConfirmed"))) {
            unmet.add("outer confirmation was not driven");
        }
        if (!"true".equals(evidence.values.get(prefix + "innerObserved"))) {
            unmet.add("re-driven inner export dialog never appeared");
        }
        if (!"true".equals(evidence.values.get(prefix + "innerInjectedSuppressed"))) {
            unmet.add("inner dialog still showed contributed options");
        }
        if (!"true".equals(evidence.values.get(prefix + "innerConfirmed"))) {
            unmet.add("inner confirmation was not driven");
        }
        if (!"true".equals(evidence.values.get(prefix + "chooserDriven"))) {
            unmet.add("native destination chooser was not driven");
        }
        if (!"true".equals(evidence.values.get(prefix + "publishedMoc3"))) {
            unmet.add("no published moc3 at the picked destination");
        }
        if (!"true".equals(evidence.values.get(prefix + "publishedModelJson"))) {
            unmet.add("no published model/display-info json at the picked destination");
        }
        if (!"true".equals(evidence.values.get(prefix + "sameLiveDocument"))) {
            unmet.add("original was not restored as the same live document");
        }
        if (!"true".equals(evidence.values.get(prefix + "fileSha256Preserved"))) {
            unmet.add("original file bytes changed across the export");
        }
        if (!"true".equals(evidence.values.get(prefix + "modifiedPreserved"))) {
            unmet.add("original dirty flag changed across the export");
        }
        if (intOf(evidence, prefix + "stagingResidue") != 0) {
            unmet.add("task-owned staging residue remains");
        }
    }

    /** Shared copy-session verdict gates, keyed by the phase's evidence prefix. */
    private static void requireCopySession(
        final Evidence evidence,
        final List<String> unmet,
        final String prefix
    ) {
        if (!"true".equals(evidence.values.get(prefix + "bound"))) {
            unmet.add("task-owned copy was not bound as the active document");
        }
        if (!"true".equals(evidence.values.get(prefix + "restored"))) {
            unmet.add("original document was not restored as active");
        }
        if (!"true".equals(evidence.values.get(prefix + "sameLiveDocument"))) {
            unmet.add("restored original was a fresh instance, not the same live document");
        }
        if (!"true".equals(evidence.values.get(prefix + "fileSha256Preserved"))) {
            unmet.add("original file bytes changed across the copy session");
        }
        if (!"true".equals(evidence.values.get(prefix + "modifiedPreserved"))) {
            unmet.add("original dirty flag changed across the copy session");
        }
        if (!"true".equals(evidence.values.get(prefix + "undoPreserved"))) {
            unmet.add("original undo state changed across the copy session");
        }
        if (!"true".equals(evidence.values.get(prefix + "selectionPreserved"))) {
            unmet.add("original selection changed across the copy session");
        }
        if (!"true".equals(evidence.values.get(prefix + "closed"))) {
            unmet.add("task-owned copy document was not closed");
        }
        if (!"true".equals(evidence.values.get(prefix + "fileRemoved"))) {
            unmet.add("task-owned copy file was not removed");
        }
    }

    private static int intOf(final Evidence evidence, final String key) {
        try {
            return Integer.parseInt(evidence.values.getOrDefault(key, "0"));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static String quote(final String value) {
        return value == null ? "<null>" : '"' + value.replace("\"", "'") + '"';
    }

    private static String text(final Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        final String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Mutable key/value evidence for one probe run. */
    private static final class Evidence {
        private final Map<String, String> values = new TreeMap<>();
        private String error;
        /**
         * Kill-safe progress mirror. The authoritative result file is only
         * written at {@link #finish}; this file exists so a supervisor kill can
         * never leave a run evidence-less. The runner ignores it.
         */
        private volatile Path progress;

        private void put(final String key, final String value) {
            final String stored = value == null ? "" : value;
            values.put(key, stored);
            appendProgress(key + "=" + stored);
        }

        private void fail(final String reason) {
            if (error == null) {
                error = reason;
                appendProgress("error=" + reason);
            }
        }

        private void appendProgress(final String line) {
            final Path target = progress;
            if (target == null) {
                return;
            }
            try {
                Files.writeString(target, line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Progress is best-effort; the terminal result stays authoritative.
            }
        }
    }
}
