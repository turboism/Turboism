package dev.turboism.plugin.recentprojectboard;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.hook.ModelFileHooks;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileService;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewCacheService;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewCapturedEvent;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewDeletedEvent;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Renders a recent-project board over the empty Cubism modeling viewport.
 *
 * <p>The plugin contributes a runtime-owned viewport board. The runtime overlays it on the
 * native OpenGL viewport only while no document is open, and hides it as soon as a project is
 * loaded. Each card shows the cached thumbnail from the official recent-preview plugin;
 * double-clicking a card opens the corresponding project through
 * {@link RecentFileService#open(RecentFileId)}. With no recent files, the centered empty
 * state offers links to create a new model or open the native New Animation flow.</p>
 *
 * <p>The plugin depends on {@code dev.turboism.plugin.recent-preview} and subscribes to its
 * public {@code thumbnail.captured} event. The dependency is declared with
 * {@code ordering: after} so the provider is admitted before this plugin imports its event
 * contract; the provider replays a bounded disk-cache snapshot shortly after startup, which
 * arrives after this subscriber is installed.</p>
 */
public final class RecentProjectBoardPlugin implements TurboismPlugin, ModelFileHooks {

    static final String PANEL_ID = "recent-project-board.viewport";
    static final String OPEN_ACTION_ID = "recent-project-board.open";
    static final String CREATE_ACTION_ID = "recent-project-board.create";
    static final String CREATE_ANIMATION_ACTION_ID = "recent-project-board.create-animation";
    static final String LAYOUT_ACTION_ID = "recent-project-board.layout";
    static final String DELETE_ACTION_ID = "recent-project-board.delete";
    static final String PANEL_PLACEMENT = "viewport";
    static final int MAX_CARDS = 8;
    static final int IMAGE_MAX_WIDTH = 220;
    static final int IMAGE_MAX_HEIGHT = 220;
    static final int PANEL_PRIORITY = 20;
    /** Pins the header title block so the layout control keeps a stable place beside it. */
    static final int HEADER_TEXT_WIDTH = 360;
    /** Header control cell height; the control is centered inside it. */
    static final int HEADER_CONTROL_HEIGHT = 34;
    /** Thumbnail column width of one single-row card. */
    static final int ROW_PREVIEW_WIDTH = 96;
    /** Thumbnail height of one single-row card; the card is only slightly taller. */
    static final int ROW_PREVIEW_HEIGHT = 64;
    /** Left inset of a single-row card's name and timestamp, keeping them off the thumbnail. */
    static final int ROW_TEXT_INSET = 16;

    /** Breathing room above the board title, so the header never touches the overlay edge. */
    private static final int HEADER_TOP_INSET = 10;
    /** Left inset that keeps the header text off the overlay edge. */
    private static final int HEADER_LEFT_INSET = 12;

    /** Bundled Lucide mask used by the per-card delete control. */
    private static final String DELETE_ICON_NAME = "trash-2";

    /** Bundled Lucide mask used by the header layout control. */
    private static final String LAYOUT_ICON_NAME = "layout-grid";

    private static final StoragePath LAYOUT_PATH = new StoragePath(StorageRoot.DATA, "recent-project-board/layout.txt");

    private static final DateTimeFormatter MODIFIED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Map<String, byte[]> previews = new ConcurrentHashMap<>();
    private final Map<String, byte[]> layoutIcons = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final AtomicReference<Registration> board = new AtomicReference<>();

    private PluginContext context;
    private PluginLogger logger;
    private PluginLocalization localization;
    private RecentFileService recentFiles;
    private RecentPreviewCacheService previewCache;
    private EditorCommandService editorCommands;
    private UiHostCapabilityService uiHost;
    private UiScheduler uiScheduler;
    private PluginStorage storage = PluginStorage.unavailable();
    private volatile BoardLayout layout = BoardLayout.SIX;
    private volatile boolean enabled;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        this.localization = context.localization();
        this.recentFiles = context.services().find(RecentFileService.class).orElseGet(RecentFileService::unavailable);
        this.previewCache = context.services()
                .find(RecentPreviewCacheService.class)
                .orElseGet(RecentPreviewCacheService::unavailable);
        this.editorCommands =
                context.services().find(EditorCommandService.class).orElseGet(EditorCommandService::unavailable);
        this.uiHost = context.services().find(UiHostCapabilityService.class).orElse(null);
        this.uiScheduler = context.uiScheduler();
        this.storage = context.storage();
        context.disposableScope().register(this::closeBoard);
        loadLayout();
        logger.info("Recent project board initialized");
    }

    @Override
    public void enable() {
        if (enabled) {
            return;
        }
        enabled = true;
        if (uiHost == null || !uiHost.isAvailable()) {
            logger.warn("Recent project board disabled: the UI host service is unavailable");
            return;
        }
        registerAction(
                OPEN_ACTION_ID,
                localization.text("board.open.label", "Open"),
                actionContext -> actionContext.uiEvent().ifPresent(event -> open(event.sourceId())));
        registerAction(
                CREATE_ACTION_ID,
                localization.text("board.create", "Create New Project"),
                ignored -> createNewProject());
        registerAction(
                CREATE_ANIMATION_ACTION_ID,
                localization.text("board.createAnimation", "Create New Animation Project"),
                ignored -> createAnimationProject());
        registerAction(
                LAYOUT_ACTION_ID, localization.text("board.layout.action", "Change layout"), ignored -> cycleLayout());
        registerAction(
                DELETE_ACTION_ID,
                localization.text("board.delete.action", "Delete preview"),
                actionContext -> actionContext.uiEvent().ifPresent(event -> deletePreview(event.sourceId())));
        dispatchRefresh();
        scheduleStartupRefreshes();
    }

    /**
     * Retries the first few contributions after plugin enable. The host's OpenGL viewport and
     * menu model can still be materializing when the plugin load wave runs; a failed first
     * contribution must not leave the board absent until the next file event.
     */
    private void scheduleStartupRefreshes() {
        for (final int delayMillis : new int[] {1_000, 2_500, 5_000}) {
            final javax.swing.Timer timer = new javax.swing.Timer(delayMillis, event -> {
                ((javax.swing.Timer) event.getSource()).stop();
                if (enabled) {
                    dispatchRefresh();
                }
            });
            timer.setRepeats(false);
            timer.start();
        }
    }

    @Override
    public void disable() {
        enabled = false;
        refreshPending.set(false);
        closeBoard();
        previews.clear();
    }

    @Override
    public void shutdown() {
        disable();
        context = null;
        logger = null;
        localization = null;
        recentFiles = null;
        editorCommands = null;
        uiHost = null;
        uiScheduler = null;
    }

    @Override
    public void afterOpenModel(final ProjectFileOperationResult result) {
        dispatchRefresh();
    }

    @Override
    public void afterCreateModel(final ProjectFileOperationResult result) {
        dispatchRefresh();
    }

    @Override
    public void afterCloseModel(final ProjectFileOperationResult result) {
        dispatchRefresh();
    }

    /** Receives committed thumbnails from the official recent-preview plugin. */
    @SubscribeEvent
    public void onPreview(final RecentPreviewCapturedEvent event) {
        if (!enabled || event == null) {
            return;
        }
        previews.put(event.id().value(), event.image().png());
        dispatchRefresh();
    }

    /**
     * Receives committed deletions from the provider. The provider is authoritative, so the local
     * copy drops even when this board did not request the deletion.
     */
    @SubscribeEvent
    public void onPreviewDeleted(final RecentPreviewDeletedEvent event) {
        if (!enabled || event == null) {
            return;
        }
        previews.remove(event.id().value());
        dispatchRefresh();
    }

    /** One coalesced UI-thread refresh of the board content. */
    void dispatchRefresh() {
        if (!enabled || !refreshPending.compareAndSet(false, true)) {
            return;
        }
        final UiScheduler scheduler = uiScheduler;
        if (scheduler == null) {
            refreshPending.set(false);
            refresh();
            return;
        }
        try {
            scheduler.runOnUiThread(() -> {
                refreshPending.set(false);
                refresh();
            });
        } catch (IllegalStateException fenced) {
            refreshPending.set(false);
            final PluginLogger current = logger;
            if (current != null) {
                current.debug("Recent project board refresh skipped: plugin generation is closed");
            }
        }
    }

    /** Rebuilds and re-contributes the board; must run on the UI thread. */
    void refresh() {
        if (!enabled || uiHost == null || recentFiles == null) {
            return;
        }
        final PanelView content;
        try {
            final List<RecentFileSummary> files = recentFiles.list();
            final Set<String> live = new HashSet<>();
            for (RecentFileSummary file : files) {
                live.add(file.id().value());
            }
            previews.keySet().retainAll(live);
            content = contentFor(files);
        } catch (RuntimeException failure) {
            logger.warn(
                    "Recent project board list failed: " + failure.getClass().getSimpleName());
            replaceContent(PanelView.centered(PanelView.column(
                    PanelView.textCentered(localization.text("board.title", "Recent Files")),
                    PanelView.textCentered(
                            localization.text("board.unavailable", "Recent files are unavailable."), true))));
            return;
        }
        replaceContent(content);
    }

    private void replaceContent(final PanelView content) {
        try {
            final Registration next = uiHost.contributeRecentProjectBoard(new EmbeddedPanelContribution(
                    PANEL_ID,
                    localization.text("board.title", "Recent Files"),
                    PANEL_PLACEMENT,
                    PANEL_PRIORITY,
                    content,
                    false));
            final Registration previous = board.getAndSet(next);
            if (previous != null && previous != next) {
                previous.close();
            }
        } catch (RuntimeException failure) {
            final String reason =
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            logger.warn("Recent project board contribution failed: " + reason);
        }
    }

    /** Builds the board content from a recent-file projection. */
    PanelView contentFor(final List<RecentFileSummary> files) {
        final String title = localization.text("board.title", "Recent Files");
        final String subtitle = localization.text("board.subtitle", "Double-click a project to open it");
        if (files == null || files.isEmpty()) {
            final List<PanelView> empty = new ArrayList<>();
            empty.add(PanelView.textCentered(localization.text("board.empty", "No recent projects")));
            if (canCreateNewProject()) {
                empty.add(PanelView.link(
                        CREATE_ACTION_ID, localization.text("board.create", "Create New Project"), CREATE_ACTION_ID));
            }
            if (canCreateAnimationProject()) {
                empty.add(PanelView.link(
                        CREATE_ANIMATION_ACTION_ID,
                        localization.text("board.createAnimation", "Create New Animation Project"),
                        CREATE_ANIMATION_ACTION_ID));
            }
            return PanelView.centered(PanelView.column(empty.toArray(PanelView[]::new)), 420, 220);
        }
        final Map<String, RecentFileSummary> unique = new LinkedHashMap<>();
        for (RecentFileSummary file : files) {
            unique.putIfAbsent(file.displayName().toLowerCase(Locale.ROOT), file);
        }
        final List<RecentFileSummary> withPreview = new ArrayList<>();
        final List<RecentFileSummary> withoutPreview = new ArrayList<>();
        for (RecentFileSummary file : unique.values()) {
            if (previews.containsKey(file.id().value())) {
                withPreview.add(file);
            } else {
                withoutPreview.add(file);
            }
        }
        final List<PanelView> cards = new ArrayList<>();
        for (RecentFileSummary file : withPreview) {
            if (cards.size() >= MAX_CARDS) break;
            cards.add(cardFor(file));
        }
        for (RecentFileSummary file : withoutPreview) {
            if (cards.size() >= MAX_CARDS) break;
            cards.add(cardFor(file));
        }
        // The header sits outside the scroll region: inside it, the vertical scrollbar narrowed the
        // viewport and shifted the layout control left whenever the card list overflowed.
        return PanelView.column(
                header(title, subtitle),
                PanelView.scroll(
                        layout == BoardLayout.ONE
                                ? rowList(cards)
                                : PanelView.grid(layout.columns(), cards.toArray(PanelView[]::new))));
    }

    /**
     * One project per row for the single-column layout: the thumbnail stays at its natural size and
     * the card fills the row width, so a screen shows more projects than a grid of tall cards.
     */
    private PanelView rowList(final List<PanelView> rows) {
        final List<PanelView> children = new ArrayList<>();
        for (PanelView row : rows) {
            if (!children.isEmpty()) {
                children.add(PanelView.separator());
            }
            children.add(row);
        }
        return PanelView.column(children.toArray(PanelView[]::new));
    }

    /** Board header: a left title block and the right-aligned layout control. */
    private PanelView header(final String title, final String subtitle) {
        return PanelView.padded(
                PanelView.spaceBetween(
                        PanelView.centered(
                                PanelView.column(PanelView.text(title), PanelView.text(subtitle, true)),
                                HEADER_TEXT_WIDTH,
                                0),
                        PanelView.centered(layoutControl(), 0, HEADER_CONTROL_HEIGHT)),
                HEADER_TOP_INSET,
                HEADER_LEFT_INSET,
                0,
                0);
    }

    private PanelView cardFor(final RecentFileSummary file) {
        return layout == BoardLayout.ONE ? rowCardFor(file) : gridCardFor(file);
    }

    private PanelView gridCardFor(final RecentFileSummary file) {
        final byte[] png = previews.get(file.id().value());
        final int imageSize = layout.imageSize();
        final PanelView preview = png == null
                ? PanelView.centered(
                        PanelView.column(PanelView.textCentered(
                                localization.text("board.noPreview", "No preview"), true, layout.textScale())),
                        imageSize,
                        imageSize)
                : PanelView.centered(
                        PanelView.scaledImage(png, file.displayName(), imageSize, imageSize), imageSize, imageSize);
        final String modified = file.lastModified().map(MODIFIED_FORMAT::format).orElse("");
        return PanelView.card(
                file.id().value(),
                OPEN_ACTION_ID,
                PanelView.column(
                        // The trash sits in its own top row so the name and timestamp stay centered
                        // under the preview exactly as before.
                        PanelView.spaceBetween(PanelView.text(""), deleteControl(file)),
                        preview,
                        PanelView.textCentered(file.displayName(), false, layout.textScale()),
                        PanelView.textCentered(modified, true, layout.textScale())));
    }

    /**
     * Single-column card: a small thumbnail, a left-aligned name and timestamp centered vertically
     * against it, and the delete control closing the row. The card fills the row width, so one
     * screen shows more projects than a grid of tall cards.
     */
    private PanelView rowCardFor(final RecentFileSummary file) {
        final byte[] png = previews.get(file.id().value());
        final PanelView preview = png == null
                ? PanelView.centered(
                        PanelView.column(PanelView.textCentered(
                                localization.text("board.noPreview", "No preview"), true, layout.textScale())),
                        ROW_PREVIEW_WIDTH,
                        ROW_PREVIEW_HEIGHT)
                : PanelView.centered(
                        PanelView.scaledImage(png, file.displayName(), ROW_PREVIEW_WIDTH, ROW_PREVIEW_HEIGHT),
                        ROW_PREVIEW_WIDTH,
                        ROW_PREVIEW_HEIGHT);
        final String modified = file.lastModified().map(MODIFIED_FORMAT::format).orElse("");
        // A flat row plus a separator reads as one list; a bordered card plus a separator would
        // draw two competing edges between neighbours.
        return PanelView.flatCard(
                file.id().value(),
                OPEN_ACTION_ID,
                PanelView.spaceBetween(
                        PanelView.leftRow(
                                preview,
                                // Natural height keeps the block at its text height, so the row centers
                                // it against the taller thumbnail instead of pinning it to the top.
                                PanelView.padded(
                                        PanelView.column(
                                                PanelView.text(file.displayName(), false, layout.textScale()),
                                                PanelView.text(modified, true, layout.textScale())),
                                        0,
                                        ROW_TEXT_INSET,
                                        0,
                                        0)),
                        deleteControl(file)));
    }

    /**
     * Per-card delete control. The card id is the action source, so the handler knows which
     * preview to delete without a separate mapping.
     */
    private PanelView deleteControl(final RecentFileSummary file) {
        final byte[] icon = layoutIcons.computeIfAbsent(DELETE_ICON_NAME, this::loadLayoutIcon);
        final String tooltip = localization.text("board.delete.action", "Delete preview");
        return icon.length == 0
                ? PanelView.button(file.id().value(), tooltip, DELETE_ACTION_ID)
                : PanelView.iconButton(file.id().value(), icon, tooltip, DELETE_ACTION_ID);
    }

    /** Icon-only layout control; falls back to a text button if the bundled mask is missing. */
    private PanelView layoutControl() {
        final byte[] icon = layoutIcons.computeIfAbsent(LAYOUT_ICON_NAME, this::loadLayoutIcon);
        final String tooltip = layout.buttonLabel(localization);
        return icon.length == 0
                ? PanelView.button(LAYOUT_ACTION_ID, tooltip, LAYOUT_ACTION_ID)
                : PanelView.iconButton(LAYOUT_ACTION_ID, icon, tooltip, LAYOUT_ACTION_ID);
    }

    private byte[] loadLayoutIcon(final String name) {
        final String path = "/icons/lucide/" + name + ".png";
        try (InputStream stream = RecentProjectBoardPlugin.class.getResourceAsStream(path)) {
            if (stream == null) {
                logger.warn("Recent project board layout icon is missing: " + path);
                return new byte[0];
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            logger.warn("Recent project board layout icon could not be read: " + name);
            return new byte[0];
        }
    }

    /** Board layouts cycled by the header button, from compact to large preview. */
    enum BoardLayout {
        SIX(6, 220, 1.0f),
        THREE(3, 340, 1.15f),
        ONE(1, ROW_PREVIEW_HEIGHT, 1.15f);

        private static final BoardLayout[] ORDER = values();

        private final int columns;
        private final int imageSize;
        private final float textScale;

        BoardLayout(final int columns, final int imageSize, final float textScale) {
            this.columns = columns;
            this.imageSize = imageSize;
            this.textScale = textScale;
        }

        int columns() {
            return columns;
        }

        int imageSize() {
            return imageSize;
        }

        float textScale() {
            return textScale;
        }

        BoardLayout next() {
            return ORDER[(ordinal() + 1) % ORDER.length];
        }

        String buttonLabel(final PluginLocalization localization) {
            final String label = localization.text("board.layout", "Layout") + ": ";
            return this == ONE
                    ? label + localization.text("board.layout.row", "1 row")
                    : label + columns + " " + localization.text("board.columns", "columns");
        }

        static BoardLayout parse(final String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "SIX" -> SIX;
                case "THREE", "FOUR", "TWO" -> THREE;
                case "ONE" -> ONE;
                default -> null;
            };
        }
    }

    /** Loads the persisted layout preference; an unavailable store keeps the default. */
    private void loadLayout() {
        try {
            storage.readUtf8(LAYOUT_PATH, 32)
                    .thenAccept(result -> result.value().ifPresent(value -> {
                        final BoardLayout parsed = BoardLayout.parse(value);
                        if (parsed != null) {
                            layout = parsed;
                            dispatchRefresh();
                        }
                    }));
        } catch (RuntimeException failure) {
            logger.debug("Recent project board layout load skipped: "
                    + failure.getClass().getSimpleName());
        }
    }

    /** Persists the selected layout; a storage failure only loses the preference. */
    private void saveLayout() {
        try {
            storage.writeUtf8Atomic(LAYOUT_PATH, layout.name());
        } catch (RuntimeException failure) {
            logger.debug("Recent project board layout save skipped: "
                    + failure.getClass().getSimpleName());
        }
    }

    /** Cycles to the next layout and re-contributes the board. */
    void cycleLayout() {
        layout = layout.next();
        saveLayout();
        dispatchRefresh();
    }

    /** Test hook: the current layout. */
    BoardLayout layout() {
        return layout;
    }

    /** Registers one board action and enrolls it in the plugin's disposable scope. */
    private void registerAction(
            final String actionId, final String label, final Consumer<ActionRegistry.ActionContext> handler) {
        try {
            context.disposableScope()
                    .register(context.actions().register(actionId, ActionRegistry.Action.of(actionId, label, handler)));
        } catch (RuntimeException failure) {
            logger.warn("Recent project board action registration failed: "
                    + failure.getClass().getSimpleName());
        }
    }

    /** Whether the host currently admits {@code command} for this plugin. */
    boolean canCreate(final EditorCommand command) {
        final EditorCommandService commands = editorCommands;
        if (commands == null || !commands.isAvailable()) {
            return false;
        }
        try {
            return commands.available().contains(command);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Whether the host currently admits the typed NEW_MODEL command for this plugin. */
    boolean canCreateNewProject() {
        return canCreate(EditorCommand.NEW_MODEL);
    }

    /** Whether the host exposes the native New Animation surface. */
    boolean canCreateAnimationProject() {
        return uiHost != null;
    }

    /** Creates a new model/project through the host's typed NEW_MODEL command. */
    void createNewProject() {
        createProject(EditorCommand.NEW_MODEL);
    }

    /** Opens the host's native New Animation flow. */
    void createAnimationProject() {
        if (!canCreateAnimationProject()) {
            logger.warn("Recent project board cannot create an animation: the UI host is unavailable");
            return;
        }
        try {
            if (!uiHost.createNewAnimationProject()) {
                logger.warn("Recent project board New Animation action was not dispatched");
            }
        } catch (RuntimeException failure) {
            logger.warn("Recent project board New Animation action failed: "
                    + failure.getClass().getSimpleName());
        }
    }

    private void createProject(final EditorCommand command) {
        if (!canCreate(command)) {
            logger.warn("Recent project board cannot create a project: " + command.name() + " is unavailable");
            return;
        }
        try {
            final EditorCommandResult result = editorCommands.execute(command);
            if (!result.executed()) {
                logger.warn("Recent project board " + command.name() + " was not executed: " + result.status());
            }
        } catch (RuntimeException failure) {
            logger.warn("Recent project board " + command.name() + " failed: "
                    + failure.getClass().getSimpleName());
        }
    }

    /** Opens one recent project by its opaque id. */
    void open(final String idValue) {
        if (idValue == null || idValue.isBlank() || recentFiles == null) {
            return;
        }
        try {
            final boolean dispatched = recentFiles.open(new RecentFileId(idValue));
            if (!dispatched) {
                logger.warn("Recent project board could not open the selected recent file");
            }
        } catch (RuntimeException failure) {
            logger.warn(
                    "Recent project board open failed: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * Deletes the cached preview of one card. The provider owns the cache, so this only asks it;
     * the local copy drops when the provider publishes the deletion.
     */
    void deletePreview(final String idValue) {
        if (idValue == null || idValue.isBlank()) {
            return;
        }
        final RecentPreviewCacheService cache = previewCache;
        if (cache == null || !cache.isAvailable()) {
            logger.warn("Recent project board cannot delete a preview: the preview cache is unavailable");
            return;
        }
        final RecentFileId id = new RecentFileId(idValue);
        try {
            cache.delete(id).whenComplete((removed, failure) -> {
                if (failure != null) {
                    logger.warn("Recent project board preview deletion failed: "
                            + failure.getClass().getSimpleName());
                    return;
                }
                if (previews.remove(idValue) != null) {
                    dispatchRefresh();
                }
                if (Boolean.TRUE.equals(removed)) {
                    logger.debug("Recent project board deleted the preview of " + idValue);
                }
            });
        } catch (RuntimeException failure) {
            logger.warn("Recent project board preview deletion failed: "
                    + failure.getClass().getSimpleName());
        }
    }

    private void closeBoard() {
        final Registration current = board.getAndSet(null);
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException failure) {
                final PluginLogger currentLogger = logger;
                if (currentLogger != null) {
                    currentLogger.warn("Recent project board close failed: "
                            + failure.getClass().getSimpleName());
                }
            }
        }
    }

    /** Test hook: whether the board is currently enabled. */
    boolean isEnabled() {
        return enabled;
    }
}
