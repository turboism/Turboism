import java.util.IdentityHashMap;
import java.util.List;

/** Real command binding/output protocol checks use owned objects, never an Editor. */
public final class NativeProducerAutoConnectSelfCheck {
    private static int checks;
    private NativeProducerAutoConnectSelfCheck() {}
    public static void main(String[] args) throws Exception {
        var a = new MeshProducerRecorderSelfCheck.Mesh(); var b = new MeshProducerRecorderSelfCheck.Mesh();
        var mode = new Mode(List.of(new Data(new Source("a"), a), new Data(new Source("b"), b)));
        require(NativeProducerAutoConnect.nativeSourceOrder(mode, List.of("b", "a")).equals(List.of("a", "b")));
        reject(() -> NativeProducerAutoConnect.nativeSourceOrder(mode, List.of("a", "c")));
        reject(() -> NativeProducerAutoConnect.nativeSourceOrder(mode, List.of("a", "a")));
        reject(() -> NativeProducerAutoConnect.nativeSourceOrder(new Mode(List.of(
            new Data(new Source("a"), a), new Data(new Source("a"), b))), List.of("a", "b")));
        reject(() -> NativeProducerAutoConnect.nativeSourceOrder(new Mode(List.of(
            new Data(new Source("a"), a))), List.of("a", "b")));
        var map = NativeProducerAutoConnect.bindings(mode, List.of("a", "b"));
        require(map.size() == 2 && map.get(a).equals("a") && map.get(b).equals("b"));
        reject(() -> NativeProducerAutoConnect.bindings(mode, List.of("b", "a")));
        reject(() -> NativeProducerAutoConnect.bindings(new Mode(List.of(
            new Data(new Source("a"), a), new Data(new Source("b"), a))), List.of("a", "b")));
        reject(() -> NativeProducerAutoConnect.bindings(new Mode(List.of(new Data(new Source("a"), null))), List.of("a")));
        var result = NativeProducerAutoConnect.observe(1, List.of("a", "b"), map, () -> {
            MeshProducerRecorder.returned(MeshProducerRecorder.started(a), a);
            MeshProducerRecorder.returned(MeshProducerRecorder.started(b), b);
        });
        require(result.complete() && result.nativeCommandReturned() && result.events().size() == 2);
        require(map.isEmpty());
        map = NativeProducerAutoConnect.bindings(mode, List.of("a", "b"));
        result = NativeProducerAutoConnect.observe(2, List.of("a", "b"), map, () -> {
            MeshProducerRecorder.returned(MeshProducerRecorder.started(a), a);
        });
        require(!result.complete() && result.nativeCommandReturned() && result.events().size() == 1);
        require(result.failure().contains("incomplete selected source coverage") && map.isEmpty());
        map = NativeProducerAutoConnect.bindings(mode, List.of("a", "b"));
        result = NativeProducerAutoConnect.observe(3, List.of("a", "b"), map,
            () -> { throw new UnsupportedOperationException("native failure"); });
        require(!result.complete() && !result.nativeCommandReturned() && result.events().isEmpty());
        require(result.failure().contains("UnsupportedOperationException:native failure") && map.isEmpty());
        a.vertexCache = 0;
        map = NativeProducerAutoConnect.bindings(mode, List.of("a", "b"));
        result = NativeProducerAutoConnect.observe(4, List.of("a", "b"), map, () -> {
            MeshProducerRecorder.returned(MeshProducerRecorder.started(a), a);
            MeshProducerRecorder.returned(MeshProducerRecorder.started(b), b);
        });
        require(!result.complete() && result.nativeCommandReturned() && result.events().size() == 2);
        require(result.events().get(0).failure() != null && result.events().get(1).result() != null && map.isEmpty());
        MeshProducerRecorder.requireIdle();
        System.out.println("Native producer command checks PASS: " + checks);
    }
    public record Source(String id) { public String getId() { return id; } }
    public record Data(Source source, Object mesh) { public Source a() { return source; } public Object b() { return mesh; } }
    public record Mode(List<Data> data) { public List<Data> getEditDataList() { return data; } }
    private interface Checked { void run() throws Exception; }
    private static void reject(Checked action) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("expected refusal");
    }
    private static void require(boolean value) { if (!value) throw new AssertionError("check failed"); checks++; }
}
