package dev.turboism.keybinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

class NativeShortcutCatalogTest {

    private static JMenuItem item(final String text, final String keystroke) {
        final JMenuItem item = new JMenuItem(text);
        if (keystroke != null) {
            item.setAccelerator(KeyStroke.getKeyStroke(keystroke));
        }
        return item;
    }

    @Test
    void collectEnumeratesAcceleratorsInMenuOrder() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(item("New Project", "ctrl N"));
        file.add(item("Save", "ctrl S"));
        file.add(item("No Shortcut", null));
        file.addSeparator();
        file.add(item("Save As", "ctrl shift S"));
        bar.add(file);
        JMenu edit = new JMenu("Edit");
        edit.add(item("Undo", "ctrl Z"));
        bar.add(edit);
        bar.add(item("Orphan", "ctrl O")); // non-menu component ignored

        List<KeybindingTable.NativeRow> rows = NativeShortcutCatalog.collect(bar);
        assertEquals(4, rows.size());
        assertEquals("File › New Project", rows.get(0).label());
        assertEquals("Ctrl+N", rows.get(0).nativeStroke());
        assertEquals("menu/file-new-project", rows.get(0).id());
        assertEquals("File › Save As", rows.get(2).label());
        assertEquals("Ctrl+Shift+S", rows.get(2).nativeStroke());
        assertEquals("Edit › Undo", rows.get(3).label());
        assertEquals("menu/edit-undo", rows.get(3).id());
        assertTrue(rows.stream().noneMatch(row -> row.custom()));
    }

    @Test
    void collectRecursesSubmenus() {
        JMenuBar bar = new JMenuBar();
        JMenu edit = new JMenu("Edit");
        JMenu paste = new JMenu("Paste Special");
        paste.add(item("Paste Into", "ctrl alt V"));
        edit.add(paste);
        bar.add(edit);

        List<KeybindingTable.NativeRow> rows = NativeShortcutCatalog.collect(bar);
        assertEquals(1, rows.size());
        assertEquals("Edit › Paste Special › Paste Into", rows.get(0).label());
        assertEquals("menu/edit-paste-special-paste-into", rows.get(0).id());
    }

    @Test
    void duplicateLeafNamesGetUniqueIds() {
        JMenuBar bar = new JMenuBar();
        JMenu a = new JMenu("File");
        a.add(item("Open", "ctrl O"));
        JMenu b = new JMenu("File");
        b.add(item("Open", "ctrl alt O"));
        bar.add(a);
        bar.add(b);

        List<KeybindingTable.NativeRow> rows = NativeShortcutCatalog.collect(bar);
        assertEquals(2, rows.size());
        assertNotEquals(rows.get(0).id(), rows.get(1).id());
    }

    @Test
    void nonLatinMenuTextStillYieldsStableIds() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("ファイル");
        file.add(item("保存", "ctrl S"));
        bar.add(file);

        List<KeybindingTable.NativeRow> rows = NativeShortcutCatalog.collect(bar);
        assertEquals(1, rows.size());
        assertEquals("ファイル › 保存", rows.get(0).label());
        assertTrue(rows.get(0).id().startsWith("menu/"));
        assertEquals(rows.get(0).id(), NativeShortcutCatalog.collect(bar).get(0).id());
    }

    @Test
    void mnemonicMarkersAreStrippedFromLabels() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("&File");
        file.add(item("&Save", "ctrl S"));
        bar.add(file);

        List<KeybindingTable.NativeRow> rows = NativeShortcutCatalog.collect(bar);
        assertEquals("File › Save", rows.get(0).label());
        assertEquals("menu/file-save", rows.get(0).id());
    }

    @Test
    void emptyMenuBarYieldsNoRows() {
        assertTrue(NativeShortcutCatalog.collect(new JMenuBar()).isEmpty());
    }
}
