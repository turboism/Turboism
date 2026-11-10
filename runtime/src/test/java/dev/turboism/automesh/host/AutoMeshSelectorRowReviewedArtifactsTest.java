package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.automesh.AutoMeshAlgorithmCatalog;
import dev.turboism.automesh.AutoMeshSelectionState;
import dev.turboism.automesh.AutoMeshSelectorHost;
import dev.turboism.automesh.AutoMeshSelectorSource;
import dev.turboism.automesh.AutoMeshText;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.plugin.CancellationToken;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Offline proof that the selector row speaks the host widget contract of every reviewed build.
 *
 * <p>The harness loads the real widget classes of a reviewed Editor artifact and drives the
 * production row against them, so a signature the row assumes but the artifact does not carry
 * fails here instead of inside a licensed host. Only JDK and Kotlin types come from the harness;
 * every widget type is the artifact's own class.</p>
 *
 * <p>Two questions are asked because they fail differently. The member case resolves each member
 * the row reflectively names, which pins the exact class, name and parameter types. The attach case
 * runs the real session host on a real container, which proves the resolved members also compose
 * into a row that joins the container's centre. Without the artifact archive every case is skipped
 * with an explicit assumption message.</p>
 */
class AutoMeshSelectorRowReviewedArtifactsTest {

    private static final String NO_ARCHIVE_MESSAGE = "reviewed Cubism artifact archive is not configured; set "
            + "TURBOISM_CUBISM_REF to a directory holding <version>/Live2D_Cubism.jar";
    private static final String CONTENT_PANEL = "com.live2d.ui.container.CSimplePane";
    private static final String ROW = "com.live2d.ui.container.CHBox";
    private static final String GROUP = "com.live2d.ui.container.CVBox";

    /** Simple name the harness reads back from a group widget. */
    private static final String GROUP_SIMPLE = "CVBox";

    private static final String TITLED_BORDER = "com.live2d.ui.border.CTitledBorder";
    private static final String BORDER = "com.live2d.ui.border.CBorder";
    private static final String CONTAINER = "com.live2d.ui.container.CContainer";
    private static final String WIDGET = "com.live2d.ui.CWidget";
    private static final String COMBO = "com.live2d.ui.control.CComboBox";
    private static final String COMBO_MODEL = "com.live2d.ui.control.CComboBox$a";
    private static final String LABEL = "com.live2d.ui.control.CLabel";
    private static final String BUTTON = "com.live2d.ui.control.CButton";
    private static final String ABSTRACT_BUTTON = "com.live2d.ui.control.CAbstractButton";
    private static final String DLG_BUTTON = "com.live2d.ui.control.CDlgButton";
    private static final String SLIDABLE_INT = "com.live2d.ui.control.CSlidableInt";
    private static final String SLIDABLE_FLOAT = "com.live2d.ui.control.CSlidableFloat";
    /** The checkbox control links against a look-and-feel jar the archive excludes, so no member here. */
    private static final String CHECK_BOX = "com.live2d.ui.control.CCheckBox";

    private static final String TEXT_FIELD = "com.live2d.ui.control.CTextField";
    private static final String BUTTON_MENU = "com.live2d.ui.menu.CButtonMenu";
    private static final String MENU_ITEM = "com.live2d.ui.menu.CMenuItem";
    private static final String POPUP_MENU = "com.live2d.ui.menu.k";
    private static final String SPACER = "com.live2d.ui.container.CSpacer";
    private static final String INSETS = "com.live2d.type.CInsets";
    private static final String GROW = "com.live2d.ui.container.a.c";
    private static final String ALIGNMENT = "com.live2d.ui.a.a";
    private static final String FLOAT = "float";
    private static final String DEFAULT_MARKER = "kotlin.jvm.internal.DefaultConstructorMarker";
    private static final String FUNCTION1 = "kotlin.jvm.functions.Function1";
    private static final String STRING = "java.lang.String";
    private static final String LIST = "java.util.List";
    private static final String INT = "int";
    private static final String BOOLEAN = "boolean";
    private static final String CONSTRUCTOR = "<init>";
    private static final String ATTACHED_MARKER = "row attached";

    /**
     * Diagnostic the row writes when it omits the preset menu.
     *
     * <p>The archived artifacts exclude the third-party look-and-feel jar that the host popup menu
     * links against, so the harness always takes the omission path. The live validation run inside the
     * Editor covers the menu itself.</p>
     */
    private static final String HOST_MENU_OMITTED = "the host button menu is unavailable";

    private static final String PLUGIN_ALGORITHM = "plugin.region";
    private static final String PARAMETRIC_ALGORITHM = "plugin.parametric";
    private static final String NATIVE_PRESET_TITLE = "Native preset";
    private static final String NATIVE_SETTINGS_TITLE = "Native parameters";
    private static final String NATIVE_SAVE_TEXT = "Save native";
    private static final String NATIVE_ADD_TEXT = "Add native";
    private static final String NATIVE_DELETE_TEXT = "Delete native";

    /** Group titles the plugin groups must carry: the Algorithm key, then the native copies. */
    private static final List<String> INLINE_GROUP_TITLES = List.of(
            AutoMeshText.text(Locale.ENGLISH, "selector.algorithm.label"),
            NATIVE_PRESET_TITLE,
            NATIVE_PRESET_TITLE,
            NATIVE_SETTINGS_TITLE,
            NATIVE_SETTINGS_TITLE);

    /**
     * The parameter controls of one parametric algorithm, in declaration order.
     *
     * <p>The archived artifacts exclude the third-party look-and-feel jar, so a host checkbox class
     * cannot load here. The validation probe covers that control inside the real Editor.</p>
     */
    private static final List<String> INLINE_CONTROLS =
            List.of("CSlidableInt", "CSlidableFloat", "CComboBox", "CTextField");

    /** The visible groups while a plugin algorithm runs: Algorithm, plugin Preset, plugin Parameters. */
    private static final List<Boolean> PLUGIN_VISIBILITY = List.of(true, false, true, false, true);

    /** The visible groups while the Editor's generator runs: Algorithm, native Preset and Parameters. */
    private static final List<Boolean> NATIVE_VISIBILITY = List.of(true, true, false, true, false);

    private static final int INLINE_GROUPS = 5;
    private static final int ALGORITHM_GROUP = 0;
    private static final int NATIVE_PRESET_GROUP = 1;
    private static final int PLUGIN_PRESET_GROUP = 2;
    private static final int NATIVE_SETTINGS_GROUP = 3;
    private static final int PLUGIN_SETTINGS_GROUP = 4;
    private static final int NATIVE_ENTRY_INDEX = 0;
    private static final int CONTROL_COLUMN = 1;
    private static final int TITLE_BORDER_MASK = 2;

    /** The row appends one label, combo, spacer, label, combo, spacer and button, in that order. */
    private static final List<String> ROW_WIDGETS =
            List.of("CLabel", "CComboBox", "CSpacer", "CLabel", "CComboBox", "CSpacer", "CButton");

    /** The Editor's generator plus the one registered plugin algorithm. */
    private static final int ALGORITHM_ENTRIES = 2;

    /** The custom placeholder plus the one preset the plugin algorithm declares. */
    private static final int PRESET_ENTRIES = 2;

    /** The Editor's own generator declares no preset, so only the custom placeholder remains. */
    private static final int NATIVE_PRESET_ENTRIES = 1;

    /** The row carries one algorithm combo and one preset combo, in that order. */
    private static final int COMBO_COUNT = 2;

    private static final int ALGORITHM_COMBO = 0;
    private static final int PRESET_COMBO = 1;

    /** The Editor's own generator always leads the algorithm listing. */
    private static final int NATIVE_ENTRY = 0;
    /**
     * Every member the selector row reflectively names, with the parameter types it passes.
     *
     * <p>The list is the offline pin of the row's host surface. The attach case below runs the row
     * itself, so a member this list misses still fails there when a reviewed artifact drops it.</p>
     */
    private static final List<Member> MEMBERS = List.of(
            new Member(ROW, CONSTRUCTOR, List.of()),
            new Member(WIDGET, "setMarginInt", List.of(INT)),
            new Member(LABEL, CONSTRUCTOR, List.of(STRING)),
            new Member(WIDGET, "setToolTipText", List.of(STRING)),
            new Member(CONTAINER, "unaryPlus", List.of(WIDGET)),
            new Member(COMBO, CONSTRUCTOR, List.of(LIST)),
            new Member(COMBO, "getModel", List.of()),
            new Member(COMBO, "setModel", List.of(COMBO_MODEL)),
            new Member(COMBO, "updatePopupWidth", List.of()),
            new Member(COMBO, "setSelectedIndex", List.of(INT)),
            new Member(COMBO, "getSelectedIndex", List.of()),
            new Member(COMBO, "setOnSelected", List.of(FUNCTION1)),
            new Member(COMBO_MODEL, CONSTRUCTOR, List.of(LIST)),
            new Member(SPACER, CONSTRUCTOR, List.of(INT, INT)),
            new Member(BUTTON, CONSTRUCTOR, List.of()),
            new Member(ABSTRACT_BUTTON, "setText", List.of(STRING)),
            new Member(WIDGET, "setEnabled", List.of(BOOLEAN)),
            new Member(ABSTRACT_BUTTON, "addOnAction", List.of(FUNCTION1)),
            new Member(CONTENT_PANEL, "getCenter", List.of()),
            new Member(WIDGET, "revalidate", List.of()),
            new Member(WIDGET, "repaint", List.of()),
            new Member(GROUP, CONSTRUCTOR, List.of()),
            new Member(CONTAINER, "add", List.of(WIDGET, INT)),
            new Member(CONTAINER, "getChildren", List.of()),
            new Member(CONTAINER, "removeAll", List.of()),
            new Member(TITLED_BORDER, CONSTRUCTOR, List.of(STRING, BORDER, INT, DEFAULT_MARKER)),
            new Member(TITLED_BORDER, "getTitle", List.of()),
            new Member(WIDGET, "getBorder", List.of()),
            new Member(WIDGET, "setBorder", List.of(BORDER)),
            new Member(WIDGET, "setMargin", List.of(INSETS)),
            new Member(WIDGET, "setPrefWidth", List.of(INT)),
            new Member(WIDGET, "setVisible", List.of(BOOLEAN)),
            new Member(INSETS, CONSTRUCTOR, List.of(INT, INT, INT, INT)),
            new Member(ROW, "setHgrow", List.of(WIDGET, GROW)),
            new Member(LABEL, "setHorizontalAlignment", List.of(ALIGNMENT)),
            new Member(LABEL, "getEnoughWidth", List.of()),
            new Member(ABSTRACT_BUTTON, "getText", List.of()),
            new Member(DLG_BUTTON, CONSTRUCTOR, List.of()),
            new Member(BUTTON_MENU, CONSTRUCTOR, List.of(STRING)),
            new Member(BUTTON_MENU, "add", List.of(MENU_ITEM)),
            new Member(BUTTON_MENU, "getPopupMenu", List.of()),
            new Member(MENU_ITEM, CONSTRUCTOR, List.of()),
            new Member(POPUP_MENU, "d", List.of()),
            new Member(SLIDABLE_INT, "setMin", List.of(INT)),
            new Member(SLIDABLE_INT, "setMax", List.of(INT)),
            new Member(SLIDABLE_INT, "setValue", List.of(INT)),
            new Member(SLIDABLE_INT, "setSlideWeight", List.of(FLOAT)),
            new Member(SLIDABLE_INT, "setOnChanged", List.of(FUNCTION1)),
            new Member(SLIDABLE_INT, "getValue", List.of()),
            new Member(SLIDABLE_FLOAT, "setMin", List.of(FLOAT)),
            new Member(SLIDABLE_FLOAT, "setMax", List.of(FLOAT)),
            new Member(SLIDABLE_FLOAT, "setValue", List.of(FLOAT)),
            new Member(SLIDABLE_FLOAT, "setKeta", List.of(INT)),
            new Member(SLIDABLE_FLOAT, "setSlideWeight", List.of(FLOAT)),
            new Member(SLIDABLE_FLOAT, "setOnChanged", List.of(FUNCTION1)),
            new Member(SLIDABLE_FLOAT, "getValue", List.of()),
            new Member(TEXT_FIELD, CONSTRUCTOR, List.of(STRING)),
            new Member(TEXT_FIELD, "setOnAction", List.of(FUNCTION1)));

    @Test
    void everyReviewedArtifactResolvesEverySelectorRowMember() throws Exception {
        final Path root = AutoMeshFunnelTestSupport.refRoot();
        assumeTrue(root != null, NO_ARCHIVE_MESSAGE);
        final List<String> unresolved = new ArrayList<>();
        int inspected = 0;
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final Path jar = AutoMeshFunnelTestSupport.jar(root, version);
            if (jar == null) {
                continue;
            }
            inspected++;
            try (HostLoader loader = new HostLoader(jar)) {
                for (final Member member : MEMBERS) {
                    try {
                        loader.resolve(member);
                    } catch (ReflectiveOperationException | LinkageError failure) {
                        unresolved.add(version + " " + member.signature() + " -> " + failure);
                    }
                }
            }
        }
        assertTrue(inspected > 0, NO_ARCHIVE_MESSAGE);
        assertEquals(List.of(), unresolved, "every reviewed artifact must resolve the selector row members");
    }

    @Test
    void everyReviewedArtifactAttachesTheRowToTheNativeCentre() throws Exception {
        final Path root = AutoMeshFunnelTestSupport.refRoot();
        assumeTrue(root != null, NO_ARCHIVE_MESSAGE);
        final List<String> problems = new ArrayList<>();
        int attached = 0;
        int inspected = 0;
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final Path jar = AutoMeshFunnelTestSupport.jar(root, version);
            if (jar == null) {
                continue;
            }
            inspected++;
            try (HostLoader loader = new HostLoader(jar)) {
                final Attachment attachment = loader.attachRow();
                problems.addAll(attachment.problems().stream()
                        .map(problem -> version + " " + problem)
                        .toList());
                if (!attachment.centreWidgets().equals(List.of("CHBox"))) {
                    problems.add(version + " centre children " + attachment.centreWidgets());
                    continue;
                }
                if (!attachment.rowWidgets().equals(ROW_WIDGETS)) {
                    problems.add(version + " row children " + attachment.rowWidgets());
                    continue;
                }
                if (!attachment.comboItemCounts().equals(List.of(ALGORITHM_ENTRIES, PRESET_ENTRIES))) {
                    problems.add(version + " combo items " + attachment.comboItemCounts());
                    continue;
                }
                if (attachment.presetItems() != NATIVE_PRESET_ENTRIES) {
                    problems.add(version + " preset items after the refresh " + attachment.presetItems());
                    continue;
                }
                if (!Boolean.FALSE.equals(attachment.parametersEnabled())) {
                    problems.add(version + " parameter button state " + attachment.parametersEnabled());
                    continue;
                }
                attached++;
            }
        }
        assertTrue(inspected > 0, NO_ARCHIVE_MESSAGE);
        assertEquals(List.of(), problems, "every reviewed artifact must accept the selector row");
        assertEquals(inspected, attached, "all reviewed releases must carry the row");
    }

    @Test
    void everyReviewedArtifactBuildsTheInlineGroupsInsideTheNativeCentre() throws Exception {
        final Path root = AutoMeshFunnelTestSupport.refRoot();
        assumeTrue(root != null, NO_ARCHIVE_MESSAGE);
        final List<String> problems = new ArrayList<>();
        int attached = 0;
        final List<String> versions = widgetVersions();
        for (final String version : versions) {
            final Path jar = AutoMeshFunnelTestSupport.jar(root, version);
            if (jar == null) {
                continue;
            }
            try (HostLoader loader = new HostLoader(jar)) {
                final List<String> refused = loader.attachInline();
                problems.addAll(
                        refused.stream().map(problem -> version + " " + problem).toList());
                if (refused.isEmpty()) {
                    attached++;
                }
            }
        }
        assertTrue(!versions.isEmpty(), NO_ARCHIVE_MESSAGE);
        assertEquals(List.of(), problems, "every reviewed artifact must accept the inline groups");
        assertEquals(attached, versions.size(), "all sampled releases must carry the inline groups");
    }

    /**
     * Returns the builds the inline case runs on: the bounds of every reviewed minor line.
     *
     * <p>The inline case constructs real widgets of five host families, and those classes stay in the
     * host cache of this JVM. The member pin runs on every reviewed build, so the widget case runs on
     * the first and last build of each line, where a widget-contract change appears first.</p>
     *
     * @return the versions in ascending order
     */
    private static List<String> widgetVersions() {
        final List<String> versions = new ArrayList<>();
        String line = "";
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final String current = version.substring(0, version.lastIndexOf('.'));
            if (!current.equals(line)) {
                versions.add(version);
                line = current;
                continue;
            }
            versions.set(versions.size() - 1, version);
        }
        return versions;
    }

    /** One reflective member the row names: owner class, member name and parameter type names. */
    private record Member(String owner, String name, List<String> parameterTypes) {

        private String signature() {
            return owner + "#" + name + "(" + String.join(", ", parameterTypes) + ")";
        }
    }

    /** What one attach produced: refused attaches, the composed widget names and the live combo state. */
    private record Attachment(
            List<String> problems,
            List<String> centreWidgets,
            List<String> rowWidgets,
            List<Integer> comboItemCounts,
            int presetItems,
            Boolean parametersEnabled) {}

    /** Readings the harness collects on the event dispatch thread. */
    private static final class Probe {

        private List<String> centreWidgets = List.of();
        private List<String> rowWidgets = List.of();
        private List<Integer> comboItemCounts = List.of();
        private int presetItems = -1;
        private Boolean parametersEnabled;
    }

    /**
     * Loads one reviewed Editor artifact and drives the production row against its real widgets.
     *
     * <p>The parent loader is this test's loader on purpose: the artifact links against the Kotlin
     * runtime, and every widget type still comes from the artifact because no class path entry of
     * the harness declares one.</p>
     */
    private static final class HostLoader extends URLClassLoader {

        private final Path jar;
        private final Probe probe = new Probe();

        private HostLoader(final Path jar) throws Exception {
            super(new URL[] {jar.toUri().toURL()}, AutoMeshSelectorRowReviewedArtifactsTest.class.getClassLoader());
            this.jar = jar;
        }

        /**
         * Loads one class with the artifact first.
         *
         * <p>The harness declares test-only stand-ins for a few Cubism value types, so a parent-first
         * load would shadow the artifact's own class with a stand-in that carries a different
         * surface. The artifact therefore answers first, and the harness - JDK and Kotlin included -
         * answers only for the classes the artifact does not carry.</p>
         */
        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException absent) {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> type(final String name) throws ClassNotFoundException {
            return Class.forName(name, false, this);
        }

        /**
         * Resolves one member exactly as the row names it.
         *
         * @throws NoSuchMethodException when the artifact has no public member with that signature
         */
        private void resolve(final Member member) throws ReflectiveOperationException {
            final List<Class<?>> parameters = new ArrayList<>();
            for (final String parameter : member.parameterTypes()) {
                parameters.add(primitive(parameter).orElseGet(() -> load(parameter)));
            }
            final Class<?> owner = type(member.owner());
            final Class<?>[] types = parameters.toArray(new Class<?>[0]);
            if (CONSTRUCTOR.equals(member.name())) {
                owner.getConstructor(types);
                return;
            }
            owner.getMethod(member.name(), types);
        }

        private Class<?> load(final String name) {
            try {
                return Class.forName(name, false, this);
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException("member parameter type is missing: " + name, failure);
            }
        }

        private Optional<Class<?>> primitive(final String name) {
            return switch (name) {
                case INT -> Optional.of(int.class);
                case BOOLEAN -> Optional.of(boolean.class);
                case FLOAT -> Optional.of(float.class);
                default -> Optional.empty();
            };
        }

        /**
         * Attaches one row to a real content panel whose centre is a real container, then picks the
         * Editor's own generator again.
         *
         * <p>The panel is the widget family every Auto Mesh container extends, so the attach path is
         * the production one: resolve the centre, build the row, append it, refresh the layout. The
         * second step runs the production selection path, because the host combo answers a user pick
         * by calling the callback the row installed. That pick is the only route through
         * {@code getModel} and {@code setModel}.</p>
         */
        private Attachment attachRow() throws Exception {
            final List<String> diagnostics = new ArrayList<>();
            final Object panel = type(CONTENT_PANEL).getConstructor().newInstance();
            final Object centre = type(ROW).getConstructor().newInstance();
            type(CONTENT_PANEL).getMethod("setCenter", type(WIDGET)).invoke(panel, centre);
            final AutoMeshSelectorHost host = new AutoMeshSelectorHost(new Source(), Locale.ENGLISH, diagnostics::add);
            final List<String> problems = new ArrayList<>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    host.attach(panel);
                    collectProblems(host, centre, diagnostics, problems);
                });
            } finally {
                host.close();
            }
            return new Attachment(
                    problems,
                    probe.centreWidgets,
                    probe.rowWidgets,
                    probe.comboItemCounts,
                    probe.presetItems,
                    probe.parametersEnabled);
        }

        /** Reads the composed row and drives one selection, so a refused read lands in {@code problems}. */
        private void collectProblems(
                final AutoMeshSelectorHost host,
                final Object centre,
                final List<String> diagnostics,
                final List<String> problems) {
            problems.addAll(diagnostics.stream()
                    .filter(message -> !message.contains(ATTACHED_MARKER))
                    .toList());
            if (!diagnostics.stream().anyMatch(message -> message.contains(ATTACHED_MARKER))) {
                problems.add(jar.getFileName() + " reported no attach: " + diagnostics);
            }
            if (host.attachedCount() != 1) {
                problems.add("the host must record exactly one row, recorded " + host.attachedCount());
            }
            try {
                final List<Object> centreChildren = childrenOf(centre);
                probe.centreWidgets = namesOf(centreChildren);
                final List<Object> row = new ArrayList<>();
                for (final Object child : centreChildren) {
                    if (ROW.equals(child.getClass().getName())) {
                        row.addAll(childrenOf(child));
                    }
                }
                probe.rowWidgets = namesOf(row);
                final List<Object> combos = new ArrayList<>();
                Object button = null;
                for (final Object member : row) {
                    final String name = member.getClass().getName();
                    if (COMBO.equals(name)) {
                        combos.add(member);
                    }
                    if (BUTTON.equals(name)) {
                        button = member;
                    }
                }
                final List<Integer> counts = new ArrayList<>();
                for (final Object combo : combos) {
                    counts.add(itemsOf(combo).size());
                }
                probe.comboItemCounts = counts;
                if (combos.size() != COMBO_COUNT || button == null) {
                    problems.add("the row must carry two combos and one button: " + probe.rowWidgets);
                    return;
                }
                probe.presetItems = pickNative(combos.get(ALGORITHM_COMBO), combos.get(PRESET_COMBO));
                probe.parametersEnabled =
                        (Boolean) type(WIDGET).getMethod("isEnabled").invoke(button);
            } catch (ReflectiveOperationException failure) {
                problems.add("the row could not be probed: " + failure);
            }
        }

        /**
         * Picks the Editor's own generator exactly as the host reports a user pick.
         *
         * <p>The host list model fires no Swing event, so the pick is delivered the way Swing
         * delivers one: the combo selects the entry, then the combo's action listeners run. The
         * host listener is what calls the callback the row installed.</p>
         *
         * @return the item count the preset combo lists after the refresh
         */
        private int pickNative(final Object algorithmCombo, final Object presetCombo)
                throws ReflectiveOperationException {
            type(COMBO).getMethod("setSelectedIndex", int.class).invoke(algorithmCombo, NATIVE_ENTRY);
            final javax.swing.JComboBox<?> swing = (javax.swing.JComboBox<?>)
                    type(WIDGET).getMethod("getJComponent").invoke(algorithmCombo);
            final java.awt.event.ActionEvent event = new java.awt.event.ActionEvent(
                    swing,
                    java.awt.event.ActionEvent.ACTION_PERFORMED,
                    swing.getClass().getName());
            for (final java.awt.event.ActionListener listener : swing.getActionListeners()) {
                listener.actionPerformed(event);
            }
            return itemsOf(presetCombo).size();
        }

        /**
         * Attaches the plugin groups to a synthetic native centre, then switches to the Editor's
         * generator and back.
         *
         * <p>The fixture reproduces the native layout: a centre group with a Preset group and a
         * Parameters group, each carrying a titled border, and the native preset chrome that supplies
         * the copied wording. Both algorithm states are driven through the host combo, because that
         * is the only route the row's visibility rule has.</p>
         *
         * @return every mismatch the run observed; empty when the artifact accepted the groups
         */
        private List<String> attachInline() throws Exception {
            final List<String> diagnostics = new ArrayList<>();
            final List<String> problems = new ArrayList<>();
            final AutoMeshSelectorHost host =
                    new AutoMeshSelectorHost(new ParametricSource(), Locale.ENGLISH, diagnostics::add);
            try {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        final Object panel = inlinePanel();
                        host.attach(panel);
                        problems.addAll(diagnostics.stream()
                                .filter(message -> !message.contains(ATTACHED_MARKER))
                                .filter(message -> !message.contains(HOST_MENU_OMITTED))
                                .toList());
                        if (!diagnostics.stream().anyMatch(message -> message.contains(ATTACHED_MARKER))) {
                            problems.add(jar.getFileName() + " reported no attach: " + diagnostics);
                        }
                        if (host.attachedCount() != 1) {
                            problems.add("the host must record exactly one row, recorded " + host.attachedCount());
                        }
                        probeInline(panel, problems);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        problems.add("the inline groups could not be probed: " + describe(failure));
                    }
                });
            } finally {
                host.close();
            }
            return problems;
        }

        /**
         * Builds one container shaped like the native Auto Mesh dialog.
         *
         * <p>The native preset chrome carries the wording the plugin group must copy, so the fixture
         * makes it distinguishable from the bundle text.</p>
         */
        private Object inlinePanel() throws ReflectiveOperationException {
            final Object panel = type(CONTENT_PANEL).getConstructor().newInstance();
            final Object centre = type(GROUP).getConstructor().newInstance();
            final Object nativePreset = titledGroup(NATIVE_PRESET_TITLE);
            final Object row = type(ROW).getConstructor().newInstance();
            addWidget(row, combo(List.of(NATIVE_PRESET_TITLE)));
            addWidget(row, button(DLG_BUTTON, NATIVE_SAVE_TEXT));
            addWidget(row, button(BUTTON, NATIVE_ADD_TEXT));
            addWidget(row, button(BUTTON, NATIVE_DELETE_TEXT));
            addWidget(nativePreset, row);
            addWidget(centre, nativePreset);
            addWidget(centre, titledGroup(NATIVE_SETTINGS_TITLE));
            type(CONTENT_PANEL).getMethod("setCenter", type(WIDGET)).invoke(panel, centre);
            return panel;
        }

        private Object titledGroup(final String title) throws ReflectiveOperationException {
            final Object group = type(GROUP).getConstructor().newInstance();
            final Object border = type(TITLED_BORDER)
                    .getConstructor(String.class, type(BORDER), int.class, type(DEFAULT_MARKER))
                    .newInstance(title, null, TITLE_BORDER_MASK, null);
            type(WIDGET).getMethod("setBorder", type(BORDER)).invoke(group, border);
            return group;
        }

        private Object combo(final List<String> items) throws ReflectiveOperationException {
            return type(COMBO).getConstructor(List.class).newInstance(items);
        }

        private Object button(final String typeName, final String text) throws ReflectiveOperationException {
            final Object button = type(typeName).getConstructor().newInstance();
            type(ABSTRACT_BUTTON).getMethod("setText", String.class).invoke(button, text);
            return button;
        }

        private void addWidget(final Object container, final Object widget) throws ReflectiveOperationException {
            type(CONTAINER).getMethod("add", type(WIDGET), int.class).invoke(container, widget, -1);
        }

        /** Reads the composed groups in both algorithm states and records every mismatch. */
        private void probeInline(final Object panel, final List<String> problems) throws ReflectiveOperationException {
            final Object centre = type(CONTENT_PANEL).getMethod("getCenter").invoke(panel);
            final List<Object> groups = childrenOf(centre);
            if (groups.size() != INLINE_GROUPS) {
                problems.add("centre children " + namesOf(groups));
                return;
            }
            if (!namesOf(groups).equals(java.util.Collections.nCopies(INLINE_GROUPS, GROUP_SIMPLE))) {
                problems.add("centre child classes " + namesOf(groups));
            }
            if (!titlesOf(groups).equals(INLINE_GROUP_TITLES)) {
                problems.add("group titles " + titlesOf(groups));
            }
            if (!textsOf(groups.get(PLUGIN_PRESET_GROUP)).equals(textsOf(groups.get(NATIVE_PRESET_GROUP)))) {
                problems.add("preset actions " + textsOf(groups.get(PLUGIN_PRESET_GROUP)) + " instead of "
                        + textsOf(groups.get(NATIVE_PRESET_GROUP)));
            }
            final List<String> controls = controlsOf(groups.get(PLUGIN_SETTINGS_GROUP));
            if (!controls.equals(INLINE_CONTROLS)) {
                problems.add("plugin parameter controls " + controls);
            }
            if (!visibilityOf(groups).equals(PLUGIN_VISIBILITY)) {
                problems.add("plugin visibility " + visibilityOf(groups));
            }
            final Object algorithmCombo = firstCombo(groups.get(ALGORITHM_GROUP));
            if (algorithmCombo == null) {
                problems.add("no algorithm combo in the Algorithm group");
                return;
            }
            pick(algorithmCombo, NATIVE_ENTRY_INDEX);
            if (!visibilityOf(groups).equals(NATIVE_VISIBILITY)) {
                problems.add("native visibility " + visibilityOf(groups));
            }
            final List<String> nativeControls = controlsOf(groups.get(PLUGIN_SETTINGS_GROUP));
            if (!nativeControls.isEmpty()) {
                problems.add("native parameter controls " + nativeControls);
            }
        }

        /** Selects one algorithm the way a user does, through the host combo listeners. */
        private void pick(final Object algorithmCombo, final int index) throws ReflectiveOperationException {
            type(COMBO).getMethod("setSelectedIndex", int.class).invoke(algorithmCombo, index);
            final javax.swing.JComboBox<?> swing = (javax.swing.JComboBox<?>)
                    type(WIDGET).getMethod("getJComponent").invoke(algorithmCombo);
            final java.awt.event.ActionEvent event = new java.awt.event.ActionEvent(
                    swing,
                    java.awt.event.ActionEvent.ACTION_PERFORMED,
                    swing.getClass().getName());
            for (final java.awt.event.ActionListener listener : swing.getActionListeners()) {
                listener.actionPerformed(event);
            }
        }

        private List<String> titlesOf(final List<Object> groups) throws ReflectiveOperationException {
            final List<String> titles = new ArrayList<>(groups.size());
            for (final Object group : groups) {
                final Object border = type(WIDGET).getMethod("getBorder").invoke(group);
                titles.add((String) type(TITLED_BORDER).getMethod("getTitle").invoke(border));
            }
            return titles;
        }

        /** Reads the button texts of one group's action row, in child order. */
        private List<String> textsOf(final Object group) throws ReflectiveOperationException {
            final List<String> texts = new ArrayList<>();
            for (final Object row : childrenOf(group)) {
                for (final Object child : childrenOf(row)) {
                    if (type(ABSTRACT_BUTTON).isInstance(child)
                            && !type(BUTTON_MENU).isInstance(child)) {
                        texts.add((String)
                                type(ABSTRACT_BUTTON).getMethod("getText").invoke(child));
                    }
                }
            }
            return texts;
        }

        /** Reads the control class of every parameter row, in row order. */
        private List<String> controlsOf(final Object group) throws ReflectiveOperationException {
            final List<String> controls = new ArrayList<>();
            for (final Object row : childrenOf(group)) {
                final List<Object> columns = childrenOf(row);
                if (columns.size() <= CONTROL_COLUMN) {
                    continue;
                }
                controls.add(columns.get(CONTROL_COLUMN).getClass().getSimpleName());
            }
            return controls;
        }

        private List<Boolean> visibilityOf(final List<Object> groups) throws ReflectiveOperationException {
            final List<Boolean> visible = new ArrayList<>(groups.size());
            for (final Object group : groups) {
                visible.add((Boolean) type(WIDGET).getMethod("isVisible").invoke(group));
            }
            return visible;
        }

        private Object firstCombo(final Object root) throws ReflectiveOperationException {
            return firstOfType(root, COMBO);
        }

        private Object firstOfType(final Object root, final String typeName) throws ReflectiveOperationException {
            if (type(typeName).isInstance(root)) {
                return root;
            }
            for (final Object child : childrenOf(root)) {
                final Object found = firstOfType(child, typeName);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        /**
         * Lists the children of one host container.
         *
         * <p>A plain widget answers no children, because only a container carries them. The fixture
         * mixes both, so the read must not fail on a spacer or a label.</p>
         */
        private List<Object> childrenOf(final Object container) throws ReflectiveOperationException {
            if (!type(CONTAINER).isInstance(container)) {
                return List.of();
            }
            final List<?> children =
                    (List<?>) type(CONTAINER).getMethod("getChildren").invoke(container);
            return List.copyOf(children);
        }

        private List<String> namesOf(final List<Object> widgets) {
            return widgets.stream()
                    .map(widget -> widget.getClass().getSimpleName())
                    .toList();
        }

        /**
         * Renders one failure with its host cause.
         *
         * <p>A reflective call wraps the host failure, and only the cause names the host member that
         * refused the call. A probe that reported the wrapper alone would hide it.</p>
         */
        private static String describe(final Throwable failure) {
            final StringBuilder text = new StringBuilder();
            Throwable current = failure;
            while (current != null && current != current.getCause()) {
                if (current instanceof java.lang.reflect.InvocationTargetException && current.getCause() != null) {
                    current = current.getCause();
                    continue;
                }
                if (text.length() > 0) {
                    text.append(" caused by ");
                }
                text.append(current.getClass().getName());
                if (current.getMessage() != null) {
                    text.append(": ").append(current.getMessage());
                }
                current = current.getCause();
            }
            return text.toString();
        }

        /**
         * Reads the items the host model of one combo carries.
         *
         * <p>The read proves the combo model is the host's own model type: the host casts the Swing
         * model to it, so a combo built without items throws instead of answering a list.</p>
         */
        private List<?> itemsOf(final Object combo) throws ReflectiveOperationException {
            return (List<?>) type(COMBO).getMethod("getItems").invoke(combo);
        }
    }

    /** Session source over the real catalog with one registered plugin algorithm and one preset. */
    private static final class Source implements AutoMeshSelectorSource {

        private final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();
        private final AutoMeshSelectionState selection = new AutoMeshSelectionState(catalog);

        private Source() {
            catalog.register(region(), "plugin", 1L, () -> true);
            selection.selectAlgorithm(PLUGIN_ALGORITHM);
        }

        @Override
        public List<AutoMeshAlgorithm> algorithms() {
            return catalog.algorithms();
        }

        @Override
        public AutoMeshSelectionState selection() {
            return selection;
        }

        private static AutoMeshAlgorithm region() {
            return new AutoMeshAlgorithm(
                    PLUGIN_ALGORITHM,
                    "Region plan",
                    "Plans a mesh from the region outline.",
                    List.of(),
                    List.of(new AlgorithmPreset("dense", Map.of())),
                    false,
                    (AutoMeshRequest request, CancellationToken cancellation) -> AutoMeshResult.Cancelled.INSTANCE);
        }
    }

    /**
     * Session source with one plugin algorithm that declares every parameter kind.
     *
     * <p>The inline parameter rows are built from the declared schema, so one algorithm of each kind
     * is what pins every control the row can build.</p>
     */
    private static final class ParametricSource implements AutoMeshSelectorSource {

        private final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();
        private final AutoMeshSelectionState selection = new AutoMeshSelectionState(catalog);

        private ParametricSource() {
            catalog.register(parametric(), "plugin", 1L, () -> true);
            selection.selectAlgorithm(PARAMETRIC_ALGORITHM);
        }

        @Override
        public List<AutoMeshAlgorithm> algorithms() {
            return catalog.algorithms();
        }

        @Override
        public AutoMeshSelectionState selection() {
            return selection;
        }

        private static AutoMeshAlgorithm parametric() {
            return new AutoMeshAlgorithm(
                    PARAMETRIC_ALGORITHM,
                    "Parametric plan",
                    "Plans a mesh from declared parameters.",
                    List.of(
                            new AlgorithmParameterDescriptor.IntParameter(
                                    "segments", "Segments", "Edge splits per boundary.", 1, 9, 1, 3),
                            new AlgorithmParameterDescriptor.DoubleParameter(
                                    "density", "Density", "Vertex density per unit.", 0.1d, 2.0d, 0.05d, 2, 0.5d),
                            new AlgorithmParameterDescriptor.EnumParameter(
                                    "mode",
                                    "Mode",
                                    "Planning mode.",
                                    List.of(
                                            new AlgorithmParameterDescriptor.EnumParameter.Option("fast", "Fast"),
                                            new AlgorithmParameterDescriptor.EnumParameter.Option("exact", "Exact")),
                                    "fast"),
                            new AlgorithmParameterDescriptor.TextParameter("tag", "Tag", "Region tag.", 8, "body")),
                    List.of(new AlgorithmPreset("dense", Map.of())),
                    false,
                    (AutoMeshRequest request, CancellationToken cancellation) -> AutoMeshResult.Cancelled.INSTANCE);
        }
    }
}
