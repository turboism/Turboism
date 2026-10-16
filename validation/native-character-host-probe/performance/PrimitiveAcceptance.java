package dev.turboism.validation.modelimport3d.host;

import dev.turboism.plugin.modelimport3d.viewer.SoftwareModelRenderer;
import dev.turboism.plugin.modelimport3d.viewer.GlbViewerLoader;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Public primitive corpus baseline. Geometry diagnostic only; native RGBA gates remain separate. */
public final class PrimitiveAcceptance {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected fixture directory and output directory");
        Path inputs = Path.of(args[0]), outputs = Path.of(args[1]);
        Files.createDirectories(outputs);
        var report = new StringBuilder("fixture,yaw,pitch,partitions,triangles,keyformSlots,wrong,missing,maxDepthGap,geometryGate\n");
        int failed = 0, samples = 0;
        for (String name : List.of("cube", "octahedron", "cross-star", "torus", "cylinder", "ring-and-column")) {
            byte[] bytes = Files.readAllBytes(inputs.resolve(name + ".glb"));
            var request = new SurfaceImport.Request(name, new ParameterId("Yaw"), new ParameterId("Pitch"),
                    new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
                    180, 180, SurfaceImportPlan.MAX_PROJECTED_VERTICES, Optional.of(new ParameterId("Roll")));
            long started = System.nanoTime();
            // Exercise the production material/control fitting chain, not geometry-only preparation.
            var prepared = NativeModelImport.prepare(bytes, request, () -> false);
            var scene = prepared.materials().scene();
            int expectedTriangles = Map.of("cube",12L,"octahedron",8L,"cross-star",60L,
                    "torus",768L,"cylinder",124L,"ring-and-column",892L).get(name).intValue();
            if (!GlbViewerLoader.requiresNativeRigImport(bytes)
                    || prepared.geometry().totalTriangles() != expectedTriangles
                    || scene.controls().size() != (name.equals("ring-and-column") ? 2 : 1)
                    || scene.textures().size() != 1)
                throw new AssertionError("Lost topology, standard morphs, texture or native routing: " + name);
            var plan = prepared.geometry();
            System.out.printf(Locale.ROOT, "%s prepareMs=%.3f partitions=%d triangles=%d sha256=%s%n", name,
                    (System.nanoTime() - started) / 1e6, plan.partitions().size(), plan.totalTriangles(),
                    HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            Path folder = outputs.resolve(name); Files.createDirectories(folder);
            var vertices=new StringBuilder("partition,slot,x,y,z,u,v\n");
            var triangles=new StringBuilder("partition,a,b,c,doubleSided\n");
            for(int index=0;index<plan.partitions().size();index++) {
                var partition=plan.partitions().get(index);
                for(int slot=0;slot<partition.positions().size();slot++) {
                    var xyz=partition.positions().get(slot);var uv=partition.uvs().get(slot);
                    vertices.append(index).append(',').append(slot).append(',').append(xyz.x()).append(',').append(xyz.y()).append(',').append(xyz.z()).append(',').append(uv.x()).append(',').append(uv.y()).append('\n');
                }
                var indices=partition.indices();
                for(int i=0;i<indices.size();i+=3) triangles.append(index).append(',').append(indices.get(i)).append(',').append(indices.get(i+1)).append(',').append(indices.get(i+2)).append(',').append(partition.doubleSided()).append('\n');
            }
            Files.writeString(folder.resolve("source-plan.vertices.csv"),vertices);
            Files.writeString(folder.resolve("source-plan.triangles.csv"),triangles);
            var renderer = new SoftwareModelRenderer();
            ImageIO.write(renderer.render(scene, 512, 512, Math.toRadians(30), Math.toRadians(25), .7),
                    "PNG", folder.resolve("source-textured.png").toFile());
            var poses = new ArrayList<SurfaceProjection.Pose>();
            for (double yaw : new double[] {-180,-150,-90,-30,0,30,90,150,180}) poses.add(new SurfaceProjection.Pose(yaw,0));
            for (double pitch : new double[] {-180,-90,-30,30,90,180}) poses.add(new SurfaceProjection.Pose(0,pitch));
            for (double yaw : new double[] {-30,30})
                for (double pitch : new double[] {-30,30}) poses.add(new SurfaceProjection.Pose(yaw,pitch));
            for(double yaw : new double[] {-165,-135,-45,-15,15,45,135,165})
                for(double pitch : new double[] {-45,-15,0,15,45}) poses.add(new SurfaceProjection.Pose(yaw,pitch));
            // Held-out angles do not occur on the compiler's 15-degree constraint grid.
            for(double yaw : new double[] {-157.49,-22.49,3.75,7.49,22.49,157.49})
                for(double pitch : new double[] {-22.49,3.75,7.49,22.49}) poses.add(new SurfaceProjection.Pose(yaw,pitch));
            for (var pose : poses) {
                double[] values = new double[plan.partitions().size()];
                int y0 = Math.min(3, (int)Math.floor((pose.yaw()+180)/90));
                int p0 = Math.min(3, (int)Math.floor((pose.pitch()+180)/90));
                double fy=(pose.yaw()-NativeCharacterPlan.KEYS.get(y0))/90;
                double fp=(pose.pitch()-NativeCharacterPlan.KEYS.get(p0))/90;
                var order = new ArrayList<Integer>();
                for (int i=0;i<values.length;i++) {
                    order.add(i);
                    for(int y=0;y<2;y++) for(int p=0;p<2;p++)
                        values[i] += plan.orders().get((y0+y)*5+p0+p).get(i)
                                * (y==0?1-fy:fy) * (p==0?1-fp:fp);
                }
                order.sort(Comparator.<Integer>comparingDouble(i -> values[i]).thenComparingInt(i -> i));
                var m = NativeOcclusionDiagnostic.measure(scene, plan, pose, order,
                        folder.resolve("yaw"+pose.yaw()+"-pitch"+pose.pitch()+".png"), false);
                boolean pass = m.visible()>0 && m.wrong()==0 && m.missing()==0;
                if(!pass) failed++; samples++;
                report.append(String.format(Locale.ROOT,"%s,%.4f,%.4f,%d,%d,%d,%d,%d,%.9g,%s%n",name,
                        pose.yaw(),pose.pitch(),plan.partitions().size(),plan.totalTriangles(),plan.totalKeyformSlots(),
                        m.wrong(),m.missing(),m.maxGap(),pass?"PASS":"FAIL"));
            }
        }
        Files.writeString(outputs.resolve("geometry-results.csv"),report);
        Files.writeString(outputs.resolve("scope.txt"),
                "Production material/control-fitted preparation; saved defaults only. Exact projected source geometry; "
                + "continuous-double order interpolation and partition-index ties. Not native interpolated geometry or "
                + "staged integer/tie rendering. Zero missing/wrong-depth pixel screen with depth epsilon 1e-5. "
                + "Native textured RGBA, control combinations, Undo/Redo and reopen require separate host gates.\n");
        System.out.printf("GEOMETRY_SCREEN=%s samples=%d failed=%d%n",failed==0?"PASS":"FAIL",samples,failed);
        if (failed>0) System.exit(1);
    }
}
