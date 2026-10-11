package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.*;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorParameterInterpolationSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Native ArtMesh extended interpolation, with full source/cache recovery checks. */
final class EditorParameterInterpolationAccess {
    private static final String M = "cubism.editor-model.";
    private static final String S = "cubism.editor-history.semantic.";
    private static final long MAX_POSITION_SAMPLES = 32_000_000;
    private static final int MAX_GRID_FORMS = 4096;
    private final VerifiedMemberResolver resolver;

    EditorParameterInterpolationAccess(VerifiedMemberResolver resolver) {
        this.resolver = resolver;
    }

    ParameterInterpolation read(Object source, Object parameterGuid) {
        requireAuthorized();
        return settings(requireBinding(source, parameterGuid));
    }

    void write(
            Object source,
            Object modelSource,
            ArtMeshId id,
            Object parameterGuid,
            ParameterInterpolation requested,
            Runnable requireCurrent,
            EditorAuthoringTransactionCoordinator coordinator,
            EditorAuthoringTransactionCoordinator.Binding authoringBinding) {
        requireAuthorized();
        Objects.requireNonNull(requested, "settings");
        requireCurrent.run();
        Object binding = requireBinding(source, parameterGuid);
        if (requested.method() == ParameterInterpolation.Method.SNS_CURVE) {
            throw new UnsupportedOperationException("SNS curve authoring is not verified by this adapter.");
        }
        var before = snapshot(id, source);
        if (!before.source().morphKeys().isEmpty()) {
            throw new UnsupportedOperationException(
                    "Interpolation authoring for existing morph targets is not yet verified.");
        }
        Object parameterId = call("keyform-binding.parameter-guid", binding);
        String selectedGuid = guid(parameterId);
        long expectedGrid = expectedGrid(before.axes(), selectedGuid, requested);
        boolean expectedEnabled = before.axes().stream().anyMatch(axis -> {
            var setting = axis.guid().equals(selectedGuid) ? requested : axis.settings();
            return setting.method() != ParameterInterpolation.Method.LINEAR && setting.insertPointCount() > 0;
        });
        if (settings(binding).equals(requested)
                && (!expectedEnabled && before.cache().isEmpty()
                        || before.cache()
                                .filter(cache -> cache.enabled() == expectedEnabled
                                        && (!expectedEnabled || cache.gridForms() == expectedGrid))
                                .isPresent())) return;
        if (requested.method() != ParameterInterpolation.Method.LINEAR
                && list(call("keyform-binding.keys", binding)).size() < 3) {
            throw new IllegalArgumentException("Ellipse interpolation requires at least three source keys.");
        }
        int vertices = before.source().sourceGeometry().positions().size();
        if (expectedGrid > MAX_GRID_FORMS || Math.multiplyExact(expectedGrid, vertices) > MAX_POSITION_SAMPLES) {
            throw new IllegalArgumentException("Native interpolation cache exceeds the form/position budget.");
        }
        Supplier<Snapshot> reader = () -> {
            requireCurrent.run();
            return snapshot(id, source);
        };
        Object[] undo = new Object[1];
        Object[] preparedExtension = new Object[1];
        Object[] structuralUndo = new Object[1];
        Object app = resolver.invokeStatic(M + "app-controller.instance");
        coordinator.mutate(
                authoringBinding,
                new EditorUndoContribution(
                        "cubism.art-mesh.parameter-interpolation",
                        authoringBinding.modelIdentity() + ":art-mesh:" + id.value(),
                        "Turboism: Set Parameter Interpolation",
                        (edit, label) -> {
                            undo[0] = resolver.construct("cubism.editor-model.group-undo.create", label);
                            preparedExtension[0] = extension(source);
                            if (preparedExtension[0] == null) {
                                preparedExtension[0] =
                                        resolver.construct("cubism.editor-model.extended-interpolation.create", source);
                                if (!resolver.isInstance(
                                                "cubism.editor-model.extended-interpolation.class",
                                                preparedExtension[0])
                                        || call(
                                                        "cubism.editor-model.extended-interpolation.owner",
                                                        preparedExtension[0])
                                                != source) {
                                    throw new IllegalStateException("Invalid native interpolation extension.");
                                }
                                structuralUndo[0] = resolver.construct(
                                        "cubism.editor-model.undo-add-remove-extension.create",
                                        source,
                                        preparedExtension[0],
                                        -1,
                                        true);
                                resolver.invoke("cubism.texture-atlas.group-undo.add", undo[0], structuralUndo[0]);
                            }
                            Object allEdit = call(
                                    "parameter-controllable-handler.create-undo-for-all-edit",
                                    call("parameter-controllable-source.handler", source),
                                    label);
                            resolver.invoke("cubism.texture-atlas.group-undo.add", undo[0], allEdit);
                            if (!Boolean.TRUE.equals(call("undo.add", edit, undo[0], true))) {
                                throw new IllegalStateException("Cubism rejected interpolation Undo admission.");
                            }
                            var listener = resolver.createFunctionalProxy(M + "undo-listener.class", ignored -> {
                                rebuild(source);
                                return null;
                            });
                            if (Boolean.FALSE.equals(call("undo.add-listener", undo[0], listener))) {
                                throw new IllegalStateException("Cubism rejected interpolation Undo listener.");
                            }
                            if (coordinator.claimRootUndoListener(
                                    authoringBinding, edit, "parameter-interpolation.model-refresh")) {
                                var rootListener =
                                        resolver.createFunctionalProxy(M + "undo-listener.class", ignored -> {
                                            call("model-source.update-instances", modelSource);
                                            Object pack = call("app-controller.complete-pack", app);
                                            call("complete-pack.update-parameter", pack, true);
                                            call("complete-pack.repaint-canvas", pack, true);
                                            return null;
                                        });
                                if (Boolean.FALSE.equals(call("undo.add-listener", edit, rootListener))) {
                                    throw new IllegalStateException(
                                            "Cubism rejected interpolation root Undo listener.");
                                }
                            }
                        },
                        () -> {
                            if (!reader.get().equals(before))
                                throw new IllegalStateException("Interpolation source changed before mutation.");
                            // Admit the structural Undo before publishing the extension. forceRedo
                            // activates the native Undo flags, unlike calling addExtension directly.
                            if (structuralUndo[0] != null)
                                resolver.invoke("cubism.texture-atlas.undo.force-redo", structuralUndo[0]);
                            Object currentBinding = requireBinding(source, parameterGuid);
                            call(
                                    "cubism.editor-model.keyform-binding.set-extended-type",
                                    currentBinding,
                                    nativeType(requested.method()));
                            call(
                                    "cubism.editor-model.keyform-binding.set-insert-count",
                                    currentBinding,
                                    requested.insertPointCount());
                            call(
                                    "cubism.editor-model.keyform-binding.set-extended-scale",
                                    currentBinding,
                                    requested.angleScale());
                            rebuild(source);
                            // The applied predicate reads source-local banks and cache. The
                            // coordinator coalesces MODEL_INSTANCES at commit, including for
                            // standalone writes; refreshing here rebuilds earlier caches in
                            // a composed import once for every subsequent interpolation edit.
                        },
                        () -> {
                            Snapshot after = reader.get();
                            if (!before.source().equals(after.source())
                                    || !before.sourceOpacities().equals(after.sourceOpacities())
                                    || !before.otherExtensions().equals(after.otherExtensions())) return false;
                            List<Axis> expectedAxes = before.axes().stream()
                                    .map(axis -> axis.guid().equals(selectedGuid)
                                            ? new Axis(axis.guid(), axis.keys(), requested, axis.legacyType(), false)
                                            : axis)
                                    .toList();
                            if (!expectedAxes.equals(after.axes())
                                    || after.cache().isEmpty()) return false;
                            Cache cache = after.cache().orElseThrow();
                            boolean enabled = expectedAxes.stream()
                                    .anyMatch(a -> a.settings().method() != ParameterInterpolation.Method.LINEAR
                                            && a.settings().insertPointCount() > 0);
                            return cache.enabled() == enabled && (!enabled || cache.gridForms() == expectedGrid);
                        },
                        () -> {
                            requireCurrent.run();
                            if (undo[0] != null) call("undo.execute-undo", undo[0]);
                            call("model-source.update-instances", modelSource);
                        },
                        () -> {
                            if (coordinator.isCreatedTargetRestored(authoringBinding, source)) return true;
                            try {
                                return reader.get().equals(before);
                            } catch (RuntimeException unreadable) {
                                return false;
                            }
                        },
                        Set.of(
                                EditorRefreshRequirement.MODEL_INSTANCES,
                                EditorRefreshRequirement.PARAMETER_PALETTE,
                                EditorRefreshRequirement.PART_PALETTE,
                                EditorRefreshRequirement.CANVAS,
                                EditorRefreshRequirement.MARK_DIRTY)));
    }

    private record Axis(
            String guid, List<Float> keys, ParameterInterpolation settings, Object legacyType, boolean illegal) {}

    private record Cache(boolean enabled, long gridForms, String digest) {}

    private record Snapshot(
            ArtMeshAuthoringSnapshot source,
            List<Float> sourceOpacities,
            List<Axis> axes,
            List<Object> otherExtensions,
            Optional<Cache> cache) {}

    private Snapshot snapshot(ArtMeshId id, Object source) {
        var stored = new EditorArtMeshAuthoringReadAccess(resolver).read(id, source);
        Object ext = extension(source);
        List<Object> otherExtensions = new ArrayList<>(list(call("parameter-controllable-source.extensions", source)));
        if (ext != null) otherExtensions.remove(ext);
        List<Float> opacities = list(call("art-mesh-source.keyforms", source)).stream()
                .map(form -> number(call("drawable-form.opacity", form)))
                .toList();
        return new Snapshot(
                stored,
                opacities,
                axes(call("parameter-controllable.keyform-grid", source)),
                List.copyOf(otherExtensions),
                ext == null ? Optional.empty() : Optional.of(cache(ext, source, stored)));
    }

    private void rebuild(Object source) {
        Object extension = extension(source);
        if (extension != null) {
            call("cubism.editor-model.extended-interpolation.setup-forms", extension, true, true);
            call("cubism.editor-model.extended-interpolation.update-forms", extension, true);
        }
    }

    /**
     * Cached form UUIDs are allocated again by native setupFormStructure. Normalize
     * only those IDs to bank indices: every position, opacity, order, grid coordinate
     * and alias relation is still checked. User/source form IDs remain exact.
     */
    private Cache cache(Object extension, Object owner, ArtMeshAuthoringSnapshot stored) {
        if (call("cubism.editor-model.extended-interpolation.owner", extension) != owner)
            throw new IllegalStateException("Foreign interpolation owner.");
        Object enabled = call("cubism.editor-model.extended-interpolation.enabled", extension);
        if (!(enabled instanceof Boolean flag)) throw new IllegalStateException("Invalid interpolation enabled flag.");
        Object grid = call("cubism.editor-model.extended-interpolation.grid", extension);
        Hash hash = new Hash();
        long rows = 0, samples = 0;
        List<?> forms = list(call("cubism.editor-model.extended-interpolation.forms", extension));
        if (forms.size() > MAX_GRID_FORMS)
            throw new IllegalStateException("Stored interpolation cache exceeds budget.");
        Set<String> sourceIds = new HashSet<>(stored.forms().stream()
                .map(ArtMeshAuthoringSnapshot.Form::formGuid)
                .toList());
        Map<String, Integer> cacheIds = new HashMap<>();
        for (int i = 0; i < forms.size(); i++) {
            String id = text(call("form-guid.value", resolver.invoke(S + "form.guid", forms.get(i))));
            if (sourceIds.contains(id) || cacheIds.putIfAbsent(id, i) != null)
                throw new IllegalStateException("Duplicate cached/source form identity.");
        }
        Set<Integer> referencedCache = new HashSet<>();
        if (grid != null) {
            for (Axis axis : axes(grid)) put(hash, axis.toString());
            List<?> cells = list(call("cubism.editor-model.keyform-grid.rows", grid));
            rows = cells.size();
            if (rows > MAX_GRID_FORMS) throw new IllegalStateException("Stored interpolation grid exceeds budget.");
            for (Object row : cells) {
                String id = text(call("form-guid.value", call("cubism.editor-model.keyform-grid-row.guid", row)));
                Integer cached = cacheIds.get(id);
                if (cached != null) {
                    put(hash, "CACHE");
                    put(hash, cached);
                    referencedCache.add(cached);
                } else {
                    if (!sourceIds.contains(id))
                        throw new IllegalStateException("Interpolation grid refers to an unknown form.");
                    put(hash, "SOURCE");
                    put(hash, id);
                }
                Object access = resolver.invoke(S + "keyform-on-grid.access-key", row);
                for (Object coordinate : list(resolver.invoke(S + "keyform-access-key.coordinates", access))) {
                    Object binding = resolver.invoke(S + "key-on-parameter.binding", coordinate);
                    put(hash, guid(call("keyform-binding.parameter-guid", binding)));
                    put(hash, Float.floatToIntBits(number(resolver.invoke(S + "key-on-parameter.value", coordinate))));
                }
            }
        }
        if (referencedCache.size() != forms.size())
            throw new IllegalStateException("Unreferenced interpolation cache form.");
        for (Object form : forms) {
            Object data = call("art-mesh-form.positions", form);
            if (!(data instanceof float[] positions) || positions.length % 2 != 0)
                throw new IllegalStateException("Invalid cached positions.");
            if (positions.length / 2 != stored.sourceGeometry().positions().size())
                throw new IllegalStateException("Cached/source vertex counts differ.");
            samples = Math.addExact(samples, positions.length / 2);
            if (samples > MAX_POSITION_SAMPLES)
                throw new IllegalStateException("Stored interpolation positions exceed budget.");
            put(hash, positions.length);
            for (float value : positions) {
                if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite interpolation position.");
                put(hash, Float.floatToIntBits(value));
            }
            put(hash, (Integer) call("drawable-form.draw-order", form));
            put(hash, Float.floatToIntBits(number(call("drawable-form.opacity", form))));
        }
        put(hash, forms.size());
        return new Cache(flag, rows, hash.finish());
    }

    private List<Axis> axes(Object grid) {
        var axes = new ArrayList<Axis>();
        for (Object binding : list(call("keyform-grid.bindings", grid))) {
            axes.add(new Axis(
                    guid(call("keyform-binding.parameter-guid", binding)),
                    list(call("keyform-binding.keys", binding)).stream()
                            .map(EditorParameterInterpolationAccess::number)
                            .toList(),
                    settings(binding),
                    Objects.requireNonNull(call("cubism.editor-model.keyform-binding.legacy-type", binding)),
                    (Boolean) call("cubism.editor-model.keyform-binding.illegal-extended", binding)));
        }
        return List.copyOf(axes);
    }

    private static long expectedGrid(List<Axis> axes, String selectedGuid, ParameterInterpolation requested) {
        long forms = 1;
        for (Axis axis : axes) {
            ParameterInterpolation setting = axis.guid().equals(selectedGuid) ? requested : axis.settings();
            long count = axis.keys().size();
            if (setting.method() != ParameterInterpolation.Method.LINEAR)
                count = Math.addExact(count, Math.multiplyExact(count - 1, setting.insertPointCount()));
            forms = Math.multiplyExact(forms, count);
        }
        return forms;
    }

    private Object requireBinding(Object source, Object parameterGuid) {
        return Objects.requireNonNull(
                call("keyform-grid.find-binding", call("parameter-controllable.keyform-grid", source), parameterGuid),
                "The target is not bound to this parameter.");
    }

    private Object extension(Object source) {
        Object found = null;
        for (Object candidate : list(call("parameter-controllable-source.extensions", source))) {
            if (resolver.isInstance("cubism.editor-model.extended-interpolation.class", candidate)) {
                if (found != null) throw new IllegalStateException("Duplicate interpolation extensions.");
                found = candidate;
            }
        }
        return found;
    }

    private ParameterInterpolation settings(Object binding) {
        Object value = call("cubism.editor-model.keyform-binding.extended-type", binding);
        for (ParameterInterpolation.Method method : ParameterInterpolation.Method.values()) {
            if (value == nativeType(method))
                return new ParameterInterpolation(
                        method,
                        (Integer) call("cubism.editor-model.keyform-binding.insert-count", binding),
                        number(call("cubism.editor-model.keyform-binding.extended-scale", binding)));
        }
        throw new IllegalStateException("Unknown native interpolation type.");
    }

    private Object nativeType(ParameterInterpolation.Method method) {
        return resolver.readStaticField(
                switch (method) {
                    case LINEAR -> "cubism.editor-model.extended-type.linear";
                    case ELLIPSE -> "cubism.editor-model.extended-type.ellipse";
                    case SNS_CURVE -> "cubism.editor-model.extended-type.sns-curve";
                });
    }

    private void requireAuthorized() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Interpolation requires the Editor thread.");
        if (!resolver.isExactCubismVersion("5.2.03", "5.3.02", "5.3.03")
                || !resolver.authorizesFeature(
                        EditorParameterInterpolationSelectorContract.ADAPTER_SLICE_ID,
                        EditorParameterInterpolationSelectorContract.CAPABILITY_ID,
                        EditorParameterInterpolationSelectorContract.REQUIRED_ALIASES)) {
            throw new UnsupportedOperationException("Native interpolation requires exact verified 5.3.02 evidence.");
        }
    }

    private Object call(String alias, Object target, Object... arguments) {
        return resolver.invoke(alias.startsWith(M) ? alias : M + alias, target, arguments);
    }

    private String guid(Object guid) {
        return text(call("guid.value", guid));
    }

    private static String text(Object value) {
        if (value instanceof String text && !text.isBlank()) return text;
        throw new IllegalStateException("Missing identity.");
    }

    private static List<?> list(Object value) {
        if (value instanceof List<?> list) return List.copyOf(list);
        throw new IllegalStateException("Expected native list.");
    }

    private static float number(Object value) {
        if (value instanceof Float f && Float.isFinite(f)) return f;
        throw new IllegalStateException("Expected finite native float.");
    }

    private static void put(Hash hash, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        put(hash, bytes.length);
        hash.bytes(bytes);
    }

    private static void put(Hash hash, int value) {
        hash.integer(value);
    }

    private static final class Hash {
        final MessageDigest digest;
        final byte[] buffer = new byte[65536];
        int used;

        Hash() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }

        void integer(int value) {
            if (used > buffer.length - 4) flush();
            buffer[used++] = (byte) (value >>> 24);
            buffer[used++] = (byte) (value >>> 16);
            buffer[used++] = (byte) (value >>> 8);
            buffer[used++] = (byte) value;
        }

        void bytes(byte[] bytes) {
            flush();
            digest.update(bytes);
        }

        void flush() {
            digest.update(buffer, 0, used);
            used = 0;
        }

        String finish() {
            flush();
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
