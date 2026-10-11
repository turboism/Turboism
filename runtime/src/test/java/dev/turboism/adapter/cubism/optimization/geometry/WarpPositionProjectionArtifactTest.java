package dev.turboism.adapter.cubism.optimization.geometry;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

class WarpPositionProjectionArtifactTest {
    @Test
    void exact5302ConsumerTransformsWithoutChangingOtherMethods() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_3_02, "5.3.02");
    }

    @Test
    void exact5203ConsumerTransformsWithoutChangingOtherMethods() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_2_03, "5.2.03");
    }

    @Test
    void exact5303ConsumerTransformsWithoutChangingOtherMethods() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_3_03, "5.3.03");
    }

    @Test
    void exact5304ConsumerTransformsWithoutChangingOtherMethods() throws Exception {
        exercise(ReviewedHostArtifacts.CUBISM_5_3_04, "5.3.04");
    }

    private static void exercise(final HostArtifactDigest expected, final String version) throws Exception {
        String configured = System.getProperty("turboism.test.cubism" + version.replace(".", "") + "Jar", "");
        if (configured.isBlank()) {
            final String ref = System.getProperty(
                    "turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
            assumeTrue(!ref.isBlank(), "exact " + version + " artifact not configured");
            configured =
                    Path.of(ref).resolve(version).resolve("Live2D_Cubism.jar").toString();
        }
        Path artifact = Path.of(configured);
        assertEquals(expected, HostArtifactDigest.from(artifact));
        URL[] urls;
        try (var files = Files.walk(artifact.getParent())) {
            urls = files.filter(p -> p.toString().endsWith(".jar"))
                    .map(p -> {
                        try {
                            return p.toUri().toURL();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toArray(URL[]::new);
        }
        try (var loader = new URLClassLoader(urls, WarpPositionProjectionArtifactTest.class.getClassLoader());
                var jar = new JarFile(artifact.toFile())) {
            byte[] bytes;
            try (var input = jar.getInputStream(jar.getJarEntry(WarpPositionProjectionTransformer.TARGET + ".class"))) {
                bytes = input.readAllBytes();
            }
            var transformer = new WarpPositionProjectionTransformer(loader, artifact, bytes);
            var domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
            byte[] output =
                    transformer.transform(null, loader, WarpPositionProjectionTransformer.TARGET, null, domain, bytes);
            assertNotNull(output, transformer.failure());
            assertEquals(1, transformer.matches());
            List<String[]> methods = new ArrayList<>();
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access, String name, String desc, String sig, String[] exceptions) {
                                    if (!name.equals(WarpPositionProjectionTransformer.METHOD)
                                            || !desc.equals(WarpPositionProjectionTransformer.DESCRIPTOR))
                                        methods.add(new String[] {name, desc});
                                    return null;
                                }
                            },
                            0);
            for (String[] method : methods)
                assertEquals(
                        ReviewedMethodShape.read(bytes, WarpPositionProjectionTransformer.TARGET, method[0], method[1]),
                        ReviewedMethodShape.read(
                                output, WarpPositionProjectionTransformer.TARGET, method[0], method[1]),
                        method[0]);
            assertTrue(methods.size() > 5);
        }
        assertEquals(expected, HostArtifactDigest.from(artifact));
    }
}
