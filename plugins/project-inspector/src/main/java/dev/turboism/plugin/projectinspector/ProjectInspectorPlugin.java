package dev.turboism.plugin.projectinspector;

import dev.turboism.sdk.hostread.AsyncHostReadError;
import dev.turboism.sdk.hostread.AsyncHostReadHandle;
import dev.turboism.sdk.hostread.AsyncHostReadIntent;
import dev.turboism.sdk.hostread.AsyncHostReadRequest;
import dev.turboism.sdk.hostread.AsyncHostReadResult;
import dev.turboism.sdk.hostread.AsyncHostReadSubmission;
import dev.turboism.sdk.hostread.AsyncHostReadSubmissionStatus;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
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
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** First localization + runtime-owned async host-read reference consumer. */
public final class ProjectInspectorPlugin implements TurboismPlugin {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    static int windowCloseOperation() {
        return WindowConstants.HIDE_ON_CLOSE;
    }

    private final Object lifecycleLock = new Object();
    private final UiAccess ui;
    private final AtomicReference<InspectorView> window = new AtomicReference<>();
    private final AtomicReference<AsyncHostReadHandle> currentRead = new AtomicReference<>();
    private PluginContext context;
    private PluginLocalization localization;
    private boolean initialized;
    private boolean enabled;
    private long generation;

    public ProjectInspectorPlugin() {
        this(new SwingUiAccess());
    }

    ProjectInspectorPlugin(final UiAccess ui) {
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.localization = context.localization();
        context.hostReads();
        synchronized (lifecycleLock) {
            initialized = true;
        }
        context.disposableScope().register(this::cancelCurrentRead);
        context.disposableScope().register(this::disposeWindow);
        context.logger().info("Project Inspector initialized");
    }

    @Override
    public void enable() {
        final long enableGeneration;
        synchronized (lifecycleLock) {
            if (!initialized) {
                throw new IllegalStateException("Project Inspector must be initialized before enable.");
            }
            enabled = true;
            enableGeneration = ++generation;
        }
        if (ui.isHeadless()) {
            context.logger().warn("Project Inspector cannot open because the JVM is headless");
            return;
        }
        ui.invokeLater(() -> showWindow(enableGeneration));
    }

    @Override
    public void disable() {
        deactivate();
    }

    @Override
    public void shutdown() {
        deactivate();
    }

    private void deactivate() {
        synchronized (lifecycleLock) {
            if (!initialized) {
                return;
            }
            enabled = false;
            generation++;
        }
        cancelCurrentRead();
        disposeWindow();
    }

    private void showWindow(final long enableGeneration) {
        if (!isEnabledGeneration(enableGeneration)) {
            return;
        }
        final InspectorView existing = window.get();
        if (existing != null) {
            if (!isCurrent(enableGeneration, existing)) {
                return;
            }
            existing.showAndFront();
            refresh();
            return;
        }

        final InspectorView created = ui.create(localization, this::refresh);
        synchronized (lifecycleLock) {
            if (!enabled || generation != enableGeneration || !window.compareAndSet(null, created)) {
                created.dispose();
                return;
            }
        }
        created.showAndFront();
        refresh();
    }

    private void refresh() {
        final long requestGeneration;
        final InspectorView view;
        synchronized (lifecycleLock) {
            if (!enabled || (view = window.get()) == null) {
                return;
            }
            requestGeneration = ++generation;
        }
        view.showReading();
        final AsyncHostReadSubmission submission = context.hostReads().submit(
            new AsyncHostReadRequest(
                AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT,
                READ_TIMEOUT
            )
        );
        if (submission.status() == AsyncHostReadSubmissionStatus.REJECTED) {
            final AsyncHostReadError error = submission.error().orElseThrow();
            context.logger().warn("Project Inspector refresh rejected safely: " + error.code().name());
            ui.invokeLater(() -> applyFailure(requestGeneration, error));
            return;
        }
        final AsyncHostReadHandle handle = submission.handle().orElseThrow();
        synchronized (lifecycleLock) {
            if (!enabled || generation != requestGeneration || window.get() != view) {
                handle.cancel();
                return;
            }
            currentRead.set(handle);
        }
        handle.completion().thenAccept(result -> ui.invokeLater(
            () -> applyResult(requestGeneration, view, handle, result)
        ));
    }

    private void applyResult(
        final long requestGeneration,
        final InspectorView expectedView,
        final AsyncHostReadHandle handle,
        final AsyncHostReadResult result
    ) {
        if (!isCurrent(requestGeneration, expectedView)) {
            return;
        }
        currentRead.compareAndSet(handle, null);
        if (result.value().isPresent()) {
            applySnapshot(expectedView, (ProjectWorkspaceSnapshot) result.value().orElseThrow());
        } else {
            applyFailure(requestGeneration, expectedView, result.error().orElseThrow());
        }
    }

    private void applySnapshot(
        final InspectorView view,
        final ProjectWorkspaceSnapshot snapshot
    ) {
        view.showSnapshot(snapshot, Instant.now());
        context.logger().info(
            "Inspector refresh: projectPresent=" + snapshot.project().isPresent()
                + ", workspacePresent=" + snapshot.workspace().isPresent()
                + ", documents=" + snapshot.project().map(value -> value.documents().size()).orElse(0)
        );
    }

    private void applyFailure(
        final long requestGeneration,
        final AsyncHostReadError error
    ) {
        final InspectorView view = window.get();
        if (view != null) {
            applyFailure(requestGeneration, view, error);
        }
    }

    private void applyFailure(
        final long requestGeneration,
        final InspectorView expectedView,
        final AsyncHostReadError error
    ) {
        if (!isCurrent(requestGeneration, expectedView)) {
            return;
        }
        expectedView.showUnavailable(Instant.now());
        context.logger().warn("Project Inspector refresh failed safely: " + error.code().name());
    }

    private boolean isCurrent(
        final long expectedGeneration,
        final InspectorView expectedView
    ) {
        synchronized (lifecycleLock) {
            return enabled && generation == expectedGeneration && window.get() == expectedView;
        }
    }

    private boolean isEnabledGeneration(final long expectedGeneration) {
        synchronized (lifecycleLock) {
            return enabled && generation == expectedGeneration;
        }
    }

    private void cancelCurrentRead() {
        final AsyncHostReadHandle handle = currentRead.getAndSet(null);
        if (handle != null) {
            handle.cancel();
        }
    }

    private void disposeWindow() {
        final Runnable dispose = () -> {
            final InspectorView view = window.getAndSet(null);
            if (view != null) {
                view.dispose();
            }
        };
        try {
            ui.invokeAndWait(dispose);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw disposalFailure(exception);
        } catch (InvocationTargetException | RuntimeException exception) {
            throw disposalFailure(exception);
        }
    }

    private IllegalStateException disposalFailure(final Exception cause) {
        context.logger().error("Project Inspector EDT disposal failed safely.");
        return new IllegalStateException("Project Inspector window disposal failed.", cause);
    }

    interface UiAccess {
        boolean isHeadless();
        void invokeLater(Runnable action);
        void invokeAndWait(Runnable action) throws InterruptedException, InvocationTargetException;
        InspectorView create(PluginLocalization localization, Runnable refreshAction);
    }

    interface InspectorView {
        void showAndFront();
        void showReading();
        void showSnapshot(ProjectWorkspaceSnapshot snapshot, Instant refreshedAt);
        void showUnavailable(Instant refreshedAt);
        void dispose();
    }

    private static final class SwingUiAccess implements UiAccess {
        @Override
        public boolean isHeadless() {
            return GraphicsEnvironment.isHeadless();
        }

        @Override
        public void invokeLater(final Runnable action) {
            SwingUtilities.invokeLater(action);
        }

        @Override
        public void invokeAndWait(final Runnable action)
            throws InterruptedException, InvocationTargetException {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeAndWait(action);
            }
        }

        @Override
        public InspectorView create(
            final PluginLocalization localization,
            final Runnable refreshAction
        ) {
            return new SwingInspectorView(localization, refreshAction);
        }
    }

    private static final class SwingInspectorView implements InspectorView {
        private final PluginLocalization localization;
        private final JFrame frame;
        private final JLabel statusValue;
        private final JLabel projectValue;
        private final JLabel documentsValue;
        private final JLabel workspaceValue;
        private final JLabel refreshedValue;

        private SwingInspectorView(
            final PluginLocalization localization,
            final Runnable refreshAction
        ) {
            this.localization = localization;
            frame = TurboismWindowFactory.frame(localization.text("window.title"));
            frame.setDefaultCloseOperation(windowCloseOperation());
            frame.setLayout(new BorderLayout(8, 8));

            final JPanel values = new JPanel(new GridLayout(0, 2, 8, 6));
            values.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
            statusValue = addRow(values, "field.host_read", "value.starting");
            projectValue = addRow(values, "field.active_document", "value.none");
            documentsValue = addRow(values, "field.documents", "value.none");
            workspaceValue = addRow(values, "field.layout_workspace", "value.none");
            refreshedValue = addRow(values, "field.last_refresh", "value.none");

            final JButton refresh = new JButton(localization.text("action.refresh"));
            refresh.addActionListener(ignored -> refreshAction.run());
            final JPanel actions = new JPanel(new BorderLayout());
            actions.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
            actions.add(refresh, BorderLayout.EAST);

            frame.add(values, BorderLayout.CENTER);
            frame.add(actions, BorderLayout.SOUTH);
            frame.pack();
            frame.setMinimumSize(frame.getSize());
            frame.setLocationByPlatform(true);
        }

        @Override
        public void showAndFront() {
            frame.setVisible(true);
            frame.toFront();
        }

        @Override
        public void showReading() {
            statusValue.setText(localization.text("status.reading"));
        }

        @Override
        public void showSnapshot(
            final ProjectWorkspaceSnapshot snapshot,
            final Instant refreshedAt
        ) {
            statusValue.setText(localization.text("status.available"));
            projectValue.setText(snapshot.project()
                .map(value -> value.name())
                .orElse(localization.text("status.no_project_open")));
            documentsValue.setText(snapshot.project()
                .map(value -> Integer.toString(value.documents().size()))
                .orElse("0"));
            workspaceValue.setText(snapshot.workspace()
                .map(value -> value.displayName())
                .orElse(localization.text("status.unavailable")));
            refreshedValue.setText(refreshedAt.toString());
        }

        @Override
        public void showUnavailable(final Instant refreshedAt) {
            statusValue.setText(localization.text("status.unavailable"));
            final String none = localization.text("value.none");
            projectValue.setText(none);
            documentsValue.setText(none);
            workspaceValue.setText(none);
            refreshedValue.setText(refreshedAt.toString());
        }

        @Override
        public void dispose() {
            frame.dispose();
        }

        private JLabel addRow(
            final JPanel panel,
            final String labelKey,
            final String initialValueKey
        ) {
            panel.add(new JLabel(localization.text(labelKey) + ":"));
            final JLabel value = new JLabel(localization.text(initialValueKey));
            panel.add(value);
            return value;
        }
    }
}
