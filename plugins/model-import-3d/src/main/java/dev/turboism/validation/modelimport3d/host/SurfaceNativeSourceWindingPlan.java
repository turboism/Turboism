package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.*;

/** Lossless partition by source-image winding before preparing editable native planes.
 * Original UVs, corner order and face identities stay in each source mesh. A reflected
 * image plane must use a correspondingly reflected bitmap; zero-area UV faces require
 * a separate derived plane and must never be discarded or admitted as editable. */
final class SurfaceNativeSourceWindingPlan {
    private SurfaceNativeSourceWindingPlan() { }
    enum Plane { ORIGINAL_IMAGE, REFLECTED_IMAGE, NEEDS_DERIVED_PLANE }
    record Group(int originalPartition, Plane plane, SurfaceVertexPlan.Mesh mesh) { }
    record Plan(List<GlbSurfaceReader.Surface> sources, List<Group> groups, long repeatedSlots) {
        Plan { sources=List.copyOf(sources);groups=List.copyOf(groups); }
        boolean requiresDerivedPlanes() {return groups.stream().anyMatch(g->g.plane()==Plane.NEEDS_DERIVED_PLANE);}
    }
    static Plan compile(SurfaceVertexPlan.Plan original, int maximumMeshes) {
        Objects.requireNonNull(original);
        if(maximumMeshes<1 || maximumMeshes>1001)throw new IllegalArgumentException("native editable partition budget");
        var groups=new ArrayList<Group>();long total=0;
        for(int partition=0;partition<original.meshes().size();partition++) {
            var mesh=original.meshes().get(partition);
            var faces=new EnumMap<Plane,List<Integer>>(Plane.class);
            for(var plane:Plane.values())faces.put(plane,new ArrayList<>());
            if(mesh.indices().size()!=mesh.triangles().size()*3 || mesh.positions().size()!=mesh.uvs().size()
                || mesh.positions().size()!=mesh.slots().size())throw new IllegalArgumentException("complete source corner/face provenance required");
            for(int face=0;face<mesh.triangles().size();face++) {
                var a=mesh.uvs().get(mesh.indices().get(3*face));
                var b=mesh.uvs().get(mesh.indices().get(3*face+1));
                var c=mesh.uvs().get(mesh.indices().get(3*face+2));
                double area=((double)b.x()-a.x())*((double)c.y()-a.y())-((double)b.y()-a.y())*((double)c.x()-a.x());
                if(!Double.isFinite(area))throw new IllegalArgumentException("finite source UV domain required");
                faces.get(area>0?Plane.ORIGINAL_IMAGE:area<0?Plane.REFLECTED_IMAGE:Plane.NEEDS_DERIVED_PLANE).add(face);
            }
            for(var plane:Plane.values())if(!faces.get(plane).isEmpty()) {
                var selections=new ArrayList<SurfaceVertexPlan.Mesh>();
                if(plane==Plane.NEEDS_DERIVED_PLANE) {
                    // Keep each original corner, even if two corners use the same
                    // source slot. Their source-image plane needs three distinct
                    // points; all 3D keyforms still use the original corner values.
                    for(int face:faces.get(plane))selections.add(triangle(mesh,face));
                } else selections.addAll(separateAliasedCorners(mesh,faces.get(plane)));
                for(var selected:selections) {
                    total+=selected.positions().size();groups.add(new Group(partition,plane,selected));
                    if(groups.size()>maximumMeshes || total>100000)throw new IllegalArgumentException("editable source split exceeds mesh/corner budget");
                }
            }
        }
        return new Plan(original.sources(),groups,total);
    }
    private record SourceUv(int surface,Point2 uv) { }
    /** Distinct source slots sharing an image point cannot be edited independently
     * by the native triangulator. Partition at the conflict; never weld their 3D
     * vertices or discard the source face. Keep the existing face sequence. */
    private static List<SurfaceVertexPlan.Mesh> separateAliasedCorners(SurfaceVertexPlan.Mesh mesh,List<Integer> faces) {
        var result=new ArrayList<SurfaceVertexPlan.Mesh>();
        var selected=new ArrayList<Integer>();var points=new HashMap<SourceUv,Integer>();
        for(int face:faces) {
            boolean conflict=false;
            for(int c=0;c<3;c++) {
                int slot=mesh.indices().get(3*face+c);var uv=mesh.uvs().get(slot);
                var key=new SourceUv(mesh.slots().get(slot).surface(),new Point2(uv.x()==0?0:uv.x(),uv.y()==0?0:uv.y()));
                Integer previous=points.get(key);if(previous!=null && previous!=slot)conflict=true;
            }
            if(conflict){result.add(submesh(mesh,selected));selected.clear();points.clear();}
            selected.add(face);
            for(int c=0;c<3;c++) {
                int slot=mesh.indices().get(3*face+c);var uv=mesh.uvs().get(slot);
                points.put(new SourceUv(mesh.slots().get(slot).surface(),new Point2(uv.x()==0?0:uv.x(),uv.y()==0?0:uv.y())),slot);
            }
        }
        if(!selected.isEmpty())result.add(submesh(mesh,selected));
        return result;
    }
    private static SurfaceVertexPlan.Mesh triangle(SurfaceVertexPlan.Mesh source,int face) {
        var positions=new ArrayList<SurfaceProjection.Vertex>();var uvs=new ArrayList<Point2>();var slots=new ArrayList<SurfaceVertexPlan.Slot>();
        for(int c=0;c<3;c++){int slot=source.indices().get(3*face+c);positions.add(source.positions().get(slot));uvs.add(source.uvs().get(slot));slots.add(source.slots().get(slot));}
        return new SurfaceVertexPlan.Mesh(positions,uvs,slots,List.of(0,1,2),List.of(source.triangles().get(face)));
    }
    private static SurfaceVertexPlan.Mesh submesh(SurfaceVertexPlan.Mesh original,List<Integer> faces) {
        var remap=new LinkedHashMap<Integer,Integer>();var indices=new ArrayList<Integer>();
        var identities=new ArrayList<SurfaceVertexPlan.Triangle>();
        for(int face:faces){identities.add(original.triangles().get(face));for(int c=0;c<3;c++){
            int old=original.indices().get(3*face+c);indices.add(remap.computeIfAbsent(old,ignored->remap.size()));
        }}
        var positions=new ArrayList<SurfaceProjection.Vertex>();var uvs=new ArrayList<Point2>();var slots=new ArrayList<SurfaceVertexPlan.Slot>();
        for(int old:remap.keySet()){positions.add(original.positions().get(old));uvs.add(original.uvs().get(old));slots.add(original.slots().get(old));}
        return new SurfaceVertexPlan.Mesh(positions,uvs,slots,indices,identities);
    }
}
