package dev.turboism.plugin.commandpalette;

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
 * name resolved from the plugin's {@code command.<id>} catalog keys.
 */
final class CommandCatalog {

    private CommandCatalog() {}

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
        final PluginLocalization localization = localization(context);
        final List<CommandMatcher.Entry> entries = new ArrayList<>();
        for (final EditorCommand command : EditorCommand.values()) {
            if (available.contains(command)) {
                entries.add(new CommandMatcher.Entry(command, command.id(), name(command, localization)));
            }
        }
        entries.sort(Comparator.comparing(CommandMatcher.Entry::name, String.CASE_INSENSITIVE_ORDER));
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
        final String key = key(command);
        if (localization != null) {
            try {
                if (localization.contains(key)) {
                    final String value = localization.text(key);
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            } catch (RuntimeException unavailable) {
                // fall through to the humanized default
            }
        }
        return humanize(command.name());
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
        try {
            final EditorCommandService service = context.services().get(EditorCommandService.class);
            return service != null && service.isAvailable() ? service : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static PluginLocalization localization(final PluginContext context) {
        try {
            final PluginLocalization localization = context.localization();
            return localization != null && localization.isAvailable() ? localization : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
