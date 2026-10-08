package dev.turboism.validation.modelimport3d.host;

import java.util.*;

/** Local piecewise-linear surface/UV comparison on the exact triangle overlay.
 * Both patches must project without folds to the same plane domain. At overlay
 * vertices, height and UV differences attain their extrema; barycentric maps
 * give a two-way surface correspondence rather than a vertex displacement bound. */
final class SurfacePatchCollapseProof {
    record Bound(double geometry,double uv) { }
    private record V(double x,double y,double height,double u,double v) { }
    private record T(V a,V b,V c) { }
    static Optional<Bound> compare(GlbSurfaceReader.Surface source,List<int[]> before,List<int[]> after) {
        if(before.isEmpty() || after.isEmpty())return Optional.empty();
        double[] normal=new double[3];
        for(int[] f:before){var a=source.positions().get(f[0]);var b=source.positions().get(f[1]);var c=source.positions().get(f[2]);double x=b.x()-a.x(),y=b.y()-a.y(),z=b.z()-a.z(),u=c.x()-a.x(),v=c.y()-a.y(),w=c.z()-a.z();normal[0]+=y*w-z*v;normal[1]+=z*u-x*w;normal[2]+=x*v-y*u;}
        if(!unit(normal))return Optional.empty();
        int axis=0;for(int i=1;i<3;i++)if(Math.abs(normal[i])<Math.abs(normal[axis]))axis=i;
        double[] seed=new double[3];seed[axis]=1;double[] horizontal=cross(seed,normal);if(!unit(horizontal))return Optional.empty();double[] vertical=cross(normal,horizontal);
        var origin=source.positions().get(before.get(0)[0]);var points=new HashMap<Integer,V>();
        for(var patch:List.of(before,after))for(int[] face:patch)for(int slot:face)points.computeIfAbsent(slot,i->{
            var p=source.positions().get(i);double[] delta={p.x()-origin.x(),p.y()-origin.y(),p.z()-origin.z()};var uv=source.textureUvs().isEmpty()?null:source.textureUvs().get(i);
            return new V(dot(delta,horizontal),dot(delta,vertical),dot(delta,normal),uv==null?0:uv.x(),uv==null?0:uv.y());
        });
        double extent=0;for(var p:points.values())extent=Math.max(extent,Math.max(Math.abs(p.x()),Math.abs(p.y())));
        if(!(extent>0) || !Double.isFinite(extent))return Optional.empty();
        double areaEpsilon=extent*extent*1e-12;
        var old=triangles(before,points,areaEpsilon);var next=triangles(after,points,areaEpsilon);
        if(old==null || next==null)return Optional.empty();
        double minArea=Double.POSITIVE_INFINITY;
        for(var patch:List.of(old,next))for(T t:patch)minArea=Math.min(minArea,area(t));
        double condition=extent*extent/minArea;
        if(!Double.isFinite(condition) || condition>1e6)return Optional.empty();
        double numericalMargin=1024*Math.ulp(1d)*Math.max(1,condition);
        double oldArea=old.stream().mapToDouble(SurfacePatchCollapseProof::area).sum(),newArea=next.stream().mapToDouble(SurfacePatchCollapseProof::area).sum();
        if(Math.abs(oldArea-newArea)>areaEpsilon*16)return Optional.empty();
        // Boundary equality plus positive projected orientation proves a shared
        // triangulated domain. Reject ambiguous/non-disk boundary arrangements.
        if(!boundary(before).equals(boundary(after)))return Optional.empty();
        double geometry=0,uv=0,overlap=0;
        for(T a:old)for(T b:next) {
            var polygon=new ArrayList<V>(List.of(a.a(),a.b(),a.c()));
            V[] clip={b.a(),b.b(),b.c()};
            for(int edge=0;edge<3 && !polygon.isEmpty();edge++)polygon=clip(polygon,clip[edge],clip[(edge+1)%3]);
            if(polygon.size()<3)continue;
            double covered=0;for(int i=1;i<polygon.size()-1;i++)covered+=Math.abs(side(polygon.get(0),polygon.get(i),polygon.get(i+1)))/2;
            overlap+=covered;
            for(V p:polygon) {
                V x=interpolate(a,p),y=interpolate(b,p);
                geometry=Math.max(geometry,Math.abs(x.height()-y.height()));uv=Math.max(uv,Math.hypot(x.u()-y.u(),x.v()-y.v()));
            }
        }
        if(!Double.isFinite(geometry) || !Double.isFinite(uv) || Math.abs(overlap-oldArea)>areaEpsilon*32)return Optional.empty();
        // Cover arithmetic roundoff. This also refuses "exact zero" claims for
        // uncertain floating geometry; zero-budget mode uses endpoint proof.
        return Optional.of(new Bound(geometry+extent*numericalMargin,uv+numericalMargin));
    }
    private static List<T> triangles(List<int[]> faces,Map<Integer,V> points,double epsilon) {
        var result=new ArrayList<T>();for(int[] face:faces){var t=new T(points.get(face[0]),points.get(face[1]),points.get(face[2]));if(area(t)<=epsilon)return null;result.add(t);}return result;
    }
    private static Map<Long,Integer> boundary(List<int[]> faces) {
        var edges=new TreeMap<Long,Integer>();for(int[] f:faces)for(int c=0;c<3;c++){int a=f[c],b=f[(c+1)%3];long key=((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);edges.merge(key,1,Integer::sum);}
        edges.entrySet().removeIf(e->e.getValue()==2);return edges;
    }
    private static ArrayList<V> clip(List<V> polygon,V a,V b) {
        var out=new ArrayList<V>();V previous=polygon.get(polygon.size()-1);double last=side(a,b,previous);
        for(V current:polygon) {
            double value=side(a,b,current);
            if((last>=0)!=(value>=0)) {double t=last/(last-value);out.add(new V(previous.x()+t*(current.x()-previous.x()),previous.y()+t*(current.y()-previous.y()),0,0,0));}
            if(value>=0)out.add(current);previous=current;last=value;
        }return out;
    }
    private static V interpolate(T t,V p) {
        double denominator=2*area(t),a=side(t.b(),t.c(),p)/denominator,b=side(t.c(),t.a(),p)/denominator,c=1-a-b;
        return new V(p.x(),p.y(),a*t.a().height()+b*t.b().height()+c*t.c().height(),a*t.a().u()+b*t.b().u()+c*t.c().u(),a*t.a().v()+b*t.b().v()+c*t.c().v());
    }
    private static double area(T t){return side(t.a(),t.b(),t.c())/2;}
    private static double side(V a,V b,V p){return (b.x()-a.x())*(p.y()-a.y())-(b.y()-a.y())*(p.x()-a.x());}
    private static boolean unit(double[] v){double length=Math.hypot(Math.hypot(v[0],v[1]),v[2]);if(!(length>0) || !Double.isFinite(length))return false;for(int i=0;i<3;i++)v[i]/=length;return true;}
    private static double[] cross(double[] a,double[] b){return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
}
