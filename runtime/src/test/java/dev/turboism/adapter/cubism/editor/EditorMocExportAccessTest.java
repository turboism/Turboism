package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.selector.EditorMocExportSelectorContract;
import dev.turboism.sdk.cubism.model.CubismMocExport;
import dev.turboism.sdk.cubism.model.CubismMocExportSettings;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.*;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;

class EditorMocExportAccessTest {
    private static final String P = "cubism.editor-model.official-export.";
    private static final CubismMocExportSettings SETTINGS = new CubismMocExportSettings(
        512, 256, 100, true, false, true, false, true);

    @Test void copiesOfficialBytesAndTexturesWithEverySettingExplicitOnEdt() {
        Source source = new Source();
        CubismMocExport export = export(source);
        assertEquals(1, source.calls);
        assertTrue(source.edt);
        assertEquals(List.of(Format.V530, 512f, 256f, 100f, true, false, true, false, true), source.settings.values);
        assertEquals(0xff102030, export.textures().get(0).argb()[0]);
        assertEquals(0xff405060, export.textures().get(1).argb()[0]);
        source.result.bytes.set(0, (byte) 0);
        source.result.images.get(0).raster.setRGB(0, 0, 0);
        assertEquals('M', export.moc3()[0]);
        assertEquals(0xff102030, export.textures().get(0).argb()[0]);
    }

    @Test void rejectsMissingCapabilityWrongVersionAndStaleModelBeforeExport() {
        Source source = new Source();
        assertThrows(UnsupportedOperationException.class, () -> access("5.3.03", true, false)
            .exportMoc("model", source, source.current, SETTINGS));
        assertThrows(UnsupportedOperationException.class, () -> access("5.3.02", false, false)
            .exportMoc("model", source, source.current, SETTINGS));
        assertThrows(IllegalStateException.class, () -> access("5.3.02", true, true)
            .exportMoc("model", source, source.current, SETTINGS));
        assertEquals(0, source.calls);
    }

    @Test void requiresAtlasBeforeCallingExporter() {
        Source source = new Source(); source.manager.atlases.clear();
        assertThrows(IllegalStateException.class, () -> export(source));
        assertEquals(0, source.calls);
    }

    @Test void rejectsOversizedMocBeforeReadingElements() {
        Source source = new Source();
        source.result.bytes = new AbstractList<>() {
            @Override public Object get(int index) { throw new AssertionError("must not read oversized list"); }
            @Override public int size() { return CubismMocExport.MAX_MOC_BYTES + 1; }
        };
        var failure = assertThrows(IllegalStateException.class, () -> export(source));
        assertTrue(failure.getMessage().contains("length=" + (CubismMocExport.MAX_MOC_BYTES + 1)));
        assertTrue(failure.getMessage().contains("maximum=" + CubismMocExport.MAX_MOC_BYTES));
    }

    @Test void distinguishesEmptyOfficialOutputFromAnOversizedResult() {
        Source source = new Source(); source.result.bytes = List.of();
        var failure = assertThrows(IllegalStateException.class, () -> export(source));
        assertTrue(failure.getMessage().contains("length=0,"));
    }

    @Test void rejectsMalformedNativeByteAndRasterWithoutFallback() {
        Source source = new Source(); source.result.bytes.set(0, 77);
        assertThrows(IllegalStateException.class, () -> export(source));
        Source badHeader = new Source(); badHeader.result.bytes.set(0, (byte) 0);
        assertThrows(IllegalArgumentException.class, () -> export(badHeader));
        Source badRaster = new Source(); badRaster.result.images.get(0).width = 2;
        assertThrows(IllegalStateException.class, () -> export(badRaster));
    }

    @Test void enforcesAggregatePixelBudgetBeforeReadingLargeRaster() {
        Source source = new Source();
        Image image = source.result.images.get(0); image.width = 8192; image.height = 8192;
        assertThrows(IllegalStateException.class, () -> export(source));
        assertEquals(0, image.reads);
    }

    @Test void refusesChangedModelMembershipEvenAfterSuccessfulNativeExport() {
        Source source = new Source(); source.mutate = true;
        assertThrows(IllegalStateException.class, () -> export(source));
    }

    @Test void rejectsResultIfDocumentBecomesStaleDuringExport() {
        Source source = new Source();
        var resolver = TestVerifiedResolvers.create("5.3.02", EditorMocExportSelectorContract.ADAPTER_SLICE_ID,
            Set.of(EditorMocExportSelectorContract.CAPABILITY_ID), selectors(), Source.class.getClassLoader());
        int[] guards = {0};
        var access = new EditorMocExportAccess(resolver, (identity, model) -> {
            if (++guards[0] == 2) throw new IllegalStateException("stale after export");
        });
        assertThrows(IllegalStateException.class,
            () -> access.exportMoc("model", source, source.current, SETTINGS));
        assertEquals(1, source.calls);
        assertEquals(2, guards[0]);
    }

    @Test void rejectsTooManyTexturesBeforeReadingTheirPixels() {
        Source source = new Source();
        Image image = source.result.images.get(0);
        source.result.images.clear();
        source.result.images.addAll(Collections.nCopies(33, image));
        assertThrows(IllegalStateException.class, () -> export(source));
        assertEquals(0, image.reads);
    }

    @Test void keepsNativeFailureAndReportsPostconditionFailure() {
        Source source = new Source(); source.mutate = true; source.fail = true;
        RuntimeException failure = assertThrows(RuntimeException.class, () -> export(source));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("changed"));
    }

    private static CubismMocExport export(Source source) {
        return access("5.3.02", true, false).exportMoc("model", source, source.current, SETTINGS);
    }

    private static EditorMocExportAccess access(String version, boolean authorized, boolean stale) {
        var resolver = TestVerifiedResolvers.create(version, EditorMocExportSelectorContract.ADAPTER_SLICE_ID,
            authorized ? Set.of(EditorMocExportSelectorContract.CAPABILITY_ID) : Set.of("cubism.editor-model.model-instance.read"),
            selectors(), Source.class.getClassLoader());
        return new EditorMocExportAccess(resolver, (identity, model) -> {
            if (stale) throw new IllegalStateException("stale model");
        });
    }

    private static List<StaticSelector> selectors() {
        List<StaticSelector> out = new ArrayList<>();
        out.add(StaticSelector.constructor(P + "exporter-create", internal(Exporter.class), "()V", 1));
        out.add(StaticSelector.constructor(P + "settings-create", internal(Settings.class), "()V", 1));
        out.add(StaticSelector.field(P + "format-530", internal(Format.class), "V530", type(Format.class), 9));
        out.add(method(P + "exporter-settings", Exporter.class, "settings", "(" + type(Settings.class) + ")V"));
        out.add(method(P + "export", Exporter.class, "export", "(" + type(Source.class) + "Ljava/lang/Object;)" + type(Result.class)));
        out.add(method(P + "set-format", Settings.class, "format", "(" + type(Format.class) + ")V"));
        for (String field : List.of("origin-x", "origin-y", "pixels-per-unit")) {
            out.add(method(P + "set-" + field, Settings.class, "number", "(F)V"));
        }
        for (String field : List.of("invisible-part", "sketch-part", "invisible-mesh", "invisible-alias", "extend-edge")) {
            out.add(method(P + "set-" + field, Settings.class, "flag", "(Z)V"));
        }
        out.add(method(P + "moc-bytes", Result.class, "bytes", "()Ljava/util/List;"));
        out.add(method(P + "texture-images", Result.class, "images", "()Ljava/util/List;"));
        out.add(method("cubism.editor-model.model-source.current-instance", Source.class, "current", "()Ljava/lang/Object;"));
        out.add(method("cubism.editor-model.model-source.model-instances", Source.class, "instances", "()Ljava/util/List;"));
        out.add(method("cubism.texture-atlas.model-source.texture-manager", Source.class, "manager", "()" + type(Manager.class)));
        out.add(method("cubism.texture-atlas.texture-manager.atlases", Manager.class, "atlases", "()Ljava/util/List;"));
        out.add(method("cubism.editor-model.image-resource.width", Image.class, "width", "()I"));
        out.add(method("cubism.editor-model.image-resource.height", Image.class, "height", "()I"));
        out.add(method("cubism.editor-model.image-resource.image", Image.class, "image", "()" + type(Image.class)));
        out.add(method("cubism.editor-model.writable-image.j-buffered-image", Image.class, "raster", "()Ljava/awt/image/BufferedImage;"));
        return out;
    }
    private static StaticSelector method(String alias, Class<?> owner, String name, String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, 1);
    }
    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }
    private static String type(Class<?> type) { return "L" + internal(type) + ";"; }

    public enum Format { V530 }
    public static final class Settings {
        final List<Object> values = new ArrayList<>();
        public Settings() { }
        public void format(Format value) { values.add(value); }
        public void number(float value) { values.add(value); }
        public void flag(boolean value) { values.add(value); }
    }
    public static final class Exporter {
        Settings settings;
        public Exporter() { }
        public void settings(Settings value) { settings = value; }
        public Result export(Source source, Object progress) {
            assertNull(progress); source.calls++; source.edt = SwingUtilities.isEventDispatchThread();
            source.settings = settings;
            if (source.mutate) source.instances.add(new Object());
            if (source.fail) throw new IllegalStateException("native export failure");
            return source.result;
        }
    }
    public static final class Source {
        final Object current = new Object();
        final List<Object> instances = new ArrayList<>(List.of(current));
        final Manager manager = new Manager();
        final Result result = new Result();
        Settings settings; int calls; boolean edt; boolean mutate; boolean fail;
        public Object current() { return current; }
        public List<Object> instances() { return instances; }
        public Manager manager() { return manager; }
    }
    public static final class Manager {
        final List<Object> atlases = new ArrayList<>(List.of(new Object()));
        public List<Object> atlases() { return atlases; }
    }
    public static final class Result {
        List<Object> bytes = new ArrayList<>();
        final List<Image> images = new ArrayList<>(List.of(new Image(0xff102030), new Image(0xff405060)));
        Result() {
            for (int i = 0; i < 64; i++) bytes.add((byte) 0);
            for (int i = 0; i < 4; i++) bytes.set(i, (byte) "MOC3".charAt(i));
        }
        public List<Object> bytes() { return bytes; }
        public List<Image> images() { return images; }
    }
    public static final class Image {
        int width = 1; int height = 1; int reads;
        final BufferedImage raster = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Image(int pixel) { raster.setRGB(0, 0, pixel); }
        public int width() { return width; }
        public int height() { return height; }
        public Image image() { reads++; return this; }
        public BufferedImage raster() { return raster; }
    }
}
