package dev.turboism.validation.externalpsd;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline focused checks for the official current-document replacement baseline. */
public final class OfficialPsdReplacementBaselineTest {
    private static final String HOST_SHA =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";

    public static void main(final String[] args) throws Exception {
        testTaskOwnedSourceGate();
        testModelChooserIdentityGate();
        testRawChooserGuidGate();
        testChooserActionBoundary();
        testWrappedOptionSelection();
        testNativeRawResolution();
        testAuthoringColors();
        testInputDetails();
        testOfficialJarShape();
        System.out.println("PASS: OfficialPsdReplacementBaselineTest");
    }

    private static void testTaskOwnedSourceGate() throws Exception {
        final Path root = Files.createTempDirectory("025-replacement-source-");
        try {
            final Path task = root.resolve("task");
            Files.createDirectories(task);
            final Path source = task.resolve("controlled.psd");
            Files.write(source, new byte[] {1, 2, 3, 4, 5});
            final String sha = sha256(source);
            final var valid = OfficialPsdReplacementBaseline.validateTaskSourceForTest(
                source, task, sha, "controlled.psd", 5_000L);
            assertEquals(source.toAbsolutePath().normalize(), valid.path(),
                "task source path is retained");
            assertEquals(sha, valid.sha256(), "task source digest is verified");

            final Path outside = root.resolve("outside.psd");
            Files.write(outside, new byte[] {1, 2, 3, 4, 5});
            expectReject("outside task root", () ->
                OfficialPsdReplacementBaseline.validateTaskSourceForTest(
                    outside, task, sha, "outside.psd", 5_000L));
            expectReject("wrong fixed basename", () ->
                OfficialPsdReplacementBaseline.validateTaskSourceForTest(
                    source, task, sha, "other.psd", 5_000L));
            expectReject("wrong source digest", () ->
                OfficialPsdReplacementBaseline.validateTaskSourceForTest(
                    source, task, repeat('a'), "controlled.psd", 5_000L));

            final Path link = task.resolve("linked.psd");
            try {
                Files.createSymbolicLink(link, source.getFileName());
                expectReject("symlink source", () ->
                    OfficialPsdReplacementBaseline.validateTaskSourceForTest(
                        link, task, sha, "linked.psd", 5_000L));
            } catch (UnsupportedOperationException | IOException ignored) {
                // The containment gate is exercised above on platforms without symlink support.
            }

            final Path parentTarget = root.resolve("parent-target");
            Files.createDirectories(parentTarget);
            final Path parentLink = root.resolve("parent-link");
            try {
                Files.createSymbolicLink(parentLink, parentTarget.getFileName());
                final Path linkedRoot = parentLink.resolve("task");
                Files.createDirectories(linkedRoot);
                final Path linkedSource = linkedRoot.resolve("controlled.psd");
                Files.write(linkedSource, new byte[] {1, 2, 3, 4, 5});
                expectReject("symlink task-root parent", () ->
                    OfficialPsdReplacementBaseline.validateTaskSourceForTest(
                        linkedSource, linkedRoot, sha, "controlled.psd", 5_000L));
            } catch (UnsupportedOperationException | IOException ignored) {
                // The containment gate is exercised above on platforms without symlink support.
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void testModelChooserIdentityGate() throws Exception {
        final Object expectedDocument = new Object();
        final Object otherDocument = new Object();
        final Method getter = ModelOption.class.getDeclaredMethod("value");
        final List<ModelOption> options = List.of(new ModelOption(null),
            new ModelOption(otherDocument), new ModelOption(expectedDocument));
        assertEquals(2, OfficialPsdReplacementBaseline.uniqueIdentityIndex(
            options, ModelOption.class, expectedDocument, getter),
            "model chooser uses object identity, not label");
        expectReject("missing model document option", () ->
            OfficialPsdReplacementBaseline.uniqueIdentityIndex(
                List.of(new ModelOption(null), new ModelOption(otherDocument)),
                ModelOption.class, expectedDocument, getter));
        expectReject("duplicate model document option", () ->
            OfficialPsdReplacementBaseline.uniqueIdentityIndex(
                List.of(new ModelOption(expectedDocument), new ModelOption(expectedDocument)),
                ModelOption.class, expectedDocument, getter));
    }

    private static void testRawChooserGuidGate() {
        final Object expectedImage = new Object();
        final Object sameNameDifferentGuid = new Object();
        final Object sameGuidDifferentObject = new Object();
        final Map<Object, String> guids = new IdentityHashMap<>();
        guids.put(expectedImage, "guid-a");
        guids.put(sameNameDifferentGuid, "guid-b");
        guids.put(sameGuidDifferentObject, "guid-a");
        final List<RawOption> options = List.of(new RawOption(null, "Add image"),
            new RawOption(sameNameDifferentGuid, "same-name.psd"),
            new RawOption(expectedImage, "same-name.psd"));
        assertEquals(2, OfficialPsdReplacementBaseline.uniqueRawIndexForTest(
            options, RawOption.class, expectedImage, "guid-a",
            option -> ((RawOption) option).image(), guids::get),
            "raw chooser uses object identity and GUID, not same-name text");
        expectReject("duplicate raw GUID", () ->
            OfficialPsdReplacementBaseline.uniqueRawIndexForTest(
                List.of(new RawOption(null, "Add image"),
                    new RawOption(expectedImage, "same-name.psd"),
                    new RawOption(sameGuidDifferentObject, "same-name.psd")),
                RawOption.class, expectedImage, "guid-a",
                option -> ((RawOption) option).image(), guids::get));
        expectReject("missing raw object", () ->
            OfficialPsdReplacementBaseline.uniqueRawIndexForTest(
                List.of(new RawOption(null, "Add image"),
                    new RawOption(sameNameDifferentGuid, "same-name.psd")),
                RawOption.class, expectedImage, "guid-a",
                option -> ((RawOption) option).image(), guids::get));
    }

    private static void testChooserActionBoundary() {
        final Object owner = new Object();
        final Object dialog = new Object();
        final Object list = new Object();
        final Object target = new Object();
        final List<Object> options = List.of(new Object(), target);
        final OfficialPsdReplacementBaseline.ChooserShape shape =
            new OfficialPsdReplacementBaseline.ChooserShape(
                ListShape.class, RendererShape.class, OptionShape.class,
                ButtonShape.class, ButtonSubclassShape.class, ActionShape.class);
        final OfficialPsdReplacementBaseline.ChooserObservation observation =
            observation(owner, owner, dialog, list, options, ButtonShape.class, ActionShape.class);
        final AtomicInteger selections = new AtomicInteger();
        final AtomicInteger confirmations = new AtomicInteger();
        final OfficialPsdReplacementBaseline.ChooserActions actions = actions(
            selections, confirmations, target);

        final var accepted = OfficialPsdReplacementBaseline.executeChooserGate(observation,
            shape, 1, target, () -> true, () -> true, () -> true, () -> true, actions);
        assertTrue(accepted.accepted(), "valid chooser action is accepted");
        assertEquals(1, selections.get(), "valid chooser selects once");
        assertEquals(1, confirmations.get(), "valid chooser confirms once");

        final int selectionBefore = selections.get();
        final int confirmationBefore = confirmations.get();
        final var wrongOwner = OfficialPsdReplacementBaseline.executeChooserGate(
            observation(owner, new Object(), dialog, list, options, ButtonShape.class,
                ActionShape.class),
            shape, 1, target, () -> true, () -> true, () -> true, () -> true, actions);
        assertFalse(wrongOwner.accepted(), "wrong owner is rejected");
        assertEquals(selectionBefore, selections.get(), "wrong owner does not select");
        assertEquals(confirmationBefore, confirmations.get(), "wrong owner does not confirm");

        final var stopped = OfficialPsdReplacementBaseline.executeChooserGate(observation,
            shape, 1, target, () -> true, () -> false, () -> true, () -> true, actions);
        assertFalse(stopped.accepted(), "stopped queued action is rejected");
        assertEquals(selectionBefore, selections.get(), "stopped action does not select");
        assertEquals(confirmationBefore, confirmations.get(), "stopped action does not confirm");

        final var expired = OfficialPsdReplacementBaseline.executeChooserGate(observation,
            shape, 1, target, () -> true, () -> true, () -> true, () -> false, actions);
        assertFalse(expired.accepted(), "expired queued action is rejected");
        assertEquals(selectionBefore, selections.get(), "expired action does not select");
        assertEquals(confirmationBefore, confirmations.get(), "expired action does not confirm");

        final AtomicInteger guardCalls = new AtomicInteger();
        final var stoppedBetweenSelectionAndConfirm =
            OfficialPsdReplacementBaseline.executeChooserGate(observation, shape, 1, target,
                () -> true, () -> guardCalls.incrementAndGet() <= 2, () -> true, () -> true,
                actions);
        assertFalse(stoppedBetweenSelectionAndConfirm.accepted(),
            "stop between selection and confirmation is rejected");
        assertEquals(selectionBefore + 1, selections.get(),
            "late stop may retain the already performed selection");
        assertEquals(confirmationBefore, confirmations.get(),
            "late stop prevents confirmation");

        final var unknownOption = OfficialPsdReplacementBaseline.executeChooserGate(observation,
            new OfficialPsdReplacementBaseline.ChooserShape(
                ListShape.class, RendererShape.class, WrongOptionShape.class,
                ButtonShape.class, ButtonSubclassShape.class, ActionShape.class),
            1, target, () -> true, () -> true, () -> true, () -> true, actions);
        assertFalse(unknownOption.accepted(), "unknown option shape is rejected");
        assertEquals(selectionBefore + 1, selections.get(),
            "unknown option shape does not select");
        assertEquals(confirmationBefore, confirmations.get(),
            "unknown option shape does not confirm");
    }

    private static OfficialPsdReplacementBaseline.ChooserObservation observation(
        final Object owner, final Object dialogOwner, final Object dialog, final Object list,
        final List<?> options, final Class<?> button, final Class<?> action) {
        return new OfficialPsdReplacementBaseline.ChooserObservation(owner, dialogOwner, dialog, list,
            ListShape.class, RendererShape.class, OptionShape.class, options, button, action,
            "OK", true, true, true);
    }

    private static OfficialPsdReplacementBaseline.ChooserActions actions(
        final AtomicInteger selections, final AtomicInteger confirmations, final Object target) {
        return new OfficialPsdReplacementBaseline.ChooserActions() {
            @Override public boolean select(final int index) {
                selections.incrementAndGet();
                return index == 1;
            }

            @Override public void confirm() { confirmations.incrementAndGet(); }
        };
    }

    private static void testOfficialJarShape() throws Exception {
        final var shape = OfficialPsdReplacementBaseline.officialJarShapeForTest();
        assertEquals(HOST_SHA, shape.sha256(), "specified official JAR digest is admitted");
        assertTrue(shape.commandOpenShape().contains("command_open(java.io.File,boolean)"),
            "command_open(File,boolean) shape is verified");
        assertTrue(shape.modelOptionShape().contains("CModelingDocument"),
            "a$a.a() model getter shape is verified");
        assertTrue(shape.rawOptionShape().contains("CLayeredImage"),
            "a$b.a() raw getter shape is verified");
        assertTrue(shape.rawGuidShape().contains("getGuid"),
            "CLayeredImage.getGuid() shape is verified");
    }

    private static void testWrappedOptionSelection() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final Object document = new Object();
            final ModelOption special = new ModelOption(null);
            final ModelOption option = new ModelOption(document);
            final javax.swing.DefaultListModel<ModelOption> model = new javax.swing.DefaultListModel<>();
            model.addElement(special);
            model.addElement(option);
            final javax.swing.JList<ModelOption> list = new javax.swing.JList<>(model);
            assertTrue(OfficialPsdReplacementBaseline.selectExactOption(
                list, List.of(special, option), 1), "selects the wrapper returned by the real list");
            assertTrue(list.getSelectedValue() == option,
                "selected object is the option, not its contained native document");
            assertFalse(OfficialPsdReplacementBaseline.selectExactOption(
                list, List.of(special, document), 1), "native target is not a list option");
            list.clearSelection();
            list.addListSelectionListener(event -> {
                if (list.getSelectedIndex() == 1 && model.getElementAt(1) == option) {
                    model.setElementAt(new ModelOption(document), 1);
                }
            });
            assertFalse(OfficialPsdReplacementBaseline.selectExactOption(
                list, List.of(special, option), 1), "changed wrapper during selection is rejected");
        });
    }

    private static void testNativeRawResolution() throws Exception {
        final NativeImage image = new NativeImage(new NativeGuid("target"));
        final NativeImage other = new NativeImage(new NativeGuid("other"));
        final Method unwrap = NativeWrapper.class.getDeclaredMethod("image");
        final Method guid = NativeImage.class.getDeclaredMethod("guid");
        final Method string = NativeGuid.class.getDeclaredMethod("value");
        assertTrue(image == OfficialPsdReplacementBaseline.uniqueRawImage(
            List.of(new NativeWrapper(other), new NativeWrapper(image)),
            NativeWrapper.class, unwrap, guid, string, "target"),
            "native raw resolution uses GUID and returns the original image object");
        for (final List<?> invalid : List.of(
            List.of(new NativeWrapper(other)),
            List.of(new NativeWrapper(image), new NativeWrapper(image)),
            List.of(new NativeWrapper(image), new NativeWrapper(
                new NativeImage(new NativeGuid("target")))),
            List.of(new NativeWrapper(null)), List.of(new Object()))) {
            try {
                OfficialPsdReplacementBaseline.uniqueRawImage(invalid,
                    NativeWrapper.class, unwrap, guid, string, "target");
                throw new AssertionError("missing, duplicated or invalid raw was accepted");
            } catch (IllegalStateException expected) {
                // Reject both duplicate wrappers and distinct native objects with the same GUID.
            }
        }
    }

    private static void testAuthoringColors() throws Exception {
        final var access = new OfficialPsdReplacementBaseline.AuthoringColorAccess(
            Object.class, ColorMesh.class, ColorForm.class, NativeColor.class, null, null,
            ColorMesh.class.getDeclaredMethod("guid"), ColorMesh.class.getDeclaredMethod("form"),
            ColorForm.class.getDeclaredMethod("multiply"), ColorForm.class.getDeclaredMethod("screen"),
            NativeColor.class.getDeclaredMethod("red"), NativeColor.class.getDeclaredMethod("green"),
            NativeColor.class.getDeclaredMethod("blue"), NativeColor.class.getDeclaredMethod("alpha"));
        final Method guidString = NativeGuid.class.getDeclaredMethod("value");
        final NativeColor multiply = new NativeColor(.125f, .25f, .5f, .75f);
        final NativeColor screen = new NativeColor(.2f, .4f, .6f, .8f);
        final ColorMesh first = new ColorMesh(new NativeGuid("guid-a"), new ColorForm(multiply, screen));
        final ColorMesh second = new ColorMesh(new NativeGuid("guid-b"), new ColorForm(screen, multiply));
        final Map<String, String> ids = Map.of("guid-a", "mesh-a", "guid-b", "mesh-b");
        expectColorReject(() -> OfficialPsdReplacementBaseline.collectAuthoringColors(
            List.of(first, second), ids, access, guidString));
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                final var colors = OfficialPsdReplacementBaseline.collectAuthoringColors(
                    List.of(second, first), ids, access, guidString);
                assertEquals("guid-a", colors.get("mesh-a").guid(), "colors match by GUID, not index");
                assertEquals(new dev.turboism.sdk.cubism.model.Color(.125f, .25f, .5f, .75f),
                    colors.get("mesh-a").multiply(), "all multiply channels survive");
                assertEquals(new dev.turboism.sdk.cubism.model.Color(.2f, .4f, .6f, .8f),
                    colors.get("mesh-a").screen(), "all screen channels survive");
                for (final List<?> invalid : List.of(List.of(first), List.of(first, first),
                    List.of(new Object()), List.of(new ColorMesh(new NativeGuid("unknown"), first.form())),
                    List.of(new ColorMesh(first.guid(), null)),
                    List.of(new ColorMesh(first.guid(), new ColorForm(null, screen))),
                    List.of(new ColorMesh(first.guid(), new ColorForm(new NativeColor(
                        Float.NaN, 0f, 0f, 1f), screen))),
                    List.of(new ColorMesh(first.guid(), new ColorForm(multiply, new NativeColor(
                        0f, 0f, 0f, Float.POSITIVE_INFINITY)))))) {
                    expectColorReject(() -> OfficialPsdReplacementBaseline.collectAuthoringColors(
                        invalid, ids, access, guidString));
                }
                expectColorReject(() -> OfficialPsdReplacementBaseline.collectAuthoringColors(
                    List.of(first, second), Map.of("guid-a", "same-id", "guid-b", "same-id"),
                    access, guidString));
                expectColorReject(() -> OfficialPsdReplacementBaseline.collectAuthoringColors(
                    List.of(), Map.of(), access, guidString));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private static void expectColorReject(final ThrowingOperation operation) throws Exception {
        try { operation.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("unavailable or mismatched authoring colors were accepted");
    }

    private record ColorMesh(NativeGuid guid, ColorForm form) { }
    private record ColorForm(NativeColor multiply, NativeColor screen) { }
    private record NativeColor(float red, float green, float blue, float alpha) { }

    private static void testInputDetails() throws Exception {
        final var positions = InputClip.class.getDeclaredField("positions");
        final var indices = InputClip.class.getDeclaredField("indices");
        positions.setAccessible(true);
        indices.setAccessible(true);
        final var cachedTransform = InputImage.class.getDeclaredField("local");
        cachedTransform.setAccessible(true);
        final var components = new java.util.ArrayList<Method>();
        for (final String name : List.of("a", "b", "c", "d", "e", "f")) {
            components.add(InputAffine.class.getDeclaredMethod(name));
        }
        final var access = new OfficialPsdReplacementBaseline.InputDetailAccess(
            InputImage.class, InputEnv.class, InputSelector.class, InputRecord.class, InputLayer.class,
            InputAffine.class, InputClip.class, null, null,
            InputImage.class.getDeclaredMethod("guid"), InputImage.class.getDeclaredMethod("env"),
            cachedTransform, InputEnv.class.getDeclaredMethod("present"),
            InputEnv.class.getDeclaredMethod("selector"), InputSelector.class.getDeclaredMethod("map"),
            InputRecord.class.getDeclaredMethod("layer"), InputLayer.class.getDeclaredMethod("guid"),
            InputLayer.class.getDeclaredMethod("owner"), InputRecord.class.getDeclaredMethod("affine"),
            InputRecord.class.getDeclaredMethod("clip"), components, positions, indices);
        final Method guid = NativeGuid.class.getDeclaredMethod("value");
        final Method rawGuid = NativeImage.class.getDeclaredMethod("guid");
        final var raw = new NativeGuid("raw");
        final var layer = new InputLayer(new NativeGuid("layer"), new NativeImage(raw));
        final var affine = new InputAffine(1f, 0f, 2f, 0f, 1f, -3f);
        final var nullClip = new InputRecord(layer, affine, null);
        final var clipped = new InputRecord(layer, affine, new InputClip(new float[] {1, 2, 3, 4}, new int[] {1, 0}));
        final var original = inputImage(raw, affine, List.of(nullClip, clipped));
        expectColorReject(() -> OfficialPsdReplacementBaseline.collectInputDetails(List.of(original), access, guid, rawGuid));
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                final var first = OfficialPsdReplacementBaseline.collectInputDetails(List.of(original), access, guid, rawGuid);
                final var observed = first.get("image");
                assertTrue(observed.selectorPresent(), "selector presence is observed");
                final var records = observed.bindings().get("raw");
                assertEquals("null", records.get(0).clipping(), "null clipping is explicit absence");
                assertEquals("[0x1.0p0, 0x0.0p0, 0x1.0p1, 0x0.0p0, 0x1.0p0, -0x1.8p1]",
                    records.get(0).affine(), "all six affine components survive in fixed order");
                assertTrue(records.get(1).clipping().contains("indices=[1, 0]"), "clip indices retain order");
                final var reversed = OfficialPsdReplacementBaseline.collectInputDetails(
                    List.of(inputImage(raw, affine, List.of(clipped, nullClip))), access, guid, rawGuid);
                assertFalse(first.equals(reversed), "input record order changes must remain visible");
                final var empty = OfficialPsdReplacementBaseline.collectInputDetails(
                    List.of(inputImage(raw, affine, List.of())), access, guid, rawGuid);
                assertEquals(List.of(), empty.get("image").bindings().get("raw"), "empty raw binding retained");
                final var absent = OfficialPsdReplacementBaseline.collectInputDetails(List.of(
                    new InputImage(new NativeGuid("image"), new InputEnv(false, null), affine)), access, guid, rawGuid);
                assertFalse(empty.equals(absent), "absent selector differs from an empty binding");
                for (final List<?> invalid : List.of(List.of(original, original), List.of(new Object()),
                    List.of(inputImage(raw, affine, List.of(new InputRecord(
                        new InputLayer(layer.guid(), new NativeImage(new NativeGuid("wrong-owner"))), affine, null)))),
                    List.of(inputImage(raw, affine, List.of(new InputRecord(layer, affine, new Object())))),
                    List.of(inputImage(raw, affine, List.of(new InputRecord(layer,
                        new InputAffine(Float.NaN, 0, 0, 0, 0, 0), null)))),
                    List.of(inputImage(raw, affine, List.of(new InputRecord(layer, affine,
                        new InputClip(new float[] {Float.POSITIVE_INFINITY}, new int[] {}))))))) {
                    expectColorReject(() -> OfficialPsdReplacementBaseline.collectInputDetails(invalid, access, guid, rawGuid));
                }
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    private static InputImage inputImage(final NativeGuid raw, final InputAffine affine, final List<InputRecord> records) {
        return new InputImage(new NativeGuid("image"), new InputEnv(true,
            new InputSelector(Map.of(raw, records))), affine);
    }
    private record InputImage(NativeGuid guid, InputEnv env, InputAffine local) { }
    private record InputEnv(boolean present, InputSelector selector) { }
    private record InputSelector(Map<NativeGuid, List<InputRecord>> map) { }
    private record InputRecord(InputLayer layer, InputAffine affine, Object clip) { }
    private record InputLayer(NativeGuid guid, NativeImage owner) { }
    private record InputAffine(float a, float b, float c, float d, float e, float f) { }
    private record InputClip(float[] positions, int[] indices) { }

    private static String sha256(final Path path) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteTree(final Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException failure) { throw new RuntimeException(failure); }
                });
        }
    }

    private static String repeat(final char value) {
        return String.valueOf(value).repeat(64);
    }

    private static void expectReject(final String name, final ThrowingOperation operation) {
        try {
            operation.run();
            throw new AssertionError(name + " was accepted");
        } catch (IllegalArgumentException expected) {
            // expected fail-closed result
        } catch (Exception failure) {
            throw new AssertionError(name + " failed with the wrong exception", failure);
        }
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(final boolean value, final String message) {
        if (value) throw new AssertionError(message);
    }

    private static void assertEquals(final Object expected, final Object actual,
        final String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation { void run() throws Exception; }

    private record ModelOption(Object value) { }
    private record RawOption(Object image, String name) { }
    private record NativeWrapper(NativeImage image) { }
    private record NativeImage(NativeGuid guid) { }
    private record NativeGuid(String value) { }
    private static final class ListShape { }
    private static final class RendererShape { }
    private static final class OptionShape { }
    private static final class WrongOptionShape { }
    private static class ButtonShape { }
    private static final class ButtonSubclassShape extends ButtonShape { }
    private static final class ActionShape { }
}
