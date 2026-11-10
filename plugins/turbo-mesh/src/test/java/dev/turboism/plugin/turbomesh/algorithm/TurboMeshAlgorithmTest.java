package dev.turboism.plugin.turbomesh.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshComputeException;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.plugin.TaskCanceledException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Contract of the two published auto-mesh algorithms: schema, presets, and the region compute on a
 * real texture sample.
 *
 * <p>The assertions describe what the runtime relies on — a declaration the SPI accepts, preset
 * values inside the declared schema, and geometry that holds no degenerate triangle — so a change
 * to the planner or the schema cannot silently publish an unusable algorithm.</p>
 */
class TurboMeshAlgorithmTest {

    /** Reviewed hair sample the texture-region prototype was verified against. */
    private static final String SAMPLE = "/texture-regions/sample.png";

    /** Triangle area in position units below which the triangle counts as degenerate. */
    private static final double DEGENERATE_AREA = 1.0e-9;

    /** Tolerance for a uv that must stay inside the sampled texture. */
    private static final double UV_TOLERANCE = 1.0e-4;

    private static final String TARGET_ID = "mesh-1";

    private static final int SINGLE_KEYFORM = 1;

    private static final PluginLocalization LOCALIZATION = new KeyLocalization();

    @Test
    void regionDeclarationIsAcceptedWithTheDocumentedSchema() {
        final AutoMeshAlgorithm algorithm = RegionAlgorithm.create(LOCALIZATION);

        assertEquals(RegionAlgorithm.ID, algorithm.id());
        assertEquals(
                Set.of(
                        "minRegionRatio", "autoMinRegionRatio",
                        "contrast", "autoContrast",
                        "level", "trackCount",
                        "maxSpacing", "autoMaxSpacing",
                        "simplifyTolerance", "autoSimplifyTolerance",
                        "minSpacing", "autoMinSpacing"),
                parameterIds(algorithm));
        assertPresetValuesAreCanonical(algorithm);
        assertEquals(
                List.of(
                        "turbo-mesh.algorithm.region.preset.sparse",
                        "turbo-mesh.algorithm.region.preset.detailed",
                        "turbo-mesh.algorithm.region.preset.hard-surface"),
                algorithm.presets().stream().map(AlgorithmPreset::name).toList());
    }

    @Test
    void regionPresetsProjectTheReviewedPlacementParams() {
        final AutoMeshAlgorithm algorithm = RegionAlgorithm.create(LOCALIZATION);
        final Map<String, AlgorithmPreset> presets = new HashMap<>();
        algorithm.presets().forEach(preset -> presets.put(preset.name(), preset));

        final AlgorithmPreset hardSurface = presets.get("turbo-mesh.algorithm.region.preset.hard-surface");
        assertEquals("false", hardSurface.values().get("autoMaxSpacing"));
        assertEquals("32", hardSurface.values().get("maxSpacing"));
        assertEquals("3", hardSurface.values().get("trackCount"));

        final AlgorithmPreset sparse = presets.get("turbo-mesh.algorithm.region.preset.sparse");
        assertEquals("true", sparse.values().get("autoMaxSpacing"));
        assertFalse(sparse.values().containsKey("maxSpacing"), "an automatic value carries no number");
    }

    @Test
    void adaptiveDeclarationIsAcceptedWithTheDocumentedSchema() {
        final AutoMeshAlgorithm algorithm = AdaptiveAlgorithm.create(LOCALIZATION);

        assertEquals(AdaptiveAlgorithm.ID, algorithm.id());
        assertEquals(
                Set.of("density", "maxBoundarySpacing", "interiorSpacing", "minSpacing", "maxPoints"),
                parameterIds(algorithm));
        assertPresetValuesAreCanonical(algorithm);
        assertEquals(4, algorithm.presets().size(), "one preset per density");
    }

    @Test
    void regionComputeBuildsValidGeometryFromTheSampleTexture() throws IOException, AutoMeshComputeException {
        final TexturePixels texture = sampleTexture();

        final AutoMeshResult result = RegionAlgorithm.create(LOCALIZATION)
                .compute()
                .compute(request(Optional.of(texture), Map.of()), activeToken());

        final AutoMeshResult.Success success = assertInstanceOf(AutoMeshResult.Success.class, result);
        final ArtMeshGeometry geometry = success.geometry();
        assertEquals(geometry.positions().size(), geometry.uvs().size());
        assertFalse(geometry.triangleIndices().isEmpty(), "the plan must contain triangles");
        assertEquals(0, geometry.triangleIndices().size() % 3);
        for (final int index : geometry.triangleIndices()) {
            assertTrue(index >= 0 && index < geometry.positions().size(), "triangle index in range");
        }
        for (final Point2 uv : geometry.uvs()) {
            assertTrue(
                    uv.x() >= -UV_TOLERANCE && uv.x() <= 1.0 + UV_TOLERANCE,
                    "uv x " + uv.x() + " must stay inside the texture");
            assertTrue(
                    uv.y() >= -UV_TOLERANCE && uv.y() <= 1.0 + UV_TOLERANCE,
                    "uv y " + uv.y() + " must stay inside the texture");
        }
        assertNoDegenerateTriangle(geometry);
    }

    @Test
    void regionComputeRejectsARequestWithoutTexture() throws AutoMeshComputeException {
        final AutoMeshResult result = RegionAlgorithm.create(LOCALIZATION)
                .compute()
                .compute(request(Optional.empty(), Map.of()), activeToken());

        final AutoMeshResult.Rejected rejected = assertInstanceOf(AutoMeshResult.Rejected.class, result);
        assertEquals(RegionAlgorithm.REASON_NO_TEXTURE, rejected.reasonCode());
        assertFalse(rejected.reason().isBlank(), "the user must see why the run produced nothing");
    }

    @Test
    void regionComputeReportsCancellation() throws IOException, AutoMeshComputeException {
        final TexturePixels texture = sampleTexture();

        final AutoMeshResult result = RegionAlgorithm.create(LOCALIZATION)
                .compute()
                .compute(request(Optional.of(texture), Map.of()), cancelledToken());

        assertEquals(AutoMeshResult.Cancelled.INSTANCE, result);
    }

    @Test
    void adaptiveComputeBuildsValidGeometryFromTheSampleTexture() throws IOException, AutoMeshComputeException {
        final TexturePixels texture = sampleTexture();

        final AutoMeshResult result = AdaptiveAlgorithm.create(LOCALIZATION)
                .compute()
                .compute(request(Optional.of(texture), Map.of()), activeToken());

        final AutoMeshResult.Success success = assertInstanceOf(AutoMeshResult.Success.class, result);
        assertFalse(success.geometry().triangleIndices().isEmpty(), "the plan must contain triangles");
        assertNoDegenerateTriangle(success.geometry());
    }

    private static Set<String> parameterIds(final AutoMeshAlgorithm algorithm) {
        return algorithm.parameters().stream()
                .map(AlgorithmParameterDescriptor::id)
                .collect(Collectors.toSet());
    }

    /** Every published preset value must pass the descriptor that declares it. */
    private static void assertPresetValuesAreCanonical(final AutoMeshAlgorithm algorithm) {
        final Map<String, AlgorithmParameterDescriptor> descriptors = new HashMap<>();
        algorithm.parameters().forEach(parameter -> descriptors.put(parameter.id(), parameter));
        for (final AlgorithmPreset preset : algorithm.presets()) {
            assertFalse(preset.name().isBlank(), "a preset needs a name");
            preset.values().forEach((parameterId, value) -> {
                final AlgorithmParameterDescriptor descriptor = descriptors.get(parameterId);
                assertNotNull(descriptor, "preset " + preset.name() + " names an undeclared parameter " + parameterId);
                assertEquals(
                        value, descriptor.canonicalize(value), "preset " + preset.name() + " value " + parameterId);
            });
        }
    }

    private static void assertNoDegenerateTriangle(final ArtMeshGeometry geometry) {
        final List<Point2> positions = geometry.positions();
        final List<Integer> indices = geometry.triangleIndices();
        for (int triangle = 0; triangle + 2 < indices.size(); triangle += 3) {
            final Point2 a = positions.get(indices.get(triangle));
            final Point2 b = positions.get(indices.get(triangle + 1));
            final Point2 c = positions.get(indices.get(triangle + 2));
            final double area = Math.abs((b.x() - a.x()) * (c.y() - a.y()) - (c.x() - a.x()) * (b.y() - a.y())) * 0.5;
            assertTrue(area > DEGENERATE_AREA, "triangle " + triangle / 3 + " is degenerate: " + area);
        }
    }

    private static AutoMeshRequest request(
            final Optional<TexturePixels> texture, final Map<String, String> parameterValues) {
        return new AutoMeshRequest(
                TARGET_ID,
                squareGeometry(),
                texture,
                SINGLE_KEYFORM,
                parameterValues,
                AutoMeshRequest.UNSPECIFIED_VERTEX_TARGET);
    }

    /** Source mesh of the run: a unit square whose uvs span the whole texture. */
    private static ArtMeshGeometry squareGeometry() {
        final List<Point2> corners =
                List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(1f, 1f), new Point2(0f, 1f));
        return new ArtMeshGeometry(corners, corners, List.of(0, 1, 2, 0, 2, 3));
    }

    private static TexturePixels sampleTexture() throws IOException {
        try (InputStream stream = TurboMeshAlgorithmTest.class.getResourceAsStream(SAMPLE)) {
            assertNotNull(stream, "missing test resource " + SAMPLE);
            final BufferedImage image = ImageIO.read(stream);
            final int width = image.getWidth();
            final int height = image.getHeight();
            final int[] argb = new int[width * height];
            image.getRGB(0, 0, width, height, argb, 0, width);
            return new TexturePixels(width, height, argb, new double[] {width, 0.0, 0.0, 0.0, height, 0.0});
        }
    }

    private static CancellationToken activeToken() {
        return new RecordingToken(false);
    }

    private static CancellationToken cancelledToken() {
        return new RecordingToken(true);
    }

    /** Token whose cancellation state is fixed for the whole run. */
    private static final class RecordingToken implements CancellationToken {

        private final boolean cancelled;

        RecordingToken(final boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public boolean isCancellationRequested() {
            return cancelled;
        }

        @Override
        public void checkCanceled() throws TaskCanceledException {
            if (cancelled) {
                throw new TaskCanceledException("test cancellation");
            }
        }
    }

    /** Localization stub that returns the key, so assertions read the catalog key itself. */
    private static final class KeyLocalization implements PluginLocalization {

        @Override
        public Locale locale() {
            return Locale.ENGLISH;
        }

        @Override
        public String text(final String key) {
            return key;
        }

        @Override
        public String format(final String key, final Object... arguments) {
            return key;
        }

        @Override
        public boolean contains(final String key) {
            return true;
        }
    }
}
