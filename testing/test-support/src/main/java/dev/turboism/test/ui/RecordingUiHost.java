package dev.turboism.test.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.sdk.ui.ChoiceDialogRequest;
import dev.turboism.sdk.ui.ChoiceDialogResultListener;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.ColorPickerResultListener;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.FormDialogRequest;
import dev.turboism.sdk.ui.FormDialogResultListener;
import dev.turboism.sdk.ui.HorizontalToolbarContribution;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiHostColorMode;
import dev.turboism.sdk.ui.VerticalToolbarContribution;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording test fake for {@link UiHostCapabilityService}.
 */
public class RecordingUiHost implements UiHostCapabilityService {

    public record ColorPickerCall(String id, String title, String initialColorHex) {
        public ColorPickerCall {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
        }

        public String initial() {
            return initialColorHex;
        }
    }

    private final List<StatusNotification> notifications = new CopyOnWriteArrayList<>();
    private final List<DialogRequest> dialogs = new CopyOnWriteArrayList<>();
    private final List<EmbeddedPanelContribution> embeddedPanels = new CopyOnWriteArrayList<>();
    private final List<CollapsibleSectionContribution> collapsibleSections = new CopyOnWriteArrayList<>();
    private final List<ColorPickerCall> colorPickers = new CopyOnWriteArrayList<>();
    private final List<ColorPickerResultListener> colorPickerListeners = new CopyOnWriteArrayList<>();
    private final List<OverlayContribution> overlays = new CopyOnWriteArrayList<>();
    private final List<BoundingBoxOverlayButton> boundingBoxButtons = new CopyOnWriteArrayList<>();
    private final List<ContextMenuRegistry.ContextMenuContribution> contextMenuContributions =
            new CopyOnWriteArrayList<>();
    private final List<MainToolbarRegistry.MainToolbarContribution> mainToolbarContributions =
            new CopyOnWriteArrayList<>();
    private final List<PaletteToolbarRegistry.PaletteToolbarContribution> paletteToolbarContributions =
            new CopyOnWriteArrayList<>();
    private final List<VerticalToolbarContribution> verticalToolbarContributions = new CopyOnWriteArrayList<>();
    private final List<HorizontalToolbarContribution> horizontalToolbarContributions = new CopyOnWriteArrayList<>();
    private final List<EmbeddedPanelId> activatedPanels = new CopyOnWriteArrayList<>();
    private final List<StoragePath> openedDirectories = new CopyOnWriteArrayList<>();

    private volatile Optional<String> chosenFile = Optional.empty();
    private volatile boolean confirmResult = true;
    private volatile RuntimeException confirmFailure;
    private volatile DialogRequest lastConfirmRequest;
    private volatile FileChooserRequest lastFileChooserRequest;
    private volatile ChoiceDialogRequest lastChoiceRequest;
    private volatile ChoiceDialogResultListener lastChoiceListener;
    private volatile FormDialogRequest lastFormRequest;
    private volatile FormDialogResultListener lastFormListener;
    private volatile ContextSourceSnapshot contextSource;
    private volatile ViewportSnapshot viewport;
    private volatile UiHostColorMode colorMode = UiHostColorMode.LIGHT;
    private volatile Locale locale = Locale.getDefault();

    public RecordingUiHost() {}

    public RecordingUiHost(final Optional<String> chosenFile, final boolean confirmResult) {
        this.chosenFile = Objects.requireNonNull(chosenFile, "chosenFile");
        this.confirmResult = confirmResult;
    }

    public List<StatusNotification> notifications() {
        return List.copyOf(notifications);
    }

    public Optional<StatusNotification> lastNotification() {
        return notifications.isEmpty() ? Optional.empty() : Optional.of(notifications.get(notifications.size() - 1));
    }

    public List<DialogRequest> dialogs() {
        return List.copyOf(dialogs);
    }

    public DialogRequest lastConfirmRequest() {
        return lastConfirmRequest;
    }

    public void setConfirmResult(final boolean confirmResult) {
        this.confirmResult = confirmResult;
    }

    public void setConfirmFailure(final RuntimeException confirmFailure) {
        this.confirmFailure = confirmFailure;
    }

    public void setChosenFile(final String chosenFile) {
        this.chosenFile = Optional.ofNullable(chosenFile);
    }

    public void setChosenFile(final Optional<String> chosenFile) {
        this.chosenFile = Objects.requireNonNull(chosenFile, "chosenFile");
    }

    public Optional<String> chosenFile() {
        return chosenFile;
    }

    public FileChooserRequest lastFileChooserRequest() {
        return lastFileChooserRequest;
    }

    public ChoiceDialogRequest lastChoiceRequest() {
        return lastChoiceRequest;
    }

    public ChoiceDialogResultListener lastChoiceListener() {
        return lastChoiceListener;
    }

    public FormDialogRequest lastFormRequest() {
        return lastFormRequest;
    }

    public FormDialogResultListener lastFormListener() {
        return lastFormListener;
    }

    public List<ColorPickerCall> colorPickers() {
        return List.copyOf(colorPickers);
    }

    public void acceptColorPicker(final int index, final String hex) {
        colorPickerListeners.get(index).onResult(true, hex);
    }

    public void cancelColorPicker(final int index) {
        colorPickerListeners.get(index).onResult(false, null);
    }

    public List<EmbeddedPanelContribution> embeddedPanels() {
        return List.copyOf(embeddedPanels);
    }

    public List<EmbeddedPanelContribution> panels() {
        return embeddedPanels();
    }

    public List<CollapsibleSectionContribution> collapsibleSections() {
        return List.copyOf(collapsibleSections);
    }

    public List<CollapsibleSectionContribution> sections() {
        return collapsibleSections();
    }

    public List<EmbeddedPanelId> activatedPanels() {
        return List.copyOf(activatedPanels);
    }

    public List<StoragePath> openedDirectories() {
        return List.copyOf(openedDirectories);
    }

    public List<OverlayContribution> overlays() {
        return List.copyOf(overlays);
    }

    public List<BoundingBoxOverlayButton> boundingBoxButtons() {
        return List.copyOf(boundingBoxButtons);
    }

    public List<ContextMenuRegistry.ContextMenuContribution> contextMenuContributions() {
        return List.copyOf(contextMenuContributions);
    }

    public List<MainToolbarRegistry.MainToolbarContribution> mainToolbarContributions() {
        return List.copyOf(mainToolbarContributions);
    }

    public List<PaletteToolbarRegistry.PaletteToolbarContribution> paletteToolbarContributions() {
        return List.copyOf(paletteToolbarContributions);
    }

    public void setContextSource(final ContextSourceSnapshot contextSource) {
        this.contextSource = contextSource;
    }

    public void setViewport(final ViewportSnapshot viewport) {
        this.viewport = viewport;
    }

    public void setColorMode(final UiHostColorMode colorMode) {
        this.colorMode = Objects.requireNonNull(colorMode, "colorMode");
    }

    public void setHostLocale(final Locale locale) {
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    @Override
    public Registration contributeOverlay(final OverlayContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        overlays.add(contribution);
        return () -> overlays.remove(contribution);
    }

    @Override
    public Registration contributeBoundingBoxOverlayButton(final BoundingBoxOverlayButton contribution) {
        Objects.requireNonNull(contribution, "contribution");
        boundingBoxButtons.add(contribution);
        return () -> boundingBoxButtons.remove(contribution);
    }

    @Override
    public ContextSourceSnapshot contextSource() {
        if (contextSource != null) {
            return contextSource;
        }
        return new ContextSourceSnapshot(
                "ctx-1", "workspace", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Override
    public ViewportSnapshot viewport() {
        if (viewport != null) {
            return viewport;
        }
        return new ViewportSnapshot("viewport-1", 1920, 1080, 1.0);
    }

    @Override
    public Registration openDialog(final DialogRequest request) {
        Objects.requireNonNull(request, "request");
        dialogs.add(request);
        return () -> dialogs.remove(request);
    }

    @Override
    public boolean confirmDialog(final DialogRequest request) {
        Objects.requireNonNull(request, "request");
        dialogs.add(request);
        lastConfirmRequest = request;
        if (confirmFailure != null) {
            throw confirmFailure;
        }
        return confirmResult;
    }

    @Override
    public void openChoiceDialog(final ChoiceDialogRequest request, final ChoiceDialogResultListener listener) {
        this.lastChoiceRequest = Objects.requireNonNull(request, "request");
        this.lastChoiceListener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public Optional<String> choose(final ChoiceDialogRequest request) {
        this.lastChoiceRequest = Objects.requireNonNull(request, "request");
        return chosenFile;
    }

    @Override
    public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        embeddedPanels.removeIf(existing -> existing.id().equals(contribution.id()));
        embeddedPanels.add(contribution);
        return () -> embeddedPanels.removeIf(existing -> existing.id().equals(contribution.id()));
    }

    @Override
    public void activateEmbeddedPanel(final EmbeddedPanelId panelId) {
        Objects.requireNonNull(panelId, "panelId");
        activatedPanels.add(panelId);
    }

    @Override
    public Registration contributeCollapsibleSection(final CollapsibleSectionContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        collapsibleSections.add(contribution);
        return () -> collapsibleSections.remove(contribution);
    }

    @Override
    public Optional<String> requestFile(final FileChooserRequest request) {
        this.lastFileChooserRequest = Objects.requireNonNull(request, "request");
        return chosenFile;
    }

    @Override
    public UiHostColorMode currentColorMode() {
        return colorMode;
    }

    @Override
    public Locale hostLocale() {
        return locale;
    }

    @Override
    public void openDirectory(final StoragePath directory) {
        Objects.requireNonNull(directory, "directory");
        openedDirectories.add(directory);
    }

    @Override
    public void openFormDialog(final FormDialogRequest request, final FormDialogResultListener listener) {
        this.lastFormRequest = Objects.requireNonNull(request, "request");
        this.lastFormListener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void openColorPicker(
            final String id,
            final String title,
            final String initialColorHex,
            final ColorPickerResultListener listener) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(listener, "listener");
        colorPickers.add(new ColorPickerCall(id, title, initialColorHex));
        colorPickerListeners.add(listener);
    }

    @Override
    public Registration notifyStatus(final StatusNotification notification) {
        Objects.requireNonNull(notification, "notification");
        notifications.add(notification);
        return () -> notifications.remove(notification);
    }

    @Override
    public Registration contributeContextMenu(final ContextMenuRegistry.ContextMenuContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        contextMenuContributions.add(contribution);
        return () -> contextMenuContributions.remove(contribution);
    }

    @Override
    public Registration contributeMainToolbar(final MainToolbarRegistry.MainToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        mainToolbarContributions.add(contribution);
        return () -> mainToolbarContributions.remove(contribution);
    }

    @Override
    public Registration contributePaletteToolbar(final PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        paletteToolbarContributions.add(contribution);
        return () -> paletteToolbarContributions.remove(contribution);
    }

    @Override
    public Registration contributeVerticalToolbar(final VerticalToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        verticalToolbarContributions.add(contribution);
        return () -> verticalToolbarContributions.remove(contribution);
    }

    @Override
    public Registration contributeHorizontalToolbar(final HorizontalToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        horizontalToolbarContributions.add(contribution);
        return () -> horizontalToolbarContributions.remove(contribution);
    }

    @Override
    public Registration contributePaletteFilter(final PaletteFilterRegistry.PaletteFilterContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        return () -> {};
    }
}
