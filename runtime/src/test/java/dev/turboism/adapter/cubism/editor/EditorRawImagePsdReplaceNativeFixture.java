package dev.turboism.adapter.cubism.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Host-free native-shaped fixture for the exact five-argument PSD replace boundary. */
public final class EditorRawImagePsdReplaceNativeFixture {
    public static final List<String> EVENTS = new ArrayList<>();
    public static final List<Boolean> EDT_EVENTS = new ArrayList<>();
    public static FailureMode failureMode = FailureMode.NONE;
    public static Runnable afterNative = () -> {};
    public static SyntheticAppController lastAppController;
    public static SyntheticLayeredImage lastIncoming;
    public static File lastStage;
    public static SyntheticDocument lastDocument;
    public static List<SyntheticLayeredImage> lastTargets;
    public static int mutationCount;

    private EditorRawImagePsdReplaceNativeFixture() {}

    public static void reset() {
        synchronized (EVENTS) {
            EVENTS.clear();
            EDT_EVENTS.clear();
        }
        failureMode = FailureMode.NONE;
        afterNative = () -> {};
        lastAppController = null;
        lastIncoming = null;
        lastStage = null;
        lastDocument = null;
        lastTargets = null;
        mutationCount = 0;
    }

    public static List<String> events() {
        synchronized (EVENTS) {
            return List.copyOf(EVENTS);
        }
    }

    public static List<Boolean> edtEvents() {
        synchronized (EVENTS) {
            return List.copyOf(EDT_EVENTS);
        }
    }

    private static void record(final String event) {
        synchronized (EVENTS) {
            EVENTS.add(event);
            EDT_EVENTS.add(EditorHostThread.isCurrent());
        }
    }

    public enum FailureMode {
        NONE,
        BEFORE_MUTATION,
        AFTER_MUTATION
    }

    public static final class SyntheticAppController {
        public SyntheticAppController() {}
    }

    public static final class SyntheticDocument {
        private final SyntheticEditMode currentEditMode;

        public SyntheticDocument(final SyntheticEditMode currentEditMode) {
            this.currentEditMode = currentEditMode;
        }

        public SyntheticEditMode getCurrentEditMode() {
            record("current-edit-mode");
            return currentEditMode;
        }
    }

    public static final class SyntheticEditMode {
        private final boolean editing;

        public SyntheticEditMode(final boolean editing) {
            this.editing = editing;
        }

        public boolean isEditing() {
            record("is-editing");
            return editing;
        }
    }

    public static final class SyntheticLayeredImage {
        private final String name;

        public SyntheticLayeredImage(final String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    public static final class SyntheticNativeEditMode {
        public SyntheticNativeEditMode() {}
    }

    public static final class SyntheticGroupUndo {
        public SyntheticGroupUndo() {}
    }

    public static final class SyntheticNativeProcess {
        public static final SyntheticNativeProcess INSTANCE = new SyntheticNativeProcess();

        private SyntheticNativeProcess() {}

        /** Same argument order as process.psd.a.a(CEAppCtrl, CLayeredImage, File, Document, List). */
        public void a(
                final SyntheticAppController appController,
                final SyntheticLayeredImage incoming,
                final File stage,
                final SyntheticDocument document,
                final List<SyntheticLayeredImage> targets) {
            record("native-begin-edit");
            lastAppController = appController;
            lastIncoming = incoming;
            lastStage = stage;
            lastDocument = document;
            lastTargets = List.copyOf(targets);
            if (failureMode == FailureMode.BEFORE_MUTATION) {
                throw new IllegalStateException("fixture failure before mutation");
            }
            record("native-mutation");
            mutationCount++;
            if (failureMode == FailureMode.AFTER_MUTATION) {
                throw new IllegalStateException("fixture failure after mutation");
            }
            record("native-end-edit");
            afterNative.run();
        }
    }
}
