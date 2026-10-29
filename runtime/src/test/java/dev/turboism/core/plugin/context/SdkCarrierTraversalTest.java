package dev.turboism.core.plugin.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
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

class SdkCarrierTraversalTest {

    interface TargetSdkInterface {}

    interface Samples {
        Optional<TargetSdkInterface> optional();

        List<TargetSdkInterface> list();

        Set<TargetSdkInterface> set();

        Map<String, TargetSdkInterface> map();

        CompletionStage<TargetSdkInterface> stage();

        TargetSdkInterface[] array();

        byte[] byteArray();

        byte[][] byteMatrix();

        String[] stringArray();

        List<String>[] genericArray();
    }

    private static Type returnType(final String methodName) throws NoSuchMethodException {
        return Samples.class.getMethod(methodName).getGenericReturnType();
    }

    @Test
    void nullAndNonCarrierValuesPassthrough() {
        assertNull(SdkCarrierTraversal.wrapValue(null, String.class, (v, t) -> v));
        assertEquals("plain", SdkCarrierTraversal.wrapValue("plain", String.class, (v, t) -> v));

        assertNull(SdkCarrierTraversal.unwrapArguments(null, proxy -> proxy));
        final Object[] emptyArgs = new Object[0];
        assertSame(emptyArgs, SdkCarrierTraversal.unwrapArguments(emptyArgs, proxy -> proxy));
    }

    @Test
    void unchangedCarriersPreserveIdentity() throws Exception {
        final Object raw = new Object();
        // Leaf interface where wrapper returns raw directly
        assertSame(raw, SdkCarrierTraversal.wrapValue(raw, TargetSdkInterface.class, (v, t) -> v));

        final Optional<Object> emptyOpt = Optional.empty();
        assertSame(emptyOpt, SdkCarrierTraversal.wrapValue(emptyOpt, returnType("optional"), (v, t) -> "wrap"));

        final Optional<Object> originalOpt = Optional.of(raw);
        assertSame(originalOpt, SdkCarrierTraversal.wrapValue(originalOpt, returnType("optional"), (v, t) -> v));

        final List<Object> originalList = List.of(raw, "other");
        assertSame(originalList, SdkCarrierTraversal.wrapValue(originalList, returnType("list"), (v, t) -> v));

        final Set<Object> originalSet = Set.of(raw, "other");
        assertSame(originalSet, SdkCarrierTraversal.wrapValue(originalSet, returnType("set"), (v, t) -> v));

        final Map<String, Object> originalMap = Map.of("k", raw);
        assertSame(originalMap, SdkCarrierTraversal.wrapValue(originalMap, returnType("map"), (v, t) -> v));
    }

    @Test
    void changedCarriersPreserveIterationOrderNullsAndAreUnmodifiable() throws Exception {
        final Object raw1 = new Object();
        final Object raw2 = new Object();
        final Object wrapped1 = new Object();
        final Object wrapped2 = new Object();

        final SdkCarrierTraversal.InterfaceWrapper wrapper = (v, t) -> {
            if (v == raw1) {
                return wrapped1;
            }
            if (v == raw2) {
                return wrapped2;
            }
            return v;
        };

        // List with null and order
        final List<Object> sourceList = Arrays.asList(raw1, null, raw2);
        final AtomicInteger listVisits = new AtomicInteger();
        @SuppressWarnings("unchecked")
        final List<Object> mappedList =
                (List<Object>) SdkCarrierTraversal.wrapValue(sourceList, returnType("list"), (v, t) -> {
                    listVisits.incrementAndGet();
                    return wrapper.wrapInterface(v, t);
                });
        assertEquals(2, listVisits.get(), "non-null list elements mapped once; null passes through");
        assertNotSame(sourceList, mappedList);
        assertEquals(3, mappedList.size());
        assertSame(wrapped1, mappedList.get(0));
        assertNull(mappedList.get(1));
        assertSame(wrapped2, mappedList.get(2));
        assertThrows(UnsupportedOperationException.class, () -> mappedList.add("err"));

        // Set with null and insertion order
        final LinkedHashSet<Object> sourceSet = new LinkedHashSet<>();
        sourceSet.add(raw1);
        sourceSet.add(null);
        sourceSet.add(raw2);
        final AtomicInteger setVisits = new AtomicInteger();
        @SuppressWarnings("unchecked")
        final Set<Object> mappedSet =
                (Set<Object>) SdkCarrierTraversal.wrapValue(sourceSet, returnType("set"), (v, t) -> {
                    setVisits.incrementAndGet();
                    return wrapper.wrapInterface(v, t);
                });
        assertEquals(2, setVisits.get(), "non-null set elements mapped once; null passes through");
        assertNotSame(sourceSet, mappedSet);
        assertEquals(3, mappedSet.size());
        final List<Object> setElements = new ArrayList<>(mappedSet);
        assertSame(wrapped1, setElements.get(0));
        assertNull(setElements.get(1));
        assertSame(wrapped2, setElements.get(2));
        assertThrows(UnsupportedOperationException.class, () -> mappedSet.add("err"));

        // Map with null key and null value and insertion order
        final LinkedHashMap<String, Object> sourceMap = new LinkedHashMap<>();
        sourceMap.put("first", raw1);
        sourceMap.put(null, null);
        sourceMap.put("third", raw2);
        final AtomicInteger mapVisits = new AtomicInteger();
        @SuppressWarnings("unchecked")
        final Map<String, Object> mappedMap =
                (Map<String, Object>) SdkCarrierTraversal.wrapValue(sourceMap, returnType("map"), (v, t) -> {
                    mapVisits.incrementAndGet();
                    return wrapper.wrapInterface(v, t);
                });
        assertEquals(2, mapVisits.get(), "interface values mapped once; non-interface keys and nulls pass through");
        assertNotSame(sourceMap, mappedMap);
        assertEquals(3, mappedMap.size());
        final List<String> mapKeys = new ArrayList<>(mappedMap.keySet());
        assertEquals("first", mapKeys.get(0));
        assertNull(mapKeys.get(1));
        assertEquals("third", mapKeys.get(2));
        assertSame(wrapped1, mappedMap.get("first"));
        assertNull(mappedMap.get(null));
        assertSame(wrapped2, mappedMap.get("third"));
        assertThrows(UnsupportedOperationException.class, () -> mappedMap.put("err", "err"));
    }

    @Test
    void arraysAreAlwaysDefensivelyCopied() throws Exception {
        // 1D primitive array
        final byte[] bytes = new byte[] {1, 2, 3};
        final byte[] bytesCopy = (byte[]) SdkCarrierTraversal.wrapValue(bytes, returnType("byteArray"), (v, t) -> v);
        assertNotSame(bytes, bytesCopy);
        assertArrayEquals(bytes, bytesCopy);

        // 2D primitive array with null
        final byte[][] matrix = new byte[][] {{1, 2}, null, {3, 4}};
        final byte[][] matrixCopy =
                (byte[][]) SdkCarrierTraversal.wrapValue(matrix, returnType("byteMatrix"), (v, t) -> v);
        assertNotSame(matrix, matrixCopy);
        assertNotSame(matrix[0], matrixCopy[0]);
        assertNull(matrixCopy[1]);
        assertNotSame(matrix[2], matrixCopy[2]);
        assertArrayEquals(matrix[0], matrixCopy[0]);
        assertArrayEquals(matrix[2], matrixCopy[2]);

        // 1D reference array
        final String[] strings = new String[] {"x", null, "z"};
        final String[] stringsCopy =
                (String[]) SdkCarrierTraversal.wrapValue(strings, returnType("stringArray"), (v, t) -> v);
        assertNotSame(strings, stringsCopy);
        assertArrayEquals(strings, stringsCopy);

        // Generic array (e.g. List<String>[])
        @SuppressWarnings("unchecked")
        final List<String>[] generic = (List<String>[]) new List<?>[1];
        generic[0] = List.of("hello");
        @SuppressWarnings("unchecked")
        final List<String>[] genericCopy =
                (List<String>[]) SdkCarrierTraversal.wrapValue(generic, returnType("genericArray"), (v, t) -> v);
        assertNotSame(generic, genericCopy);
        assertSame(generic[0], genericCopy[0]);
    }

    @Test
    void completionStageWrapsAsyncResult() throws Exception {
        final CompletableFuture<Object> future = new CompletableFuture<>();
        final Object raw = new Object();
        final Object wrapped = new Object();

        @SuppressWarnings("unchecked")
        final CompletionStage<Object> stage = (CompletionStage<Object>)
                SdkCarrierTraversal.wrapValue(future, returnType("stage"), (v, t) -> v == raw ? wrapped : v);

        final AtomicReference<Object> asyncResult = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        stage.thenAccept(res -> {
            asyncResult.set(res);
            latch.countDown();
        });

        future.complete(raw);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertSame(wrapped, asyncResult.get());
    }

    @Test
    void unwrapArgumentsUnwrapsAllCarriers() {
        final Object dummyProxy = new Object();
        final Object dummyDelegate = new Object();
        final java.util.function.Function<Object, Object> unwrapper = obj -> obj == dummyProxy ? dummyDelegate : obj;

        final Object[] input = new Object[] {
            dummyProxy,
            Optional.of(dummyProxy),
            List.of(dummyProxy),
            Set.of(dummyProxy),
            Map.of("k", dummyProxy),
            new Object[] {dummyProxy},
            "plain"
        };

        final Object[] unwrapped = SdkCarrierTraversal.unwrapArguments(input, unwrapper);
        assertNotSame(input, unwrapped);
        assertSame(dummyDelegate, unwrapped[0]);

        @SuppressWarnings("unchecked")
        final Optional<Object> opt = (Optional<Object>) unwrapped[1];
        assertSame(dummyDelegate, opt.orElseThrow());

        @SuppressWarnings("unchecked")
        final List<Object> list = (List<Object>) unwrapped[2];
        assertSame(dummyDelegate, list.get(0));

        @SuppressWarnings("unchecked")
        final Set<Object> set = (Set<Object>) unwrapped[3];
        assertSame(dummyDelegate, set.iterator().next());

        @SuppressWarnings("unchecked")
        final Map<String, Object> map = (Map<String, Object>) unwrapped[4];
        assertSame(dummyDelegate, map.get("k"));

        final Object[] array = (Object[]) unwrapped[5];
        assertSame(dummyDelegate, array[0]);

        assertSame("plain", unwrapped[6]);
    }

    @Test
    void nonEmptyPrefixRetainsIdentityAndMapsEachElementOnce() throws Exception {
        final Object keep0 = new Object();
        final Object keep1 = new Object();
        final Object target = new Object();
        final Object wrappedTarget = new Object();

        final SdkCarrierTraversal.InterfaceWrapper wrapper = (v, t) -> v == target ? wrappedTarget : v;

        // List: prefix elements 0 and 1 unchanged, index 2 changed
        final List<Object> sourceList = List.of(keep0, keep1, target);
        final AtomicInteger listVisits = new AtomicInteger();
        @SuppressWarnings("unchecked")
        final List<Object> mappedList =
                (List<Object>) SdkCarrierTraversal.wrapValue(sourceList, returnType("list"), (v, t) -> {
                    listVisits.incrementAndGet();
                    return wrapper.wrapInterface(v, t);
                });
        assertEquals(3, listVisits.get(), "each list element visited exactly once");
        assertNotSame(sourceList, mappedList);
        assertEquals(3, mappedList.size());
        assertSame(keep0, mappedList.get(0));
        assertSame(keep1, mappedList.get(1));
        assertSame(wrappedTarget, mappedList.get(2));

        // Set: prefix elements 0 and 1 unchanged, index 2 changed
        final LinkedHashSet<Object> sourceSet = new LinkedHashSet<>(List.of(keep0, keep1, target));
        final AtomicInteger setVisits = new AtomicInteger();
        @SuppressWarnings("unchecked")
        final Set<Object> mappedSet =
                (Set<Object>) SdkCarrierTraversal.wrapValue(sourceSet, returnType("set"), (v, t) -> {
                    setVisits.incrementAndGet();
                    return wrapper.wrapInterface(v, t);
                });
        assertEquals(3, setVisits.get(), "each set element visited exactly once");
        assertNotSame(sourceSet, mappedSet);
        assertEquals(3, mappedSet.size());
        final List<Object> setElements = new ArrayList<>(mappedSet);
        assertSame(keep0, setElements.get(0));
        assertSame(keep1, setElements.get(1));
        assertSame(wrappedTarget, setElements.get(2));

        // Map: prefix entries 0 and 1 unchanged, entry 2 changed
        final LinkedHashMap<String, Object> sourceMap = new LinkedHashMap<>();
        sourceMap.put("k0", keep0);
        sourceMap.put("k1", keep1);
        sourceMap.put("k2", target);
        final AtomicInteger mapVisits = new AtomicInteger();
        @SuppressWarnings("unchecked")
        final Map<String, Object> mappedMap =
                (Map<String, Object>) SdkCarrierTraversal.wrapValue(sourceMap, returnType("map"), (v, t) -> {
                    mapVisits.incrementAndGet();
                    return wrapper.wrapInterface(v, t);
                });
        assertEquals(3, mapVisits.get(), "each interface value visited exactly once");
        assertNotSame(sourceMap, mappedMap);
        assertEquals(3, mappedMap.size());
        assertSame(keep0, mappedMap.get("k0"));
        assertSame(keep1, mappedMap.get("k1"));
        assertSame(wrappedTarget, mappedMap.get("k2"));
    }

    @Test
    void unsupportedCarrierTypeThrowsIllegalStateException() {
        final Type unsupported = new Type() {
            @Override
            public String getTypeName() {
                return "com.example.UnsupportedType";
            }
        };
        final java.lang.reflect.ParameterizedType parameterized = new java.lang.reflect.ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[0];
            }

            @Override
            public Type getRawType() {
                return unsupported;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
        assertThrows(
                IllegalStateException.class,
                () -> SdkCarrierTraversal.wrapValue(new Object(), parameterized, (v, t) -> v));
    }
}
