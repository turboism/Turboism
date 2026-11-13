package dev.turboism.plugin.acp;

import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Normalizes agent plan and todo updates from ACP v1 {@code plan} notifications and native
 * harness todo tools (Claude {@code TodoWrite}, Codex {@code update_plan}, and Pi {@code todo})
 * into one immutable checklist for the Paseo-style composer tasks pill.
 */
final class AcpPlanTracker {

    static final int MAX_ITEMS = 64;
    private static final int MAX_TEXT_LENGTH = 512;
    private static final String ACTION_CREATE = "create";
    private static final String ACTION_UPDATE = "update";
    private static final String ACTION_DELETE = "delete";
    private static final String ACTION_CLEAR = "clear";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_DELETED = "deleted";

    private final Map<Integer, IndexedTask> incrementalTasks = new LinkedHashMap<>();
    private int nextIncrementalId = 1;
    private List<Item> items = List.of();

    enum Status {
        PENDING,
        IN_PROGRESS,
        COMPLETED;

        static Status parse(final String raw) {
            if (raw == null) return PENDING;
            final String normalized = raw.strip().toLowerCase(Locale.ROOT);
            if (STATUS_COMPLETED.equals(normalized) || "done".equals(normalized)) return COMPLETED;
            if (STATUS_IN_PROGRESS.equals(normalized) || "active".equals(normalized) || "running".equals(normalized)) {
                return IN_PROGRESS;
            }
            return PENDING;
        }
    }

    record Item(String content, String activeLabel, Status status) {
        Item {
            content = bound(Objects.requireNonNullElse(content, "").strip());
            activeLabel = bound(Objects.requireNonNullElse(activeLabel, "").strip());
            status = Objects.requireNonNullElse(status, Status.PENDING);
        }

        String displayText() {
            return status == Status.IN_PROGRESS && !activeLabel.isEmpty() ? activeLabel : content;
        }
    }

    private record IndexedTask(int id, String subject, String activeForm, Status status) {}

    synchronized void clear() {
        incrementalTasks.clear();
        nextIncrementalId = 1;
        items = List.of();
    }

    synchronized List<Item> items() {
        return items;
    }

    /** Replaces the plan from a structured ACP {@code plan} update list. */
    synchronized boolean applyPlanEntries(final List<Item> updated) {
        final List<Item> cleaned = updated.stream()
                .filter(item -> !item.content().isEmpty())
                .limit(MAX_ITEMS)
                .toList();
        if (cleaned.equals(items)) return false;
        incrementalTasks.clear();
        items = List.copyOf(cleaned);
        return true;
    }

    /**
     * Inspects a tool call's title and JSON input to extract todo updates from native agents that
     * represent task tracking as tool invocations rather than ACP {@code plan} notifications.
     */
    synchronized boolean observeToolInput(final String toolTitle, final String rawJsonInput) {
        if (rawJsonInput == null || rawJsonInput.isBlank()) return false;
        final String normalizedTitle =
                Objects.requireNonNullElse(toolTitle, "").strip().toLowerCase(Locale.ROOT);
        if (!isTodoToolName(normalizedTitle)) return false;
        final Map<String, Object> parsed;
        try {
            final Object root = Json.parseObject(rawJsonInput.getBytes(StandardCharsets.UTF_8));
            parsed = asObject(root);
        } catch (RuntimeException invalid) {
            return false;
        }
        if (parsed.containsKey("todos") && parsed.get("todos") instanceof List<?> todos) {
            return applyClaudeTodos(todos);
        }
        if (parsed.containsKey("plan") && parsed.get("plan") instanceof List<?> steps) {
            return applyCodexPlan(steps);
        }
        if (parsed.containsKey("action")) {
            return applyPiTodoAction(parsed);
        }
        return false;
    }

    private static boolean isTodoToolName(final String lowerTitle) {
        return lowerTitle.equals("todo")
                || lowerTitle.equals("todowrite")
                || lowerTitle.equals("todo_write")
                || lowerTitle.equals("update_plan")
                || lowerTitle.endsWith(": todo")
                || lowerTitle.endsWith(": todowrite")
                || lowerTitle.endsWith(": update_plan");
    }

    private boolean applyClaudeTodos(final List<?> rawTodos) {
        final List<Item> next = new ArrayList<>();
        for (Object raw : rawTodos) {
            final Map<String, Object> entry = asObject(raw);
            final String content = text(entry.get("content"));
            if (content.isBlank()) continue;
            next.add(new Item(content, text(entry.get("activeForm")), Status.parse(text(entry.get("status")))));
            if (next.size() >= MAX_ITEMS) break;
        }
        return applyPlanEntries(next);
    }

    private boolean applyCodexPlan(final List<?> rawSteps) {
        final List<Item> next = new ArrayList<>();
        for (Object raw : rawSteps) {
            final Map<String, Object> entry = asObject(raw);
            final String content = text(entry.containsKey("step") ? entry.get("step") : entry.get("content"));
            if (content.isBlank()) continue;
            next.add(new Item(content, "", Status.parse(text(entry.get("status")))));
            if (next.size() >= MAX_ITEMS) break;
        }
        return applyPlanEntries(next);
    }

    private boolean applyPiTodoAction(final Map<String, Object> payload) {
        final String action = text(payload.get("action")).toLowerCase(Locale.ROOT);
        switch (action) {
            case ACTION_CLEAR -> {
                final boolean hadItems = !items.isEmpty();
                clear();
                return hadItems;
            }
            case ACTION_CREATE -> {
                if (payload.get("tasks") instanceof List<?> batch) {
                    for (Object raw : batch) {
                        addIncrementalTask(asObject(raw));
                    }
                } else {
                    addIncrementalTask(payload);
                }
                return rebuildFromIncremental();
            }
            case ACTION_UPDATE -> {
                final int id = intValue(payload.get("id"));
                final IndexedTask existing = incrementalTasks.get(id);
                if (existing == null) return false;
                final String rawStatus = text(payload.get("status"));
                if (STATUS_DELETED.equalsIgnoreCase(rawStatus)) {
                    incrementalTasks.remove(id);
                } else {
                    final String subject = text(payload.get("subject"));
                    final String activeForm = text(payload.get("activeForm"));
                    final Status status = rawStatus.isEmpty() ? existing.status() : Status.parse(rawStatus);
                    incrementalTasks.put(
                            id,
                            new IndexedTask(
                                    id,
                                    subject.isEmpty() ? existing.subject() : subject,
                                    activeForm.isEmpty() ? existing.activeForm() : activeForm,
                                    status));
                }
                return rebuildFromIncremental();
            }
            case ACTION_DELETE -> {
                final int id = intValue(payload.get("id"));
                if (incrementalTasks.remove(id) == null) return false;
                return rebuildFromIncremental();
            }
            default -> {
                return false;
            }
        }
    }

    private void addIncrementalTask(final Map<String, Object> task) {
        if (incrementalTasks.size() >= MAX_ITEMS) return;
        final String subject = text(task.get("subject"));
        if (subject.isBlank()) return;
        final int id = nextIncrementalId++;
        incrementalTasks.put(
                id, new IndexedTask(id, subject, text(task.get("activeForm")), Status.parse(text(task.get("status")))));
    }

    private boolean rebuildFromIncremental() {
        final List<Item> next = incrementalTasks.values().stream()
                .map(task -> new Item(task.subject(), task.activeForm(), task.status()))
                .limit(MAX_ITEMS)
                .toList();
        if (next.equals(items)) return false;
        items = List.copyOf(next);
        return true;
    }

    static List<Item> parseRawEntries(final Object rawEntries) {
        if (!(rawEntries instanceof List<?> list)) return List.of();
        final List<Item> parsed = new ArrayList<>();
        for (Object raw : list) {
            final Map<String, Object> entry = asObject(raw);
            final String content = text(entry.containsKey("content") ? entry.get("content") : entry.get("step"));
            if (content.isBlank()) continue;
            parsed.add(new Item(content, text(entry.get("activeForm")), Status.parse(text(entry.get("status")))));
            if (parsed.size() >= MAX_ITEMS) break;
        }
        return List.copyOf(parsed);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(final Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(final Object value) {
        return value instanceof String s ? s.strip() : "";
    }

    private static int intValue(final Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static String bound(final String text) {
        if (text.length() <= MAX_TEXT_LENGTH) return text;
        final int cut = Character.isLowSurrogate(text.charAt(MAX_TEXT_LENGTH)) ? MAX_TEXT_LENGTH - 1 : MAX_TEXT_LENGTH;
        return text.substring(0, cut) + "…";
    }
}
