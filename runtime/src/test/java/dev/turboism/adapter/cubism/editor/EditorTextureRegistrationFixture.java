package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.ModelTextures;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.swing.SwingUtilities;

/**
 * Offline native substitutes for the register-and-bind slice: image resource
 * decode counters, layered image/wrapper/group/model image owner tables, the
 * texture-input extension chain, field undoables, and the root edit. Mirrors the
 * verified {@code Undo_AddOrRemove}/{@code GroupUndo.addEdit}/{@code UUUndo.a}
 * semantics: construction is pure, admission only enqueues, {@code forceRedo}
 * applies, and flags transfer undoable ↔ redoable exactly.
 */
final class EditorTextureRegistrationFixture implements EditorAuthoringTransactionCoordinator.Host {
    @Override
    public void undoEditGroup(
            final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit) {}

    static final ThreadLocal<EditorTextureRegistrationFixture> CURRENT = new ThreadLocal<>();
    static final String M = "cubism.editor-model.";

    final Document document = new Document();
    final ModelSource source = document.source;
    final Model model = source.model;
    final MeshSource meshSource = source.meshSource;
    final TextureManager manager = source.manager;
    final EditorAuthoringTransactionCoordinator coordinator = new EditorAuthoringTransactionCoordinator(this);
    final EditorAuthoringTransactionCoordinator.Binding binding = new EditorAuthoringTransactionCoordinator.Binding(
            "texture-test", "document", 1, "model", 1, Thread.currentThread());

    VerifiedMemberResolver resolver;
    Object currentSource = source;
    Object currentModel = model;
    final List<Undo> history = new ArrayList<>();
    final List<HistoryEntry> entries = new ArrayList<>();
    int position, revision, begins, refreshes, dirty, guidSequence;
    boolean rejectAdd, throwAdd, nativeAbort, failCachedImage, failImageAdd, failUndo;
    final List<String> mutationOrder = new ArrayList<>();

    EditorTextureRegistrationFixture() {
        edt();
        CURRENT.set(this);
        resolver = resolver("5.3.02", null, null);
        model.artMeshes.add(new Mesh(meshSource));
        model.artMeshes.add(new Mesh(source.meshSource2));
    }

    ModelTextures textures() {
        return textures((identity, value) -> {
            if (!"model:1".equals(identity) || value != model) {
                throw new IllegalStateException("stale model");
            }
        });
    }

    private EditorTextureAccess access;

    ModelTextures textures(final EditorParameterCombinedAccess.ModelGuard guard) {
        access = new EditorTextureAccess(resolver, guard, coordinator, () -> binding);
        return access.textures("model:1", source, model);
    }

    Map<String, Integer> decodeOracle() {
        return access.decodeOracleSnapshot();
    }

    /**
     * Faithful offline replica of the 5.3.02 {@code TextureManagerHandler.b()}
     * unused predicate (controller-t014-review 03-native-save-delegates.log):
     * for each raw image wrapper, {@code c(rawGuid)} collects model images whose
     * {@code inputFilterEnv} carries {@code hasCurrentImageGuid() &&
     * currentImageGuid().equals(rawGuid)} — {@code linkedRawImageGuids} is never
     * consulted — and {@code a(guids)} collects art mesh sources whose extension
     * model-image input guid is in that set. A raw image is unused only when
     * both collections are empty; {@code saveDocument} blocks on a nonempty
     * result (job 460fa263, CUB3-3054).
     */
    List<Wrapper> unusedRawImages() {
        edt();
        final List<Wrapper> unused = new ArrayList<>();
        for (final Wrapper wrapper : manager.rawImages) {
            final Guid rawGuid = wrapper.image.guid;
            final Set<Guid> usedBy = new HashSet<>();
            for (final ImageGroup group : manager.modelImageGroups) {
                for (final ModelImage image : group.images) {
                    final Env env = image.inputFilterEnv();
                    if (Boolean.TRUE.equals(resolver.invoke(M + "model-image-filter-env.has-current-image-guid", env))
                            && rawGuid.equals(resolver.invoke(M + "model-image-filter-env.current-image-guid", env))) {
                        usedBy.add(image.guid);
                    }
                }
            }
            boolean meshUse = false;
            if (!usedBy.isEmpty()) {
                for (final MeshSource meshSource : source.meshes()) {
                    final Ext extension = meshSource.textureInputExtension();
                    if (extension == null) continue;
                    for (final Object input : extension.inputs) {
                        if (input instanceof Input modelInput
                                && modelInput.modelImageOrNull() != null
                                && usedBy.contains(modelInput.modelImageOrNull().guid)) {
                            meshUse = true;
                        }
                    }
                }
            }
            if (usedBy.isEmpty() && !meshUse) unused.add(wrapper);
        }
        return unused;
    }

    void undo() {
        edt();
        history.get(--position).undo();
        revision++;
    }

    void redo() {
        edt();
        history.get(position++).redo();
        revision++;
    }

    static void edt() {
        assertTrue(SwingUtilities.isEventDispatchThread(), "native call off EDT");
    }

    @Override
    public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding binding) {
        return currentSource == source && currentModel == model;
    }

    @Override
    public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding binding) {
        return new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE,
                1,
                revision,
                position,
                List.copyOf(entries),
                position > 0,
                position < entries.size(),
                "document-binding",
                "manager-binding");
    }

    @Override
    public Object beginEdit(final EditorAuthoringTransactionCoordinator.Binding binding, final String label) {
        edt();
        begins++;
        return new Group(label);
    }

    @Override
    public void endEdit(
            final EditorAuthoringTransactionCoordinator.Binding binding, final Object edit, final boolean abort) {
        edt();
        if (abort) {
            if (nativeAbort) {
                // Model a host that actually restores the still-open group on abort.
                ((Undo) edit).undoable = true;
                ((Undo) edit).undo();
            }
            return;
        }
        while (history.size() > position) {
            history.remove(position);
            entries.remove(position);
        }
        history.add((Undo) edit);
        ((Undo) edit).undoable = true;
        entries.add(new HistoryEntry(position, "texture", true));
        position++;
        revision++;
    }

    @Override
    public void refresh(
            final EditorAuthoringTransactionCoordinator.Binding binding,
            final Set<EditorRefreshRequirement> requirements) {
        edt();
        refreshes++;
        dirty++;
    }

    @Override
    public Optional<String> prepareHistoryMetadata(
            final EditorAuthoringTransactionCoordinator.Binding binding,
            final Object edit,
            final String transactionId,
            final HistoryEntryDetail detail,
            final Optional<HistoryAction> action) {
        return Optional.empty();
    }

    @Override
    public Optional<String> committedHistoryEntryId(
            final EditorAuthoringTransactionCoordinator.Binding binding,
            final HistorySnapshot before,
            final HistorySnapshot after,
            final String transactionId,
            final String label) {
        return Optional.of("entry-" + after.position());
    }

    @Override
    public String diagnosticId(final String code, final Throwable failure) {
        return code;
    }

    VerifiedMemberResolver resolver(final String version, final String missingAlias, final String missingCapability) {
        final var selectors = new LinkedHashMap<String, StaticSelector>();
        final java.util.function.Consumer<StaticSelector> add = selector -> selectors.put(selector.alias(), selector);

        add.accept(method(M + "guid.value", Guid.class, "value"));
        add.accept(method(M + "id.value", Guid.class, "value"));
        add.accept(StaticSelector.staticMethod(
                M + "app-controller.instance",
                internal(App.class),
                "instance",
                "()" + type(App.class),
                StaticSelector.ACCESS_PUBLIC));
        add.accept(method(M + "app-controller.current-document", App.class, "currentDocument"));
        add.accept(method(M + "app-controller.complete-pack", App.class, "completePack"));
        add.accept(method(M + "model-source.texture-manager", ModelSource.class, "textureManager"));
        add.accept(method(M + "model-source.all-art-meshes", ModelSource.class, "meshes"));
        add.accept(method(M + "model.all-art-meshes", Model.class, "allArtMeshes"));
        add.accept(method(M + "model-source.current-instance", ModelSource.class, "currentInstance"));
        add.accept(method(M + "model-source.update-instances", ModelSource.class, "updateInstances"));
        add.accept(method(M + "texture-manager.raw-images", TextureManager.class, "rawImages"));
        add.accept(method(M + "texture-manager.model-image-groups", TextureManager.class, "modelImageGroups"));
        add.accept(method(M + "texture-manager.all-model-images", TextureManager.class, "allModelImages"));
        add.accept(method(M + "texture-manager.texture-atlases", TextureManager.class, "textureAtlases"));
        add.accept(method(M + "texture-manager.handler", TextureManager.class, "handler"));
        add.accept(StaticSelector.classSelector(M + "modeling-document.class", internal(Document.class)));
        add.accept(method(M + "modeling-document.model-source", Document.class, "source"));
        add.accept(method(M + "modeling-document.edit-mode", Document.class, "editMode"));
        add.accept(method(M + "modeling-document.mark-dirty", Document.class, "markDirty"));
        add.accept(method(M + "edit-mode.begin", EditMode.class, "begin", String.class));
        add.accept(method(M + "edit-mode.end", EditMode.class, "end", boolean.class, Object.class));
        add.accept(method(M + "complete-pack.update-part-palette", App.class, "updateParts", boolean.class));
        add.accept(method(M + "complete-pack.repaint-canvas", App.class, "repaint", boolean.class));
        add.accept(StaticSelector.classSelector(M + "art-mesh.class", internal(Mesh.class)));
        add.accept(method(M + "art-mesh.source", Mesh.class, "source"));
        add.accept(StaticSelector.classSelector(M + "art-mesh-source.class", internal(MeshSource.class)));
        add.accept(method(M + "art-mesh-source.guid", MeshSource.class, "guid"));
        add.accept(method(M + "parameter-controllable-source.id", MeshSource.class, "id"));
        add.accept(method(M + "parameter-controllable-source.locked-in-hierarchy", MeshSource.class, "locked"));
        add.accept(method(M + "parameter-controllable-source.extensions", MeshSource.class, "extensions"));
        add.accept(method(M + "parameter-controllable-source.all-parent-deformers", MeshSource.class, "parents"));
        add.accept(method(M + "parameter-controllable-source.target-deformer-source", MeshSource.class, "deformer"));
        add.accept(method(M + "art-mesh-source.texture-input-extension", MeshSource.class, "textureInputExtension"));
        add.accept(method(M + "art-mesh-source.texture", MeshSource.class, "texture"));
        add.accept(method(M + "art-mesh-source.set-texture", MeshSource.class, "setTexture", Object.class));
        add.accept(method(M + "art-mesh-source.texture-state", MeshSource.class, "textureState"));
        add.accept(method(M + "art-mesh-source.set-texture-state", MeshSource.class, "setTextureState", Object.class));
        add.accept(method(M + "art-mesh-source.uvs", MeshSource.class, "uvs"));
        add.accept(StaticSelector.field(
                M + "texture-state.model-image",
                internal(TextureState.class),
                "MODEL_IMAGE",
                type(TextureState.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));

        add.accept(StaticSelector.constructor(
                M + "image-resource.create",
                internal(ImageResource.class),
                "([B" + type(ColorType.class) + "Z)V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(StaticSelector.field(
                M + "image-color-type.int-argb",
                internal(ColorType.class),
                "c",
                type(ColorType.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        add.accept(method(M + "image-resource.image", ImageResource.class, "image"));
        add.accept(method(M + "image-resource.width", ImageResource.class, "width"));
        add.accept(method(M + "image-resource.height", ImageResource.class, "height"));
        add.accept(StaticSelector.field(
                M + "image-resource.loaded-count",
                internal(ImageResource.class),
                "loadedCount",
                "I",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        add.accept(StaticSelector.field(
                M + "image-resource.loaded-bytes",
                internal(ImageResource.class),
                "loadedBytes",
                "I",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        add.accept(StaticSelector.field(
                M + "image-resource.byte-data-bytes",
                internal(ImageResource.class),
                "byteDataBytes",
                "I",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        add.accept(StaticSelector.constructor(
                M + "layered-image.create",
                internal(LayeredImage.class),
                "(" + type(ImageResource.class) + "Ljava/io/File;Ljava/lang/String;Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(method(M + "layered-image.guid", LayeredImage.class, "guid"));
        add.accept(StaticSelector.classSelector(M + "layered-image.class", internal(LayeredImage.class)));
        add.accept(method(M + "layered-image.name", LayeredImage.class, "name"));
        add.accept(method(M + "layered-image.width", LayeredImage.class, "width"));
        add.accept(method(M + "layered-image.height", LayeredImage.class, "height"));
        add.accept(StaticSelector.constructor(
                M + "layered-image-wrapper.create",
                internal(Wrapper.class),
                "(" + type(LayeredImage.class) + "JJZ)V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(method(M + "layered-image-wrapper.image", Wrapper.class, "image"));
        add.accept(StaticSelector.constructor(
                M + "model-image-group.create",
                internal(ImageGroup.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(StaticSelector.classSelector(M + "model-image-group.class", internal(ImageGroup.class)));
        add.accept(method(M + "model-image-group.group-name", ImageGroup.class, "groupName"));
        add.accept(method(M + "model-image-group.memo", ImageGroup.class, "memo"));
        add.accept(method(M + "model-image-group.add-linked-layered-image", ImageGroup.class, "link", Guid.class));
        add.accept(method(M + "model-image-group.model-images", ImageGroup.class, "modelImages"));
        add.accept(StaticSelector.constructor(
                M + "model-image-guid.create", internal(Guid.class), "()V", StaticSelector.ACCESS_PUBLIC));
        add.accept(StaticSelector.constructor(
                M + "model-image.create",
                internal(ModelImage.class),
                "(" + type(ModelSource.class) + "Ljava/lang/String;" + type(Guid.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(StaticSelector.classSelector(M + "model-image.class", internal(ModelImage.class)));
        add.accept(method(M + "model-image.guid", ModelImage.class, "guid"));
        add.accept(method(M + "model-image.name", ModelImage.class, "name"));
        add.accept(method(M + "model-image.width", ModelImage.class, "width"));
        add.accept(method(M + "model-image.height", ModelImage.class, "height"));
        add.accept(method(M + "model-image.input-filter", ModelImage.class, "inputFilter"));
        add.accept(method(M + "model-image.input-filter-env", ModelImage.class, "inputFilterEnv"));
        add.accept(method(M + "model-image.linked-raw-image-guids", ModelImage.class, "linkedGuids"));
        add.accept(StaticSelector.classSelector(M + "texture-atlas.class", internal(Atlas.class)));
        add.accept(method(M + "texture-atlas.guid", Atlas.class, "guid"));
        add.accept(method(M + "texture-atlas.name", Atlas.class, "name"));
        add.accept(method(M + "texture-atlas.width", Atlas.class, "width"));
        add.accept(method(M + "texture-atlas.height", Atlas.class, "height"));
        add.accept(method(M + "texture-atlas.atlas-version", Atlas.class, "atlasVersion"));
        add.accept(method(M + "texture-atlas.model-images", Atlas.class, "modelImages"));

        add.accept(StaticSelector.field(
                M + "model-image-filter-env.companion",
                internal(Env.class),
                "Companion",
                type(Env.Companion.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        add.accept(method(M + "model-image-filter-env.output-image", Env.Companion.class, "outputImage"));
        add.accept(method(M + "model-image-filter-env.output-transform", Env.Companion.class, "outputTransform"));
        add.accept(method(M + "model-image-filter-env.has-current-image-guid", Env.class, "hasCurrentImageGuid"));
        add.accept(method(M + "model-image-filter-env.current-image-guid", Env.class, "currentImageGuid"));
        add.accept(method(
                M + "model-image-filter-env.set-current-image-guid", Env.class, "setCurrentImageGuid", Guid.class));
        add.accept(StaticSelector.constructor(
                M + "filter-value.create",
                internal(FilterValue.class),
                "(Ljava/lang/String;" + type(FilterValueId.class) + type(Fn0.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(method(M + "filter-value.id", FilterValue.class, "id"));
        add.accept(StaticSelector.constructor(
                M + "empty-filter.create",
                internal(EmptyFilter.class),
                "(" + type(FilterValue.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(method(M + "filter-set.add-filter", FilterSet.class, "addFilter", EmptyFilter.class, Object.class));
        add.accept(method(
                M + "filter-instance.set-input", FilterInstance.class, "setInput", FilterValue.class, Object.class));
        add.accept(StaticSelector.constructor(
                M + "const-value-connector.create",
                internal(ConstConnector.class),
                "(Ljava/lang/Object;)V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(StaticSelector.constructor(
                M + "caffine.create", internal(Affine.class), "()V", StaticSelector.ACCESS_PUBLIC));
        add.accept(method(
                M + "filter-set.connect-env-output",
                FilterSet.class,
                "connectEnvOutput",
                FilterInstance.class,
                FilterValue.class,
                FilterValue.class));
        add.accept(method(M + "filter-set.verify", FilterSet.class, "verify", boolean.class, Object.class));
        add.accept(StaticSelector.constructor(
                M + "verify-collector.create", internal(VerifyCollector.class), "()V", StaticSelector.ACCESS_PUBLIC));

        add.accept(StaticSelector.constructor(
                M + "undo-add-remove-raw-image-wrapper.create",
                internal(RawUndo.class),
                "(" + type(ModelSource.class) + type(Wrapper.class) + "IZ)V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(StaticSelector.constructor(
                M + "undo-add-remove-model-image-group.create",
                internal(GroupUndo.class),
                "(" + type(ModelSource.class) + type(ImageGroup.class) + "IZ)V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(StaticSelector.constructor(
                M + "undo-add-remove-model-image.create",
                internal(ImageUndo.class),
                "(" + type(ImageGroup.class) + type(ModelImage.class) + "IZ)V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(StaticSelector.constructor(
                M + "undo-add-remove-extension.create",
                internal(ExtensionUndo.class),
                "(" + type(MeshSource.class) + type(Ext.class) + "IZ)V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(method(M + "undo.add", Group.class, "add", Undo.class, boolean.class));
        add.accept(method(M + "undo.execute-undo", Undo.class, "undo"));
        add.accept(method("cubism.texture-atlas.undo.force-redo", Undo.class, "forceRedo"));
        add.accept(method("cubism.editor-history.semantic.add-remove.owner", AddRemove.class, "owner"));
        add.accept(StaticSelector.field(
                M + "uu-undo.instance",
                internal(UUUndo.class),
                "a",
                type(UUUndo.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        add.accept(method(
                M + "uu-undo.local-simple-create",
                UUUndo.class,
                "a",
                String.class,
                boolean.class,
                Fn0.class,
                Fn0.class,
                Fn1.class));

        add.accept(StaticSelector.constructor(
                M + "texture-input-extension.create",
                internal(Ext.class),
                "(" + type(MeshSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(StaticSelector.classSelector(M + "texture-input-extension.class", internal(Ext.class)));
        add.accept(StaticSelector.constructor(
                M + "texture-input-model-image.create",
                internal(Input.class),
                "(" + type(Ext.class) + type(ModelImage.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(StaticSelector.classSelector(M + "texture-input-model-image.class", internal(Input.class)));
        add.accept(StaticSelector.classSelector(
                M + "texture-input-texture-atlas-region.class", internal(AtlasInput.class)));
        add.accept(method(M + "texture-atlas-guid", AtlasInput.class, "textureAtlasGuid"));
        add.accept(method(M + "texture-input-extension.add-input", Ext.class, "addTextureInput", Object.class));
        add.accept(method(M + "texture-input-extension.set-current-input", Ext.class, "setCurrent", Object.class));
        add.accept(method(M + "texture-input-extension.current-input", Ext.class, "current"));
        add.accept(method(M + "texture-input-extension.inputs", Ext.class, "textureInputs"));
        add.accept(method(M + "texture-input-extension.model-image-input", Ext.class, "modelImageInput"));
        add.accept(method(M + "texture-input-model-image.model-image", Input.class, "modelImageOrNull"));
        add.accept(method(M + "texture-input-model-image.cached-image", Input.class, "cachedImage", Param.class));
        add.accept(StaticSelector.field(
                M + "cached-image-param.companion",
                internal(Param.class),
                "Companion",
                type(Param.Companion.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        add.accept(method(M + "cached-image-param.default", Param.Companion.class, "defaultParam"));
        add.accept(method(M + "cached-image.texture", CachedImage.class, "texture"));

        add.accept(
                StaticSelector.classSelector(M + "editable-mesh-extension.class", internal(EditableExtension.class)));
        add.accept(method(M + "editable-mesh-extension.owner", EditableExtension.class, "owner"));
        add.accept(method(M + "editable-mesh-extension.locked", EditableExtension.class, "locked"));

        selectors.remove(missingAlias);
        final var capabilities = new HashSet<>(Set.of(
                EditorTextureSelectorContract.READ_CAPABILITY_ID,
                EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
                EditorTextureSelectorContract.REGISTER_BIND_CAPABILITY_ID));
        capabilities.remove(missingCapability);
        return TestVerifiedResolvers.create(
                version,
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                List.copyOf(selectors.values()),
                getClass().getClassLoader());
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final Class<?>... arguments) {
        try {
            final var method = owner.getMethod(name, arguments);
            return StaticSelector.method(
                    alias,
                    internal(owner),
                    name,
                    java.lang.invoke.MethodType.methodType(method.getReturnType(), arguments)
                            .toMethodDescriptorString(),
                    StaticSelector.ACCESS_PUBLIC);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String type(final Class<?> type) {
        return "L" + internal(type) + ";";
    }

    // ---- Kotlin SAM substitutes -------------------------------------------------

    public interface Fn0 {
        Object invoke();
    }

    public interface Fn1 {
        Object invoke(Object event);
    }

    // ---- identity / document / model substitutes --------------------------------

    public record Guid(String value) {
        public Guid() {
            this("generated-" + ++CURRENT.get().guidSequence);
        }
    }

    /** {@code AppController} substitute used by the self-built host path. */
    public static final class App {
        private static final App INSTANCE = new App();

        public static App instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            edt();
            return CURRENT.get().document;
        }

        public App completePack() {
            edt();
            return this;
        }

        public void updateParts(final boolean update) {
            edt();
        }

        public void repaint(final boolean update) {
            edt();
        }
    }

    public static final class Document {
        ModelSource source = new ModelSource();
        final EditMode editMode = new EditMode();
        boolean dirty;

        public ModelSource source() {
            edt();
            return source;
        }
        /** Swaps the active document's model source, simulating a model switch. */
        void switchSource(final ModelSource replacement) {
            edt();
            source = replacement;
        }

        public EditMode editMode() {
            edt();
            return editMode;
        }

        public void markDirty() {
            edt();
            dirty = true;
        }
    }

    /** {@code CEditMode} substitute tracking its open edit like the native class. */
    public static final class EditMode {
        Group open;

        public Group begin(final String label) {
            open = (Group) CURRENT.get().beginEdit(CURRENT.get().binding, label);
            return open;
        }

        public void end(final boolean abort, final Object unused) {
            final Group edit = open;
            open = null;
            CURRENT.get().endEdit(CURRENT.get().binding, edit, abort);
        }
    }

    public static final class ModelSource {
        final TextureManager manager = new TextureManager();
        final Model model = new Model();
        final MeshSource meshSource = new MeshSource(this, "mesh");
        final MeshSource meshSource2 = new MeshSource(this, "mesh2");
        final List<MeshSource> extraMeshes = new ArrayList<>();
        boolean instancesUpdated;

        public TextureManager textureManager() {
            edt();
            return manager;
        }

        /**
         * Mirrors the exact 5.3.02 host: {@code CModelSource.getAllArtMeshes()}
         * yields the <strong>source</strong> collection ({@code CArtMeshSource}
         * elements), not instances. The former {@code List<Mesh>} mirror was a
         * fixture-fidelity gap that let the r1 production bug pass offline
         * while failing on host (job 3a64f0f9).
         */
        public List<MeshSource> meshes() {
            edt();
            final List<MeshSource> all = new ArrayList<>(List.of(meshSource, meshSource2));
            all.addAll(extraMeshes);
            return all;
        }

        public Model currentInstance() {
            edt();
            return model;
        }

        public void updateInstances() {
            edt();
            instancesUpdated = true;
        }
    }

    public static final class Model {
        /**
         * {@code CModel.getAllArtMeshes()} yields the live {@code CArtMesh}
         * instance collection bound back to sources via {@code art-mesh.source}.
         */
        final List<Mesh> artMeshes = new ArrayList<>();

        public List<Mesh> allArtMeshes() {
            edt();
            return artMeshes;
        }
    }

    /**
     * Instance-shaped stub retained solely so the regression suite can pin the
     * fail-closed behaviour against legacy instance-shaped resolution semantics
     * ({@code art-mesh.class} + {@code art-mesh.source}); production no longer
     * walks this shape.
     */
    public record Mesh(MeshSource source) {}

    /** The target ArtMesh source: texture/state fields, extensions, lock, parents. */
    public static final class MeshSource {
        final ModelSource owner;
        final List<Object> extensions = new ArrayList<>();
        final List<Object> parents = new ArrayList<>();
        boolean locked;
        Object texture;
        Object textureState;
        final String meshId;

        public MeshSource(final ModelSource owner, final String meshId) {
            this.owner = owner;
            this.meshId = meshId;
        }

        public Guid id() {
            edt();
            return new Guid(meshId);
        }

        public Guid guid() {
            edt();
            return new Guid(meshId + "-source");
        }

        public boolean locked() {
            edt();
            return locked;
        }

        public List<Object> extensions() {
            edt();
            return extensions;
        }

        public List<Object> parents() {
            edt();
            return parents;
        }

        public Object deformer() {
            edt();
            return parents.isEmpty() ? null : parents.get(0);
        }

        public Ext textureInputExtension() {
            edt();
            for (final Object extension : extensions) {
                if (extension instanceof Ext found) return found;
            }
            return null;
        }

        public Object texture() {
            edt();
            return texture;
        }

        public void setTexture(final Object value) {
            edt();
            this.texture = value;
            CURRENT.get().mutationOrder.add("texture");
        }

        public Object textureState() {
            edt();
            return textureState;
        }

        public void setTextureState(final Object value) {
            edt();
            this.textureState = value;
            CURRENT.get().mutationOrder.add("textureState");
        }

        public float[] uvs() {
            edt();
            return new float[] {0, 0, 1, 0, 1, 1, 0, 1};
        }
    }

    public static final class EditableExtension {
        final MeshSource owner;
        boolean locked;

        public EditableExtension(final MeshSource owner) {
            this.owner = owner;
        }

        public MeshSource owner() {
            return owner;
        }

        public boolean locked() {
            return locked;
        }
    }

    public static final class TextureState {
        public static final TextureState MODEL_IMAGE = new TextureState();
        public static final TextureState NONE = new TextureState();
    }

    // ---- texture library substitutes ---------------------------------------------

    public static final class TextureManager {
        final List<Wrapper> rawImages = new ArrayList<>();
        final List<ImageGroup> modelImageGroups = new ArrayList<>();
        final List<Atlas> textureAtlases = new ArrayList<>();
        final Object handler = new Object();

        public List<Wrapper> rawImages() {
            edt();
            return rawImages;
        }

        public List<ImageGroup> modelImageGroups() {
            edt();
            return modelImageGroups;
        }

        public List<Atlas> textureAtlases() {
            edt();
            return textureAtlases;
        }

        public Object handler() {
            edt();
            return handler;
        }

        public List<ModelImage> allModelImages() {
            edt();
            final List<ModelImage> all = new ArrayList<>();
            for (final ImageGroup group : modelImageGroups) all.addAll(group.images);
            return all;
        }
    }

    /** Read-side {@code CTextureAtlas} substitute for the read-set aliases. */
    public static final class Atlas {
        final Guid guid;

        public Atlas() {
            this(new Guid("atlas"));
        }

        public Atlas(final Guid guid) {
            this.guid = guid;
        }

        public Guid guid() {
            edt();
            return guid;
        }

        public String name() {
            edt();
            return "atlas";
        }

        public int width() {
            edt();
            return 0;
        }

        public int height() {
            edt();
            return 0;
        }

        public int atlasVersion() {
            edt();
            return 3;
        }

        public List<ModelImage> modelImages() {
            edt();
            return List.of();
        }
    }

    public static final class ColorType {
        public static final ColorType c = new ColorType();
    }

    /**
     * {@code CImageResource} substitute: retains the supplied array reference,
     * decodes lazily once, and counts loads exactly like the native counters.
     */
    public static final class ImageResource {
        public static int loadedCount;
        public static int loadedBytes;
        public static int byteDataBytes;
        final byte[] data;
        WritableImage image;

        public ImageResource(final byte[] data, final ColorType colorType, final boolean unmanaged) {
            this.data = Objects.requireNonNull(data, "data");
        }

        public WritableImage image() {
            edt();
            if (image == null) {
                image = decode(data);
                loadedCount++;
                loadedBytes += image.width() * image.height() * 4;
                byteDataBytes += data.length;
            }
            return image;
        }

        public int width() {
            return image().width();
        }

        public int height() {
            return image().height();
        }

        static WritableImage decode(final byte[] data) {
            try {
                final var buffered = javax.imageio.ImageIO.read(new ByteArrayInputStream(data));
                if (buffered == null) throw new IllegalStateException("decode failed");
                return new WritableImage(buffered.getWidth(), buffered.getHeight());
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("decode failed", failure);
            }
        }
    }

    public record WritableImage(int width, int height) {}

    public static final class LayeredImage {
        final ImageResource resource;
        final String name;
        final Guid guid;

        public LayeredImage(
                final ImageResource resource, final java.io.File file, final String imageName, final String layerName) {
            this.resource = Objects.requireNonNull(resource, "resource");
            this.name = Objects.requireNonNull(imageName, "imageName");
            Objects.requireNonNull(layerName, "layerName");
            resource.width();
            resource.height();
            guid = new Guid("raw-" + (++CURRENT.get().guidSequence));
        }

        public Guid guid() {
            edt();
            return guid;
        }

        public String name() {
            edt();
            return name;
        }

        public int width() {
            edt();
            return resource.width();
        }

        public int height() {
            edt();
            return resource.height();
        }
    }

    public static final class Wrapper {
        final LayeredImage image;

        public Wrapper(final LayeredImage image, final long order, final long flags, final boolean pending) {
            this.image = Objects.requireNonNull(image, "image");
        }

        public LayeredImage image() {
            edt();
            return image;
        }
    }

    public static final class ImageGroup {
        final String name;
        final List<ModelImage> images = new ArrayList<>();
        final List<Guid> linked = new ArrayList<>();

        public ImageGroup(final String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public String groupName() {
            edt();
            return name;
        }

        public String memo() {
            edt();
            return "";
        }

        public void link(final Guid layeredGuid) {
            edt();
            linked.add(layeredGuid);
        }

        public List<ModelImage> modelImages() {
            edt();
            return images;
        }
    }

    public static final class ModelImage {
        final Guid guid;
        final String name;
        final FilterSet inputFilter = new FilterSet();
        final Env env = new Env();
        final List<Guid> linkedGuids = new ArrayList<>();

        public ModelImage(final ModelSource source, final String name, final Guid guid) {
            this.guid = Objects.requireNonNull(guid, "guid");
            this.name = Objects.requireNonNull(name, "name");
        }

        public Guid guid() {
            edt();
            return guid;
        }

        public String name() {
            edt();
            return name;
        }

        public int width() {
            edt();
            return 256;
        }

        public int height() {
            edt();
            return 128;
        }

        public FilterSet inputFilter() {
            edt();
            return inputFilter;
        }

        public Env inputFilterEnv() {
            edt();
            return env;
        }

        public List<Guid> linkedGuids() {
            edt();
            return linkedGuids;
        }
    }

    // ---- model-image filter graph substitutes --------------------------------------

    public static final class FilterValueId {}

    public static final class FilterValue {
        private final FilterValueId id;

        public FilterValue(final String name, final FilterValueId id, final Fn0 supplier) {
            Objects.requireNonNull(name, "name");
            this.id = Objects.requireNonNull(id, "id");
        }

        public FilterValueId id() {
            edt();
            return id;
        }
    }

    public static final class EmptyFilter {
        public EmptyFilter(final FilterValue value) {
            Objects.requireNonNull(value, "value");
        }
    }

    public static final class FilterInstance {
        FilterValue connected;
        final java.util.Map<FilterValue, Object> inputs = new LinkedHashMap<>();

        public void setInput(final FilterValue value, final Object connector) {
            edt();
            inputs.put(value, connector);
        }
    }

    public static final class ConstConnector {
        public ConstConnector(final Object value) {}
    }

    public static final class Affine {
        public Affine() {}
    }

    /**
     * {@code ModelImageFilterEnv} substitute: the two companion outputs plus the
     * {@code mi_input_currentImageGuid} env value. Mirrors the native setter
     * exactly (r21 bytecode): non-null writes the env value, {@code null}
     * removes it so {@code getHasCurrentImageGuid()} flips back to false.
     */
    public static final class Env {
        public static final Companion Companion = new Companion();
        Guid currentImageGuid;

        public boolean hasCurrentImageGuid() {
            edt();
            return currentImageGuid != null;
        }

        public Guid currentImageGuid() {
            edt();
            return currentImageGuid;
        }

        public void setCurrentImageGuid(final Guid guid) {
            edt();
            currentImageGuid = guid;
        }

        public static final class Companion {
            final FilterValue image = new FilterValue("Image", new FilterValueId(), null);
            final FilterValue transform = new FilterValue("Transform", new FilterValueId(), null);

            public FilterValue outputImage() {
                edt();
                return image;
            }

            public FilterValue outputTransform() {
                edt();
                return transform;
            }
        }
    }

    public static final class FilterSet {
        final List<FilterInstance> instances = new ArrayList<>();
        boolean verified;
        Object lastVerifyCollector;

        public FilterInstance addFilter(final EmptyFilter definition, final Object id) {
            edt();
            final FilterInstance instance = new FilterInstance();
            instances.add(instance);
            return instance;
        }

        public void connectEnvOutput(final FilterInstance instance, final FilterValue value, final FilterValue output) {
            edt();
            instance.connected = output;
        }

        public void verify(final boolean strict, final Object context) {
            edt();
            // Host parity (job b9cc6bc0): the real FilterSet.verify opens with
            // Intrinsics.checkNotNullParameter on the collector — the native call
            // site passes a default-constructed collector, never null.
            assertTrue(context != null, "verify collector must not be null");
            lastVerifyCollector = context;
            if (instances.size() != 2 || instances.stream().anyMatch(instance -> instance.connected == null)) {
                throw new IllegalStateException("unverified filter graph");
            }
            verified = true;
        }
    }

    /** Stand-in for the native default verify collector {@code com/live2d/util/verify/a}. */
    public static final class VerifyCollector {}

    // ---- texture-input extension substitutes -----------------------------------------

    public static final class Ext {
        final MeshSource owner;
        final List<Object> inputs = new ArrayList<>();
        Object current;

        public Ext(final MeshSource owner) {
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        public void addTextureInput(final Object input) {
            edt();
            inputs.add(input);
        }

        public void setCurrent(final Object input) {
            edt();
            current = input;
        }

        public Object current() {
            edt();
            return current;
        }

        public List<Object> textureInputs() {
            edt();
            return inputs;
        }

        public Input modelImageInput() {
            edt();
            for (final Object input : inputs) {
                if (input instanceof Input found) return found;
            }
            return null;
        }
    }

    /**
     * {@code CTextureInput_TextureAtlasRegion} substitute: a texture input that
     * carries a texture-atlas GUID instead of a model image.
     */
    public static final class AtlasInput {
        final Guid atlasGuid;

        public AtlasInput(final Guid atlasGuid) {
            this.atlasGuid = Objects.requireNonNull(atlasGuid, "atlasGuid");
        }

        public Guid textureAtlasGuid() {
            edt();
            return atlasGuid;
        }
    }

    public static final class Input {
        final Ext owner;
        final ModelImage modelImage;

        public Input(final Ext owner, final ModelImage modelImage) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.modelImage = Objects.requireNonNull(modelImage, "modelImage");
        }

        private Input(final Ext owner, final ModelImage modelImage, final boolean dangling) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.modelImage = modelImage;
        }
        /** A model-image input whose image has gone missing ({@code getModelImageOrNull → null}). */
        public static Input dangling(final Ext owner) {
            return new Input(owner, null, true);
        }

        public ModelImage modelImageOrNull() {
            edt();
            return modelImage;
        }

        public CachedImage cachedImage(final Param param) {
            edt();
            if (CURRENT.get().failCachedImage) {
                throw new IllegalStateException("cached image unavailable");
            }
            final Guid rawGuid = modelImage.linkedGuids.get(0);
            for (final Wrapper wrapper : owner.owner.owner.manager.rawImages) {
                if (wrapper.image.guid.equals(rawGuid)) {
                    return new CachedImage(new GLTexture(wrapper.image.resource.image()));
                }
            }
            throw new IllegalStateException("linked raw image is not registered");
        }
    }

    public static final class Param {
        public static final Companion Companion = new Companion();

        public static final class Companion {
            final Param defaultParam = new Param();

            public Param defaultParam() {
                edt();
                return defaultParam;
            }
        }
    }

    public record GLTexture(WritableImage image) {}

    public record CachedImage(GLTexture texture) {}

    // ---- Undo substitutes --------------------------------------------------------------

    /**
     * {@code ACUndoable} substitute with the verified flag transfer: fresh
     * instances are neither undoable nor redoable; {@code forceRedo} enables
     * redo and applies it; undo/redo flip the flags exactly.
     */
    public abstract static class Undo {
        boolean undoable, redoable;
        final String label;

        Undo(final String label) {
            this.label = label;
        }

        public void undo() {
            if (!undoable) throw new IllegalStateException("not undoable");
            performUndo();
            undoable = false;
            redoable = true;
        }

        public void redo() {
            if (!redoable) throw new IllegalStateException("not redoable");
            performRedo();
            undoable = true;
            redoable = false;
        }

        public Undo forceRedo() {
            redoable = true;
            redo();
            return this;
        }

        abstract void performUndo();

        abstract void performRedo();
    }

    /** {@code Undo_AddOrRemove} substitute: constructor is pure; owner non-null. */
    abstract static class AddRemove extends Undo {
        final Object owner, item;
        final int index;
        final boolean isAdd;

        AddRemove(final Object owner, final Object item, final int index, final boolean isAdd) {
            super(isAdd ? "Add" : "Remove");
            this.owner = Objects.requireNonNull(owner, "owner");
            this.item = Objects.requireNonNull(item, "item");
            this.index = index;
            this.isAdd = isAdd;
        }

        public Object owner() {
            edt();
            return owner;
        }

        void performRedo() {
            if (isAdd) doAdd();
            else doRemove();
            CURRENT.get()
                    .mutationOrder
                    .add((isAdd ? "+" : "-") + item.getClass().getSimpleName());
        }

        void performUndo() {
            final var fixture = CURRENT.get();
            if (fixture.failUndo) throw new IllegalStateException("compensation undo failed");
            if (isAdd) doRemove();
            else doAdd();
            fixture.mutationOrder.add((isAdd ? "-" : "+") + item.getClass().getSimpleName());
        }

        abstract void doAdd();

        abstract void doRemove();

        static <T> void insert(final List<T> list, final int index, final T item) {
            if (index < 0 || index >= list.size()) list.add(item);
            else list.add(index, item);
        }
    }

    public static final class RawUndo extends AddRemove {
        public RawUndo(final ModelSource owner, final Wrapper item, final int index, final boolean add) {
            super(owner, item, index, add);
        }

        void doAdd() {
            insert(((ModelSource) owner).manager.rawImages, index, (Wrapper) item);
        }

        void doRemove() {
            ((ModelSource) owner).manager.rawImages.remove(item);
        }
    }

    public static final class GroupUndo extends AddRemove {
        public GroupUndo(final ModelSource owner, final ImageGroup item, final int index, final boolean add) {
            super(owner, item, index, add);
        }

        void doAdd() {
            insert(((ModelSource) owner).manager.modelImageGroups, index, (ImageGroup) item);
        }

        void doRemove() {
            ((ModelSource) owner).manager.modelImageGroups.remove(item);
        }
    }

    public static final class ImageUndo extends AddRemove {
        public ImageUndo(final ImageGroup owner, final ModelImage item, final int index, final boolean add) {
            super(owner, item, index, add);
        }

        void doAdd() {
            if (CURRENT.get().failImageAdd) {
                throw new IllegalStateException("model image add failed");
            }
            insert(((ImageGroup) owner).images, index, (ModelImage) item);
        }

        void doRemove() {
            ((ImageGroup) owner).images.remove(item);
        }
    }

    public static final class ExtensionUndo extends AddRemove {
        public ExtensionUndo(final MeshSource owner, final Ext item, final int index, final boolean add) {
            super(owner, item, index, add);
        }

        void doAdd() {
            insert(((MeshSource) owner).extensions, index, (Ext) item);
        }

        void doRemove() {
            ((MeshSource) owner).extensions.remove(item);
        }
    }

    /** {@code UUUndo.a} substitute: execute=false registers undoable only. */
    public static final class UUUndo {
        public static final UUUndo a = new UUUndo();

        public Undo a(
                final String label, final boolean execute, final Fn0 onRedo, final Fn0 onUndo, final Fn1 listener) {
            edt();
            final SimpleUndo undo = new SimpleUndo(label, onRedo, onUndo);
            if (execute) {
                undo.redoable = true;
                undo.redo();
            } else {
                undo.undoable = true;
                undo.redoable = false;
            }
            return undo;
        }
    }

    static final class SimpleUndo extends Undo {
        final Fn0 onRedo, onUndo;

        SimpleUndo(final String label, final Fn0 onRedo, final Fn0 onUndo) {
            super(label);
            this.onRedo = onRedo;
            this.onUndo = onUndo;
        }

        void performRedo() {
            onRedo.invoke();
        }

        void performUndo() {
            onUndo.invoke();
        }
    }

    /**
     * Root edit substitute: {@code add(child, true)} performs pure admission —
     * enqueue without executing redo — honoring reject/throw switches.
     */
    public static final class Group extends Undo {
        final List<Undo> children = new ArrayList<>();

        public Group(final String label) {
            super(label);
        }

        public boolean add(final Undo child, final boolean preparing) {
            edt();
            final var fixture = CURRENT.get();
            if (fixture.throwAdd) throw new IllegalStateException("root admission");
            if (fixture.rejectAdd) return false;
            children.add(child);
            return true;
        }

        void performUndo() {
            for (int index = children.size() - 1; index >= 0; index--) {
                children.get(index).undo();
            }
        }

        void performRedo() {
            children.forEach(Undo::redo);
        }
    }
}
