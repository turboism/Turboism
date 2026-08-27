package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutProvider.ApplyOutcome;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Shared verified texture-atlas projection and transaction engine. */
final class VerifiedTextureAtlasLayoutProviderEngine {

    private static final int DEFAULT_MAX_ATLAS_COUNT = 32;

    private final VerifiedMemberResolver resolver;
    private final String sessionIdentity;
    private final TextureAtlasDataModelCapture capture;
    private final String exactVersion;
    private final String adapterSliceId;
    private final String capabilityId;
    private final Set<String> requiredAliases;
    private final IdentityHashMap<Object, Long> revisions = new IdentityHashMap<>();

    VerifiedTextureAtlasLayoutProviderEngine(
        final VerifiedMemberResolver resolver,
        final String sessionIdentity,
        final TextureAtlasDataModelCapture capture,
        final String exactVersion,
        final String adapterSliceId,
        final String capabilityId,
        final Set<String> requiredAliases
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sessionIdentity = requireText(sessionIdentity, "sessionIdentity");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.exactVersion = requireText(exactVersion, "exactVersion");
        this.adapterSliceId = requireText(adapterSliceId, "adapterSliceId");
        this.capabilityId = requireText(capabilityId, "capabilityId");
        this.requiredAliases = Set.copyOf(requiredAliases);
    }

    Optional<TextureAtlasAuthoringState> current() {
        if (!available()) return Optional.empty();
        final Binding binding = binding();
        if (binding == null) return Optional.empty();
        return Optional.of(project(binding));
    }

    ApplyOutcome apply(final TextureAtlasAuthoringState expected, final TextureAtlasLayoutPlan plan) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(plan, "plan");
        if (!available()) return ApplyOutcome.REJECTED;
        final Binding binding = binding();
        if (binding == null || !sameBinding(expected, binding)) return ApplyOutcome.REJECTED;
        final TextureAtlasAuthoringState current = project(binding);
        if (!samePlanningState(expected, current)) return ApplyOutcome.REJECTED;
        if (plan.equals(current.currentPlan())) return ApplyOutcome.NO_CHANGE;

        final Map<String, Object> images = imagesById(binding.textureManager());
        final List<Object> staged = stage(plan, images, binding.modelSource());
        final Object app = resolver.invokeStatic(
            "cubism.editor-model.app-controller.instance"
        );
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", binding.document()
        );
        final Object groupUndo = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, "Update TextureAtlas"
        );
        boolean completed = false;
        try {
            final Object undo = resolver.construct(
                "cubism.texture-atlas.undo.create",
                "Update TextureAtlas",
                binding.modelSource(),
                atlases(binding.textureManager()),
                staged
            );
            resolver.invoke("cubism.texture-atlas.undo.force-redo", undo);
            resolver.invoke("cubism.texture-atlas.group-undo.add", groupUndo, undo);
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    relinkTextureInputs(binding.modelSource(), binding.textureManager());
                    refresh(binding.modelSource(), completePack);
                    return null;
                }
            );
            final Object listenerAccepted = resolver.invoke(
                "cubism.editor-model.undo.add-listener", undo, listener
            );
            if (!(listenerAccepted instanceof Boolean accepted) || !accepted) {
                throw new IllegalStateException(
                    "Cubism rejected the texture-atlas Undo/Redo refresh listener."
                );
            }
            relinkTextureInputs(binding.modelSource(), binding.textureManager());
            refresh(binding.modelSource(), completePack);
            resolver.invoke(
                "cubism.editor-model.modeling-document.mark-dirty", binding.document()
            );
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
        }
        revisions.put(binding.dataModel(), current.revision() + 1);
        return ApplyOutcome.APPLIED;
    }

    private boolean available() {
        return resolver.isExactCubismVersion(exactVersion)
            && resolver.authorizesFeature(adapterSliceId, capabilityId, requiredAliases);
    }

    private Binding binding() {
        final Object dataModel = capture.current().orElse(null);
        if (!resolver.isInstance("cubism.texture-atlas.data-model.class", dataModel)) return null;
        final Object document = resolver.invoke("cubism.texture-atlas.data-model.document", dataModel);
        final Object source = resolver.invoke("cubism.texture-atlas.data-model.model-source", dataModel);
        if (!resolver.isInstance("cubism.editor-model.modeling-document.class", document)
            || source == null) {
            return null;
        }
        final Object app = resolver.invokeStatic(
            "cubism.editor-model.app-controller.instance"
        );
        final Object activeDocument = app == null ? null : resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        if (activeDocument != document || resolver.invoke(
            "cubism.editor-model.modeling-document.model-source", document
        ) != source) {
            return null;
        }
        final Object guid = resolver.invoke("cubism.editor-model.model-source.guid", source);
        final String modelId = text(guid == null ? null : resolver.invoke("cubism.editor-model.guid.value", guid));
        final Object textureManager = resolver.invoke("cubism.texture-atlas.model-source.texture-manager", source);
        if (modelId == null || textureManager == null) return null;
        return new Binding(document, source, dataModel, textureManager, sessionIdentity, modelId);
    }

    private TextureAtlasAuthoringState project(final Binding binding) {
        final List<?> atlases = atlases(binding.textureManager());
        final Map<String, Object> images = imagesById(binding.textureManager());
        if (atlases.isEmpty()) throw new IllegalStateException("No verified texture atlas is available.");
        final List<String> atlasNames = atlases.stream()
            .map(atlas -> text(resolver.invoke("cubism.texture-atlas.atlas.name", atlas)))
            .map(name -> Objects.requireNonNull(
                name, "Verified texture atlas name is unavailable."
            ))
            .toList();
        final String atlasId = String.join("\u001f", atlasNames);
        final int atlasWidth = integer(resolver.invoke("cubism.texture-atlas.atlas.width", atlases.get(0)));
        final int atlasHeight = integer(resolver.invoke("cubism.texture-atlas.atlas.height", atlases.get(0)));
        final Map<Object, Integer> atlasIndexes = new IdentityHashMap<>();
        for (int index = 0; index < atlases.size(); index++) atlasIndexes.put(atlases.get(index), index);

        final Map<String, TextureAtlasLayoutItem> items = new HashMap<>();
        for (Map.Entry<String, Object> image : images.entrySet()) {
            items.put(image.getKey(), new TextureAtlasLayoutItem(
                image.getKey(),
                integer(resolver.invoke("cubism.texture-atlas.image.width", image.getValue())),
                integer(resolver.invoke("cubism.texture-atlas.image.height", image.getValue()))
            ));
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
            new TextureAtlasLayoutPlan(
                atlasWidth, atlasHeight, atlases.size(), atlasNames, placements
            )
        );
    }

    private List<Object> stage(
        final TextureAtlasLayoutPlan plan,
        final Map<String, Object> images,
        final Object modelSource
    ) {
        final List<Object> atlases = new ArrayList<>(plan.pageCount());
        for (int index = 0; index < plan.pageCount(); index++) {
            atlases.add(resolver.construct(
                "cubism.texture-atlas.atlas.create",
                modelSource,
                pageName(plan, index),
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
                (float) placement.x(), (float) placement.y()
            );
            final Object atlas = atlases.get(placement.pageIndex());
            final Object entry = resolver.construct(
                "cubism.texture-atlas.entry.create",
                atlas,
                resolver.invoke("cubism.texture-atlas.image.guid", image),
                affine
            );
            append(
                resolver.invoke("cubism.texture-atlas.atlas.entries", atlas),
                entry
            );
        }
        return List.copyOf(atlases);
    }

    private static String pageName(final TextureAtlasLayoutPlan plan, final int index) {
        return plan.pageNames().isEmpty()
            ? "Turboism Atlas " + (index + 1)
            : plan.pageNames().get(index);
    }

    private Map<String, Object> imagesById(final Object textureManager) {
        final Object handler = resolver.invoke(
            "cubism.editor-model.texture-manager.handler", textureManager
        );
        final Map<?, ?> drawableUses = map(resolver.invoke(
            "cubism.texture-atlas.texture-manager-handler.drawable-uses", handler
        ));
        final Map<String, Object> result = new HashMap<>();
        for (Object image : list(resolver.invoke(
            "cubism.texture-atlas.texture-manager.images", textureManager
        ))) {
            final Object guid = resolver.invoke("cubism.texture-atlas.image.guid", image);
            if (!hasDrawableUse(drawableUses.get(guid))) continue;
            final String id = imageId(image);
            if (id != null) result.put(id, image);
        }
        return result;
    }

    private static boolean hasDrawableUse(final Object value) {
        return value instanceof Set<?> uses && !uses.isEmpty();
    }

    private List<?> atlases(final Object textureManager) {
        return list(resolver.invoke(
            "cubism.texture-atlas.texture-manager.atlases", textureManager
        ));
    }

    private void relinkTextureInputs(
        final Object modelSource,
        final Object textureManager
    ) {
        final Object helper = resolver.readStaticField(
            "cubism.texture-atlas.texture-input-relink.helper-instance"
        );
        resolver.invoke(
            "cubism.texture-atlas.texture-input-relink.rebuild",
            helper,
            modelSource
        );
        resolver.invokeStatic(
            "cubism.editor-model.model-source.verify",
            modelSource,
            Boolean.TRUE,
            null,
            2,
            null
        );
        resolver.invoke(
            "cubism.texture-atlas.texture-manager.change-input-to-atlas",
            textureManager
        );
    }

    private void refresh(final Object modelSource, final Object completePack) {
        resolver.invoke(
            "cubism.editor-model.model-source.update-instances", modelSource
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas",
            completePack,
            Boolean.TRUE
        );
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

    private static Map<?, ?> map(final Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Verified texture atlas usage map is unavailable.");
        return map;
    }

    @SuppressWarnings("unchecked")
    private static void append(final Object value, final Object entry) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("Verified texture atlas list is unavailable.");
        }
        ((List<Object>) list).add(entry);
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

    private record Binding(
        Object document,
        Object modelSource,
        Object dataModel,
        Object textureManager,
        String documentId,
        String modelId
    ) {
    }
}
