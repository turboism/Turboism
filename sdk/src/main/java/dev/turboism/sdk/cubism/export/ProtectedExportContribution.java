package dev.turboism.sdk.cubism.export;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;
import java.util.Objects;

/**
 * Immutable protected-export contribution: one default-off option plus the
 * {@link ProtectedExportPlanner} that decides how the staged copy is rewritten.
 *
 * <p>The option is registered exactly like an {@link ExportSettingsContribution} — same
 * decision callback, same default-off semantics — and carries planning authority in the
 * type so the released inert record stays frozen. The runtime still owns the pipeline:
 * it builds the census, calls the planner and verifies the plan before it stages
 * anything.</p>
 */
@Incubating
@CubismEditor({"5.2.03", "5.3.02", "5.3.03", "5.3.04"})
public record ProtectedExportContribution(
        String optionId, String labelKey, ExportSettingsDecisionCallback callback, ProtectedExportPlanner planner) {
    private static final int MAX_KEY_LENGTH = 128;

    public ProtectedExportContribution {
        optionId = requireKey(optionId, "optionId");
        labelKey = requireKey(labelKey, "labelKey");
        callback = Objects.requireNonNull(callback, "callback");
        planner = Objects.requireNonNull(planner, "planner");
    }

    private static String requireKey(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(name + " must contain 1-" + MAX_KEY_LENGTH + " characters");
        }
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a lowercase config key");
        }
        return value;
    }
}
