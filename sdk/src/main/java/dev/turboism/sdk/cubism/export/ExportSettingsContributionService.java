package dev.turboism.sdk.cubism.export;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.plugin.Registration;

/**
 * Plugin-scoped registry for contributions to the native embedded-model Export
 * Settings flow.
 *
 * <p>A contribution registers one default-off boolean option and its typed decision
 * callback. Closing the returned {@link Registration} removes the contribution;
 * closing the plugin scope removes every contribution of the plugin.</p>
 *
 * <p>This surface is inert: it never mutates a model, never executes an export, and
 * never implies execution. A selected contribution is resolved by the runtime in a
 * later host-orchestration phase; until then it fails closed.</p>
 */
@CubismEditor({
    "5.1.00", "5.1.01", "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02",
    "5.3.03", "5.3.04"
})
public interface ExportSettingsContributionService {

    /**
     * Registers one default-off export settings option. Duplicate option ids fail.
     *
     * @param contribution immutable contribution owning the option and its decision callback
     * @return registration whose close removes the contribution
     */
    Registration contribute(ExportSettingsContribution contribution);

    /**
     * Registers one default-off protected-export option together with the planner that
     * rewrites the staging copy's identities and flatten order.
     *
     * <p>Same registration lifetime and duplicate-option rule as {@link #contribute}: a
     * protected-export contribution is an ordinary option that additionally carries
     * planning authority. The default refuses, so a service that does not support
     * protected export fails closed instead of silently dropping the planner.</p>
     *
     * @param contribution immutable contribution owning the option, its decision callback
     *     and its planner
     * @return registration whose close removes the contribution
     * @throws UnsupportedOperationException when this service does not accept protected
     *     export contributions
     */
    default Registration contributeProtectedExport(final ProtectedExportContribution contribution) {
        throw new UnsupportedOperationException("protected export contribution is not available");
    }

    /** Safe-mode instance: contribution is refused. */
    static ExportSettingsContributionService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed {@link #unavailable()} sentinel: every contribution is refused. */
    @CubismEditor({
        "5.1.00", "5.1.01", "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02",
        "5.3.03", "5.3.04"
    })
    enum Unavailable implements ExportSettingsContributionService {
        INSTANCE;

        @Override
        public Registration contribute(final ExportSettingsContribution contribution) {
            throw new UnsupportedOperationException("export settings contribution service is not available");
        }
    }
}
