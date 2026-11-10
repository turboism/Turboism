package dev.turboism.plugin.physicseditor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.json.Json;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The manifest must declare the permissions its declared capabilities are gated on.
 *
 * <p>A capability is admitted by name, but each call is still permission-checked by the runtime. A
 * plugin that declares {@code cubism.preview.capture} without {@code turboism.ui.viewport.read} is
 * therefore admitted and then denied on every call: the preview never resolves, the control strip
 * never appears, and the only trace is a WARNING audit event per poll. This test pins the pairing so
 * the manifest cannot drift away from the runtime's admission rule again.</p>
 */
class PhysicsEditorManifestTest {

    @Test
    void previewCaptureCapabilityDeclaresItsRequiredPermissions() throws Exception {
        final Map<String, ?> manifest = readManifest();
        final List<?> capabilities = (List<?>) manifest.get("capabilities");
        assertNotNull(capabilities, "the manifest must declare capabilities");
        assertTrue(capabilities.contains("cubism.preview.capture"), "the plugin drives the preview capture capability");

        final List<?> permissions = (List<?>) manifest.get("permissions");
        assertNotNull(permissions, "the manifest must declare permissions");
        final List<String> ids = permissions.stream()
                .map(entry -> String.valueOf(((Map<?, ?>) entry).get("id")))
                .toList();

        // CapabilityAdmission.CUBISM_PREVIEW_FRAMES requires exactly this permission.
        assertTrue(
                ids.contains("turboism.ui.viewport.read"),
                "cubism.preview.capture is gated on turboism.ui.viewport.read; without it every capture "
                        + "is denied and the GIF control strip never appears. Declared: " + ids);
    }

    @Test
    void gifsicleOptimizationDeclaresProcessExecution() throws Exception {
        final List<?> permissions = (List<?>) readManifest().get("permissions");
        final List<String> ids = permissions.stream()
                .map(entry -> String.valueOf(((Map<?, ?>) entry).get("id")))
                .toList();
        assertTrue(
                ids.contains("turboism.process.run"),
                "the optimizer runs a user-supplied executable; declared: " + ids);
    }

    private static Map<String, ?> readManifest() throws Exception {
        try (InputStream stream =
                PhysicsEditorManifestTest.class.getClassLoader().getResourceAsStream("META-INF/turboism/plugin.json")) {
            assertNotNull(stream, "missing plugin.json");
            return Json.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
