package dev.turboism.adapter.cubism.integration;

import java.util.Objects;

/**
 * {@link EditApprovalGate} backed by the native 「编辑」 checkbox state (spec 051, Phase 2).
 *
 * <p>On 5.4 the integration dialog's edit checkbox is the single global approval surface:
 * checked means registered clients may edit, unchecked means they may not — no per-connection
 * prompt exists. This gate expresses that same source of truth: both queries answer the
 * toggle state live and never prompt. It is wired into {@link EditBridgeEnvironment#production}
 * whenever the verified injector surface is admitted; {@link SwingEditApprovalGate} remains
 * the fallback gate when the feature is absent or killed, preserving the 050 connection-time
 * prompt on that path.</p>
 *
 * <p>{@link #isLiveState()} returns {@code true}: the router therefore re-consults the gate on
 * every gated request instead of latching a per-connection decision, which is what makes a
 * toggle flip take effect immediately in both directions — checked grants on the next request,
 * unchecked denies on the next request. Fail-closed by construction: an uninstalled injector
 * never flips the state, so this gate denies everything exactly like
 * {@link EditApprovalGate#denyAll()}.</p>
 */
public final class EditToggleApprovalGate implements EditApprovalGate {

    private final EditToggleState state;

    public EditToggleApprovalGate(final EditToggleState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public boolean isApproved(final EditConnectionInfo connection) {
        return state.isEnabled();
    }

    @Override
    public boolean requestApproval(final EditConnectionInfo connection) {
        // The checkbox IS the approval surface — unchecked answers denial, never a prompt.
        return state.isEnabled();
    }

    @Override
    public boolean isLiveState() {
        return true;
    }
}
