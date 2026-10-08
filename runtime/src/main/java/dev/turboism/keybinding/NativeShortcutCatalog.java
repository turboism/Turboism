package dev.turboism.keybinding;

import java.awt.Component;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;

/**
 * Enumerates the host's native shortcuts by walking the menu bars of every open frame and
 * collecting the {@link JMenuItem#getAccelerator() accelerators} the host itself declares.
 * Reading live menus keeps the catalog correct for whichever Cubism version is running and
 * reflects shortcuts the user already customized inside the host — no per-version key tables
 * are maintained.
 *
 * <p>Row ids derive from the menu path so the same command keeps a stable identity across
 * restarts (within one UI language); labels follow the host's own localized menu text.
 * Commands whose shortcuts are not exposed through menu accelerators (tool keys, canvas
 * gestures) are not discoverable this way — users can still add them through the
 * key-forwarding rows.</p>
 *
 * <p>{@link #scan()} touches Swing components and must run on the EDT.</p>
 */
final class NativeShortcutCatalog {

    private NativeShortcutCatalog() {}

    /** Scans the menu bar of every known frame. Must be called on the EDT. */
    static List<KeybindingTable.NativeRow> scan() {
        final List<KeybindingTable.NativeRow> rows = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (Frame frame : Frame.getFrames()) {
            if (frame instanceof JFrame jframe && jframe.getJMenuBar() != null) {
                collect(jframe.getJMenuBar(), rows, seen);
            }
        }
        return rows;
    }

    /** Collects rows from one menu bar — split out so tests can exercise it headlessly. */
    static List<KeybindingTable.NativeRow> collect(final JMenuBar bar) {
        final List<KeybindingTable.NativeRow> rows = new ArrayList<>();
        collect(bar, rows, new LinkedHashSet<>());
        return rows;
    }

    private static void collect(
            final JMenuBar bar, final List<KeybindingTable.NativeRow> rows, final Set<String> seen) {
        for (Component component : bar.getComponents()) {
            if (component instanceof JMenu menu) {
                walk(menu, new ArrayList<>(), rows, seen);
            }
        }
    }

    private static void walk(
            final JMenu menu,
            final List<String> path,
            final List<KeybindingTable.NativeRow> rows,
            final Set<String> seen) {
        final List<String> deeper = new ArrayList<>(path);
        deeper.add(text(menu));
        for (Component component : menu.getMenuComponents()) {
            if (component instanceof JMenu submenu) {
                walk(submenu, deeper, rows, seen);
            } else if (component instanceof JMenuItem item) {
                final KeyStroke accelerator = item.getAccelerator();
                if (accelerator == null) {
                    continue;
                }
                final List<String> full = new ArrayList<>(deeper);
                full.add(text(item));
                final String label = String.join(" › ", full);
                final String id = uniqueId(full, seen);
                rows.add(new KeybindingTable.NativeRow(
                        id,
                        label,
                        KeyStrokeCodec.display(KeyStrokeCodec.encode(accelerator)),
                        KeybindingTable.State.UNSET,
                        "",
                        false,
                        rows.size()));
            }
        }
    }

    /**
     * Derives a stable row id from the menu path. Readable slugs keep the persisted file
     * inspectable; paths that sanitize to nothing fall back to a content hash. Collisions
     * (two menus sharing a leaf name) get a numeric suffix.
     */
    private static String uniqueId(final List<String> path, final Set<String> seen) {
        final String joined = String.join("/", path);
        String slug = joined.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = Integer.toHexString(joined.hashCode());
        }
        String id = "menu/" + slug;
        int suffix = 2;
        while (seen.contains(id)) {
            id = "menu/" + slug + "-" + suffix++;
        }
        seen.add(id);
        return id;
    }

    /** Menu text minus mnemonic markers; empty labels fall back to an em dash. */
    private static String text(final JMenuItem item) {
        final String raw = item.getText();
        if (raw == null || raw.isBlank()) {
            return "—";
        }
        return raw.replace("&", "").trim();
    }
}
