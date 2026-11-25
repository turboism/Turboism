package dev.turboism.adapter.cubism.textureatlas.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.HostArtifactTestLoader;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies exact-artifact admission, fail-closed rejection, {@code f$c.c} bake preservation,
 * and interactive overlay/drag/camera bridge behavior across all reviewed Cubism releases.
 */
class AtlasInteractiveRenderArtifactTest {

    private static final String TEST_CUBISM_JAR = "Live2D_Cubism.jar";
    private static final String TEST_CLASS_SUFFIX = ".class";
    private static final String REF_DIR_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_DIR_ENV = "TURBOISM_CUBISM_REF";
    private static final String USER_HOME = System.getProperty("user.home");
    private static final Path LOCAL_ARTIFACTS_ROOT = Path.of(USER_HOME, "cubism-host-artifacts");
    private static final Path LOCAL_REFERENCE_ROOT = Path.of(USER_HOME, ".local", "share", "cubism-reference");
    private static final Path PROTON_CUBISM_JAR_REL =
            Path.of("proton/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/Live2D_Cubism.jar");

    private static final int MUTATION_OPCODE = Opcodes.NOP;
    private static final int CANVAS_WIDTH = 256;
    private static final int CANVAS_HEIGHT = 256;
    private static final int DEEP_ZOOM_SCALE = 128;
    private static final double WHEEL_ZOOM_RATIO = 1.25d;
    private static final double LINKED_ZOOM_TARGET = 1.5d;
    private static final int LINKED_PAN_X = 42;
    private static final int LINKED_PAN_Y = 64;
    private static final int PAN_OFFSET_X = 24;
    private static final int PAN_OFFSET_Y = 18;
    private static final int OFFSCREEN_SHIFT_X = 4096;
    private static final int OFFSCREEN_SHIFT_Y = 4096;
    private static final int TEXT_THROTTLE_WAIT_MS = 65;

    private static final int TRI_SIZE = 48;
    private static final int GRID_COLS = 3;
    private static final int GRID_ROWS = 3;
    private static final int FILL_ARGB = 0x4001FFFF;
    private static final int STROKE_ARGB = 0x20FF0000;
    private static final int BG_PIXEL_RGB = 0xFF336699;

    @Test
    void everyConfiguredReviewedArtifactRewritesVerifiesAndPreservesBakePath() throws Exception {
        int rewritten = 0;
        for (final String version : new java.util.TreeSet<>(ReviewedHostArtifacts.reviewedCubismVersions())) {
            if (!AtlasInteractiveRenderTarget.SUPPORTED_VERSIONS.contains(version)) {
                continue;
            }
            final Path artifact = locateArtifact(version);
            if (artifact == null) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final AtlasInteractiveRenderTarget target =
                    AtlasInteractiveRenderTarget.of(digest).orElseThrow();
            assertEquals(version, target.version());

            try (HostArtifactTestLoader loader = HostArtifactTestLoader.open(artifact.getParent(), null)) {
                for (final AtlasInteractiveRenderTarget.MethodDep dep : target.methodDependencies()) {
                    verifyMethodPresent(
                            Class.forName(dep.owner().replace('/', '.'), false, loader), dep.name(), dep.descriptor());
                }
                for (final AtlasInteractiveRenderTarget.FieldDep dep : target.fieldDependencies()) {
                    verifyFieldPresent(
                            Class.forName(dep.owner().replace('/', '.'), false, loader), dep.name(), dep.descriptor());
                }

                final List<String> owners = target.targetOwners();
                final List<byte[]> references = new ArrayList<>(owners.size());
                final List<Class<?>> hostClasses = new ArrayList<>(owners.size());
                for (final String owner : owners) {
                    references.add(readClassBytes(artifact, owner));
                    hostClasses.add(Class.forName(owner.replace('/', '.'), false, loader));
                }

                final AtlasInteractiveRenderTransformer transformer = new AtlasInteractiveRenderTransformer(
                        loader,
                        artifact,
                        target,
                        references.get(0),
                        references.get(1),
                        references.get(2),
                        references.get(3),
                        references.get(4),
                        references.get(5));
                final ProtectionDomain domain = domain(artifact);

                final Map<String, byte[]> transformedByBinary = new LinkedHashMap<>();
                for (int i = 0; i < owners.size(); i++) {
                    final String owner = owners.get(i);
                    final byte[] transformed =
                            transformer.transform(null, loader, owner, hostClasses.get(i), domain, references.get(i));
                    assertNotNull(
                            transformed, version + " failed transform on " + owner + ": " + transformer.failure());
                    transformedByBinary.put(owner.replace('/', '.'), transformed);
                }

                assertEquals(AtlasInteractiveRenderTarget.EXPECTED_MATCHES, transformer.matches(), version);
                assertEquals(AtlasInteractiveRenderTarget.EXPECTED_SITES, transformer.sites(), version);
                assertNull(transformer.failure(), version);

                // Force JVM verifier execution on every rewritten class in an isolated child loader.
                final InMemoryDefiningLoader frameCheck = new InMemoryDefiningLoader(loader, transformedByBinary);
                for (final String binaryName : transformedByBinary.keySet()) {
                    final Class<?> checked = Class.forName(binaryName, false, frameCheck);
                    assertNotNull(checked.getDeclaredMethods(), version + ": " + binaryName);
                }

                // Confirm f$c.c bake path in draw_exe stays directly wired without overlay property consultation.
                final byte[] rewrittenEditLayer =
                        transformedByBinary.get(AtlasInteractiveRenderTarget.EDIT_LAYER_OWNER.replace('/', '.'));
                assertBakePathUntouched(rewrittenEditLayer, version);

                assertFailClosedRejections(loader, artifact, references, target, domain);

                try (AtlasInteractiveRenderBridge bridge = new AtlasInteractiveRenderBridge(loader, target)) {
                    assertNotNull(bridge.snapshot(), version);
                }
            }
            rewritten++;
            System.out.println("ATLAS_INTERACTIVE_RENDER artifact PASS version=" + version);
        }
        assumeTrue(rewritten > 0, "no reviewed Cubism artifact found locally");
    }

    @Test
    @SuppressWarnings("unchecked")
    void overlaySpriteCachePreservesPixelsAndReusesDuringZoomAndPan() throws Exception {
        final InMemoryDefiningLoader syntheticLoader =
                new InMemoryDefiningLoader(getClass().getClassLoader(), buildSyntheticHostClasses(false));
        final AtlasInteractiveRenderTarget target = syntheticTarget(false);

        try (AtlasInteractiveRenderBridge bridge = new AtlasInteractiveRenderBridge(syntheticLoader, target)) {
            bridge.install();
            final Predicate<Object[]> overlaySlot = (Predicate<Object[]>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.OVERLAY_RENDER_PROPERTY);
            final Predicate<Object[]> wheelSlot = (Predicate<Object[]>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.WHEEL_ZERO_ROBOT_PROPERTY);
            assertNotNull(overlaySlot);
            assertNotNull(wheelSlot);

            final Class<?> editLayerClass = Class.forName(
                    AtlasInteractiveRenderTarget.EDIT_LAYER_OWNER.replace('/', '.'), true, syntheticLoader);
            final Object editLayer = editLayerClass.getDeclaredConstructor().newInstance();
            final ArrayList<Path2D> shapes = buildTriangleGridShapes();
            setField(editLayer, AtlasInteractiveRenderTarget.DRAW_DATA_SHAPES_FIELD, shapes);

            final AffineTransform txBase = AffineTransform.getTranslateInstance(16.0d, 16.0d);
            final BufferedImage expectedImg =
                    new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            drawReferenceOverlay(expectedImg, shapes, txBase);

            final BufferedImage cachedImg = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g2d = cachedImg.createGraphics();
            g2d.setClip(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g2d.setTransform(txBase);
            assertTrue(overlaySlot.test(new Object[] {editLayer, g2d, new AffineTransform(txBase), Boolean.FALSE}));
            g2d.dispose();

            assertEquals(1L, bridge.snapshot().get("overlaySpriteBuilds"));
            assertTrue(countOpaquePixels(expectedImg) > 0);
            assertImagesSimilar(expectedImg, cachedImg);

            // Pan translation reuses compiled raster sprite without rebuilding.
            final AffineTransform txPan =
                    AffineTransform.getTranslateInstance(16.0d + PAN_OFFSET_X, 16.0d + PAN_OFFSET_Y);
            final BufferedImage panImg = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D gPan = panImg.createGraphics();
            gPan.setClip(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
            gPan.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            gPan.setTransform(txPan);
            assertTrue(overlaySlot.test(new Object[] {editLayer, gPan, new AffineTransform(txPan), Boolean.FALSE}));
            gPan.dispose();
            assertEquals(1L, bridge.snapshot().get("overlaySpriteHits"));
            assertEquals(1L, bridge.snapshot().get("overlaySpriteBuilds"));

            // Active wheel zoom reuses recent sprite via GPU/Java2D scale before settle repaint.
            final Class<?> cameraClass =
                    Class.forName(target.generation().cameraOwner().replace('/', '.'), true, syntheticLoader);
            final Object camera = cameraClass.getDeclaredConstructor().newInstance();
            assertTrue(wheelSlot.test(new Object[] {camera, 0, 0, WHEEL_ZOOM_RATIO}));

            final AffineTransform txZoom = AffineTransform.getScaleInstance(WHEEL_ZOOM_RATIO, WHEEL_ZOOM_RATIO);
            final BufferedImage zoomImg = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D gZoom = zoomImg.createGraphics();
            gZoom.setClip(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
            gZoom.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            gZoom.setTransform(txZoom);
            assertTrue(overlaySlot.test(new Object[] {editLayer, gZoom, new AffineTransform(txZoom), Boolean.FALSE}));
            gZoom.dispose();
            assertEquals(1L, bridge.snapshot().get("overlayZoomScaledHits"));

            // Icon pass uses direct frustum-culled vector drawing instead of caching UI sprites.
            final BufferedImage iconImg = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D gIcon = iconImg.createGraphics();
            gIcon.setClip(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
            gIcon.setTransform(txBase);
            assertTrue(overlaySlot.test(new Object[] {editLayer, gIcon, new AffineTransform(txBase), Boolean.TRUE}));
            gIcon.dispose();
            assertEquals(1L, bridge.snapshot().get("overlayDirectDrawn"));

            // Extreme deep zoom exceeds max sprite dimensions and falls back to frustum-culled direct draw.
            final AffineTransform txDeep = AffineTransform.getScaleInstance(DEEP_ZOOM_SCALE, DEEP_ZOOM_SCALE);
            final BufferedImage deepImg = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D gDeep = deepImg.createGraphics();
            gDeep.setClip(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
            gDeep.setTransform(txDeep);
            assertTrue(overlaySlot.test(new Object[] {editLayer, gDeep, new AffineTransform(txDeep), Boolean.FALSE}));
            gDeep.dispose();
            assertEquals(2L, bridge.snapshot().get("overlayDirectDrawn"));

            // Offscreen layer is frustum-culled completely.
            final AffineTransform txOffscreen =
                    AffineTransform.getTranslateInstance(OFFSCREEN_SHIFT_X, OFFSCREEN_SHIFT_Y);
            final BufferedImage offImg = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D gOff = offImg.createGraphics();
            gOff.setClip(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
            gOff.setTransform(txOffscreen);
            assertTrue(
                    overlaySlot.test(new Object[] {editLayer, gOff, new AffineTransform(txOffscreen), Boolean.FALSE}));
            gOff.dispose();
            assertEquals(1L, bridge.snapshot().get("overlayFrustumCulled"));

            // Kill switch disables overlay interception cleanly.
            final String prior = System.getProperty(AtlasInteractiveRenderTarget.ENABLE_PROPERTY);
            try {
                System.setProperty(AtlasInteractiveRenderTarget.ENABLE_PROPERTY, "false");
                assertFalse(
                        overlaySlot.test(new Object[] {editLayer, gOff, new AffineTransform(txBase), Boolean.FALSE}));
            } finally {
                restoreProperty(AtlasInteractiveRenderTarget.ENABLE_PROPERTY, prior);
            }
            assertEquals(0L, bridge.snapshot().get("failures"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void trailingRepaintClipAndDragCoalescingBehaveCorrectly() throws Exception {
        final InMemoryDefiningLoader syntheticLoader =
                new InMemoryDefiningLoader(getClass().getClassLoader(), buildSyntheticHostClasses(true));
        final AtlasInteractiveRenderTarget target = syntheticTarget(true);

        try (AtlasInteractiveRenderBridge bridge = new AtlasInteractiveRenderBridge(syntheticLoader, target)) {
            bridge.install();
            final Predicate<Object> elideTrailing = (Predicate<Object>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.ELIDE_TRAILING_REPAINT_PROPERTY);
            final BiPredicate<Object, Object> clipBg = (BiPredicate<Object, Object>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.CLIP_BG_PROPERTY);
            final Predicate<Object> enterDrag = (Predicate<Object>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.ENTER_DRAG_SYNC_PROPERTY);
            final Predicate<Object> exitDrag = (Predicate<Object>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.EXIT_DRAG_SYNC_PROPERTY);
            final Predicate<Object> skipTransform = (Predicate<Object>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.SKIP_DRAG_TRANSFORM_PROPERTY);
            final Predicate<Object> skipIcon = (Predicate<Object>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.SKIP_DRAG_ICON_PROPERTY);
            final Predicate<Object> skipTable = (Predicate<Object>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.SKIP_DRAG_TABLE_PROPERTY);
            final BiPredicate<Object, Object> applyText = (BiPredicate<Object, Object>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.APPLY_TEXT_PROPERTY);

            // Trailing 500 ms redundant repaint elides cleanly.
            assertTrue(elideTrailing.test(new JPanel()));
            assertEquals(1L, bridge.snapshot().get("trailingRepaintsElided"));

            // Viewport clip skips disjoint draws and sub-blits partial background draws.
            final BufferedImage bgAtlas = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D gFill = bgAtlas.createGraphics();
            gFill.setColor(new Color(BG_PIXEL_RGB, true));
            gFill.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
            gFill.dispose();

            final BufferedImage dest = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D gPartial = dest.createGraphics();
            gPartial.setClip(16, 16, 64, 64);
            assertTrue(clipBg.test(gPartial, bgAtlas));
            gPartial.setClip(OFFSCREEN_SHIFT_X, OFFSCREEN_SHIFT_Y, 32, 32);
            assertTrue(clipBg.test(gPartial, bgAtlas));
            gPartial.dispose();
            assertEquals(1L, bridge.snapshot().get("backgroundDrawsClipped"));
            assertEquals(1L, bridge.snapshot().get("backgroundDrawsSkipped"));
            assertEquals(BG_PIXEL_RGB, dest.getRGB(32, 32));
            assertEquals(0, dest.getRGB(0, 0));

            // Drag coalescing defers table/icon/transform work mid-drag and flushes cleanly on release.
            final Class<?> eventClass = Class.forName(
                    AtlasInteractiveRenderTarget.LAYER_UPDATE_EVENT_OWNER.replace('/', '.'), true, syntheticLoader);
            final Object dragEvent = eventClass.getDeclaredConstructor().newInstance();
            final Object unitPanel = new Object();
            final Object inspector = new Object();
            // Warm up Swing text-field components before Pass 1 so L&F initialization does not skew the throttle
            // window.
            final JTextField xField = new JTextField("0.0");
            final JTextField yField = new JTextField("0.0");

            // Active drag pass 1 allows text fields once, while blocking table/icon/transform work.
            setField(dragEvent, AtlasInteractiveRenderTarget.EVENT_DRAGGING_FIELD, true);
            assertTrue(enterDrag.test(dragEvent));
            assertTrue(skipTransform.test(inspector));
            assertTrue(skipIcon.test(inspector));
            assertTrue(skipTable.test(inspector));

            assertFalse(applyText.test(xField, "10.0"), "first drag pass allows changed text");
            assertTrue(applyText.test(yField, "0.0"), "identical field value is deduplicated");
            assertTrue(exitDrag.test(unitPanel));

            // Immediate active drag pass 2 within throttle window elides all text updates.
            assertTrue(enterDrag.test(dragEvent));
            assertTrue(skipTransform.test(inspector));
            assertTrue(skipIcon.test(inspector));
            assertTrue(skipTable.test(inspector));
            assertTrue(applyText.test(xField, "11.0"), "rapid drag pass throttles text");
            assertTrue(exitDrag.test(unitPanel));

            // Drag pass after throttle interval allows text again.
            Thread.sleep(TEXT_THROTTLE_WAIT_MS);
            assertTrue(enterDrag.test(dragEvent));
            assertFalse(applyText.test(xField, "12.0"), "expired throttle interval admits text update");
            assertTrue(exitDrag.test(unitPanel));

            // Mouse release / undo-redo sets dragging=false so every sync step executes cleanly.
            setField(dragEvent, AtlasInteractiveRenderTarget.EVENT_DRAGGING_FIELD, false);
            assertFalse(enterDrag.test(dragEvent));
            assertFalse(skipTransform.test(inspector));
            assertFalse(skipIcon.test(inspector));
            assertFalse(skipTable.test(inspector));
            assertFalse(applyText.test(xField, "13.0"));
            assertFalse(applyText.test(yField, "20.0"));
            assertTrue(exitDrag.test(unitPanel));

            assertEquals(2L, bridge.snapshot().get("dragTransformsSkipped"));
            assertEquals(2L, bridge.snapshot().get("dragIconsSkipped"));
            assertEquals(2L, bridge.snapshot().get("dragTableSyncsSkipped"));
            assertTrue(bridge.snapshot().get("dragTextUpdatesElided") >= 2L);
            assertEquals(0L, bridge.snapshot().get("failures"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void wheelRobotZeroDeltaAndHiddenLinkedCameraDeferralFlushOnShow() throws Exception {
        final InMemoryDefiningLoader syntheticLoader =
                new InMemoryDefiningLoader(getClass().getClassLoader(), buildSyntheticHostClasses(true));
        final AtlasInteractiveRenderTarget target = syntheticTarget(true);

        try (AtlasInteractiveRenderBridge bridge = new AtlasInteractiveRenderBridge(syntheticLoader, target)) {
            bridge.install();
            final Predicate<Object[]> wheelRobot = (Predicate<Object[]>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.WHEEL_ZERO_ROBOT_PROPERTY);
            final Predicate<Object[]> deferLinkedZoom = (Predicate<Object[]>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.DEFER_LINKED_ZOOM_PROPERTY);
            final Predicate<Object> repaintViewport = (Predicate<Object>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.REPAINT_VIEWPORT_ONLY_PROPERTY);
            final Predicate<Object[]> deferLinkedPan = (Predicate<Object[]>)
                    System.getProperties().get(AtlasInteractiveRenderBridge.DEFER_LINKED_PAN_PROPERTY);

            final Class<?> cameraClass =
                    Class.forName(target.generation().cameraOwner().replace('/', '.'), true, syntheticLoader);
            final Object linkedCamera = cameraClass.getDeclaredConstructor().newInstance();
            final JComponent viewportCanvas = (JComponent) cameraClass
                    .getMethod(AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_GETTER_METHOD)
                    .invoke(linkedCamera);
            final JScrollPane scrollPane = (JScrollPane) cameraClass
                    .getMethod(AtlasInteractiveRenderTarget.SCROLL_PANE_GETTER_METHOD)
                    .invoke(linkedCamera);
            viewportCanvas.setPreferredSize(new Dimension(CANVAS_WIDTH * 4, CANVAS_HEIGHT * 4));
            viewportCanvas.setSize(CANVAS_WIDTH * 4, CANVAS_HEIGHT * 4);
            scrollPane.setPreferredSize(new Dimension(CANVAS_WIDTH, CANVAS_HEIGHT));

            // Zero cursor delta elides Robot allocation and Toolkit.sync.
            assertTrue(wheelRobot.test(new Object[] {linkedCamera, 0, 0, WHEEL_ZOOM_RATIO}));
            // Sub-pixel moveDx/moveDy truncating to zero also elides Robot warp.
            assertTrue(wheelRobot.test(new Object[] {linkedCamera, 1, 0, 0.5d}));
            // Non-zero pixel delta permits Robot warp.
            assertFalse(wheelRobot.test(new Object[] {linkedCamera, 10, 10, 1.0d}));
            assertEquals(2L, bridge.snapshot().get("robotWarpsElided"));

            // Viewport-only pan repaint targets only the inner canvas component.
            assertTrue(repaintViewport.test(linkedCamera));
            assertEquals(1L, bridge.snapshot().get("viewportRepaintsTargeted"));

            // Non-showing linked scroll pane defers both zoom and pan updates without invoking setZoom immediately.
            assertFalse(scrollPane.isShowing());
            assertTrue(deferLinkedZoom.test(new Object[] {linkedCamera, new Point(12, 24), LINKED_ZOOM_TARGET}));
            assertTrue(deferLinkedPan.test(new Object[] {linkedCamera, new Point(LINKED_PAN_X, LINKED_PAN_Y)}));
            assertEquals(1L, bridge.snapshot().get("linkedZoomsDeferred"));
            assertEquals(1L, bridge.snapshot().get("linkedPansDeferred"));
            assertEquals(1.0d, (double) getField(viewportCanvas, "lastZoom"), 1.0e-6d);

            // Making scroll pane showing flushes latest deferred zoom and pan via SHOWING_CHANGED listener.
            final JFrame frame = new JFrame();
            try {
                frame.setSize(CANVAS_WIDTH, CANVAS_HEIGHT);
                frame.add(scrollPane);
                frame.pack();
                frame.setVisible(true);
            } catch (final java.awt.HeadlessException headlessFallback) {
                // On headless JVMs without Xvfb, deliver SHOWING_CHANGED directly on a showing-stub pane.
                final ShowableScrollPane showingPane = new ShowableScrollPane();
                showingPane.setViewportView(viewportCanvas);
                showingPane.setSize(CANVAS_WIDTH, CANVAS_HEIGHT);
                showingPane.getViewport().setSize(CANVAS_WIDTH, CANVAS_HEIGHT);
                setField(linkedCamera, target.generation().cameraScrollField(), showingPane);
                showingPane.setShowingState(false);
                assertTrue(deferLinkedZoom.test(new Object[] {linkedCamera, new Point(8, 12), LINKED_ZOOM_TARGET}));
                assertTrue(deferLinkedPan.test(new Object[] {linkedCamera, new Point(LINKED_PAN_X, LINKED_PAN_Y)}));
                showingPane.setShowingState(true);
                showingPane.fireShowingChanged();
                assertEquals(LINKED_ZOOM_TARGET, (double) getField(viewportCanvas, "lastZoom"), 1.0e-6d);
                assertEquals(
                        new Point(LINKED_PAN_X, LINKED_PAN_Y),
                        showingPane.getViewport().getViewPosition());
                assertEquals(1L, bridge.snapshot().get("linkedFlushes"));
                return;
            } finally {
                frame.dispose();
            }

            assertEquals(LINKED_ZOOM_TARGET, (double) getField(viewportCanvas, "lastZoom"), 1.0e-6d);
            assertEquals(
                    new Point(LINKED_PAN_X, LINKED_PAN_Y),
                    scrollPane.getViewport().getViewPosition());
            assertEquals(1L, bridge.snapshot().get("linkedFlushes"));
            assertEquals(0L, bridge.snapshot().get("failures"));
        }
    }

    private static void assertBakePathUntouched(final byte[] rewrittenEditLayer, final String version) {
        final ClassReader reader = new ClassReader(rewrittenEditLayer);
        final boolean[] sawBakeCopy = {false};
        final boolean[] sawOverlayInBakeCopy = {false};
        final boolean[] sawOverlayInNonBakeGuard = {false};
        final boolean[] sawFallbackFill = {false};
        final boolean[] sawFallbackDraw = {false};
        final boolean[] sawOverlayOutsideGuard = {false};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (!AtlasInteractiveRenderTarget.DRAW_EXE_METHOD.equals(name)
                                || !AtlasInteractiveRenderTarget.DRAW_EXE_DESCRIPTOR.equals(descriptor)) {
                            return null;
                        }
                        return new MethodVisitor(Opcodes.ASM9) {
                            private boolean sawRenderModeC;
                            private org.objectweb.asm.Label afterBakeCopyLabel;
                            private org.objectweb.asm.Label afterNonBakeGuardLabel;
                            private boolean inBakeCopy;
                            private boolean inNonBakeGuard;

                            @Override
                            public void visitFieldInsn(
                                    final int opcode, final String owner, final String fName, final String fDesc) {
                                sawRenderModeC = opcode == Opcodes.GETSTATIC
                                        && AtlasInteractiveRenderTarget.RENDER_MODE_OWNER.equals(owner)
                                        && "c".equals(fName);
                            }

                            @Override
                            public void visitJumpInsn(final int opcode, final org.objectweb.asm.Label label) {
                                if (sawRenderModeC) {
                                    sawRenderModeC = false;
                                    if (opcode == Opcodes.IF_ACMPNE) {
                                        afterBakeCopyLabel = label;
                                        inBakeCopy = true;
                                    } else if (opcode == Opcodes.IF_ACMPEQ && afterNonBakeGuardLabel == null) {
                                        afterNonBakeGuardLabel = label;
                                        inNonBakeGuard = true;
                                    }
                                }
                            }

                            @Override
                            public void visitLabel(final org.objectweb.asm.Label label) {
                                if (label == afterBakeCopyLabel) {
                                    inBakeCopy = false;
                                }
                                if (label == afterNonBakeGuardLabel) {
                                    inNonBakeGuard = false;
                                }
                            }

                            @Override
                            public void visitLdcInsn(final Object value) {
                                if (referencesOverlaySlot(value)) {
                                    if (inBakeCopy) {
                                        sawOverlayInBakeCopy[0] = true;
                                    } else if (inNonBakeGuard) {
                                        sawOverlayInNonBakeGuard[0] = true;
                                    } else {
                                        sawOverlayOutsideGuard[0] = true;
                                    }
                                }
                            }

                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                sawRenderModeC = false;
                                if (inBakeCopy
                                        && "jp/noids/graphics/g".equals(owner)
                                        && "a".equals(mName)
                                        && "(Ljava/awt/Graphics;Ljava/awt/Rectangle;Ljava/awt/image/BufferedImage;ZI)V"
                                                .equals(mDesc)) {
                                    sawBakeCopy[0] = true;
                                }
                                if (inNonBakeGuard
                                        && "jp/noids/graphics/a".equals(owner)
                                        && "(Ljava/awt/Shape;)V".equals(mDesc)) {
                                    if ("fill".equals(mName)) {
                                        sawFallbackFill[0] = true;
                                    } else if ("draw".equals(mName)) {
                                        sawFallbackDraw[0] = true;
                                    }
                                }
                            }
                        };
                    }
                },
                0);
        assertTrue(sawBakeCopy[0], version + ": f$c.c bake path must preserve direct g.a image bake");
        assertFalse(sawOverlayInBakeCopy[0], version + ": f$c.c bake path must not consult overlay cache");
        assertTrue(
                sawOverlayInNonBakeGuard[0],
                version + ": overlay cache must stay strictly inside mode != f$c.c branch");
        assertTrue(sawFallbackFill[0] && sawFallbackDraw[0], version + ": fallback fill/draw must remain intact");
        assertFalse(sawOverlayOutsideGuard[0], version + ": overlay cache must not leak past mode != f$c.c guard");
    }

    private static boolean referencesOverlaySlot(final Object value) {
        if (AtlasInteractiveRenderBridge.OVERLAY_RENDER_PROPERTY.equals(value)) {
            return true;
        }
        if (value instanceof org.objectweb.asm.ConstantDynamic condy) {
            for (int i = 0; i < condy.getBootstrapMethodArgumentCount(); i++) {
                if (AtlasInteractiveRenderBridge.OVERLAY_RENDER_PROPERTY.equals(condy.getBootstrapMethodArgument(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assertFailClosedRejections(
            final ClassLoader loader,
            final Path artifact,
            final List<byte[]> references,
            final AtlasInteractiveRenderTarget target,
            final ProtectionDomain domain)
            throws Exception {
        final AtlasInteractiveRenderTransformer transformer = new AtlasInteractiveRenderTransformer(
                loader,
                artifact,
                target,
                references.get(0),
                references.get(1),
                references.get(2),
                references.get(3),
                references.get(4),
                references.get(5));
        final String primaryOwner = target.targetOwners().get(0);
        final Class<?> primaryClass = Class.forName(primaryOwner.replace('/', '.'), false, loader);

        // Wrong classloader must fail closed without rewriting bytes.
        assertNull(transformer.transform(
                null, new URLClassLoader(new URL[0], loader), primaryOwner, primaryClass, domain, references.get(0)));

        // Null or mismatched CodeSource must reject transformation.
        assertNull(transformer.transform(null, loader, primaryOwner, primaryClass, null, references.get(0)));
        final ProtectionDomain wrongDomain = new ProtectionDomain(
                new CodeSource(Path.of("/tmp/wrong-cubism.jar").toUri().toURL(), (Certificate[]) null), null);
        assertNull(transformer.transform(null, loader, primaryOwner, primaryClass, wrongDomain, references.get(0)));

        // Mutating draw_exe method bytecode must reject transformation and record a failure message.
        final byte[] mutated = prependNoop(
                references.get(0),
                AtlasInteractiveRenderTarget.DRAW_EXE_METHOD,
                AtlasInteractiveRenderTarget.DRAW_EXE_DESCRIPTOR);
        assertNull(transformer.transform(null, loader, primaryOwner, primaryClass, domain, mutated));
        assertNotNull(transformer.failure());
    }

    private static AtlasInteractiveRenderTarget syntheticTarget(final boolean staticColors) {
        return new AtlasInteractiveRenderTarget(
                ReviewedHostArtifacts.CUBISM_5_3_05,
                ReviewedHostArtifacts.CUBISM_5_3_05_VERSION,
                staticColors
                        ? AtlasInteractiveRenderTarget.Generation.LEGACY_EARLY
                        : AtlasInteractiveRenderTarget.Generation.TABLE_SYNC_HIDPI_ROBOT);
    }

    private static Map<String, byte[]> buildSyntheticHostClasses(final boolean legacyCamera) {
        final Map<String, byte[]> classes = new HashMap<>();
        final String cameraOwner = legacyCamera
                ? AtlasInteractiveRenderTarget.Generation.LEGACY_EARLY.cameraOwner()
                : AtlasInteractiveRenderTarget.Generation.TABLE_SYNC_HIDPI_ROBOT.cameraOwner();
        final String canvasField = legacyCamera
                ? AtlasInteractiveRenderTarget.Generation.LEGACY_EARLY.cameraCanvasField()
                : AtlasInteractiveRenderTarget.Generation.TABLE_SYNC_HIDPI_ROBOT.cameraCanvasField();
        final String scrollField = legacyCamera
                ? AtlasInteractiveRenderTarget.Generation.LEGACY_EARLY.cameraScrollField()
                : AtlasInteractiveRenderTarget.Generation.TABLE_SYNC_HIDPI_ROBOT.cameraScrollField();

        classes.put(
                AtlasInteractiveRenderTarget.EDIT_LAYER_OWNER.replace('/', '.'), generateEditLayerBytes(legacyCamera));
        classes.put(AtlasInteractiveRenderTarget.LAYER_UPDATE_EVENT_OWNER.replace('/', '.'), generateEventBytes());
        classes.put(
                AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER.replace('/', '.'), generateViewportCanvasBytes());
        classes.put(cameraOwner.replace('/', '.'), generateCameraBytes(cameraOwner, canvasField, scrollField));

        if (!legacyCamera) {
            classes.put(AtlasInteractiveRenderTarget.COLOR_OWNER.replace('/', '.'), generateColorBytes());
            classes.put(
                    AtlasInteractiveRenderTarget.DATA_MODEL_COMPANION_OWNER.replace('/', '.'),
                    generateDataModelCompanionBytes());
            classes.put(AtlasInteractiveRenderTarget.DATA_MODEL_OWNER.replace('/', '.'), generateDataModelBytes());
        }
        return classes;
    }

    private static byte[] generateEditLayerBytes(final boolean legacy) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                AtlasInteractiveRenderTarget.EDIT_LAYER_OWNER,
                null,
                "java/lang/Object",
                null);
        cw.visitField(
                Opcodes.ACC_PUBLIC,
                AtlasInteractiveRenderTarget.DRAW_DATA_SHAPES_FIELD,
                AtlasInteractiveRenderTarget.ARRAY_LIST_DESCRIPTOR,
                null,
                null);
        if (!legacy) {
            cw.visitField(
                    Opcodes.ACC_PUBLIC,
                    AtlasInteractiveRenderTarget.TEX_RELOC_MODEL_FIELD,
                    AtlasInteractiveRenderTarget.TEX_RELOC_MODEL_DESCRIPTOR,
                    null,
                    null);
        }
        emitDefaultConstructor(cw, "java/lang/Object");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateEventBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                AtlasInteractiveRenderTarget.LAYER_UPDATE_EVENT_OWNER,
                null,
                "java/lang/Object",
                null);
        cw.visitField(
                Opcodes.ACC_PUBLIC,
                AtlasInteractiveRenderTarget.EVENT_DRAGGING_FIELD,
                AtlasInteractiveRenderTarget.BOOLEAN_DESCRIPTOR,
                null,
                null);
        emitDefaultConstructor(cw, "java/lang/Object");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateViewportCanvasBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER,
                null,
                "javax/swing/JPanel",
                null);
        cw.visitField(Opcodes.ACC_PUBLIC, "lastZoom", "D", null, null);
        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "javax/swing/JPanel", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitInsn(Opcodes.DCONST_1);
        init.visitFieldInsn(Opcodes.PUTFIELD, AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER, "lastZoom", "D");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor zoom = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                AtlasInteractiveRenderTarget.VIEWPORT_SET_ZOOM_METHOD,
                AtlasInteractiveRenderTarget.VIEWPORT_SET_ZOOM_DESCRIPTOR,
                null,
                null);
        zoom.visitCode();
        zoom.visitVarInsn(Opcodes.ALOAD, 0);
        zoom.visitVarInsn(Opcodes.DLOAD, 2);
        zoom.visitFieldInsn(Opcodes.PUTFIELD, AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER, "lastZoom", "D");
        zoom.visitVarInsn(Opcodes.DLOAD, 2);
        zoom.visitInsn(Opcodes.DRETURN);
        zoom.visitMaxs(0, 0);
        zoom.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateCameraBytes(
            final String cameraOwner, final String canvasField, final String scrollField) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, cameraOwner, null, "java/lang/Object", null);
        cw.visitField(
                Opcodes.ACC_PUBLIC,
                canvasField,
                "L" + AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER + ";",
                null,
                null);
        cw.visitField(Opcodes.ACC_PUBLIC, scrollField, "Ljavax/swing/JScrollPane;", null, null);

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER);
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(
                Opcodes.INVOKESPECIAL, AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER, "<init>", "()V", false);
        init.visitFieldInsn(
                Opcodes.PUTFIELD,
                cameraOwner,
                canvasField,
                "L" + AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER + ";");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, "javax/swing/JScrollPane");
        init.visitInsn(Opcodes.DUP);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(
                Opcodes.GETFIELD,
                cameraOwner,
                canvasField,
                "L" + AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER + ";");
        init.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "javax/swing/JScrollPane", "<init>", "(Ljava/awt/Component;)V", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, cameraOwner, scrollField, "Ljavax/swing/JScrollPane;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor getCanvas = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_GETTER_METHOD,
                AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_GETTER_DESCRIPTOR,
                null,
                null);
        getCanvas.visitCode();
        getCanvas.visitVarInsn(Opcodes.ALOAD, 0);
        getCanvas.visitFieldInsn(
                Opcodes.GETFIELD,
                cameraOwner,
                canvasField,
                "L" + AtlasInteractiveRenderTarget.VIEWPORT_CANVAS_OWNER + ";");
        getCanvas.visitInsn(Opcodes.ARETURN);
        getCanvas.visitMaxs(0, 0);
        getCanvas.visitEnd();

        final MethodVisitor getScroll = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                AtlasInteractiveRenderTarget.SCROLL_PANE_GETTER_METHOD,
                AtlasInteractiveRenderTarget.SCROLL_PANE_GETTER_DESCRIPTOR,
                null,
                null);
        getScroll.visitCode();
        getScroll.visitVarInsn(Opcodes.ALOAD, 0);
        getScroll.visitFieldInsn(Opcodes.GETFIELD, cameraOwner, scrollField, "Ljavax/swing/JScrollPane;");
        getScroll.visitInsn(Opcodes.ARETURN);
        getScroll.visitMaxs(0, 0);
        getScroll.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateColorBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                AtlasInteractiveRenderTarget.COLOR_OWNER,
                null,
                "java/lang/Object",
                null);
        cw.visitField(Opcodes.ACC_PRIVATE, "argb", "I", null, null);
        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, AtlasInteractiveRenderTarget.COLOR_OWNER, "argb", "I");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor getJColor =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "getJColor", "()Ljava/awt/Color;", null, null);
        getJColor.visitCode();
        getJColor.visitTypeInsn(Opcodes.NEW, "java/awt/Color");
        getJColor.visitInsn(Opcodes.DUP);
        getJColor.visitVarInsn(Opcodes.ALOAD, 0);
        getJColor.visitFieldInsn(Opcodes.GETFIELD, AtlasInteractiveRenderTarget.COLOR_OWNER, "argb", "I");
        getJColor.visitInsn(Opcodes.ICONST_1);
        getJColor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/awt/Color", "<init>", "(IZ)V", false);
        getJColor.visitInsn(Opcodes.ARETURN);
        getJColor.visitMaxs(0, 0);
        getJColor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateDataModelCompanionBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                AtlasInteractiveRenderTarget.DATA_MODEL_COMPANION_OWNER,
                null,
                "java/lang/Object",
                null);
        emitDefaultConstructor(cw, "java/lang/Object");
        emitColorFactoryMethod(cw, "b", FILL_ARGB);
        emitColorFactoryMethod(cw, "c", STROKE_ARGB);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateDataModelBytes() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                AtlasInteractiveRenderTarget.DATA_MODEL_OWNER,
                null,
                "java/lang/Object",
                null);
        cw.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "a",
                "L" + AtlasInteractiveRenderTarget.DATA_MODEL_COMPANION_OWNER + ";",
                null,
                null);
        final MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, AtlasInteractiveRenderTarget.DATA_MODEL_COMPANION_OWNER);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(
                Opcodes.INVOKESPECIAL, AtlasInteractiveRenderTarget.DATA_MODEL_COMPANION_OWNER, "<init>", "()V", false);
        clinit.visitFieldInsn(
                Opcodes.PUTSTATIC,
                AtlasInteractiveRenderTarget.DATA_MODEL_OWNER,
                "a",
                "L" + AtlasInteractiveRenderTarget.DATA_MODEL_COMPANION_OWNER + ";");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        emitDefaultConstructor(cw, "java/lang/Object");
        emitColorFactoryMethod(cw, "i", FILL_ARGB);
        emitColorFactoryMethod(cw, "j", STROKE_ARGB);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitColorFactoryMethod(final ClassWriter cw, final String name, final int argb) {
        final MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC, name, "()L" + AtlasInteractiveRenderTarget.COLOR_OWNER + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, AtlasInteractiveRenderTarget.COLOR_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(argb);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, AtlasInteractiveRenderTarget.COLOR_OWNER, "<init>", "(I)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitDefaultConstructor(final ClassWriter cw, final String superOwner) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superOwner, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ArrayList<Path2D> buildTriangleGridShapes() {
        final ArrayList<Path2D> list = new ArrayList<>();
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                final float x0 = col * TRI_SIZE;
                final float y0 = row * TRI_SIZE;
                final float x1 = x0 + TRI_SIZE;
                final float y1 = y0 + TRI_SIZE;
                final Path2D.Float triA = new Path2D.Float();
                triA.moveTo(x0, y0);
                triA.lineTo(x1, y0);
                triA.lineTo(x1, y1);
                triA.closePath();
                list.add(triA);

                final Path2D.Float triB = new Path2D.Float();
                triB.moveTo(x0, y0);
                triB.lineTo(x1, y1);
                triB.lineTo(x0, y1);
                triB.closePath();
                list.add(triB);
            }
        }
        return list;
    }

    private static void drawReferenceOverlay(
            final BufferedImage img, final ArrayList<Path2D> shapes, final AffineTransform tx) {
        final Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setStroke(new BasicStroke(1.0f));
        for (final Path2D path : shapes) {
            final java.awt.Shape world = tx.createTransformedShape(path);
            g.setColor(new Color(FILL_ARGB, true));
            g.fill(world);
            g.setColor(new Color(STROKE_ARGB, true));
            g.draw(world);
        }
        g.dispose();
    }

    private static int countOpaquePixels(final BufferedImage img) {
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void assertImagesSimilar(final BufferedImage expected, final BufferedImage actual) {
        int expectedNonZero = 0;
        int actualNonZero = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if ((expected.getRGB(x, y) >>> 24) > 0) {
                    expectedNonZero++;
                }
                if ((actual.getRGB(x, y) >>> 24) > 0) {
                    actualNonZero++;
                }
            }
        }
        assertTrue(
                Math.abs(expectedNonZero - actualNonZero) <= expectedNonZero * 0.15d,
                "coverage mismatch expected=" + expectedNonZero + " actual=" + actualNonZero);
    }

    private static byte[] prependNoop(final byte[] bytes, final String methodName, final String methodDesc) {
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor downstream =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!methodName.equals(name) || !methodDesc.equals(descriptor)) {
                            return downstream;
                        }
                        return new MethodVisitor(Opcodes.ASM9, downstream) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitInsn(MUTATION_OPCODE);
                            }
                        };
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static Path locateArtifact(final String version) {
        final String configuredProp = System.getProperty(REF_DIR_PROPERTY);
        if (configuredProp != null && !configuredProp.isBlank()) {
            final Path p = Path.of(configuredProp).resolve(version).resolve(TEST_CUBISM_JAR);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        final String configuredEnv = System.getenv(REF_DIR_ENV);
        if (configuredEnv != null && !configuredEnv.isBlank()) {
            final Path p = Path.of(configuredEnv).resolve(version).resolve(TEST_CUBISM_JAR);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        final Path direct = LOCAL_ARTIFACTS_ROOT.resolve(version).resolve(TEST_CUBISM_JAR);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        final Path proton = LOCAL_REFERENCE_ROOT.resolve(version).resolve(PROTON_CUBISM_JAR_REL);
        return Files.isRegularFile(proton) ? proton : null;
    }

    private static byte[] readClassBytes(final Path jarPath, final String owner) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            final JarEntry entry = jar.getJarEntry(owner + TEST_CLASS_SUFFIX);
            assertNotNull(entry, "missing entry " + owner + TEST_CLASS_SUFFIX + " in " + jarPath);
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static void verifyMethodPresent(final Class<?> owner, final String name, final String descriptor)
            throws Exception {
        final org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        final Class<?>[] params = new Class<?>[argTypes.length];
        for (int i = 0; i < argTypes.length; i++) {
            params[i] = toClass(argTypes[i], owner.getClassLoader());
        }
        assertNotNull(owner.getDeclaredMethod(name, params), owner.getName() + "#" + name + descriptor);
    }

    private static void verifyFieldPresent(final Class<?> owner, final String name, final String descriptor)
            throws Exception {
        final Field field = owner.getDeclaredField(name);
        assertEquals(descriptor, org.objectweb.asm.Type.getDescriptor(field.getType()));
    }

    private static Class<?> toClass(final org.objectweb.asm.Type type, final ClassLoader loader)
            throws ClassNotFoundException {
        return switch (type.getSort()) {
            case org.objectweb.asm.Type.BOOLEAN -> boolean.class;
            case org.objectweb.asm.Type.BYTE -> byte.class;
            case org.objectweb.asm.Type.CHAR -> char.class;
            case org.objectweb.asm.Type.SHORT -> short.class;
            case org.objectweb.asm.Type.INT -> int.class;
            case org.objectweb.asm.Type.FLOAT -> float.class;
            case org.objectweb.asm.Type.LONG -> long.class;
            case org.objectweb.asm.Type.DOUBLE -> double.class;
            case org.objectweb.asm.Type.VOID -> void.class;
            case org.objectweb.asm.Type.ARRAY ->
                Class.forName(type.getDescriptor().replace('/', '.'), false, loader);
            default -> Class.forName(type.getClassName(), false, loader);
        };
    }

    private static ProtectionDomain domain(final Path jar) throws Exception {
        return new ProtectionDomain(new CodeSource(jar.toUri().toURL(), (Certificate[]) null), null);
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(final Object target, final String name) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void restoreProperty(final String key, final String prior) {
        if (prior == null) {
            System.getProperties().remove(key);
        } else {
            System.getProperties().put(key, prior);
        }
    }

    private static final class ShowableScrollPane extends JScrollPane {
        private boolean showingState;

        private void setShowingState(final boolean showingState) {
            this.showingState = showingState;
        }

        @Override
        public boolean isShowing() {
            return showingState;
        }

        private void fireShowingChanged() {
            final HierarchyEvent event = new HierarchyEvent(
                    this, HierarchyEvent.HIERARCHY_CHANGED, this, null, HierarchyEvent.SHOWING_CHANGED);
            for (final java.awt.event.HierarchyListener l : getHierarchyListeners()) {
                l.hierarchyChanged(event);
            }
        }
    }

    private static final class InMemoryDefiningLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private InMemoryDefiningLoader(final ClassLoader parent, final Map<String, byte[]> classes) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (classes.containsKey(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final byte[] bytes = classes.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            throw new ClassNotFoundException(name);
        }
    }
}
