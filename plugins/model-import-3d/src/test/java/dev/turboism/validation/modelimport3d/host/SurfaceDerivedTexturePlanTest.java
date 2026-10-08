package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceDerivedTexturePlanTest {
    @Test void unusedTransparentSourcePaddingDoesNotRejectAnOpaqueChart() throws Exception {
        var mesh=mesh(false);var image=source();image.setRGB(0,0,0);
        var coordinates=java.util.Collections.nCopies(4,new Point2(1.5f,1.5f));
        var texture=SurfaceDerivedTexturePlan.bake(SurfaceDerivedPlanePlan.compile(mesh),coordinates(mesh,coordinates),image);
        var decoded=ImageIO.read(new ByteArrayInputStream(texture.png()));
        assertEquals(0xff0000ff,decoded.getRGB(4,4));assertEquals(0,decoded.getRGB(575,575));
    }
    private static SurfaceWarpChartPlan.Mesh mesh(boolean duplicate) {
        var local=new ArrayList<>(List.of(new Point2(0,0),new Point2(1,0),new Point2(1,1),new Point2(0,1)));
        if(duplicate)local.addAll(List.copyOf(local));
        var xyz=local.stream().map(p->new SurfaceProjection.Vertex(p.x(),p.y(),0)).toList();
        var slots=java.util.stream.IntStream.range(0,local.size()).mapToObj(i->new SurfaceWarpChartPlan.Slot(0,i,0)).toList();
        var faces=duplicate?List.of(0,1,2,0,2,3,4,5,6,4,6,7):List.of(0,1,2,0,2,3);
        var triangles=java.util.stream.IntStream.range(0,faces.size()/3).mapToObj(i->new SurfaceWarpChartPlan.Triangle(0,i)).toList();
        return new SurfaceWarpChartPlan.Mesh(xyz,local,local,slots,faces,triangles);
    }
    private static BufferedImage source() {
        var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<2;y++){image.setRGB(0,y,0xffff0000);image.setRGB(1,y,0xff0000ff);}
        return image;
    }
    private static ArtMeshGeometry coordinates(SurfaceWarpChartPlan.Mesh mesh,List<Point2> positions) {
        return new ArtMeshGeometry(positions,positions.stream().map(p->new Point2(p.x()/2,p.y()/2)).toList(),mesh.indices());
    }
    @Test void originalZeroAreaUvFacesAreSampledAndAllGutterTexelsRetained() throws Exception {
        var mesh=mesh(false);var plane=SurfaceDerivedPlanePlan.compile(mesh);
        var positions=java.util.Collections.nCopies(4,new Point2(.5f,.5f));
        var result=SurfaceDerivedTexturePlan.bake(plane,coordinates(mesh,positions),source());
        assertEquals(512*512,result.coveredPixels());
        var image=ImageIO.read(new ByteArrayInputStream(result.png()));
        assertEquals(576,image.getWidth());assertEquals(576,image.getHeight());
        for(int y=0;y<576;y++)for(int x=0;x<576;x++)if(x>=520||y>=520)assertEquals(0,image.getRGB(x,y));
        for(int y=0;y<520;y++)for(int x=0;x<520;x++)assertEquals(0xffff0000,image.getRGB(x,y));
        var bytes=result.png();bytes[0]=0;assertNotEquals(0,result.png()[0]);
        assertEquals(576,result.newGrant("source UV mapping").width());
        assertEquals(6,plane.geometry().triangleIndices().size());
    }
    @Test void overlappingFacesWithDistinctOriginalMaterialUvsAreRejected() {
        var mesh=mesh(true);var plane=SurfaceDerivedPlanePlan.compile(mesh);
        var positions=new ArrayList<>(java.util.Collections.nCopies(4,new Point2(.5f,.5f)));
        positions.addAll(java.util.Collections.nCopies(4,new Point2(1.5f,.5f)));
        assertThrows(IllegalArgumentException.class,()->SurfaceDerivedTexturePlan.bake(
            plane,coordinates(mesh,positions),source()));
        assertEquals(12,plane.geometry().triangleIndices().size());
    }
    @Test void rejectsMissingOriginalFacesAndTransparentUnsupportedMaterial() {
        var mesh=mesh(false);var plane=SurfaceDerivedPlanePlan.compile(mesh);
        var image=source();image.setRGB(0,0,0);
        assertThrows(IllegalArgumentException.class,()->SurfaceDerivedTexturePlan.bake(
            plane,coordinates(mesh,mesh.warpLocal()),image));
        var truncated=new ArtMeshGeometry(mesh.warpLocal(),mesh.sourceUvs(),List.of(0,1,2));
        assertThrows(IllegalArgumentException.class,()->SurfaceDerivedTexturePlan.bake(plane,truncated,source()));
    }
}
