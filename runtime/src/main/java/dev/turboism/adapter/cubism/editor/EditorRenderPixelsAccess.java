package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorRenderPixelsSelectorContract;
import dev.turboism.sdk.cubism.model.CubismRenderedPixels;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact, generation-bound Editor projection for native offscreen model rendering.
 *
 * <p>On every generation this access admits, {@code CModelSource.getRenderedImage(
 * int,int,CParameterSet,CColor,Function1)} renders the supplied parameter set through a temporary model
 * instance into an owned {@code CWritableImage}. The temporary instance enters and
 * leaves {@code modelInstances} inside the call; this access observes membership
 * and order before and after, re-checks the generation on the same EDT, copies
 * pixels into a Turboism-owned value, and flushes the returned native image in a
 * finally block — including when the native call itself failed. A null render,
 * wrong type/size, pixel-read failure, flush failure, instance-list residue or
 * identity drift all fail closed; nothing is cached, rescaled or substituted.</p>
 */
final class EditorRenderPixelsAccess {

    private static final String INSTANCES_ALIAS = "cubism.editor-model.model-source.model-instances";
    private static final String CURRENT_INSTANCE_ALIAS = "cubism.editor-model.model-source.current-instance";
    private static final String PARAMETER_SET_ALIAS = "cubism.editor-model.model.parameter-set";
    private static final String PARAMETER_SET_CLASS_ALIAS = "cubism.editor-model.parameter-set.class";
    private static final String COLOR_CREATE_ALIAS = "cubism.editor-model.color.create";
    private static final String COLOR_CLASS_ALIAS = "cubism.editor-model.color.class";
    private static final String RENDER_ALIAS = "cubism.editor-model.model-source.get-rendered-image";
    private static final String IMAGE_CLASS_ALIAS = "cubism.editor-model.writable-image.class";
    private static final String IMAGE_RASTER_ALIAS = "cubism.editor-model.writable-image.j-buffered-image";
    private static final String IMAGE_FLUSH_ALIAS = "cubism.editor-model.writable-image.flush";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorRenderPixelsAccess(
            final VerifiedMemberResolver resolver, final EditorParameterCombinedAccess.ModelGuard modelGuard) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    CubismRenderedPixels renderPixels(
            final String identity, final Object source, final Object model, final int width, final int height) {
        requireAuthorization();
        if (EditorRenderPixelsSelectorContract.SUPPORTED_CUBISM_VERSIONS.stream()
                .noneMatch(resolver::isExactCubismVersion)) {
            throw new UnsupportedOperationException(
                    "Cubism native model rendering is unavailable on Cubism " + resolver.cubismVersion() + ".");
        }
        if (width < 1
                || width > CubismRenderedPixels.MAX_DIMENSION
                || height < 1
                || height > CubismRenderedPixels.MAX_DIMENSION
                || (long) width * (long) height > CubismRenderedPixels.MAX_PIXELS) {
            throw new IllegalArgumentException("render dimensions out of bounds: " + width + "x" + height);
        }
        return EditorHostThread.dispatch(
                "Cubism model native pixel render", () -> renderOnEdt(identity, source, model, width, height));
    }

    /**
     * The complete render lifecycle in ONE EDT closure: binding/current-model
     * recheck, instance snapshots, parameter-set read, native render, raster
     * readback, image flush and all post-checks. Off-EDT callers reach the
     * same native surface only through this dispatch.
     */
    private CubismRenderedPixels renderOnEdt(
            final String identity, final Object source, final Object model, final int width, final int height) {
        modelGuard.requireCurrent(identity, model);
        final List<Object> instancesBefore = instanceSnapshot(source);
        final Object currentBefore = currentInstance(source);

        Object image = null;
        Throwable primary = null;
        try {
            final Object parameters = resolver.invoke(PARAMETER_SET_ALIAS, currentBefore);
            requireInstance(PARAMETER_SET_CLASS_ALIAS, parameters, "Editor current parameter set is unavailable.");
            final Object background = resolver.construct(COLOR_CREATE_ALIAS, 0F, 0F, 0F, 0F);
            requireInstance(COLOR_CLASS_ALIAS, background, "Editor render background color is invalid.");
            image = resolver.invoke(
                    RENDER_ALIAS,
                    source,
                    Integer.valueOf(width),
                    Integer.valueOf(height),
                    parameters,
                    background,
                    null);
            if (image == null) {
                throw unavailable("Editor native render returned no image.");
            }
            requireInstance(IMAGE_CLASS_ALIAS, image, "Editor native render returned an unexpected image type.");
            final Object raster = resolver.invoke(IMAGE_RASTER_ALIAS, image);
            if (!(raster instanceof BufferedImage buffered)) {
                throw unavailable("Editor rendered raster is unavailable.");
            }
            if (buffered.getWidth() != width || buffered.getHeight() != height) {
                throw unavailable(
                        "Editor rendered raster size mismatch: " + buffered.getWidth() + "x" + buffered.getHeight());
            }
            final int[] argb = buffered.getRGB(0, 0, width, height, null, 0, width);
            return new CubismRenderedPixels(width, height, argb);
        } catch (RuntimeException failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                afterEachCall(image, source, identity, model, instancesBefore, currentBefore);
            } catch (RuntimeException cleanup) {
                if (primary != null) {
                    primary.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private void afterEachCall(
            final Object image,
            final Object source,
            final String identity,
            final Object model,
            final List<Object> instancesBefore,
            final Object currentBefore) {
        RuntimeException failure = null;
        if (image != null) {
            try {
                resolver.invoke(IMAGE_FLUSH_ALIAS, image);
            } catch (RuntimeException e) {
                failure = accumulate(failure, unavailable("Editor rendered image release failed.", e));
            }
        }
        try {
            final List<Object> instancesAfter = instanceSnapshot(source);
            if (instancesAfter.size() != instancesBefore.size()
                    || !identityOrderMatches(instancesBefore, instancesAfter)) {
                failure = accumulate(failure, unavailable("Editor model-instance list changed across native render."));
            }
        } catch (RuntimeException e) {
            failure = accumulate(failure, e);
        }
        try {
            if (currentInstance(source) != currentBefore) {
                failure =
                        accumulate(failure, unavailable("Editor current model instance changed across native render."));
            }
        } catch (RuntimeException e) {
            failure = accumulate(failure, e);
        }
        try {
            modelGuard.requireCurrent(identity, model);
        } catch (RuntimeException e) {
            failure = accumulate(failure, e);
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException accumulate(final RuntimeException failure, final RuntimeException next) {
        if (failure == null) return next;
        failure.addSuppressed(next);
        return failure;
    }

    private void requireAuthorization() {
        if (!resolver.authorizesFeature(
                EditorRenderPixelsSelectorContract.ADAPTER_SLICE_ID,
                EditorRenderPixelsSelectorContract.CAPABILITY_ID,
                EditorRenderPixelsSelectorContract.REQUIRED_ALIASES)) {
            throw new UnsupportedOperationException(
                    "Cubism native model rendering is unavailable without exact verified host evidence.");
        }
    }

    private List<Object> instanceSnapshot(final Object source) {
        final Object raw = resolver.invoke(INSTANCES_ALIAS, source);
        if (!(raw instanceof Iterable<?> iterable)) {
            throw unavailable("Editor model-instance list is unavailable.");
        }
        final List<Object> snapshot = new ArrayList<>();
        for (Object instance : iterable) {
            snapshot.add(instance);
        }
        return snapshot;
    }

    private Object currentInstance(final Object source) {
        return resolver.invoke(CURRENT_INSTANCE_ALIAS, source);
    }

    private static boolean identityOrderMatches(final List<Object> before, final List<Object> after) {
        for (int index = 0; index < before.size(); index++) {
            if (before.get(index) != after.get(index)) return false;
        }
        return true;
    }

    private void requireInstance(final String classAlias, final Object value, final String message) {
        if (!resolver.isInstance(classAlias, value)) {
            throw unavailable(message);
        }
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException unavailable(final String message, final Throwable cause) {
        return new IllegalStateException(message, cause);
    }
}
