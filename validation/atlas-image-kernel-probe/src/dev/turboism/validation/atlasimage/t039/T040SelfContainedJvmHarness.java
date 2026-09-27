package dev.turboism.validation.atlasimage.t039;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

/** Real no-external-ASM premain, definition, patch, helper, and same-session freeze test. */
public final class T040SelfContainedJvmHarness {
    private T040SelfContainedJvmHarness() {
    }

    public static void main(final String[] args) throws Exception {
        check(args.length == 1, "usage: fixture-metadata");
        final Properties metadata = readMetadata(Path.of(args[0]));
        final Path fixtureJar = Path.of(required(metadata, "fixtureJar"));
        check(Files.isRegularFile(fixtureJar), "fixture JAR missing");

        final Class<?> target = new T039OwnedTargetLoader(
            fixtureJar, T039ShadowAgent.class.getClassLoader()).defineTarget();
        final T039ShadowAgent.Snapshot patched = T039ShadowAgent.snapshot();
        check("PATCHED".equals(patched.state())
                && "REMOVED".equals(patched.removalStatus())
                && !patched.transformerRegistered()
                && patched.targetEvents() == 1
                && patched.candidateCount() == 1
                && patched.candidateReturnedCount() == 1,
            "real first-definition patch was not accepted: " + patched);

        executePatchedHelper(target);
        final T039ShadowHelper.Stats beforeFreeze = T039ShadowHelper.snapshot();
        check(beforeFreeze.recorded() >= 1L
                && beforeFreeze.admitted() >= 1L
                && beforeFreeze.potentialTrim() >= 1L,
            "actual patched method did not reach eligible helper: " + beforeFreeze);

        final Map<String, String> frozen = T039ShadowAgent.freezeCapture("t039-owned");
        check("true".equals(frozen.get("frozen"))
                && "true".equals(frozen.get("fullBoundsPreserved"))
                && "PATCHED".equals(frozen.get("state"))
                && "REMOVED".equals(frozen.get("removalStatus"))
                && "false".equals(frozen.get("transformerRegistered"))
                && "1".equals(frozen.get("candidateReturnedCount"))
                && "POTENTIAL_TRIM".equals(frozen.get("sampleOutcome")),
            "same-session freeze fields mismatch: " + frozen);
        check(frozen.get("stats.recorded").equals(Long.toString(beforeFreeze.recorded())),
            "freeze did not capture the helper sample");
        assertImmutable(frozen);

        final long packed = T039ShadowHelper.bounds(
            1, 1, new int[1], 1, 1, new int[1], 1, 1, 1, 0, true);
        check((int) packed == 1 && (int) (packed >>> 32) == 1,
            "late helper call did not preserve full bounds");
        final T039ShadowHelper.Stats afterLate = T039ShadowHelper.snapshot();
        check(afterLate.equals(beforeFreeze), "late helper call changed frozen stats");
        System.out.println(
            "t040SelfContainedJvm=PASS premain/first-definition/patch/helper/freeze/no-external-ASM");
        System.out.println("T040_SELF_CONTAINED_OFFLINE_PASS");
        System.out.println("OFFLINE_PASS");
    }

    private static void executePatchedHelper(final Class<?> target) throws Exception {
        target.getMethod("setAssertionsEnabled", boolean.class).invoke(null, false);
        final Method render = target.getMethod(
            "render", int.class, int.class, int[].class,
            int.class, int.class, int[].class, int.class, int.class, int.class, int.class);
        final int[] source = new int[15];
        final int[] destination = new int[12 * 8 + 3];
        Arrays.fill(source, 0x00ffffff);
        Arrays.fill(destination, 0x2468ace0);
        try {
            render.invoke(target.getDeclaredConstructor().newInstance(),
                4, 4, source, 5, 3, destination, 12, 8, 4, 2);
        } catch (final InvocationTargetException exception) {
            throw new AssertionError("patched helper execution failed", exception.getCause());
        }
    }

    private static void assertImmutable(final Map<String, String> values) {
        boolean rejected = false;
        try {
            values.put("mutation", "rejected");
        } catch (final UnsupportedOperationException expected) {
            rejected = true;
        }
        check(rejected, "freeze map was mutable");
    }

    private static Properties readMetadata(final Path path) throws Exception {
        final Properties result = new Properties();
        try (var input = Files.newInputStream(path)) {
            result.load(input);
        }
        return result;
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
