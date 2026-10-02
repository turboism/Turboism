package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MeshToolSessionSelectorContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> EXPECTED = Set.of(
            "cubism.editor-model.app-controller.instance",
            "cubism.editor-model.app-controller.current-document",
            "cubism.editor-model.modeling-document.last-active-view",
            "cubism.editor-model.modeling-view.class",
            "cubism.editor-model.view.complete-pack",
            "cubism.editor-model.complete-pack.main-view",
            "cubism.editor-model.widget.jcomponent",
            "cubism.editor-model.view.scene-graph",
            "cubism.editor-model.scene-graph.component-objects",
            "cubism.editor-model.entity.traverse-all",
            "cubism.editor-model.entity.enabled-in-hierarchy",
            "cubism.editor-model.gui-button.class",
            "cubism.editor-model.gui-icon-button.class",
            "cubism.editor-model.gui-button.on-component",
            "cubism.editor-model.gui-button.component-bounds",
            "cubism.editor-model.gui-bounds.contains",
            "cubism.editor-model.modeling-view.camera",
            "cubism.editor-model.modeling-view.current-view-mode",
            "cubism.editor-model.modeling-view.mode-current-form",
            "cubism.editor-model.modeling-view.mode-source",
            "cubism.editor-model.modeling-view.mode-image",
            "cubism.editor-model.modeling-view.model",
            "cubism.editor-model.model.get-object",
            "cubism.editor-model.art-mesh.class",
            "cubism.editor-model.art-mesh.source",
            "cubism.editor-model.art-mesh-source.positions",
            "cubism.editor-model.art-mesh-source.indices",
            "cubism.editor-model.parameter-controllable.calculated-form",
            "cubism.editor-model.art-mesh-form.positions",
            "cubism.editor-model.mesh-coordinate-converter.create",
            "cubism.editor-model.mesh-coordinate-converter.transform",
            "cubism.editor-model.camera.document-to-component",
            "cubism.editor-model.vector.create",
            "cubism.editor-model.vector.x",
            "cubism.editor-model.vector.y",
            "cubism.editor-model.mesh-editor.class",
            "cubism.editor-model.mesh-editor.start-mode",
            "cubism.editor-model.mesh-editor.end-mode",
            "cubism.editor-model.mesh-editor.document",
            "cubism.editor-model.mesh-editor.edit-data-for",
            "cubism.editor-model.mesh-edit-data.class",
            "cubism.editor-model.mesh-edit-data.art-mesh-source",
            "cubism.editor-model.mesh-edit-data.editable-mesh",
            "cubism.editor-model.editable-mesh.class",
            "cubism.editor-model.editable-mesh.selection",
            "cubism.editor-model.mesh-selection.class",
            "cubism.editor-model.mesh-selection.point-selector",
            "cubism.editor-model.point-selector.class",
            "cubism.editor-model.point-selector.selected-points",
            "cubism.editor-model.point-selector.clear",
            "cubism.editor-model.point-selector.add",
            "cubism.editor-model.editable-mesh.point-ref",
            "cubism.editor-model.point-ref.class",
            "cubism.editor-model.point-ref.mesh",
            "cubism.editor-model.point-ref.index",
            "cubism.editor-model.modeling-document.class",
            "cubism.editor-model.modeling-document.set-edit-mode",
            "cubism.editor-model.modeling-document.model-source",
            "cubism.editor-model.model-source.all-art-meshes",
            "cubism.editor-model.art-mesh-source.class",
            "cubism.editor-model.parameter-controllable-source.id",
            "cubism.editor-model.id.value",
            "cubism.editor-model.editable-mesh.coord-type",
            "cubism.editor-model.editable-mesh.point-count",
            "cubism.editor-model.editable-mesh.gl-positions");

    @Test
    void freezesTheCompleteAliasAndCapabilityContract() {
        assertEquals("cubism.mesh-tool.session", MeshToolSessionSelectorContract.CAPABILITY_ID);
        assertEquals(EXPECTED, MeshToolSessionSelectorContract.REQUIRED_ALIASES);
        assertEquals(Set.of("5.2.03", "5.3.02", "5.3.03"), MeshToolSessionSelectorContract.SUPPORTED_VERSIONS);
        assertEquals("adapter.editor-model.readwrite", MeshToolSessionSelectorContract.ADAPTER_SLICE_ID);
    }

    @Test
    void everyExactRecordAdvertisesAndContainsTheCompleteContract() throws Exception {
        for (String version : MeshToolSessionSelectorContract.SUPPORTED_VERSIONS) {
            final var record = JSON.readTree(Files.readString(
                    root().resolve("compatibility/cubism/verification/cubism-" + version + "-editor-model.json")));
            final Set<String> capabilities = strings(record.path("capabilityIds"));
            final Set<String> aliases = new HashSet<>();
            record.path("selectors")
                    .forEach(selector -> aliases.add(selector.path("alias").asText()));
            assertTrue(MeshToolSessionSelectorContract.isAdmitted(version, capabilities, aliases), version);
        }
    }

    @Test
    void unknownMissingAndDriftedInputsFailClosed() {
        assertFalse(MeshToolSessionSelectorContract.isAdmitted("5.4.00", Set.of("cubism.mesh-tool.session"), EXPECTED));
        assertFalse(MeshToolSessionSelectorContract.isAdmitted("5.3.03", Set.of(), EXPECTED));
        final Set<String> missing = new HashSet<>(EXPECTED);
        missing.remove(EXPECTED.iterator().next());
        assertFalse(MeshToolSessionSelectorContract.isAdmitted("5.3.03", Set.of("cubism.mesh-tool.session"), missing));
    }

    private static Set<String> strings(final com.fasterxml.jackson.databind.JsonNode array) {
        final Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Path root() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null && !Files.exists(cursor.resolve("settings.gradle.kts"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("project root not found");
        return cursor;
    }
}
