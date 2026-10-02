import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;

/** A single native command scope. Returned evidence contains no mesh, mode, document or Throwable. */
final class NativeProducerAutoConnect {
    private NativeProducerAutoConnect() {}
    record Observation(boolean nativeCommandReturned, boolean complete,
                       List<MeshProducerRecorder.Event> events, String failure) {}
    interface Command { void run() throws Exception; }

    static List<String> enter(Object controller, NativeAutoConnect.Binding binding) throws Exception {
        // Native entry already verifies exact selected/edit-data object identities.
        // The editor can reorder that complete list. Freeze its actual order once
        // on this same EDT dispatch, then require it to stay unchanged per command.
        List<String> selectedIds = NativeAutoConnect.enter(controller, binding);
        Object mode = NativeAutoConnect.invokeNamed(NativeAutoConnect.boundDocument(controller, binding),
            "getCurrentEditMode");
        return nativeSourceOrder(mode, selectedIds);
    }

    static List<String> nativeSourceOrder(Object mode, List<String> selectedIds) throws Exception {
        Object value = NativeAutoConnect.invokeNamed(mode, "getEditDataList");
        require(value instanceof List<?>, "missing native edit data");
        List<String> nativeIds = new ArrayList<>();
        for (Object data : (List<?>) value) {
            Object source = NativeAutoConnect.invokeNamed(data, "a");
            nativeIds.add(NativeAutoConnect.invokeNamed(source, "getId").toString());
        }
        require(!selectedIds.isEmpty() && new HashSet<>(selectedIds).size() == selectedIds.size()
                && nativeIds.size() == selectedIds.size()
                && new HashSet<>(nativeIds).equals(new HashSet<>(selectedIds)),
            "native edit data differs from complete selected source set");
        IdentityHashMap<Object, String> checked = bindings(mode, nativeIds);
        checked.clear();
        return List.copyOf(nativeIds);
    }

    static Observation connect(Object controller, NativeAutoConnect.Binding binding,
            List<String> expectedIds, int cycle) throws Exception {
        Object doc = NativeAutoConnect.boundDocument(controller, binding);
        Object mode = NativeAutoConnect.invokeNamed(doc, "getCurrentEditMode");
        require(mode.getClass().getName().equals("com.live2d.cubism.doc.modeling.CModelingEditMode_MeshEditor"),
            "producer scope requires native mesh mode");
        IdentityHashMap<Object, String> sources = bindings(mode, expectedIds);
        return observe(cycle, expectedIds, sources,
            () -> NativeAutoConnect.connect(controller, binding, true, true));
    }

    static IdentityHashMap<Object, String> bindings(Object mode, List<String> expectedIds) throws Exception {
        Object value = NativeAutoConnect.invokeNamed(mode, "getEditDataList");
        require(value instanceof List<?>, "missing native edit data");
        IdentityHashMap<Object, String> sources = new IdentityHashMap<>();
        List<String> ids = new ArrayList<>();
        for (Object data : (List<?>) value) {
            Object source = NativeAutoConnect.invokeNamed(data, "a");
            Object mesh = NativeAutoConnect.invokeNamed(data, "b");
            String id = NativeAutoConnect.invokeNamed(source, "getId").toString();
            require(mesh != null && sources.put(mesh, id) == null, "duplicate/missing native mesh identity");
            ids.add(id);
        }
        require(ids.equals(expectedIds), "native edit data order/source differs from selected IDs");
        return sources;
    }

    /** Ordinary observation/native-command failures are emitted before the driver stops the diagnostic. */
    static Observation observe(int cycle, List<String> expectedIds, IdentityHashMap<Object, String> sources,
            Command command) {
        boolean returned = false;
        try (var scope = MeshProducerRecorder.begin(cycle, expectedIds, sources)) {
            try {
                command.run();
                returned = true;
                return new Observation(true, true, scope.finish(), "");
            } catch (Exception | LinkageError failure) {
                return new Observation(returned, false, scope.events(), failure.getClass().getName() + ":"
                    + java.util.Objects.toString(failure.getMessage(), ""));
            }
        } finally {
            sources.clear();
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
