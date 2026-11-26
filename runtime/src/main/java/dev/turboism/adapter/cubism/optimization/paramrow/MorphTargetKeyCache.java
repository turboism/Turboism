package dev.turboism.adapter.cubism.optimization.paramrow;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memoizes per-parameter distinct morph-target key values per model across row repaints.
 *
 * <p>Host {@code morphTarget.{Y$f,ab$h,ag$h}.a(CModel, CParameterGuid)} scans every controllable
 * object and calls {@code CModelSource.getAllObjects()} plus {@code getMorphTargetList(guid)} once
 * per parameter row. This cache scans all model objects once per mutation epoch using backing
 * source lists directly (falling back to {@code CModel.getAllObjects()} when {@code CModelSource}
 * is absent) and populates each row's {@code CArrayList<Float>} without temporary lists or
 * {@code toArray} copies.</p>
 */
final class MorphTargetKeyCache {

    private static final String CMODEL_CLASS = "com.live2d.cubism.doc.model.CModel";
    private static final String CMODEL_SOURCE_CLASS = "com.live2d.cubism.doc.model.CModelSource";
    private static final String ROW_INFO_BASE_CLASS =
            "com.live2d.cubism.view.palette.parameter.ui.SingleParameterRowWidget$a$a";
    private static final String CARRAY_LIST_CLASS = "com.live2d.type.CArrayList";

    private static final String GET_SOURCE_METHOD = "getSource";
    private static final String GET_SOURCES_METHOD = "getSources";
    private static final String GET_KEYS_LIST_METHOD = "b";

    private static final String DRAWABLE_SET_CLASS = "com.live2d.cubism.doc.model.drawable.CDrawableSourceSet";
    private static final String DEFORMER_SET_CLASS = "com.live2d.cubism.doc.model.deformer.CDeformerSourceSet";
    private static final String AFFECTER_SET_CLASS = "com.live2d.cubism.doc.model.affecter.CAffecterSourceSet";
    private static final String PART_SET_CLASS = "com.live2d.cubism.doc.model.parts.CPartSourceSet";
    private static final String ALIAS_SET_CLASS = "com.live2d.cubism.doc.model.alias.CAliasSourceSet";

    private static final String[] REQUIRED_SOURCE_SET_GETTERS = {
        "getDrawableSourceSet", "getDeformerSourceSet", "getAffecterSourceSet", "getPartSourceSet"
    };
    private static final String[] REQUIRED_SOURCE_SET_CLASSES = {
        DRAWABLE_SET_CLASS, DEFORMER_SET_CLASS, AFFECTER_SET_CLASS, PART_SET_CLASS
    };
    private static final String ALIAS_SET_GETTER = "getAliasSourceSet";

    private static final Float[] EMPTY_FLOATS = new Float[0];
    private static final int INITIAL_REBUILD_RETRIES = 2;

    private final ParameterRowHostAccess host;
    private final MethodHandle rowInfoKeyListGetter;
    private final MethodHandle modelGetSource;
    private final MethodHandle[] sourceListGetters;

    private final AtomicLong morphEpoch = new AtomicLong(1L);
    private final Object cacheLock = new Object();
    private final ReferenceQueue<Object> staleKeys = new ReferenceQueue<>();
    private final List<CachedEntry> entries = new ArrayList<>();
    private CachedEntry lastHit;

    private volatile long cacheHits;
    private volatile long cacheMisses;

    MorphTargetKeyCache(final ClassLoader hostLoader, final ParameterRowHostAccess host) {
        this.host = Objects.requireNonNull(host, "host");
        this.rowInfoKeyListGetter = resolveRowInfoKeyListGetter(hostLoader);
        this.modelGetSource = resolveModelGetSource(hostLoader);
        this.sourceListGetters = modelGetSource == null ? new MethodHandle[0] : resolveSourceListGetters(hostLoader);
    }

    /** Advances the mutation epoch after a morph-target structural or value change. */
    void invalidate() {
        morphEpoch.incrementAndGet();
    }

    /** Clears all cached model entries and advances the invalidation epoch. */
    void clear() {
        morphEpoch.incrementAndGet();
        synchronized (cacheLock) {
            entries.clear();
            lastHit = null;
            while (staleKeys.poll() != null) {
                // Drain cleared key references after cache reset.
            }
        }
    }

    long cacheHits() {
        return cacheHits;
    }

    long cacheMisses() {
        return cacheMisses;
    }

    /**
     * Populates {@code rowInfo.b()} from cache or a single-pass build, returning {@code false} to
     * fall back to the original Cubism implementation on unexpected inputs or missing handles.
     */
    boolean populateMorphKeys(final Object rowInfo, final Object model, final Object paramGuid) {
        if (rowInfo == null || model == null || paramGuid == null || rowInfoKeyListGetter == null) {
            return false;
        }
        try {
            final Object modelSource = modelGetSource == null ? null : (Object) modelGetSource.invokeExact(model);
            final boolean useSourceSets = modelSource != null && sourceListGetters.length > 0;
            final Object cacheKey = useSourceSets ? modelSource : model;

            final long epoch = morphEpoch.get();
            final CachedEntry hit = findValidEntry(cacheKey, epoch, useSourceSets, model);
            final Float[] keys;
            if (hit != null) {
                cacheHits++;
                keys = hit.keysByParam.get(paramGuid);
            } else {
                cacheMisses++;
                final CachedEntry rebuilt = rebuildEntry(model, cacheKey, useSourceSets);
                if (rebuilt == null) {
                    return false;
                }
                keys = rebuilt.keysByParam.get(paramGuid);
            }
            if (keys == null || keys.length == 0) {
                return true;
            }
            @SuppressWarnings("unchecked")
            final List<Object> target = (List<Object>) rowInfoKeyListGetter.invokeExact(rowInfo);
            if (target == null) {
                return false;
            }
            if (target instanceof ArrayList<?> arrayList) {
                arrayList.ensureCapacity(arrayList.size() + keys.length);
            }
            for (final Float keyValue : keys) {
                target.add(keyValue);
            }
            return true;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    private CachedEntry findValidEntry(
            final Object cacheKey, final long epoch, final boolean useSourceSets, final Object model) throws Throwable {
        synchronized (cacheLock) {
            expungeStaleLocked();
            final CachedEntry quick = lastHit;
            if (quick != null && quick.matchesKey(cacheKey) && quick.epoch == epoch) {
                if (listsStillMatch(cacheKey, quick, useSourceSets, model)) {
                    return quick;
                }
            }
            for (int i = 0; i < entries.size(); i++) {
                final CachedEntry entry = entries.get(i);
                if (entry.matchesKey(cacheKey) && entry.epoch == epoch) {
                    if (listsStillMatch(cacheKey, entry, useSourceSets, model)) {
                        lastHit = entry;
                        return entry;
                    }
                    entries.remove(i);
                    if (lastHit == entry) {
                        lastHit = null;
                    }
                    return null;
                }
            }
            return null;
        }
    }

    private CachedEntry rebuildEntry(final Object model, final Object cacheKey, final boolean useSourceSets)
            throws Throwable {
        for (int attempt = 0; attempt < INITIAL_REBUILD_RETRIES; attempt++) {
            final long startEpoch = morphEpoch.get();
            final List<?>[] snapshotLists = snapshotBackingLists(cacheKey, useSourceSets, model);
            if (snapshotLists == null) {
                return null;
            }
            final int[] snapshotSizes = new int[snapshotLists.length];
            final Map<Object, LinkedHashSet<Float>> grouped = new HashMap<>();
            for (int i = 0; i < snapshotLists.length; i++) {
                final List<?> sourceList = snapshotLists[i];
                if (sourceList == null) {
                    return null;
                }
                snapshotSizes[i] = sourceList.size();
                scanSourceList(sourceList, useSourceSets, grouped);
            }
            final Map<Object, Float[]> frozen = new HashMap<>(grouped.size() * 2 + 1);
            for (final Map.Entry<Object, LinkedHashSet<Float>> entry : grouped.entrySet()) {
                frozen.put(entry.getKey(), entry.getValue().toArray(EMPTY_FLOATS));
            }
            final CachedEntry candidate =
                    new CachedEntry(cacheKey, staleKeys, startEpoch, snapshotLists, snapshotSizes, frozen);
            if (morphEpoch.get() == startEpoch && listsStillMatch(cacheKey, candidate, useSourceSets, model)) {
                storeEntry(candidate);
                return candidate;
            }
            if (attempt + 1 == INITIAL_REBUILD_RETRIES) {
                return candidate;
            }
        }
        return null;
    }

    private void scanSourceList(
            final List<?> list, final boolean elementsAreSources, final Map<Object, LinkedHashSet<Float>> out)
            throws Throwable {
        final int size = list.size();
        for (int i = 0; i < size; i++) {
            final Object raw = list.get(i);
            if (raw == null) {
                continue;
            }
            final Object source;
            if (elementsAreSources) {
                source = raw;
            } else if (host.isControllable(raw)) {
                source = host.controllableSource(raw);
            } else {
                continue;
            }
            if (source == null) {
                continue;
            }
            final Object morphSet = host.morphTargetSet(source);
            if (morphSet == null) {
                continue;
            }
            final List<?> targets = host.morphTargets(morphSet);
            if (targets == null || targets.isEmpty()) {
                continue;
            }
            final int targetCount = targets.size();
            for (int j = 0; j < targetCount; j++) {
                final Object morphTarget = targets.get(j);
                if (morphTarget == null) {
                    continue;
                }
                final Object guid = host.morphTargetGuid(morphTarget);
                final Float keyValue = host.morphTargetKeyValue(morphTarget);
                if (guid != null && keyValue != null) {
                    out.computeIfAbsent(guid, ignored -> new LinkedHashSet<>()).add(keyValue);
                }
            }
        }
    }

    private List<?>[] snapshotBackingLists(final Object cacheKey, final boolean useSourceSets, final Object model)
            throws Throwable {
        if (!useSourceSets) {
            final List<?> objects = host.modelObjects(model);
            return objects == null ? null : new List<?>[] {objects};
        }
        final List<?>[] lists = new List<?>[sourceListGetters.length];
        for (int i = 0; i < sourceListGetters.length; i++) {
            lists[i] = (List<?>) sourceListGetters[i].invokeExact(cacheKey);
            if (lists[i] == null) {
                return null;
            }
        }
        return lists;
    }

    private boolean listsStillMatch(
            final Object cacheKey, final CachedEntry entry, final boolean useSourceSets, final Object model)
            throws Throwable {
        if (!useSourceSets) {
            final List<?> objects = host.modelObjects(model);
            return objects == entry.sourceLists[0] && objects != null && objects.size() == entry.sourceSizes[0];
        }
        if (entry.sourceLists.length != sourceListGetters.length) {
            return false;
        }
        for (int i = 0; i < sourceListGetters.length; i++) {
            final List<?> live = (List<?>) sourceListGetters[i].invokeExact(cacheKey);
            if (live != entry.sourceLists[i] || live == null || live.size() != entry.sourceSizes[i]) {
                return false;
            }
        }
        return true;
    }

    private void storeEntry(final CachedEntry candidate) {
        synchronized (cacheLock) {
            expungeStaleLocked();
            final Object key = candidate.get();
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).matchesKey(key)) {
                    entries.set(i, candidate);
                    lastHit = candidate;
                    return;
                }
            }
            entries.add(candidate);
            lastHit = candidate;
        }
    }

    private void expungeStaleLocked() {
        CachedEntry stale;
        while ((stale = (CachedEntry) staleKeys.poll()) != null) {
            entries.remove(stale);
            if (lastHit == stale) {
                lastHit = null;
            }
        }
    }

    private static MethodHandle resolveRowInfoKeyListGetter(final ClassLoader loader) {
        try {
            final Class<?> rowInfoClass = Class.forName(ROW_INFO_BASE_CLASS, false, loader);
            final Class<?> cArrayListClass = Class.forName(CARRAY_LIST_CLASS, false, loader);
            return MethodHandles.publicLookup()
                    .findVirtual(rowInfoClass, GET_KEYS_LIST_METHOD, MethodType.methodType(cArrayListClass))
                    .asType(MethodType.methodType(List.class, Object.class));
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static MethodHandle resolveModelGetSource(final ClassLoader loader) {
        try {
            final Class<?> modelClass = Class.forName(CMODEL_CLASS, false, loader);
            final Class<?> sourceClass = Class.forName(CMODEL_SOURCE_CLASS, false, loader);
            return MethodHandles.publicLookup()
                    .findVirtual(modelClass, GET_SOURCE_METHOD, MethodType.methodType(sourceClass))
                    .asType(MethodType.methodType(Object.class, Object.class));
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static MethodHandle[] resolveSourceListGetters(final ClassLoader loader) {
        try {
            final Class<?> modelSourceClass = Class.forName(CMODEL_SOURCE_CLASS, false, loader);
            final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            final List<MethodHandle> resolved = new ArrayList<>(REQUIRED_SOURCE_SET_GETTERS.length + 1);
            for (int i = 0; i < REQUIRED_SOURCE_SET_GETTERS.length; i++) {
                final MethodHandle combined = resolveComposedSourcesHandle(
                        lookup,
                        loader,
                        modelSourceClass,
                        REQUIRED_SOURCE_SET_GETTERS[i],
                        REQUIRED_SOURCE_SET_CLASSES[i]);
                if (combined == null) {
                    return new MethodHandle[0];
                }
                resolved.add(combined);
            }
            final MethodHandle aliasHandle =
                    resolveComposedSourcesHandle(lookup, loader, modelSourceClass, ALIAS_SET_GETTER, ALIAS_SET_CLASS);
            if (aliasHandle != null) {
                resolved.add(aliasHandle);
            }
            return resolved.toArray(MethodHandle[]::new);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return new MethodHandle[0];
        }
    }

    private static MethodHandle resolveComposedSourcesHandle(
            final MethodHandles.Lookup lookup,
            final ClassLoader loader,
            final Class<?> modelSourceClass,
            final String setGetterName,
            final String setClassName) {
        try {
            final Class<?> setClass = Class.forName(setClassName, false, loader);
            final MethodHandle setGetter =
                    lookup.findVirtual(modelSourceClass, setGetterName, MethodType.methodType(setClass));
            final MethodHandle sourcesGetter =
                    lookup.findVirtual(setClass, GET_SOURCES_METHOD, MethodType.methodType(List.class));
            return MethodHandles.filterReturnValue(setGetter, sourcesGetter)
                    .asType(MethodType.methodType(List.class, Object.class));
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static final class CachedEntry extends WeakReference<Object> {
        private final long epoch;
        private final List<?>[] sourceLists;
        private final int[] sourceSizes;
        private final Map<Object, Float[]> keysByParam;

        private CachedEntry(
                final Object key,
                final ReferenceQueue<Object> queue,
                final long epoch,
                final List<?>[] sourceLists,
                final int[] sourceSizes,
                final Map<Object, Float[]> keysByParam) {
            super(key, queue);
            this.epoch = epoch;
            this.sourceLists = sourceLists;
            this.sourceSizes = sourceSizes;
            this.keysByParam = keysByParam;
        }

        private boolean matchesKey(final Object candidate) {
            return candidate != null && get() == candidate;
        }
    }
}
