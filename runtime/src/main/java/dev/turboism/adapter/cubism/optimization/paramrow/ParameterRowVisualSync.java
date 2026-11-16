package dev.turboism.adapter.cubism.optimization.paramrow;

import java.util.List;
import java.util.Objects;

/**
 * Decides, per palette row, whether the row must repaint and whether its displayed value moved.
 *
 * <p>Every decision is a comparison of hashed host state against the state recorded on the previous
 * pass. A row repaints when its visual signature changed; a row syncs its displayed value when the
 * model value or the row's own value differs from the recorded pair. The two decisions are separate
 * on purpose: a value that the row already shows needs no write, and a row whose value changed
 * repaints through the host's own setter, so the bridge must not repaint it twice.</p>
 *
 * <p>All host reads go through {@link ParameterRowHostAccess}, and all hashing through
 * {@link ParameterRowKeyformHasher}, so this class owns only the comparison logic.</p>
 */
final class ParameterRowVisualSync {

    private static final int MODEL_BITS_INDEX = 0;
    private static final int ROW_BITS_INDEX = 1;

    /** Shared empty reference array, so an empty selection allocates nothing. */
    static final Object[] EMPTY_REFERENCES = new Object[0];

    private final ParameterRowHostAccess host;

    ParameterRowVisualSync(final ParameterRowHostAccess host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /** Returns whether {@code needle} is one of the recorded references. */
    static boolean containsReference(final Object[] references, final Object needle) {
        for (int index = 0; index < references.length; index++) {
            if (references[index] == needle) {
                return true;
            }
        }
        return false;
    }

    /** Returns whether one recorded reference array still matches the live list element by element. */
    static boolean sameElements(final Object[] recorded, final List<?> live) {
        final int liveSize = live == null ? 0 : live.size();
        if (recorded.length != liveSize) {
            return false;
        }
        for (int index = 0; index < liveSize; index++) {
            if (!Objects.equals(recorded[index], live.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** Returns a fixed-size copy of the live list, or the shared empty array for an empty list. */
    static Object[] copyReferences(final List<?> live) {
        if (live == null || live.isEmpty()) {
            return EMPTY_REFERENCES;
        }
        final Object[] copy = new Object[live.size()];
        for (int index = 0; index < copy.length; index++) {
            copy[index] = live.get(index);
        }
        return copy;
    }

    /**
     * Mixes one widget's paint-relevant state into a signature.
     *
     * <p>The widget's own visibility always contributes. Its component contributes its background
     * ARGB and bounds only when the component exists, so a widget without a Swing component keeps a
     * signature that ignores bounds it does not have.</p>
     */
    long mixWidget(final long seed, final Object widget) throws Throwable {
        final ParameterRowHostAccess.WidgetVisualFacts facts = host.readWidgetVisuals(widget);
        long hash = ParameterRowKeyformHasher.mixFlag(seed, facts.visible());
        if (!facts.hasComponent()) {
            return hash;
        }
        hash = ParameterRowKeyformHasher.mix(hash, facts.backgroundArgb());
        hash = ParameterRowKeyformHasher.mix(hash, facts.y());
        hash = ParameterRowKeyformHasher.mix(hash, facts.width());
        return ParameterRowKeyformHasher.mix(hash, facts.height());
    }

    /**
     * Synchronizes one row's displayed value with the model value.
     *
     * <p>Returns {@code true} when the row was written. A row whose recorded model bits and recorded
     * row bits both still match the live values is left untouched unless {@code forceSync} is set,
     * which a first or rebuilt pass sets because no recorded pair exists yet.</p>
     */
    boolean syncValue(
            final Object row,
            final Object paramSet,
            final boolean secondary,
            final int[] cachedBits,
            final boolean forceSync)
            throws Throwable {
        if (paramSet == null || !host.isParameterRow(row)) {
            return false;
        }
        final Object paramId = host.parameterRowId(row, secondary);
        if (paramId == null) {
            return false;
        }
        final Object parameter = host.parameter(paramSet, paramId);
        if (parameter == null) {
            return false;
        }
        final float modelValue = host.parameterValue(parameter);
        final int modelBits = Float.floatToRawIntBits(modelValue);
        final int rowBits = Float.floatToRawIntBits(host.parameterRowValue(row, secondary));
        if (!forceSync && modelBits == cachedBits[MODEL_BITS_INDEX] && rowBits == cachedBits[ROW_BITS_INDEX]) {
            return false;
        }
        host.setParameterRowValue(row, secondary, modelValue);
        cachedBits[MODEL_BITS_INDEX] = modelBits;
        cachedBits[ROW_BITS_INDEX] = Float.floatToRawIntBits(host.parameterRowValue(row, secondary));
        return true;
    }
}
