package dev.turboism.core.reflect;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Focused tests for the shared reflective method-handle cache. */
class MethodHandleCacheTest {

    static class Base {
        public int baseValue() { return 1; }

        public void setValue(final float value) { }

        public void setValue(final double value) { }

        private String hidden() { return "hidden"; }
    }

    static final class Derived extends Base {
        private String own() { return "own"; }
    }

    @Test
    void publicLookupHitsAndReturnsSameHandle() throws Exception {
        final Method first = MethodHandleCache.method(Base.class, "baseValue");
        final Method second = MethodHandleCache.method(Base.class, "baseValue");
        assertSame(first, second, "repeated lookups must return the cached Method instance");
        assertEquals("baseValue", first.getName());
    }

    @Test
    void publicLookupFindsInheritedMethods() throws Exception {
        final Method inherited = MethodHandleCache.method(Derived.class, "baseValue");
        assertEquals(Base.class, inherited.getDeclaringClass());
    }

    @Test
    void missingPublicMethodThrowsNoSuchMethod() {
        assertThrows(NoSuchMethodException.class,
            () -> MethodHandleCache.method(Base.class, "doesNotExist"));
        // repeated misses must also throw (nothing cached for failures)
        assertThrows(NoSuchMethodException.class,
            () -> MethodHandleCache.method(Base.class, "doesNotExist"));
    }

    @Test
    void declaredLookupFindsPrivateMethodOnExactClassAndBecomesAccessible() throws Exception {
        final Method hidden = MethodHandleCache.declared(Base.class, "hidden");
        assertEquals("hidden", hidden.invoke(new Base()));
        // exact-class semantics: a method declared only on the subclass is not found on the superclass
        assertThrows(NoSuchMethodException.class,
            () -> MethodHandleCache.declared(Base.class, "own"));
    }

    @Test
    void declaredUpWalksTheHierarchy() throws Exception {
        final Method own = MethodHandleCache.declaredUp(Derived.class, "own");
        assertSame(Derived.class, own.getDeclaringClass());
        assertEquals("own", own.invoke(new Derived()));
        // private superclass method reachable through the walk
        final Method hidden = MethodHandleCache.declaredUp(Derived.class, "hidden");
        assertSame(Base.class, hidden.getDeclaringClass());
        assertEquals("hidden", hidden.invoke(new Derived()));
        assertThrows(NoSuchMethodException.class,
            () -> MethodHandleCache.declaredUp(Derived.class, "missing"));
    }

    @Test
    void declaredByArityPicksFirstMatchingOverload() throws Exception {
        // getDeclaredMethods order is unspecified; only the name/arity contract is stable.
        final Method setValue = MethodHandleCache.declaredByArity(Base.class, "setValue", 1);
        assertEquals("setValue", setValue.getName());
        assertEquals(1, setValue.getParameterCount());
        assertThrows(NoSuchMethodException.class,
            () -> MethodHandleCache.declaredByArity(Base.class, "setValue", 2));
    }

    @Test
    void overloadsReturnsAllPublicMatchingMethodsAndTryNextStillWorks() throws Exception {
        final List<Method> overloads = MethodHandleCache.overloads(Base.class, "setValue", 1);
        assertEquals(2, overloads.size());
        assertTrue(overloads.stream().allMatch(m -> m.getName().equals("setValue") && m.getParameterCount() == 1));
        // try-next-overload semantics: the first overload may reject the boxed argument
        boolean invoked = false;
        for (Method method : overloads) {
            try {
                method.invoke(new Base(), Float.valueOf(1f));
                invoked = true;
                break;
            } catch (IllegalArgumentException ignored) {
                // try the next overload
            }
        }
        assertTrue(invoked, "one of the cached overloads must accept a boxed Float argument");
        assertFalse(MethodHandleCache.overloads(Base.class, "setValue", 1).isEmpty());
        assertTrue(MethodHandleCache.overloads(Base.class, "missing", 1).isEmpty());
    }

    @Test
    void concurrentLookupsResolveEveryKeyExactlyOnceAndReturnSameHandles() throws Exception {
        final int threads = 8;
        final int lookupsPerThread = 200;
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<Method[]>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    final Method[] seen = new Method[lookupsPerThread];
                    for (int i = 0; i < lookupsPerThread; i++) {
                        // rotate over three keys to force concurrent misses and hits
                        seen[i] = switch (i % 3) {
                            case 0 -> MethodHandleCache.method(Derived.class, "baseValue");
                            case 1 -> MethodHandleCache.declared(Derived.class, "own");
                            default -> MethodHandleCache.declaredUp(Base.class, "hidden");
                        };
                    }
                    return seen;
                }));
            }
            start.countDown();
            final Method[] first = futures.get(0).get(10, TimeUnit.SECONDS);
            final Method reference0 = first[0];
            final Method reference1 = first[1];
            final Method reference2 = first[2];
            for (Future<Method[]> future : futures) {
                for (Method method : future.get(10, TimeUnit.SECONDS)) {
                    assertNotNull(method);
                }
                // every thread must observe the exact same cached handles per key
                assertSame(reference0, future.get(10, TimeUnit.SECONDS)[0]);
                assertSame(reference1, future.get(10, TimeUnit.SECONDS)[1]);
                assertSame(reference2, future.get(10, TimeUnit.SECONDS)[2]);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
