package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.automesh.AutoMeshAlgorithmCatalog;
import dev.turboism.automesh.AutoMeshSelectionState;
import dev.turboism.automesh.AutoMeshSelectorHost;
import dev.turboism.automesh.AutoMeshSelectorSource;
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
    private static final String CONTAINER = "com.live2d.ui.container.CContainer";
    private static final String WIDGET = "com.live2d.ui.CWidget";
    private static final String COMBO = "com.live2d.ui.control.CComboBox";
    private static final String COMBO_MODEL = "com.live2d.ui.control.CComboBox$a";
    private static final String LABEL = "com.live2d.ui.control.CLabel";
    private static final String BUTTON = "com.live2d.ui.control.CButton";
    private static final String ABSTRACT_BUTTON = "com.live2d.ui.control.CAbstractButton";
    private static final String SPACER = "com.live2d.ui.container.CSpacer";
    private static final String FUNCTION1 = "kotlin.jvm.functions.Function1";
    private static final String STRING = "java.lang.String";
    private static final String LIST = "java.util.List";
    private static final String INT = "int";
    private static final String BOOLEAN = "boolean";
    private static final String CONSTRUCTOR = "<init>";
    private static final String ATTACHED_MARKER = "row attached";
    private static final String PLUGIN_ALGORITHM = "plugin.region";

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
            new Member(WIDGET, "repaint", List.of()));

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

        private List<Object> childrenOf(final Object container) throws ReflectiveOperationException {
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
}
