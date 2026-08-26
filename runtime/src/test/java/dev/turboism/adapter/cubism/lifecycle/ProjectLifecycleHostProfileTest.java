package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.mapping.verification.HostArtifactDigest;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectLifecycleHostProfileTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = locateLegacyEvidence();

    @Test
    void exactLifecycleSelectorsExistInBothReviewedCubismArtifacts() throws Exception {
        for (String version : List.of("Cubism-5.2", "Cubism-5.3.02")) {
            final Path artifact = LEGACY_EVIDENCE.resolve(version + "/jars/Live2D_Cubism.jar");
            final ProjectLifecycleHostProfile profile = ProjectLifecycleHostProfile.forArtifact(
                HostArtifactDigest.from(artifact)
            ).orElseThrow();
            assertEquals(7, profile.bindings().size());
            try (URLClassLoader loader = loader(artifact)) {
                for (ProjectLifecycleNativeMethodTransformer.Binding binding : profile.bindings()) {
                    final Class<?> owner = Class.forName(
                        binding.ownerInternalName().replace('/', '.'),
                        false,
                        loader
                    );
                    assertTrue(Stream.of(owner.getDeclaredMethods()).anyMatch(method ->
                        method.getName().equals(binding.methodName())
                            && Type.getMethodDescriptor(method).equals(binding.descriptor())
                    ), version + " missing selector " + binding);
                }
            }
        }
    }

    @Test
    void reviewedJarTypesSeparateProjectContentFromEditorDocuments() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve(
            "Cubism-5.3.02/jars/Live2D_Cubism.jar"
        );
        try (URLClassLoader loader = loader(artifact)) {
            final Class<?> document = Class.forName(
                "com.live2d.cubism.doc.IDocument",
                false,
                loader
            );
            final Class<?> fileContent = Class.forName(
                "com.live2d.cubism.doc.IFileContent",
                false,
                loader
            );
            final Class<?> model = Class.forName(
                "com.live2d.cubism.doc.modeling.CModelingDocument",
                false,
                loader
            );
            final Class<?> scene = Class.forName(
                "com.live2d.cubism.doc.animation.CSceneDocument",
                false,
                loader
            );
            final Class<?> animation = Class.forName(
                "com.live2d.cubism.doc.animation.CAnimationFileContent",
                false,
                loader
            );
            final Class<?> imageDocument = Class.forName(
                "com.live2d.cubism.doc.resources.g",
                false,
                loader
            );
            final Class<?> imageEntry = Class.forName(
                "com.live2d.cubism.doc.resources.CImageDocumentProjectEntry",
                false,
                loader
            );

            assertTrue(document.isAssignableFrom(model));
            assertTrue(fileContent.isAssignableFrom(model));
            assertTrue(document.isAssignableFrom(scene));
            assertFalse(fileContent.isAssignableFrom(scene));
            assertTrue(fileContent.isAssignableFrom(animation));
            assertFalse(document.isAssignableFrom(animation));
            assertTrue(document.isAssignableFrom(imageDocument));
            assertFalse(fileContent.isAssignableFrom(imageDocument));
            assertFalse(document.isAssignableFrom(imageEntry));
            assertFalse(fileContent.isAssignableFrom(imageEntry));
        }
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
        final String configured = System.getenv("TURBOISM_LEGACY_EVIDENCE");
        final List<Path> candidates = new java.util.ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        candidates.add(PROJECT_ROOT.resolve("../turboism-legacy/cubism-ref").normalize());
        candidates.add(Path.of("/workspace/projects/turboism-legacy/cubism-ref"));
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
            return new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader());
        }
    }
}
