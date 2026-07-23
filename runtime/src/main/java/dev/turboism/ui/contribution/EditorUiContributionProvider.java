package dev.turboism.ui.contribution;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.List;

/** Native-provider seam for one declarative Editor UI contribution family. */
public interface EditorUiContributionProvider {

    EditorUiFamily family();

    /** Admission is explicit; production providers cannot inherit test-only availability. */
    EditorUiProviderAdmission admission();

    default boolean isAvailable() {
        return admission().isAdmitted();
    }

    Registration apply(long hostGeneration, List<EditorUiContribution<?>> contributions);
}
