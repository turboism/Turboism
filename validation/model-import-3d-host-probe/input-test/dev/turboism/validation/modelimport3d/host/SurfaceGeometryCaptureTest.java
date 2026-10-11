package dev.turboism.validation.modelimport3d.host;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.history.*;
import java.nio.file.*;
import java.util.*;

/** Exercises capture control flow against explicitly synthetic evaluation, not a host. */
public final class SurfaceGeometryCaptureTest {
    static final class Driver {
        final SurfaceImportTest.World world;
        final Map<dev.turboism.sdk.cubism.id.ParameterId,Float> saved;
        final List<HistoryEntry> entries=new ArrayList<>(List.of(new HistoryEntry(0,"import",true)));
        long revision=1,sequence=1;int position=1;
        Driver(SurfaceImportTest.World world){this.world=world;saved=Map.copyOf(world.base.geometry.live);}
        HistorySnapshot history(){return new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE,1,revision,position,
            entries,position>0,position<entries.size(),"synthetic-doc","synthetic-manager");}
        void set(Parameter parameter,float value) {
            float old=world.base.geometry.live.get(parameter.id());
            if(Float.compare(old,value)==0)return;
            entries.subList(position,entries.size()).clear();
            String id="pose-"+(++sequence);
            entries.add(new HistoryEntry(position,"Turboism: Set Parameter Value",true,
                Optional.of(new HistoryAction(HistoryAction.Kind.SET_PARAMETER_VALUE,"PARAMETER",parameter.id().value(),"value",
                    Optional.of(Float.toString(old)),Optional.of(Float.toString(value)),HistoryAction.DetailLevel.FULL)),
                Optional.of(new HistoryEntryId(id)),Optional.of("transaction-"+id)));
            position++;revision++;
            world.base.geometry.live.put(parameter.id(),value);world.base.geometry.liveVersion++;
        }
        void restore(int target) {
            if(target!=1)throw new AssertionError("only imported root may be restored");
            if(position!=target){position=target;revision++;world.base.geometry.live.clear();world.base.geometry.live.putAll(saved);world.base.geometry.liveVersion++;}
        }
    }
    static CubismModel model(SurfaceImportTest.World world,boolean corrupt,Driver driver) {
        var delegate=world.model();
        return SurfaceImportTest.proxy(CubismModel.class,(o,m,a)->{
            if(m.getName().equals("canvas"))return SurfaceImportTest.proxy(Canvas.class,(x,n,b)->1024f);
            if(m.getName().equals("parameters"))return SurfaceImportTest.proxy(Parameters.class,(x,n,b)->{
                var value=SurfaceImportTest.invoke(n,delegate.parameters(),b);
                if(value instanceof Parameter parameter)return SurfaceImportTest.proxy(Parameter.class,(z,k,c)->{
                    if(k.getName().equals("setValue")) {
                        // Model the actual SDK authoring Undo effect seen in both native failures.
                        driver.set(parameter,(Float)c[0]);return null;
                    }
                    return SurfaceImportTest.invoke(k,parameter,c);
                });
                return value;
            });
            if(corrupt && m.getName().equals("drawables")) {
                var meshes=delegate.drawables();
                return SurfaceImportTest.proxy(Drawables.class,(x,n,b)->{
                    var value=SurfaceImportTest.invoke(n,meshes,b);
                    if(value instanceof Drawable mesh)return SurfaceImportTest.proxy(Drawable.class,(z,k,c)->{
                        if(!k.getName().equals("evaluationSnapshot"))return SurfaceImportTest.invoke(k,mesh,c);
                        var e=mesh.evaluationSnapshot();var f=e.calculatedForm().orElseThrow();
                        var shifted=f.positions().stream().map(p->new Point2(p.x()+2,p.y())).toList();
                        return new ArtMeshEvaluationSnapshot(e.artMeshId(),e.sourceGuid(),Optional.of(new ArtMeshEvaluationSnapshot.Form(
                            shifted,f.drawOrder(),f.coordinateType(),f.interpolated())),e.liveParameters(),e.consumedParameters(),e.passEntryTimeMillis());
                    });
                    return value;
                });
            }
            return SurfaceImportTest.invoke(m,delegate,a);
        });
    }
    public static void main(String[] args)throws Exception {
        var prepared=SurfaceImport.prepare(Files.readAllBytes(Path.of(args[0])),SurfaceImportTest.request());
        for(boolean corrupt:new boolean[]{false,true}) {
            var world=new SurfaceImportTest.World();
            var imported=SurfaceImport.apply(prepared,world::model,world.transactions()).value().orElseThrow();
            world.model().parameters().find(prepared.request.yaw()).setValue(7.25f);
            world.model().parameters().find(prepared.request.pitch()).setValue(-12.5f);
            var before=world.snapshot();var driver=new Driver(world);var history=driver.history();
            var report=new LinkedHashMap<String,Object>();
            boolean failed=false;
            try {SurfaceGeometryCapture.run(prepared,imported,()->model(world,corrupt,driver),driver::history,driver::restore,report);}
            catch(IllegalStateException expected) {if(!corrupt || !expected.getMessage().contains("native surface projection error"))throw expected;failed=true;}
            if(failed!=corrupt || !report.get("status").equals(corrupt?"FAIL":"PASS"))throw new AssertionError("capture outcome");
            if(!world.snapshot().equals(before) || driver.history().position()!=history.position())throw new AssertionError("mutation");
            if(!Boolean.TRUE.equals(report.get("restored")) || !report.get("liveBefore").equals(report.get("liveAfter")))throw new AssertionError("live restoration");
            if(!report.get("before").equals(report.get("after")))throw new AssertionError("missing restoration evidence");
            for(Object value:(List<?>)report.get("samples")) {
                var row=(Map<?,?>)value;
                if(!Boolean.TRUE.equals(row.get("undoVerified")) || !row.get("liveRestored").equals(report.get("liveBefore")))
                    throw new AssertionError("per-pose undo restoration");
                var h=(Map<?,?>)row.get("historyDriven");
                if(((List<?>)h.get("entries")).size()>3)throw new AssertionError("unbounded Undo accumulation");
            }
            int samples=((List<?>)report.get("samples")).size();
            if(samples!=(corrupt?1:SurfaceGeometryCapture.poses(prepared.geometry).size()))throw new AssertionError("sample count");
            Files.writeString(Path.of(args[1]+(corrupt?"-failure.json":"-success.json")),NativeReport.json(report));
            System.out.println("synthetic capture corrupt="+corrupt+" samples="+samples+" PASS; hostExecuted=false");
        }
    }
}
