package dev.turboism.tests.plugin;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Manual-test-only plugin for exact-host object context-menu validation. */
public final class WindowsContextMenuValidationProbe implements TurboismPlugin {
    private PluginContext context;
    private Path evidence;

    @Override
    public void init(final PluginContext context) throws IOException {
        this.context = context;
        evidence = context.paths().logsDir().resolve("context-menu-validation.log");
        record("INITIALIZED");
    }

    @Override
    public void enable() throws IOException {
        for (String actionId : List.of(
            "context-menu.first",
            "context-menu.before",
            "context-menu.anchor",
            "context-menu.after",
            "context-menu.deep"
        )) {
            context.disposableScope().register(context.actions().register(actionId, action(actionId)));
        }

        contribute(
            "deformer",
            ContextMenuRegistry.Location.DEFORMER_TAB,
            ContextMenuRegistry.Location.DEFORMER_TAB.supportedKinds()
        );
        contribute(
            "parameter",
            ContextMenuRegistry.Location.PARAMETER_TAB,
            ContextMenuRegistry.Location.PARAMETER_TAB.supportedKinds()
        );
        contribute(
            "part",
            ContextMenuRegistry.Location.PART_TAB,
            ContextMenuRegistry.Location.PART_TAB.supportedKinds()
        );
        contribute(
            "workspace",
            ContextMenuRegistry.Location.WORKSPACE_OBJECT,
            ContextMenuRegistry.Location.WORKSPACE_OBJECT.supportedKinds()
        );
        record("ENABLED contributions=4");
    }

    private void contribute(
        final String id,
        final ContextMenuRegistry.Location location,
        final Set<ContextMenuRegistry.ObjectKind> kinds
    ) {
        final ContextMenuRegistry.ContextMenuEntry entry = ContextMenuRegistry.ContextMenuEntry.submenu(
            "turboism-validation-" + id,
            "Turboism Validation " + id,
            List.of(
                ContextMenuRegistry.ContextMenuEntry.item(
                    "anchor", "Anchor", "context-menu.anchor"
                ),
                ContextMenuRegistry.ContextMenuEntry.item(
                    "before", "Before Anchor", "context-menu.before",
                    ContextMenuRegistry.Placement.before("Anchor")
                ),
                ContextMenuRegistry.ContextMenuEntry.item(
                    "after", "After Anchor", "context-menu.after",
                    ContextMenuRegistry.Placement.after("Anchor")
                ),
                ContextMenuRegistry.ContextMenuEntry.item(
                    "first", "First", "context-menu.first",
                    ContextMenuRegistry.Placement.first()
                ),
                ContextMenuRegistry.ContextMenuEntry.separator(
                    "separator", ContextMenuRegistry.Placement.after("After Anchor")
                ),
                ContextMenuRegistry.ContextMenuEntry.submenu(
                    "level-two", "Level 2",
                    List.of(ContextMenuRegistry.ContextMenuEntry.submenu(
                        "level-three", "Level 3",
                        List.of(ContextMenuRegistry.ContextMenuEntry.item(
                            "deep", "Deep Action", "context-menu.deep"
                        ))
                    ))
                )
            ),
            ContextMenuRegistry.Placement.first()
        );
        context.disposableScope().register(context.contextMenu().contribute(
            new ContextMenuRegistry.ContextMenuContribution(
                "context-menu-validation-" + id,
                location,
                kinds,
                0,
                entry
            )
        ));
    }

    private ActionRegistry.Action action(final String actionId) {
        return new ActionRegistry.Action() {
            @Override public String id() { return actionId; }
            @Override public String label() { return actionId; }
            @Override public java.util.function.Consumer<ActionRegistry.ActionContext> handler() {
                return actionContext -> {
                    final String selection = actionContext.contextMenuSelection()
                        .map(value -> value.location() + ":" + value.items())
                        .orElse("missing");
                    try {
                        record("ACTION " + actionId + " selection=" + selection);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                };
            }
        };
    }

    private synchronized void record(final String message) throws IOException {
        Files.createDirectories(evidence.getParent());
        Files.writeString(
            evidence,
            Instant.now() + " " + message + System.lineSeparator(),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }
}
