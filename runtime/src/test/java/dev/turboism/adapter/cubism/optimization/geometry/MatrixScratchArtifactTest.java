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
    private static final String KEY_IDENTITY_HITS = "identityHits";
    private static final String KEY_CACHE_MISSES = "cacheMisses";
    private static final String KEY_STATIC_HITS = "staticHits";
    private static final String KEY_FAILURES = "failures";
    private static final int ALLOCATION_WARMUP = 5000;
    private static final int ALLOCATION_ITERS = 1000;
    private static final int ALLOCATION_PASSES = 3;
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
            Path artifact = resolveArtifact(root, version);
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            verifyArtifactVersion(version, artifact);
            verified++;
        }
        assertEquals(versions.size(), verified, "missing reviewed artifacts under " + root);
    }

    @Test
    void renderMatrixTransformerVerifiesParityZeroAllocAndStrictVerifyAllAcrossAllArtifacts() throws Throwable {
        final Path root = referenceRoot();
        final Set<String> versions = ReviewedHostArtifacts.reviewedCubismVersions();
        final List<Path> verifiedArtifacts = new ArrayList<>();
        for (final String version : versions) {
            final Path artifact = resolveArtifact(root, version);
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            verifyRenderMatrixArtifact(version, artifact);
            verifiedArtifacts.add(artifact);
        }
        assertEquals(versions.size(), verifiedArtifacts.size(), "missing reviewed artifacts under " + root);
        verifyStrictBytecodeAcrossAll(verifiedArtifacts);
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

        // Verify identity chain short-circuit skips both PLRU cache lookup and computeLocalToParentMatrix.
        Object[] unpatchedIdentity = unpatched.createHierarchy(HIERARCHY_DEPTH);
        Object[] patchedIdentity = patched.createHierarchy(HIERARCHY_DEPTH);
        for (int i = 0; i < HIERARCHY_DEPTH; i++) {
            float signedZero = (i % 2 == 0) ? 0f : -0f;
            unpatched.setVectors(unpatchedIdentity[i], signedZero, 0f, signedZero, 0f, signedZero, 0f, 1f, 1f, 1f);
            patched.setVectors(patchedIdentity[i], signedZero, 0f, signedZero, 0f, signedZero, 0f, 1f, 1f, 1f);
        }
        Map<String, Long> beforeIdentity = bridge.snapshot();
        rawEquals(
                unpatched.worldArray(unpatchedIdentity[HIERARCHY_DEPTH - 1]),
                patched.worldArray(patchedIdentity[HIERARCHY_DEPTH - 1]));
        Map<String, Long> afterIdentity = bridge.snapshot();
        assertTrue(afterIdentity.get(KEY_IDENTITY_HITS) >= beforeIdentity.get(KEY_IDENTITY_HITS) + HIERARCHY_DEPTH);
        assertEquals(beforeIdentity.get(KEY_CACHE_MISSES), afterIdentity.get(KEY_CACHE_MISSES), version);
        assertEquals(beforeIdentity.get(KEY_CACHE_HITS), afterIdentity.get(KEY_CACHE_HITS), version);

        // Verify identity child with -0.0f scaled parent preserves exact IEEE +0.0f multiplication result.
        unpatched.mutateScaleX(unpatchedIdentity[1], -0f);
        patched.mutateScaleX(patchedIdentity[1], -0f);
        rawEquals(
                unpatched.worldArray(unpatchedIdentity[HIERARCHY_DEPTH - 1]),
                patched.worldArray(patchedIdentity[HIERARCHY_DEPTH - 1]));
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

    private static void verifyRenderMatrixArtifact(final String version, final Path artifact) throws Throwable {
        final URL[] urls = jarUrls(artifact.getParent());
        final byte[] transformBytes = readClassBytes(artifact, MatrixScratchTransformer.OWNER);
        final byte[] meshRendererBytes = readClassBytes(artifact, RenderMatrixTransformer.MESH_RENDERER_OWNER);
        final byte[] lineRendererBytes = readClassBytes(artifact, RenderMatrixTransformer.LINE_RENDERER_OWNER);
        final ProtectionDomain domain =
                new ProtectionDomain(new CodeSource(artifact.toUri().toURL(), (Certificate[]) null), null);

        try (URLClassLoader baseLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
                URLClassLoader guardLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            final RenderMatrixTransformer guardCheck =
                    new RenderMatrixTransformer(guardLoader, artifact, meshRendererBytes, lineRendererBytes);
            assertNull(guardCheck.transform(
                    null, baseLoader, RenderMatrixTransformer.MESH_RENDERER_OWNER, null, domain, meshRendererBytes));
            assertNotNull(guardCheck.failure());
            assertNull(guardCheck.transform(
                    null, guardLoader, RenderMatrixTransformer.MESH_RENDERER_OWNER, null, null, meshRendererBytes));
            assertNull(guardCheck.transform(
                    null,
                    guardLoader,
                    RenderMatrixTransformer.MESH_RENDERER_OWNER,
                    null,
                    domain,
                    injectNop(
                            meshRendererBytes,
                            RenderMatrixTransformer.SORTING_Z_METHOD,
                            RenderMatrixTransformer.SORTING_Z_DESCRIPTOR)));
            assertNull(guardCheck.transform(
                    null,
                    guardLoader,
                    RenderMatrixTransformer.LINE_RENDERER_OWNER,
                    null,
                    domain,
                    injectNop(
                            lineRendererBytes,
                            RenderMatrixTransformer.RENDER_METHOD,
                            RenderMatrixTransformer.RENDER_DESCRIPTOR)));
            assertEquals(0, guardCheck.matches(), version);

            final RenderMatrixTransformer transformer =
                    new RenderMatrixTransformer(baseLoader, artifact, meshRendererBytes, lineRendererBytes);
            final byte[] patchedMeshRenderer = transformer.transform(
                    null, baseLoader, RenderMatrixTransformer.MESH_RENDERER_OWNER, null, domain, meshRendererBytes);
            final byte[] patchedLineRenderer = transformer.transform(
                    null, baseLoader, RenderMatrixTransformer.LINE_RENDERER_OWNER, null, domain, lineRendererBytes);
            assertNotNull(patchedMeshRenderer, version + ": " + transformer.failure());
            assertNotNull(patchedLineRenderer, version + ": " + transformer.failure());
            assertEquals(RenderMatrixTransformer.EXPECTED_MATCHES, transformer.matches(), version);
            assertOtherMethodsUnchanged(
                    meshRendererBytes,
                    patchedMeshRenderer,
                    RenderMatrixTransformer.MESH_RENDERER_OWNER,
                    Set.of(RenderMatrixTransformer.SORTING_Z_METHOD, RenderMatrixTransformer.RENDER_METHOD),
                    version);
            assertOtherMethodsUnchanged(
                    lineRendererBytes,
                    patchedLineRenderer,
                    RenderMatrixTransformer.LINE_RENDERER_OWNER,
                    Set.of(RenderMatrixTransformer.RENDER_METHOD),
                    version);

            final MatrixScratchTransformer scratchTransformer =
                    new MatrixScratchTransformer(baseLoader, artifact, transformBytes);
            final byte[] patchedTransform = scratchTransformer.transform(
                    null, baseLoader, MatrixScratchTransformer.OWNER, null, domain, transformBytes);
            assertNotNull(patchedTransform, version + ": " + scratchTransformer.failure());

            try (MultiOverrideLoader patchedLoader = new MultiOverrideLoader(
                    urls,
                    Map.of(
                            MatrixScratchTransformer.OWNER.replace('/', '.'), patchedTransform,
                            RenderMatrixTransformer.MESH_RENDERER_OWNER.replace('/', '.'), patchedMeshRenderer,
                            RenderMatrixTransformer.LINE_RENDERER_OWNER.replace('/', '.'), patchedLineRenderer),
                    domain)) {
                final AtomicBoolean admission = new AtomicBoolean(true);
                System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, Boolean.TRUE.toString());
                System.getProperties().put(MatrixScratchTransformer.ADMISSION_PROPERTY, admission);
                try (MatrixScratchBridge bridge = new MatrixScratchBridge(patchedLoader, admission)) {
                    assertTrue(bridge.supportsRenderFastPaths(), version);
                    bridge.activate();
                    System.getProperties().put(MatrixScratchBridge.CALLBACK_PROPERTY, bridge.callback());
                    System.getProperties().put(MatrixScratchBridge.RENDER_CALLBACK_PROPERTY, bridge.renderCallback());
                    System.getProperties().put(MatrixScratchBridge.SORTING_CALLBACK_PROPERTY, bridge.sortingCallback());

                    verifySortingZAndRenderScratchParity(
                            baseLoader,
                            patchedLoader,
                            bridge,
                            version,
                            "5.3.05".equals(version) || "5.0.00".equals(version));
                } finally {
                    System.getProperties().remove(MatrixScratchBridge.SORTING_CALLBACK_PROPERTY);
                    System.getProperties().remove(MatrixScratchBridge.RENDER_CALLBACK_PROPERTY);
                    System.getProperties().remove(MatrixScratchBridge.CALLBACK_PROPERTY);
                    System.getProperties().remove(MatrixScratchTransformer.ADMISSION_PROPERTY);
                    System.clearProperty(MatrixScratchTransformer.ENABLE_PROPERTY);
                }
            }
        }
    }

    private static void verifySortingZAndRenderScratchParity(
            final ClassLoader unpatchedLoader,
            final ClassLoader patchedLoader,
            final MatrixScratchBridge bridge,
            final String version,
            final boolean measureAllocations)
            throws Throwable {
        final Object[] baseScene = createSortingTestScene(unpatchedLoader);
        final Object[] patchedScene = createSortingTestScene(patchedLoader);
        // [0]=renderer, [1]=container, [2]=mesh2d, [3]=mesh3d, [4]=camera, [5]=leafTransform, [6]=camZeroDet
        final Method baseSort = baseScene[0]
                .getClass()
                .getMethod(
                        RenderMatrixTransformer.SORTING_Z_METHOD,
                        Class.forName(RenderMatrixTransformer.CAMERA_OWNER.replace('/', '.'), true, unpatchedLoader));
        final Method patSort = patchedScene[0]
                .getClass()
                .getMethod(
                        RenderMatrixTransformer.SORTING_Z_METHOD,
                        Class.forName(RenderMatrixTransformer.CAMERA_OWNER.replace('/', '.'), true, patchedLoader));

        // 1. Null mesh -> 0f
        assertEquals(0f, (float) patSort.invoke(patchedScene[0], new Object[] {null}), version);
        // 2. step == 2 mesh with null camera and transformed hierarchy camera
        baseScene[1]
                .getClass()
                .getMethod(
                        "setMesh",
                        Class.forName(RenderMatrixTransformer.MESH_OWNER.replace('/', '.'), true, unpatchedLoader))
                .invoke(baseScene[1], baseScene[2]);
        patchedScene[1]
                .getClass()
                .getMethod(
                        "setMesh",
                        Class.forName(RenderMatrixTransformer.MESH_OWNER.replace('/', '.'), true, patchedLoader))
                .invoke(patchedScene[1], patchedScene[2]);
        assertRawFloatEquals(
                (float) baseSort.invoke(baseScene[0], new Object[] {null}),
                (float) patSort.invoke(patchedScene[0], new Object[] {null}),
                version + " mesh2 null-cam");
        assertRawFloatEquals(
                (float) baseSort.invoke(baseScene[0], baseScene[4]),
                (float) patSort.invoke(patchedScene[0], patchedScene[4]),
                version + " mesh2 cam");

        // 3. step == 3 mesh with camera & empty positions
        baseScene[1]
                .getClass()
                .getMethod(
                        "setMesh",
                        Class.forName(RenderMatrixTransformer.MESH_OWNER.replace('/', '.'), true, unpatchedLoader))
                .invoke(baseScene[1], baseScene[3]);
        patchedScene[1]
                .getClass()
                .getMethod(
                        "setMesh",
                        Class.forName(RenderMatrixTransformer.MESH_OWNER.replace('/', '.'), true, patchedLoader))
                .invoke(patchedScene[1], patchedScene[3]);
        assertRawFloatEquals(
                (float) baseSort.invoke(baseScene[0], new Object[] {null}),
                (float) patSort.invoke(patchedScene[0], new Object[] {null}),
                version + " mesh3 null-cam");
        assertRawFloatEquals(
                (float) baseSort.invoke(baseScene[0], baseScene[4]),
                (float) patSort.invoke(patchedScene[0], patchedScene[4]),
                version + " mesh3 cam");

        // 4. Zero-determinant camera propagates NoninvertibleTransformException cleanly
        final var baseEx = assertThrows(Exception.class, () -> baseSort.invoke(baseScene[0], baseScene[6]));
        final var patEx = assertThrows(Exception.class, () -> patSort.invoke(patchedScene[0], patchedScene[6]));
        assertEquals(
                baseEx.getCause().getClass().getName(),
                patEx.getCause().getClass().getName(),
                version);

        // 5. Render scratch non-aliasing & re-entrancy with GTransform.getLocalToWorldMatrix()
        final Class<?> patMatrixClass =
                Class.forName(MatrixScratchTransformer.MATRIX.replace('/', '.'), true, patchedLoader);
        final Class<?> baseMatrixClass =
                Class.forName(MatrixScratchTransformer.MATRIX.replace('/', '.'), true, unpatchedLoader);
        final Method patMatArray = patMatrixClass.getMethod("a");
        final Method baseMatArray = baseMatrixClass.getMethod("a");
        final float[] expectedWorld = (float[]) baseMatArray.invoke(baseScene[5]
                .getClass()
                .getMethod(MatrixScratchTransformer.METHOD)
                .invoke(baseScene[5]));
        final Object renderMat1 = bridge.renderCallback().apply(patchedScene[5]);
        // Simulate GCamera.getWorldToCameraMatrix() -> GTransform.getLocalToWorldMatrix() running while renderMat1 is
        // live.
        final Object nestedWorld = patchedScene[5]
                .getClass()
                .getMethod(MatrixScratchTransformer.METHOD)
                .invoke(patchedScene[5]);
        assertNotSame(renderMat1, nestedWorld, version);
        rawEquals(expectedWorld, (float[]) patMatArray.invoke(renderMat1));
        final Object renderMat2 = bridge.renderCallback().apply(patchedScene[5]);
        assertSame(renderMat1, renderMat2, version + " thread-local render scratch matrix must be reused");

        if (measureAllocations) {
            final com.sun.management.ThreadMXBean allocMx =
                    (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            final var lookup = java.lang.invoke.MethodHandles.publicLookup();
            final var sortType = java.lang.invoke.MethodType.methodType(float.class, Object.class, Object.class);
            final java.lang.invoke.MethodHandle patSortHandle =
                    lookup.unreflect(patSort).asType(sortType);
            final Object renderer = patchedScene[0];
            final Object cam = patchedScene[4];
            final Object leafTransform = patchedScene[5];
            final var renderCb = bridge.renderCallback();
            for (int w = 0; w < ALLOCATION_WARMUP; w++) {
                final float z0 = (float) patSortHandle.invokeExact(renderer, (Object) null);
                final float z1 = (float) patSortHandle.invokeExact(renderer, cam);
                final Object m = renderCb.apply(leafTransform);
                if (z0 != z0 && z1 != z1 && m == null) throw new AssertionError();
            }
            final long tid = Thread.currentThread().getId();
            allocMx.getThreadAllocatedBytes(tid);
            long minNullCam = Long.MAX_VALUE;
            long minWithCam = Long.MAX_VALUE;
            long minRenderScratch = Long.MAX_VALUE;
            for (int p = 0; p < ALLOCATION_PASSES; p++) {
                long b0 = allocMx.getThreadAllocatedBytes(tid);
                for (int i = 0; i < ALLOCATION_ITERS; i++) {
                    final float z = (float) patSortHandle.invokeExact(renderer, (Object) null);
                    if (Float.isNaN(z)) throw new AssertionError();
                }
                minNullCam = Math.min(minNullCam, allocMx.getThreadAllocatedBytes(tid) - b0);

                long b1 = allocMx.getThreadAllocatedBytes(tid);
                for (int i = 0; i < ALLOCATION_ITERS; i++) {
                    final float z = (float) patSortHandle.invokeExact(renderer, cam);
                    if (Float.isNaN(z)) throw new AssertionError();
                }
                minWithCam = Math.min(minWithCam, allocMx.getThreadAllocatedBytes(tid) - b1);

                long b2 = allocMx.getThreadAllocatedBytes(tid);
                for (int i = 0; i < ALLOCATION_ITERS; i++) {
                    if (renderCb.apply(leafTransform) == null) throw new AssertionError();
                }
                minRenderScratch = Math.min(minRenderScratch, allocMx.getThreadAllocatedBytes(tid) - b2);
            }
            assertEquals(0L, minNullCam, version + " getSortingZOrder(null) must allocate 0 bytes");
            assertEquals(0L, minWithCam, version + " getSortingZOrder(camera) must allocate 0 bytes");
            assertEquals(0L, minRenderScratch, version + " renderCallback must allocate 0 bytes");
        }
    }

    private static Object[] createSortingTestScene(final ClassLoader loader) throws Exception {
        final Class<?> entityClass = Class.forName(ENTITY_CLASS, true, loader);
        final Class<?> transformClass = Class.forName(MatrixScratchTransformer.OWNER.replace('/', '.'), true, loader);
        final Class<?> vecClass = Class.forName("com.live2d.graphics3d.type.GVector3", true, loader);
        final Class<?> meshRendererClass =
                Class.forName(RenderMatrixTransformer.MESH_RENDERER_OWNER.replace('/', '.'), true, loader);
        final Class<?> containerClass =
                Class.forName(RenderMatrixTransformer.MESH_CONTAINER_OWNER.replace('/', '.'), true, loader);
        final Class<?> mesh2Class = Class.forName("com.live2d.graphics3d.mesh.GMesh2", true, loader);
        final Class<?> mesh3Class = Class.forName("com.live2d.graphics3d.mesh.GMesh3", true, loader);
        final Class<?> cameraClass =
                Class.forName(RenderMatrixTransformer.CAMERA_OWNER.replace('/', '.'), true, loader);
        final Class<?> componentClass = Class.forName("com.live2d.graphics3d.component.GComponent", true, loader);

        final Method unaryPlusEntity = entityClass.getMethod("unaryPlus", entityClass);
        final Method unaryPlusComp = entityClass.getMethod("unaryPlus", componentClass);
        final Method getTransform = entityClass.getMethod("getTransform");
        final Method vecSet = vecClass.getMethod("set", float.class, float.class, float.class);

        final Object root = entityClass.getConstructor().newInstance();
        final Object leaf = entityClass.getConstructor().newInstance();
        unaryPlusEntity.invoke(root, leaf);
        final Object rootT = getTransform.invoke(root);
        final Object leafT = getTransform.invoke(leaf);
        vecSet.invoke(transformClass.getMethod("getPosition").invoke(rootT), 1.5f, -2.0f, 3.25f);
        vecSet.invoke(transformClass.getMethod("getEulerAngles").invoke(rootT), 15f, -30f, 45f);
        vecSet.invoke(transformClass.getMethod("getScale").invoke(rootT), 1.25f, 0.8f, 1.5f);
        vecSet.invoke(transformClass.getMethod("getPosition").invoke(leafT), -0.5f, 1.25f, 2.5f);
        vecSet.invoke(transformClass.getMethod("getEulerAngles").invoke(leafT), -10f, 20f, 5f);
        vecSet.invoke(transformClass.getMethod("getScale").invoke(leafT), 2f, -1.5f, 0.75f);

        final Object renderer = meshRendererClass.getConstructor().newInstance();
        final Object container = containerClass.getConstructor().newInstance();
        unaryPlusComp.invoke(leaf, renderer);
        unaryPlusComp.invoke(leaf, container);

        final Object mesh2 = mesh2Class.getConstructor().newInstance();
        mesh2Class.getMethod("setPositions", float[].class).invoke(mesh2, new float[] {0.5f, -1.25f, 2.0f, 3.0f});
        final Object mesh3 = mesh3Class.getConstructor().newInstance();
        mesh3Class.getMethod("setPositions", float[].class).invoke(mesh3, new float[] {-1.5f, 2.25f, 4.75f});

        final Object camera = cameraClass.getConstructor(String.class).newInstance("test");
        final Object camT = cameraClass.getMethod("getTransform").invoke(camera);
        vecSet.invoke(transformClass.getMethod("getPosition").invoke(camT), 0.75f, 1.5f, -10.0f);
        vecSet.invoke(transformClass.getMethod("getEulerAngles").invoke(camT), 12f, -18f, 8f);

        final Object camZero = cameraClass.getConstructor(String.class).newInstance("zero");
        final Object camZeroT = cameraClass.getMethod("getTransform").invoke(camZero);
        vecSet.invoke(transformClass.getMethod("getScale").invoke(camZeroT), 0f, 0f, 0f);

        return new Object[] {renderer, container, mesh2, mesh3, camera, leafT, camZero};
    }

    private static void verifyStrictBytecodeAcrossAll(final List<Path> artifacts) throws Exception {
        final Path manifest = Files.createTempFile("render-matrix-verify-all-", ".txt");
        try {
            Files.writeString(
                    manifest,
                    String.join(
                            "\n",
                            artifacts.stream()
                                    .map(p -> p.toAbsolutePath().toString())
                                    .toList()));
            final String javaExe =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            final Process process = new ProcessBuilder(
                            javaExe,
                            "-Xverify:all",
                            "-cp",
                            System.getProperty("java.class.path"),
                            StrictRenderMatrixVerifierMain.class.getName(),
                            manifest.toString())
                    .redirectErrorStream(true)
                    .start();
            final String output = new String(process.getInputStream().readAllBytes());
            assertTrue(process.waitFor(90L, java.util.concurrent.TimeUnit.SECONDS), "-Xverify:all timed out");
            assertEquals(0, process.exitValue(), "-Xverify:all failed:\n" + output);
            assertTrue(output.contains("VERIFIED_RENDER_MATRIX=" + artifacts.size()), output);
        } finally {
            Files.deleteIfExists(manifest);
        }
    }

    /** Child entrypoint verifying patched {@code GTransform}, {@code GMeshRenderer}, and {@code GLineRenderer}. */
    public static final class StrictRenderMatrixVerifierMain {
        public static void main(final String[] args) throws Exception {
            final List<String> lines = Files.readAllLines(Path.of(args[0]));
            int verified = 0;
            for (final String line : lines) {
                if (line.isBlank()) continue;
                final Path artifact = Path.of(line.trim());
                final URL[] urls = jarUrls(artifact.getParent());
                final ProtectionDomain domain =
                        new ProtectionDomain(new CodeSource(artifact.toUri().toURL(), (Certificate[]) null), null);
                final byte[] tBytes = readClassBytes(artifact, MatrixScratchTransformer.OWNER);
                final byte[] mBytes = readClassBytes(artifact, RenderMatrixTransformer.MESH_RENDERER_OWNER);
                final byte[] lBytes = readClassBytes(artifact, RenderMatrixTransformer.LINE_RENDERER_OWNER);
                try (URLClassLoader dummy = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
                    final byte[] tPatched = new MatrixScratchTransformer(dummy, artifact, tBytes)
                            .transform(null, dummy, MatrixScratchTransformer.OWNER, null, domain, tBytes);
                    final RenderMatrixTransformer rmx = new RenderMatrixTransformer(dummy, artifact, mBytes, lBytes);
                    final byte[] mPatched = rmx.transform(
                            null, dummy, RenderMatrixTransformer.MESH_RENDERER_OWNER, null, domain, mBytes);
                    final byte[] lPatched = rmx.transform(
                            null, dummy, RenderMatrixTransformer.LINE_RENDERER_OWNER, null, domain, lBytes);
                    if (tPatched == null || mPatched == null || lPatched == null) {
                        throw new AssertionError("transform failed for " + artifact);
                    }
                    try (MultiOverrideLoader loader = new MultiOverrideLoader(
                            urls,
                            Map.of(
                                    MatrixScratchTransformer.OWNER.replace('/', '.'), tPatched,
                                    RenderMatrixTransformer.MESH_RENDERER_OWNER.replace('/', '.'), mPatched,
                                    RenderMatrixTransformer.LINE_RENDERER_OWNER.replace('/', '.'), lPatched),
                            domain)) {
                        Class.forName(MatrixScratchTransformer.OWNER.replace('/', '.'), true, loader);
                        Class.forName(RenderMatrixTransformer.MESH_RENDERER_OWNER.replace('/', '.'), true, loader);
                        Class.forName(RenderMatrixTransformer.LINE_RENDERER_OWNER.replace('/', '.'), true, loader);
                    }
                }
                verified++;
            }
            System.out.println("VERIFIED_RENDER_MATRIX=" + verified);
        }
    }

    private static Path resolveArtifact(final Path root, final String version) {
        final String override = System.getProperty("turboism.test.cubism" + version.replace(".", "") + "Jar", "");
        if (!override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return root.resolve(version).resolve(CUBISM_JAR);
    }

    private static byte[] readClassBytes(final Path artifact, final String owner) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final var entry = jar.getJarEntry(owner + ".class");
            assertNotNull(entry, owner + " absent from " + artifact);
            try (var in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static void assertOtherMethodsUnchanged(
            final byte[] original,
            final byte[] rewritten,
            final String owner,
            final Set<String> mutated,
            final String version) {
        new ClassReader(original)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                if (!mutated.contains(name)) {
                                    assertEquals(
                                            ReviewedMethodShape.read(original, owner, name, descriptor),
                                            ReviewedMethodShape.read(rewritten, owner, name, descriptor),
                                            version + ":" + owner + "." + name);
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_DEBUG);
    }

    private static byte[] injectNop(final byte[] original, final String targetMethod, final String targetDesc) {
        final ClassReader reader = new ClassReader(original);
        final org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        if (!targetMethod.equals(name) || !targetDesc.equals(desc)) return mv;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitInsn(Opcodes.NOP);
                            }
                        };
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static final class MultiOverrideLoader extends URLClassLoader {
        private final Map<String, byte[]> overrides;
        private final ProtectionDomain domain;

        MultiOverrideLoader(final URL[] urls, final Map<String, byte[]> overrides, final ProtectionDomain domain) {
            super(urls, ClassLoader.getPlatformClassLoader());
            this.overrides = overrides;
            this.domain = domain;
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final byte[] bytes = overrides.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length, domain);
            }
            return super.findClass(name);
        }
    }

    private static void assertRawFloatEquals(final float expected, final float actual, final String message) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), message);
    }

    private static void rawEquals(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]), "raw element " + i);
    }
}
