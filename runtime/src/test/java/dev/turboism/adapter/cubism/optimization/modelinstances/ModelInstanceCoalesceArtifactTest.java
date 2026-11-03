package dev.turboism.adapter.cubism.optimization.modelinstances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Exact-artifact admission for the model-instance coalescer. Versions without a
 * configured official jar are skipped, not failed.
 */
class ModelInstanceCoalesceArtifactTest {

    @Test
    void cubism5304SeamsAreAdmittedAndPreserveEveryOtherMethod() throws Exception {
        final Path artifact = artifact("turboism.test.cubism5304Jar", "5.3.04");
        try (var files = Files.walk(artifact.getParent());
                var loader = new URLClassLoader(
                        files.filter(path -> path.toString().endsWith(".jar"))
                                .map(path -> {
                                    try {
                                        return path.toUri().toURL();
                                    } catch (Exception failure) {
                                        throw new IllegalStateException(failure);
                                    }
                                })
                                .toArray(URL[]::new),
                        ModelInstanceCoalesceArtifactTest.class.getClassLoader());
                var jar = new JarFile(artifact.toFile())) {
            final byte[] source = bytes(jar, ModelInstanceCoalesceTarget.SOURCE_OWNER);
            final byte[] editMode = bytes(jar, ModelInstanceCoalesceTarget.EDIT_MODE_OWNER);
            final byte[] undoManager = bytes(jar, ModelInstanceCoalesceTarget.UNDO_MANAGER_OWNER);
            assertNotNull(ReviewedMethodShape.read(
                    source,
                    ModelInstanceCoalesceTarget.SOURCE_OWNER,
                    ModelInstanceCoalesceTarget.UPDATE_METHOD,
                    "()V"));
            assertNotNull(ReviewedMethodShape.read(
                    editMode,
                    ModelInstanceCoalesceTarget.EDIT_MODE_OWNER,
                    ModelInstanceCoalesceTarget.COMMIT_METHOD,
                    "()V"));
            assertNotNull(ReviewedMethodShape.read(
                    undoManager,
                    ModelInstanceCoalesceTarget.UNDO_MANAGER_OWNER,
                    ModelInstanceCoalesceTarget.UNDO_METHOD,
                    "()V"));
            assertNotNull(ReviewedMethodShape.read(
                    undoManager,
                    ModelInstanceCoalesceTarget.UNDO_MANAGER_OWNER,
                    ModelInstanceCoalesceTarget.REDO_METHOD,
                    "()V"));

            final ModelInstanceCoalesceTransformer transformer =
                    new ModelInstanceCoalesceTransformer(loader, artifact, source, editMode, undoManager);
            final ProtectionDomain domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
            assertNotNull(
                    transformer.transform(null, loader, ModelInstanceCoalesceTarget.SOURCE_OWNER, null, domain, source),
                    transformer.failure());
            assertNotNull(
                    transformer.transform(
                            null, loader, ModelInstanceCoalesceTarget.EDIT_MODE_OWNER, null, domain, editMode),
                    transformer.failure());
            assertNotNull(
                    transformer.transform(
                            null, loader, ModelInstanceCoalesceTarget.UNDO_MANAGER_OWNER, null, domain, undoManager),
                    transformer.failure());
            assertEquals(4, transformer.matches());
            assertTrue(transformer.beforeSha256().containsKey(ModelInstanceCoalesceTarget.SOURCE_OWNER));
            assertTrue(transformer.beforeSha256().containsKey(ModelInstanceCoalesceTarget.EDIT_MODE_OWNER));
            assertTrue(transformer.beforeSha256().containsKey(ModelInstanceCoalesceTarget.UNDO_MANAGER_OWNER));
        }
    }

    private static Path artifact(final String property, final String version) {
        String configured = System.getProperty(property, "");
        if (configured.isBlank()) {
            final String ref = System.getProperty(
                    "turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
            assumeTrue(!ref.isBlank(), "exact " + version + " artifact not configured");
            configured =
                    Path.of(ref).resolve(version).resolve("Live2D_Cubism.jar").toString();
        }
        final Path artifact = Path.of(configured);
        assumeTrue(Files.isRegularFile(artifact), "artifact absent: " + artifact);
        return artifact;
    }

    private static byte[] bytes(final JarFile jar, final String owner) throws Exception {
        final var entry = jar.getJarEntry(owner + ".class");
        assertNotNull(entry, "class absent from official artifact: " + owner);
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }
}
