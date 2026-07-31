package dev.turboism.ui.table;

import dev.turboism.sdk.ui.table.SceneTableService;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.MouseInputAdapter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import java.awt.event.MouseEvent;
import java.awt.Point;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Exact 5.3.02 Scene palette host operations ported from the validated legacy path. */
public final class SceneTableHostOperations implements RuntimeSceneTableService.Host {

    private static final int FAST_CONNECT_ATTEMPTS = 600;
    private static final int CONNECT_DELAY_MS = 250;
    private static final int IDLE_CONNECT_DELAY_MS = 2_000;
    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    );
    private static final String PALETTE_PROPERTY = "dev.turboism.scenePalette";

    private final RuntimeSceneTableService service;
    private volatile Object palette;
    private JTable table;
    private MouseInputAdapter headerClickListener;
    private MouseInputAdapter rowDragListener;
    private SceneTableDragSupport.DragOverlay dragOverlay;
    private boolean manualReordering;
    private volatile long connectionToken;

    public SceneTableHostOperations() {
        service = new RuntimeSceneTableService(this);
    }

    public RuntimeSceneTableService service() {
        return service;
    }

    /** Resolves the Scene palette through the same controller path used by the legacy framework. */
    public boolean connect(final ClassLoader hostClassLoader) {
        final long token = ++connectionToken;
        connect(hostClassLoader, token, 0);
        return true;
    }

    private void connect(final ClassLoader hostClassLoader, final long token, final int attempt) {
        onEdt(() -> {
            if (token != connectionToken) return;
            final Object nativePalette = resolvePalette(hostClassLoader);
            if (nativePalette != null && attachNow(nativePalette)) return;
            final boolean fast = attempt + 1 < FAST_CONNECT_ATTEMPTS;
            final javax.swing.Timer retry = new javax.swing.Timer(
                fast ? CONNECT_DELAY_MS : IDLE_CONNECT_DELAY_MS,
                ignored -> connect(hostClassLoader, token, attempt + 1)
            );
            retry.setRepeats(false);
            retry.start();
        });
    }

    private static Object resolvePalette(final ClassLoader hostClassLoader) {
        for (Window window : Window.getWindows()) {
            final Object palette = findScenePalette(window, hostClassLoader);
            if (palette != null) return palette;
        }
        return null;
    }

    private static Object findScenePalette(final Component component, final ClassLoader hostClassLoader) {
        if (component instanceof JTable table) {
            final Object remembered = table.getClientProperty(PALETTE_PROPERTY);
            if (remembered instanceof java.lang.ref.WeakReference<?> reference && reference.get() != null) {
                return reference.get();
            }
            for (java.awt.event.MouseListener listener : table.getMouseListeners()) {
                if (listener != null
                    && SceneTableDragSupport.isNativeSceneRowListener(listener)) {
                    return field(listener, "a");
                }
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final Object palette = findScenePalette(child, hostClassLoader);
                if (palette != null) return palette;
            }
        }
        return null;
    }


    public void disconnect() {
        connectionToken++;
        onEdt(() -> {
            if (table != null && table.getTableHeader() != null && headerClickListener != null) {
                table.getTableHeader().removeMouseListener(headerClickListener);
            }
            headerClickListener = null;
            if (table != null && rowDragListener != null) {
                table.removeMouseListener(rowDragListener);
                table.removeMouseMotionListener(rowDragListener);
            }
            rowDragListener = null;
            if (dragOverlay != null) dragOverlay.finish();
            dragOverlay = null;
            table = null;
            palette = null;
        });
    }

    public void attach(final Object nativePalette) {
        if (nativePalette != null) onEdt(() -> attachNow(nativePalette));
    }

    private boolean attachNow(final Object nativePalette) {
        final Object wrapper = invoke(nativePalette, "c");
        final Object swingTable = invoke(wrapper, "getJTable");
        final JTable resolvedTable = swingTable instanceof JTable value ? value : null;
        final List<Object> rows = tableData(nativePalette);
        if (resolvedTable == null || rows == null) {
            return false;
        }
        palette = nativePalette;
        table = resolvedTable;
        table.putClientProperty(PALETTE_PROPERTY, new java.lang.ref.WeakReference<>(nativePalette));
        ensureHeaderClickHandler();
        installManualReordering();
        service.publishSnapshot(snapshot(nativePalette));
        return true;
    }

    @Override
    public void setHeader(final String columnId, final String label) {
        onEdt(() -> {
            final int column = columnIndex(columnId);
            if (table == null || column < 0 || column >= table.getColumnModel().getColumnCount()) return;
            table.getColumnModel().getColumn(column).setHeaderValue(label);
            if (table.getTableHeader() != null) table.getTableHeader().repaint();
        });
    }

    @Override
    public void setItemPosition(final String itemId, final int position) {
        final SceneTableService.TableSnapshot current = snapshot(palette);
        final List<String> order = current.items().stream().map(SceneTableService.Item::id).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        final int source = order.indexOf(itemId);
        if (source < 0 || position < 0 || position >= order.size() || source == position) return;
        order.add(position, order.remove(source));
        setItemOrder(order);
    }

    @Override
    public void setItemOrder(final List<String> itemIds) {
        onEdt(() -> applyOrder(itemIds, false));
    }

    @Override
    public void setManualReordering(final boolean enabled) {
        onEdt(() -> {
            if (manualReordering == enabled && (enabled == (rowDragListener != null))) return;
            manualReordering = enabled;
            installManualReordering();
        });
    }

    private void applyOrder(final List<String> itemIds, final boolean persistNativeOrder) {
        final Object currentPalette = palette;
        final JTable currentTable = table;
        final List<Object> rows = tableData(currentPalette);
        if (currentPalette == null || currentTable == null || rows == null) return;

        final List<Object> currentDocuments = new ArrayList<>(sceneDocs(currentPalette));
        final Map<String, Object> documents = new LinkedHashMap<>();
        for (Object document : currentDocuments) documents.put(id(document), document);
        final List<Object> ordered = new ArrayList<>();
        for (String itemId : itemIds) {
            final Object document = documents.remove(itemId);
            if (document != null) ordered.add(document);
        }
        ordered.addAll(documents.values());
        if (ordered.size() != currentDocuments.size()) return;

        if (persistNativeOrder) replaceSceneDocOrder(currentPalette, ordered, true);
        rewriteTableRows(rows, ordered);
        fireTableChanged(currentTable);
        if (persistNativeOrder) notifySceneOrderChanged(currentPalette);
        if (persistNativeOrder) {
            final SceneTableService.TableSnapshot changed = snapshot(currentPalette);
            service.publishItemOrderChanged(new SceneTableService.ItemOrderChanged(
                changed.tableId(),
                changed.scopeId(),
                changed.items().stream().map(SceneTableService.Item::id).toList()
            ));
            service.publishSnapshot(changed);
    }
    }

    private void installManualReordering() {
        if (table == null) return;
        SceneTableDragSupport.removeNativeRowListeners(table);
        if (!manualReordering) {
            if (dragOverlay != null) dragOverlay.finish();
            return;
        }
        if (rowDragListener != null) return;
        dragOverlay = new SceneTableDragSupport.DragOverlay(table);
        dragOverlay.attach();
        rowDragListener = new MouseInputAdapter() {
            private int pressedRow = -1;
            private Point pressedPoint;
            private boolean dragging;

            @Override public void mousePressed(final MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                pressedRow = table.rowAtPoint(event.getPoint());
                pressedPoint = event.getPoint();
                dragging = false;
                if (pressedRow >= 0) {
                    SceneTableDragSupport.removeConflictingMouseMotionListeners(table, this);
                    event.consume();
                }
            }

            @Override public void mouseDragged(final MouseEvent event) {
                if (pressedRow < 0 || !manualReordering) return;
                if (!dragging && pressedPoint != null && pressedPoint.distance(event.getPoint()) >= 4) {
                    dragging = true;
                    dragOverlay.startDrag(pressedRow, pressedPoint);
                }
                if (!dragging) return;
                dragOverlay.updateDrag(event.getPoint());
                event.consume();
            }

            @Override public void mouseReleased(final MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    dragOverlay.finish();
                    reset();
                    return;
                }
                final int releasedRow = dragging
                    ? dragOverlay.targetRow()
                    : table.rowAtPoint(event.getPoint());
                if (dragging) dragOverlay.finish();
                if (dragging && manualReordering && releasedRow >= 0) {
                    moveScene(pressedRow, releasedRow);
                } else if (releasedRow >= 0) {
                    syncSelectedRow(releasedRow);
                }
                reset();
                event.consume();
            }

            @Override public void mouseClicked(final MouseEvent event) {
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


    private void moveScene(final int sourceRow, final int targetRow) {
        final Object currentPalette = palette;
        final JTable currentTable = table;
        final List<Object> rows = tableData(currentPalette);
        final List<Object> documents = sceneDocs(currentPalette);
        if (currentPalette == null || currentTable == null || rows == null
            || sourceRow < 0 || targetRow < 0 || sourceRow >= documents.size()
            || targetRow >= documents.size() || sourceRow == targetRow) return;

        final Object moving = documents.remove(sourceRow);
        documents.add(targetRow, moving);
        final List<Object> ordered = new ArrayList<>(documents);
        syncAnimationOrder(currentPalette, ordered);
        rewriteTableRows(rows, ordered);
        fireTableChanged(currentTable);
        notifySceneOrderChanged(currentPalette);
        if (!sameOrder(sceneDocs(currentPalette), ordered)) {
            final List<Object> restoredDocuments = sceneDocs(currentPalette);
            restoredDocuments.clear();
            restoredDocuments.addAll(ordered);
            syncAnimationOrder(currentPalette, ordered);
            rewriteTableRows(rows, ordered);
            fireTableChanged(currentTable);
            invoke(invoke(currentPalette, "e"), "updateLastModifiedTimeOfAllDocs");
        }

        final SceneTableService.TableSnapshot changed = snapshot(currentPalette);
        service.publishItemOrderChanged(new SceneTableService.ItemOrderChanged(
            changed.tableId(),
            changed.scopeId(),
            changed.items().stream().map(SceneTableService.Item::id).toList()
        ));
        service.publishSnapshot(changed);
        syncSelectedRow(targetRow);
    }

    private void syncSelectedRow(final int row) {
        if (row < 0 || table == null || row >= table.getRowCount()) return;
        final Object selectedBefore = invoke(palette, "d");
        invoke(palette, "b", Integer.valueOf(row));
        final Object selectedAfter = invoke(palette, "d");
        if (!(selectedAfter instanceof Number value) || value.intValue() != row) {
            if (!(selectedBefore instanceof Number value) || value.intValue() != row) {
                invoke(palette, "a", Integer.valueOf(row));
            }
        }
        table.getSelectionModel().setSelectionInterval(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
        table.repaint();
    }

    private void openSceneAtRow(final int row) {
        final List<Object> documents = sceneDocs(palette);
        if (row < 0 || row >= documents.size()) return;
        syncSelectedRow(row);
        final Object document = documents.get(row);
        final Object completePack = invoke(palette, "a");
        final Object viewContext = invoke(completePack, "getCurrentViewContext");
        final Object currentDocument = invoke(viewContext, "getDoc");
        if (currentDocument != null
            && "com.live2d.cubism.doc.modeling.CModelingDocument".equals(currentDocument.getClass().getName())) {
            invoke(document, "openScene");
        } else {
            invoke(document, "switchScene$default", document, null, Integer.valueOf(1), null);
        }
        table.repaint();
    }

    private static void replaceSceneDocOrder(
        final Object palette,
        final List<Object> ordered,
        final boolean syncAnimation
    ) {
        final Object content = invoke(palette, "e");
        final List<Object> documents = list(invoke(content, "getSceneDocs"));
        documents.clear();
        documents.addAll(ordered);
        if (syncAnimation) {
            final Object animation = invoke(content, "getAnimation");
            final List<Object> scenes = list(invoke(animation, "get_scenes"));
            scenes.clear();
            for (Object document : ordered) scenes.add(invoke(document, "getSceneSource"));
        }
    }

    private static void syncAnimationOrder(final Object palette, final List<Object> ordered) {
        final Object content = invoke(palette, "e");
        final List<Object> scenes = list(invoke(invoke(content, "getAnimation"), "get_scenes"));
        scenes.clear();
        for (Object document : ordered) scenes.add(invoke(document, "getSceneSource"));
    }

    private static boolean sameOrder(final List<Object> left, final List<Object> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (!id(left.get(index)).equals(id(right.get(index)))) return false;
        }
        return true;
    }


    private static void notifySceneOrderChanged(final Object palette) {
        final Object content = invoke(palette, "e");
        final Object completePack = invoke(content, "getCompletePack");
        invoke(invoke(completePack, "getUpdateManager"), "updateScene", content);
        invoke(content, "updateLastModifiedTimeOfAllDocs");
    }

    private void ensureHeaderClickHandler() {
        final JTableHeader header = table.getTableHeader();
        if (header == null) return;
        if (headerClickListener != null) {
            for (java.awt.event.MouseListener listener : header.getMouseListeners()) {
                if (listener == headerClickListener) return;
            }
        }
        headerClickListener = new MouseInputAdapter() {
            @Override
            public void mouseClicked(final MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                final JTableHeader source = (JTableHeader) event.getSource();
                if (source.getResizingColumn() != null || source.getDraggedColumn() != null) return;
                final int viewColumn = source.columnAtPoint(event.getPoint());
                if (viewColumn >= 0 && viewColumn < 3) {
                    service.publishSnapshot(snapshot(palette));
                    service.publishHeaderClick(columnId(viewColumn));
            }
            }
        };
        header.addMouseListener(headerClickListener);
    }

    private static SceneTableService.TableSnapshot snapshot(final Object palette) {
        final JTable table = table(palette);
        final List<SceneTableService.Column> columns = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            final Object header = table == null || index >= table.getColumnModel().getColumnCount()
                ? columnId(index)
                : table.getColumnModel().getColumn(index).getHeaderValue();
            columns.add(new SceneTableService.Column(columnId(index), String.valueOf(header)));
        }
        final List<SceneTableService.Item> items = new ArrayList<>();
        for (Object document : sceneDocs(palette)) {
            final Object source = invoke(document, "getSceneSource");
            final Object movieInfo = invoke(source, "getMovieInfo");
            final Map<String, String> cells = new LinkedHashMap<>();
            cells.put("name", text(invoke(source, "getSceneName")));
            cells.put("duration", text(invoke(movieInfo, "getDisplayDuration")));
            cells.put("tag", text(invoke(source, "getTag")));
            items.add(new SceneTableService.Item(id(document), cells));
        }
        return new SceneTableService.TableSnapshot(SceneTableService.SCENE_TABLE_ID, scopeId(palette), columns, items);
    }

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
        if (table.getModel() instanceof AbstractTableModel model) model.fireTableDataChanged();
        table.revalidate();
        table.repaint();
    }

    private static List<Object> sceneDocs(final Object palette) {
        return list(invoke(invoke(palette, "e"), "getSceneDocs"));
    }

    private static String scopeId(final Object palette) {
        final Object file = invoke(invoke(palette, "e"), "getFile");
        final String source;
        if (file instanceof File value) {
            source = "file:" + value.getAbsolutePath().toLowerCase(Locale.ROOT);
        } else {
            final List<String> identifiers = new ArrayList<>();
            for (Object document : sceneDocs(palette)) identifiers.add(id(document));
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

    private static JTable table(final Object palette) {
        final Object value = invoke(invoke(palette, "c"), "getJTable");
        return value instanceof JTable table ? table : null;
    }


    private static List<Object> tableData(final Object palette) {
        final Object value = field(palette, "h");
        return value instanceof List<?> ? list(value) : null;
    }

    private static String id(final Object document) {
        final String value = text(invoke(invoke(document, "getSceneSource"), "getGuid")).trim();
        final java.util.regex.Matcher matcher = UUID_PATTERN.matcher(value);
        return (matcher.find() ? matcher.group() : value).toLowerCase(Locale.ROOT);
    }


    private static int columnIndex(final String id) {
        return switch (id) { case "name" -> 0; case "duration" -> 1; case "tag" -> 2; default -> -1; };
    }

    private static String columnId(final int index) {
        return switch (index) { case 0 -> "name"; case 1 -> "duration"; case 2 -> "tag"; default -> "column-" + index; };
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static String text(final Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Object field(final Object target, final String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object invoke(final Object target, final String name, final Object... arguments) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(target, arguments);
                } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                    // Try another overload or superclass, matching the legacy reflective adapter.
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static void onEdt(final Runnable operation) {
        if (SwingUtilities.isEventDispatchThread()) operation.run();
        else SwingUtilities.invokeLater(operation);
    }
}
