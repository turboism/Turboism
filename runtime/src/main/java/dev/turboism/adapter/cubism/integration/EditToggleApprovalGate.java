package dev.turboism.adapter.cubism.integration;

import java.util.Objects;

/**
 * {@link EditApprovalGate} attachment-point skeleton backed by the native 「编辑」 checkbox
 * state (spec 051, Phase 1).
 *
 * <p>On 5.4 the integration dialog's edit checkbox is the single global approval surface:
 * checked means registered clients may edit, unchecked means they may not — no per-connection
 * prompt exists. This gate expresses that same source of truth: both queries answer the
 * toggle state and never prompt. It is deliberately <em>not wired</em> into
 * {@link EditBridgeEnvironment#production} yet — {@link SwingEditApprovalGate} remains the
 * production gate until the Phase-2 semantic migration decides how the connection-time
 * prompt degrades (hint text on unchecked vs removal).</p>
 *
 * <p>Fail-closed by construction: an uninstalled injector never flips the state, so this
 * gate denies everything exactly like {@link EditApprovalGate#denyAll()}.</p>
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
}
