package dev.turboism.bootstrap;

import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

final class HostClassLocator {

    private static final long POLL_INTERVAL_MILLIS = 200L;

    Optional<LocatedHost> await(
        final Instrumentation instrumentation,
        final String hostClassName,
        final Duration timeout
    ) throws InterruptedException {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(hostClassName, "hostClassName");
        Objects.requireNonNull(timeout, "timeout");
        final long deadline = System.nanoTime() + timeout.toNanos();
        do {
            final Optional<LocatedHost> located = findLoaded(instrumentation, hostClassName);
            if (located.isPresent()) {
                return located;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } while (System.nanoTime() < deadline);
        return Optional.empty();
    }

    Optional<LocatedHost> findLoaded(
        final Instrumentation instrumentation,
        final String hostClassName
    ) {
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (!loadedClass.getName().equals(hostClassName)) {
                continue;
            }
            final ClassLoader classLoader = loadedClass.getClassLoader();
            if (classLoader == null) {
                throw new IllegalStateException("Cubism host class was loaded by the bootstrap classloader");
            }
            return Optional.of(new LocatedHost(
                loadedClass,
                classLoader,
                artifactPath(loadedClass)
            ));
        }
        return Optional.empty();
    }

    private static Path artifactPath(final Class<?> hostClass) {
        if (hostClass.getProtectionDomain() == null
            || hostClass.getProtectionDomain().getCodeSource() == null
            || hostClass.getProtectionDomain().getCodeSource().getLocation() == null) {
            throw new IllegalStateException("Cubism host class has no code-source artifact");
        }
        try {
            final URI location = hostClass.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Path.of(location).toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IllegalStateException("Cubism host artifact path is invalid", exception);
        }
    }

    record LocatedHost(Class<?> hostClass, ClassLoader classLoader, Path artifact) {
        LocatedHost {
            hostClass = Objects.requireNonNull(hostClass, "hostClass");
            classLoader = Objects.requireNonNull(classLoader, "classLoader");
            artifact = Objects.requireNonNull(artifact, "artifact");
        }
    }
}
