package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

/** Actual cube + shared image binding through a synthetic SDK; no native rendering claims. */
public final class SurfaceImportAppearanceTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var surfaces=GlbSurfaceReader.read(Files.readAllBytes(Path.of(args[0])));
        var plan=SurfaceImportPlan.compile(surfaces,CubeGeometryProbe.CAMERA,30,30,.05,2_000_000);
        var colors=SurfaceBaseColorPlan.compile(surfaces,plan);
        check(colors.patches().size()==6 && colors.materials().size()==1,"cube has one shared base color");
        check(colors.materials().get(0).argb()==0xffe7e7e7,"linear cube .8 becomes sRGB 231");
        var faceColors=SurfaceBaseColorPlan.compile(surfaces,plan,SurfaceBaseColorPlan.Appearance.FACE_COLORS);
        check(faceColors.materials().size()==6,"six distinct face materials");
        check(faceColors.patches().stream().map(p->p.material().argb()).toList().equals(
            List.of(0xffdc3232,0xff32c850,0xff325adc,0xffdcb432,0xffbe3cc8,0xff32c8c8)),"stable face palette");
        var coloredWorld=new World();var coloredGeometry=coloredWorld.create(plan);
        var coloredBefore=Map.copyOf(coloredWorld.geometry.meshes);
        var coloredBindings=SurfaceImportAppearance.bind(coloredWorld.model(),coloredGeometry,faceColors);
        check(coloredBindings.size()==6 && coloredWorld.registrations==6 && coloredWorld.reuses==0,
            "each face binds its own image even when source material is shared");
        check(coloredBefore.equals(coloredWorld.geometry.meshes),"face colors preserve geometry and dense bindings");
        for(int i=0;i<6;i++) {
            var face=faceColors.patches().get(i);
            check(face.source().equals(colors.patches().get(i).source()) && face.sourceMaterial()==0,
                "face identity and source provenance preserved");
            var faceImage=ImageIO.read(new ByteArrayInputStream(face.material().png()));
            check(faceImage.getRGB(128,64)==face.material().argb(),"encoded face color matches binding");
        }
        check(SurfaceBaseColorPlan.compile(surfaces,plan).materials().get(0).argb()==0xffe7e7e7,
            "explicit face colors do not change source default");
        var png=colors.materials().get(0).png();var image=ImageIO.read(new ByteArrayInputStream(png));
        check(image.getWidth()==256 && image.getHeight()==128,"bounded native image dimensions");
        boolean solid=true;for(int y=0;y<128;y++)for(int x=0;x<256;x++)solid&=image.getRGB(x,y)==0xffe7e7e7;
        check(solid,"all material texels exactly match quantized base color");
        png[0]=0;check(colors.materials().get(0).png()[0]==(byte)137,"PNG ownership is defensive");
        check(SurfaceBaseColorPlan.argb(List.of(0d,.5d,1d,1d))==0xff00bcff,"independent sRGB reference points");
        check((SurfaceBaseColorPlan.argb(List.of(.0031308,.0031308,.0031308,1d))&255)==10,"sRGB branch threshold");
        reject(()->SurfaceBaseColorPlan.argb(List.of(.8,.8,.8,.5)));
        reject(()->SurfaceBaseColorPlan.argb(List.of(Double.NaN,.8,.8,1d)));
        reject(()->SurfaceBaseColorPlan.argb(List.of(-.1,.8,.8,1d)));
        var missing=new SurfaceImportPlan.Plan(plan.camera(),plan.grid(),plan.keys(),plan.neutralKey(),
            plan.patches().subList(1,6),plan.projectedVertices());
        reject(()->SurfaceBaseColorPlan.compile(surfaces,missing));
        var world=new World();var created=world.create(plan);
        var before=Map.copyOf(world.geometry.meshes);
        var bindings=SurfaceImportAppearance.bind(world.model(),created,colors);
        check(bindings.size()==6 && world.registrations==1 && world.reuses==5,"one registration and five shared-image binds");
        check(world.geometry.meshes.equals(before),"appearance preserved every source/UV/index/form");
        check(bindings.stream().allMatch(b->b.binding().modelImageId().equals(bindings.get(0).binding().modelImageId())
            && b.argb()==0xffe7e7e7 && b.sourceSurface()==0 && b.sourceMaterial()==0),"source material mapping preserved");
        reject(()->SurfaceImportAppearance.bind(world.model(),created,colors));
        check(world.registrations==1 && world.reuses==5,"existing texture input rejected before new writes");
        var occupied=new World();var occupiedCreated=occupied.create(plan);
        occupied.bound.put(occupiedCreated.patches().get(5).meshId(),new ModelImageId("prior-image"));
        reject(()->SurfaceImportAppearance.bind(occupied.model(),occupiedCreated,colors));
        check(occupied.registrations==0 && occupied.reuses==0,"last occupied target rejected before first registration");
        var dropped=new World();var droppedCreated=dropped.create(plan);dropped.dropLast=true;
        reject(()->SurfaceImportAppearance.bind(dropped.model(),droppedCreated,colors));
        var wrong=new World();var wrongCreated=wrong.create(plan);wrong.wrongGeneration=true;
        reject(()->SurfaceImportAppearance.bind(wrong.model(),wrongCreated,colors));
        System.out.println("SurfaceImportAppearanceTest: "+checks+" checks PASS; one shared image / six faces; hostExecuted=false");
    }
    static final class World {
        final JointPoseProducerIntegrationTest.World geometry=new JointPoseProducerIntegrationTest.World(false);
        final Map<ArtMeshId,ModelImageId> bound=new HashMap<>();
        int registrations,reuses;boolean dropLast,wrongGeneration;
        SurfaceImportGeometry.Created create(SurfaceImportPlan.Plan plan) {
            geometry.model().parameters().createMany(List.of(
                new ParameterDefinition(CubeGeometryProbe.YAW,"Yaw",-30,0,30,ParameterType.NORMAL,false),
                new ParameterDefinition(CubeGeometryProbe.PITCH,"Pitch",-30,0,30,ParameterType.NORMAL,false)));
            var created=SurfaceImportGeometry.create(geometry.model(),plan,CubeGeometryProbe.YAW,CubeGeometryProbe.PITCH,null,"Cube");
            created.bake(geometry.model());return created;
        }
        CubismModel model() {
            var base=geometry.model();
            return (CubismModel)Proxy.newProxyInstance(CubismModel.class.getClassLoader(),new Class<?>[]{CubismModel.class},(o,m,a)->{
                if(!m.getName().equals("textures"))return m.invoke(base,a);
                return Proxy.newProxyInstance(ModelTextures.class.getClassLoader(),new Class<?>[]{ModelTextures.class},(t,tm,ta)->{
                    if(tm.getName().equals("textureInput")) {
                        var mesh=(ArtMeshId)ta[0];var image=bound.get(mesh);
                        return image==null?Optional.empty():Optional.of(new ArtMeshTextureInput(mesh,new ModelId("model"),1,
                            ArtMeshTextureInput.Kind.MODEL_IMAGE,Optional.of(image),Optional.empty()));
                    }
                    ArtMeshId mesh=(ArtMeshId)ta[1];ModelImageId image;
                    if(tm.getName().equals("registerImageAndBind")) {
                        ((AuthorizedImageContent)ta[0]).claim();registrations++;image=new ModelImageId("image"+registrations);
                    } else if(tm.getName().equals("bindModelImage")) { reuses++;image=(ModelImageId)ta[0]; }
                    else throw new AssertionError(tm);
                    if(!dropLast || reuses!=5)bound.put(mesh,image);
                    return new ModelImageBinding("scope",new ModelId("model"),wrongGeneration && reuses>0?2:1,
                        new RawImageId("raw"),image,Optional.of(mesh));
                });
            });
        }
    }
    private interface Action { void run() throws Exception; }
    private static void reject(Action action) throws Exception {
        try {action.run();} catch(RuntimeException expected) {checks++;return;}
        throw new AssertionError("unsafe material input/result accepted");
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);checks++;}
}
