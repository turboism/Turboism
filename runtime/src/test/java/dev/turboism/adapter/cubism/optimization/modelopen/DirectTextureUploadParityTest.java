package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DirectTextureUploadParityTest {

    @Test
    void packsArgbAbgrAndPremulExhaustivelyIncludingPaddingAndFlipY() {
        for (final int type :
                new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_INT_ARGB_PRE
                }) {
            final BufferedImage src = new BufferedImage(128, 64, type);
            for (int y = 0; y < src.getHeight(); y++) {
                final int a = (y * 4) & 0xFF;
                for (int x = 0; x < src.getWidth(); x++) {
                    final int r = (x * 2) & 0xFF;
                    final int g = (x * 13 + y * 7) & 0xFF;
                    final int b = (x * 29 + 11) & 0xFF;
                    if (type == BufferedImage.TYPE_INT_ARGB_PRE) {
                        final int pr = (r * a + 127) / 255;
                        final int pg = (g * a + 127) / 255;
                        final int pb = (b * a + 127) / 255;
                        ((DataBufferInt) src.getRaster().getDataBuffer()).getData()[y * src.getWidth() + x] =
                                (a << 24) | (pr << 16) | (pg << 8) | pb;
                    } else {
                        src.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
            }

            final int dstW = 160;
            final int dstH = 96;
            final ByteBuffer direct = ByteBuffer.allocateDirect(dstW * dstH * 4);
            assertTrue(DirectTextureUploadBridge.packRgbaPremultiplied(src, dstW, dstH, false, direct));
            final byte[] actual = new byte[dstW * dstH * 4];
            direct.get(actual);
            assertArrayEquals(referencePremulRgba(src, dstW, dstH, false), actual);

            direct.clear();
            assertTrue(DirectTextureUploadBridge.packRgbaPremultiplied(
                    src, src.getWidth(), src.getHeight(), true, direct));
            final byte[] actualFlipped = new byte[src.getWidth() * src.getHeight() * 4];
            direct.get(actualFlipped);
            assertArrayEquals(referencePremulRgba(src, src.getWidth(), src.getHeight(), true), actualFlipped);
        }
    }

    @Test
    void handlesSubimageOffsetsAndRejectsUnsupportedColorSpaces() {
        final BufferedImage parent = new BufferedImage(47, 39, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < parent.getHeight(); y++) {
            for (int x = 0; x < parent.getWidth(); x++) {
                parent.setRGB(x, y, ((x + y * 5) & 0xFF) << 24 | ((x * 19) & 0xFF) << 16 | (y * 9) & 0xFF);
            }
        }
        final BufferedImage sub = parent.getSubimage(7, 5, 29, 23);
        final int dstW = 32;
        final int dstH = 32;
        final ByteBuffer out = ByteBuffer.allocateDirect(dstW * dstH * 4);
        assertTrue(DirectTextureUploadBridge.packRgbaPremultiplied(sub, dstW, dstH, false, out));
        final byte[] actual = new byte[dstW * dstH * 4];
        out.get(actual);
        assertArrayEquals(referencePremulRgba(sub, dstW, dstH, false), actual);

        final BufferedImage gray = new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_GRAY);
        assertFalse(DirectTextureUploadBridge.packRgbaPremultiplied(
                gray, 16, 16, false, ByteBuffer.allocateDirect(16 * 16 * 4)));
        assertEquals(0L, DirectTextureUploadBridge.tryUploadFlagged(new Object(), new Object(), gray, false, false));
    }

    @Test
    void fastAbgrAndBgrConversionMatchesJava2dDrawImageBitIdentically() {
        final BufferedImage abgrParent = new BufferedImage(40, 30, BufferedImage.TYPE_4BYTE_ABGR);
        for (int y = 0; y < abgrParent.getHeight(); y++) {
            for (int x = 0; x < abgrParent.getWidth(); x++) {
                abgrParent.setRGB(
                        x, y, ((y * 17) & 0xFF) << 24 | ((x * 23) & 0xFF) << 16 | ((x ^ y) & 0xFF) << 8 | 0x5A);
            }
        }
        final BufferedImage abgrSub = abgrParent.getSubimage(5, 3, 27, 21);
        final int[] refAbgr = drawReferenceArgb(abgrSub);
        final BufferedImage fastAbgr = ImageDecodeBridge.tryConvertToIntArgb(abgrSub, BufferedImage.TYPE_INT_ARGB);
        assertNotNull(fastAbgr);
        assertEquals(BufferedImage.TYPE_INT_ARGB, fastAbgr.getType());
        assertArrayEquals(refAbgr, fastAbgr.getRGB(0, 0, 27, 21, null, 0, 27));

        final BufferedImage bgr = new BufferedImage(33, 19, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < bgr.getHeight(); y++) {
            for (int x = 0; x < bgr.getWidth(); x++) {
                bgr.setRGB(x, y, 0xFF000000 | ((x * 11) & 0xFF) << 16 | ((y * 17) & 0xFF) << 8 | ((x + y) & 0xFF));
            }
        }
        final int[] refBgr = drawReferenceArgb(bgr);
        final BufferedImage fastBgr = ImageDecodeBridge.tryConvertToIntArgb(bgr, BufferedImage.TYPE_INT_ARGB);
        assertNotNull(fastBgr);
        assertArrayEquals(refBgr, fastBgr.getRGB(0, 0, 33, 19, null, 0, 33));

        // Non-sRGB / gray falls back cleanly.
        assertNull(ImageDecodeBridge.tryConvertToIntArgb(
                new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY), BufferedImage.TYPE_INT_ARGB));
    }

    @Test
    void stripedGl3UploadHandles8192SquareFlipYAndAlignedPaddingWithoutOversizedAllocations() throws Exception {
        DirectTextureUploadBridge.clearTransientFallbackBuffer();
        final FakeJoglEnv env = FakeJoglEnv.create(true);
        final long oversizedBefore = DirectTextureUploadBridge.oversizedDirectAllocationCount();
        final long stripedBefore = DirectTextureUploadBridge.stripedGl3UploadCount();

        // 8192 x 8192 = 256 MiB (> 64 MiB): must upload in 2048-row (64 MiB) strips with 0 oversized allocations.
        final BufferedImage img8k = new BufferedImage(8192, 8192, BufferedImage.TYPE_INT_ARGB);
        final int[] raw8k = ((DataBufferInt) img8k.getRaster().getDataBuffer()).getData();
        raw8k[0] = 0x80FF4020; // row 0 col 0
        raw8k[2048 * 8192 + 5] = 0xC02080FF; // strip boundary row 2048
        raw8k[8191 * 8192 + 8191] = 0xFF123456; // bottom-right pixel

        env.resetRecording(4, 17, 3, 5);
        final long packed = DirectTextureUploadBridge.tryUploadFlagged(env.shader, env.gl3, img8k, true, false);
        assertEquals(1L, packed & 1L);
        final int texId = (int) (packed >>> 32);
        assertTrue(texId > 0);
        assertEquals(stripedBefore + 1L, DirectTextureUploadBridge.stripedGl3UploadCount());
        assertEquals(oversizedBefore, DirectTextureUploadBridge.oversizedDirectAllocationCount());
        assertFalse(DirectTextureUploadBridge.hasTransientFallbackBuffer());
        assertEquals(4, env.subImages.size());
        assertEquals(List.of(0, 2048, 4096, 6144), env.subImageYOffsets);
        assertEquals(List.of(2048, 2048, 2048, 2048), env.subImageHeights);
        assertEquals(List.of(8192 * 8192 * 4L), env.allocSizes);
        // Unpack state must be restored to prior non-default values.
        assertEquals(4, env.unpackAlignment);
        assertEquals(17, env.unpackRowLength);
        assertEquals(3, env.unpackSkipRows);
        assertEquals(5, env.unpackSkipPixels);
        assertEquals(DirectTextureUploadBridge.GL_LINEAR_MIPMAP_LINEAR, env.minFilter);
        assertEquals(DirectTextureUploadBridge.GL_LINEAR, env.magFilter);
        assertEquals(DirectTextureUploadBridge.GL_CLAMP_TO_EDGE, env.wrapS);
        assertEquals(DirectTextureUploadBridge.GL_CLAMP_TO_EDGE, env.wrapT);
        assertTrue(env.mipmapGenerated);
        assertEquals(texId, env.boundTexture);

        // Verify flipY=true + bottom/right zero-padding parity across strip boundary (> 64 MiB: 4800x3600 = 69.12 MiB).
        final BufferedImage flippedSrc = new BufferedImage(4800, 3600, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < flippedSrc.getHeight(); y++) {
            final int a = (y * 13 + 7) & 0xFF;
            for (int x = 0; x < flippedSrc.getWidth(); x += 17) {
                flippedSrc.setRGB(x, y, (a << 24) | ((x * 5) & 0xFF) << 16 | ((y * 3) & 0xFF) << 8 | ((x + y) & 0xFF));
            }
        }
        env.resetRecording(1, 0, 0, 0);
        final long flipPacked =
                DirectTextureUploadBridge.tryUploadFlagged(env.shader, env.gl3, flippedSrc, false, true);
        assertEquals(1L, flipPacked & 1L);
        assertEquals(oversizedBefore, DirectTextureUploadBridge.oversizedDirectAllocationCount());
        assertArrayEquals(
                referencePremulRgba(flippedSrc, 4800, 3600, true), reassembleStrips(env.subImages, 4800, 3600));

        // Also verify tryUploadMipmapAligned padding (>64 MiB: 4801x3601 aligned to 16 -> 4816x3616 = 69.6 MiB).
        final BufferedImage mipSrc = new BufferedImage(4801, 3601, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 3601; y += 7) {
            mipSrc.setRGB(0, y, 0xA0306090);
            mipSrc.setRGB(4800, y, 0xFF102030);
        }
        env.resetRecording(1, 0, 0, 0);
        final int[] alignOut = new int[3];
        assertTrue(DirectTextureUploadBridge.tryUploadMipmapAligned(
                env.shader, env.gl3, mipSrc, BufferedImage.TYPE_INT_ARGB, 16, alignOut));
        assertEquals(4816, alignOut[1]);
        assertEquals(3616, alignOut[2]);
        assertEquals(oversizedBefore, DirectTextureUploadBridge.oversizedDirectAllocationCount());
        assertArrayEquals(referencePremulRgba(mipSrc, 4816, 3616, false), reassembleStrips(env.subImages, 4816, 3616));
    }

    @Test
    void stripedGl3ExceptionCleansTextureAndNonGl3FallbackReusesThenClearsSoftRef() throws Exception {
        DirectTextureUploadBridge.clearTransientFallbackBuffer();
        final FakeJoglEnv env = FakeJoglEnv.create(true);
        env.throwOnSubImage = true;
        final BufferedImage imgOver64 = new BufferedImage(4800, 3600, BufferedImage.TYPE_INT_ARGB);
        env.resetRecording(8, 21, 2, 1);
        final RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> DirectTextureUploadBridge.tryUploadFlagged(env.shader, env.gl3, imgOver64, false, false));
        assertTrue(failure.getMessage().contains("injected glTexSubImage2D failure"));
        assertFalse(env.deletedTextures.isEmpty(), "Failed striped upload must delete generated texture ID");
        assertEquals(8, env.unpackAlignment);
        assertEquals(21, env.unpackRowLength);
        assertEquals(2, env.unpackSkipRows);
        assertEquals(1, env.unpackSkipPixels);

        // Non-GL3 fallback holds at most one SoftReference>64 MiB buffer, reuses it across pages, and clears on
        // ModelOpenFlowBridge.endDocumentLoad().
        final FakeJoglEnv nonGl3 = FakeJoglEnv.create(false);
        final long allocBefore = DirectTextureUploadBridge.oversizedDirectAllocationCount();
        final long reuseBefore = DirectTextureUploadBridge.fallbackBufferReuseCount();
        final long p1 = DirectTextureUploadBridge.tryUploadFlagged(nonGl3.shader, nonGl3.gl3, imgOver64, false, false);
        assertEquals(1L, p1 & 1L);
        assertEquals(allocBefore + 1L, DirectTextureUploadBridge.oversizedDirectAllocationCount());
        assertTrue(DirectTextureUploadBridge.hasTransientFallbackBuffer());

        final long p2 = DirectTextureUploadBridge.tryUploadFlagged(nonGl3.shader, nonGl3.gl3, imgOver64, false, false);
        assertEquals(1L, p2 & 1L);
        assertEquals(allocBefore + 1L, DirectTextureUploadBridge.oversizedDirectAllocationCount());
        assertEquals(reuseBefore + 1L, DirectTextureUploadBridge.fallbackBufferReuseCount());

        ModelOpenFlowBridge.endDocumentLoad();
        assertFalse(DirectTextureUploadBridge.hasTransientFallbackBuffer());
    }

    private static byte[] reassembleStrips(final List<byte[]> strips, final int width, final int height) {
        final byte[] out = new byte[width * height * 4];
        int offset = 0;
        for (final byte[] strip : strips) {
            System.arraycopy(strip, 0, out, offset, strip.length);
            offset += strip.length;
        }
        assertEquals(out.length, offset);
        return out;
    }

    private static final class FakeJoglEnv {
        final Object gl3;
        final Object shader;
        final List<Long> allocSizes = new ArrayList<>();
        final List<byte[]> subImages = new ArrayList<>();
        final List<Integer> subImageYOffsets = new ArrayList<>();
        final List<Integer> subImageHeights = new ArrayList<>();
        final List<Integer> deletedTextures = new ArrayList<>();
        int unpackAlignment = 4;
        int unpackRowLength;
        int unpackSkipRows;
        int unpackSkipPixels;
        int minFilter;
        int magFilter;
        int wrapS;
        int wrapT;
        int boundTexture;
        boolean mipmapGenerated;
        boolean throwOnSubImage;
        final AtomicInteger nextTexId = new AtomicInteger(100);

        private FakeJoglEnv(final Object gl3, final Object shader) {
            this.gl3 = gl3;
            this.shader = shader;
        }

        void resetRecording(final int align, final int rowLen, final int skipRows, final int skipPixels) {
            allocSizes.clear();
            subImages.clear();
            subImageYOffsets.clear();
            subImageHeights.clear();
            deletedTextures.clear();
            unpackAlignment = align;
            unpackRowLength = rowLen;
            unpackSkipRows = skipRows;
            unpackSkipPixels = skipPixels;
            minFilter = 0;
            magFilter = 0;
            wrapS = 0;
            wrapT = 0;
            boundTexture = 0;
            mipmapGenerated = false;
        }

        static FakeJoglEnv create(final boolean isGl3Mode) throws Exception {
            final Map<String, byte[]> stubClasses = buildJoglStubs();
            final ClassLoader stubLoader = new ClassLoader(DirectTextureUploadParityTest.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            final byte[] bytes = stubClasses.get(name);
                            if (bytes != null) {
                                loaded = defineClass(name, bytes, 0, bytes.length);
                            } else {
                                return super.loadClass(name, resolve);
                            }
                        }
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                }
            };
            final Class<?> gl3Class = stubLoader.loadClass("com.jogamp.opengl.GL3");
            final Class<?> glProfileClass = stubLoader.loadClass("com.jogamp.opengl.GLProfile");
            final Class<?> textureClass = stubLoader.loadClass("com.jogamp.opengl.util.texture.Texture");
            final Class<?> textureIoClass = stubLoader.loadClass("com.jogamp.opengl.util.texture.TextureIO");
            final Class<?> shaderClass = stubLoader.loadClass("test.FakeShader");

            final FakeJoglEnv[] holder = new FakeJoglEnv[1];
            final Object glProfile = glProfileClass.getConstructor().newInstance();
            final Object texInstance = textureClass.getConstructor().newInstance();
            final Object gl3Proxy = java.lang.reflect.Proxy.newProxyInstance(
                    stubLoader, new Class<?>[] {gl3Class}, (proxy, method, args) -> {
                        final FakeJoglEnv env = holder[0];
                        return switch (method.getName()) {
                            case "getGLProfile" -> glProfile;
                            case "isGL3" -> isGl3Mode;
                            case "isGL2GL3" -> true;
                            case "isGLcore" -> false;
                            case "isGL2" -> false;
                            case "isExtensionAvailable" -> true;
                            case "glGenTextures" -> {
                                final int[] ids = (int[]) args[1];
                                ids[(int) args[2]] = env.nextTexId.getAndIncrement();
                                yield null;
                            }
                            case "glDeleteTextures" -> {
                                final int[] ids = (int[]) args[1];
                                env.deletedTextures.add(ids[(int) args[2]]);
                                yield null;
                            }
                            case "glBindTexture" -> {
                                env.boundTexture = (int) args[1];
                                yield null;
                            }
                            case "glTexImage2D" -> {
                                env.allocSizes.add((long) (int) args[3] * (int) args[4] * 4L);
                                yield null;
                            }
                            case "glTexSubImage2D" -> {
                                if (env.throwOnSubImage) {
                                    throw new RuntimeException("injected glTexSubImage2D failure");
                                }
                                final ByteBuffer buf = (ByteBuffer) args[8];
                                final byte[] copy = new byte[buf.remaining()];
                                buf.slice().get(copy);
                                env.subImageYOffsets.add((int) args[3]);
                                env.subImageHeights.add((int) args[5]);
                                env.subImages.add(copy);
                                yield null;
                            }
                            case "glGetIntegerv" -> {
                                final int pname = (int) args[0];
                                final int[] out = (int[]) args[1];
                                final int off = (int) args[2];
                                out[off] = switch (pname) {
                                    case DirectTextureUploadBridge.GL_UNPACK_ALIGNMENT -> env.unpackAlignment;
                                    case DirectTextureUploadBridge.GL_UNPACK_ROW_LENGTH -> env.unpackRowLength;
                                    case DirectTextureUploadBridge.GL_UNPACK_SKIP_ROWS -> env.unpackSkipRows;
                                    case DirectTextureUploadBridge.GL_UNPACK_SKIP_PIXELS -> env.unpackSkipPixels;
                                    default -> 0;
                                };
                                yield null;
                            }
                            case "glPixelStorei" -> {
                                final int pname = (int) args[0];
                                final int val = (int) args[1];
                                switch (pname) {
                                    case DirectTextureUploadBridge.GL_UNPACK_ALIGNMENT -> env.unpackAlignment = val;
                                    case DirectTextureUploadBridge.GL_UNPACK_ROW_LENGTH -> env.unpackRowLength = val;
                                    case DirectTextureUploadBridge.GL_UNPACK_SKIP_ROWS -> env.unpackSkipRows = val;
                                    case DirectTextureUploadBridge.GL_UNPACK_SKIP_PIXELS -> env.unpackSkipPixels = val;
                                    default -> {}
                                }
                                yield null;
                            }
                            case "glTexParameteri" -> {
                                final int pname = (int) args[1];
                                final int val = (int) args[2];
                                switch (pname) {
                                    case DirectTextureUploadBridge.GL_TEXTURE_MIN_FILTER -> env.minFilter = val;
                                    case DirectTextureUploadBridge.GL_TEXTURE_MAG_FILTER -> env.magFilter = val;
                                    case DirectTextureUploadBridge.GL_TEXTURE_WRAP_S -> env.wrapS = val;
                                    case DirectTextureUploadBridge.GL_TEXTURE_WRAP_T -> env.wrapT = val;
                                    default -> {}
                                }
                                yield null;
                            }
                            case "glGenerateMipmap" -> {
                                env.mipmapGenerated = true;
                                yield null;
                            }
                            default -> null;
                        };
                    });
            textureIoClass.getField("NEXT_TEXTURE").set(null, texInstance);
            final FakeJoglEnv env =
                    new FakeJoglEnv(gl3Proxy, shaderClass.getConstructor().newInstance());
            holder[0] = env;
            return env;
        }

        private static Map<String, byte[]> buildJoglStubs() {
            final Map<String, byte[]> map = new HashMap<>();
            map.put("com.jogamp.opengl.GLProfile", emitSimpleClass("com/jogamp/opengl/GLProfile", null));
            map.put(
                    "com.jogamp.opengl.util.texture.TextureData$Flusher",
                    emitInterface("com/jogamp/opengl/util/texture/TextureData$Flusher"));
            map.put("com.jogamp.opengl.GL", emitGlInterface());
            map.put("com.jogamp.opengl.GL3", emitInterface("com/jogamp/opengl/GL3", "com/jogamp/opengl/GL"));
            map.put("com.jogamp.opengl.util.texture.TextureData", emitTextureDataStub());
            map.put("com.jogamp.opengl.util.texture.Texture", emitTextureStub());
            map.put("com.jogamp.opengl.util.texture.TextureIO", emitTextureIoStub());
            map.put("test.FakeShader", emitFakeShaderStub());
            return map;
        }

        private static byte[] emitSimpleClass(final String internalName, final String[] ifaces) {
            final org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cw.visit(
                    org.objectweb.asm.Opcodes.V17,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    internalName,
                    null,
                    "java/lang/Object",
                    ifaces);
            final var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
            mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitInterface(final String internalName, final String... parents) {
            final org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cw.visit(
                    org.objectweb.asm.Opcodes.V17,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC
                            | org.objectweb.asm.Opcodes.ACC_INTERFACE
                            | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                    internalName,
                    null,
                    "java/lang/Object",
                    parents);
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitGlInterface() {
            final org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cw.visit(
                    org.objectweb.asm.Opcodes.V17,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC
                            | org.objectweb.asm.Opcodes.ACC_INTERFACE
                            | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                    "com/jogamp/opengl/GL",
                    null,
                    "java/lang/Object",
                    null);
            final String[][] methods = {
                {"getGLProfile", "()Lcom/jogamp/opengl/GLProfile;"},
                {"isGL3", "()Z"},
                {"isGL2GL3", "()Z"},
                {"isGLcore", "()Z"},
                {"isGL2", "()Z"},
                {"isExtensionAvailable", "(Ljava/lang/String;)Z"},
                {"glGenTextures", "(I[II)V"},
                {"glDeleteTextures", "(I[II)V"},
                {"glBindTexture", "(II)V"},
                {"glEnable", "(I)V"},
                {"glGenerateMipmap", "(I)V"},
                {"glGetIntegerv", "(I[II)V"},
                {"glPixelStorei", "(II)V"},
                {"glTexParameteri", "(III)V"},
                {"glTexImage2D", "(IIIIIIIILjava/nio/Buffer;)V"},
                {"glTexSubImage2D", "(IIIIIIIILjava/nio/Buffer;)V"},
            };
            for (final String[] sig : methods) {
                cw.visitMethod(
                                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                                sig[0],
                                sig[1],
                                null,
                                null)
                        .visitEnd();
            }
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitTextureDataStub() {
            final org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cw.visit(
                    org.objectweb.asm.Opcodes.V17,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    "com/jogamp/opengl/util/texture/TextureData",
                    null,
                    "java/lang/Object",
                    null);
            final var mv = cw.visitMethod(
                    org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    "<init>",
                    "(Lcom/jogamp/opengl/GLProfile;IIIIIIZZZLjava/nio/Buffer;Lcom/jogamp/opengl/util/texture/TextureData$Flusher;)V",
                    null,
                    null);
            mv.visitCode();
            mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
            mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
            mv.visitMaxs(1, 13);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitTextureStub() {
            final org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cw.visit(
                    org.objectweb.asm.Opcodes.V17,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    "com/jogamp/opengl/util/texture/Texture",
                    null,
                    "java/lang/Object",
                    null);
            final var init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
            init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
            init.visitMaxs(1, 1);
            init.visitEnd();
            final var auto = cw.visitMethod(
                    org.objectweb.asm.Opcodes.ACC_PUBLIC, "isUsingAutoMipmapGeneration", "()Z", null, null);
            auto.visitCode();
            auto.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
            auto.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            auto.visitMaxs(1, 1);
            auto.visitEnd();
            final var en = cw.visitMethod(
                    org.objectweb.asm.Opcodes.ACC_PUBLIC, "enable", "(Lcom/jogamp/opengl/GL;)V", null, null);
            en.visitCode();
            en.visitInsn(org.objectweb.asm.Opcodes.RETURN);
            en.visitMaxs(1, 2);
            en.visitEnd();
            final var bind = cw.visitMethod(
                    org.objectweb.asm.Opcodes.ACC_PUBLIC, "bind", "(Lcom/jogamp/opengl/GL;)V", null, null);
            bind.visitCode();
            bind.visitInsn(org.objectweb.asm.Opcodes.RETURN);
            bind.visitMaxs(1, 2);
            bind.visitEnd();
            final var getObj0 =
                    cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "getTextureObject", "()I", null, null);
            getObj0.visitCode();
            getObj0.visitIntInsn(org.objectweb.asm.Opcodes.BIPUSH, 77);
            getObj0.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            getObj0.visitMaxs(1, 1);
            getObj0.visitEnd();
            final var getObj1 = cw.visitMethod(
                    org.objectweb.asm.Opcodes.ACC_PUBLIC, "getTextureObject", "(Lcom/jogamp/opengl/GL;)I", null, null);
            getObj1.visitCode();
            getObj1.visitIntInsn(org.objectweb.asm.Opcodes.BIPUSH, 77);
            getObj1.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            getObj1.visitMaxs(1, 2);
            getObj1.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitTextureIoStub() {
            final org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cw.visit(
                    org.objectweb.asm.Opcodes.V17,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    "com/jogamp/opengl/util/texture/TextureIO",
                    null,
                    "java/lang/Object",
                    null);
            cw.visitField(
                            org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                            "NEXT_TEXTURE",
                            "Lcom/jogamp/opengl/util/texture/Texture;",
                            null,
                            null)
                    .visitEnd();
            final var mv = cw.visitMethod(
                    org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                    "newTexture",
                    "(Lcom/jogamp/opengl/GL;Lcom/jogamp/opengl/util/texture/TextureData;)Lcom/jogamp/opengl/util/texture/Texture;",
                    null,
                    null);
            mv.visitCode();
            mv.visitFieldInsn(
                    org.objectweb.asm.Opcodes.GETSTATIC,
                    "com/jogamp/opengl/util/texture/TextureIO",
                    "NEXT_TEXTURE",
                    "Lcom/jogamp/opengl/util/texture/Texture;");
            mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
            mv.visitMaxs(1, 2);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] emitFakeShaderStub() {
            final org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cw.visit(
                    org.objectweb.asm.Opcodes.V17,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    "test/FakeShader",
                    null,
                    "java/lang/Object",
                    null);
            final var init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
            init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
            init.visitMaxs(1, 1);
            init.visitEnd();
            final var err = cw.visitMethod(
                    org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    "a",
                    "(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I",
                    null,
                    null);
            err.visitCode();
            err.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
            err.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            err.visitMaxs(1, 4);
            err.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }
    }

    private static int[] drawReferenceArgb(final BufferedImage src) {
        final BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = dst.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst.getRGB(0, 0, src.getWidth(), src.getHeight(), null, 0, src.getWidth());
    }

    private static byte[] referencePremulRgba(
            final BufferedImage src, final int dstW, final int dstH, final boolean flipY) {
        final ComponentColorModel rgbaPremul = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB),
                new int[] {8, 8, 8, 8},
                true,
                true,
                Transparency.TRANSLUCENT,
                DataBuffer.TYPE_BYTE);
        final BufferedImage canvas =
                new BufferedImage(rgbaPremul, rgbaPremul.createCompatibleWritableRaster(dstW, dstH), true, null);
        final Graphics2D g = canvas.createGraphics();
        g.setComposite(AlphaComposite.Src);
        if (flipY) {
            g.translate(0.0, src.getHeight());
            g.scale(1.0, -1.0);
        }
        g.drawImage(src, 0, 0, null);
        g.dispose();
        final Raster r = canvas.getRaster();
        return ((java.awt.image.DataBufferByte) r.getDataBuffer()).getData();
    }
}
