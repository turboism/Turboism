package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorArtMeshAuthoringReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorArtMeshKeyformSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectHierarchyEditSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;

/** Before image for the native reparent handler, which eagerly transforms every stored form. */
final class EditorArtMeshParentSnapshot {
    private static final String M = "cubism.editor-model.";
    private final VerifiedMemberResolver resolver;
    private final Object source;
    private final ArtMeshId id;
    private final ArtMeshAuthoringSnapshot before;
    private final Map<String, Object> forms;
    private final Object parent;
    private final Object parentGuid;

    EditorArtMeshParentSnapshot(final VerifiedMemberResolver resolver, final ArtMeshId id, final Object source) {
        this.resolver = resolver;
        this.source = source;
        this.id = id;
        if (!available(resolver))
            throw new UnsupportedOperationException("verified ArtMesh parent recovery is unavailable");
        before = read();
        forms = nativeForms();
        parent = parent();
        parentGuid = parent == null
                ? resolver.invoke(M + "deformer-guid.root", resolver.readStaticField(M + "deformer-guid.companion"))
                : resolver.invoke(M + "parameter-controllable-source.guid", parent);
    }

    static boolean available(final VerifiedMemberResolver resolver) {
        return EditorReviewedGeneration.admits(resolver)
                && resolver.authorizesFeature(
                        EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
                        EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID,
                        EditorObjectHierarchyEditSelectorContract.REQUIRED_ALIASES)
                && resolver.authorizesFeature(
                        EditorArtMeshKeyformSelectorContract.ADAPTER_SLICE_ID,
                        EditorArtMeshKeyformSelectorContract.CAPABILITY_ID,
                        EditorArtMeshKeyformSelectorContract.REQUIRED_ALIASES)
                && EditorArtMeshAuthoringReadSelectorContract.CAPABILITY_IDS.stream()
                        .allMatch(capability -> resolver.authorizesFeature(
                                EditorArtMeshAuthoringReadSelectorContract.ADAPTER_SLICE_ID,
                                capability,
                                EditorArtMeshAuthoringReadSelectorContract.REQUIRED_ALIASES));
    }

    boolean applied(final Object expectedParent) {
        requireLayout();
        return parent() == expectedParent;
    }

    void restore() {
        requireLayout();
        resolver.invoke(M + "parameter-controllable-source.set-target-deformer-guid", source, parentGuid);
        // The native parent handler owns default, ordinary and morph positions equally.
        // Preserve their identities and untouched source geometry; never reconstruct a layout.
        for (var form : before.forms()) {
            final float[] xy = new float[form.positions().size() * 2];
            for (int i = 0; i < form.positions().size(); i++) {
                xy[2 * i] = form.positions().get(i).x();
                xy[2 * i + 1] = form.positions().get(i).y();
            }
            resolver.invoke(M + "art-mesh-form.set-positions", forms.get(form.formGuid()), xy);
            resolver.invoke(M + "drawable-form.set-draw-order", forms.get(form.formGuid()), form.drawOrder());
        }
    }

    boolean restored() {
        requireLayout();
        return parent() == parent && read().equals(before);
    }

    private Object parent() {
        return resolver.invoke(M + "parameter-controllable-source.target-deformer-source", source);
    }

    private ArtMeshAuthoringSnapshot read() {
        return new EditorArtMeshAuthoringReadAccess(resolver).read(id, source);
    }

    private void requireLayout() {
        if (!EditorArtMeshKeyformAccess.sameLayout(before, read())) throw stale();
        final var now = nativeForms();
        if (!now.keySet().equals(forms.keySet())
                || forms.entrySet().stream().anyMatch(e -> now.get(e.getKey()) != e.getValue())) throw stale();
    }

    private Map<String, Object> nativeForms() {
        final Object raw = resolver.invoke(M + "art-mesh-source.keyforms", source);
        if (!(raw instanceof java.util.List<?> values)) throw stale();
        final var result = new LinkedHashMap<String, Object>();
        for (var form : values) {
            final Object guid = resolver.invoke("cubism.editor-history.semantic.form.guid", form);
            final Object value = resolver.invoke(M + "form-guid.value", guid);
            if (!(value instanceof String text) || result.putIfAbsent(text, form) != null) throw stale();
        }
        return result;
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("ArtMesh parent snapshot layout changed");
    }
}
