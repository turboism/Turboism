package dev.turboism.plugin.scenepalette;

import dev.turboism.sdk.ui.table.SceneTableService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Plugin-owned Scene palette sorting policy ported from the legacy enhancer. */
final class SceneTableSorter implements AutoCloseable {

    private final SceneTableService service;
    private final ManualOrderStore store;
    private final Consumer<String> debug;
    private final Map<String, String> baseHeaders = new HashMap<>();
    private SceneTableService.TableSnapshot snapshot;
    private List<String> manualOrder = List.of();
    private String scopeId = "";
    private long scopeGeneration;
    private long manualGeneration;
    private boolean loadPending;
    private boolean persistenceBlocked;
    private boolean closed;
    private String sortColumn;
    private boolean ascending;

    SceneTableSorter(final SceneTableService service) {
        this(service, ManualOrderStore.unavailable(), ignored -> { });
    }

    SceneTableSorter(
        final SceneTableService service,
        final ManualOrderStore store,
        final dev.turboism.sdk.plugin.PluginLogger logger
    ) {
        this(service, store, logger::info);
    }

    private SceneTableSorter(
        final SceneTableService service,
        final ManualOrderStore store,
        final Consumer<String> debug
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.store = Objects.requireNonNull(store, "store");
        this.debug = Objects.requireNonNull(debug, "debug");
    }

    synchronized void onSnapshot(final SceneTableService.TableSnapshot next) {
        if (closed) return;
        snapshot = Objects.requireNonNull(next, "next");
        next.columns().forEach(column -> baseHeaders.put(column.id(), stripMarker(column.label())));
        final List<String> liveOrder = itemIds(next);
        if (!Objects.equals(scopeId, next.scopeId())) {
            scopeId = next.scopeId();
            manualOrder = liveOrder;
            final long generation = ++scopeGeneration;
            final long orderGeneration = manualGeneration;
            persistenceBlocked = false;
            loadPending = !scopeId.isBlank();
            apply();
            if (loadPending) {
                load(scopeId, generation, orderGeneration);
            }
            return;
        }
        final List<String> merged = merge(manualOrder, liveOrder);
        if (!merged.equals(manualOrder)) {
            manualOrder = merged;
            if (sortColumn == null && !loadPending) {
                persistManualOrder();
            }
        }
        apply();
    }

    private void load(
        final String requestedScope,
        final long generation,
        final long orderGeneration
    ) {
        try {
            store.load(requestedScope).handle((loaded, failure) -> {
                acceptLoad(requestedScope, generation, orderGeneration, loaded, failure);
                return null;
            });
        } catch (RuntimeException failure) {
            acceptLoad(requestedScope, generation, orderGeneration, null, failure);
        }
    }

    private synchronized void acceptLoad(
        final String requestedScope,
        final long generation,
        final long orderGeneration,
        final ManualOrderStore.LoadResult loaded,
        final Throwable failure
    ) {
        if (closed || generation != scopeGeneration || !Objects.equals(requestedScope, scopeId)) return;
        loadPending = false;
        if (failure != null || loaded == null) {
            persistenceBlocked = true;
            debug.accept("Scene manual order load failed; keeping the current order.");
            return;
        }
        if (loaded.status() == ManualOrderStore.LoadStatus.UNUSABLE) {
            persistenceBlocked = true;
            debug.accept("Scene manual order is unusable; keeping the current order.");
            return;
        }
        persistenceBlocked = false;
        if (orderGeneration != manualGeneration) {
            persistManualOrder();
            return;
        }
        switch (loaded.status()) {
            case CURRENT, LEGACY -> {
                manualOrder = merge(loaded.itemIds(), itemIds(snapshot));
                if (loaded.status() == ManualOrderStore.LoadStatus.LEGACY) {
                    persistManualOrder();
                }
            }
            case MISSING -> { }
        }
        apply();
    }

    private static List<String> itemIds(final SceneTableService.TableSnapshot value) {
        return value == null
            ? List.of()
            : value.items().stream().map(SceneTableService.Item::id).toList();
    }

    private static List<String> merge(final List<String> stored, final List<String> live) {
        final Set<String> liveIds = new HashSet<>(live);
        final Set<String> merged = new LinkedHashSet<>();
        stored.stream().filter(liveIds::contains).forEach(merged::add);
        merged.addAll(live);
        return List.copyOf(merged);
    }

    private void persistManualOrder() {
        if (scopeId.isBlank() || loadPending || persistenceBlocked) return;
        try {
            store.save(scopeId, manualOrder).exceptionally(failure -> {
                debug.accept("Scene manual order save failed.");
                return null;
            });
        } catch (RuntimeException failure) {
            debug.accept("Scene manual order save failed.");
        }
    }

    synchronized void onItemOrderChanged(final SceneTableService.ItemOrderChanged changed) {
        if (closed || !Objects.equals(SceneTableService.SCENE_TABLE_ID, changed.tableId())
            || !Objects.equals(scopeId, changed.scopeId())) {
            return;
        }
        manualOrder = merge(changed.itemIds(), itemIds(snapshot));
        manualGeneration++;
        persistManualOrder();
    }

    synchronized void onHeaderClick(final SceneTableService.HeaderClick click) {
        if (closed || snapshot == null || !Objects.equals(snapshot.tableId(), click.tableId())) return;
        if (!Objects.equals(sortColumn, click.columnId())) {
            sortColumn = click.columnId();
            ascending = false;
        } else if (!ascending) {
            ascending = true;
        } else {
            sortColumn = null;
            ascending = false;
        }
        apply();
        if (sortColumn == null && !loadPending) persistManualOrder();
    }

    private void apply() {
        final SceneTableService.TableSnapshot current = snapshot;
        if (closed || current == null) {
            return;
        }
        service.setManualReordering(current.tableId(), sortColumn == null);
        current.columns().forEach(column -> service.setHeader(
            current.tableId(),
            column.id(),
            baseHeaders.getOrDefault(column.id(), stripMarker(column.label())) + marker(column.id())
        ));
        if (sortColumn == null) {
            service.setItemOrder(current.tableId(), manualOrder);
            return;
        }
        final List<SceneTableService.Item> sorted = new ArrayList<>(current.items());
        final int direction = ascending ? 1 : -1;
        sorted.sort((left, right) -> direction * compareNatural(
            left.cells().getOrDefault(sortColumn, ""),
            right.cells().getOrDefault(sortColumn, "")
        ));
        service.setItemOrder(current.tableId(), sorted.stream().map(SceneTableService.Item::id).toList());
    }

    private String marker(final String columnId) {
        if (!Objects.equals(sortColumn, columnId)) {
            return "";
        }
        return ascending ? " ↑" : " ↓";
    }

    private static String stripMarker(final String label) {
        if (label == null) return "";
        return label.replaceFirst(" [↑↓]$", "");
    }

    static int compareNatural(final String left, final String right) {
        final String a = Objects.requireNonNullElse(left, "");
        final String b = Objects.requireNonNullElse(right, "");
        int ai = 0;
        int bi = 0;
        while (ai < a.length() && bi < b.length()) {
            final char ac = a.charAt(ai);
            final char bc = b.charAt(bi);
            if (Character.isDigit(ac) && Character.isDigit(bc)) {
                int aEnd = ai;
                int bEnd = bi;
                while (aEnd < a.length() && Character.isDigit(a.charAt(aEnd))) aEnd++;
                while (bEnd < b.length() && Character.isDigit(b.charAt(bEnd))) bEnd++;
                final String aNumber = stripLeadingZeroes(a.substring(ai, aEnd));
                final String bNumber = stripLeadingZeroes(b.substring(bi, bEnd));
                final int numberOrder = Comparator.comparingInt(String::length).thenComparing(String::compareTo)
                    .compare(aNumber, bNumber);
                if (numberOrder != 0) return numberOrder;
                ai = aEnd;
                bi = bEnd;
                continue;
            }
            final int characterOrder = Character.compare(
                Character.toLowerCase(ac),
                Character.toLowerCase(bc)
            );
            if (characterOrder != 0) return characterOrder;
            ai++;
            bi++;
        }
        return Integer.compare(a.length(), b.length());
    }

    private static String stripLeadingZeroes(final String value) {
        final String stripped = value.replaceFirst("^0+(?!$)", "");
        return stripped.toLowerCase(Locale.ROOT);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        scopeGeneration++;
        loadPending = false;
        final String tableId = snapshot == null ? SceneTableService.SCENE_TABLE_ID : snapshot.tableId();
        baseHeaders.forEach((columnId, base) -> service.setHeader(tableId, columnId, stripMarker(base)));
        service.setManualReordering(tableId, false);
        snapshot = null;
        scopeId = "";
        manualOrder = List.of();
        sortColumn = null;
        ascending = false;
        baseHeaders.clear();
    }
}
