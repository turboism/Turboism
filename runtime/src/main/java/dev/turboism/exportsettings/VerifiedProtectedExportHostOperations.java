package dev.turboism.exportsettings;

import dev.turboism.mapping.verification.ProtectedExportVerificationManifest;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * {@link ProtectedExportHostOperations} backed solely by the pinned record slice of the
 * admitted exact Cubism build (5.2.03, 5.3.02 or 5.3.03).
 *
 * <p>Every call resolves through {@link VerifiedMemberResolver}, which re-attests the host
 * classloader and member shape on each access — a stale or tampered host fails closed
 * rather than invoking a mismatched member.</p>
 */
public final class VerifiedProtectedExportHostOperations implements ProtectedExportHostOperations {

    private static final String PREFIX = "cubism.protected-export.";

    private static final String APP_CONTROLLER_CLASS = PREFIX + "app-controller.class";
    private static final String APP_INSTANCE = PREFIX + "app-controller.instance";
    private static final String CURRENT_DOCUMENT = PREFIX + "app-controller.current-document";
    private static final String CURRENT_PROJECT = PREFIX + "app-controller.current-project";
    private static final String COMMAND_OPEN = PREFIX + "app-controller.open";
    private static final String COMMAND_CLOSE = PREFIX + "app-controller.close-content";
    private static final String MAIN_FRAME_CTRL = PREFIX + "app-controller.main-frame-ctrl";
    private static final String MAIN_FRAME = PREFIX + "main-frame-ctrl.main-frame";
    private static final String MAIN_FRAME_CTRL_CLASS = PREFIX + "main-frame-ctrl.class";
    private static final String PROJECT_CLASS = PREFIX + "project.class";
    private static final String PROJECT_CHILDREN = PREFIX + "project.children";

    private static final String DOCUMENT_CLASS = PREFIX + "document.class";
    private static final String DOC_MODEL_SOURCE = PREFIX + "document.model-source";
    private static final String DOC_FILE_CONTENT = PREFIX + "document.file-content";
    private static final String DOC_SELECTOR = PREFIX + "document.selector";
    private static final String DOC_EDIT_MODE_CURRENT = PREFIX + "document.edit-mode-current";
    private static final String DOC_EDIT_MODE_MAIN = PREFIX + "document.edit-mode-main";
    private static final String DOC_MARK_SAVED = PREFIX + "document.mark-saved";
    private static final String DOC_FILE = PREFIX + "document.file";
    private static final String DOC_UNDO = PREFIX + "document.undo-manager";

    private static final String FILE_CONTENT_CLASS = PREFIX + "file-content.class";
    private static final String FILE_CONTENT_FILE = PREFIX + "file-content.file";
    private static final String FILE_CONTENT_MODIFIED = PREFIX + "file-content.modified";

    private static final String UNDO_CLASS = PREFIX + "undo-manager.class";
    private static final String UNDO_POSITION = PREFIX + "undo-manager.position";
    private static final String UNDO_EDIT_COUNT = PREFIX + "undo-manager.edit-count";
    private static final String UNDO_CAN_UNDO = PREFIX + "undo-manager.can-undo";

    private static final String SELECTOR_INTERFACE = PREFIX + "selector-interface.class";
    private static final String SELECTOR_CLASS = PREFIX + "selector.class";
    private static final String SELECTOR_CLEAR = PREFIX + "selector.clear";
    private static final String SELECTOR_SELECTED = PREFIX + "selector.selected";
    private static final String SELECTOR_SELECTED_COUNT = PREFIX + "selector.selected-count";
    private static final String SELECTOR_ADD_SOURCE = PREFIX + "selector.add-source";
    private static final String SELECTOR_SELECTED_DEFORMERS = PREFIX + "selector.selected-deformers";

    private static final String EDIT_MODE_BASE_CLASS = PREFIX + "edit-mode-base.class";
    private static final String EDIT_MODE_CLASS = PREFIX + "edit-mode.class";
    private static final String EDIT_MODE_APPLY = PREFIX + "edit-mode.apply-deformer";

    private static final String MODEL_SOURCE_CLASS = PREFIX + "model-source.class";
    private static final String MODEL_CLASS = PREFIX + "model.class";
    private static final String MODEL_PARAMETER_SET = PREFIX + "model.parameter-set";
    private static final String MS_DOCUMENT = PREFIX + "model-source.document";
    private static final String MS_INSTANCE = PREFIX + "model-source.current-instance";
    private static final String MS_DEFORMERS = PREFIX + "model-source.all-deformers";
    private static final String MS_OBJECTS = PREFIX + "model-source.all-objects";
    private static final String MS_ART_MESHES = PREFIX + "model-source.all-art-meshes";
    private static final String MS_PARTS = PREFIX + "model-source.all-parts";
    private static final String MS_ROOT_PART = PREFIX + "model-source.root-part";
    private static final String MS_SAVE_MODEL = PREFIX + "model-source.save-model";
    private static final String MS_PARAMETERS = PREFIX + "model-source.all-parameters";
    private static final String MS_PHYSICS = PREFIX + "model-source.all-physics-settings";
    private static final String MS_MOTION_SYNC =
        PREFIX + "model-source.all-motion-sync-settings";
    private static final String MS_GUID = PREFIX + "model-source.guid";
    private static final String MS_CONTAIN_MULTIPLY =
        PREFIX + "model-source.contain-multiply-color";
    private static final String MS_CONTAIN_SCREEN =
        PREFIX + "model-source.contain-screen-color";
    private static final String MS_CONTAIN_MORPH =
        PREFIX + "model-source.contain-morph-target";
    private static final String MS_CONTAIN_MORPH_ENH =
        PREFIX + "model-source.contain-morph-target-enhancement";
    private static final String MS_CONTAIN_ADVANCED_BLEND =
        PREFIX + "model-source.contain-advanced-blend";
    private static final String MS_CONTAIN_ART_PATH =
        PREFIX + "model-source.contain-art-path";
    private static final String MS_CONTAIN_ALIAS =
        PREFIX + "model-source.contain-alias";
    private static final String MS_CONTAIN_INVERT_CLIP =
        PREFIX + "model-source.contain-invert-clipping";
    private static final String MS_CONTAIN_QUAD =
        PREFIX + "model-source.contain-quad-transform";
    private static final String MS_CONTAIN_OFFSCREEN =
        PREFIX + "model-source.contain-offscreen-rendering";
    private static final String MS_CONTAIN_MOTION_SYNC =
        PREFIX + "model-source.contain-motion-sync";
    private static final String MS_CONTAIN_MOTION_SYNC_FIX =
        PREFIX + "model-source.contain-motion-sync-correction";

    private static final String SOURCE_MORPH_SET =
        PREFIX + "source.keyform-morph-target-set";
    private static final String SOURCE_EXT_MORPH_SET =
        PREFIX + "source.extended-morph-target-set";
    private static final String SOURCE_EXTENSIONS = PREFIX + "source.extensions";
    private static final String MORPH_SET_CLASS = PREFIX + "morph-target-set.class";
    private static final String MORPH_SET_TARGETS = PREFIX + "morph-target-set.morph-targets";

    private static final String SOURCE_GUID = PREFIX + "source.guid";
    private static final String SOURCE_ID = PREFIX + "source.id";
    private static final String SOURCE_LOCAL_NAME = PREFIX + "source.local-name";
    private static final String SOURCE_SET_LOCAL_NAME = PREFIX + "source.set-local-name";

    private static final String DEFORMER_CLASS = PREFIX + "deformer-source.class";
    private static final String DEFORMER_GUID = PREFIX + "deformer.guid";
    private static final String DEFORMER_TARGET = PREFIX + "deformer.target-guid";
    private static final String DEFORMER_CHILDREN = PREFIX + "deformer-source.children";
    private static final String WARP_CLASS = PREFIX + "warp-deformer.class";
    private static final String ROTATION_CLASS = PREFIX + "rotation-deformer.class";
    private static final String ART_MESH_CLASS = PREFIX + "art-mesh.class";
    private static final String PART_CLASS = PREFIX + "part.class";
    private static final String PARAMETER_CLASS = PREFIX + "parameter.class";
    private static final String DRAWABLE_CLASS = PREFIX + "drawable-source.class";
    private static final String DRAWABLE_ID_GET = PREFIX + "drawable.id";
    private static final String DRAWABLE_ID_SET = PREFIX + "drawable.set-id";
    private static final String DRAWABLE_ID_CLASS = PREFIX + "drawable-id.class";
    private static final String DRAWABLE_ID_CREATE = PREFIX + "drawable-id.create";
    private static final String PARAMETER_ID_CLASS = PREFIX + "parameter-id.class";
    private static final String PARAMETER_SOURCE_ID = PREFIX + "parameter-source.id";
    private static final String PARAMETER_SOURCE_NAME = PREFIX + "parameter-source.name";
    private static final String PARAMETER_SOURCE_MIN = PREFIX + "parameter-source.min-value";
    private static final String PARAMETER_SOURCE_MAX = PREFIX + "parameter-source.max-value";
    private static final String PARAMETER_SOURCE_DEFAULT =
        PREFIX + "parameter-source.default-value";
    private static final String PARAMETER_SOURCE_REPEAT = PREFIX + "parameter-source.repeat";
    private static final String GUID_CLASS = PREFIX + "guid.class";
    private static final String GUID_UUID = PREFIX + "guid.uuid-string";
    private static final String ID_CLASS = PREFIX + "id.class";
    private static final String ID_STRING = PREFIX + "id.id-string";

    private static final String PARAMETER_SET_CLASS = PREFIX + "parameter-set.class";
    private static final String PARAMETER_SET_PARAMETERS = PREFIX + "parameter-set.parameters";
    private static final String PARAMETER_INSTANCE_CLASS = PREFIX + "parameter-instance.class";
    private static final String PARAMETER_INSTANCE_VALUE = PREFIX + "parameter-instance.value";
    private static final String PARAMETER_INSTANCE_ID = PREFIX + "parameter-instance.id";
    private static final String PARAMETER_INSTANCE_SET_VALUE =
        PREFIX + "parameter-instance.set-value";

    private static final String MODEL_REINIT_EXE = PREFIX + "model.reinit-instance-exe";
    private static final String MODEL_ART_MESHES = PREFIX + "model.all-art-meshes";
    private static final String ART_MESH_INSTANCE_CLASS =
        PREFIX + "art-mesh-instance.class";
    private static final String ART_MESH_INSTANCE_SOURCE =
        PREFIX + "art-mesh-instance.source";
    private static final String ART_MESH_INSTANCE_FORM =
        PREFIX + "art-mesh-instance.calculated-form";
    private static final String ART_MESH_FORM_CLASS = PREFIX + "art-mesh-form.class";
    private static final String ART_MESH_FORM_POSITIONS =
        PREFIX + "art-mesh-form.positions";

    private static final String GRID_CLASS = PREFIX + "keyform-grid.class";
    private static final String BINDING_CLASS = PREFIX + "keyform-binding.class";
    private static final String EXT_TYPE_CLASS = PREFIX + "extended-interpolation-type.class";
    private static final String SOURCE_GRID = PREFIX + "source.keyform-grid";
    private static final String SOURCE_EXT_GRID = PREFIX + "source.extended-keyform-grid";
    private static final String GRID_BINDINGS = PREFIX + "keyform-grid.bindings";
    private static final String BINDING_EXT_TYPE = PREFIX + "keyform-binding.extended-type";
    private static final String BINDING_ILLEGAL = PREFIX + "keyform-binding.illegal-extended";
    private static final String BINDING_PARAMETER_ID =
        PREFIX + "keyform-binding.parameter-id";
    private static final String BINDING_KEYS = PREFIX + "keyform-binding.keys";
    private static final String PART_CHILD_GUIDS = PREFIX + "part.child-guids";

    private static final String DIALOG_CLASS = PREFIX + "export-dialog.class";
    private static final String DIALOG_MODEL_SOURCE = PREFIX + "export-dialog.model-source";
    private static final String DRIVER_CLASS = PREFIX + "export-driver.class";
    private static final String DRIVER_INSTANCE = PREFIX + "export-driver.instance";
    private static final String DRIVER_EXPORT = PREFIX + "export-driver.export";

    private static final String FILE_CACHE_CLASS = PREFIX + "file-cache.class";
    private static final String FILE_CACHE_INSTANCE = PREFIX + "file-cache.instance";
    private static final String FILE_CACHE_HANDLES = PREFIX + "file-cache.handles";
    private static final String FILE_CACHE_BY_FILE = PREFIX + "file-cache.handle-by-file";
    private static final String FILE_CACHE_REMOVE = PREFIX + "file-cache.remove";
    private static final String FILE_HANDLE_CLASS = PREFIX + "file-handle.class";
    private static final String FILE_HANDLE_FILE = PREFIX + "file-handle.file";
    private static final String FILE_HANDLE_LOADER = PREFIX + "file-handle.loader";
    private static final String FILE_HANDLE_LISTENERS = PREFIX + "file-handle.listeners";
    private static final String FILE_HANDLE_UNLOAD = PREFIX + "file-handle.unload";
    private static final String FILE_HANDLE_RELEASE = PREFIX + "file-handle.release";

    private static final Set<String> METHOD_ALIASES_USED = Set.of(
        APP_INSTANCE, CURRENT_DOCUMENT, CURRENT_PROJECT, COMMAND_OPEN, COMMAND_CLOSE,
        MAIN_FRAME_CTRL, MAIN_FRAME, PROJECT_CHILDREN,
        DOC_MODEL_SOURCE, DOC_FILE_CONTENT, DOC_SELECTOR, DOC_EDIT_MODE_CURRENT,
        DOC_EDIT_MODE_MAIN, DOC_MARK_SAVED, DOC_FILE, DOC_UNDO,
        FILE_CONTENT_FILE, FILE_CONTENT_MODIFIED,
        UNDO_POSITION, UNDO_EDIT_COUNT, UNDO_CAN_UNDO,
        SELECTOR_CLEAR, SELECTOR_SELECTED, SELECTOR_SELECTED_COUNT,
        SELECTOR_ADD_SOURCE, SELECTOR_SELECTED_DEFORMERS, EDIT_MODE_APPLY,
        MS_DOCUMENT, MS_INSTANCE, MS_DEFORMERS, MS_OBJECTS, MS_ART_MESHES, MS_PARTS,
        MS_ROOT_PART, MS_PARAMETERS, MS_PHYSICS, MS_MOTION_SYNC, MS_GUID,
        MS_SAVE_MODEL,
        MS_CONTAIN_MULTIPLY, MS_CONTAIN_SCREEN, MS_CONTAIN_MORPH,
        MS_CONTAIN_MORPH_ENH, MS_CONTAIN_ADVANCED_BLEND, MS_CONTAIN_ART_PATH,
        MS_CONTAIN_ALIAS, MS_CONTAIN_INVERT_CLIP, MS_CONTAIN_QUAD,
        MS_CONTAIN_OFFSCREEN, MS_CONTAIN_MOTION_SYNC, MS_CONTAIN_MOTION_SYNC_FIX,
        SOURCE_MORPH_SET, SOURCE_EXT_MORPH_SET, SOURCE_EXTENSIONS,
        MORPH_SET_TARGETS,
        MODEL_PARAMETER_SET,
        SOURCE_GUID, SOURCE_ID, SOURCE_LOCAL_NAME, SOURCE_SET_LOCAL_NAME,
        SOURCE_GRID, SOURCE_EXT_GRID, GRID_BINDINGS, BINDING_EXT_TYPE, BINDING_ILLEGAL,
        BINDING_PARAMETER_ID, BINDING_KEYS, PART_CHILD_GUIDS,
        DEFORMER_GUID, DEFORMER_TARGET, DEFORMER_CHILDREN,
        DRAWABLE_ID_GET, DRAWABLE_ID_SET, DRAWABLE_ID_CREATE,
        GUID_UUID, ID_STRING,
        PARAMETER_SET_PARAMETERS, PARAMETER_INSTANCE_VALUE, PARAMETER_INSTANCE_ID,
        PARAMETER_INSTANCE_SET_VALUE, MODEL_REINIT_EXE, MODEL_ART_MESHES,
        ART_MESH_INSTANCE_SOURCE, ART_MESH_INSTANCE_FORM, ART_MESH_FORM_POSITIONS,
        PARAMETER_SOURCE_ID, PARAMETER_SOURCE_NAME,
        PARAMETER_SOURCE_MIN, PARAMETER_SOURCE_MAX, PARAMETER_SOURCE_DEFAULT,
        PARAMETER_SOURCE_REPEAT,
        DIALOG_MODEL_SOURCE, DRIVER_INSTANCE, DRIVER_EXPORT,
        FILE_CACHE_INSTANCE, FILE_CACHE_HANDLES, FILE_CACHE_BY_FILE, FILE_CACHE_REMOVE,
        FILE_HANDLE_FILE, FILE_HANDLE_LOADER, FILE_HANDLE_LISTENERS,
        FILE_HANDLE_UNLOAD, FILE_HANDLE_RELEASE
    );
    private static final Set<String> CLASS_ALIASES_REQUIRED = Set.of(
        APP_CONTROLLER_CLASS, PROJECT_CLASS, MAIN_FRAME_CTRL_CLASS,
        DOCUMENT_CLASS, FILE_CONTENT_CLASS, UNDO_CLASS,
        SELECTOR_INTERFACE, SELECTOR_CLASS, EDIT_MODE_BASE_CLASS, EDIT_MODE_CLASS,
        MODEL_SOURCE_CLASS, MODEL_CLASS, DEFORMER_CLASS, WARP_CLASS, ROTATION_CLASS,
        ART_MESH_CLASS, PART_CLASS, PARAMETER_CLASS, DRAWABLE_CLASS,
        DRAWABLE_ID_CLASS, PARAMETER_ID_CLASS, GUID_CLASS, ID_CLASS,
        PARAMETER_SET_CLASS, PARAMETER_INSTANCE_CLASS,
        ART_MESH_INSTANCE_CLASS, ART_MESH_FORM_CLASS,
        GRID_CLASS, BINDING_CLASS, EXT_TYPE_CLASS, MORPH_SET_CLASS,
        DIALOG_CLASS, DRIVER_CLASS, FILE_CACHE_CLASS, FILE_HANDLE_CLASS
    );

    /** Aliases independently required by this implementation, not copied from its trust manifest. */
    public static final Set<String> REQUIRED_ALIASES = requiredAliases();

    /**
     * {@code contain*} gates that only exist on Cubism 5.3.x {@code CModelSource}. The
     * reviewed 5.2.03 record omits them because the gated features did not exist in that
     * release; they remain mandatory on every 5.3.x admission.
     */
    private static final Set<String> CUBISM_5_3_ONLY_GATE_ALIASES = Set.of(
        MS_CONTAIN_ADVANCED_BLEND, MS_CONTAIN_ALIAS, MS_CONTAIN_OFFSCREEN
    );

    private static final Set<String> CUBISM_5_2_METHOD_ALIASES_USED =
        without(METHOD_ALIASES_USED, CUBISM_5_3_ONLY_GATE_ALIASES);

    /** Exact alias roster this implementation requires on the reviewed 5.2.03 build. */
    public static final Set<String> CUBISM_5_2_REQUIRED_ALIASES =
        without(REQUIRED_ALIASES, CUBISM_5_3_ONLY_GATE_ALIASES);

    private static Set<String> requiredAliases() {
        final java.util.HashSet<String> aliases = new java.util.HashSet<>(METHOD_ALIASES_USED);
        aliases.addAll(CLASS_ALIASES_REQUIRED);
        return Set.copyOf(aliases);
    }

    private static Set<String> without(final Set<String> aliases, final Set<String> excluded) {
        final java.util.HashSet<String> remaining = new java.util.HashSet<>(aliases);
        remaining.removeAll(excluded);
        return Set.copyOf(remaining);
    }

    /** Exact non-class aliases invoked by this implementation. */
    public static Set<String> methodAliasesUsed() {
        return METHOD_ALIASES_USED;
    }

    /** Exact non-class aliases invoked by this implementation on the reviewed 5.2.03 build. */
    public static Set<String> cubism52MethodAliasesUsed() {
        return CUBISM_5_2_METHOD_ALIASES_USED;
    }

    /** Exact class aliases used for runtime type validation by this implementation. */
    public static Set<String> classAliasesUsed() {
        return CLASS_ALIASES_REQUIRED;
    }

    /**
     * The model source's own {@code contain*} gates — the host's semantic answer
     * to feature content the object census cannot see. Each predicate maps to a
     * stable family token; any {@code true} is a hard admission rejection.
     */
    private static final Map<String, String> FEATURE_GATES = featureGates();

    private static final Map<String, String> CUBISM_5_2_FEATURE_GATES =
        withoutGates(FEATURE_GATES, CUBISM_5_3_ONLY_GATE_ALIASES);

    private static Map<String, String> featureGates() {
        final Map<String, String> gates = new LinkedHashMap<>();
        gates.put(MS_CONTAIN_MULTIPLY, "multiply-color");
        gates.put(MS_CONTAIN_SCREEN, "screen-color");
        gates.put(MS_CONTAIN_MORPH, "morph-target");
        gates.put(MS_CONTAIN_MORPH_ENH, "morph-target-enhancement");
        gates.put(MS_CONTAIN_ADVANCED_BLEND, "advanced-blend");
        gates.put(MS_CONTAIN_ART_PATH, "art-path");
        gates.put(MS_CONTAIN_ALIAS, "alias");
        gates.put(MS_CONTAIN_INVERT_CLIP, "invert-clipping");
        gates.put(MS_CONTAIN_QUAD, "quad-transform");
        gates.put(MS_CONTAIN_OFFSCREEN, "offscreen-rendering");
        gates.put(MS_CONTAIN_MOTION_SYNC, "motion-sync");
        gates.put(MS_CONTAIN_MOTION_SYNC_FIX, "motion-sync-correction");
        return java.util.Collections.unmodifiableMap(gates);
    }

    private static Map<String, String> withoutGates(
        final Map<String, String> gates,
        final Set<String> excluded
    ) {
        final Map<String, String> remaining = new LinkedHashMap<>(gates);
        excluded.forEach(remaining::remove);
        return java.util.Collections.unmodifiableMap(remaining);
    }

    private final VerifiedMemberResolver resolver;
    private final Map<String, String> admittedFeatureGates;

    public VerifiedProtectedExportHostOperations(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        final boolean cubism52 = resolver.isExactCubismVersion(
            ProtectedExportVerificationManifest.CUBISM_VERSION_5_2_03
        );
        this.admittedFeatureGates = cubism52 ? CUBISM_5_2_FEATURE_GATES : FEATURE_GATES;
        if (!resolver.authorizes(
            ProtectedExportVerificationManifest.ADAPTER_SLICE_ID,
            ProtectedExportVerificationManifest.CAPABILITY_IDS,
            cubism52 ? CUBISM_5_2_REQUIRED_ALIASES : REQUIRED_ALIASES
        )) {
            throw new IllegalArgumentException(
                "verified access plan does not authorize the protected-export slice"
            );
        }
    }

    // ------------------------------------------------------------------
    // Session / application
    // ------------------------------------------------------------------

    @Override
    public Object appController() {
        return resolver.invokeStatic(APP_INSTANCE);
    }

    @Override
    public Object currentDocument() {
        final Object controller = appController();
        return controller == null ? null : resolver.invoke(CURRENT_DOCUMENT, controller);
    }

    @Override
    public Object currentProject() {
        final Object controller = appController();
        return controller == null ? null : resolver.invoke(CURRENT_PROJECT, controller);
    }

    @Override
    public boolean projectContains(final Object document) {
        final Object project = currentProject();
        if (project == null || document == null
            || !resolver.isInstance(PROJECT_CLASS, project)) {
            return false;
        }
        final Object children = resolver.invoke(PROJECT_CHILDREN, project);
        if (!(children instanceof List<?> list)) {
            return false;
        }
        for (Object child : list) {
            if (child == document) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<?> projectDocuments() {
        final Object project = currentProject();
        if (project == null || !resolver.isInstance(PROJECT_CLASS, project)) {
            return List.of();
        }
        final Object children = resolver.invoke(PROJECT_CHILDREN, project);
        if (!(children instanceof List<?> list)) {
            return List.of();
        }
        final List<Object> documents = new ArrayList<>();
        for (Object child : list) {
            if (isModelingDocument(child)) {
                documents.add(child);
            }
        }
        return List.copyOf(documents);
    }

    @Override
    public Object mainFrame() {
        final Object controller = appController();
        if (controller == null) {
            return null;
        }
        final Object frameCtrl = resolver.invoke(MAIN_FRAME_CTRL, controller);
        if (frameCtrl == null || !resolver.isInstance(MAIN_FRAME_CTRL_CLASS, frameCtrl)) {
            return null;
        }
        return resolver.invoke(MAIN_FRAME, frameCtrl);
    }

    @Override
    public void openFile(final File file) {
        resolver.invoke(COMMAND_OPEN, Objects.requireNonNull(appController(), "appController"),
            Objects.requireNonNull(file, "file"), Boolean.FALSE);
    }

    @Override
    public void closeFileContent(final Object fileContent) {
        resolver.invoke(COMMAND_CLOSE, Objects.requireNonNull(appController(), "appController"),
            Objects.requireNonNull(fileContent, "fileContent"));
    }

    // ------------------------------------------------------------------
    // Document
    // ------------------------------------------------------------------

    @Override
    public boolean isModelingDocument(final Object document) {
        return resolver.isInstance(DOCUMENT_CLASS, document);
    }

    @Override
    public Object documentModelSource(final Object document) {
        return document == null ? null : resolver.invoke(DOC_MODEL_SOURCE, document);
    }

    @Override
    public Object documentFileContent(final Object document) {
        return document == null ? null : resolver.invoke(DOC_FILE_CONTENT, document);
    }

    @Override
    public Object documentSelector(final Object document) {
        return document == null ? null : resolver.invoke(DOC_SELECTOR, document);
    }

    @Override
    public Object documentCurrentEditMode(final Object document) {
        return document == null ? null : resolver.invoke(DOC_EDIT_MODE_CURRENT, document);
    }

    @Override
    public Object documentMainEditMode(final Object document) {
        return document == null ? null : resolver.invoke(DOC_EDIT_MODE_MAIN, document);
    }

    @Override
    public File documentFile(final Object document) {
        final Object file = document == null ? null : resolver.invoke(DOC_FILE, document);
        return file instanceof File path ? path : null;
    }

    @Override
    public Object documentUndoManager(final Object document) {
        return document == null ? null : resolver.invoke(DOC_UNDO, document);
    }

    @Override
    public void markDocumentSaved(final Object document) {
        resolver.invoke(DOC_MARK_SAVED, Objects.requireNonNull(document, "document"),
            Long.MAX_VALUE);
    }

    // ------------------------------------------------------------------
    // File content / undo signatures
    // ------------------------------------------------------------------

    @Override
    public File fileContentFile(final Object fileContent) {
        if (fileContent == null || !resolver.isInstance(FILE_CONTENT_CLASS, fileContent)) {
            return null;
        }
        final Object file = resolver.invoke(FILE_CONTENT_FILE, fileContent);
        return file instanceof File path ? path : null;
    }

    @Override
    public boolean fileContentModified(final Object fileContent) {
        if (fileContent == null || !resolver.isInstance(FILE_CONTENT_CLASS, fileContent)) {
            return false;
        }
        return Boolean.TRUE.equals(resolver.invoke(FILE_CONTENT_MODIFIED, fileContent));
    }

    @Override
    public int undoPosition(final Object undoManager) {
        return intMember(UNDO_POSITION, undoManager);
    }

    @Override
    public int undoEditCount(final Object undoManager) {
        return intMember(UNDO_EDIT_COUNT, undoManager);
    }

    @Override
    public boolean undoCanUndo(final Object undoManager) {
        return undoManager != null && resolver.isInstance(UNDO_CLASS, undoManager)
            && Boolean.TRUE.equals(resolver.invoke(UNDO_CAN_UNDO, undoManager));
    }

    // ------------------------------------------------------------------
    // Selector / edit mode
    // ------------------------------------------------------------------

    @Override
    public boolean isMainSelector(final Object selector) {
        return resolver.isInstance(SELECTOR_CLASS, selector);
    }

    @Override
    public boolean isMainEditMode(final Object editMode) {
        return resolver.isInstance(EDIT_MODE_CLASS, editMode);
    }

    @Override
    public void clearSelection(final Object selector) {
        resolver.invoke(SELECTOR_CLEAR, requireSelector(selector));
    }

    @Override
    public int selectedCount(final Object selector) {
        final Object count = resolver.invoke(SELECTOR_SELECTED_COUNT, requireSelector(selector));
        return count instanceof Number n ? n.intValue() : -1;
    }

    @Override
    public void selectSource(final Object selector, final Object source) {
        resolver.invoke(
            SELECTOR_ADD_SOURCE,
            requireSelector(selector),
            Objects.requireNonNull(source, "source"),
            -1
        );
    }

    @Override
    public List<?> selectedDeformers(final Object selector) {
        final Object selected = resolver.invoke(
            SELECTOR_SELECTED_DEFORMERS, requireSelector(selector));
        return selected instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    @Override
    public void applyDeformerToParameters(final Object mainEditMode) {
        if (!isMainEditMode(mainEditMode)) {
            throw new IllegalStateException("edit mode is not the modeling main edit mode");
        }
        resolver.invoke(EDIT_MODE_APPLY, mainEditMode);
    }

    // ------------------------------------------------------------------
    // Model source census
    // ------------------------------------------------------------------

    @Override
    public Object modelSourceDocument(final Object modelSource) {
        return modelSource == null ? null : resolver.invoke(MS_DOCUMENT, modelSource);
    }

    @Override
    public Object modelSourceCurrentInstance(final Object modelSource) {
        return modelSource == null ? null : resolver.invoke(MS_INSTANCE, modelSource);
    }

    @Override
    public List<?> allDeformers(final Object modelSource) {
        return listOf(resolver.invoke(MS_DEFORMERS, requireModelSource(modelSource)));
    }

    @Override
    public List<?> allObjects(final Object modelSource) {
        return listOf(resolver.invoke(MS_OBJECTS, requireModelSource(modelSource)));
    }

    @Override
    public List<?> allArtMeshes(final Object modelSource) {
        return listOf(resolver.invoke(MS_ART_MESHES, requireModelSource(modelSource)));
    }

    @Override
    public List<?> allParts(final Object modelSource) {
        return listOf(resolver.invoke(MS_PARTS, requireModelSource(modelSource)));
    }

    @Override
    public Object rootPart(final Object modelSource) {
        return resolver.invoke(MS_ROOT_PART, requireModelSource(modelSource));
    }

    @Override
    public List<?> allParameters(final Object modelSource) {
        return listOf(resolver.invoke(MS_PARAMETERS, requireModelSource(modelSource)));
    }

    @Override
    public List<?> allPhysicsSettings(final Object modelSource) {
        return listOf(resolver.invoke(MS_PHYSICS, requireModelSource(modelSource)));
    }

    @Override
    public List<?> allMotionSyncSettings(final Object modelSource) {
        return listOf(resolver.invoke(MS_MOTION_SYNC, requireModelSource(modelSource)));
    }

    @Override
    public String modelSourceGuid(final Object modelSource) {
        final Object guid = resolver.invoke(MS_GUID, requireModelSource(modelSource));
        return guid == null ? null : (String) resolver.invoke(GUID_UUID, guid);
    }

    @Override
    public boolean serializeModelSource(final Object modelSource, final File target) {
        Objects.requireNonNull(target, "target");
        // saveModel(File, Z) is the model source's own serializer — the same
        // entry CModelingDocument.saveDocument delegates to, minus the
        // document-state updates (saved timestamp, project wiring) that would
        // disturb the live session. The boolean is the developer-mode
        // save-obfuscation flag; staging always writes the un-obfuscated form
        // because the copy's census must match the live census before the
        // planned obfuscation pass rewrites it.
        return Boolean.TRUE.equals(resolver.invoke(
            MS_SAVE_MODEL, requireModelSource(modelSource), target, Boolean.FALSE));
    }

    @Override
    public List<?> liveParameters(final Object modelSource) {
        final Object instance = modelSourceCurrentInstance(modelSource);
        if (instance == null || !resolver.isInstance(MODEL_CLASS, instance)) {
            return List.of();
        }
        final Object parameterSet = resolver.invoke(MODEL_PARAMETER_SET, instance);
        if (parameterSet == null || !resolver.isInstance(PARAMETER_SET_CLASS, parameterSet)) {
            return List.of();
        }
        return listOf(resolver.invoke(PARAMETER_SET_PARAMETERS, parameterSet));
    }

    @Override
    public String parameterInstanceId(final Object parameter) {
        if (parameter == null || !resolver.isInstance(PARAMETER_INSTANCE_CLASS, parameter)) {
            return null;
        }
        final Object id = resolver.invoke(PARAMETER_INSTANCE_ID, parameter);
        return id == null ? null : (String) resolver.invoke(ID_STRING, id);
    }

    @Override
    public float parameterInstanceValue(final Object parameter) {
        if (parameter == null || !resolver.isInstance(PARAMETER_INSTANCE_CLASS, parameter)) {
            return Float.NaN;
        }
        final Object value = resolver.invoke(PARAMETER_INSTANCE_VALUE, parameter);
        return value instanceof Number n ? n.floatValue() : Float.NaN;
    }

    /**
     * {@code CParameter.setValue} on a live parameter instance — behavior-sampling
     * writes only ever land on the disposable copy's model instance.
     */
    @Override
    public void setParameterInstanceValue(
        final Object parameterInstance,
        final float value
    ) {
        if (!resolver.isInstance(PARAMETER_INSTANCE_CLASS, parameterInstance)) {
            throw new IllegalStateException("object is not a parameter instance");
        }
        resolver.invoke(PARAMETER_INSTANCE_SET_VALUE, parameterInstance, value);
    }

    /**
     * {@code CModel.reinitModelInstance_exe} — the self-contained "update model"
     * evaluation: rebuilds instance objects and refreshes calculated forms with
     * no view context required.
     */
    @Override
    public void evaluateModelInstance(final Object modelInstance) {
        if (!resolver.isInstance(MODEL_CLASS, modelInstance)) {
            throw new IllegalStateException("object is not a model instance");
        }
        resolver.invoke(MODEL_REINIT_EXE, modelInstance);
    }

    @Override
    public List<?> modelInstanceArtMeshes(final Object modelInstance) {
        if (!resolver.isInstance(MODEL_CLASS, modelInstance)) {
            return List.of();
        }
        return listOf(resolver.invoke(MODEL_ART_MESHES, modelInstance));
    }

    @Override
    public Object artMeshInstanceSource(final Object artMeshInstance) {
        if (!resolver.isInstance(ART_MESH_INSTANCE_CLASS, artMeshInstance)) {
            return null;
        }
        return resolver.invoke(ART_MESH_INSTANCE_SOURCE, artMeshInstance);
    }

    @Override
    public float[] evaluatedArtMeshPositions(final Object artMeshInstance) {
        if (!resolver.isInstance(ART_MESH_INSTANCE_CLASS, artMeshInstance)) {
            return null;
        }
        final Object form = resolver.invoke(ART_MESH_INSTANCE_FORM, artMeshInstance);
        if (!resolver.isInstance(ART_MESH_FORM_CLASS, form)) {
            return null;
        }
        final Object positions = resolver.invoke(ART_MESH_FORM_POSITIONS, form);
        return positions instanceof float[] array ? array.clone() : null;
    }

    /**
     * {@code ACDeformerSource.getDeformerChildren} — every controllable source
     * whose target deformer is this one. Drawable children carry no keyform
     * bindings of their own here; the caller filters by class.
     */
    @Override
    public List<?> deformerChildren(final Object deformerSource) {
        if (!resolver.isInstance(DEFORMER_CLASS, deformerSource)) {
            return List.of();
        }
        return listOf(resolver.invoke(DEFORMER_CHILDREN, deformerSource));
    }

    /**
     * Rewrites a parameter-controllable source's local name. Used only by the protected
     * ArtMesh obfuscation pass on the disposable copy (never on the authoring document).
     */
    @Override
    public void setObjectLocalName(final Object source, final String name) {
        resolver.invoke(SOURCE_SET_LOCAL_NAME,
            Objects.requireNonNull(source, "source"),
            Objects.requireNonNull(name, "name"));
    }

    /**
     * Rewrites a drawable source's ID on the disposable copy. Rejecting callers that are
     * not drawable sources keeps the M5 pass scoped to ArtMesh identities.
     */
    @Override
    public void setDrawableId(final Object drawableSource, final String idString) {
        if (!resolver.isInstance(DRAWABLE_CLASS, drawableSource)) {
            throw new IllegalStateException("object is not a drawable source");
        }
        final Object newId = resolver.construct(DRAWABLE_ID_CREATE, idString);
        resolver.invoke(DRAWABLE_ID_SET, drawableSource, newId);
    }

    /** Drawable-specific ID string of an ArtMesh/drawable source. */
    @Override
    public String drawableIdString(final Object drawableSource) {
        if (!resolver.isInstance(DRAWABLE_CLASS, drawableSource)) {
            return null;
        }
        final Object id = resolver.invoke(DRAWABLE_ID_GET, drawableSource);
        return id == null ? null : (String) resolver.invoke(ID_STRING, id);
    }

    /**
     * Parameter sources are not parameter-controllable: their ID is a
     * {@code CParameterId} reached through the source's own accessor.
     */
    @Override
    public String parameterSourceIdString(final Object parameterSource) {
        if (!resolver.isInstance(PARAMETER_CLASS, parameterSource)) {
            return null;
        }
        final Object id = resolver.invoke(PARAMETER_SOURCE_ID, parameterSource);
        return id == null ? null : (String) resolver.invoke(ID_STRING, id);
    }

    @Override
    public String parameterSourceName(final Object parameterSource) {
        if (!resolver.isInstance(PARAMETER_CLASS, parameterSource)) {
            return null;
        }
        final Object name = resolver.invoke(PARAMETER_SOURCE_NAME, parameterSource);
        return name instanceof String text ? text : null;
    }

    @Override
    public Float parameterSourceMinValue(final Object parameterSource) {
        return floatParameterMember(PARAMETER_SOURCE_MIN, parameterSource);
    }

    @Override
    public Float parameterSourceMaxValue(final Object parameterSource) {
        return floatParameterMember(PARAMETER_SOURCE_MAX, parameterSource);
    }

    @Override
    public Float parameterSourceDefaultValue(final Object parameterSource) {
        return floatParameterMember(PARAMETER_SOURCE_DEFAULT, parameterSource);
    }

    @Override
    public Boolean parameterSourceRepeat(final Object parameterSource) {
        if (!resolver.isInstance(PARAMETER_CLASS, parameterSource)) {
            return null;
        }
        final Object repeat = resolver.invoke(PARAMETER_SOURCE_REPEAT, parameterSource);
        return repeat instanceof Boolean flag ? flag : null;
    }

    /**
     * All keyform bindings of a controllable source across both its grids — the same
     * grid pair the extended-interpolation gate reads, deduplicated by identity so a
     * shared grid object is only counted once.
     */
    @Override
    public List<?> keyformBindings(final Object controllableSource) {
        final java.util.Set<Object> grids = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>());
        final List<Object> bindings = new ArrayList<>();
        for (String gridAlias : new String[] {SOURCE_GRID, SOURCE_EXT_GRID}) {
            final Object grid = controllableSource == null
                ? null : resolver.invoke(gridAlias, controllableSource);
            if (grid == null || !grids.add(grid)
                || !resolver.isInstance(GRID_CLASS, grid)) {
                continue;
            }
            bindings.addAll(listOf(resolver.invoke(GRID_BINDINGS, grid)));
        }
        return List.copyOf(bindings);
    }

    @Override
    public String keyformBindingParameterId(final Object binding) {
        if (!resolver.isInstance(BINDING_CLASS, binding)) {
            return null;
        }
        final Object id = resolver.invoke(BINDING_PARAMETER_ID, binding);
        return id == null ? null : (String) resolver.invoke(ID_STRING, id);
    }

    @Override
    public List<Float> keyformBindingKeys(final Object binding) {
        if (!resolver.isInstance(BINDING_CLASS, binding)) {
            return List.of();
        }
        final Object keys = resolver.invoke(BINDING_KEYS, binding);
        if (!(keys instanceof List<?> list)) {
            return List.of();
        }
        final List<Float> positions = new ArrayList<>();
        for (Object key : list) {
            if (key instanceof Number number) {
                positions.add(number.floatValue());
            }
        }
        return List.copyOf(positions);
    }

    @Override
    public boolean isPartSource(final Object object) {
        return resolver.isInstance(PART_CLASS, object);
    }

    @Override
    public List<String> partChildGuids(final Object partSource) {
        if (!resolver.isInstance(PART_CLASS, partSource)) {
            return List.of();
        }
        final Object guids = resolver.invoke(PART_CHILD_GUIDS, partSource);
        if (!(guids instanceof List<?> list)) {
            return List.of();
        }
        final List<String> children = new ArrayList<>();
        for (Object guid : list) {
            final Object uuid = guid == null ? null : resolver.invoke(GUID_UUID, guid);
            if (uuid instanceof String text) {
                children.add(text);
            }
        }
        return List.copyOf(children);
    }

    /** Float member of a parameter source, or {@code null} when unreadable. */
    private Float floatParameterMember(final String alias, final Object parameterSource) {
        if (!resolver.isInstance(PARAMETER_CLASS, parameterSource)) {
            return null;
        }
        final Object value = resolver.invoke(alias, parameterSource);
        return value instanceof Number number ? number.floatValue() : null;
    }

    // ------------------------------------------------------------------
    // Object identity
    // ------------------------------------------------------------------

    @Override
    public String objectGuid(final Object source) {
        final Object guid = source == null ? null : resolver.invoke(SOURCE_GUID, source);
        return guid == null ? null : (String) resolver.invoke(GUID_UUID, guid);
    }

    @Override
    public String objectIdString(final Object source) {
        final Object id = source == null ? null : resolver.invoke(SOURCE_ID, source);
        return id == null ? null : (String) resolver.invoke(ID_STRING, id);
    }

    @Override
    public String objectLocalName(final Object source) {
        final Object name = source == null ? null : resolver.invoke(SOURCE_LOCAL_NAME, source);
        return name instanceof String text ? text : null;
    }

    @Override
    public boolean isDeformerSource(final Object object) {
        return resolver.isInstance(DEFORMER_CLASS, object);
    }

    @Override
    public boolean isWarpDeformer(final Object object) {
        return resolver.isInstance(WARP_CLASS, object);
    }

    @Override
    public boolean isRotationDeformer(final Object object) {
        return resolver.isInstance(ROTATION_CLASS, object);
    }

    @Override
    public boolean isArtMeshSource(final Object object) {
        return resolver.isInstance(ART_MESH_CLASS, object);
    }

    @Override
    public String deformerGuid(final Object deformerSource) {
        final Object guid = deformerSource == null
            ? null : resolver.invoke(DEFORMER_GUID, deformerSource);
        return guid == null ? null : (String) resolver.invoke(GUID_UUID, guid);
    }

    @Override
    public String deformerTargetGuid(final Object deformerSource) {
        final Object guid = deformerSource == null
            ? null : resolver.invoke(DEFORMER_TARGET, deformerSource);
        return guid == null ? null : (String) resolver.invoke(GUID_UUID, guid);
    }

    // ------------------------------------------------------------------
    // Extended-interpolation eligibility gate
    // ------------------------------------------------------------------

    @Override
    public boolean usesExtendedInterpolation(final Object modelSource) {
        // Both grid accessors can return the same instance; dedupe by identity so a
        // binding is only judged once. A binding with a non-LINEAR extended type or
        // the illegal flag counts as extended-interpolation usage.
        final java.util.Set<Object> seen = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>());
        for (Object object : allObjects(modelSource)) {
            if (object == null) {
                continue;
            }
            for (String gridAlias : new String[] {SOURCE_GRID, SOURCE_EXT_GRID}) {
                final Object grid = resolver.invoke(gridAlias, object);
                if (grid == null || !seen.add(grid)
                    || !resolver.isInstance(GRID_CLASS, grid)) {
                    continue;
                }
                for (Object binding : listOf(resolver.invoke(GRID_BINDINGS, grid))) {
                    if (binding == null || !resolver.isInstance(BINDING_CLASS, binding)) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(
                        resolver.invoke(BINDING_ILLEGAL, binding))) {
                        return true;
                    }
                    final Object type = resolver.invoke(BINDING_EXT_TYPE, binding);
                    if (type instanceof Enum<?> extended && !"LINEAR".equals(extended.name())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The admitted model source's own {@code contain*} gates — the host's semantic answer
     * to feature content the object census cannot see. Each predicate maps to a stable
     * family token; any {@code true} is a hard admission rejection. The gate roster is the
     * exact one the admitted record verified: on 5.2.03 the three 5.3-only predicates are
     * absent because the gated features cannot exist in a 5.2 document.
     */
    @Override
    public List<String> unsupportedModelFeatures(final Object modelSource) {
        if (modelSource == null) {
            return List.of();
        }
        final List<String> detected = new ArrayList<>();
        for (Map.Entry<String, String> gate : admittedFeatureGates.entrySet()) {
            if (Boolean.TRUE.equals(resolver.invoke(gate.getKey(), modelSource))) {
                detected.add(gate.getValue());
            }
        }
        return List.copyOf(detected);
    }

    /**
     * Extension classes that the editor installs on ordinary sources for every
     * model: texture inputs carry the atlas mapping, editable-mesh /
     * mesh-generator / warp-bezier / original-shape extensions carry standard
     * edit-time data. They are not feature carriers, so their presence alone
     * must not reject a model. Any other extension class (glue deform-path
     * skinning, art path, auto-yure, controller, rotate-3d, subdivision,
     * topology observer, extended interpolation, or an unknown future type)
     * is a feature carrier and rejects the source.
     */
    private static final Set<String> STANDARD_EXTENSION_CLASSES = Set.of(
        "com.live2d.cubism.doc.model.extension.textureInput.CTextureInputExtension",
        "com.live2d.cubism.doc.model.extension.editableMesh.CEditableMeshExtension",
        "com.live2d.cubism.doc.model.extension.meshGenerator.CMeshGeneratorExtension",
        "com.live2d.cubism.doc.model.extension.warpBezier.CWarpDeformerBezierExtension",
        "com.live2d.cubism.doc.model.extension.deformerOriginalShape.CDeformerOriginalShapeExtension");

    /**
     * Structures embedded inside a controllable source that never appear in the
     * object census: a keyform morph-target set with actual targets, an
     * extended morph-target set, or a non-standard extension object attached to
     * the source. Extension classes are named in the token so the rejection is
     * diagnosable.
     */
    @Override
    public List<String> embeddedUnsupportedFamilies(final Object controllableSource) {
        if (controllableSource == null) {
            return List.of();
        }
        final List<String> detected = new ArrayList<>();
        final Object morphSet = resolver.invoke(SOURCE_MORPH_SET, controllableSource);
        if (morphSet != null && resolver.isInstance(MORPH_SET_CLASS, morphSet)
            && !listOf(resolver.invoke(MORPH_SET_TARGETS, morphSet)).isEmpty()) {
            detected.add("keyform-morph-target-set");
        }
        if (resolver.invoke(SOURCE_EXT_MORPH_SET, controllableSource) != null) {
            detected.add("extended-morph-target-set");
        }
        for (Object extension : listOf(
            resolver.invoke(SOURCE_EXTENSIONS, controllableSource))) {
            if (extension != null
                && !STANDARD_EXTENSION_CLASSES.contains(extension.getClass().getName())) {
                detected.add("extension:" + extension.getClass().getName());
            }
        }
        return List.copyOf(detected);
    }

    /**
     * Family tokens for census objects outside the supported whitelist, keyed by
     * the reviewed model package layout of the admitted builds. Deform-path
     * skinning classes live under the glue package, so the more specific prefix
     * must be tested first. Classes absent from an admitted build (aliases do
     * not exist on 5.2.03) simply never match there.
     */
    private static final List<Map.Entry<String, String>> UNSUPPORTED_FAMILY_PREFIXES =
        List.of(
            Map.entry(
                "com.live2d.cubism.doc.model.affecter.glue.deformPathSkinning.",
                "deform-path"),
            Map.entry("com.live2d.cubism.doc.model.affecter.glue.", "glue"),
            Map.entry("com.live2d.cubism.doc.model.drawable.artPath.", "art-path"),
            Map.entry("com.live2d.cubism.doc.model.alias.", "alias"),
            Map.entry("com.live2d.cubism.doc.model.motionSync.", "motion-sync"),
            Map.entry("com.live2d.cubism.doc.gameData.physics.", "physics")
        );

    /**
     * Classifies a rejected census object by its class-hierarchy package — a
     * pure {@code getClass()} read that never resolves host members. Unknown
     * families degrade to a bounded simple-name clue, never a stack of
     * internal detail.
     */
    @Override
    public String unsupportedObjectFamily(final Object object) {
        if (object == null) {
            return "unknown";
        }
        for (Class<?> type = object.getClass();
             type != null && type != Object.class;
             type = type.getSuperclass()) {
            final String name = type.getName();
            for (Map.Entry<String, String> family : UNSUPPORTED_FAMILY_PREFIXES) {
                if (name.startsWith(family.getKey())) {
                    return family.getValue();
                }
            }
        }
        final String clue = object.getClass().getSimpleName()
            .replaceAll("[^A-Za-z0-9_$]", "-");
        return "unknown:" + (clue.isBlank()
            ? "object" : clue.substring(0, Math.min(clue.length(), 48)));
    }

    // ------------------------------------------------------------------
    // Export dialog identity + native re-drive
    // ------------------------------------------------------------------

    @Override
    public boolean isExportDialog(final Object owner) {
        return resolver.isInstance(DIALOG_CLASS, owner);
    }

    @Override
    public Object dialogModelSource(final Object dialogOwner) {
        if (!isExportDialog(dialogOwner)) {
            return null;
        }
        return resolver.readField(DIALOG_MODEL_SOURCE, dialogOwner);
    }

    @Override
    public Object exportDriver() {
        return resolver.readStaticField(DRIVER_INSTANCE);
    }

    @Override
    public void invokeNativeExport(
        final Object driver,
        final Object modelSource,
        final Object frame,
        final Object completionCallback
    ) {
        if (driver == null || !resolver.isInstance(DRIVER_CLASS, driver)) {
            throw new IllegalStateException("export driver is not the verified driver type");
        }
        resolver.invoke(DRIVER_EXPORT, driver,
            Objects.requireNonNull(modelSource, "modelSource"), frame,
            Objects.requireNonNull(completionCallback, "completionCallback"));
    }

    @Override
    public Object newExportCompletionProxy(final BiConsumer<File, List<String>> callback) {
        Objects.requireNonNull(callback, "callback");
        // Function2 is stable Kotlin stdlib surface, not an obfuscated host member; the
        // verified export descriptor already pins it as the callback parameter type.
        final ClassLoader hostLoader = resolver.hostClassLoader();
        try {
            final Class<?> function2 = Class.forName(
                "kotlin.jvm.functions.Function2", false, hostLoader);
            if (!function2.isInterface()) {
                throw new IllegalStateException("host Function2 is not an interface");
            }
            final InvocationHandler handler = (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "ProtectedExportCompletion";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                        default -> null;
                    };
                }
                if (!Modifier.isAbstract(method.getModifiers())) {
                    return null;
                }
                final File file = arguments != null && arguments[0] instanceof File f ? f : null;
                final List<String> outputs = new ArrayList<>();
                if (arguments != null && arguments[1] instanceof List<?> list) {
                    for (Object entry : list) {
                        if (entry instanceof String path) {
                            outputs.add(path);
                        }
                    }
                }
                callback.accept(file, List.copyOf(outputs));
                return null;
            };
            return Proxy.newProxyInstance(hostLoader, new Class<?>[] {function2}, handler);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("host Function2 interface is unavailable", failure);
        }
    }

    // ------------------------------------------------------------------
    // Native file-cache force release
    // ------------------------------------------------------------------

    @Override
    public boolean releaseFileHandleFor(final File file) {
        Objects.requireNonNull(file, "file");
        final Object cache = resolver.readStaticField(FILE_CACHE_INSTANCE);
        if (cache == null || !resolver.isInstance(FILE_CACHE_CLASS, cache)) {
            return false;
        }
        final Object handles = resolver.invoke(FILE_CACHE_HANDLES, cache);
        if (!(handles instanceof List<?> list)) {
            return false;
        }
        for (Object handle : new ArrayList<>(list)) {
            if (handle == null || !resolver.isInstance(FILE_HANDLE_CLASS, handle)) {
                continue;
            }
            final Object handleFile = resolver.invoke(FILE_HANDLE_FILE, handle);
            if (!(handleFile instanceof File path) || !path.equals(file)) {
                continue;
            }
            resolver.invoke(FILE_HANDLE_UNLOAD, handle);
            resolver.invoke(FILE_HANDLE_RELEASE, handle, Boolean.TRUE);
            resolver.invoke(FILE_CACHE_REMOVE, cache, handle);
        }
        final Object remaining = resolver.invoke(FILE_CACHE_BY_FILE, cache, file);
        return remaining == null;
    }

    @Override
    public List<String> fileHandleDiagnostics(final File file) {
        Objects.requireNonNull(file, "file");
        final Object cache = resolver.readStaticField(FILE_CACHE_INSTANCE);
        final Object handles = cache == null ? null : resolver.invoke(FILE_CACHE_HANDLES, cache);
        if (!(handles instanceof List<?> list)) {
            return List.of();
        }
        final List<String> diagnostics = new ArrayList<>();
        for (Object handle : list) {
            if (handle == null || !resolver.isInstance(FILE_HANDLE_CLASS, handle)) {
                continue;
            }
            final Object handleFile = resolver.invoke(FILE_HANDLE_FILE, handle);
            if (!(handleFile instanceof File path) || !path.equals(file)) {
                continue;
            }
            final Object loader = resolver.invoke(FILE_HANDLE_LOADER, handle);
            final Object listeners = resolver.invoke(FILE_HANDLE_LISTENERS, handle);
            diagnostics.add(
                handle.getClass().getName()
                    + "|loader=" + (loader == null ? "null" : loader.getClass().getName())
                    + "|listeners=" + (listeners instanceof List<?> l ? l.size() : "?"));
        }
        return List.copyOf(diagnostics);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Object requireSelector(final Object selector) {
        if (!isMainSelector(selector)) {
            throw new IllegalStateException("selector is not the modeling main selector");
        }
        return selector;
    }

    private Object requireModelSource(final Object modelSource) {
        if (!resolver.isInstance(MODEL_SOURCE_CLASS, modelSource)) {
            throw new IllegalStateException("object is not a verified model source");
        }
        return modelSource;
    }

    private int intMember(final String alias, final Object target) {
        if (target == null) {
            return -1;
        }
        final Object value = resolver.invoke(alias, target);
        return value instanceof Number n ? n.intValue() : -1;
    }

    private static List<?> listOf(final Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : List.of();
    }
}
