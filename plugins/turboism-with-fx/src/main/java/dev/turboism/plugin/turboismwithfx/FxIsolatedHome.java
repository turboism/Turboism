package dev.turboism.plugin.turboismwithfx;

import dev.turboism.protocol.json.StrictJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Plugin-owned fx home used when Turboism routes fx through its Gateway adapter. */
final class FxIsolatedHome {

    private FxIsolatedHome() {
    }

    static Map<String, String> gatewayEnvironment(
        final Path stateDirectory,
        final String profileId,
        final Map<String, String> adapterEnvironment
    ) throws IOException {
        final String profileDirectory = java.util.UUID.nameUUIDFromBytes(
            Objects.requireNonNull(profileId, "profileId").getBytes(StandardCharsets.UTF_8)
        ).toString();
        final Path home = Objects.requireNonNull(stateDirectory, "stateDirectory")
            .toAbsolutePath().normalize().resolve("fx-homes").resolve(profileDirectory);
        final Path settingsDirectory = home.resolve(".fx");
        Files.createDirectories(settingsDirectory);
        Files.write(
            settingsDirectory.resolve("settings.json"),
            StrictJson.bytes(Map.of("provider", "gateway")),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
        final LinkedHashMap<String, String> environment = new LinkedHashMap<>(
            Objects.requireNonNull(adapterEnvironment, "adapterEnvironment")
        );
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());
        return Map.copyOf(environment);
    }
}
