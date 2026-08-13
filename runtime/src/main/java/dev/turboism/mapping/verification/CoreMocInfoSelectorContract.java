package dev.turboism.mapping.verification;

import java.util.Set;

/**
 * Exact additive selector contract for Cubism Core MOC metadata reads.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.2.03 and 5.3.02):</p>
 * <ul>
 *   <li>{@code com.live2d.sdk.cubism.core.CubismModel.getMoc()} exists in both
 *       5.2.03 and 5.3.02 ({@code ()Lcom/live2d/sdk/cubism/core/CubismMoc;}).</li>
 *   <li>{@code com.live2d.sdk.cubism.core.CubismMoc.getMocVersion()} exists in
 *       5.3.02 only ({@code ()I}); it is absent from the 5.2.03 public surface,
 *       so the 5.2 profile can never authorize this capability and fails closed.</li>
 *   <li>MOC byte-level consistency ({@code Live2DCubismCore.hasMocConsistency})
 *       is not reachable from a borrowed {@code CubismModel} (no public byte
 *       accessor), so consistency is reported as {@code UNKNOWN}.</li>
 * </ul>
 */
public final class CoreMocInfoSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.core-model.readonly";

    public static final String CAPABILITY_ID = "cubism.core.moc-info.read";

    public static final String MODEL_GET_MOC = "cubism.core.model.get-moc";

    public static final String MOC_CLASS = "cubism.core.moc.class";

    public static final String MOC_GET_MOC_VERSION = "cubism.core.moc.get-moc-version";

    /** Aliases for profiles that can expose the MOC version read (5.3.02 only). */
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        MODEL_GET_MOC,
        MOC_CLASS,
        MOC_GET_MOC_VERSION
    );

    private CoreMocInfoSelectorContract() {
    }
}
