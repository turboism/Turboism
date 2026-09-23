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
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface ExportSettingsContributionService {

    /**
     * Registers one default-off export settings option. Duplicate option ids fail.
     *
     * @param contribution immutable contribution owning the option and its decision callback
     * @return registration whose close removes the contribution
     */
    Registration contribute(ExportSettingsContribution contribution);

    /** Safe-mode instance: contribution is refused. */
    static ExportSettingsContributionService unavailable() {
        return Unavailable.INSTANCE;
    }

    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    enum Unavailable implements ExportSettingsContributionService {
        INSTANCE;

        @Override
        public Registration contribute(final ExportSettingsContribution contribution) {
            throw new UnsupportedOperationException(
                "export settings contribution service is not available"
            );
        }
    }
}
