package dev.turboism.plugin.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** User-controlled MCP workflow prompts. */
final class McpPromptCatalog {

    record Prompt(
        String name,
        String title,
        String description,
        List<Map<String, Object>> arguments,
        String text
    ) {
        Prompt {
            name = requireText(name, "name");
            title = requireText(title, "title");
            description = requireText(description, "description");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            text = requireText(text, "text");
        }

        Map<String, Object> definition() {
            return linked(
                entry("name", name),
                entry("title", title),
                entry("description", description),
                entry("arguments", arguments)
            );
        }

        Map<String, Object> render(final Map<String, Object> values) {
            if (!values.isEmpty()) {
                throw new IllegalArgumentException(name + " does not accept arguments");
            }
            return linked(
                entry("description", description),
                entry("messages", List.of(linked(
                    entry("role", "user"),
                    entry("content", linked(entry("type", "text"), entry("text", text)))
                )))
            );
        }
    }

    private final LinkedHashMap<String, Prompt> prompts;

    McpPromptCatalog(final List<Prompt> prompts) {
        this.prompts = new LinkedHashMap<>();
        for (Prompt prompt : prompts) {
            if (this.prompts.putIfAbsent(prompt.name(), prompt) != null) {
                throw new IllegalArgumentException("Duplicate MCP prompt: " + prompt.name());
            }
        }
    }

    static McpPromptCatalog defaults() {
        return new McpPromptCatalog(List.of(
            prompt("inspect_active_document", "Inspect active document",
                "Inspect the current Cubism document and model before proposing edits.",
                "Read the active document, overview, hierarchy, parameters, and selection resources. "
                    + "Identify the smallest safe change and verify state after every mutation."),
            prompt("edit_model_structure", "Edit model structure",
                "Plan and execute a minimal batch of model-object structural changes.",
                "Read the model hierarchy and selection, call turboism.model_objects.apply with the "
                    + "smallest operation list, then re-read the hierarchy to verify the result."),
            prompt("normalize_parameters", "Normalize parameters",
                "Inspect and normalize parameter definitions and values.",
                "Read the parameter resources, identify inconsistent names, ranges, defaults, or missing "
                    + "parameters, apply the minimal changes, then re-read the resources."),
            prompt("repair_parameter_bindings", "Repair parameter bindings",
                "Inspect and repair parameter-binding relationships.",
                "Read turboism://active/model/parameter-bindings to inspect the aggregate binding state. "
                    + "For each parameter you may change, use the existing "
                    + "turboism://active/model/parameters/{parameterId} and "
                    + "turboism://active/model/parameters/{parameterId}/bindings templates, percent-encoding "
                    + "parameterId as one URI segment. Apply the smallest binding change, then re-read the "
                    + "aggregate and affected template resources to verify."),
            prompt("recover_document_history", "Recover document history",
                "Move the native Undo history to a requested safe state.",
                "Read the history resource, retain generation and revision, choose move/undo/redo, call "
                    + "turboism.history.move, then verify the returned snapshot."),
            prompt("run_editor_command", "Run editor command",
                "Discover and execute one available non-file Cubism Editor command.",
                "Read turboism://host/editor-commands, select only a listed command, provide exactly its "
                    + "declared parameters, execute it, and report the returned status."),
            prompt("diagnose_environment", "Diagnose environment",
                "Inspect Cubism Core, workspace, layout, and sanitized runtime diagnostics without mutation.",
                "Read turboism://environment/cubism-core, turboism://environment/workspace, "
                    + "turboism://environment/workspace/layout, turboism://environment/diagnostics, and "
                    + "turboism://environment/runtime-diagnostics. Distinguish startup evidence from recent "
                    + "runtime evidence and typed UNAVAILABLE states from permission, unsupported, timeout, "
                    + "and cancellation errors. Report blockers and do not call mutation tools."),
            prompt("inspect_model_diagnostics", "Inspect model diagnostics",
                "Inspect active model scale and texture structure without mutation.",
                "Read turboism://active/document, turboism://active/model/overview, "
                    + "turboism://active/model/statistics, and turboism://active/model/textures. Summarize "
                    + "model scale, texture organization, mask and offscreen risks, and unavailable data. "
                    + "Do not call mutation tools.")
        ));
    }

    List<Map<String, Object>> definitions() {
        return prompts.values().stream().map(Prompt::definition).toList();
    }

    Map<String, Object> get(final String name, final Map<String, Object> arguments) {
        final Prompt prompt = prompts.get(name);
        if (prompt == null) throw new IllegalArgumentException("Unknown MCP prompt: " + name);
        return prompt.render(new LinkedHashMap<>(arguments));
    }

    private static Prompt prompt(
        final String name,
        final String title,
        final String description,
        final String text
    ) {
        return new Prompt(name, title, description, new ArrayList<>(), text);
    }

    @SafeVarargs
    private static LinkedHashMap<String, Object> linked(
        final Map.Entry<String, Object>... entries
    ) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
