package dev.turboism.validation.meshhash.fixture;

import java.util.List;

/**
 * Stand-in for the host's {@code com.live2d.graphics3d.editableMesh.triangulation.l}: a triangle
 * corner triple whose {@code equals} treats the three points as an unordered set — all six
 * permutations compare equal — while {@code hashCode} returns the constant {@code 0}.
 *
 * <p>The constant hash is the defect under study. It is legal with respect to {@code equals}
 * (equal objects do share it) but pathologically bad: every element lands in one bucket.</p>
 */
public final class CornerTriple {
    private PointLike a;
    private PointLike b;
    private PointLike c;

    public CornerTriple(final PointLike a, final PointLike b, final PointLike c) {
        if (a == null || b == null || c == null) {
            throw new IllegalArgumentException("corner points must not be null");
        }
        this.a = a;
        this.b = b;
        this.c = c;
    }

    public PointLike a() {
        return a;
    }

    public PointLike b() {
        return b;
    }

    public PointLike c() {
        return c;
    }

    /** The six permutations the host's {@code equals} accepts. */
    private static final int[][] PERMUTATIONS = {
        {0, 1, 2}, {1, 2, 0}, {2, 0, 1}, {0, 2, 1}, {1, 0, 2}, {2, 1, 0},
    };

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof CornerTriple triple)) return false;
        final List<PointLike> mine = List.of(a, b, c);
        final List<PointLike> theirs = List.of(triple.a, triple.b, triple.c);
        for (final int[] order : PERMUTATIONS) {
            if (mine.get(0).equals(theirs.get(order[0]))
                    && mine.get(1).equals(theirs.get(order[1]))
                    && mine.get(2).equals(theirs.get(order[2]))) {
                return true;
            }
        }
        return false;
    }

    /** The defect: a constant that satisfies the contract but collapses every bucket. */
    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public String toString() {
        return "CornerTriple[" + a + "," + b + "," + c + "]";
    }
}
