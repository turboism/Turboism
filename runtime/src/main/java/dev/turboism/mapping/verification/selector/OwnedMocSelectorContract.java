package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for the owned-Moc workflow (plugin-owned Core
 * models built from {@code .moc3} bytes).
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.2.03 and 5.3.02):</p>
 * <ul>
 *   <li>{@code com.live2d.sdk.cubism.core.CubismMoc.instantiate(byte[])} exists in both
 *       5.2.03 and 5.3.02 as a public static method
 *       ({@code ([B)Lcom/live2d/sdk/cubism/core/CubismMoc;}).</li>
 *   <li>{@code CubismMoc.instantiateModel()} ({@code ()Lcom/live2d/sdk/cubism/core/CubismModel;})
 *       exists in both versions.</li>
 *   <li>{@code CubismMoc.getNativeHandle()} ({@code ()J}) and {@code CubismMoc.close()}
 *       ({@code ()V}) exist in both versions.</li>
 *   <li>{@code CubismModel.getNativeHandle()} ({@code ()J}), {@code CubismModel.update()}
 *       ({@code ()V}), and {@code CubismModel.close()} ({@code ()V}) exist in both
 *       versions.</li>
 *   <li>{@code CubismMoc.getMocVersion()} exists in 5.3.02 only; the owned-Moc loader
 *       uses the byte-level {@code Live2DCubismCore.getMocVersion(byte[])} instead
 *       (present in both versions), so this contract needs no version-specific alias
 *       and the 5.2 profile never fails closed on version reads.</li>
 * </ul>
 *
 * <p>This feature contract is promoted into the generated
 * {@link CorePublicApiSelectorContract} roster under the {@code OWNED_MOC} role so the
 * verified Core record carries the lifecycle evidence into provider admission. The role
 * keeps the aliases out of the structural-read call-site table: they are bound and invoked
 * through {@link CorePublicApiProvider} owned-Moc operations instead, and feature admission
 * is still checked with {@link VerifiedMemberResolver#authorizesFeature}.</p>
 */
public final class OwnedMocSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.core-model.readonly";

    public static final String CAPABILITY_ID = "cubism.core.owned-moc.read";

    public static final String MOC_CLASS = "cubism.core.moc.class";

    public static final String MOC_INSTANTIATE = "cubism.core.moc.instantiate";

    public static final String MOC_INSTANTIATE_MODEL = "cubism.core.moc.instantiate-model";

    public static final String MOC_GET_NATIVE_HANDLE = "cubism.core.moc.get-native-handle";

    public static final String MOC_CLOSE = "cubism.core.moc.close";

    public static final String MODEL_GET_NATIVE_HANDLE = "cubism.core.model.get-native-handle";

    public static final String MODEL_UPDATE = "cubism.core.model.update";

    public static final String MODEL_CLOSE = "cubism.core.model.close";

    /** Aliases required for both reviewed artifact profiles. */
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        MOC_CLASS,
        MOC_INSTANTIATE,
        MOC_INSTANTIATE_MODEL,
        MOC_GET_NATIVE_HANDLE,
        MOC_CLOSE,
        MODEL_GET_NATIVE_HANDLE,
        MODEL_UPDATE,
        MODEL_CLOSE
    );

    private OwnedMocSelectorContract() {
    }
}
