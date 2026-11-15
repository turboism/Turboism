package dev.turboism.adapter.cubism.optimization.hittest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies bytecode rewriting across reviewed Cubism artifacts, fail-closed guards, zero
 * allocation on the fast path, and exact behavioral/IEEE-754 parity.
 */
class MeshHitTestArtifactTest {

    private static final String REF_DIR_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_DIR_ENV = "TURBOISM_CUBISM_REF";
    private static final String CUBISM_JAR = "Live2D_Cubism.jar";
    private static final String JAR_EXTENSION = ".jar";
    private static final String CLASS_EXTENSION = ".class";
    private static final String OWNER_DOT_NAME = MeshHitTestTarget.OWNER.replace('/', '.');
    private static final String GMESH2_DOT_NAME = MeshHitTestTarget.GMESH2_OWNER.replace('/', '.');
    private static final String GMESH3_DOT_NAME = MeshHitTestTarget.GMESH3_OWNER.replace('/', '.');
    private static final String VECTOR2_DOT_NAME = MeshHitTestTarget.VECTOR2_OWNER.replace('/', '.');
    private static final String PREFERRED_PARITY_VERSION = "5.3.04";
    private static final String SET_POSITIONS_METHOD = "setPositions";
    private static final String SET_INDICES_METHOD = "setIndices";
    private static final String LIVE2D_PACKAGE_PREFIX = "com.live2d.";
    private static final String NOIDS_PACKAGE_PREFIX = "jp.";
    private static final String GL_BUFFERS_DOT_NAME = "com.jogamp.opengl.util.GLBuffers";
    private static final String GL_BUFFERS_INTERNAL_NAME = "com/jogamp/opengl/util/GLBuffers";
    private static final String GL_BUFFERS_NEW_INT_METHOD = "newDirectIntBuffer";
    private static final String GL_BUFFERS_NEW_INT_DESC = "(I)Ljava/nio/IntBuffer;";
    private static final String BYTE_BUFFER_OWNER = "java/nio/ByteBuffer";
    private static final String BYTE_ORDER_OWNER = "java/nio/ByteOrder";
    private static final String JAVA_OBJECT_OWNER = "java/lang/Object";
    private static final String ALLOCATE_DIRECT_METHOD = "allocateDirect";
    private static final String ALLOCATE_DIRECT_DESC = "(I)Ljava/nio/ByteBuffer;";
    private static final String NATIVE_ORDER_METHOD = "nativeOrder";
    private static final String NATIVE_ORDER_DESC = "()Ljava/nio/ByteOrder;";
    private static final String ORDER_METHOD = "order";
    private static final String ORDER_DESC = "(Ljava/nio/ByteOrder;)Ljava/nio/ByteBuffer;";
    private static final String AS_INT_BUFFER_METHOD = "asIntBuffer";
    private static final String AS_INT_BUFFER_DESC = "()Ljava/nio/IntBuffer;";
    private static final int INT_BYTES = 4;
    private static final int MAX_WALK_DEPTH = 2;
    private static final int TRIANGLE_INDICES = 3;
    private static final int RANDOM_TRIALS = 24;
    private static final int RANDOM_VERTICES = 12;
    private static final int RANDOM_QUERIES = 16;
    private static final int WARMUP_ITERS = 200;
    private static final int MEASURE_ITERS = 100;
    private static final long RANDOM_SEED = 0x4D45534848495454L;
    private static final float BOUNDARY_HALF_MARGIN = 0.5E-6f;
    private static final float BOUNDARY_DOUBLE_MARGIN = 2.0E-6f;

    @Test
    void everyConfiguredReviewedArtifactRewritesMeshHitTest() throws Exception {
        final Path root = referenceRoot();
        int rewritten = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = root.resolve(version).resolve(CUBISM_JAR);
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final MeshHitTestTarget target = MeshHitTestTarget.of(digest)
                    .orElseThrow(() -> new AssertionError("reviewed artifact refused: " + version));
            try (URLClassLoader loader = hostLoader(artifact.getParent())) {
                final byte[] original = readClassBytes(artifact, MeshHitTestTarget.OWNER);
                final MeshHitTestTransformer transformer = createTransformer(loader, artifact, original, target);
                final byte[] rewrittenBytes = transformer.transform(
                        null,
                        loader,
                        MeshHitTestTarget.OWNER,
                        null,
                        protectionDomain(artifact),
                        original);
                assertNotNull(rewrittenBytes, version + ": " + transformer.failure());
                assertEquals(1, transformer.matches(), version);
                assertEquals(MeshHitTestTransformer.EXPECTED_SITES, transformer.sites(), version);
                assertNull(transformer.failure(), version);

                // Verify fail-closed refusal for wrong loader, wrong domain, and altered shape.
                try (URLClassLoader otherLoader = hostLoader(artifact.getParent())) {
                    assertNull(transformer.transform(
                            null,
                            otherLoader,
                            MeshHitTestTarget.OWNER,
                            null,
                            protectionDomain(artifact),
                            original));
                }
                assertNull(transformer.transform(
                        null, loader, MeshHitTestTarget.OWNER, null, null, original));
                assertNull(transformer.transform(
                        null,
                        loader,
                        MeshHitTestTarget.OWNER,
                        null,
                        protectionDomain(artifact),
                        injectNopInContains(original)));
            }
            rewritten++;
        }
        assertTrue(rewritten > 0, "no reviewed exact artifact found under " + root);
    }

    @Test
    void rewrittenHostMethodMatchesOriginalAcrossGMesh2GMesh3AndDegenerateCases() throws Throwable {
        final Path root = referenceRoot();
        final Path artifact = resolveParityArtifact(root);
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        final MeshHitTestTarget target = MeshHitTestTarget.of(digest)
                .orElseThrow(() -> new AssertionError("parity artifact refused: " + artifact));
        final byte[] originalBytes = readClassBytes(artifact, MeshHitTestTarget.OWNER);

        try (HostClassLoader originalLoader = hostLoader(artifact.getParent());
                HostClassLoader rewrittenLoader = hostLoader(artifact.getParent())) {
            final MeshHitTestTransformer transformer =
                    createTransformer(rewrittenLoader, artifact, originalBytes, target);
            final byte[] rewrittenBytes = transformer.transform(
                    null,
                    rewrittenLoader,
                    MeshHitTestTarget.OWNER,
                    null,
                    protectionDomain(artifact),
                    originalBytes);
            assertNotNull(rewrittenBytes, transformer.failure());
            final ProtectionDomain signedDomain =
                    Class.forName(OWNER_DOT_NAME, false, originalLoader).getProtectionDomain();
            rewrittenLoader.installOverride(OWNER_DOT_NAME, rewrittenBytes, signedDomain);

            final MeshHarness orig = new MeshHarness(originalLoader);
            final MeshHarness rewr = new MeshHarness(rewrittenLoader);

            verifyDeterministicParity(orig, rewr, false);
            verifyDeterministicParity(orig, rewr, true);
            verifyRandomizedParity(orig, rewr);
            verifyZeroAllocationOutsideBounds(rewr);
        }
    }

    private static void verifyDeterministicParity(
            final MeshHarness orig, final MeshHarness rewr, final boolean mesh3) throws Exception {
        final float[] positions = mesh3
                ? new float[] {0.0f, 0.0f, 5.0f, 4.0f, 0.0f, -2.0f, 2.0f, 3.0f, 1.0f}
                : new float[] {0.0f, 0.0f, 4.0f, 0.0f, 2.0f, 3.0f};
        final int[] indices = {0, 1, 2};
        final float[][] queryPoints = {
            {2.0f, 1.0f},
            {0.0f, 0.0f},
            {4.0f, 0.0f},
            {-BOUNDARY_HALF_MARGIN, 0.0f},
            {-BOUNDARY_DOUBLE_MARGIN, 0.0f},
            {4.0f + BOUNDARY_HALF_MARGIN, 0.0f},
            {4.0f + BOUNDARY_DOUBLE_MARGIN, 0.0f},
            {2.0f, -BOUNDARY_DOUBLE_MARGIN},
            {2.0f, 3.0f + BOUNDARY_DOUBLE_MARGIN},
            {-0.0f, -0.0f},
            {Float.NaN, 1.0f},
            {1.0f, Float.NaN},
            {Float.POSITIVE_INFINITY, 1.0f},
            {Float.NEGATIVE_INFINITY, 1.0f},
            {10.0f, 10.0f}
        };
        assertMeshParity(orig, rewr, mesh3, positions, indices, queryPoints);

        // Empty and unaligned position arrays.
        assertMeshParity(orig, rewr, mesh3, new float[0], new int[0], queryPoints);
        assertMeshParity(orig, rewr, mesh3, new float[] {1.0f, 2.0f, 3.0f, 4.0f, 5.0f}, new int[0], queryPoints);

        // NaN and Infinity vertices inside positions.
        final float[] degeneratePositions = mesh3
                ? new float[] {Float.NaN, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 2.0f, 0.0f, 0.0f, 0.0f, 2.0f, 0.0f}
                : new float[] {Float.NaN, 0.0f, 1.0f, 1.0f, 2.0f, 0.0f, 0.0f, 2.0f};
        assertMeshParity(orig, rewr, mesh3, degeneratePositions, new int[] {1, 2, 3}, queryPoints);

        // Null point preserves Kotlin Intrinsics NullPointerException.
        final Object origMesh = orig.createMesh(mesh3, positions, indices);
        final Object rewrMesh = rewr.createMesh(mesh3, positions, indices);
        assertThrows(NullPointerException.class, () -> orig.contains(origMesh, null, null));
        assertThrows(NullPointerException.class, () -> rewr.contains(rewrMesh, null, null));
    }

    private static void verifyRandomizedParity(final MeshHarness orig, final MeshHarness rewr) throws Exception {
        final Random rng = new Random(RANDOM_SEED);
        for (int trial = 0; trial < RANDOM_TRIALS; trial++) {
            final boolean mesh3 = rng.nextBoolean();
            final int stride = mesh3 ? 3 : 2;
            final float[] positions = new float[RANDOM_VERTICES * stride];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = (rng.nextFloat() - 0.5f) * 20.0f;
            }
            final int triCount = RANDOM_VERTICES / TRIANGLE_INDICES;
            final int[] indices = new int[triCount * TRIANGLE_INDICES];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = rng.nextInt(RANDOM_VERTICES);
            }
            final float[][] queries = new float[RANDOM_QUERIES][2];
            for (int q = 0; q < RANDOM_QUERIES; q++) {
                queries[q][0] = (rng.nextFloat() - 0.5f) * 30.0f;
                queries[q][1] = (rng.nextFloat() - 0.5f) * 30.0f;
            }
            assertMeshParity(orig, rewr, mesh3, positions, indices, queries);
        }
    }

    private static void assertMeshParity(
            final MeshHarness orig,
            final MeshHarness rewr,
            final boolean mesh3,
            final float[] positions,
            final int[] indices,
            final float[][] queryPoints)
            throws Exception {
        final Object origMesh = orig.createMesh(mesh3, positions, indices);
        final Object rewrMesh = rewr.createMesh(mesh3, positions, indices);
        for (final float[] xy : queryPoints) {
            final Object origPoint = orig.createPoint(xy[0], xy[1]);
            final Object rewrPoint = rewr.createPoint(xy[0], xy[1]);
            assertEquals(
                    orig.contains(origMesh, origPoint, null),
                    rewr.contains(rewrMesh, rewrPoint, null),
                    "contains mismatch at (" + xy[0] + ", " + xy[1] + ") mesh3=" + mesh3);

            final int[] origOut = new int[TRIANGLE_INDICES];
            final int[] rewrOut = new int[TRIANGLE_INDICES];
            assertEquals(
                    orig.contains(origMesh, origPoint, origOut),
                    rewr.contains(rewrMesh, rewrPoint, rewrOut),
                    "contains(out) mismatch at (" + xy[0] + ", " + xy[1] + ") mesh3=" + mesh3);
            assertArrayEquals(origOut, rewrOut);
        }
    }

    private static void verifyZeroAllocationOutsideBounds(final MeshHarness rewr) throws Exception {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean threadMx)
                || !threadMx.isThreadAllocatedMemorySupported()) {
            return;
        }
        final Object mesh = rewr.createMesh(
                false, new float[] {0.0f, 0.0f, 4.0f, 0.0f, 2.0f, 3.0f}, new int[] {0, 1, 2});
        final Object outside = rewr.createPoint(10.0f, 10.0f);
        for (int i = 0; i < WARMUP_ITERS; i++) {
            rewr.contains(mesh, outside, null);
        }
        final long threadId = Thread.currentThread().getId();
        final long before = threadMx.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < MEASURE_ITERS; i++) {
            rewr.contains(mesh, outside, null);
        }
        final long after = threadMx.getThreadAllocatedBytes(threadId);
        assertEquals(0L, after - before, "rewritten bounds miss must allocate zero bytes");
    }

    private static MeshHitTestTransformer createTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] meshBaseBytes,
            final MeshHitTestTarget target)
            throws Exception {
        final byte[] gmesh2Bytes = readClassBytes(artifact, MeshHitTestTarget.GMESH2_OWNER);
        final byte[] gmesh3Bytes = readClassBytes(artifact, MeshHitTestTarget.GMESH3_OWNER);
        final byte[] boundsBytes = readClassBytes(artifact, MeshHitTestTarget.BOUNDS_OWNER);
        final String helperOwner = MeshHitTestTransformer.resolveBoundsHelperOwner(boundsBytes);
        final byte[] helperBytes = readClassBytes(artifact, helperOwner);
        return new MeshHitTestTransformer(
                loader, artifact, meshBaseBytes, gmesh2Bytes, gmesh3Bytes, boundsBytes, helperBytes, target);
    }

    private static byte[] injectNopInContains(final byte[] original) {
        final ClassReader reader = new ClassReader(original);
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!MeshHitTestTarget.CONTAINS_METHOD.equals(name)
                                || !MeshHitTestTarget.CONTAINS_DESCRIPTOR.equals(descriptor)) {
                            return mv;
                        }
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

    private static Path resolveParityArtifact(final Path root) {
        final Path preferred = root.resolve(PREFERRED_PARITY_VERSION).resolve(CUBISM_JAR);
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path candidate = root.resolve(version).resolve(CUBISM_JAR);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("no reviewed exact artifact found under " + root);
    }

    private static HostClassLoader hostLoader(final Path directory) throws Exception {
        return new HostClassLoader(urlsUnder(directory), MeshHitTestArtifactTest.class.getClassLoader());
    }

    private static URL[] urlsUnder(final Path directory) throws Exception {
        final List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(directory, MAX_WALK_DEPTH)) {
            for (final Path path : files.filter(p -> p.toString().endsWith(JAR_EXTENSION)).toList()) {
                urls.add(path.toUri().toURL());
            }
        }
        return urls.toArray(URL[]::new);
    }

    private static byte[] readClassBytes(final Path artifact, final String owner) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final var entry = jar.getJarEntry(owner + CLASS_EXTENSION);
            assertNotNull(entry, owner + " absent from " + artifact);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static ProtectionDomain protectionDomain(final Path artifact) throws Exception {
        return new ProtectionDomain(new CodeSource(artifact.toUri().toURL(), (Certificate[]) null), null);
    }

    private static Path referenceRoot() {
        final String configured = System.getProperty(
                REF_DIR_PROPERTY, System.getenv().getOrDefault(REF_DIR_ENV, ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }

    private static final class MeshHarness {
        private final Class<?> gmesh2Class;
        private final Class<?> gmesh3Class;
        private final Constructor<?> vector2Ctor;
        private final MethodHandle containsHandle;
        private final Method setPositions2;
        private final Method setIndices2;
        private final Method setPositions3;
        private final Method setIndices3;

        private MeshHarness(final ClassLoader loader) throws Exception {
            final Class<?> baseClass = Class.forName(OWNER_DOT_NAME, true, loader);
            this.gmesh2Class = Class.forName(GMESH2_DOT_NAME, true, loader);
            this.gmesh3Class = Class.forName(GMESH3_DOT_NAME, true, loader);
            final Class<?> vector2Class = Class.forName(VECTOR2_DOT_NAME, true, loader);
            this.vector2Ctor = vector2Class.getConstructor(float.class, float.class);
            final Method containsMethod =
                    baseClass.getMethod(MeshHitTestTarget.CONTAINS_METHOD, vector2Class, int[].class);
            this.containsHandle = MethodHandles.lookup()
                    .unreflect(containsMethod)
                    .asType(MethodType.methodType(boolean.class, Object.class, Object.class, int[].class));
            this.setPositions2 = gmesh2Class.getMethod(SET_POSITIONS_METHOD, float[].class);
            this.setIndices2 = gmesh2Class.getMethod(SET_INDICES_METHOD, int[].class);
            this.setPositions3 = gmesh3Class.getMethod(SET_POSITIONS_METHOD, float[].class);
            this.setIndices3 = gmesh3Class.getMethod(SET_INDICES_METHOD, int[].class);
        }

        private Object createMesh(final boolean mesh3, final float[] positions, final int[] indices)
                throws Exception {
            final Object mesh = (mesh3 ? gmesh3Class : gmesh2Class).getConstructor().newInstance();
            (mesh3 ? setPositions3 : setPositions2).invoke(mesh, positions);
            (mesh3 ? setIndices3 : setIndices2).invoke(mesh, indices);
            return mesh;
        }

        private Object createPoint(final float x, final float y) throws Exception {
            return vector2Ctor.newInstance(x, y);
        }

        private boolean contains(final Object mesh, final Object point, final int[] outIndices) throws Exception {
            try {
                return (boolean) containsHandle.invokeExact(mesh, point, outIndices);
            } catch (final RuntimeException | Error unchecked) {
                throw unchecked;
            } catch (final Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    private static final class HostClassLoader extends URLClassLoader {
        private String overrideName;
        private byte[] overrideBytes;
        private ProtectionDomain overrideDomain;

        private HostClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        private void installOverride(final String name, final byte[] bytes, final ProtectionDomain domain) {
            this.overrideName = name;
            this.overrideBytes = bytes;
            this.overrideDomain = domain;
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(LIVE2D_PACKAGE_PREFIX) || name.startsWith(NOIDS_PACKAGE_PREFIX)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            if (name.equals(overrideName) && overrideBytes != null) {
                final byte[] bytes = overrideBytes;
                overrideBytes = null;
                return defineClass(name, bytes, 0, bytes.length, overrideDomain);
            }
            if (GL_BUFFERS_DOT_NAME.equals(name)) {
                final byte[] stub = buildGlBuffersStub();
                return defineClass(name, stub, 0, stub.length);
            }
            return super.findClass(name);
        }

        private static byte[] buildGlBuffersStub() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(
                    Opcodes.V17,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                    GL_BUFFERS_INTERNAL_NAME,
                    null,
                    JAVA_OBJECT_OWNER,
                    null);
            final MethodVisitor mv = cw.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    GL_BUFFERS_NEW_INT_METHOD,
                    GL_BUFFERS_NEW_INT_DESC,
                    null,
                    null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitIntInsn(Opcodes.BIPUSH, INT_BYTES);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    BYTE_BUFFER_OWNER,
                    ALLOCATE_DIRECT_METHOD,
                    ALLOCATE_DIRECT_DESC,
                    false);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, BYTE_ORDER_OWNER, NATIVE_ORDER_METHOD, NATIVE_ORDER_DESC, false);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, BYTE_BUFFER_OWNER, ORDER_METHOD, ORDER_DESC, false);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    BYTE_BUFFER_OWNER,
                    AS_INT_BUFFER_METHOD,
                    AS_INT_BUFFER_DESC,
                    false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }
    }
}
