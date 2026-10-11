package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

/** SDK stubs prove provenance protection rejects corruption; this is not native assembly evidence. */
final class SourceWarpAuthoringTest {
    static int checks;
    static final ArtMeshId ID=new ArtMeshId("mesh");
    static final DeformerId WARP=new DeformerId("warp");
    public static void main(String[] args) {
        var points=List.of(new Point2(0,0),new Point2(1,0),new Point2(0,1));
        var geometry=new ArtMeshGeometry(points,points,List.of(0,1,2));
        var expected=List.of(geometry);var created=new SourceWarpAuthoring.Created(WARP,List.of(ID));
        var good=snapshot(geometry,points);
        SourceWarpAuthoring.verifyMeshes(model(good,Optional.of(WARP),true),created,expected);checks++;
        reject(()->SourceWarpAuthoring.verifyMeshes(model(good,Optional.empty(),true),created,expected));
        reject(()->SourceWarpAuthoring.verifyMeshes(model(good,Optional.of(WARP),false),created,expected));
        var changed=List.of(new Point2(.1f,0),points.get(1),points.get(2));
        reject(()->SourceWarpAuthoring.verifyMeshes(model(snapshot(geometry,changed),Optional.of(WARP),true),created,expected));
        var wrongUV=new ArtMeshGeometry(points,changed,geometry.triangleIndices());
        reject(()->SourceWarpAuthoring.verifyMeshes(model(snapshot(wrongUV,points),Optional.of(WARP),true),created,expected));
        var wrongOrder=new ArtMeshGeometry(points,points,List.of(1,0,2));
        reject(()->SourceWarpAuthoring.verifyMeshes(model(snapshot(wrongOrder,points),Optional.of(WARP),true),created,expected));
        reject(()->SourceWarpAuthoring.verifyMeshes(model(good,Optional.of(WARP),true),new SourceWarpAuthoring.Created(WARP,List.of()),expected));
        System.out.println("PASS SourceWarpAuthoring provenance checks="+checks+"; SDK stubs only; hostExecuted=false");
    }
    static ArtMeshAuthoringSnapshot snapshot(ArtMeshGeometry geometry,List<Point2> positions) {
        return new ArtMeshAuthoringSnapshot(ID,"source",geometry,List.of(),
            List.of(new ArtMeshAuthoringSnapshot.Cell("default",List.of())),
            List.of(new ArtMeshAuthoringSnapshot.Form("default",positions,500)),List.of());
    }
    static CubismModel model(ArtMeshAuthoringSnapshot s,Optional<DeformerId> parent,boolean culling) {
        var drawable=(Drawable)Proxy.newProxyInstance(Drawable.class.getClassLoader(),new Class[]{Drawable.class},(o,m,a)->switch(m.getName()) {
            case "authoringSnapshot" -> s;
            case "parentDeformerId" -> parent;
            case "culling" -> culling;
            default -> throw new AssertionError("unexpected drawable call "+m.getName());
        });
        var drawables=new Drawables() {
            public List<Drawable> all() { return List.of(drawable); }
            public Drawable find(ArtMeshId id) { if(!id.equals(ID))throw new AssertionError("identity");return drawable; }
        };
        return (CubismModel)Proxy.newProxyInstance(CubismModel.class.getClassLoader(),new Class[]{CubismModel.class},(o,m,a)-> {
            if(m.getName().equals("drawables"))return drawables;
            throw new AssertionError("unexpected model call "+m.getName());
        });
    }
    static void reject(Runnable action) {
        try { action.run(); }catch(IllegalStateException expected) { checks++;return; }
        throw new AssertionError("corrupted source provenance accepted");
    }
}
