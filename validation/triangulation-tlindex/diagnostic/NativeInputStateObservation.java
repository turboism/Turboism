import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Detached raw-field input snapshot, outside the measured invocation. Never resolves Lazy. */
final class NativeInputStateObservation {
    private NativeInputStateObservation() { }
    record Snapshot(String widget, String subtool, List<String> views) { }
    record Pair(Snapshot before, Snapshot after) { }
    private static final AtomicReference<Pair> HANDOFF = new AtomicReference<>();
    static void publish(Snapshot before, Snapshot after) {
        if (!SwingUtilities.isEventDispatchThread() || before == null || after == null
                || !HANDOFF.compareAndSet(null, new Pair(before, after)))
            throw new IllegalStateException("invalid input-state handoff");
    }
    static void persist(Path run, int cycle) throws Exception {
        if (SwingUtilities.isEventDispatchThread() || cycle < 1 || cycle > 3)
            throw new IllegalStateException("input evidence requires driver thread and cycle");
        Pair pair = HANDOFF.getAndSet(null);
        if (pair == null) throw new IllegalStateException("missing detached input state");
        Path output = run.resolve("native-input-state.tsv");
        if (cycle == 1) Files.writeString(output, "cycle\tphase\twidgetBase64\tsubtoolBase64\tviewsBase64\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        if (!Files.isRegularFile(output, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("input evidence absent or symbolic");
        StringBuilder rows = new StringBuilder();
        for (boolean before : new boolean[] {true, false}) {
            Snapshot s = before ? pair.before() : pair.after();
            rows.append(cycle).append('\t').append(before ? "before" : "after").append('\t')
                    .append(encoded(s.widget())).append('\t').append(encoded(s.subtool())).append('\t')
                    .append(encoded(String.join("\n", s.views()))).append('\n');
        }
        Files.writeString(output, rows.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field f = current.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException missing) { /* Continue through the official hierarchy. */ }
        }
        throw new NoSuchFieldException(type.getName() + ":" + name);
    }
    private static String identity(Object value) {
        return value == null ? "null" : value.getClass().getName() + "@" + Integer.toUnsignedString(System.identityHashCode(value));
    }
    static String rawScalars(Object value) throws Exception {
        if (value == null) return "null";
        List<String> rows = new ArrayList<>();
        for (Class<?> type = value.getClass(); type != null && type.getName().startsWith("com.live2d.");
                type = type.getSuperclass()) {
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || (!f.getType().isPrimitive() && !f.getType().isEnum())) continue;
                f.setAccessible(true); Object scalar = f.get(value);
                rows.add(type.getName() + ":" + f.getName() + "="
                        + (scalar instanceof Enum<?> e ? e.name() : String.valueOf(scalar)));
            }
        }
        rows.sort(String::compareTo);
        return identity(value) + rows;
    }
    static Snapshot capture(Object doc) throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("input snapshot requires EDT");
        ClassLoader loader = doc.getClass().getClassLoader();
        Class<?> mouse = Class.forName("com.live2d.ui.event.m", false, loader);
        Class<?> tool = Class.forName("com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolMode_MeshEdit_Manual", false, loader);
        Object widget = field(mouse, "i").get(null);
        Object subtool = field(tool, "currentMeshEditSubTool").get(null);
        Object list = NativeAutoConnect.invokeNamed(doc, "getViewContexts");
        if (!(list instanceof List<?> views) || views.isEmpty()) throw new IllegalStateException("native document views required");
        List<String> detached = new ArrayList<>();
        for (Object view : views) {
            Object event = field(view.getClass(), "lastMouseEvent").get(view);
            Object pack = field(view.getClass(), "_lastActionPack").get(view);
            detached.add(identity(view) + ";mouse=" + rawScalars(event) + ";actionPack=" + identity(pack));
        }
        return new Snapshot(identity(widget), subtool instanceof Enum<?> e ? e.name() : identity(subtool), List.copyOf(detached));
    }
}
