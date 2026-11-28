package dev.turboism.adapter.cubism.optimization.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.ThreadMXBean;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies bytecode rewriting across reviewed Cubism releases and bit-exact IEEE-754 parity for
 * physics simulation loops.
 */
class PhysicsAccelerationArtifactTest {

    private static final String REF_DIR_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_DIR_ENV = "TURBOISM_CUBISM_REF";
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String ARCH_REFERENCE_SUFFIX = ".local/share/cubism-reference";
    private static final String CUBISM_JAR = "Live2D_Cubism.jar";
    private static final String JAR_EXTENSION = ".jar";
    private static final String CLASS_EXTENSION = ".class";
    private static final String VERIFY_MANIFEST_FILE = "patched-physics-targets.txt";
    private static final String XVERIFY_ALL_FLAG = "-Xverify:all";
    private static final String JAVA_BIN = "java";
    private static final String CLASSPATH_OPTION = "-cp";
    private static final String JAVA_HOME_PROPERTY = "java.home";
    private static final String JAVA_CLASS_PATH_PROPERTY = "java.class.path";
    private static final String BIN_DIR = "bin";
    private static final String TAB = "\t";
    private static final int MAX_WALK_DEPTH = 10;
    private static final int VERIFY_TIMEOUT_SECONDS = 120;

    private static final int SIMULATION_TICKS = 120;
    private static final int WARMUP_STEPS = 12_000;
    private static final int MEASURED_STEPS = 500;
    private static final long RANDOM_SEED = 0x504859534943534CL;
    private static final float DELTA_TIME_60FPS = 1.0f / 60.0f;
    private static final float ZERO_DELTA_TIME = 0.0f;
    private static final float LARGE_DELTA_TIME = 0.25f;
    private static final float DEFAULT_PARAM_MIN = -30.0f;
    private static final float DEFAULT_PARAM_MAX = 30.0f;
    private static final float DEFAULT_PARAM_VALUE = 0.0f;
    private static final int PENDULUM_VERTEX_COUNT = 4;
    private static final int INPUT_COUNT = 4;
    private static final int OUTPUT_COUNT = 4;

    private static final String SOURCE_NORMAL_ENUM = "NORMAL";
    private static final String SRC_TO_X = "SRC_TO_X";
    private static final String SRC_TO_Y = "SRC_TO_Y";
    private static final String SRC_TO_G_ANGLE = "SRC_TO_G_ANGLE";

    @Test
    void everyConfiguredReviewedArtifactRewritesPhysicsEvaluator(@TempDir final Path tempDir) throws Exception {
        final Path root = referenceRoot();
        final Path extraLibsDir = findThirdPartyLibsDir(root);
        final Path manifestFile = tempDir.resolve(VERIFY_MANIFEST_FILE);
        final List<String> manifestLines = new ArrayList<>();
        int rewritten = 0;

        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = resolveVersionArtifact(root, version);
            if (artifact == null) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final PhysicsAccelerationTarget target = PhysicsAccelerationTarget.of(digest)
                    .orElseThrow(() -> new AssertionError("reviewed artifact refused: " + version));
            try (URLClassLoader loader = hostLoader(artifact.getParent(), extraLibsDir);
                    URLClassLoader wrongLoader = hostLoader(artifact.getParent(), extraLibsDir)) {
                final String evaluatorOwner = target.evaluatorOwner();
                final byte[] original = readClassBytes(artifact, evaluatorOwner);
                final PhysicsAccelerationTransformer transformer =
                        new PhysicsAccelerationTransformer(loader, artifact, original, target);
                final ProtectionDomain domain = protectionDomain(artifact);

                // Wrong loader must be declined without mutating counters or failure state.
                assertNull(transformer.transform(null, wrongLoader, evaluatorOwner, null, domain, original));

                final byte[] rewrittenBytes =
                        transformer.transform(null, loader, evaluatorOwner, null, domain, original);
                assertNotNull(rewrittenBytes, version + ": " + transformer.failure());
                assertNull(transformer.failure(), version);
                assertEquals(PhysicsAccelerationTarget.EXPECTED_MATCHES, transformer.matches(), version);
                assertEquals(PhysicsAccelerationTarget.EXPECTED_TOTAL_SITES, transformer.sites(), version);

                final Path versionDir = tempDir.resolve(version);
                writePatchedClass(versionDir, evaluatorOwner, rewrittenBytes);
                manifestLines.add(version + TAB + artifact + TAB + versionDir + TAB + evaluatorOwner);

                final ProtectionDomain signedDomain = Class.forName(evaluatorOwner.replace('/', '.'), false, loader)
                        .getProtectionDomain();
                try (RewrittenHostClassLoader verifyLoader = rewrittenLoader(artifact.getParent(), extraLibsDir)) {
                    verifyLoader.installOverride(evaluatorOwner.replace('/', '.'), rewrittenBytes, signedDomain);
                    final Class<?> evaluatorClass = Class.forName(evaluatorOwner.replace('/', '.'), true, verifyLoader);
                    assertNotNull(evaluatorClass.getDeclaredMethods());
                }
                rewritten++;
            }
        }

        assertEquals(ReviewedHostArtifacts.reviewedCubismVersions().size(), rewritten, "all reviewed JARs checked");
        Files.write(manifestFile, manifestLines);
        runStrictVerifyAllSubprocess(manifestFile, manifestLines.size());
    }

    @Test
    void activeGateCachesEnablementAndRefreshesOnConfigOrValidationChange() {
        final String prevValidation = System.getProperty(PhysicsAccelerationBridge.VALIDATION_PROPERTY);
        final String prevEnable = System.getProperty(PhysicsAccelerationBridge.ENABLE_PROPERTY);
        try (PhysicsAccelerationBridge bridge = new PhysicsAccelerationBridge()) {
            System.clearProperty(PhysicsAccelerationBridge.VALIDATION_PROPERTY);
            System.clearProperty(PhysicsAccelerationBridge.ENABLE_PROPERTY);
            PhysicsAccelerationBridge.setConfiguredDefault(true);
            bridge.install();

            @SuppressWarnings("unchecked")
            final java.util.function.Predicate<Object> gate = (java.util.function.Predicate<Object>)
                    System.getProperties().get(PhysicsAccelerationBridge.ACTIVE_PROPERTY);
            assertNotNull(gate);
            assertTrue(PhysicsAccelerationBridge.isEnabled());
            assertTrue(gate.test(null));

            // Steady-state reads stay cached without polling system properties per physics step.
            System.setProperty(PhysicsAccelerationBridge.ENABLE_PROPERTY, Boolean.FALSE.toString());
            assertTrue(PhysicsAccelerationBridge.isEnabled());
            assertTrue(gate.test(null));

            assertFalse(PhysicsAccelerationBridge.refreshEnabled());
            assertFalse(gate.test(null));

            System.clearProperty(PhysicsAccelerationBridge.ENABLE_PROPERTY);
            PhysicsAccelerationBridge.setConfiguredDefault(false);
            assertFalse(PhysicsAccelerationBridge.isEnabled());
            assertFalse(gate.test(null));

            System.setProperty(PhysicsAccelerationBridge.VALIDATION_PROPERTY, Boolean.TRUE.toString());
            assertTrue(PhysicsAccelerationBridge.refreshEnabled());
            assertTrue(gate.test(null));
        } finally {
            PhysicsAccelerationBridge.setConfiguredDefault(true);
            restoreProperty(PhysicsAccelerationBridge.VALIDATION_PROPERTY, prevValidation);
            restoreProperty(PhysicsAccelerationBridge.ENABLE_PROPERTY, prevEnable);
            PhysicsAccelerationBridge.refreshEnabled();
        }
    }

    private static void restoreProperty(final String key, final String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    @Test
    void physicsSimulationMatchesBitExactAcrossModernAndLegacyEvaluators() throws Throwable {
        final Path root = referenceRoot();
        final Path modernArtifact =
                findNewestArtifactForEvaluator(root, PhysicsAccelerationTarget.CURRENT_EVALUATOR_OWNER);
        final Path legacyArtifact =
                findNewestArtifactForEvaluator(root, PhysicsAccelerationTarget.LEGACY_EVALUATOR_OWNER);
        assertNotNull(modernArtifact, "modern evaluator artifact missing");
        final Path extraLibsDir = findThirdPartyLibsDir(root);
        verifyParityOnArtifact(modernArtifact, extraLibsDir);
        if (legacyArtifact != null) {
            verifyParityOnArtifact(legacyArtifact, extraLibsDir);
        }
    }

    private static void verifyParityOnArtifact(final Path artifact, final Path extraLibsDir) throws Throwable {
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        final PhysicsAccelerationTarget target = PhysicsAccelerationTarget.of(digest)
                .orElseThrow(() -> new AssertionError("missing target for " + artifact));
        final String evaluatorOwner = target.evaluatorOwner();
        final byte[] originalBytes = readClassBytes(artifact, evaluatorOwner);
        final ProtectionDomain domain = protectionDomain(artifact);

        final URL[] urls = hostClasspathUrls(artifact.getParent(), extraLibsDir);
        final ClassLoader parent = ClassLoader.getPlatformClassLoader();
        try (URLClassLoader originalLoader = new URLClassLoader(urls, parent);
                RewrittenHostClassLoader rewrittenLoader = new RewrittenHostClassLoader(urls, parent);
                PhysicsAccelerationBridge bridge = new PhysicsAccelerationBridge()) {
            final PhysicsAccelerationTransformer transformer =
                    new PhysicsAccelerationTransformer(rewrittenLoader, artifact, originalBytes, target);
            final byte[] rewrittenBytes =
                    transformer.transform(null, rewrittenLoader, evaluatorOwner, null, domain, originalBytes);
            assertNotNull(rewrittenBytes, target.version() + ": " + transformer.failure());
            final ProtectionDomain signedDomain = Class.forName(evaluatorOwner.replace('/', '.'), false, originalLoader)
                    .getProtectionDomain();
            rewrittenLoader.installOverride(evaluatorOwner.replace('/', '.'), rewrittenBytes, signedDomain);

            bridge.install();
            final String prevValidation = System.getProperty(PhysicsAccelerationBridge.VALIDATION_PROPERTY);
            System.setProperty(PhysicsAccelerationBridge.VALIDATION_PROPERTY, Boolean.TRUE.toString());
            PhysicsAccelerationBridge.refreshEnabled();
            try {
                runBitExactParitySuite(originalLoader, rewrittenLoader, evaluatorOwner, bridge, target.version());
            } finally {
                if (prevValidation == null) {
                    System.clearProperty(PhysicsAccelerationBridge.VALIDATION_PROPERTY);
                } else {
                    System.setProperty(PhysicsAccelerationBridge.VALIDATION_PROPERTY, prevValidation);
                }
                PhysicsAccelerationBridge.refreshEnabled();
            }
        }
    }

    private static void runBitExactParitySuite(
            final ClassLoader originalLoader,
            final ClassLoader rewrittenLoader,
            final String evaluatorOwner,
            final PhysicsAccelerationBridge bridge,
            final String version)
            throws Throwable {
        final PhysicsReflectionHost origHost = new PhysicsReflectionHost(originalLoader, evaluatorOwner);
        final PhysicsReflectionHost rewrHost = new PhysicsReflectionHost(rewrittenLoader, evaluatorOwner);

        final PhysicsFixture origFixture = origHost.buildFixture();
        final PhysicsFixture rewrFixture = rewrHost.buildFixture();

        // Verify findParameter (hit first, middle, last, absent, null).
        for (int i = 0; i < origFixture.paramGuids.size(); i++) {
            final UUID guid = origFixture.paramGuids.get(i);
            final Object origFound = origHost.findParameter(origFixture.paramSet, origHost.newGuid(guid));
            final Object rewrFound = rewrHost.findParameter(rewrFixture.paramSet, rewrHost.newGuid(guid));
            assertSame(origFixture.params.get(i), origFound, version + " orig findParameter#" + i);
            assertSame(rewrFixture.params.get(i), rewrFound, version + " rewr findParameter#" + i);
        }
        final UUID missingUuid = new UUID(0xDEADCAFEL, 0xBEEF1234L);
        assertNull(origHost.findParameter(origFixture.paramSet, origHost.newGuid(missingUuid)));
        assertNull(rewrHost.findParameter(rewrFixture.paramSet, rewrHost.newGuid(missingUuid)));
        assertNull(origHost.findParameter(origFixture.paramSet, null));
        assertNull(rewrHost.findParameter(rewrFixture.paramSet, null));

        // Verify resetVertices bit parity.
        final Object origRestGravity = origHost.newVector2(0.15f, -0.95f);
        final Object rewrRestGravity = rewrHost.newVector2(0.15f, -0.95f);
        origHost.resetVertices(origFixture.vertices, origRestGravity);
        rewrHost.resetVertices(rewrFixture.vertices, rewrRestGravity);
        assertVerticesBitExact(origHost, origFixture, rewrHost, rewrFixture, version + " reset");

        // Multi-tick simulation across diverse inputs, viewer masks, gravity vectors, and delta times.
        final Random rng = new Random(RANDOM_SEED);
        for (int tick = 0; tick < SIMULATION_TICKS; tick++) {
            for (int p = 0; p < INPUT_COUNT; p++) {
                final float val;
                if (tick % 17 == 0) {
                    val = DEFAULT_PARAM_MIN - 15.0f;
                } else if (tick % 19 == 0) {
                    val = DEFAULT_PARAM_MAX + 15.0f;
                } else {
                    val = (rng.nextFloat() * 2.0f - 1.0f) * 38.0f;
                }
                origHost.setParamValue(origFixture.params.get(p), val);
                rewrHost.setParamValue(rewrFixture.params.get(p), val);
            }

            final float dt = (tick % 29 == 0)
                    ? ZERO_DELTA_TIME
                    : (tick % 23 == 0 ? LARGE_DELTA_TIME : DELTA_TIME_60FPS + rng.nextFloat() * 0.01f);
            final float gx = (rng.nextFloat() - 0.5f) * 0.6f;
            final float gy = 0.7f + (rng.nextFloat() - 0.5f) * 0.4f;
            final float wx = (rng.nextFloat() - 0.5f) * 0.8f;
            final float wy = (rng.nextFloat() - 0.5f) * 0.5f;
            final boolean isModelWorld = (tick % 3) != 0;
            final boolean useViewerMask = (tick % 5) == 0;

            final Object origMask = useViewerMask ? origHost.newViewerMask() : null;
            final Object rewrMask = useViewerMask ? rewrHost.newViewerMask() : null;
            origHost.evaluateSource(
                    origFixture.source,
                    origFixture.paramSet,
                    origHost.newVector2(gx, gy),
                    origHost.newVector2(wx, wy),
                    dt,
                    null,
                    isModelWorld,
                    origFixture.hairPhysicsWeightMap,
                    origMask,
                    null);
            rewrHost.evaluateSource(
                    rewrFixture.source,
                    rewrFixture.paramSet,
                    rewrHost.newVector2(gx, gy),
                    rewrHost.newVector2(wx, wy),
                    dt,
                    null,
                    isModelWorld,
                    rewrFixture.hairPhysicsWeightMap,
                    rewrMask,
                    null);

            assertVerticesBitExact(origHost, origFixture, rewrHost, rewrFixture, version + " tick=" + tick);
            assertParametersBitExact(origHost, origFixture, rewrHost, rewrFixture, version + " tick=" + tick);
        }

        // Verify rollback parity when bridge closes mid-session while fixtures match bit-for-bit.
        bridge.close();
        for (int tick = 0; tick < 12; tick++) {
            final float dt = DELTA_TIME_60FPS;
            origHost.evaluateSource(
                    origFixture.source,
                    origFixture.paramSet,
                    origHost.newVector2(0.2f, 0.9f),
                    origHost.newVector2(-0.1f, 0.05f),
                    dt,
                    null,
                    true,
                    origFixture.hairPhysicsWeightMap,
                    null,
                    null);
            rewrHost.evaluateSource(
                    rewrFixture.source,
                    rewrFixture.paramSet,
                    rewrHost.newVector2(0.2f, 0.9f),
                    rewrHost.newVector2(-0.1f, 0.05f),
                    dt,
                    null,
                    true,
                    rewrFixture.hairPhysicsWeightMap,
                    null,
                    null);
            assertVerticesBitExact(origHost, origFixture, rewrHost, rewrFixture, version + " rollback=" + tick);
            assertParametersBitExact(origHost, origFixture, rewrHost, rewrFixture, version + " rollback=" + tick);
        }

        // Re-enable bridge and verify steady-state zero heap allocation on stepVertices and findParameter.
        bridge.install();
        verifyZeroSteadyStateAllocations(rewrHost, rewrFixture, version);
    }

    private static void verifyZeroSteadyStateAllocations(
            final PhysicsReflectionHost host, final PhysicsFixture fixture, final String version) throws Throwable {
        final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(bean.isThreadAllocatedMemorySupported(), "thread allocation tracking unsupported");
        bean.setThreadAllocatedMemoryEnabled(true);
        final long tid = Thread.currentThread().getId();

        final Object gravity = host.newVector2(0.1f, 0.99f);
        final Object wind = host.newVector2(0.25f, -0.05f);
        final Object probeGuid = host.newGuid(fixture.paramGuids.get(OUTPUT_COUNT - 1));
        final MethodHandle stepMh = MethodHandles.lookup()
                .unreflect(host.stepMethod)
                .bindTo(host.evaluator)
                .asType(MethodType.methodType(
                        void.class,
                        Object.class,
                        Object.class,
                        float.class,
                        Object.class,
                        Object.class,
                        float.class,
                        float.class));
        final MethodHandle findMh = MethodHandles.lookup()
                .unreflect(host.findMethod)
                .bindTo(host.evaluator)
                .asType(MethodType.methodType(Object.class, Object.class, Object.class));

        for (int i = 0; i < WARMUP_STEPS; i++) {
            stepMh.invokeExact(fixture.vertices, gravity, DELTA_TIME_60FPS, gravity, wind, 0.5f, 1.0f);
            final Object ignored = findMh.invokeExact(fixture.paramSet, probeGuid);
        }

        final long beforeStep = bean.getThreadAllocatedBytes(tid);
        for (int i = 0; i < MEASURED_STEPS; i++) {
            stepMh.invokeExact(fixture.vertices, gravity, DELTA_TIME_60FPS, gravity, wind, 0.5f, 1.0f);
            final Object ignored = findMh.invokeExact(fixture.paramSet, probeGuid);
        }
        final long allocated = bean.getThreadAllocatedBytes(tid) - beforeStep;
        assertEquals(0L, allocated, version + " steady-state stepVertices+findParameter should allocate 0 bytes");
    }

    private static void assertVerticesBitExact(
            final PhysicsReflectionHost origHost,
            final PhysicsFixture origFixture,
            final PhysicsReflectionHost rewrHost,
            final PhysicsFixture rewrFixture,
            final String context)
            throws Exception {
        assertEquals(
                Float.floatToRawIntBits(origHost.getTotalAngle(origFixture.source)),
                Float.floatToRawIntBits(rewrHost.getTotalAngle(rewrFixture.source)),
                context + " totalAngle");
        for (int v = 0; v < PENDULUM_VERTEX_COUNT; v++) {
            final Object ov = origFixture.vertexList.get(v);
            final Object rv = rewrFixture.vertexList.get(v);
            assertVectorBits(origHost, ov, rewrHost, rv, "getPosition", context + " pos[" + v + "]");
            assertVectorBits(origHost, ov, rewrHost, rv, "getLocalPosition", context + " localPos[" + v + "]");
            assertVectorBits(origHost, ov, rewrHost, rv, "getOldPosition", context + " oldPos[" + v + "]");
            assertVectorBits(origHost, ov, rewrHost, rv, "getOldGravity", context + " oldGrav[" + v + "]");
            assertVectorBits(origHost, ov, rewrHost, rv, "getVelocity", context + " vel[" + v + "]");
            assertVectorBits(origHost, ov, rewrHost, rv, "getForce", context + " force[" + v + "]");
        }
    }

    private static void assertVectorBits(
            final PhysicsReflectionHost origHost,
            final Object origVertex,
            final PhysicsReflectionHost rewrHost,
            final Object rewrVertex,
            final String getter,
            final String label)
            throws Exception {
        final float ox = origHost.vectorX(origHost.vertexVec(origVertex, getter));
        final float oy = origHost.vectorY(origHost.vertexVec(origVertex, getter));
        final float rx = rewrHost.vectorX(rewrHost.vertexVec(rewrVertex, getter));
        final float ry = rewrHost.vectorY(rewrHost.vertexVec(rewrVertex, getter));
        assertEquals(Float.floatToRawIntBits(ox), Float.floatToRawIntBits(rx), label + ".x");
        assertEquals(Float.floatToRawIntBits(oy), Float.floatToRawIntBits(ry), label + ".y");
    }

    private static void assertParametersBitExact(
            final PhysicsReflectionHost origHost,
            final PhysicsFixture origFixture,
            final PhysicsReflectionHost rewrHost,
            final PhysicsFixture rewrFixture,
            final String context)
            throws Exception {
        for (int i = 0; i < origFixture.params.size(); i++) {
            final float ov = origHost.getParamValue(origFixture.params.get(i));
            final float rv = rewrHost.getParamValue(rewrFixture.params.get(i));
            assertEquals(Float.floatToRawIntBits(ov), Float.floatToRawIntBits(rv), context + " param[" + i + "]");
        }
    }

    private static Path findNewestArtifactForEvaluator(final Path root, final String evaluatorOwner) throws Exception {
        final List<String> versions = sortedVersions();
        for (int i = versions.size() - 1; i >= 0; i--) {
            final Path artifact = resolveVersionArtifact(root, versions.get(i));
            if (artifact == null) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final PhysicsAccelerationTarget target =
                    PhysicsAccelerationTarget.of(digest).orElse(null);
            if (target != null && evaluatorOwner.equals(target.evaluatorOwner())) {
                return artifact;
            }
        }
        return null;
    }

    private static List<String> sortedVersions() {
        final List<String> versions = new ArrayList<>(ReviewedHostArtifacts.reviewedCubismVersions());
        versions.sort(String::compareTo);
        return versions;
    }

    private static Path referenceRoot() {
        final String configured =
                System.getProperty(REF_DIR_PROPERTY, System.getenv().getOrDefault(REF_DIR_ENV, ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }

    private static Path resolveVersionArtifact(final Path root, final String version) throws IOException {
        final Path direct = root.resolve(version).resolve(CUBISM_JAR);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        final Path referenceBase = Path.of(System.getProperty(USER_HOME_PROPERTY), ARCH_REFERENCE_SUFFIX, version);
        if (!Files.isDirectory(referenceBase)) {
            return null;
        }
        try (var stream = Files.walk(referenceBase, MAX_WALK_DEPTH)) {
            return stream.filter(path -> Files.isRegularFile(path)
                            && CUBISM_JAR.equals(path.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Path findThirdPartyLibsDir(final Path root) throws IOException {
        final List<String> versions = sortedVersions();
        for (int i = versions.size() - 1; i >= 0; i--) {
            final Path artifact = resolveVersionArtifact(root, versions.get(i));
            if (artifact != null && Files.isRegularFile(artifact.getParent().resolve("jdom-1.1.jar"))) {
                return artifact.getParent();
            }
        }
        return null;
    }

    private static URL[] hostClasspathUrls(final Path jarDir, final Path extraLibsDir) throws IOException {
        final List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(jarDir, 2)) {
            for (final Path file : files.toList()) {
                if (Files.isRegularFile(file) && file.toString().endsWith(JAR_EXTENSION)) {
                    urls.add(file.toUri().toURL());
                }
            }
        }
        if (extraLibsDir != null && !extraLibsDir.equals(jarDir)) {
            try (var files = Files.walk(extraLibsDir, 2)) {
                for (final Path file : files.toList()) {
                    final String fileName = file.getFileName().toString();
                    if (Files.isRegularFile(file)
                            && fileName.endsWith(JAR_EXTENSION)
                            && !fileName.startsWith("Live2D")) {
                        urls.add(file.toUri().toURL());
                    }
                }
            }
        }
        return urls.toArray(URL[]::new);
    }

    private static URLClassLoader hostLoader(final Path jarDir, final Path extraLibsDir) throws IOException {
        return new URLClassLoader(hostClasspathUrls(jarDir, extraLibsDir), ClassLoader.getPlatformClassLoader());
    }

    private static RewrittenHostClassLoader rewrittenLoader(final Path jarDir, final Path extraLibsDir)
            throws IOException {
        return new RewrittenHostClassLoader(
                hostClasspathUrls(jarDir, extraLibsDir), ClassLoader.getPlatformClassLoader());
    }

    private static byte[] readClassBytes(final Path jarPath, final String internalName) throws IOException {
        final String entryName = internalName + CLASS_EXTENSION;
        try (JarFile jarFile = new JarFile(jarPath.toFile(), false)) {
            final JarEntry entry = jarFile.getJarEntry(entryName);
            assertNotNull(entry, "missing class entry " + entryName + " in " + jarPath);
            try (InputStream in = jarFile.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static ProtectionDomain protectionDomain(final Path artifact) throws IOException {
        final URL url = artifact.toAbsolutePath().normalize().toUri().toURL();
        return new ProtectionDomain(new CodeSource(url, (Certificate[]) null), null);
    }

    private static void writePatchedClass(final Path outDir, final String internalName, final byte[] bytes)
            throws IOException {
        final Path classFile = outDir.resolve(internalName + CLASS_EXTENSION);
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
    }

    private static void runStrictVerifyAllSubprocess(final Path manifestFile, final int expectedCount)
            throws Exception {
        final Path javaExe = Path.of(System.getProperty(JAVA_HOME_PROPERTY), BIN_DIR, JAVA_BIN);
        final ProcessBuilder pb = new ProcessBuilder(
                javaExe.toString(),
                XVERIFY_ALL_FLAG,
                CLASSPATH_OPTION,
                System.getProperty(JAVA_CLASS_PATH_PROPERTY),
                StrictChildVerifier.class.getName(),
                manifestFile.toString(),
                Integer.toString(expectedCount));
        pb.redirectErrorStream(true);
        final Process process = pb.start();
        final String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "verifier timed out");
        assertEquals(0, process.exitValue(), "strict verification failed:\n" + output);
    }

    /** Loads every patched evaluator class under {@code -Xverify:all} inside a fresh JVM. */
    public static final class StrictChildVerifier {

        private StrictChildVerifier() {}

        /**
         * Verifies every class entry listed in {@code args[0]} equals {@code args[1]} count.
         *
         * @param args manifest path and expected entry count
         */
        public static void main(final String[] args) throws Exception {
            final Path manifest = Path.of(args[0]);
            final int expected = Integer.parseInt(args[1]);
            final List<String> lines = Files.readAllLines(manifest);
            if (lines.size() != expected) {
                throw new AssertionError("expected " + expected + " lines, got " + lines.size());
            }
            final ClassLoader appLoader = StrictChildVerifier.class.getClassLoader();
            for (final String line : lines) {
                final String[] parts = line.split(TAB);
                final Path jarPath = Path.of(parts[1]);
                final Path patchRoot = Path.of(parts[2]);
                final String internalName = parts[3];
                try (PatchedJarLoader loader = new PatchedJarLoader(jarPath, patchRoot, appLoader)) {
                    final Class<?> loaded = Class.forName(internalName.replace('/', '.'), false, loader);
                    loaded.getDeclaredMethods();
                }
            }
        }
    }

    private static final class PatchedJarLoader extends URLClassLoader {
        private final JarFile hostJar;
        private final Path patchRoot;
        private final ClassLoader fallbackParent;

        private PatchedJarLoader(final Path jarPath, final Path patchRoot, final ClassLoader fallbackParent)
                throws IOException {
            super(siblingUrls(jarPath), ClassLoader.getPlatformClassLoader());
            this.hostJar = new JarFile(jarPath.toFile(), false);
            this.patchRoot = patchRoot;
            this.fallbackParent = fallbackParent;
        }

        private static URL[] siblingUrls(final Path jarPath) throws IOException {
            final List<URL> urls = new ArrayList<>();
            try (var files = Files.walk(jarPath.getParent(), 2)) {
                for (final Path file : files.toList()) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(JAR_EXTENSION) && !file.equals(jarPath)) {
                        urls.add(file.toUri().toURL());
                    }
                }
            }
            return urls.toArray(URL[]::new);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final String internal = name.replace('.', '/');
            final Path patchedFile = patchRoot.resolve(internal + CLASS_EXTENSION);
            if (Files.isRegularFile(patchedFile)) {
                try {
                    final byte[] bytes = Files.readAllBytes(patchedFile);
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (final IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            final JarEntry entry = hostJar.getJarEntry(internal + CLASS_EXTENSION);
            if (entry != null) {
                try (InputStream in = hostJar.getInputStream(entry)) {
                    final byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (final IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            try {
                return super.findClass(name);
            } catch (final ClassNotFoundException ignored) {
                return fallbackParent.loadClass(name);
            }
        }

        @Override
        public void close() throws IOException {
            hostJar.close();
            super.close();
        }
    }

    private static final class RewrittenHostClassLoader extends URLClassLoader {
        private final Map<String, OverrideEntry> overrides = new HashMap<>();

        private RewrittenHostClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        private void installOverride(final String name, final byte[] bytes, final ProtectionDomain domain) {
            overrides.put(name, new OverrideEntry(bytes, domain));
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final OverrideEntry entry = overrides.remove(name);
            if (entry != null) {
                return defineClass(name, entry.bytes(), 0, entry.bytes().length, entry.domain());
            }
            return super.findClass(name);
        }

        private record OverrideEntry(byte[] bytes, ProtectionDomain domain) {}
    }

    private static final class PhysicsReflectionHost {
        private final Object evaluator;
        private final Class<?> settingsClass;
        private final Class<?> vertexClass;
        private final Class<?> inputClass;
        private final Class<?> outputClass;
        private final Class<?> sourceTypeClass;
        private final Class<?> paramSetClass;
        private final Class<?> paramClass;
        private final Class<?> paramSourceClass;
        private final Class<?> paramSourceTypeClass;
        private final Class<?> paramGuidClass;
        private final Class<?> paramIdClass;
        private final Class<?> vec2Class;
        private final Class<?> cArrayListClass;
        private final Class<?> viewerMaskClass;
        private final Object noopCallback;
        private final Method evalMethod;
        private final Method stepMethod;
        private final Method resetMethod;
        private final Method findMethod;
        private final Method vecX;
        private final Method vecY;
        private final Method getTotalAngle;
        private final Method setParamValue;
        private final Method getParamValue;

        private PhysicsReflectionHost(final ClassLoader loader, final String evaluatorOwner) throws Exception {
            final Class<?> evalClass = load(loader, evaluatorOwner);
            evaluator = evalClass.getConstructor().newInstance();
            settingsClass = load(loader, PhysicsAccelerationTarget.SETTINGS_SOURCE_OWNER);
            vertexClass = load(loader, PhysicsAccelerationTarget.VERTEX_OWNER);
            inputClass = load(loader, PhysicsAccelerationTarget.INPUT_OWNER);
            outputClass = load(loader, PhysicsAccelerationTarget.OUTPUT_OWNER);
            sourceTypeClass = load(loader, PhysicsAccelerationTarget.SOURCE_TYPE_OWNER);
            paramSetClass = load(loader, PhysicsAccelerationTarget.PARAMETER_SET_OWNER);
            paramClass = load(loader, PhysicsAccelerationTarget.PARAMETER_OWNER);
            paramSourceClass = load(loader, "com/live2d/cubism/doc/model/param/CParameterSource");
            paramSourceTypeClass = load(loader, "com/live2d/cubism/doc/model/param/CParameterSource$Type");
            paramGuidClass = load(loader, "com/live2d/type/CParameterGuid");
            paramIdClass = load(loader, PhysicsAccelerationTarget.PARAMETER_ID_OWNER);
            vec2Class = load(loader, PhysicsAccelerationTarget.VECTOR2_OWNER);
            cArrayListClass = load(loader, PhysicsAccelerationTarget.ARRAY_LIST_OWNER);
            viewerMaskClass = load(loader, PhysicsAccelerationTarget.VIEWER_MASK_OWNER);

            final Class<?> ktFn1 = load(loader, "kotlin/jvm/functions/Function1");
            final Object ktUnit =
                    load(loader, "kotlin/Unit").getField("INSTANCE").get(null);
            noopCallback = Proxy.newProxyInstance(loader, new Class<?>[] {ktFn1}, (proxy, method, args) -> ktUnit);
            evalMethod = evalClass.getMethod(
                    PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                    settingsClass,
                    paramSetClass,
                    vec2Class,
                    vec2Class,
                    float.class,
                    ktFn1,
                    boolean.class,
                    HashMap.class,
                    viewerMaskClass,
                    ArrayList.class);
            stepMethod = evalClass.getMethod(
                    PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                    cArrayListClass,
                    vec2Class,
                    float.class,
                    vec2Class,
                    vec2Class,
                    float.class,
                    float.class);
            resetMethod = evalClass.getMethod(PhysicsAccelerationTarget.TARGET_METHOD_NAME, cArrayListClass, vec2Class);
            findMethod =
                    evalClass.getMethod(PhysicsAccelerationTarget.TARGET_METHOD_NAME, paramSetClass, paramGuidClass);
            vecX = vec2Class.getMethod("getX");
            vecY = vec2Class.getMethod("getY");
            getTotalAngle = settingsClass.getMethod("getTotalAngle");
            setParamValue = paramClass.getMethod("setValue", float.class);
            getParamValue = paramClass.getMethod("getValue");
        }

        private static Class<?> load(final ClassLoader loader, final String internalName) throws Exception {
            return Class.forName(internalName.replace('/', '.'), true, loader);
        }

        private Object newVector2(final float x, final float y) throws Exception {
            return vec2Class.getConstructor(float.class, float.class).newInstance(x, y);
        }

        private Object newGuid(final UUID uuid) throws Exception {
            return paramGuidClass.getConstructor(UUID.class).newInstance(uuid);
        }

        private Object newViewerMask() throws Exception {
            return viewerMaskClass.getConstructor().newInstance();
        }

        private void evaluateSource(
                final Object settings,
                final Object paramSet,
                final Object gravity,
                final Object wind,
                final float deltaTime,
                final Object fn,
                final boolean isModelWorld,
                final HashMap<?, ?> hairWeights,
                final Object viewerMask,
                final ArrayList<?> outList)
                throws Exception {
            evalMethod.invoke(
                    evaluator,
                    settings,
                    paramSet,
                    gravity,
                    wind,
                    deltaTime,
                    fn == null ? noopCallback : fn,
                    isModelWorld,
                    hairWeights,
                    viewerMask,
                    outList);
        }

        private void stepVertices(
                final Object vertices,
                final Object initialPosition,
                final float delayScale,
                final Object gravity,
                final Object wind,
                final float timeStep,
                final float threshold)
                throws Exception {
            stepMethod.invoke(evaluator, vertices, initialPosition, delayScale, gravity, wind, timeStep, threshold);
        }

        private void resetVertices(final Object vertices, final Object initialGravity) throws Exception {
            resetMethod.invoke(evaluator, vertices, initialGravity);
        }

        private Object findParameter(final Object paramSet, final Object guid) throws Exception {
            return findMethod.invoke(evaluator, paramSet, guid);
        }

        private float getTotalAngle(final Object source) throws Exception {
            return (float) getTotalAngle.invoke(source);
        }

        private void setParamValue(final Object param, final float value) throws Exception {
            setParamValue.invoke(param, value);
        }

        private float getParamValue(final Object param) throws Exception {
            return (float) getParamValue.invoke(param);
        }

        private Object vertexVec(final Object vertex, final String getterName) throws Exception {
            return vertexClass.getMethod(getterName).invoke(vertex);
        }

        private float vectorX(final Object vec) throws Exception {
            return (float) vecX.invoke(vec);
        }

        private float vectorY(final Object vec) throws Exception {
            return (float) vecY.invoke(vec);
        }

        private PhysicsFixture buildFixture() throws Exception {
            final Object source = settingsClass.getConstructor().newInstance();
            settingsClass.getMethod("setEnable", boolean.class).invoke(source, true);
            settingsClass
                    .getMethod("setNormalizedPositionValueMin", float.class)
                    .invoke(source, -1.0f);
            settingsClass
                    .getMethod("setNormalizedPositionValueMax", float.class)
                    .invoke(source, 1.0f);
            settingsClass
                    .getMethod("setNormalizedPositionDefaultValue", float.class)
                    .invoke(source, 0.0f);
            settingsClass.getMethod("setNormalizedAngleValueMin", float.class).invoke(source, -1.0f);
            settingsClass.getMethod("setNormalizedAngleValueMax", float.class).invoke(source, 1.0f);
            settingsClass
                    .getMethod("setNormalizedAngleDefaultValue", float.class)
                    .invoke(source, 0.0f);

            @SuppressWarnings("unchecked")
            final List<Object> verticesList =
                    (List<Object>) settingsClass.getMethod("getVertices").invoke(source);
            for (int v = 0; v < PENDULUM_VERTEX_COUNT; v++) {
                final Object vertex = vertexClass.getConstructor().newInstance();
                vertexClass.getMethod("setMobility", float.class).invoke(vertex, 0.85f + 0.05f * v);
                vertexClass.getMethod("setDelay", float.class).invoke(vertex, 0.7f + 0.1f * v);
                vertexClass.getMethod("setAcceleration", float.class).invoke(vertex, 1.15f + 0.1f * v);
                vertexClass.getMethod("setRadius", float.class).invoke(vertex, v == 0 ? 0.0f : 12.0f + 4.0f * v);
                vec2Class
                        .getMethod("set", float.class, float.class)
                        .invoke(vertexVec(vertex, "getPosition"), 0.0f, 15.0f * v);
                vec2Class
                        .getMethod("set", float.class, float.class)
                        .invoke(vertexVec(vertex, "getLocalPosition"), 0.0f, v == 0 ? 0.0f : 15.0f);
                vec2Class
                        .getMethod("set", float.class, float.class)
                        .invoke(vertexVec(vertex, "getOldPosition"), 0.0f, 15.0f * v);
                verticesList.add(vertex);
            }

            final Object paramSet = paramSetClass.getConstructor().newInstance();
            final Method addParam = paramSetClass.getMethod("unaryPlus", paramClass);
            final Constructor<?> paramSourceCtor = paramSourceClass.getConstructor();
            final Constructor<?> paramCtor = paramClass.getConstructor(paramSourceClass);
            final Constructor<?> paramIdCtor = paramIdClass.getConstructor(String.class);
            @SuppressWarnings({"unchecked", "rawtypes"})
            final Object normalType = Enum.valueOf((Class<Enum>) paramSourceTypeClass, SOURCE_NORMAL_ENUM);

            final List<UUID> guids = new ArrayList<>();
            final List<Object> params = new ArrayList<>();
            final int totalParams = INPUT_COUNT + OUTPUT_COUNT;
            for (int i = 0; i < totalParams; i++) {
                final UUID uuid = new UUID(0x100000L + i, 0x200000L + i * 31L);
                guids.add(uuid);
                final Object pSrc = paramSourceCtor.newInstance();
                paramSourceClass.getMethod("setMinValue", float.class).invoke(pSrc, DEFAULT_PARAM_MIN);
                paramSourceClass.getMethod("setMaxValue", float.class).invoke(pSrc, DEFAULT_PARAM_MAX);
                paramSourceClass.getMethod("setDefaultValue", float.class).invoke(pSrc, DEFAULT_PARAM_VALUE);
                paramSourceClass.getMethod("setRepeat", boolean.class).invoke(pSrc, i == 2);
                paramSourceClass.getMethod("setGuid", paramGuidClass).invoke(pSrc, newGuid(uuid));
                paramSourceClass.getMethod("setId", paramIdClass).invoke(pSrc, paramIdCtor.newInstance("PARAM_" + i));
                paramSourceClass.getMethod("setParamType", paramSourceTypeClass).invoke(pSrc, normalType);
                final Object param = paramCtor.newInstance(pSrc);
                setParamValue.invoke(param, DEFAULT_PARAM_VALUE);
                addParam.invoke(paramSet, param);
                params.add(param);
            }

            @SuppressWarnings({"unchecked", "rawtypes"})
            final Object[] sourceTypes = {
                Enum.valueOf((Class<Enum>) sourceTypeClass, SRC_TO_X),
                Enum.valueOf((Class<Enum>) sourceTypeClass, SRC_TO_Y),
                Enum.valueOf((Class<Enum>) sourceTypeClass, SRC_TO_G_ANGLE),
                Enum.valueOf((Class<Enum>) sourceTypeClass, SRC_TO_X)
            };

            @SuppressWarnings("unchecked")
            final List<Object> inputs =
                    (List<Object>) settingsClass.getMethod("getInputs").invoke(source);
            for (int i = 0; i < INPUT_COUNT; i++) {
                final Object input = inputClass.getConstructor().newInstance();
                inputClass.getMethod("setSource", paramGuidClass).invoke(input, newGuid(guids.get(i)));
                inputClass.getMethod("setAngleScale", float.class).invoke(input, 20.0f);
                inputClass.getMethod("setTranslationScale", vec2Class).invoke(input, newVector2(2.5f + i, 1.5f));
                inputClass.getMethod("setWeight", float.class).invoke(input, 0.5f + 0.1f * i);
                inputClass.getMethod("setType", sourceTypeClass).invoke(input, sourceTypes[i]);
                inputClass.getMethod("setReverse", boolean.class).invoke(input, (i % 2) == 1);
                inputs.add(input);
            }

            @SuppressWarnings("unchecked")
            final List<Object> outputs =
                    (List<Object>) settingsClass.getMethod("getOutputs").invoke(source);
            for (int i = 0; i < OUTPUT_COUNT; i++) {
                final Object output = outputClass.getConstructor().newInstance();
                outputClass
                        .getMethod("setDestination", paramGuidClass)
                        .invoke(output, newGuid(guids.get(INPUT_COUNT + i)));
                // Index 3 intentionally points cleanly at vertex 3; one output uses scale/reverse mix.
                outputClass.getMethod("setVertexIndex", int.class).invoke(output, i == 0 ? 1 : (i == 1 ? 2 : 3));
                outputClass.getMethod("setTranslationScale", vec2Class).invoke(output, newVector2(3.0f, -2.0f));
                outputClass.getMethod("setAngleScale", float.class).invoke(output, 15.0f + 5.0f * i);
                outputClass.getMethod("setWeight", float.class).invoke(output, 0.75f);
                outputClass.getMethod("setType", sourceTypeClass).invoke(output, sourceTypes[i]);
                outputClass.getMethod("setReverse", boolean.class).invoke(output, (i % 2) == 0);
                outputs.add(output);
            }

            return new PhysicsFixture(source, paramSet, verticesList, verticesList, guids, params, new HashMap<>());
        }
    }

    private record PhysicsFixture(
            Object source,
            Object paramSet,
            Object vertices,
            List<Object> vertexList,
            List<UUID> paramGuids,
            List<Object> params,
            HashMap<Object, Float> hairPhysicsWeightMap) {}
}
