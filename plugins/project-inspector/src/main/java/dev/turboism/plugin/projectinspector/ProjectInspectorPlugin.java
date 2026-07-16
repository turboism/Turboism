package dev.turboism.plugin.projectinspector;

import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

/** First visible Turboism 0.1 vertical-slice plugin. */
public final class ProjectInspectorPlugin implements TurboismPlugin {

    private final AtomicReference<JFrame> window = new AtomicReference<>();
    private PluginContext context;
    private ExecutorService refreshExecutor;
    private JLabel statusValue;
    private JLabel projectValue;
    private JLabel documentsValue;
    private JLabel workspaceValue;
    private JLabel refreshedValue;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.refreshExecutor = Executors.newSingleThreadExecutor(new InspectorThreadFactory());
        context.disposableScope().register(() -> refreshExecutor.shutdownNow());
        context.disposableScope().register(this::disposeWindow);
        context.logger().info("Project Inspector initialized");
    }

    @Override
    public void enable() {
        if (GraphicsEnvironment.isHeadless()) {
            context.logger().warn("Project Inspector cannot open because the JVM is headless");
            return;
        }
        SwingUtilities.invokeLater(this::showWindow);
    }

    @Override
    public void disable() {
        disposeWindow();
    }

    @Override
    public void shutdown() {
        disposeWindow();
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
        }
    }

    private void showWindow() {
        final JFrame existing = window.get();
        if (existing != null) {
            existing.setVisible(true);
            existing.toFront();
            refresh();
            return;
        }

        final JFrame frame = new JFrame("Turboism Project Inspector");
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.setLayout(new BorderLayout(8, 8));

        final JPanel values = new JPanel(new GridLayout(0, 2, 8, 6));
        values.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        statusValue = addRow(values, "Host read", "Starting…");
        projectValue = addRow(values, "Active document", "—");
        documentsValue = addRow(values, "Documents", "—");
        workspaceValue = addRow(values, "Layout workspace", "—");
        refreshedValue = addRow(values, "Last refresh", "—");

        final JButton refresh = new JButton("Refresh");
        refresh.addActionListener(ignored -> refresh());
        final JPanel actions = new JPanel(new BorderLayout());
        actions.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
        actions.add(refresh, BorderLayout.EAST);

        frame.add(values, BorderLayout.CENTER);
        frame.add(actions, BorderLayout.SOUTH);
        frame.pack();
        frame.setMinimumSize(frame.getSize());
        frame.setLocationByPlatform(true);

        if (!window.compareAndSet(null, frame)) {
            frame.dispose();
            return;
        }
        frame.setVisible(true);
        refresh();
    }

    private static JLabel addRow(final JPanel panel, final String name, final String initialValue) {
        panel.add(new JLabel(name + ":"));
        final JLabel value = new JLabel(initialValue);
        panel.add(value);
        return value;
    }

    private void refresh() {
        final ExecutorService executor = refreshExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        setStatus("Reading…");
        executor.execute(() -> {
            try {
                final Optional<ProjectSnapshot> project = context.cubismRead().activeProject();
                final Optional<WorkspaceSnapshot> workspace = context.cubismRead().workspace();
                SwingUtilities.invokeLater(() -> applySnapshot(project, workspace));
            } catch (RuntimeException exception) {
                context.logger().error("Project Inspector refresh failed", exception);
                SwingUtilities.invokeLater(() -> applyFailure(exception));
            }
        });
    }

    private void applySnapshot(
        final Optional<ProjectSnapshot> project,
        final Optional<WorkspaceSnapshot> workspace
    ) {
        setStatus("Available");
        projectValue.setText(project.map(ProjectSnapshot::name).orElse("No project open"));
        documentsValue.setText(project.map(value -> Integer.toString(value.documents().size())).orElse("0"));
        workspaceValue.setText(workspace
            .map(WorkspaceSnapshot::displayName)
            .orElse("Unavailable"));
        refreshedValue.setText(Instant.now().toString());
        context.logger().info(
            "Inspector refresh: project=" + project.map(ProjectSnapshot::name).orElse("<none>")
                + ", documents=" + project.map(value -> value.documents().size()).orElse(0)
                + ", layoutWorkspace=" + workspace.map(WorkspaceSnapshot::displayName).orElse("<none>")
        );
    }

    private void applyFailure(final RuntimeException exception) {
        setStatus("Unavailable: " + exception.getClass().getSimpleName());
        projectValue.setText("—");
        documentsValue.setText("—");
        workspaceValue.setText("—");
        refreshedValue.setText(Instant.now().toString());
    }

    private void setStatus(final String value) {
        if (statusValue != null) {
            statusValue.setText(value);
        }
    }

    private void disposeWindow() {
        final Runnable dispose = () -> {
            final JFrame frame = window.getAndSet(null);
            if (frame != null) {
                frame.dispose();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            dispose.run();
        } else {
            SwingUtilities.invokeLater(dispose);
        }
    }

    private static final class InspectorThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(runnable, "turboism-project-inspector-refresh");
            thread.setDaemon(true);
            return thread;
        }
    }
}
