package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.core.OwnedModel;

/**
 * Runtime-internal write seam over an owned Core model's parameter values.
 *
 * <p>This is deliberately NOT part of the public SDK: the public
 * {@link OwnedModel} projection stays read-only. The protected-export behavior
 * oracle needs to replay sampled parameter values through the staged
 * {@code .moc3} inside the plugin process, which requires writing the live
 * {@code CubismParameters.getValues()} array through the already-bound verified
 * call sites. Implementations live only on adapter-owned runtime objects;
 * callers detect support via {@code instanceof} and fail closed otherwise.</p>
 */
public interface OwnedModelParameterWriter {

    /**
     * Writes {@code parameterId}'s current value on {@code model}. The caller is
     * responsible for invoking {@link OwnedModel#update()} afterwards to
     * re-evaluate model output.
     *
     * @throws IllegalStateException when {@code model} is not owned by this
     *     runtime, is closed, or {@code parameterId} is absent
     */
    void writeParameterValue(OwnedModel model, String parameterId, float value);
}
