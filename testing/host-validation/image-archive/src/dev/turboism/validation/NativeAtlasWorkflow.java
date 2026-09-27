package dev.turboism.validation;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Test-only, task-window-scoped driver for the native texture atlas UI (no SDK open seam). */
final class NativeAtlasWorkflow {
    private NativeAtlasWorkflow() { }

    static void awaitTaskDocument(ClassLoader loader) throws Exception {
        int seconds=Integer.getInteger("turboism.validation.imageArchive.documentTimeoutSeconds",600);
        if(seconds<1||seconds>1200)throw new IllegalArgumentException("invalid task document timeout");
        String fixture=System.getProperty("turboism.validation.imageArchive.fixtureName","");
        if(fixture.isBlank())throw new IllegalStateException("task fixture identity absent");
        long deadline=System.nanoTime()+seconds*1_000_000_000L;
        java.util.concurrent.FutureTask<Object> pending=null;Object previous=null;int stable=0;
        try {
            while(System.nanoTime()<deadline) {
                if(pending==null) {
                    pending=new java.util.concurrent.FutureTask<>(() -> {
                        Class<?> appClass=Class.forName("com.live2d.cubism.CEAppCtrl",false,loader);
                        Object app=appClass.getMethod("access$get_instance$cp").invoke(null);
                        if(app==null)return null;
                        Object doc=appClass.getMethod("getCurrentDoc").invoke(app);
                        if(doc==null||!doc.getClass().getName().equals("com.live2d.cubism.doc.modeling.CModelingDocument"))return null;
                        for(Frame frame:Frame.getFrames()) if(frame.isVisible()&&frame.getTitle().contains(fixture))return doc;
                        return null;
                    });
                    SwingUtilities.invokeLater(pending);
                }
                if(pending.isDone()) {
                    Object observed=pending.get();pending=null;
                    stable=(observed!=null&&observed==previous)?stable+1:0;previous=observed;
                    if(stable>=2)return;
                }
                Thread.sleep(500);
            }
            throw new IllegalStateException("task modeling document did not become ready within "+seconds+" seconds");
        } finally {if(pending!=null)pending.cancel(false);}
    }

    static void inspect(Path home,ClassLoader loader,java.util.Properties result) throws Exception {
        boolean workflow=Boolean.getBoolean("turboism.validation.imageArchive.atlasWorkflow");
        if(workflow && onEdt(() -> atlasCount(loader))!=0)throw new IllegalStateException("workflow requires a task fixture with no atlas");
        Frame main = onEdt(() -> {
            String fixture=System.getProperty("turboism.validation.imageArchive.fixtureName","");
            if (fixture.isBlank()) throw new IllegalStateException("task fixture identity absent");
            List<Frame> matches=new ArrayList<>();
            for (Frame frame:Frame.getFrames()) if (frame.isVisible()&&frame.getTitle().contains(fixture)) matches.add(frame);
            if (matches.size()!=1) throw new IllegalStateException("expected one task fixture frame, got "+matches.size());
            Frame frame=matches.get(0);frame.toFront();frame.requestFocus();return frame;
        });
        List<Window> before=onEdt(() -> List.of(Window.getWindows()));
        Robot robot=new Robot();robot.setAutoDelay(100);
        Thread.sleep(500);
        if (!onEdt(main::isActive)) throw new IllegalStateException("task fixture frame is not active");
        shortcut(robot,KeyEvent.VK_T);
        List<JDialog> dialogs=List.of();
        long deadline=System.nanoTime()+20_000_000_000L;
        while (dialogs.isEmpty()&&System.nanoTime()<deadline) {
            Thread.sleep(200);
            dialogs=onEdt(() -> {
                List<JDialog> found=new ArrayList<>();
                for(Window window:Window.getWindows()) if(window instanceof JDialog dialog && dialog.isVisible()
                    && !before.contains(window) && ownedBy(window,main)) found.add(dialog);
                return found;
            });
        }
        if(dialogs.isEmpty()) throw new IllegalStateException("Ctrl+T did not open a task-owned atlas dialog");
        List<JDialog> opened=dialogs;
        String text=onEdt(() -> {
            StringBuilder dump=new StringBuilder();int[] budget={2500};
            for(JDialog dialog:opened) {dump.append("WINDOW ").append(dialog.getTitle()).append('\n');dump(dialog,"",dump,budget);}
            return dump.toString();
        });
        Path output=home.resolve("state/image-archive/native-atlas-ui.txt");
        Files.createDirectories(output.getParent());Files.writeString(output,text);
        if (workflow) {
            if (opened.size()!=1 || !opened.get(0).getTitle().equals("新纹理集设置"))
                throw new IllegalStateException("expected reviewed native new-atlas settings dialog");
            AbstractButton ok=onEdt(() -> uniqueButton(opened.get(0),"OK"));
            SwingUtilities.invokeLater(ok::doClick);
            long createDeadline=System.nanoTime()+40_000_000_000L;
            List<JDialog> editors=List.of();
            while(editors.isEmpty()&&System.nanoTime()<createDeadline) {
                Thread.sleep(200);
                editors=onEdt(() -> {
                    List<JDialog> found=new ArrayList<>();
                    for(Window window:Window.getWindows()) if(window instanceof JDialog dialog && dialog.isVisible()
                        && !before.contains(window) && !opened.contains(window) && ownedBy(window,main)) found.add(dialog);
                    return found;
                });
            }
            if(editors.size()!=1) throw new IllegalStateException("native atlas editor did not open uniquely: "+editors.size());
            JDialog editor=editors.get(0);
            String editorDump=onEdt(() -> {StringBuilder value=new StringBuilder("WINDOW "+editor.getTitle()+"\n");dump(editor,"",value,new int[]{2500});return value.toString();});
            Files.writeString(output.resolveSibling("native-atlas-editor-ui.txt"),editorDump);
            if(!editor.getTitle().equals("编辑纹理集"))throw new IllegalStateException("unexpected native atlas editor title");
            AbstractButton commit=onEdt(() -> uniqueButton(editor,"OK"));
            SwingUtilities.invokeLater(commit::doClick);
            long commitDeadline=System.nanoTime()+30_000_000_000L;
            while(onEdt(editor::isVisible)&&System.nanoTime()<commitDeadline)Thread.sleep(100);
            if(onEdt(editor::isVisible))throw new IllegalStateException("native atlas editor did not commit");
            awaitAtlasCount(loader,1);
            focus(main);
            shortcut(robot,KeyEvent.VK_Z);awaitAtlasCount(loader,0);
            shortcut(robot,KeyEvent.VK_Y);awaitAtlasCount(loader,1);
            result.setProperty("workflow.nativeCreateUndoRedo","PASS:0,1,0,1");
            Path fixture=Path.of(System.getProperty("turboism.validation.imageArchive.fixture","")).toAbsolutePath().normalize();
            if(!fixture.getParent().equals(home.toAbsolutePath().normalize().getParent())
                || !fixture.getFileName().toString().equals(System.getProperty("turboism.validation.imageArchive.fixtureName"))
                || !Files.isRegularFile(fixture) || Files.isSymbolicLink(fixture))
                throw new IllegalStateException("save target is not the task-owned fixture copy");
            long beforeMtime=Files.getLastModifiedTime(fixture).toMillis(), beforeSize=Files.size(fixture);
            focus(main);shortcut(robot,KeyEvent.VK_S);
            long saveDeadline=System.nanoTime()+60_000_000_000L, previous=-1;int stable=0;
            while(System.nanoTime()<saveDeadline) {
                Thread.sleep(500);
                long mtime=Files.getLastModifiedTime(fixture).toMillis(), size=Files.size(fixture);
                if(mtime!=beforeMtime && size>0) {stable=(previous==size)?stable+1:0;previous=size;if(stable>=4)break;}
            }
            if(stable<4)throw new IllegalStateException("native save did not produce a stable changed task fixture");
            result.setProperty("workflow.saveConfirmed","true");
            result.setProperty("workflow.savedFixture",fixture.toString());
            result.setProperty("workflow.fixtureBeforeBytes",Long.toString(beforeSize));
            result.setProperty("workflow.fixtureSavedBytes",Long.toString(Files.size(fixture)));
        }
        SwingUtilities.invokeLater(() -> {for(JDialog dialog:opened) if(dialog.isVisible()) dialog.dispatchEvent(new WindowEvent(dialog,WindowEvent.WINDOW_CLOSING));});
        deadline=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<deadline) {
            if(onEdt(() -> opened.stream().noneMatch(Window::isVisible))) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("task atlas inspection dialog did not cancel");
    }

    static void shortcut(Robot robot,int key) {
        robot.keyPress(KeyEvent.VK_CONTROL);
        try {robot.keyPress(key);robot.keyRelease(key);} finally {robot.keyRelease(KeyEvent.VK_CONTROL);}
    }

    static String captureAndExport(Path home,ClassLoader loader,java.util.Properties result) throws Exception {
        // Materialize through the normal native cached-image consumer. A persisted
        // atlas need not have been displayed yet; a field-only poll cannot do this.
        onEdt(() -> {
            Object manager=manager(loader);
            List<?> atlases=(List<?>)manager.getClass().getMethod("getTextureAtlases").invoke(manager);
            if(atlases.size()!=1)throw new IllegalStateException("expected one persisted atlas");
            Object atlas=atlases.get(0);
            result.setProperty("workflow.atlasEntries",Integer.toString(((List<?>)atlas.getClass().getMethod("getModelImages").invoke(atlas)).size()));
            Class<?> param=Class.forName("com.live2d.graphics.cachedImage.CachedImageParam",false,loader);
            atlas.getClass().getMethod("getCachedImage$default",atlas.getClass(),param,int.class,Object.class)
                .invoke(null,atlas,null,1,null);
            return null;
        });
        long deadline=System.nanoTime()+30_000_000_000L;
        while(!onEdt(() -> {
            Object manager=manager(loader);
            List<?> atlases=(List<?>)manager.getClass().getMethod("getTextureAtlases").invoke(manager);
            if(atlases.size()!=1)return false;
            Object atlas=atlases.get(0);
            // A reopened, locked cached atlas is valid even with the dirty bit set.
            // Use the exact native readiness predicate, not our own interpretation.
            var needUpdate=atlas.getClass().getDeclaredMethod("getNeedUpdateCachedAtlasImage");
            needUpdate.setAccessible(true);
            return atlas.getClass().getMethod("getCachedAtlasImage").invoke(atlas)!=null
                && Boolean.FALSE.equals(needUpdate.invoke(atlas));
        })) {
            if(System.nanoTime()>deadline)throw new IllegalStateException("native atlas image did not become ready");
            Thread.sleep(200);
        }
        return onEdt(() -> {
            Object manager=manager(loader);
            List<?> images=(List<?>)manager.getClass().getMethod("getAllModelImages").invoke(manager);
            java.security.MessageDigest combined=java.security.MessageDigest.getInstance("SHA-256");
            for(int i=0;i<images.size();i++) {
                Object modelImage=images.get(i);
                Object resource=modelImage.getClass().getMethod("getFilteredImage").invoke(modelImage);
                String hash=pixelDigest(resource);
                combined.update((i+":"+hash+";").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                result.setProperty("workflow.sourceImage."+i+".sha256",hash);
            }
            List<?> atlases=(List<?>)manager.getClass().getMethod("getTextureAtlases").invoke(manager);
            Object atlas=atlases.get(0), resource=atlas.getClass().getMethod("getCachedAtlasImage").invoke(atlas);
            String hash=pixelDigest(resource);
            combined.update(("atlas:"+hash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            result.setProperty("workflow.atlasPixelsSha256",hash);
            result.setProperty("workflow.sourceImageCount",Integer.toString(images.size()));
            Object image=resource.getClass().getMethod("getImage").invoke(resource);
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
            image.getClass().getMethod("writeImageAsPng",java.io.OutputStream.class).invoke(image,output);
            byte[] png=output.toByteArray();
            Files.write(home.resolve("state/image-archive/native-atlas-export.png"),png);
            Class<?> format=Class.forName("com.live2d.graphics.n",false,loader);
            Object copy=resource.getClass().getConstructor(byte[].class,format,boolean.class)
                .newInstance(png,image.getClass().getMethod("getType").invoke(image),true);
            copy.getClass().getMethod("setUnmanagedResource",boolean.class).invoke(copy,true);
            try {
                if(!hash.equals(pixelDigest(copy)))throw new IllegalStateException("native atlas PNG export round-trip differs");
            } finally {
                var dispose=copy.getClass().getDeclaredMethod("dispose");dispose.setAccessible(true);dispose.invoke(copy);
            }
            String digest=java.util.HexFormat.of().formatHex(combined.digest());
            result.setProperty("workflow.contentDigest",digest);
            result.setProperty("workflow.nativePngExportRoundTrip","true");
            return digest;
        });
    }

    private static String pixelDigest(Object resource) throws Exception {
        synchronized(resource) {
            Object image=resource.getClass().getMethod("getImage").invoke(resource);
            int width=(int)image.getClass().getMethod("getWidth").invoke(image);
            int height=(int)image.getClass().getMethod("getHeight").invoke(image);
            int[] pixels=(int[])image.getClass().getMethod("getIntBuffer").invoke(image);
            if((long)width*height!=pixels.length)throw new IllegalStateException("image is not a dense native raster");
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate(65536);
            buffer.putInt(width).putInt(height);
            for(int pixel:pixels) {
                if(buffer.remaining()<4) {digest.update(buffer.array(),0,buffer.position());buffer.clear();}
                buffer.putInt(pixel);
            }
            digest.update(buffer.array(),0,buffer.position());
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
    }

    private static void focus(Frame main) throws Exception {
        onEdt(() -> {main.toFront();main.requestFocus();return null;});
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!onEdt(main::isActive)&&System.nanoTime()<deadline)Thread.sleep(100);
        if(!onEdt(main::isActive))throw new IllegalStateException("task window lost focus");
    }

    static Object manager(ClassLoader loader) throws Exception {
        Class<?> appClass=Class.forName("com.live2d.cubism.CEAppCtrl",false,loader);
        Object app=appClass.getMethod("access$get_instance$cp").invoke(null);
        Object doc=appClass.getMethod("getCurrentDoc").invoke(app);
        if(doc==null||!doc.getClass().getName().equals("com.live2d.cubism.doc.modeling.CModelingDocument"))
            throw new IllegalStateException("task modeling document absent");
        Object source=doc.getClass().getMethod("getModelSource").invoke(doc);
        return source.getClass().getMethod("getTextureManager").invoke(source);
    }

    static int atlasCount(ClassLoader loader) throws Exception {
        Object manager=manager(loader);
        return ((List<?>)manager.getClass().getMethod("getTextureAtlases").invoke(manager)).size();
    }

    private static void awaitAtlasCount(ClassLoader loader,int expected) throws Exception {
        long deadline=System.nanoTime()+20_000_000_000L;
        while(System.nanoTime()<deadline) {
            if(onEdt(() -> atlasCount(loader))==expected)return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("native atlas count did not reach "+expected);
    }

    private static boolean ownedBy(Window child,Window owner) {
        for(Window current=child.getOwner();current!=null;current=current.getOwner()) if(current==owner)return true;
        return false;
    }

    private static AbstractButton uniqueButton(Container root,String label) {
        List<AbstractButton> buttons=new ArrayList<>();collectButtons(root,label,buttons);
        if(buttons.size()!=1)throw new IllegalStateException("expected one enabled native button "+label+", got "+buttons.size());
        return buttons.get(0);
    }

    private static void collectButtons(Container root,String label,List<AbstractButton> matches) {
        for(Component child:root.getComponents()) {
            if(child instanceof AbstractButton button && button.isShowing() && button.isEnabled() && label.equals(button.getText())) matches.add(button);
            if(child instanceof Container container)collectButtons(container,label,matches);
        }
    }

    private static void dump(Component component,String indent,StringBuilder text,int[] budget) {
        if(--budget[0]<0||indent.length()>40)return;
        text.append(indent).append(component.getClass().getName()).append(" showing=").append(component.isShowing());
        if(component instanceof AbstractButton button) text.append(" text=").append(button.getText()).append(" enabled=").append(button.isEnabled());
        if(component instanceof JMenuItem menu) text.append(" accelerator=").append(menu.getAccelerator());
        if(component instanceof JLabel label) text.append(" text=").append(label.getText());
        if(component instanceof JTextComponent field) text.append(" text=").append(field.getText());
        text.append('\n');
        if(component instanceof JMenu menu) {for(Component child:menu.getMenuComponents())dump(child,indent+" ",text,budget);}
        else if(component instanceof Container container) {for(Component child:container.getComponents())dump(child,indent+" ",text,budget);}
    }

    static <T> T onEdt(Callable<T> action) throws Exception {
        if(SwingUtilities.isEventDispatchThread())return action.call();
        java.util.concurrent.FutureTask<T> task=new java.util.concurrent.FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        try {return task.get(30,java.util.concurrent.TimeUnit.SECONDS);}
        catch(java.util.concurrent.TimeoutException timeout) {
            task.cancel(false);
            StringBuilder trace=new StringBuilder();
            for(var entry:Thread.getAllStackTraces().entrySet()) {
                if(trace.length()>200000)break;
                trace.append(entry.getKey().getName()).append(" ").append(entry.getKey().getState()).append('\n');
                for(StackTraceElement frame:entry.getValue())trace.append("  ").append(frame).append('\n');
            }
            Path home=Path.of(System.getProperty("turboism.validation.imageArchive.home"));
            Files.writeString(home.resolve("state/image-archive/native-ui-timeout.txt"),trace);
            throw new IllegalStateException("native atlas EDT operation timed out",timeout);
        } catch(java.util.concurrent.ExecutionException failure) {
            throw new Exception("native atlas EDT operation failed",failure.getCause());
        }
    }
}
