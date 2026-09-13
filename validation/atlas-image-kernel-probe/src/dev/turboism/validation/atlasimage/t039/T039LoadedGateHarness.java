package dev.turboism.validation.atlasimage.t039;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Pure negative gate for the already-loaded target condition. */
public final class T039LoadedGateHarness {
    private T039LoadedGateHarness() {
    }

    public static void main(final String[] args) throws Exception {
        check(args.length == 1, "usage: fixture-metadata");
        final Properties metadata = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            metadata.load(input);
        }
        final Path fixtureJar = Path.of(required(metadata, "fixtureJar"));
        final Class<?> loaded = new T039OwnedTargetLoader(
            fixtureJar, T039ShadowAgent.class.getClassLoader()).defineTarget();
        check(T039ShadowAgent.targetNameAlreadyLoaded(
            loaded.getName(), new Class<?>[] {loaded}), "loaded target gate missed target");
        check(!T039ShadowAgent.targetNameAlreadyLoaded(
            loaded.getName(), new Class<?>[] {T039LoadedGateHarness.class}),
            "loaded target gate produced false positive");
        System.out.println("t039LoadedGate=PASS already-loaded/unknown");
        System.out.println("OFFLINE_PASS");
    }

    private static String required(final Properties properties, final String key) {
        final String value = properties.getProperty(key, "");
        check(!value.isEmpty(), "metadata missing " + key);
        return value;
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
