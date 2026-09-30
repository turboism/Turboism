package dev.turboism.plugin.commandpalette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionCatalogService;
import dev.turboism.sdk.action.ActionDescriptor;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.ui.UiScheduler;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Catalog composition: host commands and plugin actions merge into one row set, action
 * rows carry their owner's identity, and the palette's own action plus the shell's
 * {@code turboism.core} internals stay out of the results.
 */
class CommandCatalogTest {

    private static final String SELF = "dev.turboism.plugin.command-palette";

    @Test
    void entriesMergeCommandsAndForeignActions() {
        final StubContext context = new StubContext(
                Set.of(EditorCommand.UNDO),
                List.of(
                        new ActionDescriptor(
                                "dev.turboism.plugin.perf-stats",
                                "perf-stats.window.show",
                                "Performance",
                                Optional.empty()),
                        new ActionDescriptor(
                                "dev.turboism.plugin.ui-theme",
                                "ui-theme.manager.open",
                                "Theme Manager",
                                Optional.empty())));

        final List<CommandMatcher.Entry> entries = CommandCatalog.entries(context);

        assertEquals(3, entries.size());
        final CommandMatcher.Entry action = entries.stream()
                .filter(entry -> entry.kind() == CommandMatcher.Kind.ACTION)
                .findFirst()
                .orElseThrow();
        assertEquals("dev.turboism.plugin.perf-stats", action.pluginId());
        assertEquals("perf-stats.window.show", action.id());
        assertEquals("Performance", action.name());
        assertTrue(entries.stream()
                .anyMatch(
                        entry -> entry.kind() == CommandMatcher.Kind.COMMAND && entry.command() == EditorCommand.UNDO));
    }

    @Test
    void actionsExcludeSelfAndShellRegistrations() {
        final StubContext context = new StubContext(
                Set.of(),
                List.of(
                        new ActionDescriptor(SELF, "command-palette.open", "Command Palette", Optional.empty()),
                        new ActionDescriptor(
                                "turboism.core", "turboism.core.plugins.disable.x", "Disable X", Optional.empty()),
                        new ActionDescriptor("dev.turboism.plugin.other", "other.go", "Go", Optional.empty())));

        final List<CommandMatcher.Entry> entries = CommandCatalog.actions(context);

        assertEquals(1, entries.size());
        assertEquals("other.go", entries.get(0).id());
        assertEquals("dev.turboism.plugin.other", entries.get(0).pluginId());
    }

    @Test
    void missingOrDeniedCatalogYieldsNoActionRows() {
        // No catalog installed at all (unavailable sentinel resolves to null).
        assertTrue(CommandCatalog.actions(new StubContext(Set.of(), null)).isEmpty());

        // Catalog installed but enumerates nothing — e.g. the invoke permission denied.
        final StubContext denied = new StubContext(Set.of(), List.of());
        assertTrue(CommandCatalog.actions(denied).isEmpty());
    }

    private static final class StubContext implements PluginContext {
        private final EditorCommandService commands;
        private final ActionCatalogService catalog;
        private final DisposableScope scope = new DisposableScope();

        StubContext(final Set<EditorCommand> availableCommands, final List<ActionDescriptor> actionDescriptors) {
            commands = new EditorCommandService() {
                @Override
                public Set<EditorCommand> available() {
                    return availableCommands;
                }

                @Override
                public EditorCommandResult execute(final EditorCommand command) {
                    return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.id());
                }

                @Override
                public EditorCommandResult execute(final EditorFileCommandRequest request) {
                    return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, request.commandId());
                }

                @Override
                public EditorCommandResult execute(final EditorParameterizedRequest request) {
                    return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, request.commandId());
                }
            };
            catalog = actionDescriptors == null
                    ? ActionCatalogService.unavailable()
                    : new ActionCatalogService() {
                        @Override
                        public List<ActionDescriptor> actions() {
                            return actionDescriptors;
                        }

                        @Override
                        public void invoke(final String pluginId, final String actionId) {}
                    };
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor() {
                @Override
                public String id() {
                    return SELF;
                }

                @Override
                public String name() {
                    return "Command Palette";
                }

                @Override
                public String version() {
                    return "0.1.0";
                }

                @Override
                public String description() {
                    return "test";
                }

                @Override
                public List<String> entrypoints() {
                    return List.of();
                }

                @Override
                public String turboismApi() {
                    return "[0.1.0,0.2.0)";
                }

                @Override
                public List<Author> authors() {
                    return List.of();
                }

                @Override
                public String license() {
                    return "test";
                }

                @Override
                public Optional<String> website() {
                    return Optional.empty();
                }

                @Override
                public List<String> resources() {
                    return List.of();
                }

                @Override
                public I18n i18n() {
                    return null;
                }

                @Override
                public List<DependencyRef> dependencies() {
                    return List.of();
                }

                @Override
                public List<PermissionRef> permissions() {
                    return List.of();
                }

                @Override
                public List<String> capabilities() {
                    return List.of();
                }

                @Override
                public Environment environment() {
                    return null;
                }
            };
        }

        @Deprecated
        @Override
        public EditorCommandService editorCommands() {
            return commands;
        }

        @Deprecated
        @Override
        public ActionCatalogService actionCatalog() {
            return catalog;
        }

        @Override
        public PluginLogger logger() {
            return new PluginLogger() {
                @Override
                public void debug(final String message) {}

                @Override
                public void info(final String message) {}

                @Override
                public void warn(final String message) {}

                @Override
                public void error(final String message) {}

                @Override
                public void error(final String message, final Throwable error) {}
            };
        }

        @Override
        public PluginPaths paths() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CubismFacade cubism() {
            throw new UnsupportedOperationException();
        }

        @Override
        public EventBus eventBus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionRegistry actions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MenuRegistry menus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public UiScheduler uiScheduler() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DiagnosticReport diagnostics() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public DisposableScope disposableScope() {
            return scope;
        }
    }
}
