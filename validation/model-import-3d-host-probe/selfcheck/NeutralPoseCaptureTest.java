package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.*;
import java.nio.file.*;
import java.util.*;

/** Synthetic evaluator: reads never invent a fresh pass for unchanged parameters. */
public final class NeutralPoseCaptureTest {
    public static void main(String[] args)throws Exception {
        var prepared=SurfaceImport.prepare(Files.readAllBytes(Path.of(args[0])),SurfaceImportTest.request());
        var world=new SurfaceImportTest.World();
        var imported=SurfaceImport.apply(prepared,world::model,world.transactions()).value().orElseThrow();
        var driver=new SurfaceGeometryCaptureTest.Driver(world);
        var base=SurfaceGeometryCaptureTest.model(world,false,driver);
        var cached=new HashMap<String,ArtMeshEvaluationSnapshot>();
        var model=SurfaceImportTest.proxy(CubismModel.class,(o,m,a)->{
            if(!m.getName().equals("drawables"))return SurfaceImportTest.invoke(m,base,a);
            return SurfaceImportTest.proxy(Drawables.class,(x,n,b)->{
                var value=SurfaceImportTest.invoke(n,base.drawables(),b);
                if(!(value instanceof Drawable mesh))return value;
                return SurfaceImportTest.proxy(Drawable.class,(z,k,c)->{
                    if(!k.getName().equals("evaluationSnapshot"))return SurfaceImportTest.invoke(k,mesh,c);
                    var e=mesh.evaluationSnapshot();var old=cached.get(e.artMeshId().value());
                    var consumed=new ArtMeshEvaluationSnapshot.ParameterState(e.liveParameters().updateVersion(),e.liveParameters().parameters());
                    var next=new ArtMeshEvaluationSnapshot(e.artMeshId(),e.sourceGuid(),e.calculatedForm(),e.liveParameters(),consumed,
                        e.liveParameters().updateVersion());
                    cached.put(e.artMeshId().value(),next);
                    if(old==null)return next;
                    return new ArtMeshEvaluationSnapshot(e.artMeshId(),e.sourceGuid(),old.calculatedForm(),e.liveParameters(),
                        old.consumedParameters(),old.passEntryTimeMillis());
                });
            });
        });
        for(var patch:imported.geometry().patches())model.drawables().find(patch.meshId()).evaluationSnapshot();
        var report=new LinkedHashMap<String,Object>();
        SurfaceGeometryCapture.run(prepared,imported,()->model,driver::history,target->{
            driver.restore(target);
            // The native failure had already consumed the Undo-restored neutral
            // pose before the next drive. Flush that pass deterministically.
            for(var patch:imported.geometry().patches()) {
                model.drawables().find(patch.meshId()).evaluationSnapshot();
                model.drawables().find(patch.meshId()).evaluationSnapshot();
            }
        },report);
        var rows=(List<?>)report.get("samples");
        long primes=rows.stream().map(v->(Map<?,?>)v).filter(v->v.containsKey("priming")).count();
        if(primes!=1 || !Boolean.TRUE.equals(report.get("restored")))throw new AssertionError("neutral return coverage");
        Files.writeString(Path.of(args[1]),NativeReport.json(report));
        System.out.println("PASS "+rows.size()+" poses including one neutral priming return; synthetic evaluation only");
    }
}
