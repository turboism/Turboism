package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpWriteResults.*;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Existing model-image binding with full source CAS and an owned native transaction. */
final class McpImageBindingDomain {
    static final String READ = "turboism.assets.binding.read",
            BIND = "turboism.assets.binding.bind",
            RESULT = "turboism.assets.binding.result";
    static final int MAX_IMAGES = 1024, MAX_BYTES = 240 * 1024, MAX_OPERATIONS = 256;
    private final CubismFacade cubism;
    private final McpExecutionBridge execution;
    private final McpAuthoringSnapshotStore snapshots;
    private final Supplier<McpStoredAuthoringDomain.Context> context;
    private final Map<Key, Operation> operations = new HashMap<>();

    McpImageBindingDomain(
            CubismFacade cubism,
            McpExecutionBridge execution,
            McpAuthoringSnapshotStore snapshots,
            Supplier<McpStoredAuthoringDomain.Context> context) {
        this.cubism = Objects.requireNonNull(cubism);
        this.execution = Objects.requireNonNull(execution);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.context = Objects.requireNonNull(context);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        return McpToolCatalog.of(List.of(
                McpRegisteredTool.typed(
                        McpImageBindingSchemas.definition(READ),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        McpVersionSupport.exact(
                                "cubism.editor-model.texture.read",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "texture_input_observation",
                                                McpOperationEffect.READ,
                                                false,
                                                McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                                versions,
                                                "Full authoring/parent/history CAS with bounded registered image candidates and exact authored texture input; not pixels or GPU sampling."))),
                        execution,
                        this::read),
                McpRegisteredTool.typed(
                        McpImageBindingSchemas.definition(BIND),
                        McpOperationEffect.TRANSACTION_CONTROL,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        McpVersionSupport.exact(
                                "cubism.editor-model.texture.write",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "existing_model_image_binding",
                                                McpOperationEffect.TRANSACTION_CONTROL,
                                                false,
                                                McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
                                                versions,
                                                "Single existing model image and ArtMesh, full stored/native/binding/catalog CAS, own SDK transaction and exact readback. Runtime bind/Undo/permission/extension admission is authoritative; no real-host acceptance."))),
                        execution,
                        this::bind),
                McpRegisteredTool.typed(
                        McpImageBindingSchemas.definition(RESULT),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::result)));
    }

    private Map<String, Object> read(Map<String, Object> args) {
        try {
            fields(args, "documentId", "modelId", "objectId", "authoringSnapshotId");
            var current = context.get();
            scope(args, current);
            String session = McpRequestRegistry.currentSessionId();
            var expected = snapshots.get(text(args.get("authoringSnapshotId"), 128), session);
            if (!expected.kind().equals("art_mesh")
                    || !expected.objectId().equals(text(args.get("objectId"), 4096))
                    || !expected.binding().equals(current.binding())) throw new Stale();
            text(expected.sourceGuid(), 4096);
            verifyNative(current, expected);
            var state = observe(current, expected.objectId());
            verify(current, expected, state, true);
            var row = new LinkedHashMap<>(state);
            row.put("kind", "image_binding_state");
            var parent = expected.rows().stream()
                    .filter(r -> "parent_space".equals(r.get("kind")))
                    .findFirst()
                    .orElseThrow(Stale::new);
            int budget = Math.addExact(expected.encodedBytes(), bytes(row));
            if (budget > McpAuthoringSnapshotStore.MAX_ENTRY_BYTES) throw new Budget();
            var output = new LinkedHashMap<String, Object>();
            output.put("ok", true);
            output.put("objectId", expected.objectId());
            output.put("sourceGuid", expected.sourceGuid());
            output.put("state", current.binding());
            output.put("binding", state);
            // Reserve space for the issued UUID before retaining a snapshot; over-budget reads retain nothing.
            output.put("snapshotId", "00000000-0000-0000-0000-000000000000");
            if (bytes(output) > MAX_BYTES) throw new Budget();
            var issued = snapshots.put(
                    session,
                    current.binding(),
                    "art_mesh_image_binding",
                    expected.objectId(),
                    expected.sourceGuid(),
                    expected.nativeSnapshot(),
                    List.of(parent, Collections.unmodifiableMap(row)),
                    budget,
                    expected.snapshotId());
            output.put("snapshotId", issued.snapshotId());
            return envelope(Collections.unmodifiableMap(output));
        } catch (RuntimeException failure) {
            return envelope(Map.of("ok", false, "error", code(failure)));
        }
    }

    private Map<String, Object> bind(Map<String, Object> args) {
        String id = "";
        Key key = null;
        boolean reserved = false;
        String beforeHash = null, afterHash = null;
        try {
            fields(args, "documentId", "modelId", "objectId", "snapshotId", "operationId", "modelImageId");
            id = text(args.get("operationId"), 128);
            key = new Key(McpRequestRegistry.currentSessionId(), id);
            String fingerprint = digest(args);
            synchronized (operations) {
                var old = operations.get(key);
                if (old != null)
                    return envelope(
                            old.fingerprint.equals(fingerprint)
                                    ? old.output
                                    : output(
                                            id,
                                            "NOT_APPLIED",
                                            "OPERATION_ID_CONFLICT",
                                            null,
                                            null,
                                            null,
                                            null,
                                            false,
                                            null));
            }
            var current = context.get();
            scope(args, current);
            var expected = snapshots.get(text(args.get("snapshotId"), 128), key.session);
            if (!expected.kind().equals("art_mesh_image_binding")
                    || !expected.objectId().equals(text(args.get("objectId"), 4096))
                    || !expected.binding().equals(current.binding())) throw new Stale();
            String image = text(args.get("modelImageId"), 4096);
            var before = state(expected);
            if (images(before).stream().noneMatch(r -> image.equals(r.get("id")))) throw new IllegalArgumentException();
            if (Boolean.TRUE.equals(before.get("locked")) || parent(expected).get("parentDeformerId") != null)
                throw new UnsupportedOperationException();
            var beforeInput = input(before.get("textureInput"));
            if (beforeInput != null
                    && !("MODEL_IMAGE".equals(beforeInput.get("kind"))
                            && image.equals(beforeInput.get("modelImageId"))))
                throw new UnsupportedOperationException();
            verify(current, expected, before, true);
            beforeHash = digest(identityState(expected, beforeInput));
            afterHash = digest(identityState(expected, Map.of("kind", "MODEL_IMAGE", "modelImageId", image)));
            var transactions = cubism.authoringTransactions();
            synchronized (operations) {
                if (operations.size() >= MAX_OPERATIONS)
                    return envelope(
                            output(id, "NOT_APPLIED", "RESULT_STORE_FULL", null, null, null, null, false, null));
                operations.put(
                        key,
                        new Operation(
                                fingerprint,
                                output(id, "RUNNING", "RUNNING", null, null, beforeHash, afterHash, false, null)));
                reserved = true;
            }
            var result = transactions.execute(AuthoringTransactionOptions.of("MCP bind existing model image"), () -> {
                verify(current, expected, before, true);
                Map<String, Object> after = before;
                if (beforeInput == null) {
                    var handle = current.model()
                            .textures()
                            .bindModelImage(new ModelImageId(image), new ArtMeshId(expected.objectId()));
                    if (handle == null
                            || !handle.modelId()
                                    .value()
                                    .equals(current.binding().get("modelId"))
                            || !handle.modelImageId().value().equals(image)
                            || !handle.artMeshId().equals(Optional.of(new ArtMeshId(expected.objectId()))))
                        throw new Stale();
                    var actual = new ArtMeshTextureInput(
                            new ArtMeshId(expected.objectId()),
                            handle.modelId(),
                            handle.modelGeneration(),
                            ArtMeshTextureInput.Kind.MODEL_IMAGE,
                            Optional.of(new ModelImageId(image)),
                            Optional.empty());
                    after = withInput(before, project(actual));
                }
                verify(current, expected, after, false);
                return after;
            });
            String outcome =
                    switch (result.outcome()) {
                        case COMMITTED -> "APPLIED";
                        case NO_CHANGE -> "NO_CHANGE";
                        case ROLLED_BACK -> "ROLLED_BACK";
                        case RECOVERY_FAILED -> "OUTCOME_UNKNOWN";
                        default -> "NOT_APPLIED";
                    };
            boolean verified = false;
            Map<String, Object> finalInput = null;
            if (result.successful())
                try {
                    var after = result.value().orElseThrow(Stale::new);
                    verify(current, expected, after, false);
                    finalInput = input(after.get("textureInput"));
                    verified = true;
                } catch (RuntimeException failure) {
                    outcome = "APPLIED_WITH_READBACK_WARNING";
                }
            var output = output(
                    id,
                    outcome,
                    result.outcome().name(),
                    result.receipt().map(McpWriteResults::receipt).orElse(null),
                    result.diagnosticId().orElse(null),
                    beforeHash,
                    afterHash,
                    verified,
                    finalInput);
            finish(key, output);
            if (result.successful()) snapshots.invalidate(expected.snapshotId());
            return envelope(output);
        } catch (RuntimeException failure) {
            var output = output(
                    id,
                    reserved ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                    reserved ? "HOST_OUTCOME_UNKNOWN" : code(failure),
                    null,
                    null,
                    beforeHash,
                    afterHash,
                    false,
                    null);
            if (reserved) finish(key, output);
            return envelope(output);
        }
    }

    /** UI-thread preflight used by the asynchronous, authorized PNG registration workflow. */
    RegistrationTarget prepareRegistration(Map<String, Object> args) {
        var current = context.get();
        scope(args, current);
        var expected = snapshots.get(text(args.get("snapshotId"), 128), McpRequestRegistry.currentSessionId());
        if (!expected.kind().equals("art_mesh_image_binding")
                || !expected.objectId().equals(text(args.get("objectId"), 4096))
                || !expected.binding().equals(current.binding())) throw new Stale();
        var before = state(expected);
        if (before.get("textureInput") != null
                || Boolean.TRUE.equals(before.get("locked"))
                || parent(expected).get("parentDeformerId") != null) throw new UnsupportedOperationException();
        if (images(before).size() >= MAX_IMAGES || ((Number) before.get("groupCount")).intValue() >= MAX_IMAGES)
            throw new Budget();
        verify(current, expected, before, true);
        var raw = rawImages(current.model());
        if (raw.size() >= MAX_IMAGES) throw new Budget();
        verify(current, expected, before, true);
        if (!raw.equals(rawImages(current.model()))) throw new Stale();
        return new RegistrationTarget(current, expected, before, raw);
    }

    /** Own SDK transaction; admission callback is the final cooperative cancellation boundary. */
    Map<String, Object> registerPrepared(
            RegistrationTarget target, AuthorizedImageContent image, String operationId, Runnable admission) {
        boolean admitted = false;
        String beforeHash = null, afterHash = null;
        String rawId = null;
        try {
            var current = target.context();
            var expected = target.expected();
            if (!snapshots
                    .get(expected.snapshotId(), McpRequestRegistry.currentSessionId())
                    .equals(expected)) throw new Stale();
            verifyRegistration(target, target.before(), target.rawImages(), true);
            beforeHash = digest(Map.of(
                    "sourceGuid", expected.sourceGuid(), "binding", target.before(), "rawImages", target.rawImages()));
            var transactions = cubism.authoringTransactions();
            admission.run();
            admitted = true;
            var result =
                    transactions.execute(AuthoringTransactionOptions.of("MCP register and bind authorized PNG"), () -> {
                        verifyRegistration(target, target.before(), target.rawImages(), true);
                        var handle = current.model()
                                .textures()
                                .registerImageAndBind(image, new ArtMeshId(expected.objectId()));
                        if (handle == null
                                || !handle.modelId()
                                        .value()
                                        .equals(current.binding().get("modelId"))
                                || !handle.artMeshId().equals(Optional.of(new ArtMeshId(expected.objectId()))))
                            throw new Stale();
                        String modelImage = text(handle.modelImageId().value(), 4096),
                                rawImage = text(handle.rawImageId().value(), 4096);
                        if (images(target.before()).stream().anyMatch(r -> modelImage.equals(r.get("id")))
                                || target.rawImages().stream().anyMatch(r -> rawImage.equals(r.get("id"))))
                            throw new Stale();
                        var after = new LinkedHashMap<>(target.before());
                        var expectedImages = new ArrayList<>(images(target.before()));
                        expectedImages.add(Map.of(
                                "id",
                                modelImage,
                                "name",
                                image.sourceDescription(),
                                "width",
                                image.width(),
                                "height",
                                image.height(),
                                "groupIndex",
                                target.before().get("groupCount")));
                        after.put("images", List.copyOf(expectedImages));
                        after.put("groupCount", ((Number) target.before().get("groupCount")).intValue() + 1);
                        after.put(
                                "textureInput",
                                project(new ArtMeshTextureInput(
                                        new ArtMeshId(expected.objectId()),
                                        handle.modelId(),
                                        handle.modelGeneration(),
                                        ArtMeshTextureInput.Kind.MODEL_IMAGE,
                                        Optional.of(handle.modelImageId()),
                                        Optional.empty())));
                        var expectedRaw = new ArrayList<>(target.rawImages());
                        expectedRaw.add(Map.of(
                                "id",
                                rawImage,
                                "name",
                                image.sourceDescription(),
                                "width",
                                image.width(),
                                "height",
                                image.height()));
                        var stableAfter = Collections.unmodifiableMap(after);
                        var stableRaw = List.copyOf(expectedRaw);
                        verifyRegistration(target, stableAfter, stableRaw, false);
                        return new Registered(stableAfter, stableRaw, rawImage);
                    });
            String outcome =
                    switch (result.outcome()) {
                        case COMMITTED -> "APPLIED";
                        case NO_CHANGE -> "NO_CHANGE";
                        case ROLLED_BACK -> "ROLLED_BACK";
                        case RECOVERY_FAILED -> "OUTCOME_UNKNOWN";
                        default -> "NOT_APPLIED";
                    };
            boolean verified = false;
            Map<String, Object> finalInput = null;
            if (result.successful())
                try {
                    var actual = result.value().orElseThrow(Stale::new);
                    rawId = actual.rawImageId();
                    afterHash = digest(Map.of(
                            "sourceGuid",
                            expected.sourceGuid(),
                            "binding",
                            actual.binding(),
                            "rawImages",
                            actual.rawImages()));
                    verifyRegistration(target, actual.binding(), actual.rawImages(), false);
                    finalInput = input(actual.binding().get("textureInput"));
                    verified = true;
                } catch (RuntimeException failure) {
                    outcome = "APPLIED_WITH_READBACK_WARNING";
                }
            var output = new LinkedHashMap<>(output(
                    operationId,
                    outcome,
                    result.outcome().name(),
                    result.receipt().map(McpWriteResults::receipt).orElse(null),
                    result.diagnosticId().orElse(null),
                    beforeHash,
                    afterHash,
                    verified,
                    finalInput));
            output.put("rawImageId", rawId);
            if (result.successful()) snapshots.invalidate(expected.snapshotId());
            return Collections.unmodifiableMap(output);
        } catch (RuntimeException failure) {
            var output = new LinkedHashMap<>(output(
                    operationId,
                    admitted ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                    admitted ? "HOST_OUTCOME_UNKNOWN" : registrationError(failure),
                    null,
                    null,
                    beforeHash,
                    afterHash,
                    false,
                    null));
            output.put("rawImageId", rawId);
            return Collections.unmodifiableMap(output);
        }
    }

    private void verifyRegistration(
            RegistrationTarget target,
            Map<String, Object> binding,
            List<Map<String, Object>> raw,
            boolean exactHistory) {
        verify(target.context(), target.expected(), binding, exactHistory);
        if (!raw.equals(rawImages(target.context().model()))) throw new Stale();
        verify(target.context(), target.expected(), binding, exactHistory);
    }

    private static List<Map<String, Object>> rawImages(CubismModel model) {
        var values = model.textures().rawImages();
        if (values.size() > MAX_IMAGES) throw new Budget();
        var result = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        for (var raw : values) {
            String id = text(raw.id().value(), 4096), name = raw.name();
            if (!seen.add(id) || name == null || name.length() > 4096 || raw.width() <= 0 || raw.height() <= 0)
                throw new IllegalArgumentException();
            result.add(Map.of("id", id, "name", name, "width", raw.width(), "height", raw.height()));
        }
        if (bytes(Map.of("rawImages", result)) > MAX_BYTES) throw new Budget();
        return List.copyOf(result);
    }

    static String registrationError(RuntimeException failure) {
        return failure instanceof java.util.concurrent.CancellationException ? "CANCELLED_BEFORE_HOST" : code(failure);
    }

    record RegistrationTarget(
            McpStoredAuthoringDomain.Context context,
            McpAuthoringSnapshotStore.Entry expected,
            Map<String, Object> before,
            List<Map<String, Object>> rawImages) {}

    private record Registered(Map<String, Object> binding, List<Map<String, Object>> rawImages, String rawImageId) {}

    private Map<String, Object> result(Map<String, Object> args) {
        fields(args, "operationId");
        String id = text(args.get("operationId"), 128);
        synchronized (operations) {
            var op = operations.get(new Key(McpRequestRegistry.currentSessionId(), id));
            return envelope(
                    op == null
                            ? output(id, "OUTCOME_UNKNOWN", "NOT_FOUND", null, null, null, null, false, null)
                            : op.output);
        }
    }

    private void finish(Key key, Map<String, Object> output) {
        synchronized (operations) {
            var old = operations.get(key);
            operations.put(key, new Operation(old.fingerprint, output));
        }
    }

    private void verify(
            McpStoredAuthoringDomain.Context initial,
            McpAuthoringSnapshotStore.Entry expected,
            Map<String, Object> state,
            boolean exactHistory) {
        var now = context.get();
        if (exactHistory ? !initial.binding().equals(now.binding()) : !sameScope(initial.binding(), now.binding()))
            throw new Stale();
        verifyNative(now, expected);
        if (!state.equals(observe(now, expected.objectId()))) throw new Stale();
        verifyNative(now, expected);
        if (!now.binding().equals(context.get().binding())) throw new Stale();
    }

    private void verifyNative(McpStoredAuthoringDomain.Context current, McpAuthoringSnapshotStore.Entry expected) {
        var mesh = current.model().drawables().find(new ArtMeshId(expected.objectId()));
        if (!expected.nativeSnapshot().equals(mesh.authoringSnapshot())) throw new Stale();
        var parent = parent(expected);
        if (!Objects.equals(
                        parent.get("parentPartId"),
                        mesh.parentPartId().map(PartId::value).orElse(null))
                || !Objects.equals(
                        parent.get("parentDeformerId"),
                        mesh.parentDeformerId().map(DeformerId::value).orElse(null))) throw new Stale();
        if (!current.binding().equals(context.get().binding())) throw new Stale();
    }

    private static Map<String, Object> observe(McpStoredAuthoringDomain.Context current, String id) {
        var textures = current.model().textures();
        var rows = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        var groups = textures.modelImageGroups();
        if (groups.size() > MAX_IMAGES) throw new Budget();
        for (int g = 0; g < groups.size(); g++) {
            var images = groups.get(g).modelImages();
            if ((long) rows.size() + images.size() > MAX_IMAGES) throw new Budget();
            for (var entry : images) {
                String image = text(entry.id().value(), 4096), name = entry.name();
                if (name == null
                        || name.length() > 4096
                        || entry.width() <= 0
                        || entry.height() <= 0
                        || !seen.add(image)) throw new IllegalArgumentException();
                rows.add(Map.of(
                        "id", image, "name", name, "width", entry.width(), "height", entry.height(), "groupIndex", g));
            }
        }
        var bound = textures.textureInput(new ArtMeshId(id));
        Map<String, Object> projected = null;
        if (bound.isPresent()) {
            var value = bound.get();
            if (!value.artMeshId().value().equals(id)
                    || !value.modelId().value().equals(current.binding().get("modelId"))) throw new Stale();
            projected = project(value);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("groupCount", groups.size());
        result.put("locked", current.model().drawables().find(new ArtMeshId(id)).locked());
        result.put("textureInput", projected);
        result.put("images", List.copyOf(rows));
        if (bytes(result) > MAX_BYTES) throw new Budget();
        return Collections.unmodifiableMap(result);
    }

    static Map<String, Object> project(ArtMeshTextureInput value) {
        var row = new LinkedHashMap<String, Object>();
        row.put("artMeshId", text(value.artMeshId().value(), 4096));
        row.put("modelId", text(value.modelId().value(), 4096));
        row.put("modelGeneration", value.modelGeneration());
        row.put("kind", value.kind().name());
        row.put(
                "modelImageId",
                value.modelImageId().map(v -> text(v.value(), 4096)).orElse(null));
        row.put(
                "textureAtlasId",
                value.textureAtlasId().map(v -> text(v.value(), 4096)).orElse(null));
        return Collections.unmodifiableMap(row);
    }

    private static Map<String, Object> identityState(
            McpAuthoringSnapshotStore.Entry expected, Map<String, Object> value) {
        var row = new LinkedHashMap<String, Object>();
        row.put("sourceGuid", expected.sourceGuid());
        row.put("documentId", expected.binding().get("documentId"));
        row.put("modelId", expected.binding().get("modelId"));
        row.put("artMeshId", expected.objectId());
        var identity = new LinkedHashMap<String, Object>();
        if (value != null) {
            identity.put("kind", value.get("kind"));
            identity.put("modelImageId", value.get("modelImageId"));
            identity.put("textureAtlasId", value.get("textureAtlasId"));
        }
        row.put("inputIdentity", value == null ? null : identity);
        return row;
    }

    private static Map<String, Object> withInput(Map<String, Object> before, Map<String, Object> value) {
        var result = new LinkedHashMap<>(before);
        result.put("textureInput", value);
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> input(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> images(Map<String, Object> state) {
        return (List<Map<String, Object>>) state.get("images");
    }

    private static Map<String, Object> state(McpAuthoringSnapshotStore.Entry expected) {
        var result = new LinkedHashMap<>(expected.rows().stream()
                .filter(r -> "image_binding_state".equals(r.get("kind")))
                .findFirst()
                .orElseThrow(Stale::new));
        result.remove("kind");
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> parent(McpAuthoringSnapshotStore.Entry expected) {
        return expected.rows().stream()
                .filter(r -> "parent_space".equals(r.get("kind")))
                .findFirst()
                .orElseThrow(Stale::new);
    }

    private static void scope(Map<String, Object> args, McpStoredAuthoringDomain.Context current) {
        if (!text(args.get("documentId"), 4096).equals(current.binding().get("documentId"))
                || !text(args.get("modelId"), 4096).equals(current.binding().get("modelId"))) throw new Stale();
    }

    private static boolean sameScope(Map<String, Object> a, Map<String, Object> b) {
        return List.of("documentId", "modelId", "historyGeneration", "historyDocumentBinding", "historyManagerBinding")
                .stream()
                .allMatch(k -> Objects.equals(a.get(k), b.get(k)));
    }

    private static Map<String, Object> output(
            String id,
            String outcome,
            String code,
            Map<String, Object> receipt,
            String diagnostic,
            String before,
            String after,
            boolean verified,
            Map<String, Object> input) {
        var result = new LinkedHashMap<>(out(id, outcome, code, receipt, diagnostic, before, after, verified));
        result.put("textureInput", input);
        return Collections.unmodifiableMap(result);
    }

    private static void fields(Map<String, Object> args, String... names) {
        if (!args.keySet().equals(Set.of(names))) throw new IllegalArgumentException();
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return s;
    }

    private static int bytes(Map<String, Object> value) {
        return Json.stringify(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String code(RuntimeException e) {
        if (e instanceof Stale || e instanceof McpStoredAuthoringDomain.Stale) return "STALE_STATE";
        if (e instanceof McpStoredAuthoringDomain.NoModel) return "NO_ACTIVE_MODEL";
        if (e instanceof NoSuchElementException) return "NOT_FOUND";
        if (e instanceof Budget) return "BUDGET_EXCEEDED";
        if (e instanceof SecurityException || e instanceof dev.turboism.sdk.permission.CubismPermissionException)
            return "PERMISSION_DENIED";
        if (e instanceof UnsupportedOperationException
                || e instanceof dev.turboism.sdk.cubism.CubismEditorApiUnavailableException) return "UNAVAILABLE";
        if (e instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        return "READ_FAILED";
    }

    private record Key(String session, String id) {}

    private record Operation(String fingerprint, Map<String, Object> output) {}

    private static final class Stale extends RuntimeException {}

    private static final class Budget extends RuntimeException {}
}
