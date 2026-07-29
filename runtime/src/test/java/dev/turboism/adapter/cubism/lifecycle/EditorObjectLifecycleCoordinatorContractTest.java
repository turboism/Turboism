package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.hook.DeformerHooks;
import dev.turboism.sdk.cubism.hook.DrawableHooks;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import java.time.Clock;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorObjectLifecycleCoordinatorContractTest {

    @Test
    void drawableLifecycleCoversVisibilityLockAndAtomicGeometryReplacement() {
        final List<String> events = new ArrayList<>();
        final DrawableLifecycleCoordinator coordinator = new DrawableLifecycleCoordinator();
        coordinator.register(drawablePlugin("plugin-a", List.of(new DrawableHooks() {
            @Override public boolean beforeSetDrawableVisible(Drawable value, boolean visible) {
                events.add("before-visible:" + visible); return !visible;
            }
            @Override public void onDrawableVisibilityChanged(Drawable value, boolean oldValue, boolean newValue) {
                events.add("on-visible:" + oldValue + "->" + newValue);
            }
            @Override public void afterSetDrawableVisible(Drawable value, boolean visible) {
                events.add("after-visible:" + visible);
            }
            @Override public boolean beforeSetDrawableLocked(Drawable value, boolean locked) {
                events.add("before-locked:" + locked); return true;
            }
            @Override public void onDrawableLockChanged(Drawable value, boolean oldValue, boolean newValue) {
                events.add("on-locked:" + oldValue + "->" + newValue);
            }
            @Override public void afterSetDrawableLocked(Drawable value, boolean locked) {
                events.add("after-locked:" + locked);
            }
            @Override public ArtMeshGeometry beforeReplaceDrawableGeometry(
                Drawable value, ArtMeshGeometry geometry
            ) {
                events.add("before-geometry"); return geometry.withVertexPosition(0, 2, 3);
            }
            @Override public void onDrawableGeometryChanged(
                Drawable value, ArtMeshGeometry oldValue, ArtMeshGeometry newValue
            ) { events.add("on-geometry"); }
            @Override public void afterReplaceDrawableGeometry(Drawable value, ArtMeshGeometry geometry) {
                events.add("after-geometry");
            }
        })));
        final MutableDrawable drawable = new MutableDrawable();
        final ArtMeshGeometry requested = geometry(4);

        coordinator.setVisible(drawable, true, drawable::writeVisible);
        coordinator.awaitIdle();
        coordinator.setLocked(drawable, false, drawable::writeLocked);
        coordinator.awaitIdle();
        coordinator.replaceGeometry(drawable, requested, drawable::writeGeometry);
        coordinator.awaitIdle();

        assertFalse(drawable.visible());
        assertTrue(drawable.locked());
        assertEquals(new Point2(2, 3), drawable.geometry().positions().get(0));
        assertEquals(List.of(
            "before-visible:true", "on-visible:true->false", "after-visible:false",
            "before-locked:false", "on-locked:false->true", "after-locked:true",
            "before-geometry", "on-geometry", "after-geometry"
        ), events);
    }

    @Test
    void deformerLifecycleCoversSharedWarpAndRotationOperations() {
        final List<String> events = new ArrayList<>();
        final DeformerLifecycleCoordinator coordinator = new DeformerLifecycleCoordinator();
        coordinator.register(deformerPlugin("plugin-a", List.of(new DeformerHooks() {
            @Override public float beforeSetDeformerOpacity(Deformer value, float opacity) {
                events.add("before-opacity"); return 0.4F;
            }
            @Override public void onDeformerOpacityChanged(Deformer value, float oldValue, float newValue) {
                events.add("on-opacity");
            }
            @Override public void afterSetDeformerOpacity(Deformer value, float opacity) {
                events.add("after-opacity");
            }
            @Override public boolean beforeSetDeformerVisible(Deformer value, boolean visible) {
                events.add("before-visible"); return false;
            }
            @Override public void afterSetDeformerVisible(Deformer value, boolean visible) {
                events.add("after-visible");
            }
            @Override public boolean beforeSetDeformerLocked(Deformer value, boolean locked) {
                events.add("before-locked"); return true;
            }
            @Override public void afterSetDeformerLocked(Deformer value, boolean locked) {
                events.add("after-locked");
            }
            @Override public WarpGrid beforeReplaceWarpDeformerGrid(WarpDeformer value, WarpGrid grid) {
                events.add("before-grid"); return grid.withControlPoint(0, 3, 4);
            }
            @Override public void onWarpDeformerGridChanged(WarpDeformer value, WarpGrid oldValue, WarpGrid newValue) {
                events.add("on-grid");
            }
            @Override public void afterReplaceWarpDeformerGrid(WarpDeformer value, WarpGrid grid) {
                events.add("after-grid");
            }
            @Override public float beforeSetRotationDeformerBaseAngle(RotationDeformer value, float angle) {
                events.add("before-angle"); return 25.0F;
            }
            @Override public void afterSetRotationDeformerBaseAngle(RotationDeformer value, float angle) {
                events.add("after-angle");
            }
            @Override public RotationDeformerForm beforeReplaceRotationDeformerForm(
                RotationDeformer value, RotationDeformerForm form
            ) {
                events.add("before-form");
                return new RotationDeformerForm(9, form.originX(), form.originY(), form.scale(), true, false);
            }
            @Override public void onRotationDeformerFormChanged(
                RotationDeformer value, RotationDeformerForm oldValue, RotationDeformerForm newValue
            ) { events.add("on-form"); }
            @Override public void afterReplaceRotationDeformerForm(
                RotationDeformer value, RotationDeformerForm form
            ) { events.add("after-form"); }
        })));
        final MutableWarp warp = new MutableWarp();
        final MutableRotation rotation = new MutableRotation();

        coordinator.setOpacity(warp, 0.8F, warp::writeOpacity);
        coordinator.awaitIdle();
        coordinator.setVisible(warp, true, warp::writeVisible);
        coordinator.awaitIdle();
        coordinator.setLocked(warp, false, warp::writeLocked);
        coordinator.awaitIdle();
        coordinator.replaceGrid(warp, grid(), warp::writeGrid);
        coordinator.awaitIdle();
        coordinator.setBaseAngle(rotation, 10.0F, rotation::writeBaseAngle);
        coordinator.awaitIdle();
        coordinator.replaceForm(rotation, new RotationDeformerForm(1, 2, 3, 1, false, false), rotation::writeForm);
        coordinator.awaitIdle();

        assertEquals(0.4F, warp.getOpacity());
        assertFalse(warp.visible());
        assertTrue(warp.locked());
        assertEquals(new Point2(3, 4), warp.grid().controlPoints().get(0));
        assertEquals(25.0F, rotation.baseAngle());
        assertEquals(9.0F, rotation.form().angle());
        assertTrue(rotation.form().reflectedX());
        assertEquals(List.of(
            "before-opacity", "on-opacity", "after-opacity",
            "before-visible", "after-visible",
            "before-locked", "after-locked",
            "before-grid", "on-grid", "after-grid",
            "before-angle", "after-angle",
            "before-form", "on-form", "after-form"
        ), events);
    }

    @Test
    void permissionsFailureIsolationInvalidResultsAndUnregisterAreFailClosed() {
        final List<String> events = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final DrawableLifecycleCoordinator coordinator = new DrawableLifecycleCoordinator();
        coordinator.register(new DrawableLifecycleCoordinator.PluginHooks(
            descriptor("observer-only"), List.of(new DrawableHooks() {
                @Override public float beforeSetDrawableOpacity(Drawable value, float opacity) {
                    events.add("forbidden-before"); return 0.1F;
                }
                @Override public void afterSetDrawableOpacity(Drawable value, float opacity) {
                    events.add("observer-after:" + opacity);
                }
            }), logger(errors), false, true
        ));
        coordinator.register(new DrawableLifecycleCoordinator.PluginHooks(
            descriptor("interceptor"), List.of(new DrawableHooks() {
                @Override public float beforeSetDrawableOpacity(Drawable value, float opacity) {
                    events.add("before-throws"); throw new IllegalStateException("bad hook");
                }
            }, new DrawableHooks() {
                @Override public float beforeSetDrawableOpacity(Drawable value, float opacity) {
                    events.add("before-invalid"); return Float.NaN;
                }
            }, new DrawableHooks() {
                @Override public float beforeSetDrawableOpacity(Drawable value, float opacity) {
                    events.add("before-good:" + opacity); return opacity * 0.5F;
                }
            }), logger(errors), true, false
        ));
        final MutableDrawable drawable = new MutableDrawable();

        coordinator.setOpacity(drawable, 0.8F, drawable::writeOpacity);
        coordinator.awaitIdle();

        assertEquals(0.4F, drawable.getOpacity());
        assertEquals(List.of(
            "before-throws", "before-invalid", "before-good:0.8", "observer-after:0.4"
        ), events);
        assertEquals(1, errors.size());

        events.clear();
        coordinator.unregister("observer-only");
        coordinator.unregister("interceptor");
        coordinator.setOpacity(drawable, 0.6F, drawable::writeOpacity);
        coordinator.awaitIdle();
        assertEquals(0.6F, drawable.getOpacity());
        assertEquals(List.of(), events);
    }

    @Test
    void nativeFailureSuppressesCompletionForDeformerAndGeometryNullIsIgnored() {
        final List<String> events = new ArrayList<>();
        final DeformerLifecycleCoordinator deformer = new DeformerLifecycleCoordinator();
        deformer.register(deformerPlugin("plugin", List.of(new DeformerHooks() {
            @Override public void onDeformerOpacityChanged(Deformer value, float oldValue, float newValue) {
                events.add("on");
            }
            @Override public void afterSetDeformerOpacity(Deformer value, float opacity) {
                events.add("after");
            }
        })));
        final MutableWarp warp = new MutableWarp();
        assertThrows(IllegalStateException.class, () -> deformer.setOpacity(
            warp, 0.5F, ignored -> { throw new IllegalStateException("native"); }
        ));
        deformer.awaitIdle();
        assertEquals(List.of(), events);

        final DrawableLifecycleCoordinator drawable = new DrawableLifecycleCoordinator();
        drawable.register(drawablePlugin("plugin", List.of(new DrawableHooks() {
            @Override public ArtMeshGeometry beforeReplaceDrawableGeometry(
                Drawable value, ArtMeshGeometry geometry
            ) { return null; }
        })));
        final MutableDrawable mesh = new MutableDrawable();
        final ArtMeshGeometry requested = geometry(8);
        drawable.replaceGeometry(mesh, requested, mesh::writeGeometry);
        assertEquals(requested, mesh.geometry());
    }


    @Test
    void nullWarpAndRotationTransformsAreIgnoredAndLogged() {
        final List<String> errors = new ArrayList<>();
        final DeformerLifecycleCoordinator coordinator = new DeformerLifecycleCoordinator();
        coordinator.register(new DeformerLifecycleCoordinator.PluginHooks(
            descriptor("invalid-plugin"),
            List.of(new DeformerHooks() {
                @Override public WarpGrid beforeReplaceWarpDeformerGrid(
                    WarpDeformer value, WarpGrid grid
                ) { return null; }
                @Override public RotationDeformerForm beforeReplaceRotationDeformerForm(
                    RotationDeformer value, RotationDeformerForm form
                ) { return null; }
            }),
            logger(errors)
        ));
        final MutableWarp warp = new MutableWarp();
        final MutableRotation rotation = new MutableRotation();
        final WarpGrid requestedGrid = new WarpGrid(1, 1, true, List.of(
            new Point2(0, 0), new Point2(2, 0), new Point2(0, 2), new Point2(2, 2)
        ));
        final RotationDeformerForm requestedForm =
            new RotationDeformerForm(2, 3, 4, 1.25F, true, false);

        coordinator.replaceGrid(warp, requestedGrid, warp::writeGrid);
        coordinator.replaceForm(rotation, requestedForm, rotation::writeForm);

        assertEquals(requestedGrid, warp.grid());
        assertEquals(requestedForm, rotation.form());
        assertEquals(2, errors.size());
    }


    @Test
    void rejectsCrossOperationRecursionWithinEachObjectFamily() {
        final DrawableLifecycleCoordinator drawable = new DrawableLifecycleCoordinator();
        final MutableDrawable mesh = new MutableDrawable();
        drawable.register(drawablePlugin("plugin", List.of(new DrawableHooks() {
            @Override public float beforeSetDrawableOpacity(Drawable value, float opacity) {
                drawable.setVisible(value, false, mesh::writeVisible);
                return opacity;
            }
        })));
        drawable.setOpacity(mesh, 0.5F, mesh::writeOpacity);
        assertEquals(0.5F, mesh.getOpacity());
        assertTrue(mesh.visible());

        final DeformerLifecycleCoordinator deformer = new DeformerLifecycleCoordinator();
        final MutableWarp warp = new MutableWarp();
        deformer.register(deformerPlugin("plugin", List.of(new DeformerHooks() {
            @Override public float beforeSetDeformerOpacity(Deformer value, float opacity) {
                deformer.setLocked(value, true, warp::writeLocked);
                return opacity;
            }
        })));
        deformer.setOpacity(warp, 0.5F, warp::writeOpacity);
        assertEquals(0.5F, warp.getOpacity());
        assertFalse(warp.locked());
    }


    @Test
    void unregisterWaitsForAcceptedDrawableAndDeformerCallbacks() throws Exception {
        final DrawableLifecycleCoordinator drawable = new DrawableLifecycleCoordinator();
        final CountDownLatch drawableStarted = new CountDownLatch(1);
        final CountDownLatch releaseDrawable = new CountDownLatch(1);
        drawable.register(drawablePlugin("drawable-plugin", List.of(new DrawableHooks() {
            @Override public void afterSetDrawableOpacity(Drawable value, float opacity) {
                drawableStarted.countDown();
                await(releaseDrawable);
            }
        })));
        final MutableDrawable mesh = new MutableDrawable();
        drawable.setOpacity(mesh, 0.5F, mesh::writeOpacity);
        assertTrue(drawableStarted.await(1, TimeUnit.SECONDS));
        assertUnregisterWaits(() -> drawable.unregister("drawable-plugin"), releaseDrawable);

        final DeformerLifecycleCoordinator deformer = new DeformerLifecycleCoordinator();
        final CountDownLatch deformerStarted = new CountDownLatch(1);
        final CountDownLatch releaseDeformer = new CountDownLatch(1);
        deformer.register(deformerPlugin("deformer-plugin", List.of(new DeformerHooks() {
            @Override public void afterSetDeformerOpacity(Deformer value, float opacity) {
                deformerStarted.countDown();
                await(releaseDeformer);
            }
        })));
        final MutableWarp warp = new MutableWarp();
        deformer.setOpacity(warp, 0.5F, warp::writeOpacity);
        assertTrue(deformerStarted.await(1, TimeUnit.SECONDS));
        assertUnregisterWaits(() -> deformer.unregister("deformer-plugin"), releaseDeformer);
    }

    private static void assertUnregisterWaits(
        final Runnable unregister,
        final CountDownLatch releaseCallback
    ) throws Exception {
        final CountDownLatch unregisterFinished = new CountDownLatch(1);
        final Thread thread = new Thread(() -> {
            unregister.run();
            unregisterFinished.countDown();
        });
        thread.start();
        assertFalse(
            unregisterFinished.await(100, TimeUnit.MILLISECONDS),
            "unregister must wait for accepted callbacks to quiesce"
        );
        releaseCallback.countDown();
        assertTrue(unregisterFinished.await(5, TimeUnit.SECONDS));
        thread.join(5_000L);
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }


    @Test
    void editorObjectCallbackQueueSaturationDoesNotFailOrInlineNativeWrites() throws Exception {
        final List<PluginWorkBudgetEvent> diagnostics = new CopyOnWriteArrayList<>();
        final PluginWorkExecutorRegistry executors = new PluginWorkExecutorRegistry(
            1, 1, diagnostics::add, Clock.systemUTC()
        );
        final DrawableLifecycleCoordinator drawable = new DrawableLifecycleCoordinator(executors);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        drawable.register(drawablePlugin("plugin-a", List.of(new DrawableHooks() {
            @Override public void afterSetDrawableOpacity(Drawable value, float opacity) {
                started.countDown();
                await(release);
            }
        })));
        final MutableDrawable mesh = new MutableDrawable();

        drawable.setOpacity(mesh, 0.5F, mesh::writeOpacity);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        drawable.setOpacity(mesh, 0.6F, mesh::writeOpacity);
        drawable.setOpacity(mesh, 0.7F, mesh::writeOpacity);

        assertEquals(0.7F, mesh.getOpacity());
        assertTrue(diagnostics.stream().anyMatch(event ->
            event.phase() == PluginWorkBudgetEvent.Phase.REJECTED
        ));
        release.countDown();
        drawable.close();
    }

    private static DrawableLifecycleCoordinator.PluginHooks drawablePlugin(
        String id, List<? extends DrawableHooks> hooks
    ) { return new DrawableLifecycleCoordinator.PluginHooks(descriptor(id), hooks, logger(new ArrayList<>())); }

    private static DeformerLifecycleCoordinator.PluginHooks deformerPlugin(
        String id, List<? extends DeformerHooks> hooks
    ) { return new DeformerLifecycleCoordinator.PluginHooks(descriptor(id), hooks, logger(new ArrayList<>())); }

    private static PluginDescriptor descriptor(final String id) {
        return new PluginDescriptor() {
            @Override public String id() { return id; }
            @Override public String name() { return id; }
            @Override public String version() { return "1.0.0"; }
            @Override public String description() { return "test"; }
            @Override public List<String> entrypoints() { return List.of(); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "UNLICENSED"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return new I18n() {
                @Override public String baseName() { return "messages"; }
                @Override public List<String> locales() { return List.of(); }
            }; }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginLogger logger(final List<String> errors) {
        return new PluginLogger() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void error(String message) { errors.add(message); }
            @Override public void error(String message, Throwable throwable) { errors.add(message); }
        };
    }

    private static ArtMeshGeometry geometry(final float firstX) {
        return new ArtMeshGeometry(
            List.of(new Point2(firstX, 0), new Point2(1, 0), new Point2(0, 1)),
            List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
            List.of(0, 1, 2)
        );
    }

    private static WarpGrid grid() {
        return new WarpGrid(1, 1, false, List.of(
            new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1)
        ));
    }

    private static final class MutableDrawable implements Drawable {
        private boolean visible = true;
        private boolean locked;
        private float opacity = 1.0F;
        private ArtMeshGeometry geometry = EditorObjectLifecycleCoordinatorContractTest.geometry(0);
        private void writeVisible(boolean value) { visible = value; }
        private void writeLocked(boolean value) { locked = value; }
        private void writeOpacity(float value) { opacity = value; }
        private void writeGeometry(ArtMeshGeometry value) { geometry = value; }
        @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
        @Override public boolean visible() { return visible; }
        @Override public boolean locked() { return locked; }
        @Override public float getOpacity() { return opacity; }
        @Override public ArtMeshGeometry geometry() { return geometry; }
        @Override public byte constantFlag() { return 0; }
        @Override public byte dynamicFlag() { return 0; }
        @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
        @Override public int textureIndex() { return 0; }
        @Override public int drawOrder() { return 0; }
        @Override public int renderOrder() { return 0; }
        @Override public IntSequence masks() { return ints(); }
        @Override public FloatSequence vertexPositions() { return floats(); }
        @Override public FloatSequence vertexUvs() { return floats(); }
        @Override public IntSequence indices() { return ints(); }
        @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
        @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
        @Override public int parentPartIndex() { return -1; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return ints(); }
    }

    private static class MutableWarp implements WarpDeformer {
        private boolean visible = true;
        private boolean locked;
        private float opacity = 1.0F;
        private WarpGrid grid = new WarpGrid(1, 1, false, List.of(
            new Point2(-1, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1)
        ));
        private void writeOpacity(float value) { opacity = value; }
        private void writeVisible(boolean value) { visible = value; }
        private void writeLocked(boolean value) { locked = value; }
        private void writeGrid(WarpGrid value) { grid = value; }
        @Override public DeformerId id() { return new DeformerId("WarpA"); }
        @Override public boolean visible() { return visible; }
        @Override public boolean locked() { return locked; }
        @Override public float getOpacity() { return opacity; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return ints(); }
        @Override public WarpGrid grid() { return grid; }
        @Override public void replaceGrid(WarpGrid value) { writeGrid(value); }
    }

    private static final class MutableRotation implements RotationDeformer {
        private float angle;
        private RotationDeformerForm form = new RotationDeformerForm(0, 0, 0, 1, false, false);
        private void writeBaseAngle(float value) { angle = value; }
        private void writeForm(RotationDeformerForm value) { form = value; }
        @Override public DeformerId id() { return new DeformerId("RotationA"); }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return ints(); }
        @Override public float baseAngle() { return angle; }
        @Override public void setBaseAngle(float value) { angle = value; }
        @Override public RotationDeformerForm form() { return form; }
        @Override public void replaceForm(RotationDeformerForm value) { form = value; }
    }

    private static IntSequence ints() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    private static FloatSequence floats() {
        return new FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
