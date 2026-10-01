import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Snapshot integrity checks only; does not establish native command execution. */
public final class MeshResultSnapshotSelfCheck {
    private static int checks;
    private MeshResultSnapshotSelfCheck() {}

    public static void main(String[] args) throws Exception {
        float[] xy = {0, 0, 1, 0, 0, 1};
        int[] indices = {0, 1, 2};
        MeshResultSnapshot.Result original = MeshResultSnapshot.snapshot(3, 7, xy, indices);
        require(original.equals(MeshResultSnapshot.snapshot(3, 7, xy.clone(), indices.clone())));
        require(!original.indicesSha256().equals(
            MeshResultSnapshot.snapshot(3, 7, xy, new int[] {2, 1, 0}).indicesSha256()));
        xy[0] = -0.0f;
        require(!original.positionsSha256().equals(MeshResultSnapshot.snapshot(3, 7, xy, indices).positionsSha256()));
        require(original.positionValues() == 6 && original.indexValues() == 3);
        reject(() -> MeshResultSnapshot.snapshot(3, 0, new float[5], indices));
        reject(() -> MeshResultSnapshot.snapshot(3, 0, new float[] {Float.NaN, 0, 1, 0, 0, 1}, indices));
        reject(() -> MeshResultSnapshot.snapshot(3, 0, xy, new int[] {0, 1, 3}));
        reject(() -> MeshResultSnapshot.snapshot(3, 0, xy, new int[] {0, 1}));
        reject(() -> MeshResultSnapshot.snapshot(3, 0, xy, new int[0]));
        reject(() -> MeshResultSnapshot.capture(new NativeShape()));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                NativeShape mesh = new NativeShape();
                require(MeshResultSnapshot.capture(mesh).equals(original));
                mesh.stale = true;
                int reads = mesh.arrayReads;
                reject(() -> MeshResultSnapshot.capture(mesh));
                require(mesh.arrayReads == reads);
                mesh.stale = false;
                mesh.changeDuringRead = true;
                reject(() -> MeshResultSnapshot.capture(mesh));
                mesh.changeDuringRead = false;
                mesh.throwGetter = true;
                try {
                    MeshResultSnapshot.capture(mesh);
                    throw new AssertionError("expected native error");
                } catch (UnsupportedOperationException expected) {
                    checks++;
                }
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        if (failure.get() != null) throw new AssertionError("EDT checks failed", failure.get());
        System.out.println("Mesh result snapshot checks PASS: " + checks);
    }

    public static final class NativeShape {
        boolean stale;
        boolean changeDuringRead;
        boolean throwGetter;
        int arrayReads;
        int version = 7;
        public int get_edge_edit_version() { return version; }
        public int getCache_version_gl_indices$core() { return stale ? -1 : version; }
        public int get_postion_edit_version() { return 4; }
        public int getCache_version_gl_vertex$core() { return 4; }
        public int getPointCount() { return 3; }
        public float[] getCached_positions$core() {
            arrayReads++;
            if (throwGetter) throw new UnsupportedOperationException("native getter error");
            return new float[] {0, 0, 1, 0, 0, 1};
        }
        public int[] getCached_indices$core() {
            arrayReads++;
            if (changeDuringRead) version++;
            return new int[] {0, 1, 2};
        }
    }

    private interface Action { void run() throws Exception; }
    private static void reject(Action action) throws Exception {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            checks++;
            return;
        }
        throw new AssertionError("expected rejection");
    }
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("failed check");
        checks++;
    }
}
