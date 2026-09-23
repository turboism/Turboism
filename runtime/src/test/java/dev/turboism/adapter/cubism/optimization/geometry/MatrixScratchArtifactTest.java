package dev.turboism.adapter.cubism.optimization.geometry;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Random;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MatrixScratchArtifactTest {
    @Test void exactNativeGeneralMultiplicationIsRawBitEquivalentAndInputsUnchanged() throws Exception {
        String configured = System.getenv("TURBOISM_UNIFORM_HOST_JAR");
        assumeTrue(configured != null && !configured.isBlank(), "explicit reviewed Editor input is required");
        Path jar = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(UniformLocationLifecycleTransformer.supportedEditor(HostArtifactDigest.from(jar)));
        ArrayList<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        try (var files = Files.walk(jar.getParent(), 2)) {
            for (Path dependency : files.filter(p -> p.getFileName().toString().startsWith("kotlin-stdlib") && p.toString().endsWith(".jar")).toList()) {
                urls.add(dependency.toUri().toURL());
            }
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
             JarFile reference = new JarFile(jar.toFile())) {
            Class<?> matrix = Class.forName(MatrixScratchTransformer.MATRIX.replace('/', '.'), true, loader);
            Class<?> product = Class.forName("com.live2d.graphics3d.type.a", true, loader);
            var array = matrix.getMethod("a");
            var allocateProduct = product.getMethod("a", matrix, matrix);
            var inPlace = matrix.getMethod("b", float[].class, float[].class, float[].class, boolean.class);
            Random random = new Random(0x341);
            for (int iteration = 0; iteration < 2048; iteration++) {
                Object left = matrix.getConstructor().newInstance(), right = matrix.getConstructor().newInstance();
                Object destination = matrix.getConstructor().newInstance();
                float[] l = (float[]) array.invoke(left), r = (float[]) array.invoke(right), out = (float[]) array.invoke(destination);
                for (int i = 0; i < 16; i++) {
                    l[i] = iteration % 4 == 0 ? Float.intBitsToFloat(random.nextInt()) : (random.nextFloat() - .5f) * 200f;
                    r[i] = iteration % 4 == 0 ? Float.intBitsToFloat(random.nextInt()) : (random.nextFloat() - .5f) * 200f;
                }
                if (iteration < 8) {
                    float[] edge = {0f, -0f, Float.NaN, Float.intBitsToFloat(0x7fc00001), Float.POSITIVE_INFINITY,
                        Float.NEGATIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE};
                    l[iteration] = edge[iteration]; r[15 - iteration] = edge[iteration];
                }
                float[] originalLeft = l.clone(), originalRight = r.clone();
                float[] expected = (float[]) array.invoke(allocateProduct.invoke(null, left, right));
                inPlace.invoke(left, l, r, out, false);
                rawEquals(expected, out); rawEquals(originalLeft, l); rawEquals(originalRight, r);
            }
            byte[] transform = reference.getInputStream(reference.getJarEntry(MatrixScratchTransformer.OWNER + ".class")).readAllBytes();
            MatrixScratchTransformer rewrite = new MatrixScratchTransformer(loader, jar, transform);
            var domain = new ProtectionDomain(new CodeSource(jar.toUri().toURL(), (Certificate[]) null), null);
            byte[] rewritten = rewrite.transform(null, loader, MatrixScratchTransformer.OWNER, null, domain, transform);
            assertNotNull(rewritten, rewrite.failure());
            assertEquals(1, rewrite.matches());
            new ClassReader(transform).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals(MatrixScratchTransformer.METHOD) || !descriptor.equals(MatrixScratchTransformer.DESCRIPTOR)) {
                        assertEquals(ReviewedMethodShape.read(transform, MatrixScratchTransformer.OWNER, name, descriptor),
                            ReviewedMethodShape.read(rewritten, MatrixScratchTransformer.OWNER, name, descriptor), name);
                    }
                    return null;
                }
            }, ClassReader.SKIP_DEBUG);
            // Verify rewritten bytecode in a loader without initializing the Editor or UI.
            class Definer extends ClassLoader {
                Definer() { super(loader); }
                Class<?> define() { return defineClass(MatrixScratchTransformer.OWNER.replace('/', '.'), rewritten, 0, rewritten.length); }
            }
            assertNotNull(new Definer().define().getDeclaredMethods());
        }
    }
    private static void rawEquals(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]), "raw element " + i);
    }
}
