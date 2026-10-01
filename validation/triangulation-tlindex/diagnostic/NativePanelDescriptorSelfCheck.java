import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Loads official method metadata without initializing or instantiating native UI classes. */
public final class NativePanelDescriptorSelfCheck {
    private NativePanelDescriptorSelfCheck() {}
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("official jar directories required");
        for (String argument : args) {
            Path directory = Path.of(argument);
            List<URL> urls = new ArrayList<>();
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                    urls.add(file.toUri().toURL());
                }
            }
            if (urls.isEmpty()) throw new IllegalArgumentException("no jars: " + directory);
            try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                Class<?> manual = Class.forName("com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolMode_MeshEdit_Manual", false, loader);
                int exact = 0;
                for (Method method : manual.getMethods()) {
                    if (!method.getName().equals("getToolPanel") || method.getParameterCount() != 0) continue;
                    System.out.println(directory + " " + method.getReturnType().getName() + " bridge=" + method.isBridge());
                    if (method.getReturnType().getName().equals("com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolPanel_MeshEdit") && !method.isBridge()) exact++;
                }
                if (exact != 1) throw new AssertionError("native exact descriptor count=" + exact);
                System.out.println(directory + " exact native panel descriptor PASS");
            }
        }
    }
}
