package dev.turboism.plugin.acp;

import static dev.turboism.plugin.acp.NativeAgentTransport.jsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Keep native MCP/Pi and Claude content blocks typed through the ACP UI boundary. */
final class NativeToolContent {
    private NativeToolContent() {}

    static List<Map<String, Object>> from(final Object result) {
        final ArrayList<Map<String, Object>> content = new ArrayList<>();
        if (result instanceof Map<?, ?> envelope && envelope.get("content") instanceof List<?> blocks) {
            append(content, blocks);
            if (envelope.get("structuredContent") != null) text(content, jsonValue(envelope.get("structuredContent")));
            else if (envelope.get("details") != null && !Map.of().equals(envelope.get("details")))
                text(content, jsonValue(envelope.get("details")));
        } else if (result instanceof List<?> blocks) {
            append(content, blocks);
        } else if (result instanceof Map<?, ?> block && block.get("type") instanceof String) {
            append(content, List.of(block));
        } else {
            text(content, result instanceof String string ? string : jsonValue(result));
        }
        return List.copyOf(content);
    }

    private static void append(final List<Map<String, Object>> target, final List<?> blocks) {
        for (Object value : blocks) {
            if (!(value instanceof Map<?, ?> block)) {
                text(target, value instanceof String string ? string : jsonValue(value));
                continue;
            }
            final Object type = block.get("type");
            if ("text".equals(type) && block.get("text") instanceof String text) {
                text(target, text);
            } else if ("image".equals(type)) {
                // MCP/Pi carry data directly; Claude uses an explicit base64 source.
                if (block.get("data") instanceof String data && block.get("mimeType") instanceof String mime)
                    image(target, mime, data);
                else if (block.get("source") instanceof Map<?, ?> source
                        && "base64".equals(source.get("type"))
                        && source.get("data") instanceof String data
                        && source.get("media_type") instanceof String mime) image(target, mime, data);
                else text(target, "[Tool image unavailable: unsupported image representation]");
            } else if ("resource".equals(type) && block.get("resource") instanceof Map<?, ?> resource) {
                target.add(wrap(Map.of("type", "resource", "resource", resource)));
            } else if ("resource_link".equals(type)
                    && block.get("uri") instanceof String uri
                    && block.get("name") instanceof String name) {
                target.add(wrap(Map.of("type", "resource_link", "uri", uri, "name", name)));
            } else {
                // Never dump unsupported binary content (e.g. audio) as base64 chat text.
                text(target, "[Unsupported tool content: " + String.valueOf(type) + "]");
            }
        }
    }

    private static void image(final List<Map<String, Object>> target, final String mime, final String data) {
        if (mime.startsWith("image/")) target.add(wrap(Map.of("type", "image", "mimeType", mime, "data", data)));
        else text(target, "[Tool image unavailable: invalid MIME type]");
    }

    private static void text(final List<Map<String, Object>> target, final String text) {
        target.add(wrap(Map.of("type", "text", "text", text)));
    }

    private static Map<String, Object> wrap(final Map<String, ?> block) {
        return Map.of("type", "content", "content", block);
    }
}
