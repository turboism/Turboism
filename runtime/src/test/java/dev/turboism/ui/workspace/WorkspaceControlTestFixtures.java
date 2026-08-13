package dev.turboism.ui.workspace;

import dev.turboism.mapping.verification.StaticSelector;

import java.util.List;

public final class WorkspaceControlTestFixtures {
    static {
        App.instance = new App(new Frame(new Dock(new Workspace(new Id("modeling"), "Modeling"))));
    }

    public static List<StaticSelector> selectors() {
        return List.of(
            StaticSelector.classSelector("workspace.app.class", name(App.class)),
            StaticSelector.staticMethod("workspace.app.instance", name(App.class), "instance", "()L" + name(App.class) + ";", 1),
            StaticSelector.method("workspace.app.main-frame", name(App.class), "mainFrame", "()L" + name(Frame.class) + ";", 1),
            StaticSelector.method("workspace.main-frame.dock", name(Frame.class), "dock", "()L" + name(Dock.class) + ";", 1),
            StaticSelector.method("workspace.dock.current", name(Dock.class), "current", "()L" + name(Workspace.class) + ";", 1),
            StaticSelector.method("workspace.dock.preset", name(Dock.class), "preset", "()Ljava/util/List;", 1),
            StaticSelector.method("workspace.dock.custom", name(Dock.class), "custom", "()Ljava/util/List;", 1),
            StaticSelector.method("workspace.workspace.id", name(Workspace.class), "id", "()L" + name(Id.class) + ";", 1),
            StaticSelector.method("workspace.workspace.name", name(Workspace.class), "name", "()Ljava/lang/String;", 1),
            StaticSelector.method("workspace.id.value", name(Id.class), "value", "()Ljava/lang/String;", 1),
            StaticSelector.method("workspace.dock.change", name(Dock.class), "change", "(L" + name(Id.class) + ";)V", 1),
            StaticSelector.method("workspace.dock.update-default", name(Dock.class), "update", "()V", 1),
            StaticSelector.method("workspace.dock.reset-default", name(Dock.class), "reset", "()V", 1)
        );
    }

    public static ClassLoader classLoader() { return App.class.getClassLoader(); }
    private static String name(Class<?> type) { return type.getName().replace('.', '/'); }

    public static final class App {
        static App instance;
        final Frame frame;
        App(Frame frame) { this.frame = frame; }
        public static App instance() { return instance; }
        public Frame mainFrame() { return frame; }
    }
    public record Frame(Dock dock) { }
    public static final class Dock {
        private Workspace current;
        Dock(Workspace current) { this.current = current; }
        public Workspace current() { return current; }
        public List<Workspace> preset() { return List.of(current); }
        public List<Workspace> custom() { return List.of(); }
        public void change(Id id) { }
        public void update() { }
        public void reset() { }
    }
    public record Workspace(Id id, String name) { }
    public record Id(String value) { }

    private WorkspaceControlTestFixtures() { }
}
