package dev.turboism.sdk.cubism.screenshot;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScreenshotCaptureServiceContractTest {

    @Test
    void exposesBoundedAsyncPngCaptureWithoutHostOrPathEscape() throws Exception {
        final Method accessor = Arrays.stream(PluginContext.class.getMethods())
            .filter(method -> method.getName().equals("screenshots"))
            .findFirst()
            .orElseThrow();

        assertTrue(accessor.isDefault());
        assertEquals(ScreenshotCaptureService.class, accessor.getReturnType());
        assertEquals(CompletionStage.class,
            ScreenshotCaptureService.class.getDeclaredMethod("capture", ScreenshotCaptureRequest.class)
                .getReturnType());

        final PluginContext context = (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[]{PluginContext.class},
            (proxy, method, args) -> method.isDefault() ? invokeDefault(proxy, method, args) : null
        );
        final UnsupportedOperationException unavailable = assertThrows(
            UnsupportedOperationException.class,
            context::screenshots
        );
        assertEquals("screenshot capture service is not available", unavailable.getMessage());

        final ScreenshotCaptureRequest request = new ScreenshotCaptureRequest(
            new RecentFileId("recent-1"),
            150,
            150
        );
        // Bounds are capped at 150×150 per the preview contract.
        assertThrows(IllegalArgumentException.class,
            () -> new ScreenshotCaptureRequest(new RecentFileId("recent-1"), 0, 150));
        assertThrows(IllegalArgumentException.class,
            () -> new ScreenshotCaptureRequest(new RecentFileId("recent-1"), 150, 151));
        assertThrows(IllegalArgumentException.class,
            () -> new ScreenshotCaptureRequest(new RecentFileId("recent-1"), 4096, 150));

        final byte[] source = png();
        final byte[] expected = source.clone();
        final ScreenshotImage image = new ScreenshotImage(1, 1, source);
        source[0] = 9;
        assertArrayEquals(expected, image.png());
        final byte[] returned = image.png();
        returned[1] = 9;
        assertArrayEquals(expected, image.png());
        assertThrows(IllegalArgumentException.class,
            () -> new ScreenshotImage(150, 100, new byte[1024 * 1024 + 1]));
        assertThrows(IllegalArgumentException.class,
            () -> new ScreenshotImage(150, 100, new byte[]{1, 2, 3}));
        assertEquals(request.id(), new ScreenshotCaptureResult(request.id(), image).id());

        final Set<String> forbidden = Set.of(
            Path.class.getName(),
            "java.io.File",
            "java.awt.",
            "javax.swing.",
            "com.jogamp.",
            "com.live2d.",
            "dev.turboism.adapter.",
            "dev.turboism.core."
        );
        for (Class<?> type : Set.of(
            ScreenshotCaptureService.class,
            ScreenshotCaptureRequest.class,
            ScreenshotCaptureResult.class,
            ScreenshotImage.class
        )) {
            for (Method method : type.getMethods()) {
                for (String prefix : forbidden) {
                    assertFalse(method.toGenericString().contains(prefix), method.toGenericString());
                }
            }
        }
    }

    private static Object invokeDefault(
        final Object proxy,
        final Method method,
        final Object[] args
    ) throws Throwable {
        final java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.privateLookupIn(
            method.getDeclaringClass(),
            java.lang.invoke.MethodHandles.lookup()
        );
        return lookup.unreflectSpecial(method, method.getDeclaringClass())
            .bindTo(proxy)
            .invokeWithArguments(args == null ? new Object[0] : args);
    }

    private static byte[] png() {
        return java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        );
    }
}
