package dev.turboism.validation.modelimport3d.host;

import java.util.*;

/** Deterministic geometry/interpolation tradeoff inside the existing 5px budget.
 * Cost covers XY float storage and the 80-byte form object only; it is not a
 * prediction of total Editor memory, load time, or occlusion fidelity. */
final class SurfaceImportCostPlan {
    private SurfaceImportCostPlan() { }
    static final double TOTAL_ERROR_PIXELS=5;
    record Candidate(SurfaceSimplifier.Result simplified,SurfaceVertexPlan.Plan geometry,
                     double interpolationAllowance,long forms,long cacheSlots,long storageBytes) { }

    static Candidate choose(List<GlbSurfaceReader.Surface> sources,SurfaceImport.Request request,int maximumMeshes) {
        return choose(sources,request,maximumMeshes,0);
    }

    /** Compare complete editable partitions before spending the image allocation
     * budget. Source winding and aliased UV corners can change the cheapest
     * geometry candidate; selecting first and splitting later misses that cost. */
    static Candidate chooseEditable(List<GlbSurfaceReader.Surface> sources,SurfaceImport.Request request,
                                    int originalMeshes,int maximumEditableMeshes) {
        if(maximumEditableMeshes<1 || maximumEditableMeshes>1001)
            throw new IllegalArgumentException("native editable partition budget");
        return choose(sources,request,originalMeshes,maximumEditableMeshes);
    }

    private static Candidate choose(List<GlbSurfaceReader.Surface> sources,SurfaceImport.Request request,
                                    int maximumMeshes,int maximumEditableMeshes) {
        if(request.roll().isEmpty() || request.yawLimit()!=180 || request.pitchLimit()!=180)
            throw new IllegalArgumentException("optimized direct projection requires XYZ full circle");
        if(sources.stream().anyMatch(GlbSurfaceReader.Surface::doubleSided))
            throw new IllegalArgumentException("optimized direct projection requires source front-face culling");
        double radius=0;var camera=request.camera();var pivot=camera.pivot();
        for(var source:sources)for(var v:source.positions())radius=Math.max(radius,camera.scale()*Math.sqrt(
            Math.pow(v.x()-pivot.x(),2)+Math.pow(v.y()-pivot.y(),2)+Math.pow(v.z()-pivot.z(),2)));
        double geometryBudget=TOTAL_ERROR_PIXELS-(2*SurfaceVertexAuthoring.chordError(radius,SurfaceVertexAuthoring.INTERPOLATION.insertPointCount())+.01);
        if(geometryBudget<0)throw new IllegalArgumentException("source interpolation exceeds combined error budget");
        Candidate best=null;
        IllegalArgumentException editableFailure=null;
        // Keep the existing candidate, then reserve more error for interpolation.
        // Partition and density are measured before building any image resources.
        for(double fraction:new double[]{1,.5,.25}) {
            var simplified=SurfaceSimplifier.simplifySurface(sources,camera.scale(),geometryBudget*fraction,.5);
            var geometry=SurfaceVertexPlan.compile(simplified.surfaces(),maximumMeshes);
            if(maximumEditableMeshes>0) {
                try {
                    var editable=SurfaceNativeSourceWindingPlan.compile(geometry,maximumEditableMeshes);
                    geometry=new SurfaceVertexPlan.Plan(editable.sources(),
                        editable.groups().stream().map(SurfaceNativeSourceWindingPlan.Group::mesh).toList(),editable.repeatedSlots());
                } catch(IllegalArgumentException rejected) { editableFailure=rejected;continue; }
            }
            double allowance=TOTAL_ERROR_PIXELS-simplified.maximumGeometryErrorPixels();
            long forms=0,slots=0;boolean fits=true;
            for(var mesh:geometry.meshes()) {
                var density=SurfaceVertexAuthoring.sampling(SurfaceVertexAuthoring.projectedRadius(mesh,camera),allowance);
                forms=Math.addExact(forms,density.grid());
                slots=Math.addExact(slots,Math.multiplyExact(density.grid(),mesh.positions().size()));
                if(density.grid()*mesh.positions().size()>32_000_000L || 80L*mesh.positions().size()>2_000_000L)fits=false;
            }
            if(!fits || slots>SurfaceVertexAuthoring.MAX_CACHE_SLOTS || geometry.repeatedSlots()*81>
                    Math.min(SurfaceWarpLayerAuthoring.MAX_REPEATED_LAYER_SLOTS,request.maxProjectedVertices()))continue;
            long bytes=Math.addExact(Math.multiplyExact(8,slots),Math.multiplyExact(80,forms));
            var candidate=new Candidate(simplified,geometry,allowance,forms,slots,bytes);
            if(best==null || compare(candidate,best)<0)best=candidate;
        }
        if(best==null)throw new IllegalArgumentException("combined import storage budget exceeded",editableFailure);
        return best;
    }
    static int compare(Candidate a,Candidate b) {
        int result=Long.compare(a.storageBytes(),b.storageBytes());
        if(result==0)result=Integer.compare(a.simplified().outputTriangles(),b.simplified().outputTriangles());
        if(result==0)result=Double.compare(a.simplified().maximumGeometryErrorPixels(),b.simplified().maximumGeometryErrorPixels());
        return result;
    }
}
