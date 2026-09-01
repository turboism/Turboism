package dev.turboism.plugin.turboismwithfx;

import dev.turboism.protocol.json.StrictJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class FxIsolatedHomeTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void seedsOnlyGatewayRoutingInAPluginOwnedHome() throws Exception {
        final Map<String, String> environment = FxIsolatedHome.gatewayEnvironment(
            temporaryDirectory.resolve("state"),
            "profile-one",
            Map.of("AI_GATEWAY_API_KEY", "adapter-key")
        );
        final Path home = Path.of(environment.get("HOME"));

        assertEquals(home.toString(), environment.get("USERPROFILE"));
        assertEquals("adapter-key", environment.get("AI_GATEWAY_API_KEY"));
        assertEquals(
            Map.of("provider", "gateway"),
            StrictJson.parse(Files.readAllBytes(home.resolve(".fx/settings.json")))
        );
        assertFalse(Files.readString(home.resolve(".fx/settings.json")).contains("adapter-key"));
        final Map<String, String> other = FxIsolatedHome.gatewayEnvironment(
            temporaryDirectory.resolve("state"),
            "profile-two",
            Map.of("AI_GATEWAY_API_KEY", "other-key")
        );
        assertFalse(home.equals(Path.of(other.get("HOME"))));
    }
}
