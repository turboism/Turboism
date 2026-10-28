package dev.turboism.adapter.cubism.interpolation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeInterpolationInspectionTest {
    @Test
    void nonemptyNormalBankWithNullFirstElementDoesNotSelectMorph() {
        NativeMeshInterpolationBridge.enabled(true);
        try {
            var normal = java.util.Arrays.asList((Object) null);
            assertSame(normal, NativeMeshInterpolationBridge.firstInspectionBank(normal, List.of(new Object())));
        } finally {
            NativeMeshInterpolationBridge.enabled(false);
        }
    }

    @Test
    void firstElementMatchesNativeConcatenationForNormalMorphAndEmptyBanks() {
        NativeMeshInterpolationBridge.enabled(true);
        try {
            Object normalForm = new Object(), morphForm = new Object();
            for (List<?> normal : List.of(List.of(), List.of(normalForm))) {
                for (List<?> morph : List.of(List.of(), List.of(morphForm))) {
                    List<?> selected = NativeMeshInterpolationBridge.firstInspectionBank(normal, morph);
                    Object expected = !normal.isEmpty() ? normal.get(0) : morph.isEmpty() ? null : morph.get(0);
                    assertSame(expected, selected.isEmpty() ? null : selected.get(0));
                    assertSame(normal.isEmpty() ? morph : normal, selected);
                }
            }
        } finally {
            NativeMeshInterpolationBridge.enabled(false);
        }
    }

    @Test
    void disabledInstallationKeepsFullNativeBankOrderAndSnapshotSemantics() {
        NativeMeshInterpolationBridge.enabled(false);
        var normal = new java.util.ArrayList<>(List.of(new Object()));
        var morph = List.of(new Object());
        List<?> selected = NativeMeshInterpolationBridge.firstInspectionBank(normal, morph);
        normal.clear();
        assertEquals(2, selected.size());
        assertSame(morph.get(0), selected.get(1));
    }
}
