package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for Editor model-instance reads.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.2.03 and 5.3.02):</p>
 * <ul>
 *   <li>{@code CModelSource.getModelInstances()} returns the instance list
 *       ({@code CArrayList<CModel>}); {@code getCurrentInstance()} returns the
 *       current instance or {@code null}; {@code isModelEditing()} returns the
 *       source editing flag.</li>
 *   <li>{@code CModel.getRenderType()} returns the instance render kind;
 *       {@code com.live2d.graphics3d.rendering.RenderType} is a public enum with
 *       NORMAL(1), PSD_EXPORT(2), ART_PATH(3), ART_PATH_ILLEGAL(4) in both
 *       versions, plus ONION_SKIN_FOR_MODELING(5) in 5.3.02 only.</li>
 *   <li>All five instance mutations ({@code createModelInstance},
 *       {@code setCurrentInstance}, {@code setModelEditing}, {@code createRootPart},
 *       {@code removeParameterControllableSource}) are plain field/list mutations
 *       without undo evidence — decompiled bodies recorded in the mapping docs —
 *       so the write family stays fail closed.</li>
 * </ul>
 */
public final class EditorModelInstanceReadSelectorContract {

    public static final String CUBISM_VERSION = "5.3.02";

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";

    public static final String CAPABILITY_ID = "cubism.editor-model.model-instance.read";

    public static final Set<String> ONION_SKIN_ALIASES = Set.of(
        "cubism.editor-model.render-type.onion-skin-for-modeling"
    );

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.model-source.model-instances",
        "cubism.editor-model.model-source.model-editing",
        "cubism.editor-model.model-source.current-instance",
        "cubism.editor-model.model-instance.class",
        "cubism.editor-model.model-instance.render-type",
        "cubism.editor-model.render-type.class",
        "cubism.editor-model.render-type.normal",
        "cubism.editor-model.render-type.psd-export",
        "cubism.editor-model.render-type.art-path",
        "cubism.editor-model.render-type.art-path-illegal",
        "cubism.editor-model.render-type.onion-skin-for-modeling"
    );

    private EditorModelInstanceReadSelectorContract() {
    }
}
