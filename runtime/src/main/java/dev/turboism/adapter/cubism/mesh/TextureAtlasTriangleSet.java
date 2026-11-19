package dev.turboism.adapter.cubism.mesh;

import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLoading;
import dev.turboism.core.io.Sha256;
import dev.turboism.core.runtime.work.FatalErrors;
import java.io.IOException;
import java.io.Serial;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;

/** Preserves native triangle equality and insertion order with coordinate-based hash keys. */
public final class TextureAtlasTriangleSet extends LinkedHashSet<Object> {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final String TRIANGLE = TriangulationEdgeIndexPatcher.TRIANGLE.replace('/', '.');
    private static final String POINT = TriangulationEdgeIndexPatcher.POINT.replace('/', '.');
    private static final String VECTOR = "com.live2d.graphics3d.type.GVector2";
    private static final Set<String> TRIANGLE_DIGESTS = Set.of(
            "1fbcf0dc4cac94a02a943e1032c8e1b350aac9b357ac2590541ba5dd03e3cf47",
            "6f06427c59d3907fe0d4ec80c72a318410d2e5169e18a8263ddfaa526813bd90");
    private static final Set<String> POINT_DIGESTS =
            Set.of("7d09c3c00c7fe730e09ccff106749de65821ce21654b86c982c1660e90f59b5c");
    private static final Set<String> VECTOR_DIGESTS = Set.of(
            "c6fc13cdbc80d13a2a2eb236d494a0242bd76fb1c03668ba4cfbbcfd940058fe",
            "91d06613e29fe8d0b1b03a30594cbc28d9e2a49adcdcd544be60e2576e0aac31");
    private static final String CLASS_SUFFIX = ".class";
    private static final String[] CORNER_GETTERS = {"a", "b", "c"};
    private static final String X_GETTER = "getX";
    private static final String Y_GETTER = "getY";
    private static final int AXIS_MULTIPLIER = 31;
    private static final MethodType AXIS_TYPE = MethodType.methodType(float.class, Object.class);
    private static final MethodType CORNER_TYPE = MethodType.methodType(Object.class, Object.class);

    private static final ClassValue<Optional<CornerHash>> HASHES = new ClassValue<>() {
        @Override
        protected Optional<CornerHash> computeValue(final Class<?> owner) {
            try {
                final ClassLoader loader = owner.getClassLoader();
                final Class<?> triangle = Class.forName(TRIANGLE, false, loader);
                final Class<?> point = Class.forName(POINT, false, loader);
                final Class<?> vector = Class.forName(VECTOR, false, loader);
                if (!matches(triangle, TRIANGLE_DIGESTS)
                        || !matches(point, POINT_DIGESTS)
                        || !matches(vector, VECTOR_DIGESTS)) return Optional.empty();
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                final MethodHandle[] corners = new MethodHandle[CORNER_GETTERS.length];
                for (int index = 0; index < corners.length; index++) {
                    corners[index] = lookup.findVirtual(triangle, CORNER_GETTERS[index], MethodType.methodType(point))
                            .asType(CORNER_TYPE);
                }
                return Optional.of(new CornerHash(
                        triangle,
                        corners,
                        lookup.findVirtual(point, X_GETTER, MethodType.methodType(float.class))
                                .asType(AXIS_TYPE),
                        lookup.findVirtual(point, Y_GETTER, MethodType.methodType(float.class))
                                .asType(AXIS_TYPE)));
            } catch (ReflectiveOperationException | IOException | LinkageError | SecurityException unsupported) {
                return Optional.empty();
            }
        }
    };

    private final LinkedHashMap<Key, Object> values = new LinkedHashMap<>();
    private final ToIntFunction<Object> hash;

    TextureAtlasTriangleSet(final ToIntFunction<Object> hash) {
        this.hash = hash;
    }

    /** Uses the reviewed collection only for triangle lists created while opening the atlas. */
    public static LinkedHashSet<Object> forAtlas(final LinkedHashSet<Object> original, final Class<?> owner) {
        if (!TextureAtlasLoading.isInitializing()) return original;
        return HASHES.get(owner)
                .<LinkedHashSet<Object>>map(TextureAtlasTriangleSet::new)
                .orElse(original);
    }

    /** Preserves edge lookup for accepted triangles in the atlas set. */
    public static boolean add(
            final LinkedHashSet<Object> triangles, final Object triangle, final int a, final int b, final int c) {
        return triangles instanceof TextureAtlasTriangleSet
                ? TriangulationEdgeIndex.add(triangles, triangle, a, b, c)
                : triangles.add(triangle);
    }

    /** Removes the triangle and its indexed edges from the atlas set. */
    public static boolean remove(final LinkedHashSet<Object> triangles, final Object triangle) {
        return triangles instanceof TextureAtlasTriangleSet
                ? TriangulationEdgeIndex.remove(triangles, triangle)
                : triangles.remove(triangle);
    }

    /** Uses native triangle equality for membership in either collection. */
    public static boolean contains(final LinkedHashSet<Object> triangles, final Object triangle) {
        return triangles instanceof TextureAtlasTriangleSet
                ? TriangulationEdgeIndex.contains(triangles, triangle)
                : triangles.contains(triangle);
    }

    /** Releases edge lookup state when the host clears an atlas set. */
    public static void clear(final LinkedHashSet<Object> triangles) {
        if (triangles instanceof TextureAtlasTriangleSet) {
            TriangulationEdgeIndex.clear(triangles);
        } else {
            triangles.clear();
        }
    }

    /** Returns null when the caller must use the native edge scan. */
    public static List<?> tryQuery(final LinkedHashSet<Object> triangles, final int a, final int b) {
        return triangles instanceof TextureAtlasTriangleSet ? TriangulationEdgeIndex.tryQuery(triangles, a, b) : null;
    }

    @Override
    public boolean add(final Object value) {
        return values.putIfAbsent(key(value), Objects.requireNonNull(value)) == null;
    }

    @Override
    public boolean contains(final Object value) {
        return values.containsKey(key(value));
    }

    @Override
    public boolean remove(final Object value) {
        return values.remove(key(value)) != null;
    }

    @Override
    public void clear() {
        values.clear();
    }

    @Override
    public Iterator<Object> iterator() {
        return values.values().iterator();
    }

    @Override
    public Object[] toArray() {
        return values.values().toArray();
    }

    @Override
    public <T> T[] toArray(final T[] target) {
        return values.values().toArray(target);
    }

    @Override
    public int size() {
        return values.size();
    }

    @Override
    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public java.util.Spliterator<Object> spliterator() {
        return java.util.Spliterators.spliterator(this, java.util.Spliterator.ORDERED | java.util.Spliterator.DISTINCT);
    }

    private Key key(final Object value) {
        return new Key(value, hash.applyAsInt(value));
    }

    private static boolean matches(final Class<?> type, final Set<String> digests) throws IOException {
        final String resource = '/' + type.getName().replace('.', '/') + CLASS_SUFFIX;
        try (var bytes = type.getResourceAsStream(resource)) {
            return bytes != null && digests.contains(Sha256.hex(bytes.readAllBytes()));
        }
    }

    private record Key(Object value, int hash) {
        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Key key && Objects.equals(value, key.value);
        }
    }

    private record CornerHash(Class<?> triangle, MethodHandle[] corners, MethodHandle x, MethodHandle y)
            implements ToIntFunction<Object> {
        @Override
        public int applyAsInt(final Object value) {
            if (!triangle.isInstance(value)) return Objects.hashCode(value);
            int hash = 0;
            try {
                for (final MethodHandle corner : corners) {
                    final Object point = corner.invokeExact(value);
                    hash += AXIS_MULTIPLIER * coordinateHash((float) x.invokeExact(point))
                            + coordinateHash((float) y.invokeExact(point));
                }
                return hash;
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw FatalErrors.propagate(failure);
            }
        }

        private static int coordinateHash(final float value) {
            return value == 0.0f ? 0 : Float.hashCode(value);
        }
    }
}
