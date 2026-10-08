package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Tests the actual importer orchestration through a synthetic SDK. No native atomicity claims. */
public final class SurfaceImportTest {
    static int checks;
    static SurfaceImport.Request request() {
        return new SurfaceImport.Request("Imported cube",CubeGeometryProbe.YAW,CubeGeometryProbe.PITCH,
            CubeGeometryProbe.CAMERA,30,30,2_000_000);
    }
    public static void main(String[] args) throws Exception {
        byte[] bytes=Files.readAllBytes(Path.of(args[0]));
        var prepared=SurfaceImport.prepare(bytes,request());
        check(prepared.inputSha256.equals(CubeGeometryProbe.INPUT_SHA256),"frozen input identity");
        var colored=SurfaceImport.prepare(bytes,request(),SurfaceBaseColorPlan.Appearance.FACE_COLORS);
        check(colored.inputSha256.equals(prepared.inputSha256) && colored.geometry.keys().equals(prepared.geometry.keys()),
            "face appearance preserves source identity and dense parameter grid");
        var coloredWorld=new World();
        var coloredResult=SurfaceImport.apply(colored,coloredWorld::model,coloredWorld.transactions());
        check(coloredResult.outcome()==AuthoringTransactionOutcome.COMMITTED && coloredWorld.calls==1
            && coloredWorld.base.registrations==6 && coloredWorld.base.reuses==0,"colored import commits six face images in one transaction");
        check(coloredResult.value().orElseThrow().materials().stream().map(m->m.argb()).distinct().count()==6,
            "colored importer returns six distinguishable faces");
        if(args.length>2) {
            var textured=SurfaceImport.prepare(Files.readAllBytes(Path.of(args[2])),request());
            check(textured.appearance.patches().stream().allMatch(p->p.material().textured()),"textured source reaches transaction plan");
            var texturedWorld=new World();
            var texturedResult=SurfaceImport.apply(textured,texturedWorld::model,texturedWorld.transactions());
            check(texturedResult.outcome()==AuthoringTransactionOutcome.COMMITTED && texturedWorld.calls==1,
                "actual textured import follows one authoring root");
            for(var patch:texturedResult.value().orElseThrow().geometry().patches()) {
                var snapshot=texturedWorld.model().drawables().find(patch.meshId()).authoringSnapshot();
                check(snapshot.sourceGeometry().uvs().equals(SurfaceImportGeometry.UvMode.EDGE_PADDED_IMAGE_PIXELS.map(patch.source().textureUvs())),"registered UVs retain complete source extent with one-pixel padding");
                check(snapshot.sourceGeometry().positions().equals(snapshot.sourceGeometry().uvs().stream()
                    .map(v->new Point2(v.x()*512,v.y()*256)).toList()),"actual import uses source image pixels instead of formed projection");
                patch.bake().verify(texturedWorld.model().drawables().find(patch.meshId()));
            }
            check(texturedWorld.base.registrations==1 && texturedWorld.base.reuses==5,"shared texture registered once");
        }
        bytes[0]=0;
        check(prepared.geometry.patches().size()==6 && prepared.geometry.keys().size()==2025,"six faces and dense two-axis grid");
        var noHost=new World();
        var unavailable=SurfaceImport.apply(prepared,noHost::model,AuthoringTransactionService.unavailable());
        check(unavailable.outcome()==AuthoringTransactionOutcome.UNAVAILABLE && noHost.modelReads==0,"unavailable service never touches model");
        var world=new World();var stages=new ArrayList<SurfaceImport.Stage>();
        var result=SurfaceImport.apply(prepared,world::model,world.transactions(),stages::add);
        check(result.outcome()==AuthoringTransactionOutcome.COMMITTED,"single root returned committed");
        var imported=result.value().orElseThrow();
        check(world.calls==1 && world.base.geometry.position==1,"one transaction and one synthetic history entry");
        check(stages.equals(List.of(SurfaceImport.Stage.values())),"all production stages observed in order");
        check(world.parts.size()==1 && imported.partId().equals(world.parts.get(0).id()),"group returned and used for all meshes");
        check(imported.geometry().patches().size()==6 && imported.materials().size()==6,"typed imported result complete");
        check(world.base.registrations==1 && world.base.reuses==5,"shared source material registered once");
        check(world.culling.size()==6 && world.culling.values().stream().allMatch(Boolean::booleanValue),"all faces enable native culling");
        for(var patch:imported.geometry().patches()) patch.bake().verify(world.model().drawables().find(patch.meshId()));
        if(args.length>1) Files.writeString(Path.of(args[1]),NativeReport.json(Map.of(
            "inputSha256",imported.inputSha256(),"partId",imported.partId().value(),
            "meshIds",imported.geometry().patches().stream().map(p->p.meshId().value()).toList(),
            "written",imported.geometry().patches().stream().map(p->NativeAssertions.snapshot(
                world.model().drawables().find(p.meshId()).authoringSnapshot())).toList())));
        var original=world.snapshot();
        var duplicate=SurfaceImport.apply(prepared,world::model,world.transactions());
        check(duplicate.outcome()==AuthoringTransactionOutcome.ROLLED_BACK && world.snapshot().equals(original),"duplicate axes rejected without changing import");
        for(var stage:SurfaceImport.Stage.values()) {
            var failed=new World();
            var prior=new ParameterId("Existing");
            failed.base.geometry.model().parameters().createMany(List.of(new ParameterDefinition(prior,"Existing",-1,0,1,ParameterType.NORMAL,false)));
            var before=failed.snapshot();var reached=new ArrayList<SurfaceImport.Stage>();
            var failure=SurfaceImport.apply(prepared,failed::model,failed.transactions(),at->{
                reached.add(at);if(at==stage)throw new IllegalStateException("injected "+stage);
            });
            check(failure.outcome()==AuthoringTransactionOutcome.ROLLED_BACK,"failure returned runtime result at "+stage);
            check(reached.get(reached.size()-1)==stage && failed.calls==1,"actual stage reached in one callback "+stage);
            check(failed.snapshot().equals(before),"synthetic service restored preexisting content at "+stage);
        }
        try { SurfaceImport.prepare(bytes,request()); throw new AssertionError("bad GLB admitted"); }
        catch(IllegalArgumentException expected) { checks++; }
        try { new SurfaceImport.Request("",CubeGeometryProbe.YAW,CubeGeometryProbe.PITCH,CubeGeometryProbe.CAMERA,30,30,100);throw new AssertionError(); }
        catch(IllegalArgumentException expected) { checks++; }
        System.out.println("SurfaceImportTest: "+checks+" checks PASS; importer orchestration only; hostExecuted=false");
    }
    record State(JointPoseProducerIntegrationTest.World.State geometry,Map<ArtMeshId,ModelImageId> images,
            Map<ArtMeshId,Boolean> culling,List<Part> parts,int registrations,int reuses) { }
    static final class World {
        final SurfaceImportAppearanceTest.World base=new SurfaceImportAppearanceTest.World();
        final Map<ArtMeshId,Boolean> culling=new HashMap<>();
        final List<Part> parts=new ArrayList<>();
        int calls,modelReads;boolean active;
        State snapshot() {return new State(base.geometry.state(),Map.copyOf(base.bound),Map.copyOf(culling),List.copyOf(parts),base.registrations,base.reuses);}
        void restore(State state) {
            var g=state.geometry();base.geometry.defs=new LinkedHashMap<>(g.defs());base.geometry.meshes=new LinkedHashMap<>(g.meshes());
            base.geometry.live.clear();base.geometry.live.putAll(g.live());
            base.bound.clear();base.bound.putAll(state.images());culling.clear();culling.putAll(state.culling());
            parts.clear();parts.addAll(state.parts());base.registrations=state.registrations();base.reuses=state.reuses();
        }
        CubismModel model() {
            modelReads++;var delegate=base.model();
            return proxy(CubismModel.class,(o,m,a)->{
                if(m.getName().equals("canvas"))throw new AssertionError("import must not alter canvas");
                if(m.getName().equals("parts")) return proxy(Parts.class,(po,pm,pa)->{
                    if(pm.getName().equals("all"))return List.copyOf(parts);
                    if(!pm.getName().equals("create") || !active)throw new AssertionError(pm);
                    var id=new PartId("group"+parts.size());
                    Part part=proxy(Part.class,(p,x,y)->switch(x.getName()) {
                        case "id"->id;case "equals"->p==y[0];case "hashCode"->System.identityHashCode(p);default->throw new AssertionError(x);
                    });parts.add(part);return part;
                });
                if(m.getName().equals("drawables")) {
                    var drawables=delegate.drawables();
                    return proxy(Drawables.class,(d,dm,da)->{
                        if(dm.getName().equals("create") || dm.getName().equals("createWithSourceGeometry")) check(active && da[1]==parts.get(0),"mesh created in root under import group");
                        var value=invoke(dm,drawables,da);
                        if(value instanceof Drawable mesh)return mesh(mesh);
                        return value;
                    });
                }
                return invoke(m,delegate,a);
            });
        }
        Drawable mesh(Drawable delegate) {
            return proxy(Drawable.class,(o,m,a)->switch(m.getName()) {
                case "setCulling"->{if(!active)throw new AssertionError("culling outside root");culling.put(delegate.id(),(Boolean)a[0]);yield null;}
                case "culling"->culling.getOrDefault(delegate.id(),false);
                default->invoke(m,delegate,a);
            });
        }
        AuthoringTransactionService transactions() {
            return new AuthoringTransactionService() {
                public <T> AuthoringTransactionResult<T> execute(AuthoringTransactionOptions options,AuthoringTransactionWork<T> work) {
                    if(active)throw new AssertionError("nested transaction");calls++;var saved=snapshot();var before=base.geometry.history();
                    active=true;base.geometry.inTransaction=true;
                    try {
                        T value=work.run();base.geometry.inTransaction=false;base.geometry.commit();
                        return AuthoringTransactionResult.committed(value,new AuthoringTransactionReceipt("synthetic",options.label(),before,
                            base.geometry.history(),Optional.of("entry")));
                    } catch(Exception failure) {
                        restore(saved);
                        return AuthoringTransactionResult.rolledBack(new AuthoringTransactionReceipt("synthetic",options.label(),before,
                            base.geometry.history(),Optional.empty()),"injected-or-preflight");
                    } finally {active=false;base.geometry.inTransaction=false;}
                }
            };
        }
    }
    interface Call {Object invoke(Object proxy,Method method,Object[] args) throws Throwable;}
    static <T>T proxy(Class<T> type,Call call) {return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},call::invoke));}
    static Object invoke(Method method,Object target,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
