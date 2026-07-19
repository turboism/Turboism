package dev.turboism.plugin.perfopt.b1.domain;

import java.util.Objects;

public record FpsPresentationPreference(FpsPresentationState state) {
    public FpsPresentationPreference {
        state = Objects.requireNonNull(state, "state");
    }

    public static FpsPresentationPreference defaults() {
        return new FpsPresentationPreference(FpsPresentationState.DISABLED);
    }

    public boolean enabled() {
        return state == FpsPresentationState.ENABLED;
    }

    public FpsPresentationPreference toggle() {
        return setEnabled(!enabled());
    }

    public FpsPresentationPreference setEnabled(final boolean enabled) {
        final FpsPresentationState next = enabled
            ? FpsPresentationState.ENABLED : FpsPresentationState.DISABLED;
        return next == state ? this : new FpsPresentationPreference(next);
    }

    public FpsPresentationPreference onPluginDisabled() {
        return this;
    }
}
