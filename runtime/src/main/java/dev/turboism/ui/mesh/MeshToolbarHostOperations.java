package dev.turboism.ui.mesh;

import dev.turboism.sdk.plugin.Registration;
import java.util.function.IntConsumer;

/** Toolkit-neutral native mesh-toolbar materialization seam. */
public interface MeshToolbarHostOperations {
    /** Adds one generation-owned tool button and returns its removal handle. */
    ButtonHandle addButton(MeshToolbarContributionDescriptor contribution, Runnable action);

    /** Adds one generation-owned radius control with an isolated change callback. */
    SliderHandle addSlider(MeshToolbarSliderContributionDescriptor contribution, IntConsumer onChanged);

    /** Rebuild signals dispose current controls before the provider rematerializes them. */
    default Registration onRebuild(final Runnable reconcile) {
        return () -> {};
    }

    /** Exact native button ownership and selected-state update. */
    interface ButtonHandle extends Registration {
        /** Synchronizes the native selected state with the custom-tool coordinator. */
        void setSelected(boolean selected);
    }

    /** Exact native slider ownership and integer value access. */
    interface SliderHandle extends Registration {
        /** Reads the current native slider value. */
        int value();

        /** Sets a bounded native slider value. */
        void setValue(int value);
    }
}
