package dev.turboism.adapter.cubism;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JMenu;
import javax.swing.JMenuItem;

/**
 * Test-only synthetic host graph + verified resolvers for the recent-preview slice.
 * Mirrors the reviewed project-workspace and embedded-panel selector chains.
 */
public final class RecentPreviewHostFixture {

    private RecentPreviewHostFixture() {}

    /** Project-workspace slice resolver: reviewed version, project slice id + capability. */
    public static VerifiedMemberResolver projectResolver(final String version, final ClassLoader loader) {
        final String owner = ProjectHost.class.getName().replace('.', '/');
        final String viewContextOwner = ViewContextHost.class.getName().replace('.', '/');
        return TestVerifiedResolvers.create(
                version,
                "adapter.project-workspace.readonly",
                java.util.Set.of("cubism.project.read"),
                List.of(
                        StaticSelector.staticMethod(
                                "cubism.app-controller.instance",
                                owner,
                                "instance",
                                "()Ljava/lang/Object;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "cubism.app-controller.current-document",
                                owner,
                                "currentDocument",
                                "()Ljava/lang/Object;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.field(
                                "cubism.app-controller.view-context-history",
                                owner,
                                "viewContextHistory",
                                "Ljava/util/ArrayList;",
                                0x0012),
                        StaticSelector.method(
                                "cubism.view-context.document",
                                viewContextOwner,
                                "getDoc",
                                "()Ljava/lang/Object;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "cubism.document.file-content",
                                owner,
                                "fileContent",
                                "()Ljava/lang/Object;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "cubism.file-content.file",
                                owner,
                                "file",
                                "()Ljava/lang/Object;",
                                StaticSelector.ACCESS_PUBLIC)),
                loader);
    }

    /** Embedded-panel slice resolver: reviewed version, panel slice id + capability. */
    public static VerifiedMemberResolver panelResolver(final String version, final ClassLoader loader) {
        final String owner = PanelHost.class.getName().replace('.', '/');
        return TestVerifiedResolvers.create(
                version,
                "adapter.editor-ui.embedded-panel",
                java.util.Set.of("cubism.editor-ui.embedded-panel"),
                List.of(
                        StaticSelector.staticMethod(
                                "cubism.ui-panel.app-controller.instance",
                                owner,
                                "instance",
                                "()Ljava/lang/Object;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "cubism.ui-panel.app-controller.main-frame",
                                owner,
                                "mainFrame",
                                "()Ljava/lang/Object;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "cubism.ui-panel.main-frame.window",
                                owner,
                                "window",
                                "()Ljava/lang/Object;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "cubism.ui-panel.window.menu-bar",
                                owner,
                                "menuBar",
                                "()Ljava/lang/Object;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "cubism.ui-panel.menu-bar.menus",
                                owner,
                                "menus",
                                "()Ljava/lang/Object;",
                                StaticSelector.ACCESS_PUBLIC),
                        StaticSelector.method(
                                "cubism.ui-panel.menu.swing",
                                owner,
                                "swing",
                                "()Ljava/lang/Object;",
                                StaticSelector.ACCESS_PUBLIC)),
                loader);
    }

    public static final class ProjectHost {
        private static Object instanceRoot;
        private final Object document;
        private final Object content;
        private final Object file;
        private final java.util.ArrayList<Object> viewContextHistory;

        private ProjectHost(
                final Object document,
                final Object content,
                final Object file,
                final java.util.ArrayList<Object> viewContextHistory) {
            this.document = document;
            this.content = content;
            this.file = file;
            this.viewContextHistory = viewContextHistory;
        }

        public static void setRoot(final ProjectHost root) {
            instanceRoot = root;
        }

        public static Object instance() {
            return instanceRoot;
        }

        public Object currentDocument() {
            return document;
        }

        public Object fileContent() {
            return content;
        }

        public Object file() {
            return file;
        }
    }

    public static final class PanelHost {
        private static Object instanceRoot;
        private final Object mainFrame;
        private final Object window;
        private final Object menuBar;
        private final Object menus;
        private final Object swing;

        private PanelHost(
                final Object mainFrame,
                final Object window,
                final Object menuBar,
                final Object menus,
                final Object swing) {
            this.mainFrame = mainFrame;
            this.window = window;
            this.menuBar = menuBar;
            this.menus = menus;
            this.swing = swing;
        }

        public static void setRoot(final PanelHost root) {
            instanceRoot = root;
        }

        public static Object instance() {
            return instanceRoot;
        }

        public Object mainFrame() {
            return mainFrame;
        }

        public Object window() {
            return window;
        }

        public Object menuBar() {
            return menuBar;
        }

        public Object menus() {
            return menus;
        }

        public Object swing() {
            return swing;
        }
    }

    /** Stand-in for a host view context: wraps the document its view edits. */
    public static final class ViewContextHost {
        private final Object document;

        public ViewContextHost(final Object document) {
            this.document = document;
        }

        public Object getDoc() {
            return document;
        }
    }

    public static ProjectHost projectChain(final Path current) {
        return projectChain(current, true);
    }

    /**
     * Current-document chain; {@code viewFocused=false} mirrors a host whose
     * current-document member reads null because focus left the document view
     * (e.g. a title-bar click), while the view-context history still names the
     * document's last activated view.
     */
    public static ProjectHost projectChain(final Path current, final boolean viewFocused) {
        final ProjectHost file = new ProjectHost(null, null, current.toFile(), null);
        final ProjectHost document = new ProjectHost(null, file, null, null);
        final java.util.ArrayList<Object> history = new java.util.ArrayList<>();
        history.add(new ViewContextHost(document));
        return new ProjectHost(viewFocused ? document : null, null, null, history);
    }

    /** Root with a null current document and an empty view-context history. */
    public static ProjectHost emptyProjectChain() {
        return new ProjectHost(null, null, null, new java.util.ArrayList<>());
    }

    public static PanelHost panelChain(final JMenu recentMenu) {
        final JMenu fileMenu = new JMenu("File");
        fileMenu.add(recentMenu);
        final PanelHost menu = new PanelHost(null, null, null, null, fileMenu);
        final PanelHost menuBar = new PanelHost(null, null, null, List.of(menu), null);
        final PanelHost window = new PanelHost(null, null, menuBar, null, null);
        final PanelHost frame = new PanelHost(null, window, null, null, null);
        return new PanelHost(frame, null, null, null, null);
    }

    public static JMenu recentMenu(final Path... paths) {
        final JMenu recent = new JMenu("Recent Files");
        for (Path path : paths) {
            final JMenuItem item = new JMenuItem();
            item.setActionCommand(path.toString());
            recent.add(item);
        }
        return recent;
    }
}
