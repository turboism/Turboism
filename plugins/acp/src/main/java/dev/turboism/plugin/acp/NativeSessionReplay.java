package dev.turboism.plugin.acp;

import static dev.turboism.plugin.acp.NativeAgentTransport.list;
import static dev.turboism.plugin.acp.NativeAgentTransport.object;
import static dev.turboism.plugin.acp.NativeAgentTransport.text;

import dev.turboism.sdk.json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Replays persisted conversation history from native provider storage into ACP updates. */
final class NativeSessionReplay {

    private static final String USER_CHUNK = "user_message_chunk";
    private static final String AGENT_CHUNK = "agent_message_chunk";
    private static final String THOUGHT_CHUNK = "agent_thought_chunk";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";
    private static final String PROJECTS_DIR = "projects";
    private static final String DEFAULT_CLAUDE_DIR = ".claude";
    private static final String JSONL_SUFFIX = ".jsonl";
    private static final long MAX_CLAUDE_HISTORY_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_CLAUDE_HISTORY_LINES = 50_000;
    private static final int MAX_PROJECT_WALK_DEPTH = 2;
    private static final Set<String> CODEX_TOOL_TYPES =
            Set.of("commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall", "webSearch");

    private NativeSessionReplay() {}

    /** Emits ACP session updates for persisted Codex turns returned by {@code thread/resume}. */
    static void replayCodexTurns(
            final NativeAgentProvider provider, final NativeAgentTransport host, final List<Object> turns) {
        for (Object rawTurn : turns) {
            final Map<String, Object> turn = object(rawTurn);
            for (Object rawItem : list(turn.get("items"))) {
                replayCodexItem(provider, host, object(rawItem));
            }
        }
    }

    private static void replayCodexItem(
            final NativeAgentProvider provider, final NativeAgentTransport host, final Map<String, Object> item) {
        final String type = text(item.get("type"));
        if ("userMessage".equals(type)) {
            for (Object rawBlock : list(item.get("content"))) {
                final Map<String, Object> block = object(rawBlock);
                if ("text".equals(block.get("type"))) {
                    host.text(USER_CHUNK, text(block.get("text")));
                } else if ("image".equals(block.get("type"))) {
                    emitDataUrlImage(host, text(block.get("url")));
                }
            }
            return;
        }
        if ("reasoning".equals(type)) {
            emitJoinedLines(host, THOUGHT_CHUNK, list(item.get("summary")));
            emitJoinedLines(host, THOUGHT_CHUNK, list(item.get("content")));
            return;
        }
        if ("agentMessage".equals(type)) {
            host.text(AGENT_CHUNK, text(item.get("text")));
            return;
        }
        if (!CODEX_TOOL_TYPES.contains(type)) return;
        final String id = text(item.get("id"));
        final String toolName = text(item.get("tool"));
        final String command = text(item.get("command"));
        final String title = !toolName.isBlank() ? toolName : !command.isBlank() ? command : type;
        final String rawStatus = text(item.get("status"));
        final String status = "failed".equals(rawStatus) || item.get("error") != null
                ? STATUS_FAILED
                : ("inProgress".equals(rawStatus) || STATUS_IN_PROGRESS.equals(rawStatus))
                        ? STATUS_IN_PROGRESS
                        : STATUS_COMPLETED;
        final Object input = item.containsKey("arguments")
                ? item.get("arguments")
                : item.containsKey("command")
                        ? item.get("command")
                        : item.containsKey("changes") ? item.get("changes") : item.get("query");
        final Object result = item.get("error") != null
                ? item.get("error")
                : item.containsKey("result")
                        ? item.get("result")
                        : item.containsKey("aggregatedOutput")
                                ? item.get("aggregatedOutput")
                                : item.get("contentItems");
        provider.tool(id, title, status, input, result);
    }

    /** Emits ACP session updates for Pi RPC {@code get_messages} entries. */
    static void replayPiMessages(
            final NativeAgentProvider provider, final NativeAgentTransport host, final List<Object> messages) {
        for (Object rawMessage : messages) {
            final Map<String, Object> message = object(rawMessage);
            final String role = text(message.get("role"));
            switch (role) {
                case "user" -> replayPiUserContent(host, message.get("content"));
                case "assistant" -> replayPiAssistantContent(provider, host, message.get("content"));
                case "toolResult" ->
                    provider.toolUpdate(
                            text(message.get("toolCallId")),
                            Boolean.TRUE.equals(message.get("isError")) ? STATUS_FAILED : STATUS_COMPLETED,
                            message.get("content"));
                default -> {}
            }
        }
    }

    private static void replayPiUserContent(final NativeAgentTransport host, final Object content) {
        if (content instanceof String textValue) {
            host.text(USER_CHUNK, textValue);
            return;
        }
        if (!(content instanceof List<?>)) return;
        for (Object rawBlock : list(content)) {
            final Map<String, Object> block = object(rawBlock);
            final String type = text(block.get("type"));
            if ("text".equals(type)) {
                host.text(USER_CHUNK, text(block.get("text")));
            } else if ("image".equals(type)) {
                host.image(USER_CHUNK, text(block.get("mimeType")), text(block.get("data")));
            }
        }
    }

    private static void replayPiAssistantContent(
            final NativeAgentProvider provider, final NativeAgentTransport host, final Object content) {
        if (!(content instanceof List<?>)) return;
        for (Object rawBlock : list(content)) {
            final Map<String, Object> block = object(rawBlock);
            switch (text(block.get("type"))) {
                case "thinking" -> host.text(THOUGHT_CHUNK, text(block.get("thinking")));
                case "toolCall" ->
                    provider.tool(
                            text(block.get("id")),
                            text(block.get("name")),
                            STATUS_IN_PROGRESS,
                            block.get("arguments"),
                            null);
                case "text" -> host.text(AGENT_CHUNK, text(block.get("text")));
                default -> {}
            }
        }
    }

    /** Reads Claude Code's persisted JSONL transcript for {@code sessionId} when present. */
    static void replayClaudeSession(
            final NativeAgentProvider provider, final NativeAgentTransport host, final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        final Path transcriptFile = findClaudeSessionFile(host.launch().environment(), sessionId);
        if (transcriptFile == null) return;
        try {
            if (Files.isSymbolicLink(transcriptFile)
                    || !Files.isRegularFile(transcriptFile)
                    || Files.size(transcriptFile) > MAX_CLAUDE_HISTORY_BYTES) {
                return;
            }
            try (BufferedReader reader = Files.newBufferedReader(transcriptFile, StandardCharsets.UTF_8)) {
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null && lines < MAX_CLAUDE_HISTORY_LINES) {
                    lines++;
                    if (line.isBlank()) continue;
                    final Map<String, Object> record;
                    try {
                        record = object(Json.parseObject(line.getBytes(StandardCharsets.UTF_8)));
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    replayClaudeRecord(provider, host, record);
                }
            }
        } catch (IOException ignored) {
            // Missing or unreadable history leaves the resumed session empty without breaking resume.
        }
    }

    static void replayClaudeRecord(
            final NativeAgentProvider provider, final NativeAgentTransport host, final Map<String, Object> record) {
        if (Boolean.TRUE.equals(record.get("isSidechain")) || Boolean.TRUE.equals(record.get("isMeta"))) return;
        final String type = text(record.get("type"));
        if (!"user".equals(type) && !"assistant".equals(type)) return;
        final Map<String, Object> message = object(record.get("message"));
        final Object content = message.get("content");
        if ("user".equals(type)) {
            if (content instanceof String textValue) {
                host.text(USER_CHUNK, textValue);
                return;
            }
            if (!(content instanceof List<?>)) return;
            for (Object rawBlock : list(content)) {
                final Map<String, Object> block = object(rawBlock);
                switch (text(block.get("type"))) {
                    case "text" -> host.text(USER_CHUNK, text(block.get("text")));
                    case "image" -> emitClaudeImage(host, object(block.get("source")));
                    case "tool_result" ->
                        provider.toolUpdate(
                                text(block.get("tool_use_id")),
                                Boolean.TRUE.equals(block.get("is_error")) ? STATUS_FAILED : STATUS_COMPLETED,
                                block.get("content"));
                    default -> {}
                }
            }
            return;
        }
        if (!(content instanceof List<?>)) return;
        for (Object rawBlock : list(content)) {
            final Map<String, Object> block = object(rawBlock);
            switch (text(block.get("type"))) {
                case "thinking" -> host.text(THOUGHT_CHUNK, text(block.get("thinking")));
                case "text" -> host.text(AGENT_CHUNK, text(block.get("text")));
                case "tool_use" ->
                    provider.tool(
                            text(block.get("id")),
                            text(block.get("name")),
                            STATUS_IN_PROGRESS,
                            block.get("input"),
                            null);
                default -> {}
            }
        }
    }

    private static Path findClaudeSessionFile(final Map<String, String> environment, final String sessionId) {
        final Path configDir = resolveClaudeConfigDir(environment);
        final Path projectsDir = configDir.resolve(PROJECTS_DIR);
        if (!Files.isDirectory(projectsDir) || Files.isSymbolicLink(projectsDir)) return null;
        final String targetName = sessionId + JSONL_SUFFIX;
        try (Stream<Path> entries = Files.walk(projectsDir, MAX_PROJECT_WALK_DEPTH)) {
            return entries.filter(path -> !Files.isSymbolicLink(path)
                            && Files.isRegularFile(path)
                            && targetName.equals(path.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Path resolveClaudeConfigDir(final Map<String, String> environment) {
        final String explicit = environment.get("CLAUDE_CONFIG_DIR");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit);
        final String processEnv = System.getenv("CLAUDE_CONFIG_DIR");
        if (processEnv != null && !processEnv.isBlank()) return Path.of(processEnv);
        final String home = environment.get("HOME");
        if (home != null && !home.isBlank()) return Path.of(home, DEFAULT_CLAUDE_DIR);
        return Path.of(System.getProperty("user.home"), DEFAULT_CLAUDE_DIR);
    }

    private static void emitJoinedLines(final NativeAgentTransport host, final String kind, final List<Object> parts) {
        for (Object raw : parts) {
            final String line = text(raw);
            if (!line.isBlank()) host.text(kind, line + "\n");
        }
    }

    private static void emitClaudeImage(final NativeAgentTransport host, final Map<String, Object> source) {
        if ("base64".equals(source.get("type"))) {
            host.image(USER_CHUNK, text(source.get("media_type")), text(source.get("data")));
        }
    }

    private static void emitDataUrlImage(final NativeAgentTransport host, final String url) {
        final int comma = url.indexOf(',');
        if (!url.startsWith("data:") || comma <= 5 || !url.substring(0, comma).endsWith(";base64")) return;
        final String mimeType = url.substring(5, comma - ";base64".length());
        host.image(USER_CHUNK, mimeType, url.substring(comma + 1));
    }
}
