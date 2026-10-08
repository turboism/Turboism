package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Decoded source texels in glTF UV order (top row first), never resized or flipped. */
record EmbeddedBaseColorTexture(int textureIndex, int imageIndex, int width, int height, List<Integer> pixels) {
    EmbeddedBaseColorTexture {
        pixels=List.copyOf(pixels);
        if(textureIndex<0 || imageIndex<0 || width<=0 || height<=0
                || (width&(width-1))!=0 || (height&(height-1))!=0
                || (long)width*height>AuthorizedImageContent.MAX_RGBA_BYTES/4L || pixels.size()!=(long)width*height
                || pixels.stream().anyMatch(p->(p>>>24)!=255)) {
            throw new IllegalArgumentException("opaque power-of-two base-color textures within the RGBA budget required");
        }
    }

    static EmbeddedBaseColorTexture decode(int texture,int image,byte[] png) {
        // Validate dimensions before invoking a decoder. PNG chunks are bounded
        // and CRC-checked; unsupported profiles/animation are never ignored.
        require(png!=null && png.length>=33,"PNG header bounds");
        var headerBytes=ByteBuffer.wrap(png);
        AuthorizedImageContent.ofDimensions(png,"embedded GLB source texture",headerBytes.getInt(16),headerBytes.getInt(20));
        ByteBuffer b=ByteBuffer.wrap(png);b.position(8);boolean header=false,end=false,idat=false;
        while(b.remaining()>=12 && !end) {
            int length=b.getInt(),start=b.position(),type=b.getInt();
            require(length>=0 && length<=b.remaining()-4,"PNG chunk bounds");
            CRC32 crc=new CRC32();crc.update(png,start,length+4);
            require((int)crc.getValue()==b.getInt(start+4+length),"PNG CRC");
            if(!header) {
                require(type==0x49484452 && length==13,"PNG IHDR");header=true;
                require((png[start+12]&255)==8 && ((png[start+13]&255)==2 || (png[start+13]&255)==6)
                    && png[start+14]==0 && png[start+15]==0 && png[start+16]==0,
                    "PNG requires 8-bit RGB/RGBA, standard compression/filter and no interlace");
            } else if(type==0x49444154)idat=true;
            else if(type==0x49454e44) {require(length==0 && idat,"PNG IEND");end=true;}
            else if(type==0x73524742)require(!idat && length==1 && (png[start+4]&255)<=3,"PNG sRGB chunk");
            else throw new IllegalArgumentException("unsupported PNG chunk; pre-bake as plain sRGB PNG");
            b.position(start+length+8);
        }
        require(header && idat && end && !b.hasRemaining(),"incomplete/trailing PNG data");
        try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            var readers=ImageIO.getImageReadersByFormatName("png");require(readers.hasNext(),"PNG reader unavailable");
            var reader=readers.next();
            try {
                reader.setInput(stream,true,true);var decoded=reader.read(0);require(decoded!=null,"PNG decode");
                int w=decoded.getWidth(),h=decoded.getHeight();var pixels=new ArrayList<Integer>(w*h);
                for(int y=0;y<h;y++)for(int x=0;x<w;x++)pixels.add(decoded.getRGB(x,y));
                return new EmbeddedBaseColorTexture(texture,image,w,h,pixels);
            } finally {reader.dispose();}
        } catch(IOException e) {throw new IllegalArgumentException("invalid embedded PNG",e);}
    }
    private static void require(boolean ok,String message) {if(!ok)throw new IllegalArgumentException(message);}
}
