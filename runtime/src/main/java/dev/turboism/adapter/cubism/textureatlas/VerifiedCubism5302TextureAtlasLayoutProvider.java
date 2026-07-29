package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exact Cubism 5.3.02 translator for complete texture-atlas authoring plans. */
public final class VerifiedCubism5302TextureAtlasLayoutProvider implements TextureAtlasLayoutProvider {

    private static final String VERSION = "5.3.02";
    private static final int DEFAULT_MAX_ATLAS_COUNT = 32;

    private final VerifiedMemberResolver resolver;
    @SuppressWarnings("unused") private final String sessionIdentity;
    private final IdentityHashMap<Object, Long> revisions = new IdentityHashMap<>();

    public VerifiedCubism5302TextureAtlasLayoutProvider(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sessionIdentity = requireText(sessionIdentity, "sessionIdentity");
    }

    @Override
    public Optional<TextureAtlasAuthoringState> current() {
        if (!available()) return Optional.empty();
        final Binding binding = binding();
        if (binding == null) return Optional.empty();
        return Optional.of(project(binding));
    }

    @Override
    public ApplyOutcome apply(final TextureAtlasAuthoringState expected, final TextureAtlasLayoutPlan plan) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(plan, "plan");
        if (!available()) return ApplyOutcome.REJECTED;
        final Binding binding = binding();
        if (binding == null || !sameBinding(expected, binding)) return ApplyOutcome.REJECTED;
        final TextureAtlasAuthoringState current = project(binding);
        if (!samePlanningState(expected, current)) return ApplyOutcome.REJECTED;
        if (plan.equals(current.currentPlan())) return ApplyOutcome.NO_CHANGE;

        final Map<String, Object> images = imagesById(binding.dataModel());
        final List<Object> staged = stage(plan, images);
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", binding.document()
        );
        final Object groupUndo = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, "Update TextureAtlas"
        );
        final Object undo = resolver.construct(
            "cubism.texture-atlas.undo.create",
            "Update TextureAtlas",
            binding.modelSource(),
            list(resolver.invoke("cubism.texture-atlas.data-model.atlases", binding.dataModel())),
            staged
        );
        resolver.invoke("cubism.texture-atlas.undo.force-redo", undo);
        resolver.invoke("cubism.texture-atlas.group-undo.add", groupUndo, undo);
        revisions.put(binding.dataModel(), current.revision() + 1);
        return ApplyOutcome.APPLIED;
    }

    private boolean available() {
        return resolver.isExactCubismVersion(VERSION)
            && resolver.authorizesFeature(
                VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                VerifiedCubism5302TextureAtlasSelectorContract.CAPABILITY_ID,
                VerifiedCubism5302TextureAtlasSelectorContract.REQUIRED_ALIASES
            );
    }

    private Binding binding() {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = app == null ? null : resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        if (!resolver.isInstance("cubism.editor-model.modeling-document.class", document)) return null;
        final Object source = resolver.invoke("cubism.editor-model.modeling-document.model-source", document);
        final Object guid = source == null ? null : resolver.invoke("cubism.editor-model.model-source.guid", source);
        final String modelId = text(guid == null ? null : resolver.invoke("cubism.editor-model.guid.value", guid));
        final String documentId = text(resolver.invoke("cubism.texture-atlas.document.id", document));
        final Object dataModel = resolver.invoke("cubism.texture-atlas.document.data-model", document);
        if (modelId == null || documentId == null
            || !resolver.isInstance("cubism.texture-atlas.data-model.class", dataModel)) {
            return null;
        }
        return new Binding(document, source, dataModel, documentId, modelId);
    }

    private TextureAtlasAuthoringState project(final Binding binding) {
        final List<?> atlases = list(resolver.invoke("cubism.texture-atlas.data-model.atlases", binding.dataModel()));
        final List<?> images = list(resolver.invoke("cubism.texture-atlas.data-model.images", binding.dataModel()));
        if (atlases.isEmpty()) throw new IllegalStateException("No verified texture atlas is available.");
        final String atlasId = atlases.stream()
            .map(atlas -> text(resolver.invoke("cubism.texture-atlas.atlas.name", atlas)))
            .filter(Objects::nonNull)
            .reduce((first, second) -> first + "\u001f" + second)
            .orElseThrow();
        final int atlasWidth = integer(resolver.invoke("cubism.texture-atlas.atlas.width", atlases.get(0)));
        final int atlasHeight = integer(resolver.invoke("cubism.texture-atlas.atlas.height", atlases.get(0)));
        final Map<Object, Integer> atlasIndexes = new IdentityHashMap<>();
        for (int index = 0; index < atlases.size(); index++) atlasIndexes.put(atlases.get(index), index);

        final Map<String, TextureAtlasLayoutItem> items = new HashMap<>();
        for (Object image : images) {
            if (!resolver.isInstance("cubism.texture-atlas.image.class", image)) continue;
            final String id = imageId(image);
            if (id != null) {
                items.put(id, new TextureAtlasLayoutItem(
                    id,
                    integer(resolver.invoke("cubism.texture-atlas.image.width", image)),
                    integer(resolver.invoke("cubism.texture-atlas.image.height", image))
                ));
            }
        }

        final List<TextureAtlasPlacement> placements = new ArrayList<>();
        for (Object atlas : atlases) {
            final int atlasIndex = atlasIndexes.get(atlas);
            for (Object entry : list(resolver.invoke("cubism.texture-atlas.atlas.entries", atlas))) {
                if (!resolver.isInstance("cubism.texture-atlas.entry.class", entry)) continue;
                final Object image = resolver.invoke("cubism.texture-atlas.entry.image", entry);
                final String id = imageId(image);
                final TextureAtlasLayoutItem item = items.get(id);
                if (item == null) continue;
                final Object transform = resolver.invoke("cubism.texture-atlas.entry.transform", entry);
                if (!resolver.isInstance("cubism.texture-atlas.affine.class", transform)) {
                    throw new IllegalStateException("Verified texture atlas transform is unavailable.");
                }
                final AffineTransform affine = (AffineTransform) transform;
                placements.add(new TextureAtlasPlacement(
                    id,
                    atlasIndex,
                    rounded(affine.getTranslateX()),
                    rounded(affine.getTranslateY()),
                    item.width(),
                    item.height(),
                    false
                ));
            }
        }
        placements.sort(java.util.Comparator.comparing(TextureAtlasPlacement::textureId));
        final TextureAtlasLayoutConstraints constraints = new TextureAtlasLayoutConstraints(
            atlasWidth,
            atlasHeight,
            0,
            0,
            DEFAULT_MAX_ATLAS_COUNT,
            false,
            false
        );
        return new TextureAtlasAuthoringState(
            binding.documentId(),
            binding.modelId(),
            atlasId,
            revisions.getOrDefault(binding.dataModel(), 0L),
            constraints,
            List.copyOf(items.values()).stream().sorted(java.util.Comparator.comparing(TextureAtlasLayoutItem::textureId)).toList(),
            new TextureAtlasLayoutPlan(atlasWidth, atlasHeight, atlases.size(), placements)
        );
    }

    private List<Object> stage(final TextureAtlasLayoutPlan plan, final Map<String, Object> images) {
        final List<Object> atlases = new ArrayList<>(plan.pageCount());
        for (int index = 0; index < plan.pageCount(); index++) {
            atlases.add(resolver.construct(
                "cubism.texture-atlas.atlas.create",
                "Turboism Atlas " + (index + 1),
                plan.pageWidth(),
                plan.pageHeight()
            ));
        }
        for (TextureAtlasPlacement placement : plan.placements()) {
            final Object image = images.get(placement.textureId());
            if (image == null || placement.pageIndex() >= atlases.size()) {
                throw new IllegalStateException("Texture atlas plan references an unavailable verified image.");
            }
            final Object affine = resolver.construct("cubism.texture-atlas.affine.create");
            resolver.invoke(
                "cubism.texture-atlas.affine.translate", affine,
                (double) placement.x(), (double) placement.y()
            );
            resolver.construct(
                "cubism.texture-atlas.entry.create",
                atlases.get(placement.pageIndex()),
                resolver.invoke("cubism.texture-atlas.image.guid", image),
                affine
            );
        }
        return List.copyOf(atlases);
    }

    private Map<String, Object> imagesById(final Object dataModel) {
        final Map<String, Object> result = new HashMap<>();
        for (Object image : list(resolver.invoke("cubism.texture-atlas.data-model.images", dataModel))) {
            final String id = imageId(image);
            if (id != null) result.put(id, image);
        }
        return result;
    }

    private String imageId(final Object image) {
        if (!resolver.isInstance("cubism.texture-atlas.image.class", image)) return null;
        final Object guid = resolver.invoke("cubism.texture-atlas.image.guid", image);
        return text(guid == null ? null : resolver.invoke("cubism.editor-model.guid.value", guid));
    }

    private boolean sameBinding(final TextureAtlasAuthoringState expected, final Binding current) {
        return expected.documentId().equals(current.documentId())
            && expected.modelId().equals(current.modelId());
    }

    private static boolean samePlanningState(
        final TextureAtlasAuthoringState expected,
        final TextureAtlasAuthoringState current
    ) {
        return expected.atlasId().equals(current.atlasId())
            && expected.revision() == current.revision()
            && expected.constraints().equals(current.constraints())
            && expected.items().equals(current.items())
            && expected.currentPlan().equals(current.currentPlan());
    }

    private static int rounded(final double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalStateException("Texture atlas translation is outside the supported range.");
        }
        return (int) Math.round(value);
    }

    private static int integer(final Object value) {
        if (!(value instanceof Number number)) throw new IllegalStateException("Verified texture atlas number is unavailable.");
        return number.intValue();
    }

    private static List<?> list(final Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalStateException("Verified texture atlas list is unavailable.");
        return list;
    }

    private static String text(final Object value) {
        if (!(value instanceof String text)) return null;
        final String normalized = text.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String requireText(final String value, final String name) {
        final String normalized = text(Objects.requireNonNull(value, name));
        if (normalized == null) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private record Binding(Object document, Object modelSource, Object dataModel, String documentId, String modelId) {
    }
}
