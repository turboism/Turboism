package dev.turboism.ui.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MeshToolbarSelectorContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> EXPECTED = Set.of(
            "cubism.ui-main-toolbar.mesh-tool-mode.instance",
            "cubism.ui-main-toolbar.mesh-tool-mode.tool-panel",
            "cubism.ui-main-toolbar.mesh-tool-panel.arrow-button",
            "cubism.ui-main-toolbar.mesh-tool-panel.panel",
            "cubism.ui-main-toolbar.abstract-button.class",
            "cubism.ui-main-toolbar.abstract-button.get-jabstract-button",
            "cubism.ui-main-toolbar.abstract-button.set-selected",
            "cubism.ui-main-toolbar.abstract-button.set-icon",
            "cubism.ui-main-toolbar.abstract-button.set-pressed-icon",
            "cubism.ui-main-toolbar.abstract-button.set-selected-icon",
            "cubism.ui-main-toolbar.abstract-button.set-disabled-icon",
            "cubism.ui-main-toolbar.abstract-button.set-disabled-selected-icon",
            "cubism.ui-main-toolbar.icon-button.create",
            "cubism.ui-main-toolbar.icon-button.set-rollover-icon",
            "cubism.ui-main-toolbar.icon.class",
            "cubism.ui-main-toolbar.icon.create",
            "cubism.ui-main-toolbar.widget.class",
            "cubism.ui-main-toolbar.widget.parent",
            "cubism.ui-main-toolbar.widget.name",
            "cubism.ui-main-toolbar.widget.set-name",
            "cubism.ui-main-toolbar.widget.set-tooltip",
            "cubism.ui-main-toolbar.widget.set-pref-width",
            "cubism.ui-main-toolbar.widget.set-pref-height",
            "cubism.ui-main-toolbar.widget.revalidate",
            "cubism.ui-main-toolbar.widget.repaint",
            "cubism.ui-main-toolbar.container.class",
            "cubism.ui-main-toolbar.container.children",
            "cubism.ui-main-toolbar.container.add",
            "cubism.ui-main-toolbar.container.remove",
            "cubism.ui-main-toolbar.slider.class",
            "cubism.ui-main-toolbar.slider.create",
            "cubism.ui-main-toolbar.slider.value",
            "cubism.ui-main-toolbar.slider.set-value",
            "cubism.ui-main-toolbar.slider.min",
            "cubism.ui-main-toolbar.slider.set-min",
            "cubism.ui-main-toolbar.slider.max",
            "cubism.ui-main-toolbar.slider.set-max",
            "cubism.ui-main-toolbar.slider.set-on-changed");

    @Test
    void freezesTheCompleteAliasAndCapabilityContract() {
        assertEquals("ui.mesh-toolbar.contribute", MeshToolbarSelectorContract.CAPABILITY_ID);
        assertEquals(EXPECTED, MeshToolbarSelectorContract.REQUIRED_ALIASES);
        assertEquals(Set.of("5.2.03", "5.3.02", "5.3.03"), MeshToolbarSelectorContract.SUPPORTED_VERSIONS);
        assertEquals("adapter.editor-ui.main-toolbar", MeshToolbarSelectorContract.ADAPTER_SLICE_ID);
    }

    @Test
    void everyExactRecordAdvertisesAndContainsTheCompleteContract() throws Exception {
        for (String version : MeshToolbarSelectorContract.SUPPORTED_VERSIONS) {
            final var record = JSON.readTree(Files.readString(
                    root().resolve("compatibility/cubism/verification/cubism-" + version + "-ui-main-toolbar.json")));
            final Set<String> capabilities = strings(record.path("capabilityIds"));
            final Set<String> aliases = new HashSet<>();
            record.path("selectors")
                    .forEach(selector -> aliases.add(selector.path("alias").asText()));
            assertTrue(MeshToolbarSelectorContract.isAdmitted(version, capabilities, aliases), version);
        }
    }

    @Test
    void unknownMissingAndDriftedInputsFailClosed() {
        assertFalse(MeshToolbarSelectorContract.isAdmitted("5.4.00", Set.of("ui.mesh-toolbar.contribute"), EXPECTED));
        assertFalse(MeshToolbarSelectorContract.isAdmitted("5.3.03", Set.of(), EXPECTED));
        final Set<String> missing = new HashSet<>(EXPECTED);
        missing.remove(EXPECTED.iterator().next());
        assertFalse(MeshToolbarSelectorContract.isAdmitted("5.3.03", Set.of("ui.mesh-toolbar.contribute"), missing));
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
