package dev.turboism.ui.appearance;

import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearancePalette;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exact-host appearance provider using the reviewed legacy FlatLaf refresh path. */
public final class FlatLafAppearanceHostProvider implements AppearanceHostProvider {

    private final String hostVersion;
    private final HostOperations host;
    private AppearanceStatus status;
    private AppearanceRequest lastRequest;

    public FlatLafAppearanceHostProvider(final String hostVersion, final HostOperations host) {
        this.hostVersion = requireVersion(hostVersion);
        this.host = Objects.requireNonNull(host, "host");
        this.status = nativeStatus(0);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public synchronized AppearanceStatus readStatus() {
        return status;
    }

    @Override
    public synchronized RestorePoint captureRestorePoint() {
        return new Point(host.capture(), host.capturedBaselineCanBeRestored());
    }

    @Override
    public synchronized ApplyOutcome apply(final AppearanceRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.equals(lastRequest)) {
            return ApplyOutcome.NO_CHANGE;
        }
        host.replace(defaults(request.palette()));
        host.refresh();
        lastRequest = request;
        status = new AppearanceStatus(
            AppearanceStatus.Availability.AVAILABLE,
            AppearanceStatus.Source.PLUGIN_OVERLAY,
            Optional.of(request.appearanceId()),
            request.base(),
            status.revision() + 1,
            Optional.empty()
        );
        return ApplyOutcome.APPLIED;
    }

    @Override
    public synchronized void restore(final RestorePoint restorePoint) {
        if (!(restorePoint instanceof Point point)) {
            throw new IllegalArgumentException("restore point was not created by this provider");
        }
        if (point.restorable()) {
            host.restore(point.defaults());
        } else {
            // Native restore drops owned overrides and deletes the shared
            // custom-defaults source file; captured baseline values are unreliable
            // when the early bootstrap may already have injected the theme.
            host.restoreNative();
        }
        lastRequest = null;
        status = nativeStatus(status.revision() + 1);
    }

    /**
     * @return the exact Cubism host version this provider was built for; ordinary runtime
     *     admission remains independently gated by the connector
     */
    public String hostVersion() {
        return hostVersion;
    }

    static java.util.Set<String> ownedKeys() {
        return defaults(new AppearancePalette(
            "#000000", "#000000", "#000000", "#000000", "#000000",
            "#000000", "#000000", "#000000", "#000000", "#000000"
        )).keySet();
    }

    private static Map<String, String> defaults(final AppearancePalette palette) {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        put(values, palette.accent(),
            "CubismCommon.blue", "CubismCommon.selectedColor", "CubismCommon.progressColor",
            "Component.accentColor", "ToggleButton.selectedBackground");
        put(values, palette.selectionBackground(),
            "CubismCommon.activeColor", "CubismCommon.selectionBackground",
            "Table.selectionBackground", "List.selectionBackground", "Button.hoverBackground");
        put(values, palette.background(),
            "CubismCommon.background", "Panel.background", "Viewport.background",
            "ScrollBar.track", "CubismCommon.editor.background");
        put(values, palette.surface(),
            "CubismCommon.surface", "ScrollBar.background", "CubismCommon.toolbar.background",
            "ToolBar.background", "TabbedPane.background", "Table.background",
            "Tree.background", "List.background");
        put(values, palette.inputBackground(),
            "CubismCommon.inputBackground", "Button.background", "TextField.background",
            "TextArea.background", "ComboBox.background");
        put(values, palette.foreground(),
            "CubismCommon.foreground", "ToolBar.foreground", "TabbedPane.foreground",
            "Table.foreground");
        put(values, palette.mutedForeground(), "CubismCommon.mutedForeground");
        put(values, palette.selectionForeground(),
            "CubismCommon.selectionForeground", "Table.selectionForeground",
            "List.selectionForeground");
        put(values, palette.border(),
            "CubismCommon.border", "Component.borderColor", "Separator.foreground",
            "Table.gridColor", "ScrollBar.thumb");
        put(values, palette.viewportBackground(), "CubismCommon.gl.viewArea.background");
        return Map.copyOf(values);
    }

    private static void put(
        final Map<String, String> target,
        final String value,
        final String... keys
    ) {
        for (String key : keys) {
            target.put(key, value);
        }
    }

    private static AppearanceStatus nativeStatus(final long revision) {
        return new AppearanceStatus(
            AppearanceStatus.Availability.AVAILABLE,
            AppearanceStatus.Source.NATIVE,
            Optional.empty(),
            AppearanceBase.NATIVE,
            revision,
            Optional.empty()
        );
    }

    private static String requireVersion(final String value) {
        Objects.requireNonNull(value, "hostVersion");
        // The 5.2 project/workspace manifest identifies the host as "5.2.03";
        // normalize it to the product version "5.2.03" used by reviewed evidence.
        if (value.equals("5.2.03")) {
            return "5.2.03";
        }
        if (!value.equals("5.3.03") && !value.equals("5.3.02") && !value.equals("5.2.03")) {
            throw new IllegalArgumentException("unsupported appearance host version");
        }
        return value;
    }

    private record Point(Map<String, String> defaults, boolean restorable) implements RestorePoint {
        private Point {
            defaults = Map.copyOf(defaults);
        }
    }

    public interface HostOperations {
        Map<String, String> capture();

        default boolean capturedBaselineCanBeRestored() {
            return false;
        }

        void replace(Map<String, String> defaults);

        default void restore(final Map<String, String> defaults) {
            throw new UnsupportedOperationException("captured appearance baseline is not restorable");
        }

        void restoreNative();

        void refresh();
    }
}
