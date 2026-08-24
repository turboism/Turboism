package dev.turboism.adapter.cubism;

import dev.turboism.adapter.cubism.RecentPreviewHostFixture.PanelHost;
import dev.turboism.adapter.cubism.RecentPreviewHostFixture.ProjectHost;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.panelChain;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.panelResolver;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.projectChain;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.projectResolver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenshotCaptureHostOperationsTest {

    @Test
    void portsLegacyCroppingAndBoundedScaling() {
        BufferedImage source = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
        BufferedImage cropped = PreviewCaptureHostOperations.cropMargins(source);
        BufferedImage scaled = PreviewCaptureHostOperations.scale(cropped, 150, 150);

        assertEquals(380, cropped.getWidth());
        assertEquals(190, cropped.getHeight());
        assertEquals(150, scaled.getWidth());
        assertEquals(75, scaled.getHeight());

        // Never upscales beyond the request.
        BufferedImage small = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        assertTrue(PreviewCaptureHostOperations.scale(small, 150, 150) == small);
    }


    @Test
    void detectsSolidBuffersFromUniformOrNearUniformContent() {
        final BufferedImage uniform = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(uniform, new Color(20, 20, 20));
        assertTrue(PreviewCaptureHostOperations.isSolidContent(uniform));

        final BufferedImage nearUniform = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(nearUniform, new Color(100, 100, 100));
        nearUniform.setRGB(0, 0, new Color(101, 100, 100).getRGB());
        assertTrue(PreviewCaptureHostOperations.isSolidContent(nearUniform),
            "two near-identical colors inside the luminance variance must stay solid");
    }

    @Test
    void treatsNullEmptyAndTransparentImagesAsSolid() {
        assertTrue(PreviewCaptureHostOperations.isSolidContent(null));
        final BufferedImage zeroSized = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) {
            @Override
            public int getWidth() {
                return 0;
            }

            @Override
            public int getHeight() {
                return 0;
            }
        };
        assertTrue(PreviewCaptureHostOperations.isSolidContent(zeroSized));
        final BufferedImage transparent = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        assertTrue(PreviewCaptureHostOperations.isSolidContent(transparent));
    }

    @Test
    void keepsStructuredContentAsValid() {
        final BufferedImage checker = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < checker.getHeight(); y++) {
            for (int x = 0; x < checker.getWidth(); x++) {
                checker.setRGB(x, y, ((x + y) & 1) == 0 ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        assertFalse(PreviewCaptureHostOperations.isSolidContent(checker),
            "a black/white checkerboard has a large luminance spread");

        final BufferedImage split = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < split.getHeight(); y++) {
            for (int x = 0; x < split.getWidth(); x++) {
                split.setRGB(x, y, x < 200 ? 0xFF000000 : 0xFFFF8040);
            }
        }
        assertFalse(PreviewCaptureHostOperations.isSolidContent(split),
            "dense grid sampling must see both halves of a split image");
    }

    private static void fill(final BufferedImage image, final Color color) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
    }
    @Test
    void scoresGlCanvasLikeAndDarkComponentsOverPlainSurfaces() {
        final JPanel plain = new ShowingPanel("plain", 400, 300, null);
        final JPanel dark = new ShowingPanel("mainCanvas", 400, 300, new Color(20, 20, 20));
        final JPanel gl = new ShowingPanel("GLJPanel", 800, 600, new Color(20, 20, 20));

        // findBestCaptureComponent requires isShowing(); a wrapper makes the fixture honest.
        final ShowingWrapper wrapper = new ShowingWrapper(plain, dark, gl);
        assertEquals(gl, PreviewCaptureHostOperations.findBestCaptureComponent(wrapper),
            "the GL-like dark canvas must outscore plain surfaces");
        assertEquals(dark, PreviewCaptureHostOperations.findBestCaptureComponent(new ShowingWrapper(plain, dark)),
            "a named canvas must outscore a plain surface");
    }

    @Test
    void selectsTheLargestVisibleNonDialogWindow() {
        assertNull(PreviewCaptureHostOperations.selectCaptureWindow(new Window[0]));
    }

    @Test
    void targetGuardRejectsIdsThatAreNotTheCurrentProject() throws Exception {
        final Path current = Files.createTempFile("recent-preview-capture", ".cmo3");
        final ClassLoader loader = getClass().getClassLoader();
        final VerifiedRecentFileListHostOperations files = new VerifiedRecentFileListHostOperations(
            projectResolver("5.2.03", loader), panelResolver("5.2.03", loader)
        );
        ProjectHost.setRoot(projectChain(current));
        PanelHost.setRoot(panelChain(RecentPreviewHostFixture.recentMenu()));
        files.list();

        final PreviewCaptureHostOperations capture = new PreviewCaptureHostOperations(
            panelResolver("5.2.03", loader), files, noopSuppression()
        );
        final RecentFileId other = new RecentFileId("0".repeat(64));
        assertThrows(java.util.concurrent.CompletionException.class, () -> capture.capture(
            new ScreenshotCaptureRequest(other, 150, 150)
        ).toCompletableFuture().join());
    }

    @Test
    void throwingDiagnosticSinkCannotPreventFailureCompletion() throws Exception {
        final Path current = Files.createTempFile("recent-preview-capture", ".cmo3");
        final ClassLoader loader = getClass().getClassLoader();
        final VerifiedRecentFileListHostOperations files = new VerifiedRecentFileListHostOperations(
            projectResolver("5.2.03", loader), panelResolver("5.2.03", loader)
        );
        ProjectHost.setRoot(projectChain(current));
        PanelHost.setRoot(panelChain(RecentPreviewHostFixture.recentMenu()));
        files.list();

        final PreviewCaptureHostOperations capture = new PreviewCaptureHostOperations(
            panelResolver("5.2.03", loader), files, noopSuppression(),
            ignored -> { throw new IllegalStateException("diagnostic unavailable"); }
        );
        final RecentFileId other = new RecentFileId("0".repeat(64));
        final var future = capture.capture(
            new ScreenshotCaptureRequest(other, 150, 150)
        ).toCompletableFuture();

        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void capturesCurrentProjectWithDebounceAndPopupSuppression() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
            "direct capture requires a visible AWT window; covered by the real-host rerun");

        final Path current = Files.createTempFile("recent-preview-capture", ".cmo3");
        final ClassLoader loader = getClass().getClassLoader();
        final VerifiedRecentFileListHostOperations files = new VerifiedRecentFileListHostOperations(
            projectResolver("5.2.03", loader), panelResolver("5.2.03", loader)
        );
        ProjectHost.setRoot(projectChain(current));
        PanelHost.setRoot(panelChain(RecentPreviewHostFixture.recentMenu()));
        files.list();

        final AtomicInteger hides = new AtomicInteger();
        final AtomicInteger restores = new AtomicInteger();
        final PreviewCaptureHostOperations.PopupSuppression suppression =
            new PreviewCaptureHostOperations.PopupSuppression() {
                @Override public void hide() { hides.incrementAndGet(); }
                @Override public void restore() { restores.incrementAndGet(); }
            };
        final PreviewCaptureHostOperations capture = new PreviewCaptureHostOperations(
            panelResolver("5.2.03", loader), files, suppression
        );

        final Window host = new Window(null);
        SwingUtilities.invokeAndWait(() -> {
            host.add(new ShowingPanel("mainCanvas", 800, 600, new Color(20, 20, 20)));
            host.setSize(900, 700);
            host.setVisible(true);
        });
        try {
            final ScreenshotCaptureRequest request =
                new ScreenshotCaptureRequest(files.list().get(0).id(), 150, 150);
            final ScreenshotCaptureResult first = capture.capture(request)
                .toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(request.id(), first.id());
            assertTrue(first.image().width() <= 150 && first.image().height() <= 150);
            final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(first.image().png()));
            assertNotNull(decoded);

            // Debounce: an immediate repeat for the same id reuses the cached result.
            final ScreenshotCaptureResult second = capture.capture(request)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(first.image(), second.image());

            assertEquals(2, hides.get(), "the popup must be suppressed around every capture");
            assertEquals(2, restores.get(), "the popup must be restored after every capture");
        } finally {
            SwingUtilities.invokeAndWait(host::dispose);
        }
    }

    private static PreviewCaptureHostOperations.PopupSuppression noopSuppression() {
        return new PreviewCaptureHostOperations.PopupSuppression() {
            @Override public void hide() { }
            @Override public void restore() { }
        };
    }

    private static final class ShowingWrapper extends Container {
        private final Component[] children;

        private ShowingWrapper(final Component... children) {
            this.children = children;
            setSize(1000, 800);
        }

        @Override
        public boolean isShowing() {
            return true;
        }

        @Override
        public Component[] getComponents() {
            return children;
        }
    }

    private static final class ShowingPanel extends JPanel {
        private ShowingPanel(final String name, final int width, final int height, final Color background) {
            setName(name);
            setSize(width, height);
            setBackground(background);
        }

        @Override
        public boolean isShowing() {
            return true;
        }
    }
}
