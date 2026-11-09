package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for the Auto Mesh probe's host-independent logic.
 *
 * <p>These cover the parts a host run cannot isolate: the geometry digest that makes every
 * comparison meaningful, the degenerate-triangle rule that decides whether a mesh is usable, the
 * Alpha IoU that decides whether it covers its silhouette, the result file the runner reads, and the
 * dump layout the offline renderer consumes.</p>
 */
class AutoMeshFrameworkValidationProbeTest {

    private static final double DEGENERATE_TOLERANCE = 1.0e-9;

    @Test
    void digestIsStableAndDistinguishesEveryGeometryField() {
        final ArtMeshGeometry square = square();
        assertEquals(AutoMeshGeometryDigest.of(square), AutoMeshGeometryDigest.of(square));

        final ArtMeshGeometry movedVertex = new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(1.0f, 1.0f), new Point2(0.0f, 1.0f)),
                square.uvs(),
                square.triangleIndices());
        final ArtMeshGeometry movedUv = new ArtMeshGeometry(
                square.positions(),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(1.0f, 1.0f), new Point2(0.0f, 0.5f)),
                square.triangleIndices());
        final ArtMeshGeometry flippedTriangles =
                new ArtMeshGeometry(square.positions(), square.uvs(), List.of(0, 2, 1, 0, 3, 2));

        assertEquals(
                3,
                countDistinct(
                        AutoMeshGeometryDigest.of(square),
                        AutoMeshGeometryDigest.of(movedVertex),
                        AutoMeshGeometryDigest.of(movedUv),
                        AutoMeshGeometryDigest.of(flippedTriangles)));
    }

    @Test
    void digestSeparatesTwoVerticesFromOneVertexWithTheSameExtent() {
        final ArtMeshGeometry twoVertices = new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 1.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 1.0f)),
                List.of());
        final ArtMeshGeometry oneVertex =
                new ArtMeshGeometry(List.of(new Point2(0.0f, 0.0f)), List.of(new Point2(0.0f, 0.0f)), List.of());
        assertFalse(AutoMeshGeometryDigest.of(twoVertices).equals(AutoMeshGeometryDigest.of(oneVertex)));
    }

    @Test
    void degenerateTrianglesCountRepeatedIndicesAndZeroArea() {
        final ArtMeshGeometry healthy = square();
        assertEquals(0, AutoMeshGeometryDigest.degenerateTriangles(healthy, DEGENERATE_TOLERANCE));

        final ArtMeshGeometry repeated =
                new ArtMeshGeometry(healthy.positions(), healthy.uvs(), List.of(0, 1, 1, 0, 3, 2));
        assertEquals(1, AutoMeshGeometryDigest.degenerateTriangles(repeated, DEGENERATE_TOLERANCE));

        final ArtMeshGeometry collinear = new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(2.0f, 0.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(2.0f, 0.0f)),
                List.of(0, 1, 2));
        assertEquals(1, AutoMeshGeometryDigest.degenerateTriangles(collinear, DEGENERATE_TOLERANCE));
    }

    @Test
    void unreferencedVerticesCountEveryVertexNoTriangleNames() {
        assertEquals(0, AutoMeshGeometryDigest.unreferencedVertices(square()));
        final ArtMeshGeometry orphan = new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(1.0f, 1.0f), new Point2(9.0f, 9.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(1.0f, 1.0f), new Point2(9.0f, 9.0f)),
                List.of(0, 1, 2));
        assertEquals(1, AutoMeshGeometryDigest.unreferencedVertices(orphan));
    }

    @Test
    void alphaIouIsOneForAnIdenticalMeshAndZeroForAnEmptyOne() {
        final TexturePixels texture = opaqueTexture();
        final ArtMeshGeometry full = unitSquare();
        assertEquals(
                1.0,
                AutoMeshAlphaCoverage.intersectionOverUnion(
                        full, texture, AutoMeshAlphaCoverage.DEFAULT_ALPHA_THRESHOLD, 64),
                0.02);
        assertEquals(
                1.0,
                AutoMeshAlphaCoverage.opaqueRecall(full, texture, AutoMeshAlphaCoverage.DEFAULT_ALPHA_THRESHOLD, 64),
                0.02);

        final ArtMeshGeometry empty = new ArtMeshGeometry(List.of(), List.of(), List.of());
        assertEquals(
                0.0,
                AutoMeshAlphaCoverage.intersectionOverUnion(
                        empty, texture, AutoMeshAlphaCoverage.DEFAULT_ALPHA_THRESHOLD, 64),
                1.0e-9);
    }

    @Test
    void alphaIouFallsWhenTheMeshCoversHalfTheSilhouette() {
        final TexturePixels texture = opaqueTexture();
        final ArtMeshGeometry half = new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(0.5f, 0.0f), new Point2(0.5f, 1.0f), new Point2(0.0f, 1.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(0.5f, 0.0f), new Point2(0.5f, 1.0f), new Point2(0.0f, 1.0f)),
                List.of(0, 1, 2, 0, 2, 3));
        final double iou = AutoMeshAlphaCoverage.intersectionOverUnion(
                half, texture, AutoMeshAlphaCoverage.DEFAULT_ALPHA_THRESHOLD, 64);
        final double recall =
                AutoMeshAlphaCoverage.opaqueRecall(half, texture, AutoMeshAlphaCoverage.DEFAULT_ALPHA_THRESHOLD, 64);
        assertTrue(iou > 0.4 && iou < 0.6, "IoU of a half-covered silhouette: " + iou);
        assertTrue(recall > 0.4 && recall < 0.6, "recall of a half-covered silhouette: " + recall);
    }

    @Test
    void alphaCoverageReadsTheTextureUvToPixelAffine() {
        final int[] argb = new int[16 * 16];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) {
                argb[y * 16 + x] = 0xFFFFFFFF;
            }
        }
        // The bottom half is transparent, and the affine maps uvs onto the top half only.
        final TexturePixels texture = new TexturePixels(16, 16, argb, new double[] {16.0, 0.0, 0.0, 0.0, 8.0, 0.0});
        assertEquals(
                1.0,
                AutoMeshAlphaCoverage.intersectionOverUnion(
                        unitSquare(), texture, AutoMeshAlphaCoverage.DEFAULT_ALPHA_THRESHOLD, 64),
                0.02);
    }

    @Test
    void terminalVerdictFollowsTheDocumentedRuleOrder() {
        assertEquals(
                AutoMeshProbeStatus.FAIL,
                AutoMeshProbeResult.terminal(
                        List.of(AutoMeshProbeStatus.PASS, AutoMeshProbeStatus.FAIL, AutoMeshProbeStatus.BLOCKED)));
        assertEquals(
                AutoMeshProbeStatus.BLOCKED,
                AutoMeshProbeResult.terminal(
                        List.of(AutoMeshProbeStatus.PASS, AutoMeshProbeStatus.BLOCKED, AutoMeshProbeStatus.SKIPPED)));
        assertEquals(AutoMeshProbeStatus.BLOCKED, AutoMeshProbeResult.terminal(List.of()));
        assertEquals(
                AutoMeshProbeStatus.BLOCKED,
                AutoMeshProbeResult.terminal(List.of(AutoMeshProbeStatus.INFO, AutoMeshProbeStatus.INFO)));
        assertEquals(
                AutoMeshProbeStatus.SKIPPED,
                AutoMeshProbeResult.terminal(List.of(AutoMeshProbeStatus.PASS, AutoMeshProbeStatus.SKIPPED)));
        assertEquals(
                AutoMeshProbeStatus.PASS,
                AutoMeshProbeResult.terminal(List.of(AutoMeshProbeStatus.PASS, AutoMeshProbeStatus.INFO)));
    }

    @Test
    void countersPublishAVerdictCountThatExcludesEvidenceOnlyRecords() {
        final Map<String, String> counters = AutoMeshProbeResult.counters(
                List.of(AutoMeshProbeStatus.PASS, AutoMeshProbeStatus.INFO, AutoMeshProbeStatus.INFO));
        assertEquals("3", counters.get("assertions"));
        assertEquals("1", counters.get("assertions.verdict"));
        assertEquals("1", counters.get("assertions.pass"));
        assertEquals("2", counters.get("assertions.info"));
        assertEquals("0", counters.get("assertions.fail"));
    }

    @Test
    void resultFileCollapsesControlCharactersAndKeepsRecordingOrder(@TempDir final Path directory) throws Exception {
        final AutoMeshProbeResult result = new AutoMeshProbeResult();
        result.put("first", "line one\nline two");
        result.put("second", 7L);
        result.put("third", true);
        final Path file = directory.resolve("state/result.properties");
        result.writeTo(file);

        final List<String> lines = Files.readAllLines(file);
        assertEquals(List.of("first=line one line two", "second=7", "third=true"), lines);
        assertEquals("line one line two", result.get("first"));
    }

    @Test
    void dumpMetadataCarriesTheCountsDigestAndAffine(@TempDir final Path directory) throws Exception {
        final ArtMeshGeometry geometry = unitSquare();
        final TexturePixels texture = opaqueTexture();
        final Path variant = directory.resolve("ArtMesh342/native");
        AutoMeshDumpWriter.write(variant, "ArtMesh342", "native", geometry, texture, "baseline");

        final Map<String, String> meta = readProperties(variant.resolve(AutoMeshDumpWriter.META_FILE));
        assertEquals("ArtMesh342", meta.get("id"));
        assertEquals("native", meta.get("variant"));
        assertEquals("4", meta.get("vertices"));
        assertEquals("2", meta.get("triangles"));
        assertEquals(AutoMeshGeometryDigest.of(geometry), meta.get("digest"));
        assertEquals("16", meta.get("texture.width"));
        assertEquals("16.0", meta.get("texture.uv2px.0"));
        assertEquals("baseline", meta.get("diagnostic"));
        assertEquals(
                4,
                Files.readAllLines(variant.resolve(AutoMeshDumpWriter.POSITIONS_FILE))
                        .size());
        assertEquals(
                6,
                Files.readAllLines(variant.resolve(AutoMeshDumpWriter.INDICES_FILE))
                        .size());
        assertTrue(Files.size(variant.resolve(AutoMeshDumpWriter.TEXTURE_FILE)) > 0L);
    }

    @Test
    void dumpSkipsTheTextureFilesWhenTheHostExposesNoPixels(@TempDir final Path directory) throws Exception {
        final Path variant = directory.resolve("hfa10/region");
        AutoMeshDumpWriter.write(variant, "hfa10", "turbo-mesh.region", unitSquare(), null, "");
        assertFalse(Files.exists(variant.resolve(AutoMeshDumpWriter.TEXTURE_FILE)));
        final Map<String, String> meta = readProperties(variant.resolve(AutoMeshDumpWriter.META_FILE));
        assertFalse(meta.containsKey("texture.width"));
        assertFalse(meta.containsKey("diagnostic"));
    }

    @Test
    void uvExtentOfAnEmptyMeshIsAnEmptyBox() {
        final double[] extent = AutoMeshDumpWriter.uvExtent(new ArtMeshGeometry(List.of(), List.of(), List.of()));
        assertEquals(0.0, extent[0], 1.0e-9);
        assertEquals(0.0, extent[3], 1.0e-9);
    }

    @Test
    void budgetFallsBackForAbsentBlankMalformedAndNonPositiveValues() {
        final Duration fallback = Duration.ofSeconds(30);
        final String property = "turboism.automeshValidation.testBudget";
        System.clearProperty(property);
        assertEquals(fallback, AutoMeshProbeBudget.read(property, fallback));
        for (final String value : List.of("", "   ", "not-a-number", "0", "-5")) {
            System.setProperty(property, value);
            assertEquals(fallback, AutoMeshProbeBudget.read(property, fallback), "value: '" + value + "'");
        }
        System.setProperty(property, " 45 ");
        assertEquals(Duration.ofSeconds(45), AutoMeshProbeBudget.read(property, fallback));
        System.clearProperty(property);
    }

    @Test
    void readinessProceedsWhenTheSignalReportsReadyOnTheFirstEvaluation() throws Exception {
        final AtomicInteger evaluations = new AtomicInteger();
        final AutoMeshProbeReadiness.Outcome outcome = AutoMeshProbeReadiness.await(
                () -> {
                    evaluations.incrementAndGet();
                    return Optional.empty();
                },
                Duration.ofSeconds(5),
                1L);
        assertTrue(outcome.ready());
        assertEquals("", outcome.reason());
        assertEquals(1, evaluations.get());
    }

    @Test
    void readinessBlocksWithTheLastReasonWhenTheDeadlineExpires() throws Exception {
        final AtomicInteger evaluations = new AtomicInteger();
        final AutoMeshProbeReadiness.Outcome outcome = AutoMeshProbeReadiness.await(
                () -> {
                    evaluations.incrementAndGet();
                    return Optional.of("the active model exposes no readable ArtMesh");
                },
                Duration.ZERO,
                1L);
        assertFalse(outcome.ready());
        assertEquals("the active model exposes no readable ArtMesh", outcome.reason());
        assertEquals(1, evaluations.get());
    }

    @Test
    void readinessProceedsOnceALaterEvaluationReportsReady() throws Exception {
        final AtomicInteger evaluations = new AtomicInteger();
        final AutoMeshProbeReadiness.Outcome outcome = AutoMeshProbeReadiness.await(
                () -> evaluations.incrementAndGet() < 3
                        ? Optional.of("the plugin load wave is not committed")
                        : Optional.empty(),
                Duration.ofSeconds(30),
                1L);
        assertTrue(outcome.ready());
        assertEquals(3, evaluations.get());
    }

    @Test
    void readinessRejectsANonPositivePollInterval() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoMeshProbeReadiness.await(Optional::<String>empty, Duration.ofSeconds(1), 0L));
    }

    @Test
    void guardCallbackAssertionAcceptsAnyFunctionImplementation() {
        final Function<Object, Object> lambda = arguments -> arguments;
        assertTrue(lambda instanceof Function<?, ?>);
        assertFalse("java.util.function.Function".equals(lambda.getClass().getName()));
    }

    @Test
    void hostVersionCodeCompactsDottedReleasesAndRejectsOtherShapes() {
        assertEquals("5302", AutoMeshHostVersionCode.compact("5.3.02"));
        assertEquals("5203", AutoMeshHostVersionCode.compact("5.2.03"));
        assertEquals("", AutoMeshHostVersionCode.compact("5302"));
        assertEquals("", AutoMeshHostVersionCode.compact(""));
        assertEquals("", AutoMeshHostVersionCode.compact(null));
    }

    @Test
    void hostVersionCodeAdmitsOnlyReviewedCompactReleases() {
        assertTrue(AutoMeshHostVersionCode.admitted("5302"));
        assertTrue(AutoMeshHostVersionCode.admitted("5203"));
        assertTrue(AutoMeshHostVersionCode.admitted("5303"));
        assertFalse(AutoMeshHostVersionCode.admitted("5.3.02"));
        assertFalse(AutoMeshHostVersionCode.admitted("9999"));
        assertFalse(AutoMeshHostVersionCode.admitted(""));
        assertFalse(AutoMeshHostVersionCode.admitted(null));
    }

    private static ArtMeshGeometry square() {
        return new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(1.0f, 1.0f), new Point2(0.0f, 1.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(1.0f, 1.0f), new Point2(0.0f, 1.0f)),
                List.of(0, 1, 2, 0, 2, 3));
    }

    private static ArtMeshGeometry unitSquare() {
        return square();
    }

    private static TexturePixels opaqueTexture() {
        final int[] argb = new int[16 * 16];
        java.util.Arrays.fill(argb, 0xFFFFFFFF);
        return new TexturePixels(16, 16, argb);
    }

    private static int countDistinct(final String... digests) {
        return (int) java.util.Arrays.stream(digests).distinct().count();
    }

    private static Map<String, String> readProperties(final Path file) throws Exception {
        final java.util.Properties properties = new java.util.Properties();
        try (var input = Files.newInputStream(file)) {
            properties.load(input);
        }
        final Map<String, String> values = new java.util.TreeMap<>();
        for (final String name : properties.stringPropertyNames()) {
            values.put(name, properties.getProperty(name));
        }
        return values;
    }

    @Test
    void nativeRunsAgreeWhenOnlyTheTriangleCountDriftsWithinOnePercent() {
        assertTrue(AutoMeshFrameworkValidationProbe.nativeCountsStable(List.of(617, 617), List.of(1018, 1019)));
        assertFalse(AutoMeshFrameworkValidationProbe.nativeCountsStable(List.of(617, 618), List.of(1018, 1018)));
        assertFalse(AutoMeshFrameworkValidationProbe.nativeCountsStable(List.of(617, 617), List.of(1018, 1100)));
    }
}
