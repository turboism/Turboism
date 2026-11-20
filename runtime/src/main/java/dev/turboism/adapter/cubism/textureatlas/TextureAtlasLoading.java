package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

/** Gives feedback and coordinates off-EDT workers within one atlas initialization on EDT. */
public final class TextureAtlasLoading {

    private static final String BUNDLE = "dev.turboism.textureatlas.loading";
    private static final String TITLE = "loading.title";
    private static final String COUNT = "loading.count";
    private static final String LOG_COMPONENT = "texture-atlas-loading";
    private static final String DISPLAY_FAILURE = "Could not display texture atlas loading progress";
    private static final String SET_PREVIEW_METHOD = "setPreviewImg$cubism";
    private static final String WRITABLE_IMAGE_CLASS = "com.live2d.graphics.CWritableImage";
    private static final int VERTICAL_PADDING = 18;
    private static final int HORIZONTAL_PADDING = 24;
    private static final int COUNT_WIDTH_SAMPLE = 99_999;
    private static final long REPAINT_INTERVAL = TimeUnit.MILLISECONDS.toNanos(100);
    private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();

    private static final ClassValue<Optional<MethodHandle>> SET_PREVIEW_CACHE = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(final Class<?> layerClass) {
            try {
                final Class<?> writableClass = layerClass.getClassLoader().loadClass(WRITABLE_IMAGE_CLASS);
                final Method setter = layerClass.getMethod(SET_PREVIEW_METHOD, writableClass);
                return Optional.of(MethodHandles.lookup().unreflect(setter));
            } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
                return Optional.empty();
            }
        }
    };

    private TextureAtlasLoading() {}

    /** Keeps loading feedback, drawable group cache, preview workers, and mesh workers within one open. */
    public static void run(final Object model, final MethodHandle initialize) throws Throwable {
        if (!EventQueue.isDispatchThread() || ACTIVE.get() != null) {
            initialize.invoke(model);
            return;
        }
        final Progress progress = open();
        final Session session = new Session(progress);
        ACTIVE.set(session);
        try {
            initialize.invoke(model);
            session.previews.awaitAll();
        } finally {
            ACTIVE.remove();
            try {
                try {
                    session.previews.close();
                } finally {
                    session.meshes.close();
                }
            } finally {
                session.closeProgress();
            }
        }
    }

    /** Limits scoped mesh optimizations to atlas initialization and its detached solvers. */
    public static boolean isInitializing() {
        return ACTIVE.get() != null || TextureAtlasParallelMeshes.onWorker();
    }

    /**
     * Caches {@code Editor_Texture$a.a(CModelSource)} across all atlas pages in the active open
     * and launches off-EDT mesh triangulation once when enabled.
     */
    public static Object resolveDrawableGroups(
            final Object helper, final Object modelSource, final Class<?> meshOps, final MethodHandle computeGroups)
            throws Throwable {
        final Session session = ACTIVE.get();
        if (session == null) {
            return computeGroups.invoke(helper, modelSource);
        }
        Object groups = session.cachedDrawableGroups;
        if (groups == null) {
            groups = computeGroups.invoke(helper, modelSource);
            session.cachedDrawableGroups = groups;
            if (meshOps != null && groups instanceof Map<?, ?> map) {
                TextureAtlasParallelMeshes.preload(map, meshOps);
            }
        }
        return groups;
    }

    static TextureAtlasParallelMeshes meshBatch() {
        final Session session = ACTIVE.get();
        return session == null ? null : session.meshes;
    }

    static TextureAtlasPreviewImages previewBatch() {
        final Session session = ACTIVE.get();
        return session == null ? null : session.previews;
    }

    static boolean hasLayerSetPreview(final Object layer) {
        return SET_PREVIEW_CACHE.get(layer.getClass()).isPresent();
    }

    static void assignLayerPreview(final Object layer, final Object preview) throws Throwable {
        final Optional<MethodHandle> setter = SET_PREVIEW_CACHE.get(layer.getClass());
        if (setter.isPresent()) {
            setter.get().invoke(layer, preview);
        }
    }

    /** Reports completed edit layers to the loading window. */
    public static void layerReady() {
        final Session session = ACTIVE.get();
        if (session == null || session.progress == null) return;
        try {
            session.progress.advance();
        } catch (RuntimeException failure) {
            session.closeProgress();
            RuntimeDiagnostics.error(LOG_COMPONENT, DISPLAY_FAILURE, failure);
        }
    }

    private static Progress open() {
        Progress progress = null;
        try {
            final Window active =
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            final Window owner = active != null && active.isShowing()
                    ? active
                    : Arrays.stream(Frame.getFrames())
                            .filter(Frame::isShowing)
                            .findFirst()
                            .orElse(null);
            if (owner == null) return null;
            progress = new Progress(owner);
            progress.show();
            return progress;
        } catch (RuntimeException failure) {
            if (progress != null) progress.close();
            RuntimeDiagnostics.error(LOG_COMPONENT, DISPLAY_FAILURE, failure);
            return null;
        }
    }

    private static final class Session {
        private final TextureAtlasParallelMeshes meshes = new TextureAtlasParallelMeshes();
        private final TextureAtlasPreviewImages previews = new TextureAtlasPreviewImages();
        private Object cachedDrawableGroups;
        private Progress progress;

        Session(final Progress progress) {
            this.progress = progress;
        }

        void closeProgress() {
            if (progress != null) {
                progress.close();
                progress = null;
            }
        }
    }

    private static final class Progress {
        private final JDialog dialog;
        private final JLabel label;
        private final MessageFormat countFormat;
        private int completed;
        private long lastPaint;

        Progress(final Window owner) {
            final ResourceBundle strings = ResourceBundle.getBundle(BUNDLE);
            final String title = strings.getString(TITLE);
            countFormat = new MessageFormat(strings.getString(COUNT), strings.getLocale());
            dialog = new JDialog(owner, title, Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.setAutoRequestFocus(false);
            dialog.setFocusableWindowState(false);
            dialog.setResizable(false);
            label = new JLabel(countFormat.format(new Object[] {COUNT_WIDTH_SAMPLE}));
            label.setPreferredSize(label.getPreferredSize());
            final JPanel content = new JPanel();
            content.setBorder(BorderFactory.createEmptyBorder(
                    VERTICAL_PADDING, HORIZONTAL_PADDING, VERTICAL_PADDING, HORIZONTAL_PADDING));
            content.add(label);
            dialog.setContentPane(content);
            updateText();
            dialog.pack();
            dialog.setLocationRelativeTo(owner);
        }

        void show() {
            dialog.setVisible(true);
            paint();
        }

        void advance() {
            completed++;
            if (System.nanoTime() - lastPaint >= REPAINT_INTERVAL) {
                updateText();
                paint();
            }
        }

        void close() {
            try {
                dialog.dispose();
            } catch (RuntimeException failure) {
                RuntimeDiagnostics.error(LOG_COMPONENT, DISPLAY_FAILURE, failure);
            }
        }

        private void updateText() {
            label.setText(countFormat.format(new Object[] {completed}));
        }

        private void paint() {
            // The host keeps the event queue busy until it finishes building the edit layers.
            final var rootPane = dialog.getRootPane();
            rootPane.paintImmediately(rootPane.getVisibleRect());
            Toolkit.getDefaultToolkit().sync();
            lastPaint = System.nanoTime();
        }
    }
}
