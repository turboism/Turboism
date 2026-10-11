package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.plugin.*;
import dev.turboism.sdk.ui.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelImportPluginTest {
    static <T>T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
    static void edt(Runnable r)throws Exception {SwingUtilities.invokeAndWait(r);}
    static final class World implements ModelImportPlugin.Ui {
        final CompletableFuture<UserFileRequestResult> choose=new CompletableFuture<>();
        final CompletableFuture<UserFileReadResult<byte[]>> read=new CompletableFuture<>();
        final CountDownLatch outcome=new CountDownLatch(1);
        final AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger(),closed=new AtomicInteger();
        final DisposableScope scope=new DisposableScope();
        dev.turboism.sdk.menu.MenuRegistry.MenuContribution menu;
        ActionRegistry.Action action;String document="original";int choice=0;boolean confirm=false;
        boolean stale=false;Runnable onConfirm=()->{},onAppearance=()->{};String error;SurfaceImport.Prepared prepared;SurfaceWarpLayerAuthoring.Prepared layered;SurfaceVertexAuthoring.Prepared vertices;
        int appearances;ModelImportPlugin.OptimizationSummary optimization;
        int previews;Runnable onPreview=()->{};
        boolean rejectPreview;
        final CubismModel model=proxy(CubismModel.class,(o,m,a)->{
            if(m.getName().equals("id"))return new ModelId("same-model-id");
            if(m.getName().equals("canvas")) {
                if(stale)throw new IllegalStateException("stale generation");
                return proxy(Canvas.class,(x,n,v)->1024f);
            }
            if(m.getName().equals("drawables"))return proxy(m.getReturnType(),(x,n,v)->{
                if(!n.getName().equals("previewSourceTriangulation"))throw new AssertionError("preview must not author "+n);
                previews++;onPreview.run();
                var geometry=(dev.turboism.sdk.cubism.model.ArtMeshGeometry)v[0];
                return new dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation(geometry.positions(),rejectPreview?List.of():geometry.triangleIndices());
            });
            throw new AssertionError("unexpected model access "+m);
        });
        Object cubism() {return proxy(CubismFacade.class,(o,m,a)->{
            if(m.getName().equals("activeDocument"))return Optional.of(new DocumentSnapshot(document,"model","model.cmo3",Optional.empty(),Optional.empty()));
            if(m.getName().equals("model"))return proxy(m.getReturnType(),(x,n,v)->model);
            if(m.getName().equals("authoringTransactions")){writes.incrementAndGet();throw new AssertionError("write must not begin");}
            throw new AssertionError(m);
        });}
        final UserFileHandle handle=proxy(UserFileHandle.class,(o,m,a)->{
            if(m.getName().equals("close")){closed.incrementAndGet();return null;}
            throw new AssertionError(m);
        });
        PluginContext context() {return proxy(PluginContext.class,(o,m,a)->switch(m.getName()) {
            case "cubism"->cubism();
            case "disposableScope"->scope;
            case "actions"->proxy(m.getReturnType(),(x,n,v)->{action=(ActionRegistry.Action)v[1];return (Registration)()->{};});
            case "menus"->proxy(m.getReturnType(),(x,n,v)->{menu=(dev.turboism.sdk.menu.MenuRegistry.MenuContribution)v[0];return (Registration)()->{};});
            case "logger"->proxy(m.getReturnType(),(x,n,v)->null);
            case "userFiles"->proxy(UserFileAccessService.class,(x,n,v)->{
                if(n.getName().equals("request"))return choose;
                if(n.getName().equals("readBytes")){reads.incrementAndGet();assertEquals(16*1024*1024,v[1]);return read;}
                throw new AssertionError(n);
            });
            default->throw new AssertionError(m);
        });}
        public int appearance(){appearances++;onAppearance.run();return choice;}
        public boolean confirm(SurfaceImport.Prepared p){prepared=p;onConfirm.run();outcome.countDown();return confirm;}
        public boolean confirmLayers(SurfaceWarpLayerAuthoring.Prepared p){layered=p;onConfirm.run();outcome.countDown();return confirm;}
        public boolean confirmVertices(SurfaceVertexAuthoring.Prepared p){vertices=p;onConfirm.run();outcome.countDown();return confirm;}
        public boolean confirmVertices(SurfaceVertexAuthoring.Prepared p,ModelImportPlugin.OptimizationSummary summary){optimization=summary;return confirmVertices(p);}
        public void message(String text,boolean failure){if(failure)error=text;outcome.countDown();}
        ModelImportPlugin start()throws Exception {
            var plugin=new ModelImportPlugin(this);plugin.init(context());plugin.enable();
            action.handler().accept(null);edt(()->{});return plugin;
        }
        void grant(){choose.complete(new UserFileRequestResult(UserFileRequestStatus.GRANTED,Optional.of(handle),Optional.empty()));}
        void cube()throws Exception {
            Path root=Path.of(System.getProperty("mi3d.repo"));
            read.complete(new UserFileReadResult<>(Optional.of(Files.readAllBytes(root.resolve("build/model-import-3d-inputs/cube-985496727e50/cube.glb"))),Optional.empty(),false));
        }
        void done()throws Exception {assertTrue(outcome.await(45,TimeUnit.SECONDS));edt(()->{});assertEquals(0,writes.get());}
    }
    @Test void menuPathIncludesActionLeaf()throws Exception {
        var w=new World();w.choice=2;w.start();
        assertEquals(List.of("插件","3D 模型",w.action.label()),List.of(w.menu.menuPath().split("/")));
        assertEquals(w.action.id(),w.menu.actionId());
    }
    @Test void cancelFileDoesNotReadOrWrite()throws Exception {
        var w=new World();w.start();w.choose.complete(new UserFileRequestResult(UserFileRequestStatus.CANCELED,Optional.empty(),Optional.empty()));
        edt(()->{});assertEquals(0,w.reads.get());assertEquals(0,w.writes.get());assertNull(w.prepared);
    }
    @Test void disableBeforeGrantClosesWithoutReading()throws Exception {
        var w=new World();var p=w.start();p.disable();w.grant();edt(()->{});
        assertEquals(1,w.closed.get());assertEquals(0,w.reads.get());assertEquals(0,w.writes.get());
    }
    @Test void truncatedInputNeverPreparesOrWrites()throws Exception {
        var w=new World();w.start();w.grant();w.read.complete(new UserFileReadResult<>(Optional.of(new byte[0]),Optional.empty(),true));w.done();
        assertTrue(w.error.contains("16 MiB"));assertNull(w.prepared);
    }
    @Test void documentSwitchDuringPreparationRefusesWrite()throws Exception {
        var w=new World();w.start();w.grant();edt(()->w.document="other");w.cube();w.done();
        assertTrue(w.error.contains("目标文档"),w.error);assertNull(w.prepared);
    }
    @Test void staleRetainedModelRefusesWriteEvenWithSameId()throws Exception {
        var w=new World();w.start();w.grant();edt(()->w.stale=true);w.cube();w.done();
        assertTrue(w.error.contains("stale generation"),w.error);
    }
    @Test void confirmationCannotWriteToChangedDocument()throws Exception {
        var w=new World();w.confirm=true;w.onConfirm=()->w.document="other";w.start();w.grant();w.cube();w.done();
        assertTrue(w.error.contains("目标文档"),w.error);
    }
    @Test void faceColorsAndDensePlanReachConfirmationBeforeWrite()throws Exception {
        var w=new World();w.choice=1;w.start();w.grant();w.cube();w.done();
        assertNull(w.error);assertEquals(6,w.prepared.appearance.materials().size());
        assertEquals(2401,w.prepared.geometry.keys().size());assertEquals(49,w.prepared.geometry.grid().yawKeys().size());
    }
    @Test void appleSourceMaterialReachesLayeredConfirmation()throws Exception {
        var w=new World();w.start();w.grant();
        Path root=Path.of(System.getProperty("mi3d.repo"));
        w.read.complete(new UserFileReadResult<>(Optional.of(Files.readAllBytes(root.resolve("artifacts/apple-textured/source-r2/apple-textured.glb"))),Optional.empty(),false));
        w.done();assertNull(w.error);assertNull(w.prepared);assertNotNull(w.layered,w.error);
        assertEquals(98,w.layered.surface().geometry.meshes().size());
        assertTrue(w.layered.surface().request.roll().isPresent());
        assertEquals(180,w.layered.surface().request.yawLimit());
        assertEquals(180,w.layered.surface().request.pitchLimit());
        assertEquals(49,w.layered.surface().yawKeys.size());assertEquals(49,w.layered.surface().pitchKeys.size());
        assertEquals(7,w.layered.layerYawKeys().size());assertEquals(7,w.layered.layerPitchKeys().size());
        assertEquals(49,w.layered.orders().jointKeys().size());
        var images=w.layered.surface().images;
        assertTrue(images.derived);assertEquals(576,images.width);assertEquals(70,images.imageCount());
        long slots=images.inputs.stream().mapToLong(m->m.defaultLocal().size()).sum();
        assertEquals(80514,slots);
        assertEquals(150784,images.inputs.stream().mapToInt(m->m.imageGeometry().triangleIndices().size()/3).sum());
        assertEquals(100258,w.layered.surface().geometry.meshes().stream().mapToLong(m->m.warpLocal().size()).sum());
        assertTrue(slots*49<=SurfaceWarpLayerAuthoring.MAX_REPEATED_LAYER_SLOTS);
        assertTrue(slots*169>SurfaceWarpLayerAuthoring.MAX_REPEATED_LAYER_SLOTS);
        assertEquals(150784,w.layered.surface().geometry.meshes().stream().mapToInt(m->m.triangles().size()).sum());
    }
    @Test void catBoundedOptimizationReachesDirectVertexConfirmationBeforeWriting() throws Exception {
        var w=new World();w.start();w.grant();var root=Path.of(System.getProperty("mi3d.repo"));
        w.read.complete(new UserFileReadResult<>(Optional.of(Files.readAllBytes(root.resolve("artifacts/cat-textured/source-r2/cat-textured.glb"))),Optional.empty(),false));
        w.done();assertNull(w.error);assertNull(w.prepared);assertNull(w.layered);assertNotNull(w.vertices);
        assertEquals(545,w.vertices.geometry().meshes().size());
        assertEquals(545,w.previews);
        assertTrue(w.vertices.images().derived);
        assertEquals(6,w.vertices.images().imageCount());
        assertNotNull(w.optimization);assertEquals(70576,w.optimization.sourceTriangles());
        assertTrue(w.optimization.outputTriangles()<w.optimization.sourceTriangles());
        assertEquals(w.optimization.outputTriangles(),w.vertices.geometry().meshes().stream().mapToInt(m->m.triangles().size()).sum());
        assertTrue(w.optimization.combinedErrorBoundPixels()<=5);
        System.out.println("CAT_UI_SIMPLIFY source="+w.optimization.sourceTriangles()+" output="+w.optimization.outputTriangles()+" slots="+w.vertices.geometry().repeatedSlots()+" combinedBoundPixels="+w.optimization.combinedErrorBoundPixels());
        assertTrue(w.vertices.request().roll().isPresent());
        assertTrue(w.vertices.interpolationErrorBoundPixels()+w.optimization.geometryErrorPixels()<=5);
    }
    @Test void originalCatZipReachesAutomaticOptimizationBeforeWriting() throws Exception {
        var w=new World();w.start();w.grant();
        w.read.complete(new UserFileReadResult<>(Optional.of(Files.readAllBytes(Path.of(System.getProperty("mi3d.repo")).resolve("artifacts/cat-textured/package-source-r1/cat.zip"))),Optional.empty(),false));
        w.done();assertNull(w.error);assertNotNull(w.vertices);assertNotNull(w.optimization);
        assertEquals(70576,w.optimization.sourceTriangles());
        assertTrue(w.optimization.outputTriangles()<70576);
        long slots=0,forms=0;
        for(int i=0;i<w.vertices.sampling().size();i++) {
            long grid=w.vertices.sampling().get(i).grid();forms+=grid;
            slots+=grid*w.vertices.geometry().meshes().get(i).positions().size();
        }
        assertTrue(8*slots+80*forms<304011176L,"automatic plan must reduce Cat form storage vs the admitted legacy plan");
        assertTrue(w.optimization.combinedErrorBoundPixels()<=5);
    }
    @Test void directVertexConfirmationCannotWriteAfterDocumentSwitch() throws Exception {
        var w=new World();w.confirm=true;w.onConfirm=()->w.document="other";w.start();w.grant();var root=Path.of(System.getProperty("mi3d.repo"));
        w.read.complete(new UserFileReadResult<>(Optional.of(Files.readAllBytes(root.resolve("artifacts/cat-textured/source-r2/cat-textured.glb"))),Optional.empty(),false));
        w.done();assertNotNull(w.vertices);assertTrue(w.error.contains("目标文档"),w.error);
    }
    @Test void nativePreviewDocumentSwitchStopsBeforeConfirmationOrWrites() throws Exception {
        var w=new World();w.onPreview=()->w.document="other";w.start();w.grant();
        var root=Path.of(System.getProperty("mi3d.repo"));
        w.read.complete(new UserFileReadResult<>(Optional.of(Files.readAllBytes(root.resolve("artifacts/cat-textured/source-r2/cat-textured.glb"))),Optional.empty(),false));
        w.done();assertTrue(w.previews>0);assertNull(w.vertices);assertTrue(w.error.contains("目标文档"),w.error);assertEquals(0,w.writes.get());
    }
    @Test void singleFaceNativeRefusalStopsBeforeConfirmationOrWrites() throws Exception {
        var w=new World();w.rejectPreview=true;w.start();w.grant();
        var root=Path.of(System.getProperty("mi3d.repo"));
        w.read.complete(new UserFileReadResult<>(Optional.of(Files.readAllBytes(root.resolve("artifacts/cat-textured/source-r2/cat-textured.glb"))),Optional.empty(),false));
        w.done();assertTrue(w.previews>0);assertNull(w.vertices);assertNotNull(w.error);assertEquals(0,w.writes.get());
    }
    @Test void layeredConfirmationCannotCommitAfterDocumentSwitch()throws Exception {
        var w=new World();w.confirm=true;w.onConfirm=()->w.document="other";w.start();w.grant();
        Path root=Path.of(System.getProperty("mi3d.repo"));
        w.read.complete(new UserFileReadResult<>(Optional.of(Files.readAllBytes(root.resolve("artifacts/apple-textured/source-r2/apple-textured.glb"))),Optional.empty(),false));
        w.done();assertNotNull(w.layered,w.error);assertTrue(w.error.contains("目标文档"),w.error);
    }
    @Test void nestedAppearanceActionCannotStartSecondImport()throws Exception {
        var w=new World();w.choice=2;
        w.onAppearance=()->{
            w.onAppearance=()->{};
            w.action.handler().accept(null);
            var loop=java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            SwingUtilities.invokeLater(loop::exit);
            loop.enter();
        };
        w.start();assertEquals(1,w.appearances);assertEquals(0,w.reads.get());
        w.action.handler().accept(null);edt(()->{});
        assertEquals(2,w.appearances); // Cancel releases the same generation for a fresh request.
    }
}
