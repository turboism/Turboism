package dev.turboism.adapter.cubism.optimization.readback;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Read-only artifact verification for the pipelined readback transformer: pinned JOGL digest,
 * exact {@code postGL} shape, the single rewritten readback call, and fail-closed behaviour on
 * drift. Does not initialize or launch the Editor.
 */
class AsyncReadbackArtifactTest {

    private static Path joglArtifact() {
        final String supplied = System.getenv("TURBOISM_UNIFORM_HOST_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "explicit exact-host artifact not supplied");
        final Path artifact = Path.of(supplied).toAbsolutePath();
        assertTrue(Files.isRegularFile(artifact), "explicit artifact must exist, not silently skip");
        return artifact.getParent().resolve("jogl/jogl-all.jar").normalize();
    }

    @Test
    void pinnedJoglRewritesExactlyTheGlslReadback() throws Exception {
        final Path jogl = joglArtifact();
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_03_JOGL, HostArtifactDigest.from(jogl));
        assertTrue(AsyncReadbackTarget.admitted(HostArtifactDigest.from(jogl)));
        try (JarFile jar = new JarFile(jogl.toFile())) {
            final byte[] reference;
            try (var input = jar.getInputStream(jar.getJarEntry(AsyncReadbackTarget.OWNER + ".class"))) {
                reference = input.readAllBytes();
            }
            final List<URL> urls = new ArrayList<>();
            urls.add(jogl.toUri().toURL());
            try (URLClassLoader loader =
                    new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader())) {
                final AsyncReadbackTransformer transformer = new AsyncReadbackTransformer(loader, jogl, reference);
                final ProtectionDomain domain =
                        new ProtectionDomain(new CodeSource(jogl.toUri().toURL(), (Certificate[]) null), null);
                final byte[] changed =
                        transformer.transform(loader, AsyncReadbackTarget.OWNER, null, domain, reference);
                assertNotNull(changed, transformer.failure());
                assertEquals(1, transformer.matches());
                assertNotNull(transformer.beforeSha256());

                final List<String> calls = readbackCalls(changed);
                assertEquals(2, calls.size(), "one pipelined and one native client readback expected");
                assertEquals(
                        "dev/turboism/adapter/cubism/optimization/readback/AsyncReadbackBridge.read"
                                + AsyncReadbackTarget.BRIDGE_DESCRIPTOR,
                        calls.get(0));
                assertEquals("com/jogamp/opengl/GL.glReadPixels" + AsyncReadbackTarget.READ_DESCRIPTOR, calls.get(1));
            }
        }
    }

    @Test
    void driftedShapeIsRefused() throws Exception {
        final Path jogl = joglArtifact();
        try (JarFile jar = new JarFile(jogl.toFile())) {
            final byte[] reference;
            try (var input = jar.getInputStream(jar.getJarEntry(AsyncReadbackTarget.OWNER + ".class"))) {
                reference = input.readAllBytes();
            }
            try (URLClassLoader loader = new URLClassLoader(
                    new URL[] {jogl.toUri().toURL()}, getClass().getClassLoader())) {
                final AsyncReadbackTransformer transformer = new AsyncReadbackTransformer(loader, jogl, reference);
                final ProtectionDomain domain =
                        new ProtectionDomain(new CodeSource(jogl.toUri().toURL(), (Certificate[]) null), null);
                final byte[] tampered = reference.clone();
                // Flip one code byte inside the class body: the reviewed shape no longer matches.
                tampered[tampered.length / 2] ^= 0x01;
                assertNull(transformer.transform(loader, AsyncReadbackTarget.OWNER, null, domain, tampered));
                assertNotNull(transformer.failure());
                assertEquals(0, transformer.matches());
            }
        }
    }

    @Test
    void foreignClassAndLoaderAreIgnored() throws Exception {
        final Path jogl = joglArtifact();
        try (JarFile jar = new JarFile(jogl.toFile());
                URLClassLoader loader = new URLClassLoader(
                        new URL[] {jogl.toUri().toURL()}, getClass().getClassLoader())) {
            final byte[] reference;
            try (var input = jar.getInputStream(jar.getJarEntry(AsyncReadbackTarget.OWNER + ".class"))) {
                reference = input.readAllBytes();
            }
            final AsyncReadbackTransformer transformer = new AsyncReadbackTransformer(loader, jogl, reference);
            assertNull(transformer.transform(loader, "other/Class", null, null, reference));
            assertNull(transformer.transform(
                    getClass().getClassLoader(), AsyncReadbackTarget.OWNER, null, null, reference));
            assertEquals(0, transformer.matches());
        }
    }

    private static List<String> readbackCalls(final byte[] bytes) {
        final List<String> calls = new ArrayList<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!AsyncReadbackTarget.METHOD.equals(name)
                                        || !AsyncReadbackTarget.DESCRIPTOR.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String name,
                                            final String descriptor,
                                            final boolean isInterface) {
                                        if (AsyncReadbackTarget.READ_METHOD.equals(name)
                                                && AsyncReadbackTarget.READ_DESCRIPTOR.equals(descriptor)) {
                                            calls.add(owner + "." + name + descriptor);
                                        } else if (AsyncReadbackTarget.BRIDGE_OWNER.equals(owner)
                                                && AsyncReadbackTarget.BRIDGE_METHOD.equals(name)) {
                                            calls.add(owner + "." + name + descriptor);
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return calls;
    }
}
