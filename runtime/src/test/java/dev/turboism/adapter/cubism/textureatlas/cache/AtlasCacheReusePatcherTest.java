package dev.turboism.adapter.cubism.textureatlas.cache;

import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.texture.modelImage.CModelImage;
import com.live2d.graphics.CImageResource;
import com.live2d.graphics.CWritableImage;
import com.live2d.type.CAffine;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape-gate and rewrite tests for {@link AtlasCacheReusePatcher}.
 *
 * <p>The fixture {@code CTextureAtlas} is compiled into the test source set with the
 * reviewed method shape; the end-to-end case defines the patched class in a child loader
 * and drives {@code updateTexture} reflectively, proving the guard both skips a redundant
 * rebuild and keeps the cache-manager registration tail intact.</p>
 */
final class AtlasCacheReusePatcherTest {

    private static final String ATLAS =
        "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas";

    @Test
    void acceptsTheReviewedFixtureShape() throws Exception {
        final Map<String, Integer> anchors =
            AtlasCacheReusePatcher.inspect(fixtureBytes(ATLAS + ".class")).counts();
        assertEquals(1, anchors.get("method"));
        assertEquals(1, anchors.get("setup"));
        assertEquals(1, anchors.get("managerRegister"));
        assertEquals(1, anchors.get("getManager"));
        assertEquals(1, anchors.get("getCache"));
    }

    @Test
    void rejectsAClassWithoutTheTargetMethod() throws Exception {
        final byte[] foreign = fixtureBytes("com/live2d/type/CModelImageGuid.class");
        assertThrows(AtlasCacheReusePatcher.NotApplicable.class,
            () -> AtlasCacheReusePatcher.patch(foreign));
    }

    @Test
    void rewrittenBodyCallsTheDelegateAndKeepsTheRegisterTail() throws Exception {
        final byte[] patched = AtlasCacheReusePatcher.patch(fixtureBytes(ATLAS + ".class"));
        final Map<String, Integer> calls = new java.util.LinkedHashMap<>();
        new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String desc, final String sig,
                                             final String[] ex) {
                if (!name.equals("updateTexture")
                        || !desc.equals(AtlasCacheReusePatcher.METHOD_DESC)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(final int op, final String owner,
                                                final String nm, final String d,
                                                final boolean itf) {
                        calls.merge(owner + "." + nm, 1, Integer::sum);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        assertEquals(1, calls.getOrDefault(
            "dev/turboism/bootstrap/atlascache/AtlasCacheReuseDelegate.tryReuse", 0));
        assertEquals(1, calls.getOrDefault(
            "dev/turboism/bootstrap/atlascache/AtlasCacheReuseDelegate.rebuilt", 0));
        assertEquals(1, calls.getOrDefault(
            "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas.setupCacheImage$cubism",
            0));
        assertEquals(1, calls.getOrDefault(
            "com/live2d/graphics/cachedImage/CCachedImageManager.a", 0));
    }

    @Test
    void patchedClassSkipsAnUnchangedRebuildEndToEnd() throws Exception {
        final byte[] patched = AtlasCacheReusePatcher.patch(fixtureBytes(ATLAS + ".class"));
        // The inner entry class must live in the same loader: its ctor signature references
        // CTextureAtlas, which would otherwise resolve to the parent's unpatched class.
        final PatchLoader loader = new PatchLoader(getClass().getClassLoader());
        loader.define(ATLAS.replace('/', '.') + "$ModelImageEntry",
            fixtureBytes(ATLAS + "$ModelImageEntry.class"));
        final Class<?> patchedAtlas = loader.define(ATLAS.replace('/', '.'), patched);

        // Drive the patched class entirely by reflection; fixture support types resolve
        // from the parent loader while the patched atlas class is the child-defined one.
        final CModelSource source = new CModelSource();
        final CModelImage image = new CModelImage(() -> new CImageResource(
            new CWritableImage(tile(0xFF204060))));
        source.getTextureManager().put(image);
        final Object atlas = patchedAtlas
            .getConstructor(CModelSource.class, int.class, int.class)
            .newInstance(source, 64, 64);
        final Method addEntry = patchedAtlas.getMethod("addEntry",
            com.live2d.type.CModelImageGuid.class, CAffine.class);
        addEntry.invoke(atlas, image.getGuid(),
            new CAffine(AffineTransform.getTranslateInstance(4, 4)));
        final Method update = patchedAtlas.getMethod("updateTexture",
            boolean.class, boolean.class, com.live2d.util.a.a.class);
        final Method setupCalls = patchedAtlas.getMethod("setupCalls");
        final Method registrations = patchedAtlas.getMethod("getCachedImageManager");
        final Method cachedImage = patchedAtlas.getMethod("getCachedAtlasImage");

        update.invoke(atlas, true, true, null);
        assertEquals(1, setupCalls.invoke(atlas), "first call must rebuild");
        final Object firstCache = cachedImage.invoke(atlas);
        assertNotNull(firstCache);

        update.invoke(atlas, true, true, null);
        assertEquals(1, setupCalls.invoke(atlas),
            "unchanged inputs must skip the rebuild");
        assertTrue(cachedImage.invoke(atlas) == firstCache,
            "the recorded cache object stays installed");
        final Object manager = registrations.invoke(atlas);
        assertEquals(2, manager.getClass().getMethod("registrations").invoke(manager),
            "the register tail runs on the skipped call too");

        image.reset();
        update.invoke(atlas, true, true, null);
        assertEquals(2, setupCalls.invoke(atlas),
            "a content change forces a rebuild");

        update.invoke(atlas, false, true, null);
        assertEquals(2, setupCalls.invoke(atlas),
            "updateTexture(false) never reaches the rebuild");
    }

    /** Defines the patched class itself; every other type delegates to the parent. */
    private static final class PatchLoader extends ClassLoader {
        PatchLoader(final ClassLoader parent) {
            super(parent);
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static BufferedImage tile(final int argb) {
        final BufferedImage t = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) t.setRGB(x, y, argb);
        return t;
    }

    private static byte[] fixtureBytes(final String resource) throws Exception {
        try (var in = AtlasCacheReusePatcherTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "missing fixture resource " + resource);
            return in.readAllBytes();
        }
    }
}
