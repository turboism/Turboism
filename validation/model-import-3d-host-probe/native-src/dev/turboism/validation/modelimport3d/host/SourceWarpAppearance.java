package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.model.Point2;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

/** Original source texels and explicit derived shader coordinates for mixed-material Warp meshes.
 * Does not admit source visibility. Caller owns the whole-import native transaction. */
final class SourceWarpAppearance {
    private SourceWarpAppearance() { }
    static final int WIDTH=1024,HEIGHT=256;
    static final class Plan {
        final List<SourceWarpAuthoring.MeshInput> inputs;
        private final byte[] png;
        private Plan(List<SourceWarpAuthoring.MeshInput> inputs,byte[] png) {
            this.inputs=List.copyOf(inputs);this.png=png.clone();
        }
        AuthorizedImageContent imageGrant() { return AuthorizedImageContent.ofDimensions(png,"Original apple source textures packed without resampling",WIDTH,HEIGHT); }
    }
    static Plan prepare(List<GlbSurfaceReader.Surface> surfaces,SourceWarpFixture fixture) throws IOException {
        if(surfaces.size()!=2 || surfaces.stream().anyMatch(s->s.texture()==null || s.doubleSided()
                || !s.baseColor().equals(List.of(1d,1d,1d,1d))))
            throw new IllegalArgumentException("candidate requires two opaque source textures with identity base factors");
        var image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_ARGB);
        for(int component=0;component<2;component++) {
            var texture=surfaces.get(component).texture();
            if(texture.width()!=256 || texture.height()!=128)throw new IllegalArgumentException("source texture dimensions");
            for(int y=0;y<HEIGHT;y++)for(int x=0;x<512;x++) {
                int sx=Math.max(0,Math.min(255,x-1)),sy=Math.max(0,Math.min(127,y-1));
                image.setRGB(component*512+x,y,texture.pixels().get(sy*256+sx));
            }
        }
        var inputs=new ArrayList<SourceWarpAuthoring.MeshInput>();
        for(var mesh:fixture.meshes) {
            var original=mesh.geometry();var pixels=new ArrayList<Point2>();var shader=new ArrayList<Point2>();
            for(int i=0;i<mesh.sourceVertex.length;i++) {
                int component=mesh.slotIdentity[i*3],slot=mesh.slotIdentity[i*3+1];
                if(component<0 || component>=surfaces.size())throw new IllegalArgumentException("source material component");
                var surface=surfaces.get(component);
                if(slot<0 || slot>=surface.positions().size())throw new IllegalArgumentException("source material slot");
                var xyz=surface.positions().get(slot);var uv=surface.textureUvs().get(slot);
                if(xyz.x()!=mesh.source3D[i*3] || xyz.y()!=mesh.source3D[i*3+1] || xyz.z()!=mesh.source3D[i*3+2]
                    || uv.x()!=mesh.sourceUv[i*2] || uv.y()!=mesh.sourceUv[i*2+1] || uv.x()<0 || uv.x()>1 || uv.y()<0 || uv.y()>1)
                    throw new IllegalArgumentException("source geometry/UV provenance differs from material input");
                float x=(float)(component*512+1+256d*uv.x()),y=(float)(1+128d*uv.y());
                pixels.add(new Point2(x,y));shader.add(new Point2(x/WIDTH,y/HEIGHT));
            }
            inputs.add(new SourceWarpAuthoring.MeshInput(new ArtMeshGeometry(pixels,shader,original.triangleIndices()),original.positions()));
        }
        var bytes=new ByteArrayOutputStream();
        if(!ImageIO.write(image,"png",bytes))throw new IOException("source atlas PNG writer unavailable");
        return new Plan(inputs,bytes.toByteArray());
    }
    static List<ModelImageBinding> bind(CubismModel model,SourceWarpAuthoring.Created created,Plan plan) {
        SourceWarpAuthoring.verifyInputs(model,created,plan.inputs,Optional.empty());
        for(var id:created.meshIds())if(model.textures().textureInput(id).isPresent())
            throw new IllegalArgumentException("candidate material target already has an image");
        var bindings=new ArrayList<ModelImageBinding>();ModelImageBinding first=null;
        for(var id:created.meshIds()) {
            var binding=first==null ? model.textures().registerImageAndBind(plan.imageGrant(),id)
                : model.textures().bindModelImage(first.modelImageId(),id);
            if(binding==null || !binding.artMeshId().equals(Optional.of(id)))throw new IllegalStateException("image binding target");
            if(first!=null && (!binding.modelImageId().equals(first.modelImageId()) || !binding.rawImageId().equals(first.rawImageId())
                || !binding.modelId().equals(first.modelId()) || binding.modelGeneration()!=first.modelGeneration()))
                throw new IllegalStateException("shared source image identity changed");
            if(first==null)first=binding;bindings.add(binding);
            // Image registration may update source/default forms: require exact domain preservation.
            SourceWarpAuthoring.verifyInputs(model,created,plan.inputs,Optional.empty());
        }
        for(int i=0;i<bindings.size();i++) {
            var expected=bindings.get(i);var actual=model.textures().textureInput(created.meshIds().get(i)).orElseThrow();
            if(!actual.artMeshId().equals(created.meshIds().get(i)) || actual.kind()!=ArtMeshTextureInput.Kind.MODEL_IMAGE || !actual.modelImageId().equals(Optional.of(expected.modelImageId()))
                || !actual.modelId().equals(expected.modelId()) || actual.modelGeneration()!=expected.modelGeneration())
                throw new IllegalStateException("source image readback identity");
        }
        return List.copyOf(bindings);
    }
}
