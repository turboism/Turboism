package dev.turboism.validation.modelimport3d.host;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Independent compact fixture and bounded parser rejection checks; does not start Cubism. */
final class SourceWarpFixtureTest {
    static int checks;
    public static void main(String[] args) throws Exception {
        byte[] data=fixture();var f=SourceWarpFixture.read(new ByteArrayInputStream(data),data.length);
        check(f.poseCount()==289 && f.columns==1 && f.rows==1 && f.meshes.size()==1,"header");
        check(f.meshes.get(0).geometry().positions().size()==3 && f.meshes.get(0).geometry().triangleIndices().equals(java.util.List.of(0,1,2)),"geometry");
        check(f.pose(144).yaw()==0 && f.pose(144).pitch()==0 && f.controls(0).get(0).x()==512,"control projection");
        reject(data,data.length-1);
        var truncated=java.util.Arrays.copyOf(data,data.length-1);reject(truncated,data.length);
        var extra=java.util.Arrays.copyOf(data,data.length+1);reject(extra,data.length);
        mutant(data,0,0);
        mutant(data,8,513);
        mutant(data,12,512); // Grid exceeds sample budget, before allocation.
        mutant(data,20,290);
        mutant(data,24,65536);
        mutant(data,32,0x7fc00000); // Non-finite original XYZ.
        mutant(data,92,Float.floatToIntBits(1.1f)); // Warp local x is outside [0,1].
        mutant(data,116,3); // Vertex index equal to slot count.
        mutant(data,188,1); // Source triangle permutation out of range.
        mutant(data,192,Float.floatToIntBits(-29)); // First yaw key.
        if(args.length==1) {
            Path file=Path.of(args[0]);var actual=SourceWarpFixture.read(Files.newInputStream(file),Files.size(file));
            check(actual.columns==238 && actual.rows==119 && actual.meshes.size()==2,"actual candidate shape");
            check(actual.meshes.stream().mapToInt(m->m.indices.length/3).sum()==150784,"actual original triangle count");
            check(actual.meshes.stream().mapToInt(m->m.sourceVertex.length).sum()==101090,"actual derived source slots");
            check(actual.controls(144).size()==28680,"actual neutral controls");
            var hash=java.security.MessageDigest.getInstance("SHA-256");
            for(var m:actual.meshes) {
                for(float x:m.source3D) hash.update(java.nio.ByteBuffer.allocate(4).putFloat(x).array());
                for(float x:m.sourceUv) hash.update(java.nio.ByteBuffer.allocate(4).putFloat(x).array());
                for(int x:m.indices) hash.update(java.nio.ByteBuffer.allocate(4).putInt(x).array());
            }
            System.out.println("sourceGeometrySha256="+java.util.HexFormat.of().formatHex(hash.digest()));
        }
        System.out.println("PASS SourceWarpFixture checks="+checks+"; hostExecuted=false");
    }
    static byte[] fixture() throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeBytes("MI3DAW01");for(int x:new int[]{1,1,1,289,3,1})out.writeInt(x);
        for(float x:new float[]{0,0,.35f,.3f,.1f,-.1f,-.3f,.1f,-.1f})out.writeFloat(x);
        for(float x:new float[]{0,0,1,0,0,1,0,0,1,0,0,1})out.writeFloat(x);
        for(int x:new int[]{0,1,2,0,1,2,0,1,2,0,0,0,0,1,0,0,2,0,0})out.writeInt(x);
        for(int y=0;y<17;y++)for(int p=0;p<17;p++){out.writeFloat(-30+3.75f*y);out.writeFloat(-30+3.75f*p);}
        for(int i=0;i<289*4;i++){out.writeFloat(512);out.writeFloat(512);}
        return bytes.toByteArray();
    }
    static void mutant(byte[] original,int offset,int bits) throws IOException {
        var data=original.clone();java.nio.ByteBuffer.wrap(data).putInt(offset,bits);reject(data,data.length);
    }
    static void reject(byte[] data,long length) throws IOException {
        try { SourceWarpFixture.read(new ByteArrayInputStream(data),length); }
        catch(IOException expected) { checks++;return; }
        throw new AssertionError("bad fixture accepted");
    }
    static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why);checks++; }
}
