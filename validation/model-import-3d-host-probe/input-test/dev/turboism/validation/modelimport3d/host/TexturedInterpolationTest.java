package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.*;
import java.nio.file.*;
import java.util.*;

/** Checks actual synthetic import readback, not native Cubism interpolation. */
public final class TexturedInterpolationTest {
    record Pose(float yaw,float pitch) {}
    static long vertices;
    static double maximum,coarseMaximum;
    public static void main(String[] args)throws Exception {
        var request=SurfaceImportTest.request();
        var prepared=SurfaceImport.prepare(Files.readAllBytes(Path.of(args[0])),request);
        var world=new SurfaceImportTest.World();
        var imported=SurfaceImport.apply(prepared,world::model,world.transactions()).value().orElseThrow();
        var poses=new LinkedHashSet<Pose>();
        for(var patch:imported.geometry().patches()) {
            var snapshot=world.model().drawables().find(patch.meshId()).authoringSnapshot();
            var yaw=snapshot.axes().stream().filter(a->a.parameterId().equals(request.yaw())).findFirst().orElseThrow();
            var pitch=snapshot.axes().stream().filter(a->a.parameterId().equals(request.pitch())).findFirst().orElseThrow();
            if(yaw.keys().size()<=3 || pitch.keys().size()<=3)throw new AssertionError("dense joint grid required");
            var forms=new HashMap<String,List<Point2>>();
            snapshot.forms().forEach(f->forms.put(f.formGuid(),f.positions()));
            var stored=new HashMap<Pose,List<Point2>>();
            for(var cell:snapshot.cells()) {
                float y=cell.coordinates().stream().filter(c->c.parameterGuid().equals(yaw.parameterGuid())).findFirst().orElseThrow().value();
                float p=cell.coordinates().stream().filter(c->c.parameterGuid().equals(pitch.parameterGuid())).findFirst().orElseThrow().value();
                if(stored.put(new Pose(y,p),forms.get(cell.formGuid()))!=null)throw new AssertionError("duplicate tuple");
            }
            poses.clear();
            for(float y:subdivide(yaw.keys()))for(float p:subdivide(pitch.keys()))poses.add(new Pose(y,p));
            var random=new Random(0x3344494dL);
            for(int i=0;i<512;i++)poses.add(new Pose(-30+60*random.nextFloat(),-30+60*random.nextFloat()));
            for(var pose:poses) {
                var actual=interpolate(stored,yaw.keys(),pitch.keys(),pose);
                for(int i=0;i<actual.size();i++) {
                    var source=patch.source().positions().get(i);
                    double[] expected=project(source,request.camera(),pose);
                    maximum=Math.max(maximum,Math.hypot(actual.get(i)[0]-expected[0],actual.get(i)[1]-expected[1]));
                    vertices++;
                }
            }
            // Same stored source-derived forms, deliberately reduced to the familiar three keys.
            var coarse=List.of(-30f,0f,30f);
            for(float y:new float[]{-15,15})for(float p:new float[]{-15,15}) {
                var pose=new Pose(y,p);var actual=interpolate(stored,coarse,coarse,pose);
                for(int i=0;i<actual.size();i++) {
                    double[] expected=project(patch.source().positions().get(i),request.camera(),pose);
                    coarseMaximum=Math.max(coarseMaximum,Math.hypot(actual.get(i)[0]-expected[0],actual.get(i)[1]-expected[1]));
                }
            }
        }
        if(maximum>.1)throw new AssertionError("dense readback exceeds 0.1 canvas px: "+maximum);
        if(coarseMaximum<=.1)throw new AssertionError("three-key negative control did not expose curvature");
        System.out.println(NativeReport.json(Map.of("status","PASS","hostExecuted",false,
            "scope","SYNTHETIC_TEXTURED_IMPORT_STORED_LINEAR_INTERPOLATION",
            "inputSha256",prepared.inputSha256,"samplesPerPatch",poses.size(),"vertexComparisons",vertices,
            "maxErrorPixels",maximum,"threeKeyMaxErrorPixels",coarseMaximum,"tolerancePixels",.1)));
    }
    static List<Float> subdivide(List<Float> keys) {
        var values=new ArrayList<Float>();
        for(int i=0;i<keys.size();i++) {
            values.add(keys.get(i));
            if(i+1<keys.size())values.add((keys.get(i)+keys.get(i+1))/2);
        }
        return values;
    }
    static int lower(List<Float> keys,float value) {
        for(int i=0;i+1<keys.size();i++)if(value<=keys.get(i+1))return i;
        throw new AssertionError("outside grid");
    }
    static List<double[]> interpolate(Map<Pose,List<Point2>> stored,List<Float> ys,List<Float> ps,Pose pose) {
        int yi=lower(ys,pose.yaw()),pi=lower(ps,pose.pitch());
        float y0=ys.get(yi),y1=ys.get(yi+1),p0=ps.get(pi),p1=ps.get(pi+1);
        double u=((double)pose.yaw()-y0)/(y1-y0),v=((double)pose.pitch()-p0)/(p1-p0);
        var a=stored.get(new Pose(y0,p0));var b=stored.get(new Pose(y1,p0));
        var c=stored.get(new Pose(y0,p1));var d=stored.get(new Pose(y1,p1));
        var result=new ArrayList<double[]>();
        for(int i=0;i<a.size();i++)result.add(new double[]{
            (1-v)*((1-u)*a.get(i).x()+u*b.get(i).x())+v*((1-u)*c.get(i).x()+u*d.get(i).x()),
            (1-v)*((1-u)*a.get(i).y()+u*b.get(i).y())+v*((1-u)*c.get(i).y()+u*d.get(i).y())});
        return result;
    }
    /** Independent double-precision rotation matrix; does not call the production projector. */
    static double[] project(SurfaceProjection.Vertex source,SurfaceProjection.Camera camera,Pose pose) {
        double a=Math.toRadians(pose.yaw()),b=Math.toRadians(pose.pitch());
        double x=source.x()-camera.pivot().x(),y=source.y()-camera.pivot().y(),z=source.z()-camera.pivot().z();
        double rx=Math.cos(a)*x+Math.sin(a)*Math.sin(b)*y+Math.sin(a)*Math.cos(b)*z;
        double ry=Math.cos(b)*y-Math.sin(b)*z;
        return new double[]{camera.centerX()+camera.scale()*(rx+camera.pivot().x()),
            camera.centerY()-camera.scale()*(ry+camera.pivot().y())};
    }
}
