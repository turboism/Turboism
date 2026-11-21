package dev.turboism.adapter.cubism.exportperf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Verifies 21-release host bytecode patching, idempotency, and strict -Xverify:all linkage.
 */
final class RuntimeExportRealHostPatchVerificationTest {

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
    private static final Set<String> VERSIONS_WITHOUT_UTIL_DA = Set.of("5.0.00", "5.0.01", "5.0.02");
    private static final Set<String> STUB_PREFIXES =
            Set.of("org.jdom.", "org.apache.logging.", "org.apache.commons.", "com.jogamp.");
    private static final String ARCHIVE_WRITER_CLASS = "com/live2d/serialize/archiver/ArchiveWriter";
    private static final String ATLAS_BUILDER_CLASS = "com/live2d/cubism/doc/model/texture/G";
    private static final String IMAGE_RESOURCE_CLASS = "com/live2d/graphics/CImageResource";
    private static final String WRITABLE_IMAGE_CLASS = "com/live2d/graphics/CWritableImage";
    private static final String TEXTURE_ATLAS_CLASS = "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas";
    private static final String PNG_EXPORTER_CLASS = "com/live2d/cubism/doc/model/exporter/b$a";
    private static final String BYTE_SINK_CLASS = "com/live2d/util/c/b";
    private static final String EDGE_CLEAN_CLASS = "jp/noids/graphics/h";
    private static final String EDGE_EXTEND_CLASS = "com/live2d/util/d/a";
    private static final String EXPORTER_PACKAGE = "com/live2d/cubism/doc/model/exporter/";

    @Test
    void patchesAll21HostReleasesWithStrictVerifyAllAndIdempotency(@TempDir final Path tempDir) throws Exception {
        final Map<String, Path> releaseJars = discoverAll21ReleaseJars();
        Assumptions.assumeTrue(
                releaseJars.size() == EXPECTED_TOTAL_JARS,
                "Expected 21 local Cubism host JARs, found " + releaseJars.size());

        final RuntimeExportAccelerationTransformer transformer = new RuntimeExportAccelerationTransformer();
        final List<String> manifestLines = new ArrayList<>();

        for (final Map.Entry<String, Path> release : releaseJars.entrySet()) {
            final String version = release.getKey();
            final Path jarPath = release.getValue();
            final Path patchDir = tempDir.resolve(version);
            final Set<String> patchedNames = new TreeSet<>();

            try (JarFile jarFile = new JarFile(jarPath.toFile(), false)) {
                final Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    final JarEntry entry = entries.nextElement();
                    final String name = entry.getName();
                    if (!name.endsWith(CLASS_SUFFIX)) {
                        continue;
                    }
                    final String internalName = name.substring(0, name.length() - CLASS_SUFFIX.length());
                    final byte[] raw;
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        raw = in.readAllBytes();
                    }
                    final byte[] patched = transformer.transform(null, internalName, null, null, raw);
                    if (patched == null) {
                        continue;
                    }
                    assertFalse(
                            java.util.Arrays.equals(raw, patched),
                            version + " returned identical bytes for " + internalName);
                    final byte[] secondPass = transformer.transform(null, internalName, null, null, patched);
                    assertNull(secondPass, version + " patch was not idempotent for " + internalName);

                    final Path outFile = patchDir.resolve(internalName + CLASS_SUFFIX);
                    Files.createDirectories(outFile.getParent());
                    Files.write(outFile, patched);
                    patchedNames.add(internalName);
                    manifestLines.add(
                            String.join("\t", version, jarPath.toString(), patchDir.toString(), internalName));
                }
            }

            assertExpectedReleaseCoverage(version, patchedNames);
        }

        final Path manifest = tempDir.resolve("manifest.tsv");
        Files.write(manifest, manifestLines);
        runStrictVerifySubprocess(manifest, manifestLines.size());
        verifyPatchedByteSinkExecution(releaseJars.get(VERSION_5305), tempDir.resolve(VERSION_5305));
    }

    @Test
    void transformerPublicApiExcludesAsmTypes() {
        for (final Method method : RuntimeExportAccelerationTransformer.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) && !Modifier.isProtected(method.getModifiers())) {
                continue;
            }
            assertFalse(
                    method.getReturnType().getName().startsWith("org.objectweb.asm."),
                    "Public/protected return type leaks ASM: " + method);
            for (final Class<?> param : method.getParameterTypes()) {
                assertFalse(
                        param.getName().startsWith("org.objectweb.asm."),
                        "Public/protected parameter type leaks ASM: " + method);
            }
        }
    }

    private static void assertExpectedReleaseCoverage(final String version, final Set<String> patchedNames) {
        assertTrue(patchedNames.contains(ARCHIVE_WRITER_CLASS), version + " missing ArchiveWriter");
        assertTrue(patchedNames.contains(ATLAS_BUILDER_CLASS), version + " missing texture/G");
        assertTrue(patchedNames.contains(IMAGE_RESOURCE_CLASS), version + " missing CImageResource");
        assertTrue(patchedNames.contains(WRITABLE_IMAGE_CLASS), version + " missing CWritableImage");
        assertTrue(patchedNames.contains(TEXTURE_ATLAS_CLASS), version + " missing CTextureAtlas");
        assertTrue(patchedNames.contains(PNG_EXPORTER_CLASS), version + " missing exporter/b$a");
        assertTrue(patchedNames.contains(BYTE_SINK_CLASS), version + " missing util/c/b");
        assertTrue(patchedNames.contains(EDGE_CLEAN_CLASS), version + " missing graphics/h");
        if (!VERSIONS_WITHOUT_UTIL_DA.contains(version)) {
            assertTrue(patchedNames.contains(EDGE_EXTEND_CLASS), version + " missing com/live2d/util/d/a");
        }
        final long downscaleCount = patchedNames.stream()
                .filter(n -> n.startsWith("com/live2d/util/")
                        && n.endsWith("/g")
                        && n.length() == "com/live2d/util/d/g".length())
                .count();
        assertEquals(1L, downscaleCount, version + " expected 1 downscale class: " + patchedNames);

        final List<String> exporterPatched = patchedNames.stream()
                .filter(n -> n.startsWith(EXPORTER_PACKAGE))
                .sorted()
                .toList();
        // Expect exporter/{b$a, v/w/x$a, B/C, o/p/q, q/r/s, cz/cA/cQ/cR} = 6 classes in exporter/.
        assertEquals(6, exporterPatched.size(), version + " unexpected exporter patched classes: " + exporterPatched);
    }

    private static void verifyPatchedByteSinkExecution(final Path jarPath, final Path patchDir) throws Exception {
        try (PatchedJarLoader loader = new PatchedJarLoader(
                jarPath, patchDir, RuntimeExportRealHostPatchVerificationTest.class.getClassLoader())) {
            final Class<?> byteSinkClass = Class.forName(BYTE_SINK_CLASS.replace('/', '.'), true, loader);
            final Constructor<?> ctor = byteSinkClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            final Object sink = ctor.newInstance();
            final Method writeBytes = byteSinkClass.getMethod("a", byte[].class);
            final Method toByteArray = byteSinkClass.getMethod("b");
            final byte[] payload = "cubism-moc3-bulk-write-parity".getBytes(StandardCharsets.UTF_8);
            writeBytes.invoke(sink, new Object[] {payload});
            assertArrayEquals(
                    payload, (byte[]) toByteArray.invoke(sink), "Patched util/c/b bulk write payload mismatch");
        }
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
        assertEquals(0, process.exitValue(), "Bytecode verification failed:\n" + output);
    }

    /** Loads and links every patched class under {@code -Xverify:all} inside an isolated loader. */
    public static final class ChildVerifier {
        private ChildVerifier() {}

        /**
         * Verifies all manifest entries using strict JVM bytecode verification.
         *
         * @param args manifest path and expected verified class count
         * @throws Exception if any patched class fails verification or count mismatches
         */
        public static void main(final String[] args) throws Exception {
            final List<String> lines = Files.readAllLines(Path.of(args[0]));
            final int expectedCount = Integer.parseInt(args[1]);
            String currentVersion = null;
            PatchedJarLoader currentLoader = null;
            int verifiedCount = 0;
            try {
                for (final String line : lines) {
                    if (line.isBlank()) {
                        continue;
                    }
                    final String[] parts = line.split("\t");
                    final String version = parts[0];
                    final Path jarPath = Path.of(parts[1]);
                    final Path patchRoot = Path.of(parts[2]);
                    final String internalName = parts[3];
                    if (!version.equals(currentVersion)) {
                        if (currentLoader != null) {
                            currentLoader.close();
                        }
                        currentVersion = version;
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
                } catch (IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            final JarEntry hostEntry = hostJar.getJarEntry(internal + CLASS_SUFFIX);
            if (hostEntry != null) {
                try (InputStream in = hostJar.getInputStream(hostEntry)) {
                    final byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            try {
                return super.findClass(name);
            } catch (ClassNotFoundException ignored) {
                try {
                    return hostParent.loadClass(name);
                } catch (ClassNotFoundException absent) {
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
