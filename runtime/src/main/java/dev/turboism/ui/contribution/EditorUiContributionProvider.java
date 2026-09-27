package dev.turboism.ui.contribution;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EditorUiFamily;

import java.util.List;

/** Native-provider seam for one declarative Editor UI contribution family. */
public interface EditorUiContributionProvider {

    /**
     * @return the contribution family this provider serves
     */
    EditorUiFamily family();

    /** Admission is explicit; production providers cannot inherit test-only availability. */
    EditorUiProviderAdmission admission();

    /**
     * @return whether this provider may install contributions on the connected host —
     *         the default derives it from {@link #admission()}
     */
    default boolean isAvailable() {
        return admission().isAdmitted();
    }

    /** True only when the provider can safely retain its current native registration. */
    default boolean supportsIncrementalReconcile() {
        return false;
    }

    /**
     * Installs the family's current contributions natively.
     *
     * @param hostGeneration the verified connection generation this install belongs to
     * @param contributions the logical contributions to install
     * @return a registration that removes everything installed when disposed
     */
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
