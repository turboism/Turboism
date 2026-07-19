package dev.turboism.plugin.renderopt.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

final class RenderOptInStateTest {

    @Test
    void reportsStableInertB1States() {
        final RenderOptInState defaults = RenderOptInState.defaults();
        assertFalse(defaults.requested());
        assertFalse(defaults.effectiveOptimization());
        assertEquals(RenderOptInReportStatus.NOT_REQUESTED, defaults.reportStatus());

        assertEquals(
            RenderOptInReportStatus.REQUESTED_PENDING_CAPABILITY,
            defaults.setRequested(true).reportStatus()
        );
        assertEquals(
            RenderOptInReportStatus.REQUESTED_UNSUPPORTED,
            defaults.setRequested(true).withSupport(RenderSupportStatus.UNSUPPORTED).reportStatus()
        );
        assertEquals(
            RenderOptInReportStatus.REQUESTED_SUPPORTED_BUT_NOT_APPLIED,
            defaults.setRequested(true).withSupport(RenderSupportStatus.SUPPORTED).reportStatus()
        );
        assertFalse(defaults.setRequested(true).withSupport(RenderSupportStatus.SUPPORTED).effectiveOptimization());
    }

    @Test
    void reducersAreValueStable() {
        final RenderOptInState value = new RenderOptInState(true, RenderSupportStatus.SUPPORTED);
        assertEquals(value, value.setRequested(true));
        assertEquals(value, value.withSupport(RenderSupportStatus.SUPPORTED));
    }
}
