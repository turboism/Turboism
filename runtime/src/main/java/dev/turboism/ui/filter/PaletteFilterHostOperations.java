package dev.turboism.ui.filter;

import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Window;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Session-level host attachment for palette tab filter boxes.
 *
 * <p>Ported from the legacy {@code CubismPaletteToolbarFramework} and the
 * validated legacy palette enhancers. The runtime polls the host for the four
 * palette controllers (PARAMETER, DEFORMER, SCENE, LOG), attaches a keyword
 * filter box to each palette's toolbar, and applies per-palette filtering.
 *
 * <p>5.3.02 host facts (window-tree evidence): palette content components use
 * generic obfuscated class names ({@code com.live2d.ui.swingImpl.*}); deformer
 * and parts are tree-tables ({@code CDeformerTreeTable.e},
 * {@code CPartsTreeTable.g}); parameter rows are native row widgets rendered as
 * Swing components under a viewport; the LOG palette is a non-editable
 * {@code JTextPane} inside a custom scroll shell ({@code swingImpl.y} +
 * {@code JViewport}, not {@code JScrollPane}); the
 * scene palette is located through the validated
 * {@code SceneTableHostOperations} remembered property.</p>
 *
 * <p>Every binding is re-validated each reconcile: stale or detached
 * components are reset and re-resolved, and attach sub-methods return success
 * only when the complete anchor, filter box and filter seam are installed.
 * Real-host readiness is not claimed here; reflective host paths remain
 * pending exact-version validation.</p>
 */
public final class PaletteFilterHostOperations implements PaletteFilterVisibilitySink, AutoCloseable {

    private static final int CONNECT_ATTEMPTS = 300;
    private static final int CONNECT_DELAY_MS = 250;
    private static final int IDLE_CONNECT_DELAY_MS = 2_000;

    private static final String FILTER_PANEL_NAME = "turboismPaletteFilterPanel";
    private static final String FILTER_FIELD_NAME = "turboismPaletteFilterField";
    private static final String CLEAR_BUTTON_NAME = "turboismPaletteFilterClearButton";
    private static final int FILTER_PANEL_WIDTH = 140;
    private static final int FILTER_PANEL_HEIGHT = 26;
    private static final int CLEAR_BUTTON_WIDTH = 20;
    private static final int CLEAR_BUTTON_HEIGHT = 24;
    private static final int TEXT_LEFT_INSET = 6;
    private static final int TEXT_RIGHT_INSET = 28;

    private static final String PALETTE_PROPERTY = "dev.turboism.paletteFilter";
    private static final String FILTERED_TEXT_PANE_KEY = "turboism.paletteFilter.filteredTextPane";
    private static final String WRAPPER_MARKER_KEY = "turboism.paletteFilter.wrapper";
    private static final String SCENE_PALETTE_PROPERTY = "dev.turboism.scenePalette";
    private static final String TOOLBAR_ROW_MARKER_KEY = "turboism.paletteFilter.toolbarRow";
    private static final String APP_INSTANCE = "cubism.editor-model.app-controller.instance";
    private static final String APP_MAIN_FRAME = "cubism.editor-model.app-controller.main-frame";
    private static final String MAIN_FRAME_PARAMETER_PALETTE =
        "cubism.editor-model.main-frame.parameter-palette";
    private static final String PARAMETER_PALETTE_VIEW = "cubism.editor-model.parameter-palette.view";
    private static final String PARAMETER_VIEW_OPERATION =
        "cubism.editor-model.parameter-palette-view.operation";
    private static final String PARAMETER_OPERATION_ROWS =
        "cubism.editor-model.parameter-operation.rows";

    /** Palette kind names understood by this host. */
    public enum PaletteKind {
        PARAMETER("palette.parameter"),
        DEFORMER("palette.deformer"),
        SCENE("palette.scene"),
        LOG("palette.log");

        private final String classHint;

        PaletteKind(final String classHint) {
            this.classHint = classHint;
        }
    }

    private final Map<PaletteKind, PaletteFilterState> states = new ConcurrentHashMap<>();
    private final Map<String, List<PaletteFilterRegistry.PaletteFilterContribution>> contributionsByPlugin =
        new ConcurrentHashMap<>();
    private final PaletteControllerResolver controllerResolver;
    private final Map<PaletteKind, String> lastAttachStatus = new ConcurrentHashMap<>();
    private volatile SceneFilterSink sceneFilterSink;
    private volatile dev.turboism.sdk.runtime.CubismLogService cubismLogService;
    private volatile dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator parameterRows;
    private volatile VerifiedMemberResolver parameterRowsResolver;
    private volatile ClassLoader hostClassLoader;
    private volatile long connectionToken;
    private volatile boolean connected;
    private long lastParameterReplayMillis;

    /** Test seam: resolves the palette root object for a palette kind. */
    interface PaletteControllerResolver {
        Object resolve(PaletteKind kind);
    }

    /** Single-owner sink for scene filtering; implemented by the scene table host. */
    public interface SceneFilterSink {
        void setSceneFilter(String keyword);
    }

    public PaletteFilterHostOperations() {
        this(null);
    }

    PaletteFilterHostOperations(final PaletteControllerResolver controllerResolver) {
        this.controllerResolver = controllerResolver;
        for (PaletteKind kind : PaletteKind.values()) {
            states.put(kind, new PaletteFilterState(kind));
        }
    }

    /** Binds the scene table host as the single owner of scene row filtering. */
    public void bindSceneFilterSink(final SceneFilterSink sink) {
        this.sceneFilterSink = Objects.requireNonNull(sink, "sink");
    }

    /** Binds the framework Cubism log service so log filtering drives pre-render filtering. */
    public void bindCubismLogService(final dev.turboism.sdk.runtime.CubismLogService service) {
        this.cubismLogService = Objects.requireNonNull(service, "service");
    }

    /** Binds the exact native parameter-row catalog populated by the verified row hook. */
    public void bindParameterRows(
        final dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator source
    ) {
        this.parameterRows = Objects.requireNonNull(source, "source");
    }

    /** Binds the exact Editor-model resolver used to enumerate rows created before hook installation. */
    public void bindParameterRowsResolver(final VerifiedMemberResolver resolver) {
        this.parameterRowsResolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Clears the session-owned parameter-row resolver during host replacement or shutdown. */
    public void clearParameterRowsResolver() {
        this.parameterRowsResolver = null;
    }

    /** Publishes the current log filter to the framework service (pre-render interception). */
    private void publishLogFilter(final PaletteFilterState state) {
        if (cubismLogService == null) {
            return;
        }
        cubismLogService.setFilter(new dev.turboism.sdk.runtime.CubismLogService.LogFilter(
            state.showInfo, state.showWarn, state.showError, state.filterText));
    }

    /** Starts EDT polling for the four palette controllers. */
    public void connect(final ClassLoader hostClassLoader) {
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        final long token = ++connectionToken;
        connected = true;
        onEdt(() -> connect(hostClassLoader, token, 0));
    }

    private void connect(final ClassLoader hostClassLoader, final long token, final int attempt) {
        if (token != connectionToken || !connected) {
            return;
        }
        try {
            reconcilePalettes();
        } catch (Throwable failure) {
            for (PaletteKind kind : PaletteKind.values()) {
                lastAttachStatus.put(kind, "reconcile-failed:" + failure.getClass().getSimpleName()
                    + ":" + failure.getMessage());
            }
        }
        final boolean fast = attempt + 1 < CONNECT_ATTEMPTS;
        final Timer retry = new Timer(
            fast ? CONNECT_DELAY_MS : IDLE_CONNECT_DELAY_MS,
            ignored -> connect(hostClassLoader, token, attempt + 1)
        );
        retry.setRepeats(false);
        retry.start();
    }

    @Override
    public void onPaletteFilterVisibilityChanged(
        final String pluginId,
        final List<PaletteFilterRegistry.PaletteFilterContribution> contributions
    ) {
        Objects.requireNonNull(pluginId, "pluginId");
        if (contributions.isEmpty()) {
            contributionsByPlugin.remove(pluginId);
        } else {
            contributionsByPlugin.put(pluginId, List.copyOf(contributions));
        }
        onEdt(this::reconcilePalettes);
    }

    private boolean reconcilePalettes() {
        boolean allAttached = true;
        for (PaletteKind kind : PaletteKind.values()) {
            final PaletteFilterRegistry.PaletteFilterContribution contribution = highestOrderContribution(kind);
            final PaletteFilterState state = states.get(kind);
            if (contribution == null) {
                detach(state);
                continue;
            }
            if (!attach(state, contribution)) {
                allAttached = false;
            }
        }
        writeAttachStatus();
        return allAttached;
    }

    /** Appends the latest per-palette attach outcomes as machine-readable evidence. */
    private void writeAttachStatus() {
        final StringBuilder lines = new StringBuilder();
        for (PaletteKind kind : PaletteKind.values()) {
            lines.append("palette=").append(kind.name())
                .append(" status=").append(lastAttachStatus.getOrDefault(kind, "not-attempted"))
                .append("\n");
        }
        try {
            final String home = System.getProperty("turboism.home", "");
            if (!home.isEmpty()) {
                final java.nio.file.Path output = java.nio.file.Path.of(home, "logs", "runtime", "palette-filter-attach.tsv");
                java.nio.file.Files.writeString(
                    output,
                    lines.toString(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE
                );
            }
        } catch (Exception ignored) {
            // Evidence best-effort only; never fail host attachment for a write problem.
        }
    }


    private PaletteFilterRegistry.PaletteFilterContribution highestOrderContribution(final PaletteKind kind) {
        return contributionsByPlugin.values().stream()
            .flatMap(List::stream)
            .filter(contribution -> kind.name().equals(contribution.paletteId()))
            .max(Comparator.comparingInt(PaletteFilterRegistry.PaletteFilterContribution::order))
            .orElse(null);
    }

    @Override
    public void close() {
        connected = false;
        connectionToken++;
        onEdt(() -> {
            for (PaletteKind kind : PaletteKind.values()) {
                detach(states.get(kind));
            }
        });
    }

    /** Structured attach status for diagnostics (kind -> outcome description). */
    public Map<PaletteKind, String> attachStatus() {
        return Map.copyOf(lastAttachStatus);
    }

    // ------------------------------------------------------------------ attach

    private boolean attach(final PaletteFilterState state, final PaletteFilterRegistry.PaletteFilterContribution contribution) {
        if (!bindingIsCurrent(state)) {
            resetBinding(state, true);
        }
        if (state.controller == null) {
            final Object resolved = controllerResolver == null
                ? resolvePaletteController(state.kind)
                : controllerResolver.resolve(state.kind);
            if (resolved == null) {
                lastAttachStatus.put(state.kind, "root-not-found");
                return false;
            }
            state.controller = resolved;
            state.root = resolved instanceof JComponent component ? component : null;
            if (state.root == null) {
                lastAttachStatus.put(state.kind, "root-not-swing:" + resolved.getClass().getName());
                resetBinding(state, true);
                return false;
            }
        }
        final boolean installed;
        try {
            installed = switch (state.kind) {
                case SCENE -> attachScene(state, contribution);
                case LOG -> attachLog(state, contribution);
                case PARAMETER -> attachParameter(state, contribution);
                case DEFORMER -> attachDeformer(state, contribution);
            };
        } catch (Throwable failure) {
            lastAttachStatus.put(state.kind, "attach-failed:" + failure.getClass().getSimpleName()
                + ":" + failure.getMessage());
            resetBinding(state, true);
            return false;
        }
        if (!installed) {
            resetBinding(state, true);
        }
        return installed;
    }

    /** Validates that the cached binding still refers to live, visible, connected components. */
    private static boolean bindingIsCurrent(final PaletteFilterState state) {
        final JComponent root = state.root;
        if (root != null) {
            if (!root.isDisplayable()) {
                return false;
            }
            if (!isInVisibleCubismWindow(root)) {
                return false;
            }
        }
        final JTable table = state.table;
        if (table != null && (!table.isDisplayable() || !isInVisibleCubismWindow(table))) {
            return false;
        }
        final JTextPane textPane = state.sourceTextPane;
        if (textPane != null && state.kind != PaletteKind.LOG
            && (!textPane.isDisplayable() || !isInVisibleCubismWindow(textPane))) {
            return false;
        }
        if (state.kind == PaletteKind.LOG && state.filteredDoc != null) {
            if (state.sourceTextPane == null || state.sourceTextPane.getDocument() != state.filteredDoc) {
                return false;
            }
            if (!state.sourceTextPane.isDisplayable() || !isInVisibleCubismWindow(state.sourceTextPane)) {
                return false;
            }
        }
        if (state.filterBox != null) {
            if (state.kind == PaletteKind.LOG) {
                if (state.scrollShell != null && state.filterBox.panel.getParent() != state.toolbarPanel) {
                    return false;
                }
            } else if (state.toolbarPlacement == null || !state.toolbarPlacement.isCurrent()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isInVisibleCubismWindow(final Component component) {
        final Window window = SwingUtilities.getWindowAncestor(component);
        return window != null
            && window.isVisible()
            && window.getClass().getName().startsWith("com.live2d.ui.window.CFrame");
    }

    /**
     * Removes injected UI and clears the cached binding. When
     * {@code preserveFilterText} is true the user's keyword survives a
     * re-location; plugin removal/close passes false.
     */
    private static void resetBinding(final PaletteFilterState state, final boolean preserveFilterText) {
        restoreParameterRows(state);
        final String filterText = preserveFilterText ? state.filterText : "";
        detachFilterBox(state);
        if (state.sourceDocumentListener != null && state.sourceDoc != null) {
            state.sourceDoc.removeDocumentListener(state.sourceDocumentListener);
        }
        if (state.sourceTextPane != null && state.filteredDoc != null
            && state.sourceTextPane.getDocument() == state.filteredDoc) {
            state.sourceTextPane.setDocument(state.sourceDoc);
        }
        if (state.wrapper != null && state.scrollShell != null) {
            final Container parent = state.wrapper.getParent();
            if (parent != null) {
                replaceComponent(parent, state.wrapper, state.scrollShell);
            }
        }
        if (state.filteredTreeModel != null) {
            if (state.tree != null && state.treeModel != null
                && state.tree.getModel() == state.filteredTreeModel) {
                state.tree.setModel(state.treeModel);
                refreshTableModel(state.table);
            }
            state.filteredTreeModel.dispose();
            state.filteredTreeModel = null;
        }
        state.controller = null;
        state.scenePalette = null;
        state.root = null;
        state.table = null;
        state.sourceTextPane = null;
        state.sourceDoc = null;
        state.filteredDoc = null;
        state.viewport = null;
        state.scrollShell = null;
        state.wrapper = null;
        state.toolbarPanel = null;
        state.toolbar = null;
        state.filterBox = null;
        state.levelPanel = null;
        state.infoButton = null;
        state.warnButton = null;
        state.errorButton = null;
        // Level toggles survive re-binding: they are user preferences, not binding state.
        // (kept out of reset so the log filter state persists across reconciles)
        state.tree = null;
        state.treeModel = null;
        state.tableModel = null;
        state.rows = List.of();
        state.originalRowVisibility.clear();
        state.lastRawText = "";
        state.lastKeyword = "";
        state.lastFiltered = "";
        state.filterText = filterText;
    }

    /** Replaces {@code component} with {@code replacement} preserving layout constraint and z-order. */
    private static void replaceComponent(final Container parent, final Component component, final Component replacement) {
        final LayoutManager layout = parent.getLayout();
        final Object constraint = layout instanceof BorderLayout
            ? ((BorderLayout) layout).getConstraints(component)
            : null;
        final int index = parent.getComponentZOrder(component);
        parent.remove(component);
        if (constraint != null) {
            parent.add(replacement, constraint);
        } else {
            final int safeIndex = index < 0 ? parent.getComponentCount() : Math.min(index, parent.getComponentCount());
            parent.add(replacement, safeIndex);
        }
        parent.revalidate();
        parent.repaint();
    }

    // ------------------------------------------------------- palette resolution

    /**
     * Resolves the palette root per kind using 5.3.02 structural anchors rather
     * than a uniform class-name substring scan.
     */
    private Object resolvePaletteController(final PaletteKind kind) {
        switch (kind) {
            case SCENE -> {
                final JTable remembered = findRememberedSceneTable();
                if (remembered != null) {
                    return remembered;
                }
            }
            case DEFORMER -> {
                final JTable table = findTreeTable("com.live2d.cubism.view.palette.deformer.CDeformerTreeTable");
                if (table != null) {
                    return table;
                }
            }
            case PARAMETER -> {
                final JComponent root = findParameterRowsRoot();
                if (root != null) {
                    return root;
                }
            }
            case LOG -> {
                final JTextPane pane = findLogTextPane();
                if (pane != null) {
                    return pane;
                }
            }
        }
        // Fallback: class-path hint scan (kept for unknown shapes; fails closed otherwise).
        for (Window window : Window.getWindows()) {
            final Object root = findPaletteRoot(window, kind, null);
            if (root != null) {
                return root;
            }
        }
        return null;
    }

    private static Object findPaletteRoot(
        final Component component,
        final PaletteKind kind,
        final ClassLoader hostClassLoader
    ) {
        final String cacheKey = PALETTE_PROPERTY + "." + kind.name();
        final Object remembered = component instanceof JComponent
            ? ((JComponent) component).getClientProperty(cacheKey)
            : null;
        if (remembered instanceof java.lang.ref.WeakReference<?> reference && reference.get() != null) {
            return reference.get();
        }
        if (matchesPaletteRoot(component, kind)) {
            if (component instanceof JComponent jComponent) {
                jComponent.putClientProperty(cacheKey, new java.lang.ref.WeakReference<>(component));
            }
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final Object root = findPaletteRoot(child, kind, hostClassLoader);
                if (root != null) {
                    return root;
                }
            }
        }
        return null;
    }

    private static boolean matchesPaletteRoot(final Component component, final PaletteKind kind) {
        final String name = component.getClass().getName();
        if (kind == PaletteKind.DEFORMER) {
            return name.startsWith("com.live2d.cubism.view.palette.deformer.CDeformerTreeTable");
        }
        return name.contains(kind.classHint);
    }

    /** Finds the JTable remembered by the scene-table host (validated 5.3.02 property). */
    private static JTable findRememberedSceneTable() {
        for (Window window : Window.getWindows()) {
            final JTable table = findRememberedSceneTable(window);
            if (table != null) {
                return table;
            }
        }
        return null;
    }

    private static JTable findRememberedSceneTable(final Component component) {
        if (component instanceof JTable table) {
            final Object remembered = table.getClientProperty(SCENE_PALETTE_PROPERTY);
            if (remembered instanceof java.lang.ref.WeakReference<?> reference && reference.get() != null) {
                return table;
            }
            // Native scene row listener (exact 5.3.02 class) carrying the palette in field "a".
            for (java.awt.event.MouseListener listener : table.getMouseListeners()) {
                if (listener != null && listener.getClass().getName().equals(
                    "com.live2d.cubism.view.palette.scene.m")) {
                    return table;
                }
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTable found = findRememberedSceneTable(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Finds a tree-table whose class name starts with the given 5.3.02 prefix. */
    private static JTable findTreeTable(final String classNamePrefix) {
        for (Window window : Window.getWindows()) {
            final JTable table = findTreeTable(window, classNamePrefix);
            if (table != null) {
                return table;
            }
        }
        return null;
    }

    private static JTable findTreeTable(final Component component, final String classNamePrefix) {
        if (component instanceof JTable table
            && table.getClass().getName().startsWith(classNamePrefix)
            && table.isDisplayable()
            && isInVisibleCubismWindow(table)) {
            return table;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTable found = findTreeTable(child, classNamePrefix);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Resolves the parameter viewport from exact row bindings, never from an unrelated JTree. */
    private JComponent findParameterRowsRoot() {
        final dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator source = parameterRows;
        if (source == null) {
            return null;
        }
        JComponent root = parameterRowsRoot(source);
        if (root != null) {
            return root;
        }
        final long now = System.currentTimeMillis();
        if (now - lastParameterReplayMillis >= 1_000) {
            lastParameterReplayMillis = now;
            replayExistingParameterRows();
            root = parameterRowsRoot(source);
        }
        return root;
    }

    private static JComponent parameterRowsRoot(
        final dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator source
    ) {
        for (dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator.ParameterControlBinding binding
            : source.parameterControlBindings()) {
            final Component label = binding.label();
            if (!label.isDisplayable() || !isInVisibleCubismWindow(label)) {
                continue;
            }
            final JViewport viewport = findAncestorViewport(label);
            if (viewport != null && viewport.getView() instanceof JComponent root
                && findParameterToolbar(root) != null) {
                return root;
            }
        }
        return null;
    }

    private void replayExistingParameterRows() {
        final VerifiedMemberResolver resolver = parameterRowsResolver;
        if (resolver == null) {
            return;
        }
        try {
            final Object app = resolver.invokeStatic(APP_INSTANCE);
            final Object mainFrame = app == null ? null : resolver.invoke(APP_MAIN_FRAME, app);
            final Object palette = mainFrame == null
                ? null : resolver.invoke(MAIN_FRAME_PARAMETER_PALETTE, mainFrame);
            final Object view = palette == null ? null : resolver.invoke(PARAMETER_PALETTE_VIEW, palette);
            final Object operation = view == null ? null : resolver.invoke(PARAMETER_VIEW_OPERATION, view);
            final Object rows = operation == null ? null : resolver.invoke(PARAMETER_OPERATION_ROWS, operation);
            if (rows instanceof Iterable<?> iterable) {
                dev.turboism.ui.appearance.control.NativeParameterAppearanceBridge.replayExistingRows(iterable);
            }
        } catch (RuntimeException ignored) {
            // Palette/document may not be ready yet; the bounded connector retries.
        }
    }

    private static final String PARAM_ADD_COMMAND = "CMD_PARAMETER_PALETTE_ADD_NEW_PARAMETER";
    private static final String PARAM_FOLDER_COMMAND = "CMD_PARAMETER_PALETTE_NEW_FOLDER";
    private static final String PARAM_DELETE_COMMAND = "CMD_PARAMETER_PALETTE_DELETE_OBJECT";

    private static boolean isParameterToolbar(final Component component) {
        if (!(component instanceof Container container)) {
            return false;
        }
        final List<AbstractButton> buttons = collectButtons(container, 2);
        if (buttons.size() != 3) {
            return false;
        }
        boolean hasAdd = false;
        boolean hasFolder = false;
        boolean hasDelete = false;
        for (AbstractButton button : buttons) {
            final String action = normalize(button.getActionCommand());
            final String tooltip = normalize(button.getToolTipText());
            final String text = normalize(button.getText());
            // Exact legacy command first; fall back to multi-language labels.
            hasAdd |= action.equals(normalize(PARAM_ADD_COMMAND))
                || action.contains("add_new_parameter") || action.contains("newparameter") || action.contains("createparameter")
                || tooltip.contains("创建新参数") || tooltip.contains("create parameter") || tooltip.contains("パラメータ作成")
                || text.contains("创建新参数");
            hasFolder |= action.equals(normalize(PARAM_FOLDER_COMMAND))
                || action.contains("new_folder") || action.contains("newfolder") || action.contains("createfolder")
                || tooltip.contains("创建新文件夹") || tooltip.contains("create folder") || tooltip.contains("フォルダ作成")
                || text.contains("创建新文件夹");
            hasDelete |= action.equals(normalize(PARAM_DELETE_COMMAND))
                || action.contains("delete") || action.contains("remove")
                || tooltip.contains("删除选定的元素") || tooltip.contains("delete selected") || tooltip.contains("削除")
                || text.contains("删除");
        }
        return hasAdd && hasFolder && hasDelete;
    }

    private static List<AbstractButton> collectButtons(final Container root, final int depth) {
        final List<AbstractButton> buttons = new ArrayList<>();
        collectButtons(root, depth, buttons);
        return buttons;
    }

    private static void collectButtons(final Component component, final int depth, final List<AbstractButton> buttons) {
        if (component == null || depth < 0) {
            return;
        }
        if (component instanceof AbstractButton button) {
            buttons.add(button);
            return;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectButtons(child, depth - 1, buttons);
            }
        }
    }

    /** Finds the LOG palette text pane: visible main frame, non-editable JTextPane with viewport scroll shell. */
    private static JTextPane findLogTextPane() {
        for (Window window : Window.getWindows()) {
            if (!window.isVisible() || !window.getClass().getName().startsWith("com.live2d.ui.window.CFrame")) {
                continue;
            }
            final JTextPane pane = findLogTextPane(window);
            if (pane != null) {
                return pane;
            }
        }
        return null;
    }

    private static JTextPane findLogTextPane(final Component component) {
        if (component instanceof JTextPane pane
            && !pane.isEditable()
            && !Boolean.TRUE.equals(pane.getClientProperty(FILTERED_TEXT_PANE_KEY))
            && pane.isDisplayable()
            && findAncestorViewport(pane) != null) {
            return pane;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTextPane found = findLogTextPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JViewport findAncestorViewport(final Component component) {
        Component current = component == null ? null : component.getParent();
        while (current != null) {
            if (current instanceof JViewport viewport) {
                return viewport;
            }
            current = current.getParent();
        }
        return null;
    }

    // ------------------------------------------------------------ attach kinds

    private boolean attachScene(final PaletteFilterState state, final PaletteFilterRegistry.PaletteFilterContribution contribution) {
        final JComponent component = state.root;
        if (component == null) {
            return false;
        }
        final JTable table = component instanceof JTable tableValue ? tableValue : findTable(component);
        if (table == null) {
            lastAttachStatus.put(state.kind, "scene-table-not-found root=" + component.getClass().getName());
            return false;
        }
        final Object palette = reverseResolvePalette(table);
        if (palette == null) {
            lastAttachStatus.put(state.kind, "scene-palette-not-found table=" + table.getClass().getName());
            return false;
        }
        state.table = table;
        state.scenePalette = palette;
        state.root = table;
        final Container toolbar = findToolbarContainer(table);
        if (toolbar == null) {
            lastAttachStatus.put(state.kind, "scene-toolbar-not-found table=" + table.getClass().getName()
                + " palette=" + palette.getClass().getName());
            return false;
        }
        state.toolbar = toolbar;
        ensureFilterBox(state, toolbar, contribution, text -> {
            state.filterText = normalize(text);
            if (sceneFilterSink != null) {
                sceneFilterSink.setSceneFilter(state.filterText);
            }
        });
        if (sceneFilterSink != null) {
            sceneFilterSink.setSceneFilter(state.filterText);
        }
        lastAttachStatus.put(state.kind, "attached table=" + table.getClass().getName()
            + " toolbar=" + toolbar.getClass().getName() + " palette=" + palette.getClass().getName()
            + " sink=" + (sceneFilterSink != null));
        return true;
    }

    private boolean attachDeformer(final PaletteFilterState state, final PaletteFilterRegistry.PaletteFilterContribution contribution) {
        final JComponent component = state.root;
        if (component == null) {
            return false;
        }
        final JTable table = component instanceof JTable tableValue ? tableValue : findTable(component);
        if (table == null) {
            lastAttachStatus.put(state.kind, "deformer-table-not-found root=" + component.getClass().getName());
            return false;
        }
        state.table = table;
        final Container toolbar = findToolbarContainer(table);
        if (toolbar == null) {
            lastAttachStatus.put(state.kind, "deformer-toolbar-not-found table=" + table.getClass().getName());
            return false;
        }
        final JTree tree = extractTree(table);
        if (tree == null) {
            lastAttachStatus.put(state.kind, "deformer-tree-not-found table=" + table.getClass().getName());
            return false;
        }
        state.toolbar = toolbar;
        state.tree = tree;
        if (state.treeModel == null) {
            state.treeModel = tree.getModel();
        }
        ensureFilterBox(state, toolbar, contribution, text -> applyTreeFilter(state, tree, text));
        applyTreeFilter(state, tree, state.filterText);
        lastAttachStatus.put(state.kind, "attached table=" + table.getClass().getName()
            + " toolbar=" + toolbar.getClass().getName() + " tree=" + tree.getClass().getName()
            + " treeModel=" + tree.getModel().getClass().getName()
            + " tableModel=" + table.getModel().getClass().getName()
            + " treeRows=" + tree.getRowCount() + " tableRows=" + table.getRowCount()
            + " filterText=[" + state.filterText + "]");
        return true;
    }

    private boolean attachParameter(final PaletteFilterState state, final PaletteFilterRegistry.PaletteFilterContribution contribution) {
        final JComponent component = state.root;
        if (component == null) {
            return false;
        }
        final Container toolbar = findParameterToolbar(component);
        if (toolbar == null) {
            lastAttachStatus.put(state.kind, "parameter-toolbar-not-found root=" + component.getClass().getName());
            return false;
        }
        final List<ParameterFilterRow> rows = parameterFilterRows(component);
        if (rows.isEmpty()) {
            lastAttachStatus.put(state.kind, "parameter-rows-not-found root=" + component.getClass().getName());
            return false;
        }
        state.toolbar = toolbar;
        state.rows = rows;
        final Set<JComponent> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ParameterFilterRow row : rows) {
            live.add(row.component());
            state.originalRowVisibility.putIfAbsent(row.component(), row.component().isVisible());
        }
        restoreDiscardedParameterRows(state.originalRowVisibility, live);
        ensureFilterBox(state, toolbar, contribution, text -> applyParameterFilter(state, text));
        applyParameterFilter(state, state.filterText);
        lastAttachStatus.put(state.kind, "attached rows=" + rows.size()
            + " root=" + component.getClass().getName()
            + " toolbar=" + toolbar.getClass().getName()
            + " filterText=[" + state.filterText + "]");
        return true;
    }

    private List<ParameterFilterRow> parameterFilterRows(final JComponent root) {
        final dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator source = parameterRows;
        if (source == null) {
            return List.of();
        }
        final Map<JComponent, StringBuilder> textByRow = new java.util.IdentityHashMap<>();
        final Map<JComponent, Boolean> folderByRow = new java.util.IdentityHashMap<>();
        final List<JComponent> order = new ArrayList<>();
        for (dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator.ParameterControlBinding binding
            : source.parameterControlBindings()) {
            final Component label = binding.label();
            if (!SwingUtilities.isDescendingFrom(label, root)) {
                continue;
            }
            final JComponent row = parameterRowComponent(label, binding.folder());
            if (row == null) {
                continue;
            }
            final StringBuilder searchText = textByRow.computeIfAbsent(row, ignored -> {
                order.add(row);
                return new StringBuilder();
            });
            appendToken(searchText, binding.id());
            if (label instanceof JLabel swingLabel) {
                appendToken(searchText, swingLabel.getText());
            }
            folderByRow.merge(row, binding.folder(), Boolean::logicalOr);
        }
        final List<ParameterFilterRow> rows = new ArrayList<>(order.size());
        for (JComponent row : order) {
            rows.add(new ParameterFilterRow(
                row,
                textByRow.get(row).toString(),
                Boolean.TRUE.equals(folderByRow.get(row))
            ));
        }
        return List.copyOf(rows);
    }

    private static JComponent parameterRowComponent(final Component label, final boolean folder) {
        final String expected = folder
            ? "com.live2d.ui.swingImpl.n"
            : "com.live2d.ui.swingImpl.p";
        Component current = label;
        while (current != null && !(current instanceof JViewport)) {
            if (current instanceof JComponent component && current.getClass().getName().equals(expected)) {
                return component;
            }
            current = current.getParent();
        }
        return null;
    }

    private static void applyParameterFilter(final PaletteFilterState state, final String text) {
        state.filterText = normalize(text);
        applyParameterRows(state.rows, state.originalRowVisibility, state.filterText);
    }

    static void applyParameterRows(
        final List<ParameterFilterRow> rows,
        final Map<JComponent, Boolean> originalVisibility,
        final String text
    ) {
        final String keyword = normalize(text);
        final Set<JComponent> visible = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (!keyword.isEmpty()) {
            for (ParameterFilterRow row : rows) {
                if (row.searchText().contains(keyword)) {
                    visible.add(row.component());
                    // ponytail: O(n²) ancestor scan; index folder ancestry only if EDT typing becomes measurable.
                    for (ParameterFilterRow candidate : rows) {
                        if (candidate.folder() && (candidate.component() == row.component()
                            || SwingUtilities.isDescendingFrom(row.component(), candidate.component()))) {
                            visible.add(candidate.component());
                        }
                    }
                }
            }
        }
        final Set<Container> dirty = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ParameterFilterRow row : rows) {
            final boolean next = keyword.isEmpty()
                ? originalVisibility.getOrDefault(row.component(), true)
                : visible.contains(row.component());
            if (row.component().isVisible() != next) {
                row.component().setVisible(next);
            }
            if (row.component().getParent() != null) {
                dirty.add(row.component().getParent());
            }
        }
        for (Container container : dirty) {
            container.revalidate();
            container.repaint();
        }
    }

    private static void restoreParameterRows(final PaletteFilterState state) {
        for (Map.Entry<JComponent, Boolean> entry : state.originalRowVisibility.entrySet()) {
            entry.getKey().setVisible(entry.getValue());
            if (entry.getKey().getParent() != null) {
                entry.getKey().getParent().revalidate();
                entry.getKey().getParent().repaint();
            }
        }
    }

    private static void restoreDiscardedParameterRows(
        final Map<JComponent, Boolean> originalVisibility,
        final Set<JComponent> live
    ) {
        final java.util.Iterator<Map.Entry<JComponent, Boolean>> iterator =
            originalVisibility.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<JComponent, Boolean> entry = iterator.next();
            if (live.contains(entry.getKey())) {
                continue;
            }
            entry.getKey().setVisible(entry.getValue());
            if (entry.getKey().getParent() != null) {
                entry.getKey().getParent().revalidate();
                entry.getKey().getParent().repaint();
            }
            iterator.remove();
        }
    }

    static record ParameterFilterRow(JComponent component, String searchText, boolean folder) {
        ParameterFilterRow {
            component = Objects.requireNonNull(component, "component");
            searchText = normalize(searchText);
        }
    }


    private boolean attachLog(final PaletteFilterState state, final PaletteFilterRegistry.PaletteFilterContribution contribution) {
        // Installed fast path: keep identity stable across reconciles.
        if (state.filteredDoc != null && state.sourceTextPane != null
            && state.sourceTextPane.getDocument() == state.filteredDoc
            && state.sourceTextPane.isDisplayable()) {
            refreshFilteredLogText(state);
            lastAttachStatus.put(state.kind, "attached(installed) pane="
                + state.sourceTextPane.getClass().getName()
                + " scrollShell=" + state.scrollShell.getClass().getName());
            return true;
        }
        final JComponent component = state.root;
        if (component == null) {
            return false;
        }
        final JTextPane textPane = component instanceof JTextPane pane ? pane : findTextPane(component);
        if (textPane == null) {
            lastAttachStatus.put(state.kind, "log-textpane-not-found root=" + component.getClass().getName());
            return false;
        }
        final JViewport viewport = findAncestorViewport(textPane);
        if (viewport == null) {
            lastAttachStatus.put(state.kind, "log-viewport-not-found pane=" + textPane.getClass().getName());
            return false;
        }
        final Container scrollShell = viewport.getParent();
        if (scrollShell == null) {
            lastAttachStatus.put(state.kind, "log-scrollshell-not-found");
            return false;
        }
        state.sourceTextPane = textPane;
        state.viewport = viewport;
        state.scrollShell = scrollShell;
        ensureLogToolbar(state, scrollShell, contribution);
        // Pre-render filtering: keep the native JTextPane and its viewport untouched;
        // filter the data at the Document layer instead of swapping the view.
        state.sourceDoc = textPane.getDocument();
        installFilteredDocument(state);
        refreshFilteredLogText(state);
        lastAttachStatus.put(state.kind, "attached pane=" + textPane.getClass().getName()
            + " scrollShell=" + scrollShell.getClass().getName()
            + " documentFiltered=" + (textPane.getDocument() != state.sourceDoc));
        return true;
    }

    // ------------------------------------------------------------- filter box

    /** Creates the filter box (placeholder field + clear button overlay). Pure Swing, ported from legacy. */
    static FilterBox createFilterBox(
        final String placeholder,
        final String initialText,
        final Consumer<String> onTextChanged
    ) {
        final JPanel filterPanel = new JPanel(new BorderLayout());
        filterPanel.setName(FILTER_PANEL_NAME);
        filterPanel.setOpaque(false);
        filterPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        filterPanel.setPreferredSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));
        filterPanel.setMinimumSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));
        filterPanel.setMaximumSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));

        final JTextField filterField = new JTextField() {
            @Override
            protected void paintComponent(final Graphics graphics) {
                super.paintComponent(graphics);
                if (!getText().isEmpty() || isFocusOwner()) {
                    return;
                }
                final Graphics2D graphics2d = (Graphics2D) graphics.create();
                try {
                    graphics2d.setRenderingHint(
                        RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    graphics2d.setColor(new Color(150, 150, 150));
                    graphics2d.setFont(getFont());
                    final Insets insets = getInsets();
                    final FontMetrics fontMetrics = graphics2d.getFontMetrics();
                    final int textX = insets.left;
                    final int textY = (getHeight() - fontMetrics.getHeight()) / 2 + fontMetrics.getAscent();
                    graphics2d.drawString(placeholder, textX, textY);
                } finally {
                    graphics2d.dispose();
                }
            }
        };
        filterField.setName(FILTER_FIELD_NAME);
        filterField.setMargin(new Insets(0, TEXT_LEFT_INSET, 0, TEXT_RIGHT_INSET));
        filterField.setToolTipText(placeholder);

        final JButton clearButton = new JButton("×");
        clearButton.setName(CLEAR_BUTTON_NAME);
        clearButton.setFocusable(false);
        clearButton.setMargin(new Insets(0, 0, 0, 0));
        clearButton.setBorder(BorderFactory.createEmptyBorder());
        clearButton.setBorderPainted(false);
        clearButton.setContentAreaFilled(false);
        clearButton.setOpaque(false);
        clearButton.setFocusPainted(false);
        clearButton.setFont(filterField.getFont().deriveFont(Font.BOLD, 13f));
        clearButton.setForeground(new Color(70, 70, 70));
        clearButton.setPreferredSize(new Dimension(CLEAR_BUTTON_WIDTH, CLEAR_BUTTON_HEIGHT));
        clearButton.setMinimumSize(new Dimension(CLEAR_BUTTON_WIDTH, CLEAR_BUTTON_HEIGHT));
        clearButton.addActionListener(event -> {
            filterField.setText("");
            filterField.requestFocusInWindow();
        });

        final JPanel fieldOverlay = new JPanel(null) {
            @Override
            public void doLayout() {
                final int width = getWidth();
                final int height = getHeight();
                filterField.setBounds(0, 0, width, height);
                final int buttonHeight = Math.min(CLEAR_BUTTON_HEIGHT, Math.max(0, height));
                final int buttonX = Math.max(0, width - CLEAR_BUTTON_WIDTH - 4);
                final int buttonY = Math.max(0, (height - buttonHeight) / 2);
                clearButton.setBounds(buttonX, buttonY, CLEAR_BUTTON_WIDTH, buttonHeight);
            }
        };
        fieldOverlay.setOpaque(false);
        fieldOverlay.setPreferredSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));
        fieldOverlay.setMinimumSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));
        fieldOverlay.setMaximumSize(new Dimension(FILTER_PANEL_WIDTH, FILTER_PANEL_HEIGHT));
        fieldOverlay.add(filterField);
        fieldOverlay.add(clearButton);
        fieldOverlay.setComponentZOrder(clearButton, 0);
        fieldOverlay.setComponentZOrder(filterField, 1);
        filterPanel.add(fieldOverlay, BorderLayout.CENTER);

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(final DocumentEvent event) { update(); }
            @Override public void removeUpdate(final DocumentEvent event) { update(); }
            @Override public void changedUpdate(final DocumentEvent event) { update(); }
            private void update() {
                onTextChanged.accept(filterField.getText());
            }
        });
        filterField.setText(initialText);
        return new FilterBox(filterPanel, filterField, clearButton);
    }

    static final class FilterBox {
        final JPanel panel;
        final JTextField field;
        final JButton clearButton;

        FilterBox(final JPanel panel, final JTextField field, final JButton clearButton) {
            this.panel = panel;
            this.field = field;
            this.clearButton = clearButton;
        }
    }

    /** Framework-owned placement: contribution left, untouched host toolbar right. */
    static ToolbarPlacement attachToolbarContribution(
        final Container toolbar,
        final JComponent contribution
    ) {
        Objects.requireNonNull(toolbar, "toolbar");
        Objects.requireNonNull(contribution, "contribution");
        final Container parent = toolbar.getParent();
        if (parent == null) {
            toolbar.add(contribution, 0);
            toolbar.revalidate();
            toolbar.repaint();
            return new ToolbarPlacement(toolbar, contribution, null, null, -1, null);
        }

        final LayoutManager layout = parent.getLayout();
        final Object constraint = layout instanceof BorderLayout
            ? ((BorderLayout) layout).getConstraints(toolbar)
            : null;
        final int index = parent.getComponentZOrder(toolbar);
        final JPanel wrapper = new JPanel(new BorderLayout(8, 0));
        wrapper.setOpaque(false);
        wrapper.putClientProperty(TOOLBAR_ROW_MARKER_KEY, Boolean.TRUE);

        parent.remove(toolbar);
        wrapper.add(contribution, BorderLayout.WEST);
        wrapper.add(toolbar, BorderLayout.EAST);
        if (constraint != null) {
            parent.add(wrapper, constraint);
        } else {
            parent.add(wrapper, Math.max(0, Math.min(index, parent.getComponentCount())));
        }
        parent.revalidate();
        parent.repaint();
        return new ToolbarPlacement(toolbar, contribution, wrapper, parent, index, constraint);
    }

    static final class ToolbarPlacement {
        private final Container toolbar;
        private final JComponent contribution;
        private final JPanel wrapper;
        private final Container originalParent;
        private final int originalIndex;
        private final Object originalConstraint;
        private boolean attached = true;

        private ToolbarPlacement(
            final Container toolbar,
            final JComponent contribution,
            final JPanel wrapper,
            final Container originalParent,
            final int originalIndex,
            final Object originalConstraint
        ) {
            this.toolbar = toolbar;
            this.contribution = contribution;
            this.wrapper = wrapper;
            this.originalParent = originalParent;
            this.originalIndex = originalIndex;
            this.originalConstraint = originalConstraint;
        }

        boolean isCurrent() {
            return attached && (wrapper == null
                ? contribution.getParent() == toolbar
                : wrapper.getParent() == originalParent
                    && contribution.getParent() == wrapper
                    && toolbar.getParent() == wrapper);
        }

        void detach() {
            if (!attached) return;
            attached = false;
            if (wrapper == null) {
                toolbar.remove(contribution);
                toolbar.revalidate();
                toolbar.repaint();
                return;
            }
            wrapper.remove(contribution);
            wrapper.remove(toolbar);
            if (wrapper.getParent() == originalParent) {
                originalParent.remove(wrapper);
                if (originalConstraint != null) {
                    originalParent.add(toolbar, originalConstraint);
                } else {
                    originalParent.add(
                        toolbar,
                        Math.max(0, Math.min(originalIndex, originalParent.getComponentCount()))
                    );
                }
                originalParent.revalidate();
                originalParent.repaint();
            }
        }
    }

    private void ensureFilterBox(
        final PaletteFilterState state,
        final Container toolbar,
        final PaletteFilterRegistry.PaletteFilterContribution contribution,
        final Consumer<String> onTextChanged
    ) {
        if (state.filterBox != null && state.toolbarPlacement != null
            && state.toolbarPlacement.isCurrent()) {
            return;
        }
        detachFilterBox(state);
        state.filterBox = createFilterBox(contribution.placeholderKey(), state.filterText, text -> {
            state.filterText = normalize(text);
            onTextChanged.accept(text);
        });
        state.toolbarPlacement = attachToolbarContribution(toolbar, state.filterBox.panel);
    }

    private static void detachFilterBox(final PaletteFilterState state) {
        if (state.toolbarPlacement != null) {
            state.toolbarPlacement.detach();
            state.toolbarPlacement = null;
        } else if (state.filterBox != null) {
            final Container parent = state.filterBox.panel.getParent();
            if (parent != null) {
                parent.remove(state.filterBox.panel);
                parent.revalidate();
                parent.repaint();
            }
        }
        state.filterBox = null;
    }

    // ------------------------------------------------------- scene filtering

    private void applySceneFilter(final PaletteFilterState state, final String text) {
        // Scene filtering now belongs to the scene table host (single owner).
        // This path is retained only for evidence when no sink is bound.
        if (sceneFilterSink == null) {
            lastAttachStatus.put(state.kind, "scene-filter:no-sink");
        }
    }

    private void applySceneFilterUnsafe(final PaletteFilterState state, final String text) {
        final JTable table = state.table;
        final Object palette = state.scenePalette;
        if (table == null || palette == null) {
            lastAttachStatus.put(state.kind, "scene-filter:no-binding");
            return;
        }
        final List<Object> documents = sceneDocs(palette);
        final List<Object> rows = tableData(palette);
        if (documents.isEmpty() && rows != null && !rows.isEmpty()) {
            lastAttachStatus.put(state.kind, "scene-filter:no-docs cells=" + rows.size());
            return;
        }
        final String keyword = normalize(text);
        final List<Object> visibleDocs = new ArrayList<>();
        for (Object document : documents) {
            if (matchesSceneDocument(document, keyword)) {
                visibleDocs.add(document);
            }
        }
        final int totalDocs = documents.size();
        if (rows != null) {
            rewriteTableRows(rows, visibleDocs);
        }
        fireTableChanged(table);
        final int cells = rows == null ? -1 : rows.size();
        lastAttachStatus.put(state.kind, "scene-filter keyword=" + keyword
            + " totalDocs=" + totalDocs + " visibleDocs=" + visibleDocs.size()
            + " cells=" + cells
            + " model=" + table.getModel().getClass().getName());
    }

    private static boolean matchesSceneDocument(final Object document, final String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return true;
        }
        final Object source = invoke(document, "getSceneSource");
        final Object movieInfo = source == null ? null : invoke(source, "getMovieInfo");
        final String haystack = (text(invoke(source, "getSceneName")) + "\n"
            + text(invoke(movieInfo, "getDisplayDuration")) + "\n"
            + text(invoke(source, "getTag"))).toLowerCase(Locale.ROOT);
        return haystack.contains(keyword);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sceneDocs(final Object palette) {
        final Object value = invoke(invoke(palette, "e"), "getSceneDocs");
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> tableData(final Object palette) {
        final Object value = field(palette, "h");
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    /** Rewrites the scene display cells (name/duration/tag per document) matching the validated host path. */
    private static void rewriteTableRows(final List<Object> rows, final List<Object> documents) {
        rows.clear();
        for (Object document : documents) {
            final Object source = invoke(document, "getSceneSource");
            rows.add(text(invoke(source, "getSceneName")));
            final Object movieInfo = invoke(source, "getMovieInfo");
            final Object duration = invoke(movieInfo, "getDisplayDuration");
            rows.add(duration instanceof Number ? String.valueOf(((Number) duration).intValue()) : "0");
            rows.add(text(invoke(source, "getTag")));
        }
    }

    private static void fireTableChanged(final JTable table) {
        if (table.getModel() instanceof AbstractTableModel model) {
            model.fireTableDataChanged();
        }
        table.revalidate();
        table.repaint();
    }

    /** Reverses a Scene palette controller from its table (scene-table host property or native listener field "a"). */
    private static Object reverseResolvePalette(final JTable table) {
        final Object remembered = table.getClientProperty(SCENE_PALETTE_PROPERTY);
        if (remembered instanceof java.lang.ref.WeakReference<?> reference && reference.get() != null) {
            return reference.get();
        }
        for (java.awt.event.MouseListener listener : table.getMouseListeners()) {
            if (listener != null && listener.getClass().getName().equals(
                "com.live2d.cubism.view.palette.scene.m")) {
                final Object palette = field(listener, "a");
                if (palette != null) {
                    return palette;
                }
            }
        }
        return null;
    }

    // --------------------------------------------------------- log filtering

    private static final Color LOG_INFO_ON = new Color(60, 146, 72);
    private static final Color LOG_WARN_ON = new Color(208, 165, 45);
    private static final Color LOG_ERROR_ON = new Color(184, 64, 64);
    private static final Color LOG_OFF = new Color(150, 150, 150);

    private void ensureLogToolbar(
        final PaletteFilterState state,
        final Container scrollShell,
        final PaletteFilterRegistry.PaletteFilterContribution contribution
    ) {
        if (state.toolbarPanel != null && state.wrapper != null && state.wrapper.getParent() != null) {
            return;
        }
        if (state.toolbarPanel == null) {
            state.toolbarPanel = new JPanel(new BorderLayout(4, 0));
            state.toolbarPanel.setOpaque(false);
            state.toolbarPanel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        }
        if (state.filterBox == null) {
            state.filterBox = createFilterBox(contribution.placeholderKey(), state.filterText, text -> {
                state.filterText = normalize(text);
                refreshFilteredLogText(state);
                publishLogFilter(state);
            });
        }
        if (state.filterBox.panel.getParent() != state.toolbarPanel) {
            state.toolbarPanel.add(state.filterBox.panel, BorderLayout.CENTER);
        }
        if (state.levelPanel == null) {
            state.levelPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
            state.levelPanel.setOpaque(false);
            state.infoButton = createLogLevelButton("info", LOG_INFO_ON, () -> {
                state.showInfo = !state.showInfo;
                refreshLogLevelButtons(state);
                refreshFilteredLogText(state);
                publishLogFilter(state);
            });
            state.warnButton = createLogLevelButton("warn", LOG_WARN_ON, () -> {
                state.showWarn = !state.showWarn;
                refreshLogLevelButtons(state);
                refreshFilteredLogText(state);
                publishLogFilter(state);
            });
            state.errorButton = createLogLevelButton("error", LOG_ERROR_ON, () -> {
                state.showError = !state.showError;
                refreshLogLevelButtons(state);
                refreshFilteredLogText(state);
                publishLogFilter(state);
            });
            state.levelPanel.add(state.infoButton);
            state.levelPanel.add(state.warnButton);
            state.levelPanel.add(state.errorButton);
            refreshLogLevelButtons(state);
        }
        if (state.levelPanel.getParent() != state.toolbarPanel) {
            state.toolbarPanel.add(state.levelPanel, BorderLayout.EAST);
        }
        if (state.wrapper != null && state.wrapper.getParent() == scrollShell.getParent()) {
            return;
        }
        final Container parent = scrollShell.getParent();
        if (parent == null) {
            return;
        }
        final JPanel wrapper = new JPanel(new BorderLayout(0, 0));
        wrapper.setOpaque(false);
        wrapper.putClientProperty(WRAPPER_MARKER_KEY, Boolean.TRUE);
        final LayoutManager parentLayout = parent.getLayout();
        final Object constraint = parentLayout instanceof BorderLayout
            ? ((BorderLayout) parentLayout).getConstraints(scrollShell)
            : null;
        final int zOrder = parent.getComponentZOrder(scrollShell);
        parent.remove(scrollShell);
        wrapper.add(state.toolbarPanel, BorderLayout.NORTH);
        wrapper.add(scrollShell, BorderLayout.CENTER);
        if (constraint != null) {
            parent.add(wrapper, constraint);
        } else {
            final int safeIndex = zOrder < 0 ? parent.getComponentCount() : Math.min(zOrder, parent.getComponentCount());
            parent.add(wrapper, safeIndex);
        }
        parent.revalidate();
        parent.repaint();
        state.wrapper = wrapper;
    }

    private static JButton createLogLevelButton(final String text, final Color activeColor, final Runnable action) {
        final JButton button = new JButton(text);
        button.setFocusable(false);
        button.setOpaque(false);
        button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setForeground(activeColor);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static void refreshLogLevelButtons(final PaletteFilterState state) {
        refreshLogLevelButton(state.infoButton, state.showInfo, LOG_INFO_ON);
        refreshLogLevelButton(state.warnButton, state.showWarn, LOG_WARN_ON);
        refreshLogLevelButton(state.errorButton, state.showError, LOG_ERROR_ON);
    }

    private static void refreshLogLevelButton(final JButton button, final boolean active, final Color activeColor) {
        if (button == null) {
            return;
        }
        button.setForeground(active ? activeColor : LOG_OFF);
        button.repaint();
    }

    /**
     * Installs a filtered Document on the native log text pane. The pane itself
     * (and its viewport/scrollbars) stay untouched; only the data written into
     * the document is filtered (pre-render filtering).
     */
    private void installFilteredDocument(final PaletteFilterState state) {
        if (state.sourceDoc == null || state.sourceTextPane == null) {
            return;
        }
        if (state.sourceDocumentListener == null) {
            state.sourceDocumentListener = new DocumentListener() {
                @Override public void insertUpdate(final DocumentEvent event) { scheduleLogRefresh(state); }
                @Override public void removeUpdate(final DocumentEvent event) { scheduleLogRefresh(state); }
                @Override public void changedUpdate(final DocumentEvent event) { scheduleLogRefresh(state); }
            };
            state.sourceDoc.addDocumentListener(state.sourceDocumentListener);
        }
        if (state.filteredDoc == null) {
            state.filteredDoc = new javax.swing.text.DefaultStyledDocument();
        }
        state.sourceTextPane.setDocument(state.filteredDoc);
        state.lastRawText = "";
        refreshFilteredLogText(state);
    }

    private void scheduleLogRefresh(final PaletteFilterState state) {
        if (state.refreshScheduled) {
            return;
        }
        state.refreshScheduled = true;
        onEdt(() -> {
            state.refreshScheduled = false;
            refreshFilteredLogText(state);
        });
    }

    /** Returns true when the user is reading the tail of the filtered log (follow-tail mode). */
    private static boolean isAtTail(final JTextPane pane) {
        if (pane == null) {
            return false;
        }
        final int length = pane.getDocument().getLength();
        if (length <= 0) {
            return true;
        }
        return pane.getCaretPosition() >= length - 80;
    }

    private void refreshFilteredLogText(final PaletteFilterState state) {
        final JTextPane pane = state.sourceTextPane;
        if (pane == null || state.sourceDoc == null) {
            return;
        }
        final String raw;
        try {
            raw = state.sourceDoc.getText(0, state.sourceDoc.getLength());
        } catch (javax.swing.text.BadLocationException impossible) {
            return;
        }
        final String filteredText = filterLogText(raw, state.filterText, state.showInfo, state.showWarn, state.showError);
        if (state.lastRawText.equals(raw)
            && state.lastKeyword.equals(state.filterText)
            && state.lastFiltered.equals(filteredText)) {
            return;
        }
        state.lastRawText = raw;
        state.lastKeyword = state.filterText;
        state.lastFiltered = filteredText;
        final boolean tail = isAtTail(pane);
        final javax.swing.text.StyleContext context = new javax.swing.text.StyleContext();
        state.filteredDoc = new javax.swing.text.DefaultStyledDocument(context);
        try {
            state.filteredDoc.insertString(0, filteredText, null);
        } catch (javax.swing.text.BadLocationException ignored) {
        }
        pane.setDocument(state.filteredDoc);
        if (tail) {
            pane.setCaretPosition(state.filteredDoc.getLength());
        }
    }

    /** Pure log-line filter ported from the legacy log palette installer (keyword + level). */
    static String filterLogText(
        final String rawText,
        final String keyword,
        final boolean showInfo,
        final boolean showWarn,
        final boolean showError
    ) {
        if (rawText == null || rawText.isEmpty()) {
            return "";
        }
        final String[] lines = rawText.split("\\R", -1);
        final StringBuilder builder = new StringBuilder(rawText.length());
        LogLevel currentLevel = LogLevel.INFO;
        final String normalizedKeyword = normalize(keyword);
        for (String line : lines) {
            final LogLevel explicitLevel = detectExplicitLogLevel(line);
            if (explicitLevel != null) {
                currentLevel = explicitLevel;
            }
            final boolean levelVisible = (currentLevel == LogLevel.INFO && showInfo)
                || (currentLevel == LogLevel.WARN && showWarn)
                || (currentLevel == LogLevel.ERROR && showError);
            final boolean keywordVisible = normalizedKeyword.isEmpty()
                || normalize(line).contains(normalizedKeyword);
            if (levelVisible && keywordVisible) {
                if (builder.length() > 0) {
                    builder.append(System.lineSeparator());
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private enum LogLevel {
        INFO, WARN, ERROR
    }

    private static LogLevel detectExplicitLogLevel(final String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        if (line.contains("ERROR") || line.contains("FATAL") || line.contains("[e")) {
            return LogLevel.ERROR;
        }
        if (line.contains("WARN") || line.contains("WARNING") || line.contains("[w")) {
            return LogLevel.WARN;
        }
        if (line.contains("INFO") || line.contains("DEBUG") || line.contains("TRACE") || line.contains("[i")) {
            return LogLevel.INFO;
        }
        return null;
    }

    // ------------------------------------------------------- tree filtering

    private void applyTreeFilter(final PaletteFilterState state, final JTree tree, final String text) {
        try {
            final String keyword = normalize(text);
            final TreeModel original = state.treeModel;
            if (original == null) {
                lastAttachStatus.put(state.kind, "tree-filter:no-model");
                return;
            }
            final TreeModel current = tree.getModel();
            if (current != original && current != state.filteredTreeModel) {
                lastAttachStatus.put(state.kind, "tree-filter:host-model-replaced "
                    + current.getClass().getName());
                if (state.filteredTreeModel != null) {
                    state.filteredTreeModel.dispose();
                    state.filteredTreeModel = null;
                }
                state.treeModel = current;
                applyTreeFilter(state, tree, text);
                return;
            }
            if (keyword.isEmpty()) {
                if (current == state.filteredTreeModel) {
                    tree.setModel(original);
                    refreshTableModel(state.table);
                }
                if (state.filteredTreeModel != null) {
                    state.filteredTreeModel.dispose();
                    state.filteredTreeModel = null;
                }
                lastAttachStatus.put(state.kind, "tree-filter keyword= restored");
                return;
            }
            if (state.filteredTreeModel == null) {
                state.filteredTreeModel = new FilteredTreeModel(
                    original, keyword, PaletteFilterHostOperations::deformerNodeSearchText
                );
                tree.setModel(state.filteredTreeModel);
            } else {
                state.filteredTreeModel.setKeyword(keyword);
            }
            expandFilteredTree(tree);
            refreshTableModel(state.table);
            lastAttachStatus.put(state.kind, "tree-filter keyword=" + keyword
                + " original=" + original.getClass().getSimpleName()
                + " treeRows=" + tree.getRowCount()
                + " tableRows=" + (state.table == null ? -1 : state.table.getRowCount()));
        } catch (Throwable failure) {
            lastAttachStatus.put(state.kind, "tree-filter-failed:"
                + failure.getClass().getSimpleName() + ":" + failure.getMessage());
        }
    }

    static void expandFilteredTree(final JTree tree) {
        for (int row = 0; row < tree.getRowCount() && row < 2_000; row++) {
            tree.expandRow(row);
        }
    }

    static final class FilteredTreeModel implements TreeModel {
        private final TreeModel delegate;
        private final Function<Object, String> searchText;
        private final List<TreeModelListener> listeners = new ArrayList<>();
        // Host nodes may have colliding equals/hashCode, so cache by identity.
        private final Map<Object, List<Object>> childrenCache = new java.util.IdentityHashMap<>();
        private final Map<Object, Boolean> matchCache = new java.util.IdentityHashMap<>();
        private final TreeModelListener delegateListener;
        private String keyword = "";
        private boolean disposed;


        FilteredTreeModel(
            final TreeModel delegate,
            final String keyword,
            final Function<Object, String> searchText
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.searchText = Objects.requireNonNull(searchText, "searchText");
            this.keyword = normalize(keyword);
            this.delegateListener = new TreeModelListener() {
                @Override public void treeNodesChanged(final TreeModelEvent event) { onDelegateEvent(); }
                @Override public void treeNodesInserted(final TreeModelEvent event) { onDelegateEvent(); }
                @Override public void treeNodesRemoved(final TreeModelEvent event) { onDelegateEvent(); }
                @Override public void treeStructureChanged(final TreeModelEvent event) { onDelegateEvent(); }
                private void onDelegateEvent() {
                    invalidate();
                    fireStructureChanged();
                }
            };
            delegate.addTreeModelListener(delegateListener);
        }


        void setKeyword(final String keyword) {
            final String next = normalize(keyword);
            if (this.keyword.equals(next)) {
                return;
            }
            this.keyword = next;
            invalidate();
            fireStructureChanged();
        }

        void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            delegate.removeTreeModelListener(delegateListener);
        }

        private void invalidate() {
            childrenCache.clear();
            matchCache.clear();
        }

        private void fireStructureChanged() {
            final Object root = getRoot();
            if (root == null) {
                return;
            }
            final TreeModelEvent event = new TreeModelEvent(this, new Object[] {root});
            for (TreeModelListener listener : listeners) {
                listener.treeStructureChanged(event);
            }
        }

        @Override public Object getRoot() {
            return delegate.getRoot();
        }

        @Override public Object getChild(final Object parent, final int index) {
            return visibleChildren(parent).get(index);
        }

        @Override public int getChildCount(final Object parent) {
            return visibleChildren(parent).size();
        }

        @Override public boolean isLeaf(final Object node) {
            return delegate.isLeaf(node);
        }

        @Override public int getIndexOfChild(final Object parent, final Object child) {
            return visibleChildren(parent).indexOf(child);
        }

        @Override
        public void valueForPathChanged(final TreePath path, final Object newValue) {
            delegate.valueForPathChanged(path, newValue);
        }

        @Override
        public void addTreeModelListener(final TreeModelListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeTreeModelListener(final TreeModelListener listener) {
            listeners.remove(listener);
        }

        private List<Object> visibleChildren(final Object parent) {
            final List<Object> cached = childrenCache.get(parent);
            if (cached != null) {
                return cached;
            }
            final List<Object> visible = new ArrayList<>();
            final int count = delegate.getChildCount(parent);
            for (int index = 0; index < count; index++) {
                final Object child = delegate.getChild(parent, index);
                if (matchesNodeOrDescendant(child)) {
                    visible.add(child);
                }
            }
            childrenCache.put(parent, visible);
            return visible;
        }

        private boolean matchesNodeOrDescendant(final Object node) {
            final Boolean cached = matchCache.get(node);
            if (cached != null) {
                return cached;
            }
            boolean matches = keyword.isEmpty() || normalize(searchText.apply(node)).contains(keyword);
            if (!matches) {
                final int count = delegate.getChildCount(node);
                for (int index = 0; index < count; index++) {
                    if (matchesNodeOrDescendant(delegate.getChild(node, index))) {
                        matches = true;
                        break;
                    }
                }
            }
            matchCache.put(node, matches);
            return matches;
        }
    }

    private static void refreshTableModel(final JTable table) {
        if (table == null) {
            return;
        }
        if (table.getModel() instanceof AbstractTableModel model) {
            model.fireTableDataChanged();
        }
        table.revalidate();
        table.repaint();
        final Container parent = table.getParent();
        if (parent != null) {
            parent.repaint();
        }
    }

    /** Extracts the embedded JTree from a tree-table via reflective field scan (bounded, fail-closed). */
    private static JTree extractTree(final JTable table) {
        for (Component child : table.getComponents()) {
            if (child instanceof JTree tree) {
                return tree;
            }
        }
        Class<?> type = table.getClass();
        int depth = 0;
        while (type != null && depth < 4) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    final Object value = field.get(table);
                    if (value instanceof JTree tree) {
                        return tree;
                    }
                    if (value instanceof JComponent component) {
                        final JTree nested = findTreeInComponent(component);
                        if (nested != null) {
                            return nested;
                        }
                    }
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    // Try the next field.
                }
            }
            type = type.getSuperclass();
            depth++;
        }
        return null;
    }

    private static JTree findTreeInComponent(final Component component) {
        if (component instanceof JTree tree) {
            return tree;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTree found = findTreeInComponent(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Exact 5.3.02 deformer fields: verified node {@code i()} source ID and local name. */
    static String deformerNodeSearchText(final Object node) {
        final Object source = invoke(node, "i");
        if (source == null || source == node) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        final Object id = invoke(source, "getId");
        if (id instanceof String value) {
            appendToken(builder, value);
        } else {
            appendToken(builder, text(invoke(id, "getIdString")));
        }
        appendToken(builder, text(invoke(source, "getLocalName")));
        return builder.toString();
    }


    private static void appendToken(final StringBuilder builder, final String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        final String token = value.trim().toLowerCase(Locale.ROOT);
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(token);
    }

    // ------------------------------------------------------------ resolution

    private static Container findToolbarContainer(final JTable table) {
        final Component scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        Component current = scrollPane == null ? table : scrollPane;
        while (current != null && current.getParent() != null) {
            final Container parent = current.getParent();
            for (Component child : parent.getComponents()) {
                if (child == current) {
                    continue;
                }
                if (child instanceof Container && containsToolbarButton((Container) child)) {
                    return (Container) child;
                }
            }
            current = parent;
        }
        return null;
    }

    private static boolean containsToolbarButton(final Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof AbstractButton) {
                return true;
            }
            if (child instanceof Container && containsToolbarButton((Container) child)) {
                return true;
            }
        }
        return false;
    }

    private static Container findParameterToolbar(final Component component) {
        // Walk up from the parameter viewport and inspect bounded sibling subtrees
        // for the exact three-button toolbar.
        Component current = component;
        int hops = 0;
        while (current != null && current.getParent() != null && hops < 8) {
            final Container parent = current.getParent();
            for (Component sibling : parent.getComponents()) {
                if (sibling != current) {
                    final Container toolbar = findParameterToolbarInSubtree(sibling, 3);
                    if (toolbar != null) {
                        return toolbar;
                    }
                }
            }
            current = parent;
            hops++;
        }
        return null;
    }

    private static Container findParameterToolbarInSubtree(final Component component, final int depth) {
        if (component == null || depth < 0) {
            return null;
        }
        if (isParameterToolbar(component)) {
            return (Container) component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final Container found = findParameterToolbarInSubtree(child, depth - 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JTable findTable(final Component root) {
        if (root instanceof JTable table) {
            return table;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTable table = findTable(child);
                if (table != null) {
                    return table;
                }
            }
        }
        return null;
    }

    private static JTextPane findTextPane(final Component root) {
        if (root instanceof JTextPane pane && !pane.isEditable()) {
            return pane;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTextPane pane = findTextPane(child);
                if (pane != null) {
                    return pane;
                }
            }
        }
        return null;
    }


    private void detach(final PaletteFilterState state) {
        if (state == null) {
            return;
        }
        resetBinding(state, false);
        if (state.kind == PaletteKind.LOG && cubismLogService != null) {
            cubismLogService.setFilter(dev.turboism.sdk.runtime.CubismLogService.LogFilter.all());
        }
    }

    // ------------------------------------------------------------------ util

    private static Object invoke(final Object target, final String methodName) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final java.lang.reflect.Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object field(final Object target, final String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static String text(final Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String normalize(final String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private static void onEdt(final Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    private static final class PaletteFilterState {
        final PaletteKind kind;
        volatile Object controller;
        volatile Object scenePalette;
        volatile JComponent root;
        volatile JTable table;
        volatile JTextPane sourceTextPane;
        volatile javax.swing.text.Document sourceDoc;
        volatile javax.swing.text.Document filteredDoc;
        volatile JViewport viewport;
        volatile Container scrollShell;
        volatile JPanel wrapper;
        volatile JPanel toolbarPanel;
        volatile Container toolbar;
        volatile FilterBox filterBox;
        volatile ToolbarPlacement toolbarPlacement;
        volatile JPanel levelPanel;
        volatile JButton infoButton;
        volatile JButton warnButton;
        volatile JButton errorButton;
        volatile boolean showInfo = true;
        volatile boolean showWarn = true;
        volatile boolean showError = true;
        volatile JTree tree;
        volatile TreeModel treeModel;
        volatile FilteredTreeModel filteredTreeModel;
        volatile Object tableModel;
        volatile List<ParameterFilterRow> rows = List.of();
        final Map<JComponent, Boolean> originalRowVisibility = new java.util.IdentityHashMap<>();
        volatile String filterText = "";
        volatile boolean refreshScheduled;
        volatile String lastRawText = "";
        volatile String lastKeyword = "";
        volatile String lastFiltered = "";
        volatile DocumentListener sourceDocumentListener;

        PaletteFilterState(final PaletteKind kind) {
            this.kind = Objects.requireNonNull(kind, "kind");
        }
    }
}
