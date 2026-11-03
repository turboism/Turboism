package dev.modelimport.validation;

import com.live2d.cubism.doc.modeling.ui.viewerForOriginalWorkflow.OWViewerDropFrame;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.event.InputEvent;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.tree.TreePath;

/** Observation only: starts the official Viewer, records its windows, requests normal close. */
public final class OfficialViewerCapture {
    private static final String EDITOR_SHA="988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    private static Path output;
    private static boolean apple;
    private static List<String> axes;
    private OfficialViewerCapture() {}
    public static void main(String[] args) throws Exception {
        boolean xyz=args.length==6 && args[2].equals("apple-xyz-3-v1");
        if(args.length!=2 && !(args.length==3 && args[2].equals("apple-3-v1")) && !xyz)throw new IllegalArgumentException("model3.json and new evidence directory with explicit profile required");
        apple=args.length==3 || xyz;
        axes=xyz?List.of(args[3],args[4],args[5]):apple?List.of("Param3DYaw394e4cf740f3","Param3DPitch394e4cf740f3"):List.of("ParamMI3DCubeYaw","ParamMI3DCubePitch");
        if(xyz) {
            for(int i=0;i<3;i++)if(!axes.get(i).matches("Param3D"+List.of("Yaw","Pitch","Roll").get(i)+"[0-9a-f]{12}"))throw new IllegalArgumentException("exact generated XYZ axes required");
            if(axes.stream().map(a->a.substring(a.length()-12)).distinct().count()!=1)throw new IllegalArgumentException("same imported XYZ suffix required");
        }
        Path model=plain(Path.of(args[0]));output=plain(Path.of(args[1]));
        if(!Files.isRegularFile(model)||Files.size(model)>65536||!model.getFileName().toString().endsWith(".model3.json"))
            throw new IllegalArgumentException("bounded model3.json required");
        var vm=ManagementFactory.getRuntimeMXBean();
        if(vm.getInputArguments().stream().anyMatch(a->a.startsWith("-javaagent:")||a.startsWith("-agentlib:")||a.startsWith("-agentpath:")||a.startsWith("-Xbootclasspath")))
            throw new IllegalStateException("agents are forbidden");
        for(String name:List.of("dev/turboism/sdk/plugin/PluginContext.class","dev/turboism/bootstrap/BootstrapAgent.class"))
            if(OfficialViewerCapture.class.getClassLoader().getResource(name)!=null)
                throw new IllegalStateException("Turboism is on the Viewer classpath");
        Path official=plain(Path.of(OWViewerDropFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        if(!sha(Files.readAllBytes(official)).equals(EDITOR_SHA))throw new IllegalStateException("wrong official Viewer JAR");
        Files.createDirectory(output);
        Files.writeString(output.resolve("launch.json"),"{\"schemaVersion\":\"mi3d-official-viewer-observation-v1\",\"status\":\"STARTED_NOT_VERIFIED\",\"pid\":"+ProcessHandle.current().pid()+
            ",\"processStart\":"+quote(ProcessHandle.current().info().startInstant().orElseThrow().toString())+
            ",\"officialJarSha256\":"+quote(EDITOR_SHA)+",\"officialJar\":"+quote(official.toString())+
            ",\"model3Sha256\":"+quote(sha(Files.readAllBytes(model)))+",\"classpath\":"+quote(System.getProperty("java.class.path"))+
            ",\"jvmArguments\":"+array(vm.getInputArguments())+"}\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Thread observer=new Thread(()->{
            try { observe(); }
            catch(Throwable failure) {
                try { Files.writeString(output.resolve("failure.txt"),failure.toString()+"\n",StandardOpenOption.CREATE_NEW); }
                catch(Exception ignored) { failure.printStackTrace(); }
                // Request only ordinary application close; queue containment owns cleanup.
                EventQueue.invokeLater(OfficialViewerCapture::close);
            }
        },"official-viewer-observer");
        observer.setDaemon(false);observer.start();
        OWViewerDropFrame.main(new String[]{model.toString()});
    }
    private static void observe() throws Exception {
        long deadline=System.nanoTime()+120_000_000_000L;
        Window[] selected=new Window[1];
        while(System.nanoTime()<deadline) {
            EventQueue.invokeAndWait(()->{
                selected[0]=null;
                for(Window w:Window.getWindows())if(w instanceof Frame && w.isShowing() && w.getWidth()>=640 && w.getHeight()>=400)
                    if(selected[0]==null || w.getWidth()*w.getHeight()>selected[0].getWidth()*selected[0].getHeight())selected[0]=w;
            });
            if(selected[0]!=null)break;
            Thread.sleep(250);
        }
        if(selected[0]==null)throw new IllegalStateException("no official Viewer main window within deadline");
        Robot robot=new Robot();List<String> captures=new ArrayList<>();
        for(int i=0;i<3;i++) {
            // These timed observations do not assert that model loading succeeded.
            Thread.sleep(i==0?15000:2000);
            if(i==0) {
                EventQueue.invokeAndWait(()->selectModelNode(selected[0]));
                Thread.sleep(2000);
            }
            if(i>0) {
                double fraction=i==1?0.2:0.8;
                for(String axis:axes)clickParameter(robot,selected[0],axis,fraction);
                Thread.sleep(2000);
            }
            Rectangle[] bounds=new Rectangle[1];StringBuilder tree=new StringBuilder();
            EventQueue.invokeAndWait(()->{
                Window w=selected[0];if(!w.isShowing())throw new IllegalStateException("Viewer closed before capture");
                w.toFront();bounds[0]=w.getBounds();int[] count={0};components(w,0,count,tree);
            });
            if(bounds[0].width>4096||bounds[0].height>4096)throw new IllegalStateException("window pixel budget exceeded");
            String name="viewer-"+i+".png";
            ImageIO.write(robot.createScreenCapture(bounds[0]),"png",output.resolve(name).toFile());
            Files.writeString(output.resolve("components-"+i+".txt"),tree.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            captures.add("{\"path\":"+quote(name)+",\"sha256\":"+quote(sha(Files.readAllBytes(output.resolve(name))))+",\"width\":"+bounds[0].width+",\"height\":"+bounds[0].height+"}");
        }
        Files.writeString(output.resolve("observation.json"),"{\"status\":\"CAPTURED_NOT_RENDER_VERIFIED\",\"poseControl\":\"UI_MOUSE_REQUESTED_NOT_VALUE_VERIFIED\",\"axes\":"+array(axes)+",\"frames\":["+String.join(",",captures)+"]}\n",StandardOpenOption.CREATE_NEW);
        EventQueue.invokeLater(OfficialViewerCapture::close);
    }
    /** Select the visible MOC node through its standard Swing selection event. */
    private static void selectModelNode(Component root) {
        List<JTree> trees=new ArrayList<>();collectTrees(root,trees);
        List<TreePath> matches=new ArrayList<>();List<JTree> owners=new ArrayList<>();
        for(JTree tree:trees)if(tree.isShowing())for(int row=0;row<tree.getRowCount();row++) {
            TreePath path=tree.getPathForRow(row);Object value=path.getLastPathComponent();
            Component label=tree.getCellRenderer().getTreeCellRendererComponent(tree,value,false,tree.isExpanded(path),tree.getModel().isLeaf(value),row,false);
            String text=label instanceof JLabel l?l.getText():String.valueOf(value);
            if(text!=null && text.startsWith("model.moc3")) {matches.add(path);owners.add(tree);}
        }
        if(matches.size()!=1)throw new IllegalStateException("expected exactly one visible model.moc3 node, got "+matches.size());
        owners.get(0).setSelectionPath(matches.get(0));owners.get(0).scrollPathToVisible(matches.get(0));
    }
    /** Ordinary mouse input on the observed custom slider, inside the owned Viewer window. */
    private static void clickParameter(Robot robot,Window window,String id,double fraction)throws Exception {
        JComponent[] slider=new JComponent[1];
        EventQueue.invokeAndWait(()->{
            List<JLabel> labels=new ArrayList<>();findLabels(window,id,labels);
            if(labels.size()!=1)throw new IllegalStateException("ambiguous parameter label: "+id);
            JLabel label=labels.get(0);List<JComponent> candidates=new ArrayList<>();
            for(Component c:label.getParent().getComponents())
                if(c instanceof JComponent j && c.getX()>label.getX()+label.getWidth()
                    && c.getWidth()>=40 && c.getHeight()>=10
                    && c.getClass().getName().equals("com.live2d.ui.control.CCustomPaintComponent$a"))candidates.add(j);
            if(candidates.size()!=1)throw new IllegalStateException("ambiguous parameter slider: "+id);
            slider[0]=candidates.get(0);slider[0].scrollRectToVisible(new Rectangle(0,0,slider[0].getWidth(),slider[0].getHeight()));
        });
        Thread.sleep(300);Point[] point=new Point[1];
        EventQueue.invokeAndWait(()->{
            JComponent c=slider[0];Rectangle visible=c.getVisibleRect();
            int x=(int)Math.round((c.getWidth()-1)*fraction),y=c.getHeight()/2;
            if(!c.isShowing()||!visible.contains(x,y))throw new IllegalStateException("slider clipped: "+id);
            point[0]=c.getLocationOnScreen();point[0].translate(x,y);
            if(!window.getBounds().contains(point[0]))throw new IllegalStateException("slider outside owned window");
        });
        robot.mouseMove(point[0].x,point[0].y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(80);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);Thread.sleep(300);
    }
    private static void findLabels(Component c,String text,List<JLabel> labels) {
        if(c instanceof JLabel label && text.equals(label.getText()))labels.add(label);
        if(c instanceof Container parent)for(Component child:parent.getComponents())findLabels(child,text,labels);
    }
    private static void collectTrees(Component c,List<JTree> result) {
        if(c instanceof JTree tree)result.add(tree);
        if(c instanceof Container container)for(Component child:container.getComponents())collectTrees(child,result);
    }
    private static void close() {
        for(Window w:Window.getWindows())if(w instanceof Frame && w.isShowing())
            w.dispatchEvent(new WindowEvent(w,WindowEvent.WINDOW_CLOSING));
    }
    private static void components(Component c,int depth,int[] count,StringBuilder out) {
        if(depth>32||++count[0]>20000)throw new IllegalStateException("component inventory budget");
        out.append(" ".repeat(depth)).append(c.getClass().getName()).append(" bounds=").append(c.getBounds()).append(" showing=").append(c.isShowing());
        if(c.getName()!=null)out.append(" name=").append(quote(c.getName()));
        if(c instanceof Frame f)out.append(" title=").append(quote(f.getTitle()));
        if(c instanceof JLabel l)out.append(" label=").append(quote(l.getText()));
        if(c instanceof AbstractButton b)out.append(" button=").append(quote(b.getText()));
        if(c instanceof javax.swing.text.JTextComponent t)out.append(" text=").append(quote(t.getText()));
        if(c instanceof JSlider s)out.append(" slider=").append(s.getMinimum()).append('/').append(s.getValue()).append('/').append(s.getMaximum());
        out.append('\n');
        if(c instanceof JTree tree)for(int row=0;row<tree.getRowCount() && row<256;row++) {
            TreePath path=tree.getPathForRow(row);Object value=path.getLastPathComponent();
            Component label=tree.getCellRenderer().getTreeCellRendererComponent(tree,value,tree.isPathSelected(path),tree.isExpanded(path),tree.getModel().isLeaf(value),row,false);
            out.append(" ".repeat(depth+1)).append("treeRow=").append(row).append(" selected=").append(tree.isPathSelected(path))
                .append(" text=").append(quote(label instanceof JLabel l?l.getText():String.valueOf(value))).append('\n');
        }
        if(out.length()>4*1024*1024)throw new IllegalStateException("component text budget");
        if(c instanceof Container parent)for(Component child:parent.getComponents())components(child,depth+1,count,out);
    }
    private static Path plain(Path p) {
        p=p.toAbsolutePath();if(!p.equals(p.normalize()))throw new IllegalArgumentException("path traversal");
        for(Path q=p;q!=null;q=q.getParent())if(Files.isSymbolicLink(q))throw new IllegalArgumentException("linked path");
        return p;
    }
    private static String sha(byte[] b) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    private static String array(List<String> items) {return "["+String.join(",",items.stream().map(OfficialViewerCapture::quote).toList())+"]";}
    private static String quote(String s) {
        if(s==null)return "null";StringBuilder b=new StringBuilder("\"");
        for(char c:s.toCharArray()) {if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}
        return b.append('"').toString();
    }
}
