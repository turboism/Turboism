package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpTexturePersistenceProbeTest {
    @TempDir Path temporary;
    private static final String RUN = "queue-1234567890abcdef";

    @Test void outputIsNewAndInsideTheSameTaskHome() throws Exception {
        final Path task = Files.createDirectory(temporary.resolve(RUN));
        final Path home = Files.createDirectory(task.resolve("turboism-home"));
        final Path fixture = Files.writeString(task.resolve(RUN + ".cmo3"), "fixture");
        final Path output = McpTexturePersistenceProbe.requireTaskPaths(home, fixture, RUN);
        assertTrue(output.startsWith(home.toRealPath()));
        assertNotEquals(fixture, output);
        assertFalse(Files.exists(output));
        assertEquals("fixture", Files.readString(fixture));
    }

    @Test void foreignFixtureIsRejectedWithoutCreatingOutput() throws Exception {
        final Path task = Files.createDirectory(temporary.resolve(RUN));
        final Path home = Files.createDirectory(task.resolve("turboism-home"));
        final Path fixture = Files.writeString(temporary.resolve(RUN + ".cmo3"), "foreign");
        assertThrows(IllegalArgumentException.class,
            () -> McpTexturePersistenceProbe.requireTaskPaths(home, fixture, RUN));
        assertFalse(Files.exists(home.resolve("state")));
    }

    @Test void symlinkedOutputParentIsRejected() throws Exception {
        final Path task = Files.createDirectory(temporary.resolve(RUN));
        final Path home = Files.createDirectory(task.resolve("turboism-home"));
        final Path fixture = Files.writeString(task.resolve(RUN + ".cmo3"), "fixture");
        Files.createSymbolicLink(home.resolve("state"), temporary);
        assertThrows(IllegalArgumentException.class,
            () -> McpTexturePersistenceProbe.requireTaskPaths(home, fixture, RUN));
    }

    @Test void unknownAndLooseVersionsAreRejected() {
        for (String version : List.of("5203", "5302", "5303")) {
            assertTrue(McpTexturePersistenceProbe.expectedJarHash(version).matches("[a-f0-9]{64}"));
        }
        for (String version : List.of("5.3", "5304", "5400", "")) {
            assertThrows(IllegalArgumentException.class,
                () -> McpTexturePersistenceProbe.expectedJarHash(version));
        }
    }

    @Test void requestMustBeUniqueAndBoundToTheCurrentRun() {
        assertTrue(McpTexturePersistenceProbe.acceptsRequest(List.of("runId=" + RUN, "status=REQUESTED"), RUN));
        assertFalse(McpTexturePersistenceProbe.acceptsRequest(List.of("runId=foreign", "status=REQUESTED"), RUN));
        assertFalse(McpTexturePersistenceProbe.acceptsRequest(List.of("runId=" + RUN, "status=PASS"), RUN));
        assertFalse(McpTexturePersistenceProbe.acceptsRequest(List.of("runId=" + RUN, "runId=" + RUN,
            "status=REQUESTED"), RUN));
    }

    @Test void pixelFingerprintDetectsOnePixelAndDimensions() throws Exception {
        final BufferedImage first = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        final BufferedImage second = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        assertEquals(McpTexturePersistenceProbe.pixelDigest(first), McpTexturePersistenceProbe.pixelDigest(second));
        second.setRGB(1, 1, 0xff123456);
        assertNotEquals(McpTexturePersistenceProbe.pixelDigest(first), McpTexturePersistenceProbe.pixelDigest(second));
        assertNotEquals(McpTexturePersistenceProbe.pixelDigest(first), McpTexturePersistenceProbe.pixelDigest(
            new BufferedImage(1, 4, BufferedImage.TYPE_INT_ARGB)));
    }
}
