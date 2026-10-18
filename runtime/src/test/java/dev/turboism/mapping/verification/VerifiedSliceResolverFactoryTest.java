package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class VerifiedSliceResolverFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String> SAMPLE_RECORDS = Map.ofEntries(
            Map.entry(SliceRegistry.AUTO_BACKUP.sliceId(), "cubism-5.3.03-autobackup.json"),
            Map.entry(
                    SliceRegistry.BOUNDING_BOX_OVERLAY_BUTTON.sliceId(), "cubism-5.3.03-ui-bounding-box-overlay.json"),
            Map.entry(SliceRegistry.CLIP_MASK.sliceId(), "cubism-5.3.03-clipmask.json"),
            Map.entry(SliceRegistry.CONTROL_APPEARANCE.sliceId(), "cubism-5.3.03-ui-control-appearance.json"),
            Map.entry(SliceRegistry.CORE_PUBLIC_API.sliceId(), "cubism-5.3.03-core-model-read.json"),
            Map.entry(SliceRegistry.EDITOR_MODEL.sliceId(), "cubism-5.3.03-editor-model.json"),
            Map.entry(SliceRegistry.EMBEDDED_PANEL.sliceId(), "cubism-5.3.03-ui-embedded-panel.json"),
            Map.entry(SliceRegistry.MAIN_TOOLBAR.sliceId(), "cubism-5.3.03-ui-main-toolbar.json"),
            Map.entry(SliceRegistry.PROJECT_WORKSPACE.sliceId(), "cubism-5.3.03-project-workspace.json"),
            Map.entry(SliceRegistry.PROTECTED_EXPORT.sliceId(), "cubism-5.3.03-protected-export.json"),
            Map.entry(SliceRegistry.STATUS_BAR.sliceId(), "cubism-5.3.03-ui-status-bar.json"),
            Map.entry(SliceRegistry.TOP_MENU.sliceId(), "cubism-5.3.03-ui-top-menu.json"),
            Map.entry(SliceRegistry.WORKSPACE_CONTROL.sliceId(), "cubism-5.3.03-workspace-control.json"));

    static Stream<SliceDescriptor> allDescriptors() {
        return SliceRegistry.all().stream();
    }

    @ParameterizedTest
    @MethodSource("allDescriptors")
    void rejectsMissingArtifactBeforeReadingAnything(final SliceDescriptor descriptor) {
        final Path record = sampleRecord(descriptor);
        assertThrows(
                NoSuchFileException.class,
                () -> descriptor
                        .resolverFactory()
                        .create(
                                record,
                                Path.of("missing-host-artifact.jar"),
                                getClass().getClassLoader()));
    }

    @ParameterizedTest
    @MethodSource("allDescriptors")
    void rejectsUnreviewedArtifact(final SliceDescriptor descriptor) throws IOException {
        final Path record = sampleRecord(descriptor);
        final Path foreign = Files.createTempFile("foreign-host-artifact", ".jar");
        Files.writeString(foreign, "not-a-reviewed-host-artifact");
        assertThrows(
                IllegalArgumentException.class,
                () -> descriptor
                        .resolverFactory()
                        .create(record, foreign, getClass().getClassLoader()));
    }

    @ParameterizedTest
    @MethodSource("allDescriptors")
    void rejectsSelfIssuedRecordBeforeUsingItsSelectors(final SliceDescriptor descriptor) throws Exception {
        final Path reviewed = sampleRecord(descriptor);
        final Path selfIssued = Files.createTempFile("self-issued-record", ".json");
        final var root = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(reviewed.toFile());
        root.put("verifiedBy", "self-issued-test");
        MAPPER.writeValue(selfIssued.toFile(), root);
        final Path foreign = Files.createTempFile("foreign-host-artifact", ".jar");
        Files.writeString(foreign, "not-a-reviewed-host-artifact");
        assertThrows(
                IllegalArgumentException.class,
                () -> descriptor
                        .resolverFactory()
                        .create(selfIssued, foreign, getClass().getClassLoader()));
    }

    @ParameterizedTest
    @MethodSource("allDescriptors")
    void rejectsRecordForDifferentSlice(final SliceDescriptor descriptor) throws IOException {
        final String foreignRecordName = descriptor.sliceId().equals(SliceRegistry.PROJECT_WORKSPACE.sliceId())
                ? "cubism-5.3.03-clipmask.json"
                : "cubism-5.3.03-project-workspace.json";
        final Path foreignRecord = repositoryPath("compatibility/cubism/verification/" + foreignRecordName);
        final Path foreignArtifact = Files.createTempFile("foreign-artifact", ".jar");
        Files.writeString(foreignArtifact, "not-a-reviewed-host-artifact");
        assertThrows(
                IllegalArgumentException.class,
                () -> descriptor
                        .resolverFactory()
                        .create(foreignRecord, foreignArtifact, getClass().getClassLoader()));
    }

    private static Path sampleRecord(final SliceDescriptor descriptor) {
        final String fileName = SAMPLE_RECORDS.get(descriptor.sliceId());
        if (fileName == null) {
            throw new IllegalArgumentException("no sample record for slice: " + descriptor.sliceId());
        }
        return repositoryPath("compatibility/cubism/verification/" + fileName);
    }

    private static Path repositoryPath(final String relative) {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root not found");
        }
        return root.resolve(relative);
    }
}
