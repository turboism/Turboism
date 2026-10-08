import java.util.IdentityHashMap;
import java.util.List;
import javax.swing.SwingUtilities;

/** A separate dual-boundary diagnostic; never admits observer-free measurement. */
final class NativeCommandReturnAutoConnect {
    private NativeCommandReturnAutoConnect() {}
    record Observation(NativeProducerAutoConnect.Observation producer,
                       List<NativeCommandReturnSnapshot.Result> results,
                       boolean complete, String failure) {}

    static Observation connect(Object controller, NativeAutoConnect.Binding binding,
            List<String> expectedIds, int cycle) throws Exception {
        require(SwingUtilities.isEventDispatchThread(), "dual-boundary command requires EDT");
        Object document = NativeAutoConnect.boundDocument(controller, binding);
        Object mode = NativeAutoConnect.invokeNamed(document, "getCurrentEditMode");
        require(mode.getClass().getName().equals("com.live2d.cubism.doc.modeling.CModelingEditMode_MeshEditor"),
                "dual-boundary command requires native mesh mode");
        IdentityHashMap<Object, String> before = NativeProducerAutoConnect.bindings(mode, expectedIds);
        NativeProducerAutoConnect.Observation producer = null;
        try {
            producer = NativeProducerAutoConnect.connect(controller, binding, expectedIds, cycle);
            if (!producer.complete()) return new Observation(producer, List.of(), false, "PRODUCER_INCOMPLETE");
            requireContext(controller, binding, document, mode, before, expectedIds);
            var results = NativeCommandReturnSnapshot.captureAndCompare(expectedIds, before, producer, cycle);
            requireContext(controller, binding, document, mode, before, expectedIds);
            return new Observation(producer, results, true, "");
        } catch (Exception | LinkageError failure) {
            if (producer == null) throw failure;
            // Preserve completed producer evidence when the second boundary refuses.
            return new Observation(producer, List.of(), false,
                    "COMMAND_RETURN_OBSERVATION_FAILED:" + failure.getClass().getSimpleName());
        } finally {
            before.clear();
        }
    }

    private static void requireContext(Object controller, NativeAutoConnect.Binding binding,
            Object document, Object mode, IdentityHashMap<Object, String> before,
            List<String> expectedIds) throws Exception {
        require(NativeAutoConnect.boundDocument(controller, binding) == document
                && NativeAutoConnect.invokeNamed(document, "getCurrentEditMode") == mode,
                "dual-boundary native context changed");
        IdentityHashMap<Object, String> after = NativeProducerAutoConnect.bindings(mode, expectedIds);
        try {
            require(before.size() == after.size(), "dual-boundary mesh count changed");
            for (var entry : before.entrySet()) {
                require(entry.getValue().equals(after.get(entry.getKey())),
                        "dual-boundary source/mesh identity changed");
            }
        } finally {
            after.clear();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
