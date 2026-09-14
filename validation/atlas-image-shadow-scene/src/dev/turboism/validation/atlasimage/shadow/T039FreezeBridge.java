package dev.turboism.validation.atlasimage.shadow;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Fixed reflection bridge for the separately delivered T039 public freeze API. */
final class T039FreezeBridge {
    @FunctionalInterface
    interface FreezeProvider {
        Map<String, String> freeze(String expectedRunId) throws Exception;
    }

    private static final Set<String> EXPECTED_KEYS = Set.of(
        "schemaVersion", "runId", "profile", "shadowMode", "state", "removalStatus",
        "transformerRegistered", "candidateReturnedCount", "frozen", "fullBoundsPreserved",
        "sampleOutcome", "reason", "helperPrewarmed", "helperHash", "t038HelperPrewarmed",
        "t038HelperHash",
        "stats.recorded", "stats.dropped", "stats.admitted", "stats.rejected",
        "stats.aliased", "stats.potentialTrim", "stats.eventLimit",
        "stats.lastKx", "stats.lastKy", "stats.lastSourceWidth", "stats.lastSourceHeight",
        "stats.lastStride", "stats.lastFullWidth", "stats.lastFullHeight", "stats.lastPadding",
        "stats.lastSourceLength", "stats.lastDestinationLength", "stats.lastAliased",
        "stats.lastAdmitted", "stats.lastPotentialTrim", "stats.lastOptimizationRequested",
        "stats.targetEvents", "stats.lateCallbacks", "stats.candidateCount",
        "stats.candidateReturnedCount", "stats.rejectionCount", "stats.methodExecuted");
    private static final Set<String> SAMPLE_OUTCOMES = Set.of(
        "NO_CALLS", "NO_ELIGIBLE_CALLS", "POTENTIAL_TRIM", "TRUNCATED");

    private T039FreezeBridge() {}

    static Map<String, String> invokeReal(final String expectedRunId,
                                           final String expectedProfile) throws Exception {
        final Class<?> type = Class.forName(
            ShadowSceneContract.T039_CLASS, false, T040ShadowSceneDriverAgent.class.getClassLoader());
        final Method method = type.getMethod("freezeCapture", String.class);
        final int modifiers = method.getModifiers();
        if (method.getReturnType() != Map.class || !Modifier.isPublic(modifiers)
                || !Modifier.isStatic(modifiers)) {
            throw new IllegalStateException("T039 freezeCapture is not public static Map");
        }
        final Object returned;
        try {
            returned = method.invoke(null, expectedRunId);
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("T039 freezeCapture failed", cause);
        }
        if (!(returned instanceof Map<?, ?> map)) {
            throw new IllegalStateException("T039 freezeCapture did not return a Map");
        }
        return validateAndCopy(map, expectedRunId, expectedProfile);
    }

    static Map<String, String> validateAndCopy(final Map<?, ?> raw,
                                               final String expectedRunId,
                                               final String expectedProfile) {
        if (raw == null || raw.isEmpty()) throw new IllegalStateException("T039 freeze map is empty");
        final Map<String, String> copy = new TreeMap<>();
        final Set<String> seen = new HashSet<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
                throw new IllegalStateException("T039 freeze map is not a flat string map");
            }
            if (key.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                    || value.indexOf('\0') >= 0) {
                throw new IllegalStateException("T039 freeze map contains an unsafe scalar");
            }
            if (!seen.add(key)) throw new IllegalStateException("T039 freeze map contains duplicate key");
            if (!EXPECTED_KEYS.contains(key)) {
                throw new IllegalStateException("T039 freeze map contains unknown key: " + key);
            }
            copy.put(key, value);
        }
        if (copy.size() != EXPECTED_KEYS.size()) {
            throw new IllegalStateException("T039 freeze map does not contain the exact 43-key schema");
        }
        for (String key : EXPECTED_KEYS) {
            if (!copy.containsKey(key)) throw new IllegalStateException("T039 freeze map missing " + key);
        }
        requireEqual(copy, "schemaVersion", "1");
        requireEqual(copy, "runId", expectedRunId);
        requireEqual(copy, "profile", expectedProfile);
        requireEqual(copy, "shadowMode", ShadowSceneContract.T039_SHADOW_MODE);
        requireEqual(copy, "state", "SHADOW_READY");
        requireEqual(copy, "removalStatus", "REMOVED");
        requireEqual(copy, "transformerRegistered", "false");
        requireEqual(copy, "candidateReturnedCount", "1");
        requireEqual(copy, "frozen", "true");
        requireEqual(copy, "fullBoundsPreserved", "true");
        if (!SAMPLE_OUTCOMES.contains(copy.get("sampleOutcome"))) {
            throw new IllegalStateException("T039 freeze sampleOutcome is not a known classification");
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void requireEqual(final Map<String, String> values,
                                     final String key, final String expected) {
        if (!expected.equals(values.get(key))) {
            throw new IllegalStateException("T039 freeze " + key + " mismatch");
        }
    }
}
