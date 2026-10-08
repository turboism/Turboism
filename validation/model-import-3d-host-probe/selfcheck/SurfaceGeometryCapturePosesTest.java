package dev.turboism.validation.modelimport3d.host;
import java.nio.file.*;
import java.util.*;
public final class SurfaceGeometryCapturePosesTest {
    public static void main(String[] args)throws Exception {
        int checks=0;
        for(String arg:args) {
            var prepared=SurfaceImport.prepare(Files.readAllBytes(Path.of(arg)),SurfaceImportTest.request());
            var poses=SurfaceGeometryCapture.poses(prepared.geometry);
            var set=new HashSet<>(poses);
            if(set.size()!=poses.size() || !poses.equals(SurfaceGeometryCapture.poses(prepared.geometry)))throw new AssertionError("inventory nondeterminism");
            var yaw=SurfaceKeyformBake.bindingKeys(prepared.geometry.grid().yawKeys());
            var pitch=SurfaceKeyformBake.bindingKeys(prepared.geometry.grid().pitchKeys());
            for(int y=0;y<2*yaw.size()-1;y++)for(int p=0;p<2*pitch.size()-1;p++) {
                float yy=y%2==0?yaw.get(y/2):(yaw.get(y/2)+yaw.get(y/2+1))/2;
                float pp=p%2==0?pitch.get(p/2):(pitch.get(p/2)+pitch.get(p/2+1))/2;
                if(!set.contains(new SurfaceProjection.Pose(yy,pp)))throw new AssertionError("missing node/center/edge/boundary");
                checks++;
            }
            if(poses.size()!=(2*yaw.size()-1)*(2*pitch.size()-1)+512)throw new AssertionError("random inventory");
            if(poses.stream().anyMatch(p->p.yaw()< -30 || p.yaw()>30 || p.pitch()< -30 || p.pitch()>30))throw new AssertionError("domain");
            System.out.println(arg+": "+poses.size()+" deterministic poses");
        }
        System.out.println(checks+" inventory coverage checks PASS; capture not host-executed");
    }
}
