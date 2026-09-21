package dev.turboism.validation.atlasimage.t039;

import java.util.Map;

/** Offline T040 bridge test; official bytes are data only and never defined. */
public final class T040FreezeJvmHarness {
    private static final String[] EXPECTED_KEYS = {
        "schemaVersion", "runId", "profile", "shadowMode", "state", "removalStatus",
        "transformerRegistered", "candidateReturnedCount", "frozen", "fullBoundsPreserved",
        "sampleOutcome", "reason", "helperPrewarmed", "helperHash", "t038HelperPrewarmed",
        "t038HelperHash", "stats.recorded", "stats.dropped", "stats.admitted", "stats.rejected",
        "stats.aliased", "stats.potentialTrim", "stats.eventLimit", "stats.lastKx", "stats.lastKy",
        "stats.lastSourceWidth", "stats.lastSourceHeight", "stats.lastStride", "stats.lastFullWidth",
        "stats.lastFullHeight", "stats.lastPadding", "stats.lastSourceLength",
        "stats.lastDestinationLength", "stats.lastAliased", "stats.lastAdmitted",
        "stats.lastPotentialTrim", "stats.lastOptimizationRequested", "stats.targetEvents",
        "stats.lateCallbacks", "stats.candidateCount", "stats.candidateReturnedCount",
        "stats.rejectionCount", "stats.methodExecuted"
    };

    private T040FreezeJvmHarness() {
    }

    public static void main(final String[] args) throws Exception {
        check(args.length == 0, "usage: no arguments");
        final Map<String, String> capture = T039ShadowAgent.checkOfficialFreezeCapture();
        check(capture.size() == EXPECTED_KEYS.length,
            "T040 key count mismatch: " + capture.size());
        for (final String key : EXPECTED_KEYS) {
            check(capture.containsKey(key), "T040 missing key: " + key);
        }
        check("1".equals(capture.get("schemaVersion")), "schemaVersion mismatch");
        check("t040-official".equals(capture.get("runId")), "runId mismatch");
        check("5303".equals(capture.get("profile")), "profile mismatch");
        check("shadow-ready".equals(capture.get("shadowMode")), "shadowMode mismatch");
        check("SHADOW_READY".equals(capture.get("state")), "state mismatch");
        check("REMOVED".equals(capture.get("removalStatus")), "removalStatus mismatch");
        check("false".equals(capture.get("transformerRegistered")),
            "transformerRegistered mismatch");
        check("1".equals(capture.get("candidateReturnedCount")),
            "candidateReturnedCount mismatch");
        check("true".equals(capture.get("frozen")), "frozen mismatch");
        check("true".equals(capture.get("fullBoundsPreserved")),
            "fullBoundsPreserved mismatch");
        check("NO_CALLS".equals(capture.get("sampleOutcome")), "sampleOutcome mismatch");
        check("0".equals(capture.get("stats.recorded"))
                && "0".equals(capture.get("stats.dropped"))
                && "0".equals(capture.get("stats.admitted"))
                && "0".equals(capture.get("stats.rejected"))
                && "0".equals(capture.get("stats.aliased"))
                && "0".equals(capture.get("stats.potentialTrim"))
                && "16".equals(capture.get("stats.eventLimit")),
            "official helper stats mismatch: " + capture);
        check("1".equals(capture.get("stats.targetEvents"))
                && "0".equals(capture.get("stats.lateCallbacks"))
                && "1".equals(capture.get("stats.candidateCount"))
                && "1".equals(capture.get("stats.candidateReturnedCount"))
                && "0".equals(capture.get("stats.rejectionCount"))
                && "false".equals(capture.get("stats.methodExecuted")),
            "official session stats mismatch: " + capture);
        boolean immutable = false;
        try {
            capture.put("unexpected", "mutation");
        } catch (final UnsupportedOperationException expected) {
            immutable = true;
        }
        check(immutable, "T040 official capture was mutable");
        System.out.println("t040OfficialFreeze=PASS keys=" + capture.size()
            + " official-bytes-only-classpath=none-execution=none");
        System.out.println("T040_OFFLINE_PASS");
        System.out.println("OFFLINE_PASS");
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
