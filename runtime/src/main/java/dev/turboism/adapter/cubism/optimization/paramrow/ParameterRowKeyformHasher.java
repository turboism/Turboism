package dev.turboism.adapter.cubism.optimization.paramrow;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * Computes the SplitMix64 signatures that decide which palette rows may skip a repaint.
 *
 * <p>Two signatures exist. The keyform signature answers "did the keyform or morph-target
 * geometry of this parameter change", and the visual signature answers "did anything this row
 * paints change". Both are order-sensitive accumulations of the same mixer, so a swapped pair of
 * values changes the result while a repeated pass over unchanged host state does not.</p>
 *
 * <p>The keyform signature is stored per parameter GUID in a caller-owned map, because one
 * parameter can be bound by several grids and morph sets. A GUID whose bindings contribute twice
 * accumulates, and a GUID that no reviewed source mentions keeps no entry, which the row lookup
 * reads as {@code 0}. The caller clears every bucket before a rehash, so a removed binding cannot
 * survive as a stale signature.</p>
 *
 * <p>Every host read goes through {@link ParameterRowHostAccess}, so this class holds no host type
 * and no descriptor.</p>
 */
final class ParameterRowKeyformHasher {

    private static final long SPLITMIX_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long SPLITMIX_MUL_1 = 0xBF58476D1CE4E5B9L;
    private static final long SPLITMIX_MUL_2 = 0x94D049BB133111EBL;
    private static final int SPLITMIX_SHIFT_1 = 30;
    private static final int SPLITMIX_SHIFT_2 = 27;
    private static final int SPLITMIX_SHIFT_3 = 31;

    private static final long KEYFORM_PRIMARY_SALT = 0x243F6A8885A308D3L;
    private static final long KEYFORM_EXTENDED_SALT = 0x13198A2E03707344L;
    private static final long KEYFORM_MORPH_SALT = 0xA4093822299F31D0L;

    private static final long SINGLE_ROW_KIND_TAG = 1L;
    private static final long DOUBLE_ROW_KIND_TAG = 2L;
    private static final long GROUP_ROW_KIND_TAG = 3L;
    private static final long GENERIC_ROW_KIND_TAG = 4L;

    private static final int FLAG_TRUE_HASH = 1231;
    private static final int FLAG_FALSE_HASH = 1237;
    private static final long ABSENT_KEY_BITS = 0L;
    private static final long NO_KEYFORM_SIGNATURE = 0L;

    private final ParameterRowHostAccess host;

    ParameterRowKeyformHasher(final ParameterRowHostAccess host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /** Returns the row signature seed of a row that carries one parameter. */
    static long singleRowSeed() {
        return SINGLE_ROW_KIND_TAG;
    }

    /** Returns the row signature seed of a row that carries two parameters. */
    static long doubleRowSeed() {
        return DOUBLE_ROW_KIND_TAG;
    }

    /** Returns the row signature seed of a palette folder. */
    static long groupRowSeed() {
        return GROUP_ROW_KIND_TAG;
    }

    /** Returns the row signature seed of a widget outside the parameter row families. */
    static long genericRowSeed() {
        return GENERIC_ROW_KIND_TAG;
    }

    /** Mixes one value into a signature. */
    static long mix(final long seed, final long value) {
        long z = seed + value + SPLITMIX_GAMMA;
        z = (z ^ (z >>> SPLITMIX_SHIFT_1)) * SPLITMIX_MUL_1;
        z = (z ^ (z >>> SPLITMIX_SHIFT_2)) * SPLITMIX_MUL_2;
        return z ^ (z >>> SPLITMIX_SHIFT_3);
    }

    /** Mixes one flag into a signature with distinct true and false values. */
    static long mixFlag(final long seed, final boolean flag) {
        return mix(seed, flag ? FLAG_TRUE_HASH : FLAG_FALSE_HASH);
    }

    /** Mixes one boxed value's hash code into a signature; {@code null} hashes as zero. */
    static long mixHashCode(final long seed, final Object value) {
        return mix(seed, Objects.hashCode(value));
    }

    /**
     * Mixes one parameter source into a row signature and returns its GUID.
     *
     * <p>A {@code null} source contributes a single zero value and no GUID, so a row without a
     * source keeps a stable signature instead of failing.</p>
     */
    Object accumulateSource(final long[] signature, final Object paramSource) throws Throwable {
        if (paramSource == null) {
            signature[0] = mix(signature[0], 0L);
            return null;
        }
        final ParameterRowHostAccess.ParameterSourceFacts facts = host.readParameterSource(paramSource);
        long hash = mix(signature[0], Objects.hashCode(facts.guid()));
        hash = mixHashCode(hash, facts.id());
        hash = mixHashCode(hash, facts.name());
        hash = mix(hash, facts.minBits());
        hash = mix(hash, facts.maxBits());
        hash = mix(hash, facts.defaultBits());
        hash = mixFlag(hash, facts.repeat());
        hash = mixFlag(hash, facts.combined());
        hash = mixFlag(hash, facts.morphTarget());
        hash = mixHashCode(hash, facts.paramType());
        hash = mix(hash, facts.visibilityColorArgb());
        signature[0] = hash;
        return facts.guid();
    }

    /**
     * Recomputes every keyform signature of one model into {@code signatures}.
     *
     * <p>The caller clears the map first. Each controllable object contributes its primary grid,
     * extended grid, primary morph set and extended morph set; the object index is part of every
     * contribution, so moving a binding between objects changes the signature.</p>
     */
    void hashKeyforms(final Object model, final HashMap<Object, long[]> signatures) throws Throwable {
        final List<?> objects = host.modelObjects(model);
        if (objects == null) {
            return;
        }
        final int count = objects.size();
        for (int index = 0; index < count; index++) {
            final Object candidate = objects.get(index);
            if (!host.isControllable(candidate)) {
                continue;
            }
            final Object source = host.controllableSource(candidate);
            if (source == null) {
                continue;
            }
            accumulateGrid(signatures, host.keyformGrid(source), index, KEYFORM_PRIMARY_SALT);
            accumulateGrid(signatures, host.extendedKeyformGrid(source), index, KEYFORM_EXTENDED_SALT);
            accumulateMorphs(signatures, host.morphTargetSet(source), index, KEYFORM_MORPH_SALT);
            accumulateMorphs(signatures, host.extendedMorphTargetSet(source), index, KEYFORM_EXTENDED_SALT);
        }
    }

    /** Returns the stored keyform signature of one GUID, or {@code 0} when the GUID has no entry. */
    static long keyformSignature(final HashMap<Object, long[]> signatures, final Object guid) {
        if (guid == null) {
            return NO_KEYFORM_SIGNATURE;
        }
        final long[] bucket = signatures.get(guid);
        return bucket == null ? NO_KEYFORM_SIGNATURE : bucket[0];
    }

    /** Resets every stored keyform signature to zero without dropping the buckets. */
    static void clearSignatures(final HashMap<Object, long[]> signatures) {
        for (final long[] bucket : signatures.values()) {
            bucket[0] = 0L;
        }
    }

    private void accumulateGrid(
            final HashMap<Object, long[]> signatures, final Object grid, final int objectIndex, final long salt)
            throws Throwable {
        if (grid == null) {
            return;
        }
        final List<?> bindings = host.keyformBindings(grid);
        if (bindings == null) {
            return;
        }
        final int size = bindings.size();
        for (int index = 0; index < size; index++) {
            final Object binding = bindings.get(index);
            if (binding == null) {
                continue;
            }
            final Object guid = host.bindingGuid(binding);
            final List<?> keys = host.bindingKeys(binding);
            if (guid == null || keys == null) {
                continue;
            }
            final int keyCount = keys.size();
            long hash = mix(salt, objectIndex);
            hash = mix(hash, keyCount);
            for (int key = 0; key < keyCount; key++) {
                hash = mix(hash, keyBits(keys.get(key)));
            }
            recordSignature(signatures, guid, hash);
        }
    }

    private void accumulateMorphs(
            final HashMap<Object, long[]> signatures, final Object morphSet, final int objectIndex, final long salt)
            throws Throwable {
        if (morphSet == null) {
            return;
        }
        final List<?> targets = host.morphTargets(morphSet);
        if (targets == null) {
            return;
        }
        final int size = targets.size();
        for (int index = 0; index < size; index++) {
            final Object target = targets.get(index);
            if (target == null) {
                continue;
            }
            final Object guid = host.morphTargetGuid(target);
            if (guid == null) {
                continue;
            }
            final Float keyValue = host.morphTargetKeyValue(target);
            final long keyBits = keyValue == null ? ABSENT_KEY_BITS : Float.floatToRawIntBits(keyValue);
            final long hash = mix(mix(salt, objectIndex), keyBits);
            recordSignature(signatures, guid, hash);
        }
    }

    /**
     * Accumulates one contribution into a GUID's bucket.
     *
     * <p>A GUID that already holds a non-zero signature keeps accumulating, so two bindings of the
     * same parameter produce one combined signature rather than the last writer winning.</p>
     */
    private static void recordSignature(final HashMap<Object, long[]> signatures, final Object guid, final long hash) {
        long[] bucket = signatures.get(guid);
        if (bucket == null) {
            bucket = new long[1];
            signatures.put(guid, bucket);
        }
        final long previous = bucket[0];
        bucket[0] = mix(previous == NO_KEYFORM_SIGNATURE ? SPLITMIX_GAMMA : previous, hash);
    }

    /** Returns the raw bits of one keyform key; a non-float key contributes zero. */
    private static long keyBits(final Object key) {
        if (key instanceof Float value) {
            return Float.floatToRawIntBits(value);
        }
        return ABSENT_KEY_BITS;
    }
}
