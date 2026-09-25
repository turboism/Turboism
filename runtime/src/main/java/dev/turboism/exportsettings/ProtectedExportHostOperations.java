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

    /** The file-content handle's backing file, or {@code null} when virtual. */
    File fileContentFile(Object fileContent);

    /** Whether the file content differs from its persisted on-disk state. */
    boolean fileContentModified(Object fileContent);

    /** Current position in the undo manager's edit stack. */
    int undoPosition(Object undoManager);

    /** Total number of edits recorded on the undo stack. */
    int undoEditCount(Object undoManager);

    /** Whether the undo manager currently has an undoable edit. */
    boolean undoCanUndo(Object undoManager);

    // ------------------------------------------------------------------
    // Selector / edit mode (deformer application)
    // ------------------------------------------------------------------

    /** Whether the selector is the modeling main selector that deformer commands read. */
    boolean isMainSelector(Object selector);

    /** Whether the edit mode is the modeling main edit mode owning the apply command. */
    boolean isMainEditMode(Object editMode);

    /** Clears every selection currently held by the selector. */
    void clearSelection(Object selector);

    /** Number of sources currently selected on the selector. */
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

    /** Every deformer source in the model source, in model order (immutable). */
    List<?> allDeformers(Object modelSource);

    /** Every parameter-controllable object in the model source (immutable). */
    List<?> allObjects(Object modelSource);

    /** Every art mesh source in the model source (immutable). */
    List<?> allArtMeshes(Object modelSource);

    /** Every part source in the model source, including the root (immutable). */
    List<?> allParts(Object modelSource);

    /**
     * The model source's synthetic root part — present in {@link #allParts} but
     * never serialized by the native exporter — or {@code null} when absent.
     */
    Object rootPart(Object modelSource);

    /** Every parameter in the model source, in model order (immutable). */
    List<?> allParameters(Object modelSource);

    /**
     * Physics settings sources — outside the parameter-controllable census.
     * Pass-through content: admitted untouched and pinned by identity and
     * structure signature so a mid-session mutation is a drift rejection.
     */
    List<?> allPhysicsSettings(Object modelSource);

    /**
     * Motion-sync settings sources — outside the parameter-controllable census.
     * Same pass-through semantics as physics settings.
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

    /**
     * Stable GUID string of a parameter source ({@code CParameterSource.getGuid}).
     * Physics input/output entries reference their parameter through this GUID —
     * the census resolves it to the pinned parameter ID so a physics reference
     * that cannot be resolved fails closed.
     */
    String parameterSourceGuid(Object parameterSource);

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

    /** True when {@code object} is a deformer source. */
    boolean isDeformerSource(Object object);

    /**
     * True when {@code object} is a parameter-controllable source — the
     * supertype of every member {@link #allObjects} may carry. Objects failing
     * this check have no pinnable identity and cannot pass through.
     */
    boolean isControllableSource(Object object);

    /**
     * Stable family token for a census object — {@code warp}, {@code rotation},
     * {@code artmesh}, {@code part}, {@code glue}, {@code art-path},
     * {@code alias}, a package-derived token such as {@code deform-path}, or a
     * bounded {@code unknown:<class-clue>} token when no family matches.
     * Diagnostics only; never {@code null} and never longer than a short token.
     */
    String censusFamily(Object object);

    /** True when {@code object} is a warp deformer source. */
    boolean isWarpDeformer(Object object);

    /** True when {@code object} is a rotation deformer source. */
    boolean isRotationDeformer(Object object);

    /** True when {@code object} is an art mesh source. */
    boolean isArtMeshSource(Object object);

    /** True when {@code object} is a part source. */
    boolean isPartSource(Object object);

    /**
     * True when {@code object} is a Glue affecter source. Glue is admitted as an
     * untouched pass-through channel: it is never flattened, renamed or
     * re-identified, but it is still censused so a mutation during the session is
     * detected instead of silently published.
     */
    boolean isGlueSource(Object object);

    /**
     * Ordered {@code [targetArtMeshA, targetArtMeshB]} stable-GUID strings of one
     * Glue source's mesh references; a {@code null} entry means the reference did
     * not resolve to a live source. Glue references ride on GUIDs, which ArtMesh
     * name/ID obfuscation never rewrites — the census pins them so a drift is a
     * rejection rather than an invisible reference break.
     */
    List<String> glueTargetGuids(Object glueSource);

    /**
     * Child-membership GUID strings of a part source — the part palette nesting
     * (child parts and member drawables/deformers). Empty when none or unreadable.
     */
    List<String> partChildGuids(Object partSource);

    /**
     * Parent-edge GUID string of a controllable source
     * ({@code getTargetDeformerGuid}) — the parent deformer a source hangs
     * under, or {@code null} for a root-level member. Flattening may
     * legitimately re-root a pass-through object's parent edge (the removed
     * deformer), so the drift check treats a transition to {@code null} as
     * legal and any other change as drift.
     */
    String sourceTargetDeformerGuid(Object source);

    /**
     * Ordered reference-GUID strings a pass-through object carries, composed
     * per family: Glue returns its two target ArtMesh GUIDs, an ArtPath its
     * brush GUID then clip-mask GUIDs, an alias its reference-object GUID then
     * clip-mask GUIDs, a Part its clip-mask GUIDs, an ArtMesh its clip-mask
     * GUIDs; a family without references returns an empty list. Entries are
     * {@code null} when the reference slot is unset. These references ride on
     * stable GUIDs, which ArtMesh name/ID obfuscation never rewrites — the
     * census pins them so a drift is a rejection rather than an invisible
     * reference break.
     */
    List<String> passThroughReferenceGuids(Object source);

    /**
     * Ordered {@code key=value} flag tokens pinning the host-reported content
     * flags of a source — drawable clip-inversion, part offscreen/clipping/
     * color-composition/alpha-composition, alias flags — empty for families
     * without pinnable flags or on hosts that do not expose them. Tokens are
     * deterministic so a silent flag mutation is a drift rejection.
     */
    List<String> passThroughFlagSignature(Object source);

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
     * Content feature families the host reports for a model source via its own
     * {@code contain*} gates — the authoritative semantic checks for content
     * the object census cannot see (blend/multiply/screen color, morph-target
     * parameters and enhancements, aliases, art paths, inverted clipping, quad
     * transforms, offscreen rendering, motion sync). Detected families are
     * pass-through content: the returned tokens are pinned in the census so a
     * feature silently appearing or disappearing mid-session is a drift
     * rejection. Empty means the host detects none of them.
     */
    List<String> modelFeatureFlags(Object modelSource);

    /**
     * Content structures embedded inside an otherwise-allowed controllable
     * source — invisible to the {@link #allObjects} census because they live in
     * members, not the object list: keyform morph-target sets, extended morph
     * target sets and any attached extension objects (deform-path skinning
     * lives here). They are pass-through content: the token list is pinned in
     * the census so a mutation or serialization loss is a drift rejection.
     * Empty means the source carries none of them.
     */
    List<String> embeddedContentFamilies(Object controllableSource);

    // ------------------------------------------------------------------
    // Pass-through settings objects (physics, motion sync)
    // ------------------------------------------------------------------

    /**
     * True when {@code object} is a physics settings source. Physics settings
     * are pass-through content: they are never rewritten, but each is pinned by
     * identity and structure so a mutation mid-session is a drift rejection.
     */
    boolean isPhysicsSettingsSource(Object object);

    /**
     * True when {@code object} is a motion-sync setting source. Same
     * pass-through semantics as physics settings.
     */
    boolean isMotionSyncSettingSource(Object object);

    /**
     * Stable GUID string of a settings source (physics or motion sync);
     * {@code null} when the object is not a recognized settings family or the
     * GUID is unset — the census treats that as unpinnable and rejects.
     */
    String settingsGuid(Object settingsSource);

    /**
     * ID string of a settings source; {@code null} when unrecognized or unset.
     */
    String settingsIdString(Object settingsSource);

    /**
     * Local name of a settings source; {@code null} when unrecognized.
     */
    String settingsName(Object settingsSource);

    /**
     * Ordered {@code key=value} tokens pinning a settings source's authored
     * <em>behavior content</em> — everything except its name and ID, which the
     * protected export rewrites to obfuscation tokens. For physics the tokens
     * carry the enable flag, normalization windows, total angle, and every
     * input/output/vertex entry with its resolved parameter IDs (the physics
     * contract preserves parameter references); for motion sync, the version,
     * mapping and post-processing checksums. A parameter reference that cannot
     * be resolved against the model's parameter census, or an unreadable
     * member, makes the signature {@code null} — the census rejects, never
     * assumes. Ordered.
     *
     * @param modelSource the owning model source (parameter reference resolution)
     * @param settingsSource a physics or motion-sync settings source
     */
    List<String> settingsSignature(Object modelSource, Object settingsSource);

    /**
     * Rewrites a settings source's local name — disposable-copy obfuscation only.
     */
    void setSettingsName(Object settingsSource, String name);

    /**
     * Rewrites a settings source's ID — disposable-copy obfuscation only.
     * Implementations rebuild the typed host ID object
     * ({@code CPhysicsSettingId}/{@code CMotionSyncSettingId}); the settings GUID
     * is never touched.
     */
    void setSettingsId(Object settingsSource, String idString);

    /**
     * Ordered {@code key=value} tokens pinning the physics settings <em>set</em>
     * level state that governs every contained setting — effective forces
     * (gravity, wind), the configured FPS, and the selected settings GUID.
     * Empty when the model carries no physics settings at all; {@code null}
     * when physics settings exist but the set cannot be pinned (the census
     * rejects).
     */
    List<String> physicsSettingsSetSignature(Object modelSource);

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
