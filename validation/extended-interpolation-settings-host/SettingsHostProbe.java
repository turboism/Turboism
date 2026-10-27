package dev.turboism.validation.extendedsettings;

import java.awt.*;
import java.awt.event.*;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real Swing checkbox/save/restart proof. No official Editor classes bundled. */
public final class SettingsHostProbe {
    static final Properties result = new Properties();
    static Path home;
    static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }
    static List<Component> tree(Component root) {
        List<Component> out = new ArrayList<>(); out.add(root);
        if (root instanceof Container c) for (Component child:c.getComponents()) out.addAll(tree(child));
        if (root instanceof JMenu m) for (Component child:m.getMenuComponents()) out.addAll(tree(child));
        return out;
    }
    static JCheckBox checkbox() {
        for (Window w:Window.getWindows()) if (w.isShowing()) for (Component c:tree(w))
            if (c instanceof JCheckBox box && "mesh-interpolation".equals(box.getName())) return box;
        return null;
    }
    static Object status() throws Exception {
        Class<?> cls=Class.forName("dev.turboism.config.NativeOptimizationDiagnostics");
        Map<?,?> statuses=(Map<?,?>)cls.getMethod("snapshot").invoke(null);
        for (var entry:statuses.entrySet()) if (entry.getKey().toString().equals("MESH_INTERPOLATION")) return entry.getValue();
        throw new AssertionError("interpolation diagnostic absent");
    }
    static String state(Object status) throws Exception {return (String)status.getClass().getMethod("state").invoke(status);}
    static boolean requested(Object status) throws Exception {
        Object choice=status.getClass().getMethod("choice").invoke(status);
        return (boolean)choice.getClass().getMethod("enabled").invoke(choice);
    }
    public static void premain(String args, Instrumentation instrumentation) {
        Thread thread=new Thread(()->{try {run();} catch(Throwable failure) {failure.printStackTrace();}},"extended-settings-proof");
        thread.setDaemon(true);thread.start();
    }
    static void run() throws Exception {
        home=Path.of(System.getProperty("turboism.validation.extendedSettings.home"));
        Path dir=home.resolve("state/extended-settings");Files.createDirectories(dir);
        boolean restart=Boolean.getBoolean("turboism.validation.extendedSettings.restart");
        try {
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);
            while(!Files.isRegularFile(dir.resolve("start.flag")) && System.nanoTime()<deadline) Thread.sleep(200);
            if(!Files.isRegularFile(dir.resolve("start.flag"))) throw new AssertionError("trigger timeout");
            if(System.getProperty("turboism.optimization.meshInterpolation")!=null || System.getProperty("turboism.meshInterpolation.enabled")!=null)
                throw new AssertionError("JVM interpolation override invalidates persisted-preference proof");
            Object before=status();
            if(requested(before)!=restart) throw new AssertionError("unexpected startup request: "+before);
            result.setProperty("startup.diagnostic",before.toString());
            Robot robot=new Robot();robot.setAutoDelay(80);
            edt(()->{for(Frame f:Frame.getFrames()) if(f.isShowing() && f.getTitle().contains("Live2D")){f.toFront();f.requestFocus();break;}return null;});
            Thread.sleep(500);
            robot.keyPress(KeyEvent.VK_CONTROL);robot.keyPress(KeyEvent.VK_ALT);robot.keyPress(KeyEvent.VK_S);
            robot.keyRelease(KeyEvent.VK_S);robot.keyRelease(KeyEvent.VK_ALT);robot.keyRelease(KeyEvent.VK_CONTROL);
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            JCheckBox box;
            while((box=edt(SettingsHostProbe::checkbox))==null && System.nanoTime()<deadline) Thread.sleep(200);
            if(box==null) throw new AssertionError("registered Settings shortcut did not expose mesh-interpolation checkbox");
            final JCheckBox target=box;
            edt(()->{
                if(target.isSelected()!=restart) throw new AssertionError("checkbox does not reflect saved preference");
                for(Container p=target.getParent();p!=null;p=p.getParent()) if(p instanceof JTabbedPane tabs)
                    for(int i=0;i<tabs.getTabCount();i++) if(tree(tabs.getComponentAt(i)).contains(target)) {tabs.setSelectedIndex(i);result.setProperty("ui.tab",tabs.getTitleAt(i));}
                if(!restart) target.doClick();
                return null;
            });
            Thread.sleep(300);
            Rectangle bounds=edt(()->SwingUtilities.getWindowAncestor(target).getBounds());
            ImageIO.write(robot.createScreenCapture(bounds),"png",dir.resolve(restart?"restart-checkbox.png":"enabled-checkbox.png").toFile());
            edt(()->{
                Window window=SwingUtilities.getWindowAncestor(target);
                var painted=new java.awt.image.BufferedImage(window.getWidth(),window.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics=painted.createGraphics();
                try {window.printAll(graphics);} finally {graphics.dispose();}
                ImageIO.write(painted,"png",dir.resolve(restart?"restart-swing-paint.png":"enabled-swing-paint.png").toFile());
                return null;
            });
            result.setProperty("ui.checkbox.label",edt(target::getText));
            if(!restart) {
                // Save follows the same visible confirmation button as a user. Do not block
                // this worker on the modal "saved" notice's nested event loop.
                edt(()->{
                    Window dialog=SwingUtilities.getWindowAncestor(target);
                    JButton ok=null;
                    for(Component c:tree(dialog)) if(c instanceof JButton b && "OK".equals(b.getText())) {ok=b;break;}
                    if(ok==null) throw new AssertionError("English Settings OK button absent");
                    final JButton save=ok;SwingUtilities.invokeLater(save::doClick);return null;
                });
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                boolean saved=false;
                while(System.nanoTime()<deadline) {
                    String config=Files.readString(home.resolve("config.json"));
                    if(config.matches("(?s).*\\\"meshInterpolation\\\"\\s*:\\s*true.*")) {saved=true;break;}
                    Thread.sleep(100);
                }
                if(!saved) throw new AssertionError("checkbox did not persist enabled preference");
                if(requested(status())) throw new AssertionError("launch snapshot changed without restart");
                Files.copy(home.resolve("config.json"),dir.resolve("saved-config.json"),StandardCopyOption.REPLACE_EXISTING);
                result.setProperty("ui.persisted","true");
                result.setProperty("ui.restartRequiredProven","true");
            } else {
                // Fixtures without extension data may never load this lazy class.
                // Resolve it through its official loader, then require hash admission.
                Class.forName("com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension");
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!"installed".equals(state(status())) && System.nanoTime()<deadline) Thread.sleep(200);
                if(!"installed".equals(state(status()))) throw new AssertionError("saved preference requested but host patch not installed: "+status());
                result.setProperty("restart.actual",status().toString());
            }
            result.setProperty("scope","actual Settings shortcut, visible Performance checkbox; source editing/visual equivalence not covered");
            result.setProperty("status","PASS");
        } catch(Throwable failure) {
            failure.printStackTrace();result.setProperty("status","FAIL");result.setProperty("error",failure.toString());
        } finally {
            try(var output=Files.newOutputStream(dir.resolve("result.properties"))) {result.store(output,"extended interpolation settings host proof");}
            edt(()->{
                for(Window w:Window.getWindows()) if(w instanceof JDialog && w.isShowing()) w.dispose();
                for(Frame f:Frame.getFrames()) if(f.isShowing() && f.getTitle().contains("Live2D")) f.dispatchEvent(new WindowEvent(f,WindowEvent.WINDOW_CLOSING));
                return null;
            });
        }
    }
}
