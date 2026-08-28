package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FxHostValidationProbeTest {

    @Test
    void automatedHostCloseUsesExactHostVersionRoute() {
        assertEquals(
            FxHostValidationProbe.HostCloseRoute.SYNTHETIC_WINDOW_CLOSING,
            FxHostValidationProbe.hostCloseRoute("5203")
        );
        assertEquals(
            FxHostValidationProbe.HostCloseRoute.ROBOT_ALT_F4,
            FxHostValidationProbe.hostCloseRoute("5302")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FxHostValidationProbe.hostCloseRoute("unknown")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FxHostValidationProbe.hostCloseRoute(null)
        );
    }

    @Test
    void automatedHostCloseRequiresAVisibleDisplayableNonDialogWindow() {
        assertThrows(
            IllegalStateException.class,
            () -> FxHostValidationProbe.selectHostWindow(new java.awt.Window[0])
        );
    }
}
