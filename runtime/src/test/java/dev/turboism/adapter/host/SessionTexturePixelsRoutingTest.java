package dev.turboism.adapter.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code Drawable.texturePixels} travels the same session route as the other drawable
 * reads: the generation guard admits it, a stale generation rejects it before the delegate runs,
 * and the delegate's value reaches the caller unchanged.
 */
class SessionTexturePixelsRoutingTest {

    @Test
    void texturePixelsForwardsThroughTheGenerationGuard() {
        final var host = new DynamicCubismModelAccess();
        host.connect(() -> {
            throw new AssertionError("A retained view must not look up an active model");
        });
        final var calls = new AtomicInteger();
        final var expected = new TexturePixels(2, 1, new int[] {0xFF010203, 0x04050607});
        final var mesh = mesh(host, calls, expected);

        final var actual = mesh.texturePixels();

        assertSame(expected, actual.orElseThrow());
        assertEquals(1, calls.get());
    }

    @Test
    void texturePixelsRejectsAStaleGenerationBeforeTouchingTheDelegate() {
        final var host = new DynamicCubismModelAccess();
        host.connect(() -> {
            throw new AssertionError("An inactive host must not look up an active model");
        });
        final var calls = new AtomicInteger();
        final var mesh = mesh(host, calls, new TexturePixels(1, 1, new int[] {0xFF000000}));
        host.deactivate();

        assertThrows(IllegalStateException.class, mesh::texturePixels);
        assertEquals(0, calls.get());
    }

    private static Drawable mesh(
            final DynamicCubismModelAccess host, final AtomicInteger calls, final TexturePixels value) {
        final long generation = host.generation();
        final Drawable delegate = Drawable.class.cast(Proxy.newProxyInstance(
                Drawable.class.getClassLoader(), new Class<?>[] {Drawable.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("texturePixels")) {
                        throw new AssertionError("Unexpected method: " + method.getName());
                    }
                    calls.incrementAndGet();
                    return Optional.of(value);
                }));
        return new SessionDrawable(host, generation, generation, new ModelId("model"), delegate);
    }
}
