package dev.turboism.core.plugin.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.CubismEditorApiUnavailableException;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.plugin.PluginServiceDirectory;
import dev.turboism.test.concurrent.Await;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CubismEditorApiAvailabilityInterceptorTest {

    @Test
    void baseDeformerCollectionsPreserveClosedSubtypesAndKeepTheirGuards() {
        var version = new AtomicReference<>(Optional.of("5.3.03"));
        var guard = new CubismEditorApiAvailabilityInterceptor(version::get);
        var warp = (dev.turboism.sdk.cubism.model.WarpDeformer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {dev.turboism.sdk.cubism.model.WarpDeformer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "id" -> new dev.turboism.sdk.cubism.id.DeformerId("warp");
                    case "grid" ->
                        new dev.turboism.sdk.cubism.model.WarpGrid(
                                1,
                                1,
                                false,
                                java.util.List.of(
                                        new dev.turboism.sdk.cubism.model.Point2(0, 0),
                                                new dev.turboism.sdk.cubism.model.Point2(1, 0),
                                        new dev.turboism.sdk.cubism.model.Point2(0, 1),
                                                new dev.turboism.sdk.cubism.model.Point2(1, 1)));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var rotation = (dev.turboism.sdk.cubism.model.RotationDeformer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {dev.turboism.sdk.cubism.model.RotationDeformer.class},
                (proxy, method, arguments) -> new dev.turboism.sdk.cubism.id.DeformerId("rotation"));
        var source = (dev.turboism.sdk.cubism.model.Deformers) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {dev.turboism.sdk.cubism.model.Deformers.class},
                (proxy, method, arguments) ->
                        method.getName().equals("all") ? java.util.List.of(warp, rotation) : warp);
        var wrapped = guard.wrapForTesting(source, dev.turboism.sdk.cubism.model.Deformers.class);
        var values = wrapped.all();
        assertTrue(values.get(0) instanceof dev.turboism.sdk.cubism.model.WarpDeformer);
        assertTrue(values.get(1) instanceof dev.turboism.sdk.cubism.model.RotationDeformer);
        assertSame(values.get(0), wrapped.find(new dev.turboism.sdk.cubism.id.DeformerId("warp")));
        assertEquals(
                1,
                ((dev.turboism.sdk.cubism.model.WarpDeformer) values.get(0))
                        .grid()
                        .rows());
        assertEquals("rotation", values.get(1).id().value());
        version.set(Optional.empty());
        assertThrows(
                CubismEditorApiUnavailableException.class,
                () -> ((dev.turboism.sdk.cubism.model.WarpDeformer) values.get(0)).authoringSnapshot());
    }

    @Test
    void compatibleEditSessionsRequireLiveNativeIngressAndRespectThe54Boundary() throws Exception {
        final var version = new java.util.concurrent.atomic.AtomicReference<>(Optional.of("5.3.99"));
        final var capabilities = new java.util.concurrent.atomic.AtomicReference<>(
                java.util.Set.of("cubism.editor-model.edit.session.edit-begin"));
        final AtomicInteger calls = new AtomicInteger();
        final var delegate = new dev.turboism.sdk.cubism.edit.EditSessionService() {
            @Override
            public boolean isEditApproved(final dev.turboism.sdk.plugin.PluginContext context) {
                calls.incrementAndGet();
                return true;
            }

            @Override
            public dev.turboism.sdk.cubism.edit.EditSession open(
                    final dev.turboism.sdk.plugin.PluginContext context,
                    final dev.turboism.sdk.cubism.id.DocumentId document,
                    final dev.turboism.sdk.cubism.edit.EditSessionOptions options) {
                throw new UnsupportedOperationException();
            }
        };
        final var api = new CubismEditorApiAvailabilityInterceptor(version::get, capabilities::get)
                .wrapForTesting(delegate, dev.turboism.sdk.cubism.edit.EditSessionService.class);
        assertTrue(api.isEditApproved(null));
        capabilities.set(java.util.Set.of());
        assertThrows(CubismEditorApiUnavailableException.class, () -> api.isEditApproved(null));
        capabilities.set(java.util.Set.of("cubism.editor-model.edit.session.edit-begin"));
        version.set(Optional.of("5.4.00"));
        assertThrows(CubismEditorApiUnavailableException.class, () -> api.isEditApproved(null));
        version.set(Optional.of("5.1.01"));
        assertThrows(CubismEditorApiUnavailableException.class, () -> api.isEditApproved(null));
        assertEquals(1, calls.get());
    }

    @Test
    void futureHostCanUpdateAutoBackupSettingsOnlyWithTheMatchedCapabilities() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.99"));
        final AtomicReference<java.util.Set<String>> capabilities =
                new AtomicReference<>(java.util.Set.of("cubism.autobackup.settings", "cubism.autobackup.backup"));
        final AtomicInteger mutations = new AtomicInteger();
        final var delegate = (dev.turboism.sdk.cubism.backup.EditorAutoBackupService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {dev.turboism.sdk.cubism.backup.EditorAutoBackupService.class},
                (proxy, method, args) -> {
                    if ("updateSettings".equals(method.getName())) {
                        mutations.incrementAndGet();
                        return args[0];
                    }
                    throw new AssertionError("Unexpected delegate call: " + method);
                });
        final var service = new CubismEditorApiAvailabilityInterceptor(version::get, capabilities::get)
                .wrapForTesting(delegate, dev.turboism.sdk.cubism.backup.EditorAutoBackupService.class);
        final var target =
                new dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings(true, 3, 120, java.util.Optional.empty());

        assertEquals(target, service.updateSettings(target));
        capabilities.set(java.util.Set.of("cubism.autobackup.settings"));
        assertThrows(CubismEditorApiUnavailableException.class, () -> service.updateSettings(target));
        capabilities.set(java.util.Set.of("cubism.autobackup.settings", "cubism.autobackup.backup"));
        version.set(Optional.of("5.0.06"));
        assertThrows(CubismEditorApiUnavailableException.class, () -> service.updateSettings(target));
        assertEquals(1, mutations.get());
    }

    @Test
    void immutableAvailabilityResolutionIsReusedButActiveVersionIsAlwaysRechecked() throws Exception {
        final var method = Example.class.getMethod("only5302");
        assertSame(CubismEditorAvailabilityPolicy.resolve(method), CubismEditorAvailabilityPolicy.resolve(method));
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.02"));
        final AtomicInteger calls = new AtomicInteger();
        final var interceptor = new CubismEditorApiAvailabilityInterceptor(version::get);
        final Example proxy = interceptor.wrapForTesting(new ExampleImpl(calls), Example.class);
        proxy.only5302();
        version.set(Optional.of("5.2.03"));
        assertThrows(CubismEditorApiUnavailableException.class, proxy::only5302);
        version.set(Optional.empty());
        assertThrows(CubismEditorApiUnavailableException.class, proxy::only5302);
        version.set(Optional.of("5.3.02"));
        proxy.only5302();
        assertEquals(2, calls.get());
    }

    @Test
    void unchangedValueListsPreserveTheProvidersCachedIdentity() {
        final List<String> values = List.of("first", "second");
        final ValueList proxy = proxy((ValueList) () -> values, ValueList.class, Optional.of("5.3.02"));
        assertSame(values, proxy.values());
        assertSame(proxy.values(), proxy.values());
    }

    @CubismEditor("5.3.02")
    interface ValueList {
        List<String> values();
    }

    @Test
    void reviewed5303IdentityEntersTheSdkAvailabilitySet() {
        assertEquals(
                List.of(
                        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01",
                        "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02",
                        "5.3.03", "5.3.04"),
                CubismEditorAvailabilityPolicy.reviewedVersions());
    }

    @Test
    void inheritedMethodStillHonorsTheNarrowerChildInterface() {
        final AtomicInteger calls = new AtomicInteger();
        final NarrowInherited child =
                proxy((NarrowInherited) calls::incrementAndGet, NarrowInherited.class, Optional.of("5.2.03"));
        assertThrows(CubismEditorApiUnavailableException.class, child::call);
        final OpenParent parent = proxy((OpenParent) calls::incrementAndGet, OpenParent.class, Optional.of("5.2.03"));
        parent.call();
        assertEquals(1, calls.get(), "a child-specific cached restriction must not leak into its parent");
    }

    @Test
    void overridingMethodCannotEraseTheParentsExplicitRestriction() {
        final AtomicInteger calls = new AtomicInteger();
        final var version = new AtomicReference<>(Optional.of("5.3.99"));
        final RedeclaredChild child = new CubismEditorApiAvailabilityInterceptor(version::get)
                .wrapForTesting((RedeclaredChild) calls::incrementAndGet, RedeclaredChild.class);

        assertThrows(CubismEditorApiUnavailableException.class, child::call);
        assertEquals(0, calls.get());
        version.set(Optional.of("5.3.02"));
        child.call();
        assertEquals(1, calls.get());
    }

    @CubismEditor(from = "5.2.03")
    interface OpenParent {
        void call();
    }

    @CubismEditor(from = "5.3.02")
    interface NarrowInherited extends OpenParent {}

    interface RestrictedMethodParent {
        @CubismEditor("5.3.02")
        void call();
    }

    @CubismEditor(from = "5.2.03")
    interface RedeclaredChild extends RestrictedMethodParent {
        @Override
        void call();
    }

    @Test
    void bareDeclarationImposesNoFloorWithoutVerifiedHostAndCannotEraseParentRestriction() {
        final AtomicInteger bareCalls = new AtomicInteger();
        final BareCarrier bare = proxy((BareCarrier) bareCalls::incrementAndGet, BareCarrier.class, Optional.empty());
        assertDoesNotThrow(bare::unconstrained);
        assertEquals(1, bareCalls.get());
        assertThrows(CubismEditorApiUnavailableException.class, bare::restrictedDescendant);
        assertEquals(1, bareCalls.get());

        final AtomicInteger childCalls = new AtomicInteger();
        final BareChildOfRestricted parentRestricted = proxy(
                (BareChildOfRestricted) childCalls::incrementAndGet, BareChildOfRestricted.class, Optional.empty());
        assertThrows(CubismEditorApiUnavailableException.class, parentRestricted::call);
        assertEquals(0, childCalls.get());
    }

    @CubismEditor
    interface BareCarrier {
        void unconstrained();

        @CubismEditor(from = "5.0.00")
        default void restrictedDescendant() {
            unconstrained();
        }
    }

    @CubismEditor
    interface BareChildOfRestricted extends OpenParent {
        @CubismEditor
        @Override
        void call();
    }

    @Test
    void cubismHistoryFacadeAndReturnedApiAreAvailableOnReviewed5203Host() {
        final AtomicInteger calls = new AtomicInteger();
        final dev.turboism.sdk.cubism.CubismFacade delegate = new dev.turboism.sdk.cubism.CubismFacade() {
            @Override
            public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() {
                return null;
            }

            @Override
            public Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() {
                return Optional.empty();
            }

            @Override
            public Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() {
                return Optional.empty();
            }

            @Override
            public Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() {
                return Optional.empty();
            }

            @Override
            public boolean isHostPresent() {
                return true;
            }

            @Override
            public dev.turboism.sdk.cubism.history.CubismHistory history() {
                calls.incrementAndGet();
                return dev.turboism.sdk.cubism.history.CubismHistory.unavailable();
            }
        };
        final dev.turboism.sdk.cubism.CubismFacade proxy = new CubismEditorApiAvailabilityInterceptor(
                        () -> Optional.of("5.2.03"),
                        () -> java.util.Set.of("cubism.editor-model.read", "cubism.editor-history.read"))
                .wrapForTesting(delegate, dev.turboism.sdk.cubism.CubismFacade.class);

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

        final CubismEditorApiUnavailableException failure =
                assertThrows(CubismEditorApiUnavailableException.class, proxy::only5302);

        assertEquals(0, calls.get());
        assertEquals(Optional.of("5.2.03"), failure.activeVersion());
        assertEquals(List.of("5.3.02"), failure.supportedVersions());
    }

    @Test
    void failsClosedWithoutVerifiedEditorVersion() {
        final Example proxy = proxy(new ExampleImpl(new AtomicInteger()), Optional.empty());

        final CubismEditorApiUnavailableException failure =
                assertThrows(CubismEditorApiUnavailableException.class, proxy::shared);

        assertEquals(Optional.empty(), failure.activeVersion());
    }

    @Test
    void admitsDeclaredApisOnTheReviewed5303Host() {
        final AtomicInteger calls = new AtomicInteger();
        final Declared5303 proxy =
                proxy((Declared5303) calls::incrementAndGet, Declared5303.class, Optional.of("5.3.03"));

        proxy.call();

        assertEquals(1, calls.get());
    }

    @Test
    void declarationContaining5303PreservesExistingReviewedHosts() {
        final AtomicInteger calls = new AtomicInteger();
        final Declared5303 proxy =
                proxy((Declared5303) calls::incrementAndGet, Declared5303.class, Optional.of("5.3.02"));

        proxy.call();

        assertEquals(1, calls.get());
    }

    @Test
    void recursivelyWrapsOptionalListAndCompletionStageResults() {
        final Example proxy = proxy(new ExampleImpl(new AtomicInteger()), Optional.of("5.2.03"));

        assertThrows(
                CubismEditorApiUnavailableException.class,
                () -> proxy.optional().orElseThrow().only5302());
        assertThrows(
                CubismEditorApiUnavailableException.class,
                () -> proxy.list().get(0).only5302());
        assertThrows(
                CubismEditorApiUnavailableException.class,
                () -> proxy.stage().toCompletableFuture().join().only5302());
    }

    @Test
    void unannotatedModelAccessCarrierStillWrapsAnnotatedDescendants() {
        final AtomicInteger activeCalls = new AtomicInteger();
        final AtomicInteger idCalls = new AtomicInteger();
        final dev.turboism.sdk.cubism.model.CubismModel model =
                recordingDelegate(dev.turboism.sdk.cubism.model.CubismModel.class, idCalls);
        final dev.turboism.sdk.cubism.model.CubismModelAccess access = () -> {
            activeCalls.incrementAndGet();
            return model;
        };
        final dev.turboism.sdk.cubism.model.CubismModelAccess proxy =
                proxy(access, dev.turboism.sdk.cubism.model.CubismModelAccess.class, Optional.empty());

        final dev.turboism.sdk.cubism.model.CubismModel wrapped = proxy.active();
        assertEquals(1, activeCalls.get());
        assertThrows(CubismEditorApiUnavailableException.class, wrapped::id);
        assertEquals(0, idCalls.get());
    }

    @Test
    void unannotatedPartsCarrierStillWrapsAnnotatedPartListElements() {
        final AtomicInteger allCalls = new AtomicInteger();
        final AtomicInteger idCalls = new AtomicInteger();
        final dev.turboism.sdk.cubism.model.Part part =
                recordingDelegate(dev.turboism.sdk.cubism.model.Part.class, idCalls);
        final dev.turboism.sdk.cubism.model.Parts parts = new dev.turboism.sdk.cubism.model.Parts() {
            @Override
            public java.util.List<dev.turboism.sdk.cubism.model.Part> all() {
                allCalls.incrementAndGet();
                return List.of(part);
            }

            @Override
            public dev.turboism.sdk.cubism.model.Part find(final dev.turboism.sdk.cubism.model.PartId id) {
                throw new UnsupportedOperationException("not exercised");
            }
        };
        final dev.turboism.sdk.cubism.model.Parts proxy =
                proxy(parts, dev.turboism.sdk.cubism.model.Parts.class, Optional.of("5.2.03"));

        final dev.turboism.sdk.cubism.model.Part wrapped = proxy.all().get(0);
        assertEquals(1, allCalls.get());
        // Part is restricted to {5.2.03, 5.3.02}; a 5.2.03 host is admitted.
        assertDoesNotThrow(() -> wrapped.id());
        assertEquals(1, idCalls.get());

        // Part is pinned to an exact reviewed list; the declared 5.2.04 host
        // is not named by it and fails closed. The reviewed contract's source
        // version is never consulted.
        final dev.turboism.sdk.cubism.model.Parts onUnlistedHost =
                proxy(parts, dev.turboism.sdk.cubism.model.Parts.class, Optional.of("5.2.04"));
        final dev.turboism.sdk.cubism.model.Part wrappedUnlisted =
                onUnlistedHost.all().get(0);
        assertThrows(CubismEditorApiUnavailableException.class, wrappedUnlisted::id);
        assertEquals(1, idCalls.get());
    }

    @Test
    void discardedModelWrappersAndDelegatesCanBeCollectedWhileRootRemainsLive() throws Exception {
        final List<WeakReference<?>> references = new ArrayList<>();
        final CubismEditorApiAvailabilityInterceptor interceptor = new CubismEditorApiAvailabilityInterceptor(
                () -> Optional.of("5.3.02"), () -> java.util.Set.of("cubism.editor-model.read"));
        final dev.turboism.sdk.cubism.model.CubismModelAccess access = () -> {
            final dev.turboism.sdk.cubism.model.CubismModel model =
                    recordingDelegate(dev.turboism.sdk.cubism.model.CubismModel.class, new AtomicInteger());
            references.add(new WeakReference<>(model));
            return model;
        };
        final dev.turboism.sdk.cubism.model.CubismModelAccess root =
                interceptor.wrapForTesting(access, dev.turboism.sdk.cubism.model.CubismModelAccess.class);
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
        final WeakReference<Example> discarded =
                new WeakReference<>(interceptor.wrapForTesting(delegate, Example.class));
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
            @Override
            public boolean equals(final Object other) {
                throw new AssertionError("cache must use identity, not delegate equality");
            }

            @Override
            public int hashCode() {
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

    private static void assertCollected(final List<WeakReference<?>> references) {
        Await.condition(
                "references collected",
                () -> {
                    System.gc();
                    return references.stream().allMatch(reference -> reference.refersTo(null));
                },
                Duration.ofSeconds(5));
        assertTrue(
                references.stream().allMatch(reference -> reference.refersTo(null)),
                "the live interceptor must not retain discarded proxies or delegates");
    }

    private static <T> T recordingDelegate(final Class<T> type, final AtomicInteger calls) {
        return type.cast(
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> {
                    calls.incrementAndGet();
                    return null;
                }));
    }

    @Test
    void unwrapsOwnedProxyArgumentsBeforeDelegateInvocation() {
        final AtomicReference<Example> received = new AtomicReference<>();
        final ExampleImpl delegate = new ExampleImpl(new AtomicInteger()) {
            @Override
            public void accept(final Example example) {
                received.set(example);
            }
        };
        final Example proxy = proxy(delegate, Optional.of("5.3.02"));
        final Example nested = proxy.optional().orElseThrow();

        proxy.accept(nested);

        assertNotSame(delegate, nested);
        assertSame(delegate, received.get());
    }

    @Test
    void compatibilityBoundHostKeepsItsDeclaredVersionRestrictions() {
        final AtomicInteger calls = new AtomicInteger();
        final Example compat = compatProxy(new ExampleImpl(calls), Optional.of("5.3.99"), Optional.of("5.3.02"));

        assertThrows(CubismEditorApiUnavailableException.class, compat::only5302);
        assertEquals(0, calls.get());

        // Binding an older contract cannot satisfy an exact version list.
        final Only5303 otherGeneration = new CubismEditorApiAvailabilityInterceptor(
                        () -> Optional.of("5.3.99"), java.util.Set::of, () -> Optional.of("5.3.02"))
                .wrapForTesting((Only5303) () -> {}, Only5303.class);
        assertThrows(CubismEditorApiUnavailableException.class, otherGeneration::call);
        final ExcludedExample excluded = new CubismEditorApiAvailabilityInterceptor(
                        () -> Optional.of("5.3.99"), java.util.Set::of, () -> Optional.of("5.3.02"))
                .wrapForTesting(new ExcludedExampleImpl(), ExcludedExample.class);
        assertDoesNotThrow(excluded::call);

        // Range boundaries also use the host version, not the source record.
        final BoundedBelow boundedAbove = new CubismEditorApiAvailabilityInterceptor(
                        () -> Optional.of("5.2.03"), java.util.Set::of, () -> Optional.of("5.3.02"))
                .wrapForTesting((BoundedBelow) () -> {}, BoundedBelow.class);
        assertDoesNotThrow(boundedAbove::call);
    }

    @Test
    void compatibilityHostWithoutCapabilityEvidenceFailsAnnotatedSurface() {
        // A mapped surface whose backing capability was dropped must fail
        // closed even though the bound generation satisfies the annotation.
        final dev.turboism.sdk.cubism.history.CubismHistory history = new CubismEditorApiAvailabilityInterceptor(
                        () -> Optional.of("5.3.99"), java.util.Set::of, () -> Optional.of("5.3.02"))
                .wrapForTesting(
                        dev.turboism.sdk.cubism.history.CubismHistory.unavailable(),
                        dev.turboism.sdk.cubism.history.CubismHistory.class);
        assertThrows(CubismEditorApiUnavailableException.class, history::snapshot);
    }

    private static Example compatProxy(
            final Example delegate, final Optional<String> declared, final Optional<String> generation) {
        return new CubismEditorApiAvailabilityInterceptor(() -> declared, java.util.Set::of, () -> generation)
                .wrapForTesting(delegate, Example.class);
    }

    @Test
    void rangesEvaluateTheDeclaredVersionNumerically() {
        final RangeExample supported = proxy(new RangeExampleImpl(), RangeExample.class, Optional.of("5.3.02"));
        supported.ranged();

        // An unreviewed declared version inside the inclusive range is
        // permitted by the annotation; capability evidence is a separate gate.
        final RangeExample inRange = proxy(new RangeExampleImpl(), RangeExample.class, Optional.of("5.2.04"));
        assertDoesNotThrow(inRange::ranged);

        final RangeExample aboveBound = proxy(new RangeExampleImpl(), RangeExample.class, Optional.of("5.3.99"));
        final CubismEditorApiUnavailableException failure =
                assertThrows(CubismEditorApiUnavailableException.class, aboveBound::ranged);
        assertEquals(List.of("5.2.03", "5.3.00", "5.3.01", "5.3.02"), failure.supportedVersions());
        final RangeExample belowFloor = proxy(new RangeExampleImpl(), RangeExample.class, Optional.of("5.1.00"));
        assertThrows(CubismEditorApiUnavailableException.class, belowFloor::ranged);
    }

    @Test
    void appliesExclusionsAfterPositiveSelection() {
        final ExcludedExample proxy = proxy(new ExcludedExampleImpl(), ExcludedExample.class, Optional.of("5.3.02"));

        final CubismEditorApiUnavailableException failure =
                assertThrows(CubismEditorApiUnavailableException.class, proxy::call);
        assertEquals(
                List.of(
                        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01",
                        "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.03",
                        "5.3.04"),
                failure.supportedVersions());
    }

    @Test
    void intersectsInheritedTypeAndMethodDeclarations() {
        final ChildExample on5203 = proxy(new ChildExampleImpl(), ChildExample.class, Optional.of("5.2.03"));
        assertThrows(CubismEditorApiUnavailableException.class, on5203::shared);
        assertThrows(CubismEditorApiUnavailableException.class, on5203::narrow);

        final ChildExample on5302 = proxy(new ChildExampleImpl(), ChildExample.class, Optional.of("5.3.02"));
        on5302.shared();
        on5302.narrow();
    }

    @Test
    void exclusionOnlyDeclarationMayProhibitEveryReviewedVersion() {
        final ProhibitedExample prohibited =
                proxy(new ProhibitedExampleImpl(), ProhibitedExample.class, Optional.of("5.3.02"));

        final CubismEditorApiUnavailableException failure =
                assertThrows(CubismEditorApiUnavailableException.class, prohibited::call);
        assertEquals(List.of(), failure.supportedVersions());
    }

    @Test
    void rejectsInvalidMixedAndReverseRangeDeclarations() {
        final InvalidExample invalid = proxy(new InvalidExampleImpl(), InvalidExample.class, Optional.of("5.3.02"));
        assertThrows(IllegalStateException.class, invalid::mixed);
        assertThrows(IllegalStateException.class, invalid::reverse);
    }

    private static Example proxy(final Example delegate, final Optional<String> version) {
        return proxy(delegate, Example.class, version);
    }

    private static <T> T proxy(final T delegate, final Class<T> type, final Optional<String> version) {
        return new CubismEditorApiAvailabilityInterceptor(() -> version).wrapForTesting(delegate, type);
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

        @Override
        public void shared() {
            calls.incrementAndGet();
        }

        @Override
        public void only5302() {
            calls.incrementAndGet();
        }

        @Override
        public Optional<Example> optional() {
            return Optional.of(this);
        }

        @Override
        public List<Example> list() {
            return List.of(this);
        }

        @Override
        public CompletionStage<Example> stage() {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void accept(final Example example) {}
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    interface Declared5303 {
        void call();
    }

    @CubismEditor("5.3.03")
    interface Only5303 {
        void call();
    }

    @CubismEditor(to = "5.2.03")
    interface BoundedBelow {
        void call();
    }

    @CubismEditor(from = "5.2.03", to = "5.3.02")
    interface RangeExample {
        void ranged();
    }

    static final class RangeExampleImpl implements RangeExample {
        @Override
        public void ranged() {}
    }

    @CubismEditor(exclude = "5.3.02")
    interface ExcludedExample {
        void call();
    }

    static final class ExcludedExampleImpl implements ExcludedExample {
        @Override
        public void call() {}
    }

    @CubismEditor({"5.2.03", "5.3.02"})
    interface ParentExample {
        void shared();
    }

    @CubismEditor(from = "5.3.02")
    interface ChildExample extends ParentExample {
        @CubismEditor("5.3.02")
        @Override
        void shared();

        @CubismEditor("5.3.02")
        void narrow();
    }

    static final class ChildExampleImpl implements ChildExample {
        @Override
        public void shared() {}

        @Override
        public void narrow() {}
    }

    @CubismEditor(
            exclude = {
                "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
            })
    interface ProhibitedExample {
        void call();
    }

    static final class ProhibitedExampleImpl implements ProhibitedExample {
        @Override
        public void call() {}
    }

    // ---- Declared-version evaluation against arbitrary (unreviewed) hosts ----

    @Test
    void boundGenerationCannotOverrideDeclaredVersionRestrictions() {
        final var interceptor = new CubismEditorApiAvailabilityInterceptor(
                () -> Optional.of("5.9.99"), java.util.Set::of, () -> Optional.of("5.3.02"));
        final FutureBoundService bounded =
                interceptor.wrapForTesting(new FutureBoundServiceImpl(), FutureBoundService.class);
        assertThrows(CubismEditorApiUnavailableException.class, bounded::excluded);
        assertThrows(CubismEditorApiUnavailableException.class, bounded::bounded);
        final ExactOnlyService exact = interceptor.wrapForTesting(new ExactOnlyServiceImpl(), ExactOnlyService.class);
        assertThrows(CubismEditorApiUnavailableException.class, exact::exact);
    }

    @Test
    void boundGenerationCannotSubstituteForAnAbsentHost() {
        final var interceptor = new CubismEditorApiAvailabilityInterceptor(
                Optional::empty, java.util.Set::of, () -> Optional.of("5.3.02"));
        final ExactOnlyService exact = interceptor.wrapForTesting(new ExactOnlyServiceImpl(), ExactOnlyService.class);
        assertThrows(CubismEditorApiUnavailableException.class, exact::exact);
    }

    @Test
    void annotationPermittedHostStillRequiresCapabilityEvidence() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.99"));
        final AtomicReference<java.util.Set<String>> capabilities =
                new AtomicReference<>(java.util.Set.of("cubism.editor-model.read"));
        final dev.turboism.sdk.cubism.CubismFacade delegate = new dev.turboism.sdk.cubism.CubismFacade() {
            @Override
            public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() {
                return null;
            }

            @Override
            public Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() {
                return Optional.empty();
            }

            @Override
            public Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() {
                return Optional.empty();
            }

            @Override
            public Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() {
                return Optional.empty();
            }

            @Override
            public boolean isHostPresent() {
                return true;
            }

            @Override
            public dev.turboism.sdk.cubism.history.CubismHistory history() {
                return dev.turboism.sdk.cubism.history.CubismHistory.unavailable();
            }
        };
        final dev.turboism.sdk.cubism.CubismFacade proxy = new CubismEditorApiAvailabilityInterceptor(
                        version::get, capabilities::get)
                .wrapForTesting(delegate, dev.turboism.sdk.cubism.CubismFacade.class);
        // Declared 5.3.99 is not named by the facade's exact reviewed list, so
        // the call fails even though capability evidence is present.
        assertThrows(CubismEditorApiUnavailableException.class, proxy::history);

        // A compatibility-bound host declaring the reviewed 5.3.02 satisfies
        // the annotation but still requires the admitted capability evidence.
        version.set(Optional.of("5.3.02"));
        assertDoesNotThrow(proxy::history);
        capabilities.set(java.util.Set.of());
        assertThrows(CubismEditorApiUnavailableException.class, proxy::history);

        // And the version itself is still evaluated against the annotation:
        // a declared version not on the reviewed list is denied.
        capabilities.set(java.util.Set.of("cubism.editor-model.read"));
        version.set(Optional.of("5.4.00"));
        assertThrows(CubismEditorApiUnavailableException.class, proxy::history);
    }

    @Test
    void unreviewedDeclaredVersionNeverSatisfiesAnExactValueList() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.99"));
        final ExactOnlyService proxy = new CubismEditorApiAvailabilityInterceptor(
                        version::get, () -> java.util.Set.of("test.capability"))
                .wrapForTesting(new ExactOnlyServiceImpl(), ExactOnlyService.class);
        // The compatibility-bound host was admitted under a 5.3.02 contract but
        // declared 5.3.99: an API narrowed to the exact reviewed list stays off.
        final CubismEditorApiUnavailableException failure =
                assertThrows(CubismEditorApiUnavailableException.class, proxy::exact);
        assertEquals(Optional.of("5.3.99"), failure.activeVersion());
        version.set(Optional.of("5.3.02"));
        proxy.exact();
    }

    @Test
    void futureVersionHonorsExplicitExcludeAndUpperBound() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.9.99"));
        final var interceptor =
                new CubismEditorApiAvailabilityInterceptor(version::get, () -> java.util.Set.of("test.capability"));
        final FutureBoundService proxy =
                interceptor.wrapForTesting(new FutureBoundServiceImpl(), FutureBoundService.class);
        assertThrows(CubismEditorApiUnavailableException.class, proxy::excluded);
        assertThrows(CubismEditorApiUnavailableException.class, proxy::bounded);
        version.set(Optional.of("5.3.02"));
        proxy.excluded();
        proxy.bounded();
    }

    @Test
    void inheritedDeclarationsIntersectAgainstTheRealDeclaredVersion() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.99"));
        final ChildExample proxy = new CubismEditorApiAvailabilityInterceptor(version::get, java.util.Set::of)
                .wrapForTesting(new ChildExampleImpl(), ChildExample.class);
        // Method narrowed to exact 5.3.02 stays off for a 5.3.99 host even
        // though the inherited type declarations would allow it.
        assertThrows(CubismEditorApiUnavailableException.class, proxy::shared);
        assertThrows(CubismEditorApiUnavailableException.class, proxy::narrow);
        version.set(Optional.of("5.3.02"));
        proxy.shared();
        proxy.narrow();
    }

    @Test
    void serviceDirectoryRequireAndFindWrapServicesForAvailabilityEnforcement() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.01"));
        final CubismEditorApiAvailabilityInterceptor interceptor =
                new CubismEditorApiAvailabilityInterceptor(version::get);
        final ExactOnlyService service = new ExactOnlyServiceImpl();
        final PluginServiceDirectory directory = new PluginServiceDirectory() {
            @Override
            public java.util.Set<dev.turboism.sdk.plugin.PluginService> installed() {
                return java.util.Set.of();
            }

            @Override
            public <T> Optional<T> find(final Class<T> serviceType) {
                if (serviceType == ExactOnlyService.class) {
                    return Optional.of(serviceType.cast(service));
                }
                return Optional.empty();
            }
        };
        final PluginServiceDirectory guardedDirectory =
                interceptor.wrapForTesting(directory, PluginServiceDirectory.class);

        final ExactOnlyService viaRequire = guardedDirectory.require(ExactOnlyService.class);
        final ExactOnlyService viaFind =
                guardedDirectory.find(ExactOnlyService.class).orElseThrow();

        assertNotSame(service, viaRequire);
        assertNotSame(service, viaFind);
        assertSame(viaRequire, viaFind);

        // On 5.3.01, ExactOnlyService requires 5.3.02 so it throws
        assertThrows(CubismEditorApiUnavailableException.class, viaRequire::exact);
        assertThrows(CubismEditorApiUnavailableException.class, viaFind::exact);

        // On 5.3.02, it succeeds
        version.set(Optional.of("5.3.02"));
        assertDoesNotThrow(viaRequire::exact);
        assertDoesNotThrow(viaFind::exact);
    }

    @Test
    void primitiveArrayInputAndOutputPreserveDefensiveCopyAndHandleMultiDimensional() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.02"));
        final CubismEditorApiAvailabilityInterceptor interceptor =
                new CubismEditorApiAvailabilityInterceptor(version::get);
        final ArrayService service = new ArrayServiceImpl();
        final ArrayService proxy = interceptor.wrapForTesting(service, ArrayService.class);

        // 1D primitive array
        final byte[] inputBytes = new byte[] {1, 2, 3};
        final byte[] outputBytes = proxy.transferBytes(inputBytes);
        assertNotSame(inputBytes, outputBytes);
        assertNotSame(inputBytes, ((ArrayServiceImpl) service).receivedBytes);
        assertArrayEquals(new byte[] {1, 2, 3}, outputBytes);

        inputBytes[0] = 99;
        assertEquals(1, outputBytes[0]);
        assertEquals(1, ((ArrayServiceImpl) service).receivedBytes[0]);

        // 2D primitive array with null
        final byte[][] inputMatrix = new byte[][] {{1, 2}, null, {3, 4}};
        final byte[][] outputMatrix = proxy.transferMatrix(inputMatrix);
        assertNotSame(inputMatrix, outputMatrix);
        assertNotSame(inputMatrix, ((ArrayServiceImpl) service).receivedMatrix);
        assertNotSame(inputMatrix[0], outputMatrix[0]);
        assertNull(outputMatrix[1]);
        assertNotSame(inputMatrix[2], outputMatrix[2]);
        assertArrayEquals(new byte[] {1, 2}, outputMatrix[0]);
        assertArrayEquals(new byte[] {3, 4}, outputMatrix[2]);

        inputMatrix[0][0] = 88;
        assertEquals(1, outputMatrix[0][0]);
        assertEquals(1, ((ArrayServiceImpl) service).receivedMatrix[0][0]);
    }

    @Test
    void objectArrayInputUnwrapsAndOutputProxiesElements() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.01"));
        final CubismEditorApiAvailabilityInterceptor interceptor =
                new CubismEditorApiAvailabilityInterceptor(version::get);
        final ArrayService service = new ArrayServiceImpl();
        final ArrayService proxy = interceptor.wrapForTesting(service, ArrayService.class);

        final ExactOnlyService child1 = new ExactOnlyServiceImpl();
        final ExactOnlyService guardedChild1 = interceptor.wrapForTesting(child1, ExactOnlyService.class);
        final ExactOnlyService[] input = new ExactOnlyService[] {guardedChild1, null};

        final ExactOnlyService[] output = proxy.transferServices(input);
        assertNotSame(input, output);
        assertSame(child1, ((ArrayServiceImpl) service).receivedServices[0], "proxy should be unwrapped for delegate");
        assertNull(((ArrayServiceImpl) service).receivedServices[1]);

        assertTrue(Proxy.isProxyClass(output[0].getClass()));
        assertNull(output[1]);

        // On 5.3.01, ExactOnlyService is unavailable
        assertThrows(CubismEditorApiUnavailableException.class, output[0]::exact);

        // On 5.3.02, ExactOnlyService is permitted
        version.set(Optional.of("5.3.02"));
        assertDoesNotThrow(output[0]::exact);
    }

    @Test
    void sdkSnapshotContractsArePreservedWhenUnchanged() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.02"));
        final CubismEditorApiAvailabilityInterceptor interceptor =
                new CubismEditorApiAvailabilityInterceptor(version::get);

        final Set<EditorCommand> commandSnapshot = Set.copyOf(EnumSet.of(EditorCommand.SELECT_ALL));
        final CommandSnapshotCarrier commandProxy =
                interceptor.wrapForTesting(() -> commandSnapshot, CommandSnapshotCarrier.class);
        assertSame(commandSnapshot, commandProxy.available());

        final Map<Integer, String> markerSnapshot = Map.of(1, "start", 10, "end");
        final MarkerSnapshotCarrier markerProxy =
                interceptor.wrapForTesting(() -> markerSnapshot, MarkerSnapshotCarrier.class);
        assertSame(markerSnapshot, markerProxy.markers());
    }

    @CubismEditor
    interface CommandSnapshotCarrier {
        Set<EditorCommand> available();
    }

    @CubismEditor
    interface MarkerSnapshotCarrier {
        Map<Integer, String> markers();
    }

    @Test
    void nestedCarriersWrapRestrictedServicesAndEnforceAvailability() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.02"));
        final CubismEditorApiAvailabilityInterceptor interceptor =
                new CubismEditorApiAvailabilityInterceptor(version::get);
        final ExactOnlyService target = new ExactOnlyServiceImpl();
        final NestedCarrierCarrier service = new NestedCarrierCarrier() {
            @Override
            public Set<ExactOnlyService> servicesSet() {
                return Set.of(target);
            }

            @Override
            public Map<String, ExactOnlyService> servicesMap() {
                return Map.of("key", target);
            }

            @Override
            public Map<String, List<ExactOnlyService>> nestedMap() {
                return Map.of("group", List.of(target));
            }
        };
        final NestedCarrierCarrier proxy = interceptor.wrapForTesting(service, NestedCarrierCarrier.class);

        final Set<ExactOnlyService> set = proxy.servicesSet();
        final Map<String, ExactOnlyService> map = proxy.servicesMap();
        final Map<String, List<ExactOnlyService>> nested = proxy.nestedMap();

        final ExactOnlyService fromSet = set.iterator().next();
        final ExactOnlyService fromMap = map.get("key");
        final ExactOnlyService fromNested = nested.get("group").get(0);

        assertTrue(Proxy.isProxyClass(fromSet.getClass()));
        assertTrue(Proxy.isProxyClass(fromMap.getClass()));
        assertTrue(Proxy.isProxyClass(fromNested.getClass()));

        assertDoesNotThrow(fromSet::exact);
        assertDoesNotThrow(fromMap::exact);
        assertDoesNotThrow(fromNested::exact);

        version.set(Optional.of("5.2.03"));
        assertThrows(CubismEditorApiUnavailableException.class, fromSet::exact);
        assertThrows(CubismEditorApiUnavailableException.class, fromMap::exact);
        assertThrows(CubismEditorApiUnavailableException.class, fromNested::exact);

        version.set(Optional.empty());
        assertThrows(CubismEditorApiUnavailableException.class, fromSet::exact);
        assertThrows(CubismEditorApiUnavailableException.class, fromMap::exact);
        assertThrows(CubismEditorApiUnavailableException.class, fromNested::exact);
    }

    @CubismEditor
    interface NestedCarrierCarrier {
        Set<ExactOnlyService> servicesSet();

        Map<String, ExactOnlyService> servicesMap();

        Map<String, List<ExactOnlyService>> nestedMap();
    }

    @Test
    void completionStageWrapsAsyncResultAndEnforcesAvailability() throws Exception {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.02"));
        final CubismEditorApiAvailabilityInterceptor interceptor =
                new CubismEditorApiAvailabilityInterceptor(version::get);
        final CompletableFuture<ExactOnlyService> future = new CompletableFuture<>();
        final StageCarrier proxy = interceptor.wrapForTesting(() -> future, StageCarrier.class);
        final CompletionStage<ExactOnlyService> stage = proxy.stage();

        final ExactOnlyService target = new ExactOnlyServiceImpl();
        final AtomicReference<ExactOnlyService> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        stage.thenAccept(svc -> {
            result.set(svc);
            latch.countDown();
        });

        future.complete(target);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        final ExactOnlyService wrapped = result.get();
        assertNotNull(wrapped);
        assertNotSame(target, wrapped);
        assertTrue(Proxy.isProxyClass(wrapped.getClass()));

        assertDoesNotThrow(wrapped::exact);

        version.set(Optional.of("5.2.03"));
        assertThrows(CubismEditorApiUnavailableException.class, wrapped::exact);
    }

    @CubismEditor
    interface StageCarrier {
        CompletionStage<ExactOnlyService> stage();
    }

    @Test
    void unwrapsAllCarrierArgumentsBeforeDelegateInvocation() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.02"));
        final CubismEditorApiAvailabilityInterceptor interceptor =
                new CubismEditorApiAvailabilityInterceptor(version::get);
        final CarrierReceiverImpl delegate = new CarrierReceiverImpl();
        final CarrierReceiver proxy = interceptor.wrapForTesting(delegate, CarrierReceiver.class);

        final ExactOnlyService raw = new ExactOnlyServiceImpl();
        final ExactOnlyService wrapped = interceptor.wrapForTesting(raw, ExactOnlyService.class);

        proxy.acceptCarriers(Optional.of(wrapped), List.of(wrapped), Set.of(wrapped), Map.of("k", wrapped));

        assertSame(raw, delegate.receivedOpt.orElseThrow(), "proxy in Optional must unwrap to delegate");
        assertSame(raw, delegate.receivedList.get(0), "proxy in List must unwrap to delegate");
        assertSame(raw, delegate.receivedSet.iterator().next(), "proxy in Set must unwrap to delegate");
        assertSame(raw, delegate.receivedMap.get("k"), "proxy in Map must unwrap to delegate");
    }

    @CubismEditor
    interface CarrierReceiver {
        void acceptCarriers(
                Optional<ExactOnlyService> opt,
                List<ExactOnlyService> list,
                Set<ExactOnlyService> set,
                Map<String, ExactOnlyService> map);
    }

    static final class CarrierReceiverImpl implements CarrierReceiver {
        Optional<ExactOnlyService> receivedOpt;
        List<ExactOnlyService> receivedList;
        Set<ExactOnlyService> receivedSet;
        Map<String, ExactOnlyService> receivedMap;

        @Override
        public void acceptCarriers(
                final Optional<ExactOnlyService> opt,
                final List<ExactOnlyService> list,
                final Set<ExactOnlyService> set,
                final Map<String, ExactOnlyService> map) {
            this.receivedOpt = opt;
            this.receivedList = list;
            this.receivedSet = set;
            this.receivedMap = map;
        }
    }

    @Test
    void unchangedReferenceArrayPreservesDefensiveCopy() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.02"));
        final CubismEditorApiAvailabilityInterceptor interceptor =
                new CubismEditorApiAvailabilityInterceptor(version::get);
        final StringArrayService service = new StringArrayServiceImpl();
        final StringArrayService proxy = interceptor.wrapForTesting(service, StringArrayService.class);

        final String[] input = new String[] {"x", "y"};
        final String[] output = proxy.transferStrings(input);

        assertNotSame(input, output);
        assertNotSame(input, ((StringArrayServiceImpl) service).received);
        assertArrayEquals(new String[] {"x", "y"}, output);

        input[0] = "mutated";
        assertEquals("x", output[0]);
        assertEquals("x", ((StringArrayServiceImpl) service).received[0]);
    }

    @CubismEditor
    interface StringArrayService {
        String[] transferStrings(String[] input);
    }

    static final class StringArrayServiceImpl implements StringArrayService {
        String[] received;

        @Override
        public String[] transferStrings(final String[] input) {
            this.received = input;
            return input;
        }
    }

    @Test
    void changedCollectionsPreserveOrderNullsAndAreUnmodifiable() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.02"));
        final CubismEditorApiAvailabilityInterceptor interceptor =
                new CubismEditorApiAvailabilityInterceptor(version::get);
        final ExactOnlyService child1 = new ExactOnlyServiceImpl();
        final ExactOnlyService child2 = new ExactOnlyServiceImpl();
        final MixedCarrierService service = new MixedCarrierService() {
            @Override
            public List<ExactOnlyService> mixedList() {
                return Arrays.asList(child1, null, child2);
            }

            @Override
            public Set<ExactOnlyService> mixedSet() {
                final LinkedHashSet<ExactOnlyService> set = new LinkedHashSet<>();
                set.add(child1);
                set.add(null);
                set.add(child2);
                return set;
            }

            @Override
            public Map<String, ExactOnlyService> mixedMap() {
                final LinkedHashMap<String, ExactOnlyService> map = new LinkedHashMap<>();
                map.put("first", child1);
                map.put(null, null);
                map.put("third", child2);
                return map;
            }
        };
        final MixedCarrierService proxy = interceptor.wrapForTesting(service, MixedCarrierService.class);

        final List<ExactOnlyService> list = proxy.mixedList();
        assertEquals(3, list.size());
        assertTrue(Proxy.isProxyClass(list.get(0).getClass()));
        assertNull(list.get(1));
        assertTrue(Proxy.isProxyClass(list.get(2).getClass()));
        assertThrows(UnsupportedOperationException.class, () -> list.add(child1));

        final Set<ExactOnlyService> set = proxy.mixedSet();
        assertEquals(3, set.size());
        final List<ExactOnlyService> setList = new ArrayList<>(set);
        assertTrue(Proxy.isProxyClass(setList.get(0).getClass()));
        assertNull(setList.get(1));
        assertTrue(Proxy.isProxyClass(setList.get(2).getClass()));
        assertThrows(UnsupportedOperationException.class, () -> set.add(child1));

        final Map<String, ExactOnlyService> map = proxy.mixedMap();
        assertEquals(3, map.size());
        final List<String> mapKeys = new ArrayList<>(map.keySet());
        assertEquals("first", mapKeys.get(0));
        assertNull(mapKeys.get(1));
        assertEquals("third", mapKeys.get(2));
        assertTrue(Proxy.isProxyClass(map.get("first").getClass()));
        assertNull(map.get(null));
        assertTrue(Proxy.isProxyClass(map.get("third").getClass()));
        assertThrows(UnsupportedOperationException.class, () -> map.put("new", child1));
    }

    @Test
    void nonEmptyPrefixPreservesIdentityBeforeLaterElementChanges() {
        final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.of("5.3.02"));
        final CubismEditorApiAvailabilityInterceptor interceptor =
                new CubismEditorApiAvailabilityInterceptor(version::get);
        final ExactOnlyService raw0 = new ExactOnlyServiceImpl();
        final ExactOnlyService proxy0 = interceptor.wrapForTesting(raw0, ExactOnlyService.class);
        final ExactOnlyService raw1 = new ExactOnlyServiceImpl();

        final MixedCarrierService service = new MixedCarrierService() {
            @Override
            public List<ExactOnlyService> mixedList() {
                return Arrays.asList(proxy0, raw1);
            }

            @Override
            public Set<ExactOnlyService> mixedSet() {
                final LinkedHashSet<ExactOnlyService> set = new LinkedHashSet<>();
                set.add(proxy0);
                set.add(raw1);
                return set;
            }

            @Override
            public Map<String, ExactOnlyService> mixedMap() {
                final LinkedHashMap<String, ExactOnlyService> map = new LinkedHashMap<>();
                map.put("p0", proxy0);
                map.put("p1", raw1);
                return map;
            }
        };
        final MixedCarrierService proxy = interceptor.wrapForTesting(service, MixedCarrierService.class);

        final List<ExactOnlyService> list = proxy.mixedList();
        assertEquals(2, list.size());
        assertSame(proxy0, list.get(0), "prefix element already proxied must preserve identity");
        assertNotSame(raw1, list.get(1));
        assertTrue(Proxy.isProxyClass(list.get(1).getClass()));

        final Set<ExactOnlyService> set = proxy.mixedSet();
        assertEquals(2, set.size());
        final List<ExactOnlyService> setAsList = new ArrayList<>(set);
        assertSame(proxy0, setAsList.get(0));
        assertNotSame(raw1, setAsList.get(1));

        final Map<String, ExactOnlyService> map = proxy.mixedMap();
        assertEquals(2, map.size());
        assertSame(proxy0, map.get("p0"));
        assertNotSame(raw1, map.get("p1"));
    }

    @CubismEditor
    interface MixedCarrierService {
        List<ExactOnlyService> mixedList();

        Set<ExactOnlyService> mixedSet();

        Map<String, ExactOnlyService> mixedMap();
    }

    @CubismEditor
    interface ArrayService {
        byte[] transferBytes(byte[] input);

        byte[][] transferMatrix(byte[][] input);

        ExactOnlyService[] transferServices(ExactOnlyService[] input);
    }

    static final class ArrayServiceImpl implements ArrayService {
        byte[] receivedBytes;
        byte[][] receivedMatrix;
        ExactOnlyService[] receivedServices;

        @Override
        public byte[] transferBytes(final byte[] input) {
            this.receivedBytes = input;
            return input;
        }

        @Override
        public byte[][] transferMatrix(final byte[][] input) {
            this.receivedMatrix = input;
            return input;
        }

        @Override
        public ExactOnlyService[] transferServices(final ExactOnlyService[] input) {
            this.receivedServices = input;
            return input;
        }
    }

    @CubismEditor("5.3.02")
    interface ExactOnlyService {
        void exact();
    }

    static final class ExactOnlyServiceImpl implements ExactOnlyService {
        @Override
        public void exact() {}
    }

    interface FutureBoundService {
        @CubismEditor(from = "5.2.03", exclude = "5.9.99")
        void excluded();

        @CubismEditor(from = "5.2.03", to = "5.3.03")
        void bounded();
    }

    static final class FutureBoundServiceImpl implements FutureBoundService {
        @Override
        public void excluded() {}

        @Override
        public void bounded() {}
    }

    interface InvalidExample {
        @CubismEditor(value = "5.3.02", from = "5.2.03")
        void mixed();

        @CubismEditor(from = "5.3.02", to = "5.2.03")
        void reverse();
    }

    static final class InvalidExampleImpl implements InvalidExample {
        @Override
        public void mixed() {}

        @Override
        public void reverse() {}
    }
}
