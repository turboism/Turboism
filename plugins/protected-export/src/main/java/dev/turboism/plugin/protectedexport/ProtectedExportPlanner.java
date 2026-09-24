package dev.turboism.plugin.protectedexport;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.MorphTargets;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingTargetType;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Deterministic, read-only protected-export preflight. */
final class ProtectedExportPlanner {
    private static final String OBFUSCATED_NAME_PREFIX = "ArtMesh_";
    private static final String TARGET_ID_PREFIX = "@";
    private static final int INITIAL_HASH_LENGTH = 16;
    private static final int MAX_TARGET_ID_LENGTH = 63;
    private static final int MAX_HASH_PREFIX_LENGTH = MAX_TARGET_ID_LENGTH - TARGET_ID_PREFIX.length();
    private static final int MAX_HASH_LENGTH = 64;

    private final GuidHasher guidHasher;

    ProtectedExportPlanner() {
        this(ProtectedExportPlanner::sha256Hex);
    }

    /** Package-private seam for bounded collision tests; production uses SHA-256. */
    ProtectedExportPlanner(final GuidHasher guidHasher) {
        this.guidHasher = Objects.requireNonNull(guidHasher, "guidHasher");
    }

    ProtectedExportPlan plan(final CubismModel model) {
        Objects.requireNonNull(model, "model");
        try {
            return planSnapshot(model);
        } catch (IllegalArgumentException rejection) {
            throw rejection;
        } catch (RuntimeException unavailable) {
            throw invalid("model snapshot is unavailable or ambiguous");
        }
    }

    private ProtectedExportPlan planSnapshot(final CubismModel model) {
        final List<PartSnapshot> readParts = snapshotParts(snapshot(model.parts().all(), "Part"));
        final Map<PartId, PartSnapshot> partsById = uniqueParts(readParts);
        validatePartHierarchy(partsById);
        final List<PartSnapshot> parts = stablePartOrder(partsById);

        final List<ParameterSnapshot> readParameters = snapshotParameters(
            snapshot(model.parameters().all(), "Parameter")
        );
        final Map<ParameterId, ParameterSnapshot> parametersById = uniqueParameters(readParameters);
        final List<ParameterSnapshot> parameters = readParameters.stream()
            .sorted(Comparator.comparing(value -> value.id().value()))
            .toList();

        final List<DeformerSnapshot> readDeformers = snapshotDeformers(
            snapshot(model.deformers().all(), "deformer")
        );
        final Map<DeformerId, DeformerSnapshot> deformersById = uniqueDeformers(readDeformers);
        validateDeformerParents(readDeformers, deformersById, partsById);
        final List<DeformerId> deformerOrder = leafToRoot(readDeformers, deformersById);

        final List<DrawableSnapshot> readDrawables = snapshotDrawables(
            snapshot(model.drawables().all(), "ArtMesh")
        );
        final Map<ArtMeshId, DrawableSnapshot> drawablesById = uniqueDrawables(readDrawables);
        validateDrawableParents(readDrawables, deformersById, partsById);
        final List<DrawableSnapshot> drawables = readDrawables.stream()
            .sorted(Comparator.comparing(DrawableSnapshot::guid).thenComparing(value -> value.id().value()))
            .toList();

        final List<ProtectedExportPlan.GlueSnapshot> glueSnapshots =
            snapshotGlues(model, drawablesById, parametersById);
        validateParameterBindings(
            parameters,
            parametersById,
            readDeformers,
            readDrawables,
            deformersById,
            drawablesById
        );

        final Set<String> sourceIds = sourceStrings(parts, parameters, readDeformers, drawables);
        final Set<String> sourceNames = sourceNames(drawables);
        return new ProtectedExportPlan(
            deformerOrder,
            drawables.stream().map(DrawableSnapshot::id).toList(),
            parts.stream().map(PartSnapshot::id).toList(),
            parameters.stream().map(ParameterSnapshot::id).toList(),
            obfuscate(drawables, sourceIds, sourceNames),
            parts.stream().map(PartSnapshot::toPlanSnapshot).toList(),
            glueSnapshots
        );
    }

    private static List<PartSnapshot> snapshotParts(final List<Part> parts) {
        final List<PartSnapshot> result = new ArrayList<>();
        for (Part part : parts) {
            final PartId id = requireId(part.id(), "Part");
            final String name = text(part.name(), "Part name");
            final Optional<PartId> parentId = requiredOptional(part.parentId(), "Part parent");
            final List<PartId> childIds = snapshot(part.childIds(), "Part child");
            for (PartId childId : childIds) {
                requireId(childId, "Part child");
            }
            requireMorphTargetsReadable(part.morphTargets(), "Part");
            result.add(new PartSnapshot(id, name, parentId, childIds, finite(part.getOpacity(), "Part opacity")));
        }
        return List.copyOf(result);
    }

    private static List<ParameterSnapshot> snapshotParameters(final List<Parameter> parameters) {
        final List<ParameterSnapshot> result = new ArrayList<>();
        for (Parameter parameter : parameters) {
            final ParameterId id = requireId(parameter.id(), "Parameter");
            // Parameter type/combination are pass-through content: only the
            // reads themselves must succeed for the snapshot to be unambiguous.
            Objects.requireNonNull(parameter.type(), "Parameter type");
            final Optional<Boolean> combined = requiredOptional(
                parameter.combined(), "Parameter combination state"
            );
            final Optional<ParameterId> combinedWith = requiredOptional(
                parameter.combinedWith(), "Parameter combination partner"
            );
            if (combined.isEmpty()) {
                throw invalid("parameter combination state is unreadable");
            }
            result.add(new ParameterSnapshot(
                id,
                snapshot(parameter.getParameterBindings(), "Parameter binding")
            ));
        }
        return List.copyOf(result);
    }

    private static List<DeformerSnapshot> snapshotDeformers(final List<Deformer> deformers) {
        final List<DeformerSnapshot> result = new ArrayList<>();
        for (Deformer deformer : deformers) {
            final boolean warp = deformer instanceof WarpDeformer;
            final boolean rotation = deformer instanceof RotationDeformer;
            if (warp == rotation) {
                throw invalid("unsupported or ambiguous deformer type");
            }
            result.add(new DeformerSnapshot(
                requireId(deformer.id(), "deformer"),
                requiredOptional(deformer.parentPartId(), "deformer parent Part"),
                requiredOptional(deformer.parentDeformerId(), "deformer parent Deformer"),
                warp ? ParameterBindingTargetType.WARP_DEFORMER : ParameterBindingTargetType.ROTATION_DEFORMER,
                snapshot(deformer.getParameterBindings(), "deformer parameter binding")
            ));
        }
        return List.copyOf(result);
    }

    private static List<DrawableSnapshot> snapshotDrawables(final List<Drawable> drawables) {
        final List<DrawableSnapshot> result = new ArrayList<>();
        for (Drawable drawable : drawables) {
            final ArtMeshId id = requireId(drawable.id(), "ArtMesh");
            final String guid = text(drawable.guid(), "ArtMesh GUID");
            final String name = text(drawable.name(), "ArtMesh name");
            requireMorphTargetsReadable(drawable.morphTargets(), "ArtMesh");
            result.add(new DrawableSnapshot(
                id,
                guid,
                name,
                requiredOptional(drawable.parentPartId(), "ArtMesh parent Part"),
                requiredOptional(drawable.parentDeformerId(), "ArtMesh parent Deformer"),
                snapshot(drawable.getParameterBindings(), "ArtMesh parameter binding")
            ));
        }
        return List.copyOf(result);
    }

    /**
     * Morph targets are pass-through content: they are never transformed, so a
     * populated set is admitted. The snapshot must still be readable — an
     * unreadable surface means the model snapshot itself is ambiguous.
     */
    private static void requireMorphTargetsReadable(final MorphTargets morphTargets, final String label) {
        if (morphTargets == null) {
            throw invalid("missing " + label + " Morph Target snapshot");
        }
        snapshot(morphTargets.all(), label + " Morph Target");
    }

    /**
     * Glue is the explicit pass-through exception the user ruled for this slice:
     * each relation is admitted unchanged — never renamed, re-identified or
     * retargeted — but its identity is pinned in the plan and every reference must
     * resolve against the censused model. A Glue with a duplicate ID, a missing
     * ArtMesh target or an unknown Parameter binding is corrupt input, not a
     * pass-through case.
     */
    private static List<ProtectedExportPlan.GlueSnapshot> snapshotGlues(
        final CubismModel model,
        final Map<ArtMeshId, DrawableSnapshot> drawablesById,
        final Map<ParameterId, ParameterSnapshot> parametersById
    ) {
        final List<Glue> glues = snapshot(model.glues().all(), "Glue");
        final List<ProtectedExportPlan.GlueSnapshot> result = new ArrayList<>();
        final Set<GlueId> ids = new HashSet<>();
        for (Glue glue : glues) {
            final GlueId id = Objects.requireNonNull(glue.id(), "Glue ID");
            if (!ids.add(id)) {
                throw invalid("duplicate Glue ID " + id.value());
            }
            final ArtMeshId drawableA =
                Objects.requireNonNull(glue.drawableAId(), "Glue source target");
            final ArtMeshId drawableB =
                Objects.requireNonNull(glue.drawableBId(), "Glue destination target");
            if (!drawablesById.containsKey(drawableA)
                || !drawablesById.containsKey(drawableB)) {
                throw invalid("Glue references an unavailable ArtMesh");
            }
            final List<ParameterId> boundParameters =
                snapshot(glue.parameterIds(), "Glue parameter");
            for (ParameterId parameterId : boundParameters) {
                if (parameterId == null || !parametersById.containsKey(parameterId)) {
                    throw invalid("Glue references an unavailable Parameter");
                }
            }
            result.add(new ProtectedExportPlan.GlueSnapshot(
                id, drawableA, drawableB, boundParameters));
        }
        return List.copyOf(result);
    }

    private static void validateParameterBindings(
        final List<ParameterSnapshot> parameters,
        final Map<ParameterId, ParameterSnapshot> parametersById,
        final List<DeformerSnapshot> deformers,
        final List<DrawableSnapshot> drawables,
        final Map<DeformerId, DeformerSnapshot> deformersById,
        final Map<ArtMeshId, DrawableSnapshot> drawablesById
    ) {
        final Map<ParameterBindingKey, ParameterBinding> ownerBindings = new HashMap<>();
        for (DeformerSnapshot deformer : deformers) {
            validateOwnerBindings(
                deformer.bindings(), deformer.bindingTargetType(), deformer.id().value(), "deformer",
                parametersById, deformersById, drawablesById, ownerBindings
            );
        }
        for (DrawableSnapshot drawable : drawables) {
            validateOwnerBindings(
                drawable.bindings(), ParameterBindingTargetType.ART_MESH, drawable.id().value(), "ArtMesh",
                parametersById, deformersById, drawablesById, ownerBindings
            );
        }

        final Map<ParameterBindingKey, ParameterBinding> parameterBindings = new HashMap<>();
        for (ParameterSnapshot parameter : parameters) {
            for (ParameterBinding binding : parameter.bindings()) {
                validateBinding(binding, parametersById, deformersById, drawablesById);
                rejectDuplicateBinding(parameterBindings, binding);
                if (!binding.parameterId().equals(parameter.id())) {
                    throw invalid("Parameter binding is attached to the wrong Parameter");
                }
            }
        }
        if (!ownerBindings.equals(parameterBindings)) {
            throw invalid("parameter binding snapshots are inconsistent");
        }
    }

    private static void validateOwnerBindings(
        final List<ParameterBinding> bindings,
        final ParameterBindingTargetType expectedType,
        final String expectedId,
        final String label,
        final Map<ParameterId, ParameterSnapshot> parametersById,
        final Map<DeformerId, DeformerSnapshot> deformersById,
        final Map<ArtMeshId, DrawableSnapshot> drawablesById,
        final Map<ParameterBindingKey, ParameterBinding> allOwnerBindings
    ) {
        for (ParameterBinding binding : bindings) {
            validateBinding(binding, parametersById, deformersById, drawablesById);
            if (binding.target().type() != expectedType || !binding.target().id().equals(expectedId)) {
                throw invalid(label + " binding target is inconsistent with its owner");
            }
            rejectDuplicateBinding(allOwnerBindings, binding);
        }
    }

    private static void rejectDuplicateBinding(
        final Map<ParameterBindingKey, ParameterBinding> bindings,
        final ParameterBinding binding
    ) {
        if (bindings.putIfAbsent(ParameterBindingKey.from(binding), binding) != null) {
            throw invalid("duplicate parameter binding");
        }
    }

    private static void validateBinding(
        final ParameterBinding binding,
        final Map<ParameterId, ParameterSnapshot> parametersById,
        final Map<DeformerId, DeformerSnapshot> deformersById,
        final Map<ArtMeshId, DrawableSnapshot> drawablesById
    ) {
        if (binding == null || binding.family() != ParameterBindingFamily.KEYFORM_GRID) {
            throw invalid("unsupported or unknown parameter binding");
        }
        if (binding.points().stream().anyMatch(Objects::isNull)) {
            throw invalid("parameter binding contains an unknown point");
        }
        if (!parametersById.containsKey(binding.parameterId())) {
            throw invalid("parameter binding references a missing Parameter");
        }
        final String targetId = text(binding.target().id(), "parameter binding target ID");
        final boolean targetExists = switch (binding.target().type()) {
            case ART_MESH -> drawablesById.keySet().stream()
                .anyMatch(id -> id.value().equals(targetId));
            case WARP_DEFORMER -> deformersById.values().stream().anyMatch(deformer ->
                deformer.bindingTargetType() == ParameterBindingTargetType.WARP_DEFORMER
                    && deformer.id().value().equals(targetId)
            );
            case ROTATION_DEFORMER -> deformersById.values().stream().anyMatch(deformer ->
                deformer.bindingTargetType() == ParameterBindingTargetType.ROTATION_DEFORMER
                    && deformer.id().value().equals(targetId)
            );
        };
        if (!targetExists) {
            throw invalid("parameter binding references an unavailable or mismatched target");
        }
    }

    private static Map<PartId, PartSnapshot> uniqueParts(final List<PartSnapshot> parts) {
        final Map<PartId, PartSnapshot> result = new LinkedHashMap<>();
        for (PartSnapshot part : parts) {
            if (result.putIfAbsent(part.id(), part) != null) {
                throw invalid("duplicate Part ID " + part.id().value());
            }
        }
        return result;
    }

    private static Map<ParameterId, ParameterSnapshot> uniqueParameters(
        final List<ParameterSnapshot> parameters
    ) {
        final Map<ParameterId, ParameterSnapshot> result = new LinkedHashMap<>();
        for (ParameterSnapshot parameter : parameters) {
            if (result.putIfAbsent(parameter.id(), parameter) != null) {
                throw invalid("duplicate Parameter ID " + parameter.id().value());
            }
        }
        return result;
    }

    private static Map<DeformerId, DeformerSnapshot> uniqueDeformers(
        final List<DeformerSnapshot> deformers
    ) {
        final Map<DeformerId, DeformerSnapshot> result = new LinkedHashMap<>();
        for (DeformerSnapshot deformer : deformers) {
            if (result.putIfAbsent(deformer.id(), deformer) != null) {
                throw invalid("duplicate deformer ID " + deformer.id().value());
            }
        }
        return result;
    }

    private static Map<ArtMeshId, DrawableSnapshot> uniqueDrawables(
        final List<DrawableSnapshot> drawables
    ) {
        final Map<ArtMeshId, DrawableSnapshot> result = new LinkedHashMap<>();
        final Set<String> guids = new HashSet<>();
        for (DrawableSnapshot drawable : drawables) {
            if (result.putIfAbsent(drawable.id(), drawable) != null) {
                throw invalid("duplicate ArtMesh ID " + drawable.id().value());
            }
            if (!guids.add(drawable.guid())) {
                throw invalid("duplicate ArtMesh GUID");
            }
        }
        return result;
    }

    private static void validatePartHierarchy(final Map<PartId, PartSnapshot> parts) {
        for (PartSnapshot part : parts.values()) {
            final Optional<PartId> parent = part.parentId();
            if (parent.isPresent()) {
                final PartId parentId = parent.orElseThrow();
                final PartSnapshot parentPart = parts.get(parentId);
                if (parentPart == null) {
                    throw invalid("missing Part parent " + parentId.value());
                }
                final long reciprocalChildReferences = parentPart.childIds().stream()
                    .filter(part.id()::equals)
                    .count();
                if (reciprocalChildReferences != 1) {
                    throw invalid("inconsistent Part child reference");
                }
            }
            final Set<PartId> childIds = new HashSet<>();
            for (PartId childId : part.childIds()) {
                if (!childIds.add(requireId(childId, "Part child"))) {
                    throw invalid("duplicate Part child reference");
                }
                final PartSnapshot child = parts.get(childId);
                if (child == null) {
                    throw invalid("missing Part child " + childId.value());
                }
                if (!child.parentId().equals(Optional.of(part.id()))) {
                    throw invalid("inconsistent Part parent reference");
                }
            }
        }
        final Map<PartId, Integer> state = new HashMap<>();
        final List<PartId> stableIds = parts.keySet().stream()
            .sorted(Comparator.comparing(PartId::value))
            .toList();
        for (PartId start : stableIds) {
            final List<PartId> chain = new ArrayList<>();
            PartId cursor = start;
            while (true) {
                final int current = state.getOrDefault(cursor, 0);
                if (current == 2) {
                    break;
                }
                if (current == 1) {
                    throw invalid("Part hierarchy cycle at " + cursor.value());
                }
                state.put(cursor, 1);
                chain.add(cursor);
                final Optional<PartId> parent = parts.get(cursor).parentId();
                if (parent.isEmpty()) {
                    break;
                }
                cursor = parent.orElseThrow();
            }
            for (PartId visited : chain) {
                state.put(visited, 2);
            }
        }
    }

    private static List<PartSnapshot> stablePartOrder(final Map<PartId, PartSnapshot> parts) {
        final List<PartSnapshot> roots = parts.values().stream()
            .filter(value -> value.parentId().isEmpty())
            .sorted(Comparator.comparing(value -> value.id().value()))
            .toList();
        final List<PartSnapshot> starts = new ArrayList<>(roots);
        starts.addAll(parts.values().stream()
            .sorted(Comparator.comparing(value -> value.id().value()))
            .toList());
        final Set<PartId> emitted = new HashSet<>();
        final List<PartSnapshot> result = new ArrayList<>();
        for (PartSnapshot start : starts) {
            if (emitted.contains(start.id())) {
                continue;
            }
            final Deque<PartId> pending = new ArrayDeque<>();
            pending.push(start.id());
            while (!pending.isEmpty()) {
                final PartId id = pending.pop();
                if (!emitted.add(id)) {
                    continue;
                }
                final PartSnapshot part = parts.get(id);
                result.add(part);
                final List<PartId> children = part.childIds().stream()
                    .sorted(Comparator.comparing(PartId::value))
                    .toList();
                for (int index = children.size() - 1; index >= 0; index--) {
                    pending.push(children.get(index));
                }
            }
        }
        if (result.size() != parts.size()) {
            throw invalid("Part hierarchy is unavailable or ambiguous");
        }
        return List.copyOf(result);
    }

    private static void validateDeformerParents(
        final List<DeformerSnapshot> deformers,
        final Map<DeformerId, DeformerSnapshot> deformersById,
        final Map<PartId, PartSnapshot> partsById
    ) {
        for (DeformerSnapshot deformer : deformers) {
            if (deformer.parentPartId().isPresent()
                && !partsById.containsKey(deformer.parentPartId().orElseThrow())) {
                throw invalid("missing deformer Part parent " + deformer.parentPartId().orElseThrow().value());
            }
            if (deformer.parentDeformerId().isPresent()
                && !deformersById.containsKey(deformer.parentDeformerId().orElseThrow())) {
                throw invalid(
                    "missing deformer parent " + deformer.parentDeformerId().orElseThrow().value()
                );
            }
        }
    }

    private static void validateDrawableParents(
        final List<DrawableSnapshot> drawables,
        final Map<DeformerId, DeformerSnapshot> deformersById,
        final Map<PartId, PartSnapshot> partsById
    ) {
        for (DrawableSnapshot drawable : drawables) {
            if (drawable.parentPartId().isPresent()
                && !partsById.containsKey(drawable.parentPartId().orElseThrow())) {
                throw invalid("missing ArtMesh Part parent " + drawable.parentPartId().orElseThrow().value());
            }
            if (drawable.parentDeformerId().isPresent()
                && !deformersById.containsKey(drawable.parentDeformerId().orElseThrow())) {
                throw invalid(
                    "missing ArtMesh parent " + drawable.parentDeformerId().orElseThrow().value()
                );
            }
        }
    }

    private static List<DeformerId> leafToRoot(
        final List<DeformerSnapshot> deformers,
        final Map<DeformerId, DeformerSnapshot> deformersById
    ) {
        final Map<DeformerId, List<DeformerSnapshot>> children = new HashMap<>();
        for (DeformerSnapshot deformer : deformers) {
            deformer.parentDeformerId().ifPresent(parent ->
                children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(deformer)
            );
        }
        children.values().forEach(list -> list.sort(Comparator.comparing(value -> value.id().value())));
        final List<DeformerSnapshot> stable = deformers.stream()
            .sorted(Comparator.comparing(value -> value.id().value()))
            .toList();
        final Map<DeformerId, Integer> state = new HashMap<>();
        final List<DeformerId> result = new ArrayList<>();
        for (DeformerSnapshot deformer : stable) {
            if (deformer.parentDeformerId().isEmpty()) {
                visitDeformer(deformer, children, state, result);
            }
        }
        for (DeformerSnapshot deformer : stable) {
            visitDeformer(deformer, children, state, result);
        }
        if (result.size() != deformersById.size()) {
            throw invalid("deformer hierarchy is unavailable or ambiguous");
        }
        return List.copyOf(result);
    }

    private static void visitDeformer(
        final DeformerSnapshot start,
        final Map<DeformerId, List<DeformerSnapshot>> children,
        final Map<DeformerId, Integer> state,
        final List<DeformerId> result
    ) {
        final Deque<DeformerFrame> pending = new ArrayDeque<>();
        pending.push(new DeformerFrame(start, false));
        while (!pending.isEmpty()) {
            final DeformerFrame frame = pending.pop();
            final DeformerSnapshot deformer = frame.deformer();
            final DeformerId id = deformer.id();
            final int current = state.getOrDefault(id, 0);
            if (frame.expanded()) {
                if (current != 1) {
                    continue;
                }
                result.add(id);
                state.put(id, 2);
                continue;
            }
            if (current == 1) {
                throw invalid("deformer hierarchy cycle at " + id.value());
            }
            if (current == 2) {
                continue;
            }
            state.put(id, 1);
            pending.push(new DeformerFrame(deformer, true));
            final List<DeformerSnapshot> stableChildren = children.getOrDefault(id, List.of());
            for (int index = stableChildren.size() - 1; index >= 0; index--) {
                pending.push(new DeformerFrame(stableChildren.get(index), false));
            }
        }
    }

    private static Set<String> sourceStrings(
        final List<PartSnapshot> parts,
        final List<ParameterSnapshot> parameters,
        final List<DeformerSnapshot> deformers,
        final List<DrawableSnapshot> drawables
    ) {
        final Set<String> ids = new HashSet<>();
        parts.forEach(part -> ids.add(text(part.id().value(), "Part ID")));
        parameters.forEach(parameter -> ids.add(text(parameter.id().value(), "Parameter ID")));
        deformers.forEach(deformer -> ids.add(text(deformer.id().value(), "deformer ID")));
        drawables.forEach(drawable -> ids.add(text(drawable.id().value(), "ArtMesh ID")));
        return Set.copyOf(ids);
    }

    private static Set<String> sourceNames(final List<DrawableSnapshot> drawables) {
        return Set.copyOf(drawables.stream()
            .map(DrawableSnapshot::name)
            .toList());
    }

    private Map<ArtMeshId, ProtectedExportPlan.ArtMeshTarget> obfuscate(
        final List<DrawableSnapshot> drawables,
        final Set<String> reservedIds,
        final Set<String> reservedNames
    ) {
        final Map<ArtMeshId, ProtectedExportPlan.ArtMeshTarget> result = new LinkedHashMap<>();
        final Set<String> usedNames = new HashSet<>();
        final Set<String> hashes = new HashSet<>();
        final Set<String> usedIds = new HashSet<>();
        for (DrawableSnapshot drawable : drawables) {
            final String hash = hex(guidHasher.hash(drawable.guid()));
            if (!hashes.add(hash)) {
                throw invalid("ArtMesh GUID hash collision");
            }
            boolean allocated = false;
            for (int length = INITIAL_HASH_LENGTH;
                length <= Math.min(hash.length(), MAX_HASH_PREFIX_LENGTH); length++) {
                final String suffix = hash.substring(0, length);
                final String name = OBFUSCATED_NAME_PREFIX + suffix;
                final String idToken = TARGET_ID_PREFIX + suffix;
                if (reservedNames.contains(name) || reservedNames.contains(idToken)
                    || reservedIds.contains(name) || reservedIds.contains(idToken)
                    || usedNames.contains(name) || usedIds.contains(idToken)) {
                    continue;
                }
                final ProtectedExportPlan.ArtMeshTarget target =
                    new ProtectedExportPlan.ArtMeshTarget(name, idToken);
                result.put(drawable.id(), target);
                usedNames.add(name);
                usedIds.add(idToken);
                allocated = true;
                break;
            }
            if (!allocated) {
                throw invalid("ArtMesh GUID collision or target ID conflict");
            }
        }
        return result;
    }

    private static <T> List<T> snapshot(final List<T> values, final String label) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw invalid("missing " + label + " snapshot");
        }
        return List.copyOf(values);
    }

    private static <T> Optional<T> requiredOptional(final Optional<T> value, final String label) {
        if (value == null) {
            throw invalid("missing " + label);
        }
        return value;
    }

    private static <T> T requireId(final T id, final String label) {
        if (id == null) {
            throw invalid("missing " + label + " ID");
        }
        if (id instanceof PartId partId) {
            text(partId.value(), label + " ID");
        } else if (id instanceof ArtMeshId artMeshId) {
            text(artMeshId.value(), label + " ID");
        } else if (id instanceof DeformerId deformerId) {
            text(deformerId.value(), label + " ID");
        } else if (id instanceof ParameterId parameterId) {
            text(parameterId.value(), label + " ID");
        }
        return id;
    }

    private static float finite(final float value, final String label) {
        if (!Float.isFinite(value)) {
            throw invalid("invalid " + label);
        }
        return value;
    }

    private static String text(final String value, final String label) {
        if (value == null || value.isBlank()) {
            throw invalid("missing " + label);
        }
        return value;
    }

    private static IllegalArgumentException invalid(final String message) {
        return new IllegalArgumentException("Protected export preflight rejected: " + message);
    }

    @FunctionalInterface
    interface GuidHasher {
        String hash(String guid);
    }

    private static String sha256Hex(final String guid) {
        try {
            final byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(guid.getBytes(StandardCharsets.UTF_8));
            final StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(Character.forDigit((value >>> 4) & 0x0F, 16));
                result.append(Character.forDigit(value & 0x0F, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(final String value) {
        if (value == null || value.length() < INITIAL_HASH_LENGTH || value.length() > MAX_HASH_LENGTH
            || value.chars().anyMatch(character -> Character.digit(character, 16) < 0)) {
            throw invalid("ArtMesh GUID hash is unavailable");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private record PartSnapshot(
        PartId id,
        String name,
        Optional<PartId> parentId,
        List<PartId> childIds,
        float opacity
    ) {
        private PartSnapshot {
            id = requireId(id, "Part");
            name = text(name, "Part name");
            parentId = requiredOptional(parentId, "Part parent");
            childIds = List.copyOf(childIds);
            finite(opacity, "Part opacity");
        }

        private ProtectedExportPlan.PartSnapshot toPlanSnapshot() {
            return new ProtectedExportPlan.PartSnapshot(id, name, parentId, childIds, opacity);
        }
    }

    private record ParameterSnapshot(ParameterId id, List<ParameterBinding> bindings) {
        private ParameterSnapshot {
            id = requireId(id, "Parameter");
            bindings = List.copyOf(bindings);
        }
    }

    private record DeformerSnapshot(
        DeformerId id,
        Optional<PartId> parentPartId,
        Optional<DeformerId> parentDeformerId,
        ParameterBindingTargetType bindingTargetType,
        List<ParameterBinding> bindings
    ) {
        private DeformerSnapshot {
            id = requireId(id, "deformer");
            parentPartId = requiredOptional(parentPartId, "deformer parent Part");
            parentDeformerId = requiredOptional(parentDeformerId, "deformer parent Deformer");
            bindingTargetType = Objects.requireNonNull(bindingTargetType, "bindingTargetType");
            bindings = List.copyOf(bindings);
        }
    }

    private record DeformerFrame(DeformerSnapshot deformer, boolean expanded) {
    }

    private record DrawableSnapshot(
        ArtMeshId id,
        String guid,
        String name,
        Optional<PartId> parentPartId,
        Optional<DeformerId> parentDeformerId,
        List<ParameterBinding> bindings
    ) {
        private DrawableSnapshot {
            id = requireId(id, "ArtMesh");
            guid = text(guid, "ArtMesh GUID");
            name = text(name, "ArtMesh name");
            parentPartId = requiredOptional(parentPartId, "ArtMesh parent Part");
            parentDeformerId = requiredOptional(parentDeformerId, "ArtMesh parent Deformer");
            bindings = List.copyOf(bindings);
        }
    }

    private record ParameterBindingKey(
        dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
        ParameterId parameterId,
        ParameterBindingFamily family
    ) {
        private static ParameterBindingKey from(final ParameterBinding binding) {
            return new ParameterBindingKey(binding.target(), binding.parameterId(), binding.family());
        }
    }
}
