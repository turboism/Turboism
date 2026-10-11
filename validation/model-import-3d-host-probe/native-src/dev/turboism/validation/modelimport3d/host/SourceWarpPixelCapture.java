package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismRenderedPixels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Capture the actual native textured candidate; an independent source oracle must assess pixels. */
final class SourceWarpPixelCapture {
    private SourceWarpPixelCapture() { }
    static void capture(SourceWarpAuthoring.Created created,SourceWarpAppearance.Plan images,
            Supplier<CubismModel> current,Supplier<HistorySnapshot> history,SurfaceProjection.Pose pose,
            Path directory,int ordinal,Map<String,Object> data) throws Exception {
        if(ordinal<0 || ordinal>=11)throw new IllegalArgumentException("bounded candidate render ordinal");
        for(var id:created.meshIds()) {
            var requested=HostProbe.edt(()->current.get().drawables().find(id).evaluationSnapshot());
            requirePose(requested.liveParameters(),pose);
            var evidence=new LinkedHashMap<String,Object>();data.put("consumption-"+id.value(),evidence);
            var actual=EvaluationProbe.awaitConsumed(requested,()->HostProbe.edt(()->current.get().drawables().find(id).evaluationSnapshot()),evidence::putAll,System::nanoTime,Thread::sleep);
            requirePose(actual.consumedParameters(),pose);
        }
        var pixels=HostProbe.edt(()->{
            var model=current.get();
            NativeAssertions.require(model.canvas().widthPixels()==1024 && model.canvas().heightPixels()==1024,"candidate pixel capture canvas");
            SourceWarpAuthoring.verifyInputs(model,created,images.inputs);var bindingsBefore=verifyBindings(model,created);
            var before=history.get();data.put("historyBefore",HostProbe.historyData(before));
            var raster=model.renderPixels(1024,1024);
            SourceWarpAuthoring.verifyInputs(model,created,images.inputs);
            NativeAssertions.require(bindingsBefore.equals(verifyBindings(model,created)),"candidate render changed image inputs");
            var after=history.get();data.put("historyAfter",HostProbe.historyData(after));
            NativeAssertions.require(before.equals(after),"candidate native render changed history");
            for(var id:created.meshIds()) {
                var observation=model.drawables().find(id).evaluationSnapshot();
                requirePose(observation.liveParameters(),pose);requirePose(observation.consumedParameters(),pose);
            }
            return raster;
        });
        Files.createDirectories(directory);byte[] png=encode(pixels);
        var file=directory.resolve(ordinal+".png");Files.write(file,png,StandardOpenOption.CREATE_NEW);
        data.put("pose",java.util.List.of(pose.yaw(),pose.pitch()));data.put("artifact",directory.getFileName()+"/"+file.getFileName());
        data.put("artifactBytes",png.length);data.put("artifactSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png)));
        data.put("status","CAPTURED_NATIVE_NOT_YET_INDEPENDENTLY_VERIFIED");
    }
    private static void requirePose(dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState state,SurfaceProjection.Pose pose) {
        var values=new java.util.HashMap<String,Float>();for(var value:state.parameters())values.put(value.parameterId().value(),value.value());
        NativeAssertions.require(Float.valueOf((float)pose.yaw()).equals(values.get(SourceWarpCandidateProbe.YAW.value()))
            && Float.valueOf((float)pose.pitch()).equals(values.get(SourceWarpCandidateProbe.PITCH.value())),"candidate render parameter live/consumed mismatch");
    }
    static java.util.List<dev.turboism.sdk.cubism.model.ArtMeshTextureInput> verifyBindings(CubismModel model,SourceWarpAuthoring.Created created) {
        var inputs=new java.util.ArrayList<dev.turboism.sdk.cubism.model.ArtMeshTextureInput>();
        dev.turboism.sdk.cubism.model.ArtMeshTextureInput first=null;
        for(var id:created.meshIds()) {
            var actual=model.textures().textureInput(id).orElseThrow();
            NativeAssertions.require(actual.artMeshId().equals(id) && actual.kind()==dev.turboism.sdk.cubism.model.ArtMeshTextureInput.Kind.MODEL_IMAGE,"candidate render source image missing");
            if(first!=null)NativeAssertions.require(actual.modelImageId().equals(first.modelImageId()) && actual.modelId().equals(first.modelId()) && actual.modelGeneration()==first.modelGeneration(),"candidate render image ownership differs");
            first=actual;inputs.add(actual);
        }
        return java.util.List.copyOf(inputs);
    }
    static byte[] encode(CubismRenderedPixels pixels) throws Exception {
        if(pixels.width()!=1024 || pixels.height()!=1024)throw new IllegalArgumentException("candidate raster dimensions");
        var image=new java.awt.image.BufferedImage(1024,1024,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,1024,1024,pixels.argb(),0,1024);var bytes=new java.io.ByteArrayOutputStream();
        if(!javax.imageio.ImageIO.write(image,"png",bytes))throw new java.io.IOException("candidate PNG writer");
        return bytes.toByteArray();
    }
}
