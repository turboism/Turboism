package dev.turboism.bootstrap;

import dev.turboism.core.io.Sha256;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.jar.JarFile;

/** Shared admission, capture and restoration primitives for the verified hook installers. */
final class VerifiedHookInstallerSupport {

    private VerifiedHookInstallerSupport() {}

    /**
     * Attests that a loaded class is defined by the expected loader and code source.
     *
     * @throws IllegalArgumentException with {@code failureMessage} when either check fails
     */
    static void attestClassSource(
            final Class<?> type,
            final ClassLoader expectedLoader,
            final Path expectedArtifact,
            final String failureMessage)
            throws Exception {
        if (type.getClassLoader() != expectedLoader
                || type.getProtectionDomain() == null
                || type.getProtectionDomain().getCodeSource() == null
                || !Path.of(type.getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI())
                        .toAbsolutePath()
                        .normalize()
                        .equals(expectedArtifact.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(failureMessage);
        }
    }

    /** Attests that a class belongs to the running JDK rather than the host or a plugin loader. */
    static void attestJdkClass(final Class<?> type, final String failureMessage) {
        if ((type.getClassLoader() != null && type.getClassLoader() != ClassLoader.getPlatformClassLoader())
                || (type.getProtectionDomain() != null
                        && type.getProtectionDomain().getCodeSource() != null)) {
            throw new IllegalArgumentException(failureMessage);
        }
    }

    /** Reads the official class bytes for {@code type} from its reviewed artifact. */
    static byte[] readReferenceBytes(final JarFile jar, final Class<?> type) throws Exception {
        try (var input = jar.getInputStream(jar.getJarEntry(type.getName().replace('.', '/') + ".class"))) {
            return input.readAllBytes();
        }
    }

    /**
     * Reads the official class bytes and fails closed with {@code missingMessage} when the artifact
     * does not contain the reviewed class entry.
     */
    static byte[] readReferenceBytes(final JarFile jar, final Class<?> type, final String missingMessage)
            throws Exception {
        final var entry = jar.getJarEntry(type.getName().replace('.', '/') + ".class");
        if (entry == null) {
            throw new IllegalArgumentException(missingMessage);
        }
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    /**
     * Captures the current bytes of a loaded class through a transient retransformation observer.
     *
     * @param unmodifiableMessage failure message when the class cannot be retransformed, or
     *     {@code null} to let the retransformation itself report that condition
     * @param absentMessage failure message when the observer did not receive the class
     */
    static byte[] captureLoadedBytes(
            final Instrumentation instrumentation,
            final Class<?> type,
            final String unmodifiableMessage,
            final String absentMessage)
            throws Exception {
        return captureLoadedBytes(instrumentation, type, unmodifiableMessage, absentMessage, null);
    }

    /**
     * Captures the current bytes of a loaded class and optionally verifies that the transient
     * observer was removed.
     */
    static byte[] captureLoadedBytes(
            final Instrumentation instrumentation,
            final Class<?> type,
            final String unmodifiableMessage,
            final String absentMessage,
            final String removalFailureMessage)
            throws Exception {
        if (unmodifiableMessage != null && !instrumentation.isModifiableClass(type)) {
            throw new IllegalStateException(unmodifiableMessage);
        }
        final AtomicReference<byte[]> result = new AtomicReference<>();
        final ClassFileTransformer observer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    final Module module,
                    final ClassLoader loader,
                    final String name,
                    final Class<?> redefined,
                    final ProtectionDomain domain,
                    final byte[] bytes) {
                if (redefined == type && bytes != null) result.set(bytes.clone());
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try {
            instrumentation.retransformClasses(type);
        } finally {
            if (!instrumentation.removeTransformer(observer) && removalFailureMessage != null) {
                throw new IllegalStateException(removalFailureMessage);
            }
        }
        if (result.get() == null) {
            throw new IllegalStateException(absentMessage);
        }
        return result.get();
    }

    /** Returns the lowercase SHA-256 digest of {@code bytes} as hexadecimal text. */
    static String sha256Hex(final byte[] bytes) {
        return Sha256.hex(bytes);
    }

    /**
     * Computes the lowercase SHA-256 hex digest of {@code bytes} inside a {@link ClassFileTransformer}
     * callback where checked exceptions cannot be thrown.
     */
    static String sha256HexUnchecked(final byte[] bytes) {
        return Sha256.hex(bytes);
    }

    /**
     * Runs {@code close} after an installation failure and merges any secondary cleanup failure
     * onto {@code failure} with fatal JVM conditions prioritized.
     */
    static void closeOnInstallFailure(final Throwable failure, final AutoCloseable close) {
        try {
            close.close();
        } catch (Exception | Error cleanup) {
            // @containment-exempt: cleanup failure merged, prioritizing fatal error
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(
                    dev.turboism.core.runtime.work.FatalErrors.mergeForCleanup(failure, cleanup));
        }
    }

    /**
     * Retransforms every already-loaded class with the exact binary name and host loader.
     *
     * @return the number of retransformed classes
     */
    static int retransformIfLoaded(
            final Instrumentation instrumentation, final String targetClassName, final ClassLoader hostClassLoader)
            throws Exception {
        return retransformIfLoaded(instrumentation, Set.of(targetClassName), hostClassLoader);
    }

    /**
     * Retransforms every already-loaded class whose binary name is in {@code targetClassNames} and
     * whose defining loader is {@code hostClassLoader}.
     *
     * @return the number of retransformed classes
     */
    static int retransformIfLoaded(
            final Instrumentation instrumentation,
            final Collection<String> targetClassNames,
            final ClassLoader hostClassLoader)
            throws Exception {
        final List<Class<?>> targets = new ArrayList<>();
        for (final Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded != null
                    && loaded.getClassLoader() == hostClassLoader
                    && targetClassNames.contains(loaded.getName())
                    && instrumentation.isModifiableClass(loaded)) {
                targets.add(loaded);
            }
        }
        if (!targets.isEmpty()) {
            // One request lets the JVM reach a single safepoint for the whole target set.
            instrumentation.retransformClasses(targets.toArray(Class<?>[]::new));
        }
        return targets.size();
    }

    /**
     * Retransforms {@code targets} in one request, so installation reaches one safepoint.
     *
     * @return the number of retransformed classes
     */
    static int retransformAll(final Instrumentation instrumentation, final Collection<Class<?>> targets)
            throws Exception {
        if (targets.isEmpty()) {
            return 0;
        }
        instrumentation.retransformClasses(targets.toArray(Class<?>[]::new));
        return targets.size();
    }

    /**
     * One enumeration of the loaded classes, so an installation pass scans the JVM once.
     *
     * <p>A snapshot is only valid while no installer between the scan and the use can load the
     * classes it looks for. Installation registers its transformers first and then retransforms,
     * so the snapshot taken after registration sees every owner the pass must reach.</p>
     */
    static final class LoadedClassIndex {

        private final Class<?>[] classes;

        private LoadedClassIndex(final Class<?>[] classes) {
            this.classes = classes;
        }

        /** Captures the currently loaded classes. */
        static LoadedClassIndex snapshot(final Instrumentation instrumentation) {
            return new LoadedClassIndex(instrumentation.getAllLoadedClasses());
        }

        /**
         * Returns the loaded classes that {@code filter} accepts under {@code loader}.
         *
         * @param loader the defining loader every accepted class must have
         * @param filter additional acceptance test, such as an owner-name check
         * @return the accepted classes in enumeration order
         */
        List<Class<?>> select(final ClassLoader loader, final Predicate<Class<?>> filter) {
            final List<Class<?>> selected = new ArrayList<>();
            for (final Class<?> loaded : classes) {
                if (loaded != null && loaded.getClassLoader() == loader && filter.test(loaded)) {
                    selected.add(loaded);
                }
            }
            return selected;
        }

        /**
         * Reports whether any loaded class has the exact binary name, whatever its loader.
         *
         * @param binaryName the binary class name to look for
         * @return {@code true} when that name is already defined
         */
        boolean containsName(final String binaryName) {
            for (final Class<?> loaded : classes) {
                if (loaded != null && loaded.getName().equals(binaryName)) {
                    return true;
                }
            }
            return false;
        }
    }
}
