package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ProtectedExportPlan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Identity census of one model source — captured before mutation, enforced after.
 *
 * <p>This is the fail-closed half of protected export that makes every other phase
 * comparable. {@link #censusModel} pins the identities a rewrite must preserve: parts,
 * parameters, ArtMeshes, the pass-through channel and physics/motion-sync settings,
 * keyed by stable host GUID, plus each member's embedded content signature and the
 * host-reported feature flags. {@link #verifyPostMutationCensus} then re-reads the
 * mutated copy and rejects any drift the plan does not authorize — a dropped part, a
 * re-parented pass-through object, a settings signature that moved or an ArtMesh that
 * does not carry exactly its planned token.</p>
 *
 * <p>Deliberately package-private and host-read only: the census never carries a host
 * object out of the package, never writes, and every unreadable identity rejects with a
 * bounded {@code protected-export.*} detail instead of riding through unseen. It is a
 * collaborator of {@link ProtectedExportOrchestrator}, not a second gate: the
 * orchestrator still owns the session, the phases and the terminal report.</p>
 */
final class ProtectedExportModelCensus {

    /**
     * Drift rejections carry the orchestrator's obfuscation-phase failure identity so a
     * post-mutation census failure and an obfuscation failure read the same to callers.
     */
    private static final String OBFUSCATE_FAILED_KEY = ProtectedExportOrchestrator.OBFUSCATE_FAILED_KEY;

    private final ProtectedExportHostOperations host;

    /** Binds the census to one host read surface; every snapshot is taken through it. */
    ProtectedExportModelCensus(final ProtectedExportHostOperations host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Part identity: stable GUID keys the map; value holds serialized identity,
     * membership, parent edge, clip references and host content flags.
     */
    record PartIdentity(
            String id,
            String name,
            List<String> childGuids,
            String targetDeformerGuid,
            List<String> referenceGuids,
            List<String> flags) {}

    /**
     * Parameter contract: evaluable range/default/repeat plus the authored
     * bound-key union — every binding position on every carrier. The union
     * exists for behavior-oracle sampling coverage; it is not the staged moc3
     * contract (that reads {@link #parameterExpectations} post-mutation)
     * and is excluded from post-mutation identity equality because flatten
     * legitimately rewrites binding-key disposition.
     */
    record ParameterIdentity(float min, float max, float defaultValue, Boolean repeat, List<Float> keys) {}

    /**
     * ArtMesh identity: serialized name and drawable ID under the stable GUID,
     * plus the clip-mask references and drawable flags obfuscation must leave
     * untouched.
     */
    record ArtMeshIdentity(String name, String drawableId, List<String> referenceGuids, List<String> flags) {}

    /**
     * One pass-through census member's pinned identity: family token, ID, local
     * name, parent edge, ordered reference GUIDs and host content flags. A
     * pass-through object is never mutated by this session; the pin exists so a
     * mid-run change of any of these facts — including a reference that stopped
     * resolving — fails closed. {@code targetDeformerGuid} is the only component
     * allowed to transition, and only to {@code null}: flatten re-roots objects
     * whose parent deformer was removed.
     */
    record PassThroughIdentity(
            String family,
            String id,
            String name,
            String targetDeformerGuid,
            List<String> referenceGuids,
            List<String> flags) {}

    /**
     * One settings object's pinned identity — a physics or motion-sync setting:
     * ID, name and an ordered behavior-content signature (every physics
     * input/output/vertex member; the motion-sync version plus mapping and
     * post-processing checksums). Obfuscation rewrites the ID and name to the
     * planned tokens; the signature must compare identical across it, and any
     * other drift fails closed.
     */
    record SettingsIdentity(String id, String name, List<String> signature) {}

    /**
     * Identity snapshot of one model source: parts, ArtMeshes and pass-through
     * members keyed by stable GUID, parameters keyed by ID, physics and
     * motion-sync settings keyed by stable GUID, the physics settings-set level
     * pin (gravity/wind/fps/selection), every census member's embedded
     * content signature, and the host-reported content feature flags.
     */
    record ModelCensus(
            Map<String, PartIdentity> parts,
            Map<String, ParameterIdentity> parameters,
            Map<String, ArtMeshIdentity> artMeshes,
            Map<String, PassThroughIdentity> passThrough,
            Map<String, SettingsIdentity> physicsSettings,
            Map<String, SettingsIdentity> motionSyncSettings,
            List<String> physicsSetSignature,
            Map<String, List<String>> embeddedContent,
            List<String> featureFlags) {}

    /**
     * Bounded family token for census diagnostics; degrades to a short
     * {@code unknown} form when the host cannot classify the member.
     */
    private String censusFamilyToken(final Object object) {
        if (object == null) {
            return "null";
        }
        String family;
        try {
            family = host.censusFamily(object);
        } catch (RuntimeException failure) {
            family = "unknown";
        }
        return family == null || family.isBlank() ? "unknown" : family;
    }

    /**
     * Snapshots part, parameter, ArtMesh, pass-through and settings identities of
     * {@code modelSource}. Parameter keys are the union of key positions across
     * every object's keyform bindings for that parameter — the evaluable surface
     * flatten must preserve. Any unreadable identity fails closed with
     * {@code failureKey}; a census member that cannot be pinned at all rejects
     * with a bounded {@code unpinnable-structure} detail.
     */
    ModelCensus censusModel(final Object modelSource, final String failureKey) {
        final Object root = host.rootPart(modelSource);
        final List<?> objects = host.allObjects(modelSource);

        // Every census member must carry a pinnable identity: controllable source
        // with a stable GUID and a nonblank ID. Anything else — including families
        // whose references cannot be enumerated — rejects before any mutation.
        final Map<String, Integer> unpinnable = new TreeMap<>();
        final Set<String> seenGuids = new LinkedHashSet<>();
        final Set<String> duplicateGuids = new LinkedHashSet<>();
        final Map<Object, String> guidOf = new IdentityHashMap<>();
        for (Object object : objects) {
            final String family = censusFamilyToken(object);
            final boolean pinnableFamily = object != null
                    && (host.isWarpDeformer(object)
                            || host.isRotationDeformer(object)
                            || host.isArtMeshSource(object)
                            || host.isPartSource(object)
                            || "glue".equals(family)
                            || "art-path".equals(family)
                            || "alias".equals(family));
            String guid = null;
            if (pinnableFamily && host.isControllableSource(object)) {
                try {
                    guid = host.objectGuid(object);
                    final String id = host.objectIdString(object);
                    if (guid == null || guid.isBlank() || id == null || id.isBlank()) {
                        guid = null;
                    }
                } catch (RuntimeException unreadable) {
                    guid = null;
                }
            }
            if (guid == null) {
                final String key = unpinnable.size() < 12 || unpinnable.containsKey(family) ? family : "other";
                unpinnable.merge(key, 1, Integer::sum);
                continue;
            }
            guidOf.put(object, guid);
            if (!seenGuids.add(guid)) {
                duplicateGuids.add(guid);
            }
        }
        if (!unpinnable.isEmpty()) {
            final StringBuilder detail = new StringBuilder("protected-export.unpinnable-structure:");
            for (Map.Entry<String, Integer> entry : unpinnable.entrySet()) {
                detail.append(entry.getKey())
                        .append('=')
                        .append(entry.getValue())
                        .append(',');
            }
            detail.setLength(detail.length() - 1);
            throw new SessionRejection(failureKey, detail.toString());
        }
        if (!duplicateGuids.isEmpty()) {
            throw new SessionRejection(
                    failureKey, "protected-export.duplicate-guid:" + String.join(",", new TreeSet<>(duplicateGuids)));
        }

        final Map<String, PartIdentity> parts = new LinkedHashMap<>();
        for (Object part : host.allParts(modelSource)) {
            if (part == null || part == root) {
                continue;
            }
            if (!host.isPartSource(part)) {
                throw new SessionRejection(failureKey);
            }
            final String guid = guidOf.getOrDefault(part, host.objectGuid(part));
            final String id = host.objectIdString(part);
            if (guid == null || guid.isBlank() || id == null || id.isBlank()) {
                throw new SessionRejection(failureKey);
            }
            if (parts.put(
                            guid,
                            new PartIdentity(
                                    id,
                                    host.objectLocalName(part),
                                    List.copyOf(host.partChildGuids(part)),
                                    host.sourceTargetDeformerGuid(part),
                                    host.passThroughReferenceGuids(part),
                                    host.passThroughFlagSignature(part)))
                    != null) {
                throw new SessionRejection(failureKey);
            }
        }
        final Map<String, TreeSet<Float>> keyUnion = new LinkedHashMap<>();
        for (Object object : objects) {
            for (Object binding : host.keyformBindings(object)) {
                final String parameterId = host.keyformBindingParameterId(binding);
                if (parameterId == null || parameterId.isBlank()) {
                    continue;
                }
                keyUnion.computeIfAbsent(parameterId, id -> new TreeSet<>()).addAll(host.keyformBindingKeys(binding));
            }
        }
        final Map<String, ParameterIdentity> parameters = new LinkedHashMap<>();
        for (Object parameter : host.allParameters(modelSource)) {
            final String id = host.parameterSourceIdString(parameter);
            final Float min = host.parameterSourceMinValue(parameter);
            final Float max = host.parameterSourceMaxValue(parameter);
            final Float def = host.parameterSourceDefaultValue(parameter);
            if (id == null || id.isBlank() || min == null || max == null || def == null) {
                throw new SessionRejection(failureKey);
            }
            final TreeSet<Float> keys = keyUnion.get(id);
            final ParameterIdentity identity = new ParameterIdentity(
                    min, max, def, host.parameterSourceRepeat(parameter), keys == null ? List.of() : List.copyOf(keys));
            final ParameterIdentity prior = parameters.put(id, identity);
            if (prior != null && !prior.equals(identity)) {
                // Duplicate parameter IDs with diverging contracts are ambiguous.
                throw new SessionRejection(failureKey);
            }
        }
        final Map<String, ArtMeshIdentity> artMeshes = new LinkedHashMap<>();
        for (Object mesh : host.allArtMeshes(modelSource)) {
            final String guid = host.objectGuid(mesh);
            final String drawableId = host.drawableIdString(mesh);
            if (guid == null || guid.isBlank() || drawableId == null) {
                throw new SessionRejection(failureKey);
            }
            if (artMeshes.put(
                            guid,
                            new ArtMeshIdentity(
                                    host.objectLocalName(mesh),
                                    drawableId,
                                    host.passThroughReferenceGuids(mesh),
                                    host.passThroughFlagSignature(mesh)))
                    != null) {
                throw new SessionRejection(failureKey);
            }
        }
        // Pass-through channel: every censused member outside the
        // flatten/obfuscate surface is pinned by identity, parent edge,
        // references and flags so flatten/obfuscation must leave it unchanged.
        // A Glue target that does not resolve to a censused ArtMesh is corrupt
        // input, not a pass-through case.
        final Map<String, PassThroughIdentity> passThrough = new LinkedHashMap<>();
        final Map<String, List<String>> embedded = new LinkedHashMap<>();
        for (Object object : objects) {
            final String guid = guidOf.get(object);
            if (guid == null) {
                continue; // unpinnable members already rejected above
            }
            embedded.put(guid, List.copyOf(host.embeddedContentFamilies(object)));
            if (host.isWarpDeformer(object)
                    || host.isRotationDeformer(object)
                    || host.isArtMeshSource(object)
                    || host.isPartSource(object)) {
                continue;
            }
            final List<String> references = new ArrayList<>(host.passThroughReferenceGuids(object));
            if ("glue".equals(censusFamilyToken(object))) {
                for (String targetGuid : references) {
                    if (targetGuid == null || !artMeshes.containsKey(targetGuid)) {
                        throw new SessionRejection(failureKey, "glue-reference-drift");
                    }
                }
            }
            passThrough.put(
                    guid,
                    new PassThroughIdentity(
                            censusFamilyToken(object),
                            host.objectIdString(object),
                            host.objectLocalName(object),
                            host.sourceTargetDeformerGuid(object),
                            Collections.unmodifiableList(references),
                            host.passThroughFlagSignature(object)));
        }

        // Physics and motion-sync settings live outside the object census. Each
        // must carry a pinnable identity — anything else rejects rather than
        // pass through untracked.
        final Map<String, SettingsIdentity> physicsSettings =
                settingsCensus(modelSource, host.allPhysicsSettings(modelSource), failureKey);
        final Map<String, SettingsIdentity> motionSyncSettings =
                settingsCensus(modelSource, host.allMotionSyncSettings(modelSource), failureKey);
        final List<String> physicsSet = host.physicsSettingsSetSignature(modelSource);
        if (physicsSet == null) {
            throw new SessionRejection(failureKey, "protected-export.unpinnable-settings:set");
        }
        final List<String> featureFlags = new ArrayList<>(host.modelFeatureFlags(modelSource));
        Collections.sort(featureFlags);
        // Settings maps stay in the host's list order (settingsCensus returns
        // an unmodifiable insertion-ordered map) — physics3.json enumerates
        // settings in that order, so positional artifact IDs map by index.
        return new ModelCensus(
                Map.copyOf(parts),
                Map.copyOf(parameters),
                Map.copyOf(artMeshes),
                Map.copyOf(passThrough),
                physicsSettings,
                motionSyncSettings,
                List.copyOf(physicsSet),
                Map.copyOf(embedded),
                List.copyOf(featureFlags));
    }

    /**
     * Identity+signature pin for one settings list. Every entry must be a
     * recognized settings family with a stable GUID and ID — an object that
     * cannot be pinned rejects the session rather than riding through unseen.
     */
    private Map<String, SettingsIdentity> settingsCensus(
            final Object modelSource, final List<?> settings, final String failureKey) {
        final Map<String, SettingsIdentity> pinned = new LinkedHashMap<>();
        for (Object setting : settings) {
            final String guid = host.settingsGuid(setting);
            final String id = host.settingsIdString(setting);
            final List<String> signature = host.settingsSignature(modelSource, setting);
            if (guid == null || guid.isBlank() || id == null || id.isBlank() || signature == null) {
                throw new SessionRejection(
                        failureKey, "protected-export.unpinnable-settings:" + settingsFamilyToken(setting));
            }
            if (pinned.put(guid, new SettingsIdentity(id, host.settingsName(setting), List.copyOf(signature)))
                    != null) {
                throw new SessionRejection(failureKey, "protected-export.unpinnable-settings:duplicate-guid");
            }
        }
        // Insertion order is the host's settings-list order — the staged
        // physics3.json enumerates settings in exactly that order, so the
        // artifact check maps positional IDs to expectations by index.
        return Collections.unmodifiableMap(new LinkedHashMap<>(pinned));
    }

    /** Bounded family token for an unrecognized settings object. */
    private String settingsFamilyToken(final Object setting) {
        if (host.isPhysicsSettingsSource(setting)) {
            return "physics";
        }
        if (host.isMotionSyncSettingSource(setting)) {
            return "motion-sync";
        }
        return "unknown";
    }

    /** Expected staged parameter contracts from the pre-mutation census. */
    Map<String, ProtectedExportStaging.ParameterExpectation> parameterExpectations(final ModelCensus census) {
        final Map<String, ProtectedExportStaging.ParameterExpectation> expectations = new LinkedHashMap<>();
        for (Map.Entry<String, ParameterIdentity> entry : census.parameters().entrySet()) {
            final ParameterIdentity identity = entry.getValue();
            expectations.put(
                    entry.getKey(),
                    new ProtectedExportStaging.ParameterExpectation(
                            entry.getKey(),
                            identity.min(),
                            identity.max(),
                            identity.defaultValue(),
                            identity.repeat(),
                            identity.keys()));
        }
        return Map.copyOf(expectations);
    }

    /** Serialized part ID set from the census (synthetic root excluded). */
    Set<String> partIdSet(final ModelCensus census) {
        final Set<String> ids = new LinkedHashSet<>();
        for (PartIdentity part : census.parts().values()) {
            ids.add(part.id());
        }
        return Set.copyOf(ids);
    }

    /** Glue ID set from the census — the staged output must carry them verbatim. */
    Set<String> glueIdSet(final ModelCensus census) {
        final Set<String> ids = new LinkedHashSet<>();
        for (PassThroughIdentity member : census.passThrough().values()) {
            if ("glue".equals(member.family())) {
                ids.add(member.id());
            }
        }
        return Set.copyOf(ids);
    }

    /**
     * Staged physics expectations in the host's settings-list order — each
     * entry pairs the planned token identity with the post-mutation behavior
     * signature the staged {@code physics3.json} must reproduce verbatim.
     */
    List<ProtectedExportStaging.PhysicsSettingExpectation> physicsExpectations(
            final ModelCensus census, final ProtectedExportPlan plan) {
        final List<ProtectedExportStaging.PhysicsSettingExpectation> expectations = new ArrayList<>();
        for (Map.Entry<String, SettingsIdentity> entry :
                census.physicsSettings().entrySet()) {
            final ProtectedExportPlan.Target target = plan.physicsSettings().get(entry.getKey());
            if (target == null) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "settings-identity-drift");
            }
            expectations.add(new ProtectedExportStaging.PhysicsSettingExpectation(
                    target.idToken(), target.name(), entry.getValue().signature()));
        }
        return List.copyOf(expectations);
    }

    /**
     * Post-mutation census enforcement: parts and parameters must equal the bound
     * snapshot — flatten may only remove planned deformer GUIDs from part
     * membership and re-root pass-through parent edges to {@code null}; ArtMeshes
     * must carry exactly their planned obfuscated identity; every pass-through
     * object, settings entry and embedded signature must be exactly what the bound
     * snapshot recorded. The host content feature flags are pinned by snapshot
     * equality at bind instead: they derive from deformer-owned content that
     * flatten legitimately consumes, so a flag may legitimately disappear here.
     */
    void verifyPostMutationCensus(final ModelCensus before, final ModelCensus after, final ProtectedExportPlan plan) {
        if (before == null) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY);
        }
        // Parameter keys are deliberately excluded from this drift check: the
        // authored bound-key union legitimately rewrites under flatten — keys
        // bound on consumed deformers vanish, positions may bake into mesh
        // grids. Range/default/repeat stay exact; staged key positions are
        // asserted against the post-mutation census at validation, and binding
        // correctness is the behavior oracle's contract.
        if (!before.parameters().keySet().equals(after.parameters().keySet())) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY, "parameter-identity-drift");
        }
        for (Map.Entry<String, ParameterIdentity> entry : before.parameters().entrySet()) {
            final ParameterIdentity prior = entry.getValue();
            final ParameterIdentity current = after.parameters().get(entry.getKey());
            if (Float.compare(prior.min(), current.min()) != 0
                    || Float.compare(prior.max(), current.max()) != 0
                    || Float.compare(prior.defaultValue(), current.defaultValue()) != 0
                    || !Objects.equals(prior.repeat(), current.repeat())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "parameter-identity-drift");
            }
        }
        if (!before.parts().keySet().equals(after.parts().keySet())) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY, "part-identity-drift");
        }
        final Set<String> flattened = new LinkedHashSet<>(plan.deformerOrderLeafToRoot());
        for (Map.Entry<String, PartIdentity> entry : before.parts().entrySet()) {
            final PartIdentity prior = entry.getValue();
            final PartIdentity current = after.parts().get(entry.getKey());
            if (!prior.id().equals(current.id()) || !Objects.equals(prior.name(), current.name())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "part-identity-drift");
            }
            final List<String> priorChildren = new ArrayList<>(prior.childGuids());
            priorChildren.removeAll(flattened);
            if (!priorChildren.equals(current.childGuids())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "part-hierarchy-drift");
            }
            if (!Objects.equals(prior.targetDeformerGuid(), current.targetDeformerGuid())
                    && current.targetDeformerGuid() != null) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "part-parent-drift");
            }
            if (!prior.referenceGuids().equals(current.referenceGuids())
                    || !prior.flags().equals(current.flags())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "part-reference-drift");
            }
        }
        if (!before.artMeshes().keySet().equals(after.artMeshes().keySet())) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY, "artmesh-identity-drift");
        }
        for (Map.Entry<String, ArtMeshIdentity> entry : after.artMeshes().entrySet()) {
            final ProtectedExportPlan.Target target = plan.artMeshTargets().get(entry.getKey());
            final ArtMeshIdentity current = entry.getValue();
            if (target == null
                    || !target.name().equals(current.name())
                    || !target.idToken().equals(current.drawableId())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "artmesh-identity-drift");
            }
            final ArtMeshIdentity prior = before.artMeshes().get(entry.getKey());
            if (prior != null
                    && (!prior.referenceGuids().equals(current.referenceGuids())
                            || !prior.flags().equals(current.flags()))) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "artmesh-reference-drift");
            }
        }
        // The pass-through channel is untouched: every member's identity, name,
        // references and flags must be exactly what the bound copy started with.
        // Only the parent edge may change — and only to null, the re-rooting a
        // removed parent deformer produces.
        if (!before.passThrough().keySet().equals(after.passThrough().keySet())) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY, "pass-through-identity-drift");
        }
        for (Map.Entry<String, PassThroughIdentity> entry : before.passThrough().entrySet()) {
            final PassThroughIdentity prior = entry.getValue();
            final PassThroughIdentity current = after.passThrough().get(entry.getKey());
            if (!prior.family().equals(current.family())
                    || !prior.id().equals(current.id())
                    || !Objects.equals(prior.name(), current.name())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "pass-through-identity-drift");
            }
            if (!Objects.equals(prior.targetDeformerGuid(), current.targetDeformerGuid())
                    && current.targetDeformerGuid() != null) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "pass-through-parent-drift");
            }
            if (!prior.referenceGuids().equals(current.referenceGuids())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "pass-through-reference-drift");
            }
            if (!prior.flags().equals(current.flags())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "pass-through-flag-drift");
            }
        }
        // Settings carry their planned obfuscated identity after obfuscation:
        // name and ID must equal the plan exactly, and the behavior signature —
        // every physics value and reference — must equal the bound snapshot.
        verifySettingsDrift(before.physicsSettings(), after.physicsSettings(), plan.physicsSettings());
        verifySettingsDrift(before.motionSyncSettings(), after.motionSyncSettings(), plan.motionSyncSettings());
        if (!before.physicsSetSignature().equals(after.physicsSetSignature())) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY, "settings-identity-drift");
        }
        // The aggregate contain* flag set is pinned at bind (copy must equal the
        // original) but cannot be asserted post-mutation: the flags derive from
        // deformer-owned content that flatten legitimately consumes, so a flag
        // may disappear or appear as a flatten effect rather than a defect.
        final Map<String, List<String>> survivingEmbedded = new LinkedHashMap<>(before.embeddedContent());
        flattened.forEach(survivingEmbedded::remove);
        for (Map.Entry<String, List<String>> entry : survivingEmbedded.entrySet()) {
            final List<String> current = after.embeddedContent().get(entry.getKey());
            if (current == null) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "embedded-content-drift");
            }
            // Flatten merges deformer keyform content into its target meshes, so
            // a surviving ArtMesh may legitimately gain embedded entries; it must
            // never lose one. Untouched members (parts, pass-through) stay exact.
            if (before.artMeshes().containsKey(entry.getKey())) {
                if (!current.containsAll(entry.getValue())) {
                    throw new SessionRejection(OBFUSCATE_FAILED_KEY, "embedded-content-drift");
                }
            } else if (!entry.getValue().equals(current)) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "embedded-content-drift");
            }
        }
    }

    /**
     * Settings drift rule after obfuscation: identical GUID sets, identical
     * behavior signatures, and name/ID equal to the planned tokens — anything
     * else is a hard rejection.
     */
    private static void verifySettingsDrift(
            final Map<String, SettingsIdentity> before,
            final Map<String, SettingsIdentity> after,
            final Map<String, ProtectedExportPlan.Target> planned) {
        if (!before.keySet().equals(after.keySet()) || !before.keySet().equals(planned.keySet())) {
            throw new SessionRejection(OBFUSCATE_FAILED_KEY, "settings-identity-drift");
        }
        for (Map.Entry<String, SettingsIdentity> entry : after.entrySet()) {
            final SettingsIdentity prior = before.get(entry.getKey());
            final SettingsIdentity current = entry.getValue();
            final ProtectedExportPlan.Target target = planned.get(entry.getKey());
            if (!prior.signature().equals(current.signature())
                    || !target.name().equals(current.name())
                    || !target.idToken().equals(current.id())) {
                throw new SessionRejection(OBFUSCATE_FAILED_KEY, "settings-identity-drift");
            }
        }
    }
}
