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
    private static final String CONFIRM_ACTION = "com.live2d.ui.window.z";
    private static final String CANCEL_ACTION = "com.live2d.ui.window.A";
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
        File copyFile = null;
        Object copyDoc = null;
        try {
            final Object original = readNoArg(controller, "getCurrentDoc");
            if (original == null) {
                evidence.fail("COPY_NO_ACTIVE_DOCUMENT");
                return;
            }
            final Object originalContent = readNoArg(original, "getFileContent");
            final Object originalFileObj =
                originalContent == null ? null : readNoArg(originalContent, "getFile");
            if (!(originalFileObj instanceof File originalFile) || !originalFile.isFile()) {
                evidence.fail("COPY_ORIGINAL_FILE_MISSING");
                return;
            }

            final DocumentState before = snapshotDocument(original, evidence, "orig");
            evidence.put("copy.origFile", originalFile.getAbsolutePath());
            evidence.put("copy.origFileSha256", sha256(originalFile));

            copyFile = new File(
                originalFile.getParentFile(),
                "pe-copy-" + runToken() + originalFile.getName()
            );
            evidence.put("copy.file", copyFile.getAbsolutePath());
            Files.copy(originalFile.toPath(), copyFile.toPath());
            evidence.put("copy.fileCreated", "true");

            copyDoc = openAndAwaitBoundDocument(
                controller, copyFile, evidence, "copy"
            );
            if (copyDoc == null) {
                evidence.fail("COPY_OPEN_NOT_BOUND");
                return;
            }
            evidence.put("copy.bound", "true");
            evidence.put("copy.docId", Integer.toHexString(System.identityHashCode(copyDoc)));

            // Restore: reopening the original file must reactivate the SAME live
            // document (identity), not a fresh instance.
            final Object restored = openAndAwaitBoundDocument(
                controller, originalFile, evidence, "orig"
            );
            if (restored == null) {
                evidence.fail("COPY_RESTORE_NOT_BOUND");
                return;
            }
            evidence.put("copy.restored", "true");
            evidence.put(
                "copy.sameLiveDocument",
                Boolean.toString(System.identityHashCode(restored) == before.docId)
            );

            final DocumentState after = snapshotDocument(restored, evidence, "restored");
            evidence.put("copy.fileSha256Preserved",
                Boolean.toString(sha256(originalFile)
                    .equals(evidence.values.get("copy.origFileSha256"))));
            evidence.put("copy.modifiedPreserved",
                Boolean.toString(after.modified == before.modified));
            evidence.put("copy.undoPreserved",
                Boolean.toString(after.undoSignature.equals(before.undoSignature)));
            evidence.put("copy.selectionPreserved",
                Boolean.toString(after.selectionSignature.equals(before.selectionSignature)));
        } catch (Throwable failure) {
            evidence.fail("COPY_PHASE_FAILURE:" + failure.getClass().getName() + ":" + text(failure));
        } finally {
            if (copyDoc != null) {
                try {
                    closeDocument(controller, copyDoc, evidence);
                } catch (Throwable failure) {
                    evidence.put("copy.closeFailure", text(failure));
                }
            }
            if (copyFile != null && copyFile.isFile()) {
                try {
                    Files.deleteIfExists(copyFile.toPath());
                    evidence.put("copy.fileRemoved", "true");
                } catch (Throwable failure) {
                    evidence.put("copy.fileRemoveFailure", text(failure));
                }
            }
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
        evidence.put("copy." + prefix + ".docId", Integer.toHexString(docId));
        evidence.put("copy." + prefix + ".modified", Boolean.toString(modified));
        evidence.put("copy." + prefix + ".undo", undo);
        evidence.put("copy." + prefix + ".selection", selection);
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
            evidence.put("copy." + prefix + ".openFailure", text(failure));
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
                    evidence.put("copy." + prefix + ".boundPath", boundFile.getAbsolutePath());
                    return lastDoc;
                }
            }
            sleep(POLL_MILLIS);
        }
        if (lastDoc != null) {
            final Object content = readNoArg(lastDoc, "getFileContent");
            final Object bound = content == null ? null : readNoArg(content, "getFile");
            evidence.put("copy." + prefix + ".lastBoundPath", String.valueOf(bound));
        }
        return null;
    }

    private static void closeDocument(
        final Object controller,
        final Object document,
        final Evidence evidence
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
        // Wait until the copy content reports no live documents; focus moving away
        // alone would also satisfy the weaker check, so both are recorded.
        final long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final Object docs = readNoArg(content, "getFileContentDocs");
            final boolean empty = docs instanceof List<?> list && list.isEmpty();
            final Object current = readNoArg(controller, "getCurrentDoc");
            final boolean unfocused = current == null
                || System.identityHashCode(current) != System.identityHashCode(document);
            if (empty || unfocused) {
                evidence.put("copy.closed", "true");
                evidence.put("copy.closeState",
                    "docsEmpty=" + empty + ",unfocused=" + unfocused);
                return;
            }
            sleep(POLL_MILLIS);
        }
        evidence.put("copy.closed", "timeout");
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
            if (!"true".equals(evidence.values.get("copy.bound"))) {
                unmet.add("task-owned copy was not bound as the active document");
            }
            if (!"true".equals(evidence.values.get("copy.restored"))) {
                unmet.add("original document was not restored as active");
            }
            if (!"true".equals(evidence.values.get("copy.sameLiveDocument"))) {
                unmet.add("restored original was a fresh instance, not the same live document");
            }
            if (!"true".equals(evidence.values.get("copy.fileSha256Preserved"))) {
                unmet.add("original file bytes changed across the copy session");
            }
            if (!"true".equals(evidence.values.get("copy.modifiedPreserved"))) {
                unmet.add("original dirty flag changed across the copy session");
            }
            if (!"true".equals(evidence.values.get("copy.undoPreserved"))) {
                unmet.add("original undo state changed across the copy session");
            }
            if (!"true".equals(evidence.values.get("copy.selectionPreserved"))) {
                unmet.add("original selection changed across the copy session");
            }
            if (!"true".equals(evidence.values.get("copy.closed"))) {
                unmet.add("task-owned copy document was not closed");
            }
            if (!"true".equals(evidence.values.get("copy.fileRemoved"))) {
                unmet.add("task-owned copy file was not removed");
            }
        }
        if (!unmet.isEmpty()) {
            evidence.fail("UNMET:" + String.join("; ", unmet));
            return false;
        }
        return true;
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
