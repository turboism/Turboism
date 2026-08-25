package dev.turboism.adapter.cubism;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ResourceKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedProjectWorkspaceImageDocumentTest {

    private static final Path LEGACY_EVIDENCE = locateLegacyEvidence();

    @AfterEach
    void clearHost() {
        ImageSyntheticAppCtrl.instance = null;
    }

    @Test
    void layeredPsdDocumentUsesItsReviewedResourcePathInsteadOfUnimplementedFileContent()
        throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve(
            "Cubism-5.3.02/jars/Live2D_Cubism.jar"
        );
        try (URLClassLoader loader = loader(artifact)) {
            final Class<?> layeredImageType = Class.forName(
                "com.live2d.cubism.doc.resources.CLayeredImage",
                true,
                loader
            );
            final Object layeredImage = layeredImageType.getConstructor().newInstance();
            layeredImageType.getMethod("setName", String.class).invoke(
                layeredImage,
                "Character Source"
            );
            layeredImageType.getMethod("setPsdFile", File.class).invoke(
                layeredImage,
                new File("C:/assets/character.psd")
            );
            final Class<?> imageDocumentType = Class.forName(
                "com.live2d.cubism.doc.resources.g",
                true,
                loader
            );
            final Object imageDocument = imageDocumentType
                .getConstructor(layeredImageType)
                .newInstance(layeredImage);
            ImageSyntheticAppCtrl.instance = new ImageSyntheticAppCtrl(
                new ImageSyntheticProject(
                    List.of(imageDocument),
                    List.of(layeredImage)
                ),
                imageDocument
            );
            final VerifiedProjectWorkspaceHostOperations operations =
                new VerifiedProjectWorkspaceHostOperations(resolver(loader), "5.3.02");

            final var activeDocument = operations.activeDocument().orElseThrow();
            final var project = operations.activeProject().orElseThrow();
            final var content = project.contents().get(0);

            assertEquals(DocumentKind.IMAGE, activeDocument.kind());
            assertEquals("Character Source", activeDocument.name());
            assertTrue(activeDocument.filePath().isEmpty());
            assertTrue(activeDocument.model().isEmpty());
            assertTrue(activeDocument.animation().isEmpty());
            assertEquals(ProjectContentKind.IMAGE, content.kind());
            assertEquals(content.contentId(), activeDocument.contentId().orElseThrow());
            assertEquals(List.of(activeDocument.documentId()), content.documentIds());
            assertEquals(1, content.resources().size());
            assertEquals(ResourceKind.PSD, content.resources().get(0).kind());
            assertEquals("character.psd", content.resources().get(0).relativePath().orElseThrow());
            assertFalse(activeDocument.relativePath().contains("C:"));
        }
    }

    private static VerifiedMemberResolver resolver(final ClassLoader classLoader) {
        final String host = name(ImageSyntheticAppCtrl.class);
        final String project = name(ImageSyntheticProject.class);
        return TestVerifiedResolvers.create(
            ProjectWorkspaceAdapter.ADAPTER_SLICE_ID,
            java.util.Set.of(ProjectWorkspaceAdapter.PROJECT_CAPABILITY_ID),
            List.of(
                StaticSelector.staticMethod(
                    "cubism.app-controller.instance",
                    host,
                    "instance",
                    "()L" + host + ";",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.app-controller.current-project",
                    host,
                    "currentProject",
                    "()L" + project + ";",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.app-controller.current-document",
                    host,
                    "currentDocument",
                    "()Ljava/lang/Object;",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.project.documents",
                    project,
                    "documents",
                    "()Ljava/util/List;",
                    StaticSelector.ACCESS_PUBLIC
                )
            ),
            classLoader
        );
    }

    private static String name(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static Path locateLegacyEvidence() {
        final String configured = System.getenv("TURBOISM_LEGACY_EVIDENCE");
        final List<Path> candidates = new java.util.ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            candidates.add(current.resolve("../turboism-legacy/cubism-ref").normalize());
            current = current.getParent();
        }
        return candidates.stream()
            .filter(Files::isDirectory)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("legacy Cubism evidence is unavailable"));
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
            return new URLClassLoader(
                classpath,
                VerifiedProjectWorkspaceImageDocumentTest.class.getClassLoader()
            );
        }
    }

    public static final class ImageSyntheticAppCtrl {
        private static ImageSyntheticAppCtrl instance;
        private final ImageSyntheticProject project;
        private final Object document;

        private ImageSyntheticAppCtrl(
            final ImageSyntheticProject project,
            final Object document
        ) {
            this.project = project;
            this.document = document;
        }

        public static ImageSyntheticAppCtrl instance() {
            return instance;
        }

        public ImageSyntheticProject currentProject() {
            return project;
        }

        public Object currentDocument() {
            return document;
        }
    }

    public record ImageSyntheticProject(
        List<Object> documents,
        List<Object> children
    ) {
        public List<Object> getChildren() {
            return children;
        }
    }
}
