package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticSelectorVerifierTest {

    @Test
    void verifiesMethodNameAndDescriptorFromJarMetadata() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);
        StaticSelector selector = StaticSelector.method(
            "sample.greet",
            internalName(SampleTarget.class),
            "greet",
            "(Ljava/lang/String;)Ljava/lang/String;"
        );

        StaticVerificationReport report = new StaticSelectorVerifier().verify(
            artifact,
            fingerprint,
            List.of(selector)
        );

        assertTrue(report.artifactMatched());
        assertEquals(StaticVerificationStatus.VERIFIED_STATIC, report.results().get(0).status());
        assertEquals("sample.greet", report.results().get(0).alias());
    }

    @Test
    void rejectsSameMethodNameWithWrongDescriptor() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier().verify(
            artifact,
            fingerprint,
            List.of(StaticSelector.method(
                "sample.greet",
                internalName(SampleTarget.class),
                "greet",
                "()Ljava/lang/String;"
            ))
        );

        assertEquals(StaticVerificationStatus.DESCRIPTOR_MISMATCH, report.results().get(0).status());
    }

    @Test
    void rejectsInstanceMethodWhenSelectorRequiresStatic() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier().verify(
            artifact,
            fingerprint,
            List.of(StaticSelector.staticMethod(
                "sample.greet.static",
                internalName(SampleTarget.class),
                "greet",
                "(Ljava/lang/String;)Ljava/lang/String;",
                0
            ))
        );

        assertEquals(StaticVerificationStatus.ACCESS_MISMATCH, report.results().get(0).status());
    }

    @Test
    void rejectsSelectorsBeforeParsingWhenArtifactHashDiffers() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint actual = HostArtifactFingerprint.from("5.3.02", artifact);
        HostArtifactFingerprint wrong = new HostArtifactFingerprint(
            actual.cubismVersion(),
            actual.size(),
            "0".repeat(64)
        );

        StaticVerificationReport report = new StaticSelectorVerifier().verify(
            artifact,
            wrong,
            List.of(StaticSelector.method(
                "sample.greet",
                internalName(SampleTarget.class),
                "greet",
                "(Ljava/lang/String;)Ljava/lang/String;"
            ))
        );

        assertFalse(report.artifactMatched());
        assertEquals(StaticVerificationStatus.ARTIFACT_MISMATCH, report.results().get(0).status());
    }

    @Test
    void reportsMissingClassWithoutLoadingHostTypes() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);

        StaticVerificationReport report = new StaticSelectorVerifier().verify(
            artifact,
            fingerprint,
            List.of(StaticSelector.classSelector("missing.class", "missing/HostClass"))
        );

        assertEquals(StaticVerificationStatus.CLASS_MISSING, report.results().get(0).status());
    }

    @Test
    void rejectsDuplicateAliasesDeterministically() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);
        StaticSelector selector = StaticSelector.classSelector("duplicate.alias", internalName(SampleTarget.class));

        StaticVerificationReport report = new StaticSelectorVerifier().verify(
            artifact,
            fingerprint,
            List.of(selector, selector)
        );

        assertEquals(2, report.results().size());
        assertTrue(report.results().stream().allMatch(
            result -> result.status() == StaticVerificationStatus.DUPLICATE_ALIAS
        ));
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

    static final class SampleTarget {
        String greet(final String name) {
            return "Hello " + name;
        }
    }
}
