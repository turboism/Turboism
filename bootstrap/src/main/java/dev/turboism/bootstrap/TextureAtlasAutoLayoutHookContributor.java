package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasAutoLayoutHookInstaller;

/** Declarative contributor for the verified texture-atlas automatic-layout hook. */
final class TextureAtlasAutoLayoutHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_TEXTURE_ATLAS_AUTO_LAYOUT_HOOK";
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
        final VerifiedTextureAtlasAutoLayoutHookInstaller installer =
            VerifiedTextureAtlasAutoLayoutHookInstaller.fromVerifiedResolver(
                environment.instrumentation(),
                runtime.editorModelResolver(),
                host.classLoader(),
                runtime.hostAccess().textureAtlasNativeInvocations(),
                () -> {
                    final Object callback = System.getProperties().get(
                        VerifiedTextureAtlasAutoLayoutHookInstaller.PLUGIN_CALLBACK_KEY
                    );
                    return callback instanceof java.util.function.BooleanSupplier supplier
                        && supplier.getAsBoolean();
                },
                runtime.hostAccess().textureAtlasEditorUi(),
                runtime.hostAccess().textureAtlasAlgorithms(),
                runtime.effectiveLocale()
            );
        installer.install();
        runtime.info("bootstrap", id() + " installation=COMPLETE");
        return installer;
    }
}
