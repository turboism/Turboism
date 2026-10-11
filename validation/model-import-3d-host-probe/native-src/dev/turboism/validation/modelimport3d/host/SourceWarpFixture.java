package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded validation fixture retaining original source coordinates separately from Warp inputs.
 * Reading this candidate does not establish production import or visibility admission. */
final class SourceWarpFixture {
    private static final byte[] MAGIC = {'M','I','3','D','A','W','0','1'};
    private static final int MAX_VERTICES = 131070, MAX_TRIANGLES = 200000;
    final int columns, rows;
    final List<Mesh> meshes;
    private final float[] poses, controls;
    private SourceWarpFixture(int columns,int rows,List<Mesh> meshes,float[] poses,float[] controls) {
        this.columns=columns;this.rows=rows;this.meshes=List.copyOf(meshes);this.poses=poses;this.controls=controls;
    }
    static final class Mesh {
        final float[] source3D, sourceUv, local;
        final int[] indices, sourceVertex, sourceUvIdentity, slotIdentity, sourceTriangle;
        Mesh(float[] xyz,float[] uv,float[] local,int[] indices,int[] vertex,int[] uvIdentity,int[] slots,int[] triangles) {
            this.source3D=xyz;this.sourceUv=uv;this.local=local;this.indices=indices;
            this.sourceVertex=vertex;this.sourceUvIdentity=uvIdentity;this.slotIdentity=slots;this.sourceTriangle=triangles;
        }
        ArtMeshGeometry geometry() {
            return new ArtMeshGeometry(points(local,0,local.length/2),points(sourceUv,0,sourceUv.length/2),
                Arrays.stream(indices).boxed().toList());
        }
    }
    int poseCount() { return poses.length/2; }
    List<Point2> controls(int pose) {
        if(pose<0 || pose>=poseCount())throw new IndexOutOfBoundsException(pose);
        int count=(columns+1)*(rows+1);
        return points(controls,Math.multiplyExact(pose,count*2),count);
    }
    SurfaceWarpKeyformBake.Pose pose(int index) {
        if(index<0 || index>=poseCount())throw new IndexOutOfBoundsException(index);
        return new SurfaceWarpKeyformBake.Pose(poses[index*2],poses[index*2+1]);
    }
    private static List<Point2> points(float[] data,int offset,int size) {
        return new AbstractList<>() {
            @Override public int size() { return size; }
            @Override public Point2 get(int index) {
                if(index<0 || index>=size)throw new IndexOutOfBoundsException(index);
                return new Point2(data[offset+index*2],data[offset+index*2+1]);
            }
        };
    }
    static SourceWarpFixture read(InputStream input,long exactBytes) throws IOException {
        // Every advertised allocation is checked before reading the first array.
        if(exactBytes<40 || exactBytes>90_000_000)throw new IOException("fixture byte budget");
        var in=new DataInputStream(new BufferedInputStream(input));
        if(!Arrays.equals(in.readNBytes(8),MAGIC))throw new IOException("fixture version");
        int columns=in.readInt(),rows=in.readInt(),meshCount=in.readInt(),poseCount=in.readInt();
        if(columns<1 || columns>512 || rows<1 || rows>512 || meshCount<1 || meshCount>2 || poseCount!=289)
            throw new IOException("fixture header bounds");
        long controlCount=((long)columns+1)*(rows+1);
        if(controlCount*poseCount>SurfaceImportPlan.MAX_PROJECTED_VERTICES)throw new IOException("control sample budget");
        int[] vertices=new int[meshCount],triangles=new int[meshCount];long byteCount=24+8L*meshCount;
        long totalVertices=0,totalTriangles=0;
        for(int i=0;i<meshCount;i++) {
            vertices[i]=in.readInt();triangles[i]=in.readInt();
            if(vertices[i]<3 || vertices[i]>65535 || triangles[i]<1 || triangles[i]>MAX_TRIANGLES)
                throw new IOException("mesh allocation bounds");
            totalVertices+=vertices[i];totalTriangles+=triangles[i];
            byteCount+=48L*vertices[i]+16L*triangles[i];
        }
        byteCount+=8L*poseCount+8L*poseCount*controlCount;
        if(totalVertices>MAX_VERTICES || totalTriangles>MAX_TRIANGLES || byteCount!=exactBytes)
            throw new IOException("fixture counts/length");
        var meshes=new ArrayList<Mesh>();
        var seenTriangles=new java.util.BitSet((int)totalTriangles);
        for(int i=0;i<meshCount;i++) {
            int n=vertices[i],t=triangles[i];
            var xyz=floats(in,n*3);var uv=floats(in,n*2);var local=floats(in,n*2);
            for(float x:local)if(x<0 || x>1)throw new IOException("local outside Warp grid");
            var indices=ints(in,t*3);for(int x:indices)if(x<0 || x>=n)throw new IOException("mesh index");
            var sourceVertex=ints(in,n);var sourceUv=ints(in,n);var slots=ints(in,n*3);var sourceTriangles=ints(in,t);
            for(int x:sourceVertex)if(x<0)throw new IOException("negative source vertex identity");
            for(int x:sourceUv)if(x<0)throw new IOException("negative source UV identity");
            for(int j=0;j<n;j++)if(slots[j*3]<0 || slots[j*3]>1 || slots[j*3+1]<0 || slots[j*3+2]<0)
                throw new IOException("source slot identity");
            for(int x:sourceTriangles) {
                if(x<0 || x>=totalTriangles || seenTriangles.get(x))throw new IOException("source triangle permutation");
                seenTriangles.set(x);
            }
            meshes.add(new Mesh(xyz,uv,local,indices,sourceVertex,sourceUv,slots,sourceTriangles));
        }
        var poses=floats(in,poseCount*2);
        for(int y=0;y<17;y++)for(int p=0;p<17;p++) {
            int offset=(y*17+p)*2;
            if(poses[offset]!=-30+3.75f*y || poses[offset+1]!=-30+3.75f*p)throw new IOException("joint pose inventory");
        }
        var controls=floats(in,Math.toIntExact(controlCount*poseCount*2));
        if(in.read()!=-1)throw new IOException("trailing fixture bytes");
        return new SourceWarpFixture(columns,rows,meshes,poses,controls);
    }
    private static float[] floats(DataInputStream in,int count) throws IOException {
        var result=new float[count];for(int i=0;i<count;i++) {
            float x=in.readFloat();if(!Float.isFinite(x))throw new IOException("nonfinite fixture coordinate");result[i]=x;
        }
        return result;
    }
    private static int[] ints(DataInputStream in,int count) throws IOException {
        var result=new int[count];for(int i=0;i<count;i++)result[i]=in.readInt();return result;
    }
}
