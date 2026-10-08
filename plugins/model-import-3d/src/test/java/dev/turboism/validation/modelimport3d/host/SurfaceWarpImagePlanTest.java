package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceWarpImagePlanTest {
    @Test void sourceReflectionKeepsEveryTexelAndBarycentricMaterialSampleWithOriginalFacesAndWorldPositions() throws Exception {
        var source=source(0,true,List.of(1d,1d,1d,1d));var plan=SurfaceWarpImagePlan.compile(geometry(List.of(source)));
        var reflected=SurfaceWarpImagePlan.reflectVertically(plan);
        var pixels=ImageIO.read(new ByteArrayInputStream(plan.png()));
        var flipped=ImageIO.read(new ByteArrayInputStream(reflected.png()));
        for(int y=0;y<plan.height;y++)for(int x=0;x<plan.width;x++)assertEquals(pixels.getRGB(x,y),flipped.getRGB(x,plan.height-1-y));
        assertEquals(plan.meshImages,reflected.meshImages);assertEquals(plan.sourceMaterialTiles,reflected.sourceMaterialTiles);
        for(int m=0;m<plan.inputs.size();m++) {
            var a=plan.inputs.get(m);var b=reflected.inputs.get(m);
            assertEquals(a.defaultLocal(),b.defaultLocal());assertEquals(a.imageGeometry().triangleIndices(),b.imageGeometry().triangleIndices());
            for(int t=0;t<a.imageGeometry().triangleIndices().size();t+=3)for(float u:new float[]{0,.125f,.25f,.5f,1})for(float v:new float[]{0,.125f,.25f,.5f,1})if(u+v<=1) {
                float y=0,ry=0;float[] weights={1-u-v,u,v};
                for(int c=0;c<3;c++){int slot=a.imageGeometry().triangleIndices().get(t+c);y+=weights[c]*a.imageGeometry().uvs().get(slot).y();ry+=weights[c]*b.imageGeometry().uvs().get(slot).y();}
                assertEquals(1f-y,ry,1e-6,"entire triangle uses the same reflected material coordinate");
            }
        }
        var twice=SurfaceWarpImagePlan.reflectVertically(reflected);
        assertEquals(plan.inputs,twice.inputs);
        var restored=ImageIO.read(new ByteArrayInputStream(twice.png()));
        for(int y=0;y<plan.height;y++)for(int x=0;x<plan.width;x++)assertEquals(pixels.getRGB(x,y),restored.getRGB(x,y));
    }
    @Test void derivedChartsReuseOnlyExactImagesAndKeepOriginalCornerTopology() throws Exception {
        var a=source(0,false,List.of(.5d,.25d,.1d,1d));
        var b=source(1,false,List.of(.5d,.25d,.1d,1d));
        var geometry=SurfaceWarpLayerPlan.splitCharts(geometry(List.of(a,b)));
        var plan=SurfaceWarpImagePlan.compileDerived(geometry);
        assertEquals(576,plan.width);assertEquals(576,plan.height);
        assertEquals(1,plan.imageCount());assertEquals(List.of(0,0),plan.meshImages);
        for(int i=0;i<geometry.meshes().size();i++) {
            assertEquals(geometry.meshes().get(i).indices(),plan.inputs.get(i).imageGeometry().triangleIndices());
            assertEquals(geometry.meshes().get(i).warpLocal(),plan.inputs.get(i).defaultLocal());
        }
        var decoded=ImageIO.read(new ByteArrayInputStream(plan.png()));
        assertEquals(0,decoded.getRGB(575,575));assertEquals(SurfaceBaseColorPlan.argb(a.baseColor()),decoded.getRGB(4,4));
        byte[] copy=plan.png(0);copy[0]=0;assertEquals((byte)0x89,plan.png(0)[0]);
        var c=source(1,false,List.of(.5d,.25d,.2d,1d));
        var distinct=SurfaceWarpImagePlan.compileDerived(SurfaceWarpLayerPlan.splitCharts(geometry(List.of(a,c))));
        assertEquals(2,distinct.imageCount());assertEquals(List.of(0,1),distinct.meshImages);
        assertThrows(IllegalStateException.class,distinct::newGrant);
        assertEquals(576,distinct.newGrant(1).width());
        var third=source(2,false,List.of(.5d,.25d,.3d,1d));
        var padded=SurfaceWarpImagePlan.compileDerived(SurfaceWarpLayerPlan.splitCharts(geometry(List.of(a,c,third))));
        assertEquals(3,padded.imageCount());assertEquals(List.of(0,1,2),padded.meshImages);
    }
    static GlbSurfaceReader.Surface source(int id,boolean textured,List<Double> factor) {
        var pixels=new ArrayList<Integer>();for(int y=0;y<128;y++)for(int x=0;x<256;x++)pixels.add(0xff000000|x<<16|y<<8|(x^y));
        var texture=textured?new EmbeddedBaseColorTexture(id,id,256,128,pixels):null;
        return new GlbSurfaceReader.Surface(id,id,0,id,List.of(new SurfaceProjection.Vertex(0,0,id),new SurfaceProjection.Vertex(1,0,id),new SurfaceProjection.Vertex(1,1,id),new SurfaceProjection.Vertex(0,1,id)),List.of(),
            List.of(new Point2(0,0),new Point2(1,0),new Point2(1,1),new Point2(0,1)),List.of(0,1,2,0,2,3),factor,false,false,texture);
    }
    static SurfaceWarpChartPlan.Plan geometry(List<GlbSurfaceReader.Surface> sources) {return geometry(sources,65535);}
    static SurfaceWarpChartPlan.Plan geometry(List<GlbSurfaceReader.Surface> sources,int maxSlots) {
        var attributed=new ArrayList<SurfaceWarpChartPlan.ChartTriangle>();var p=List.of(new SurfaceWarpChartPlan.Parameter(0,0),new SurfaceWarpChartPlan.Parameter(1,0),new SurfaceWarpChartPlan.Parameter(1,1),new SurfaceWarpChartPlan.Parameter(0,1));
        for(int s=0;s<sources.size();s++) {
            attributed.add(new SurfaceWarpChartPlan.ChartTriangle(new SurfaceWarpChartPlan.Triangle(s,0),s,List.of(p.get(0),p.get(1),p.get(2))));
            attributed.add(new SurfaceWarpChartPlan.ChartTriangle(new SurfaceWarpChartPlan.Triangle(s,1),s,List.of(p.get(0),p.get(2),p.get(3))));
        }
        return SurfaceWarpChartPlan.compile(sources,attributed,attributed.stream().map(SurfaceWarpChartPlan.ChartTriangle::source).toList(),new SurfaceWarpChartPlan.Layout(sources.size(),1,sources.size(),3,1000000,maxSlots));
    }
    @Test void identityFactorsPreserveEverySourceTexelAndSeparateShaderFromWarpDomains() throws Exception {
        var sources=List.of(source(0,true,List.of(1d,1d,1d,1d)),source(1,true,List.of(1d,1d,1d,1d)));var geometry=geometry(sources);var plan=SurfaceWarpImagePlan.compile(geometry);var image=ImageIO.read(new ByteArrayInputStream(plan.png()));
        assertEquals(1024,plan.width);assertEquals(256,plan.height);assertEquals(List.of(0,1),plan.sourceMaterialTiles);
        for(int tile=0;tile<2;tile++)for(int y=0;y<256;y++)for(int x=0;x<512;x++)assertEquals(sources.get(tile).texture().pixels().get(Math.max(0,Math.min(127,y-1))*256+Math.max(0,Math.min(255,x-1))),image.getRGB(tile*512+x,y));
        for(int m=0;m<geometry.meshes().size();m++) {
            var original=geometry.meshes().get(m);var input=plan.inputs.get(m);assertEquals(original.warpLocal(),input.defaultLocal());assertEquals(original.indices(),input.imageGeometry().triangleIndices());
            for(int i=0;i<original.sourceUvs().size();i++) {
                var uv=original.sourceUvs().get(i);int tile=original.slotIdentities().get(i).surface();float x=tile*512+1+256*uv.x(),y=1+128*uv.y();
                assertEquals(new Point2(x,y),input.imageGeometry().positions().get(i));assertEquals(new Point2(x/1024,y/256),input.imageGeometry().uvs().get(i));
            }
        }
        assertEquals(1024,plan.newGrant().width());var bytes=plan.png();bytes[0]=0;assertEquals((byte)0x89,plan.png()[0]);
    }
    @Test void sharedMaterialsReuseOneTileAndDifferentSourceFactorsStayDistinct() throws Exception {
        var a=source(0,false,List.of(.5d,.25d,.1d,1d));var b=source(1,false,List.of(.5d,.25d,.1d,1d));var plan=SurfaceWarpImagePlan.compile(geometry(List.of(a,b)));
        assertEquals(512,plan.width);assertEquals(List.of(0,0),plan.sourceMaterialTiles);
        var image=ImageIO.read(new ByteArrayInputStream(plan.png()));assertEquals(SurfaceBaseColorPlan.argb(a.baseColor()),image.getRGB(100,100));
        var c=source(1,false,List.of(.5d,.25d,.2d,1d));assertEquals(1024,SurfaceWarpImagePlan.compile(geometry(List.of(a,c))).width);
    }
    @Test void alteredSourceUvIsRejectedBeforeImagePreparation() {
        var geometry=geometry(List.of(source(0,true,List.of(1d,1d,1d,1d))));var original=geometry.meshes().get(0);
        var changed=new ArrayList<>(original.sourceUvs());changed.set(0,new Point2(.125f,.125f));
        var mesh=new SurfaceWarpChartPlan.Mesh(original.sourcePositions(),changed,original.warpLocal(),original.slotIdentities(),original.indices(),original.triangles());
        var corrupt=new SurfaceWarpChartPlan.Plan(geometry.sources(),List.of(mesh),geometry.columns(),geometry.rows(),geometry.sourceControls(),geometry.maximumSourceResidual(),geometry.controlSamples());
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpImagePlan.compile(corrupt));
    }
    @Test void sourceTextureFactorUsesEstablishedConversionAndBudgetStaysBounded() throws Exception {
        var s=source(0,true,List.of(.5d,.25d,.1d,1d));var plan=SurfaceWarpImagePlan.compile(geometry(List.of(s)));var actual=ImageIO.read(new ByteArrayInputStream(plan.png()));var expected=ImageIO.read(new ByteArrayInputStream(SurfaceBaseColorPlan.sourceMaterial(s.texture(),s.baseColor()).png()));
        for(int y=0;y<256;y++)for(int x=0;x<512;x++)assertEquals(expected.getRGB(x,y),actual.getRGB(x,y));
        var sources=List.of(source(0,true,List.of(1d,1d,1d,1d)),source(1,true,List.of(1d,1d,1d,1d)),source(2,true,List.of(1d,1d,1d,1d)));
        var expanded=SurfaceWarpImagePlan.compile(geometry(sources));assertEquals(2048,expanded.width);
        assertEquals(2048,expanded.newGrant().width());
        var tooMany=java.util.stream.IntStream.range(0,9).mapToObj(i->source(i,true,List.of(1d,1d,1d,1d))).toList();
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpImagePlan.compile(geometry(tooMany)));
    }
    @Test void fullBudgetImageKeepsAllTexelsAndCorrectShaderCoordinatesWithoutPadding() throws Exception {
        var root=java.nio.file.Path.of(System.getProperty("mi3d.repo"));
        var cat=GlbSurfaceReader.read(java.nio.file.Files.readAllBytes(root.resolve("artifacts/cat-textured/source-r2/cat-textured.glb"))).get(0);
        // A mapping fixture exercises image packing, not cat projection/occlusion acceptance.
        var fixture=source(0,false,List.of(1d,1d,1d,1d));
        var mapped=new GlbSurfaceReader.Surface(0,0,0,0,fixture.positions(),fixture.sourceNormals(),
            fixture.textureUvs(),fixture.indices(),fixture.baseColor(),false,false,cat.texture());
        var geometry=geometry(List.of(mapped));var plan=SurfaceWarpImagePlan.compile(geometry);
        assertEquals(1024,plan.width);assertEquals(1024,plan.height);
        var image=ImageIO.read(new ByteArrayInputStream(plan.png()));
        for(int y=0;y<1024;y++)for(int x=0;x<1024;x++)assertEquals(cat.texture().pixels().get(y*1024+x),image.getRGB(x,y));
        var mesh=geometry.meshes().get(0);var input=plan.inputs.get(0);
        for(int i=0;i<mesh.sourceUvs().size();i++) {
            var uv=mesh.sourceUvs().get(i);
            assertEquals(uv,input.imageGeometry().uvs().get(i));
            assertEquals(new Point2(uv.x()*1024,uv.y()*1024),input.imageGeometry().positions().get(i));
        }
        var grant=plan.newGrant();assertEquals(1024,grant.width());assertEquals(1024,grant.height());
        var other=source(1,false,List.of(1d,1d,1d,1d));
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpImagePlan.compile(geometry(List.of(mapped,other))));
    }
}
