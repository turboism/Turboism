package dev.turboism.graal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GraalHostConfigurationTest {

    @Test
    void explicitClasspathWithWindowsWildcardDoesNotResolveTheWildcardAsAPath() {
        final String originalJava = System.getProperty("turboism.graal.java");
        final String originalClasspath = System.getProperty("turboism.graal.classpath");
        try {
            System.setProperty("turboism.graal.java", "C:\\GraalVM\\bin\\java.exe");
            System.setProperty("turboism.graal.classpath", "C:\\Turboism\\graal\\lib\\*");

            final GraalHostConfiguration configuration = GraalHostConfiguration.resolve(
                Path.of(System.getProperty("java.io.tmpdir"), "turboism-graal-config")
            );

            assertEquals("C:\\Turboism\\graal\\lib\\*", configuration.classpath());
        } finally {
            restore("turboism.graal.java", originalJava);
            restore("turboism.graal.classpath", originalClasspath);
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
