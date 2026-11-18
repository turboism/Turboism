package dev.turboism.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.failure.RuntimeFailureSnapshot;
import dev.turboism.kernel.report.KernelReportDocuments;
import dev.turboism.kernel.report.KernelReportType;
import dev.turboism.kernel.report.KernelReportValidator;
import dev.turboism.mapping.verification.CompatibilityResolution;
import dev.turboism.mapping.verification.CubismHostIdentity;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.HostIdentityProbe;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class KernelRuntimeEditorReleaseAdmissionTest {

    private static final String COMPATIBLE_FIXTURE_VERSION = "5.3.99";
    private static final int COMPATIBLE_FIXTURE_BUILD = 503990001;
    private static final String FIXTURE_PRODUCT = "Live2D Cubism Editor";
    private static final String FIXTURE_DECLARATION = "com/live2d/cubism/h";
    private static final String SOURCE_RECORD = "project-workspace.json";
    private static final String SOURCE_VERSION = "5.3.04";
    private static final String SOURCE_VERSION_FIELD = "cubismVersion";
    private static final String FIXTURE_ADMISSION_DETAIL = "fixture admitted";
    private static final String FIXTURE_RUNTIME_ID = "runtime-compatible-host";

    @TempDir
    Path tempDir;

    @Test
    void exactArtifactAndDeclarationMustAgreeBeforeRuntimeStartup() throws Exception {
        final Path declared5303 = editorJar("5.3.03", 503030001);

        final IllegalStateException unreviewedArtifact = assertThrows(
                IllegalStateException.class, () -> KernelHostAdmission.requireReviewedEditorRelease(declared5303));
        assertEquals(
                "Cubism host artifact is not an exact reviewed identity; admission failed closed",
                unreviewedArtifact.getMessage());
    }

    @Test
    void releaseMismatchFailsClosedEvenWhenDigestLookupIsReviewed() {
        final String reviewed = ReviewedHostArtifacts.cubismVersionOf(ReviewedHostArtifacts.CUBISM_5_3_03)
                .orElseThrow();
        final String declared = "5.3.02";

        final IllegalStateException mismatch = assertThrows(
                IllegalStateException.class, () -> KernelHostAdmission.requireReleaseAgreement(reviewed, declared));
        assertEquals("Cubism host release/artifact mismatch; admission failed closed", mismatch.getMessage());
    }

    @Test
    void exactReviewed5303AgreementOpensFullRuntimeAdmission() {
        assertEquals("5.3.03", KernelHostAdmission.requireReleaseAgreement("5.3.03", "5.3.03"));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    @Test
    void compatibilityAdmissionRejectsAnArtifactChangedSinceIdentityProbe() throws Exception {
        final Path artifact = editorJar("5.3.02", 503020001);
        final var identity = new dev.turboism.mapping.verification.CubismHostIdentity(
                "Live2D Cubism Editor",
                "5.3.02",
                java.util.Optional.empty(),
                503020001,
                "com/live2d/cubism/h",
                dev.turboism.mapping.verification.HostArtifactDigest.from(artifact));
        final var resolution = dev.turboism.mapping.verification.CompatibilityResolution.of(
                dev.turboism.mapping.verification.CompatibilityResolution.Mode.COMPATIBLE,
                new dev.turboism.mapping.verification.HostIdentityProbe(
                        dev.turboism.mapping.verification.HostIdentityProbe.Status.DECLARED,
                        java.util.Optional.of(identity),
                        "fixture identity"),
                java.util.Map.of(),
                true,
                "fixture admitted");
        assertEquals("5.3.02", KernelHostAdmission.requireAdmittedEditorRelease(artifact, resolution));

        Files.copy(editorJar("5.3.99", 503990001), artifact, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThrows(
                IllegalStateException.class,
                () -> KernelHostAdmission.requireAdmittedEditorRelease(artifact, resolution),
                "a previously admitted version cannot authorize a replaced artifact");
    }

    @Test
    void compatibleRuntimeReportUsesTheDeclaredVersion() throws Exception {
        final Path artifact = editorJar(COMPATIBLE_FIXTURE_VERSION, COMPATIBLE_FIXTURE_BUILD);
        final var identity = new CubismHostIdentity(
                FIXTURE_PRODUCT,
                COMPATIBLE_FIXTURE_VERSION,
                Optional.empty(),
                COMPATIBLE_FIXTURE_BUILD,
                FIXTURE_DECLARATION,
                HostArtifactDigest.from(artifact));
        final var resolution = CompatibilityResolution.of(
                CompatibilityResolution.Mode.COMPATIBLE,
                new HostIdentityProbe(
                        HostIdentityProbe.Status.DECLARED, Optional.of(identity), FIXTURE_ADMISSION_DETAIL),
                Map.of(),
                true,
                FIXTURE_ADMISSION_DETAIL);
        final Path record = tempDir.resolve(SOURCE_RECORD);
        KernelReportDocuments.json()
                .writeValue(
                        record.toFile(),
                        KernelReportDocuments.json().createObjectNode().put(SOURCE_VERSION_FIELD, SOURCE_VERSION));

        final var report = KernelReportSnapshotFactory.create(
                        FIXTURE_RUNTIME_ID,
                        Instant.EPOCH,
                        tempDir,
                        HostSession.State.ACTIVE,
                        artifact,
                        record,
                        new LocalPluginRuntime.LoadReport(List.of(), List.of(), List.of()),
                        List.of(),
                        RuntimeFailureSnapshot.empty(),
                        false,
                        false,
                        resolution)
                .get(KernelReportType.PREVIEW_RUNTIME);

        final var host = KernelReportValidator.validate(
                        KernelReportDocuments.json().writeValueAsBytes(report))
                .document()
                .path("payload")
                .path("host");
        assertEquals(COMPATIBLE_FIXTURE_VERSION, host.path("version").textValue());
        assertEquals(
                CompatibilityResolution.Mode.COMPATIBLE.name(),
                host.path("identityState").textValue());
    }

    @Test
    void exactModeStillRequiresBaseCapabilityAndTheObservedArtifactSnapshot() throws Exception {
        final Path artifact = editorJar("5.3.02", 503020001);
        final var identity = new dev.turboism.mapping.verification.CubismHostIdentity(
                "Live2D Cubism Editor",
                "5.3.02",
                java.util.Optional.empty(),
                503020001,
                "com/live2d/cubism/h",
                dev.turboism.mapping.verification.HostArtifactDigest.from(artifact));
        final var probe = new dev.turboism.mapping.verification.HostIdentityProbe(
                dev.turboism.mapping.verification.HostIdentityProbe.Status.DECLARED,
                java.util.Optional.of(identity),
                "fixture identity");
        final var missingBase = dev.turboism.mapping.verification.CompatibilityResolution.of(
                dev.turboism.mapping.verification.CompatibilityResolution.Mode.VERIFIED,
                probe,
                java.util.Map.of(),
                false,
                "missing records");

        assertEquals(
                "Cubism admission resolved no base runtime capability",
                assertThrows(
                                IllegalStateException.class,
                                () -> KernelHostAdmission.requireAdmittedEditorRelease(artifact, missingBase))
                        .getMessage());

        final var admitted = dev.turboism.mapping.verification.CompatibilityResolution.of(
                dev.turboism.mapping.verification.CompatibilityResolution.Mode.VERIFIED,
                probe,
                java.util.Map.of(),
                true,
                "fixture admitted");
        Files.copy(editorJar("5.3.99", 503990001), artifact, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(
                "Cubism host artifact changed since compatibility probing",
                assertThrows(
                                IllegalStateException.class,
                                () -> KernelHostAdmission.requireAdmittedEditorRelease(artifact, admitted))
                        .getMessage());
    }

    private Path editorJar(final String version, final int build) throws Exception {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "com/live2d/cubism/h",
                null,
                "java/lang/Object",
                null);
        addString(writer, "PRODUCT", "Live2D Cubism Editor");
        addString(writer, "VERSION", version);
        addString(writer, "DATE", "2026/06/16");
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "BUILD", "I", null, build)
                .visitEnd();
        writer.visitEnd();

        final Path jar = Files.createTempFile(tempDir, "editor-release-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("com/live2d/cubism/h.class"));
            output.write(writer.toByteArray());
            output.closeEntry();
        }
        return jar;
    }

    private static void addString(final ClassWriter writer, final String name, final String value) {
        writer.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        name,
                        "Ljava/lang/String;",
                        null,
                        value)
                .visitEnd();
    }
}
