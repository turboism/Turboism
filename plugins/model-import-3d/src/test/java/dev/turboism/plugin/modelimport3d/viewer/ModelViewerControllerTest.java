package dev.turboism.plugin.modelimport3d.viewer;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.ui.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ModelViewerControllerTest {
    static class Observer implements ModelViewerController.View {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final CountDownLatch failed = new CountDownLatch(1);
        final AtomicBoolean offEdt = new AtomicBoolean();

        @Override
        public void status(String message) {
            if (!SwingUtilities.isEventDispatchThread()) offEdt.set(true);
            messages.add(message);
            if (message.equals("viewer.failed")) failed.countDown();
        }

        @Override
        public void loaded(ViewerScene.Stats stats) {
            fail("Unexpected scene");
        }

        @Override
        public void frame(BufferedImage frame) {
            fail("Unexpected frame");
        }
    }

    @Test
    void malformedInputFailsOffWorkerAndUpdatesOnlyOnEdt() throws Exception {
        Observer observer = new Observer();
        AtomicReference<Throwable> logged = new AtomicReference<>();
        try (var controller =
                new ModelViewerController(UserFileAccessService.unavailable(), observer, key -> key, logged::set)) {
            controller.load(new byte[] {1, 2, 3});
            assertTrue(observer.failed.await(10, TimeUnit.SECONDS));
            assertNotNull(logged.get());
            assertFalse(observer.offEdt.get());
        }
    }

    @Test
    void lateGrantAfterCloseIsClosedAndNeverRead() throws Exception {
        CompletableFuture<UserFileRequestResult> selection = new CompletableFuture<>();
        AtomicInteger requests = new AtomicInteger(), reads = new AtomicInteger(), closes = new AtomicInteger();
        UserFileAccessService files = (UserFileAccessService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {UserFileAccessService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("request")) {
                        requests.incrementAndGet();
                        return selection;
                    }
                    if (method.getName().startsWith("read")) reads.incrementAndGet();
                    throw new AssertionError(method.getName());
                });
        UserFileHandle handle = (UserFileHandle) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {UserFileHandle.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closes.incrementAndGet();
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
        Observer observer = new Observer();
        var controller = new ModelViewerController(files, observer, key -> key, failure -> fail(failure));
        SwingUtilities.invokeAndWait(() -> {
            controller.choose();
            controller.choose();
        });
        controller.close();
        selection.complete(
                new UserFileRequestResult(UserFileRequestStatus.GRANTED, Optional.of(handle), Optional.empty()));
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(1, requests.get());
        assertEquals(0, reads.get());
        assertEquals(1, closes.get());
        assertTrue(controller.isClosed());
        for (int i = 0; i < 100 && !controller.isTerminated(); i++) Thread.sleep(10);
        assertTrue(controller.isTerminated());
        int count = observer.messages.size();
        controller.load(new byte[] {1});
        controller.rotate(1, 1);
        controller.zoom(2);
        controller.reset();
        controller.close();
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(count, observer.messages.size());
    }

    @Test
    void selectedFileUsesBoundedReadAndRejectsTruncationBeforeDecode() throws Exception {
        AtomicInteger closes = new AtomicInteger(), reads = new AtomicInteger();
        UserFileHandle handle = (UserFileHandle) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {UserFileHandle.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closes.incrementAndGet();
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
        UserFileAccessService files = (UserFileAccessService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {UserFileAccessService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("request"))
                        return CompletableFuture.completedFuture(new UserFileRequestResult(
                                UserFileRequestStatus.GRANTED, Optional.of(handle), Optional.empty()));
                    if (method.getName().equals("readBytesBounded")) {
                        reads.incrementAndGet();
                        assertSame(handle, args[0]);
                        assertEquals(128 * 1024 * 1024, args[1]);
                        return CompletableFuture.completedFuture(
                                new UserFileReadResult<>(Optional.of(new byte[] {1, 2, 3}), Optional.empty(), true));
                    }
                    throw new AssertionError(method.getName());
                });
        Observer observer = new Observer();
        try (var controller = new ModelViewerController(files, observer, key -> key, failure -> fail(failure))) {
            SwingUtilities.invokeAndWait(controller::choose);
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals(1, reads.get());
            assertEquals(1, closes.get());
            assertTrue(observer.messages.contains("viewer.too-large"));
            assertFalse(observer.messages.contains("viewer.failed"));
        }
    }

    @Test
    void oldRenderFailureCannotFailReplacementModel() throws Exception {
        replacementDuringRender(true);
    }

    @Test
    void oldRenderLoopYieldsToReplacementDecode() throws Exception {
        replacementDuringRender(false);
    }

    private void replacementDuringRender(boolean throwOldFailure) throws Exception {
        var stats = new ViewerScene.Stats(1, 0, 0, 0, 0, 0, 0);
        ViewerScene oldScene =
                new ViewerScene(List.of(), List.of(), List.of(), new ViewerScene.Bounds(0, 0, 0, 1, 1, 1), stats);
        ViewerScene newScene = new ViewerScene(List.of(), List.of(), List.of(), oldScene.bounds(), stats);
        CountDownLatch renderingOld = new CountDownLatch(1),
                releaseOld = new CountDownLatch(1),
                frameNew = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger(), oldRenders = new AtomicInteger(), frames = new AtomicInteger();
        var view = new ModelViewerController.View() {
            @Override
            public void status(String message) {
                if (message.equals("viewer.failed")) failures.incrementAndGet();
            }

            @Override
            public void loaded(ViewerScene.Stats ignored) {}

            @Override
            public void frame(BufferedImage image) {
                assertEquals(0xff00ff00, image.getRGB(0, 0));
                frames.incrementAndGet();
                frameNew.countDown();
            }
        };
        try (var controller = new ModelViewerController(
                UserFileAccessService.unavailable(),
                view,
                key -> key,
                error -> failures.incrementAndGet(),
                (bytes, cancelled) -> bytes[0] == 1 ? oldScene : newScene,
                (scene, yaw, pitch, zoom, geometry) -> {
                    if (scene == oldScene) {
                        oldRenders.incrementAndGet();
                        renderingOld.countDown();
                        try {
                            assertTrue(releaseOld.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new CancellationException();
                        }
                        if (throwOldFailure) throw new IllegalArgumentException("Old render failed");
                    }
                    BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0, 0, scene == oldScene ? 0xffff0000 : 0xff00ff00);
                    return image;
                })) {
            controller.load(new byte[] {1});
            assertTrue(renderingOld.await(5, TimeUnit.SECONDS));
            controller.rotate(.1, .1);
            controller.load(new byte[] {2});
            releaseOld.countDown();
            assertTrue(frameNew.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals(1, oldRenders.get());
            assertEquals(1, frames.get());
            assertEquals(0, failures.get());
        } finally {
            releaseOld.countDown();
        }
    }
}
