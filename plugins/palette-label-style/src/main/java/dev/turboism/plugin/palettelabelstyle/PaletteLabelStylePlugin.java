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
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuEntry;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ObjectKind;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Official SDK-only plugin: label text and background colors for palette entries.
 *
 * <p>Both submenus are contributed per palette location; the shared actions
 * dispatch on {@code contextMenuSelection()} by location and object kind.
 * Override colors persist per project through {@code PluginStorage} (the config
 * registry routes through the sidecar and is unavailable in preview sessions)
 * and replay on enable and on every model open/create.</p>
 */
public final class PaletteLabelStylePlugin implements CubismPlugin {

    public static final String TEXT_ACTION_PREFIX = "palette-label-style.text.";
    public static final String BACKGROUND_ACTION_PREFIX = "palette-label-style.background.";

    private static final int MENU_PRIORITY = 100;
    private static final int MAX_COLOR_FILE_BYTES = 64 * 1024;

    private PluginContext context;
    private PluginLogger logger;
    private PluginLocalization i18n;
    private final LabelStyleApplier applier = new LabelStyleApplier();
    private final ExecutorService persistenceIo = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "palette-label-style-persistence");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Authoritative persisted entries of the current project: entry key to hex. */
    private final Map<String, String> persisted = new HashMap<>();
    private String persistedProjectId;

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
                    actionContext -> runSafely("text." + key,
                        () -> handleColorAction(actionContext, true, key)));
                registerAction(BACKGROUND_ACTION_PREFIX + key, i18n.text(colorLabelKey(key)),
                    actionContext -> runSafely("background." + key,
                        () -> handleColorAction(actionContext, false, key)));
            }
            registerAction(TEXT_ACTION_PREFIX + LabelStylePresets.CUSTOM_KEY, i18n.text("color.custom"),
                actionContext -> runSafely("text.custom",
                    () -> handleColorAction(actionContext, true, LabelStylePresets.CUSTOM_KEY)));
            registerAction(BACKGROUND_ACTION_PREFIX + LabelStylePresets.CUSTOM_KEY, i18n.text("color.custom"),
                actionContext -> runSafely("background.custom",
                    () -> handleColorAction(actionContext, false, LabelStylePresets.CUSTOM_KEY)));

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
        closed.set(true);
        applier.clearAll();
        persistenceIo.shutdownNow();
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

    private void runSafely(final String operation, final Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            logger.warn("PaletteLabelStylePlugin " + operation + " failed: " + failure.getMessage());
        }
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
        final String initialHex = selection.items().stream()
            .findFirst()
            .map(item -> LabelStylePersistence.key(
                selection.location(), item.id(), property))
            .map(persisted::get)
            .orElse(null);
        context.uiHost().openColorPicker(
            "palette-label-style.custom-color",
            i18n.text("dialog.title"),
            initialHex,
            (accepted, colorHex) -> {
                if (!accepted) {
                    return;
                }
                final CubismModel model = activeModelOrNull();
                if (model == null) {
                    return;
                }
                LabelStylePresets.parseHex(colorHex).ifPresent(color ->
                    applier.apply(model, selection, property,
                        LabelStyleApplier.ColorChoice.custom(color), this::save));
            }
        );
    }

    private void replayForActiveProject() {
        final String projectId = currentProjectId();
        synchronized (persisted) {
            persistedProjectId = projectId;
        }
        if (closed.get()) {
            return;
        }
        persistenceIo.submit(() -> {
            if (closed.get()) {
                return;
            }
            final Map<String, String> loaded;
            try {
                final StorageReadResult<String> result = context.storage().readUtf8(
                    LabelStylePersistence.filePath(projectId), MAX_COLOR_FILE_BYTES
                ).toCompletableFuture().join();
                loaded = result.value().map(LabelStylePersistence::parse).orElseGet(Map::of);
            } catch (RuntimeException readFailure) {
                logger.warn("PaletteLabelStylePlugin could not load persisted colors for project "
                    + projectId + ": " + readFailure.getMessage());
                return;
            }
            synchronized (persisted) {
                if (closed.get()) {
                    return;
                }
                persisted.clear();
                persisted.putAll(loaded);
            }
            applyPersisted(loaded, projectId);
        });
    }

    /** Applies persisted entries to the active model without re-persisting them. */
    private void applyPersisted(final Map<String, String> entries, final String projectId) {
        final CubismModel model = activeModelOrNull();
        if (model == null) {
            return;
        }
        applier.clearAll();
        for (final Map.Entry<String, String> entry : entries.entrySet()) {
            LabelStylePersistence.parseKey(entry.getKey()).ifPresent(parsed ->
                LabelStylePresets.parseHex(entry.getValue()).ifPresent(color ->
                    applier.replay(model, parsed.palette(), parsed.objectId(), parsed.property(), color,
                        LabelStyleApplier.NOOP_SINK)));
        }
        logger.info("PaletteLabelStylePlugin replayed " + entries.size()
            + " persisted label color(s) for project " + projectId);
    }

    /** Persistence callback: updates the in-memory entry map and schedules one atomic file write. */
    private void save(
        final Location palette,
        final String objectId,
        final String property,
        final Optional<String> hex
    ) {
        final String entryKey = LabelStylePersistence.key(palette, objectId, property);
        final String projectId = currentProjectId();
        final Map<String, String> snapshot;
        synchronized (persisted) {
            if (!projectId.equals(persistedProjectId)) {
                persisted.clear();
                persistedProjectId = projectId;
            }
            if (hex.isPresent()) {
                persisted.put(entryKey, hex.orElseThrow());
            } else {
                persisted.remove(entryKey);
            }
            snapshot = Map.copyOf(persisted);
        }
        if (closed.get()) {
            return;
        }
        persistenceIo.submit(() -> {
            if (closed.get()) {
                return;
            }
            try {
                context.storage().writeUtf8Atomic(
                    LabelStylePersistence.filePath(projectId),
                    LabelStylePersistence.serialize(snapshot)
                ).toCompletableFuture().join();
            } catch (RuntimeException writeFailure) {
                logger.warn("PaletteLabelStylePlugin could not persist label color for " + entryKey
                    + ": " + writeFailure.getMessage());
            }
        });
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
}
