package dev.turboism.adapter.cubism.editor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Host-free native-shaped fixture used to exercise the verified save/parse call sequence. */
public final class EditorRawImagePsdNativeFixture {
    public static final List<String> EVENTS = new ArrayList<>();
    public static final List<Boolean> EDT_EVENTS = new ArrayList<>();
    public static boolean throwOnSave;
    public static boolean swallowSave;
    public static boolean throwOnParse;
    public static boolean parseFirstFlag;
    public static boolean parseSecondFlag;
    public static File parseTarget;
    public static File constructedTarget;
    public static String constructedName;
    public static SyntheticProgress lastProgress;
    public static final SyntheticProgress DEFAULT_PROGRESS = new SyntheticProgress();
    public static Runnable afterSave = () -> {};
    public static Runnable saveFailure = () -> {};

    private EditorRawImagePsdNativeFixture() {
    }

    public static void reset() {
        synchronized (EVENTS) {
            EVENTS.clear();
            EDT_EVENTS.clear();
        }
        throwOnSave = false;
        swallowSave = false;
        throwOnParse = false;
        parseFirstFlag = true;
        parseSecondFlag = true;
        parseTarget = null;
        constructedTarget = null;
        constructedName = null;
        lastProgress = null;
        afterSave = () -> {};
        saveFailure = () -> {};
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

    public static final class SyntheticProgress {
        public SyntheticProgress() {
        }
    }

    public static final class SyntheticProgressFactory {
        private SyntheticProgressFactory() {
        }

        public static SyntheticProgress e() {
            record("progress");
            return DEFAULT_PROGRESS;
        }
    }

    public static final class SyntheticParsed {
        private final File source;

        public SyntheticParsed(final File source) {
            this.source = source;
        }

        public File source() {
            return source;
        }
    }

    public static final class SyntheticCompanion {
        public SyntheticCompanion() {
        }

        public SyntheticParsed a(
            final File file,
            final boolean firstFlag,
            final boolean secondFlag
        ) {
            record("parse");
            parseTarget = file;
            parseFirstFlag = firstFlag;
            parseSecondFlag = secondFlag;
            if (throwOnParse) {
                throw new IllegalStateException("fixture parser failure");
            }
            if (!file.isFile() || file.length() == 0) {
                return null;
            }
            return new SyntheticParsed(file);
        }
    }

    public static final class SyntheticPsdDocument {
        public static final SyntheticCompanion a = new SyntheticCompanion();

        private SyntheticPsdDocument() {
        }
    }

    public static final class SyntheticLayeredImage {
        private final String name;

        public SyntheticLayeredImage(final String name) {
            this.name = name;
        }

        public String getName() {
            record("name");
            return name;
        }

        public void save(final File file, final SyntheticProgress progress) {
            record("save");
            lastProgress = progress;
            saveFailure.run();
            if (progress == null) {
                throw new IllegalStateException("fixture progress was null");
            }
            if (throwOnSave) {
                throw new IllegalStateException("fixture save failure");
            }
            if (swallowSave) {
                afterSave.run();
                return;
            }
            try {
                Files.writeString(file.toPath(), "synthetic-psd");
            } catch (IOException exception) {
                throw new IllegalStateException("fixture output failure", exception);
            }
            afterSave.run();
        }

        public SyntheticLayeredImage(
            final SyntheticParsed parsed,
            final File file,
            final String name
        ) {
            record("construct");
            if (parsed == null || parsed.source() != file) {
                throw new IllegalStateException("fixture constructor received the wrong parsed file");
            }
            this.name = name;
            constructedTarget = file;
            constructedName = name;
        }
    }
}
