package dev.turboism.adapter.cubism.optimization.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies bytecode patching, idempotency, and strict {@code -Xverify:all} class verification
 * across all reviewed Cubism Editor JARs ({@code 5.0.00} through {@code 5.3.05}).
 */
final class TextureAtlasRealHostPatchVerificationTest {

    private static final String REF_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_ENV = "TURBOISM_CUBISM_REF";
    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String REFERENCE_DIR_SUFFIX = ".local/share/cubism-reference";
    private static final String REFERENCE_5305_VERSION = "5.3.05";
    private static final Path REFERENCE_5305_RELATIVE_JAR = Path.of(
            "proton", "pfx", "drive_c", "Program Files", "Live2D Cubism 5.3", "app", "lib", "Live2D_Cubism.jar");
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String JAR_SUFFIX = ".jar";
    private static final String CLASS_SUFFIX = ".class";
    private static final String STUB_PACKAGE_PREFIX = "org.jdom.";
    private static final int EXPECTED_BASE_JAR_COUNT = 20;
    private static final int EXPECTED_WITH_5305_JAR_COUNT = 21;
    private static final long VERIFY_TIMEOUT_SECONDS = 60L;

    private static final List<String> TARGET_INTERNAL_NAMES = List.of(
            "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel",
            TextureAtlasUiListTransformer.EDIT_LAYER_MODEL_IMAGE,
            TextureAtlasUiListTransformer.MODEL_IMAGE_LIST,
            TextureAtlasUiListTransformer.PATH_WRAPPER,
            TextureAtlasUiListTransformer.CONFIRM_TASK,
            TextureAtlasUiListTransformer.CONFIRM_BRIDGE,
            TextureAtlasMeshApplyTransformer.MESH,
            TextureAtlasMeshApplyTransformer.MESH_OPS,
            TextureAtlasMeshApplyTransformer.NON_DELAUNAY_SOLVER,
            TextureAtlasEdgeFillTransformer.TARGET);

    @Test
    void patchesAndStrictlyVerifiesAllTargetsAcrossAllTwentyHostJars(@TempDir final Path tempDir) throws Exception {
        final Path artifactsRoot = resolveArtifactsRoot();
        Assumptions.assumeTrue(Files.isDirectory(artifactsRoot), "Host artifacts directory is absent");

        final List<Path> jars = new ArrayList<>();
        final List<String> versions = new ArrayList<>();
        try (var stream = Files.list(artifactsRoot)) {
            stream.filter(Files::isDirectory).sorted().forEach(dir -> {
                final Path jar = dir.resolve(JAR_NAME);
                if (Files.isRegularFile(jar)) {
                    jars.add(jar);
                    versions.add(dir.getFileName().toString());
                }
            });
        }
        final Path reference5305 = resolve5305ReferenceJar();
        if (jars.size() == EXPECTED_BASE_JAR_COUNT
                && !versions.contains(REFERENCE_5305_VERSION)
                && Files.isRegularFile(reference5305)) {
            jars.add(reference5305);
            versions.add(REFERENCE_5305_VERSION);
        }
        assertTrue(
                jars.size() == EXPECTED_BASE_JAR_COUNT || jars.size() == EXPECTED_WITH_5305_JAR_COUNT,
                "Expected 20 or 21 reviewed host JARs, found " + jars.size());

        final TextureAtlasLoadingTransformer mainTransformer = new TextureAtlasLoadingTransformer(true);
        final TextureAtlasMeshSolverTransformer solverTransformer = new TextureAtlasMeshSolverTransformer();
        final TextureAtlasMeshApplyTransformer applyTransformer = new TextureAtlasMeshApplyTransformer();
        final TextureAtlasUiListTransformer uiTransformer = new TextureAtlasUiListTransformer();
        final TextureAtlasEdgeFillTransformer edgeFillTransformer = new TextureAtlasEdgeFillTransformer();

        final Path manifestList = tempDir.resolve("patched-targets.txt");
        final List<String> manifestLines = new ArrayList<>();

        for (int index = 0; index < jars.size(); index++) {
            final Path jarPath = jars.get(index);
            final String version = versions.get(index);
            final Path versionOutDir = tempDir.resolve(version);
            Files.createDirectories(versionOutDir);

            try (JarFile jar = new JarFile(jarPath.toFile())) {
                for (final String internalName : TARGET_INTERNAL_NAMES) {
                    final JarEntry entry = jar.getJarEntry(internalName + CLASS_SUFFIX);
                    assertNotNull(entry, "Missing " + internalName + " in " + version);
                    final byte[] original;
                    try (InputStream in = jar.getInputStream(entry)) {
                        original = in.readAllBytes();
                    }
                    final byte[] patched = mainTransformer.transform(null, internalName, null, null, original);
                    assertNotNull(patched, "Patch failed for " + internalName + " in " + version);

                    // Verify idempotency across standalone transformers as well.
                    assertNull(
                            mainTransformer.transform(null, internalName, null, null, patched),
                            "Main transformer must be idempotent for " + internalName + " in " + version);
                    assertNull(
                            solverTransformer.transform(null, null, internalName, null, null, patched),
                            "Solver transformer must be idempotent for " + internalName + " in " + version);
                    assertNull(
                            applyTransformer.transform(null, null, internalName, null, null, patched),
                            "Apply transformer must be idempotent for " + internalName + " in " + version);
                    assertNull(
                            uiTransformer.transform(null, null, internalName, null, null, patched),
                            "UI transformer must be idempotent for " + internalName + " in " + version);
                    assertNull(
                            edgeFillTransformer.transform(null, null, internalName, null, null, patched),
                            "EdgeFill transformer must be idempotent for " + internalName + " in " + version);

                    final Path outFile = versionOutDir.resolve(internalName + CLASS_SUFFIX);
                    Files.createDirectories(outFile.getParent());
                    Files.write(outFile, patched);
                    manifestLines.add(version + "\t" + jarPath + "\t" + versionOutDir + "\t" + internalName);
                }
            }
        }

        Files.write(manifestList, manifestLines);
        runStrictVerifySubprocess(manifestList, manifestLines.size());
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

    /** Loads and links every patched class under {@code -Xverify:all} inside an isolated classloader. */
    public static final class ChildVerifier {
        private ChildVerifier() {}

        public static void main(final String[] args) throws Exception {
            final List<String> lines = Files.readAllLines(Path.of(args[0]));
            final int expectedVerifiedCount = Integer.parseInt(args[1]);
            String currentVersion = null;
            PatchedJarLoader currentLoader = null;
            int verifiedCount = 0;
            try {
                for (final String line : lines) {
                    if (line.isBlank()) continue;
                    final String[] parts = line.split("\t");
                    final String version = parts[0];
                    final Path jarPath = Path.of(parts[1]);
                    final Path patchRoot = Path.of(parts[2]);
                    final String internalName = parts[3];
                    if (!version.equals(currentVersion)) {
                        if (currentLoader != null) currentLoader.close();
                        currentVersion = version;
                        currentLoader = new PatchedJarLoader(jarPath, patchRoot, ChildVerifier.class.getClassLoader());
                    }
                    final Class<?> defined = Class.forName(internalName.replace('/', '.'), false, currentLoader);
                    // Force full bytecode verification of all methods, stack maps, and code frames.
                    defined.getDeclaredMethods();
                    verifiedCount++;
                }
            } finally {
                if (currentLoader != null) currentLoader.close();
            }
            if (verifiedCount != expectedVerifiedCount) {
                throw new IllegalStateException("Unexpected verified count: " + verifiedCount);
            }
        }
    }

    private static Path resolve5305ReferenceJar() {
        return Path.of(System.getProperty("user.home"), REFERENCE_DIR_SUFFIX, REFERENCE_5305_VERSION)
                .resolve(REFERENCE_5305_RELATIVE_JAR);
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

    private static final class PatchedJarLoader extends URLClassLoader {
        private final JarFile hostJar;
        private final Path patchRoot;
        private final ClassLoader hostParent;

        PatchedJarLoader(final Path jarPath, final Path patchRoot, final ClassLoader hostParent) throws IOException {
            super(siblingUrls(jarPath), ClassLoader.getPlatformClassLoader());
            // Disable signature verification so patched classes share identical null signer certs with host classes.
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
                    if (name.startsWith(STUB_PACKAGE_PREFIX)) {
                        final var cw = new org.objectweb.asm.ClassWriter(0);
                        cw.visit(
                                org.objectweb.asm.Opcodes.V17,
                                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                                internal,
                                null,
                                "java/lang/Object",
                                null);
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
