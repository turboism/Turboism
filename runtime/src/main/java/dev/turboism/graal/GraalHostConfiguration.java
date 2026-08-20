package dev.turboism.graal;

import java.io.File;
import java.nio.file.Files;
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
    long startupTimeoutMillis
) {

    public static final String DEFAULT_MAIN_CLASS = "dev.turboism.graalhost.GraalHostMain";

    public GraalHostConfiguration {
        javaBinary = Objects.requireNonNullElse(javaBinary, "").trim();
        classpath = Objects.requireNonNullElse(classpath, "").trim();
        mainClass = Objects.requireNonNullElse(mainClass, DEFAULT_MAIN_CLASS).trim();
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
     * then TURBOISM_GRAALVM_HOME/GRAALVM_HOME are considered. The default classpath expects
     * a packaged {@code <turboism.home>/graal/*} directory.
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
        final String graalHome = firstNonBlank(
            System.getenv("TURBOISM_GRAALVM_HOME"),
            System.getenv("GRAALVM_HOME")
        );
        final String javaBinary = !explicitJava.isBlank()
            ? explicitJava
            : javaFromHome(graalHome).orElse("");
        if (javaBinary.isBlank()) {
            return disabled();
        }

        final String classpath = firstNonBlank(
            System.getProperty("turboism.graal.classpath", ""),
            System.getenv("TURBOISM_GRAAL_CLASSPATH"),
            home.resolve("graal").resolve("*").toString()
        );
        final String mainClass = System.getProperty(
            "turboism.graal.mainClass", DEFAULT_MAIN_CLASS
        );
        final long timeout = Long.getLong("turboism.graal.startupTimeoutMillis", 10_000L);
        return new GraalHostConfiguration(true, javaBinary, classpath, mainClass, timeout);
    }

    public static GraalHostConfiguration disabled() {
        return new GraalHostConfiguration(false, "", "", DEFAULT_MAIN_CLASS, 10_000L);
    }

    private static Optional<String> javaFromHome(final String rawHome) {
        if (rawHome == null || rawHome.isBlank()) {
            return Optional.empty();
        }
        final Path home = Path.of(rawHome).toAbsolutePath().normalize();
        final String executable = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        final Path candidate = home.resolve("bin").resolve(executable);
        return Files.isRegularFile(candidate) ? Optional.of(candidate.toString()) : Optional.empty();
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
