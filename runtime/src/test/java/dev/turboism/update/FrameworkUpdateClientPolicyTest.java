package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class FrameworkUpdateClientPolicyTest {
    @Test void noBundledActivationCannotStartNetworkRequests() throws Exception {
        final var policy = FrameworkUpdateClientPolicy.bundled();
        assertFalse(policy.enabled());
        assertThrows(IOException.class, () -> policy.source().open("document", "a".repeat(64)));
    }

    @Test void endpointMustBePinnedToTheOfficialOriginEvenWhenConfigurationIsBundled() throws Exception {
        final var policy = parse("https://api.turboism.dev/v1/framework/");
        assertTrue(policy.enabled());
        assertEquals("https://api.turboism.dev/v1/framework/documents/" + "a".repeat(64),
                policy.source().open("document", "a".repeat(64)).getURL().toString());
        for (final String endpoint : new String[] {
                "http://api.turboism.dev/v1/framework/", "https://evil.example/v1/framework/",
                "https://api.turboism.dev:443/v1/framework/", "https://api.turboism.dev/v1/framework/?redirect=evil",
                "https://user@api.turboism.dev/v1/framework/", "http://127.0.0.1:8080/",
                "https://api.turboism.dev/v1/../framework/"
        }) assertThrows(IOException.class, () -> parse(endpoint), endpoint);
    }

    public static final class ClasspathProbe {
        public static void main(final String[] args) throws Exception {
            System.out.println("enabled=" + FrameworkUpdateClientPolicy.bundled().enabled());
            System.out.println("keys=" + FrameworkUpdateTrust.bundled().activeKeys().size());
        }
    }

    @Test void unrelatedClasspathResourcesCannotEnableTheUpdater() throws Exception {
        final var directory = java.nio.file.Files.createTempDirectory("update-policy-injection-");
        try {
            final var resources = directory.resolve("META-INF/turboism");
            java.nio.file.Files.createDirectories(resources);
            java.nio.file.Files.writeString(resources.resolve("framework-update-client.json"),
                    "{\"schemaVersion\":1,\"enabled\":true,\"endpoint\":\"https://api.turboism.dev/v1/framework/\"}");
            java.nio.file.Files.writeString(resources.resolve("framework-update-trusted-keys.json"), "invalid untrusted keyring");
            final var output = directory.resolve("probe.log");
            final Process process = new ProcessBuilder(
                    java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", directory + java.io.File.pathSeparator + System.getProperty("java.class.path"),
                    ClasspathProbe.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("Classpath probe timed out");
            }
            final String log = java.nio.file.Files.readString(output);
            assertEquals(0, process.exitValue(), log);
            assertTrue(log.contains("enabled=false"), log);
            assertTrue(log.contains("keys=0"), log);
        } finally {
            try (var paths = java.nio.file.Files.walk(directory)) {
                for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(path);
                }
            }
        }
    }

    private static FrameworkUpdateClientPolicy.Policy parse(final String endpoint) throws IOException {
        final var node = UpdateFileSupport.JSON.createObjectNode()
                .put("schemaVersion", 1).put("enabled", true).put("endpoint", endpoint);
        return FrameworkUpdateClientPolicy.parse(UpdateFileSupport.JSON.writeValueAsBytes(node));
    }

    @Test void unknownFieldsDuplicateFieldsAndUnboundedDocumentsFailClosed() {
        for (final String value : new String[] {"null", "{}", "[]", " ",
                "{\"schemaVersion\":1,\"enabled\":true,\"enabled\":false,\"endpoint\":\"https://api.turboism.dev/v1/framework/\"}",
                "{\"schemaVersion\":1,\"enabled\":true,\"endpoint\":\"https://api.turboism.dev/v1/framework/\",\"extra\":1}"}) {
            assertThrows(IOException.class, () -> FrameworkUpdateClientPolicy.parse(value.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(IOException.class, () -> FrameworkUpdateClientPolicy.parse(new byte[4097]));
    }
}
