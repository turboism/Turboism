package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.regex.Pattern;

/** One plugin-owned transient native-control style contribution. */
@PreviewApi
public record ControlAppearanceContribution(
    String id,
    ControlAppearanceTarget target,
    ControlAppearanceStyle style
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    public ControlAppearanceContribution {
        id = Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id is invalid");
        }
        target = Objects.requireNonNull(target, "target");
        style = Objects.requireNonNull(style, "style");
    }
}
