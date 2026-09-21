package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.awt.geom.AffineTransform;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-path dispatch tests: the runtime dispatcher drives a registered
 * planner through the real native-invocation coordinator and layout service, with
 * no official plugin and no plugin system properties involved.
 */
final class TextureAtlasAutoLayoutDispatcherTest {

    @Test
    void dispatchesSelectedThirdPartyPlannerThroughTheNativeInvocation() {
        final Fixture fixture = new Fixture();
        final TextureAtlasNativeInvocationCoordinator coordinator =
            new TextureAtlasNativeInvocationCoordinator();
        coordinator.connect(resolver());
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry =
            new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection();
        registry.bindSelection(selection);
        final RuntimeTextureAtlasLayoutService service = service(coordinator);
        final TextureAtlasAutoLayoutDispatcher dispatcher =
            new TextureAtlasAutoLayoutDispatcher(registry, selection, service);

        final AtomicReference<List<TextureAtlasLayoutItem>> seenItems = new AtomicReference<>();
        final AtomicReference<TextureAtlasLayoutConstraints> seenConstraints = new AtomicReference<>();
        final AtomicBoolean seenParallel = new AtomicBoolean();
        registry.register(new TextureAtlasLayoutAlgorithm(
            "third-party",
            "Third Party",
            true,
            new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlanner() {
                @Override
                public TextureAtlasLayoutPlan plan(
                    final List<TextureAtlasLayoutItem> items,
                    final TextureAtlasLayoutConstraints constraints
                ) {
                    return plan(items, constraints, false);
                }

                @Override
                public TextureAtlasLayoutPlan plan(
                    final List<TextureAtlasLayoutItem> items,
                    final TextureAtlasLayoutConstraints constraints,
                    final boolean parallel
                ) {
                    seenItems.set(items);
                    seenConstraints.set(constraints);
                    seenParallel.set(parallel);
                    return TextureAtlasLayoutPlan.currentPage(32, 16, List.of(), 1);
                }
            }
        ));
        selection.select(new TextureAtlasLayoutSelection("third-party", true));

        assertTrue(coordinator.ingress(dispatcher.callback()).test(fixture.receiver));
        assertEquals(List.of("native-item-0", "native-item-1"),
            seenItems.get().stream().map(TextureAtlasLayoutItem::textureId).toList());
        // The snapshot is the native current-page scope, exactly what the plugin saw before.
        assertTrue(seenConstraints.get().singlePageOptions() != null);
        assertTrue(seenParallel.get());
        // The empty plan leaves every item overflowing to the native output list.
        assertEquals(List.of(fixture.first, fixture.second), fixture.receiver.i);
    }

    @Test
    void unsetSelectionAndMissingAlgorithmFallBackToNative() {
        final Fixture fixture = new Fixture();
        final AffineTransform before = new AffineTransform(fixture.first.f);
        final TextureAtlasNativeInvocationCoordinator coordinator =
            new TextureAtlasNativeInvocationCoordinator();
        coordinator.connect(resolver());
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry =
            new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection();
        registry.bindSelection(selection);
        final TextureAtlasAutoLayoutDispatcher dispatcher =
            new TextureAtlasAutoLayoutDispatcher(registry, selection, service(coordinator));

        // Nothing selected at all: pure native pass-through.
        assertFalse(coordinator.ingress(dispatcher.callback()).test(fixture.receiver));
        assertEquals(before, fixture.first.f);

        // A selected id with no registration also falls back, leaving state untouched.
        selection.select(new TextureAtlasLayoutSelection("ghost", false));
        assertFalse(coordinator.ingress(dispatcher.callback()).test(fixture.receiver));
        assertEquals(before, fixture.first.f);
        assertEquals(0.75, fixture.data.b);
    }

    @Test
    void registrationDoesNotAutoSelectAndClosedRegistrationIsNotDispatched() {
        final RecordingLayouts layouts = new RecordingLayouts();
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry =
            new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection();
        registry.bindSelection(selection);
        final TextureAtlasAutoLayoutDispatcher dispatcher =
            new TextureAtlasAutoLayoutDispatcher(registry, selection, layouts);
        final AtomicBoolean invoked = new AtomicBoolean();
        final Registration registration = registry.register(new TextureAtlasLayoutAlgorithm(
            "algo", "Algo", false, (items, constraints) -> {
                invoked.set(true);
                return layouts.snapshotPlan;
            }
        ));

        // Registration alone must not select: native fallback, planner never runs.
        assertFalse(dispatcher.dispatch());
        assertFalse(invoked.get());

        selection.select(new TextureAtlasLayoutSelection("algo", false));
        assertTrue(dispatcher.dispatch());
        assertTrue(invoked.get());
        assertEquals(1, layouts.applied);

        registration.close();
        selection.select(new TextureAtlasLayoutSelection("algo", false));
        assertFalse(dispatcher.dispatch());
        assertEquals(1, layouts.applied);
    }

    @Test
    void inactiveOwnerBlocksDispatch() {
        final RecordingLayouts layouts = new RecordingLayouts();
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry =
            new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection();
        registry.bindSelection(selection);
        final TextureAtlasAutoLayoutDispatcher dispatcher =
            new TextureAtlasAutoLayoutDispatcher(registry, selection, layouts);
        final AtomicBoolean ownerAlive = new AtomicBoolean(true);
        final AtomicBoolean invoked = new AtomicBoolean();
        registry.register(
            new TextureAtlasLayoutAlgorithm("algo", "Algo", false,
                (items, constraints) -> {
                    invoked.set(true);
                    return layouts.snapshotPlan;
                }),
            ownerAlive::get
        );
        selection.select(new TextureAtlasLayoutSelection("algo", false));

        ownerAlive.set(false);
        assertFalse(dispatcher.dispatch());
        assertFalse(invoked.get());
        assertTrue(registry.find("algo").isEmpty());
    }

    @Test
    void plannerFailureAndNullPlanFallBackToNative() {
        final RecordingLayouts layouts = new RecordingLayouts();
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry =
            new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection();
        registry.bindSelection(selection);
        final TextureAtlasAutoLayoutDispatcher dispatcher =
            new TextureAtlasAutoLayoutDispatcher(registry, selection, layouts);
        registry.register(new TextureAtlasLayoutAlgorithm("boom", "Boom", false,
            (items, constraints) -> {
                throw new IllegalStateException("planner blew up");
            }));
        registry.register(new TextureAtlasLayoutAlgorithm("empty", "Empty", false,
            (items, constraints) -> null));

        selection.select(new TextureAtlasLayoutSelection("boom", false));
        assertFalse(dispatcher.dispatch());
        selection.select(new TextureAtlasLayoutSelection("empty", false));
        assertFalse(dispatcher.dispatch());
        assertEquals(0, layouts.applied);
    }

    @Test
    void replacementDuringPlanningPreventsTheStaleWriteback() {
        final RecordingLayouts layouts = new RecordingLayouts();
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry =
            new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection();
        registry.bindSelection(selection);
        final TextureAtlasAutoLayoutDispatcher dispatcher =
            new TextureAtlasAutoLayoutDispatcher(registry, selection, layouts);
        final AtomicBoolean invoked = new AtomicBoolean();
        registry.register(new TextureAtlasLayoutAlgorithm("algo", "Old", false,
            (items, constraints) -> {
                invoked.set(true);
                // A replacement registration wins while the old planner still runs.
                registry.register(new TextureAtlasLayoutAlgorithm("algo", "New", false,
                    (i, c) -> layouts.snapshotPlan));
                return layouts.snapshotPlan;
            }));
        selection.select(new TextureAtlasLayoutSelection("algo", false));

        assertFalse(dispatcher.dispatch());
        assertTrue(invoked.get());
        assertEquals(0, layouts.applied);
        assertEquals("New", registry.find("algo").orElseThrow().displayName());
    }

    @Test
    void plannerClosingItsOwnRegistrationIsRejectedWithoutDeadlockOrWriteback() {
        final RecordingLayouts layouts = new RecordingLayouts();
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry =
            new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection();
        registry.bindSelection(selection);
        final TextureAtlasAutoLayoutDispatcher dispatcher =
            new TextureAtlasAutoLayoutDispatcher(registry, selection, layouts);
        final AtomicReference<Registration> handle = new AtomicReference<>();
        final AtomicReference<Throwable> plannerFailure = new AtomicReference<>();
        handle.set(registry.register(new TextureAtlasLayoutAlgorithm("algo", "Algo", false,
            (items, constraints) -> {
                try {
                    handle.get().close();
                } catch (RuntimeException failure) {
                    plannerFailure.set(failure);
                }
                return layouts.snapshotPlan;
            })));
        selection.select(new TextureAtlasLayoutSelection("algo", false));

        assertFalse(dispatcher.dispatch());
        assertTrue(plannerFailure.get() instanceof IllegalStateException);
        assertEquals(0, layouts.applied);
        assertTrue(registry.find("algo").isEmpty());
        // A later dispatch still resolves cleanly against the closed registration.
        assertFalse(dispatcher.dispatch());
    }

    @Test
    void closeWaitsUninterruptiblyForAnInFlightPlanner() throws Exception {
        final RecordingLayouts layouts = new RecordingLayouts();
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry =
            new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection();
        registry.bindSelection(selection);
        final TextureAtlasAutoLayoutDispatcher dispatcher =
            new TextureAtlasAutoLayoutDispatcher(registry, selection, layouts);
        final CountDownLatch planning = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final Registration registration =
            registry.register(new TextureAtlasLayoutAlgorithm("algo", "Algo", false,
                (items, constraints) -> {
                    planning.countDown();
                    try {
                        assertTrue(finish.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return layouts.snapshotPlan;
                }));
        selection.select(new TextureAtlasLayoutSelection("algo", false));

        final AtomicBoolean dispatchDone = new AtomicBoolean();
        final Thread dispatchThread = new Thread(() -> {
            dispatcher.dispatch();
            dispatchDone.set(true);
        });
        dispatchThread.start();
        assertTrue(planning.await(10, TimeUnit.SECONDS));

        final AtomicBoolean closeDone = new AtomicBoolean();
        final AtomicBoolean closeInterrupted = new AtomicBoolean();
        final Thread closer = new Thread(() -> {
            registration.close();
            closeInterrupted.set(Thread.currentThread().isInterrupted());
            closeDone.set(true);
        });
        closer.start();
        // Interrupting the close must not report success while the planner still runs.
        closer.interrupt();
        Thread.sleep(50);
        assertFalse(closeDone.get());
        finish.countDown();
        dispatchThread.join(10_000);
        closer.join(10_000);
        assertTrue(dispatchDone.get());
        assertTrue(closeDone.get());
        // The recorded interrupt is restored only after the in-flight planner drained.
        assertTrue(closeInterrupted.get());
    }

    private static RuntimeTextureAtlasLayoutService service(
        final TextureAtlasNativeInvocationCoordinator coordinator
    ) {
        final List<CubismFacadeAuditEvent> audit = new ArrayList<>();
        final List<PluginPermission> permissions = List.of(
            permission(RuntimeTextureAtlasLayoutService.READ_PERMISSION),
            permission(RuntimeTextureAtlasLayoutService.WRITE_PERMISSION)
        );
        return new RuntimeTextureAtlasLayoutService(
            new TextureAtlasLayoutCoordinator(),
            new CubismPermissionGate("plugin.texture-atlas", permissions, audit::add, Clock.systemUTC()),
            coordinator
        );
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "test"; }
            @Override public String reason() { return "test"; }
        };
    }

    /** Recording layout service: returns a fixed native-scope snapshot and counts applies. */
    private static final class RecordingLayouts
        implements dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService {
        private final TextureAtlasLayoutTarget target = new TextureAtlasLayoutTarget() { };
        private final TextureAtlasLayoutPlan snapshotPlan =
            TextureAtlasLayoutPlan.currentPage(32, 16, List.of(), 1);
        private final TextureAtlasLayoutSnapshot snapshot = new TextureAtlasLayoutSnapshot(
            target,
            "document",
            "model",
            "atlas",
            TextureAtlasLayoutConstraints.currentPage(32, 16, 1, false, 1),
            List.of(new TextureAtlasLayoutItem("native-item-0", 4, 3)),
            snapshotPlan
        );
        private int applied;

        @Override
        public Optional<TextureAtlasLayoutSnapshot> current() {
            return Optional.of(snapshot);
        }

        @Override
        public TextureAtlasLayoutApplyResult apply(
            final TextureAtlasLayoutTarget target,
            final TextureAtlasLayoutPlan plan
        ) {
            applied++;
            return TextureAtlasLayoutApplyResult.applied();
        }
    }

    private static dev.turboism.mapping.verification.VerifiedMemberResolver resolver() {
        final String receiver = internal(Receiver.class);
        final String settings = internal(Settings.class);
        final String data = internal(Data.class);
        final String item = internal(Item.class);
        final String rect = internal(Rect.class);
        final String affine = internal(Affine.class);
        final String impl = internal(Impl.class);
        final String container = internal(Container.class);
        final String layerRef = internal(LayerRef.class);
        final String editorAffine = internal(EditorAffine.class);
        final List<StaticSelector> selectors = List.of(
            StaticSelector.classSelector(VerifiedTextureAtlasNativeInvocationAdapter.RECEIVER_CLASS, receiver),
            StaticSelector.field(VerifiedTextureAtlasNativeInvocationAdapter.RECEIVER_SETTINGS, receiver, "b", "L" + settings + ";", 0),
            StaticSelector.field(VerifiedTextureAtlasNativeInvocationAdapter.RECEIVER_DATA, receiver, "c", "L" + data + ";", 0),
            StaticSelector.field(VerifiedTextureAtlasNativeInvocationAdapter.RECEIVER_OVERFLOW, receiver, "i", "Ljava/util/ArrayList;", 0),
            method(VerifiedTextureAtlasNativeInvocationAdapter.SETTINGS_MARGIN, settings, "a", "()I"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.SETTINGS_ROTATE, settings, "b", "()Z"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.SETTINGS_MODEL_IMAGE, settings, "c", "()Z"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.SETTINGS_SCALE, settings, "d", "()D"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.DATA_ITEMS, data, "b", "()Ljava/util/ArrayList;"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.DATA_WIDTH, data, "c", "()I"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.DATA_HEIGHT, data, "d", "()I"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.DATA_SCALE, data, "a", "(D)V"),
            StaticSelector.field(VerifiedTextureAtlasNativeInvocationAdapter.DATA_CURRENT_SCALE, data, "b", "D", 0),
            method(VerifiedTextureAtlasNativeInvocationAdapter.DATA_IMPL, data, "a", "()L" + impl + ";"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.IMPL_CONTAINER, impl, "b", "()L" + container + ";"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.CONTAINER_CHILDREN, container, "getChildren", "()[L" + layerRef + ";"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.ITEM_SCALE, item, "c", "()D"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.ITEM_RECT, item, "h", "()L" + rect + ";"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.ITEM_MODEL_RECT, item, "e", "()L" + rect + ";"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.ITEM_WIDTH, item, "f", "()I"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.ITEM_HEIGHT, item, "g", "()I"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.ITEM_TRANSFORM, item, "a", "(L" + affine + ";)V"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.ITEM_EDIT_LAYER, item, "a", "()Ljava/lang/Object;"),
            StaticSelector.field(VerifiedTextureAtlasNativeInvocationAdapter.ITEM_CURRENT_TRANSFORM, item, "f", "L" + affine + ";", 0),
            method(VerifiedTextureAtlasNativeInvocationAdapter.RECT_X, rect, "getX", "()F"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.RECT_Y, rect, "getY", "()F"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.RECT_WIDTH, rect, "getWidth", "()F"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.RECT_HEIGHT, rect, "getHeight", "()F"),
            StaticSelector.constructor(VerifiedTextureAtlasNativeInvocationAdapter.AFFINE_CREATE, affine, "(Ljava/awt/geom/AffineTransform;)V", StaticSelector.ACCESS_PUBLIC),
            method(VerifiedTextureAtlasNativeInvocationAdapter.LAYER_REF_LAYER, layerRef, "getLayer", "()Ljava/lang/Object;"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.LAYER_REF_TRANSFORM, layerRef, "getTransformToParent", "()L" + editorAffine + ";"),
            method(VerifiedTextureAtlasNativeInvocationAdapter.LAYER_REF_SET_TRANSFORM, layerRef, "setTransformToParent", "(L" + editorAffine + ";)V"),
            StaticSelector.constructor(VerifiedTextureAtlasNativeInvocationAdapter.EDITOR_AFFINE_CREATE, editorAffine, "(Ljava/awt/geom/AffineTransform;)V", StaticSelector.ACCESS_PUBLIC)
        );
        return TestVerifiedResolvers.create(
            "5.3.02",
            VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            Set.of(VerifiedTextureAtlasNativeInvocationAdapter.CAPABILITY_ID),
            selectors,
            Receiver.class.getClassLoader()
        );
    }

    private static StaticSelector method(String alias, String owner, String name, String descriptor) {
        return StaticSelector.method(alias, owner, name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) { return type.getName().replace('.', '/'); }

    public static final class Receiver {
        private final Settings b;
        private final Data c;
        private final ArrayList<Item> i = new ArrayList<>();
        Receiver(Settings settings, Data data) { b = settings; c = data; }
    }
    public static final class Settings {
        double scale = 1D;
        boolean rotate;
        boolean modelImage;
        public int a() { return 1; }
        public boolean b() { return rotate; }
        public boolean c() { return modelImage; }
        public double d() { return scale; }
    }
    public static final class Data {
        private double b = 0.75D;
        private final ArrayList<Item> items;
        private final Impl impl;
        Data(ArrayList<Item> items, Impl impl) { this.items = items; this.impl = impl; }
        public Impl a() { return impl; }
        public void a(double value) { b = value; }
        public ArrayList<Item> b() { return items; }
        public int c() { return 32; }
        public int d() { return 16; }
    }
    public static final class Item {
        private Affine f;
        private double q = 1D;
        public double c() { return q; }
        private Rect rect;
        private int fullWidth = 12, fullHeight = 10;
        private final Object layer = new Object();
        Item(float width, float height, double x) { rect = new Rect(0, 0, width, height); f = new Affine(AffineTransform.getTranslateInstance(x, 0)); }
        public Object a() { return layer; }
        public void a(Affine value) { f = value; }
        public Rect e() { return rect; }
        public int f() { return fullWidth; }
        public int g() { return fullHeight; }
        public Rect h() { return rect; }
    }
    public static final class Rect {
        final float x, y, width, height;
        Rect(float x, float y, float width, float height) { this.x=x; this.y=y; this.width=width; this.height=height; }
        public float getX() { return x; }
        public float getY() { return y; }
        public float getWidth() { return width; }
        public float getHeight() { return height; }
    }
    public static final class Affine extends AffineTransform { public Affine(AffineTransform value) { super(value); } }
    public static final class EditorAffine extends AffineTransform { public EditorAffine(AffineTransform value) { super(value); } }
    public static final class LayerRef {
        private final Object layer;
        private EditorAffine transform;
        LayerRef(Object layer, EditorAffine transform) { this.layer=layer; this.transform=transform; }
        public Object getLayer() { return layer; }
        public EditorAffine getTransformToParent() { return transform; }
        public void setTransformToParent(EditorAffine value) {
            try {
                transform = new EditorAffine(value.createInverse().createInverse());
            } catch (java.awt.geom.NoninvertibleTransformException failure) {
                throw new IllegalArgumentException(failure);
            }
        }
    }
    public static final class Container {
        private final LayerRef[] children;
        Container(LayerRef[] children) { this.children=children; }
        public LayerRef[] getChildren() { return children; }
    }
    public static final class Impl {
        private final Container container;
        Impl(Container container) { this.container=container; }
        public Container b() { return container; }
    }
    private static final class Fixture {
        final Item first = new Item(4, 3, 3);
        final Item second = new Item(2, 2, 9);
        final LayerRef firstRef = new LayerRef(first.layer, new EditorAffine(first.f));
        final LayerRef secondRef = new LayerRef(second.layer, new EditorAffine(second.f));
        final Data data = new Data(new ArrayList<>(List.of(first, second)), new Impl(new Container(new LayerRef[]{firstRef, secondRef})));
        final Receiver receiver = new Receiver(new Settings(), data);
    }
}
