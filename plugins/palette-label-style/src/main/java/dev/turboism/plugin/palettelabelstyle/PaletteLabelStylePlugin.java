package dev.turboism.plugin.palettelabelstyle;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.FormDialogField;
import dev.turboism.sdk.ui.FormDialogRequest;
import dev.turboism.sdk.ui.FormFieldKind;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuEntry;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ObjectKind;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Official SDK-only plugin: label text and background colors for palette entries.
 *
 * <p>Both submenus are contributed per palette location; the shared actions
 * dispatch on {@code contextMenuSelection()} by location and object kind.
 * Override colors persist per project in plugin config and replay on enable
 * and on every model open/create.</p>
 */
public final class PaletteLabelStylePlugin implements CubismPlugin {

    public static final String TEXT_ACTION_PREFIX = "palette-label-style.text.";
    public static final String BACKGROUND_ACTION_PREFIX = "palette-label-style.background.";

    private static final int MENU_PRIORITY = 100;

    private PluginContext context;
    private PluginLogger logger;
    private PluginLocalization i18n;
    private final LabelStyleApplier applier = new LabelStyleApplier();
    private Registration readScopeRegistration;
    private Registration writeScopeRegistration;
    private String scopeProjectId;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.i18n = context.localization();
        logger.info("PaletteLabelStylePlugin initialized");
    }

    @Override
    public void enable() {
        try {
            for (final String key : LabelStylePresets.MENU_KEYS) {
                registerAction(TEXT_ACTION_PREFIX + key, i18n.text(colorLabelKey(key)),
                    actionContext -> handleColorAction(actionContext, true, key));
                registerAction(BACKGROUND_ACTION_PREFIX + key, i18n.text(colorLabelKey(key)),
                    actionContext -> handleColorAction(actionContext, false, key));
            }
            registerAction(TEXT_ACTION_PREFIX + LabelStylePresets.CUSTOM_KEY, i18n.text("color.custom"),
                actionContext -> handleColorAction(actionContext, true, LabelStylePresets.CUSTOM_KEY));
            registerAction(BACKGROUND_ACTION_PREFIX + LabelStylePresets.CUSTOM_KEY, i18n.text("color.custom"),
                actionContext -> handleColorAction(actionContext, false, LabelStylePresets.CUSTOM_KEY));

            contributeColorSubmenu(
                "palette-label-style.deformer-tab.text",
                Location.DEFORMER_TAB,
                Set.of(ObjectKind.WARP_DEFORMER, ObjectKind.ROTATION_DEFORMER, ObjectKind.ART_MESH),
                i18n.text("menu.label.textColor"),
                TEXT_ACTION_PREFIX
            );
            contributeColorSubmenu(
                "palette-label-style.deformer-tab.background",
                Location.DEFORMER_TAB,
                Set.of(ObjectKind.WARP_DEFORMER, ObjectKind.ROTATION_DEFORMER, ObjectKind.ART_MESH),
                i18n.text("menu.label.backgroundColor"),
                BACKGROUND_ACTION_PREFIX
            );
            contributeColorSubmenu(
                "palette-label-style.part-tab.text",
                Location.PART_TAB,
                Set.of(ObjectKind.PART, ObjectKind.PART_FOLDER, ObjectKind.WARP_DEFORMER,
                    ObjectKind.ROTATION_DEFORMER, ObjectKind.ART_MESH),
                i18n.text("menu.label.textColor"),
                TEXT_ACTION_PREFIX
            );
            contributeColorSubmenu(
                "palette-label-style.parameter-tab.text",
                Location.PARAMETER_TAB,
                Set.of(ObjectKind.PARAMETER, ObjectKind.PARAMETER_FOLDER),
                i18n.text("menu.label.textColor"),
                TEXT_ACTION_PREFIX
            );
            contributeColorSubmenu(
                "palette-label-style.parameter-tab.background",
                Location.PARAMETER_TAB,
                Set.of(ObjectKind.PARAMETER),
                i18n.text("menu.label.backgroundColor"),
                BACKGROUND_ACTION_PREFIX
            );

            replayForActiveProject();
            logger.info("PaletteLabelStylePlugin enabled: label text/background color actions and menus enrolled in disposable scope");
        } catch (RuntimeException failure) {
            closeDisposableScopeQuietly();
            throw failure;
        }
    }

    @Override
    public void disable() {
        logger.info("PaletteLabelStylePlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("PaletteLabelStylePlugin shutdown");
    }

    @Override
    public void onModelOpened(final ProjectContentSnapshot model) {
        replayForActiveProject();
    }

    @Override
    public void onModelCreated(final ProjectContentSnapshot model) {
        replayForActiveProject();
    }

    private void handleColorAction(final ActionRegistry.ActionContext actionContext, final boolean text, final String key) {
        final ContextMenuSelection selection = actionContext.contextMenuSelection().orElse(null);
        if (selection == null) {
            return;
        }
        final String property = text
            ? LabelStylePersistence.PROPERTY_TEXT
            : LabelStylePersistence.PROPERTY_BACKGROUND;
        if (LabelStylePresets.CUSTOM_KEY.equals(key)) {
            openCustomColorDialog(selection, property);
            return;
        }
        final CubismModel model = activeModelOrNull();
        if (model == null) {
            return;
        }
        applier.apply(model, selection, property, LabelStyleApplier.ColorChoice.preset(key), this::save);
    }

    private void openCustomColorDialog(final ContextMenuSelection selection, final String property) {
        context.uiHost().openFormDialog(
            new FormDialogRequest(
                "palette-label-style.custom-color",
                i18n.text("dialog.title"),
                List.of(new FormDialogField("color", i18n.text("dialog.field.color"), "#000000", FormFieldKind.COLOR)),
                i18n.text("dialog.accept"),
                i18n.text("dialog.cancel")
            ),
            (accepted, actionId, values) -> {
                if (!accepted) {
                    return;
                }
                final CubismModel model = activeModelOrNull();
                if (model == null) {
                    return;
                }
                LabelStylePresets.parseHex(values.get("color")).ifPresent(color ->
                    applier.apply(model, selection, property,
                        LabelStyleApplier.ColorChoice.custom(color), this::save));
            }
        );
    }

    private void replayForActiveProject() {
        final String projectId = currentProjectId();
        ensureConfigScopes(projectId);
        applier.clearAll();
        final CubismModel model = activeModelOrNull();
        if (model == null) {
            return;
        }
        final Map<String, String> stored = LabelStylePersistence.readAll(context.config(), projectId);
        for (final Map.Entry<String, String> entry : stored.entrySet()) {
            LabelStylePersistence.parseKey(entry.getKey()).ifPresent(parsed ->
                LabelStylePresets.parseHex(entry.getValue()).ifPresent(color ->
                    applier.replay(model, parsed.palette(), parsed.objectId(), parsed.property(), color,
                        LabelStyleApplier.NOOP_SINK)));
        }
    }

    private void save(
        final Location palette,
        final String objectId,
        final String property,
        final Optional<String> hex
    ) {
        final String projectId = currentProjectId();
        ensureConfigScopes(projectId);
        try {
            if (hex.isPresent()) {
                LabelStylePersistence.write(context.config(), projectId, palette, objectId, property, hex.orElseThrow());
            } else {
                LabelStylePersistence.clear(context.config(), projectId, palette, objectId, property);
            }
        } catch (dev.turboism.sdk.config.PluginConfigException persistenceFailure) {
            logger.warn("PaletteLabelStylePlugin could not persist label color for "
                + palette + ":" + objectId + ":" + property + ": " + persistenceFailure.getMessage());
        }
    }

    private void ensureConfigScopes(final String projectId) {
        if (projectId.equals(scopeProjectId)) {
            return;
        }
        closeQuietly(readScopeRegistration);
        closeQuietly(writeScopeRegistration);
        final String scope = LabelStylePersistence.scopePath(projectId);
        readScopeRegistration = context.config().readScope(scope);
        writeScopeRegistration = context.config().writeScope(scope);
        context.disposableScope().register(readScopeRegistration);
        context.disposableScope().register(writeScopeRegistration);
        scopeProjectId = projectId;
    }

    private String currentProjectId() {
        return context.cubism().activeProject()
            .map(ProjectSnapshot::projectId)
            .orElse(LabelStylePersistence.DEFAULT_PROJECT_ID);
    }

    private CubismModel activeModelOrNull() {
        try {
            return context.cubism().model().active();
        } catch (IllegalStateException | UnsupportedOperationException unavailable) {
            return null;
        }
    }

    private void contributeColorSubmenu(
        final String contributionId,
        final Location location,
        final Set<ObjectKind> objectKinds,
        final String submenuLabel,
        final String actionPrefix
    ) {
        final List<ContextMenuEntry> children = new ArrayList<>();
        for (final String key : LabelStylePresets.MENU_KEYS) {
            children.add(ContextMenuEntry.item(
                contributionId + "." + key,
                i18n.text(colorLabelKey(key)),
                actionPrefix + key
            ));
        }
        children.add(ContextMenuEntry.separator(contributionId + ".separator"));
        children.add(ContextMenuEntry.item(
            contributionId + "." + LabelStylePresets.CUSTOM_KEY,
            i18n.text("color.custom"),
            actionPrefix + LabelStylePresets.CUSTOM_KEY
        ));
        context.disposableScope().register(context.contextMenu().contribute(
            new ContextMenuContribution(
                contributionId,
                location,
                objectKinds,
                MENU_PRIORITY,
                ContextMenuEntry.submenu(contributionId, submenuLabel, children)
            )
        ));
    }

    private static String colorLabelKey(final String key) {
        return "color." + key;
    }

    private void registerAction(
        final String id,
        final String label,
        final Consumer<ActionRegistry.ActionContext> handler
    ) {
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
            public Consumer<ActionRegistry.ActionContext> handler() {
                return handler;
            }
        });
        context.disposableScope().register(registration);
    }

    private void closeDisposableScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("PaletteLabelStylePlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }

    private static void closeQuietly(final Registration registration) {
        if (registration == null) {
            return;
        }
        try {
            registration.close();
        } catch (Exception ignored) {
        }
    }
}
