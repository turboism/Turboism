package dev.turboism.plugin.renderopt.b1.domain;

import java.util.Objects;

public record RenderOptInState(boolean requested, RenderSupportStatus supportStatus) {
    public RenderOptInState {
        supportStatus = Objects.requireNonNull(supportStatus, "supportStatus");
    }

    public static RenderOptInState defaults() {
        return new RenderOptInState(false, RenderSupportStatus.UNVERIFIED);
    }

    public boolean effectiveOptimization() {
        return false;
    }

    public RenderOptInReportStatus reportStatus() {
        if (!requested) {
            return RenderOptInReportStatus.NOT_REQUESTED;
        }
        return switch (supportStatus) {
            case UNVERIFIED -> RenderOptInReportStatus.REQUESTED_PENDING_CAPABILITY;
            case UNSUPPORTED -> RenderOptInReportStatus.REQUESTED_UNSUPPORTED;
            case SUPPORTED -> RenderOptInReportStatus.REQUESTED_SUPPORTED_BUT_NOT_APPLIED;
        };
    }

    public RenderOptInState setRequested(final boolean value) {
        return requested == value ? this : new RenderOptInState(value, supportStatus);
    }

    public RenderOptInState withSupport(final RenderSupportStatus value) {
        Objects.requireNonNull(value, "value");
        return supportStatus == value ? this : new RenderOptInState(requested, value);
    }
}
