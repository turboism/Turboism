package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import java.nio.file.*;
import java.util.*;

/** Actual importer and stored forms through synthetic SDK; no native evaluation claim. */
public final class SurfaceSheetImportTest {
    static int checks;
    static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args)throws Exception {
        var prepared=SurfaceImport.prepare(Files.readAllBytes(Path.of(args[0])),SurfaceImportTest.request());
        check(prepared.visibility.kind()==SurfaceImport.VisibilityKind.PARALLEL_SHEETS,"sheet admission");
        check(ParallelSheetOrderPlan.verifyStoredTransition(prepared.sheetOrders,prepared.geometry).doubleValue()>80,
            "actual float keys retain full-domain neutral strip separation");
        for(float gap:new float[]{0,.125f,-1}) {
            try {
                ParallelSheetOrderPlan.verifyStoredTransition(prepared.sheetOrders,withGap(prepared.geometry,gap));
                throw new AssertionError("overlap/native-error margin admitted: "+gap);
            } catch(IllegalArgumentException expected) {checks++;}
        }
        check(ParallelSheetOrderPlan.verifyStoredTransition(prepared.sheetOrders,withGap(prepared.geometry,.25f))
            .compareTo(new java.math.BigDecimal("0.25"))==0,"exact stored margin positive control");
        var world=new SurfaceImportTest.World();
        var stages=new ArrayList<SurfaceImport.Stage>();
        var result=SurfaceImport.apply(prepared,world::model,world.transactions(),stages::add);
        check(result.outcome()==AuthoringTransactionOutcome.COMMITTED,"sheet commit: "+result);
        check(world.calls==1 && world.base.geometry.position==1,"single root and history entry");
        check(stages.equals(List.of(SurfaceImport.Stage.values())),"all stages");
        var imported=result.value().orElseThrow();
        check(imported.geometry().patches().size()==2 && world.parts.size()==1,"two meshes one controller");
        check(world.base.registrations==2 && imported.materials().stream().map(m->m.argb()).distinct().count()==2,"distinct source materials");
        for(int i=0;i<2;i++) {
            var patch=imported.geometry().patches().get(i);
            var mesh=world.model().drawables().find(patch.meshId());
            check(!mesh.culling(),"double-sided rendering");
            var actual=mesh.authoringSnapshot();
            var beforeOrder=patch.bake().expected();
            var normalized=new ArtMeshAuthoringSnapshot(actual.artMeshId(),actual.sourceGuid(),actual.sourceGeometry(),
                actual.axes(),actual.cells(),actual.forms().stream().map(f->new ArtMeshAuthoringSnapshot.Form(
                    f.formGuid(),f.positions(),500)).toList(),actual.morphKeys());
            check(normalized.equals(beforeOrder),"order writes preserve all geometry/UV/topology/identity/axes");
            var yaw=actual.axes().stream().filter(a->a.parameterId().equals(prepared.request.yaw())).findFirst().orElseThrow();
            var forms=new HashMap<String,ArtMeshAuthoringSnapshot.Form>();
            actual.forms().forEach(f->forms.put(f.formGuid(),f));
            for(var cell:actual.cells()) {
                float angle=cell.coordinates().stream().filter(c->c.parameterGuid().equals(yaw.parameterGuid())).findFirst().orElseThrow().value();
                // Independent fixture truth: source 0 is at X=-.25, source 1 at X=+.25.
                int expected=angle==0?500:((i==0)==(angle<0)?501:499);
                check(forms.get(cell.formGuid()).drawOrder()==expected,"stored joint tuple order");
            }
        }
        for(var stage:SurfaceImport.Stage.values()) {
            var failed=new SurfaceImportTest.World();
            var before=failed.snapshot();
            var reached=new ArrayList<SurfaceImport.Stage>();
            var failure=SurfaceImport.apply(prepared,failed::model,failed.transactions(),at->{
                reached.add(at);if(at==stage)throw new IllegalStateException("injected "+stage);
            });
            check(failure.outcome()==AuthoringTransactionOutcome.ROLLED_BACK,"rollback "+stage);
            check(reached.get(reached.size()-1)==stage && failed.calls==1,"reached "+stage);
            check(failed.snapshot().equals(before),"full restoration "+stage);
        }
        try {
            SurfaceImport.prepare(Files.readAllBytes(Path.of(args[1])),SurfaceImportTest.request());
            throw new AssertionError("intersecting sheets admitted");
        } catch(IllegalArgumentException expected) {checks++;}
        System.out.println("SurfaceSheetImportTest: "+checks+" checks PASS; stored-state/synthetic rollback only; hostExecuted=false");
    }
    static SurfaceImportPlan.Plan withGap(SurfaceImportPlan.Plan plan,float gap) {
        var patches=plan.patches().stream().map(p->new SurfaceImportPlan.Patch(p.surfaceIndex(),p.source(),
            p.neutralCollapsedTriangles(),p.positionsByKey().stream().map(points->points.stream()
                .map(v->new Point2(p.surfaceIndex()==0?0:gap,v.y())).toList()).toList())).toList();
        return new SurfaceImportPlan.Plan(plan.camera(),plan.grid(),plan.keys(),plan.neutralKey(),patches,plan.projectedVertices());
    }
}
