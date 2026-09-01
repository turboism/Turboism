package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTargetType;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.permission.CubismPermissionException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** MCP parameter and parameter-binding domain backed only by the public Cubism SDK. */
final class McpParameterDomain {

    static final String PARAMETERS_APPLY = "turboism.parameters.apply";
    static final String BINDINGS_APPLY = "turboism.parameter_bindings.apply";
    static final String PARAMETERS_URI = "turboism://active/model/parameters";
    static final String PARAMETER_URI_TEMPLATE = "turboism://active/model/parameters/{parameterId}";
    static final String BINDINGS_URI_TEMPLATE =
        "turboism://active/model/parameters/{parameterId}/bindings";

    private final CubismFacade cubism;
    private final McpExecutionBridge execution;

    McpParameterDomain(final CubismFacade cubism, final McpExecutionBridge execution) {
        this.cubism = Objects.requireNonNull(cubism, "cubism");
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    McpToolCatalog toolCatalog() {
        return new McpToolCatalog(tools(), (name, arguments) -> toolEnvelope(call(name, arguments)));
    }

    McpResourceCatalog resourceCatalog() {
        return new McpResourceCatalog(resources(), resourceTemplates(), uri -> {
            final Map<String, Object> content;
            try {
                content = read(uri);
            } catch (InputException | java.util.NoSuchElementException failure) {
                throw new McpResourceCatalog.ResourceNotFound(uri);
            }
            return List.of(linked(
                entry("uri", uri),
                entry("mimeType", "application/json"),
                entry("text", Json.stringify(content))
            ));
        });
    }

    List<Map<String, Object>> resources() {
        return List.of(linked(
            entry("uri", PARAMETERS_URI),
            entry("name", "Active model parameters"),
            entry("description", "Actual parameter state for the active Cubism model."),
            entry("mimeType", "application/json")
        ));
    }

    List<Map<String, Object>> resourceTemplates() {
        return List.of(
            linked(
                entry("uriTemplate", PARAMETER_URI_TEMPLATE),
                entry("name", "Active model parameter"),
                entry("description", "Actual state for one active-model parameter."),
                entry("mimeType", "application/json")
            ),
            linked(
                entry("uriTemplate", BINDINGS_URI_TEMPLATE),
                entry("name", "Active model parameter bindings"),
                entry("description", "Actual binding state for one active-model parameter."),
                entry("mimeType", "application/json")
            )
        );
    }

    Map<String, Object> read(final String uri) {
        return execution.execute(() -> readOnUi(uri));
    }

    List<Map<String, Object>> tools() {
        return List.of(
            tool(
                PARAMETERS_APPLY,
                "Apply parameter operations",
                "Runs ordered parameter operations against the active Cubism model. Each completed write returns re-read actual state. "
                    + "create_many and remove_many retain their native atomic semantics.",
                applySchema(parameterOperationSchema()),
                Map.of("readOnlyHint", false, "destructiveHint", true, "idempotentHint", false)
            ),
            tool(
                BINDINGS_APPLY,
                "Apply parameter binding operations",
                "Runs ordered parameter-binding operations against the active Cubism model. Individual blend-shape binding CRUD is rejected; "
                    + "batch transfer_morph_clamped remains available through the native atomic API.",
                applySchema(bindingOperationSchema()),
                Map.of("readOnlyHint", false, "destructiveHint", true, "idempotentHint", false)
            )
        );
    }

    Map<String, Object> call(final String name, final Map<String, Object> arguments) {
        final String toolName = Objects.requireNonNull(name, "name");
        final Map<String, Object> checked = copyObject(Objects.requireNonNull(arguments, "arguments"), "arguments");
        try {
            return switch (toolName) {
                case PARAMETERS_APPLY -> execution.execute(() -> applyParameters(checked));
                case BINDINGS_APPLY -> execution.execute(() -> applyBindings(checked));
                default -> throw new InputException("Unknown MCP parameter-domain tool: " + toolName);
            };
        } catch (java.util.concurrent.CancellationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            return topLevelFailure(failure);
        }
    }

    private Map<String, Object> readOnUi(final String uri) {
        final String checked = requireText(uri, "uri", 512);
        final CubismModel model = activeModel();
        if (PARAMETERS_URI.equals(checked)) {
            return linked(entry("parameters", parameters(model)));
        }
        final String parameterPrefix = "turboism://active/model/parameters/";
        if (!checked.startsWith(parameterPrefix)) {
            throw new InputException("Unknown MCP parameter resource: " + checked);
        }
        final String suffix = checked.substring(parameterPrefix.length());
        final int bindingsMarker = suffix.indexOf("/bindings");
        if (bindingsMarker >= 0) {
            if (!suffix.endsWith("/bindings")
                || bindingsMarker != suffix.length() - "/bindings".length()) {
                throw new InputException("Invalid parameter bindings resource URI");
            }
            final ParameterId id = parameterId(decodeUriSegment(
                suffix.substring(0, bindingsMarker)
            ));
            return linked(entry("parameterId", id.value()), entry("bindings", bindings(model, id)));
        }
        if (suffix.isBlank() || suffix.contains("/")) {
            throw new InputException("Invalid parameter resource URI");
        }
        return parameter(model, parameterId(decodeUriSegment(suffix)));
    }

    private static String decodeUriSegment(final String value) {
        try {
            final String decoded = java.net.URLDecoder.decode(
                value.replace("+", "%2B"),
                java.nio.charset.StandardCharsets.UTF_8
            );
            if (decoded.contains("/")) throw new InputException("parameterId must be one URI segment");
            return decoded;
        } catch (IllegalArgumentException failure) {
            throw new InputException("parameterId contains invalid percent encoding");
        }
    }

    private Map<String, Object> applyParameters(final Map<String, Object> arguments) {
        only(arguments, "operations", "stopOnError");
        final List<Object> operations = array(required(arguments, "operations"), "operations");
        final boolean stopOnError = optionalBoolean(arguments, "stopOnError").orElse(false);
        final CubismModel model = activeModel();
        final List<Map<String, Object>> results = new ArrayList<>();
        boolean failed = false;
        for (int index = 0; index < operations.size(); index++) {
            if (failed && stopOnError) break;
            final Map<String, Object> operation = copyObject(operations.get(index), "operations[" + index + "]");
            try {
                results.add(linked(
                    entry("index", index),
                    entry("operation", operationName(operation)),
                    entry("ok", true),
                    entry("result", applyParameter(model, operation))
                ));
            } catch (java.util.concurrent.CancellationException failure) {
                throw failure;
            } catch (WriteOutcomeUnknownException failure) {
                failed = true;
                results.add(outcomeUnknown(index, operation, failure));
            } catch (RuntimeException failure) {
                failed = true;
                results.add(failure(index, operation, failure));
            }
        }
        return linked(
            entry("ok", !failed),
            entry("stopOnError", stopOnError),
            entry("results", List.copyOf(results)),
            entry("parameters", parameters(model))
        );
    }

    private Map<String, Object> applyParameter(
        final CubismModel model,
        final Map<String, Object> operation
    ) {
        final String name = operationName(operation);
        return switch (name) {
            case "set_value" -> {
                only(operation, "operation", "parameterId", "value");
                final Parameter parameter = model.parameters().find(parameterId(operation));
                parameter.setValue(requiredFloat(operation, "value"));
                yield parameter(model, parameter.id());
            }
            case "reset_default" -> {
                only(operation, "operation", "parameterId");
                final Parameter parameter = model.parameters().find(parameterId(operation));
                parameter.resetToDefault();
                yield parameter(model, parameter.id());
            }
            case "create" -> {
                only(operation, "operation", "definition");
                final ParameterDefinition definition = definition(
                    requiredObject(operation, "definition")
                );
                yield parameterWrite(
                    () -> {
                        final Parameter created = model.parameters().create(definition);
                        return linked(entry("parameterId", created.id().value()));
                    },
                    identity -> parameter(
                        model,
                        new ParameterId((String) identity.get("parameterId"))
                    )
                );
            }
            case "create_many" -> {
                only(operation, "operation", "definitions");
                final List<ParameterDefinition> definitions = definitions(
                    required(operation, "definitions")
                );
                yield parameterWrite(
                    () -> {
                        final List<Parameter> created = model.parameters().createMany(definitions);
                        return linked(entry(
                            "parameterIds",
                            created.stream().map(value -> value.id().value()).toList()
                        ));
                    },
                    identity -> linked(entry(
                        "created",
                        parameterIds(identity.get("parameterIds")).stream()
                            .map(id -> parameter(model, id)).toList()
                    ))
                );
            }
            case "copy" -> {
                only(operation, "operation", "parameterId");
                final ParameterId sourceId = parameterId(operation);
                yield parameterWrite(
                    () -> {
                        final Parameter copied = model.parameters().copy(sourceId);
                        return linked(entry("parameterId", copied.id().value()));
                    },
                    identity -> parameter(
                        model,
                        new ParameterId((String) identity.get("parameterId"))
                    )
                );
            }
            case "update_definition" -> {
                only(operation, "operation", "parameterId", "definition");
                final ParameterId targetId = parameterId(operation);
                final ParameterDefinition definition = definition(requiredObject(operation, "definition"));
                model.parameters().find(targetId).updateDefinition(definition);
                yield parameter(model, definition.id());
            }
            case "remove" -> {
                only(operation, "operation", "parameterId");
                final ParameterId id = parameterId(operation);
                yield parameterWrite(
                    () -> {
                        model.parameters().remove(id);
                        return linked(
                            entry("parameterId", id.value()),
                            entry("removed", true)
                        );
                    },
                    identity -> identity
                );
            }
            case "remove_many" -> {
                only(operation, "operation", "parameterIds");
                final List<ParameterId> ids = parameterIds(required(operation, "parameterIds"));
                yield parameterWrite(
                    () -> {
                        model.parameters().removeMany(ids);
                        return linked(
                            entry("parameterIds", ids.stream().map(ParameterId::value).toList()),
                            entry("removed", true)
                        );
                    },
                    identity -> identity
                );
            }
            default -> throw new InputException("Unknown parameter operation: " + name);
        };
    }

    private Map<String, Object> applyBindings(final Map<String, Object> arguments) {
        only(arguments, "operations", "stopOnError");
        final List<Object> operations = array(required(arguments, "operations"), "operations");
        final boolean stopOnError = optionalBoolean(arguments, "stopOnError").orElse(false);
        final CubismModel model = activeModel();
        final List<Map<String, Object>> results = new ArrayList<>();
        boolean failed = false;
        for (int index = 0; index < operations.size(); index++) {
            if (failed && stopOnError) break;
            final Map<String, Object> operation = copyObject(operations.get(index), "operations[" + index + "]");
            try {
                results.add(linked(
                    entry("index", index),
                    entry("operation", operationName(operation)),
                    entry("ok", true),
                    entry("result", applyBinding(model, operation))
                ));
            } catch (java.util.concurrent.CancellationException failure) {
                throw failure;
            } catch (WriteOutcomeUnknownException failure) {
                failed = true;
                results.add(outcomeUnknown(index, operation, failure));
            } catch (RuntimeException failure) {
                failed = true;
                results.add(failure(index, operation, failure));
            }
        }
        return linked(entry("ok", !failed), entry("stopOnError", stopOnError), entry("results", List.copyOf(results)));
    }

    private Map<String, Object> applyBinding(
        final CubismModel model,
        final Map<String, Object> operation
    ) {
        final String name = operationName(operation);
        return switch (name) {
            case "bind" -> {
                only(operation, "operation", "parameterId", "target", "points");
                final ParameterId parameterId = parameterId(operation);
                rejectBlendShapeCrud(model, parameterId);
                final ParameterBindingTarget target = target(requiredObject(operation, "target"));
                final List<ParameterBindingPoint> points = points(required(operation, "points"));
                yield bindingWrite(
                    () -> model.parameterBindings(parameterId).bind(target, points),
                    () -> bindingResult(model, parameterId, target),
                    linked(entry("parameterId", parameterId.value()), entry("target", target(target)))
                );
            }
            case "create_point" -> {
                only(operation, "operation", "parameterId", "target", "point");
                final ParameterId parameterId = parameterId(operation);
                rejectBlendShapeCrud(model, parameterId);
                final ParameterBindingTarget target = target(requiredObject(operation, "target"));
                final ParameterBindingPoint point = point(requiredObject(operation, "point"));
                yield bindingWrite(
                    () -> model.parameterBindings(parameterId).createPoint(target, point),
                    () -> bindingResult(model, parameterId, target),
                    linked(entry("parameterId", parameterId.value()), entry("target", target(target)))
                );
            }
            case "move_point" -> {
                only(operation, "operation", "parameterId", "target", "pointId", "value");
                final ParameterId parameterId = parameterId(operation);
                rejectBlendShapeCrud(model, parameterId);
                final ParameterBindingTarget target = target(requiredObject(operation, "target"));
                final ParameterBindingPointId pointId = pointId(operation);
                final float value = requiredFloat(operation, "value");
                yield bindingWrite(
                    () -> model.parameterBindings(parameterId).movePoint(target, pointId, value),
                    () -> bindingResult(model, parameterId, target),
                    linked(entry("parameterId", parameterId.value()), entry("target", target(target)))
                );
            }
            case "delete_point" -> {
                only(operation, "operation", "parameterId", "target", "pointId");
                final ParameterId parameterId = parameterId(operation);
                rejectBlendShapeCrud(model, parameterId);
                final ParameterBindingTarget target = target(requiredObject(operation, "target"));
                final ParameterBindingPointId pointId = pointId(operation);
                yield bindingWrite(
                    () -> model.parameterBindings(parameterId).deletePoint(target, pointId),
                    () -> bindingResult(model, parameterId, target),
                    linked(entry("parameterId", parameterId.value()), entry("target", target(target)))
                );
            }
            case "unbind" -> {
                only(operation, "operation", "parameterId", "target");
                final ParameterId parameterId = parameterId(operation);
                rejectBlendShapeCrud(model, parameterId);
                final ParameterBindingTarget target = target(requiredObject(operation, "target"));
                yield bindingWrite(
                    () -> model.parameterBindings(parameterId).unbind(target),
                    () -> bindingResult(model, parameterId, target),
                    linked(entry("parameterId", parameterId.value()), entry("target", target(target)))
                );
            }
            case "invert" -> {
                only(operation, "operation", "parameterId", "targets");
                final ParameterId parameterId = parameterId(operation);
                final List<ParameterBindingTarget> targets = targets(required(operation, "targets"));
                yield bindingWrite(
                    () -> model.parameterBindingBatch().invert(targets),
                    () -> bindingResults(model, parameterId, targets),
                    linked(
                        entry("parameterId", parameterId.value()),
                        entry("targets", targets.stream().map(McpParameterDomain::target).toList())
                    )
                );
            }
            case "transfer", "transfer_clamped" -> {
                only(operation, "operation", "sourceParameterId", "targetParameterId", "targets", "invertAfterTransfer");
                final ParameterBindingTransferPlan plan = transferPlan(operation);
                yield bindingWrite(
                    () -> {
                        if ("transfer".equals(name)) {
                            model.parameterBindingBatch().transfer(plan);
                        } else {
                            model.parameterBindingBatch().transferClamped(plan);
                        }
                    },
                    () -> linked(
                        entry("source", bindingResults(model, plan.sourceParameterId(), plan.targets())),
                        entry("target", bindingResults(model, plan.targetParameterId(), plan.targets()))
                    ),
                    linked(
                        entry("sourceParameterId", plan.sourceParameterId().value()),
                        entry("targetParameterId", plan.targetParameterId().value()),
                        entry("targets", plan.targets().stream().map(McpParameterDomain::target).toList())
                    )
                );
            }
            case "transfer_morph_clamped" -> {
                only(operation, "operation", "sourceParameterId", "targetParameterId", "targets", "invertAfterTransfer");
                final ParameterBindingTransferPlan plan = transferPlan(operation);
                yield bindingWrite(
                    () -> model.parameterBindingBatch().transferMorphClamped(plan),
                    () -> linked(
                        entry("source", bindingResults(model, plan.sourceParameterId(), plan.targets())),
                        entry("target", bindingResults(model, plan.targetParameterId(), plan.targets()))
                    ),
                    linked(
                        entry("sourceParameterId", plan.sourceParameterId().value()),
                        entry("targetParameterId", plan.targetParameterId().value()),
                        entry("targets", plan.targets().stream().map(McpParameterDomain::target).toList())
                    )
                );
            }
            default -> throw new InputException("Unknown parameter binding operation: " + name);
        };
    }

    private CubismModel activeModel() {
        return cubism.model().active();
    }

    private static void rejectBlendShapeCrud(final CubismModel model, final ParameterId id) {
        if (model.parameters().find(id).isBlendShape()) {
            throw new InputException("Individual blend_shape binding CRUD is not supported; use transfer_morph_clamped");
        }
    }

    private static Map<String, Object> toolEnvelope(final Map<String, Object> output) {
        final boolean error = !Boolean.TRUE.equals(output.get("ok"));
        return linked(
            entry("content", List.of(linked(
                entry("type", "text"),
                entry("text", Json.stringify(output))
            ))),
            entry("structuredContent", output),
            entry("isError", error)
        );
    }

    private static Map<String, Object> parameterWrite(
        final java.util.function.Supplier<Map<String, Object>> write,
        final java.util.function.Function<Map<String, Object>, Map<String, Object>> readback
    ) {
        final Map<String, Object> identity;
        try {
            identity = write.get();
        } catch (RuntimeException failure) {
            throw new WriteOutcomeUnknownException(failure);
        }
        try {
            final LinkedHashMap<String, Object> result = new LinkedHashMap<>(
                readback.apply(identity)
            );
            result.put("outcome", WriteOutcome.APPLIED.name());
            result.put("retryable", false);
            return result;
        } catch (RuntimeException failure) {
            final LinkedHashMap<String, Object> result = new LinkedHashMap<>(identity);
            result.put("outcome", WriteOutcome.APPLIED_WITH_READBACK_WARNING.name());
            result.put("retryable", false);
            result.put("readbackWarning", error(failure));
            result.put("diagnosticId", java.util.UUID.randomUUID().toString());
            return result;
        }
    }

    private static Map<String, Object> bindingWrite(
        final Runnable write,
        final java.util.function.Supplier<Map<String, Object>> readback,
        final Map<String, Object> identity
    ) {
        try {
            write.run();
        } catch (RuntimeException failure) {
            throw new WriteOutcomeUnknownException(failure);
        }
        try {
            return bindingWriteResult(WriteOutcome.APPLIED, readback.get(), null);
        } catch (RuntimeException failure) {
            return bindingWriteResult(
                WriteOutcome.APPLIED_WITH_READBACK_WARNING,
                identity,
                failure
            );
        }
    }

    private static Map<String, Object> bindingWriteResult(
        final WriteOutcome outcome,
        final Map<String, Object> readback,
        final RuntimeException readbackFailure
    ) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", outcome.name());
        result.put("retryable", false);
        result.put(
            "canonicalPointIds",
            readbackFailure == null ? canonicalPointIds(readback) : null
        );
        result.putAll(readback);
        if (readbackFailure != null) {
            result.put("readbackWarning", error(readbackFailure));
            result.put("diagnosticId", java.util.UUID.randomUUID().toString());
        }
        return result;
    }

    private static List<String> canonicalPointIds(final Object value) {
        final java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        collectCanonicalPointIds(value, result);
        return List.copyOf(result);
    }

    private static void collectCanonicalPointIds(
        final Object value,
        final java.util.Set<String> result
    ) {
        if (value instanceof Map<?, ?> object) {
            final Object points = object.get("points");
            if (points instanceof List<?> list) {
                for (Object point : list) {
                    if (point instanceof Map<?, ?> pointObject
                        && pointObject.get("id") instanceof String id) {
                        result.add(id);
                    }
                }
            }
            for (Object nested : object.values()) collectCanonicalPointIds(nested, result);
        } else if (value instanceof List<?> list) {
            for (Object nested : list) collectCanonicalPointIds(nested, result);
        }
    }

    private static Map<String, Object> outcomeUnknown(
        final int index,
        final Map<String, Object> operation,
        final WriteOutcomeUnknownException failure
    ) {
        final RuntimeException cause = (RuntimeException) failure.getCause();
        return linked(
            entry("index", index),
            entry("operation", operation.get("operation")),
            entry("ok", false),
            entry("error", linked(
                entry("code", WriteOutcome.OUTCOME_UNKNOWN.name()),
                entry("message", safeMessage(cause)),
                entry("outcome", WriteOutcome.OUTCOME_UNKNOWN.name()),
                entry("retryable", false),
                entry("canonicalPointIds", null),
                entry("diagnosticId", java.util.UUID.randomUUID().toString())
            ))
        );
    }

    private static Map<String, Object> failure(
        final int index,
        final Map<String, Object> operation,
        final RuntimeException failure
    ) {
        return linked(
            entry("index", index),
            entry("operation", operation.get("operation")),
            entry("ok", false),
            entry("error", error(failure))
        );
    }

    private static Map<String, Object> topLevelFailure(final RuntimeException failure) {
        return linked(entry("ok", false), entry("error", error(failure)));
    }

    private static Map<String, Object> error(final RuntimeException failure) {
        return linked(
            entry("code", errorCode(failure)),
            entry("message", safeMessage(failure))
        );
    }

    private static String errorCode(final RuntimeException failure) {
        if (failure instanceof InputException) return "INVALID_ARGUMENT";
        if (failure instanceof CubismPermissionException || failure instanceof SecurityException) {
            return "PERMISSION_DENIED";
        }
        if (failure instanceof UnsupportedOperationException) return "UNAVAILABLE";
        if (failure instanceof McpExecutionBridge.ExecutionFailure
            && safeMessage(failure).toLowerCase(Locale.ROOT).contains("timed out")) {
            return "TIMEOUT";
        }
        return "FAILED";
    }

    private static Map<String, Object> parameter(final CubismModel model, final ParameterId id) {
        final Parameter value = model.parameters().find(id);
        final ParameterDefinition definition = model.parameterDefinitions().find(id);
        return linked(
            entry("id", value.id().value()),
            entry("name", definition.name()),
            entry("value", value.getValue()),
            entry("minimumValue", value.getMinimumValue()),
            entry("defaultValue", value.getDefaultValue()),
            entry("maximumValue", value.getMaximumValue()),
            entry("type", definition.type().name().toLowerCase(Locale.ROOT)),
            entry("repeat", definition.repeat())
        );
    }

    private static List<Map<String, Object>> parameters(final CubismModel model) {
        return model.parameters().all().stream().map(value -> parameter(model, value.id())).toList();
    }

    private static List<Map<String, Object>> bindings(final CubismModel model, final ParameterId id) {
        return model.parameters().find(id).getParameterBindings().stream()
            .map(McpParameterDomain::binding).toList();
    }

    private static Map<String, Object> bindingResult(
        final CubismModel model,
        final ParameterId parameterId,
        final ParameterBindingTarget target
    ) {
        final Map<String, Object> result = linked(
            entry("parameterId", parameterId.value()),
            entry("target", target(target)),
            entry("binding", model.parameters().find(parameterId).getParameterBindings().stream()
                .filter(value -> value.target().equals(target))
                .findFirst().map(McpParameterDomain::binding).orElse(null))
        );
        return result;
    }

    private static Map<String, Object> bindingResults(
        final CubismModel model,
        final ParameterId parameterId,
        final List<ParameterBindingTarget> targets
    ) {
        return linked(
            entry("parameterId", parameterId.value()),
            entry("bindings", targets.stream().map(target -> bindingResult(model, parameterId, target)).toList())
        );
    }

    private static Map<String, Object> binding(final ParameterBinding value) {
        return linked(
            entry("parameterId", value.parameterId().value()),
            entry("target", target(value.target())),
            entry("family", value.family().name().toLowerCase(Locale.ROOT)),
            entry("points", value.points().stream().map(McpParameterDomain::point).toList())
        );
    }

    private static Map<String, Object> target(final ParameterBindingTarget value) {
        return linked(
            entry("type", value.type().name().toLowerCase(Locale.ROOT)),
            entry("id", value.id())
        );
    }

    private static Map<String, Object> point(final ParameterBindingPoint value) {
        return linked(entry("id", value.id().value()), entry("value", value.value()));
    }

    private static String operationName(final Map<String, Object> operation) {
        return requireText(operation.get("operation"), "operation", 64).toLowerCase(Locale.ROOT);
    }

    private static ParameterId parameterId(final Map<String, Object> values) {
        return parameterId(requireText(values.get("parameterId"), "parameterId", 256));
    }

    private static ParameterId parameterId(final String value) {
        return new ParameterId(requireText(value, "parameterId", 256));
    }

    private static ParameterBindingPointId pointId(final Map<String, Object> values) {
        return new ParameterBindingPointId(requireText(values.get("pointId"), "pointId", 256));
    }

    private static ParameterDefinition definition(final Map<String, Object> values) {
        only(values, "id", "name", "minimumValue", "defaultValue", "maximumValue", "type", "repeat");
        final String type = requireText(values.get("type"), "definition.type", 64).toLowerCase(Locale.ROOT);
        final ParameterType parameterType = switch (type) {
            case "normal" -> ParameterType.NORMAL;
            case "blend_shape" -> ParameterType.BLEND_SHAPE;
            default -> throw new InputException("definition.type must be normal or blend_shape");
        };
        return new ParameterDefinition(
            parameterId(requireText(values.get("id"), "definition.id", 256)),
            requireText(values.get("name"), "definition.name", 256),
            requiredFloat(values, "minimumValue"),
            requiredFloat(values, "defaultValue"),
            requiredFloat(values, "maximumValue"),
            parameterType,
            requiredBoolean(values, "repeat")
        );
    }

    private static List<ParameterDefinition> definitions(final Object value) {
        return array(value, "definitions").stream().map(item -> definition(copyObject(item, "definition"))).toList();
    }

    private static List<ParameterId> parameterIds(final Object value) {
        return array(value, "parameterIds").stream()
            .map(item -> parameterId(requireText(item, "parameterIds[]", 256))).toList();
    }

    private static ParameterBindingTarget target(final Map<String, Object> values) {
        only(values, "type", "id");
        final String id = requireText(values.get("id"), "target.id", 256);
        return switch (requireText(values.get("type"), "target.type", 64).toLowerCase(Locale.ROOT)) {
            case "art_mesh" -> ParameterBindingTarget.artMesh(new ArtMeshId(id));
            case "warp_deformer" -> ParameterBindingTarget.warpDeformer(new DeformerId(id));
            case "rotation_deformer" -> ParameterBindingTarget.rotationDeformer(new DeformerId(id));
            default -> throw new InputException("target.type must be art_mesh, warp_deformer, or rotation_deformer");
        };
    }

    private static List<ParameterBindingTarget> targets(final Object value) {
        return array(value, "targets").stream().map(item -> target(copyObject(item, "target"))).toList();
    }

    private static ParameterBindingPoint point(final Map<String, Object> values) {
        only(values, "id", "value");
        final String provisionalId = values.containsKey("id")
            ? requireText(values.get("id"), "point.id", 256)
            : "provisional:" + java.util.UUID.randomUUID();
        return new ParameterBindingPoint(
            new ParameterBindingPointId(provisionalId),
            requiredFloat(values, "value")
        );
    }

    private static List<ParameterBindingPoint> points(final Object value) {
        return array(value, "points").stream().map(item -> point(copyObject(item, "point"))).toList();
    }

    private static ParameterBindingTransferPlan transferPlan(final Map<String, Object> values) {
        return new ParameterBindingTransferPlan(
            new ParameterId(requireText(values.get("sourceParameterId"), "sourceParameterId", 256)),
            new ParameterId(requireText(values.get("targetParameterId"), "targetParameterId", 256)),
            targets(required(values, "targets")),
            optionalBoolean(values, "invertAfterTransfer").orElse(false)
        );
    }

    private static Map<String, Object> applySchema(final Map<String, Object> operationSchema) {
        return objectSchema(properties(
            entry("operations", linked(
                entry("type", "array"),
                entry("minItems", 1),
                entry("items", operationSchema)
            )),
            entry("stopOnError", Map.of("type", "boolean"))
        ), List.of("operations"));
    }

    private static Map<String, Object> parameterOperationSchema() {
        return oneOf(
            parameterOperation("set_value", properties(
                entry("parameterId", stringSchema()),
                entry("value", Map.of("type", "number"))
            ), List.of("parameterId", "value")),
            parameterOperation("reset_default", properties(
                entry("parameterId", stringSchema())
            ), List.of("parameterId")),
            parameterOperation("create", properties(
                entry("definition", definitionSchema())
            ), List.of("definition")),
            parameterOperation("create_many", properties(
                entry("definitions", arraySchema(definitionSchema(), 1))
            ), List.of("definitions")),
            parameterOperation("copy", properties(
                entry("parameterId", linked(
                    entry("type", "string"),
                    entry("minLength", 1),
                    entry("maxLength", 256),
                    entry("description", "Source Parameter ID. Cubism generates the copied Parameter ID returned by the service.")
                ))
            ), List.of("parameterId")),
            parameterOperation("update_definition", properties(
                entry("parameterId", stringSchema()),
                entry("definition", definitionSchema())
            ), List.of("parameterId", "definition")),
            parameterOperation("remove", properties(
                entry("parameterId", stringSchema())
            ), List.of("parameterId")),
            parameterOperation("remove_many", properties(
                entry("parameterIds", arraySchema(stringSchema(), 1))
            ), List.of("parameterIds"))
        );
    }

    private static Map<String, Object> parameterOperation(
        final String operation,
        final Map<String, Object> operationProperties,
        final List<String> required
    ) {
        final LinkedHashMap<String, Object> properties = new LinkedHashMap<>(linked(
            entry("operation", enumSchema(List.of(operation)))
        ));
        properties.putAll(operationProperties);
        final ArrayList<String> requiredFields = new ArrayList<>();
        requiredFields.add("operation");
        requiredFields.addAll(required);
        return objectSchema(properties, List.copyOf(requiredFields));
    }

    private static Map<String, Object> bindingOperationSchema() {
        return oneOf(
            bindingOperation("bind", properties(
                entry("parameterId", stringSchema()),
                entry("target", targetSchema()),
                entry("points", arraySchema(pointSchema(), 1))
            ), List.of("parameterId", "target", "points")),
            bindingOperation("create_point", properties(
                entry("parameterId", stringSchema()),
                entry("target", targetSchema()),
                entry("point", pointSchema())
            ), List.of("parameterId", "target", "point")),
            bindingOperation("move_point", properties(
                entry("parameterId", stringSchema()),
                entry("target", targetSchema()),
                entry("pointId", canonicalPointIdSchema()),
                entry("value", Map.of("type", "number"))
            ), List.of("parameterId", "target", "pointId", "value")),
            bindingOperation("delete_point", properties(
                entry("parameterId", stringSchema()),
                entry("target", targetSchema()),
                entry("pointId", canonicalPointIdSchema())
            ), List.of("parameterId", "target", "pointId")),
            bindingOperation("unbind", properties(
                entry("parameterId", stringSchema()),
                entry("target", targetSchema())
            ), List.of("parameterId", "target")),
            bindingOperation("invert", properties(
                entry("parameterId", stringSchema()),
                entry("targets", arraySchema(targetSchema(), 1))
            ), List.of("parameterId", "targets")),
            transferOperation("transfer"),
            transferOperation("transfer_clamped"),
            transferOperation("transfer_morph_clamped")
        );
    }

    private static Map<String, Object> bindingOperation(
        final String operation,
        final Map<String, Object> operationProperties,
        final List<String> required
    ) {
        return parameterOperation(operation, operationProperties, required);
    }

    private static Map<String, Object> transferOperation(final String operation) {
        return bindingOperation(operation, properties(
            entry("sourceParameterId", stringSchema()),
            entry("targetParameterId", stringSchema()),
            entry("targets", arraySchema(targetSchema(), 1)),
            entry("invertAfterTransfer", Map.of("type", "boolean"))
        ), List.of("sourceParameterId", "targetParameterId", "targets"));
    }

    private static Map<String, Object> definitionSchema() {
        return objectSchema(properties(
            entry("id", stringSchema()), entry("name", stringSchema()),
            entry("minimumValue", Map.of("type", "number")), entry("defaultValue", Map.of("type", "number")),
            entry("maximumValue", Map.of("type", "number")), entry("type", enumSchema(List.of("normal", "blend_shape"))),
            entry("repeat", Map.of("type", "boolean"))
        ), List.of("id", "name", "minimumValue", "defaultValue", "maximumValue", "type", "repeat"));
    }

    private static Map<String, Object> targetSchema() {
        return objectSchema(properties(entry("type", enumSchema(List.of("art_mesh", "warp_deformer", "rotation_deformer"))), entry("id", stringSchema())), List.of("type", "id"));
    }

    private static Map<String, Object> pointSchema() {
        return objectSchema(properties(
            entry("id", linked(
                entry("type", "string"),
                entry("minLength", 1),
                entry("maxLength", 256),
                entry("deprecated", true),
                entry("description", "Optional provisional input label. Cubism may replace it; use the canonical ID returned by MCP for move/delete.")
            )),
            entry("value", Map.of("type", "number"))
        ), List.of("value"));
    }

    private static Map<String, Object> canonicalPointIdSchema() {
        return linked(
            entry("type", "string"),
            entry("minLength", 1),
            entry("maxLength", 256),
            entry("description", "Canonical binding point ID returned by the latest binding read/write response.")
        );
    }

    private static Map<String, Object> tool(final String name, final String title, final String description, final Map<String, Object> schema, final Map<String, Object> annotations) {
        final Map<String, Object> outputSchema = PARAMETERS_APPLY.equals(name)
            ? McpOutputSchemas.parameterBatch() : McpOutputSchemas.bindingBatch();
        return linked(
            entry("name", name),
            entry("title", title),
            entry("description", description),
            entry("inputSchema", schema),
            entry("outputSchema", outputSchema),
            entry("annotations", annotations)
        );
    }

    private static Map<String, Object> objectSchema(final Map<String, Object> properties, final List<String> required) {
        return linked(entry("type", "object"), entry("properties", properties), entry("required", required), entry("additionalProperties", false));
    }

    private static Map<String, Object> arraySchema(final Map<String, Object> items) {
        return linked(entry("type", "array"), entry("items", items));
    }

    private static Map<String, Object> arraySchema(
        final Map<String, Object> items,
        final int minimumItems
    ) {
        return linked(
            entry("type", "array"),
            entry("minItems", minimumItems),
            entry("items", items)
        );
    }

    @SafeVarargs
    private static Map<String, Object> oneOf(final Map<String, Object>... alternatives) {
        return linked(entry("oneOf", List.of(alternatives)));
    }

    private static Map<String, Object> stringSchema() {
        return linked(entry("type", "string"), entry("minLength", 1), entry("maxLength", 256));
    }

    private static Map<String, Object> enumSchema(final List<String> values) {
        return linked(entry("type", "string"), entry("enum", values));
    }

    @SafeVarargs
    private static Map<String, Object> properties(final Map.Entry<String, Object>... values) { return linked(values); }

    @SafeVarargs
    private static Map<String, Object> linked(final Map.Entry<String, Object>... values) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> value : values) result.put(value.getKey(), value.getValue());
        return result;
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    private static Object required(final Map<String, Object> values, final String key) {
        if (!values.containsKey(key) || values.get(key) == null) throw new InputException(key + " is required");
        return values.get(key);
    }

    private static Map<String, Object> requiredObject(final Map<String, Object> values, final String key) { return copyObject(required(values, key), key); }

    private static String requireText(final Object value, final String label, final int maximum) {
        if (!(value instanceof String text)) throw new InputException(label + " must be a string");
        final String normalized = text.strip();
        if (normalized.isEmpty()) throw new InputException(label + " must not be blank");
        if (normalized.length() > maximum) throw new InputException(label + " must not exceed " + maximum + " characters");
        return normalized;
    }

    private static float requiredFloat(final Map<String, Object> values, final String key) {
        final Object value = required(values, key);
        if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())) throw new InputException(key + " must be a finite number");
        return number.floatValue();
    }

    private static boolean requiredBoolean(final Map<String, Object> values, final String key) {
        final Object value = required(values, key);
        if (!(value instanceof Boolean result)) throw new InputException(key + " must be a boolean");
        return result;
    }

    private static Optional<Boolean> optionalBoolean(final Map<String, Object> values, final String key) {
        if (!values.containsKey(key) || values.get(key) == null) return Optional.empty();
        return Optional.of(requiredBoolean(values, key));
    }

    private static List<Object> array(final Object value, final String label) {
        if (!(value instanceof List<?> list)) throw new InputException(label + " must be an array");
        return new ArrayList<>(list);
    }

    private static Map<String, Object> copyObject(final Object value, final String label) {
        if (!(value instanceof Map<?, ?> raw)) throw new InputException(label + " must be an object");
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new InputException(label + " contains a non-string key");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void only(final Map<String, Object> values, final String... names) {
        final java.util.Set<String> allowed = java.util.Set.of(names);
        for (String key : values.keySet()) if (!allowed.contains(key)) throw new InputException("unknown argument: " + key);
    }

    private static String safeMessage(final RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank() ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private enum WriteOutcome {
        APPLIED,
        APPLIED_WITH_READBACK_WARNING,
        OUTCOME_UNKNOWN
    }

    private static final class WriteOutcomeUnknownException extends RuntimeException {
        private WriteOutcomeUnknownException(final RuntimeException cause) {
            super(cause);
        }
    }

    private static final class InputException extends RuntimeException {
        private InputException(final String message) { super(message); }
    }
}
