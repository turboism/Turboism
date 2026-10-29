package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasSelectorContract;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorEditSessionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.AtlasPageAppendPlan;
import dev.turboism.sdk.cubism.model.ModelImagePlacement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Executes append-only texture atlas layout, relinking, verification, and undo/rollback.
 */
final class EditorTextureAtlasAppendAccess {

    private static final String APP_INSTANCE = "cubism.editor-model.app-controller.instance";
    private static final String CURRENT_DOCUMENT = "cubism.editor-model.app-controller.current-document";
    private static final String EDIT_MODE = "cubism.editor-model.modeling-document.edit-mode";
    private static final String MARK_DIRTY = "cubism.editor-model.modeling-document.mark-dirty";
    private static final String BEGIN_EDIT = "cubism.editor-model.edit-mode.begin";
    private static final String END_EDIT = "cubism.editor-model.edit-mode.end";
    private static final String UNDO_ADD = "cubism.editor-model.undo.add";
    private static final String UNDO_ADD_LISTENER = "cubism.editor-model.undo.add-listener";
    private static final String UNDO_REVERT = "cubism.editor-model.texture-undo.undo";
    private static final String UNDO_LISTENER_CLASS = "cubism.editor-model.undo-listener.class";
    private static final String UPDATE_INSTANCES = "cubism.editor-model.model-source.update-instances";
    private static final String COMPLETE_PACK = "cubism.editor-model.app-controller.complete-pack";
    private static final String REPAINT_CANVAS = "cubism.editor-model.complete-pack.repaint-canvas";

    private static final String ATLAS_CREATE = "cubism.editor-model.texture-atlas.create";
    private static final String ATLAS_ENTRIES = "cubism.texture-atlas.atlas.entries";
    private static final String ENTRY_CLASS = "cubism.texture-atlas.entry.class";
    private static final String ENTRY_CREATE = "cubism.texture-atlas.entry.create";
    private static final String ENTRY_IMAGE = "cubism.texture-atlas.entry.image";
    private static final String IMAGE_GUID = "cubism.texture-atlas.image.guid";
    private static final String AFFINE_CREATE = "cubism.texture-atlas.affine.create";
    private static final String AFFINE_TRANSLATE = "cubism.texture-atlas.affine.translate";
    private static final String ATLAS_UNDO_CREATE = "cubism.texture-atlas.undo.create";
    private static final String ATLAS_UNDO_FORCE_REDO = "cubism.texture-atlas.undo.force-redo";
    private static final String RELINK_HELPER = "cubism.texture-atlas.texture-input-relink.helper-instance";
    private static final String RELINK_REBUILD = "cubism.texture-atlas.texture-input-relink.rebuild";
    private static final String MODEL_SOURCE_VERIFY = "cubism.editor-model.model-source.verify";
    private static final String CHANGE_INPUT_TO_ATLAS = "cubism.texture-atlas.texture-manager.change-input-to-atlas";
    private static final String REINIT_INPUT = "cubism.texture-atlas.texture-manager.reinit-input";

    private static final String TEXTURE_MANAGER = "cubism.editor-model.model-source.texture-manager";
    private static final String TEXTURE_ATLASES = "cubism.editor-model.texture-manager.texture-atlases";
    private static final String ALL_MODEL_IMAGES = "cubism.editor-model.texture-manager.all-model-images";
    private static final String ALL_ART_MESHES = "cubism.editor-model.model-source.all-art-meshes";
    private static final String ART_MESH_SOURCE_CLASS = "cubism.editor-model.art-mesh-source.class";
    private static final String SOURCE_ID = "cubism.editor-model.parameter-controllable-source.id";
    private static final String ID_VALUE = "cubism.editor-model.id.value";
    private static final String GUID_VALUE = "cubism.editor-model.guid.value";

    static final Set<String> APPEND_ATLAS_REQUIRED_ALIASES = Set.of(
            ATLAS_CREATE,
            ATLAS_ENTRIES,
            ENTRY_CLASS,
            ENTRY_CREATE,
            ENTRY_IMAGE,
            IMAGE_GUID,
            AFFINE_CREATE,
            AFFINE_TRANSLATE,
            ATLAS_UNDO_CREATE,
            ATLAS_UNDO_FORCE_REDO,
            RELINK_HELPER,
            RELINK_REBUILD,
            MODEL_SOURCE_VERIFY,
            CHANGE_INPUT_TO_ATLAS,
            REINIT_INPUT);

    @FunctionalInterface
    interface ArtMeshInputObserver {
        Optional<ArtMeshTextureInput> observe(String identity, Object source, Object model, ArtMeshId target);
    }

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;
    private final EditorAuthoringTransactionCoordinator authoringCoordinator;
    private final ArtMeshInputObserver inputObserver;

    EditorTextureAtlasAppendAccess(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final EditorAuthoringTransactionCoordinator authoringCoordinator,
            final ArtMeshInputObserver inputObserver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
        this.authoringCoordinator = authoringCoordinator;
        this.inputObserver = Objects.requireNonNull(inputObserver, "inputObserver");
    }

    private boolean writeAuthorized() {
        return resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
                EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES);
    }

    boolean supportsAppendTextureAtlasPages(final String identity, final Object model) {
        try {
            modelGuard.requireCurrent(identity, model);
        } catch (RuntimeException notCurrent) {
            return false;
        }
        if (!writeAuthorized()
                || !resolver.authorizesFeature(
                        VerifiedTextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                        VerifiedTextureAtlasSelectorContract.CAPABILITY_ID,
                        APPEND_ATLAS_REQUIRED_ALIASES)) {
            return false;
        }
        if ("false".equalsIgnoreCase(System.getProperty(HostUndoMutationScope.ENABLED_PROPERTY))) {
            return false;
        }
        return resolver.authorizesFeature(
                EditorEditSessionSelectorContract.ADAPTER_SLICE_ID,
                EditorEditSessionSelectorContract.EDIT_BEGIN_CAPABILITY_ID,
                Set.of("cubism.editor-model.undo.group-undo"));
    }

    List<TextureAtlasId> appendTextureAtlasPages(
            final String identity, final Object source, final Object model, final AtlasPageAppendPlan plan) {
        return EditorHostThread.dispatch(
                "Cubism texture write", () -> appendTextureAtlasPagesOnEdt(identity, source, model, plan));
    }

    private List<TextureAtlasId> appendTextureAtlasPagesOnEdt(
            final String identity, final Object source, final Object model, final AtlasPageAppendPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!supportsAppendTextureAtlasPages(identity, model)) {
            throw new UnsupportedOperationException(
                    "Append texture atlas pages is unavailable without verified layout write capability.");
        }
        modelGuard.requireCurrent(identity, model);

        final Object manager = textureManager(source);
        final List<?> oldAtlasesRaw = list(TEXTURE_ATLASES, manager, "texture atlas");
        final List<Object> oldAtlases = new ArrayList<>(oldAtlasesRaw);
        final int oldPageCount = oldAtlases.size();
        final int totalPageCount = oldPageCount + plan.pageCount();
        if (totalPageCount > AtlasPageAppendPlan.MAX_PAGE_COUNT) {
            throw new IllegalArgumentException("Total atlas page count (" + totalPageCount
                    + ") exceeds maximum allowed (" + AtlasPageAppendPlan.MAX_PAGE_COUNT + ")");
        }

        long totalPixels = 0L;
        for (final Object oldAtlas : oldAtlases) {
            final int w = dimension(oldAtlas, "cubism.editor-model.texture-atlas.width", "texture atlas");
            final int h = dimension(oldAtlas, "cubism.editor-model.texture-atlas.height", "texture atlas");
            totalPixels += (long) w * (long) h;
        }
        totalPixels += (long) plan.pageWidth() * (long) plan.pageHeight() * (long) plan.pageCount();
        if (totalPixels > AtlasPageAppendPlan.MAX_TOTAL_PIXELS) {
            throw new IllegalArgumentException(
                    "Total atlas pixel budget exceeded: " + totalPixels + " > " + AtlasPageAppendPlan.MAX_TOTAL_PIXELS);
        }

        final List<String> oldGuids = new ArrayList<>(oldPageCount);
        for (final Object oldAtlas : oldAtlases) {
            final Object guidObj = resolver.invoke("cubism.editor-model.texture-atlas.guid", oldAtlas);
            oldGuids.add(guidValue(guidObj, "texture atlas"));
        }

        final java.util.HashSet<String> existingPlacedImageGuids = new java.util.HashSet<>();
        for (final Object oldAtlas : oldAtlases) {
            final Object entriesObj = resolver.invoke(ATLAS_ENTRIES, oldAtlas);
            if (entriesObj instanceof List<?> entries) {
                for (final Object entry : entries) {
                    if (resolver.isInstance(ENTRY_CLASS, entry)) {
                        final Object image = resolver.invoke(ENTRY_IMAGE, entry);
                        if (image != null) {
                            final Object guid = resolver.invoke(IMAGE_GUID, image);
                            final String val = guidValue(guid, "model image");
                            existingPlacedImageGuids.add(val);
                        }
                    }
                }
            }
        }

        final Map<ModelImageId, Object> modelImages = new LinkedHashMap<>();
        final Map<ModelImageId, Object> modelImageGuids = new LinkedHashMap<>();
        for (final ModelImagePlacement placement : plan.placements()) {
            final ModelImageId id = placement.modelImageId();
            if (existingPlacedImageGuids.contains(id.value())) {
                throw new IllegalArgumentException(
                        "ModelImage is already placed on an existing atlas page: " + id.value());
            }
            if (!modelImages.containsKey(id)) {
                final Object modelImage = findModelImage(source, id);
                final Object guid = findModelImageGuid(source, id);
                final int imgW = dimension(modelImage, "cubism.editor-model.model-image.width", "model image");
                final int imgH = dimension(modelImage, "cubism.editor-model.model-image.height", "model image");
                if (placement.width() != imgW || placement.height() != imgH) {
                    throw new IllegalArgumentException(
                            "Placement dimensions (" + placement.width() + "x" + placement.height()
                                    + ") must match ModelImage dimensions (" + imgW + "x" + imgH
                                    + ") for image " + id.value());
                }
                modelImages.put(id, modelImage);
                modelImageGuids.put(id, guid);
            }
        }

        // Capture before-state of all art mesh texture inputs to verify applied and restored bindings
        final Map<ArtMeshId, Optional<ArtMeshTextureInput>> beforeMeshInputs = new LinkedHashMap<>();
        for (final Object meshSource : list(ALL_ART_MESHES, source, "art mesh")) {
            if (resolver.isInstance(ART_MESH_SOURCE_CLASS, meshSource)) {
                final Object idObj = resolver.invoke(SOURCE_ID, meshSource);
                final Object text = resolver.invoke(ID_VALUE, idObj);
                if (text instanceof String meshId && !meshId.isBlank()) {
                    final ArtMeshId targetId = new ArtMeshId(meshId);
                    beforeMeshInputs.put(targetId, inputObserver.observe(identity, source, model, targetId));
                }
            }
        }

        final List<Object> newAtlases = new ArrayList<>(oldPageCount + plan.pageCount());
        newAtlases.addAll(oldAtlases);

        for (int i = 0; i < plan.pageCount(); i++) {
            final int pageIndex = oldPageCount + i;
            final String pageName = (plan.pageNames() != null
                            && i < plan.pageNames().size()
                            && !plan.pageNames().get(i).isBlank())
                    ? plan.pageNames().get(i).strip()
                    : "Atlas Page " + (pageIndex + 1);
            final Object newAtlas =
                    resolver.construct(ATLAS_CREATE, source, pageName, plan.pageWidth(), plan.pageHeight());
            newAtlases.add(newAtlas);
        }

        for (final ModelImagePlacement placement : plan.placements()) {
            final Object targetAtlas = newAtlases.get(oldPageCount + placement.pageIndex());
            final Object guid = modelImageGuids.get(placement.modelImageId());
            final Object affine = resolver.construct(AFFINE_CREATE);
            resolver.invoke(AFFINE_TRANSLATE, affine, (float) placement.x(), (float) placement.y());
            final Object entry = resolver.construct(ENTRY_CREATE, targetAtlas, guid, affine);
            append(resolver.invoke(ATLAS_ENTRIES, targetAtlas), entry);
        }

        final List<TextureAtlasId> createdPageIds = new ArrayList<>(plan.pageCount());
        for (int i = 0; i < plan.pageCount(); i++) {
            final Object newAtlas = newAtlases.get(oldPageCount + i);
            final Object guid = resolver.invoke("cubism.editor-model.texture-atlas.guid", newAtlas);
            createdPageIds.add(new TextureAtlasId(guidValue(guid, "texture atlas")));
        }

        final Object undo = resolver.construct(
                ATLAS_UNDO_CREATE, "Turboism: Append Texture Atlas Pages", source, oldAtlases, newAtlases);

        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object document = resolver.invoke(CURRENT_DOCUMENT, app);
        final Object completePack = app == null ? null : resolver.invoke(COMPLETE_PACK, app);

        final var ambientJoin = HostUndoMutationScope.ambient(authoringCoordinator, resolver);
        if (ambientJoin.isPresent()) {
            ambientJoin
                    .orElseThrow()
                    .admit(
                            "cubism.texture.write",
                            "texture:Turboism: Append Texture Atlas Pages",
                            "Turboism: Append Texture Atlas Pages",
                            (edit, transactionLabel) -> {
                                applyUndoAndRegisterListener(edit, undo, source, manager, completePack, oldPageCount);
                            },
                            () -> {
                                relinkTextureInputs(source, manager, true);
                                refreshAtlas(source, completePack);
                            },
                            () -> verifyAtlasApplied(
                                    identity,
                                    model,
                                    manager,
                                    oldAtlases,
                                    oldGuids,
                                    newAtlases,
                                    plan.pageCount(),
                                    source,
                                    beforeMeshInputs,
                                    plan.placements(),
                                    createdPageIds),
                            () -> {
                                rollbackAppend(undo, source, manager, completePack, oldPageCount);
                            },
                            () -> verifyAtlasRestored(
                                    identity, model, manager, oldAtlases, oldGuids, source, beforeMeshInputs),
                            EnumSet.of(
                                    EditorRefreshRequirement.MODEL_INSTANCES,
                                    EditorRefreshRequirement.PART_PALETTE,
                                    EditorRefreshRequirement.PROJECT_PALETTE,
                                    EditorRefreshRequirement.CANVAS,
                                    EditorRefreshRequirement.MARK_DIRTY));
            return createdPageIds;
        }

        EditorAmbientTransactionGuard.requireNoAmbientTransaction(
                authoringCoordinator, "Turboism: Append Texture Atlas Pages");
        final Object editMode = resolver.invoke(EDIT_MODE, document);
        final Object edit = resolver.invoke(BEGIN_EDIT, editMode, "Turboism: Append Texture Atlas Pages");
        boolean completed = false;
        try {
            resolver.invoke(ATLAS_UNDO_FORCE_REDO, undo);
            registerAppliedUndo(edit, undo);

            final Object listener = createRelinkListener(source, manager, completePack, oldPageCount);
            final Object accepted = resolver.invoke(UNDO_ADD_LISTENER, undo, listener);
            if (!Boolean.TRUE.equals(accepted)) {
                throw new IllegalStateException("Cubism rejected the texture-atlas Undo/Redo refresh listener.");
            }

            relinkTextureInputs(source, manager, true);
            refreshAtlas(source, completePack);
            resolver.invoke(MARK_DIRTY, document);
            if (!verifyAtlasApplied(
                    identity,
                    model,
                    manager,
                    oldAtlases,
                    oldGuids,
                    newAtlases,
                    plan.pageCount(),
                    source,
                    beforeMeshInputs,
                    plan.placements(),
                    createdPageIds)) {
                throw new IllegalStateException("Texture atlas append postcondition failed.");
            }
            completed = true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            try {
                final List<?> current = list(TEXTURE_ATLASES, manager, "texture atlas");
                final boolean atlasesAlreadyReverted = samePageCountAndInstances(current, oldAtlases);
                if (!atlasesAlreadyReverted) {
                    resolver.invoke(UNDO_REVERT, undo);
                }
                relinkTextureInputs(source, manager, oldPageCount > 0);
                refreshAtlas(source, completePack);
                if (!verifyAtlasRestored(identity, model, manager, oldAtlases, oldGuids, source, beforeMeshInputs)) {
                    throw new IllegalStateException("Texture atlas append rollback postcondition failed.");
                }
            } catch (Throwable rollbackError) {
                FatalErrors.rethrowIfFatal(rollbackError);
                failure.addSuppressed(rollbackError);
            }
            throw failure;
        } finally {
            resolver.invoke(END_EDIT, editMode, Boolean.valueOf(!completed), null);
        }
        return createdPageIds;
    }

    private static boolean samePageCountAndInstances(final List<?> current, final List<?> expected) {
        if (current == null || expected == null || current.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (current.get(i) != expected.get(i)) return false;
        }
        return true;
    }

    private Object createRelinkListener(
            final Object source, final Object manager, final Object completePack, final int oldPageCount) {
        return resolver.createFunctionalProxy(UNDO_LISTENER_CLASS, ignored -> {
            final List<?> current = list(TEXTURE_ATLASES, manager, "texture atlas");
            final boolean isRedo = current.size() > oldPageCount;
            relinkTextureInputs(source, manager, isRedo);
            refreshAtlas(source, completePack);
            return null;
        });
    }

    private void applyUndoAndRegisterListener(
            final Object edit,
            final Object undo,
            final Object source,
            final Object manager,
            final Object completePack,
            final int oldPageCount) {
        resolver.invoke(ATLAS_UNDO_FORCE_REDO, undo);
        registerAppliedUndo(edit, undo);
        final Object listener = createRelinkListener(source, manager, completePack, oldPageCount);
        final Object listenerAccepted = resolver.invoke(UNDO_ADD_LISTENER, undo, listener);
        if (!(listenerAccepted instanceof Boolean accepted) || !accepted) {
            throw new IllegalStateException("Cubism rejected the texture-atlas Undo/Redo refresh listener.");
        }
    }

    private void rollbackAppend(
            final Object undo,
            final Object source,
            final Object manager,
            final Object completePack,
            final int oldPageCount) {
        resolver.invoke(UNDO_REVERT, undo);
        relinkTextureInputs(source, manager, oldPageCount > 0);
        refreshAtlas(source, completePack);
    }

    private boolean verifyAtlasApplied(
            final String identity,
            final Object model,
            final Object manager,
            final List<Object> oldAtlases,
            final List<String> oldGuids,
            final List<Object> newAtlases,
            final int newPageCount,
            final Object source,
            final Map<ArtMeshId, Optional<ArtMeshTextureInput>> beforeMeshInputs,
            final List<ModelImagePlacement> placements,
            final List<TextureAtlasId> createdPageIds) {
        final List<?> current = list(TEXTURE_ATLASES, manager, "texture atlas");
        if (current.size() != oldAtlases.size() + newPageCount) {
            return false;
        }
        for (int i = 0; i < oldAtlases.size(); i++) {
            if (current.get(i) != oldAtlases.get(i)) return false;
            final Object guid = resolver.invoke("cubism.editor-model.texture-atlas.guid", current.get(i));
            if (!oldGuids.get(i).equals(guidValue(guid, "texture atlas"))) return false;
        }
        for (int i = 0; i < newPageCount; i++) {
            if (current.get(oldAtlases.size() + i) != newAtlases.get(oldAtlases.size() + i)) {
                return false;
            }
        }
        final Map<ModelImageId, TextureAtlasId> expectedAtlasByImage = new LinkedHashMap<>();
        for (final ModelImagePlacement p : placements) {
            expectedAtlasByImage.put(p.modelImageId(), createdPageIds.get(p.pageIndex()));
        }
        for (final Map.Entry<ArtMeshId, Optional<ArtMeshTextureInput>> entry : beforeMeshInputs.entrySet()) {
            final ArtMeshId meshId = entry.getKey();
            final Optional<ArtMeshTextureInput> before = entry.getValue();
            final Optional<ArtMeshTextureInput> now;
            try {
                now = inputObserver.observe(identity, source, model, meshId);
            } catch (NoSuchElementException deleted) {
                return false;
            }
            if (before.isPresent() && before.get().kind() == ArtMeshTextureInput.Kind.MODEL_IMAGE) {
                final ModelImageId imgId = before.get().modelImageId().orElse(null);
                if (imgId != null && expectedAtlasByImage.containsKey(imgId)) {
                    final TextureAtlasId expectedAtlasId = expectedAtlasByImage.get(imgId);
                    if (now.isEmpty()
                            || now.get().kind() != ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION
                            || !expectedAtlasId.equals(
                                    now.get().textureAtlasId().orElse(null))) {
                        return false;
                    }
                    continue;
                }
            }
            if (!sameSemanticInput(before, now)) {
                return false;
            }
        }
        return true;
    }

    private boolean verifyAtlasRestored(
            final String identity,
            final Object model,
            final Object manager,
            final List<Object> oldAtlases,
            final List<String> oldGuids,
            final Object source,
            final Map<ArtMeshId, Optional<ArtMeshTextureInput>> beforeMeshInputs) {
        final List<?> current = list(TEXTURE_ATLASES, manager, "texture atlas");
        if (current.size() != oldAtlases.size()) {
            return false;
        }
        for (int i = 0; i < oldAtlases.size(); i++) {
            if (current.get(i) != oldAtlases.get(i)) return false;
            final Object guid = resolver.invoke("cubism.editor-model.texture-atlas.guid", current.get(i));
            if (!oldGuids.get(i).equals(guidValue(guid, "texture atlas"))) return false;
        }
        for (final Map.Entry<ArtMeshId, Optional<ArtMeshTextureInput>> entry : beforeMeshInputs.entrySet()) {
            final ArtMeshId meshId = entry.getKey();
            final Optional<ArtMeshTextureInput> expected = entry.getValue();
            final Optional<ArtMeshTextureInput> now;
            try {
                now = inputObserver.observe(identity, source, model, meshId);
            } catch (NoSuchElementException deleted) {
                // Mesh was deleted during root transaction rollback; this is expected and valid
                continue;
            }
            if (!sameSemanticInput(expected, now)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSemanticInput(
            final Optional<ArtMeshTextureInput> a, final Optional<ArtMeshTextureInput> b) {
        if (a.isEmpty() && b.isEmpty()) return true;
        if (a.isEmpty() || b.isEmpty()) return false;
        final ArtMeshTextureInput inputA = a.get();
        final ArtMeshTextureInput inputB = b.get();
        return inputA.kind() == inputB.kind()
                && Objects.equals(inputA.modelImageId(), inputB.modelImageId())
                && Objects.equals(inputA.textureAtlasId(), inputB.textureAtlasId());
    }

    private void relinkTextureInputs(final Object modelSource, final Object textureManager, final boolean selectAtlas) {
        final Object helper = resolver.readStaticField(RELINK_HELPER);
        resolver.invoke(RELINK_REBUILD, helper, modelSource);
        resolver.invokeStatic(MODEL_SOURCE_VERIFY, modelSource, Boolean.TRUE, null, 2, null);
        resolver.invoke(!selectAtlas ? REINIT_INPUT : CHANGE_INPUT_TO_ATLAS, textureManager);
    }

    private void refreshAtlas(final Object modelSource, final Object completePack) {
        resolver.invoke(UPDATE_INSTANCES, modelSource);
        if (completePack != null) {
            resolver.invoke("cubism.editor-model.complete-pack.update-project", completePack);
            resolver.invoke(REPAINT_CANVAS, completePack, Boolean.TRUE);
        }
    }

    @SuppressWarnings("unchecked")
    private static void append(final Object value, final Object entry) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("Verified texture atlas list is unavailable.");
        }
        ((List<Object>) list).add(entry);
    }

    private void registerAppliedUndo(final Object edit, final Object undoable) {
        try {
            final Object accepted = resolver.invoke(UNDO_ADD, edit, undoable, Boolean.TRUE);
            if (!Boolean.TRUE.equals(accepted)) {
                throw new IllegalStateException("Cubism rejected the texture Undo entry.");
            }
        } catch (RuntimeException | Error failure) {
            // @containment-exempt: nested revert runs before rethrow with fatal error prioritized
            Throwable toThrow = failure;
            try {
                resolver.invoke(UNDO_REVERT, undoable);
            } catch (RuntimeException | Error compensation) {
                // @containment-exempt: compensation failure merged, prioritizing fatal error
                toThrow = FatalErrors.mergeForCleanup(toThrow, compensation);
            }
            throw FatalErrors.propagate(toThrow);
        }
    }

    private Object textureManager(final Object source) {
        final Object manager = resolver.invoke(TEXTURE_MANAGER, source);
        if (manager == null) {
            throw new IllegalStateException("Editor texture manager is unavailable.");
        }
        return manager;
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

    private int dimension(final Object image, final String alias, final String label) {
        final Object raw = resolver.invoke(alias, image);
        if (!(raw instanceof Integer value) || value < 0) {
            throw new IllegalStateException("Editor " + label + " dimension is invalid.");
        }
        return value;
    }

    private Object findModelImage(final Object source, final ModelImageId id) {
        for (final Object image : list(ALL_MODEL_IMAGES, textureManager(source), "model image")) {
            if (!resolver.isInstance("cubism.editor-model.model-image.class", image)) {
                throw new IllegalStateException("Editor model image collection contains an invalid value.");
            }
            final Object guid = resolver.invoke("cubism.editor-model.model-image.guid", image);
            if (guidValue(guid, "model image").equals(id.value())) {
                return image;
            }
        }
        throw new NoSuchElementException("Cubism model image is absent: " + id.value());
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
}
