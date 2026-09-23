package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DocumentId;
import java.util.Objects;
import java.util.Optional;

/**
 * An admitted editing session on one model document — the Turboism equivalent of the span
 * between the official {@code EditBegin} and {@code EditEnd} requests.
 *
 * <p>While a session is open the editor locks its editing UI and operations run inside a single
 * undoable transaction; {@link #close()} commits (one history entry may be recorded) and {@link
 * #cancel()} restores the pre-session model state. Exactly one session can be open per document
 * at a time.
 *
 * <p>Operations are grouped into five families reachable through the accessor methods:
 * {@link #parameterKeys()}, {@link #parameterStructure()}, {@link #selection()},
 * {@link #partObjects()}, and {@link #deformers()}.
 *
 * <p>Every method on a session that is not {@link EditSessionState#OPEN} fails closed: work after
 * cancellation raises {@link EditCancelledException}, work after close or on {@link
 * #unavailable()} raises {@link EditUnavailableException}.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface EditSession {

    /**
     * Returns the document this session edits.
     *
     * @throws EditUnavailableException when the session is unavailable
     */
    DocumentId document() throws EditSessionException;

    /**
     * Returns the current lifecycle state.
     *
     * @throws EditUnavailableException when the session is unavailable
     */
    EditSessionState state() throws EditSessionException;

    /**
     * Returns the typed evidence of this session's admission.
     *
     * @throws EditUnavailableException when the session is unavailable
     */
    EditSessionOpenResult openResult() throws EditSessionException;

    /**
     * Returns who cancelled this session; empty while the session is open or was committed.
     *
     * @throws EditUnavailableException when the session is unavailable
     */
    Optional<CancelSource> cancelledBy() throws EditSessionException;

    /**
     * Returns {@code true} while the session admits edit operations. This is the only member that
     * is safe to call on {@link #unavailable()}: it returns {@code false} there.
     */
    boolean isOpen();

    /**
     * Sends a log line to the editor's editing dialog ({@code EditSendLog}).
     *
     * @throws EditSessionException when the session cannot accept work
     */
    void log(String message) throws EditSessionException;

    /**
     * Reports progress to the editor's editing dialog ({@code EditSendProgress}).
     *
     * @param value progress in {@code [0.0, 1.0]}
     * @throws EditSessionException when the session cannot accept work
     */
    void progress(double value) throws EditSessionException;

    /**
     * Ends the session by cancelling it ({@code EditEnd} with {@code Cancel = true}): the model
     * is restored to its pre-session state and no history entry is recorded.
     *
     * @return the typed close outcome
     * @throws EditSessionException when the session cannot be ended
     */
    EditSessionCloseResult cancel() throws EditSessionException;

    /**
     * Ends the session by committing it ({@code EditEnd}); the model keeps the edits.
     *
     * @return the typed close outcome
     * @throws EditSessionException when the session cannot be ended
     */
    EditSessionCloseResult close() throws EditSessionException;

    /** Returns the parameter-key (keyform) operation family. */
    ParameterKeyOps parameterKeys();

    /** Returns the parameter-structure operation family. */
    ParameterStructureOps parameterStructure();

    /** Returns the selection operation family. */
    SelectionOps selection();

    /** Returns the parts/object operation family. */
    PartObjectOps partObjects();

    /** Returns the deformer operation family. */
    DeformerOps deformers();

    /**
     * Returns a fail-closed session handle: {@link #isOpen()} is {@code false} and every other
     * member fails with {@link EditUnavailableException}. Returned by {@link
     * EditSessionService#unavailable()} so call sites can stay branch-free.
     */
    static EditSession unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Singleton fail-closed session returned by {@link #unavailable()}. */
    enum Unavailable implements EditSession {
        INSTANCE;

        @Override
        public DocumentId document() throws EditSessionException {
            throw new EditUnavailableException("EditSession.document");
        }

        @Override
        public EditSessionState state() throws EditSessionException {
            throw new EditUnavailableException("EditSession.state");
        }

        @Override
        public EditSessionOpenResult openResult() throws EditSessionException {
            throw new EditUnavailableException("EditSession.openResult");
        }

        @Override
        public Optional<CancelSource> cancelledBy() throws EditSessionException {
            throw new EditUnavailableException("EditSession.cancelledBy");
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void log(final String message) throws EditSessionException {
            Objects.requireNonNull(message, "message");
            throw new EditUnavailableException("EditSession.log");
        }

        @Override
        public void progress(final double value) throws EditSessionException {
            throw new EditUnavailableException("EditSession.progress");
        }

        @Override
        public EditSessionCloseResult cancel() throws EditSessionException {
            throw new EditUnavailableException("EditSession.cancel");
        }

        @Override
        public EditSessionCloseResult close() throws EditSessionException {
            throw new EditUnavailableException("EditSession.close");
        }

        @Override
        public ParameterKeyOps parameterKeys() {
            return ParameterKeyOps.unavailable();
        }

        @Override
        public ParameterStructureOps parameterStructure() {
            return ParameterStructureOps.unavailable();
        }

        @Override
        public SelectionOps selection() {
            return SelectionOps.unavailable();
        }

        @Override
        public PartObjectOps partObjects() {
            return PartObjectOps.unavailable();
        }

        @Override
        public DeformerOps deformers() {
            return DeformerOps.unavailable();
        }
    }
}
