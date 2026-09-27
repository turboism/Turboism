package dev.turboism.adapter.cubism.integration;

/**
 * Turboism-side edit approval for one external connection (US4).
 *
 * <p>Low-version hosts have no native edit-approval concept — their integration settings carry
 * only the registration grant — so the bridge answers {@code GetIsEditApproval} and gates
 * editing methods through this interface. The production implementation mirrors the official
 * approval dialog; headless environments and tests inject a programmatic answer.</p>
 *
 * <p>Implementations must be side-effect free on {@link #isApproved}: it is the read-only
 * query {@code GetIsEditApproval} maps to. {@link #requestApproval} is the interaction point —
 * invoked once per undecided connection by the first method that requires edit approval; the
 * result is then cached on the connection state.</p>
 */
public interface EditApprovalGate {

    /**
     * Reports whether editing is currently granted for {@code connection} without interacting
     * with the user. This is the {@code GetIsEditApproval} read path.
     */
    boolean isApproved(EditConnectionInfo connection);

    /**
     * Resolves the grant for {@code connection}, prompting the user when the implementation is
     * interactive. Called when a method requiring edit approval arrives while the connection's
     * grant is still undecided.
     *
     * @return {@code true} when editing is (now) granted
     */
    boolean requestApproval(EditConnectionInfo connection);

    /**
     * {@return whether this gate answers a mutable global state rather than a per-connection
     * decision}
     *
     * <p>{@code true} marks the 051 approval source: the native integration-settings dialog's
     * 「编辑」 checkbox is a single global flag that may flip at any time, so the bridge
     * re-consults the gate on every gated request and {@code GetIsEditApproval} answers the
     * live state instead of the per-connection latch. {@code false} keeps the 050 semantics:
     * the first gated request resolves the grant once (interactive prompt) and the decision
     * latches on the connection state.</p>
     */
    default boolean isLiveState() {
        return false;
    }

    /** {@return a gate that never grants editing — the fail-closed default} */
    static EditApprovalGate denyAll() {
        return new EditApprovalGate() {
            @Override
            public boolean isApproved(final EditConnectionInfo connection) {
                return false;
            }

            @Override
            public boolean requestApproval(final EditConnectionInfo connection) {
                return false;
            }
        };
    }
}
