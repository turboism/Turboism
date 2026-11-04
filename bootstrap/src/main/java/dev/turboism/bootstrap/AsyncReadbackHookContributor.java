package dev.turboism.bootstrap;

/**
 * Contributor for the opt-in pipelined canvas readback ({@code launcher.asyncReadback}).
 *
 * <p>The hook rewrites one JOGL call and changes visible behaviour: the canvas paints the frame
 * that was read one paint earlier, so the image trails the UI by one frame. It is therefore
 * default-off, and only {@code pipelined=ACTIVE} — not {@code installation=COMPLETE} — proves the
 * reviewed rewrite was admitted.</p>
 */
final class AsyncReadbackHookContributor extends NativeOptimizationHookContributor {

    AsyncReadbackHookContributor() {
        super("TURBOISM_ASYNC_READBACK", VerifiedAsyncReadbackInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.asyncReadbackEnabled(environment)) {
            debug(environment, id() + " pipelined=INACTIVE installation=NOT_ADMITTED reason=preference-disabled");
            return refused("preference-disabled");
        }
        try {
            final var host = environment.host().orElseThrow();
            final VerifiedAsyncReadbackInstaller installer = new VerifiedAsyncReadbackInstaller(
                    environment.instrumentation(), host.artifact(), host.classLoader());
            installer.install();
            log(environment, id() + " pipelined=ACTIVE oneFrameLatency=true");
            return installer;
        } catch (Exception | Error failure) {
            if (environment.runtime().isPresent()) {
                environment.runtime().get().warn("bootstrap", id() + " pipelined=INACTIVE reason=installation-failed");
            }
            throw failure;
        }
    }
}
