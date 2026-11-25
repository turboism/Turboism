package dev.turboism.adapter.cubism.optimization.drag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.HostArtifactTestLoader;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionTarget;
import dev.turboism.adapter.cubism.optimization.inputpath.InputPathElisionTransformer;
import dev.turboism.adapter.cubism.optimization.warpdeform.WarpDeformEditTarget;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies exact-artifact admission, bytecode transformation, strict {@code -Xverify:all} JVM
 * linkage, bridge handle resolution, and {@link InputPathElisionTransformer} composition across
 * all locally available reviewed Cubism artifacts.
 */
final class EditDragResponsivenessArtifactTest {

    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String ARCH_REFERENCE_SUFFIX = ".local/share/cubism-reference";
    private static final String REF_DIR_PROPERTY = "turboism.test.cubismRefDir";
    private static final String FULL_REF_PROPERTY = "turboism.cubismRefRoot";
    private static final String CLASS_SUFFIX = ".class";
    private static final int VERIFY_TIMEOUT_SECONDS = 120;

    @Test
    void transformsAndStrictlyVerifiesAllAvailableReviewedCubismArtifacts(@TempDir final Path tempDir)
            throws Exception {
        final List<Path> jars = findReviewedJars();
        assumeTrue(!jars.isEmpty(), "no local reviewed Cubism JARs supplied");

        final Path manifestFile = tempDir.resolve("patched-targets.txt");
        final List<String> manifestLines = new ArrayList<>();
        int verifiedCount = 0;

        for (final Path jarPath : jars) {
            final HostArtifactDigest digest = HostArtifactDigest.cached(jarPath);
            final EditDragResponsivenessTarget contract =
                    EditDragResponsivenessTarget.of(digest).orElse(null);
            if (contract == null) {
                continue;
            }
            verifiedCount++;
            final Path versionOutDir = tempDir.resolve(contract.version());
            Files.createDirectories(versionOutDir);

            try (JarFile jar = new JarFile(jarPath.toFile());
                    HostArtifactTestLoader loader =
                            HostArtifactTestLoader.open(jarPath.getParent(), findJoglCompanion(jarPath))) {
                final ProtectionDomain domain =
                        new ProtectionDomain(new CodeSource(jarPath.toUri().toURL(), (Certificate[]) null), null);
                final Map<String, byte[]> references = new LinkedHashMap<>();
                for (final String owner : contract.targetClasses()) {
                    references.put(owner, readClassBytes(jar, owner));
                }

                final EditDragResponsivenessTransformer transformer =
                        new EditDragResponsivenessTransformer(loader, jarPath, references, contract);
                for (final String owner : contract.targetClasses()) {
                    final byte[] raw = references.get(owner);
                    final byte[] transformed = transformer.transform(null, loader, owner, null, domain, raw);
                    assertNull(transformer.failure(), contract.version() + " failed " + owner);
                    assertTrue(
                            transformed != null && transformed.length > raw.length, contract.version() + " " + owner);
                    writePatchedClass(versionOutDir, owner, transformed);
                    manifestLines.add(contract.version() + "\t" + jarPath + "\t" + versionOutDir + "\t" + owner);
                }
                assertEquals(EditDragResponsivenessTarget.EXPECTED_MATCHES, transformer.matches(), contract.version());
                assertEquals(transformer.expectedTotalSites(), transformer.sites(), contract.version());
                final WarpDeformEditTarget warpContract =
                        WarpDeformEditTarget.of(digest).orElseThrow();
                assertTrue(
                        java.util.Collections.disjoint(contract.targetClasses(), warpContract.targetClasses()),
                        contract.version() + " edit drag owners must stay disjoint from warp deform owners");

                try (EditDragResponsivenessBridge bridge = new EditDragResponsivenessBridge(loader)) {
                    assertNotNull(bridge.snapshot(), contract.version());
                }

                // Verify clean composition when InputPathElisionTransformer runs ahead of drag responsiveness.
                final byte[] rawWidget = references.get(InputPathElisionTarget.OWNER);
                final InputPathElisionTransformer inputPath =
                        new InputPathElisionTransformer(loader, jarPath, rawWidget);
                final byte[] inputPatched =
                        inputPath.transform(null, loader, InputPathElisionTarget.OWNER, null, domain, rawWidget);
                assertNull(inputPath.failure(), contract.version() + " inputPath");
                final EditDragResponsivenessTransformer composed =
                        new EditDragResponsivenessTransformer(loader, jarPath, references, contract);
                final byte[] doublePatched =
                        composed.transform(null, loader, InputPathElisionTarget.OWNER, null, domain, inputPatched);
                assertNull(composed.failure(), contract.version() + " composed failure");
                assertEquals(1, composed.matchesFor(InputPathElisionTarget.OWNER), contract.version());
                final Path composedDir = tempDir.resolve(contract.version() + "-composed");
                writePatchedClass(composedDir, InputPathElisionTarget.OWNER, doublePatched);
                manifestLines.add(contract.version()
                        + "-composed\t"
                        + jarPath
                        + "\t"
                        + composedDir
                        + "\t"
                        + InputPathElisionTarget.OWNER);
            }
        }
        assertEquals(ReviewedHostArtifacts.all().size(), verifiedCount, "expected every reviewed JAR to verify");
        Files.write(manifestFile, manifestLines);
        runStrictVerifySubprocess(manifestFile, manifestLines.size());
    }

    @Test
    void realCubism5305DeformerSelectorHandlesBindCleanlyWhenFullLibrariesPresent() throws Exception {
        final Path libDir = Path.of(
                System.getProperty("user.home"),
                ARCH_REFERENCE_SUFFIX,
                "5.3.05",
                "proton",
                "pfx",
                "drive_c",
                "Program Files",
                "Live2D Cubism 5.3",
                "app",
                "lib");
        final Path jdomJar = libDir.resolve("jdom-1.1.jar");
        if (!Files.isRegularFile(libDir.resolve(JAR_NAME)) || !Files.isRegularFile(jdomJar)) {
            return;
        }
        final List<URL> urls = new ArrayList<>();
        try (var stream = Files.list(libDir)) {
            for (final Path entry : stream.toList()) {
                if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
                    urls.add(entry.toUri().toURL());
                }
            }
        }
        try (URLClassLoader fullLoader =
                        new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
                DeformerSelectorAccelerator accelerator = DeformerSelectorAccelerator.tryCreate(fullLoader)) {
            assertNotNull(accelerator);
            assertTrue(accelerator.isBound(), "5.3.05 DeformerSelectorAccelerator handles must bind cleanly");
        }
    }

    private static void writePatchedClass(final Path outDir, final String owner, final byte[] bytes)
            throws IOException {
        final Path outFile = outDir.resolve(owner + CLASS_SUFFIX);
        Files.createDirectories(outFile.getParent());
        Files.write(outFile, bytes);
    }

    private static void runStrictVerifySubprocess(final Path manifestFile, final int expectedVerifiedCount)
            throws Exception {
        final String javaExe =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final String classpath = System.getProperty("java.class.path");
        final Process process = new ProcessBuilder(
                        javaExe,
                        "-Xverify:all",
                        "-cp",
                        classpath,
                        StrictChildVerifier.class.getName(),
                        manifestFile.toString(),
                        Integer.toString(expectedVerifiedCount))
                .redirectErrorStream(true)
                .start();
        final String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes());
        }
        assertTrue(process.waitFor(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Strict -Xverify:all timed out");
        assertEquals(0, process.exitValue(), "Strict -Xverify:all failed:\n" + output);
    }

    /** Child entrypoint launched under {@code -Xverify:all} to link all patched host classes. */
    public static final class StrictChildVerifier {
        private StrictChildVerifier() {}

        public static void main(final String[] args) throws Exception {
            final List<String> lines = Files.readAllLines(Path.of(args[0]));
            final int expectedCount = Integer.parseInt(args[1]);
            String currentTag = null;
            PatchedJarLoader currentLoader = null;
            int verifiedCount = 0;
            try {
                for (final String line : lines) {
                    if (line.isBlank()) {
                        continue;
                    }
                    final String[] parts = line.split("\t");
                    final String tag = parts[0];
                    final Path jarPath = Path.of(parts[1]);
                    final Path patchRoot = Path.of(parts[2]);
                    final String internalName = parts[3];
                    if (!tag.equals(currentTag)) {
                        if (currentLoader != null) {
                            currentLoader.close();
                        }
                        currentTag = tag;
                        currentLoader =
                                new PatchedJarLoader(jarPath, patchRoot, StrictChildVerifier.class.getClassLoader());
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
                throw new IllegalStateException("Unexpected verified count: " + verifiedCount + " != " + expectedCount);
            }
        }
    }

    private static final class PatchedJarLoader extends URLClassLoader {
        private final JarFile hostJar;
        private final Path patchRoot;
        private final ClassLoader fallbackParent;

        PatchedJarLoader(final Path jarPath, final Path patchRoot, final ClassLoader fallbackParent)
                throws IOException {
            super(siblingUrls(jarPath), ClassLoader.getPlatformClassLoader());
            this.hostJar = new JarFile(jarPath.toFile(), false);
            this.patchRoot = patchRoot;
            this.fallbackParent = fallbackParent;
        }

        private static URL[] siblingUrls(final Path jarPath) throws IOException {
            final List<URL> urls = new ArrayList<>();
            boolean hasJogl = false;
            try (var files = Files.walk(jarPath.getParent(), 2)) {
                for (final Path file : files.toList()) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(".jar") && !file.equals(jarPath)) {
                        urls.add(file.toUri().toURL());
                        if (file.getFileName().toString().startsWith("jogl-all")) {
                            hasJogl = true;
                        }
                    }
                }
            }
            if (!hasJogl) {
                final Path jogl = findJoglCompanion(jarPath);
                if (jogl != null) {
                    urls.add(jogl.toUri().toURL());
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
            final JarEntry entry = hostJar.getJarEntry(internal + CLASS_SUFFIX);
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

    private static List<Path> findReviewedJars() {
        final List<Path> found = new ArrayList<>();
        final String explicit = System.getProperty("turboism.test.cubismEditorJar");
        if (explicit != null && !explicit.isBlank() && Files.isRegularFile(Path.of(explicit))) {
            found.add(Path.of(explicit));
        }
        final String userHome = System.getProperty("user.home");
        final List<Path> roots = new ArrayList<>();
        for (final String prop : List.of(REF_DIR_PROPERTY, FULL_REF_PROPERTY)) {
            final String configured = System.getProperty(prop);
            if (configured != null && !configured.isBlank()) {
                roots.add(Path.of(configured));
            }
        }
        roots.add(Path.of(userHome, ARTIFACTS_DIR_NAME));
        for (final Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (final HostArtifactDigest digest : ReviewedHostArtifacts.all()) {
                final String ver = ReviewedHostArtifacts.cubismVersionOf(digest).orElse("");
                final Path candidate = root.resolve(ver).resolve(JAR_NAME);
                if (Files.isRegularFile(candidate) && !found.contains(candidate)) {
                    found.add(candidate);
                }
            }
        }
        final Path win5305 = Path.of(
                userHome,
                ARCH_REFERENCE_SUFFIX,
                "5.3.05",
                "proton",
                "pfx",
                "drive_c",
                "Program Files",
                "Live2D Cubism 5.3",
                "app",
                "lib",
                JAR_NAME);
        if (Files.isRegularFile(win5305) && !found.contains(win5305)) {
            found.add(win5305);
        }
        return found;
    }

    private static Path findJoglCompanion(final Path jarPath) {
        final Path versionRoot =
                jarPath.getParent() != null ? jarPath.getParent().getParent() : null;
        if (versionRoot != null) {
            final Path fromRoot =
                    HostArtifactTestLoader.reviewedJogl(versionRoot).orElse(null);
            if (fromRoot != null && Files.isRegularFile(fromRoot)) {
                return fromRoot;
            }
        }
        final Path archiveRoot = Path.of(System.getProperty("user.home"), ARTIFACTS_DIR_NAME);
        return HostArtifactTestLoader.reviewedJogl(archiveRoot).orElse(null);
    }

    private static byte[] readClassBytes(final JarFile jar, final String internalName) throws Exception {
        final JarEntry entry = jar.getJarEntry(internalName + CLASS_SUFFIX);
        if (entry == null) {
            throw new IllegalStateException("missing class entry: " + internalName);
        }
        try (InputStream in = jar.getInputStream(entry);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
