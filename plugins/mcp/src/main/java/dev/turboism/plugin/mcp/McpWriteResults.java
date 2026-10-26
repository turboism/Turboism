package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Common bounded wire semantics for retained transactional operation results. */
final class McpWriteResults {
    static Map<String, Object> out(
            String id,
            String outcome,
            String code,
            Map<String, Object> receipt,
            String diagnostic,
            String before,
            String after,
            boolean verified) {
        var map = new LinkedHashMap<String, Object>();
        map.put(
                "ok",
                Set.of("APPLIED", "NO_CHANGE", "APPLIED_WITH_READBACK_WARNING").contains(outcome));
        map.put("operationId", id);
        map.put("outcome", outcome);
        map.put("code", code);
        map.put("receipt", receipt);
        map.put("diagnosticId", diagnostic);
        map.put("beforeHash", before);
        map.put("expectedAfterHash", after);
        map.put("readbackVerified", verified);
        return Collections.unmodifiableMap(map);
    }

    static Map<String, Object> receipt(AuthoringTransactionReceipt r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("transactionId", r.transactionId());
        m.put("historyEntryId", r.historyEntryId().orElse(null));
        m.put("label", r.label());
        m.put("historyBefore", history(r.historyBefore()));
        m.put("historyAfter", history(r.historyAfter()));
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Object> history(dev.turboism.sdk.cubism.history.HistorySnapshot h) {
        return Map.of(
                "generation",
                h.generation(),
                "revision",
                h.revision(),
                "documentBindingId",
                h.documentBindingId(),
                "managerBindingId",
                h.managerBindingId());
    }

    static Map<String, Object> envelope(Map<String, Object> value) {
        var safe = McpJsonSupport.encodable(value);
        return Map.of(
                "content",
                List.of(Map.of("type", "text", "text", Json.stringify(safe))),
                "structuredContent",
                safe,
                "isError",
                !Boolean.TRUE.equals(value.get("ok")));
    }

    private static Object canonical(Object v) {
        if (v instanceof Map<?, ?> m) {
            var sorted = new TreeMap<String, Object>();
            m.forEach((k, x) -> sorted.put((String) k, canonical(x)));
            return sorted;
        }
        if (v instanceof List<?> l)
            return l.stream().map(McpWriteResults::canonical).toList();
        return v;
    }

    static String digest(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(Json.stringify(Map.of("value", canonical(value)))
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> schema() {
        var history = object(Map.of(
                "generation",
                Map.of("type", "integer"),
                "revision",
                Map.of("type", "integer"),
                "documentBindingId",
                Map.of("type", "string"),
                "managerBindingId",
                Map.of("type", "string")));
        var receipt = object(Map.of(
                "transactionId",
                id(),
                "historyEntryId",
                nullable(),
                "label",
                id(),
                "historyBefore",
                history,
                "historyAfter",
                history));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("ok", Map.of("type", "boolean"));
        fields.put("operationId", Map.of("type", "string", "maxLength", 128));
        fields.put(
                "outcome",
                Map.of(
                        "enum",
                        List.of(
                                "RUNNING",
                                "APPLIED",
                                "NO_CHANGE",
                                "NOT_APPLIED",
                                "ROLLED_BACK",
                                "OUTCOME_UNKNOWN",
                                "APPLIED_WITH_READBACK_WARNING")));
        fields.put("code", id());
        fields.put("receipt", Map.of("oneOf", List.of(receipt, Map.of("type", "null"))));
        fields.put("diagnosticId", nullable());
        fields.put("beforeHash", nullable());
        fields.put("expectedAfterHash", nullable());
        fields.put("readbackVerified", Map.of("type", "boolean"));
        return object(fields);
    }

    private static Map<String, Object> id() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 4096);
    }

    private static Map<String, Object> nullable() {
        return Map.of("type", List.of("string", "null"));
    }

    private static Map<String, Object> object(Map<String, Object> fields) {
        return Map.of(
                "type",
                "object",
                "properties",
                fields,
                "required",
                List.copyOf(fields.keySet()),
                "additionalProperties",
                false);
    }
}
