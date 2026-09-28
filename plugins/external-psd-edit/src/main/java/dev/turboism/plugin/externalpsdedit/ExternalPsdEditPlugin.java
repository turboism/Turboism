package dev.turboism.plugin.externalpsdedit;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import java.util.Objects;
import java.util.Set;

/**
 * Official plugin for external PSD editing: an ArtMesh context-menu entry exports the
 * complete raw PSD for the resolved raw image, opens it in the operating system's default
 * PSD application, and applies a Cubism native explicit-target raw-image replacement for
 * every stable save of the temporary file.
 */
public final class ExternalPsdEditPlugin implements CubismPlugin {

    public static final String OPEN_ACTION_ID = "external-psd-edit.open";
    private static final String PART_CONTEXT_MENU_ID = "external-psd-edit.open.part";
    private static final String DEFORMER_CONTEXT_MENU_ID = "external-psd-edit.open.deformer";
    private static final String WORKSPACE_CONTEXT_MENU_ID = "external-psd-edit.open.workspace";

    private PluginContext context;
    private PluginLogger logger;
    private PluginLocalization localization;
    private ExternalPsdEditSessionManager sessions;

    public ExternalPsdEditPlugin() {}

    ExternalPsdEditPlugin(final ExternalPsdEditSessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        this.localization = localization(context);
        if (sessions == null) {
            sessions = new ExternalPsdEditSessionManager(context, localization);
        }
        logger.info("ExternalPsdEditPlugin initialized");
    }

    @Override
    public void enable() {
        sessions.reopen();
        try {
            registerAction(
                    OPEN_ACTION_ID,
                    text("external-psd-edit.action.open"),
                    actionContext -> sessions.openFromContextMenu(actionContext));
            registerContextMenu(PART_CONTEXT_MENU_ID, ContextMenuRegistry.Location.PART_TAB);
            registerContextMenu(DEFORMER_CONTEXT_MENU_ID, ContextMenuRegistry.Location.DEFORMER_TAB);
            registerContextMenu(WORKSPACE_CONTEXT_MENU_ID, ContextMenuRegistry.Location.WORKSPACE_OBJECT);
        } catch (RuntimeException failure) {
            closeDisposableScopeQuietly();
            sessions.stopAll("plugin enable rollback");
            throw failure;
        }
        logger.info("ExternalPsdEditPlugin enabled: external PSD edit action enrolled in disposable scope");
    }

    @Override
    public void disable() {
        sessions.stopAll("plugin disabled");
        logger.info("ExternalPsdEditPlugin disabled");
    }

    @Override
    public void shutdown() {
        sessions.stopAll("plugin shutdown");
        context = null;
        logger.info("ExternalPsdEditPlugin shutdown");
    }

    int liveSessions() {
        return sessions == null ? 0 : sessions.liveSessionCount();
    }

    @Override
    public void onModelClosed(final ProjectContentSnapshot model) {
        if (sessions != null) {
            sessions.onModelClosed();
        }
    }

    @Override
    public void beforeEditorExit(final EditorLifecycleSnapshot editor) {
        if (sessions != null) {
            sessions.stopAll("editor exiting");
        }
    }

    private void registerAction(
            final String id,
            final String label,
            final java.util.function.Consumer<ActionRegistry.ActionContext> handler) {
        final Registration registration = context.actions().register(id, new ActionRegistry.Action() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return label;
            }

            @Override
            public java.util.function.Consumer<ActionRegistry.ActionContext> handler() {
                return handler;
            }
        });
        context.disposableScope().register(registration);
    }

    private void registerContextMenu(final String id, final ContextMenuRegistry.Location location) {
        context.disposableScope()
                .register(context.contextMenu()
                        .contribute(new ContextMenuRegistry.ContextMenuContribution(
                                id,
                                OPEN_ACTION_ID,
                                text("external-psd-edit.menu"),
                                null,
                                location,
                                Set.of(ContextMenuRegistry.ObjectKind.ART_MESH),
                                110)));
    }

    private String text(final String key) {
        return localization.text(key);
    }

    private void closeDisposableScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn(localization.format("external-psd-edit.enable.rollback-failed", closeFailure.getMessage()));
        }
    }

    private static PluginLocalization localization(final PluginContext context) {
        try {
            return context.localization();
        } catch (UnsupportedOperationException unavailable) {
            return new PluginLocalization() {
                @Override
                public java.util.Locale locale() {
                    return java.util.Locale.ENGLISH;
                }

                @Override
                public String text(final String key) {
                    return key;
                }

                @Override
                public String format(final String key, final Object... arguments) {
                    return java.text.MessageFormat.format(text(key), arguments);
                }

                @Override
                public boolean contains(final String key) {
                    return true;
                }
            };
        }
    }
}
