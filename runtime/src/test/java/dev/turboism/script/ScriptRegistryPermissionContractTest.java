package dev.turboism.script;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.schema.permission.PermissionValidator;
import dev.turboism.core.schema.plugin.PluginMetaValidator;
import dev.turboism.sdk.permission.PermissionIds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Locks the single-source-of-truth invariant for permission ids:
 * {@code ScriptRegistry.KNOWN_PERMISSIONS ⊆ PermissionValidator ⊆ PermissionIds.KNOWN_IDS},
 * and every first-party {@code plugin.json} declaration stays inside the SDK set.
 */
final class ScriptRegistryPermissionContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void scriptWhitelistIsAProperSubsetOfTheSdkCatalog() {
        assertFalse(ScriptRegistry.KNOWN_PERMISSIONS.isEmpty());
        assertTrue(PermissionIds.KNOWN_IDS.containsAll(ScriptRegistry.KNOWN_PERMISSIONS));
        assertTrue(ScriptRegistry.KNOWN_PERMISSIONS.size() < PermissionIds.KNOWN_IDS.size());
    }

    @Test
    void everySdkPermissionIdPassesThePermissionSchema() {
        final PermissionValidator validator = new PermissionValidator();
        for (final String id : PermissionIds.KNOWN_IDS) {
            assertTrue(
                    validator
                            .validate(permissionDocument(id), "permission.json")
                            .isEmpty(),
                    () -> id);
        }
        assertTrue(validator.validate(permissionDocument("bogus.permission"), "permission.json").stream()
                .anyMatch(error -> "PERMISSION_UNKNOWN_ID".equals(error.code())));
    }

    @Test
    void everySdkPermissionIdPassesThePluginMetaSchema() {
        final PluginMetaValidator validator = PluginMetaValidator.forSchemaVersion(3);
        for (final String id : PermissionIds.KNOWN_IDS) {
            assertTrue(
                    validator
                            .validate(manifestWithPermission(id), "plugin.json")
                            .isEmpty(),
                    () -> id);
        }
        assertTrue(validator.validate(manifestWithPermission("bogus.permission"), "plugin.json").stream()
                .anyMatch(error -> "PERMISSION_UNKNOWN_ID".equals(error.code())));
    }

    @Test
    void firstPartyManifestsOnlyDeclareKnownPermissionIds() throws Exception {
        final Path pluginsRoot = repositoryRoot().resolve("plugins");
        final Set<String> declared = new TreeSet<>();
        try (Stream<Path> manifests =
                Files.walk(pluginsRoot).filter(path -> path.endsWith("META-INF/turboism/plugin.json"))) {
            for (final Path manifest : manifests.collect(Collectors.toList())) {
                final JsonNode root = JSON.readTree(manifest.toFile());
                if (root == null || !root.path("permissions").isArray()) {
                    continue;
                }
                for (final JsonNode permission : root.path("permissions")) {
                    declared.add(permission.path("id").asText(""));
                }
            }
        }
        declared.remove("");
        assertFalse(declared.isEmpty());
        assertTrue(
                PermissionIds.KNOWN_IDS.containsAll(declared),
                () -> "Unknown ids: "
                        + declared.stream()
                                .filter(id -> !PermissionIds.KNOWN_IDS.contains(id))
                                .toList());
    }

    private static JsonNode permissionDocument(final String id) {
        final var node = JSON.createObjectNode();
        node.put("format", "turboism.permission");
        node.put("schemaVersion", 1);
        node.put("id", id);
        node.put("scope", "application");
        node.put("reason", "Contract probe");
        return node;
    }

    private static JsonNode manifestWithPermission(final String id) {
        final var node = JSON.createObjectNode();
        node.put("format", "turboism.plugin.meta");
        node.put("schemaVersion", 3);
        node.put("id", "dev.turboism.contract.probe");
        node.put("name", "Contract Probe");
        node.put("version", "0.1.0");
        node.putArray("entrypoints").add("dev.turboism.contract.Probe");
        node.put("turboismApi", "[0.1.0,0.2.0)");
        node.putArray("authors").addObject().put("name", "Turboism");
        node.put("website", "https://turboism.dev");
        node.putArray("resources");
        node.putObject("i18n")
                .put("baseName", "META-INF/turboism/i18n/messages")
                .putArray("locales");
        node.put("category", "probe");
        node.putArray("permissions")
                .addObject()
                .put("id", id)
                .put("scope", "application")
                .put("reason", "Contract probe");
        return node;
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root from user.dir");
        }
        return current;
    }
}
