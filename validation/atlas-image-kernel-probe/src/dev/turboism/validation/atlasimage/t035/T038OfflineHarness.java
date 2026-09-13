package dev.turboism.validation.atlasimage.t035;

import dev.turboism.validation.atlasimage.t033.T033FixtureEvents;
import dev.turboism.validation.atlasimage.t038.T038ArrayHelper;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/** T038 owned-loader integration; official classes remain data-only. */
public final class T038OfflineHarness {
    private T038OfflineHarness() {
    }

    public static void main(final String[] args) throws Exception {
        check(args.length == 0, "unexpected T038 arguments");
        T035OfflineHarness.main(new String[0]);
        final byte[] original = T035OwnedFixtureGenerator.generate();
        final byte[] helperBytes = readHelperBytes();
        final ClassLoader parent = T038OfflineHarness.class.getClassLoader();

        testPureHelperGuard();
        final byte[] patched = testParentDelegation(original, parent);
        testOwnedExecution(original, patched, helperBytes, parent);
        testCoexistingLoaderIsolation(original, patched, helperBytes, parent);
        testGuardRejectionExecution(original, patched, helperBytes, parent);
        testNegativePreflightControls(original, patched, helperBytes, parent);

        System.out.println("t038HelperSha256=" + T035Shape.sha256(helperBytes));
        System.out.println("T038_OFFLINE_PASS");
        System.out.println("OFFLINE_PASS");
    }

    private static void testPureHelperGuard() {
        final int[] source = {0x01020304};
        final int[] destination = {};
        check(!T038ArrayHelper.isAdmitted(
            1, 1, source, 1, 1, destination, 1, 1, 1, 0
        ), "extracted helper admitted invalid target capacity");
        final long rejectedBounds = T038ArrayHelper.bounds(
            1, 1, source, 1, 1, destination, 1, 1, 1, 0, true
        );
        check(T038ArrayHelper.unpackWidth(rejectedBounds) == 1
                && T038ArrayHelper.unpackHeight(rejectedBounds) == 1,
            "extracted helper changed rejected full bounds");
        final int[] partialSource = new int[15];
        final int[] partialDestination = new int[12 * 8 + 3];
        final long acceptedBounds = T038ArrayHelper.bounds(
            4, 4, partialSource, 5, 3, partialDestination, 12, 8, 4, 2, true
        );
        check(T038ArrayHelper.isAdmitted(
            4, 4, partialSource, 5, 3, partialDestination, 12, 8, 4, 2
        ), "extracted helper rejected valid partial input");
        check(T038ArrayHelper.unpackWidth(acceptedBounds) == 2
                && T038ArrayHelper.unpackHeight(acceptedBounds) == 1,
            "extracted helper partial bounds mismatch");
        System.out.println("t038PureArrayHelper=PASS guard/full-bounds/no-pixel-read");
    }

    private static byte[] testParentDelegation(
        final byte[] original,
        final ClassLoader parent
    ) {
        final T038OwnedClassLoader loader = new T038OwnedClassLoader(
            original, parent, null, false
        );
        final Class<?> helper = loader.visibleHelper();
        check(helper == T038ArrayHelper.class, "default parent delegation did not select helper");
        check(helper.getClassLoader() == parent, "parent helper loader identity changed");
        final T038HelperBinding binding = T038HelperBinding.expected(helper, parent);
        final T038OfflineAdapter.Decision decision = T038OfflineAdapter.patch(
            original, loader, binding
        );
        check(decision.accepted(), "parent-visible helper was rejected: " + decision.reason());
        check(loader.fixtureDefineCount() == 0, "preflight defined fixture class");
        check(!Arrays.equals(original, decision.bytes()), "parent patch did not change bytes");
        checkBoundaryShape(original, decision.bytes());
        System.out.println("t038ParentDelegation=PASS helper-visible/preflight-before-define");
        return decision.bytes();
    }

    private static void testOwnedExecution(
        final byte[] original,
        final byte[] patched,
        final byte[] helperBytes,
        final ClassLoader parent
    ) throws Exception {
        final Input input = partialInput();
        final Execution reference = execute(
            original,
            new T038OwnedClassLoader(original, parent, null, false),
            input,
            false,
            T033FixtureEvents.FaultPoint.NONE
        );
        final Execution candidate = execute(
            patched,
            new T038OwnedClassLoader(patched, parent, helperBytes, false),
            input,
            false,
            T033FixtureEvents.FaultPoint.NONE
        );
        compare(reference, candidate, "parent-owned partial execution");
        check(reference.failure() == null && candidate.failure() == null,
            "parent-owned partial execution failed");
        check(reference.events().clearCount() == 1 && candidate.events().clearCount() == 1,
            "parent-owned partial clear count");
        check(reference.events().sourceReadCount() == 15
                && candidate.events().sourceReadCount() == 15,
            "parent-owned partial source read count");
        check(candidate.events().targetWriteCount() < reference.events().targetWriteCount(),
            "parent-owned candidate did not trim empty blocks");
        check(candidate.destination()[26] == 0xbfffffff
                && candidate.destination()[27] == 0x2fffffff,
            "parent-owned partial output witness changed");
        check(Arrays.equals(candidate.source(), input.source()),
            "parent-owned candidate changed source");
        System.out.println("t038OwnedExecution=PASS full-backing/source-read-order/clear/trim");
    }

    private static void testCoexistingLoaderIsolation(
        final byte[] original,
        final byte[] patched,
        final byte[] helperBytes,
        final ClassLoader parent
    ) throws Exception {
        final T038OwnedClassLoader loaderA = new T038OwnedClassLoader(
            original, parent, helperBytes, false
        );
        final T038OwnedClassLoader loaderB = new T038OwnedClassLoader(
            original, parent, helperBytes, false
        );
        final Class<?> helperA = loaderA.visibleHelper();
        final Class<?> helperB = loaderB.visibleHelper();
        check(helperA != helperB, "coexisting loaders shared helper identity");
        check(helperA.getClassLoader() == loaderA && helperB.getClassLoader() == loaderB,
            "child helper loader identity was not isolated");
        final T038OfflineAdapter.Decision decisionA = T038OfflineAdapter.patch(
            original, loaderA, T038HelperBinding.expected(helperA, loaderA)
        );
        final T038OfflineAdapter.Decision decisionB = T038OfflineAdapter.patch(
            original, loaderB, T038HelperBinding.expected(helperB, loaderB)
        );
        check(decisionA.accepted() && decisionB.accepted(),
            "isolated helper preflight rejected a correct binding");
        check(Arrays.equals(decisionA.bytes(), patched)
                && Arrays.equals(decisionB.bytes(), patched),
            "isolated helper patch bytes differ from parent patch");

        final Input input = partialInput();
        final Execution reference = execute(
            original,
            new T038OwnedClassLoader(original, parent, null, false),
            input,
            false,
            T033FixtureEvents.FaultPoint.NONE
        );
        final Execution candidateA = execute(
            decisionA.bytes(), loaderA, input, false, T033FixtureEvents.FaultPoint.NONE
        );
        final Execution candidateB = execute(
            decisionB.bytes(), loaderB, input, false, T033FixtureEvents.FaultPoint.NONE
        );
        compare(reference, candidateA, "isolated loader A");
        compare(reference, candidateB, "isolated loader B");
        check(loaderA.fixtureDefineCount() == 1 && loaderB.fixtureDefineCount() == 1,
            "isolated fixture definition count mismatch");
        System.out.println("t038LoaderIsolation=PASS parent-and-coexisting-child-identities");
    }

    private static void testGuardRejectionExecution(
        final byte[] original,
        final byte[] patched,
        final byte[] helperBytes,
        final ClassLoader parent
    ) throws Exception {
        final Input rejected = new Input(
            1, 1, new int[] {0xff010203}, 1, 1, new int[0], 1, 1, 1, 0
        );
        final long bounds = T038ArrayHelper.bounds(
            rejected.kx(), rejected.ky(), rejected.source(), rejected.sourceWidth(),
            rejected.sourceHeight(), rejected.destination(), rejected.stride(),
            rejected.fullWidth(), rejected.fullHeight(), rejected.padding(), true
        );
        check(T038ArrayHelper.unpackWidth(bounds) == rejected.fullWidth()
                && T038ArrayHelper.unpackHeight(bounds) == rejected.fullHeight(),
            "rejected execution did not retain original bounds");
        final Execution reference = execute(
            original,
            new T038OwnedClassLoader(original, parent, null, false),
            rejected,
            false,
            T033FixtureEvents.FaultPoint.NONE
        );
        final Execution candidate = execute(
            patched,
            new T038OwnedClassLoader(patched, parent, helperBytes, false),
            rejected,
            false,
            T033FixtureEvents.FaultPoint.NONE
        );
        compare(reference, candidate, "patched full-Guard rejection");
        check(reference.failure() instanceof ArrayIndexOutOfBoundsException
                && candidate.failure() instanceof ArrayIndexOutOfBoundsException,
            "Guard rejection changed original finite failure type");
        check(reference.events().clearCount() == 1 && candidate.events().clearCount() == 1,
            "Guard rejection did not clear exactly once");
        check(reference.events().sourceReadCount() == 1
                && candidate.events().sourceReadCount() == 1,
            "Guard rejection altered source-read prefix");
        System.out.println("t038GuardRejectExecution=PASS original-bounds/clear/exception/no-rerun");
    }

    private static void testNegativePreflightControls(
        final byte[] original,
        final byte[] patched,
        final byte[] helperBytes,
        final ClassLoader parent
    ) {
        final T038OwnedClassLoader missing = new T038OwnedClassLoader(
            original, parent, null, true
        );
        final Class<?> parentHelper = T038ArrayHelper.class;
        expectRejected(
            "helper-missing", original, missing,
            T038HelperBinding.expected(parentHelper, parent)
        );

        final T038OwnedClassLoader identityLoader = new T038OwnedClassLoader(
            original, parent, null, false
        );
        expectRejected(
            "helper-identity", original, identityLoader,
            new T038HelperBinding(T038OfflineHarness.class, parent,
                T038ArrayHelper.INTERNAL_NAME, T038ArrayHelper.BOUNDS_DESCRIPTOR)
        );

        final T038OwnedClassLoader loaderA = new T038OwnedClassLoader(
            original, parent, helperBytes, false
        );
        final T038OwnedClassLoader loaderB = new T038OwnedClassLoader(
            original, parent, helperBytes, false
        );
        final Class<?> helperA = loaderA.visibleHelper();
        final Class<?> helperB = loaderB.visibleHelper();
        expectRejected(
            "helper-loader", original, loaderA,
            T038HelperBinding.expected(helperB, loaderB)
        );

        final T038OwnedClassLoader ownerLoader = new T038OwnedClassLoader(
            original, parent, null, false
        );
        expectRejected(
            "helper-owner", original, ownerLoader,
            new T038HelperBinding(parentHelper, parent, "wrong/Owner",
                T038ArrayHelper.BOUNDS_DESCRIPTOR)
        );
        final T038OwnedClassLoader descriptorLoader = new T038OwnedClassLoader(
            original, parent, null, false
        );
        expectRejected(
            "helper-descriptor", original, descriptorLoader,
            new T038HelperBinding(parentHelper, parent, T038ArrayHelper.INTERNAL_NAME,
                "(I)V")
        );

        for (final T035OwnedFixtureGenerator.Variant variant : new T035OwnedFixtureGenerator.Variant[] {
            T035OwnedFixtureGenerator.Variant.WRONG_DESCRIPTOR,
            T035OwnedFixtureGenerator.Variant.WRONG_FLAGS,
            T035OwnedFixtureGenerator.Variant.WRONG_BACKEDGE,
            T035OwnedFixtureGenerator.Variant.WRONG_PREDECESSOR,
            T035OwnedFixtureGenerator.Variant.WRONG_FRAME,
            T035OwnedFixtureGenerator.Variant.TYPE_MERGE
        }) {
            final T038OwnedClassLoader shapeLoader = new T038OwnedClassLoader(
                original, parent, null, false
            );
            expectRejected(
                "fixture-shape-" + variant,
                T035OwnedFixtureGenerator.generate(variant),
                shapeLoader,
                T038HelperBinding.expected(parentHelper, parent)
            );
        }

        final T038OwnedClassLoader repeatedLoader = new T038OwnedClassLoader(
            patched, parent, null, false
        );
        expectRejected(
            "repeated-patch", patched, repeatedLoader,
            T038HelperBinding.expected(parentHelper, parent)
        );
        System.out.println("t038PreflightNegatives=PASS missing/identity/loader/owner/descriptor/shape/repeat");
    }

    private static void expectRejected(
        final String label,
        final byte[] input,
        final T038OwnedClassLoader loader,
        final T038HelperBinding binding
    ) {
        T035OwnedTransformer.resetParseCount();
        final int definitionsBefore = loader.fixtureDefineCount();
        final T038OfflineAdapter.Decision decision = T038OfflineAdapter.patch(
            input, loader, binding
        );
        check(!decision.accepted(), label + " unexpectedly accepted: " + decision.reason());
        check(decision.bytes() == input, label + " did not retain original byte identity");
        check(loader.fixtureDefineCount() == definitionsBefore,
            label + " defined/executed fixture before rejection");
        check(T035OwnedTransformer.parseCount() == 0,
            label + " reached ASM parse before rejection");
    }

    private static void checkBoundaryShape(final byte[] original, final byte[] patched) {
        final T035Shape.Shape originalShape = T035Shape.inspect(original);
        final T035Shape.Shape patchedShape = T035Shape.inspect(patched);
        final String helperCall = "M 184 " + T038ArrayHelper.INTERNAL_NAME + ".bounds"
            + T038ArrayHelper.BOUNDS_DESCRIPTOR + " false";
        check(patchedShape.countContains(helperCall) == 1,
            "T038 helper call shape mismatch");
        check(patchedShape.countContains("M 184 java/util/Arrays.fill([IIII)V false") == 1,
            "T038 changed full clear");
        check(patchedShape.countContains(
            "M 184 " + T035OwnedFixtureGenerator.EVENTS_OWNER + ".afterClear()V false"
        ) == 1, "T038 changed after-clear boundary");
        check(patchedShape.countContains(
            "M 184 " + T035OwnedFixtureGenerator.EVENTS_OWNER + ".beforeSourceRead(I)V false"
        ) == 1, "T038 changed source-read predecessor");
        check(patchedShape.countContains(
            "M 184 " + T035OwnedFixtureGenerator.EVENTS_OWNER + ".afterTargetWrite(I)V false"
        ) == 1, "T038 changed target-write successor");
        check(originalShape.countContains("V 21 8") > 0
                && originalShape.countContains("V 21 9") > 0,
            "owned original lost full dimension parameters");
        check(patchedShape.countContains("V 21 8") > 0
                && patchedShape.countContains("V 21 9") > 0,
            "patched fixture lost full dimension parameters");
        check(patchedShape.countContains("V 21 35") == 1
                && patchedShape.countContains("V 21 36") == 1,
            "T038 did not replace exactly two boundary reads");
        check(patchedShape.handlerCount() == 0, "T038 introduced a handler");
    }

    private static Execution execute(
        final byte[] classBytes,
        final T038OwnedClassLoader loader,
        final Input input,
        final boolean assertionsEnabled,
        final T033FixtureEvents.FaultPoint fault
    ) throws Exception {
        final int[] source = input.source().clone();
        final int[] destination = input.destination().clone();
        T033FixtureEvents.reset(fault);
        Throwable failure = null;
        final Class<?> fixtureClass = loader.defineFixture();
        final Object fixture = fixtureClass.getConstructor().newInstance();
        final Method assertionSetter = fixtureClass.getMethod(
            "setAssertionsEnabled", boolean.class
        );
        final Method render = fixtureClass.getMethod(
            "render", int.class, int.class, int[].class, int.class, int.class,
            int[].class, int.class, int.class, int.class, int.class
        );
        try {
            assertionSetter.invoke(null, assertionsEnabled);
            render.invoke(
                fixture,
                input.kx(), input.ky(), source, input.sourceWidth(), input.sourceHeight(),
                destination, input.stride(), input.fullWidth(), input.fullHeight(), input.padding()
            );
        } catch (final InvocationTargetException exception) {
            failure = exception.getCause();
        }
        return new Execution(source, destination, T033FixtureEvents.snapshot(), failure);
    }

    private static void compare(
        final Execution reference,
        final Execution candidate,
        final String label
    ) {
        check(sameFailure(reference.failure(), candidate.failure()),
            label + " exception type differs");
        check(Arrays.equals(reference.source(), candidate.source()),
            label + " source differs");
        check(Arrays.equals(reference.destination(), candidate.destination()),
            label + " whole backing differs");
        check(reference.events().clearCount() == candidate.events().clearCount(),
            label + " clear count differs");
        check(reference.events().sourceReadCount() == candidate.events().sourceReadCount(),
            label + " source read count differs");
        check(Arrays.equals(reference.events().sourceReads(), candidate.events().sourceReads()),
            label + " source read order differs");
        check(candidate.events().targetWriteCount() <= reference.events().targetWriteCount(),
            label + " candidate wrote more blocks");
    }

    private static boolean sameFailure(final Throwable first, final Throwable second) {
        return first == null ? second == null : second != null
            && first.getClass() == second.getClass();
    }

    private static byte[] readHelperBytes() throws IOException {
        final String resource = "/" + T038ArrayHelper.INTERNAL_NAME + ".class";
        try (InputStream stream = T038ArrayHelper.class.getResourceAsStream(resource)) {
            check(stream != null, "compiled T038 helper resource is missing");
            return stream.readAllBytes();
        }
    }

    private static Input partialInput() {
        final int[] source = new int[5 * 3];
        Arrays.fill(source, 0xffffffff);
        final int[] destination = new int[12 * 8 + 3];
        Arrays.fill(destination, 0x7f7f7f7f);
        return new Input(4, 4, source, 5, 3, destination, 12, 8, 4, 2);
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

    private record Execution(
        int[] source,
        int[] destination,
        T033FixtureEvents.Snapshot events,
        Throwable failure
    ) {
    }
}
