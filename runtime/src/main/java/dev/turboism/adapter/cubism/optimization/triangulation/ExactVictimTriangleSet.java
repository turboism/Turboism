package dev.turboism.adapter.cubism.optimization.triangulation;

import dev.turboism.adapter.cubism.optimization.atlas.TextureAtlasLoading;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Backing {@link LinkedHashSet} for native triangle lists that exposes the actually removed entry
 * in {@code O(1)} without allocating wrapper keys or scanning survivors on removal.
 */
public final class ExactVictimTriangleSet extends LinkedHashSet<Object> {
    private static final long serialVersionUID = 1L;
    private static final int UNUSED_SUPER_CAPACITY = 0;
    private static final float UNUSED_SUPER_LOAD_FACTOR = 1.0f;

    /** Package-private sentinel distinguishing an absent probe from a stored {@code null} element. */
    static final Object ABSENT = new Object();

    private final LinkedHashMap<Object, Object> values = new LinkedHashMap<>();

    public ExactVictimTriangleSet() {
        super(UNUSED_SUPER_CAPACITY, UNUSED_SUPER_LOAD_FACTOR);
    }

    /** Replaces a raw {@link LinkedHashSet} field value while keeping any existing adapter set. */
    public static LinkedHashSet<Object> wrap(final LinkedHashSet<Object> original) {
        if (original instanceof ExactVictimTriangleSet || original instanceof TextureAtlasTriangleSet) {
            return original;
        }
        final ExactVictimTriangleSet wrapped = new ExactVictimTriangleSet();
        if (original != null && !original.isEmpty()) {
            wrapped.addAll(original);
        }
        return wrapped;
    }

    /** Factory matching {@link TextureAtlasTriangleSet#forAtlas} when triangle hashing is global. */
    public static LinkedHashSet<Object> forGlobal(final LinkedHashSet<Object> original, final Class<?> owner) {
        if (!TextureAtlasLoading.isInitializing()) return original;
        return wrap(original);
    }

    Object removeExact(final Object value) {
        if (value == null) {
            if (!values.containsKey(null)) {
                return ABSENT;
            }
            values.remove(null);
            return null;
        }
        final Object victim = values.remove(value);
        return victim != null ? victim : ABSENT;
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
    public boolean contains(final Object value) {
        return values.containsKey(value);
    }

    @Override
    public boolean add(final Object value) {
        if (value == null) {
            if (values.containsKey(null)) {
                return false;
            }
            values.put(null, null);
            return true;
        }
        // Preserve the earliest identity when a distinct equal probe is inserted again.
        return values.putIfAbsent(value, value) == null;
    }

    @Override
    public boolean remove(final Object value) {
        return removeExact(value) != ABSENT;
    }

    @Override
    public void clear() {
        values.clear();
    }

    @Override
    public Iterator<Object> iterator() {
        return values.keySet().iterator();
    }

    @Override
    public Spliterator<Object> spliterator() {
        return values.keySet().spliterator();
    }

    @Override
    public Object[] toArray() {
        return values.keySet().toArray();
    }

    @Override
    public <T> T[] toArray(final T[] destination) {
        return values.keySet().toArray(destination);
    }

    @Override
    public boolean containsAll(final Collection<?> items) {
        return values.keySet().containsAll(items);
    }

    @Override
    public boolean addAll(final Collection<?> items) {
        boolean modified = false;
        for (final Object item : items) {
            if (add(item)) {
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public boolean retainAll(final Collection<?> items) {
        return values.keySet().retainAll(items);
    }

    @Override
    public boolean removeAll(final Collection<?> items) {
        return values.keySet().removeAll(items);
    }

    @Override
    public boolean removeIf(final Predicate<? super Object> filter) {
        return values.keySet().removeIf(filter);
    }

    @Override
    public void forEach(final Consumer<? super Object> action) {
        values.keySet().forEach(action);
    }

    @Override
    public boolean equals(final Object other) {
        if (other == this) {
            return true;
        }
        return values.keySet().equals(other);
    }

    @Override
    public int hashCode() {
        return values.keySet().hashCode();
    }

    @Override
    public String toString() {
        return values.keySet().toString();
    }

    @Override
    public Object clone() {
        final ExactVictimTriangleSet copy = new ExactVictimTriangleSet();
        copy.values.putAll(values);
        return copy;
    }
}
