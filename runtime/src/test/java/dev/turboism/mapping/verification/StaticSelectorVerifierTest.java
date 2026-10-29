package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

class StaticSelectorVerifierTest {

    @Test
    void verifiesMethodNameAndDescriptorFromJarMetadata() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);
        StaticSelector selector = StaticSelector.method(
                "sample.greet", internalName(SampleTarget.class), "greet", "(Ljava/lang/String;)Ljava/lang/String;");

        StaticVerificationReport report = new StaticSelectorVerifier().verify(artifact, fingerprint, List.of(selector));

        assertTrue(report.artifactMatched());
        assertEquals(
                StaticVerificationStatus.VERIFIED_STATIC,
                report.results().get(0).status());
        assertEquals("sample.greet", report.results().get(0).alias());
    }

    @Test
    void verifiesConstructorDescriptorFromJarMetadata() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier()
                .verify(
                        artifact,
                        fingerprint,
                        List.of(StaticSelector.constructor(
                                "sample.constructor", internalName(SampleTarget.class), "(Ljava/lang/String;)V", 0)));

        assertEquals(
                StaticVerificationStatus.VERIFIED_STATIC,
                report.results().get(0).status());
    }

    @Test
    void rejectsSameMethodNameWithWrongDescriptor() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier()
                .verify(
                        artifact,
                        fingerprint,
                        List.of(StaticSelector.method(
                                "sample.greet", internalName(SampleTarget.class), "greet", "()Ljava/lang/String;")));

        assertEquals(
                StaticVerificationStatus.DESCRIPTOR_MISMATCH,
                report.results().get(0).status());
    }

    @Test
    void rejectsInstanceMethodWhenSelectorRequiresStatic() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier()
                .verify(
                        artifact,
                        fingerprint,
                        List.of(StaticSelector.staticMethod(
                                "sample.greet.static",
                                internalName(SampleTarget.class),
                                "greet",
                                "(Ljava/lang/String;)Ljava/lang/String;",
                                0)));

        assertEquals(
                StaticVerificationStatus.ACCESS_MISMATCH,
                report.results().get(0).status());
    }

    @Test
    void rejectsSelectorsBeforeParsingWhenArtifactHashDiffers() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint actual = HostArtifactFingerprint.from("5.3.02", artifact);
        HostArtifactFingerprint wrong =
                new HostArtifactFingerprint(actual.cubismVersion(), actual.size(), "0".repeat(64));

        StaticVerificationReport report = new StaticSelectorVerifier()
                .verify(
                        artifact,
                        wrong,
                        List.of(StaticSelector.method(
                                "sample.greet",
                                internalName(SampleTarget.class),
                                "greet",
                                "(Ljava/lang/String;)Ljava/lang/String;")));

        assertFalse(report.artifactMatched());
        assertEquals(
                StaticVerificationStatus.ARTIFACT_MISMATCH,
                report.results().get(0).status());
    }

    @Test
    void reportsMissingClassWithoutLoadingHostTypes() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier()
                .verify(
                        artifact,
                        fingerprint,
                        List.of(StaticSelector.classSelector("missing.class", "missing/HostClass")));

        assertEquals(
                StaticVerificationStatus.CLASS_MISSING, report.results().get(0).status());
    }

    @Test
    void rejectsDuplicateAliasesDeterministically() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);
        StaticSelector selector = StaticSelector.classSelector("duplicate.alias", internalName(SampleTarget.class));

        StaticVerificationReport report =
                new StaticSelectorVerifier().verify(artifact, fingerprint, List.of(selector, selector));

        assertEquals(2, report.results().size());
        assertTrue(report.results().stream()
                .allMatch(result -> result.status() == StaticVerificationStatus.DUPLICATE_ALIAS));
    }

    @Test
    void verifiesDeclaredSuperclassFromJarMetadata() throws Exception {
        Path artifact = jarContaining(DerivedTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier()
                .verify(
                        artifact,
                        fingerprint,
                        List.of(StaticSelector.inheritsFrom(
                                "sample.mapping.inherits-superclass",
                                "sample.inherits.superclass",
                                internalName(DerivedTarget.class),
                                internalName(SampleTarget.class))));

        assertEquals(
                StaticVerificationStatus.VERIFIED_STATIC,
                report.results().get(0).status());
    }

    @Test
    void verifiesDeclaredInterfaceFromJarMetadata() throws Exception {
        Path artifact = jarContaining(DerivedTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier()
                .verify(
                        artifact,
                        fingerprint,
                        List.of(StaticSelector.inheritsFrom(
                                "sample.mapping.inherits-interface",
                                "sample.inherits.interface",
                                internalName(DerivedTarget.class),
                                internalName(SampleContract.class))));

        assertEquals(
                StaticVerificationStatus.VERIFIED_STATIC,
                report.results().get(0).status());
    }

    @Test
    void rejectsWrongSuperclassFromJarMetadata() throws Exception {
        Path artifact = jarContaining(DerivedTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier()
                .verify(
                        artifact,
                        fingerprint,
                        List.of(StaticSelector.inheritsFrom(
                                "sample.mapping.inherits-wrong-superclass",
                                "sample.inherits.wrong-superclass",
                                internalName(DerivedTarget.class),
                                "java/lang/String")));

        assertEquals(
                StaticVerificationStatus.SUPERTYPE_MISMATCH,
                report.results().get(0).status());
    }

    @Test
    void rejectsUndeclaredInterfaceFromJarMetadata() throws Exception {
        Path artifact = jarContaining(DerivedTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier()
                .verify(
                        artifact,
                        fingerprint,
                        List.of(StaticSelector.inheritsFrom(
                                "sample.mapping.inherits-missing-interface",
                                "sample.inherits.missing-interface",
                                internalName(DerivedTarget.class),
                                "java/io/Closeable")));

        assertEquals(
                StaticVerificationStatus.SUPERTYPE_MISMATCH,
                report.results().get(0).status());
    }

    @Test
    void reportsMissingOwnerForInheritsSelector() throws Exception {
        Path artifact = jarContaining(DerivedTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier()
                .verify(
                        artifact,
                        fingerprint,
                        List.of(StaticSelector.inheritsFrom(
                                "sample.mapping.inherits-missing-owner",
                                "sample.inherits.missing-owner",
                                "missing/HostClass",
                                internalName(SampleTarget.class))));

        assertEquals(
                StaticVerificationStatus.CLASS_MISSING, report.results().get(0).status());
    }

    @Test
    void standaloneVerifyReadsReplacedFileDigestWithoutCachingOldState() throws Exception {
        final Path artifact = jarContaining(SampleTarget.class);
        final HostArtifactFingerprint fingerprint1 = HostArtifactFingerprint.from("5.3.02", artifact);
        final StaticSelector selector = StaticSelector.method(
                "sample.greet", internalName(SampleTarget.class), "greet", "(Ljava/lang/String;)Ljava/lang/String;");

        final StaticSelectorVerifier verifier = new StaticSelectorVerifier();
        final var report1 = verifier.verify(artifact, fingerprint1, List.of(selector));
        assertTrue(report1.artifactMatched());

        final Path secondJar = jarContaining(DerivedTarget.class);
        Files.copy(secondJar, artifact, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        final HostArtifactFingerprint fingerprint2 = HostArtifactFingerprint.from("5.3.02", artifact);
        org.junit.jupiter.api.Assertions.assertNotEquals(fingerprint1.sha256(), fingerprint2.sha256());

        final var report2 = verifier.verify(artifact, fingerprint2, List.of(selector));
        assertTrue(report2.artifactMatched());
        assertEquals(fingerprint2.sha256(), report2.actualFingerprint().sha256());
    }

    @Test
    void admissionSessionReusesArtifactMetadataAndClosesJar() throws Exception {
        final Path artifact = jarContaining(SampleTarget.class);
        final StaticSelector selector = StaticSelector.method(
                "sample.greet", internalName(SampleTarget.class), "greet", "(Ljava/lang/String;)Ljava/lang/String;");

        final StaticSelectorVerifier.AdmissionSession session = new StaticSelectorVerifier.AdmissionSession();
        final var report1 = session.verifyStructure(artifact, List.of(selector));
        assertTrue(report1.allVerified());

        final StaticSelectorVerifier.CachedArtifact cached = session.get(artifact);
        org.junit.jupiter.api.Assertions.assertNotNull(cached);
        org.junit.jupiter.api.Assertions.assertSame(
                cached, session.get(artifact), "same CachedArtifact must be returned for same path");

        final var report2 = session.verifyStructure(artifact, List.of(selector));
        assertTrue(report2.allVerified());
        assertEquals(report1.artifact(), report2.artifact());
        assertFalse(cached.metadataCache.isEmpty(), "class metadata must be populated in cache");

        session.close();
        assertThrows(IllegalStateException.class, () -> cached.jar.getJarEntry("sample/SampleTarget.class"));
    }

    @Test
    void admissionSessionDetectsSameSizeContentReplacement() throws Exception {
        final Path artifact = jarContaining(SampleTarget.class);
        final StaticSelector selector = StaticSelector.method(
                "sample.greet", internalName(SampleTarget.class), "greet", "(Ljava/lang/String;)Ljava/lang/String;");

        try (StaticSelectorVerifier.AdmissionSession session = new StaticSelectorVerifier.AdmissionSession()) {
            final var report = session.verifyStructure(artifact, List.of(selector));
            assertTrue(report.allVerified());

            final long originalSize = Files.size(artifact);
            final var mtime = Files.getLastModifiedTime(artifact);
            final byte[] bytes = Files.readAllBytes(artifact);
            bytes[bytes.length - 1] ^= 0x55;
            Files.write(artifact, bytes);
            assertEquals(originalSize, Files.size(artifact));
            Files.setLastModifiedTime(artifact, mtime);

            final IllegalStateException failure =
                    assertThrows(IllegalStateException.class, session::verifyAllUnchanged);
            assertTrue(failure.getMessage().contains("host artifact changed"));
        }
    }

    private static Path jarContaining(final Class<?> type) throws Exception {
        Path jar = Files.createTempFile("turboism-selector-fixture", ".jar");
        String entryName = internalName(type) + ".class";
        try (InputStream input = type.getClassLoader().getResourceAsStream(entryName);
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static String internalName(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    static class SampleTarget {
        SampleTarget(final String ignored) {}

        String greet(final String name) {
            return "Hello " + name;
        }
    }

    interface SampleContract {
        String contract();
    }

    static final class DerivedTarget extends SampleTarget implements SampleContract {
        DerivedTarget() {
            super("derived");
        }

        @Override
        public String contract() {
            return "derived";
        }
    }
}
