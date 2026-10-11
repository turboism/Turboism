package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.util.*;
import java.util.function.Supplier;

/** SDK-only witness on a task-owned document; never claims source-model acceptance. */
final class NativeInterpolationWitness {
    private static final ParameterId X=new ParameterId("ProbePitch"), Y=new ParameterId("ProbeYaw");
    private static final List<Float> KEYS=List.of(-180f,-90f,0f,90f,180f);
    private static final double[][] V={{-120,-80,110},{140,-70,-90},{20,160,70},{-60,30,-130}};
    private static final ParameterInterpolation E6=new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE,6,1);
    private static final ParameterInterpolation E2=new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE,2,1);
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static Map<String,Object> run(PluginContext context,Supplier<CubismModel> current,Map<String,Object> owner) {
        var report=new LinkedHashMap<String,Object>();owner.put("interpolationWitness",report);
        var id=create(context,current);
        var source=current.get().drawables().find(id).authoringSnapshot();
        var target=ParameterBindingTarget.artMesh(id);
        var original=List.of(current.get().parameterBindings(X).interpolation(target),current.get().parameterBindings(Y).interpolation(target));
        report.put("originalSettings",original.toString());
        var history=context.cubism().history();int before=history.snapshot().position();
        change(context,current,target,E6);check(history.snapshot().position()==before+1,"two-axis edit must be one history root");
        checkSource(current,id,source);settings(current,target,E6);
        var undoMove=history.undo(1);report.put("firstUndo",undoMove.outcome().name());checkSource(current,id,source);
        check(current.get().parameterBindings(X).interpolation(target).equals(original.get(0)) && current.get().parameterBindings(Y).interpolation(target).equals(original.get(1)),"new extension Undo settings: original="+original+" actual="+List.of(current.get().parameterBindings(X).interpolation(target),current.get().parameterBindings(Y).interpolation(target))+" move="+undoMove.outcome());
        history.redo(1);checkSource(current,id,source);settings(current,target,E6);
        report.put("ellipse6Samples",evaluate(context,current,id,5));
        change(context,current,target,E2);checkSource(current,id,source);settings(current,target,E2);
        history.undo(1);checkSource(current,id,source);settings(current,target,E6);
        history.redo(1);checkSource(current,id,source);settings(current,target,E2);
        report.put("ellipse2Samples",evaluate(context,current,id,25));
        var rollback=context.cubism().authoringTransactions().execute(AuthoringTransactionOptions.of("Interpolation witness abort"),()->{
            current.get().parameterBindings(X).setInterpolation(target,E6);
            current.get().parameterBindings(Y).setInterpolation(target,E6);
            throw new IllegalStateException("intentional witness abort");
        });
        check(rollback.outcome()==AuthoringTransactionOutcome.ROLLED_BACK,"abort recovery failed: "+rollback);
        checkSource(current,id,source);settings(current,target,E2);evaluate(context,current,id,25);
        change(context,current,target,E6);checkSource(current,id,source);settings(current,target,E6);
        report.put("rollbackOutcome",rollback.outcome().name());report.put("sourceForms",source.forms().size());
        report.put("sourceProtected",true);report.put("newExtensionUndoRedo",true);report.put("existingCacheUndoRedo",true);
        report.put("scope","ACTUAL_EDITOR_SDK_INTERPOLATION_HISTORY_NOT_MODEL_ACCEPTANCE");return report;
    }
    static ArtMeshId create(PluginContext context,Supplier<CubismModel> current) {
        var result=context.cubism().authoringTransactions().execute(AuthoringTransactionOptions.of("Interpolation witness source"),()->{
            var model=current.get();check(model.drawables().all().isEmpty(),"isolated empty model required");
            var p=project(0,0);
            var geometry=new ArtMeshGeometry(p,List.of(new Point2(0,0),new Point2(1,0),new Point2(1,1),new Point2(0,1)),List.of(0,1,2,0,2,3));
            var mesh=model.drawables().create("Interpolation witness",null,-1,geometry);
            model.parameters().createMany(List.of(new ParameterDefinition(X,"Probe X",-180,0,180,ParameterType.NORMAL,false),new ParameterDefinition(Y,"Probe Y",-180,0,180,ParameterType.NORMAL,false)));
            for(var id:List.of(X,Y)) {
                var points=new ArrayList<ParameterBindingPoint>();
                for(int i=0;i<KEYS.size();i++)points.add(new ParameterBindingPoint(new ParameterBindingPointId(id.value()+i),KEYS.get(i)));
                model.parameterBindings(id).bind(ParameterBindingTarget.artMesh(mesh.id()),points);
            }
            var snapshot=mesh.authoringSnapshot();var edits=new ArrayList<ArtMeshKeyformPositions>();
            var xGuid=snapshot.axes().stream().filter(a->a.parameterId().equals(X)).findFirst().orElseThrow().parameterGuid();
            var yGuid=snapshot.axes().stream().filter(a->a.parameterId().equals(Y)).findFirst().orElseThrow().parameterGuid();
            for(var cell:snapshot.cells()) {
                float x=cell.coordinates().stream().filter(c->c.parameterGuid().equals(xGuid)).findFirst().orElseThrow().value();
                float y=cell.coordinates().stream().filter(c->c.parameterGuid().equals(yGuid)).findFirst().orElseThrow().value();
                if(x!=0 || y!=0)edits.add(new ArtMeshKeyformPositions(cell.coordinates(),cell.formGuid(),project(x,y)));
            }
            mesh.setOrdinaryKeyformPositions(snapshot,edits);return mesh.id();
        });
        check(result.changed(),"source creation failed: "+result);return result.value().orElseThrow();
    }
    static Map<String,Object> persistenceState(PluginContext context,Supplier<CubismModel> current,boolean create) {
        if(create){var id=create(context,current);change(context,current,ParameterBindingTarget.artMesh(id),E6);}
        var meshes=current.get().drawables().all();check(meshes.size()==1,"one persisted witness mesh");var mesh=meshes.get(0);
        settings(current,ParameterBindingTarget.artMesh(mesh.id()),E6);
        var source=mesh.authoringSnapshot();check(source.forms().size()==25 && source.axes().size()==2 && source.sourceGeometry().positions().size()==4,"persisted source domain");
        var samples=evaluate(context,current,mesh.id(),5);checkSource(current,mesh.id(),source);
        return Map.of("sourceBank",source.toString(),"samples",samples,"sourceForms",25,"settings",E6.toString(),"scope","EDITOR_INTERPOLATION_PERSISTENCE_ONLY");
    }
    private static void change(PluginContext c,Supplier<CubismModel> m,ParameterBindingTarget t,ParameterInterpolation value){
        var r=c.cubism().authoringTransactions().execute(AuthoringTransactionOptions.of("Interpolation witness settings"),()->{
            for(var id:List.of(X,Y))m.get().parameterBindings(id).setInterpolation(t,value);return null;
        });check(r.changed(),"settings transaction failed: "+r);
    }
    private static void checkSource(Supplier<CubismModel> m,ArtMeshId id,ArtMeshAuthoringSnapshot s){check(m.get().drawables().find(id).authoringSnapshot().equals(s),"source banks changed");}
    private static void settings(Supplier<CubismModel> m,ParameterBindingTarget t,ParameterInterpolation value){for(var id:List.of(X,Y))check(m.get().parameterBindings(id).interpolation(t).equals(value),"restored settings differ");}
    private static List<Map<String,Object>> evaluate(PluginContext context,Supplier<CubismModel> m,ArtMeshId id,double limit){
        var history=context.cubism().history();int before=history.snapshot().position();
        var out=new ArrayList<Map<String,Object>>();
        for(float[] pose:new float[][]{{17,31},{-123,67},{159,-141},{-45,-22},{0,0}}){
            var model=m.get();model.parameters().find(X).setValue(pose[0]);model.parameters().find(Y).setValue(pose[1]);model.update();
            var observed=model.drawables().find(id).evaluationSnapshot();
            for(int a=0;a<2;a++){var pid=a==0?X:Y;float v=pose[a];check(observed.consumedParameters().parameters().stream().anyMatch(p->p.parameterId().equals(pid) && p.value()==v),"evaluation did not consume pose");}
            var actual=observed.calculatedForm().orElseThrow().positions();var reference=project(pose[0],pose[1]);check(actual.size()==V.length,"evaluated vertex count");double max=0;
            for(int i=0;i<V.length;i++)max=Math.max(max,Math.hypot(actual.get(i).x()-reference.get(i).x(),actual.get(i).y()-reference.get(i).y()));
            check(max<limit,"native evaluated interpolation error "+max);out.add(Map.of("x",pose[0],"y",pose[1],"maxPixels",max,"actual",actual.stream().map(p->List.of(p.x(),p.y())).toList()));
        }
        int added=history.snapshot().position()-before;check(added>=0,"pose history moved backwards");
        if(added>0){var restored=history.undo(added);check(restored.snapshot().position()==before,"pose history restore failed");}
        return out;
    }
    private static List<Point2> project(float x,float y){
        double a=Math.toRadians(x),b=Math.toRadians(y),ca=Math.cos(a),sa=Math.sin(a),cb=Math.cos(b),sb=Math.sin(b);var out=new ArrayList<Point2>();
        for(var v:V){double ry=ca*v[1]-sa*v[2],rz=sa*v[1]+ca*v[2];out.add(new Point2((float)(512+cb*v[0]+sb*rz),(float)(512+ry)));}return out;
    }
}
