package dev.turboism.validation.protectedexport;

import java.awt.Component;
import java.awt.Container;
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
import java.security.MessageDigest;
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
import javax.swing.SwingUtilities;

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
    // Action classes on the native window buttons, verified against the 5.3.02
    // dialog tree dump: the "OK" button carries com.live2d.ui.window.A (its
    // actionPerformed calls y.i() which sets the accept flag returned by y.f()),
    // and the "Cancel" button carries com.live2d.ui.window.z (y.h(), the dismiss
    // path). Matching by action class rather than label is locale-stable.
    private static final String CONFIRM_ACTION = "com.live2d.ui.window.A";
    private static final String CANCEL_ACTION = "com.live2d.ui.window.z";
    private static final String BRIDGE_PREFIX = "turboism.export-settings.dialog.";
    private static final String ATTACH_KEY = BRIDGE_PREFIX + "attach";
    private static final String CANCEL_KEY = BRIDGE_PREFIX + "cancel";
    private static final String DECIDE_KEY = BRIDGE_PREFIX + "decide";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private static final long READY_TIMEOUT_MILLIS = 300_000L;
    private static final long DOCUMENT_TIMEOUT_MILLIS = 120_000L;
    private static final long DIALOG_TIMEOUT_MILLIS = 45_000L;
    private static final long SEQUENCE_TIMEOUT_MILLIS = 90_000L;
    private static final long QUIESCENCE_MILLIS = 15_000L;
    private static final long IDLE_MILLIS = 6_000L;
    private static final long POLL_MILLIS = 200L;
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
            evidence.put("phases", String.join("+", phases));

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
        final AbstractButton confirm = findButton(settings, CONFIRM_ACTION);
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
        // A rejection closes the settings dialog without raising any continuation window.
        final List<String> unexpected = awaitQuiescence(alreadyVisible, settings);
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
        final AbstractButton cancel = findButton(settings, CANCEL_ACTION);
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
        final java.util.concurrent.atomic.AtomicReference<String> selectionLanded =
            new java.util.concurrent.atomic.AtomicReference<>();
        try {
            onEdtBounded(APPLY_STEP_MILLIS, () -> {
                final Object source = resolveDeformer(scope.modelSource, target.guid);
                if (source == null) {
                    throw new IllegalStateException("target deformer GUID absent before apply");
                }
                final Object guidObject = readNoArg(source, "getGuid");
                final Object updateManager = readNoArg(controller, "getUpdateManager");
                invoke(updateManager, "setSelection",
                    new Class<?>[] {Object.class, List.class, boolean.class, boolean.class},
                    scope.document, List.of(guidObject), Boolean.FALSE, Boolean.TRUE);

                // sendEvent=true may have re-entered the host; re-verify every
                // identity the native command is about to act on (AC05 guard).
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
                final Object selector = readNoArg(scope.document, "getSelector");
                final Object selectedCount =
                    selector == null ? null : readNoArg(selector, "getSelectedCount");
                selectionLanded.set(String.valueOf(selectedCount));

                invoke(controller, "command_deleteDeformerAndSetParam", new Class<?>[0]);

                if (resolveDeformer(scope.modelSource, target.guid) != null) {
                    throw new IllegalStateException("target deformer GUID still present after apply");
                }
                return null;
            });
            evidence.put(key + ".applied", "true");
            evidence.put(key + ".selectedCount", selectionLanded.get());
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
            if (component.getClass().getName().equals("com.live2d.ui.control.CCheckBox")) {
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
            if (component.getClass().getName().equals("com.live2d.ui.control.CCheckBox")) {
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
            Boolean.toString(findButton(dialog, CONFIRM_ACTION) != null)
        );
        evidence.put(
            phase + "CancelButtonPresent",
            Boolean.toString(findButton(dialog, CANCEL_ACTION) != null)
        );
        dumpTree(stateDir.resolve("dialog-tree-settings-" + phase + ".txt"), dialog);
    }

    private static void confirmUnchecked(final JDialog dialog, final Evidence evidence) {
        final AbstractButton confirm = findButton(dialog, CONFIRM_ACTION);
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
            final AbstractButton cancel = findButton(dialog, CANCEL_ACTION);
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

    private static AbstractButton firstButton(final Container root) {
        for (Component component : allComponents(root)) {
            if (component instanceof AbstractButton button && button.isEnabled()) {
                return button;
            }
        }
        return null;
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
            // halt (not exit): skips shutdown hooks that can hang under Wine once the probe has
            // written its terminal result.
            Runtime.getRuntime().halt(passed ? 0 : 2);
        }
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
        final String phases = evidence.values.getOrDefault("phases", "dialog");
        if (phases.contains("dialog")) {
            if (intOf(evidence, "uncheckedInjectedCheckBoxCount") < 1) {
                unmet.add("injected option missing from the native dialog");
            }
            if (!"true".equals(evidence.values.get("uncheckedInjectedCheckBoxesUnselected"))) {
                unmet.add("injected option was not default-off");
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
            if (intOf(evidence, "bridgeCancelCalls") < 1) {
                unmet.add("cancel path never reached the bridge cleanup");
            }
            if (!"true".equals(evidence.values.get("cancelNoContinuation"))) {
                unmet.add("cancel still raised a continuation window");
            }
        }
        if (phases.contains("copy-binding")) {
            requireCopySession(evidence, unmet, "copy.");
        }
        if (phases.contains("flatten")) {
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
        if (!unmet.isEmpty()) {
            evidence.fail("UNMET:" + String.join("; ", unmet));
            return false;
        }
        return true;
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

        private void put(final String key, final String value) {
            values.put(key, value == null ? "" : value);
        }

        private void fail(final String reason) {
            if (error == null) {
                error = reason;
            }
        }
    }
}
