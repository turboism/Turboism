package dev.turboism.sdk.cubism.psd;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.plugin.Registration;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Workflow-specific, runtime-issued temporary PSD handle, not a general file or process API.
 *
 * <p>Every operation requires runtime registry, plugin ownership, permission and model-session
 * validation. User implementations do not acquire those rights. This API exposes no document path, URI, bytes or native host objects.</p>
 */
@CubismEditor(from = "5.0.00")
public interface PsdEditFile {
    /**
     * Opens this file using the system's default PSD association after runtime authorization.
     * An unavailable association must not change system settings or discard the temporary file.
     */
    CompletionStage<PsdFileOperationResult> openInDefaultApplication();

    /**
     * Opens this PSD with a user-selected executable, passing the PSD as a single argument.
     * The path must be absolute and identify an executable file; no command-line parsing occurs.
     * Blank paths use the system default association. Requires the same authorization as
     * {@link #openInDefaultApplication()}.
     *
     * @param executablePath executable path, or blank for the system default
     * @return the authorized launch result
     */
    default CompletionStage<PsdFileOperationResult> openInApplication(final String executablePath) {
        if (java.util.Objects.requireNonNull(executablePath, "executablePath").isBlank()) {
            return openInDefaultApplication();
        }
        throw new UnsupportedOperationException("custom PSD applications are not available");
    }

    /**
     * Subscribes to stable changed save versions, excluding the initial export baseline.
     * Callbacks run on a bounded worker, with consumer failures isolated. Closing the returned
     * registration stops only this subscription; callers should subscribe before opening the file.
     *
     * @param listener non-null consumer of runtime-issued stable revisions
     * @return subscription registration, independent of the file's overall lifetime
     */
    Registration observeSaves(Consumer<PsdFileRevision> listener);

    /**
     * Idempotently revokes this handle and waits for already-started work to settle.
     * No new mutation may start after revocation. Does not delete any temporary PSD or staging
     * file, kill an external application, or attempt to detect when that application closes.
     */
    CompletionStage<PsdFileOperationResult> stop();
}
