package dev.turboism.graal;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Process-only configuration for the optional Graal execution host. */
public record GraalHostConfiguration(
    boolean enabled,
    String javaBinary,
    String classpath,
    String mainClass,
    long startupTimeoutMillis,
    Source source
) {

    /** Authoritative origin of the Java executable selected for GraalJS. */
    public enum Source {
        DISABLED,
        MANAGED,
        LEGACY_PACKAGED,
        EXTERNAL,
        EXPLICIT
    }

    /** Compatibility constructor retained for focused manager tests. */
    public GraalHostConfiguration(
        final boolean enabled,
        final String javaBinary,
        final String classpath,
        final String mainClass,
        final long startupTimeoutMillis
    ) {
        this(
            enabled,
            javaBinary,
            classpath,
            mainClass,
            startupTimeoutMillis,
            enabled ? Source.EXPLICIT : Source.DISABLED
        );
    }

    public static final String DEFAULT_MAIN_CLASS = "dev.turboism.graalhost.GraalHostMain";

    public GraalHostConfiguration {
        javaBinary = Objects.requireNonNullElse(javaBinary, "").trim();
        classpath = Objects.requireNonNullElse(classpath, "").trim();
        mainClass = Objects.requireNonNullElse(mainClass, DEFAULT_MAIN_CLASS).trim();
        source = Objects.requireNonNull(source, "source");
        if (enabled && source == Source.DISABLED) {
            throw new IllegalArgumentException("Enabled Graal host requires an enabled source");
        }
        if (!enabled && source != Source.DISABLED) {
            throw new IllegalArgumentException("Disabled Graal host requires the disabled source");
        }
        if (enabled && javaBinary.isEmpty()) {
            throw new IllegalArgumentException("Enabled Graal host requires a Java binary");
        }
        if (enabled && classpath.isEmpty()) {
            throw new IllegalArgumentException("Enabled Graal host requires a classpath");
        }
        if (mainClass.isEmpty()) {
            throw new IllegalArgumentException("Graal host main class must not be blank");
        }
        if (startupTimeoutMillis <= 0L || startupTimeoutMillis > 60_000L) {
            throw new IllegalArgumentException("Graal host startup timeout must be in (0, 60000]");
        }
    }

    /**
     * Resolves an opt-in host without changing Cubism's JVM. Explicit system properties win,
     * then the Turboism-managed runtime, legacy packaged runtime, and external
     * TURBOISM_GRAALVM_HOME/GRAALVM_HOME are considered. The default classpath expects
     * packaged host libraries under {@code <turboism.home>/graal/lib/*}.
     */
    public static GraalHostConfiguration resolve(final Path turboismHome) {
        final Path home = Objects.requireNonNull(turboismHome, "turboismHome")
            .toAbsolutePath().normalize();
        final boolean explicitlyDisabled = "false".equalsIgnoreCase(
            System.getProperty("turboism.graal.enabled", "true")
        );
        if (explicitlyDisabled) {
            return disabled();
        }

        final String explicitJava = System.getProperty("turboism.graal.java", "").trim();
        final String externalGraalHome = firstNonBlank(
            System.getenv("TURBOISM_GRAALVM_HOME"),
            System.getenv("GRAALVM_HOME")
        );
        final Selection selection;
        if (!explicitJava.isBlank()) {
            selection = validatedExplicitJava(explicitJava)
                .map(java -> new Selection(java, Source.EXPLICIT))
                .orElse(null);
        } else {
            selection = managedJava(home)
                .map(java -> new Selection(java, Source.MANAGED))
                .or(() -> javaFromHome(home.resolve("graalvm").toString())
                    .map(java -> new Selection(java, Source.LEGACY_PACKAGED)))
                .or(() -> javaFromHome(externalGraalHome)
                    .map(java -> new Selection(java, Source.EXTERNAL)))
                .orElse(null);
        }
        if (selection == null) {
            return disabled();
        }
        final String javaBinary = selection.javaBinary();

        final String explicitClasspath = firstNonBlank(
            System.getProperty("turboism.graal.classpath", ""),
            System.getenv("TURBOISM_GRAAL_CLASSPATH")
        );
        final String classpath = explicitClasspath.isBlank()
            ? home.resolve("graal").resolve("lib").toString() + File.separator + "*"
            : explicitClasspath;
        final String mainClass = System.getProperty(
            "turboism.graal.mainClass", DEFAULT_MAIN_CLASS
        );
        final long timeout = Long.getLong("turboism.graal.startupTimeoutMillis", 10_000L);
        return new GraalHostConfiguration(
            true, javaBinary, classpath, mainClass, timeout, selection.source()
        );
    }

    /** @return a configuration that keeps the external Graal host disabled */
    public static GraalHostConfiguration disabled() {
        return new GraalHostConfiguration(
            false, "", "", DEFAULT_MAIN_CLASS, 10_000L, Source.DISABLED
        );
    }

    private static Optional<String> managedJava(final Path turboismHome) {
        final String executable = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return ManagedGraalRuntimeService.managedJavaExecutableIfReady(
            turboismHome, "bin/" + executable
        ).map(Path::toString);
    }

    private static Optional<String> validatedExplicitJava(final String rawJava) {
        if (rawJava == null || rawJava.isBlank()) return Optional.empty();
        try {
            final Path candidate = Path.of(rawJava).toAbsolutePath().normalize();
            final Path bin = candidate.getParent();
            final Path home = bin == null ? null : bin.getParent();
            return home == null
                ? Optional.empty()
                : javaFromHome(home.toString())
                    .filter(validated -> Path.of(validated).equals(candidate));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<String> javaFromHome(final String rawHome) {
        if (rawHome == null || rawHome.isBlank()) {
            return Optional.empty();
        }
        final Path home = Path.of(rawHome).toAbsolutePath().normalize();
        final String executable = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        final Path candidate = home.resolve("bin").resolve(executable);
        if (Files.isSymbolicLink(candidate)
            || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        final Path release = home.resolve("release");
        if (Files.isSymbolicLink(release)
            || !Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            if (Files.size(release) > 64 * 1024L) return Optional.empty();
            final String metadata = Files.readString(release, StandardCharsets.UTF_8);
            return exactReleaseValue(metadata, "IMPLEMENTOR").orElse("")
                    .equals("GraalVM Community")
                && exactReleaseValue(metadata, "GRAALVM_VERSION").orElse("")
                    .equals(ManagedGraalRuntimeService.GRAAL_VERSION)
                && exactReleaseValue(metadata, "JAVA_VERSION").orElse("")
                    .equals(ManagedGraalRuntimeService.JAVA_VERSION)
                ? Optional.of(candidate.toString())
                : Optional.empty();
        } catch (java.io.IOException | RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<String> exactReleaseValue(
        final String metadata,
        final String key
    ) {
        final String prefix = key + "=";
        return metadata.lines()
            .map(String::trim)
            .filter(line -> line.startsWith(prefix))
            .map(line -> line.substring(prefix.length()).trim())
            .map(value -> value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value)
            .findFirst();
    }

    private record Selection(String javaBinary, Source source) {
        private Selection {
            javaBinary = Objects.requireNonNull(javaBinary, "javaBinary");
            source = Objects.requireNonNull(source, "source");
        }
    }

    private static String firstNonBlank(final String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
