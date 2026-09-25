package dev.turboism.bootstrap;

import dev.turboism.exportsettings.ExportSettingsHostProfile;
import dev.turboism.exportsettings.ProtectedExportChooserProfile;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.preview.PreviewRuntime;

/**
 * Declarative contributor for the verified export-settings hook and its
 * protected-export chooser redirect seam.
 *
 * <p>The chooser redirect only exists while the dialog bridge is live, so the
 * seam installs inside this contributor right after the dialog hook succeeds;
 * the returned handle closes the seam first so transformed exporter bytecode
 * never outlives the callbacks it invokes.</p>
 */
final class ExportSettingsHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_EXPORT_SETTINGS_HOOK";
    }

    @Override public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override public boolean closesOnProcessExit() {
        return true;
    }

    /**
     * Export-settings admission: the ordinary reviewed-runtime gate plus this hook's own exact
     * release requirement.
     *
     * <p>The shared gate admits every reviewed build, but the export-settings selectors are only
     * pinned for the exact reviewed 5.2.03, 5.3.02 and 5.3.03 builds. Narrowing here keeps the
     * gate and the capability in agreement, so an admitted-but-unsupported build never even
     * attempts installation.</p>
     */
    @Override public boolean admitted(final HookEnvironment environment) {
        return runtimeAdmitted(environment.profile(), environment.fullRuntimeAdmission());
    }

    static boolean runtimeAdmitted(final String profile, final boolean fullRuntimeAdmission) {
        return fullRuntimeAdmission
            && ReviewedHostArtifacts.admitsFullRuntime(profile)
            && ExportSettingsHostProfile.supportedHostVersions().contains(profile);
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final PreviewRuntime runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        final var profile = ExportSettingsHostProfile.forArtifact(
            HostArtifactDigest.from(host.artifact())
        ).orElseThrow(() -> new IllegalStateException(
            "Unsupported export-settings host artifact"
        ));
        final VerifiedExportSettingsHookInstaller installer =
            VerifiedExportSettingsHookInstaller.fromHostProfile(
                environment.instrumentation(),
                profile,
                runtime.exportSettingsAuthority(),
                host.classLoader()
            );
        try {
            installer.install();
        } catch (Throwable failure) {
            installer.close();
            throw failure;
        }
        runtime.info("bootstrap", id() + " installation=COMPLETE");
        final VerifiedProtectedExportHookInstaller protectedExportHook =
            installProtectedExportChooserHook(environment, runtime, host);
        return () -> {
            try {
                if (protectedExportHook != null) {
                    protectedExportHook.close();
                }
            } finally {
                installer.close();
            }
        };
    }

    /**
     * Installs the protected-export chooser redirect on the exporter driver.
     *
     * <p>The bridge is already live from the export-settings hook, so this transformer only adds
     * the bytecode seam on {@code com/live2d/cubism/doc/model/exporter/b}. Failure degrades to
     * checked-always-reject: the authority keeps serving the dialog hook and every armed attempt
     * is refused because the staging redirect cannot be proven.</p>
     */
    private static VerifiedProtectedExportHookInstaller installProtectedExportChooserHook(
        final HookEnvironment environment,
        final PreviewRuntime runtime,
        final HostClassLocator.LocatedHost host
    ) {
        VerifiedProtectedExportHookInstaller installer = null;
        try {
            final var profile = ProtectedExportChooserProfile.forArtifact(
                HostArtifactDigest.from(host.artifact())
            ).orElseThrow(() -> new IllegalStateException(
                "Unsupported protected-export chooser host artifact"
            ));
            installer = VerifiedProtectedExportHookInstaller.fromHostProfile(
                environment.instrumentation(),
                profile,
                host.classLoader()
            );
            if (!installer.install()) {
                installer.close();
                runtime.warn(
                    "bootstrap",
                    "Turboism protected-export chooser redirect unavailable; checked export stays rejected"
                );
                return null;
            }
            runtime.exportSettingsAuthority().markProtectedExportRedirectSeamInstalled();
            runtime.info("bootstrap", "TURBOISM_PROTECTED_EXPORT_HOOK installation=COMPLETE");
            return installer;
        } catch (Throwable failure) {
            if (installer != null) {
                try {
                    installer.close();
                } catch (Throwable suppressed) {
                    failure.addSuppressed(suppressed);
                }
            }
            runtime.warn(
                "bootstrap",
                "Turboism protected-export chooser hook disabled safely: "
                    + failure.getClass().getName()
            );
            return null;
        }
    }
}
