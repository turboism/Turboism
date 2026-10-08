package dev.turboism.validation.modelimport3d.host;

import java.nio.file.*;
import java.util.*;
import dev.turboism.protocol.json.StrictJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceImportCostPlanTest {
    @Test void editableChoiceAccountsForRetainedSourceCornersAndDerivedLattice() throws Exception {
        var root=Path.of(System.getProperty("mi3d.repo"));
        var source=GlbSurfaceReader.read(Files.readAllBytes(root.resolve("artifacts/cat-textured/source-r2/cat-textured.glb")));
        var camera=SurfaceAutoCamera.fit(source,512,512,400);var base=SurfaceVertexAuthoringTest.request(1,true);
        var request=new SurfaceImport.Request(base.name(),base.yaw(),base.pitch(),camera,180,180,base.maxProjectedVertices(),base.roll());
        var choice=SurfaceImportCostPlan.chooseEditable(source,request,512,1001);
        var editable=SurfaceWarpImagePlan.compileEditableVertex(choice.geometry(),camera,1001);
        assertEquals(choice.geometry().meshes(),editable.geometry().meshes(),"cost must measure the final source-plane partition");
        assertEquals(choice.geometry().repeatedSlots(),editable.geometry().repeatedSlots());
        var prepared=SurfaceVertexAuthoring.prepareGeometry(request,editable.geometry(),editable.images(),choice.interpolationAllowance());
        long forms=0,slots=0;
        for(int i=0;i<prepared.sampling().size();i++) {
            forms+=prepared.sampling().get(i).grid();
            slots+=prepared.sampling().get(i).grid()*prepared.geometry().meshes().get(i).positions().size();
        }
        assertEquals(choice.forms(),forms);assertEquals(choice.cacheSlots(),slots);
        assertEquals(8*slots+80*forms,choice.storageBytes());
        assertTrue(choice.storageBytes()<304011176L);
        assertTrue(choice.simplified().maximumGeometryErrorPixels()+prepared.interpolationErrorBoundPixels()<=5);
        var expected=new HashSet<SurfaceVertexPlan.Triangle>();
        for(int s=0;s<choice.simplified().surfaces().size();s++)
            for(int face=0;face<choice.simplified().surfaces().get(s).indices().size()/3;face++)expected.add(new SurfaceVertexPlan.Triangle(s,face));
        var actual=new HashSet<SurfaceVertexPlan.Triangle>();
        for(var mesh:choice.geometry().meshes())for(var face:mesh.triangles())assertTrue(actual.add(face));
        assertEquals(expected,actual);
        assertThrows(IllegalArgumentException.class,()->SurfaceImportCostPlan.chooseEditable(source,request,512,1));
        System.out.println("CAT_EDITABLE_COST_CHOICE meshes="+choice.geometry().meshes().size()+" forms="+forms+" slots="+slots+" estimatedBytes="+choice.storageBytes());
    }
    @Test void rejectsMissingRollAndInfeasibleInterpolationBeforeSimplification() {
        var source=List.of(SurfaceQuadChartDiscoveryTest.cube(2,0));
        assertThrows(IllegalArgumentException.class,()->SurfaceImportCostPlan.choose(source,SurfaceVertexAuthoringTest.request(100,false),12));
        assertThrows(IllegalArgumentException.class,()->SurfaceImportCostPlan.choose(source,SurfaceVertexAuthoringTest.request(10000,true),12));
    }
    @Test void realCatCostChoicePreservesBudgetAndIsDeterministic() throws Exception {
        var root=Path.of(System.getProperty("mi3d.repo"));
        var source=GlbSurfaceReader.read(Files.readAllBytes(root.resolve("artifacts/cat-textured/source-r2/cat-textured.glb")));
        var camera=SurfaceAutoCamera.fit(source,512,512,400);var base=SurfaceVertexAuthoringTest.request(1,true);
        var request=new SurfaceImport.Request(base.name(),base.yaw(),base.pitch(),camera,180,180,base.maxProjectedVertices(),base.roll());
        var chosen=SurfaceImportCostPlan.choose(source,request,512);
        var repeated=SurfaceImportCostPlan.choose(source,request,512);
        assertEquals(chosen,repeated);
        assertEquals(70576,chosen.simplified().sourceTriangles());
        assertTrue(chosen.simplified().outputTriangles()<70576);
        assertTrue(chosen.storageBytes()<304011176L,"must beat the full legacy form/coordinate storage estimate");
        assertTrue(chosen.simplified().maximumTextureErrorTexels()<=.5);
        for(int i=0;i<source.size();i++)assertEquals(source.get(i).texture(),chosen.simplified().surfaces().get(i).texture());
        var prepared=SurfaceVertexAuthoring.prepareGeometry(request,chosen.geometry(),
            SurfaceWarpImagePlan.compileVertex(chosen.geometry(),camera),chosen.interpolationAllowance());
        assertTrue(prepared.interpolationErrorBoundPixels()+chosen.simplified().maximumGeometryErrorPixels()<=5);
        long forms=0,slots=0;
        for(int i=0;i<prepared.sampling().size();i++) {
            forms+=prepared.sampling().get(i).grid();
            slots+=prepared.sampling().get(i).grid()*prepared.geometry().meshes().get(i).positions().size();
        }
        assertEquals(chosen.forms(),forms);assertEquals(chosen.cacheSlots(),slots);
        var directory=root.resolve("build/model-import-3d-offline/cat-cost-plan-candidate-r1-20261004");Files.createDirectories(directory);
        var surfaces=chosen.simplified().surfaces().stream().map(s->Map.of("positions",s.positions().stream().map(p->List.of(p.x(),p.y(),p.z())).toList(),
            "uvs",s.textureUvs().stream().map(p->List.of(p.x(),p.y())).toList(),"indices",s.indices())).toList();
        Files.write(directory.resolve("geometry.json"),StrictJson.bytes(Map.of("sourceSha256","4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7",
            "sourceTriangles",70576,"outputTriangles",chosen.simplified().outputTriangles(),"scale",camera.scale(),"geometryBoundPixels",chosen.simplified().maximumGeometryErrorPixels(),
            "textureBudgetTexels",.5,"maximumTextureErrorTexels",chosen.simplified().maximumTextureErrorTexels(),"partitionSlots",chosen.geometry().repeatedSlots(),"surfaces",surfaces)));
        var sampling=new StringBuilder("forms="+forms+"\n");
        for(int i=0;i<prepared.sampling().size();i++){var d=prepared.sampling().get(i);sampling.append("mesh.").append(i).append('=').append(d.yaw().insertPointCount()).append(',').append(d.pitch().insertPointCount()).append(',').append(d.grid()).append('\n');}
        Files.writeString(directory.resolve("sampling.properties"),sampling);
        System.out.println("CAT_COST_PLAN triangles="+chosen.simplified().outputTriangles()+" slots="+chosen.geometry().repeatedSlots()+" forms="+forms+" cacheSlots="+slots+
            "estimatedStorage="+chosen.storageBytes()+" combinedBound="+(prepared.interpolationErrorBoundPixels()+chosen.simplified().maximumGeometryErrorPixels())+" nativeAccepted=false");
    }
}
