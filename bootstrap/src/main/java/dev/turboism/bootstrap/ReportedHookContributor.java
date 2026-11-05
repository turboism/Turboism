package dev.turboism.bootstrap;

/**
 * Contributor whose installation outcome
 * {@link dev.turboism.config.NativeOptimizationDiagnostics} records.
 *
 * <p>The recorded protocol id is the contributor id, so this marker lets the
 * diagnostics protocol table and the hook manifest be checked against each
 * other: a contributing hook that reports under a protocol the table does not
 * know is a review decision, not an accident.</p>
 */
interface ReportedHookContributor extends HookContributor {

    /**
     * @return protocol id this contributor's installation outcome is recorded under
     */
    default String protocolId() {
        return id();
    }
}
