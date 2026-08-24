package dev.turboism.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.graal.GraalHostManager;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.script.ScriptDescriptor;

import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Permission-checked JSON bridge from one script execution to the existing Turboism SDK. */
final class RuntimeScriptHostBridge implements GraalHostManager.HostCallHandler {

    private static final long UI_TIMEOUT_SECONDS = 15L;
    private static final int MAX_BATCH_ITEMS = 256;

    private final ObjectMapper mapper = new ObjectMapper();
    private final PluginContext context;
    private final Set<String> scriptPermissions;
    private final long uiTimeout;
    private final TimeUnit uiTimeoutUnit;

    RuntimeScriptHostBridge(final PluginContext context, final ScriptDescriptor descriptor) {
        this(
            context,
            descriptor,
            UI_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        );
    }

    RuntimeScriptHostBridge(
        final PluginContext context,
        final ScriptDescriptor descriptor,
        final long uiTimeout,
        final TimeUnit uiTimeoutUnit
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.scriptPermissions = Set.copyOf(
            Objects.requireNonNull(descriptor, "descriptor").permissions()
        );
        if (uiTimeout <= 0L) {
            throw new IllegalArgumentException("uiTimeout must be positive");
        }
        this.uiTimeout = uiTimeout;
        this.uiTimeoutUnit = Objects.requireNonNull(
            uiTimeoutUnit,
            "uiTimeoutUnit"
        );
    }

    @Override
    public String call(final String operation, final String payloadJson) throws Exception {
        Objects.requireNonNull(operation, "operation");
        final JsonNode payload = payload(payloadJson);
        return switch (operation) {
            case "cubism.status" -> withPermission(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                () -> onEdtDirect(this::status)
            );
            case "cubism.model.snapshot" -> withPermission(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                () -> onUiThread(this::modelSnapshot)
            );
            case "cubism.parameters.list", "cubism.parameters.snapshot" -> withPermission(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                () -> onUiThread(this::parameterList)
            );
            case "cubism.parameters.get" -> withPermission(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                () -> onUiThread(() -> parameterGet(payload))
            );
            case "cubism.parameters.getMany" -> withPermission(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                () -> onUiThread(() -> parameterGetMany(payload))
            );
            case "cubism.parameters.set" -> withPermissions(
                java.util.List.of(
                    PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE
                ),
                () -> onUiThread(cancelled -> parameterSet(payload, cancelled))
            );
            case "cubism.parameters.setMany" -> withPermissions(
                java.util.List.of(
                    PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE
                ),
                () -> onUiThread(cancelled -> parameterSetMany(payload, cancelled))
            );
            case "cubism.parameters.reset" -> withPermissions(
                java.util.List.of(
                    PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE
                ),
                () -> onUiThread(cancelled -> parameterReset(payload, cancelled))
            );
            case "cubism.parameters.resetMany" -> withPermissions(
                java.util.List.of(
                    PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE
                ),
                () -> onUiThread(cancelled -> parameterResetMany(payload, cancelled))
            );
            default -> throw new GraalHostManager.HostCallException(
                "SCRIPT_OPERATION_UNSUPPORTED",
                "Unsupported script host operation: " + operation
            );
        };
    }

    private String withPermission(final String permission, final Callable<String> action)
        throws Exception {
        return withPermissions(Set.of(permission), action);
    }

    private String withPermissions(
        final Iterable<String> permissions,
        final Callable<String> action
    ) throws Exception {
        for (String permission : permissions) {
            if (!scriptPermissions.contains(permission)) {
                throw new GraalHostManager.HostCallException(
                    "SCRIPT_PERMISSION_DENIED",
                    "Script did not declare required permission: " + permission
                );
            }
            final boolean callerGranted = context.permissions().stream()
                .anyMatch(granted -> permission.equals(granted.id()));
            if (!callerGranted) {
                throw new GraalHostManager.HostCallException(
                    "SCRIPT_CALLER_PERMISSION_DENIED",
                    "Calling plugin is not granted script permission: " + permission
                );
            }
        }
        return action.call();
    }

    /**
     * Uses a runtime-owned bounded EDT handoff for the lightweight status call.
     * Going through the calling plugin's RuntimeScheduler can exhaust its short
     * startup budget before the Cubism UI becomes idle; bypassing the EDT itself
     * would instead race live editor objects. The cancellation bit prevents a
     * timed-out queued callback from touching Cubism later.
     */
    private String onEdtDirect(final Callable<String> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.call();
        }
        final CompletableFuture<String> completion = new CompletableFuture<>();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        SwingUtilities.invokeLater(() -> {
            if (cancelled.get()) {
                completion.completeExceptionally(hostCallCancelled());
                return;
            }
            try {
                completion.complete(operation.call());
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
        try {
            return completion.get(uiTimeout, uiTimeoutUnit);
        } catch (ExecutionException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof GraalHostManager.HostCallException hostFailure) {
                throw hostFailure;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("Script host call failed on the UI thread.", cause);
        } finally {
            cancelled.set(true);
        }
    }

    private String onUiThread(final Callable<String> operation) throws Exception {
        return onUiThread(cancelled -> operation.call());
    }

    private String onUiThread(final UiOperation operation) throws Exception {
        final CompletableFuture<String> completion = new CompletableFuture<>();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final Registration registration = context.uiScheduler().runOnUiThread(() -> {
            if (cancelled.get()) {
                completion.completeExceptionally(hostCallCancelled());
                return;
            }
            try {
                completion.complete(operation.call(() -> cancelled.get()
                    || Thread.currentThread().isInterrupted()));
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
        try {
            return completion.get(uiTimeout, uiTimeoutUnit);
        } catch (ExecutionException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof GraalHostManager.HostCallException hostFailure) {
                throw hostFailure;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("Script host call failed on the UI thread.", cause);
        } finally {
            cancelled.set(true);
            registration.close();
        }
    }

    private String status() throws Exception {
        final ObjectNode result = mapper.createObjectNode();
        result.put("hostPresent", context.cubism().isHostPresent());
        try {
            final CubismModel model = context.cubism().model().active();
            result.put("activeModel", true);
            result.put("modelId", model.id().value());
            try {
                result.put("modelName", model.name());
            } catch (UnsupportedOperationException unavailable) {
                result.putNull("modelName");
            }
        } catch (IllegalStateException | UnsupportedOperationException unavailable) {
            result.put("activeModel", false);
            result.putNull("modelId");
            result.putNull("modelName");
        }
        return mapper.writeValueAsString(result);
    }

    private String modelSnapshot() throws Exception {
        final CubismModel model = activeModel();
        final ObjectNode result = mapper.createObjectNode();
        result.put("id", model.id().value());
        try {
            result.put("name", model.name());
        } catch (UnsupportedOperationException unavailable) {
            result.putNull("name");
        }
        addParameterSnapshot(result, model);
        return mapper.writeValueAsString(result);
    }

    private String parameterList() throws Exception {
        final ObjectNode result = mapper.createObjectNode();
        addParameterSnapshot(result, activeModel());
        return mapper.writeValueAsString(result);
    }

    private void addParameterSnapshot(
        final ObjectNode result,
        final CubismModel model
    ) {
        final java.util.List<Parameter> parameters = model.parameters().all();
        final int included = Math.min(parameters.size(), MAX_BATCH_ITEMS);
        final ArrayNode nodes = mapper.createArrayNode();
        for (int index = 0; index < included; index++) {
            nodes.add(parameterNode(parameters.get(index)));
        }
        result.set("parameters", nodes);
        result.put("parameterCount", parameters.size());
        result.put("parametersTruncated", parameters.size() > included);
    }

    private String parameterGet(final JsonNode payload) throws Exception {
        return mapper.writeValueAsString(parameterNode(parameter(payload)));
    }

    private String parameterGetMany(final JsonNode payload) throws Exception {
        final ArrayNode result = mapper.createArrayNode();
        for (String id : ids(payload, "ids")) {
            result.add(parameterNode(parameter(id)));
        }
        final ObjectNode response = mapper.createObjectNode();
        response.set("parameters", result);
        return mapper.writeValueAsString(response);
    }

    private String parameterSet(
        final JsonNode payload,
        final BooleanSupplier cancelled
    ) throws Exception {
        final Parameter parameter = parameter(payload);
        checkHostCallActive(cancelled);
        parameter.setValue(value(payload.get("value")));
        return mapper.writeValueAsString(parameterNode(parameter));
    }

    private String parameterSetMany(
        final JsonNode payload,
        final BooleanSupplier cancelled
    ) throws Exception {
        final JsonNode changes = boundedArray(payload, "changes");
        final java.util.List<ParameterChange> validated = new java.util.ArrayList<>(changes.size());
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode change : changes) {
            if (!change.isObject()) {
                throw invalid("Parameter changes must be JSON objects.");
            }
            final String id = id(change);
            if (!seen.add(id)) {
                throw invalid("Parameter changes must not contain duplicate ids.");
            }
            validated.add(new ParameterChange(parameter(id), value(change.get("value"))));
        }
        final ArrayNode updated = mapper.createArrayNode();
        for (ParameterChange change : validated) {
            checkHostCallActive(cancelled);
            change.parameter().setValue(change.value());
            updated.add(parameterNode(change.parameter()));
        }
        final ObjectNode response = mapper.createObjectNode();
        response.set("updated", updated);
        return mapper.writeValueAsString(response);
    }

    private String parameterReset(
        final JsonNode payload,
        final BooleanSupplier cancelled
    ) throws Exception {
        final Parameter parameter = parameter(payload);
        checkHostCallActive(cancelled);
        parameter.resetToDefault();
        return mapper.writeValueAsString(parameterNode(parameter));
    }

    private String parameterResetMany(
        final JsonNode payload,
        final BooleanSupplier cancelled
    ) throws Exception {
        final java.util.List<Parameter> parameters = new java.util.ArrayList<>();
        for (String id : ids(payload, "ids")) {
            parameters.add(parameter(id));
        }
        final ArrayNode reset = mapper.createArrayNode();
        for (Parameter parameter : parameters) {
            checkHostCallActive(cancelled);
            parameter.resetToDefault();
            reset.add(parameterNode(parameter));
        }
        final ObjectNode response = mapper.createObjectNode();
        response.set("reset", reset);
        return mapper.writeValueAsString(response);
    }

    private Parameter parameter(final JsonNode payload) throws GraalHostManager.HostCallException {
        return parameter(id(payload));
    }

    private Parameter parameter(final String id) throws GraalHostManager.HostCallException {
        return activeModel().parameters().findById(new ParameterId(id))
            .orElseThrow(() -> new GraalHostManager.HostCallException(
                "SCRIPT_PARAMETER_NOT_FOUND", "Parameter was not found: " + id
            ));
    }

    private static String id(final JsonNode payload) throws GraalHostManager.HostCallException {
        final JsonNode rawId = payload.get("id");
        if (rawId == null || !rawId.isTextual() || rawId.textValue().isBlank()
            || rawId.textValue().length() > 256) {
            throw invalid("Parameter operation requires a non-blank id of at most 256 characters.");
        }
        return rawId.textValue();
    }

    private static java.util.List<String> ids(final JsonNode payload, final String field)
        throws GraalHostManager.HostCallException {
        final JsonNode values = boundedArray(payload, field);
        final java.util.List<String> result = new java.util.ArrayList<>(values.size());
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 256) {
                throw invalid(field + " must contain non-blank parameter ids.");
            }
            if (!seen.add(value.textValue())) {
                throw invalid(field + " must not contain duplicate ids.");
            }
            result.add(value.textValue());
        }
        return java.util.List.copyOf(result);
    }

    private static JsonNode boundedArray(final JsonNode payload, final String field)
        throws GraalHostManager.HostCallException {
        final JsonNode values = payload.get(field);
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > MAX_BATCH_ITEMS) {
            throw invalid(field + " must contain 1-" + MAX_BATCH_ITEMS + " items.");
        }
        return values;
    }

    private static float value(final JsonNode rawValue) throws GraalHostManager.HostCallException {
        if (rawValue == null || !rawValue.isNumber()) {
            throw invalid("Parameter set requires a numeric value.");
        }
        final double doubleValue = rawValue.doubleValue();
        if (!Double.isFinite(doubleValue) || doubleValue < -Float.MAX_VALUE || doubleValue > Float.MAX_VALUE) {
            throw invalid("Parameter value must be finite.");
        }
        return (float) doubleValue;
    }

    private static void checkHostCallActive(
        final BooleanSupplier cancelled
    ) throws GraalHostManager.HostCallException {
        if (cancelled.getAsBoolean()) {
            throw hostCallCancelled();
        }
    }

    private static GraalHostManager.HostCallException hostCallCancelled() {
        return new GraalHostManager.HostCallException(
            "SCRIPT_CANCELLED",
            "Script host call was cancelled."
        );
    }

    private static GraalHostManager.HostCallException invalid(final String message) {
        return new GraalHostManager.HostCallException("SCRIPT_ARGUMENT_INVALID", message);
    }

    private CubismModel activeModel() throws GraalHostManager.HostCallException {
        try {
            return context.cubism().model().active();
        } catch (IllegalStateException | UnsupportedOperationException unavailable) {
            throw new GraalHostManager.HostCallException(
                "SCRIPT_ACTIVE_MODEL_UNAVAILABLE", "No active Cubism model is available."
            );
        }
    }

    private ObjectNode parameterNode(final Parameter parameter) {
        final ObjectNode node = mapper.createObjectNode();
        node.put("id", parameter.id().value());
        parameter.name().ifPresentOrElse(
            name -> node.put("name", name),
            () -> node.putNull("name")
        );
        node.put("value", parameter.getValue());
        node.put("minimum", parameter.getMinimumValue());
        node.put("maximum", parameter.getMaximumValue());
        node.put("defaultValue", parameter.getDefaultValue());
        node.put("type", parameter.type().name());
        return node;
    }

    @FunctionalInterface
    private interface UiOperation {
        String call(BooleanSupplier cancelled) throws Exception;
    }

    private record ParameterChange(Parameter parameter, float value) {
    }

    private JsonNode payload(final String json) throws GraalHostManager.HostCallException {
        try {
            final JsonNode parsed = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
            if (parsed == null || !parsed.isObject()) {
                throw new GraalHostManager.HostCallException(
                    "SCRIPT_ARGUMENT_INVALID", "Script host-call payload must be a JSON object."
                );
            }
            return parsed;
        } catch (GraalHostManager.HostCallException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new GraalHostManager.HostCallException(
                "SCRIPT_ARGUMENT_INVALID", "Script host-call payload is malformed JSON."
            );
        }
    }
}
