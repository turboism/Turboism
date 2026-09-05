package dev.turboism.core.plugin.context;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.CubismEditorApiUnavailableException;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubismEditorApiAvailabilityInterceptorTest {

    @Test
    void reviewed5303IdentityEntersTheSdkAvailabilitySet() {
        assertEquals(
            List.of("5.2.03", "5.3.02", "5.3.03"),
            CubismEditorAvailabilityPolicy.reviewedVersions()
        );
    }

    @Test
    void cubismHistoryFacadeAndReturnedApiAreAvailableOnReviewed5203Host() {
        final AtomicInteger calls = new AtomicInteger();
        final dev.turboism.sdk.cubism.CubismFacade delegate =
            new dev.turboism.sdk.cubism.CubismFacade() {
                @Override public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() {
                    return null;
                }
                @Override public Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() {
                    return Optional.empty();
                }
                @Override public Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() {
                    return Optional.empty();
                }
                @Override public Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() {
                    return Optional.empty();
                }
                @Override public boolean isHostPresent() { return true; }
                @Override public dev.turboism.sdk.cubism.history.CubismHistory history() {
                    calls.incrementAndGet();
                    return dev.turboism.sdk.cubism.history.CubismHistory.unavailable();
                }
                @Override public dev.turboism.sdk.cubism.transaction.TransactionManager transactionManager() {
                    return null;
                }
            };
        final dev.turboism.sdk.cubism.CubismFacade proxy = proxy(
            delegate,
            dev.turboism.sdk.cubism.CubismFacade.class,
            Optional.of("5.2.03")
        );

        final dev.turboism.sdk.cubism.history.HistorySnapshot snapshot =
            assertDoesNotThrow(() -> proxy.history().snapshot());

        assertEquals(dev.turboism.sdk.cubism.history.HistorySnapshot.unavailable(), snapshot);
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsNarrowMethodBeforeDelegateOnUnsupportedHost() {
        final AtomicInteger calls = new AtomicInteger();
        final Example delegate = new ExampleImpl(calls);
        final Example proxy = proxy(delegate, Optional.of("5.2.03"));

        final CubismEditorApiUnavailableException failure = assertThrows(
            CubismEditorApiUnavailableException.class,
            proxy::only5302
        );

        assertEquals(0, calls.get());
        assertEquals(Optional.of("5.2.03"), failure.activeVersion());
        assertEquals(List.of("5.3.02"), failure.supportedVersions());
    }

    @Test
    void failsClosedWithoutVerifiedEditorVersion() {
        final Example proxy = proxy(new ExampleImpl(new AtomicInteger()), Optional.empty());

        final CubismEditorApiUnavailableException failure = assertThrows(
            CubismEditorApiUnavailableException.class,
            proxy::shared
        );

        assertEquals(Optional.empty(), failure.activeVersion());
    }

    @Test
    void admitsDeclaredApisOnTheReviewed5303Host() {
        final AtomicInteger calls = new AtomicInteger();
        final Declared5303 proxy = proxy(
            (Declared5303) calls::incrementAndGet,
            Declared5303.class,
            Optional.of("5.3.03")
        );

        proxy.call();

        assertEquals(1, calls.get());
    }

    @Test
    void declarationContaining5303PreservesExistingReviewedHosts() {
        final AtomicInteger calls = new AtomicInteger();
        final Declared5303 proxy = proxy(
            (Declared5303) calls::incrementAndGet,
            Declared5303.class,
            Optional.of("5.3.02")
        );

        proxy.call();

        assertEquals(1, calls.get());
    }

    @Test
    void recursivelyWrapsOptionalListAndCompletionStageResults() {
        final Example proxy = proxy(new ExampleImpl(new AtomicInteger()), Optional.of("5.2.03"));

        assertThrows(CubismEditorApiUnavailableException.class, () -> proxy.optional().orElseThrow().only5302());
        assertThrows(CubismEditorApiUnavailableException.class, () -> proxy.list().get(0).only5302());
        assertThrows(
            CubismEditorApiUnavailableException.class,
            () -> proxy.stage().toCompletableFuture().join().only5302()
        );
    }

    @Test
    void unannotatedModelAccessCarrierStillWrapsAnnotatedDescendants() {
        final AtomicInteger activeCalls = new AtomicInteger();
        final AtomicInteger idCalls = new AtomicInteger();
        final dev.turboism.sdk.cubism.model.CubismModel model = recordingDelegate(
            dev.turboism.sdk.cubism.model.CubismModel.class,
            idCalls
        );
        final dev.turboism.sdk.cubism.model.CubismModelAccess access = () -> {
            activeCalls.incrementAndGet();
            return model;
        };
        final dev.turboism.sdk.cubism.model.CubismModelAccess proxy = proxy(
            access,
            dev.turboism.sdk.cubism.model.CubismModelAccess.class,
            Optional.empty()
        );

        final dev.turboism.sdk.cubism.model.CubismModel wrapped = proxy.active();
        assertEquals(1, activeCalls.get());
        assertThrows(CubismEditorApiUnavailableException.class, wrapped::id);
        assertEquals(0, idCalls.get());
    }

    @Test
    void unannotatedPartsCarrierStillWrapsAnnotatedPartListElements() {
        final AtomicInteger allCalls = new AtomicInteger();
        final AtomicInteger idCalls = new AtomicInteger();
        final dev.turboism.sdk.cubism.model.Part part = recordingDelegate(
            dev.turboism.sdk.cubism.model.Part.class,
            idCalls
        );
        final dev.turboism.sdk.cubism.model.Parts parts = new dev.turboism.sdk.cubism.model.Parts() {
            @Override public java.util.List<dev.turboism.sdk.cubism.model.Part> all() {
                allCalls.incrementAndGet();
                return List.of(part);
            }
            @Override public dev.turboism.sdk.cubism.model.Part find(
                final dev.turboism.sdk.cubism.model.PartId id
            ) {
                throw new UnsupportedOperationException("not exercised");
            }
        };
        final dev.turboism.sdk.cubism.model.Parts proxy = proxy(
            parts,
            dev.turboism.sdk.cubism.model.Parts.class,
            Optional.of("5.2.03")
        );

        final dev.turboism.sdk.cubism.model.Part wrapped = proxy.all().get(0);
        assertEquals(1, allCalls.get());
        // Part is restricted to {5.2.03, 5.3.02}; a 5.2.03 host is admitted.
        assertDoesNotThrow(() -> wrapped.id());
        assertEquals(1, idCalls.get());

        final dev.turboism.sdk.cubism.model.Parts onUnknownHost = proxy(
            parts,
            dev.turboism.sdk.cubism.model.Parts.class,
            Optional.of("5.2.04")
        );
        final dev.turboism.sdk.cubism.model.Part wrappedUnknown = onUnknownHost.all().get(0);
        assertThrows(CubismEditorApiUnavailableException.class, wrappedUnknown::id);
        assertEquals(1, idCalls.get());
    }

    @Test
    void discardedModelWrappersAndDelegatesCanBeCollectedWhileRootRemainsLive() throws Exception {
        final List<WeakReference<?>> references = new ArrayList<>();
        final CubismEditorApiAvailabilityInterceptor interceptor =
            new CubismEditorApiAvailabilityInterceptor(() -> Optional.of("5.3.02"));
        final dev.turboism.sdk.cubism.model.CubismModelAccess access = () -> {
            final dev.turboism.sdk.cubism.model.CubismModel model = recordingDelegate(
                dev.turboism.sdk.cubism.model.CubismModel.class, new AtomicInteger()
            );
            references.add(new WeakReference<>(model));
            return model;
        };
        final dev.turboism.sdk.cubism.model.CubismModelAccess root = interceptor.wrapForTesting(
            access, dev.turboism.sdk.cubism.model.CubismModelAccess.class
        );
        for (int index = 0; index < 64; index++) {
            references.add(new WeakReference<>(root.active()));
        }
        try {
            assertCollected(references);
            // Exercise lookup after collection too: stale cache keys must not break new wrapping.
            assertDoesNotThrow(() -> root.active().id());
        } finally {
            Reference.reachabilityFence(root);
            Reference.reachabilityFence(interceptor);
        }
    }

    @Test
    void discardedProxyCanBeCollectedEvenWhenDelegateRemainsLive() throws Exception {
        final CubismEditorApiAvailabilityInterceptor interceptor =
            new CubismEditorApiAvailabilityInterceptor(() -> Optional.of("5.3.02"));
        final Example delegate = new ExampleImpl(new AtomicInteger());
        final WeakReference<Example> discarded = new WeakReference<>(
            interceptor.wrapForTesting(delegate, Example.class)
        );
        try {
            assertCollected(List.of(discarded));
            final Example recreated = interceptor.wrapForTesting(delegate, Example.class);
            assertSame(recreated, recreated.optional().orElseThrow());
            assertDoesNotThrow(recreated::only5302);
        } finally {
            Reference.reachabilityFence(delegate);
            Reference.reachabilityFence(interceptor);
        }
    }

    @Test
    void liveProxiesKeepIdentityWithoutCallingDelegateEqualityAndStillCheckVersions() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.02"));
        final CubismEditorApiAvailabilityInterceptor interceptor =
            new CubismEditorApiAvailabilityInterceptor(version::get);
        final Example delegate = new ExampleImpl(new AtomicInteger()) {
            @Override public boolean equals(final Object other) {
                throw new AssertionError("cache must use identity, not delegate equality");
            }
            @Override public int hashCode() {
                throw new AssertionError("cache must not call delegate hashCode");
            }
        };
        final Example live = interceptor.wrapForTesting(delegate, Example.class);
        System.gc();
        assertSame(live, interceptor.wrapForTesting(delegate, Example.class));
        assertSame(live, live.optional().orElseThrow());
        assertNotSame(live, interceptor.wrapForTesting(new ExampleImpl(new AtomicInteger()), Example.class));
        version.set(Optional.of("5.2.03"));
        assertThrows(CubismEditorApiUnavailableException.class, live::only5302);
        assertSame(live, interceptor.wrapForTesting(delegate, Example.class));
    }

    private static void assertCollected(final List<WeakReference<?>> references) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (references.stream().anyMatch(reference -> !reference.refersTo(null))
            && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(10);
        }
        assertTrue(references.stream().allMatch(reference -> reference.refersTo(null)),
            "the live interceptor must not retain discarded proxies or delegates");
    }

    private static <T> T recordingDelegate(final Class<T> type, final AtomicInteger calls) {
        return type.cast(Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, arguments) -> {
                calls.incrementAndGet();
                return null;
            }
        ));
    }

    @Test
    void unwrapsOwnedProxyArgumentsBeforeDelegateInvocation() {
        final AtomicReference<Example> received = new AtomicReference<>();
        final ExampleImpl delegate = new ExampleImpl(new AtomicInteger()) {
            @Override public void accept(final Example example) { received.set(example); }
        };
        final Example proxy = proxy(delegate, Optional.of("5.3.02"));
        final Example nested = proxy.optional().orElseThrow();

        proxy.accept(nested);

        assertNotSame(delegate, nested);
        assertSame(delegate, received.get());
    }

    @Test
    void expandsInclusiveReviewedRangesWithoutAdmittingUnknownHosts() {
        final RangeExample supported = proxy(
            new RangeExampleImpl(), RangeExample.class, Optional.of("5.3.02")
        );
        supported.ranged();

        final RangeExample unknown = proxy(
            new RangeExampleImpl(), RangeExample.class, Optional.of("5.2.04")
        );
        final CubismEditorApiUnavailableException failure = assertThrows(
            CubismEditorApiUnavailableException.class,
            unknown::ranged
        );
        assertEquals(List.of("5.2.03", "5.3.02"), failure.supportedVersions());
    }

    @Test
    void appliesExclusionsAfterPositiveSelection() {
        final ExcludedExample proxy = proxy(
            new ExcludedExampleImpl(), ExcludedExample.class, Optional.of("5.3.02")
        );

        final CubismEditorApiUnavailableException failure = assertThrows(
            CubismEditorApiUnavailableException.class,
            proxy::call
        );
        assertEquals(List.of("5.2.03", "5.3.03"), failure.supportedVersions());
    }

    @Test
    void intersectsInheritedTypeAndMethodDeclarations() {
        final ChildExample on5203 = proxy(
            new ChildExampleImpl(), ChildExample.class, Optional.of("5.2.03")
        );
        assertThrows(CubismEditorApiUnavailableException.class, on5203::shared);
        assertThrows(CubismEditorApiUnavailableException.class, on5203::narrow);

        final ChildExample on5302 = proxy(
            new ChildExampleImpl(), ChildExample.class, Optional.of("5.3.02")
        );
        on5302.shared();
        on5302.narrow();
    }

    @Test
    void exclusionOnlyDeclarationMayProhibitEveryReviewedVersion() {
        final ProhibitedExample prohibited = proxy(
            new ProhibitedExampleImpl(), ProhibitedExample.class, Optional.of("5.3.02")
        );

        final CubismEditorApiUnavailableException failure = assertThrows(
            CubismEditorApiUnavailableException.class,
            prohibited::call
        );
        assertEquals(List.of(), failure.supportedVersions());
    }

    @Test
    void rejectsInvalidMixedAndReverseRangeDeclarations() {
        final InvalidExample invalid = proxy(
            new InvalidExampleImpl(), InvalidExample.class, Optional.of("5.3.02")
        );
        assertThrows(IllegalStateException.class, invalid::mixed);
        assertThrows(IllegalStateException.class, invalid::reverse);
    }

    private static Example proxy(final Example delegate, final Optional<String> version) {
        return proxy(delegate, Example.class, version);
    }

    private static <T> T proxy(
        final T delegate,
        final Class<T> type,
        final Optional<String> version
    ) {
        return new CubismEditorApiAvailabilityInterceptor(() -> version)
            .wrapForTesting(delegate, type);
    }

    @CubismEditor({"5.2.03", "5.3.02"})
    interface Example {
        void shared();

        @CubismEditor("5.3.02")
        void only5302();

        Optional<Example> optional();

        List<Example> list();

        CompletionStage<Example> stage();

        void accept(Example example);
    }

    static class ExampleImpl implements Example {
        private final AtomicInteger calls;

        ExampleImpl(final AtomicInteger calls) {
            this.calls = calls;
        }

        @Override public void shared() { calls.incrementAndGet(); }

        @Override public void only5302() { calls.incrementAndGet(); }

        @Override public Optional<Example> optional() { return Optional.of(this); }

        @Override public List<Example> list() { return List.of(this); }

        @Override public CompletionStage<Example> stage() {
            return CompletableFuture.completedFuture(this);
        }

        @Override public void accept(final Example example) { }
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    interface Declared5303 {
        void call();
    }

    @CubismEditor(from = "5.2.03", to = "5.3.02")
    interface RangeExample {
        void ranged();
    }

    static final class RangeExampleImpl implements RangeExample {
        @Override public void ranged() { }
    }

    @CubismEditor(exclude = "5.3.02")
    interface ExcludedExample {
        void call();
    }

    static final class ExcludedExampleImpl implements ExcludedExample {
        @Override public void call() { }
    }

    @CubismEditor({"5.2.03", "5.3.02"})
    interface ParentExample {
        void shared();
    }

    @CubismEditor(from = "5.3.02")
    interface ChildExample extends ParentExample {
        @CubismEditor("5.3.02")
        @Override void shared();

        @CubismEditor("5.3.02")
        void narrow();
    }

    static final class ChildExampleImpl implements ChildExample {
        @Override public void shared() { }
        @Override public void narrow() { }
    }

    @CubismEditor(exclude = {"5.2.03", "5.3.02", "5.3.03"})
    interface ProhibitedExample {
        void call();
    }

    static final class ProhibitedExampleImpl implements ProhibitedExample {
        @Override public void call() { }
    }

    interface InvalidExample {
        @CubismEditor(value = "5.3.02", from = "5.2.03")
        void mixed();

        @CubismEditor(from = "5.3.02", to = "5.2.03")
        void reverse();
    }

    static final class InvalidExampleImpl implements InvalidExample {
        @Override public void mixed() { }
        @Override public void reverse() { }
    }
}
