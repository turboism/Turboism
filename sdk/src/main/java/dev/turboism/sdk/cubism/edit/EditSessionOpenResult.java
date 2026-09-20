package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.cubism.id.DocumentId;
import java.util.Objects;

/**
 * Typed evidence that a session was admitted by the editor, equivalent to a successful
 * {@code EditBegin}.
 *
 * <p>Admission failures never produce this value: they surface as
 * {@link EditUnavailableException}, {@link dev.turboism.sdk.permission.CubismPermissionException},
 * or another {@link EditSessionException} from
 * {@link EditSessionService#open}. A live session reports its own evidence through
 * {@link EditSession#openResult()}.
 *
 * @param document the model document the session edits
 * @param options the options the session was opened with
 */
public record EditSessionOpenResult(DocumentId document, EditSessionOptions options) {

    public EditSessionOpenResult {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(options, "options");
    }
}
