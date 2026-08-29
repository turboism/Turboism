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
                case "plugins.column.author" -> "Author";
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
            id, name, "1.0.0", "", "ENABLED", "ENABLED", core, Optional.empty(), category, tags,
            List.of(
                new CorePluginManagement.Author("First Author", Optional.empty()),
                new CorePluginManagement.Author("Second Author", Optional.of("second@example.test"))
            )
        );
    }

    @Test
    void tableShowsDisplayNameAlongsideIdentifierAndAuthors() {
        final CoreWindows.PluginTableModel model = new CoreWindows.PluginTableModel(I18N);
        model.setPlugins(List.of(row("a.plugin", "Localized A", "modeling", List.of("parameter"), false)));

        assertEquals(9, model.getColumnCount());
        assertEquals("ID", model.getColumnName(1));
        assertEquals("Author", model.getColumnName(2));
        assertEquals("Category", model.getColumnName(7));
        assertEquals("Tags", model.getColumnName(8));
        assertEquals("Localized A", model.getValueAt(0, 0));
        assertEquals("a.plugin", model.getValueAt(0, 1));
        assertEquals("First Author, Second Author", model.getValueAt(0, 2));
    }

    @Test
    void categoryCellShowsLocalizedLabelForRegisteredAndFallbackCategories() {
        final CoreWindows.PluginTableModel model = new CoreWindows.PluginTableModel(I18N);
        model.setPlugins(List.of(
            row("a.plugin", "A", "modeling", List.of("parameter"), false),
            row("b.plugin", "B", "workflow", List.of("backup"), false),
            row("c.plugin", "C", "other", List.of(), false)
        ));

        assertEquals("Modeling", model.getValueAt(0, 7));
        assertEquals("Workflow", model.getValueAt(1, 7));
        assertEquals("Other", model.getValueAt(2, 7));
        assertEquals("parameter", model.getValueAt(0, 8));
        assertEquals("backup", model.getValueAt(1, 8));
        assertEquals("", model.getValueAt(2, 8));
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
