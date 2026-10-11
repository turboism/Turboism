package dev.turboism.validation.modelimport3d.host;
import dev.turboism.sdk.cubism.model.*;
import java.nio.file.*;
import java.util.*;

/** Synthetic raster and SDK only: checks control flow/history, never native pixels. */
public final class PixelPoseDriveTest {
    static CubismModel model(SurfaceImportTest.World world,SurfaceGeometryCaptureTest.Driver driver,
            SurfaceImport.Imported imported,boolean failRender) {
        var base=SurfaceGeometryCaptureTest.model(world,false,driver);
        var cache=new HashMap<String,ArtMeshEvaluationSnapshot>();
        java.util.function.Function<Drawable,Drawable> mesh=d->SurfaceImportTest.proxy(Drawable.class,(o,m,a)->switch(m.getName()) {
            case "evaluationSnapshot"->{
                var e=d.evaluationSnapshot();var snap=d.authoringSnapshot();var f=e.calculatedForm().orElseThrow();
                var cell=snap.cells().stream().filter(c->c.coordinates().stream().allMatch(v->
                    snap.axes().stream().filter(axis->axis.parameterGuid().equals(v.parameterGuid())).findFirst()
                        .map(axis->Float.compare(base.parameters().find(axis.parameterId()).getValue(),v.value())==0).orElse(false))).findFirst();
                int order=cell.map(c->snap.forms().stream().filter(form->form.formGuid().equals(c.formGuid())).findFirst().orElseThrow().drawOrder()).orElse(500);
                var next=new ArtMeshEvaluationSnapshot(e.artMeshId(),e.sourceGuid(),Optional.of(new ArtMeshEvaluationSnapshot.Form(
                    f.positions(),order,f.coordinateType(),f.interpolated())),e.liveParameters(),
                    new ArtMeshEvaluationSnapshot.ParameterState(e.liveParameters().updateVersion(),e.liveParameters().parameters()),
                    e.liveParameters().updateVersion());
                var old=cache.put(e.artMeshId().value(),next);
                yield old==null ? next : new ArtMeshEvaluationSnapshot(e.artMeshId(),e.sourceGuid(),old.calculatedForm(),
                    e.liveParameters(),old.consumedParameters(),old.passEntryTimeMillis());
            }
            case "visible"->true;case "parentPartId"->Optional.empty();
            default->SurfaceImportTest.invoke(m,d,a);
        });
        return SurfaceImportTest.proxy(CubismModel.class,(o,m,a)->switch(m.getName()) {
            case "id"->new dev.turboism.sdk.cubism.id.ModelId("synthetic-pixels");
            case "renderPixels"->{if(failRender)throw new IllegalStateException("injected raster failure");yield new CubismRenderedPixels(1024,1024,new int[1024*1024]);}
            case "parts"->SurfaceImportTest.proxy(Parts.class,(x,n,b)->{if(n.getName().equals("all"))return List.of();throw new AssertionError(n);});
            case "parameters"->SurfaceImportTest.proxy(Parameters.class,(x,n,b)->{if(n.getName().equals("all"))return List.of(imported.yaw(),imported.pitch()).stream().map(id->{
                    var parameter=base.parameters().find(id);
                    return SurfaceImportTest.proxy(Parameter.class,(z,k,c)->k.getName().equals("name")?Optional.of(id.value()):SurfaceImportTest.invoke(k,parameter,c));
                }).toList();return SurfaceImportTest.invoke(n,base.parameters(),b);});
            case "drawables"->SurfaceImportTest.proxy(Drawables.class,(x,n,b)->{
                if(n.getName().equals("all"))return imported.geometry().patches().stream().map(p->mesh.apply(base.drawables().find(p.meshId()))).toList();
                var value=SurfaceImportTest.invoke(n,base.drawables(),b);return value instanceof Drawable d?mesh.apply(d):value;
            });
            case "textures"->SurfaceImportTest.proxy(m.getReturnType(),(x,n,b)->{
                if(n.getName().equals("rawImages") || n.getName().equals("modelImageGroups"))return List.of();
                return SurfaceImportTest.invoke(n,base.textures(),b);
            });
            default->SurfaceImportTest.invoke(m,base,a);
        });
    }
    public static void main(String[] args)throws Exception {
        var prepared=SurfaceImport.prepare(Files.readAllBytes(Path.of(args[0])),SurfaceImportTest.request());
        boolean sheets=prepared.sheetOrders!=null;
        for(boolean fail:new boolean[]{false,true}) {
            var world=new SurfaceImportTest.World();
            var imported=SurfaceImport.apply(prepared,world::model,world.transactions()).value().orElseThrow();
            var driver=new SurfaceGeometryCaptureTest.Driver(world);
            var testModel=model(world,driver,imported,fail);
            for(var patch:imported.geometry().patches())testModel.drawables().find(patch.meshId()).evaluationSnapshot();
            java.util.function.IntConsumer restore=target->{
                driver.restore(target);
                for(var patch:imported.geometry().patches()) {
                    testModel.drawables().find(patch.meshId()).evaluationSnapshot();
                    testModel.drawables().find(patch.meshId()).evaluationSnapshot();
                }
            };
            var geometry=new LinkedHashMap<String,Object>();
            var session=SurfaceGeometryCapture.run(prepared,imported,()->testModel,driver::history,restore,geometry);
            if(!driver.history().canRedo())throw new AssertionError("missing geometry redo ownership");
            var report=new LinkedHashMap<String,Object>();
            Path state=Path.of(args[1]+(fail?"-failure":"-success"));Files.createDirectory(state);
            Files.writeString(state.resolve("geometry.json"),NativeReport.json(geometry));
            boolean failed=false;
            try {
                if(sheets)SheetPixelCapture.run(prepared,imported,()->testModel,driver::history,restore,session,state,report);
                else PlanarPixelCapture.run(prepared,imported,()->testModel,driver::history,restore,session,state,report);
            } catch(IllegalStateException e) {if(!fail || !e.getMessage().equals("injected raster failure"))throw e;failed=true;}
            if(failed!=fail || !Boolean.TRUE.equals(report.get("finallyRestored")))throw new AssertionError("outcome/recovery");
            session.ready(driver.history());
            var rows=(List<?>)report.get("frames");if(rows.size()!=(fail?1:9))throw new AssertionError("frame inventory");
            for(var obj:rows) {
                var row=(Map<?,?>)obj;
                if(!Boolean.TRUE.equals(row.get("undoVerified")) || !row.get("liveRestored").equals(List.of(0f,0f)))throw new AssertionError("per-frame recovery");
            }
            if(driver.history().entries().size()>3 || driver.history().position()!=1)throw new AssertionError("history accumulation");
            Files.writeString(state.resolve("report.json"),NativeReport.json(report));
            System.out.println("PASS source="+prepared.inputSha256+" synthetic frames="+rows.size()+" renderFailure="+fail+" ownedHistoryRestored=true; nativePixels=false");
        }
    }
}
