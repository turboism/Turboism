package dev.turboism.ui.toolbar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract;
import dev.turboism.adapter.cubism.modeling.ModelingToolCoordinator;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VerifiedMainToolbarHostOperationsTest {
    @Test
    void nativeRefreshCannotReselectToolsUntilTheCustomActivationEnds() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final var arrow = new javax.swing.JToggleButton();
            final var lasso = new javax.swing.JToggleButton();
            final var group = new javax.swing.ButtonGroup();
            group.add(arrow);
            group.add(lasso);
            final var active = new java.util.concurrent.atomic.AtomicBoolean(true);
            final java.util.List<javax.swing.AbstractButton> buttons = List.of(arrow, lasso);
            final var watch = VerifiedMainToolbarHostOperations.watchNativeSelection(
                    buttons, active::get, () -> VerifiedMainToolbarHostOperations.clearNativeSelection(buttons));
            try {
                arrow.setSelected(true);
                assertFalse(arrow.isSelected(), "a later native refresh must preserve exclusive activation");
                lasso.setSelected(true);
                assertFalse(lasso.isSelected());
                assertNull(group.getSelection());
                active.set(false);
                arrow.setSelected(true);
                assertTrue(arrow.isSelected(), "native activation must be restored without the custom tool");
                watch.close();
                watch.close();
                active.set(true);
                lasso.setSelected(true);
                assertTrue(lasso.isSelected(), "closed widgets must stop observing native buttons");
            } finally {
                watch.close();
            }
        });
    }

    @Test
    void productionAnchorAndRebuildObserverReadNativeBrushAsAnInstanceField() throws Exception {
        final String prefix = "cubism.ui-main-toolbar.";
        final var selectors = new HashMap<String, StaticSelector>();
        selectors.put(
                prefix + "app-controller.instance",
                new StaticSelector(
                        prefix + "app-controller.instance",
                        prefix + "app-controller.instance",
                        StaticSelector.Kind.METHOD,
                        nativeName(ToolbarApp.class),
                        "instance",
                        "()L" + nativeName(ToolbarApp.class) + ";",
                        9,
                        0));
        selectors.put(
                prefix + "app-controller.main-frame",
                StaticSelector.method(
                        prefix + "app-controller.main-frame",
                        nativeName(ToolbarApp.class),
                        "frame",
                        "()L" + nativeName(ToolbarApp.class) + ";",
                        1));
        selectors.put(
                prefix + "main-frame.view",
                StaticSelector.method(
                        prefix + "main-frame.view",
                        nativeName(ToolbarApp.class),
                        "view",
                        "()L" + nativeName(ToolbarView.class) + ";",
                        1));
        selectors.put(
                prefix + "main-frame-view.home-button",
                StaticSelector.method(
                        prefix + "main-frame-view.home-button",
                        nativeName(ToolbarView.class),
                        "home",
                        "()L" + nativeName(ToolbarWidget.class) + ";",
                        1));
        selectors.put(
                prefix + "widget.parent",
                StaticSelector.method(
                        prefix + "widget.parent",
                        nativeName(ToolbarWidget.class),
                        "parent",
                        "()Ljava/lang/Object;",
                        1));
        selectors.put(
                prefix + "abstract-button.set-selected",
                StaticSelector.method(
                        prefix + "abstract-button.set-selected",
                        nativeName(ToolbarWidget.class),
                        "setSelected",
                        "(Z)V",
                        1));
        selectors.put(
                prefix + "abstract-button.get-jabstract-button",
                StaticSelector.method(
                        prefix + "abstract-button.get-jabstract-button",
                        nativeName(ToolbarWidget.class),
                        "peer",
                        "()Ljavax/swing/AbstractButton;",
                        1));
        final var main = TestVerifiedResolvers.create(
                "toolbar",
                Set.of("toolbar"),
                List.copyOf(selectors.values()),
                getClass().getClassLoader());
        final var modelingSelectors = new HashMap<String, StaticSelector>();
        for (String alias : ModelingSelectionSelectorContract.REQUIRED_ALIASES)
            modelingSelectors.put(alias, StaticSelector.classSelector(alias, nativeName(ToolbarWidget.class)));
        final String brushAlias = ModelingSelectionSelectorContract.BRUSH_ANCHOR;
        modelingSelectors.put(
                brushAlias,
                StaticSelector.field(
                        brushAlias,
                        nativeName(ToolbarView.class),
                        "brush",
                        "L" + nativeName(ToolbarWidget.class) + ";",
                        1));
        final String appAlias = ModelingSelectionSelectorContract.APP;
        modelingSelectors.put(
                appAlias,
                new StaticSelector(
                        appAlias,
                        appAlias,
                        StaticSelector.Kind.METHOD,
                        nativeName(ToolbarApp.class),
                        "instance",
                        "()L" + nativeName(ToolbarApp.class) + ";",
                        9,
                        0));
        final String frameAlias = ModelingSelectionSelectorContract.MAIN_FRAME;
        modelingSelectors.put(
                frameAlias,
                StaticSelector.method(
                        frameAlias,
                        nativeName(ToolbarApp.class),
                        "frame",
                        "()L" + nativeName(ToolbarApp.class) + ";",
                        1));
        final String buttonsAlias = ModelingSelectionSelectorContract.NATIVE_TOOL_BUTTONS;
        modelingSelectors.put(
                buttonsAlias,
                StaticSelector.method(
                        buttonsAlias, nativeName(ToolbarApp.class), "buttons", "()Ljava/util/HashMap;", 1));
        final String arrowAlias = ModelingSelectionSelectorContract.ARROW_BUTTON;
        modelingSelectors.put(
                arrowAlias,
                StaticSelector.field(
                        arrowAlias,
                        nativeName(ToolbarView.class),
                        "arrow",
                        "L" + nativeName(ToolbarWidget.class) + ";",
                        1));
        final var modeling = TestVerifiedResolvers.create(
                ModelingSelectionSelectorContract.ADAPTER_SLICE_ID,
                Set.of(ModelingSelectionSelectorContract.CAPABILITY_ID),
                List.copyOf(modelingSelectors.values()),
                getClass().getClassLoader());
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final var coordinator = new ModelingToolCoordinator();
            final var resources = new EditorUiPluginResourceRegistry();
            final var operations = new VerifiedMainToolbarHostOperations(main, resources, modeling, coordinator);
            final var anchor = operations
                    .anchor(MainToolbarRegistry.Anchor.HOST_BRUSH_SELECTION_TOOL)
                    .orElseThrow();
            final var observer = operations.onRebuild(() -> {});
            try {
                // Native registration overwrites the modeling arrow map entry with the animator
                // arrow. The actual modeling button must be cleared independently of that map.
                ToolbarApp.INSTANCE.view.arrow.setSelected(true);
                ToolbarApp.INSTANCE.animatorArrow.setSelected(true);
                operations.clearNativeToolHighlights();
                assertFalse(
                        ToolbarApp.INSTANCE.view.arrow.peer().isSelected(),
                        "modeling arrow must be mutually exclusive");
                assertFalse(ToolbarApp.INSTANCE.animatorArrow.peer().isSelected());
                ToolbarApp.INSTANCE.view.brush = new ToolbarWidget();
                assertNotEquals(
                        anchor,
                        operations
                                .anchor(MainToolbarRegistry.Anchor.HOST_BRUSH_SELECTION_TOOL)
                                .orElseThrow());
                assertTrue(operations
                        .anchor(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY)
                        .isPresent());
            } finally {
                observer.close();
                resources.close();
                coordinator.close();
            }
        });
    }

    private static String nativeName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public static class ToolbarApp {
        static final ToolbarApp INSTANCE = new ToolbarApp();
        final ToolbarView view = new ToolbarView();
        final ToolbarWidget animatorArrow = new ToolbarWidget();

        ToolbarApp() {
            final var modelingGroup = new javax.swing.ButtonGroup();
            modelingGroup.add(view.arrow.peer());
            modelingGroup.add(view.brush.peer());
            final var animatorGroup = new javax.swing.ButtonGroup();
            animatorGroup.add(animatorArrow.peer());
        }

        public static ToolbarApp instance() {
            return INSTANCE;
        }

        public ToolbarApp frame() {
            return this;
        }

        public ToolbarView view() {
            return view;
        }

        public HashMap<Object, ToolbarWidget> buttons() {
            return new HashMap<>(java.util.Map.of("arrow-group", animatorArrow));
        }
    }

    public static class ToolbarView {
        private final ToolbarWidget home = new ToolbarWidget();
        public ToolbarWidget brush = new ToolbarWidget();
        public ToolbarWidget arrow = new ToolbarWidget();

        public ToolbarWidget home() {
            return home;
        }
    }

    public static class ToolbarWidget {
        private final Object parent = new Object();
        private final javax.swing.AbstractButton button = new javax.swing.JToggleButton();

        public void setSelected(boolean selected) {
            button.setSelected(selected);
        }

        public javax.swing.AbstractButton peer() {
            return button;
        }

        public Object parent() {
            return parent;
        }
    }

    @Test
    void toolbarReplacementAppearanceAndDisposalDoNotLeaveObservers() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<Object> identity =
                new java.util.concurrent.atomic.AtomicReference<>(new Object());
        final java.util.concurrent.atomic.AtomicBoolean detached = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger rebuilds = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<ToolbarLifecycleWatch> watch =
                new java.util.concurrent.atomic.AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            watch.set(new ToolbarLifecycleWatch(identity::get, detached::get, () -> {
                rebuilds.incrementAndGet();
                detached.set(false);
            }));
            watch.get().start();
            watch.get().poll();
            org.junit.jupiter.api.Assertions.assertEquals(0, rebuilds.get());
            identity.set(new Object());
            watch.get().poll();
            detached.set(true);
            watch.get().poll();
            org.junit.jupiter.api.Assertions.assertEquals(2, rebuilds.get());
            watch.get().appearanceChanged();
            watch.get().appearanceChanged();
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            org.junit.jupiter.api.Assertions.assertEquals(3, rebuilds.get());
            watch.get().appearanceChanged();
            watch.get().close();
            watch.get().close();
            identity.set(new Object());
            watch.get().poll();
        });
        javax.swing.SwingUtilities.invokeAndWait(
                () -> org.junit.jupiter.api.Assertions.assertEquals(3, rebuilds.get()));
    }

    @Test
    void afterNativeBrushKeepsTheFollowingDividerOnItsRight() {
        final Object brush = new Object(), divider = new Object();
        assertEquals(
                1,
                VerifiedMainToolbarHostOperations.insertionIndex(
                        List.of(brush, divider, new Object()),
                        MainToolbarRegistry.Placement.after(MainToolbarRegistry.Anchor.HOST_BRUSH_SELECTION_TOOL),
                        brush,
                        value -> value == divider));
    }

    @Test
    void pluginButtonsUseTheNativeMainToolbarFootprint() {
        assertEquals(32, VerifiedMainToolbarHostOperations.nativeButtonSize());
    }

    @Test
    void afterHomeSkipsTheHostOwnedHomeDivider() {
        final Object home = new Object();
        final Object divider = new Object();
        final List<Object> children = List.of(home, divider, new Object());

        assertEquals(
                2,
                VerifiedMainToolbarHostOperations.insertionIndex(
                        children,
                        MainToolbarRegistry.Placement.after(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY),
                        home,
                        value -> value == divider));
    }

    @Test
    void laterAfterHomeContributionsInsertBeforeEarlierOnesWithinTheGroup() {
        final Object home = new Object();
        final Object divider = new Object();
        final Object turboismHome = new Object();
        final List<Object> children = List.of(home, divider, turboismHome, new Object());

        assertEquals(
                2,
                VerifiedMainToolbarHostOperations.insertionIndex(
                        children,
                        MainToolbarRegistry.Placement.after(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY),
                        home,
                        value -> value == divider));
    }

    @Test
    void afterHomeFallsBackToTheImmediateNeighborWhenThereIsNoDivider() {
        final Object home = new Object();
        final List<Object> children = List.of(home, new Object());

        assertEquals(
                1,
                VerifiedMainToolbarHostOperations.insertionIndex(
                        children,
                        MainToolbarRegistry.Placement.after(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY),
                        home,
                        ignored -> false));
    }
}
