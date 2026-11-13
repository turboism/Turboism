package dev.turboism.plugin.acp;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Translates raw ACP and MCP permission payloads into concise, human-readable summaries while
 * preserving the exact underlying arguments for optional inspection.
 */
final class AcpPermissionFormatter {

    private static final int MAX_SUMMARY_ROWS = 5;
    private static final int MAX_VALUE_PREVIEW = 120;
    private static final int INDENT_SPACES = 2;
    private static final String MCP_COLON_PREFIX = "turboism: ";
    private static final String MCP_DOUBLE_UNDERSCORE_PREFIX = "mcp__turboism__";
    private static final String MCP_SINGLE_UNDERSCORE_PREFIX = "mcp_turboism_";
    private static final List<String> IGNORED_UUID_KEYS = List.of(
            "planId",
            "snapshotId",
            "authoringSnapshotId",
            "documentSnapshotId",
            "historyDocumentBinding",
            "historyManagerBinding",
            "expectedGeneration",
            "expectedRevision");

    private AcpPermissionFormatter() {}

    record SummaryRow(String label, String value) {}

    record FormattedPermission(String headline, String toolBadge, List<SummaryRow> summaryRows, String rawDetails) {}

    /** Returns a canonical session-scoped key identifying the requested tool or capability. */
    static String approvalKey(final AcpListener.PermissionRequest request) {
        final String clean = normalizeToolName(request.title());
        if (!clean.isEmpty()) return clean;
        return Objects.requireNonNullElse(request.kind(), "tool").strip().toLowerCase(Locale.ROOT);
    }

    static String normalizeToolName(final String rawTitle) {
        String title = Objects.requireNonNullElse(rawTitle, "").strip();
        if (title.regionMatches(true, 0, MCP_COLON_PREFIX, 0, MCP_COLON_PREFIX.length())) {
            title = title.substring(MCP_COLON_PREFIX.length()).strip();
        } else if (title.startsWith(MCP_DOUBLE_UNDERSCORE_PREFIX)) {
            title = title.substring(MCP_DOUBLE_UNDERSCORE_PREFIX.length());
        } else if (title.startsWith(MCP_SINGLE_UNDERSCORE_PREFIX)) {
            title = title.substring(MCP_SINGLE_UNDERSCORE_PREFIX.length());
        }
        if (title.startsWith("turboism_") && !title.contains(".")) {
            title = title.replace('_', '.');
        }
        return title;
    }

    static FormattedPermission format(
            final PluginLocalization localization, final AcpListener.PermissionRequest request) {
        final String toolName = normalizeToolName(request.title());
        final String headline = resolveHeadline(localization, toolName, request.kind());
        final Object parsed = parseUnwrappedJson(request.details());
        final List<SummaryRow> rows = extractSummaryRows(localization, parsed);
        final String prettyRaw = formatRawDetails(parsed, request.details());
        return new FormattedPermission(headline, toolName, rows, prettyRaw);
    }

    private static String resolveHeadline(
            final PluginLocalization localization, final String toolName, final String kind) {
        final String lower = toolName.toLowerCase(Locale.ROOT);
        final String lowerKind = Objects.requireNonNullElse(kind, "").toLowerCase(Locale.ROOT);
        if (lower.startsWith("turboism.parameters.")) {
            return localization.text("permission.desc.parameters");
        }
        if (lower.startsWith("turboism.authoring.bindings.")) {
            return localization.text("permission.desc.bindings");
        }
        if (lower.startsWith("turboism.authoring.keyforms.") || lower.startsWith("turboism.rig.")) {
            return localization.text("permission.desc.keyforms");
        }
        if (lower.startsWith("turboism.objects.") || lower.startsWith("turboism.model.")) {
            return localization.text("permission.desc.objects");
        }
        if (lower.startsWith("turboism.atlas.")
                || lower.startsWith("turboism.textures.")
                || lower.startsWith("turboism.images.")) {
            return localization.text("permission.desc.atlas");
        }
        if (lower.startsWith("turboism.clipmask.") || lower.startsWith("turboism.masks.")) {
            return localization.text("permission.desc.clipmask");
        }
        if (lower.startsWith("turboism.glue.")) {
            return localization.text("permission.desc.glue");
        }
        if (lower.startsWith("turboism.history.")) {
            return localization.text("permission.desc.history");
        }
        if (lower.equals("turboism.documents.new_model")) {
            return localization.text("permission.desc.new-model");
        }
        if (lower.startsWith("turboism.documents.")) {
            return localization.text("permission.desc.documents");
        }
        if (lower.startsWith("turboism.editor_commands.")) {
            return localization.text("permission.desc.editor-command");
        }
        if (lower.startsWith("turboism.render.")) {
            return localization.text("permission.desc.render");
        }
        if ("execute".equals(lowerKind) || lower.equals("bash") || lower.contains("command")) {
            return localization.text("permission.desc.execute");
        }
        if ("edit".equals(lowerKind) || "write".equals(lowerKind) || lower.contains("edit") || lower.contains("file")) {
            return localization.text("permission.desc.edit");
        }
        final String fallbackName = toolName.isEmpty() ? localization.text("sender.tool") : toolName;
        return localization.format("permission.desc.generic", fallbackName);
    }

    /** Unwraps nested JSON strings such as Pi's {@code {"message": "{...}"}} confirmation wrapper. */
    static Object parseUnwrappedJson(final String rawDetails) {
        if (rawDetails == null || rawDetails.isBlank()) return null;
        try {
            Object root = Json.parseObject(rawDetails.getBytes(StandardCharsets.UTF_8));
            if (root instanceof Map<?, ?> map && map.size() == 1 && map.get("message") instanceof String nested) {
                final String trimmed = nested.strip();
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try {
                        root = Json.parseObject(trimmed.getBytes(StandardCharsets.UTF_8));
                    } catch (RuntimeException keepOuter) {
                        return trimmed;
                    }
                } else {
                    return trimmed;
                }
            }
            return root;
        } catch (RuntimeException notJsonObject) {
            return null;
        }
    }

    private static List<SummaryRow> extractSummaryRows(final PluginLocalization localization, final Object parsed) {
        if (parsed instanceof String plain && !plain.isBlank()) {
            return List.of(new SummaryRow(localization.text("permission.field.summary"), truncate(plain)));
        }
        if (!(parsed instanceof Map<?, ?> map) || map.isEmpty()) {
            return List.of();
        }
        final List<SummaryRow> rows = new ArrayList<>();
        if (map.get("documentId") instanceof String docId && !docId.isBlank()) {
            rows.add(new SummaryRow(localization.text("permission.field.project"), truncate(docId)));
        }
        addStringField(rows, map, "command", localization.text("permission.field.command"));
        addStringField(rows, map, "cmd", localization.text("permission.field.command"));
        addStringField(rows, map, "commandId", localization.text("permission.field.command"));
        addStringField(rows, map, "path", localization.text("permission.field.target"));
        addStringField(rows, map, "file_path", localization.text("permission.field.target"));
        addStringField(rows, map, "objectId", localization.text("permission.field.target"));
        addStringField(rows, map, "parameterId", localization.text("permission.field.target"));
        addStringField(rows, map, "name", localization.text("permission.field.target"));
        if (map.get("operations") instanceof List<?> ops && !ops.isEmpty()) {
            rows.add(new SummaryRow(
                    localization.text("permission.field.changes"),
                    localization.format("permission.items-count", ops.size())));
        } else if (map.get("edits") instanceof List<?> edits && !edits.isEmpty()) {
            rows.add(new SummaryRow(
                    localization.text("permission.field.changes"),
                    localization.format("permission.items-count", edits.size())));
        } else if (map.get("entries") instanceof List<?> entries && !entries.isEmpty()) {
            rows.add(new SummaryRow(
                    localization.text("permission.field.changes"),
                    localization.format("permission.items-count", entries.size())));
        }
        addStringField(rows, map, "operationId", localization.text("permission.field.operation"));
        if (rows.isEmpty()) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (rows.size() >= MAX_SUMMARY_ROWS) break;
                final String key = String.valueOf(entry.getKey());
                if (IGNORED_UUID_KEYS.contains(key)) continue;
                final Object val = entry.getValue();
                if (val instanceof String || val instanceof Number || val instanceof Boolean) {
                    rows.add(new SummaryRow(key, truncate(String.valueOf(val))));
                }
            }
        }
        return rows.size() <= MAX_SUMMARY_ROWS ? List.copyOf(rows) : List.copyOf(rows.subList(0, MAX_SUMMARY_ROWS));
    }

    private static void addStringField(
            final List<SummaryRow> rows, final Map<?, ?> map, final String key, final String label) {
        if (rows.size() >= MAX_SUMMARY_ROWS) return;
        if (map.get(key) instanceof String value && !value.isBlank()) {
            rows.add(new SummaryRow(label, truncate(value)));
        }
    }

    private static String truncate(final String value) {
        final String singleLine = value.replaceAll("\\s+", " ").strip();
        if (singleLine.length() <= MAX_VALUE_PREVIEW) return singleLine;
        final int cut = Character.isLowSurrogate(singleLine.charAt(MAX_VALUE_PREVIEW))
                ? MAX_VALUE_PREVIEW - 1
                : MAX_VALUE_PREVIEW;
        return singleLine.substring(0, cut) + "…";
    }

    private static String formatRawDetails(final Object parsed, final String fallbackRaw) {
        if (parsed instanceof String plain) return plain;
        if (parsed instanceof Map<?, ?> || parsed instanceof List<?>) {
            final StringBuilder out = new StringBuilder();
            appendPrettyJson(out, parsed, 0);
            return out.toString();
        }
        final String raw = Objects.requireNonNullElse(fallbackRaw, "").strip();
        return "{}".equals(raw) ? "" : raw;
    }

    private static void appendPrettyJson(final StringBuilder out, final Object value, final int depth) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(out, depth + 1);
                out.append(quoteJsonScalar(String.valueOf(entry.getKey()))).append(": ");
                appendPrettyJson(out, entry.getValue(), depth + 1);
                if (++index < map.size()) out.append(',');
                out.append('\n');
            }
            indent(out, depth);
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int index = 0; index < list.size(); index++) {
                indent(out, depth + 1);
                appendPrettyJson(out, list.get(index), depth + 1);
                if (index + 1 < list.size()) out.append(',');
                out.append('\n');
            }
            indent(out, depth);
            out.append(']');
        } else if (value == null || value instanceof Number || value instanceof Boolean) {
            out.append(String.valueOf(value));
        } else {
            out.append(quoteJsonScalar(String.valueOf(value)));
        }
    }

    private static String quoteJsonScalar(final String text) {
        final String arrayWrapped = Json.stringify(List.of(text));
        return arrayWrapped.substring(1, arrayWrapped.length() - 1);
    }

    private static void indent(final StringBuilder out, final int depth) {
        out.append(" ".repeat(Math.max(0, depth * INDENT_SPACES)));
    }
}
