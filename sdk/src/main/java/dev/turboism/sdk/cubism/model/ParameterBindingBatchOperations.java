package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import java.util.List;

/** Atomic model-level parameter-binding inversion and GUID-transfer operations.
 * <p>{@link #transferClamped(ParameterBindingTransferPlan)} linearly remaps each source keyform
 * coordinate from the source parameter range into the destination parameter range and clamps the
 * result to that range, retaining each coordinate's keyform association.
 * {@link #transferMorphClamped(ParameterBindingTransferPlan)} moves every Morph Target setting
 * unchanged, without remapping or clamping. The ordinary
 * {@link #transfer(ParameterBindingTransferPlan)} operation keeps its existing semantics.</p>
 */
@CubismEditor
public interface ParameterBindingBatchOperations {

    /**
     * Atomically changes existing axes of one ArtMesh and rebuilds its cache once.
     * All settings and the combined cache budget are validated before mutation.
     * Empty settings are a no-op; unsupported backends fail closed.
     */
    default void setInterpolations(
            ParameterBindingTarget target,
            java.util.Map<dev.turboism.sdk.cubism.id.ParameterId, ParameterInterpolation> settings) {
        throw new UnsupportedOperationException("Combined interpolation authoring is unavailable.");
    }

    /**
     * Atomically inverts the bindings of every listed target.
     *
     * @param targets the binding targets to invert
     */
    void invert(List<ParameterBindingTarget> targets);

    /**
     * Atomically transfers bindings according to {@code plan}. Equal source and target
     * parameters invert only the existing binding.
     *
     * @param plan the source-to-destination transfer plan
     */
    void transfer(ParameterBindingTransferPlan plan);

    /**
     * Atomically transfers bindings while remapping every source keyform value linearly from the
     * source parameter range into the destination range and clamping it to that range; a source
     * range with zero span is left unremapped. When {@code plan.invertAfterTransfer()} is true,
     * each value is reflected across the source range first and then remapped; the keyform
     * association is retained. The
     * default is fail-closed for binary compatibility. Equal source and target parameters
     * reflect the existing coordinates in place without remapping.
     */
    default void transferClamped(final ParameterBindingTransferPlan plan) {
        throw new UnsupportedOperationException("Clamped parameter-binding transfer is not supported.");
    }

    /**
     * Atomically transfers every Morph Target point bound to the source parameter while keeping
     * each mapped key value unchanged (the whole setting moves, no remapping or clamping). The
     * default is fail-closed for binary compatibility. Equal source and target parameters
     * negate the existing Morph Target keys in place.
     */
    default void transferMorphClamped(final ParameterBindingTransferPlan plan) {
        throw new UnsupportedOperationException("Clamped Morph Target transfer is not supported.");
    }
}
