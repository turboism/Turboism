package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.RawLayerId;
import dev.turboism.sdk.cubism.model.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Structural comparisons must normalize generated identities without erasing real differences. */
public final class PsdStructuralStateTest {
    private static final PsdStructuralState.Basis BASIS = new PsdStructuralState.Basis(
        Set.of("original"), Set.of("image"), Map.of("mesh", "mesh-guid"));

    public static void main(final String[] args) throws Exception {
        final var nativeState = state("native", "layer-old", "layer-new", 0, false, false);
        final var automatic = state("automatic", "layer-old", "other-new", 0, false, false);
        equal(observe(nativeState), observe(automatic), "generated raw/layer GUIDs normalize");
        different(observe(nativeState), observe(state("native", "changed-old", "layer-new", 0, false, false)),
            "original layer identity is preserved");
        different(observe(nativeState), observe(state("native", "layer-old", "layer-new", 1, false, false)),
            "input order is preserved");
        different(observe(nativeState), observe(state("native", "layer-old", "layer-new", 0, true, false)),
            "empty raw binding entry differs from absence");
        different(observe(nativeState), observe(state("native", "layer-old", "layer-new", 0, false, true)),
            "current raw differs");
        final var reversedLayers = copy(nativeState, List.of(nativeState.rawImages().get(0),
            raw("native", List.of(layer("native", "extra", "extra"), layer("native", "layer-new", "layer")))),
            nativeState.modelImages());
        different(observe(nativeState), observe(reversedLayers), "layer insertion and order are visible");
        expectRejected(() -> observe(copy(nativeState, List.of(nativeState.rawImages().get(0),
            nativeState.rawImages().get(1), raw("second-new", List.of())), nativeState.modelImages())));
        expectRejected(() -> observe(copy(nativeState, List.of(nativeState.rawImages().get(0)),
            nativeState.modelImages())));
        expectRejected(() -> observe(copy(nativeState, nativeState.rawImages(),
            List.of(nativeState.modelImages().get(0), nativeState.modelImages().get(0)))));
        expectRejected(() -> observe(TextureRelationsSnapshot.unavailable()));
        final var withNew = new ArrayList<>(nativeState.modelImages());
        withNew.add(image("new-one", "native", "layer-new", 0, false, false));
        withNew.add(image("new-two", "native", "layer-new", 0, false, false));
        expectRejected(() -> observe(copy(nativeState, nativeState.rawImages(), withNew)));
        final var newA = copy(nativeState, nativeState.rawImages(),
            List.of(nativeState.modelImages().get(0), image("new-a", "native", "layer-new", 0, false, false)));
        final var newB = copy(nativeState, nativeState.rawImages(),
            List.of(nativeState.modelImages().get(0), image("new-b", "native", "layer-new", 0, false, false)));
        equal(observe(newA), observe(newB), "one unambiguous new image matches by bound structure");
        captureChecks(nativeState);
        System.out.println("PASS: PsdStructuralStateTest");
    }

    private static void captureChecks(final TextureRelationsSnapshot relations) throws Exception {
        final CubismModel model = model(0f);
        expectRejected(() -> PsdStructuralState.capture(model, relations, BASIS));
        expectRejected(() -> PsdStructuralState.basis(model, relations));
        final AtomicReference<Map<String, String>> first = new AtomicReference<>();
        final AtomicReference<Map<String, String>> moved = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final var basis = PsdStructuralState.basis(model, relations);
            first.set(PsdStructuralState.capture(model, relations, basis));
            moved.set(PsdStructuralState.capture(model(1f), relations, basis));
        });
        different(first.get(), moved.get(), "authoring vertex movement must be observed");
        equal("SDK_DETAILS_UNAVAILABLE", first.get().get("rawInputTransformAndClipping"),
            "missing transform/clipping details remain explicit");
    }

    private static CubismModel model(final float x) {
        final Drawable mesh = proxy(Drawable.class, Map.ofEntries(
            Map.entry("id", new ArtMeshId("mesh")), Map.entry("guid", "mesh-guid"),
            Map.entry("name", "mesh"), Map.entry("geometry", new ArtMeshGeometry(
                List.of(new Point2(x, 0f)), List.of(new Point2(0f, 0f)), List.of())),
            Map.entry("parentPartId", Optional.empty()), Map.entry("parentDeformerId", Optional.empty()),
            Map.entry("visible", true), Map.entry("locked", false), Map.entry("getOpacity", 1f),
            Map.entry("drawOrder", 0), Map.entry("maskIds", List.of()), Map.entry("invertedMask", false),
            Map.entry("culling", false), Map.entry("multiplyColor", new Color(1f, 1f, 1f, 1f)),
            Map.entry("screenColor", new Color(0f, 0f, 0f, 1f))));
        final Drawables drawables = proxy(Drawables.class, Map.of("all", List.of(mesh)));
        final Canvas canvas = proxy(Canvas.class, Map.of("widthPixels", 2048f,
            "heightPixels", 2048f, "originXPixels", 0f, "originYPixels", 0f, "pixelsPerUnit", 100f));
        return proxy(CubismModel.class, Map.of("drawables", drawables, "canvas", canvas));
    }

    private static <T> T proxy(final Class<T> type, final Map<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (object, method, args) -> {
                if (!values.containsKey(method.getName())) throw new AssertionError(
                    "unexpected host access: " + method);
                return values.get(method.getName());
            }));
    }

    private static TextureRelationsSnapshot state(final String incoming, final String oldLayer,
        final String newLayer, final int order, final boolean emptyOld, final boolean oldCurrent) {
        return new TextureRelationsSnapshot(TextureRelationsSnapshot.Availability.AVAILABLE,
            "observation", 1, 1, List.of(raw("original", List.of(layer("original", oldLayer, "layer"))),
                raw(incoming, List.of(layer(incoming, newLayer, "layer")))),
            List.of(image("image", incoming, newLayer, order, emptyOld, oldCurrent)), List.of(),
            List.of(new ArtMeshTextureInputs(new ArtMeshId("mesh"),
                List.of(TextureInputBinding.modelImage(new ModelImageId("image"))), OptionalInt.of(0))));
    }

    private static ModelImageRelation image(final String id, final String raw, final String layer,
        final int order, final boolean emptyOld, final boolean oldCurrent) {
        final RawImageId incoming = new RawImageId(raw);
        final RawLayerBinding binding = new RawLayerBinding(incoming, new RawLayerId(layer), order,
            RawLayerBinding.DetailAvailability.AVAILABLE, RawLayerBinding.DetailAvailability.UNAVAILABLE);
        final Map<RawImageId, List<RawLayerBinding>> inputs = emptyOld
            ? Map.of(incoming, List.of(binding), new RawImageId("original"), List.of())
            : Map.of(incoming, List.of(binding));
        return new ModelImageRelation(new ModelImageId(id), proxy(ModelImageEntry.class,
            Map.of("id", new ModelImageId(id), "name", "image", "width", 20, "height", 20)),
            List.of(incoming), Optional.of(oldCurrent ? new RawImageId("original") : incoming),
            inputs, List.of(new ArtMeshId("mesh")));
    }

    private static RawImageDetails raw(final String id, final List<RawLayerDetails> layers) {
        return new RawImageDetails(proxy(RawTexture.class, Map.of("id", new RawImageId(id),
            "name", "external-edit.psd", "width", 20, "height", 20)), RawImageDetails.SourceKind.PSD,
            layers, false, Optional.empty(), Optional.empty(), Optional.of(true));
    }

    private static RawLayerDetails layer(final String raw, final String id, final String name) {
        return new RawLayerDetails(new RawLayerId(id), new RawImageId(raw), RawLayerDetails.EntryKind.PIXEL,
            name, Optional.of("42"), List.of());
    }

    private static TextureRelationsSnapshot copy(final TextureRelationsSnapshot base,
        final List<RawImageDetails> raw, final List<ModelImageRelation> images) {
        return new TextureRelationsSnapshot(base.availability(), base.binding(), base.generation(),
            base.revision(), raw, images, base.groups(), base.artMeshInputs());
    }

    private static Map<String, String> observe(final TextureRelationsSnapshot state) {
        return PsdStructuralState.relations(state, BASIS);
    }

    private static void equal(final Object expected, final Object actual, final String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + expected + " != " + actual);
    }
    private static void different(final Object left, final Object right, final String label) {
        if (left.equals(right)) throw new AssertionError(label);
    }
    private static void expectRejected(final Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("ambiguous, unavailable or unbound structure was accepted");
    }
}
