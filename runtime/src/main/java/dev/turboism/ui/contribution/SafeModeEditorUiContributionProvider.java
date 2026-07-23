package dev.turboism.ui.contribution;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.List;
import java.util.Objects;

/** Explicit fail-closed provider for an unavailable Editor UI family. */
public final class SafeModeEditorUiContributionProvider implements EditorUiContributionProvider {

    private final EditorUiFamily family;
    private final EditorUiProviderAdmission admission;

    public SafeModeEditorUiContributionProvider(
        final EditorUiFamily family,
        final String diagnosticId
    ) {
        this.family = Objects.requireNonNull(family, "family");
        this.admission = EditorUiProviderAdmission.safeMode(family, diagnosticId);
    }

    @Override
    public EditorUiFamily family() {
        return family;
    }

    @Override
    public EditorUiProviderAdmission admission() {
        return admission;
    }

    @Override
    public Registration apply(
        final long hostGeneration,
        final List<EditorUiContribution<?>> contributions
    ) {
        throw new IllegalStateException("Editor UI provider is unavailable for " + family);
    }
}
