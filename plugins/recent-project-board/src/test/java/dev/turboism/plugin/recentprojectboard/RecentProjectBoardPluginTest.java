package dev.turboism.plugin.recentprojectboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileService;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewCapturedEvent;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginServiceDirectory;
import dev.turboism.sdk.plugin.PluginServices;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.test.Stubs;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class RecentProjectBoardPluginTest {

    private static final byte[] ONE_PIXEL_PNG = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    void enableContributesViewportBoardWithRecentCards() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("one"), "One.cmo3");
        final RecordingRecentFiles recentFiles = new RecordingRecentFiles(List.of(file));
        final List<EmbeddedPanelContribution> contributions = new CopyOnWriteArrayList<>();
        final UiHostCapabilityService uiHost = uiHost(contributions);
        final List<ActionRegistry.Action> actions = new ArrayList<>();
        final RecentProjectBoardPlugin plugin = new RecentProjectBoardPlugin();

        plugin.init(context(recentFiles, uiHost, actions));
        plugin.enable();

        assertEquals(1, contributions.size());
        final EmbeddedPanelContribution contribution = contributions.get(0);
        assertEquals(RecentProjectBoardPlugin.PANEL_ID, contribution.id());
        assertEquals(RecentProjectBoardPlugin.PANEL_PLACEMENT, contribution.placement());
        assertEquals(1, cards(contribution.content()).size());
        assertEquals("one", cards(contribution.content()).get(0).id());
        assertEquals(1, actionsMarkedOpen(actions).size());
    }

    @Test
    void previewEventReplacesTheCardContentWithTheCapturedImage() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("one"), "One.cmo3");
        final RecordingRecentFiles recentFiles = new RecordingRecentFiles(List.of(file));
        final List<EmbeddedPanelContribution> contributions = new CopyOnWriteArrayList<>();
        final UiHostCapabilityService uiHost = uiHost(contributions);
        final RecentProjectBoardPlugin plugin = new RecentProjectBoardPlugin();

        plugin.init(context(recentFiles, uiHost, new ArrayList<>()));
        plugin.enable();
        plugin.onPreview(new RecentPreviewCapturedEvent(
                file.id(), file.displayName(), new ScreenshotImage(1, 1, ONE_PIXEL_PNG)));

        final PanelView cardContent = cards(
                        contributions.get(contributions.size() - 1).content())
                .get(0)
                .content();
        assertTrue(containsScaledImage(cardContent), "the re-contributed card should carry the captured thumbnail");
        assertTrue(
                hasFixedCenteredPlaceholder(cardContent),
                "card with thumbnail should also use a fixed centered box for alignment");
    }

    @Test
    void openActionDispatchesTheRecentFileId() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("one"), "One.cmo3");
        final RecordingRecentFiles recentFiles = new RecordingRecentFiles(List.of(file));
        final List<EmbeddedPanelContribution> contributions = new CopyOnWriteArrayList<>();
        final UiHostCapabilityService uiHost = uiHost(contributions);
        final List<ActionRegistry.Action> actions = new ArrayList<>();
        final RecentProjectBoardPlugin plugin = new RecentProjectBoardPlugin();

        plugin.init(context(recentFiles, uiHost, actions));
        plugin.enable();
        final ActionRegistry.Action open = actions.stream()
                .filter(action -> RecentProjectBoardPlugin.OPEN_ACTION_ID.equals(action.id()))
                .findFirst()
                .orElseThrow();
        open.handler().accept(new ActionRegistry.ActionContext() {
            @Override
            public Optional<UiActionEvent> uiEvent() {
                return Optional.of(UiActionEvent.text("one", "one"));
            }
        });

        assertEquals(List.of(new RecentFileId("one")), recentFiles.opened);
    }

    @Test
    void emptyRecentListRendersTheEmptyState() {
        final List<EmbeddedPanelContribution> contributions = new CopyOnWriteArrayList<>();
        final UiHostCapabilityService uiHost = uiHost(contributions);
        final RecentProjectBoardPlugin plugin = new RecentProjectBoardPlugin();

        plugin.init(context(new RecordingRecentFiles(List.of()), uiHost, new ArrayList<>()));
        plugin.enable();

        assertTrue(contributions.size() >= 1);
        assertTrue(cards(contributions.get(0).content()).isEmpty());
    }

    @Test
    void emptyBoardShowsCreateLinkAndExecutesNewModel() {
        final List<EmbeddedPanelContribution> contributions = new CopyOnWriteArrayList<>();
        final UiHostCapabilityService uiHost = uiHost(contributions);
        final List<ActionRegistry.Action> actions = new ArrayList<>();
        final RecordingCommands commands = new RecordingCommands(Set.of(EditorCommand.NEW_MODEL));
        final RecentProjectBoardPlugin plugin = new RecentProjectBoardPlugin();

        plugin.init(context(new RecordingRecentFiles(List.of()), uiHost, actions, commands));
        plugin.enable();

        final PanelView empty = contributions.get(0).content();
        assertTrue(cards(empty).isEmpty());
        assertTrue(containsLink(empty), "the empty board should offer the create-project link");
        assertTrue(plugin.canCreateNewProject());

        final ActionRegistry.Action create = actions.stream()
                .filter(action -> RecentProjectBoardPlugin.CREATE_ACTION_ID.equals(action.id()))
                .findFirst()
                .orElseThrow();
        create.handler().accept(new ActionRegistry.ActionContext() {});

        assertEquals(List.of(EditorCommand.NEW_MODEL), commands.executed);
    }

    @Test
    void emptyBoardShowsAnimationLinkAndExecutesNewAnimation() {
        final List<EmbeddedPanelContribution> contributions = new CopyOnWriteArrayList<>();
        final AtomicInteger animationCalls = new AtomicInteger();
        final UiHostCapabilityService uiHost = uiHost(contributions, animationCalls);
        final List<ActionRegistry.Action> actions = new ArrayList<>();
        final RecentProjectBoardPlugin plugin = new RecentProjectBoardPlugin();

        plugin.init(context(
                new RecordingRecentFiles(List.of()),
                uiHost,
                actions,
                new RecordingCommands(Set.of(EditorCommand.NEW_MODEL))));
        plugin.enable();

        assertTrue(plugin.canCreateAnimationProject());
        assertTrue(containsLink(contributions.get(0).content()));

        final ActionRegistry.Action createAnimation = actions.stream()
                .filter(action -> RecentProjectBoardPlugin.CREATE_ANIMATION_ACTION_ID.equals(action.id()))
                .findFirst()
                .orElseThrow();
        createAnimation.handler().accept(new ActionRegistry.ActionContext() {});

        assertEquals(1, animationCalls.get());
    }

    @Test
    void cardWithoutPreviewCentersThePlaceholderAndMetadata() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("one"), "One.cmo3");
        final RecentProjectBoardPlugin plugin = new RecentProjectBoardPlugin();
        plugin.init(context(
                new RecordingRecentFiles(List.of(file)), uiHost(new CopyOnWriteArrayList<>()), new ArrayList<>()));

        final PanelView content = plugin.contentFor(List.of(file));
        final PanelView cardContent = cards(content).get(0).content();
        assertTrue(hasFixedCenteredPlaceholder(cardContent), "missing preview should use a fixed centered box");
        assertTrue(hasCenteredText(cardContent, "One.cmo3"), "the card name should be centered");
    }

    @Test
    void layoutActionCyclesFromSixToThreeToDetails() {
        final RecentFileSummary file = new RecentFileSummary(new RecentFileId("one"), "One.cmo3");
        final List<EmbeddedPanelContribution> contributions = new CopyOnWriteArrayList<>();
        final UiHostCapabilityService uiHost = uiHost(contributions);
        final List<ActionRegistry.Action> actions = new ArrayList<>();
        final RecentProjectBoardPlugin plugin = new RecentProjectBoardPlugin();

        plugin.init(context(new RecordingRecentFiles(List.of(file)), uiHost, actions));
        plugin.enable();

        assertEquals(RecentProjectBoardPlugin.BoardLayout.SIX, plugin.layout());
        assertEquals(6, gridColumns(contributions.get(0).content()));
        assertTrue(hasIconButton(contributions.get(0).content()));

        final ActionRegistry.Action layout = actions.stream()
                .filter(action -> RecentProjectBoardPlugin.LAYOUT_ACTION_ID.equals(action.id()))
                .findFirst()
                .orElseThrow();
        layout.handler().accept(new ActionRegistry.ActionContext() {});

        assertEquals(RecentProjectBoardPlugin.BoardLayout.THREE, plugin.layout());
        assertEquals(3, gridColumns(contributions.get(contributions.size() - 1).content()));
        assertEquals(
                1.15f,
                textWithValue(contributions.get(contributions.size() - 1).content(), "One.cmo3")
                        .fontScale(),
                0.001f);

        layout.handler().accept(new ActionRegistry.ActionContext() {});
        assertEquals(RecentProjectBoardPlugin.BoardLayout.ONE, plugin.layout());
        final PanelView details = contributions.get(contributions.size() - 1).content();
        assertEquals(0, gridColumns(details));
        final List<PanelView.Card> rows = cards(details);
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).bordered());
        assertInstanceOf(PanelView.LeftRow.class, rows.get(0).content());
        assertEquals(1.25f, textWithValue(details, "One.cmo3").fontScale(), 0.001f);
    }

    @Test
    void boardLayoutCyclesAndParsesPersistedValues() {
        assertEquals(RecentProjectBoardPlugin.BoardLayout.THREE, RecentProjectBoardPlugin.BoardLayout.SIX.next());
        assertEquals(RecentProjectBoardPlugin.BoardLayout.ONE, RecentProjectBoardPlugin.BoardLayout.THREE.next());
        assertEquals(RecentProjectBoardPlugin.BoardLayout.SIX, RecentProjectBoardPlugin.BoardLayout.ONE.next());
        assertEquals(RecentProjectBoardPlugin.BoardLayout.THREE, RecentProjectBoardPlugin.BoardLayout.parse("three"));
        assertEquals(RecentProjectBoardPlugin.BoardLayout.ONE, RecentProjectBoardPlugin.BoardLayout.parse(" ONE "));
        assertEquals(RecentProjectBoardPlugin.BoardLayout.THREE, RecentProjectBoardPlugin.BoardLayout.parse("four"));
        assertEquals(RecentProjectBoardPlugin.BoardLayout.THREE, RecentProjectBoardPlugin.BoardLayout.parse("two"));
        assertNull(RecentProjectBoardPlugin.BoardLayout.parse("nope"));
    }

    private static int gridColumns(final PanelView view) {
        if (view instanceof PanelView.Grid grid) {
            return grid.columns();
        }
        if (view instanceof PanelView.Column column) {
            for (PanelView child : column.children()) {
                final int columns = gridColumnsOrZero(child);
                if (columns > 0) return columns;
            }
        }
        if (view instanceof PanelView.Row row) {
            for (PanelView child : row.children()) {
                final int columns = gridColumnsOrZero(child);
                if (columns > 0) return columns;
            }
        }
        if (view instanceof PanelView.Scroll scroll) {
            return gridColumnsOrZero(scroll.child());
        }
        if (view instanceof PanelView.Centered centered) {
            return gridColumnsOrZero(centered.child());
        }
        if (view instanceof PanelView.SpaceBetween between) {
            final int left = gridColumnsOrZero(between.left());
            return left > 0 ? left : gridColumnsOrZero(between.right());
        }
        return 0;
    }

    private static int gridColumnsOrZero(final PanelView view) {
        return gridColumns(view);
    }

    private static boolean hasFixedCenteredPlaceholder(final PanelView view) {
        if (view instanceof PanelView.Centered centered) {
            return centered.preferredWidth() == RecentProjectBoardPlugin.IMAGE_MAX_WIDTH
                    && centered.preferredHeight() == RecentProjectBoardPlugin.IMAGE_MAX_HEIGHT;
        }
        if (view instanceof PanelView.Column column) {
            return column.children().stream().anyMatch(RecentProjectBoardPluginTest::hasFixedCenteredPlaceholder);
        }
        if (view instanceof PanelView.Row row) {
            return row.children().stream().anyMatch(RecentProjectBoardPluginTest::hasFixedCenteredPlaceholder);
        }
        if (view instanceof PanelView.Grid grid) {
            return grid.children().stream().anyMatch(RecentProjectBoardPluginTest::hasFixedCenteredPlaceholder);
        }
        if (view instanceof PanelView.Scroll scroll) {
            return hasFixedCenteredPlaceholder(scroll.child());
        }
        return false;
    }

    private static boolean hasCenteredText(final PanelView view, final String text) {
        if (view instanceof PanelView.Text value) {
            return value.centered() && text.equals(value.value());
        }
        if (view instanceof PanelView.Column column) {
            return column.children().stream().anyMatch(child -> hasCenteredText(child, text));
        }
        if (view instanceof PanelView.Row row) {
            return row.children().stream().anyMatch(child -> hasCenteredText(child, text));
        }
        if (view instanceof PanelView.Centered centered) {
            return hasCenteredText(centered.child(), text);
        }
        return false;
    }

    private static PanelView.Text textWithValue(final PanelView view, final String value) {
        if (view instanceof PanelView.Text text && value.equals(text.value())) {
            return text;
        }
        if (view instanceof PanelView.Column column) {
            for (PanelView child : column.children()) {
                final PanelView.Text found = textWithValueOrNull(child, value);
                if (found != null) return found;
            }
        }
        if (view instanceof PanelView.Row row) {
            for (PanelView child : row.children()) {
                final PanelView.Text found = textWithValueOrNull(child, value);
                if (found != null) return found;
            }
        }
        if (view instanceof PanelView.Grid grid) {
            for (PanelView child : grid.children()) {
                final PanelView.Text found = textWithValueOrNull(child, value);
                if (found != null) return found;
            }
        }
        if (view instanceof PanelView.Card card) {
            return textWithValueOrNull(card.content(), value);
        }
        if (view instanceof PanelView.Scroll scroll) {
            return textWithValueOrNull(scroll.child(), value);
        }
        if (view instanceof PanelView.Centered centered) {
            return textWithValueOrNull(centered.child(), value);
        }
        if (view instanceof PanelView.SpaceBetween between) {
            final PanelView.Text left = textWithValueOrNull(between.left(), value);
            return left != null ? left : textWithValueOrNull(between.right(), value);
        }
        if (view instanceof PanelView.LeftRow leftRow) {
            final PanelView.Text left = textWithValueOrNull(leftRow.left(), value);
            return left != null ? left : textWithValueOrNull(leftRow.right(), value);
        }
        if (view instanceof PanelView.CollapsibleSection section) {
            for (PanelView child : section.children()) {
                final PanelView.Text found = textWithValueOrNull(child, value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static PanelView.Text textWithValueOrNull(final PanelView view, final String value) {
        return textWithValue(view, value);
    }

    private static boolean hasIconButton(final PanelView view) {
        if (view instanceof PanelView.IconButton) {
            return true;
        }
        if (view instanceof PanelView.Column column) {
            return column.children().stream().anyMatch(RecentProjectBoardPluginTest::hasIconButton);
        }
        if (view instanceof PanelView.Row row) {
            return row.children().stream().anyMatch(RecentProjectBoardPluginTest::hasIconButton);
        }
        if (view instanceof PanelView.Grid grid) {
            return grid.children().stream().anyMatch(RecentProjectBoardPluginTest::hasIconButton);
        }
        if (view instanceof PanelView.Scroll scroll) {
            return hasIconButton(scroll.child());
        }
        if (view instanceof PanelView.Centered centered) {
            return hasIconButton(centered.child());
        }
        if (view instanceof PanelView.SpaceBetween between) {
            return hasIconButton(between.left()) || hasIconButton(between.right());
        }
        if (view instanceof PanelView.LeftRow leftRow) {
            return hasIconButton(leftRow.left()) || hasIconButton(leftRow.right());
        }
        return false;
    }

    private static List<PanelView.Card> cards(final PanelView view) {
        final List<PanelView.Card> cards = new ArrayList<>();
        collectCards(view, cards);
        return cards;
    }

    private static void collectCards(final PanelView view, final List<PanelView.Card> cards) {
        if (view instanceof PanelView.Card card) {
            cards.add(card);
            return;
        }
        if (view instanceof PanelView.Column column) {
            column.children().forEach(child -> collectCards(child, cards));
        } else if (view instanceof PanelView.Row row) {
            row.children().forEach(child -> collectCards(child, cards));
        } else if (view instanceof PanelView.Grid grid) {
            grid.children().forEach(child -> collectCards(child, cards));
        } else if (view instanceof PanelView.Scroll scroll) {
            collectCards(scroll.child(), cards);
        } else if (view instanceof PanelView.Centered centered) {
            collectCards(centered.child(), cards);
        } else if (view instanceof PanelView.CollapsibleSection section) {
            section.children().forEach(child -> collectCards(child, cards));
        }
    }

    private static boolean containsScaledImage(final PanelView view) {
        if (view instanceof PanelView.ScaledImage) {
            return true;
        }
        if (view instanceof PanelView.Column column) {
            return column.children().stream().anyMatch(RecentProjectBoardPluginTest::containsScaledImage);
        }
        if (view instanceof PanelView.Row row) {
            return row.children().stream().anyMatch(RecentProjectBoardPluginTest::containsScaledImage);
        }
        if (view instanceof PanelView.Grid grid) {
            return grid.children().stream().anyMatch(RecentProjectBoardPluginTest::containsScaledImage);
        }
        if (view instanceof PanelView.Scroll scroll) {
            return containsScaledImage(scroll.child());
        }
        if (view instanceof PanelView.Centered centered) {
            return containsScaledImage(centered.child());
        }
        if (view instanceof PanelView.CollapsibleSection section) {
            return section.children().stream().anyMatch(RecentProjectBoardPluginTest::containsScaledImage);
        }
        return false;
    }

    private static boolean containsLink(final PanelView view) {
        if (view instanceof PanelView.Link) {
            return true;
        }
        if (view instanceof PanelView.Column column) {
            return column.children().stream().anyMatch(RecentProjectBoardPluginTest::containsLink);
        }
        if (view instanceof PanelView.Row row) {
            return row.children().stream().anyMatch(RecentProjectBoardPluginTest::containsLink);
        }
        if (view instanceof PanelView.Grid grid) {
            return grid.children().stream().anyMatch(RecentProjectBoardPluginTest::containsLink);
        }
        if (view instanceof PanelView.Scroll scroll) {
            return containsLink(scroll.child());
        }
        if (view instanceof PanelView.Centered centered) {
            return containsLink(centered.child());
        }
        if (view instanceof PanelView.CollapsibleSection section) {
            return section.children().stream().anyMatch(RecentProjectBoardPluginTest::containsLink);
        }
        return false;
    }

    private static List<ActionRegistry.Action> actionsMarkedOpen(final List<ActionRegistry.Action> actions) {
        return actions.stream()
                .filter(action -> RecentProjectBoardPlugin.OPEN_ACTION_ID.equals(action.id()))
                .toList();
    }

    private static PluginContext context(
            final RecentFileService recentFiles,
            final UiHostCapabilityService uiHost,
            final List<ActionRegistry.Action> actions) {
        return context(recentFiles, uiHost, actions, EditorCommandService.unavailable());
    }

    private static PluginContext context(
            final RecentFileService recentFiles,
            final UiHostCapabilityService uiHost,
            final List<ActionRegistry.Action> actions,
            final EditorCommandService commands) {
        final DisposableScope scope = new DisposableScope();
        final PluginServiceDirectory services = PluginServices.builder()
                .install(RecentFileService.class, recentFiles)
                .install(UiHostCapabilityService.class, uiHost)
                .install(EditorCommandService.class, commands)
                .build();
        return (PluginContext) Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "services" -> services;
                    case "logger" -> Stubs.noopLogger();
                    case "localization" -> dev.turboism.sdk.i18n.PluginLocalization.unavailable();
                    case "uiScheduler" -> new FakeDirectUiScheduler();
                    case "storage" -> PluginStorage.unavailable();
                    case "disposableScope" -> scope;
                    case "actions" ->
                        (ActionRegistry) (id, action) -> {
                            actions.add(action);
                            return (Registration) () -> actions.remove(action);
                        };
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class RecordingCommands implements EditorCommandService {
        private final Set<EditorCommand> available;
        private final List<EditorCommand> executed = new CopyOnWriteArrayList<>();

        private RecordingCommands(final Set<EditorCommand> available) {
            this.available = Set.copyOf(available);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Set<EditorCommand> available() {
            return available;
        }

        @Override
        public EditorCommandResult execute(final EditorCommand command) {
            executed.add(command);
            return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.id());
        }

        @Override
        public EditorCommandResult execute(final EditorFileCommandRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EditorCommandResult execute(final EditorParameterizedRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingRecentFiles implements RecentFileService {
        private final List<RecentFileSummary> files;
        private final List<RecentFileId> opened = new CopyOnWriteArrayList<>();

        private RecordingRecentFiles(final List<RecentFileSummary> files) {
            this.files = List.copyOf(files);
        }

        @Override
        public List<RecentFileSummary> list() {
            return files;
        }

        @Override
        public boolean open(final RecentFileId id) {
            opened.add(id);
            return true;
        }
    }

    private static UiHostCapabilityService uiHost(final List<EmbeddedPanelContribution> contributions) {
        return uiHost(contributions, new AtomicInteger());
    }

    private static UiHostCapabilityService uiHost(
            final List<EmbeddedPanelContribution> contributions, final AtomicInteger animationCalls) {
        return (UiHostCapabilityService) Proxy.newProxyInstance(
                UiHostCapabilityService.class.getClassLoader(),
                new Class<?>[] {UiHostCapabilityService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "contributeRecentProjectBoard", "contributeEmbeddedPanel" -> {
                        final EmbeddedPanelContribution contribution = (EmbeddedPanelContribution) args[0];
                        contributions.add(contribution);
                        yield (Registration) () -> contributions.remove(contribution);
                    }
                    case "isAvailable" -> true;
                    case "createNewAnimationProject" -> {
                        animationCalls.incrementAndGet();
                        yield true;
                    }
                    default -> Stubs.defaultValue(method.getReturnType());
                });
    }
}
