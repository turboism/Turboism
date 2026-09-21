package dev.turboism.validation.atlasimage.t033;

import dev.turboism.validation.atlasimage.AtlasImageKernelProbe;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import dev.turboism.validation.atlasimage.shaded.asm97.Opcodes;

public final class T033OfflineHarness {
    private static final int[] RANDOM_FACTORS = {1, 2, 4, 8, 16};
    private static final long RANDOM_SEED = 0x202709072027L;

    private T033OfflineHarness() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--print-identities")) {
            printFixtureIdentity();
            return;
        }
        check(args.length == 0, "unexpected harness arguments");
        AtlasImageKernelProbe.main(new String[0]);
        final byte[] original = T033FixtureGenerator.generate();
        final byte[] patched = T033Transformer.apply(original, true);
        testShapeGates(original, patched);
        testOptimizationSwitch(original, patched);
        testRandomWholeBacking(original, patched);
        testFactorOne(original, patched);
        testGuardRejects();
        testFiniteRejectedExecution(original, patched);
        testRejectedBoundsNegativeControl(original, patched);
        testMinBoundaryRejects(original, patched);
        testFaultPropagation(original, patched);
        System.out.println("T033_OFFLINE_PASS");
        System.out.println("OFFLINE_PASS");
    }

    private static void printFixtureIdentity() {
        final byte[] original = T033FixtureGenerator.generate();
        final T033ShapeInspector.Shape shape = T033ShapeInspector.inspect(original);
        System.out.println("t033FixtureClassSha256=" + T033ShapeInspector.sha256(original));
        System.out.println("t033FixtureMethodShapeSha256=" + shape.sha256());
        System.out.println("t033FixtureClassName=" + shape.className());
        System.out.println("t033FixtureClassAccess=" + shape.classAccess());
        System.out.println("t033FixtureMethodCount=" + shape.methodCount());
        System.out.println("t033FixtureMethodAccess=" + shape.methodAccess());
        System.out.println("t033FixtureMethodDescriptor=" + shape.descriptor());
        System.out.println("t033FixtureMaxLocals=" + shape.maxLocals());
        System.out.println("t033FixtureHeightBoundaryCandidates=" + shape.heightBoundaryCandidates());
        System.out.println("t033FixtureWidthBoundaryCandidates=" + shape.widthBoundaryCandidates());
        System.out.println("t033FixtureFillCount=" + shape.fillCount());
        System.out.println("t033FixtureLoopEntryCount=" + shape.loopEntryCount());
        System.out.println("t033FixtureLoopExitCount=" + shape.loopExitCount());
    }

    private static void testShapeGates(final byte[] original, final byte[] patched) {
        final T033ShapeInspector.Shape originalShape = T033ShapeInspector.inspect(original);
        check(originalShape.exactMethodIdentity(), "generated render identity is not exact");
        check(originalShape.hasExpectedControlShape(), "generated fixture control shape is not exact");
        check(T033Transformer.apply(original, false) == original, "optimization-off route changed bytes");
        check(!Arrays.equals(original, patched), "enabled transform did not change exact fixture");
        final T033ShapeInspector.Shape patchedShape = T033ShapeInspector.inspect(patched);
        check(patchedShape.maxLocals() > originalShape.maxLocals(), "patch did not allocate boundary locals");
        check(patchedShape.heightBoundaryCandidates() == 0, "height boundary read was not replaced");
        check(patchedShape.widthBoundaryCandidates() == 0, "width boundary read was not replaced");
        check(countPrefix(patchedShape.events(), "V:" + Opcodes.ILOAD + ":35") == 1,
            "patched height local load count mismatch");
        check(countPrefix(patchedShape.events(), "V:" + Opcodes.ILOAD + ":36") == 1,
            "patched width local load count mismatch");
        check(countPrefix(patchedShape.events(), "M:" + Opcodes.INVOKESTATIC
            + ":" + T033FixtureGenerator.ADMISSION_OWNER + ":bounds:"
            + T033FixtureGenerator.BOUNDS_DESCRIPTOR + ":false") == 1,
            "bounds helper insertion count mismatch");
        check(countPrefix(patchedShape.events(), "M:" + Opcodes.INVOKESTATIC
            + ":java/util/Arrays:fill:([IIII)V:false") == 1,
            "full clear count changed by patch");
        check(countPrefix(patchedShape.events(), "M:" + Opcodes.INVOKESTATIC
            + ":" + T033FixtureGenerator.EVENTS_OWNER + ":afterClear:()V:false") == 1,
            "after-clear event count changed by patch");
        check(countPrefix(originalShape.events(), "V:" + Opcodes.ILOAD + ":8") > 0,
            "original fullWidth assertion/input read missing");
        check(countPrefix(originalShape.events(), "V:" + Opcodes.ILOAD + ":9") > 0,
            "original fullHeight assertion/input read missing");
        check(countPrefix(patchedShape.events(), "V:" + Opcodes.ILOAD + ":8") > 0,
            "patched fullWidth assertion/input read missing");
        check(countPrefix(patchedShape.events(), "V:" + Opcodes.ILOAD + ":9") > 0,
            "patched fullHeight assertion/input read missing");

        final byte[] repeated = T033Transformer.apply(patched, true);
        check(repeated == patched, "repeated patch was not a no-op");
        check(Arrays.equals(repeated, patched), "repeated patch changed bytes");
        for (final T033FixtureGenerator.Variant variant : new T033FixtureGenerator.Variant[] {
            T033FixtureGenerator.Variant.WRONG_DESCRIPTOR,
            T033FixtureGenerator.Variant.WRONG_FLAGS,
            T033FixtureGenerator.Variant.WRONG_SHAPE
        }) {
            final byte[] wrong = T033FixtureGenerator.generate(variant);
            check(T033Transformer.apply(wrong, true) == wrong,
                "shape gate changed " + variant + " fixture");
        }
        System.out.println("t033ShapeGate=PASS exactHashAndShape/twoBoundaries/oneClear");
    }

    private static void testOptimizationSwitch(final byte[] original, final byte[] patched) throws Exception {
        final Input input = makeInput(
            2,
            2,
            new int[] {0x10203040, 0x80a0b0c0, 0x01020304, 0xffeeddcc},
            2,
            2,
            new int[] {0x11111111, 0x22222222, 0x33333333, 0x44444444, 0x55555555,
                0x66666666, 0x77777777, 0x88888888, 0x99999999},
            3,
            2,
            2,
            1
        );
        check(T033FixtureAdmission.isAdmitted(
            input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
            input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding()),
            "optimization switch input was rejected");
        final byte[] offBytes = T033Transformer.apply(original, false);
        check(offBytes == original, "optimization-off bytes changed");
        final RunResult off = execute(offBytes, false, input, T033FixtureEvents.FaultPoint.NONE);
        final RunResult on = execute(patched, false, input, T033FixtureEvents.FaultPoint.NONE);
        compareSuccessful(off, on, "optimization switch");
        check(Arrays.equals(off.destination(), on.destination()), "optimization switch backing diff");
        System.out.println("t033OptimizationOnOff=PASS");
    }

    private static void testRandomWholeBacking(final byte[] original, final byte[] patched) throws Exception {
        final Random random = new Random(RANDOM_SEED);
        long fullIterations = 0L;
        long trimmedIterations = 0L;
        int destinationDiffCount = 0;
        int readOrderDiffCount = 0;
        int trimmedCases = 0;
        for (int caseNumber = 0; caseNumber < 5000; caseNumber++) {
            final Input input = randomInput(random);
            final boolean assertionsEnabled = (caseNumber & 1) == 0;
            check(T033FixtureAdmission.isAdmitted(
                input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
                input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding()),
                "T033 random case rejected: " + caseNumber);
            final long bounds = T033FixtureAdmission.bounds(
                input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
                input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding(), true);
            final int loopWidth = T033FixtureAdmission.unpackWidth(bounds);
            final int loopHeight = T033FixtureAdmission.unpackHeight(bounds);
            check(loopWidth == Math.min(input.fullWidth(), ceilDiv(input.sourceWidth(), input.kx())),
                "T033 random width mismatch: " + caseNumber);
            check(loopHeight == Math.min(input.fullHeight(), ceilDiv(input.sourceHeight(), input.ky())),
                "T033 random height mismatch: " + caseNumber);
            final int[] sourceBefore = input.source().clone();
            final RunResult reference = execute(original, assertionsEnabled, input, T033FixtureEvents.FaultPoint.NONE);
            final RunResult candidate = execute(patched, assertionsEnabled, input, T033FixtureEvents.FaultPoint.NONE);
            check(reference.failure() == null, "T033 reference failed in random case " + caseNumber);
            check(candidate.failure() == null, "T033 candidate failed in random case " + caseNumber);
            if (!Arrays.equals(reference.destination(), candidate.destination())) {
                destinationDiffCount++;
            }
            if (!Arrays.equals(reference.events().sourceReads(), candidate.events().sourceReads())) {
                readOrderDiffCount++;
            }
            check(Arrays.equals(reference.source(), sourceBefore),
                "T033 reference changed source in random case " + caseNumber);
            check(Arrays.equals(candidate.source(), sourceBefore),
                "T033 candidate changed source in random case " + caseNumber);
            check(reference.events().clearCount() == 1 && candidate.events().clearCount() == 1,
                "T033 clear count mismatch in random case " + caseNumber);
            check(reference.events().sourceReadCount() == candidate.events().sourceReadCount(),
                "T033 source read count mismatch in random case " + caseNumber);
            fullIterations += (long) input.fullWidth() * input.fullHeight();
            trimmedIterations += (long) loopWidth * loopHeight;
            if (loopWidth < input.fullWidth() || loopHeight < input.fullHeight()) {
                trimmedCases++;
            }
        }
        check(destinationDiffCount == 0, "T033 random destination diffs=" + destinationDiffCount);
        check(readOrderDiffCount == 0, "T033 random source-read-order diffs=" + readOrderDiffCount);
        System.out.println("t033RandomWholeBackingCases=5000");
        System.out.println("t033RandomWholeBackingDiffCount=" + destinationDiffCount);
        System.out.println("t033RandomSourceReadOrderDiffCount=" + readOrderDiffCount);
        System.out.println("t033RandomFullIterations=" + fullIterations);
        System.out.println("t033RandomTrimmedIterations=" + trimmedIterations);
        System.out.println("t033RandomTrimmedCases=" + trimmedCases);
    }

    private static void testFactorOne(final byte[] original, final byte[] patched) throws Exception {
        final Input input = makeInput(
            1,
            1,
            new int[] {0x00000000, 0x01020304, 0x80aabbcc, 0xff102030, 0x04050607, 0x010000ff},
            3,
            2,
            new int[] {0x12345678, 0x23456789, 0x3456789a, 0x456789ab, 0x56789abc,
                0x6789abcd, 0x789abcde, 0x89abcdef, 0x9abcdef0, 0xabcdef01,
                0xbcdef012, 0xcdef0123, 0xdef01234, 0xef012345, 0xf0123456},
            5,
            3,
            2,
            1
        );
        check(T033FixtureAdmission.isAdmitted(
            input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
            input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding()),
            "factor-one input was rejected");
        final RunResult reference = execute(original, true, input, T033FixtureEvents.FaultPoint.NONE);
        final RunResult candidate = execute(patched, true, input, T033FixtureEvents.FaultPoint.NONE);
        compareSuccessful(reference, candidate, "factor one");
        check(Arrays.equals(reference.events().targetWrites(), candidate.events().targetWrites()),
            "factor-one target write order changed");
        check(reference.events().targetWriteCount() == (long) input.fullWidth() * input.fullHeight(),
            "factor-one target write count mismatch");
        System.out.println("t033FactorOne=PASS no-empty-block-trim");
    }

    private static void testGuardRejects() {
        final int[] alias = new int[4];
        final List<GuardCase> cases = List.of(
            new GuardCase("factor-zero", makeInput(0, 1, new int[1], 1, 1, new int[1], 1, 1, 1, 0)),
            new GuardCase("factor-not-power-two", makeInput(3, 1, new int[4], 2, 2, new int[4], 2, 4, 2, 0)),
            new GuardCase("negative-dimension", makeInput(1, 1, new int[1], -1, 1, new int[1], 1, 1, 1, 0)),
            new GuardCase("zero-dimension", makeInput(1, 1, new int[0], 0, 1, new int[1], 1, 1, 1, 0)),
            new GuardCase("negative-padding", makeInput(1, 1, new int[1], 1, 1, new int[1], 1, 1, 1, -1)),
            new GuardCase("stride-too-small", makeInput(2, 2, new int[4], 2, 2, new int[4], 1, 2, 2, 0)),
            new GuardCase("full-bounds-mismatch", makeInput(2, 2, new int[4], 2, 2, new int[4], 2, 1, 2, 0)),
            new GuardCase("destination-capacity", makeInput(2, 2, new int[4], 2, 2, new int[3], 2, 2, 2, 0)),
            new GuardCase("source-capacity", makeInput(2, 2, new int[3], 2, 2, new int[4], 2, 2, 2, 0)),
            new GuardCase("area-overflow", makeInput(1 << 30, 2, new int[0], 1, 1, new int[0], 1, 1 << 30, 2, 0)),
            new GuardCase("null-source", makeInput(1, 1, null, 1, 1, new int[1], 1, 1, 1, 0)),
            new GuardCase("null-destination", makeInput(1, 1, new int[1], 1, 1, null, 1, 1, 1, 0)),
            new GuardCase("source-destination-alias", makeInput(1, 1, alias, 2, 2, alias, 2, 2, 2, 0))
        );
        for (final GuardCase guardCase : cases) {
            final Input input = guardCase.input();
            final int[] sourceBefore = input.source() == null ? null : input.source().clone();
            final int[] destinationBefore = input.destination() == null ? null : input.destination().clone();
            final boolean admitted = T033FixtureAdmission.isAdmitted(
                input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
                input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding());
            check(!admitted, "T033 guard unexpectedly admitted " + guardCase.name());
            final long bounds = T033FixtureAdmission.bounds(
                input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
                input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding(), true);
            check(T033FixtureAdmission.unpackWidth(bounds) == input.fullWidth(),
                "T033 rejected width changed for " + guardCase.name());
            check(T033FixtureAdmission.unpackHeight(bounds) == input.fullHeight(),
                "T033 rejected height changed for " + guardCase.name());
            check(Arrays.equals(input.source(), sourceBefore), "T033 guard changed source for " + guardCase.name());
            check(Arrays.equals(input.destination(), destinationBefore),
                "T033 guard changed destination for " + guardCase.name());
        }
        System.out.println("t033GuardRejects=PASS original-bounds/no-side-effects/guard-only");
    }

    private static void testFiniteRejectedExecution(final byte[] original, final byte[] patched) throws Exception {
        final int[] alias = new int[] {0x01020304, 0x05060708, 0x090a0b0c, 0x0d0e0f10};
        final List<FiniteRejectCase> cases = List.of(
            new FiniteRejectCase("alias-normal", makeInput(1, 1, alias, 2, 2, alias, 2, 2, 2, 0), false, false),
            new FiniteRejectCase("mismatched-dimensions-normal",
                makeInput(1, 1, new int[] {1, 2, 3, 4}, 2, 2,
                    new int[] {9, 9, 9, 9, 9, 9, 9, 9}, 4, 1, 2, 0), true, false),
            new FiniteRejectCase("null-destination",
                makeInput(1, 1, new int[] {1}, 1, 1, null, 1, 1, 1, 0), false, true),
            new FiniteRejectCase("null-source",
                makeInput(1, 1, null, 1, 1, new int[] {9}, 1, 1, 1, 0), false, true),
            new FiniteRejectCase("destination-capacity-no-assert",
                makeInput(2, 2, new int[] {1, 2, 3, 4}, 2, 2,
                    new int[] {9, 9, 9}, 2, 2, 2, 0), false, true),
            new FiniteRejectCase("destination-capacity-assert",
                makeInput(2, 2, new int[] {1, 2, 3, 4}, 2, 2,
                    new int[] {9, 9, 9}, 2, 2, 2, 0), true, true),
            new FiniteRejectCase("source-capacity",
                makeInput(2, 2, new int[] {1, 2, 3}, 2, 2,
                    new int[] {9, 9, 9, 9}, 2, 2, 2, 0), false, true),
            new FiniteRejectCase("target-layout",
                makeInput(1, 1, new int[] {0x01020304}, 1, 1,
                    new int[] {9, 9}, 1, 1, 1, 1), true, true)
        );
        int normalCases = 0;
        int failureCases = 0;
        for (final FiniteRejectCase rejectCase : cases) {
            final Input input = rejectCase.input();
            check(!T033FixtureAdmission.isAdmitted(
                input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
                input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding()),
                "T033 finite reject was admitted: " + rejectCase.name());
            final long bounds = T033FixtureAdmission.bounds(
                input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
                input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding(), true);
            check(T033FixtureAdmission.unpackWidth(bounds) == input.fullWidth()
                    && T033FixtureAdmission.unpackHeight(bounds) == input.fullHeight(),
                "T033 finite reject changed original bounds: " + rejectCase.name());
            final RunResult reference = execute(original, rejectCase.assertionsEnabled(), input,
                T033FixtureEvents.FaultPoint.NONE);
            final RunResult candidate = execute(patched, rejectCase.assertionsEnabled(), input,
                T033FixtureEvents.FaultPoint.NONE);
            compareExecutionOutcomes(reference, candidate, "finite reject " + rejectCase.name());
            if (rejectCase.expectedFailure()) {
                check(reference.failure() != null && candidate.failure() != null,
                    "finite reject unexpectedly succeeded: " + rejectCase.name());
                failureCases++;
            } else {
                check(reference.failure() == null && candidate.failure() == null,
                    "finite reject unexpectedly failed: " + rejectCase.name());
                normalCases++;
            }
        }
        System.out.println("t033FiniteGuardRejectExecution=PASS normal=" + normalCases
            + " failure=" + failureCases + "/backing/source/exception/events");
    }

    private static void testRejectedBoundsNegativeControl(final byte[] original, final byte[] patched)
        throws Exception {
        final Input input = makeInput(2, 2, new int[] {1, 2, 3, 4}, 2, 2,
            new int[] {9, 9, 9}, 2, 2, 2, 0);
        check(!T033FixtureAdmission.isAdmitted(
            input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
            input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding()),
            "T033 negative-control input was admitted");
        final long originalBounds = T033FixtureAdmission.bounds(
            input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
            input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding(), true);
        check(T033FixtureAdmission.unpackWidth(originalBounds) == input.fullWidth()
                && T033FixtureAdmission.unpackHeight(originalBounds) == input.fullHeight(),
            "T033 negative-control baseline bounds changed");
        T033FixtureAdmission.setNegativeControlReturnTrimmed(true);
        boolean comparisonDetectedBadBounds = false;
        try {
            final RunResult reference = execute(original, false, input, T033FixtureEvents.FaultPoint.NONE);
            check(reference.failure() != null
                    && reference.failure().getClass() == ArrayIndexOutOfBoundsException.class,
                "T033 negative-control reference did not fail at the finite boundary");
            final RunResult badCandidate = execute(patched, false, input, T033FixtureEvents.FaultPoint.NONE);
            try {
                compareExecutionOutcomes(reference, badCandidate, "negative-control trimmed rejection");
            } catch (final AssertionError expected) {
                comparisonDetectedBadBounds = true;
            }
        } finally {
            T033FixtureAdmission.setNegativeControlReturnTrimmed(false);
        }
        check(comparisonDetectedBadBounds,
            "T033 negative control did not detect incorrectly trimmed rejected bounds");
        System.out.println("t033RejectedBoundsNegativeControl=PASS bad-zero-bounds-detected");
    }

    private static void testMinBoundaryRejects(final byte[] original, final byte[] patched) throws Exception {
        int inputPairs = 0;
        for (final boolean assertionsEnabled : new boolean[] {false, true}) {
            for (final boolean minHeight : new boolean[] {true, false}) {
                final Input input = minHeight
                    ? makeInput(1, 1, new int[0], 1, 1, new int[] {0x7f7f7f7f}, 1, 1,
                        Integer.MIN_VALUE, 0)
                    : makeInput(1, 1, new int[0], 1, 1, new int[] {0x7f7f7f7f}, 1,
                        Integer.MIN_VALUE, 1, 0);
                final String label = "T033 MIN " + (minHeight ? "height" : "width")
                    + " assertions=" + assertionsEnabled;
                check(!T033FixtureAdmission.isAdmitted(
                    input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
                    input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding()),
                    label + " was admitted");
                final long bounds = T033FixtureAdmission.bounds(
                    input.kx(), input.ky(), input.source(), input.sourceWidth(), input.sourceHeight(),
                    input.destination(), input.stride(), input.fullWidth(), input.fullHeight(), input.padding(), true);
                check(T033FixtureAdmission.unpackWidth(bounds) == input.fullWidth()
                        && T033FixtureAdmission.unpackHeight(bounds) == input.fullHeight(),
                    label + " changed original bounds");
                final RunResult reference = execute(original, assertionsEnabled, input,
                    T033FixtureEvents.FaultPoint.NONE);
                final RunResult candidate = execute(patched, assertionsEnabled, input,
                    T033FixtureEvents.FaultPoint.NONE);
                compareExecutionOutcomes(reference, candidate, label);
                check(reference.failure() != null
                        && reference.failure().getClass() == ArrayIndexOutOfBoundsException.class,
                    label + " reference did not produce first-read AIOOBE: " + reference.failure());
                check(candidate.failure() != null
                        && candidate.failure().getClass() == ArrayIndexOutOfBoundsException.class,
                    label + " patched did not produce first-read AIOOBE: " + candidate.failure());
                check(Arrays.equals(reference.destination(), new int[] {0}),
                    label + " reference destination was not only-cleared backing");
                check(Arrays.equals(candidate.destination(), new int[] {0}),
                    label + " patched destination was not only-cleared backing");
                check(reference.events().clearCount() == 1 && candidate.events().clearCount() == 1,
                    label + " clear count mismatch");
                check(reference.events().sourceReadCount() == 0 && candidate.events().sourceReadCount() == 0,
                    label + " source hook ran before failed read");
                check(reference.events().targetWriteCount() == 0 && candidate.events().targetWriteCount() == 0,
                    label + " target write occurred");
                inputPairs++;
            }
        }
        System.out.println("t033MinBoundaryRejects=PASS " + inputPairs
            + " input-pairs/8-direct-executions/first-read-AIOOBE/clear-only");
    }

    private static void testFaultPropagation(final byte[] original, final byte[] patched) throws Exception {
        final Input input = makeInput(
            2,
            2,
            new int[] {0x10203040, 0x80a0b0c0, 0x01020304, 0xffeeddcc},
            2,
            2,
            new int[] {0x11111111, 0x22222222, 0x33333333, 0x44444444, 0x55555555,
                0x66666666, 0x77777777, 0x88888888, 0x99999999},
            3,
            2,
            2,
            1
        );
        for (final T033FixtureEvents.FaultPoint faultPoint : new T033FixtureEvents.FaultPoint[] {
            T033FixtureEvents.FaultPoint.AFTER_CLEAR,
            T033FixtureEvents.FaultPoint.AFTER_SOURCE_READ,
            T033FixtureEvents.FaultPoint.AFTER_TARGET_WRITE
        }) {
            final RunResult reference = execute(original, true, input, faultPoint);
            final RunResult candidate = execute(patched, true, input, faultPoint);
            compareFault(reference, candidate, faultPoint);
        }
        System.out.println("t033FaultPropagation=PASS clear/read/write/one-throw/no-rerun");
    }

    private static void compareSuccessful(
        final RunResult reference,
        final RunResult candidate,
        final String label
    ) {
        check(reference.failure() == null && candidate.failure() == null,
            label + " unexpectedly failed");
        check(Arrays.equals(reference.destination(), candidate.destination()),
            label + " destination backing differs");
        check(Arrays.equals(reference.source(), candidate.source()), label + " source differs");
        check(Arrays.equals(reference.events().sourceReads(), candidate.events().sourceReads()),
            label + " source-read order differs");
        check(reference.events().clearCount() == 1 && candidate.events().clearCount() == 1,
            label + " clear count differs");
    }

    private static void compareFault(
        final RunResult reference,
        final RunResult candidate,
        final T033FixtureEvents.FaultPoint faultPoint
    ) {
        final String label = "fault " + faultPoint;
        check(reference.failure() != null && candidate.failure() != null, label + " was swallowed");
        check(reference.failure().getClass() == T033FixtureEvents.FixtureFault.class,
            label + " reference failure type changed");
        check(candidate.failure().getClass() == T033FixtureEvents.FixtureFault.class,
            label + " candidate failure type changed");
        check(Arrays.equals(reference.destination(), candidate.destination()),
            label + " destination prefix differs");
        check(Arrays.equals(reference.source(), candidate.source()), label + " source changed");
        check(eventsEqual(reference.events(), candidate.events()), label + " event trace differs");
        check(reference.events().clearCount() == 1 && candidate.events().clearCount() == 1,
            label + " clear was repeated");
        check(reference.events().faultThrowCount() == 1 && candidate.events().faultThrowCount() == 1,
            label + " fault was thrown more than once");
    }

    private static boolean eventsEqual(
        final T033FixtureEvents.Snapshot left,
        final T033FixtureEvents.Snapshot right
    ) {
        return left.clearCount() == right.clearCount()
            && left.sourceReadCount() == right.sourceReadCount()
            && left.targetWriteCount() == right.targetWriteCount()
            && left.faultThrowCount() == right.faultThrowCount()
            && Arrays.equals(left.sourceReads(), right.sourceReads())
            && Arrays.equals(left.targetWrites(), right.targetWrites());
    }

    private static void compareExecutionOutcomes(
        final RunResult reference,
        final RunResult candidate,
        final String label
    ) {
        check(sameFailureType(reference.failure(), candidate.failure()),
            label + " exception type differs: reference=" + reference.failure()
                + " candidate=" + candidate.failure());
        check(Arrays.equals(reference.destination(), candidate.destination()),
            label + " destination backing differs");
        check(Arrays.equals(reference.source(), candidate.source()), label + " source differs");
        check(eventsEqual(reference.events(), candidate.events()), label + " event trace differs");
    }

    private static boolean sameFailureType(final Throwable left, final Throwable right) {
        return left == null ? right == null : right != null && left.getClass() == right.getClass();
    }

    private static RunResult execute(
        final byte[] classBytes,
        final boolean assertionsEnabled,
        final Input input,
        final T033FixtureEvents.FaultPoint faultPoint
    ) throws Exception {
        final int[] source = input.source() == null ? null : input.source().clone();
        final int[] destination;
        if (input.destination() == null) {
            destination = null;
        } else if (input.destination() == input.source()) {
            destination = source;
        } else {
            destination = input.destination().clone();
        }
        T033FixtureEvents.reset(faultPoint);
        Throwable failure = null;
        final Class<?> fixtureClass = new FixtureLoader().define(classBytes);
        final Object fixture = fixtureClass.getConstructor().newInstance();
        final Method assertionSetter = fixtureClass.getMethod("setAssertionsEnabled", boolean.class);
        final Method render = fixtureClass.getMethod(
            "render", int.class, int.class, int[].class, int.class, int.class, int[].class,
            int.class, int.class, int.class, int.class);
        try {
            assertionSetter.invoke(null, assertionsEnabled);
            render.invoke(fixture, input.kx(), input.ky(), source, input.sourceWidth(),
                input.sourceHeight(), destination, input.stride(), input.fullWidth(),
                input.fullHeight(), input.padding());
        } catch (final InvocationTargetException exception) {
            failure = exception.getCause();
        }
        return new RunResult(source, destination, T033FixtureEvents.snapshot(), failure);
    }

    private static Input randomInput(final Random random) {
        final int sourceWidth = 1 + random.nextInt(20);
        final int sourceHeight = 1 + random.nextInt(14);
        final int kx = RANDOM_FACTORS[random.nextInt(RANDOM_FACTORS.length)];
        final int ky = RANDOM_FACTORS[random.nextInt(RANDOM_FACTORS.length)];
        final int fullWidth = alignUp(sourceWidth, kx);
        final int fullHeight = alignUp(sourceHeight, ky);
        final int padding = random.nextInt(5);
        final int stride = fullWidth + padding + random.nextInt(6);
        final int physicalHeight = fullHeight + padding + random.nextInt(5);
        final int destinationTail = random.nextInt(7);
        final int[] source = new int[sourceWidth * sourceHeight + random.nextInt(4)];
        final int[] destination = new int[stride * physicalHeight + destinationTail];
        for (int index = 0; index < source.length; index++) {
            source[index] = random.nextInt();
        }
        for (int index = 0; index < destination.length; index++) {
            destination[index] = random.nextInt() | 1;
        }
        return makeInput(kx, ky, source, sourceWidth, sourceHeight, destination, stride,
            fullWidth, fullHeight, padding);
    }

    private static Input makeInput(
        final int kx,
        final int ky,
        final int[] source,
        final int sourceWidth,
        final int sourceHeight,
        final int[] destination,
        final int stride,
        final int fullWidth,
        final int fullHeight,
        final int padding
    ) {
        return new Input(kx, ky, source, sourceWidth, sourceHeight, destination, stride,
            fullWidth, fullHeight, padding);
    }

    private static int alignUp(final int value, final int factor) {
        return (int) (((long) value + factor - 1L) & ~((long) factor - 1L));
    }

    private static int ceilDiv(final int value, final int divisor) {
        final long quotient = (long) value / divisor;
        return (int) (value % divisor == 0 ? quotient : quotient + 1L);
    }

    private static int countPrefix(final List<String> events, final String prefix) {
        int count = 0;
        for (final String event : events) {
            if (event.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
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

    private record GuardCase(String name, Input input) {
    }

    private record FiniteRejectCase(
        String name,
        Input input,
        boolean assertionsEnabled,
        boolean expectedFailure
    ) {
    }

    private record RunResult(
        int[] source,
        int[] destination,
        T033FixtureEvents.Snapshot events,
        Throwable failure
    ) {
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader() {
            super(T033OfflineHarness.class.getClassLoader());
        }

        private Class<?> define(final byte[] bytes) {
            return defineClass(T033FixtureGenerator.CLASS_NAME, bytes, 0, bytes.length);
        }
    }
}
