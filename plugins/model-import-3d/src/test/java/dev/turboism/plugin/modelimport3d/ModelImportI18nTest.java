package dev.turboism.plugin.modelimport3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ModelImportI18nTest {
    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_en.properties",
            "messages_ja.properties",
            "messages_ko.properties",
            "messages_zh_Hans.properties",
            "messages_zh_Hant.properties");

    /** Keys the import windows and the progress window read at run time. */
    private static final List<String> REQUIRED_KEYS = List.of(
            "dialog.preset.hint",
            "progress.mesh",
            "progress.writing",
            "preset.torus",
            ModelObjectNames.KEY_DEFORMER,
            ModelObjectNames.KEY_JOINT,
            ModelObjectNames.KEY_LOCAL_ACTION,
            ModelObjectNames.KEY_MESH,
            ModelObjectNames.KEY_MESH_NAMED,
            ModelObjectNames.KEY_PARTITION,
            ModelObjectNames.KEY_PLACEMENT,
            ModelObjectNames.KEY_ROTATION_X,
            ModelObjectNames.KEY_ROTATION_Y,
            ModelObjectNames.KEY_ROTATION_Z,
            ModelObjectNames.KEY_SURFACE,
            ModelObjectNames.KEY_WARP);

    @Test
    void everyBundleCarriesTheImportAndProgressKeys() throws Exception {
        for (String bundle : BUNDLES) {
            final Properties properties = load(bundle);
            for (String key : REQUIRED_KEYS) {
                assertTrue(properties.containsKey(key), bundle + " is missing " + key);
                assertFalse(properties.getProperty(key).isBlank(), bundle + " has a blank " + key);
            }
        }
    }

    @Test
    void solidTorusPresetIsNamedAsTheDonutOperatorsSee() throws Exception {
        for (String bundle : BUNDLES) {
            final String label = load(bundle).getProperty("preset.torus");
            assertTrue(
                    label.contains("Donut") || label.contains("甜甜圈") || label.contains("ドーナツ") || label.contains("도넛"),
                    bundle + " still calls the solid torus something else: " + label);
        }
        assertEquals("甜甜圈", load("messages_zh_Hans.properties").getProperty("preset.torus"));
    }

    @Test
    void presetAndSyntheticDeformerAndMeshNamesResolveThroughLocalizedBundles() throws Exception {
        final Properties zhHans = load("messages_zh_Hans.properties");
        final Properties english = load("messages_en.properties");

        final SurfaceImport.Request zhRequest = localizedRequest("甜甜圈", zhHans);
        final var zhPrepared = NativeModelImport.preparePreset(ModelPreset.TORUS, zhRequest, () -> false);
        assertEquals("甜甜圈 曲面变形", zhPrepared.materials().scene().meshes().get(0).appearanceGroup());
        assertEquals("", zhPrepared.materials().scene().meshes().get(0).name());
        assertEquals("甜甜圈 分区 0", zhRequest.partitionName(0));
        assertEquals("甜甜圈 网格 0", zhRequest.partitionMeshName("", 0));
        assertEquals("甜甜圈 网格 0", zhRequest.partitionMeshName("mesh_0", 0));
        assertEquals("甜甜圈 外套 网格 2", zhRequest.partitionMeshName("外套", 2));
        assertEquals("甜甜圈 变形器 1", zhRequest.appearanceGroupName("mesh_0 [1]", false));
        assertEquals("甜甜圈 曲面变形", zhRequest.appearanceGroupName("mesh_0 [0]", true));
        assertEquals("关节 3", zhRequest.bodyPartName("Joint [node 3]"));

        final SurfaceImport.Request enRequest = localizedRequest("Cube", english);
        final var enPrepared = NativeModelImport.preparePreset(ModelPreset.CUBE, enRequest, () -> false);
        assertEquals("Cube Warp", enPrepared.materials().scene().meshes().get(0).appearanceGroup());
        assertEquals("Cube Partition 0", enRequest.partitionName(0));
        assertEquals("Cube Mesh 0", enRequest.partitionMeshName("", 0));
        assertEquals("Cube Surface 1", enRequest.surfaceName(1));
        assertEquals("Joint 4", enRequest.bodyPartName("Joint [node 4]"));
    }

    private static SurfaceImport.Request localizedRequest(final String name, final Properties bundle) {
        return new SurfaceImport.Request(
                name,
                new dev.turboism.sdk.cubism.id.ParameterId("ParamYaw"),
                new dev.turboism.sdk.cubism.id.ParameterId("ParamPitch"),
                new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
                180,
                180,
                SurfaceImportPlan.MAX_PROJECTED_VERTICES,
                java.util.Optional.of(new dev.turboism.sdk.cubism.id.ParameterId("ParamRoll")),
                ModelObjectNames.labels(bundle::getProperty));
    }

    private static Properties load(final String bundle) throws Exception {
        final String path = "META-INF/turboism/i18n/" + bundle;
        try (InputStream in = ModelImportI18nTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "missing bundle " + path);
            final Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
