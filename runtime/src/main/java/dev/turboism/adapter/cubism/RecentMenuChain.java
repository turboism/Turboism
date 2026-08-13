package dev.turboism.adapter.cubism;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

import java.awt.Component;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Shared verified selector chain for the Recent Files menu: app controller → main
 * frame → window → menu bar → menus → Swing peer. Used by the recent-file list,
 * preview capture, and hover-popup host operations so all three agree on the same
 * reviewed host roots.
 */
final class RecentMenuChain {

    static final String PANEL_APP_INSTANCE = "cubism.ui-panel.app-controller.instance";
    static final String PANEL_MAIN_FRAME = "cubism.ui-panel.app-controller.main-frame";
    static final String MAIN_FRAME_WINDOW = "cubism.ui-panel.main-frame.window";
    static final String WINDOW_MENU_BAR = "cubism.ui-panel.window.menu-bar";
    static final String MENU_BAR_MENUS = "cubism.ui-panel.menu-bar.menus";
    static final String MENU_SWING = "cubism.ui-panel.menu.swing";

    static final String PROJECT_APP_INSTANCE = "cubism.app-controller.instance";
    static final String CURRENT_DOCUMENT = "cubism.app-controller.current-document";
    static final String DOCUMENT_FILE_CONTENT = "cubism.document.file-content";
    static final String FILE_CONTENT_FILE = "cubism.file-content.file";

    static final Set<String> PROJECT_ALIASES = Set.of(
        PROJECT_APP_INSTANCE, CURRENT_DOCUMENT, DOCUMENT_FILE_CONTENT, FILE_CONTENT_FILE
    );

    static final Set<String> PANEL_ALIASES = Set.of(
        PANEL_APP_INSTANCE, PANEL_MAIN_FRAME, MAIN_FRAME_WINDOW, WINDOW_MENU_BAR,
        MENU_BAR_MENUS, MENU_SWING
    );

    static final Set<String> FILE_LABELS = Set.of("file", "ファイル", "文件", "파일");
    static final Set<String> RECENT_LABELS = Set.of(
        "recent", "recent files", "open recent", "最近使用したファイル", "最近的文件", "最近使用的文件", "최근 파일"
    );

    private RecentMenuChain() {
    }

    /** Verified window root of the main frame; null when the chain is unavailable. */
    static Object resolveWindow(final VerifiedMemberResolver panelResolver) {
        final Object app = panelResolver.invokeStatic(PANEL_APP_INSTANCE);
        final Object mainFrame = panelResolver.invoke(PANEL_MAIN_FRAME, app);
        final Object window = panelResolver.invoke(MAIN_FRAME_WINDOW, mainFrame);
        return window;
    }

    /** Current project file path via the verified project chain; null when unsaved/unavailable. */
    static Path currentProjectPath(final VerifiedMemberResolver projectResolver) {
        final Object app = projectResolver.invokeStatic(PROJECT_APP_INSTANCE);
        final Object document = projectResolver.invoke(CURRENT_DOCUMENT, app);
        if (document == null) return null;
        final Object content = projectResolver.invoke(DOCUMENT_FILE_CONTENT, document);
        if (content == null) return null;
        final Object raw = projectResolver.invoke(FILE_CONTENT_FILE, content);
        if (!(raw instanceof File file)) return null;
        return normalizeExisting(file.toPath());
    }

    /** Paths of the existing files listed in the Recent menu, in menu order. */
    static List<Path> recentPaths(final VerifiedMemberResolver panelResolver, final Object window) {
        final JMenu recent = recentMenu(panelResolver, window);
        return recent == null ? List.of() : pathsFrom(recent);
    }

    /** The Recent submenu (Swing peer) of the host File menu, or null. */
    static JMenu recentMenu(final VerifiedMemberResolver panelResolver, final Object window) {
        final Object menuBar = panelResolver.invoke(WINDOW_MENU_BAR, window);
        final Object rawMenus = panelResolver.invoke(MENU_BAR_MENUS, menuBar);
        if (!(rawMenus instanceof Iterable<?> menus)) return null;
        for (Object menu : menus) {
            final Object peer = panelResolver.invoke(MENU_SWING, menu);
            if (peer instanceof JMenu swing && FILE_LABELS.contains(normalizeLabel(swing.getText()))) {
                final JMenu recent = findRecentMenu(swing);
                if (recent != null) return recent;
            }
        }
        return null;
    }

    /** The Recent submenu of a File menu, or null. */
    static JMenu findRecentMenu(final JMenu fileMenu) {
        for (Component component : fileMenu.getMenuComponents()) {
            if (component instanceof JMenu menu && isRecentLabel(menu.getText())) return menu;
        }
        return null;
    }

    /** Existing-file paths of the Recent menu items, deduplicated, in menu order. */
    static List<Path> pathsFrom(final JMenu recentMenu) {
        final LinkedHashMap<String, Path> unique = new LinkedHashMap<>();
        for (Component component : recentMenu.getMenuComponents()) {
            if (!(component instanceof JMenuItem item)) continue;
            final Path path = firstExistingPath(item.getActionCommand(), item.getToolTipText(), item.getText());
            if (path != null) unique.putIfAbsent(pathKey(path), path);
        }
        return List.copyOf(unique.values());
    }

    static Path firstExistingPath(final String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            for (String value : List.of(candidate, suffix(candidate, '|'), suffix(candidate, '\n'))) {
                final Path path = pathFrom(value);
                if (path != null) return path;
            }
        }
        return null;
    }

    private static String suffix(final String value, final char separator) {
        final int index = value.lastIndexOf(separator);
        return index < 0 ? value : value.substring(index + 1).trim();
    }

    private static Path pathFrom(final String value) {
        try {
            return normalizeExisting(Path.of(value.trim()));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Path normalizeExisting(final Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        return Files.isRegularFile(normalized) ? normalized : null;
    }

    static String pathKey(final Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    static String normalizeLabel(final String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT).replace("…", "").replace("...", "");
    }

    static boolean isRecentLabel(final String value) {
        final String label = normalizeLabel(value);
        return RECENT_LABELS.contains(label) || label.contains("recent") || label.contains("最近") || label.contains("최근");
    }
}
