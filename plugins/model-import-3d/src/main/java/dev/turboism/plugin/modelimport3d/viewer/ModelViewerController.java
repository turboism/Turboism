package dev.turboism.plugin.modelimport3d.viewer;

import dev.turboism.sdk.ui.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.SwingUtilities;

/** Owns one viewer session. Decode and coalesced renders never run on the EDT. */
public final class ModelViewerController implements AutoCloseable {
    /** Maximum authorized GLB input size in bytes. */
    public static final int MAX_FILE_BYTES = 128 * 1024 * 1024;

    /** Receives session updates on the Swing EDT. */
    public interface View {
        /** Displays a localized loading or failure message. */
        void status(String message);

        /** Reports the newly decoded scene. */
        void loaded(ViewerScene.Stats stats);

        /** Replaces the displayed frame with a session-owned image. */
        void frame(BufferedImage image);
    }

    private record Camera(double yaw, double pitch, double zoom) {}

    private final UserFileAccessService files;
    private final View view;
    private final Function<String, String> text;
    private final Consumer<Throwable> log;
    private final ThreadPoolExecutor worker;
    private Future<?> decodeTask;

    interface FrameRenderer {
        BufferedImage render(ViewerScene scene, double yaw, double pitch, double zoom, boolean geometry);
    }

    private final FrameRenderer renderer;
    private final BiFunction<byte[], BooleanSupplier, ViewerScene> decoder;
    private volatile boolean closed;
    private volatile long generation;
    private ViewerScene scene;
    private Camera camera = new Camera(0, 0, 1);
    private boolean rendering;
    private boolean geometry;
    private long cameraVersion;
    private UserFileHandle handle;
    private boolean selecting;
    private boolean loading;

    /** Creates a session with authorized file access, EDT updates and localized messages. */
    public ModelViewerController(
            UserFileAccessService files, View view, Function<String, String> text, Consumer<Throwable> log) {
        this(files, view, text, log, GlbViewerLoader::load, defaultRenderer());
    }

    private static FrameRenderer defaultRenderer() {
        var software = new SoftwareModelRenderer();
        return (scene, yaw, pitch, zoom, geometry) -> software.render(
                scene,
                640,
                640,
                yaw,
                pitch,
                zoom,
                geometry ? SoftwareModelRenderer.Shading.GEOMETRY : SoftwareModelRenderer.Shading.SOURCE_MATERIAL);
    }

    ModelViewerController(
            UserFileAccessService files,
            View view,
            Function<String, String> text,
            Consumer<Throwable> log,
            BiFunction<byte[], BooleanSupplier, ViewerScene> decoder,
            FrameRenderer renderer) {
        this.decoder = decoder;
        this.renderer = renderer;
        this.files = files;
        this.view = view;
        this.text = text;
        this.log = log;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "turboism-model-viewer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Requests an authorized GLB file through the runtime chooser. */
    public synchronized void choose() {
        if (closed || selecting) return;
        selecting = true;
        loading = true;
        long token = ++generation;
        update(token, () -> view.status(text.apply("viewer.loading")));
        try {
            files.request(new UserFileRequest(
                            "view-model-" + UUID.randomUUID(),
                            text.apply("viewer.choose"),
                            List.of("glb"),
                            UserFileMode.READ,
                            UserFileLifetime.ONE_OPERATION))
                    .whenComplete((selected, error) -> {
                        if (error != null) {
                            fail(token, error);
                            return;
                        }
                        if (selected.status() != UserFileRequestStatus.GRANTED) {
                            synchronized (this) {
                                if (active(token)) {
                                    selecting = false;
                                    loading = false;
                                }
                            }
                            if (selected.status() == UserFileRequestStatus.CANCELED)
                                update(token, () -> view.status(text.apply("viewer.hint")));
                            else fail(token, new IllegalStateException("File grant: " + selected.status()));
                            return;
                        }
                        UserFileHandle granted = selected.handle().orElseThrow();
                        synchronized (this) {
                            if (!active(token)) {
                                granted.close();
                                return;
                            }
                            handle = granted;
                        }
                        try {
                            files.readBytesBounded(granted, MAX_FILE_BYTES).whenComplete((read, failure) -> {
                                granted.close();
                                synchronized (this) {
                                    if (handle == granted) handle = null;
                                    if (active(token)) selecting = false;
                                }
                                if (!active(token)) return;
                                if (failure != null) {
                                    fail(token, failure);
                                    return;
                                }
                                if (read.truncated()) {
                                    synchronized (this) {
                                        loading = false;
                                    }
                                    update(token, () -> view.status(text.apply("viewer.too-large")));
                                    return;
                                }
                                if (read.value().isEmpty()) {
                                    fail(token, new IllegalStateException("File read: " + read.error()));
                                    return;
                                }
                                decode(read.value().orElseThrow(), token);
                            });
                        } catch (RuntimeException failure) {
                            granted.close();
                            fail(token, failure);
                        }
                    });
        } catch (RuntimeException failure) {
            fail(token, failure);
        }
    }

    /** Accepts already authorized bytes; also used by offline and exact-host probes. */
    public synchronized void load(byte[] bytes) {
        if (closed) return;
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Viewer file budget exceeded");
        long token = ++generation;
        if (handle != null) {
            handle.close();
            handle = null;
        }
        selecting = false;
        loading = true;
        update(token, () -> view.status(text.apply("viewer.loading")));
        decode(bytes.clone(), token);
    }

    private synchronized void decode(byte[] bytes, long token) {
        if (!active(token)) return;
        if (decodeTask != null) decodeTask.cancel(true);
        worker.purge();
        decodeTask = worker.submit(() -> {
            if (!active(token)) return;
            try {
                ViewerScene loaded = decoder.apply(
                        bytes, () -> !active(token) || Thread.currentThread().isInterrupted());
                synchronized (this) {
                    if (!active(token)) return;
                    scene = loaded;
                    loading = false;
                    camera = new Camera(0, 0, 1);
                    ++cameraVersion;
                }
                update(token, () -> view.loaded(loaded.stats()));
                requestRender();
            } catch (CancellationException ignored) {
                // Closing or replacing a model invalidates the task.
            } catch (Exception failure) {
                fail(token, failure);
            }
        });
    }

    /** Adds camera rotation in radians and coalesces pending renders. */
    public synchronized void rotate(double yawDelta, double pitchDelta) {
        if (closed) return;
        camera = new Camera(
                camera.yaw + yawDelta,
                Math.max(-Math.PI / 2, Math.min(Math.PI / 2, camera.pitch + pitchDelta)),
                camera.zoom);
        ++cameraVersion;
        requestRender();
    }

    /** Multiplies camera zoom within the session limits. */
    public synchronized void zoom(double factor) {
        if (closed || !Double.isFinite(factor) || factor <= 0) return;
        camera = new Camera(camera.yaw, camera.pitch, Math.max(.2, Math.min(8, camera.zoom * factor)));
        ++cameraVersion;
        requestRender();
    }

    /** Selects neutral geometry inspection or source materials. */
    public synchronized void geometry(boolean value) {
        if (closed) return;
        geometry = value;
        ++cameraVersion;
        requestRender();
    }

    /** Restores the initial rotation and zoom. */
    public synchronized void reset() {
        if (closed) return;
        camera = new Camera(0, 0, 1);
        ++cameraVersion;
        requestRender();
    }

    private synchronized void requestRender() {
        if (closed || loading || rendering || scene == null) return;
        rendering = true;
        execute(this::renderLatest);
    }

    private void renderLatest() {
        long renderToken = generation;
        long renderedVersion = -1;
        try {
            while (true) {
                ViewerScene current;
                Camera position;
                boolean geometryView;
                long token, version;
                synchronized (this) {
                    if (closed || loading || scene == null) return;
                    current = scene;
                    position = camera;
                    geometryView = geometry;
                    token = generation;
                    renderToken = token;
                    version = cameraVersion;
                    renderedVersion = version;
                }
                BufferedImage image =
                        renderer.render(current, position.yaw, position.pitch, position.zoom, geometryView);
                update(token, () -> view.frame(image));
                synchronized (this) {
                    if (closed || token != generation || version == cameraVersion) {
                        return;
                    }
                }
            }
        } catch (CancellationException ignored) {
        } catch (Exception failure) {
            fail(renderToken, failure);
        } finally {
            synchronized (this) {
                rendering = false;
                if (active(renderToken) && cameraVersion != renderedVersion) requestRender();
            }
        }
    }

    private void execute(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException failure) {
            if (!closed) throw failure;
        }
    }

    private boolean active(long token) {
        return !closed && generation == token;
    }

    private void update(long token, Runnable callback) {
        SwingUtilities.invokeLater(() -> {
            if (active(token)) callback.run();
        });
    }

    private void fail(long token, Throwable failure) {
        synchronized (this) {
            if (!active(token)) return;
            selecting = false;
            loading = false;
        }
        log.accept(failure);
        update(token, () -> view.status(text.apply("viewer.failed")));
    }

    /** Reports whether the session has been closed. */
    public boolean isClosed() {
        return closed;
    }

    /** Reports whether the background worker has stopped. */
    public boolean isTerminated() {
        return worker.isTerminated();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        ++generation;
        if (handle != null) {
            handle.close();
            handle = null;
        }
        scene = null;
        worker.shutdownNow();
    }
}
