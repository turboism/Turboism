package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The launch facts one verified hook may see, resolved once per launch by the caller: the
 * instrumentation it registers through, the located artifact, the host loader, the launch startup
 * policy, the running JVM feature and the loaded-class snapshot of this install phase.
 *
 * <p>The startup policy is the launch snapshot, never a re-read of {@code config.json}: a hook that
 * re-read the home directory could admit or refuse differently from the agent that gated it.</p>
 *
 * @param instrumentation the instrumentation the hook registers through
 * @param artifact the located host artifact
 * @param hostLoader the host class loader
 * @param policy the launch startup policy, {@code null} for a hook that never consults it
 * @param jvmFeature {@code Runtime.version().feature()} of the running JVM
 * @param loadedClasses the provider of the loaded-class snapshot shared by this install phase
 */
record InstallContext(
        Instrumentation instrumentation,
        Path artifact,
        ClassLoader hostLoader,
        RuntimeStartupConfig policy,
        int jvmFeature,
        LoadedClassSnapshot loadedClasses) {

    InstallContext {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(hostLoader, "hostLoader");
        Objects.requireNonNull(loadedClasses, "loadedClasses");
    }

    /**
     * Builds a context that samples the loaded classes on its own, for a caller outside an install
     * phase pass such as a focused test.
     *
     * @param instrumentation the instrumentation the hook registers through
     * @param artifact the located host artifact
     * @param hostLoader the host class loader
     * @param policy the launch startup policy, {@code null} for a hook that never consults it
     * @param jvmFeature {@code Runtime.version().feature()} of the running JVM
     */
    InstallContext(
            final Instrumentation instrumentation,
            final Path artifact,
            final ClassLoader hostLoader,
            final RuntimeStartupConfig policy,
            final int jvmFeature) {
        this(instrumentation, artifact, hostLoader, policy, jvmFeature, new LoadedClassSnapshot(instrumentation));
    }

    /**
     * @param environment the environment of a hook that has already established its host
     * @return the context carrying the launch startup policy snapshot and this phase's
     *     loaded-class snapshot
     * @throws java.util.NoSuchElementException when no host is located yet
     */
    static InstallContext of(final HookEnvironment environment) {
        final var host = environment.host().orElseThrow();
        return new InstallContext(
                environment.instrumentation(),
                host.artifact(),
                host.classLoader(),
                environment.plan().policy(),
                Runtime.version().feature(),
                environment.loadedClassSnapshot());
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

    /** @return the loaded-class snapshot of this install phase, sampled on first use */
    LoadedClassIndex loadedClassIndex() {
        return loadedClasses.current();
    }

    /**
     * Drops the loaded-class snapshot of this install phase only when it cannot answer for every
     * class in {@code types}. A hook that defined nothing new keeps the sample the phase already
     * paid for.
     *
     * @param types the classes the calling hook defined or loaded
     */
    void invalidateLoadedClassesUnlessContains(final Iterable<Class<?>> types) {
        loadedClasses.invalidateUnlessContains(types);
    }
}
