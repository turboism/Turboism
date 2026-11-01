package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasAutoLayoutHookInstaller;

/** Declarative contributor for the verified texture-atlas automatic-layout hook. */
final class TextureAtlasAutoLayoutHookContributor implements HookContributor {

    @Override
    public String id() {
        return "TURBOISM_TEXTURE_ATLAS_AUTO_LAYOUT_HOOK";
    }

    @Override
    public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override
    public boolean closesOnProcessExit() {
        return true;
    }

    @Override
    public boolean admitted(final HookEnvironment environment) {
        return environment.hookRuntimeAdmitted();
    }

    @Override
    public java.util.Set<String> runtimeHookIds() {
        return java.util.Set.of("texture-atlas");
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        final VerifiedTextureAtlasAutoLayoutHookInstaller installer =
                VerifiedTextureAtlasAutoLayoutHookInstaller.fromVerifiedResolver(
                        environment.instrumentation(),
                        runtime.editorModelResolver(),
                        host.classLoader(),
                        runtime.hostAccess().textureAtlasNativeInvocations(),
                        runtime.hostAccess().textureAtlasAutoLayoutDispatch(),
                        runtime.hostAccess().textureAtlasEditorUi(),
                        runtime.hostAccess().textureAtlasAlgorithms(),
                        runtime.effectiveLocale());
        // The status names what this installation actually rewrote: a registered transformer alone
        // leaves the host bytes untouched until the automatic-layout class loads.
        final VerifiedTextureAtlasAutoLayoutHookInstaller.Installation installation = installer.install();
        runtime.info(
                "bootstrap",
                id()
                        + (installation == VerifiedTextureAtlasAutoLayoutHookInstaller.Installation.RETRANSFORMED
                                ? " installation=COMPLETE"
                                : " installation=ARMED_NOT_LOADED"));
        return installer;
    }
}