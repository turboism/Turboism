package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import org.junit.jupiter.api.Test;

import javax.swing.RowFilter;
import javax.swing.table.TableRowSorter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Core Plugins table exposes localized Category/Tags and the existing row filter matches them. */
class PluginTableModelTest {

    private static final PluginLocalization I18N = new PluginLocalization() {
        @Override public Locale locale() { return Locale.ENGLISH; }
        @Override public String text(final String key) {
            return switch (key) {
                case "plugins.column.category" -> "Category";
                case "plugins.column.tags" -> "Tags";
                case "plugin.category.modeling" -> "Modeling";
                case "plugin.category.workflow" -> "Workflow";
                case "plugin.category.system" -> "System";
                case "plugin.category.other" -> "Other";
                case "plugins.core" -> "Core";
                case "plugins.column.id" -> "ID";
                default -> "?" + key;
            };
        }
        @Override public String format(final String key, final Object... arguments) { return text(key); }
        @Override public boolean contains(final String key) { return true; }
    };

    private static CorePluginManagement.PluginInfo row(
        final String id,
        final String name,
        final String category,
        final List<String> tags,
        final boolean core
    ) {
        return new CorePluginManagement.PluginInfo(
            id, name, "1.0.0", "", "ENABLED", "ENABLED", core, Optional.empty(), category, tags
        );
    }

    @Test
    void tableHasCategoryAndTagsColumnsWithLocalizedHeaders() {
        final CoreWindows.PluginTableModel model = new CoreWindows.PluginTableModel(I18N);
        model.setPlugins(List.of(row("a.plugin", "A", "modeling", List.of("parameter"), false)));

        assertEquals(8, model.getColumnCount());
        assertEquals("Category", model.getColumnName(6));
        assertEquals("Tags", model.getColumnName(7));
        assertEquals("ID", model.getColumnName(5));
    }

    @Test
    void categoryCellShowsLocalizedLabelForRegisteredAndFallbackCategories() {
        final CoreWindows.PluginTableModel model = new CoreWindows.PluginTableModel(I18N);
        model.setPlugins(List.of(
            row("a.plugin", "A", "modeling", List.of("parameter"), false),
            row("b.plugin", "B", "workflow", List.of("backup"), false),
            row("c.plugin", "C", "other", List.of(), false)
        ));

        assertEquals("Modeling", model.getValueAt(0, 6));
        assertEquals("Workflow", model.getValueAt(1, 6));
        assertEquals("Other", model.getValueAt(2, 6));
        assertEquals("parameter", model.getValueAt(0, 7));
        assertEquals("backup", model.getValueAt(1, 7));
        assertEquals("", model.getValueAt(2, 7));
    }

    @Test
    void existingRowFilterMatchesCategoryAndTagsCells() {
        final CoreWindows.PluginTableModel model = new CoreWindows.PluginTableModel(I18N);
        model.setPlugins(List.of(
            row("a.plugin", "Alpha", "modeling", List.of("parameter", "batch-edit"), false),
            row("b.plugin", "Beta", "workflow", List.of("backup"), false)
        ));
        final TableRowSorter<CoreWindows.PluginTableModel> sorter = new TableRowSorter<>(model);

        sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote("Modeling")));
        assertEquals(1, sorter.getViewRowCount());

        sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote("batch-edit")));
        assertEquals(1, sorter.getViewRowCount());

        sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote("backup")));
        assertEquals(1, sorter.getViewRowCount());

        sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote("absent")));
        assertEquals(0, sorter.getViewRowCount());
    }
}
