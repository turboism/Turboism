package dev.turboism.bootstrap;

import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-modal startup feedback shared by every agent launch path. Uses only AWT:
 * initializing Swing's look and feel here would race Cubism's FlatLaf startup.
 * Closing this window dismisses feedback, never the editor or its bootstrap.
 *
 * <p>Every frame is composed into a compatible back buffer and blitted in one
 * operation, and the heavyweight canvas skips AWT's asynchronous background
 * erase. Painting the text and the antialiased spinner directly would let the
 * white erase land before the redraw, which reads as a flicker on each frame.
 */
final class StartupLoadingWindow implements AutoCloseable {

    enum Phase {
        WAITING,
        INITIALIZING,
        PLUGINS,
        INTERFACE,
        FAILED,
        TIMED_OUT
    }

    private static final String TITLE = "Turboism";
    private static final String SLOGAN = "Switch on the power line";
    private static final Color BACKGROUND = Color.WHITE;
    private static final Color TITLE_COLOR = new Color(35, 39, 47);
    private static final Color SUBTITLE_COLOR = new Color(105, 110, 119);
    private static final Color WARNING_COLOR = new Color(176, 95, 30);
    private static final Color SPINNER_TRACK = new Color(224, 228, 235);
    private static final Color SPINNER_ACCENT = new Color(65, 112, 210);
    private static final Font TITLE_FONT = new Font(Font.DIALOG, Font.BOLD, 19);
    private static final Font SUBTITLE_FONT = new Font(Font.DIALOG, Font.PLAIN, 14);
    private static final Font WARNING_FONT = new Font(Font.DIALOG, Font.BOLD, 24);
    private static final int PADDING = 18;
    private static final int SPINNER_SIZE = 22;
    private static final int TEXT_GAP = 14;
    private static final int LINE_GAP = 3;
    private static final int ARC_DEGREES = 100;
    /** ~60 fps: continuous motion for a 20k pixel blit, cheap enough for the shared EDT. */
    private static final long FRAME_INTERVAL_MILLIS = 16L;
    /** One spinner revolution; the angle is derived from the clock, so ticks never stutter. */
    private static final long SPIN_PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(1100L);

    private static final long FADE_IN_NANOS = TimeUnit.MILLISECONDS.toNanos(140L);
    private static final float MIN_FADE_ALPHA = 0.02f;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean repaintPending = new AtomicBoolean();
    private final boolean chinese =
            Locale.getDefault(Locale.Category.DISPLAY).getLanguage().equals("zh");
    private final long startedAt = System.nanoTime();
    private volatile Phase phase = Phase.WAITING;
    private volatile long terminalAt;
    private volatile long shownAt;
    private volatile int renderedKey = Integer.MIN_VALUE;
    private volatile int renderedAngle;
    // Dialog, canvas, back buffer and fade state are owned exclusively by the EDT.
    private Dialog window;
    private Canvas content;
    private BufferedImage backBuffer;
    private String displayedSubtitle;
    private String displayedMessage;
    private float contentAlpha = MIN_FADE_ALPHA;
    private boolean windowOpacity;
    private boolean fadeSettled;
    private Timer timer;

    static StartupLoadingWindow open() {
        final StartupLoadingWindow loading = new StartupLoadingWindow();
        try {
            if (GraphicsEnvironment.isHeadless()) {
                loading.closed.set(true);
                return loading;
            }
            loading.timer = new Timer("turboism-startup-loading", true);
            loading.timer.scheduleAtFixedRate(
                    new TimerTask() {
                        @Override
                        public void run() {
                            loading.tick();
                        }
                    },
                    0L,
                    FRAME_INTERVAL_MILLIS);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            loading.close();
        }
        return loading;
    }

    synchronized void phase(final Phase next) {
        if (terminalAt != 0L || closed.get()) return;
        phase = next;
        if (next == Phase.FAILED || next == Phase.TIMED_OUT) terminalAt = System.nanoTime();
    }

    private void tick() {
        if (closed.get()) return;
        final long now = System.nanoTime();
        if (terminalAt == 0L && now - startedAt >= TimeUnit.SECONDS.toNanos(180)) {
            phase(Phase.TIMED_OUT);
        }
        // A failed bootstrap must not leave an AWT window keeping the JVM alive.
        if (terminalAt != 0L && now - terminalAt >= TimeUnit.SECONDS.toNanos(30)) {
            close();
            return;
        }
        final int key = frameKey(now);
        if (key == renderedKey && window != null) return;
        if (!repaintPending.compareAndSet(false, true)) return;
        renderedKey = key;
        renderedAngle = terminalAt == 0L ? spinnerDegrees(now) : -1;
        try {
            EventQueue.invokeLater(() -> {
                try {
                    if (closed.get()) return;
                    if (window == null) showWindow();
                    applyMessage();
                    presentFrame();
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    close();
                } finally {
                    repaintPending.set(false);
                }
            });
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            repaintPending.set(false);
            close();
        }
    }

    /**
     * Repaint trigger for the next frame. The spinner angle alone would already
     * change on every tick; folding the fade progress in keeps the appearance
     * animation continuous for its whole duration.
     */
    private int frameKey(final long now) {
        if (terminalAt != 0L) return -1;
        final int degrees = spinnerDegrees(now);
        final float alpha = fadeAlpha(now);
        return alpha >= 1f ? degrees * 100 : degrees * 100 + 1 + (int) (alpha * 98f);
    }

    private int spinnerDegrees(final long now) {
        return (int) ((now - startedAt) % SPIN_PERIOD_NANOS * 360L / SPIN_PERIOD_NANOS);
    }

    /**
     * Fade-in progress for the first {@link #FADE_IN_NANOS} of the window life.
     * Terminal states are shown at full opacity so a failure is never dimmed.
     */
    private float fadeAlpha(final long now) {
        if (terminalAt != 0L) return 1f;
        if (shownAt == 0L) return MIN_FADE_ALPHA;
        final long elapsed = now - shownAt;
        if (elapsed >= FADE_IN_NANOS) return 1f;
        return Math.max(MIN_FADE_ALPHA, elapsed / (float) FADE_IN_NANOS);
    }

    private void showWindow() {
        window = new Dialog((Frame) null, TITLE, false);
        window.setUndecorated(true);
        window.setName("turboism-startup-loading");
        window.setBackground(BACKGROUND);
        window.setResizable(false);
        window.setAutoRequestFocus(false);
        window.setFocusableWindowState(false);
        // Keep feedback visible when Cubism opens its main window, without
        // activating it or taking keyboard focus away from the editor.
        if (window.isAlwaysOnTopSupported()) window.setAlwaysOnTop(true);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent event) {
                close();
            }
        });
        content = new Canvas() {
            @Override
            public void update(final Graphics graphics) {
                // Skip the asynchronous background erase that blinks a heavyweight
                // canvas on every frame; paint() blits one fully rendered buffer.
                paint(graphics);
            }

            @Override
            public void paint(final Graphics graphics) {
                paintContent((Graphics2D) graphics);
            }
        };
        content.setBackground(BACKGROUND);
        displayedSubtitle = subtitle();
        displayedMessage = message();
        window.getAccessibleContext().setAccessibleDescription(displayedMessage);
        content.setPreferredSize(contentSize(displayedSubtitle));
        window.add(content);
        window.pack();
        window.setLocationRelativeTo(null);
        final GraphicsConfiguration configuration = content.getGraphicsConfiguration();
        windowOpacity = configuration != null
                && configuration
                        .getDevice()
                        .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT);
        final float initialAlpha = fadeAlpha(System.nanoTime());
        contentAlpha = windowOpacity ? 1f : initialAlpha;
        if (windowOpacity) applyWindowOpacity(initialAlpha);
        window.setVisible(true);
        shownAt = System.nanoTime();
    }

    private void applyMessage() {
        final String message = message();
        if (!message.equals(displayedMessage)) {
            displayedMessage = message;
            window.getAccessibleContext().setAccessibleDescription(message);
        }
        final String subtitle = subtitle();
        if (subtitle.equals(displayedSubtitle)) return;
        displayedSubtitle = subtitle;
        final Dimension size = contentSize(subtitle);
        if (size.equals(content.getPreferredSize())) return;
        content.setPreferredSize(size);
        window.pack();
        // A taller terminal message must not leave the window off center.
        window.setLocationRelativeTo(null);
    }

    private void presentFrame() {
        final float alpha = fadeAlpha(System.nanoTime());
        if (windowOpacity) {
            applyWindowOpacity(alpha);
        } else {
            contentAlpha = alpha;
        }
        content.repaint();
    }

    private void applyWindowOpacity(final float alpha) {
        if (fadeSettled) return;
        try {
            window.setOpacity(alpha >= 1f ? 1f : alpha);
            fadeSettled = alpha >= 1f;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // No compositing support after all: fall back to fading the content.
            windowOpacity = false;
            contentAlpha = alpha;
        }
    }

    private Dimension contentSize(final String subtitle) {
        final FontMetrics title = content.getFontMetrics(TITLE_FONT);
        final FontMetrics body = content.getFontMetrics(SUBTITLE_FONT);
        final int textWidth = Math.max(title.stringWidth(TITLE), body.stringWidth(subtitle));
        final int textHeight = title.getHeight() + LINE_GAP + body.getHeight();
        return new Dimension(
                PADDING * 2 + SPINNER_SIZE + TEXT_GAP + textWidth, PADDING * 2 + Math.max(SPINNER_SIZE, textHeight));
    }

    private String subtitle() {
        return terminalAt == 0L ? SLOGAN : message();
    }

    private void paintContent(final Graphics2D target) {
        final int width = Math.max(1, content.getWidth());
        final int height = Math.max(1, content.getHeight());
        if (backBuffer == null || backBuffer.getWidth() != width || backBuffer.getHeight() != height) {
            backBuffer = createBackBuffer(width, height);
        }
        final Graphics2D graphics = backBuffer.createGraphics();
        try {
            render(graphics, width, height);
        } finally {
            graphics.dispose();
        }
        target.drawImage(backBuffer, 0, 0, null);
        // Without an explicit flush the X11 pipeline batches frames and presents
        // them up to half a second late; a no-op where the platform already flushes.
        Toolkit.getDefaultToolkit().sync();
    }

    private BufferedImage createBackBuffer(final int width, final int height) {
        final GraphicsConfiguration configuration = content.getGraphicsConfiguration();
        if (configuration != null) {
            try {
                return configuration.createCompatibleImage(width, height);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
            }
        }
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    private void render(final Graphics2D graphics, final int width, final int height) {
        graphics.setComposite(AlphaComposite.Src);
        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, width, height);
        graphics.setComposite(
                contentAlpha >= 1f
                        ? AlphaComposite.SrcOver
                        : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, contentAlpha));
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        final FontMetrics title = graphics.getFontMetrics(TITLE_FONT);
        final FontMetrics subtitle = graphics.getFontMetrics(SUBTITLE_FONT);
        final int x = PADDING + SPINNER_SIZE + TEXT_GAP;
        graphics.setColor(TITLE_COLOR);
        graphics.setFont(TITLE_FONT);
        graphics.drawString(TITLE, x, PADDING + title.getAscent());
        graphics.setColor(SUBTITLE_COLOR);
        graphics.setFont(SUBTITLE_FONT);
        graphics.drawString(displayedSubtitle, x, PADDING + title.getHeight() + LINE_GAP + subtitle.getAscent());
        final int spinnerY = (height - SPINNER_SIZE) / 2;
        if (terminalAt != 0L) {
            graphics.setColor(WARNING_COLOR);
            graphics.setFont(WARNING_FONT);
            graphics.drawString("!", PADDING + 6, spinnerY + SPINNER_SIZE);
        } else {
            graphics.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.setColor(SPINNER_TRACK);
            graphics.drawOval(PADDING, spinnerY, SPINNER_SIZE, SPINNER_SIZE);
            graphics.setColor(SPINNER_ACCENT);
            graphics.drawArc(PADDING, spinnerY, SPINNER_SIZE, SPINNER_SIZE, -renderedAngle, ARC_DEGREES);
        }
    }

    private String message() {
        return switch (phase) {
            case WAITING -> chinese ? "正在启动 Cubism…" : "Starting Cubism…";
            case INITIALIZING -> chinese ? "正在初始化 Turboism…" : "Initializing Turboism…";
            case PLUGINS -> chinese ? "正在加载插件…" : "Loading plugins…";
            case INTERFACE -> chinese ? "正在准备界面…" : "Preparing the interface…";
            case FAILED -> chinese ? "Turboism 加载失败，请查看启动日志" : "Turboism failed to load. Check the startup log.";
            case TIMED_OUT -> chinese ? "启动等待超时，请查看启动日志" : "Startup wait timed out. Check the startup log.";
        };
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (timer != null) timer.cancel();
        EventQueue.invokeLater(() -> {
            if (window == null) return;
            try {
                window.dispose();
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                // Optional feedback must never interrupt the editor bootstrap.
            }
        });
    }
}
