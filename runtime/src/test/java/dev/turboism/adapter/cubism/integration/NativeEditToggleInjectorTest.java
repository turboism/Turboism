package dev.turboism.adapter.cubism.integration;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fake-host contract tests for {@link NativeEditToggleInjector}: the fake dialog singleton
 * mirrors the verified member shapes ({@code y.p} static remote checkbox,
 * {@code CCheckBox.getJCheckBox()}) with JDK Swing behind them, so the injection mechanics
 * run end to end while admission stays gated by the verified plan.
 */
class NativeEditToggleInjectorTest {

    private static final String CAPABILITY =
        "cubism.integration.external-app-settings.edit-toggle";
    private static final String DIALOG_CLASS_ALIAS =
        "cubism.integration.external-app-settings.dialog.class";
    private static final String REMOTE_CHECKBOX_ALIAS =
        "cubism.integration.external-app-settings.dialog.remote-checkbox";
    private static final String JCHECKBOX_ALIAS =
        "cubism.integration.external-app-settings.checkbox.jcheckbox";

    @AfterEach
    void resetKillSwitch() {
        System.clearProperty(NativeEditToggleInjector.ENABLED_PROPERTY);
    }

    @Test
    void injectAppendsEditCheckboxAfterRemoteInRow() throws Exception {
        final FakeHost host = new FakeHost();
        final NativeEditToggleInjector injector = admitted(host);

        onEdt(() -> assertTrue(injector.ensureInjected()));

        onEdt(() -> {
            final Component[] children = host.row.getComponents();
            assertEquals(5, children.length);
            assertSame(children[4], findInjected(host.row));
            assertEquals(NativeEditToggleInjector.EDIT_LABEL, findInjected(host.row).getText());
            assertFalse(injector.state().isEnabled(), "toggle starts fail-closed");
        });
    }

    @Test
    void injectionRequiresEdt() throws Exception {
        final FakeHost host = new FakeHost();
        final NativeEditToggleInjector injector = admitted(host);

        assertThrows(IllegalStateException.class, injector::ensureInjected);
        assertNull(findInjected(host.row));
        assertTrue(injector.ensureInjectedOnEdt(), "off-EDT callers get the EDT hop");
        assertNotNull(findInjected(host.row));
    }

    @Test
    void noInjectionWithoutVerifiedAdmission() throws Exception {
        final FakeHost host = new FakeHost();
        final VerifiedMemberResolver denied = TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-model.read"),
            selectors(internal(FakeDialogY.class), internal(FakeCheckBox.class)),
            FakeHost.class.getClassLoader()
        );

        assertEquals(Optional.empty(),
            NativeEditToggleInjector.fromVerifiedResolver(denied));
        onEdt(() -> assertNull(findInjected(host.row), "row stays native"));
    }

    @Test
    void unsupportedVersionFailsClosed() {
        final FakeHost host = new FakeHost();
        final VerifiedMemberResolver resolver = host.resolver("5.4.00");

        assertEquals(Optional.empty(),
            NativeEditToggleInjector.fromVerifiedResolver(resolver));
    }

    @Test
    void selectorDriftFailsClosed() {
        final FakeHost host = new FakeHost();

        // Missing alias: drop the jcheckbox selector from the plan.
        final VerifiedMemberResolver missingAlias = TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of(CAPABILITY),
            List.of(
                StaticSelector.classSelector(DIALOG_CLASS_ALIAS, internal(FakeDialogY.class)),
                StaticSelector.field(REMOTE_CHECKBOX_ALIAS, internal(FakeDialogY.class),
                    "p", "L" + internal(FakeCheckBox.class) + ";", 0x001A)),
            FakeHost.class.getClassLoader()
        );
        assertEquals(Optional.empty(),
            NativeEditToggleInjector.fromVerifiedResolver(missingAlias));

        // Kind drift: the remote-checkbox alias resolves to a method, not a static field.
        final VerifiedMemberResolver kindDrift = TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of(CAPABILITY),
            List.of(
                StaticSelector.classSelector(DIALOG_CLASS_ALIAS, internal(FakeDialogY.class)),
                StaticSelector.method(REMOTE_CHECKBOX_ALIAS, internal(FakeDialogY.class),
                    "p", "()L" + internal(FakeCheckBox.class) + ";", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method(JCHECKBOX_ALIAS, internal(FakeCheckBox.class),
                    "getJCheckBox", "()Ljavax/swing/JCheckBox;", StaticSelector.ACCESS_PUBLIC)),
            FakeHost.class.getClassLoader()
        );
        assertEquals(Optional.empty(),
            NativeEditToggleInjector.fromVerifiedResolver(kindDrift));
    }

    @Test
    void injectionIsIdempotentOnSameRow() throws Exception {
        final FakeHost host = new FakeHost();
        final NativeEditToggleInjector injector = admitted(host);

        onEdt(() -> {
            assertTrue(injector.ensureInjected());
            final JCheckBox first = findInjected(host.row);
            assertTrue(injector.ensureInjected());
            assertSame(first, findInjected(host.row));
            assertEquals(5, host.row.getComponentCount());
            assertTrue(injector.isInjected());
        });
    }

    @Test
    void reinjectsAfterDialogRebuild() throws Exception {
        final FakeHost host = new FakeHost();
        final NativeEditToggleInjector injector = admitted(host);

        onEdt(() -> {
            assertTrue(injector.ensureInjected());
            final JCheckBox first = findInjected(host.row);

            // Host rebuild: remote control re-parented into a fresh row; old row discarded.
            final JPanel rebuilt = new JPanel();
            rebuilt.add(new JComboBox<String>());
            rebuilt.add(new JToggleButton());
            rebuilt.add(new JPanel());
            rebuilt.add(host.remote.getJCheckBox());

            assertTrue(injector.ensureInjected());
            final JCheckBox second = findInjected(rebuilt);
            assertNotNull(second);
            assertNotSame(first, second, "rebuild gets a fresh checkbox");
            assertNull(first.getParent(), "stale checkbox detached from the old row");
            assertSame(rebuilt, second.getParent());
        });
    }

    @Test
    void removeInjectedDetachesAndIsIdempotent() throws Exception {
        final FakeHost host = new FakeHost();
        final NativeEditToggleInjector injector = admitted(host);

        onEdt(() -> {
            injector.ensureInjected();
            assertTrue(injector.removeInjected());
            assertNull(findInjected(host.row));
            assertFalse(injector.isInjected());
            assertFalse(injector.removeInjected());
            assertEquals(4, host.row.getComponentCount(), "native row restored exactly");
        });
    }

    @Test
    void checkboxSelectionForwardsToState() throws Exception {
        final FakeHost host = new FakeHost();
        final EditToggleState state = new EditToggleState();
        final List<Boolean> transitions = new ArrayList<>();
        state.addListener(transitions::add);
        final NativeEditToggleInjector injector =
            NativeEditToggleInjector.fromVerifiedResolver(host.resolver("5.3.02"), state)
                .orElseThrow();

        onEdt(() -> {
            injector.ensureInjected();
            final JCheckBox edit = findInjected(host.row);
            edit.setSelected(true);
            edit.setSelected(false);
            edit.setSelected(false);
        });

        assertEquals(List.of(true, false), transitions,
            "one callback per effective transition, none for no-op writes");
        assertFalse(state.isEnabled());
    }

    @Test
    void stateSourceDeniesApprovalWhenUnavailable() {
        final EditToggleState state = new EditToggleState();
        final EditToggleApprovalGate gate = new EditToggleApprovalGate(state);
        final EditConnectionInfo connection =
            new EditConnectionInfo(true, true, "session", "plugin");

        assertFalse(gate.isApproved(connection));
        assertFalse(gate.requestApproval(connection),
            "the checkbox surface never prompts; unchecked denies");

        state.setEnabled(true);
        assertTrue(gate.isApproved(connection));
        assertTrue(gate.requestApproval(connection));
    }

    @Test
    void killSwitchSuppressesInjection() throws Exception {
        final FakeHost host = new FakeHost();
        final NativeEditToggleInjector injector = admitted(host);
        System.setProperty(NativeEditToggleInjector.ENABLED_PROPERTY, "false");

        onEdt(() -> {
            assertFalse(injector.ensureInjected());
            assertNull(findInjected(host.row));
        });
    }

    private static NativeEditToggleInjector admitted(final FakeHost host) {
        return NativeEditToggleInjector
            .fromVerifiedResolver(host.resolver("5.3.02"), new EditToggleState())
            .orElseThrow(() -> new AssertionError("admission unexpectedly denied"));
    }

    private static JCheckBox findInjected(final JPanel row) {
        for (final Component child : row.getComponents()) {
            if (child instanceof JCheckBox box
                && Boolean.TRUE.equals(box.getClientProperty(NativeEditToggleInjector.MARKER_KEY))) {
                return box;
            }
        }
        return null;
    }

    private static void onEdt(final Runnable body)
        throws InvocationTargetException, InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) {
            body.run();
            return;
        }
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });
        if (thrown.get() != null) {
            if (thrown.get() instanceof AssertionError error) {
                throw error;
            }
            throw new AssertionError(thrown.get());
        }
    }

    private static List<StaticSelector> selectors(final String y, final String checkBox) {
        return List.of(
            StaticSelector.classSelector(DIALOG_CLASS_ALIAS, y),
            StaticSelector.field(REMOTE_CHECKBOX_ALIAS, y,
                "p", "L" + checkBox + ";", 0x001A),
            StaticSelector.method(JCHECKBOX_ALIAS, checkBox,
                "getJCheckBox", "()Ljavax/swing/JCheckBox;", StaticSelector.ACCESS_PUBLIC)
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /** Stands in for {@code com.live2d.ui.control.CCheckBox}. */
    static final class FakeCheckBox {
        private final JCheckBox jcheckBox = new JCheckBox();

        public JCheckBox getJCheckBox() {
            return jcheckBox;
        }
    }

    /** Stands in for {@code com.live2d.cubism.doc.webSocket.y}: static remote-checkbox field. */
    static final class FakeDialogY {
        @SuppressWarnings("unused")
        private static FakeCheckBox p;
    }

    /** Fake host: the native row already parented, mirroring the built dialog state. */
    static final class FakeHost {
        final FakeCheckBox remote = new FakeCheckBox();
        final JPanel row = new JPanel();

        FakeHost() {
            FakeDialogY.p = remote;
            row.add(new JComboBox<String>());   // port
            row.add(new JToggleButton());       // server toggle
            row.add(new JPanel());              // hgrow spacer
            row.add(remote.getJCheckBox());     // remote checkbox
        }

        VerifiedMemberResolver resolver(final String version) {
            return TestVerifiedResolvers.create(
                version,
                "adapter.editor-model.readwrite",
                Set.of(CAPABILITY),
                selectors(internal(FakeDialogY.class), internal(FakeCheckBox.class)),
                FakeHost.class.getClassLoader()
            );
        }
    }
}
