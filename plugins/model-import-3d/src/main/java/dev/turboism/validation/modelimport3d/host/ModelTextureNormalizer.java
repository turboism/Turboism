package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounded opaque PNG/JPEG to plain RGB PNG. Keeps decoded size; never resizes. */
final class ModelTextureNormalizer {
    static byte[] png(byte[] input) throws IOException {
        if(input==null || input.length<8 || input.length>AuthorizedImageContent.MAX_PNG_BYTES)
            throw new IllegalArgumentException("贴图文件大小超限。");
        try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            var readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext())throw new IllegalArgumentException("贴图需要 PNG 或 JPEG。");
            var reader=readers.next();
            try {
                String format=reader.getFormatName();
                if(!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg") && !format.equalsIgnoreCase("jpg"))throw new IllegalArgumentException("贴图需要 PNG 或 JPEG。");
                reader.setInput(stream,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<=0 || h<=0 || (w&(w-1))!=0 || (h&(h-1))!=0 || (long)w*h>AuthorizedImageContent.MAX_RGBA_BYTES/4)
                    throw new IllegalArgumentException("当前贴图要求二次幂尺寸，解码后最多 1,048,576 像素。");
                var decoded=reader.read(0);var rgb=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
                for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                    int pixel=decoded.getRGB(x,y);if((pixel>>>24)!=255)throw new IllegalArgumentException("暂不支持透明贴图。");rgb.setRGB(x,y,pixel);
                }
                var output=new ByteArrayOutputStream();if(!ImageIO.write(rgb,"png",output))throw new IOException("PNG encoder unavailable");
                byte[] png=output.toByteArray();if(png.length>AuthorizedImageContent.MAX_PNG_BYTES)throw new IllegalArgumentException("规范化 PNG 超限。");return png;
            } finally {reader.dispose();}
        }
    }
}
