package dev.turboism.adapter.cubism.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract.Bound;
import dev.turboism.adapter.cubism.optimization.ReviewedHostContract.Candidate;
import dev.turboism.adapter.cubism.optimization.ReviewedHostContract.Refused;
import dev.turboism.adapter.cubism.optimization.ReviewedHostContract.Resolution;
import dev.turboism.mapping.verification.CubismEditorReleaseDetector;
import dev.turboism.mapping.verification.HostIdentityProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Synthetic evidence tests for {@link ReviewedHostContract}: minimal jars carry
 * a real declaration class and pinned target-class bytes, so repackaging,
 * unknown declarations, tampering and ambiguity are exercised without a host.
 */
class ReviewedHostContractTest {

    private static final String ANCHOR = "com/live2d/cubism/CEAppCtrl.class";
    private static final String DECLARATION = "com/live2d/cubism/h.class";
    private static final String REVIEWED_VERSION = "5.3.02";
    private static final int REVIEWED_BUILD = 503020001;
    private static final String UNKNOWN_VERSION = "5.9.99";
    private static final int UNKNOWN_BUILD = 509990001;

    @TempDir
    Path tempDir;

    @Test
    void reviewedDeclarationBindsDespiteResourceRepackaging() throws Exception {
        final List<Candidate<String>> candidates = List.of(candidate(REVIEWED_VERSION));
        final Path original = hostJar(REVIEWED_VERSION, REVIEWED_BUILD, pinnedClasses(), Map.of());
        final Path repacked = hostJar(
                REVIEWED_VERSION,
                REVIEWED_BUILD,
                pinnedClasses(),
                Map.of(
                        "META-INF/extra-resource.txt",
                        "resource".getBytes(),
                        "com/live2d/cubism/notes.txt",
                        "reordered".getBytes()));

        final Resolution<String> first = ReviewedHostContract.resolve(original, candidates);
        final Resolution<String> second = ReviewedHostContract.resolve(repacked, candidates);
        assertInstanceOf(Bound.class, first);
        assertInstanceOf(Bound.class, second);
        assertEquals(REVIEWED_VERSION, ((Bound<String>) second).sourceVersion());
        assertTrue(((Bound<String>) second).declaredRelease());
        assertFalse(((Bound<String>) first)
                .probe()
                .identity()
                .orElseThrow()
                .artifact()
                .equals(((Bound<String>) second)
                        .probe()
                        .identity()
                        .orElseThrow()
                        .artifact()));
    }

    @Test
    void declaredSelfConsistentUnknownVersionBindsUniqueContract() throws Exception {
        final Path jar = hostJar(UNKNOWN_VERSION, UNKNOWN_BUILD, pinnedClasses(), Map.of());
        final Resolution<String> resolution = ReviewedHostContract.resolve(jar, List.of(candidate(REVIEWED_VERSION)));
        final Bound<String> bound = assertInstanceOf(Bound.class, resolution);
        assertFalse(bound.declaredRelease());
        assertEquals(REVIEWED_VERSION, bound.sourceVersion());
        assertEquals(UNKNOWN_VERSION, bound.probe().identity().orElseThrow().version());
        assertEquals(UNKNOWN_BUILD, bound.probe().identity().orElseThrow().build());
    }

    @Test
    void reviewedReleaseCannotBorrowAnotherGenerationsContract() throws Exception {
        final Path jar = hostJar(REVIEWED_VERSION, REVIEWED_BUILD, pinnedClasses(), Map.of());
        final Refused<?> refused =
                assertInstanceOf(Refused.class, ReviewedHostContract.resolve(jar, List.of(candidate("5.2.03"))));
        assertTrue(refused.reason().contains("no reviewed target contract for declared release"));
    }

    @Test
    void artifactReplacementAfterIdentityProbeRefusesMatchingTargets() throws Exception {
        final Path original = hostJar(REVIEWED_VERSION, REVIEWED_BUILD, pinnedClasses(), Map.of());
        final Path replacement = hostJar(UNKNOWN_VERSION, UNKNOWN_BUILD, pinnedClasses(), Map.of());
        final HostIdentityProbe identity = CubismEditorReleaseDetector.probe(original);
        Files.copy(replacement, original, StandardCopyOption.REPLACE_EXISTING);

        final Refused<?> refused = assertInstanceOf(
                Refused.class,
                ReviewedHostContract.resolve(original, List.of(candidate(REVIEWED_VERSION)), ignored -> identity));
        assertTrue(refused.reason().contains("changed during contract inspection"));
    }

    @Test
    void installerCannotReuseAStaleBindingAfterRepackaging() throws Exception {
        final Path original = hostJar(REVIEWED_VERSION, REVIEWED_BUILD, pinnedClasses(), Map.of());
        final List<Candidate<String>> candidates = List.of(candidate(REVIEWED_VERSION));
        final Bound<?> bound = assertInstanceOf(Bound.class, ReviewedHostContract.resolve(original, candidates));
        bound.requireUnchanged(original);

        final Path repacked = hostJar(
                REVIEWED_VERSION, REVIEWED_BUILD, pinnedClasses(), Map.of("repacked-resource.txt", new byte[] {1}));
        Files.copy(repacked, original, StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IllegalStateException.class, () -> bound.requireUnchanged(original));

        // A fresh probe admits the same release again; this guard pins a snapshot,
        // not the set of whole-archive hashes that the product accepts.
        final Bound<?> refreshed = assertInstanceOf(Bound.class, ReviewedHostContract.resolve(original, candidates));
        refreshed.requireUnchanged(original);
        assertEquals(REVIEWED_VERSION, refreshed.sourceVersion());
    }

    @Test
    void tamperedPinnedClassRefusesOnlyTheAlteredContract() throws Exception {
        final Map<String, byte[]> tampered = pinnedClasses();
        tampered.put("feature/Two.class", classBytes("feature/Two", "tampered"));
        final Path jar = hostJar(REVIEWED_VERSION, REVIEWED_BUILD, tampered, Map.of());

        final Resolution<String> refused = ReviewedHostContract.resolve(jar, List.of(candidate(REVIEWED_VERSION)));
        assertInstanceOf(Refused.class, refused);
        assertTrue(((Refused<String>) refused).reason().contains("altered"));

        // A sibling hook pinning only untouched classes still binds on the same jar.
        final List<Candidate<String>> sibling = List.of(new Candidate<>(
                REVIEWED_VERSION,
                "sibling",
                Map.of(
                        "feature/One.class".replace(".class", ""),
                        sha256(pinnedClasses().get("feature/One.class")))));
        assertInstanceOf(Bound.class, ReviewedHostContract.resolve(jar, sibling));
    }

    @Test
    void missingDeclarationRefusesEvenWhenClassesMatch() throws Exception {
        final Map<String, byte[]> entries = pinnedClasses();
        final Path jar = rawJar(entries, Map.of(), List.of());
        final Resolution<String> resolution = ReviewedHostContract.resolve(jar, List.of(candidate(REVIEWED_VERSION)));
        assertInstanceOf(Refused.class, resolution);
        assertTrue(((Refused<String>) resolution).reason().contains("DECLARATION_MISSING"));
    }

    @Test
    void malformedDeclarationRefusesEvenWhenClassesMatch() throws Exception {
        final Path jar = hostJarWithDeclarationBytes(new byte[] {0, 1, 2, 3}, pinnedClasses());
        final Resolution<String> resolution = ReviewedHostContract.resolve(jar, List.of(candidate(REVIEWED_VERSION)));
        assertInstanceOf(Refused.class, resolution);
        assertTrue(((Refused<String>) resolution).reason().contains("DECLARATION"));
    }

    @Test
    void conflictingDeclarationsRefuseEvenWhenClassesMatch() throws Exception {
        final Map<String, byte[]> classes = pinnedClasses();
        classes.put("com/live2d/cubism/a.class", declarationBytes("a", "5.3.02", 503020001));
        classes.put("com/live2d/cubism/b.class", declarationBytes("b", "5.3.03", 503030001));
        final Path jar = rawJar(classes, Map.of(), List.of());
        final Resolution<String> resolution = ReviewedHostContract.resolve(jar, List.of(candidate(REVIEWED_VERSION)));
        assertInstanceOf(Refused.class, resolution);
        assertTrue(((Refused<String>) resolution).reason().contains("DECLARATION_AMBIGUOUS"));
    }

    @Test
    void distinctMatchingContractsAreAmbiguousAndRefuse() throws Exception {
        final Map<String, byte[]> classes = pinnedClasses();
        final List<Candidate<String>> candidates = List.of(
                new Candidate<>(
                        "5.3.02", "contract-a", Map.of("feature/One", sha256(classes.get("feature/One.class")))),
                new Candidate<>(
                        "5.3.03", "contract-b", Map.of("feature/Two", sha256(classes.get("feature/Two.class")))));
        final Path jar = hostJar(UNKNOWN_VERSION, UNKNOWN_BUILD, classes, Map.of());
        final Resolution<String> resolution = ReviewedHostContract.resolve(jar, candidates);
        assertInstanceOf(Refused.class, resolution);
        assertTrue(((Refused<String>) resolution).reason().contains("ambiguous"));
    }

    @Test
    void equalContractsBehindEqualPinsMergeIntoOneBinding() throws Exception {
        final Map<String, String> pins = pinnedSha256(pinnedClasses());
        final List<Candidate<String>> candidates =
                List.of(new Candidate<>("5.3.02", "shared", pins), new Candidate<>("5.3.03", "shared", pins));
        final Path jar = hostJar(UNKNOWN_VERSION, UNKNOWN_BUILD, pinnedClasses(), Map.of());
        final Resolution<String> resolution = ReviewedHostContract.resolve(jar, candidates);
        final Bound<String> bound = assertInstanceOf(Bound.class, resolution);
        assertEquals(List.of("5.3.02", "5.3.03"), bound.matchedVersions());
        assertEquals("shared", bound.contract());
    }

    @Test
    void nonCubismAndUnreadableArtifactsRefuseBeforeInspection() throws Exception {
        final Path foreign =
                rawJar(Map.of("other/Type.class", classBytes("other/Type", "x")), Map.of(), List.of(), false);
        final Refused<?> notCubism = assertInstanceOf(
                Refused.class, ReviewedHostContract.resolve(foreign, List.of(candidate(REVIEWED_VERSION))));
        assertTrue(notCubism.reason().contains("NOT_CUBISM"));
        final Refused<?> unreadable = assertInstanceOf(
                Refused.class,
                ReviewedHostContract.resolve(tempDir.resolve("absent.jar"), List.of(candidate(REVIEWED_VERSION))));
        assertTrue(unreadable.reason().contains("UNREADABLE"));
    }

    @Test
    void candidatesMustPinAtLeastOneClass() {
        assertThrows(IllegalArgumentException.class, () -> new Candidate<>(REVIEWED_VERSION, "empty", Map.of()));
    }

    private Candidate<String> candidate(final String version) {
        return new Candidate<>(version, "feature-" + version, pinnedSha256(pinnedClasses()));
    }

    private static Map<String, byte[]> pinnedClasses() {
        final Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("feature/One.class", classBytes("feature/One", "one"));
        classes.put("feature/Two.class", classBytes("feature/Two", "two"));
        return classes;
    }

    private static Map<String, String> pinnedSha256(final Map<String, byte[]> classes) {
        final Map<String, String> pins = new LinkedHashMap<>();
        classes.forEach(
                (entry, bytes) -> pins.put(entry.substring(0, entry.length() - ".class".length()), sha256(bytes)));
        return pins;
    }

    private Path hostJar(
            final String version,
            final int build,
            final Map<String, byte[]> classes,
            final Map<String, byte[]> resources)
            throws IOException {
        return rawJar(classes, resources, List.of(declarationBytes("h", version, build)));
    }

    private Path hostJarWithDeclarationBytes(final byte[] declaration, final Map<String, byte[]> classes)
            throws IOException {
        final Path jar = Files.createTempFile(tempDir, "host-", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, ANCHOR, classBytes("com/live2d/cubism/CEAppCtrl", "anchor"));
            put(out, DECLARATION, declaration);
            for (final Map.Entry<String, byte[]> entry : classes.entrySet()) {
                put(out, entry.getKey(), entry.getValue());
            }
        }
        return jar;
    }

    private Path rawJar(
            final Map<String, byte[]> classes, final Map<String, byte[]> resources, final List<byte[]> declarations)
            throws IOException {
        return rawJar(classes, resources, declarations, true);
    }

    private Path rawJar(
            final Map<String, byte[]> classes,
            final Map<String, byte[]> resources,
            final List<byte[]> declarations,
            final boolean cubismAnchor)
            throws IOException {
        final Path jar = Files.createTempFile(tempDir, "host-", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            if (cubismAnchor) {
                put(out, ANCHOR, classBytes("com/live2d/cubism/CEAppCtrl", "anchor"));
            }
            for (final byte[] declaration : declarations) {
                put(out, DECLARATION, declaration);
            }
            for (final Map.Entry<String, byte[]> entry : classes.entrySet()) {
                put(out, entry.getKey(), entry.getValue());
            }
            for (final Map.Entry<String, byte[]> entry : resources.entrySet()) {
                put(out, entry.getKey(), entry.getValue());
            }
        }
        return jar;
    }

    private static void put(final JarOutputStream out, final String name, final byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static byte[] declarationBytes(final String internalName, final String version, final int build) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "com/live2d/cubism/" + internalName,
                null,
                "java/lang/Object",
                null);
        writer.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        "PRODUCT",
                        "Ljava/lang/String;",
                        null,
                        "Live2D Cubism Editor")
                .visitEnd();
        writer.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        "VERSION",
                        "Ljava/lang/String;",
                        null,
                        version)
                .visitEnd();
        writer.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        "DATE",
                        "Ljava/lang/String;",
                        null,
                        "2026/06/16")
                .visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "BUILD", "I", null, build)
                .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classBytes(final String internalName, final String marker) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null);
        writer.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        "MARKER",
                        "Ljava/lang/String;",
                        null,
                        marker)
                .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
