package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import javax.imageio.ImageIO;

/** Opaque GLB base color and bounded source PNG to sRGB PNG. No lighting or document access. */
final class SurfaceBaseColorPlan {
    private SurfaceBaseColorPlan() { }
    enum Appearance { SOURCE_MATERIAL, FACE_COLORS }
    static final List<Integer> FACE_PALETTE=List.of(0xffdc3232,0xff32c850,0xff325adc,0xffdcb432,0xffbe3cc8,0xff32c8c8);

    static final class Material {
        private final int argb;
        private final byte[] png;
        private final boolean textured;
        private Material(int argb, byte[] png) { this(argb,png,false); }
        private Material(int argb, byte[] png,boolean textured) { this.argb=argb;this.png=png.clone();this.textured=textured; }
        boolean textured() { return textured; }
        int argb() { return argb; }
        byte[] png() { return png.clone(); }
        int width() { return java.nio.ByteBuffer.wrap(png).getInt(16); }
        int height() { return java.nio.ByteBuffer.wrap(png).getInt(20); }
        AuthorizedImageContent newGrant() {
            return AuthorizedImageContent.ofDimensions(png, "GLB base color " + Integer.toHexString(argb),width(),height());
        }
    }
    record PatchMaterial(SurfacePatchPlan.Patch source, int surfaceIndex, int sourceMaterial, Material material) { }
    record Plan(List<PatchMaterial> patches, List<Material> materials) {
        Plan { patches=List.copyOf(patches);materials=List.copyOf(materials); }
    }

    static Plan compile(List<GlbSurfaceReader.Surface> surfaces, SurfaceImportPlan.Plan geometry) throws IOException {
        return compile(surfaces,geometry,Appearance.SOURCE_MATERIAL);
    }

    static Plan compile(List<GlbSurfaceReader.Surface> surfaces, SurfaceImportPlan.Plan geometry,
            Appearance appearance) throws IOException {
        java.util.Objects.requireNonNull(appearance,"appearance");
        if(surfaces.isEmpty() || geometry.patches().isEmpty())throw new IllegalArgumentException("empty material input");
        record MaterialKey(int argb, List<Double> factor, EmbeddedBaseColorTexture texture) { }
        var materials=new LinkedHashMap<MaterialKey,Material>();var patches=new ArrayList<PatchMaterial>();
        int index=0;
        for(int s=0;s<surfaces.size();s++) {
            var surface=surfaces.get(s);
            if(appearance==Appearance.SOURCE_MATERIAL && surface.texture()!=null
                && (surface.texture().width()!=256 || surface.texture().height()!=128))
                throw new IllegalArgumentException("large source textures require the source Warp image mapping");
            for(var patch:SurfacePatchPlan.plan(surface)) {
                if(index>=geometry.patches().size() || geometry.patches().get(index).surfaceIndex()!=s
                        || !geometry.patches().get(index).source().equals(patch)) {
                    throw new IllegalArgumentException("material source differs from geometry plan");
                }
                int argb=appearance==Appearance.SOURCE_MATERIAL ? argb(surface.baseColor())
                    : FACE_PALETTE.get(index % FACE_PALETTE.size());
                var texture=appearance==Appearance.SOURCE_MATERIAL ? surface.texture() : null;
                // Keep texture source identities and exact linear factors in the key:
                // rounded solid-color bytes cannot identify a textured material.
                var key=new MaterialKey(argb,texture==null ? List.of() : surface.baseColor(),texture);
                var material=materials.get(key);
                if(material==null) { material=new Material(argb,texture==null ? png(argb) : texturedPng(texture,surface.baseColor()),texture!=null);materials.put(key,material); }
                patches.add(new PatchMaterial(patch,s,surface.material(),material));index++;
            }
        }
        if(index!=geometry.patches().size())throw new IllegalArgumentException("material patch inventory differs");
        return new Plan(patches,List.copyOf(materials.values()));
    }

    /** One source material, preserving the existing texture/factor conversion exactly. */
    static Material sourceMaterial(EmbeddedBaseColorTexture texture,List<Double> factor) throws IOException {
        int color=argb(factor);
        return new Material(color,texture==null ? png(color) : texturedPng(texture,factor),texture!=null);
    }

    /** Fixed diagnostic colors for visibility probes; never substitutes for a decoded source material. */
    static Material diagnosticSrgb(int argb) throws IOException {
        if((argb>>>24)!=255)throw new IllegalArgumentException("opaque diagnostic color required");
        return new Material(argb,png(argb));
    }

    static int argb(List<Double> linear) {
        if(linear.size()!=4 || linear.stream().anyMatch(v->v==null || !Double.isFinite(v) || v<0 || v>1)
                || linear.get(3)!=1d)throw new IllegalArgumentException("only finite opaque base colors are supported");
        return 0xff000000 | channel(linear.get(0))<<16 | channel(linear.get(1))<<8 | channel(linear.get(2));
    }
    private static int channel(double linear) {
        double srgb=linear<=.0032568 ? 12.92*linear : 1.055*Math.pow(linear,1/2.4)-.055;
        return (int)Math.round(255*srgb);
    }
    private static byte[] texturedPng(EmbeddedBaseColorTexture texture,List<Double> factor) throws IOException {
        int width=1,height=1;
        while(width<texture.width()+2)width*=2;
        while(height<texture.height()+2)height*=2;
        // A full-budget source image remains unpadded and uses native CLAMP.
        // No source pixel is resized, dropped or shifted without matching UVs.
        int border=1;
        if((long)width*height>AuthorizedImageContent.MAX_RGBA_BYTES/4L) {
            width=texture.width();height=texture.height();border=0;
        }
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        // Pad within budget, otherwise retain the complete clamp-sampled image.
        // Fill added pixels by edge replication; never resample source texels.
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
            int sx=Math.max(0,Math.min(texture.width()-1,x-border));
            int sy=Math.max(0,Math.min(texture.height()-1,y-border));
            int source=texture.pixels().get(sy*texture.width()+sx),pixel=0xff000000;
            for(int c=0;c<3;c++) {
                int shift=16-8*c;double srgb=((source>>>shift)&255)/255d;
                double linear=srgb<=.04045 ? srgb/12.92 : Math.pow((srgb+.055)/1.055,2.4);
                pixel|=channel(linear*factor.get(c))<<shift;
            }
            image.setRGB(x,y,pixel);
        }
        var bytes=new ByteArrayOutputStream();
        if(!ImageIO.write(image,"png",bytes))throw new IOException("PNG encoder unavailable");
        return bytes.toByteArray();
    }

    private static byte[] png(int argb) throws IOException {
        int width=AuthorizedImageContent.REQUIRED_WIDTH,height=AuthorizedImageContent.REQUIRED_HEIGHT;
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        int[] pixels=new int[width*height];java.util.Arrays.fill(pixels,argb);
        image.setRGB(0,0,width,height,pixels,0,width);
        var bytes=new ByteArrayOutputStream();
        if(!ImageIO.write(image,"png",bytes))throw new IOException("PNG encoder unavailable");
        return bytes.toByteArray();
    }
}
