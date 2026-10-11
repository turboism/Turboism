package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Real GLB -> projection plan -> SDK consumer, with a synthetic stored-grid provider. */
public final class SurfaceImportGeometryTest {
    static final ParameterId YAW = new ParameterId("CubeYaw"), PITCH = new ParameterId("CubePitch");
    private static int checks;
    public static void main(String[] args) throws Exception {
        var surfaces = GlbSurfaceReader.read(Files.readAllBytes(Path.of(args[0])));
        var camera = new SurfaceProjection.Camera(512,512,200,new SurfaceProjection.Vertex(0,0,0));
        var plan = SurfaceImportPlan.compile(surfaces,camera,30,30,.05,2_000_000);
        var world = new World();
        var created = SurfaceImportGeometry.create(world.model(),plan,YAW,PITCH,null,"Cube");
        check(created.patches().size()==6 && world.meshes.size()==6,"six native-target meshes planned");
        check(world.bindings==12,"two complete axis bindings per face");
        for(int i=0;i<6;i++) {
            var patch=created.patches().get(i);
            var snapshot=world.meshes.get(patch.meshId());
            check(patch.source().equals(plan.patches().get(i).source()),"source correspondence survives SDK creation");
            check(snapshot.sourceGeometry().uvs().equals(patch.source().textureUvs())
                && snapshot.sourceGeometry().triangleIndices().equals(patch.source().indices()),"all UV slots/indices retained");
            check(snapshot.cells().size()==2025 && snapshot.forms().size()==2025,"full two-axis grid");
        }
        created.bake(world.model());
        check(world.batches==6 && world.edits==6*2024,"one complete non-default batch per face");
        for(var patch:created.patches())check(world.meshes.get(patch.meshId()).equals(patch.bake().expected()),"complete expected stored result");
        var insetWorld=new World();
        var inset=SurfaceImportGeometry.create(insetWorld.model(),plan,YAW,PITCH,null,"FlatCube",
            SurfaceImportGeometry.UvMode.FLAT_COLOR_INTERIOR);
        inset.bake(insetWorld.model());
        for(int i=0;i<6;i++) {
            var patch=inset.patches().get(i);var actual=insetWorld.meshes.get(patch.meshId());
            check(patch.source().equals(created.patches().get(i).source()),"original GLB UV/source identity remains intact");
            var expected=patch.source().textureUvs().stream().map(v->new Point2(.25f+.5f*v.x(),.25f+.5f*v.y())).toList();
            check(actual.sourceGeometry().uvs().equals(expected),"declared flat-color UV transform applied before binding");
            check(actual.sourceGeometry().positions().equals(expected.stream()
                .map(v->new Point2(v.x()*256,v.y()*128)).toList()),"source is unfolded in the 256x128 image pixel domain");
            check(actual.sourceGeometry().positions().stream().map(v->new Point2(v.x()/256,v.y()/128)).toList().equals(expected),
                "native identity-image UV reconstruction preserves every source UV");
            var neutral=SurfaceProjection.project(patch.source().positions(),camera,new SurfaceProjection.Pose(0,0)).positions();
            check(!actual.sourceGeometry().positions().equals(neutral),"unfolded source is distinct from neutral projected form");
            var defaultCell=actual.cells().stream().filter(c->c.coordinates().stream().allMatch(v->v.value()==0)).findFirst().orElseThrow();
            check(actual.forms().stream().filter(f->f.formGuid().equals(defaultCell.formGuid())).findFirst().orElseThrow()
                .positions().equals(neutral),"default ordinary form retains the neutral 3D projection");
            check(expected.stream().allMatch(v->v.x()>=.25f && v.x()<=.75f && v.y()>=.25f && v.y()<=.75f),
                "texture samples exclude transparent border filtering footprint");
            check(expected.stream().map(v->new Point2((v.x()-.25f)*2,(v.y()-.25f)*2)).toList().equals(patch.source().textureUvs()),
                "cube UV correspondence is exactly invertible");
            check(actual.equals(patch.bake().expected()),"inset UVs protected through complete dense bake");
        }
        var mixedWorld=new World();
        var modes=java.util.stream.IntStream.range(0,6).mapToObj(i->i%2==0
            ? SurfaceImportGeometry.UvMode.IMAGE_PIXELS : SurfaceImportGeometry.UvMode.FLAT_COLOR_INTERIOR).toList();
        var mixed=SurfaceImportGeometry.create(mixedWorld.model(),plan,YAW,PITCH,null,"MixedCube",modes);
        mixed.bake(mixedWorld.model());
        for(int i=0;i<6;i++) {
            var patch=mixed.patches().get(i);var actual=mixedWorld.meshes.get(patch.meshId());
            var expected=i%2==0 ? patch.source().textureUvs()
                : patch.source().textureUvs().stream().map(v->new Point2(.25f+.5f*v.x(),.25f+.5f*v.y())).toList();
            check(actual.sourceGeometry().uvs().equals(expected),"mixed textured/flat patches use their own image mapping");
            check(actual.sourceGeometry().positions().equals(expected.stream().map(v->new Point2(v.x()*256,v.y()*128)).toList()),
                "image source geometry matches full or interior source UV domain");
            check(actual.equals(patch.bake().expected()),"mixed source mapping survives all dense keyforms");
        }
        var invalid = new World(); invalid.maximum=29;
        reject(()->SurfaceImportGeometry.create(invalid.model(),plan,YAW,PITCH,null,"Cube"));
        check(invalid.meshes.isEmpty(),"bad domain rejected before creation");
        invalid.maximum=30;invalid.value=1;
        reject(()->SurfaceImportGeometry.create(invalid.model(),plan,YAW,PITCH,null,"Cube"));
        invalid.value=0;invalid.type=ParameterType.BLEND_SHAPE;
        reject(()->SurfaceImportGeometry.create(invalid.model(),plan,YAW,PITCH,null,"Cube"));
        invalid.type=ParameterType.NORMAL;invalid.repeat=Optional.of(true);
        reject(()->SurfaceImportGeometry.create(invalid.model(),plan,YAW,PITCH,null,"Cube"));
        invalid.repeat=Optional.empty();
        reject(()->SurfaceImportGeometry.create(invalid.model(),plan,YAW,PITCH,null,"Cube"));
        check(invalid.meshes.isEmpty(),"unsupported axis settings rejected before any creation");
        var corrupt = new World();corrupt.dropWrites=true;
        var corruptCreated=SurfaceImportGeometry.create(corrupt.model(),plan,YAW,PITCH,null,"Cube");
        reject(()->corruptCreated.bake(corrupt.model()));
        System.out.println("SurfaceImportGeometryTest: "+checks+" checks; six meshes, 12150 forms, six SDK batches; hostExecuted=false");
    }

    private static final class World {
        final Map<ArtMeshId,ArtMeshAuthoringSnapshot> meshes=new LinkedHashMap<>();
        int bindings,batches,edits;float maximum=30,value;boolean dropWrites;
        ParameterType type=ParameterType.NORMAL;
        Optional<Boolean> repeat=Optional.of(false);
        CubismModel model() {
            return proxy(CubismModel.class,(o,m,a)->switch(m.getName()) {
                case "parameters" -> proxy(Parameters.class,(po,pm,pa)-> {
                    if(!pm.getName().equals("find"))throw new AssertionError(pm);
                    return proxy(Parameter.class,(v,vm,va)->switch(vm.getName()) {
                        case "type" -> type; case "getValue" -> value; case "getDefaultValue" -> 0f;
                        case "repeat" -> repeat;
                        case "getMinimumValue" -> -30f; case "getMaximumValue" -> maximum;
                        default -> throw new AssertionError(vm);
                    });
                });
                case "drawables" -> proxy(Drawables.class,(d,dm,da)->switch(dm.getName()) {
                    case "find" -> mesh((ArtMeshId)da[0]);
                    case "create", "createWithSourceGeometry" -> {
                        ArtMeshId id=new ArtMeshId("Mesh"+meshes.size());var geometry=(ArtMeshGeometry)da[3];
                        var defaultPositions=dm.getName().equals("create") ? geometry.positions()
                            : ((List<?>)da[4]).stream().map(Point2.class::cast).toList();
                        String guid=id.value()+"-default";
                        meshes.put(id,new ArtMeshAuthoringSnapshot(id,id.value()+"-source",geometry,List.of(),
                            List.of(new ArtMeshAuthoringSnapshot.Cell(guid,List.of())),
                            List.of(new ArtMeshAuthoringSnapshot.Form(guid,defaultPositions,500)),List.of()));
                        yield mesh(id);
                    }
                    default -> throw new AssertionError(dm);
                });
                case "parameterBindings" -> Proxy.newProxyInstance(m.getReturnType().getClassLoader(),new Class<?>[]{m.getReturnType()},
                    (b,bm,ba)-> {
                        if(!bm.getName().equals("bind"))throw new AssertionError(bm);
                        bind((ParameterId)a[0],new ArtMeshId(((ParameterBindingTarget)ba[0]).id()),(List<?>)ba[1]);return null;
                    });
                default -> throw new AssertionError(m);
            });
        }
        void bind(ParameterId axis,ArtMeshId id,List<?> points) {
            bindings++;var before=meshes.get(id);var axes=new ArrayList<>(before.axes());
            axes.add(new ArtMeshAuthoringSnapshot.Axis(axis.value()+"-guid",axis,-30,0,30,
                points.stream().map(p->((ParameterBindingPoint)p).value()).toList()));
            List<List<ArtMeshAuthoringSnapshot.Coordinate>> tuples=List.of(List.of());
            for(var dimension:axes) {
                var next=new ArrayList<List<ArtMeshAuthoringSnapshot.Coordinate>>();
                for(var tuple:tuples)for(float key:dimension.keys()) {
                    var copy=new ArrayList<>(tuple);copy.add(new ArtMeshAuthoringSnapshot.Coordinate(dimension.parameterGuid(),key));next.add(copy);
                }
                tuples=next;
            }
            var cells=new ArrayList<ArtMeshAuthoringSnapshot.Cell>();var forms=new ArrayList<ArtMeshAuthoringSnapshot.Form>();
            for(var tuple:tuples) {
                String guid=id.value()+"-form-"+cells.size();cells.add(new ArtMeshAuthoringSnapshot.Cell(guid,tuple));
                forms.add(new ArtMeshAuthoringSnapshot.Form(guid,before.forms().get(0).positions(),500));
            }
            meshes.put(id,new ArtMeshAuthoringSnapshot(id,before.sourceGuid(),before.sourceGeometry(),axes,cells,forms,List.of()));
        }
        Drawable mesh(ArtMeshId id) {
            return proxy(Drawable.class,(o,m,a)->switch(m.getName()) {
                case "id" -> id;case "authoringSnapshot" -> meshes.get(id);
                case "setOrdinaryKeyformPositions" -> {
                    var before=meshes.get(id);check(before.equals(a[0]),"consumer sends exact expected snapshot");
                    var replacements=new HashMap<String,List<Point2>>();
                    for(var item:(List<?>)a[1]) {
                        var edit=(ArtMeshKeyformPositions)item;replacements.put(edit.formGuid(),edit.positions());edits++;
                    }
                    batches++;
                    if(!dropWrites)meshes.put(id,new ArtMeshAuthoringSnapshot(id,before.sourceGuid(),before.sourceGeometry(),before.axes(),
                        before.cells(),before.forms().stream().map(f->new ArtMeshAuthoringSnapshot.Form(f.formGuid(),
                            replacements.getOrDefault(f.formGuid(),f.positions()),f.drawOrder())).toList(),before.morphKeys()));
                    yield null;
                }
                default -> throw new AssertionError(m);
            });
        }
    }
    private static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void reject(Runnable work){try{work.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("invalid input/result accepted");}
}
