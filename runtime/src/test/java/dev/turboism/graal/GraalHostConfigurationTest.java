package dev.turboism.graal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GraalHostConfigurationTest {

    @Test
    void explicitJavaMustMatchThePinnedGraalRelease() throws Exception {
        final Path home = java.nio.file.Files.createTempDirectory("turboism-graal-explicit-");
        final Path ordinaryJava = home.resolve("ordinary/bin/java.exe");
        java.nio.file.Files.createDirectories(ordinaryJava.getParent());
        java.nio.file.Files.write(ordinaryJava, new byte[] {1});
        java.nio.file.Files.writeString(home.resolve("ordinary/release"), """
            IMPLEMENTOR="Other VM"
            JAVA_VERSION="25.0.4"
            """);
        final String originalJava = System.getProperty("turboism.graal.java");
        try {
            System.setProperty("turboism.graal.java", ordinaryJava.toString());

            final GraalHostConfiguration configuration = GraalHostConfiguration.resolve(home);

            assertTrue(!configuration.enabled());
        } finally {
            restore("turboism.graal.java", originalJava);
            try (var paths = java.nio.file.Files.walk(home)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void explicitClasspathWithWindowsWildcardDoesNotResolveTheWildcardAsAPath() throws Exception {
        final Path home = java.nio.file.Files.createTempDirectory("turboism-graal-classpath-");
        final String executableName = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        final Path javaExecutable = home.resolve("graal/bin").resolve(executableName);
        java.nio.file.Files.createDirectories(javaExecutable.getParent());
        java.nio.file.Files.write(javaExecutable, new byte[] {1});
        java.nio.file.Files.writeString(home.resolve("graal/release"), """
            IMPLEMENTOR="GraalVM Community"
            GRAALVM_VERSION="25.2.4"
            JAVA_VERSION="25.0.4"
            """);
        final String originalJava = System.getProperty("turboism.graal.java");
        final String originalClasspath = System.getProperty("turboism.graal.classpath");
        try {
            System.setProperty("turboism.graal.java", javaExecutable.toString());
            System.setProperty("turboism.graal.classpath", "C:\\Turboism\\graal\\lib\\*");

            final GraalHostConfiguration configuration = GraalHostConfiguration.resolve(home);

            assertEquals("C:\\Turboism\\graal\\lib\\*", configuration.classpath());
        } finally {
            restore("turboism.graal.java", originalJava);
            restore("turboism.graal.classpath", originalClasspath);
            try (var paths = java.nio.file.Files.walk(home)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void managedRuntimeBehindALinkedAncestorIsRejected() throws Exception {
        final Path home = java.nio.file.Files.createTempDirectory("turboism-graal-linked-");
        final Path outside = java.nio.file.Files.createTempDirectory("turboism-graal-outside-");
        final String executableName = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        final Path executable = outside.resolve("runtime/bin").resolve(executableName);
        java.nio.file.Files.createDirectories(executable.getParent());
        java.nio.file.Files.write(executable, new byte[] {1});
        java.nio.file.Files.writeString(outside.resolve("runtime/release"), """
            IMPLEMENTOR="GraalVM Community"
            GRAALVM_VERSION="25.2.4"
            JAVA_VERSION="25.0.4"
            """);
        try {
            java.nio.file.Files.createSymbolicLink(home.resolve("graal"), outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable: " + unavailable);
        }
        try {
            final GraalHostConfiguration configuration = GraalHostConfiguration.resolve(home);

            assertTrue(!configuration.enabled());
        } finally {
            java.nio.file.Files.deleteIfExists(home.resolve("graal"));
            java.nio.file.Files.deleteIfExists(home);
            try (var paths = java.nio.file.Files.walk(outside)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void incompatibleManagedRuntimeDoesNotShadowAUsableLegacyRuntime() throws Exception {
        final Path home = java.nio.file.Files.createTempDirectory("turboism-graal-shadow-");
        final String executableName = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        final Path managed = home.resolve("graal/runtime/bin").resolve(executableName);
        java.nio.file.Files.createDirectories(managed.getParent());
        java.nio.file.Files.write(managed, new byte[] {1});
        java.nio.file.Files.writeString(home.resolve("graal/runtime/release"), """
            IMPLEMENTOR="GraalVM Community"
            GRAALVM_VERSION="25.2.3"
            JAVA_VERSION="25.0.3"
            """);
        final Path legacy = home.resolve("graalvm/bin").resolve(executableName);
        java.nio.file.Files.createDirectories(legacy.getParent());
        java.nio.file.Files.write(legacy, new byte[] {2});
        java.nio.file.Files.writeString(home.resolve("graalvm/release"), """
            IMPLEMENTOR="GraalVM Community"
            GRAALVM_VERSION="25.2.4"
            JAVA_VERSION="25.0.4"
            """);
        try {
            final GraalHostConfiguration configuration = GraalHostConfiguration.resolve(home);

            assertTrue(configuration.enabled());
            assertEquals(legacy.toAbsolutePath().normalize().toString(), configuration.javaBinary());
        } finally {
            try (var paths = java.nio.file.Files.walk(home)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void managedRuntimeIsUsedWithoutEnvironmentConfiguration() throws Exception {
        final Path home = java.nio.file.Files.createTempDirectory("turboism-graal-managed-");
        final Path executable = home.resolve("graal/runtime/bin/")
            .resolve(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java");
        java.nio.file.Files.createDirectories(executable.getParent());
        java.nio.file.Files.write(executable, new byte[] {1});
        java.nio.file.Files.writeString(home.resolve("graal/runtime/release"), """
            IMPLEMENTOR="GraalVM Community"
            GRAALVM_VERSION="25.2.4"
            JAVA_VERSION="25.0.4"
            """);
        try {
            final GraalHostConfiguration configuration = GraalHostConfiguration.resolve(home);

            assertTrue(configuration.enabled());
            assertEquals(executable.toAbsolutePath().normalize().toString(), configuration.javaBinary());
        } finally {
            try (var paths = java.nio.file.Files.walk(home)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void restore(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
