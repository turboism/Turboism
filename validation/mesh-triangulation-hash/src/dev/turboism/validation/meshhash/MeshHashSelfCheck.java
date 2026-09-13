package dev.turboism.validation.meshhash;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline self-check for the constant-hash triangulation fix. It never defines or executes any
 * official class: it works on its own fixture, which mirrors the reviewed host shapes.
 *
 * <p>It answers four questions with evidence rather than assertion:</p>
 * <ol>
 *   <li>does a constant hash actually degenerate the host's edge set (one treeified bucket)?</li>
 *   <li>does the patched hash restore a normal bucket distribution and lookup cost?</li>
 *   <li>is the patched hash consistent with the six-permutation {@code equals}?</li>
 *   <li>does the patch refuse every shape that is not exactly the reviewed one?</li>
 * </ol>
 */
public final class MeshHashSelfCheck {
    private static final String POINT = "dev.turboism.validation.meshhash.fixture.PointLike";
    private static final String TRIPLE = "dev.turboism.validation.meshhash.fixture.CornerTriple";
    private static final String PAIR = "dev.turboism.validation.meshhash.fixture.PairLike";
    private static final String TRIPLE_INTERNAL =
        "dev/turboism/validation/meshhash/fixture/CornerTriple";
    private static final String POINT_DESCRIPTOR =
        "Ldev/turboism/validation/meshhash/fixture/PointLike;";
    private static final String CHILD_PREFIX = "dev.turboism.validation.meshhash.fixture.";
    private static final int ELEMENTS = 4000;

    private static final List<String> FAILURES = new ArrayList<>();

    private MeshHashSelfCheck() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalStateException("usage: MeshHashSelfCheck <fixture-classes-dir>");
        }
        final Path fixtureDir = Path.of(args[0]);
        final Map<String, byte[]> original = readFixture(fixtureDir);
        final CornerTripleHashPatcher patcher =
            new CornerTripleHashPatcher(TRIPLE_INTERNAL, POINT_DESCRIPTOR);

        final byte[] originalTriple = original.get(TRIPLE);
        check(originalTriple != null, "fixture triple class is present");
        final byte[] patchedTriple = patcher.patch(originalTriple);
        check(patchedTriple != null && patchedTriple.length > 0, "patch produced bytes");
        check(!java.util.Arrays.equals(originalTriple, patchedTriple),
            "patch changed the class bytes");

        checkRejections(patcher, original, patchedTriple);
        checkContract(original, patchedTriple);

        final Stats before = measure(loader(original), ELEMENTS);
        final Stats after = measure(loader(withPatchedTriple(original, patchedTriple)), ELEMENTS);
        report(before, after);

        check(after.distinctHashes > 1,
            "patched hash values are not constant");
        check(after.maxBucket < before.maxBucket,
            "patched bucket distribution is strictly better than the constant hash");
        // Treeified buckets are normal above the treeify threshold; what must not survive is a
        // single bucket holding every element.
        check(after.maxBucket <= 64 && after.maxBucket < before.maxBucket / 8,
            "patched buckets are shallow instead of holding every element");
        check(after.lookupNanos < before.lookupNanos,
            "patched lookups are faster on the same element count");

        if (!FAILURES.isEmpty()) {
            System.out.println("MESH_HASH_SELFCHECK FAIL checks=" + FAILURES.size());
            for (final String failure : FAILURES) System.out.println("  " + failure);
            System.exit(1);
        }
        System.out.println("MESH_HASH_SELFCHECK PASS"
            + " elements=" + ELEMENTS
            + " originalMaxBucket=" + before.maxBucket
            + " patchedMaxBucket=" + after.maxBucket
            + " originalTreeBuckets=" + before.treeNodeBuckets
            + " patchedTreeBuckets=" + after.treeNodeBuckets
            + " originalLookupMillis=" + (before.lookupNanos / 1_000_000L)
            + " patchedLookupMillis=" + (after.lookupNanos / 1_000_000L)
            + " hostExecuted=false officialClassLoaded=false");
    }

    /** The six permutations the host's equals accepts must all hash identically. */
    private static void checkContract(final Map<String, byte[]> original, final byte[] patchedTriple)
            throws Exception {
        final ClassLoader loader = loader(withPatchedTriple(original, patchedTriple));
        final Class<?> point = loader.loadClass(POINT);
        final Class<?> triple = loader.loadClass(TRIPLE);
        final Method hashCode = triple.getMethod("hashCode");
        final Method equals = triple.getMethod("equals", Object.class);

        final Object p1 = newPoint(point, 1.5f, 2.5f, 7);
        final Object p2 = newPoint(point, 3.5f, 4.5f, 8);
        final Object p3 = newPoint(point, 5.5f, 6.5f, 9);
        final Object[][] orders = {
            {p1, p2, p3}, {p2, p3, p1}, {p3, p1, p2},
            {p1, p3, p2}, {p2, p1, p3}, {p3, p2, p1},
        };
        final int expected = (int) hashCode.invoke(newTriple(triple, orders[0]));
        for (final Object[] order : orders) {
            final Object instance = newTriple(triple, order);
            check((int) hashCode.invoke(instance) == expected,
                "permutation hashes are identical: " + java.util.Arrays.toString(order));
            check((boolean) equals.invoke(instance, newTriple(triple, orders[0])),
                "permutation compares equal");
        }

        // The host's point equals ignores index while its point hashCode includes it. The patch must
        // not delegate to that hashCode, or equal corners with different indices would hash apart.
        final Object sameXYDifferentIndex = newPoint(point, 1.5f, 2.5f, 700);
        final Object a = newTriple(triple, new Object[] {p1, p2, p3});
        final Object b = newTriple(triple, new Object[] {sameXYDifferentIndex, p2, p3});
        check((boolean) equals.invoke(a, b), "index is not part of equality");
        check((int) hashCode.invoke(a) == (int) hashCode.invoke(b),
            "the patched hash ignores index, matching equals");
        check(point.getMethod("hashCode").invoke(p1)
                != point.getMethod("hashCode").invoke(sameXYDifferentIndex),
            "fixture point hashCode is index-sensitive, as the host's is");
    }

    private static void checkRejections(final CornerTripleHashPatcher patcher,
                                        final Map<String, byte[]> original,
                                        final byte[] patchedTriple) throws Exception {
        rejects("already patched", () -> patcher.patch(patchedTriple));
        rejects("unexpected class identity", () -> new CornerTripleHashPatcher(
            "dev/turboism/validation/meshhash/fixture/NotTheReviewedClass", POINT_DESCRIPTOR)
            .patch(original.get(TRIPLE)));
        rejects("unexpected field descriptor", () -> new CornerTripleHashPatcher(
            TRIPLE_INTERNAL, "Ljava/lang/Object;").patch(original.get(TRIPLE)));
        rejects("non class bytes", () -> patcher.patch(new byte[] {1, 2, 3, 4}));
        rejects("truncated bytes", () -> patcher.patch(java.util.Arrays.copyOf(
            original.get(TRIPLE), 24)));
    }

    private static Map<String, byte[]> readFixture(final Path dir) throws Exception {
        final Map<String, byte[]> classes = new LinkedHashMap<>();
        for (final String name : List.of(POINT, TRIPLE, PAIR)) {
            final Path file = dir.resolve(name.replace('.', '/') + ".class");
            classes.put(name, Files.readAllBytes(file));
        }
        return classes;
    }

    private static Map<String, byte[]> withPatchedTriple(final Map<String, byte[]> base,
                                                         final byte[] patched) {
        final Map<String, byte[]> copy = new LinkedHashMap<>(base);
        copy.put(TRIPLE, patched);
        return copy;
    }

    private static ClassLoader loader(final Map<String, byte[]> classes) {
        return new FixtureLoader(MeshHashSelfCheck.class.getClassLoader(), CHILD_PREFIX, classes);
    }

    private static Object newPoint(final Class<?> point, final float x, final float y,
                                   final int index) throws Exception {
        return point.getConstructor(float.class, float.class, int.class).newInstance(x, y, index);
    }

    private static Object newTriple(final Class<?> triple, final Object[] points)
            throws Exception {
        return triple.getConstructor(
            Class.forName(POINT, false, triple.getClassLoader()),
            Class.forName(POINT, false, triple.getClassLoader()),
            Class.forName(POINT, false, triple.getClassLoader()))
            .newInstance(points[0], points[1], points[2]);
    }

    private record Stats(int distinctHashes, int maxBucket, int treeNodeBuckets, long lookupNanos) {
    }

    /**
     * Populates the host-shaped edge set — {@code Set<Pair<triple, triple>>} — and measures the
     * resulting bucket layout plus lookup cost.
     */
    private static Stats measure(final ClassLoader loader, final int elements) throws Exception {
        final Class<?> point = loader.loadClass(POINT);
        final Class<?> triple = loader.loadClass(TRIPLE);
        final Class<?> pair = loader.loadClass(PAIR);
        final Set<Object> set = new HashSet<>();
        final Set<Integer> hashes = new HashSet<>();
        final List<Object> keys = new ArrayList<>(elements);
        for (int index = 0; index < elements; index++) {
            // Well-spread coordinates, so the patched hash is judged on a fair distribution.
            final Object p1 = newPoint(point, index * 8191.0f, index * 131.0f + 0.5f, index);
            final Object p2 = newPoint(point, index * 4093.0f + 0.25f, index * 257.0f + 0.75f,
                index + elements);
            final Object p3 = newPoint(point, index * 2039.0f + 0.125f, index * 521.0f + 0.375f,
                index + 2 * elements);
            final Object left = newTriple(triple, new Object[] {p1, p2, p3});
            final Object right = newTriple(triple, new Object[] {p3, p1, p2});
            final Object edge = pair.getConstructor(Object.class, Object.class)
                .newInstance(left, right);
            hashes.add((int) left.getClass().getMethod("hashCode").invoke(left));
            set.add(edge);
            keys.add(edge);
        }
        final long started = System.nanoTime();
        int found = 0;
        for (final Object key : keys) {
            if (set.contains(key)) found++;
        }
        final long lookupNanos = System.nanoTime() - started;
        check(found == elements, "every inserted element is found again");

        final Object[] table = backingTable(set);
        int maxBucket = 0;
        int treeBuckets = 0;
        int usedBuckets = 0;
        for (final Object bucket : table) {
            if (bucket == null) continue;
            usedBuckets++;
            int length = 0;
            boolean tree = false;
            Object node = bucket;
            while (node != null) {
                length++;
                if (node.getClass().getName().contains("TreeNode")) tree = true;
                node = field(node, "next");
            }
            maxBucket = Math.max(maxBucket, length);
            if (tree) treeBuckets++;
        }
        System.out.println("MESH_HASH_SAMPLE buckets=" + usedBuckets
            + " tableLength=" + table.length
            + " maxBucket=" + maxBucket
            + " treeBuckets=" + treeBuckets
            + " distinctHashes=" + hashes.size());
        return new Stats(hashes.size(), maxBucket, treeBuckets, lookupNanos);
    }

    private static void report(final Stats before, final Stats after) {
        System.out.println("MESH_HASH_COMPARE"
            + " original[distinct=" + before.distinctHashes
            + " maxBucket=" + before.maxBucket
            + " treeBuckets=" + before.treeNodeBuckets
            + " lookupMillis=" + (before.lookupNanos / 1_000_000L) + "]"
            + " patched[distinct=" + after.distinctHashes
            + " maxBucket=" + after.maxBucket
            + " treeBuckets=" + after.treeNodeBuckets
            + " lookupMillis=" + (after.lookupNanos / 1_000_000L) + "]");
    }

    private static Object[] backingTable(final Set<?> set) throws Exception {
        final Object map = field(set, "map");
        return (Object[]) field(map, "table");
    }

    private static Object field(final Object target, final String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            for (final Field field : type.getDeclaredFields()) {
                if (field.getName().equals(name)) {
                    field.setAccessible(true);
                    return field.get(target);
                }
            }
            type = type.getSuperclass();
        }
        throw new IllegalStateException("field not found: " + name);
    }

    private static void check(final boolean condition, final String description) {
        if (!condition) FAILURES.add(description);
    }

    private static void rejects(final String label, final Runnable action) {
        try {
            action.run();
            FAILURES.add("patch accepted an invalid input: " + label);
        } catch (CornerTripleHashPatcher.Rejected expected) {
            System.out.println("MESH_HASH_REJECTED " + label + " -> " + expected.getMessage());
        } catch (RuntimeException unexpected) {
            FAILURES.add("patch failed with an unexpected error for " + label + ": " + unexpected);
        }
    }
}
