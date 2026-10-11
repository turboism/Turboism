package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** SDK-only official export after native atlas authoring. This does not run a substitute evaluator. */
final class CubeMocExportProbe {
    private CubeMocExportProbe() { }
    static void run(Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
                    Path state, Map<String,Object> data, CubePersistenceInput profile) throws Exception {
        data.put("schemaVersion","mi3d-official-export-v1");data.put("status","RUNNING");data.put("failure",null);
        data.put("coreVerification","NOT_RUN");
        var before=edt(()->CubePersistenceProbe.capture(current.get()));data.put("before",before);
        var h0=edt(()->WholeImportProbe.historyData(history.get()));data.put("historyBefore",h0);
        int size=profile.atlasSize;
        data.put("inputSha256",profile.sha256);data.put("textureLayout",profile.layout);
        // Export origin is in canvas pixels, independent of texture-atlas size.
        var settings=new CubismMocExportSettings(512,512,100,false,false,false,false,false);
        data.put("settings",List.of(512,512,100,false,false,false,false,false));
        try {
            CubismMocExport result=edt(()->current.get().exportMoc(settings));
            var after=edt(()->CubePersistenceProbe.capture(current.get()));data.put("after",after);
            var h1=edt(()->WholeImportProbe.historyData(history.get()));data.put("historyAfter",h1);
            NativeAssertions.require(before.equals(after) && h0.equals(h1),"official export changed authored model/history");
            NativeAssertions.require(result.settings().equals(settings) && result.textures().size()==1,
                "cube export settings/texture count differs");
            Path directory=state.resolve("official-export");Files.createDirectory(directory);
            byte[] moc=result.moc3();Path file=directory.resolve("model.moc3");Files.write(file,moc,StandardOpenOption.CREATE_NEW);
            data.put("moc",artifact("official-export/model.moc3",moc));
            var textures=new ArrayList<Map<String,Object>>();
            for(int i=0;i<result.textures().size();i++) {
                var texture=result.textures().get(i);
                NativeAssertions.require(texture.width()==size && texture.height()==size,"cube export atlas dimensions differ");
                int[] pixels=texture.argb();
                var image=new BufferedImage(texture.width(),texture.height(),BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0,0,texture.width(),texture.height(),pixels,0,texture.width());
                String path="official-export/texture-"+i+".png";
                try(var stream=Files.newOutputStream(state.resolve(path),StandardOpenOption.CREATE_NEW)) {
                    NativeAssertions.require(ImageIO.write(image,"png",stream),"PNG encoder unavailable");
                } finally {image.flush();}
                byte[] png=Files.readAllBytes(state.resolve(path));
                var row=new LinkedHashMap<String,Object>(artifact(path,png));
                row.put("index",i);row.put("width",texture.width());row.put("height",texture.height());
                var digest=MessageDigest.getInstance("SHA-256");
                for(int pixel:pixels)for(int shift:new int[]{24,16,8,0})digest.update((byte)(pixel>>>shift));
                row.put("argbSha256",HexFormat.of().formatHex(digest.digest()));textures.add(row);
            }
            data.put("textures",textures);data.put("status","CAPTURED_NOT_CORE_VERIFIED");
        } catch(Exception|Error failure) {
            data.put("status","FAIL");data.put("failure",failure.toString());throw failure;
        }
    }
    private static Map<String,Object> artifact(String path,byte[] bytes)throws Exception {
        return Map.of("path",path,"bytes",bytes.length,"sha256",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    private static <T>T edt(Callable<T> action)throws Exception {
        if(SwingUtilities.isEventDispatchThread())return action.call();
        var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get();
    }
}
