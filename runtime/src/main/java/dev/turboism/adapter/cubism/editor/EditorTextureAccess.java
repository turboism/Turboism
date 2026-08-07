package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorTextureSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Exact, generation-bound Editor projection of the model texture library.
 *
 * <p>Reads enumerate {@code CTextureManager} state (raw layered images, model
 * image groups, texture atlases). Writes are Editor-authoring operations routed
 * through {@code TextureManagerHandler} undo factories inside the standard Undo
 * envelope: {@code beginEdit} → {@code GroupUndo.addEdit} → side effects →
 * {@code endEdit} with rollback through the native Undo path on failure.</p>
 */
final class EditorTextureAccess {

    private static final String TEXTURE_MANAGER = "cubism.editor-model.model-source.texture-manager";
    private static final String RAW_IMAGES = "cubism.editor-model.texture-manager.raw-images";
    private static final String MODEL_IMAGE_GROUPS = "cubism.editor-model.texture-manager.model-image-groups";
    private static final String ALL_MODEL_IMAGES = "cubism.editor-model.texture-manager.all-model-images";
    private static final String TEXTURE_ATLASES = "cubism.editor-model.texture-manager.texture-atlases";
    private static final String HANDLER = "cubism.editor-model.texture-manager.handler";
    private static final String WRAPPER_IMAGE = "cubism.editor-model.layered-image-wrapper.image";
    private static final String GUID_VALUE = "cubism.editor-model.guid.value";

    private static final String APP_INSTANCE = "cubism.editor-model.app-controller.instance";
    private static final String CURRENT_DOCUMENT = "cubism.editor-model.app-controller.current-document";
    private static final String EDIT_MODE = "cubism.editor-model.modeling-document.edit-mode";
    private static final String MARK_DIRTY = "cubism.editor-model.modeling-document.mark-dirty";
    private static final String BEGIN_EDIT = "cubism.editor-model.edit-mode.begin";
    private static final String END_EDIT = "cubism.editor-model.edit-mode.end";
    private static final String UNDO_ADD = "cubism.editor-model.undo.add";
    private static final String UNDO_ADD_LISTENER = "cubism.editor-model.undo.add-listener";
    private static final String UNDO_LISTENER_CLASS = "cubism.editor-model.undo-listener.class";
    private static final String UPDATE_INSTANCES = "cubism.editor-model.model-source.update-instances";
    private static final String COMPLETE_PACK = "cubism.editor-model.app-controller.complete-pack";
    private static final String UPDATE_PART_PALETTE = "cubism.editor-model.complete-pack.update-part-palette";
    private static final String REPAINT_CANVAS = "cubism.editor-model.complete-pack.repaint-canvas";

    private static final String GROUP_CREATE = "cubism.editor-model.model-image-group.create";
    private static final String ATLAS_CREATE = "cubism.editor-model.texture-atlas.create";
    private static final String ADD_MODEL_IMAGE_GROUP = "cubism.editor-model.texture-handler.add-model-image-group";
    private static final String REMOVE_MODEL_IMAGE = "cubism.editor-model.texture-handler.remove-model-image";
    private static final String ADD_TEXTURE_ATLAS = "cubism.editor-model.texture-handler.add-texture-atlas";
    private static final String REMOVE_TEXTURE_ATLAS = "cubism.editor-model.texture-handler.remove-texture-atlas";
    private static final String REMOVE_RAW_IMAGE = "cubism.editor-model.texture-handler.remove-raw-image";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorTextureAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    ModelTextures textures(final String identity, final Object source, final Object model) {
        requireReadAuthorization();
        modelGuard.requireCurrent(identity, model);
        return new EditorTextures(identity, source, model);
    }

    private boolean readAuthorized() {
        return resolver.authorizesFeature(
            EditorTextureSelectorContract.ADAPTER_SLICE_ID,
            EditorTextureSelectorContract.READ_CAPABILITY_ID,
            EditorTextureSelectorContract.READ_REQUIRED_ALIASES
        );
    }

    private boolean writeAuthorized() {
        return resolver.authorizesFeature(
            EditorTextureSelectorContract.ADAPTER_SLICE_ID,
            EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
            EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES
        );
    }

    private void requireReadAuthorization() {
        if (!readAuthorized()) {
            throw new UnsupportedOperationException(
                "Texture-library reading is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireWriteAuthorization() {
        if (!writeAuthorized()) {
            throw new UnsupportedOperationException(
                "Texture-library writing is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireRawImageRemoval() {
        if (!resolver.authorizesFeature(
            EditorTextureSelectorContract.ADAPTER_SLICE_ID,
            EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
            EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Raw image removal is unavailable on this Cubism version (5.2.03 exposes only a dialog path)."
            );
        }
    }

    private Object textureManager(final Object source) {
        final Object manager = resolver.invoke(TEXTURE_MANAGER, source);
        if (manager == null) {
            throw new IllegalStateException("Editor texture manager is unavailable.");
        }
        return manager;
    }

    private Object handler(final Object source) {
        final Object handler = resolver.invoke(HANDLER, textureManager(source));
        if (handler == null) {
            throw new IllegalStateException("Editor texture handler is unavailable.");
        }
        return handler;
    }

    private List<?> list(final String alias, final Object target, final String label) {
        final Object raw = resolver.invoke(alias, target);
        if (!(raw instanceof List<?> values)) {
            throw new IllegalStateException("Editor " + label + " collection is unavailable.");
        }
        return values;
    }

    private String guidValue(final Object guid, final String label) {
        final Object raw = resolver.invoke(GUID_VALUE, guid);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("Editor " + label + " GUID is invalid.");
        }
        return value;
    }

    private String name(final Object image, final String alias, final String label) {
        final Object raw = resolver.invoke(alias, image);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("Editor " + label + " name is invalid.");
        }
        return value;
    }

    private int dimension(final Object image, final String alias, final String label) {
        final Object raw = resolver.invoke(alias, image);
        if (!(raw instanceof Integer value) || value < 0) {
            throw new IllegalStateException("Editor " + label + " dimension is invalid.");
        }
        return value;
    }

    private void refresh(final Object app) {
        final Object completePack = resolver.invoke(COMPLETE_PACK, app);
        resolver.invoke(UPDATE_PART_PALETTE, completePack, Boolean.TRUE);
        resolver.invoke(REPAINT_CANVAS, completePack, Boolean.TRUE);
    }

    /**
     * Standard Undo envelope: begin → register the construct-and-redo undoable →
     * update instances/repaint → mark dirty → end. On any failure the partial
     * group is closed through the native Undo path so no partial state survives.
     */
    private void envelope(
        final String label,
        final Object source,
        final Operation operation
    ) {
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object document = resolver.invoke(CURRENT_DOCUMENT, app);
        final Object editMode = resolver.invoke(EDIT_MODE, document);
        final Object edit = resolver.invoke(BEGIN_EDIT, editMode, label);
        boolean completed = false;
        try {
            final Object undoable = operation.apply(edit);
            final Object accepted = resolver.invoke(UNDO_ADD, edit, undoable, Boolean.TRUE);
            if (!(accepted instanceof Boolean acceptedValue) || !acceptedValue) {
                throw new IllegalStateException("Cubism rejected the texture Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                UNDO_LISTENER_CLASS,
                ignored -> {
                    resolver.invoke(UPDATE_INSTANCES, source);
                    refresh(app);
                    return null;
                }
            );
            resolver.invoke(UNDO_ADD_LISTENER, undoable, listener);
            resolver.invoke(UPDATE_INSTANCES, source);
            refresh(app);
            resolver.invoke(MARK_DIRTY, document);
            completed = true;
        } finally {
            resolver.invoke(END_EDIT, editMode, Boolean.valueOf(!completed), null);
        }
        if (!completed) {
            throw new IllegalStateException("Cubism texture edit was rolled back: " + label);
        }
    }

    private Object findModelImageGuid(final Object source, final ModelImageId id) {
        for (Object image : list(ALL_MODEL_IMAGES, textureManager(source), "model image")) {
            if (!resolver.isInstance("cubism.editor-model.model-image.class", image)) {
                throw new IllegalStateException("Editor model image collection contains an invalid value.");
            }
            final Object guid = resolver.invoke("cubism.editor-model.model-image.guid", image);
            if (guidValue(guid, "model image").equals(id.value())) {
                return guid;
            }
        }
        throw new NoSuchElementException("Cubism model image is absent: " + id.value());
    }

    private Object findTextureAtlas(final Object source, final TextureAtlasId id) {
        for (Object atlas : list(TEXTURE_ATLASES, textureManager(source), "texture atlas")) {
            if (!resolver.isInstance("cubism.editor-model.texture-atlas.class", atlas)) {
                throw new IllegalStateException("Editor texture atlas collection contains an invalid value.");
            }
            final Object guid = resolver.invoke("cubism.editor-model.texture-atlas.guid", atlas);
            if (guidValue(guid, "texture atlas").equals(id.value())) {
                return atlas;
            }
        }
        throw new NoSuchElementException("Cubism texture atlas is absent: " + id.value());
    }

    private Object findRawImageGuid(final Object source, final RawImageId id) {
        for (Object wrapper : list(RAW_IMAGES, textureManager(source), "raw image")) {
            final Object image = resolver.invoke(WRAPPER_IMAGE, wrapper);
            if (!resolver.isInstance("cubism.editor-model.layered-image.class", image)) {
                throw new IllegalStateException("Editor raw image collection contains an invalid value.");
            }
            final Object guid = resolver.invoke("cubism.editor-model.layered-image.guid", image);
            if (guidValue(guid, "raw image").equals(id.value())) {
                return guid;
            }
        }
        throw new NoSuchElementException("Cubism raw image is absent: " + id.value());
    }

    @FunctionalInterface
    private interface Operation {
        Object apply(Object edit);
    }

    private final class EditorTextures implements ModelTextures {
        private final String identity;
        private final Object source;
        private final Object model;

        private EditorTextures(final String identity, final Object source, final Object model) {
            this.identity = identity;
            this.source = source;
            this.model = model;
        }

        @Override
        public List<RawTexture> rawImages() {
            modelGuard.requireCurrent(identity, model);
            final List<RawTexture> values = new ArrayList<>();
            for (Object wrapper : list(RAW_IMAGES, textureManager(source), "raw image")) {
                final Object image = resolver.invoke(WRAPPER_IMAGE, wrapper);
                if (!resolver.isInstance("cubism.editor-model.layered-image.class", image)) {
                    throw new IllegalStateException("Editor raw image collection contains an invalid value.");
                }
                final String id = guidValue(
                    resolver.invoke("cubism.editor-model.layered-image.guid", image), "raw image");
                values.add(new RawTexture() {
                    @Override public RawImageId id() { return new RawImageId(id); }
                    @Override public String name() {
                        return EditorTextureAccess.this.name(image, "cubism.editor-model.layered-image.name", "raw image");
                    }
                    @Override public int width() {
                        return EditorTextureAccess.this.dimension(image, "cubism.editor-model.layered-image.width", "raw image");
                    }
                    @Override public int height() {
                        return EditorTextureAccess.this.dimension(image, "cubism.editor-model.layered-image.height", "raw image");
                    }
                });
            }
            return List.copyOf(values);
        }

        @Override
        public List<ModelImageGroup> modelImageGroups() {
            modelGuard.requireCurrent(identity, model);
            final List<ModelImageGroup> values = new ArrayList<>();
            for (Object group : list(MODEL_IMAGE_GROUPS, textureManager(source), "model image group")) {
                if (!resolver.isInstance("cubism.editor-model.model-image-group.class", group)) {
                    throw new IllegalStateException("Editor model image group collection contains an invalid value.");
                }
                final String groupName = name(
                    group, "cubism.editor-model.model-image-group.group-name", "model image group");
                final Object rawMemo = resolver.invoke("cubism.editor-model.model-image-group.memo", group);
                final String memo = rawMemo instanceof String value ? value : "";
                final List<ModelImageEntry> images = new ArrayList<>();
                for (Object image : list(
                    "cubism.editor-model.model-image-group.model-images", group, "model image group images")) {
                    if (!resolver.isInstance("cubism.editor-model.model-image.class", image)) {
                        throw new IllegalStateException("Editor model image collection contains an invalid value.");
                    }
                    final String id = guidValue(
                        resolver.invoke("cubism.editor-model.model-image.guid", image), "model image");
                    images.add(new ModelImageEntry() {
                        @Override public ModelImageId id() { return new ModelImageId(id); }
                        @Override public String name() {
                            return EditorTextureAccess.this.name(image, "cubism.editor-model.model-image.name", "model image");
                        }
                        @Override public int width() {
                            return EditorTextureAccess.this.dimension(image, "cubism.editor-model.model-image.width", "model image");
                        }
                        @Override public int height() {
                            return EditorTextureAccess.this.dimension(image, "cubism.editor-model.model-image.height", "model image");
                        }
                    });
                }
                values.add(new ModelImageGroup() {
                    @Override public String groupName() { return groupName; }
                    @Override public String memo() { return memo; }
                    @Override public List<ModelImageEntry> modelImages() { return List.copyOf(images); }
                });
            }
            return List.copyOf(values);
        }

        @Override
        public List<AtlasTexture> textureAtlases() {
            modelGuard.requireCurrent(identity, model);
            final List<AtlasTexture> values = new ArrayList<>();
            for (Object atlas : list(TEXTURE_ATLASES, textureManager(source), "texture atlas")) {
                if (!resolver.isInstance("cubism.editor-model.texture-atlas.class", atlas)) {
                    throw new IllegalStateException("Editor texture atlas collection contains an invalid value.");
                }
                final String id = guidValue(
                    resolver.invoke("cubism.editor-model.texture-atlas.guid", atlas), "texture atlas");
                final String atlasName = name(
                    atlas, "cubism.editor-model.texture-atlas.name", "texture atlas");
                final int width = dimension(atlas, "cubism.editor-model.texture-atlas.width", "texture atlas");
                final int height = dimension(atlas, "cubism.editor-model.texture-atlas.height", "texture atlas");
                final Object rawVersion = resolver.invoke(
                    "cubism.editor-model.texture-atlas.atlas-version", atlas);
                final int atlasVersion = rawVersion instanceof Integer value ? value : 0;
                final int modelImageCount = list(
                    "cubism.editor-model.texture-atlas.model-images", atlas, "texture atlas images").size();
                values.add(new AtlasTexture() {
                    @Override public TextureAtlasId id() { return new TextureAtlasId(id); }
                    @Override public String name() { return atlasName; }
                    @Override public int width() { return width; }
                    @Override public int height() { return height; }
                    @Override public int atlasVersion() { return atlasVersion; }
                    @Override public int modelImageCount() { return modelImageCount; }
                });
            }
            return List.copyOf(values);
        }

        @Override
        public void addModelImageGroup(final String name) {
            requireWriteAuthorization();
            final String value = Objects.requireNonNull(name, "name");
            if (value.strip().isEmpty()) throw new IllegalArgumentException("name must not be blank");
            modelGuard.requireCurrent(identity, model);
            final Object group = resolver.construct(GROUP_CREATE, value);
            final int index = list(MODEL_IMAGE_GROUPS, textureManager(source), "model image group").size();
            envelope("Turboism: Add Model Image Group", source,
                edit -> resolver.invoke(ADD_MODEL_IMAGE_GROUP, handler(source), group, index));
        }

        @Override
        public void removeModelImage(final ModelImageId id) {
            requireWriteAuthorization();
            Objects.requireNonNull(id, "id");
            modelGuard.requireCurrent(identity, model);
            final Object imageGuid = findModelImageGuid(source, id);
            envelope("Turboism: Remove Model Image", source,
                edit -> resolver.invoke(REMOVE_MODEL_IMAGE, handler(source), imageGuid));
        }

        @Override
        public TextureAtlasId addTextureAtlas(final String name, final int widthPixels, final int heightPixels) {
            requireWriteAuthorization();
            final String value = Objects.requireNonNull(name, "name");
            if (value.strip().isEmpty()) throw new IllegalArgumentException("name must not be blank");
            if (widthPixels <= 0 || heightPixels <= 0) {
                throw new IllegalArgumentException("atlas dimensions must be positive");
            }
            modelGuard.requireCurrent(identity, model);
            final Object atlas = resolver.construct(ATLAS_CREATE, source, value, widthPixels, heightPixels);
            final int index = list(TEXTURE_ATLASES, textureManager(source), "texture atlas").size();
            envelope("Turboism: Add Texture Atlas", source,
                edit -> resolver.invoke(ADD_TEXTURE_ATLAS, handler(source), atlas, index));
            final Object guid = resolver.invoke("cubism.editor-model.texture-atlas.guid", atlas);
            return new TextureAtlasId(guidValue(guid, "texture atlas"));
        }

        @Override
        public void removeTextureAtlas(final TextureAtlasId id) {
            requireWriteAuthorization();
            Objects.requireNonNull(id, "id");
            modelGuard.requireCurrent(identity, model);
            final Object atlas = findTextureAtlas(source, id);
            envelope("Turboism: Remove Texture Atlas", source,
                edit -> resolver.invoke(REMOVE_TEXTURE_ATLAS, handler(source), atlas));
        }

        @Override
        public void removeRawImage(final RawImageId id) {
            requireWriteAuthorization();
            requireRawImageRemoval();
            Objects.requireNonNull(id, "id");
            modelGuard.requireCurrent(identity, model);
            final Object guid = findRawImageGuid(source, id);
            envelope("Turboism: Remove Raw Image", source,
                edit -> resolver.invoke(REMOVE_RAW_IMAGE, handler(source), guid, Boolean.FALSE));
        }
    }
}
