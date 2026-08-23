package dev.turboism.plugin.scenepalette;

import dev.turboism.sdk.ui.table.SceneTableService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    void onSnapshot(final SceneTableService.TableSnapshot next) {
        snapshot = next;
        next.columns().forEach(column -> baseHeaders.putIfAbsent(column.id(), stripMarker(column.label())));
        final List<String> liveOrder = next.items().stream().map(SceneTableService.Item::id).toList();
        if (!Objects.equals(scopeId, next.scopeId())) {
            scopeId = next.scopeId();
            manualOrder = liveOrder;
            final long generation = ++scopeGeneration;
            if (!scopeId.isBlank()) {
                store.load(scopeId).thenAccept(stored -> {
                    if (generation != scopeGeneration || snapshot == null || !Objects.equals(scopeId, snapshot.scopeId())) return;
                    manualOrder = merge(stored, snapshot.items().stream().map(SceneTableService.Item::id).toList());
                    apply();
                });
            }
        } else if (sortColumn == null) {
            final List<String> merged = merge(manualOrder, liveOrder);
            if (!merged.equals(manualOrder)) {
                manualOrder = merged;
                persistManualOrder();
            }
        } else {
            manualOrder = merge(manualOrder, liveOrder);
        }
        apply();
    }

    private static List<String> merge(final List<String> stored, final List<String> live) {
        final List<String> merged = new ArrayList<>();
        stored.stream().filter(live::contains).filter(id -> !merged.contains(id)).forEach(merged::add);
        live.stream().filter(id -> !merged.contains(id)).forEach(merged::add);
        return List.copyOf(merged);
    }

    private void persistManualOrder() {
        if (!scopeId.isBlank()) store.save(scopeId, manualOrder);
    }

    void onItemOrderChanged(final SceneTableService.ItemOrderChanged changed) {
        if (!Objects.equals(scopeId, changed.scopeId())) return;
        manualOrder = List.copyOf(changed.itemIds());
        persistManualOrder();
    }

    void onHeaderClick(final SceneTableService.HeaderClick click) {
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
        service.setManualReordering(SceneTableService.SCENE_TABLE_ID, sortColumn == null);
        if (sortColumn == null) persistManualOrder();
    }

    private void apply() {
        final SceneTableService.TableSnapshot current = snapshot;
        if (current == null) {
            return;
        }
        service.setManualReordering(current.tableId(), sortColumn == null);
        current.columns().forEach(column -> service.setHeader(
            current.tableId(),
            column.id(),
            baseHeaders.getOrDefault(column.id(), column.label()) + marker(column.id())
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
    public void close() {
        service.setManualReordering(SceneTableService.SCENE_TABLE_ID, false);
        snapshot = null;
        scopeGeneration++;
        scopeId = "";
        manualOrder = List.of();
    }
}
