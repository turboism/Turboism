import com.live2d.graphics3d.editableMesh.triangulation.TriPoint;
import com.live2d.graphics3d.editableMesh.triangulation.j;
import com.live2d.graphics3d.editableMesh.triangulation.k;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;

/** T058 owned-only native membership proof; no host method is patched. */
public final class BorrowedEdgeMembershipSelfCheck {
    private static int checks;
    private static volatile int consumed;
    private BorrowedEdgeMembershipSelfCheck() { }

    private static void require(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    private static j edge(float x, float y, int first, int second) {
        return new j(new TriPoint(x, y, first), new TriPoint(x + 0.5f, y - 0.5f, second));
    }

    private static boolean initialContains(k edges, j query) {
        // Exact surrounding native predicate: coordinates, not endpoint indices.
        for (j entry : edges.a()) if (coordinateEqual(entry, query)) return true;
        return false;
    }

    private static boolean coordinateEqual(j entry, j query) {
        return kotlin.jvm.internal.Intrinsics.areEqual(entry.a(), query.a())
                && kotlin.jvm.internal.Intrinsics.areEqual(entry.b(), query.b())
                || kotlin.jvm.internal.Intrinsics.areEqual(entry.a(), query.b())
                && kotlin.jvm.internal.Intrinsics.areEqual(entry.b(), query.a());
    }

    private static void controls(String profile) throws Exception {
        java.lang.reflect.Method nativeCoordinate = null;
        try {
            nativeCoordinate = j.class.getDeclaredMethod("a", j.class, boolean.class);
        } catch (NoSuchMethodException absent) {
            require(profile.equals("5203"), "coordinate method absent only in 5203");
        }
        require((nativeCoordinate != null) == !profile.equals("5203"), "version-specific coordinate predicate");
        Random random = new Random(5805303);
        for (int size : new int[] {0, 1, 2, 8, 32, 128, 512}) {
            k edges = new k();
            for (int i = 0; i < size; i++) {
                float x = i % 11 == 0 ? Float.NaN : random.nextFloat() * 1000;
                float y = i % 13 == 0 ? Float.POSITIVE_INFINITY : random.nextFloat() * 1000;
                edges.a().add(edge(x, y, i * 2, i * 2 + 1));
            }
            require(edges.a().getClass() == ArrayList.class, "native list shape");
            j query = edge(-10000, -10000, -3, -2);
            require(!initialContains(edges, query), "native negative full scan");
            for (j borrowed : edges.a()) {
                if (nativeCoordinate != null) {
                    for (j compared : new j[] {query, borrowed, new j(borrowed.b(), borrowed.a())}) {
                        require((boolean) nativeCoordinate.invoke(borrowed, compared, false)
                                == coordinateEqual(borrowed, compared), "actual native coordinate predicate");
                    }
                }
                require(edges.a(borrowed, false), "borrowed native unordered membership");
                require(edges.a(borrowed, true), "borrowed native ordered membership");
                require(edges.a(new j(borrowed.b(), borrowed.a()), false), "reversed indices");
                borrowed.a().setX(Float.NaN);
                borrowed.b().setY(Float.NEGATIVE_INFINITY);
                require(edges.a(borrowed, false), "coordinate mutation does not change index membership");
            }
            // Equal index pair with different physical endpoints/coordinates remains a native hit.
            for (j borrowed : edges.a()) {
                j equalIndices = edge(-3000, 2000, borrowed.a().getIndex(), borrowed.b().getIndex());
                require(edges.a(equalIndices, false), "index equality differs from coordinate equality");
                require(!coordinateEqual(borrowed, equalIndices), "never replace initial coordinate predicate");
            }
        }

        k malformed = new k();
        j member = edge(1, 2, 1, 2);
        malformed.a().add(null);
        malformed.a().add(member);
        try {
            malformed.a(member, false);
            throw new AssertionError("an unqualified physical-member shortcut hides native null failure");
        } catch (NullPointerException expected) {
            checks++;
        }
        final IllegalStateException sentinel = new IllegalStateException("owned iterator failure");
        final class FailingList extends ArrayList<j> {
            private static final long serialVersionUID = 1L;
            int iteratorRequests;
            @Override public Iterator<j> iterator() {
                if (++iteratorRequests == 3) throw sentinel;
                return super.iterator();
            }
        }
        FailingList altered = new FailingList();
        altered.add(member);
        k subclass = new k();
        var field = k.class.getDeclaredField("a");
        field.setAccessible(true);
        field.set(subclass, altered); // Owned fixture only; no production field mutation.
        require(!initialContains(subclass, edge(10, 11, 3, 4)), "first scan completes");
        Iterator<j> inner = subclass.a().iterator();
        j borrowed = inner.next();
        require(subclass.a().getClass() != ArrayList.class, "subclass must decline shortcut");
        try {
            subclass.a(borrowed, false);
            throw new AssertionError("subclass native iterator exception must remain");
        } catch (IllegalStateException actual) {
            require(actual == sentinel, "native Throwable identity");
        }
        try {
            initialContains(malformed, edge(10, 11, 3, 4));
            throw new AssertionError("surrounding initial scan must reject malformed list first");
        } catch (NullPointerException expected) {
            checks++;
        }
        require(!new k().a(member, false), "empty native list is false");
        try {
            new k().a(null, false);
            throw new AssertionError("native null parameter failure");
        } catch (NullPointerException expected) {
            checks++;
        }
    }

    private static long time(k edges, j[] members, boolean shortcut, int rounds) {
        int hits = 0;
        long start = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            for (j member : members) {
                // Only the duplicated inner membership predicate is measured here.
                // Initial scan/intersection/host method/lease are deliberately excluded.
                boolean found = shortcut ? edges.a().getClass() == ArrayList.class && member != null
                        : edges.a(member, false);
                if (found) hits++;
            }
        }
        long elapsed = System.nanoTime() - start;
        consumed = hits;
        require(hits == rounds * members.length, "all measured native borrowed members found");
        return elapsed;
    }

    private static void benchmark() {
        for (int size : new int[] {8, 32, 128, 512}) {
            k edges = new k();
            for (int i = 0; i < size; i++) edges.a().add(edge(i, -i, i * 2, i * 2 + 1));
            j[] members = edges.a().toArray(j[]::new);
            int rounds = Math.max(100, 200_000 / size);
            for (int warmup = 0; warmup < 5; warmup++) {
                time(edges, members, false, rounds);
                time(edges, members, true, rounds);
            }
            long[] original = new long[7], shortcut = new long[7];
            for (int repeat = 0; repeat < 7; repeat++) {
                if ((repeat & 1) == 0) {
                    original[repeat] = time(edges, members, false, rounds);
                    shortcut[repeat] = time(edges, members, true, rounds);
                } else {
                    shortcut[repeat] = time(edges, members, true, rounds);
                    original[repeat] = time(edges, members, false, rounds);
                }
            }
            System.out.println("timing size=" + size + " comparisons=" + rounds * size
                    + " nativeNanos=" + Arrays.toString(original) + " shortcutNanos=" + Arrays.toString(shortcut));
        }
    }

    public static void main(String[] args) throws Exception {
        controls(args[0]);
        benchmark();
        System.out.println("BORROWED_EDGE_MEMBERSHIP_PASS profile=" + args[0] + " checks=" + checks
                + " consumed=" + consumed + " scope=OWNED_PREDICATE_ONLY");
    }
}
