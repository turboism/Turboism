package dev.turboism.validation.modelimport3d.host;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.*;import java.nio.file.*;import javax.imageio.ImageIO;import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class SurfaceVertexAuthoringTest {
    static SurfaceImport.Request request(double scale,boolean roll) {
        return new SurfaceImport.Request("Vertex source",new ParameterId("Yaw"),new ParameterId("Pitch"),new SurfaceProjection.Camera(512,512,scale,new SurfaceProjection.Vertex(0,0,0)),180,180,20000000,roll?Optional.of(new ParameterId("Roll")):Optional.empty());
    }
    @Test void densityUsesSmallestLatticeWithinTheExistingGlobalPixelBudget() {
        double budget=2*SurfaceVertexAuthoring.chordError(400,3)+.01;
        for(double radius:List.of(0d,20d,80d,150d,200d,280d,400d)) {
            var density=SurfaceVertexAuthoring.sampling(radius,budget);
            assertTrue(density.errorBoundPixels()<=budget);
            for(int y=0;y<=3;y++)for(int x=0;x<=3;x++) {
                double error=SurfaceVertexAuthoring.chordError(radius,y)+SurfaceVertexAuthoring.chordError(radius,x)+.01;
                if(error<=budget)assertTrue(density.grid()<=(9+8L*y)*(9+8L*x));
            }
        }
        assertEquals(1089,SurfaceVertexAuthoring.sampling(400,budget).grid());
        assertTrue(SurfaceVertexAuthoring.sampling(150,budget).grid()<1089);
        assertThrows(IllegalArgumentException.class,()->SurfaceVertexAuthoring.sampling(Double.NaN,budget));
        assertThrows(IllegalArgumentException.class,()->SurfaceVertexAuthoring.sampling(400,.01));
    }

    @Test void directImageCoordinatesAndNeutralProjectionKeepIndependentDomains() throws Exception {
        var sources=List.of(SurfaceQuadChartDiscoveryTest.cube(2,0));var p=SurfaceVertexAuthoring.prepareDecoded(sources,request(100,true),12);
        assertEquals(p.geometry().meshes().size(),p.sampling().size());
        assertTrue(p.sampling().stream().allMatch(d->d.errorBoundPixels()<=p.interpolationErrorBoundPixels()));
        assertEquals(81,p.orders().size());assertTrue(p.interpolationErrorBoundPixels()<5);assertEquals(9,SurfaceVertexAuthoring.KEYS.size());
        for(int m=0;m<p.geometry().meshes().size();m++) {
            var mesh=p.geometry().meshes().get(m);var image=p.images().inputs.get(m);assertEquals(mesh.indices(),image.imageGeometry().triangleIndices());
            assertEquals(SurfaceProjection.project(mesh.positions(),p.request().camera(),new SurfaceProjection.Pose(0,0)).positions(),image.defaultLocal());
            assertEquals(mesh.positions().size(),image.imageGeometry().positions().size());
        }
        for(var row:p.orders()){assertEquals(p.geometry().meshes().size(),new HashSet<>(row).size());assertTrue(row.stream().allMatch(order->order>=0 && order<=1000));}
        assertThrows(IllegalArgumentException.class,()->SurfaceVertexAuthoring.prepareDecoded(sources,request(100,false),12));
        assertThrows(IllegalArgumentException.class,()->SurfaceVertexAuthoring.prepareDecoded(sources,request(10000,true),12));
    }
    @Test void catPreparationPreservesOriginalTextureAndEverySourceUVWithoutFitting() throws Exception {
        var root=Path.of(System.getProperty("mi3d.repo"));var p=SurfaceVertexAuthoring.prepareFramed(Files.readAllBytes(root.resolve("artifacts/cat-textured/source-r2/cat-textured.glb")),request(1,true),512);
        assertEquals(512,p.geometry().meshes().size());assertEquals(48497,p.geometry().repeatedSlots());assertEquals(1024,p.images().width);assertEquals(1024,p.images().height);
        assertTrue(p.interpolationErrorBoundPixels()<4);assertTrue(1089*p.geometry().repeatedSlots()<=SurfaceVertexAuthoring.MAX_CACHE_SLOTS);
        var original=ImageIO.read(root.resolve("artifacts/cat-textured/source-r2/Cat_diffuse.png").toFile());var actual=ImageIO.read(new ByteArrayInputStream(p.images().png()));
        assertArrayEquals(original.getRGB(0,0,1024,1024,null,0,1024),actual.getRGB(0,0,1024,1024,null,0,1024));
        int triangles=0;
        for(int m=0;m<p.geometry().meshes().size();m++) {
            var mesh=p.geometry().meshes().get(m);var image=p.images().inputs.get(m);triangles+=mesh.triangles().size();assertEquals(mesh.indices(),image.imageGeometry().triangleIndices());
            for(int v=0;v<mesh.uvs().size();v++) {
                var uv=mesh.uvs().get(v);assertEquals(uv,image.imageGeometry().uvs().get(v));
                assertEquals(new Point2(1024*uv.x(),1024*uv.y()),image.imageGeometry().positions().get(v));
            }
        }
        assertEquals(70576,triangles);
        long adaptive=0,fixed=1089*p.geometry().repeatedSlots(),forms=0;
        for(int i=0;i<p.sampling().size();i++){adaptive+=p.sampling().get(i).grid()*p.geometry().meshes().get(i).positions().size();forms+=p.sampling().get(i).grid();}
        assertTrue(adaptive<fixed);assertTrue(forms<557568);
        System.out.println("CAT_ADAPTIVE_PREPARED sourceForms=81 meshes=512 slots="+p.geometry().repeatedSlots()+" fixedCacheSlots="+fixed+" adaptiveCacheSlots="+adaptive+" forms="+forms+" jointErrorBound="+p.interpolationErrorBoundPixels()+" nativeAccepted=false");
    }
}
