package dev.turboism.userfile;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Real modal Swing windows on an isolated display; no Cubism acceptance implied. */
@EnabledIfEnvironmentVariable(named = "TURBOISM_TEST_SWING_CHOOSER", matches = "1")
class SwingUserFileGrantSourceDisplayTest {
    @TempDir
    Path temporary;

    @Test
    void actualWindowGrantsSelectedFile() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        Path input = Files.write(temporary.resolve("cube.glb"), new byte[] {1, 2, 3});
        try (var source = new SwingUserFileGrantSource()) {
            var result = source.request(request()).toCompletableFuture();
            JFileChooser chooser = awaitChooser();
            SwingUtilities.invokeAndWait(() -> {
                chooser.setCurrentDirectory(temporary.toFile());
                chooser.setSelectedFile(input.toFile());
            });
            snapshot(chooser, "selected.png");
            SwingUtilities.invokeAndWait(chooser::approveSelection);
            assertEquals(new UserFileGrantSource.Selected(input), result.get(10, TimeUnit.SECONDS));
            awaitHidden(chooser);
        }
    }

    @Test
    void actualWindowCancelCreatesNoGrant() throws Exception {
        try (var source = new SwingUserFileGrantSource()) {
            var result = source.request(request()).toCompletableFuture();
            JFileChooser chooser = awaitChooser();
            SwingUtilities.invokeAndWait(chooser::cancelSelection);
            assertEquals(UserFileGrantSource.Decision.canceled(), result.get(10, TimeUnit.SECONDS));
            awaitHidden(chooser);
        }
    }

    @Test
    void closingSourceDismissesActualWindow() throws Exception {
        try (var source = new SwingUserFileGrantSource()) {
            var result = source.request(request()).toCompletableFuture();
            JFileChooser chooser = awaitChooser();
            source.close(); // Called off EDT, as during runtime teardown.
            assertEquals(UserFileGrantSource.Decision.unavailable(), result.get(10, TimeUnit.SECONDS));
            awaitHidden(chooser);
        }
    }

    private static UserFileRequest request() {
        return new UserFileRequest(
                "display-test",
                "GLB import chooser acceptance",
                List.of("glb"),
                UserFileMode.READ,
                UserFileLifetime.ONE_OPERATION);
    }

    private static JFileChooser awaitChooser() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var found = new AtomicReference<JFileChooser>();
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    if (window.isShowing()) {
                        JFileChooser chooser = find(window);
                        if (chooser != null && chooser.isShowing()) found.set(chooser);
                    }
                }
            });
            if (found.get() != null) return found.get();
            Thread.sleep(25);
        }
        throw new AssertionError("No visible JFileChooser");
    }

    private static JFileChooser find(Component component) {
        if (component instanceof JFileChooser chooser) return chooser;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JFileChooser found = find(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void awaitHidden(JFileChooser chooser) throws Exception {
        SwingUtilities.invokeAndWait(() -> assertFalse(chooser.isShowing()));
    }

    private static void snapshot(JFileChooser chooser, String name) throws Exception {
        String output = System.getenv("TURBOISM_TEST_SWING_EVIDENCE");
        if (output == null) return;
        var bounds = new AtomicReference<java.awt.Rectangle>();
        SwingUtilities.invokeAndWait(() -> {
            var window = SwingUtilities.getWindowAncestor(chooser);
            bounds.set(new java.awt.Rectangle(window.getLocationOnScreen(), window.getSize()));
        });
        new Robot().waitForIdle();
        ImageIO.write(
                new Robot().createScreenCapture(bounds.get()),
                "png",
                Path.of(output, name).toFile());
    }
}
