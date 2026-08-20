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

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Permission-checked JSON bridge from one script execution to the existing Turboism SDK. */
final class RuntimeScriptHostBridge implements GraalHostManager.HostCallHandler {

    private static final long UI_TIMEOUT_SECONDS = 15L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final PluginContext context;
    private final Set<String> scriptPermissions;

    RuntimeScriptHostBridge(final PluginContext context, final ScriptDescriptor descriptor) {
        this.context = Objects.requireNonNull(context, "context");
        this.scriptPermissions = Set.copyOf(
            Objects.requireNonNull(descriptor, "descriptor").permissions()
        );
    }

    @Override
    public String call(final String operation, final String payloadJson) throws Exception {
        Objects.requireNonNull(operation, "operation");
        final JsonNode payload = payload(payloadJson);
        return switch (operation) {
            case "cubism.status" -> withPermission(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                () -> onUiThread(this::status)
            );
            case "cubism.parameters.list" -> withPermission(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                () -> onUiThread(this::parameterList)
            );
            case "cubism.parameters.get" -> withPermission(
                PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                () -> onUiThread(() -> parameterGet(payload))
            );
            case "cubism.parameters.set" -> withPermission(
                PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                () -> onUiThread(() -> parameterSet(payload))
            );
            default -> throw new GraalHostManager.HostCallException(
                "SCRIPT_OPERATION_UNSUPPORTED",
                "Unsupported script host operation: " + operation
            );
        };
    }

    private String withPermission(final String permission, final Callable<String> action)
        throws Exception {
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
        return action.call();
    }

    private String onUiThread(final Callable<String> operation) throws Exception {
        final CompletableFuture<String> completion = new CompletableFuture<>();
        final Registration registration = context.uiScheduler().runOnUiThread(() -> {
            try {
                completion.complete(operation.call());
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
        try {
            return completion.get(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
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

    private String parameterList() throws Exception {
        final CubismModel model = activeModel();
        final ArrayNode parameters = mapper.createArrayNode();
        for (Parameter parameter : model.parameters().all()) {
            parameters.add(parameterNode(parameter));
        }
        final ObjectNode result = mapper.createObjectNode();
        result.set("parameters", parameters);
        return mapper.writeValueAsString(result);
    }

    private String parameterGet(final JsonNode payload) throws Exception {
        return mapper.writeValueAsString(parameterNode(parameter(payload)));
    }

    private String parameterSet(final JsonNode payload) throws Exception {
        final Parameter parameter = parameter(payload);
        final JsonNode rawValue = payload.get("value");
        if (rawValue == null || !rawValue.isNumber()) {
            throw new GraalHostManager.HostCallException(
                "SCRIPT_ARGUMENT_INVALID", "Parameter set requires a numeric value."
            );
        }
        final double doubleValue = rawValue.doubleValue();
        if (!Double.isFinite(doubleValue) || doubleValue < -Float.MAX_VALUE || doubleValue > Float.MAX_VALUE) {
            throw new GraalHostManager.HostCallException(
                "SCRIPT_ARGUMENT_INVALID", "Parameter value must be finite."
            );
        }
        parameter.setValue((float) doubleValue);
        return mapper.writeValueAsString(parameterNode(parameter));
    }

    private Parameter parameter(final JsonNode payload) throws GraalHostManager.HostCallException {
        final JsonNode rawId = payload.get("id");
        if (rawId == null || !rawId.isTextual() || rawId.textValue().isBlank()) {
            throw new GraalHostManager.HostCallException(
                "SCRIPT_ARGUMENT_INVALID", "Parameter operation requires a non-blank id."
            );
        }
        return activeModel().parameters().findById(new ParameterId(rawId.textValue()))
            .orElseThrow(() -> new GraalHostManager.HostCallException(
                "SCRIPT_PARAMETER_NOT_FOUND", "Parameter was not found: " + rawId.textValue()
            ));
    }

    private CubismModel activeModel() throws GraalHostManager.HostCallException {
        try {
            return context.cubism().model().active();
        } catch (IllegalStateException unavailable) {
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
