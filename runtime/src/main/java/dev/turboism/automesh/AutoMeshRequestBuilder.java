package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Drawable;
import java.util.Map;
import java.util.Objects;

/**
 * Reads one ArtMesh into the immutable request an auto-mesh algorithm receives.
 *
 * <p>Reads run on the Editor UI thread, because every {@link Drawable} accessor is host-affine. The
 * returned request owns detached snapshots only, so the compute may run after the user changes the
 * selection without touching a stale host handle.</p>
 */
public final class AutoMeshRequestBuilder {

    /** Keyform count of a target that carries its default form only. */
    private static final int SINGLE_KEYFORM_COUNT = 1;

    /**
     * Keyform count reported when the stored-form read is unavailable but the target declares
     * parameter bindings: a bound target stores at least one form beside its default form.
     */
    private static final int MINIMUM_MULTI_KEYFORM_COUNT = 2;

    /**
     * Builds the request for one target.
     *
     * @param target the selected ArtMesh; must be read on the UI thread
     * @param parameterValues the complete canonical value set the run resolved
     * @return an immutable snapshot of the target
     * @throws UnsupportedOperationException when the host cannot read the target geometry
     */
    public AutoMeshRequest build(final Drawable target, final Map<String, String> parameterValues) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(parameterValues, "parameterValues");
        final ArtMeshGeometry geometry = target.geometry();
        return new AutoMeshRequest(
                target.id().value(),
                geometry,
                target.texturePixels(),
                keyformCount(target),
                parameterValues,
                AutoMeshRequest.UNSPECIFIED_VERTEX_TARGET);
    }

    private static int keyformCount(final Drawable target) {
        try {
            return Math.max(target.authoringSnapshot().forms().size(), SINGLE_KEYFORM_COUNT);
        } catch (UnsupportedOperationException unavailable) {
            return hasParameterBindings(target) ? MINIMUM_MULTI_KEYFORM_COUNT : SINGLE_KEYFORM_COUNT;
        }
    }

    private static boolean hasParameterBindings(final Drawable target) {
        try {
            return !target.getParameterBindings().isEmpty();
        } catch (UnsupportedOperationException unavailable) {
            return false;
        }
    }
}
