package dev.turboism.exportsettings;

import static dev.turboism.exportsettings.ProtectedExportHostVocabulary.*;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.ProtectedExportVerificationManifest;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.io.File;
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
 *
 * <p>This facade owns the session/document/selector surface, the operation aliases and the
 * version-capability gates. The three cooperating package-private halves carry the rest:
 * {@link ProtectedExportHostModelReadWriteOps} for the model-object graph,
 * {@link ProtectedExportHostSettingsOps} for physics and motion-sync settings, and
 * {@link ProtectedExportHostExportSurfaceOps} for native export and file-cache release. The
 * alias literals themselves live in {@link ProtectedExportHostVocabulary}.</p>
 */
public final class VerifiedProtectedExportHostOperations implements ProtectedExportHostOperations {

    private static final Set<String> METHOD_ALIASES_USED = Set.of(
            APP_INSTANCE,
            CURRENT_DOCUMENT,
            CURRENT_PROJECT,
            COMMAND_OPEN,
            COMMAND_CLOSE,
            MAIN_FRAME_CTRL,
            MAIN_FRAME,
            PROJECT_CHILDREN,
            DOC_MODEL_SOURCE,
            DOC_FILE_CONTENT,
            DOC_SELECTOR,
            DOC_EDIT_MODE_CURRENT,
            DOC_EDIT_MODE_MAIN,
            DOC_MARK_SAVED,
            DOC_FILE,
            DOC_UNDO,
            FILE_CONTENT_FILE,
            FILE_CONTENT_MODIFIED,
            UNDO_POSITION,
            UNDO_EDIT_COUNT,
            UNDO_CAN_UNDO,
            SELECTOR_CLEAR,
            SELECTOR_SELECTED,
            SELECTOR_SELECTED_COUNT,
            SELECTOR_ADD_SOURCE,
            SELECTOR_SELECTED_DEFORMERS,
            EDIT_MODE_APPLY,
            MS_DOCUMENT,
            MS_INSTANCE,
            MS_DEFORMERS,
            MS_OBJECTS,
            MS_ART_MESHES,
            MS_PARTS,
            MS_ROOT_PART,
            MS_PARAMETERS,
            MS_PHYSICS,
            MS_MOTION_SYNC,
            MS_GUID,
            MS_SAVE_MODEL,
            MS_CONTAIN_MULTIPLY,
            MS_CONTAIN_SCREEN,
            MS_CONTAIN_MORPH,
            MS_CONTAIN_MORPH_ENH,
            MS_CONTAIN_ADVANCED_BLEND,
            MS_CONTAIN_ART_PATH,
            MS_CONTAIN_ALIAS,
            MS_CONTAIN_INVERT_CLIP,
            MS_CONTAIN_QUAD,
            MS_CONTAIN_OFFSCREEN,
            MS_CONTAIN_MOTION_SYNC,
            MS_CONTAIN_MOTION_SYNC_FIX,
            SOURCE_MORPH_SET,
            SOURCE_EXT_MORPH_SET,
            SOURCE_EXTENSIONS,
            MORPH_SET_TARGETS,
            MODEL_PARAMETER_SET,
            SOURCE_GUID,
            SOURCE_ID,
            SOURCE_LOCAL_NAME,
            SOURCE_SET_LOCAL_NAME,
            SOURCE_GRID,
            SOURCE_EXT_GRID,
            GRID_BINDINGS,
            BINDING_EXT_TYPE,
            BINDING_ILLEGAL,
            BINDING_PARAMETER_ID,
            BINDING_KEYS,
            PART_CHILD_GUIDS,
            GLUE_TARGET_A,
            GLUE_TARGET_B,
            SOURCE_TARGET_DEFORMER,
            DRAWABLE_CLIP_GUIDS,
            DRAWABLE_INVERT_CLIP,
            ART_PATH_BRUSH,
            ALIAS_REFERENCE,
            ALIAS_CLIP_GUIDS,
            ALIAS_INVERT_CLIP,
            ALIAS_USE_OFFSCREEN,
            ALIAS_COLOR_COMPOSITION,
            ALIAS_ALPHA_COMPOSITION,
            ALIAS_CIRCULATED,
            PART_CLIP_GUIDS,
            PART_INVERT_CLIP,
            PART_USE_OFFSCREEN,
            PART_COLOR_COMPOSITION,
            PART_ALPHA_COMPOSITION,
            PHYSICS_SETTINGS_GUID,
            PHYSICS_SETTINGS_ID,
            PHYSICS_SETTINGS_NAME,
            PHYSICS_SETTINGS_SET_NAME,
            PHYSICS_SETTINGS_SET_ID,
            PHYSICS_SETTING_ID_CREATE,
            PHYSICS_SETTINGS_ENABLE,
            PHYSICS_SETTINGS_INPUTS,
            PHYSICS_SETTINGS_OUTPUTS,
            PHYSICS_SETTINGS_VERTICES,
            PHYSICS_SETTINGS_TOTAL_ANGLE,
            PHYSICS_SETTINGS_NORM_POS_MIN,
            PHYSICS_SETTINGS_NORM_POS_DEFAULT,
            PHYSICS_SETTINGS_NORM_POS_MAX,
            PHYSICS_SETTINGS_NORM_ANGLE_MIN,
            PHYSICS_SETTINGS_NORM_ANGLE_DEFAULT,
            PHYSICS_SETTINGS_NORM_ANGLE_MAX,
            PHYSICS_INPUT_SOURCE,
            PHYSICS_INPUT_WEIGHT,
            PHYSICS_INPUT_TYPE,
            PHYSICS_INPUT_REVERSE,
            PHYSICS_INPUT_ANGLE_SCALE,
            PHYSICS_INPUT_TRANSLATION_SCALE,
            PHYSICS_OUTPUT_DESTINATION,
            PHYSICS_OUTPUT_VERTEX_INDEX,
            PHYSICS_OUTPUT_WEIGHT,
            PHYSICS_OUTPUT_TYPE,
            PHYSICS_OUTPUT_REVERSE,
            PHYSICS_OUTPUT_ANGLE_SCALE,
            PHYSICS_OUTPUT_TRANSLATION_SCALE,
            PHYSICS_OUTPUT_BELOW_MINIMUM,
            PHYSICS_OUTPUT_EXCEEDED_MAXIMUM,
            PHYSICS_VERTEX_POSITION,
            PHYSICS_VERTEX_MOBILITY,
            PHYSICS_VERTEX_DELAY,
            PHYSICS_VERTEX_ACCELERATION,
            PHYSICS_VERTEX_RADIUS,
            VECTOR2_X,
            VECTOR2_Y,
            PARAMETER_SOURCE_GUID,
            MS_PHYSICS_SETTINGS_SET,
            PHYSICS_SET_GRAVITY,
            PHYSICS_SET_WIND,
            PHYSICS_SET_FPS,
            PHYSICS_SET_SELECTED,
            MOTION_SYNC_GUID,
            MOTION_SYNC_ID,
            MOTION_SYNC_ID_FIELD,
            MOTION_SYNC_NAME,
            MOTION_SYNC_SET_NAME,
            MOTION_SYNC_ID_CREATE,
            MOTION_SYNC_MAPPING,
            MOTION_SYNC_POSTPROC,
            MOTION_SYNC_VERSION,
            MOTION_SYNC_MAPPING_CHECKSUM,
            MOTION_SYNC_POSTPROC_CHECKSUM,
            DEFORMER_GUID,
            DEFORMER_TARGET,
            DEFORMER_CHILDREN,
            DRAWABLE_ID_GET,
            DRAWABLE_ID_SET,
            DRAWABLE_ID_CREATE,
            GUID_UUID,
            ID_STRING,
            PARAMETER_SET_PARAMETERS,
            PARAMETER_INSTANCE_VALUE,
            PARAMETER_INSTANCE_ID,
            PARAMETER_INSTANCE_SET_VALUE,
            MODEL_REINIT_EXE,
            MODEL_ART_MESHES,
            ART_MESH_INSTANCE_SOURCE,
            ART_MESH_INSTANCE_FORM,
            ART_MESH_FORM_POSITIONS,
            PARAMETER_SOURCE_ID,
            PARAMETER_SOURCE_NAME,
            PARAMETER_SOURCE_MIN,
            PARAMETER_SOURCE_MAX,
            PARAMETER_SOURCE_DEFAULT,
            PARAMETER_SOURCE_REPEAT,
            DIALOG_MODEL_SOURCE,
            DRIVER_INSTANCE,
            DRIVER_EXPORT,
            FILE_CACHE_INSTANCE,
            FILE_CACHE_HANDLES,
            FILE_CACHE_BY_FILE,
            FILE_CACHE_REMOVE,
            FILE_HANDLE_FILE,
            FILE_HANDLE_LOADER,
            FILE_HANDLE_LISTENERS,
            FILE_HANDLE_UNLOAD,
            FILE_HANDLE_RELEASE);
    private static final Set<String> CLASS_ALIASES_REQUIRED = Set.of(
            APP_CONTROLLER_CLASS,
            PROJECT_CLASS,
            MAIN_FRAME_CTRL_CLASS,
            DOCUMENT_CLASS,
            FILE_CONTENT_CLASS,
            UNDO_CLASS,
            SELECTOR_INTERFACE,
            SELECTOR_CLASS,
            EDIT_MODE_BASE_CLASS,
            EDIT_MODE_CLASS,
            MODEL_SOURCE_CLASS,
            MODEL_CLASS,
            DEFORMER_CLASS,
            WARP_CLASS,
            ROTATION_CLASS,
            ART_MESH_CLASS,
            PART_CLASS,
            PARAMETER_CLASS,
            DRAWABLE_CLASS,
            GLUE_CLASS,
            CONTROLLABLE_CLASS,
            ART_PATH_CLASS,
            ALIAS_CLASS,
            PHYSICS_SETTINGS_CLASS,
            PHYSICS_SETTING_ID_CLASS,
            MOTION_SYNC_CLASS,
            MOTION_SYNC_ID_CLASS,
            DRAWABLE_ID_CLASS,
            PARAMETER_ID_CLASS,
            GUID_CLASS,
            ID_CLASS,
            PARAMETER_SET_CLASS,
            PARAMETER_INSTANCE_CLASS,
            ART_MESH_INSTANCE_CLASS,
            ART_MESH_FORM_CLASS,
            GRID_CLASS,
            BINDING_CLASS,
            EXT_TYPE_CLASS,
            MORPH_SET_CLASS,
            DIALOG_CLASS,
            DRIVER_CLASS,
            FILE_CACHE_CLASS,
            FILE_HANDLE_CLASS);

    /** Aliases independently required by this implementation, not copied from its trust manifest. */
    public static final Set<String> REQUIRED_ALIASES = requiredAliases();

    /**
     * Selectors that only exist on Cubism 5.3.x builds: {@code contain*} gates
     * whose gated features did not ship in 5.2.03, the alias family
     * ({@code CAliasSource} is absent from the 5.2.03 jar), and the part
     * detail members (clip/invert/offscreen/composition) that 5.2.03
     * {@code CPartSource} does not declare. The reviewed 5.2.03 record omits
     * them; they remain mandatory on every 5.3.x admission.
     */
    private static final Set<String> CUBISM_5_3_ONLY_ALIASES = Set.of(
            MS_CONTAIN_ADVANCED_BLEND,
            MS_CONTAIN_ALIAS,
            MS_CONTAIN_OFFSCREEN,
            ALIAS_CLASS,
            ALIAS_REFERENCE,
            ALIAS_CLIP_GUIDS,
            ALIAS_INVERT_CLIP,
            ALIAS_USE_OFFSCREEN,
            ALIAS_COLOR_COMPOSITION,
            ALIAS_ALPHA_COMPOSITION,
            ALIAS_CIRCULATED,
            PART_CLIP_GUIDS,
            PART_INVERT_CLIP,
            PART_USE_OFFSCREEN,
            PART_COLOR_COMPOSITION,
            PART_ALPHA_COMPOSITION);

    private static final Set<String> CUBISM_5_2_METHOD_ALIASES_USED =
            without(METHOD_ALIASES_USED, CUBISM_5_3_ONLY_ALIASES);

    /** Exact alias roster this implementation requires on the reviewed 5.2.03 build. */
    public static final Set<String> CUBISM_5_2_REQUIRED_ALIASES = without(REQUIRED_ALIASES, CUBISM_5_3_ONLY_ALIASES);

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

    private static final Set<String> CUBISM_5_2_CLASS_ALIASES_USED =
            without(CLASS_ALIASES_REQUIRED, CUBISM_5_3_ONLY_ALIASES);

    /** Exact class aliases used for runtime type validation by this implementation. */
    public static Set<String> classAliasesUsed() {
        return CLASS_ALIASES_REQUIRED;
    }

    /** Exact class aliases used on the reviewed 5.2.03 build (no {@code CAliasSource}). */
    public static Set<String> cubism52ClassAliasesUsed() {
        return CUBISM_5_2_CLASS_ALIASES_USED;
    }

    /**
     * The model source's own {@code contain*} gates — the host's semantic answer
     * to feature content the object census cannot see. Each predicate maps to a
     * stable family token. Detected families are pass-through content: the flag
     * set is pinned in the census, never a rejection reason by itself.
     */
    private static final Map<String, String> FEATURE_GATES = featureGates();

    private static final Map<String, String> CUBISM_5_2_FEATURE_GATES =
            withoutGates(FEATURE_GATES, CUBISM_5_3_ONLY_ALIASES);

    /**
     * Exact versions whose reviewed record carries the legacy protected-export surface — the
     * {@code cubism52} selector roster without the 5.3-only alias family, matching
     * {@code ProtectedExportVerificationManifest#cubism52Aliases()}.
     */
    private static final Set<String> CUBISM_LEGACY_SURFACE_VERSIONS = ReviewedVersions.between("5.1.00", "5.2.03");

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

    private static Map<String, String> withoutGates(final Map<String, String> gates, final Set<String> excluded) {
        final Map<String, String> remaining = new LinkedHashMap<>(gates);
        excluded.forEach(remaining::remove);
        return java.util.Collections.unmodifiableMap(remaining);
    }

    private final VerifiedMemberResolver resolver;
    private final ProtectedExportHostModelReadWriteOps modelOps;
    private final ProtectedExportHostSettingsOps settingsOps;
    private final ProtectedExportHostExportSurfaceOps exportSurfaceOps;

    public VerifiedProtectedExportHostOperations(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        final boolean cubism52 = CUBISM_LEGACY_SURFACE_VERSIONS.stream().anyMatch(resolver::isExactCubismVersion);
        final Map<String, String> admittedFeatureGates = cubism52 ? CUBISM_5_2_FEATURE_GATES : FEATURE_GATES;
        if (!resolver.authorizes(
                ProtectedExportVerificationManifest.ADAPTER_SLICE_ID,
                ProtectedExportVerificationManifest.CAPABILITY_IDS,
                cubism52 ? CUBISM_5_2_REQUIRED_ALIASES : REQUIRED_ALIASES)) {
            throw new IllegalArgumentException("verified access plan does not authorize the protected-export slice");
        }
        // The halves are built only after the plan is authorized, so a rejected plan can never
        // expose a partially wired host surface.
        this.modelOps = new ProtectedExportHostModelReadWriteOps(resolver, cubism52, admittedFeatureGates);
        this.settingsOps = new ProtectedExportHostSettingsOps(resolver, modelOps);
        this.exportSurfaceOps = new ProtectedExportHostExportSurfaceOps(resolver);
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
        if (project == null || document == null || !resolver.isInstance(PROJECT_CLASS, project)) {
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
        resolver.invoke(
                COMMAND_OPEN,
                Objects.requireNonNull(appController(), "appController"),
                Objects.requireNonNull(file, "file"),
                Boolean.FALSE);
    }

    @Override
    public void closeFileContent(final Object fileContent) {
        resolver.invoke(
                COMMAND_CLOSE,
                Objects.requireNonNull(appController(), "appController"),
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
        resolver.invoke(DOC_MARK_SAVED, Objects.requireNonNull(document, "document"), Long.MAX_VALUE);
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
        return undoManager != null
                && resolver.isInstance(UNDO_CLASS, undoManager)
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
        resolver.invoke(SELECTOR_ADD_SOURCE, requireSelector(selector), Objects.requireNonNull(source, "source"), -1);
    }

    @Override
    public List<?> selectedDeformers(final Object selector) {
        final Object selected = resolver.invoke(SELECTOR_SELECTED_DEFORMERS, requireSelector(selector));
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
        return modelOps.modelSourceDocument(modelSource);
    }

    @Override
    public Object modelSourceCurrentInstance(final Object modelSource) {
        return modelOps.modelSourceCurrentInstance(modelSource);
    }

    @Override
    public List<?> allDeformers(final Object modelSource) {
        return modelOps.allDeformers(modelSource);
    }

    @Override
    public List<?> allObjects(final Object modelSource) {
        return modelOps.allObjects(modelSource);
    }

    @Override
    public List<?> allArtMeshes(final Object modelSource) {
        return modelOps.allArtMeshes(modelSource);
    }

    @Override
    public List<?> allParts(final Object modelSource) {
        return modelOps.allParts(modelSource);
    }

    @Override
    public Object rootPart(final Object modelSource) {
        return modelOps.rootPart(modelSource);
    }

    @Override
    public List<?> allParameters(final Object modelSource) {
        return modelOps.allParameters(modelSource);
    }

    @Override
    public List<?> allPhysicsSettings(final Object modelSource) {
        return modelOps.allPhysicsSettings(modelSource);
    }

    @Override
    public List<?> allMotionSyncSettings(final Object modelSource) {
        return modelOps.allMotionSyncSettings(modelSource);
    }

    @Override
    public String modelSourceGuid(final Object modelSource) {
        return modelOps.modelSourceGuid(modelSource);
    }

    @Override
    public boolean serializeModelSource(final Object modelSource, final File target) {
        return modelOps.serializeModelSource(modelSource, target);
    }

    @Override
    public List<?> liveParameters(final Object modelSource) {
        return modelOps.liveParameters(modelSource);
    }

    @Override
    public String parameterInstanceId(final Object parameter) {
        return modelOps.parameterInstanceId(parameter);
    }

    @Override
    public float parameterInstanceValue(final Object parameter) {
        return modelOps.parameterInstanceValue(parameter);
    }

    @Override
    public void setParameterInstanceValue(final Object parameterInstance, final float value) {
        modelOps.setParameterInstanceValue(parameterInstance, value);
    }

    @Override
    public void evaluateModelInstance(final Object modelInstance) {
        modelOps.evaluateModelInstance(modelInstance);
    }

    @Override
    public List<?> modelInstanceArtMeshes(final Object modelInstance) {
        return modelOps.modelInstanceArtMeshes(modelInstance);
    }

    @Override
    public Object artMeshInstanceSource(final Object artMeshInstance) {
        return modelOps.artMeshInstanceSource(artMeshInstance);
    }

    @Override
    public float[] evaluatedArtMeshPositions(final Object artMeshInstance) {
        return modelOps.evaluatedArtMeshPositions(artMeshInstance);
    }

    // ------------------------------------------------------------------
    // Controllable / drawable / parameter reflections — ProtectedExportHostModelReadWriteOps
    // ------------------------------------------------------------------

    @Override
    public List<?> deformerChildren(final Object deformerSource) {
        return modelOps.deformerChildren(deformerSource);
    }

    @Override
    public void setObjectLocalName(final Object source, final String name) {
        modelOps.setObjectLocalName(source, name);
    }

    @Override
    public void setDrawableId(final Object drawableSource, final String idString) {
        modelOps.setDrawableId(drawableSource, idString);
    }

    @Override
    public String drawableIdString(final Object drawableSource) {
        return modelOps.drawableIdString(drawableSource);
    }

    @Override
    public String parameterSourceIdString(final Object parameterSource) {
        return modelOps.parameterSourceIdString(parameterSource);
    }

    @Override
    public String parameterSourceName(final Object parameterSource) {
        return modelOps.parameterSourceName(parameterSource);
    }

    @Override
    public Float parameterSourceMinValue(final Object parameterSource) {
        return modelOps.parameterSourceMinValue(parameterSource);
    }

    @Override
    public Float parameterSourceMaxValue(final Object parameterSource) {
        return modelOps.parameterSourceMaxValue(parameterSource);
    }

    @Override
    public Float parameterSourceDefaultValue(final Object parameterSource) {
        return modelOps.parameterSourceDefaultValue(parameterSource);
    }

    @Override
    public Boolean parameterSourceRepeat(final Object parameterSource) {
        return modelOps.parameterSourceRepeat(parameterSource);
    }

    @Override
    public List<?> keyformBindings(final Object controllableSource) {
        return modelOps.keyformBindings(controllableSource);
    }

    @Override
    public String keyformBindingParameterId(final Object binding) {
        return modelOps.keyformBindingParameterId(binding);
    }

    @Override
    public List<Float> keyformBindingKeys(final Object binding) {
        return modelOps.keyformBindingKeys(binding);
    }

    @Override
    public boolean isPartSource(final Object object) {
        return modelOps.isPartSource(object);
    }

    @Override
    public boolean isGlueSource(final Object object) {
        return modelOps.isGlueSource(object);
    }

    @Override
    public List<String> glueTargetGuids(final Object glueSource) {
        return modelOps.glueTargetGuids(glueSource);
    }

    @Override
    public List<String> partChildGuids(final Object partSource) {
        return modelOps.partChildGuids(partSource);
    }

    @Override
    public boolean isControllableSource(final Object object) {
        return modelOps.isControllableSource(object);
    }

    @Override
    public String sourceTargetDeformerGuid(final Object source) {
        return modelOps.sourceTargetDeformerGuid(source);
    }

    @Override
    public List<String> passThroughReferenceGuids(final Object source) {
        return modelOps.passThroughReferenceGuids(source);
    }

    @Override
    public List<String> passThroughFlagSignature(final Object source) {
        return modelOps.passThroughFlagSignature(source);
    }

    // ------------------------------------------------------------------
    // Physics / motion-sync settings — ProtectedExportHostSettingsOps
    // ------------------------------------------------------------------

    @Override
    public boolean isPhysicsSettingsSource(final Object object) {
        return settingsOps.isPhysicsSettingsSource(object);
    }

    @Override
    public boolean isMotionSyncSettingSource(final Object object) {
        return settingsOps.isMotionSyncSettingSource(object);
    }

    @Override
    public String settingsGuid(final Object settingsSource) {
        return settingsOps.settingsGuid(settingsSource);
    }

    @Override
    public String settingsIdString(final Object settingsSource) {
        return settingsOps.settingsIdString(settingsSource);
    }

    @Override
    public String settingsName(final Object settingsSource) {
        return settingsOps.settingsName(settingsSource);
    }

    @Override
    public List<String> settingsSignature(final Object modelSource, final Object settingsSource) {
        return settingsOps.settingsSignature(modelSource, settingsSource);
    }

    @Override
    public List<String> physicsSettingsSetSignature(final Object modelSource) {
        return settingsOps.physicsSettingsSetSignature(modelSource);
    }

    @Override
    public String parameterSourceGuid(final Object parameterSource) {
        return settingsOps.parameterSourceGuid(parameterSource);
    }

    @Override
    public void setSettingsName(final Object settingsSource, final String name) {
        settingsOps.setSettingsName(settingsSource, name);
    }

    @Override
    public void setSettingsId(final Object settingsSource, final String idString) {
        settingsOps.setSettingsId(settingsSource, idString);
    }

    // ------------------------------------------------------------------
    // Object identity
    // ------------------------------------------------------------------

    @Override
    public String objectGuid(final Object source) {
        return modelOps.objectGuid(source);
    }

    @Override
    public String objectIdString(final Object source) {
        return modelOps.objectIdString(source);
    }

    @Override
    public String objectLocalName(final Object source) {
        return modelOps.objectLocalName(source);
    }

    @Override
    public boolean isDeformerSource(final Object object) {
        return modelOps.isDeformerSource(object);
    }

    @Override
    public boolean isWarpDeformer(final Object object) {
        return modelOps.isWarpDeformer(object);
    }

    @Override
    public boolean isRotationDeformer(final Object object) {
        return modelOps.isRotationDeformer(object);
    }

    @Override
    public boolean isArtMeshSource(final Object object) {
        return modelOps.isArtMeshSource(object);
    }

    @Override
    public String deformerGuid(final Object deformerSource) {
        return modelOps.deformerGuid(deformerSource);
    }

    @Override
    public String deformerTargetGuid(final Object deformerSource) {
        return modelOps.deformerTargetGuid(deformerSource);
    }

    // ------------------------------------------------------------------
    // Extended-interpolation eligibility gate
    // ------------------------------------------------------------------

    @Override
    public boolean usesExtendedInterpolation(final Object modelSource) {
        return modelOps.usesExtendedInterpolation(modelSource);
    }

    @Override
    public List<String> modelFeatureFlags(final Object modelSource) {
        return modelOps.modelFeatureFlags(modelSource);
    }

    @Override
    public List<String> embeddedContentFamilies(final Object controllableSource) {
        return modelOps.embeddedContentFamilies(controllableSource);
    }

    @Override
    public String censusFamily(final Object object) {
        return modelOps.censusFamily(object);
    }

    // ------------------------------------------------------------------
    // Export dialog identity + native re-drive
    // ------------------------------------------------------------------

    @Override
    public boolean isExportDialog(final Object owner) {
        return exportSurfaceOps.isExportDialog(owner);
    }

    @Override
    public Object dialogModelSource(final Object dialogOwner) {
        return exportSurfaceOps.dialogModelSource(dialogOwner);
    }

    @Override
    public Object exportDriver() {
        return exportSurfaceOps.exportDriver();
    }

    @Override
    public void invokeNativeExport(
            final Object driver, final Object modelSource, final Object frame, final Object completionCallback) {
        exportSurfaceOps.invokeNativeExport(driver, modelSource, frame, completionCallback);
    }

    @Override
    public Object newExportCompletionProxy(final BiConsumer<File, List<String>> callback) {
        return exportSurfaceOps.newExportCompletionProxy(callback);
    }

    // ------------------------------------------------------------------
    // Native file-cache force release
    // ------------------------------------------------------------------

    @Override
    public boolean releaseFileHandleFor(final File file) {
        return exportSurfaceOps.releaseFileHandleFor(file);
    }

    @Override
    public List<String> fileHandleDiagnostics(final File file) {
        return exportSurfaceOps.fileHandleDiagnostics(file);
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

    private int intMember(final String alias, final Object target) {
        if (target == null) {
            return -1;
        }
        final Object value = resolver.invoke(alias, target);
        return value instanceof Number n ? n.intValue() : -1;
    }
}
