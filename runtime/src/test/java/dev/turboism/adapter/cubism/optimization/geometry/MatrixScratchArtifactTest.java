package dev.turboism.adapter.cubism.optimization.geometry;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class MatrixScratchArtifactTest {
    private static final String REF_DIR_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_DIR_ENV = "TURBOISM_CUBISM_REF";
    private static final String CUBISM_JAR = "Live2D_Cubism.jar";
    private static final String ENTITY_CLASS = "com.live2d.graphics3d.entity.GEntity";
    private static final int JAR_WALK_DEPTH = 2;
    private static final int MATRIX_ELEMENT_COUNT = 16;
    private static final int HIERARCHY_DEPTH = 4;
    private static final long TEST_SEED = 0x50304L;
    private static final float[] EDGE_FLOATS = {0f, -0f, 1.5f, -2.25f, 45f, -90f, 180f, 360f};
    private static final String KEY_CALLS = "calls";
    private static final String KEY_CACHE_HITS = "cacheHits";
    private static final String KEY_CACHE_MISSES = "cacheMisses";
    private static final String KEY_STATIC_HITS = "staticHits";
    private static final String KEY_FAILURES = "failures";
    private static final String JAR_SUFFIX = ".jar";
    private static final String KOTLIN_STDLIB_PREFIX = "kotlin-stdlib";
    private static final String KOTLIN_MARKER_CLASS = "kotlin.jvm.internal.markers.KMappedMarker";

    @Test
    void exactNativeGeneralMultiplicationIsRawBitEquivalentAndInputsUnchanged() throws Exception {
        String configured = System.getenv("TURBOISM_UNIFORM_HOST_JAR");
        assumeTrue(configured != null && !configured.isBlank(), "explicit reviewed Editor input is required");
        Path jar = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(UniformLocationLifecycleTransformer.supportedEditor(HostArtifactDigest.from(jar)));
        try (URLClassLoader loader =
                        new URLClassLoader(jarUrls(jar.getParent()), ClassLoader.getPlatformClassLoader());
                JarFile reference = new JarFile(jar.toFile())) {
            Class<?> matrix = Class.forName(MatrixScratchTransformer.MATRIX.replace('/', '.'), true, loader);
            Class<?> product = Class.forName("com.live2d.graphics3d.type.a", true, loader);
            var array = matrix.getMethod("a");
            var allocateProduct = product.getMethod("a", matrix, matrix);
            var inPlace = matrix.getMethod("b", float[].class, float[].class, float[].class, boolean.class);
            Random random = new Random(0x341);
            for (int iteration = 0; iteration < 2048; iteration++) {
                Object left = matrix.getConstructor().newInstance(),
                        right = matrix.getConstructor().newInstance();
                Object destination = matrix.getConstructor().newInstance();
                float[] l = (float[]) array.invoke(left),
                        r = (float[]) array.invoke(right),
                        out = (float[]) array.invoke(destination);
                for (int i = 0; i < 16; i++) {
                    l[i] = iteration % 4 == 0
                            ? Float.intBitsToFloat(random.nextInt())
                            : (random.nextFloat() - .5f) * 200f;
                    r[i] = iteration % 4 == 0
                            ? Float.intBitsToFloat(random.nextInt())
                            : (random.nextFloat() - .5f) * 200f;
                }
                if (iteration < 8) {
                    float[] edge = {
                        0f,
                        -0f,
                        Float.NaN,
                        Float.intBitsToFloat(0x7fc00001),
                        Float.POSITIVE_INFINITY,
                        Float.NEGATIVE_INFINITY,
                        Float.MIN_VALUE,
                        Float.MAX_VALUE
                    };
                    l[iteration] = edge[iteration];
                    r[15 - iteration] = edge[iteration];
                }
                float[] originalLeft = l.clone(), originalRight = r.clone();
                float[] expected = (float[]) array.invoke(allocateProduct.invoke(null, left, right));
                inPlace.invoke(left, l, r, out, false);
                rawEquals(expected, out);
                rawEquals(originalLeft, l);
                rawEquals(originalRight, r);
            }
            byte[] transform = reference
                    .getInputStream(reference.getJarEntry(MatrixScratchTransformer.OWNER + ".class"))
                    .readAllBytes();
            MatrixScratchTransformer rewrite = new MatrixScratchTransformer(loader, jar, transform);
            var domain = new ProtectionDomain(new CodeSource(jar.toUri().toURL(), (Certificate[]) null), null);
            byte[] rewritten = rewrite.transform(null, loader, MatrixScratchTransformer.OWNER, null, domain, transform);
            assertNotNull(rewritten, rewrite.failure());
            assertEquals(1, rewrite.matches());
            new ClassReader(transform)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    if (!name.equals(MatrixScratchTransformer.METHOD)
                                            || !descriptor.equals(MatrixScratchTransformer.DESCRIPTOR)) {
                                        assertEquals(
                                                ReviewedMethodShape.read(
                                                        transform, MatrixScratchTransformer.OWNER, name, descriptor),
                                                ReviewedMethodShape.read(
                                                        rewritten, MatrixScratchTransformer.OWNER, name, descriptor),
                                                name);
                                    }
                                    return null;
                                }
                            },
                            ClassReader.SKIP_DEBUG);
            // Verify rewritten bytecode in a loader without initializing the Editor or UI.
            class Definer extends ClassLoader {
                Definer() {
                    super(loader);
                }

                Class<?> define() {
                    return defineClass(
                            MatrixScratchTransformer.OWNER.replace('/', '.'), rewritten, 0, rewritten.length);
                }
            }
            assertNotNull(new Definer().define().getDeclaredMethods());
        }
    }

    @Test
    void everyConfiguredReviewedArtifactRewritesAndMatchesUnpatchedHierarchy() throws Exception {
        Path root = referenceRoot();
        Set<String> versions = ReviewedHostArtifacts.reviewedCubismVersions();
        int verified = 0;
        for (String version : versions) {
            Path artifact = root.resolve(version).resolve(CUBISM_JAR);
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            verifyArtifactVersion(version, artifact);
            verified++;
        }
        assertEquals(versions.size(), verified, "missing reviewed artifacts under " + root);
    }

    private static void verifyArtifactVersion(String version, Path artifact) throws Exception {
        URL[] urls = jarUrls(artifact.getParent());
        TransformEntry entry = readTransformEntry(artifact);
        ProtectionDomain domain =
                new ProtectionDomain(new CodeSource(artifact.toUri().toURL(), entry.signers()), null);
        try (URLClassLoader unpatchedLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
                TransformingLoader patchedLoader = new TransformingLoader(urls, artifact, entry.bytes(), domain)) {
            MatrixScratchTransformer rewrite = patchedLoader.transformer();
            byte[] rewritten = patchedLoader.rewrittenBytes();
            assertNotNull(rewritten, version + ": " + rewrite.failure());
            assertEquals(1, rewrite.matches(), version);
            assertUnmodifiedOtherMethods(entry.bytes(), rewritten, version);

            AtomicBoolean admission = new AtomicBoolean(true);
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, Boolean.TRUE.toString());
            System.getProperties().put(MatrixScratchTransformer.ADMISSION_PROPERTY, admission);
            try (MatrixScratchBridge bridge = new MatrixScratchBridge(patchedLoader, admission)) {
                bridge.activate();
                System.getProperties().put(MatrixScratchTransformer.CALLBACK_PROPERTY, bridge.callback());
                verifyHierarchyParity(unpatchedLoader, patchedLoader, bridge, version);
            } finally {
                System.getProperties().remove(MatrixScratchTransformer.CALLBACK_PROPERTY);
                System.getProperties().remove(MatrixScratchTransformer.ADMISSION_PROPERTY);
                System.clearProperty(MatrixScratchTransformer.ENABLE_PROPERTY);
            }
        }
    }

    private static void verifyHierarchyParity(
            ClassLoader unpatchedLoader, ClassLoader patchedLoader, MatrixScratchBridge bridge, String version)
            throws Exception {
        HostReflection unpatched = HostReflection.open(unpatchedLoader);
        HostReflection patched = HostReflection.open(patchedLoader);
        Object[] unpatchedNodes = unpatched.createHierarchy(HIERARCHY_DEPTH);
        Object[] patchedNodes = patched.createHierarchy(HIERARCHY_DEPTH);
        Random random = new Random(TEST_SEED);
        for (int i = 0; i < HIERARCHY_DEPTH; i++) {
            float px = EDGE_FLOATS[i % EDGE_FLOATS.length];
            float py = (random.nextFloat() - 0.5f) * 20f;
            float pz = EDGE_FLOATS[(i + 1) % EDGE_FLOATS.length];
            float rx = (random.nextFloat() - 0.5f) * 360f;
            float ry = EDGE_FLOATS[(i + 2) % EDGE_FLOATS.length];
            float rz = (random.nextFloat() - 0.5f) * 180f;
            float sx = 1f + random.nextFloat();
            float sy = -0.5f - random.nextFloat();
            float sz = 0.75f + random.nextFloat();
            unpatched.setVectors(unpatchedNodes[i], px, py, pz, rx, ry, rz, sx, sy, sz);
            patched.setVectors(patchedNodes[i], px, py, pz, rx, ry, rz, sx, sy, sz);
        }

        Object unpatchedLeaf = unpatchedNodes[HIERARCHY_DEPTH - 1];
        Object patchedLeaf = patchedNodes[HIERARCHY_DEPTH - 1];
        rawEquals(unpatched.worldArray(unpatchedLeaf), patched.worldArray(patchedLeaf));

        Map<String, Long> afterWarmup = bridge.snapshot();
        long missesBeforeRepeat = afterWarmup.get(KEY_CACHE_MISSES);
        long hitsBeforeRepeat = afterWarmup.get(KEY_CACHE_HITS);
        rawEquals(unpatched.worldArray(unpatchedLeaf), patched.worldArray(patchedLeaf));
        Map<String, Long> afterRepeat = bridge.snapshot();
        assertEquals(0L, afterRepeat.get(KEY_FAILURES), version);
        assertEquals(missesBeforeRepeat, afterRepeat.get(KEY_CACHE_MISSES), version);
        assertEquals(hitsBeforeRepeat + HIERARCHY_DEPTH, afterRepeat.get(KEY_CACHE_HITS), version);
        assertTrue(afterRepeat.get(KEY_CALLS) >= 2L, version);

        unpatched.mutateScaleX(unpatchedNodes[1], -0f);
        patched.mutateScaleX(patchedNodes[1], -0f);
        rawEquals(unpatched.worldArray(unpatchedLeaf), patched.worldArray(patchedLeaf));
        Map<String, Long> afterMutation = bridge.snapshot();
        assertEquals(missesBeforeRepeat + 1L, afterMutation.get(KEY_CACHE_MISSES), version);

        float[] overrideValues = new float[MATRIX_ELEMENT_COUNT];
        for (int i = 0; i < MATRIX_ELEMENT_COUNT; i++) {
            overrideValues[i] = (i + 1) * 0.25f;
        }
        unpatched.setStaticMatrix(unpatchedNodes[2], overrideValues);
        patched.setStaticMatrix(patchedNodes[2], overrideValues);
        rawEquals(unpatched.worldArray(unpatchedLeaf), patched.worldArray(patchedLeaf));
        assertTrue(bridge.snapshot().get(KEY_STATIC_HITS) >= 1L, version);

        unpatched.clearStaticMatrix(unpatchedNodes[2]);
        patched.clearStaticMatrix(patchedNodes[2]);
        rawEquals(unpatched.worldArray(unpatchedLeaf), patched.worldArray(patchedLeaf));
    }

    private static void assertUnmodifiedOtherMethods(byte[] original, byte[] rewritten, String version) {
        new ClassReader(original)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                if (!name.equals(MatrixScratchTransformer.METHOD)
                                        || !descriptor.equals(MatrixScratchTransformer.DESCRIPTOR)) {
                                    assertEquals(
                                            ReviewedMethodShape.read(
                                                    original, MatrixScratchTransformer.OWNER, name, descriptor),
                                            ReviewedMethodShape.read(
                                                    rewritten, MatrixScratchTransformer.OWNER, name, descriptor),
                                            version + ":" + name);
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_DEBUG);
    }

    private static Path referenceRoot() {
        String configured = System.getProperty(REF_DIR_PROPERTY, System.getenv().getOrDefault(REF_DIR_ENV, ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }

    private static URL[] jarUrls(Path directory) throws Exception {
        List<URL> urls = new ArrayList<>();
        boolean hasKotlinStdlib = false;
        try (var files = Files.walk(directory, JAR_WALK_DEPTH)) {
            for (Path path :
                    files.filter(p -> p.toString().endsWith(JAR_SUFFIX)).toList()) {
                if (path.getFileName().toString().startsWith(KOTLIN_STDLIB_PREFIX)) {
                    hasKotlinStdlib = true;
                }
                urls.add(path.toUri().toURL());
            }
        }
        if (!hasKotlinStdlib) {
            URL stdlib = Class.forName(KOTLIN_MARKER_CLASS, false, MatrixScratchArtifactTest.class.getClassLoader())
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation();
            if (stdlib != null) {
                urls.add(stdlib);
            }
        }
        return urls.toArray(URL[]::new);
    }

    private record TransformEntry(byte[] bytes, CodeSigner[] signers) {}

    private static TransformEntry readTransformEntry(Path artifact) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile(), true)) {
            var entry = jar.getJarEntry(MatrixScratchTransformer.OWNER + ".class");
            assertNotNull(entry, MatrixScratchTransformer.OWNER + " absent from " + artifact);
            byte[] bytes;
            try (var input = jar.getInputStream(entry)) {
                bytes = input.readAllBytes();
            }
            return new TransformEntry(bytes, entry.getCodeSigners());
        }
    }

    private static final class TransformingLoader extends URLClassLoader {
        private final ProtectionDomain domain;
        private final MatrixScratchTransformer transformer;
        private final byte[] rewritten;
        private Class<?> cachedTransform;

        TransformingLoader(URL[] urls, Path artifact, byte[] original, ProtectionDomain domain) throws Exception {
            super(urls, ClassLoader.getPlatformClassLoader());
            this.domain = domain;
            this.transformer = new MatrixScratchTransformer(this, artifact, original);
            this.rewritten =
                    this.transformer.transform(null, this, MatrixScratchTransformer.OWNER, null, domain, original);
        }

        MatrixScratchTransformer transformer() {
            return transformer;
        }

        byte[] rewrittenBytes() {
            return rewritten;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if ((MatrixScratchTransformer.OWNER.replace('/', '.')).equals(name)) {
                if (cachedTransform == null) {
                    cachedTransform = defineClass(name, rewritten, 0, rewritten.length, domain);
                }
                return cachedTransform;
            }
            return super.findClass(name);
        }
    }

    private record HostReflection(
            Class<?> entityClass,
            Class<?> matrixClass,
            Method getTransform,
            Method addChild,
            Method getPosition,
            Method getEulerAngles,
            Method getScale,
            Method vectorSet,
            Method vectorSetX,
            Method setStatic,
            Method getWorld,
            Method matrixArray) {

        static HostReflection open(ClassLoader loader) throws Exception {
            Class<?> entity = Class.forName(ENTITY_CLASS, true, loader);
            Class<?> transform = Class.forName(MatrixScratchTransformer.OWNER.replace('/', '.'), true, loader);
            Class<?> matrix = Class.forName(MatrixScratchTransformer.MATRIX.replace('/', '.'), true, loader);
            Class<?> vector = Class.forName("com.live2d.graphics3d.type.GVector3", true, loader);
            return new HostReflection(
                    entity,
                    matrix,
                    entity.getMethod("getTransform"),
                    entity.getMethod("unaryPlus", entity),
                    transform.getMethod("getPosition"),
                    transform.getMethod("getEulerAngles"),
                    transform.getMethod("getScale"),
                    vector.getMethod("set", float.class, float.class, float.class),
                    vector.getMethod("setX", float.class),
                    transform.getMethod("setStaticMatrixLocalToParent", matrix),
                    transform.getMethod(MatrixScratchTransformer.METHOD),
                    matrix.getMethod("a"));
        }

        Object[] createHierarchy(int depth) throws Exception {
            Object[] transforms = new Object[depth];
            Object parent = null;
            for (int i = 0; i < depth; i++) {
                Object entity = entityClass.getConstructor().newInstance();
                if (parent != null) {
                    addChild.invoke(parent, entity);
                }
                transforms[i] = getTransform.invoke(entity);
                parent = entity;
            }
            return transforms;
        }

        void setVectors(
                Object transform,
                float px,
                float py,
                float pz,
                float rx,
                float ry,
                float rz,
                float sx,
                float sy,
                float sz)
                throws Exception {
            vectorSet.invoke(getPosition.invoke(transform), px, py, pz);
            vectorSet.invoke(getEulerAngles.invoke(transform), rx, ry, rz);
            vectorSet.invoke(getScale.invoke(transform), sx, sy, sz);
        }

        void mutateScaleX(Object transform, float value) throws Exception {
            vectorSetX.invoke(getScale.invoke(transform), value);
        }

        void setStaticMatrix(Object transform, float[] values) throws Exception {
            Object matrix = matrixClass.getConstructor().newInstance();
            float[] array = (float[]) matrixArray.invoke(matrix);
            System.arraycopy(values, 0, array, 0, MATRIX_ELEMENT_COUNT);
            setStatic.invoke(transform, matrix);
        }

        void clearStaticMatrix(Object transform) throws Exception {
            setStatic.invoke(transform, new Object[] {null});
        }

        float[] worldArray(Object transform) throws Exception {
            Object matrix = getWorld.invoke(transform);
            return (float[]) matrixArray.invoke(matrix);
        }
    }

    private static void rawEquals(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]), "raw element " + i);
    }
}
