package dev.turboism.adapter.cubism.interpolation;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** GUID index owned by one pinned native structure rebuild, never by a model lifetime. */
public final class NativeFormLookupBridge {
    /** Distinguishes an unsupported lookup from the native valid "not found" null. */
    public static final Object UNSUPPORTED = new Object();
    // Bound temporary index memory even when the native dense grid is enormous.
    static final int MAX_INDEXED_FORMS = 65_536;
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final AtomicLong INDEX_BUILDS = new AtomicLong();
    private static final AtomicLong INDEX_LOOKUPS = new AtomicLong();
    private static final AtomicLong BUDGET_FALLBACKS = new AtomicLong();
    private static final AtomicLong LARGEST_OBSERVED_BANK = new AtomicLong();
    private static final AtomicLong ACTIVE_SCOPES = new AtomicLong();
    private static final AtomicLong ACTIVE_INDEX_ENTRIES = new AtomicLong();
    private static final AtomicLong PEAK_INDEX_ENTRIES = new AtomicLong();

    /**
     * Detached observations of the auxiliary lookup cache, not a limit on native dense forms.
     * Each counter is atomic; concurrent rebuilds may advance between individual reads.
     */
    public record CacheStatistics(
            int formBudget,
            long indexBuilds,
            long indexedLookups,
            long budgetFallbacks,
            long largestObservedBank,
            long activeScopes,
            long activeIndexEntries,
            long peakIndexEntries) {}

    /** Exposes the fixed budget and cache lifecycle without retaining host objects. */
    public static CacheStatistics cacheStatistics() {
        return new CacheStatistics(
                MAX_INDEXED_FORMS,
                INDEX_BUILDS.get(),
                INDEX_LOOKUPS.get(),
                BUDGET_FALLBACKS.get(),
                LARGEST_OBSERVED_BANK.get(),
                ACTIVE_SCOPES.get(),
                ACTIVE_INDEX_ENTRIES.get(),
                PEAK_INDEX_ENTRIES.get());
    }

    private static final ClassValue<Optional<Access>> ACCESS = new ClassValue<>() {
        @Override
        protected Optional<Access> computeValue(Class<?> type) {
            try {
                return Optional.of(new Access(type));
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return Optional.empty();
            }
        }
    };

    private NativeFormLookupBridge() {}

    /** Called before the native rebuild. The token also makes disable/reentrant calls balanced. */
    public static Object enter() {
        if (!NativeMeshInterpolationBridge.isEnabled()) return null;
        Scope scope = new Scope(CURRENT.get());
        CURRENT.set(scope);
        ACTIVE_SCOPES.incrementAndGet();
        return scope;
    }

    /** Called by the transformed wrapper on both normal return and exception. */
    public static void exit(Object token) {
        if (token == null) return;
        Scope scope = (Scope) token;
        if (CURRENT.get() != scope) throw new IllegalStateException("unbalanced interpolation lookup scope");
        if (scope.parent == null) CURRENT.remove();
        else CURRENT.set(scope.parent);
        ACTIVE_INDEX_ENTRIES.addAndGet(-scope.indexEntries);
        ACTIVE_SCOPES.decrementAndGet();
        // A caller may retain the opaque token. Release its host references as
        // well as removing it from the ThreadLocal on both native exit paths.
        scope.index = null;
        scope.source = null;
        scope.extension = null;
        scope.indexEntries = 0;
    }

    /** Number of per-rebuild indexes constructed, for installed-path verification. */
    public static long indexBuilds() {
        return INDEX_BUILDS.get();
    }

    /** Number of successful indexed lookups, including valid missing GUID results. */
    public static long indexLookups() {
        return INDEX_LOOKUPS.get();
    }

    static boolean hasScope() {
        return CURRENT.get() != null;
    }

    /** Only called at the pinned local lookup after structure allocation has finished. */
    public static Object find(Object source, Object extension, Object guid) {
        Scope scope = CURRENT.get();
        if (!NativeMeshInterpolationBridge.isEnabled() || scope == null || scope.declined || extension == null)
            return UNSUPPORTED;
        Optional<Access> access = ACCESS.get(extension.getClass());
        if (access.isEmpty()) return UNSUPPORTED;
        try {
            Access host = access.get();
            if (source == null
                    || !host.sourceTypes.contains(source.getClass())
                    || (guid != null && guid.getClass() != host.guidType)
                    || host.owner.invokeExact(extension) != source) return UNSUPPORTED;
            if (scope.index == null) {
                // Native setup has finished creating all forms before the first local
                // lookup; the remaining loops only assign combination lists. No model
                // mutation or user callback can change these banks inside this scope.
                List<?> ordinary = (List<?>) host.forms.invokeExact(source);
                List<?> extended = (List<?>) host.extended.invokeExact(extension);
                long bankSize = (long) ordinary.size() + extended.size();
                LARGEST_OBSERVED_BANK.accumulateAndGet(bankSize, Math::max);
                if (bankSize > MAX_INDEXED_FORMS) {
                    scope.declined = true;
                    BUDGET_FALLBACKS.incrementAndGet();
                    return UNSUPPORTED;
                }
                Map<Object, Object> index = new HashMap<>();
                for (List<?> forms : List.of(ordinary, extended)) {
                    for (Object form : forms) {
                        if (!host.formType.isInstance(form)) return UNSUPPORTED;
                        Object key = host.guid.invokeExact(form);
                        if (key != null && key.getClass() != host.guidType) return UNSUPPORTED;
                        index.putIfAbsent(key, form); // native first match; ordinary wins
                    }
                }
                scope.source = source;
                scope.extension = extension;
                scope.index = index;
                scope.indexEntries = index.size();
                long activeEntries = ACTIVE_INDEX_ENTRIES.addAndGet(scope.indexEntries);
                PEAK_INDEX_ENTRIES.accumulateAndGet(activeEntries, Math::max);
                INDEX_BUILDS.incrementAndGet();
            }
            if (scope.source != source || scope.extension != extension) return UNSUPPORTED;
            INDEX_LOOKUPS.incrementAndGet();
            return scope.index.get(guid);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return UNSUPPORTED; // getters/index construction have not modified the host
        }
    }

    private static final class Scope {
        final Scope parent;
        Object source, extension;
        boolean declined;
        int indexEntries;
        Map<Object, Object> index;

        Scope(Scope parent) {
            this.parent = parent;
        }
    }

    private static final class Access {
        final Class<?> sourceType, formType, guidType;
        final java.util.Set<Class<?>> sourceTypes;
        final MethodHandle owner, forms, extended, guid;

        private static Class<?> host(String name, ClassLoader loader, Path artifact) throws Exception {
            Class<?> type = Class.forName(name, false, loader);
            if (type.getClassLoader() != loader
                    || !Path.of(type.getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI())
                            .toRealPath()
                            .equals(artifact)) throw new IllegalArgumentException("mixed host definition " + name);
            return type;
        }

        Access(Class<?> extension) throws Exception {
            if (!extension.getName().equals(NativeMeshInterpolationBridge.EXTENSION))
                throw new IllegalArgumentException("extension type");
            Path artifact = Path.of(extension
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toRealPath();
            NativeMeshInterpolationBridge.requireAdmittedHostArtifact(artifact);
            ClassLoader loader = extension.getClassLoader();
            sourceType = host("com.live2d.cubism.doc.model.ACParameterControllableSource", loader, artifact);
            sourceTypes = java.util.Set.of(
                    host("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource", loader, artifact),
                    host("com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerSource", loader, artifact),
                    host("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerSource", loader, artifact));
            formType = host("com.live2d.cubism.doc.model.ACForm", loader, artifact);
            guidType = host("com.live2d.type.CFormGuid", loader, artifact);
            var lookup = MethodHandles.publicLookup();
            owner = lookup.unreflect(extension.getMethod("getOwner"))
                    .asType(MethodType.methodType(Object.class, Object.class));
            forms = lookup.unreflect(sourceType.getMethod("getKeyforms"))
                    .asType(MethodType.methodType(List.class, Object.class));
            extended = lookup.unreflect(extension.getMethod("getNormalExtendedKeyforms"))
                    .asType(MethodType.methodType(List.class, Object.class));
            guid = lookup.unreflect(formType.getMethod("getGuid"))
                    .asType(MethodType.methodType(Object.class, Object.class));
        }
    }
}
