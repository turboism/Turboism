package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.plugin.PluginContext;
import java.util.Objects;

/**
 * Entry point of the editing surface: opens {@link EditSession}s on model documents.
 *
 * <p>Opening a session is the Turboism equivalent of the official {@code EditBegin}: the editor
 * admits the session only when editing is approved for the document, the caller holds the
 * {@code turboism.cubism.edit} permission, and every operation the runtime will need has verified
 * bindings for the connected editor build. A refused admission raises a typed {@link
 * EditSessionException} (or {@link dev.turboism.sdk.permission.CubismPermissionException} for
 * permission denial); it never returns a half-open session.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface EditSessionService {

    /**
     * Reports whether the editor currently accepts edit sessions ({@code GetIsEditApproval}).
     *
     * @return {@code true} only when approval is positively confirmed
     * @throws EditSessionException when the approval state cannot be determined — including when
     *     the service is unavailable
     */
    boolean isEditApproved(PluginContext context) throws EditSessionException;

    /**
     * Opens an edit session on {@code document} with default options.
     *
     * @return the admitted session
     * @throws EditSessionException when admission is refused
     */
    default EditSession open(final PluginContext context, final DocumentId document)
            throws EditSessionException {
        return open(context, document, EditSessionOptions.defaults());
    }

    /**
     * Opens an edit session on {@code document}.
     *
     * @return the admitted session
     * @throws EditSessionException when admission is refused
     */
    EditSession open(PluginContext context, DocumentId document, EditSessionOptions options)
            throws EditSessionException;

    /**
     * Returns a fail-closed implementation: {@link #open} validates its arguments and hands back
     * {@link EditSession#unavailable()}, and {@link #isEditApproved} fails closed.
     */
    static EditSessionService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Singleton fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements EditSessionService {
        INSTANCE;

        @Override
        public boolean isEditApproved(final PluginContext context) throws EditSessionException {
            Objects.requireNonNull(context, "context");
            throw new EditUnavailableException("EditSessionService.isEditApproved");
        }

        @Override
        public EditSession open(
                final PluginContext context,
                final DocumentId document,
                final EditSessionOptions options) {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(options, "options");
            return EditSession.unavailable();
        }
    }
}
