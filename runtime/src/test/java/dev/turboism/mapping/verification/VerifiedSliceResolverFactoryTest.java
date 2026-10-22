package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class VerifiedSliceResolverFactoryTest {
    @TempDir
    Path temporary;

    private static final Map<SliceDescriptor, String> RECORDS = Map.ofEntries(
            Map.entry(SliceRegistry.AUTO_BACKUP, "autobackup"),
            Map.entry(SliceRegistry.BOUNDING_BOX_OVERLAY_BUTTON, "ui-bounding-box-overlay"),
            Map.entry(SliceRegistry.CLIP_MASK, "clipmask"),
            Map.entry(SliceRegistry.CONTROL_APPEARANCE, "ui-control-appearance"),
            Map.entry(SliceRegistry.CORE_PUBLIC_API, "core-model-read"),
            Map.entry(SliceRegistry.EDITOR_MODEL, "editor-model"),
            Map.entry(SliceRegistry.EMBEDDED_PANEL, "ui-embedded-panel"),
            Map.entry(SliceRegistry.MAIN_TOOLBAR, "ui-main-toolbar"),
            Map.entry(SliceRegistry.PROJECT_WORKSPACE, "project-workspace"),
            Map.entry(SliceRegistry.PROTECTED_EXPORT, "protected-export"),
            Map.entry(SliceRegistry.STATUS_BAR, "ui-status-bar"),
            Map.entry(SliceRegistry.TOP_MENU, "ui-top-menu"),
            Map.entry(SliceRegistry.WORKSPACE_CONTROL, "workspace-control"));

    static Stream<SliceDescriptor> allDescriptors() {
        return SliceRegistry.all().stream();
    }

    @ParameterizedTest(name = "{index}: reviewed manifest, structural record and fail-closed factory")
    @MethodSource("allDescriptors")
    void preservesReviewedSliceContract(final SliceDescriptor descriptor) throws Exception {
        final Path root = EditorSelectorContractTestPaths.projectRoot();
        final Path record =
                root.resolve("build/generated/verification/cubism-5.2.03-" + RECORDS.get(descriptor) + ".json");
        final var loaded = new StaticVerificationRecordLoader().load(record);
        final var expected = loaded.record().artifact();
        final var manifest = descriptor.manifestFor(new HostArtifactDigest(expected.size(), expected.sha256()));
        final var workflow = new PinnedVerifiedResolverWorkflow();
        assertEquals(loaded.record().adapterSliceId(), manifest.adapterSliceId());
        assertEquals(loaded.record(), workflow.requireReviewedRecord(record, manifest));
        assertEquals(descriptor, SliceRegistry.find(descriptor.sliceId()).orElseThrow());

        // Exercise record rejection with the correct reviewed manifest, not a foreign JAR
        // that would short-circuit before inspecting the record.
        final Path tampered = temporary.resolve("tampered.json");
        final var mapper = new ObjectMapper();
        final var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(record.toFile());
        tree.put("verifiedBy", "self-issued-test");
        mapper.writeValue(tampered.toFile(), tree);
        assertEquals(
                "self-issued-test",
                workflow.requireReviewedRecord(tampered, manifest).verifiedBy());
        final Path otherRecord = root.resolve("build/generated/verification/cubism-5.2.03-"
                + (descriptor == SliceRegistry.CLIP_MASK ? "project-workspace" : "clipmask") + ".json");
        assertThrows(IllegalArgumentException.class, () -> workflow.requireReviewedRecord(otherRecord, manifest));

        final var factory = descriptor.resolverFactory();
        assertThrows(
                NoSuchFileException.class,
                () -> factory.create(
                        record, temporary.resolve("missing.jar"), getClass().getClassLoader()));
        final Path foreign = temporary.resolve("foreign.jar");
        Files.writeString(foreign, "not-a-reviewed-host-artifact");
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(record, foreign, getClass().getClassLoader()));
    }
}
