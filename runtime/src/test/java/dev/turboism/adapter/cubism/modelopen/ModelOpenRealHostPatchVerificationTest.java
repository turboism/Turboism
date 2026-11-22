package dev.turboism.adapter.cubism.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Verifies bytecode transformation, idempotency, strict {@code -Xverify:all} class verification,
 * and patched host class execution across all 21 official Cubism Editor JARs ({@code 5.0.00}
 * through {@code 5.3.05}).
 */
final class ModelOpenRealHostPatchVerificationTest {

    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String JAR_SUFFIX = ".jar";
    private static final String JAR_5305_REL =
            ".local/share/cubism-reference/5.3.05/proton/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/"
                    + JAR_NAME;
    private static final String CLASS_SUFFIX = ".class";
    private static final String SHADER_PKG = "com/live2d/graphics3d/shader/";
    private static final String UTIL_PKG = "com/live2d/util/";
    private static final String STUB_PACKAGE_PREFIX = "org.jdom.";
    private static final int DYNAMIC_TARGETS_PER_JAR = 3;
    private static final int EXPECTED_TOTAL_JARS = 21;
    private static final long VERIFY_TIMEOUT_SECONDS = 60L;

    private static final List<String> FIXED_TARGETS = List.of(
            ImageDecodePatcher.UT_IMAGE,
            ImageDecodePatcher.IMAGE_RESOURCE,
            ModelOpenFlowPatcher.MODEL_SOURCE_COMPANION,
            ModelOpenFlowPatcher.DOCUMENT_LOADER,
            FloatArrayDeserializerPatcher.TARGET_INTERNAL_NAME,
            ArchiveReadPatcher.ARCHIVE_READER,
            ArchiveReadPatcher.GRAPHICS_READ_LAMBDA,
            ArchiveReadPatcher.SERIALIZE_READ_LAMBDA,
            ExtendedInterpolationParallelPatcher.GAME_MOTION_SET,
            ExtendedInterpolationParallelPatcher.EXT_INTERP_EXTENSION);

    @Test
    void patchesVerifyAllTargetsAndIsIdempotentAcrossAllTwentyOneCubismJars(@TempDir final Path tempDir)
            throws Exception {
        final List<Path> jars = discoverAllTwentyOneJars();
        Assumptions.assumeTrue(
                jars.size() == EXPECTED_TOTAL_JARS,
                "All 21 Cubism JARs (5.0.00-5.3.05) must exist locally; found " + jars.size());

        final ModelOpenAccelerationTransformer transformer = new ModelOpenAccelerationTransformer();
        final Path manifestList = tempDir.resolve("patched-targets.txt");
        final List<String> manifestLines = new ArrayList<>();

        for (final Path jarPath : jars) {
            final String version = versionLabel(jarPath);
            final Path versionOutDir = tempDir.resolve(version);
            Files.createDirectories(versionOutDir);

            try (JarFile jar = new JarFile(jarPath.toFile(), false);
                    URLClassLoader hostLoader = new URLClassLoader(
                            new URL[] {jarPath.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                final List<String> targets = new ArrayList<>(FIXED_TARGETS);
                final String shaderFactory = findShaderFactory(jar);
                assertNotNull(shaderFactory, "Shader texture factory not found in " + version);
                targets.add(shaderFactory);
                final String saxBuilderUtil = findSaxBuilderUtil(jar, hostLoader);
                assertNotNull(saxBuilderUtil, "JDOM SAXBuilder util not found in " + version);
                targets.add(saxBuilderUtil);
                final String stopwatchUtil = findStopwatchUtil(jar, hostLoader);
                assertNotNull(stopwatchUtil, "Stopwatch util not found in " + version);
                targets.add(stopwatchUtil);

                for (final String internalName : targets) {
                    final JarEntry entry = jar.getJarEntry(internalName + CLASS_SUFFIX);
                    assertNotNull(entry, "Missing " + internalName + " in " + version);
                    final byte[] original;
                    try (InputStream in = jar.getInputStream(entry)) {
                        original = in.readAllBytes();
                    }

                    final byte[] patched = transformer.transform(hostLoader, internalName, null, null, original);
                    assertNotNull(patched, "Expected patch bytes for " + internalName + " in " + version);
                    assertNull(
                            transformer.transform(hostLoader, internalName, null, null, patched),
                            "Patch must be idempotent for " + internalName + " in " + version);

                    if (ImageDecodePatcher.IMAGE_RESOURCE.equals(internalName)) {
                        assertTrue(
                                ModelOpenClassWriters.hasDeclaredMethod(
                                        new ClassReader(patched), "turboism$addLoadedBytes", "(I)V"),
                                "Atomic stats helper missing in " + version);
                    }

                    final Path outFile = versionOutDir.resolve(internalName + CLASS_SUFFIX);
                    Files.createDirectories(outFile.getParent());
                    Files.write(outFile, patched);
                    manifestLines.add(version + "\t" + jarPath + "\t" + versionOutDir + "\t" + internalName);
                }
            }
        }

        Files.write(manifestList, manifestLines);
        runStrictBytecodeVerifierSubprocess(manifestList);
    }

    @Test
    void patchedHostClassesExecuteAtomicallyAndPreserveBehavior(@TempDir final Path tempDir) throws Exception {
        final List<Path> jars = discoverAllTwentyOneJars();
        Assumptions.assumeTrue(!jars.isEmpty(), "Host Cubism JAR absent");
        final Path jarPath = jars.get(jars.size() - 1);

        final ModelOpenAccelerationTransformer transformer = new ModelOpenAccelerationTransformer();
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            for (final String internalName : List.of(
                    ImageDecodePatcher.IMAGE_RESOURCE,
                    ImageDecodePatcher.UT_IMAGE,
                    FloatArrayDeserializerPatcher.TARGET_INTERNAL_NAME,
                    ArchiveReadPatcher.SERIALIZE_READ_LAMBDA)) {
                final byte[] original = jar.getInputStream(jar.getJarEntry(internalName + CLASS_SUFFIX))
                        .readAllBytes();
                final byte[] patched = transformer.transform(null, internalName, null, null, original);
                assertNotNull(patched, "Patch failed for " + internalName);
                final Path out = tempDir.resolve(internalName + CLASS_SUFFIX);
                Files.createDirectories(out.getParent());
                Files.write(out, patched);
            }
        }

        try (PatchedJarLoader loader =
                new PatchedJarLoader(jarPath, tempDir, getClass().getClassLoader())) {
            // 1. Atomic CImageResource stats under multi-threaded updates.
            final Class<?> resClass = loader.loadClass("com.live2d.graphics.CImageResource");
            final Method addCount = resClass.getDeclaredMethod("turboism$addLoadedCount", int.class);
            final Method addBytes = resClass.getDeclaredMethod("turboism$addLoadedBytes", int.class);
            addCount.setAccessible(true);
            addBytes.setAccessible(true);
            final Field countField = resClass.getDeclaredField("loadedCount");
            final Field bytesField = resClass.getDeclaredField("loadedBytes");
            countField.setAccessible(true);
            bytesField.setAccessible(true);
            final int baseCount = countField.getInt(null);
            final int baseBytes = bytesField.getInt(null);

            final int threads = 6;
            final int iterations = 2000;
            final ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                final CountDownLatch startGate = new CountDownLatch(1);
                final List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        try {
                            startGate.await();
                            for (int i = 0; i < iterations; i++) {
                                addCount.invoke(null, 1);
                                addBytes.invoke(null, 64);
                            }
                        } catch (final Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    }));
                }
                startGate.countDown();
                for (final Future<?> future : futures) {
                    future.get(15, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(baseCount + threads * iterations, countField.getInt(null));
            assertEquals(baseBytes + threads * iterations * 64, bytesField.getInt(null));

            // 2. Patched UtImage memory-stream PNG decode + ABGR -> ARGB parity.
            final BufferedImage src = new BufferedImage(19, 17, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 17; y++) {
                for (int x = 0; x < 19; x++) {
                    src.setRGB(x, y, ((x * 11 + y * 5) & 0xFF) << 24 | (x * 7) << 16 | (y * 13) << 8 | 0x4D);
                }
            }
            final ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(src, "png", pngOut));
            final Class<?> utImageClass = loader.loadClass("jp.noids.graphics.UtImage");
            final Class<?> progressClass = loader.loadClass("jp.noids.framework.e.c");
            final Method getBufferedImage =
                    utImageClass.getMethod("getBufferedImage", InputStream.class, int.class, progressClass);
            final BufferedImage decoded = (BufferedImage) getBufferedImage.invoke(
                    null, new ByteArrayInputStream(pngOut.toByteArray()), BufferedImage.TYPE_INT_ARGB, null);
            assertNotNull(decoded);
            assertEquals(BufferedImage.TYPE_INT_ARGB, decoded.getType());
            assertArrayEquals(src.getRGB(0, 0, 19, 17, null, 0, 19), decoded.getRGB(0, 0, 19, 17, null, 0, 19));

            // 3. Patched com.live2d.serialize.b lambda zero-copy RAW claim.
            final Class<?> refClass = loader.loadClass("kotlin.jvm.internal.Ref$ObjectRef");
            final Object ref = refClass.getConstructor().newInstance();
            final Class<?> lambdaClass = loader.loadClass("com.live2d.serialize.b");
            final var lambdaCtor = lambdaClass.getDeclaredConstructor(refClass);
            lambdaCtor.setAccessible(true);
            final Object lambda = lambdaCtor.newInstance(ref);
            final Method invokeFn = lambdaClass.getMethod("a", InputStream.class);
            invokeFn.setAccessible(true);
            final byte[] rawPayload = new byte[] {9, 8, 7, 6, 5, 4};
            invokeFn.invoke(lambda, ArchiveReadBridge.wrapRawBytes(rawPayload));
            assertSame(rawPayload, refClass.getField("element").get(ref));

            // 4. Patched com.live2d.serialize.impl.G in-place float array deserializer with real jdom Element.
            final Class<?> elemClass = loader.loadClass("org.jdom.Element");
            final Object elem = elemClass.getConstructor(String.class).newInstance("float_array");
            elemClass.getMethod("setAttribute", String.class, String.class).invoke(elem, "count", "3");
            elemClass.getMethod("setText", String.class).invoke(elem, "1.25 -0.0 1.4012985E-45");
            final Class<?> xmlReaderClass = loader.loadClass("com.live2d.serialize.XmlReader");
            final Class<?> archiveReaderClass = loader.loadClass("com.live2d.serialize.archiver.ArchiveReader");
            final Object xmlReader = xmlReaderClass
                    .getConstructor(archiveReaderClass, java.util.HashMap.class)
                    .newInstance(null, new java.util.HashMap<String, Object>());
            final Class<?> gClass = loader.loadClass("com.live2d.serialize.impl.G");
            final var gCtor = gClass.getDeclaredConstructor(String.class);
            gCtor.setAccessible(true);
            final Object gInstance = gCtor.newInstance("float[]");
            final Method createInstance = gClass.getMethod("createInstance", elemClass, xmlReaderClass);
            final float[] parsed = (float[]) createInstance.invoke(gInstance, elem, xmlReader);
            assertEquals(3, parsed.length);
            assertEquals(Float.floatToRawIntBits(1.25f), Float.floatToRawIntBits(parsed[0]));
            assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(parsed[1]));
            assertEquals(Float.floatToRawIntBits(Float.MIN_VALUE), Float.floatToRawIntBits(parsed[2]));
        }
    }

    private static String findShaderFactory(final JarFile jar) throws IOException {
        final var entries = jar.entries();
        while (entries.hasMoreElements()) {
            final JarEntry entry = entries.nextElement();
            final String name = entry.getName();
            if (!name.startsWith(SHADER_PKG) || !name.endsWith(CLASS_SUFFIX) || name.indexOf('$') >= 0) {
                continue;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                final ClassReader cr = new ClassReader(in.readAllBytes());
                if (ModelOpenClassWriters.hasDeclaredMethod(
                                cr,
                                DirectTextureUploadPatcher.FLAGGED_METHOD_NAME,
                                DirectTextureUploadPatcher.FLAGGED_METHOD_DESC)
                        && ModelOpenClassWriters.hasDeclaredMethod(
                                cr,
                                DirectTextureUploadPatcher.MIPMAP_METHOD_NAME,
                                DirectTextureUploadPatcher.MIPMAP_METHOD_DESC)) {
                    return name.substring(0, name.length() - CLASS_SUFFIX.length());
                }
            }
        }
        return null;
    }

    private static String findSaxBuilderUtil(final JarFile jar, final ClassLoader loader) throws IOException {
        final var entries = jar.entries();
        while (entries.hasMoreElements()) {
            final JarEntry entry = entries.nextElement();
            final String name = entry.getName();
            if (!name.startsWith(UTIL_PKG) || !name.endsWith(CLASS_SUFFIX)) {
                continue;
            }
            final String internalName = name.substring(0, name.length() - CLASS_SUFFIX.length());
            if (!SaxBuilderFastPatcher.isCandidateTarget(internalName)) {
                continue;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                if (SaxBuilderFastPatcher.tryPatch(internalName, loader, in.readAllBytes()) != null) {
                    return internalName;
                }
            }
        }
        return null;
    }

    private static String findStopwatchUtil(final JarFile jar, final ClassLoader loader) throws IOException {
        final var entries = jar.entries();
        while (entries.hasMoreElements()) {
            final JarEntry entry = entries.nextElement();
            final String name = entry.getName();
            if (!name.startsWith(UTIL_PKG) || !name.endsWith(CLASS_SUFFIX)) {
                continue;
            }
            final String internalName = name.substring(0, name.length() - CLASS_SUFFIX.length());
            if (!ExtendedInterpolationParallelPatcher.isSupportedTarget(internalName)) {
                continue;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                if (ExtendedInterpolationParallelPatcher.tryPatch(internalName, loader, in.readAllBytes()) != null) {
                    return internalName;
                }
            }
        }
        return null;
    }

    private static List<Path> discoverAllTwentyOneJars() throws IOException {
        final List<Path> jars = new ArrayList<>();
        final Path home = Path.of(System.getProperty("user.home"));
        final Path artifacts = home.resolve(ARTIFACTS_DIR_NAME);
        if (Files.isDirectory(artifacts)) {
            try (var stream = Files.list(artifacts)) {
                stream.filter(Files::isDirectory).sorted().forEach(dir -> {
                    final Path jar = dir.resolve(JAR_NAME);
                    if (Files.isRegularFile(jar)) {
                        jars.add(jar);
                    }
                });
            }
        }
        final Path j5305 = home.resolve(JAR_5305_REL);
        if (Files.isRegularFile(j5305)) {
            jars.add(j5305);
        }
        return jars;
    }

    private static String versionLabel(final Path jarPath) {
        final String p = jarPath.toString();
        if (p.contains("5.3.05")) {
            return "5.3.05";
        }
        return jarPath.getParent().getFileName().toString();
    }

    private static void runStrictBytecodeVerifierSubprocess(final Path manifestList) throws Exception {
        final String javaExe =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final String classpath = System.getProperty("java.class.path");
        final Process process = new ProcessBuilder(
                        javaExe,
                        "-Xverify:all",
                        "-cp",
                        classpath,
                        StrictVerifierMain.class.getName(),
                        manifestList.toString())
                .redirectErrorStream(true)
                .start();
        final String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }
        assertTrue(process.waitFor(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Verifier subprocess timed out");
        assertEquals(0, process.exitValue(), "Strict -Xverify:all failed:\n" + output);
        assertTrue(
                output.contains(
                        "VERIFIED_TARGETS=" + (EXPECTED_TOTAL_JARS * (FIXED_TARGETS.size() + DYNAMIC_TARGETS_PER_JAR))),
                output);
    }

    /** Child entrypoint launched under {@code -Xverify:all} to link all patched host classes. */
    public static final class StrictVerifierMain {

        private StrictVerifierMain() {}

        /**
         * Links patched classes from each version against its host JAR under strict verification.
         *
         * @param args single-element array containing manifest file path
         * @throws Exception when verification fails
         */
        public static void main(final String[] args) throws Exception {
            final List<String> lines = Files.readAllLines(Path.of(args[0]));
            int verified = 0;
            String currentVersion = null;
            PatchedJarLoader currentLoader = null;

            try {
                for (final String line : lines) {
                    if (line.isBlank()) {
                        continue;
                    }
                    final String[] parts = line.split("\t");
                    final String version = parts[0];
                    final Path jarPath = Path.of(parts[1]);
                    final Path patchedDir = Path.of(parts[2]);
                    final String internalName = parts[3];

                    if (!version.equals(currentVersion)) {
                        if (currentLoader != null) {
                            currentLoader.close();
                        }
                        currentVersion = version;
                        currentLoader =
                                new PatchedJarLoader(jarPath, patchedDir, StrictVerifierMain.class.getClassLoader());
                    }
                    final Class<?> defined = Class.forName(internalName.replace('/', '.'), false, currentLoader);
                    // Force full bytecode verification and method descriptor linking without <clinit>.
                    defined.getDeclaredMethods();
                    verified++;
                }
            } finally {
                if (currentLoader != null) {
                    currentLoader.close();
                }
            }
            System.out.println("VERIFIED_TARGETS=" + verified);
        }
    }

    private static final class PatchedJarLoader extends URLClassLoader {

        private final JarFile hostJar;
        private final Path patchRoot;
        private final ClassLoader runtimeParent;

        PatchedJarLoader(final Path jarPath, final Path patchRoot, final ClassLoader runtimeParent) throws IOException {
            super(siblingUrls(jarPath), ClassLoader.getPlatformClassLoader());
            // Disable JAR signer verification so patched unsigned classes match host classes cleanly.
            this.hostJar = new JarFile(jarPath.toFile(), false);
            this.patchRoot = patchRoot;
            this.runtimeParent = runtimeParent;
        }

        private static URL[] siblingUrls(final Path jarPath) throws IOException {
            final List<URL> urls = new ArrayList<>();
            boolean hasGluegen = false;
            boolean hasJogl = false;
            boolean hasJdom = false;
            boolean hasLog4j = false;
            try (var files = Files.walk(jarPath.getParent(), 2)) {
                for (final Path file : files.toList()) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(JAR_SUFFIX) && !file.equals(jarPath)) {
                        urls.add(file.toUri().toURL());
                        final String fname = file.getFileName().toString();
                        hasGluegen |= fname.contains("gluegen");
                        hasJogl |= fname.contains("jogl");
                        hasJdom |= fname.contains("jdom");
                        hasLog4j |= fname.contains("log4j");
                    }
                }
            }
            final Path home = Path.of(System.getProperty("user.home"));
            if (!hasJogl) {
                final Path fallbackJogl =
                        home.resolve(ARTIFACTS_DIR_NAME).resolve("5.3.03").resolve("jogl-all.jar");
                if (Files.isRegularFile(fallbackJogl)) {
                    urls.add(fallbackJogl.toUri().toURL());
                }
            }
            if (!hasGluegen || !hasJogl || !hasJdom || !hasLog4j) {
                final Path libDir = home.resolve(JAR_5305_REL).getParent();
                if (Files.isDirectory(libDir)) {
                    try (var files = Files.walk(libDir, 2)) {
                        for (final Path file : files.toList()) {
                            final String fname = file.getFileName().toString();
                            if (Files.isRegularFile(file)
                                    && fname.endsWith(JAR_SUFFIX)
                                    && (fname.contains("gluegen")
                                            || fname.contains("jogl")
                                            || fname.contains("jdom")
                                            || fname.contains("log4j"))) {
                                urls.add(file.toUri().toURL());
                            }
                        }
                    }
                }
            }
            return urls.toArray(URL[]::new);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final String internal = name.replace('.', '/');
            final Path patchedFile = this.patchRoot.resolve(internal + CLASS_SUFFIX);
            if (Files.isRegularFile(patchedFile)) {
                try {
                    final byte[] bytes = Files.readAllBytes(patchedFile);
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (final IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            final JarEntry hostEntry = this.hostJar.getJarEntry(internal + CLASS_SUFFIX);
            if (hostEntry != null) {
                try (InputStream in = this.hostJar.getInputStream(hostEntry)) {
                    final byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (final IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            try {
                return super.findClass(name);
            } catch (final ClassNotFoundException ignored) {
                try {
                    return this.runtimeParent.loadClass(name);
                } catch (final ClassNotFoundException absent) {
                    if (name.startsWith(STUB_PACKAGE_PREFIX)) {
                        final ClassWriter cw = new ClassWriter(0);
                        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
                        cw.visitEnd();
                        final byte[] stub = cw.toByteArray();
                        return defineClass(name, stub, 0, stub.length);
                    }
                    throw absent;
                }
            }
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("dev.turboism.")) {
                return this.runtimeParent.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                this.hostJar.close();
            }
        }
    }
}
