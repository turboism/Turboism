package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorModelProfileSelectorContract;
import dev.turboism.mapping.verification.selector.EditorMorphTargetSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterStructureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartStructureSelectorContract;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorStructureSelectorContractTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = PROJECT_ROOT.resolve("../turboism-legacy/cubism-ref");

    @Test
    void exact5302RecordVerifiesPartStructureContract() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("cubism-ref/verification/cubism-5.3.02-editor-model.json"),
            artifact,
            loader(artifact)
        );
        assertTrue(resolver.authorizesFeature(
            EditorPartStructureSelectorContract.ADAPTER_SLICE_ID,
            EditorPartStructureSelectorContract.CAPABILITY_ID,
            EditorPartStructureSelectorContract.REQUIRED_ALIASES
        ));
    }

    @Test
    void exact5302RecordVerifiesParameterStructureContract() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("cubism-ref/verification/cubism-5.3.02-editor-model.json"),
            artifact,
            loader(artifact)
        );
        assertTrue(resolver.authorizesFeature(
            EditorParameterStructureSelectorContract.ADAPTER_SLICE_ID,
            EditorParameterStructureSelectorContract.CAPABILITY_ID,
            EditorParameterStructureSelectorContract.REQUIRED_ALIASES
        ));
    }

    @Test
    void exact5302RecordVerifiesMorphTargetReadAndWriteContracts() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("cubism-ref/verification/cubism-5.3.02-editor-model.json"),
            artifact,
            loader(artifact)
        );
        assertTrue(resolver.authorizesFeature(
            EditorMorphTargetSelectorContract.ADAPTER_SLICE_ID,
            EditorMorphTargetSelectorContract.READ_CAPABILITY_ID,
            EditorMorphTargetSelectorContract.READ_REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorMorphTargetSelectorContract.ADAPTER_SLICE_ID,
            EditorMorphTargetSelectorContract.WRITE_CAPABILITY_ID,
            EditorMorphTargetSelectorContract.WRITE_REQUIRED_ALIASES
        ));
    }

    @Test
    void exact5302RecordVerifiesModelProfileContracts() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("cubism-ref/verification/cubism-5.3.02-editor-model.json"),
            artifact,
            loader(artifact)
        );
        assertTrue(resolver.authorizesFeature(
            EditorModelProfileSelectorContract.ADAPTER_SLICE_ID,
            EditorModelProfileSelectorContract.NAME_WRITE_CAPABILITY_ID,
            EditorModelProfileSelectorContract.NAME_WRITE_REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorModelProfileSelectorContract.ADAPTER_SLICE_ID,
            EditorModelProfileSelectorContract.PROFILE_READ_CAPABILITY_ID,
            EditorModelProfileSelectorContract.PROFILE_READ_REQUIRED_ALIASES
        ));
    }

    @Test
    void exact5203RecordVerifiesAllNewStructureContracts() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.2/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("cubism-ref/verification/cubism-5.2-editor-model.json"),
            artifact,
            loader(artifact)
        );
        assertTrue(resolver.authorizesFeature(
            EditorPartStructureSelectorContract.ADAPTER_SLICE_ID,
            EditorPartStructureSelectorContract.CAPABILITY_ID,
            EditorPartStructureSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorParameterStructureSelectorContract.ADAPTER_SLICE_ID,
            EditorParameterStructureSelectorContract.CAPABILITY_ID,
            EditorParameterStructureSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorMorphTargetSelectorContract.ADAPTER_SLICE_ID,
            EditorMorphTargetSelectorContract.READ_CAPABILITY_ID,
            EditorMorphTargetSelectorContract.READ_REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorMorphTargetSelectorContract.ADAPTER_SLICE_ID,
            EditorMorphTargetSelectorContract.WRITE_CAPABILITY_ID,
            EditorMorphTargetSelectorContract.WRITE_REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorModelProfileSelectorContract.ADAPTER_SLICE_ID,
            EditorModelProfileSelectorContract.NAME_WRITE_CAPABILITY_ID,
            EditorModelProfileSelectorContract.NAME_WRITE_REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorModelProfileSelectorContract.ADAPTER_SLICE_ID,
            EditorModelProfileSelectorContract.PROFILE_READ_CAPABILITY_ID,
            EditorModelProfileSelectorContract.PROFILE_READ_REQUIRED_ALIASES
        ));
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !java.nio.file.Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root is unavailable");
        return current;
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
