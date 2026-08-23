package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for Editor physics settings document reads.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.2.03 and 5.3.02):
 * {@code CModelSource.getPhysicsSettingsSourceSet()} exposes gravity/wind as
 * {@code GVector2} ({@code getX()}/{@code getY()}), an optional settings FPS,
 * and the source list; each {@code CPhysicsSettingsSource} exposes its id
 * ({@code CPhysicsSettingId extends Id}), name, total angle, and input/output/
 * vertex collections (counts read through {@code List} semantics).</p>
 */
public final class EditorPhysicsReadSelectorContract {

    public static final String CUBISM_VERSION = "5.3.02";

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";

    public static final String CAPABILITY_ID = "cubism.editor-model.physics.read";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.model-source.physics-settings-source-set",
        "cubism.editor-model.physics-settings-source-set.class",
        "cubism.editor-model.physics-settings-source-set.gravity",
        "cubism.editor-model.physics-settings-source-set.wind",
        "cubism.editor-model.physics-settings-source-set.setting-fps",
        "cubism.editor-model.physics-settings-source-set.sources",
        "cubism.editor-model.physics-settings-source.class",
        "cubism.editor-model.physics-settings-source.id",
        "cubism.editor-model.physics-settings-source.name",
        "cubism.editor-model.physics-settings-source.total-angle",
        "cubism.editor-model.physics-settings-source.inputs",
        "cubism.editor-model.physics-settings-source.outputs",
        "cubism.editor-model.physics-settings-source.vertices",
        "cubism.editor-model.vector2.class",
        "cubism.editor-model.vector2.x",
        "cubism.editor-model.vector2.y",
        "cubism.editor-model.id.value"
    );

    private EditorPhysicsReadSelectorContract() {
    }
}
