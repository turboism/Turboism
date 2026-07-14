package dev.turboism.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentOptionsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void usesPreviewDefaults() {
        final AgentOptions options = AgentOptions.parse(null, temporaryDirectory);

        assertEquals(temporaryDirectory.toAbsolutePath().normalize(), options.home());
        assertEquals("com.live2d.cubism.CEAppCtrl", options.hostClassName());
        assertEquals(90L, options.detectionTimeout().toSeconds());
    }

    @Test
    void parsesExplicitOptionsIncludingSpaces() {
        final Path home = temporaryDirectory.resolve("preview home");
        final AgentOptions options = AgentOptions.parse(
            "home=" + home + ";hostClass=example.Host;timeoutSeconds=120",
            temporaryDirectory
        );

        assertEquals(home.toAbsolutePath().normalize(), options.home());
        assertEquals("example.Host", options.hostClassName());
        assertEquals(120L, options.detectionTimeout().toSeconds());
    }

    @Test
    void rejectsUnknownAndDuplicateOptions() {
        assertThrows(
            IllegalArgumentException.class,
            () -> AgentOptions.parse("unsafe=true", temporaryDirectory)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> AgentOptions.parse("timeoutSeconds=1;timeoutSeconds=2", temporaryDirectory)
        );
    }
}
