package dev.turboism.adapter.cubism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2ES3;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreviewFramebufferReadbackTest {
    @Test
    void readsTheFullPhysicalCanvasAtHighDpi() throws Exception {
        final BufferedImage image = new ReadbackFixture(7, 8).read();
        assertNotNull(image);
        assertEquals(3, image.getWidth());
        assertEquals(3, image.getHeight());
    }

    @Test
    void readsResolvedPixelsBeforeDeletingTheFramebuffer() throws Exception {
        final ReadbackFixture fixture = new ReadbackFixture(7, 8);

        final BufferedImage image = fixture.read();

        assertNotNull(image);
        assertEquals(0xFF804020, image.getRGB(0, 0));
        assertEquals(List.of("read:11", "delete:11"), fixture.operations);
        assertEquals(7, fixture.drawFramebuffer);
        assertEquals(8, fixture.readFramebuffer);
        assertEquals(42, fixture.texture);
    }

    @Test
    void readsTheDefaultFramebufferOnlyOnceAndRestoresItsReadBuffer() throws Exception {
        final ReadbackFixture fixture = new ReadbackFixture(0, 0);

        assertNotNull(fixture.read());

        assertEquals(List.of("read:0"), fixture.operations);
        assertEquals(0x0404, fixture.readBuffer);
    }

    @Test
    void restoresBindingsAndDeletesTemporaryObjectsWhenReadbackFails() throws Exception {
        final ReadbackFixture fixture = new ReadbackFixture(7, 8);
        fixture.failRead = true;

        assertNull(fixture.read());

        assertEquals(List.of("read:11", "delete:11"), fixture.operations);
        assertEquals(7, fixture.drawFramebuffer);
        assertEquals(8, fixture.readFramebuffer);
        assertEquals(42, fixture.texture);
    }

    public static final class Drawable {
        private final GL gl;

        Drawable(final GL gl) {
            this.gl = gl;
        }

        public GL getGL() {
            return gl;
        }

        public int getSurfaceWidth() {
            return 3;
        }

        public int getSurfaceHeight() {
            return 3;
        }
    }

    private static final class ReadbackFixture {
        private int drawFramebuffer;
        private int readFramebuffer;
        private int texture = 42;
        private int readBuffer = 0x0404;
        private boolean failRead;
        private boolean resolved;
        private final List<String> operations = new ArrayList<>();

        ReadbackFixture(final int drawFramebuffer, final int readFramebuffer) {
            this.drawFramebuffer = drawFramebuffer;
            this.readFramebuffer = readFramebuffer;
        }

        BufferedImage read() throws Exception {
            final GL gl = (GL) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {GL2ES3.class}, (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "glGetIntegerv" ->
                                ((int[]) arguments[1])[(Integer) arguments[2]] = switch ((Integer) arguments[0]) {
                                    case 0x8CA6 -> drawFramebuffer;
                                    case 0x8CAA -> readFramebuffer;
                                    case 0x8069 -> texture;
                                    case 0x0C02 -> readBuffer;
                                    default -> 0;
                                };
                            case "glBindFramebuffer" -> {
                                final int target = (Integer) arguments[0];
                                if (target == 0x8D40 || target == 0x8CA9) drawFramebuffer = (Integer) arguments[1];
                                if (target == 0x8D40 || target == 0x8CA8) readFramebuffer = (Integer) arguments[1];
                            }
                            case "glGenFramebuffers" -> ((int[]) arguments[1])[0] = 11;
                            case "glGenTextures" -> ((int[]) arguments[1])[0] = 12;
                            case "glBindTexture" -> texture = (Integer) arguments[1];
                            case "glReadBuffer" -> readBuffer = (Integer) arguments[0];
                            case "glBlitFramebuffer" -> resolved = readFramebuffer == 7 && drawFramebuffer == 11;
                            case "glReadPixels" -> {
                                operations.add("read:" + readFramebuffer);
                                if (failRead) throw new IllegalStateException("read failed");
                                final ByteBuffer pixels = (ByteBuffer) arguments[6];
                                final int color =
                                        (readFramebuffer == 11 && resolved) || readFramebuffer == 0 ? 0xFF204080 : 0;
                                for (int offset = 0; offset < pixels.capacity(); offset += 4)
                                    pixels.putInt(offset, color);
                            }
                            case "glDeleteFramebuffers" -> operations.add("delete:" + ((int[]) arguments[1])[0]);
                            case "glCheckFramebufferStatus" -> {
                                return 0x8CD5;
                            }
                            case "glGetError" -> {
                                return 0;
                            }
                            default -> {}
                        }
                        return null;
                    });
            final var read = PreviewCaptureHostOperations.class.getDeclaredMethod(
                    "readJogl", Object.class, int.class, int.class);
            read.setAccessible(true);
            return (BufferedImage) read.invoke(null, new Drawable(gl), 2, 2);
        }
    }
}
