package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.filechooser.FileChooserHistoryHostProfile;
import dev.turboism.mapping.verification.HostArtifactDigest;

/** Declarative contributor for the verified file-chooser history hook. */
final class FileChooserHistoryHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_FILE_CHOOSER_HISTORY_HOOK";
    }

    @Override public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override public boolean closesOnProcessExit() {
        return true;
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return environment.ordinaryReviewedRuntimeAdmitted();
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        final var profile = FileChooserHistoryHostProfile.forArtifact(
            HostArtifactDigest.from(host.artifact())
        ).orElseThrow(() -> new IllegalStateException(
            "Unsupported file-chooser history host artifact"
        ));
        final VerifiedFileChooserHistoryHookInstaller installer =
            new VerifiedFileChooserHistoryHookInstaller(
                environment.instrumentation(),
                host.classLoader(),
                profile,
                runtime.fileChooserHistoryService()
            );
        installer.install();
        runtime.info("bootstrap", id() + " installation=COMPLETE");
        return installer;
    }
}
