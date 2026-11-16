package dev.turboism.adapter.cubism.optimization;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Builds the isolated class loader that exact-artifact tests use to load a reviewed Cubism Editor
 * artifact, and locates the reviewed JOGL dependency.
 *
 * <p>A reviewed {@code Live2D_Cubism.jar} links against two dependency families that the artifact
 * archive does not carry inside a version directory:</p>
 *
 * <ul>
 *   <li>the Kotlin runtime, reached through member shapes such as
 *       {@code kotlin.jvm.internal.markers.KMutableList};</li>
 *   <li>JOGL, reached through render-backend signatures such as {@code com.jogamp.opengl.GL2ES2}.</li>
 * </ul>
 *
 * <p>Loading an Editor class without them fails with {@link NoClassDefFoundError} while the JVM
 * resolves that class's field and method shapes, before any assertion runs. The loader therefore
 * appends the Kotlin runtime from the test JVM's own resolved classpath, which supplies compatible
 * linkage for these offline contract tests, and the JOGL jar the archive pins.</p>
 *
 * <p>The appended Kotlin runtime is the test suite's {@code testImplementation} dependency, not the
 * runtime bundled with any shipped Editor. These tests check reviewed member shapes and bytecode
 * weaves, not bundled-runtime fidelity, so a compatible runtime is sufficient.</p>
 *
 * <p>The loader keeps the platform loader as parent and binds host packages child-first. A reviewed
 * artifact must bind its own {@code com.live2d.*} and {@code jp.noids.*} classes, never the offline
 * fixture replicas that the test sources also compile.</p>
 */
public final class HostArtifactTestLoader extends URLClassLoader {

    /** Host packages that must resolve from the reviewed artifact instead of the test classpath. */
    private static final List<String> CHILD_FIRST_PREFIXES = List.of("com.live2d.", "jp.noids.");

    /** Marker class whose code source is the Kotlin runtime a reviewed artifact links against. */
    private static final String KOTLIN_MARKER = "kotlin.jvm.internal.markers.KMutableList";

    /** Reviewed JOGL jar name, both at a version directory and in its {@code jogl/} subdirectory. */
    private static final List<String> JOGL_CANDIDATES = List.of("jogl/jogl-all.jar", "jogl-all.jar");

    /** Environment override for the reviewed JOGL jar. */
    public static final String JOGL_ENVIRONMENT_VARIABLE = "TURBOISM_CUBISM_JOGL";

    private static final String JAR_SUFFIX = ".jar";

    /** Directory walk depth that covers a version directory and its {@code jogl/} subdirectory. */
    private static final int JAR_WALK_DEPTH = 2;

    private HostArtifactTestLoader(final URL[] urls, final ClassLoader parent) {
        super(urls, parent);
    }

    /**
     * Opens a loader over every jar below {@code artifactDirectory}, the Kotlin runtime, and one
     * additional dependency jar.
     *
     * @param artifactDirectory the reviewed version directory, for example {@code 5.3.04}
     * @param extraJar an additional dependency jar, or {@code null} for none
     * @return a loader that binds host classes child-first
     * @throws IOException when the directory cannot be walked
     */
    public static HostArtifactTestLoader open(final Path artifactDirectory, final Path extraJar) throws IOException {
        final List<URL> urls = jarsUnder(artifactDirectory);
        if (extraJar != null) {
            urls.add(extraJar.toUri().toURL());
        }
        kotlinRuntime().ifPresent(urls::add);
        return new HostArtifactTestLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    /**
     * Lists every jar below {@code directory} in a stable order.
     *
     * @param directory the directory to walk
     * @return the jar URLs in path order
     * @throws IOException when the directory cannot be walked
     */
    public static List<URL> jarsUnder(final Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        final List<URL> urls = new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory, JAR_WALK_DEPTH)) {
            for (final Path path : files.filter(path -> path.toString().endsWith(JAR_SUFFIX))
                    .sorted()
                    .toList()) {
                urls.add(path.toUri().toURL());
            }
        }
        return urls;
    }

    /**
     * Returns the Kotlin runtime the test JVM resolved, so a reviewed artifact can link against it.
     *
     * <p>This is the suite's {@code testImplementation} runtime, not the runtime bundled with a
     * shipped Editor. It supplies compatible linkage for offline contract tests.</p>
     *
     * @return the runtime jar, or empty when this JVM has no Kotlin runtime
     */
    private static Optional<URL> kotlinRuntime() {
        try {
            final Class<?> marker = Class.forName(KOTLIN_MARKER, false, HostArtifactTestLoader.class.getClassLoader());
            final var source = marker.getProtectionDomain().getCodeSource();
            return source == null ? Optional.empty() : Optional.of(source.getLocation());
        } catch (final ClassNotFoundException absent) {
            return Optional.empty();
        }
    }

    /**
     * Locates the reviewed JOGL jar that the render backends link against.
     *
     * <p>The jar is version-independent: one reviewed digest covers every Editor version, and the
     * archive stores it under the single version whose installer shipped it. The environment
     * override wins over the archive search.</p>
     *
     * @param root the reviewed artifact archive root
     * @return the JOGL jar, or empty when neither the override nor the archive supplies one
     */
    public static Optional<Path> reviewedJogl(final Path root) {
        final String override = System.getenv(JOGL_ENVIRONMENT_VARIABLE);
        if (override != null && !override.isBlank()) {
            return Optional.of(Path.of(override));
        }
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            for (final String candidate : JOGL_CANDIDATES) {
                final Path jogl = root.resolve(version).resolve(candidate);
                if (Files.isRegularFile(jogl)) {
                    return Optional.of(jogl);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        if (!isChildFirst(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = findClass(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static boolean isChildFirst(final String name) {
        for (final String prefix : CHILD_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
