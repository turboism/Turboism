package dev.turboism.validation.modelimport3d.host;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** SDK stubs verify sharing and corruption refusal. No native binding or rollback proof. */
final class SourceWarpAppearanceBindingTest {
 static final DeformerId WARP=new DeformerId("warp");
 static final ModelId MODEL=new ModelId("model");
 static final List<ArtMeshId> IDS=List.of(new ArtMeshId("mesh-0"),new ArtMeshId("mesh-1"));
 static final ModelImageId IMAGE=new ModelImageId("image");
 static final RawImageId RAW=new RawImageId("raw");
 static int checks;
 public static void main(String[] args)throws Exception {
  var surfaces=GlbSurfaceReader.read(Files.readAllBytes(Path.of(args[0])));var file=Path.of(args[1]);SourceWarpFixture fixture;
  try(var input=Files.newInputStream(file)){fixture=SourceWarpFixture.read(input,Files.size(file));}
  var plan=SourceWarpAppearance.prepare(surfaces,fixture);var created=new SourceWarpAuthoring.Created(WARP,IDS);
  var normal=new State(plan,"normal");var bound=SourceWarpAppearance.bind(normal.model(),created,plan);
  check(normal.registrations==1 && normal.bindings==1 && bound.size()==2,"register once/reuse once");
  for(String fault:List.of("existing","wrong-target","new-image","mutated-default","existing-parent")) {
   var state=new State(plan,fault);
   try {SourceWarpAppearance.bind(state.model(),created,plan);throw new AssertionError("accepted "+fault);}
   catch(IllegalStateException|IllegalArgumentException expected) {checks++;}
   if(fault.equals("existing") || fault.equals("existing-parent"))check(state.registrations==0 && state.bindings==0,"preflight before mutation");
  }
  System.out.println("PASS image sharing/refusal checks="+checks+"; SDK stubs only; hostExecuted=false");
 }
 static final class State {
  final SourceWarpAppearance.Plan plan;final String fault;int registrations,bindings;
  final Map<ArtMeshId,ArtMeshTextureInput> inputs=new HashMap<>();boolean corrupted;
  State(SourceWarpAppearance.Plan p,String f) {
   plan=p;fault=f;
   if(f.equals("existing"))inputs.put(IDS.get(1),input(IDS.get(1),IMAGE));
  }
  ArtMeshTextureInput input(ArtMeshId id,ModelImageId image) {return new ArtMeshTextureInput(id,MODEL,1,ArtMeshTextureInput.Kind.MODEL_IMAGE,Optional.of(image),Optional.empty());}
  ModelImageBinding result(ArtMeshId id,ModelImageId image) {return new ModelImageBinding("scope",MODEL,1,RAW,image,Optional.of(id));}
  CubismModel model() {
   var textures=(ModelTextures)Proxy.newProxyInstance(ModelTextures.class.getClassLoader(),new Class[]{ModelTextures.class},(o,m,a)->switch(m.getName()) {
    case "textureInput" -> Optional.ofNullable(inputs.get((ArtMeshId)a[0]));
    case "registerImageAndBind" -> {
     registrations++;((AuthorizedImageContent)a[0]).claim();var id=(ArtMeshId)a[1];inputs.put(id,input(id,IMAGE));
     if(fault.equals("mutated-default"))corrupted=true;
     yield result(fault.equals("wrong-target")?IDS.get(1):id,IMAGE);
    }
    case "bindModelImage" -> {
     bindings++;var id=(ArtMeshId)a[1];var image=fault.equals("new-image")?new ModelImageId("other"):IMAGE;inputs.put(id,input(id,image));yield result(id,image);
    }
    default -> throw new AssertionError("unexpected texture call "+m.getName());
   });
   var drawables=new Drawables() {
    public List<Drawable> all(){throw new AssertionError("not used");}
    public Drawable find(ArtMeshId id) {
     int index=IDS.indexOf(id);if(index<0)throw new AssertionError("target");
     return (Drawable)Proxy.newProxyInstance(Drawable.class.getClassLoader(),new Class[]{Drawable.class},(o,m,a)->switch(m.getName()) {
      case "parentDeformerId" -> fault.equals("existing-parent") ? Optional.of(WARP) : Optional.empty();
      case "culling" -> true;
      case "authoringSnapshot" -> {
       var p=plan.inputs.get(index);var positions=corrupted?p.sourceGeometry().positions():p.defaultLocal();
       yield new ArtMeshAuthoringSnapshot(id,"source-"+index,p.sourceGeometry(),List.of(),List.of(new ArtMeshAuthoringSnapshot.Cell("default",List.of())),List.of(new ArtMeshAuthoringSnapshot.Form("default",positions,500)),List.of());
      }
      default -> throw new AssertionError("unexpected drawable call "+m.getName());
     });
    }
   };
   return (CubismModel)Proxy.newProxyInstance(CubismModel.class.getClassLoader(),new Class[]{CubismModel.class},(o,m,a)->switch(m.getName()) {
    case "drawables" -> drawables;case "textures" -> textures;
    default -> throw new AssertionError("unexpected model call "+m.getName());
   });
  }
 }
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
}
