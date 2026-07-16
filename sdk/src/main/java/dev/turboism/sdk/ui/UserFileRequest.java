package dev.turboism.sdk.ui;

import java.util.List;
import java.util.Objects;

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
