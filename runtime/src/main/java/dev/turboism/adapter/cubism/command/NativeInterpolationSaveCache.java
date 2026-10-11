package dev.turboism.adapter.cubism.command;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorParameterInterpolationSelectorContract;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/**
 * Avoids Cubism 5.3.02's giant XML String when serializing derived interpolation
 * forms. CModelSource.setup() unconditionally rebuilds these forms from ordinary
 * bindings on open. Only the disposable normal cache is omitted, and live cache
 * objects are restored even when the native save fails. Source forms, bindings,
 * extended grids and interpolation settings are never changed.
 */
final class NativeInterpolationSaveCache {
    private static final String M = "cubism.editor-model.";
    private static final long MAX_SERIALIZED_CACHE_VERTICES = 16_000_000;

    static <T> T save(VerifiedMemberResolver resolver, Object source, Supplier<T> nativeSave) {
        if (!resolver.isExactCubismVersion("5.2.03", "5.3.02", "5.3.03")
                || !resolver.authorizesFeature(
                        EditorParameterInterpolationSelectorContract.ADAPTER_SLICE_ID,
                        EditorParameterInterpolationSelectorContract.CAPABILITY_ID,
                        EditorParameterInterpolationSelectorContract.REQUIRED_ALIASES)) return nativeSave.get();
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Save requires Editor thread.");
        if (dev.turboism.adapter.cubism.interpolation.DerivedCacheSerializationBridge.availableForModel(source))
            return nativeSave.get();
        // Resolve every member before clearing the first cache.
        for (String alias : List.of(
                "model-source.all-art-meshes",
                "parameter-controllable-source.extensions",
                "extended-interpolation.owner",
                "extended-interpolation.forms",
                "art-mesh-form.positions")) {
            resolver.bind(M + alias);
        }
        resolver.verifiedSelector(M + "extended-interpolation.class");
        var caches = new ArrayList<List<Object>>();
        long vertices = 0;
        for (Object mesh : list(resolver.invoke(M + "model-source.all-art-meshes", source))) {
            for (Object ext : list(resolver.invoke(M + "parameter-controllable-source.extensions", mesh))) {
                if (!resolver.isInstance(M + "extended-interpolation.class", ext)) continue;
                if (resolver.invoke(M + "extended-interpolation.owner", ext) != mesh)
                    throw new IllegalStateException("Foreign interpolation cache owner.");
                List<Object> forms = list(resolver.invoke(M + "extended-interpolation.forms", ext));
                for (Object form : forms) {
                    Object positions = resolver.invoke(M + "art-mesh-form.positions", form);
                    if (!(positions instanceof float[] xy) || xy.length % 2 != 0)
                        throw new IllegalStateException("Invalid interpolation cache positions.");
                    vertices = Math.addExact(vertices, xy.length / 2);
                }
                caches.add(forms);
            }
        }
        return vertices <= MAX_SERIALIZED_CACHE_VERTICES ? nativeSave.get() : withoutCaches(caches, nativeSave);
    }

    static <T> T withoutCaches(List<List<Object>> caches, Supplier<T> save) {
        // Preserve exact form references and order, including on a partial clear failure.
        var originals = caches.stream().map(ArrayList::new).toList();
        try {
            for (List<Object> cache : caches) cache.clear();
            return save.get();
        } finally {
            RuntimeException failure = null;
            for (int i = 0; i < caches.size(); i++) {
                try {
                    List<Object> cache = caches.get(i), original = originals.get(i);
                    cache.clear();
                    cache.addAll(original);
                    if (cache.size() != original.size())
                        throw new IllegalStateException("Cache restoration size mismatch.");
                    for (int j = 0; j < cache.size(); j++) {
                        if (cache.get(j) != original.get(j))
                            throw new IllegalStateException("Cache restoration identity mismatch.");
                    }
                } catch (RuntimeException restore) {
                    if (failure == null) failure = restore;
                    else failure.addSuppressed(restore);
                }
            }
            if (failure != null) throw new IllegalStateException("Native save cache restoration failed.", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (!(value instanceof List<?> result)) throw new IllegalStateException("Expected native cache collection.");
        return (List<Object>) result;
    }

    private NativeInterpolationSaveCache() {}
}
