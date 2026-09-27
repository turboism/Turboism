package dev.turboism.adapter.cubism.optimization.stateelision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Read-only artifact verification for the redundant-state transformer: the
 * pinned JOGL digest, every tracked method body, and the transformed class
 * shape. Does not initialize or launch the Editor.
 */
class RedundantStateElisionArtifactTest {

    private static Path joglArtifact() {
        final String supplied = System.getenv("TURBOISM_UNIFORM_HOST_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "explicit exact-host artifact not supplied");
        final Path artifact = Path.of(supplied).toAbsolutePath();
        assertTrue(Files.isRegularFile(artifact), "explicit artifact must exist, not silently skip");
        return artifact.getParent().resolve("jogl/jogl-all.jar").normalize();
    }

    @Test void pinnedJoglCarriesEveryTrackedMethodWithConcreteBodies() throws Exception {
        final Path jogl = joglArtifact();
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_03_JOGL, HostArtifactDigest.from(jogl));
        try (JarFile jar = new JarFile(jogl.toFile())) {
            final byte[] reference;
            try (var input = jar.getInputStream(jar.getJarEntry(
                    RedundantStateElisionTarget.OWNER + ".class"))) {
                reference = input.readAllBytes();
            }
            final List<URL> urls = new ArrayList<>();
            urls.add(jogl.toUri().toURL());
            try (URLClassLoader loader =
                     new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader())) {
                final RedundantStateElisionTransformer transformer =
                    new RedundantStateElisionTransformer(loader, jogl, reference);
                assertNull(transformer.failure());
                final ProtectionDomain domain = new ProtectionDomain(
                    new CodeSource(jogl.toUri().toURL(), (Certificate[]) null), null);
                final byte[] changed = transformer.transform(null, loader,
                    RedundantStateElisionTarget.OWNER, null, domain, reference);
                assertNotNull(changed, transformer.failure());
                assertEquals(1, transformer.matches());
                assertTrue(transformer.sites() > RedundantStateElisionTarget.SITES.size(),
                    "invalidators must also instrument: " + transformer.sites());
                assertFalse(transformer.invalidatorNames().isEmpty());
                assertTrue(transformer.invalidatorNames().values().stream()
                        .anyMatch(name -> name.startsWith("glDelete")),
                    "glDelete* methods must be invalidators");
                assertTrue(transformer.invalidatorNames().values().contains("glBindFramebuffer"));
                assertTrue(transformer.invalidatorNames().values().contains("glBindVertexArray"));
                assertNotNull(transformer.beforeSha256());
                final Map<String, String> methods = methods(reference);
                assertEquals(methods, methods(changed), "no added, removed or renamed methods");
            }
        }
    }

    @Test void foreignClassIsIgnored() throws Exception {
        final Path jogl = joglArtifact();
        try (JarFile jar = new JarFile(jogl.toFile());
             URLClassLoader loader = new URLClassLoader(new URL[] {jogl.toUri().toURL()},
                 getClass().getClassLoader())) {
            final byte[] reference;
            try (var input = jar.getInputStream(jar.getJarEntry(
                    RedundantStateElisionTarget.OWNER + ".class"))) {
                reference = input.readAllBytes();
            }
            final RedundantStateElisionTransformer transformer =
                new RedundantStateElisionTransformer(loader, jogl, reference);
            assertNull(transformer.transform(null, loader, "other/Class", null, null, reference));
            assertNull(transformer.transform(null, getClass().getClassLoader(),
                RedundantStateElisionTarget.OWNER, null, null, reference));
            assertEquals(0, transformer.matches());
        }
    }

    private static Map<String, String> methods(final byte[] bytes) {
        final Map<String, String> result = new LinkedHashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(final int access, final String name,
                    final String descriptor, final String signature, final String[] exceptions) {
                result.put(name + descriptor, descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return result;
    }
}
