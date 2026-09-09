package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.mapping.verification.selector.EditorRawImagePsdSelectorContract;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static and synthetic guard for the exact 5.3.02 raw-image PSD export/parse seam. */
class EditorRawImagePsdSelectorContractTest {
    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = locateLegacyEvidence();
    private static final Path RECORD_PATH = PROJECT_ROOT.resolve(
        "compatibility/cubism/verification/cubism-5.3.02-editor-model.json"
    );
    private static final Path DRAFT_PACK_PATH = PROJECT_ROOT.resolve(
        "compatibility/cubism/mapping-packs/draft/cubism-5.3.02-editor-model-read.json"
    );
    private static final Path ARTIFACT = LEGACY_EVIDENCE.resolve(
        "Cubism-5.3.02/jars/Live2D_Cubism.jar"
    );

    @Test
    void exact5302RecordVerifiesEveryPsdExportAndParseSelector() throws Exception {
        final var loaded = new StaticVerificationRecordLoader().load(RECORD_PATH);
        final var report = new StaticSelectorVerifier().verify(
            ARTIFACT,
            loaded.record().artifact(),
            loaded.record().selectors()
        );

        assertTrue(
            report.allSelectorsVerified(),
            report.results().stream()
                .filter(result -> result.status() != StaticVerificationStatus.VERIFIED_STATIC)
                .map(result -> result.alias() + ": " + result.message())
                .toList()
                .toString()
        );

        final var resolver = new VerifiedEditorModelResolverFactory().create(
            RECORD_PATH,
            ARTIFACT,
            loader(ARTIFACT)
        );
        assertTrue(resolver.isExactCubismVersion(EditorRawImagePsdSelectorContract.SUPPORTED_CUBISM_VERSION));
        assertTrue(resolver.authorizesFeature(
            EditorRawImagePsdSelectorContract.ADAPTER_SLICE_ID,
            EditorRawImagePsdSelectorContract.CAPABILITY_ID,
            EditorRawImagePsdSelectorContract.REQUIRED_ALIASES
        ));
    }

    @Test
    void recordPinsTheNativeParserFieldMethodConstructorAndSaveShapes() throws Exception {
        final StaticVerificationRecord record = new StaticVerificationRecordLoader()
            .load(RECORD_PATH)
            .record();
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
            0
        );
        assertShape(
            selectors,
            EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_ALIAS,
            StaticSelector.Kind.FIELD,
            "com/live2d/graphics/psd/CPsdDocument",
            "a",
            "Lcom/live2d/graphics/psd/CPsdDocument$a;",
            9,
            0
        );
        assertShape(
            selectors,
            EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_CLASS_ALIAS,
            StaticSelector.Kind.CLASS,
            "com/live2d/graphics/psd/CPsdDocument$a",
            "",
            "",
            1,
            0
        );
        assertShape(
            selectors,
            EditorRawImagePsdSelectorContract.PSD_DOCUMENT_PARSE_FILE_ALIAS,
            StaticSelector.Kind.METHOD,
            "com/live2d/graphics/psd/CPsdDocument$a",
            "a",
            "(Ljava/io/File;ZZ)Lcom/live2d/graphics/psd/CPsdDocument;",
            1,
            8
        );
        assertShape(
            selectors,
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_FROM_PSD_ALIAS,
            StaticSelector.Kind.CONSTRUCTOR,
            "com/live2d/cubism/doc/resources/CLayeredImage",
            "<init>",
            "(Lcom/live2d/graphics/psd/CPsdDocument;Ljava/io/File;Ljava/lang/String;)V",
            1,
            8
        );
        assertShape(
            selectors,
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_SAVE_PSD_ALIAS,
            StaticSelector.Kind.METHOD,
            "com/live2d/cubism/doc/resources/CLayeredImage",
            "save",
            "(Ljava/io/File;Lcom/live2d/util/a/a;)V",
            1,
            8
        );
    }

    @Test
    void draftMappingPackCarriesEveryNewPsdSelectorAlias() throws Exception {
        final var pack = new ObjectMapper().readTree(DRAFT_PACK_PATH.toFile());
        final Set<String> aliases = new HashSet<>();
        pack.get("entries").forEach(entry -> aliases.add(entry.get("name").asText()));

        assertTrue(aliases.containsAll(Set.of(
            EditorRawImagePsdSelectorContract.PSD_DOCUMENT_CLASS_ALIAS,
            EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_ALIAS,
            EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_CLASS_ALIAS,
            EditorRawImagePsdSelectorContract.PSD_DOCUMENT_PARSE_FILE_ALIAS,
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_FROM_PSD_ALIAS,
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_SAVE_PSD_ALIAS
        )));
    }

    @Test
    void psdCapabilityAndSelectorsAreAdmittedOnlyFor5302() {
        final var manifest5302 = EditorModelVerificationManifest.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_02
        );
        final var manifest52 = EditorModelVerificationManifest.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_2_03
        );
        final var manifest5303 = EditorModelVerificationManifest.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_03
        );

        assertTrue(manifest5302.capabilityIds().contains(EditorRawImagePsdSelectorContract.CAPABILITY_ID));
        assertTrue(manifest5302.requiredAliases().containsAll(EditorRawImagePsdSelectorContract.REQUIRED_ALIASES));
        assertFalse(manifest52.capabilityIds().contains(EditorRawImagePsdSelectorContract.CAPABILITY_ID));
        assertFalse(manifest52.requiredAliases().containsAll(EditorRawImagePsdSelectorContract.REQUIRED_ALIASES));
        assertFalse(manifest5303.capabilityIds().contains(EditorRawImagePsdSelectorContract.CAPABILITY_ID));
        assertFalse(manifest5303.requiredAliases().containsAll(EditorRawImagePsdSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void syntheticResolverWithAnotherVersionCannotAuthorizeThe5302Contract() {
        final var resolver = TestVerifiedResolvers.create(
            "5.3.03",
            EditorRawImagePsdSelectorContract.ADAPTER_SLICE_ID,
            java.util.Set.of(EditorRawImagePsdSelectorContract.CAPABILITY_ID),
            java.util.List.of(StaticSelector.classSelector("fixture.class", "java/lang/Object")),
            getClass().getClassLoader()
        );

        assertFalse(resolver.isExactCubismVersion(EditorRawImagePsdSelectorContract.SUPPORTED_CUBISM_VERSION));
    }

    private static void assertShape(
        final Map<String, StaticSelector> selectors,
        final String alias,
        final StaticSelector.Kind kind,
        final String owner,
        final String member,
        final String descriptor,
        final int requiredAccessFlags,
        final int forbiddenAccessFlags
    ) {
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

    private static Path locateLegacyEvidence() {
        final Path explicit = Path.of("/opt/dev/projects/turboism-legacy/cubism-ref");
        if (Files.isDirectory(explicit)) return explicit;
        throw new IllegalStateException("legacy Cubism evidence directory is unavailable");
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        try (Stream<Path> files = Files.list(artifact.getParent())) {
            final URL[] classpath = files
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
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
