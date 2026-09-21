package dev.turboism.tests.plugin;

import dev.turboism.protocol.json.StrictJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FxMcpPromptContractTest {
    @Test
    void nativeEnumKindsUseThePublicSchemaSpelling() {
        for (String kind : List.of("PART", "ART_MESH", "WARP_DEFORMER", "ROTATION_DEFORMER")) {
            final String prompt = FxHostValidationProbe.mcpRenamePrompt(kind, "target", "name");
            final String json = prompt.substring(prompt.indexOf('{'), prompt.lastIndexOf('}') + 1);
            assertEquals(Map.of("operations", List.of(Map.of(
                "operation", "rename", "kind", kind.toLowerCase(java.util.Locale.ROOT),
                "id", "target", "name", "name"
            ))), StrictJson.parse(json.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void renameProbeUsesPublicToolWithOneExactlyEncodedOperation() {
        final String id = "Part\"头部\\42";
        final String name = "新名称\nwith \"quotes\"";
        final String prompt = FxHostValidationProbe.mcpRenamePrompt("part", id, name);
        assertTrue(prompt.contains("turboism.model_objects.apply exactly once"));
        assertFalse(prompt.contains("turboism_model_object_rename"));
        final String json = prompt.substring(prompt.indexOf('{'), prompt.lastIndexOf('}') + 1);
        final Object arguments = StrictJson.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(Map.of("operations", List.of(Map.of(
            "operation", "rename", "kind", "part", "id", id, "name", name
        ))), arguments);
    }
}
