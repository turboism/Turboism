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

    /** True only when the provider can safely retain its current native registration. */
    default boolean supportsIncrementalReconcile() {
        return false;
    }

    Registration apply(long hostGeneration, List<EditorUiContribution<?>> contributions);

    /**
     * Reconciles a changed logical snapshot while retaining the existing native registration.
     * Called only when {@link #supportsIncrementalReconcile()} returns true.
     */
    default Registration reconcile(
        final long hostGeneration,
        final List<EditorUiContribution<?>> contributions,
        final Registration existing
    ) {
        throw new UnsupportedOperationException("provider does not support incremental reconcile");
    }
}
