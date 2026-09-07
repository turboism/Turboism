package dev.turboism.ui.overlay;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundingBoxOverlayButtonUpdateTransformerTest {

    private static final String OWNER = "fixture/BoundingBoxDrawEntity";
    private static final String UPDATE_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String HELPER_NAME = "update$setupButton";
    private static final String HELPER_DESCRIPTOR =
        "(Lfixture/Action;Lfixture/Box;Lfixture/Points;Lfixture/Scene;"
            + "Lfixture/Button;Lfixture/Offset;)V";
    private static final String OFFSET_OWNER = "fixture/Offset";

    @Test
    void instrumentsOnlyTheExactOwnerLoaderAndSelector() {
        final FixtureLoader loader = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(loader);

        assertNull(transformer.transform(null, new FixtureLoader(), OWNER, null, null, fixtureEntity(loader, 3)));
        assertNull(transformer.transform(null, loader, "fixture/Other", null, null, fixtureEntity(loader, 3)));
        assertNull(transformer.transform(null, loader, null, null, null, fixtureEntity(loader, 3)));
        assertNull(transformer.transform(null, loader, OWNER, null, null, fixtureEntity(loader, 3, true)));
        assertNotNull(transformer.transform(null, loader, OWNER, null, null, fixtureEntity(loader, 3)));
    }

    @Test
    void rejectsWrongUpdateOrHelperShapeWithoutATransformedCandidate() {
        final FixtureLoader loader = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(loader);

        // Wrong update descriptor: no update method matches.
        assertNull(transformer.transform(
            null,
            loader,
            OWNER,
            null,
            null,
            fixtureEntity(loader, 3, "(Ljava/lang/String;Ljava/lang/String;)V", HELPER_DESCRIPTOR)
        ));
        // Missing helper.
        assertNull(transformer.transform(
            null,
            loader,
            OWNER,
            null,
            null,
            fixtureEntity(loader, 3, UPDATE_DESCRIPTOR, null)
        ));
        // Wrong helper flags (not private static final).
        assertNull(transformer.transform(
            null,
            loader,
            OWNER,
            null,
            null,
            fixtureEntity(
                loader,
                3,
                UPDATE_DESCRIPTOR,
                HELPER_DESCRIPTOR,
                Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            )
        ));
        // Wrong helper call count: two and four calls.
        assertNull(transformer.transform(null, loader, OWNER, null, null, fixtureEntity(loader, 2)));
        assertNull(transformer.transform(null, loader, OWNER, null, null, fixtureEntity(loader, 4)));
    }

    @Test
    void augmentsTheThirdCallSiteWithCachedCustomButtonsAndPreservesNativeArguments() throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final byte[] original = fixtureEntity(source, 3);
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] transformed = transformer.transform(null, source, OWNER, null, null, original);
        assertNotNull(transformed);

        final FixtureLoader runtime = new FixtureLoader();
        defineDependencies(runtime);
        final Class<?> entity = runtime.define(OWNER.replace('/', '.'), transformed);
        final Object instance = entity.getConstructor().newInstance();
        final List<?> calls = recorder(runtime, "CALLS");
        final Object action = field(instance, "action");
        final Object box = field(instance, "box");
        final Object points = field(instance, "points");
        final Object scene = field(instance, "scene");
        final Object buttonA = newInstance(runtime, "fixture.Button");
        final Object buttonB = newInstance(runtime, "fixture.Button");

        try (Registration ignored = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[] {buttonA, buttonB}
        )) {
            final Method update = entity.getMethod("update", Object.class, Object.class);
            update.invoke(instance, new Object(), new Object());
        }

        // Exactly five setup calls: the three native calls plus two custom calls.
        assertEquals(5, calls.size());
        for (int index = 0; index < 3; index++) {
            final Object[] call = (Object[]) calls.get(index);
            assertSame(action, call[0], "native action pack identity");
            assertSame(box, call[1], "native bounding box identity");
            assertSame(points, call[2], "native transformed points identity");
            assertSame(scene, call[3], "native scene graph identity");
        }
        final Object[] custom0 = (Object[]) calls.get(3);
        final Object[] custom1 = (Object[]) calls.get(4);
        assertSame(action, custom0[0]);
        assertSame(box, custom0[1]);
        assertSame(points, custom0[2]);
        assertSame(scene, custom0[3]);
        assertSame(buttonA, custom0[4], "custom button 1 identity");
        assertSame(action, custom1[0]);
        assertSame(box, custom1[1]);
        assertSame(points, custom1[2]);
        assertSame(scene, custom1[3]);
        assertSame(buttonB, custom1[4], "custom button 2 identity");

        // Captured native step/anchor produce the next two slots.
        final Object step = ((Object[]) calls.get(1))[5];
        final Object anchor = ((Object[]) calls.get(2))[5];
        final List<?> times = recorder(runtime, "TIMES");
        final List<?> plus = recorder(runtime, "PLUS");
        assertEquals(2, times.size());
        final Object[] times0 = (Object[]) times.get(0);
        final Object[] times1 = (Object[]) times.get(1);
        assertSame(step, times0[0]);
        assertEquals(1.0f, ((Number) times0[1]).floatValue());
        assertSame(step, times1[0]);
        assertEquals(2.0f, ((Number) times1[1]).floatValue());
        assertEquals(2, plus.size());
        final Object[] plus0 = (Object[]) plus.get(0);
        final Object[] plus1 = (Object[]) plus.get(1);
        assertSame(anchor, plus0[0]);
        assertSame(times0[2], plus0[1]);
        assertSame(anchor, plus1[0]);
        assertSame(times1[2], plus1[1]);
        assertSame(plus0[2], custom0[5], "custom slot 4 offset");
        assertSame(plus1[2], custom1[5], "custom slot 5 offset");
    }

    @Test
    void earlyReturnDoesNotReceivePostThirdCallAugmentation() throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] transformed = transformer.transform(
            null,
            source,
            OWNER,
            null,
            null,
            fixtureEntityWithEarlyReturn(source, 3)
        );
        assertNotNull(transformed);

        final FixtureLoader runtime = new FixtureLoader();
        defineDependencies(runtime);
        final Class<?> entity = runtime.define(OWNER.replace('/', '.'), transformed);
        final Object instance = entity.getConstructor().newInstance();
        final List<?> calls = recorder(runtime, "CALLS");
        final Object button = newInstance(runtime, "fixture.Button");
        entity.getField("skip").setBoolean(instance, true);

        try (Registration ignored = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[] {button}
        )) {
            executeUpdate(entity, instance);
            assertEquals(0, calls.size(), "early/native-hidden return must not run the callback");
            entity.getField("skip").setBoolean(instance, false);
            executeUpdate(entity, instance);
        }

        assertEquals(4, calls.size(), "the normal path keeps three native plus one custom call");
    }
    @Test
    void malformedCallbackArraysExecuteNoCustomSetupCalls() throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] transformed = transformer.transform(
            null,
            source,
            OWNER,
            null,
            null,
            fixtureEntity(source, 3)
        );
        assertNotNull(transformed);

        final FixtureLoader runtime = new FixtureLoader();
        defineDependencies(runtime);
        final Class<?> entity = runtime.define(OWNER.replace('/', '.'), transformed);
        final Object instance = entity.getConstructor().newInstance();
        final List<?> calls = recorder(runtime, "CALLS");
        final Object button = newInstance(runtime, "fixture.Button");
        final java.util.concurrent.atomic.AtomicInteger failures =
            new java.util.concurrent.atomic.AtomicInteger();
        final java.util.function.Consumer<Object> failure = ignored -> failures.incrementAndGet();

        final BiFunction<Object, Object, Object> nullElement =
            (overlay, sceneGraph) -> new Object[] {button, null, button};
        System.getProperties().put(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY, nullElement);
        System.getProperties().put(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY, failure);
        try {
            entity.getMethod("update", Object.class, Object.class)
                .invoke(instance, new Object(), new Object());
        } finally {
            System.getProperties().remove(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY, nullElement);
            System.getProperties().remove(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY, failure);
        }
        assertEquals(3, calls.size(), "null element rejects the whole callback array");

        calls.clear();
        final BiFunction<Object, Object, Object> wrongType =
            (overlay, sceneGraph) -> new Object[] {button, new Object(), button};
        System.getProperties().put(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY, wrongType);
        System.getProperties().put(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY, failure);
        try {
            entity.getMethod("update", Object.class, Object.class)
                .invoke(instance, new Object(), new Object());
        } finally {
            System.getProperties().remove(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY, wrongType);
            System.getProperties().remove(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY, failure);
        }
        assertEquals(3, calls.size(), "wrong type rejects the whole callback array");
        assertEquals(2, failures.get(), "each malformed array emits one bounded diagnostic");
    }

    @Test
    void zeroContributionsPreserveExactlyTheThreeNativeCalls() throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] transformed = transformer.transform(
            null,
            source,
            OWNER,
            null,
            null,
            fixtureEntity(source, 3)
        );
        assertNotNull(transformed);

        final FixtureLoader runtime = new FixtureLoader();
        defineDependencies(runtime);
        final Class<?> entity = runtime.define(OWNER.replace('/', '.'), transformed);
        final Object instance = entity.getConstructor().newInstance();
        final List<?> calls = recorder(runtime, "CALLS");

        try (Registration ignored = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[0]
        )) {
            entity.getMethod("update", Object.class, Object.class)
                .invoke(instance, new Object(), new Object());
        }

        assertEquals(3, calls.size());
    }

    @Test
    void emptyCallbackArrayIsANormalDiagnosticFreeNoOp() throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] transformed = transformer.transform(
            null, source, OWNER, null, null, fixtureEntity(source, 3)
        );
        assertNotNull(transformed);

        final FixtureLoader runtime = new FixtureLoader();
        defineDependencies(runtime);
        final Class<?> entity = runtime.define(OWNER.replace('/', '.'), transformed);
        final List<?> calls = recorder(runtime, "CALLS");
        final java.util.concurrent.atomic.AtomicInteger failures =
            new java.util.concurrent.atomic.AtomicInteger();
        final BiFunction<Object, Object, Object> setup = (overlay, sceneGraph) -> new Object[0];
        final java.util.function.Consumer<Object> failure = ignored -> failures.incrementAndGet();
        System.getProperties().put(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY, setup);
        System.getProperties().put(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY, failure);
        try {
            executeUpdate(entity);
        } finally {
            System.getProperties().remove(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY, setup);
            System.getProperties().remove(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY, failure);
        }

        // A valid empty callback result branches directly to normal completion: exactly the
        // three native setup calls run and the instrumented failure consumer is never
        // invoked (no element-zero indexing, no escaping throwable).
        assertEquals(3, calls.size(), "exactly the three native setup calls");
        assertEquals(0, failures.get(), "an empty callback array is a normal no-op");
    }

    @Test
    void failingSetupHandlerIsReportedThroughTheInstrumentedFailureConsumer() throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] transformed = transformer.transform(
            null, source, OWNER, null, null, fixtureEntity(source, 3)
        );
        assertNotNull(transformed);

        final FixtureLoader runtime = new FixtureLoader();
        defineDependencies(runtime);
        final Class<?> entity = runtime.define(OWNER.replace('/', '.'), transformed);
        final List<?> calls = recorder(runtime, "CALLS");
        final java.util.concurrent.atomic.AtomicInteger failures =
            new java.util.concurrent.atomic.AtomicInteger();
        final BiFunction<Object, Object, Object> setup = (overlay, sceneGraph) -> {
            throw new IllegalStateException("injected rebuild failure");
        };
        final java.util.function.Consumer<Object> failure = ignored -> failures.incrementAndGet();
        System.getProperties().put(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY, setup);
        System.getProperties().put(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY, failure);
        try {
            executeUpdate(entity);
        } finally {
            System.getProperties().remove(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY, setup);
            System.getProperties().remove(NativeBoundingBoxOverlayButtonBridge.FAILURE_PROPERTY, failure);
        }

        // A failing handler (as a failing rebuild would surface) is observable once through
        // the fail-open diagnostic channel while the three native calls still complete.
        assertEquals(3, calls.size(), "the original three native setup calls are preserved");
        assertEquals(1, failures.get(), "one aggregated failure through the fail-open channel");
    }

    @Test
    void nativeVectorOperationsInTheOriginalUpdateArePreservedOnBothLoadPaths() throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] original = fixtureEntityWithVectorOps(source, 3);
        final byte[] transformed = transformer.transform(
            null, source, OWNER, null, null, original
        );
        assertNotNull(
            transformed,
            "a host-shaped update with native vector ops must still transform"
        );

        // Initial-load path: two custom buttons yield exactly 3 + 2 setup calls, with the
        // candidate deltas times +1 and plus +1 over the analyzed original counts.
        final FixtureLoader initialLoader = new FixtureLoader();
        defineDependencies(initialLoader);
        final Class<?> initialEntity = initialLoader.define(OWNER.replace('/', '.'), transformed);
        final Object initialInstance = initialEntity.getConstructor().newInstance();
        final Object buttonA = newInstance(initialLoader, "fixture.Button");
        final Object buttonB = newInstance(initialLoader, "fixture.Button");
        try (Registration ignored = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[] {buttonA, buttonB}
        )) {
            executeUpdate(initialEntity, initialInstance);
        }
        assertEquals(5, recorder(initialLoader, "CALLS").size(), "3 native + 2 custom setup calls");
        assertEquals(
            3,
            recorder(initialLoader, "TIMES").size(),
            "1 native + 2 custom times calls"
        );
        assertEquals(
            4,
            recorder(initialLoader, "PLUS").size(),
            "2 native + 2 custom plus calls"
        );

        // Retransform-style path: the original bytes were already defined elsewhere and the
        // transformed bytes must still JVM-verify, load and run with the same deltas.
        final FixtureLoader originalLoader = new FixtureLoader();
        defineDependencies(originalLoader);
        assertNotNull(originalLoader.define(OWNER.replace('/', '.'), original));
        final FixtureLoader retransformLoader = new FixtureLoader();
        defineDependencies(retransformLoader);
        final Class<?> retransformed = retransformLoader.define(
            OWNER.replace('/', '.'),
            transformed
        );
        final Object retransformInstance = retransformed.getConstructor().newInstance();
        final Object buttonC = newInstance(retransformLoader, "fixture.Button");
        final Object buttonD = newInstance(retransformLoader, "fixture.Button");
        try (Registration ignored = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[] {buttonC, buttonD}
        )) {
            executeUpdate(retransformed, retransformInstance);
        }
        assertEquals(5, recorder(retransformLoader, "CALLS").size());
        assertEquals(3, recorder(retransformLoader, "TIMES").size());
        assertEquals(4, recorder(retransformLoader, "PLUS").size());
    }

    @Test
    void missingReplacedMalformedAndThrowingCallbacksFailOpen() throws Exception {
        assertEquals(3, runUpdateWithCallback(null).size());
        // Null callback result.
        assertEquals(
            3,
            runUpdateWithCallback((overlay, sceneGraph) -> null).size()
        );
        // Throwing callback.
        assertEquals(
            3,
            runUpdateWithCallback((overlay, sceneGraph) -> {
                throw new IllegalStateException("callback exploded");
            }).size()
        );
        // Malformed result (not an Object array): a raw BiFunction is installed directly.
        final BiFunction<Object, Object, Object> malformed = (overlay, sceneGraph) -> "not-an-array";
        System.getProperties().put(NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY, malformed);
        try {
            assertEquals(3, runUpdateWithCallback(null).size());
        } finally {
            System.getProperties().remove(
                NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY,
                malformed
            );
        }
        // Replaced callback value (not a BiFunction).
        System.getProperties().put(
            NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY,
            "replaced"
        );
        try {
            assertEquals(3, runUpdateWithCallback(null).size());
        } finally {
            System.getProperties().remove(
                NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY,
                "replaced"
            );
        }
    }

    @Test
    void transformedBytesContainNoTurboismReferenceAndVerifyOnBothLoadPaths() throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] original = fixtureEntity(source, 3);
        final byte[] transformed = transformer.transform(null, source, OWNER, null, null, original);
        assertNotNull(transformed);

        assertFalse(
            new String(transformed, StandardCharsets.ISO_8859_1).contains("dev/turboism"),
            "transformed host bytecode must not reference Turboism classes"
        );

        // Initial-load path: the transformed bytes are defined directly in a fresh loader.
        final FixtureLoader initialLoader = new FixtureLoader();
        defineDependencies(initialLoader);
        final Class<?> initialEntity = initialLoader.define(OWNER.replace('/', '.'), transformed);
        executeUpdate(initialEntity);
        assertEquals(3, recorder(initialLoader, "CALLS").size());

        // Retransform-style path: the original bytes were already defined in another loader.
        final FixtureLoader originalLoader = new FixtureLoader();
        defineDependencies(originalLoader);
        final Class<?> alreadyLoaded = originalLoader.define(OWNER.replace('/', '.'), original);
        assertNotNull(alreadyLoaded);
        final FixtureLoader retransformLoader = new FixtureLoader();
        defineDependencies(retransformLoader);
        final Class<?> retransformed = retransformLoader.define(OWNER.replace('/', '.'), transformed);
        executeUpdate(retransformed);
        assertEquals(3, recorder(retransformLoader, "CALLS").size());
    }

    @Test
    void equalSecondAndThirdNativeOffsetsDeriveTheNextTwoSlots() throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] transformed = transformer.transform(
            null,
            source,
            OWNER,
            null,
            null,
            fixtureEntityWithOffsets(source, 3, new float[] {0.0f, 40.0f, 40.0f})
        );
        assertNotNull(transformed);

        final FixtureLoader runtime = new FixtureLoader();
        defineDependencies(runtime);
        final Class<?> entity = runtime.define(OWNER.replace('/', '.'), transformed);
        final Object instance = entity.getConstructor().newInstance();
        final List<?> calls = recorder(runtime, "CALLS");
        final Object buttonA = newInstance(runtime, "fixture.Button");
        final Object buttonB = newInstance(runtime, "fixture.Button");

        try (Registration ignored = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[] {buttonA, buttonB}
        )) {
            executeUpdate(entity, instance);
        }

        assertEquals(5, calls.size());
        // Equal offsets: step = second native offset (40), anchor = third (40), so the
        // derived custom slots are 80 and 120.
        final Object step = ((Object[]) calls.get(1))[5];
        final Object anchor = ((Object[]) calls.get(2))[5];
        final List<?> times = recorder(runtime, "TIMES");
        final List<?> plus = recorder(runtime, "PLUS");
        assertEquals(2, times.size());
        assertEquals(2, plus.size());
        final Object[] plus0 = (Object[]) plus.get(0);
        final Object[] plus1 = (Object[]) plus.get(1);
        assertSame(anchor, plus0[0]);
        assertSame(anchor, plus1[0]);
        assertSame(step, ((Object[]) times.get(0))[0]);
        assertSame(step, ((Object[]) times.get(1))[0]);
        assertSame(plus0[2], ((Object[]) calls.get(3))[5], "first custom offset = anchor + step");
        assertSame(plus1[2], ((Object[]) calls.get(4))[5], "second custom offset = anchor + 2 * step");
    }

    @Test
    void mismatchedSetupHelperOwnerRejectsBeforeAugmentation() {
        final FixtureLoader loader = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = new BoundingBoxOverlayButtonUpdateTransformer(
            loader,
            updateSelector(),
            StaticSelector.staticMethod(
                "fixture/OtherEntity",
                "fixture/OtherEntity",
                HELPER_NAME,
                HELPER_DESCRIPTOR,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            ),
            timesSelector(),
            plusSelector()
        );
        // Same name/descriptor but a different owner: no candidate is emitted.
        assertNull(transformer.transform(null, loader, OWNER, null, null, fixtureEntity(loader, 3)));
    }

    @Test
    void oversizedCallbackOutputFailsOpenToTheOriginalThreeNativeCalls() throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] transformed = transformer.transform(
            null, source, OWNER, null, null, fixtureEntity(source, 3)
        );
        assertNotNull(transformed);

        // Exactly eight buttons run the bounded loop: three native plus eight custom calls.
        assertEquals(11, runUpdateWithArrayLength(transformed, 8));
        // Nine buttons exceed the hard limit and fail open to exactly the three native calls.
        assertEquals(3, runUpdateWithArrayLength(transformed, 9));
    }

    @Test
    void replacedValidCallbackIsHonoredWithinTheBound() throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] transformed = transformer.transform(
            null, source, OWNER, null, null, fixtureEntity(source, 3)
        );
        assertNotNull(transformed);

        // A valid replacement BiFunction installed directly (not through the bridge) drives
        // the augmentation exactly like the bridge installation would.
        final FixtureLoader runtime = new FixtureLoader();
        defineDependencies(runtime);
        final BiFunction<Object, Object, Object> replacement = (overlay, scene) -> {
            try {
                return new Object[] {
                    newInstance(runtime, "fixture.Button"),
                    newInstance(runtime, "fixture.Button")
                };
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        };
        System.getProperties().put(
            NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY,
            replacement
        );
        try {
            final Class<?> entity = runtime.define(OWNER.replace('/', '.'), transformed);
            final List<?> calls = recorder(runtime, "CALLS");
            executeUpdate(entity);
            assertEquals(5, calls.size(), "three native plus two custom setup calls");
        } finally {
            System.getProperties().remove(
                NativeBoundingBoxOverlayButtonBridge.SETUP_PROPERTY,
                replacement
            );
        }
    }

    @Test
    void hostLoaderMergedFramesAreComputedWithTheExactLoaderAndVerifyOnBothLoadPaths()
        throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] original = fixtureEntityWithMerge(source, 3);
        final byte[] transformed = transformer.transform(null, source, OWNER, null, null, original);
        assertNotNull(
            transformed,
            "host-loader-only merge types must resolve through the exact verified host loader"
        );

        // Initial-load-style path: define the transformed bytes in a fresh loader.
        final FixtureLoader initialLoader = new FixtureLoader();
        defineDependencies(initialLoader);
        final Class<?> initialEntity = initialLoader.define(OWNER.replace('/', '.'), transformed);
        executeUpdate(initialEntity);
        assertEquals(3, recorder(initialLoader, "CALLS").size());

        // Retransform-style path: original bytes were already defined elsewhere.
        final FixtureLoader originalLoader = new FixtureLoader();
        defineDependencies(originalLoader);
        assertNotNull(originalLoader.define(OWNER.replace('/', '.'), original));
        final FixtureLoader retransformLoader = new FixtureLoader();
        defineDependencies(retransformLoader);
        final Class<?> retransformed = retransformLoader.define(OWNER.replace('/', '.'), transformed);
        executeUpdate(retransformed);
        assertEquals(3, recorder(retransformLoader, "CALLS").size());
    }

    @Test
    void earlyReturnDisablesPreviouslyVolatileCustomButtonThroughNativeSceneSemantics()
        throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] transformed = transformer.transform(
            null,
            source,
            OWNER,
            null,
            null,
            fixtureEntityWithEarlyReturn(source, 3)
        );
        assertNotNull(transformed);

        final FixtureLoader runtime = new FixtureLoader();
        defineDependencies(runtime);
        final Class<?> entity = runtime.define(OWNER.replace('/', '.'), transformed);
        final Object instance = entity.getConstructor().newInstance();
        final List<?> calls = recorder(runtime, "CALLS");
        final Object customButton = newInstance(runtime, "fixture.Button");
        final Class<?> scene = runtime.loadClass("fixture.Scene");
        final List<?> volatileList = (List<?>) scene.getField("VOLATILE").get(null);
        final List<?> disabled = (List<?>) scene.getField("DISABLED_BY_PRE_RENDER").get(null);

        try (Registration ignored = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> new Object[] {customButton}
        )) {
            // Visible frame: the custom button is submitted to the native volatile set.
            executeUpdate(entity, instance);
            assertEquals(4, calls.size());
            assertTrue(volatileList.contains(customButton), "custom button submitted as volatile");
            scene.getMethod("preRender").invoke(null);

            // Hidden/early-return frame: the augmentation is not reached and the custom
            // button is not resubmitted; native preRender disables it.
            calls.clear();
            entity.getField("skip").setBoolean(instance, true);
            executeUpdate(entity, instance);
            assertEquals(0, calls.size());
            scene.getMethod("preRender").invoke(null);
            assertTrue(
                disabled.contains(customButton),
                "non-resubmitted custom button is disabled by native volatile semantics"
            );
        }
    }

    private static int runUpdateWithArrayLength(
        final byte[] transformed,
        final int buttonCount
    ) throws Exception {
        final FixtureLoader runtime = new FixtureLoader();
        defineDependencies(runtime);
        final Class<?> entity = runtime.define(OWNER.replace('/', '.'), transformed);
        final List<?> calls = recorder(runtime, "CALLS");
        final Object[] buttons = new Object[buttonCount];
        for (int index = 0; index < buttonCount; index++) {
            buttons[index] = newInstance(runtime, "fixture.Button");
        }
        try (Registration ignored = NativeBoundingBoxOverlayButtonBridge.install(
            (overlay, sceneGraph) -> buttons
        )) {
            executeUpdate(entity);
        }
        return calls.size();
    }

    private static List<?> runUpdateWithCallback(
        final NativeBoundingBoxOverlayButtonBridge.SetupHandler handler
    ) throws Exception {
        final FixtureLoader source = new FixtureLoader();
        final BoundingBoxOverlayButtonUpdateTransformer transformer = transformer(source);
        final byte[] transformed = transformer.transform(
            null,
            source,
            OWNER,
            null,
            null,
            fixtureEntity(source, 3)
        );
        assertNotNull(transformed);
        final FixtureLoader runtime = new FixtureLoader();
        defineDependencies(runtime);
        final Class<?> entity = runtime.define(OWNER.replace('/', '.'), transformed);
        final List<?> calls = recorder(runtime, "CALLS");
        try (Registration ignored = handler == null
            ? null
            : NativeBoundingBoxOverlayButtonBridge.install(handler)) {
            executeUpdate(entity);
        }
        return calls;
    }

    private static void executeUpdate(final Class<?> entity) throws Exception {
        final Object instance = entity.getConstructor().newInstance();
        entity.getMethod("update", Object.class, Object.class)
            .invoke(instance, new Object(), new Object());
    }

    private static void executeUpdate(final Class<?> entity, final Object instance) throws Exception {
        entity.getMethod("update", Object.class, Object.class)
            .invoke(instance, new Object(), new Object());
    }

    private static BoundingBoxOverlayButtonUpdateTransformer transformer(
        final ClassLoader loader
    ) {
        return new BoundingBoxOverlayButtonUpdateTransformer(
            loader,
            updateSelector(),
            helperSelector(),
            timesSelector(),
            plusSelector()
        );
    }

    private static StaticSelector updateSelector() {
        return StaticSelector.method(OWNER, OWNER, "update", UPDATE_DESCRIPTOR);
    }

    private static StaticSelector helperSelector() {
        return StaticSelector.staticMethod(
            OWNER,
            OWNER,
            HELPER_NAME,
            HELPER_DESCRIPTOR,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL
        );
    }

    private static StaticSelector timesSelector() {
        return StaticSelector.method(
            OFFSET_OWNER,
            OFFSET_OWNER,
            "times",
            "(F)L" + OFFSET_OWNER + ";",
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL
        );
    }

    private static StaticSelector plusSelector() {
        return StaticSelector.method(
            OFFSET_OWNER,
            OFFSET_OWNER,
            "plus",
            "(L" + OFFSET_OWNER + ";)L" + OFFSET_OWNER + ";",
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL
        );
    }

    private static byte[] fixtureEntity(final FixtureLoader loader, final int callCount) {
        return fixtureEntity(loader, callCount, UPDATE_DESCRIPTOR, HELPER_DESCRIPTOR);
    }

    private static byte[] fixtureEntityWithEarlyReturn(
        final FixtureLoader loader,
        final int callCount
    ) {
        return fixtureEntity(
            loader,
            callCount,
            UPDATE_DESCRIPTOR,
            HELPER_DESCRIPTOR,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            true
        );
    }

    private static byte[] fixtureEntity(
        final FixtureLoader loader,
        final int callCount,
        final boolean noHelper
    ) {
        return fixtureEntity(loader, callCount, UPDATE_DESCRIPTOR, noHelper ? null : HELPER_DESCRIPTOR);
    }

    private static byte[] fixtureEntity(
        final FixtureLoader loader,
        final int callCount,
        final String updateDescriptor,
        final String helperDescriptor
    ) {
        return fixtureEntity(
            loader,
            callCount,
            updateDescriptor,
            helperDescriptor,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
        );
    }

    private static byte[] fixtureEntity(
        final FixtureLoader loader,
        final int callCount,
        final String updateDescriptor,
        final String helperDescriptor,
        final int helperAccess
    ) {
        return fixtureEntity(
            loader,
            callCount,
            updateDescriptor,
            helperDescriptor,
            helperAccess,
            false
        );
    }

    private static byte[] fixtureEntity(
        final FixtureLoader loader,
        final int callCount,
        final String updateDescriptor,
        final String helperDescriptor,
        final int helperAccess,
        final boolean earlyReturn
    ) {
        return fixtureEntity(
            loader,
            callCount,
            updateDescriptor,
            helperDescriptor,
            helperAccess,
            earlyReturn,
            new float[] {0.0f, 40.0f, 80.0f}
        );
    }

    private static byte[] fixtureEntityWithOffsets(
        final FixtureLoader loader,
        final int callCount,
        final float[] offsets
    ) {
        return fixtureEntity(
            loader,
            callCount,
            UPDATE_DESCRIPTOR,
            HELPER_DESCRIPTOR,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            false,
            offsets
        );
    }

    /**
     * Host-shaped fixture whose original {@code update} already performs the exact selected
     * vector operations before the three native helper calls: one {@code times} and two
     * {@code plus} calls, mirroring the exact 5.2.03/5.3.02 host body (the O2 review found
     * {@code GVector2.times x1 / GVector2.plus x2} natively). The candidate must verify by
     * exact deltas ({@code times +1}, {@code plus +1}) rather than global absolute counts.
     */
    private static byte[] fixtureEntityWithVectorOps(final FixtureLoader loader, final int callCount) {
        return fixtureEntity(
            loader,
            callCount,
            UPDATE_DESCRIPTOR,
            HELPER_DESCRIPTOR,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            false,
            new float[] {0.0f, 40.0f, 80.0f},
            true
        );
    }

    /**
     * Fixture whose third native button argument is a control-flow merge of two
     * host-loader-only types (Button and SubButton extends Button): frame computation must
     * resolve the common super through the exact host loader, not the writer's own.
     */
    private static byte[] fixtureEntityWithMerge(final FixtureLoader loader, final int callCount) {
        defineDependencies(loader);
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String left, final String right) {
                try {
                    final Class<?> leftType = Class.forName(
                        left.replace('/', '.'), false, loader
                    );
                    final Class<?> rightType = Class.forName(
                        right.replace('/', '.'), false, loader
                    );
                    if (leftType.isAssignableFrom(rightType)) {
                        return left;
                    }
                    if (rightType.isAssignableFrom(leftType)) {
                        return right;
                    }
                    return "java/lang/Object";
                } catch (Throwable ignored) {
                    return "java/lang/Object";
                }
            }
        };
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        for (String field : new String[] {
            "action", "box", "points", "scene", "hideButton", "adjustButton", "warpButton"
        }) {
            writer.visitField(Opcodes.ACC_PUBLIC, field, fieldType(field), null, null).visitEnd();
        }
        writer.visitField(Opcodes.ACC_PUBLIC, "mergePick", "Z", null, null).visitEnd();
        writer.visitField(
            Opcodes.ACC_PUBLIC, "mergeButton", "Lfixture/SubButton;", null, null
        ).visitEnd();
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CALLS", "Ljava/util/List;", null, null
        ).visitEnd();
        entityConstructor(writer, OWNER, new String[] {
            "action", "box", "points", "scene", "hideButton", "adjustButton", "warpButton"
        });
        clinit(writer);

        final MethodVisitor update = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "update", UPDATE_DESCRIPTOR, null, null
        );
        update.visitCode();
        for (int call = 0; call < callCount; call++) {
            pushField(update, "action");
            pushField(update, "box");
            pushField(update, "points");
            pushField(update, "scene");
            if (call == callCount - 1) {
                // Merge: hideButton (Button) or mergeButton (SubButton), both host-loader-only.
                final Label pickSub = new Label();
                final Label after = new Label();
                update.visitVarInsn(Opcodes.ALOAD, 0);
                update.visitFieldInsn(Opcodes.GETFIELD, OWNER, "mergePick", "Z");
                update.visitJumpInsn(Opcodes.IFNE, pickSub);
                pushField(update, "hideButton");
                update.visitJumpInsn(Opcodes.GOTO, after);
                update.visitLabel(pickSub);
                pushField(update, "mergeButton");
                update.visitLabel(after);
            } else {
                pushField(update, buttonsFor(call));
            }
            update.visitTypeInsn(Opcodes.NEW, OFFSET_OWNER);
            update.visitInsn(Opcodes.DUP);
            update.visitInsn(Opcodes.FCONST_0);
            update.visitLdcInsn(80.0f);
            update.visitMethodInsn(
                Opcodes.INVOKESPECIAL, OFFSET_OWNER, "<init>", "(FF)V", false
            );
            update.visitMethodInsn(
                Opcodes.INVOKESTATIC, OWNER, HELPER_NAME, HELPER_DESCRIPTOR, false
            );
        }
        update.visitInsn(Opcodes.RETURN);
        update.visitMaxs(0, 0);
        update.visitEnd();
        helper(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, HELPER_DESCRIPTOR);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String buttonsFor(final int call) {
        return new String[] {"hideButton", "adjustButton", "warpButton"}[call % 3];
    }
    private static byte[] fixtureEntity(
        final FixtureLoader loader,
        final int callCount,
        final String updateDescriptor,
        final String helperDescriptor,
        final int helperAccess,
        final boolean earlyReturn,
        final float[] offsets
    ) {
        return fixtureEntity(
            loader,
            callCount,
            updateDescriptor,
            helperDescriptor,
            helperAccess,
            earlyReturn,
            offsets,
            false
        );
    }

    private static byte[] fixtureEntity(
        final FixtureLoader loader,
        final int callCount,
        final String updateDescriptor,
        final String helperDescriptor,
        final int helperAccess,
        final boolean earlyReturn,
        final float[] offsets,
        final boolean vectorOps
    ) {
        defineDependencies(loader);
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);

        final java.util.List<String> fields = new java.util.ArrayList<>(
            java.util.List.of(
                "action", "box", "points", "scene",
                "hideButton", "adjustButton", "warpButton"
            )
        );
        if (vectorOps) {
            fields.add("offsetA");
            fields.add("offsetB");
            fields.add("offsetC");
        }
        for (String field : fields) {
            writer.visitField(
                Opcodes.ACC_PUBLIC,
                field,
                fieldType(field),
                null,
                null
            ).visitEnd();
        }
        if (earlyReturn) {
            writer.visitField(
                Opcodes.ACC_PUBLIC,
                "skip",
                "Z",
                null,
                null
            ).visitEnd();
        }
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "CALLS",
            "Ljava/util/List;",
            null,
            null
        ).visitEnd();

        entityConstructor(writer, OWNER, fields.toArray(new String[0]));
        clinit(writer);

        final MethodVisitor update = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "update",
            updateDescriptor,
            null,
            null
        );
        update.visitCode();
        if (earlyReturn) {
            final Label nativeCalls = new Label();
            update.visitVarInsn(Opcodes.ALOAD, 0);
            update.visitFieldInsn(Opcodes.GETFIELD, OWNER, "skip", "Z");
            update.visitJumpInsn(Opcodes.IFEQ, nativeCalls);
            update.visitInsn(Opcodes.RETURN);
            update.visitLabel(nativeCalls);
        }
        if (vectorOps) {
            // Exact selected vector operations before the three native helper calls:
            // times x1 then plus x2, mirroring the exact host update() body.
            update.visitVarInsn(Opcodes.ALOAD, 0);
            update.visitFieldInsn(Opcodes.GETFIELD, OWNER, "offsetA", "Lfixture/Offset;");
            update.visitLdcInsn(2.0f);
            update.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                OFFSET_OWNER,
                "times",
                "(F)L" + OFFSET_OWNER + ";",
                false
            );
            update.visitVarInsn(Opcodes.ASTORE, 3);
            update.visitVarInsn(Opcodes.ALOAD, 3);
            update.visitVarInsn(Opcodes.ALOAD, 0);
            update.visitFieldInsn(Opcodes.GETFIELD, OWNER, "offsetB", "Lfixture/Offset;");
            update.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                OFFSET_OWNER,
                "plus",
                "(L" + OFFSET_OWNER + ";)L" + OFFSET_OWNER + ";",
                false
            );
            update.visitVarInsn(Opcodes.ASTORE, 4);
            update.visitVarInsn(Opcodes.ALOAD, 4);
            update.visitVarInsn(Opcodes.ALOAD, 0);
            update.visitFieldInsn(Opcodes.GETFIELD, OWNER, "offsetC", "Lfixture/Offset;");
            update.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                OFFSET_OWNER,
                "plus",
                "(L" + OFFSET_OWNER + ";)L" + OFFSET_OWNER + ";",
                false
            );
            update.visitVarInsn(Opcodes.ASTORE, 5);
        }
        final String[] buttons = {"hideButton", "adjustButton", "warpButton"};
        for (int call = 0; call < callCount; call++) {
            pushField(update, "action");
            pushField(update, "box");
            pushField(update, "points");
            pushField(update, "scene");
            pushField(update, buttons[call % buttons.length]);
            update.visitTypeInsn(Opcodes.NEW, OFFSET_OWNER);
            update.visitInsn(Opcodes.DUP);
            update.visitInsn(Opcodes.FCONST_0);
            update.visitLdcInsn(offsets[call % offsets.length]);
            update.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                OFFSET_OWNER,
                "<init>",
                "(FF)V",
                false
            );
            update.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                OWNER,
                HELPER_NAME,
                HELPER_DESCRIPTOR,
                false
            );
        }
        update.visitInsn(Opcodes.RETURN);
        update.visitMaxs(0, 0);
        update.visitEnd();

        if (helperDescriptor != null) {
            helper(writer, helperAccess, helperDescriptor);
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void pushField(final MethodVisitor visitor, final String name) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitFieldInsn(
            Opcodes.GETFIELD,
            OWNER,
            name,
            fieldType(name)
        );
    }

    private static String fieldType(final String name) {
        return switch (name) {
            case "action" -> "Lfixture/Action;";
            case "box" -> "Lfixture/Box;";
            case "points" -> "Lfixture/Points;";
            case "scene" -> "Lfixture/Scene;";
            case "hideButton", "adjustButton", "warpButton" -> "Lfixture/Button;";
            case "offsetA", "offsetB", "offsetC" -> "Lfixture/Offset;";
            case "mergeButton" -> "Lfixture/SubButton;";
            default -> throw new IllegalArgumentException("unknown fixture field " + name);
        };
    }

    private static void helper(
        final ClassWriter writer,
        final int access,
        final String descriptor
    ) {
        final MethodVisitor helper = writer.visitMethod(
            access,
            HELPER_NAME,
            descriptor,
            null,
            null
        );
        helper.visitCode();
        helper.visitIntInsn(Opcodes.BIPUSH, 6);
        helper.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        int[] locals = {0, 1, 2, 3, 4, 5};
        for (int index = 0; index < locals.length; index++) {
            helper.visitInsn(Opcodes.DUP);
            helper.visitLdcInsn(index);
            helper.visitVarInsn(Opcodes.ALOAD, locals[index]);
            helper.visitInsn(Opcodes.AASTORE);
        }
        helper.visitFieldInsn(Opcodes.GETSTATIC, OWNER, "CALLS", "Ljava/util/List;");
        helper.visitInsn(Opcodes.SWAP);
        helper.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            "java/util/List",
            "add",
            "(Ljava/lang/Object;)Z",
            true
        );
        helper.visitInsn(Opcodes.POP);
        // Native setup submits the button to the scene's volatile set (frame lifecycle).
        helper.visitVarInsn(Opcodes.ALOAD, 3);
        helper.visitVarInsn(Opcodes.ALOAD, 4);
        helper.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "fixture/Scene",
            "setVolatile",
            "(Lfixture/Button;)V",
            false
        );
        helper.visitInsn(Opcodes.RETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
    }

    private static void clinit(final ClassWriter writer) {
        final MethodVisitor clinit = writer.visitMethod(
            Opcodes.ACC_STATIC,
            "<clinit>",
            "()V",
            null,
            null
        );
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/util/ArrayList",
            "<init>",
            "()V",
            false
        );
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, OWNER, "CALLS", "Ljava/util/List;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
    }

    private static void constructor(final ClassWriter writer, final String owner) {
        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/Object",
            "<init>",
            "()V",
            false
        );
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    /** Entity constructor: initializes the given fixture object fields. */
    private static void entityConstructor(
        final ClassWriter writer,
        final String owner,
        final String[] fields
    ) {
        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/Object",
            "<init>",
            "()V",
            false
        );
        for (String field : fields) {
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitTypeInsn(Opcodes.NEW, fieldClass(field));
            constructor.visitInsn(Opcodes.DUP);
            if (field.startsWith("offset")) {
                // Offset only exposes its (FF) constructor.
                constructor.visitInsn(Opcodes.FCONST_0);
                constructor.visitInsn(Opcodes.FCONST_0);
                constructor.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    fieldClass(field),
                    "<init>",
                    "(FF)V",
                    false
                );
            } else {
                constructor.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    fieldClass(field),
                    "<init>",
                    "()V",
                    false
                );
            }
            constructor.visitFieldInsn(
                Opcodes.PUTFIELD,
                owner,
                field,
                fieldType(field)
            );
        }
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private static String fieldClass(final String name) {
        return switch (name) {
            case "action" -> "fixture/Action";
            case "box" -> "fixture/Box";
            case "points" -> "fixture/Points";
            case "scene" -> "fixture/Scene";
            case "offsetA", "offsetB", "offsetC" -> "fixture/Offset";
            default -> "fixture/Button";
        };
    }

    /** Defines the plain fixture classes and the recording {@code fixture/Offset} once per loader. */
    private static void defineDependencies(final FixtureLoader loader) {
        for (String name : new String[] {
            "fixture/Action", "fixture/Box", "fixture/Points", "fixture/Button"
        }) {
            if (!isDefined(loader, name)) {
                loader.define(name.replace('/', '.'), plainClass(name));
            }
        }
        if (!isDefined(loader, "fixture/Scene")) {
            loader.define("fixture.Scene", sceneClass());
        }
        if (!isDefined(loader, "fixture/SubButton")) {
            loader.define("fixture.SubButton", subButtonClass());
        }
        if (!isDefined(loader, "fixture/Offset")) {
            loader.define("fixture.Offset", offsetClass());
        }
    }

    /** Fixture scene modeling the native volatile lifecycle: setVolatile + preRender disable. */
    private static byte[] sceneClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Scene", null, "java/lang/Object", null);
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "VOLATILE", "Ljava/util/List;", null, null
        ).visitEnd();
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "PREVIOUS", "Ljava/util/List;", null, null
        ).visitEnd();
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "DISABLED_BY_PRE_RENDER", "Ljava/util/List;", null, null
        ).visitEnd();
        constructor(writer, "fixture/Scene");

        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (String list : new String[] {"VOLATILE", "PREVIOUS", "DISABLED_BY_PRE_RENDER"}) {
            clinit.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false
            );
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, "fixture/Scene", list, "Ljava/util/List;");
        }
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        final MethodVisitor setVolatile = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "setVolatile", "(Lfixture/Button;)V", null, null
        );
        setVolatile.visitCode();
        setVolatile.visitFieldInsn(Opcodes.GETSTATIC, "fixture/Scene", "VOLATILE", "Ljava/util/List;");
        setVolatile.visitVarInsn(Opcodes.ALOAD, 1);
        setVolatile.visitMethodInsn(
            Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true
        );
        setVolatile.visitInsn(Opcodes.POP);
        setVolatile.visitInsn(Opcodes.RETURN);
        setVolatile.visitMaxs(0, 0);
        setVolatile.visitEnd();

        final MethodVisitor preRender = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "preRender", "()V", null, null
        );
        preRender.visitCode();
        final Label loop = new Label();
        final Label next = new Label();
        final Label done = new Label();
        // Disable every entity submitted last frame that was not resubmitted this frame.
        preRender.visitFieldInsn(Opcodes.GETSTATIC, "fixture/Scene", "PREVIOUS", "Ljava/util/List;");
        preRender.visitMethodInsn(
            Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true
        );
        preRender.visitVarInsn(Opcodes.ASTORE, 1);
        preRender.visitLabel(loop);
        preRender.visitVarInsn(Opcodes.ALOAD, 1);
        preRender.visitMethodInsn(
            Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true
        );
        preRender.visitJumpInsn(Opcodes.IFEQ, done);
        preRender.visitVarInsn(Opcodes.ALOAD, 1);
        preRender.visitMethodInsn(
            Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true
        );
        preRender.visitVarInsn(Opcodes.ASTORE, 0);
        preRender.visitFieldInsn(Opcodes.GETSTATIC, "fixture/Scene", "VOLATILE", "Ljava/util/List;");
        preRender.visitVarInsn(Opcodes.ALOAD, 0);
        preRender.visitMethodInsn(
            Opcodes.INVOKEINTERFACE, "java/util/List", "contains", "(Ljava/lang/Object;)Z", true
        );
        preRender.visitJumpInsn(Opcodes.IFNE, next);
        preRender.visitFieldInsn(
            Opcodes.GETSTATIC, "fixture/Scene", "DISABLED_BY_PRE_RENDER", "Ljava/util/List;"
        );
        preRender.visitVarInsn(Opcodes.ALOAD, 0);
        preRender.visitMethodInsn(
            Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true
        );
        preRender.visitInsn(Opcodes.POP);
        preRender.visitLabel(next);
        preRender.visitJumpInsn(Opcodes.GOTO, loop);
        preRender.visitLabel(done);
        // PREVIOUS = this frame's submissions; VOLATILE clears.
        preRender.visitFieldInsn(Opcodes.GETSTATIC, "fixture/Scene", "PREVIOUS", "Ljava/util/List;");
        preRender.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true);
        preRender.visitFieldInsn(Opcodes.GETSTATIC, "fixture/Scene", "PREVIOUS", "Ljava/util/List;");
        preRender.visitFieldInsn(Opcodes.GETSTATIC, "fixture/Scene", "VOLATILE", "Ljava/util/List;");
        preRender.visitMethodInsn(
            Opcodes.INVOKEINTERFACE, "java/util/List", "addAll", "(Ljava/util/Collection;)Z", true
        );
        preRender.visitInsn(Opcodes.POP);
        preRender.visitFieldInsn(Opcodes.GETSTATIC, "fixture/Scene", "VOLATILE", "Ljava/util/List;");
        preRender.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true);
        preRender.visitInsn(Opcodes.RETURN);
        preRender.visitMaxs(0, 0);
        preRender.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] subButtonClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/SubButton", null, "fixture/Button", null);
        constructor(writer, "fixture/SubButton");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static boolean isDefined(final FixtureLoader loader, final String name) {
        try {
            loader.loadClass(name.replace('/', '.'));
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static byte[] plainClass(final String internalName) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        constructor(writer, internalName);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] offsetClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OFFSET_OWNER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "x", "F", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "y", "F", null, null).visitEnd();
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "TIMES",
            "Ljava/util/List;",
            null,
            null
        ).visitEnd();
        writer.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "PLUS",
            "Ljava/util/List;",
            null,
            null
        ).visitEnd();

        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "(FF)V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/Object",
            "<init>",
            "()V",
            false
        );
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.FLOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, OFFSET_OWNER, "x", "F");
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.FLOAD, 2);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, OFFSET_OWNER, "y", "F");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor clinit = writer.visitMethod(
            Opcodes.ACC_STATIC, "<clinit>", "()V", null, null
        );
        clinit.visitCode();
        for (String list : new String[] {"TIMES", "PLUS"}) {
            clinit.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/util/ArrayList",
                "<init>",
                "()V",
                false
            );
            clinit.visitFieldInsn(
                Opcodes.PUTSTATIC,
                OFFSET_OWNER,
                list,
                "Ljava/util/List;"
            );
        }
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        times(writer);
        plus(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void times(final ClassWriter writer) {
        final MethodVisitor times = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            "times",
            "(F)L" + OFFSET_OWNER + ";",
            null,
            null
        );
        times.visitCode();
        // Offset result = new Offset(x * scale, y * scale)
        times.visitTypeInsn(Opcodes.NEW, OFFSET_OWNER);
        times.visitInsn(Opcodes.DUP);
        times.visitVarInsn(Opcodes.ALOAD, 0);
        times.visitFieldInsn(Opcodes.GETFIELD, OFFSET_OWNER, "x", "F");
        times.visitVarInsn(Opcodes.FLOAD, 1);
        times.visitInsn(Opcodes.FMUL);
        times.visitVarInsn(Opcodes.ALOAD, 0);
        times.visitFieldInsn(Opcodes.GETFIELD, OFFSET_OWNER, "y", "F");
        times.visitVarInsn(Opcodes.FLOAD, 1);
        times.visitInsn(Opcodes.FMUL);
        times.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            OFFSET_OWNER,
            "<init>",
            "(FF)V",
            false
        );
        times.visitVarInsn(Opcodes.ASTORE, 2);
        // TIMES.add(new Object[]{this, scale, result})
        times.visitInsn(Opcodes.ICONST_3);
        times.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        times.visitInsn(Opcodes.DUP);
        times.visitInsn(Opcodes.ICONST_0);
        times.visitVarInsn(Opcodes.ALOAD, 0);
        times.visitInsn(Opcodes.AASTORE);
        times.visitInsn(Opcodes.DUP);
        times.visitInsn(Opcodes.ICONST_1);
        times.visitVarInsn(Opcodes.FLOAD, 1);
        times.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Float",
            "valueOf",
            "(F)Ljava/lang/Float;",
            false
        );
        times.visitInsn(Opcodes.AASTORE);
        times.visitInsn(Opcodes.DUP);
        times.visitInsn(Opcodes.ICONST_2);
        times.visitVarInsn(Opcodes.ALOAD, 2);
        times.visitInsn(Opcodes.AASTORE);
        times.visitFieldInsn(Opcodes.GETSTATIC, OFFSET_OWNER, "TIMES", "Ljava/util/List;");
        times.visitInsn(Opcodes.SWAP);
        times.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            "java/util/List",
            "add",
            "(Ljava/lang/Object;)Z",
            true
        );
        times.visitInsn(Opcodes.POP);
        times.visitVarInsn(Opcodes.ALOAD, 2);
        times.visitInsn(Opcodes.ARETURN);
        times.visitMaxs(0, 0);
        times.visitEnd();
    }

    private static void plus(final ClassWriter writer) {
        final MethodVisitor plus = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            "plus",
            "(L" + OFFSET_OWNER + ";)L" + OFFSET_OWNER + ";",
            null,
            null
        );
        plus.visitCode();
        plus.visitTypeInsn(Opcodes.NEW, OFFSET_OWNER);
        plus.visitInsn(Opcodes.DUP);
        plus.visitVarInsn(Opcodes.ALOAD, 0);
        plus.visitFieldInsn(Opcodes.GETFIELD, OFFSET_OWNER, "x", "F");
        plus.visitVarInsn(Opcodes.ALOAD, 1);
        plus.visitFieldInsn(Opcodes.GETFIELD, OFFSET_OWNER, "x", "F");
        plus.visitInsn(Opcodes.FADD);
        plus.visitVarInsn(Opcodes.ALOAD, 0);
        plus.visitFieldInsn(Opcodes.GETFIELD, OFFSET_OWNER, "y", "F");
        plus.visitVarInsn(Opcodes.ALOAD, 1);
        plus.visitFieldInsn(Opcodes.GETFIELD, OFFSET_OWNER, "y", "F");
        plus.visitInsn(Opcodes.FADD);
        plus.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            OFFSET_OWNER,
            "<init>",
            "(FF)V",
            false
        );
        plus.visitVarInsn(Opcodes.ASTORE, 2);
        // PLUS.add(new Object[]{this, other, result})
        plus.visitInsn(Opcodes.ICONST_3);
        plus.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        plus.visitInsn(Opcodes.DUP);
        plus.visitInsn(Opcodes.ICONST_0);
        plus.visitVarInsn(Opcodes.ALOAD, 0);
        plus.visitInsn(Opcodes.AASTORE);
        plus.visitInsn(Opcodes.DUP);
        plus.visitInsn(Opcodes.ICONST_1);
        plus.visitVarInsn(Opcodes.ALOAD, 1);
        plus.visitInsn(Opcodes.AASTORE);
        plus.visitInsn(Opcodes.DUP);
        plus.visitInsn(Opcodes.ICONST_2);
        plus.visitVarInsn(Opcodes.ALOAD, 2);
        plus.visitInsn(Opcodes.AASTORE);
        plus.visitFieldInsn(Opcodes.GETSTATIC, OFFSET_OWNER, "PLUS", "Ljava/util/List;");
        plus.visitInsn(Opcodes.SWAP);
        plus.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            "java/util/List",
            "add",
            "(Ljava/lang/Object;)Z",
            true
        );
        plus.visitInsn(Opcodes.POP);
        plus.visitVarInsn(Opcodes.ALOAD, 2);
        plus.visitInsn(Opcodes.ARETURN);
        plus.visitMaxs(0, 0);
        plus.visitEnd();
    }

    private static Object field(final Object instance, final String name) throws Exception {
        return instance.getClass().getField(name).get(instance);
    }

    private static List<?> recorder(final FixtureLoader loader, final String name) throws Exception {
        final Class<?> owner = loader.loadClass(OWNER.replace('/', '.'));
        final Class<?> type = "TIMES".equals(name) || "PLUS".equals(name)
            ? loader.loadClass("fixture.Offset")
            : owner;
        final Field field = type.getField(name);
        return (List<?>) field.get(null);
    }

    private static Object newInstance(final FixtureLoader loader, final String name) throws Exception {
        return loader.loadClass(name).getConstructor().newInstance();
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader() {
            super(BoundingBoxOverlayButtonUpdateTransformerTest.class.getClassLoader());
        }

        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
