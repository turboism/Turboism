package dev.turboism.ui.appearance;

import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearancePalette;
import dev.turboism.sdk.appearance.AppearanceRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlatLafAppearanceHostProviderTest {

    @Test
    void appliesLegacyUiDefaultsAndRestoresTheCapturedBaseline() {
        RecordingHost host = new RecordingHost(Map.of(
            "CubismCommon.blue", "#010203",
            "Panel.background", "#040506"
        ));
        FlatLafAppearanceHostProvider provider = new FlatLafAppearanceHostProvider("5.3.02", host);
        AppearanceHostProvider.RestorePoint baseline = provider.captureRestorePoint();

        assertEquals(AppearanceHostProvider.ApplyOutcome.APPLIED, provider.apply(request()));
        assertEquals("#112233", host.values.get("CubismCommon.blue"));
        assertEquals("#223344", host.values.get("Panel.background"));
        assertEquals("#445566", host.values.get("Button.hoverBackground"));
        assertEquals("#AABBCC", host.values.get("CubismCommon.gl.viewArea.background"));
        assertEquals(1, host.refreshes);

        provider.restore(baseline);

        // This synthetic host does not opt into captured-baseline restoration, matching the
        // fail-safe native fallback used by non-Swing host operations.
        assertEquals(Map.of(), host.values);
        assertEquals(2, host.refreshes);
        assertEquals(AppearanceBase.NATIVE, provider.readStatus().base());
    }

    @Test
    void repeatedEquivalentRequestIsNoChange() {
        RecordingHost host = new RecordingHost(Map.of());
        FlatLafAppearanceHostProvider provider = new FlatLafAppearanceHostProvider("5.3.02", host);

        assertEquals(AppearanceHostProvider.ApplyOutcome.APPLIED, provider.apply(request()));
        assertEquals(AppearanceHostProvider.ApplyOutcome.NO_CHANGE, provider.apply(request()));
        assertEquals(1, host.refreshes);
    }

    @Test
    void exact5303CanBeConstructedForValidationWithoutWideningConnectorAdmission() {
        RecordingHost host = new RecordingHost(Map.of());
        FlatLafAppearanceHostProvider provider = new FlatLafAppearanceHostProvider("5.3.03", host);

        assertEquals("5.3.03", provider.hostVersion());
        assertEquals(AppearanceHostProvider.ApplyOutcome.APPLIED, provider.apply(request()));
    }

    @Test
    void restorableHostOperationsReapplyTheCapturedBaseline() {
        RestorableRecordingHost host = new RestorableRecordingHost(Map.of(
            "CubismCommon.blue", "#010203",
            "Panel.background", "#040506"
        ));
        FlatLafAppearanceHostProvider provider = new FlatLafAppearanceHostProvider("5.3.03", host);
        AppearanceHostProvider.RestorePoint baseline = provider.captureRestorePoint();

        provider.apply(request());
        provider.restore(baseline);

        assertEquals(Map.of(
            "CubismCommon.blue", "#010203",
            "Panel.background", "#040506"
        ), host.values);
        assertEquals(0, host.nativeRestores);
    }

    private static AppearanceRequest request() {
        return new AppearanceRequest(
            "nord",
            AppearanceBase.DARK,
            new AppearancePalette(
                "#112233",
                "#223344",
                "#334455",
                "#556677",
                "#DDEEFF",
                "#778899",
                "#445566",
                "#FFFFFF",
                "#445566",
                "#AABBCC"
            ),
            0
        );
    }

    private static class RecordingHost implements FlatLafAppearanceHostProvider.HostOperations {
        protected final LinkedHashMap<String, String> values;
        private int refreshes;
        protected int nativeRestores;

        protected RecordingHost(final Map<String, String> initial) {
            values = new LinkedHashMap<>(initial);
        }

        @Override
        public Map<String, String> capture() {
            return Map.copyOf(values);
        }

        @Override
        public void replace(final Map<String, String> defaults) {
            values.clear();
            values.putAll(defaults);
        }

        @Override
        public void restoreNative() {
            nativeRestores++;
            values.clear();
            refresh();
        }

        @Override
        public void refresh() {
            refreshes++;
        }
    }

    private static final class RestorableRecordingHost extends RecordingHost {
        private RestorableRecordingHost(final Map<String, String> initial) {
            super(initial);
        }

        @Override
        public boolean capturedBaselineCanBeRestored() {
            return true;
        }

        @Override
        public void restore(final Map<String, String> defaults) {
            values.clear();
            values.putAll(defaults);
            refresh();
        }
    }
}
