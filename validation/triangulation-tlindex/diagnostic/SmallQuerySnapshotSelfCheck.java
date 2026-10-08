import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Random;
import java.util.RandomAccess;

/** Standalone feasibility only; no production integration or performance claim. */
public final class SmallQuerySnapshotSelfCheck {
    private static long checks;

    static final class Snapshot<E> extends AbstractList<E> implements RandomAccess {
        private E first;
        private E second;
        private int initialSize;
        private ArrayList<E> mutable;

        Snapshot(List<? extends E> source) {
            initialSize = source.size();
            if (initialSize > 2) throw new IllegalArgumentException("small snapshot only");
            if (initialSize > 0) first = source.get(0);
            if (initialSize > 1) second = source.get(1);
        }

        @Override public int size() { return mutable == null ? initialSize : mutable.size(); }

        @Override public E get(int index) {
            Objects.checkIndex(index, size());
            return mutable != null ? mutable.get(index) : index == 0 ? first : second;
        }

        private ArrayList<E> materialize() {
            if (mutable == null) {
                // Publish only after both entries have been copied successfully.
                ArrayList<E> result = new ArrayList<>(initialSize + 1);
                if (initialSize > 0) result.add(first);
                if (initialSize > 1) result.add(second);
                mutable = result;
                first = null;
                second = null;
                initialSize = 0;
            }
            return mutable;
        }

        @Override public E set(int index, E value) {
            Objects.checkIndex(index, size());
            return materialize().set(index, value);
        }

        @Override public void add(int index, E value) {
            if (index < 0 || index > size()) throw new IndexOutOfBoundsException(index);
            materialize().add(index, value);
            modCount++;
        }

        @Override public E remove(int index) {
            Objects.checkIndex(index, size());
            E removed = materialize().remove(index);
            modCount++;
            return removed;
        }

        @Override public void clear() {
            if (mutable != null) mutable.clear();
            first = null;
            second = null;
            initialSize = 0;
            modCount++;
        }
    }

    private static void require(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void equal(List<Integer> actual, List<Integer> expected) {
        require(actual.equals(expected) && expected.equals(actual), "ordered equality");
        require(actual.hashCode() == expected.hashCode(), "hash");
        require(Arrays.equals(actual.toArray(), expected.toArray()), "object array");
        require(Arrays.equals(actual.toArray(new Integer[0]), expected.toArray(new Integer[0])), "typed array");
        require(actual.stream().toList().equals(expected), "stream iteration");
    }

    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("invalid index accepted"); }
        catch (IndexOutOfBoundsException expected) { checks++; }
    }

    public static void main(String[] args) {
        Random random = new Random(0x740053L);
        for (int trial = 0; trial < 20000; trial++) {
            ArrayList<Integer> source = new ArrayList<>();
            for (int i = 0, n = trial % 3; i < n; i++) source.add(random.nextBoolean() ? null : i);
            Snapshot<Integer> actual = new Snapshot<>(source);
            ArrayList<Integer> expected = new ArrayList<>(source);
            source.clear();
            equal(actual, expected);
            require(actual.mutable == null, "read snapshot has no backing list");
            invalid(() -> actual.get(-1));
            invalid(() -> actual.get(actual.size()));
            invalid(() -> actual.set(actual.size(), 8));
            invalid(() -> actual.add(actual.size()+1, 8));
            require(actual.mutable == null, "invalid access does not materialize");
            for (int step = 0; step < 12; step++) {
                Integer value = random.nextInt(4) == 0 ? null : random.nextInt(7);
                int index = random.nextInt(actual.size()+1);
                switch (random.nextInt(10)) {
                    case 0 -> { actual.add(index, value); expected.add(index, value); }
                    case 1 -> {
                        if (!actual.isEmpty()) {
                            index %= actual.size();
                            require(Objects.equals(actual.set(index, value), expected.set(index, value)), "set result");
                        }
                    }
                    case 2 -> {
                        if (!actual.isEmpty()) {
                            index %= actual.size();
                            require(Objects.equals(actual.remove(index), expected.remove(index)), "remove result");
                        }
                    }
                    case 3 -> require(actual.remove(value) == expected.remove(value), "remove object");
                    case 4 -> { actual.clear(); expected.clear(); }
                    case 5 -> {
                        Collection<Integer> added = Arrays.asList(value, null, 9);
                        require(actual.addAll(index, added) == expected.addAll(index, added), "addAll");
                    }
                    case 6 -> {
                        if (!actual.isEmpty()) {
                            Iterator<Integer> a = actual.iterator(), b = expected.iterator();
                            require(Objects.equals(a.next(), b.next()), "iterator value");
                            a.remove(); b.remove();
                        }
                    }
                    case 7 -> {
                        actual.subList(0, index).clear(); expected.subList(0, index).clear();
                    }
                    case 8 -> {
                        actual.replaceAll(x -> x == null ? 0 : x + 1);
                        expected.replaceAll(x -> x == null ? 0 : x + 1);
                    }
                    case 9 -> {
                        actual.removeIf(x -> x == null || x == 9);
                        expected.removeIf(x -> x == null || x == 9);
                    }
                    default -> throw new AssertionError();
                }
                equal(actual, expected);
                if (actual.mutable != null) {
                    require(actual.first == null && actual.second == null && actual.initialSize == 0,
                        "migrated inline slots released");
                }
            }
        }
        Snapshot<Integer> snapshot = new Snapshot<>(List.of(1, 2));
        Iterator<Integer> iterator = snapshot.iterator();
        snapshot.add(3);
        try { iterator.next(); throw new AssertionError("iterator not fail-fast"); }
        catch (ConcurrentModificationException expected) { checks++; }
        snapshot.clear();
        require(snapshot.first == null && snapshot.second == null && snapshot.isEmpty(), "cleared refs");
        Object identity = new Object() {
            @Override public boolean equals(Object other) { throw new AssertionError("element equals"); }
            @Override public int hashCode() { throw new AssertionError("element hash"); }
        };
        ArrayList<Object> source = new ArrayList<>(Arrays.asList(identity, null));
        Snapshot<Object> detached = new Snapshot<>(source);
        source.set(0, new Object());
        source.clear();
        require(detached.get(0) == identity && detached.get(1) == null && detached.mutable == null,
            "capture identity with no element callbacks or retained source");
        ListIterator<Object> edits = detached.listIterator(detached.size());
        require(edits.previous() == null, "reverse iteration");
        edits.set(identity);
        edits.add(null);
        require(edits.previous() == null, "iterator inserted null");
        edits.remove();
        require(detached.size() == 2 && detached.get(0) == identity && detached.get(1) == identity,
            "list iterator mutations");
        List<Object> view = detached.subList(0, 2);
        view.subList(0, 1).clear();
        require(detached.size() == 1 && detached.get(0) == identity, "nested sublist deletion");
        detached.clear();
        require(detached.first == null && detached.second == null && detached.mutable.isEmpty(),
            "clear migrated values");
        System.out.println("SMALL_QUERY_SNAPSHOT_FEASIBILITY_PASS checks=" + checks);
    }
}
