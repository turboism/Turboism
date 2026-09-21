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
