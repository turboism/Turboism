package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Manual-test-only probe: contributes a panel and floats it via the SDK API. */
public final class WindowsHistoryFloatProbe implements CubismPlugin {

    private static final String PANEL_ID = "history.float.probe";

    private PluginContext context;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
    }

    @Override
    public void enable() {
        final Path evidence = context.paths().logsDir().resolve("history-float.txt");
        try {
            Files.createDirectories(evidence.getParent());
            if (Files.exists(evidence)) {
                throw new IllegalStateException("History float evidence already exists");
            }
            write(evidence, "status=RUNNING at=" + Instant.now() + "\n");
            final Registration panel = context.uiHost().contributeEmbeddedPanel(
                new EmbeddedPanelContribution(
                    PANEL_ID,
                    "History Float Probe",
                    "side",
                    90,
                    PanelView.column(PanelView.text("Float probe panel"))
                )
            );
            try {
                context.uiHost().activateEmbeddedPanelFloating(EmbeddedPanelId.of(PANEL_ID));
                write(evidence, "status=PASS\npanelId=" + PANEL_ID
                    + "\nfloating=activated via SDK activateEmbeddedPanelFloating\n"
                    + "at=" + Instant.now() + "\n");
                Thread.sleep(3_000L);
            } finally {
                try {
                    panel.close();
                    append(evidence, "cleanup=closed\n");
                } catch (Exception cleanupFailure) {
                    append(evidence, "cleanup=FAIL:" + cleanupFailure.getClass().getSimpleName()
                        + ":" + cleanupFailure.getMessage() + "\n");
                }
            }
        } catch (Exception failure) {
            context.logger().error("History float probe failed", failure);
            try {
                write(evidence, "status=FAIL\nerror=" + failure.getClass().getName()
                    + ": " + failure.getMessage() + "\n");
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void disable() {
    }

    private static void append(final Path artifact, final String value) throws Exception {
        Files.writeString(
            artifact,
            value,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    private static void write(final Path artifact, final String value) throws Exception {
        Files.writeString(
            artifact,
            value,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }
}
