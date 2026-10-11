package dev.turboism.validation.selectionlag;

import java.awt.Point;
import java.awt.Rectangle;

/** Offline unit tests for selection-lag probe activation coordinates. */
public final class SelectionLagProbePluginTest {
    public static void main(final String[] args) {
        final Point bounds50 = SelectionLagProbePlugin.titleBarActivationPoint(
            new Rectangle(-4, -30, 950, 1046));
        if (bounds50.x != 471 || bounds50.y != 20) {
            throw new AssertionError(
                "5.0.x negative-y window must clamp activation y into visible client area: "
                    + bounds50);
        }
        final Point bounds53 = SelectionLagProbePlugin.titleBarActivationPoint(
            new Rectangle(-4, -10, 950, 1046));
        if (bounds53.x != 471 || bounds53.y != 20) {
            throw new AssertionError("5.2/5.3 activation point drifted: " + bounds53);
        }
        final Point floating = SelectionLagProbePlugin.titleBarActivationPoint(
            new Rectangle(100, 50, 800, 600));
        if (floating.x != 500 || floating.y != 80) {
            throw new AssertionError("floating window activation point drifted: " + floating);
        }
        System.out.println("PASS: SelectionLagProbePluginTest");
    }
}
