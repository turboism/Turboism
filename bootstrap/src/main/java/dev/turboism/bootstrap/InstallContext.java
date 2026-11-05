package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The launch facts one verified hook may see, resolved once per launch by the caller: the
 * instrumentation it registers through, the located artifact, the host loader, the launch startup
 * policy and the running JVM feature.
 *
 * <p>The startup policy is the launch snapshot, never a re-read of {@code config.json}: a hook that
 * re-read the home directory could admit or refuse differently from the agent that gated it.</p>
 *
 * @param instrumentation the instrumentation the hook registers through
 * @param artifact the located host artifact
 * @param hostLoader the host class loader
 * @param policy the launch startup policy, {@code null} for a hook that never consults it
 * @param jvmFeature {@code Runtime.version().feature()} of the running JVM
 */
record InstallContext(
        Instrumentation instrumentation,
        Path artifact,
        ClassLoader hostLoader,
        RuntimeStartupConfig policy,
        int jvmFeature) {

    InstallContext {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(hostLoader, "hostLoader");
    }

    /**
     * @param environment the environment of a hook that has already established its host
     * @return the context carrying the launch startup policy snapshot
     * @throws java.util.NoSuchElementException when no host is located yet
     */
    static InstallContext of(final HookEnvironment environment) {
        final var host = environment.host().orElseThrow();
        return new InstallContext(
                environment.instrumentation(),
                host.artifact(),
                host.classLoader(),
                environment.startupPolicy(),
                Runtime.version().feature());
    }

    /**
     * Facts for a hook whose own switch is a system property or preference and which therefore declares
     * {@link PolicyCheck#SKIP}; the resulting context carries no startup policy.
     *
     * @param instrumentation the instrumentation the hook registers through
     * @param artifact the located host artifact
     * @param hostLoader the host class loader
     * @return a context without a startup policy
     */
    static InstallContext of(final Instrumentation instrumentation, final Path artifact, final ClassLoader hostLoader) {
        return new InstallContext(
                instrumentation, artifact, hostLoader, null, Runtime.version().feature());
    }
}
