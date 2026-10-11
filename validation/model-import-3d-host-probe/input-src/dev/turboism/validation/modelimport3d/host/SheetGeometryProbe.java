package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.transaction.*;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** Dedicated real-import + native geometry leg for the frozen disjoint sheets. */
final class SheetGeometryProbe {
    static final String INPUT_SHA256="0c0f716da082c9987ba580565a97ec81237c4e0333ae4023042b9c17b41a9984";
    static byte[] readInput(InputStream stream)throws Exception {
        NativeAssertions.require(stream!=null,"sheet geometry requires packaged fixture");
        byte[] bytes=stream.readNBytes(1024*1024+1);
        NativeAssertions.require(bytes.length<=1024*1024 && HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)).equals(INPUT_SHA256),"sheet fixture identity/budget");
        return bytes;
    }
    static void run(byte[] glb,Supplier<CubismModel> current,Supplier<HistorySnapshot> history,IntConsumer restore,
            EditorCommandService commands,AuthoringTransactionService transactions,Map<String,Object> report)throws Exception {
        run(glb,current,history,restore,commands,transactions,report,null);
    }
    static void run(byte[] glb,Supplier<CubismModel> current,Supplier<HistorySnapshot> history,IntConsumer restore,
            EditorCommandService commands,AuthoringTransactionService transactions,Map<String,Object> report,
            java.nio.file.Path pixelState)throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(),"sheet probe must run outside EDT");
        var prepared=SurfaceImport.prepare(glb,new SurfaceImport.Request("MI3D Sheets",CubeGeometryProbe.YAW,
            CubeGeometryProbe.PITCH,CubeGeometryProbe.CAMERA,30,30,2_000_000));
        NativeAssertions.require(prepared.inputSha256.equals(INPUT_SHA256),"sheet source identity");
        var root=edt(history::get);var initial=edt(()->WholeImportProbe.state(current.get()));
        report.put("status","RUNNING");report.put("inputSha256",prepared.inputSha256);report.put("initialState",initial);
        Throwable primary=null;
        try {
            edt(()->{
                var result=commands.execute(new EditorCanvasSettingsRequest(1024,1024));
                NativeAssertions.require(result.status()==EditorCommandResult.Status.EXECUTED,"sheet canvas setup");return null;
            });
            var before=edt(history::get);
            var result=edt(()->SurfaceImport.apply(prepared,current,transactions));
            report.put("importOutcome",result.outcome().name());
            NativeAssertions.require(result.outcome()==AuthoringTransactionOutcome.COMMITTED,"sheet import commit");
            var committed=edt(history::get);
            NativeAssertions.require(committed.position()==before.position()+1,"sheet import single history entry");
            var imported=result.value().orElseThrow();
            var capture=new LinkedHashMap<String,Object>();report.put("capture",capture);
            var driver=SurfaceGeometryCapture.run(prepared,imported,current,history,restore,capture);
            if(pixelState!=null) {
                var pixels=new LinkedHashMap<String,Object>();report.put("pixels",pixels);
                SheetPixelCapture.run(prepared,imported,current,history,restore,driver,pixelState,pixels);
            }
        } catch(Exception | Error failure) {
            primary=failure;report.put("status","FAIL");report.put("failure",failure.toString());throw failure;
        } finally {
            try {
                edt(()->{restore.accept(root.position());return null;});
                var restored=edt(()->WholeImportProbe.state(current.get()));report.put("restoredState",restored);
                NativeAssertions.require(restored.equals(initial) && edt(history::get).position()==root.position(),"sheet root restoration");
                report.put("finallyRestored",true);
            } catch(Exception | Error failure) {
                report.put("status","FAIL");report.put("finallyRestored",false);report.put("recoveryFailure",failure.toString());
                if(primary!=null)primary.addSuppressed(failure);else throw failure;
            }
        }
        report.put("status","PASS");
    }
    private static <T>T edt(Callable<T> action)throws Exception {
        if(SwingUtilities.isEventDispatchThread())return action.call();
        var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);
        try{return task.get();}catch(ExecutionException e){
            if(e.getCause() instanceof Exception x)throw x;if(e.getCause() instanceof Error x)throw x;throw e;
        }
    }
}
