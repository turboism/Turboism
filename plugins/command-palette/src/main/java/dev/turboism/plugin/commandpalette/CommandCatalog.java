package dev.turboism.plugin.commandpalette;

import dev.turboism.sdk.action.ActionCatalogService;
import dev.turboism.sdk.action.ActionDescriptor;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the palette's row set: every {@link EditorCommand} the host currently admits
 * (the runtime filters {@link EditorCommandService#available()} by host state and this
 * plugin's granted permissions), paired with its stable id and the localized display
 * name resolved from the plugin's {@code command.<id>} catalog keys — plus every action
 * registered by other plugins, exposed through {@link ActionCatalogService} under the
 * owner's own label.
 */
final class CommandCatalog {

    /** Reserved identity of the runtime shell; its actions are internal UI wiring. */
    private static final String SHELL_PLUGIN_ID = "turboism.core";

    private CommandCatalog() {}

    /**
     * Returns all palette entries — host commands and plugin actions — ordered by
     * display name. Never throws: absent or failing services contribute nothing.
     */
    static List<CommandMatcher.Entry> entries(final PluginContext context) {
        final List<CommandMatcher.Entry> entries = new ArrayList<>(commands(context));
        entries.addAll(actions(context));
        entries.sort(Comparator.comparing(CommandMatcher.Entry::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    /**
     * Returns the currently executable commands as palette entries, ordered by localized
     * name for stable presentation. Never throws: an absent or failing command service
     * yields an empty list.
     */
    static List<CommandMatcher.Entry> commands(final PluginContext context) {
        Objects.requireNonNull(context, "context");
        final EditorCommandService service = service(context);
        if (service == null) {
            return List.of();
        }
        final Set<EditorCommand> available;
        try {
            available = service.available();
        } catch (RuntimeException failure) {
            return List.of();
        }
        if (available == null || available.isEmpty()) {
            return List.of();
        }
        final PluginLocalization localization = context.localization();
        final List<CommandMatcher.Entry> entries = new ArrayList<>();
        for (final EditorCommand command : EditorCommand.values()) {
            if (available.contains(command)) {
                entries.add(CommandMatcher.Entry.command(command, command.id(), name(command, localization)));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Returns the actions registered by other plugins as palette entries, ordered by
     * label. Actions registered by this plugin and by the shell's reserved
     * {@code turboism.core} identity are filtered out — the former to keep the palette's
     * own open action out of its results, the latter because shell entries are internal
     * UI wiring rather than user-level commands. Never throws: an absent catalog service
     * or a plugin lacking {@code turboism.action.invoke} yields an empty list.
     */
    static List<CommandMatcher.Entry> actions(final PluginContext context) {
        Objects.requireNonNull(context, "context");
        final ActionCatalogService service = catalogService(context);
        if (service == null) {
            return List.of();
        }
        final List<ActionDescriptor> descriptors;
        try {
            descriptors = service.actions();
        } catch (RuntimeException failure) {
            return List.of();
        }
        if (descriptors == null || descriptors.isEmpty()) {
            return List.of();
        }
        final String self = context.descriptor().id();
        final List<CommandMatcher.Entry> entries = new ArrayList<>(descriptors.size());
        for (final ActionDescriptor descriptor : descriptors) {
            final String owner = descriptor.pluginId();
            if (self.equals(owner) || SHELL_PLUGIN_ID.equals(owner)) {
                continue;
            }
            final String label =
                    descriptor.label() == null || descriptor.label().isBlank()
                            ? descriptor.actionId()
                            : descriptor.label();
            entries.add(CommandMatcher.Entry.action(owner, descriptor.actionId(), label));
        }
        return List.copyOf(entries);
    }

    /**
     * Catalog key for one command: {@code command.<id>} with a digit-led segment folded
     * into the previous one ({@code edit.level.1} becomes {@code command.edit.level1})
     * because catalog keys require every dot segment to start with a letter.
     */
    static String key(final EditorCommand command) {
        return "command." + command.id().replaceAll("\\.(\\d)", "$1");
    }

    /**
     * Localized display name for one command: the {@code command.<id>} catalog value when
     * present, else a humanized English rendering of the enum constant so a missing
     * translation degrades to readable text instead of a raw id.
     */
    static String name(final EditorCommand command, final PluginLocalization localization) {
        return localization.text(key(command), humanize(command.name()));
    }

    /** Splits {@code UNDO_LAST_STEP}-style constants into {@code "Undo Last Step"} words. */
    static String humanize(final String constant) {
        final String[] words = constant.toLowerCase(Locale.ROOT).split("_");
        final StringBuilder text = new StringBuilder(constant.length());
        for (final String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                text.append(word, 1, word.length());
            }
        }
        return text.toString();
    }

    private static EditorCommandService service(final PluginContext context) {
        return context.services()
                .find(EditorCommandService.class)
                .filter(EditorCommandService::isAvailable)
                .orElse(null);
    }

    private static ActionCatalogService catalogService(final PluginContext context) {
        return context.services()
                .find(ActionCatalogService.class)
                .filter(ActionCatalogService::isAvailable)
                .orElse(null);
    }
}
