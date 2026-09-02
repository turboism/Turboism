package dev.turboism.ui.table;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.sdk.ui.table.SceneTableService;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.MouseInputAdapter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Exact-artifact Runtime Scene table bridge with fail-closed host member binding. */
public final class SceneTableHostOperations implements RuntimeSceneTableService.Host,
    dev.turboism.ui.filter.PaletteFilterHostOperations.SceneFilterSink {

    private static final int FAST_CONNECT_ATTEMPTS = 600;
    private static final int CONNECT_DELAY_MS = 250;
    private static final int IDLE_CONNECT_DELAY_MS = 2_000;
    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    );
    private static final String PALETTE_PROPERTY = "dev.turboism.scenePalette";

    private final RuntimeSceneTableService service;
    private final ProfileLoader profileLoader;
    private final PaletteLocator paletteLocator;
    private final RetryScheduler retryScheduler;
    private final AtomicLong connectionToken = new AtomicLong();
    private volatile State state = State.DISCONNECTED;
    private volatile String filterText = "";
    private volatile List<Object> currentOrder;
    private volatile Object currentOrderContent;
    private volatile String currentOrderFile;
    private volatile Object palette;
    private SceneTableHostProfile.Bound bindings;
    private JTable table;
    private MouseInputAdapter headerClickListener;
    private MouseInputAdapter rowDragListener;
    private SceneTableDragSupport.DragOverlay dragOverlay;
    private final List<MouseListener> nativeMouseListeners = new ArrayList<>();
    private final List<MouseMotionListener> nativeMotionListeners = new ArrayList<>();
    private final Map<Integer, Object> originalHeaders = new LinkedHashMap<>();
    private boolean manualReordering;
    private boolean detaching;

    public SceneTableHostOperations() {
        this(
            (artifact, loader) -> SceneTableHostProfile.forArtifact(
                HostArtifactDigest.from(artifact)
            ).map(profile -> profile.bind(loader)),
            SceneTableHostOperations::resolvePalette,
            (delay, operation) -> {
                final javax.swing.Timer retry = new javax.swing.Timer(delay, ignored -> operation.run());
                retry.setRepeats(false);
                retry.start();
            }
        );
    }

    SceneTableHostOperations(
        final ProfileLoader profileLoader,
        final PaletteLocator paletteLocator,
        final RetryScheduler retryScheduler
    ) {
        this.profileLoader = Objects.requireNonNull(profileLoader, "profileLoader");
        this.paletteLocator = Objects.requireNonNull(paletteLocator, "paletteLocator");
        this.retryScheduler = Objects.requireNonNull(retryScheduler, "retryScheduler");
        service = new RuntimeSceneTableService(this);
    }
    /** Returns the runtime Scene-table service this host operations object backs. */
    public RuntimeSceneTableService service() {
        return service;
    }

    /** Returns the current bridge connection state. */
    public State state() {
        return state;
    }

    /**
     * Admits and pre-binds one exact verified host artifact before Scene palette discovery starts.
     * Unsupported artifacts and artifact-read or member-binding failures never enter polling.
     */
    public State connect(final Path verifiedArtifact, final ClassLoader hostClassLoader) {
        Objects.requireNonNull(verifiedArtifact, "verifiedArtifact");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        final long token = connectionToken.incrementAndGet();
        final Optional<SceneTableHostProfile.Bound> admitted;
        try {
            admitted = profileLoader.load(verifiedArtifact, hostClassLoader);
        } catch (Exception | LinkageError failure) {
            if (token == connectionToken.get()) state = State.FAILED;
            detachForToken(token);
            return state;
        }
        if (admitted.isEmpty()) {
            if (token == connectionToken.get()) state = State.UNSUPPORTED;
            detachForToken(token);
            return state;
        }
        final SceneTableHostProfile.Bound bound = admitted.orElseThrow();
        if (token != connectionToken.get()) return state;
        state = State.CONNECTING;
        onEdt(() -> {
            if (token != connectionToken.get()) return;
            detachCurrent();
            bindings = bound;
            pollForPalette(bound, token, 0);
        });
        return state;
    }

    private void detachForToken(final long token) {
        onEdt(() -> {
            if (token == connectionToken.get()) detachCurrent();
        });
    }

    private void pollForPalette(
        final SceneTableHostProfile.Bound bound,
        final long token,
        final int attempt
    ) {
        if (token != connectionToken.get() || state != State.CONNECTING) return;
        final Object nativePalette;
        try {
            nativePalette = paletteLocator.resolve(bound);
        } catch (RuntimeException | LinkageError failure) {
            failConnection(token);
            return;
        }
        if (nativePalette != null) {
            try {
                if (attachNow(nativePalette, bound)) {
                    if (token == connectionToken.get()) state = State.CONNECTED;
                } else {
                    schedulePoll(bound, token, attempt + 1);
                }
            } catch (RuntimeException | LinkageError failure) {
                failConnection(token);
            }
            return;
        }
        schedulePoll(bound, token, attempt + 1);
    }

    private void schedulePoll(
        final SceneTableHostProfile.Bound bound,
        final long token,
        final int attempt
    ) {
        final boolean fast = attempt < FAST_CONNECT_ATTEMPTS;
        retryScheduler.schedule(
            fast ? CONNECT_DELAY_MS : IDLE_CONNECT_DELAY_MS,
            () -> onEdt(() -> pollForPalette(bound, token, attempt))
        );
    }

    private void failConnection(final long token) {
        if (token != connectionToken.get()) return;
        state = State.FAILED;
        detachCurrent();
    }

    private static Object resolvePalette(final SceneTableHostProfile.Bound bound) {
        for (Window window : Window.getWindows()) {
            final Object palette = findScenePalette(window, bound);
            if (palette != null) return palette;
        }
        return null;
    }

    private static Object findScenePalette(
        final Component component,
        final SceneTableHostProfile.Bound bound
    ) {
        if (component instanceof JTable table) {
            final Object remembered = table.getClientProperty(PALETTE_PROPERTY);
            if (remembered instanceof java.lang.ref.WeakReference<?> reference) {
                final Object value = reference.get();
                if (bound.isController(value)) return value;
            }
            for (MouseListener listener : table.getMouseListeners()) {
                if (bound.isNativeSceneRowListener(listener)) {
                    final Object value = bound.listenerPalette(listener);
                    if (bound.isController(value)) return value;
                }
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final Object palette = findScenePalette(child, bound);
                if (palette != null) return palette;
            }
        }
        return null;
    }

    @Override
    public void setSceneFilter(final String keyword) {
        onEdt(() -> {
            filterText = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
            reconcileRowListeners();
            applyViewState();
        });
    }

    private void applyViewState() {
        final JTable currentTable = table;
        final List<Object> rows = tableData();
        if (palette == null || currentTable == null || rows == null) return;
        rewriteTableRows(rows, visibleDocuments());
        fireTableChanged(currentTable);
    }

    private boolean matchesSceneFilter(final Object document, final String keyword) {
        if (keyword == null || keyword.isEmpty()) return true;
        final SceneProjection projection = project(document);
        final String haystack = (projection.name() + "\n" + projection.duration() + "\n"
            + projection.tag()).toLowerCase(Locale.ROOT);
        return haystack.contains(keyword);
    }

    /** Invalidates pending retries and symmetrically restores all host-owned table state. */
    public void disconnect() {
        final long token = connectionToken.incrementAndGet();
        state = State.DISCONNECTED;
        onEdt(() -> {
            if (token == connectionToken.get()) detachCurrent();
        });
    }

    /** Attaches an already-resolved palette only after a verified profile has been admitted. */
    public void attach(final Object nativePalette) {
        if (nativePalette == null) return;
        final long token = connectionToken.get();
        onEdt(() -> {
            if (token != connectionToken.get()) return;
            final SceneTableHostProfile.Bound bound = bindings;
            if (bound == null) return;
            try {
                if (attachNow(nativePalette, bound)) state = State.CONNECTED;
            } catch (RuntimeException | LinkageError failure) {
                state = State.FAILED;
                detachCurrent();
            }
        });
    }

    private boolean attachNow(
        final Object nativePalette,
        final SceneTableHostProfile.Bound bound
    ) {
        if (!SwingUtilities.isEventDispatchThread() || !bound.isController(nativePalette)) return false;
        final Object wrapper = bound.controllerTable(nativePalette);
        final Object swingTable = wrapper == null ? null : bound.tableSwing(wrapper);
        final JTable resolvedTable = swingTable instanceof JTable value ? value : null;
        final Object data = bound.controllerTableData(nativePalette);
        if (resolvedTable == null || !(data instanceof List<?>)) return false;

        detachCurrent();
        bindings = bound;
        palette = nativePalette;
        table = resolvedTable;
        clearViewOrder();
        table.putClientProperty(PALETTE_PROPERTY, new java.lang.ref.WeakReference<>(nativePalette));
        ensureHeaderClickHandler();
        reconcileRowListeners();
        applyViewState();
        service.publishSnapshot(snapshot(effectiveDocuments()));
        return true;
    }

    @Override
    public void setHeader(final String columnId, final String label) {
        final long token = connectionToken.get();
        onEdt(() -> {
            if (token != connectionToken.get()) return;
            final int column = columnIndex(columnId);
            if (table == null || column < 0 || column >= table.getColumnModel().getColumnCount()) return;
            originalHeaders.putIfAbsent(
                column, table.getColumnModel().getColumn(column).getHeaderValue()
            );
            table.getColumnModel().getColumn(column).setHeaderValue(label);
            if (table.getTableHeader() != null) table.getTableHeader().repaint();
        });
    }

    @Override
    public void setItemPosition(final String itemId, final int position) {
        final long token = connectionToken.get();
        onEdt(() -> {
            if (token != connectionToken.get()) return;
            final List<String> order = effectiveDocuments().stream()
                .map(this::id)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            final int source = order.indexOf(itemId);
            if (source < 0 || position < 0 || position >= order.size() || source == position) return;
            order.add(position, order.remove(source));
            applyOrder(order);
        });
    }

    @Override
    public void setItemOrder(final List<String> itemIds) {
        final List<String> requested = List.copyOf(itemIds);
        final long token = connectionToken.get();
        onEdt(() -> {
            if (token == connectionToken.get()) applyOrder(requested);
        });
    }

    @Override
    public void setManualReordering(final boolean enabled) {
        final long token = connectionToken.get();
        onEdt(() -> {
            if (token != connectionToken.get()) return;
            if (enabled == manualReordering) return;
            manualReordering = enabled;
            if (!enabled) clearViewOrder();
            reconcileRowListeners();
            if (!enabled) applyViewState();
        });
    }

    private void applyOrder(final List<String> itemIds) {
        if (palette == null || table == null || bindings == null) return;
        final List<Object> authoritative = authoritativeDocuments();
        final Map<String, Object> documents = new LinkedHashMap<>();
        for (Object document : authoritative) documents.put(id(document), document);
        final List<Object> ordered = new ArrayList<>();
        for (String itemId : itemIds) {
            final Object document = documents.remove(itemId);
            if (document != null) ordered.add(document);
        }
        ordered.addAll(documents.values());
        if (ordered.size() != authoritative.size()) return;
        setViewOrder(ordered);
        applyViewState();
    }

    private boolean needsMappedRowListener() {
        return manualReordering || currentOrder != null || !filterText.isEmpty();
    }

    /**
     * Restores Cubism's native row listener only when the visible row order still maps 1:1 onto
     * the authoritative order. Sorting, filtering, and manual (view-only) order all diverge from
     * that mapping, so those modes keep the mapped listener that translates view rows back to
     * authoritative rows. The mapped listener still only permits dragging when manual reordering
     * is enabled; sort/filter modes use it solely for correct selection and double-click mapping.
     */
    private void reconcileRowListeners() {
        if (detaching || table == null || bindings == null) return;
        if (needsMappedRowListener()) {
            captureAndRemoveNativeRowListeners();
            installRowDragListener();
        } else {
            removeDragListener();
            restoreNativeRowListeners();
        }
    }

    private void installRowDragListener() {
        if (rowDragListener != null) return;
        dragOverlay = new SceneTableDragSupport.DragOverlay(table);
        dragOverlay.attach();
        rowDragListener = new MouseInputAdapter() {
            private int pressedRow = -1;
            private Point pressedPoint;
            private boolean dragging;

            @Override
            public void mousePressed(final MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                pressedRow = table.rowAtPoint(event.getPoint());
                pressedPoint = event.getPoint();
                dragging = false;
                if (pressedRow >= 0) event.consume();
            }

            @Override
            public void mouseDragged(final MouseEvent event) {
                if (pressedRow < 0 || !manualReordering) return;
                if (!dragging && pressedPoint != null && pressedPoint.distance(event.getPoint()) >= 4) {
                    dragging = true;
                    dragOverlay.startDrag(pressedRow, pressedPoint);
                }
                if (!dragging) return;
                dragOverlay.updateDrag(event.getPoint());
                event.consume();
            }

            @Override
            public void mouseReleased(final MouseEvent event) {
                final int releasedRow = dragging
                    ? dragOverlay.targetRow()
                    : table.rowAtPoint(event.getPoint());
                if (dragging) dragOverlay.finish();
                if (SwingUtilities.isLeftMouseButton(event) && releasedRow >= 0) {
                    if (dragging && manualReordering) moveScene(pressedRow, releasedRow);
                    else syncSelectedRow(releasedRow);
                    event.consume();
                }
                reset();
            }

            @Override
            public void mouseClicked(final MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() < 2) return;
                final int row = table.rowAtPoint(event.getPoint());
                if (row >= 0) {
                    openSceneAtRow(row);
                    event.consume();
                }
            }

            private void reset() {
                pressedRow = -1;
                pressedPoint = null;
                dragging = false;
            }
        };
        table.addMouseListener(rowDragListener);
        table.addMouseMotionListener(rowDragListener);
    }

    private void captureAndRemoveNativeRowListeners() {
        for (MouseListener listener : table.getMouseListeners()) {
            if (bindings.isNativeSceneRowListener(listener)) {
                nativeMouseListeners.add(listener);
                table.removeMouseListener(listener);
            }
        }
        for (MouseMotionListener listener : table.getMouseMotionListeners()) {
            if (bindings.isNativeSceneRowListener(listener)) {
                nativeMotionListeners.add(listener);
                table.removeMouseMotionListener(listener);
            }
        }
    }

    private void restoreNativeRowListeners() {
        if (table == null) return;
        for (MouseListener listener : nativeMouseListeners) {
            if (countIdentity(table.getMouseListeners(), listener)
                < countIdentity(nativeMouseListeners.toArray(), listener)) {
                table.addMouseListener(listener);
            }
        }
        for (MouseMotionListener listener : nativeMotionListeners) {
            if (countIdentity(table.getMouseMotionListeners(), listener)
                < countIdentity(nativeMotionListeners.toArray(), listener)) {
                table.addMouseMotionListener(listener);
            }
        }
        nativeMouseListeners.clear();
        nativeMotionListeners.clear();
    }

    private void removeDragListener() {
        if (table != null && rowDragListener != null) {
            table.removeMouseListener(rowDragListener);
            table.removeMouseMotionListener(rowDragListener);
        }
        rowDragListener = null;
        if (dragOverlay != null) dragOverlay.detach();
        dragOverlay = null;
    }

    boolean moveScene(final int sourceRow, final int targetRow) {
        final List<Object> fullOrder = effectiveDocuments();
        final List<Object> visible = filterVisible(fullOrder);
        if (sourceRow < 0 || targetRow < 0 || sourceRow >= visible.size()
            || targetRow >= visible.size() || sourceRow == targetRow) return false;

        final Object moving = visible.get(sourceRow);
        final Object target = visible.get(targetRow);
        fullOrder.remove(moving);
        int insertion = fullOrder.indexOf(target);
        if (insertion < 0) return false;
        if (sourceRow < targetRow) insertion++;
        fullOrder.add(insertion, moving);
        setViewOrder(fullOrder);
        applyViewState();

        final List<String> orderedIds = fullOrder.stream().map(this::id).toList();
        final SceneTableService.TableSnapshot changed = snapshot(fullOrder);
        service.publishItemOrderChanged(new SceneTableService.ItemOrderChanged(
            changed.tableId(), changed.scopeId(), orderedIds
        ));
        service.publishSnapshot(changed);
        syncSelectedRow(targetRow);
        return true;
    }

    private void syncSelectedRow(final int viewRow) {
        if (viewRow < 0 || table == null || viewRow >= table.getRowCount()) return;
        final List<Object> visible = visibleDocuments();
        if (viewRow >= visible.size()) return;
        final int nativeRow = indexOfIdentityOrId(authoritativeDocuments(), visible.get(viewRow));
        if (nativeRow < 0) return;
        final int selectedBefore = bindings.controllerSelectedRow(palette);
        bindings.controllerSelectRow(palette, nativeRow);
        if (bindings.controllerSelectedRow(palette) != nativeRow && selectedBefore != nativeRow) {
            bindings.controllerSelectRowFallback(palette, nativeRow);
        }
        table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
        table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
        table.repaint();
    }

    private void openSceneAtRow(final int row) {
        final List<Object> visible = visibleDocuments();
        if (row < 0 || row >= visible.size()) return;
        syncSelectedRow(row);
        final Object document = visible.get(row);
        final Object completePack = bindings.controllerCompletePack(palette);
        final Object viewContext = completePack == null
            ? null : bindings.completePackViewContext(completePack);
        final Object currentDocument = viewContext == null ? null : bindings.viewContextDoc(viewContext);
        if (bindings.isModelingDocument(currentDocument)) {
            bindings.documentOpenScene(document);
        } else if (bindings.isSceneDocument(currentDocument)) {
            bindings.documentSwitchSceneDefault(document);
        }
        table.repaint();
    }

    private void ensureHeaderClickHandler() {
        final JTableHeader header = table.getTableHeader();
        if (header == null) return;
        headerClickListener = new MouseInputAdapter() {
            @Override
            public void mouseClicked(final MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                final JTableHeader source = (JTableHeader) event.getSource();
                if (source.getResizingColumn() != null || source.getDraggedColumn() != null) return;
                final int viewColumn = source.columnAtPoint(event.getPoint());
                if (viewColumn >= 0 && viewColumn < 3) {
                    service.publishSnapshot(snapshot(effectiveDocuments()));
                    service.publishHeaderClick(columnId(viewColumn));
                }
            }
        };
        header.addMouseListener(headerClickListener);
    }

    private SceneTableService.TableSnapshot snapshot(final List<Object> documents) {
        final List<SceneTableService.Column> columns = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            final Object header = table == null || index >= table.getColumnModel().getColumnCount()
                ? columnId(index)
                : table.getColumnModel().getColumn(index).getHeaderValue();
            columns.add(new SceneTableService.Column(columnId(index), String.valueOf(header)));
        }
        final List<SceneTableService.Item> items = new ArrayList<>();
        for (Object document : documents) {
            final SceneProjection projection = project(document);
            final Map<String, String> cells = new LinkedHashMap<>();
            cells.put("name", projection.name());
            cells.put("duration", projection.duration());
            cells.put("tag", projection.tag());
            items.add(new SceneTableService.Item(projection.id(), cells));
        }
        return new SceneTableService.TableSnapshot(
            SceneTableService.SCENE_TABLE_ID, scopeId(), columns, items
        );
    }

    private void rewriteTableRows(final List<Object> rows, final List<Object> documents) {
        rows.clear();
        for (Object document : documents) {
            final SceneProjection projection = project(document);
            rows.add(projection.name());
            rows.add(projection.duration());
            rows.add(projection.tag());
        }
    }

    private static void fireTableChanged(final JTable table) {
        if (table.getModel() instanceof AbstractTableModel model) model.fireTableDataChanged();
        table.revalidate();
        table.repaint();
    }

    private List<Object> effectiveDocuments() {
        final List<Object> authoritative = authoritativeDocuments();
        if (currentOrder == null || !orderScopeMatches()) return authoritative;
        final Map<String, Object> current = new LinkedHashMap<>();
        for (Object document : authoritative) current.put(id(document), document);
        final List<Object> reconciled = new ArrayList<>();
        for (Object ordered : currentOrder) {
            final Object document = current.remove(id(ordered));
            if (document != null) reconciled.add(document);
        }
        reconciled.addAll(current.values());
        currentOrder = new ArrayList<>(reconciled);
        return reconciled;
    }

    private List<Object> visibleDocuments() {
        return filterVisible(effectiveDocuments());
    }

    private List<Object> filterVisible(final List<Object> documents) {
        final List<Object> visible = new ArrayList<>();
        for (Object document : documents) {
            if (matchesSceneFilter(document, filterText)) visible.add(document);
        }
        return visible;
    }

    private List<Object> authoritativeDocuments() {
        if (palette == null || bindings == null) return new ArrayList<>();
        final Object content = bindings.controllerContent(palette);
        if (content == null) return new ArrayList<>();
        final Object value = bindings.contentSceneDocs(content);
        return value instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<Object> tableData() {
        if (palette == null || bindings == null) return null;
        final Object value = bindings.controllerTableData(palette);
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    private void setViewOrder(final List<Object> ordered) {
        currentOrder = new ArrayList<>(ordered);
        currentOrderContent = currentContent();
        currentOrderFile = currentFileKey();
        reconcileRowListeners();
    }

    private void clearViewOrder() {
        currentOrder = null;
        currentOrderContent = null;
        currentOrderFile = null;
        reconcileRowListeners();
    }

    private boolean orderScopeMatches() {
        final Object content = currentContent();
        final String file = currentFileKey();
        if (content != currentOrderContent || !Objects.equals(file, currentOrderFile)) {
            clearViewOrder();
            return false;
        }
        return true;
    }

    private Object currentContent() {
        return palette == null || bindings == null ? null : bindings.controllerContent(palette);
    }

    private String currentFileKey() {
        final Object content = currentContent();
        if (content == null) return null;
        final Object value = bindings.contentFile(content);
        return value instanceof File file
            ? file.toPath().toAbsolutePath().normalize().toString()
            : text(value);
    }

    private String scopeId() {
        final Object content = currentContent();
        if (content == null) return "";
        final Object file = bindings.contentFile(content);
        final String source;
        if (file instanceof File value) {
            source = "file:" + value.toPath().toAbsolutePath().normalize();
        } else {
            final List<String> identifiers = new ArrayList<>();
            for (Object document : authoritativeDocuments()) identifiers.add(id(document));
            identifiers.sort(String::compareTo);
            if (identifiers.isEmpty()) return "";
            source = "scenes:" + String.join("\n", identifiers);
        }
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8));
            final StringBuilder result = new StringBuilder(64);
            for (byte part : digest) result.append(String.format("%02x", part));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String id(final Object document) {
        final Object source = bindings.documentSceneSource(document);
        return source == null ? "" : idFromSource(source);
    }

    private String idFromSource(final Object source) {
        final String value = text(bindings.sourceGuid(source)).trim();
        final java.util.regex.Matcher matcher = UUID_PATTERN.matcher(value);
        return (matcher.find() ? matcher.group() : value).toLowerCase(Locale.ROOT);
    }

    private SceneProjection project(final Object document) {
        final Object source = bindings.documentSceneSource(document);
        if (source == null) return new SceneProjection("", "", "0", "");
        final String name = text(bindings.sourceSceneName(source));
        final Object movieInfo = bindings.sourceMovieInfo(source);
        final String duration = movieInfo == null
            ? "0" : text(bindings.movieInfoDisplayDuration(movieInfo));
        final String tag = text(bindings.sourceTag(source));
        return new SceneProjection(idFromSource(source), name, duration, tag);
    }

    private record SceneProjection(String id, String name, String duration, String tag) { }

    private void detachCurrent() {
        detaching = true;
        try {
            final JTable currentTable = table;
            if (currentTable != null) {
                try {
                    removeDragListener();
                    restoreNativeRowListeners();
                    restoreHeaders();
                    restoreAuthoritativeRows();
                    if (currentTable.getTableHeader() != null && headerClickListener != null) {
                        currentTable.getTableHeader().removeMouseListener(headerClickListener);
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // Best-effort restoration; always finish tearing the bridge down.
                } finally {
                    clearPaletteProperty();
                }
            }
            headerClickListener = null;
            originalHeaders.clear();
            nativeMouseListeners.clear();
            nativeMotionListeners.clear();
            clearViewOrder();
            manualReordering = false;
            filterText = "";
            table = null;
            palette = null;
            bindings = null;
        } finally {
            detaching = false;
        }
    }

    private void clearPaletteProperty() {
        final Object property = table.getClientProperty(PALETTE_PROPERTY);
        if (property instanceof java.lang.ref.WeakReference<?> reference
            && reference.get() == palette) {
            table.putClientProperty(PALETTE_PROPERTY, null);
        }
    }

    private void restoreHeaders() {
        for (Map.Entry<Integer, Object> entry : originalHeaders.entrySet()) {
            final int index = entry.getKey();
            if (index >= 0 && index < table.getColumnModel().getColumnCount()) {
                table.getColumnModel().getColumn(index).setHeaderValue(entry.getValue());
            }
        }
        if (!originalHeaders.isEmpty() && table.getTableHeader() != null) {
            table.getTableHeader().repaint();
        }
    }

    private void restoreAuthoritativeRows() {
        final List<Object> rows = tableData();
        if (rows == null) return;
        rewriteTableRows(rows, authoritativeDocuments());
        fireTableChanged(table);
    }

    private static int indexOfIdentityOrId(final List<Object> documents, final Object target) {
        for (int index = 0; index < documents.size(); index++) {
            if (documents.get(index) == target) return index;
        }
        return -1;
    }

    private static int countIdentity(final Object[] values, final Object target) {
        return (int) Arrays.stream(values).filter(value -> value == target).count();
    }

    private static int columnIndex(final String id) {
        return switch (id) {
            case "name" -> 0;
            case "duration" -> 1;
            case "tag" -> 2;
            default -> -1;
        };
    }

    private static String columnId(final int index) {
        return switch (index) {
            case 0 -> "name";
            case 1 -> "duration";
            case 2 -> "tag";
            default -> "column-" + index;
        };
    }

    private static String text(final Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void onEdt(final Runnable operation) {
        if (SwingUtilities.isEventDispatchThread()) operation.run();
        else SwingUtilities.invokeLater(operation);
    }

    @FunctionalInterface
    interface ProfileLoader {
        Optional<SceneTableHostProfile.Bound> load(Path artifact, ClassLoader loader) throws Exception;
    }

    @FunctionalInterface
    interface PaletteLocator {
        Object resolve(SceneTableHostProfile.Bound profile);
    }

    @FunctionalInterface
    interface RetryScheduler {
        void schedule(int delayMillis, Runnable operation);
    }

    public enum State {
        DISCONNECTED,
        UNSUPPORTED,
        CONNECTING,
        CONNECTED,
        FAILED
    }
}
