package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;

import java.awt.AWTException;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
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
                // Poll the Swing tree for the history tool strip over ~18s:
                // Cubism may rebuild the main-frame layout after fixture load.
                String last = "ABSENT";
                for (int poll = 1; poll <= 6; poll++) {
                    Thread.sleep(3_000L);
                    last = findToolStrip();
                    append(evidence, "poll" + poll + "=" + last + "\n");
                    if (!last.equals("ABSENT")) {
                        break;
                    }
                }
                captureScreen(evidence.getParent().resolve("history-baseline-screen.png"));
                append(evidence, "toolstrip=" + last + "\n");
                append(evidence, "structure=" + dumpStructure() + "\n");
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

    private static void captureScreen(final Path target) {
        try {
            final Rectangle bounds = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            final BufferedImage image = new Robot().createScreenCapture(bounds);
            ImageIO.write(image, "png", target.toFile());
        } catch (AWTException | java.io.IOException failure) {
            // Non-fatal: the probe still reports PASS from the SDK path.
        }
    }

    private static String findToolStrip() {
        try {
            final String wanted = "turboism:dev.turboism.plugin.historypanel:history.toolstrip";
            final java.util.List<String> found = new java.util.ArrayList<>();
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                scan(window, wanted, found);
            }
            return found.isEmpty() ? "ABSENT" : String.join(",", found);
        } catch (Throwable failure) {
            return "ERROR:" + failure.getClass().getSimpleName();
        }
    }

    private static String dumpStructure() {
        try {
            final java.util.List<String> hits = new java.util.ArrayList<>();
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                scanNames(window, hits);
            }
            return hits.isEmpty() ? "NO_CANVAS" : String.join("|", hits);
        } catch (Throwable failure) {
            return "ERROR:" + failure.getClass().getSimpleName();
        }
    }

    private static void scanNames(final Container container, final java.util.List<String> hits) {
        final String name = container.getClass().getName();
        if (name.contains("GLJPanel") || name.contains("CSimplePane") || name.contains("CEMainFrame")) {
            hits.add(name);
        }
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                scanNames(nested, hits);
            }
        }
    }

    private static void scan(final Container container, final String wanted, final java.util.List<String> found) {
        for (Component child : container.getComponents()) {
            if (wanted.equals(child.getName())) {
                found.add(child.getClass().getName() + "@" + child.getBounds());
            }
            if (child instanceof Container nested) {
                scan(nested, wanted, found);
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
