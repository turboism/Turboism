package dev.turboism.adapter.cubism.optimization.warpgrid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies bytecode rewriting across reviewed artifacts and bit-exact IEEE-754 parity.
 */
class WarpGridInterpolationArtifactTest {

    private static final String REF_DIR_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_DIR_ENV = "TURBOISM_CUBISM_REF";
    private static final String CUBISM_JAR = "Live2D_Cubism.jar";
    private static final String JAR_EXTENSION = ".jar";
    private static final String CLASS_EXTENSION = ".class";
    private static final String OWNER_DOT_NAME = WarpGridInterpolationTarget.OWNER.replace('/', '.');
    private static final String PREFERRED_PARITY_VERSION = "5.3.04";
    private static final String STAT_OOB_TAIL_CALLS = "oobTailCalls";
    private static final String STAT_OOB_POINTS = "oobPoints";
    private static final String STAT_IN_BOUNDS_FALLBACK_POINTS = "inBoundsFallbackPoints";
    private static final String STAT_FAILURES = "failures";
    private static final String STAT_BOUND = "bound";
    private static final String PROPERTIES_INTERNAL = "java/util/Properties";
    private static final String PROPERTIES_GET_METHOD = "get";
    private static final String METHOD_HANDLE_INTERNAL = "java/lang/invoke/MethodHandle";
    private static final String INVOKE_EXACT_METHOD = "invokeExact";
    private static final String CONSUMER_INTERNAL = "java/util/function/Consumer";
    private static final String ATOMIC_BOOLEAN_INTERNAL = "java/util/concurrent/atomic/AtomicBoolean";
    private static final String INTEGER_INTERNAL = "java/lang/Integer";
    private static final String BOOLEAN_INTERNAL = "java/lang/Boolean";
    private static final String VALUE_OF_METHOD = "valueOf";
    private static final long RANDOM_SEED = 0x5741525047524944L;
    private static final int RANDOM_TRIALS = 24;
    private static final int MAX_WALK_DEPTH = 2;
    private static final int COORD_STRIDE = 2;

    @Test
    void everyConfiguredReviewedArtifactRewritesWarpGridInterpolation() throws Exception {
        final Path root = referenceRoot();
        int rewritten = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = root.resolve(version).resolve(CUBISM_JAR);
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final WarpGridInterpolationTarget target = WarpGridInterpolationTarget.of(digest)
                    .orElseThrow(() -> new AssertionError("reviewed artifact refused: " + version));
            try (URLClassLoader loader = hostLoader(artifact.getParent())) {
                final byte[] original = readClassBytes(artifact, WarpGridInterpolationTarget.OWNER);
                final WarpGridInterpolationTransformer transformer =
                        new WarpGridInterpolationTransformer(loader, artifact, original, target);
                final byte[] rewrittenBytes = transformer.transform(
                        null, loader, WarpGridInterpolationTarget.OWNER, null, protectionDomain(artifact), original);
                assertNotNull(rewrittenBytes, version + ": " + transformer.failure());
                assertEquals(1, transformer.matches(), version);
                assertNull(transformer.failure(), version);
                assertSingleLookupPrimitiveOobBytecode(original, rewrittenBytes, version);

                // Verify fail-closed refusal for wrong loader.
                try (URLClassLoader otherLoader = hostLoader(artifact.getParent())) {
                    assertNull(transformer.transform(
                            null,
                            otherLoader,
                            WarpGridInterpolationTarget.OWNER,
                            null,
                            protectionDomain(artifact),
                            original));
                }
            }
            rewritten++;
        }
        assertTrue(rewritten > 0, "no reviewed exact artifact found under " + root);
    }

    @Test
    void rewrittenHostMethodMatchesOriginalRawBitsAcrossInBoundsOutOfBoundsAndDegenerateCases() throws Throwable {
        final Path root = referenceRoot();
        final Path artifact = resolveParityArtifact(root);
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        final WarpGridInterpolationTarget target = WarpGridInterpolationTarget.of(digest)
                .orElseThrow(() -> new AssertionError("parity artifact refused: " + artifact));
        final byte[] originalBytes = readClassBytes(artifact, WarpGridInterpolationTarget.OWNER);

        try (URLClassLoader originalLoader = hostLoader(artifact.getParent());
                RewrittenHostClassLoader rewrittenLoader = new RewrittenHostClassLoader(
                        urlsUnder(artifact.getParent()), WarpGridInterpolationArtifactTest.class.getClassLoader())) {
            final WarpGridInterpolationTransformer transformer =
                    new WarpGridInterpolationTransformer(rewrittenLoader, artifact, originalBytes, target);
            final byte[] rewrittenBytes = transformer.transform(
                    null,
                    rewrittenLoader,
                    WarpGridInterpolationTarget.OWNER,
                    null,
                    protectionDomain(artifact),
                    originalBytes);
            assertNotNull(rewrittenBytes, transformer.failure());
            final ProtectionDomain signedDomain =
                    Class.forName(OWNER_DOT_NAME, false, originalLoader).getProtectionDomain();
            rewrittenLoader.installOverride(OWNER_DOT_NAME, rewrittenBytes, signedDomain);

            final Invocation originalInvoker = loadInvoker(originalLoader);
            final Invocation rewrittenInvoker = loadInvoker(rewrittenLoader);

            try (WarpGridInterpolationBridge bridge = WarpGridInterpolationBridge.open(rewrittenLoader)) {
                bridge.install();
                final MethodHandle publishedHandle = assertInstanceOf(
                        MethodHandle.class, System.getProperties().get(WarpGridInterpolationBridge.OOB_PROPERTY));
                assertEquals(
                        WarpGridInterpolationTarget.OOB_RUN_DESCRIPTOR,
                        publishedHandle.type().descriptorString());

                verifyMixedTailCountersAndParity(originalInvoker, rewrittenInvoker, bridge);
                verifyNullParity(originalInvoker, rewrittenInvoker);
                verifyDeterministicCases(originalInvoker, rewrittenInvoker);
                assertTrue(bridge.snapshot().get(STAT_OOB_TAIL_CALLS) > 0L, "OOB delegation was not exercised");
                verifyRandomizedCases(originalInvoker, rewrittenInvoker);
                bridge.close();
                assertNull(System.getProperties().get(WarpGridInterpolationBridge.OOB_PROPERTY));
                // Verify inactive fallback path still produces exact raw-bit equality.
                verifyNullParity(originalInvoker, rewrittenInvoker);
                verifyDeterministicCases(originalInvoker, rewrittenInvoker);
            }
        }
    }

    private static void verifyDeterministicCases(final Invocation original, final Invocation rewritten)
            throws Throwable {
        // Pure in-bounds fast path (quad and tri, zero and nonzero offsets/steps).
        final float[] regularGrid = buildGrid(3, 2, 10.0f, 15.0f, 20.0f, -12.0f, false);
        final float[] inBoundsPts = {
            0.0f, 0.0f,
            0.25f, 0.75f,
            0.5f, 0.5f,
            0.9999f, 0.0001f,
            0.1f, 0.2f,
            0.8f, 0.9f
        };
        assertBitParity(original, rewritten, inBoundsPts, 6, 0, COORD_STRIDE, regularGrid, 3, 2, true, false);
        assertBitParity(original, rewritten, inBoundsPts, 6, 0, COORD_STRIDE, regularGrid, 3, 2, false, false);
        assertBitParity(original, rewritten, inBoundsPts, 3, 2, 4, regularGrid, 3, 2, false, true);

        // Edge cases covering every border triangle band, far OOB, NaN, -0.0f, Inf, and nextDown(1.0f).
        final float[] edgePts = {
            -0.0f,
            -0.0f,
            1.0f,
            1.0f,
            Math.nextDown(1.0f),
            0.5f,
            -0.5f,
            -0.5f,
            -0.5f,
            1.5f,
            -0.5f,
            0.4f,
            1.5f,
            -0.5f,
            1.5f,
            1.5f,
            1.5f,
            0.6f,
            0.4f,
            -0.5f,
            0.6f,
            1.5f,
            -2.0f,
            0.5f,
            3.0f,
            0.5f,
            -3.5f,
            4.25f,
            Float.NaN,
            0.25f,
            0.25f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            0.5f,
            0.0f,
            Math.nextDown(1.0f)
        };
        final int count = edgePts.length / COORD_STRIDE;
        assertBitParity(original, rewritten, edgePts, count, 0, COORD_STRIDE, regularGrid, 3, 2, true, false);
        assertBitParity(original, rewritten, edgePts, count, 0, COORD_STRIDE, regularGrid, 3, 2, false, false);

        // Degenerate 1x1 collapsed grid and empty/inverted iteration bounds.
        final float[] collapsedGrid = buildGrid(1, 1, 0.0f, 0.0f, 0.0f, 0.0f, true);
        assertBitParity(original, rewritten, edgePts, count, 0, COORD_STRIDE, collapsedGrid, 1, 1, true, false);
        assertBitParity(original, rewritten, edgePts, count, 0, COORD_STRIDE, collapsedGrid, 1, 1, false, false);
        assertBitParity(original, rewritten, inBoundsPts, 0, 0, COORD_STRIDE, regularGrid, 3, 2, true, false);
    }

    private static void verifyRandomizedCases(final Invocation original, final Invocation rewritten) throws Throwable {
        final Random rng = new Random(RANDOM_SEED);
        for (int trial = 0; trial < RANDOM_TRIALS; trial++) {
            final int col = 1 + rng.nextInt(5);
            final int row = 1 + rng.nextInt(5);
            final boolean isQuad = rng.nextBoolean();
            final boolean collapsed = trial == 0;
            final float[] grid = buildGrid(
                    col,
                    row,
                    (rng.nextFloat() - 0.5f) * 200.0f,
                    (rng.nextFloat() - 0.5f) * 200.0f,
                    (rng.nextFloat() - 0.5f) * 100.0f,
                    (rng.nextFloat() - 0.5f) * 100.0f,
                    collapsed);
            final int step = COORD_STRIDE + rng.nextInt(3);
            final int numPoint = 40;
            final int offset = rng.nextInt(3);
            final float[] src = new float[numPoint * step + 4];
            for (int p = offset; p < numPoint * step; p += step) {
                src[p] = (rng.nextFloat() * 6.0f) - 2.5f;
                src[p + 1] = (rng.nextFloat() * 6.0f) - 2.5f;
            }
            assertBitParity(original, rewritten, src, numPoint, offset, step, grid, col, row, isQuad, trial % 4 == 0);
        }
    }

    private static void assertBitParity(
            final Invocation original,
            final Invocation rewritten,
            final float[] srcTemplate,
            final int numPoint,
            final int srcOffset,
            final int srcStep,
            final float[] grid,
            final int col,
            final int row,
            final boolean isQuad,
            final boolean aliasInPlace)
            throws Throwable {
        final float[] origSrc = srcTemplate.clone();
        final float[] rewrSrc = srcTemplate.clone();
        final float[] origDst = aliasInPlace ? origSrc : new float[srcTemplate.length];
        final float[] rewrDst = aliasInPlace ? rewrSrc : new float[srcTemplate.length];

        original.invoke(origSrc, origDst, numPoint, srcOffset, srcStep, grid, col, row, isQuad);
        rewritten.invoke(rewrSrc, rewrDst, numPoint, srcOffset, srcStep, grid, col, row, isQuad);

        assertEquals(origDst.length, rewrDst.length);
        for (int idx = 0; idx < origDst.length; idx++) {
            assertEquals(
                    Float.floatToRawIntBits(origDst[idx]),
                    Float.floatToRawIntBits(rewrDst[idx]),
                    "raw bit mismatch at index=" + idx + " isQuad=" + isQuad + " col=" + col + " row=" + row);
        }
    }

    private static float[] buildGrid(
            final int col,
            final int row,
            final float ox,
            final float oy,
            final float sx,
            final float sy,
            final boolean collapsed) {
        final float[] pt = new float[(col + 1) * (row + 1) * COORD_STRIDE];
        if (collapsed) {
            return pt;
        }
        for (int r = 0; r <= row; r++) {
            for (int c = 0; c <= col; c++) {
                final int idx = (c + r * (col + 1)) * COORD_STRIDE;
                pt[idx] = ox + sx * ((float) c / col) + 0.125f * r;
                pt[idx + 1] = oy + sy * ((float) r / row) - 0.125f * c;
            }
        }
        return pt;
    }

    private static Invocation loadInvoker(final ClassLoader loader) throws Exception {
        final Class<?> owner = Class.forName(OWNER_DOT_NAME, true, loader);
        final Field singletonField = owner.getField(WarpGridInterpolationTarget.SINGLETON_FIELD);
        final Object singleton = singletonField.get(null);
        final var method = owner.getMethod(
                WarpGridInterpolationTarget.METHOD,
                float[].class,
                float[].class,
                int.class,
                int.class,
                int.class,
                float[].class,
                int.class,
                int.class,
                boolean.class);
        final MethodHandle handle =
                java.lang.invoke.MethodHandles.lookup().unreflect(method).bindTo(singleton);
        return handle::invokeExact;
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

    private static URLClassLoader hostLoader(final Path directory) throws Exception {
        return new URLClassLoader(urlsUnder(directory), WarpGridInterpolationArtifactTest.class.getClassLoader());
    }

    private static URL[] urlsUnder(final Path directory) throws Exception {
        final List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(directory, MAX_WALK_DEPTH)) {
            for (final Path path :
                    files.filter(p -> p.toString().endsWith(JAR_EXTENSION)).toList()) {
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

    private static void verifyMixedTailCountersAndParity(
            final Invocation original, final Invocation rewritten, final WarpGridInterpolationBridge bridge)
            throws Throwable {
        final float[] grid = buildGrid(2, 2, 5.0f, -3.0f, 12.0f, 8.0f, false);
        final float[] mixed = {0.25f, 0.25f, 1.0f, 0.5f, 0.5f, 0.75f, -0.5f, 1.5f};
        final int count = mixed.length / COORD_STRIDE;

        assertBitParity(original, rewritten, mixed, count, 0, COORD_STRIDE, grid, 2, 2, true, false);
        final Map<String, Long> afterQuad = bridge.snapshot();
        assertEquals(2L, afterQuad.get(STAT_OOB_TAIL_CALLS));
        assertEquals(2L, afterQuad.get(STAT_OOB_POINTS));
        assertEquals(0L, afterQuad.get(STAT_IN_BOUNDS_FALLBACK_POINTS));
        assertEquals(0L, afterQuad.get(STAT_FAILURES));
        assertEquals(1L, afterQuad.get(STAT_BOUND));

        assertBitParity(original, rewritten, mixed, count, 0, COORD_STRIDE, grid, 2, 2, false, false);
        final Map<String, Long> afterTri = bridge.snapshot();
        assertEquals(4L, afterTri.get(STAT_OOB_TAIL_CALLS));
        assertEquals(4L, afterTri.get(STAT_OOB_POINTS));
        assertEquals(0L, afterTri.get(STAT_IN_BOUNDS_FALLBACK_POINTS));
        assertEquals(0L, afterTri.get(STAT_FAILURES));
        assertEquals(1L, afterTri.get(STAT_BOUND));
    }

    private static void verifyNullParity(final Invocation original, final Invocation rewritten) {
        final float[] coords = {0.5f, 0.5f};
        final float[] grid = buildGrid(1, 1, 0.0f, 0.0f, 1.0f, 1.0f, false);
        assertThrows(
                NullPointerException.class,
                () -> original.invoke(null, coords.clone(), 1, 0, COORD_STRIDE, grid, 1, 1, true));
        assertThrows(
                NullPointerException.class,
                () -> rewritten.invoke(null, coords.clone(), 1, 0, COORD_STRIDE, grid, 1, 1, true));
        assertThrows(
                NullPointerException.class,
                () -> rewritten.invoke(coords.clone(), null, 0, 0, COORD_STRIDE, grid, 1, 1, true));
        assertThrows(
                NullPointerException.class,
                () -> rewritten.invoke(coords.clone(), coords.clone(), 1, 0, COORD_STRIDE, null, 1, 1, false));
    }

    private static void assertSingleLookupPrimitiveOobBytecode(
            final byte[] originalBytes, final byte[] rewrittenBytes, final String version) {
        final BytecodeProfile original = BytecodeProfile.scan(originalBytes);
        final BytecodeProfile rewritten = BytecodeProfile.scan(rewrittenBytes);
        assertEquals(1, rewritten.propertyLookups, version + ": expected single Properties.get lookup");
        assertEquals(1, rewritten.invokeExactCalls, version + ": expected single MethodHandle.invokeExact");
        assertEquals(0, rewritten.consumerCalls, version + ": unexpected Consumer call");
        assertEquals(0, rewritten.atomicBooleanCalls, version + ": unexpected AtomicBoolean call");
        assertEquals(0, rewritten.boxingCalls, version + ": unexpected Integer/Boolean boxing");
        assertEquals(original.objectArrayAllocs, rewritten.objectArrayAllocs, version + ": added Object[] allocation");
    }

    private static final class BytecodeProfile extends ClassVisitor {
        private int propertyLookups;
        private int invokeExactCalls;
        private int consumerCalls;
        private int atomicBooleanCalls;
        private int boxingCalls;
        private int objectArrayAllocs;

        private BytecodeProfile() {
            super(Opcodes.ASM9);
        }

        private static BytecodeProfile scan(final byte[] classBytes) {
            final BytecodeProfile profile = new BytecodeProfile();
            new ClassReader(classBytes).accept(profile, 0);
            return profile;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions) {
            if (!WarpGridInterpolationTarget.METHOD.equals(name)
                    || !WarpGridInterpolationTarget.DESCRIPTOR.equals(descriptor)) {
                return null;
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String methodName,
                        final String methodDesc,
                        final boolean isInterface) {
                    if (PROPERTIES_INTERNAL.equals(owner) && PROPERTIES_GET_METHOD.equals(methodName)) {
                        propertyLookups++;
                    } else if (METHOD_HANDLE_INTERNAL.equals(owner)
                            && INVOKE_EXACT_METHOD.equals(methodName)
                            && WarpGridInterpolationTarget.OOB_RUN_DESCRIPTOR.equals(methodDesc)) {
                        invokeExactCalls++;
                    } else if (CONSUMER_INTERNAL.equals(owner)) {
                        consumerCalls++;
                    } else if (ATOMIC_BOOLEAN_INTERNAL.equals(owner)) {
                        atomicBooleanCalls++;
                    } else if ((INTEGER_INTERNAL.equals(owner) || BOOLEAN_INTERNAL.equals(owner))
                            && VALUE_OF_METHOD.equals(methodName)) {
                        boxingCalls++;
                    }
                }

                @Override
                public void visitTypeInsn(final int opcode, final String type) {
                    if (opcode == Opcodes.ANEWARRAY) {
                        objectArrayAllocs++;
                    }
                }
            };
        }
    }

    private static Path referenceRoot() {
        final String configured =
                System.getProperty(REF_DIR_PROPERTY, System.getenv().getOrDefault(REF_DIR_ENV, ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }

    @FunctionalInterface
    private interface Invocation {
        void invoke(
                float[] src,
                float[] dst,
                int numPoint,
                int srcOffset,
                int srcStep,
                float[] pt,
                int col,
                int row,
                boolean isQuad)
                throws Throwable;
    }

    private static final class RewrittenHostClassLoader extends URLClassLoader {
        private String overrideName;
        private byte[] overrideBytes;
        private ProtectionDomain overrideDomain;

        private RewrittenHostClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        private void installOverride(final String name, final byte[] bytes, final ProtectionDomain domain) {
            this.overrideName = name;
            this.overrideBytes = bytes;
            this.overrideDomain = domain;
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            if (name.equals(overrideName) && overrideBytes != null) {
                final byte[] bytes = overrideBytes;
                overrideBytes = null;
                return defineClass(name, bytes, 0, bytes.length, overrideDomain);
            }
            return super.findClass(name);
        }
    }
}
