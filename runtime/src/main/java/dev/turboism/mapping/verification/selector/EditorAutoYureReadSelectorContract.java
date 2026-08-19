package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for Editor auto-Yure evaluation reads.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.2.03 and 5.3.02):</p>
 * <ul>
 *   <li>{@code ACParameterControllableSource.getExtensions()} returns the
 *       extension list of every controllable source (warp deformer sources
 *       included); {@code CAutoYureConfigExtension} is the auto-Yure extension
 *       and {@code getParamToConfigMap()} maps parameter GUIDs to evaluated
 *       {@code AutoYureConfig} values.</li>
 *   <li>Kotlin-obfuscated members are stable across both versions:
 *       {@code AutoYureConfig.a()}=left, {@code b()}=right,
 *       {@code c()}=syncLeftRight, {@code d()}=rootDirection, {@code e()}=isFlip;
 *       {@code YureDeformConfig.a()}=scalePercentX, {@code b()}=scalePercentY,
 *       {@code c()}=expandScale, {@code d()}=decayLevel.</li>
 *   <li>{@code YureRootDirection} constants are public static fields
 *       TOP/RIGHT/BOTTOM/LEFT in both versions.</li>
 * </ul>
 */
public final class EditorAutoYureReadSelectorContract {

    public static final String CUBISM_VERSION = "5.3.02";

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";

    public static final String CAPABILITY_ID = "cubism.editor-model.auto-yure.read";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.parameter-controllable-source.extensions",
        "cubism.editor-model.auto-yure-config-extension.class",
        "cubism.editor-model.auto-yure-config-extension.param-to-config-map",
        "cubism.editor-model.auto-yure-config.class",
        "cubism.editor-model.auto-yure-config.left",
        "cubism.editor-model.auto-yure-config.right",
        "cubism.editor-model.auto-yure-config.sync-left-right",
        "cubism.editor-model.auto-yure-config.root-direction",
        "cubism.editor-model.auto-yure-config.flip",
        "cubism.editor-model.auto-yure-config-root-direction.class",
        "cubism.editor-model.auto-yure-config-root-direction.top",
        "cubism.editor-model.auto-yure-config-root-direction.right",
        "cubism.editor-model.auto-yure-config-root-direction.bottom",
        "cubism.editor-model.auto-yure-config-root-direction.left",
        "cubism.editor-model.yure-deform-config.class",
        "cubism.editor-model.yure-deform-config.scale-percent-x",
        "cubism.editor-model.yure-deform-config.scale-percent-y",
        "cubism.editor-model.yure-deform-config.expand-scale",
        "cubism.editor-model.yure-deform-config.decay-level",
        "cubism.editor-model.warp-source.class",
        "cubism.editor-model.model-source.all-deformers",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.model-source.all-parameters",
        "cubism.editor-model.parameter.class",
        "cubism.editor-model.parameter.id",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.id.value"
    );

    private EditorAutoYureReadSelectorContract() {
    }
}
