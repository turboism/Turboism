package dev.turboism.bootstrap;

import dev.turboism.core.runtime.work.FatalErrors;
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
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-modal startup feedback shared by every agent launch path. Uses only AWT:
 * initializing Swing's look and feel here would race Cubism's FlatLaf startup.
 * Closing this window dismisses feedback, never the editor or its bootstrap.
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
    private static final Font TITLE_FONT = new Font(Font.DIALOG, Font.BOLD, 19);
    private static final Font SUBTITLE_FONT = new Font(Font.DIALOG, Font.PLAIN, 14);
    private static final int PADDING = 18;
    private static final int SPINNER_SIZE = 22;
    private static final int TEXT_GAP = 14;
    private static final int LINE_GAP = 3;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean repaintPending = new AtomicBoolean();
    private final boolean chinese =
            Locale.getDefault(Locale.Category.DISPLAY).getLanguage().equals("zh");
    private final long startedAt = System.nanoTime();
    private volatile Phase phase = Phase.WAITING;
    private volatile long terminalAt;
    // Dialog and animation frame are owned exclusively by the EDT.
    private Dialog window;
    private Canvas content;
    private int frame;
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
                    100L);
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
        if (terminalAt == 0L && now - startedAt >= java.util.concurrent.TimeUnit.SECONDS.toNanos(180)) {
            phase(Phase.TIMED_OUT);
        }
        // A failed bootstrap must not leave an AWT window keeping the JVM alive.
        if (terminalAt != 0L && now - terminalAt >= java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
            close();
            return;
        }
        if (!repaintPending.compareAndSet(false, true)) return;
        try {
            EventQueue.invokeLater(() -> {
                try {
                    if (closed.get()) return;
                    if (window == null) showWindow();
                    window.getAccessibleContext().setAccessibleDescription(message());
                    final Dimension size = contentSize();
                    if (!size.equals(content.getPreferredSize())) {
                        content.setPreferredSize(size);
                        window.pack();
                    }
                    frame = (frame + 1) % 12;
                    content.repaint();
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

    private void showWindow() {
        window = new Dialog((Frame) null, "Turboism", false);
        window.setName("turboism-startup-loading");
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
            public void paint(final Graphics graphics) {
                paintContent((Graphics2D) graphics);
            }
        };
        content.setBackground(Color.WHITE);
        content.setPreferredSize(contentSize());
        window.add(content);
        window.pack();
        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }

    private Dimension contentSize() {
        final FontMetrics title = content.getFontMetrics(TITLE_FONT);
        final FontMetrics subtitle = content.getFontMetrics(SUBTITLE_FONT);
        final int textWidth = Math.max(title.stringWidth(TITLE), subtitle.stringWidth(subtitle()));
        final int textHeight = title.getHeight() + LINE_GAP + subtitle.getHeight();
        return new Dimension(
                PADDING * 2 + SPINNER_SIZE + TEXT_GAP + textWidth, PADDING * 2 + Math.max(SPINNER_SIZE, textHeight));
    }

    private String subtitle() {
        return terminalAt == 0L ? SLOGAN : message();
    }

    private void paintContent(final Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        final FontMetrics title = graphics.getFontMetrics(TITLE_FONT);
        final FontMetrics subtitle = graphics.getFontMetrics(SUBTITLE_FONT);
        final int x = PADDING + SPINNER_SIZE + TEXT_GAP;
        graphics.setColor(new Color(35, 39, 47));
        graphics.setFont(TITLE_FONT);
        graphics.drawString(TITLE, x, PADDING + title.getAscent());
        graphics.setColor(new Color(105, 110, 119));
        graphics.setFont(SUBTITLE_FONT);
        graphics.drawString(subtitle(), x, PADDING + title.getHeight() + LINE_GAP + subtitle.getAscent());
        final int spinnerY = (content.getHeight() - SPINNER_SIZE) / 2;
        if (terminalAt != 0L) {
            graphics.setColor(new Color(176, 95, 30));
            graphics.setFont(new Font(Font.DIALOG, Font.BOLD, 24));
            graphics.drawString("!", PADDING + 6, spinnerY + SPINNER_SIZE);
        } else {
            graphics.setStroke(new java.awt.BasicStroke(3f));
            graphics.setColor(new Color(224, 228, 235));
            graphics.drawOval(PADDING, spinnerY, SPINNER_SIZE, SPINNER_SIZE);
            graphics.setColor(new Color(65, 112, 210));
            graphics.drawArc(PADDING, spinnerY, SPINNER_SIZE, SPINNER_SIZE, -frame * 30, 100);
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
        try {
            EventQueue.invokeLater(() -> {
                if (window != null) window.dispose();
            });
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // Optional feedback must never interrupt the editor bootstrap.
        }
    }
}
