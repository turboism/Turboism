package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.awt.geom.AffineTransform;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasNativeInvocationCoordinatorTest {

    @Test
    void nativeScopeProjectsAndWritesTemporaryStateWithoutCallingProvider() {
        final Fixture fixture = new Fixture();
        final RecordingProvider provider = new RecordingProvider();
        final TextureAtlasLayoutCoordinator persistent = new TextureAtlasLayoutCoordinator();
        persistent.connect(provider);
        final TextureAtlasNativeInvocationCoordinator nativeInvocations = new TextureAtlasNativeInvocationCoordinator();
        nativeInvocations.connect(resolver());
        final RuntimeTextureAtlasLayoutService service = service(persistent, nativeInvocations);

        final boolean handled = nativeInvocations.ingress(() -> {
            final var snapshot = service.current().orElseThrow();
            assertEquals(List.of("native-item-0", "native-item-1"),
                snapshot.items().stream().map(item -> item.textureId()).toList());
            final var result = service.apply(snapshot.target(), plan());
            assertTrue(result.status().isPresent());
            return true;
        }).test(fixture.receiver);

        assertTrue(handled);
        assertEquals(0, provider.currentCount.get());
        assertEquals(0, provider.applyCount.get());
        assertEquals(AffineTransform.getTranslateInstance(1, 1), fixture.first.f);
        assertEquals(AffineTransform.getTranslateInstance(7, 1), fixture.second.f);
        assertEquals(fixture.first.f, fixture.firstRef.transform);
        assertEquals(fixture.second.f, fixture.secondRef.transform);
        assertTrue(fixture.receiver.i.isEmpty());
    }

    @Test
    void callbackFailureAfterApplyRestoresEveryTemporaryOutput() {
        final Fixture fixture = new Fixture();
        fixture.receiver.i.add(fixture.second);
        final AffineTransform firstBefore = new AffineTransform(fixture.first.f);
        final AffineTransform secondBefore = new AffineTransform(fixture.second.f);
        final AffineTransform firstRefBefore = new AffineTransform(fixture.firstRef.transform);
        final AffineTransform secondRefBefore = new AffineTransform(fixture.secondRef.transform);
        final TextureAtlasNativeInvocationCoordinator nativeInvocations = new TextureAtlasNativeInvocationCoordinator();
        nativeInvocations.connect(resolver());
        final RuntimeTextureAtlasLayoutService service = service(new TextureAtlasLayoutCoordinator(), nativeInvocations);

        final boolean handled = nativeInvocations.ingress(() -> {
            final var snapshot = service.current().orElseThrow();
            assertTrue(service.apply(snapshot.target(), plan()).status().isPresent());
            return false;
        }).test(fixture.receiver);

        assertFalse(handled);
        assertEquals(firstBefore, fixture.first.f);
        assertEquals(secondBefore, fixture.second.f);
        assertEquals(firstRefBefore, fixture.firstRef.transform);
        assertEquals(secondRefBefore, fixture.secondRef.transform);
        assertEquals(List.of(fixture.second), fixture.receiver.i);
        assertEquals(0.75D, fixture.data.b);
        assertTrue(service.current().isEmpty());
    }

    @Test
    void connectionReplacementDuringCallbackCancelsAndRestoresHandledOutput() {
        final Fixture fixture = new Fixture();
        final AffineTransform firstBefore = new AffineTransform(fixture.first.f);
        final AffineTransform secondBefore = new AffineTransform(fixture.second.f);
        final TextureAtlasNativeInvocationCoordinator nativeInvocations =
            new TextureAtlasNativeInvocationCoordinator();
        nativeInvocations.connect(resolver());
        final RuntimeTextureAtlasLayoutService service = service(
            new TextureAtlasLayoutCoordinator(), nativeInvocations
        );

        final boolean handled = nativeInvocations.ingress(() -> {
            final var snapshot = service.current().orElseThrow();
            assertTrue(service.apply(snapshot.target(), plan()).status().isPresent());
            nativeInvocations.connect(resolver());
            return true;
        }).test(fixture.receiver);

        assertFalse(handled);
        assertEquals(firstBefore, fixture.first.f);
        assertEquals(secondBefore, fixture.second.f);
        assertTrue(service.current().isEmpty());
    }

    @Test
    void callbackThrowableFallsBackAndRestoresHandledOutput() {
        final Fixture fixture = new Fixture();
        final AffineTransform firstBefore = new AffineTransform(fixture.first.f);
        final AffineTransform secondBefore = new AffineTransform(fixture.second.f);
        final TextureAtlasNativeInvocationCoordinator nativeInvocations =
            new TextureAtlasNativeInvocationCoordinator();
        nativeInvocations.connect(resolver());
        final RuntimeTextureAtlasLayoutService service = service(
            new TextureAtlasLayoutCoordinator(), nativeInvocations
        );

        final boolean handled = nativeInvocations.ingress(() -> {
            final var snapshot = service.current().orElseThrow();
            assertTrue(service.apply(snapshot.target(), plan()).status().isPresent());
            throw new AssertionError("callback failed");
        }).test(fixture.receiver);

        assertFalse(handled);
        assertEquals(firstBefore, fixture.first.f);
        assertEquals(secondBefore, fixture.second.f);
        assertTrue(service.current().isEmpty());
    }

    @Test
    void nestedInvocationDeclinesWithoutRunningNestedCallback() {
        final Fixture fixture = new Fixture();
        final AtomicInteger nestedCalls = new AtomicInteger();
        final TextureAtlasNativeInvocationCoordinator nativeInvocations =
            new TextureAtlasNativeInvocationCoordinator();
        nativeInvocations.connect(resolver());

        final boolean handled = nativeInvocations.ingress(() -> {
            final boolean nested = nativeInvocations.ingress(() -> {
                nestedCalls.incrementAndGet();
                return true;
            }).test(fixture.receiver);
            assertFalse(nested);
            return false;
        }).test(fixture.receiver);

        assertFalse(handled);
        assertEquals(0, nestedCalls.get());
    }

    private static TextureAtlasLayoutPlan plan() {
        return new TextureAtlasLayoutPlan(32, 16, 1, List.of(
            new TextureAtlasPlacement("native-item-0", 0, 1, 1, 4, 3, false),
            new TextureAtlasPlacement("native-item-1", 0, 7, 1, 2, 2, false)
        ));
    }

    private static RuntimeTextureAtlasLayoutService service(
        final TextureAtlasLayoutCoordinator persistent,
        final TextureAtlasNativeInvocationCoordinator nativeInvocations
    ) {
        final List<CubismFacadeAuditEvent> audit = new ArrayList<>();
        final List<PluginPermission> permissions = List.of(
            permission(RuntimeTextureAtlasLayoutService.READ_PERMISSION),
            permission(RuntimeTextureAtlasLayoutService.WRITE_PERMISSION)
        );
        return new RuntimeTextureAtlasLayoutService(
            persistent,
            new CubismPermissionGate("plugin.texture-atlas", permissions, audit::add, Clock.systemUTC()),
            nativeInvocations
        );
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "test"; }
            @Override public String reason() { return "test"; }
        };
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

    private static final class RecordingProvider implements TextureAtlasLayoutProvider {
        final AtomicInteger currentCount = new AtomicInteger();
        final AtomicInteger applyCount = new AtomicInteger();
        @Override public Optional<TextureAtlasAuthoringState> current() { currentCount.incrementAndGet(); return Optional.empty(); }
        @Override public ApplyOutcome apply(TextureAtlasAuthoringState expected, TextureAtlasLayoutPlan plan) {
            applyCount.incrementAndGet(); return ApplyOutcome.REJECTED;
        }
    }

    public static final class Receiver {
        private final Settings b;
        private final Data c;
        private final ArrayList<Item> i = new ArrayList<>();
        Receiver(Settings settings, Data data) { b = settings; c = data; }
    }
    public static final class Settings {
        public int a() { return 1; }
        public boolean b() { return false; }
        public boolean c() { return false; }
        public double d() { return 1D; }
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
        private final Rect rect;
        private final Object layer = new Object();
        Item(float width, float height, double x) { rect = new Rect(0, 0, width, height); f = new Affine(AffineTransform.getTranslateInstance(x, 0)); }
        public Object a() { return layer; }
        public void a(Affine value) { f = value; }
        public Rect e() { return rect; }
        public int f() { return (int) rect.width; }
        public int g() { return (int) rect.height; }
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
        public void setTransformToParent(EditorAffine value) { transform=value; }
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
