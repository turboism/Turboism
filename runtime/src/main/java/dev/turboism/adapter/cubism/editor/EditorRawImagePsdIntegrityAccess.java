package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Package-private export-fidelity observation for a typed {@code CLayeredImage}.
 *
 * <p>This is deliberately a comparison of observations, not a user-edit validator. The root
 * layered-image GUID is not read or compared: a reparsed {@code CLayeredImage} is expected to
 * have a different host object identity. The 5.3.02 {@code save(File, Progress)} bytecode rebuilds
 * the output from width, height, and root-layer state without reading source {@code psdDoc}; its
 * layer helper writes PSD layer identifiers from {@code CLayerIdentifier.getLayerId()}, not from
 * editor {@code CLayerEntry.guid}. The {@code ACLayerEntry(CLayeredImage)} constructor also
 * allocates a fresh {@code CLayerGuid}, so editor layer-entry GUIDs are observations only. There
 * is no verified evidence in this slice that those GUIDs are written into a PSD and preserved by
 * reparsing, so regenerated GUIDs must not gate a structural match. The selectors do not expose a
 * verified PSD layer ID, per-pixel-layer bounds, usable-pixel buffer, or special-blend fidelity,
 * so those dimensions remain explicitly unverified.</p>
 */
final class EditorRawImagePsdIntegrityAccess {
    private final VerifiedMemberResolver resolver;

    EditorRawImagePsdIntegrityAccess(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Captures one typed layered-image observation on the host thread; source PSD metadata is optional. */
    Snapshot captureOnHostThread(final Object layeredImage) {
        if (!EditorHostThread.isCurrent()) {
            throw new IllegalStateException("PSD integrity capture must run on the Editor host thread");
        }
        Objects.requireNonNull(layeredImage, "layeredImage");
        requireInstance(
            "cubism.editor-model.layered-image.class",
            layeredImage,
            "layered image"
        );
        final String name = string(
            resolver.invoke("cubism.editor-model.layered-image.name", layeredImage),
            "layered image name"
        );
        final int width = dimension(
            resolver.invoke("cubism.editor-model.layered-image.width", layeredImage),
            "layered image width"
        );
        final int height = dimension(
            resolver.invoke("cubism.editor-model.layered-image.height", layeredImage),
            "layered image height"
        );
        final List<LayerNode> layers = readLayers(
            resolver.invoke("cubism.editor-model.layered-image.children", layeredImage),
            new IdentityHashMap<>(),
            new HashSet<>()
        );
        return new Snapshot(name, width, height, layers);
    }

    /**
     * Compares the pre-save and reparsed observations. A structural match is still
     * {@link VerificationStatus#MATCHED_UNVERIFIED}; it never certifies PSD pixel fidelity.
     */
    Verification verify(final Snapshot before, final Snapshot after) {
        if (before == null || after == null) {
            return Verification.unavailable("PSD integrity comparison lacks a before or after snapshot");
        }
        final boolean rootNameMatches = before.name().equals(after.name());
        final boolean dimensionsMatch = before.width() == after.width()
            && before.height() == after.height();
        final boolean layerTreeMatches = sameTree(before.layers(), after.layers());
        final boolean editorLayerIdsObserved = hasEditorLayerIds(before.layers())
            && hasEditorLayerIds(after.layers());
        final boolean editorLayerIdsMatch = editorLayerIdsObserved
            && sameEditorLayerIds(before.layers(), after.layers());
        final boolean structuralMatch = rootNameMatches
            && dimensionsMatch
            && layerTreeMatches;
        final String detail = structuralMatch
            ? "verified root name, canvas dimensions, ordered layer/group tree, and layer names; "
                + "Editor layer-entry GUIDs were observed but not treated as persisted-PSD equality; "
                + "PSD layer IDs, pixel bounds, usable pixels, and special-blend fidelity remain unverified"
            : mismatchDetail(rootNameMatches, dimensionsMatch, layerTreeMatches);
        return new Verification(
            structuralMatch ? VerificationStatus.MATCHED_UNVERIFIED : VerificationStatus.MISMATCH,
            rootNameMatches,
            dimensionsMatch,
            layerTreeMatches,
            editorLayerIdsObserved,
            editorLayerIdsMatch,
            false,
            false,
            false,
            false,
            detail
        );
    }

    private List<LayerNode> readLayers(
        final Object rawEntries,
        final IdentityHashMap<Object, Boolean> visited,
        final Set<String> layerIds
    ) {
        final List<?> entries = list(rawEntries, "layered image children");
        final ArrayList<LayerNode> values = new ArrayList<>(entries.size());
        for (final Object entry : entries) {
            if (entry == null) throw unavailable("layered image contains a null layer entry");
            if (visited.put(entry, Boolean.TRUE) != null) {
                throw unavailable("layered image layer tree contains a repeated or cyclic entry");
            }
            requireInstance("cubism.editor-model.layer-entry.class", entry, "layer entry");
            final String editorLayerGuid = guidValue(
                resolver.invoke("cubism.editor-model.layer-entry.guid", entry),
                "layer entry"
            );
            if (!layerIds.add(editorLayerGuid)) {
                throw unavailable("layered image layer-entry GUIDs are not unique");
            }
            final String name = string(
                resolver.invoke("cubism.editor-model.layer-entry.name", entry),
                "layer entry name"
            );
            if (resolver.isInstance("cubism.editor-model.layer-group.class", entry)) {
                values.add(new LayerNode(
                    LayerKind.GROUP,
                    editorLayerGuid,
                    name,
                    readLayers(
                        resolver.invoke("cubism.editor-model.layer-group.children", entry),
                        visited,
                        layerIds
                    )
                ));
            } else {
                values.add(new LayerNode(LayerKind.PIXEL, editorLayerGuid, name, List.of()));
            }
        }
        return List.copyOf(values);
    }

    private static boolean sameTree(
        final List<LayerNode> before,
        final List<LayerNode> after
    ) {
        if (before.size() != after.size()) return false;
        for (int index = 0; index < before.size(); index++) {
            final LayerNode left = before.get(index);
            final LayerNode right = after.get(index);
            if (left.kind() != right.kind() || !left.name().equals(right.name())) return false;
            if (!sameTree(left.children(), right.children())) return false;
        }
        return true;
    }

    private static boolean sameEditorLayerIds(
        final List<LayerNode> before,
        final List<LayerNode> after
    ) {
        if (before.size() != after.size()) return false;
        for (int index = 0; index < before.size(); index++) {
            final LayerNode left = before.get(index);
            final LayerNode right = after.get(index);
            if (!left.editorLayerGuid().equals(right.editorLayerGuid())) return false;
            if (!sameEditorLayerIds(left.children(), right.children())) return false;
        }
        return true;
    }

    private static boolean hasEditorLayerIds(final List<LayerNode> layers) {
        for (LayerNode layer : layers) {
            if (layer.editorLayerGuid().isBlank() || !hasEditorLayerIds(layer.children())) return false;
        }
        return true;
    }

    private static String mismatchDetail(
        final boolean rootNameMatches,
        final boolean dimensionsMatch,
        final boolean layerTreeMatches
    ) {
        final ArrayList<String> mismatches = new ArrayList<>();
        if (!rootNameMatches) mismatches.add("root name");
        if (!dimensionsMatch) mismatches.add("canvas dimensions");
        if (!layerTreeMatches) mismatches.add("ordered layer/group tree or layer names");
        return "export observations differ in " + String.join(", ", mismatches)
            + "; Editor layer-entry GUIDs are observations only; PSD layer IDs, pixel bounds, usable pixels, "
            + "and special-blend fidelity remain unverified";
    }

    private void requireInstance(final String alias, final Object value, final String label) {
        if (!resolver.isInstance(alias, value)) {
            throw unavailable(label + " has an invalid verified host type");
        }
    }

    private String guidValue(final Object guid, final String label) {
        if (guid == null) throw unavailable(label + " GUID is unavailable");
        final Object raw = resolver.invoke("cubism.editor-model.guid.value", guid);
        if (!(raw instanceof String text) || text.isBlank()) throw unavailable(label + " GUID is invalid");
        return text;
    }

    private static List<?> list(final Object value, final String label) {
        if (!(value instanceof List<?> values)) throw unavailable(label + " is unavailable");
        try {
            return List.copyOf(values);
        } catch (NullPointerException failure) {
            throw unavailable(label + " contains null");
        }
    }

    private static String string(final Object value, final String label) {
        if (!(value instanceof String text)) throw unavailable(label + " is invalid");
        return text;
    }

    private static int dimension(final Object value, final String label) {
        if (!(value instanceof Integer number) || number < 0) throw unavailable(label + " is invalid");
        return number;
    }

    private static IllegalStateException unavailable(final String detail) {
        return new IllegalStateException(detail);
    }

    record Snapshot(String name, int width, int height, List<LayerNode> layers) {
        Snapshot {
            name = Objects.requireNonNull(name, "name");
            if (width < 0 || height < 0) throw new IllegalArgumentException("snapshot dimensions must be non-negative");
            layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
        }
    }

    record LayerNode(LayerKind kind, String editorLayerGuid, String name, List<LayerNode> children) {
        LayerNode {
            kind = Objects.requireNonNull(kind, "kind");
            editorLayerGuid = Objects.requireNonNull(editorLayerGuid, "editorLayerGuid");
            name = Objects.requireNonNull(name, "name");
            children = List.copyOf(Objects.requireNonNull(children, "children"));
            if (kind == LayerKind.PIXEL && !children.isEmpty()) {
                throw new IllegalArgumentException("pixel layer cannot have children");
            }
        }
    }

    enum LayerKind {
        PIXEL,
        GROUP
    }

    enum VerificationStatus {
        MATCHED_UNVERIFIED,
        MISMATCH,
        UNAVAILABLE
    }

    record Verification(
        VerificationStatus status,
        boolean rootNameMatches,
        boolean dimensionsMatch,
        boolean layerTreeMatches,
        boolean editorLayerIdsObserved,
        boolean editorLayerIdsMatch,
        boolean psdLayerIdsVerified,
        boolean pixelLayerBoundsVerified,
        boolean usablePixelsVerified,
        boolean specialBlendVerified,
        String detail
    ) {
        Verification {
            status = Objects.requireNonNull(status, "status");
            detail = Objects.requireNonNull(detail, "detail");
        }

        static Verification unavailable(final String detail) {
            return new Verification(
                VerificationStatus.UNAVAILABLE,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                detail
            );
        }
    }
}
