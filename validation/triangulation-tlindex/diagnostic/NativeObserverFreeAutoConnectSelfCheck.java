import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

/** Own fixtures only: not native execution, plugin-loader or generation admission. */
public final class NativeObserverFreeAutoConnectSelfCheck {
    private static int checks;
    public static final class Mesh {
        int edge = 8, cache = 2, position = 3, vertex = 3, count = 3;
        float[] xy = {0, 0, 1, 0, 0, 1};
        int[] indices = {0, 1, 2};
        boolean mutate;
        public int get_edge_edit_version() { return edge; }
        public int getCache_version_gl_indices$core() { return cache; }
        public int get_postion_edit_version() { return position; }
        public int getCache_version_gl_vertex$core() { return vertex; }
        public int getPointCount() { return count; }
        public float[] getCached_positions$core() { return xy; }
        public int[] getCached_indices$core() { if (mutate) edge++; return indices; }
        @Override public int hashCode() { throw new AssertionError("mesh hashCode"); }
        @Override public boolean equals(Object other) { throw new AssertionError("mesh equals"); }
    }
    public static final class Command {
        final List<String> order;
        boolean fail;
        Command(List<String> order) { this.order = order; }
        public void run(Object doc) {
            require(doc == this, "invocation binding");
            order.add("command");
            if (fail) throw new IllegalStateException("native failure fixture");
        }
    }
    interface Checked { void run() throws Exception; }
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    static void refuse(Checked action) throws Exception {
        try { action.run(); }
        catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("invalid command observation accepted");
    }
    static NativeObserverFreeAutoConnect.Input input(String id, Mesh mesh) {
        return new NativeObserverFreeAutoConnect.Input(id, id, mesh, 7, 3, 3);
    }
    static void onEdt() throws Exception {
        Mesh a = new Mesh(), b = new Mesh();
        var inputs = List.of(input("a", a), input("b", b));
        var result = NativeObserverFreeAutoConnect.capture(inputs);
        require(result.size() == 2 && result.get(1).sourceId().equals("b"), "ordered complete output");
        require(result.get(0).beforeEdgeVersion() == 7 && result.get(0).arrays().edgeVersion() == 8
                && result.get(0).indexCacheVersion() == 2, "separate version observations");
        refuse(() -> MeshResultSnapshot.capture(a));
        String pin = result.get(0).arrays().positionsSha256();
        a.xy[0] = 9;
        require(result.get(0).arrays().positionsSha256().equals(pin), "detached scalar evidence");
        a.xy[0] = 0;
        refuse(() -> NativeObserverFreeAutoConnect.capture(List.of()));
        refuse(() -> NativeObserverFreeAutoConnect.capture(List.of(input("a", a), input("a", b))));
        refuse(() -> NativeObserverFreeAutoConnect.capture(List.of(input("a", a), input("b", a))));
        a.edge = 7;
        refuse(() -> NativeObserverFreeAutoConnect.capture(inputs));
        a.edge = Integer.MIN_VALUE;
        var wrap = NativeObserverFreeAutoConnect.capture(List.of(
                new NativeObserverFreeAutoConnect.Input("a", "a", a, Integer.MAX_VALUE, 3, 3)));
        require(wrap.get(0).arrays().edgeVersion() == Integer.MIN_VALUE, "no fabricated monotonic version rule");
        a.edge = 8; a.position = 4; a.vertex = 4;
        refuse(() -> NativeObserverFreeAutoConnect.capture(inputs));
        a.position = 3; a.vertex = 2;
        refuse(() -> NativeObserverFreeAutoConnect.capture(inputs));
        a.vertex = 3; a.count = 4;
        refuse(() -> NativeObserverFreeAutoConnect.capture(inputs));
        a.count = 3; a.mutate = true;
        refuse(() -> NativeObserverFreeAutoConnect.capture(inputs));
        a.mutate = false; a.indices[2] = 3;
        refuse(() -> NativeObserverFreeAutoConnect.capture(inputs));
        a.indices[2] = 2; a.xy[0] = Float.NaN;
        refuse(() -> NativeObserverFreeAutoConnect.capture(inputs));
        List<String> order = new ArrayList<>();
        Command command = new Command(order);
        long[] ticks = {0}, cpuTicks = {0};
        var cpu = new CommandCpuBoundary(() -> { order.add("cpu"); return ++cpuTicks[0]; },
                () -> ++ticks[0], () -> 1);
        var method = Command.class.getMethod("run", Object.class);
        var interval = NativeObserverFreeAutoConnect.invokeMeasured(method, command, command, cpu);
        require(order.equals(List.of("cpu", "command", "cpu")), "native invocation only between CPU reads");
        require(interval.after().processCpuNanos() > interval.before().processCpuNanos(), "counter samples preserved");
        order.clear(); command.fail = true;
        refuse(() -> NativeObserverFreeAutoConnect.invokeMeasured(method, command, command, cpu));
        require(order.equals(List.of("cpu", "command", "cpu")), "failed native invocation still bracketed");
    }
    public static void main(String[] args) throws Exception {
        final Exception[] failure = {null};
        SwingUtilities.invokeAndWait(() -> {
            try { onEdt(); } catch (Exception exception) { failure[0] = exception; }
        });
        if (failure[0] != null) throw failure[0];
        refuse(() -> NativeObserverFreeAutoConnect.capture(List.of(input("a", new Mesh()))));
        List<String> order = new ArrayList<>(); Command command = new Command(order);
        refuse(() -> NativeObserverFreeAutoConnect.invokeMeasured(Command.class.getMethod("run", Object.class),
                command, command, new CommandCpuBoundary(() -> 0, () -> 0, () -> 0)));
        require(order.isEmpty(), "off-EDT command never invoked");
        System.out.println("NativeObserverFreeAutoConnectSelfCheck PASS checks=" + checks);
    }
}
