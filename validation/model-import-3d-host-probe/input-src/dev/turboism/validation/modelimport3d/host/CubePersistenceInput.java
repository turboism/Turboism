package dev.turboism.validation.modelimport3d.host;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Explicit frozen persistence inputs; never infer texture layout from image appearance. */
enum CubePersistenceInput {
    ORIGINAL(CubeGeometryProbe.INPUT_SHA256,"FLAT_COLOR_INTERIOR",256,128,512,List.of(.5,.25,.5,.25)),
    SOURCE_TEXTURE("dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859",
        "EDGE_PADDED_POT",512,256,1024,List.of(.5,1d/512,.5,1d/256));

    final String sha256,layout;
    final int imageWidth,imageHeight,atlasSize;
    final List<Double> uvTransform;
    CubePersistenceInput(String sha256,String layout,int width,int height,int atlasSize,List<Double> uvTransform) {
        this.sha256=sha256;this.layout=layout;this.imageWidth=width;this.imageHeight=height;
        this.atlasSize=atlasSize;this.uvTransform=List.copyOf(uvTransform);
    }
    static CubePersistenceInput identify(String sha256) {
        for(var profile:values())if(profile.sha256.equals(sha256))return profile;
        throw new IllegalArgumentException("unapproved cube persistence input");
    }
    static byte[] readTexture(java.io.InputStream stream)throws IOException {
        byte[] bytes=java.util.Objects.requireNonNull(stream).readNBytes(16*1024*1024+1);
        if(bytes.length==0 || bytes.length>16*1024*1024)throw new IllegalArgumentException("persistence texture size");
        try {
            if(!SOURCE_TEXTURE.sha256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IllegalArgumentException("packaged persistence texture identity");
        } catch(NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
        return bytes;
    }
    static SurfaceImport.Prepared prepare(byte[] input)throws IOException {
        if(input==null || input.length==0 || input.length>16*1024*1024)
            throw new IllegalArgumentException("cube persistence input size");
        byte[] frozen=input.clone();
        try {identify(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frozen)));}
        catch(NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
        return SurfaceImport.prepare(frozen,new SurfaceImport.Request("MI3D Imported Cube",
            CubeGeometryProbe.YAW,CubeGeometryProbe.PITCH,CubeGeometryProbe.CAMERA,30,30,2_000_000));
    }
}
