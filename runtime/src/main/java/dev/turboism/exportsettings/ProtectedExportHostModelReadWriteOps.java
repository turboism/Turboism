package dev.turboism.exportsettings;

import static dev.turboism.exportsettings.ProtectedExportHostVocabulary.*;

import dev.turboism.adapter.cubism.profile.CubismExportHostProfile;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Model-object-graph half of {@link VerifiedProtectedExportHostOperations}: the model source
 * census reads, the drawable/parameter/deformer reflection reads and the two rewrites the
 * protected ArtMesh obfuscation pass needs, plus the embedded-content and family facts the
 * census pins.
 *
 * <p>Package-private and read-mostly on purpose. It never decides whether a rewrite is
 * admissible — {@code ProtectedExportOrchestrator} owns that — and it never carries a host
 * object out of the package. Every member access still resolves through
 * {@link VerifiedMemberResolver}, so a stale or tampered host fails closed here exactly as it
 * did inside the operations facade.</p>
 */
final class ProtectedExportHostModelReadWriteOps {

    private final VerifiedMemberResolver resolver;
    private final boolean cubism52;
    private final Map<String, String> admittedFeatureGates;

    /**
     * Binds the reads to one exact-version resolver. {@code cubism52} gates the alias and part
     * clip-mask members the reviewed 5.2.03 surface does not declare; the gate roster is the one
     * the admitted record verified.
     */
    ProtectedExportHostModelReadWriteOps(
            final VerifiedMemberResolver resolver,
            final boolean cubism52,
            final Map<String, String> admittedFeatureGates) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.cubism52 = cubism52;
        this.admittedFeatureGates = Objects.requireNonNull(admittedFeatureGates, "admittedFeatureGates");
    }

    // ------------------------------------------------------------------
    // Model source census
    // ------------------------------------------------------------------

    Object modelSourceDocument(final Object modelSource) {
        return modelSource == null ? null : resolver.invoke(MS_DOCUMENT, modelSource);
    }

    Object modelSourceCurrentInstance(final Object modelSource) {
        return modelSource == null ? null : resolver.invoke(MS_INSTANCE, modelSource);
    }

    List<?> allDeformers(final Object modelSource) {
        return listOf(resolver.invoke(MS_DEFORMERS, requireModelSource(modelSource)));
    }

    List<?> allObjects(final Object modelSource) {
        return listOf(resolver.invoke(MS_OBJECTS, requireModelSource(modelSource)));
    }

    List<?> allArtMeshes(final Object modelSource) {
        return listOf(resolver.invoke(MS_ART_MESHES, requireModelSource(modelSource)));
    }

    List<?> allParts(final Object modelSource) {
        return listOf(resolver.invoke(MS_PARTS, requireModelSource(modelSource)));
    }

    Object rootPart(final Object modelSource) {
        return resolver.invoke(MS_ROOT_PART, requireModelSource(modelSource));
    }

    List<?> allParameters(final Object modelSource) {
        return listOf(resolver.invoke(MS_PARAMETERS, requireModelSource(modelSource)));
    }

    List<?> allPhysicsSettings(final Object modelSource) {
        return listOf(resolver.invoke(MS_PHYSICS, requireModelSource(modelSource)));
    }

    List<?> allMotionSyncSettings(final Object modelSource) {
        return listOf(resolver.invoke(MS_MOTION_SYNC, requireModelSource(modelSource)));
    }

    String modelSourceGuid(final Object modelSource) {
        final Object guid = resolver.invoke(MS_GUID, requireModelSource(modelSource));
        return guid == null ? null : (String) resolver.invoke(GUID_UUID, guid);
    }

    private Object requireModelSource(final Object modelSource) {
        if (!resolver.isInstance(MODEL_SOURCE_CLASS, modelSource)) {
            throw new IllegalStateException("object is not a verified model source");
        }
        return modelSource;
    }

    boolean serializeModelSource(final Object modelSource, final File target) {
        Objects.requireNonNull(target, "target");
        // saveModel(File, Z) is the model source's own serializer — the same
        // entry CModelingDocument.saveDocument delegates to, minus the
        // document-state updates (saved timestamp, project wiring) that would
        // disturb the live session. The boolean is the developer-mode
        // save-obfuscation flag; staging always writes the un-obfuscated form
        // because the copy's census must match the live census before the
        // planned obfuscation pass rewrites it.
        return Boolean.TRUE.equals(
                resolver.invoke(MS_SAVE_MODEL, requireModelSource(modelSource), target, Boolean.FALSE));
    }

    List<?> liveParameters(final Object modelSource) {
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

    String parameterInstanceId(final Object parameter) {
        if (parameter == null || !resolver.isInstance(PARAMETER_INSTANCE_CLASS, parameter)) {
            return null;
        }
        final Object id = resolver.invoke(PARAMETER_INSTANCE_ID, parameter);
        return id == null ? null : (String) resolver.invoke(ID_STRING, id);
    }

    float parameterInstanceValue(final Object parameter) {
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
    void setParameterInstanceValue(final Object parameterInstance, final float value) {
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
    void evaluateModelInstance(final Object modelInstance) {
        if (!resolver.isInstance(MODEL_CLASS, modelInstance)) {
            throw new IllegalStateException("object is not a model instance");
        }
        resolver.invoke(MODEL_REINIT_EXE, modelInstance);
    }

    List<?> modelInstanceArtMeshes(final Object modelInstance) {
        if (!resolver.isInstance(MODEL_CLASS, modelInstance)) {
            return List.of();
        }
        return listOf(resolver.invoke(MODEL_ART_MESHES, modelInstance));
    }

    Object artMeshInstanceSource(final Object artMeshInstance) {
        if (!resolver.isInstance(ART_MESH_INSTANCE_CLASS, artMeshInstance)) {
            return null;
        }
        return resolver.invoke(ART_MESH_INSTANCE_SOURCE, artMeshInstance);
    }

    float[] evaluatedArtMeshPositions(final Object artMeshInstance) {
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

    // ------------------------------------------------------------------
    // Controllable / drawable / parameter reflections
    // ------------------------------------------------------------------

    /**
     * {@code ACDeformerSource.getDeformerChildren} — every controllable source
     * whose target deformer is this one. Drawable children carry no keyform
     * bindings of their own here; the caller filters by class.
     */
    List<?> deformerChildren(final Object deformerSource) {
        if (!resolver.isInstance(DEFORMER_CLASS, deformerSource)) {
            return List.of();
        }
        return listOf(resolver.invoke(DEFORMER_CHILDREN, deformerSource));
    }

    /**
     * Rewrites a parameter-controllable source's local name. Used only by the protected
     * ArtMesh obfuscation pass on the disposable copy (never on the authoring document).
     */
    void setObjectLocalName(final Object source, final String name) {
        resolver.invoke(
                SOURCE_SET_LOCAL_NAME, Objects.requireNonNull(source, "source"), Objects.requireNonNull(name, "name"));
    }

    /**
     * Rewrites a drawable source's ID on the disposable copy. Rejecting callers that are
     * not drawable sources keeps the M5 pass scoped to ArtMesh identities.
     */
    void setDrawableId(final Object drawableSource, final String idString) {
        if (!resolver.isInstance(DRAWABLE_CLASS, drawableSource)) {
            throw new IllegalStateException("object is not a drawable source");
        }
        final Object newId = resolver.construct(DRAWABLE_ID_CREATE, idString);
        resolver.invoke(DRAWABLE_ID_SET, drawableSource, newId);
    }

    /** Drawable-specific ID string of an ArtMesh/drawable source. */
    String drawableIdString(final Object drawableSource) {
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
    String parameterSourceIdString(final Object parameterSource) {
        if (!resolver.isInstance(PARAMETER_CLASS, parameterSource)) {
            return null;
        }
        final Object id = resolver.invoke(PARAMETER_SOURCE_ID, parameterSource);
        return id == null ? null : (String) resolver.invoke(ID_STRING, id);
    }

    String parameterSourceName(final Object parameterSource) {
        if (!resolver.isInstance(PARAMETER_CLASS, parameterSource)) {
            return null;
        }
        final Object name = resolver.invoke(PARAMETER_SOURCE_NAME, parameterSource);
        return name instanceof String text ? text : null;
    }

    Float parameterSourceMinValue(final Object parameterSource) {
        return floatParameterMember(PARAMETER_SOURCE_MIN, parameterSource);
    }

    Float parameterSourceMaxValue(final Object parameterSource) {
        return floatParameterMember(PARAMETER_SOURCE_MAX, parameterSource);
    }

    Float parameterSourceDefaultValue(final Object parameterSource) {
        return floatParameterMember(PARAMETER_SOURCE_DEFAULT, parameterSource);
    }

    Boolean parameterSourceRepeat(final Object parameterSource) {
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
    List<?> keyformBindings(final Object controllableSource) {
        final java.util.Set<Object> grids = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final List<Object> bindings = new ArrayList<>();
        for (String gridAlias : new String[] {SOURCE_GRID, SOURCE_EXT_GRID}) {
            final Object grid = controllableSource == null ? null : resolver.invoke(gridAlias, controllableSource);
            if (grid == null || !grids.add(grid) || !resolver.isInstance(GRID_CLASS, grid)) {
                continue;
            }
            bindings.addAll(listOf(resolver.invoke(GRID_BINDINGS, grid)));
        }
        return List.copyOf(bindings);
    }

    String keyformBindingParameterId(final Object binding) {
        if (!resolver.isInstance(BINDING_CLASS, binding)) {
            return null;
        }
        final Object id = resolver.invoke(BINDING_PARAMETER_ID, binding);
        return id == null ? null : (String) resolver.invoke(ID_STRING, id);
    }

    List<Float> keyformBindingKeys(final Object binding) {
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

    boolean isPartSource(final Object object) {
        return resolver.isInstance(PART_CLASS, object);
    }

    boolean isGlueSource(final Object object) {
        return resolver.isInstance(GLUE_CLASS, object);
    }

    List<String> glueTargetGuids(final Object glueSource) {
        if (!resolver.isInstance(GLUE_CLASS, glueSource)) {
            return List.of();
        }
        final List<String> targets = new ArrayList<>(2);
        for (String alias : new String[] {GLUE_TARGET_A, GLUE_TARGET_B}) {
            final Object mesh = resolver.invoke(alias, glueSource);
            targets.add(mesh == null || !resolver.isInstance(ART_MESH_CLASS, mesh) ? null : objectGuid(mesh));
        }
        return java.util.Collections.unmodifiableList(targets);
    }

    List<String> partChildGuids(final Object partSource) {
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

    boolean isControllableSource(final Object object) {
        return resolver.isInstance(CONTROLLABLE_CLASS, object);
    }

    String sourceTargetDeformerGuid(final Object source) {
        if (source == null || !resolver.isInstance(CONTROLLABLE_CLASS, source)) {
            return null;
        }
        final Object target = resolver.invoke(SOURCE_TARGET_DEFORMER, source);
        return target == null ? null : (String) resolver.invoke(GUID_UUID, target);
    }

    /**
     * Ordered reference GUIDs a census member carries, composed per family. Glue
     * targets are resolved mesh GUIDs; ArtPath carries its brush GUID followed by
     * clip-mask GUIDs; an alias carries its reference-object GUID followed by
     * clip-mask GUIDs; parts and ArtMeshes carry clip-mask GUIDs. Reference slots
     * that are unset read as {@code null} entries so the census can pin their
     * presence-or-absence exactly.
     */
    List<String> passThroughReferenceGuids(final Object source) {
        if (source == null) {
            return List.of();
        }
        if (resolver.isInstance(GLUE_CLASS, source)) {
            return glueTargetGuids(source);
        }
        final List<String> references = new ArrayList<>();
        if (resolver.isInstance(ART_PATH_CLASS, source)) {
            references.add(readGuid(resolver.invoke(ART_PATH_BRUSH, source)));
            references.addAll(clipGuidStrings(resolver.invoke(DRAWABLE_CLIP_GUIDS, source)));
            return java.util.Collections.unmodifiableList(references);
        }
        if (!cubism52 && resolver.isInstance(ALIAS_CLASS, source)) {
            references.add(readGuid(resolver.invoke(ALIAS_REFERENCE, source)));
            references.addAll(clipGuidStrings(resolver.invoke(ALIAS_CLIP_GUIDS, source)));
            return java.util.Collections.unmodifiableList(references);
        }
        if (!cubism52 && resolver.isInstance(PART_CLASS, source)) {
            references.addAll(clipGuidStrings(resolver.invoke(PART_CLIP_GUIDS, source)));
            return java.util.Collections.unmodifiableList(references);
        }
        if (resolver.isInstance(DRAWABLE_CLASS, source)) {
            references.addAll(clipGuidStrings(resolver.invoke(DRAWABLE_CLIP_GUIDS, source)));
        }
        return java.util.Collections.unmodifiableList(references);
    }

    /**
     * Ordered {@code key=value} flag tokens for the source's content flags. The
     * token order is fixed per family so census equality is deterministic.
     */
    List<String> passThroughFlagSignature(final Object source) {
        if (source == null) {
            return List.of();
        }
        final List<String> flags = new ArrayList<>();
        if (!cubism52 && resolver.isInstance(ALIAS_CLASS, source)) {
            flags.add("invertClipping=" + flagValue(ALIAS_INVERT_CLIP, source));
            flags.add("useOffscreen=" + flagValue(ALIAS_USE_OFFSCREEN, source));
            flags.add("colorComposition=" + enumName(resolver.invoke(ALIAS_COLOR_COMPOSITION, source)));
            flags.add("alphaComposition=" + enumName(resolver.invoke(ALIAS_ALPHA_COMPOSITION, source)));
            flags.add("circulated=" + flagValue(ALIAS_CIRCULATED, source));
            return List.copyOf(flags);
        }
        if (!cubism52 && resolver.isInstance(PART_CLASS, source)) {
            flags.add("useOffscreen=" + flagValue(PART_USE_OFFSCREEN, source));
            flags.add("invertClipping=" + flagValue(PART_INVERT_CLIP, source));
            flags.add("colorComposition=" + enumName(resolver.invoke(PART_COLOR_COMPOSITION, source)));
            flags.add("alphaComposition=" + enumName(resolver.invoke(PART_ALPHA_COMPOSITION, source)));
            return List.copyOf(flags);
        }
        if (resolver.isInstance(DRAWABLE_CLASS, source)) {
            flags.add("invertClipping=" + flagValue(DRAWABLE_INVERT_CLIP, source));
        }
        return List.copyOf(flags);
    }

    /** Float member of a parameter source, or {@code null} when unreadable. */
    private Float floatParameterMember(final String alias, final Object parameterSource) {
        if (!resolver.isInstance(PARAMETER_CLASS, parameterSource)) {
            return null;
        }
        final Object value = resolver.invoke(alias, parameterSource);
        return value instanceof Number number ? number.floatValue() : null;
    }

    /** The {@code getUuidString} of a GUID object, or {@code null} when unset. */
    String readGuid(final Object guid) {
        return guid == null ? null : (String) resolver.invoke(GUID_UUID, guid);
    }

    /** UUID strings of a GUID list member ({@code CArrayList<CClipGuid>} etc.). */
    private List<String> clipGuidStrings(final Object guids) {
        if (!(guids instanceof List<?> list)) {
            return List.of();
        }
        final List<String> result = new ArrayList<>(list.size());
        for (Object guid : list) {
            result.add(readGuid(guid));
        }
        return java.util.Collections.unmodifiableList(result);
    }

    /** Boolean member rendered as a signature token value. */
    String flagValue(final String alias, final Object source) {
        return String.valueOf(resolver.invoke(alias, source));
    }

    /** Enum member rendered by name; falls back to the value's string form. */
    static String enumName(final Object value) {
        return value instanceof Enum<?> enumeration ? enumeration.name() : String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // Object identity
    // ------------------------------------------------------------------

    String objectGuid(final Object source) {
        final Object guid = source == null ? null : resolver.invoke(SOURCE_GUID, source);
        return guid == null ? null : (String) resolver.invoke(GUID_UUID, guid);
    }

    String objectIdString(final Object source) {
        final Object id = source == null ? null : resolver.invoke(SOURCE_ID, source);
        return id == null ? null : (String) resolver.invoke(ID_STRING, id);
    }

    String objectLocalName(final Object source) {
        final Object name = source == null ? null : resolver.invoke(SOURCE_LOCAL_NAME, source);
        return name instanceof String text ? text : null;
    }

    boolean isDeformerSource(final Object object) {
        return resolver.isInstance(DEFORMER_CLASS, object);
    }

    boolean isWarpDeformer(final Object object) {
        return resolver.isInstance(WARP_CLASS, object);
    }

    boolean isRotationDeformer(final Object object) {
        return resolver.isInstance(ROTATION_CLASS, object);
    }

    boolean isArtMeshSource(final Object object) {
        return resolver.isInstance(ART_MESH_CLASS, object);
    }

    String deformerGuid(final Object deformerSource) {
        final Object guid = deformerSource == null ? null : resolver.invoke(DEFORMER_GUID, deformerSource);
        return guid == null ? null : (String) resolver.invoke(GUID_UUID, guid);
    }

    String deformerTargetGuid(final Object deformerSource) {
        final Object guid = deformerSource == null ? null : resolver.invoke(DEFORMER_TARGET, deformerSource);
        return guid == null ? null : (String) resolver.invoke(GUID_UUID, guid);
    }

    // ------------------------------------------------------------------
    // Extended-interpolation eligibility gate
    // ------------------------------------------------------------------

    boolean usesExtendedInterpolation(final Object modelSource) {
        // Both grid accessors can return the same instance; dedupe by identity so a
        // binding is only judged once. A binding with a non-LINEAR extended type or
        // the illegal flag counts as extended-interpolation usage.
        final java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object object : allObjects(modelSource)) {
            if (object == null) {
                continue;
            }
            for (String gridAlias : new String[] {SOURCE_GRID, SOURCE_EXT_GRID}) {
                final Object grid = resolver.invoke(gridAlias, object);
                if (grid == null || !seen.add(grid) || !resolver.isInstance(GRID_CLASS, grid)) {
                    continue;
                }
                for (Object binding : listOf(resolver.invoke(GRID_BINDINGS, grid))) {
                    if (binding == null || !resolver.isInstance(BINDING_CLASS, binding)) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(resolver.invoke(BINDING_ILLEGAL, binding))) {
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
     * family token; detected families are pass-through content pinned by the census,
     * never a rejection reason. The gate roster is the exact one the admitted record
     * verified: on 5.2.03 the three 5.3-only predicates are absent because the gated
     * features cannot exist in a 5.2 document.
     */
    List<String> modelFeatureFlags(final Object modelSource) {
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

    // ------------------------------------------------------------------
    // Embedded content families
    // ------------------------------------------------------------------

    /**
     * Extension classes that the editor installs on ordinary sources for every
     * model: texture inputs carry the atlas mapping, editable-mesh /
     * mesh-generator / warp-bezier / original-shape extensions carry standard
     * edit-time data. They are not feature carriers, so their presence alone
     * does not mark the source. Any other extension class (glue deform-path
     * skinning, art path, auto-yure, controller, rotate-3d, subdivision,
     * topology observer, extended interpolation, or an unknown future type)
     * is recorded as pass-through content the census pins.
     */
    private static final Set<String> STANDARD_EXTENSION_CLASSES = CubismExportHostProfile.STANDARD_EXTENSION_CLASSES;

    /**
     * Structures embedded inside a controllable source that never appear in the
     * object census: a keyform morph-target set with actual targets, an
     * extended morph-target set, or a non-standard extension object attached to
     * the source. Extension classes are named in the token so a census drift is
     * diagnosable; the tokens pin the content rather than reject it.
     */
    List<String> embeddedContentFamilies(final Object controllableSource) {
        if (controllableSource == null) {
            return List.of();
        }
        final List<String> detected = new ArrayList<>();
        final Object morphSet = resolver.invoke(SOURCE_MORPH_SET, controllableSource);
        if (morphSet != null
                && resolver.isInstance(MORPH_SET_CLASS, morphSet)
                && !listOf(resolver.invoke(MORPH_SET_TARGETS, morphSet)).isEmpty()) {
            detected.add("keyform-morph-target-set");
        }
        if (resolver.invoke(SOURCE_EXT_MORPH_SET, controllableSource) != null) {
            detected.add("extended-morph-target-set");
        }
        for (Object extension : listOf(resolver.invoke(SOURCE_EXTENSIONS, controllableSource))) {
            if (extension != null
                    && !STANDARD_EXTENSION_CLASSES.contains(extension.getClass().getName())) {
                detected.add("extension:" + extension.getClass().getName());
            }
        }
        return List.copyOf(detected);
    }

    /**
     * Family tokens for census objects, keyed by the reviewed model package
     * layout of the admitted builds. Deform-path skinning classes live under
     * the glue package, so the more specific prefix must be tested first.
     * Classes absent from an admitted build (aliases do not exist on 5.2.03)
     * simply never match there.
     */
    private static final List<Map.Entry<String, String>> FAMILY_PREFIXES = CubismExportHostProfile.FAMILY_PREFIXES;

    /**
     * Classifies a census object by its class-hierarchy package — a pure
     * {@code getClass()} read that never resolves host members. Unknown
     * families degrade to a bounded simple-name clue, never a stack of
     * internal detail.
     */
    String censusFamily(final Object object) {
        if (object == null) {
            return "unknown";
        }
        for (Class<?> type = object.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            final String name = type.getName();
            for (Map.Entry<String, String> family : FAMILY_PREFIXES) {
                if (name.startsWith(family.getKey())) {
                    return family.getValue();
                }
            }
        }
        final String clue = object.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]", "-");
        return "unknown:" + (clue.isBlank() ? "object" : clue.substring(0, Math.min(clue.length(), 48)));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static List<?> listOf(final Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : List.of();
    }
}
