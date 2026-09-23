package dev.turboism.validation.atlasimage.t039;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Exercises one-shot target rejection after premain has armed the transformer. */
public final class T039NegativeJvmHarness {
    private T039NegativeJvmHarness() {
    }

    public static void main(final String[] args) throws Exception {
        check(args.length == 2, "usage: kind fixture-metadata");
        final String kind = args[0];
        final Properties metadata = new Properties();
        try (var input = Files.newInputStream(Path.of(args[1]))) {
            metadata.load(input);
        }
        final Path fixtureJar = Path.of(required(metadata, "fixtureJar"));
        check(Files.isRegularFile(fixtureJar), "fixture JAR missing");
        new T039OwnedTargetLoader(fixtureJar, T039ShadowAgent.class.getClassLoader()).defineTarget();
        final T039ShadowAgent.Snapshot snapshot = T039ShadowAgent.snapshot();
        check("BLOCKED".equals(snapshot.state()), kind + " did not block target: " + snapshot);
        check(!snapshot.transformerRegistered()
                && "REMOVED".equals(snapshot.removalStatus())
                && snapshot.targetEvents() == 1,
            kind + " did not record successful removal after rejection: " + snapshot);
        check(snapshot.candidateCount() == 0 && snapshot.candidateReturnedCount() == 0
                && snapshot.rejectionCount() >= 1,
            kind + " unexpectedly patched or returned target: " + snapshot);
        System.out.println("t039NegativeGate=PASS kind=" + kind + " reason=" + snapshot.reason());
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
