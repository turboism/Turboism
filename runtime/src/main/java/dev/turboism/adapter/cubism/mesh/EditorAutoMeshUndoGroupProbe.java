package dev.turboism.adapter.cubism.mesh;

import dev.turboism.automesh.AutoMeshUndoGroupProbe;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorEditSessionSelectorContract;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Detects an open native Editor Undo group through the verified Editor-model selectors.
 *
 * <p>The probe reads the modeling document's edit mode and asks it for its current group. The
 * Editor assigns that group in {@code beginEdit} and clears it in {@code endEdit}, so a non-null
 * value means one host edit group is open right now. Both members are verified selectors of the
 * reviewed Editor-model record, and the probe is admitted only when the record authorizes the whole
 * edit-session envelope.</p>
 *
 * <p>Limitation: a host whose record does not authorize that envelope answers "closed" and reports
 * one diagnostic. The guard runs at funnel entry, before the host opens its own group, so the
 * unchecked case covers a narrow window rather than the normal confirmation path. A host that
 * cannot answer is therefore not denied the custom run; the dispatcher still refuses a run whose
 * own transaction is rejected.</p>
 */
public final class EditorAutoMeshUndoGroupProbe implements AutoMeshUndoGroupProbe {

    private static final String APP_CONTROLLER = "cubism.editor-model.app-controller.instance";
    private static final String CURRENT_DOCUMENT = "cubism.editor-model.app-controller.current-document";
    private static final String MODELING_DOCUMENT_CLASS = "cubism.editor-model.modeling-document.class";
    private static final String MODELING_EDIT_MODE = "cubism.editor-model.modeling-document.edit-mode";
    private static final String CURRENT_UNDO = "cubism.editor-model.edit-mode.current-undo";
    private static final String UNAVAILABLE_DIAGNOSTIC =
            "auto mesh guard: the host Undo-group probe is unauthorized, so an open group is not detected";

    private final VerifiedMemberResolver resolver;
    private final Consumer<String> diagnostics;
    private final AtomicBoolean reported = new AtomicBoolean();

    /**
     * Creates the probe.
     *
     * @param resolver the verified Editor-model resolver of the active session
     * @param diagnostics receives the one-time limitation note; it must not throw
     */
    public EditorAutoMeshUndoGroupProbe(final VerifiedMemberResolver resolver, final Consumer<String> diagnostics) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public boolean undoGroupOpen() {
        if (!authorized()) {
            reportOnce();
            return false;
        }
        try {
            final Object app = resolver.invokeStatic(APP_CONTROLLER);
            if (app == null) {
                return false;
            }
            final Object document = resolver.invoke(CURRENT_DOCUMENT, app);
            if (!resolver.isInstance(MODELING_DOCUMENT_CLASS, document)) {
                return false;
            }
            final Object editMode = resolver.invoke(MODELING_EDIT_MODE, document);
            return editMode != null && resolver.invoke(CURRENT_UNDO, editMode) != null;
        } catch (RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            reportOnce();
            return false;
        }
    }

    private boolean authorized() {
        try {
            return resolver.authorizesFeature(
                    EditorEditSessionSelectorContract.ADAPTER_SLICE_ID,
                    EditorEditSessionSelectorContract.EDIT_END_CAPABILITY_ID,
                    EditorEditSessionSelectorContract.EDIT_END_REQUIRED_ALIASES);
        } catch (RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    private void reportOnce() {
        if (!reported.compareAndSet(false, true)) {
            return;
        }
        try {
            diagnostics.accept(UNAVAILABLE_DIAGNOSTIC);
        } catch (RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            // A diagnostic sink must never change the probe's answer.
        }
    }
}
