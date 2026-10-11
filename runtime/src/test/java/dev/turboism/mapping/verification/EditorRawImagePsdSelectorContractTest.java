package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.mapping.verification.selector.EditorRawImagePsdSelectorContract;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Static and synthetic guard for reviewed exact raw-image PSD export/parse seams. */
class EditorRawImagePsdSelectorContractTest {
    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path RECORD_PATH =
            PROJECT_ROOT.resolve("compatibility/cubism/verification/cubism-5.3.02-editor-model.json");
    private static final Path DRAFT_PACK_PATH =
            PROJECT_ROOT.resolve("compatibility/cubism/mapping-packs/draft/cubism-5.3.02-editor-model-read.json");

    @ParameterizedTest
    @ValueSource(strings = {"5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"})
    void exactRecordsVerifyEveryPsdExportAndParseSelector(final String version) throws Exception {
        final Path recordPath =
                PROJECT_ROOT.resolve("compatibility/cubism/verification/cubism-" + version + "-editor-model.json");
        final Path artifact = EditorSelectorContractTestPaths.artifact(version);
        final var loaded = new StaticVerificationRecordLoader().load(recordPath);
        final var report = new StaticSelectorVerifier()
                .verify(artifact, loaded.record().artifact(), loaded.record().selectors());

        assertTrue(
                report.allSelectorsVerified(),
                report.results().stream()
                        .filter(result -> result.status() != StaticVerificationStatus.VERIFIED_STATIC)
                        .map(result -> result.alias() + ": " + result.message())
                        .toList()
                        .toString());

        try (final URLClassLoader loader = loader(artifact)) {
            final var resolver = new VerifiedEditorModelResolverFactory().create(recordPath, artifact, loader);
            assertTrue(EditorRawImagePsdSelectorContract.SUPPORTED_CUBISM_VERSIONS.contains(version));
            assertTrue(resolver.isExactCubismVersion(version));
            final Set<String> required = new HashSet<>(EditorRawImagePsdSelectorContract.REQUIRED_ALIASES);
            if (EditorRawImagePsdSelectorContract.TRANSPARENCY_SHAPES_ABSENT_VERSIONS.contains(version)) {
                required.removeAll(EditorRawImagePsdSelectorContract.TRANSPARENCY_SHAPES_ALIASES);
            }
            assertTrue(resolver.authorizesFeature(
                    EditorRawImagePsdSelectorContract.ADAPTER_SLICE_ID,
                    EditorRawImagePsdSelectorContract.CAPABILITY_ID,
                    required));
        }
    }

    @Test
    void recordPinsTheNativeParserFieldMethodConstructorAndSaveShapes() throws Exception {
        final StaticVerificationRecord record =
                new StaticVerificationRecordLoader().load(RECORD_PATH).record();
        final Map<String, StaticSelector> selectors = new HashMap<>();
        for (final StaticSelector selector : record.selectors()) {
            selectors.put(selector.alias(), selector);
        }

        assertShape(
                selectors,
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_CLASS_ALIAS,
                StaticSelector.Kind.CLASS,
                "com/live2d/graphics/psd/CPsdDocument",
                "",
                "",
                1,
                0);
        assertShape(
                selectors,
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_ALIAS,
                StaticSelector.Kind.FIELD,
                "com/live2d/graphics/psd/CPsdDocument",
                "a",
                "Lcom/live2d/graphics/psd/CPsdDocument$a;",
                9,
                0);
        assertShape(
                selectors,
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_CLASS_ALIAS,
                StaticSelector.Kind.CLASS,
                "com/live2d/graphics/psd/CPsdDocument$a",
                "",
                "",
                1,
                0);
        assertShape(
                selectors,
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_PARSE_FILE_ALIAS,
                StaticSelector.Kind.METHOD,
                "com/live2d/graphics/psd/CPsdDocument$a",
                "a",
                "(Ljava/io/File;ZZ)Lcom/live2d/graphics/psd/CPsdDocument;",
                1,
                8);
        assertShape(
                selectors,
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_FROM_PSD_ALIAS,
                StaticSelector.Kind.CONSTRUCTOR,
                "com/live2d/cubism/doc/resources/CLayeredImage",
                "<init>",
                "(Lcom/live2d/graphics/psd/CPsdDocument;Ljava/io/File;Ljava/lang/String;)V",
                1,
                8);
        assertShape(
                selectors,
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_SAVE_PSD_ALIAS,
                StaticSelector.Kind.METHOD,
                "com/live2d/cubism/doc/resources/CLayeredImage",
                "save",
                "(Ljava/io/File;Lcom/live2d/util/a/a;)V",
                1,
                8);
        for (final String retired : java.util.List.of(
                "cubism.editor-model.layered-image.dispose-owned",
                "cubism.editor-model.psd-document.layers-owned",
                "cubism.editor-model.psd-layer.image-owned",
                "cubism.editor-model.psd-image.dispose-owned")) {
            org.junit.jupiter.api.Assertions.assertFalse(selectors.containsKey(retired));
        }
        assertShape(
                selectors,
                EditorRawImagePsdSelectorContract.PSD_PROGRESS_CLASS_ALIAS,
                StaticSelector.Kind.CLASS,
                "com/live2d/util/a/a",
                "",
                "",
                1,
                0);
        assertShape(
                selectors,
                EditorRawImagePsdSelectorContract.PSD_PROGRESS_DEFAULT_ALIAS,
                StaticSelector.Kind.METHOD,
                "com/live2d/util/a/a",
                "e",
                "()Lcom/live2d/util/a/a;",
                9,
                0);
    }

    @Test
    void recordPinsTheLayeredImageDimensionsUsedByIntegrityObservation() throws Exception {
        final Map<String, StaticSelector> selectors = new HashMap<>();
        for (final StaticSelector selector :
                new StaticVerificationRecordLoader().load(RECORD_PATH).record().selectors()) {
            selectors.put(selector.alias(), selector);
        }

        assertShape(
                selectors,
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_WIDTH_ALIAS,
                StaticSelector.Kind.METHOD,
                "com/live2d/cubism/doc/resources/CLayeredImage",
                "getWidth",
                "()I",
                17,
                8);
        assertShape(
                selectors,
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_HEIGHT_ALIAS,
                StaticSelector.Kind.METHOD,
                "com/live2d/cubism/doc/resources/CLayeredImage",
                "getHeight",
                "()I",
                17,
                8);
    }

    @Test
    void draftMappingPackCarriesEveryNewPsdSelectorAlias() throws Exception {
        final var pack = new ObjectMapper().readTree(DRAFT_PACK_PATH.toFile());
        final Set<String> aliases = new HashSet<>();
        pack.get("entries").forEach(entry -> aliases.add(entry.get("name").asText()));

        assertTrue(aliases.containsAll(Set.of(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_WIDTH_ALIAS,
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_HEIGHT_ALIAS,
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_CLASS_ALIAS,
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_ALIAS,
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_CLASS_ALIAS,
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_PARSE_FILE_ALIAS,
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_FROM_PSD_ALIAS,
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_SAVE_PSD_ALIAS,
                EditorRawImagePsdSelectorContract.PSD_PROGRESS_CLASS_ALIAS,
                EditorRawImagePsdSelectorContract.PSD_PROGRESS_DEFAULT_ALIAS)));
        assertTrue(
                aliases.containsAll(EditorRawImagePsdSelectorContract.REQUIRED_ALIASES),
                "draft mapping pack misses an admitted PSD selector alias");
    }

    @Test
    void psdCapabilityAndSelectorsAreAdmittedForReviewedGenerations() {
        final var manifest5302 = EditorModelVerificationManifest.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_02);
        final var manifest52 = EditorModelVerificationManifest.forArtifact(ReviewedHostArtifacts.CUBISM_5_2_03);
        final var manifest5200 = EditorModelVerificationManifest.forArtifact(ReviewedHostArtifacts.CUBISM_5_2_00);
        final var manifest5303 = EditorModelVerificationManifest.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03);
        final java.util.Set<String> transparencyAbsent =
                new java.util.HashSet<>(EditorRawImagePsdSelectorContract.REQUIRED_ALIASES);
        transparencyAbsent.removeAll(EditorRawImagePsdSelectorContract.TRANSPARENCY_SHAPES_ALIASES);

        assertTrue(manifest5302.capabilityIds().contains(EditorRawImagePsdSelectorContract.CAPABILITY_ID));
        assertTrue(manifest5302.requiredAliases().containsAll(EditorRawImagePsdSelectorContract.REQUIRED_ALIASES));
        assertTrue(manifest52.capabilityIds().contains(EditorRawImagePsdSelectorContract.CAPABILITY_ID));
        assertTrue(manifest52.requiredAliases().containsAll(transparencyAbsent));
        assertFalse(manifest52
                .requiredAliases()
                .containsAll(EditorRawImagePsdSelectorContract.TRANSPARENCY_SHAPES_ALIASES));
        assertTrue(manifest5200.capabilityIds().contains(EditorRawImagePsdSelectorContract.CAPABILITY_ID));
        assertTrue(manifest5200.requiredAliases().containsAll(transparencyAbsent));
        assertFalse(manifest5200
                .requiredAliases()
                .containsAll(EditorRawImagePsdSelectorContract.TRANSPARENCY_SHAPES_ALIASES));
        assertTrue(manifest5303.capabilityIds().contains(EditorRawImagePsdSelectorContract.CAPABILITY_ID));
        assertTrue(manifest5303.requiredAliases().containsAll(EditorRawImagePsdSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void reviewedVersionWithoutCompleteSelectorsCannotAuthorizePsdExport() {
        final var resolver = TestVerifiedResolvers.create(
                "5.3.03",
                EditorRawImagePsdSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of(EditorRawImagePsdSelectorContract.CAPABILITY_ID),
                java.util.List.of(StaticSelector.classSelector("fixture.class", "java/lang/Object")),
                getClass().getClassLoader());

        assertTrue(EditorRawImagePsdSelectorContract.SUPPORTED_CUBISM_VERSIONS.stream()
                .anyMatch(resolver::isExactCubismVersion));
        assertFalse(resolver.authorizesFeature(
                EditorRawImagePsdSelectorContract.ADAPTER_SLICE_ID,
                EditorRawImagePsdSelectorContract.CAPABILITY_ID,
                EditorRawImagePsdSelectorContract.REQUIRED_ALIASES));
    }

    private static void assertShape(
            final Map<String, StaticSelector> selectors,
            final String alias,
            final StaticSelector.Kind kind,
            final String owner,
            final String member,
            final String descriptor,
            final int requiredAccessFlags,
            final int forbiddenAccessFlags) {
        final StaticSelector selector = selectors.get(alias);
        assertTrue(selector != null, "record is missing selector " + alias);
        assertEquals(kind, selector.kind(), alias);
        assertEquals(owner, selector.ownerInternalName(), alias);
        assertEquals(member, selector.memberName(), alias);
        assertEquals(descriptor, selector.descriptor(), alias);
        assertEquals(requiredAccessFlags, selector.requiredAccessFlags(), alias);
        assertEquals(forbiddenAccessFlags, selector.forbiddenAccessFlags(), alias);
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root is unavailable");
        return current;
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        try (Stream<Path> files = Files.list(artifact.getParent())) {
            final URL[] classpath = files.filter(
                            path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .map(path -> {
                        try {
                            return path.toUri().toURL();
                        } catch (java.net.MalformedURLException exception) {
                            throw new IllegalArgumentException(exception);
                        }
                    })
                    .toArray(URL[]::new);
            return new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader());
        }
    }
}
