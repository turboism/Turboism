package dev.turboism.validation.meshhash.fixture;

/**
 * Stand-in for {@code kotlin.Pair<l, l>} as used by the host's triangulator edge set: its hash is
 * {@code 31 * first.hashCode() + second.hashCode()}, so two constant-hash elements hash to the same
 * value and collapse into one bucket.
 */
public final class PairLike<F, S> {
    private final F first;
    private final S second;

    public PairLike(final F first, final S second) {
        this.first = first;
        this.second = second;
    }

    public F first() {
        return first;
    }

    public S second() {
        return second;
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof PairLike<?, ?> pair)) return false;
        return java.util.Objects.equals(first, pair.first)
            && java.util.Objects.equals(second, pair.second);
    }

    @Override
    public int hashCode() {
        int result = first != null ? first.hashCode() : 0;
        result = 31 * result + (second != null ? second.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "(" + first + "|" + second + ")";
    }
}
