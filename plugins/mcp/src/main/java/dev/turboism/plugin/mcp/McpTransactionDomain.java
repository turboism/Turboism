package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Executes a bounded sequence of explicitly transaction-eligible MCP operations. */
final class McpTransactionDomain {

    static final String TRANSACTION_EXECUTE = "turboism.transaction.execute";
    private static final int MAX_STEPS = 64;
    private static final int MAX_VALUE_DEPTH = 32;
    private static final int MAX_STEP_RESULT_BYTES = 256 * 1024;
    private static final Pattern STEP_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    private static final Set<McpOperationEffect> ALLOWED_EFFECTS = Set.of(
        McpOperationEffect.READ,
        McpOperationEffect.UNDOABLE_WRITE
    );

    private final ToolSource tools;
    private final AuthoringTransactionService transactions;

    McpTransactionDomain(
        final McpToolCatalog tools,
        final AuthoringTransactionService transactions
    ) {
        this(new CatalogToolSource(tools), transactions);
    }

    McpTransactionDomain(
        final ToolSource tools,
        final AuthoringTransactionService transactions
    ) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    McpToolCatalog tools(final McpExecutionBridge execution) {
        return McpToolCatalog.of(List.of(McpRegisteredTool.typed(
            toolDefinition(),
            McpOperationEffect.TRANSACTION_CONTROL,
            McpExecutionAffinity.UI_THREAD,
            false,
            Objects.requireNonNull(execution, "execution"),
            arguments -> call(TRANSACTION_EXECUTE, arguments)
        )));
    }

    static McpToolCatalog attach(
        final McpToolCatalog baseTools,
        final AuthoringTransactionService transactions,
        final McpExecutionBridge execution,
        final UnaryOperator<McpToolCatalog> observer
    ) {
        final McpToolCatalog checkedBase = Objects.requireNonNull(baseTools, "baseTools");
        final UnaryOperator<McpToolCatalog> checkedObserver = Objects.requireNonNull(
            observer,
            "observer"
        );
        final AtomicReference<McpToolCatalog> observed = new AtomicReference<>();
        final ToolSource deferred = new ToolSource() {
            @Override
            public Optional<ToolDescriptor> descriptor(final String name) {
                return new CatalogToolSource(requireObserved(observed)).descriptor(name);
            }

            @Override
            public Map<String, Object> callRaw(
                final String name,
                final Map<String, Object> arguments
            ) {
                return requireObserved(observed).callRaw(name, arguments);
            }
        };
        final McpTransactionDomain domain = new McpTransactionDomain(deferred, transactions);
        final McpCapabilitiesDomain capabilities = new McpCapabilitiesDomain(
            () -> requireObserved(observed)
        );
        final McpToolCatalog decorated = Objects.requireNonNull(
            checkedObserver.apply(McpToolCatalog.combine(
                checkedBase,
                domain.tools(execution),
                capabilities.tools()
            )),
            "observer result"
        );
        observed.set(decorated);
        return decorated;
    }

    private static McpToolCatalog requireObserved(
        final AtomicReference<McpToolCatalog> observed
    ) {
        final McpToolCatalog catalog = observed.get();
        if (catalog == null) {
            throw new IllegalStateException("transaction tool catalog is not fully initialized");
        }
        return catalog;
    }

    Map<String, Object> call(final String name, final Map<String, Object> arguments) {
        if (!TRANSACTION_EXECUTE.equals(name)) {
            return envelope(rejected("mcp.transaction.unknown_tool"));
        }
        try {
            return envelope(execute(arguments == null ? Map.of() : arguments));
        } catch (RequestRejected rejection) {
            return envelope(rejected(rejection.diagnosticId));
        } catch (RuntimeException failure) {
            return envelope(rejected("mcp.transaction.internal_failure"));
        }
    }

    private Map<String, Object> execute(final Map<String, Object> arguments) {
        requireDepth(arguments);
        only(arguments, "label", "steps");
        final AuthoringTransactionOptions options;
        try {
            options = AuthoringTransactionOptions.of(requiredString(arguments, "label"));
        } catch (IllegalArgumentException failure) {
            throw rejectedRequest("mcp.transaction.invalid_request");
        }
        final List<StepPlan> plans = parseSteps(arguments.get("steps"));
        final List<Map<String, Object>> executed = new ArrayList<>();

        final AuthoringTransactionResult<List<Map<String, Object>>> result = transactions.execute(
            options,
            () -> runSteps(plans, executed)
        );
        return transactionOutput(result, executed);
    }

    private List<StepPlan> parseSteps(final Object rawSteps) {
        if (!(rawSteps instanceof List<?> list) || list.isEmpty() || list.size() > MAX_STEPS) {
            throw rejectedRequest("mcp.transaction.invalid_request");
        }
        final List<StepPlan> plans = new ArrayList<>(list.size());
        final Set<String> priorIds = new LinkedHashSet<>();
        final Map<String, ToolDescriptor> priorDescriptors = new LinkedHashMap<>();
        for (Object rawStep : list) {
            if (!(rawStep instanceof Map<?, ?> map)) {
                throw rejectedRequest("mcp.transaction.invalid_request");
            }
            final Map<String, Object> step = stringKeyedMap(map);
            only(step, "id", "tool", "arguments");
            final String id = requiredString(step, "id");
            if (!STEP_ID.matcher(id).matches() || !priorIds.add(id)) {
                throw rejectedRequest("mcp.transaction.invalid_request");
            }
            final String tool = requiredString(step, "tool");
            final ToolDescriptor descriptor = tools.descriptor(tool)
                .orElseThrow(() -> rejectedRequest("mcp.transaction.tool_unknown"));
            if (!descriptor.transactionEligible() || !ALLOWED_EFFECTS.contains(descriptor.effect())) {
                throw rejectedRequest("mcp.transaction.tool_not_eligible");
            }
            final Map<String, Object> childArguments = step.containsKey("arguments")
                ? stringKeyedMap(requiredMap(step.get("arguments")))
                : Map.of();
            requireDepth(childArguments);
            validateReferences(
                childArguments,
                descriptor.inputSchema(),
                priorDescriptors,
                id
            );
            if (!containsReference(childArguments)
                && !descriptor.inputSchema().isEmpty()
                && !McpJsonSchema.validates(childArguments, descriptor.inputSchema())) {
                throw rejectedRequest("mcp.transaction.input_schema_mismatch");
            }
            plans.add(new StepPlan(id, tool, childArguments, descriptor));
            priorDescriptors.put(id, descriptor);
        }
        return List.copyOf(plans);
    }

    private List<Map<String, Object>> runSteps(
        final List<StepPlan> plans,
        final List<Map<String, Object>> executed
    ) throws Exception {
        final Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        for (StepPlan plan : plans) {
            final Map<String, Object> resolved = stringKeyedMap(
                requiredMap(resolveValue(plan.arguments(), outputs))
            );
            if (!plan.descriptor().inputSchema().isEmpty()
                && !McpJsonSchema.validates(resolved, plan.descriptor().inputSchema())) {
                final Map<String, Object> output = immutableMap(
                    entry("ok", false),
                    entry("code", "INPUT_SCHEMA_MISMATCH"),
                    entry("message", "Resolved child arguments do not match the declared input schema")
                );
                executed.add(stepOutput(
                    plan,
                    output,
                    "mcp.transaction.step_input_schema_mismatch"
                ));
                throw new StepFailed(plan.id(), null);
            }
            final Map<String, Object> childEnvelope;
            try {
                childEnvelope = Objects.requireNonNull(
                    tools.callRaw(plan.tool(), resolved),
                    "transaction child tool envelope"
                );
            } catch (RuntimeException failure) {
                final Map<String, Object> output = immutableMap(
                    entry("ok", false),
                    entry("code", "TOOL_EXCEPTION"),
                    entry("message", safeMessage(failure))
                );
                executed.add(stepOutput(plan, output, "mcp.transaction.step_failed"));
                throw new StepFailed(plan.id(), failure);
            }

            final Object structured = childEnvelope.get("structuredContent");
            if (!(structured instanceof Map<?, ?> map)) {
                final Map<String, Object> output = immutableMap(
                    entry("ok", false),
                    entry("code", "INVALID_TOOL_OUTPUT"),
                    entry("message", "Child tool returned no structuredContent object")
                );
                executed.add(stepOutput(plan, output, "mcp.transaction.step_failed"));
                throw new StepFailed(plan.id(), null);
            }
            final Map<String, Object> output = stringKeyedMap(map);
            if (valueDepthExceeds(output, MAX_VALUE_DEPTH)) {
                executed.add(stepOutput(
                    plan,
                    output,
                    "mcp.transaction.step_output_too_deep"
                ));
                throw new StepFailed(plan.id(), null);
            }
            final int outputBytes = Json.stringify(output)
                .getBytes(StandardCharsets.UTF_8).length;
            if (outputBytes > MAX_STEP_RESULT_BYTES) {
                executed.add(stepOutput(
                    plan,
                    output,
                    "mcp.transaction.step_output_too_large"
                ));
                throw new StepFailed(plan.id(), null);
            }
            if (!plan.descriptor().outputSchema().isEmpty()
                && !McpJsonSchema.validates(output, plan.descriptor().outputSchema())) {
                executed.add(stepOutput(
                    plan,
                    output,
                    "mcp.transaction.step_output_schema_mismatch"
                ));
                throw new StepFailed(plan.id(), null);
            }
            outputs.put(plan.id(), output);
            if (!Boolean.TRUE.equals(output.get("ok"))) {
                executed.add(stepOutput(plan, output, "mcp.transaction.step_failed"));
                throw new StepFailed(plan.id(), null);
            }
            executed.add(stepOutput(plan, output, null));
        }
        return List.copyOf(executed);
    }

    private static Map<String, Object> transactionOutput(
        final AuthoringTransactionResult<List<Map<String, Object>>> result,
        final List<Map<String, Object>> executed
    ) {
        final Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", result.successful());
        output.put("outcome", result.outcome().name());
        output.put("steps", List.copyOf(executed));
        result.receipt().ifPresent(receipt -> output.put("receipt", receiptPayload(receipt)));
        result.diagnosticId().ifPresent(id -> output.put("diagnosticId", id));
        return Collections.unmodifiableMap(output);
    }

    private static Map<String, Object> stepOutput(
        final StepPlan plan,
        final Map<String, Object> output,
        final String diagnosticId
    ) {
        final Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", plan.id());
        step.put("tool", plan.tool());
        step.put("output", deepCopy(output));
        if (diagnosticId != null) step.put("diagnosticId", diagnosticId);
        return Collections.unmodifiableMap(step);
    }

    private static Map<String, Object> receiptPayload(final AuthoringTransactionReceipt receipt) {
        final Map<String, Object> value = new LinkedHashMap<>();
        value.put("transactionId", receipt.transactionId());
        value.put("label", receipt.label());
        value.put("historyBefore", historyPayload(receipt.historyBefore()));
        value.put("historyAfter", historyPayload(receipt.historyAfter()));
        receipt.historyEntryId().ifPresent(id -> value.put("historyEntryId", id));
        return Collections.unmodifiableMap(value);
    }

    private static Map<String, Object> historyPayload(final HistorySnapshot snapshot) {
        return immutableMap(
            entry("availability", snapshot.availability().name()),
            entry("generation", snapshot.generation()),
            entry("revision", snapshot.revision()),
            entry("position", snapshot.position()),
            entry("canUndo", snapshot.canUndo()),
            entry("canRedo", snapshot.canRedo())
        );
    }

    private static Object resolveValue(
        final Object value,
        final Map<String, Map<String, Object>> outputs
    ) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> object = stringKeyedMap(map);
            if (object.size() == 1 && object.containsKey("$ref")) {
                final Reference reference = parseReference(object.get("$ref"));
                final Map<String, Object> source = outputs.get(reference.step());
                if (source == null) {
                    throw rejectedRequest("mcp.transaction.invalid_reference");
                }
                return deepCopy(resolvePointer(source, reference.pointer()));
            }
            final Map<String, Object> resolved = new LinkedHashMap<>();
            object.forEach((key, item) -> resolved.put(key, resolveValue(item, outputs)));
            return Collections.unmodifiableMap(resolved);
        }
        if (value instanceof List<?> list) {
            final List<Object> resolved = new ArrayList<>(list.size());
            for (Object item : list) resolved.add(resolveValue(item, outputs));
            return List.copyOf(resolved);
        }
        return value;
    }

    private static void validateReferences(
        final Object value,
        final Map<String, Object> inputSchema,
        final Map<String, ToolDescriptor> priorDescriptors,
        final String currentId
    ) {
        validateReferences(
            value,
            inputSchema,
            priorDescriptors,
            currentId,
            List.of()
        );
    }

    private static void validateReferences(
        final Object value,
        final Map<String, Object> inputSchema,
        final Map<String, ToolDescriptor> priorDescriptors,
        final String currentId,
        final List<String> path
    ) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> object = stringKeyedMap(map);
            if (object.size() == 1 && object.containsKey("$ref")) {
                final Reference reference = parseReference(object.get("$ref"));
                final ToolDescriptor source = priorDescriptors.get(reference.step());
                if (currentId.equals(reference.step()) || source == null) {
                    throw rejectedRequest("mcp.transaction.invalid_reference");
                }
                if (!source.outputSchema().isEmpty() && !inputSchema.isEmpty()) {
                    final List<Map<String, Object>> sourceSchemas =
                        McpJsonSchema.schemasAtPath(
                            source.outputSchema(),
                            pointerTokens(reference.pointer())
                        );
                    final List<Map<String, Object>> destinationSchemas =
                        McpJsonSchema.schemasAtPath(inputSchema, path);
                    if (!McpJsonSchema.compatible(sourceSchemas, destinationSchemas)) {
                        throw rejectedRequest(
                            "mcp.transaction.reference_schema_incompatible"
                        );
                    }
                }
                return;
            }
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                validateReferences(
                    entry.getValue(),
                    inputSchema,
                    priorDescriptors,
                    currentId,
                    append(path, entry.getKey())
                );
            }
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                validateReferences(
                    list.get(index),
                    inputSchema,
                    priorDescriptors,
                    currentId,
                    append(path, Integer.toString(index))
                );
            }
        }
    }

    private static boolean containsReference(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> object = stringKeyedMap(map);
            if (object.size() == 1 && object.containsKey("$ref")) return true;
            return object.values().stream().anyMatch(McpTransactionDomain::containsReference);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(McpTransactionDomain::containsReference);
        }
        return false;
    }

    private static List<String> append(final List<String> path, final String token) {
        final ArrayList<String> result = new ArrayList<>(path.size() + 1);
        result.addAll(path);
        result.add(token);
        return List.copyOf(result);
    }

    private static Reference parseReference(final Object value) {
        if (value instanceof String shorthand) {
            final int separator = shorthand.indexOf('#');
            if (separator <= 0) throw rejectedRequest("mcp.transaction.invalid_reference");
            return new Reference(
                shorthand.substring(0, separator),
                shorthand.substring(separator + 1)
            );
        }
        final Map<String, Object> object = stringKeyedMap(requiredMap(value));
        only(object, "step", "pointer");
        return new Reference(
            requiredString(object, "step"),
            requiredString(object, "pointer")
        );
    }

    private static Object resolvePointer(final Object root, final String pointer) {
        validatePointer(pointer);
        if (pointer.isEmpty()) return root;
        Object current = root;
        for (String encoded : pointer.substring(1).split("/", -1)) {
            final String token = decodePointerToken(encoded);
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    throw rejectedRequest("mcp.transaction.invalid_reference");
                }
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                if (!token.matches("0|[1-9][0-9]*")) {
                    throw rejectedRequest("mcp.transaction.invalid_reference");
                }
                final int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException failure) {
                    throw rejectedRequest("mcp.transaction.invalid_reference");
                }
                if (index >= list.size()) {
                    throw rejectedRequest("mcp.transaction.invalid_reference");
                }
                current = list.get(index);
            } else {
                throw rejectedRequest("mcp.transaction.invalid_reference");
            }
        }
        return current;
    }

    private static void validatePointer(final String pointer) {
        pointerTokens(pointer);
    }

    private static List<String> pointerTokens(final String pointer) {
        if (pointer == null || (!pointer.isEmpty() && !pointer.startsWith("/"))) {
            throw rejectedRequest("mcp.transaction.invalid_reference");
        }
        if (pointer.isEmpty()) return List.of();
        final ArrayList<String> result = new ArrayList<>();
        for (String token : pointer.substring(1).split("/", -1)) {
            result.add(decodePointerToken(token));
        }
        return List.copyOf(result);
    }

    private static String decodePointerToken(final String encoded) {
        final StringBuilder decoded = new StringBuilder(encoded.length());
        for (int index = 0; index < encoded.length(); index++) {
            final char character = encoded.charAt(index);
            if (character != '~') {
                decoded.append(character);
                continue;
            }
            if (++index >= encoded.length()) {
                throw rejectedRequest("mcp.transaction.invalid_reference");
            }
            final char escape = encoded.charAt(index);
            if (escape == '0') decoded.append('~');
            else if (escape == '1') decoded.append('/');
            else throw rejectedRequest("mcp.transaction.invalid_reference");
        }
        return decoded.toString();
    }

    private static Map<String, Object> rejected(final String diagnosticId) {
        return immutableMap(
            entry("ok", false),
            entry("outcome", "REJECTED_REQUEST"),
            entry("steps", List.of()),
            entry("diagnosticId", diagnosticId)
        );
    }

    private static Map<String, Object> envelope(final Map<String, Object> output) {
        return immutableMap(
            entry("content", List.of(immutableMap(
                entry("type", "text"),
                entry("text", Json.stringify(output))
            ))),
            entry("structuredContent", output),
            entry("isError", !Boolean.TRUE.equals(output.get("ok")))
        );
    }

    private static Map<String, Object> toolDefinition() {
        return immutableMap(
            entry("name", TRANSACTION_EXECUTE),
            entry("title", "Execute authoring transaction"),
            entry("description", "Executes up to 64 explicitly transaction-eligible read and "
                + "undoable-write tools inside one synchronous authoring transaction. A reference "
                + "object has the form {\"$ref\":{\"step\":\"id\",\"pointer\":\"/path\"}}."),
            entry("inputSchema", transactionInputSchema()),
            entry("outputSchema", transactionOutputSchema()),
            entry("annotations", immutableMap(
                entry("readOnlyHint", false),
                entry("destructiveHint", true),
                entry("idempotentHint", false)
            ))
        );
    }

    private static Map<String, Object> transactionInputSchema() {
        final Map<String, Object> step = immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("id", immutableMap(
                    entry("type", "string"),
                    entry("pattern", STEP_ID.pattern())
                )),
                entry("tool", immutableMap(
                    entry("type", "string"),
                    entry("minLength", 1)
                )),
                entry("arguments", immutableMap(entry("type", "object")))
            )),
            entry("required", List.of("id", "tool")),
            entry("additionalProperties", false)
        );
        return immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("label", immutableMap(
                    entry("type", "string"),
                    entry("minLength", 1),
                    entry("maxLength", AuthoringTransactionOptions.MAX_LABEL_LENGTH)
                )),
                entry("steps", immutableMap(
                    entry("type", "array"),
                    entry("minItems", 1),
                    entry("maxItems", MAX_STEPS),
                    entry("items", step)
                ))
            )),
            entry("required", List.of("label", "steps")),
            entry("additionalProperties", false)
        );
    }

    private static Map<String, Object> transactionOutputSchema() {
        final Map<String, Object> history = immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("availability", immutableMap(entry("type", "string"))),
                entry("generation", immutableMap(entry("type", "integer"))),
                entry("revision", immutableMap(entry("type", "integer"))),
                entry("position", immutableMap(entry("type", "integer"))),
                entry("canUndo", immutableMap(entry("type", "boolean"))),
                entry("canRedo", immutableMap(entry("type", "boolean")))
            )),
            entry("required", List.of(
                "availability", "generation", "revision", "position", "canUndo", "canRedo"
            )),
            entry("additionalProperties", false)
        );
        final Map<String, Object> receipt = immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("transactionId", immutableMap(entry("type", "string"))),
                entry("label", immutableMap(entry("type", "string"))),
                entry("historyBefore", history),
                entry("historyAfter", history),
                entry("historyEntryId", immutableMap(entry("type", "string")))
            )),
            entry("required", List.of(
                "transactionId", "label", "historyBefore", "historyAfter"
            )),
            entry("additionalProperties", false)
        );
        final Map<String, Object> step = immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("id", immutableMap(entry("type", "string"))),
                entry("tool", immutableMap(entry("type", "string"))),
                entry("output", immutableMap(entry("type", "object"))),
                entry("diagnosticId", immutableMap(entry("type", "string")))
            )),
            entry("required", List.of("id", "tool", "output")),
            entry("additionalProperties", false)
        );
        return immutableMap(
            entry("type", "object"),
            entry("properties", immutableMap(
                entry("ok", immutableMap(entry("type", "boolean"))),
                entry("outcome", immutableMap(
                    entry("type", "string"),
                    entry("enum", List.of(
                        "COMMITTED", "NO_CHANGE", "ROLLED_BACK", "REJECTED_STALE",
                        "REJECTED_SCOPE", "UNAVAILABLE", "RECOVERY_FAILED",
                        "REJECTED_REQUEST"
                    ))
                )),
                entry("steps", immutableMap(
                    entry("type", "array"),
                    entry("items", step)
                )),
                entry("receipt", receipt),
                entry("diagnosticId", immutableMap(entry("type", "string")))
            )),
            entry("required", List.of("ok", "outcome", "steps")),
            entry("additionalProperties", false)
        );
    }

    private static Map<String, Object> stringKeyedMap(final Map<?, ?> source) {
        final Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw rejectedRequest("mcp.transaction.invalid_request");
            }
            copy.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<?, ?> requiredMap(final Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw rejectedRequest("mcp.transaction.invalid_request");
        }
        return map;
    }

    private static String requiredString(final Map<String, Object> values, final String field) {
        final Object value = values.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw rejectedRequest("mcp.transaction.invalid_request");
        }
        return text;
    }

    private static void only(final Map<String, Object> values, final String... allowed) {
        final Set<String> admitted = Set.of(allowed);
        if (!admitted.containsAll(values.keySet())) {
            throw rejectedRequest("mcp.transaction.invalid_request");
        }
    }

    private static void requireDepth(final Object value) {
        if (valueDepthExceeds(value, MAX_VALUE_DEPTH)) {
            throw rejectedRequest("mcp.transaction.payload_too_deep");
        }
    }

    private static boolean valueDepthExceeds(final Object value, final int maximum) {
        return valueDepthExceeds(value, maximum, 0);
    }

    private static boolean valueDepthExceeds(
        final Object value,
        final int maximum,
        final int depth
    ) {
        if (depth > maximum) return true;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (valueDepthExceeds(entry.getValue(), maximum, depth + 1)) return true;
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                if (valueDepthExceeds(item, maximum, depth + 1)) return true;
            }
        }
        return false;
    }

    private static Object deepCopy(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), deepCopy(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            final List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(deepCopy(item));
            return List.copyOf(copy);
        }
        return value;
    }

    private static String safeMessage(final RuntimeException failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : message;
    }

    @SafeVarargs
    private static Map<String, Object> immutableMap(
        final Map.Entry<String, Object>... entries
    ) {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            values.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(values);
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return Map.entry(key, value);
    }

    private static RequestRejected rejectedRequest(final String diagnosticId) {
        return new RequestRejected(diagnosticId);
    }

    interface ToolSource {
        Optional<ToolDescriptor> descriptor(String name);

        Map<String, Object> callRaw(String name, Map<String, Object> arguments);
    }

    record ToolDescriptor(
        McpOperationEffect effect,
        boolean transactionEligible,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema
    ) {
        ToolDescriptor {
            effect = Objects.requireNonNull(effect, "effect");
            inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "inputSchema"));
            outputSchema = Map.copyOf(Objects.requireNonNull(outputSchema, "outputSchema"));
        }
    }

    private record StepPlan(
        String id,
        String tool,
        Map<String, Object> arguments,
        ToolDescriptor descriptor
    ) {
    }

    private record Reference(String step, String pointer) {
        Reference {
            if (!STEP_ID.matcher(Objects.requireNonNull(step, "step")).matches()) {
                throw rejectedRequest("mcp.transaction.invalid_reference");
            }
            validatePointer(Objects.requireNonNull(pointer, "pointer"));
        }
    }

    private static final class CatalogToolSource implements ToolSource {
        private final McpToolCatalog catalog;

        private CatalogToolSource(final McpToolCatalog catalog) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<ToolDescriptor> descriptor(final String name) {
            final McpRegisteredTool registration;
            try {
                registration = catalog.registration(name);
            } catch (IllegalArgumentException unknownTool) {
                return Optional.empty();
            }
            return Optional.of(new ToolDescriptor(
                registration.effect(),
                registration.transactionEligible(),
                (Map<String, Object>) registration.publicDefinition().get("inputSchema"),
                (Map<String, Object>) registration.publicDefinition().get("outputSchema")
            ));
        }

        @Override
        public Map<String, Object> callRaw(
            final String name,
            final Map<String, Object> arguments
        ) {
            return catalog.callRaw(name, arguments);
        }
    }

    private static final class RequestRejected extends IllegalArgumentException {
        private final String diagnosticId;

        private RequestRejected(final String diagnosticId) {
            super(diagnosticId);
            this.diagnosticId = diagnosticId;
        }
    }

    private static final class StepFailed extends Exception {
        private StepFailed(final String stepId, final Throwable cause) {
            super("transaction step failed: " + stepId, cause);
        }
    }
}
