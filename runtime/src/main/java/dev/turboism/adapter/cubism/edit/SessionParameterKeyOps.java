package dev.turboism.adapter.cubism.edit;

import dev.turboism.mapping.verification.selector.EditorEditParameterKeySelectorContract;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.edit.ParameterKeyOps;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parameter-key (keyform) family routed through the session's verified member surface
 * (spec 046, T3): {@code AddParameterKey}, {@code DeleteParameterKey},
 * {@code MoveParameterKey}, {@code GetParameterKeys}, and {@code GetObjectsByParameterKeys}.
 *
 * <p>Each operation gates on its declared capability row and member set before touching the
 * host, then orchestrates the verified {@code KeyformGridSource} members exactly as the
 * official operation does: writes capture the object's {@code createUndoForAllEdit} payload on
 * the session edit token before mutating and finish with the refresh envelope.</p>
 *
 * <p>OPEN: {@code MoveParameterKey}'s {@code forceOverwrite} has no verified collision
 * semantics on the host ({@code rearrangeKeyformsOnParameter} behavior toward an existing
 * destination key is unverified) — requests carrying it fail closed until host verification
 * lands (T7). {@code DeleteParameterKey} loose matching deletes whole bindings matched by any
 * supplied filter, mirroring the official "任一匹配" shape; exact host confirmation is deferred
 * to T7.</p>
 */
final class SessionParameterKeyOps implements ParameterKeyOps {

    private final EditSessionOps ops;

    SessionParameterKeyOps(final EditSessionOps ops) {
        this.ops = Objects.requireNonNull(ops, "ops");
    }

    @Override
    public boolean addParameterKey(final AddParameterKey request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("AddParameterKey", access -> {
            ops.require(
                access,
                EditorEditParameterKeySelectorContract.ADD_PARAMETER_KEY_CAPABILITY_ID,
                EditorEditParameterKeySelectorContract.ADD_PARAMETER_KEY_REQUIRED_ALIASES,
                "AddParameterKey");
            final Object source = ops.requireObjectSource(access, request.object());
            final Object parameterSource =
                ops.requireParameterSource(access, request.parameter());
            final Object guid = ops.parameterGuid(access, parameterSource);
            final Object grid = ops.keyformGrid(access, source);
            ops.captureUndoForAllEdit(access, source, "Turboism: Add Parameter Key");
            access.invoke(
                "cubism.editor-model.keyform-grid.add-key",
                grid,
                Float.valueOf((float) request.keyValue()),
                guid);
            ops.finishWrite(access);
            return true;
        });
    }

    @Override
    public boolean deleteParameterKey(final DeleteParameterKey request)
            throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("DeleteParameterKey", access -> {
            ops.require(
                access,
                EditorEditParameterKeySelectorContract.DELETE_PARAMETER_KEY_CAPABILITY_ID,
                EditorEditParameterKeySelectorContract.DELETE_PARAMETER_KEY_REQUIRED_ALIASES,
                "DeleteParameterKey");
            final Object parameterSource = request.parameter()
                .map(id -> ops.requireParameterSource(access, id))
                .orElse(null);
            final Object parameterGuid = parameterSource == null
                ? null
                : ops.parameterGuid(access, parameterSource);
            final Object parameterSet = access.invoke(
                "cubism.editor-model.model.parameter-set", access.model());
            final List<Object> targets = targetObjects(access, request.object().orElse(null));
            boolean deleted = false;
            for (final Object source : targets) {
                final boolean objectMatches = request.object()
                    .map(ref -> ref.id().equals(ops.objectId(access, source)))
                    .orElse(true);
                if (!objectMatches) {
                    continue;
                }
                final Object grid = ops.keyformGrid(access, source);
                boolean captured = false;
                for (final Object binding : ops.list(
                    access.invoke("cubism.editor-model.keyform-grid.bindings", grid),
                    "Editor keyform bindings")) {
                    final Object bindingGuid = access.invoke(
                        "cubism.editor-model.keyform-binding.parameter-guid", binding);
                    final boolean parameterMatches = parameterGuid == null
                        || bindingGuid.equals(parameterGuid);
                    final List<Float> keys = ops.bindingKeys(access, binding);
                    final boolean valueMatches = request.keyValue()
                        .map(value -> contains(keys, value))
                        .orElse(false);
                    if (!bindingMatches(request, parameterMatches, valueMatches)) {
                        continue;
                    }
                    if (!captured) {
                        ops.captureUndoForAllEdit(
                            access, source, "Turboism: Delete Parameter Key");
                        captured = true;
                    }
                    if (request.keyValue().isPresent() && request.strict()) {
                        access.invoke(
                            "cubism.editor-model.keyform-grid.remove-key",
                            grid,
                            Float.valueOf(request.keyValue().get().floatValue()),
                            bindingGuid);
                    } else {
                        access.invoke(
                            "cubism.editor-model.keyform-grid.remove-all-key",
                            grid,
                            parameterSet,
                            bindingGuid);
                    }
                    deleted = true;
                }
            }
            if (deleted) {
                ops.finishWrite(access);
            }
            return deleted;
        });
    }

    @Override
    public boolean moveParameterKey(final MoveParameterKey request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        if (request.forceOverwrite()) {
            // OPEN: rearrangeKeyformsOnParameter's collision behavior toward an existing
            // destination key is unverified — the overwrite request fails closed until host
            // verification lands (T7).
            throw new EditUnavailableException(
                "cubism.edit.op-unverified",
                "MoveParameterKey forceOverwrite is not verified on this Cubism host");
        }
        return ops.dispatch("MoveParameterKey", access -> {
            ops.require(
                access,
                EditorEditParameterKeySelectorContract.MOVE_PARAMETER_KEY_CAPABILITY_ID,
                EditorEditParameterKeySelectorContract.MOVE_PARAMETER_KEY_REQUIRED_ALIASES,
                "MoveParameterKey");
            final Object parameterSource = request.parameter()
                .map(id -> ops.requireParameterSource(access, id))
                .orElse(null);
            final Object parameterGuid = parameterSource == null
                ? null
                : ops.parameterGuid(access, parameterSource);
            final List<Object> targets = targetObjects(access, request.object().orElse(null));
            boolean moved = false;
            for (final Object source : targets) {
                final Object grid = ops.keyformGrid(access, source);
                boolean captured = false;
                for (final Object binding : ops.list(
                    access.invoke("cubism.editor-model.keyform-grid.bindings", grid),
                    "Editor keyform bindings")) {
                    final Object bindingGuid = access.invoke(
                        "cubism.editor-model.keyform-binding.parameter-guid", binding);
                    if (parameterGuid != null && !bindingGuid.equals(parameterGuid)) {
                        continue;
                    }
                    final ArrayList<Float> before = new ArrayList<>(ops.bindingKeys(access, binding));
                    final int index = indexOf(before, request.fromValue());
                    if (index < 0) {
                        if (request.strict()) {
                            continue;
                        }
                        continue;
                    }
                    if (indexOf(before, request.toValue()) >= 0) {
                        // Destination already bound: only a verified overwrite could proceed.
                        continue;
                    }
                    final ArrayList<Float> after = new ArrayList<>(before);
                    after.set(index, Float.valueOf((float) request.toValue()));
                    after.sort(Float::compare);
                    before.sort(Float::compare);
                    if (!captured) {
                        ops.captureUndoForAllEdit(
                            access, source, "Turboism: Move Parameter Key");
                        captured = true;
                    }
                    access.invoke(
                        "cubism.editor-model.keyform-grid.rearrange-keys",
                        grid,
                        bindingGuid,
                        List.copyOf(before),
                        List.copyOf(after));
                    moved = true;
                }
            }
            if (moved) {
                ops.finishWrite(access);
            }
            return moved;
        });
    }

    @Override
    public List<ParameterKeyValues> parameterKeys(final GetParameterKeys request)
            throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("GetParameterKeys", access -> {
            ops.require(
                access,
                EditorEditParameterKeySelectorContract.GET_PARAMETER_KEYS_CAPABILITY_ID,
                EditorEditParameterKeySelectorContract.GET_PARAMETER_KEYS_REQUIRED_ALIASES,
                "GetParameterKeys");
            final Object source = ops.requireObjectSource(access, request.object());
            final Object grid = ops.keyformGrid(access, source);
            final ArrayList<ParameterKeyValues> result = new ArrayList<>();
            for (final Object binding : ops.list(
                access.invoke("cubism.editor-model.keyform-grid.bindings", grid),
                "Editor keyform bindings")) {
                final String parameterId = ops.idValue(
                    access,
                    access.invoke(
                        "cubism.editor-model.keyform-binding.parameter-id", binding));
                final ArrayList<Double> keys = new ArrayList<>();
                for (final Float key : ops.bindingKeys(access, binding)) {
                    keys.add(key.doubleValue());
                }
                result.add(new ParameterKeyValues(new ParameterId(parameterId), List.copyOf(keys)));
            }
            return List.copyOf(result);
        });
    }

    @Override
    public List<ModelObjectId> objectsByParameterKeys(
            final GetObjectsByParameterKeys request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("GetObjectsByParameterKeys", access -> {
            ops.require(
                access,
                EditorEditParameterKeySelectorContract.GET_OBJECTS_BY_PARAMETER_KEYS_CAPABILITY_ID,
                EditorEditParameterKeySelectorContract
                    .GET_OBJECTS_BY_PARAMETER_KEYS_REQUIRED_ALIASES,
                "GetObjectsByParameterKeys");
            final Object parameterSource =
                ops.requireParameterSource(access, request.parameter());
            final Object guid = ops.parameterGuid(access, parameterSource);
            final ArrayList<ModelObjectId> matches = new ArrayList<>();
            for (final Object source : ops.allObjectSources(access)) {
                final Object grid = ops.keyformGrid(access, source);
                final Object binding = ops.findBinding(access, grid, guid);
                if (binding == null) {
                    continue;
                }
                if (contains(ops.bindingKeys(access, binding), request.keyValue())) {
                    matches.add(new ModelObjectId(ops.objectId(access, source)));
                }
            }
            return List.copyOf(matches);
        });
    }

    // ------------------------------------------------------------------

    private List<Object> targetObjects(
        final EditSessionOpsAccess access,
        final dev.turboism.sdk.cubism.model.ModelObjectReference object
    ) {
        if (object == null) {
            return ops.allObjectSources(access);
        }
        return List.of(ops.requireObjectSource(access, object));
    }

    /**
     * Strict requests match conjunctively (parameter and value filters must both hold when
     * present); loose requests match a binding when any supplied filter hits — the official
     * "任一匹配" shape.
     */
    private boolean bindingMatches(
        final DeleteParameterKey request,
        final boolean parameterMatches,
        final boolean valueMatches
    ) {
        if (request.strict()) {
            return parameterMatches && (request.keyValue().isEmpty() || valueMatches);
        }
        return (request.parameter().isPresent() && parameterMatches)
            || (request.keyValue().isPresent() && valueMatches)
            || (request.parameter().isEmpty() && request.keyValue().isEmpty());
    }

    private static boolean contains(final List<Float> keys, final double value) {
        return indexOf(keys, value) >= 0;
    }

    private static int indexOf(final List<Float> keys, final double value) {
        for (int i = 0; i < keys.size(); i++) {
            if (Math.abs(keys.get(i) - value) < 1.0e-4) {
                return i;
            }
        }
        return -1;
    }
}
