package dev.turboism.validation.atlasimage;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

/**
 * Offline, dependency-free reference/candidate slice for the 5303 integer
 * down-sampling helper. This class never loads or names a host class at run
 * time; it operates only on caller-owned primitive arrays.
 */
public final class AtlasImageKernelProbe {

    private static final int MAX_INT = Integer.MAX_VALUE;
    private static final long MAX_ALPHA_SUM_PER_BLOCK = MAX_INT;
    private static final long MAX_CHANNEL_TIMES_255 = MAX_INT;
    private static final long MAX_WEIGHTED_SAMPLE = 255L * 255L;
    private static final int[] RANDOM_FACTORS = {1, 2, 4, 8, 16};
    private static final int[] RANDOM_ALPHAS = {0, 1, 2, 3, 4, 127, 128, 254, 255};
    private static final long RANDOM_SEED = 0x202709072027L;

    private AtlasImageKernelProbe() {
    }

    public static void main(final String[] args) {
        runSelfTest();
    }

    private static void runSelfTest() {
        final RandomSummary random = runRandomWholeBackingCases();
        runPartialBlockCase();
        runAlphaTruncationCases();
        runTinyFactorOneAndLargeFactorCases();
        runGuardCases();
        runFallbackFailureCases();
        runOriginalCfgBoundaryCases();

        System.out.println("randomWholeBackingCases=" + random.cases());
        System.out.println("randomWholeBackingDiffCount=" + random.diffCount());
        System.out.println("randomSourceReadOrderDiffCount=" + random.readOrderDiffCount());
        System.out.println("randomFullIterations=" + random.fullIterations());
        System.out.println("randomTrimmedIterations=" + random.trimmedIterations());
        System.out.println("randomTrimmedCases=" + random.trimmedCases());
        System.out.println("full-vs-trim counts only; not host acceleration or wall-time evidence");
        System.out.println("OFFLINE_PASS");
    }

    private static RandomSummary runRandomWholeBackingCases() {
        final Random random = new Random(RANDOM_SEED);
        long fullIterations = 0L;
        long trimmedIterations = 0L;
        int diffCount = 0;
        int readOrderDiffCount = 0;
        int trimmedCases = 0;

        for (int caseNumber = 0; caseNumber < 5000; caseNumber++) {
            final int sourceWidth = 1 + random.nextInt(20);
            final int sourceHeight = 1 + random.nextInt(14);
            final int kx = RANDOM_FACTORS[random.nextInt(RANDOM_FACTORS.length)];
            final int ky = RANDOM_FACTORS[random.nextInt(RANDOM_FACTORS.length)];
            final int fullWidth = alignUpForTest(sourceWidth, kx);
            final int fullHeight = alignUpForTest(sourceHeight, ky);
            final int padding = random.nextInt(5);
            final int stride = fullWidth + padding + random.nextInt(6);
            final int physicalHeight = fullHeight + padding + random.nextInt(5);
            final int destinationTail = random.nextInt(7);
            final int[] source = new int[sourceWidth * sourceHeight + random.nextInt(4)];
            final int[] destination = new int[stride * physicalHeight + destinationTail];

            fillRandomSource(source, sourceWidth * sourceHeight, random);
            fillNonZero(destination, random);
            final Input input = new Input(
                kx,
                ky,
                source,
                sourceWidth,
                sourceHeight,
                destination,
                stride,
                fullWidth,
                fullHeight,
                padding
            );
            final AssertionMode mode = (caseNumber & 1) == 0
                ? AssertionMode.ENABLED
                : AssertionMode.DISABLED;
            final Admission admission = Guard.inspect(input, mode);
            check(admission.admitted(), "random case rejected: " + admission);
            check(
                admission.bounds().width() == Math.min(fullWidth, ceilDivPositive(sourceWidth, kx)),
                "random width bound mismatch: " + admission
            );
            check(
                admission.bounds().height() == Math.min(fullHeight, ceilDivPositive(sourceHeight, ky)),
                "random height bound mismatch: " + admission
            );

            final int[] sourceBefore = source.clone();
            final ExecutionOutcome reference = execute(input, mode, false);
            final ExecutionOutcome candidate = execute(input, mode, true);
            compareSuccessfulOutcomes(reference, candidate, "random case " + caseNumber);
            check(
                Arrays.equals(reference.sourceAfter(), sourceBefore),
                "reference changed source in random case " + caseNumber
            );
            check(
                Arrays.equals(candidate.sourceAfter(), sourceBefore),
                "candidate changed source in random case " + caseNumber
            );
            check(
                reference.blockIterations() == (long) fullWidth * (long) fullHeight,
                "reference full iteration count mismatch in random case " + caseNumber
            );
            check(
                candidate.blockIterations() == admission.candidateIterations(),
                "candidate iteration count mismatch in random case " + caseNumber
            );
            check(
                reference.sourceReads() == (long) sourceWidth * (long) sourceHeight,
                "reference source read count mismatch in random case " + caseNumber
            );
            if (candidate.blockIterations() < reference.blockIterations()) {
                trimmedCases++;
            }
            fullIterations += reference.blockIterations();
            trimmedIterations += candidate.blockIterations();
            diffCount += diffCount(reference.destinationAfter(), candidate.destinationAfter());
            if (!Arrays.equals(reference.reads(), candidate.reads())) {
                readOrderDiffCount++;
            }
        }

        check(diffCount == 0, "random whole-backing diff count=" + diffCount);
        check(readOrderDiffCount == 0, "random source read order diffs=" + readOrderDiffCount);
        return new RandomSummary(
            5000,
            diffCount,
            readOrderDiffCount,
            fullIterations,
            trimmedIterations,
            trimmedCases
        );
    }

    private static void runPartialBlockCase() {
        final int[] source = new int[5 * 3];
        Arrays.fill(source, 0xFFFFFFFF);
        final int[] destination = new int[12 * 8 + 3];
        fillNonZero(destination, new Random(0x5303_0001L));
        final Input input = new Input(4, 4, source, 5, 3, destination, 12, 8, 4, 2);
        final Admission admission = Guard.inspect(input, AssertionMode.ENABLED);
        check(admission.admitted(), "partial-block input rejected: " + admission);
        final ExecutionOutcome reference = execute(input, AssertionMode.ENABLED, false);
        final ExecutionOutcome candidate = execute(input, AssertionMode.ENABLED, true);
        compareSuccessfulOutcomes(reference, candidate, "partial-block");
        final int first = 2 * 12 + 2;
        check(
            reference.destinationAfter()[first] == 0xBFFFFFFF,
            "partial first block expected bfffffff but was "
                + hex(reference.destinationAfter()[first])
        );
        check(
            reference.destinationAfter()[first + 1] == 0x2FFFFFFF,
            "partial second block expected 2fffffff but was "
                + hex(reference.destinationAfter()[first + 1])
        );
        check(
            reference.blockIterations() == 8L * 4L,
            "partial full iteration count mismatch"
        );
        check(
            candidate.blockIterations() == 2L * 1L,
            "partial trimmed iteration count mismatch"
        );
        System.out.println("partialBlock=PASS bfffffff/2fffffff");
    }

    private static void runAlphaTruncationCases() {
        final int[] expected = {
            0x00000000,
            0x01000000,
            0x027F0000,
            0x03AA0000,
            0x04BF0000
        };
        for (int alpha = 0; alpha <= 4; alpha++) {
            final int[] source = {(alpha << 24) | (254 << 16)};
            final int[] destination = new int[3 * 3];
            fillNonZero(destination, new Random(0x5303_1000L + alpha));
            final Input input = new Input(1, 1, source, 1, 1, destination, 3, 1, 1, 1);
            for (final AssertionMode mode : AssertionMode.values()) {
                final Admission admission = Guard.inspect(input, mode);
                check(admission.admitted(), "alpha " + alpha + " rejected: " + admission);
                final ExecutionOutcome reference = execute(input, mode, false);
                final ExecutionOutcome candidate = execute(input, mode, true);
                compareSuccessfulOutcomes(reference, candidate, "alpha " + alpha + " mode " + mode);
                final int outputIndex = 1 * 3 + 1;
                check(
                    reference.destinationAfter()[outputIndex] == expected[alpha],
                    "alpha " + alpha + " expected " + hex(expected[alpha])
                        + " but was " + hex(reference.destinationAfter()[outputIndex])
                );
            }
        }
        System.out.println("alpha0to4Truncation=PASS");
    }

    private static void runTinyFactorOneAndLargeFactorCases() {
        final Input tiny = new Input(
            1,
            1,
            new int[] {0x04010203},
            1,
            1,
            new int[8],
            3,
            1,
            1,
            0
        );
        final Admission tinyAdmission = Guard.inspect(tiny, AssertionMode.ENABLED);
        check(tinyAdmission.admitted(), "tiny factor-one input rejected: " + tinyAdmission);
        check(
            tinyAdmission.reason() == Reason.ACCEPTED_NO_EMPTY_BLOCKS,
            "factor-one falsely reported as a gain: " + tinyAdmission
        );
        check(
            tinyAdmission.bounds().width() == tiny.fullWidth()
                && tinyAdmission.bounds().height() == tiny.fullHeight(),
            "factor-one bound changed"
        );
        compareSuccessfulOutcomes(
            execute(tiny, AssertionMode.ENABLED, false),
            execute(tiny, AssertionMode.ENABLED, true),
            "tiny factor-one"
        );

        final int largeKx = 256;
        final int largeKy = 128;
        final int largeWidth = alignUpForTest(1, largeKx);
        final int largeHeight = alignUpForTest(1, largeKy);
        final Input large = new Input(
            largeKx,
            largeKy,
            new int[] {0x04FE0000},
            1,
            1,
            new int[260 * 130],
            260,
            largeWidth,
            largeHeight,
            2
        );
        final Admission largeAdmission = Guard.inspect(large, AssertionMode.DISABLED);
        check(largeAdmission.admitted(), "large legal factor rejected: " + largeAdmission);
        check(
            largeAdmission.bounds().width() == 1 && largeAdmission.bounds().height() == 1,
            "large factor did not trim to one non-empty block: " + largeAdmission
        );
        final ExecutionOutcome largeReference = execute(large, AssertionMode.DISABLED, false);
        final ExecutionOutcome largeCandidate = execute(large, AssertionMode.DISABLED, true);
        compareSuccessfulOutcomes(largeReference, largeCandidate, "large factor");
        check(largeReference.blockIterations() == 32768L, "large full count mismatch");
        check(largeCandidate.blockIterations() == 1L, "large trimmed count mismatch");
        System.out.println("tinyFactorOneAndLargeFactor=PASS");
    }

    private static void runGuardCases() {
        expectReject(
            new Input(1, 1, null, 1, 1, new int[4], 2, 2, 2, 0),
            AssertionMode.ENABLED,
            Reason.NULL_SOURCE
        );
        expectReject(
            new Input(1, 1, new int[4], 2, 2, null, 2, 2, 2, 0),
            AssertionMode.ENABLED,
            Reason.NULL_DESTINATION
        );
        expectReject(
            new Input(0, 1, new int[1], 1, 1, new int[1], 1, 1, 1, 0),
            AssertionMode.ENABLED,
            Reason.FACTOR_NON_POSITIVE
        );
        expectReject(
            new Input(-2, 1, new int[1], 1, 1, new int[1], 1, 1, 1, 0),
            AssertionMode.ENABLED,
            Reason.FACTOR_NON_POSITIVE
        );
        expectReject(
            new Input(3, 1, new int[3], 3, 1, new int[4], 4, 3, 1, 0),
            AssertionMode.ENABLED,
            Reason.FACTOR_NOT_POWER_OF_TWO
        );
        expectReject(
            new Input(1, 1, new int[1], 0, 1, new int[1], 1, 1, 1, 0),
            AssertionMode.ENABLED,
            Reason.DIMENSION_NON_POSITIVE
        );
        expectReject(
            new Input(1, 1, new int[1], -1, 1, new int[1], 1, 1, 1, 0),
            AssertionMode.ENABLED,
            Reason.DIMENSION_NON_POSITIVE
        );
        expectReject(
            new Input(1, 1, new int[1], 1, 1, new int[1], 0, 1, 1, 0),
            AssertionMode.ENABLED,
            Reason.STRIDE_NON_POSITIVE
        );
        expectReject(
            new Input(1, 1, new int[1], 1, 1, new int[1], 1, 1, 1, -1),
            AssertionMode.ENABLED,
            Reason.PADDING_NEGATIVE
        );

        final int[] aliased = new int[4];
        expectReject(
            new Input(1, 1, aliased, 2, 2, aliased, 2, 2, 2, 0),
            AssertionMode.ENABLED,
            Reason.SOURCE_DESTINATION_ALIAS
        );
        expectReject(
            new Input(2, 2, new int[9], 3, 3, new int[16], 5, 3, 3, 0),
            AssertionMode.ENABLED,
            Reason.ORIGINAL_BOUNDS_MISMATCH
        );
        expectReject(
            new Input(65536, 65536, new int[1], 1, 1, new int[1], 1, 1, 1, 0),
            AssertionMode.ENABLED,
            Reason.BLOCK_AREA_OVERFLOW
        );
        expectReject(
            new Input(1, 1, new int[0], 50000, 50000, new int[0], 50000, 50000, 50000, 0),
            AssertionMode.ENABLED,
            Reason.FULL_AREA_OVERFLOW
        );

        final Input xOverflow = new Input(
            2,
            1,
            new int[0],
            1_500_000_000,
            1,
            new int[0],
            1_500_000_000,
            1_500_000_000,
            1,
            0
        );
        expectRejectWithoutFixedReason(xOverflow, AssertionMode.DISABLED, "x coordinate overflow");

        final Input yOverflow = new Input(
            1,
            2,
            new int[0],
            1,
            1_500_000_000,
            new int[0],
            1,
            1,
            1_500_000_000,
            0
        );
        expectRejectWithoutFixedReason(yOverflow, AssertionMode.DISABLED, "y coordinate overflow");

        final int weightedSourceSize = 256 * 256;
        final Input weightedOverflow = new Input(
            256,
            256,
            new int[weightedSourceSize],
            256,
            256,
            new int[258 * 258],
            258,
            256,
            256,
            2
        );
        expectReject(
            weightedOverflow,
            AssertionMode.ENABLED,
            Reason.SUM_CHANNEL_TIMES_255_OVERFLOW
        );

        final Input assertionUnsatisfied = new Input(
            1,
            1,
            new int[4],
            2,
            2,
            new int[3],
            2,
            2,
            2,
            0
        );
        expectReject(
            assertionUnsatisfied,
            AssertionMode.ENABLED,
            Reason.ORIGINAL_ASSERTION_UNSATISFIED
        );
        expectReject(
            new Input(1, 1, new int[3], 2, 2, new int[4], 2, 2, 2, 0),
            AssertionMode.ENABLED,
            Reason.SOURCE_CAPACITY
        );
        expectReject(
            new Input(1, 1, new int[4], 2, 2, new int[4], 4, 2, 2, 2),
            AssertionMode.ENABLED,
            Reason.TARGET_CAPACITY
        );
        expectReject(
            new Input(1, 1, new int[4], 2, 2, new int[4], 3, 2, 2, 0),
            AssertionMode.ENABLED,
            Reason.TARGET_CAPACITY
        );
        expectReject(
            new Input(1, 1, new int[9], 3, 3, new int[9], 1_073_741_823, 3, 3, 0),
            AssertionMode.ENABLED,
            Reason.TARGET_INDEX_OVERFLOW
        );
        System.out.println("guardRejects=PASS null/factor/dimension/alias/capacity/overflow/assertion");
    }

    private static void runFallbackFailureCases() {
        final Input assertionFailure = new Input(
            1,
            1,
            new int[] {1, 2, 3, 4},
            2,
            2,
            new int[] {9, 8, 7},
            2,
            2,
            2,
            0
        );
        compareFallback(
            assertionFailure,
            AssertionMode.ENABLED,
            "fallback assertion-enabled pre-clear failure",
            AssertionError.class
        );
        compareFallback(
            assertionFailure,
            AssertionMode.DISABLED,
            "fallback assertion-disabled post-clear failure",
            ArrayIndexOutOfBoundsException.class
        );

        final Input sourceFailure = new Input(
            1,
            1,
            new int[] {1, 2, 3},
            2,
            2,
            new int[] {9, 8, 7, 6},
            2,
            2,
            2,
            0
        );
        compareFallback(
            sourceFailure,
            AssertionMode.ENABLED,
            "fallback source capacity failure",
            ArrayIndexOutOfBoundsException.class
        );

        final Input targetFailure = new Input(
            1,
            1,
            new int[] {1, 2, 3, 4},
            2,
            2,
            new int[] {9, 8, 7, 6},
            3,
            2,
            2,
            0
        );
        compareFallback(
            targetFailure,
            AssertionMode.ENABLED,
            "fallback target capacity failure",
            ArrayIndexOutOfBoundsException.class
        );
        System.out.println("fallbackRouteChecks=PASS prefix/clear/exception-type self-consistency");
    }

    private static void runOriginalCfgBoundaryCases() {
        final Input minHeight = new Input(
            1,
            1,
            new int[0],
            1,
            1,
            new int[] {9},
            1,
            1,
            Integer.MIN_VALUE,
            0
        );
        final Input minWidth = new Input(
            1,
            1,
            new int[0],
            1,
            1,
            new int[] {9},
            1,
            Integer.MIN_VALUE,
            1,
            0
        );
        for (final AssertionMode mode : AssertionMode.values()) {
            checkOriginalCfgBoundaryCase(minHeight, mode, false, "reference MIN height " + mode);
            checkOriginalCfgBoundaryCase(minHeight, mode, true, "fallback MIN height " + mode);
            checkOriginalCfgBoundaryCase(minWidth, mode, false, "reference MIN width " + mode);
            checkOriginalCfgBoundaryCase(minWidth, mode, true, "fallback MIN width " + mode);
        }
        System.out.println("originalCfgBoundaryExceptions=PASS 2MINAxesx2assertModesx2routes");
    }

    private static void checkOriginalCfgBoundaryCase(
        final Input input,
        final AssertionMode mode,
        final boolean candidate,
        final String label
    ) {
        final ExecutionOutcome outcome = execute(input, mode, candidate);
        check(
            outcome.failure() != null
                && outcome.failure().getClass() == ArrayIndexOutOfBoundsException.class,
            label + " failure was " + failureType(outcome.failure())
        );
        check(
            Arrays.equals(outcome.destinationAfter(), new int[] {0}),
            label + " destination after failure was " + Arrays.toString(outcome.destinationAfter())
        );
        check(outcome.sourceReads() == 0L, label + " read source before first failing access");
        check(outcome.reads().length == 0, label + " recorded a read before first failing access");
        check(outcome.blockIterations() == 1L, label + " block iterations=" + outcome.blockIterations());
        check(outcome.usedFallback() == candidate, label + " fallback route mismatch");
    }

    private static void compareFallback(
        final Input input,
        final AssertionMode mode,
        final String label,
        final Class<? extends Throwable> expectedFailure
    ) {
        final Admission admission = Guard.inspect(input, mode);
        check(!admission.admitted(), label + " was unexpectedly admitted: " + admission);
        final ExecutionOutcome reference = execute(input, mode, false);
        final ExecutionOutcome candidate = execute(input, mode, true);
        compareExecutionOutcomes(reference, candidate, label);
        check(candidate.usedFallback(), label + " did not use fallback");
        check(
            reference.failure() != null && reference.failure().getClass() == expectedFailure,
            label + " reference failure was " + failureType(reference.failure())
        );
        check(
            candidate.failure() != null && candidate.failure().getClass() == expectedFailure,
            label + " candidate failure was " + failureType(candidate.failure())
        );
    }

    private static void expectReject(
        final Input input,
        final AssertionMode mode,
        final Reason expectedReason
    ) {
        final Admission admission = Guard.inspect(input, mode);
        check(!admission.admitted(), "expected guard rejection " + expectedReason + " but got " + admission);
        check(
            admission.reason() == expectedReason,
            "expected reason " + expectedReason + " but got " + admission
        );
        checkOriginalBounds(input, admission);
        checkGuardDidNotMutate(input);
    }

    private static void expectRejectWithoutFixedReason(
        final Input input,
        final AssertionMode mode,
        final String label
    ) {
        final Admission admission = Guard.inspect(input, mode);
        check(!admission.admitted(), label + " was unexpectedly admitted: " + admission);
        checkOriginalBounds(input, admission);
        checkGuardDidNotMutate(input);
    }

    private static void checkOriginalBounds(final Input input, final Admission admission) {
        check(
            admission.bounds().width() == input.fullWidth()
                && admission.bounds().height() == input.fullHeight(),
            "rejected input did not retain original bounds: " + admission
        );
    }

    private static void checkGuardDidNotMutate(final Input input) {
        if (input.source() != null && input.source() != input.destination()) {
            final int[] sourceBefore = input.source().clone();
            final int[] destinationBefore = input.destination() == null
                ? null
                : input.destination().clone();
            final Admission second = Guard.inspect(input, AssertionMode.DISABLED);
            check(!second.admitted(), "guard mutation check unexpectedly admitted: " + second);
            check(
                Arrays.equals(input.source(), sourceBefore),
                "guard changed source"
            );
            check(
                Arrays.equals(input.destination(), destinationBefore),
                "guard changed destination"
            );
        } else if (input.destination() != null) {
            final int[] destinationBefore = input.destination().clone();
            Guard.inspect(input, AssertionMode.DISABLED);
            check(
                Arrays.equals(input.destination(), destinationBefore),
                "guard changed destination in alias/null case"
            );
        }
    }

    private static ExecutionOutcome execute(
        final Input input,
        final AssertionMode mode,
        final boolean candidate
    ) {
        final int[] destination = input.destination() == null ? null : input.destination().clone();
        final int[] source;
        if (input.source() == null) {
            source = null;
        } else if (input.source() == input.destination()) {
            source = destination;
        } else {
            source = input.source().clone();
        }
        final Input isolated = new Input(
            input.kx(),
            input.ky(),
            source,
            input.sourceWidth(),
            input.sourceHeight(),
            destination,
            input.stride(),
            input.fullWidth(),
            input.fullHeight(),
            input.padding()
        );
        final ReadTrace trace = new ReadTrace();
        final Counters counters = new Counters();
        final Admission admission = candidate ? Guard.inspect(input, mode) : null;
        final boolean usedFallback = candidate && !admission.admitted();
        Throwable failure = null;
        try {
            if (candidate && admission.admitted()) {
                CandidateKernel.run(isolated, admission, trace, counters);
            } else {
                ReferenceKernel.run(isolated, mode, trace, counters);
            }
        } catch (final RuntimeException | AssertionError exception) {
            failure = exception;
        }
        return new ExecutionOutcome(
            destination,
            source,
            trace.snapshot(),
            counters.blockIterations,
            counters.sourceReads,
            failure,
            usedFallback
        );
    }

    private static void compareSuccessfulOutcomes(
        final ExecutionOutcome reference,
        final ExecutionOutcome candidate,
        final String label
    ) {
        compareExecutionOutcomes(reference, candidate, label);
        check(reference.failure() == null, label + " reference failed: " + failureType(reference.failure()));
        check(candidate.failure() == null, label + " candidate failed: " + failureType(candidate.failure()));
        check(!candidate.usedFallback(), label + " unexpectedly used fallback");
    }

    private static void compareExecutionOutcomes(
        final ExecutionOutcome reference,
        final ExecutionOutcome candidate,
        final String label
    ) {
        check(
            Arrays.equals(reference.destinationAfter(), candidate.destinationAfter()),
            label + " destination differs at "
                + firstDiff(reference.destinationAfter(), candidate.destinationAfter())
        );
        check(
            Arrays.equals(reference.sourceAfter(), candidate.sourceAfter()),
            label + " source state differs"
        );
        check(
            Arrays.equals(reference.reads(), candidate.reads()),
            label + " source read order differs at "
                + firstDiff(reference.reads(), candidate.reads())
        );
        check(
            sameFailureType(reference.failure(), candidate.failure()),
            label + " exception differs reference=" + failureType(reference.failure())
                + " candidate=" + failureType(candidate.failure())
        );
    }

    private static boolean sameFailureType(final Throwable first, final Throwable second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.getClass() == second.getClass();
    }

    private static String failureType(final Throwable failure) {
        return failure == null ? "none" : failure.getClass().getName();
    }

    private static int diffCount(final int[] first, final int[] second) {
        check(first.length == second.length, "diff arrays have different lengths");
        int count = 0;
        for (int index = 0; index < first.length; index++) {
            if (first[index] != second[index]) {
                count++;
            }
        }
        return count;
    }

    private static String firstDiff(final int[] first, final int[] second) {
        if (first == null || second == null) {
            return first == second ? "none" : "null-array";
        }
        final int limit = Math.min(first.length, second.length);
        for (int index = 0; index < limit; index++) {
            if (first[index] != second[index]) {
                return "index=" + index + " first=" + hex(first[index])
                    + " second=" + hex(second[index]);
            }
        }
        return first.length == second.length ? "none" : "length";
    }

    private static String hex(final int value) {
        return String.format("0x%08x", value);
    }

    private static void fillRandomSource(
        final int[] source,
        final int logicalLength,
        final Random random
    ) {
        for (int index = 0; index < source.length; index++) {
            final int alpha = RANDOM_ALPHAS[random.nextInt(RANDOM_ALPHAS.length)];
            final int rgb = random.nextInt() & 0x00FFFFFF;
            source[index] = (alpha << 24) | rgb;
        }
        for (int index = logicalLength; index < source.length; index++) {
            source[index] = random.nextInt();
        }
    }

    private static void fillNonZero(final int[] values, final Random random) {
        for (int index = 0; index < values.length; index++) {
            values[index] = random.nextInt() | 1;
        }
    }

    private static int alignUpForTest(final int value, final int factor) {
        final long result = ((long) value + (long) factor - 1L)
            & ~((long) factor - 1L);
        check(result > 0L && result <= MAX_INT, "test align-up overflow");
        return (int) result;
    }

    private static int ceilDivPositive(final int value, final int divisor) {
        check(value > 0 && divisor > 0, "ceilDivPositive received invalid input");
        final long quotient = (long) value / divisor;
        final long result = value % divisor == 0 ? quotient : quotient + 1L;
        check(result <= MAX_INT, "ceilDivPositive overflow");
        return (int) result;
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private enum AssertionMode {
        ENABLED,
        DISABLED
    }

    private enum Reason {
        ACCEPTED_TRIMMED,
        ACCEPTED_NO_EMPTY_BLOCKS,
        NULL_SOURCE,
        NULL_DESTINATION,
        SOURCE_DESTINATION_ALIAS,
        FACTOR_NON_POSITIVE,
        FACTOR_NOT_POWER_OF_TWO,
        BLOCK_AREA_OVERFLOW,
        DIMENSION_NON_POSITIVE,
        STRIDE_NON_POSITIVE,
        PADDING_NEGATIVE,
        ORIGINAL_BOUNDS_OVERFLOW,
        ORIGINAL_BOUNDS_MISMATCH,
        FULL_AREA_OVERFLOW,
        ORIGINAL_ASSERTION_UNSATISFIED,
        SOURCE_AREA_OVERFLOW,
        X_COORDINATE_OVERFLOW,
        Y_COORDINATE_OVERFLOW,
        SUM_ALPHA_OVERFLOW,
        SUM_CHANNEL_TIMES_255_OVERFLOW,
        TARGET_COORDINATE_OVERFLOW,
        TARGET_INDEX_OVERFLOW,
        TARGET_STRIDE_TOO_SMALL,
        SOURCE_CAPACITY,
        TARGET_CAPACITY
    }

    private record Input(
        int kx,
        int ky,
        int[] source,
        int sourceWidth,
        int sourceHeight,
        int[] destination,
        int stride,
        int fullWidth,
        int fullHeight,
        int padding
    ) {
    }

    private record LoopBounds(int width, int height) {
    }

    private record Admission(
        boolean admitted,
        LoopBounds bounds,
        Reason reason,
        String detail,
        long fullIterations,
        long candidateIterations
    ) {
    }

    private record RandomSummary(
        int cases,
        int diffCount,
        int readOrderDiffCount,
        long fullIterations,
        long trimmedIterations,
        int trimmedCases
    ) {
    }

    private record ExecutionOutcome(
        int[] destinationAfter,
        int[] sourceAfter,
        int[] reads,
        long blockIterations,
        long sourceReads,
        Throwable failure,
        boolean usedFallback
    ) {
    }

    private static final class Counters {
        private long blockIterations;
        private long sourceReads;
    }

    private static final class ReadTrace {
        private int[] values = new int[16];
        private int size;

        private void record(final int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size] = value;
            size++;
        }

        private int[] snapshot() {
            return Arrays.copyOf(values, size);
        }
    }

    private static final class Guard {

        private Guard() {
        }

        private static Admission inspect(final Input input, final AssertionMode mode) {
            Objects.requireNonNull(input, "input");
            if (input.source() == null) {
                return reject(input, Reason.NULL_SOURCE, "source is null");
            }
            if (input.destination() == null) {
                return reject(input, Reason.NULL_DESTINATION, "destination is null");
            }
            if (input.source() == input.destination()) {
                return reject(input, Reason.SOURCE_DESTINATION_ALIAS, "source and destination are the same array");
            }
            if (input.kx() <= 0 || input.ky() <= 0) {
                return reject(input, Reason.FACTOR_NON_POSITIVE, "factors must be positive");
            }
            if (!isPowerOfTwo(input.kx()) || !isPowerOfTwo(input.ky())) {
                return reject(input, Reason.FACTOR_NOT_POWER_OF_TWO, "factors must be powers of two");
            }
            final long blockArea = (long) input.kx() * (long) input.ky();
            if (blockArea > MAX_INT) {
                return reject(input, Reason.BLOCK_AREA_OVERFLOW, "kx*ky exceeds signed int");
            }
            if (input.sourceWidth() <= 0 || input.sourceHeight() <= 0
                || input.fullWidth() <= 0 || input.fullHeight() <= 0) {
                return reject(input, Reason.DIMENSION_NON_POSITIVE, "logical dimensions must be positive");
            }
            if (input.stride() <= 0) {
                return reject(input, Reason.STRIDE_NON_POSITIVE, "stride must be positive");
            }
            if (input.padding() < 0) {
                return reject(input, Reason.PADDING_NEGATIVE, "padding must be non-negative");
            }

            final long expectedFullWidth = alignUpChecked(input.sourceWidth(), input.kx());
            final long expectedFullHeight = alignUpChecked(input.sourceHeight(), input.ky());
            if (expectedFullWidth <= 0L || expectedFullHeight <= 0L
                || expectedFullWidth > MAX_INT || expectedFullHeight > MAX_INT) {
                return reject(input, Reason.ORIGINAL_BOUNDS_OVERFLOW, "original align-up exceeds signed int");
            }
            if (input.fullWidth() != (int) expectedFullWidth
                || input.fullHeight() != (int) expectedFullHeight) {
                return reject(
                    input,
                    Reason.ORIGINAL_BOUNDS_MISMATCH,
                    "fullWidth/fullHeight are not the original align-up bounds"
                );
            }

            final long fullArea = (long) input.fullWidth() * (long) input.fullHeight();
            if (fullArea > MAX_INT) {
                return reject(input, Reason.FULL_AREA_OVERFLOW, "original fullWidth*fullHeight overflows int");
            }

            final long maxXBlockEndExclusive = (long) input.fullWidth() * (long) input.kx();
            if (maxXBlockEndExclusive > MAX_INT) {
                return reject(
                    input,
                    Reason.X_COORDINATE_OVERFLOW,
                    "x*kx then +kx overflows the original int expression"
                );
            }
            final long maxYBlockEndExclusive = (long) input.fullHeight() * (long) input.ky();
            if (maxYBlockEndExclusive > MAX_INT) {
                return reject(
                    input,
                    Reason.Y_COORDINATE_OVERFLOW,
                    "y*ky then +ky overflows the original int expression"
                );
            }

            final long maxSamplesPerBlock = Math.min((long) input.kx(), input.sourceWidth())
                * Math.min((long) input.ky(), input.sourceHeight());
            if (maxSamplesPerBlock > MAX_ALPHA_SUM_PER_BLOCK / 255L) {
                return reject(input, Reason.SUM_ALPHA_OVERFLOW, "sumAlpha can overflow int");
            }
            if (maxSamplesPerBlock > MAX_CHANNEL_TIMES_255 / MAX_WEIGHTED_SAMPLE) {
                return reject(
                    input,
                    Reason.SUM_CHANNEL_TIMES_255_OVERFLOW,
                    "sumChannel*255 can overflow int"
                );
            }

            final long paddedMaxX = (long) input.fullWidth() - 1L + input.padding();
            final long paddedMaxY = (long) input.fullHeight() - 1L + input.padding();
            if (paddedMaxX > MAX_INT || paddedMaxY > MAX_INT) {
                return reject(input, Reason.TARGET_COORDINATE_OVERFLOW, "padding coordinate overflows int");
            }
            final long requiredStride = (long) input.fullWidth() + input.padding();
            if (requiredStride > MAX_INT || input.stride() < requiredStride) {
                return reject(
                    input,
                    Reason.TARGET_STRIDE_TOO_SMALL,
                    "stride cannot contain the full original x range plus padding"
                );
            }
            final long maxRowOffset = paddedMaxY * (long) input.stride();
            if (maxRowOffset > MAX_INT) {
                return reject(input, Reason.TARGET_COORDINATE_OVERFLOW, "(y+padding)*stride overflows int");
            }
            final long maxDestinationIndex = maxRowOffset + paddedMaxX;
            if (maxDestinationIndex > MAX_INT) {
                return reject(input, Reason.TARGET_INDEX_OVERFLOW, "target index addition overflows int");
            }

            if (input.destination().length < fullArea) {
                return reject(
                    input,
                    Reason.ORIGINAL_ASSERTION_UNSATISFIED,
                    "dst.length < original fullWidth*fullHeight; mode=" + mode
                );
            }
            final long sourceArea = (long) input.sourceWidth() * (long) input.sourceHeight();
            if (sourceArea > MAX_INT) {
                return reject(input, Reason.SOURCE_AREA_OVERFLOW, "sourceWidth*sourceHeight exceeds signed int");
            }
            if (input.source().length < sourceArea) {
                return reject(input, Reason.SOURCE_CAPACITY, "source backing is shorter than sourceWidth*sourceHeight");
            }
            if (maxDestinationIndex >= input.destination().length) {
                return reject(input, Reason.TARGET_CAPACITY, "full original target index is outside destination backing");
            }

            final int loopWidth = Math.min(input.fullWidth(), ceilDivPositive(input.sourceWidth(), input.kx()));
            final int loopHeight = Math.min(input.fullHeight(), ceilDivPositive(input.sourceHeight(), input.ky()));
            final boolean trimmed = loopWidth < input.fullWidth() || loopHeight < input.fullHeight();
            final long candidateIterations = (long) loopWidth * (long) loopHeight;
            return new Admission(
                true,
                new LoopBounds(loopWidth, loopHeight),
                trimmed ? Reason.ACCEPTED_TRIMMED : Reason.ACCEPTED_NO_EMPTY_BLOCKS,
                "reference assertion mode=" + mode + "; original bounds retained for allocation/stride/padding",
                fullArea,
                candidateIterations
            );
        }

        private static Admission reject(
            final Input input,
            final Reason reason,
            final String detail
        ) {
            return new Admission(
                false,
                new LoopBounds(input.fullWidth(), input.fullHeight()),
                reason,
                detail,
                -1L,
                -1L
            );
        }

        private static long alignUpChecked(final int value, final int factor) {
            final long sum = (long) value + (long) factor - 1L;
            return sum & ~((long) factor - 1L);
        }

        private static int ceilDivPositive(final int value, final int divisor) {
            final long quotient = (long) value / divisor;
            final long result = value % divisor == 0 ? quotient : quotient + 1L;
            if (result > MAX_INT) {
                throw new AssertionError("checked ceilDiv overflow");
            }
            return (int) result;
        }

        private static boolean isPowerOfTwo(final int value) {
            return (value & (value - 1)) == 0;
        }
    }

    /**
     * Independent BCI-shaped oracle. It intentionally uses Java int
     * arithmetic for the original method; it does not use the guard's long
     * arithmetic to repair overflow or change exception behavior.
     */
    private static final class ReferenceKernel {

        private ReferenceKernel() {
        }

        private static void run(
            final Input input,
            final AssertionMode mode,
            final ReadTrace trace,
            final Counters counters
        ) {
            // Pure-array contract only: keep source-before-destination null order,
            // the original int assertion expression, and the full backing clear.
            // This does not claim Kotlin Intrinsics messages, stack behavior, or
            // class-initialization behavior are completely equivalent.
            Objects.requireNonNull(input.source(), "source");
            Objects.requireNonNull(input.destination(), "destination");
            final int blockArea = input.kx() * input.ky();
            final boolean originalAssertion = input.destination().length
                >= input.fullWidth() * input.fullHeight();
            if (mode == AssertionMode.ENABLED && !originalAssertion) {
                throw new AssertionError("Assertion failed");
            }
            Arrays.fill(input.destination(), 0);

            final int yEnd = input.fullHeight() - 1;
            int y = 0;
            if (y > yEnd) {
                return;
            }
            while (true) {
                final int sourceY = y * input.ky();
                final int sourceLastY = Math.min(sourceY + input.ky() - 1, input.sourceHeight() - 1);
                final int xEnd = input.fullWidth() - 1;
                int x = 0;
                if (x <= xEnd) {
                    while (true) {
                        counters.blockIterations++;
                        final int sourceX = x * input.kx();
                        final int sourceLastX = Math.min(sourceX + input.kx() - 1, input.sourceWidth() - 1);
                        int sumAlpha = 0;
                        int sumRed = 0;
                        int sumGreen = 0;
                        int sumBlue = 0;
                        int sampleCount = 0;
                        int sy = sourceY;
                        if (sy <= sourceLastY) {
                            while (true) {
                                int sx = sourceX;
                                if (sx <= sourceLastX) {
                                    while (true) {
                                        final int sourceIndex = sy * input.sourceWidth() + sx;
                                        final int pixel = input.source()[sourceIndex];
                                        trace.record(sourceIndex);
                                        counters.sourceReads++;
                                        final int alpha = (pixel >>> 24) & 255;
                                        final int red = (pixel >>> 16) & 255;
                                        final int green = (pixel >>> 8) & 255;
                                        final int blue = pixel & 255;
                                        sumAlpha += alpha;
                                        sumRed += red * alpha / 255;
                                        sumGreen += green * alpha / 255;
                                        sumBlue += blue * alpha / 255;
                                        sampleCount++;
                                        if (sx == sourceLastX) {
                                            break;
                                        }
                                        sx++;
                                    }
                                }
                                if (sy == sourceLastY) {
                                    break;
                                }
                                sy++;
                            }
                        }
                        int outputAlpha = sumAlpha;
                        if (sumAlpha != 0) {
                            if (sampleCount == 0) {
                                // The original branch jumps directly to the store.
                            } else {
                                outputAlpha = sumAlpha / blockArea;
                                sumRed = sumRed * 255 / sumAlpha;
                                sumGreen = sumGreen * 255 / sumAlpha;
                                sumBlue = sumBlue * 255 / sumAlpha;
                            }
                        }
                        final int destinationIndex = (y + input.padding()) * input.stride()
                            + x + input.padding();
                        input.destination()[destinationIndex] = (outputAlpha << 24)
                            | (sumRed << 16)
                            | (sumGreen << 8)
                            | sumBlue;
                        if (x == xEnd) {
                            break;
                        }
                        x++;
                    }
                }
                if (y == yEnd) {
                    break;
                }
                y++;
            }
        }
    }

    /** Candidate: only the loop bounds differ; its pixel body is deliberately separate from the oracle. */
    private static final class CandidateKernel {

        private CandidateKernel() {
        }

        private static void run(
            final Input input,
            final Admission admission,
            final ReadTrace trace,
            final Counters counters
        ) {
            final int blockArea = input.kx() * input.ky();
            Arrays.fill(input.destination(), 0);
            final int loopWidth = admission.bounds().width();
            final int loopHeight = admission.bounds().height();
            for (int y = 0; y < loopHeight; y++) {
                final int sourceY = y * input.ky();
                final int sourceLastY = Math.min(sourceY + input.ky() - 1, input.sourceHeight() - 1);
                for (int x = 0; x < loopWidth; x++) {
                    counters.blockIterations++;
                    final int sourceX = x * input.kx();
                    final int sourceLastX = Math.min(sourceX + input.kx() - 1, input.sourceWidth() - 1);
                    int sumAlpha = 0;
                    int sumRed = 0;
                    int sumGreen = 0;
                    int sumBlue = 0;
                    int sampleCount = 0;
                    for (int sy = sourceY; sy <= sourceLastY; sy++) {
                        for (int sx = sourceX; sx <= sourceLastX; sx++) {
                            final int sourceIndex = sy * input.sourceWidth() + sx;
                            final int pixel = input.source()[sourceIndex];
                            trace.record(sourceIndex);
                            counters.sourceReads++;
                            final int alpha = (pixel >>> 24) & 255;
                            final int red = (pixel >>> 16) & 255;
                            final int green = (pixel >>> 8) & 255;
                            final int blue = pixel & 255;
                            sumAlpha += alpha;
                            sumRed += red * alpha / 255;
                            sumGreen += green * alpha / 255;
                            sumBlue += blue * alpha / 255;
                            sampleCount++;
                        }
                    }
                    int outputAlpha = sumAlpha;
                    if (sumAlpha != 0) {
                        if (sampleCount == 0) {
                            // Kept to mirror the original branch shape.
                        } else {
                            outputAlpha = sumAlpha / blockArea;
                            sumRed = sumRed * 255 / sumAlpha;
                            sumGreen = sumGreen * 255 / sumAlpha;
                            sumBlue = sumBlue * 255 / sumAlpha;
                        }
                    }
                    final int destinationIndex = (y + input.padding()) * input.stride()
                        + x + input.padding();
                    input.destination()[destinationIndex] = (outputAlpha << 24)
                        | (sumRed << 16)
                        | (sumGreen << 8)
                        | sumBlue;
                }
            }
        }
    }
}
