package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact-version adapter for one temporary Cubism packing invocation. */
final class VerifiedTextureAtlasNativeInvocationAdapter {

    static final String CAPABILITY_ID = "cubism.texture-atlas.native-layout-invocation";
    static final String RECEIVER_CLASS = "cubism.texture-atlas.native.receiver.class";
    static final String RECEIVER_SETTINGS = "cubism.texture-atlas.native.receiver.settings";
    static final String RECEIVER_DATA = "cubism.texture-atlas.native.receiver.data";
    static final String RECEIVER_OVERFLOW = "cubism.texture-atlas.native.receiver.overflow";
    static final String SETTINGS_MARGIN = "cubism.texture-atlas.native.settings.margin";
    static final String SETTINGS_ROTATE = "cubism.texture-atlas.native.settings.rotate";
    static final String SETTINGS_MODEL_IMAGE = "cubism.texture-atlas.native.settings.model-image";
    static final String SETTINGS_SCALE = "cubism.texture-atlas.native.settings.scale";
    static final String DATA_ITEMS = "cubism.texture-atlas.native.data.items";
    static final String DATA_WIDTH = "cubism.texture-atlas.native.data.width";
    static final String DATA_HEIGHT = "cubism.texture-atlas.native.data.height";
    static final String DATA_SCALE = "cubism.texture-atlas.native.data.scale";
    static final String DATA_CURRENT_SCALE = "cubism.texture-atlas.native.data.current-scale";
    static final String DATA_IMPL = "cubism.texture-atlas.native.data.impl";
    static final String IMPL_CONTAINER = "cubism.texture-atlas.native.impl.container";
    static final String CONTAINER_CHILDREN = "cubism.texture-atlas.native.container.children";
    static final String ITEM_SCALE = "cubism.texture-atlas.native.item.scale";
    static final String ITEM_RECT = "cubism.texture-atlas.native.item.rect";
    static final String ITEM_MODEL_RECT = "cubism.texture-atlas.native.item.model-rect";
    static final String ITEM_WIDTH = "cubism.texture-atlas.native.item.width";
    static final String ITEM_HEIGHT = "cubism.texture-atlas.native.item.height";
    static final String ITEM_TRANSFORM = "cubism.texture-atlas.native.item.transform";
    static final String ITEM_EDIT_LAYER = "cubism.texture-atlas.native.item.edit-layer";
    static final String ITEM_CURRENT_TRANSFORM = "cubism.texture-atlas.native.item.current-transform";
    static final String RECT_X = "cubism.texture-atlas.native.rect.x";
    static final String RECT_Y = "cubism.texture-atlas.native.rect.y";
    static final String RECT_WIDTH = "cubism.texture-atlas.native.rect.width";
    static final String RECT_HEIGHT = "cubism.texture-atlas.native.rect.height";
    static final String AFFINE_CREATE = "cubism.texture-atlas.native.affine.create";
    static final String LAYER_REF_LAYER = "cubism.texture-atlas.native.layer-ref.layer";
    static final String LAYER_REF_TRANSFORM = "cubism.texture-atlas.native.layer-ref.transform";
    static final String LAYER_REF_SET_TRANSFORM = "cubism.texture-atlas.native.layer-ref.set-transform";
    static final String EDITOR_AFFINE_CREATE = "cubism.texture-atlas.native.editor-affine.create";
    static final String DIALOG_CLASS = "cubism.texture-atlas.dialog.class";
    static final String DIALOG_INIT = "cubism.texture-atlas.dialog.init";
    static final String STATISTICS_VIEW_INIT = "cubism.texture-atlas.statistics.view-init";
    static final String STATISTICS_VIEW_DATA_MODEL = "cubism.texture-atlas.statistics.view-data-model";
    static final String STATISTICS_DATA_MODEL_CURRENT_PAGE = "cubism.texture-atlas.statistics.data-model.current-page";
    static final String STATISTICS_PAGE_STATE_ATLAS = "cubism.texture-atlas.statistics.page-state.atlas";

    private final VerifiedMemberResolver resolver;

    VerifiedTextureAtlasNativeInvocationAdapter(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    TextureAtlasNativeInvocationCoordinator.Invocation open(
        final Object ownerToken,
        final long generation,
        final Object receiver,
        final Thread thread
    ) {
        if (!resolver.isInstance(RECEIVER_CLASS, receiver)) {
            throw new IllegalArgumentException("Native texture atlas receiver is unavailable.");
        }
        final Object settings = resolver.readField(RECEIVER_SETTINGS, receiver);
        final Object data = resolver.readField(RECEIVER_DATA, receiver);
        final Object overflowRaw = resolver.readField(RECEIVER_OVERFLOW, receiver);
        final List<?> nativeItems = list(resolver.invoke(DATA_ITEMS, data));
        final boolean modelImage = bool(resolver.invoke(SETTINGS_MODEL_IMAGE, settings));
        final double requestedScale = number(resolver.invoke(SETTINGS_SCALE, settings)).doubleValue();
        if (!Double.isFinite(requestedScale)) throw new IllegalArgumentException("Invalid native scale.");
        final boolean allowRotation = bool(resolver.invoke(SETTINGS_ROTATE, settings));
        final int width = integer(resolver.invoke(DATA_WIDTH, data));
        final int height = integer(resolver.invoke(DATA_HEIGHT, data));
        final int margin = integer(resolver.invoke(SETTINGS_MARGIN, settings));
        final LinkedHashMap<String, Object> byId = new LinkedHashMap<>();
        final IdentityHashMap<Object, Object> originalTransforms = new IdentityHashMap<>();
        final LinkedHashMap<String, SourceRect> sourceRects = new LinkedHashMap<>();
        int index = 0;
        for (Object item : nativeItems) {
            // Native wrapper.c() is per-item packing scale q, not the old atlas/LayerRef scale.
            // In reviewed Cubism 5.2.03, 5.3.02 and 5.3.03, the normal native auto-layout
            // entry creates fresh wrappers whose constructor fixes q to exactly 1; that
            // path does not change q. Automatic/fixed output scale s (e.g. 0.5 or 0.97)
            // and pre-existing LayerRef scaling do NOT make q non-unit.
            // This guard protects against external mutation or a future changed contract,
            // not a known normal UI case in those versions. Native packing reads q, but
            // output matrices do not simply multiply by q. For unsupported q, decline the
            // whole invocation before callbacks/writes; the coordinator returns false so
            // native layout runs with untouched state. Do not extrapolate to future versions.
            final double itemScale = number(resolver.invoke(ITEM_SCALE, item)).doubleValue();
            if (!Double.isFinite(itemScale) || itemScale != 1D) {
                throw new IllegalArgumentException("Unsupported native per-item packing scale (q): " + itemScale);
            }
            final SourceRect rect = sourceRect(resolver, item, modelImage);
            final String id = "native-item-" + index++;
            byId.put(id, item);
            originalTransforms.put(item, copyTransform(resolver.readField(ITEM_CURRENT_TRANSFORM, item)));
            sourceRects.put(id, rect);
        }
        if (byId.isEmpty()) throw new IllegalArgumentException("Native texture atlas invocation has no items.");
        final List<Object> overflow = mutableList(overflowRaw);
        final double originalScale = number(resolver.readField(DATA_CURRENT_SCALE, data)).doubleValue();
        final Object impl = resolver.invoke(DATA_IMPL, data);
        final Object container = resolver.invoke(IMPL_CONTAINER, impl);
        final Object childrenRaw = resolver.invoke(CONTAINER_CHILDREN, container);
        final Object[] children = childrenRaw instanceof Object[] array ? array : new Object[0];
        final IdentityHashMap<Object, Object> originalLayerTransforms = new IdentityHashMap<>();
        for (Object child : children) {
            originalLayerTransforms.put(child, copyTransform(resolver.invoke(LAYER_REF_TRANSFORM, child)));
        }
        final Session session = new Session(
            resolver, data, modelImage, width, height, margin, Math.max(0D, requestedScale), allowRotation, byId, sourceRects,
            overflow, List.copyOf(overflow), originalTransforms, originalScale,
            children, originalLayerTransforms
        );
        return new TextureAtlasNativeInvocationCoordinator.Invocation(
            ownerToken, generation, receiver, thread, session
        );
    }

    static final class Session {
        private final VerifiedMemberResolver resolver;
        private final Object data;
        private final boolean modelImage;
        private final int width;
        private final int height;
        private final int margin;
        private final double requestedScale;
        private final boolean allowRotation;
        private final LinkedHashMap<String, Object> byId;
        private final Map<String, SourceRect> sourceRects;
        private final List<Object> overflow;
        private final List<Object> originalOverflow;
        private final IdentityHashMap<Object, Object> originalTransforms;
        private final double originalScale;
        private final IdentityHashMap<Object, Object> layerByItem = new IdentityHashMap<>();
        private final java.util.Set<Object> touchedItems = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final java.util.Set<Object> touchedLayers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final IdentityHashMap<Object, Object> originalLayerTransforms;
        private boolean mutated;

        Session(
            final VerifiedMemberResolver resolver,
            final Object data,
            final boolean modelImage,
            final int width,
            final int height,
            final int margin,
            final double requestedScale,
            final boolean allowRotation,
            final LinkedHashMap<String, Object> byId,
            final Map<String, SourceRect> sourceRects,
            final List<Object> overflow,
            final List<Object> originalOverflow,
            final IdentityHashMap<Object, Object> originalTransforms,
            final double originalScale,
            final Object[] children,
            final IdentityHashMap<Object, Object> originalLayerTransforms
        ) {
            this.resolver = resolver;
            this.data = data;
            this.modelImage = modelImage;
            this.width = width;
            this.height = height;
            this.margin = margin;
            this.requestedScale = requestedScale;
            this.allowRotation = allowRotation;
            this.byId = byId;
            this.sourceRects = Map.copyOf(sourceRects);
            this.overflow = overflow;
            this.originalOverflow = originalOverflow;
            this.originalTransforms = originalTransforms;
            this.originalScale = originalScale;
            final IdentityHashMap<Object, Object> refsByLayer = new IdentityHashMap<>();
            for (Object child : children) {
                final Object layer = resolver.invoke(LAYER_REF_LAYER, child);
                if (refsByLayer.put(layer, child) != null) throw new IllegalArgumentException("Ambiguous native layer reference.");
            }
            for (Object item : byId.values()) {
                final Object ref = refsByLayer.get(resolver.invoke(ITEM_EDIT_LAYER, item));
                if (ref == null) throw new IllegalArgumentException("Native item has no visual layer reference.");
                layerByItem.put(item, ref);
            }
            this.originalLayerTransforms = originalLayerTransforms;
        }

        TextureAtlasAuthoringState state() {
            final List<TextureAtlasLayoutItem> items = new ArrayList<>();
            for (Map.Entry<String, Object> entry : byId.entrySet()) {
                final SourceRect rect = sourceRects.get(entry.getKey());
                final int itemWidth = roundedUp(rect.width());
                final int itemHeight = roundedUp(rect.height());
                items.add(new TextureAtlasLayoutItem(entry.getKey(), itemWidth, itemHeight));
            }
            return new TextureAtlasAuthoringState(
                "native-invocation", "native-model", "native-atlas", 0,
                TextureAtlasLayoutConstraints.currentPage(width, height, margin, allowRotation, requestedScale),
                items,
                new TextureAtlasLayoutPlan(width, height, 1, List.of())
            );
        }

        TextureAtlasLayoutProvider.ApplyOutcome apply(final TextureAtlasLayoutPlan plan) {
            final Map<String, TextureAtlasPlacement> placements = new LinkedHashMap<>();
            for (TextureAtlasPlacement placement : plan.placements()) placements.put(placement.textureId(), placement);
            if (plan.pageCount() != 1 || !byId.keySet().containsAll(placements.keySet())) {
                return TextureAtlasLayoutProvider.ApplyOutcome.REJECTED;
            }
            final IdentityHashMap<Object, Object> staged = new IdentityHashMap<>();
            final List<Object> stagedOverflow = new ArrayList<>();
            for (Map.Entry<String, Object> entry : byId.entrySet()) {
                final TextureAtlasPlacement placement = placements.get(entry.getKey());
                if (placement != null && placement.rotated() && !allowRotation) return TextureAtlasLayoutProvider.ApplyOutcome.REJECTED;
                if (placement != null) {
                    final Object item = entry.getValue();
                    final SourceRect rect = sourceRects.get(entry.getKey());
                    final double sourceX = rect.x();
                    final double sourceY = rect.y();
                    final double sourceHeight = rect.height();
                    final double scale = plan.scale();
                    final AffineTransform transform = placement.rotated()
                        ? new AffineTransform(0, scale, -scale, 0,
                            placement.x() + scale * (sourceY + sourceHeight), placement.y() - scale * sourceX)
                        : new AffineTransform(scale, 0, 0, scale,
                            placement.x() - scale * sourceX, placement.y() - scale * sourceY);
                    if (!Double.isFinite(transform.getDeterminant()) || transform.getDeterminant() <= 9.99999993922529e-9
                        || !sameTransform(transform, transform)) return TextureAtlasLayoutProvider.ApplyOutcome.REJECTED;
                    staged.put(item, resolver.construct(AFFINE_CREATE, transform));
                } else {
                    stagedOverflow.add(entry.getValue());
                }
            }
            try {
                mutated = true;
                for (Map.Entry<Object, Object> entry : staged.entrySet()) {
                    touchedItems.add(entry.getKey());
                    resolver.invoke(ITEM_TRANSFORM, entry.getKey(), entry.getValue());
                    updateLayer(entry.getKey(), entry.getValue());
                }
                overflow.clear();
                overflow.addAll(stagedOverflow);
                resolver.invoke(DATA_SCALE, data, plan.scale());
                return same(staged, stagedOverflow, plan.scale())
                    ? TextureAtlasLayoutProvider.ApplyOutcome.APPLIED
                    : TextureAtlasLayoutProvider.ApplyOutcome.REJECTED;
            } catch (RuntimeException failure) {
                restore();
                return TextureAtlasLayoutProvider.ApplyOutcome.REJECTED;
            }
        }

        void restore() {
            if (!mutated) return;
            RuntimeException failure = null;
            for (Map.Entry<Object, Object> entry : originalTransforms.entrySet()) {
                if (!touchedItems.contains(entry.getKey())) continue;
                failure = restoreStep(failure, () -> resolver.invoke(ITEM_TRANSFORM, entry.getKey(),
                    restoredTransform(AFFINE_CREATE, entry.getValue())));
            }
            for (Map.Entry<Object, Object> entry : originalLayerTransforms.entrySet()) {
                if (!touchedLayers.contains(entry.getKey())) continue;
                failure = restoreStep(failure, () -> resolver.invoke(LAYER_REF_SET_TRANSFORM, entry.getKey(),
                    restoredTransform(EDITOR_AFFINE_CREATE, entry.getValue())));
            }
            failure = restoreStep(failure, () -> resolver.invoke(DATA_SCALE, data, originalScale));
            failure = restoreStep(failure, () -> {
                overflow.clear();
                overflow.addAll(originalOverflow);
            });
            // A host setter failure must not prevent restoring the remaining outputs.
            // Keep the mutation marker on failure so coordinator cleanup can retry.
            if (failure != null) throw failure;
            mutated = false;
        }

        private static RuntimeException restoreStep(RuntimeException previous, Runnable restore) {
            try {
                restore.run();
            } catch (RuntimeException failure) {
                if (previous == null) return failure;
                if (previous != failure) previous.addSuppressed(failure);
            }
            return previous;
        }

        private Object restoredTransform(final String constructor, final Object value) {
            return value == null
                ? null
                : resolver.construct(constructor, copyTransform(value));
        }

        private void updateLayer(final Object item, final Object affine) {
            final Object child = layerByItem.get(item);
            touchedLayers.add(child);
            resolver.invoke(LAYER_REF_SET_TRANSFORM, child,
                resolver.construct(EDITOR_AFFINE_CREATE, copyTransform(affine)));
        }

        private boolean same(final IdentityHashMap<Object, Object> staged, final List<Object> stagedOverflow,
            final double expectedScale) {
            if (!overflow.equals(stagedOverflow)
                || number(resolver.readField(DATA_CURRENT_SCALE, data)).doubleValue() != expectedScale) return false;
            for (Map.Entry<Object, Object> entry : staged.entrySet()) {
                if (!sameTransform(entry.getValue(), resolver.readField(ITEM_CURRENT_TRANSFORM, entry.getKey()))
                    || !sameTransform(entry.getValue(), resolver.invoke(LAYER_REF_TRANSFORM, layerByItem.get(entry.getKey())))) return false;
            }
            return true;
        }
    }

    private static boolean sameTransform(final Object expected, final Object actual) {
        if (!(expected instanceof AffineTransform left) || !(actual instanceof AffineTransform right)) return false;
        final double[] a = new double[6], b = new double[6];
        left.getMatrix(a);
        right.getMatrix(b);
        for (int i = 0; i < a.length; i++) {
            if (!Double.isFinite(a[i]) || !Double.isFinite(b[i])
                || Math.abs(a[i] - b[i]) > 1e-8 + 32D * Math.max(Math.ulp(a[i]), Math.ulp(b[i]))) return false;
        }
        return true;
    }

    private static SourceRect sourceRect(VerifiedMemberResolver resolver, Object item, boolean modelImage) {
        if (modelImage) {
            // ITEM_MODEL_RECT returns CRect, not GRectF. Do not invoke GRectF getters on it.
            return new SourceRect(0, 0, number(resolver.invoke(ITEM_WIDTH, item)).doubleValue(),
                number(resolver.invoke(ITEM_HEIGHT, item)).doubleValue());
        }
        final Object rect = resolver.invoke(ITEM_RECT, item);
        return new SourceRect(number(resolver.invoke(RECT_X, rect)).doubleValue(),
            number(resolver.invoke(RECT_Y, rect)).doubleValue(),
            number(resolver.invoke(RECT_WIDTH, rect)).doubleValue(),
            number(resolver.invoke(RECT_HEIGHT, rect)).doubleValue());
    }

    private record SourceRect(double x, double y, double width, double height) {
        SourceRect {
            if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Invalid native origin.");
            roundedUp(width);
            roundedUp(height);
        }
    }

    private static Object copyTransform(final Object value) {
        return value instanceof AffineTransform transform ? new AffineTransform(transform) : value;
    }

    private static int roundedUp(final Object value) {
        final double number = number(value).doubleValue();
        if (!Double.isFinite(number) || number <= 0 || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Native texture atlas dimension is invalid.");
        }
        return Math.max(1, (int) Math.ceil(number));
    }

    private static int integer(final Object value) { return number(value).intValue(); }
    private static Number number(final Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Native number is unavailable.");
        return number;
    }
    private static boolean bool(final Object value) {
        if (!(value instanceof Boolean booleanValue)) throw new IllegalArgumentException("Native boolean is unavailable.");
        return booleanValue;
    }
    private static List<?> list(final Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Native list is unavailable.");
        return list;
    }
    @SuppressWarnings("unchecked")
    private static List<Object> mutableList(final Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Native mutable list is unavailable.");
        return (List<Object>) list;
    }
}
