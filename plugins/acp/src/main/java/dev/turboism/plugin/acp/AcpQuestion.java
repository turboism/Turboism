package dev.turboism.plugin.acp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured Q&A request and response models shared across native agent providers, the internal
 * ACP JSON-RPC boundary, and the Swing question dialog.
 */
final class AcpQuestion {

    static final String REQUEST_METHOD = "session/request_question";
    private static final int MAX_ITEMS = 16;
    private static final int MAX_OPTIONS = 32;
    private static final int MAX_TEXT_CHARS = 16 * 1024;
    private static final String DEFAULT_ID_PREFIX = "q";
    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_TITLE = "title";
    private static final String KEY_QUESTIONS = "questions";
    private static final String KEY_ID = "id";
    private static final String KEY_HEADER = "header";
    private static final String KEY_QUESTION = "question";
    private static final String KEY_OPTIONS = "options";
    private static final String KEY_LABEL = "label";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_PREVIEW = "preview";
    private static final String KEY_MULTI_SELECT = "multiSelect";
    private static final String KEY_ALLOW_OTHER = "allowOther";
    private static final String KEY_IS_OTHER = "isOther";
    private static final String KEY_ALLOW_EMPTY = "allowEmpty";
    private static final String KEY_SECRET = "secret";
    private static final String KEY_IS_SECRET = "isSecret";
    private static final String KEY_PLACEHOLDER = "placeholder";
    private static final String KEY_CANCELLED = "cancelled";
    private static final String KEY_ANSWERS = "answers";
    private static final String KEY_SELECTED = "selected";
    private static final String KEY_CUSTOM_TEXT = "customText";

    private AcpQuestion() {}

    record Option(String label, String description, String preview) {
        Option {
            label = boundText(Objects.requireNonNullElse(label, "").strip());
            description = boundText(Objects.requireNonNullElse(description, "").strip());
            preview = boundText(Objects.requireNonNullElse(preview, ""));
        }
    }

    record Item(
            String id,
            String header,
            String question,
            List<Option> options,
            boolean multiSelect,
            boolean allowOther,
            boolean allowEmpty,
            boolean secret,
            String placeholder) {
        Item {
            id = boundText(Objects.requireNonNullElse(id, "").strip());
            header = boundText(Objects.requireNonNullElse(header, "").strip());
            question = boundText(Objects.requireNonNullElse(question, "").strip());
            options = List.copyOf(Objects.requireNonNullElse(options, List.of()));
            placeholder = boundText(Objects.requireNonNullElse(placeholder, "").strip());
        }
    }

    record Request(String title, List<Item> items, AcpPermissionCancellation cancellation) {
        Request {
            title = boundText(Objects.requireNonNullElse(title, "").strip());
            items = List.copyOf(Objects.requireNonNullElse(items, List.of()));
            cancellation = Objects.requireNonNullElseGet(cancellation, AcpPermissionCancellation::new);
        }

        Request(final String title, final List<Item> items) {
            this(title, items, new AcpPermissionCancellation());
        }
    }

    record Answer(String itemId, String header, String question, List<String> selectedOptions, String customText) {
        Answer {
            itemId = boundText(Objects.requireNonNullElse(itemId, "").strip());
            header = boundText(Objects.requireNonNullElse(header, "").strip());
            question = boundText(Objects.requireNonNullElse(question, "").strip());
            selectedOptions = List.copyOf(Objects.requireNonNullElse(selectedOptions, List.of()));
            customText = boundText(Objects.requireNonNullElse(customText, "").strip());
        }

        boolean hasContent() {
            return !selectedOptions.isEmpty() || !customText.isEmpty();
        }

        /** Formats selected choices and optional custom text as one concise string. */
        String formattedText() {
            if (selectedOptions.isEmpty()) {
                return customText;
            }
            final String joined = String.join(", ", selectedOptions);
            return customText.isEmpty() ? joined : joined + ", " + customText;
        }

        /** Returns all non-empty answer strings in submission order. */
        List<String> values() {
            final List<String> result = new ArrayList<>(selectedOptions.size() + 1);
            result.addAll(selectedOptions);
            if (!customText.isEmpty()) {
                result.add(customText);
            }
            return List.copyOf(result);
        }
    }

    record Response(boolean cancelled, List<Answer> answers) {
        static final Response CANCELLED = new Response(true, List.of());

        Response {
            answers = List.copyOf(Objects.requireNonNullElse(answers, List.of()));
        }

        Answer answerFor(final Item item, final int index) {
            if (item != null && !item.id().isEmpty()) {
                for (Answer answer : answers) {
                    if (item.id().equals(answer.itemId())) return answer;
                }
            }
            return index >= 0 && index < answers.size() ? answers.get(index) : null;
        }
    }

    /** Parses provider-supplied question payloads (Claude, Codex, Pi) into normalized items. */
    static List<Item> parseRawQuestions(final Object rawQuestions, final boolean defaultAllowOther) {
        if (!(rawQuestions instanceof List<?> list)) return List.of();
        final List<Item> items = new ArrayList<>();
        int index = 0;
        for (Object raw : list) {
            if (items.size() >= MAX_ITEMS) break;
            if (!(raw instanceof Map<?, ?> map)) continue;
            final String questionText = text(map.get(KEY_QUESTION));
            if (questionText.isBlank()) continue;
            final String id = text(map.get(KEY_ID));
            final String header = text(map.get(KEY_HEADER));
            final List<Option> options = parseOptions(map.get(KEY_OPTIONS));
            final boolean multiSelect = Boolean.TRUE.equals(map.get(KEY_MULTI_SELECT));
            final boolean explicitOther =
                    Boolean.TRUE.equals(map.get(KEY_IS_OTHER)) || Boolean.TRUE.equals(map.get(KEY_ALLOW_OTHER));
            final boolean allowOther = explicitOther || defaultAllowOther || options.isEmpty();
            final boolean allowEmpty = Boolean.TRUE.equals(map.get(KEY_ALLOW_EMPTY));
            final boolean secret =
                    Boolean.TRUE.equals(map.get(KEY_IS_SECRET)) || Boolean.TRUE.equals(map.get(KEY_SECRET));
            final String placeholder = text(map.get(KEY_PLACEHOLDER));
            items.add(new Item(
                    id.isEmpty() ? DEFAULT_ID_PREFIX + (index + 1) : id,
                    header,
                    questionText,
                    options,
                    multiSelect,
                    allowOther,
                    allowEmpty,
                    secret,
                    placeholder));
            index++;
        }
        return List.copyOf(items);
    }

    private static List<Option> parseOptions(final Object rawOptions) {
        if (!(rawOptions instanceof List<?> list)) return List.of();
        final List<Option> options = new ArrayList<>();
        for (Object raw : list) {
            if (options.size() >= MAX_OPTIONS) break;
            if (raw instanceof String label) {
                if (!label.isBlank()) options.add(new Option(label, "", ""));
            } else if (raw instanceof Map<?, ?> map) {
                final String label = text(map.get(KEY_LABEL));
                if (label.isBlank()) continue;
                options.add(new Option(label, text(map.get(KEY_DESCRIPTION)), text(map.get(KEY_PREVIEW))));
            }
        }
        return List.copyOf(options);
    }

    static Map<String, Object> toRequestParams(final String sessionId, final String title, final List<Item> items) {
        final List<Map<String, Object>> serialized = new ArrayList<>(items.size());
        for (Item item : items) {
            final List<Map<String, Object>> opts =
                    new ArrayList<>(item.options().size());
            for (Option option : item.options()) {
                final Map<String, Object> entry = new LinkedHashMap<>();
                entry.put(KEY_LABEL, option.label());
                if (!option.description().isEmpty()) entry.put(KEY_DESCRIPTION, option.description());
                if (!option.preview().isEmpty()) entry.put(KEY_PREVIEW, option.preview());
                opts.add(entry);
            }
            final Map<String, Object> q = new LinkedHashMap<>();
            q.put(KEY_ID, item.id());
            q.put(KEY_HEADER, item.header());
            q.put(KEY_QUESTION, item.question());
            q.put(KEY_OPTIONS, opts);
            q.put(KEY_MULTI_SELECT, item.multiSelect());
            q.put(KEY_ALLOW_OTHER, item.allowOther());
            q.put(KEY_ALLOW_EMPTY, item.allowEmpty());
            q.put(KEY_SECRET, item.secret());
            if (!item.placeholder().isEmpty()) q.put(KEY_PLACEHOLDER, item.placeholder());
            serialized.add(q);
        }
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put(KEY_SESSION_ID, Objects.requireNonNullElse(sessionId, ""));
        params.put(KEY_TITLE, Objects.requireNonNullElse(title, ""));
        params.put(KEY_QUESTIONS, serialized);
        return params;
    }

    static Request fromRequestParams(final Map<String, Object> params) {
        if (params == null) return new Request("", List.of());
        final String title = text(params.get(KEY_TITLE));
        final List<Item> items = parseRawQuestions(params.get(KEY_QUESTIONS), false);
        return new Request(title, items);
    }

    static Map<String, Object> toResponseResult(final Response response) {
        if (response == null || response.cancelled()) {
            return Map.of(KEY_CANCELLED, true, KEY_ANSWERS, List.of());
        }
        final List<Map<String, Object>> serialized =
                new ArrayList<>(response.answers().size());
        for (Answer answer : response.answers()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(KEY_ID, answer.itemId());
            entry.put(KEY_HEADER, answer.header());
            entry.put(KEY_QUESTION, answer.question());
            entry.put(KEY_SELECTED, answer.selectedOptions());
            entry.put(KEY_CUSTOM_TEXT, answer.customText());
            serialized.add(entry);
        }
        return Map.of(KEY_CANCELLED, false, KEY_ANSWERS, serialized);
    }

    static Response fromResponseResult(final Map<String, Object> result) {
        if (result == null || Boolean.TRUE.equals(result.get(KEY_CANCELLED))) {
            return Response.CANCELLED;
        }
        if (!(result.get(KEY_ANSWERS) instanceof List<?> list)) {
            return Response.CANCELLED;
        }
        final List<Answer> answers = new ArrayList<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> map)) continue;
            final List<String> selected = new ArrayList<>();
            if (map.get(KEY_SELECTED) instanceof List<?> rawSelected) {
                for (Object item : rawSelected) {
                    if (item instanceof String label && !label.isBlank()) {
                        selected.add(label);
                    }
                }
            }
            answers.add(new Answer(
                    text(map.get(KEY_ID)),
                    text(map.get(KEY_HEADER)),
                    text(map.get(KEY_QUESTION)),
                    selected,
                    text(map.get(KEY_CUSTOM_TEXT))));
        }
        return new Response(false, answers);
    }

    private static String text(final Object value) {
        return value instanceof String s ? s : "";
    }

    private static String boundText(final String value) {
        if (value.length() <= MAX_TEXT_CHARS) return value;
        final int cut = Character.isLowSurrogate(value.charAt(MAX_TEXT_CHARS)) ? MAX_TEXT_CHARS - 1 : MAX_TEXT_CHARS;
        return value.substring(0, cut) + "…";
    }
}
