package dev.turboism.ui.filter;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.mapping.verification.VerifiedAccessException;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.ui.palette.LogPaletteHostStructure;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;
import dev.turboism.ui.toolbar.PaletteToolbarContributionDescriptor;
import dev.turboism.ui.toolbar.PaletteToolbarHostOperations;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.ImageIcon;
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
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
public class PaletteFilterHostOperations implements PaletteFilterVisibilitySink, PaletteToolbarHostOperations, AutoCloseable {

    private static final int CONNECT_ATTEMPTS = 300;
    private static final int CONNECT_DELAY_MS = 250;
    private static final int IDLE_CONNECT_DELAY_MS = 2_000;

    private static final String FILTERED_TEXT_PANE_KEY = LogPaletteHostStructure.FILTERED_TEXT_PANE_KEY;
    private static final String WRAPPER_MARKER_KEY = LogPaletteHostStructure.FILTER_WRAPPER_MARKER_KEY;

    /** Palette kind names understood by this host. */
    public enum PaletteKind {
        PARAMETER("palette.parameter"),
        DEFORMER("palette.deformer"),
        SCENE("palette.scene"),
        LOG("palette.log");

        final String classHint;

        PaletteKind(final String classHint) {
            this.classHint = classHint;
        }
    }

    private final Map<PaletteKind, PaletteFilterState> states = new ConcurrentHashMap<>();
    private final Map<String, List<PaletteFilterRegistry.PaletteFilterContribution>> contributionsByPlugin =
        new ConcurrentHashMap<>();
    final Map<PaletteKind, List<PaletteToolbarHostOperations.ButtonContribution>> toolbarContributions =
        new ConcurrentHashMap<>();
    final EditorUiPluginResourceRegistry resources;
    private final PaletteControllerResolver controllerResolver;
    final Map<PaletteKind, String> lastAttachStatus = new ConcurrentHashMap<>();
    volatile SceneFilterSink sceneFilterSink;
    private volatile dev.turboism.sdk.runtime.CubismLogService cubismLogService;
    volatile dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator parameterRows;
    volatile VerifiedMemberResolver parameterRowsResolver;
    private volatile ClassLoader hostClassLoader;
    private volatile long connectionToken;
    private volatile boolean connected;

    long lastParameterReplayMillis;

    /** Test seam: resolves the palette root object for a palette kind. */
    interface PaletteControllerResolver {
        Object resolve(PaletteKind kind);
    }

    /** Single-owner sink for scene filtering; implemented by the scene table host. */
    public interface SceneFilterSink {
        void setSceneFilter(String keyword);
    }

    public PaletteFilterHostOperations() {
        this(null, null);
    }

    public PaletteFilterHostOperations(final EditorUiPluginResourceRegistry resources) {
        this(resources, null);
    }

    PaletteFilterHostOperations(final PaletteControllerResolver controllerResolver) {
        this(null, controllerResolver);
    }

    PaletteFilterHostOperations(
        final EditorUiPluginResourceRegistry resources,
        final PaletteControllerResolver controllerResolver
    ) {
        this.resources = resources;
        this.controllerResolver = controllerResolver;
        for (PaletteKind kind : PaletteKind.values()) {
            states.put(kind, new PaletteFilterState(kind));
            toolbarContributions.put(kind, List.of());
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

    /** Replaces the authority-owned filter snapshot for the shared Palette surface. */
    public void setFilterContributions(
        final List<PaletteFilterRegistry.PaletteFilterContribution> contributions
    ) {
        final List<PaletteFilterRegistry.PaletteFilterContribution> requested = List.copyOf(
            Objects.requireNonNull(contributions, "contributions")
        );
        for (PaletteFilterRegistry.PaletteFilterContribution contribution : requested) {
            paletteKind(contribution.paletteId());
        }
        if (requested.isEmpty()) {
            contributionsByPlugin.remove("__authority__");
        } else {
            contributionsByPlugin.put("__authority__", requested);
        }
        onEdt(this::reconcilePalettes);
    }

    @Override
    public void setContributions(final List<PaletteToolbarHostOperations.ButtonContribution> contributions) {
        final Map<PaletteKind, List<PaletteToolbarHostOperations.ButtonContribution>> next =
            new java.util.EnumMap<>(PaletteKind.class);
        for (PaletteKind kind : PaletteKind.values()) {
            next.put(kind, new ArrayList<>());
        }
        final Set<String> nativeIds = new java.util.HashSet<>();
        for (PaletteToolbarHostOperations.ButtonContribution contribution : List.copyOf(
            Objects.requireNonNull(contributions, "contributions")
        )) {
            final PaletteKind kind = paletteKind(contribution.descriptor().paletteId());
            PaletteToolbarSupport.toolbarAlignment(contribution.descriptor().anchor());
            if (!nativeIds.add(contribution.descriptor().nativeId())) {
                throw new IllegalArgumentException(
                    "duplicate palette toolbar contribution: " + contribution.descriptor().nativeId()
                );
            }
            next.get(kind).add(contribution);
        }
        for (PaletteKind kind : PaletteKind.values()) {
            toolbarContributions.put(kind, List.copyOf(next.get(kind)));
        }
        onEdt(this::reconcilePalettes);
    }

    @Override
    public void reconcileNow() {
        onEdt(this::reconcilePalettes);
    }

    @Override
    public void clearContributions() {
        for (PaletteKind kind : PaletteKind.values()) {
            toolbarContributions.put(kind, List.of());
        }
        onEdt(this::reconcilePalettes);
    }

    @Override
    public boolean hasLiveButtons() {
        for (PaletteFilterState state : states.values()) {
            if (!state.toolbarButtons.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static PaletteKind paletteKind(final String paletteId) {
        try {
            return PaletteKind.valueOf(Objects.requireNonNull(paletteId, "paletteId").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unsupported palette id: " + paletteId, failure);
        }
    }

    private boolean reconcilePalettes() {
        boolean allAttached = true;
        for (PaletteKind kind : PaletteKind.values()) {
            final PaletteFilterRegistry.PaletteFilterContribution filter = highestOrderContribution(kind);
            final List<PaletteToolbarHostOperations.ButtonContribution> buttons = toolbarContributions.get(kind);
            final PaletteFilterState state = states.get(kind);
            if (filter == null && (buttons == null || buttons.isEmpty())) {
                detach(state);
                continue;
            }
            if (!attach(state, filter)) {
                allAttached = false;
            }
        }
        return allAttached;
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

    int filterContributionCount(final PaletteKind kind) {
        return highestOrderContribution(kind) == null ? 0 : 1;
    }

    // ------------------------------------------------------------------ attach

    private boolean attach(
        final PaletteFilterState state,
        final PaletteFilterRegistry.PaletteFilterContribution contribution
    ) {
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
            if (!PaletteComponentFinder.isInVisibleCubismWindow(root)) {
                return false;
            }
        }
        final JTable table = state.table;
        if (table != null && (!table.isDisplayable() || !PaletteComponentFinder.isInVisibleCubismWindow(table))) {
            return false;
        }
        final JTextPane textPane = state.sourceTextPane;
        if (textPane != null && state.kind != PaletteKind.LOG
            && (!textPane.isDisplayable() || !PaletteComponentFinder.isInVisibleCubismWindow(textPane))) {
            return false;
        }
        if (state.kind == PaletteKind.LOG && state.filteredDoc != null) {
            if (state.sourceTextPane == null || state.sourceTextPane.getDocument() != state.filteredDoc) {
                return false;
            }
            if (!state.sourceTextPane.isDisplayable() || !PaletteComponentFinder.isInVisibleCubismWindow(state.sourceTextPane)) {
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

    /**
     * Removes injected UI and clears the cached binding. When
     * {@code preserveFilterText} is true the user's keyword survives a
     * re-location; plugin removal/close passes false.
     */
    private static void resetBinding(final PaletteFilterState state, final boolean preserveFilterText) {
        PaletteParameterRows.restoreParameterRows(state);
        PaletteToolbarSupport.detachToolbarButtons(state);
        final String filterText = preserveFilterText ? state.filterText : "";
        PaletteToolbarSupport.detachFilterBox(state);
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
                LogPaletteHostStructure.replaceComponent(parent, state.wrapper, state.scrollShell);
            }
        }
        if (state.filteredTreeModel != null) {
            if (state.tree != null && state.treeModel != null
                && state.tree.getModel() == state.filteredTreeModel) {
                state.tree.setModel(state.treeModel);
                PaletteComponentFinder.refreshTableModel(state.table);
            }
            state.filteredTreeModel.dispose();
            state.filteredTreeModel = null;
        }

        // A pre-warming model may still be in flight; dispose it and stop the debounce timer so a
        // detached binding can neither install itself nor fire a stale filter.
        if (state.pendingFilteredTreeModel != null) {
            state.pendingFilteredTreeModel.dispose();
            state.pendingFilteredTreeModel = null;
        }
        if (state.treeFilterTimer != null) {
            state.treeFilterTimer.stop();
            state.treeFilterTimer = null;
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
        state.parameterFilterStamp = null;
        state.originalRowVisibility.clear();
        state.lastRawText = "";
        state.lastKeyword = "";
        state.lastFiltered = "";
        state.filterText = filterText;
    }

    // ------------------------------------------------------- palette resolution

    /**
     * Resolves the palette root per kind using 5.3.02 structural anchors rather
     * than a uniform class-name substring scan.
     */
    private Object resolvePaletteController(final PaletteKind kind) {
        switch (kind) {
            case SCENE -> {
                final JTable remembered = PaletteComponentFinder.findRememberedSceneTable();
                if (remembered != null) {
                    return remembered;
                }
            }
            case DEFORMER -> {
                final JTable table = PaletteComponentFinder.findTreeTable("com.live2d.cubism.view.palette.deformer.CDeformerTreeTable");
                if (table != null) {
                    return table;
                }
            }
            case PARAMETER -> {
                final JComponent root = PaletteParameterRows.findParameterRowsRoot(PaletteFilterHostOperations.this);
                if (root != null) {
                    return root;
                }
            }
            case LOG -> {
                final JTextPane pane = LogPaletteHostStructure.findLogTextPane();
                if (pane != null) {
                    return pane;
                }
            }
        }
        // Fallback: class-path hint scan (kept for unknown shapes; fails closed otherwise).
        for (Window window : Window.getWindows()) {
            final Object root = PaletteComponentFinder.findPaletteRoot(window, kind, null);
            if (root != null) {
                return root;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ attach kinds

    private boolean attachScene(
        final PaletteFilterState state,
        final PaletteFilterRegistry.PaletteFilterContribution contribution
    ) {
        final JComponent component = state.root;
        if (component == null) return false;
        final JTable table = component instanceof JTable tableValue ? tableValue : PaletteComponentFinder.findTable(component);
        if (table == null) {
            lastAttachStatus.put(state.kind, "scene-table-not-found root=" + component.getClass().getName());
            return false;
        }
        final Container toolbar = PaletteComponentFinder.findToolbarContainer(table);
        if (toolbar == null) {
            lastAttachStatus.put(state.kind, "scene-toolbar-not-found table=" + table.getClass().getName());
            return false;
        }
        state.table = table;
        state.root = table;
        state.toolbar = toolbar;
        if (contribution != null) {
            final Object palette = reverseResolvePalette(table);
            if (palette == null) {
                lastAttachStatus.put(state.kind, "scene-palette-not-found table=" + table.getClass().getName());
                return false;
            }
            state.scenePalette = palette;
            PaletteToolbarSupport.ensureFilterBox(state, toolbar, contribution, text -> {
                state.filterText = normalize(text);
                if (sceneFilterSink != null) sceneFilterSink.setSceneFilter(state.filterText);
            });
            if (sceneFilterSink != null) sceneFilterSink.setSceneFilter(state.filterText);
        } else {
            PaletteToolbarSupport.detachFilterBox(state);
        }
        PaletteToolbarSupport.syncToolbarButtons(PaletteFilterHostOperations.this, state, toolbar);
        lastAttachStatus.put(state.kind, "attached table=" + table.getClass().getName()
            + " toolbar=" + toolbar.getClass().getName());
        return true;
    }

    private boolean attachDeformer(
        final PaletteFilterState state,
        final PaletteFilterRegistry.PaletteFilterContribution contribution
    ) {
        final JComponent component = state.root;
        if (component == null) return false;
        final JTable table = component instanceof JTable tableValue ? tableValue : PaletteComponentFinder.findTable(component);
        if (table == null) {
            lastAttachStatus.put(state.kind, "deformer-table-not-found root=" + component.getClass().getName());
            return false;
        }
        final Container toolbar = PaletteComponentFinder.findToolbarContainer(table);
        if (toolbar == null) {
            lastAttachStatus.put(state.kind, "deformer-toolbar-not-found table=" + table.getClass().getName());
            return false;
        }
        state.table = table;
        state.toolbar = toolbar;
        if (contribution != null) {
            final JTree tree = PaletteComponentFinder.extractTree(table);
            if (tree == null) {
                lastAttachStatus.put(state.kind, "deformer-tree-not-found table=" + table.getClass().getName());
                return false;
            }
            state.tree = tree;
            if (state.treeModel == null) state.treeModel = tree.getModel();
            final String nodeSourceUnavailable = nodeSourceUnavailableDiagnostic();
            if (nodeSourceUnavailable != null) {
                lastAttachStatus.put(state.kind, nodeSourceUnavailable);
                return false;
            }
            PaletteToolbarSupport.ensureFilterBox(state, toolbar, contribution, text -> {
                state.filterText = normalize(text);
                PaletteTreeFilter.scheduleTreeFilter(PaletteFilterHostOperations.this, state, text);
            });
            PaletteTreeFilter.applyTreeFilter(PaletteFilterHostOperations.this, state, tree, state.filterText);
        } else {
            if (state.tree != null) PaletteTreeFilter.restoreOriginalDeformerTree(state, state.tree);
            PaletteToolbarSupport.detachFilterBox(state);
        }
        PaletteToolbarSupport.syncToolbarButtons(PaletteFilterHostOperations.this, state, toolbar);
        lastAttachStatus.put(state.kind, "attached table=" + table.getClass().getName()
            + " toolbar=" + toolbar.getClass().getName());
        return true;
    }

    private boolean attachParameter(
        final PaletteFilterState state,
        final PaletteFilterRegistry.PaletteFilterContribution contribution
    ) {
        final JComponent component = state.root;
        if (component == null) return false;
        final Container toolbar = PaletteComponentFinder.findParameterToolbar(component);
        if (toolbar == null) {
            lastAttachStatus.put(state.kind, "parameter-toolbar-not-found root=" + component.getClass().getName());
            return false;
        }
        state.toolbar = toolbar;
        if (contribution != null) {
            final List<ParameterFilterRow> rows = PaletteParameterRows.parameterFilterRows(PaletteFilterHostOperations.this, component);
            if (rows.isEmpty()) {
                lastAttachStatus.put(state.kind, "parameter-rows-not-found root=" + component.getClass().getName());
                return false;
            }
            state.rows = rows;
            final Set<JComponent> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (ParameterFilterRow row : rows) {
                live.add(row.component());
                state.originalRowVisibility.putIfAbsent(row.component(), row.component().isVisible());
            }
            PaletteParameterRows.restoreDiscardedParameterRows(state.originalRowVisibility, live);
            PaletteToolbarSupport.ensureFilterBox(state, toolbar, contribution, text -> PaletteParameterRows.applyParameterFilter(state, text));
            PaletteParameterRows.applyParameterFilter(state, state.filterText);
        } else {
            PaletteParameterRows.restoreParameterRows(state);
            PaletteToolbarSupport.detachFilterBox(state);
        }
        PaletteToolbarSupport.syncToolbarButtons(PaletteFilterHostOperations.this, state, toolbar);
        lastAttachStatus.put(state.kind, "attached root=" + component.getClass().getName()
            + " toolbar=" + toolbar.getClass().getName());
        return true;
    }

    private boolean attachLog(
        final PaletteFilterState state,
        final PaletteFilterRegistry.PaletteFilterContribution contribution
    ) {
        if (contribution == null && state.filteredDoc != null) {
            resetBinding(state, true);
            return false;
        }
        if (contribution != null && state.filteredDoc != null && state.sourceTextPane != null
            && state.sourceTextPane.getDocument() == state.filteredDoc
            && state.sourceTextPane.isDisplayable()) {
            PaletteToolbarSupport.syncToolbarButtons(PaletteFilterHostOperations.this, state, state.toolbarPanel);
            refreshFilteredLogText(state);
            return true;
        }
        final JComponent component = state.root;
        if (component == null) return false;
        final JTextPane textPane = component instanceof JTextPane pane ? pane : PaletteComponentFinder.findTextPane(component);
        if (textPane == null) {
            lastAttachStatus.put(state.kind, "log-textpane-not-found root=" + component.getClass().getName());
            return false;
        }
        final JViewport viewport = LogPaletteHostStructure.findAncestorViewport(textPane);
        if (viewport == null || viewport.getParent() == null) {
            lastAttachStatus.put(state.kind, "log-scrollshell-not-found");
            return false;
        }
        final Container scrollShell = viewport.getParent();
        state.sourceTextPane = textPane;
        state.viewport = viewport;
        state.scrollShell = scrollShell;
        ensureLogToolbar(state, scrollShell, contribution);
        PaletteToolbarSupport.syncToolbarButtons(PaletteFilterHostOperations.this, state, state.toolbarPanel);
        if (contribution != null) {
            state.sourceDoc = textPane.getDocument();
            installFilteredDocument(state);
            refreshFilteredLogText(state);
        }
        lastAttachStatus.put(state.kind, "attached pane=" + textPane.getClass().getName()
            + " scrollShell=" + scrollShell.getClass().getName()
            + " filter=" + (contribution != null));
        return true;
    }

    // ------------------------------------------------------------- filter box

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
        PaletteComponentFinder.fireTableChanged(table);
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

    /** Reverses a Scene palette controller from its table (scene-table host property or native listener field "a"). */
    private static Object reverseResolvePalette(final JTable table) {
        final Object remembered = table.getClientProperty(PaletteComponentFinder.SCENE_PALETTE_PROPERTY);
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
        if (state.toolbarPanel == null) {
            state.toolbarPanel = new JPanel(new BorderLayout(4, 0));
            state.toolbarPanel.setOpaque(false);
            state.toolbarPanel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        }
        if (contribution != null) {
            if (state.filterBox == null) {
                state.filterBox = PaletteToolbarSupport.createFilterBox(contribution.placeholderKey(), state.filterText, text -> {
                    state.filterText = normalize(text);
                    refreshFilteredLogText(state);
                    publishLogFilter(state);
                });
            }
            if (state.filterBox.panel.getParent() != state.toolbarPanel) {
                state.toolbarPanel.add(state.filterBox.panel, BorderLayout.CENTER);
            }
            if (state.levelPanel == null) {
                state.levelPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
                state.levelPanel.setOpaque(false);
            }
            if (state.infoButton == null) {
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
        }
        if (state.wrapper != null && state.wrapper.getParent() == scrollShell.getParent()) return;
        final Container parent = scrollShell.getParent();
        if (parent == null) return;
        final JPanel wrapper = new JPanel(new BorderLayout());
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
        if (constraint != null) parent.add(wrapper, constraint);
        else parent.add(wrapper, zOrder < 0 ? parent.getComponentCount() : Math.min(zOrder, parent.getComponentCount()));
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

    /** Exact 5.3.02 deformer fields: verified node {@code i()} source ID and local name. */
    /**
     * Search text for one deformer-tree node. The node→source accessor is resolved per exact
     * version (Cubism 5.2.03 → {@code h()}, 5.3.02 → {@code i()} on {@code com.live2d.ui.treeTable.c},
     * pinned by the ui-control-appearance verification records, alias
     * {@code cubism.ui-control-appearance.part.node-source}); the id/name chain continues through
     * the bound Editor-model resolver ({@code parameter-controllable-source.id} → {@code id.value}
     * plus {@code parameter-controllable-source.local-name}).
     *
     * <p>A single-node resolution failure yields an empty search text (that node does not match);
     * the attach/apply guards fail the whole deformer filter closed when the binding itself is
     * unavailable, so this method is never asked to filter an unresolvable tree.</p>
     */
    String deformerNodeSearchText(final Object node) {
        final VerifiedMemberResolver resolver = parameterRowsResolver;
        if (resolver == null) {
            return "";
        }
        final Optional<DeformerNodeSourceProfile> profile = DeformerNodeSourceProfile.forResolver(resolver);
        if (profile.isEmpty()) {
            return "";
        }
        final Object source = invoke(node, profile.get().accessorName());
        if (source == null || source == node) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        try {
            final Object id = resolver.invoke("cubism.editor-model.parameter-controllable-source.id", source);
            if (id == null) {
                return "";
            }
            appendToken(builder, text(resolver.invoke("cubism.editor-model.id.value", id)));
            appendToken(builder, text(resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.local-name", source)));
        } catch (VerifiedAccessException perNodeFailure) {
            // Per-node fail closed: an unresolvable node simply does not match the keyword.
            return "";
        }
        return builder.toString();
    }

    /** Verified Editor-model aliases of the deformer id/name search-text chain. */
    private static final List<String> DEORMER_ID_NAME_ALIASES = List.of(
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.id.value",
        "cubism.editor-model.parameter-controllable-source.local-name"
    );

    /**
     * Binding-period guard for deformer tree filtering. Returns a fail-closed diagnostic when
     * filtering must not run, {@code null} when the exact-version node→source accessor binding
     * and the whole id/name chain are available.
     *
     * <p>Fail closed on: unbound Editor-model resolver, unknown Cubism version, unresolvable
     * accessor in the attested host class loader, or any missing id/name alias in the verified
     * plan. Callers restore the original tree model and never install a FilteredTreeModel whose
     * matches would all be empty (the 5.2.03 silent-collapse regression).</p>
     */
    String nodeSourceUnavailableDiagnostic() {
        final VerifiedMemberResolver resolver = parameterRowsResolver;
        if (resolver == null) {
            return "tree-filter:node-source-unavailable resolver=unbound";
        }
        final Optional<DeformerNodeSourceProfile> profile = DeformerNodeSourceProfile.forResolver(resolver);
        if (profile.isEmpty()) {
            return "tree-filter:node-source-unavailable version=" + resolver.cubismVersion();
        }
        if (!nodeSourceAccessorResolvable(resolver, profile.get())) {
            return "tree-filter:node-source-unavailable accessor=" + profile.get().accessorName()
                + " class=" + DeformerNodeSourceProfile.OWNER_BINARY_NAME;
        }
        // The whole id/name chain must be present in the verified plan; a missing alias would
        // make every node search text empty and silently collapse the tree.
        for (String alias : DEORMER_ID_NAME_ALIASES) {
            try {
                resolver.verifiedSelector(alias);
            } catch (VerifiedAccessException missingAlias) {
                return "tree-filter:node-source-unavailable alias=" + alias;
            }
        }
        return null;
    }

    /**
     * Binding-period validation in the attested host class loader: the pinned owner class must
     * load, declare the exact accessor name with no parameters, return {@code Object} (pinned
     * descriptor {@code ()Ljava/lang/Object;}), and be accessible. Any mismatch disables deformer
     * filtering (fail closed); no guessing fallback is attempted.
     */
    private static boolean nodeSourceAccessorResolvable(
        final VerifiedMemberResolver resolver,
        final DeformerNodeSourceProfile profile
    ) {
        try {
            final Class<?> owner = Class.forName(
                DeformerNodeSourceProfile.OWNER_BINARY_NAME,
                false,
                resolver.hostClassLoader()
            );
            final Method accessor = owner.getDeclaredMethod(profile.accessorName());
            if (accessor.getParameterCount() != 0
                || accessor.getReturnType() != Object.class
                || !accessor.getDeclaringClass().equals(owner)) {
                return false;
            }
            // No-arg instance accessor: reflective access is granted once trySetAccessible
            // succeeds (canAccess(null) would throw for instance methods and is not usable here).
            return accessor.trySetAccessible();
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError | SecurityException unavailable) {
            return false;
        }
    }

    static void appendToken(final StringBuilder builder, final String value) {
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

    static Object invoke(final Object target, final String methodName) {
        if (target == null) {
            return null;
        }
        try {
            // Cached hierarchy walk of declared methods; the cache applies the access policy once.
            return MethodHandleCache.declaredUp(target.getClass(), methodName).invoke(target);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    static Object field(final Object target, final String name) {
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

    static String text(final Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String normalize(final String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    static void onEdt(final Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

}
