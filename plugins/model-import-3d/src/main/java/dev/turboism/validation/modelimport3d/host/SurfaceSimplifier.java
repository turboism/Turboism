package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.*;

/** Endpoint edge collapse with deterministic ordering and conservative displacement bounds.
 * Primitive/material boundaries, open boundaries and duplicated-position seams are locked.
 * Surface mode uses the local triangle-overlay proof and propagates geometry/UV
 * errors across successive collapses. This is not a rendered-image equivalence proof. */
final class SurfaceSimplifier {
    record Result(List<GlbSurfaceReader.Surface> surfaces,int sourceTriangles,int outputTriangles,
                  double maximumGeometryErrorPixels,double maximumTextureErrorTexels,List<List<Integer>> originalToOutput) {
        Result { surfaces=List.copyOf(surfaces);originalToOutput=originalToOutput.stream().map(List::copyOf).toList(); }
    }
    private record Edge(int a,int b,double length) { }
    static Result simplify(List<GlbSurfaceReader.Surface> sources,double scale,double pixels,double texels) {
        return simplify(sources,scale,pixels,texels,false);
    }
    static Result simplifySurface(List<GlbSurfaceReader.Surface> sources,double scale,double pixels,double texels) {
        return simplify(sources,scale,pixels,texels,pixels>0 && texels>0);
    }
    private static Result simplify(List<GlbSurfaceReader.Surface> sources,double scale,double pixels,double texels,boolean surfaceProof) {
        if(!Double.isFinite(scale) || scale<=0 || !Double.isFinite(pixels) || pixels<0 || !Double.isFinite(texels) || texels<0)
            throw new IllegalArgumentException("finite simplification budgets required");
        var output=new ArrayList<GlbSurfaceReader.Surface>();var mappings=new ArrayList<List<Integer>>();int before=0,after=0;double error=0,textureError=0;
        // Preserve seams and instance joins even when they are in different primitives.
        var occurrences=new HashMap<SurfaceProjection.Vertex,Integer>();
        for(var s:sources)for(var p:s.positions())occurrences.merge(p,1,Integer::sum);
        for(var s:sources) {
            var mesh=new Mesh(s,occurrences,pixels/scale,texels,surfaceProof);mesh.collapse();
            output.add(mesh.surface());mappings.add(mesh.mapping());before+=s.indices().size()/3;after+=mesh.count();error=Math.max(error,mesh.maximumError()*scale);
            if(s.texture()!=null)textureError=Math.max(textureError,mesh.maximumUvError()*Math.max(s.texture().width(),s.texture().height()));
        }
        return new Result(output,before,after,error,textureError,mappings);
    }
    private static final class Mesh {
        final GlbSurfaceReader.Surface source;final int[][] faces;final boolean[] live,locked;final int[] parent;
        final List<Set<Integer>> incident=new ArrayList<>();final double[] radius,uvRadius;final double distanceBudget,uvBudget;final boolean surfaceProof;final double[] faceError,faceUvError;
        final PriorityQueue<Edge> edges=new PriorityQueue<>(Comparator.comparingDouble(Edge::length).thenComparingInt(Edge::a).thenComparingInt(Edge::b));
        Mesh(GlbSurfaceReader.Surface source,Map<SurfaceProjection.Vertex,Integer> occurrences,double distanceBudget,double texels,boolean surfaceProof) {
            this.source=source;this.distanceBudget=distanceBudget;this.surfaceProof=surfaceProof;
            this.uvBudget=source.texture()==null?Double.POSITIVE_INFINITY:texels/Math.max(source.texture().width(),source.texture().height());
            int n=source.positions().size();parent=new int[n];locked=new boolean[n];radius=new double[n];uvRadius=new double[n];
            for(int i=0;i<n;i++){parent[i]=i;incident.add(new TreeSet<>());locked[i]=occurrences.get(source.positions().get(i))>1;}
            faces=new int[source.indices().size()/3][3];live=new boolean[faces.length];faceError=new double[faces.length];faceUvError=new double[faces.length];Arrays.fill(live,true);
            var counts=new TreeMap<Long,Integer>();
            for(int f=0;f<faces.length;f++)for(int c=0;c<3;c++) {
                int a=source.indices().get(f*3+c),b=source.indices().get(f*3+(c+1)%3);faces[f][c]=a;incident.get(a).add(f);counts.merge(key(a,b),1,Integer::sum);
            }
            for(var e:counts.entrySet()){int a=(int)(e.getKey()>>>32),b=(int)(long)e.getKey();if(e.getValue()!=2){locked[a]=true;locked[b]=true;}else enqueue(a,b);}
        }
        void enqueue(int a,int b){if(a==b)return;int lo=Math.min(a,b),hi=Math.max(a,b);if(!locked[lo]&&!locked[hi])edges.add(new Edge(lo,hi,distance(source.positions().get(lo),source.positions().get(hi))));}
        void collapse() {
            while(!edges.isEmpty()) {
                var e=edges.remove();int a=e.a(),b=e.b();if(parent[a]!=a || parent[b]!=b || locked[a] || locked[b])continue;
                double next=Math.max(radius[a],e.length()+radius[b]);if(!surfaceProof && next>distanceBudget)continue;
                double uv=Math.max(uvRadius[a],uvDistance(a,b)+uvRadius[b]);if(!surfaceProof && uv>uvBudget)continue;
                Set<Integer> na=neighbors(a),nb=neighbors(b),common=new TreeSet<>(na);common.retainAll(nb);
                var shared=new TreeSet<>(incident.get(a));shared.retainAll(incident.get(b));
                if(shared.size()!=2 || common.size()!=2 || duplicateAfterCollapse(a,b,shared))continue; // manifold link condition
                var touched=new TreeSet<>(incident.get(b));boolean valid=true;
                for(int f:touched)if(!shared.contains(f) && !validFace(f,b,a)){valid=false;break;}
                if(!valid)continue;
                double newError=0,newUvError=0;var patch=new TreeSet<>(incident.get(a));patch.addAll(touched);
                if(surfaceProof) {
                    var before=new ArrayList<int[]>();var after=new ArrayList<int[]>();double oldError=0,oldUvError=0;
                    for(int f:patch) {
                        before.add(faces[f]);oldError=Math.max(oldError,faceError[f]);oldUvError=Math.max(oldUvError,faceUvError[f]);
                        if(!shared.contains(f)){int[] face=faces[f].clone();for(int c=0;c<3;c++)if(face[c]==b)face[c]=a;after.add(face);}
                    }
                    var proof=SurfacePatchCollapseProof.compare(source,before,after);if(proof.isEmpty())continue;
                    newError=oldError+proof.get().geometry();newUvError=oldUvError+proof.get().uv();
                    if(newError>distanceBudget || newUvError>uvBudget)continue;
                }
                for(int f:touched) {
                    for(int c:faces[f])incident.get(c).remove(f);
                    if(shared.contains(f)){live[f]=false;continue;}
                    for(int c=0;c<3;c++)if(faces[f][c]==b)faces[f][c]=a;
                    for(int c:faces[f])incident.get(c).add(f);
                }
                parent[b]=a;radius[a]=next;uvRadius[a]=uv;
                if(surfaceProof)for(int f:patch)if(live[f]){faceError[f]=newError;faceUvError[f]=newUvError;}
                for(int other:neighbors(a))enqueue(a,other);
            }
        }
        boolean duplicateAfterCollapse(int a,int b,Set<Integer> removed) {
            var seen=new HashSet<List<Integer>>();var touched=new TreeSet<>(incident.get(a));touched.addAll(incident.get(b));
            for(int f:touched)if(!removed.contains(f)) {
                int[] triple=faces[f].clone();for(int i=0;i<3;i++)if(triple[i]==b)triple[i]=a;Arrays.sort(triple);
                if(!seen.add(List.of(triple[0],triple[1],triple[2])))return true;
            }return false;
        }
        boolean validFace(int f,int from,int to) {
            int[] old=faces[f],next=old.clone();for(int i=0;i<3;i++)if(next[i]==from)next[i]=to;
            if(next[0]==next[1] || next[1]==next[2] || next[0]==next[2])return false;
            double[] n=normal(old),m=normal(next);double nn=dot(n,n),mm=dot(m,m);
            if(nn==0 || mm==0 || dot(n,m)<=.99*Math.sqrt(nn*mm))return false;
            if(!source.textureUvs().isEmpty()) {
                double u=area(old),v=area(next);if(u!=0 && (u*v<=0 || Math.abs(v)<Math.abs(u)*.01))return false;
            }
            return true;
        }
        double area(int[] f){var a=source.textureUvs().get(f[0]);var b=source.textureUvs().get(f[1]);var c=source.textureUvs().get(f[2]);return (b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());}
        double uvDistance(int a,int b){if(source.textureUvs().isEmpty())return 0;Point2 u=source.textureUvs().get(a),v=source.textureUvs().get(b);return Math.hypot(u.x()-v.x(),u.y()-v.y());}
        double[] normal(int[] f){var a=source.positions().get(f[0]);var b=source.positions().get(f[1]);var c=source.positions().get(f[2]);double x=b.x()-a.x(),y=b.y()-a.y(),z=b.z()-a.z(),u=c.x()-a.x(),v=c.y()-a.y(),w=c.z()-a.z();return new double[]{y*w-z*v,z*u-x*w,x*v-y*u};}
        Set<Integer> neighbors(int v){var result=new TreeSet<Integer>();for(int f:incident.get(v))for(int c:faces[f])if(c!=v)result.add(c);return result;}
        int root(int v){while(parent[v]!=v){parent[v]=parent[parent[v]];v=parent[v];}return v;}
        List<Integer> roots(){var result=new TreeSet<Integer>();for(int f=0;f<faces.length;f++)if(live[f])for(int c:faces[f])result.add(c);return List.copyOf(result);}
        List<Integer> mapping(){var roots=roots();var index=new HashMap<Integer,Integer>();for(int i=0;i<roots.size();i++)index.put(roots.get(i),i);var map=new ArrayList<Integer>();for(int i=0;i<parent.length;i++)map.add(index.getOrDefault(root(i),-1));return map;}
        GlbSurfaceReader.Surface surface(){var roots=roots();var map=mapping();var positions=roots.stream().map(source.positions()::get).toList();var normals=source.sourceNormals().isEmpty()?List.<SurfaceProjection.Vertex>of():roots.stream().map(source.sourceNormals()::get).toList();var uv=source.textureUvs().isEmpty()?List.<Point2>of():roots.stream().map(source.textureUvs()::get).toList();var indices=new ArrayList<Integer>();for(int f=0;f<faces.length;f++)if(live[f])for(int c:faces[f])indices.add(map.get(c));return new GlbSurfaceReader.Surface(source.node(),source.mesh(),source.primitive(),source.material(),positions,normals,uv,indices,source.baseColor(),source.doubleSided(),source.reflected(),source.texture());}
        int count(){int n=0;for(boolean b:live)if(b)n++;return n;}
        double maximumUvError(){double max=0;if(surfaceProof){for(int f=0;f<faces.length;f++)if(live[f])max=Math.max(max,faceUvError[f]);}else for(int i:roots())max=Math.max(max,uvRadius[i]);return max;}
        double maximumError(){if(surfaceProof){double max=0;for(int f=0;f<faces.length;f++)if(live[f])max=Math.max(max,faceError[f]);return max;}double max=0;for(int i:roots())max=Math.max(max,radius[i]);return max;}
    }
    private static long key(int a,int b){return ((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);}
    private static double distance(SurfaceProjection.Vertex a,SurfaceProjection.Vertex b){return Math.hypot(Math.hypot(a.x()-b.x(),a.y()-b.y()),a.z()-b.z());}
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
}
