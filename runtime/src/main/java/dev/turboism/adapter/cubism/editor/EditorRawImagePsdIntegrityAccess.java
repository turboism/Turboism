package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Package-private export-fidelity observation for a typed {@code CLayeredImage}.
 *
 * <p>This compares native observations, not a user-edit validator. The 5.3.02
 * {@code save(File, Progress)} implementation rebuilds the PSD from the current layered-image
 * dimensions and root state; it does not require the source {@code psdDoc}. Its layer helper writes
 * names, visibility, PSD blend values, opacity, transparency-shape state, layer IDs from
 * {@code CLayerIdentifier.getLayerId()}, pixel bounds, and the current {@code CImageResource}
 * image. Editor {@code CLayerGuid}s are observed only: the reparsing constructor allocates fresh
 * editor GUIDs, and there is no verified evidence that those GUIDs are PSD-persisted identifiers.
 * A missing native layer ID is therefore retained as {@code null}, never replaced with an editor
 * GUID. Clipping is observed but remains unverified because the exact save helper does not read
 * {@code isClipping()}. Pixel values use one shared per-capture sample budget plus bounded
 * node and recursion-depth budgets. Cubism 5.3.02 exposes {@code CWritableImage.getIntBuffer()},
 * but its exact bytecode returns raw {@code DataBufferInt} storage or {@code null}; it does not
 * establish an ARGB-normalized, contiguous-row contract for every image type. This observer
 * therefore retains verified {@code getArgb()} reads with conservative budgets and does not
 * claim host-thread performance validation. This class does not expose host
 * objects or restrict edits made after export.</p>
 */
final class EditorRawImagePsdIntegrityAccess {
    static final long MAX_PIXEL_SAMPLES = 16_777_216L;
    static final int MAX_LAYER_NODES = 16_384;
    static final int MAX_TREE_DEPTH = 256;
    private static final String DIGEST_ALGORITHM = "SHA-256";

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
        final ObservationBudget budget = new ObservationBudget();
        final List<LayerNode> layers = readLayers(
            resolver.invoke("cubism.editor-model.layered-image.children", layeredImage),
            new IdentityHashMap<>(),
            budget,
            0
        );
        return new Snapshot(name, width, height, layers);
    }

    /**
     * Compares the pre-save and reparsed observations. A matching observation remains
     * {@link VerificationStatus#MATCHED_UNVERIFIED}: unsupported PSD features are not inferred.
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

        final Comparison comparison = new Comparison();
        compareLayerFacts(before.layers(), after.layers(), "root", comparison);

        final boolean psdLayerIdsVerified = comparison.verified(
            comparison.psdLayerIdsAny,
            comparison.psdLayerIdsComplete,
            comparison.psdLayerIdsMatch
        );
        final boolean pixelLayerBoundsVerified = comparison.verified(
            comparison.boundsAny,
            comparison.boundsComplete,
            comparison.boundsMatch
        );
        final boolean usablePixelsVerified = comparison.verified(
            comparison.pixelsAny,
            comparison.pixelsComplete,
            comparison.pixelsMatch
        );
        final boolean opacityVerified = comparison.verified(
            comparison.opacityAny,
            comparison.opacityComplete,
            comparison.opacityMatch
        );
        final boolean visibleVerified = comparison.verified(
            comparison.visibleAny,
            comparison.visibleComplete,
            comparison.visibleMatch
        );
        final boolean blendVerified = comparison.verified(
            comparison.blendAny,
            comparison.blendComplete,
            comparison.blendMatch
        );
        final boolean transparencyShapesVerified = comparison.verified(
            comparison.transparencyShapesAny,
            comparison.transparencyShapesComplete,
            comparison.transparencyShapesMatch
        );
        final boolean clippingObserved = comparison.clippingAny && comparison.clippingComplete;
        final boolean clippingMatches = clippingObserved && comparison.clippingMatch;
        final boolean structuralMatch = rootNameMatches && dimensionsMatch && layerTreeMatches;
        final boolean serializedObservationsMatch = comparison.differences.isEmpty();
        final VerificationStatus status = structuralMatch && serializedObservationsMatch
            ? VerificationStatus.MATCHED_UNVERIFIED
            : VerificationStatus.MISMATCH;
        return new Verification(
            status,
            rootNameMatches,
            dimensionsMatch,
            layerTreeMatches,
            editorLayerIdsObserved,
            comparison.psdLayerIdsAny,
            comparison.psdLayerIdsMatch,
            psdLayerIdsVerified,
            comparison.boundsAny,
            comparison.boundsMatch,
            pixelLayerBoundsVerified,
            comparison.pixelsAny,
            comparison.pixelsMatch,
            usablePixelsVerified,
            comparison.opacityAny,
            comparison.opacityMatch,
            opacityVerified,
            comparison.visibleAny,
            comparison.visibleMatch,
            visibleVerified,
            comparison.blendAny,
            comparison.blendMatch,
            blendVerified,
            comparison.transparencyShapesAny,
            comparison.transparencyShapesMatch,
            transparencyShapesVerified,
            clippingObserved,
            clippingMatches,
            false,
            blendVerified,
            detail(
                status,
                rootNameMatches,
                dimensionsMatch,
                layerTreeMatches,
                psdLayerIdsVerified,
                pixelLayerBoundsVerified,
                usablePixelsVerified,
                opacityVerified,
                visibleVerified,
                blendVerified,
                transparencyShapesVerified,
                clippingObserved,
                clippingMatches,
                comparison
            )
        );
    }

    private List<LayerNode> readLayers(
        final Object rawEntries,
        final IdentityHashMap<Object, Boolean> visited,
        final ObservationBudget budget,
        final int parentDepth
    ) {
        final List<?> entries = list(rawEntries, "layered image children");
        final ArrayList<LayerNode> values = new ArrayList<>();
        for (final Object entry : entries) {
            if (entry == null) throw unavailable("layered image contains a null layer entry");
            final int depth = parentDepth + 1;
            if (depth > MAX_TREE_DEPTH) {
                throw unavailable(
                    "layer tree depth " + depth + " exceeds capture limit of " + MAX_TREE_DEPTH
                );
            }
            if (!budget.tryVisitNode()) {
                throw unavailable(
                    "layer node budget exceeded at " + MAX_LAYER_NODES + " nodes; capture is unverified"
                );
            }
            if (visited.put(entry, Boolean.TRUE) != null) {
                throw unavailable("layered image layer tree contains a repeated or cyclic entry");
            }
            requireInstance("cubism.editor-model.layer-entry.class", entry, "layer entry");
            final String editorLayerGuid = editorLayerGuid(entry);
            final String name = string(
                resolver.invoke("cubism.editor-model.layer-entry.name", entry),
                "layer entry name"
            );
            final LayerAttributes attributes = readAttributes(entry);
            if (resolver.isInstance("cubism.editor-model.layer-group.class", entry)) {
                values.add(new LayerNode(
                    LayerKind.GROUP,
                    editorLayerGuid,
                    name,
                    readLayerId(
                        "cubism.editor-model.layer-group.layer-identifier",
                        entry,
                        "group"
                    ),
                    attributes,
                    BoundsObservation.notApplicable(),
                    PixelObservation.notApplicable(),
                    readLayers(
                        resolver.invoke("cubism.editor-model.layer-group.children", entry),
                        visited,
                        budget,
                        depth
                    )
                ));
            } else {
                requireInstance("cubism.editor-model.layer.class", entry, "pixel layer");
                values.add(new LayerNode(
                    LayerKind.PIXEL,
                    editorLayerGuid,
                    name,
                    readLayerId(
                        "cubism.editor-model.layer.layer-identifier",
                        entry,
                        "pixel layer"
                    ),
                    attributes,
                    readBounds(entry),
                    readPixels(entry, budget),
                    List.of()
                ));
            }
        }
        return List.copyOf(values);
    }

    private LayerIdObservation readLayerId(
        final String identifierAlias,
        final Object entry,
        final String label
    ) {
        try {
            final Object identifier = resolver.invoke(identifierAlias, entry);
            if (identifier == null) {
                return LayerIdObservation.unavailable(
                    label + " has no verified CLayerIdentifier; editor layer-entry GUID is not a fallback"
                );
            }
            requireInstance(
                "cubism.editor-model.layer-identifier.class",
                identifier,
                label + " layer identifier"
            );
            final Object rawId = resolver.invoke(
                "cubism.editor-model.layer-identifier.id",
                identifier
            );
            if (rawId == null) {
                return new LayerIdObservation(
                    true,
                    null,
                    "CLayerIdentifier.getLayerId() returned null; no editor layer-entry GUID fallback"
                );
            }
            if (!(rawId instanceof String id)) {
                return LayerIdObservation.unavailable(
                    label + " CLayerIdentifier.getLayerId() is not a String"
                );
            }
            return new LayerIdObservation(true, id, "CLayerIdentifier.getLayerId() observed");
        } catch (RuntimeException failure) {
            return LayerIdObservation.unavailable(
                label + " PSD layer ID observation failed: " + message(failure)
            );
        }
    }

    private LayerAttributes readAttributes(final Object entry) {
        try {
            final int opacity255 = integer(
                resolver.invoke("cubism.editor-model.layer-entry.opacity", entry),
                "layer opacity"
            );
            final boolean visible = bool(
                resolver.invoke("cubism.editor-model.layer-entry.visible", entry),
                "layer visibility"
            );
            final Object blend = resolver.invoke("cubism.editor-model.layer-entry.blend", entry);
            requireInstance("cubism.editor-model.blend.class", blend, "layer blend");
            final Object psdBlend = resolver.invoke(
                "cubism.editor-model.blend.psd-value",
                blend
            );
            requireInstance("cubism.editor-model.psd-blend.class", psdBlend, "PSD blend value");
            final String serializedBlendKey = string(
                resolver.invoke("cubism.editor-model.psd-blend.key", psdBlend),
                "PSD blend key"
            );
            final boolean clipping = bool(
                resolver.invoke("cubism.editor-model.layer-entry.clipping", entry),
                "layer clipping"
            );
            final boolean transparencyShapes = bool(
                resolver.invoke("cubism.editor-model.layer-entry.transparency-shapes", entry),
                "transparency-shape state"
            );
            return new LayerAttributes(
                true,
                opacity255,
                visible,
                serializedBlendKey,
                clipping,
                transparencyShapes,
                "all save-written layer attributes observed"
            );
        } catch (RuntimeException failure) {
            return LayerAttributes.unavailable(
                "save-written layer attributes could not be observed: " + message(failure)
            );
        }
    }

    private BoundsObservation readBounds(final Object layer) {
        try {
            final Object bounds = resolver.invoke("cubism.editor-model.layer.bounds", layer);
            if (bounds == null) {
                return BoundsObservation.unavailable("pixel layer bounds are null");
            }
            requireInstance("cubism.editor-model.rect.class", bounds, "pixel layer bounds");
            return new BoundsObservation(
                true,
                true,
                integer(resolver.invoke("cubism.editor-model.rect.x", bounds), "bounds x"),
                integer(resolver.invoke("cubism.editor-model.rect.y", bounds), "bounds y"),
                integer(resolver.invoke("cubism.editor-model.rect.width", bounds), "bounds width"),
                integer(resolver.invoke("cubism.editor-model.rect.height", bounds), "bounds height"),
                "CRect bounds observed"
            );
        } catch (RuntimeException failure) {
            return BoundsObservation.unavailable(
                "pixel layer bounds could not be observed: " + message(failure)
            );
        }
    }

    private PixelObservation readPixels(final Object layer, final ObservationBudget budget) {
        try {
            if (budget.pixelBudgetExhausted()) {
                return PixelObservation.unavailable(
                    "capture pixel budget was already exhausted; ARGB reads were skipped"
                );
            }
            final Object resource = resolver.invoke(
                "cubism.editor-model.layer.image-resource",
                layer
            );
            if (resource == null) {
                return PixelObservation.unavailable("pixel layer image resource is null");
            }
            requireInstance(
                "cubism.editor-model.image-resource.class",
                resource,
                "pixel layer image resource"
            );
            final Object image = resolver.invoke(
                "cubism.editor-model.image-resource.image",
                resource
            );
            if (image == null) {
                return PixelObservation.unavailable("CImageResource.getImage() returned null");
            }
            requireInstance("cubism.editor-model.writable-image.class", image, "writable pixel image");
            final int width = dimension(
                resolver.invoke("cubism.editor-model.writable-image.width", image),
                "writable image width"
            );
            final int height = dimension(
                resolver.invoke("cubism.editor-model.writable-image.height", image),
                "writable image height"
            );
            final long sampleCount;
            try {
                sampleCount = Math.multiplyExact((long) width, (long) height);
            } catch (ArithmeticException failure) {
                return PixelObservation.unavailable(
                    "ARGB scan size overflowed the bounded pixel observer"
                );
            }
            if (!budget.tryReservePixels(sampleCount)) {
                return PixelObservation.unavailable(width, height, budget.pixelBudgetDetail());
            }
            final MessageDigest digest = messageDigest();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int argb = integer(
                        resolver.invoke(
                            "cubism.editor-model.writable-image.argb",
                            image,
                            x,
                            y
                        ),
                        "ARGB pixel"
                    );
                    digest.update((byte) (argb >>> 24));
                    digest.update((byte) (argb >>> 16));
                    digest.update((byte) (argb >>> 8));
                    digest.update((byte) argb);
                }
            }
            return new PixelObservation(
                true,
                true,
                width,
                height,
                hex(digest.digest()),
                sampleCount,
                "row-major ARGB digest observed from CImageResource.getImage()"
            );
        } catch (RuntimeException failure) {
            return PixelObservation.unavailable(
                "current CImageResource.getImage() pixels could not be observed: " + message(failure)
            );
        }
    }

    private String editorLayerGuid(final Object entry) {
        try {
            final Object guid = resolver.invoke("cubism.editor-model.layer-entry.guid", entry);
            if (guid == null) return null;
            final Object raw = resolver.invoke("cubism.editor-model.guid.value", guid);
            return raw instanceof String text && !text.isBlank() ? text : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void compareLayerFacts(
        final List<LayerNode> before,
        final List<LayerNode> after,
        final String path,
        final Comparison comparison
    ) {
        final int commonSize = Math.min(before.size(), after.size());
        for (int index = 0; index < commonSize; index++) {
            final LayerNode left = before.get(index);
            final LayerNode right = after.get(index);
            final String nodePath = path + "[" + index + "]";
            if (left.kind() != right.kind()) continue;
            compareLayerId(left.psdLayerId(), right.psdLayerId(), nodePath, comparison);
            compareAttributes(left.attributes(), right.attributes(), nodePath, comparison);
            if (left.kind() == LayerKind.PIXEL) {
                compareBounds(left.bounds(), right.bounds(), nodePath, comparison);
                comparePixels(left.pixels(), right.pixels(), nodePath, comparison);
            }
            compareLayerFacts(left.children(), right.children(), nodePath, comparison);
        }
    }

    private static void compareLayerId(
        final LayerIdObservation left,
        final LayerIdObservation right,
        final String path,
        final Comparison comparison
    ) {
        comparison.psdLayerIdsAny = true;
        if (!left.observed() || !right.observed()) {
            comparison.psdLayerIdsComplete = false;
            comparison.blocker(path + " PSD layer ID: " + firstUnavailable(left, right));
            return;
        }
        if (!Objects.equals(left.id(), right.id())) {
            comparison.psdLayerIdsMatch = false;
            comparison.difference(path + " PSD layer ID");
        }
    }

    private static void compareAttributes(
        final LayerAttributes left,
        final LayerAttributes right,
        final String path,
        final Comparison comparison
    ) {
        comparison.opacityAny = true;
        comparison.visibleAny = true;
        comparison.blendAny = true;
        comparison.transparencyShapesAny = true;
        comparison.clippingAny = true;
        if (!left.observed() || !right.observed()) {
            comparison.opacityComplete = false;
            comparison.visibleComplete = false;
            comparison.blendComplete = false;
            comparison.transparencyShapesComplete = false;
            comparison.clippingComplete = false;
            comparison.blocker(path + " layer attributes: " + firstUnavailable(left, right));
            return;
        }
        if (left.opacity255() != right.opacity255()) {
            comparison.opacityMatch = false;
            comparison.difference(path + " opacity255");
        }
        if (left.visible() != right.visible()) {
            comparison.visibleMatch = false;
            comparison.difference(path + " visibility");
        }
        if (!Objects.equals(left.serializedBlendKey(), right.serializedBlendKey())) {
            comparison.blendMatch = false;
            comparison.difference(path + " PSD blend value");
        }
        if (left.transparencyShapes() != right.transparencyShapes()) {
            comparison.transparencyShapesMatch = false;
            comparison.difference(path + " transparency-shape state");
        }
        if (left.clipping() != right.clipping()) {
            comparison.clippingMatch = false;
            comparison.note(path + " clipping observation differs; exact save bytecode does not serialize isClipping()");
        }
    }

    private static void compareBounds(
        final BoundsObservation left,
        final BoundsObservation right,
        final String path,
        final Comparison comparison
    ) {
        comparison.boundsAny = true;
        if (!left.applicable() || !right.applicable()) {
            comparison.boundsComplete = false;
            comparison.blocker(path + " pixel bounds applicability differs");
            return;
        }
        if (!left.observed() || !right.observed()) {
            comparison.boundsComplete = false;
            comparison.blocker(path + " pixel bounds: " + firstUnavailable(left, right));
            return;
        }
        if (left.x() != right.x()
            || left.y() != right.y()
            || left.width() != right.width()
            || left.height() != right.height()) {
            comparison.boundsMatch = false;
            comparison.difference(path + " pixel bounds");
        }
    }

    private static void comparePixels(
        final PixelObservation left,
        final PixelObservation right,
        final String path,
        final Comparison comparison
    ) {
        comparison.pixelsAny = true;
        if (!left.applicable() || !right.applicable()) {
            comparison.pixelsComplete = false;
            comparison.blocker(path + " pixel observation applicability differs");
            return;
        }
        if (!left.observed() || !right.observed()) {
            comparison.pixelsComplete = false;
            comparison.blocker(path + " usable pixels: " + firstUnavailable(left, right));
            return;
        }
        if (left.width() != right.width()
            || left.height() != right.height()
            || !Objects.equals(left.digest(), right.digest())) {
            comparison.pixelsMatch = false;
            comparison.difference(path + " usable ARGB pixels");
        }
    }

    private static boolean sameTree(final List<LayerNode> before, final List<LayerNode> after) {
        if (before.size() != after.size()) return false;
        for (int index = 0; index < before.size(); index++) {
            final LayerNode left = before.get(index);
            final LayerNode right = after.get(index);
            if (left.kind() != right.kind() || !left.name().equals(right.name())) return false;
            if (!sameTree(left.children(), right.children())) return false;
        }
        return true;
    }

    private static boolean hasEditorLayerIds(final List<LayerNode> layers) {
        for (LayerNode layer : layers) {
            if (layer.editorLayerGuid() == null || layer.editorLayerGuid().isBlank()) return false;
            if (!hasEditorLayerIds(layer.children())) return false;
        }
        return true;
    }

    private static String detail(
        final VerificationStatus status,
        final boolean rootNameMatches,
        final boolean dimensionsMatch,
        final boolean layerTreeMatches,
        final boolean psdLayerIdsVerified,
        final boolean pixelLayerBoundsVerified,
        final boolean usablePixelsVerified,
        final boolean opacityVerified,
        final boolean visibleVerified,
        final boolean blendVerified,
        final boolean transparencyShapesVerified,
        final boolean clippingObserved,
        final boolean clippingMatches,
        final Comparison comparison
    ) {
        final ArrayList<String> validated = new ArrayList<>();
        if (rootNameMatches) validated.add("root name");
        if (dimensionsMatch) validated.add("canvas dimensions");
        if (layerTreeMatches) validated.add("ordered layer/group tree and layer names");
        if (psdLayerIdsVerified) validated.add("PSD layer IDs via CLayerIdentifier.getLayerId (null preserved without GUID fallback)");
        if (pixelLayerBoundsVerified) validated.add("pixel layer bounds");
        if (usablePixelsVerified) validated.add("bounded row-major ARGB pixel digests");
        if (opacityVerified) validated.add("opacity255");
        if (visibleVerified) validated.add("visibility");
        if (blendVerified) validated.add("serialized PSD blend value");
        if (transparencyShapesVerified) validated.add("transparency-shape state");

        final ArrayList<String> unverified = new ArrayList<>();
        if (!psdLayerIdsVerified) unverified.add("PSD layer IDs");
        if (!pixelLayerBoundsVerified) unverified.add("pixel layer bounds");
        if (!usablePixelsVerified) unverified.add("usable pixels");
        if (!opacityVerified) unverified.add("opacity255");
        if (!visibleVerified) unverified.add("visibility");
        if (!blendVerified) unverified.add("serialized PSD blend value");
        if (!transparencyShapesVerified) unverified.add("transparency-shape state");
        unverified.add(
            clippingObserved
                ? "clipping state (observed only; exact save bytecode does not read isClipping())"
                : "clipping state (not observed; exact save bytecode does not read isClipping())"
        );
        unverified.add("unexposed special PSD features");

        final ArrayList<String> facts = new ArrayList<>();
        if (!rootNameMatches) facts.add("root name");
        if (!dimensionsMatch) facts.add("canvas dimensions");
        if (!layerTreeMatches) facts.add("ordered layer/group tree or layer names");
        if (!comparison.differences.isEmpty()) {
            facts.add("mismatches: " + String.join(", ", comparison.differences));
        }
        if (!comparison.blockers.isEmpty()) {
            facts.add("observation gaps: " + String.join(", ", comparison.blockers));
        }
        if (!comparison.notes.isEmpty()) {
            facts.add("notes: " + String.join(", ", comparison.notes));
        }
        return status == VerificationStatus.MISMATCH
            ? "export observations differ; " + String.join("; ", facts)
                + "; Editor layer-entry GUIDs were observed for diagnostics only and were not compared; reparsing allocates fresh GUIDs"
                + "; validated " + joinOrNone(validated)
                + "; unverified " + joinOrNone(unverified) + " remain unverified"
            : "validated " + joinOrNone(validated)
                + "; Editor layer-entry GUIDs were observed for diagnostics only and were not compared; reparsing allocates fresh GUIDs"
                + "; clipping matches=" + clippingMatches
                + "; unverified " + joinOrNone(unverified) + " remain unverified"
                + (facts.isEmpty() ? "" : "; " + String.join("; ", facts));
    }

    private static String joinOrNone(final List<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    private static String firstUnavailable(final Observation left, final Observation right) {
        if (!left.observed()) return left.detail();
        if (!right.observed()) return right.detail();
        return "observation values were not comparable";
    }

    private void requireInstance(final String alias, final Object value, final String label) {
        if (!resolver.isInstance(alias, value)) {
            throw unavailable(label + " has an invalid verified host type");
        }
    }

    private static List<?> list(final Object value, final String label) {
        if (!(value instanceof List<?> values)) throw unavailable(label + " is unavailable");
        // Do not copy an unbounded host collection before the shared node budget can stop traversal.
        return values;
    }

    private static String string(final Object value, final String label) {
        if (!(value instanceof String text)) throw unavailable(label + " is invalid");
        return text;
    }

    private static int integer(final Object value, final String label) {
        if (!(value instanceof Integer number)) throw unavailable(label + " is invalid");
        return number;
    }

    private static boolean bool(final Object value, final String label) {
        if (!(value instanceof Boolean flag)) throw unavailable(label + " is invalid");
        return flag;
    }

    private static int dimension(final Object value, final String label) {
        final int number = integer(value, label);
        if (number < 0) throw unavailable(label + " is negative");
        return number;
    }

    private static MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("required digest algorithm is unavailable", failure);
        }
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder result = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    private static String message(final Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static IllegalStateException unavailable(final String detail) {
        return new IllegalStateException(detail);
    }

    private static final class ObservationBudget {
        private int nodes;
        private long pixels;
        private boolean pixelBudgetExhausted;

        boolean tryVisitNode() {
            if (nodes >= MAX_LAYER_NODES) return false;
            nodes++;
            return true;
        }

        boolean tryReservePixels(final long requested) {
            if (pixelBudgetExhausted || requested < 0
                || requested > MAX_PIXEL_SAMPLES - pixels) {
                pixelBudgetExhausted = true;
                return false;
            }
            pixels += requested;
            return true;
        }

        boolean pixelBudgetExhausted() {
            return pixelBudgetExhausted;
        }

        String pixelBudgetDetail() {
            return "capture pixel budget exceeded: requested pixels would pass the shared limit of "
                + MAX_PIXEL_SAMPLES + "; ARGB reads were skipped"
                + " (already reserved " + pixels + ")";
        }
    }

    private interface Observation {
        boolean observed();

        String detail();
    }

    record Snapshot(String name, int width, int height, List<LayerNode> layers) {
        Snapshot {
            name = Objects.requireNonNull(name, "name");
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("snapshot dimensions must be non-negative");
            }
            layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
        }
    }

    record LayerNode(
        LayerKind kind,
        String editorLayerGuid,
        String name,
        LayerIdObservation psdLayerId,
        LayerAttributes attributes,
        BoundsObservation bounds,
        PixelObservation pixels,
        List<LayerNode> children
    ) {
        LayerNode {
            kind = Objects.requireNonNull(kind, "kind");
            name = Objects.requireNonNull(name, "name");
            psdLayerId = Objects.requireNonNull(psdLayerId, "psdLayerId");
            attributes = Objects.requireNonNull(attributes, "attributes");
            bounds = Objects.requireNonNull(bounds, "bounds");
            pixels = Objects.requireNonNull(pixels, "pixels");
            children = List.copyOf(Objects.requireNonNull(children, "children"));
            if (kind == LayerKind.PIXEL && !children.isEmpty()) {
                throw new IllegalArgumentException("pixel layer cannot have children");
            }
        }
    }

    record LayerIdObservation(boolean observed, String id, String detail) implements Observation {
        LayerIdObservation {
            detail = Objects.requireNonNull(detail, "detail");
        }

        static LayerIdObservation unavailable(final String detail) {
            return new LayerIdObservation(false, null, detail);
        }
    }

    record LayerAttributes(
        boolean observed,
        int opacity255,
        boolean visible,
        String serializedBlendKey,
        boolean clipping,
        boolean transparencyShapes,
        String detail
    ) implements Observation {
        LayerAttributes {
            detail = Objects.requireNonNull(detail, "detail");
        }

        static LayerAttributes unavailable(final String detail) {
            return new LayerAttributes(false, 0, false, null, false, false, detail);
        }
    }

    record BoundsObservation(
        boolean applicable,
        boolean observed,
        int x,
        int y,
        int width,
        int height,
        String detail
    ) implements Observation {
        BoundsObservation {
            detail = Objects.requireNonNull(detail, "detail");
            if (width < 0 || height < 0) throw new IllegalArgumentException("bounds dimensions must be non-negative");
        }

        static BoundsObservation notApplicable() {
            return new BoundsObservation(false, true, 0, 0, 0, 0, "not a pixel layer");
        }

        static BoundsObservation unavailable(final String detail) {
            return new BoundsObservation(true, false, 0, 0, 0, 0, detail);
        }
    }

    record PixelObservation(
        boolean applicable,
        boolean observed,
        int width,
        int height,
        String digest,
        long pixelsCompared,
        String detail
    ) implements Observation {
        PixelObservation {
            detail = Objects.requireNonNull(detail, "detail");
            if (width < 0 || height < 0) throw new IllegalArgumentException("pixel dimensions must be non-negative");
            if (pixelsCompared < 0) throw new IllegalArgumentException("pixels compared must be non-negative");
        }

        static PixelObservation notApplicable() {
            return new PixelObservation(false, true, 0, 0, null, 0, "not a pixel layer");
        }

        static PixelObservation unavailable(final String detail) {
            return unavailable(0, 0, detail);
        }

        static PixelObservation unavailable(final int width, final int height, final String detail) {
            return new PixelObservation(true, false, width, height, null, 0, detail);
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
        boolean psdLayerIdsObserved,
        boolean psdLayerIdsMatch,
        boolean psdLayerIdsVerified,
        boolean pixelLayerBoundsObserved,
        boolean pixelLayerBoundsMatch,
        boolean pixelLayerBoundsVerified,
        boolean usablePixelsObserved,
        boolean usablePixelsMatch,
        boolean usablePixelsVerified,
        boolean opacityObserved,
        boolean opacityMatch,
        boolean opacityVerified,
        boolean visibleObserved,
        boolean visibleMatch,
        boolean visibleVerified,
        boolean blendObserved,
        boolean blendMatch,
        boolean blendVerified,
        boolean transparencyShapesObserved,
        boolean transparencyShapesMatch,
        boolean transparencyShapesVerified,
        boolean clippingObserved,
        boolean clippingMatch,
        boolean clippingVerified,
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
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
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

    private static final class Comparison {
        boolean psdLayerIdsAny;
        boolean psdLayerIdsComplete = true;
        boolean psdLayerIdsMatch = true;
        boolean boundsAny;
        boolean boundsComplete = true;
        boolean boundsMatch = true;
        boolean pixelsAny;
        boolean pixelsComplete = true;
        boolean pixelsMatch = true;
        boolean opacityAny;
        boolean opacityComplete = true;
        boolean opacityMatch = true;
        boolean visibleAny;
        boolean visibleComplete = true;
        boolean visibleMatch = true;
        boolean blendAny;
        boolean blendComplete = true;
        boolean blendMatch = true;
        boolean transparencyShapesAny;
        boolean transparencyShapesComplete = true;
        boolean transparencyShapesMatch = true;
        boolean clippingAny;
        boolean clippingComplete = true;
        boolean clippingMatch = true;
        final List<String> differences = new ArrayList<>();
        final List<String> blockers = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        boolean verified(final boolean any, final boolean complete, final boolean match) {
            return any && complete && match;
        }

        void difference(final String value) {
            if (!differences.contains(value)) differences.add(value);
        }

        void blocker(final String value) {
            if (!blockers.contains(value)) blockers.add(value);
        }

        void note(final String value) {
            if (!notes.contains(value)) notes.add(value);
        }
    }
}
