package dev.turboism.adapter.cubism.optimization.inspector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Exact-artifact admission for the inspector visual-property bypass. Versions without a
 * configured official jar are skipped, not failed.
 */
class InspectorPropertyBypassArtifactTest {

    @Test
    void cubism5304SeamsTransformFromOfficialBytes() throws Exception {
        final Path artifact = artifact("turboism.test.cubism5304Jar", "5.3.04");
        final URL[] urls;
        try (var files = Files.walk(artifact.getParent())) {
            urls = files.filter(path -> path.toString().endsWith(".jar"))
                    .map(path -> {
                        try {
                            return path.toUri().toURL();
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .toArray(URL[]::new);
        }
        final Map<String, byte[]> references = new LinkedHashMap<>();
        try (var loader = new URLClassLoader(urls, InspectorPropertyBypassArtifactTest.class.getClassLoader());
                var jar = new JarFile(artifact.toFile())) {
            for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
                final var entry = jar.getJarEntry(seam.owner() + ".class");
                assertNotNull(entry, "class absent from official artifact: " + seam.owner());
                try (var input = jar.getInputStream(entry)) {
                    references.put(seam.owner(), input.readAllBytes());
                }
                assertNotNull(
                        ReviewedMethodShape.read(
                                references.get(seam.owner()), seam.owner(), seam.method(), seam.descriptor()),
                        "reviewed setter absent: " + seam.owner());
            }

            final InspectorPropertyBypassTransformer transformer =
                    new InspectorPropertyBypassTransformer(loader, artifact, references);
            final ProtectionDomain domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
            final Map<String, byte[]> transformed = new LinkedHashMap<>();
            for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
                final byte[] output =
                        transformer.transform(null, loader, seam.owner(), null, domain, references.get(seam.owner()));
                assertNotNull(output, transformer.failure());
                transformed.put(seam.binaryName(), output);
            }
            assertEquals(InspectorPropertyBypassTarget.SEAMS.size(), transformer.matches());
            for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
                assertEquals(
                        transformer.beforeSha256().get(seam.owner()),
                        java.util.HexFormat.of()
                                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(references.get(seam.owner()))));
            }
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
}
