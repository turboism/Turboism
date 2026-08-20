package dev.turboism.sdk.ui;

import java.util.List;
import java.util.Objects;

/**
 * A plugin’s ask for user-mediated access to a file it cannot name itself.
 *
 * @param id                caller-chosen request identity, non-blank, at most 128
 *                          characters and free of control characters
 * @param title             chooser title shown to the user, non-blank, at most 256
 *                          characters and free of control characters
 * @param allowedExtensions defensively copied, immutable list of acceptable
 *                          extensions; each must match {@code [A-Za-z0-9][A-Za-z0-9+_-]*}
 *                          and be at most 32 characters. Empty means no filter.
 * @param mode              the single access direction being requested
 * @param lifetime          how long a resulting grant should remain usable
 * @throws IllegalArgumentException when text bounds are violated or an extension
 *     is malformed
 * @throws NullPointerException when any component is {@code null}
 */
public record UserFileRequest(
    String id,
    String title,
    List<String> allowedExtensions,
    UserFileMode mode,
    UserFileLifetime lifetime
) {
    public UserFileRequest {
        id = UserFileContracts.requireId(id);
        title = UserFileContracts.requireTitle(title);
        allowedExtensions = UserFileContracts.extensions(allowedExtensions);
        mode = Objects.requireNonNull(mode, "mode");
        lifetime = Objects.requireNonNull(lifetime, "lifetime");
    }
}
