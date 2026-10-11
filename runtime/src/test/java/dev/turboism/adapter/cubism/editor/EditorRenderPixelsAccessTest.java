package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorRenderPixelsSelectorContract;
import dev.turboism.sdk.cubism.model.CubismRenderedPixels;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline fixture for {@link EditorRenderPixelsAccess}. The fixture models the
 * exact side effects observed in the 5.3.02 bytecode: a temporary model instance
 * enters and leaves {@code modelInstances} inside {@code getRenderedImage}, the
 * supplied parameter set is copied into it, and the returned {@code CWritableImage}
 * is an owned raster that must be flushed after readout.
 */
class EditorRenderPixelsAccessTest {

    @Test
    void rendersCurrentParametersAndVerifiesLifecycle() {
        final Fixture fixture = new Fixture();
        final CubismRenderedPixels pixels = access("5.3.02").renderPixels("model", fixture.source, fixture.model, 4, 2);
        assertEquals(4, pixels.width());
        assertEquals(2, pixels.height());
        assertEquals(8, pixels.argb().length);
        assertEquals(0xFF102030, pixels.argbAt(1, 1));
        assertTrue(fixture.source.tempCreated, "render must create the temporary model");
        assertTrue(fixture.source.tempDisposed, "render must dispose the temporary model");
        assertTrue(fixture.source.parametersCopied, "render must copy the supplied parameter set");
        assertTrue(fixture.image.flushed, "rendered image must be flushed after readout");
        assertEquals(1, fixture.source.instances.size(), "no instance residue may remain");
    }

    @Test
    void rejectsWhenAuthorizationIsMissing() {
        final Fixture fixture = new Fixture();
        final var access = new EditorRenderPixelsAccess(resolverWithoutCapability(), (identity, model) -> {});
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.renderPixels("model", fixture.source, fixture.model, 4, 2));
    }

    @Test
    void rejectsWhenVersionIsNotExact() {
        final Fixture fixture = new Fixture();
        assertThrows(
                UnsupportedOperationException.class,
                () -> access("5.4.00").renderPixels("model", fixture.source, fixture.model, 4, 2));
        assertThrows(
                UnsupportedOperationException.class,
                () -> access("5.5.00").renderPixels("model", fixture.source, fixture.model, 4, 2));
    }

    @Test
    void rejectsOutOfBudgetDimensionsBeforeNativeCall() {
        final Fixture fixture = new Fixture();
        final var access = access("5.3.02");
        assertThrows(
                IllegalArgumentException.class,
                () -> access.renderPixels("model", fixture.source, fixture.model, 0, 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> access.renderPixels("model", fixture.source, fixture.model, 2049, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> access.renderPixels("model", fixture.source, fixture.model, 2048, 2049));
        assertEquals(0, fixture.source.renderCalls, "native render must not run for bad input");
    }

    @Test
    void nullRenderIsAnHonestFailure() {
        final Fixture fixture = new Fixture();
        fixture.source.nullReturn = true;
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> access("5.3.02").renderPixels("model", fixture.source, fixture.model, 4, 2));
        assertTrue(failure.getMessage().contains("no image"));
    }

    @Test
    void wrongImageTypeIsRejected() {
        final Fixture fixture = new Fixture();
        fixture.source.wrongTypeReturn = true;
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> access("5.3.02").renderPixels("model", fixture.source, fixture.model, 4, 2));
        assertTrue(failure.getMessage().contains("unexpected image type"));
    }

    @Test
    void wrongRasterSizeIsRejected() {
        final Fixture fixture = new Fixture();
        fixture.source.rasterWidth = 8;
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> access("5.3.02").renderPixels("model", fixture.source, fixture.model, 4, 2));
        assertTrue(failure.getMessage().contains("size mismatch"));
    }

    @Test
    void flushFailureSurfaces() {
        final Fixture fixture = new Fixture();
        fixture.image.flushFails = true;
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> access("5.3.02").renderPixels("model", fixture.source, fixture.model, 4, 2));
        assertTrue(failure.getMessage().contains("release failed"));
    }

    @Test
    void nativeFailureStillRunsPostChecksAndKeepsPrimary() {
        final Fixture fixture = new Fixture();
        fixture.source.nativeThrows = true;
        fixture.source.leaveTempInstance = true;
        // The resolver safely wraps the native throw; the residue is still surfaced.
        final RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> access("5.3.02").renderPixels("model", fixture.source, fixture.model, 4, 2));
        assertEquals(1, failure.getSuppressed().length, "residue must be reported as suppressed cleanup failure");
        assertTrue(failure.getSuppressed()[0].getMessage().contains("instance list changed"));
    }

    @Test
    void instanceResidueIsRejected() {
        final Fixture fixture = new Fixture();
        fixture.source.leaveTempInstance = true;
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> access("5.3.02").renderPixels("model", fixture.source, fixture.model, 4, 2));
        assertTrue(failure.getMessage().contains("instance list changed"));
    }

    @Test
    void currentInstanceDriftIsRejected() {
        final Fixture fixture = new Fixture();
        fixture.source.swapCurrent = true;
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> access("5.3.02").renderPixels("model", fixture.source, fixture.model, 4, 2));
        assertTrue(failure.getMessage().contains("current model instance changed"));
    }

    @Test
    void staleGenerationIsRejected() {
        final Fixture fixture = new Fixture();
        final var access = new EditorRenderPixelsAccess(resolver("5.3.02"), (identity, model) -> {
            throw new IllegalStateException("stale generation");
        });
        assertThrows(
                IllegalStateException.class, () -> access.renderPixels("model", fixture.source, fixture.model, 4, 2));
    }

    private EditorRenderPixelsAccess access(final String cubismVersion) {
        return new EditorRenderPixelsAccess(resolver(cubismVersion), (identity, model) -> {});
    }

    private static VerifiedMemberResolver resolver(final String cubismVersion) {
        return TestVerifiedResolvers.create(
                cubismVersion,
                EditorRenderPixelsSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of(EditorRenderPixelsSelectorContract.CAPABILITY_ID),
                selectors(),
                Fixture.class.getClassLoader());
    }

    private static VerifiedMemberResolver resolverWithoutCapability() {
        return TestVerifiedResolvers.create(
                "5.3.02",
                EditorRenderPixelsSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of("cubism.editor-model.model-instance.read"),
                selectors(),
                Fixture.class.getClassLoader());
    }

    private static List<StaticSelector> selectors() {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.model-instance.class", internal(FixtureModel.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.parameter-set.class", internal(FixtureParameterSet.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.color.class", internal(FixtureColor.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.writable-image.class", internal(FixtureWritableImage.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.color.create",
                internal(FixtureColor.class),
                "(FFFF)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.editor-model.model-source.model-instances",
                FixtureModelSource.class,
                "getModelInstances",
                desc(List.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.current-instance",
                FixtureModelSource.class,
                "getCurrentInstance",
                desc(FixtureModel.class)));
        selectors.add(method(
                "cubism.editor-model.model.parameter-set",
                FixtureModel.class,
                "getParameterSet",
                desc(FixtureParameterSet.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.get-rendered-image",
                FixtureModelSource.class,
                "getRenderedImage",
                "(II" + type(FixtureParameterSet.class) + type(FixtureColor.class)
                        + "Ljava/lang/Object;)Ljava/lang/Object;"));
        selectors.add(method(
                "cubism.editor-model.writable-image.j-buffered-image",
                FixtureWritableImage.class,
                "getJBufferedImage",
                desc(BufferedImage.class)));
        selectors.add(method("cubism.editor-model.writable-image.flush", FixtureWritableImage.class, "flush", "()V"));
        return selectors;
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String type(final Class<?> type) {
        return "L" + internal(type) + ";";
    }

    private static String desc(final Class<?> type) {
        return "()" + type(type);
    }

    public static final class Fixture {
        final FixtureModelSource source = new FixtureModelSource();
        final FixtureModel model = source.instances.get(0);
        final FixtureWritableImage image = source.nextImage;
    }

    public static final class FixtureParameterSet {
        boolean copied;
    }

    public static final class FixtureColor {
        public FixtureColor(final float r, final float g, final float b, final float a) {}
    }

    public static final class FixtureModel {
        private final FixtureParameterSet parameterSet = new FixtureParameterSet();

        public FixtureParameterSet getParameterSet() {
            return parameterSet;
        }
    }

    public static final class FixtureWritableImage {
        private final BufferedImage raster;
        boolean flushed;
        boolean flushFails;

        FixtureWritableImage(final BufferedImage raster) {
            this.raster = raster;
        }

        public BufferedImage getJBufferedImage() {
            return raster;
        }

        public void flush() {
            if (flushFails) throw new IllegalStateException("flush exploded");
            flushed = true;
        }
    }

    /** Models CModelSource: temp instance joins/leaves modelInstances, params are copied. */
    public static final class FixtureModelSource {
        final List<FixtureModel> instances = new ArrayList<>(List.of(new FixtureModel()));
        FixtureModel current = instances.get(0);
        final FixtureWritableImage nextImage =
                new FixtureWritableImage(new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB));
        int rasterWidth = 4;
        int renderCalls;
        boolean tempCreated;
        boolean tempDisposed;
        boolean parametersCopied;
        boolean nullReturn;
        boolean wrongTypeReturn;
        boolean nativeThrows;
        boolean leaveTempInstance;
        boolean swapCurrent;

        {
            nextImage.getJBufferedImage().setRGB(1, 1, 0xFF102030);
        }

        public List<FixtureModel> getModelInstances() {
            return instances;
        }

        public FixtureModel getCurrentInstance() {
            return current;
        }

        public Object getRenderedImage(
                final int width,
                final int height,
                final FixtureParameterSet parameters,
                final FixtureColor background,
                final Object callback) {
            renderCalls++;
            final FixtureModel temp = new FixtureModel();
            tempCreated = true;
            instances.add(temp);
            try {
                if (parameters != null) parameters.copied = true;
                parametersCopied = parameters == current.parameterSet;
                if (nativeThrows) throw new IllegalStateException("render exploded");
                if (nullReturn) return null;
                if (wrongTypeReturn) return "not-an-image";
                if (rasterWidth != nextImage.getJBufferedImage().getWidth()) {
                    return new FixtureWritableImage(
                            new BufferedImage(rasterWidth, height, BufferedImage.TYPE_INT_ARGB));
                }
                if (swapCurrent) {
                    // A buggy native path would leave currentInstance at the temp model.
                    current = temp;
                }
                return nextImage;
            } finally {
                if (!leaveTempInstance) {
                    instances.remove(temp);
                    tempDisposed = true;
                }
            }
        }
    }
}
