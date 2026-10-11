package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** SDK stubs exercise the real importer orchestration, never native rollback or rendering. */
class SurfaceWarpAuthoringTest {
    @Test void derivedAuthoringRegistersDistinctChartImagesWithoutChangingProjectionBank() throws Exception {
        var sources=List.of(SurfaceWarpImagePlanTest.source(0,false,List.of(.5d,.25d,.1d,1d)),
            SurfaceWarpImagePlanTest.source(1,false,List.of(.5d,.25d,.2d,1d)));
        var geometry=SurfaceWarpLayerPlan.splitCharts(SurfaceWarpImagePlanTest.geometry(sources));
        var original=SurfaceWarpAuthoring.prepare(geometry,request(1000),grid(),1);
        var prepared=SurfaceWarpAuthoring.derivedImages(original);
        for(int y=0;y<3;y++)for(int p=0;p<3;p++)assertEquals(original.controls(y,p),prepared.controls(y,p));
        var state=new State(prepared,"normal");var created=SurfaceWarpAuthoring.assemble(state.model(),prepared,s->{});
        assertEquals(2,state.registrations);assertEquals(0,state.reuses);
        assertNotEquals(created.imageBindings().get(0).modelImageId(),created.imageBindings().get(1).modelImageId());
        SurfaceWarpAuthoring.verifyInputs(state.model(),created,prepared.images.inputs,Optional.of(WARP));
        SurfaceWarpAuthoring.verifyImageInputs(state.model(),created);
        var rejected=new State(prepared,"preview-missing-face");
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpAuthoring.assemble(rejected.model(),prepared,s->{}));
        assertTrue(rejected.meshes.isEmpty());assertNull(rejected.grid);assertEquals(0,rejected.registrations);
    }
    static final ParameterId YAW=new ParameterId("Yaw"),PITCH=new ParameterId("Pitch");
    static final DeformerId WARP=new DeformerId("warp");static final PartId PART=new PartId("part");
    static final ModelId MODEL=new ModelId("model");static final ModelImageId IMAGE=new ModelImageId("image");static final RawImageId RAW=new RawImageId("raw");
    static SurfaceWarpChartPlan.Plan geometry() {
        return SurfaceWarpImagePlanTest.geometry(List.of(SurfaceWarpImagePlanTest.source(0,true,List.of(1d,1d,1d,1d)),SurfaceWarpImagePlanTest.source(1,true,List.of(1d,1d,1d,1d))),3);
    }
    static SurfaceImport.Request request(long budget) {return new SurfaceImport.Request("Test",YAW,PITCH,new SurfaceProjection.Camera(0,0,1,new SurfaceProjection.Vertex(0,0,0)),30,30,budget);}
    static SurfaceKeyPlan.Grid grid() {return new SurfaceKeyPlan.Grid(List.of(-30d,0d,30d),List.of(-30d,0d,30d),999);}
    static SurfaceWarpAuthoring.Prepared prepared() throws Exception {return SurfaceWarpAuthoring.prepare(geometry(),request(1000),grid(),1);}
    @Test void preparationStoresActualFloatKeyProjectionsAndRejectsCapacityOrAccuracyBeforeHostAccess() throws Exception {
        var p=prepared();assertTrue(p.idealErrorBoundPixels<1);assertTrue(p.grid.errorBoundPixels()<1);
        for(int y=0;y<3;y++)for(int pitch=0;pitch<3;pitch++)assertEquals(p.geometry.projectControls(p.request.camera(),new SurfaceProjection.Pose(p.yawKeys.get(y),p.pitchKeys.get(pitch))),p.controls(y,pitch));
        assertThrows(UnsupportedOperationException.class,()->p.controls(0,0).set(0,new Point2(0,0)));
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpAuthoring.prepare(geometry(),request(134),grid(),1));
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpAuthoring.prepare(geometry(),request(1000),grid(),.001));
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpAuthoring.prepare(geometry(),request(1000),new SurfaceKeyPlan.Grid(List.of(-30d,-15d,0d,15d,30d),grid().pitchKeys(),1),1));
    }
    @Test void assemblesRealImporterInRequiredOrderWithOneSharedImageAndCompleteJointControls() throws Exception {
        var p=prepared();var state=new State(p,"normal");var stages=new ArrayList<SurfaceWarpAuthoring.Stage>();var created=SurfaceWarpAuthoring.assemble(state.model(),p,stages::add);
        assertEquals(List.of(SurfaceWarpAuthoring.Stage.IDENTITY,SurfaceWarpAuthoring.Stage.MATERIAL,SurfaceWarpAuthoring.Stage.ATTACHED,SurfaceWarpAuthoring.Stage.NEUTRAL,SurfaceWarpAuthoring.Stage.BOUND,SurfaceWarpAuthoring.Stage.BAKED),stages);
        assertEquals(1,state.registrations);assertEquals(created.meshIds().size()-1,state.reuses);assertEquals(9,state.snapshot.forms().size());assertEquals(1,state.bakeCalls);
        assertEquals(Optional.of(new DeformerId("placement")),state.warpParent);
        assertEquals(new RotationDeformerForm(0,0,0,1,false,false),state.placementForm);
        for(var cell:state.snapshot.cells()) {
            float y=cell.coordinates().stream().filter(c->c.parameterGuid().equals("guid-Yaw")).findFirst().orElseThrow().value();float pitch=cell.coordinates().stream().filter(c->c.parameterGuid().equals("guid-Pitch")).findFirst().orElseThrow().value();
            var form=state.snapshot.forms().stream().filter(f->f.formGuid().equals(cell.formGuid())).findFirst().orElseThrow();assertEquals(p.controls(p.yawKeys.indexOf(y),p.pitchKeys.indexOf(pitch)),form.controlPoints());
        }
        SurfaceWarpAuthoring.verifyInputs(state.model(),created,p.images.inputs,Optional.of(WARP));SurfaceWarpAuthoring.verifyImageInputs(state.model(),created);
    }
    @Test void tinyNativeParentRoundOffIsFrozenAndLaterDriftStillRejected() throws Exception {
        var p=prepared();var state=new State(p,"parent-roundoff");
        var created=SurfaceWarpAuthoring.assemble(state.model(),p,s->{});
        assertTrue(created.maximumParentLocalDrift()>0 && created.maximumParentLocalDrift()<1e-6);
        assertTrue(created.parentRoundTripErrorBoundPixels()>0);
        assertTrue(p.idealErrorBoundPixels+created.parentRoundTripErrorBoundPixels()<p.maximumErrorPixels);
        SurfaceWarpAuthoring.verifyInputs(state.model(),created,p.images.inputs,Optional.of(WARP));
        var first=state.meshes.values().iterator().next();var positions=new ArrayList<>(first.local);
        positions.set(0,new Point2(positions.get(0).x()+1e-7f,positions.get(0).y()));first.local=positions;
        assertThrows(IllegalStateException.class,()->SurfaceWarpAuthoring.verifyInputs(state.model(),created,p.images.inputs,Optional.of(WARP)));
    }
    @Test void rotationOriginFloatRoundOffIsBoundedAndFrozen() throws Exception {
        var base=prepared();var state=new State(base,"normal");
        var original=SurfaceWarpAuthoring.assemble(state.model(),base,s->{});
        var req=request(1000);
        var rollRequest=new SurfaceImport.Request(req.name(),req.yaw(),req.pitch(),req.camera(),
            30,30,1000,Optional.of(new ParameterId("Roll")));
        var p=SurfaceWarpAuthoring.prepare(geometry(),rollRequest,grid(),1);
        for(var mesh:state.meshes.values()) {
            var positions=new ArrayList<>(mesh.local);var point=positions.get(0);
            positions.set(0,new Point2(point.x()+1.517e-5f,point.y()));mesh.local=positions;
        }
        assertThrows(IllegalStateException.class,()->SurfaceWarpAuthoring.acceptParentRoundTrip(state.model(),original,base));
        var created=SurfaceWarpAuthoring.acceptParentRoundTrip(state.model(),original,p);
        assertTrue(created.maximumParentLocalDrift()>1e-6 && created.maximumParentLocalDrift()<1e-4);
        assertTrue(p.idealErrorBoundPixels+created.parentRoundTripErrorBoundPixels()<p.maximumErrorPixels);
        SurfaceWarpAuthoring.verifyInputs(state.model(),created,p.images.inputs,Optional.of(WARP));
        var mesh=state.meshes.values().iterator().next();var positions=new ArrayList<>(mesh.local);
        positions.set(0,new Point2(positions.get(0).x()+1e-7f,positions.get(0).y()));mesh.local=positions;
        assertThrows(IllegalStateException.class,()->SurfaceWarpAuthoring.verifyInputs(state.model(),created,p.images.inputs,Optional.of(WARP)));
    }

    @Test void parentRoundOffCannotConsumeAnUnavailablePixelBudget() throws Exception {
        var prepared=prepared();
        var tight=SurfaceWarpAuthoring.prepare(geometry(),request(1000),grid(),prepared.idealErrorBoundPixels+1e-12);
        var state=new State(tight,"parent-roundoff");
        assertThrows(IllegalStateException.class,()->SurfaceWarpAuthoring.assemble(state.model(),tight,s->{}));
        assertEquals(0,state.bakeCalls);
    }
    @Test void attachesNormalizedChartsBeforeAddingTheTranslatedRollParent() throws Exception {
        var request=new SurfaceImport.Request("Apple",YAW,PITCH,new SurfaceProjection.Camera(512,512,1,new SurfaceProjection.Vertex(0,0,0)),
            30,30,1000,Optional.of(new ParameterId("Roll")));
        var prepared=SurfaceWarpAuthoring.prepare(geometry(),request,grid(),1);
        var state=new State(prepared,"reject-roll-during-mesh-attachment");
        var created=SurfaceWarpAuthoring.assemble(state.model(),prepared,s->{});
        assertEquals(0,created.maximumParentLocalDrift());
        assertEquals(Optional.of(new DeformerId("roll")),state.warpParent);
        assertEquals(Optional.of(new DeformerId("placement")),state.rollParent);
        for(var mesh:state.meshes.values())assertEquals(Optional.of(WARP),mesh.parent);
    }
    @Test void imageAndReparentCorruptionStopBeforeBindingOrBake() throws Exception {
        for(String fault:List.of("image-uv","parent-local","parent-form-guid","image-owner")) {
            var state=new State(prepared(),fault);assertThrows(IllegalStateException.class,()->SurfaceWarpAuthoring.assemble(state.model(),state.prepared,s->{}));assertEquals(0,state.bakeCalls);assertTrue(state.axes.isEmpty());
        }
    }
    @Test void existingParameterIsRejectedBeforeWritingAndUnavailableScopeNeverTouchesModel() throws Exception {
        var p=prepared();var state=new State(p,"normal");state.definitions.add(new ParameterDefinition(YAW,"existing",-30,0,30,ParameterType.NORMAL,false));
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpAuthoring.assemble(state.model(),p,s->{}));assertEquals(0,state.meshes.size());assertEquals(0,state.gridWrites);
        int[] requested={0};AuthoringTransactionService scope=new AuthoringTransactionService(){public <T>AuthoringTransactionResult<T> execute(AuthoringTransactionOptions options,AuthoringTransactionWork<T> work){requested[0]++;return AuthoringTransactionService.unavailable().execute(options,work);}};
        assertEquals(AuthoringTransactionOutcome.UNAVAILABLE,SurfaceWarpAuthoring.apply(p,()->{throw new AssertionError("model access outside available scope");},scope).outcome());assertEquals(1,requested[0]);
    }
    static <T>T proxy(Class<T> type,BiFunction<Method,Object[],Object> call) {return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(o,m,a)->call.apply(m,a)));}
    static Object unexpected(Method method) {throw new AssertionError("unexpected SDK call "+method);}
    static final class MeshState {
        final ArtMeshId id;ArtMeshGeometry geometry;List<Point2> local;String form="default";Optional<DeformerId> parent=Optional.empty();boolean culling;
        MeshState(ArtMeshId id,ArtMeshGeometry geometry,List<Point2> local){this.id=id;this.geometry=geometry;this.local=List.copyOf(local);}
        int order=500;
        final LinkedHashMap<ParameterId,List<Float>> axes=new LinkedHashMap<>();
        final Map<String,Integer> orders=new HashMap<>();
        ArtMeshAuthoringSnapshot snapshot(){
            var axisList=axes.entrySet().stream().map(a->new ArtMeshAuthoringSnapshot.Axis("guid-"+a.getKey().value(),a.getKey(),-30,0,30,a.getValue())).toList();
            var cells=new ArrayList<ArtMeshAuthoringSnapshot.Cell>();var forms=new ArrayList<ArtMeshAuthoringSnapshot.Form>();
            for(float y:axes.getOrDefault(YAW,List.of(0f)))for(float p:axes.getOrDefault(PITCH,List.of(0f))) {
                var coordinates=new ArrayList<ArtMeshAuthoringSnapshot.Coordinate>();
                if(axes.containsKey(YAW))coordinates.add(new ArtMeshAuthoringSnapshot.Coordinate("guid-Yaw",y));
                if(axes.containsKey(PITCH))coordinates.add(new ArtMeshAuthoringSnapshot.Coordinate("guid-Pitch",p));
                String guid=y==0f && p==0f?form:id.value()+"-"+y+"-"+p;
                cells.add(new ArtMeshAuthoringSnapshot.Cell(guid,coordinates));forms.add(new ArtMeshAuthoringSnapshot.Form(guid,local,orders.getOrDefault(guid,order)));
            }
            return new ArtMeshAuthoringSnapshot(id,"source-"+id.value(),geometry,axisList,cells,forms,List.of());
        }
    }
    static final class State {
        final SurfaceWarpAuthoring.Prepared prepared;final String fault;final LinkedHashMap<ArtMeshId,MeshState> meshes=new LinkedHashMap<>();final List<ParameterDefinition> definitions=new ArrayList<>();
        final LinkedHashMap<ParameterId,List<Float>> axes=new LinkedHashMap<>();final Map<ArtMeshId,ArtMeshTextureInput> imageInputs=new HashMap<>();WarpGrid grid;WarpAuthoringSnapshot snapshot;int registrations,reuses,bakeCalls,gridWrites,orderBatches;
        Optional<DeformerId> warpParent=Optional.empty();RotationDeformerForm placementForm;
        Optional<DeformerId> rollParent=Optional.empty();final SurfaceRotationAuthoringTest.State rollState=new SurfaceRotationAuthoringTest.State();
        State(SurfaceWarpAuthoring.Prepared p,String fault){prepared=p;this.fault=fault;}
        List<Parameter> parameters(){return definitions.stream().map(d->proxy(Parameter.class,(m,a)->m.getName().equals("id")?d.id():unexpected(m))).toList();}
        void rebuild() {
            var axisList=axes.entrySet().stream().map(a->new WarpAuthoringSnapshot.Axis("guid-"+a.getKey().value(),a.getKey(),-30,0,30,a.getValue())).toList();var cells=new ArrayList<WarpAuthoringSnapshot.Cell>();var forms=new ArrayList<WarpAuthoringSnapshot.Form>();
            for(float y:axes.getOrDefault(YAW,List.of(0f)))for(float p:axes.getOrDefault(PITCH,List.of(0f))) {
                var coords=new ArrayList<WarpAuthoringSnapshot.Coordinate>();if(axes.containsKey(YAW))coords.add(new WarpAuthoringSnapshot.Coordinate("guid-Yaw",y));if(axes.containsKey(PITCH))coords.add(new WarpAuthoringSnapshot.Coordinate("guid-Pitch",p));
                String guid="form-"+y+"-"+p;cells.add(new WarpAuthoringSnapshot.Cell(guid,coords));forms.add(new WarpAuthoringSnapshot.Form(guid,grid.controlPoints()));
            }
            snapshot=new WarpAuthoringSnapshot(WARP,"warp-source",grid.rows(),grid.columns(),grid.quadTransform(),axisList,cells,forms);
        }
        Drawable drawable(MeshState state){return proxy(Drawable.class,(m,a)->switch(m.getName()) {
            case "id" -> state.id;case "authoringSnapshot" -> state.snapshot();case "culling" -> state.culling;case "visible" -> true;case "parentDeformerId" -> state.parent;
            case "setCulling" -> {state.culling=(boolean)a[0];yield null;}
            case "setDrawOrder" -> {assertTrue(state.axes.isEmpty());state.order=(int)a[0];yield null;}
            case "setOrdinaryKeyformDrawOrders" -> {
                assertEquals(state.snapshot(),a[0]);@SuppressWarnings("unchecked") var edits=(List<ArtMeshKeyformDrawOrder>)a[1];
                for(var e:edits){assertFalse(e.coordinates().stream().allMatch(c->c.value()==0));state.orders.put(e.formGuid(),e.drawOrder());}
                orderBatches++;yield null;
            }
            case "setTargetDeformer" -> { @SuppressWarnings("unchecked") var parent=(Optional<DeformerId>)a[0];state.parent=parent;
                if(fault.equals("reject-roll-during-mesh-attachment"))assertTrue(warpParent.isEmpty(),"normalized mesh attachment must not pass through translated Roll coordinates");
                if(fault.equals("parent-roundoff")){var p=new ArrayList<>(state.local);var v=p.get(0);p.set(0,new Point2(v.x()+1e-7f,v.y()));state.local=p;}
                if(fault.equals("parent-rotation-roundoff")){var p=new ArrayList<>(state.local);var v=p.get(0);p.set(0,new Point2(v.x()+1.517e-5f,v.y()));state.local=p;}
                if(fault.equals("parent-local")){var p=new ArrayList<>(state.local);p.set(0,new Point2(.125f,0));state.local=p;}
                if(fault.equals("parent-form-guid"))state.form="changed-form";yield null;}
            default -> unexpected(m);
        });}
        RotationDeformer roll(){return proxy(RotationDeformer.class,(m,a)->switch(m.getName()) {
            case "id" -> new DeformerId("roll");case "parentDeformerId" -> rollParent;
            case "setTargetDeformer" -> {rollParent=(Optional<DeformerId>)a[0];yield null;}
            case "replaceForm" -> {rollState.neutral=(RotationDeformerForm)a[0];yield null;}
            case "authoringSnapshot" -> rollState.snapshot();
            case "setOrdinaryKeyformForms" -> {
                assertEquals(rollState.snapshot(),a[0]);
                for(var edit:(List<RotationKeyformForm>)a[1])rollState.values.put(edit.formGuid(),edit.value());yield null;
            }
            default -> unexpected(m);
        });}
        WarpDeformer warp(){return proxy(WarpDeformer.class,(m,a)->switch(m.getName()) {
            case "id" -> WARP;case "replaceGrid" -> {gridWrites++;grid=(WarpGrid)a[0];rebuild();yield null;}
            case "parentDeformerId" -> warpParent;
            case "setTargetDeformer" -> {warpParent=(Optional<DeformerId>)a[0];yield null;}
            case "authoringSnapshot" -> snapshot;
            case "setOrdinaryKeyformPositions" -> {assertEquals(snapshot,a[0]);@SuppressWarnings("unchecked") var edits=(List<WarpKeyformPositions>)a[1];var byGuid=new HashMap<String,List<Point2>>();edits.forEach(e->byGuid.put(e.formGuid(),e.controlPoints()));
                snapshot=new WarpAuthoringSnapshot(WARP,snapshot.sourceGuid(),snapshot.rows(),snapshot.columns(),snapshot.quadTransform(),snapshot.axes(),snapshot.cells(),snapshot.forms().stream().map(f->new WarpAuthoringSnapshot.Form(f.formGuid(),byGuid.getOrDefault(f.formGuid(),f.controlPoints()))).toList());bakeCalls++;yield null;}
            default -> unexpected(m);
        });}
        RotationDeformer placement(){return proxy(RotationDeformer.class,(m,a)->switch(m.getName()) {
            case "id" -> new DeformerId("placement");
            case "parentDeformerId" -> Optional.empty();
            case "replaceForm" -> {placementForm=(RotationDeformerForm)a[0];yield null;}
            case "authoringSnapshot" -> new RotationAuthoringSnapshot(new DeformerId("placement"),"placement-source",0,List.of(),
                List.of(new RotationAuthoringSnapshot.Cell("placement-default",List.of())),
                List.of(new RotationAuthoringSnapshot.Form("placement-default",placementForm)));
            default -> unexpected(m);
        });}
        ModelImageBinding bind(ArtMeshId id,ModelImageId image) {
            assertTrue(meshes.get(id).parent.isEmpty(),"image must precede deformer parent");
            imageInputs.put(id,new ArtMeshTextureInput(id,MODEL,1,ArtMeshTextureInput.Kind.MODEL_IMAGE,Optional.of(fault.equals("image-owner")?new ModelImageId("wrong"):image),Optional.empty()));
            if(fault.equals("image-uv")){var s=meshes.get(id);var uv=new ArrayList<>(s.geometry.uvs());uv.set(0,new Point2(.25f,.25f));s.geometry=new ArtMeshGeometry(s.geometry.positions(),uv,s.geometry.triangleIndices());}
            return new ModelImageBinding("scope",MODEL,1,image.equals(IMAGE)?RAW:new RawImageId(image.value()+"-raw"),image,Optional.of(id));
        }
        CubismModel model(){return proxy(CubismModel.class,(m,a)->switch(m.getName()) {
            case "parameters" -> proxy(Parameters.class,(method,args)->switch(method.getName()){case "all" -> parameters();case "createMany" -> {@SuppressWarnings("unchecked") var values=(List<ParameterDefinition>)args[0];definitions.addAll(values);yield parameters();}default -> unexpected(method);});
            case "parts" -> proxy(Parts.class,(method,args)->method.getName().equals("create")?proxy(Part.class,(x,y)->x.getName().equals("id")?PART:unexpected(x)):unexpected(method));
            case "deformers" -> proxy(Deformers.class,(method,args)->switch(method.getName()) {
                case "createWarp" -> warp();case "createRotation" -> ((String)args[0]).endsWith(" Z 旋转")?roll():placement();default -> unexpected(method);
            });
            case "drawables" -> proxy(Drawables.class,(method,args)->switch(method.getName()) {
                case "previewSourceTriangulation" -> {var geometry=(ArtMeshGeometry)args[0];yield new ArtMeshSourceTriangulation(geometry.positions(),
                    fault.equals("preview-missing-face")?geometry.triangleIndices().subList(3,geometry.triangleIndices().size()):geometry.triangleIndices());}
                case "find" -> drawable(meshes.get((ArtMeshId)args[0]));case "createWithSourceGeometry" -> {var id=new ArtMeshId("mesh-"+meshes.size());@SuppressWarnings("unchecked") var local=(List<Point2>)args[4];var s=new MeshState(id,(ArtMeshGeometry)args[3],local);meshes.put(id,s);yield drawable(s);}default -> unexpected(method);
            });
            case "textures" -> proxy(ModelTextures.class,(method,args)->switch(method.getName()) {
                case "textureInput" -> Optional.ofNullable(imageInputs.get((ArtMeshId)args[0]));case "registerImageAndBind" -> {((AuthorizedImageContent)args[0]).claim();registrations++;yield bind((ArtMeshId)args[1],registrations==1?IMAGE:new ModelImageId("image-"+registrations));}
                case "bindModelImage" -> {reuses++;yield bind((ArtMeshId)args[1],(ModelImageId)args[0]);}default -> unexpected(method);
            });
            case "parameterBindings" -> proxy(m.getReturnType(),(method,args)->{
                if(!method.getName().equals("bind"))return unexpected(method);
                @SuppressWarnings("unchecked") var values=(List<ParameterBindingPoint>)args[1];var target=(ParameterBindingTarget)args[0];
                if(target.type()==ParameterBindingTargetType.WARP_DEFORMER){assertEquals(ParameterBindingTarget.warpDeformer(WARP),target);axes.put((ParameterId)a[0],values.stream().map(ParameterBindingPoint::value).toList());rebuild();}
                else if(target.type()==ParameterBindingTargetType.ROTATION_DEFORMER)rollState.keys=List.copyOf(values);
                else {
                    assertEquals(ParameterBindingTargetType.ART_MESH,target.type());var mesh=meshes.get(new ArtMeshId(target.id()));
                    mesh.axes.put((ParameterId)a[0],values.stream().map(ParameterBindingPoint::value).toList());
                    if(fault.equals("layer-bound-clone"))mesh.form="native-cloned-"+mesh.id.value();
                    if(fault.equals("layer-bound-local")){var changed=new ArrayList<>(mesh.local);changed.set(0,new Point2(123,456));mesh.local=changed;}
                }
                return null;
            });
            default -> unexpected(m);
        });}
    }
}
