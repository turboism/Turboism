package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Source-space polygon splitting for future visibility partitioning.
 * Every corner retains its original triangle and barycentric coordinates. No pose,
 * UV welding, material conversion or native admission occurs here. Numerical plane
 * tolerance is reported explicitly; this primitive is not an occlusion proof.
 */
final class SurfaceSourceSplit {
    private SurfaceSourceSplit() { }

    record Source(int node, int mesh, int primitive, int triangle,
            List<SurfaceProjection.Vertex> positions, List<Point2> uvs) {
        Source {
            positions=List.copyOf(positions);uvs=List.copyOf(uvs);
            require(node>=0 && mesh>=0 && primitive>=0 && triangle>=0,"source identity");
            require(positions.size()==3 && uvs.size()==3,"source triangle required");
            for(var p:positions)require(finite(p.x(),p.y(),p.z()),"finite source positions");
            for(var p:uvs)require(finite(p.x(),p.y()),"finite source UVs");
        }
        static Source from(GlbSurfaceReader.Surface surface,int triangle) {
            require(triangle>=0 && triangle<surface.indices().size()/3,"source triangle index");
            var slots=surface.indices().subList(triangle*3,triangle*3+3);
            return new Source(surface.node(),surface.mesh(),surface.primitive(),triangle,
                slots.stream().map(surface.positions()::get).toList(),slots.stream().map(surface.textureUvs()::get).toList());
        }
    }
    record Uv(double u,double v) { }
    record Bary(double a,double b,double c) {
        Bary {
            require(finite(a,b,c) && a>=0 && b>=0 && c>=0 && Math.abs(a+b+c-1)<=1e-12,"barycentric corner");
        }
        SurfaceProjection.Vertex position(Source s) {
            var p=s.positions();
            return new SurfaceProjection.Vertex(a*p.get(0).x()+b*p.get(1).x()+c*p.get(2).x(),
                a*p.get(0).y()+b*p.get(1).y()+c*p.get(2).y(),a*p.get(0).z()+b*p.get(1).z()+c*p.get(2).z());
        }
        Uv uv(Source s) {
            var p=s.uvs();
            return new Uv(a*p.get(0).x()+b*p.get(1).x()+c*p.get(2).x(),
                a*p.get(0).y()+b*p.get(1).y()+c*p.get(2).y());
        }
        Bary mix(Bary other,double t) {
            require(Double.isFinite(t) && t>=0 && t<=1,"edge intersection");
            return new Bary((1-t)*a+t*other.a,(1-t)*b+t*other.b,(1-t)*c+t*other.c);
        }
    }
    record Polygon(Source source,List<Bary> corners) {
        Polygon {
            Objects.requireNonNull(source,"source");corners=List.copyOf(corners);
            require(corners.size()>=3 && corners.size()<=64,"split polygon budget");
        }
        static Polygon triangle(Source source) {
            return new Polygon(source,List.of(new Bary(1,0,0),new Bary(0,1,0),new Bary(0,0,1)));
        }
    }
    /** Unit-normal plane n.p + offset = 0. */
    record Plane(double x,double y,double z,double offset) {
        Plane {
            require(finite(x,y,z,offset),"finite plane");
            double length=Math.hypot(Math.hypot(x,y),z);
            require(Double.isFinite(length) && length>0,"nonzero plane normal");
            x/=length;y/=length;z/=length;offset/=length;
            require(Double.isFinite(offset),"finite normalized plane");
        }
        double distance(SurfaceProjection.Vertex p) {return x*p.x()+y*p.y()+z*p.z()+offset;}
    }
    record Split(Polygon negative,Polygon positive,Polygon coplanar,double tolerance) { }

    static Split split(Polygon polygon,Plane plane) {
        Objects.requireNonNull(polygon,"polygon");Objects.requireNonNull(plane,"plane");
        int count=polygon.corners().size();double[] distances=new double[count];double magnitude=1;
        for(int i=0;i<count;i++) {
            var p=polygon.corners().get(i).position(polygon.source());
            distances[i]=plane.distance(p);
            double scale=Math.abs(plane.x()*p.x())+Math.abs(plane.y()*p.y())+Math.abs(plane.z()*p.z())+Math.abs(plane.offset());
            require(finite(distances[i],scale),"plane evaluation overflow");magnitude=Math.max(magnitude,scale);
        }
        double tolerance=64*Math.ulp(magnitude);boolean negative=false,positive=false;
        int[] sides=new int[count];
        for(int i=0;i<count;i++) {
            sides[i]=distances[i]<-tolerance?-1:distances[i]>tolerance?1:0;
            negative|=sides[i]<0;positive|=sides[i]>0;
        }
        if(!negative && !positive)return new Split(null,null,polygon,tolerance);
        if(!positive)return new Split(polygon,null,null,tolerance);
        if(!negative)return new Split(null,polygon,null,tolerance);
        var lower=new ArrayList<Bary>();var upper=new ArrayList<Bary>();
        for(int i=0;i<count;i++) {
            int next=(i+1)%count;var a=polygon.corners().get(i);
            if(sides[i]<=0)lower.add(a);
            if(sides[i]>=0)upper.add(a);
            if(sides[i]*sides[next]<0) {
                // Use a scaled ratio to avoid overflow when signed distances differ greatly.
                double da=Math.abs(distances[i]),db=Math.abs(distances[next]);double scale=Math.max(da,db);
                double t=(da/scale)/(da/scale+db/scale);
                var crossing=a.mix(polygon.corners().get(next),t);
                lower.add(crossing);upper.add(crossing);
            }
        }
        return new Split(new Polygon(polygon.source(),lower),new Polygon(polygon.source(),upper),null,tolerance);
    }
    private static boolean finite(double... values) {
        for(double value:values)if(!Double.isFinite(value))return false;return true;
    }
    private static void require(boolean value,String message) {if(!value)throw new IllegalArgumentException(message);}
}
