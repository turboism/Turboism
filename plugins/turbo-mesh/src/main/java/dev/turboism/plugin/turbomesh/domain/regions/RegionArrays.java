package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.List;

/** Index-array conversions and raster-key helpers shared by the region passes. */
final class RegionArrays {

    private RegionArrays() {}

    /**
     * Copies {@code values} into a primitive array in list order.
     *
     * @param values index list; a {@code null} element fails with {@link NullPointerException}
     * @return a new array holding the same indices in the same order
     */
    static int[] toIntArray(final List<Integer> values) {
        final int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /**
     * Returns the largest label in {@code labels}, or {@code 0} for an empty array or an array of
     * non-positive labels. Callers size a label-indexed array with {@code maxLabel() + 1}.
     *
     * @param labels dense label raster
     * @return the largest label value, never negative
     */
    static int maxLabel(final int[] labels) {
        int max = 0;
        for (final int label : labels) {
            max = Math.max(max, label);
        }
        return max;
    }

    /**
     * Packs a quantised cell coordinate pair into one map key.
     *
     * <p>The high half holds {@code qx} and the low half holds {@code qy}, so two pairs collide only
     * when both coordinates are equal. Coordinates outside the 32-bit range lose their high bits.
     * </p>
     *
     * @param qx quantised horizontal coordinate
     * @param qy quantised vertical coordinate
     * @return the packed key
     */
    static long cellKey(final long qx, final long qy) {
        return (qx << 32) ^ (qy & 0xFFFF_FFFFL);
    }
}
