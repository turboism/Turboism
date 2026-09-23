package dev.turboism.validation.atlasimage.t039;

import dev.turboism.validation.atlasimage.t033.T033FixtureEvents;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real premain/transformer/helper behavior test for the owned target only. */
public final class T039OwnedJvmHarness {
    private T039OwnedJvmHarness() {
    }

    public static void main(final String[] args) throws Exception {
        check(args.length == 1, "usage: fixture-metadata");
        final Properties metadata = readMetadata(Path.of(args[0]));
        final T039ShadowAgent.Snapshot armed = T039ShadowAgent.snapshot();
        check("ARMED".equals(armed.state()), "premain did not arm: " + armed);
        check(armed.helperPrewarmed(), "premain did not prewarm T039 helper");
        check(armed.t038HelperPrewarmed(), "premain did not prewarm T038 helper closure");
        check("owned".equals(armed.shadowMode()), "owned mode was not explicit");
        check(armed.transformerRegistered(), "premain did not register cold transformer");
        System.out.println("t039PremainArm=PASS no-args/named-properties/helper-prewarm");

        final Path fixtureJar = Path.of(required(metadata, "fixtureJar"));
        check(Files.isRegularFile(fixtureJar), "fixture JAR missing");
        check(required(metadata, "owner").equals(T039ShadowAgent.ownedInternalName()),
            "owned fixture owner mismatch");

        final ClassLoader parent = T039ShadowAgent.class.getClassLoader();
        final Class<?> patchedClass = new T039OwnedTargetLoader(fixtureJar, parent).defineTarget();
        final T039ShadowAgent.Snapshot patched = T039ShadowAgent.snapshot();
        check("PATCHED".equals(patched.state()), "owned target was not patched: " + patched);
        check("REMOVED".equals(patched.removalStatus()),
            "successful removal call was not recorded as REMOVED: " + patched);
        check(!patched.transformerRegistered(),
            "registration state was not cleared after successful removal: " + patched);
        check(patched.candidateCount() == 1 && patched.candidateReturnedCount() == 1
                && patched.targetEvents() == 1 && !patched.methodExecuted(),
            "unexpected one-shot candidate counts: " + patched);

        final Class<?> originalClass = new T039OwnedTargetLoader(fixtureJar, parent).defineTarget();
        final T039ShadowAgent.Snapshot repeated = T039ShadowAgent.snapshot();
        check(repeated.candidateCount() == 1 && repeated.candidateReturnedCount() == 1
                && "REMOVED".equals(repeated.removalStatus())
                && !repeated.transformerRegistered(),
            "repeat target changed candidate/removal state: " + repeated);
        check(repeated.lateCallbacks() <= 1,
            "late callback accounting exceeded one inert definition: " + repeated);
        final Execution patchedNormal = execute(patchedClass, false, false);
        final Execution originalNormal = execute(originalClass, false, false);
        compare(patchedNormal, originalNormal, "normal-zero-optimization");
        final Execution patchedFailure = execute(patchedClass, true, false);
        final Execution originalFailure = execute(originalClass, true, false);
        compare(patchedFailure, originalFailure, "failure-zero-optimization");
        System.out.println("t039OwnedExecution=PASS whole-backing/read-order/clear/exception/repeat");

        final T039ShadowAgent.InstrumentationRemovalCheck removalScenarios =
            T039ShadowAgent.checkInstrumentationRemovalScenarios(fixtureJar);
        check(removalScenarios.trueFirstReturned()
                && removalScenarios.trueLateReturnedNull()
                && "PATCHED".equals(removalScenarios.trueTerminal().state())
                && "REMOVED".equals(removalScenarios.trueTerminal().removalStatus())
                && !removalScenarios.trueTerminal().transformerRegistered()
                && removalScenarios.trueTerminal().candidateReturnedCount() == 1
                && removalScenarios.trueAfterLate().lateCallbacks() == 1,
            "true instrumentation removal chain mismatch: " + removalScenarios);
        check(!removalScenarios.falseFirstReturned()
                && removalScenarios.falseLateReturnedNull()
                && "BLOCKED".equals(removalScenarios.falseTerminal().state())
                && "NOT_REMOVED".equals(removalScenarios.falseTerminal().removalStatus())
                && removalScenarios.falseTerminal().transformerRegistered()
                && removalScenarios.falseTerminal().candidateReturnedCount() == 0
                && removalScenarios.falseAfterLate().lateCallbacks() == 1,
            "false instrumentation removal chain mismatch: " + removalScenarios);
        check(!removalScenarios.throwsFirstReturned()
                && removalScenarios.throwsLateReturnedNull()
                && "BLOCKED".equals(removalScenarios.throwsTerminal().state())
                && "FAILED".equals(removalScenarios.throwsTerminal().removalStatus())
                && removalScenarios.throwsTerminal().transformerRegistered()
                && removalScenarios.throwsTerminal().candidateReturnedCount() == 0
                && removalScenarios.throwsAfterLate().lateCallbacks() == 1,
            "throwing instrumentation removal chain mismatch: " + removalScenarios);
        System.out.println(
            "t039InstrumentationRemoval=PASS arm/transform/unregister true/false/throws/late");

        testShadowHelperBoundsAndStats();
        System.out.println("t039ShadowHelper=PASS full-bounds/no-pixel-fields/bounded-stats");

        final T039ShadowAgent.RemovalCheck removal =
            T039ShadowAgent.checkRemovalOutcomes();
        check(removal.trueMeansRemoved() && removal.falseMeansNotRemoved()
                && removal.missingResultMeansUnknown() && removal.exceptionMeansFailed(),
            "removal status model mismatch: " + removal);
        System.out.println("t039RemovalStatuses=PASS classifier model only");
        final T039ShadowAgent.LateCallbackCheck late =
            T039ShadowAgent.checkLateCallbackInert();
        check(late.callbackReturnedNull() && late.stateStayedBlocked()
                && late.callbackCounted() && late.candidateCountStayedZero()
                && late.rejectionCountStayedZero(), "late callback was not inert: " + late);
        final T039ShadowAgent.ArmRaceCheck race = T039ShadowAgent.checkArmRaceGuards();
        check(race.loadedAfterInitialScanBlocks() && race.callbackDuringArmBlocks()
                && race.noLateSignalDoesNotBlock(), "arm race gate mismatch: " + race);
        System.out.println("t039ArmRace=PASS synchronized-arm/late-callback/loaded-after-scan");

        final T039ShadowAgent.OfficialDataCheck official =
            T039ShadowAgent.checkOfficialDataOnly();
        check(official.commonSuperQueries() == 0, "official data path requested resolution");
        final T039ShadowAgent.OfficialModesCheck modes =
            T039ShadowAgent.checkOfficialModes();
        check("DATA_ONLY_VERIFIED".equals(modes.dataState())
                && "SHADOW_READY".equals(modes.shadowState())
                && !modes.dataReturned() && modes.shadowReturned()
                && modes.dataCandidateCount() == 1
                && modes.shadowCandidateCount() == 1
                && modes.shadowCandidateReturnedCount() == 1,
            "official mode gate mismatch: " + modes);
        System.out.println(
            "t039OfficialModes=PASS data-only/no-return/shadow-ready/manual-candidate");
        System.out.println(
            "t039OfficialDataOnly=PASS version=5303 classpath=none execution=none"
                + " candidateBytes=" + official.candidateLength());

        testT040OwnedFreeze(fixtureJar);
        System.out.println("t039FinalSnapshot=" + T039ShadowAgent.snapshot());
        System.out.println("T039_OFFLINE_PASS");
        System.out.println("OFFLINE_PASS");
    }


    private static void testT040OwnedFreeze(final Path fixtureJar) throws Exception {
        final T039ShadowAgent.Snapshot prepared =
            T039ShadowAgent.installOwnedFreezeSession(fixtureJar);
        check("PATCHED".equals(prepared.state())
                && "REMOVED".equals(prepared.removalStatus())
                && !prepared.transformerRegistered()
                && prepared.candidateReturnedCount() == 1
                && prepared.targetEvents() == 1
                && prepared.rejectionCount() == 0
                && prepared.lateCallbacks() == 0,
            "T040 owned session was not cleanly prepared: " + prepared);

        final T039ShadowAgent.FreezeRejectionCheck rejected =
            T039ShadowAgent.checkFreezeRejections();
        check(rejected.unarmedRejected() && rejected.unpatchedRejected()
                && rejected.blockedRejected(),
            "T040 freeze rejection gates were bypassed: " + rejected);
        boolean wrongRunRejected = false;
        try {
            T039ShadowAgent.freezeCapture("wrong-run");
        } catch (final IllegalArgumentException expected) {
            wrongRunRejected = true;
        }
        check(wrongRunRejected, "T040 wrong runId was accepted");

        T039ShadowHelper.prewarm(128);
        final int[] source = new int[15];
        final int[] destination = new int[99];
        Arrays.fill(source, 0x00ffffff);
        Arrays.fill(destination, 0x2468ace0);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicBoolean workerFailed = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        final Thread[] workers = new Thread[4];
        for (int index = 0; index < workers.length; index++) {
            final int workerIndex = index;
            workers[index] = new Thread(() -> {
                try {
                    await(start);
                    while (running.get()) {
                        final long result = T039ShadowHelper.bounds(
                            4, 4, source, 5, 3, destination, 12, 8, 4, 2, true);
                        if ((int) result != 8 || (int) (result >>> 32) != 4) {
                            workerFailed.set(true);
                        }
                        calls.incrementAndGet();
                        Thread.yield();
                    }
                } catch (final RuntimeException | AssertionError exception) {
                    workerFailed.set(true);
                }
            }, "t040-freeze-worker-" + workerIndex);
            workers[index].start();
        }
        start.countDown();
        awaitCalls(calls, 32);
        final Map<String, String> capture =
            T039ShadowAgent.freezeCapture("t040-owned");
        running.set(false);
        for (final Thread worker : workers) {
            worker.join();
        }
        check(!workerFailed.get() && calls.get() >= 32,
            "T040 in-flight worker regression failed calls=" + calls.get());
        checkOwnedCapture(capture);
        compareFrozenStats(capture, T039ShadowHelper.snapshot());

        final T039ShadowHelper.Stats beforeLate = T039ShadowHelper.snapshot();
        final long lateBounds = T039ShadowHelper.bounds(
            4, 4, source, 5, 3, destination, 12, 8, 4, 2, true);
        check((int) lateBounds == 8 && (int) (lateBounds >>> 32) == 4,
            "T040 late helper call changed full bounds");
        final T039ShadowHelper.Stats afterLate = T039ShadowHelper.snapshot();
        check(beforeLate.equals(afterLate),
            "T040 late helper call changed frozen stats: " + beforeLate + " -> " + afterLate);
        compareFrozenStats(capture, afterLate);

        boolean repeatedRejected = false;
        try {
            T039ShadowAgent.freezeCapture("t040-owned");
        } catch (final IllegalStateException expected) {
            repeatedRejected = true;
        }
        check(repeatedRejected, "T040 repeated freeze was accepted");
        boolean resetRejected = false;
        try {
            T039ShadowHelper.resetCounters();
        } catch (final IllegalStateException expected) {
            resetRejected = true;
        }
        boolean prewarmRejected = false;
        try {
            T039ShadowHelper.prewarm(1);
        } catch (final IllegalStateException expected) {
            prewarmRejected = true;
        }
        check(resetRejected && prewarmRejected,
            "T040 frozen helper was reset or prewarmed");
        boolean immutable = false;
        try {
            capture.put("unexpected", "mutation");
        } catch (final UnsupportedOperationException expected) {
            immutable = true;
        }
        check(immutable, "T040 capture map was mutable");
        System.out.println("t040OwnedFreeze=PASS in-flight/late/repeat/wrong-run/immutable"
            + "/reset-prewarm/full-bounds sampleOutcome=" + capture.get("sampleOutcome"));
    }

    private static void checkOwnedCapture(final Map<String, String> capture) {
        check("1".equals(capture.get("schemaVersion")), "T040 schema mismatch");
        check("t040-owned".equals(capture.get("runId")), "T040 runId mismatch");
        check("owned".equals(capture.get("profile")), "T040 profile mismatch");
        check("owned".equals(capture.get("shadowMode")), "T040 shadow mode mismatch");
        check("PATCHED".equals(capture.get("state")), "T040 state mismatch");
        check("REMOVED".equals(capture.get("removalStatus")),
            "T040 removal status mismatch");
        check("false".equals(capture.get("transformerRegistered")),
            "T040 registration mismatch");
        check("1".equals(capture.get("candidateReturnedCount")),
            "T040 candidate count mismatch");
        check("true".equals(capture.get("frozen")), "T040 frozen flag mismatch");
        check("true".equals(capture.get("fullBoundsPreserved")),
            "T040 full-bounds flag mismatch");
        check("POTENTIAL_TRIM".equals(capture.get("sampleOutcome"))
                || "TRUNCATED".equals(capture.get("sampleOutcome")),
            "T040 sample outcome mismatch: " + capture.get("sampleOutcome"));
        check("true".equals(capture.get("helperPrewarmed"))
                && "true".equals(capture.get("t038HelperPrewarmed")),
            "T040 helper prewarm flags missing");
        check("1".equals(capture.get("stats.targetEvents"))
                && "0".equals(capture.get("stats.lateCallbacks"))
                && "1".equals(capture.get("stats.candidateCount"))
                && "1".equals(capture.get("stats.candidateReturnedCount"))
                && "0".equals(capture.get("stats.rejectionCount"))
                && "false".equals(capture.get("stats.methodExecuted")),
            "T040 session statistics mismatch: " + capture);
    }

    private static void compareFrozenStats(
        final Map<String, String> capture,
        final T039ShadowHelper.Stats stats
    ) {
        checkLong(capture, "stats.recorded", stats.recorded());
        checkLong(capture, "stats.dropped", stats.dropped());
        checkLong(capture, "stats.admitted", stats.admitted());
        checkLong(capture, "stats.rejected", stats.rejected());
        checkLong(capture, "stats.aliased", stats.aliased());
        checkLong(capture, "stats.potentialTrim", stats.potentialTrim());
        checkLong(capture, "stats.eventLimit", stats.eventLimit());
        checkInt(capture, "stats.lastKx", stats.lastKx());
        checkInt(capture, "stats.lastKy", stats.lastKy());
        checkInt(capture, "stats.lastSourceWidth", stats.lastSourceWidth());
        checkInt(capture, "stats.lastSourceHeight", stats.lastSourceHeight());
        checkInt(capture, "stats.lastStride", stats.lastStride());
        checkInt(capture, "stats.lastFullWidth", stats.lastFullWidth());
        checkInt(capture, "stats.lastFullHeight", stats.lastFullHeight());
        checkInt(capture, "stats.lastPadding", stats.lastPadding());
        checkInt(capture, "stats.lastSourceLength", stats.lastSourceLength());
        checkInt(capture, "stats.lastDestinationLength", stats.lastDestinationLength());
        checkBoolean(capture, "stats.lastAliased", stats.lastAliased());
        checkBoolean(capture, "stats.lastAdmitted", stats.lastAdmitted());
        checkBoolean(capture, "stats.lastPotentialTrim", stats.lastPotentialTrim());
        checkBoolean(capture, "stats.lastOptimizationRequested", stats.lastOptimizationRequested());
    }

    private static void checkLong(
        final Map<String, String> values, final String key, final long expected
    ) {
        check(Long.toString(expected).equals(values.get(key)),
            "T040 stat mismatch " + key + "=" + values.get(key) + " expected=" + expected);
    }

    private static void checkInt(
        final Map<String, String> values, final String key, final int expected
    ) {
        check(Integer.toString(expected).equals(values.get(key)),
            "T040 stat mismatch " + key + "=" + values.get(key) + " expected=" + expected);
    }

    private static void checkBoolean(
        final Map<String, String> values, final String key, final boolean expected
    ) {
        check(Boolean.toString(expected).equals(values.get(key)),
            "T040 stat mismatch " + key + "=" + values.get(key) + " expected=" + expected);
    }

    private static void awaitCalls(final AtomicInteger calls, final int minimum) {
        final long deadline = System.nanoTime() + 2_000_000_000L;
        while (calls.get() < minimum && System.nanoTime() < deadline) {
            Thread.yield();
        }
        check(calls.get() >= minimum,
            "T040 workers did not reach minimum calls: " + calls.get());
    }
    private static void testShadowHelperBoundsAndStats() throws Exception {
        T039ShadowHelper.prewarm(3);
        final int[] source = new int[15];
        final int[] destination = new int[12 * 8 + 3];
        Arrays.fill(source, 0x13579bdf);
        Arrays.fill(destination, 0x2468ace0);
        final int[] sourceBefore = source.clone();
        final int[] destinationBefore = destination.clone();
        final long returned = T039ShadowHelper.bounds(
            4, 4, source, 5, 3, destination, 12, 8, 4, 2, true);
        check((int) returned == 8 && (int) (returned >>> 32) == 4,
            "shadow helper returned trimmed bounds");
        final T039ShadowHelper.Stats partial = T039ShadowHelper.snapshot();
        check(partial.recorded() == 1 && partial.admitted() == 1
                && partial.potentialTrim() == 1 && partial.lastPotentialTrim(),
            "partial shadow stats mismatch: " + partial);
        check(Arrays.equals(source, sourceBefore), "shadow helper changed source");
        check(Arrays.equals(destination, destinationBefore), "shadow helper changed destination");

        T039ShadowHelper.resetCounters();
        final int[] alias = new int[16];
        final long aliasBounds = T039ShadowHelper.bounds(
            1, 1, alias, 1, 1, alias, 1, 1, 1, 0, false);
        check((int) aliasBounds == 1 && (int) (aliasBounds >>> 32) == 1,
            "alias shadow helper changed full bounds");
        final T039ShadowHelper.Stats aliasStats = T039ShadowHelper.snapshot();
        check(aliasStats.recorded() == 1 && aliasStats.rejected() == 1
                && aliasStats.aliased() == 1 && aliasStats.lastAliased(),
            "alias shadow stats mismatch: " + aliasStats);

        T039ShadowHelper.resetCounters();
        for (int index = 0; index < 8; index++) {
            T039ShadowHelper.bounds(
                4, 4, source, 5, 3, destination, 12, 8, 4, 2, false);
        }
        final T039ShadowHelper.Stats bounded = T039ShadowHelper.snapshot();
        check(bounded.recorded() == 3 && bounded.dropped() == 5
                && bounded.recorded() <= bounded.eventLimit(),
            "shadow stats exceeded limit: " + bounded);
        for (final Field field : T039ShadowHelper.class.getDeclaredFields()) {
            check(!field.getType().isArray(), "helper retains an array field: " + field);
            check(!Thread.class.isAssignableFrom(field.getType()),
                "helper owns a thread field: " + field);
        }
        testConcurrentShadowSnapshots();
    }

    private static void testConcurrentShadowSnapshots() throws Exception {
        T039ShadowHelper.prewarm(128);
        final int[] admittedSource = new int[15];
        final int[] admittedDestination = new int[99];
        final int[] aliased = new int[16];
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicBoolean invalid = new AtomicBoolean();
        final Thread observer = new Thread(() -> {
            while (running.get()) {
                final T039ShadowHelper.Stats stats = T039ShadowHelper.snapshot();
                if (stats.recorded() != stats.admitted() + stats.rejected()
                        || (stats.recorded() > 0 && !isAllowedSample(stats))) {
                    invalid.set(true);
                }
            }
        });
        final Thread admitted = new Thread(() -> {
            await(start);
            for (int index = 0; index < 32; index++) {
                T039ShadowHelper.bounds(
                    4, 4, admittedSource, 5, 3, admittedDestination,
                    12, 8, 4, 2, true);
            }
        });
        final Thread rejected = new Thread(() -> {
            await(start);
            for (int index = 0; index < 32; index++) {
                T039ShadowHelper.bounds(
                    1, 1, aliased, 1, 1, aliased, 1, 1, 1, 0, false);
            }
        });
        observer.start();
        admitted.start();
        rejected.start();
        start.countDown();
        admitted.join();
        rejected.join();
        running.set(false);
        observer.join();
        final T039ShadowHelper.Stats result = T039ShadowHelper.snapshot();
        check(!invalid.get(), "concurrent helper snapshot mixed samples: " + result);
        check(result.recorded() == 64
                && result.recorded() == result.admitted() + result.rejected()
                && result.admitted() > 0 && result.rejected() > 0,
            "concurrent helper counts mismatch: " + result);
        System.out.println("t039ShadowConcurrency=PASS atomic-sample/counts");
    }

    private static boolean isAllowedSample(final T039ShadowHelper.Stats stats) {
        final boolean admittedSample = stats.lastKx() == 4 && stats.lastKy() == 4
            && stats.lastSourceWidth() == 5 && stats.lastSourceHeight() == 3
            && stats.lastStride() == 12 && stats.lastFullWidth() == 8
            && stats.lastFullHeight() == 4 && stats.lastPadding() == 2
            && stats.lastSourceLength() == 15 && stats.lastDestinationLength() == 99
            && !stats.lastAliased() && stats.lastAdmitted()
            && stats.lastPotentialTrim() && stats.lastOptimizationRequested();
        final boolean rejectedSample = stats.lastKx() == 1 && stats.lastKy() == 1
            && stats.lastSourceWidth() == 1 && stats.lastSourceHeight() == 1
            && stats.lastStride() == 1 && stats.lastFullWidth() == 1
            && stats.lastFullHeight() == 1 && stats.lastPadding() == 0
            && stats.lastSourceLength() == 16 && stats.lastDestinationLength() == 16
            && stats.lastAliased() && !stats.lastAdmitted()
            && !stats.lastPotentialTrim() && !stats.lastOptimizationRequested();
        return admittedSample || rejectedSample;
    }

    private static void await(final CountDownLatch start) {
        try {
            start.await();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrency test interrupted", exception);
        }
    }

    private static Execution execute(
        final Class<?> target,
        final boolean undersizedDestination,
        final boolean assertions
    ) throws Exception {
        final Method assertionSetter = target.getMethod("setAssertionsEnabled", boolean.class);
        assertionSetter.invoke(null, assertions);
        final Method render = target.getMethod("render", int.class, int.class, int[].class,
            int.class, int.class, int[].class, int.class, int.class, int.class, int.class);
        final int[] source = new int[15];
        final int[] destination = undersizedDestination
            ? new int[1]
            : new int[12 * 8 + 3];
        Arrays.fill(source, 0x00ffffff);
        Arrays.fill(destination, 0x2468ace0);
        T033FixtureEvents.reset(T033FixtureEvents.FaultPoint.NONE);
        Throwable failure = null;
        try {
            render.invoke(target.getDeclaredConstructor().newInstance(), 4, 4, source, 5, 3, destination, 12, 8, 4, 2);
        } catch (final InvocationTargetException exception) {
            failure = exception.getCause();
        }
        return new Execution(source, destination, T033FixtureEvents.snapshot(), failure);
    }

    private static void compare(
        final Execution candidate,
        final Execution reference,
        final String label
    ) {
        check(sameFailure(candidate.failure(), reference.failure()),
            label + " exception mismatch");
        check(Arrays.equals(candidate.source(), reference.source()), label + " source mismatch");
        check(Arrays.equals(candidate.destination(), reference.destination()),
            label + " whole backing mismatch");
        check(candidate.events().clearCount() == reference.events().clearCount(),
            label + " clear count mismatch");
        check(candidate.events().sourceReadCount() == reference.events().sourceReadCount(),
            label + " source read count mismatch");
        check(Arrays.equals(candidate.events().sourceReads(), reference.events().sourceReads()),
            label + " source read order mismatch");
        check(candidate.events().targetWriteCount() == reference.events().targetWriteCount(),
            label + " target write count mismatch");
    }

    private static boolean sameFailure(final Throwable first, final Throwable second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.getClass() == second.getClass();
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

    private record Execution(
        int[] source,
        int[] destination,
        T033FixtureEvents.Snapshot events,
        Throwable failure
    ) {
    }
}
