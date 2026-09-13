package dev.turboism.validation.atlasimage.t035;

import dev.turboism.validation.atlasimage.t033.T033FixtureEvents;
import dev.turboism.validation.atlasimage.t033.T033FixtureAdmission;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** T035 offline evidence harness; official bytes are never defined or executed. */
public final class T035OfflineHarness {
    private T035OfflineHarness() {
    }

    public static void main(final String[] args) throws Exception {
        check(args.length == 0, "unexpected harness arguments");
        runExistingRegressions();
        testOfficialDataAndAdapter();
        testOwnedFixture();
        System.out.println("T035_OFFLINE_PASS");
        System.out.println("OFFLINE_PASS");
    }

    private static void runExistingRegressions() throws Exception {
        dev.turboism.validation.atlasimage.t033.T033OfflineHarness.main(new String[0]);
        System.out.println("t035RetainedT030T033=PASS");
    }

    private static void testOfficialDataAndAdapter() throws Exception {
        final List<T035OfficialProfile.Loaded> official = T035OfficialProfile.loadAll();
        int accepted = 0;
        for (final T035OfficialProfile.Loaded loaded : official) {
            T035OfficialProfile.print(loaded);
            final T035OfficialAdapter.Result result = T035OfficialAdapter.apply(loaded);
            check(result.accepted(), loaded.profile().label() + " official adapter rejected: " + result.reason());
            check(result.commonSuperQueries() == 0,
                loaded.profile().label() + " requested type resolution: " + result.commonSuperQueries());
            check(result.heightReads() == 1 && result.widthReads() == 1 && result.boundsCalls() == 1,
                loaded.profile().label() + " patch counts are not 1/1/1");
            final byte[] original = loaded.classBytes();
            final byte[] candidate = result.candidate();
            check(!Arrays.equals(original, candidate), loaded.profile().label() + " candidate did not change");
            final T035Shape.Shape candidateShape = T035Shape.inspect(candidate);
            check(candidateShape.maxLocals() == 39,
                loaded.profile().label() + " candidate maxLocals is not 39: " + candidateShape.maxLocals());
            check(candidateShape.maxStack() >= 15,
                loaded.profile().label() + " candidate maxStack is too small: " + candidateShape.maxStack());
            check(candidateShape.handlerCount() == 0,
                loaded.profile().label() + " candidate added an exception handler");
            final T035InstructionMapping.Mapping mapping =
                T035InstructionMapping.compare(original, candidate, "a");
            check(mapping.originalInstructionCount() == 244,
                loaded.profile().label() + " original inverse-map count=" + mapping.originalInstructionCount());
            check(mapping.candidateInstructionCount() == 265,
                loaded.profile().label() + " candidate inverse-map count=" + mapping.candidateInstructionCount());
            check(mapping.inverseMappedInstructionCount() == 244
                    && mapping.inverseMappedOriginalInstructionsEqual(),
                loaded.profile().label() + " inverse mapping did not recover all original instructions");
            checkMembersUnchanged(original, candidate, loaded.profile().label());
            System.out.println("t035OfficialAdapter version=" + loaded.profile().label()
                + " PASS inserted=21 inverseMapped=244/244 methodInfo=13/13 commonSuperQueries=0");
            accepted++;
        }
        check(accepted == 3, "official profile count=" + accepted);
        System.out.println("t035OfficialDataProfile=PASS versions=5203,5302,5303 classpath=none execution=none");
    }

    private static void checkMembersUnchanged(
        final byte[] original,
        final byte[] candidate,
        final String label
    ) {
        final Map<String, byte[]> before = T035Members.methodChunks(original);
        final Map<String, byte[]> after = T035Members.methodChunks(candidate);
        check(before.size() == 14 && after.size() == 14, label + " method_info count changed");
        int equal = 0;
        for (final Map.Entry<String, byte[]> entry : before.entrySet()) {
            final byte[] other = after.get(entry.getKey());
            check(other != null, label + " method disappeared: " + entry.getKey());
            if (!entry.getKey().equals("a" + T035OfficialProfile.DESCRIPTOR)) {
                check(Arrays.equals(entry.getValue(), other), label + " untouched method_info changed: " + entry.getKey());
                equal++;
            }
        }
        check(Arrays.equals(
            T035Members.targetNonCode(original, "a" + T035OfficialProfile.DESCRIPTOR),
            T035Members.targetNonCode(candidate, "a" + T035OfficialProfile.DESCRIPTOR)
        ), label + " target non-Code method metadata changed");
        check(T035Members.metadata(original).equals(T035Members.metadata(candidate)),
            label + " class/field metadata changed");
        check(equal == 13, label + " untouched method_info equality=" + equal);
    }

    private static void testOwnedFixture() throws Exception {
        final byte[] original = T035OwnedFixtureGenerator.generate();
        final byte[] patched = T035OwnedTransformer.apply(original, true);
        check(!Arrays.equals(original, patched), "owned exact fixture was not patched");
        final T035Shape.Shape originalShape = T035Shape.inspect(original);
        final T035Shape.Shape patchedShape = T035Shape.inspect(patched);
        check(originalShape.methodCount() == 3 && originalShape.handlerCount() == 0,
            "owned fixture metadata shape mismatch");
        check(originalShape.countContains("LDC owned fixture assertion") == 1
                && originalShape.countContains("V 58 13") == 1
                && originalShape.countContains("V 54 13") == 1,
            "owned assertion message-slot reuse shape mismatch");
        check(originalShape.countContains("FRAME [0") > 0
                && originalShape.countContains("[I, 1, 1, 0") > 0,
            "owned TOP this/param9 frame witness missing");
        check(originalShape.countContains(": J 167 ") == 4,
            "owned fixture does not have four direct backedges");
        check(patchedShape.countContains("V 21 35") == 1 && patchedShape.countContains("V 21 36") == 1,
            "owned fixture did not replace exactly two loop-bound reads");
        check(patchedShape.countContains("M 184 " + T033FixtureAdmission.class.getName().replace('.', '/')
                + ".bounds" + T035OwnedFixtureGenerator.BOUNDS_DESCRIPTOR + " false") == 1,
            "owned fixture bounds call missing");
        check(T035OwnedTransformer.apply(original, false) == original,
            "owned optimization-off route changed bytes");

        T035OwnedTransformer.resetParseCount();
        for (final T035OwnedFixtureGenerator.Variant variant : new T035OwnedFixtureGenerator.Variant[] {
            T035OwnedFixtureGenerator.Variant.WRONG_DESCRIPTOR,
            T035OwnedFixtureGenerator.Variant.WRONG_FLAGS,
            T035OwnedFixtureGenerator.Variant.WRONG_BACKEDGE,
            T035OwnedFixtureGenerator.Variant.WRONG_PREDECESSOR,
            T035OwnedFixtureGenerator.Variant.WRONG_FRAME,
            T035OwnedFixtureGenerator.Variant.TYPE_MERGE
        }) {
            final byte[] wrong = T035OwnedFixtureGenerator.generate(variant);
            check(T035OwnedTransformer.apply(wrong, true) == wrong,
                "owned negative control changed bytes: " + variant);
        }
        check(T035OwnedTransformer.parseCount() == 0,
            "owned negative controls reached ASM parse before identity rejection");
        final byte[] repeated = T035OwnedTransformer.apply(patched, true);
        check(repeated == patched && Arrays.equals(repeated, patched), "owned repeat patch was not a no-op");
        System.out.println("t035OwnedGates=PASS descriptor/flags/boundary-predecessor/backedge/repeat/frame/type-merge");

        testOwnedExecution(original, patched);
        System.out.println("t035OwnedFixtureVerify=PASS original/patched/normal/partial/reject/MIN/fault");
    }

    private static void testOwnedExecution(final byte[] original, final byte[] patched) throws Exception {
        final Input normal = input(
            1, 1,
            new int[] {0x10203040, 0x80a0b0c0, 0x01020304, 0xffeeddcc}, 2, 2,
            new int[] {0x11111111, 0x22222222, 0x33333333, 0x44444444}, 2, 2, 2, 0
        );
        compare(original, patched, false, normal, T033FixtureEvents.FaultPoint.NONE, "normal");

        final Input partial = partialInput();
        testOwnedTrimmedCase(original, patched, partial);

        final int[] aliasBacking = new int[] {1, 2, 3, 4};
        final Input aliasInput = input(1, 1, aliasBacking, 2, 2, aliasBacking, 2, 2, 2, 0);
        check(aliasInput.source() == aliasInput.destination(), "alias fixture did not use one backing array");
        check(!T033FixtureAdmission.isAdmitted(
            aliasInput.kx(), aliasInput.ky(), aliasInput.source(), aliasInput.sourceWidth(),
            aliasInput.sourceHeight(), aliasInput.destination(), aliasInput.stride(),
            aliasInput.fullWidth(), aliasInput.fullHeight(), aliasInput.padding()),
            "alias fixture was admitted");

        final List<RejectCase> rejects = List.of(
            new RejectCase("null-source", input(1, 1, null, 1, 1, new int[] {9}, 1, 1, 1, 0), false),
            new RejectCase("null-destination", input(1, 1, new int[] {1}, 1, 1, null, 1, 1, 1, 0), true),
            new RejectCase("alias", aliasInput, false),
            new RejectCase("destination-capacity", input(1, 1, new int[] {1, 2, 3, 4}, 2, 2,
                new int[] {9, 9, 9}, 2, 2, 2, 0), false),
            new RejectCase("mismatched-full-size", input(1, 1, new int[] {1, 2, 3, 4}, 2, 2,
                new int[] {9, 9}, 1, 1, 1, 0), false)
        );
        int rejectCount = 0;
        for (final RejectCase reject : rejects) {
            compare(original, patched, reject.assertionsEnabled(), reject.input(),
                T033FixtureEvents.FaultPoint.NONE, "reject " + reject.name());
            rejectCount++;
        }

        int minCount = 0;
        for (final boolean assertionsEnabled : new boolean[] {false, true}) {
            for (final boolean minHeight : new boolean[] {true, false}) {
                final Input minInput = minHeight
                    ? input(1, 1, new int[0], 1, 1, new int[] {0x7f7f7f7f}, 1, 1,
                        Integer.MIN_VALUE, 0)
                    : input(1, 1, new int[0], 1, 1, new int[] {0x7f7f7f7f}, 1,
                        Integer.MIN_VALUE, 1, 0);
                final String label = "MIN " + (minHeight ? "height" : "width")
                    + " assertions=" + assertionsEnabled;
                final RunResult reference = execute(original, assertionsEnabled, minInput,
                    T033FixtureEvents.FaultPoint.NONE);
                final RunResult candidate = execute(patched, assertionsEnabled, minInput,
                    T033FixtureEvents.FaultPoint.NONE);
                compareResults(reference, candidate, label);
                check(reference.failure() != null
                        && reference.failure().getClass() == ArrayIndexOutOfBoundsException.class,
                    label + " reference did not fail on first source read");
                check(candidate.failure() != null
                        && candidate.failure().getClass() == ArrayIndexOutOfBoundsException.class,
                    label + " candidate did not fail on first source read");
                check(Arrays.equals(reference.destination(), new int[] {0})
                        && Arrays.equals(candidate.destination(), new int[] {0}),
                    label + " backing was not clear-only");
                check(reference.events().clearCount() == 1 && candidate.events().clearCount() == 1,
                    label + " clear count");
                check(reference.events().sourceReadCount() == 0 && candidate.events().sourceReadCount() == 0,
                    label + " source read preceded failure");
                minCount++;
            }
        }
        int faultCount = 0;
        for (final T033FixtureEvents.FaultPoint fault : new T033FixtureEvents.FaultPoint[] {
            T033FixtureEvents.FaultPoint.AFTER_CLEAR,
            T033FixtureEvents.FaultPoint.AFTER_SOURCE_READ,
            T033FixtureEvents.FaultPoint.AFTER_TARGET_WRITE
        }) {
            final RunResult reference = execute(original, false, normal, fault);
            final RunResult candidate = execute(patched, false, normal, fault);
            compareResults(reference, candidate, "fault " + fault);
            check(reference.events().faultThrowCount() == 1 && candidate.events().faultThrowCount() == 1,
                "fault was thrown more than once: " + fault);
            faultCount++;
        }
        System.out.println("t035OwnedExecution=PASS normal=1 partial=1 rejects=" + rejectCount
            + " MIN=" + minCount + " faults=" + faultCount + " backing/source/exception/events");
    }

    private static void testOwnedTrimmedCase(
        final byte[] original,
        final byte[] patched,
        final Input partial
    ) throws Exception {
        check(T033FixtureAdmission.isAdmitted(
            partial.kx(), partial.ky(), partial.source(), partial.sourceWidth(), partial.sourceHeight(),
            partial.destination(), partial.stride(), partial.fullWidth(), partial.fullHeight(), partial.padding()),
            "partial fixture was not admitted");
        final long unoptimizedBounds = T033FixtureAdmission.bounds(
            partial.kx(), partial.ky(), partial.source(), partial.sourceWidth(), partial.sourceHeight(),
            partial.destination(), partial.stride(), partial.fullWidth(), partial.fullHeight(), partial.padding(), false);
        final long optimizedBounds = T033FixtureAdmission.bounds(
            partial.kx(), partial.ky(), partial.source(), partial.sourceWidth(), partial.sourceHeight(),
            partial.destination(), partial.stride(), partial.fullWidth(), partial.fullHeight(), partial.padding(), true);
        check(unpackWidth(unoptimizedBounds) == 8
                && unpackHeight(unoptimizedBounds) == 4,
            "partial unoptimized bounds changed");
        check(unpackWidth(optimizedBounds) == 2
                && unpackHeight(optimizedBounds) == 1,
            "partial optimized bounds are not ceil-divided");
        check(unpackWidth(optimizedBounds)
                    < unpackWidth(unoptimizedBounds)
                && unpackHeight(optimizedBounds)
                    < unpackHeight(unoptimizedBounds),
            "partial bounds were not strictly trimmed");
        // Mixed-alpha case below is separate from the opaque-output witness.

        final RunResult reference = execute(original, false, partial, T033FixtureEvents.FaultPoint.NONE);
        final RunResult candidate = execute(patched, false, partial, T033FixtureEvents.FaultPoint.NONE);
        compareTrimmedResults(reference, candidate, "partial");
        check(reference.failure() == null && candidate.failure() == null,
            "partial valid execution failed");
        check(reference.events().clearCount() == 1 && candidate.events().clearCount() == 1,
            "partial clear count");
        check(reference.events().sourceReadCount() == 15 && candidate.events().sourceReadCount() == 15,
            "partial source read count");
        check(candidate.events().targetWriteCount() < reference.events().targetWriteCount(),
            "partial candidate did not delete empty-block writes");
        final int expected = expectedPartialPixel(partial.source());
        check(reference.destination()[26] == expected && candidate.destination()[26] == expected,
            "partial opaque pixel differs: expected=" + Integer.toHexString(expected));
        final int[] opaqueSource = new int[15];
        Arrays.fill(opaqueSource, 0xffffffff);
        final Input opaque = input(4, 4, opaqueSource, 5, 3, dirtyDestination(84), 12, 8, 4, 2);
        final RunResult opaqueOriginal = execute(original, false, opaque, T033FixtureEvents.FaultPoint.NONE);
        final RunResult opaquePatched = execute(patched, false, opaque, T033FixtureEvents.FaultPoint.NONE);
        compareTrimmedResults(opaqueOriginal, opaquePatched, "opaque partial");
        check(opaqueOriginal.failure() == null && opaquePatched.failure() == null,
            "opaque partial unexpectedly failed");
        for (final RunResult result : new RunResult[] {opaqueOriginal, opaquePatched}) {
            check(result.destination()[26] == 0xbfffffff && result.destination()[27] == 0x2fffffff,
                "opaque partial output must be bfffffff/2fffffff");
            check(Arrays.equals(result.source(), opaqueSource), "opaque source was modified");
            check(result.events().clearCount() == 1, "opaque clear count");
        }
        check(partial.destination()[70] != 0, "partial destination tail was not prefilled");
        for (int index = 70; index < partial.destination().length; index++) {
            check(reference.destination()[index] == 0 && candidate.destination()[index] == 0,
                "partial dirty tail was not cleared at index " + index);
        }

        int faultCount = 0;
        for (final T033FixtureEvents.FaultPoint fault : new T033FixtureEvents.FaultPoint[] {
            T033FixtureEvents.FaultPoint.AFTER_CLEAR,
            T033FixtureEvents.FaultPoint.AFTER_SOURCE_READ,
            T033FixtureEvents.FaultPoint.AFTER_TARGET_WRITE
        }) {
            final RunResult faultReference = execute(original, false, partial, fault);
            final RunResult faultCandidate = execute(patched, false, partial, fault);
            compareTrimmedResults(faultReference, faultCandidate, "partial fault " + fault);
            check(faultReference.events().clearCount() == 1
                    && faultCandidate.events().clearCount() == 1,
                "partial fault clear count: " + fault);
            check(faultReference.events().faultThrowCount() == 1
                    && faultCandidate.events().faultThrowCount() == 1,
                "partial fault was thrown more than once: " + fault);
            faultCount++;
        }
        System.out.println("t035OwnedTrim=PASS k=4 sw=5 sh=3 full=8x4 stride=12 padding=2"
            + " bounds=2x1 sourceReads=15 targetWrites="
            + reference.events().targetWriteCount() + "/" + candidate.events().targetWriteCount()
            + " opaqueOutput=0xbfffffff/0x2fffffff faults=" + faultCount);
    }

    private static void compareTrimmedResults(
        final RunResult reference,
        final RunResult candidate,
        final String label
    ) {
        final Throwable left = reference.failure();
        final Throwable right = candidate.failure();
        check(left == null ? right == null : right != null && left.getClass() == right.getClass(),
            label + " exception differs: " + left + " / " + right);
        check(Arrays.equals(reference.source(), candidate.source()), label + " source differs");
        check(Arrays.equals(reference.destination(), candidate.destination()), label + " backing differs");
        check(reference.events().clearCount() == candidate.events().clearCount(), label + " clear differs");
        check(reference.events().sourceReadCount() == candidate.events().sourceReadCount(),
            label + " source read count differs");
        check(Arrays.equals(reference.events().sourceReads(), candidate.events().sourceReads()),
            label + " source read order differs");
        check(candidate.events().targetWriteCount() <= reference.events().targetWriteCount(),
            label + " candidate wrote more target blocks");
        check(reference.events().faultThrowCount() == candidate.events().faultThrowCount(),
            label + " fault count differs");
    }

    private static Input partialInput() {
        final int[] source = new int[] {
            0xbfffffff, 0x2fffffff, 0xff010203, 0x80040506, 0x7f070809,
            0x400a0b0c, 0x200d0e0f, 0xc0101112, 0x55131415, 0xe0161718,
            0x31191a1b, 0x9c1c1d1e, 0x25212223, 0x74242526, 0xff272829
        };
        return input(4, 4, source, 5, 3, dirtyDestination(84), 12, 8, 4, 2);
    }

    private static int[] dirtyDestination(final int length) {
        final int[] destination = new int[length];
        for (int index = 0; index < destination.length; index++) {
            destination[index] = 0x7f7f7f7f ^ index;
        }
        return destination;
    }

    private static int expectedPartialPixel(final int[] source) {
        int sumAlpha = 0;
        int sumBlue = 0;
        int sumGreen = 0;
        int sumRed = 0;
        for (int sy = 0; sy < 3; sy++) {
            for (int sx = 0; sx < 4; sx++) {
                final int pixel = source[sy * 5 + sx];
                final int alpha = (pixel >>> 24) & 255;
                final int blue = (pixel >>> 16) & 255;
                final int green = (pixel >>> 8) & 255;
                final int red = pixel & 255;
                sumAlpha += alpha;
                sumBlue += blue * alpha / 255;
                sumGreen += green * alpha / 255;
                sumRed += red * alpha / 255;
            }
        }
        int outputAlpha = 0;
        if (sumAlpha != 0) {
            outputAlpha = sumAlpha / 16;
            sumBlue = sumBlue * 255 / sumAlpha;
            sumGreen = sumGreen * 255 / sumAlpha;
            sumRed = sumRed * 255 / sumAlpha;
        }
        return (outputAlpha << 24) | (sumBlue << 16) | (sumGreen << 8) | sumRed;
    }

    private static int unpackWidth(final long packed) {
        return (int) packed;
    }

    private static int unpackHeight(final long packed) {
        return (int) (packed >>> 32);
    }

    private static void compare(
        final byte[] original,
        final byte[] patched,
        final boolean assertionsEnabled,
        final Input input,
        final T033FixtureEvents.FaultPoint fault,
        final String label
    ) throws Exception {
        final RunResult reference = execute(original, assertionsEnabled, input, fault);
        final RunResult candidate = execute(patched, assertionsEnabled, input, fault);
        compareResults(reference, candidate, label);
    }

    private static void compareResults(
        final RunResult reference,
        final RunResult candidate,
        final String label
    ) {
        final Throwable left = reference.failure();
        final Throwable right = candidate.failure();
        check(left == null ? right == null : right != null && left.getClass() == right.getClass(),
            label + " exception differs: " + left + " / " + right);
        check(Arrays.equals(reference.source(), candidate.source()), label + " source differs");
        check(Arrays.equals(reference.destination(), candidate.destination()), label + " backing differs");
        check(eventsEqual(reference.events(), candidate.events()), label + " events differ");
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

    private static RunResult execute(
        final byte[] classBytes,
        final boolean assertionsEnabled,
        final Input input,
        final T033FixtureEvents.FaultPoint fault
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
        T033FixtureEvents.reset(fault);
        Throwable failure = null;
        final Class<?> fixtureClass = new FixtureLoader().define(classBytes);
        final Object fixture = fixtureClass.getConstructor().newInstance();
        final Method setter = fixtureClass.getMethod("setAssertionsEnabled", boolean.class);
        final Method render = fixtureClass.getMethod(
            "render", int.class, int.class, int[].class, int.class, int.class, int[].class,
            int.class, int.class, int.class, int.class
        );
        try {
            setter.invoke(null, assertionsEnabled);
            render.invoke(fixture, input.kx(), input.ky(), source, input.sourceWidth(),
                input.sourceHeight(), destination, input.stride(), input.fullWidth(),
                input.fullHeight(), input.padding());
        } catch (final InvocationTargetException exception) {
            failure = exception.getCause();
        }
        return new RunResult(source, destination, T033FixtureEvents.snapshot(), failure);
    }

    private static Input input(
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

    private record RejectCase(String name, Input input, boolean assertionsEnabled) {
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
            super(T035OfflineHarness.class.getClassLoader());
        }

        private Class<?> define(final byte[] bytes) {
            return defineClass(T035OwnedFixtureGenerator.CLASS_NAME, bytes, 0, bytes.length);
        }
    }
}
