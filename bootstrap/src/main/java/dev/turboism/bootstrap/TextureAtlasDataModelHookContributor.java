package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasDataModelHookInstaller;

/** Declarative contributor for the verified texture-atlas data-model hook. */
final class TextureAtlasDataModelHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_TEXTURE_ATLAS_DATA_MODEL_HOOK";
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
        final VerifiedTextureAtlasDataModelHookInstaller installer =
            VerifiedTextureAtlasDataModelHookInstaller.fromVerifiedResolver(
                environment.instrumentation(),
                runtime.editorModelResolver(),
                host.classLoader(),
                runtime.textureAtlasDataModelCapture()
            );
        installer.install();
        runtime.info("bootstrap", id() + " installation=COMPLETE");
        return installer;
    }
}
