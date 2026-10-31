package dev.turboism.shell;

import dev.turboism.internal.core.CoreFrameworkUpdates;
import dev.turboism.internal.core.CoreUpdateService;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** Modeless native Swing UI. The service owns all IO; closing this window never closes Cubism. */
final class FrameworkUpdatesWindow implements AutoCloseable {
    private final PluginLocalization i18n;
    private final CoreUpdateService discovery;
    private final CoreFrameworkUpdates updates;
    private final List<Registration> subscriptions = new ArrayList<>();
    private final JLabel version = new JLabel();
    private final JLabel phase = new JLabel();
    private final JTextArea detail = new JTextArea();
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JButton download;
    private final JButton cancel;
    private final JButton install;
    private final JButton check;
    private JDialog dialog;
    private boolean closed;

    FrameworkUpdatesWindow(final PluginLocalization i18n, final CoreUpdateService discovery) {
        this.i18n = i18n;
        this.discovery = discovery;
        this.updates = discovery.frameworkUpdates();
        download = new JButton(text("updates.install.download"));
        cancel = new JButton(text("updates.install.cancel"));
        install = new JButton(text("updates.install.on-exit"));
        check = new JButton(text("updates.check"));
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        progress.setStringPainted(true);
        check.addActionListener(e -> {
            check.setEnabled(false);
            discovery.checkManual().whenComplete((result, failure) -> CoreDialogs.onEdt(() -> {
                if (closed) return;
                check.setEnabled(true);
                render();
            }));
        });
        download.addActionListener(e -> updates.download());
        cancel.addActionListener(e -> updates.cancel());
        install.addActionListener(e -> updates.installOnExit());
    }

    void show() {
        if (closed) return;
        if (dialog == null) {
            dialog = CoreDialogs.create(text("updates.install.title"), 540, 330);
            if (dialog == null) return;
            final JPanel content = new JPanel(new BorderLayout(8, 8));
            content.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));
            final JPanel heading = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
            heading.add(version);
            heading.add(phase);
            heading.add(progress);
            content.add(heading, BorderLayout.NORTH);
            content.add(new JScrollPane(detail), BorderLayout.CENTER);
            final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
            for (JButton button : List.of(check, download, cancel, install)) buttons.add(button);
            content.add(buttons, BorderLayout.SOUTH);
            dialog.setContentPane(content);
            subscriptions.add(discovery.subscribe(s -> CoreDialogs.onEdt(this::render)));
            subscriptions.add(updates.subscribe(s -> CoreDialogs.onEdt(this::render)));
        }
        render();
        CoreDialogs.show(dialog);
    }

    private void render() {
        if (closed) return;
        final var offered = discovery.snapshot();
        final var state = updates.snapshot();
        version.setText(offered.localVersion() + offered.availableIdentity().map(v -> " → " + v).orElse(""));
        phase.setText(text("updates.install.state." + state.phase().name().toLowerCase(java.util.Locale.ROOT)));
        final boolean busy = state.phase() == CoreFrameworkUpdates.Phase.DOWNLOADING;
        progress.setIndeterminate(busy && state.totalBytes() == 0);
        progress.setValue(state.totalBytes() == 0 ? 0
                : (int) Math.min(100, (double) state.verifiedBytes() * 100 / state.totalBytes()));
        progress.setString(state.verifiedBytes() + " / " + state.totalBytes() + " B");
        download.setEnabled(offered.status() == CoreUpdateService.Status.UPDATE_AVAILABLE
                && switch (state.phase()) { case IDLE, FAILED, CANCELLED -> true; default -> false; });
        cancel.setEnabled(busy);
        install.setEnabled(state.phase() == CoreFrameworkUpdates.Phase.STAGED);
        check.setEnabled(!busy && offered.status() != CoreUpdateService.Status.CHECKING);
        final String explanation = state.phase() == CoreFrameworkUpdates.Phase.UNAVAILABLE
                ? text("updates.install.unsupported") : text("updates.install.no-shutdown");
        detail.setText(explanation + (state.detail().isBlank() ? "" : "\n\n" + state.detail()));
    }

    private String text(final String key) { return i18n.text(key); }

    @Override public void close() {
        closed = true;
        for (final Registration subscription : subscriptions) subscription.close();
        subscriptions.clear();
        if (dialog != null) dialog.dispose();
    }
}
