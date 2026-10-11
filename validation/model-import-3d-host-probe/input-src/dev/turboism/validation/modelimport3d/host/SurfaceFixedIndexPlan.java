package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Candidate fixed-index packing of original source triangles. Centroid Z ordering
 * is not an occlusion proof and this plan is not admitted by SurfaceImport.
 * Slots are keyed by source surface/index; numerical position or UV equality never
 * welds seams or instances. Different appearances and native vertex limits split runs.
 */
final class SurfaceFixedIndexPlan {
    private SurfaceFixedIndexPlan() { }
    record Slot(int surface,int vertex) { }
    record Triangle(int surface,int triangle,double centerZ) { }
    record Appearance(int material,boolean doubleSided,boolean reflected,List<Double> color,EmbeddedBaseColorTexture texture) {
        Appearance {color=List.copyOf(color);}
    }
    record Patch(int id,Appearance appearance,List<Slot> slots,List<Triangle> triangles,
            List<SurfaceProjection.Vertex> positions,List<Point2> uvs,List<Integer> indices) {
        Patch {
            slots=List.copyOf(slots);triangles=List.copyOf(triangles);positions=List.copyOf(positions);
            uvs=List.copyOf(uvs);indices=List.copyOf(indices);
        }
    }
    record Plan(List<Patch> patches,long expandedSlots,long indices) {
        Plan {patches=List.copyOf(patches);}
    }
    record Budget(int verticesPerPatch,int patches,long expandedSlots,long indices) {
        Budget {
            require(verticesPerPatch>=3 && verticesPerPatch<=65535 && patches>0 && patches<=4096
                && expandedSlots>=3 && expandedSlots<=200000 && indices>=3 && indices<=600000,"fixed-index allocation budget");
        }
    }
    static Plan compile(List<GlbSurfaceReader.Surface> surfaces,Budget budget) {
        surfaces=List.copyOf(surfaces);require(!surfaces.isEmpty(),"nonempty source required");
        var triangles=new ArrayList<Triangle>();long totalIndices=0;
        for(int surface=0;surface<surfaces.size();surface++) {
            var source=surfaces.get(surface);require(source.indices().size()%3==0,"source triangle indices");
            totalIndices=Math.addExact(totalIndices,source.indices().size());require(totalIndices<=budget.indices(),"fixed-index total index budget");
            for(int triangle=0;triangle<source.indices().size()/3;triangle++) {
                double z=0;for(int corner=0;corner<3;corner++)z+=source.positions().get(source.indices().get(3*triangle+corner)).z()/3;
                triangles.add(new Triangle(surface,triangle,z));
            }
        }
        require(!triangles.isEmpty(),"source has no triangles");
        triangles.sort(Comparator.comparingDouble(Triangle::centerZ).reversed());
        var patches=new ArrayList<Patch>();var slots=new LinkedHashMap<Slot,Integer>();
        var indices=new ArrayList<Integer>();var run=new ArrayList<Triangle>();Appearance appearance=null;long expanded=0;
        for(var triangle:triangles) {
            var source=surfaces.get(triangle.surface());var next=new Appearance(source.material(),source.doubleSided(),source.reflected(),source.baseColor(),source.texture());
            var corners=new ArrayList<Slot>();for(int i=0;i<3;i++)corners.add(new Slot(triangle.surface(),source.indices().get(triangle.triangle()*3+i)));
            long additions=corners.stream().distinct().filter(c->!slots.containsKey(c)).count();
            if(!run.isEmpty() && (!next.equals(appearance) || slots.size()+additions>budget.verticesPerPatch())) {
                expanded=flush(surfaces,patches,slots,run,indices,appearance,expanded,budget);slots.clear();run.clear();indices.clear();
            }
            appearance=next;run.add(triangle);for(var corner:corners)indices.add(slots.computeIfAbsent(corner,key->slots.size()));
        }
        if(!run.isEmpty())expanded=flush(surfaces,patches,slots,run,indices,appearance,expanded,budget);
        return new Plan(patches,expanded,totalIndices);
    }
    private static long flush(List<GlbSurfaceReader.Surface> surfaces,List<Patch> patches,LinkedHashMap<Slot,Integer> slots,
            List<Triangle> triangles,List<Integer> indices,Appearance appearance,long expanded,Budget budget) {
        expanded=Math.addExact(expanded,slots.size());require(expanded<=budget.expandedSlots(),"fixed-index expanded vertex budget");
        require(patches.size()<budget.patches(),"fixed-index patch budget");var keys=List.copyOf(slots.keySet());
        patches.add(new Patch(patches.size(),appearance,keys,triangles,
            keys.stream().map(k->surfaces.get(k.surface()).positions().get(k.vertex())).toList(),
            keys.stream().map(k->surfaces.get(k.surface()).textureUvs().get(k.vertex())).toList(),indices));return expanded;
    }
    private static void require(boolean value,String message) {if(!value)throw new IllegalArgumentException(message);}
}
