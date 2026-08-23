package dev.turboism.sdk.cubism.model;


import java.util.List;

/** Atomic model-level parameter-binding inversion and GUID-transfer operations.
 * <p>{@link #transferClamped(ParameterBindingTransferPlan)} linearly remaps each source keyform
 * coordinate from the source parameter range into the destination parameter range and clamps the
 * result to that range, retaining each coordinate's keyform association.
 * {@link #transferMorphClamped(ParameterBindingTransferPlan)} moves every Morph Target setting
 * unchanged, without remapping or clamping. The ordinary
 * {@link #transfer(ParameterBindingTransferPlan)} operation keeps its existing semantics.</p>
 */
public interface ParameterBindingBatchOperations {

    void invert(List<ParameterBindingTarget> targets);

    void transfer(ParameterBindingTransferPlan plan);

    /**
     * Atomically transfers bindings while remapping every source keyform value linearly from the
     * source parameter range into the destination range and clamping it to that range; a source
     * range with zero span is left unremapped. When {@code plan.invertAfterTransfer()} is true,
     * each value is negated first and then remapped; the keyform association is retained. The
     * default is fail-closed for binary compatibility.
     */
    default void transferClamped(final ParameterBindingTransferPlan plan) {
        throw new UnsupportedOperationException("Clamped parameter-binding transfer is not supported.");
    }

    /**
     * Atomically transfers every Morph Target point bound to the source parameter while keeping
     * each mapped key value unchanged (the whole setting moves, no remapping or clamping). The
     * default is fail-closed for binary compatibility.
     */
    default void transferMorphClamped(final ParameterBindingTransferPlan plan) {
        throw new UnsupportedOperationException("Clamped Morph Target transfer is not supported.");
    }
}
