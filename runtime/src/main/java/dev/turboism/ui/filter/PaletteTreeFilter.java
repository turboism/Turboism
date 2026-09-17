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

/** Debounced deformer-tree filtering and the filtering tree model. */
final class FilteredTreeModel implements TreeModel {
    final TreeModel delegate;
    final Function<Object, String> searchText;
    final List<TreeModelListener> listeners = new ArrayList<>();
    // Host nodes may have colliding equals/hashCode, so cache by identity. The caches are
    // written by the background pre-warm thread and read by the EDT, hence synchronized maps.
    final Map<Object, List<Object>> childrenCache =
        java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    final Map<Object, Boolean> matchCache =
        java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    final TreeModelListener delegateListener;
    volatile String keyword = "";
    volatile boolean disposed;
    volatile long generation;


    FilteredTreeModel(
        final TreeModel delegate,
        final String keyword,
        final Function<Object, String> searchText
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.searchText = Objects.requireNonNull(searchText, "searchText");
        this.keyword = PaletteFilterHostOperations.normalize(keyword);
        this.delegateListener = new TreeModelListener() {
            @Override public void treeNodesChanged(final TreeModelEvent event) { onDelegateEvent(); }
            @Override public void treeNodesInserted(final TreeModelEvent event) { onDelegateEvent(); }
            @Override public void treeNodesRemoved(final TreeModelEvent event) { onDelegateEvent(); }
            @Override public void treeStructureChanged(final TreeModelEvent event) { onDelegateEvent(); }
            void onDelegateEvent() {
                invalidate();
                fireStructureChanged();
            }
        };
        delegate.addTreeModelListener(delegateListener);
    }


    void setKeyword(final String keyword) {
        final String next = PaletteFilterHostOperations.normalize(keyword);
        if (this.keyword.equals(next)) {
            return;
        }
        this.keyword = next;
        invalidate();
        fireStructureChanged();
    }

    /** Normalized keyword used to build this model (for apply-time supersession checks). */
    String keyword() {
        return keyword;
    }

    /**
     * Eagerly walks the whole delegate tree to fill {@link #childrenCache} and
     * {@link #matchCache}, so the EDT only performs cached lookups once the model is installed.
     * Runs on the background filter executor; best-effort (any failure leaves the caches
     * partially warm and the model falls back to lazy traversal on the EDT).
     */
    void prewarm() {
        final long atGeneration = generation;
        final Object root = delegate.getRoot();
        if (root != null) {
            prewarmNode(root);
        }
        if (generation != atGeneration) {
            // The delegate changed mid-walk; drop the stale entries so the EDT re-computes.
            childrenCache.clear();
            matchCache.clear();
        }
    }

    void prewarmNode(final Object node) {
        for (Object child : visibleChildren(node)) {
            prewarmNode(child);
        }
    }

    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        delegate.removeTreeModelListener(delegateListener);
    }

    void invalidate() {
        generation++;
        childrenCache.clear();
        matchCache.clear();
    }

    void fireStructureChanged() {
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

    List<Object> visibleChildren(final Object parent) {
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

    boolean matchesNodeOrDescendant(final Object node) {
        final Boolean cached = matchCache.get(node);
        if (cached != null) {
            return cached;
        }
        boolean matches = keyword.isEmpty() || PaletteFilterHostOperations.normalize(searchText.apply(node)).contains(keyword);
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

final class PaletteTreeFilter {

    private PaletteTreeFilter() {
    }

    /** Debounce window for deformer-tree filter keystrokes (continuous typing rebuilds once). */
    static final int FILTER_DEBOUNCE_MS = 200;

    /**
     * Daemon executor that pre-warms the filtered tree model caches off the EDT; the EDT only
     * installs the model and expands rows once the (read-only) reflection traversal is done.
     */
    static final java.util.concurrent.ExecutorService TREE_FILTER_EXECUTOR =
        java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "turboism-palette-tree-filter");
            thread.setDaemon(true);
            return thread;
        });

    /**
     * Debounced deformer-tree filter application (last change wins within the debounce window).
     * Package-visible for the filter regression tests.
     */
    static void scheduleTreeFilter(final PaletteFilterHostOperations host, final PaletteFilterState state, final String text) {
        // Record the keyword synchronously: the debounce timer applies state.filterText when it
        // fires, so it must always reflect the latest keystroke (last-change-wins semantics).
        state.filterText = PaletteFilterHostOperations.normalize(text);
        if (state.treeFilterTimer == null) {
            state.treeFilterTimer = new Timer(FILTER_DEBOUNCE_MS, event -> {
                applyTreeFilter(host, state, state.tree, state.filterText);
            });
            state.treeFilterTimer.setRepeats(false);
        }
        state.treeFilterTimer.restart();
    }

    static void applyTreeFilter(final PaletteFilterHostOperations host, final PaletteFilterState state, final JTree tree, final String text) {
        try {
            if (tree == null) {
                return;
            }
            final String keyword = PaletteFilterHostOperations.normalize(text);
            final TreeModel original = state.treeModel;
            if (original == null) {
                host.lastAttachStatus.put(state.kind, "tree-filter:no-model");
                return;
            }
            final TreeModel current = tree.getModel();
            if (current != original && current != state.filteredTreeModel) {
                host.lastAttachStatus.put(state.kind, "tree-filter:host-model-replaced \""
                    + current.getClass().getName());
                if (state.filteredTreeModel != null) {
                    state.filteredTreeModel.dispose();
                    state.filteredTreeModel = null;
                }
                state.treeModel = current;
                applyTreeFilter(host, state, tree, text);
                return;
            }
            if (keyword.isEmpty()) {
                restoreOriginalDeformerTree(state, tree);
                host.lastAttachStatus.put(state.kind, "tree-filter keyword= restored");
                return;
            }
            // Fail closed unless the exact-version node→source accessor binding is available:
            // with an unresolvable accessor every search text would be empty and the whole tree
            // would silently collapse (the 5.2.03 regression). Restore the original tree model
            // and never install a FilteredTreeModel whose matches would all be empty.
            final String nodeSourceUnavailable = host.nodeSourceUnavailableDiagnostic();
            if (nodeSourceUnavailable != null) {
                restoreOriginalDeformerTree(state, tree);
                host.lastAttachStatus.put(state.kind, nodeSourceUnavailable);
                return;
            }
            // A fresh model per keyword: the old applied model stays installed while the new one
            // pre-warms its caches on the background executor, so the EDT never performs the full
            // reflective tree walk. The EDT only swaps the model and expands rows afterwards.
            final FilteredTreeModel filtered = new FilteredTreeModel(
                original, keyword, host::deformerNodeSearchText);
            final TreeModel installed = tree.getModel();
            final FilteredTreeModel applied = installed instanceof FilteredTreeModel model ? model : null;
            if (state.pendingFilteredTreeModel != null && state.pendingFilteredTreeModel != applied) {
                state.pendingFilteredTreeModel.dispose();
            }
            state.pendingFilteredTreeModel = filtered;
            TREE_FILTER_EXECUTOR.execute(() -> {
                try {
                    filtered.prewarm();
                } catch (Throwable ignored) {
                    // Best-effort: on failure the model falls back to lazy EDT traversal (legacy behavior).
                }
                PaletteFilterHostOperations.onEdt(() -> {
                    if (state.pendingFilteredTreeModel != filtered) {
                        filtered.dispose(); // superseded by a newer keyword before install
                        return;
                    }
                    state.pendingFilteredTreeModel = null;
                    final TreeModel appliedModel = tree.getModel();
                    if (appliedModel != original && appliedModel != applied) {
                        // The host replaced the model while pre-warming; keep the host model and let
                        // the next reconcile/keystroke rebuild against it (legacy re-apply semantics).
                        host.lastAttachStatus.put(state.kind, "tree-filter:host-model-replaced \""
                            + appliedModel.getClass().getName());
                        state.treeModel = appliedModel;
                        state.filteredTreeModel = null;
                        if (applied != null) applied.dispose();
                        filtered.dispose();
                        return;
                    }
                    state.filteredTreeModel = filtered;
                    if (appliedModel != filtered) {
                        tree.setModel(filtered);
                    }
                    if (applied != null && applied != filtered) {
                        applied.dispose();
                    }
                    expandFilteredTree(tree);
                    PaletteComponentFinder.refreshTableModel(state.table);
                    host.lastAttachStatus.put(state.kind, "tree-filter keyword=" + keyword
                        + " original=" + original.getClass().getSimpleName()
                        + " treeRows=" + tree.getRowCount()
                        + " tableRows=" + (state.table == null ? -1 : state.table.getRowCount()));
                });
            });
        } catch (Throwable failure) {
            host.lastAttachStatus.put(state.kind, "tree-filter-failed:"
                + failure.getClass().getSimpleName() + ":" + failure.getMessage());
        }
    }

    static void expandFilteredTree(final JTree tree) {
        for (int row = 0; row < tree.getRowCount() && row < 2_000; row++) {
            tree.expandRow(row);
        }
    }

    /** Restores the original tree model and disposes any installed or pending filtered model. */
    static void restoreOriginalDeformerTree(final PaletteFilterState state, final JTree tree) {
        final TreeModel original = state.treeModel;
        if (original != null && tree.getModel() != original) {
            tree.setModel(original);
            PaletteComponentFinder.refreshTableModel(state.table);
        }
        if (state.filteredTreeModel != null) {
            state.filteredTreeModel.dispose();
            state.filteredTreeModel = null;
        }
        if (state.pendingFilteredTreeModel != null) {
            state.pendingFilteredTreeModel.dispose();
            state.pendingFilteredTreeModel = null;
        }
    }
}
