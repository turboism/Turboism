package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTargetType;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpTexturePersistenceProbeTest {
    @TempDir
    Path temporary;

    private static final String RUN = "queue-1234567890abcdef";

    @Test
    void freeFixturePlanKeepsTwoParametersPerTargetAndPreservesOtherFamilies() {
        final var first = new ParameterBindingTarget(ParameterBindingTargetType.ART_MESH, "mesh-a");
        final var second = new ParameterBindingTarget(ParameterBindingTargetType.ART_MESH, "mesh-b");
        final var a = new ParameterBinding(first, new ParameterId("a"), ParameterBindingFamily.KEYFORM_GRID, List.of());
        final var b = new ParameterBinding(first, new ParameterId("b"), ParameterBindingFamily.KEYFORM_GRID, List.of());
        final var c = new ParameterBinding(first, new ParameterId("c"), ParameterBindingFamily.KEYFORM_GRID, List.of());
        final var other =
                new ParameterBinding(second, new ParameterId("c"), ParameterBindingFamily.KEYFORM_GRID, List.of());
        final var morph =
                new ParameterBinding(first, new ParameterId("m"), ParameterBindingFamily.BLEND_SHAPE, List.of());
        final List<ParameterBinding> original = List.of(a, b, c, other, morph);
        assertEquals(List.of(b, c), McpTexturePersistenceProbe.freeBindingPrunePlan(original));
        assertEquals(2, McpTexturePersistenceProbe.maximumBoundParameters(List.of(a, other, morph)));
        assertEquals(List.of(b, c), McpTexturePersistenceProbe.freeBindingPrunePlan(List.of(morph, a, b, c, other)));
        final var morphB =
                new ParameterBinding(first, new ParameterId("n"), ParameterBindingFamily.BLEND_SHAPE, List.of());
        final var morphC =
                new ParameterBinding(first, new ParameterId("o"), ParameterBindingFamily.BLEND_SHAPE, List.of());
        assertThrows(
                IllegalStateException.class,
                () -> McpTexturePersistenceProbe.freeBindingPrunePlan(List.of(a, morph, morphB, morphC)));
        assertTrue(McpTexturePersistenceProbe.freeBindingPrunePlan(List.of(a, b, other))
                .isEmpty());
        assertEquals(4, McpTexturePersistenceProbe.maximumBoundParameters(original));
        assertEquals(3, McpTexturePersistenceProbe.maximumBoundParameters(List.of(a, b, morph)));
        assertEquals(2, McpTexturePersistenceProbe.maximumBoundParameters(List.of(a, b, other)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> McpTexturePersistenceProbe.freeBindingPrunePlan(original).clear());
    }

    @Test
    void outputIsNewAndInsideTheSameTaskHome() throws Exception {
        final Path task = Files.createDirectory(temporary.resolve(RUN));
        final Path home = Files.createDirectory(task.resolve("turboism-home"));
        final Path fixture = Files.writeString(task.resolve(RUN + ".cmo3"), "fixture");
        final Path output = McpTexturePersistenceProbe.requireTaskPaths(home, fixture, RUN);
        assertTrue(output.startsWith(home.toRealPath()));
        assertNotEquals(fixture, output);
        assertFalse(Files.exists(output));
        assertEquals("fixture", Files.readString(fixture));
    }

    @Test
    void foreignFixtureIsRejectedWithoutCreatingOutput() throws Exception {
        final Path task = Files.createDirectory(temporary.resolve(RUN));
        final Path home = Files.createDirectory(task.resolve("turboism-home"));
        final Path fixture = Files.writeString(temporary.resolve(RUN + ".cmo3"), "foreign");
        assertThrows(
                IllegalArgumentException.class, () -> McpTexturePersistenceProbe.requireTaskPaths(home, fixture, RUN));
        assertFalse(Files.exists(home.resolve("state")));
    }

    @Test
    void symlinkedOutputParentIsRejected() throws Exception {
        final Path task = Files.createDirectory(temporary.resolve(RUN));
        final Path home = Files.createDirectory(task.resolve("turboism-home"));
        final Path fixture = Files.writeString(task.resolve(RUN + ".cmo3"), "fixture");
        Files.createSymbolicLink(home.resolve("state"), temporary);
        assertThrows(
                IllegalArgumentException.class, () -> McpTexturePersistenceProbe.requireTaskPaths(home, fixture, RUN));
    }

    @Test
    void unknownAndLooseVersionsAreRejected() {
        for (String version : List.of(
                "5000", "5001", "5002", "5003", "5004", "5005", "5006", "5100", "5101", "5102", "5103", "5200", "5201",
                "5202", "5203", "5300", "5301", "5302", "5303", "5304")) {
            assertTrue(McpTexturePersistenceProbe.expectedJarHash(version).matches("[a-f0-9]{64}"));
        }
        for (String version : List.of("5.3", "5305", "5400", "")) {
            assertThrows(IllegalArgumentException.class, () -> McpTexturePersistenceProbe.expectedJarHash(version));
        }
    }

    @Test
    void requestMustBeUniqueAndBoundToTheCurrentRun() {
        assertTrue(McpTexturePersistenceProbe.acceptsRequest(List.of("runId=" + RUN, "status=REQUESTED"), RUN));
        assertFalse(McpTexturePersistenceProbe.acceptsRequest(List.of("runId=foreign", "status=REQUESTED"), RUN));
        assertFalse(McpTexturePersistenceProbe.acceptsRequest(List.of("runId=" + RUN, "status=PASS"), RUN));
        assertFalse(McpTexturePersistenceProbe.acceptsRequest(
                List.of("runId=" + RUN, "runId=" + RUN, "status=REQUESTED"), RUN));
    }

    @Test
    void pixelFingerprintDetectsOnePixelAndDimensions() throws Exception {
        final BufferedImage first = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        final BufferedImage second = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        assertEquals(McpTexturePersistenceProbe.pixelDigest(first), McpTexturePersistenceProbe.pixelDigest(second));
        second.setRGB(1, 1, 0xff123456);
        assertNotEquals(McpTexturePersistenceProbe.pixelDigest(first), McpTexturePersistenceProbe.pixelDigest(second));
        assertNotEquals(
                McpTexturePersistenceProbe.pixelDigest(first),
                McpTexturePersistenceProbe.pixelDigest(new BufferedImage(1, 4, BufferedImage.TYPE_INT_ARGB)));
    }
}
