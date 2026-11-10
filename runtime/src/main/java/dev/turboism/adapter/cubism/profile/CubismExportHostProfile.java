package dev.turboism.adapter.cubism.profile;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Preserves existing host contracts without expanding selector admission. */
public final class CubismExportHostProfile {
    private CubismExportHostProfile() {}

    public static final String SETTINGS_DIALOG_OWNER = "com/live2d/cubism/doc/model/exporter/e";
    public static final String SETTINGS_DIALOG_SETTING_DATA =
            "com/live2d/cubism/doc/model/exporter/CModelExportSettingDialogData";
    public static final String SETTINGS_MODEL_SOURCE = "com/live2d/cubism/doc/model/CModelSource";
    public static final String SETTINGS_WINDOW_PANEL_5_2 = "com/live2d/ui/window/X";
    public static final String SETTINGS_WINDOW_PANEL_5_3 = "com/live2d/ui/window/V";
    public static final String SETTINGS_WINDOW_BASE = "com/live2d/ui/window/y";
    public static final String CHOOSER_EXPORTER = "com/live2d/cubism/doc/model/exporter/b";
    public static final Set<String> STANDARD_EXTENSION_CLASSES = Set.of(
            "com.live2d.cubism.doc.model.extension.textureInput.CTextureInputExtension",
            "com.live2d.cubism.doc.model.extension.editableMesh.CEditableMeshExtension",
            "com.live2d.cubism.doc.model.extension.meshGenerator.CMeshGeneratorExtension",
            "com.live2d.cubism.doc.model.extension.warpBezier.CWarpDeformerBezierExtension",
            "com.live2d.cubism.doc.model.extension.deformerOriginalShape.CDeformerOriginalShapeExtension");
    public static final List<Map.Entry<String, String>> FAMILY_PREFIXES = List.of(
            Map.entry("com.live2d.cubism.doc.model.affecter.glue.deformPathSkinning.", "deform-path"),
            Map.entry("com.live2d.cubism.doc.model.affecter.glue.", "glue"),
            Map.entry("com.live2d.cubism.doc.model.drawable.artPath.", "art-path"),
            Map.entry("com.live2d.cubism.doc.model.alias.", "alias"),
            Map.entry("com.live2d.cubism.doc.model.motionSync.", "motion-sync"),
            Map.entry("com.live2d.cubism.doc.gameData.physics.", "physics"));
}
