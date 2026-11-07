package dev.turboism.kernel;

import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.home.TurboismHomeLayout;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Builds the protected-export orchestration owner {@link KernelRuntime} binds at startup.
 *
 * <p>The factory is deliberately fail-open into a degraded capability instead of fail-closed into
 * an unchecked export: a rejected chain leaves no orchestrator bound, so the export-settings
 * authority keeps serving the native dialog while a checked export stays rejected.</p>
 */
final class KernelProtectedExportFactory {

    private KernelProtectedExportFactory() {}

    /**
     * Builds the protected-export orchestrator when the reviewed record is present and its
     * whole pinned chain checks out (record hash, manifest agreement, byte-identical artifact,
     * static selector verification, host classloader attestation).
     *
     * <p>Any failure degrades to no orchestrator: the authority keeps serving the native dialog
     * and a checked option stays rejected, never silently downgraded to an unchecked export.</p>
     *
     * <p>The orchestrated owner is not a plugin id: it is whichever single plugin the capability
     * table admits for the protected-export surface, resolved from the live load report on every
     * use because plugins load after this factory runs.</p>
     */
    static dev.turboism.exportsettings.ProtectedExportOrchestrator createProtectedExportOrchestrator(
            final Optional<Path> protectedExportVerificationRecord,
            final Path normalizedHostArtifact,
            final ClassLoader verifiedHostClassLoader,
            final dev.turboism.exportsettings.RuntimeExportSettingsAuthority exportSettings,
            final HostRuntimeIngress ingress,
            final TurboismHomeLayout layout,
            final KernelLog log,
            final java.util.function.Consumer<dev.turboism.exportsettings.ExportSettingsVetoDiagnostic> vetoSurface,
            final java.util.function.Supplier<LocalPluginRuntime.LoadReport> loadReport) {
        if (protectedExportVerificationRecord.isEmpty()) {
            return null;
        }
        try {
            final dev.turboism.mapping.verification.VerifiedMemberResolver resolver =
                    dev.turboism.mapping.verification.SliceRegistry.PROTECTED_EXPORT
                            .resolverFactory()
                            .create(
                                    protectedExportVerificationRecord.orElseThrow(),
                                    normalizedHostArtifact,
                                    verifiedHostClassLoader);
            final java.util.function.Supplier<java.util.Optional<String>> orchestrationOwner =
                    () -> admittedOrchestrationOwner(loadReport.get(), log);
            final dev.turboism.exportsettings.ProtectedExportOrchestrator.EdtDispatcher edt =
                    new dev.turboism.exportsettings.ProtectedExportOrchestrator.EdtDispatcher() {
                        @Override
                        public <T> T call(final java.util.concurrent.Callable<T> action) throws Exception {
                            return dev.turboism.ui.host.EdtDispatch.callExact(
                                    "protected-export EDT operation",
                                    dev.turboism.ui.host.EdtDispatch.DEFAULT_ACCEPT_TIMEOUT,
                                    action);
                        }

                        @Override
                        public void submit(final Runnable task) {
                            dev.turboism.ui.host.EdtDispatch.post("protected-export EDT submission", task);
                        }
                    };
            final dev.turboism.sdk.cubism.core.MocLoader ownedMocLoader =
                    ingress.adapterAccess().coreRuntimeInfo().mocLoader();
            final dev.turboism.exportsettings.ProtectedExportOrchestrator orchestrator =
                    new dev.turboism.exportsettings.ProtectedExportOrchestrator(
                            new dev.turboism.exportsettings.VerifiedProtectedExportHostOperations(resolver),
                            new dev.turboism.exportsettings.ProtectedExportStaging(
                                    ownedMocLoader::load,
                                    ownedMocLoader
                                                    instanceof
                                                    dev.turboism.adapter.cubism.core.OwnedModelParameterWriter writer
                                            ? writer::writeParameterValue
                                            : null),
                            layout.runtimeStateDir().resolve("protected-export"),
                            orchestrationOwner,
                            "protected-export",
                            exportSettings::protectedExportRedirectSeamInstalled,
                            () -> orchestrationOwner
                                    .get()
                                    .map(exportSettings::pluginBindingLive)
                                    .orElse(false),
                            exportSettings::hostGeneration,
                            edt,
                            report -> {
                                log.info(
                                        "protected-export",
                                        "session=" + report.sessionId()
                                                + " reached=" + report.reached()
                                                + " published=" + report.published()
                                                + " failure=" + report.failureKey()
                                                + (report.failureDetail() == null
                                                        ? ""
                                                        : " detail=" + report.failureDetail()));
                                // A user-initiated chooser cancel explains itself; every other
                                // non-published terminal report must surface visibly.
                                if (!report.published()
                                        && !dev.turboism.exportsettings.ProtectedExportOrchestrator.EXPORT_CANCELLED_KEY
                                                .equals(report.failureKey())) {
                                    vetoSurface.accept(new dev.turboism.exportsettings.ExportSettingsVetoDiagnostic(
                                            report.failureKey() != null
                                                    ? report.failureKey()
                                                    : "protected-export.failed",
                                            report.failureDetail()));
                                }
                            },
                            10_000L,
                            600_000L);
            orchestrator.refusalReporter(vetoSurface);
            return orchestrator;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            log.warn(
                    "protected-export",
                    "Protected-export orchestration unavailable: "
                            + failure.getClass().getName());
            return null;
        }
    }

    /**
     * Resolves the single plugin entitled to drive the protected-export pipeline from the
     * capability table.
     *
     * <p>Fail-closed by construction: with no entitled owner nothing arms, and a second entitled
     * owner is a configuration error that leaves the pipeline disarmed instead of picking one of
     * the two.</p>
     */
    private static java.util.Optional<String> admittedOrchestrationOwner(
            final LocalPluginRuntime.LoadReport report, final KernelLog log) {
        if (report == null) {
            return java.util.Optional.empty();
        }
        try {
            return dev.turboism.core.lifecycle.CapabilityAdmission.admittedOwner(
                    report.loaded(), dev.turboism.core.lifecycle.CapabilityAdmission.EXPORT_PROTECTED_ORCHESTRATION);
        } catch (RuntimeException failure) {
            log.warn("protected-export", "Orchestration owner is not admissible: " + failure.getMessage());
            return java.util.Optional.empty();
        }
    }
}
