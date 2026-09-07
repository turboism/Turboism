package dev.turboism.validation.boundingbox.selfcheck;

import dev.turboism.validation.boundingbox.BoundingBoxIconDetector;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runnable synthetic-image self-check for {@link BoundingBoxIconDetector}.
 *
 * <p>Executed by the probe build script before the plugin JAR is assembled and
 * never packaged into the JAR. Covers detector success on the real generated
 * icon PNGs, the exact observed 24x23 / 40 px vertical geometry, ambiguity
 * rejection, absence detection, the bounded DPI size band, reversed-order and
 * horizontal-misalignment rejection, too-small/too-large vertical spacing
 * rejection, tolerance matching, edge blending tolerance, the context-bound
 * absence rules (stable-background absence accepted; occlusion/context-change
 * rejected), and — when the task-local diagnostic screenshot is available —
 * an offline replay of the exact 5.2.03 capture that must return the observed
 * pair at centers (550,524) and (550,564).</p>
 */
public final class DetectorSelfCheck {

    private static final int BACKGROUND = 0x333333;
    private static final int MAGENTA = 0xFF00FF;
    private static final int CYAN = 0x00FFFF;
    private static final int BLEND = 0xFF80FF;

    /** Exact observed 5.2.03 geometry: A/B 24x23, dx=0, dy=40 (native 40-unit step). */
    private static final int OBSERVED_A_X = 539;
    private static final int OBSERVED_A_Y = 513;
    private static final int OBSERVED_SIZE_W = 24;
    private static final int OBSERVED_SIZE_H = 23;
    private static final int OBSERVED_CENTER_AX = 550;
    private static final int OBSERVED_CENTER_AY = 524;
    private static final int OBSERVED_CENTER_BX = 550;
    private static final int OBSERVED_CENTER_BY = 564;

    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(final String[] args) {
        baselinePairDetected();
        realIconPngsDetected(args);
        exactObservedGeometryAccepted();
        if (args.length >= 3) {
            replayScreenshotDetected(args[2]);
        }
        ambiguityRejected();
        absenceDetected();
        dpiScaledPairAccepted();
        reversedPairRejected();
        horizontalMisalignmentRejected();
        verticalSpacingTooSmallRejected();
        verticalSpacingTooLargeRejected();
        oversizedRegionRejected();
        toleranceColorAccepted();
        blendedEdgesTolerated();
        stableBackgroundAbsenceAccepted();
        occlusionContextChangeRejected();
        if (FAILURES.isEmpty()) {
            System.out.println("BOUNDING_BOX_DETECTOR_SELFCHECK status=PASS checks="
                + (args.length >= 3 ? 16 : 15));
            return;
        }
        for (String failure : FAILURES) {
            System.err.println("BOUNDING_BOX_DETECTOR_SELFCHECK failure: " + failure);
        }
        System.err.println("BOUNDING_BOX_DETECTOR_SELFCHECK status=FAIL checks=" + FAILURES.size());
        Runtime.getRuntime().exit(1);
    }

    private static void baselinePairDetected() {
        final BufferedImage image = canvas(320, 180);
        fill(image, 150, 80, 16, 16, MAGENTA);
        fill(image, 150, 120, 16, 16, CYAN);
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
        check("baseline vertical pair detected", result.pair() != null, result.reason());
        if (result.pair() != null) {
            check("baseline center A", result.pair().a().x() == 157 && result.pair().a().y() == 87,
                "actual=(" + result.pair().a().x() + "," + result.pair().a().y() + ")");
            check("baseline center B", result.pair().b().x() == 157 && result.pair().b().y() == 127,
                "actual=(" + result.pair().b().x() + "," + result.pair().b().y() + ")");
            check("baseline centers distinct",
                result.pair().b().y() - result.pair().a().y() >= BoundingBoxIconDetector.MIN_PAIR_DY,
                "dy=" + (result.pair().b().y() - result.pair().a().y()));
        }
    }

    private static void realIconPngsDetected(final String[] args) {
        if (args.length < 2) {
            check("real icon pngs provided", false, "expected two icon paths");
            return;
        }
        try {
            final BufferedImage iconA = ImageIO.read(new File(args[0]));
            final BufferedImage iconB = ImageIO.read(new File(args[1]));
            check("icon A decodes 16x16",
                iconA != null && iconA.getWidth() == 16 && iconA.getHeight() == 16,
                iconA == null ? "null" : iconA.getWidth() + "x" + iconA.getHeight());
            check("icon B decodes 16x16",
                iconB != null && iconB.getWidth() == 16 && iconB.getHeight() == 16,
                iconB == null ? "null" : iconB.getWidth() + "x" + iconB.getHeight());
            if (iconA == null || iconB == null) {
                return;
            }
            check("icon A center pixel magenta",
                (iconA.getRGB(8, 8) & 0xFFFFFF) == MAGENTA,
                String.format("%06X", iconA.getRGB(8, 8) & 0xFFFFFF));
            check("icon B center pixel cyan",
                (iconB.getRGB(8, 8) & 0xFFFFFF) == CYAN,
                String.format("%06X", iconB.getRGB(8, 8) & 0xFFFFFF));
            final BufferedImage image = canvas(320, 180);
            image.getGraphics().drawImage(iconA, 150, 80, null);
            image.getGraphics().drawImage(iconB, 150, 120, null);
            final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
            check("real icons pair detected", result.pair() != null, result.reason());
        } catch (Exception failure) {
            check("real icon pngs decoded", false, failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    /**
     * Mandatory synthetic regression for the exact observed 5.2.03 capture:
     * A/B 24x23, dx=0, dy=40, centers (550,524) and (550,564).
     */
    private static void exactObservedGeometryAccepted() {
        final BufferedImage image = canvas(700, 700);
        fill(image, OBSERVED_A_X, OBSERVED_A_Y, OBSERVED_SIZE_W, OBSERVED_SIZE_H, MAGENTA);
        fill(image, OBSERVED_A_X, OBSERVED_A_Y + 40, OBSERVED_SIZE_W, OBSERVED_SIZE_H, CYAN);
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
        check("exact observed geometry accepted", result.pair() != null, result.reason());
        if (result.pair() != null) {
            check("exact observed center A",
                result.pair().a().x() == OBSERVED_CENTER_AX && result.pair().a().y() == OBSERVED_CENTER_AY,
                "actual=(" + result.pair().a().x() + "," + result.pair().a().y() + ")");
            check("exact observed center B",
                result.pair().b().x() == OBSERVED_CENTER_BX && result.pair().b().y() == OBSERVED_CENTER_BY,
                "actual=(" + result.pair().b().x() + "," + result.pair().b().y() + ")");
            check("exact observed alignment",
                Math.abs(result.pair().b().x() - result.pair().a().x()) <= 1
                    && result.pair().b().y() - result.pair().a().y() == 40,
                "dx=" + (result.pair().b().x() - result.pair().a().x())
                    + " dy=" + (result.pair().b().y() - result.pair().a().y()));
        }
    }

    /**
     * Offline replay gate for the exact diagnostic capture
     * {@code /tmp/overlay-d2-ui-tree-doubleclick-probe-r4-after.png}: one valid
     * pair at the observed centers (550,524) and (550,564) is mandatory. The
     * build script passes the path only when the task-local screenshot exists,
     * so its absence never breaks a clean build.
     */
    private static void replayScreenshotDetected(final String path) {
        try {
            final BufferedImage image = ImageIO.read(new File(path));
            check("replay screenshot decodes", image != null, path);
            if (image == null) {
                return;
            }
            final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
            check("replay returns one valid pair",
                result.pair() != null && result.aCount() == 1 && result.bCount() == 1,
                "pair=" + (result.pair() != null) + " a=" + result.aCount() + " b=" + result.bCount()
                    + " reason=" + result.reason());
            if (result.pair() != null) {
                check("replay center A",
                    Math.abs(result.pair().a().x() - OBSERVED_CENTER_AX) <= 1
                        && Math.abs(result.pair().a().y() - OBSERVED_CENTER_AY) <= 1,
                    "actual=(" + result.pair().a().x() + "," + result.pair().a().y() + ")");
                check("replay center B",
                    Math.abs(result.pair().b().x() - OBSERVED_CENTER_BX) <= 1
                        && Math.abs(result.pair().b().y() - OBSERVED_CENTER_BY) <= 1,
                    "actual=(" + result.pair().b().x() + "," + result.pair().b().y() + ")");
            }
        } catch (Exception failure) {
            check("replay screenshot decoded", false, failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private static void ambiguityRejected() {
        final BufferedImage image = canvas(320, 180);
        fill(image, 150, 80, 16, 16, MAGENTA);
        fill(image, 50, 30, 16, 16, MAGENTA);
        fill(image, 150, 120, 16, 16, CYAN);
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
        check("ambiguity rejected", result.pair() == null && result.aCount() == 2,
            "pair=" + (result.pair() != null) + " aCount=" + result.aCount() + " reason=" + result.reason());
    }

    private static void absenceDetected() {
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(canvas(320, 180));
        check("absence detected",
            result.pair() == null && result.aCount() == 0 && result.bCount() == 0
                && "absent".equals(result.reason()),
            "aCount=" + result.aCount() + " bCount=" + result.bCount() + " reason=" + result.reason());
    }

    private static void dpiScaledPairAccepted() {
        final BufferedImage image = canvas(320, 240);
        fill(image, 120, 90, 32, 32, MAGENTA);
        fill(image, 120, 160, 32, 32, CYAN);
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
        check("dpi-scaled pair accepted (32px, dy=70)", result.pair() != null, result.reason());
    }

    /** B above A: forward vertical order must fail closed. */
    private static void reversedPairRejected() {
        final BufferedImage image = canvas(320, 180);
        fill(image, 150, 120, 16, 16, MAGENTA);
        fill(image, 150, 80, 16, 16, CYAN);
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
        check("reversed pair rejected",
            result.pair() == null && result.reason().startsWith("pair-reversed"),
            result.reason());
    }

    /** B displaced horizontally beyond the same-column band: fail closed. */
    private static void horizontalMisalignmentRejected() {
        final BufferedImage image = canvas(320, 180);
        fill(image, 150, 80, 16, 16, MAGENTA);
        fill(image, 190, 120, 16, 16, CYAN);
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
        check("horizontal misalignment rejected",
            result.pair() == null && result.reason().startsWith("pair-misaligned"),
            result.reason());
    }

    private static void verticalSpacingTooSmallRejected() {
        final BufferedImage image = canvas(320, 180);
        fill(image, 150, 80, 16, 16, MAGENTA);
        fill(image, 150, 96, 16, 16, CYAN);
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
        check("too-small vertical spacing rejected",
            result.pair() == null && result.reason().startsWith("pair-spacing"),
            result.reason());
    }

    private static void verticalSpacingTooLargeRejected() {
        final BufferedImage image = canvas(320, 260);
        fill(image, 150, 80, 16, 16, MAGENTA);
        fill(image, 150, 210, 16, 16, CYAN);
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
        check("too-large vertical spacing rejected",
            result.pair() == null && result.reason().startsWith("pair-spacing"),
            result.reason());
    }

    private static void oversizedRegionRejected() {
        final BufferedImage image = canvas(320, 240);
        fill(image, 100, 80, 96, 96, MAGENTA);
        fill(image, 150, 120, 16, 16, CYAN);
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
        check("oversized region rejected", result.pair() == null && result.aCount() == 0,
            "aCount=" + result.aCount() + " bCount=" + result.bCount() + " reason=" + result.reason());
    }

    private static void toleranceColorAccepted() {
        final BufferedImage image = canvas(320, 180);
        fill(image, 150, 80, 16, 16, 0xFD04FE);
        fill(image, 150, 120, 16, 16, 0x02FDFE);
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
        check("tolerance color accepted", result.pair() != null, result.reason());
    }

    private static void blendedEdgesTolerated() {
        final BufferedImage image = canvas(320, 180);
        fill(image, 150, 80, 16, 16, MAGENTA);
        fill(image, 150, 80, 2, 2, BLEND);
        fill(image, 164, 94, 2, 2, BLEND);
        fill(image, 150, 120, 16, 16, CYAN);
        final BoundingBoxIconDetector.Result result = BoundingBoxIconDetector.detect(image);
        check("blended edges tolerated", result.pair() != null, result.reason());
        if (result.pair() != null) {
            check("blended edges center preserved",
                Math.abs(result.pair().a().x() - 157) <= 1 && Math.abs(result.pair().a().y() - 87) <= 1,
                "actual=(" + result.pair().a().x() + "," + result.pair().a().y() + ")");
        }
    }

    /**
     * Icons removed with the background untouched: the absence is accepted
     * because the bounded surrounding context from the last positive frame
     * still matches.
     */
    private static void stableBackgroundAbsenceAccepted() {
        final BufferedImage image = canvas(320, 180);
        fill(image, 150, 80, 16, 16, MAGENTA);
        fill(image, 150, 120, 16, 16, CYAN);
        final BoundingBoxIconDetector.Result detected = BoundingBoxIconDetector.detect(image);
        check("stable-context baseline detected", detected.pair() != null, detected.reason());
        if (detected.pair() == null) {
            return;
        }
        final BoundingBoxIconDetector.ContextRegion region =
            BoundingBoxIconDetector.captureContext(image, detected.pair());
        fill(image, 150, 80, 16, 16, BACKGROUND);
        fill(image, 150, 120, 16, 16, BACKGROUND);
        final BoundingBoxIconDetector.Result absent = BoundingBoxIconDetector.detect(image);
        check("stable-context absence detected",
            absent.aCount() == 0 && absent.bCount() == 0,
            "aCount=" + absent.aCount() + " bCount=" + absent.bCount() + " reason=" + absent.reason());
        check("stable-context absence accepted",
            BoundingBoxIconDetector.contextMatches(image, region),
            "context did not match baseline");
    }

    /**
     * A covering window hides both icons and changes the surrounding band:
     * plain absence detection would be fooled, the context comparison must
     * reject it.
     */
    private static void occlusionContextChangeRejected() {
        final BufferedImage image = canvas(320, 180);
        fill(image, 150, 80, 16, 16, MAGENTA);
        fill(image, 150, 120, 16, 16, CYAN);
        final BoundingBoxIconDetector.Result detected = BoundingBoxIconDetector.detect(image);
        check("occlusion baseline detected", detected.pair() != null, detected.reason());
        if (detected.pair() == null) {
            return;
        }
        final BoundingBoxIconDetector.ContextRegion region =
            BoundingBoxIconDetector.captureContext(image, detected.pair());
        fill(image, 130, 70, 110, 90, 0x5A5A5A);
        final BoundingBoxIconDetector.Result occluded = BoundingBoxIconDetector.detect(image);
        check("occlusion hides icons",
            occluded.aCount() == 0 && occluded.bCount() == 0,
            "aCount=" + occluded.aCount() + " bCount=" + occluded.bCount());
        check("occlusion context change rejected",
            !BoundingBoxIconDetector.contextMatches(image, region),
            "context unexpectedly matched the baseline");
    }

    private static BufferedImage canvas(final int width, final int height) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, BACKGROUND);
            }
        }
        return image;
    }

    private static void fill(
        final BufferedImage image,
        final int x,
        final int y,
        final int width,
        final int height,
        final int color
    ) {
        for (int yy = y; yy < y + height; yy++) {
            for (int xx = x; xx < x + width; xx++) {
                image.setRGB(xx, yy, color);
            }
        }
    }

    private static void check(final String name, final boolean condition, final String detail) {
        if (!condition) {
            FAILURES.add(name + ": " + detail);
        }
    }
}
