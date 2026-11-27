package dev.turboism.adapter.cubism.optimization.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.exportperf.RuntimeExportAccelerationTransformer;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLoadingTransformer;
import dev.turboism.adapter.cubism.textureatlas.cache.AtlasCacheReuseTransformer;
import dev.turboism.adapter.cubism.textureatlas.image.AtlasTileBboxTransformer;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Verifies 21-release ({@code 5.0.00–5.3.05}) patching, idempotency, transformer composition order,
 * and strict {@code -Xverify:all} class verification for multi-page atlas parallel rebuilds and
 * direct tile compositing.
 */
final class AtlasDirectCompositingHostVerificationTest {

    private static final int EXPECTED_TOTAL_JARS = 21;
    private static final long VERIFY_TIMEOUT_SECONDS = 120L;
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String CLASS_SUFFIX = ".class";
    private static final String JAR_SUFFIX = ".jar";
    private static final String VERSION_5305 = "5.3.05";
    private static final String REF_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_ENV = "TURBOISM_CUBISM_REF";
    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String REFERENCE_5305_SUBPATH =
            "proton/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/" + JAR_NAME;
    private static final List<String> TILE_HELPERS =
            List.of("com/live2d/util/d/g", "com/live2d/util/e/g", "com/live2d/util/f/g");
    private static final Set<String> STUB_PREFIXES =
            Set.of("org.jdom.", "org.apache.logging.", "org.apache.commons.", "com.jogamp.");

    @Test
    void patchesComposesAndStrictlyVerifiesAll21CubismReleases(@TempDir final Path tempDir) throws Exception {
        final Map<String, Path> releaseJars = discoverAll21ReleaseJars();
        Assumptions.assumeTrue(
                releaseJars.size() == EXPECTED_TOTAL_JARS,
                "Expected 21 local Cubism host JARs, found " + releaseJars.size());

        final List<String> manifestLines = new ArrayList<>();
        final AtlasCacheReuseTransformer reuseTransformer = new AtlasCacheReuseTransformer();
        final AtlasTileBboxTransformer bboxTransformer = new AtlasTileBboxTransformer();
        final TextureAtlasLoadingTransformer loadingTransformer = new TextureAtlasLoadingTransformer(true);
        final RuntimeExportAccelerationTransformer exportTransformer = new RuntimeExportAccelerationTransformer();

        for (final Map.Entry<String, Path> entry : releaseJars.entrySet()) {
            final String version = entry.getKey();
            final Path jarPath = entry.getValue();
            final Path patchDirA = tempDir.resolve(version + "-orderA");
            final Path patchDirB = tempDir.resolve(version + "-orderB");

            try (JarFile jar = new JarFile(jarPath.toFile(), false);
                    URLClassLoader hostJarLoader = new URLClassLoader(
                            new URL[] {jarPath.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                final byte[] atlasRaw = readClass(jar, AtlasDirectCompositingPatcher.TEXTURE_ATLAS);
                final byte[] confirmRaw = readClass(jar, AtlasDirectCompositingPatcher.CONFIRM_LOOP);
                final byte[] updateRaw = readClass(jar, AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP);
                final String tileHelperName = findTileHelper(jar);
                assertNotNull(tileHelperName, version + " missing util/{d,e,f}/g");
                final byte[] helperRaw = readClass(jar, tileHelperName);

                // Order A: CacheReuse/Bbox/Export -> TextureAtlasLoadingTransformer
                final byte[] atlasReuseFirst = reuseTransformer.transform(
                        hostJarLoader, AtlasDirectCompositingPatcher.TEXTURE_ATLAS, null, null, atlasRaw);
                assertNotNull(atlasReuseFirst, version + " AtlasCacheReuse did not patch CTextureAtlas");
                final byte[] atlasOrderA = loadingTransformer.transform(
                        hostJarLoader, AtlasDirectCompositingPatcher.TEXTURE_ATLAS, null, null, atlasReuseFirst);
                assertNotNull(atlasOrderA, version + " Order A did not patch CTextureAtlas");
                assertNull(
                        AtlasDirectCompositingPatcher.patch(AtlasDirectCompositingPatcher.TEXTURE_ATLAS, atlasOrderA),
                        version + " CTextureAtlas Order A not idempotent");

                final byte[] helperBboxFirst =
                        bboxTransformer.transform(hostJarLoader, tileHelperName, null, null, helperRaw);
                assertNotNull(helperBboxFirst, version + " Order A bbox failed for " + tileHelperName);
                final byte[] helperOrderA =
                        loadingTransformer.transform(hostJarLoader, tileHelperName, null, null, helperBboxFirst);
                assertNotNull(helperOrderA, version + " Order A did not patch " + tileHelperName);
                assertNull(
                        AtlasDirectCompositingPatcher.patch(tileHelperName, helperOrderA),
                        version + " tile helper Order A not idempotent");

                final byte[] confirmPatched =
                        AtlasDirectCompositingPatcher.patch(AtlasDirectCompositingPatcher.CONFIRM_LOOP, confirmRaw);
                assertNotNull(confirmPatched, version + " confirm loop not patched");
                assertNull(
                        AtlasDirectCompositingPatcher.patch(AtlasDirectCompositingPatcher.CONFIRM_LOOP, confirmPatched),
                        version + " confirm loop not idempotent");

                final byte[] updateExportFirst = exportTransformer.transform(
                        hostJarLoader, AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP, null, null, updateRaw);
                assertNotNull(updateExportFirst, version + " export transformer did not patch texture/G");
                assertNull(
                        loadingTransformer.transform(
                                hostJarLoader,
                                AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP,
                                null,
                                null,
                                updateExportFirst),
                        version + " Order A texture/G should already be patched");

                writePatched(patchDirA, AtlasDirectCompositingPatcher.TEXTURE_ATLAS, atlasOrderA);
                writePatched(patchDirA, tileHelperName, helperOrderA);
                writePatched(patchDirA, AtlasDirectCompositingPatcher.CONFIRM_LOOP, confirmPatched);
                writePatched(patchDirA, AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP, updateExportFirst);
                manifestLines.add(
                        entryLine(version + "-A", jarPath, patchDirA, AtlasDirectCompositingPatcher.TEXTURE_ATLAS));
                manifestLines.add(entryLine(version + "-A", jarPath, patchDirA, tileHelperName));
                manifestLines.add(
                        entryLine(version + "-A", jarPath, patchDirA, AtlasDirectCompositingPatcher.CONFIRM_LOOP));
                manifestLines.add(entryLine(
                        version + "-A", jarPath, patchDirA, AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP));

                // Order B: TextureAtlasLoadingTransformer -> CacheReuse/Bbox/Export
                final byte[] atlasLoadFirst = loadingTransformer.transform(
                        hostJarLoader, AtlasDirectCompositingPatcher.TEXTURE_ATLAS, null, null, atlasRaw);
                assertNotNull(atlasLoadFirst, version + " Order B load first failed for CTextureAtlas");
                final byte[] atlasOrderB = reuseTransformer.transform(
                        hostJarLoader, AtlasDirectCompositingPatcher.TEXTURE_ATLAS, null, null, atlasLoadFirst);
                assertNotNull(atlasOrderB, version + " Order B reuse second failed for CTextureAtlas");
                assertFalse(Arrays.equals(atlasRaw, atlasOrderB));

                final byte[] helperLoadFirst =
                        loadingTransformer.transform(hostJarLoader, tileHelperName, null, null, helperRaw);
                assertNotNull(helperLoadFirst, version + " Order B load first failed for " + tileHelperName);
                final byte[] helperOrderB =
                        bboxTransformer.transform(hostJarLoader, tileHelperName, null, null, helperLoadFirst);
                assertNotNull(helperOrderB, version + " Order B bbox second failed for " + tileHelperName);

                final byte[] updateLoadFirst = loadingTransformer.transform(
                        hostJarLoader, AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP, null, null, updateRaw);
                assertNotNull(updateLoadFirst, version + " Order B load first failed for texture/G");
                assertNull(
                        exportTransformer.transform(
                                hostJarLoader,
                                AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP,
                                null,
                                null,
                                updateLoadFirst),
                        version + " Order B export transform should see G already patched");

                writePatched(patchDirB, AtlasDirectCompositingPatcher.TEXTURE_ATLAS, atlasOrderB);
                writePatched(patchDirB, tileHelperName, helperOrderB);
                writePatched(patchDirB, AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP, updateLoadFirst);
                manifestLines.add(
                        entryLine(version + "-B", jarPath, patchDirB, AtlasDirectCompositingPatcher.TEXTURE_ATLAS));
                manifestLines.add(entryLine(version + "-B", jarPath, patchDirB, tileHelperName));
                manifestLines.add(entryLine(
                        version + "-B", jarPath, patchDirB, AtlasDirectCompositingPatcher.TEXTURE_UPDATE_LOOP));
            }
        }

        final Path manifest = tempDir.resolve("atlas-compositing-manifest.tsv");
        Files.write(manifest, manifestLines);
        runStrictVerifySubprocess(manifest, manifestLines.size());
    }

    private static String entryLine(
            final String version, final Path jar, final Path patchDir, final String internalName) {
        return String.join("\t", version, jar.toString(), patchDir.toString(), internalName);
    }

    private static byte[] readClass(final JarFile jar, final String internalName) throws IOException {
        final JarEntry entry = jar.getJarEntry(internalName + CLASS_SUFFIX);
        assertNotNull(entry, "Missing host class " + internalName + " in " + jar.getName());
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static String findTileHelper(final JarFile jar) {
        for (final String candidate : TILE_HELPERS) {
            final JarEntry entry = jar.getJarEntry(candidate + CLASS_SUFFIX);
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry)) {
                    final byte[] raw = in.readAllBytes();
                    if (AtlasDirectCompositingPatcher.patch(candidate, raw) != null) {
                        return candidate;
                    }
                } catch (final IOException ignored) {
                    // Try next candidate.
                }
            }
        }
        return null;
    }

    private static void writePatched(final Path patchDir, final String internalName, final byte[] bytes)
            throws IOException {
        final Path out = patchDir.resolve(internalName + CLASS_SUFFIX);
        Files.createDirectories(out.getParent());
        Files.write(out, bytes);
    }

    private static Map<String, Path> discoverAll21ReleaseJars() throws IOException {
        final Map<String, Path> result = new LinkedHashMap<>();
        final Path root = resolveArtifactsRoot();
        if (Files.isDirectory(root)) {
            try (var dirs = Files.list(root)) {
                dirs.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(dir -> {
                            final Path jar = dir.resolve(JAR_NAME);
                            if (Files.isRegularFile(jar)) {
                                result.put(dir.getFileName().toString(), jar);
                            }
                        });
            }
        }
        if (!result.containsKey(VERSION_5305)) {
            final Path ref5305 = resolve5305Jar();
            if (Files.isRegularFile(ref5305)) {
                result.put(VERSION_5305, ref5305);
            }
        }
        return result;
    }

    private static Path resolveArtifactsRoot() {
        final String prop = System.getProperty(REF_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        final String env = System.getenv(REF_ENV);
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ARTIFACTS_DIR_NAME);
    }

    private static Path resolve5305Jar() {
        return Path.of(
                System.getProperty("user.home"),
                ".local",
                "share",
                "cubism-reference",
                VERSION_5305,
                REFERENCE_5305_SUBPATH);
    }

    private static void runStrictVerifySubprocess(final Path manifestList, final int expectedVerifiedCount)
            throws Exception {
        final String javaExe =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final String classpath = System.getProperty("java.class.path");
        final Process process = new ProcessBuilder(
                        javaExe,
                        "-Xverify:all",
                        "-cp",
                        classpath,
                        ChildVerifier.class.getName(),
                        manifestList.toString(),
                        Integer.toString(expectedVerifiedCount))
                .redirectErrorStream(true)
                .start();

        final String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes());
        }
        assertTrue(process.waitFor(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Verifier subprocess timed out");
        assertEquals(0, process.exitValue(), "Strict bytecode verification failed:\n" + output);
    }

    /** Loads and links every patched class under {@code -Xverify:all} inside isolated loaders. */
    public static final class ChildVerifier {
        private ChildVerifier() {}

        public static void main(final String[] args) throws Exception {
            final List<String> lines = Files.readAllLines(Path.of(args[0]));
            final int expectedCount = Integer.parseInt(args[1]);
            String currentKey = null;
            PatchedJarLoader currentLoader = null;
            int verifiedCount = 0;
            try {
                for (final String line : lines) {
                    if (line.isBlank()) {
                        continue;
                    }
                    final String[] parts = line.split("\t");
                    final String key = parts[0];
                    final Path jarPath = Path.of(parts[1]);
                    final Path patchRoot = Path.of(parts[2]);
                    final String internalName = parts[3];
                    if (!key.equals(currentKey)) {
                        if (currentLoader != null) {
                            currentLoader.close();
                        }
                        currentKey = key;
                        currentLoader = new PatchedJarLoader(jarPath, patchRoot, ChildVerifier.class.getClassLoader());
                    }
                    final Class<?> defined = Class.forName(internalName.replace('/', '.'), false, currentLoader);
                    defined.getDeclaredMethods();
                    verifiedCount++;
                }
            } finally {
                if (currentLoader != null) {
                    currentLoader.close();
                }
            }
            if (verifiedCount != expectedCount) {
                throw new IllegalStateException(
                        "Expected " + expectedCount + " verified classes, got " + verifiedCount);
            }
        }
    }

    private static final class PatchedJarLoader extends URLClassLoader {
        private final JarFile hostJar;
        private final Path patchRoot;
        private final ClassLoader hostParent;

        PatchedJarLoader(final Path jarPath, final Path patchRoot, final ClassLoader hostParent) throws IOException {
            super(siblingUrls(jarPath), ClassLoader.getPlatformClassLoader());
            this.hostJar = new JarFile(jarPath.toFile(), false);
            this.patchRoot = patchRoot;
            this.hostParent = hostParent;
        }

        private static URL[] siblingUrls(final Path jarPath) throws IOException {
            final List<URL> urls = new ArrayList<>();
            try (var files = Files.walk(jarPath.getParent(), 2)) {
                for (final Path file : files.toList()) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(JAR_SUFFIX) && !file.equals(jarPath)) {
                        urls.add(file.toUri().toURL());
                    }
                }
            }
            return urls.toArray(URL[]::new);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final String internal = name.replace('.', '/');
            final Path patchedFile = patchRoot.resolve(internal + CLASS_SUFFIX);
            if (Files.isRegularFile(patchedFile)) {
                try {
                    final byte[] bytes = Files.readAllBytes(patchedFile);
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (final IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            final JarEntry hostEntry = hostJar.getJarEntry(internal + CLASS_SUFFIX);
            if (hostEntry != null) {
                try (InputStream in = hostJar.getInputStream(hostEntry)) {
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
                    return hostParent.loadClass(name);
                } catch (final ClassNotFoundException absent) {
                    for (final String prefix : STUB_PREFIXES) {
                        if (name.startsWith(prefix)) {
                            final ClassWriter cw = new ClassWriter(0);
                            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
                            cw.visitEnd();
                            final byte[] stub = cw.toByteArray();
                            return defineClass(name, stub, 0, stub.length);
                        }
                    }
                    throw absent;
                }
            }
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("dev.turboism.") || name.startsWith("kotlin.")) {
                return hostParent.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                hostJar.close();
            }
        }
    }
}
