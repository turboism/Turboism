package dev.turboism.adapter.cubism;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

import java.awt.Component;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        return panelResolver.invoke(MAIN_FRAME_WINDOW, mainFrame);
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
        return normalizeExistingProject(file.toPath());
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
            if (peer instanceof JMenu swing && isFileLabel(swing.getText())) {
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

    /** Legacy-compatible, fail-closed extraction from action command, tooltip, or label text. */
    static Path firstExistingPath(final String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            for (String value : pathCandidates(candidate)) {
                final Path path = existingProjectPath(value);
                if (path != null) return path;
            }
        }
        return null;
    }

    private static List<String> pathCandidates(final String value) {
        final ArrayList<String> candidates = new ArrayList<>(5);
        candidates.add(value);
        final int close = value.lastIndexOf(']');
        final int open = close < 0 ? -1 : value.lastIndexOf('[', close);
        if (open >= 0 && close > open + 1) candidates.add(value.substring(open + 1, close));
        addSuffix(candidates, value, '\t');
        addSuffix(candidates, value, '|');
        addSuffix(candidates, value, '\n');
        return candidates;
    }

    private static void addSuffix(final List<String> candidates, final String value, final char separator) {
        final int index = value.lastIndexOf(separator);
        if (index >= 0 && index + 1 < value.length()) candidates.add(value.substring(index + 1));
    }

    static Path existingProjectPath(final String value) {
        final String candidate = sanitizeCandidate(value);
        if (candidate.isEmpty()) return null;
        try {
            return normalizeExistingProject(Path.of(candidate));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String sanitizeCandidate(final String value) {
        String path = Objects.toString(value, "").replace('\u0000', ' ').trim();
        if (path.isEmpty()) return "";
        while (path.length() >= 2 && (path.startsWith("\"") && path.endsWith("\"")
            || path.startsWith("'") && path.endsWith("'"))) {
            path = path.substring(1, path.length() - 1).trim();
        }
        if (path.startsWith("\\\\?\\")) path = path.substring(4);
        return path;
    }

    private static Path normalizeExistingProject(final Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) return null;
        final String name = Objects.toString(normalized.getFileName(), "").toLowerCase(Locale.ROOT);
        return name.endsWith(".cmo3") || name.endsWith(".can3") ? normalized : null;
    }

    static String pathKey(final Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    static String normalizeLabel(final String value) {
        return Objects.toString(value, "")
            .trim()
            .toLowerCase(Locale.ROOT)
            .replace("…", "")
            .replace("...", "")
            .replaceAll("\\(&.\\)$", "")
            .replace("&", "")
            .trim();
    }

    private static boolean isFileLabel(final String value) {
        return FILE_LABELS.contains(normalizeLabel(value));
    }

    static boolean isRecentLabel(final String value) {
        final String label = normalizeLabel(value);
        return RECENT_LABELS.contains(label) || label.contains("recent") || label.contains("最近") || label.contains("최근");
    }
}
