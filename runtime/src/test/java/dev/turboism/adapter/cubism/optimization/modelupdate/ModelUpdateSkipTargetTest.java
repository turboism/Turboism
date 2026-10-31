package dev.turboism.adapter.cubism.optimization.modelupdate;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTarget.Dep;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateTarget;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ModelUpdateSkipTargetTest {

    @Test
    void everyReviewedArtifactHasExactlyOneTarget() {
        assertEquals(20, ModelUpdateSkipTarget.all().size());
        for (ModelUpdateSkipTarget target : ModelUpdateSkipTarget.all()) {
            assertSame(target, ModelUpdateSkipTarget.of(target.digest()).orElseThrow());
        }
    }

    @Test
    void unknownDigestIsRejected() {
        HostArtifactDigest alien = new HostArtifactDigest(1L, "9".repeat(64));
        assertTrue(ModelUpdateSkipTarget.of(alien).isEmpty());
    }

    @Test
    void reviewedEntryOwnersAndDescriptorsMatchTheVersionTable() {
        ModelUpdateSkipTarget v5203 =
                ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_2_03).orElseThrow();
        assertEquals("com/live2d/cubism/view/au", v5203.owner());
        assertEquals(
                "(Lcom/live2d/cubism/view/context/CEViewContext;"
                        + "Lcom/live2d/cubism/doc/model/CModel;ZLcom/live2d/cubism/doc/model/ay;Z"
                        + "Lcom/live2d/cubism/view/context/bL;)V",
                v5203.methodDescriptor());
        assertEquals("com.live2d.cubism.doc.model.ay", v5203.updateContext());
        assertFalse(v5203.conflictPolygonFlag());
        assertFalse(v5203.extendedUpdateContext());
        assertFalse(v5203.appearanceAuxSettings());

        ModelUpdateSkipTarget v5302 =
                ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_02).orElseThrow();
        assertEquals("com/live2d/cubism/view/ay", v5302.owner());
        assertEquals(
                "(Lcom/live2d/cubism/view/context/CEViewContext;"
                        + "Lcom/live2d/cubism/doc/model/CModel;ZLcom/live2d/cubism/doc/model/ax;Z"
                        + "Lcom/live2d/cubism/view/context/bK;Z)V",
                v5302.methodDescriptor());
        assertEquals("com.live2d.cubism.doc.model.ax", v5302.updateContext());
        assertTrue(v5302.conflictPolygonFlag());
        assertTrue(v5302.extendedUpdateContext());
        assertTrue(v5302.appearanceAuxSettings());
        assertEquals("com.live2d.cubism.view.context.bR$b", v5302.developSetting());
        assertEquals("com.live2d.cubism.view.context.bR$a", v5302.appearanceSetting());

        ModelUpdateSkipTarget v5303 =
                ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow();
        assertEquals("com/live2d/cubism/view/ay", v5303.owner());
        assertEquals(
                "(Lcom/live2d/cubism/view/context/CEViewContext;"
                        + "Lcom/live2d/cubism/doc/model/CModel;ZLcom/live2d/cubism/doc/model/ax;Z"
                        + "Lcom/live2d/cubism/view/context/bL;Z)V",
                v5303.methodDescriptor());
        assertEquals("com.live2d.cubism.view.context.bS$b", v5303.developSetting());
        assertEquals("com.live2d.cubism.view.context.bS$a", v5303.appearanceSetting());

        ModelUpdateSkipTarget v5200 =
                ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_2_00).orElseThrow();
        assertEquals("com/live2d/cubism/view/at", v5200.owner());
        assertEquals(
                "(Lcom/live2d/cubism/view/context/CEViewContext;"
                        + "Lcom/live2d/cubism/doc/model/CModel;ZLcom/live2d/cubism/doc/model/ay;Z"
                        + "Lcom/live2d/cubism/view/context/bI;)V",
                v5200.methodDescriptor());
        assertEquals("com.live2d.cubism.doc.model.ay", v5200.updateContext());
        assertEquals("com.live2d.cubism.view.context.bO$b", v5200.developSetting());
        assertEquals("com.live2d.cubism.view.context.bO$a", v5200.appearanceSetting());
        assertFalse(v5200.extendedUpdateContext());
        assertFalse(v5200.appearanceAuxSettings());
        for (String version : new String[] {"5.2.01", "5.2.02"}) {
            ModelUpdateSkipTarget v52x = ModelUpdateSkipTarget.all().stream()
                    .filter(t -> t.version().equals(version))
                    .findFirst()
                    .orElseThrow();
            assertEquals(v5200.owner(), v52x.owner(), version);
            assertEquals(v5200.methodDescriptor(), v52x.methodDescriptor(), version);
        }

        ModelUpdateSkipTarget v5300 =
                ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_00).orElseThrow();
        assertEquals("com/live2d/cubism/view/ay", v5300.owner());
        assertEquals(v5302.methodDescriptor(), v5300.methodDescriptor());
        assertEquals("com.live2d.cubism.doc.model.ax", v5300.updateContext());
        ModelUpdateSkipTarget v5304 =
                ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_04).orElseThrow();
        assertEquals("com/live2d/cubism/view/ay", v5304.owner());
        assertEquals(
                "(Lcom/live2d/cubism/view/context/CEViewContext;"
                        + "Lcom/live2d/cubism/doc/model/CModel;ZLcom/live2d/cubism/doc/model/az;Z"
                        + "Lcom/live2d/cubism/view/context/bM;Z)V",
                v5304.methodDescriptor());
        assertEquals("com.live2d.cubism.doc.model.az", v5304.updateContext());
        assertEquals("com.live2d.cubism.view.context.bT$b", v5304.developSetting());
        assertEquals("com.live2d.cubism.view.context.bT$a", v5304.appearanceSetting());
        assertTrue(v5304.extendedUpdateContext());
        assertTrue(v5304.appearanceAuxSettings());
    }

    @Test
    void dependencyTableIsVersionScopedAndWellFormed() {
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
                    .map(dep -> dep.owner() + "." + dep.name())
                    .distinct()
                    .count();
            assertEquals(
                    distinct,
                    target.dependencies().size(),
                    "dependency lookup is keyed by owner+name; duplicates would alias");
        }
        // 5.2.03 has no extended update-context or auxiliary appearance settings.
        List<String> names5203 = v5203.dependencies().stream()
                .map(dep -> dep.owner() + "." + dep.name() + dep.descriptor())
                .toList();
        String uc5203 = v5203.updateContext();
        assertFalse(names5203.contains(uc5203 + ".l()Ljava/util/ArrayList;"));
        assertFalse(names5203.contains(uc5203 + ".m()Ljava/lang/Integer;"));
        assertFalse(names5203.contains(uc5203 + ".n()Ljava/util/ArrayList;"));
        assertTrue(v5203.dependencies().stream()
                .noneMatch(dep -> dep.owner().contains("GuiSetting")
                        || dep.owner().contains("CanvasSetting")
                        || dep.owner().contains("DeveloperSetting")
                        || dep.owner().contains("Warning")));
        List<String> names5303 = v5303.dependencies().stream()
                .map(dep -> dep.owner() + "." + dep.name() + dep.descriptor())
                .toList();
        String uc5303 = v5303.updateContext();
        assertTrue(names5303.contains(uc5303 + ".l()Ljava/util/ArrayList;"));
        assertTrue(names5303.contains(uc5303 + ".m()Ljava/lang/Integer;"));
        assertTrue(names5303.contains(uc5303 + ".n()Ljava/util/ArrayList;"));
        assertTrue(v5303.dependencies().stream().anyMatch(dep -> dep.name().equals("getHighLightDeformerChild")));
        assertTrue(v5303.dependencies().stream().anyMatch(dep -> dep.name().equals("getHideSelectedState")));
        // Both versions carry the updater's own singleton flag getters.
        assertTrue(v5203.dependencies().stream()
                .anyMatch(dep -> dep.owner().equals("com.live2d.cubism.view.au")
                        && dep.name().equals("a")));
        assertTrue(v5303.dependencies().stream()
                .anyMatch(dep -> dep.owner().equals("com.live2d.cubism.view.ay")
                        && dep.name().equals("b")));
    }

    @Test
    void completeOutputMatchesIndependentPreMigrationBaseline() throws Exception {
        StringBuilder actual = new StringBuilder();
        for (var target : ModelUpdateSkipTarget.all()) {
            actual.append(target).append('\n');
            for (var dependency : target.dependencies()) {
                actual.append(dependency).append('\n');
            }
        }
        for (var target : IncrementalUpdateTarget.all()) {
            actual.append(target).append('\n');
            actual.append(target.coreDescriptor()).append('\n');
            actual.append(target.deformerUpdateDescriptor()).append('\n');
            actual.append(target.artMeshUpdateDescriptor()).append('\n');
            actual.append(target.deformerInterpolateDescriptor()).append('\n');
            actual.append(target.meshInterpolateDescriptor()).append('\n');
            actual.append(target.deformerBinary()).append('\n');
            actual.append(target.meshFormBinary()).append('\n');
            for (var dependency : target.dependencies()) {
                actual.append(dependency).append('\n');
            }
        }
        try (var input = getClass().getResourceAsStream("targets-before-generation.txt")) {
            assertNotNull(input);
            assertEquals(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), actual.toString());
        }
    }

    @Test
    void generatedQueriesPreserveOrderIdentityAndUnknownVersionRejection() {
        assertEquals(
                ReviewedHostArtifacts.reviewedCubismVersions(),
                new java.util.LinkedHashSet<>(ModelUpdateSkipTarget.all().stream()
                        .map(ModelUpdateSkipTarget::version)
                        .toList()));
        assertEquals(
                ModelUpdateSkipTarget.all().stream()
                        .map(ModelUpdateSkipTarget::version)
                        .toList(),
                IncrementalUpdateTarget.all().stream()
                        .map(IncrementalUpdateTarget::version)
                        .toList());
        for (var target : ModelUpdateSkipTarget.all()) {
            assertSame(
                    target,
                    ModelUpdateSkipTarget.forReviewedVersion(target.version()).orElseThrow());
            var incremental =
                    IncrementalUpdateTarget.forReviewedVersion(target.version()).orElseThrow();
            assertSame(
                    incremental,
                    IncrementalUpdateTarget.of(incremental.digest()).orElseThrow());
            assertEquals(target.owner(), incremental.updater());
            assertEquals(target.updateContext().replace('.', '/'), incremental.updateContext());
        }
        assertTrue(ModelUpdateSkipTarget.forReviewedVersion("5.3.99").isEmpty());
        assertTrue(IncrementalUpdateTarget.forReviewedVersion("5.3.99").isEmpty());
        assertTrue(IncrementalUpdateTarget.of(new HostArtifactDigest(1L, "9".repeat(64)))
                .isEmpty());
        assertThrows(NullPointerException.class, () -> IncrementalUpdateTarget.of(null));
        assertThrows(NullPointerException.class, () -> ModelUpdateSkipTarget.forReviewedVersion(null));
    }
}
