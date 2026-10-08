package dev.turboism.validation.modelimport3d.host;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Packs the exact original apple; candidate only, never an import admission. */
public final class AppleFixedIndexProbe {
    private AppleFixedIndexProbe() { }
    public static void main(String[] args)throws Exception {
        byte[] raw=Files.readAllBytes(Path.of(args[0]));
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        if(!sha.equals("54331e29af9a3ca07f11017712ce9156208a8ae8538b0a80b78e8c5eb8a894e0"))throw new IllegalArgumentException("source changed");
        var surfaces=GlbSurfaceReader.read(raw);var plan=SurfaceFixedIndexPlan.compile(surfaces,new SurfaceFixedIndexPlan.Budget(65535,1024,200000,600000));
        int[] offsets=new int[surfaces.size()];int count=0;
        for(int i=0;i<surfaces.size();i++){offsets[i]=count;count+=surfaces.get(i).indices().size()/3;}
        var sequence=ByteBuffer.allocate(count*4).order(ByteOrder.LITTLE_ENDIAN);var seen=new java.util.BitSet(count);
        for(var patch:plan.patches())for(var triangle:patch.triangles()) {
            int id=offsets[triangle.surface()]+triangle.triangle();
            if(seen.get(id))throw new IllegalStateException("source triangle duplicated");seen.set(id);sequence.putInt(id);
            // Independently compare packed slots/UV/index corners to the source surface.
        }
        for(var patch:plan.patches())for(int i=0;i<patch.triangles().size();i++) {
            var triangle=patch.triangles().get(i);var source=surfaces.get(triangle.surface());
            for(int c=0;c<3;c++) {
                int local=patch.indices().get(3*i+c),sourceSlot=source.indices().get(3*triangle.triangle()+c);
                if(!patch.slots().get(local).equals(new SurfaceFixedIndexPlan.Slot(triangle.surface(),sourceSlot))
                    || !patch.positions().get(local).equals(source.positions().get(sourceSlot))
                    || !patch.uvs().get(local).equals(source.textureUvs().get(sourceSlot)))throw new IllegalStateException("source corner changed");
            }
        }
        if(seen.cardinality()!=count || sequence.hasRemaining())throw new IllegalStateException("source triangle missing");
        Files.write(Path.of(args[1]),sequence.array());
        var camera=SurfaceAutoCamera.fit(surfaces,512,512,400);
        double radius=surfaces.stream().flatMap(s->s.positions().stream()).mapToDouble(v->Math.hypot(Math.hypot(v.x()-camera.pivot().x(),v.y()-camera.pivot().y()),v.z()-camera.pivot().z())).max().orElseThrow();
        var grid=SurfaceKeyPlan.linear(surfaces.stream().flatMap(s->s.positions().stream()).toList(),camera,30,30,.05,4096);
        long keys=(long)grid.yawKeys().size()*grid.pitchKeys().size();
        System.out.println("{\"status\":\"EXACT_SOURCE_FIXED_INDEX_CANDIDATE_NOT_IMPORTED\",\"sourceTriangles\":"+count
            +",\"patches\":"+plan.patches().size()+",\"expandedSlots\":"+plan.expandedSlots()+",\"indices\":"+plan.indices()
            +",\"jointKeys\":"+keys+",\"keyforms\":"+(keys*plan.patches().size())
            +",\"projectedVertices\":"+(keys*plan.expandedSlots())+",\"minimumCoordinateBytes\":"+(8*keys*plan.expandedSlots())
            +",\"frameScale\":"+camera.scale()+",\"frameCenter\":["+camera.centerX()+","+camera.centerY()+"]"
            +",\"framePivot\":["+camera.pivot().x()+","+camera.pivot().y()+","+camera.pivot().z()+"],\"framedRadiusPixels\":"+(radius*camera.scale())+"}");
    }
}
