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
        if (modelImage) {
            throw new IllegalArgumentException("Native model-image texture layout is not supported.");
        }
        final double requestedScale = number(resolver.invoke(SETTINGS_SCALE, settings)).doubleValue();
        if (requestedScale > 0D && Math.abs(requestedScale - 1D) > 1e-9) {
            throw new IllegalArgumentException("Native texture atlas scaling is not supported.");
        }
        final int width = integer(resolver.invoke(DATA_WIDTH, data));
        final int height = integer(resolver.invoke(DATA_HEIGHT, data));
        final int margin = integer(resolver.invoke(SETTINGS_MARGIN, settings));
        final LinkedHashMap<String, Object> byId = new LinkedHashMap<>();
        final IdentityHashMap<Object, Object> originalTransforms = new IdentityHashMap<>();
        final List<TextureAtlasLayoutItem> items = new ArrayList<>();
        int index = 0;
        for (Object item : nativeItems) {
            final Object rect = resolver.invoke(ITEM_RECT, item);
            final int itemWidth = roundedUp(resolver.invoke(RECT_WIDTH, rect));
            final int itemHeight = roundedUp(resolver.invoke(RECT_HEIGHT, rect));
            final String id = "native-item-" + index++;
            byId.put(id, item);
            originalTransforms.put(item, copyTransform(resolver.readField(ITEM_CURRENT_TRANSFORM, item)));
            items.add(new TextureAtlasLayoutItem(id, itemWidth, itemHeight));
        }
        if (items.isEmpty()) throw new IllegalArgumentException("Native texture atlas invocation has no items.");
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
            resolver, data, modelImage, width, height, margin, byId,
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
        private final LinkedHashMap<String, Object> byId;
        private final List<Object> overflow;
        private final List<Object> originalOverflow;
        private final IdentityHashMap<Object, Object> originalTransforms;
        private final double originalScale;
        private final Object[] children;
        private final IdentityHashMap<Object, Object> originalLayerTransforms;
        private boolean mutated;

        Session(
            final VerifiedMemberResolver resolver,
            final Object data,
            final boolean modelImage,
            final int width,
            final int height,
            final int margin,
            final LinkedHashMap<String, Object> byId,
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
            this.byId = byId;
            this.overflow = overflow;
            this.originalOverflow = originalOverflow;
            this.originalTransforms = originalTransforms;
            this.originalScale = originalScale;
            this.children = children;
            this.originalLayerTransforms = originalLayerTransforms;
        }

        TextureAtlasAuthoringState state() {
            final List<TextureAtlasLayoutItem> items = new ArrayList<>();
            for (Map.Entry<String, Object> entry : byId.entrySet()) {
                final Object rect = resolver.invoke(ITEM_RECT, entry.getValue());
                final int itemWidth = roundedUp(resolver.invoke(RECT_WIDTH, rect));
                final int itemHeight = roundedUp(resolver.invoke(RECT_HEIGHT, rect));
                items.add(new TextureAtlasLayoutItem(entry.getKey(), itemWidth, itemHeight));
            }
            return new TextureAtlasAuthoringState(
                "native-invocation", "native-model", "native-atlas", 0,
                new TextureAtlasLayoutConstraints(width, height, margin, margin, 32, false, false),
                items,
                new TextureAtlasLayoutPlan(width, height, 1, List.of())
            );
        }

        TextureAtlasLayoutProvider.ApplyOutcome apply(final TextureAtlasLayoutPlan plan) {
            final Map<String, TextureAtlasPlacement> placements = new LinkedHashMap<>();
            for (TextureAtlasPlacement placement : plan.placements()) placements.put(placement.textureId(), placement);
            if (!placements.keySet().equals(byId.keySet())) return TextureAtlasLayoutProvider.ApplyOutcome.REJECTED;
            final IdentityHashMap<Object, Object> staged = new IdentityHashMap<>();
            final List<Object> stagedOverflow = new ArrayList<>();
            for (Map.Entry<String, Object> entry : byId.entrySet()) {
                final TextureAtlasPlacement placement = placements.get(entry.getKey());
                if (placement.rotated()) return TextureAtlasLayoutProvider.ApplyOutcome.REJECTED;
                if (placement.pageIndex() == 0) {
                    final Object item = entry.getValue();
                    final Object rect = resolver.invoke(ITEM_RECT, item);
                    final double sourceX = number(resolver.invoke(RECT_X, rect)).doubleValue();
                    final double sourceY = number(resolver.invoke(RECT_Y, rect)).doubleValue();
                    final AffineTransform transform = AffineTransform.getTranslateInstance(
                        placement.x() - sourceX,
                        placement.y() - sourceY
                    );
                    staged.put(item, resolver.construct(AFFINE_CREATE, transform));
                } else {
                    stagedOverflow.add(entry.getValue());
                }
            }
            try {
                mutated = true;
                for (Map.Entry<Object, Object> entry : staged.entrySet()) {
                    resolver.invoke(ITEM_TRANSFORM, entry.getKey(), entry.getValue());
                    updateLayer(entry.getKey(), entry.getValue());
                }
                overflow.clear();
                overflow.addAll(stagedOverflow);
                resolver.invoke(DATA_SCALE, data, 1D);
                return same(staged, stagedOverflow)
                    ? TextureAtlasLayoutProvider.ApplyOutcome.APPLIED
                    : TextureAtlasLayoutProvider.ApplyOutcome.REJECTED;
            } catch (RuntimeException failure) {
                restore();
                return TextureAtlasLayoutProvider.ApplyOutcome.REJECTED;
            }
        }

        void restore() {
            if (!mutated) return;
            for (Map.Entry<Object, Object> entry : originalTransforms.entrySet()) {
                resolver.invoke(
                    ITEM_TRANSFORM,
                    entry.getKey(),
                    resolver.construct(AFFINE_CREATE, copyTransform(entry.getValue()))
                );
            }
            for (Map.Entry<Object, Object> entry : originalLayerTransforms.entrySet()) {
                resolver.invoke(
                    LAYER_REF_SET_TRANSFORM,
                    entry.getKey(),
                    resolver.construct(EDITOR_AFFINE_CREATE, copyTransform(entry.getValue()))
                );
            }
            resolver.invoke(DATA_SCALE, data, originalScale);
            overflow.clear();
            overflow.addAll(originalOverflow);
            mutated = false;
        }

        private void updateLayer(final Object item, final Object affine) {
            final Object editLayer = resolver.invoke(ITEM_EDIT_LAYER, item);
            for (Object child : children) {
                if (resolver.invoke(LAYER_REF_LAYER, child) == editLayer) {
                    resolver.invoke(
                        LAYER_REF_SET_TRANSFORM,
                        child,
                        resolver.construct(EDITOR_AFFINE_CREATE, copyTransform(affine))
                    );
                    return;
                }
            }
        }

        private boolean same(final IdentityHashMap<Object, Object> staged, final List<Object> stagedOverflow) {
            if (!overflow.equals(stagedOverflow)) return false;
            for (Map.Entry<Object, Object> entry : staged.entrySet()) {
                if (!entry.getValue().equals(resolver.readField(ITEM_CURRENT_TRANSFORM, entry.getKey()))) return false;
            }
            return true;
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
