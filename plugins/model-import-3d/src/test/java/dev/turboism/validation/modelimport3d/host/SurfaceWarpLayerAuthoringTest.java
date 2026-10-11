package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceWarpLayerAuthoringTest {
    private static SurfaceWarpLayerAuthoring.Prepared prepared() throws Exception {
        var request=new SurfaceImport.Request("Layers",new ParameterId("Yaw"),new ParameterId("Pitch"),
            new SurfaceProjection.Camera(512,512,200,new SurfaceProjection.Vertex(.5,.5,.5)),30,30,10000000);
        return SurfaceWarpLayerAuthoring.prepare(SurfaceWarpImportCandidate.prepareDecoded(
            List.of(SurfaceQuadChartDiscoveryTest.cube(2,0)),request,SurfaceWarpImportCandidate.DEFAULT));
    }
    @Test void bindsJointChartOrdersWithoutChangingSourceOrLocalPositions() throws Exception {
        var p=prepared();var state=new SurfaceWarpAuthoringTest.State(p.surface(),"normal");
        var created=SurfaceWarpLayerAuthoring.assemble(state.model(),p,stage->{});
        assertEquals(6,created.meshIds().size());assertEquals(6,state.orderBatches);
        for(int mesh=0;mesh<6;mesh++) {
            var s=created.sourceSnapshots().get(mesh);
            assertEquals(289,s.forms().size());assertEquals(289,s.cells().size());
            assertTrue(s.forms().stream().allMatch(f->f.positions().equals(s.forms().get(0).positions())));
            for(var cell:s.cells()) {
                var values=new HashMap<String,Float>();cell.coordinates().forEach(c->values.put(c.parameterGuid(),c.value()));
                int y=p.surface().yawKeys.indexOf(values.get("guid-Yaw")),pitch=p.surface().pitchKeys.indexOf(values.get("guid-Pitch"));
                int expected=p.orders().jointKeys().get(y*17+pitch).get(mesh);
                assertEquals(expected,s.forms().stream().filter(f->f.formGuid().equals(cell.formGuid())).findFirst().orElseThrow().drawOrder());
            }
        }
    }
    @Test void compactOrdersKeepDenseWarpAndEverySourceCoordinate() throws Exception {
        var full=prepared();var keys=new ArrayList<Float>();for(int i=0;i<17;i+=2)keys.add(full.surface().yawKeys.get(i));
        var p=new SurfaceWarpLayerAuthoring.Prepared(full.surface(),SurfaceWarpLayerPlan.orders(
            full.surface().geometry,full.surface().request.camera(),keys,keys),keys,keys);
        var state=new SurfaceWarpAuthoringTest.State(p.surface(),"normal");
        var created=SurfaceWarpLayerAuthoring.assemble(state.model(),p,stage->{});
        for(int mesh=0;mesh<created.meshIds().size();mesh++) {
            var snapshot=created.sourceSnapshots().get(mesh);
            assertEquals(81,snapshot.forms().size());assertEquals(81,snapshot.cells().size());
            assertEquals(keys,snapshot.axes().get(0).keys());assertEquals(keys,snapshot.axes().get(1).keys());
            assertEquals(p.surface().geometry.meshes().get(mesh).warpLocal().size(),snapshot.sourceGeometry().positions().size());
            assertTrue(snapshot.forms().stream().allMatch(form->form.positions().equals(snapshot.forms().get(0).positions())));
        }
        var warp=state.snapshot;
        assertEquals(289,warp.forms().size());assertEquals(17,warp.axes().get(0).keys().size());
        assertEquals(17,warp.axes().get(1).keys().size());
    }
    @Test void freezesNativeBindingFactoryClonedDefaultGuids() throws Exception {
        var p=prepared();var state=new SurfaceWarpAuthoringTest.State(p.surface(),"layer-bound-clone");
        var created=SurfaceWarpLayerAuthoring.assemble(state.model(),p,stage->{});
        assertEquals(6,state.orderBatches);
        for(var snapshot:created.sourceSnapshots()) {
            var neutral=snapshot.cells().stream().filter(c->c.coordinates().stream().allMatch(v->v.value()==0f)).findFirst().orElseThrow();
            assertEquals("native-cloned-"+snapshot.artMeshId().value(),neutral.formGuid());
            assertTrue(snapshot.forms().stream().allMatch(f->f.positions().equals(snapshot.forms().get(0).positions())));
        }
        var first=state.meshes.values().iterator().next();first.form="foreign-replacement";
        assertThrows(IllegalStateException.class,()->SurfaceWarpAuthoring.verifyInputs(state.model(),created,p.surface().images.inputs,Optional.of(created.warpId())));
    }
    @Test void rejectsBindingPositionCorruptionBeforeBatchOrderMutation() throws Exception {
        var p=prepared();var state=new SurfaceWarpAuthoringTest.State(p.surface(),"layer-bound-local");
        assertThrows(IllegalStateException.class,()->SurfaceWarpLayerAuthoring.assemble(state.model(),p,stage->{}));
        assertEquals(0,state.orderBatches);
    }
}
