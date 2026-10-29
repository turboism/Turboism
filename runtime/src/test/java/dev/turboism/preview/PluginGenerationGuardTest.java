package dev.turboism.preview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.GuardedServiceFixture;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.PluginService;
import dev.turboism.sdk.plugin.PluginServiceDirectory;
import dev.turboism.sdk.plugin.PluginServiceUnavailableException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PluginGenerationGuardTest {

    @Test
    void baseDeformerCollectionsPreserveClosedSubtypesAndKeepTheirGuards() {
        var guard = new PluginGenerationGuard("p");
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
        guard.fence();
        assertThrows(
                IllegalStateException.class, () -> ((dev.turboism.sdk.cubism.model.WarpDeformer) values.get(0)).grid());
        assertThrows(IllegalStateException.class, values.get(1)::id);
    }

    @Test
    void fencedContextDeniesNewAdmissionButKeepsDiagnostics() {
        final AtomicReference<String> logSink = new AtomicReference<>();
        final PluginContext delegate = stubContext(new DisposableScope(), new SinkLogger(logSink));
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginContext guarded = guard.wrap(delegate);

        assertNotNull(guarded.paths());
        guard.fence();

        assertThrows(IllegalStateException.class, guarded::paths);
        assertThrows(IllegalStateException.class, guarded::disposableScope);
        assertThrows(IllegalStateException.class, guarded::storage);
        // Diagnostics remain available while fenced so cleanup can still log and describe.
        assertSame(delegate.descriptor(), guarded.descriptor());
        assertNotNull(guarded.logger());
        guarded.logger().info("cleanup still logs");
        assertEquals("cleanup still logs", logSink.get());
    }

    @Test
    void fencePropagatesToHandlesAcquiredBeforeFence() {
        final FixtureService service = new FixtureService();
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);
        final GuardedServiceFixture child = guarded.child();

        assertEquals("m:a", guarded.mutate("a"));
        assertEquals("m:c", child.mutate("c"));

        guard.fence();

        assertThrows(IllegalStateException.class, () -> guarded.mutate("b"));
        assertThrows(
                IllegalStateException.class,
                () -> child.mutate("d"),
                "handles acquired before fencing must deny new admission too");
        // Terminal release still works so teardown can detach handles.
        guarded.close();
        child.close();
        assertEquals(2, service.closes.get());
    }

    @Test
    void terminalCloseInsideScopeRunsAfterFence() throws Exception {
        // The real sequence: acquire a guarded registration, hand it to the plugin scope, fence
        // the generation, then close the scope — the proxy's close() must reach the delegate so
        // resources actually detach (atlas-style blocking closers included).
        final FixtureService registration = new FixtureService();
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final GuardedServiceFixture guarded = guard.wrapForTesting(registration, GuardedServiceFixture.class);

        final DisposableScope scope = new DisposableScope();
        scope.register(guarded::close);
        guard.fence();
        scope.seal();
        scope.close();

        assertEquals(1, registration.closes.get(), "scope teardown must reach the guarded registration's close()");
    }

    @Test
    void terminalBypassIsExactVerbAndZeroArgumentOnly() {
        // The documented surface: a zero-arg terminal verb reaches the delegate after
        // fencing; the same verb with an argument and ordinary work stay fenced.
        final FixtureService service = new FixtureService();
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);
        guard.fence();

        guarded.close();
        assertEquals(1, service.closes.get());
        assertThrows(IllegalStateException.class, () -> guarded.close("reason"));
        assertThrows(IllegalStateException.class, () -> guarded.mutate("x"));
    }

    @Test
    void inFlightAdmittedCallsKeepDrainedFalse() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final FixtureService service = new FixtureService() {
            @Override
            public String mutate(final String value) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            }
        };
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        final AtomicReference<String> outcome = new AtomicReference<>();
        final Thread caller = new Thread(() -> outcome.set(guarded.mutate("x")));
        caller.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        guard.fence();
        assertFalse(guard.drained(), "an admitted call still executing must block disposal");
        release.countDown();
        caller.join(5_000);
        assertTrue(guard.drained());
        assertEquals("done", outcome.get());
    }

    @Test
    void repeatedAccessReturnsSameWrappedHandle() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final FixtureService service = new FixtureService();
        final GuardedServiceFixture first = guard.wrapForTesting(service, GuardedServiceFixture.class);
        final GuardedServiceFixture second = guard.wrapForTesting(service, GuardedServiceFixture.class);
        assertSame(first, second);
        assertTrue(Proxy.isProxyClass(first.getClass()));
    }

    @Test
    void serviceDirectoryRequireAndFindReturnGuardedProxiesAndEnforceFence() {
        final FixtureService service = new FixtureService();
        final PluginServiceDirectory directory = createDirectory(service);
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginServiceDirectory guardedDirectory = guard.wrapForTesting(directory, PluginServiceDirectory.class);

        final GuardedServiceFixture viaRequire = guardedDirectory.require(GuardedServiceFixture.class);
        final GuardedServiceFixture viaFind =
                guardedDirectory.find(GuardedServiceFixture.class).orElseThrow();

        assertNotSame(service, viaRequire);
        assertNotSame(service, viaFind);
        assertSame(viaRequire, viaFind);
        assertTrue(Proxy.isProxyClass(viaRequire.getClass()));

        assertEquals("m:req", viaRequire.mutate("req"));
        assertEquals("m:find", viaFind.mutate("find"));

        guard.fence();

        assertThrows(IllegalStateException.class, () -> viaRequire.mutate("post"));
        assertThrows(IllegalStateException.class, () -> viaFind.mutate("post"));
    }

    @Test
    void inFlightCallsOnDirectoryServiceKeepDrainedFalse() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final FixtureService service = new FixtureService() {
            @Override
            public String mutate(final String value) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return "done:" + value;
            }
        };
        final PluginServiceDirectory directory = createDirectory(service);
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginServiceDirectory guardedDirectory = guard.wrapForTesting(directory, PluginServiceDirectory.class);
        final GuardedServiceFixture guarded = guardedDirectory.require(GuardedServiceFixture.class);

        final AtomicReference<String> outcome = new AtomicReference<>();
        final Thread caller = new Thread(() -> outcome.set(guarded.mutate("inFlight")));
        caller.start();

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            guard.fence();
            assertFalse(guard.drained(), "running call on directory-discovered service must keep guard non-drained");
            assertEquals(1, guard.inFlightCalls());
        } finally {
            release.countDown();
            caller.join(5_000);
        }
        assertFalse(caller.isAlive());
        assertTrue(guard.drained());
        assertEquals(0, guard.inFlightCalls());
        assertEquals("done:inFlight", outcome.get());
    }

    @Test
    void terminalCloseOnDirectoryServiceSucceedsAfterFence() {
        final FixtureService service = new FixtureService();
        final PluginServiceDirectory directory = createDirectory(service);
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginServiceDirectory guardedDirectory = guard.wrapForTesting(directory, PluginServiceDirectory.class);
        final GuardedServiceFixture guarded = guardedDirectory.require(GuardedServiceFixture.class);

        guard.fence();

        guarded.close();
        assertEquals(1, service.closes.get());
        assertTrue(guard.drained());
        assertEquals(0, guard.inFlightCalls());

        assertThrows(IllegalStateException.class, () -> guarded.mutate("denied"));
        assertThrows(IllegalStateException.class, () -> guarded.close("with-arg"));
    }

    @Test
    void nestedSdkHandlesFromDirectoryServiceAreGuarded() {
        final FixtureService service = new FixtureService();
        final PluginServiceDirectory directory = createDirectory(service);
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginServiceDirectory guardedDirectory = guard.wrapForTesting(directory, PluginServiceDirectory.class);
        final GuardedServiceFixture parent = guardedDirectory.require(GuardedServiceFixture.class);
        final GuardedServiceFixture child = parent.child();

        assertEquals("m:child", child.mutate("child"));
        guard.fence();

        assertThrows(IllegalStateException.class, () -> child.mutate("denied"));
        child.close();
        assertEquals(1, service.closes.get());
    }

    @Test
    void serviceDirectoryThrowsStructuredUnavailableExceptionWhenMissing() {
        final FixtureService service = new FixtureService();
        final PluginServiceDirectory directory = createDirectory(service);
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginServiceDirectory guardedDirectory = guard.wrapForTesting(directory, PluginServiceDirectory.class);

        assertTrue(guardedDirectory.find(PluginPaths.class).isEmpty());
        assertThrows(PluginServiceUnavailableException.class, () -> guardedDirectory.require(PluginPaths.class));
    }

    @Test
    void primitiveArrayInputAndOutputPreserveDefensiveCopy() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final ArrayFixtureService service = new ArrayFixtureService();
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        final byte[] input = new byte[] {1, 2, 3};
        final byte[] output = guarded.transferBytes(input);

        assertNotSame(input, output);
        assertNotSame(input, service.receivedBytes);
        assertArrayEquals(new byte[] {1, 2, 3}, output);

        input[0] = 99;
        assertEquals(1, output[0]);
        assertEquals(1, service.receivedBytes[0]);

        output[1] = 88;
        assertEquals(2, service.receivedBytes[1]);
    }

    @Test
    void multiDimensionalPrimitiveArrayPreservesDeepDefensiveCopyAndHandlesNulls() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final ArrayFixtureService service = new ArrayFixtureService();
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        final byte[][] matrix = new byte[][] {{1, 2}, null, {3, 4, 5}};
        final byte[][] output = guarded.transferMatrix(matrix);

        assertNotSame(matrix, output);
        assertNotSame(matrix, service.receivedMatrix);
        assertNotSame(matrix[0], output[0]);
        assertNull(output[1]);
        assertNotSame(matrix[2], output[2]);

        assertArrayEquals(new byte[] {1, 2}, output[0]);
        assertArrayEquals(new byte[] {3, 4, 5}, output[2]);

        matrix[0][0] = 77;
        assertEquals(1, output[0][0]);
        assertEquals(1, service.receivedMatrix[0][0]);
    }

    @Test
    void objectHandleArrayInputUnwrapsAndOutputProxiesElements() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final ArrayFixtureService service = new ArrayFixtureService();
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        final FixtureService child1 = new FixtureService();
        final GuardedServiceFixture guardedChild1 = guard.wrapForTesting(child1, GuardedServiceFixture.class);
        final GuardedServiceFixture[] array = new GuardedServiceFixture[] {guardedChild1, null};

        final GuardedServiceFixture[] output = guarded.transferArray(array);

        assertNotSame(array, output);
        assertSame(child1, service.receivedArray[0], "proxy should be unwrapped to delegate before reaching service");
        assertNull(service.receivedArray[1]);

        assertNotNull(output[0]);
        assertNull(output[1]);
        assertTrue(Proxy.isProxyClass(output[0].getClass()));
        assertEquals("m:nested", output[0].mutate("nested"));

        guard.fence();
        assertThrows(IllegalStateException.class, () -> output[0].mutate("post"));
        output[0].close();
        assertEquals(1, child1.closes.get());
    }

    @Test
    void unchangedCarriersPreserveIdentityAndSkipReconstruction() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final FixtureService service = new FixtureService();
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        assertSame(service.unchangedSet(), guarded.unchangedSet());
        assertSame(service.unchangedMap(), guarded.unchangedMap());
        assertSame(service.unchangedOptional(), guarded.unchangedOptional());
        assertSame(service.unchangedList(), guarded.unchangedList());
    }

    @Test
    void nestedCarriersWrapSdkInterfacesAndEnforceFence() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final FixtureService service = new FixtureService();
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        final Optional<GuardedServiceFixture> optChild = guarded.optionalChild();
        final List<GuardedServiceFixture> listChildren = guarded.listChildren();
        final Set<GuardedServiceFixture> setChildren = guarded.setChildren();
        final Map<String, GuardedServiceFixture> mapChildren = guarded.mapChildren();
        final Map<String, List<GuardedServiceFixture>> nestedChildren = guarded.nestedCarrierChildren();

        assertTrue(optChild.isPresent());
        assertTrue(Proxy.isProxyClass(optChild.get().getClass()));
        assertEquals("m:opt", optChild.get().mutate("opt"));

        assertEquals(1, listChildren.size());
        assertTrue(Proxy.isProxyClass(listChildren.get(0).getClass()));
        assertEquals("m:list", listChildren.get(0).mutate("list"));

        assertEquals(1, setChildren.size());
        final GuardedServiceFixture setElem = setChildren.iterator().next();
        assertTrue(Proxy.isProxyClass(setElem.getClass()));
        assertEquals("m:set", setElem.mutate("set"));

        assertEquals(1, mapChildren.size());
        final GuardedServiceFixture mapElem = mapChildren.get("key");
        assertTrue(Proxy.isProxyClass(mapElem.getClass()));
        assertEquals("m:map", mapElem.mutate("map"));

        assertEquals(1, nestedChildren.size());
        final GuardedServiceFixture nestedElem = nestedChildren.get("group").get(0);
        assertTrue(Proxy.isProxyClass(nestedElem.getClass()));
        assertEquals("m:nested", nestedElem.mutate("nested"));

        guard.fence();

        assertThrows(IllegalStateException.class, () -> optChild.get().mutate("denied"));
        assertThrows(IllegalStateException.class, () -> listChildren.get(0).mutate("denied"));
        assertThrows(IllegalStateException.class, () -> setElem.mutate("denied"));
        assertThrows(IllegalStateException.class, () -> mapElem.mutate("denied"));
        assertThrows(IllegalStateException.class, () -> nestedElem.mutate("denied"));

        optChild.get().close();
        listChildren.get(0).close();
        setElem.close();
        mapElem.close();
        nestedElem.close();
        assertEquals(5, service.closes.get());
    }

    @Test
    void completionStageWrapsAsyncResultAndEnforcesFence() throws Exception {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final CompletableFuture<GuardedServiceFixture> future = new CompletableFuture<>();
        final FixtureService service = new FixtureService() {
            @Override
            public java.util.concurrent.CompletionStage<GuardedServiceFixture> stageChild() {
                return future;
            }
        };
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);
        final java.util.concurrent.CompletionStage<GuardedServiceFixture> stage = guarded.stageChild();

        final FixtureService asyncDelegate = new FixtureService();
        final AtomicReference<GuardedServiceFixture> asyncResult = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        stage.thenAccept(result -> {
            asyncResult.set(result);
            latch.countDown();
        });

        future.complete(asyncDelegate);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        final GuardedServiceFixture resolved = asyncResult.get();
        assertNotNull(resolved);
        assertNotSame(asyncDelegate, resolved);
        assertTrue(Proxy.isProxyClass(resolved.getClass()));
        assertEquals("m:async", resolved.mutate("async"));

        guard.fence();
        assertThrows(IllegalStateException.class, () -> resolved.mutate("blocked"));
        resolved.close();
        assertEquals(1, asyncDelegate.closes.get());
    }

    @Test
    void unwrapsCarrierArgumentsBeforeDelegateInvocation() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final CarrierRecordingFixture service = new CarrierRecordingFixture();
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        final FixtureService child = new FixtureService();
        final GuardedServiceFixture guardedChild = guard.wrapForTesting(child, GuardedServiceFixture.class);

        guarded.acceptOptional(Optional.of(guardedChild));
        assertSame(child, service.receivedOptional.orElseThrow(), "proxy in Optional must unwrap to delegate");

        guarded.acceptList(List.of(guardedChild));
        assertSame(child, service.receivedList.get(0), "proxy in List must unwrap to delegate");

        guarded.acceptSet(Set.of(guardedChild));
        assertSame(child, service.receivedSet.iterator().next(), "proxy in Set must unwrap to delegate");

        guarded.acceptMap(Map.of("key", guardedChild));
        assertSame(child, service.receivedMap.get("key"), "proxy in Map must unwrap to delegate");
    }

    @Test
    void changedCollectionsPreserveIterationOrderNullsAndAreUnmodifiable() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final FixtureService child1 = new FixtureService();
        final FixtureService child2 = new FixtureService();
        final CarrierCollectionFixtureImpl service = new CarrierCollectionFixtureImpl(child1, child2);
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        // List with null and order
        final List<GuardedServiceFixture> list = guarded.mixedList();
        assertEquals(3, list.size());
        assertTrue(Proxy.isProxyClass(list.get(0).getClass()));
        assertNull(list.get(1));
        assertTrue(Proxy.isProxyClass(list.get(2).getClass()));
        assertEquals("m:1", list.get(0).mutate("1"));
        assertEquals("m:2", list.get(2).mutate("2"));
        assertThrows(UnsupportedOperationException.class, () -> list.add(child1));

        // Set with null and order
        final Set<GuardedServiceFixture> set = guarded.mixedSet();
        assertEquals(3, set.size());
        final List<GuardedServiceFixture> setAsList = new ArrayList<>(set);
        assertTrue(Proxy.isProxyClass(setAsList.get(0).getClass()));
        assertNull(setAsList.get(1));
        assertTrue(Proxy.isProxyClass(setAsList.get(2).getClass()));
        assertThrows(UnsupportedOperationException.class, () -> set.add(child1));

        // Map with null key and null value and order
        final Map<String, GuardedServiceFixture> map = guarded.mixedMap();
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

    private static class CarrierCollectionFixtureImpl extends FixtureService {
        private final GuardedServiceFixture child1;
        private final GuardedServiceFixture child2;

        CarrierCollectionFixtureImpl(final GuardedServiceFixture child1, final GuardedServiceFixture child2) {
            this.child1 = child1;
            this.child2 = child2;
        }

        @Override
        public List<GuardedServiceFixture> mixedList() {
            return Arrays.asList(child1, null, child2);
        }

        @Override
        public Set<GuardedServiceFixture> mixedSet() {
            final LinkedHashSet<GuardedServiceFixture> set = new LinkedHashSet<>();
            set.add(child1);
            set.add(null);
            set.add(child2);
            return set;
        }

        @Override
        public Map<String, GuardedServiceFixture> mixedMap() {
            final LinkedHashMap<String, GuardedServiceFixture> map = new LinkedHashMap<>();
            map.put("first", child1);
            map.put(null, null);
            map.put("third", child2);
            return map;
        }
    }

    @Test
    void nonEmptyPrefixPreservesIdentityBeforeLaterElementChanges() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final FixtureService child0 = new FixtureService();
        final FixtureService child1 = new FixtureService();
        final GuardedServiceFixture proxy0 = guard.wrapForTesting(child0, GuardedServiceFixture.class);

        final GuardedServiceFixture service = new FixtureService() {
            @Override
            public List<GuardedServiceFixture> mixedList() {
                return Arrays.asList(proxy0, child1);
            }

            @Override
            public Set<GuardedServiceFixture> mixedSet() {
                final LinkedHashSet<GuardedServiceFixture> set = new LinkedHashSet<>();
                set.add(proxy0);
                set.add(child1);
                return set;
            }

            @Override
            public Map<String, GuardedServiceFixture> mixedMap() {
                final LinkedHashMap<String, GuardedServiceFixture> map = new LinkedHashMap<>();
                map.put("p0", proxy0);
                map.put("p1", child1);
                return map;
            }
        };
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        // List: index 0 was already guarded (unchanged), index 1 was raw child1 (wrapped)
        final List<GuardedServiceFixture> list = guarded.mixedList();
        assertEquals(2, list.size());
        assertSame(proxy0, list.get(0), "prefix element already guarded must preserve identity");
        assertNotSame(child1, list.get(1));
        assertTrue(Proxy.isProxyClass(list.get(1).getClass()));

        // Set: prefix element already guarded preserves identity
        final Set<GuardedServiceFixture> set = guarded.mixedSet();
        assertEquals(2, set.size());
        final List<GuardedServiceFixture> setAsList = new ArrayList<>(set);
        assertSame(proxy0, setAsList.get(0));
        assertNotSame(child1, setAsList.get(1));

        // Map: prefix entry already guarded preserves identity
        final Map<String, GuardedServiceFixture> map = guarded.mixedMap();
        assertEquals(2, map.size());
        assertSame(proxy0, map.get("p0"));
        assertNotSame(child1, map.get("p1"));
    }

    private static PluginServiceDirectory createDirectory(final GuardedServiceFixture service) {
        return new PluginServiceDirectory() {
            @Override
            public Set<PluginService> installed() {
                return Set.of();
            }

            @Override
            public <T> Optional<T> find(final Class<T> serviceType) {
                if (serviceType == GuardedServiceFixture.class) {
                    return Optional.of(serviceType.cast(service));
                }
                return Optional.empty();
            }
        };
    }

    private static class ArrayFixtureService implements GuardedServiceFixture {
        byte[] receivedBytes;
        byte[][] receivedMatrix;
        GuardedServiceFixture[] receivedArray;

        @Override
        public String mutate(final String value) {
            return value;
        }

        @Override
        public GuardedServiceFixture child() {
            return this;
        }

        @Override
        public void close() {}

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
        public GuardedServiceFixture[] transferArray(final GuardedServiceFixture[] input) {
            this.receivedArray = input;
            return input;
        }
    }

    private static PluginContext stubContext(final DisposableScope scope, final PluginLogger logger) {
        return (PluginContext) Proxy.newProxyInstance(
                PluginGenerationGuardTest.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "descriptor" -> null;
                    case "logger" -> logger;
                    case "disposableScope" -> scope;
                    case "paths" ->
                        (dev.turboism.sdk.plugin.PluginPaths) Proxy.newProxyInstance(
                                PluginGenerationGuardTest.class.getClassLoader(),
                                new Class<?>[] {dev.turboism.sdk.plugin.PluginPaths.class},
                                (p, m, a) -> switch (m.getName()) {
                                    case "dataDir" -> Path.of("data");
                                    case "stateDir" -> Path.of("state");
                                    case "toString" -> "paths";
                                    default -> null;
                                });
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static class FixtureService implements GuardedServiceFixture {
        final java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        final Set<String> constantSet = Set.of("a", "b");
        final Map<Integer, String> constantMap = Map.of(1, "marker1", 2, "marker2");
        final Optional<String> constantOptional = Optional.of("constant");
        final List<String> constantList = List.of("alpha", "beta");

        @Override
        public String mutate(final String value) {
            return "m:" + value;
        }

        @Override
        public GuardedServiceFixture child() {
            return this;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }

        @Override
        public Set<String> unchangedSet() {
            return constantSet;
        }

        @Override
        public Map<Integer, String> unchangedMap() {
            return constantMap;
        }

        @Override
        public Optional<String> unchangedOptional() {
            return constantOptional;
        }

        @Override
        public List<String> unchangedList() {
            return constantList;
        }
    }

    private static class CarrierRecordingFixture extends FixtureService {
        Optional<GuardedServiceFixture> receivedOptional;
        List<GuardedServiceFixture> receivedList;
        Set<GuardedServiceFixture> receivedSet;
        Map<String, GuardedServiceFixture> receivedMap;

        @Override
        public void acceptOptional(final Optional<GuardedServiceFixture> optional) {
            this.receivedOptional = optional;
        }

        @Override
        public void acceptList(final List<GuardedServiceFixture> list) {
            this.receivedList = list;
        }

        @Override
        public void acceptSet(final Set<GuardedServiceFixture> set) {
            this.receivedSet = set;
        }

        @Override
        public void acceptMap(final Map<String, GuardedServiceFixture> map) {
            this.receivedMap = map;
        }
    }

    private static final class SinkLogger implements PluginLogger {
        private final AtomicReference<String> sink;

        private SinkLogger(final AtomicReference<String> sink) {
            this.sink = sink;
        }

        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {
            sink.set(message);
        }

        @Override
        public void warn(final String message) {}

        @Override
        public void error(final String message) {}

        @Override
        public void error(final String message, final Throwable throwable) {}
    }
}
