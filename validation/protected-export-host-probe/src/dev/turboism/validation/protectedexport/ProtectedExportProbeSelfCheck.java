package dev.turboism.validation.protectedexport;

import java.awt.Component;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;

/**
 * Offline self-check for the probe's two discrimination rules.
 *
 * <p>The probe decides "which check box did the runtime inject?" and "which button confirms?" purely
 * from the Swing component tree. Both rules are load-bearing, and both would silently produce
 * plausible-but-wrong evidence if they regressed, so they are exercised here on synthetic
 * components before the jar is allowed to exist.</p>
 */
public final class ProtectedExportProbeSelfCheck {

    private ProtectedExportProbeSelfCheck() {
    }

    public static void main(final String[] args) {
        exactClassIsolatesTheInjectedCheckBox();
        actionClassIdentifiesTheRightButton();
        actionClassesDispatchOnExactHostVersion();
        optionsPeerDispatchesOnExactHostVersion();
        nativeLeafIsolatesTheHostCheckBox();
        traversalTerminatesOnCyclesAndDepth();
        System.out.println("[self-check] protected-export probe rules OK");
    }

    /** A subclass of {@code JCheckBox} must not be mistaken for a runtime-injected option. */
    private static void exactClassIsolatesTheInjectedCheckBox() {
        final JPanel root = new JPanel();
        final JPanel nested = new JPanel();
        final JPanel deeper = new JPanel();
        root.add(nested);
        nested.add(deeper);
        deeper.add(new NativeLikeCheckBox("native-a"));
        root.add(new NativeLikeCheckBox("native-b"));
        final JCheckBox injected = new JCheckBox("Protected export (candidate; currently unavailable)");
        deeper.add(injected);

        final List<JCheckBox> found = ProtectedExportHostProbeAgent.injectedCheckBoxes(root);
        require(found.size() == 1, "expected exactly one injected check box, found " + found.size());
        require(found.get(0) == injected, "injected check box identity mismatch");
        require(
            ProtectedExportHostProbeAgent.allComponents(root).size() == 6,
            "unexpected component count " + ProtectedExportHostProbeAgent.allComponents(root).size()
        );
    }

    /** Confirm/cancel are told apart by the action class the native window installs on the button. */
    private static void actionClassIdentifiesTheRightButton() {
        final JPanel root = new JPanel();
        final JButton confirm = new JButton("OK");
        confirm.setAction(new ConfirmAction());
        final JButton cancel = new JButton("Cancel");
        cancel.setAction(new CancelAction());
        final JButton actionless = new JButton("Decorative");
        root.add(confirm);
        root.add(cancel);
        root.add(actionless);

        require(
            ProtectedExportHostProbeAgent.findButtonByAction(
                root, name -> name.equals(ConfirmAction.class.getName())
            ) == confirm,
            "action-class lookup did not return the confirm button"
        );
        require(
            ProtectedExportHostProbeAgent.findButtonByAction(
                root, name -> name.equals(CancelAction.class.getName())
            ) == cancel,
            "action-class lookup did not return the cancel button"
        );
        // The production class names must not match anything here: a false positive would make the
        // probe click an unrelated button on the real host.
        require(
            ProtectedExportHostProbeAgent.findButtonByAction(
                root, name -> name.equals("com.live2d.ui.window.z")
            ) == null,
            "production confirm class name matched a synthetic button"
        );
        require(
            ProtectedExportHostProbeAgent.findButtonByAction(
                root, name -> name.equals("com.live2d.ui.window.A")
            ) == null,
            "production cancel class name matched a synthetic button"
        );
    }

    /**
     * The 5.2.03 window wires confirm/cancel through the {@code C}/{@code B} actions while
     * every reviewed 5.3.x build uses {@code A}/{@code z}. Dispatch must be exact-version —
     * an unknown or missing version property resolves to the 5.3.x classes, never to a
     * heuristic mix.
     */
    private static void actionClassesDispatchOnExactHostVersion() {
        require(
            "com.live2d.ui.window.C".equals(
                ProtectedExportHostProbeAgent.confirmAction("5203")),
            "5.2.03 confirm action must be window.C"
        );
        require(
            "com.live2d.ui.window.B".equals(
                ProtectedExportHostProbeAgent.cancelAction("5203")),
            "5.2.03 cancel action must be window.B"
        );
        for (final String version : new String[] {"5302", "5303", "", "9999"}) {
            require(
                "com.live2d.ui.window.A".equals(
                    ProtectedExportHostProbeAgent.confirmAction(version)),
                "confirm action for " + version + " must be window.A"
            );
            require(
                "com.live2d.ui.window.z".equals(
                    ProtectedExportHostProbeAgent.cancelAction(version)),
                "cancel action for " + version + " must be window.z"
            );
        }
    }

    /**
     * The mount-parent pin is an exact-version dispatch like the button actions:
     * today every reviewed build resolves to {@code com.live2d.ui.swingImpl.u}.
     */
    private static void optionsPeerDispatchesOnExactHostVersion() {
        for (final String version : new String[] {"5203", "5302", "5303", "", "9999"}) {
            require(
                "com.live2d.ui.swingImpl.u".equals(
                    ProtectedExportHostProbeAgent.optionsContainerPeer(version)),
                "options peer for " + version + " must be swingImpl.u"
            );
        }
    }

    /** A subclassed check box is a native leaf; the exact class is the injected row. */
    private static void nativeLeafIsolatesTheHostCheckBox() {
        require(
            ProtectedExportHostProbeAgent.isNativeCheckBoxLeaf(new NativeLikeCheckBox("native")),
            "a JCheckBox subclass must count as a native leaf"
        );
        require(
            !ProtectedExportHostProbeAgent.isNativeCheckBoxLeaf(new JCheckBox("injected")),
            "a plain JCheckBox must never count as a native leaf"
        );
        require(
            !ProtectedExportHostProbeAgent.isNativeCheckBoxLeaf(new JButton("button")),
            "a non-check-box must never count as a native leaf"
        );
    }

    /** Traversal must terminate on a genuine cycle and refuse to recurse without bound. */
    private static void traversalTerminatesOnCyclesAndDepth() {
        require(
            ProtectedExportHostProbeAgent.allComponents(new SelfReferencingPanel()).size() == 1,
            "a self-referencing container must be visited exactly once"
        );
        final JPanel shared = new JPanel();
        final JPanel diamond = new JPanel();
        diamond.add(shared);
        diamond.add(shared);
        require(
            ProtectedExportHostProbeAgent.allComponents(diamond).size() == 2,
            "a shared child reachable by two paths must be visited once"
        );

        JPanel deep = new JPanel();
        final JPanel top = deep;
        for (int i = 0; i < 200; i++) {
            final JPanel child = new JPanel();
            deep.add(child);
            deep = child;
        }
        require(
            ProtectedExportHostProbeAgent.allComponents(top).size() == 41,
            "depth guard misbehaved"
        );
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** Reports itself as its own child, which is the only way to build a real containment cycle. */
    private static final class SelfReferencingPanel extends JPanel {
        private static final long serialVersionUID = 1L;

        @Override
        public Component[] getComponents() {
            return new Component[] { this };
        }
    }

    /** Stands in for Cubism's {@code com.live2d.ui.control.CCheckBox} wrapper. */
    private static final class NativeLikeCheckBox extends JCheckBox {
        private static final long serialVersionUID = 1L;

        private NativeLikeCheckBox(final String text) {
            super(text);
        }
    }

    private static final class ConfirmAction extends AbstractAction {
        private static final long serialVersionUID = 1L;

        @Override
        public void actionPerformed(final java.awt.event.ActionEvent event) {
            // No-op: the self-check only needs the action's class identity.
        }
    }

    private static final class CancelAction extends AbstractAction {
        private static final long serialVersionUID = 1L;

        @Override
        public void actionPerformed(final java.awt.event.ActionEvent event) {
            // No-op: the self-check only needs the action's class identity.
        }
    }
}
