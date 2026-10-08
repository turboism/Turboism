package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import javax.imageio.ImageIO;

/** Pure per-chart material remapping from original image coordinates. Original
 * face/corner UVs are sampled before derived-slot welding can erase a UV seam. */
final class SurfaceDerivedTexturePlan {
    private SurfaceDerivedTexturePlan() { }
    record Texture(byte[] png, int coveredPixels) {
        Texture { png = png.clone(); }
        @Override public byte[] png() { return png.clone(); }
        AuthorizedImageContent newGrant(String name) {
            return AuthorizedImageContent.ofDimensions(png,name,SurfaceDerivedPlanePlan.IMAGE_SIZE,SurfaceDerivedPlanePlan.IMAGE_SIZE);
        }
    }
    static Texture bake(SurfaceDerivedPlanePlan.Plan plane, ArtMeshGeometry originalImage,
            BufferedImage source) throws IOException {
        Objects.requireNonNull(plane); Objects.requireNonNull(originalImage); Objects.requireNonNull(source);
        if ((long)source.getWidth()*source.getHeight()>AuthorizedImageContent.MAX_RGBA_BYTES/4L)
            throw new IllegalArgumentException("original source image exceeds the decoded material budget");
        var original = plane.original();
        if (!original.indices().equals(originalImage.triangleIndices())
                || originalImage.positions().size() != original.sourcePositions().size())
            throw new IllegalArgumentException("complete original image corner provenance required");
        int size=SurfaceDerivedPlanePlan.IMAGE_SIZE;
        double[] pixels=new double[size*size*4];boolean[] covered=new boolean[size*size];
        var geometry=plane.geometry();int coveredCount=0;
        for(int corner=0;corner<original.indices().size();corner+=3) {
            var a=geometry.positions().get(geometry.triangleIndices().get(corner));
            var b=geometry.positions().get(geometry.triangleIndices().get(corner+1));
            var c=geometry.positions().get(geometry.triangleIndices().get(corner+2));
            var ua=originalImage.positions().get(original.indices().get(corner));
            var ub=originalImage.positions().get(original.indices().get(corner+1));
            var uc=originalImage.positions().get(original.indices().get(corner+2));
            double dx=b.x()-a.x(),dy=b.y()-a.y(),ex=c.x()-a.x(),ey=c.y()-a.y();
            double determinant=dx*ey-ex*dy;
            if(determinant==0)throw new IllegalArgumentException("derived texture plane has a zero-area original face");
            int minX=(int)Math.floor(Math.min(a.x(),Math.min(b.x(),c.x())));
            int minY=(int)Math.floor(Math.min(a.y(),Math.min(b.y(),c.y())));
            int maxX=(int)Math.ceil(Math.max(a.x(),Math.max(b.x(),c.x())));
            int maxY=(int)Math.ceil(Math.max(a.y(),Math.max(b.y(),c.y())));
            for(int y=minY;y<maxY;y++)for(int x=minX;x<maxX;x++) {
                double px=x+.5-a.x(),py=y+.5-a.y();
                double v=(px*ey-ex*py)/determinant,w=(dx*py-px*dy)/determinant,u=1-v-w;
                if(u < -1e-12 || v < -1e-12 || w < -1e-12)continue;
                double sx=u*ua.x()+v*ub.x()+w*uc.x(),sy=u*ua.y()+v*ub.y()+w*uc.y();
                int pixel=y*size+x,offset=pixel*4;
                for(int channel=0;channel<4;channel++) {
                    double value=sample(source,sx,sy,channel);
                    // Unused source-atlas padding may be transparent. Only the
                    // original material sampled by a face must be opaque.
                    if(channel==0 && value<255-1e-6)
                        throw new IllegalArgumentException("derived opaque chart material required");
                    if(covered[pixel]&&Math.abs(pixels[offset+channel]-value)>1e-6)
                        throw new IllegalArgumentException("overlapping derived faces disagree on original material mapping");
                    pixels[offset+channel]=value;
                }
                if(!covered[pixel])coveredCount++;covered[pixel]=true;
            }
        }
        var output=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        try {
            int border=SurfaceDerivedPlanePlan.GUTTER,end=SurfaceDerivedPlanePlan.CONTENT_SIZE-border;
            // Quad-chart admission requires complete rectangular coverage. Every
            // uncovered interior pixel is an error, never a hidden acceptance mask.
            for(int y=border;y<end;y++)for(int x=border;x<end;x++)
                if(!covered[y*size+x])throw new IllegalArgumentException("derived chart rectangle has missing material coverage");
            for(int y=0;y<SurfaceDerivedPlanePlan.CONTENT_SIZE;y++)for(int x=0;x<SurfaceDerivedPlanePlan.CONTENT_SIZE;x++) {
                int cx=Math.max(border,Math.min(end-1,x)),cy=Math.max(border,Math.min(end-1,y));
                int offset=(cy*size+cx)*4,argb=0;
                for(int channel=0;channel<4;channel++)argb|=((int)Math.rint(pixels[offset+channel]))<<(24-channel*8);
                output.setRGB(x,y,argb);
            }
            var encoded=new ByteArrayOutputStream();
            if(!ImageIO.write(output,"PNG",encoded))throw new IOException("derived PNG encoder unavailable");
            return new Texture(encoded.toByteArray(),coveredCount);
        } finally {output.flush();}
    }
    // Coordinates address source texel centers, matching the native bilinear
    // image sampler; clamping preserves the original material tile boundary.
    private static double sample(BufferedImage image,double x,double y,int channel) {
        x-=.5;y-=.5;int ix=(int)Math.floor(x),iy=(int)Math.floor(y);
        double dx=x-ix,dy=y-iy;
        return (at(image,ix,iy,channel)*(1-dx)+at(image,ix+1,iy,channel)*dx)*(1-dy)
            +(at(image,ix,iy+1,channel)*(1-dx)+at(image,ix+1,iy+1,channel)*dx)*dy;
    }
    private static int at(BufferedImage image,int x,int y,int channel) {
        int argb=image.getRGB(Math.max(0,Math.min(image.getWidth()-1,x)),Math.max(0,Math.min(image.getHeight()-1,y)));
        return (argb>>>(24-channel*8))&255;
    }
}
