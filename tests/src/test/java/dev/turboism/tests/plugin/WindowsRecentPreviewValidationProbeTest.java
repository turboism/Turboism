package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import javax.swing.ImageIcon;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.io.TempDir;

class WindowsRecentPreviewValidationProbeTest {

    @Test
    void opaqueIdIsExactly64LowercaseHex() {
        assertTrue(WindowsRecentPreviewValidationProbe.isOpaqueHexId(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertFalse(WindowsRecentPreviewValidationProbe.isOpaqueHexId(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeF"));
        assertFalse(WindowsRecentPreviewValidationProbe.isOpaqueHexId(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeg"));
        assertFalse(WindowsRecentPreviewValidationProbe.isOpaqueHexId(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde"));
        assertFalse(WindowsRecentPreviewValidationProbe.isOpaqueHexId(""));
        assertFalse(WindowsRecentPreviewValidationProbe.isOpaqueHexId(null));
    }

    @Test
    void closeRouteMapsSupportedVersionsAndFailsClosed() {
        assertEquals(
            WindowsRecentPreviewValidationProbe.HostCloseRoute.SYNTHETIC_WINDOW_CLOSING,
            WindowsRecentPreviewValidationProbe.hostCloseRoute("5203"));
        assertEquals(
            WindowsRecentPreviewValidationProbe.HostCloseRoute.ROBOT_ALT_F4,
            WindowsRecentPreviewValidationProbe.hostCloseRoute("5302"));
        assertThrows(IllegalArgumentException.class,
            () -> WindowsRecentPreviewValidationProbe.hostCloseRoute("5100"));
        assertThrows(IllegalArgumentException.class,
            () -> WindowsRecentPreviewValidationProbe.hostCloseRoute(null));
    }

    @Test
    void absolutePathRecognitionCoversWindowsAndUnixForms() {
        assertTrue(WindowsRecentPreviewValidationProbe.isAbsolutePath("C:\\Users\\rain\\file.cmo3"));
        assertTrue(WindowsRecentPreviewValidationProbe.isAbsolutePath("Z:/home/local-user/file.cmo3"));
        assertTrue(WindowsRecentPreviewValidationProbe.isAbsolutePath("/home/local-user/file.cmo3"));
        assertTrue(WindowsRecentPreviewValidationProbe.isAbsolutePath("\\\\server\\share\\file.cmo3"));
        assertFalse(WindowsRecentPreviewValidationProbe.isAbsolutePath("relative/file.cmo3"));
        assertFalse(WindowsRecentPreviewValidationProbe.isAbsolutePath(""));
        assertFalse(WindowsRecentPreviewValidationProbe.isAbsolutePath(null));
    }

    @Test
    void fixturePathSuffixMatchingAcceptsBothSeparators() {
        assertTrue(WindowsRecentPreviewValidationProbe.endsWithSeparator(
            "C:\\home\\rain\\fixture.cmo3", "fixture.cmo3"));
        assertTrue(WindowsRecentPreviewValidationProbe.endsWithSeparator(
            "Z:/home/local-user/fixture.cmo3", "fixture.cmo3"));
        assertTrue(WindowsRecentPreviewValidationProbe.endsWithSeparator(
            "fixture.cmo3", "fixture.cmo3"));
        assertFalse(WindowsRecentPreviewValidationProbe.endsWithSeparator(
            "C:\\home\\rain\\other.cmo3", "fixture.cmo3"));
        assertFalse(WindowsRecentPreviewValidationProbe.endsWithSeparator(null, "fixture.cmo3"));
    }

    @Test
    void savedModelNameMatchesFixtureStemOrFullName() {
        assertTrue(WindowsRecentPreviewValidationProbe.matchesFixtureName("fixture.cmo3"));
        assertTrue(WindowsRecentPreviewValidationProbe.matchesFixtureName("fixture"));
        assertTrue(WindowsRecentPreviewValidationProbe.matchesFixtureName("fixture.cmo3.backup"));
        assertFalse(WindowsRecentPreviewValidationProbe.matchesFixtureName("other"));
        assertFalse(WindowsRecentPreviewValidationProbe.matchesFixtureName(""));
        assertFalse(WindowsRecentPreviewValidationProbe.matchesFixtureName(null));
    }

    @Test
    void saveOutcomeMappingDistinguishesHookFromKeyboardProblems() {
        assertNull(WindowsRecentPreviewValidationProbe.saveFailurePhase(true, false));
        assertNull(WindowsRecentPreviewValidationProbe.saveFailurePhase(true, true));
        assertEquals("save", WindowsRecentPreviewValidationProbe.saveFailurePhase(false, false));
        assertEquals("save-event", WindowsRecentPreviewValidationProbe.saveFailurePhase(false, true));
    }

    @Test
    void saveDiagnosticLineIsBooleanOnlyAndPathFree() {
        assertEquals("Recent preview save diagnostic savedEvent=false fileModified=true menuPath=menu",
            WindowsRecentPreviewValidationProbe.saveDiagnostic(false, true, "menu"));
        assertEquals("Recent preview save diagnostic savedEvent=true fileModified=false menuPath=ctrls",
            WindowsRecentPreviewValidationProbe.saveDiagnostic(true, false, "ctrls"));
        final String line = WindowsRecentPreviewValidationProbe.saveDiagnostic(false, true, "ctrls");
        assertFalse(WindowsRecentPreviewValidationProbe.containsAbsolutePath(line));
        assertFalse(line.contains("\\"));
        assertFalse(line.contains("/"));
    }

    @Test
    void saveMenuPathCoversMenuCtrlsAndNone() {
        assertEquals("menu", WindowsRecentPreviewValidationProbe.saveMenuPath(true, false));
        assertEquals("menu", WindowsRecentPreviewValidationProbe.saveMenuPath(true, true));
        assertEquals("ctrls", WindowsRecentPreviewValidationProbe.saveMenuPath(false, true));
        assertEquals("none", WindowsRecentPreviewValidationProbe.saveMenuPath(false, false));
    }

    @Test
    void closeDialogDecisionOnlyForNewDialogWhileContinuing() {
        assertTrue(WindowsRecentPreviewValidationProbe.shouldCloseDialog(false, false, true));
        assertFalse(WindowsRecentPreviewValidationProbe.shouldCloseDialog(true, false, true));
        assertFalse(WindowsRecentPreviewValidationProbe.shouldCloseDialog(false, true, true));
        assertFalse(WindowsRecentPreviewValidationProbe.shouldCloseDialog(false, false, false));
    }

    @Test
    void dialogIdentityIsClassHashKeyed() {
        assertEquals("javax.swing.JDialog@42",
            WindowsRecentPreviewValidationProbe.dialogIdentity("javax.swing.JDialog", 42));
        assertEquals("a@0", WindowsRecentPreviewValidationProbe.dialogIdentity("a", 0));
    }

    @Test
    void popupClassRecognitionIsShowingAndClassNameBased() {
        assertTrue(WindowsRecentPreviewValidationProbe.isPopupWindow(true, "javax.swing.JPopupMenu"));
        assertTrue(WindowsRecentPreviewValidationProbe.isPopupWindow(true, "javax.swing.JPopupMenu$HeavyWeightWindow"));
        assertFalse(WindowsRecentPreviewValidationProbe.isPopupWindow(false, "javax.swing.JPopupMenu"));
        assertFalse(WindowsRecentPreviewValidationProbe.isPopupWindow(true, "javax.swing.JFrame"));
        assertFalse(WindowsRecentPreviewValidationProbe.isPopupWindow(true, null));
        assertFalse(WindowsRecentPreviewValidationProbe.isPopupWindow(true, "javax.swing.JRootPane"));
    }

    @Test
    void fileModifiedSinceTracksTimeAndSizeBaselines(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("fixture.cmo3");
        Files.writeString(file, "v1");
        final long baselineModified = Files.getLastModifiedTime(file).toMillis();
        final long baselineSize = Files.size(file);
        assertFalse(WindowsRecentPreviewValidationProbe.fileModifiedSince(
            file, baselineModified, baselineSize));

        Files.writeString(file, "v1-longer-content");
        assertTrue(WindowsRecentPreviewValidationProbe.fileModifiedSince(
            file, baselineModified, baselineSize));

        // time-only change (same size, explicitly newer lastModified)
        final long size = Files.size(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(baselineModified + 60_000L));
        assertEquals(size, Files.size(file));
        assertTrue(WindowsRecentPreviewValidationProbe.fileModifiedSince(
            file, baselineModified, baselineSize));

        // missing file reads as not modified (deleted during the wait)
        final Path missing = dir.resolve("gone.cmo3");
        assertFalse(WindowsRecentPreviewValidationProbe.fileModifiedSince(
            missing, baselineModified, baselineSize));
    }

    @Test
    void resultAndIndexContentNeverContainAbsolutePaths() {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", "1");
        fields.put("runId", "recent-preview-5302-r1-20260805T000000Z");
        fields.put("hostVersion", "5302");
        fields.put("fixtureName", "fixture.cmo3");
        fields.put("recentCount", "4");
        fields.put("idOpaque", "true");
        fields.put("pathAbsolute", "true");
        fields.put("pathEndsWithFixture", "true");
        fields.put("directCaptureWidth", "150");
        fields.put("directCaptureHeight", "150");
        fields.put("directPngSha256",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        fields.put("directPngColors", "12");
        fields.put("savedEventMatched", "true");
        fields.put("productionCachePng", "true");
        fields.put("productionIndex", "true");
        fields.put("popupThumbnail", "true");
        fields.put("status", "PASS");
        final String content = WindowsRecentPreviewValidationProbe.resultContent(fields);
        assertFalse(WindowsRecentPreviewValidationProbe.containsAbsolutePath(content));
        assertTrue(content.contains("\nstatus=PASS\n"));

        final String failure = WindowsRecentPreviewValidationProbe.failureResult(
            "java.lang.IllegalStateException", "popup");
        assertFalse(WindowsRecentPreviewValidationProbe.containsAbsolutePath(failure));
        assertTrue(failure.contains("failureClass=java.lang.IllegalStateException"));
        assertTrue(failure.contains("failurePhase=popup"));

        final String saveEventFailure = WindowsRecentPreviewValidationProbe.failureResult(
            "java.lang.IllegalStateException", "save-event");
        assertFalse(WindowsRecentPreviewValidationProbe.containsAbsolutePath(saveEventFailure));
        assertTrue(saveEventFailure.contains("failurePhase=save-event"));

        final String index = "v2\n" + fields.get("directPngSha256") + "\nfixture.cmo3\n150\n150\n";
        assertFalse(WindowsRecentPreviewValidationProbe.containsAbsolutePath(index));

        assertTrue(WindowsRecentPreviewValidationProbe.containsAbsolutePath("C:\\Users\\rain\\file.cmo3"));
        assertTrue(WindowsRecentPreviewValidationProbe.containsAbsolutePath("Z:/home/local-user/file.cmo3"));
        assertTrue(WindowsRecentPreviewValidationProbe.containsAbsolutePath("/home/local-user/file.cmo3"));
    }

    @Test
    void directPngAndThumbnailAreBoundedReadableAndMultiColor() throws Exception {
        final BufferedImage source = new BufferedImage(150, 150, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, x < 75 ? 0xFF0000 : 0x0000FF);
            }
        }
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", png));
        final byte[] pngBytes = png.toByteArray();
        assertTrue(WindowsRecentPreviewValidationProbe.isBoundedPng(pngBytes, 150));
        assertEquals(2, WindowsRecentPreviewValidationProbe.distinctSampledColors(source));
        assertTrue(WindowsRecentPreviewValidationProbe.validThumbnailIcon(new ImageIcon(pngBytes)));
        assertFalse(WindowsRecentPreviewValidationProbe.validThumbnailIcon(null));

        final BufferedImage oversized = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        final ByteArrayOutputStream bigPng = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(oversized, "png", bigPng));
        assertFalse(WindowsRecentPreviewValidationProbe.isBoundedPng(bigPng.toByteArray(), 150));
        assertFalse(WindowsRecentPreviewValidationProbe.isBoundedPng(new byte[]{1, 2, 3}, 150));
    }

    @Test
    void denseSamplingDetectsFourColorsOnThumbnailSizedImage() {
        final BufferedImage source = new BufferedImage(58, 150, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                final int color = x < 29 ? (y < 75 ? 0xFF0000 : 0x0000FF)
                    : (y < 75 ? 0x00FF00 : 0x000000);
                source.setRGB(x, y, color);
            }
        }
        assertEquals(4, WindowsRecentPreviewValidationProbe.distinctSampledColors(source));
        assertEquals(4, WindowsRecentPreviewValidationProbe.distinctSampledColors(source));

        final BufferedImage single = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        single.setRGB(0, 0, 0xFF0000);
        assertEquals(1, WindowsRecentPreviewValidationProbe.distinctSampledColors(single));
    }

    @Test
    void sha256HexIsStableLowercase64() {
        final String digest = WindowsRecentPreviewValidationProbe.sha256Hex(
            "fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(64, digest.length());
        assertTrue(digest.matches("[0-9a-f]{64}"));
        assertEquals(digest, WindowsRecentPreviewValidationProbe.sha256Hex(
            "fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
