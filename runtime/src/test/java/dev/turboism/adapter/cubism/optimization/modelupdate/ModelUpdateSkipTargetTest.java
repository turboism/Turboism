package dev.turboism.adapter.cubism.optimization.modelupdate;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTarget.Dep;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ModelUpdateSkipTargetTest {

    @Test void everyReviewedArtifactHasExactlyOneTarget() {
        assertEquals(3, ModelUpdateSkipTarget.all().size());
        for (ModelUpdateSkipTarget target : ModelUpdateSkipTarget.all()) {
            assertSame(target, ModelUpdateSkipTarget.of(target.digest()).orElseThrow());
        }
    }

    @Test void unknownDigestIsRejected() {
        HostArtifactDigest alien = new HostArtifactDigest(1L, "9".repeat(64));
        assertTrue(ModelUpdateSkipTarget.of(alien).isEmpty());
    }

    @Test void reviewedEntryOwnersAndDescriptorsMatchTheVersionTable() {
        ModelUpdateSkipTarget v5203 =
            ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_2_03).orElseThrow();
        assertEquals("com/live2d/cubism/view/au", v5203.owner());
        assertEquals("(Lcom/live2d/cubism/view/context/CEViewContext;"
            + "Lcom/live2d/cubism/doc/model/CModel;ZLcom/live2d/cubism/doc/model/ay;Z"
            + "Lcom/live2d/cubism/view/context/bL;)V", v5203.methodDescriptor());
        assertEquals("com.live2d.cubism.doc.model.ay", v5203.updateContext());
        assertFalse(v5203.conflictPolygonFlag());
        assertFalse(v5203.extendedUpdateContext());
        assertFalse(v5203.appearanceAuxSettings());

        ModelUpdateSkipTarget v5302 =
            ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_02).orElseThrow();
        assertEquals("com/live2d/cubism/view/ay", v5302.owner());
        assertEquals("(Lcom/live2d/cubism/view/context/CEViewContext;"
            + "Lcom/live2d/cubism/doc/model/CModel;ZLcom/live2d/cubism/doc/model/ax;Z"
            + "Lcom/live2d/cubism/view/context/bK;Z)V", v5302.methodDescriptor());
        assertEquals("com.live2d.cubism.doc.model.ax", v5302.updateContext());
        assertTrue(v5302.conflictPolygonFlag());
        assertTrue(v5302.extendedUpdateContext());
        assertTrue(v5302.appearanceAuxSettings());
        assertEquals("com.live2d.cubism.view.context.bR$b", v5302.developSetting());
        assertEquals("com.live2d.cubism.view.context.bR$a", v5302.appearanceSetting());

        ModelUpdateSkipTarget v5303 =
            ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow();
        assertEquals("com/live2d/cubism/view/ay", v5303.owner());
        assertEquals("(Lcom/live2d/cubism/view/context/CEViewContext;"
            + "Lcom/live2d/cubism/doc/model/CModel;ZLcom/live2d/cubism/doc/model/ax;Z"
            + "Lcom/live2d/cubism/view/context/bL;Z)V", v5303.methodDescriptor());
        assertEquals("com.live2d.cubism.view.context.bS$b", v5303.developSetting());
        assertEquals("com.live2d.cubism.view.context.bS$a", v5303.appearanceSetting());
    }

    @Test void dependencyTableIsVersionScopedAndWellFormed() {
        ModelUpdateSkipTarget v5203 =
            ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_2_03).orElseThrow();
        ModelUpdateSkipTarget v5303 =
            ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow();
        for (ModelUpdateSkipTarget target : List.of(v5203, v5303)) {
            for (Dep dep : target.dependencies()) {
                assertFalse(dep.owner().isBlank());
                assertFalse(dep.name().isBlank());
                assertTrue(dep.descriptor().startsWith("("));
                assertTrue(dep.descriptor().contains(")"));
            }
            // Every reviewed getter name is unique per owner+name pair used by the bridge.
            long distinct = target.dependencies().stream()
                .map(dep -> dep.owner() + "." + dep.name()).distinct().count();
            assertEquals(distinct, target.dependencies().size(),
                "dependency lookup is keyed by owner+name; duplicates would alias");
        }
        // 5.2.03 has no extended update-context or auxiliary appearance settings.
        List<String> names5203 = v5203.dependencies().stream()
            .map(dep -> dep.owner() + "." + dep.name() + dep.descriptor()).toList();
        String uc5203 = v5203.updateContext();
        assertFalse(names5203.contains(uc5203 + ".l()Ljava/util/ArrayList;"));
        assertFalse(names5203.contains(uc5203 + ".m()Ljava/lang/Integer;"));
        assertFalse(names5203.contains(uc5203 + ".n()Ljava/util/ArrayList;"));
        assertTrue(v5203.dependencies().stream().noneMatch(
            dep -> dep.owner().contains("GuiSetting")
                || dep.owner().contains("CanvasSetting")
                || dep.owner().contains("DeveloperSetting")
                || dep.owner().contains("Warning")));
        List<String> names5303 = v5303.dependencies().stream()
            .map(dep -> dep.owner() + "." + dep.name() + dep.descriptor()).toList();
        String uc5303 = v5303.updateContext();
        assertTrue(names5303.contains(uc5303 + ".l()Ljava/util/ArrayList;"));
        assertTrue(names5303.contains(uc5303 + ".m()Ljava/lang/Integer;"));
        assertTrue(names5303.contains(uc5303 + ".n()Ljava/util/ArrayList;"));
        assertTrue(v5303.dependencies().stream().anyMatch(
            dep -> dep.name().equals("getHighLightDeformerChild")));
        assertTrue(v5303.dependencies().stream().anyMatch(
            dep -> dep.name().equals("getHideSelectedState")));
        // Both versions carry the updater's own singleton flag getters.
        assertTrue(v5203.dependencies().stream().anyMatch(
            dep -> dep.owner().equals("com.live2d.cubism.view.au") && dep.name().equals("a")));
        assertTrue(v5303.dependencies().stream().anyMatch(
            dep -> dep.owner().equals("com.live2d.cubism.view.ay") && dep.name().equals("b")));
    }
}
