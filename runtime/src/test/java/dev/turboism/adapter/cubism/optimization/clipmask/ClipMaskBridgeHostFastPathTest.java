package dev.turboism.adapter.cubism.optimization.clipmask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives both clipping-mask bridges against a compiled mirror of the reviewed 5.3 host.
 *
 * <p>Camera zoom and pan re-enter the whole mask path every frame, so these tests pin the two
 * properties the fast path must keep: the per-frame memo removes the repeated component walks,
 * viewport driver reads and vertex scans, and the frame boundary drops everything measured with
 * the previous camera so a pan is never cleared against stale screen bounds.</p>
 */
final class ClipMaskBridgeHostFastPathTest {

    private static final int GL_VIEWPORT = 0x0BA2;
    private static final int GL_SCISSOR_BOX = 0x0C10;
    private static final int GL_SCISSOR_TEST = 0x0C11;
    private static final int GL_COLOR_BUFFER_BIT = 0x4000;

    @TempDir
    Path root;

    private URLClassLoader loader;
    private Object host;
    private ClipMaskDeduplicationBridge dedupe;
    private ClipMaskScissorBridge scissor;

    @BeforeEach
    void setUp() throws Exception {
        installHost(true);
    }

    /** Compiles and installs the fixture host; {@code renderUnit} mirrors the 5.3 material setters. */
    private void installHost(final boolean renderUnit) throws Exception {
        closeHost();
        loader = host(renderUnit);
        host = loader.loadClass("fixture.Host").getConstructor().newInstance();
        dedupe = new ClipMaskDeduplicationBridge(loader, "5.3.04");
        scissor = new ClipMaskScissorBridge(loader, "5.3.04");
        dedupe.attachScissorBridge(scissor);
        dedupe.install();
        scissor.install();
    }

    private void closeHost() throws Exception {
        if (dedupe != null) dedupe.close();
        if (scissor != null) scissor.close();
        if (loader != null) loader.close();
        dedupe = null;
        scissor = null;
        loader = null;
    }

    @AfterEach
    void tearDown() throws Exception {
        closeHost();
    }

    @Test
    void dedupeHitReusesTheMaskTextureAndHoldsTheLeaseUntilTheFrameEnds() throws Exception {
        final Object renderSystem = call("renderSystem");
        final Object rendererA = call("renderer", 0);
        final Object rendererB = call("renderer", 1);

        // First renderer of the mask set renders the mask pass (the scissor bridge is not
        // attached here, so this test only exercises the dedupe consult).
        assertFalse(entry().test(renderSystem, rendererA));
        call("bindLease", renderSystem);
        call("glClear", GL_COLOR_BUFFER_BIT);
        exit().accept(renderSystem, rendererA);
        assertEquals(1, field("clearCalls"));
        assertEquals(1L, dedupe.snapshot().get("dedupeMisses"));

        // Second renderer of the same mask set is served from the cached attachment.
        assertTrue(entry().test(renderSystem, rendererB));
        assertEquals(1, field("clearCalls"), "a dedupe hit must not clear the mask attachment again");
        assertEquals(2, count("clipWalks"), "each renderer's component list is walked once per frame");
        assertNotNull(call("leaseField"), "the cached lease must be installed on the render system");
        assertEquals(1L, dedupe.snapshot().get("dedupeHits"));
        assertEquals(1.0f / 1920.0f, (float) call("materialScaleX", 1), 1.0e-6f);
        assertEquals(0.0f, (float) call("materialInvert", 1), 1.0e-6f);

        // endClipping must not return a lease the cache still holds.
        assertTrue(release().test(renderSystem));
        assertNull(call("leaseField"));

        // The frame boundary returns every held lease to the pool exactly once.
        frame().run();
        assertEquals(1, count("poolReleases"));
        assertEquals(1L, dedupe.snapshot().get("frames"));
        assertEquals(0L, dedupe.snapshot().get("cachedRenderers"));
    }

    @Test
    void olderHostWithoutTheRenderUnitParameterStillAppliesTheCachedMask() throws Exception {
        installHost(false);
        final Object renderSystem = call("renderSystem");
        final Object rendererA = call("renderer", 0);
        final Object rendererB = call("renderer", 1);

        pass(renderSystem, rendererA);
        assertEquals(1L, dedupe.snapshot().get("dedupeMisses"));
        assertTrue(entry().test(renderSystem, rendererB), "the 5.0-5.2 setter shape still dedupes");
        assertEquals(1.0f / 1080.0f, (float) call("materialScaleY", 1), 1.0e-6f);
        assertEquals(1.0f / 1920.0f, (float) call("materialScaleX", 1), 1.0e-6f);
    }

    @Test
    void scissorClearReadsTheViewportAndEachMeshOncePerFrame() throws Exception {
        final Object renderSystem = call("renderSystem");
        final Object rendererA = call("renderer", 0);

        // First use of a pooled attachment keeps the original full clear.
        pass(renderSystem, rendererA);
        assertEquals(0, field("scissorCalls"), "the untracked attachment is cleared in full");
        frame().run();

        // From the second frame on the dirty rectangle is known and the clear is scissored.
        pass(renderSystem, rendererA);
        assertEquals(2, field("viewportQueries"), "one viewport read serves each frame");
        assertEquals(4, count("meshReads"), "each mask silhouette is scanned once per frame");
        assertEquals(1, field("scissorCalls"));
        assertEquals(1, field("enableCalls"));
        assertEquals(1, field("disableCalls"), "the previous scissor state is restored after the clear");
        assertEquals(1L, scissor.snapshot().get("scissorPasses"));

        // A different mask set in the same frame reuses the memoized silhouette.
        pass(renderSystem, call("renderer", 2));
        assertEquals(2, field("viewportQueries"), "the second pass reuses the frame's viewport");
        assertEquals(4, count("meshReads"), "the shared mask mesh is not scanned again");
        assertEquals(2, field("scissorCalls"));
        assertEquals(2L, scissor.snapshot().get("scissorPasses"));
    }

    @Test
    void cameraPanClearsAgainstTheNewScreenBoundsOnTheNextFrame() throws Exception {
        final Object renderSystem = call("renderSystem");
        final Object rendererA = call("renderer", 0);

        pass(renderSystem, rendererA);
        frame().run();
        pass(renderSystem, rendererA);
        final int[] before = scissorRect();
        frame().run();

        // A pan moves every mask on screen; stale frame data would keep the old rectangle.
        call("pan", 120.0f);
        pass(renderSystem, rendererA);
        final int[] after = scissorRect();

        assertEquals(3, field("viewportQueries"), "the viewport cache is frame-scoped");
        assertEquals(6, count("meshReads"), "silhouettes are rescanned after the frame boundary");
        assertTrue(after[2] > before[2], "the panned rectangle must extend past the first frame's");
        assertEquals(before[0], after[0], "the union keeps the earlier dirty column");
    }

    @Test
    void maskSetSwapWithinOneFrameIsNeverDedupedAgainstTheOldDigest() throws Exception {
        final Object renderSystem = call("renderSystem");
        final Object rendererA = call("renderer", 0);
        final Object rendererB = call("renderer", 1);

        pass(renderSystem, rendererA);
        assertEquals(1L, dedupe.snapshot().get("dedupeMisses"));

        // The host swaps renderer B's mask set while the frame is still open.
        call("dropMaskFrom", 1);
        assertFalse(entry().test(renderSystem, rendererB), "a changed mask set must render its own pass");
        assertEquals(0L, dedupe.snapshot().get("dedupeHits"));
    }

    @Test
    void hostScissorStateIsReadAndRestoredAroundTheClear() throws Exception {
        final Object renderSystem = call("renderSystem");
        final Object rendererA = call("renderer", 0);
        call("hostScissor", 10, 20, 640, 480, true);

        // The untracked attachment takes the full clear, which must drop the host's scissor
        // and put it back afterwards.
        pass(renderSystem, rendererA);
        assertEquals(1, field("disableCalls"));
        assertEquals(1, field("enableCalls"));
        assertEquals(1, field("scissorCalls"));
        assertEquals(10, field("currentScissorX"));
        assertEquals(20, field("currentScissorY"));
        assertEquals(640, field("currentScissorWidth"));
        assertEquals(480, field("currentScissorHeight"));
        frame().run();

        // The scissored clear narrows the box and restores the host's enabled state.
        pass(renderSystem, rendererA);
        assertEquals(1, field("disableCalls"), "a scissored clear never disables the test");
        assertEquals(3, field("enableCalls"));
        assertEquals(3, field("scissorCalls"));
        assertEquals(10, field("currentScissorX"));
        assertEquals(640, field("currentScissorWidth"));
    }

    /** Runs one full mask pass the way the host does: entry, bracket, clear, restore, exit. */
    private void pass(final Object renderSystem, final Object renderer) throws Exception {
        assertFalse(entry().test(renderSystem, renderer));
        runClear(renderSystem);
        exit().accept(renderSystem, renderer);
    }

    /** Runs the woven clear bracket the way the host calls it: setup, clear, restore. */
    private void runClear(final Object renderSystem) throws Exception {
        call("bindLease", renderSystem);
        final Runnable restore = clear().apply(call("context"));
        assertNotNull(restore, "the scissored clear must hand back its restore action");
        call("glClear", GL_COLOR_BUFFER_BIT);
        restore.run();
    }

    @SuppressWarnings("unchecked")
    private BiPredicate<Object, Object> entry() {
        return (BiPredicate<Object, Object>) System.getProperties().get(ClipMaskDeduplicationBridge.DEDUPE_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    private BiConsumer<Object, Object> exit() {
        return (BiConsumer<Object, Object>) System.getProperties().get(ClipMaskDeduplicationBridge.RECORD_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    private Predicate<Object> release() {
        return (Predicate<Object>) System.getProperties().get(ClipMaskDeduplicationBridge.RELEASE_PROPERTY);
    }

    private Runnable frame() {
        return (Runnable) System.getProperties().get(ClipMaskDeduplicationBridge.FRAME_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    private Function<Object, Runnable> clear() {
        return (Function<Object, Runnable>) System.getProperties().get(ClipMaskScissorBridge.CLEAR_PROPERTY);
    }

    private int[] scissorRect() throws Exception {
        final int x = field("currentScissorX");
        final int y = field("currentScissorY");
        return new int[] {x, y, x + field("currentScissorWidth"), y + field("currentScissorHeight")};
    }

    private int count(final String name) throws Exception {
        return ((Number) call(name)).intValue();
    }

    private Object call(final String name, final Object... arguments) throws Exception {
        for (final var method : host.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(host, arguments);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private int field(final String name) throws Exception {
        return host.getClass().getField(name).getInt(host);
    }

    private URLClassLoader host(final boolean renderUnit) throws Exception {
        final List<String> files = new ArrayList<>();
        source(files, "com/jogamp/opengl/GL.java", """
                package com.jogamp.opengl;
                public interface GL {
                    void glGetIntegerv(int name, int[] values, int offset);
                    boolean glIsEnabled(int name);
                    void glEnable(int name);
                    void glDisable(int name);
                    void glScissor(int x, int y, int width, int height);
                }
                """);
        source(files, "com/jogamp/opengl/GL3.java", """
                package com.jogamp.opengl;
                public interface GL3 extends GL {
                    void glClear(int mask);
                }
                """);
        source(files, "com/live2d/graphics3d/type/GVector2.java", """
                package com.live2d.graphics3d.type;
                public class GVector2 {
                    private final float x;
                    private final float y;
                    public GVector2(float x, float y) { this.x = x; this.y = y; }
                    public float getX() { return x; }
                    public float getY() { return y; }
                }
                """);
        source(files, "com/live2d/graphics3d/type/GMatrix44.java", """
                package com.live2d.graphics3d.type;
                public class GMatrix44 {
                    private final float[] values = new float[16];
                    public GMatrix44() { values[0] = 1; values[5] = 1; values[10] = 1; values[15] = 1; }
                    public float[] a() { return values; }
                    public GMatrix44 scale(float x, float y) { values[0] = x; values[5] = y; return this; }
                }
                """);
        source(files, "com/live2d/graphics3d/texture/GTexture.java", """
                package com.live2d.graphics3d.texture;
                public class GTexture {
                    private final int glTextureName;
                    public GTexture(int glTextureName) { this.glTextureName = glTextureName; }
                    public int getGlTextureName() { return glTextureName; }
                }
                """);
        source(files, "com/live2d/graphics3d/texture/GRenderTexture.java", """
                package com.live2d.graphics3d.texture;
                public class GRenderTexture extends GTexture {
                    private final int width;
                    private final int height;
                    public GRenderTexture(int glTextureName, int width, int height) {
                        super(glTextureName);
                        this.width = width;
                        this.height = height;
                    }
                    public int getWidth() { return width; }
                    public int getHeight() { return height; }
                }
                """);
        source(files, "com/live2d/graphics3d/material/GMaterial.java", materialSource(renderUnit));
        source(files, "com/live2d/graphics3d/mesh/a.java", """
                package com.live2d.graphics3d.mesh;
                public class a {
                    public float[] positions = new float[0];
                    public int step = 2;
                    public float[] getPositions() { fixture.Counters.meshReads++; return positions; }
                    public int getStep() { return step; }
                }
                """);
        source(files, "com/live2d/graphics3d/component/GTransform.java", """
                package com.live2d.graphics3d.component;
                import com.live2d.graphics3d.type.GMatrix44;
                public class GTransform {
                    public final GMatrix44 localToWorld = new GMatrix44();
                    public GMatrix44 getLocalToWorldMatrix() { return localToWorld; }
                }
                """);
        source(files, "com/live2d/graphics3d/component/GClipping.java", """
                package com.live2d.graphics3d.component;
                import com.live2d.graphics3d.entity.GEntity;
                public class GClipping {
                    public java.util.List<GEntity> masks = new java.util.ArrayList<>();
                    public boolean remove;
                    public boolean invert;
                    public int reads;
                    public java.util.List<GEntity> getClippingVaildMasks() { reads++; return masks; }
                    public boolean isRemove() { return remove; }
                    public boolean getInvert() { return invert; }
                }
                """);
        source(files, "com/live2d/graphics3d/component/GMeshContainer.java", """
                package com.live2d.graphics3d.component;
                import com.live2d.graphics3d.mesh.a;
                public class GMeshContainer {
                    public a mesh;
                    public a getMesh() { return mesh; }
                }
                """);
        source(files, "com/live2d/graphics3d/component/AGRenderer.java", """
                package com.live2d.graphics3d.component;
                import com.live2d.graphics3d.entity.GEntity;
                import com.live2d.graphics3d.material.GMaterial;
                public class AGRenderer {
                    public GEntity entity;
                    public GMaterial material;
                    public GEntity getEntity() { return entity; }
                    public GMaterial getCurMaterial() { return material; }
                }
                """);
        source(files, "com/live2d/graphics3d/component/GMeshRenderer.java", """
                package com.live2d.graphics3d.component;
                public class GMeshRenderer extends AGRenderer {
                    public GMeshContainer container;
                    public GMeshContainer getMeshContainer() { return container; }
                }
                """);
        source(files, "com/live2d/graphics3d/entity/Components.java", """
                package com.live2d.graphics3d.entity;
                import java.util.ArrayList;
                import java.util.Iterator;
                import java.util.List;
                public final class Components implements Iterable<Object> {
                    private final List<Object> items = new ArrayList<>();
                    public Components add(Object item) { items.add(item); return this; }
                    public Iterator<Object> iterator() { return items.iterator(); }
                }
                """);
        source(files, "com/live2d/graphics3d/entity/GEntity.java", """
                package com.live2d.graphics3d.entity;
                import com.live2d.graphics3d.component.AGRenderer;
                import com.live2d.graphics3d.component.GTransform;
                public class GEntity {
                    public final Components components = new Components();
                    public AGRenderer renderer;
                    public GTransform transform;
                    public Components getComponents() { fixture.Counters.clipWalks++; return components; }
                    public AGRenderer getRenderer() { return renderer; }
                    public GTransform getTransform() { return transform; }
                }
                """);
        source(files, "com/live2d/graphics3d/GCamera.java", """
                package com.live2d.graphics3d;
                import com.live2d.graphics3d.type.GMatrix44;
                public class GCamera {
                    public final GMatrix44 projection = new GMatrix44();
                    public final GMatrix44 worldToCamera = new GMatrix44();
                    public GMatrix44 getProjectionMatrix() { return projection; }
                    public GMatrix44 getWorldToCameraMatrix() { return worldToCamera; }
                }
                """);
        source(files, "com/live2d/graphics3d/a.java", """
                package com.live2d.graphics3d;
                import com.jogamp.opengl.GL3;
                public class a {
                    public GL3 gl;
                    public GCamera camera;
                    public GL3 a() { return gl; }
                    public GCamera b() { return camera; }
                }
                """);
        source(files, "com/live2d/graphics3d/rendering/a.java", """
                package com.live2d.graphics3d.rendering;
                import com.jogamp.opengl.GL;
                import com.live2d.graphics3d.texture.GRenderTexture;
                public final class a {
                    private final GL a;
                    private final GRenderTexture b;
                    public a(GL gl, GRenderTexture texture) { this.a = gl; this.b = texture; }
                    public GL a() { return a; }
                    public GRenderTexture b() { return b; }
                }
                """);
        source(files, "com/live2d/graphics3d/rendering/b.java", """
                package com.live2d.graphics3d.rendering;
                public final class b {
                    public static final b a = new b();
                    public int releases;
                    private b() {}
                    public void a(a lease) { releases++; }
                }
                """);
        source(files, "com/live2d/graphics3d/rendering/e.java", """
                package com.live2d.graphics3d.rendering;
                public final class e {
                    private a f;
                    public Object leaseField() { return f; }
                    public void leaseField(Object value) { f = (a) value; }
                }
                """);
        source(files, "fixture/Counters.java", """
                package fixture;
                public final class Counters {
                    public static int clipWalks;
                    public static int meshReads;
                    private Counters() {}
                }
                """);
        source(files, "fixture/FixtureGl.java", glSource());
        source(files, "fixture/Host.java", hostSource());
        final Path classes = Files.createDirectories(root.resolve("classes"));
        final List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        args.addAll(files);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        // The fixture host stays isolated: it must not see this module's host fakes or plugins.
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    /** 5.3 declares a trailing {@code Integer} render unit; 5.0-5.2 declare the setters without it. */
    private static String materialSource(final boolean renderUnit) {
        final String unit = renderUnit ? ", Integer unit" : "";
        return """
                package com.live2d.graphics3d.material;
                import com.live2d.graphics3d.texture.GTexture;
                import com.live2d.graphics3d.type.GVector2;
                public class GMaterial {
                    public GTexture mainTexture;
                    public GVector2 clippingMaskScale;
                    public float clippingMaskMin;
                    public float invertClippingMask;
                    public void setTexture(String name, GTexture texture) {
                        if ("_MainTex".equals(name)) mainTexture = texture;
                    }
                    public void setVector2(String name, GVector2 value%s) {
                        if ("clippingMaskScale".equals(name)) clippingMaskScale = value;
                    }
                    public void setFloat(String name, float value%s) {
                        if ("clippingMaskMin".equals(name)) clippingMaskMin = value;
                        if ("invertClippingMask".equals(name)) invertClippingMask = value;
                    }
                }
                """.formatted(unit, unit);
    }

    private static String hostSource() {
        return """
                package fixture;

                import com.jogamp.opengl.GL3;
                import com.live2d.graphics3d.GCamera;
                import com.live2d.graphics3d.component.AGRenderer;
                import com.live2d.graphics3d.component.GClipping;
                import com.live2d.graphics3d.component.GMeshContainer;
                import com.live2d.graphics3d.component.GMeshRenderer;
                import com.live2d.graphics3d.component.GTransform;
                import com.live2d.graphics3d.entity.GEntity;
                import com.live2d.graphics3d.material.GMaterial;
                import com.live2d.graphics3d.mesh.a;
                import com.live2d.graphics3d.texture.GRenderTexture;
                import java.util.ArrayList;
                import java.util.List;

                public final class Host {
                    public int viewportQueries;
                    public int isEnabledQueries;
                    public int enableCalls;
                    public int disableCalls;
                    public int scissorCalls;
                    public int clearCalls;
                    public int clipWalks;
                    public int meshReads;
                    public int currentScissorX;
                    public int currentScissorY;
                    public int currentScissorWidth;
                    public int currentScissorHeight;
                    public boolean scissorEnabled;
                    private int hostScissorX;
                    private int hostScissorY;
                    private int hostScissorWidth;
                    private int hostScissorHeight;

                    public final com.live2d.graphics3d.rendering.e renderSystem =
                            new com.live2d.graphics3d.rendering.e();
                    public final GRenderTexture texture = new GRenderTexture(7, 1920, 1080);
                    public final GCamera camera = new GCamera();
                    public final com.live2d.graphics3d.a context = new com.live2d.graphics3d.a();
                    public final List<GEntity> masks = new ArrayList<>();
                    public final AGRenderer[] renderers = new AGRenderer[3];
                    private final com.live2d.graphics3d.rendering.a lease;

                    public Host() {
                        final GL3 gl = new FixtureGl(this);
                        context.gl = gl;
                        context.camera = camera;
                        lease = new com.live2d.graphics3d.rendering.a(gl, texture);
                        for (int index = 0; index < 2; index++) {
                            final GEntity mask = new GEntity();
                            final GMeshRenderer renderer = new GMeshRenderer();
                            final GMeshContainer container = new GMeshContainer();
                            final a mesh = new a();
                            mesh.positions = new float[] {
                                -0.5f, -0.5f + index * 0.25f, 0.5f, -0.5f + index * 0.25f,
                                0.5f, 0.5f + index * 0.25f, -0.5f, 0.5f + index * 0.25f
                            };
                            container.mesh = mesh;
                            renderer.container = container;
                            renderer.entity = mask;
                            mask.renderer = renderer;
                            mask.transform = new GTransform();
                            masks.add(mask);
                        }
                        renderers[0] = clipped(masks);
                        renderers[1] = clipped(masks);
                        final List<GEntity> single = new ArrayList<>();
                        single.add(masks.get(0));
                        renderers[2] = clipped(single);
                    }

                    private AGRenderer clipped(final List<GEntity> maskSet) {
                        final AGRenderer renderer = new AGRenderer();
                        final GEntity entity = new GEntity();
                        final GClipping clipping = new GClipping();
                        clipping.masks = new ArrayList<>(maskSet);
                        entity.components.add(clipping);
                        renderer.entity = entity;
                        renderer.material = new GMaterial();
                        return renderer;
                    }

                    public Object renderSystem() { return renderSystem; }

                    public Object context() { return context; }

                    public Object renderer(int index) { return renderers[index]; }

                    public Object leaseField() { return renderSystem.leaseField(); }

                    public void bindLease(Object ignored) { renderSystem.leaseField(lease); }

                    public float materialScaleX(int index) {
                        return renderers[index].material.clippingMaskScale.getX();
                    }

                    public float materialScaleY(int index) {
                        return renderers[index].material.clippingMaskScale.getY();
                    }

                    public float materialInvert(int index) {
                        return renderers[index].material.invertClippingMask;
                    }

                    public void dropMaskFrom(int index) {
                        for (final Object component : renderers[index].entity.components) {
                            ((GClipping) component).masks.remove(masks.get(1));
                        }
                    }

                    public void pan(float pixels) {
                        camera.worldToCamera.a()[12] = pixels * 2.0f / 1920.0f;
                    }

                    public int poolReleases() { return com.live2d.graphics3d.rendering.b.a.releases; }

                    public int clipWalks() { return Counters.clipWalks; }

                    public int meshReads() { return Counters.meshReads; }

                    public void hostScissor(int x, int y, int width, int height, boolean enabled) {
                        hostScissorX = x;
                        hostScissorY = y;
                        hostScissorWidth = width;
                        hostScissorHeight = height;
                        scissorEnabled = enabled;
                    }

                    public void glClear(int mask) { clearCalls++; }

                    int hostScissorX() { return hostScissorX; }

                    int hostScissorY() { return hostScissorY; }

                    int hostScissorWidth() { return hostScissorWidth; }

                    int hostScissorHeight() { return hostScissorHeight; }
                }
                """;
    }

    /** Host GL stand-in that counts the driver reads the bridges are allowed to make. */
    private static String glSource() {
        return """
                package fixture;

                import com.jogamp.opengl.GL3;

                final class FixtureGl implements GL3 {
                    private final Host host;
                    FixtureGl(Host host) { this.host = host; }
                    public void glGetIntegerv(int name, int[] values, int offset) {
                        if (name == 0x0BA2) {
                            host.viewportQueries++;
                            values[offset] = 0;
                            values[offset + 1] = 0;
                            values[offset + 2] = 1920;
                            values[offset + 3] = 1080;
                            return;
                        }
                        if (name == 0x0C10) {
                            values[offset] = host.hostScissorX();
                            values[offset + 1] = host.hostScissorY();
                            values[offset + 2] = host.hostScissorWidth();
                            values[offset + 3] = host.hostScissorHeight();
                        }
                    }
                    public boolean glIsEnabled(int name) { host.isEnabledQueries++; return host.scissorEnabled; }
                    public void glEnable(int name) { host.enableCalls++; host.scissorEnabled = true; }
                    public void glDisable(int name) { host.disableCalls++; host.scissorEnabled = false; }
                    public void glScissor(int x, int y, int width, int height) {
                        host.scissorCalls++;
                        host.currentScissorX = x;
                        host.currentScissorY = y;
                        host.currentScissorWidth = width;
                        host.currentScissorHeight = height;
                    }
                    public void glClear(int mask) { host.glClear(mask); }
                }
                """;
    }

    private void source(final List<String> files, final String name, final String content) throws Exception {
        final Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        files.add(path.toString());
    }
}
