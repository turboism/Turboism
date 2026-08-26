package dev.turboism.tests.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the PSD clip-mask import plugin descriptor to its effect contract. */
class PsdClipMaskImportPluginDescriptorTest {

    private static final Path PROJECT_ROOT = Paths.get(
        System.getProperty("projectRoot", System.getProperty("user.dir"))
    );
    private static final Path DESCRIPTOR = PROJECT_ROOT.resolve(
        "plugins/psd-clip-mask-import/src/main/resources/META-INF/turboism/plugin.json"
    );
    private static final Path PSD_IMPORT_SHELL_DESCRIPTOR = PROJECT_ROOT.resolve(
        "plugins/psd-import/src/main/resources/META-INF/turboism/plugin.json"
    );

    private static final Set<String> REQUIRED_PERMISSIONS = Set.of(
        "turboism.cubism.model.read",
        "turboism.cubism.model.write",
        "turboism.action.register",
        "turboism.ui.panel.contribute",
        "turboism.ui.dialog.contribute",
        "turboism.ui.status.notify"
    );

    private static final Set<String> REQUIRED_CAPABILITIES = Set.of(
        "cubism.psd.layer-relationship.read",
        "cubism.clipmask.read",
        "ui.dialog.contribute",
        "ui.status.notify",
        "cubism.clipmask.replace-ordered-sources",
        "cubism.transaction.real-write-undo",
        "ui.embedded-panel.contribute"
    );

    @Test
    void descriptorDeclaresTheEffectContractIdentityAndEntrypoint() throws Exception {
        final JsonNode root = new ObjectMapper().readTree(Files.readString(DESCRIPTOR));

        assertEquals("dev.turboism.plugin.psd-clip-mask-import", root.path("id").asText());
        assertEquals(
            "dev.turboism.plugin.psdclipmaskimport.PsdClipMaskImportPlugin",
            root.path("entrypoints").get(0).asText()
        );
        assertTrue(root.path("environment").path("requiresCubism").asBoolean());
        assertEquals("embedded", root.path("environment").path("ui").asText());
    }

    @Test
    void descriptorDeclaresEveryActualPermission() throws Exception {
        final JsonNode root = new ObjectMapper().readTree(Files.readString(DESCRIPTOR));
        final Set<String> declared = new HashSet<>();
        for (JsonNode permission : root.path("permissions")) {
            declared.add(permission.path("id").asText());
        }

        assertEquals(REQUIRED_PERMISSIONS, declared);
    }

    @Test
    void descriptorDemandsTheEffectContractOperationCapabilities() throws Exception {
        final JsonNode root = new ObjectMapper().readTree(Files.readString(DESCRIPTOR));
        final Set<String> declared = new HashSet<>();
        for (JsonNode capability : root.path("capabilities")) {
            declared.add(capability.asText());
        }

        assertEquals(REQUIRED_CAPABILITIES, declared);
    }

    @Test
    void psdImportShellDescriptorRemainsCapabilityFree() throws Exception {
        final JsonNode root = new ObjectMapper().readTree(Files.readString(PSD_IMPORT_SHELL_DESCRIPTOR));

        assertEquals("dev.turboism.plugin.psd-import", root.path("id").asText());
        assertEquals(0, root.path("permissions").size());
        assertEquals(0, root.path("capabilities").size());
        assertTrue(root.path("environment").path("requiresCubism").asBoolean() == false);
    }
}
