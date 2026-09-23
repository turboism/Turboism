package dev.turboism.exportsettings;

import java.io.File;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Semantic seam between the protected-export orchestrator and verified host members.
 *
 * <p>All host objects are opaque {@code Object} handles; the production implementation is
 * {@link VerifiedProtectedExportHostOperations}, backed by the exact record slice of the
 * admitted reviewed build (5.2.03, 5.3.02 or 5.3.03).
 * Tests substitute fakes to drive the fault-injection matrix without a host.</p>
 */
public interface ProtectedExportHostOperations {

    // ------------------------------------------------------------------
    // Session / application
    // ------------------------------------------------------------------

    /** The {@code CEAppCtrl} singleton. */
    Object appController();

    /** The active document, or {@code null} when none is open. */
    Object currentDocument();

    /** The active project, or {@code null}. */
    Object currentProject();

    /** Whether the project currently contains the given document. */
    boolean projectContains(Object document);

    /**
     * Modeling documents currently in the active project. Used to find a bound copy
     * document that completed its native open after the bind wait already timed out.
     */
    List<?> projectDocuments();

    /** The main frame used as export-dialog parent, or {@code null}. */
    Object mainFrame();

    /** Opens a file through the native open path (non-interactive). */
    void openFile(File file);

    /** Closes a file content through the native close command. */
    void closeFileContent(Object fileContent);

    // ------------------------------------------------------------------
    // Document
    // ------------------------------------------------------------------

    /** Whether the document is a modeling document. */
    boolean isModelingDocument(Object document);

    /** The document's model source, or {@code null}. */
    Object documentModelSource(Object document);

    /** The document's file content handle, or {@code null}. */
    Object documentFileContent(Object document);

    /** The document's selector, or {@code null}. */
    Object documentSelector(Object document);

    /** The document's current edit mode (any subtype), or {@code null}. */
    Object documentCurrentEditMode(Object document);

    /** The document's modeling-main edit mode, or {@code null}. */
    Object documentMainEditMode(Object document);

    /** The document's backing file, or {@code null}. */
    File documentFile(Object document);

    /** The document's undo manager, or {@code null}. */
    Object documentUndoManager(Object document);

    /** Marks the (disposable) document saved so native close discards without prompting. */
    void markDocumentSaved(Object document);

    // ------------------------------------------------------------------
    // File content / undo signatures
    // ------------------------------------------------------------------

    File fileContentFile(Object fileContent);

    boolean fileContentModified(Object fileContent);

    int undoPosition(Object undoManager);

    int undoEditCount(Object undoManager);

    boolean undoCanUndo(Object undoManager);

    // ------------------------------------------------------------------
    // Selector / edit mode (deformer application)
    // ------------------------------------------------------------------

    /** Whether the selector is the modeling main selector that deformer commands read. */
    boolean isMainSelector(Object selector);

    /** Whether the edit mode is the modeling main edit mode owning the apply command. */
    boolean isMainEditMode(Object editMode);

    void clearSelection(Object selector);

    int selectedCount(Object selector);

    /** Adds a parameter-controllable source directly to the modeling selector. */
    void selectSource(Object selector, Object source);

    /** Deformer sources currently selected on the modeling main selector. */
    List<?> selectedDeformers(Object selector);

    /** Applies the selected deformer into its target parameters on the main edit mode. */
    void applyDeformerToParameters(Object mainEditMode);

    // ------------------------------------------------------------------
    // Model source census
    // ------------------------------------------------------------------

    /** The modeling document owning a model source, or {@code null}. */
    Object modelSourceDocument(Object modelSource);

    /** The live model instance of a model source, or {@code null}. */
    Object modelSourceCurrentInstance(Object modelSource);

    List<?> allDeformers(Object modelSource);

    List<?> allObjects(Object modelSource);

    List<?> allArtMeshes(Object modelSource);

    List<?> allParts(Object modelSource);

    /**
     * The model source's synthetic root part — present in {@link #allParts} but
     * never serialized by the native exporter — or {@code null} when absent.
     */
    Object rootPart(Object modelSource);

    List<?> allParameters(Object modelSource);

    /**
     * Physics settings sources — outside the parameter-controllable census.
     * Protected export admits only models with none.
     */
    List<?> allPhysicsSettings(Object modelSource);

    /**
     * Motion-sync settings sources — outside the parameter-controllable census.
     * Protected export admits only models with none.
     */
    List<?> allMotionSyncSettings(Object modelSource);

    /** Stable model GUID string of a model source. */
    String modelSourceGuid(Object modelSource);

    /**
     * Serializes the live model source to {@code target}
     * ({@code CModelSource.saveModel(File, boolean)}) — the in-memory state the
     * protected export must stage, including edits not yet written to disk.
     * Pure serialization: it never touches the owning document's dirty flag,
     * undo state, selection or file binding.
     *
     * @return {@code true} when the host serializer reported success
     */
    boolean serializeModelSource(Object modelSource, File target);

    /** Parameter objects of the model instance's live parameter set (empty when absent). */
    List<?> liveParameters(Object modelSource);

    /** Parameter ID string of a live parameter instance. */
    String parameterInstanceId(Object parameter);

    /** Current value of a live parameter instance, or {@code Float.NaN} when unreadable. */
    float parameterInstanceValue(Object parameter);

    /**
     * Writes a live parameter instance's value ({@code CParameter.setValue}).
     * Behavior sampling only — used on the disposable copy's model instance,
     * never the authoring document.
     */
    void setParameterInstanceValue(Object parameterInstance, float value);

    /**
     * Re-evaluates a model instance's calculated forms
     * ({@code CModel.reinitModelInstance_exe} — the self-contained "update
     * model" path that tolerates null view context). Behavior sampling only.
     */
    void evaluateModelInstance(Object modelInstance);

    /** Instance ArtMeshes of a live model instance (empty when unreadable). */
    List<?> modelInstanceArtMeshes(Object modelInstance);

    /** The source object an instance ArtMesh evaluates, or {@code null}. */
    Object artMeshInstanceSource(Object artMeshInstance);

    /**
     * Evaluated vertex positions of an instance ArtMesh's calculated form —
     * post-deformation geometry in model space — or {@code null} when the form
     * cannot be read.
     */
    float[] evaluatedArtMeshPositions(Object artMeshInstance);

    // ------------------------------------------------------------------
    // Object identity
    // ------------------------------------------------------------------

    /** Stable GUID string of a parameter-controllable source. */
    String objectGuid(Object source);

    /** ID string of a parameter-controllable source. */
    String objectIdString(Object source);

    /** Local name of a parameter-controllable source. */
    String objectLocalName(Object source);

    /** Rewrites a source's local name — disposable-copy obfuscation only. */
    void setObjectLocalName(Object source, String name);

    /** Drawable ID string of an ArtMesh source, or {@code null} when unreadable. */
    String drawableIdString(Object drawableSource);

    /** Rewrites an ArtMesh source's drawable ID — disposable-copy obfuscation only. */
    void setDrawableId(Object drawableSource, String idString);

    /**
     * Parameter ID string of a parameter <em>source</em>. Parameter sources are
     * not parameter-controllable sources — they need their own accessor.
     */
    String parameterSourceIdString(Object parameterSource);

    /** Display name of a parameter source, or {@code null} when unreadable. */
    String parameterSourceName(Object parameterSource);

    /** Minimum of a parameter source's evaluable range, or {@code null} unreadable. */
    Float parameterSourceMinValue(Object parameterSource);

    /** Maximum of a parameter source's evaluable range, or {@code null} unreadable. */
    Float parameterSourceMaxValue(Object parameterSource);

    /** A parameter source's default value, or {@code null} when unreadable. */
    Float parameterSourceDefaultValue(Object parameterSource);

    /** A parameter source's repeat flag, or {@code null} when unreadable. */
    Boolean parameterSourceRepeat(Object parameterSource);

    /**
     * Keyform bindings of a controllable source across both its keyform grids
     * (normal and extended), deduplicated by identity. Empty when the source has
     * no grid.
     */
    List<?> keyformBindings(Object controllableSource);

    /** Parameter ID string a keyform binding targets, or {@code null} when absent. */
    String keyformBindingParameterId(Object binding);

    /** Key positions a keyform binding contributes to its parameter (immutable). */
    List<Float> keyformBindingKeys(Object binding);

    boolean isDeformerSource(Object object);

    boolean isWarpDeformer(Object object);

    boolean isRotationDeformer(Object object);

    boolean isArtMeshSource(Object object);

    boolean isPartSource(Object object);

    /**
     * Child-membership GUID strings of a part source — the part palette nesting
     * (child parts and member drawables/deformers). Empty when none or unreadable.
     */
    List<String> partChildGuids(Object partSource);

    /** Deformer GUID string; the receiver must be a deformer source. */
    String deformerGuid(Object deformerSource);

    /** Target (parent) deformer GUID string, or {@code null} when absent. */
    String deformerTargetGuid(Object deformerSource);

    // ------------------------------------------------------------------
    // Deformer structure inspection (flatten diagnostics)
    // ------------------------------------------------------------------

    /**
     * Direct children of a deformer source — every controllable source whose
     * target deformer is this one ({@code ACDeformerSource.getDeformerChildren}).
     * Drawables and nested deformers alike; empty for a childless deformer.
     */
    List<?> deformerChildren(Object deformerSource);

    // ------------------------------------------------------------------
    // Extended-interpolation eligibility gate
    // ------------------------------------------------------------------

    /**
     * Whether any object of the model source uses extended interpolation.
     *
     * <p>Reads every object's keyform grid bindings host-side: a non-{@code LINEAR}
     * extended-interpolation type or an illegal-extended flag counts as usage. This is
     * the host-resolved form of the planner's unresolved preflight condition; when it
     * returns {@code true} (or cannot be proven {@code false}) the export rejects.</p>
     */
    boolean usesExtendedInterpolation(Object modelSource);

    /**
     * Unsupported feature families the host reports for a model source via its
     * own {@code contain*} gates — the authoritative semantic checks for
     * content the object census cannot see (blend/multiply/screen color,
     * morph-target parameters and enhancements, aliases, art paths, inverted
     * clipping, quad transforms, offscreen rendering, motion sync). The
     * returned tokens are stable family names for reporting only; empty means
     * the host detects none of them.
     */
    List<String> unsupportedModelFeatures(Object modelSource);

    /**
     * Unsupported structures embedded inside an otherwise-allowed controllable
     * source — invisible to the {@link #allObjects} census because they live in
     * members, not the object list: keyform morph-target sets, extended morph
     * target sets and any attached extension objects. Empty means the source
     * carries none of them.
     */
    List<String> embeddedUnsupportedFamilies(Object controllableSource);

    // ------------------------------------------------------------------
    // Export dialog identity + native re-drive
    // ------------------------------------------------------------------

    /** Whether the object is the export settings dialog owner. */
    boolean isExportDialog(Object owner);

    /** The model source bound into the export settings dialog instance. */
    Object dialogModelSource(Object dialogOwner);

    /** The {@code appCtrlImpl/al} export driver singleton. */
    Object exportDriver();

    /**
     * Re-drives the native export entry {@code al.a(CModelSource, CFrame, Function2)}.
     *
     * @param driver the driver from {@link #exportDriver()}
     * @param modelSource bound copy's model source
     * @param frame parent frame or {@code null} for the native fallback
     * @param completionCallback proxy from {@link #newExportCompletionProxy(BiConsumer)}
     */
    void invokeNativeExport(
        Object driver,
        Object modelSource,
        Object frame,
        Object completionCallback
    );

    /**
     * Builds a host-classloader {@code kotlin.jvm.functions.Function2} proxy receiving the
     * staged output file and the generated absolute-path list.
     */
    Object newExportCompletionProxy(BiConsumer<File, List<String>> callback);

    // ------------------------------------------------------------------
    // Native file-cache force release (disposable copy cleanup)
    // ------------------------------------------------------------------

    /**
     * Force-releases the native file-cache handle for the given file so the disposable
     * copy can be deleted even if the host still pins its loader.
     *
     * @return {@code true} when no handle remains for the file afterwards
     */
    boolean releaseFileHandleFor(File file);

    /**
     * Diagnostic snapshot of the file-cache entries pinned to {@code file}:
     * {@code loader=<loader-class>|listeners=<n>} per matching handle.
     */
    List<String> fileHandleDiagnostics(File file);
}
