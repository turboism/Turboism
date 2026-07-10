package dev.turboism.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.adapter.cubism.VerifiedProjectWorkspaceHostOperations;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedRuntimeHostAdaptersFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();


    @Test
    void rejectsAnySelfIssuedRecordEvenWhenItsSelectorsAndSyntheticArtifactMatch() throws Exception {
        Path artifact = jarContaining(Host.class);
        Path record = recordFor(
            artifact,
            "adapter.project-workspace.readonly",
            List.of("cubism.project.read", "cubism.workspace.read"),
            VerifiedProjectWorkspaceHostOperations.REQUIRED_ALIASES
        );

        assertThrows(IllegalArgumentException.class, () -> new VerifiedRuntimeHostAdaptersFactory().projectWorkspace(
            record,
            artifact,
            Host.class.getClassLoader()
        ));
    }

    private Path recordFor(
        final Path artifact,
        final String adapterSliceId,
        final List<String> capabilityIds,
        final java.util.Set<String> aliases
    ) throws Exception {
        var fingerprint = dev.turboism.mapping.verification.HostArtifactFingerprint.from("5.3.02", artifact);
        String owner = Host.class.getName().replace('.', '/');
        Path record = Files.createTempFile("turboism-runtime-adapters", ".json");
        var capabilities = mapper.createArrayNode();
        capabilityIds.forEach(capabilities::add);
        var selectors = mapper.createArrayNode();
        for (String alias : aliases.stream().sorted().toList()) {
            var selector = mapper.createObjectNode();
            selector.put("mappingId", "fixture." + alias);
            selector.put("alias", alias);
            selector.put("ownerInternalName", owner);
            if (alias.endsWith(".class")) {
                selector.put("kind", "class");
                selector.putNull("memberName");
                selector.putNull("descriptor");
                selector.put("requiredAccessFlags", 0);
                selector.put("forbiddenAccessFlags", 0);
            } else if (alias.equals("cubism.app-controller.instance")) {
                selector.put("kind", "method");
                selector.put("memberName", "instance");
                selector.put("descriptor", "()L" + owner + ";");
                selector.put("requiredAccessFlags", 9);
                selector.put("forbiddenAccessFlags", 0);
            } else {
                selector.put("kind", "method");
                selector.put("memberName", "placeholder");
                selector.put("descriptor", "()Ljava/lang/Object;");
                selector.put("requiredAccessFlags", 1);
                selector.put("forbiddenAccessFlags", 8);
            }
            selector.put("status", "VERIFIED_STATIC");
            selectors.add(selector);
        }
        var root = mapper.createObjectNode();
        root.put("format", "turboism.static.verification.record");
        root.put("schemaVersion", 1);
        root.put("verificationId", "fixture.runtime");
        root.put("adapterSliceId", adapterSliceId);
        root.set("capabilityIds", capabilities);
        root.put("cubismVersion", "5.3.02");
        root.put("profileId", "fixture");
        var artifactNode = root.putObject("artifact");
        artifactNode.put("name", artifact.getFileName().toString());
        artifactNode.put("size", fingerprint.size());
        artifactNode.put("sha256", fingerprint.sha256());
        root.put("evidenceType", "JAR_METADATA");
        root.put("evidencePath", "docs/migration/verification/static/fixture.json");
        root.put("owner", "runtime-adapter");
        root.put("verifiedBy", "test");
        root.put("verifiedAt", "2026-07-10T00:00:00Z");
        root.put("safeMode", "Fail closed.");
        root.put("status", "VERIFIED_STATIC");
        root.set("selectors", selectors);
        mapper.writeValue(record.toFile(), root);
        return record;
    }

    private static Path jarContaining(final Class<?> type) throws Exception {
        Path jar = Files.createTempFile("turboism-runtime-host", ".jar");
        List<String> entries = new ArrayList<>();
        entries.add(type.getName().replace('.', '/') + ".class");
        for (Class<?> nested : type.getDeclaredClasses()) {
            entries.add(nested.getName().replace('.', '/') + ".class");
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entryName : entries) {
                try (InputStream input = type.getClassLoader().getResourceAsStream(entryName)) {
                    output.putNextEntry(new JarEntry(entryName));
                    input.transferTo(output);
                    output.closeEntry();
                }
            }
        }
        return jar;
    }

    public static final class Host {
        public static Host instance() {
            return null;
        }

        public Object placeholder() {
            return null;
        }
    }
}
