package dev.turboism.ui.toolbar;

import java.util.List;
import java.util.Objects;

/** Native-independent operations required by the palette-toolbar provider. */
public interface PaletteToolbarHostOperations {

    /** Replaces the complete four-Palette contribution snapshot and reconciles it immediately. */
    void setContributions(List<ButtonContribution> contributions);

    /** Runs one lifecycle reconciliation immediately. */
    void reconcileNow();

    /** Clears only Toolbar contributions while preserving Filter slots and shared lifecycle polling. */
    void clearContributions();

    /** Returns whether native contribution buttons are currently materialized. */
    boolean hasLiveButtons();

    /** One normalized button and its routed plugin action. */
    record ButtonContribution(
        PaletteToolbarContributionDescriptor descriptor,
        Runnable action
    ) {
        public ButtonContribution {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(action, "action");
        }
    }
}
