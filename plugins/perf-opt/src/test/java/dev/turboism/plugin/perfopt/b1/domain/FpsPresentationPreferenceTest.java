package dev.turboism.plugin.perfopt.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FpsPresentationPreferenceTest {

    @Test
    void isDisabledByDefaultAndReducesDeterministically() {
        final FpsPresentationPreference defaults = FpsPresentationPreference.defaults();
        assertEquals(FpsPresentationState.DISABLED, defaults.state());
        assertFalse(defaults.enabled());
        assertTrue(defaults.toggle().enabled());
        assertEquals(defaults, defaults.toggle().toggle());
        assertEquals(defaults, defaults.setEnabled(false));
        assertEquals(FpsPresentationState.ENABLED, defaults.setEnabled(true).state());
    }

    @Test
    void lifecycleDisableDoesNotErasePersistedPreference() {
        final FpsPresentationPreference enabled = FpsPresentationPreference.defaults().setEnabled(true);
        assertEquals(enabled, enabled.onPluginDisabled());
    }
}
