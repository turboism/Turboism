package dev.turboism.validation.modelimport3d.host;

import java.math.*;
import java.util.*;

/** Analytic order plan for exactly two disjoint opaque, double-sided X-plane rectangles.
 * Import preparation also checks the float-stored transition cells. Native evaluation
 * and rendered overlap remain separate acceptance requirements.
 */
final class ParallelSheetOrderPlan {
    private ParallelSheetOrderPlan() {}
    record Plan(int leftSurface,double yawLimit,double transitionDegrees,BigDecimal separationLowerBound) {
        int drawOrder(int surface,double yaw) {
            require(surface==0 || surface==1,"surface identity");
            require(Double.isFinite(yaw) && Math.abs(yaw)<=yawLimit,"yaw outside proved domain");
            if(yaw==0)return 500;
            return ((surface==leftSurface)==(yaw<0)) ? 501 : 499;
        }
    }
    record Rectangle(BigDecimal x,BigDecimal radius) {}
    /** Each vertex-pair X gap is bilinear within a joint cell, hence a convex
     * combination of its four corner gaps. A positive gap at every corner proves
     * separation throughout both neutral-adjacent columns, including all pitches.
     * Reserve 0.1 px per sheet for the separately required native geometry bound.
     */
    static BigDecimal verifyStoredTransition(Plan plan,SurfaceImportPlan.Plan geometry) {
        require(geometry.patches().size()==2,"two stored sheet patches required");
        require(geometry.patches().get(0).surfaceIndex()==0 && geometry.patches().get(1).surfaceIndex()==1,
            "stored sheet identities differ");
        var left=geometry.patches().get(plan.leftSurface()).positionsByKey();
        var right=geometry.patches().get(1-plan.leftSurface()).positionsByKey();
        require(left.size()==geometry.keys().size() && right.size()==left.size(),"incomplete stored grid");
        BigDecimal minimum=null;
        boolean negative=false,neutral=false,positive=false;
        for(int i=0;i<geometry.keys().size();i++) {
            float yaw=geometry.keys().get(i).yaw();
            if(Math.abs(yaw)>plan.transitionDegrees())continue;
            negative|=yaw<0;neutral|=yaw==0;positive|=yaw>0;
            require(!left.get(i).isEmpty() && !right.get(i).isEmpty(),"empty stored sheet");
            for(var a:left.get(i))for(var b:right.get(i)) {
                var gap=d(b.x()).subtract(d(a.x()));
                minimum=minimum==null?gap:minimum.min(gap);
            }
        }
        require(negative && neutral && positive,"neutral transition keys missing");
        require(minimum.compareTo(new BigDecimal("0.2"))>0,"stored order-transition margin insufficient");
        return minimum;
    }
    static Plan compile(List<GlbSurfaceReader.Surface> surfaces,SurfaceProjection.Camera camera,
            double yawLimit,double pitchLimit,double transitionDegrees) {
        require(surfaces.size()==2,"exactly two sheet primitives required");
        require(Double.isFinite(yawLimit) && yawLimit>0 && yawLimit<=30
            && Double.isFinite(pitchLimit) && pitchLimit>=0 && pitchLimit<=30
            && Double.isFinite(transitionDegrees) && transitionDegrees>0 && transitionDegrees<=yawLimit,"finite bounded rotation domain");
        var pitch=upperRadians(pitchLimit);
        var a=rectangle(surfaces.get(0),camera,pitch);var b=rectangle(surfaces.get(1),camera,pitch);
        var gap=a.x.subtract(b.x).abs();require(gap.signum()>0,"coincident sheets");
        // 355/113 > pi. For 0<=t<=pi/6: sin(t)<=t, cos(t)>=1-t²/2.
        // Exact decimal arithmetic after upward rounding radians gives a conservative
        // positive projected-X separation throughout the entire order-transition strip.
        var t=upperRadians(transitionDegrees);
        var lower=gap.multiply(BigDecimal.ONE.subtract(t.multiply(t).divide(BigDecimal.valueOf(2))))
            .subtract(a.radius.add(b.radius).multiply(t));
        require(lower.signum()>0,"order-transition strip may overlap");
        // After Ry*Rx the plane normal is (cos(yaw),0,-sin(yaw)). At a common
        // screen point, depth difference = (xLeft-xRight)/(-sin(yaw)), independent
        // of pitch. Thus left is nearer for negative yaw, right for positive yaw.
        return new Plan(a.x.compareTo(b.x)<0?0:1,yawLimit,transitionDegrees,lower);
    }
    private static Rectangle rectangle(GlbSurfaceReader.Surface s,SurfaceProjection.Camera camera,BigDecimal pitch) {
        require(s.doubleSided() && !s.reflected() && s.baseColor().size()==4 && s.baseColor().get(3)==1d,
            "opaque double-sided nonreflected sheet required");
        require(s.positions().size()==4 && s.indices().size()==6,"four-corner two-triangle sheet required");
        var p=s.positions();var x=d(p.get(0).x());
        require(p.stream().allMatch(v->d(v.x()).compareTo(x)==0),"X-plane required");
        double ymin=p.stream().mapToDouble(SurfaceProjection.Vertex::y).min().orElseThrow();
        double ymax=p.stream().mapToDouble(SurfaceProjection.Vertex::y).max().orElseThrow();
        double zmin=p.stream().mapToDouble(SurfaceProjection.Vertex::z).min().orElseThrow();
        double zmax=p.stream().mapToDouble(SurfaceProjection.Vertex::z).max().orElseThrow();
        require(ymin<ymax && zmin<zmax,"positive sheet extent");
        var corners=new HashSet<List<Double>>();
        for(var v:p) {
            require((v.y()==ymin || v.y()==ymax) && (v.z()==zmin || v.z()==zmax),"axis-aligned rectangle required");
            require(corners.add(List.of(v.y()==0?0d:v.y(),v.z()==0?0d:v.z())),"duplicate source corner");
        }
        var first=new HashSet<Integer>();var second=new HashSet<Integer>();
        for(int i=0;i<6;i++) {
            int index=s.indices().get(i);require(index>=0 && index<4,"sheet index");
            require((i<3?first:second).add(index),"degenerate sheet triangle");
        }
        var shared=new HashSet<>(first);shared.retainAll(second);
        var all=new HashSet<>(first);all.addAll(second);
        require(shared.size()==2 && all.size()==4,"triangles must partition rectangle");
        var diagonal=shared.stream().map(p::get).toList();
        require(diagonal.get(0).y()!=diagonal.get(1).y() && diagonal.get(0).z()!=diagonal.get(1).z(),"shared edge must be diagonal");
        var yRadius=d(ymin).subtract(d(camera.pivot().y())).abs().max(d(ymax).subtract(d(camera.pivot().y())).abs());
        var zRadius=d(zmin).subtract(d(camera.pivot().z())).abs().max(d(zmax).subtract(d(camera.pivot().z())).abs());
        return new Rectangle(x,zRadius.add(yRadius.multiply(pitch)));
    }
    private static BigDecimal d(double value) {
        require(Double.isFinite(value),"finite geometry required");return new BigDecimal(value);
    }
    private static BigDecimal upperRadians(double degrees) {
        return d(degrees).multiply(BigDecimal.valueOf(355)).divide(BigDecimal.valueOf(113*180),new MathContext(50,RoundingMode.CEILING));
    }
    private static void require(boolean ok,String message) {if(!ok)throw new IllegalArgumentException(message);}
}
