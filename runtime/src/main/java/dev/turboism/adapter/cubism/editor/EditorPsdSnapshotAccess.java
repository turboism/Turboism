package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorPsdSnapshotSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exact-version projection from Editor PSD resources to immutable Turboism snapshots. */
final class EditorPsdSnapshotAccess {

    private final VerifiedMemberResolver resolver;
    private final EditorObjectReadAccess.CurrentGuard currentGuard;

    EditorPsdSnapshotAccess(
        final VerifiedMemberResolver resolver,
        final EditorObjectReadAccess.CurrentGuard currentGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
    }

    List<PsdClipMaskDocumentSnapshot> snapshots(
        final String identity,
        final Object source,
        final Object model
    ) {
        requireAuthorized();
        currentGuard.requireCurrent(identity, model);
        final Map<String, List<ArtMeshId>> bindings = artMeshBindings(identity, source, model);
        final Object textureManager = resolver.invoke(
            "cubism.editor-model.model-source.texture-manager", source
        );
        final List<PsdClipMaskDocumentSnapshot> result = new ArrayList<>();
        for (Object wrapper : list(resolver.invoke(
            "cubism.editor-model.texture-manager.raw-images", textureManager
        ), "Editor raw image collection")) {
            final Object image = resolver.invoke(
                "cubism.editor-model.layered-image-wrapper.image", wrapper
            );
            if (!resolver.isInstance("cubism.editor-model.layered-image.class", image)) {
                continue;
            }
            final String documentId = guid(resolver.invoke(
                "cubism.editor-model.layered-image.guid", image
            ));
            final Object psdFile = resolver.invoke(
                "cubism.editor-model.layered-image.psd-file", image
            );
            final String fileName = psdFile instanceof File file ? file.getName() : String.valueOf(psdFile);
            final List<Object> roots = list(resolver.invoke(
                "cubism.editor-model.layered-image.children", image
            ), "Editor layered image children collection");
            result.add(new PsdClipMaskDocumentSnapshot(
                documentId,
                safeFileName(fileName),
                layers(roots, bindings)
            ));
        }
        currentGuard.requireCurrent(identity, model);
        return List.copyOf(result);
    }

    private Map<String, List<ArtMeshId>> artMeshBindings(
        final String identity,
        final Object source,
        final Object model
    ) {
        // Bindings are keyed by the verified layer GUID string, not by host
        // object identity, so distinct wrapper instances of the same layer
        // still resolve to the same stable identity.
        final Map<String, List<ArtMeshId>> bindings = new LinkedHashMap<>();
        final EditorObjectReadAccess objects = new EditorObjectReadAccess(
            resolver,
            currentGuard,
            new dev.turboism.adapter.cubism.editor.EditorMorphTargetAccess(
                resolver,
                (expectedIdentity, expectedModel) -> currentGuard.requireCurrent(expectedIdentity, expectedModel)
            ),
            null
        );
        for (var drawable : objects.drawables(identity, source, model).all()) {
            final Object hostSource = ((EditorNativeObjectRef) drawable).nativeSource();
            final Object extension = resolver.invoke(
                "cubism.editor-model.art-mesh-source.texture-input-extension", hostSource
            );
            // An ArtMesh without a PSD texture-input extension is not PSD-bound;
            // that is the genuinely optional case and is skipped.
            if (!resolver.isInstance("cubism.editor-model.texture-input-extension.class", extension)) {
                continue;
            }
            final Object input = resolver.invoke(
                "cubism.editor-model.texture-input-extension.model-image-input", extension
            );
            if (!resolver.isInstance("cubism.editor-model.texture-input-model-image.class", input)) {
                continue;
            }
            final Object modelImage = resolver.invoke(
                "cubism.editor-model.texture-input-model-image.model-image", input
            );
            if (!resolver.isInstance("cubism.editor-model.model-image.class", modelImage)) {
                continue;
            }
            final Object inputDataValue = resolver.invoke(
                "cubism.editor-model.model-image.current-layer-input-data", modelImage
            );
            // No layer input data is the genuinely optional case for a PSD-bound
            // ArtMesh without a layer binding; only a non-iterable non-null value
            // is a projection error.
            final List<Object> inputDataList = inputDataValue == null
                ? List.of()
                : list(inputDataValue, "Editor layer input data collection");
            for (Object inputData : inputDataList) {
                if (!resolver.isInstance("cubism.editor-model.layer-input-data.class", inputData)) {
                    throw new IllegalStateException(
                        "Editor layer input data collection contains an invalid value."
                    );
                }
                final Object layer = resolver.invoke(
                    "cubism.editor-model.layer-input-data.layer", inputData
                );
                if (layer == null) {
                    continue;
                }
                if (!resolver.isInstance("cubism.editor-model.layer-entry.class", layer)) {
                    throw new IllegalStateException("Editor layer binding is not a layer entry.");
                }
                bindings.computeIfAbsent(layerId(layer), ignored -> new ArrayList<>()).add(drawable.id());
            }
        }
        return bindings;
    }

    private List<PsdLayerSnapshot> layers(
        final List<Object> entries,
        final Map<String, List<ArtMeshId>> bindings
    ) {
        final List<PsdLayerSnapshot> result = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            final Object entry = entries.get(index);
            if (!resolver.isInstance("cubism.editor-model.layer-entry.class", entry)) {
                continue;
            }
            final boolean clipping = booleanValue(resolver.invoke(
                "cubism.editor-model.layer-entry.clipping", entry
            ));
            final Optional<String> base = clipping
                ? nearestBase(entries, index).map(this::layerId)
                : Optional.empty();
            final List<Object> children = resolver.isInstance("cubism.editor-model.layer-group.class", entry)
                ? list(resolver.invoke("cubism.editor-model.layer-group.children", entry), "Editor layer group children collection")
                : List.of();
            result.add(new PsdLayerSnapshot(
                layerId(entry),
                layerName(entry),
                booleanValue(resolver.invoke("cubism.editor-model.layer-entry.visible", entry)),
                clipping,
                deduplicate(bindings.getOrDefault(layerId(entry), List.of())),
                base,
                layers(children, bindings)
            ));
        }
        return List.copyOf(result);
    }

    private Optional<Object> nearestBase(final List<Object> entries, final int clippingIndex) {
        for (int index = clippingIndex + 1; index < entries.size(); index++) {
            final Object candidate = entries.get(index);
            if (candidate != null && !booleanValue(resolver.invoke(
                "cubism.editor-model.layer-entry.clipping", candidate
            ))) {
                return Optional.of(candidate);
            }
        }
        for (int index = clippingIndex - 1; index >= 0; index--) {
            final Object candidate = entries.get(index);
            if (candidate != null && !booleanValue(resolver.invoke(
                "cubism.editor-model.layer-entry.clipping", candidate
            ))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private String layerId(final Object entry) {
        return guid(resolver.invoke("cubism.editor-model.layer-entry.guid", entry));
    }

    private String layerName(final Object entry) {
        final Object value = resolver.invoke("cubism.editor-model.layer-entry.name", entry);
        final String name = value instanceof String text ? text.trim() : "";
        return name.isEmpty() ? layerId(entry) : name;
    }

    private String guid(final Object value) {
        final Object raw = resolver.invoke("cubism.editor-model.guid.value", value);
        final String text = raw instanceof String string ? string.trim() : "";
        if (text.isEmpty()) {
            throw new IllegalStateException("Verified PSD identity is unavailable.");
        }
        return text;
    }

    private void requireAuthorized() {
        if (!resolver.authorizesFeature(
            EditorPsdSnapshotSelectorContract.ADAPTER_SLICE_ID,
            EditorPsdSnapshotSelectorContract.CAPABILITY_ID,
            EditorPsdSnapshotSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "PSD snapshots are unavailable without exact verified host evidence."
            );
        }
    }

    private static List<ArtMeshId> deduplicate(final List<ArtMeshId> ids) {
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    private static String safeFileName(final String value) {
        final String name = value == null ? "" : new File(value).getName();
        return name.isBlank() ? "unknown.psd" : name;
    }

    private static boolean booleanValue(final Object value) {
        if (value instanceof Boolean result) {
            return result;
        }
        throw new IllegalStateException("Verified PSD boolean value is invalid.");
    }

    private static List<Object> list(final Object value, final String label) {
        if (value == null || !(value instanceof Iterable<?> iterable)) {
            throw new IllegalStateException(label + " must be an iterable.");
        }
        final List<Object> result = new ArrayList<>();
        iterable.forEach(result::add);
        return result;
    }
}
